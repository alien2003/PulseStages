"""Pre-registered realistic nap estimates (``docs/prereg/prereg-naps-realistic.md``, :data:`PREREG_SHA256`): an
evidence-following nap model with a literature-anchored stage mix, tested on real afternoon naps.

Models of naps (sessions shorter than ``napMode.maxSessionMin`` = 180 min), per held-out subject (LOSO over the 78
subjects of ``walch+bid-zero``; outer groups, selection sets and inner folds of run ``bias-base``; the night side of
every fold bundle is the N0 fold bundle of 13.12):

- ``N1`` (reference): 13.12's nap model at fold level, unchanged (stored outer nap models of ``nap-model`` with the
  group's nap stage log-bias, decoder theta_nap,g, the night's context);
- ``C0``: N1's classifier and statistics, its scalar level replaced by a per-length table fitted to the literature
  target on LIT-weighted inner out-of-fold decodes of N1's refitted inner models (dropped when gate G4 fails);
- ``C1`` / ``C2``: the evidence model (the session-time and step features masked, trained on the tiles of the training
  subjects with half of the weight per dataset, HSMM statistics copied from the night model, no heart-rate context
  around a nap: ``napMode.contextMarginMin`` = 0), with a constant bias fitted to the tiles' night truth (C1) or a
  per-length table fitted to the literature target (C2); classifier, C and decoder theta_E,g by nested inner CV;
- context: RULES, HMM-table and "all LIGHT"; every model also scored as SLEEP_WAKE.

Evaluation pools (:mod:`pulsetrain.pools`): EVAL (onset O, ends-in-wake W, mid-night M, return to sleep R at the
anchors), MIDANCHOR, the held-out subjects' tiles, LATESTART, perturbed copies (:mod:`pulsetrain.perturb`) and
featureless synthetic sessions, plus the whole nights (which must stay identical to candidate B). Metrics and the
pre-registered rules (i) (two tiers), (ii) and (iii) are those of :mod:`pulsetrain.realism`, on the common set E-cap.

Subcommands (``train.py``): :func:`run_gates` (G0, G1, G2, G3a, G4: reproduction only, no candidate fitted),
:func:`run_experiment` (the experiment, ``summary.json`` with rules (i) and (ii)), :func:`rerender`,
:func:`featureless_timing` (the dry run of section 18), :func:`final_model`, :func:`external` (ds006576 and rule
(iii)), :func:`install` and :func:`verify`. Each logs its START / END / RESULT lines in the commands log (append only;
the pre-registration NOTE must precede them).
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
import os
import shlex
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Callable, Iterable, Mapping, Sequence

import numpy as np
import pandas as pd

from . import ds006576 as ds
from . import litprior, loso, napmodel, naps, perturb, pools, realism, report
from .algocli import AlgoCli
from .bias import fit_stage_bias, fit_stage_bias_table
from .bundle import (length_bias_table, nap_section, read_bundle, validate_bundle, with_nap_level, with_nap_model,
                     with_stage_log_bias, write_bundle)
from .features import FeatureTable, concat, read_features
from .models import MASK_STD, TrainedClassifier, fit_classifier, log_loss
from .nightset import dataset_from_id
from .util import DEEP, N_STAGES, REM, STAGES, canonical_json, code_fingerprint, deep_merge, read_json, write_json

log = logging.getLogger(__name__)

PREREG_PATH = "docs/prereg/prereg-naps-realistic.md"
PREREG_NAME = "prereg-naps-realistic.md"
PREREG_SHA256 = "38c1589e3fee463e7495e4c2a952e1e212ce5eba9fb24bf25d1e7d3212a7c43a"  # the text with Amendment 1
# the text of the pre-registration commit 52517a7, before Amendment 1
ORIGINAL_PREREG_SHA256 = "ff8e4c96ca9c224c6ded01a7324068cf88167b8f3e3127efc276eb0a914f22db"
REPO_ROOT = Path(__file__).resolve().parents[3]
PREREG_NOTE = f"NOTE {PREREG_NAME} sha256 {PREREG_SHA256}"

# the shipped bundle of 13.12 ("the previous bundle") and the fingerprints the base build (commit cad7a6f, before any
# code change) computed for it: commands log, NOTE "naps-realistic base-build fingerprints (phase A ...)" of
# 2026-10-15T04:09:28+02:00 (tools/train/out/naps-realistic-base/ref/fingerprints.tsv)
SHIPPED_BUNDLE_SHA256 = "583df315d12af09bd1a0e72bd78d1e1d835d6e8ae53565a264368538591530f9"
PREVIOUS_BUNDLE = REPO_ROOT / "core-algo" / "src" / "test" / "resources" / "nap-model-final-bundle.json"
APP_BUNDLE = REPO_ROOT / "app" / "src" / "main" / "assets" / "models" / "hybrid_model.json"
BASE_COMMIT = "cad7a6f"
BASE_FINGERPRINT = "6589c5fa749cfe1a7563a990cd6cd0152e3bfe1bb607528c85d11baae8112a69"
BASE_NIGHT_FINGERPRINT = "50c145ef2d0f1e5b5b9d14abbf041da3a88c3f507da04ba41d05a7c46006b9cf"

EXPECTED_FEATURE_SET_VERSION = 1
EXPECTED_FEATURES = 40
MASKED_FEATURES: tuple[str, ...] = ("t", "hoursSinceStart", "hoursToEnd", "stepsLog1p", "stepsWin5Log1p")
ANCHORS: tuple[int, ...] = pools.ANCHORS
MID_ANCHORS: tuple[int, ...] = pools.MID_ANCHORS
NAP_MAX_SESSION_MIN = 180.0
EVIDENCE_MARGIN_MIN = 0                    # napMode.contextMarginMin of C1 / C2 (section 6.2)
REFERENCE = realism.REFERENCE              # N1
CANDIDATES: tuple[str, ...] = realism.CANDIDATES   # C0, C1, C2 (tie order: fewest changes first)
MODELS: tuple[str, ...] = (REFERENCE, *CANDIDATES)
EVIDENCE_MODELS: tuple[str, ...] = ("C1", "C2")
CONTEXT_ESTIMATORS: tuple[str, ...] = ("RULES", "HMM-table")
LIGHT_KIND = "all-LIGHT"
MODEL_LABEL = {"N1": "13.12 nap model (reference)", "C0": "N1 + literature table",
               "C1": "evidence model + night-level bias", "C2": "evidence model + literature table",
               "RULES": "RULES (context)", "HMM-table": "HMM-table (context)", LIGHT_KIND: "all LIGHT (reference)"}
CLASSIFIER_ORDER: tuple[str, ...] = ("lr", "mlp")          # inner-CV ties: logistic regression first
EVIDENCE_GRID = loso.GRIDS["nap-evidence"]
STAT_KEYS: tuple[str, ...] = ("durations", "boutTransitions", "initialStage", "timePrior")
TRAIN_VARIANT_ORDER: tuple[str, ...] = ("instantaneous", "mean2min")
VARIANTS: tuple[str, ...] = report.HEADLINE_VARIANTS
DATASETS: tuple[str, ...] = naps.DATASETS
G1_PER_VARIANT = 1854                      # 13.12's N1 decodes per variant: 1704 onset proxies + 150 ew windows
PERTURBATION_LENGTHS: tuple[int, ...] = (60, 90)
G5_PERTURBATIONS: tuple[str, ...] = ("noctx", "app+15steps")
FEATURELESS_SEED_CHOICES: tuple[int, ...] = (20, 10)          # seeds 0-19, or 0-9 when the dry run projects > 2 h
FEATURELESS_BUDGET_S = 2 * 3600
MAIN_KINDS: tuple[str, ...] = ("eval", "night", "tile", "latestart")   # one decode call with --per-epoch
# section 6.6: the stages are scored, the output policy applies after the estimate (every other default as built)
STAGES_PATCH: dict = {"napMode": {"outputPolicy": "STAGES"}}
PARTS: tuple[str, ...] = ("main", "midanchor", "perturbed", "featureless")
FINAL_MODEL_PREFIX = "pulsestages-walch+bidsleep-mlp"

# the fingerprint probe of section 15 item 5 / gate G2: HybridModel.fingerprint() and nightModelFingerprint() of a
# bundle, computed by the core-algo classes on the class path of the algo-cli build (java single-file source launch)
PROBE_SOURCE = """\
// Generated by tools/train/pulsetrain/napsrealistic.py: prints HybridModel.fingerprint() and
// nightModelFingerprint() of every bundle given, as computed by the core-algo build on the class path.
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FingerprintProbe {
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            HybridModel model = HybridModel.Companion.parse(Files.readString(Path.of(arg), StandardCharsets.UTF_8));
            System.out.println(arg + "\\t" + model.fingerprint() + "\\t" + model.nightModelFingerprint());
        }
    }
}
"""


class NapsRealisticError(RuntimeError):
    """The realistic-nap experiment cannot run (inputs missing, a refused build or order) or a fatal gate failed."""


class GateFailure(NapsRealisticError):
    """A fatal gate failed; ``gates`` holds the gate records reached so far."""

    def __init__(self, message: str, gates: Mapping):
        super().__init__(message)
        self.gates = dict(gates)


# =================================================================================================== logging ====

def command_text(argv: Sequence[str]) -> str:
    return " ".join(["python -I", "tools/train/train.py", *(shlex.quote(str(a)) for a in argv)])


class RunLog:
    """START / END / RESULT / NOTE lines of one ``train.py naps-realistic*`` run in the commands log (append only, the
    format of the log: ``<ISO time stamp> <KIND> <text>``). ``path`` None writes nothing (library use)."""

    def __init__(self, path: Path | None, argv: Sequence[str]):
        self.path = Path(path) if path is not None else None
        self.cmd = command_text(argv)
        self.t0 = time.monotonic()

    def line(self, text: str) -> None:
        if self.path is not None:
            ds.append_log(self.path, " ".join(str(text).split()))

    def start(self) -> None:
        self.line(f"START {self.cmd}")

    def end(self, rc: int) -> None:
        self.line(f"END rc={rc} {time.monotonic() - self.t0:.0f}s {self.cmd}")

    def result(self, name: str, text: str) -> None:
        self.line(f"RESULT {name} {text}")

    def note(self, text: str) -> None:
        self.line(f"NOTE {text}")


def prereg_record(log_path: Path | None) -> dict:
    """G0 (pre-registration): the committed text's SHA-256 equals :data:`PREREG_SHA256`, so does its ``.sha256`` file,
    and (with a log) the pre-registration NOTE line is in the commands log."""
    path = REPO_ROOT / PREREG_PATH
    digest = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
    side = path.with_name(path.name + ".sha256")
    side_ok = side.is_file() and side.read_text(encoding="utf-8").split() == [PREREG_SHA256, PREREG_NAME]
    noted = None
    if log_path is not None:
        lines = Path(log_path).read_text(encoding="utf-8").splitlines() if Path(log_path).is_file() else []
        noted = any(ds.STAMP_RE.match(x) and f" {PREREG_NOTE}" in x for x in lines)
    ok = digest == PREREG_SHA256 and side_ok and noted is not False
    return {"path": PREREG_PATH, "sha256": digest, "expected": PREREG_SHA256, "sha256File": side_ok,
            "noteInLog": noted, "passed": bool(ok)}


def gate_g0(cli: AlgoCli, log_path: Path | None) -> dict:
    """G0 provenance: the algo-cli fingerprint, ``meta.code`` and the pre-registration digest (fatal)."""
    pre = prereg_record(log_path)
    rec = {"algoCli": cli.fingerprint(), "code": code_fingerprint(), "prereg": pre, **provenance(log_path),
           "passed": pre["passed"]}
    if not rec["passed"]:
        raise GateFailure(f"G0: the pre-registration does not match {PREREG_SHA256} (or its NOTE is not in the "
                          f"commands log): {pre}", {"G0": rec})
    return rec


def provenance(log_path: Path | None, lock_path: Path = ds.LOCK_PATH) -> dict:
    """Section 19: the ds006576 manifest and YASA-summary digests logged so far (empty before the extraction) and the
    YASA version, wheel, classifier and OpenMP-runtime digests of the lock."""
    lines = Path(log_path).read_text(encoding="utf-8").splitlines() if log_path and Path(log_path).is_file() else []
    man, summ = ds.logged_digests(lines)
    try:
        lock = ds.read_lock(lock_path)
        yasa = {"version": lock.pins.get("yasa"), "wheelSha256": lock.yasa_wheel_sha256,
                "classifier": lock.classifier_name, "classifierSha256": lock.classifier_sha256,
                "openmpSha256": lock.openmp_lib_sha256}
    except (OSError, ds.ExternalError) as e:
        yasa = {"error": str(e)}
    return {"ds006576ManifestSha256": man, "ds006576SummarySha256": summ, "yasaLock": yasa}


def check_build(cli: AlgoCli, g0: Mapping, where: str) -> None:
    """G0: the run refuses a build other than the one recorded at its start."""
    now = cli.fingerprint().get("sha256")
    if now != g0["algoCli"].get("sha256"):
        raise GateFailure(f"G0: algo-cli changed during the run ({where}): {now} != {g0['algoCli'].get('sha256')}",
                          {"G0": dict(g0, passed=False, changedAt=where)})


# ==================================================================================================== inputs ====

class Inputs:
    """Read-only inputs: run ``bias-base`` (fold bundles, groups, selection sets, kept inner fold night models),
    ``naps`` (b_short_g(180)), ``nap-model`` (N1: outer nap models, selections, stored decodes, proxies and features,
    inner selections), the night files, and the base-build references of gate G2."""

    def __init__(self, run_dir: Path, naps_dir: Path, nap_model_dir: Path, nights_root: Path,
                 posthoc_dir: Path | None = None, g2_base: Path | None = None,
                 previous_bundle: Path = PREVIOUS_BUNDLE, variants: Sequence[str] = VARIANTS):
        self.nm = napmodel.Inputs(run_dir, naps_dir, nights_root, posthoc_dir, variants)
        self.run_dir = Path(run_dir)
        self.nap_model_dir = Path(nap_model_dir)
        self.g2_base = Path(g2_base) if g2_base is not None else None
        self.previous_bundle = Path(previous_bundle)
        self.variants = tuple(variants)
        ds.assert_not_in_index(self.nm.index)
        sel = read_json(self.nap_model_dir / "selections.json")["selections"]
        self.n1 = {(s["variant"], s["key"]): {"theta": _theta(s["chosen"]["hybrid"]),
                                              "bias": [float(x) for x in s["napBias"]["bias"]],
                                              "chosen": dict(s["chosen"])} for s in sel}
        for g in self.groups:
            if (g.variant, g.key) not in self.n1:
                raise NapsRealisticError(f"{g.variant}/{g.key}: no N1 selection in {self.nap_model_dir}")
        self._folds: dict[tuple[str, str], dict] = {}
        self._group = {(g.variant, s): g for g in self.groups for s in g.heldout}

    @property
    def groups(self) -> list[napmodel.Group]:
        return self.nm.groups

    @property
    def index(self):
        return self.nm.index

    @property
    def cfg(self):
        return self.nm.cfg

    @property
    def pool(self) -> list[str]:
        return list(self.nm.pool)

    @property
    def nights_root(self) -> Path:
        return self.nm.base.nights_root

    def walch(self) -> list[str]:
        return sorted(s for s in self.pool if self.index.dataset_of(s) == report.HEADLINE_DATASET)

    def group_of(self, variant: str, subject: str) -> napmodel.Group:
        return self._group[(variant, subject)]

    def night(self, variant: str, night_id: str) -> dict:
        return self.nm.night(variant, night_id)

    def fold(self, variant: str, subject: str) -> dict:
        k = (variant, subject)
        if k not in self._folds:
            self._folds[k] = read_json(self.nm.folds(variant) / f"{subject}.json")
        return self._folds[k]

    def n0(self, variant: str, subject: str) -> dict:
        return napmodel.n0_bundle(self.fold(variant, subject), self.group_of(variant, subject))

    def n1_nap(self, variant: str, subject: str) -> dict:
        """13.12's stored outer nap model of the subject (no level)."""
        return read_json(self.nap_model_dir / "outer" / variant / "nap" / f"{subject}.json")

    def eval_params(self, g: napmodel.Group, theta_nap: Mapping, margin: int | None = None) -> dict:
        """Section 6.6: the night run's base parameters with theta_g, beta 1, ``napMode`` maxSessionMin 180, HYBRID,
        STAGES, ``napMode.hybrid`` = ``theta_nap``, and ``napMode.contextMarginMin`` = ``margin`` when given."""
        p = self.nm.eval_params(g.theta, theta_nap)
        return deep_merge(p, {"napMode": {"contextMarginMin": int(margin)}}) if margin is not None else p

    def n1_params(self, g: napmodel.Group) -> dict:
        return self.eval_params(g, self.n1[(g.variant, g.key)]["theta"])

    def context_params(self, estimator: str) -> dict:
        return self.nm.context_params(estimator)

    def feature_params(self, margin: int | None = EVIDENCE_MARGIN_MIN) -> dict:
        """Base parameters with the nap mode of the candidates (maxSessionMin 180; the context margin when given)."""
        nap: dict = {"maxSessionMin": NAP_MAX_SESSION_MIN}
        if margin is not None:
            nap["contextMarginMin"] = int(margin)
        return deep_merge(dict(self.cfg.base_params), {"napMode": nap})

    def inner_night_model(self, variant: str, key: str, subject: str) -> Path:
        """``bias-base``'s kept inner fold night model of an inner held-out subject."""
        return self.nm.base.run_dir / "work" / self.nm.base.config / variant / self.nm.base.clf / key / "models" / \
            f"{subject}.json"


def _theta(h: Mapping) -> dict:
    return {"durationModel": str(h["durationModel"]), "emissionWeight": float(h["emissionWeight"]),
            "timePriorWeight": float(h["timePriorWeight"])}


# ================================================================================================== sessions ====

def night_meta(night: Mapping) -> dict:
    """The meta of a whole night (decoded with every model; gate G6)."""
    s0, s1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])
    return {"kind": "night", "pool": "NIGHT", "regime": None, "L": (s1 - s0) / pools.MIN, "o": None, "offsetMin": 0.0,
            "startMs": s0, "endMs": s1, "base": str(night["id"]), "subject": str(night["subject"]),
            "dataset": dataset_from_id(str(night["id"])), "variant": night.get("variant")}


@dataclass
class Sessions:
    """Every session of the run per variant and kind (``night``, ``tile``, ``eval``, ``midanchor``, ``latestart``,
    ``perturbed``), each as (session, meta); the featureless sessions are generated per group when decoded."""
    by: dict[str, dict[str, list[tuple[dict, dict]]]]
    counts: dict

    def of(self, variant: str, kinds: Iterable[str], subjects: Iterable[str] | None = None,
           where: Callable[[Mapping], bool] | None = None) -> tuple[list[dict], dict[str, dict]]:
        sub = None if subjects is None else set(subjects)
        ss, meta = [], {}
        for k in kinds:
            for s, m in self.by[variant].get(k, []):
                if (sub is None or m["subject"] in sub) and (where is None or where(m)):
                    ss.append(s)
                    meta[s["id"]] = m
        return ss, meta


def with_counts(session: Mapping, meta: Mapping) -> dict:
    return {**meta, "labelCounts": pools.label_counts(session).tolist()}


def perturbed_of(eval_items: Sequence[tuple[dict, dict]]) -> list[tuple[dict, dict]]:
    """Section 4.5: every perturbation of the O and LATE sessions of 60 and 90 min (both datasets) and, for gate G5,
    ``noctx`` and ``app+15steps`` of every Walch EVAL session; ids ``<original id>~<perturbation>``."""
    out, seen = [], set()
    for s, m in eval_items:
        names: list[str] = []
        if (m["pool"] == "O" or m["regime"] == "LATE") and m["L"] in PERTURBATION_LENGTHS:
            names += list(perturb.NAMES)
        if m["dataset"] == report.HEADLINE_DATASET:
            names += [n for n in G5_PERTURBATIONS if n not in names]
        for n in names:
            c, cm = perturb.perturbed_copy(s, n, m)
            if c["id"] not in seen:
                seen.add(c["id"])
                out.append((c, {**cm, "originalPool": m["pool"]}))
    return out


def build_sessions(inp: Inputs) -> Sessions:
    """Tiles, EVAL, MIDANCHOR, LATESTART and perturbed sessions and the whole nights of both variants (section 4); both
    variants must give identical windows; ids unique per variant (unit-tested; algo-cli refuses duplicates)."""
    by: dict[str, dict[str, list]] = {}
    windows: dict[str, dict[str, list]] = {}
    nights: dict[str, list[dict]] = {}
    for v in inp.variants:
        by[v] = defaultdict(list)
        windows[v] = {}
        nights[v] = []
        for n in inp.index.for_variant(v):
            night = inp.night(v, n.id)
            nights[v].append(night)
            by[v]["night"].append((night, with_counts(night, night_meta(night))))
            items = {"tile": pools.tiles(night), "eval": pools.eval_sessions(night),
                     "midanchor": pools.midanchor_sessions(night), "latestart": pools.late_start(night)}
            for k, xs in items.items():
                by[v][k] += [(s, with_counts(s, m)) for s, m in xs]
            windows[v][napmodel.night_key(n.id)] = sorted(
                (k, m["startMs"], m["endMs"]) for k, xs in items.items() for _, m in xs)
        by[v]["perturbed"] = perturbed_of(by[v]["eval"])
        ids = [s["id"] for k in by[v] for s, _ in by[v][k]]
        pools.assert_unique_ids({"id": i} for i in ids)
        by[v] = dict(by[v])
    vs = list(inp.variants)
    for v in vs[1:]:
        if windows[v] != windows[vs[0]]:
            bad = [k for k in windows[v] if windows[v][k] != windows[vs[0]].get(k)]
            raise NapsRealisticError(f"session windows differ between {vs[0]} and {v}, e.g. {bad[:3]}")
    counts = {v: pools.count_pools(nights[v]) for v in vs}
    counts["diffs"] = {v: pools.compare_counts(counts[v]) for v in vs}
    counts["sessions"] = {v: {k: len(x) for k, x in by[v].items()} for v in vs}
    return Sessions(by=by, counts=counts)


def featureless_items(subjects: Sequence[str], seeds: int, variant: str) -> list[tuple[dict, dict]]:
    """The FEATURELESS sessions of section 4.5 (seeds 0 .. ``seeds`` - 1) copied for ``subjects``."""
    return pools.featureless_set(list(subjects), tuple(range(int(seeds))), variant=variant)


# ================================================================================================== decoding ====

def write_sessions(path: Path, sessions: Sequence[Mapping]) -> Path:
    """A session file (compact JSON lines, the serialization of ``napmodel.check_sessions_file``); ids unique."""
    pools.assert_unique_ids(sessions)
    return napmodel.write_sessions(path, sessions)


def write_bundles(d: Path, bundles: Mapping[str, Mapping]) -> Path:
    return napmodel._write_bundles(d, bundles)


