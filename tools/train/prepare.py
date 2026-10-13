#!/usr/bin/env python3
"""Convert the downloaded datasets into PulseStages night files (contract: INTERCHANGE.md section 1) under
OHealth-like conditions.

For every night four variants are written to data/nights/<variant>/<stem>.night.json:

    instantaneous      HR: on a 2-min grid (deterministic per-night phase), the raw sample nearest to each grid
                       point within ±30 s (else no value); session = OHealth-like window
    mean2min           HR: mean of the raw samples in each 2-min window, stamped at the window centre;
                       session = OHealth-like window
    instantaneous-psg  as instantaneous, session = full scored PSG / label range
    mean2min-psg       as mean2min,      session = full scored PSG / label range

OHealth-like session: first non-wake epoch − 5 min .. end of the last non-wake epoch + 5 min, clipped to the scored
range. HR and steps cover [sessionStart − 30 min, sessionEnd + 30 min] where the recording has data. Labels: scored
30-s epochs inside the session, 4 classes (N1+N2 -> LIGHT, N3(+N4) -> DEEP); unscored epochs are omitted. Steps:
conservative pedometer emulation on the raw accelerometer (pulsetrain/pedometer.py), 1-min samples with count > 0.

Datasets:
    walch     PhysioNet sleep-accel 1.0.0; subject = Walch id ("46343"), night id "walch-<id>-<variant>",
              file <id>.night.json; times in ms since PSG start (negative = before it).
    bidsleep  PhysioNet BIDSleep 1.0.1, EXPERT-CORRECTED labels; subject "bid-<NN>" (folder Bidslab<NN>), one file
              per night bid-<NN>-<night>.night.json, night id "bid-<NN>-<night>-<variant>"; Unix epoch ms.

Also writes data/nights/summary.csv (one row per night and variant, plus rows for skipped nights),
data/nights/steps_comparison_walch.csv (accelerometer vs Apple Watch steps) and data/nights/manifest.json.

Reads untrusted data: run with ``python -I`` (this script adds only its own directory to sys.path).
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
from concurrent.futures import ProcessPoolExecutor
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

TRAIN_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TRAIN_DIR))  # our own package only; `python -I` does not add the script directory

import numpy as np  # noqa: E402

from pulsetrain import bidsleep, ohealth, walch  # noqa: E402
from pulsetrain.pedometer import PedometerParams, StepDetection, detect_steps, steps_per_minute  # noqa: E402

RAW_DIR = TRAIN_DIR / "data" / "raw"
OUT_DIR = TRAIN_DIR / "data" / "nights"
WALCH_ROOT = RAW_DIR / "sleep-accel" / "1.0.0"
BID_ROOT = RAW_DIR / "bidsleep-dataset" / "1.0.1"

HR_VARIANTS = {"instantaneous": "inst", "mean2min": "mean2min"}
SESSION_SUFFIX = {"ohealth": "", "psg": "-psg"}
VARIANTS = [hv + suf for suf in SESSION_SUFFIX.values() for hv in HR_VARIANTS]
FILE_PATTERNS = {"walch": re.compile(r"^\d+\.night\.json$"), "bidsleep": re.compile(r"^bid-[\w-]+\.night\.json$")}
SUMMARY_FIELDS = [
    "dataset", "subject", "night", "night_id", "variant", "session_start_ms", "session_end_ms", "session_minutes",
    "hr_samples_session", "hr_samples_file", "coverage_proxy", "label_minutes", "awake_min", "light_min",
    "deep_min", "rem_min", "steps_total", "steps_file", "notes",
]
DATASET_INFO = {
    "walch": {
        "name": "PhysioNet sleep-accel (Walch et al. 2019)", "version": "1.0.0",
        "url": "https://physionet.org/content/sleep-accel/1.0.0/",
        "license": "Open Data Commons Attribution License v1.0",
        "citation": "Walch, O. (2019). Motion and heart rate from a wrist-worn wearable and labeled sleep from "
                    "polysomnography (version 1.0.0). PhysioNet. https://doi.org/10.13026/hmhs-py35",
        "labels": "PSG 30-s epochs: 0 wake, 1 N1, 2 N2, 3 N3, 4 N4, 5 REM, -1 unscored",
    },
    "bidsleep": {
        "name": "PhysioNet BIDSleep (Song et al. 2026)", "version": "1.0.1",
        "url": "https://physionet.org/content/bidsleep-dataset/1.0.1/",
        "license": "Open Data Commons Attribution License v1.0",
        "citation": "Song, T., Zhang, Y., Zhou, Z., & Dutta, J. (2026). A Multi-Night Instantaneous Heart Rate and "
                    "Accelerometry Dataset with EEG Sleep Stage Labels (version 1.0.1). PhysioNet. "
                    "https://doi.org/10.13026/rees-1092",
        "labels": "Dreem 2 EEG, expert-corrected 30-s epochs (expert_label): 0 wake, 1 N1, 2 N2, 3 N3, 4 REM, "
                  "5 unknown",
    },
}


@dataclass
class Recording:
    dataset: str
    subject: str
    night: str
    night_key: str               # night id prefix, variant suffix appended
    file_stem: str
    phase_key: str               # seed of the 2-min grid phase
    epochs: list
    hr_t_ms: np.ndarray
    hr_bpm: np.ndarray
    motion: np.ndarray | None    # (n, 4): t seconds, x, y, z (g)
    motion_missing_note: str = "motion missing"
    apple_steps: tuple[np.ndarray, np.ndarray] | None = None
    notes: list[str] = field(default_factory=list)


@dataclass
class NightResult:
    dataset: str
    rows: list[dict]
    written: list[str]
    steps_comparison: dict | None = None


# ------------------------------------------------------------------------------------------------- loading ----

def load_walch(subject: str) -> Recording:
    w = walch.load_subject(WALCH_ROOT, subject, with_motion=True)
    notes = []
    d = np.diff(w.label_t_s)
    if d.size and not np.all(d == 30):
        notes.append(f"label rows not 30 s apart ({int(np.sum(d != 30))}x)")
    epochs = ohealth.label_epochs(w.label_t_s, w.label_code, mapping=ohealth.WALCH_CODE_TO_STAGE)
    if any(e.code == 4 for e in epochs):
        notes.append("R&K N4 epochs present (mapped to DEEP)")
    return Recording(
        dataset="walch", subject=subject, night="1", night_key=f"walch-{subject}", file_stem=subject,
        phase_key=subject, epochs=epochs,
        hr_t_ms=np.round(w.hr_t_s * 1000.0).astype(np.int64), hr_bpm=w.hr_bpm,
        motion=w.motion, motion_missing_note="motion file missing",
        apple_steps=(w.steps_t_s, w.steps_count), notes=notes,
    )


def load_bid(subject_dir: str, night: str) -> Recording:
    b = bidsleep.load_night(BID_ROOT, subject_dir, night)
    epochs = []
    if b.rec_start_ms is not None and b.expert_label.size:
        epochs = ohealth.label_epochs_from_start(b.rec_start_ms, b.expert_label, ohealth.BIDSLEEP_CODE_TO_STAGE)
    motion = bidsleep.load_motion(BID_ROOT, subject_dir, night)
    key = f"{b.subject}-{night}"
    return Recording(
        dataset="bidsleep", subject=b.subject, night=night, night_key=key, file_stem=key, phase_key=key,
        epochs=epochs, hr_t_ms=np.round(b.hr_t_s * 1000.0).astype(np.int64), hr_bpm=b.hr_bpm,
        motion=motion, motion_missing_note="motion.csv not downloaded -> steps empty", notes=list(b.notes),
    )


# ---------------------------------------------------------------------------------------------- processing ----

def _segments_coverage(det: StepDetection | None, start_ms: int, end_ms: int) -> float:
    if det is None or end_ms <= start_ms:
        return 0.0
    covered = 0.0
    for a, b in det.segments:
        covered += max(0.0, min(b * 1000.0, end_ms) - max(a * 1000.0, start_ms))
    return covered / (end_ms - start_ms)


def _longest_hr_gap_min(t_ms: np.ndarray, start_ms: int, end_ms: int) -> float:
    inside = t_ms[(t_ms >= start_ms) & (t_ms <= end_ms)]
    pts = np.concatenate(([start_ms], inside, [end_ms]))
    return float(np.max(np.diff(pts))) / 60_000.0


def _steps_by_stage(step_times_s: np.ndarray, epochs: list) -> tuple[int, int]:
    """Counted steps falling into scored sleep epochs / scored wake epochs."""
    if step_times_s.size == 0 or not epochs:
        return 0, 0
    starts = np.array([e.start_ms for e in epochs])
    st = step_times_s * 1000.0
    idx = np.searchsorted(starts, st, side="right") - 1
    sleep = wake = 0
    for i, t in zip(idx, st):
        if 0 <= i < len(epochs) and t < epochs[i].end_ms and epochs[i].stage is not None:
            if epochs[i].stage == "AWAKE":
                wake += 1
            else:
                sleep += 1
    return sleep, wake


def _skip_row(rec: Recording, reason: str) -> dict:
    row = {k: "" for k in SUMMARY_FIELDS}
    row.update(dataset=rec.dataset, subject=rec.subject, night=rec.night, night_id=rec.night_key,
               notes="; ".join(["SKIPPED: " + reason] + rec.notes))
    return row


def process(rec: Recording, out_dir: Path, pedo: PedometerParams) -> NightResult:
    res = NightResult(dataset=rec.dataset, rows=[], written=[])
    s_oh = ohealth.ohealth_session(rec.epochs)
    s_psg = ohealth.psg_session(rec.epochs)
    hr_t, hr_v = ohealth.clean_hr(rec.hr_t_ms, rec.hr_bpm)
    if s_psg is None:
        res.rows.append(_skip_row(rec, "no usable labels (no scored epoch)"))
        return res
    if s_oh is None:
        res.rows.append(_skip_row(rec, "no scored sleep epoch"))
        return res
    if hr_t.size == 0:
        res.rows.append(_skip_row(rec, "no HR samples"))
        return res

    notes = list(rec.notes)
    det = None
    steps_min: list[list[int]] = []
    if rec.motion is None or rec.motion.shape[0] == 0:
        notes.append(rec.motion_missing_note)
    else:
        m = rec.motion
        det = detect_steps(m[:, 0], m[:, 1], m[:, 2], m[:, 3], pedo)
        steps_min = steps_per_minute(det.step_times_s)
    phase = ohealth.grid_phase_ms(rec.phase_key)

    for sdef, (s0, s1) in (("ohealth", s_oh), ("psg", s_psg)):
        ctx0, ctx1 = s0 - ohealth.CONTEXT_MS, s1 + ohealth.CONTEXT_MS
        labels = ohealth.session_labels(rec.epochs, s0, s1)
        mins = ohealth.stage_minutes(labels)
        steps = [s for s in steps_min if s[1] > ctx0 and s[0] < ctx1]
        steps_session = sum(c for a, _, c in steps if s0 <= a < s1)
        snotes = list(notes)
        unscored = sum(1 for e in rec.epochs if e.stage is None and e.start_ms >= s0 and e.end_ms <= s1)
        if unscored:
            snotes.append(f"{unscored} unscored epochs in session (omitted)")
        for st in ohealth.STAGES:
            if mins[st] == 0:
                snotes.append(f"no {st}")
        if det is not None:
            cov = _segments_coverage(det, s0, s1)
            if cov < 0.95:
                snotes.append(f"motion covers {cov:.0%} of session")
        gap = _longest_hr_gap_min(hr_t, s0, s1)
        if gap > 10:
            snotes.append(f"longest raw HR gap in session {gap:.0f} min")
        for hv, short in HR_VARIANTS.items():
            variant = hv + SESSION_SUFFIX[sdef]
            hr = ohealth.VARIANT_RESAMPLERS[hv](hr_t, hr_v, ctx0, ctx1, phase)
            n_sess = sum(1 for t, _ in hr if s0 <= t <= s1)
            night_id = f"{rec.night_key}-{short}{SESSION_SUFFIX[sdef]}"
            row = {
                "dataset": rec.dataset, "subject": rec.subject, "night": rec.night, "night_id": night_id,
                "variant": variant, "session_start_ms": s0, "session_end_ms": s1,
                "session_minutes": round((s1 - s0) / 60_000.0, 1),
                "hr_samples_session": n_sess, "hr_samples_file": len(hr),
                "coverage_proxy": round(ohealth.coverage_proxy(hr, s0, s1), 3),
                "label_minutes": round(sum(mins.values()), 1),
                "awake_min": round(mins["AWAKE"], 1), "light_min": round(mins["LIGHT"], 1),
                "deep_min": round(mins["DEEP"], 1), "rem_min": round(mins["REM"], 1),
                "steps_total": steps_session if det is not None else "",   # blank = no motion data
                "steps_file": sum(c for _, _, c in steps) if det is not None else "",
                "notes": "; ".join(snotes),
            }
            if n_sess == 0:
                row["notes"] = "; ".join(["SKIPPED: no HR in session"] + snotes)
                res.rows.append(row)
                continue
            night = {
                "id": night_id,
                "subject": rec.subject,
                "variant": variant,
                "sessionStartMs": int(s0),
                "sessionEndMs": int(s1),
                "hr": hr,
                "steps": steps,
                "labels": labels,
            }
            path = out_dir / variant / f"{rec.file_stem}.night.json"
            tmp = path.with_suffix(".json.part")
            tmp.write_text(json.dumps(night, separators=(",", ":")) + "\n", encoding="utf-8")
            os.replace(tmp, path)
            res.written.append(str(path))
            res.rows.append(row)

    if rec.dataset == "walch" and rec.apple_steps is not None:
        s0, s1 = s_oh
        ctx0, ctx1 = s0 - ohealth.CONTEXT_MS, s1 + ohealth.CONTEXT_MS
        a_t, a_c = rec.apple_steps
        ap_s, ap_bins = walch.apple_steps_in_window(a_t, a_c, s0, s1)
        ap_c, _ = walch.apple_steps_in_window(a_t, a_c, ctx0, ctx1)
        st = det.step_times_s * 1000.0 if det is not None else np.empty(0)
        in_sleep, in_wake = _steps_by_stage(det.step_times_s if det is not None else np.empty(0), rec.epochs)
        res.steps_comparison = {
            "subject": rec.subject,
            "session_minutes": round((s1 - s0) / 60_000.0, 1),
            "accel_steps_session": int(np.sum((st >= s0) & (st < s1))),
            "apple_steps_session": round(ap_s, 1),
            "apple_bins_session": ap_bins,
            "accel_steps_context": int(np.sum((st >= ctx0) & (st < ctx1))),
            "apple_steps_context": round(ap_c, 1),
            "motion_coverage_context": round(_segments_coverage(det, ctx0, ctx1), 3),
            "accel_steps_in_psg_sleep_epochs": in_sleep,
            "accel_steps_in_psg_wake_epochs": in_wake,
            "accel_bouts_whole_recording": len(det.bouts) if det is not None else 0,
        }
    return res


def _work(task: tuple[str, tuple[str, ...]], out_dir: str, pedo: PedometerParams) -> NightResult:
    dataset, ids = task
    try:
        rec = load_walch(*ids) if dataset == "walch" else load_bid(*ids)
    except Exception as exc:  # noqa: BLE001 - one broken night must not stop the run
        row = {k: "" for k in SUMMARY_FIELDS}
        key = f"walch-{ids[0]}" if dataset == "walch" else f"{bidsleep.subject_key(ids[0])}-{ids[1]}"
        row.update(dataset=dataset, subject=ids[0] if dataset == "walch" else bidsleep.subject_key(ids[0]),
                   night=ids[-1] if dataset == "bidsleep" else "1", night_id=key,
                   notes=f"SKIPPED: unreadable ({type(exc).__name__}: {exc})")
        return NightResult(dataset=dataset, rows=[row], written=[])
    return process(rec, Path(out_dir), pedo)


# ------------------------------------------------------------------------------------------------- outputs ----

def _merge_csv(path: Path, fields: list[str], new_rows: list[dict], datasets: set[str], key: str = "dataset") -> None:
    rows = []
    if path.exists():
        with open(path, newline="", encoding="utf-8") as fh:
            rows = [r for r in csv.DictReader(fh) if r.get(key) not in datasets]
    rows.extend(new_rows)
    rows.sort(key=lambda r: (str(r.get("dataset", "")), _natural(str(r.get("subject", ""))),
                             _natural(str(r.get("night", ""))), str(r.get("variant", ""))))
    tmp = path.with_suffix(".csv.part")
    with open(tmp, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    os.replace(tmp, path)


def _natural(s: str) -> tuple:
    return tuple(int(p) if p.isdigit() else p for p in re.split(r"(\d+)", s))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", choices=["walch", "bidsleep", "all"], default="all",
                    help="'all' = every dataset whose raw data is present (default)")
    ap.add_argument("--out", default=str(OUT_DIR))
    ap.add_argument("--jobs", type=int, default=min(4, os.cpu_count() or 1))
    ap.add_argument("--limit", type=int, default=0, help="process only the first N nights per dataset (debug)")
    args = ap.parse_args(argv)

    out_dir = Path(args.out).resolve()
    wanted = ["walch", "bidsleep"] if args.dataset == "all" else [args.dataset]
    tasks: list[tuple[str, tuple[str, ...]]] = []
    datasets: set[str] = set()
    for ds in wanted:
        if ds == "walch":
            if not (WALCH_ROOT / "labels").is_dir():
                print(f"walch: raw data not found under {WALCH_ROOT} (run download.py)")
                continue
            ids = [(s,) for s in walch.list_subjects(WALCH_ROOT)]
        else:
            if not BID_ROOT.is_dir():
                print(f"bidsleep: raw data not found under {BID_ROOT} (run download.py --dataset bidsleep)")
                continue
            ids = bidsleep.list_nights(BID_ROOT)
        if args.limit:
            ids = ids[: args.limit]
        tasks += [(ds, i) for i in ids]
        datasets.add(ds)
    if not tasks:
        print("nothing to do")
        return 1

    for v in VARIANTS:
        vdir = out_dir / v
        vdir.mkdir(parents=True, exist_ok=True)
        for f in vdir.iterdir():  # remove this dataset's previous outputs (stale nights)
            if any(FILE_PATTERNS[ds].match(f.name) for ds in datasets):
                f.unlink()

    pedo = PedometerParams()
    results: list[NightResult] = []
    with ProcessPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        futs = [pool.submit(_work, t, str(out_dir), pedo) for t in tasks]
        for i, fut in enumerate(futs, 1):
            r = fut.result()
            results.append(r)
            skipped = [row for row in r.rows if row["notes"].startswith("SKIPPED")]
            tag = "skipped" if skipped and not r.written else "ok"
            print(f"[{i:3d}/{len(tasks)}] {r.rows[0]['night_id']:<22s} {tag}"
                  + (f"  ({skipped[0]['notes'][:90]})" if skipped else ""), flush=True)

    rows = [row for r in results for row in r.rows]
    _merge_csv(out_dir / "summary.csv", SUMMARY_FIELDS, rows, datasets)
    comps = [r.steps_comparison for r in results if r.steps_comparison]
    if "walch" in datasets and comps:
        fields = list(comps[0].keys())
        comps.sort(key=lambda c: int(c["subject"]))
        with open(out_dir / "steps_comparison_walch.csv", "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=fields)
            w.writeheader()
            w.writerows(comps)

    manifest_path = out_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    manifest.setdefault("datasets", {})
    for ds in datasets:
        ds_rows = [r for r in rows if r["dataset"] == ds]
        manifest["datasets"][ds] = {
            **DATASET_INFO[ds],
            "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "subjects": len({r["subject"] for r in ds_rows}),
            "nightsSeen": len({(r["subject"], r["night"]) for r in ds_rows}),
            "nightsWritten": len({(r["subject"], r["night"]) for r in ds_rows
                                  if r["variant"] and not r["notes"].startswith("SKIPPED")}),
            "filesPerVariant": {v: sum(1 for r in ds_rows if r["variant"] == v and not r["notes"].startswith("SKIPPED"))
                                for v in VARIANTS},
            "skipped": sorted({f"{r['subject']}/{r['night']}: {r['notes'].split(';')[0]}" for r in ds_rows
                               if r["notes"].startswith("SKIPPED")}),
        }
    manifest["params"] = {
        "gridMs": ohealth.GRID_MS, "nearestToleranceMs": ohealth.NEAREST_TOL_MS,
        "sessionMarginMs": ohealth.SESSION_MARGIN_MS, "contextMs": ohealth.CONTEXT_MS,
        "gridPhase": "sha256('pulsestages/ohealth-grid/' + night key)[:8] mod gridMs (Walch: subject id, "
                     "BIDSleep: bid-<NN>-<night>)",
        "pedometer": asdict(pedo),
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    for ds in sorted(datasets):
        info = manifest["datasets"][ds]
        print(f"{ds}: subjects={info['subjects']} nights={info['nightsSeen']} written={info['nightsWritten']} files/variant={info['filesPerVariant']}"
              f" skipped={len(info['skipped'])}")
    print(f"summary: {out_dir / 'summary.csv'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
