"""Supervised statistics of labelled nights (pure functions on stage-index sequences on the epoch grid).

A sequence holds one stage index (0..3, see ``util.STAGES``) per epoch and -1 where the epoch has no label.
Labelled runs between unlabelled epochs are *segments*; a *bout* is a maximal run of one stage inside a segment.
The first bout of a segment is left-censored (it started before the window / gap), the last is right-censored.

Conventions (shared by the HSMM bundle fields and the HMM AlgoParams estimates):
- Bout durations (gamma, minutes) are fitted on UNCENSORED, UNSPLIT bout lengths. If a stage has fewer than
  ``min_uncensored_bouts`` uncensored bouts, the censored ones are used too. ``scipy.stats.gamma.fit(floc=0)``;
  on failure / degenerate data: method of moments (shape clipped to ``shape_bounds``); one bout -> exponential.
  Long bouts are not split for the duration fit: the HSMM puts the tail mass P(D >= Dmax) on Dmax.
- Bout transitions P(next | current): counts of consecutive bouts inside segments (never across unlabelled gaps).
  A bout longer than ``max_bout_min`` is split into ceil(L / Dmax) consecutive pieces of at most Dmax epochs
  (Dmax = floor(max_bout_min / epoch_min)); the split contributes ceil(L / Dmax) − 1 SELF transitions, which is
  the only source of diagonal mass (the HSMM allows a self-transition only as listed in the matrix). A pseudo-count
  is added to the off-diagonal entries only; rows without data become uniform over the other stages.
- Initial bout distribution: stage of the first labelled epoch of each night, plus a pseudo-count.
- Time prior P(stage | bin since session start), bins of ``bin_minutes``: counts over labelled epochs; the bins after
  the last bin with at least ``time_prior_min_count`` epochs are merged into it (the app uses the last bin past the
  end); Laplace smoothing ``alpha`` per class.
- HMM (AlgoParams shape): Gaussian MLE (population sd, floor ``sd_floor``) of hrNorm/hrVar/hrSlope per stage over
  epochs with HR data; meanBouts = mean uncensored bout length (minutes, same fallback as the durations);
  transition weights = row-normalized off-diagonal bout-transition counts WITHOUT pseudo-count (the HMM replaces
  zeros by epsilon itself); rows without data keep the physiology defaults.
"""

from __future__ import annotations

import math
import warnings
from dataclasses import dataclass, field
from typing import Iterable, Mapping, Sequence

import numpy as np
from scipy import stats as sps

from .util import AWAKE, DEEP, LIGHT, N_STAGES, PARAM_STAGE_KEYS, REM, STAGES

# Defaults of :core-algo AlgoParams (EmissionTable.PHYSIOLOGY, MeanBouts(), TransitionWeights()).
PHYSIOLOGY_EMISSIONS: dict = {
    "awake": {"hrNorm": {"mean": 2.8, "sd": 0.9}, "hrVar": {"mean": 1.8, "sd": 0.7}, "hrSlope": {"mean": 0.0, "sd": 0.8}},
    "light": {"hrNorm": {"mean": 1.0, "sd": 0.45}, "hrVar": {"mean": 0.9, "sd": 0.4}, "hrSlope": {"mean": 0.0, "sd": 0.4}},
    "deep": {"hrNorm": {"mean": 0.1, "sd": 0.35}, "hrVar": {"mean": 0.6, "sd": 0.3}, "hrSlope": {"mean": 0.0, "sd": 0.3}},
    "rem": {"hrNorm": {"mean": 1.7, "sd": 0.55}, "hrVar": {"mean": 1.4, "sd": 0.5}, "hrSlope": {"mean": 0.0, "sd": 0.5}},
}
DEFAULT_MEAN_BOUTS: dict = {"awakeMin": 5.0, "lightMin": 20.0, "deepMin": 20.0, "remMin": 20.0}
DEFAULT_TRANSITIONS: dict = {
    "awakeToLight": 0.9, "awakeToDeep": 0.0, "awakeToRem": 0.1,
    "lightToAwake": 0.2, "lightToDeep": 0.45, "lightToRem": 0.35,
    "deepToAwake": 0.13, "deepToLight": 0.85, "deepToRem": 0.02,
    "remToAwake": 0.3, "remToLight": 0.7, "remToDeep": 0.0,
}
EMISSION_DIMS: tuple[str, ...] = ("hrNorm", "hrVar", "hrSlope")
# Defaults of :core-algo HmmTimePrior() (the specification's time-dependent priors and initial distribution).
SPEC_TIME_PRIOR: dict = {
    "deepTimeIntercept": 1.6, "deepTimeSlope": 1.2, "deepTimeFloor": 0.3,
    "remTimeIntercept": 0.4, "remTimeSlope": 1.2,
    "remLockoutMin": 45.0, "remLockoutFactor": 0.05,
    "initialAwake": 0.5, "initialLight": 0.5,
}
TIME_PRIOR_KEYS: tuple[str, ...] = tuple(SPEC_TIME_PRIOR)
HR_NORM_ORDER: tuple[str, ...] = ("deep", "light", "rem", "awake")     # required order of the hrNorm means


