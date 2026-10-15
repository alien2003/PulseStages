#!/usr/bin/env python3
"""OpenNeuro ds006576, the external test set of the pre-registration naps-realistic (docs/prereg/
prereg-naps-realistic.md, sections 13.2-13.6): fetch and reduce the recordings, build the night files, freeze them.
Test only; no PulseStages model is involved anywhere in this script. Training environment (``mise exec``).

    mise exec -- python -I tools/train/external.py fetch --data tools/train/data/external/ds006576 --workers 4
    tools/train/.venv-external/bin/python -I tools/train/external_stage.py --data tools/train/data/external/ds006576
    mise exec -- python -I tools/train/external.py nights --data tools/train/data/external/ds006576   # digests only

``fetch``   the snapshot pin first (DatasetDOI, the first CHANGES entry, the 65 ``.eeg`` objects and sizes of the
            committed key list ``external-ds006576-v1.0.5.keys.tsv``, their groups and RecordingDuration), before any
            ``.eeg`` byte is read; then every ``.eeg`` streamed in 44 851 200-byte range requests (HTTP 206, exact
            Content-Range and length), its SHA-256 computed on the fly, reduced to ``derived/sub-<id>.npz``
            (float32, no pickle) with ``derived/sub-<id>.json``. Raw bytes are never stored. Resumable per recording.
``nights``  heart rate (frozen R-peak detector), the oh / oh1 / m60 / rec windows from the YASA stages, the night
            files ``nights/<variant>/ds006576-sub-<id>-<window>.night.json``, ``manifest.json`` and
            ``yasa-summary.json``. Prints and logs **only** the two digests (``NOTE ds006576 manifest sha256 …``,
            ``NOTE ds006576 yasa-summary sha256 …``); refuses if either NOTE is already in the log, unless
            ``--reproduce``, which rebuilds into a new directory, compares the digests with the logged ones and logs
            the outcome (the frozen files are never touched).
``keys``    the committed key list from public metadata only (S3 listing sizes, ``*_eeg.json`` RecordingDuration,
            ``participants.tsv`` groups); never an ``.eeg`` byte.
``lock``    ``requirements-external.lock`` from a ``pip install --dry-run --report`` resolution (hashes) plus the
            digest comment lines; ``lock-check`` checks every pinned release against PyPI (upload dates, hashes).

``fetch``, ``nights`` (and ``external_stage.py``) append their own START / END lines to ``tools/train/out/
commands.log`` (``--log``): the order of these lines is checked by gate G9 (``pulsetrain.ds006576.freeze_order``).
Everything downloaded is untrusted data, parsed only; run with ``python -I``.
"""

from __future__ import annotations

import argparse
import json
import shlex
import shutil
import sys
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

TRAIN_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TRAIN_DIR))  # our own package only; `python -I` does not add the script directory

import numpy as np  # noqa: E402

from pulsetrain import ds006576 as ds  # noqa: E402
from pulsetrain.util import write_json  # noqa: E402


def command_text(argv: list[str], script: str = "external.py") -> str:
    return " ".join(["python -I", f"tools/train/{script}", *(shlex.quote(a) for a in argv)])


class LoggedRun:
    """START / END lines of one command in the commands log."""

    def __init__(self, log: Path | None, argv: list[str], script: str = "external.py"):
        self.log, self.cmd = log, command_text(argv, script)
        self.t0 = time.monotonic()

    def __enter__(self):
        if self.log is not None:
            ds.append_log(self.log, f"START {self.cmd}")
        return self

    def end(self, rc: int) -> int:
        if self.log is not None:
            ds.append_log(self.log, f"END rc={rc} {time.monotonic() - self.t0:.0f}s {self.cmd}")
        return rc

    def __exit__(self, exc_type, exc, tb):
        if exc_type is not None:
            self.end(1)
        return False


# ================================================================================================= keys =====

