#!/usr/bin/env python3
"""PulseStages offline training + leave-one-subject-out validation (see tools/train/README.md, "Training").

    mise exec -- python -I tools/train/train.py run --run <name> [options]    # everything (PART B)
    mise exec -- python -I tools/train/train.py report --run <name>           # re-render report.md / summary.json
    mise exec -- python -I tools/train/train.py final --run <name> [--install-model]   # re-train the final model
    mise exec -- python -I tools/train/train.py defaults --run <name> --write  # core-algo TrainedDefaults.kt
    mise exec -- python -I tools/train/train.py compare --runs <ref>,<other>,...  # paired CIs between runs
    mise exec -- python -I tools/train/train.py sensitivity --run <name>      # session-boundary sensitivity
    mise exec -- python -I tools/train/train.py bias --run <name>             # stage-proportion experiment
    mise exec -- python -I tools/train/train.py bias-report --run <name>      # re-render its summary / report
    mise exec -- python -I tools/train/train.py bias-final --run <name> [--install-model]   # its final model
    mise exec -- python -I tools/train/train.py naps --run bias-base --out-run naps   # nap-proxy evaluation
    mise exec -- python -I tools/train/train.py naps-report --out-run naps            # re-render its report
    mise exec -- python -I tools/train/train.py naps-final --out-run naps-final [--install-model]   # its bundle
    mise exec -- python -I tools/train/train.py naps-verify --out-run naps-verify     # check the Kotlin build
    mise exec -- python -I tools/train/train.py naps-posthoc --rerun naps-rerun        # post-hoc, reporting only
    mise exec -- python -I tools/train/train.py nap-model --out-run nap-model          # dedicated nap model (prereg)
    mise exec -- python -I tools/train/train.py nap-model-report --out-run nap-model   # re-render its report
    mise exec -- python -I tools/train/train.py nap-model-final --install-model        # its nap section + defaults
    mise exec -- python -I tools/train/train.py nap-model-verify                       # nights unchanged, new build
    mise exec -- python -I tools/train/train.py naps-realistic-gates --out-run naps-realistic-gates   # G0-G4 (prereg)
    mise exec -- python -I tools/train/train.py naps-realistic-timing --out-run naps-realistic-timing  # section 18
    mise exec -- python -I tools/train/train.py naps-realistic --out-run naps-realistic  # realistic naps (prereg)
    mise exec -- python -I tools/train/train.py naps-realistic-report --out-run naps-realistic   # re-render it
    mise exec -- python -I tools/train/train.py naps-realistic-final --exp naps-realistic --out-run naps-realistic-final
    mise exec -- python -I tools/train/train.py naps-realistic-external --exp naps-realistic --final ... --data ...
    mise exec -- python -I tools/train/train.py naps-realistic-install --exp ... --final ... --external ...
    mise exec -- python -I tools/train/train.py naps-realistic-verify --exp ... --final ... --out-run ...

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

from pulsetrain import (bias, kotlin_defaults, loso, napmodel, naps, naps_posthoc, napsrealistic,  # noqa: E402
                        pipeline, robustness)
from pulsetrain.algocli import AlgoCliError  # noqa: E402
from pulsetrain.bundle import BundleError  # noqa: E402
from pulsetrain.features import FeatureFileError  # noqa: E402
from pulsetrain.report import MetricsFormatError  # noqa: E402
from pulsetrain.stats import LabelStatsParams  # noqa: E402
from pulsetrain.train_final import APP_MODEL_PATH  # noqa: E402
from pulsetrain.util import read_json  # noqa: E402

OUT_ROOT = TRAIN_DIR / "out"
EXPECTED_ERRORS = (AlgoCliError, BundleError, FeatureFileError, MetricsFormatError, FileNotFoundError,
                   kotlin_defaults.DefaultsError, robustness.RobustnessError, bias.BiasError, naps.NapError,
                   napmodel.NapModelError, napsrealistic.NapsRealisticError)
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
    e = sub.add_parser("bias", help="pre-registered stage-proportion experiment on a run made with --keep-inner "
                                     "(docs/prereg/prereg-bias.md): beta, proportion matching, both")
    e.add_argument("--run", required=True)
    e.add_argument("--out-root", type=Path, default=OUT_ROOT)
    e.add_argument("--algo-cli", default=None)
    e.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    _bias_common(e)
    er = sub.add_parser("bias-report", help="re-render <run>/<out-name>/{summary.json,report.md} of a finished `bias` "
                                            "from its results.jsonl / decisions.json (no algo-cli)")
    er.add_argument("--run", required=True)
    er.add_argument("--out-root", type=Path, default=OUT_ROOT)
    er.add_argument("--out-name", default=bias.DEFAULT_OUT, help="experiment directory inside the run (default bias)")
    f = sub.add_parser("bias-final", help="final bundle + trained defaults of the candidate adopted by `bias`")
    f.add_argument("--run", required=True, help="the run the experiment was made on")
    f.add_argument("--out-root", type=Path, default=OUT_ROOT)
    f.add_argument("--out-run", default="bias-final", help="output run directory (final/, trained_defaults.json)")
    f.add_argument("--selection-from", type=Path, default=None,
                   help="summary.json of the complete-protocol run documented in TrainedDefaults (e.g. loso-v2)")
    f.add_argument("--install-model", action="store_true")
    f.add_argument("--algo-cli", default=None)
    f.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    _bias_common(f)
    n = sub.add_parser("naps", help="pre-registered nap-proxy evaluation (docs/prereg/prereg-naps.md) on the fold "
                                    "artefacts of a finished `bias` experiment (run made with --keep-inner)")
    n.add_argument("--run", default="bias-base", help="the run of the stage-proportion experiment (read only)")
    n.add_argument("--out-root", type=Path, default=OUT_ROOT)
    n.add_argument("--out-run", default="naps", help="output directory under --out-root")
    n.add_argument("--nights-root", type=Path, default=NIGHTS_ROOT)
    n.add_argument("--context-run", default="loso-v2",
                   help="run whose RULES / HMM-table night results the re-evaluated nights are compared with")
    n.add_argument("--algo-cli", default=None)
    n.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    nr = sub.add_parser("naps-report", help="re-render <out-run>/{summary.json,report.md} of `naps` (no algo-cli)")
    nr.add_argument("--out-root", type=Path, default=OUT_ROOT)
    nr.add_argument("--out-run", default="naps")
    for name, default_out, what in (("naps-final", "naps-final", "final bundle + trained defaults of the candidate "
                                                                 "adopted by `naps`"),
                                    ("naps-verify", "naps-verify", "check a build that implements the adopted "
                                                                   "candidate against the `naps` results")):
        q = sub.add_parser(name, help=what)
        q.add_argument("--run", default="bias-base", help="the run of the stage-proportion experiment (read only)")
        q.add_argument("--exp", default="naps", help="the `naps` output directory under --out-root")
        q.add_argument("--out-root", type=Path, default=OUT_ROOT)
        q.add_argument("--out-run", default=default_out)
        q.add_argument("--nights-root", type=Path, default=NIGHTS_ROOT)
        q.add_argument("--algo-cli", default=None)
        q.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
        if name == "naps-final":
            q.add_argument("--base-run", default="bias-final", help="run with the shipped final bundle / defaults")
            q.add_argument("--install-model", action="store_true")
        else:
            q.add_argument("--final", default="naps-final", help="run whose final bundle is checked (resubstitution)")
    ph = sub.add_parser("naps-posthoc", help="post-hoc analyses of the `naps` results (reporting only, not "
                                             "pre-registered): proxies of 150 / 170 min, C-180 vs C-120, the rule "
                                             "with the context estimators, flags, shares, kappa, final fit, re-run")
    ph.add_argument("--run", default="bias-base", help="the run of the stage-proportion experiment (read only)")
    ph.add_argument("--exp", default="naps", help="the `naps` output directory under --out-root")
    ph.add_argument("--final", default="naps-final", help="the `naps-final` output directory (fit quality)")
    ph.add_argument("--rerun", default=None, help="a re-run of `naps` with the current code, compared file by file")
    ph.add_argument("--lengths", type=lambda x: tuple(int(v) for v in _csv(x)), default=naps_posthoc.BETWEEN_LENGTHS,
                    help="proxy lengths (min) decoded with A and the adopted C (default 150,170)")
    ph.add_argument("--no-decode", action="store_true", help="skip the new decodes (no algo-cli)")
    ph.add_argument("--out-root", type=Path, default=OUT_ROOT)
    ph.add_argument("--out-run", default="naps-posthoc")
    ph.add_argument("--nights-root", type=Path, default=NIGHTS_ROOT)
    ph.add_argument("--algo-cli", default=None)
    ph.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    _nap_model_parsers(sub)
    _naps_realistic_parsers(sub)
    return p


NAPS_REALISTIC = ("naps-realistic-gates", "naps-realistic-timing", "naps-realistic", "naps-realistic-report",
                  "naps-realistic-final", "naps-realistic-external", "naps-realistic-install", "naps-realistic-verify")


def _naps_realistic_parsers(sub) -> None:
    """The subcommands of the pre-registered realistic-nap experiment (docs/prereg/prereg-naps-realistic.md section
    18); each logs its START / END / RESULT lines in the commands log (default <out-root>/commands.log)."""
    jobs = max(1, (os.cpu_count() or 2) - 2)
    helps = {
        "naps-realistic-gates": "reproduction-only gates G0, G1, G2, G3a, G4 (no candidate is fitted)",
        "naps-realistic-timing": "featureless dry run of section 18 (previous bundle, one variant; timing only, NOTE)",
        "naps-realistic": "the experiment: sessions, gates, nested inner CV, C0 / C1 / C2, LOSO, rules (i) and (ii)",
        "naps-realistic-report": "re-render <out-run>/{summary.json,report.md} from the stored records (no algo-cli)",
        "naps-realistic-final": "final nap section of the adopted candidate in the shipped bundle (section 14)",
        "naps-realistic-external": "OpenNeuro ds006576: freeze check, decodes, rule (iii) (section 13)",
        "naps-realistic-install": "apply rule (iii): install the bundle (--install-model) and write trained_defaults",
        "naps-realistic-verify": "with the final build: nights unchanged, decodes reproduced (section 15)"}
    for name in NAPS_REALISTIC:
        q = sub.add_parser(name, help=helps[name])
        q.add_argument("--out-root", type=Path, default=OUT_ROOT)
        q.add_argument("--log", type=Path, default=None, help="commands log (default <out-root>/commands.log)")
        if name in ("naps-realistic-report", "naps-realistic-install"):
            if name == "naps-realistic-report":
                q.add_argument("--out-run", default="naps-realistic")
            else:
                q.add_argument("--exp", default="naps-realistic")
                q.add_argument("--final", default="naps-realistic-final")
                q.add_argument("--external", default="naps-realistic-external")
                q.add_argument("--install-model", action="store_true",
                               help=f"copy an installed bundle to {APP_MODEL_PATH}")
            continue
        q.add_argument("--out-run", default=name)
        q.add_argument("--run", default="bias-base", help="the night run with the fold bundles and groups (read only)")
        q.add_argument("--naps", default="naps", help="the `naps` experiment (b_short_g(180))")
        q.add_argument("--posthoc", default="naps-posthoc", help="`naps-posthoc` (read only)")
        q.add_argument("--nap-model", default="nap-model", help="the `nap-model` experiment (N1; read only)")
        q.add_argument("--g2-base", type=Path, default=None,
                       help="saved base build references of gate G2 (default <out-root>/naps-realistic-base)")
        q.add_argument("--previous", type=Path, default=napsrealistic.PREVIOUS_BUNDLE,
                       help="the previous (shipped) bundle, SHA-256 583df315...")
        q.add_argument("--nights-root", type=Path, default=NIGHTS_ROOT)
        q.add_argument("--algo-cli", default=None)
        q.add_argument("--jobs", type=int, default=jobs)
        q.add_argument("--created-at", default=None, help="ISO-8601 timestamp of the bundles (default: the run's)")
        if name == "naps-realistic-timing":
            q.add_argument("--seeds", type=int, default=napsrealistic.FEATURELESS_SEED_CHOICES[0],
                           choices=napsrealistic.FEATURELESS_SEED_CHOICES, help="featureless seeds 0..n-1 timed")
        if name == "naps-realistic":
            q.add_argument("--seeds", type=int, default=None, choices=napsrealistic.FEATURELESS_SEED_CHOICES,
                           help="featureless seeds 0..n-1 (default: the dry run's logged decision, else 0-19; an "
                                "explicit value must equal the logged decision; section 18)")
            q.add_argument("--external-void", default=None, metavar="REASON",
                           help="run without a frozen ds006576 test set (no manifest / yasa-summary NOTE pair in the "
                                "log): the external test is void; REASON is logged before the START line")
        if name == "naps-realistic-timing":
            q.add_argument("--variant", default=None, help="the variant timed (default instantaneous)")
        if name in ("naps-realistic-final", "naps-realistic-external", "naps-realistic-verify"):
            q.add_argument("--exp", default="naps-realistic")
        if name == "naps-realistic-final":
            q.add_argument("--base-run", default="nap-model-final", help="run with the shipped bundle / defaults")
        if name in ("naps-realistic-external", "naps-realistic-verify"):
            q.add_argument("--final", default="naps-realistic-final")
        if name == "naps-realistic-external":
            q.add_argument("--data", type=Path, required=True, help="tools/train/data/external/ds006576")
            q.add_argument("--lock", type=Path, default=None, help="requirements-external.lock (default: committed)")
        if name == "naps-realistic-verify":
            q.add_argument("--installed", type=Path, default=APP_MODEL_PATH, help="the installed bundle")


def _nap_model_parsers(sub) -> None:
    jobs = max(1, (os.cpu_count() or 2) - 2)
    for name, default_out, what in (
            ("nap-model", "nap-model", "pre-registered dedicated nap model (docs/prereg/prereg-nap-model.md): nap "
                                       "proxies v2, LOSO, N0 / N1 / N2, rules (i) and (ii)"),
            ("nap-model-final", "nap-model-final", "final nap model of the candidate adopted by `nap-model` (nap "
                                                   "section of the shipped bundle) and trained defaults"),
            ("nap-model-verify", "nap-model-verify", "with the final build: nights unchanged, the adopted candidate "
                                                     "reproduced, the installed bundle accepted")):
        q = sub.add_parser(name, help=what)
        q.add_argument("--run", default="bias-base", help="the night run with the fold bundles and groups (read only)")
        q.add_argument("--naps", default="naps", help="the `naps` experiment (b_short_g(180), stored C-180 proxies)")
        q.add_argument("--posthoc", default="naps-posthoc", help="`naps-posthoc` (stored 150 / 170-min proxies)")
        q.add_argument("--out-root", type=Path, default=OUT_ROOT)
        q.add_argument("--out-run", default=default_out)
        q.add_argument("--nights-root", type=Path, default=NIGHTS_ROOT)
        q.add_argument("--algo-cli", default=None)
        q.add_argument("--jobs", type=int, default=jobs)
        if name != "nap-model":
            q.add_argument("--exp", default="nap-model", help="the `nap-model` output directory under --out-root")
        if name == "nap-model-final":
            q.add_argument("--base-run", default="naps-final", help="run with the shipped final bundle / defaults")
            q.add_argument("--install-model", action="store_true")
            q.add_argument("--created-at", default=None, help="ISO-8601 timestamp of the nap section (default: now)")
        if name == "nap-model-verify":
            q.add_argument("--final", default="nap-model-final", help="the `nap-model-final` output directory")
            q.add_argument("--previous", type=Path, default=None,
                           help="the previously shipped bundle (default: <out-root>/naps-final/final/hybrid_model.json)")
    r = sub.add_parser("nap-model-report", help="re-render <out-run>/{summary.json,report.md} of `nap-model`")
    r.add_argument("--out-root", type=Path, default=OUT_ROOT)
    r.add_argument("--out-run", default="nap-model")


def _bias_common(p: argparse.ArgumentParser) -> None:
    p.add_argument("--out-name", default=bias.DEFAULT_OUT, help="experiment directory inside the run (default bias)")
    p.add_argument("--allow-other-build", action="store_true",
                   help="decode with an algo-cli build other than the run's (logged and recorded; BASE must still "
                        "reproduce the run night by night)")


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
    argv = list(sys.argv[1:] if argv is None else argv)
    a = build_parser().parse_args(argv)
    try:
        if a.cmd in NAPS_REALISTIC:
            return _naps_realistic(a, argv)
        return _dispatch(a)
    except EXPECTED_ERRORS as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


def _naps_realistic(a: argparse.Namespace, argv: list[str]) -> int:
    """A ``naps-realistic*`` subcommand between its START and END lines in the commands log; refused unless the
    pre-registration NOTE is in the log (and the committed text has the registered digest)."""
    log_path = a.log or a.out_root / "commands.log"
    pre = napsrealistic.prereg_record(log_path)
    if not pre["passed"]:
        raise napsrealistic.NapsRealisticError(f"refused: the pre-registration does not match or its NOTE is not in "
                                               f"{log_path}: {pre}")
    rl = napsrealistic.RunLog(log_path, argv)
    if a.cmd == "naps-realistic":          # seeds and the test-set freeze are checked before the START line
        flight = napsrealistic.experiment_preflight(log_path, a.seeds, a.external_void)
        a.seeds = flight["seeds"]
        if flight["externalVoid"]:
            rl.note(napsrealistic.external_void_note(flight["externalVoid"]))
    rl.start()
    rc = 1
    try:
        rc = _naps_realistic_run(a, rl, log_path)
        return rc
    except napsrealistic.GateFailure as e:
        failed = [k for k, g in e.gates.items() if isinstance(g, dict) and g.get("passed") is False]
        rl.result(getattr(a, "out_run", a.cmd), f"stopped by gate {', '.join(failed) or '?'}: {e}; gates.json written")
        rc = 2
        raise
    except EXPECTED_ERRORS:
        rc = 2
        raise
    finally:
        rl.end(rc)


def _naps_realistic_run(a: argparse.Namespace, rl, log_path: Path) -> int:
    from pulsetrain.algocli import AlgoCli

    nr = napsrealistic
    if a.cmd == "naps-realistic-report":
        res = nr.rerender(a.out_root / a.out_run)
        rl.note(f"naps-realistic-report re-rendered {a.out_run}/summary.json and report.md: {nr.result_line(res)}")
        print(json.dumps(res["decision"], indent=2))
        print(a.out_root / a.out_run / "report.md")
        return 0
    if a.cmd == "naps-realistic-install":
        rec = nr.install(a.out_root / a.exp, a.out_root / a.final, a.out_root / a.external,
                         install_model=APP_MODEL_PATH if a.install_model else None)
        rl.result(a.final, nr.install_result_line(rec))
        print(json.dumps(rec, indent=2))
        return 0
    out = a.out_root / a.out_run
    pipeline.setup_logging(out)
    cli = AlgoCli.locate(a.algo_cli, commands_log=out / "algocli.log")
    if a.cmd == "naps-realistic-external":
        res = nr.external(cli, a.out_root / a.exp, a.out_root / a.final, a.data, out, log_path,
                          previous_bundle=a.previous, lock_path=a.lock or nr.ds.LOCK_PATH)
        rl.result(a.out_run, nr.external_result_line(res))
        print(json.dumps(nr.naps._clean(res["ruleIII"]), indent=2))
        print(out / "report.md")
        return 0
    inp = nr.Inputs(a.out_root / a.run, a.out_root / a.naps, a.out_root / a.nap_model, a.nights_root,
                    posthoc_dir=a.out_root / a.posthoc if a.posthoc else None,
                    g2_base=a.g2_base or a.out_root / "naps-realistic-base", previous_bundle=a.previous)
    if a.cmd == "naps-realistic-gates":
        g = nr.run_gates(inp, cli, out, jobs=a.jobs, created_at=a.created_at, log_path=log_path)
        rl.result(a.out_run, "G0, G1, G2, G3a passed; G4 " + ("passed" if g["G4"]["passed"] else
                                                              "failed (C0 would be dropped)")
                  + f"; gates.json in out/{a.out_run}")
        print(json.dumps({k: v.get("passed") for k, v in g.items() if isinstance(v, dict)}, indent=2))
    elif a.cmd == "naps-realistic-timing":
        t = nr.featureless_timing(inp, cli, out, a.jobs, seeds=a.seeds, variant=a.variant)
        from pulsetrain.util import write_json
        write_json(out / "timing.json", t)
        rl.note(nr.timing_note(t))
        print(json.dumps(t, indent=2))
    elif a.cmd == "naps-realistic":
        res = nr.run_experiment(inp, cli, out, jobs=a.jobs, seeds=a.seeds, created_at=a.created_at,
                                log_path=log_path, external_void=a.external_void)
        rl.result(a.out_run, nr.result_line(res))
        print(json.dumps(res["decision"], indent=2))
        print(out / "report.md")
    elif a.cmd == "naps-realistic-final":
        res = nr.final_model(inp, cli, a.out_root / a.exp, a.out_root / a.base_run, out, jobs=a.jobs,
                             created_at=a.created_at, log_path=log_path)
        rl.result(a.out_run, f"final bundle of {res['adopted'] or 'none (shipped bundle unchanged)'}"
                             f" sha256 {res['bundleSha256']}")
        print(json.dumps(nr.naps._clean({k: v for k, v in res.items() if k != "fit"}), indent=2))
    elif a.cmd == "naps-realistic-verify":
        res = nr.verify(inp, cli, a.out_root / a.exp, a.out_root / a.final, out, installed_bundle=a.installed,
                        jobs=a.jobs, log_path=log_path)
        rl.result(a.out_run, f"verification of {res['model']} passed: " + ", ".join(
            k for k in res if k.startswith("item") and isinstance(res[k], dict) and res[k].get("passed")))
        print(json.dumps(nr.naps._clean({k: v for k, v in res.items() if k not in ("algoCli", "code")}), indent=2))
    return 0


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
    elif a.cmd == "bias-report":
        res = bias.rerender(a.out_root / a.run, out_name=a.out_name)
        print(json.dumps(res["decision"], indent=2))
        print(a.out_root / a.run / a.out_name / "report.md")
    elif a.cmd in ("bias", "bias-final"):
        run_dir = a.out_root / a.run
        log_dir = run_dir / a.out_name if a.cmd == "bias" else a.out_root / a.out_run
        pipeline.setup_logging(log_dir)
        cli = None
        if a.algo_cli:
            from pulsetrain.algocli import AlgoCli
            cli = AlgoCli.locate(a.algo_cli, commands_log=log_dir / "commands.log")
        if a.cmd == "bias":
            res = bias.run_experiment(run_dir, cli=cli, jobs=a.jobs, out_name=a.out_name,
                                      allow_other_build=a.allow_other_build)
            print(json.dumps(res["decision"], indent=2))
            print(run_dir / a.out_name / "report.md")
        else:
            res = bias.final_model(run_dir, a.out_root / a.out_run, cli=cli, jobs=a.jobs,
                                   install=APP_MODEL_PATH if a.install_model else None,
                                   selection_from=a.selection_from, out_name=a.out_name,
                                   allow_other_build=a.allow_other_build)
            print(json.dumps({k: v for k, v in res.items() if k != "fit"}, indent=2))
    elif a.cmd == "naps":
        out = a.out_root / a.out_run
        pipeline.setup_logging(out)
        from pulsetrain.algocli import AlgoCli
        cli = AlgoCli.locate(a.algo_cli, commands_log=out / "algocli.log")
        inp = naps.Inputs(a.out_root / a.run, a.nights_root,
                          context_run=a.out_root / a.context_run if a.context_run else None)
        res = naps.run_experiment(inp, cli, out, jobs=a.jobs)
        print(json.dumps(res["decision"], indent=2))
        print(out / "report.md")
    elif a.cmd == "naps-report":
        res = naps.rerender(a.out_root / a.out_run)
        print(json.dumps(res["decision"], indent=2))
        print(a.out_root / a.out_run / "report.md")
    elif a.cmd in ("naps-final", "naps-verify"):
        out = a.out_root / a.out_run
        pipeline.setup_logging(out)
        from pulsetrain.algocli import AlgoCli
        cli = AlgoCli.locate(a.algo_cli, commands_log=out / "algocli.log")
        inp = naps.Inputs(a.out_root / a.run, a.nights_root)
        if a.cmd == "naps-final":
            res = naps.final_model(inp, cli, a.out_root / a.exp, a.out_root / a.base_run, out,
                                   install=APP_MODEL_PATH if a.install_model else None)
            print(json.dumps({k: v for k, v in res.items() if k != "fit"}, indent=2))
        else:
            final = a.out_root / a.final / "final" / "hybrid_model.json"
            res = naps.verify_implementation(inp, cli, a.out_root / a.exp, out,
                                             final_bundle=final if final.is_file() else None, jobs=a.jobs)
            print(json.dumps({k: v for k, v in res.items() if k != "algoCli"}, indent=2))
    elif a.cmd == "naps-posthoc":
        out = a.out_root / a.out_run
        pipeline.setup_logging(out)
        cli = None
        if not a.no_decode:
            from pulsetrain.algocli import AlgoCli
            cli = AlgoCli.locate(a.algo_cli, commands_log=out / "algocli.log")
        inp = naps.Inputs(a.out_root / a.run, a.nights_root)
        res = naps_posthoc.run_posthoc(inp, cli, a.out_root / a.exp, out, final_dir=a.out_root / a.final,
                                       rerun_dir=a.out_root / a.rerun if a.rerun else None, lengths=a.lengths,
                                       jobs=a.jobs)
        print(json.dumps({"ruleWithContext": res["ruleWithContext"]["decision"]["adopted"],
                          "rerunIdentical": (res.get("rerun") or {}).get("allIdentical"),
                          "check": (res.get("between") or {}).get("check")}, indent=2))
        print(out / "report.md")
    elif a.cmd == "nap-model-report":
        res = napmodel.rerender(a.out_root / a.out_run)
        print(json.dumps({"decision": res["decision"], "policy": res["policy"]}, indent=2))
        print(a.out_root / a.out_run / "report.md")
    elif a.cmd in ("nap-model", "nap-model-final", "nap-model-verify"):
        out = a.out_root / a.out_run
        pipeline.setup_logging(out)
        from pulsetrain.algocli import AlgoCli
        cli = AlgoCli.locate(a.algo_cli, commands_log=out / "algocli.log")
        posthoc = a.out_root / a.posthoc if a.posthoc else None
        inp = napmodel.Inputs(a.out_root / a.run, a.out_root / a.naps, a.nights_root, posthoc_dir=posthoc)
        if a.cmd == "nap-model":
            res = napmodel.run_experiment(inp, cli, out, jobs=a.jobs)
            print(json.dumps({"decision": res["decision"], "policy": res["policy"]}, indent=2))
            print(out / "report.md")
        elif a.cmd == "nap-model-final":
            res = napmodel.final_model(inp, cli, a.out_root / a.exp, a.out_root / a.base_run, out, jobs=a.jobs,
                                       install=APP_MODEL_PATH if a.install_model else None, created_at=a.created_at)
            print(json.dumps({k: v for k, v in res.items() if k not in ("fit", "proxyCounts")}, indent=2))
        else:
            prev = a.previous or a.out_root / "naps-final" / "final" / "hybrid_model.json"
            res = napmodel.verify(inp, cli, a.out_root / a.exp, a.out_root / a.final, prev, out, jobs=a.jobs)
            print(json.dumps({k: v for k, v in res.items() if k not in ("algoCli", "code")}, indent=2))
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
