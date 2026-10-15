"""Metrics and decision rules of the pre-registered realistic-nap experiment (``docs/prereg/prereg-naps-realistic.md``
sections 7, 8, 9 and 13.8). Pure functions over per-session records, per-epoch rows and metric dicts; no algo-cli.

A session record is the dict of ``napmodel.tag`` (``night`` = the session id, ``subject``, ``dataset``, ``variant``,
``estimated``, ``reason``, ``estimatorUsed``, ``warnings``, ``confusion`` = the 4 x 4 matrix of scored 30-s chunks,
rows truth AWAKE / LIGHT / DEEP / REM) carrying the session meta of :mod:`pools` (``pool``, ``regime``, ``L``, ``o``,
``kind``, ...).

- Section 7.1: :func:`common_set` (E-cap: the sessions estimated by HYBRID with a scored chunk under every model).
- Section 7.2: :func:`mann_whitney_auc`, :func:`match_epochs`, :func:`auc_summary` (cells = (subject, anchor L)).
- Section 7.3: :func:`regime_contrast` (shares of sleep, subject bootstrap).
- Section 7.4: :func:`featureless_check` (R1), :func:`late_tail` (R2), :func:`lit_weight_table` and
  :func:`lit_metrics` (LitDist, guard excess, LIT accuracy / all-LIGHT / kappa; R3-R5), :func:`sleep_wake_kappa`.
- Section 7.5 (reported): :func:`prior_matched`, :func:`tracking`, :func:`onset_bins`, :func:`clock_table`,
  :func:`perturbation_effects`, :func:`late_start_effects`.
- Rules: :func:`rule_i` (D, F, R1-R5, F0, the two tiers, the selection), :func:`rule_ii`, :func:`rule_iii` with
  :func:`external_summary`; every criterion is recorded with its value, threshold and outcome (:func:`criterion`),
  and :func:`failure_reasons` writes the reasons of a negative outcome from the failing criteria.
"""

from __future__ import annotations

import csv
import math
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, Iterable, Mapping, Sequence
from zoneinfo import ZoneInfo

import numpy as np
from scipy.stats import rankdata, spearmanr

from . import litprior, perturb, report
from .litprior import ANCHORS, MID_ANCHORS
from .napmodel import collapse_sleep_wake
from .util import AWAKE, DEEP, LIGHT, N_STAGES, REM, STAGE_INDEX, STAGES

CHUNK_MIN = 0.5                                    # minutes per scored 30-s chunk
VARIANTS: tuple[str, ...] = report.HEADLINE_VARIANTS
CANDIDATES: tuple[str, ...] = ("C0", "C1", "C2")   # tie order: fewest changes first
REFERENCE = "N1"
MATCHED_LENGTHS: tuple[int, ...] = (60, 90)
TAIL_LENGTHS: tuple[int, ...] = (60, 90)
DEFAULT_SCOPE_LENGTHS: tuple[int, ...] = (90, 120, 150, 170)       # the app's default minSessionMin = 90
AUC_GROUPS: tuple[str, ...] = ("ANCHOR", "EARLY", "LATE")
AUC_STAGES: tuple[str, ...] = STAGES
BOOT = report.BOOTSTRAP_SAMPLES
# rule (i)
D_ANCHOR = -0.02                       # Delta AUC of AWAKE, DEEP, REM over ANCHOR
D_EARLY_LATE = -0.05                   # Delta AUC DEEP over EARLY, REM over LATE
F_RATIO = 0.25
R1_NO_SLEEP_MAX = 0.5
R1_SLEEP_SHARE_MIN = 0.5               # a decode with predicted sleep < 50 % of its scored chunks has "no sleep"
R2_TAIL_MAX = 0.10
R3_GUARD_EXCESS_MAX = 0.05
R4_MID_MAX = 2.0
LITDIST_TIE = 1.0
DEEP_HALF = 0.5
# rule (ii) (13.12's thresholds)
POLICY_ACC_MARGIN = 0.03
POLICY_KAPPA = 0.15
POLICY_SW_KAPPA = 0.20
POLICIES = ("STAGES", "SLEEP_WAKE", "SKIP")
# rule (iii)
TABLE1_TST_MIN = {"NREM": 49.27, "REM": 89.91}     # McDevitt et al. 2026, Table 1 (weak labels of the test set)
REM_FREE_MAX_PCT = 6.9                 # (b): the rule-I REM ceiling of naps with < 45 min of sleep (6.87 %)
FAIL_SHARE_MAX = 0.098                 # (c): Wilson upper bound of GL's false-flag rate on 2 / 70 genuine naps
FAIL_SHARE_ALL93 = 0.105               # reported: Wilson upper bound of 4 / 93
SHORT_SLEEP_MIN = 15.0                 # (c): a decode with <= 15 min of sleep counts as a failure
MIN_USABLE = 19                        # void below ceil(0.9 x 21) usable recordings per group
M60_MIN_USABLE = 15                    # 13.9: ceil(0.7 x 21)
ET = ZoneInfo("America/New_York")


# ------------------------------------------------------------------------------------- session quantities ----

def confusion(rec: Mapping) -> np.ndarray | None:
    c = rec.get("confusion")
    return None if c is None else np.asarray(c, float)


def scored_chunks(rec: Mapping) -> float:
    c = confusion(rec)
    return 0.0 if c is None else float(c.sum())


def is_hybrid_scored(rec: Mapping) -> bool:
    """Estimated by HYBRID with at least one scored chunk."""
    return bool(rec.get("estimated")) and rec.get("estimatorUsed") == "HYBRID" and scored_chunks(rec) > 0


def share_of_sleep(counts: np.ndarray, stage: int) -> float:
    """counts[stage] / (LIGHT + DEEP + REM); NaN without sleep."""
    c = np.asarray(counts, float)
    s = float(c[LIGHT] + c[DEEP] + c[REM])
    return float(c[stage] / s) if s > 0 else float("nan")


def window_share(counts: np.ndarray, stage: int) -> float:
    c = np.asarray(counts, float)
    t = float(c.sum())
    return float(c[stage] / t) if t > 0 else float("nan")


def minutes(counts: np.ndarray) -> dict:
    """AWAKE / LIGHT / DEEP / REM minutes of chunk counts and TST = LIGHT + DEEP + REM."""
    c = np.asarray(counts, float) * CHUNK_MIN
    return {"awake": float(c[AWAKE]), "light": float(c[LIGHT]), "deep": float(c[DEEP]), "rem": float(c[REM]),
            "tst": float(c[LIGHT] + c[DEEP] + c[REM])}


def gl_flag(counts: np.ndarray) -> bool:
    """The composition of ``counts`` violates GL (rows by its TST)."""
    m = minutes(counts)
    return litprior.gl_violation(m["tst"], m["deep"], m["rem"])


def g_flag(counts: np.ndarray) -> bool:
    m = minutes(counts)
    return litprior.g_violation(m["tst"], m["deep"], m["rem"], m["light"])


def _nan(x) -> float:
    return float("nan") if x is None else float(x)


def _finite(x) -> bool:
    return x is not None and math.isfinite(float(x))


# ------------------------------------------------------------------------------------ section 7.1: E-cap ----

def exclusion_reason(rec: Mapping | None) -> str | None:
    """Why a session is not in E-cap under a model (None: estimated by HYBRID with a scored chunk)."""
    if rec is None:
        return "MISSING"
    if not rec.get("estimated"):
        return f"NOT_ESTIMATED:{rec.get('reason') or 'unknown'}"
    if rec.get("estimatorUsed") != "HYBRID":
        return f"ESTIMATOR:{rec.get('estimatorUsed')}"
    if scored_chunks(rec) <= 0:
        return "NO_SCORED_CHUNK"
    return None


def _group(rec: Mapping) -> str:
    return f"{rec.get('pool')}|{rec.get('dataset')}|{rec.get('variant')}"


def common_set(records: Mapping[str, Sequence[Mapping]]) -> dict:
    """E-cap (section 7.1): the sessions, per pool, dataset and variant, estimated by HYBRID with at least one scored
    chunk under every model of ``records`` (model -> its records). Returns ``members`` (set of (variant, session id)),
    ``sessions`` / ``common`` (counts per ``pool|dataset|variant``) and ``excluded`` (model -> group -> reason ->
    count; a session can be excluded by several models)."""
    by_model: dict[str, dict[tuple[str, str], Mapping]] = {}
    for m, recs in records.items():
        d: dict[tuple[str, str], Mapping] = {}
        for r in recs:
            k = (str(r["variant"]), str(r["night"]))
            if k in d:
                raise ValueError(f"{m}: duplicate record {k}")
            d[k] = r
        by_model[m] = d
    keys = sorted(set().union(*(set(d) for d in by_model.values())))
    members: set[tuple[str, str]] = set()
    sessions: Counter = Counter()
    common: Counter = Counter()
    excluded: dict[str, dict[str, Counter]] = {m: defaultdict(Counter) for m in by_model}
    for k in keys:
        any_rec = next(d[k] for d in by_model.values() if k in d)
        g = _group(any_rec)
        sessions[g] += 1
        ok = True
        for m, d in by_model.items():
            why = exclusion_reason(d.get(k))
            if why is not None:
                excluded[m][g][why] += 1
                ok = False
        if ok:
            members.add(k)
            common[g] += 1
    return {"members": members, "sessions": dict(sessions), "common": dict(common),
            "excluded": {m: {g: dict(c) for g, c in e.items()} for m, e in excluded.items()}}


def in_common(records: Iterable[Mapping], members: set[tuple[str, str]]) -> list[Mapping]:
    return [r for r in records if (str(r["variant"]), str(r["night"])) in members]


