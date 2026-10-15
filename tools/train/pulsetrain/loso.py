"""Leave-one-subject-out training / validation through the Kotlin ``algo-cli``.

For every training configuration (which datasets are pooled, how missing step channels are handled), night variant
and emission classifier:

- outer folds: each subject of the pool (restricted to ``heldout_datasets``) is held out once; everything (feature
  standardization, classifier, gamma durations, bout transitions, initial distribution, time prior, class priors,
  HMM MLE) is fitted on the other subjects only. Subjects of datasets outside the pool are evaluated as one
  ``external`` fold with a model fitted on the whole pool (e.g. BIDSleep with the Walch-only model).
- nested selection (never touching the held-out subject): grouped, dataset-stratified K-fold over the fold's
  selection subjects (``per-subject``: all training subjects; ``per-group``: the training subjects minus the
  held-out subject's outer group, shared by the group -> ~K times cheaper, still leak-free). For LR the L2 strength
  C is chosen by the inner held-out log-loss; then one ``algo-cli evaluate --param-grid`` run with the inner fold
  models (``--fold-models``) scores the decoder grid; the point with the best mean per-subject kappa wins (ties ->
  grid order, which starts at the AlgoParams defaults). Inner scoring uses the subjects of ``select_datasets``.
- outer evaluation: held-out subjects are grouped by their chosen decoder params; one ``evaluate --fold-models``
  call per group.

Baselines without training: RULES (AlgoParams defaults) and HMM with the physiology table (passed explicitly, since
the AlgoParams HMM defaults may be trained estimates). HMM-trained: per-fold
supervised MLE (``folds/<subject>.hmm-params.json``), evaluated per fold with ``--params``.

Training rows: epochs with HR data and a label, from the evaluated variant, or (``train_variants="pair"``) from the
evaluated variant and its sampling twin (instantaneous <-> mean2min, same session window). Label statistics use the
evaluated variant's label sequences only (the twin's labels are identical).
"""

from __future__ import annotations

import hashlib
import itertools
import json
import logging
import math
import multiprocessing as mp
import shutil
import time
from concurrent.futures import FIRST_EXCEPTION, Future, ProcessPoolExecutor, ThreadPoolExecutor, wait
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

import numpy as np

from . import report
from .algocli import AlgoCli
from .bundle import build_bundle, write_bundle
from .features import FeatureFileError, FeatureTable, concat, read_features, step_feature_names
from .models import ClassifierSpec, TrainedClassifier, fit_classifier, log_loss
from .nightset import NightIndex, load_index
from .stats import (DEFAULT_MEAN_BOUTS, DEFAULT_TRANSITIONS, PHYSIOLOGY_EMISSIONS, SPEC_TIME_PRIOR,
                    TIME_PRIOR_KEYS, LabelStats, LabelStatsParams, fit_label_stats, hmm_emissions, hmm_time_prior,
                    hr_norm_ordered)
from .util import canonical_json, deep_merge, read_json, write_json

log = logging.getLogger(__name__)

ALL_VARIANTS = ("instantaneous", "mean2min", "instantaneous-psg", "mean2min-psg")
TWINS = (("instantaneous", "mean2min"), ("mean2min", "instantaneous"))
ESTIMATORS = ("RULES", "HMM-table", "HMM-trained", "HYBRID")
CLASSIFIERS = ("lr", "mlp")


@dataclass(frozen=True)
class TrainConfig:
    name: str
    datasets: tuple[str, ...]
    step_policy: str                    # none | mask | nonstep
    description: str

    @property
    def pool(self) -> str:
        return pool_name(self.datasets)


CONFIGS: dict[str, TrainConfig] = {
    "walch": TrainConfig("walch", ("walch",), "none", "Walch only, step features used"),
    "walch+bid-mask": TrainConfig("walch+bid-mask", ("walch", "bidsleep"), "mask",
                                  "Walch + BIDSleep, step features masked for all nights"),
    "walch+bid-zero": TrainConfig("walch+bid-zero", ("walch", "bidsleep"), "zero",
                                  "Walch + BIDSleep; BIDSleep nights have no step channel and keep the step features "
                                  "that algo-cli computes for them (0 steps, as the app does for a night without step "
                                  "records)"),
}
# Configurations of earlier runs that may no longer be trained (descriptions kept so old reports re-render).
RETIRED_CONFIGS: dict[str, TrainConfig] = {
    "walch+bid-nonstep": TrainConfig("walch+bid-nonstep", ("walch", "bidsleep"), "nonstep",
                                     "RETIRED (train/inference mismatch): BIDSleep step features set to missing in "
                                     "training, while algo-cli and the app compute 0 steps for them"),
}
STEP_POLICIES = ("none", "zero", "mask")


def config_info(name: str) -> TrainConfig:
    """Current or retired training configuration ``name`` (retired ones only for re-rendering old reports)."""
    if name in CONFIGS:
        return CONFIGS[name]
    return RETIRED_CONFIGS[name]

GRIDS: dict[str, dict[str, tuple]] = {
    # value order matters for ties: the first point is the AlgoParams default (EXPLICIT, 1.0, 1.0)
    "full": {"durationModel": ("EXPLICIT", "GEOMETRIC"), "emissionWeight": (1.0, 0.7, 0.5, 0.3),
             "timePriorWeight": (1.0, 0.5, 2.0, 0.0)},
    "small": {"durationModel": ("EXPLICIT", "GEOMETRIC"), "emissionWeight": (1.0, 0.5),
              "timePriorWeight": (1.0, 0.0)},
    "default": {"durationModel": ("EXPLICIT",), "emissionWeight": (1.0,), "timePriorWeight": (1.0,)},
    # the evidence nap model of the realistic-nap pre-registration (docs/prereg/prereg-naps-realistic.md section
    # 6.2): the time prior off, 8 points in the order of grid_points (ties: grid order)
    "nap-evidence": {"durationModel": ("EXPLICIT", "GEOMETRIC"), "emissionWeight": (1.0, 0.7, 0.5, 0.3),
                     "timePriorWeight": (0.0,)},
}