def build_keys(base_url: str = ds.BASE_URL, opener=ds.urlopen) -> tuple[list[ds.KeyRow], list[str]]:
    """The key list of the current snapshot from public metadata; returns (rows, pin failures of DOI / CHANGES)."""
    listing = ds.list_bucket(f"{ds.DATASET}/", base_url=base_url, opener=opener)
    sizes = ds.eeg_sizes(listing)
    desc = json.loads(ds.get_bytes(f"{base_url}/{ds.DATASET}/dataset_description.json", opener=opener))
    changes = ds.get_bytes(f"{base_url}/{ds.DATASET}/CHANGES", opener=opener).decode("utf-8")
    groups = ds.parse_participants(ds.get_bytes(f"{base_url}/{ds.DATASET}/participants.tsv",
                                                opener=opener).decode("utf-8"))
    rows = []
    for subject in sorted(sizes):
        js = json.loads(ds.get_bytes(f"{base_url}/{ds.sidecar_key(subject, 'eeg.json')}", opener=opener),
                        parse_int=str, parse_float=str)
        rows.append(ds.KeyRow(subject, groups.get(subject, "?"), sizes[subject], str(js["RecordingDuration"])))
    fails = [f for f in ds.snapshot_pin(desc, changes, sizes, rows) if "DatasetDOI" in f or "CHANGES" in f]
    return rows, fails


def compare_with_pilot_audit(rows: list[ds.KeyRow], audit_path: Path) -> list[str]:
    """Differences between the key list and the pilot's header audit (sizes, durations, groups)."""
    audit = {r["sub"]: r for r in json.loads(Path(audit_path).read_text(encoding="utf-8"))}
    diffs = []
    if set(audit) != {r.subject for r in rows}:
        diffs.append("subjects differ")
    for r in rows:
        a = audit.get(r.subject)
        if a and (a["bytes"] != r.bytes or Decimal(str(a["durationS"])) != Decimal(r.duration_s)
                  or a["group"] != r.group):
            diffs.append(f"{r.subject}: audit {a['bytes']} / {a['durationS']} / {a['group']}")
    return diffs


def cmd_keys(a: argparse.Namespace) -> int:
    rows, fails = build_keys(a.base_url)
    if fails:
        print("not the pre-registered snapshot:", "; ".join(fails))
        return 3
    counts = {g: sum(1 for r in rows if r.group == g) for g in ds.GROUP_COUNTS}
    if len(rows) != ds.N_EEG_OBJECTS or counts != ds.GROUP_COUNTS:
        print(f"{len(rows)} .eeg objects {counts}, expected {ds.N_EEG_OBJECTS} {ds.GROUP_COUNTS}")
        return 3
    if a.pilot_audit:
        diffs = compare_with_pilot_audit(rows, a.pilot_audit)
        if diffs:
            print("differs from the pilot's header audit:", "; ".join(diffs[:5]))
            return 3
    comments = [
        f"OpenNeuro {ds.DATASET} v1.0.5 ({ds.DATASET_DOI}, CC0): the {len(rows)} BrainVision .eeg objects of the",
        "snapshot (pre-registration naps-realistic, section 13.2; the snapshot pin compares the bucket listing with",
        "this list before any .eeg byte is read). Public metadata only: size in bytes from the S3 listing,",
        "RecordingDuration (s) from sub-<id>/eeg/sub-<id>_task-rest_eeg.json, group from participants.tsv.",
        f"Written by `external.py keys` on {datetime.now(timezone.utc).date().isoformat()}"
        + ("; equal to the pilot's header audit" if a.pilot_audit else "") + ".",
        "A file is truncated when bytes is not a multiple of 292 or differs from recordingDurationS x 512 x 292.",
    ]
    Path(a.out).write_text(ds.format_keys(rows, comments), encoding="utf-8")
    print(f"{a.out}: {len(rows)} rows {counts}; truncated: {[r.subject for r in rows if r.truncated]}")
    return 0


# ================================================================================================ fetch =====