# --------------------------------------------------------------------------------- section 7.2: AUC (D) ----

@dataclass(frozen=True)
class EpochRow:
    has_data: bool
    label: str
    stage: str
    p: tuple[float, float, float, float]


def read_per_epoch(path, sessions: set[str] | None = None, grid_index: int | None = 0
                   ) -> dict[tuple[str, int], EpochRow]:
    """The rows of an ``algo-cli evaluate --per-epoch`` CSV by (session id, epoch index), optionally only of
    ``sessions`` and one grid index."""
    out: dict[tuple[str, int], EpochRow] = {}
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if grid_index is not None and int(r["grid_index"]) != grid_index:
                continue
            if sessions is not None and r["night_id"] not in sessions:
                continue
            k = (r["night_id"], int(r["epoch"]))
            if k in out:
                raise ValueError(f"{path}: duplicate per-epoch row {k}")
            out[k] = EpochRow(r["has_data"] == "1", r["label"], r["stage"],
                              (float(r["p_awake"]), float(r["p_light"]), float(r["p_deep"]), float(r["p_rem"])))
    return out


def mann_whitney_auc(scores: np.ndarray, outcomes: np.ndarray) -> float:
    """P(score of a positive > score of a negative) + 1/2 P(tie) (Mann-Whitney U / (n1 n0)); NaN unless both outcomes
    occur."""
    s = np.asarray(scores, float)
    y = np.asarray(outcomes, bool)
    n1 = int(y.sum())
    n0 = int(y.size - n1)
    if n1 == 0 or n0 == 0:
        return float("nan")
    ranks = rankdata(s, method="average")
    return float((ranks[y].sum() - n1 * (n1 + 1) / 2.0) / (n1 * n0))


@dataclass
class MatchedEpochs:
    """Epochs present with heart-rate data and a label under both compared models (rows matched by session id and
    epoch), with their cell keys and the posteriors of both."""
    session: np.ndarray
    subject: np.ndarray
    length: np.ndarray
    groups: dict[str, np.ndarray]
    label: np.ndarray
    p_x: np.ndarray
    p_ref: np.ndarray
    excluded: dict = field(default_factory=dict)


def auc_groups_of(meta: Mapping) -> list[str]:
    """The pool groups Q of a session: ANCHOR (O, M, R at the anchors), EARLY, LATE."""
    out = []
    if meta.get("kind") == "eval" and meta.get("pool") in ("O", "M", "R"):
        out.append("ANCHOR")
        if meta.get("regime") in ("EARLY", "LATE"):
            out.append(str(meta["regime"]))
    return out


def match_epochs(rows_x: Mapping[tuple[str, int], EpochRow], rows_ref: Mapping[tuple[str, int], EpochRow],
                 sessions: Mapping[str, Mapping]) -> MatchedEpochs:
    """The epochs of ``sessions`` (session id -> meta; the caller restricts them to E-cap and the dataset) with
    ``has_data`` = 1 and a non-empty label under both models; excluded epochs counted by reason."""
    excl: Counter = Counter()
    keys = sorted(k for k in set(rows_x) | set(rows_ref) if k[0] in sessions)
    sel = []
    for k in keys:
        a, b = rows_x.get(k), rows_ref.get(k)
        if a is None or b is None:
            excl["missingInOneModel"] += 1
        elif not (a.has_data and b.has_data):
            excl["noData"] += 1
        elif not (a.label and b.label):
            excl["noLabel"] += 1
        elif a.label != b.label:
            raise ValueError(f"epoch {k}: labels differ between the models ({a.label} / {b.label})")
        else:
            sel.append(k)
    groups = {q: np.array([q in auc_groups_of(sessions[k[0]]) for k in sel], bool) for q in AUC_GROUPS}
    return MatchedEpochs(
        session=np.array([k[0] for k in sel], dtype=object),
        subject=np.array([str(sessions[k[0]]["subject"]) for k in sel], dtype=object),
        length=np.array([float(sessions[k[0]]["L"]) for k in sel], float),
        groups=groups, label=np.array([STAGE_INDEX[rows_x[k].label] for k in sel], int),
        p_x=np.array([rows_x[k].p for k in sel], float).reshape(-1, N_STAGES),
        p_ref=np.array([rows_ref[k].p for k in sel], float).reshape(-1, N_STAGES), excluded=dict(excl))


def cell_aucs(subject: np.ndarray, length: np.ndarray, mask: np.ndarray, label: np.ndarray, p: np.ndarray,
              stage: int) -> dict[tuple[str, float], float]:
    """AUC of stage ``stage`` (score p_s, outcome label = s) per cell (subject, length) of the epochs in ``mask``;
    only cells where both outcomes occur."""
    out = {}
    idx = np.flatnonzero(mask)
    cells: dict[tuple[str, float], list[int]] = defaultdict(list)
    for i in idx:
        cells[(str(subject[i]), float(length[i]))].append(int(i))
    for c, ii in sorted(cells.items()):
        a = mann_whitney_auc(p[ii, stage], label[ii] == stage)
        if math.isfinite(a):
            out[c] = a
    return out


def auc_of(m: MatchedEpochs, p: np.ndarray, group: str, stage: int) -> dict:
    """AUC_s(Q) = mean over subjects of the mean over the subject's defined cells in Q."""
    cells = cell_aucs(m.subject, m.length, m.groups[group], m.label, p, stage)
    per_subject: dict[str, list[float]] = defaultdict(list)
    for (s, _), a in cells.items():
        per_subject[s].append(a)
    subj = {s: float(np.mean(v)) for s, v in sorted(per_subject.items())}
    return {"auc": float(np.mean(list(subj.values()))) if subj else float("nan"), "cells": len(cells),
            "subjects": len(subj), "subjectAuc": subj}


def auc_summary(m: MatchedEpochs, groups: Sequence[str] = AUC_GROUPS, stages: Sequence[str] = AUC_STAGES) -> dict:
    """AUC_s(Q) of the compared model (``X``) and the reference (``REF``) on the same matched epochs, and their
    difference (``delta`` = X - REF)."""
    out: dict = {"X": {}, "REF": {}, "delta": {}, "epochs": int(m.label.size), "excluded": m.excluded}
    for q in groups:
        for name, p in (("X", m.p_x), ("REF", m.p_ref)):
            out[name][q] = {s: auc_of(m, p, q, STAGE_INDEX[s]) for s in stages}
        out["delta"][q] = {s: out["X"][q][s]["auc"] - out["REF"][q][s]["auc"] for s in stages}
    return out


# --------------------------------------------------------------------------- section 7.3: regime contrast (F) ----

def _cells(lengths: Sequence[int]) -> list[tuple[str, int]]:
    return [(k, int(L)) for k in ("EARLY", "LATE") for L in lengths]


def _shares(pooled: np.ndarray, kind: str) -> np.ndarray:
    """Shares of DEEP and REM (last axis 4 -> 2) of pooled counts: of sleep or of the window; NaN where empty."""
    den = pooled[..., 1:].sum(axis=-1) if kind == "sleep" else pooled.sum(axis=-1)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(den[..., None] > 0, pooled[..., [DEEP, REM]] / np.where(den > 0, den, 1.0)[..., None],
                        np.nan)


def _deltas(pred: np.ndarray, lengths: Sequence[int], kind: str) -> tuple[np.ndarray, np.ndarray]:
    """(Delta_DEEP, Delta_REM) of pooled counts with axes (..., cell, 4), cells as :func:`_cells`."""
    sh = _shares(pred, kind)                                   # (..., cells, 2)
    n = len(lengths)
    early, late = sh[..., :n, :], sh[..., n:, :]
    d_deep = np.mean(early[..., 0] - late[..., 0], axis=-1)
    d_rem = np.mean(late[..., 1] - early[..., 1], axis=-1)
    return d_deep, d_rem