def evaluate(cli: AlgoCli, path: Path, sessions: Sequence[Mapping] | Path, meta: Mapping[str, Mapping],
             estimator: str, params: Mapping, *, fold_models: Path | None = None, model: Path | None = None,
             per_epoch: Path | None = None, extra: Mapping | None = None, keep_metrics: bool = True) -> list[dict]:
    """One ``algo-cli evaluate`` call on ``sessions`` (a list, written next to ``path`` and removed afterwards, or an
    existing session file) tagged with ``meta`` (every session id exactly once): the checks of ``napmodel.tag``."""
    own = not isinstance(sessions, (str, Path))
    nf = write_sessions(Path(path).with_suffix(".sessions.jsonl"), sessions) if own else Path(sessions)
    pf = write_json(Path(path).with_suffix(".params.json"), dict(params))
    try:
        m = cli.evaluate(nf, "HMM" if estimator == "HMM-table" else estimator, Path(path), params=pf, model=model,
                         fold_models=fold_models, per_epoch=per_epoch)
    finally:
        if own:
            nf.unlink(missing_ok=True)
    recs = napmodel.tag(m, meta, estimator, extra)
    if not keep_metrics:
        Path(path).unlink(missing_ok=True)
        pf.unlink(missing_ok=True)
    return recs


def hybrid_scored(recs: Iterable[Mapping]) -> list[Mapping]:
    return [r for r in recs if realism.is_hybrid_scored(r)]


def hybrid_kappa(recs: Iterable[Mapping]) -> float:
    """Mean per-subject Cohen's kappa over the sessions estimated by HYBRID with a scored chunk (inner score)."""
    return report.mean_subject_kappa(hybrid_scored(recs))


def pooled(recs: Iterable[Mapping]) -> np.ndarray:
    return sum((np.asarray(r["confusion"], float) for r in recs), np.zeros((N_STAGES, N_STAGES)))


# =================================================================================================== fitting ====

@dataclass
class FitData:
    """Training rows of one data set (``tiles``: the evidence model; ``n1``: 13.12's proxies for the refit of N1's
    inner models), the masked columns and whether the rows are weighted (section 6.2)."""
    X: np.ndarray
    y: np.ndarray
    subject: np.ndarray
    dataset: np.ndarray
    masked: tuple[int, ...] = ()
    weighted: bool = False


_FIT_DATA: dict[str, FitData] = {}
_SPEC: Callable | None = None

FitKey = tuple[str, tuple[str, ...], str, "float | None"]          # (data, training subjects, lr | mlp, C)


def subject_weights(subject: np.ndarray, dataset: np.ndarray) -> np.ndarray:
    """Section 6.2: u_i = 1/2 * 1 / (|S_d| * n_s(i)) for the rows of training subject s of dataset d (n_s = the rows of
    s, both variants), normalised to mean 1: each dataset half of the weight, each subject the same weight within
    its dataset."""
    subject = np.asarray(subject, dtype=object).tolist()
    dataset = np.asarray(dataset, dtype=object).tolist()
    n_rows = Counter(subject)
    n_subj = Counter(d for _, d in set(zip(subject, dataset)))
    u = np.array([0.5 / (n_subj[d] * n_rows[s]) for s, d in zip(subject, dataset)], float)
    return u / u.mean()


def _fit_task(task: FitKey) -> tuple[FitKey, TrainedClassifier]:
    """Fits ``task`` = (data, training subjects, lr | mlp, C) on its rows (forked worker)."""
    name, subjects, kind, C = task
    d = _FIT_DATA.get(name)
    if d is None or _SPEC is None:
        raise RuntimeError(f"fit data {name!r} not initialised")
    m = np.isin(d.subject, np.array(subjects, dtype=object))
    w = subject_weights(d.subject[m], d.dataset[m]) if d.weighted else None
    tc = fit_classifier(d.X[m], d.y[m], _SPEC(kind, C), masked=d.masked, sample_weight=w)
    return task, replace(tc, model=None)


def fit_all(tasks: Iterable[FitKey], jobs: int) -> dict[FitKey, TrainedClassifier]:
    """Fits every task in forked workers. The parent's objects (the session pools among them) are frozen out of the
    cyclic garbage collector while the workers run, so that a collection in a worker does not touch, and copy, their
    memory pages."""
    import gc

    todo = sorted(set(tasks), key=lambda t: (t[0], t[2], -1.0 if t[3] is None else float(t[3]), t[1]))
    t0 = time.monotonic()
    gc.collect()
    gc.freeze()
    try:
        res = dict(loso.map_processes(_fit_task, todo, jobs))
    finally:
        gc.unfreeze()
    log.info("%d classifier fits in %.1f s", len(todo), time.monotonic() - t0)
    return res


# ================================================================================================ tile data ====

@dataclass
class TileData:
    """algo-cli features of every tile (candidate parameters) and the evidence model's training rows of both variants
    (instantaneous first)."""
    tables: dict[str, FeatureTable]
    names: list[str]
    fsv: int
    masked: tuple[int, ...]
    X: np.ndarray
    y: np.ndarray
    subject: np.ndarray
    dataset: np.ndarray
    variant: np.ndarray
    session: np.ndarray


def masked_indices(names: Sequence[str]) -> tuple[int, ...]:
    """The masked columns of section 6.2 by name; every name must be a feature of the build."""
    missing = [n for n in MASKED_FEATURES if n not in names]
    if missing:
        raise NapsRealisticError(f"masked features {missing} are not features of this build")
    return tuple(sorted(list(names).index(n) for n in MASKED_FEATURES))


def tile_features(inp: Inputs, cli: AlgoCli, sessions: Sessions, out: Path, jobs: int) -> dict:
    """``algo-cli features`` of the tile session files with the candidate parameters, per variant."""
    pf = write_json(out / "features" / "candidate-params.json", inp.feature_params())

    def one(v: str) -> dict:
        ss = [s for s, _ in sessions.by[v]["tile"]]
        nf = write_sessions(out / "sessions" / v / "tile.jsonl", ss)
        cli.features(nf, out / "features" / f"{v}.tiles.csv", params=pf)
        return {"variant": v, "tiles": len(ss)}

    return {"tiles": loso.map_threads(one, list(inp.variants), max(1, min(jobs, len(inp.variants))))}


def load_tile_data(inp: Inputs, cli: AlgoCli, sessions: Sessions, out: Path) -> TileData:
    """The feature tables of the tiles, checked against ``algo-cli feature-names`` and the decoded tiles (the session
    file byte for byte, ``napmodel.check_sessions_file``), and the training rows (heart-rate data and a label)."""
    info = cli.feature_names()
    fsv = int(info["featureSetVersion"])
    tables = {v: read_features(out / "features" / f"{v}.tiles.csv", fsv, variant=v) for v in inp.variants}
    names = list(tables[inp.variants[0]].feature_names)
    if list(info["featureNames"]) != names or any(t.feature_names != names for t in tables.values()):
        raise NapsRealisticError("tile feature columns differ from algo-cli feature-names")
    for v, t in tables.items():
        ss = [s for s, _ in sessions.by[v]["tile"]]
        napmodel.check_sessions_file(out / "sessions" / v / "tile.jsonl", ss)
        want = {s["id"]: s["subject"] for s in ss}
        got = dict(zip(t.night_id.tolist(), t.subject.tolist()))
        if set(got) != set(want) or any(got[k] != want[k] for k in want):
            raise NapsRealisticError(f"features of the {v} tiles: sessions or subjects differ from the tiles")
    order = [v for v in TRAIN_VARIANT_ORDER if v in tables]
    t = concat([tables[v] for v in order])
    rows = t.training_rows()
    subject = t.subject[rows]
    return TileData(tables=tables, names=names, fsv=fsv, masked=masked_indices(names), X=t.X[rows].copy(),
                    y=t.y[rows].astype(np.int64), subject=subject,
                    dataset=np.array([inp.index.dataset_of(s) for s in subject.tolist()], dtype=object),
                    variant=t.variant[rows], session=t.night_id[rows])


# ============================================================================================ model bundles ====

def statistics_of(b: Mapping) -> dict:
    """The HSMM statistics a model copies from a night model (section 6.2)."""
    return {k: b[k] for k in STAT_KEYS}


def evidence_bundle(tc: TrainedClassifier, stats_src: Mapping, td: TileData, model_id: str, notes: Sequence[str],
                    created_at: str, subjects: int, sessions: int) -> dict:
    """A complete bundle of the evidence model: the fitted classifier and standardization, the weighted class priors,
    the HSMM statistics copied from the night model ``stats_src`` (section 6.2), no level."""
    st = tc.standardizer
    nn = [*notes, tc.spec.describe(), f"masked features (mean 0, std {MASK_STD:g}, zero first-layer weights): "
          + ", ".join(MASKED_FEATURES), "rows: tiles of both variants (" + ", ".join(TRAIN_VARIANT_ORDER)
          + f"), each dataset half of the weight, each subject the same weight within its dataset; rows "
            f"{tc.info.get('rows')}", "durations, bout transitions, initial stage and time prior copied from the night "
          "model; class priors = the weighted class frequencies; napMode.contextMarginMin 0"]
    b = {"schemaVersion": 1, "modelId": str(model_id), "featureSetVersion": int(td.fsv),
         "featureNames": list(td.names),
         "standardization": {"mean": [float(x) for x in st.mean], "std": [float(x) for x in st.std]},
         "classOrder": list(STAGES), "classifier": {"type": tc.type, "layers": tc.layers_json()},
         "classPriors": [float(x) for x in tc.class_priors], **statistics_of(stats_src),
         "training": {"dataset": "tiles of walch+bid-zero nights", "license": "see tools/train/DATASETS.md",
                      "subjects": int(subjects), "nights": int(sessions), "variants": list(TRAIN_VARIANT_ORDER),
                      "createdAt": created_at, "notes": "; ".join(nn)}}
    validate_bundle(b, expected_feature_names=td.names)
    bad = masked_problems(b, MASKED_FEATURES)
    if bad:
        raise NapsRealisticError(f"{model_id}: masked columns not masked: {bad}")
    return b


def masked_problems(b: Mapping, masked: Sequence[str]) -> list[str]:
    """G3b: every masked column has std 1e12 and exactly zero first-layer weights in the exported bundle."""
    names = list(b["featureNames"])
    w0 = np.asarray(b["classifier"]["layers"][0]["weights"], float)
    out = []
    for n in masked:
        i = names.index(n)
        if b["standardization"]["std"][i] != MASK_STD or b["standardization"]["mean"][i] != 0.0:
            out.append(f"{n}: standardization {b['standardization']['mean'][i]} / {b['standardization']['std'][i]}")
        if np.any(w0[:, i] != 0.0):
            out.append(f"{n}: non-zero first-layer weights")
    return out


def standalone(model: Mapping, bias: Sequence[float] | None = None, table: Mapping | None = None) -> dict:
    """An evaluation bundle whose main model and nap section are both ``model`` (a bundle without level), the nap
    section with the constant ``bias`` or the length ``table`` (``napmodel.standalone`` with a table)."""
    if table is None:
        return napmodel.standalone(model, bias)
    base = with_stage_log_bias(model, None)
    return with_nap_model(base, with_nap_level(nap_section(base), length_bias=table))


def zero_table(anchors: Sequence[int] = ANCHORS) -> dict:
    return length_bias_table(anchors, [[0.0] * N_STAGES for _ in anchors])


# ===================================================================================== reproduction gates ====

def stored_n1(inp: Inputs) -> dict[tuple[str, str], dict]:
    """13.12's N1 decodes of its proxies (onset and ends-in-wake) in ``nap-model/results.jsonl`` by (variant, id)."""
    out = {}
    for r in napmodel._read_jsonl(inp.nap_model_dir / "results.jsonl"):
        if r["candidate"] == REFERENCE and r["proxy"] in napmodel.PROXY_KINDS and r["variant"] in inp.variants:
            out[(r["variant"], r["night"])] = r
    return out


def same_decode(a: Mapping | None, b: Mapping | None) -> bool:
    """Equal confusion, estimated status and warnings (no warning adjustment)."""
    return (a is not None and b is not None and a["confusion"] == b["confusion"] and a["estimated"] == b["estimated"]
            and list(a["warnings"]) == list(b["warnings"]))


def gate_g1(inp: Inputs, cli: AlgoCli, out: Path, jobs: int) -> tuple[dict, list[dict]]:
    """G1 (fatal): N1's fold bundles (the N0 fold bundle with the stored outer nap model and the group's nap stage
    log-bias) and parameters (theta_g, theta_nap,g) reproduce every stored N1 decode of ``nap-model/results.jsonl``
    (onset proxies and ends-in-wake windows, :data:`G1_PER_VARIANT` per variant) with equal confusion, estimated status
    and warnings, and candidate B of ``bias-base/bias`` on every whole night. Returns (record, decodes)."""
    proxies, meta, counts = napmodel.build_proxies(inp.nm)
    files = {}
    for v in inp.variants:
        try:
            napmodel.check_sessions_file(inp.nap_model_dir / "proxies" / f"{v}.jsonl", proxies[v])
            files[v] = True
        except napmodel.NapModelError:
            files[v] = False
    stored, nights = stored_n1(inp), napmodel.stored_nights(inp.nm)
    work = out / "g1"

    def one(g: napmodel.Group):
        v, held = g.variant, set(g.heldout)
        bundles = {s: napmodel.n1_bundle(inp.fold(v, s), g, inp.n1_nap(v, s), inp.n1[(v, g.key)]["bias"])
                   for s in g.heldout}
        d = write_bundles(work / "models" / v / g.key, bundles)
        ss = [p for p in proxies[v] if meta[v][p["id"]]["subject"] in held]
        mt = {p["id"]: meta[v][p["id"]] for p in ss}
        for n in inp.index.nights_of(v, sorted(held)):
            night = inp.night(v, n.id)
            ss.append(night)
            mt[n.id] = {**night_meta(night), "proxy": "night"}
        try:
            recs = evaluate(cli, work / v / f"{g.key}.json", ss, mt, "HYBRID", inp.n1_params(g), fold_models=d,
                            extra={"model": REFERENCE, "group": g.key}, keep_metrics=False)
        finally:
            shutil.rmtree(d, ignore_errors=True)
        return recs, {(v, s["id"]): int(s["sessionEndMs"]) - int(s["sessionStartMs"]) for s in ss}

    res = loso.map_threads(one, inp.groups, jobs)
    recs = [r for rs, _ in res for r in rs]
    sms = {k: x for _, m in res for k, x in m.items()}
    prox = [r for r in recs if r["proxy"] in napmodel.PROXY_KINDS]
    got = {(r["variant"], r["night"]): r for r in prox}
    per_variant: dict = {}
    mismatches = []
    for v in inp.variants:
        keys = sorted(k for k in set(stored) | set(got) if k[0] == v)
        same = [k for k in keys if same_decode(stored.get(k), got.get(k))]
        per_variant[v] = {"stored": sum(1 for k in stored if k[0] == v), "decoded": sum(1 for k in got if k[0] == v),
                          "identical": len(same), "onset": sum(1 for k in same if got[k]["proxy"] == "onset"),
                          "ew": sum(1 for k in same if got[k]["proxy"] == "ew")}
        mismatches += [list(k) for k in keys if k not in set(same)][:10]
    chk = napmodel.check_records([r for r in recs if r["proxy"] == "night"], nights, {}, sms, 0.0)
    ok_p = all(q["stored"] == q["decoded"] == q["identical"] == G1_PER_VARIANT for q in per_variant.values())
    ok_n = chk["nights"] == chk["nightsIdentical"] == len(nights) > 0
    rec = {"proxyFiles": files, "proxyCounts": counts, "perVariant": per_variant, "expectedPerVariant": G1_PER_VARIANT,
           "proxyMismatches": mismatches[:10], "nights": chk["nights"], "nightsIdenticalToB": chk["nightsIdentical"],
           "storedNights": len(nights), "nightMismatches": chk["mismatches"],
           "passed": bool(all(files.values()) and ok_p and ok_n)}
    return rec, recs


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _decode_key(recs: Iterable[Mapping]) -> dict[str, tuple]:
    return {r["id"]: (None if r["confusion"] is None else np.asarray(r["confusion"]).tolist(), list(r["warnings"]),
                      bool(r["estimated"])) for r in recs}


def probe_fingerprints(cli: AlgoCli, bundles: Sequence[Path], work: Path) -> dict | None:
    """``fingerprint()`` and ``nightModelFingerprint()`` of ``bundles`` computed by the core-algo classes of the
    algo-cli build (its ``lib/*.jar``) with the probe :data:`PROBE_SOURCE`; None when the launcher has no ``lib``
    beside it (not an ``installDist`` build)."""
    lib = cli.launcher.parent.parent / "lib"
    jars = sorted(lib.glob("*.jar")) if lib.is_dir() else []
    if not jars:
        return None
    java = shutil.which("java")
    if java is None:
        raise NapsRealisticError("java not found (run under mise exec): the fingerprint probe needs it")
    work.mkdir(parents=True, exist_ok=True)
    probe = work / "FingerprintProbe.java"
    probe.write_text(PROBE_SOURCE, encoding="utf-8")
    proc = subprocess.run([java, "-cp", os.pathsep.join(str(j) for j in jars), str(probe), *map(str, bundles)],
                          capture_output=True, text=True, timeout=600)
    if proc.returncode != 0:
        raise NapsRealisticError(f"fingerprint probe failed (exit {proc.returncode}): {proc.stderr[-2000:]}")
    out = {}
    for line in proc.stdout.splitlines():
        path, fp, nfp = line.split("\t")
        out[path] = {"fingerprint": fp, "nightModelFingerprint": nfp}
    return out


# G2: the core-algo tests that pin the previous bundle and the nights ("NapModeRegressionTest passes with its recorded
# digests"; HybridModelTest holds the base-build fingerprint pins), run on this tree by the gate itself
G2_JVM_TESTS: tuple[str, ...] = ("io.github.alien2003.pulsestages.algo.NapModeRegressionTest",
                                 "io.github.alien2003.pulsestages.algo.hybrid.HybridModelTest")
JVM_TEST_TIMEOUT_S = 3600


def jvm_test_record(classes: Sequence[str] = G2_JVM_TESTS, root: Path = REPO_ROOT) -> dict:
    """Runs ``./gradlew :core-algo:test --tests <class> ... --rerun`` on the working tree (forced, so the JUnit XML is
    written now) and reads each class's ``TEST-<class>.xml``: passed when Gradle succeeded and every class ran now with
    at least one test and no failure, error or skip."""
    cmd = [str(root / "gradlew"), "--console=plain", ":core-algo:test",
           *[x for c in classes for x in ("--tests", c)], "--rerun"]
    results = root / "core-algo" / "build" / "test-results" / "test"
    t0 = time.time()
    try:
        proc = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=JVM_TEST_TIMEOUT_S)
        rc, text = proc.returncode, proc.stdout + proc.stderr
    except (OSError, subprocess.TimeoutExpired) as e:
        rc, text = None, str(e)
    rec: dict = {"command": " ".join(cmd[1:]), "exitCode": rc, "classes": {}}
    ok = rc == 0
    for c in classes:
        f = results / f"TEST-{c}.xml"
        q: dict = {"file": str(f.relative_to(root)) if f.is_relative_to(root) else str(f)}
        try:
            fresh = f.stat().st_mtime >= t0 - 1.0
            suite = ET.parse(f).getroot()
            q.update({k: int(suite.get(k, "-1")) for k in ("tests", "failures", "errors", "skipped")},
                     timestamp=suite.get("timestamp"), writtenNow=fresh)
            q["passed"] = bool(fresh and q["tests"] > 0 and q["failures"] == q["errors"] == q["skipped"] == 0)
        except (OSError, ET.ParseError) as e:
            q.update(error=str(e), passed=False)
        rec["classes"][c] = q
        ok = ok and q["passed"]
    if not ok:
        rec["outputTail"] = text[-4000:]
    rec["passed"] = bool(ok)
    return rec


def gate_g2(inp: Inputs, cli: AlgoCli, out: Path) -> dict:
    """G2 (fatal): ``NapModeRegressionTest`` passes with its recorded digests (run now on this tree by
    :func:`jvm_test_record`, with ``HybridModelTest``'s base-build fingerprint pins); with the previous (shipped)
    bundle and the default parameters the build decodes all whole nights exactly as the base build of commit cad7a6f
    (per-night confusion, warnings and estimated status; per-epoch CSV byte for byte; references in
    ``<g2-base>/ref/nights``), and identically with ``napMode.contextMarginMin`` = 0; the bundle's ``fingerprint()``
    and ``nightModelFingerprint()`` under this build equal the base build's values (logged NOTE; a probe that cannot
    run fails). Reported: the base CSVs against ``nap-model-verify/final/<variant>.installed.per-epoch.csv``."""
    if inp.g2_base is None:
        raise NapsRealisticError("G2 needs --g2-base (the saved base build's references)")
    work = out / "g2"
    work.mkdir(parents=True, exist_ok=True)
    prev = inp.previous_bundle
    rec: dict = {"previousBundle": str(prev), "previousBundleSha256": _sha256(prev) if prev.is_file() else None,
                 "base": str(inp.g2_base), "variants": {}}
    ok = rec["previousBundleSha256"] == SHIPPED_BUNDLE_SHA256
    rec["jvmTests"] = jvm_test_record()        # NapModeRegressionTest with its recorded digests (+ HybridModelTest)
    ok = ok and rec["jvmTests"]["passed"]
    margin0 = write_json(work / "margin0.params.json", {"napMode": {"contextMarginMin": 0}})
    for v in inp.variants:
        ref_json, ref_pe = inp.g2_base / "ref" / "nights" / f"{v}.json", inp.g2_base / "ref" / "nights" / \
            f"{v}.per-epoch.csv"
        ref = _decode_key(report.parse_metrics(read_json(ref_json)))
        ref_digest = _sha256(ref_pe)
        q: dict = {"nights": len(ref), "refPerEpochSha256": ref_digest}
        for tag, pf in (("default", None), ("margin0", margin0)):
            pe = work / f"{v}.{tag}.per-epoch.csv"
            mp = work / f"{v}.{tag}.json"
            m = cli.evaluate(inp.nights_root / v, "HYBRID", mp, params=pf, model=prev, per_epoch=pe)
            got = _decode_key(report.parse_metrics(m))
            same = sum(1 for k in ref if ref[k] == got.get(k))
            q[tag] = {"nights": len(got), "identical": same, "perEpochSha256": _sha256(pe),
                      "mismatches": sorted(k for k in set(ref) | set(got) if ref.get(k) != got.get(k))[:10]}
            q[tag]["passed"] = same == len(ref) == len(got) > 0 and q[tag]["perEpochSha256"] == ref_digest
            ok = ok and q[tag]["passed"]
            pe.unlink(missing_ok=True)
            mp.unlink(missing_ok=True)
        nmv = inp.nm.base.run_dir.parent / "nap-model-verify" / "final" / f"{v}.installed.per-epoch.csv"
        if nmv.is_file():
            with open(ref_pe, encoding="utf-8") as a, open(nmv, encoding="utf-8") as b:
                q["reportedNapModelVerifySame"] = sorted(a.read().splitlines()) == sorted(b.read().splitlines())
        rec["variants"][v] = q
    fps = probe_fingerprints(cli, [prev], work) if prev.is_file() else None
    if fps is None:          # the probe could not run: the pre-registered equality is not shown, so G2 fails
        rec["fingerprints"] = {"probe": "the launcher has no lib/*.jar (not an installDist build) or the previous "
                                        "bundle is missing: fingerprints not computed", "passed": False}
        ok = False
    else:
        f = fps[str(prev)]
        rec["fingerprints"] = {**f, "expected": {"fingerprint": BASE_FINGERPRINT,
                                                 "nightModelFingerprint": BASE_NIGHT_FINGERPRINT}}
        rec["fingerprints"]["passed"] = (f["fingerprint"] == BASE_FINGERPRINT
                                         and f["nightModelFingerprint"] == BASE_NIGHT_FINGERPRINT)
        ok = ok and rec["fingerprints"]["passed"]
    rec["passed"] = bool(ok)
    return rec


