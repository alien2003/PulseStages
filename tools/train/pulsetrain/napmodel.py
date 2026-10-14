"""Pre-registered dedicated nap model (``docs/prereg/prereg-nap-model.md``, :data:`PREREG_SHA256`): does a nap model
in the bundle's ``nap`` section (classifier, durations, transitions, initial stage, time prior, class priors and its own
stageLogBias, fitted on nap-length sessions, with its own decoder parameters ``napMode.hybrid.*``) decode naps better
than the current default, the night model with ``stageLogBiasShort``? And which nap output policy should be the
default?

Nap proxies v2 (:func:`proxies_of`), cut from every OHealth-like night of both headline variants:

- ONSET proxies (:data:`ONSET_LENGTHS`, ``naps.nap_proxy`` unchanged): the first L minutes of the night's session,
  30 min of context before, nothing after the end;
- ENDS-IN-WAKE windows (:func:`sample_windows`): a sleep segment from the end of a wake bout of >= 2 min to the start
  of the next wake bout of >= 5 min, with the OHealth-like margins (up to 5 min of the opening bout, the first 5 min of
  the closing awakening), 30-179 min long, up to 30 min of context before and nothing after the end; at most
  :data:`EW_CAP` non-overlapping windows per night in a deterministic (SHA-256) order shared by the two variants.

Candidates for naps, per held-out subject (LOSO over the 78 subjects of ``walch+bid-zero``; outer groups and inner
folds of run ``bias-base``):

- ``N0``: the current default at fold level: the outer fold night bundle + b_g + b_short_g(180), no nap section;
- ``N1``: a nap model fitted on the training subjects' proxies (both kinds, rows of both variants), classifier type
  (logreg / MLP 2x32), C and decoder parameters chosen per (variant, group) by nested inner CV (mean per-subject
  kappa of the inner held-out Walch subjects' proxies), its stageLogBias fitted by ``bias.fit_stage_bias`` on the
  inner out-of-fold decodes, installed as the ``nap`` section of the N0 fold bundle;
- ``N2``: N1 without the nap stageLogBias;
- context: RULES, HMM-table and "all LIGHT"; every candidate also scored as SLEEP_WAKE output (wake vs sleep).

Rule (i) (:func:`adopt_model`) and rule (ii) (:func:`choose_policy`) are the pre-registered ones; everything else is
reported with paired per-subject bootstrap CIs. Before any nap model is fitted, :func:`run_experiment` checks that N0
reproduces the stored whole nights (``bias-base/bias`` candidate B) and onset proxies (``naps`` C-180) and that the
Python cut of the onset proxies gives exactly the features of algo-cli's own ``--nap-proxies`` cut; the N1 / N2
evaluations include the whole nights, which must stay identical to candidate B (a nap section never changes a night).
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import logging
import math
import shutil
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Callable, Iterable, Mapping, Sequence

import numpy as np

from . import loso, naps, report
from .algocli import AlgoCli
from .bias import fit_stage_bias
from .bundle import (build_bundle, nap_section, validate_bundle, with_nap_model, with_stage_log_bias,
                     with_stage_log_bias_short, write_bundle)
from .features import FeatureTable, concat, read_features
from .models import TrainedClassifier, fit_classifier, log_loss
from .nightset import NightIndex
from .stats import fit_label_stats
from .util import AWAKE, LIGHT, N_STAGES, STAGES, canonical_json, code_fingerprint, deep_merge, read_json, write_json

log = logging.getLogger(__name__)

PREREG_PATH = "docs/prereg/prereg-nap-model.md"
PREREG_SHA256 = "b79de349c58adf2459de89955b72a1433e3d3eb79664b647b3ab5bceb476d290"

ONSET_LENGTHS: tuple[int, ...] = (30, 60, 90, 120, 150, 170)       # onset proxies, minutes
GATE_LENGTHS: tuple[int, ...] = (30, 60, 90, 120)                   # reproduced from ``naps`` (C-180)
POSTHOC_LENGTHS: tuple[int, ...] = (150, 170)                       # compared with ``naps-posthoc`` (reported)
MINUTE_MS = 60_000
OPEN_MIN_MS = 2 * MINUTE_MS            # an opening wake bout lasts at least 2 min
CLOSE_MIN_MS = 5 * MINUTE_MS           # the closing awakening lasts at least 5 min
MARGIN_MS = 5 * MINUTE_MS              # OHealth-like margins of prepare.py
EW_MIN_MS = 30 * MINUTE_MS
EW_MAX_MS = 179 * MINUTE_MS
EW_CAP = 3                             # ends-in-wake windows per night
EW_BINS: tuple[int, ...] = (30, 60, 90, 120, 150)                   # length bins [lo, lo + 30) of the ew windows
VARIANT_SUFFIXES = ("-inst", "-mean2min")
PROXY_KINDS = ("onset", "ew")
NAP_MAX_SESSION_MIN = 180.0
CANDIDATES: tuple[str, ...] = ("N0", "N1", "N2")
MODEL_CANDIDATES: tuple[str, ...] = ("N1", "N2")                    # rule (i) order for ties
CONTEXT_ESTIMATORS: tuple[str, ...] = ("RULES", "HMM-table")
LIGHT_KIND = "all-LIGHT"
NAMES: tuple[str, ...] = (*CANDIDATES, *CONTEXT_ESTIMATORS, LIGHT_KIND)
CANDIDATE_LABEL = {"N0": "current default (night model + stageLogBiasShort)", "N1": "nap model + nap stageLogBias",
                   "N2": "nap model, no stageLogBias", "RULES": "RULES (context)", "HMM-table": "HMM-table (context)",
                   LIGHT_KIND: "all LIGHT (reference)"}
CLASSIFIER_ORDER: tuple[str, ...] = ("lr", "mlp")                    # inner-CV ties: logreg first
GRID = loso.GRIDS["full"]
ACC_GAIN = 0.02                        # rule (i)
ACC_TIE = 0.0005
POLICY_ACC_MARGIN = 0.03               # rule (ii)
POLICY_KAPPA = 0.15
POLICY_SW_KAPPA = 0.20
POLICIES = ("STAGES", "SLEEP_WAKE", "SKIP")
PAIRS: tuple[tuple[str, str], ...] = (("N1", "N0"), ("N2", "N0"), ("N1", "N2"), ("RULES", "N0"), ("HMM-table", "N0"),
                                      (LIGHT_KIND, "N0"))
COMPARE_PARTS = ("all", "onset", "ew")
TRAIN_VARIANT_ORDER = ("instantaneous", "mean2min")                  # canonical row order of every fit
DATASETS = naps.DATASETS


class NapModelError(RuntimeError):
    """The nap-model experiment cannot run (inputs missing) or failed a consistency check."""


# ------------------------------------------------------------------------------------------------ proxies ----

def night_key(night_id: str) -> str:
    """The night id without its variant suffix (``-inst`` / ``-mean2min``): both variants share the key."""
    for s in VARIANT_SUFFIXES:
        if night_id.endswith(s):
            return night_id[: -len(s)]
    return night_id


def wake_bouts(labels: Iterable[Sequence]) -> list[tuple[int, int]]:
    """Maximal runs of consecutive AWAKE label epochs, (start ms, end ms); another stage or a gap ends a run."""
    out: list[tuple[int, int]] = []
    cur: list[int] | None = None
    for a, b, st in sorted(((int(x[0]), int(x[1]), str(x[2])) for x in labels), key=lambda x: (x[0], x[1])):
        if st == "AWAKE":
            if cur is not None and cur[1] == a:
                cur[1] = b
                continue
            if cur is not None:
                out.append((cur[0], cur[1]))
            cur = [a, b]
        elif cur is not None:
            out.append((cur[0], cur[1]))
            cur = None
    if cur is not None:
        out.append((cur[0], cur[1]))
    return out


def ew_candidates(night: Mapping) -> list[tuple[int, int]]:
    """Every ends-in-wake session (s0, s1) of ``night``: for each opening wake bout o (>= 2 min) the closing bout c is
    the first wake bout of >= 5 min starting at or after o's end; s0 = end(o) - min(5 min, len(o)), s1 = start(c) + 5
    min; kept when 30 min <= s1 - s0 <= 179 min and the session lies inside the night's session."""
    bouts = wake_bouts(night.get("labels", []))
    t0, t1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])
    out = []
    for i, (o0, o1) in enumerate(bouts):
        if o1 - o0 < OPEN_MIN_MS:
            continue
        close = next((c for c in bouts[i + 1:] if c[0] >= o1 and c[1] - c[0] >= CLOSE_MIN_MS), None)
        if close is None:
            continue
        s0 = o1 - min(MARGIN_MS, o1 - o0)
        s1 = close[0] + MARGIN_MS
        if EW_MIN_MS <= s1 - s0 <= EW_MAX_MS and s0 >= t0 and s1 <= t1:
            out.append((s0, s1))
    return out


def window_order_key(night: Mapping, s0: int) -> str:
    """Sampling order of a window: SHA-256 of ``<night key>|<s0 - night session start in ms>``."""
    off = int(s0) - int(night["sessionStartMs"])
    return hashlib.sha256(f"{night_key(str(night['id']))}|{off}".encode("utf-8")).hexdigest()


def sample_windows(night: Mapping, cap: int = EW_CAP) -> list[tuple[int, int]]:
    """At most ``cap`` non-overlapping ends-in-wake windows of ``night``, taken greedily in :func:`window_order_key`
    order; returned by start time."""
    cands = sorted(ew_candidates(night), key=lambda w: (window_order_key(night, w[0]), w))
    taken: list[tuple[int, int]] = []
    for w in cands:
        if len(taken) >= cap:
            break
        if all(w[1] <= x[0] or x[1] <= w[0] for x in taken):
            taken.append(w)
    return sorted(taken)


def cut_window(night: Mapping, s0: int, s1: int, proxy_id: str) -> dict:
    """The session [s0, s1] of ``night`` with the cut of ``naps.nap_proxy``: heart rate of [s0 - 30 min, s1], step
    records ending in (s0 - 30 min, s1], label epochs fully inside; nothing after s1."""
    extra = sorted(set(night) - set(naps.NIGHT_KEYS))
    if extra:
        raise NapModelError(f"{night.get('id')}: unexpected night fields {extra}")
    if not int(night["sessionStartMs"]) <= s0 < s1 <= int(night["sessionEndMs"]):
        raise NapModelError(f"{night.get('id')}: window [{s0}, {s1}] outside the session")
    c0 = s0 - naps.CONTEXT_MS
    return {"id": proxy_id, "subject": night["subject"], "variant": night.get("variant"),
            "sessionStartMs": int(s0), "sessionEndMs": int(s1),
            "hr": [h for h in night["hr"] if c0 <= h[0] <= s1],
            "steps": [s for s in night.get("steps", []) if c0 < s[1] <= s1],
            "labels": [lab for lab in night.get("labels", []) if lab[0] >= s0 and lab[1] <= s1]}


def ew_id(night: Mapping, s0: int) -> str:
    return f"{night['id']}-ew{(int(s0) - int(night['sessionStartMs'])) / MINUTE_MS:g}"


