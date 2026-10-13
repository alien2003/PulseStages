"""Post-hoc analyses of finished runs. Reporting only: nothing here feeds back into any selection or default.

- :func:`compare_runs`: the default rows (or a named estimator / configuration) of several runs on the headline
  dataset: worse-variant score per run, mean ± sample sd over the runs (e.g. seeds of one setting) and paired
  per-subject bootstrap CIs of every run against the first one (e.g. an experiment against its reference).
- :func:`boundary_sensitivity`: the outer LOSO fold models of a run, each with the decoder parameters its nested
  selection chose, evaluated by algo-cli on Walch nights whose OHealth-like session window was moved (sleep onset /
  final awakening detected later or earlier, random jitter) or whose steps were removed. The training emulation cuts
  the session from the PSG labels (onset − 5 min … final awakening + 5 min), and the hybrid features include the
  elapsed / remaining session time; real watch sessions come from the watch's own sleep detection, so this measures
  how much the reported kappa depends on knowing the true boundaries. The shifted nights are rebuilt from the
  ``<variant>-psg`` night files (full scored PSG range): heart rate and steps of [start − 30 min, end + 30 min] and
  the label epochs fully inside [start, end], exactly as ``prepare.py`` builds the OHealth-like files (the
  unshifted rebuild reproduces them, which :func:`boundary_sensitivity` checks).
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence

import numpy as np

from . import report
from .algocli import AlgoCli
from .loso import PipelineConfig, fold_dir, map_threads, subjects_key
from .nightset import NightIndex, load_index
from .util import canonical_json, deep_merge, read_json, write_json

log = logging.getLogger(__name__)

CONTEXT_MS = 30 * 60_000           # prepare.py / ohealth.CONTEXT_MS


class RobustnessError(RuntimeError):
    """A post-hoc analysis could not reproduce the run it analyses (other build / data) or lacks its inputs."""
MIN_SESSION_MS = 60 * 60_000       # a moved window is never made shorter than one hour


@dataclass(frozen=True)
class Scenario:
    name: str
    start_shift_min: float = 0.0   # + = the session starts later (sleep onset detected late)
    end_shift_min: float = 0.0     # − = the session ends earlier (final awakening detected early)
    jitter_min: float = 0.0        # > 0: independent uniform shifts in [−jitter, +jitter] for start and end
    no_steps: bool = False
    description: str = ""


SCENARIOS: tuple[Scenario, ...] = (
    Scenario("base", description="reference: the OHealth-like window rebuilt from the -psg files"),
    Scenario("narrow-10", 10, -10, description="start 10 min later, end 10 min earlier"),
    Scenario("narrow-20", 20, -20, description="start 20 min later, end 20 min earlier"),
    Scenario("late-start-15", 15, 0, description="start 15 min later"),
    Scenario("early-end-15", 0, -15, description="end 15 min earlier"),
    Scenario("wide-10", -10, 10, description="start 10 min earlier, end 10 min later: more in-bed wake"),
    Scenario("wide-20", -20, 20, description="start 20 min earlier, end 20 min later"),
    Scenario("jitter-15", jitter_min=15, description="start and end each moved uniformly within ±15 min"),
    Scenario("no-steps", no_steps=True, description="OHealth-like window, all steps removed"),
)


def _uniform(key: str, lo: float, hi: float) -> float:
    d = hashlib.sha256(key.encode("utf-8")).digest()
    return lo + (hi - lo) * (int.from_bytes(d[:8], "big") / 2.0 ** 64)


def scenario_shifts(s: Scenario, night_id: str) -> tuple[float, float]:
    """(start shift, end shift) in minutes for ``night_id`` (deterministic per night for jitter scenarios)."""
    if s.jitter_min > 0:
        return (_uniform(f"{s.name}/start/{night_id}", -s.jitter_min, s.jitter_min),
                _uniform(f"{s.name}/end/{night_id}", -s.jitter_min, s.jitter_min))
    return float(s.start_shift_min), float(s.end_shift_min)


def shift_night(ohealth: Mapping, psg: Mapping, start_shift_min: float, end_shift_min: float,
                no_steps: bool = False) -> dict:
    """The night ``ohealth`` with its session moved by the given minutes, clipped to the scored range of its
    ``-psg`` twin (never shorter than one hour), rebuilt from the twin like prepare.py: HR samples and step
    records of [start − 30 min, end + 30 min] (step records overlapping it), label epochs fully inside
    [start, end]."""
    rng0, rng1 = int(psg["sessionStartMs"]), int(psg["sessionEndMs"])
    s0 = int(round(ohealth["sessionStartMs"] + start_shift_min * 60_000))
    s1 = int(round(ohealth["sessionEndMs"] + end_shift_min * 60_000))
    s0, s1 = max(rng0, min(s0, rng1)), max(rng0, min(s1, rng1))
    if s1 - s0 < MIN_SESSION_MS:
        mid = (s0 + s1) // 2
        s0, s1 = max(rng0, mid - MIN_SESSION_MS // 2), min(rng1, mid + MIN_SESSION_MS // 2)
    c0, c1 = s0 - CONTEXT_MS, s1 + CONTEXT_MS
    out = {k: v for k, v in ohealth.items() if k not in ("hr", "steps", "labels", "sessionStartMs", "sessionEndMs")}
    out.update({
        "sessionStartMs": s0, "sessionEndMs": s1,
        "hr": [h for h in psg["hr"] if c0 <= h[0] <= c1],
        "steps": [] if no_steps else [s for s in psg.get("steps", []) if s[1] > c0 and s[0] < c1],
        "labels": [lab for lab in psg.get("labels", []) if lab[0] >= s0 and lab[1] <= s1],
    })
    return out


def _psg_twin(variant: str) -> str:
    return variant + "-psg"


def _fold_choices(run_dir: Path, config: str, variant: str, clf: str, subjects: Iterable[str]) -> dict[str, dict]:
    """Held-out subject -> decoder params chosen by its outer fold's nested selection."""
    meta_dir = Path(run_dir) / "hybrid" / config / variant / clf / "folds-meta"
    want = set(subjects)
    out: dict[str, dict] = {}
    for p in sorted(meta_dir.glob("*.meta.json")):
        m = read_json(p)
        if m.get("kind") != "loso":
            continue
        for s in m["test"]:
            if s in want:
                out[s] = dict(m["params"])
    missing = sorted(want - set(out))
    if missing:
        raise RobustnessError(f"no outer fold meta for {missing[:3]} in {meta_dir}")
    return out