def mean_bout_key(stage: int) -> str:
    return PARAM_STAGE_KEYS[stage] + "Min"


def transition_key(i: int, j: int) -> str:
    return PARAM_STAGE_KEYS[i] + "To" + STAGES[j].capitalize()


TRANSITION_KEYS: tuple[str, ...] = tuple(transition_key(i, j) for i in range(N_STAGES) for j in range(N_STAGES)
                                         if i != j)
assert set(TRANSITION_KEYS) == set(DEFAULT_TRANSITIONS)


# ----------------------------------------------------------------------------------------------------- bouts ----

@dataclass(frozen=True)
class Bout:
    stage: int
    start: int
    length: int                  # epochs
    left_censored: bool
    right_censored: bool

    @property
    def censored(self) -> bool:
        return self.left_censored or self.right_censored


def runs(seq: Sequence[int]) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """(values, starts, lengths) of the maximal constant runs of ``seq``."""
    a = np.asarray(seq)
    if a.size == 0:
        e = np.zeros(0, dtype=np.int64)
        return a.astype(np.int64), e, e
    change = np.flatnonzero(a[1:] != a[:-1]) + 1
    starts = np.r_[0, change]
    ends = np.r_[change, a.size]
    return a[starts].astype(np.int64), starts.astype(np.int64), (ends - starts).astype(np.int64)


def segments(seq: Sequence[int]) -> list[list[Bout]]:
    """Bouts grouped by labelled segment (segments are separated by unlabelled epochs, value -1)."""
    vals, starts, lens = runs(seq)
    out: list[list[Bout]] = []
    cur: list[tuple[int, int, int]] = []
    for v, s, n in list(zip(vals, starts, lens)) + [(-1, -1, 0)]:
        if v < 0:
            if cur:
                m = len(cur)
                out.append([Bout(int(st), int(s0), int(ln), i == 0, i == m - 1) for i, (st, s0, ln) in enumerate(cur)])
                cur = []
            continue
        if not 0 <= v < N_STAGES:
            raise ValueError(f"stage index out of range: {v}")
        cur.append((v, s, n))
    return out


def extract_bouts(seq: Sequence[int]) -> list[Bout]:
    return [b for seg in segments(seq) for b in seg]


def _as_list(sequences: Mapping[str, np.ndarray] | Iterable[np.ndarray]) -> list[np.ndarray]:
    if isinstance(sequences, Mapping):
        return [sequences[k] for k in sorted(sequences)]
    return list(sequences)


def bout_lengths(sequences, include_censored: bool) -> dict[int, np.ndarray]:
    """Stage -> bout lengths in epochs."""
    acc: dict[int, list[int]] = {s: [] for s in range(N_STAGES)}
    for seq in _as_list(sequences):
        for b in extract_bouts(seq):
            if include_censored or not b.censored:
                acc[b.stage].append(b.length)
    return {s: np.asarray(v, dtype=float) for s, v in acc.items()}