def pool_name(datasets: Iterable[str]) -> str:
    return "+".join(datasets)


def twin_of(variant: str) -> str | None:
    for a, b in TWINS:
        if variant == a or variant.startswith(a + "-"):
            return b + variant[len(a):]
    return None


def train_variants_for(variant: str, mode: str, available: Iterable[str]) -> tuple[str, ...]:
    if mode == "same":
        return (variant,)
    if mode != "pair":
        raise ValueError(f"train_variants must be same|pair, got {mode!r}")
    t = twin_of(variant)
    return (variant, t) if t is not None and t in set(available) else (variant,)


def grid_spec(name_or_path: str) -> dict[str, tuple]:
    if name_or_path in GRIDS:
        return GRIDS[name_or_path]
    spec = read_json(Path(name_or_path))
    return {k: tuple(spec[k]) for k in ("durationModel", "emissionWeight", "timePriorWeight")}


def grid_points(spec: Mapping[str, Sequence], base_params: Mapping | None = None) -> list[dict]:
    keys = ("durationModel", "emissionWeight", "timePriorWeight")
    pts = []
    for i, vals in enumerate(itertools.product(*(spec[k] for k in keys))):
        hybrid = {"emissionWeight": float(vals[1]), "timePriorWeight": float(vals[2]), "durationModel": str(vals[0])}
        params = deep_merge(dict(base_params or {}), {"estimator": "HYBRID", "hybrid": hybrid})
        pts.append({"id": f"g{i:03d}", "index": i, "hybrid": hybrid, "params": params})
    return pts


# --------------------------------------------------------------------------------------------- folds ----

@dataclass(frozen=True)
class Fold:
    name: str                           # held-out subject id, or "external"
    test: tuple[str, ...]
    train: tuple[str, ...]
    kind: str                           # loso | external


def make_folds(pool: Sequence[str], heldout: Sequence[str], external: Sequence[str] = ()) -> list[Fold]:
    pool_s = sorted(set(pool))
    if not set(heldout) <= set(pool_s):
        raise ValueError("held-out subjects must belong to the training pool")
    if set(external) & set(pool_s):
        raise ValueError("external subjects must not belong to the training pool")
    folds = [Fold(s, (s,), tuple(x for x in pool_s if x != s), "loso") for s in sorted(set(heldout))]
    if external:
        folds.append(Fold("external", tuple(sorted(set(external))), tuple(pool_s), "external"))
    return folds


def inner_folds(subjects: Sequence[str], k: int, seed: int,
                strata: Callable[[str], str] | None = None) -> list[tuple[tuple[str, ...], tuple[str, ...]]]:
    """Deterministic grouped K-fold over subjects: (train subjects, test subjects) per fold.

    Subjects are shuffled with ``seed`` within each stratum (dataset) and dealt round-robin, continuing across
    strata, so every fold gets a share of every dataset. K is reduced to the number of subjects if needed.
    """
    subs = sorted(set(subjects))
    if len(subs) < 2:
        raise ValueError("inner CV needs at least 2 subjects")
    k = max(2, min(int(k), len(subs)))
    groups: dict[str, list[str]] = {}
    for s in subs:
        groups.setdefault(strata(s) if strata else "", []).append(s)
    rng = np.random.default_rng(seed)
    tests: list[list[str]] = [[] for _ in range(k)]
    pos = 0
    for g in sorted(groups):
        for s in rng.permutation(np.array(groups[g], dtype=object)):
            tests[pos % k].append(str(s))
            pos += 1
    return [(tuple(x for x in subs if x not in set(t)), tuple(sorted(t))) for t in tests]


def selection_sets(folds: Sequence[Fold], pool: Sequence[str], mode: str, k: int, seed: int,
                   strata: Callable[[str], str] | None = None) -> dict[str, tuple[str, ...]]:
    """Subjects used for the nested selection of each outer fold (never contains the fold's test subjects)."""
    if mode == "per-subject":
        return {f.name: f.train for f in folds}
    if mode != "per-group":
        raise ValueError(f"selection must be per-subject|per-group, got {mode!r}")
    group_of = {s: set(te) for _, te in inner_folds(pool, k, seed, strata) for s in te}
    out = {}
    for f in folds:
        if f.kind == "external":
            out[f.name] = f.train
        else:
            out[f.name] = tuple(sorted(set(f.train) - group_of[f.test[0]]))
    return out


def subjects_key(subjects: Iterable[str]) -> str:
    return hashlib.sha1("\n".join(sorted(subjects)).encode()).hexdigest()[:12]


# ------------------------------------------------------------------------------------------ pipeline config ----