def regime_contrast(records: Sequence[Mapping], lengths: Sequence[int] = MATCHED_LENGTHS, kind: str = "sleep",
                    both_regimes: bool = True, n_boot: int = BOOT, seed: int = 0, level: float = 0.95) -> dict:
    """Section 7.3 on the MATCHED sessions in ``records`` (one model, variant and dataset, already restricted to
    E-cap): S_F = the subjects with at least one EARLY and one LATE session (``both_regimes``; all subjects
    otherwise); pooled predicted / true shares of DEEP and REM (``kind`` = ``sleep``: of LIGHT + DEEP + REM;
    ``window``: of all scored chunks) per regime and length; Delta_DEEP = mean over L of [EARLY - LATE], Delta_REM =
    mean over L of [LATE - EARLY]; r = Delta / Delta_true; percentile CI from ``n_boot`` resamples of the subjects
    (``default_rng(seed)``), each drawn subject contributing all its MATCHED sessions; a resample whose contrast is
    undefined (an empty cell) counts as -inf (:data:`UNDEFINED_RESAMPLE_RULE`, :func:`undefined_low_ci`)."""
    cells = _cells(lengths)
    cix = {c: i for i, c in enumerate(cells)}
    pred: dict[str, np.ndarray] = {}
    true: dict[str, np.ndarray] = {}
    regimes: dict[str, set] = defaultdict(set)
    for r in records:
        key = (r.get("regime"), int(r["L"]) if float(r["L"]).is_integer() else None)
        if key not in cix or not is_hybrid_scored(r):
            continue
        s = str(r["subject"])
        if s not in pred:
            pred[s], true[s] = np.zeros((len(cells), N_STAGES)), np.zeros((len(cells), N_STAGES))
        c = confusion(r)
        pred[s][cix[key]] += c.sum(axis=0)
        true[s][cix[key]] += c.sum(axis=1)
        regimes[s].add(key[0])
    subjects = sorted(s for s in pred if not both_regimes or regimes[s] == {"EARLY", "LATE"})
    nan = float("nan")
    if not subjects:
        return {"subjects": [], "n": 0, "kind": kind, "deltaDeep": nan, "deltaRem": nan, "trueDeltaDeep": nan,
                "trueDeltaRem": nan, "rDeep": nan, "rRem": nan, "ciDeep": [nan, nan], "ciRem": [nan, nan],
                "nanResamples": {"DEEP": 0, "REM": 0}}
    P = np.stack([pred[s] for s in subjects])                  # (n, cells, 4)
    T = np.stack([true[s] for s in subjects])
    dp, rp = _deltas(P.sum(axis=0), lengths, kind)
    dt, rt = _deltas(T.sum(axis=0), lengths, kind)
    n = len(subjects)
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, n, size=(int(n_boot), n))
    mult = np.zeros((int(n_boot), n))
    np.add.at(mult, (np.repeat(np.arange(int(n_boot)), n), idx.ravel()), 1.0)
    boot = np.einsum("bn,nck->bck", mult, P)
    bd, br = _deltas(boot, lengths, kind)
    sh_p, sh_t = _shares(P.sum(axis=0), kind), _shares(T.sum(axis=0), kind)
    return {
        "subjects": subjects, "n": n, "kind": kind, "bothRegimes": both_regimes,
        "pred": {f"{k}{L}": {"DEEP": float(sh_p[i, 0]), "REM": float(sh_p[i, 1])} for i, (k, L) in enumerate(cells)},
        "true": {f"{k}{L}": {"DEEP": float(sh_t[i, 0]), "REM": float(sh_t[i, 1])} for i, (k, L) in enumerate(cells)},
        "deltaDeep": float(dp), "deltaRem": float(rp), "trueDeltaDeep": float(dt), "trueDeltaRem": float(rt),
        "rDeep": float(dp / dt) if _finite(dt) and dt != 0 else nan,
        "rRem": float(rp / rt) if _finite(rt) and rt != 0 else nan,
        "ciDeep": undefined_low_ci(bd, level), "ciRem": undefined_low_ci(br, level),
        "nanResamples": {"DEEP": int((~np.isfinite(bd)).sum()), "REM": int((~np.isfinite(br)).sum())},
        "nanResampleRule": UNDEFINED_RESAMPLE_RULE, "resamples": int(n_boot), "seed": seed,
    }


# Section 7.3 does not say what a resample is worth in which a (regime, length) cell has no session (or no predicted
# sleep), so that its contrast is undefined. Decided before the code commit (amendment requested for section 7.3): it
# counts as the lowest possible contrast (-inf), i.e. it can only lower the interval and make F's CI criteria
# stricter; the number of such resamples is reported. With 27 Walch subjects in S_F none is expected.
UNDEFINED_RESAMPLE_RULE = "an undefined resample counts as a contrast of -inf (it can only lower the interval)"


def undefined_low_ci(x: np.ndarray, level: float = 0.95) -> list[float]:
    """The ``level`` percentile interval of bootstrap values ``x`` (numpy's default linear interpolation) in which a
    non-finite value counts as -inf: identical to ``np.quantile`` when every value is finite; otherwise a bound whose
    interpolation touches an undefined value is -inf. No values: NaN."""
    x = np.asarray(x, float)
    q = [(1 - level) / 2, 1 - (1 - level) / 2]
    if x.size == 0:                                            # no resample (n_boot = 0): no interval
        return [float("nan"), float("nan")]
    bad = ~np.isfinite(x)
    if not bad.any():
        return [float(v) for v in np.quantile(x, q)]
    k = int(bad.sum())
    if k == x.size:
        return [float("-inf"), float("-inf")]
    v = np.where(bad, float(np.min(x[~bad])) - 1.0, x)       # below every defined value: sorted first
    return [float("-inf") if int(np.floor(qq * (x.size - 1))) < k else float(np.quantile(v, qq)) for qq in q]


# ------------------------------------------------------------------------ section 7.4: featureless (R1) ----

def no_sleep(rec: Mapping) -> bool:
    """A featureless decode "without sleep": NOT_ESTIMATED, estimated by RULES, or predicted sleep < 50 % of its
    scored chunks (a decode without a scored chunk predicts no sleep)."""
    if not rec.get("estimated"):
        return True
    used = rec.get("estimatorUsed")
    if used == "RULES":
        return True
    if used != "HYBRID":
        raise ValueError(f"{rec.get('night')}: featureless decode by {used} (bundle rejected?)")
    tot = scored_chunks(rec)
    if tot <= 0:
        return True
    pred = confusion(rec).sum(axis=0)
    return float(pred[1:].sum()) < R1_SLEEP_SHARE_MIN * tot


def featureless_stats(records: Sequence[Mapping]) -> dict:
    """(a) pooled predicted DEEP and REM % of sleep over the decodes with sleep, (b) the "no sleep" share among all
    decodes, (c) the shares of the decodes with sleep violating GL and G (rows by predicted TST)."""
    n = len(records)
    ns = [r for r in records if no_sleep(r)]
    ok = [r for r in records if not no_sleep(r)]
    pooled = sum((confusion(r).sum(axis=0) for r in ok), np.zeros(N_STAGES))
    nan = float("nan")
    return {"decodes": n, "noSleep": len(ns), "noSleepShare": len(ns) / n if n else nan, "sleepDecodes": len(ok),
            "deepPctSleep": 100.0 * share_of_sleep(pooled, DEEP) if ok else nan,
            "remPctSleep": 100.0 * share_of_sleep(pooled, REM) if ok else nan,
            "glShare": float(np.mean([gl_flag(confusion(r).sum(axis=0)) for r in ok])) if ok else nan,
            "gShare": float(np.mean([g_flag(confusion(r).sum(axis=0)) for r in ok])) if ok else nan,
            "notEstimated": sum(1 for r in records if not r.get("estimated")),
            "rules": sum(1 for r in records if r.get("estimated") and r.get("estimatorUsed") == "RULES")}


def featureless_check(records: Sequence[Mapping], lengths: Sequence[int] = ANCHORS + MID_ANCHORS) -> dict:
    """Per length (one model and variant, every featureless decode of that length): :func:`featureless_stats`, the
    band of section 5.2 at t = TST_est(L), and the same per design and noise SD (reported)."""
    by_len: dict[int, list] = defaultdict(list)
    for r in records:
        by_len[int(round(float(r["L"])))].append(r)
    out = {}
    for L in sorted(lengths):
        rs = by_len.get(int(L), [])
        t = litprior.tst_est(L)
        b = litprior.band(t)
        per: dict[str, dict] = defaultdict(dict)
        for d in sorted({str(r.get("design")) for r in rs}):
            for sd in sorted({int(r.get("sd", 0)) for r in rs if str(r.get("design")) == d}):
                per[d][str(sd)] = featureless_stats([r for r in rs if str(r.get("design")) == d
                                                     and int(r.get("sd", 0)) == sd])
        out[str(L)] = {**featureless_stats(rs), "tstEst": t, "band": {"deepLo": b.deep_lo, "deepHi": b.deep_hi,
                                                                       "remLo": b.rem_lo, "remHi": b.rem_hi},
                       "lowerBounds": t >= litprior.LOWER_BOUND_MIN_TST, "byDesign": dict(per)}
    return out


# ------------------------------------------------------------------------------ section 7.4: LATE tail (R2) ----

def late_tail(records: Sequence[Mapping], lengths: Sequence[int] = TAIL_LENGTHS) -> dict:
    """Per L: over the LATE sessions of length L in ``records`` (one model and variant, E-cap), the share with
    predicted DEEP >= 50 % of the scored chunks minus the share with true DEEP >= 50 %; ``tail`` = the larger."""
    per = {}
    for L in lengths:
        rs = [r for r in records if r.get("regime") == "LATE" and float(r["L"]) == float(L) and scored_chunks(r) > 0]
        p = [window_share(confusion(r).sum(axis=0), DEEP) >= DEEP_HALF for r in rs]
        t = [window_share(confusion(r).sum(axis=1), DEEP) >= DEEP_HALF for r in rs]
        n = len(rs)
        per[str(L)] = {"sessions": n, "predShare": float(np.mean(p)) if n else float("nan"),
                       "trueShare": float(np.mean(t)) if n else float("nan"),
                       "excess": float(np.mean(p) - np.mean(t)) if n else float("nan"),
                       "predCount": int(sum(p)), "trueCount": int(sum(t))}
    ex = [v["excess"] for v in per.values()]
    return {"perLength": per, "tail": float(max(ex)) if ex and all(map(_finite, ex)) else float("nan")}


# ------------------------------------------------------------------------- section 7.4: LIT metrics (R3-R5) ----

def lit_weight_table(sessions: Sequence[Mapping], lengths: Sequence[int]) -> dict[int, litprior.LitWeights]:
    """Section 5.5 weights per length of ``sessions`` (items with ``id``, ``subject``, ``L`` and ``counts`` = the
    session's true label chunks, :func:`pools.label_counts`); one set of sessions (dataset, variant, pool)."""
    out = {}
    for L in lengths:
        ss = sorted((s for s in sessions if float(s["L"]) == float(L)), key=lambda s: str(s["id"]))
        if not ss:
            continue
        out[int(L)] = litprior.lit_weights([str(s["id"]) for s in ss], [str(s["subject"]) for s in ss],
                                           np.array([np.asarray(s["counts"], float) for s in ss]), L)
    return out