def _duration_samples(sequences, epoch_min: float, min_uncensored: int) -> tuple[dict[int, np.ndarray], dict[int, str]]:
    unc = bout_lengths(sequences, include_censored=False)
    allb = bout_lengths(sequences, include_censored=True)
    out, used = {}, {}
    for s in range(N_STAGES):
        if len(unc[s]) >= min_uncensored or len(allb[s]) == len(unc[s]):
            out[s], used[s] = unc[s] * epoch_min, "uncensored"
        else:
            out[s], used[s] = allb[s] * epoch_min, "all (few uncensored bouts)"
    return out, used


# ----------------------------------------------------------------------------------------------------- gamma ----

@dataclass(frozen=True)
class GammaFit:
    shape: float
    scale: float
    n: int
    method: str                  # mle | moments | exponential | default

    @property
    def mean(self) -> float:
        return self.shape * self.scale

    def to_json(self) -> dict:
        return {"shape": float(self.shape), "scale": float(self.scale)}


def fit_gamma(x_minutes: Sequence[float], default_mean: float,
              shape_bounds: tuple[float, float] = (0.05, 50.0)) -> GammaFit:
    """Gamma MLE with loc fixed at 0; method-of-moments fallback; exponential for a single value."""
    x = np.asarray(x_minutes, dtype=float)
    x = x[np.isfinite(x) & (x > 0)]
    n = int(x.size)
    lo, hi = shape_bounds
    if n == 0:
        return GammaFit(1.0, float(default_mean), 0, "default")
    m = float(x.mean())
    v = float(x.var())
    if n == 1:
        return GammaFit(1.0, m, 1, "exponential")
    if v > 0:
        try:
            with warnings.catch_warnings(), np.errstate(all="ignore"):
                warnings.simplefilter("ignore")
                a, _loc, scale = sps.gamma.fit(x, floc=0)
            if all(map(math.isfinite, (a, scale))) and lo <= a <= hi and scale > 0:
                return GammaFit(float(a), float(scale), n, "mle")
        except Exception:  # noqa: BLE001 - any optimizer failure -> moments
            pass
        a = float(np.clip(m * m / v, lo, hi))
    else:
        a = hi
    return GammaFit(a, m / a, n, "moments")


# ------------------------------------------------------------------------------------------------ transitions ----

def max_bout_epochs(max_bout_min: float, epoch_min: float) -> int:
    return max(1, int(math.floor(max_bout_min / epoch_min + 1e-9)))


def bout_transition_counts(sequences, epoch_min: float, max_bout_min: float = 120.0) -> np.ndarray:
    """4x4 counts (row = from). Long bouts add ceil(L / Dmax) − 1 self transitions (see module doc)."""
    dmax = max_bout_epochs(max_bout_min, epoch_min)
    c = np.zeros((N_STAGES, N_STAGES), dtype=float)
    for seq in _as_list(sequences):
        for seg in segments(seq):
            for i, b in enumerate(seg):
                c[b.stage, b.stage] += math.ceil(b.length / dmax) - 1
                if i + 1 < len(seg):
                    c[b.stage, seg[i + 1].stage] += 1
    return c


def transition_matrix(counts: np.ndarray, pseudocount: float = 0.5) -> np.ndarray:
    p = np.asarray(counts, dtype=float).copy()
    off = ~np.eye(N_STAGES, dtype=bool)
    p[off] += pseudocount
    for i in range(N_STAGES):
        tot = p[i].sum()
        if tot <= 0:
            p[i] = off[i] / off[i].sum()
        else:
            p[i] /= tot
    return p


def initial_distribution(sequences, pseudocount: float = 0.5) -> np.ndarray:
    c = np.full(N_STAGES, float(pseudocount))
    for seq in _as_list(sequences):
        lab = np.asarray(seq)
        lab = lab[lab >= 0]
        if lab.size:
            c[int(lab[0])] += 1
    if c.sum() <= 0:
        return np.full(N_STAGES, 1.0 / N_STAGES)
    return c / c.sum()