@dataclass
class PipelineConfig:
    run: str
    out_dir: Path
    nights_root: Path
    variants: tuple[str, ...] = ALL_VARIANTS
    configs: tuple[str, ...] = tuple(CONFIGS)
    classifiers: tuple[str, ...] = CLASSIFIERS
    estimators: tuple[str, ...] = ESTIMATORS
    train_variants: str = "pair"
    selection: str = "per-subject"
    inner_k: int = 5
    select_datasets: tuple[str, ...] = ("walch",)
    heldout_datasets: tuple[str, ...] | None = None
    external: bool = True
    lr_grid: str = "full"
    mlp_grid: str = "small"
    lr_Cs: tuple[float, ...] = (0.01, 0.1, 1.0)
    class_weight: str = "none"
    seed: int = 0
    jobs: int = 1
    created_at: str = "1970-01-01T00:00:00Z"
    feature_set_version: int | None = None
    base_params: dict = field(default_factory=dict)
    grid_mode: str = "auto"
    per_epoch: bool = False
    keep_inner: bool = False
    reuse_features: bool = False
    step_feature_regex: str = r"(?i)step"
    stats: LabelStatsParams = field(default_factory=LabelStatsParams)
    mlp_max_iter: int = 200
    mlp_alpha: float = 1e-4
    final_variants: tuple[str, ...] = ("instantaneous", "mean2min")
    install_model: Path | None = None
    algo_cli: str | None = None
    timeout_s: float | None = None
    summary_csv: Path | None = None

    def to_json(self) -> dict:
        d = {}
        for f in fields(self):
            v = getattr(self, f.name)
            if isinstance(v, Path):
                v = str(v)
            elif isinstance(v, LabelStatsParams):
                v = asdict(v)
            elif isinstance(v, tuple):
                v = list(v)
            d[f.name] = v
        return d

    @classmethod
    def from_json(cls, d: Mapping) -> "PipelineConfig":
        kw = dict(d)
        for k in ("out_dir", "nights_root", "install_model", "summary_csv"):
            if kw.get(k) is not None:
                kw[k] = Path(kw[k])
        kw["stats"] = LabelStatsParams(**kw.get("stats", {}))
        for f in fields(cls):
            if isinstance(kw.get(f.name), list):
                kw[f.name] = tuple(kw[f.name])
        return cls(**{k: v for k, v in kw.items() if k in {f.name for f in fields(cls)}})

    def classifier_spec(self, kind: str, C: float | None = None) -> ClassifierSpec:
        return ClassifierSpec(kind=kind, C=1.0 if C is None else float(C), class_weight=self.class_weight,
                              seed=self.seed, mlp_max_iter=self.mlp_max_iter, mlp_alpha=self.mlp_alpha)

    def grid_for(self, clf: str) -> list[dict]:
        return grid_points(grid_spec(self.lr_grid if clf == "lr" else self.mlp_grid), self.base_params)


@dataclass
class Context:
    cfg: PipelineConfig
    index: NightIndex
    tables: dict[str, FeatureTable]
    feature_names: list[str]
    feature_set_version: int
    step_idx: tuple[int, ...]
    cli: AlgoCli
    has_steps: dict[str, bool]
    manifest: dict = field(default_factory=dict)

    @property
    def run_dir(self) -> Path:
        return self.cfg.out_dir

    def dataset_of(self, subject: str) -> str:
        return self.index.dataset_of(subject)


_CTX: Context | None = None


def _ctx() -> Context:
    if _CTX is None:
        raise RuntimeError("pipeline context not initialised")
    return _CTX


# ------------------------------------------------------------------------------------------ parallel helpers ----

def _worker_init() -> None:
    from threadpoolctl import threadpool_limits

    threadpool_limits(1)


def _run_all(executor, fn, tasks: Sequence) -> list:
    futs: list[Future] = [executor.submit(fn, t) for t in tasks]
    done, _ = wait(futs, return_when=FIRST_EXCEPTION)
    for f in futs:
        if f.done() and f.exception() is not None:
            for g in futs:
                g.cancel()
            raise f.exception()
    return [f.result() for f in futs]


def map_processes(fn, tasks: Sequence, jobs: int) -> list:
    if jobs <= 1 or len(tasks) <= 1 or "fork" not in mp.get_all_start_methods():
        return [fn(t) for t in tasks]
    with ProcessPoolExecutor(max_workers=min(jobs, len(tasks)), mp_context=mp.get_context("fork"),
                             initializer=_worker_init) as ex:
        try:
            return _run_all(ex, fn, tasks)
        except BaseException:
            ex.shutdown(wait=True, cancel_futures=True)
            raise


def map_threads(fn, tasks: Sequence, jobs: int) -> list:
    if jobs <= 1 or len(tasks) <= 1:
        return [fn(t) for t in tasks]
    with ThreadPoolExecutor(max_workers=min(jobs, len(tasks))) as ex:
        try:
            return _run_all(ex, fn, tasks)
        except BaseException:
            ex.shutdown(wait=True, cancel_futures=True)
            raise


# ----------------------------------------------------------------------------------------------- context ----