def default_row_key(selection: Mapping) -> tuple[str, str, str]:
    """(estimator label, training configuration, classifier) of the run's default HYBRID classifier."""
    c = selection["classifier"]
    return report.CLASSIFIER_LABEL[c["chosen"]], c["config"], c["chosen"]


def boundary_sensitivity(run_dir: Path, cli: AlgoCli | None = None, scenarios: Sequence[Scenario] = SCENARIOS,
                         variants: Sequence[str] = report.HEADLINE_VARIANTS, dataset: str = report.HEADLINE_DATASET,
                         jobs: int = 4) -> dict:
    """Evaluates the outer fold models of ``run_dir`` (default classifier / configuration) on moved session windows
    of the ``dataset`` nights; writes ``<run>/sensitivity/{summary.json,report.md}`` and returns the summary."""
    run_dir = Path(run_dir)
    cfg = PipelineConfig.from_json(read_json(run_dir / "config.json"))
    summary = read_json(run_dir / "summary.json")
    est, config, clf = default_row_key(summary["selection"])
    out_dir = run_dir / "sensitivity"
    if cli is None:
        cli = AlgoCli.locate(cfg.algo_cli, commands_log=out_dir / "commands.log", timeout_s=cfg.timeout_s)
    fingerprint = cli.fingerprint()
    index = load_index(cfg.nights_root, [v for v in variants] + [_psg_twin(v) for v in variants], cfg.summary_csv)
    tasks = []
    groups_meta = []
    for v in variants:
        nights = [n for n in index.for_variant(v) if n.dataset == dataset]
        subjects = sorted({n.subject for n in nights})
        choice = _fold_choices(run_dir, config, v, clf, subjects)
        twins = _twins(index, v, nights)
        by_params: dict[str, list[str]] = {}
        for s in subjects:
            by_params.setdefault(canonical_json(choice[s]), []).append(s)
        for sc in [None, *scenarios]:                       # None = the original night files (headline check)
            for pk, subs in sorted(by_params.items()):
                tasks.append((v, sc, json.loads(pk), subs, twins))
        groups_meta.append({"variant": v, "paramGroups": len(by_params), "subjects": len(subjects)})

    def one(task):
        v, sc, hybrid, subs, twins = task
        name = "original" if sc is None else sc.name
        d = out_dir / v / name
        gid = subjects_key(subs)
        lines = []
        for n in index.nights_of(v, subs):
            o = read_json(n.path)
            if sc is not None:
                a, b = scenario_shifts(sc, n.id)
                o = shift_night(o, read_json(twins[n.id].path), a, b, sc.no_steps)
            lines.append(json.dumps(o, separators=(",", ":")))
        nights_file = d / f"{gid}.nights.jsonl"
        nights_file.parent.mkdir(parents=True, exist_ok=True)
        nights_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        pf = write_json(d / f"{gid}.params.json", deep_merge(cfg.base_params, {"estimator": "HYBRID",
                                                                                "hybrid": hybrid}))
        m = cli.evaluate(nights_file, "HYBRID", d / f"{gid}.json", params=pf,
                         fold_models=fold_dir(cfg, config, v, clf))
        nights_file.unlink()
        recs = report.parse_metrics(m)
        report.check_estimator_used(recs, "HYBRID")
        subj_of = {n.id: n.subject for n in index.nights_of(v, subs)}
        for r in recs:
            r["subject"] = subj_of[r["id"]]
        return v, name, recs

    results: dict[tuple[str, str], list[dict]] = {}
    for v, name, recs in map_threads(one, tasks, jobs):
        results.setdefault((v, name), []).extend(recs)
    if cli.fingerprint().get("sha256") != fingerprint.get("sha256"):
        raise RobustnessError("algo-cli changed during the sensitivity analysis")
    rows = []
    for (v, name), recs in sorted(results.items()):
        agg = report.aggregate(recs)
        rows.append({"variant": v, "scenario": name, "kappaSubjectMean": agg["kappaSubjectMean"],
                     "kappaSubjectSd": agg["kappaSubjectSd"], "kappaPooled": agg["kappaPooled"],
                     "accuracy": agg["accuracy"], "subjectKappa": agg["subjectKappa"],
                     "scoredChunks": int(np.asarray(agg["confusion"]).sum()), "nights": agg["nightsEstimated"],
                     "minutesError": agg["minutesError"]})
    headline = {r["variant"]: r["kappaSubjectMean"] for r in summary["rows"]
                if r["estimator"] == est and r["config"] == config and r["dataset"] == dataset
                and r["variant"] in variants}
    checks = {}
    for v in variants:
        orig = _row(rows, v, "original")
        base = _row(rows, v, "base")
        checks[v] = {"headline": headline.get(v), "original": orig["kappaSubjectMean"] if orig else None,
                     "base": base["kappaSubjectMean"] if base else None}
        if orig and headline.get(v) is not None and not math.isclose(orig["kappaSubjectMean"], headline[v],
                                                                       abs_tol=1e-9):
            raise RobustnessError(f"{v}: re-evaluating the original nights gives {orig['kappaSubjectMean']} "
                                  f"instead of the run's {headline[v]} (different build or data?)")
        if orig and base and orig["subjectKappa"] != base["subjectKappa"]:
            raise RobustnessError(f"{v}: the unshifted rebuild from the -psg files does not reproduce the "
                                  f"original nights")
    for r in rows:
        base = _row(rows, r["variant"], "base")
        r["vsBase"] = (report.paired_bootstrap(r["subjectKappa"], base["subjectKappa"])
                       if base and r["scenario"] not in ("base", "original") else None)
    res = {"run": cfg.run, "estimator": est, "config": config, "classifier": clf, "dataset": dataset,
           "algoCli": fingerprint, "groups": groups_meta, "checks": checks,
           "scenarios": [{"name": s.name, "startShiftMin": s.start_shift_min, "endShiftMin": s.end_shift_min,
                          "jitterMin": s.jitter_min, "noSteps": s.no_steps, "description": s.description}
                         for s in scenarios], "rows": rows}
    write_json(out_dir / "summary.json", _clean(res))
    (out_dir / "report.md").write_text(render_sensitivity(res), encoding="utf-8")
    return res