def lit_metrics(confusions: Mapping[str, np.ndarray], weights: Mapping[int, litprior.LitWeights],
                subjects: Mapping[str, str], lengths: Sequence[int] | None = None) -> dict:
    """LIT metrics of one model (``confusions``: session id -> its 4 x 4 matrix) on the weighted pool: per length the
    weighted pooled predicted DEEP and REM % of sleep and their distance to (D*(L), R*(L)); ``litDist`` = mean over
    the lengths of |DEEP - D*| + |REM - R*| (pp); ``guardExcess`` = mean over the lengths of the weighted share of
    sessions whose predicted composition violates GL minus the same for the true composition (rows by TST; the true
    composition of the same scored chunks); the LIT matrix = mean over the lengths of sum_i w_i C_i normalised to 1;
    ``accuracy`` = its trace, ``allLightAccuracy`` = its LIGHT row sum; ``kappa`` = mean over subjects of Cohen's
    kappa of sum_i w_i C_i over the subject's sessions (weights normalised to 1 per length)."""
    lengths = sorted(weights) if lengths is None else [int(L) for L in lengths]
    per: dict[str, dict] = {}
    mats = []
    subj_mat: dict[str, np.ndarray] = {}
    for L in lengths:
        lw = weights[int(L)]
        w = np.asarray(lw.w, float)
        w = w / w.sum()
        cs = []
        for i in lw.ids:
            if i not in confusions or confusions[i] is None:
                raise ValueError(f"LIT session {i} has no confusion matrix (not in E-cap?)")
            cs.append(np.asarray(confusions[i], float))
        C = np.einsum("i,ijk->jk", w, np.array(cs))
        tgt = litprior.target(L)
        col = C.sum(axis=0)
        d = 100.0 * share_of_sleep(col, DEEP)
        r = 100.0 * share_of_sleep(col, REM)
        gp = np.array([gl_flag(c.sum(axis=0)) for c in cs], float)
        gt = np.array([gl_flag(c.sum(axis=1)) for c in cs], float)
        per[str(L)] = {"deepPctSleep": d, "remPctSleep": r, "target": [tgt.deep, tgt.rem],
                       "dist": abs(d - tgt.deep) + abs(r - tgt.rem), "guardPred": float(w @ gp),
                       "guardTrue": float(w @ gt), "guardExcess": float(w @ gp - w @ gt),
                       "sessions": len(lw.ids), "ess": lw.ess, "lambda": lw.lam, "feasible": lw.feasible}
        tot = C.sum()
        mats.append(C / tot if tot > 0 else C)
        for i, wi, c in zip(lw.ids, w, cs):
            s = subjects[i]
            subj_mat[s] = subj_mat.get(s, np.zeros((N_STAGES, N_STAGES))) + wi * c
    M = np.mean(mats, axis=0) if mats else np.full((N_STAGES, N_STAGES), np.nan)
    sk = {s: report.kappa(m) for s, m in sorted(subj_mat.items())}
    ks = [k for k in sk.values() if _finite(k)]
    sa = {s: float(np.trace(m) / m.sum()) if m.sum() > 0 else float("nan") for s, m in sorted(subj_mat.items())}
    sl = {s: float(m[LIGHT].sum() / m.sum()) if m.sum() > 0 else float("nan") for s, m in sorted(subj_mat.items())}
    nan = float("nan")
    return {"perLength": per, "lengths": lengths,
            "litDist": float(np.mean([v["dist"] for v in per.values()])) if per else nan,
            "guardExcess": float(np.mean([v["guardExcess"] for v in per.values()])) if per else nan,
            "confusion": M.tolist(), "accuracy": float(np.trace(M)), "allLightAccuracy": float(M[LIGHT].sum()),
            "kappa": float(np.mean(ks)) if ks else nan, "kappaDefined": len(ks), "subjectKappa": sk,
            "subjectAccuracy": sa, "subjectLightAccuracy": sl}


def sleep_wake_kappa(records: Sequence[Mapping]) -> dict:
    """Mean per-subject sleep / wake kappa (each subject's scored sessions pooled, collapsed to wake / sleep)."""
    subj: dict[str, np.ndarray] = {}
    for r in records:
        if scored_chunks(r) > 0 and r.get("estimated"):
            s = str(r["subject"])
            subj[s] = subj.get(s, np.zeros((N_STAGES, N_STAGES))) + confusion(r)
    sk = {s: report.kappa(collapse_sleep_wake(m)) for s, m in sorted(subj.items())}
    ks = [k for k in sk.values() if _finite(k)]
    return {"kappa": float(np.mean(ks)) if ks else float("nan"), "defined": len(ks), "subjectKappa": sk}


def default_scope(confusions: Mapping[str, np.ndarray], weights: Mapping[int, litprior.LitWeights],
                  subjects: Mapping[str, str], featureless: Mapping[str, Mapping]) -> dict:
    """The default-population scope (section 7.4, reported): LitDist, guard excess and R1 restricted to the anchors
    >= 90 min (with ``sources.minSessionMin`` = 90 only sessions of 90-180 min are naps)."""
    lit = lit_metrics(confusions, weights, subjects, [L for L in DEFAULT_SCOPE_LENGTHS if L in weights])
    r1 = [c for L in DEFAULT_SCOPE_LENGTHS if str(L) in featureless for c in r1_criteria(featureless[str(L)], L, "")]
    return {"lengths": list(DEFAULT_SCOPE_LENGTHS), "litDist": lit["litDist"], "guardExcess": lit["guardExcess"],
            "r1": all(c["pass"] for c in r1) if r1 else False, "r1Criteria": r1}


# ---------------------------------------------------------------------- section 7.5: reported metrics ----

ANCHOR_BOUNDARIES: tuple[float, ...] = (45.0, 75.0, 105.0, 135.0, 160.0)


def nearest_anchor(length_min: float) -> int:
    """The anchor cell of a session (section 7.5, prior-matched metrics, reported only): boundaries 45, 75, 105, 135
    and 160 min. A session exactly at a boundary (W windows can have such lengths) is not covered by the
    nearest-anchor wording; it is put in the cell above (half-open cells [45, 75), ..., the convention of section 5's
    bins; amendment requested for section 7.5 before the code commit: "a session at a boundary belongs to the cell
    above")."""
    for b, a in zip(ANCHOR_BOUNDARIES, ANCHORS):
        if length_min < b:
            return a
    return ANCHORS[-1]


def prior_matched(records: Sequence[Mapping], min_row: float = 20.0) -> dict:
    """Prior-matched metrics (label-shift reading; one model, variant and dataset; EVAL pools of E-cap): per anchor
    cell k, C_k = the pooled confusion row-normalised (a row with < ``min_row`` chunks replaced by the row pooled over
    all cells), pi'_k = (A_k, (1 - A_k)(1 - D* - R*), (1 - A_k) D*, (1 - A_k) R*) with A_k the pooled true AWAKE
    share of the O sessions at L_k; acc' = sum_s pi'_s C_k[s, s], q = pi' C_k, E' = 100 sum_j |q_j - pi'_j|, kappa' =
    (acc' - sum pi' q) / (1 - sum pi' q); means over the anchors."""
    cells: dict[int, np.ndarray] = defaultdict(lambda: np.zeros((N_STAGES, N_STAGES)))
    onset_true: dict[int, np.ndarray] = defaultdict(lambda: np.zeros(N_STAGES))
    for r in records:
        if scored_chunks(r) <= 0:
            continue
        k = nearest_anchor(float(r["L"]))
        c = confusion(r)
        cells[k] += c
        if r.get("pool") == "O":
            onset_true[k] += c.sum(axis=1)
    if not cells:
        return {"perAnchor": {}, "accuracy": float("nan"), "E": float("nan"), "kappa": float("nan")}
    allrows = sum(cells.values(), np.zeros((N_STAGES, N_STAGES)))
    per = {}
    for k in ANCHORS:
        if k not in cells or onset_true[k].sum() <= 0:
            continue
        C = cells[k].copy()
        for s in range(N_STAGES):
            src = C[s] if C[s].sum() >= min_row else allrows[s]
            C[s] = src / src.sum() if src.sum() > 0 else np.zeros(N_STAGES)
        a_k = float(onset_true[k][AWAKE] / onset_true[k].sum())
        t = litprior.target(k)
        dd, rr = t.deep / 100.0, t.rem / 100.0
        pi = np.array([a_k, (1 - a_k) * (1 - dd - rr), (1 - a_k) * dd, (1 - a_k) * rr])
        acc = float(pi @ np.diag(C))
        q = pi @ C
        pe = float(pi @ q)
        per[str(k)] = {"prior": pi.tolist(), "accuracy": acc, "E": float(100.0 * np.abs(q - pi).sum()),
                       "kappa": (acc - pe) / (1 - pe) if pe < 1 else float("nan"), "predicted": q.tolist(),
                       "onsetAwake": a_k}
    mean = (lambda key: float(np.mean([v[key] for v in per.values()])) if per else float("nan"))
    return {"perAnchor": per, "accuracy": mean("accuracy"), "E": mean("E"), "kappa": mean("kappa")}


def _constant(x: np.ndarray) -> bool:
    """All values equal up to rounding (shares computed from proportional counts differ in the last bits)."""
    return float(np.ptp(x)) <= 1e-12 * max(1.0, float(np.abs(x).max()))


def _slope_rho(x: Sequence[float], y: Sequence[float]) -> dict:
    x, y = np.asarray(x, float), np.asarray(y, float)
    ok = np.isfinite(x) & np.isfinite(y)
    x, y = x[ok], y[ok]
    nan = float("nan")
    if x.size < 3 or _constant(x):
        return {"n": int(x.size), "slope": nan, "rho": nan}
    slope = float(np.polyfit(x, y, 1)[0])
    rho = nan if _constant(y) else float(spearmanr(x, y).statistic)
    return {"n": int(x.size), "slope": slope, "rho": rho}