def fetch_metadata(data: Path, keys: list[ds.KeyRow], base_url: str, opener=ds.urlopen) -> tuple[list[str], dict]:
    """Downloads the public metadata (never an ``.eeg`` byte) into ``data/meta`` and runs the snapshot pin.
    Returns (pin failures, {subject: vhdr text})."""
    meta = data / "meta"
    meta.mkdir(parents=True, exist_ok=True)
    top = {}
    for name in ds.TOP_LEVEL_FILES:
        top[name] = ds.get_bytes(f"{base_url}/{ds.DATASET}/{name}", opener=opener)
        (meta / name).write_bytes(top[name])
    listing = ds.list_bucket(f"{ds.DATASET}/", base_url=base_url, opener=opener)
    (meta / "listing.tsv").write_text("".join(f"{k}\t{v}\n" for k, v in sorted(listing.items())), encoding="utf-8")
    vhdrs, durations = {}, {}
    for k in keys:
        sub = meta / k.subject
        sub.mkdir(exist_ok=True)
        for suffix in ("eeg.vhdr", "eeg.json", "channels.tsv"):
            b = ds.get_bytes(f"{base_url}/{ds.sidecar_key(k.subject, suffix)}", opener=opener)
            (sub / f"{k.subject}_task-rest_{suffix}").write_bytes(b)
            if suffix == "eeg.vhdr":
                vhdrs[k.subject] = b.decode("utf-8", errors="replace")
            elif suffix == "eeg.json":
                durations[k.subject] = str(json.loads(b, parse_int=str, parse_float=str)["RecordingDuration"])
    fails = ds.snapshot_pin(json.loads(top["dataset_description.json"]), top["CHANGES"].decode("utf-8"),
                            ds.eeg_sizes(listing), keys, ds.parse_participants(top["participants.tsv"].decode()),
                            durations)
    return fails, vhdrs


def fetch_one(data: Path, key: ds.KeyRow, vhdr_text: str, base_url: str, opener=ds.urlopen, retries: int = 8,
              sleep=time.sleep) -> dict:
    """Streams and reduces one recording (or records why it is excluded); writes the npz, then the record."""
    record_path = data / ds.record_relpath(key.subject)
    rec = {"subject": key.subject, "group": key.group, "key": ds.sidecar_key(key.subject, "eeg.eeg"),
           "bytes": key.bytes, "recordingDurationS": key.duration_s, "truncated": key.truncated, "excluded": None}
    layout, reason = ds.channel_layout(ds.parse_vhdr(vhdr_text))
    if layout is None:
        rec["excluded"] = f"channels: {reason}"
        write_json(record_path, rec)
        return rec
    url = f"{base_url}/{rec['key']}"
    arrays, digest, frames = ds.stream_recording(url, key.bytes, layout, opener=opener, retries=retries,
                                                 sleep=sleep)
    ds.save_derived(data / ds.derived_relpath(key.subject), arrays)
    rec.update({
        "sha256": digest, "completeFrames": frames, "fs": {"ecg": ds.FS_RAW, "eeg": ds.FS_EEG}, "unit": "uV",
        "channels": {label: {"index": i, "scaleToMicrovolt": s} for label, (i, s) in layout.items()},
        "derived": {name: {"shape": list(a.shape), "dtype": a.dtype.str} for name, a in arrays.items()},
        "derivedSha256": ds.arrays_digest(arrays),
    })
    write_json(record_path, rec)
    return rec


def fetched(data: Path, key: ds.KeyRow) -> bool:
    p = data / ds.record_relpath(key.subject)
    if not p.is_file():
        return False
    rec = json.loads(p.read_text(encoding="utf-8"))
    if rec.get("bytes") != key.bytes:
        return False
    return rec.get("excluded") is not None or (rec.get("sha256") is not None
                                               and (data / ds.derived_relpath(key.subject)).is_file())