def _twins(index: NightIndex, variant: str, nights) -> dict:
    twin = _psg_twin(variant)
    by_stem = {n.path.name: n for n in index.for_variant(twin)}
    out = {}
    for n in nights:
        t = by_stem.get(n.path.name)
        if t is None:
            raise RobustnessError(f"no {twin} twin for {n.id} ({n.path.name})")
        out[n.id] = t
    return out


def _row(rows: Sequence[Mapping], variant: str, scenario: str) -> Mapping | None:
    return next((r for r in rows if r["variant"] == variant and r["scenario"] == scenario), None)


def _clean(obj):
    if isinstance(obj, float):
        return obj if math.isfinite(obj) else None
    if isinstance(obj, Mapping):
        return {str(k): _clean(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_clean(v) for v in obj]
    return obj


def render_sensitivity(res: Mapping) -> str:
    f = report._f
    L = [f"# Session-boundary sensitivity — run `{res['run']}`", "",
         f"{res['estimator']} [{res['config']}], outer LOSO fold models with the decoder parameters chosen by each "
         f"fold's nested selection, {res['dataset']} nights; mean per-subject κ (sample sd), paired Δ vs `base` "
         f"with a 95 % bootstrap CI over subjects. Reporting only.", ""]
    variants = sorted({r["variant"] for r in res["rows"]})
    L.append("| scenario | " + " | ".join(f"κ {v} | Δ {v} [95 % CI]" for v in variants) + " | κ worse |")
    L.append("|---|" + "---|---|" * len(variants) + "---|")
    desc = {s["name"]: s["description"] for s in res["scenarios"]}
    for name in ["original"] + [s["name"] for s in res["scenarios"]]:
        cells, ks = [], []
        for v in variants:
            r = _row(res["rows"], v, name)
            if r is None:
                cells += ["—", "—"]
                continue
            ks.append(r["kappaSubjectMean"])
            c = r.get("vsBase")
            cells.append(f"{f(r['kappaSubjectMean'])} ± {f(r['kappaSubjectSd'])}")
            cells.append(f"{f(c['meanDiff'])} [{f(c['ciLow'])}, {f(c['ciHigh'])}]" if c else "—")
        label = name if name == "original" else f"{name} ({desc.get(name, '')})"
        L.append(f"| {label} | " + " | ".join(cells) + f" | {f(min(ks)) if ks else '—'} |")
    L.append("")
    return "\n".join(L)


# -------------------------------------------------------------------------------------------- compare runs ----

def compare_runs(run_dirs: Sequence[Path], estimator: str | None = None, config: str | None = None,
                 dataset: str = report.HEADLINE_DATASET, variants: Sequence[str] = report.HEADLINE_VARIANTS) -> dict:
    """Default rows (or ``estimator`` / ``config``) of several runs: scores, mean ± sample sd over runs and paired
    bootstrap CIs against the first run."""
    runs = []
    for d in run_dirs:
        s = read_json(Path(d) / "summary.json")
        est, cfg = estimator, config
        if est is None or cfg is None:
            e, c, _ = default_row_key(s["selection"])
            est, cfg = est or e, cfg or c
        by_v = {r["variant"]: r for r in s["rows"] if r["estimator"] == est and r["config"] == cfg
                and r["dataset"] == dataset and r["variant"] in variants}
        missing = [v for v in variants if v not in by_v]
        if missing:
            raise RobustnessError(f"{d}: no {est} [{cfg}] rows for {dataset} {missing}")
        runs.append({"run": s["meta"]["run"], "dir": str(d), "estimator": est, "config": cfg,
                     "kappa": {v: by_v[v]["kappaSubjectMean"] for v in variants},
                     "worse": min(by_v[v]["kappaSubjectMean"] for v in variants),
                     "subjectKappa": {v: by_v[v]["subjectKappa"] for v in variants}})
    worse = np.array([r["worse"] for r in runs])
    comps = []
    for r in runs[1:]:
        for v in variants:
            comps.append({"run": r["run"], "reference": runs[0]["run"], "variant": v,
                          **report.paired_bootstrap(r["subjectKappa"][v], runs[0]["subjectKappa"][v])})
    return {"dataset": dataset, "variants": list(variants),
            "runs": [{k: x[k] for k in ("run", "estimator", "config", "kappa", "worse")} for x in runs],
            "meanWorse": float(worse.mean()), "sdWorse": float(worse.std(ddof=1)) if worse.size > 1 else 0.0,
            "comparisons": comps}


def render_compare(res: Mapping) -> str:
    f = report._f
    v = res["variants"]
    L = ["| run | estimator [config] | " + " | ".join(f"κ {x}" for x in v) + " | κ worse |",
         "|---|---|" + "---|" * len(v) + "---|"]
    for r in res["runs"]:
        L.append(f"| {r['run']} | {r['estimator']} [{r['config']}] | " + " | ".join(f(r["kappa"][x]) for x in v)
                 + f" | {f(r['worse'])} |")
    L += ["", f"worse-variant κ over the runs: mean {f(res['meanWorse'])}, sample sd {f(res['sdWorse'])}", ""]
    if res["comparisons"]:
        L += ["| run − reference | variant | subjects | mean Δκ | 95 % CI | sd of Δ | better / worse |",
              "|---|---|---|---|---|---|---|"]
        for c in res["comparisons"]:
            L.append(f"| {c['run']} − {c['reference']} | {c['variant']} | {c['n']} | {f(c['meanDiff'])} | "
                     f"[{f(c['ciLow'])}, {f(c['ciHigh'])}] | {f(c['sdDiff'])} | {c['wins']} / {c['losses']} |")
    return "\n".join(L) + "\n"