def tracking(records: Sequence[Mapping], share: str = "window") -> dict:
    """Evidence tracking (one model, variant and dataset; O, M and R sessions of E-cap): session-level OLS slope and
    Spearman rho of predicted on true DEEP and REM share (``window`` shares, the diagnosis statistic, or shares of
    ``sleep``), pooled and within length (per anchor, then the mean over the anchors)."""
    f = window_share if share == "window" else share_of_sleep
    rs = [r for r in records if r.get("pool") in ("O", "M", "R") and scored_chunks(r) > 0]
    out: dict = {"share": share}
    for name, st in (("DEEP", DEEP), ("REM", REM)):
        x = [f(confusion(r).sum(axis=1), st) for r in rs]
        y = [f(confusion(r).sum(axis=0), st) for r in rs]
        pooled = _slope_rho(x, y)
        within = {}
        for L in ANCHORS:
            ii = [i for i, r in enumerate(rs) if float(r["L"]) == float(L)]
            within[str(L)] = _slope_rho([x[i] for i in ii], [y[i] for i in ii])
        fin = [v for v in within.values() if _finite(v["slope"])]
        out[name] = {**pooled, "withinLength": within,
                     "withinSlopeMean": float(np.mean([v["slope"] for v in fin])) if fin else float("nan"),
                     "withinRhoMean": float(np.mean([v["rho"] for v in fin if _finite(v["rho"])]))
                     if any(_finite(v["rho"]) for v in fin) else float("nan")}
    return out


def onset_bins(rows: Mapping[tuple[str, int], EpochRow], sessions: Iterable[str], bin_min: int = 10,
               length_min: int = 60) -> dict:
    """Pooled predicted vs true stage shares per ``bin_min``-minute bin of session time over the 60-s epochs of
    ``sessions`` (the O-60 sessions) with a label and a predicted stage (UNKNOWN epochs left out)."""
    sel = set(sessions)
    pred: dict[int, np.ndarray] = defaultdict(lambda: np.zeros(N_STAGES))
    true: dict[int, np.ndarray] = defaultdict(lambda: np.zeros(N_STAGES))
    for (sid, ep), r in rows.items():
        if sid not in sel or not r.label or r.stage not in STAGE_INDEX:
            continue
        b = int(ep) // bin_min
        pred[b][STAGE_INDEX[r.stage]] += 1
        true[b][STAGE_INDEX[r.label]] += 1
    out = {}
    for b in range(int(math.ceil(length_min / bin_min))):
        if b in pred:
            out[f"{b * bin_min}-{(b + 1) * bin_min}"] = {
                "epochs": int(pred[b].sum()), "pred": dict(zip(STAGES, (pred[b] / pred[b].sum()).tolist())),
                "true": dict(zip(STAGES, (true[b] / true[b].sum()).tolist()))}
    return out


def et_clock_bin(start_ms: int) -> str:
    """US Eastern clock bin of a window start (an inference about where BIDSleep participants slept): ``before 03:00``
    (from noon), ``03:00-05:00``, ``after 05:00`` (until noon)."""
    t = datetime.fromtimestamp(int(start_ms) / 1000.0, tz=timezone.utc).astimezone(ET)
    h = t.hour + t.minute / 60.0
    if 3.0 <= h < 5.0:
        return "03:00-05:00"
    if 5.0 <= h < 12.0:
        return "after 05:00"
    return "before 03:00"


def clock_table(records: Sequence[Mapping]) -> dict:
    """BIDSleep non-onset windows (M, R, W) by the US Eastern clock of their start: pooled true and predicted shares
    and accuracy per bin (records carry ``startMs``)."""
    by: dict[str, np.ndarray] = defaultdict(lambda: np.zeros((N_STAGES, N_STAGES)))
    n: Counter = Counter()
    for r in records:
        if r.get("pool") in ("M", "R", "W") and scored_chunks(r) > 0:
            b = et_clock_bin(int(r["startMs"]))
            by[b] += confusion(r)
            n[b] += 1
    out = {}
    for b in ("before 03:00", "03:00-05:00", "after 05:00"):
        if b in by:
            c = by[b]
            out[b] = {"sessions": n[b], "accuracy": float(np.trace(c) / c.sum()),
                      "true": dict(zip(STAGES, (c.sum(axis=1) / c.sum()).tolist())),
                      "pred": dict(zip(STAGES, (c.sum(axis=0) / c.sum()).tolist()))}
    return out


def _subject_means(values: Iterable[tuple[str, float]]) -> dict[str, float]:
    acc: dict[str, list[float]] = defaultdict(list)
    for subj, x in values:
        if _finite(x):
            acc[str(subj)].append(float(x))
    return {s: float(np.mean(v)) for s, v in sorted(acc.items())}


def perturbation_effects(original: Sequence[Mapping], perturbed: Sequence[Mapping], share: str = "window") -> dict:
    """Robustness (section 7.5, reported): per perturbation, the paired per-session change of the predicted DEEP and
    REM share (``window`` shares or shares of ``sleep``) of each perturbed copy (id ``<original>~<name>``) against its
    original, over the pairs where both are estimated by HYBRID with a scored chunk; mean change and a paired
    per-subject bootstrap CI (``report.paired_bootstrap`` of the subjects' mean shares)."""
    f = window_share if share == "window" else share_of_sleep
    orig = {str(r["night"]): r for r in original if is_hybrid_scored(r)}
    pairs: dict[str, list[tuple[Mapping, Mapping]]] = defaultdict(list)
    for r in perturbed:
        base, name = perturb.original_of(str(r["night"]))
        if name is not None and base in orig and is_hybrid_scored(r):
            pairs[name].append((orig[base], r))
    out = {}
    for name in sorted(pairs):
        ps = pairs[name]
        res = {"pairs": len(ps), "share": share}
        for label, st in (("DEEP", DEEP), ("REM", REM)):
            a = [(o["subject"], f(confusion(p).sum(axis=0), st)) for o, p in ps]
            b = [(o["subject"], f(confusion(o).sum(axis=0), st)) for o, p in ps]
            d = [x - y for (_, x), (_, y) in zip(a, b) if _finite(x) and _finite(y)]
            res[label] = {"meanChange": float(np.mean(d)) if d else float("nan"),
                          "subjectBootstrap": report.paired_bootstrap(_subject_means(a), _subject_means(b))}
        out[name] = res
    return out


def late_start_effects(onset: Sequence[Mapping], late: Sequence[Mapping]) -> dict:
    """LATESTART (section 7.5, reported): per (delta, L), pooled accuracy and predicted AWAKE share of the sessions that
    begin late against their unshifted onset sessions (``original`` in the meta), on the pairs where both are
    estimated by HYBRID with a scored chunk, with the paired per-subject accuracy difference."""
    orig = {str(r["night"]): r for r in onset if is_hybrid_scored(r)}
    groups: dict[str, list[tuple[Mapping, Mapping]]] = defaultdict(list)
    for r in late:
        o = orig.get(str(r.get("original")))
        if o is not None and is_hybrid_scored(r):
            groups[f"ls{int(r['delta'])}x{int(float(r['L']))}"].append((o, r))
    out = {}
    for key in sorted(groups):
        ps = groups[key]
        res = {"pairs": len(ps)}
        for name, idx in (("onset", 0), ("lateStart", 1)):
            c = sum((confusion(p[idx]) for p in ps), np.zeros((N_STAGES, N_STAGES)))
            res[name] = {"accuracy": float(np.trace(c) / c.sum()), "predAwake": float(c[:, AWAKE].sum() / c.sum()),
                         "trueAwake": float(c[AWAKE].sum() / c.sum())}

        def subj_acc(idx: int) -> dict[str, float]:
            m: dict[str, np.ndarray] = {}
            for p in ps:
                s = str(p[idx]["subject"])
                m[s] = m.get(s, np.zeros((N_STAGES, N_STAGES))) + confusion(p[idx])
            return {s: float(np.trace(x) / x.sum()) for s, x in sorted(m.items())}

        res["accuracyDiff"] = report.paired_bootstrap(subj_acc(1), subj_acc(0))
        out[key] = res
    return out


# -------------------------------------------------------------------------------------------- criteria ----

OPS: dict[str, Callable[[float, float], bool]] = {
    ">=": lambda a, b: a >= b, ">": lambda a, b: a > b, "<=": lambda a, b: a <= b, "<": lambda a, b: a < b}


def criterion(name: str, value, op: str, threshold, **detail) -> dict:
    """One recorded criterion; a NaN / missing value fails."""
    v = _nan(value)
    t = _nan(threshold)
    ok = math.isfinite(v) and math.isfinite(t) and OPS[op](v, t)
    return {"name": name, "value": v, "op": op, "threshold": t, "pass": bool(ok), **detail}


def _all(crits: Sequence[Mapping]) -> bool:
    return bool(crits) and all(c["pass"] for c in crits)


def _worse_min(per: Mapping[str, float]) -> tuple[float, str | None]:
    """The minimum over variants (NaN if any is NaN) and its variant."""
    vals = {v: _nan(x) for v, x in per.items()}
    if not vals or any(not math.isfinite(x) for x in vals.values()):
        return float("nan"), None
    v = min(vals, key=vals.get)
    return vals[v], v