def cmd_fetch(a: argparse.Namespace, argv: list[str]) -> int:
    data = Path(a.data).resolve()
    keys = ds.read_keys(a.keys)
    with LoggedRun(a.log, argv) as run:
        fails, vhdrs = fetch_metadata(data, keys, a.base_url)
        write_json(data / "pin.json", {"ok": not fails, "failures": fails, "doi": ds.DATASET_DOI,
                                       "changesFirstEntry": list(ds.CHANGES_FIRST_ENTRY),
                                       "keyListSha256": ds.sha256_file(a.keys)})
        if fails:
            print("snapshot pin FAILED, no .eeg byte read (the external test is void):")
            for f in fails:
                print("  " + f)
            if a.log is not None:
                ds.append_log(a.log, "NOTE ds006576 snapshot pin failed (external test void): " + "; ".join(fails))
            return run.end(3)
        todo = [k for k in keys if not fetched(data, k)]
        print(f"snapshot pin ok; {len(keys) - len(todo)} recordings already reduced, {len(todo)} to stream")
        errors = []
        t0 = time.monotonic()
        with ThreadPoolExecutor(max_workers=max(1, a.workers)) as pool:
            futs = {pool.submit(fetch_one, data, k, vhdrs[k.subject], a.base_url): k for k in todo}
            for i, fut in enumerate(as_completed(futs), 1):
                k = futs[fut]
                try:
                    rec = fut.result()
                    tag = rec["excluded"] or ("truncated" if rec["truncated"] else "ok")
                except (ds.ExternalError, OSError, ValueError) as e:
                    errors.append(f"{k.subject}: {e}")
                    tag = f"FAILED ({e})"
                print(f"[{i:2d}/{len(todo)}] {k.subject} {k.group:4s} {k.bytes / 1e6:7.1f} MB {tag} "
                      f"({time.monotonic() - t0:.0f} s)", flush=True)
        if errors:
            print(f"{len(errors)} recordings failed; re-run to resume")
            return run.end(1)
        return run.end(0)


# =============================================================================================== nights =====

