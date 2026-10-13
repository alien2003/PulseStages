#!/usr/bin/env python3
"""PulseStages offline training + leave-one-subject-out validation (see tools/train/README.md, "Training").

    mise exec -- python -I tools/train/train.py run --run <name> [options]    # everything (PART B)
    mise exec -- python -I tools/train/train.py report --run <name>           # re-render report.md / summary.json
    mise exec -- python -I tools/train/train.py final --run <name> [--install-model]   # re-train the final model
    mise exec -- python -I tools/train/train.py defaults --run <name> --write  # core-algo TrainedDefaults.kt
    mise exec -- python -I tools/train/train.py compare --runs <ref>,<other>,...  # paired CIs between runs
    mise exec -- python -I tools/train/train.py sensitivity --run <name>      # session-boundary sensitivity

Features and decoding are computed by the Kotlin ``algo-cli`` (``mise exec -- ./gradlew :algo-cli:installDist``;
override the launcher with ``--algo-cli`` or $ALGO_CLI). Reads untrusted data: run with ``python -I``.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

TRAIN_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TRAIN_DIR))  # our own package only; `python -I` does not add the script directory

from pulsetrain import kotlin_defaults, loso, pipeline, robustness  # noqa: E402
from pulsetrain.algocli import AlgoCliError  # noqa: E402
from pulsetrain.bundle import BundleError  # noqa: E402
from pulsetrain.features import FeatureFileError  # noqa: E402
from pulsetrain.report import MetricsFormatError  # noqa: E402
from pulsetrain.stats import LabelStatsParams  # noqa: E402
from pulsetrain.train_final import APP_MODEL_PATH  # noqa: E402
from pulsetrain.util import read_json  # noqa: E402

OUT_ROOT = TRAIN_DIR / "out"
EXPECTED_ERRORS = (AlgoCliError, BundleError, FeatureFileError, MetricsFormatError, FileNotFoundError,
                   kotlin_defaults.DefaultsError, robustness.RobustnessError)
NIGHTS_ROOT = TRAIN_DIR / "data" / "nights"


def _csv(s: str) -> tuple[str, ...]:
    return tuple(x.strip() for x in s.split(",") if x.strip())


def _floats(s: str) -> tuple[float, ...]:
    return tuple(float(x) for x in _csv(s))


def _choices(values, allowed, what):
    bad = [v for v in values if v not in allowed]
    if bad:
        raise SystemExit(f"unknown {what}: {bad}; allowed: {list(allowed)}")
    return values


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="full pipeline: features, LOSO, report, final model")
    r.add_argument("--run", required=True, help="run name (output directory under --out-root)")
    r.add_argument("--out-root", type=Path, default=OUT_ROOT)
    r.add_argument("--nights-root", type=Path, default=NIGHTS_ROOT)
    r.add_argument("--summary-csv", type=Path, default=None, help="default: <nights-root>/summary.csv")
    r.add_argument("--algo-cli", default=None, help="launcher path (default: $ALGO_CLI, then the installDist output)")
    r.add_argument("--variants", type=_csv, default=loso.ALL_VARIANTS)
    r.add_argument("--configs", type=_csv, default=tuple(loso.CONFIGS),
                   help=f"training configurations, any of {list(loso.CONFIGS)}")
    r.add_argument("--classifiers", type=_csv, default=loso.CLASSIFIERS)
    r.add_argument("--estimators", type=_csv, default=loso.ESTIMATORS)
    r.add_argument("--train-variants", choices=("pair", "same"), default="pair",
                   help="pair: train on the evaluated variant + its sampling twin (default); same: evaluated only")
    r.add_argument("--selection", choices=("per-subject", "per-group"), default="per-subject",
                   help="nested selection per held-out subject (default) or shared per outer group (~K x cheaper)")
    r.add_argument("--inner-k", type=int, default=5)
    r.add_argument("--select-datasets", type=_csv, default=("walch",),
                   help="datasets whose training subjects score the inner CV (default walch = PSG headline)")
    r.add_argument("--heldout-datasets", type=_csv, default=None,
                   help="restrict outer folds to subjects of these datasets (default: all pool datasets)")
    r.add_argument("--no-external", action="store_true",
                   help="do not evaluate subjects outside the training pool (e.g. BIDSleep with the Walch model)")
    r.add_argument("--lr-grid", default="full", help="decoder grid for LR: full|small|default|<grid.json>")
    r.add_argument("--mlp-grid", default="small", help="decoder grid for the MLP: full|small|default|<grid.json>")
    r.add_argument("--lr-cs", type=_floats, default=(0.01, 0.1, 1.0))
    r.add_argument("--class-weight", choices=("none", "balanced"), default="none")
    r.add_argument("--mlp-max-iter", type=int, default=200)
    r.add_argument("--mlp-alpha", type=float, default=1e-4)
    r.add_argument("--seed", type=int, default=0)
    r.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    r.add_argument("--created-at", default=None, help="ISO-8601 timestamp for the bundles (default: now, UTC)")
    r.add_argument("--feature-set-version", type=int, default=None,
                   help="override the featureSetVersion reported by algo-cli feature-names (tests only)")
    r.add_argument("--base-params", type=Path, default=None, help="AlgoParams JSON patch applied to every run")
    r.add_argument("--grid-mode", choices=("auto", "native", "loop"), default="auto",
                   help="--param-grid in one algo-cli call (native), one call per point (loop), or auto-detect")
    r.add_argument("--per-epoch", action="store_true", help="keep algo-cli --per-epoch predictions")
    r.add_argument("--keep-inner", action="store_true", help="keep inner-CV fold models and night subsets")
    r.add_argument("--reuse-features", action="store_true", help="reuse features/<variant>.csv of a previous run")
    r.add_argument("--step-feature-regex", default=r"(?i)step")
    r.add_argument("--max-bout-min", type=float, default=120.0)
    r.add_argument("--time-prior-min-count", type=float, default=300.0)
    r.add_argument("--final-variants", type=_csv, default=("instantaneous", "mean2min"))
    r.add_argument("--install-model", action="store_true",
                   help=f"also write the final bundle to {APP_MODEL_PATH} (PART B only)")
    r.add_argument("--timeout", type=float, default=None, help="per algo-cli call, seconds")
    r.add_argument("-v", "--verbose", action="store_true")
    for name in ("report", "final"):
        s = sub.add_parser(name, help=f"re-run the {name} step of an existing run")
        s.add_argument("--run", required=True)
        s.add_argument("--out-root", type=Path, default=OUT_ROOT)
        if name == "final":
            s.add_argument("--install-model", action="store_true")
    d = sub.add_parser("defaults", help="render core-algo TrainedDefaults.kt from a run's trained_defaults.json")
    d.add_argument("--run", required=True)
    d.add_argument("--out-root", type=Path, default=OUT_ROOT)
    d.add_argument("--write", action="store_true", help=f"write {kotlin_defaults.KOTLIN_PATH} (default: print)")
    d.add_argument("--output", type=Path, default=None, help="write to this path instead")
    c = sub.add_parser("compare", help="paired per-subject comparison of the default rows of several runs "
                                       "(first = reference; reporting only)")
    c.add_argument("--runs", type=_csv, required=True)
    c.add_argument("--out-root", type=Path, default=OUT_ROOT)
    c.add_argument("--estimator", default=None, help="estimator label, e.g. HYBRID-MLP (default: each run's default)")
    c.add_argument("--config", default=None, help="training configuration (default: each run's default)")
    c.add_argument("--output", type=Path, default=None, help="also write the JSON result here")
    s = sub.add_parser("sensitivity", help="evaluate a run's outer fold models on moved session windows and without "
                                           "steps (reporting only)")
    s.add_argument("--run", required=True)
    s.add_argument("--out-root", type=Path, default=OUT_ROOT)
    s.add_argument("--algo-cli", default=None)
    s.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    return p


def config_from_args(a: argparse.Namespace) -> loso.PipelineConfig:
    _choices(a.configs, loso.CONFIGS, "configs")
    _choices(a.classifiers, loso.CLASSIFIERS, "classifiers")
    _choices(a.estimators, loso.ESTIMATORS, "estimators")
    created = a.created_at or datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    base = read_json(a.base_params) if a.base_params else {}
    return loso.PipelineConfig(
        run=a.run, out_dir=a.out_root / a.run, nights_root=a.nights_root, variants=tuple(a.variants),
        configs=tuple(a.configs), classifiers=tuple(a.classifiers), estimators=tuple(a.estimators),
        train_variants=a.train_variants, selection=a.selection, inner_k=a.inner_k,
        select_datasets=tuple(a.select_datasets), heldout_datasets=tuple(a.heldout_datasets) if a.heldout_datasets
        else None, external=not a.no_external, lr_grid=a.lr_grid, mlp_grid=a.mlp_grid, lr_Cs=tuple(a.lr_cs),
        class_weight=a.class_weight, seed=a.seed, jobs=a.jobs, created_at=created,
        feature_set_version=a.feature_set_version,
        base_params=base, grid_mode=a.grid_mode, per_epoch=a.per_epoch, keep_inner=a.keep_inner,
        reuse_features=a.reuse_features, step_feature_regex=a.step_feature_regex,
        stats=LabelStatsParams(max_bout_min=a.max_bout_min, time_prior_min_count=a.time_prior_min_count),
        mlp_max_iter=a.mlp_max_iter, mlp_alpha=a.mlp_alpha, final_variants=tuple(a.final_variants),
        install_model=APP_MODEL_PATH if a.install_model else None, algo_cli=a.algo_cli, timeout_s=a.timeout,
        summary_csv=a.summary_csv,
    )


def main(argv: list[str] | None = None) -> int:
    a = build_parser().parse_args(argv)
    try:
        return _dispatch(a)
    except EXPECTED_ERRORS as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


def _dispatch(a: argparse.Namespace) -> int:
    if a.cmd == "run":
        summary = pipeline.run_pipeline(config_from_args(a), verbose=a.verbose)
        sel = summary.get("selection", {})
        print(json.dumps({k: sel.get(k) for k in ("defaultEstimator", "estimatorScores", "classifier", "hmm",
                                                  "bidSteps") if k in sel}, indent=2))
    elif a.cmd == "report":
        pipeline.rerun_report(a.out_root / a.run)
        print(a.out_root / a.run / "report.md")
    elif a.cmd == "final":
        print(json.dumps(pipeline.rerun_final(a.out_root / a.run, APP_MODEL_PATH if a.install_model else None),
                         indent=2))
    elif a.cmd == "compare":
        res = robustness.compare_runs([a.out_root / r for r in a.runs], a.estimator, a.config)
        if a.output:
            from pulsetrain.util import write_json
            write_json(a.output, res)
        print(robustness.render_compare(res), end="")
    elif a.cmd == "sensitivity":
        run_dir = a.out_root / a.run
        cli = None
        if a.algo_cli:
            from pulsetrain.algocli import AlgoCli
            cli = AlgoCli.locate(a.algo_cli, commands_log=run_dir / "sensitivity" / "commands.log")
        res = robustness.boundary_sensitivity(run_dir, cli=cli, jobs=a.jobs)
        print(robustness.render_sensitivity(res), end="")
    elif a.cmd == "defaults":
        text = kotlin_defaults.render_run(a.out_root / a.run)
        target = a.output or (kotlin_defaults.KOTLIN_PATH if a.write else None)
        if target is None:
            print(text, end="")
        else:
            Path(target).parent.mkdir(parents=True, exist_ok=True)
            Path(target).write_text(text, encoding="utf-8")
            print(target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