def _worse_max(per: Mapping[str, float]) -> tuple[float, str | None]:
    vals = {v: _nan(x) for v, x in per.items()}
    if not vals or any(not math.isfinite(x) for x in vals.values()):
        return float("nan"), None
    v = max(vals, key=vals.get)
    return vals[v], v


# ------------------------------------------------------------------------------------- rule (i): D, F, R ----

def d_criteria(auc: Mapping[str, Mapping], variants: Sequence[str] = VARIANTS) -> list[dict]:
    """D: Delta AUC_s,v = AUC_s(X, v) - AUC_s(N1, v) on the matched epochs (``auc[v]`` = :func:`auc_summary` of X vs
    N1), minimum over v: ANCHOR AWAKE, DEEP, REM >= -0.02; EARLY DEEP >= -0.05; LATE REM >= -0.05."""
    out = []
    for q, s, thr in (("ANCHOR", "AWAKE", D_ANCHOR), ("ANCHOR", "DEEP", D_ANCHOR), ("ANCHOR", "REM", D_ANCHOR),
                      ("EARLY", "DEEP", D_EARLY_LATE), ("LATE", "REM", D_EARLY_LATE)):
        per = {v: auc.get(v, {}).get("delta", {}).get(q, {}).get(s, float("nan")) for v in variants}
        val, worse = _worse_min(per)
        out.append(criterion(f"D.{q}.{s}", val, ">=", thr, perVariant=per, worseVariant=worse))
    return out


def f_criteria(walch: Mapping[str, Mapping], bidsleep: Mapping[str, Mapping],
               variants: Sequence[str] = VARIANTS) -> list[dict]:
    """F (shares of sleep; ``walch[v]`` = :func:`regime_contrast` on S_F, ``bidsleep[v]`` on its subjects with both
    regimes): r_REM and r_DEEP >= 0.25 (minimum over v); the CI lower bounds of Delta_REM and Delta_DEEP > 0
    (minimum over v); on BIDSleep Delta_REM > 0 and Delta_DEEP > 0 in both variants; a criterion fails where the true
    contrast is <= 0."""
    out = []
    for s, rk, dk, tk, ck in (("DEEP", "rDeep", "deltaDeep", "trueDeltaDeep", "ciDeep"),
                              ("REM", "rRem", "deltaRem", "trueDeltaRem", "ciRem")):
        true_ok = all(_finite(walch.get(v, {}).get(tk)) and walch[v][tk] > 0 for v in variants)
        per_r = {v: walch.get(v, {}).get(rk, float("nan")) for v in variants}
        val, worse = _worse_min(per_r)
        out.append(criterion(f"F.r{s}", val if true_ok else float("nan"), ">=", F_RATIO, perVariant=per_r,
                             worseVariant=worse, trueContrastPositive=true_ok,
                             trueContrast={v: walch.get(v, {}).get(tk) for v in variants}))
        per_ci = {v: (walch.get(v, {}).get(ck) or [float("nan")])[0] for v in variants}
        val, worse = _worse_min(per_ci)
        out.append(criterion(f"F.ciLow{s}", val if true_ok else float("nan"), ">", 0.0, perVariant=per_ci,
                             worseVariant=worse, trueContrastPositive=true_ok))
        btrue = all(_finite(bidsleep.get(v, {}).get(tk)) and bidsleep[v][tk] > 0 for v in variants)
        per_b = {v: bidsleep.get(v, {}).get(dk, float("nan")) for v in variants}
        val, worse = _worse_min(per_b)
        out.append(criterion(f"F.bidsleep{s}", val if btrue else float("nan"), ">", 0.0, perVariant=per_b,
                             worseVariant=worse, trueContrastPositive=btrue))
    return out


def f0_criteria(walch_x: Mapping[str, Mapping], walch_ref: Mapping[str, Mapping],
                variants: Sequence[str] = VARIANTS) -> list[dict]:
    """F0 (tier 2): for s in {DEEP, REM} and each variant v, r_s(X, v) >= min(r_s(N1, v), 0) (point estimates, shares
    of sleep, S_F); fails where the true contrast is <= 0."""
    out = []
    for s, rk, tk in (("DEEP", "rDeep", "trueDeltaDeep"), ("REM", "rRem", "trueDeltaRem")):
        for v in variants:
            t = walch_x.get(v, {}).get(tk)
            ok = _finite(t) and t > 0
            ref = _nan(walch_ref.get(v, {}).get(rk))
            thr = min(ref, 0.0) if math.isfinite(ref) else float("nan")
            out.append(criterion(f"F0.r{s}.{v}", walch_x.get(v, {}).get(rk) if ok else float("nan"), ">=", thr,
                                 referenceRatio=ref, trueContrastPositive=ok))
    return out


def r1_criteria(stats: Mapping, length: int, variant: str) -> list[dict]:
    """R1 at one length and variant (:func:`featureless_check` entry): pooled DEEP and REM % of sleep <= the band's
    upper bounds; at t >= 45 min also >= the lower bounds; "no sleep" share <= 0.5; all decodes "no sleep" fails."""
    b = stats["band"]
    tag = {"L": int(length), "variant": variant}
    allno = stats["decodes"] > 0 and stats["sleepDecodes"] == 0
    out = [criterion("R1.deepHi", stats["deepPctSleep"], "<=", b["deepHi"], **tag),
           criterion("R1.remHi", stats["remPctSleep"], "<=", b["remHi"], **tag)]
    if stats["lowerBounds"]:
        out += [criterion("R1.deepLo", stats["deepPctSleep"], ">=", b["deepLo"], **tag),
                criterion("R1.remLo", stats["remPctSleep"], ">=", b["remLo"], **tag)]
    out.append(criterion("R1.noSleep", stats["noSleepShare"], "<=", R1_NO_SLEEP_MAX, **tag))
    out.append(criterion("R1.someSleep", 0.0 if allno or stats["decodes"] == 0 else 1.0, ">", 0.0, **tag))
    return out


def r_criteria(m: Mapping[str, Mapping], ref: Mapping[str, Mapping] | None, variants: Sequence[str] = VARIANTS,
               lengths: Sequence[int] = ANCHORS + MID_ANCHORS) -> list[dict]:
    """R1-R5 of one model (``m[v]`` with ``featureless``, ``lateTail``, ``lit``, ``litMid``); R5 against ``ref`` (N1;
    None: R5 holds by definition, the case of N1 itself)."""
    out = []
    for v in variants:
        fl = m[v]["featureless"]
        for L in sorted(lengths):
            if str(L) not in fl:
                out.append(criterion("R1.missing", float("nan"), "<=", 0.0, L=int(L), variant=v))
                continue
            out += r1_criteria(fl[str(L)], L, v)
    tail = {v: m[v]["lateTail"]["tail"] for v in variants}
    val, worse = _worse_max(tail)
    out.append(criterion("R2.lateTail", val, "<=", R2_TAIL_MAX, perVariant=tail, worseVariant=worse))
    ge = {v: m[v]["lit"]["guardExcess"] for v in variants}
    val, worse = _worse_max(ge)
    out.append(criterion("R3.guardExcess", val, "<=", R3_GUARD_EXCESS_MAX, perVariant=ge, worseVariant=worse))
    dm = {v: _nan(m[v]["litMid"]["litDist"]) - _nan(m[v]["lit"]["litDist"]) for v in variants}
    val, worse = _worse_max(dm)
    out.append(criterion("R4.midMinusAnchor", val, "<=", R4_MID_MAX, perVariant=dm, worseVariant=worse))
    ld = {v: m[v]["lit"]["litDist"] for v in variants}
    val, worse = _worse_max(ld)
    if ref is None:
        out.append({"name": "R5.litDistVsN1", "value": val, "op": "<=", "threshold": val, "pass": True,
                    "perVariant": ld, "note": "holds for N1 by definition"})
    else:
        rv, _ = _worse_max({v: ref[v]["lit"]["litDist"] for v in variants})
        out.append(criterion("R5.litDistVsN1", val, "<=", rv, perVariant=ld, worseVariant=worse))
    return out


def lit_dist_worse(m: Mapping[str, Mapping], variants: Sequence[str] = VARIANTS) -> float:
    return _worse_max({v: m[v]["lit"]["litDist"] for v in variants})[0]


def select(tier: Sequence[str], dist: Mapping[str, float], order: Sequence[str] = CANDIDATES,
           tie: float = LITDIST_TIE) -> tuple[str | None, list[str]]:
    """The candidate of ``tier`` with the smallest LitDist; ties = LitDist within ``tie`` pp of the lowest (inclusive),
    then ``order``."""
    if not tier:
        return None, []
    low = min(dist[c] for c in tier)
    tied = [c for c in order if c in tier and dist[c] - low <= tie]
    return tied[0], tied


