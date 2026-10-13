"""End-to-end run: features -> baselines -> HMM-trained -> hybrid LOSO -> report -> final model.

Output layout (``<out_root>/<run>/``)::

    config.json  run.log  commands.log            run configuration (+ algo-cli fingerprint), log, every algo-cli
                                                  command (rc, seconds)
    features/<variant>.csv                         algo-cli features (computed once per variant)
    baselines/<variant>/{RULES,HMM-table}.metrics.json
    hmm-trained/<pool>/<variant>/folds/<fold>.hmm-params.json   per-fold HMM MLE (AlgoParams patch) + metrics/
    hmm-trained/<pool>/final.hmm-params.json       HMM MLE on all subjects of the pool (validity gate, defaults)
    hybrid/<config>/<variant>/<lr|mlp>/folds/<subject>.json      LOSO fold bundles (+ <subject>.hmm-params.json)
    hybrid/<config>/<variant>/<lr|mlp>/folds-meta/<fold>.meta.json   train/test subjects, chosen C / params
    hybrid/<config>/<variant>/<lr|mlp>/selection/<key>.json      inner-CV results (C log-loss, grid kappas)
    hybrid/<config>/<variant>/<lr|mlp>/metrics/<group>.json      outer evaluation (one call per params group)
    work/...                                      inner-CV scratch (removed unless --keep-inner)
    results.jsonl  fold_selections.jsonl          per-night results / per-fold choices (input of the report)
    summary.json  report.md                       aggregated metrics, selection, markdown for ALGORITHM.md
    final/hybrid_model.json  trained_defaults.json
"""

from __future__ import annotations

import logging
import math
import sys
import time
from pathlib import Path
from typing import Any, Mapping, Sequence

from . import report
from .algocli import AlgoCli, AlgoCliError
from .loso import (PipelineConfig, config_info, final_hmm_estimates, prepare_context, read_jsonl, run_baselines,
                   run_hmm_trained, run_hybrid, write_jsonl)
from .train_final import train_final
from .util import read_json, write_json

log = logging.getLogger("pulsetrain")


def setup_logging(run_dir: Path, verbose: bool = False) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    root = logging.getLogger()
    for h in list(root.handlers):
        if getattr(h, "_pulsetrain", False):
            root.removeHandler(h)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
    fh = logging.FileHandler(run_dir / "run.log", encoding="utf-8")
    sh = logging.StreamHandler(sys.stderr)
    for h in (fh, sh):
        h.setFormatter(fmt)
        h._pulsetrain = True  # type: ignore[attr-defined]
        root.addHandler(h)
    root.setLevel(logging.DEBUG if verbose else logging.INFO)