def gate_g3a(inp: Inputs, cli: AlgoCli, out: Path, jobs: int) -> dict:
    """G3a (fatal): the Python cut of the onset proxies (``naps.nap_proxy`` at the anchors) gives exactly (every row,
    every field) the features of ``algo-cli features --nap-proxies 30,60,90,120,150,170``, with the base parameters and
    with ``napMode.contextMarginMin`` = 0; ``FEATURE_SET_VERSION`` 1 with the 40 names of ``algo-cli feature-names``
    (the masked features among them)."""
    info = cli.feature_names()
    names = list(info["featureNames"])
    rec: dict = {"featureSetVersion": int(info["featureSetVersion"]), "features": len(names),
                 "maskedPresent": all(n in names for n in MASKED_FEATURES), "parity": []}
    work = out / "g3a"
    base = dict(inp.cfg.base_params)
    cases = {"base": base, "margin0": inp.feature_params(EVIDENCE_MARGIN_MIN)}
    lengths = ",".join(str(L) for L in ANCHORS)

    def one(task):
        v, tag = task
        pf = write_json(work / f"{v}.{tag}.params.json", cases[tag])
        ss = [s for n in inp.index.for_variant(v) for s, _ in pools.onset(inp.night(v, n.id), ANCHORS)]
        nf = write_sessions(work / f"{v}.{tag}.onset.jsonl", ss)
        py = cli.features(nf, work / f"{v}.{tag}.py.csv", params=pf)
        kt = work / f"{v}.{tag}.kotlin.csv"
        cli.run(["features", "--nights", str(inp.nights_root / v), "--nap-proxies", lengths, "--params", str(pf),
                 "--out", str(kt)])
        ids = {s["id"] for s in ss}
        h1, a = napmodel._csv_rows_by_id(py, lambda nid: nid in ids)
        h2, b = napmodel._csv_rows_by_id(kt, lambda nid: nid in ids)
        diff = sorted(k for k in set(a) | set(b) if a.get(k) != b.get(k))
        for f in (nf, py, kt):
            f.unlink(missing_ok=True)
        return {"variant": v, "params": tag, "onsetProxies": len(ss), "pythonSessions": len(a),
                "kotlinSessions": len(b), "rowsCompared": sum(len(x) for x in a.values()), "headerEqual": h1 == h2,
                "nDiffering": len(diff), "differing": diff[:10],
                "passed": h1 == h2 and not diff and len(a) == len(b) == len(ss)}

    tasks = [(v, t) for v in inp.variants for t in cases]
    rec["parity"] = loso.map_threads(one, tasks, max(1, min(jobs, len(tasks))))
    rec["passed"] = bool(rec["featureSetVersion"] == EXPECTED_FEATURE_SET_VERSION and len(names) == EXPECTED_FEATURES
                         and rec["maskedPresent"] and all(p["passed"] for p in rec["parity"]))
    return rec


# ------------------------------------------------------------------------------------- G4: N1's inner models ----

def n1_data(inp: Inputs, cli: AlgoCli) -> napmodel.Data:
    """13.12's proxies and their stored features (``nap-model/features``; the session files checked byte for byte),
    the rows N1's inner models were fitted on."""
    proxies, meta, _ = napmodel.build_proxies(inp.nm)
    return napmodel.load_data(inp.nm, cli, proxies, meta, inp.nap_model_dir)


def n1_choice(inp: Inputs, g: napmodel.Group) -> tuple[str, float | None]:
    """13.12's inner choice of (variant, group): classifier and C (the MLP in every selection of ``nap-model``)."""
    ch = inp.n1[(g.variant, g.key)]["chosen"]
    return str(ch["classifier"]), None if ch.get("C") is None else float(ch["C"])


def n1_inner_tasks(inp: Inputs) -> list[FitKey]:
    """N1's inner fold models of each group's chosen classifier and C (section 6.5; the MLP in all 10 selections of
    13.12, hence "N1's refitted inner MLPs" of gate G4), refitted on 13.12's rows (no weights, no mask)."""
    return [("n1", tr, *n1_choice(inp, g)) for g in inp.groups for _, tr, _ in napmodel.inner_fold_sets(g)]


def n1_inner_models(inp: Inputs, data: napmodel.Data, fits: Mapping, g: napmodel.Group, created_at: str
                    ) -> dict[str, dict]:
    """N1's inner fold nap models of (variant, group) by inner held-out Walch subject: the refitted classifier of the
    group's choice with the label statistics of the inner training subjects' proxies (13.12's procedure), no
    level."""
    out = {}
    clf, C = n1_choice(inp, g)
    for k, tr, te in napmodel.inner_fold_sets(g):
        stats = napmodel.label_stats(inp.nm, data, g.variant, tr)
        b = napmodel.nap_bundle(inp.nm, data, fits[("n1", tr, clf, C)], g.variant, tr,
                                f"nap-inner-{g.variant}-{clf}-{g.key}-k{k}",
                                [f"inner fold {k} of selection set {g.key}"], created_at, stats=stats)
        for s in te:
            out[s] = b
    return out


def gate_g4(inp: Inputs, cli: AlgoCli, data: napmodel.Data, fits: Mapping, out: Path, jobs: int,
            created_at: str) -> tuple[dict, dict]:
    """G4 (C0 dropped on failure): N1's refitted inner models (the MLPs of every 13.12 selection), decoded on 13.12's
    inner held-out proxies at the stored chosen point, reproduce the stored inner score
    (``nap-model/inner/<variant>/<group>/selection.json``) of every (variant, group) exactly. Returns (record, inner
    models by (variant, group))."""
    work = out / "g4"

    def one(g: napmodel.Group):
        stored = read_json(inp.nap_model_dir / "inner" / g.variant / g.key / "selection.json")["chosen"]
        models = n1_inner_models(inp, data, fits, g, created_at)
        held = set(g.eval_subjects)
        ss = [p for p in data.proxies[g.variant] if data.meta[g.variant][p["id"]]["subject"] in held]
        mt = {p["id"]: data.meta[g.variant][p["id"]] for p in ss}
        params = napmodel.grid_points(inp.nm.eval_params(g.theta, g.theta))[int(stored["index"])]["params"]
        d = write_bundles(work / "models" / g.variant / g.key, {s: napmodel.standalone(b) for s, b in models.items()})
        try:
            recs = evaluate(cli, work / g.variant / f"{g.key}.json", ss, mt, "HYBRID", params, fold_models=d,
                            keep_metrics=False)
        finally:
            shutil.rmtree(d, ignore_errors=True)
        score = report.mean_subject_kappa(recs)
        return g, models, {"variant": g.variant, "key": g.key, "classifier": stored["classifier"], "C": stored["C"],
                           "storedScore": stored["score"], "score": score, "point": stored["index"],
                           "sessions": len(ss), "passed": score == stored["score"]}

    res = loso.map_threads(one, inp.groups, jobs)
    groups = [r for _, _, r in res]
    return ({"groups": groups, "passed": all(r["passed"] for r in groups)},
            {(g.variant, g.key): m for g, m, _ in res})


# ============================================================================================ level fits ====

def lit_record(lw: litprior.LitWeights) -> dict:
    """One LIT weighting for the run's ``lit/`` record (section 19): how it was obtained (target, lambda, Newton
    steps, effective sample size, ...) and the weights themselves by session id."""
    return {**lw.as_dict(), "ids": [str(i) for i in lw.ids], "w": [float(x) for x in np.asarray(lw.w, float)]}


def write_lit(out: Path, lit_w: Mapping, lit_mid_w: Mapping) -> None:
    """``lit/`` (section 19): ``targets.json`` = every number of sections 5.1-5.3 recomputed from the arm table
    (:func:`litprior.summary_tables`), ``eval.json`` = the evaluation LIT (ANCHOR) and LIT_mid (MIDANCHOR) weights per
    dataset, variant and length with their targets, lambda, Newton steps and effective sample sizes. The weights of
    the inner C2 / C0 table fits are written by :func:`select_group` (``lit/inner/<variant>/<group>.json``)."""
    d = Path(out) / "lit"
    write_json(d / "targets.json", naps._clean(litprior.summary_tables()))
    write_json(d / "eval.json", naps._clean({
        pool: {ds_: {v: {str(L): lit_record(lw) for L, lw in t.items()} for v, t in q.items()} for ds_, q in w.items()}
        for pool, w in (("ANCHOR", lit_w), ("MIDANCHOR", lit_mid_w))}))


def lit_table_fit(decode_records: Callable[[Mapping], list[dict]], anchors: Sequence[int] = ANCHORS) -> dict:
    """The per-length table of C2 / C0 (section 6.4): ``decode_records(table)`` decodes the ANCHOR sessions (one or
    both variants) with the length table ``table`` and returns their records. The first decode (all rows 0) fixes the
    sessions estimated by HYBRID (with a scored chunk; estimation does not depend on the level) and their literature
    weights per variant and anchor, from their labels (section 5.5, ``realism.lit_weight_table``, normalised to 1 per
    variant and anchor); every decode then gives each anchor the weighted pooled confusion matrix summed over the
    variants, and ``bias.fit_stage_bias_table`` runs the fixed point (at most 8 decodes, the first included)."""
    anchors = [int(a) for a in anchors]

    def table_of(rows: Mapping) -> dict:
        return length_bias_table(anchors, [np.asarray(rows[float(a)], float).tolist() for a in anchors])

    first = decode_records(table_of({float(a): np.zeros(N_STAGES) for a in anchors}))
    est = [r for r in hybrid_scored(first) if float(r["L"]) in {float(a) for a in anchors}]
    weights: dict[str, dict[int, litprior.LitWeights]] = {}
    for v in sorted({str(r["variant"]) for r in est}):
        items = [{"id": r["night"], "subject": r["subject"], "L": float(r["L"]), "counts": r["labelCounts"]}
                 for r in est if r["variant"] == v]
        weights[v] = realism.lit_weight_table(items, anchors)
        missing = [a for a in anchors if a not in weights[v]]
        if missing:
            raise NapsRealisticError(f"{v}: no session estimated by HYBRID at the anchors {missing}")

    def weighted(recs: Sequence[Mapping]) -> dict[float, np.ndarray]:
        by = {(str(r["variant"]), str(r["night"])): r for r in recs}
        mats = {float(a): np.zeros((N_STAGES, N_STAGES)) for a in anchors}
        for v, table in weights.items():
            for a, lw in table.items():
                w = np.asarray(lw.w, float) / float(np.sum(lw.w))
                for i, wi in zip(lw.ids, w):
                    r = by.get((v, i))
                    if r is None or not realism.is_hybrid_scored(r):
                        raise NapsRealisticError(f"{v}/{i}: estimated by HYBRID in the first decode, not later")
                    mats[float(a)] += wi * np.asarray(r["confusion"], float)
        return mats

    state = {"first": True}

    def decode(rows: Mapping[float, np.ndarray]) -> dict[float, np.ndarray]:
        if state["first"] and all(not np.any(np.asarray(x)) for x in rows.values()):
            state["first"] = False
            return weighted(first)
        return weighted(decode_records(table_of(rows)))

    fit = fit_stage_bias_table(decode, anchors)
    fit["table"] = length_bias_table(anchors, fit["bias"])
    fit["weights"] = {v: {str(a): lw.as_dict() for a, lw in t.items()} for v, t in weights.items()}
    fit["weightValues"] = {v: {str(a): lit_record(lw) for a, lw in t.items()} for v, t in weights.items()}
    fit["sessions"] = {v: sum(len(lw.ids) for lw in t.values()) for v, t in weights.items()}
    return fit


def inner_statistics(inp: Inputs, g: napmodel.Group) -> tuple[dict[int, dict], list[dict]]:
    """Per inner fold: the HSMM statistics of ``bias-base``'s kept inner fold night model (the copies of all its Walch
    subjects must be equal: gate G3b)."""
    stats, checks = {}, []
    for k, _, te in napmodel.inner_fold_sets(g):
        srcs = {s: statistics_of(read_json(inp.inner_night_model(g.variant, g.key, s))) for s in te}
        first = srcs[te[0]]
        equal = all(canonical_json(x) == canonical_json(first) for x in srcs.values())
        stats[k] = first
        checks.append({"variant": g.variant, "key": g.key, "fold": k, "subjects": list(te), "equal": equal})
    return stats, checks


def tile_count(sessions: Sessions, subjects: Iterable[str]) -> int:
    sub = set(subjects)
    return sum(1 for _, m in sessions.by[TRAIN_VARIANT_ORDER[0]]["tile"] if m["subject"] in sub)


def evidence_inner_models(td: TileData, sessions: Sessions, fits: Mapping, g: napmodel.Group, clf: str,
                          C: float | None, stats: Mapping[int, Mapping], created_at: str) -> dict[str, dict]:
    """The evidence model's inner fold models of (variant, group) by inner held-out Walch subject."""
    out = {}
    for k, tr, te in napmodel.inner_fold_sets(g):
        b = evidence_bundle(fits[("tiles", tr, clf, C)], stats[k], td, f"evidence-inner-{g.variant}-{clf}-{g.key}-k{k}",
                            [f"inner fold {k} of selection set {g.key}"], created_at, len(tr), tile_count(sessions, tr))
        for s in te:
            out[s] = b
    return out


def inner_tasks(inp: Inputs, Cs: Sequence[float]) -> list[FitKey]:
    tasks: list[FitKey] = []
    for g in inp.groups:
        for _, tr, _ in napmodel.inner_fold_sets(g):
            tasks += [("tiles", tr, "lr", float(C)) for C in Cs] + [("tiles", tr, "mlp", None)]
    return tasks


def select_group(inp: Inputs, td: TileData, sessions: Sessions, cli: AlgoCli, g: napmodel.Group,
                 fits: Mapping[FitKey, TrainedClassifier], n1_models: Mapping[str, Mapping] | None, out: Path,
                 created_at: str) -> dict:
    """Nested inner CV of (variant, group) (section 6.2): C of the logistic regression by the unweighted mean
    log-loss on the inner held-out Walch rows of the variant; the decoder grid ``nap-evidence`` per classifier on the
    inner held-out Walch subjects' tiles, each decoded by its inner fold model as a standalone bundle without level
    (``napMode.contextMarginMin`` 0); inner score = mean per-subject kappa over the HYBRID-estimated tiles; highest
    wins, ties -> logistic regression, then grid order. Then, with the chosen classifier and theta_E,g: the C1 bias
    (``bias.fit_stage_bias``, unchanged, on the pooled decodes of the tiles, section 6.3; pooled as 13.12 pooled its
    decodes, every estimated tile with a scored chunk) and the C2 table (:func:`lit_table_fit` on the
    ANCHOR sessions, section 6.4); and the C0 table with N1's inner models at theta_nap,g (section 6.5) when
    ``n1_models`` is given (G4 passed)."""
    v = g.variant
    folds = napmodel.inner_fold_sets(g)
    work = out / "inner" / v / g.key
    work.mkdir(parents=True, exist_ok=True)
    Cs = [float(C) for C in inp.cfg.lr_Cs]
    loss = {C: 0.0 for C in Cs}
    n = 0
    for _, tr, te in folds:
        m = np.isin(td.subject, np.array(te, dtype=object)) & (td.variant == v)
        for C in Cs:
            loss[C] += log_loss(fits[("tiles", tr, "lr", C)].predict_proba(td.X[m]), td.y[m]) * int(m.sum())
        n += int(m.sum())
    best_C = min(Cs, key=lambda C: (loss[C], Cs.index(C)))
    stats, stat_checks = inner_statistics(inp, g)
    models = {clf: evidence_inner_models(td, sessions, fits, g, clf, best_C if clf == "lr" else None, stats,
                                         created_at) for clf in CLASSIFIER_ORDER}
    masked = [p for clf in CLASSIFIER_ORDER for b in {id(x): x for x in models[clf].values()}.values()
              for p in masked_problems(b, MASKED_FEATURES)]
    ss, mt = sessions.of(v, ["tile"], g.eval_subjects)
    tiles_file = write_sessions(work / "tiles.sessions.jsonl", ss)
    points = napmodel.grid_points(inp.eval_params(g, inp.n1[(v, g.key)]["theta"], EVIDENCE_MARGIN_MIN),
                                  EVIDENCE_GRID)
    grid: dict[str, list[dict]] = {}
    for clf in CLASSIFIER_ORDER:
        d = write_bundles(work / f"models-{clf}", {s: standalone(b) for s, b in models[clf].items()})
        try:
            res = cli.evaluate_grid(tiles_file, "HYBRID", work / f"grid-{clf}.json", points, fold_models=d)
        finally:
            shutil.rmtree(d, ignore_errors=True)
        rows = []
        for p, gm in zip(points, res["grid"]):
            score = hybrid_kappa(napmodel.tag(gm["metrics"], mt, "HYBRID"))
            rows.append({"id": p["id"], "index": p["index"], "hybrid": p["hybrid"],
                         "score": None if not math.isfinite(score) else score})
        grid[clf] = rows
    cands = [(clf, r) for clf in CLASSIFIER_ORDER for r in grid[clf]]
    clf, best = max(cands, key=lambda x: (x[1]["score"] if x[1]["score"] is not None else -math.inf,
                                          -CLASSIFIER_ORDER.index(x[0]), -x[1]["index"]))
    runner = max((x[1]["score"] for x in cands if x[1] is not best and x[1]["score"] is not None), default=None)
    params = points[best["index"]]["params"]
    chosen_models = models[clf]
    counter = iter(range(10_000))

    def decode_tiles(b: np.ndarray) -> np.ndarray:
        i = next(counter)
        d = write_bundles(work / f"c1-{i}", {s: standalone(m, b.tolist() if np.any(b) else None)
                                             for s, m in chosen_models.items()})
        try:
            recs = evaluate(cli, work / f"c1-{i}.json", tiles_file, mt, "HYBRID", params, fold_models=d,
                            keep_metrics=False)
        finally:
            shutil.rmtree(d, ignore_errors=True)
        # every estimated tile with a scored chunk, as 13.12's fixed point pooled its decodes (``naps._pooled``)
        return naps._pooled(recs)

    c1 = fit_stage_bias(decode_tiles)
    an_ss, an_mt = sessions.of(v, ["eval"], g.eval_subjects, where=pools.is_anchor)
    anchors_file = write_sessions(work / "anchors.sessions.jsonl", an_ss)

    def table_decoder(tag: str, bundles: Mapping[str, Mapping], p: Mapping) -> Callable[[Mapping], list[dict]]:
        it = iter(range(10_000))

        def decode(table: Mapping) -> list[dict]:
            i = next(it)
            d = write_bundles(work / f"{tag}-{i}", {s: standalone(m, table=table) for s, m in bundles.items()})
            try:
                return evaluate(cli, work / f"{tag}-{i}.json", anchors_file, an_mt, "HYBRID", p, fold_models=d,
                                keep_metrics=False)
            finally:
                shutil.rmtree(d, ignore_errors=True)

        return decode

    c2 = lit_table_fit(table_decoder("c2", chosen_models, params))
    c0 = None
    if n1_models is not None:
        c0 = lit_table_fit(table_decoder("c0", n1_models, inp.n1_params(g)))
    for f in (tiles_file, anchors_file):
        f.unlink(missing_ok=True)
    for f in work.glob("grid-*"):
        f.unlink(missing_ok=True)
    write_json(out / "lit" / "inner" / v / f"{g.key}.json", naps._clean(
        {"variant": v, "key": g.key, **{name: fit.pop("weightValues") for name, fit in (("c2", c2), ("c0", c0))
                                         if fit is not None}}))
    level = ("bias", "D", "decodes", "trueShares", "predShares", "bestIndex", "trace")
    res = {"variant": v, "key": g.key, "heldout": g.heldout, "subjects": len(g.subjects),
           "evalSubjects": g.eval_subjects, "innerFoldsUsed": [k for k, _, _ in folds], "tiles": len(ss),
           "anchorSessions": len(an_ss), "C": best_C,
           "logLoss": {f"{C:g}": x / n if n else None for C, x in loss.items()},
           "grid": grid, "chosen": {"classifier": clf, "C": best_C if clf == "lr" else None, "id": best["id"],
                                    "index": best["index"], "hybrid": best["hybrid"], "score": best["score"]},
           "margin": None if runner is None or best["score"] is None else best["score"] - runner,
           "bestPerClassifier": {c: max(grid[c], key=lambda r: (r["score"] if r["score"] is not None else -math.inf,
                                                                 -r["index"])) for c in CLASSIFIER_ORDER},
           "statistics": stat_checks, "maskedProblems": masked,
           "c1": {k: c1[k] for k in level}, "c2": c2,
           "c0": c0, "n1": {"theta": inp.n1[(v, g.key)]["theta"], "bias": inp.n1[(v, g.key)]["bias"]}}
    write_json(work / "selection.json", naps._clean(res))
    return res


# ======================================================================================== outer models ====

def outer_tasks(inp: Inputs, sel: Mapping[tuple[str, str], Mapping]) -> list[FitKey]:
    tasks: list[FitKey] = []
    for g in inp.groups:
        ch = sel[(g.variant, g.key)]["chosen"]
        for s in g.heldout:
            tasks.append(("tiles", tuple(x for x in inp.pool if x != s), ch["classifier"], ch["C"]))
    return tasks


def outer_models(inp: Inputs, td: TileData, sessions: Sessions, sel: Mapping[tuple[str, str], Mapping],
                 fits: Mapping[FitKey, TrainedClassifier], out: Path, created_at: str
                 ) -> tuple[dict[tuple[str, str], dict], list[dict]]:
    """The C1 / C2 outer fold nap models (section 6.2): per held-out subject the group's classifier fitted on the tiles
    of every other subject, statistics copied from the subject's outer fold night bundle, no level; written to
    ``outer/<variant>/nap/<subject>.json``. Returns (models, fold metas)."""
    models, metas = {}, []
    for g in inp.groups:
        s_ = sel[(g.variant, g.key)]
        ch = s_["chosen"]
        for s in g.heldout:
            tr = tuple(x for x in inp.pool if x != s)
            tc = fits[("tiles", tr, ch["classifier"], ch["C"])]
            b = evidence_bundle(tc, statistics_of(inp.fold(g.variant, s)), td,
                                f"evidence-loso-{g.variant}-{ch['classifier']}-{s}",
                                [f"LOSO fold, held out: {s}", f"decoder chosen by inner CV of group {g.key}: "
                                 f"{canonical_json(_theta(ch['hybrid']))}"], created_at, len(tr),
                                tile_count(sessions, tr))
            write_bundle(out / "outer" / g.variant / "nap" / f"{s}.json", b)
            models[(g.variant, s)] = b
            metas.append({"variant": g.variant, "subject": s, "group": g.key, "classifier": ch["classifier"],
                          "C": ch["C"], "hybrid": _theta(ch["hybrid"]), "score": ch["score"],
                          "c1Bias": s_["c1"]["bias"], "c2Table": s_["c2"]["table"],
                          "c0Table": (s_.get("c0") or {}).get("table"), "n1Theta": s_["n1"]["theta"],
                          "n1Bias": s_["n1"]["bias"], "rows": tc.info.get("rows"),
                          "exportMaxAbsDiff": tc.info.get("export_max_abs_diff")})
    return models, metas