def prepare_context(cfg: PipelineConfig, cli: AlgoCli | None = None) -> Context:
    cfg.out_dir.mkdir(parents=True, exist_ok=True)
    if cli is None:
        cli = AlgoCli.locate(cfg.algo_cli, commands_log=cfg.out_dir / "commands.log", timeout_s=cfg.timeout_s,
                             grid_mode=cfg.grid_mode)
    available = [v for v in dict.fromkeys([*ALL_VARIANTS, *cfg.variants]) if (cfg.nights_root / v).is_dir()]
    need: list[str] = []
    for v in cfg.variants:
        for tv in train_variants_for(v, cfg.train_variants, available):
            if tv not in need:
                need.append(tv)
    for v in cfg.final_variants:
        if v not in need:
            need.append(v)
    index = load_index(cfg.nights_root, need, cfg.summary_csv)
    base_file = None
    if cfg.base_params:
        base_file = write_json(cfg.out_dir / "base-params.json", cfg.base_params)

    def feat(v: str) -> FeatureTable:
        out = cfg.out_dir / "features" / f"{v}.csv"
        if not (cfg.reuse_features and out.is_file()):
            cli.features(cfg.nights_root / v, out, params=base_file)
        return read_features(out, cfg.feature_set_version, variant=v)

    t0 = time.monotonic()
    tables = dict(zip(need, map_threads(feat, need, cfg.jobs)))
    log.info("features for %s in %.1f s", need, time.monotonic() - t0)
    names = None
    fsv = None
    for v, t in tables.items():
        if names is None:
            names, fsv = t.feature_names, t.feature_set_version
        elif t.feature_names != names or t.feature_set_version != fsv:
            raise FeatureFileError(f"variant {v}: feature columns / featureSetVersion differ from {need[0]}")
        want = {n.id: n.subject for n in index.for_variant(v)}
        got = dict(zip(t.night_id.tolist(), t.subject.tolist()))
        missing = sorted(set(want) - set(got))
        if missing:
            raise FeatureFileError(f"features {v}: {len(missing)} nights missing, e.g. {missing[:3]}")
        bad = [n for n in want if got[n] != want[n]]
        if bad:
            raise FeatureFileError(f"features {v}: subject column differs from the night files for {bad[:3]}")
    if fsv is None:
        info = cli.feature_names()
        if list(info["featureNames"]) != list(names):
            raise FeatureFileError(f"algo-cli feature-names {info['featureNames']} differ from the features CSV "
                                   f"header {names}")
        fsv = int(info["featureSetVersion"])
        log.info("featureSetVersion %d and featureNames from `algo-cli feature-names` (algoVersion %s)", fsv,
                 info.get("algoVersion"))
    steps = step_feature_names(names, cfg.step_feature_regex)
    step_idx = tuple(names.index(s) for s in steps)
    has_steps = {n.id: n.has_steps for n in index.nights}
    manifest = {}
    mp_path = cfg.nights_root / "manifest.json"
    if mp_path.is_file():
        manifest = read_json(mp_path)
    log.info("%d hybrid features (step features: %s), featureSetVersion %d, epoch %.3g min", len(names), steps, fsv,
             next(iter(tables.values())).epoch_min)
    return Context(cfg=cfg, index=index, tables=tables, feature_names=list(names), feature_set_version=int(fsv),
                   step_idx=step_idx, cli=cli, has_steps=has_steps, manifest=manifest)


# ---------------------------------------------------------------------------------------------- training ----

@dataclass
class TrainingData:
    X: np.ndarray
    y: np.ndarray
    subject: np.ndarray
    variant: np.ndarray
    hr: np.ndarray                      # hrNorm, hrVar, hrSlope of the same rows
    masked: tuple[int, ...]


class TrainInferenceMismatch(ValueError):
    """Training rows would carry feature values that algo-cli / the app never compute for the same epochs."""


def check_train_inference_parity(train_X: np.ndarray, inference_X: np.ndarray, masked: Iterable[int] = ()) -> None:
    """Every feature value the classifier is trained on must be exactly what algo-cli (= the app) computes for that
    epoch, which is what the bundle sees at inference; only masked columns (zero training column, zero first-layer
    weights, so never used) are exempt. The retired ``nonstep`` policy violated this: it set the step features of
    BIDSleep nights to missing (0 after standardization), while algo-cli computes 0 steps for them."""
    a = np.asarray(train_X, dtype=float)
    b = np.asarray(inference_X, dtype=float)
    if a.shape != b.shape:
        raise TrainInferenceMismatch(f"training matrix {a.shape} differs in shape from the features {b.shape}")
    keep = np.ones(a.shape[1], dtype=bool)
    keep[list(masked)] = False
    same = (a == b) | (np.isnan(a) & np.isnan(b))
    bad = ~same[:, keep]
    if bad.any():
        cols = np.flatnonzero(keep)[np.flatnonzero(bad.any(axis=0))]
        raise TrainInferenceMismatch(f"{int(bad.sum())} training values differ from the algo-cli features in "
                                     f"columns {cols.tolist()[:5]} (not masked)")


def training_data(ctx: Context, subjects: Iterable[str], variants: Sequence[str], policy: str) -> TrainingData:
    """Training rows (HR data + label) of ``subjects`` in ``variants``. Step policies: ``none`` / ``zero`` = the
    algo-cli features as they are (for a night without step records they are 0 steps, exactly what the app
    computes); ``mask`` = step features masked for every night (never used by the model)."""
    subjects = sorted(set(subjects))
    t = concat([ctx.tables[v].for_subjects(subjects) for v in variants])
    rows = t.training_rows()
    X = t.X[rows].copy()
    masked: tuple[int, ...] = ()
    if policy == "mask":
        masked = ctx.step_idx
    elif policy not in STEP_POLICIES:
        raise ValueError(f"unknown step policy {policy!r} (allowed: {', '.join(STEP_POLICIES)})")
    check_train_inference_parity(X, t.X[rows], masked)
    return TrainingData(X=X, y=t.y[rows].astype(np.int64), subject=t.subject[rows], variant=t.variant[rows],
                        hr=t.base[rows][:, :3], masked=masked)


def label_stats_for(ctx: Context, subjects: Iterable[str], variant: str) -> LabelStats:
    t = ctx.tables[variant].for_subjects(subjects)
    return fit_label_stats(t.label_sequences(), t.epoch_min, ctx.cfg.stats)


def label_sequences_for(ctx: Context, subjects: Iterable[str], variant: str) -> dict[str, np.ndarray]:
    return ctx.tables[variant].for_subjects(subjects).label_sequences()


def hmm_mle(ctx: Context, hr: np.ndarray, y: np.ndarray, stats: LabelStats,
            sequences: Mapping[str, np.ndarray] | None = None) -> tuple[dict, list[str]]:
    """Supervised HMM estimates: emissions, mean bouts, transition shares and, when the label ``sequences`` are
    given, the time-dependent priors / initial distribution (:func:`stats.hmm_time_prior`)."""
    em, notes = hmm_emissions(hr, y, ctx.cfg.stats.sd_floor, ctx.cfg.stats.min_emission_n)
    out = {"emissions": em, "meanBouts": dict(stats.mean_bouts), "transitions": dict(stats.transition_weights)}
    if sequences is not None:
        eps = float(ctx.cfg.base_params.get("hmm", {}).get("epsilon", 1e-4))
        tp, tp_notes = hmm_time_prior(sequences, stats.epoch_min, out["meanBouts"], out["transitions"], epsilon=eps)
        out.update(tp)
        notes = notes + tp_notes
    return out, notes