def proxies_of(night: Mapping, cap: int = EW_CAP) -> list[tuple[dict, dict]]:
    """(proxy session, meta) of ``night``: the onset proxies of :data:`ONSET_LENGTHS`, then its ends-in-wake windows.
    meta = ``{"proxy": "onset" | "ew", "L": minutes, "offsetMin": start - night start in minutes}``."""
    out = []
    for L in ONSET_LENGTHS:
        p = naps.nap_proxy(night, L)
        if p is None:
            raise NapModelError(f"{night['id']}: session shorter than {L} min, no onset proxy")
        out.append((p, {"proxy": "onset", "L": L, "offsetMin": 0.0}))
    for s0, s1 in sample_windows(night, cap):
        out.append((cut_window(night, s0, s1, ew_id(night, s0)),
                    {"proxy": "ew", "L": (s1 - s0) / MINUTE_MS,
                     "offsetMin": (s0 - int(night["sessionStartMs"])) / MINUTE_MS}))
    return out


def ew_bin(length_min: float) -> str:
    for lo in EW_BINS:
        if lo <= length_min < lo + 30:
            return f"ew-{lo}-{lo + 29}"
    raise NapModelError(f"ends-in-wake length {length_min} min outside 30-179")


def parts_of(rec: Mapping) -> list[str]:
    """The report parts a proxy record belongs to: all, its kind, its length (onset) or length bin (ew)."""
    if rec["proxy"] == "onset":
        return ["all", "onset", f"onset-{int(rec['L'])}"]
    if rec["proxy"] == "ew":
        return ["all", "ew", ew_bin(float(rec["L"]))]
    return []


PART_ORDER: tuple[str, ...] = ("all", "onset", *(f"onset-{L}" for L in ONSET_LENGTHS), "ew",
                               *(f"ew-{lo}-{lo + 29}" for lo in EW_BINS))


# ------------------------------------------------------------------------------------------------ metrics ----

def collapse_sleep_wake(confusion) -> np.ndarray:
    """The 4 x 4 matrix (rows = truth AWAKE, LIGHT, DEEP, REM) as 2 x 2 wake / sleep (the SLEEP_WAKE output)."""
    c = np.asarray(confusion, dtype=float)
    return np.array([[c[AWAKE, AWAKE], c[AWAKE, 1:].sum()], [c[1:, AWAKE].sum(), c[1:, 1:].sum()]])