def rule_i(metrics: Mapping[str, Mapping], auc: Mapping[str, Mapping], candidates: Sequence[str] = CANDIDATES,
           dropped: Mapping[str, str] | None = None, variants: Sequence[str] = VARIANTS,
           lengths: Sequence[int] = ANCHORS + MID_ANCHORS) -> dict:
    """Rule (i) (section 8). ``metrics[model]`` = ``{"walch": {v: {"contrast", "featureless", "lateTail", "lit",
    "litMid", ...}}, "bidsleep": {v: {"contrast"}}}`` for N1 and every candidate; ``auc[X][v]`` = :func:`auc_summary`
    of X against N1. Tier 1 = the candidates passing D, F and R; only if it is empty, tier 2 = those passing D, R and
    F0; the selection (smallest worse-variant LitDist, ties within 1.0 pp -> C0, C1, C2) is applied within the first
    non-empty tier; none -> keep N1. F and R are also evaluated for N1 (R5 holds for N1 by definition), for rule (ii).

    Verbatim: "On Walch, held-out subjects, worse sampling variant: a candidate (C0, C1, C2) qualifies if it passes
    D, F and R against the reference N1.
    D: its per-epoch AUC of AWAKE, DEEP and REM over the anchor sessions is at least N1's minus 0.02 each, its DEEP AUC
    over the early pool and its REM AUC over the late pool at least N1's minus 0.05.
    F: in sessions of 60 and 90 min, its predicted REM share of sleep is higher in the late than in the early pool and
    its DEEP share of sleep lower; each difference is at least a quarter of the true difference and has a 95 %
    subject-bootstrap interval above 0; and both differences are positive on BIDSleep.
    R: on featureless sessions of every anchor and mid-anchor length, its pooled DEEP and REM shares of sleep lie
    within the Tier-1 band of the length (upper bounds only below 45 min of sleep) and at most half of them are
    decoded without sleep; on late sessions of 60 and of 90 min, its share of sessions decoded with at least 50 % DEEP
    exceeds the true share by at most 0.10; on the literature-reweighted pool, its share of sessions beyond the Tier-2
    guard exceeds the true share by at most 0.05, its literature distance at the mid-anchor lengths exceeds that at
    the anchors by at most 2.0 pp, and its literature distance is not larger than N1's.
    Adopt the qualifying candidate with the smallest literature distance; ties within 1.0 pp go to C0, then C1, then
    C2. If none qualifies, adopt in the same way among the candidates that pass D and R and whose regime contrasts are
    not weaker than N1's where N1's are wrong and not in the wrong direction where N1's are right (a realistic level
    whose evidence-following is not shown; its output policy cannot be STAGES). If none of these either, keep N1."

    "Within 1.0 pp" is read inclusively (LitDist - lowest <= 1.0)."""
    dropped = dict(dropped or {})
    ref = metrics[REFERENCE]

    def walch_contrast(model):
        return {v: metrics[model]["walch"][v]["contrast"] for v in variants}

    def bid_contrast(model):
        return {v: metrics[model]["bidsleep"][v]["contrast"] for v in variants}

    n1_f = f_criteria(walch_contrast(REFERENCE), bid_contrast(REFERENCE), variants)
    n1_r = r_criteria(ref["walch"], None, variants, lengths)
    out: dict = {"reference": {"F": {"pass": _all(n1_f), "criteria": n1_f},
                               "R": {"pass": _all(n1_r), "criteria": n1_r},
                               "litDist": lit_dist_worse(ref["walch"], variants)},
                 "candidates": {}, "dropped": dropped}
    for c in candidates:
        if c in dropped:
            continue
        m = metrics[c]["walch"]
        d = d_criteria(auc[c], variants)
        f = f_criteria(walch_contrast(c), bid_contrast(c), variants)
        f0 = f0_criteria(walch_contrast(c), walch_contrast(REFERENCE), variants)
        r = r_criteria(m, ref["walch"], variants, lengths)
        rec = {"D": {"pass": _all(d), "criteria": d}, "F": {"pass": _all(f), "criteria": f},
               "F0": {"pass": _all(f0), "criteria": f0}, "R": {"pass": _all(r), "criteria": r},
               "litDist": lit_dist_worse(m, variants)}
        rec["tier1"] = rec["D"]["pass"] and rec["F"]["pass"] and rec["R"]["pass"]
        rec["tier2"] = rec["D"]["pass"] and rec["R"]["pass"] and rec["F0"]["pass"]
        out["candidates"][c] = rec
    cands = out["candidates"]
    tier1 = [c for c in candidates if c in cands and cands[c]["tier1"]]
    tier2 = [c for c in candidates if c in cands and cands[c]["tier2"]] if not tier1 else []
    tier, members = (1, tier1) if tier1 else ((2, tier2) if tier2 else (None, []))
    adopted, tied = select(members, {c: cands[c]["litDist"] for c in members})
    out.update({"tier1": tier1, "tier2": tier2, "tier": tier, "qualifying": members, "tied": tied,
                "adopted": adopted, "decision": adopted or "keep N1"})
    return out


# ------------------------------------------------------------------------------------------- rule (ii) ----

def rule_ii(model: str, f_pass: bool, r_pass: bool, lit: Mapping[str, Mapping], sw_kappa: Mapping[str, float],
            variants: Sequence[str] = VARIANTS) -> dict:
    """Rule (ii) (section 9) for ``model``: STAGES if it passes F and R of rule (i) and, on the literature-reweighted
    pool, min over v of (LIT accuracy - LIT all-LIGHT accuracy) >= 0.03 and min over v of LIT kappa >= 0.15; otherwise
    SLEEP_WAKE if min over v of the mean per-subject sleep / wake kappa over all EVAL sessions of E-cap >= 0.20;
    otherwise SKIP.

    Verbatim: "STAGES if the nap estimator passes F and R of rule (i) and, on the literature-reweighted pool, its
    accuracy is at least the 'all LIGHT' accuracy + 0.03 and its 4-class kappa at least 0.15; otherwise SLEEP_WAKE if
    its sleep/wake kappa over all evaluation sessions is at least 0.20; otherwise SKIP." """
    margin = {v: _nan(lit[v]["accuracy"]) - _nan(lit[v]["allLightAccuracy"]) for v in variants}
    kap = {v: lit[v]["kappa"] for v in variants}
    mv, mw = _worse_min(margin)
    kv, kw = _worse_min(kap)
    sv, sw = _worse_min(dict(sw_kappa))
    crit = [criterion("ii.F", 1.0 if f_pass else 0.0, ">", 0.0),
            criterion("ii.R", 1.0 if r_pass else 0.0, ">", 0.0),
            criterion("ii.litMargin", mv, ">=", POLICY_ACC_MARGIN, perVariant=margin, worseVariant=mw),
            criterion("ii.litKappa", kv, ">=", POLICY_KAPPA, perVariant=kap, worseVariant=kw)]
    swc = criterion("ii.swKappa", sv, ">=", POLICY_SW_KAPPA, perVariant=dict(sw_kappa), worseVariant=sw)
    stages = _all(crit)
    policy = "STAGES" if stages else ("SLEEP_WAKE" if swc["pass"] else "SKIP")
    return {"model": model, "policy": policy, "stagesCriteria": crit, "sleepWakeCriterion": swc}


def demote(policy: str, sw_pass: bool) -> str:
    """Rule (ii) as if the STAGES condition had failed: SLEEP_WAKE if the sleep / wake criterion holds, else SKIP."""
    return policy if policy != "STAGES" else ("SLEEP_WAKE" if sw_pass else "SKIP")


# ------------------------------------------------------------------------------------------ rule (iii) ----

def external_window(rec: Mapping) -> dict:
    """Section 13.8 quantities of one decoded ds006576 window: minutes per stage over the scored chunks, TST_p, the
    shares of TST_p d (DEEP) and m (REM), window shares, the GL / G flags (rows by TST_p) and usability (estimated by
    HYBRID with a scored chunk)."""
    usable = is_hybrid_scored(rec)
    nan = float("nan")
    if not usable:
        return {"usable": False}
    pred = confusion(rec).sum(axis=0)
    mins = minutes(pred)
    scored = float(pred.sum()) * CHUNK_MIN
    tst = mins["tst"]
    return {"usable": True, **{f"{k}Min": v for k, v in mins.items()}, "scoredMin": scored,
            "d": mins["deep"] / tst if tst > 0 else nan, "m": mins["rem"] / tst if tst > 0 else nan,
            "deepWindowShare": mins["deep"] / scored, "remWindowShare": mins["rem"] / scored,
            "gl": litprior.gl_violation(tst, mins["deep"], mins["rem"]),
            "g": litprior.g_violation(tst, mins["deep"], mins["rem"], mins["light"])}


def group_diff_bootstrap(a: Sequence[float], b: Sequence[float], n_boot: int = BOOT, seed: int = 0,
                         level: float = 0.95) -> dict:
    """mean(a) - mean(b) with a percentile CI from ``n_boot`` resamples within each group (``default_rng(seed)``; the
    resamples of ``a`` are drawn first)."""
    a, b = np.asarray(a, float), np.asarray(b, float)
    nan = float("nan")
    if a.size == 0 or b.size == 0:
        return {"diff": nan, "ciLow": nan, "ciHigh": nan, "nA": int(a.size), "nB": int(b.size)}
    rng = np.random.default_rng(seed)
    ma = a[rng.integers(0, a.size, size=(int(n_boot), a.size))].mean(axis=1)
    mb = b[rng.integers(0, b.size, size=(int(n_boot), b.size))].mean(axis=1)
    lo, hi = np.quantile(ma - mb, [(1 - level) / 2, 1 - (1 - level) / 2])
    return {"diff": float(a.mean() - b.mean()), "ciLow": float(lo), "ciHigh": float(hi), "nA": int(a.size),
            "nB": int(b.size), "resamples": int(n_boot), "seed": seed}