def fold_bundle(inp: Inputs, model: str, variant: str, subject: str, sel_g: Mapping,
                evidence: Mapping | None) -> dict:
    """The fold bundle of ``model`` for a held-out subject: the N0 fold bundle with the model's nap section."""
    g = inp.group_of(variant, subject)
    if model == REFERENCE:
        return napmodel.n1_bundle(inp.fold(variant, subject), g, inp.n1_nap(variant, subject),
                                  inp.n1[(variant, g.key)]["bias"])
    n0 = inp.n0(variant, subject)
    if model == "C0":
        return with_nap_model(n0, with_nap_level(nap_section(inp.n1_nap(variant, subject)),
                                                 length_bias=sel_g["c0"]["table"]))
    if model == "C1":
        return with_nap_model(n0, with_nap_level(nap_section(evidence), bias=sel_g["c1"]["bias"]))
    if model == "C2":
        return with_nap_model(n0, with_nap_level(nap_section(evidence), length_bias=sel_g["c2"]["table"]))
    raise ValueError(f"unknown model {model}")


def model_params(inp: Inputs, model: str, g: napmodel.Group, sel_g: Mapping) -> dict:
    """Section 6.6: theta_nap,g and the night's context for N1 / C0, theta_E,g and no context for C1 / C2."""
    if model in (REFERENCE, "C0"):
        return inp.n1_params(g)
    return inp.eval_params(g, _theta(sel_g["chosen"]["hybrid"]), EVIDENCE_MARGIN_MIN)


# ================================================================================================ evaluation ====

def part_sessions(inp: Inputs, sessions: Sessions, g: napmodel.Group, part: str, seeds: int
                  ) -> tuple[list[dict], dict[str, dict]]:
    """The sessions of a held-out group decoded in one call: ``main`` (EVAL, whole nights, tiles, LATESTART; with
    ``--per-epoch``), ``midanchor``, ``perturbed`` and ``featureless`` (copies for its held-out Walch subjects)."""
    if part == "main":
        return sessions.of(g.variant, MAIN_KINDS, g.heldout)
    if part in ("midanchor", "perturbed"):
        return sessions.of(g.variant, [part], g.heldout)
    walch = [s for s in g.heldout if inp.index.dataset_of(s) == report.HEADLINE_DATASET]
    items = featureless_items(walch, seeds, g.variant) if walch else []
    return [s for s, _ in items], {s["id"]: m for s, m in items}


def evaluate_models(inp: Inputs, cli: AlgoCli, sessions: Sessions, sel: Mapping, evidence: Mapping,
                    models_: Sequence[str], seeds: int, out: Path, jobs: int) -> dict[str, list[dict]]:
    """Outer evaluation (sections 4, 6.6, 7): per (variant, group) and model one call per part with that group's
    fold bundles and parameters (the main part with ``--per-epoch per-epoch/<model>/<variant>/<group>.csv``), and
    RULES / HMM-table on every EVAL session (all subjects, their own parameters). Returns the records per part
    (``main``, ``midanchor``, ``perturbed``, ``featureless``, ``context``)."""
    work = out / "work"
    files: dict[tuple, tuple[Path, dict]] = {}
    for g in inp.groups:
        for part in PARTS:
            ss, mt = part_sessions(inp, sessions, g, part, seeds)
            if ss:
                files[(g.variant, g.key, part)] = (write_sessions(work / g.variant / g.key / f"{part}.jsonl", ss), mt)
    write_json(out / "sessions" / "featureless.json", naps._clean({
        "generator": "pulsetrain.pools.featureless_set(<subjects>, seeds, variant=<variant>) (section 4.5)",
        "seeds": list(range(int(seeds))), "lengths": sorted(ANCHORS + MID_ANCHORS),
        "designs": list(pools.FEATURELESS_DESIGNS), "sds": list(pools.FEATURELESS_SDS),
        "note": "one file per (variant, group): the copies of the group's held-out Walch subjects, decoded by every "
                "model and deleted afterwards (regenerated byte for byte by the generator); digests of the files",
        "files": {f"{v}/{key}": {"sessions": len(mt), "sha256": _sha256(path),
                                 "subjects": sorted({str(m["subject"]) for m in mt.values()})}
                  for (v, key, part), (path, mt) in sorted(files.items()) if part == "featureless"}}))
    dirs = {}
    for g in inp.groups:
        sel_g = sel[(g.variant, g.key)]
        for model in models_:
            dirs[(g.variant, g.key, model)] = write_bundles(
                work / "models" / model / g.variant / g.key,
                {s: fold_bundle(inp, model, g.variant, s, sel_g, evidence.get((g.variant, s))) for s in g.heldout})
    tasks = [(g, model, part) for g in inp.groups for model in models_ for part in PARTS
             if (g.variant, g.key, part) in files]

    def one(task):
        g, model, part = task
        nf, mt = files[(g.variant, g.key, part)]
        pe = None
        if part == "main":
            pe = out / "per-epoch" / model / g.variant / f"{g.key}.csv"
            pe.parent.mkdir(parents=True, exist_ok=True)
        recs = evaluate(cli, out / "outer" / g.variant / f"{g.key}.{model}.{part}.json", nf, mt, "HYBRID",
                        model_params(inp, model, g, sel[(g.variant, g.key)]),
                        fold_models=dirs[(g.variant, g.key, model)], per_epoch=pe,
                        extra={"model": model, "group": g.key}, keep_metrics=part in ("main", "midanchor"))
        return part, recs

    def context(task):
        v, est = task
        ss, mt = sessions.of(v, ["eval"])
        return "context", evaluate(cli, out / "outer" / v / f"{est}.json", ss, mt, est, inp.context_params(est),
                                   extra={"model": est, "group": "-"})

    res = loso.map_threads(one, tasks, jobs)
    res += loso.map_threads(context, [(v, e) for v in inp.variants for e in CONTEXT_ESTIMATORS], jobs)
    shutil.rmtree(work, ignore_errors=True)
    by: dict[str, list[dict]] = {p: [] for p in (*PARTS, "context")}
    for part, recs in res:
        by[part] += recs
    for p in by:
        by[p].sort(key=lambda r: (r["model"], r["variant"], r["night"]))
    return by


def gate_g5(main: Sequence[Mapping], perturbed: Sequence[Mapping], models_: Sequence[str]) -> dict:
    """G5 (fatal), decodes: for every C1 and C2 fold bundle every Walch EVAL session decodes identically (confusion,
    estimated, warnings) as cut, under ``noctx`` and under ``app+15steps``, both variants."""
    orig = {(r["model"], r["variant"], r["night"]): r for r in main
            if r["kind"] == "eval" and r["dataset"] == report.HEADLINE_DATASET}
    per: dict = {}
    for r in perturbed:
        if r["model"] not in models_ or r.get("perturbation") not in G5_PERTURBATIONS:
            continue
        if r["dataset"] != report.HEADLINE_DATASET:
            continue
        k = f"{r['model']}|{r['variant']}|{r['perturbation']}"
        q = per.setdefault(k, {"sessions": 0, "identical": 0, "mismatches": []})
        q["sessions"] += 1
        if same_decode(orig.get((r["model"], r["variant"], r["original"])), r):
            q["identical"] += 1
        elif len(q["mismatches"]) < 10:
            q["mismatches"].append(r["night"])
    want = Counter((r["model"], r["variant"]) for r in orig.values() if r["model"] in models_)
    ok = all(per.get(f"{m}|{v}|{p}", {}).get("identical") == per.get(f"{m}|{v}|{p}", {}).get("sessions") == want[(m, v)]
             for (m, v) in want for p in G5_PERTURBATIONS) and bool(want)
    return {"decodes": per, "expected": {f"{m}|{v}": n for (m, v), n in sorted(want.items())}, "passed": bool(ok)}


def gate_g5_features(inp: Inputs, cli: AlgoCli, sessions: Sessions, out: Path) -> dict:
    """G5 (fatal), features: the algo-cli features (candidate parameters) of the ``noctx`` copy of every Walch EVAL
    session equal those of the cut session (every row, every field but the id)."""
    work = out / "g5"
    pf = write_json(work / "candidate-params.json", inp.feature_params())
    res = {}
    for v in inp.variants:
        ss, _ = sessions.of(v, ["eval"], where=lambda m: m["dataset"] == report.HEADLINE_DATASET)
        copies = [perturb.perturbed_copy(s, "noctx")[0] for s in ss]
        nf = write_sessions(work / f"{v}.jsonl", ss + copies)
        csv_path = cli.features(nf, work / f"{v}.csv", params=pf)
        _, rows = napmodel._csv_rows_by_id(csv_path, lambda nid: True)
        diff = [s["id"] for s in ss
                if [x.split(",", 1)[1] for x in rows.get(s["id"], [])]
                != [x.split(",", 1)[1] for x in rows.get(perturb.perturbed_id(s["id"], "noctx"), [])]
                or not rows.get(s["id"])]
        res[v] = {"sessions": len(ss), "identical": len(ss) - len(diff), "differing": diff[:10]}
        for f in (nf, csv_path):
            f.unlink(missing_ok=True)
    return {"variants": res, "passed": all(q["identical"] == q["sessions"] > 0 for q in res.values())}


def gate_g6(main: Sequence[Mapping], nights: Mapping[tuple[str, str], Mapping], models_: Sequence[str]) -> dict:
    """G6 (fatal): every model's evaluation includes the held-out subjects' whole nights, which reproduce candidate B
    night by night (confusion, estimated, warnings; no adjustment)."""
    res = {}
    for m in models_:
        rs = [r for r in main if r["model"] == m and r["kind"] == "night"]
        same = [r for r in rs if napmodel.same_as(nights.get((r["variant"], r["night"])), r,
                                                  int(r["endMs"]) - int(r["startMs"]), 0.0)]
        res[m] = {"nights": len(rs), "identical": len(same),
                  "mismatches": sorted({r["night"] for r in rs} - {r["night"] for r in same})[:10]}
    ok = all(q["nights"] == q["identical"] == len(nights) > 0 for q in res.values())
    return {"models": res, "storedNights": len(nights), "passed": bool(ok)}


# ================================================================================================== metrics ====

EPOCH_COLUMNS = ("grid_index", "night_id", "epoch", "has_data", "label", "stage", "p_awake", "p_light", "p_deep",
                 "p_rem")
P_COLUMNS = ("p_awake", "p_light", "p_deep", "p_rem")


def read_epochs(paths: Iterable[Path], sessions: set[str]) -> pd.DataFrame:
    """The rows of ``algo-cli evaluate --per-epoch`` CSVs (grid index 0) of ``sessions`` (numbers parsed exactly as
    Python's ``float``); a duplicate (session, epoch) is an error."""
    frames = []
    for p in paths:
        df = pd.read_csv(p, usecols=list(EPOCH_COLUMNS), dtype={"night_id": str, "label": str, "stage": str},
                         keep_default_na=False, float_precision="round_trip")
        frames.append(df[(df["grid_index"] == 0) & df["night_id"].isin(sessions)])
    df = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=list(EPOCH_COLUMNS))
    if df.duplicated(["night_id", "epoch"]).any():
        raise NapsRealisticError("duplicate per-epoch rows (session, epoch)")
    return df


def matched_epochs(fx: pd.DataFrame, fr: pd.DataFrame, meta: Mapping[str, Mapping]) -> realism.MatchedEpochs:
    """``realism.match_epochs`` on data frames (:func:`read_epochs`) of the compared model and the reference: the epochs
    of the sessions in ``meta`` with heart-rate data and a label under both models (rows matched by session and epoch;
    excluded epochs counted by reason; differing labels are an error)."""
    keep = set(meta)
    m = fx[fx["night_id"].isin(keep)].merge(fr[fr["night_id"].isin(keep)], on=["night_id", "epoch"], how="outer",
                                            suffixes=("_x", "_r"), indicator=True)
    excl: Counter = Counter()
    miss = m["_merge"] != "both"
    excl["missingInOneModel"] = int(miss.sum())
    b = m[~miss]
    nodata = ~((b["has_data_x"].astype(int) == 1) & (b["has_data_r"].astype(int) == 1))
    excl["noData"] = int(nodata.sum())
    b = b[~nodata]
    nolab = (b["label_x"] == "") | (b["label_r"] == "")
    excl["noLabel"] = int(nolab.sum())
    b = b[~nolab]
    if (b["label_x"] != b["label_r"]).any():
        raise ValueError("per-epoch labels differ between the compared models")
    b = b.sort_values(["night_id", "epoch"], kind="mergesort")
    sess = b["night_id"].to_numpy(dtype=object)
    groups_of = {sid: set(realism.auc_groups_of(q)) for sid, q in meta.items()}
    return realism.MatchedEpochs(
        session=sess, subject=np.array([str(meta[x]["subject"]) for x in sess], dtype=object),
        length=np.array([float(meta[x]["L"]) for x in sess], float),
        groups={q: np.array([q in groups_of[x] for x in sess], bool) for q in realism.AUC_GROUPS},
        label=np.array([realism.STAGE_INDEX[x] for x in b["label_x"]], int),
        p_x=b[[f"{c}_x" for c in P_COLUMNS]].to_numpy(float).reshape(-1, N_STAGES),
        p_ref=b[[f"{c}_r" for c in P_COLUMNS]].to_numpy(float).reshape(-1, N_STAGES),
        excluded={k: v for k, v in excl.items() if v})


def per_epoch_paths(out: Path, model: str, variant: str) -> list[Path]:
    return sorted((out / "per-epoch" / model / variant).glob("*.csv"))


def cell_parts(r: Mapping) -> list[str]:
    """The report cells of a record: EVAL, its pool, regime, pool x length and regime x length (MATCHED lengths);
    ANCHOR; MIDANCHOR (and per length); TILE; LATESTART (and per delay x length); the EARLY / OTHER / LATE regimes."""
    k = r.get("kind")
    if k == "eval":
        out = ["EVAL", f"pool:{r['pool']}", f"regime:{r['regime']}"]
        if r["pool"] != "W":
            out += ["ANCHOR", f"{r['pool']}-{int(r['L'])}"]
            if r["regime"] in ("EARLY", "LATE"):
                out.append(f"{r['regime']}-{int(r['L'])}")
        return out
    if k == "midanchor":
        return ["MIDANCHOR", f"MIDANCHOR-{int(r['L'])}"]
    if k == "tile":
        return ["TILE"]
    if k == "latestart":
        return ["LATESTART", f"LATESTART-{int(r['delta'])}x{int(r['L'])}"]
    return []


def share_counts(recs: Sequence[Mapping]) -> dict:
    """Shares of sessions with predicted (and true) DEEP >= 50 %, REM = 0 and DEEP = 0 of the window."""
    rs = [r for r in recs if realism.scored_chunks(r) > 0]
    out = {"sessions": len(rs)}
    for name, axis in (("pred", 0), ("true", 1)):
        sh = [np.asarray(r["confusion"], float).sum(axis=axis) / realism.scored_chunks(r) for r in rs]
        out[name] = {"deepHalf": float(np.mean([x[DEEP] >= 0.5 for x in sh])) if sh else float("nan"),
                     "remZero": float(np.mean([x[REM] == 0 for x in sh])) if sh else float("nan"),
                     "deepZero": float(np.mean([x[DEEP] == 0 for x in sh])) if sh else float("nan")}
    return out


def cells_of(recs: Sequence[Mapping], name_key: str = "model") -> dict:
    """cells[name][dataset][variant][part] (``napmodel.cell`` plus :func:`share_counts`)."""
    groups: dict[tuple, list] = defaultdict(list)
    for r in recs:
        for part in cell_parts(r):
            groups[(r[name_key], r["dataset"], r["variant"], part)].append(r)
    out: dict = {}
    for (n, d, v, part), rs in sorted(groups.items()):
        out.setdefault(n, {}).setdefault(d, {}).setdefault(v, {})[part] = {**napmodel.cell(rs), **{
            "shareCounts": share_counts(rs)}}
    return out


def strip_subjects(obj):
    """A copy without the per-subject maps (kept in memory for the paired bootstraps, not in summary.json)."""
    if isinstance(obj, Mapping):
        return {k: strip_subjects(v) for k, v in obj.items() if not str(k).startswith("subject")}
    if isinstance(obj, list):
        return [strip_subjects(v) for v in obj]
    return obj


COMPARE_PARTS: tuple[str, ...] = ("EVAL", "ANCHOR", "pool:O", "pool:W", "pool:M", "pool:R", "regime:EARLY",
                                  "regime:LATE", "regime:OTHER", "MIDANCHOR")


def comparisons(cells: Mapping, pairs: Sequence[tuple[str, str]], variants: Sequence[str] = VARIANTS) -> list[dict]:
    """Section 7.6: paired per-subject bootstrap (10 000 resamples, seed 0) of a - b per dataset, variant and pool on
    E-cap: accuracy, E, 4-class kappa, sleep / wake kappa, wake F1."""
    out = []
    for ds_ in DATASETS:
        for a, b in pairs:
            for v in variants:
                ca, cb = cells.get(a, {}).get(ds_, {}).get(v), cells.get(b, {}).get(ds_, {}).get(v)
                if not ca or not cb:
                    continue
                for part in COMPARE_PARTS:
                    if part in ca and part in cb:
                        out.append({"a": a, "b": b, "dataset": ds_, "variant": v, "part": part,
                                    **{m: report.paired_bootstrap(ca[part][k], cb[part][k])
                                       for m, k in napmodel.METRIC_KEYS.items()}})
    return out


def lit_items(recs: Iterable[Mapping]) -> list[dict]:
    return [{"id": r["night"], "subject": r["subject"], "L": float(r["L"]), "counts": r["labelCounts"]} for r in recs]


def sensitivity(lit: Mapping) -> dict:
    """Section 5.1 / 7.5 (reported): LitDist of the same weighted shares against the target table without the 30-min
    row and against the no-margin mapping TST = (1 - a) L."""
    out = {}
    for name, f in (("no30Row", lambda L: litprior.target(L, litprior.ROWS_NO30)),
                    ("noMargin", lambda L: litprior.target(L, margins=0.0))):
        ds_ = []
        for L, q in lit["perLength"].items():
            t = f(float(L))
            ds_.append(abs(_num(q["deepPctSleep"]) - t.deep) + abs(_num(q["remPctSleep"]) - t.rem))
        out[name] = float(np.mean(ds_)) if ds_ else float("nan")
    return out


def _num(x) -> float:
    return float("nan") if x is None else float(x)


def model_metrics(e_eval: Sequence[Mapping], e_mid: Sequence[Mapping], featureless: Sequence[Mapping],
                  lit_w: Mapping, lit_mid_w: Mapping, dataset: str) -> dict:
    """The metrics of one model, dataset and variant (section 7): ``e_eval`` / ``e_mid`` its EVAL / MIDANCHOR records in
    E-cap, ``featureless`` its featureless decodes (Walch fold bundles), the LIT weights of the variant."""
    conf = {r["night"]: np.asarray(r["confusion"], float) for r in (*e_eval, *e_mid)}
    subj = {r["night"]: str(r["subject"]) for r in (*e_eval, *e_mid)}
    out = {"contrast": realism.regime_contrast(e_eval),
           "contrastWindow": realism.regime_contrast(e_eval, kind="window", n_boot=0),
           "contrastAll": realism.regime_contrast(e_eval, both_regimes=False, n_boot=0),
           "swKappa": realism.sleep_wake_kappa(e_eval), "tracking": realism.tracking(e_eval),
           "trackingSleep": realism.tracking(e_eval, share="sleep"), "priorMatched": realism.prior_matched(e_eval)}
    if dataset == report.HEADLINE_DATASET:
        lit = realism.lit_metrics(conf, lit_w, subj) if lit_w else None
        lit_mid = realism.lit_metrics(conf, lit_mid_w, subj) if lit_mid_w else None
        fl = realism.featureless_check(featureless) if featureless else {}
        out.update({"lateTail": realism.late_tail(e_eval), "lit": lit, "litMid": lit_mid, "featureless": fl,
                    "sensitivity": sensitivity(lit) if lit else None,
                    "defaultScope": realism.default_scope(conf, lit_w, subj, fl) if lit_w and fl else None})
    else:
        out.update({"lateTail": realism.late_tail(e_eval),
                    "lit": realism.lit_metrics(conf, lit_w, subj) if lit_w else None})
    return out


def auc_tables(out: Path, models_: Sequence[str], ecap: Mapping[str, Mapping[str, dict]], dataset: str,
               variants: Sequence[str] = VARIANTS) -> dict:
    """Section 7.2: per candidate and variant, ``realism.auc_summary`` against N1 on the epochs of the E-cap EVAL
    sessions of ``dataset`` present in both models (``ecap[v]`` = session id -> meta)."""
    res: dict = {}
    for v in variants:
        meta = ecap[v]
        ref = read_epochs(per_epoch_paths(out, REFERENCE, v), set(meta))
        for x in models_:
            if x == REFERENCE:
                continue
            fx = read_epochs(per_epoch_paths(out, x, v), set(meta))
            res.setdefault(x, {})[v] = realism.auc_summary(matched_epochs(fx, ref, meta))
    return res


def onset_bin_table(out: Path, models_: Sequence[str], ecap: Mapping[str, Mapping[str, dict]],
                    variants: Sequence[str] = VARIANTS) -> dict:
    """Section 7.5 (reported): pooled predicted vs true shares per 10-min bin of the Walch O-60 sessions of E-cap."""
    res: dict = {}
    for v in variants:
        ids = {i for i, m in ecap[v].items() if m["pool"] == "O" and float(m["L"]) == 60.0}
        for x in models_:
            rows: dict = {}
            for p in per_epoch_paths(out, x, v):
                rows.update(realism.read_per_epoch(p, sessions=ids))
            res.setdefault(x, {})[v] = realism.onset_bins(rows, ids)
    return res