def wake_scores(c2: np.ndarray) -> dict:
    """Wake precision / recall / F1 of a 2 x 2 wake / sleep matrix (NaN where undefined)."""
    tp, fn, fp = float(c2[0, 0]), float(c2[0, 1]), float(c2[1, 0])
    nan = float("nan")
    return {"precision": tp / (tp + fp) if tp + fp > 0 else nan, "recall": tp / (tp + fn) if tp + fn > 0 else nan,
            "f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn > 0 else nan}


def all_light(recs: Sequence[Mapping]) -> list[dict]:
    """The trivial reference: the records with every scored chunk predicted LIGHT (from their labels)."""
    out = []
    for r in recs:
        c = None
        if r["confusion"] is not None:
            m = np.asarray(r["confusion"], float)
            z = np.zeros_like(m)
            z[:, LIGHT] = m.sum(axis=1)
            c = z.tolist()
        out.append({**r, "candidate": LIGHT_KIND, "warnings": [], "confusion": c})
    return out


def _finite_mean(v: Iterable[float]) -> float:
    xs = [float(x) for x in v if x is not None and math.isfinite(float(x))]
    return float(np.mean(xs)) if xs else float("nan")


def cell(recs: Sequence[Mapping]) -> dict:
    """Metrics of a set of proxy sessions (``naps.length_cell`` plus the sleep / wake and "all LIGHT" numbers):
    accuracy pooled over the scored chunks, E = mean per-session summed share error, kappa = mean per-subject kappa
    (the subject's sessions pooled), sleep / wake kappa likewise on the collapsed matrices, wake F1 and sleep / wake
    accuracy of the pooled collapsed matrix, "all LIGHT" accuracy = the LIGHT share of the true labels of the same
    chunks; per-subject values for the paired bootstrap."""
    c = naps.length_cell(recs)
    rs = naps._scored(recs)
    pooled = np.asarray(c["confusion"], float)
    tot = pooled.sum()
    c2 = collapse_sleep_wake(pooled) if tot > 0 else np.zeros((2, 2))
    subj = naps._subject_pooled(rs)
    sw_k = {s: report.kappa(collapse_sleep_wake(m)) for s, m in subj.items()}
    sw_f1 = {s: wake_scores(collapse_sleep_wake(m))["f1"] for s, m in subj.items()}
    light = {s: float(m[LIGHT].sum() / m.sum()) for s, m in subj.items()}
    ws = wake_scores(c2)
    c.update({
        "lightAccuracy": float(pooled[LIGHT].sum() / tot) if tot > 0 else float("nan"),
        "swKappa": _finite_mean(sw_k.values()), "swKappaDefined": sum(1 for x in sw_k.values() if math.isfinite(x)),
        "swKappaPooled": report.kappa(c2) if tot > 0 else float("nan"),
        "swAccuracy": float(np.trace(c2) / tot) if tot > 0 else float("nan"),
        "wakeF1": ws["f1"], "wakePrecision": ws["precision"], "wakeRecall": ws["recall"],
        "subjectSwKappa": sw_k, "subjectWakeF1": sw_f1, "subjectLightAccuracy": light,
    })
    return c


def cells_of(recs: Sequence[Mapping], names: Sequence[str] = NAMES,
             variants: Sequence[str] = report.HEADLINE_VARIANTS) -> dict:
    """cells[name][dataset][variant][part] of the proxy records (nights are left out)."""
    groups: dict[tuple, list] = defaultdict(list)
    for r in recs:
        for p in parts_of(r):
            groups[(r["candidate"], r["dataset"], r["variant"], p)].append(r)
    out: dict = {}
    for name in names:
        for ds in DATASETS:
            for v in variants:
                per = {p: cell(groups[(name, ds, v, p)]) for p in PART_ORDER if groups.get((name, ds, v, p))}
                if per:
                    out.setdefault(name, {}).setdefault(ds, {})[v] = per
    return out


def rule_scores(cells: Mapping, variants: Sequence[str] = report.HEADLINE_VARIANTS,
                dataset: str = report.HEADLINE_DATASET, part: str = "all", names: Sequence[str] = NAMES) -> dict:
    """Per name: the pooled values per variant and their worse values (accuracy, kappas, accuracy margin over "all
    LIGHT": min; E: max)."""
    out = {}
    for n in names:
        cv = cells.get(n, {}).get(dataset, {})
        if not all(v in cv and part in cv[v] for v in variants):
            continue
        q = {v: cv[v][part] for v in variants}
        acc = {v: q[v]["accuracy"] for v in variants}
        e = {v: q[v]["E"] for v in variants}
        k4 = {v: q[v]["kappa"] for v in variants}
        sw = {v: q[v]["swKappa"] for v in variants}
        margin = {v: q[v]["accuracy"] - q[v]["lightAccuracy"] for v in variants}
        out[n] = {"accuracy": acc, "E": e, "kappa": k4, "swKappa": sw, "accMargin": margin,
                  "lightAccuracy": {v: q[v]["lightAccuracy"] for v in variants},
                  "wakeF1": {v: q[v]["wakeF1"] for v in variants},
                  "swAccuracy": {v: q[v]["swAccuracy"] for v in variants},
                  "accWorse": min(acc.values()), "accWorseVariant": min(acc, key=acc.get),
                  "EWorse": max(e.values()), "EWorseVariant": max(e, key=e.get),
                  "kappaWorse": _nanmin(k4.values()), "swKappaWorse": _nanmin(sw.values()),
                  "accMarginWorse": min(margin.values()), "sessions": {v: q[v]["sessions"] for v in variants}}
    return out


def _nanmin(v: Iterable[float]) -> float:
    xs = [float(x) for x in v]
    return float("nan") if any(not math.isfinite(x) for x in xs) or not xs else min(xs)


def adopt_model(scores: Mapping[str, Mapping], gain: float = ACC_GAIN, tie: float = ACC_TIE,
                order: Sequence[str] = MODEL_CANDIDATES) -> dict:
    """Rule (i): among the nap-model candidates with E worse <= N0's and accuracy worse >= N0's + ``gain``, the one with
    the highest accuracy worse; ties (within ``tie``) -> lower E worse, then ``order``; none -> N0."""
    ref = scores["N0"]
    ok = [c for c in order if c in scores and scores[c]["EWorse"] <= ref["EWorse"]
          and scores[c]["accWorse"] >= ref["accWorse"] + gain]
    adopted = "N0"
    tied: list[str] = []
    if ok:
        top = max(scores[c]["accWorse"] for c in ok)
        tied = [c for c in ok if top - scores[c]["accWorse"] < tie]
        adopted = min(tied, key=lambda c: (scores[c]["EWorse"], list(order).index(c)))
    return {"adopted": adopted, "qualifying": ok, "tied": tied, "referenceAccuracy": ref["accWorse"],
            "accuracyThreshold": ref["accWorse"] + gain, "referenceE": ref["EWorse"],
            "rule": "Walch, worse variant, pooled over proxies of both kinds: the nap-model candidate with the highest "
                    "accuracy among those whose summed share error <= N0's and whose accuracy >= N0's + 0.02; else N0"}


def choose_policy(s: Mapping, acc_margin: float = POLICY_ACC_MARGIN, kappa_min: float = POLICY_KAPPA,
                  sw_kappa_min: float = POLICY_SW_KAPPA) -> dict:
    """Rule (ii) on the rule scores ``s`` of the adopted candidate: STAGES if accuracy margin over "all LIGHT" >= 0.03
    and 4-class kappa >= 0.15 (worse variant each); otherwise SLEEP_WAKE if sleep / wake kappa >= 0.20; else SKIP."""
    stages = s["accMarginWorse"] >= acc_margin and s["kappaWorse"] >= kappa_min
    sw = (not stages) and s["swKappaWorse"] >= sw_kappa_min
    return {"policy": "STAGES" if stages else ("SLEEP_WAKE" if sw else "SKIP"),
            "accMarginWorse": s["accMarginWorse"], "kappaWorse": s["kappaWorse"], "swKappaWorse": s["swKappaWorse"],
            "stagesCriteria": {"accMargin": s["accMarginWorse"] >= acc_margin, "kappa": s["kappaWorse"] >= kappa_min},
            "sleepWakeCriterion": s["swKappaWorse"] >= sw_kappa_min,
            "rule": "STAGES if accuracy >= all-LIGHT accuracy + 0.03 AND 4-class kappa >= 0.15; otherwise SLEEP_WAKE if "
                    "sleep/wake kappa >= 0.20; otherwise SKIP (Walch, worse variant, both kinds pooled)"}


METRIC_KEYS = {"accuracy": "subjectAccuracy", "E": "subjectE", "kappa": "subjectKappa", "swKappa": "subjectSwKappa",
               "wakeF1": "subjectWakeF1"}


def comparisons(cells: Mapping, variants: Sequence[str] = report.HEADLINE_VARIANTS,
                pairs: Sequence[tuple[str, str]] = PAIRS, parts: Sequence[str] = COMPARE_PARTS) -> list[dict]:
    """Paired per-subject differences a - b (95 % bootstrap CI, 10 000 resamples, seed 0) of every metric of
    :data:`METRIC_KEYS`, per dataset, variant and part."""
    out = []
    for ds in DATASETS:
        for a, b in pairs:
            for v in variants:
                ca, cb = cells.get(a, {}).get(ds, {}).get(v), cells.get(b, {}).get(ds, {}).get(v)
                if not ca or not cb:
                    continue
                for p in parts:
                    if p not in ca or p not in cb:
                        continue
                    out.append({"a": a, "b": b, "dataset": ds, "variant": v, "part": p,
                                **{m: report.paired_bootstrap(ca[p][k], cb[p][k]) for m, k in METRIC_KEYS.items()}})
    return out


def light_margins(cells: Mapping, names: Sequence[str] = CANDIDATES,
                  variants: Sequence[str] = report.HEADLINE_VARIANTS) -> list[dict]:
    """Paired per-subject accuracy - "all LIGHT" accuracy of each candidate (rule (ii)), per dataset and variant."""
    out = []
    for ds in DATASETS:
        for n in names:
            for v in variants:
                c = cells.get(n, {}).get(ds, {}).get(v, {}).get("all")
                if c:
                    out.append({"name": n, "dataset": ds, "variant": v,
                                "margin": report.paired_bootstrap(c["subjectAccuracy"], c["subjectLightAccuracy"])})
    return out


# --------------------------------------------------------------------------------------------- inputs ----

@dataclass
class Group:
    """One (variant, outer group) of run ``bias-base``: night decoder, fold-level b / b_short, subjects, inner folds."""
    variant: str
    key: str
    theta: dict                        # night decoder theta_g
    bias: list[float]                  # b_g (bias-base/bias candidate B)
    bias_short: list[float]            # b_short_g(180) (naps/fits.json)
    heldout: list[str]
    subjects: list[str]                # selection set: the pool minus the group
    inner_folds: list[list[str]]       # inner held-out subjects per inner fold
    eval_subjects: list[str]           # Walch subjects of the selection set (inner scoring)


class Inputs:
    """Read-only inputs: run ``bias-base`` (fold bundles, groups, selection sets), ``naps`` (b_short_g(180), the stored
    C-180 proxies), ``naps-posthoc`` (150 / 170-min proxies) and the night files."""

    def __init__(self, run_dir: Path, naps_dir: Path, nights_root: Path, posthoc_dir: Path | None = None,
                 variants: Sequence[str] = report.HEADLINE_VARIANTS):
        self.base = naps.Inputs(run_dir, nights_root, variants)
        self.run_dir = Path(run_dir)
        self.naps_dir = Path(naps_dir)
        self.posthoc_dir = Path(posthoc_dir) if posthoc_dir is not None else None
        self.variants = tuple(variants)
        self.cfg = self.base.cfg
        self.index: NightIndex = self.base.index
        fits = read_json(self.naps_dir / "fits.json")["fits"]
        short = {(f["variant"], f["key"]): f["bias"] for f in fits if int(f["T"]) == int(NAP_MAX_SESSION_MIN)}
        self.groups: list[Group] = []
        for g in self.base.groups:
            if (g.variant, g.key) not in short:
                raise NapModelError(f"{g.variant}/{g.key}: no b_short_g(180) in {self.naps_dir / 'fits.json'}")
            sel = read_json(self.base.hybrid_dir(g.variant) / "selection" / f"{g.key}.json")
            if set(sel["subjects"]) & set(g.heldout):
                raise NapModelError(f"{g.variant}/{g.key}: selection set contains held-out subjects")
            self.groups.append(Group(variant=g.variant, key=g.key, theta=dict(g.theta), bias=list(g.bias),
                                     bias_short=[float(x) for x in short[(g.variant, g.key)]],
                                     heldout=list(g.heldout), subjects=sorted(sel["subjects"]),
                                     inner_folds=[sorted(x) for x in sel["innerFolds"]],
                                     eval_subjects=sorted(sel["evalSubjects"])))
        for v in self.variants:
            covered = sorted(s for g in self.groups if g.variant == v for s in g.heldout)
            want = self.index.subjects(None, v)
            if covered != want:
                raise NapModelError(f"{v}: the outer groups cover {len(covered)} of {len(want)} subjects")
        self.pool = self.index.subjects(None, self.variants[0])

    def night(self, variant: str, night_id: str) -> dict:
        return self.base.night(variant, night_id)

    def folds(self, variant: str) -> Path:
        return self.base.folds(variant)

    def eval_params(self, theta_night: Mapping, theta_nap: Mapping) -> dict:
        """The common evaluation parameters (night decoder theta_g, beta 1, the nap mode set explicitly)."""
        return deep_merge(self.base.params(theta_night), {"napMode": {
            "maxSessionMin": NAP_MAX_SESSION_MIN, "estimator": "HYBRID", "outputPolicy": "STAGES",
            "hybrid": {k: theta_nap[k] for k in ("durationModel", "emissionWeight", "timePriorWeight")}}})

    def context_params(self, estimator: str) -> dict:
        est = "RULES" if estimator == "RULES" else "HMM"
        p = {"estimator": est} if est == "RULES" else deep_merge({"estimator": "HMM"}, loso.physiology_hmm_patch())
        p = deep_merge(p, {"napMode": {"maxSessionMin": NAP_MAX_SESSION_MIN, "estimator": est,
                                       "outputPolicy": "STAGES"}})
        return deep_merge(dict(self.cfg.base_params), p)


def grid_points(base: Mapping, grid: Mapping = GRID) -> list[dict]:
    """The decoder grid as complete parameter sets with ``napMode.hybrid`` set to each point (order = ties)."""
    pts = []
    for i, p in enumerate(loso.grid_points(grid)):
        h = p["hybrid"]
        params = deep_merge(dict(base), {"napMode": {"hybrid": dict(h)}})
        pts.append({"id": f"g{i:03d}", "index": i, "hybrid": dict(h), "params": params})
    return pts


# ----------------------------------------------------------------------------------- proxy data, features ----

@dataclass
class Data:
    """Proxy sessions, their meta and algo-cli features; the training rows of every fit (instantaneous first)."""
    proxies: dict[str, list[dict]]                 # variant -> proxy sessions
    meta: dict[str, dict[str, dict]]               # variant -> proxy id -> meta
    tables: dict[str, FeatureTable]                # variant -> features of the proxies
    feature_names: list[str]
    fsv: int
    X: np.ndarray                                  # training rows (HR data + label) of both variants
    y: np.ndarray
    subject: np.ndarray
    variant: np.ndarray


_DATA: Data | None = None
_SPEC: Callable | None = None


def build_proxies(inp: Inputs) -> tuple[dict, dict, dict]:
    """(proxies, meta, counts) of every night of every variant; the ends-in-wake windows of both variants agree."""
    proxies: dict[str, list[dict]] = {}
    meta: dict[str, dict[str, dict]] = {}
    windows: dict[str, dict[str, tuple[str, list]]] = {}
    for v in inp.variants:
        proxies[v], meta[v], windows[v] = [], {}, {}
        for n in inp.index.for_variant(v):
            night = inp.night(v, n.id)
            items = proxies_of(night)
            windows[v][night_key(n.id)] = (n.dataset, [(m["offsetMin"], m["L"]) for _, m in items
                                                       if m["proxy"] == "ew"])
            for p, m in items:
                proxies[v].append(p)
                meta[v][p["id"]] = {"base": n.id, "subject": n.subject, "dataset": n.dataset, "variant": v, **m}
    vs = list(inp.variants)
    for v in vs[1:]:
        if windows[v] != windows[vs[0]]:
            bad = [k for k in windows[v] if windows[v][k] != windows[vs[0]].get(k)]
            raise NapModelError(f"ends-in-wake windows differ between {vs[0]} and {v}, e.g. {bad[:3]}")
    counts: dict = {}
    for v in vs:
        for ds in DATASETS:
            ms = [m for m in meta[v].values() if m["dataset"] == ds]
            ew = [m for m in ms if m["proxy"] == "ew"]
            per_night = Counter(len(w) for d_, w in windows[v].values() if d_ == ds)
            counts.setdefault(v, {})[ds] = {
                "nights": sum(per_night.values()), "onset": sum(1 for m in ms if m["proxy"] == "onset"),
                "ew": len(ew), "ewPerNight": {str(k): per_night[k] for k in sorted(per_night)},
                "ewLengthMin": {"mean": float(np.mean([m["L"] for m in ew])) if ew else float("nan"),
                                "min": min((m["L"] for m in ew), default=float("nan")),
                                "max": max((m["L"] for m in ew), default=float("nan"))},
                "ewBins": dict(Counter(ew_bin(m["L"]) for m in ew)),
                "ewSubjects": len({m["subject"] for m in ew})}
    return proxies, meta, counts


def _session_line(n: Mapping) -> str:
    return json.dumps(n, separators=(",", ":")) + "\n"


def write_sessions(path: Path, sessions: Sequence[Mapping]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for n in sessions:
            f.write(_session_line(n))
    return path


def check_sessions_file(path: Path, sessions: Sequence[Mapping]) -> None:
    """Train / inference parity of the nap pipeline: the training rows are the algo-cli features of the session file
    ``path`` (written by :func:`compute_features`), and every decode runs algo-cli on ``sessions`` (the proxies as they
    are decoded). No transformation lies between the features and the training matrix, so parity holds exactly when
    ``path`` holds byte for byte the serialization of ``sessions``; raises :class:`NapModelError` otherwise (features of
    another cut, a stale or edited file)."""
    want, got = hashlib.sha256(), hashlib.sha256()
    for n in sessions:
        want.update(_session_line(n).encode("utf-8"))
    if path.is_file():
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                got.update(chunk)
    if not path.is_file() or got.hexdigest() != want.hexdigest():
        raise NapModelError(f"train / inference parity: {path} is not the session file of the decoded proxies")


def _csv_rows_by_id(path: Path, keep: Callable[[str], bool]) -> tuple[str, dict[str, list[str]]]:
    with open(path, encoding="utf-8") as f:
        header = f.readline()
        rows: dict[str, list[str]] = defaultdict(list)
        for line in f:
            nid = next(csv.reader(io.StringIO(line)))[0]
            if keep(nid):
                rows[nid].append(line)
    return header, rows


def compute_features(inp: Inputs, cli: AlgoCli, proxies: Mapping[str, list[dict]], out: Path, jobs: int) -> dict:
    """algo-cli features of the proxies (Python cut) per variant, and the parity check against algo-cli's own
    ``--nap-proxies`` cut of the onset proxies (every row, every field); raises on any difference."""
    base_file = write_json(out / "base-params.json", dict(inp.cfg.base_params)) if inp.cfg.base_params else None
    lengths = ",".join(str(L) for L in ONSET_LENGTHS)

    def one(v: str) -> dict:
        nf = write_sessions(out / "proxies" / f"{v}.jsonl", proxies[v])
        csv_path = cli.features(nf, out / "features" / f"{v}.csv", params=base_file)
        kt = out / "features" / f"{v}.kotlin-onset.csv"
        args = ["features", "--nights", str(inp.base.nights_root / v), "--nap-proxies", lengths, "--out", str(kt)]
        if base_file is not None:
            args += ["--params", str(base_file)]
        cli.run(args)
        is_onset = (lambda nid: any(nid.endswith(f"-nap{L}") for L in ONSET_LENGTHS))
        h1, py = _csv_rows_by_id(csv_path, is_onset)
        h2, kt_rows = _csv_rows_by_id(kt, is_onset)
        diff = sorted(k for k in set(py) | set(kt_rows) if py.get(k) != kt_rows.get(k))
        res = {"variant": v, "onsetProxies": len(py), "kotlinOnsetProxies": len(kt_rows), "headerEqual": h1 == h2,
               "rowsCompared": sum(len(x) for x in py.values()), "differing": diff[:10], "nDiffering": len(diff)}
        kt.unlink(missing_ok=True)
        if h1 != h2 or diff or len(py) != len(kt_rows):
            raise NapModelError(f"feature parity failed for {v}: {res}")
        return res

    return {"parity": loso.map_threads(one, list(inp.variants), max(1, min(jobs, len(inp.variants))))}


def load_data(inp: Inputs, cli: AlgoCli, proxies: Mapping, meta: Mapping, out: Path) -> Data:
    """Feature tables of the proxies; feature set checked against ``algo-cli feature-names``, the session files against
    the proxies that are decoded (:func:`check_sessions_file`); the training rows."""
    info = cli.feature_names()
    tables = {v: read_features(out / "features" / f"{v}.csv", int(info["featureSetVersion"]), variant=v)
              for v in inp.variants}
    names = tables[inp.variants[0]].feature_names
    if list(info["featureNames"]) != list(names) or any(t.feature_names != names for t in tables.values()):
        raise NapModelError("feature columns differ from algo-cli feature-names")
    for v, t in tables.items():
        if set(t.night_id.tolist()) != set(meta[v]):
            raise NapModelError(f"features of {v}: sessions differ from the proxies")
        got = dict(zip(t.night_id.tolist(), t.subject.tolist()))
        if any(got[k] != meta[v][k]["subject"] for k in meta[v]):
            raise NapModelError(f"features of {v}: subject column differs from the proxies")
    for v in inp.variants:
        check_sessions_file(out / "proxies" / f"{v}.jsonl", proxies[v])
    order = [v for v in TRAIN_VARIANT_ORDER if v in tables]
    t = concat([tables[v] for v in order])
    rows = t.training_rows()
    # The training rows are the algo-cli features as they are (nothing masked or imputed), so checking the session
    # file above is the train / inference parity check of this pipeline; loso.check_train_inference_parity guards a
    # transformation that this pipeline does not have.
    X = t.X[rows].copy()
    return Data(proxies=dict(proxies), meta=dict(meta), tables=tables, feature_names=list(names),
                fsv=int(info["featureSetVersion"]), X=X, y=t.y[rows].astype(np.int64), subject=t.subject[rows],
                variant=t.variant[rows])


# ------------------------------------------------------------------------------------------------ fitting ----

FitKey = tuple[tuple[str, ...], str, float | None]


def _fit_task(task: FitKey) -> tuple[FitKey, TrainedClassifier]:
    """Fits the classifier ``task`` = (training subjects, lr | mlp, C) on the training rows (forked worker)."""
    d = _DATA
    if d is None or _SPEC is None:
        raise RuntimeError("nap-model data not initialised")
    subjects, kind, C = task
    m = np.isin(d.subject, np.array(subjects, dtype=object))
    tc = fit_classifier(d.X[m], d.y[m], _SPEC(kind, C))
    return task, replace(tc, model=None)


def fit_all(tasks: Iterable[FitKey], jobs: int) -> dict[FitKey, TrainedClassifier]:
    todo = sorted(set(tasks), key=lambda t: (t[1], -1.0 if t[2] is None else float(t[2]), t[0]))
    t0 = time.monotonic()
    res = dict(loso.map_processes(_fit_task, todo, jobs))
    log.info("%d classifier fits in %.1f s", len(todo), time.monotonic() - t0)
    return res


def label_stats(inp: Inputs, data: Data, variant: str, subjects: Iterable[str]):
    t = data.tables[variant].for_subjects(subjects)
    return fit_label_stats(t.label_sequences(), t.epoch_min, inp.cfg.stats)


def nap_bundle(inp: Inputs, data: Data, tc: TrainedClassifier, variant: str, subjects: Sequence[str], model_id: str,
               notes: Sequence[str], created_at: str, stats=None) -> dict:
    """A complete bundle of a nap model (classifier ``tc``, statistics of ``subjects``' proxies of ``variant``)."""
    stats = stats if stats is not None else label_stats(inp, data, variant, subjects)
    ds, lic = dataset_description(inp, {inp.index.dataset_of(s) for s in subjects})
    sessions = sum(1 for m in data.meta[variant].values() if m["subject"] in set(subjects))
    nn = [*notes, tc.spec.describe(), "training rows: proxies (onset + ends-in-wake) of both variants "
          f"({', '.join(TRAIN_VARIANT_ORDER)}); statistics from the {variant} proxies", f"rows: {tc.info.get('rows')}",
          *stats.notes]
    return build_bundle(model_id=model_id, feature_set_version=data.fsv, feature_names=data.feature_names,
                        classifier=tc, label_stats=stats, hmm=None,
                        training={"dataset": f"nap proxies of {ds}", "license": lic, "subjects": len(set(subjects)),
                                  "nights": sessions, "variants": list(TRAIN_VARIANT_ORDER), "createdAt": created_at,
                                  "notes": "; ".join(nn)})


def dataset_description(inp: Inputs, datasets: Iterable[str]) -> tuple[str, str]:
    mp = inp.base.nights_root / "manifest.json"
    info = read_json(mp).get("datasets", {}) if mp.is_file() else {}
    names, lic = [], []
    for d in sorted(set(datasets), key=lambda x: (x != "walch", x)):
        i = info.get(d, {})
        names.append(f"{i.get('name', d)} {i.get('version', '')}".strip())
        if i.get("license") and i["license"] not in lic:
            lic.append(i["license"])
    return " + ".join(names), " / ".join(lic) if lic else "see tools/train/DATASETS.md"


def standalone(nap: Mapping, bias: Sequence[float] | None = None) -> dict:
    """An evaluation bundle whose main model and nap section are both ``nap`` (with nap stageLogBias ``bias``)."""
    b = with_stage_log_bias(nap, bias) if bias is not None else dict(nap)
    return with_nap_model(b, nap_section(b))


def n0_bundle(fold: Mapping, g: Group) -> dict:
    """The current default at fold level: the night fold bundle with b_g and b_short_g(180), no nap section."""
    return with_stage_log_bias_short(with_stage_log_bias(fold, g.bias), g.bias_short)


def n1_bundle(fold: Mapping, g: Group, nap: Mapping, nap_bias: Sequence[float] | None) -> dict:
    """N1 (``nap_bias`` given) / N2 (None): the N0 fold bundle with the nap model as its nap section."""
    sec = nap_section(with_stage_log_bias(nap, nap_bias) if nap_bias is not None else nap)
    return with_nap_model(n0_bundle(fold, g), sec)


def _write_bundles(d: Path, bundles: Mapping[str, Mapping]) -> Path:
    d.mkdir(parents=True, exist_ok=True)
    for s, b in bundles.items():
        (d / f"{s}.json").write_text(json.dumps(b, separators=(",", ":"), allow_nan=False), encoding="utf-8")
    return d


# ----------------------------------------------------------------------------------------- decoding ----

class Decoder:
    """algo-cli calls on proxy / night subsets with per-session tagging (``naps.Decoder`` underneath)."""

    def __init__(self, inp: Inputs, data: Data, cli: AlgoCli, out: Path):
        self.inp, self.data, self.cli, self.out = inp, data, cli, Path(out)
        self.dec = naps.Decoder(inp.base, cli, out)

    def sessions(self, variant: str, subjects: Iterable[str], nights: bool = False) -> tuple[list[dict], dict]:
        s = set(subjects)
        ps = [p for p in self.data.proxies[variant] if self.data.meta[variant][p["id"]]["subject"] in s]
        meta = {p["id"]: self.data.meta[variant][p["id"]] for p in ps}
        if nights:
            for n in self.inp.index.nights_of(variant, sorted(s)):
                ps.append(self.inp.night(variant, n.id))
                meta[n.id] = {"base": n.id, "subject": n.subject, "dataset": n.dataset, "variant": variant,
                              "proxy": "night", "L": None, "offsetMin": None}
        return ps, meta

    def evaluate(self, path: Path, sessions: Sequence[Mapping], meta: Mapping, estimator: str, params: Mapping,
                 fold_models: Path | None = None, extra: Mapping | None = None) -> list[dict]:
        nf = write_sessions(path.with_suffix(".sessions.jsonl"), sessions)
        try:
            return self.dec.evaluate(nf, meta, estimator, params, path, fold_models=fold_models, extra=extra)
        finally:
            nf.unlink(missing_ok=True)

    def evaluate_grid(self, path: Path, sessions: Sequence[Mapping], meta: Mapping, points: Sequence[Mapping],
                      fold_models: Path, extra: Mapping | None = None) -> list[list[dict]]:
        nf = write_sessions(path.with_suffix(".sessions.jsonl"), sessions)
        try:
            res = self.cli.evaluate_grid(nf, "HYBRID", path, points, fold_models=fold_models)
        finally:
            nf.unlink(missing_ok=True)
        return [tag(g["metrics"], meta, "HYBRID", extra) for g in res["grid"]]


def tag(metrics: Mapping, meta: Mapping[str, Mapping], estimator: str, extra: Mapping | None = None) -> list[dict]:
    """Per-session records of a metrics block (the checks of ``naps.Decoder.evaluate``)."""
    recs = report.parse_metrics(metrics)
    report.check_estimator_used(recs, "HYBRID" if estimator == "HYBRID" else estimator)
    if sorted(r["id"] for r in recs) != sorted(meta):
        raise NapModelError(f"algo-cli returned {len(recs)} sessions for {len(meta)} expected")
    bad = [r["id"] for r in recs if r["reason"] in ("NO_FOLD_MODEL", "NO_LABELS")]
    if bad:
        raise NapModelError(f"{len(bad)} sessions without fold model / labels, e.g. {bad[:3]}")
    return [{"night": r["id"], **meta[r["id"]], "estimated": r["estimated"], "reason": r["reason"],
             "estimatorUsed": r["estimatorUsed"], "warnings": list(r["warnings"]),
             "confusion": None if r["confusion"] is None else np.asarray(r["confusion"]).tolist(), **(extra or {})}
            for r in recs]


# --------------------------------------------------------------------------------- reproduction checks ----

def _read_jsonl(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


def _write_jsonl(path: Path, rows: Sequence[Mapping]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(naps._clean(r), separators=(",", ":"), allow_nan=False) + "\n")


def stored_nights(inp: Inputs) -> dict[tuple[str, str], dict]:
    """Candidate B (= A, the shipped default) of ``bias-base/bias/results.jsonl`` by (variant, night)."""
    out = {}
    for r in _read_jsonl(inp.base.bias_dir / "results.jsonl"):
        if r["candidate"] == "B" and r["variant"] in inp.variants:
            out[(r["variant"], r["night"])] = r
    return out


def stored_proxies(inp: Inputs) -> dict[tuple[str, str], dict]:
    """C-180 of the onset proxies: ``naps`` (L < 180, kind C-180) and ``naps-posthoc`` (150 / 170) by (variant, id)."""
    out = {}
    for r in _read_jsonl(inp.naps_dir / "results.jsonl"):
        if r["kind"] == "C-180" and r["L"] in GATE_LENGTHS and r["variant"] in inp.variants:
            out[(r["variant"], r["night"])] = r
    if inp.posthoc_dir is not None and (inp.posthoc_dir / "results.jsonl").is_file():
        for r in _read_jsonl(inp.posthoc_dir / "results.jsonl"):
            if r["kind"] == "C-180" and r["L"] in POSTHOC_LENGTHS and r["variant"] in inp.variants:
                out[(r["variant"], r["night"])] = r
    return out


def same_as(stored: Mapping | None, rec: Mapping, session_ms: int, check_min: float) -> bool:
    """``rec`` equals a stored decode (confusion, estimated, warnings; ``SANITY_*`` dropped on shorter sessions)."""
    if stored is None:
        return False
    want = naps.expected_warnings(stored["warnings"], session_ms, check_min)
    return stored["confusion"] == rec["confusion"] and stored["estimated"] == rec["estimated"] and want == rec["warnings"]


def check_records(recs: Sequence[Mapping], nights: Mapping, proxies: Mapping, sessions_ms: Mapping[tuple, int],
                  check_min: float) -> dict:
    """Counts of records identical to the stored nights / onset proxies (by length)."""
    res: dict = {"nights": 0, "nightsIdentical": 0, "byLength": {}, "mismatches": []}
    for r in recs:
        k = (r["variant"], r["night"])
        if r["proxy"] == "night":
            res["nights"] += 1
            ok = same_as(nights.get(k), r, sessions_ms[k], check_min)
            res["nightsIdentical"] += int(ok)
        elif r["proxy"] == "onset" and k in proxies:
            q = res["byLength"].setdefault(str(int(r["L"])), {"sessions": 0, "identical": 0})
            q["sessions"] += 1
            ok = same_as(proxies[k], r, sessions_ms[k], check_min)
            q["identical"] += int(ok)
        else:
            continue
        if not ok and len(res["mismatches"]) < 10:
            res["mismatches"].append(list(k))
    return res


# --------------------------------------------------------------------------------------- the experiment ----

def _session_ms(sessions: Iterable[Mapping], variant: str) -> dict[tuple[str, str], int]:
    return {(variant, s["id"]): int(s["sessionEndMs"]) - int(s["sessionStartMs"]) for s in sessions}


def _theta(h: Mapping) -> dict:
    return {"durationModel": str(h["durationModel"]), "emissionWeight": float(h["emissionWeight"]),
            "timePriorWeight": float(h["timePriorWeight"])}


def run_n0(inp: Inputs, dec: Decoder, out: Path, jobs: int) -> tuple[list[dict], dict]:
    """N0 on the held-out subjects' nights and proxies, per (variant, group), and the reproduction gate."""
    nights, proxies = stored_nights(inp), stored_proxies(inp)

    def one(g: Group):
        folds = inp.folds(g.variant)
        bundles = {s: n0_bundle(read_json(folds / f"{s}.json"), g) for s in g.heldout}
        d = _write_bundles(out / "n0" / g.variant / g.key, bundles)
        ss, meta = dec.sessions(g.variant, g.heldout, nights=True)
        try:
            recs = dec.evaluate(out / "outer" / g.variant / f"{g.key}.N0.json", ss, meta, "HYBRID",
                                inp.eval_params(g.theta, g.theta), fold_models=d,
                                extra={"candidate": "N0", "group": g.key})
        finally:
            shutil.rmtree(d, ignore_errors=True)
        check_min = naps.sanity_check_min_session_min(read_json(out / "outer" / g.variant / f"{g.key}.N0.json"))
        return recs, _session_ms(ss, g.variant), check_min

    res = loso.map_threads(one, inp.groups, jobs)
    recs = [r for rs, _, _ in res for r in rs]
    sms = {k: v for _, m, _ in res for k, v in m.items()}
    check_min = sorted({c for _, _, c in res})
    if len(check_min) != 1:
        raise NapModelError(f"the build reported different sanity-check thresholds {check_min}")
    gate = check_records(recs, nights, proxies, sms, check_min[0])
    gate["sanityCheckMinSessionMin"] = check_min[0]
    gate["storedNights"] = len(nights)
    gate["gateLengths"] = list(GATE_LENGTHS)
    ok_n = gate["nights"] == gate["nightsIdentical"] == len(nights)
    ok_p = all(gate["byLength"].get(str(L), {}).get("identical") == gate["byLength"].get(str(L), {}).get("sessions")
               == sum(1 for (v, i), r in proxies.items() if r["L"] == L) > 0 for L in GATE_LENGTHS)
    gate["passed"] = bool(ok_n and ok_p)
    if not gate["passed"]:
        write_json(out / "gate.json", naps._clean(gate))
        raise NapModelError(f"reproduction gate failed: {gate}")
    return recs, gate


def run_context(inp: Inputs, dec: Decoder, out: Path, jobs: int) -> list[dict]:
    tasks = [(v, e) for v in inp.variants for e in CONTEXT_ESTIMATORS]

    def one(task):
        v, est = task
        ss, meta = dec.sessions(v, inp.index.subjects(None, v))
        return dec.evaluate(out / "outer" / v / f"{est}.json", ss, meta, est, inp.context_params(est),
                            extra={"candidate": est, "group": "-"})

    return [r for rs in loso.map_threads(one, tasks, max(1, min(jobs, len(tasks)))) for r in rs]


def inner_fold_sets(g: Group) -> list[tuple[int, tuple[str, ...], tuple[str, ...]]]:
    """(inner fold, training subjects, inner held-out Walch subjects) of the folds with Walch held-out subjects."""
    out = []
    ev = set(g.eval_subjects)
    for k, te in enumerate(g.inner_folds):
        te_eval = tuple(sorted(s for s in te if s in ev))
        if te_eval:
            out.append((k, tuple(sorted(set(g.subjects) - set(te))), te_eval))
    return out


def inner_tasks(inp: Inputs) -> list[FitKey]:
    tasks: list[FitKey] = []
    for g in inp.groups:
        for _, tr, _ in inner_fold_sets(g):
            tasks += [(tr, "lr", float(C)) for C in inp.cfg.lr_Cs] + [(tr, "mlp", None)]
    return tasks


def select_group(inp: Inputs, data: Data, dec: Decoder, g: Group, fits: Mapping[FitKey, TrainedClassifier],
                 out: Path, created_at: str) -> dict:
    """Nested inner CV of (variant, group): C by inner log-loss, the decoder grid per classifier, the chosen
    (classifier, point), then the nap stageLogBias fixed point on the inner out-of-fold decodes."""
    v = g.variant
    folds = inner_fold_sets(g)
    work = out / "inner" / v / g.key
    # C of LR: summed inner held-out log-loss on the Walch rows of the evaluated variant
    loss = {float(C): 0.0 for C in inp.cfg.lr_Cs}
    n = 0
    for _, tr, te in folds:
        m = np.isin(data.subject, np.array(te, dtype=object)) & (data.variant == v)
        for C in loss:
            loss[C] += log_loss(fits[(tr, "lr", C)].predict_proba(data.X[m]), data.y[m]) * int(m.sum())
        n += int(m.sum())
    best_C = min(loss, key=lambda C: (loss[C], list(loss).index(C)))
    stats = {tr: label_stats(inp, data, v, tr) for _, tr, _ in folds}
    models: dict[str, dict[str, dict]] = {}
    for clf in CLASSIFIER_ORDER:
        C = best_C if clf == "lr" else None
        per: dict[str, dict] = {}
        for k, tr, te in folds:
            b = nap_bundle(inp, data, fits[(tr, clf, C)], v, tr, f"nap-inner-{v}-{clf}-{g.key}-k{k}",
                           [f"inner fold {k} of selection set {g.key}"], created_at, stats=stats[tr])
            for s in te:
                per[s] = b
        models[clf] = per
    ss, meta = dec.sessions(v, g.eval_subjects)
    base = inp.eval_params(g.theta, g.theta)
    points = grid_points(base)
    grid: dict[str, list[dict]] = {}
    for clf in CLASSIFIER_ORDER:
        d = _write_bundles(work / f"models-{clf}", {s: standalone(b) for s, b in models[clf].items()})
        try:
            blocks = dec.evaluate_grid(work / f"grid-{clf}.json", ss, meta, points, d)
        finally:
            shutil.rmtree(d, ignore_errors=True)
        rows = []
        for p, recs in zip(points, blocks):
            score = report.mean_subject_kappa(recs)
            rows.append({"id": p["id"], "index": p["index"], "hybrid": p["hybrid"],
                         "score": None if not math.isfinite(score) else score})
        grid[clf] = rows
    cands = [(clf, r) for clf in CLASSIFIER_ORDER for r in grid[clf]]
    clf, best = max(cands, key=lambda x: (x[1]["score"] if x[1]["score"] is not None else -math.inf,
                                           -CLASSIFIER_ORDER.index(x[0]), -x[1]["index"]))
    runner = max((x[1]["score"] for x in cands if x[1] is not best and x[1]["score"] is not None), default=None)
    params = points[best["index"]]["params"]
    counter = iter(range(10_000))
    decoded: dict[int, list[dict]] = {}

    def decode(b: np.ndarray) -> np.ndarray:
        i = next(counter)
        d = _write_bundles(work / f"fp-{i}", {s: standalone(m, b.tolist() if np.any(b) else None)
                                              for s, m in models[clf].items()})
        try:
            recs = dec.evaluate(work / f"fp-{i}.json", ss, meta, "HYBRID", params, fold_models=d)
        finally:
            shutil.rmtree(d, ignore_errors=True)
        decoded[i] = recs
        return naps._pooled(recs)

    fit = fit_stage_bias(decode)
    rs = naps._scored(decoded[fit["bestIndex"]])
    inner_cell = cell(rs)
    res = {"variant": v, "key": g.key, "heldout": g.heldout, "subjects": len(g.subjects),
           "evalSubjects": g.eval_subjects, "innerFoldsUsed": [k for k, _, _ in folds], "sessions": len(meta),
           "C": best_C, "logLoss": {str(C): x / n if n else None for C, x in loss.items()}, "grid": grid,
           "chosen": {"classifier": clf, "C": best_C if clf == "lr" else None, "id": best["id"],
                      "index": best["index"], "hybrid": best["hybrid"], "score": best["score"]},
           "margin": None if runner is None or best["score"] is None else best["score"] - runner,
           "bestPerClassifier": {c: max(grid[c], key=lambda r: (r["score"] if r["score"] is not None else -math.inf,
                                                                 -r["index"])) for c in CLASSIFIER_ORDER},
           "napBias": {k: fit[k] for k in ("bias", "D", "decodes", "trueShares", "predShares", "bestIndex", "trace")},
           "innerE": inner_cell["E"], "innerAccuracy": inner_cell["accuracy"], "innerKappa": inner_cell["kappa"]}
    write_json(work / "selection.json", naps._clean(res))
    for f in work.glob("*.json"):
        if f.name.startswith(("grid-", "fp-")) and not f.name.endswith(".params.json"):
            f.unlink(missing_ok=True)
    return res


def outer_tasks(inp: Inputs, sel: Mapping[tuple[str, str], Mapping]) -> list[FitKey]:
    tasks: list[FitKey] = []
    for g in inp.groups:
        ch = sel[(g.variant, g.key)]["chosen"]
        for s in g.heldout:
            tasks.append((tuple(x for x in inp.pool if x != s), ch["classifier"], ch["C"]))
    return tasks


def run_outer(inp: Inputs, data: Data, dec: Decoder, sel: Mapping, fits: Mapping, out: Path, created_at: str,
              jobs: int) -> tuple[list[dict], list[dict], dict]:
    """N1 / N2 per (variant, group): nap fold models of every held-out subject, decoded with its N0 fold bundle; the
    whole nights are decoded too and must stay identical to candidate B. Returns (records, fold metas, night check)."""

    def one(g: Group):
        s_ = sel[(g.variant, g.key)]
        ch = s_["chosen"]
        theta_nap = _theta(ch["hybrid"])
        nap_dir = out / "outer" / g.variant / "nap"
        metas, models = [], {}
        for s in g.heldout:
            tr = tuple(x for x in inp.pool if x != s)
            b = nap_bundle(inp, data, fits[(tr, ch["classifier"], ch["C"])], g.variant, tr,
                           f"nap-loso-{g.variant}-{ch['classifier']}-{s}",
                           [f"LOSO fold, held out: {s}", f"decoder chosen by inner CV of group {g.key}: "
                            f"{canonical_json(theta_nap)}"], created_at)
            write_bundle(nap_dir / f"{s}.json", b)
            models[s] = b
            metas.append({"variant": g.variant, "subject": s, "group": g.key, "classifier": ch["classifier"],
                          "C": ch["C"], "hybrid": theta_nap, "score": ch["score"], "napBias": s_["napBias"]["bias"],
                          "rows": fits[(tr, ch["classifier"], ch["C"])].info.get("rows")})
        folds = inp.folds(g.variant)
        ss, meta = dec.sessions(g.variant, g.heldout, nights=True)
        recs = []
        for cand, nb in (("N1", s_["napBias"]["bias"]), ("N2", None)):
            d = _write_bundles(out / "eval" / cand / g.variant / g.key,
                               {s: n1_bundle(read_json(folds / f"{s}.json"), g, models[s], nb) for s in g.heldout})
            try:
                recs += dec.evaluate(out / "outer" / g.variant / f"{g.key}.{cand}.json", ss, meta, "HYBRID",
                                     inp.eval_params(g.theta, theta_nap), fold_models=d,
                                     extra={"candidate": cand, "group": g.key})
            finally:
                shutil.rmtree(d, ignore_errors=True)
        return recs, metas, _session_ms(ss, g.variant)

    res = loso.map_threads(one, inp.groups, jobs)
    recs = [r for rs, _, _ in res for r in rs]
    metas = [m for _, ms, _ in res for m in ms]
    sms = {k: v for _, _, m in res for k, v in m.items()}
    nights = stored_nights(inp)
    unchanged = {}
    for cand in MODEL_CANDIDATES:
        chk = check_records([r for r in recs if r["candidate"] == cand and r["proxy"] == "night"], nights, {}, sms, 0.0)
        unchanged[cand] = {"nights": chk["nights"], "identical": chk["nightsIdentical"], "mismatches": chk["mismatches"]}
        if chk["nights"] != len(nights) or chk["nightsIdentical"] != chk["nights"]:
            raise NapModelError(f"{cand}: whole nights changed with a nap section: {unchanged[cand]}")
    return recs, metas, unchanged


def run_experiment(inp: Inputs, cli: AlgoCli, out: Path, jobs: int = 4, created_at: str | None = None) -> dict:
    """The whole pre-registered experiment; writes results.jsonl, selections.json, folds.json, summary.json and
    report.md into ``out``."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    fp = cli.fingerprint()
    created_at = created_at or inp.cfg.created_at
    t_start = time.monotonic()
    timings: dict[str, float] = {}
    log.info("nap-model experiment: %d groups, algo-cli %s", len(inp.groups), fp.get("sha256"))
    proxies, meta, counts = build_proxies(inp)
    write_json(out / "proxies" / "counts.json", naps._clean(counts))
    feat = compute_features(inp, cli, proxies, out, jobs)
    data = load_data(inp, cli, proxies, meta, out)
    timings["proxiesFeatures"] = time.monotonic() - t_start
    log.info("proxies %s; feature parity %s; %d training rows", counts, feat, len(data.y))
    dec = Decoder(inp, data, cli, out)
    t0 = time.monotonic()
    n0, gate = run_n0(inp, dec, out, jobs)
    write_json(out / "gate.json", naps._clean(gate))
    timings["n0Gate"] = time.monotonic() - t0
    log.info("reproduction gate passed: %s", {k: gate[k] for k in ("nights", "nightsIdentical", "byLength")})
    global _DATA, _SPEC
    _DATA, _SPEC = data, inp.cfg.classifier_spec
    t0 = time.monotonic()
    fits = fit_all(inner_tasks(inp), jobs)
    timings["innerFits"] = time.monotonic() - t0
    t0 = time.monotonic()
    sel_list = loso.map_threads(lambda g: select_group(inp, data, dec, g, fits, out, created_at), inp.groups, jobs)
    sel = {(s["variant"], s["key"]): s for s in sel_list}
    write_json(out / "selections.json", naps._clean({"selections": sel_list}))
    timings["innerSelection"] = time.monotonic() - t0
    t0 = time.monotonic()
    fits.update(fit_all(outer_tasks(inp, sel), jobs))
    timings["outerFits"] = time.monotonic() - t0
    t0 = time.monotonic()
    model_recs, metas, unchanged = run_outer(inp, data, dec, sel, fits, out, created_at, jobs)
    write_json(out / "folds.json", naps._clean({"nightsUnchanged": unchanged, "folds": metas}))
    ctx = run_context(inp, dec, out, jobs)
    timings["outerEvaluation"] = time.monotonic() - t0
    _DATA = None
    if cli.fingerprint().get("sha256") != fp.get("sha256"):
        raise NapModelError("algo-cli changed during the nap-model experiment")
    recs = n0 + model_recs + ctx
    _write_jsonl(out / "results.jsonl", recs)
    timings["total"] = time.monotonic() - t_start
    m = {"run": out.name, "baseRun": str(inp.run_dir), "napsRun": str(inp.naps_dir),
         "posthocRun": str(inp.posthoc_dir) if inp.posthoc_dir else None, "config": inp.base.config,
         "nightClassifier": inp.base.clf, "algoCli": fp, "code": code_fingerprint(), "prereg": PREREG_PATH,
         "preregSha256": PREREG_SHA256, "onsetLengths": list(ONSET_LENGTHS), "ewCap": EW_CAP,
         "featureSetVersion": data.fsv, "features": len(data.feature_names), "trainingRows": int(len(data.y)),
         "createdAt": created_at, "timingsSeconds": timings, "proxyCounts": counts, "featureParity": feat["parity"],
         "gate": gate, "nightsUnchanged": unchanged}
    summary = summarize(recs, sel_list, metas, m, inp.variants)
    write_json(out / "summary.json", naps._clean(summary))
    (out / "report.md").write_text(render(summary), encoding="utf-8")
    return summary


def summarize(recs: Sequence[Mapping], selections: Sequence[Mapping], folds: Sequence[Mapping], meta: Mapping,
              variants: Sequence[str] = report.HEADLINE_VARIANTS) -> dict:
    proxies = [r for r in recs if r["proxy"] in PROXY_KINDS]
    proxies += all_light([r for r in proxies if r["candidate"] == "N0"])
    cells = cells_of(proxies, NAMES, variants)
    scores = rule_scores(cells, variants)
    decision = adopt_model(scores) if all(c in scores for c in CANDIDATES) else None
    policy = choose_policy(scores[decision["adopted"]]) if decision else None
    secondary = rule_scores(cells, variants, dataset="bidsleep")
    by_kind = {p: rule_scores(cells, variants, part=p) for p in PROXY_KINDS}
    groups = [{k: s[k] for k in ("variant", "key", "chosen", "margin", "C", "logLoss", "bestPerClassifier", "innerE",
                                 "innerAccuracy", "innerKappa", "sessions", "subjects")}
              | {"napBias": {k: s["napBias"][k] for k in ("bias", "D", "decodes", "trueShares", "predShares")}}
              for s in selections]
    choice_counts = Counter(f["classifier"] for f in folds)
    return {"meta": dict(meta), "cells": cells, "scores": scores, "scoresByKind": by_kind,
            "scoresSecondary": secondary, "decision": decision, "policy": policy,
            "comparisons": comparisons(cells, variants), "lightMargins": light_margins(cells, variants=variants),
            "groups": groups, "foldChoices": {"classifier": dict(choice_counts),
                                              "theta": dict(Counter(canonical_json(f["hybrid"]) for f in folds))}}


def rerender(out: Path, variants: Sequence[str] = report.HEADLINE_VARIANTS) -> dict:
    """Recomputes ``summary.json`` / ``report.md`` from the stored results, selections and folds (no algo-cli)."""
    out = Path(out)
    old = read_json(out / "summary.json")
    sel = read_json(out / "selections.json")["selections"]
    folds = read_json(out / "folds.json")["folds"]
    summary = summarize(_read_jsonl(out / "results.jsonl"), sel, folds, old["meta"], variants)
    write_json(out / "summary.json", naps._clean(summary))
    (out / "report.md").write_text(render(summary), encoding="utf-8")
    return summary


# ------------------------------------------------------------------------------------------------- final ----

def final_choice(folds: Sequence[Mapping], selections: Sequence[Mapping]) -> dict:
    """Final classifier type (most frequent over the outer folds; ties -> higher mean inner score, then logreg), C and
    decoder (most frequent among the folds of that type; ties -> higher mean inner score, then grid order)."""
    by_key = {(s["variant"], s["key"]): s for s in selections}
    score = {(f["variant"], f["subject"]): by_key[(f["variant"], f["group"])]["chosen"]["score"] for f in folds}
    cnt = Counter(f["classifier"] for f in folds)

    def mean_score(fs):
        xs = [score[(f["variant"], f["subject"])] for f in fs if score[(f["variant"], f["subject"])] is not None]
        return float(np.mean(xs)) if xs else -math.inf

    clf = sorted(cnt, key=lambda c: (-cnt[c], -mean_score([f for f in folds if f["classifier"] == c]),
                                     CLASSIFIER_ORDER.index(c)))[0]
    fs = [f for f in folds if f["classifier"] == clf]
    C = None
    if clf == "lr":
        cc = Counter(float(f["C"]) for f in fs)
        C = sorted(cc, key=lambda c: (-cc[c], -mean_score([f for f in fs if float(f["C"]) == c]), c))[0]
    index = {canonical_json(p["hybrid"]): p["index"] for p in loso.grid_points(GRID)}
    tc = Counter(canonical_json(_theta(f["hybrid"])) for f in fs)
    th = sorted(tc, key=lambda t: (-tc[t], -mean_score([f for f in fs if canonical_json(_theta(f["hybrid"])) == t]),
                                   index[t]))[0]
    return {"classifier": clf, "C": C, "hybrid": json.loads(th), "classifierCounts": dict(cnt),
            "thetaCount": tc[th], "folds": len(folds), "foldsOfClassifier": len(fs)}


def final_model(inp: Inputs, cli: AlgoCli, exp_dir: Path, base_run: Path, out_dir: Path, jobs: int = 4,
                install: Path | None = None, created_at: str | None = None) -> dict:
    """Final nap model of the candidate adopted in ``exp_dir/summary.json`` (N1 / N2), installed as the nap section of
    ``base_run``'s final bundle, and trained_defaults with the nap decoder and the output policy of rule (ii); with N0
    kept only the output policy changes (no nap section)."""
    exp_dir, out_dir, base_run = Path(exp_dir), Path(out_dir), Path(base_run)
    s = read_json(exp_dir / "summary.json")
    adopted, policy = s["decision"]["adopted"], s["policy"]["policy"]
    created_at = created_at or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    base_summary = read_json(base_run / "summary.json")
    base_defaults = read_json(base_run / "trained_defaults.json")
    shipped = read_json(base_run / "final" / "hybrid_model.json")
    validate_bundle(shipped)
    result: dict = {"adopted": adopted, "policy": policy}
    nap_defaults: dict = {"outputPolicy": policy}
    note = (f"pre-registered nap-model experiment (docs/prereg/prereg-nap-model.md): {adopted} "
            f"({CANDIDATE_LABEL[adopted]}) adopted by rule (i); output policy {policy} by rule (ii)")
    bundle = shipped
    if adopted in MODEL_CANDIDATES:
        folds = read_json(exp_dir / "folds.json")["folds"]
        selections = read_json(exp_dir / "selections.json")["selections"]
        ch = final_choice(folds, selections)
        theta = _theta(ch["hybrid"])
        result["choice"] = ch
        proxies, meta, counts = build_proxies(inp)
        work = out_dir / "fit"
        compute_features(inp, cli, proxies, work, jobs)
        data = load_data(inp, cli, proxies, meta, work)
        global _DATA, _SPEC
        _DATA, _SPEC = data, inp.cfg.classifier_spec
        walch = sorted(x for x in inp.pool if inp.index.dataset_of(x) == report.HEADLINE_DATASET)
        tasks: list[FitKey] = [(tuple(inp.pool), ch["classifier"], ch["C"])]
        stored: dict[tuple[str, str], dict] = {}
        if adopted == "N1":
            for f in folds:
                if f["subject"] in set(walch):
                    if f["classifier"] == ch["classifier"] and f["C"] == ch["C"]:
                        stored[(f["variant"], f["subject"])] = read_json(
                            exp_dir / "outer" / f["variant"] / "nap" / f"{f['subject']}.json")
                    else:
                        tasks.append((tuple(x for x in inp.pool if x != f["subject"]), ch["classifier"], ch["C"]))
        fits = fit_all(tasks, jobs)
        _DATA = None
        tc = fits[(tuple(inp.pool), ch["classifier"], ch["C"])]
        nap = nap_bundle(inp, data, tc, TRAIN_VARIANT_ORDER[0], inp.pool, "nap-final", ["final nap model, all subjects"],
                         created_at)
        nap_bias = None
        if adopted == "N1":
            dec = Decoder(inp, data, cli, work)
            oof: dict[str, dict[str, dict]] = {v: {} for v in inp.variants}
            refit = 0
            for v in inp.variants:
                for x in walch:
                    if (v, x) in stored:
                        oof[v][x] = stored[(v, x)]
                    else:
                        tr = tuple(y for y in inp.pool if y != x)
                        oof[v][x] = nap_bundle(inp, data, fits[(tr, ch["classifier"], ch["C"])], v, tr,
                                               f"nap-loso-{v}-{ch['classifier']}-{x}", [f"LOSO fold, held out: {x}"],
                                               created_at)
                        refit += 1
            counter = iter(range(10_000))
            params = inp.eval_params(base_summary["selection"]["decoderParams"]["params"], theta)

            def decode(b: np.ndarray) -> np.ndarray:
                i = next(counter)
                total = np.zeros((N_STAGES, N_STAGES))
                for v in inp.variants:
                    d = _write_bundles(work / f"fp-{v}-{i}", {x: standalone(m, b.tolist() if np.any(b) else None)
                                                             for x, m in oof[v].items()})
                    ss, meta_v = dec.sessions(v, walch)
                    try:
                        recs = dec.evaluate(work / f"fp-{v}-{i}.json", ss, meta_v, "HYBRID", params, fold_models=d)
                    finally:
                        shutil.rmtree(d, ignore_errors=True)
                    total += naps._pooled(recs)
                return total

            fit = fit_stage_bias(decode)
            nap_bias = fit["bias"]
            result["fit"] = fit
            result["refittedFolds"] = refit
        sec_src = with_stage_log_bias(nap, nap_bias) if nap_bias is not None else nap
        ds, lic = dataset_description(inp, {inp.index.dataset_of(x) for x in inp.pool})
        n_sessions = sum(1 for m_ in data.meta[TRAIN_VARIANT_ORDER[0]].values())
        sec = nap_section(sec_src, training={
            "dataset": f"nap proxies of {ds}", "license": lic, "subjects": len(inp.pool), "nights": n_sessions,
            "variants": list(TRAIN_VARIANT_ORDER), "createdAt": created_at,
            "notes": f"{note}; {tc.spec.describe()}; decoder {canonical_json(theta)} (napMode.hybrid); proxies: onset "
                     f"L {list(ONSET_LENGTHS)} min + ends-in-wake windows (<= {EW_CAP} per night); rows {tc.info.get('rows')}"
                     + (f"; nap stageLogBias fitted on the LOSO out-of-fold proxies of the {len(walch)} Walch subjects"
                        if nap_bias is not None else "; no nap stageLogBias")})
        bundle = with_nap_model(shipped, sec)
        bundle["modelId"] = f"pulsestages-{loso.CONFIGS[inp.base.config].pool}-{inp.base.clf}-{out_dir.name}"
        bundle["training"] = dict(bundle["training"], notes=bundle["training"]["notes"] + "; " + note)
        nap_defaults["hybrid"] = theta
        result.update({"napBias": nap_bias, "classifier": ch["classifier"], "C": ch["C"], "hybrid": theta,
                       "proxyCounts": counts, "rows": tc.info.get("rows")})
    path = write_bundle(out_dir / "final" / "hybrid_model.json", bundle)
    defaults = deep_merge(base_defaults, {"napMode": nap_defaults})
    write_json(out_dir / "trained_defaults.json", defaults)
    sel = dict(base_summary["selection"])
    sel["napModel"] = {"note": note + (f"; nap decoder {canonical_json(nap_defaults['hybrid'])}"
                                       if "hybrid" in nap_defaults else "")}
    if "choice" in result:
        ch = result["choice"]
        sel["napModel"]["decoderNote"] = (
            f"most frequent inner-CV choice of the pre-registered nap-model experiment: {ch['thetaCount']} of "
            f"{ch['foldsOfClassifier']} outer folds of the nap {ch['classifier'].upper()}, docs/prereg/prereg-nap-model.md")
    sel["napOutputPolicy"] = {"note": f"rule (ii) of the pre-registered nap-model experiment on {adopted}: "
                                      f"{policy} (accuracy margin over all LIGHT "
                                      f"{s['policy']['accMarginWorse']:+.3f}, 4-class kappa "
                                      f"{s['policy']['kappaWorse']:.3f}, sleep/wake kappa "
                                      f"{s['policy']['swKappaWorse']:.3f}, Walch, worse variant)"}
    meta = dict(base_summary["meta"], run=out_dir.name, createdAt=created_at,
                napModel={"algoCli": cli.fingerprint(), "code": code_fingerprint(), "exp": str(exp_dir)})
    write_json(out_dir / "summary.json", {"meta": meta, "selection": sel, "napModel": naps._clean(
        {k: v for k, v in result.items() if k != "fit"}) | {"bundle": str(path)}})
    result["bundle"] = str(path)
    if install is not None:
        install = Path(install)
        install.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, install)
        result["installed"] = str(install)
    write_json(out_dir / "final" / "result.json", naps._clean(result))
    return result


# ------------------------------------------------------------------------------------------- verification ----

def verify(inp: Inputs, cli: AlgoCli, exp_dir: Path, final_dir: Path, previous_bundle: Path, out_dir: Path,
           jobs: int = 4) -> dict:
    """With the final build: (1) the adopted candidate's fold bundles reproduce candidate B on every whole night with
    each group's night theta_g and otherwise default parameters; (2) its proxies are reproduced with theta_nap_g and
    STAGES passed explicitly; (3) the installed bundle decodes every whole night exactly as ``previous_bundle``
    (metrics and per-epoch output, default parameters); (4) the installed bundle is accepted on every proxy."""
    exp_dir, final_dir, out_dir = Path(exp_dir), Path(final_dir), Path(out_dir)
    s = read_json(exp_dir / "summary.json")
    adopted = s["decision"]["adopted"]
    stored = [r for r in _read_jsonl(exp_dir / "results.jsonl") if r["candidate"] == adopted]
    want = {(r["variant"], r["night"]): r for r in stored}
    sel = {(x["variant"], x["key"]): x for x in read_json(exp_dir / "selections.json")["selections"]}
    proxies, meta, _ = build_proxies(inp)
    data = Data(proxies=proxies, meta=meta, tables={}, feature_names=[], fsv=0, X=np.zeros((0, 0)), y=np.zeros(0),
                subject=np.zeros(0, dtype=object), variant=np.zeros(0, dtype=object))
    dec = Decoder(inp, data, cli, out_dir)
    nights = stored_nights(inp)

    def one(g: Group):
        folds = inp.folds(g.variant)
        if adopted in MODEL_CANDIDATES:
            nb = sel[(g.variant, g.key)]["napBias"]["bias"] if adopted == "N1" else None
            bundles = {x: n1_bundle(read_json(folds / f"{x}.json"), g,
                                    read_json(exp_dir / "outer" / g.variant / "nap" / f"{x}.json"), nb)
                       for x in g.heldout}
            theta_nap = _theta(sel[(g.variant, g.key)]["chosen"]["hybrid"])
        else:
            bundles = {x: n0_bundle(read_json(folds / f"{x}.json"), g) for x in g.heldout}
            theta_nap = dict(g.theta)
        d = _write_bundles(out_dir / "models" / g.variant / g.key, bundles)
        try:
            ns = [inp.night(g.variant, n.id) for n in inp.index.nights_of(g.variant, g.heldout)]
            mn = {n["id"]: {"base": n["id"], "subject": n["subject"], "dataset": inp.index.get(g.variant, n["id"]).dataset,
                            "variant": g.variant, "proxy": "night", "L": None, "offsetMin": None} for n in ns}
            night_recs = dec.evaluate(out_dir / g.variant / f"{g.key}.nights.json", ns, mn, "HYBRID",
                                      inp.base.params(g.theta), fold_models=d, extra={"group": g.key})
            ps, mp = dec.sessions(g.variant, g.heldout)
            proxy_recs = dec.evaluate(out_dir / g.variant / f"{g.key}.proxies.json", ps, mp, "HYBRID",
                                      inp.eval_params(g.theta, theta_nap), fold_models=d, extra={"group": g.key})
        finally:
            shutil.rmtree(d, ignore_errors=True)
        return night_recs, proxy_recs, _session_ms(ns, g.variant)

    res = loso.map_threads(one, inp.groups, jobs)
    night_recs = [r for a, _, _ in res for r in a]
    proxy_recs = [r for _, b, _ in res for r in b]
    sms = {k: v for _, _, m in res for k, v in m.items()}
    chk = check_records(night_recs, nights, {}, sms, 0.0)
    bad_p = [(r["variant"], r["night"]) for r in proxy_recs
             if (o := want.get((r["variant"], r["night"]))) is None or o["confusion"] != r["confusion"]
             or o["warnings"] != r["warnings"] or o["estimated"] != r["estimated"]]
    out: dict = {"adopted": adopted, "foldNights": chk["nights"], "foldNightsIdenticalToB": chk["nightsIdentical"],
                 "foldNightMismatches": chk["mismatches"], "proxies": len(proxy_recs),
                 "proxiesIdentical": len(proxy_recs) - len(bad_p), "proxyMismatches": bad_p[:10],
                 "algoCli": cli.fingerprint(), "code": code_fingerprint()}
    # (3) installed vs previous bundle on the whole nights, default parameters, per-epoch output compared
    final_bundle = final_dir / "final" / "hybrid_model.json"
    same_nights, same_epochs, total = 0, 0, 0
    resub = []
    for v in inp.variants:
        ids = [n.id for n in inp.index.for_variant(v)]
        ns = [inp.night(v, i) for i in ids]
        nf = write_sessions(out_dir / "final" / f"{v}.nights.jsonl", ns)
        got = {}
        for tagname, bpath in (("previous", Path(previous_bundle)), ("installed", final_bundle)):
            pe = out_dir / "final" / f"{v}.{tagname}.per-epoch.csv"
            m = cli.evaluate(nf, "HYBRID", out_dir / "final" / f"{v}.{tagname}.json", model=bpath, per_epoch=pe)
            rr = report.parse_metrics(m)
            report.check_estimator_used(rr, "HYBRID")
            got[tagname] = ({r["id"]: (r["confusion"].tolist() if r["confusion"] is not None else None, r["warnings"],
                                       r["estimated"]) for r in rr}, pe.read_bytes())
        nf.unlink(missing_ok=True)
        a, b = got["previous"][0], got["installed"][0]
        total += len(ids)
        same_nights += sum(1 for i in ids if a.get(i) == b.get(i))
        same_epochs += int(got["previous"][1] == got["installed"][1])
        ps, mp = dec.sessions(v, inp.index.subjects(None, v))
        pf = write_json(out_dir / "final" / f"{v}.proxies.params.json", dict(inp.cfg.base_params))
        nfp = write_sessions(out_dir / "final" / f"{v}.proxies.jsonl", ps)
        m = cli.evaluate(nfp, "HYBRID", out_dir / "final" / f"{v}.proxies.json", params=pf, model=final_bundle)
        nfp.unlink(missing_ok=True)
        rr = report.parse_metrics(m)
        report.check_estimator_used(rr, "HYBRID")
        resub.append({"variant": v, "sessions": len(rr), "estimated": sum(1 for x in rr if x["estimated"]),
                      "hybrid": sum(1 for x in rr if x["estimatorUsed"] == "HYBRID"),
                      "outputPolicy": (m.get("params") or {}).get("napMode", {}).get("outputPolicy")})
    out.update({"installedVsPrevious": {"nights": total, "identical": same_nights, "perEpochFilesIdentical": same_epochs,
                                        "variants": len(inp.variants)},
                "finalBundle": {"path": str(final_bundle), "resubstitution": resub}})
    write_json(out_dir / "summary.json", naps._clean(out))
    if (chk["nights"] != len(nights) or chk["nightsIdentical"] != chk["nights"] or bad_p or same_nights != total
            or same_epochs != len(inp.variants)):
        raise NapModelError(f"verification failed: {json.dumps(naps._clean({k: v for k, v in out.items() if k not in ('algoCli', 'code')}))}")
    return out


# ----------------------------------------------------------------------------------------------- markdown ----

_f = naps._f
_pct = naps._pct
_ci = naps._ci
_shares = naps._shares


def _ci_line(x: Mapping, m: str, nd: int = 3) -> str:
    return _ci(x.get(m), nd)


def render(s: Mapping) -> str:
    m = s["meta"]
    variants = list(report.HEADLINE_VARIANTS)
    L = [f"# Dedicated nap model (pre-registered) — run `{m.get('run', '?')}`", "",
         f"Pre-registration: `{m['prereg']}` (SHA-256 `{m['preregSha256'][:12]}…`). Proxies: onset L in "
         f"{m['onsetLengths']} min and ends-in-wake windows (<= {m['ewCap']} per night); LOSO over the subjects of "
         f"[{m['config']}], outer groups / inner folds of `{Path(str(m['baseRun'])).name}`. Pooled = over the proxies "
         "of both kinds: accuracy over all scored chunks, E = mean per-session summed share error (pp), κ = mean "
         "per-subject Cohen's kappa (the subject's proxies pooled), SW = sleep / wake (AWAKE vs the rest).", ""]
    g = m.get("gate", {})
    L += ["## Reproduction gate and checks", "",
          f"- N0 whole nights: {g.get('nightsIdentical')} of {g.get('nights')} identical to `bias-base/bias` "
          f"candidate B; onset proxies vs C-180 of `naps` / `naps-posthoc`: "
          + ", ".join(f"L {k}: {v['identical']}/{v['sessions']}" for k, v in sorted(g.get("byLength", {}).items(),
                                                                                    key=lambda x: int(x[0])))
          + f" (sanity check from {g.get('sanityCheckMinSessionMin')} min).",
          "- Feature parity (Python cut vs algo-cli `--nap-proxies`): "
          + ", ".join(f"{p['variant']} {p['onsetProxies']} proxies / {p['rowsCompared']} rows, {p['nDiffering']} "
                      f"differing" for p in m.get("featureParity", [])) + ".",
          "- Whole nights with a nap section (N1 / N2): "
          + ", ".join(f"{k} {v['identical']}/{v['nights']}" for k, v in m.get("nightsUnchanged", {}).items()) + ".",
          f"- Training rows (all proxies, both variants): {m.get('trainingRows')}; featureSetVersion "
          f"{m.get('featureSetVersion')}, {m.get('features')} features.", ""]
    L += ["## Proxies", "", "| variant | dataset | nights | onset | ends-in-wake | per night (count: nights) | "
          "ew length mean / min / max | ew bins | subjects with ew |", "|---|---|---|---|---|---|---|---|---|"]
    for v, dd in m.get("proxyCounts", {}).items():
        for ds, q in dd.items():
            L.append(f"| {v} | {ds} | {q['nights']} | {q['onset']} | {q['ew']} | "
                     + ", ".join(f"{k}: {n}" for k, n in q["ewPerNight"].items())
                     + f" | {_f(q['ewLengthMin']['mean'], 1)} / {_f(q['ewLengthMin']['min'], 1)} / "
                       f"{_f(q['ewLengthMin']['max'], 1)} | "
                     + ", ".join(f"{k} {n}" for k, n in sorted(q["ewBins"].items())) + f" | {q['ewSubjects']} |")
    L.append("")
    d, p = s.get("decision"), s.get("policy")
    if d:
        L += ["## Rule (i): the model (Walch, worse variant, both kinds pooled)", "",
              f"N0 accuracy {_f(d['referenceAccuracy'])} → threshold {_f(d['accuracyThreshold'])}, N0 E "
              f"{_f(d['referenceE'], 1)} pp; qualifying: {', '.join(d['qualifying']) or 'none'}; **adopted: "
              f"{d['adopted']} ({CANDIDATE_LABEL[d['adopted']]})**.", "",
              "| candidate | accuracy inst. / mean2min | **acc. worse** | E inst. / mean2min | **E worse** | κ inst. / "
              "mean2min | SW κ inst. / mean2min | wake F1 inst. / mean2min | acc. − all LIGHT (worse) |",
              "|---|---|---|---|---|---|---|---|---|"]
        for n, q in s["scores"].items():
            L.append(f"| {n} {CANDIDATE_LABEL[n]} | " + " / ".join(_f(q["accuracy"][v]) for v in variants)
                     + f" | **{_f(q['accWorse'])}** | " + " / ".join(_f(q["E"][v], 1) for v in variants)
                     + f" | **{_f(q['EWorse'], 1)}** | " + " / ".join(_f(q["kappa"][v]) for v in variants) + " | "
                     + " / ".join(_f(q["swKappa"][v]) for v in variants) + " | "
                     + " / ".join(_f(q["wakeF1"][v]) for v in variants) + f" | {_f(q['accMarginWorse'])} |")
        L.append("")
    if p:
        L += ["## Rule (ii): the default output policy", "",
              f"Adopted candidate {d['adopted']}: accuracy − all LIGHT {_f(p['accMarginWorse'])} (needs ≥ 0.03), "
              f"4-class κ {_f(p['kappaWorse'])} (≥ 0.15), sleep/wake κ {_f(p['swKappaWorse'])} (≥ 0.20) → "
              f"**{p['policy']}**.", "", "| candidate | variant | accuracy − all LIGHT, paired per subject (95 % CI) |",
              "|---|---|---|"]
        for x in s.get("lightMargins", []):
            if x["dataset"] == report.HEADLINE_DATASET:
                L.append(f"| {x['name']} | {x['variant']} | {_ci(x['margin'])} |")
        L.append("")
    for ds in DATASETS:
        title = "Walch (PSG), headline" if ds == report.HEADLINE_DATASET else "BIDSleep (EEG headband), secondary"
        L += [f"## {title}", ""]
        for v in variants:
            L += [f"### {v}", "", "| name | part | sessions (not est.) | accuracy | E (pp) | κ (defined) | κ pooled | "
                  "SW κ | SW accuracy | wake F1 | all-LIGHT acc. | predicted shares A / L / D / R % | true shares | "
                  "low-conf. % |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
            for n in NAMES:
                per = s["cells"].get(n, {}).get(ds, {}).get(v, {})
                for part in PART_ORDER:
                    c = per.get(part)
                    if c is None:
                        continue
                    L.append(f"| {n} | {part} | {c['sessions']} ({c['notEstimated']}) | {_f(c['accuracy'])} | "
                             f"{_f(c['E'], 1)} | {_f(c['kappa'])} ({c['kappaDefined']}) | {_f(c['kappaPooled'])} | "
                             f"{_f(c['swKappa'])} | {_f(c['swAccuracy'])} | {_f(c['wakeF1'])} | "
                             f"{_f(c['lightAccuracy'])} | {_shares(c['predShares'])} | {_shares(c['trueShares'])} | "
                             f"{_pct(c['lowConfidenceRate'])} |")
            L.append("")
        L += ["Paired per-subject differences a − b (95 % bootstrap CI, 10 000 resamples):", "",
              "| a − b | variant | part | Δaccuracy | ΔE pp | Δκ | ΔSW κ | Δwake F1 |", "|---|---|---|---|---|---|---|---|"]
        for x in s["comparisons"]:
            if x["dataset"] == ds:
                L.append(f"| {x['a']} − {x['b']} | {x['variant']} | {x['part']} | {_ci_line(x, 'accuracy')} | "
                         f"{_ci_line(x, 'E', 1)} | {_ci_line(x, 'kappa')} | {_ci_line(x, 'swKappa')} | "
                         f"{_ci_line(x, 'wakeF1')} |")
        L.append("")
    L += ["## Inner selection per (variant, group)", "",
          "| variant | group | classifier (C) | θ_nap | inner κ (margin) | best LR / MLP κ | nap b A / L / D / R | D | "
          "decodes | inner E / accuracy |", "|---|---|---|---|---|---|---|---|---|---|"]
    for q in s["groups"]:
        ch, h = q["chosen"], q["chosen"]["hybrid"]
        bp = q["bestPerClassifier"]
        L.append(f"| {q['variant']} | {q['key']} | {ch['classifier']}"
                 + (f" ({ch['C']:g})" if ch.get("C") is not None else "")
                 + f" | {h['durationModel']}, {h['emissionWeight']:g}, {h['timePriorWeight']:g} | {_f(ch['score'])} "
                   f"({_f(q['margin'], 4)}) | {_f(bp['lr']['score'])} / {_f(bp['mlp']['score'])} | "
                 + " / ".join(f"{x:+.2f}" for x in q["napBias"]["bias"])
                 + f" | {_f(q['napBias']['D'], 4)} | {q['napBias']['decodes']} | {_f(q['innerE'], 1)} / "
                   f"{_f(q['innerAccuracy'])} |")
    fc = s.get("foldChoices", {})
    L += ["", f"Outer folds per classifier: {fc.get('classifier')}; per decoder: {fc.get('theta')}.", ""]
    return "\n".join(L)


__all__ = ["CANDIDATES", "EW_CAP", "NAMES", "ONSET_LENGTHS", "PREREG_PATH", "PREREG_SHA256", "Inputs",
           "NapModelError", "adopt_model", "all_light", "cell", "cells_of", "check_sessions_file", "choose_policy",
           "collapse_sleep_wake",
           "comparisons", "cut_window", "ew_candidates", "final_choice", "final_model", "night_key", "parts_of",
           "proxies_of", "rerender", "rule_scores", "run_experiment", "sample_windows", "summarize", "verify",
           "wake_bouts", "wake_scores"]