def hmm_patch(hmm: Mapping) -> dict:
    """AlgoParams patch with the HMM estimates (time-prior fields only when they were estimated)."""
    keys = ("emissions", "meanBouts", "transitions", *TIME_PRIOR_KEYS)
    return {"hmm": {k: hmm[k] for k in keys if k in hmm}}


def dataset_description(ctx: Context, datasets: Iterable[str]) -> tuple[str, str]:
    info = ctx.manifest.get("datasets", {})
    names, lic = [], []
    for d in sorted(set(datasets), key=lambda x: (x != "walch", x)):
        i = info.get(d, {})
        names.append(f"{i.get('name', d)} {i.get('version', '')}".strip())
        if i.get("license") and i["license"] not in lic:
            lic.append(i["license"])
    return " + ".join(names), " / ".join(lic) if lic else "see tools/train/DATASETS.md"


def fit_bundle(ctx: Context, *, subjects: Sequence[str], label_variant: str, train_variants: Sequence[str],
               policy: str, spec: ClassifierSpec, model_id: str, notes: Sequence[str] = (),
               data: TrainingData | None = None, classifier: TrainedClassifier | None = None) -> tuple[dict, dict]:
    """Fits everything on ``subjects`` and returns (bundle, info). ``data`` / ``classifier`` may be passed when
    already computed for exactly these subjects."""
    if data is None:
        data = training_data(ctx, subjects, train_variants, policy)
    if classifier is None:
        classifier = fit_classifier(data.X, data.y, spec, data.masked)
    stats = label_stats_for(ctx, subjects, label_variant)
    hmm, hmm_notes = hmm_mle(ctx, data.hr, data.y, stats)
    masked_names = [ctx.feature_names[i] for i in data.masked]
    nn = [*notes, spec.describe(), f"policy for missing step channels: {policy}",
          f"training variants: {', '.join(train_variants)}; label statistics from {label_variant}",
          f"rows: {len(data.y)}"]
    if masked_names:
        nn.append(f"masked features (std {1e12:g}, zero weights): {', '.join(masked_names)}")
    nn += stats.notes + hmm_notes
    ds, lic = dataset_description(ctx, {ctx.dataset_of(s) for s in subjects})
    n_nights = len(ctx.index.nights_of(label_variant, subjects))
    b = build_bundle(model_id=model_id, feature_set_version=ctx.feature_set_version,
                     feature_names=ctx.feature_names, classifier=classifier, label_stats=stats, hmm=hmm,
                     training={"dataset": ds, "license": lic, "subjects": len(set(subjects)), "nights": n_nights,
                               "variants": list(train_variants), "createdAt": ctx.cfg.created_at,
                               "notes": "; ".join(nn)})
    info = {"C": spec.C if spec.kind == "lr" else None, "rows": int(len(data.y)), "nIter": classifier.info["n_iter"],
            "exportMaxAbsDiff": classifier.info.get("export_max_abs_diff"), "masked": masked_names,
            "classPriors": classifier.class_priors.tolist()}
    return b, info


# ------------------------------------------------------------------------------------------ result records ----

def tag_records(ctx: Context, metrics: Mapping, variant: str, expected_ids: Iterable[str], *, estimator: str,
                requested: str, config: str, fold_of: Callable[[str], str], evaluation_of: Callable[[str], str]
                ) -> list[dict]:
    recs = report.parse_metrics(metrics)
    report.check_estimator_used(recs, requested)
    exp = set(expected_ids)
    got = {r["id"] for r in recs}
    if got != exp or len(recs) != len(exp):
        raise report.MetricsFormatError(f"algo-cli returned nights {sorted(got - exp)[:3]} / lacks "
                                        f"{sorted(exp - got)[:3]} (or returned a night twice)")
    no_model = [r["id"] for r in recs if r["reason"] == "NO_FOLD_MODEL"]
    if no_model:
        raise report.MetricsFormatError(f"algo-cli found no fold model for {len(no_model)} nights, e.g. "
                                        f"{no_model[:3]} (<fold-models>/<subject>.json missing)")
    out = []
    for r in recs:
        ref = ctx.index.get(variant, r["id"])
        out.append({"estimator": estimator, "config": config, "variant": variant, "night": r["id"],
                    "subject": ref.subject, "dataset": ref.dataset, "fold": fold_of(ref.subject),
                    "evaluation": evaluation_of(ref.subject), "estimated": r["estimated"], "reason": r["reason"],
                    "estimatorUsed": r["estimatorUsed"], "warnings": r["warnings"],
                    "confusion": None if r["confusion"] is None else np.asarray(r["confusion"]).tolist(),
                    "minutesError": r["minutesError"]})
    return out


def _per_epoch(ctx: Context, metrics_path: Path) -> Path | None:
    return metrics_path.with_suffix(".per-epoch.csv") if ctx.cfg.per_epoch else None


# ------------------------------------------------------------------------------------------------- phases ----

def physiology_hmm_patch() -> dict:
    """AlgoParams patch with the specification's HMM table, mean bouts, transition shares and time-dependent priors
    (HmmParams.PHYSIOLOGY).

    The HMM-table baseline passes it explicitly: the AlgoParams defaults may themselves be trained estimates
    (core-algo TrainedDefaults.kt), and "HMM-table" must always mean the physiology table."""
    return {"hmm": {"emissions": PHYSIOLOGY_EMISSIONS, "meanBouts": DEFAULT_MEAN_BOUTS,
                    "transitions": DEFAULT_TRANSITIONS, **SPEC_TIME_PRIOR}}