def summarize(records: Sequence[Mapping], out: Path, selections: Sequence[Mapping], folds: Sequence[Mapping],
              meta: Mapping, gates: Mapping, variants: Sequence[str] = VARIANTS) -> dict:
    """Every metric of section 7, rule (i) with both tiers, rule (ii) for N1 and the adopted candidate, the generated
    reasons and the reported tables, from the stored records of ``results.jsonl`` (no algo-cli; the AUC from the
    per-epoch CSVs)."""
    perturbed = [r for r in records if r["kind"] == "perturbed"]
    featureless = [r for r in records if r["kind"] == "featureless"]
    results = [r for r in records if r["kind"] not in ("perturbed", "featureless")]
    dropped = dict(meta.get("dropped") or {})
    models_ = [m for m in MODELS if any(r["model"] == m for r in results) and m not in dropped]
    by_model: dict[str, list[Mapping]] = defaultdict(list)
    for r in results:
        by_model[r["model"]].append(r)
    common, members = {}, {}
    for kind in ("eval", "midanchor", "tile", "latestart"):
        cs = realism.common_set({m: [r for r in by_model[m] if r["kind"] == kind] for m in models_})
        members[kind] = cs["members"]
        common[kind] = {k: cs[k] for k in ("sessions", "common", "excluded")}

    def ecap(m: str, kind: str, v: str | None = None, dataset: str | None = None) -> list[Mapping]:
        return [r for r in realism.in_common(by_model[m], members[kind]) if r["kind"] == kind
                and (v is None or r["variant"] == v) and (dataset is None or r["dataset"] == dataset)]

    lit_w: dict = {}
    lit_mid_w: dict = {}
    for d in DATASETS:
        for v in variants:
            ref = ecap(REFERENCE, "eval", v, d)
            lit_w.setdefault(d, {})[v] = realism.lit_weight_table(lit_items(r for r in ref if pools.is_anchor(r)),
                                                                  ANCHORS)
            lit_mid_w.setdefault(d, {})[v] = realism.lit_weight_table(lit_items(ecap(REFERENCE, "midanchor", v, d)),
                                                                      MID_ANCHORS)
    write_lit(out, lit_w, lit_mid_w)
    fl_by = defaultdict(list)
    for r in featureless:
        fl_by[(r["model"], r["variant"])].append(r)
    metrics: dict = {}
    for m in models_:
        for d in DATASETS:
            for v in variants:
                metrics.setdefault(m, {}).setdefault(d, {})[v] = model_metrics(
                    ecap(m, "eval", v, d), ecap(m, "midanchor", v, d), fl_by.get((m, v), []), lit_w[d][v],
                    lit_mid_w[d][v], d)
    walch_ecap = {v: {r["night"]: r for r in ecap(REFERENCE, "eval", v, report.HEADLINE_DATASET)} for v in variants}
    bid_ecap = {v: {r["night"]: r for r in ecap(REFERENCE, "eval", v, "bidsleep")} for v in variants}
    auc = auc_tables(out, models_, walch_ecap, report.HEADLINE_DATASET, variants)
    auc_bid = auc_tables(out, models_, bid_ecap, "bidsleep", variants)
    cands = [c for c in CANDIDATES if c in models_ or c in dropped]
    rule = realism.rule_i(metrics, auc, cands, dropped, variants)
    rule_ii = {REFERENCE: realism.rule_ii(
        REFERENCE, rule["reference"]["F"]["pass"], rule["reference"]["R"]["pass"],
        {v: metrics[REFERENCE]["walch"][v]["lit"] for v in variants},
        {v: metrics[REFERENCE]["walch"][v]["swKappa"]["kappa"] for v in variants}, variants)}
    x = rule["adopted"]
    if x is not None:
        rx = rule["candidates"][x]
        rule_ii[x] = realism.rule_ii(x, rx["F"]["pass"], rx["R"]["pass"], {v: metrics[x]["walch"][v]["lit"]
                                                                          for v in variants},
                                     {v: metrics[x]["walch"][v]["swKappa"]["kappa"] for v in variants}, variants)
    # reported: classical cells on E-cap (and RULES / HMM-table on their own sets), comparisons, LIT comparisons
    ecap_all = [r for m in models_ for k in ("eval", "midanchor", "tile", "latestart") for r in ecap(m, k)]
    ctx = [r for r in results if r["model"] in CONTEXT_ESTIMATORS]
    ctx_ecap = realism.in_common(ctx, members["eval"])
    light = [dict(r, model=LIGHT_KIND) for r in napmodel.all_light([r for r in ecap_all
                                                                     if r["model"] == REFERENCE])]
    cells = cells_of(ecap_all + ctx_ecap + light)
    cells_own = cells_of([r for r in results if r["model"] in (*models_, *CONTEXT_ESTIMATORS)])
    pairs = [(c, REFERENCE) for c in models_ if c != REFERENCE]
    if "C1" in models_ and "C2" in models_:
        pairs.append(("C2", "C1"))
    pairs += [(e, REFERENCE) for e in CONTEXT_ESTIMATORS] + [(m, LIGHT_KIND) for m in models_]
    lit_cmp = []
    for c in models_:
        if c == REFERENCE:
            continue
        for v in variants:
            a, b = metrics[c]["walch"][v]["lit"], metrics[REFERENCE]["walch"][v]["lit"]
            if a and b:
                lit_cmp.append({"a": c, "b": REFERENCE, "variant": v,
                                "accuracy": report.paired_bootstrap(a["subjectAccuracy"], b["subjectAccuracy"]),
                                "kappa": report.paired_bootstrap(a["subjectKappa"], b["subjectKappa"])})
    margin_1312 = {}
    for m in models_:
        per = {}
        for v in variants:
            rs = [r for r in ecap(m, "eval", v, report.HEADLINE_DATASET) if r["pool"] in ("O", "W")]
            c = napmodel.cell(rs)
            per[v] = c["accuracy"] - c["lightAccuracy"]
        margin_1312[m] = {"perVariant": per, "worse": min(per.values()) if per else float("nan")}
    reported: dict = {"margin1312": margin_1312, "onsetBins": onset_bin_table(out, models_, walch_ecap, variants),
                      "clock": {}, "perturbations": {}, "lateStart": {}}
    for m in models_:
        for v in variants:
            reported["clock"].setdefault(m, {})[v] = realism.clock_table(ecap(m, "eval", v, "bidsleep"))
            ls = [r for r in by_model[m] if r["kind"] == "latestart" and r["variant"] == v]
            on = [r for r in by_model[m] if r["kind"] == "eval" and r["pool"] == "O" and r["variant"] == v]
            reported["lateStart"].setdefault(m, {})[v] = realism.late_start_effects(on, ls)
            for d in DATASETS:
                orig = [r for r in by_model[m] if r["kind"] == "eval" and r["variant"] == v and r["dataset"] == d]
                pert = [r for r in perturbed if r["model"] == m and r["variant"] == v and r["dataset"] == d]
                reported["perturbations"].setdefault(m, {}).setdefault(d, {})[v] = {
                    "window": realism.perturbation_effects(orig, pert),
                    "sleep": realism.perturbation_effects(orig, pert, share="sleep")}
    decision = {k: rule[k] for k in ("adopted", "tier", "decision", "qualifying", "tied", "tier1", "tier2")}
    groups = [{k: s.get(k) for k in ("variant", "key", "chosen", "margin", "C", "logLoss", "bestPerClassifier",
                                     "tiles", "anchorSessions")}
              | {"c1": {k: s["c1"][k] for k in ("bias", "D", "decodes", "trueShares", "predShares")},
                 "c2": {k: s["c2"][k] for k in ("table", "anchors", "decodes", "maxD", "sessions")},
                 "c0": None if not s.get("c0") else {k: s["c0"][k] for k in ("table", "anchors", "decodes", "maxD",
                                                                              "sessions")}}
              for s in selections]
    return {"meta": dict(meta), "gates": dict(gates), "commonSet": common,
            "litWeights": {d: {v: {str(L): lw.as_dict() for L, lw in t.items()} for v, t in q.items()}
                           for d, q in lit_w.items()},
            "litMidWeights": {d: {v: {str(L): lw.as_dict() for L, lw in t.items()} for v, t in q.items()}
                              for d, q in lit_mid_w.items()},
            "metrics": metrics, "auc": auc, "aucBidsleep": auc_bid, "ruleI": rule, "decision": decision,
            "ruleII": {"N1": rule_ii[REFERENCE], "X": rule_ii.get(x) if x else None},
            "reasons": realism.failure_reasons(rule), "cells": strip_subjects(cells),
            "cellsOwnSets": strip_subjects(cells_own),
            "comparisons": comparisons(cells, pairs, variants), "litComparisons": lit_cmp, "reported": reported,
            "groups": groups,
            "foldChoices": {"classifier": dict(Counter(f["classifier"] for f in folds)),
                            "theta": dict(Counter(canonical_json(f["hybrid"]) for f in folds))}}


# ================================================================================================= the runs ====

def _write_jsonl(path: Path, rows: Sequence[Mapping]) -> None:
    napmodel._write_jsonl(path, rows)


def _read_jsonl(path: Path) -> list[dict]:
    return napmodel._read_jsonl(path) if Path(path).is_file() else []


def _fatal(gates: dict, name: str, rec: Mapping) -> None:
    gates[name] = dict(rec)
    if not rec.get("passed"):
        raise GateFailure(f"{name} failed", gates)


def _init_n1_fits(inp: Inputs, cli: AlgoCli, jobs: int) -> tuple[napmodel.Data, dict]:
    global _SPEC
    data = n1_data(inp, cli)
    _FIT_DATA["n1"] = FitData(X=data.X, y=data.y, subject=data.subject,
                              dataset=np.array([inp.index.dataset_of(s) for s in data.subject.tolist()], dtype=object))
    _SPEC = inp.cfg.classifier_spec
    try:
        fits = fit_all(n1_inner_tasks(inp), jobs)
    finally:
        _FIT_DATA.pop("n1", None)
    return data, fits


def reproduction_gates(inp: Inputs, cli: AlgoCli, out: Path, jobs: int, created_at: str, gates: dict,
                       g0: Mapping) -> dict:
    """G1, G2, G3a (fatal) and G4 (C0 dropped on failure), in this order; returns N1's refitted inner models by
    (variant, group) when G4 passed."""
    t0 = time.monotonic()
    g1, _ = gate_g1(inp, cli, out, jobs)
    _fatal(gates, "G1", g1)
    _fatal(gates, "G2", gate_g2(inp, cli, out))
    _fatal(gates, "G3a", gate_g3a(inp, cli, out, jobs))
    data, fits = _init_n1_fits(inp, cli, jobs)
    g4, models = gate_g4(inp, cli, data, fits, out, jobs, created_at)
    gates["G4"] = g4
    check_build(cli, g0, "after the reproduction gates")
    gates["timingsSeconds"] = {"reproductionGates": time.monotonic() - t0}
    return models if g4["passed"] else {}