def external_summary(windows: Sequence[Mapping]) -> dict:
    """Rule-(iii) quantities of one model and variant. ``windows``: decoded records with ``group`` (NREM / REM /
    WAKE), ``window`` (oh / oh1 / m60 / rec) and ``complete`` (False for a truncated recording, excluded from every
    rule). Usable counts (NREM and REM oh windows, complete WAKE rec windows); group means of 100 d and 100 m over the
    usable oh windows with TST_p > 15 min; the share of usable oh windows of both groups with TST_p <= 15 min or a GL
    violation (and with G, reported); (d) on the rec windows: mean predicted DEEP window share of the NREM recordings
    minus that of the WAKE recordings with its bootstrap CI."""
    q = [(w, external_window(w)) for w in windows if w.get("complete", True)]
    oh = {g: [x for w, x in q if w["group"] == g and w["window"] == "oh" and x["usable"]] for g in ("NREM", "REM")}
    rec = {g: [x for w, x in q if w["group"] == g and w["window"] == "rec" and x["usable"]] for g in ("NREM", "WAKE")}

    def mean_pct(xs, key):
        v = [100.0 * x[key] for x in xs if x["tstMin"] > SHORT_SLEEP_MIN]
        return float(np.mean(v)) if v else float("nan")

    both = oh["NREM"] + oh["REM"]
    fail = [x["tstMin"] <= SHORT_SLEEP_MIN or x["gl"] for x in both]
    fail_g = [x["tstMin"] <= SHORT_SLEEP_MIN or x["g"] for x in both]
    return {
        "usable": {"NREM": len(oh["NREM"]), "REM": len(oh["REM"]), "WAKE": len(rec["WAKE"])},
        "nremDeepPct": mean_pct(oh["NREM"], "d"), "nremRemPct": mean_pct(oh["NREM"], "m"),
        "remDeepPct": mean_pct(oh["REM"], "d"), "remRemPct": mean_pct(oh["REM"], "m"),
        "failShare": float(np.mean(fail)) if fail else float("nan"),
        "failShareG": float(np.mean(fail_g)) if fail_g else float("nan"),
        "deepDiff": group_diff_bootstrap([x["deepWindowShare"] for x in rec["NREM"]],
                                         [x["deepWindowShare"] for x in rec["WAKE"]]),
    }


def iii_criteria(ext: Mapping[str, Mapping], variants: Sequence[str] = VARIANTS) -> dict:
    """(a)-(d) of rule (iii) for one model (``ext[v]`` = :func:`external_summary`), in both variants: (a) the bands of
    section 5.2 at Table 1's TST (NREM mean 100 d; REM mean 100 d and 100 m); (b) NREM mean 100 m <= 6.9; (c) the
    failure share <= 0.098; (d) the rec DEEP contrast (NREM minus complete WAKE recordings) > 0 and its CI lower
    bound > 0 (13.8 (d): "... > 0, with the 95 % percentile CI ... above 0")."""
    bn = litprior.band(TABLE1_TST_MIN["NREM"])
    br = litprior.band(TABLE1_TST_MIN["REM"])
    out: dict[str, list] = {"a": [], "b": [], "c": [], "d": []}
    for v in variants:
        e = ext[v]
        out["a"] += [criterion("iii.a.nremDeepLo", e["nremDeepPct"], ">=", bn.deep_lo, variant=v),
                     criterion("iii.a.nremDeepHi", e["nremDeepPct"], "<=", bn.deep_hi, variant=v),
                     criterion("iii.a.remDeepLo", e["remDeepPct"], ">=", br.deep_lo, variant=v),
                     criterion("iii.a.remDeepHi", e["remDeepPct"], "<=", br.deep_hi, variant=v),
                     criterion("iii.a.remRemLo", e["remRemPct"], ">=", br.rem_lo, variant=v),
                     criterion("iii.a.remRemHi", e["remRemPct"], "<=", br.rem_hi, variant=v)]
        out["b"].append(criterion("iii.b.nremRem", e["nremRemPct"], "<=", REM_FREE_MAX_PCT, variant=v))
        out["c"].append(criterion("iii.c.failShare", e["failShare"], "<=", FAIL_SHARE_MAX, variant=v,
                                  reportedG=e.get("failShareG"), reportedAgainst=FAIL_SHARE_ALL93))
        d = e["deepDiff"]
        out["d"] += [criterion("iii.d.deepDiff", d["diff"], ">", 0.0, variant=v),
                     criterion("iii.d.deepDiffCiLow", d["ciLow"], ">", 0.0, variant=v, diff=d["diff"])]
    return {k: {"pass": _all(c), "criteria": c} for k, c in out.items()}


def void_reasons(ext: Mapping[str, Mapping], variants: Sequence[str] = VARIANTS,
                 global_reasons: Sequence[str] = ()) -> list[str]:
    """Why the test of one model is void: fewer than 19 usable NREM / REM oh windows or complete WAKE rec windows in
    either variant, or any global reason (YASA unavailable, a digest mismatch, a failed pin or freeze check)."""
    out = list(global_reasons)
    for v in variants:
        for g, n in ext[v]["usable"].items():
            if n < MIN_USABLE:
                out.append(f"{v}: {n} usable {g} recordings (< {MIN_USABLE})")
    return out


def rule_iii(ext: Mapping[str, Mapping[str, Mapping]], adopted: str | None, tier: int | None,
             policy_n1: Mapping, policy_x: Mapping | None, global_void: Sequence[str] = (),
             variants: Sequence[str] = VARIANTS) -> dict:
    """Rule (iii) (section 13.8). ``ext["S"]`` (N1 = the shipped bundle) and, when a candidate is adopted,
    ``ext["X"]`` map variants to :func:`external_summary`; ``policy_n1`` / ``policy_x`` are the :func:`rule_ii`
    records. X is installed only if its test is not void and it passes (a), (c), (d) and, when adopted in the first
    tier, (b), in both variants; (b) of a tier-2 candidate is reported. The model in effect (X if installed, else N1)
    keeps its rule-(ii) policy unless its test is not void, the policy is STAGES and it fails (a), (b) or (c): then
    rule (ii) as if the STAGES condition had failed. A void test installs nothing and demotes nothing.

    Verbatim: "ds006576 is decoded only after rules (i) and (ii) are written for N1 and for the adopted candidate. An
    adopted candidate is installed only if, on the real naps of ds006576, in both sampling variants and with a test
    that is not void, (a) the NREM group's mean DEEP share of predicted sleep, and the REM group's mean DEEP and REM
    shares of predicted sleep, lie within the Tier-1 bands at the groups' published total sleep times, (b) for a
    candidate adopted in the first tier of rule (i), the NREM group's mean REM share of predicted sleep is at most 6.9
    %, (c) at most 9.8 % of the naps are decoded with 15 minutes of sleep or less or are flagged by the literature-only
    guard, and (d) NREM-group recordings get more predicted DEEP than quiet-wake recordings (95 % bootstrap interval
    above 0); otherwise the shipped nap model stays with its rule-(ii) policy. If the test is not void and the nap
    estimator in effect has STAGES but fails (a), (b) or (c), its policy is set by rule (ii) as if it had failed the
    STAGES condition. ds006576 never chooses between candidates, never changes a fitted value and never raises an
    output policy." """
    models = {"S": policy_n1}
    if adopted is not None:
        if policy_x is None:
            raise ValueError(f"rule (iii) needs rule (ii) of the adopted candidate {adopted}")
        models["X"] = policy_x
    res: dict = {"adopted": adopted, "tier": tier, "models": {}}
    for k in models:
        if k not in ext:
            res["models"][k] = {"void": ["no decodes"], "criteria": None}
            continue
        vr = void_reasons(ext[k], variants, global_void)
        res["models"][k] = {"void": vr, "criteria": iii_criteria(ext[k], variants)}
    install = False
    if adopted is not None:
        x = res["models"]["X"]
        if not x["void"]:
            cr = x["criteria"]
            install = cr["a"]["pass"] and cr["c"]["pass"] and cr["d"]["pass"] and (tier != 1 or cr["b"]["pass"])
        res["installGates"] = ["a", "c", "d"] + (["b"] if tier == 1 else [])
    in_effect = "X" if install else "S"
    pol = models[in_effect]
    eff = res["models"][in_effect]
    demoted = False
    policy = pol["policy"]
    if not eff["void"] and policy == "STAGES":
        cr = eff["criteria"]
        if not (cr["a"]["pass"] and cr["b"]["pass"] and cr["c"]["pass"]):
            policy = demote(policy, pol["sleepWakeCriterion"]["pass"])
            demoted = True
    res.update({"install": install, "inEffect": "X" if install else "N1", "policyBefore": pol["policy"],
                "policy": policy, "demoted": demoted})
    return res


# ------------------------------------------------------------------------------------ generated reasons ----

def failure_reasons(rule: Mapping) -> list[str]:
    """One line per candidate (and N1) naming the first failing criteria of each failed block of rule (i), e.g.
    ``"C1 failed F: F.rREM = 0.120 (>= 0.25 required, worse variant mean2min)"`` (section 17: reasons are generated
    from the recorded criteria, never written by hand)."""
    lines = []

    def describe(name: str, block: str, crit: Sequence[Mapping]) -> str:
        bad = [c for c in crit if not c["pass"]]
        parts = []
        for c in bad[:3]:
            where = ", ".join(f"{k} {c[k]}" for k in ("L", "variant", "worseVariant") if c.get(k) is not None)
            parts.append(f"{c['name']} = {c['value']:.3f} ({c['op']} {c['threshold']:.3f} required"
                         + (f", {where}" if where else "") + ")")
        more = f" and {len(bad) - 3} more" if len(bad) > 3 else ""
        return f"{name} failed {block}: " + "; ".join(parts) + more

    for c, rec in rule.get("candidates", {}).items():
        for block in ("D", "F", "R") + (("F0",) if not rule.get("tier1") else ()):
            if not rec[block]["pass"]:
                lines.append(describe(c, block, rec[block]["criteria"]))
    for c, why in rule.get("dropped", {}).items():
        lines.append(f"{c} dropped: {why}")
    ref = rule.get("reference", {})
    for block in ("F", "R"):
        if ref.get(block) and not ref[block]["pass"]:
            lines.append(describe(REFERENCE, block, ref[block]["criteria"]))
    return lines