def clean_json(obj: Any) -> Any:
    """NaN / inf -> None (strict JSON)."""
    if isinstance(obj, float):
        return obj if math.isfinite(obj) else None
    if isinstance(obj, Mapping):
        return {str(k): clean_json(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [clean_json(v) for v in obj]
    return obj


def report_meta(cfg: PipelineConfig, extra: Mapping | None = None) -> dict:
    rows = ("evaluated variant + its sampling twin (instantaneous <-> mean2min)" if cfg.train_variants == "pair"
            else "evaluated variant only")
    notes = [
        f"training rows: {rows}",
        f"classifiers: LR C grid {list(cfg.lr_Cs)} (inner log-loss), MLP 32x32 relu (early stopping, seed {cfg.seed}); "
        f"class weighting: {cfg.class_weight}",
        f"decoder grids: LR `{cfg.lr_grid}`, MLP `{cfg.mlp_grid}` over hybrid.emissionWeight / timePriorWeight / "
        f"durationModel",
        "HMM-trained: supervised MLE per fold (emissions of hrNorm/hrVar/hrSlope on epochs with HR data, mean bout "
        "lengths, bout-transition shares); other HMM parameters at their defaults",
        "evaluation `external` = subjects of datasets outside the training pool, scored with the model fitted on the "
        "whole pool (no LOSO needed, no overlap)",
        "per-subject kappa = kappa of the subject's pooled confusion matrix over its nights; mean ± sd over subjects",
        "final decoder params = most frequent inner-CV choice across the outer folds of the chosen configuration "
        "(the inner selection is not re-run on all subjects)",
    ]
    meta = {"run": cfg.run, "createdAt": cfg.created_at, "innerK": cfg.inner_k, "selection": cfg.selection,
            "selectDatasets": list(cfg.select_datasets), "headlineVariants": list(report.HEADLINE_VARIANTS),
            "variants": list(cfg.variants), "configs": list(cfg.configs), "classifiers": list(cfg.classifiers),
            "configDescriptions": {c: config_info(c).description for c in cfg.configs}, "notes": notes}
    if extra:
        meta.update(extra)
    return meta


def hmm_checks_of(estimates: Mapping | None) -> dict | None:
    """Pool -> validity facts of the HMM estimates on all subjects (input of the selection's validity gate)."""
    if estimates is None:
        return None
    return {pool: {"hrNormOrdered": e.get("hrNormOrdered"), "subjects": e.get("subjects")}
            for pool, e in estimates.items()}


def build_report(cfg: PipelineConfig, records: Sequence[Mapping], fold_selections: Sequence[Mapping],
                 extra_meta: Mapping | None = None) -> dict:
    rows = report.summarize(records)
    estimates = (extra_meta or {}).get("hmmFinalEstimates")
    selection = report.select_defaults(rows, fold_selections, configs_order=cfg.configs,
                                       hmm_checks=hmm_checks_of(estimates))
    meta = report_meta(cfg, extra_meta)
    summary = clean_json({"meta": meta, "rows": rows, "selection": selection})
    write_json(cfg.out_dir / "summary.json", summary)
    (cfg.out_dir / "report.md").write_text(report.render_markdown(rows, selection, meta), encoding="utf-8")
    return summary


def check_algo_cli_unchanged(cli: AlgoCli, expected: Mapping, where: str) -> None:
    """Fails when the algo-cli build changed during the run (e.g. a rebuild in parallel with a run): every number
    of a run must come from one build, the one recorded in config.json."""
    now = cli.fingerprint()
    if now.get("sha256") != expected.get("sha256"):
        raise AlgoCliError(f"algo-cli changed during the run ({where}): sha256 {expected.get('sha256')} at the start, "
                           f"{now.get('sha256')} now; rebuild before a run, never during one")


def run_pipeline(cfg: PipelineConfig, cli: AlgoCli | None = None, verbose: bool = False) -> dict:
    t_start = time.monotonic()
    setup_logging(cfg.out_dir, verbose)
    if cli is None:
        cli = AlgoCli.locate(cfg.algo_cli, commands_log=cfg.out_dir / "commands.log", timeout_s=cfg.timeout_s,
                             grid_mode=cfg.grid_mode)
    fingerprint = cli.fingerprint()
    write_json(cfg.out_dir / "config.json", {**cfg.to_json(), "algoCli": fingerprint})
    log.info("run %s -> %s; algo-cli %s (sha256 %s)", cfg.run, cfg.out_dir, fingerprint.get("launcher"),
             fingerprint.get("sha256"))
    phases: dict[str, float] = {}

    def phase(name, fn, *a):
        t0 = time.monotonic()
        r = fn(*a)
        phases[name] = round(time.monotonic() - t0, 2)
        log.info("phase %s: %.1f s", name, phases[name])
        check_algo_cli_unchanged(cli, fingerprint, f"after phase {name}")
        return r

    ctx = phase("features", prepare_context, cfg, cli)
    records: list[dict] = []
    fold_selections: list[dict] = []
    estimates = None
    if {"RULES", "HMM-table"} & set(cfg.estimators):
        records += phase("baselines", run_baselines, ctx)
    if "HMM-trained" in cfg.estimators:
        records += phase("hmm-trained", run_hmm_trained, ctx)
        estimates = phase("hmm-final-estimates", final_hmm_estimates, ctx)
    if "HYBRID" in cfg.estimators:
        r, fs = phase("hybrid", run_hybrid, ctx)
        records += r
        fold_selections += fs
    write_jsonl(cfg.out_dir / "results.jsonl", records)
    write_jsonl(cfg.out_dir / "fold_selections.jsonl", fold_selections)
    extra = {"featureSetVersion": ctx.feature_set_version, "featureNames": ctx.feature_names,
             "stepFeatures": [ctx.feature_names[i] for i in ctx.step_idx], "algoCli": fingerprint}
    if estimates is not None:
        extra["hmmFinalEstimates"] = clean_json(estimates)
    summary = phase("report", build_report, cfg, records, fold_selections, extra)
    final = phase("final", train_final, ctx, summary["selection"], fold_selections, cfg.install_model, True,
                  estimates)
    wall = time.monotonic() - t_start
    summary["final"] = clean_json(final)
    summary["meta"]["phaseSeconds"] = phases
    summary["meta"]["wallSeconds"] = round(wall, 2)
    write_json(cfg.out_dir / "summary.json", summary)
    msg = f"run {cfg.run}: total wall time {wall:.1f} s ({wall / 60:.1f} min); report: {cfg.out_dir / 'report.md'}"
    log.info(msg)
    print(msg)
    return summary


def load_run(run_dir: Path) -> PipelineConfig:
    cfg_path = Path(run_dir) / "config.json"
    if not cfg_path.is_file():
        raise FileNotFoundError(f"{cfg_path} not found (run the pipeline first)")
    return PipelineConfig.from_json(read_json(cfg_path))


def rerun_report(run_dir: Path) -> dict:
    cfg = load_run(run_dir)
    setup_logging(cfg.out_dir)
    old = read_json(cfg.out_dir / "summary.json") if (cfg.out_dir / "summary.json").is_file() else {}
    extra = {k: old.get("meta", {}).get(k) for k in ("featureSetVersion", "featureNames", "stepFeatures",
                                                      "phaseSeconds", "wallSeconds", "algoCli", "hmmFinalEstimates")
             if k in old.get("meta", {})}
    summary = build_report(cfg, read_jsonl(cfg.out_dir / "results.jsonl"),
                           read_jsonl(cfg.out_dir / "fold_selections.jsonl"), extra)
    if "final" in old:
        summary["final"] = old["final"]
        write_json(cfg.out_dir / "summary.json", summary)
    return summary


def rerun_final(run_dir: Path, install: Path | None = None, cli: AlgoCli | None = None) -> dict:
    cfg = load_run(run_dir)
    cfg.reuse_features = True
    setup_logging(cfg.out_dir)
    summary = read_json(cfg.out_dir / "summary.json")
    ctx = prepare_context(cfg, cli)
    final = train_final(ctx, summary["selection"], read_jsonl(cfg.out_dir / "fold_selections.jsonl"), install)
    summary["final"] = clean_json(final)
    write_json(cfg.out_dir / "summary.json", summary)
    return final