def run_gates(inp: Inputs, cli: AlgoCli, out: Path, jobs: int = 4, created_at: str | None = None,
              log_path: Path | None = None) -> dict:
    """``naps-realistic-gates``: G0, G1, G2, G3a and G4 (reproduction only: no candidate is fitted); writes
    ``gates.json``. Raises :class:`GateFailure` on a fatal gate (``gates.json`` written)."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    created_at = created_at or inp.cfg.created_at
    gates: dict = {}
    try:
        gates["G0"] = g0 = gate_g0(cli, log_path)
        reproduction_gates(inp, cli, out, jobs, created_at, gates, g0)
        gates["passed"] = True
    except GateFailure as e:
        gates.update(e.gates)
        gates["passed"] = False
        write_json(out / "gates.json", naps._clean(gates))
        raise
    write_json(out / "gates.json", naps._clean(gates))
    return gates


def g3b_record(sessions: Sessions, out: Path, variants: Sequence[str], selections: Sequence[Mapping],
               fits: Mapping[FitKey, TrainedClassifier], evidence: Mapping[tuple[str, str], Mapping]) -> dict:
    """G3b (fatal): the tile, EVAL and MIDANCHOR session files are the decoded sessions byte for byte, every session
    file has unique ids, every exported classifier reproduces scikit-learn to 1e-9, every exported bundle has its
    masked columns masked, and the copied statistics of every inner fold are equal among its Walch subjects."""
    files = {}
    for v in variants:
        for kind in ("tile", "eval", "midanchor"):
            try:
                napmodel.check_sessions_file(out / "sessions" / v / f"{kind}.jsonl",
                                             [s for s, _ in sessions.by[v][kind]])
                files[f"{v}/{kind}"] = True
            except napmodel.NapModelError:
                files[f"{v}/{kind}"] = False
    diffs = [tc.info.get("export_max_abs_diff") for tc in fits.values()]
    export = max((float(x) for x in diffs if x is not None), default=0.0)
    masked = [f"{k}: {p}" for k, b in evidence.items() for p in masked_problems(b, MASKED_FEATURES)]
    masked += [p for s in selections for p in s.get("maskedProblems", [])]
    stats = [c for s in selections for c in s["statistics"] if not c["equal"]]
    ok = (all(files.values()) and export <= 1e-9 and all(d is not None for d in diffs) and not masked and not stats)
    return {"sessionFiles": files, "uniqueIds": True, "exportMaxAbsDiff": export, "fits": len(fits),
            "maskedProblems": masked[:10], "statisticsUnequal": stats[:10], "passed": bool(ok)}


def experiment_preflight(log_path: Path | None, seeds: int | None = None, external_void: str | None = None) -> dict:
    """The checks of ``naps-realistic`` that must pass before its START line is written (that line is "the first START
    of train.py naps-realistic" of the test-set freeze, section 13.6, so a refused run must leave no trace in the log):

    - the featureless seeds (section 18): the dry run's logged decision (:func:`logged_seeds`); an explicit ``seeds``
      must equal it; without a logged decision ``seeds`` or 0-19;
    - the frozen test set (section 13.6): ``ds006576.freeze_order(complete=False)`` passes with exactly one
      ``NOTE ds006576 manifest sha256`` and one ``NOTE ds006576 yasa-summary sha256`` line, i.e. the extraction is
      frozen before this run starts. ``external_void`` (a reason) runs the experiment without them: the external test
      of section 13 is then void (section 13.8), and the caller logs the reason before the START line.

    Raises :class:`NapsRealisticError`; returns ``{"seeds", "dryRunDecision", "freeze", "externalVoid"}``."""
    logged = logged_seeds(log_path)
    if seeds is not None and seeds not in FEATURELESS_SEED_CHOICES:
        raise NapsRealisticError(f"--seeds must be one of {FEATURELESS_SEED_CHOICES} (section 18)")
    if seeds is not None and logged is not None and logged != seeds:
        raise NapsRealisticError(f"--seeds {seeds} differs from the featureless dry run's decision ({logged}) in the "
                                 f"commands log (section 18)")
    chosen = seeds if seeds is not None else (logged if logged is not None else FEATURELESS_SEED_CHOICES[0])
    lines = Path(log_path).read_text(encoding="utf-8").splitlines() if log_path and Path(log_path).is_file() else []
    freeze = ds.freeze_order(lines, complete=False)
    man, summ = ds.logged_digests(lines)
    frozen = freeze["ok"] and len(man) == 1 and len(summ) == 1
    if not frozen and not external_void:
        raise NapsRealisticError(
            "refused: the ds006576 test set is not frozen in the commands log (exactly one manifest and one "
            f"yasa-summary NOTE after the extraction, section 13.6; found {len(man)} / {len(summ)}"
            + (f"; {'; '.join(freeze['failures'])}" if freeze["failures"] else "") + "). Run the extraction first, "
            "or pass --external-void <reason> to run with a void external test")
    return {"seeds": int(chosen), "dryRunDecision": logged, "freeze": freeze, "frozen": bool(frozen),
            "externalVoid": None if frozen else external_void}


def external_void_note(reason: str) -> str:
    return (f"naps-realistic started without a frozen ds006576 test set (no manifest / yasa-summary NOTE pair before "
            f"its START, section 13.6): the external test of section 13 is void; reason: {reason}")


def run_experiment(inp: Inputs, cli: AlgoCli, out: Path, jobs: int = 4, seeds: int | None = None,
                   created_at: str | None = None, log_path: Path | None = None,
                   external_void: str | None = None) -> dict:
    """``naps-realistic``: the whole pre-registered experiment (sections 4-12). Writes ``gates.json``, ``sessions/``
    (with ``featureless.json``: the generator, its parameters and the digest of every featureless file decoded),
    ``features/``, ``lit/`` (targets, the evaluation and inner LIT weights), ``inner/``, ``selections.json``,
    ``outer/``, ``folds.json``, ``per-epoch/``, ``results.jsonl``
    (every decoded session: EVAL, MIDANCHOR, tiles, LATESTART, perturbed copies, featureless sessions and whole nights
    of every model; RULES and HMM-table on EVAL; ``kind`` tells them apart), ``common-set.json``, ``summary.json``
    (rules (i) and (ii)) and ``report.md``. Raises :class:`GateFailure` on a fatal gate (``gates.json`` written)."""
    global _SPEC
    pre = experiment_preflight(log_path, seeds, external_void)
    seeds = pre["seeds"]
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    created_at = created_at or inp.cfg.created_at
    timings: dict[str, float] = {}
    gates: dict = {}
    t_start = time.monotonic()
    try:
        gates["G0"] = g0 = gate_g0(cli, log_path)
        g0["featurelessSeeds"] = {"run": seeds, "dryRunDecision": pre["dryRunDecision"]}
        g0["ds006576FrozenBefore"] = dict(pre["freeze"], frozen=pre["frozen"], externalVoid=pre["externalVoid"])
        gates["G8"] = {"note": "single-threaded BLAS in every fit (threadpool_limits(1)), fixed seeds (MLP seed 0, "
                               "bootstrap seed 0); --jobs changes the scheduling only", "jobs": jobs}
        t0 = time.monotonic()
        sessions = build_sessions(inp)
        for v in inp.variants:
            for kind in ("tile", "eval", "midanchor", "latestart", "perturbed"):
                write_sessions(out / "sessions" / v / f"{kind}.jsonl", [s for s, _ in sessions.by[v][kind]])
        write_json(out / "sessions" / "counts.json", naps._clean(sessions.counts))
        gates["G7"] = {"counts": {v: sessions.counts[v] for v in inp.variants}, "diffs": sessions.counts["diffs"],
                       "identical": all(not d for d in sessions.counts["diffs"].values())}
        timings["sessions"] = time.monotonic() - t0
        c0_models = reproduction_gates(inp, cli, out, jobs, created_at, gates, g0)
        dropped = {} if gates["G4"]["passed"] else {"C0": "gate G4 failed: N1's inner models were not reproduced"}
        t0 = time.monotonic()
        tile_features(inp, cli, sessions, out, jobs)
        td = load_tile_data(inp, cli, sessions, out)
        timings["tileFeatures"] = time.monotonic() - t0
        _FIT_DATA["tiles"] = FitData(X=td.X, y=td.y, subject=td.subject, dataset=td.dataset, masked=td.masked,
                                     weighted=True)
        _SPEC = inp.cfg.classifier_spec
        t0 = time.monotonic()
        fits = fit_all(inner_tasks(inp, inp.cfg.lr_Cs), jobs)
        timings["innerFits"] = time.monotonic() - t0
        t0 = time.monotonic()
        sel_list = loso.map_threads(lambda g: select_group(
            inp, td, sessions, cli, g, fits, None if dropped else c0_models[(g.variant, g.key)], out, created_at),
            inp.groups, jobs)
        sel = {(s["variant"], s["key"]): s for s in sel_list}
        write_json(out / "selections.json", naps._clean({"selections": sel_list}))
        timings["innerSelection"] = time.monotonic() - t0
        check_build(cli, g0, "after the inner selection")
        t0 = time.monotonic()
        fits.update(fit_all(outer_tasks(inp, sel), jobs))
        _FIT_DATA.pop("tiles", None)
        evidence, metas = outer_models(inp, td, sessions, sel, fits, out, created_at)
        write_json(out / "folds.json", naps._clean({"folds": metas, "dropped": dropped}))
        timings["outerFits"] = time.monotonic() - t0
        _fatal(gates, "G3b", g3b_record(sessions, out, inp.variants, sel_list, fits, evidence))
        models_ = [m for m in MODELS if m not in dropped]
        t0 = time.monotonic()
        recs = evaluate_models(inp, cli, sessions, sel, evidence, models_, seeds, out, jobs)
        timings["outerEvaluation"] = time.monotonic() - t0
        _fatal(gates, "G6", gate_g6(recs["main"], napmodel.stored_nights(inp.nm), models_))
        g5 = gate_g5(recs["main"], recs["perturbed"], [m for m in EVIDENCE_MODELS if m in models_])
        g5["features"] = gate_g5_features(inp, cli, sessions, out)
        g5["passed"] = g5["passed"] and g5["features"]["passed"]
        _fatal(gates, "G5", g5)
        check_build(cli, g0, "at the end of the decodes")
    except GateFailure as e:
        gates.update(e.gates)
        gates["passed"] = False
        write_json(out / "gates.json", naps._clean(gates))
        raise
    finally:
        _FIT_DATA.clear()
    gates["passed"] = True
    write_json(out / "gates.json", naps._clean(gates))
    results = recs["main"] + recs["midanchor"] + recs["context"] + recs["perturbed"] + recs["featureless"]
    _write_jsonl(out / "results.jsonl", results)
    timings["total"] = time.monotonic() - t_start
    meta = {"run": out.name, "baseRun": str(inp.run_dir), "napModelRun": str(inp.nap_model_dir),
            "nightClassifier": inp.nm.base.clf, "config": inp.nm.base.config, "algoCli": g0["algoCli"],
            "code": g0["code"], "prereg": PREREG_PATH, "preregSha256": PREREG_SHA256, "createdAt": created_at,
            "featurelessSeeds": seeds, "ds006576Frozen": pre["frozen"], "externalVoid": pre["externalVoid"],
            "maskedFeatures": list(MASKED_FEATURES), "featureSetVersion": td.fsv,
            "features": len(td.names), "trainingRows": int(len(td.y)), "dropped": dropped, "timingsSeconds": timings,
            "models": models_}
    summary = summarize(results, out, sel_list, metas, meta, gates)
    write_json(out / "common-set.json", naps._clean(summary["commonSet"]))
    write_json(out / "summary.json", naps._clean(summary))
    (out / "report.md").write_text(render(summary), encoding="utf-8")
    return summary


def rerender(out: Path) -> dict:
    """``naps-realistic-report``: ``summary.json`` and ``report.md`` recomputed from the stored records, selections,
    folds, gates and per-epoch CSVs (no algo-cli)."""
    out = Path(out)
    old = read_json(out / "summary.json")
    sel = read_json(out / "selections.json")["selections"]
    folds = read_json(out / "folds.json")["folds"]
    summary = summarize(_read_jsonl(out / "results.jsonl"), out, sel, folds, old["meta"], old["gates"])
    write_json(out / "common-set.json", naps._clean(summary["commonSet"]))
    write_json(out / "summary.json", naps._clean(summary))
    (out / "report.md").write_text(render(summary), encoding="utf-8")
    return summary


def result_line(summary: Mapping) -> str:
    """The RESULT text of a run: decision, tier, both rule-(ii) policies, the gates, the generated reasons."""
    d, r2 = summary["decision"], summary["ruleII"]
    x = d.get("adopted")
    parts = [f"rule (i): {d['decision']}" + (f" (tier {d['tier']})" if x else ""),
             f"rule (ii): N1 {r2['N1']['policy']}" + (f", {x} {r2['X']['policy']}" if x and r2.get("X") else ""),
             "gates G0-G8 passed" + (f"; C0 dropped ({summary['meta']['dropped']['C0']})"
                                     if summary["meta"].get("dropped") else "")]
    lit = {m: realism.lit_dist_worse(q["walch"]) for m, q in summary["metrics"].items()
           if all(q["walch"][v].get("lit") for v in VARIANTS)}
    parts.append("LitDist (worse variant) " + ", ".join(f"{m} {v:.2f}" for m, v in lit.items()))
    reasons = summary.get("reasons") or []
    if reasons:
        parts.append("reasons: " + " | ".join(reasons))
    return "; ".join(parts)


# ================================================================================================= markdown ====

_f = naps._f
_pct = naps._pct
_ci = naps._ci
_shares = naps._shares


def _crit(c: Mapping | None, nd: int = 3) -> str:
    if not c:
        return "—"
    return f"{_f(c['value'], nd)} {c['op']} {_f(c['threshold'], nd)} {'✓' if c['pass'] else '✗'}"


def _gate_line(name: str, g: Mapping | None) -> str:
    if not g:
        return f"| {name} | not reached | |"
    st = "passed" if g.get("passed") else ("failed" if "passed" in g else "recorded")
    return f"| {name} | {st} | {_gate_detail(name, g)} |"


def _gate_detail(name: str, g: Mapping) -> str:
    if name == "G0":
        return f"algo-cli {g['algoCli'].get('sha256', '')[:12]}…, code {g['code']['sha256'][:12]}…, prereg " \
               f"{str(g['prereg'].get('sha256'))[:12]}…"
    if name == "G1":
        return ", ".join(f"{v} {q['identical']}/{q['stored']}" for v, q in g.get("perVariant", {}).items()) + \
            f"; nights {g.get('nightsIdenticalToB')}/{g.get('storedNights')}"
    if name == "G2":
        return ", ".join(f"{v} {q['default']['identical']}/{q['nights']} (margin 0: {q['margin0']['identical']})"
                         for v, q in g.get("variants", {}).items()) + f"; fingerprints {g.get('fingerprints')}"
    if name == "G3a":
        return ", ".join(f"{p['variant']}/{p['params']} {p['nDiffering']} differing of {p['rowsCompared']} rows"
                         for p in g.get("parity", []))
    if name == "G4":
        return ", ".join(f"{q['variant'][:4]}/{q['key'][:6]} {'=' if q['passed'] else '≠'}"
                         for q in g.get("groups", []))
    if name == "G5":
        return f"{sum(q['identical'] for q in g.get('decodes', {}).values())} / " \
               f"{sum(q['sessions'] for q in g.get('decodes', {}).values())} decodes identical; features " \
               + ", ".join(f"{v} {q['identical']}/{q['sessions']}" for v, q in g.get("features", {}).get(
                   "variants", {}).items())
    if name == "G6":
        return ", ".join(f"{m} {q['identical']}/{q['nights']}" for m, q in g.get("models", {}).items())
    if name == "G7":
        return "identical to section 4.6" if g.get("identical") else f"differences: {g.get('diffs')}"
    if name == "G3b":
        return f"export max |diff| {_f(g.get('exportMaxAbsDiff'), 12)}, {g.get('fits')} fits"
    return ""


def render(s: Mapping) -> str:
    m = s["meta"]
    variants = list(VARIANTS)
    models_ = list(s["metrics"])
    L = [f"# Realistic nap estimates (pre-registered) — run `{m.get('run', '?')}`", "",
         f"Pre-registration: `{m['prereg']}` (SHA-256 `{m['preregSha256'][:12]}…`). Models: "
         + ", ".join(f"{x} ({MODEL_LABEL[x]})" for x in models_)
         + (f"; dropped: {m['dropped']}" if m.get("dropped") else "")
         + f". Featureless seeds 0–{m.get('featurelessSeeds', 20) - 1}. Walch is the headline (PSG), BIDSleep "
           "secondary; every gate and decision on the common set E∩ (section 7.1), worse sampling variant.", ""]
    L += ["## Gates", "", "| gate | outcome | detail |", "|---|---|---|"]
    for name in ("G0", "G1", "G2", "G3a", "G3b", "G4", "G5", "G6", "G7"):
        L.append(_gate_line(name, s["gates"].get(name)))
    L.append("")
    d = s["decision"]
    L += ["## Rule (i): the model", "",
          f"Tier 1 (D, F, R): {', '.join(d['tier1']) or 'empty'}; tier 2 (D, R, F0): "
          f"{', '.join(d['tier2']) or ('not evaluated' if d['tier1'] else 'empty')}. **Decision: {d['decision']}**"
          + (f" (tier {d['tier']}; tied within 1.0 pp: {', '.join(d['tied'])})" if d.get("adopted") else "") + ".", ""]
    if s.get("reasons"):
        L += ["Generated reasons (failing criteria):", ""] + [f"- {x}" for x in s["reasons"]] + [""]
    rule = s["ruleI"]
    L += ["| model | D | F | F0 | R | LitDist (worse) | first failing criteria |", "|---|---|---|---|---|---|---|"]
    ref = rule["reference"]
    L.append(f"| N1 (reference) | — | {'✓' if ref['F']['pass'] else '✗'} | — | {'✓' if ref['R']['pass'] else '✗'} | "
             f"{_f(ref['litDist'], 2)} | " + "; ".join(c["name"] for blk in ("F", "R") for c in ref[blk]["criteria"]
                                                      if not c["pass"])[:200] + " |")
    for c, q in rule["candidates"].items():
        bad = [x["name"] + (f"@{x['L']}" if x.get("L") else "") for blk in ("D", "F", "F0", "R")
               for x in q[blk]["criteria"] if not x["pass"]]
        L.append(f"| {c} | " + " | ".join("✓" if q[b]["pass"] else "✗" for b in ("D", "F", "F0", "R"))
                 + f" | {_f(q['litDist'], 2)} | {'; '.join(bad[:8])}{' …' if len(bad) > 8 else ''} |")
    L.append("")
    for c, q in rule["candidates"].items():
        L += [f"### {c}: criteria", "", "| criterion | value | threshold | pass |", "|---|---|---|---|"]
        for blk in ("D", "F", "F0"):
            for x in q[blk]["criteria"]:
                L.append(f"| {x['name']} | {_f(x['value'])} | {x['op']} {_f(x['threshold'])} | "
                         f"{'✓' if x['pass'] else '✗'} |")
        rr = q["R"]["criteria"]
        r1 = [x for x in rr if x["name"].startswith("R1")]
        L.append(f"| R1 (featureless, {len(r1)} criteria) | {sum(not x['pass'] for x in r1)} failing | all pass | "
                 f"{'✓' if all(x['pass'] for x in r1) else '✗'} |")
        for x in rr:
            if not x["name"].startswith("R1"):
                L.append(f"| {x['name']} | {_f(x['value'])} | {x['op']} {_f(x['threshold'])} | "
                         f"{'✓' if x['pass'] else '✗'} |")
        L.append("")
    r2 = s["ruleII"]
    L += ["## Rule (ii): the default output policy", "", "| model | F, R | LIT accuracy − all LIGHT (≥ 0.03) | "
          "LIT κ (≥ 0.15) | sleep / wake κ (≥ 0.20) | **policy** |", "|---|---|---|---|---|---|"]
    for key in ("N1", "X"):
        p = r2.get(key)
        if not p:
            continue
        sc = {c["name"]: c for c in p["stagesCriteria"]}
        L.append(f"| {p['model']} | {'✓' if sc['ii.F']['pass'] else '✗'} {'✓' if sc['ii.R']['pass'] else '✗'} | "
                 f"{_crit(sc['ii.litMargin'])} | {_crit(sc['ii.litKappa'])} | {_crit(p['sleepWakeCriterion'])} | "
                 f"**{p['policy']}** |")
    L.append("")
    L += ["## Per-epoch AUC (gate D; Walch, E∩, cells = subject × anchor length)", "",
          "| model | variant | ΔAUC ANCHOR AWAKE / DEEP / REM | ΔAUC EARLY DEEP | ΔAUC LATE REM | N1 AUC ANCHOR "
          "A / D / R | epochs |", "|---|---|---|---|---|---|---|"]
    for c, per in s["auc"].items():
        for v in variants:
            a = per.get(v)
            if not a:
                continue
            dl = a["delta"]
            L.append(f"| {c} | {v} | " + " / ".join(_f(dl["ANCHOR"][x]) for x in ("AWAKE", "DEEP", "REM"))
                     + f" | {_f(dl['EARLY']['DEEP'])} | {_f(dl['LATE']['REM'])} | "
                     + " / ".join(_f(a["REF"]["ANCHOR"][x]["auc"]) for x in ("AWAKE", "DEEP", "REM"))
                     + f" | {a['epochs']} |")
    L.append("")
    L += ["## Regime contrasts (gate F; shares of sleep, 60 + 90 min, S_F)", "",
          "| model | dataset | variant | subjects | Δ DEEP (95 % CI) | Δ REM (95 % CI) | r DEEP / r REM | true Δ "
          "DEEP / REM |", "|---|---|---|---|---|---|---|---|"]
    for x in models_:
        for d_ in DATASETS:
            for v in variants:
                c = s["metrics"][x][d_][v]["contrast"]
                L.append(f"| {x} | {d_} | {v} | {c['n']} | {_pct(c['deltaDeep'])} [{_pct(c['ciDeep'][0])}, "
                         f"{_pct(c['ciDeep'][1])}] | {_pct(c['deltaRem'])} [{_pct(c['ciRem'][0])}, "
                         f"{_pct(c['ciRem'][1])}] | {_f(c['rDeep'], 2)} / {_f(c['rRem'], 2)} | "
                         f"{_pct(c['trueDeltaDeep'])} / {_pct(c['trueDeltaRem'])} |")
    L.append("")
    L += ["## Featureless sessions (gate R1; pooled % of sleep, band of the length)", "",
          "| model | variant | L | DEEP % sleep | REM % sleep | DEEP band | REM band | no sleep | GL |",
          "|---|---|---|---|---|---|---|---|---|"]
    for x in models_:
        for v in variants:
            for L_, q in (s["metrics"][x]["walch"][v].get("featureless") or {}).items():
                b = q["band"]
                lo = q["lowerBounds"]
                L.append(f"| {x} | {v} | {L_} | {_f(q['deepPctSleep'], 1)} | {_f(q['remPctSleep'], 1)} | "
                         f"{_f(b['deepLo'], 2) if lo else '—'}–{_f(b['deepHi'], 2)} | "
                         f"{_f(b['remLo'], 2) if lo else '—'}–{_f(b['remHi'], 2)} | {_pct(q['noSleepShare'])} % | "
                         f"{_pct(q['glShare'])} % |")
    L.append("")
    L += ["## Late DEEP tail, literature-reweighted pools (R2-R5) and sleep / wake κ (Walch)", "",
          "| model | variant | LATE tail (60 / 90) | LitDist | LitDist_mid | guard excess (GL) | LIT accuracy | LIT "
          "all LIGHT | LIT κ | SW κ | LitDist ≥ 90 min | sensitivity no-30 / no-margin |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for x in models_:
        for v in variants:
            q = s["metrics"][x]["walch"][v]
            lit, mid, t = q.get("lit") or {}, q.get("litMid") or {}, q["lateTail"]["perLength"]
            dsc = q.get("defaultScope") or {}
            sen = q.get("sensitivity") or {}
            L.append(f"| {x} | {v} | {_f(t.get('60', {}).get('excess'))} / {_f(t.get('90', {}).get('excess'))} | "
                     f"{_f(lit.get('litDist'), 2)} | {_f(mid.get('litDist'), 2)} | {_f(lit.get('guardExcess'))} | "
                     f"{_f(lit.get('accuracy'))} | {_f(lit.get('allLightAccuracy'))} | {_f(lit.get('kappa'))} | "
                     f"{_f(q['swKappa']['kappa'])} | {_f(dsc.get('litDist'), 2)} | {_f(sen.get('no30Row'), 2)} / "
                     f"{_f(sen.get('noMargin'), 2)} |")
    L.append("")
    L += ["LIT shares per anchor (DEEP / REM % of sleep; target D* / R*):", "",
          "| model | variant | " + " | ".join(f"{a} min" for a in ANCHORS) + " |",
          "|---|---|" + "---|" * len(ANCHORS)]
    for x in models_:
        for v in variants:
            per = (s["metrics"][x]["walch"][v].get("lit") or {}).get("perLength", {})
            L.append(f"| {x} | {v} | " + " | ".join(
                f"{_f(per[str(a)]['deepPctSleep'], 1)} / {_f(per[str(a)]['remPctSleep'], 1)} "
                f"({_f(per[str(a)]['target'][0], 1)} / {_f(per[str(a)]['target'][1], 1)})" if str(a) in per else "—"
                for a in ANCHORS) + " |")
    L.append("")
    for d_ in DATASETS:
        title = "Walch (PSG), headline" if d_ == report.HEADLINE_DATASET else "BIDSleep (EEG headband), secondary"
        L += [f"## Classical metrics, {title} (reported; against night truth)", ""]
        for v in variants:
            L += [f"### {v}", "", "| model | part | sessions | accuracy | E (pp) | κ | SW κ | wake F1 | all-LIGHT acc. "
                  "| predicted A / L / D / R % | true | P_DEEP ≥ 50 % (true) |",
                  "|---|---|---|---|---|---|---|---|---|---|---|---|"]
            for n in [*models_, *CONTEXT_ESTIMATORS, LIGHT_KIND]:
                per = s["cells"].get(n, {}).get(d_, {}).get(v, {})
                for part in ("EVAL", "pool:O", "pool:W", "pool:M", "pool:R", "regime:LATE", "MIDANCHOR", "TILE",
                             "LATESTART"):
                    c = per.get(part)
                    if not c:
                        continue
                    sc = c["shareCounts"]
                    L.append(f"| {n} | {part} | {c['sessions']} | {_f(c['accuracy'])} | {_f(c['E'], 1)} | "
                             f"{_f(c['kappa'])} | {_f(c['swKappa'])} | {_f(c['wakeF1'])} | {_f(c['lightAccuracy'])} | "
                             f"{_shares(c['predShares'])} | {_shares(c['trueShares'])} | "
                             f"{_pct(sc['pred']['deepHalf'])} ({_pct(sc['true']['deepHalf'])}) |")
            L.append("")
    L += ["## Inner selection per (variant, group)", "",
          "| variant | group | classifier (C) | θ_E | inner κ (margin) | C1 bias A / L / D / R (D) | C2 max D "
          "(decodes) | C0 max D (decodes) |", "|---|---|---|---|---|---|---|---|"]
    for q in s["groups"]:
        ch, h = q["chosen"], q["chosen"]["hybrid"]
        L.append(f"| {q['variant']} | {q['key']} | {ch['classifier']}" + (f" ({ch['C']:g})" if ch.get("C") else "")
                 + f" | {h['durationModel']}, {h['emissionWeight']:g} | {_f(ch['score'])} ({_f(q['margin'], 4)}) | "
                 + " / ".join(f"{x:+.2f}" for x in q["c1"]["bias"]) + f" ({_f(q['c1']['D'], 4)}) | "
                 f"{_f(q['c2']['maxD'], 4)} ({q['c2']['decodes']}) | "
                 + (f"{_f(q['c0']['maxD'], 4)} ({q['c0']['decodes']})" if q.get("c0") else "—") + " |")
    fc = s.get("foldChoices", {})
    L += ["", f"Outer folds per classifier: {fc.get('classifier')}; per decoder: {fc.get('theta')}.", ""]
    mg = s["reported"]["margin1312"]
    L += ["## Reported", "", "13.12-style margin (accuracy − all LIGHT over O ∪ W, Walch, worse variant): "
          + ", ".join(f"{x} {_f(q['worse'])}" for x, q in mg.items()) + ".", ""]
    return "\n".join(L)


# ========================================================================== featureless dry run (section 18) ====

SEEDS_DECISION_TAG = "naps-realistic featureless dry run"


def logged_seeds(log_path: Path | None) -> int | None:
    """The seed count decided by the featureless dry run NOTE of the commands log (the last one), or None."""
    if log_path is None or not Path(log_path).is_file():
        return None
    seeds = None
    for x in Path(log_path).read_text(encoding="utf-8").splitlines():
        if ds.STAMP_RE.match(x) and f" NOTE {SEEDS_DECISION_TAG}" in x and "-> seeds 0-" in x:
            seeds = int(x.rsplit("-> seeds 0-", 1)[1].split()[0].rstrip(";.,")) + 1
    return seeds


def featureless_timing(inp: Inputs, cli: AlgoCli, out: Path, jobs: int, seeds: int = FEATURELESS_SEED_CHOICES[0],
                       variant: str | None = None) -> dict:
    """Section 18: the featureless decodes of one variant timed with the previous (shipped) bundle (one call per outer
    group on the copies of its held-out Walch subjects, as in the run); timing and output size only, no metric
    computed, outputs deleted. Projects the run's featureless total (4 models x 2 variants) and decides the seeds:
    0-19, or 0-9 when the projection exceeds 2 h."""
    v = variant or inp.variants[0]
    work = Path(out) / "featureless-timing"
    groups = [g for g in inp.groups if g.variant == v]
    files = {}
    for g in groups:
        walch = [s for s in g.heldout if inp.index.dataset_of(s) == report.HEADLINE_DATASET]
        if walch:
            files[g.key] = write_sessions(work / f"{g.key}.jsonl", [s for s, _ in featureless_items(walch, seeds, v)])
    n = sum(1 for f in files.values() for _ in open(f, encoding="utf-8"))
    sizes: dict[str, int] = {}

    def one(g: napmodel.Group) -> None:
        if g.key not in files:
            return
        pf = write_json(work / f"{g.key}.params.json", inp.n1_params(g))
        mp = work / f"{g.key}.json"
        cli.run(["evaluate", "--nights", str(files[g.key]), "--estimator", "HYBRID", "--params", str(pf), "--model",
                 str(inp.previous_bundle), "--out", str(mp)])
        sizes[g.key] = mp.stat().st_size

    t0 = time.monotonic()
    loso.map_threads(one, groups, jobs)
    wall = time.monotonic() - t0
    shutil.rmtree(work, ignore_errors=True)
    projected = wall * len(MODELS) * len(VARIANTS)
    chosen = FEATURELESS_SEED_CHOICES[0] if projected <= FEATURELESS_BUDGET_S else FEATURELESS_SEED_CHOICES[1]
    return {"variant": v, "timedSeeds": int(seeds), "sessions": n, "calls": len(files), "jobs": jobs, "seconds": wall,
            "outputBytes": sum(sizes.values()), "projectedSeconds": projected, "budgetSeconds": FEATURELESS_BUDGET_S,
            "seeds": chosen, "algoCli": cli.fingerprint()}


def timing_note(t: Mapping) -> str:
    return (f"{SEEDS_DECISION_TAG} (section 18; previous bundle {SHIPPED_BUNDLE_SHA256[:8]}, {t['variant']}, seeds "
            f"0-{t['timedSeeds'] - 1}, no metric computed, outputs deleted): {t['sessions']} sessions in "
            f"{t['seconds']:.0f} s in {t['calls']} calls at --jobs {t['jobs']}, {t['outputBytes']} output bytes; "
            f"projected featureless total ({len(MODELS)} models x {len(VARIANTS)} variants) "
            f"{t['projectedSeconds'] / 3600:.2f} h (budget 2 h) -> seeds 0-{t['seeds'] - 1}")


# ===================================================================================== final (section 14) ====

def _fit_ranges(selections: Sequence[Mapping], key: str) -> dict:
    """Per anchor (tables) or overall (bias): the range of the fold fits' D."""
    if key == "c1":
        ds_ = [s["c1"]["D"] for s in selections]
        return {"all": [min(ds_), max(ds_)]} if ds_ else {}
    out: dict[str, list[float]] = defaultdict(list)
    for s in selections:
        for a, q in ((s.get(key) or {}).get("anchors") or {}).items():
            out[a].append(q["D"])
    return {a: [min(x), max(x)] for a, x in out.items()}


def final_model(inp: Inputs, cli: AlgoCli, exp_dir: Path, base_run: Path, out_dir: Path, jobs: int = 4,
                created_at: str | None = None, log_path: Path | None = None) -> dict:
    """``naps-realistic-final`` (section 14): the final nap section of the adopted candidate in the shipped bundle
    (main model, ``stageLogBias`` and ``stageLogBiasShort`` unchanged). C1 / C2: classifier, C and theta_X by
    ``napmodel.final_choice`` over the outer folds, fitted on the tiles of all subjects (weights over all subjects),
    statistics copied from the shipped main model; the C1 bias / C2 table on the LOSO out-of-fold decodes of the
    Walch subjects (both variants pooled; outer fold models of the final type and C, refitted where a group chose
    another). C0: the shipped nap section with its scalar bias replaced by a table fitted on the out-of-fold decodes
    of the stored N1 outer models at 13.12's final nap decoder. No candidate adopted: the shipped bundle unchanged.
    Writes ``final/hybrid_model.json``, ``final/result.json``, ``summary.json`` and ``base_defaults.json`` (the
    defaults of ``base_run``; ``naps-realistic-install`` writes ``trained_defaults.json``)."""
    global _SPEC
    exp_dir, out_dir, base_run = Path(exp_dir), Path(out_dir), Path(base_run)
    fp = cli.fingerprint()
    s = read_json(exp_dir / "summary.json")
    adopted, tier = s["decision"]["adopted"], s["decision"]["tier"]
    created_at = created_at or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    base_summary = read_json(base_run / "summary.json")
    base_defaults = read_json(base_run / "trained_defaults.json")
    shipped_path = base_run / "final" / "hybrid_model.json"
    shipped = read_bundle(shipped_path)
    result: dict = {"adopted": adopted, "tier": tier, "shippedSha256": _sha256(shipped_path),
                    "policies": {k: (v or {}).get("policy") for k, v in s["ruleII"].items()}}
    bundle = shipped
    note = (f"pre-registered realistic-nap experiment (docs/prereg/prereg-naps-realistic.md): "
            + (f"{adopted} ({MODEL_LABEL[adopted]}) adopted by rule (i), tier {tier}" if adopted
               else "no candidate adopted by rule (i), N1 kept"))
    selections = read_json(exp_dir / "selections.json")["selections"]
    night_theta = base_summary["selection"]["decoderParams"]["params"]
    walch = inp.walch()
    work = out_dir / "fit"
    if adopted in EVIDENCE_MODELS:
        folds = read_json(exp_dir / "folds.json")["folds"]
        ch = napmodel.final_choice(folds, selections)      # the nap-evidence order is the full grid's (unit-tested)
        theta = _theta(ch["hybrid"])
        result["choice"] = ch
        sessions = build_sessions(inp)
        tile_features(inp, cli, sessions, work, jobs)
        td = load_tile_data(inp, cli, sessions, work)
        _FIT_DATA["tiles"] = FitData(X=td.X, y=td.y, subject=td.subject, dataset=td.dataset, masked=td.masked,
                                     weighted=True)
        _SPEC = inp.cfg.classifier_spec
        final_key: FitKey = ("tiles", tuple(inp.pool), ch["classifier"], ch["C"])
        stored, tasks = {}, [final_key]
        for f in folds:
            if f["subject"] in set(walch):
                if f["classifier"] == ch["classifier"] and f["C"] == ch["C"]:
                    stored[(f["variant"], f["subject"])] = read_json(
                        exp_dir / "outer" / f["variant"] / "nap" / f"{f['subject']}.json")
                else:
                    tasks.append(("tiles", tuple(x for x in inp.pool if x != f["subject"]), ch["classifier"],
                                  ch["C"]))
        try:
            fits = fit_all(tasks, jobs)
            again = fit_all([final_key], 1)[final_key]
        finally:
            _FIT_DATA.clear()
        tc = fits[final_key]
        result["refitIdentical"] = tc.layers_json() == again.layers_json()
        if not result["refitIdentical"]:
            raise NapsRealisticError("the final fit is not reproducible with the same seed")
        oof: dict[str, dict[str, dict]] = {v: {} for v in inp.variants}
        refit = 0
        for v in inp.variants:
            for x in walch:
                if (v, x) in stored:
                    oof[v][x] = stored[(v, x)]
                else:
                    tr = tuple(y for y in inp.pool if y != x)
                    oof[v][x] = evidence_bundle(fits[("tiles", tr, ch["classifier"], ch["C"])],
                                                statistics_of(inp.fold(v, x)), td,
                                                f"evidence-loso-{v}-{ch['classifier']}-{x}",
                                                [f"LOSO fold, held out: {x}"], created_at, len(tr),
                                                tile_count(sessions, tr))
                    refit += 1
        result["refittedFolds"] = refit
        params = deep_merge(inp.nm.eval_params(night_theta, theta),
                            {"napMode": {"contextMarginMin": EVIDENCE_MARGIN_MIN}})
        final = evidence_bundle(tc, statistics_of(shipped), td, "naps-realistic-final-nap",
                                ["final model, all subjects"], created_at, len(inp.pool),
                                tile_count(sessions, inp.pool))
        if adopted == "C1":
            counter = iter(range(10_000))

            def decode(b: np.ndarray) -> np.ndarray:
                i = next(counter)
                total = np.zeros((N_STAGES, N_STAGES))
                for v in inp.variants:
                    ss, mt = sessions.of(v, ["tile"], walch)
                    d = write_bundles(work / f"c1-{v}-{i}", {x: standalone(m, b.tolist() if np.any(b) else None)
                                                             for x, m in oof[v].items()})
                    try:
                        recs = evaluate(cli, work / f"c1-{v}-{i}.json", ss, mt, "HYBRID", params, fold_models=d,
                                        keep_metrics=False)
                    finally:
                        shutil.rmtree(d, ignore_errors=True)
                    total += naps._pooled(recs)
                return total

            fit = fit_stage_bias(decode)
            section = with_nap_level(nap_section(final), bias=fit["bias"])
            result["fit"] = {k: fit[k] for k in ("bias", "D", "decodes", "trueShares", "predShares", "trace")}
            result["foldFitRange"] = _fit_ranges(selections, "c1")
            result["worseThanEveryFold"] = fit["D"] > max(result["foldFitRange"]["all"])
        else:
            fit = lit_table_fit(_oof_table_decoder(cli, sessions, oof, walch, params, work))
            section = with_nap_level(nap_section(final), length_bias=fit["table"])
            result["fit"] = {k: fit[k] for k in ("table", "anchors", "decodes", "maxD", "sessions", "weights")}
            result["foldFitRange"] = _fit_ranges(selections, "c2")
            result["worseThanEveryFold"] = {a: q["D"] > result["foldFitRange"].get(a, [math.inf, math.inf])[1]
                                            for a, q in fit["anchors"].items()}
        result.update({"classifier": ch["classifier"], "C": ch["C"], "hybrid": theta,
                       "contextMarginMin": EVIDENCE_MARGIN_MIN, "rows": tc.info.get("rows")})
        training = {"dataset": "tiles of the walch+bid-zero nights (PhysioNet sleep-accel 1.0.0 + BIDSleep 1.0.1)",
                    "license": shipped["training"]["license"], "subjects": len(inp.pool),
                    "nights": tile_count(sessions, inp.pool), "variants": list(TRAIN_VARIANT_ORDER),
                    "createdAt": created_at,
                    "notes": f"{note}; {tc.spec.describe()}; decoder {canonical_json(theta)} (napMode.hybrid); masked "
                             f"features {', '.join(MASKED_FEATURES)}; trained on tiles (every night minute once), each "
                             f"dataset half of the weight, each subject the same weight within its dataset; HSMM "
                             f"statistics copied from the main model; weighted class priors; napMode.contextMarginMin "
                             f"0 (no heart-rate context around a nap); "
                             + ("constant nap stageLogBias fitted to the tiles' night truth" if adopted == "C1" else
                                "stageLogBiasByLength fitted to the literature target of afternoon naps "
                                "(docs/prereg/prereg-naps-realistic.md section 5.1)")
                             + f"; rows {tc.info.get('rows')}"}
        section["training"] = training
    elif adopted == "C0":
        theta = _theta(base_defaults["napMode"]["hybrid"])
        sessions = build_sessions(inp)
        oof = {v: {x: inp.n1_nap(v, x) for x in walch} for v in inp.variants}
        params = inp.nm.eval_params(night_theta, theta)
        fit = lit_table_fit(_oof_table_decoder(cli, sessions, oof, walch, params, work))
        section = with_nap_level(shipped["nap"], length_bias=fit["table"])
        section["training"] = dict(section.get("training") or {}, notes=str((section.get("training") or {}).get(
            "notes", "")) + f"; {note}: the scalar nap stageLogBias replaced by stageLogBiasByLength fitted to the "
            "literature target of afternoon naps on the LOSO out-of-fold decodes of the stored N1 outer models")
        result.update({"fit": {k: fit[k] for k in ("table", "anchors", "decodes", "maxD", "sessions", "weights")},
                       "foldFitRange": _fit_ranges(selections, "c0"), "hybrid": theta, "contextMarginMin": None})
        result["worseThanEveryFold"] = {a: q["D"] > result["foldFitRange"].get(a, [math.inf, math.inf])[1]
                                        for a, q in fit["anchors"].items()}
    if adopted is not None:
        bundle = with_nap_model(shipped, section)
        bundle["modelId"] = f"{FINAL_MODEL_PREFIX}-{out_dir.name}"
        bundle["training"] = dict(bundle["training"], notes=bundle["training"]["notes"] + "; " + note)
        validate_bundle(bundle)
        nights_same = {k: v for k, v in bundle.items() if k not in ("nap", "modelId", "training")} == {
            k: v for k, v in shipped.items() if k not in ("nap", "modelId", "training")}
        if not nights_same:
            raise NapsRealisticError("the final bundle changed the main model")
    path = write_bundle(out_dir / "final" / "hybrid_model.json", bundle)
    write_json(out_dir / "base_defaults.json", base_defaults)
    sel = dict(base_summary["selection"])
    candidate_note = None
    if adopted in EVIDENCE_MODELS:
        ch = result["choice"]
        candidate_note = {"note": note, "decoderNote": (
            f"most frequent inner-CV choice of the pre-registered realistic-nap experiment: {ch['thetaCount']} of "
            f"{ch['foldsOfClassifier']} outer folds of the evidence {ch['classifier'].upper()}, "
            "docs/prereg/prereg-naps-realistic.md")}
    if cli.fingerprint().get("sha256") != fp.get("sha256"):
        raise NapsRealisticError("algo-cli changed during naps-realistic-final")
    meta = dict(base_summary["meta"], run=out_dir.name, createdAt=created_at,
                napsRealistic={"algoCli": fp, "code": code_fingerprint(), "exp": str(exp_dir), "prereg": PREREG_PATH,
                               "preregSha256": PREREG_SHA256, **provenance(log_path)})
    result["bundle"] = str(path)
    result["bundleSha256"] = _sha256(path)
    write_json(out_dir / "summary.json", naps._clean({
        "meta": meta, "selection": sel, "candidateNapModel": candidate_note,
        "napsRealistic": {k: v for k, v in result.items() if k not in ("fit",)}}))
    write_json(out_dir / "final" / "result.json", naps._clean(result))
    return result


def _oof_table_decoder(cli: AlgoCli, sessions: Sessions, oof: Mapping[str, Mapping[str, Mapping]],
                       walch: Sequence[str], params: Mapping, work: Path) -> Callable[[Mapping], list[dict]]:
    """decode(table) for the final tables: the ANCHOR sessions of the Walch subjects, both variants, each decoded by its
    out-of-fold model with ``table``."""
    it = iter(range(10_000))

    def decode(table: Mapping) -> list[dict]:
        i = next(it)
        recs = []
        for v, models in oof.items():
            ss, mt = sessions.of(v, ["eval"], walch, where=pools.is_anchor)
            d = write_bundles(work / f"table-{v}-{i}", {x: standalone(m, table=table) for x, m in models.items()})
            try:
                recs += evaluate(cli, work / f"table-{v}-{i}.json", ss, mt, "HYBRID", params, fold_models=d,
                                 keep_metrics=False)
            finally:
                shutil.rmtree(d, ignore_errors=True)
        return recs

    return decode


# ================================================================================ external test (section 13) ====

# McDevitt et al. 2026, Table 1 (weak labels of the test set; n = 22 per group): mean (SD)
TABLE1: dict[str, dict[str, tuple[float, float]]] = {
    "NREM": {"tib": (65.20, 23.51), "tst": (49.27, 9.41), "deepMin": (20.18, 14.33), "remMin": (0.11, 0.34),
             "wasoMin": (8.75, 16.06), "deepPct": (39.92, 25.37), "remPct": (0.25, 0.75)},
    "REM": {"tib": (113.86, 10.72), "tst": (89.91, 13.63), "deepMin": (21.95, 9.86), "remMin": (16.70, 9.49),
            "wasoMin": (14.05, 13.42), "deepPct": (24.90, 10.94), "remPct": (17.90, 9.29)},
}
TABLE1_N = 22
REM_PRESENT_MIN = 5.0                  # 13.10: REM presence = at least 5 min of predicted REM
FIRST_MIN_AFTER_ONSET = 50             # 13.9: REM minutes in the first 50 min after the onset (oh decode)


def external_sessions(data_dir: Path, variant: str) -> tuple[Path, dict[str, dict], dict[str, dict]]:
    """The ds006576 night files of a variant (``nights/<variant>``) with their meta: recording, group (manifest),
    window, ``complete`` (not truncated, not excluded); and the sessions themselves (for the heart-rate statistics)."""
    manifest = read_json(Path(data_dir) / ds.MANIFEST_NAME)
    subjects = manifest["subjects"]
    d = Path(data_dir) / "nights" / variant
    meta, sessions = {}, {}
    for f in sorted(d.glob("*.night.json")):
        n = read_json(f)
        sub, window = f.name[len(ds.DATASET) + 1:-len(".night.json")].rsplit("-", 1)
        e = subjects[sub]
        if n["id"] != ds.night_id(sub, window, variant):
            raise NapsRealisticError(f"{f}: id {n['id']} does not match its file name")
        meta[n["id"]] = {"kind": "external", "recording": sub, "subject": n["subject"], "group": e["group"],
                         "window": window, "complete": not e["truncated"] and not e.get("excluded"),
                         "variant": variant, "dataset": ds.DATASET, "L": (n["sessionEndMs"] - n["sessionStartMs"])
                         / pools.MIN, "startMs": n["sessionStartMs"], "endMs": n["sessionEndMs"]}
        sessions[n["id"]] = n
    return d, meta, sessions


def hr_stats(session: Mapping) -> dict:
    """Heart-rate statistics of the samples inside [start, end]: P50 - P10, SD of the 2-min steps, range, median."""
    s0, s1 = int(session["sessionStartMs"]), int(session["sessionEndMs"])
    hr = np.array([float(h[1]) for h in session["hr"] if s0 <= int(h[0]) <= s1])
    nan = float("nan")
    if hr.size < 2:
        return {"samples": int(hr.size), "p50MinusP10": nan, "stepSd": nan, "range": nan, "median": nan}
    return {"samples": int(hr.size), "p50MinusP10": float(np.percentile(hr, 50) - np.percentile(hr, 10)),
            "stepSd": float(np.std(np.diff(hr), ddof=1)) if hr.size > 2 else nan,
            "range": float(hr.max() - hr.min()), "median": float(np.median(hr))}


def mean_ci(values: Sequence[float], n_boot: int = report.BOOTSTRAP_SAMPLES, seed: int = 0) -> dict:
    v = np.asarray([x for x in values if x is not None and math.isfinite(x)], float)
    nan = float("nan")
    if v.size == 0:
        return {"n": 0, "mean": nan, "ciLow": nan, "ciHigh": nan}
    m = v[np.random.default_rng(seed).integers(0, v.size, size=(int(n_boot), v.size))].mean(axis=1)
    lo, hi = np.quantile(m, [0.025, 0.975])
    return {"n": int(v.size), "mean": float(v.mean()), "ciLow": float(lo), "ciHigh": float(hi)}


def predicted_waso(rows: Sequence[realism.EpochRow]) -> float:
    """Minutes of predicted AWAKE between the first and the last predicted sleep epoch (60-s epochs)."""
    st = [r.stage for r in rows]
    sl = [i for i, x in enumerate(st) if x in ("LIGHT", "DEEP", "REM")]
    return float(sum(1 for x in st[sl[0]:sl[-1] + 1] if x == "AWAKE")) if sl else 0.0


def m60_endpoint(by_variant: Mapping[str, Sequence[Mapping]]) -> dict:
    """Section 13.9: on the m60 windows, mean predicted REM minutes of the REM group minus the NREM group (95 % CI,
    10 000 resamples within groups, seed 0) above 0 in both variants; not evaluable when either group has fewer than
    15 usable m60 windows. Reported with it: YASA's REM minutes in the same windows per group."""
    out: dict = {"variants": {}}
    ok = True
    for v, recs in by_variant.items():
        rs = [(r, realism.external_window(r)) for r in recs if r["window"] == "m60" and r.get("complete", True)]
        g = {k: [x["remMin"] for r, x in rs if x["usable"] and r["group"] == k] for k in ("REM", "NREM")}
        yasa = {k: [float(np.asarray(r["confusion"], float)[REM].sum()) * realism.CHUNK_MIN for r, x in rs
                    if x["usable"] and r["group"] == k] for k in ("REM", "NREM")}
        evaluable = all(len(x) >= realism.M60_MIN_USABLE for x in g.values())
        diff = realism.group_diff_bootstrap(g["REM"], g["NREM"])
        out["variants"][v] = {"usable": {k: len(x) for k, x in g.items()}, "evaluable": evaluable, "diff": diff,
                              "yasaRemMin": {k: mean_ci(x) for k, x in yasa.items()},
                              "passed": evaluable and math.isfinite(diff["ciLow"]) and diff["ciLow"] > 0}
        ok = ok and out["variants"][v]["passed"]
    out["evaluable"] = all(q["evaluable"] for q in out["variants"].values())
    out["passed"] = bool(ok and out["evaluable"])
    return out


def external_reported(recs: Sequence[Mapping], rows: Mapping[tuple[str, int], realism.EpochRow],
                      sessions: Mapping[str, Mapping], onsets: Mapping[str, int | None] | None = None) -> dict:
    """Section 13.10 (reported) for one model and variant: group means with 95 % CIs against Table 1, dev_T1, shares
    of windows with DEEP >= 50 % / REM = 0 / DEEP = 0, the distribution of TST_p, REM presence vs group, the WAKE
    recordings, silver agreement with YASA, warnings and the heart-rate statistics of the windows; and the REM
    minutes in the first 50 min after the onset of the oh decode (13.9, reported)."""
    q = [(r, realism.external_window(r)) for r in recs if r.get("complete", True)]
    out: dict = {"groups": {}, "presence": {}, "agreement": {}, "wake": {}, "warnings": {}, "hr": {}}
    by_session: dict[str, list] = defaultdict(list)
    for (sid, ep), row in sorted(rows.items()):
        by_session[sid].append(row)
    for grp in ("NREM", "REM"):
        xs = [(r, x) for r, x in q if r["group"] == grp and r["window"] == "oh" and x["usable"]]
        long = [(r, x) for r, x in xs if x["tstMin"] > realism.SHORT_SLEEP_MIN]
        t1 = TABLE1[grp]
        out["groups"][grp] = {
            "usable": len(xs), "tstMin": mean_ci([x["tstMin"] for _, x in xs]),
            "deepMin": mean_ci([x["deepMin"] for _, x in xs]), "remMin": mean_ci([x["remMin"] for _, x in xs]),
            "deepPct": mean_ci([100 * x["d"] for _, x in long]), "remPct": mean_ci([100 * x["m"] for _, x in long]),
            "wasoMin": mean_ci([predicted_waso(by_session[r["night"]]) for r, _ in xs if r["night"] in by_session]),
            "table1": {k: {"mean": m, "sd": sd, "halfWidth": 1.96 * sd / math.sqrt(TABLE1_N)}
                       for k, (m, sd) in t1.items()},
            "deepHalfShare": float(np.mean([x["deepWindowShare"] >= 0.5 for _, x in xs])) if xs else float("nan"),
            "remZeroShare": float(np.mean([x["remMin"] == 0 for _, x in xs])) if xs else float("nan"),
            "deepZeroShare": float(np.mean([x["deepMin"] == 0 for _, x in xs])) if xs else float("nan"),
            "tstQuantiles": [float(v) for v in np.quantile([x["tstMin"] for _, x in xs], [0, 0.1, 0.25, 0.5, 0.75,
                                                                                        0.9, 1.0])] if xs else None,
            "first50MinRem": mean_ci([x for x in (_rem_after_onset(by_session.get(r["night"], []),
                                                                   sessions.get(r["night"]),
                                                                   (onsets or {}).get(r.get("recording")))
                                                  for r, _ in xs) if math.isfinite(x)])}
    g = out["groups"]
    out["devT1"] = (abs(g["NREM"]["deepPct"]["mean"] - TABLE1["NREM"]["deepPct"][0])
                    + abs(g["REM"]["deepPct"]["mean"] - TABLE1["REM"]["deepPct"][0])
                    + abs(g["REM"]["remPct"]["mean"] - TABLE1["REM"]["remPct"][0]))
    for w in ("oh", "rec", "oh1"):
        xs = [(r, x) for r, x in q if r["group"] in ("NREM", "REM") and r["window"] == w and x["usable"]]
        pos = np.array([r["group"] == "REM" for r, _ in xs], bool)
        rem = np.array([x["remMin"] for _, x in xs], float)
        pred = rem >= REM_PRESENT_MIN
        sens = float(pred[pos].mean()) if pos.any() else float("nan")
        spec = float((~pred[~pos]).mean()) if (~pos).any() else float("nan")
        out["presence"][w] = {"windows": len(xs), "sensitivity": sens, "specificity": spec,
                              "balancedAccuracy": (sens + spec) / 2, "auc": realism.mann_whitney_auc(rem, pos)}
    for w in ds.WINDOWS:
        rs = [r for r, x in q if r["window"] == w and x["usable"]]
        c = pooled(rs)
        out["agreement"][w] = {"windows": len(rs), "accuracy": float(np.trace(c) / c.sum()) if c.sum() else
                               float("nan"), "kappa": report.kappa(c) if c.sum() else float("nan")}
        out["warnings"][w] = dict(Counter(x for r in recs if r["window"] == w for x in r.get("warnings", ())))
        hs = [hr_stats(sessions[r["night"]]) for r in recs if r["window"] == w and r["night"] in sessions]
        out["hr"][w] = {k: float(np.nanmean([h[k] for h in hs])) if hs else float("nan")
                        for k in ("p50MinusP10", "stepSd", "range", "median")}
    for label, complete in (("complete", True), ("truncated", False)):
        ws = [r for r in recs if r["group"] == "WAKE" and r["window"] == "rec" and r.get("complete", True) == complete]
        xs = [realism.external_window(r) for r in ws]
        out["wake"][label] = {
            "recordings": len(ws), "usable": sum(1 for x in xs if x["usable"]),
            "yasaSleepMin": mean_ci([float(np.asarray(r["confusion"], float)[1:].sum()) * realism.CHUNK_MIN
                                     for r in ws if r["confusion"] is not None]),
            **{f"{k}Min": mean_ci([x[f"{k}Min"] for x in xs if x["usable"]]) for k in ("tst", "deep", "rem")}}
    return out


def _rem_after_onset(rows: Sequence[realism.EpochRow], session: Mapping | None, onset_ms: int | None) -> float:
    """Predicted REM minutes in the first 50 min after the onset of an oh decode (13.9, reported): the 60-s session
    epochs (epoch k starts at the window start + k min) that start in [onset, onset + 50 min). The onset is YASA's
    (the first epoch that starts a run of 3 non-W epochs, :func:`yasa_onsets`), not "the window start + 5 min", which
    is wrong when the window start was clipped to the ECG-valid range; the part of the 50 min before a clipped window
    start has no decode and is not counted."""
    if session is None or not rows or onset_ms is None:
        return float("nan")
    s0 = int(session["sessionStartMs"])
    first = max(0, -(-(int(onset_ms) - s0) // pools.MIN))
    last = -(-(int(onset_ms) + FIRST_MIN_AFTER_ONSET * pools.MIN - s0) // pools.MIN)
    return float(sum(1 for r in rows[first:max(first, last)] if r.stage == "REM"))


def yasa_onsets(data_dir: Path) -> tuple[dict[str, int | None], list[str]]:
    """YASA's sleep onset of every staged recording (ms from the first frame): the first 30-s epoch that starts a run
    of 3 non-W epochs (section 13.5; None without such a run), from ``stages/<subject>.json`` checked against the
    manifest's ``stagesSha256``. Returns (onsets, recordings whose stage file is missing or differs from the manifest:
    no onset for them). Read only by the external step, after the freeze check."""
    manifest = read_json(Path(data_dir) / ds.MANIFEST_NAME)
    out: dict[str, int | None] = {}
    bad: list[str] = []
    for sub, e in sorted(manifest["subjects"].items()):
        f = Path(data_dir) / ds.stage_relpath(sub)
        if e.get("excluded"):
            continue
        if not e.get("stagesSha256") or not f.is_file() or ds.sha256_file(f) != e["stagesSha256"]:
            bad.append(sub)
            continue
        b = ds.run_bounds(read_json(f)["stages"], ds.RUN_EPOCHS)
        out[sub] = None if b is None else int(b[0] * ds.EPOCH_MS)
    return out, bad


def paired_windows(s_recs: Sequence[Mapping], x_recs: Sequence[Mapping]) -> dict:
    """X - S on the same usable windows (oh, both groups): DEEP and REM % of TST_p and TST_p, bootstrap over windows."""
    s = {r["night"]: realism.external_window(r) for r in s_recs if r["window"] == "oh"}
    out = {}
    for key, scale in (("d", 100.0), ("m", 100.0), ("tstMin", 1.0)):
        dif = [scale * (realism.external_window(r)[key] - s[r["night"]][key]) for r in x_recs
               if r["window"] == "oh" and r["night"] in s and s[r["night"]]["usable"]
               and realism.external_window(r)["usable"]]
        out[key] = mean_ci([x for x in dif if math.isfinite(x)])
    return out


def shipped_nap_params(final_dir: Path) -> dict:
    """Section 13.7, S = the shipped bundle at the shipped defaults: the ``napMode`` of ``nap-model-final``'s trained
    defaults (``base_defaults.json`` of the final run: theta_nap of 13.12), ``contextMarginMin`` null (the night's
    30 min) and ``outputPolicy`` STAGES, all passed explicitly, so that S is N1 whatever defaults the build that runs
    the external step has (e.g. a re-run after ``defaults --write`` of an installation)."""
    base = read_json(Path(final_dir) / "base_defaults.json").get("napMode") or {}
    nap: dict = {"outputPolicy": "STAGES", "contextMarginMin": None}
    if base.get("hybrid"):
        nap["hybrid"] = _theta(base["hybrid"])
    if base.get("maxSessionMin") is not None:
        nap["maxSessionMin"] = float(base["maxSessionMin"])
    return nap


def final_nap_params(fr: Mapping, final_dir: Path) -> dict:
    """Section 13.7, X = the adopted candidate's final bundle at its final parameters: theta_X and
    ``contextMarginMin`` 0 (C1 / C2) or null (C0), STAGES, passed explicitly as for S."""
    m = fr.get("contextMarginMin")
    return {**shipped_nap_params(final_dir), "hybrid": _theta(fr["hybrid"]),
            "contextMarginMin": None if m is None else int(m)}


def external(cli: AlgoCli, exp_dir: Path, final_dir: Path, data_dir: Path, out_dir: Path, log_path: Path,
             previous_bundle: Path = PREVIOUS_BUNDLE, lock_path: Path = ds.LOCK_PATH,
             variants: Sequence[str] = VARIANTS) -> dict:
    """``naps-realistic-external`` (section 13): refuses unless ``summary.json`` of ``exp_dir`` holds rule (i) and rule
    (ii) for N1 and the adopted candidate; G9 (``ds006576.freeze_check``: the order of the commands log, the logged
    manifest and summary digests, the YASA identity of the lock; a failure makes the test void, nothing is decoded);
    then decodes S (the previous bundle at the shipped defaults, STAGES: :func:`shipped_nap_params`) and, when a
    candidate is adopted, X (its final bundle at its final parameters: :func:`final_nap_params`) on every ds006576
    window of both variants, with every nap parameter passed explicitly (whether the build is the experiment's is
    recorded in ``meta.buildIsTheExperiments``), and computes rule (iii)
    (``realism.rule_iii``), the m60 endpoint (13.9) and the reported quantities (13.10); YASA's group summaries are
    rendered only now. Writes ``summary.json``, ``report.md`` and ``decodes.jsonl``."""
    exp_dir, final_dir, data_dir, out_dir = Path(exp_dir), Path(final_dir), Path(data_dir), Path(out_dir)
    s = read_json(exp_dir / "summary.json")
    dec, r2 = s.get("decision"), s.get("ruleII") or {}
    if not dec or not r2.get("N1") or (dec.get("adopted") and not r2.get("X")):
        raise NapsRealisticError(f"{exp_dir / 'summary.json'} does not hold rules (i) and (ii) for N1 and the adopted "
                                 "candidate: the external step refuses")
    adopted, tier = dec["adopted"], dec["tier"]
    fr = read_json(final_dir / "final" / "result.json")
    if fr.get("adopted") != adopted:
        raise NapsRealisticError(f"{final_dir} was made for {fr.get('adopted')}, the experiment adopted {adopted}")
    out_dir.mkdir(parents=True, exist_ok=True)
    freeze = ds.freeze_check(data_dir, log_path=log_path, lock_path=lock_path, prereg_sha256=PREREG_SHA256,
                             exp_run=exp_dir.name)
    global_void = [] if freeze["ok"] else ["freeze check (G9) failed: " + "; ".join(freeze["failures"])]
    prev_sha = _sha256(previous_bundle) if Path(previous_bundle).is_file() else None
    if prev_sha != SHIPPED_BUNDLE_SHA256:
        global_void.append(f"the previous bundle {previous_bundle} is not {SHIPPED_BUNDLE_SHA256}")
    fp = cli.fingerprint()
    exp_build = (s.get("meta") or {}).get("algoCli") or {}
    res: dict = {"meta": {"run": out_dir.name, "exp": str(exp_dir), "final": str(final_dir), "data": str(data_dir),
                          "algoCli": fp, "experimentAlgoCli": exp_build.get("sha256"),
                          "buildIsTheExperiments": fp.get("sha256") == exp_build.get("sha256"),
                          "code": code_fingerprint(), "prereg": PREREG_PATH, "preregSha256": PREREG_SHA256,
                          "previousBundleSha256": prev_sha, **provenance(log_path, lock_path)},
                 "freeze": freeze, "globalVoid": global_void, "adopted": adopted, "tier": tier}
    ext: dict = {}
    decodes: list[dict] = []
    if not global_void:
        plans = [("S", Path(previous_bundle), {"napMode": shipped_nap_params(final_dir)})]
        if adopted:
            plans.append(("X", final_dir / "final" / "hybrid_model.json", {"napMode": final_nap_params(fr, final_dir)}))
        res["params"] = {name: params for name, _, params in plans}
        onsets, bad_stages = yasa_onsets(data_dir)
        res["onsets"] = {"recordings": len(onsets), "withOnset": sum(1 for x in onsets.values() if x is not None),
                         "stageFileMissingOrChanged": bad_stages}
        reported: dict = {}
        sessions_by_v = {}
        for v in variants:
            nights_dir, meta, sessions = external_sessions(data_dir, v)
            sessions_by_v[v] = sessions
            for name, model, params in plans:
                pe = out_dir / "per-epoch" / f"{name}.{v}.csv"
                pe.parent.mkdir(parents=True, exist_ok=True)
                recs = evaluate(cli, out_dir / "decodes" / f"{name}.{v}.json", nights_dir, meta, "HYBRID", params,
                                model=model, per_epoch=pe, extra={"model": name})
                decodes += recs
                ext.setdefault(name, {})[v] = realism.external_summary(recs)
                rows = realism.read_per_epoch(pe)
                reported.setdefault(name, {})[v] = external_reported(recs, rows, sessions, onsets)
        res["external"] = ext
        res["m60"] = {name: m60_endpoint({v: [r for r in decodes if r["model"] == name and r["variant"] == v]
                                          for v in variants}) for name, _, _ in plans}
        res["reported"] = reported
        if adopted:
            res["xMinusS"] = {v: paired_windows([r for r in decodes if r["model"] == "S" and r["variant"] == v],
                                                [r for r in decodes if r["model"] == "X" and r["variant"] == v])
                              for v in variants}
        res["yasaSummary"] = read_json(data_dir / ds.SUMMARY_NAME)
    if cli.fingerprint().get("sha256") != fp.get("sha256"):
        raise NapsRealisticError("algo-cli changed during naps-realistic-external")
    res["ruleIII"] = realism.rule_iii(ext, adopted, tier, r2["N1"], r2.get("X") if adopted else None, global_void,
                                      variants)
    res["ruleIII"]["globalVoid"] = global_void
    _write_jsonl(out_dir / "decodes.jsonl", decodes)
    write_json(out_dir / "summary.json", naps._clean(res))
    (out_dir / "report.md").write_text(render_external(naps._clean(res)), encoding="utf-8")
    return res


def external_result_line(res: Mapping) -> str:
    r = res["ruleIII"]
    void = {k: q["void"] for k, q in r["models"].items() if q["void"]}
    return (f"rule (iii): adopted {res['adopted'] or 'none'}" + (f" (tier {res['tier']})" if res["adopted"] else "")
            + f"; install {r['install']}; in effect {r['inEffect']}; policy {r['policyBefore']} -> {r['policy']}"
            + (" (demoted)" if r["demoted"] else "") + (f"; void {void}" if void else "")
            + f"; freeze check {'passed' if res['freeze']['ok'] else 'failed'}")


def render_external(res: Mapping) -> str:
    r = res["ruleIII"]
    L = [f"# Realistic naps — external test on OpenNeuro ds006576 (run `{res['meta']['run']}`)", "",
         f"Pre-registration `{res['meta']['prereg']}` section 13; adopted candidate: {res['adopted'] or 'none'}"
         + (f" (tier {res['tier']})" if res["adopted"] else "") + ". Freeze check (G9): "
         + ("passed" if res["freeze"]["ok"] else "**failed** — " + "; ".join(res["freeze"]["failures"])) + ".", "",
         "## Rule (iii)", "",
         f"Install: **{r['install']}**; model in effect: {r['inEffect']}; output policy {r['policyBefore']} → "
         f"**{r['policy']}**" + (" (demoted)" if r["demoted"] else "") + ".", ""]
    for k, q in r["models"].items():
        L.append(f"### {'S (shipped N1)' if k == 'S' else 'X (' + str(res['adopted']) + ')'}")
        L.append("")
        if q["void"]:
            L += ["Void: " + "; ".join(q["void"]), ""]
            continue
        L += ["| criterion | variant | value | threshold | pass |", "|---|---|---|---|---|"]
        for blk in ("a", "b", "c", "d"):
            for c in q["criteria"][blk]["criteria"]:
                L.append(f"| ({blk}) {c['name']} | {c.get('variant')} | {_f(c['value'], 2)} | {c['op']} "
                         f"{_f(c['threshold'], 2)} | {'✓' if c['pass'] else '✗'} |")
        L.append("")
    for name, m in (res.get("m60") or {}).items():
        L += [f"m60 REM endpoint ({name}): evaluable {m['evaluable']}, passed {m['passed']}: "
              + ", ".join(f"{v} {_f(q['diff']['diff'], 1)} min [{_f(q['diff']['ciLow'], 1)}, "
                          f"{_f(q['diff']['ciHigh'], 1)}]" for v, q in m["variants"].items()), ""]
    for name, per in (res.get("reported") or {}).items():
        for v, q in per.items():
            L += [f"### Reported: {name}, {v}", "",
                  "| group | usable oh | TST min | DEEP % TST | REM % TST | DEEP min | REM min | WASO min | Table 1 "
                  "DEEP % / REM % |", "|---|---|---|---|---|---|---|---|---|"]
            for grp, g in q["groups"].items():
                t = g["table1"]
                L.append(f"| {grp} | {g['usable']} | {_f(g['tstMin']['mean'], 1)} | {_f(g['deepPct']['mean'], 1)} | "
                         f"{_f(g['remPct']['mean'], 1)} | {_f(g['deepMin']['mean'], 1)} | "
                         f"{_f(g['remMin']['mean'], 1)} | {_f(g['wasoMin']['mean'], 1)} | {t['deepPct']['mean']} / "
                         f"{t['remPct']['mean']} |")
            L += ["", f"dev_T1 {_f(q['devT1'], 1)} pp; REM presence (oh): " + ", ".join(
                f"{k} {_f(x, 2)}" for k, x in q["presence"]["oh"].items() if k != "windows"), ""]
    y = res.get("yasaSummary")
    if y:
        L += ["## YASA's own group summaries (silver labels, reported only)", "", "```",
              json.dumps(y.get("groups"), indent=1, sort_keys=True), "```", ""]
    return "\n".join(L)


# =============================================================================== installation (section 14) ====

def install(exp_dir: Path, final_dir: Path, external_dir: Path, install_model: Path | None = None) -> dict:
    """``naps-realistic-install``: applies rule (iii) of ``external_dir``. If the adopted candidate X passes, its final
    bundle is copied to ``install_model`` (the app asset) when given, and the defaults set ``napMode.hybrid`` =
    theta_X, ``napMode.contextMarginMin`` = 0 (C1 / C2; C0 none) and ``napMode.outputPolicy`` = X's rule-(ii) policy
    after any demotion; otherwise the bundle is unchanged and only ``napMode.outputPolicy`` is set, by N1's rule-(ii)
    policy and any demotion. Writes ``trained_defaults.json`` (``base_defaults.json`` deep-merged) and the selection
    notes into ``final_dir/summary.json`` (read by ``train.py defaults``)."""
    exp_dir, final_dir, external_dir = Path(exp_dir), Path(final_dir), Path(external_dir)
    s = read_json(exp_dir / "summary.json")
    ext = read_json(external_dir / "summary.json")
    fr = read_json(final_dir / "final" / "result.json")
    adopted = s["decision"]["adopted"]
    if fr.get("adopted") != adopted or ext.get("adopted") != adopted:
        raise NapsRealisticError(f"experiment, final run and external step disagree on the adopted candidate: "
                                 f"{adopted} / {fr.get('adopted')} / {ext.get('adopted')}")
    iii = ext["ruleIII"]
    installed = bool(iii["install"])
    in_effect = adopted if installed else REFERENCE
    pol_rec = s["ruleII"]["X"] if installed else s["ruleII"]["N1"]
    nap: dict = {"outputPolicy": iii["policy"]}
    if installed:
        nap["hybrid"] = _theta(fr["hybrid"])
        if fr.get("contextMarginMin") is not None:
            nap["contextMarginMin"] = int(fr["contextMarginMin"])
    defaults = deep_merge(read_json(final_dir / "base_defaults.json"), {"napMode": nap})
    write_json(final_dir / "trained_defaults.json", defaults)
    summary = read_json(final_dir / "summary.json")
    sel = dict(summary["selection"])
    crit = {c["name"]: c for c in pol_rec["stagesCriteria"]}
    sel["napOutputPolicy"] = {"note": (
        f"rule (ii) of the pre-registered realistic-nap experiment (docs/prereg/prereg-naps-realistic.md) on "
        f"{in_effect}: {pol_rec['policy']} (LIT accuracy margin over all LIGHT {_f(crit['ii.litMargin']['value'])}, "
        f"LIT 4-class kappa {_f(crit['ii.litKappa']['value'])}, sleep/wake kappa "
        f"{_f(pol_rec['sleepWakeCriterion']['value'])}, F {'passed' if crit['ii.F']['pass'] else 'failed'}, R "
        f"{'passed' if crit['ii.R']['pass'] else 'failed'}, Walch, worse variant)"
        + (f", set to {iii['policy']} by rule (iii) on ds006576" if iii["demoted"] else ""))}
    if installed and "contextMarginMin" in nap:
        sel["napContextMargin"] = {"note": f"{adopted} of the pre-registered realistic-nap experiment installed: a nap "
                                           "reads no heart-rate context around it"}
    if installed and summary.get("candidateNapModel"):
        sel["napModel"] = summary["candidateNapModel"]          # C1 / C2: the evidence model's decoder
    rec = {"installed": installed, "model": in_effect, "policy": iii["policy"], "demoted": iii["demoted"],
           "void": {k: q["void"] for k, q in iii["models"].items()}, "napMode": nap,
           "bundle": str(final_dir / "final" / "hybrid_model.json") if installed else None}
    if installed and install_model is not None:
        install_model = Path(install_model)
        install_model.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(final_dir / "final" / "hybrid_model.json", install_model)
        rec["installedTo"] = str(install_model)
    summary["selection"] = sel
    summary["installation"] = rec
    write_json(final_dir / "summary.json", naps._clean(summary))
    return rec


def install_result_line(rec: Mapping) -> str:
    return (f"installed {rec['installed']} ({rec['model']} in effect); napMode {canonical_json(rec['napMode'])}"
            + (" (demoted by rule (iii))" if rec["demoted"] else "")
            + (f"; bundle copied to {rec['installedTo']}" if rec.get("installedTo") else ""))


# ============================================================================== verification (section 15) ====

def verify(inp: Inputs, cli: AlgoCli, exp_dir: Path, final_dir: Path, out_dir: Path,
           installed_bundle: Path = APP_BUNDLE, jobs: int = 4, log_path: Path | None = None) -> dict:
    """``naps-realistic-verify`` with the final build (fails on any difference): (1) the fold bundles of the model in
    effect (the installed candidate, else N1) decode every whole night with each group's night theta_g and otherwise
    the build's default parameters as candidate B; (2) the adopted candidate's EVAL decodes (N1's when none was
    adopted) are reproduced session by session (theta, ``napMode.contextMarginMin`` and STAGES passed explicitly);
    (3) the installed bundle and the previous bundle decode every whole night identically with the default parameters
    (records and per-epoch CSV byte for byte); (4) the nights decode byte-identically with ``napMode.contextMarginMin``
    null, 0 and 30 (each passed explicitly, and with no params file); (5) the previous bundle's fingerprints are the
    base build's and the installed bundle's night-model fingerprint is the previous one's (fails when the probe cannot
    run); (7) the installed bundle is accepted on every EVAL session; (8) with C1 / C2 installed every EVAL nap decodes
    identically with and without its context records and with ``app+15steps``. Items 7 and 8 use the defaults with
    ``napMode.outputPolicy`` STAGES (section 6.6), so that a SKIP default cannot make them fail or vacuous. Items 6
    and 9 are the core-algo / app unit tests (``./gradlew test``)."""
    exp_dir, final_dir, out_dir = Path(exp_dir), Path(final_dir), Path(out_dir)
    fp = cli.fingerprint()
    s = read_json(exp_dir / "summary.json")
    inst = read_json(final_dir / "summary.json").get("installation")
    if inst is None:
        raise NapsRealisticError(f"{final_dir}: naps-realistic-install has not run")
    model = inst["model"]
    adopted = s["decision"]["adopted"] or REFERENCE
    sel = {(x["variant"], x["key"]): x for x in read_json(exp_dir / "selections.json")["selections"]}
    stored = {(r["variant"], r["night"]): r for r in _read_jsonl(exp_dir / "results.jsonl")
              if r["model"] == adopted and r["kind"] == "eval"}
    sessions = build_sessions(inp)
    nights_b = napmodel.stored_nights(inp.nm)
    out: dict = {"model": model, "adopted": adopted, "installed": inst["installed"], "algoCli": fp,
                 "code": code_fingerprint(), **provenance(log_path)}

    def bundles_of(m: str, g: napmodel.Group) -> dict[str, dict]:
        sel_g = sel[(g.variant, g.key)]
        evid = {x: read_json(exp_dir / "outer" / g.variant / "nap" / f"{x}.json") for x in g.heldout} \
            if m in EVIDENCE_MODELS else {}
        return {x: fold_bundle(inp, m, g.variant, x, sel_g, evid.get(x)) for x in g.heldout}

    def one(g: napmodel.Group):
        d1 = write_bundles(out_dir / "models" / "effect" / g.variant / g.key, bundles_of(model, g))
        d2 = write_bundles(out_dir / "models" / "adopted" / g.variant / g.key, bundles_of(adopted, g))
        try:
            ns, nm_ = sessions.of(g.variant, ["night"], g.heldout)
            night_recs = evaluate(cli, out_dir / g.variant / f"{g.key}.nights.json", ns, nm_, "HYBRID",
                                  inp.nm.base.params(g.theta), fold_models=d1, keep_metrics=False)
            es, em = sessions.of(g.variant, ["eval"], g.heldout)
            eval_recs = evaluate(cli, out_dir / g.variant / f"{g.key}.eval.json", es, em, "HYBRID",
                                 model_params(inp, adopted, g, sel[(g.variant, g.key)]), fold_models=d2,
                                 keep_metrics=False)
        finally:
            shutil.rmtree(d1, ignore_errors=True)
            shutil.rmtree(d2, ignore_errors=True)
        return night_recs, eval_recs

    res = loso.map_threads(one, inp.groups, jobs)
    night_recs = [r for a, _ in res for r in a]
    eval_recs = [r for _, b in res for r in b]
    same_n = [r for r in night_recs if napmodel.same_as(nights_b.get((r["variant"], r["night"])), r,
                                                         int(r["endMs"]) - int(r["startMs"]), 0.0)]
    bad_e = [(r["variant"], r["night"]) for r in eval_recs
             if not same_decode(stored.get((r["variant"], r["night"])), r)]
    out["item1"] = {"nights": len(night_recs), "identicalToB": len(same_n), "stored": len(nights_b),
                    "passed": len(night_recs) == len(same_n) == len(nights_b) > 0}
    out["item2"] = {"sessions": len(eval_recs), "identical": len(eval_recs) - len(bad_e), "mismatches": bad_e[:10],
                    "passed": not bad_e and len(eval_recs) == len(stored) > 0}
    work = out_dir / "final"
    item3, item4, item7, item8 = {}, {}, [], {}
    for v in inp.variants:
        got = {}
        for tag, bpath in (("previous", inp.previous_bundle), ("installed", Path(installed_bundle))):
            pe = work / f"{v}.{tag}.per-epoch.csv"
            m = cli.evaluate(inp.nights_root / v, "HYBRID", work / f"{v}.{tag}.json", model=bpath, per_epoch=pe)
            got[tag] = (_decode_key(report.parse_metrics(m)), _sha256(pe))
        a, b = got["previous"], got["installed"]
        item3[v] = {"nights": len(a[0]), "identical": sum(1 for k in a[0] if a[0][k] == b[0].get(k)),
                    "perEpochIdentical": a[1] == b[1]}
        digests = {}
        # every registered value is passed explicitly (null too), so that the three are decoded whatever the build's
        # default NAP_CONTEXT_MARGIN_MIN is (0 after the installation of C1 / C2); "default" = no params file
        for tag, patch in (("default", None), ("null", {"napMode": {"contextMarginMin": None}}),
                           ("0", {"napMode": {"contextMarginMin": 0}}), ("30", {"napMode": {"contextMarginMin": 30}})):
            pf = write_json(work / f"margin-{tag}.params.json", patch) if patch else None
            pe = work / f"{v}.margin-{tag}.per-epoch.csv"
            cli.evaluate(inp.nights_root / v, "HYBRID", work / f"{v}.margin-{tag}.json", params=pf,
                         model=Path(installed_bundle), per_epoch=pe)
            digests[tag] = _sha256(pe)
        item4[v] = {"digests": digests, "identical": len(set(digests.values())) == 1}
        es, em = sessions.of(v, ["eval"])
        recs = evaluate(cli, work / f"{v}.eval.json", es, em, "HYBRID", STAGES_PATCH, model=Path(installed_bundle))
        # accepted = never rejected (HYBRID_MODEL_UNAVAILABLE / HMM fallback: refused by ``napmodel.tag``); a RULES
        # fallback for low coverage is a property of the session, counted
        item7.append({"variant": v, "sessions": len(recs), "estimated": sum(1 for r in recs if r["estimated"]),
                      "hybrid": sum(1 for r in recs if r["estimatorUsed"] == "HYBRID"),
                      "rulesFallback": sum(1 for r in recs if r["estimatorUsed"] == "RULES"),
                      "modelUnavailable": sum(1 for r in recs if "HYBRID_MODEL_UNAVAILABLE" in r["warnings"])})
        if inst["installed"] and model in EVIDENCE_MODELS:
            per = {}
            for name in ("noctx", *G5_PERTURBATIONS):
                if name in per:
                    continue
                copies = [perturb.perturbed_copy(x, name)[0] for x in es]
                cm = {c["id"]: {**em[c["id"].rsplit(perturb.SEPARATOR, 1)[0]], "original": c["id"].rsplit(
                    perturb.SEPARATOR, 1)[0], "perturbation": name} for c in copies}
                prec = evaluate(cli, work / f"{v}.{name}.json", copies, cm, "HYBRID", STAGES_PATCH,
                                model=Path(installed_bundle))
                orig = {r["night"]: r for r in recs}
                per[name] = {"sessions": len(prec), "estimated": sum(1 for r in prec if r["estimated"]),
                             "identical": sum(1 for r in prec if same_decode(orig.get(r["original"]), r))}
            item8[v] = per
        for f in work.glob(f"{v}.*"):
            f.unlink(missing_ok=True)
    out["item3"] = {"variants": item3, "passed": all(q["identical"] == q["nights"] > 0 and q["perEpochIdentical"]
                                                     for q in item3.values())}
    out["item4"] = {"variants": item4, "passed": all(q["identical"] for q in item4.values())}
    fps = probe_fingerprints(cli, [inp.previous_bundle, Path(installed_bundle)], work)
    if fps is None:          # the probe could not run: the pre-registered equality is not shown, so it fails
        out["item5"] = {"probe": "the launcher has no lib/*.jar (not an installDist build): fingerprints not computed",
                        "passed": False}
    else:
        p, i = fps[str(inp.previous_bundle)], fps[str(Path(installed_bundle))]
        out["item5"] = {"previous": p, "installed": i,
                        "passed": p["fingerprint"] == BASE_FINGERPRINT and p["nightModelFingerprint"] ==
                        BASE_NIGHT_FINGERPRINT and i["nightModelFingerprint"] == p["nightModelFingerprint"]}
    out["item7"] = {"variants": item7, "passed": all(q["hybrid"] > 0 and q["modelUnavailable"] == 0
                                                     and q["hybrid"] + q["rulesFallback"] == q["estimated"]
                                                     for q in item7)}
    out["item8"] = {"variants": item8, "applies": bool(inst["installed"] and model in EVIDENCE_MODELS),
                    "passed": all(q["identical"] == q["sessions"] for per in item8.values() for q in per.values())}
    out["items6and9"] = "run `mise exec -- ./gradlew test ktlintCheck detekt` (PersonalNightInvarianceTest, " \
                        "NapModeRegressionTest, HybridModelTest)"
    if cli.fingerprint().get("sha256") != fp.get("sha256"):
        raise NapsRealisticError("algo-cli changed during naps-realistic-verify")
    out["passed"] = all(out[k]["passed"] for k in ("item1", "item2", "item3", "item4", "item5", "item7", "item8"))
    write_json(out_dir / "summary.json", naps._clean(out))
    if not out["passed"]:
        raise NapsRealisticError("verification failed: " + json.dumps(naps._clean(
            {k: v for k, v in out.items() if k.startswith("item") and isinstance(v, dict) and not v.get("passed")})))
    return out


__all__ = ["CANDIDATES", "MASKED_FEATURES", "MODELS", "PREREG_PATH", "PREREG_SHA256", "GateFailure", "Inputs",
           "NapsRealisticError", "RunLog", "build_sessions", "evaluate_models", "external", "featureless_timing",
           "final_model", "install", "lit_table_fit", "matched_epochs", "read_epochs", "rerender", "run_experiment",
           "run_gates", "select_group", "subject_weights", "summarize", "verify"]