def time_prior_counts(sequences, epoch_min: float, bin_minutes: float = 30.0) -> np.ndarray:
    """(bins, 4) counts of labelled epochs per bin of ``bin_minutes`` since the session start (epoch 0)."""
    parts = []
    for seq in _as_list(sequences):
        a = np.asarray(seq)
        idx = np.flatnonzero(a >= 0)
        if idx.size:
            parts.append((np.floor(idx * epoch_min / bin_minutes + 1e-9).astype(np.int64), a[idx].astype(np.int64)))
    if not parts:
        return np.zeros((0, N_STAGES))
    n_bins = 1 + max(int(b.max()) for b, _ in parts)
    c = np.zeros((n_bins, N_STAGES))
    for b, s in parts:
        np.add.at(c, (b, s), 1)
    return c


def merge_sparse_tail(counts: np.ndarray, min_count: float) -> np.ndarray:
    """Merges the bins after the last bin with >= min_count epochs into that bin (one bin if none qualifies)."""
    c = np.asarray(counts, dtype=float)
    if c.shape[0] == 0:
        return c
    n = c.sum(axis=1)
    ok = np.flatnonzero(n >= min_count)
    last = int(ok[-1]) if ok.size else 0
    out = c[: last + 1].copy()
    out[last] += c[last + 1:].sum(axis=0)
    return out


def time_prior_table(sequences, epoch_min: float, bin_minutes: float = 30.0, alpha: float = 1.0,
                     min_count: float = 300.0) -> tuple[np.ndarray, np.ndarray]:
    """(table P(stage | bin), merged counts). Without any labelled epoch: one uniform bin."""
    counts = merge_sparse_tail(time_prior_counts(sequences, epoch_min, bin_minutes), min_count)
    if counts.shape[0] == 0:
        return np.full((1, N_STAGES), 1.0 / N_STAGES), np.zeros((1, N_STAGES))
    table = (counts + alpha) / (counts.sum(axis=1, keepdims=True) + N_STAGES * alpha)
    return table, counts


def class_priors(y: np.ndarray, weights: np.ndarray | None = None) -> np.ndarray:
    y = np.asarray(y)
    w = np.ones(y.shape[0]) if weights is None else np.asarray(weights, dtype=float)
    keep = y >= 0
    c = np.bincount(y[keep].astype(np.int64), weights=w[keep], minlength=N_STAGES)[:N_STAGES]
    if c.sum() <= 0:
        raise ValueError("no labelled rows")
    return c / c.sum()


# ---------------------------------------------------------------------------------------------------- HMM MLE ----

def hmm_emissions(hr: np.ndarray, y: np.ndarray, sd_floor: float = 0.05, min_n: int = 10,
                  fallback: Mapping = PHYSIOLOGY_EMISSIONS) -> tuple[dict, list[str]]:
    """EmissionTable JSON from rows ``hr[:, (hrNorm, hrVar, hrSlope)]`` with stage labels ``y`` (only rows with
    HR data should be passed). Returns (table, notes)."""
    hr = np.asarray(hr, dtype=float)
    y = np.asarray(y)
    notes: list[str] = []
    table: dict = {}
    for s, key in enumerate(PARAM_STAGE_KEYS):
        st: dict = {}
        for d, dim in enumerate(EMISSION_DIMS):
            x = hr[(y == s), d]
            x = x[np.isfinite(x)]
            if x.size < min_n:
                st[dim] = dict(fallback[key][dim])
                notes.append(f"{key}.{dim}: {x.size} values < {min_n}, physiology default kept")
            else:
                st[dim] = {"mean": float(x.mean()), "sd": float(max(x.std(), sd_floor))}
        table[key] = st
    if not hr_norm_ordered(table):
        notes.append("hrNorm means violate DEEP < LIGHT < REM < AWAKE (per-night EM will roll back)")
    return table, notes


def hr_norm_ordered(emissions: Mapping) -> bool:
    """The specification's order constraint mu_DEEP < mu_LIGHT < mu_REM < mu_AWAKE on hrNorm (core-algo
    ``isHrNormOrdered``): per-night EM and the HMM personalization roll back to the table when it fails."""
    mu = [float(emissions[k]["hrNorm"]["mean"]) for k in HR_NORM_ORDER]
    return all(a < b for a, b in zip(mu, mu[1:]))