def run_baselines(ctx: Context) -> list[dict]:
    cfg = ctx.cfg
    tasks = [(v, est) for v in cfg.variants for est in ("RULES", "HMM-table") if est in cfg.estimators]
    base_file = cfg.out_dir / "base-params.json" if cfg.base_params else None
    table_file = write_json(cfg.out_dir / "baselines" / "hmm-table.params.json",
                            deep_merge(cfg.base_params, physiology_hmm_patch()))

    def one(task):
        v, est = task
        out = cfg.out_dir / "baselines" / v / f"{est}.metrics.json"
        m = ctx.cli.evaluate(cfg.nights_root / v, "RULES" if est == "RULES" else "HMM", out,
                             params=base_file if est == "RULES" else table_file, per_epoch=_per_epoch(ctx, out))
        return tag_records(ctx, m, v, [n.id for n in ctx.index.for_variant(v)], estimator=est,
                           requested="RULES" if est == "RULES" else "HMM", config="untrained",
                           fold_of=lambda s: "-", evaluation_of=lambda s: "untrained")

    return [r for rs in map_threads(one, tasks, cfg.jobs) for r in rs]


def _pool_folds(ctx: Context, datasets: Sequence[str], variant: str) -> list[Fold]:
    cfg = ctx.cfg
    pool = ctx.index.subjects(datasets, variant)
    if len(pool) < 3:
        raise ValueError(f"pool {pool_name(datasets)} / {variant}: need at least 3 subjects, have {len(pool)}")
    hd = cfg.heldout_datasets
    heldout = [s for s in pool if hd is None or ctx.dataset_of(s) in hd]
    external = [s for s in ctx.index.subjects(None, variant) if s not in set(pool)] if cfg.external else []
    return make_folds(pool, heldout, external)


def hmm_pools(cfg: PipelineConfig) -> list[tuple[str, ...]]:
    pools: list[tuple[str, ...]] = []
    for c in cfg.configs:
        if CONFIGS[c].datasets not in pools:
            pools.append(CONFIGS[c].datasets)
    return pools


def fit_hmm_trained(ctx: Context, subjects: Sequence[str], label_variant: str,
                    train_variants: Sequence[str]) -> tuple[dict, list[str]]:
    """Supervised HMM estimates on ``subjects`` (HR rows of ``train_variants``, label sequences of
    ``label_variant``): emissions, mean bouts, transition shares and the time-dependent priors."""
    data = training_data(ctx, subjects, train_variants, "none")
    stats = label_stats_for(ctx, subjects, label_variant)
    hmm, notes = hmm_mle(ctx, data.hr, data.y, stats, label_sequences_for(ctx, subjects, label_variant))
    return hmm, notes + stats.notes


def _hmm_fold_task(task) -> tuple:
    ds, v, f, fdir = task
    ctx = _ctx()
    hmm, notes = fit_hmm_trained(ctx, f.train, v, train_variants_for(v, ctx.cfg.train_variants, ctx.tables))
    pf = write_json(fdir / f"{f.name}.hmm-params.json", deep_merge(ctx.cfg.base_params, hmm_patch(hmm)))
    write_json(fdir.parent / "folds-meta" / f"{f.name}.meta.json",
               {"fold": f.name, "kind": f.kind, "test": list(f.test), "train": list(f.train), "notes": notes,
                "hrNormOrdered": hr_norm_ordered(hmm["emissions"])})
    return pool_name(ds), v, f, pf


def final_hmm_estimates(ctx: Context) -> dict[str, dict]:
    """Supervised HMM estimates on ALL subjects of every HMM pool (rows of the final variants), i.e. what would
    become the AlgoParams defaults, with the validity facts the selection rule checks (hrNorm order)."""
    cfg = ctx.cfg
    fv = tuple(cfg.final_variants)
    out = {}
    for ds in hmm_pools(cfg):
        subjects: set[str] = set()
        for v in fv:
            subjects |= set(ctx.index.subjects(ds, v))
        hmm, notes = fit_hmm_trained(ctx, sorted(subjects), fv[0], fv)
        out[pool_name(ds)] = {"hmm": hmm, "notes": notes, "subjects": len(subjects),
                              "hrNormOrdered": hr_norm_ordered(hmm["emissions"])}
        write_json(cfg.out_dir / "hmm-trained" / pool_name(ds) / "final.hmm-params.json",
                   deep_merge(cfg.base_params, hmm_patch(hmm)))
    return out


def run_hmm_trained(ctx: Context) -> list[dict]:
    cfg = ctx.cfg
    fit_tasks = []
    for ds in hmm_pools(cfg):
        for v in cfg.variants:
            fdir = cfg.out_dir / "hmm-trained" / pool_name(ds) / v / "folds"
            fit_tasks += [(ds, v, f, fdir) for f in _pool_folds(ctx, ds, v)]
    global _CTX
    _CTX = ctx
    t0 = time.monotonic()
    tasks = map_processes(_hmm_fold_task, fit_tasks, cfg.jobs)
    log.info("HMM-trained: %d fold estimates in %.1f s", len(tasks), time.monotonic() - t0)

    def one(task):
        pool, v, f, pf = task
        out = pf.parent.parent / "metrics" / f"{f.name}.json"
        nights = ctx.index.write_subset(v, f.test, out.with_suffix(".nights.jsonl"))
        m = ctx.cli.evaluate(nights, "HMM", out, params=pf, per_epoch=_per_epoch(ctx, out))
        nights.unlink()
        return tag_records(ctx, m, v, [n.id for n in ctx.index.nights_of(v, f.test)], estimator="HMM-trained",
                           requested="HMM", config=pool, fold_of=lambda s: f.name, evaluation_of=lambda s: f.kind)

    return [r for rs in map_threads(one, tasks, cfg.jobs) for r in rs]