def build_nights(data: Path, out: Path, keys: list[ds.KeyRow], lock: ds.Lock) -> tuple[bytes, bytes]:
    """Night files, manifest and YASA summary of every recording into ``out``; returns (manifest, summary) bytes."""
    files: dict[str, str] = {}
    subjects: dict[str, dict] = {}
    minutes: dict[str, dict] = {}
    identity = None
    for k in keys:
        rec_path = data / ds.record_relpath(k.subject)
        if not rec_path.is_file():
            raise ds.ExternalError(f"{k.subject} was not fetched")
        rec = json.loads(rec_path.read_text(encoding="utf-8"))
        entry = {"group": k.group, "truncated": k.truncated, "excluded": rec.get("excluded"),
                 "eegSha256": rec.get("sha256"), "derivedSha256": rec.get("derivedSha256")}
        subjects[k.subject] = entry
        if entry["excluded"]:
            continue
        stage_path = data / ds.stage_relpath(k.subject)
        if not stage_path.is_file():
            raise ds.ExternalError(f"{k.subject} has no stage file (run external_stage.py)")
        stage_raw = stage_path.read_bytes()
        stage = json.loads(stage_raw)
        bad = ds.check_stage_file(stage, lock)
        if bad:
            raise ds.ExternalError(f"{k.subject}: stage file not from the locked YASA environment: {bad}")
        if identity is None:
            identity = stage["yasa"]
        elif stage["yasa"] != identity:
            raise ds.ExternalError(f"{k.subject}: stage files from different YASA environments")
        arrays = ds.load_derived(data / ds.derived_relpath(k.subject))
        if ds.arrays_digest(arrays) != rec["derivedSha256"] or stage.get("derivedSha256") != rec["derivedSha256"]:
            raise ds.ExternalError(f"{k.subject}: derived data changed since fetch / staging")
        entry["stagesSha256"] = ds.sha256_bytes(stage_raw)
        ecg = arrays["ecg"].astype(np.float64)
        er = ds.ecg_rr(ecg, ds.FS_RAW)
        t_ms, bpm = ds.raw_hr_samples(er.rr_mid_ms, er.rr_s, int(len(ecg) * 1000 // ds.FS_RAW))
        stages = stage["stages"]
        windows = ds.windows_from_stages(stages, er.valid_range_ms, k.group)
        if er.valid_range_ms is None:
            entry["excluded"] = "no good ECG chunk"
        minutes[k.subject] = {"group": k.group, "complete": not k.truncated,
                              **{w: ds.window_stage_minutes(stages, *windows[w]) for w in ("oh", "rec")
                                 if w in windows}}
        for w, (s0, s1) in windows.items():
            labels = ds.window_labels(stages, s0, s1)
            for variant, hr in ds.hr_variants(t_ms, bpm, s0, s1, er.valid_range_ms, k.subject).items():
                rel = ds.night_relpath(k.subject, w, variant)
                body = ds.night_bytes(ds.night_object(k.subject, w, variant, s0, s1, hr, labels))
                path = out / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(body)
                files[rel] = ds.sha256_bytes(body)
    if identity is None:
        raise ds.ExternalError("no recording was staged")
    fails = ds.lock_identity_failures(identity, lock)
    if fails:
        raise ds.ExternalError("YASA environment differs from the lock: " + "; ".join(fails))
    manifest = {
        "dataset": {"id": ds.DATASET, "doi": ds.DATASET_DOI, "changesFirstEntry": list(ds.CHANGES_FIRST_ENTRY),
                    "keyListSha256": ds.sha256_bytes(ds.format_keys(keys).encode("utf-8"))},
        "preregistration": {"file": ds.PREREG_NAME, "sha256": ds.PREREG_SHA256},
        "yasa": identity,
        "code": ds.code_digests(),
        "subjects": subjects,
        "files": dict(sorted(files.items())),
        "filesSha256": ds.digest_of_digests(files),
    }
    summary = ds.yasa_summary(minutes, identity)
    return ds.write_json_atomic(out / ds.MANIFEST_NAME, manifest), ds.write_json_atomic(out / ds.SUMMARY_NAME,
                                                                                        summary)


def cmd_nights(a: argparse.Namespace, argv: list[str]) -> int:
    data = Path(a.data).resolve()
    log_lines = Path(a.log).read_text(encoding="utf-8").splitlines() if a.log and Path(a.log).is_file() else []
    man_logged, sum_logged = ds.logged_digests(log_lines)
    if (man_logged or sum_logged) and not a.reproduce:
        print("refused: a ds006576 manifest / yasa-summary NOTE is already in the log (the test set is frozen); "
              "use --reproduce to re-extract into a new directory and compare")
        return 2
    if (data / ds.MANIFEST_NAME).exists() and not a.reproduce:
        # whatever log is given: frozen files are never overwritten (a manifest left by a run that crashed before
        # its NOTE lines must be removed by hand, after checking the log)
        print(f"refused: {data / ds.MANIFEST_NAME} exists; use --reproduce (or remove it if no NOTE was logged)")
        return 2
    if a.reproduce and (len(man_logged) != 1 or len(sum_logged) != 1):
        print("refused: --reproduce needs exactly one logged manifest and one yasa-summary NOTE")
        return 2
    keys, lock = ds.read_keys(a.keys), ds.read_lock(a.lock)
    with LoggedRun(a.log, argv) as run:
        if a.reproduce:
            out = data / f"reproduce-{datetime.now().strftime('%Y%m%dT%H%M%S')}"
        else:
            out = data
            for stale in (data / "nights", data / ds.MANIFEST_NAME, data / ds.SUMMARY_NAME):
                if stale.is_dir():
                    shutil.rmtree(stale)
                elif stale.exists():
                    stale.unlink()
        man, summ = build_nights(data, out, keys, lock)
        d_man, d_sum = ds.sha256_bytes(man), ds.sha256_bytes(summ)
        print(f"manifest sha256 {d_man}")
        print(f"yasa-summary sha256 {d_sum}")
        if a.reproduce:
            same = (d_man, d_sum) == (man_logged[0], sum_logged[0])
            text = ("NOTE ds006576 re-extraction into " + out.name + (
                " reproduces the logged manifest and yasa-summary digests" if same else
                f" differs from the logged digests (deviation): manifest sha256 {d_man} yasa-summary sha256 {d_sum}"))
            if a.log is not None:
                ds.append_log(a.log, text)
            print(text[5:])
            return run.end(0 if same else 4)
        if a.log is not None:
            ds.append_log(a.log, ds.NOTE_MANIFEST + d_man)
            ds.append_log(a.log, ds.NOTE_SUMMARY + d_sum)
        return run.end(0)


# ================================================================================================= lock =====

def format_lock(report: dict, cutoff: str, pip_version: str) -> str:
    """The lock text from a ``pip install --dry-run --ignore-installed --report`` resolution."""
    lines = [
        "# tools/train/.venv-external: the separate environment of the YASA sleep staging of OpenNeuro ds006576",
        "# (pre-registration naps-realistic, section 13.4); never the training environment. CPython 3.12 (mise),",
        "# Linux x86_64. Resolved by pip " + pip_version + " with",
        "#   pip --python tools/train/.venv-external/bin/python install --dry-run --ignore-installed \\",
        f"#       --report <report.json> --uploaded-prior-to {cutoff} yasa==0.7.0",
        "# (every pinned release at least two weeks old; checked with `external.py lock-check`) and written by",
        "# `external.py lock`. Install (pre-registration section 18, plus the OpenMP runtime of LightGBM):",
        "#   mise exec -- python -m venv tools/train/.venv-external",
        "#   tools/train/.venv-external/bin/python -m pip download yasa==0.7.0 --no-deps \\",
        "#       -d tools/train/.venv-external/wheelhouse",
        "#   sha256sum tools/train/.venv-external/wheelhouse/yasa-0.7.0-py3-none-any.whl   # = yasa-wheel below",
        "#   tools/train/.venv-external/bin/python -m pip install --no-deps \\",
        "#       --find-links tools/train/.venv-external/wheelhouse -r tools/train/requirements-external.lock",
        "#   (cd tools/train/.venv-external/wheelhouse && apt-get download libgomp1=" + ds.OPENMP_VERSION + ")",
        "#   dpkg-deb -x tools/train/.venv-external/wheelhouse/" + ds.OPENMP_DEB + " <tmp>",
        "#   mkdir tools/train/.venv-external/openmp && cp <tmp>/usr/lib/x86_64-linux-gnu/libgomp.so.1.0.0 \\",
        "#       tools/train/.venv-external/openmp/libgomp.so.1",
        "#   tools/train/.venv-external/bin/python -I tools/train/external_stage.py --self-test",
        "# LightGBM's Linux wheel needs the OpenMP runtime libgomp.so.1 (not installed on the build machine);",
        "# external_stage.py loads exactly the file below (digest-checked) before LightGBM, whatever the host has.",
        f"# yasa-wheel {ds.YASA_WHEEL} sha256 {ds.YASA_WHEEL_SHA256}",
        f"# yasa-classifier {ds.YASA_CLASSIFIER} sha256 {ds.YASA_CLASSIFIER_SHA256}",
        f"# openmp-deb {ds.OPENMP_DEB} sha256 {ds.OPENMP_DEB_SHA256}",
        f"# openmp-lib libgomp.so.1 sha256 {ds.OPENMP_LIB_SHA256}",
    ]
    for it in sorted(report["install"], key=lambda i: ds.normalize_name(i["metadata"]["name"])):
        m = it["metadata"]
        h = it["download_info"]["archive_info"]["hashes"]["sha256"]
        lines.append(f"{m['name']}=={m['version']} \\\n    --hash=sha256:{h}")
    return "\n".join(lines) + "\n"


def cmd_lock(a: argparse.Namespace) -> int:
    report = json.loads(Path(a.report).read_text(encoding="utf-8"))
    names = {ds.normalize_name(i["metadata"]["name"]): i["metadata"]["version"] for i in report["install"]}
    if names.get("yasa") != ds.YASA_VERSION:
        print(f"the report resolves yasa {names.get('yasa')}, not {ds.YASA_VERSION}")
        return 1
    Path(a.out).write_text(format_lock(report, a.cutoff, report.get("pip_version", "?")), encoding="utf-8")
    print(f"{a.out}: {len(names)} packages")
    return 0


def pypi_release(name: str, version: str, opener=ds.urlopen) -> dict:
    return json.loads(ds.get_bytes(f"https://pypi.org/pypi/{urllib.parse.quote(name)}/{version}/json",
                                   opener=opener))


def lock_check(lock: ds.Lock, cutoff: str, release=pypi_release) -> list[str]:
    """Every pinned release: its hashed file exists on PyPI, and the release (first upload) and that file were
    uploaded before ``cutoff``; plus the pre-registered YASA digests in the comment lines."""
    limit = datetime.fromisoformat(cutoff.replace("Z", "+00:00"))
    fails = ds.lock_identity_failures({"version": lock.pins.get("yasa"), "wheelSha256": lock.yasa_wheel_sha256,
                                       "classifier": lock.classifier_name, "classifierSha256": lock.classifier_sha256,
                                       "openmpSha256": lock.openmp_lib_sha256}, lock)
    if (lock.openmp_deb_name, lock.openmp_deb_sha256, lock.openmp_lib_sha256) != (
            ds.OPENMP_DEB, ds.OPENMP_DEB_SHA256, ds.OPENMP_LIB_SHA256):
        fails.append("the openmp lines of the lock differ from pulsetrain.ds006576")
    for name, version in sorted(lock.pins.items()):
        files = release(name, version).get("urls", [])
        times = [datetime.fromisoformat(f["upload_time_iso_8601"].replace("Z", "+00:00")) for f in files]
        mine = [f for f in files if f.get("digests", {}).get("sha256") == lock.hashes.get(name)]
        if not files or min(times) >= limit:
            fails.append(f"{name}=={version}: released {min(times).isoformat() if times else '?'}, not before {cutoff}")
        if not mine:
            fails.append(f"{name}=={version}: no file with the locked hash on PyPI")
        elif datetime.fromisoformat(mine[0]["upload_time_iso_8601"].replace("Z", "+00:00")) >= limit:
            fails.append(f"{name}=={version}: the locked file was uploaded after {cutoff}")
    return fails


def cmd_lock_check(a: argparse.Namespace) -> int:
    lock = ds.read_lock(a.lock)
    fails = lock_check(lock, a.cutoff)
    for f in fails:
        print(f)
    print(f"{len(lock.pins)} pinned releases; {'all' if not fails else 'NOT all'} released before {a.cutoff}")
    return 1 if fails else 0


# ================================================================================================= main =====

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch", help="snapshot pin, stream and reduce every .eeg (no model involved)")
    f.add_argument("--data", type=Path, default=ds.DATA_DIR)
    f.add_argument("--workers", type=int, default=4)
    n = sub.add_parser("nights", help="night files, manifest, yasa-summary; prints and logs digests only")
    n.add_argument("--data", type=Path, default=ds.DATA_DIR)
    n.add_argument("--lock", type=Path, default=ds.LOCK_PATH)
    n.add_argument("--reproduce", action="store_true",
                   help="re-extract into <data>/reproduce-<time> and compare with the logged digests")
    for s in (f, n):
        s.add_argument("--keys", type=Path, default=ds.KEYS_PATH)
        s.add_argument("--log", type=Path, default=ds.COMMANDS_LOG, help="commands log (START / END / NOTE lines)")
        s.add_argument("--base-url", default=ds.BASE_URL, help=argparse.SUPPRESS)
    k = sub.add_parser("keys", help="write the key list from public metadata (never an .eeg byte)")
    k.add_argument("--out", type=Path, default=ds.KEYS_PATH)
    k.add_argument("--pilot-audit", type=Path, default=None, help="also require equality with the pilot's audit")
    k.add_argument("--base-url", default=ds.BASE_URL, help=argparse.SUPPRESS)
    lk = sub.add_parser("lock", help="write requirements-external.lock from a pip --report resolution")
    lk.add_argument("--report", type=Path, required=True)
    lk.add_argument("--out", type=Path, default=ds.LOCK_PATH)
    lk.add_argument("--cutoff", required=True, help="the --uploaded-prior-to of the resolution (ISO 8601)")
    lc = sub.add_parser("lock-check", help="check every pinned release against PyPI (upload dates, hashes)")
    lc.add_argument("--lock", type=Path, default=ds.LOCK_PATH)
    lc.add_argument("--cutoff", required=True, help="ISO 8601; every release must be older")
    return p


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    a = build_parser().parse_args(argv)
    try:
        if a.cmd == "fetch":
            return cmd_fetch(a, argv)
        if a.cmd == "nights":
            return cmd_nights(a, argv)
        if a.cmd == "keys":
            return cmd_keys(a)
        if a.cmd == "lock":
            return cmd_lock(a)
        return cmd_lock_check(a)
    except ds.ExternalError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