def mean_bouts(sequences, epoch_min: float, min_uncensored: int = 5) -> tuple[dict, list[str]]:
    samples, _ = _duration_samples(sequences, epoch_min, min_uncensored)
    out, notes = {}, []
    for s in range(N_STAGES):
        k = mean_bout_key(s)
        if samples[s].size:
            out[k] = float(samples[s].mean())
        else:
            out[k] = DEFAULT_MEAN_BOUTS[k]
            notes.append(f"{k}: no bouts, default kept")
    return out, notes


def transition_weights(counts: np.ndarray) -> tuple[dict, list[str]]:
    c = np.asarray(counts, dtype=float)
    out, notes = {}, []
    for i in range(N_STAGES):
        off = [j for j in range(N_STAGES) if j != i]
        tot = sum(c[i, j] for j in off)
        for j in off:
            k = transition_key(i, j)
            out[k] = float(c[i, j] / tot) if tot > 0 else DEFAULT_TRANSITIONS[k]
        if tot <= 0:
            notes.append(f"{PARAM_STAGE_KEYS[i]} row: no transitions, defaults kept")
    return {k: out[k] for k in TRANSITION_KEYS}, notes


# --------------------------------------------------------------------------------------- HMM time prior MLE ----

_SELF_CLAMP = (0.5, 0.9999)                  # core-algo TransitionModel MIN_SELF / MAX_SELF
# (deepTimeIntercept, deepTimeSlope, deepTimeFloor, remTimeIntercept, remTimeSlope, remLockoutFactor): the ranges of
# AlgoParamsValidator (all >= 0, factor in [0, 1]); the small lower bounds keep the likelihood finite.
_TP_NAMES = ("deepTimeIntercept", "deepTimeSlope", "deepTimeFloor", "remTimeIntercept", "remTimeSlope",
             "remLockoutFactor")
_TP_BOUNDS = ((1e-3, 50.0), (0.0, 50.0), (0.0, 50.0), (1e-3, 50.0), (0.0, 50.0), (1e-4, 1.0))


def _base_rows(mean_bouts_min: Mapping, transitions: Mapping, epoch_min: float, epsilon: float
               ) -> tuple[np.ndarray, np.ndarray]:
    """(self-transition probabilities, normalized off-diagonal shares) exactly as core-algo TransitionModel."""
    self_p = np.array([np.clip(1.0 - epoch_min / float(mean_bouts_min[mean_bout_key(s)]), *_SELF_CLAMP)
                       for s in range(N_STAGES)])
    shares = np.zeros((N_STAGES, N_STAGES))
    for i in range(N_STAGES):
        w = np.array([0.0 if j == i else (float(transitions[transition_key(i, j)])
                                          if float(transitions[transition_key(i, j)]) > 0 else epsilon)
                      for j in range(N_STAGES)])
        shares[i] = w / w.sum()
    return self_p, shares


def hmm_transition_probs(i: np.ndarray, j: np.ndarray, t: np.ndarray, lock: np.ndarray, prior: Mapping,
                         self_p: np.ndarray, shares: np.ndarray, epsilon: float) -> np.ndarray:
    """P(state j at epoch k | state i at k−1) of the core-algo HMM for vectors of transitions (``t`` = (k + ½)/N,
    ``lock`` = k inside the REM lockout): self-transition, LIGHT row with the time multipliers (non-positive
    weights -> ε, renormalized), REM lockout (REM target × factor, the other targets scaled to keep the row sum)."""
    i = np.asarray(i, dtype=np.int64)
    j = np.asarray(j, dtype=np.int64)
    t = np.asarray(t, dtype=float)
    n = i.size
    row = ((1.0 - self_p)[:, None] * shares + np.diag(self_p))[i]
    li = i == LIGHT
    if li.any():
        tl = t[li]
        mult = np.ones((tl.size, N_STAGES))
        mult[:, DEEP] = np.maximum(prior["deepTimeFloor"], prior["deepTimeIntercept"] - prior["deepTimeSlope"] * tl)
        mult[:, REM] = prior["remTimeIntercept"] + prior["remTimeSlope"] * tl
        w = shares[LIGHT][None, :] * mult
        w = np.where(w > 0, w, epsilon)
        w[:, LIGHT] = 0.0
        adj = w / w.sum(axis=1, keepdims=True)
        row[li] = (1.0 - self_p[LIGHT]) * adj
        row[li, LIGHT] = self_p[LIGHT]
    lk = np.asarray(lock, dtype=bool)
    if lk.any():
        before = row[lk, REM]
        after = before * float(np.clip(prior["remLockoutFactor"], 0.0, 1.0))
        others = 1.0 - before
        scale = np.where(others > 0, (1.0 - after) / np.where(others > 0, others, 1.0), 1.0)
        sub = row[lk] * scale[:, None]
        sub[:, REM] = np.where(others > 0, after, before)
        row[lk] = sub
    return row[np.arange(n), j]