@dataclass(frozen=True)
class SelectionTask:
    config: str
    variant: str
    classifier: str
    subjects: tuple[str, ...]

    @property
    def key(self) -> str:
        return subjects_key(self.subjects)


def run_selection(task: SelectionTask) -> dict:
    """Nested inner-CV selection on ``task.subjects`` (C for LR, then the decoder grid)."""
    ctx = _ctx()
    cfg = ctx.cfg
    tc = CONFIGS[task.config]
    v = task.variant
    tv = train_variants_for(v, cfg.train_variants, ctx.tables)
    subs = sorted(task.subjects)
    eval_subj = [s for s in subs if ctx.dataset_of(s) in set(cfg.select_datasets)] or subs
    folds = inner_folds(subs, cfg.inner_k, cfg.seed, ctx.dataset_of)
    work = cfg.out_dir / "work" / task.config / v / task.classifier / task.key
    data = training_data(ctx, subs, tv, tc.step_policy)
    Cs = tuple(cfg.lr_Cs) if task.classifier == "lr" else (None,)
    fitted: dict[tuple[int, Any], TrainedClassifier] = {}
    loss_sum = {C: 0.0 for C in Cs}
    loss_n = 0
    used = []
    for k, (tr, te) in enumerate(folds):
        te_eval = [s for s in te if s in set(eval_subj)]
        if not te_eval:
            continue
        used.append(k)
        trm = np.isin(data.subject, np.array(tr, dtype=object))
        tem = np.isin(data.subject, np.array(te_eval, dtype=object)) & (data.variant == v)
        for C in Cs:
            tcf = fit_classifier(data.X[trm], data.y[trm], cfg.classifier_spec(task.classifier, C), data.masked)
            fitted[(k, C)] = tcf
            if len(Cs) > 1 and tem.any():
                loss_sum[C] += log_loss(tcf.predict_proba(data.X[tem]), data.y[tem]) * int(tem.sum())
        loss_n += int(tem.sum())
    if len(Cs) > 1:
        best_C = min(Cs, key=lambda C: (loss_sum[C], Cs.index(C)))
    else:
        best_C = Cs[0]
    models = work / "models"
    for k in used:
        tr, te = folds[k]
        trm = np.isin(data.subject, np.array(tr, dtype=object))
        sub = TrainingData(X=data.X[trm], y=data.y[trm], subject=data.subject[trm], variant=data.variant[trm],
                           hr=data.hr[trm], masked=data.masked)
        b, _ = fit_bundle(ctx, subjects=tr, label_variant=v, train_variants=tv, policy=tc.step_policy,
                          spec=cfg.classifier_spec(task.classifier, best_C),
                          model_id=f"inner-{task.config}-{v}-{task.classifier}-{task.key}-k{k}",
                          notes=[f"inner fold {k} of selection set {task.key}"], data=sub, classifier=fitted[(k, best_C)])
        for s in folds[k][1]:
            if s in set(eval_subj):
                write_bundle(models / f"{s}.json", b)
    nights = ctx.index.write_subset(v, eval_subj, work / "nights.jsonl")
    points = cfg.grid_for(task.classifier)
    res = ctx.cli.evaluate_grid(nights, "HYBRID", work / "grid-metrics.json", points, fold_models=models)
    expected = [n.id for n in ctx.index.nights_of(v, eval_subj)]
    grid = []
    for p, g in zip(points, res["grid"]):
        recs = tag_records(ctx, g["metrics"], v, expected, estimator="inner", requested="HYBRID",
                           config=task.config, fold_of=lambda s: "inner", evaluation_of=lambda s: "inner")
        score = report.mean_subject_kappa(recs)
        grid.append({"id": p["id"], "index": p["index"], "hybrid": p["hybrid"],
                     "score": None if math.isnan(score) else score})
    best = max(grid, key=lambda g: (g["score"] if g["score"] is not None else -math.inf, -g["index"]))
    others = [g["score"] for g in grid if g is not best and g["score"] is not None]
    margin = (best["score"] - max(others)) if others and best["score"] is not None else None
    if not cfg.keep_inner:
        shutil.rmtree(models, ignore_errors=True)
        nights.unlink(missing_ok=True)
        (work / "grid-metrics.json").unlink(missing_ok=True)      # scores are kept in selection.json
    out = {"key": task.key, "config": task.config, "variant": v, "classifier": task.classifier,
           "subjects": subs, "evalSubjects": eval_subj, "innerFolds": [list(te) for _, te in folds],
           "innerFoldsUsed": used, "evalNights": expected, "C": best_C,
           "logLoss": {str(C): (loss_sum[C] / loss_n if loss_n else None) for C in Cs} if len(Cs) > 1 else {},
           "grid": grid, "chosen": best, "margin": margin}
    write_json(work / "selection.json", out)
    return out


@dataclass(frozen=True)
class OuterTask:
    config: str
    variant: str
    classifier: str
    fold: Fold
    C: float | None
    hybrid: Mapping
    point_id: str
    point_index: int
    score: float | None
    selection_key: str
    margin: float | None = None


def fold_dir(cfg: PipelineConfig, config: str, variant: str, clf: str) -> Path:
    return cfg.out_dir / "hybrid" / config / variant / clf / "folds"


def run_outer(task: OuterTask) -> dict:
    ctx = _ctx()
    cfg = ctx.cfg
    tc = CONFIGS[task.config]
    v, f = task.variant, task.fold
    tv = train_variants_for(v, cfg.train_variants, ctx.tables)
    b, info = fit_bundle(ctx, subjects=f.train, label_variant=v, train_variants=tv, policy=tc.step_policy,
                         spec=cfg.classifier_spec(task.classifier, task.C),
                         model_id=f"loso-{task.config}-{v}-{task.classifier}-{f.name}",
                         notes=[f"{'LOSO fold, held out' if f.kind == 'loso' else 'external fold, evaluated on'}: "
                                f"{', '.join(f.test) if len(f.test) <= 5 else f'{len(f.test)} subjects'}",
                                f"decoder params chosen by inner CV: {canonical_json(task.hybrid)}"])
    d = fold_dir(cfg, task.config, v, task.classifier)
    patch = deep_merge(cfg.base_params, hmm_patch(b["hmm"]))
    for s in f.test:
        write_bundle(d / f"{s}.json", b)
        write_json(d / f"{s}.hmm-params.json", patch)
    meta = {"fold": f.name, "kind": f.kind, "test": list(f.test), "train": list(f.train), "config": task.config,
            "variant": v, "classifier": task.classifier, "selectionKey": task.selection_key,
            "pointId": task.point_id, "gridIndex": task.point_index, "params": dict(task.hybrid),
            "score": task.score, "innerMargin": task.margin, **info}
    write_json(d.parent / "folds-meta" / f"{f.name}.meta.json", meta)
    return meta


def run_hybrid(ctx: Context) -> tuple[list[dict], list[dict]]:
    """Returns (records, fold selections)."""
    cfg = ctx.cfg
    combos = [(c, v, k) for c in cfg.configs for v in cfg.variants for k in cfg.classifiers]
    sel_tasks: dict[tuple, SelectionTask] = {}
    fold_sel: dict[tuple, tuple] = {}
    folds_by_combo: dict[tuple, list[Fold]] = {}
    for c, v, k in combos:
        tc = CONFIGS[c]
        folds = _pool_folds(ctx, tc.datasets, v)
        folds_by_combo[(c, v, k)] = folds
        pool = ctx.index.subjects(tc.datasets, v)
        ss = selection_sets(folds, pool, cfg.selection, cfg.inner_k, cfg.seed, ctx.dataset_of)
        for f in folds:
            t = SelectionTask(c, v, k, ss[f.name])
            if set(t.subjects) & set(f.test):
                raise RuntimeError(f"selection set of fold {f.name} contains its held-out subject")
            sel_tasks.setdefault((c, v, k, t.key), t)
            fold_sel[(c, v, k, f.name)] = (c, v, k, t.key)
    global _CTX
    _CTX = ctx
    t0 = time.monotonic()
    tasks = list(sel_tasks.values())
    log.info("nested selection: %d inner-CV runs", len(tasks))
    sel_res = dict(zip(sel_tasks.keys(), map_processes(run_selection, tasks, cfg.jobs)))
    log.info("nested selection done in %.1f s", time.monotonic() - t0)
    outer = []
    for (c, v, k), folds in folds_by_combo.items():
        for f in folds:
            s = sel_res[fold_sel[(c, v, k, f.name)]]
            ch = s["chosen"]
            outer.append(OuterTask(c, v, k, f, s["C"], ch["hybrid"], ch["id"], ch["index"], ch["score"], s["key"],
                                   s.get("margin")))
    t0 = time.monotonic()
    metas = map_processes(run_outer, outer, cfg.jobs)
    log.info("outer fold models: %d in %.1f s", len(metas), time.monotonic() - t0)
    for s in sel_res.values():
        write_json(cfg.out_dir / "hybrid" / s["config"] / s["variant"] / s["classifier"] / "selection" /
                   f"{s['key']}.json", s)
    # outer evaluation, grouped by chosen params
    groups: dict[tuple, list[dict]] = {}
    for m in metas:
        groups.setdefault((m["config"], m["variant"], m["classifier"], canonical_json(m["params"])), []).append(m)
    eval_tasks = []
    for (c, v, k, pk), ms in sorted(groups.items()):
        eval_tasks.append((c, v, k, json.loads(pk), sorted(s for m in ms for s in m["test"]),
                           {s: m["fold"] for m in ms for s in m["test"]},
                           {s: m["kind"] for m in ms for s in m["test"]}))

    def one(task):
        c, v, k, hybrid, subjects, fold_map, kind_map = task
        d = cfg.out_dir / "hybrid" / c / v / k / "metrics"
        gid = subjects_key(subjects)
        out = d / f"{gid}.json"
        pf = write_json(d / f"{gid}.params.json",
                        deep_merge(cfg.base_params, {"estimator": "HYBRID", "hybrid": hybrid}))
        nights = ctx.index.write_subset(v, subjects, d / f"{gid}.nights.jsonl")
        m = ctx.cli.evaluate(nights, "HYBRID", out, params=pf, fold_models=fold_dir(cfg, c, v, k),
                             per_epoch=_per_epoch(ctx, out))
        nights.unlink()
        return tag_records(ctx, m, v, [n.id for n in ctx.index.nights_of(v, subjects)],
                           estimator=report.CLASSIFIER_LABEL[k], requested="HYBRID", config=c,
                           fold_of=fold_map.__getitem__, evaluation_of=kind_map.__getitem__)

    t0 = time.monotonic()
    recs = [r for rs in map_threads(one, eval_tasks, cfg.jobs) for r in rs]
    log.info("outer evaluation: %d algo-cli runs in %.1f s", len(eval_tasks), time.monotonic() - t0)
    fold_selections = [{"config": m["config"], "variant": m["variant"], "classifier": m["classifier"],
                        "fold": m["fold"], "kind": m["kind"], "pointId": m["pointId"], "gridIndex": m["gridIndex"],
                        "params": m["params"], "score": m["score"], "C": m["C"], "selectionKey": m["selectionKey"],
                        "innerMargin": m["innerMargin"]} for m in metas]
    return recs, fold_selections


def write_jsonl(path: Path, rows: Iterable[Mapping]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, separators=(",", ":"), allow_nan=False) + "\n")
    return path


def read_jsonl(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]