def hmm_transition_events(sequences, epoch_min: float, lockout_min: float) -> dict[str, np.ndarray]:
    """Consecutive labelled epoch pairs (k−1 -> k, k >= 1) of the session sequences: from, to, t, in lockout."""
    fr, to, tt, lk = [], [], [], []
    for seq in _as_list(sequences):
        a = np.asarray(seq, dtype=np.int64)
        n = a.size
        if n < 2:
            continue
        k = np.arange(1, n)
        ok = (a[:-1] >= 0) & (a[1:] >= 0)
        fr.append(a[:-1][ok])
        to.append(a[1:][ok])
        tt.append(np.clip((k[ok] + 0.5) / n, 0.0, 1.0))
        lk.append((k[ok] * epoch_min < lockout_min) & (lockout_min > 0))
    if not fr:
        e = np.zeros(0)
        return {"from": e.astype(np.int64), "to": e.astype(np.int64), "t": e, "lock": e.astype(bool)}
    return {"from": np.concatenate(fr), "to": np.concatenate(to), "t": np.concatenate(tt),
            "lock": np.concatenate(lk)}


def hmm_time_prior(sequences, epoch_min: float, mean_bouts_min: Mapping, transitions: Mapping,
                   epsilon: float = 1e-4, lockout_min: float = SPEC_TIME_PRIOR["remLockoutMin"],
                   initial_pseudocount: float = 0.5) -> tuple[dict, list[str]]:
    """Supervised estimate of the HMM's time-dependent priors (core-algo ``HmmTimePrior``) from labelled sequences.

    Conditional maximum likelihood of the epoch-to-epoch transitions under the core-algo transition model, with the
    self-transitions (``mean_bouts_min``) and the off-diagonal shares (``transitions``) fixed at their own MLE:
    LIGHT→DEEP multiplier max(floor, a − b·t), LIGHT→REM multiplier c + d·t (b, d >= 0, the shape the specification
    and AlgoParamsValidator allow) and the REM lockout factor (lockout length kept at ``lockout_min``). Only the
    transitions whose probability depends on these parameters enter the likelihood (LIGHT exits and transitions
    inside the lockout). L-BFGS-B from the specification's values and from "no time effect"; the better optimum wins.
    The initial AWAKE / LIGHT weights are the frequencies of AWAKE / LIGHT as first labelled stage (+ pseudo-count;
    DEEP and REM starts keep ε as in the specification).
    """
    from scipy.optimize import minimize

    notes: list[str] = []
    out = dict(SPEC_TIME_PRIOR)
    out["remLockoutMin"] = float(lockout_min)
    seqs = _as_list(sequences)
    first = [int(np.asarray(s)[np.asarray(s) >= 0][0]) for s in seqs if (np.asarray(s) >= 0).any()]
    n_a, n_l = sum(1 for f in first if f == AWAKE), sum(1 for f in first if f == LIGHT)
    if n_a + n_l > 0:
        out["initialAwake"] = (n_a + initial_pseudocount) / (n_a + n_l + 2 * initial_pseudocount)
        out["initialLight"] = 1.0 - out["initialAwake"]
    else:
        notes.append("time prior: no night starts in AWAKE or LIGHT, initial distribution kept")
    ev = hmm_transition_events(seqs, epoch_min, lockout_min)
    rel = (ev["from"] == LIGHT) & (ev["to"] != LIGHT) | ev["lock"]
    if not rel.any() or not ((ev["from"] == LIGHT) & (ev["to"] != LIGHT)).any():
        notes.append("time prior: no LIGHT exits, specification values kept")
        return out, notes
    i, j, t, lk = ev["from"][rel], ev["to"][rel], ev["t"][rel], ev["lock"][rel]
    self_p, shares = _base_rows(mean_bouts_min, transitions, epoch_min, epsilon)

    def nll(theta: np.ndarray) -> float:
        p = hmm_transition_probs(i, j, t, lk, dict(zip(_TP_NAMES, theta)), self_p, shares, epsilon)
        return float(-np.sum(np.log(np.maximum(p, 1e-300))))

    starts = [np.array([SPEC_TIME_PRIOR[k] for k in _TP_NAMES]), np.array([1.0, 0.0, 0.0, 1.0, 0.0, 1.0])]
    best = None
    for x0 in starts:
        r = minimize(nll, x0, method="L-BFGS-B", bounds=_TP_BOUNDS, options={"maxiter": 500})
        if best is None or r.fun < best.fun:
            best = r
    if best is None or not np.all(np.isfinite(best.x)):
        notes.append("time prior: optimizer failed, specification values kept")
        return out, notes
    for k, v in zip(_TP_NAMES, best.x):
        out[k] = float(v)
    n_exit = int(((i == LIGHT) & (j != LIGHT)).sum())
    notes.append(f"time prior: conditional MLE on {n_exit} LIGHT exits and {int(lk.sum())} lockout transitions "
                 f"(negative log-likelihood {best.fun:.1f})")
    return out, notes


# ------------------------------------------------------------------------------------------------- aggregate ----

@dataclass(frozen=True)
class LabelStatsParams:
    max_bout_min: float = 120.0
    bin_minutes: float = 30.0
    time_prior_alpha: float = 1.0
    time_prior_min_count: float = 300.0
    transition_pseudocount: float = 0.5
    initial_pseudocount: float = 0.5
    min_uncensored_bouts: int = 5
    sd_floor: float = 0.05
    min_emission_n: int = 10


@dataclass
class LabelStats:
    durations: dict[str, GammaFit]
    bout_counts: np.ndarray
    bout_transitions: np.ndarray
    initial: np.ndarray
    time_prior: np.ndarray
    time_prior_counts: np.ndarray
    mean_bouts: dict
    transition_weights: dict
    n_nights: int
    epoch_min: float
    bin_minutes: float
    notes: list[str] = field(default_factory=list)

    def durations_json(self) -> dict:
        return {s: self.durations[s].to_json() for s in STAGES}


def fit_label_stats(sequences, epoch_min: float, params: LabelStatsParams = LabelStatsParams()) -> LabelStats:
    seqs = _as_list(sequences)
    notes: list[str] = []
    samples, used = _duration_samples(seqs, epoch_min, params.min_uncensored_bouts)
    durations = {}
    for s in range(N_STAGES):
        g = fit_gamma(samples[s], DEFAULT_MEAN_BOUTS[mean_bout_key(s)])
        durations[STAGES[s]] = g
        if g.method != "mle" or used[s] != "uncensored":
            notes.append(f"duration {STAGES[s]}: {g.method} on {g.n} {used[s]} bouts")
    counts = bout_transition_counts(seqs, epoch_min, params.max_bout_min)
    tp, tpc = time_prior_table(seqs, epoch_min, params.bin_minutes, params.time_prior_alpha,
                               params.time_prior_min_count)
    mb, n1 = mean_bouts(seqs, epoch_min, params.min_uncensored_bouts)
    tw, n2 = transition_weights(counts)
    return LabelStats(
        durations=durations, bout_counts=counts,
        bout_transitions=transition_matrix(counts, params.transition_pseudocount),
        initial=initial_distribution(seqs, params.initial_pseudocount), time_prior=tp, time_prior_counts=tpc,
        mean_bouts=mb, transition_weights=tw, n_nights=len(seqs), epoch_min=epoch_min,
        bin_minutes=params.bin_minutes, notes=notes + n1 + n2,
    )
