"""Pre-registered nap evaluation (``docs/prereg/prereg-naps.md``, :data:`PREREG_SHA256`): is the shipped HYBRID
default good enough for daytime naps (sessions of 30 min and more), or should short sessions be decoded differently?

No open dataset has labelled daytime naps, so the labelled nights are cut into NAP PROXIES (:func:`nap_proxy`): the
OHealth-like session start of each night (first non-wake epoch - 5 min) and an end L minutes later, L in
:data:`LENGTHS`; heart rate / steps of the 30-min context before the session as the night file has them and nothing
after the session end (a real nap is followed by wakefulness, which the nights cannot supply; dropping the
post-session context is the conservative choice); labels inside the proxy.

Everything is decoded post hoc with the fold artefacts of a finished stage-proportion experiment (run ``bias-base``
and its ``bias/decisions.json``): every held-out subject with its LOSO outer fold bundle and the decoder choice
theta_g of its (variant, outer group g); no classifier is retrained. Decoding kinds (one algo-cli call per variant,
group and kind):

- ``A``: fold bundle + b_g (the fold-level stageLogBias of the adopted candidate (b), i.e. the shipped default);
- ``BASE``: fold bundle without b;
- ``C-<T>``: fold bundle with b_short_g(T), fitted by :func:`bias.fit_stage_bias` (unchanged) on the proxies
  (L < T) of g's inner held-out Walch selection subjects, decoded by their inner fold models at theta_g;
- ``RULES`` / ``HMM-table``: the untrained context estimators.

Candidates (:data:`CANDIDATES`) are assembled from the kinds per session: ``A`` everywhere; ``B-<T>`` = BASE for
sessions shorter than T, A otherwise; ``C-<T>`` = C-<T> for sessions shorter than T, A otherwise (so whole nights,
all >= 199 min, are A in every candidate). Metrics per session: accuracy, summed share error E = 100 * sum_s |P_s -
T_s| (percentage points), kappa (reported, not used). Adoption (:func:`adopt`): Walch, worse variant, pooled over
:data:`RULE_LENGTHS` (mean over the lengths of the per-length values): lowest E among the candidates with accuracy
>= accuracy(A) - 0.01; ties (|dE| < 0.05 pp) -> higher accuracy, then :data:`CANDIDATES` order; A competes.

Before any proxy is decoded, :func:`run_experiment` re-evaluates the whole nights (A, BASE, RULES, HMM-table) and
fails unless A / BASE reproduce ``bias-base/bias/results.jsonl`` night by night (proof that the fold artefacts are
the ones behind the published numbers); the context estimators are compared with ``loso-v2`` and reported.
"""

from __future__ import annotations

import itertools
import json
import logging
import math
import shutil
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Mapping, Sequence

import numpy as np

from . import report
from .algocli import AlgoCli
from .bias import fit_stage_bias
from .bundle import validate_bundle, with_stage_log_bias, with_stage_log_bias_short, write_bundle
from .loso import CONFIGS, PipelineConfig, map_threads, physiology_hmm_patch
from .nightset import NightIndex, load_index
from .util import N_STAGES, STAGES, canonical_json, code_fingerprint, deep_merge, read_json, write_json

log = logging.getLogger(__name__)

LENGTHS: tuple[int, ...] = (30, 60, 90, 120, 180)          # proxy lengths, minutes
RULE_LENGTHS: tuple[int, ...] = (30, 60, 90, 120)          # pooled by the adoption rule
THRESHOLDS: tuple[int, ...] = (120, 180)                   # T: sessions strictly shorter are "short"
CANDIDATES: tuple[str, ...] = ("A", "B-120", "B-180", "C-120", "C-180")     # tie order: A first
CANDIDATE_LABEL = {"A": "shipped default (b on)", "B-120": "b off below 120 min", "B-180": "b off below 180 min",
                   "C-120": "b_short below 120 min", "C-180": "b_short below 180 min"}
CONTEXT_ESTIMATORS: tuple[str, ...] = ("RULES", "HMM-table")
HYBRID_KINDS: tuple[str, ...] = ("A", "BASE", *(f"C-{t}" for t in THRESHOLDS))
ACC_MARGIN = 0.01
TIE_PP = 0.05
CONTEXT_MS = 30 * 60_000                                   # prepare.py / ohealth.CONTEXT_MS
SANITY_CODES = ("SANITY_DEEP_HIGH", "SANITY_REM_HIGH", "SANITY_DEEP_LOW")
FLAG_CODES = (*SANITY_CODES, "LOW_HR_RANGE", "RULES_FALLBACK_LOW_COVERAGE", "CYCLE_PRIOR_DISABLED", "DATA_GAPS")
LOW_CONFIDENCE_CODES = ("LOW_HR_RANGE", *SANITY_CODES)    # NightFlags.lowConfidence
DATASETS = (report.HEADLINE_DATASET, "bidsleep")
NIGHT_KEYS = ("id", "subject", "variant", "sessionStartMs", "sessionEndMs", "hr", "steps", "labels")
PREREG_PATH = "docs/prereg/prereg-naps.md"
PREREG_SHA256 = "10ebc9f4bd3edcb5e5f8de62bdf6f2899a3b8e2a84ea2fb0b3e20bb70bfa3815"


class NapError(RuntimeError):
    """The nap evaluation cannot run (inputs missing) or failed a consistency check."""


# ------------------------------------------------------------------------------------------- pure helpers ----

def nap_proxy(night: Mapping, length_min: float) -> dict | None:
    """The first ``length_min`` minutes of ``night``'s session as a nap session, or None when the session is
    shorter. Heart rate of [start - 30 min, end], step records ending in (start - 30 min, end], label epochs fully
    inside [start, end]; nothing after the end (no post-session context)."""
    extra = sorted(set(night) - set(NIGHT_KEYS))
    if extra:
        raise NapError(f"{night.get('id')}: unexpected night fields {extra} (only {list(NIGHT_KEYS)} are cut)")
    s0 = int(night["sessionStartMs"])
    s1 = s0 + int(round(length_min * 60_000))
    if s1 > int(night["sessionEndMs"]):
        return None
    c0 = s0 - CONTEXT_MS
    return {
        "id": f"{night['id']}-nap{length_min:g}", "subject": night["subject"], "variant": night.get("variant"),
        "sessionStartMs": s0, "sessionEndMs": s1,
        "hr": [h for h in night["hr"] if c0 <= h[0] <= s1],
        "steps": [s for s in night.get("steps", []) if c0 < s[1] <= s1],
        "labels": [lab for lab in night.get("labels", []) if lab[0] >= s0 and lab[1] <= s1],
    }


def session_shares(confusion) -> tuple[np.ndarray, np.ndarray] | None:
    """(true, predicted) shares over the scored chunks of one session; None without a scored chunk."""
    c = np.asarray(confusion, dtype=float)
    tot = c.sum()
    if tot <= 0:
        return None
    return c.sum(axis=1) / tot, c.sum(axis=0) / tot


def share_error(confusion) -> float | None:
    """E of one session: 100 * sum over AWAKE, LIGHT, DEEP, REM of |predicted - true share| (percentage points)."""
    s = session_shares(confusion)
    return None if s is None else float(100.0 * np.abs(s[1] - s[0]).sum())


def session_accuracy(confusion) -> float | None:
    c = np.asarray(confusion, dtype=float)
    tot = c.sum()
    return None if tot <= 0 else float(np.trace(c) / tot)


def short_session(length_min: float, threshold_min: float) -> bool:
    """Sessions strictly shorter than the threshold are short (the Kotlin ``hybrid.shortSessionMaxMin`` rule)."""
    return length_min < threshold_min


def candidate_kind(candidate: str, length_min: float) -> str:
    """Decoding kind whose result the ``candidate`` uses for a session of ``length_min`` minutes."""
    if candidate == "A":
        return "A"
    family, t = candidate.split("-")
    if not short_session(length_min, float(t)):
        return "A"
    return "BASE" if family == "B" else f"C-{t}"


def sanity_check_min_session_min(metrics: Mapping) -> float:
    """``post.proportionsMinSessionMin`` of the effective parameters that an ``algo-cli evaluate`` metrics block
    reports (``params``): the build runs the stage-share sanity check only on sessions at least this many minutes
    long. 0 when the field is absent: builds before that rule, the nap experiment's among them, checked every session."""
    post = (metrics.get("params") or {}).get("post") or {}
    return float(post.get("proportionsMinSessionMin") or 0.0)


def expected_warnings(stored: Sequence[str], session_ms: int, check_min_session_min: float) -> list[str]:
    """The warnings that a build skipping the sanity check on sessions shorter than ``check_min_session_min`` minutes
    (:func:`sanity_check_min_session_min`) gives a session of ``session_ms`` whose decode by the nap experiment's
    build had ``stored``: ``stored`` without :data:`SANITY_CODES` when the session is shorter (the Kotlin rule
    ``durationMs >= ceil(minutes * 60000)``), ``stored`` otherwise. The rule changes no stage: the proportion
    calibration it skips as well is off by default."""
    limit_ms = math.ceil(check_min_session_min * 60_000) if check_min_session_min > 0 else 0
    if session_ms < limit_ms:
        return [w for w in stored if w not in SANITY_CODES]
    return list(stored)


def adopt(scores: Mapping[str, Mapping], order: Sequence[str] = CANDIDATES, margin: float = ACC_MARGIN,
          tie: float = TIE_PP) -> dict:
    """The pre-registered rule on ``scores`` (candidate -> ``{"accWorse", "EWorse"}``): qualifying = accuracy worse
    >= that of ``order[0]`` (A) - ``margin``; lowest E worse; ties (E within ``tie`` pp of the lowest) -> higher
    accuracy, then ``order``."""
    ref = scores[order[0]]["accWorse"]
    ok = [c for c in order if c in scores and scores[c]["accWorse"] >= ref - margin]
    low = min(scores[c]["EWorse"] for c in ok)
    tied = [c for c in ok if scores[c]["EWorse"] - low < tie]
    best = max(tied, key=lambda c: (scores[c]["accWorse"], -list(order).index(c)))
    return {"adopted": best, "qualifying": ok, "threshold": ref - margin, "referenceAccuracy": ref, "tied": tied,
            "rule": "Walch, worse variant, pooled over L in " + "/".join(map(str, RULE_LENGTHS))
                    + ": lowest mean summed share error among candidates with accuracy >= accuracy(A) - 0.01; ties "
                      "(|dE| < 0.05 pp) -> higher accuracy; else A"}


# ---------------------------------------------------------------------------------------------- aggregation ----

def _scored(recs: Iterable[Mapping]) -> list[Mapping]:
    return [r for r in recs if r["estimated"] and r["confusion"] is not None
            and float(np.asarray(r["confusion"], float).sum()) > 0]


def _pooled(recs: Iterable[Mapping]) -> np.ndarray:
    return sum((np.asarray(r["confusion"], float) for r in _scored(recs)), np.zeros((N_STAGES, N_STAGES)))


def _subject_pooled(recs: Iterable[Mapping]) -> dict[str, np.ndarray]:
    out: dict[str, np.ndarray] = {}
    for r in _scored(recs):
        out[r["subject"]] = out.get(r["subject"], np.zeros((N_STAGES, N_STAGES))) + np.asarray(r["confusion"], float)
    return dict(sorted(out.items()))


def length_cell(recs: Sequence[Mapping]) -> dict:
    """Metrics of the sessions of one (kind or candidate, dataset, variant, L)."""
    rs = _scored(recs)
    est = [r for r in recs if r["estimated"]]
    skipped = Counter(str(r.get("reason") or "unknown") for r in recs if not r["estimated"])
    pooled = _pooled(rs)
    tot = pooled.sum()
    e = [share_error(r["confusion"]) for r in rs]
    acc = [session_accuracy(r["confusion"]) for r in rs]
    sh = [session_shares(r["confusion"]) for r in rs]
    stage_err = {s: float(np.mean([100 * abs(p[i] - t[i]) for t, p in sh])) if sh else float("nan")
                 for i, s in enumerate(STAGES)}
    subj = _subject_pooled(rs)
    sk = {s: report.kappa(c) for s, c in subj.items()}
    ks = [k for k in sk.values() if k is not None and math.isfinite(k)]
    subj_e: dict[str, list[float]] = defaultdict(list)
    for r, x in zip(rs, e):
        subj_e[r["subject"]].append(x)
    flags = {c: sum(1 for r in est if c in r.get("warnings", ())) for c in FLAG_CODES}
    n_est = len(est)
    rate = (lambda k: k / n_est) if n_est else (lambda k: float("nan"))
    return {
        "sessions": len(recs), "estimated": n_est, "scored": len(rs), "noScoredChunks": n_est - len(rs),
        "notEstimated": sum(skipped.values()), "notEstimatedReasons": dict(sorted(skipped.items())),
        "notEstimatedRate": sum(skipped.values()) / len(recs) if recs else float("nan"),
        "subjects": len(subj), "kappaDefined": len(ks), "kappa": float(np.mean(ks)) if ks else float("nan"),
        "kappaSd": float(np.std(ks, ddof=1)) if len(ks) > 1 else float("nan"),
        "kappaPooled": report.kappa(pooled) if tot > 0 else float("nan"),
        "accuracy": float(np.trace(pooled) / tot) if tot > 0 else float("nan"),
        "accuracySessionMean": float(np.mean(acc)) if acc else float("nan"),
        "E": float(np.mean(e)) if e else float("nan"), "ESd": float(np.std(e, ddof=1)) if len(e) > 1 else float("nan"),
        "stageShareError": stage_err,
        "trueShares": dict(zip(STAGES, (pooled.sum(axis=1) / tot).tolist())) if tot > 0 else None,
        "predShares": dict(zip(STAGES, (pooled.sum(axis=0) / tot).tolist())) if tot > 0 else None,
        "trueSharesSessionMean": dict(zip(STAGES, np.mean([t for t, _ in sh], axis=0).tolist())) if sh else None,
        "predSharesSessionMean": dict(zip(STAGES, np.mean([p for _, p in sh], axis=0).tolist())) if sh else None,
        "singleTrueStageSessions": sum(1 for t, _ in sh if int((t > 0).sum()) == 1),
        "flags": flags, "flagRates": {c: rate(k) for c, k in flags.items()},
        "sanityAny": sum(1 for r in est if set(r.get("warnings", ())) & set(SANITY_CODES)),
        "sanityAnyRate": rate(sum(1 for r in est if set(r.get("warnings", ())) & set(SANITY_CODES))),
        "lowConfidence": sum(1 for r in est if set(r.get("warnings", ())) & set(LOW_CONFIDENCE_CODES)),
        "lowConfidenceRate": rate(sum(1 for r in est if set(r.get("warnings", ())) & set(LOW_CONFIDENCE_CODES))),
        "confusion": pooled.tolist(),
        "subjectKappa": sk,
        "subjectAccuracy": {s: float(np.trace(c) / c.sum()) for s, c in subj.items()},
        "subjectE": {s: float(np.mean(v)) for s, v in sorted(subj_e.items())},
        "subjectSessions": {s: len(v) for s, v in sorted(subj_e.items())},
    }


def pooled_cell(cells: Mapping[int, Mapping], lengths: Sequence[int] = RULE_LENGTHS) -> dict:
    """Pooled over ``lengths``: mean over the lengths of the per-length E / accuracy / kappa (each length counts
    equally); per-subject values for the paired bootstrap: E = mean over the subject's sessions (the per-length
    subject means weighted by the subject's scored sessions, ``subjectSessions``), accuracy = mean over the lengths
    of the subject's pooled accuracy."""
    cs = [cells[L] for L in lengths if L in cells]
    if len(cs) != len(lengths):
        raise NapError(f"pooled cell needs all lengths {list(lengths)}, has {sorted(cells)}")
    subj_a: dict[str, list[float]] = defaultdict(list)
    e_sum: dict[str, float] = defaultdict(float)
    e_n: dict[str, int] = defaultdict(int)
    for c in cs:
        for s, v in c["subjectAccuracy"].items():
            subj_a[s].append(v)
        for s, n in c["subjectSessions"].items():
            e_sum[s] += c["subjectE"][s] * n
            e_n[s] += n
    kappas = [c["kappa"] for c in cs if c["kappa"] is not None and math.isfinite(c["kappa"])]
    return {
        "lengths": list(lengths), "sessions": sum(c["sessions"] for c in cs), "scored": sum(c["scored"] for c in cs),
        "notEstimated": sum(c["notEstimated"] for c in cs),
        "E": float(np.mean([c["E"] for c in cs])), "accuracy": float(np.mean([c["accuracy"] for c in cs])),
        "accuracySessionMean": float(np.mean([c["accuracySessionMean"] for c in cs])),
        "kappa": float(np.mean(kappas)) if kappas else float("nan"),
        "lowConfidenceRate": float(np.mean([c["lowConfidenceRate"] for c in cs])),
        "sanityAnyRate": float(np.mean([c["sanityAnyRate"] for c in cs])),
        "subjectAccuracy": {s: float(np.mean(v)) for s, v in sorted(subj_a.items())},
        "subjectE": {s: e_sum[s] / e_n[s] for s in sorted(e_sum)},
    }


def assemble(kind_recs: Sequence[Mapping], candidates: Sequence[str] = CANDIDATES) -> list[dict]:
    """Per-session records of every candidate from the per-kind records (:func:`candidate_kind`)."""
    by_kind: dict[tuple[str, str, str], Mapping] = {}
    for r in kind_recs:
        by_kind[(r["kind"], r["variant"], r["night"])] = r
    out = []
    a = [r for r in kind_recs if r["kind"] == "A"]
    for c in candidates:
        for r in a:
            src = by_kind.get((candidate_kind(c, r["L"]), r["variant"], r["night"]))
            if src is None:
                raise NapError(f"{c}: no {candidate_kind(c, r['L'])} decode of {r['variant']} {r['night']}")
            out.append({**src, "candidate": c})
    return out


def cells_of(recs: Sequence[Mapping], key: str, names: Sequence[str], variants: Sequence[str],
             lengths: Sequence[int] = LENGTHS) -> dict:
    """cells[name][dataset][variant][L | "pooled"] for the records grouped by ``key`` (candidate or kind); "pooled"
    only when every length of :data:`RULE_LENGTHS` is present."""
    groups: dict[tuple, list] = defaultdict(list)
    for r in recs:
        groups[(r[key], r["dataset"], r["variant"], r["L"])].append(r)
    cells: dict = {}
    for name in names:
        for ds in DATASETS:
            for v in variants:
                per = {}
                for L in lengths:
                    rs = groups.get((name, ds, v, L))
                    if rs:
                        per[L] = length_cell(rs)
                if not per:
                    continue
                if all(L in per for L in RULE_LENGTHS):
                    per["pooled"] = pooled_cell(per)
                cells.setdefault(name, {}).setdefault(ds, {})[v] = per
    return cells


def rule_scores(cells: Mapping, variants: Sequence[str], dataset: str = report.HEADLINE_DATASET,
                names: Sequence[str] = CANDIDATES) -> dict[str, dict]:
    out = {}
    for c in names:
        cv = cells.get(c, {}).get(dataset)
        if not cv or not all(v in cv and "pooled" in cv[v] for v in variants):
            continue
        acc = {v: cv[v]["pooled"]["accuracy"] for v in variants}
        e = {v: cv[v]["pooled"]["E"] for v in variants}
        per_l = {str(L): {"accWorse": min(cv[v][L]["accuracy"] for v in variants),
                          "EWorse": max(cv[v][L]["E"] for v in variants)} for L in LENGTHS
                 if all(L in cv[v] for v in variants)}
        out[c] = {"accWorse": min(acc.values()), "accWorseVariant": min(acc, key=acc.get),
                  "EWorse": max(e.values()), "EWorseVariant": max(e, key=e.get), "accuracy": acc, "E": e,
                  "perLength": per_l}
    return out


def comparisons(cells: Mapping, variants: Sequence[str], ref: str = "A",
                names: Sequence[str] = CANDIDATES[1:], lengths: Sequence[int] = LENGTHS) -> list[dict]:
    """Paired per-subject differences candidate - ``ref`` (95 % bootstrap CI, 10 000 resamples, seed 0) of E,
    accuracy and (per length) kappa, per dataset, variant and length (and pooled)."""
    out = []
    for ds in DATASETS:
        for c in names:
            for v in variants:
                a, b = cells.get(c, {}).get(ds, {}).get(v), cells.get(ref, {}).get(ds, {}).get(v)
                if not a or not b:
                    continue
                for L in [*lengths, "pooled"]:
                    if L not in a or L not in b:
                        continue
                    x = {"candidate": c, "reference": ref, "dataset": ds, "variant": v, "L": L,
                         "E": report.paired_bootstrap(a[L]["subjectE"], b[L]["subjectE"]),
                         "accuracy": report.paired_bootstrap(a[L]["subjectAccuracy"], b[L]["subjectAccuracy"])}
                    if L != "pooled":
                        x["kappa"] = report.paired_bootstrap(a[L]["subjectKappa"], b[L]["subjectKappa"])
                    out.append(x)
    return out


# ------------------------------------------------------------------------------------------------ run data ----

@dataclass
class Group:
    """One (variant, outer group) of the stage-proportion experiment: decoder choice, fold-level b, subjects."""
    variant: str
    key: str
    theta: dict
    bias: list[float]
    heldout: list[str]
    eval_subjects: list[str]
    eval_nights: list[str]
    inner_models: Path


class Inputs:
    """Read-only inputs: the run (``bias-base``), its ``bias`` experiment and the prepared night files."""

    def __init__(self, run_dir: Path, nights_root: Path, variants: Sequence[str] = report.HEADLINE_VARIANTS,
                 bias_name: str = "bias", context_run: Path | None = None):
        self.run_dir = Path(run_dir)
        self.cfg = PipelineConfig.from_json(read_json(self.run_dir / "config.json"))
        sel = read_json(self.run_dir / "summary.json")["selection"]["classifier"]
        self.clf, self.config = sel["chosen"], sel["config"]
        self.label = report.CLASSIFIER_LABEL[self.clf]
        self.variants = tuple(variants)
        self.bias_dir = self.run_dir / bias_name
        self.nights_root = Path(nights_root)
        self.index: NightIndex = load_index(self.nights_root, list(self.variants), self.nights_root / "summary.csv")
        self.context_run = Path(context_run) if context_run is not None else None
        self.groups = self._groups()
        self._nights: dict[tuple[str, str], dict] = {}
        self.run_build = read_json(self.run_dir / "config.json").get("algoCli", {}).get("sha256")

    def hybrid_dir(self, variant: str) -> Path:
        return self.run_dir / "hybrid" / self.config / variant / self.clf

    def folds(self, variant: str) -> Path:
        return self.hybrid_dir(variant) / "folds"

    def _groups(self) -> list[Group]:
        dec = read_json(self.bias_dir / "decisions.json")["groups"]
        out = []
        for d in dec:
            v, key = d["variant"], d["key"]
            if v not in self.variants:
                continue
            metas = [read_json(p) for p in sorted((self.hybrid_dir(v) / "folds-meta").glob("*.meta.json"))]
            heldout = sorted(s for m in metas if m.get("selectionKey") == key for s in m["test"])
            if heldout != sorted(d["heldout"]):
                raise NapError(f"{v}/{key}: decisions.json held-out subjects differ from the fold metas")
            if any(canonical_json(m["params"]) != canonical_json(d["theta"]) for m in metas
                   if m.get("selectionKey") == key):
                raise NapError(f"{v}/{key}: fold metas disagree with the decoder choice of decisions.json")
            s = read_json(self.hybrid_dir(v) / "selection" / f"{key}.json")
            models = self.run_dir / "work" / self.config / v / self.clf / key / "models"
            if not models.is_dir():
                raise NapError(f"{models}: inner fold models missing (the run must have been made with --keep-inner)")
            b = d["choices"]["B"]["bias"]
            if b is None or len(b) != N_STAGES:
                raise NapError(f"{v}/{key}: no fold-level b (candidate B) in decisions.json")
            out.append(Group(variant=v, key=key, theta=dict(d["theta"]), bias=[float(x) for x in b], heldout=heldout,
                             eval_subjects=list(s["evalSubjects"]), eval_nights=list(s["evalNights"]),
                             inner_models=models))
        if not out:
            raise NapError(f"no groups in {self.bias_dir / 'decisions.json'}")
        return out

    def night(self, variant: str, night_id: str) -> dict:
        k = (variant, night_id)
        if k not in self._nights:
            self._nights[k] = read_json(self.index.get(variant, night_id).path)
        return self._nights[k]

    def params(self, theta: Mapping, extra: Mapping | None = None) -> dict:
        return deep_merge(deep_merge(dict(self.cfg.base_params), {"estimator": "HYBRID",
                                                                   "hybrid": {**theta, "priorExponent": 1.0}}),
                          dict(extra or {}))


# --------------------------------------------------------------------------------------------- decoding ----

class Decoder:
    """algo-cli access with JSONL subsets, biased fold-bundle copies and per-session tagging."""

    def __init__(self, inputs: Inputs, cli: AlgoCli, out: Path):
        self.inp = inputs
        self.cli = cli
        self.out = Path(out)
        self._bundles: dict[Path, dict] = {}

    def _bundle(self, path: Path) -> dict:
        b = self._bundles.get(path)
        if b is None:
            b = read_json(path)
            validate_bundle(b)
            self._bundles[path] = b
        return b

    def biased_models(self, src: Path, subjects: Sequence[str], bias: Sequence[float] | None, dst: Path) -> Path:
        """``dst`` with ``<subject>.json`` = the bundles of ``src`` with stageLogBias ``bias`` (None: as they are)."""
        dst.mkdir(parents=True, exist_ok=True)
        for s in subjects:
            b = self._bundle(src / f"{s}.json")
            b = with_stage_log_bias(b, bias) if bias is not None else b
            (dst / f"{s}.json").write_text(json.dumps(b, separators=(",", ":"), allow_nan=False), encoding="utf-8")
        return dst

    def write_nights(self, path: Path, nights: Sequence[Mapping]) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            for n in nights:
                f.write(json.dumps(n, separators=(",", ":")) + "\n")
        return path

    def evaluate(self, nights: Path, meta: Mapping[str, Mapping], estimator: str, params: Mapping, out: Path,
                 fold_models: Path | None = None, extra: Mapping | None = None) -> list[dict]:
        pf = write_json(out.with_suffix(".params.json"), dict(params))
        m = self.cli.evaluate(nights, "HMM" if estimator == "HMM-table" else estimator, out, params=pf,
                              fold_models=fold_models)
        recs = report.parse_metrics(m)
        report.check_estimator_used(recs, "HYBRID" if estimator == "HYBRID" else estimator)
        got = sorted(r["id"] for r in recs)
        if got != sorted(meta):
            raise NapError(f"{out}: algo-cli returned {len(got)} sessions for {len(meta)} expected")
        bad = [r["id"] for r in recs if r["reason"] in ("NO_FOLD_MODEL", "NO_LABELS")]
        if bad:
            raise NapError(f"{out}: {len(bad)} sessions without fold model / labels, e.g. {bad[:3]}")
        out_recs = []
        for r in recs:
            q = meta[r["id"]]
            out_recs.append({"night": r["id"], **q, "estimated": r["estimated"], "reason": r["reason"],
                             "estimatorUsed": r["estimatorUsed"], "warnings": list(r["warnings"]),
                             "confusion": None if r["confusion"] is None else np.asarray(r["confusion"]).tolist(),
                             **(extra or {})})
        return out_recs


def proxy_set(inp: Inputs, variant: str, night_ids: Iterable[str], lengths: Sequence[int]) -> tuple[list[dict],
                                                                                                    dict, list[dict]]:
    """(proxy nights, meta per proxy id, proxies that could not be cut) of ``night_ids`` for ``lengths``."""
    nights, meta, missing = [], {}, []
    for nid in night_ids:
        ref = inp.index.get(variant, nid)
        src = inp.night(variant, nid)
        for L in lengths:
            p = nap_proxy(src, L)
            if p is None:
                missing.append({"variant": variant, "night": nid, "L": L})
                continue
            nights.append(p)
            meta[p["id"]] = {"base": nid, "subject": ref.subject, "dataset": ref.dataset, "variant": variant, "L": L}
    return nights, meta, missing


def night_meta(inp: Inputs, variant: str, night_ids: Iterable[str]) -> dict:
    out = {}
    for nid in night_ids:
        ref = inp.index.get(variant, nid)
        out[nid] = {"base": nid, "subject": ref.subject, "dataset": ref.dataset, "variant": variant, "L": None}
    return out


# ----------------------------------------------------------------------------------------- reproduction ----

def reproduce_nights(inp: Inputs, dec: Decoder, jobs: int) -> dict:
    """Whole nights with A / BASE (every group) and RULES / HMM-table: A and BASE must reproduce the experiment's
    candidates B / BASE night by night (else :class:`NapError`); the context estimators are compared with
    ``context_run`` (``loso-v2``) and reported."""
    out = dec.out / "repro"
    tasks = [(g, k) for g in inp.groups for k in ("A", "BASE")]

    def one(task):
        g, kind = task
        ids = [n.id for n in inp.index.nights_of(g.variant, g.heldout)]
        meta = night_meta(inp, g.variant, ids)
        nights = dec.write_nights(out / g.variant / f"{g.key}.nights.jsonl", [inp.night(g.variant, i) for i in ids])
        fm = inp.folds(g.variant) if kind == "BASE" else dec.biased_models(
            inp.folds(g.variant), g.heldout, g.bias, out / "models" / kind / g.variant / g.key)
        try:
            return dec.evaluate(nights, meta, "HYBRID", inp.params(g.theta), out / g.variant / f"{g.key}.{kind}.json",
                                fold_models=fm, extra={"kind": kind, "group": g.key})
        finally:
            if fm != inp.folds(g.variant):
                shutil.rmtree(fm, ignore_errors=True)

    def ctx(task):
        v, est = task
        ids = [n.id for n in inp.index.for_variant(v)]
        nights = dec.write_nights(out / v / "all.nights.jsonl", [inp.night(v, i) for i in ids])
        params = {"estimator": "RULES"} if est == "RULES" else deep_merge({"estimator": "HMM"}, physiology_hmm_patch())
        return dec.evaluate(nights, night_meta(inp, v, ids), est, deep_merge(dict(inp.cfg.base_params), params),
                            out / v / f"{est}.json", extra={"kind": est, "group": "-"})

    recs = [r for rs in map_threads(one, tasks, jobs) for r in rs]
    ctx_tasks = [(v, e) for v in inp.variants for e in CONTEXT_ESTIMATORS]
    ctx_recs = [r for rs in map_threads(ctx, ctx_tasks, max(1, min(jobs, len(ctx_tasks)))) for r in rs]
    for v in inp.variants:
        (out / v / "all.nights.jsonl").unlink(missing_ok=True)
        for g in inp.groups:
            (out / v / f"{g.key}.nights.jsonl").unlink(missing_ok=True)
    own = {}
    with open(inp.bias_dir / "results.jsonl", encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            if r["candidate"] in ("B", "BASE") and r["variant"] in inp.variants:
                own[("A" if r["candidate"] == "B" else "BASE", r["variant"], r["night"])] = r
    mismatches = []
    for r in recs:
        o = own.get((r["kind"], r["variant"], r["night"]))
        if (o is None or o["confusion"] != r["confusion"] or list(o["warnings"]) != r["warnings"]
                or o["estimated"] != r["estimated"]):
            mismatches.append((r["kind"], r["variant"], r["night"]))
    if len(own) != len(recs) or mismatches:
        raise NapError(f"night-level reproduction failed: {len(recs)} decoded vs {len(own)} stored, "
                       f"{len(mismatches)} mismatches, e.g. {mismatches[:3]}")
    ctx_check: dict[str, dict] = {}
    if inp.context_run is not None and (inp.context_run / "results.jsonl").is_file():
        ref = {}
        with open(inp.context_run / "results.jsonl", encoding="utf-8") as f:
            for line in f:
                r = json.loads(line)
                if r["estimator"] in CONTEXT_ESTIMATORS and r["variant"] in inp.variants:
                    ref[(r["estimator"], r["variant"], r["night"])] = r
        for est in CONTEXT_ESTIMATORS:
            for v in inp.variants:
                rs = [r for r in ctx_recs if r["kind"] == est and r["variant"] == v]
                same = sum(1 for r in rs if (o := ref.get((est, v, r["night"]))) is not None
                           and o["confusion"] == r["confusion"] and list(o["warnings"]) == r["warnings"])
                ctx_check[f"{est}/{v}"] = {"nights": len(rs), "identical": same}
    summary = {}
    for kind in ("A", "BASE", *CONTEXT_ESTIMATORS):
        for v in inp.variants:
            for ds in DATASETS:
                rs = [r for r in (recs if kind in ("A", "BASE") else ctx_recs)
                      if r["kind"] == kind and r["variant"] == v and r["dataset"] == ds]
                if rs:
                    agg = report.aggregate(rs)
                    summary.setdefault(kind, {}).setdefault(ds, {})[v] = {
                        "nights": agg["nightsEstimated"], "kappa": agg["kappaSubjectMean"],
                        "accuracy": agg["accuracy"], "kappaPooled": agg["kappaPooled"]}
    return {"hybridNights": len(recs), "identical": len(recs) - len(mismatches), "contextCheck": ctx_check,
            "summary": summary}


# ------------------------------------------------------------------------------------------ short-session b ----

def fit_short_bias(inp: Inputs, dec: Decoder, g: Group, threshold: int,
                   fitter: Callable = fit_stage_bias) -> dict:
    """b_short_g(T): the fixed point on the proxies (L < T) of g's inner held-out Walch selection nights, each
    decoded by its inner fold model at theta_g (beta 1)."""
    lengths = [L for L in LENGTHS if short_session(L, threshold)]
    nights, meta, missing = proxy_set(inp, g.variant, g.eval_nights, lengths)
    if missing:
        raise NapError(f"{g.variant}/{g.key}: inner proxies could not be cut: {missing[:3]}")
    if any(inp.index.get(g.variant, n).dataset != report.HEADLINE_DATASET for n in g.eval_nights):
        raise NapError(f"{g.variant}/{g.key}: inner evaluation nights must be Walch")
    d = dec.out / "fits" / g.variant / g.key / f"T{threshold}"
    nights_file = dec.write_nights(d / "nights.jsonl", nights)
    counter = itertools.count()
    decoded: dict[int, list[dict]] = {}

    def decode(b: np.ndarray) -> np.ndarray:
        n = next(counter)
        fm = g.inner_models if not np.any(b) else dec.biased_models(g.inner_models, g.eval_subjects, b.tolist(),
                                                                     d / f"models-{n}")
        try:
            recs = dec.evaluate(nights_file, meta, "HYBRID", inp.params(g.theta), d / f"fp-{n}.json",
                                fold_models=fm)
        finally:
            if fm != g.inner_models:
                shutil.rmtree(fm, ignore_errors=True)
        decoded[n] = recs
        return _pooled(recs)

    fit = fitter(decode)
    best = decoded[fit["bestIndex"]]
    rs = _scored(best)
    return {"variant": g.variant, "key": g.key, "T": threshold, "lengths": lengths, "theta": g.theta,
            "evalSubjects": len(g.eval_subjects), "sessions": len(meta), "bias": fit["bias"], "D": fit["D"],
            "decodes": fit["decodes"], "trueShares": fit["trueShares"], "predShares": fit["predShares"],
            "trace": fit["trace"], "innerE": float(np.mean([share_error(r["confusion"]) for r in rs])),
            "innerAccuracy": float(np.trace(_pooled(rs)) / _pooled(rs).sum())}


# ------------------------------------------------------------------------------------------ the experiment ----

def run_experiment(inp: Inputs, cli: AlgoCli, out: Path, jobs: int = 4) -> dict:
    """Reproduction gate, proxies, b_short fits, every decoding kind; writes ``results.jsonl``, ``fits.json``,
    ``summary.json`` and ``report.md`` into ``out``."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    fp = cli.fingerprint()
    dec = Decoder(inp, cli, out)
    log.info("nap evaluation on %s: %d groups, algo-cli %s (run built with %s)", inp.run_dir, len(inp.groups),
             fp.get("sha256"), inp.run_build)
    repro = reproduce_nights(inp, dec, jobs)
    write_json(out / "repro" / "summary.json", _clean(repro))
    log.info("night-level reproduction: %d / %d HYBRID nights identical; context %s", repro["identical"],
             repro["hybridNights"], repro["contextCheck"])

    fit_tasks = [(g, t) for g in inp.groups for t in THRESHOLDS]
    fits = map_threads(lambda task: fit_short_bias(inp, dec, task[0], task[1]), fit_tasks, jobs)
    fit_of = {(f["variant"], f["key"], f["T"]): f for f in fits}
    write_json(out / "fits.json", _clean({"fits": fits}))

    missing_all: list[dict] = []
    tasks = [(g, k) for g in inp.groups for k in HYBRID_KINDS]

    def outer(task):
        g, kind = task
        ids = [n.id for n in inp.index.nights_of(g.variant, g.heldout)]
        nights, meta, missing = proxy_set(inp, g.variant, ids, LENGTHS)
        d = out / "outer" / g.variant
        nf = dec.write_nights(d / f"{g.key}.{kind}.nights.jsonl", nights)
        if kind == "BASE":
            bias = None
        elif kind == "A":
            bias = g.bias
        else:
            bias = fit_of[(g.variant, g.key, int(kind.split("-")[1]))]["bias"]
        fm = inp.folds(g.variant) if bias is None else dec.biased_models(inp.folds(g.variant), g.heldout, bias,
                                                                         d / f"{g.key}.{kind}.models")
        try:
            recs = dec.evaluate(nf, meta, "HYBRID", inp.params(g.theta), d / f"{g.key}.{kind}.json", fold_models=fm,
                                extra={"kind": kind, "group": g.key, "bias": bias})
        finally:
            nf.unlink(missing_ok=True)
            if fm != inp.folds(g.variant):
                shutil.rmtree(fm, ignore_errors=True)
        return recs, missing

    res = map_threads(outer, tasks, jobs)
    recs = [r for rs, _ in res for r in rs]
    for _, m in res:
        missing_all += m

    def context(task):
        v, est = task
        ids = [n.id for n in inp.index.for_variant(v)]
        nights, meta, _ = proxy_set(inp, v, ids, LENGTHS)
        d = out / "outer" / v
        nf = dec.write_nights(d / f"{est}.nights.jsonl", nights)
        params = {"estimator": "RULES"} if est == "RULES" else deep_merge({"estimator": "HMM"}, physiology_hmm_patch())
        try:
            return dec.evaluate(nf, meta, est, deep_merge(dict(inp.cfg.base_params), params), d / f"{est}.json",
                                extra={"kind": est, "group": "-", "bias": None})
        finally:
            nf.unlink(missing_ok=True)

    ctx_tasks = [(v, e) for v in inp.variants for e in CONTEXT_ESTIMATORS]
    recs += [r for rs in map_threads(context, ctx_tasks, max(1, min(jobs, len(ctx_tasks)))) for r in rs]
    if cli.fingerprint().get("sha256") != fp.get("sha256"):
        raise NapError("algo-cli changed during the nap evaluation")
    _write_jsonl(out / "results.jsonl", recs)
    meta = {"run": inp.cfg.run, "runDir": str(inp.run_dir), "estimator": inp.label, "config": inp.config,
            "algoCli": fp, "code": code_fingerprint(), "runAlgoCliSha256": inp.run_build, "groups": len(inp.groups),
            "missingProxies": missing_all, "prereg": PREREG_PATH, "preregSha256": PREREG_SHA256,
            "lengths": list(LENGTHS), "ruleLengths": list(RULE_LENGTHS), "thresholds": list(THRESHOLDS),
            "accMargin": ACC_MARGIN, "tiePp": TIE_PP}
    summary = summarize(recs, fits, repro, meta, inp.variants)
    write_json(out / "summary.json", _clean(summary))
    (out / "report.md").write_text(render(summary), encoding="utf-8")
    return summary


def rerender(out: Path, variants: Sequence[str] = report.HEADLINE_VARIANTS) -> dict:
    """Recomputes ``summary.json`` / ``report.md`` from the stored ``results.jsonl``, ``fits.json`` and
    ``repro/summary.json`` (no algo-cli; run identity from the previous summary's meta)."""
    out = Path(out)
    old = read_json(out / "summary.json")
    recs = _read_jsonl(out / "results.jsonl")
    summary = summarize(recs, read_json(out / "fits.json")["fits"], read_json(out / "repro" / "summary.json"),
                        old["meta"], variants)
    write_json(out / "summary.json", _clean(summary))
    (out / "report.md").write_text(render(summary), encoding="utf-8")
    return summary


def summarize(recs: Sequence[Mapping], fits: Sequence[Mapping], repro: Mapping, meta: Mapping,
              variants: Sequence[str] = report.HEADLINE_VARIANTS) -> dict:
    kinds = [r for r in recs if r["kind"] in HYBRID_KINDS]
    cand = assemble(kinds)
    ccells = cells_of(cand, "candidate", CANDIDATES, variants)
    kcells = cells_of(recs, "kind", (*HYBRID_KINDS, *CONTEXT_ESTIMATORS), variants)
    scores = rule_scores(ccells, variants)
    sec = rule_scores(ccells, variants, dataset="bidsleep")
    decision = adopt(scores) if "A" in scores else None
    return {"meta": dict(meta), "repro": repro, "fits": [{k: f[k] for k in f if k != "trace"} for f in fits],
            "candidates": _strip(ccells), "kinds": _strip(kcells), "scores": scores, "scoresSecondary": sec,
            "comparisons": comparisons(ccells, variants),
            "contextComparisons": comparisons(kcells, variants, ref="A", names=CONTEXT_ESTIMATORS),
            "decision": decision}


def _strip(cells: Mapping) -> dict:
    """Cells without the per-subject maps of the pooled entries' helpers (kept: subject values, for re-rendering)."""
    return {n: {ds: {v: {str(L): c for L, c in per.items()} for v, per in dv.items()} for ds, dv in d.items()}
            for n, d in cells.items()}


def _write_jsonl(path: Path, rows: Sequence[Mapping]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(_clean(r), separators=(",", ":"), allow_nan=False) + "\n")


def _read_jsonl(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


def _clean(obj):
    if isinstance(obj, float):
        return obj if math.isfinite(obj) else None
    if isinstance(obj, np.ndarray):
        return _clean(obj.tolist())
    if isinstance(obj, (np.floating, np.integer)):
        return _clean(obj.item())
    if isinstance(obj, Mapping):
        return {str(k): _clean(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_clean(v) for v in obj]
    return obj


# ------------------------------------------------------------------------------------------------ final ----

def final_model(inp: Inputs, cli: AlgoCli, exp_dir: Path, base_run: Path, out_dir: Path,
                install: Path | None = None, run_name: str | None = None) -> dict:
    """Final bundle + trained defaults of the candidate adopted in ``exp_dir/summary.json`` (C-T only; A keeps the
    shipped bundle, a B candidate needs no bundle change). b_short = :func:`bias.fit_stage_bias` on the LOSO
    out-of-fold proxies (L < T) of every Walch subject, both headline variants pooled, each decoded by its own outer
    fold bundle without b at the final decoder parameters of ``base_run`` (``bias-final``: its summary's
    ``selection.decoderParams`` and trained_defaults' priorExponent); the bundle is ``base_run``'s final bundle plus
    ``stageLogBiasShort``; trained_defaults gains ``hybrid.shortSessionMaxMin`` = T."""
    s = read_json(Path(exp_dir) / "summary.json")
    adopted = s["decision"]["adopted"]
    if adopted == "A":
        raise NapError("the shipped default was kept: there is no new final model to write")
    family, t = adopted.split("-")
    threshold = int(t)
    out_dir = Path(out_dir)
    name = run_name or out_dir.name
    base_run = Path(base_run)
    base_summary = read_json(base_run / "summary.json")
    base_defaults = read_json(base_run / "trained_defaults.json")
    theta = dict(base_summary["selection"]["decoderParams"]["params"])
    beta = float(base_defaults["hybrid"].get("priorExponent", 1.0))
    if beta != 1.0:
        raise NapError(f"{base_run}: priorExponent {beta} != 1 (the experiment decoded with beta = 1)")
    lengths = [L for L in LENGTHS if short_session(L, threshold)]
    walch = sorted({n.subject for v in inp.variants for n in inp.index.for_variant(v)
                    if n.dataset == report.HEADLINE_DATASET})
    work = out_dir / "fit"
    dec = Decoder(inp, cli, work)
    result: dict = {"adopted": adopted, "threshold": threshold, "theta": theta, "beta": beta, "lengths": lengths,
                    "subjects": len(walch)}
    bias = None
    if family == "C":
        counter = itertools.count()

        def decode(b: np.ndarray) -> np.ndarray:
            n = next(counter)
            total = np.zeros((N_STAGES, N_STAGES))
            for v in inp.variants:
                ids = [x.id for x in inp.index.nights_of(v, walch)]
                nights, meta, missing = proxy_set(inp, v, ids, lengths)
                if missing:
                    raise NapError(f"final fit: proxies could not be cut: {missing[:3]}")
                nf = dec.write_nights(work / f"{v}.nights.jsonl", nights)
                fm = inp.folds(v) if not np.any(b) else dec.biased_models(inp.folds(v), walch, b.tolist(),
                                                                          work / f"{v}-{n}.folds")
                try:
                    recs = dec.evaluate(nf, meta, "HYBRID", inp.params(theta), work / f"{v}-{n}.json", fold_models=fm)
                finally:
                    if fm != inp.folds(v):
                        shutil.rmtree(fm, ignore_errors=True)
                total += _pooled(recs)
            return total

        fit = fit_stage_bias(decode)
        bias = fit["bias"]
        result["fit"] = fit
    b = read_json(base_run / "final" / "hybrid_model.json")
    validate_bundle(b)
    if bias is not None:
        b = with_stage_log_bias_short(b, bias)
    b["modelId"] = f"pulsestages-{CONFIGS[inp.config].pool}-{inp.clf}-{name}"
    note = (f"nap experiment (docs/prereg/prereg-naps.md) on run {inp.cfg.run}: candidate {adopted} "
            f"({CANDIDATE_LABEL[adopted]}) adopted; sessions shorter than hybrid.shortSessionMaxMin = {threshold} min")
    note += (f" use stageLogBiasShort, fitted on the LOSO out-of-fold nap proxies (L in {lengths} min) of the "
             f"{report.HEADLINE_DATASET} subjects (both headline variants pooled) so that their decoded stage shares "
             f"match the PSG shares" if bias is not None else " are decoded without stageLogBias")
    b["training"] = dict(b["training"], notes=b["training"]["notes"] + "; " + note)
    path = write_bundle(out_dir / "final" / "hybrid_model.json", b)
    defaults = deep_merge(base_defaults, {"hybrid": {"shortSessionMaxMin": float(threshold)}})
    write_json(out_dir / "trained_defaults.json", defaults)
    sel = dict(base_summary["selection"])
    sel["shortSessionMaxMin"] = {"note": f"pre-registered nap experiment (docs/prereg/prereg-naps.md), candidate "
                                         f"{adopted}: sessions shorter than {threshold} min use the bundle's "
                                         f"stageLogBiasShort" if bias is not None else
                                         f"pre-registered nap experiment, candidate {adopted}: no stageLogBias on "
                                         f"sessions shorter than {threshold} min"}
    meta = dict(base_summary["meta"], run=name, naps={"algoCli": cli.fingerprint(), "code": code_fingerprint(),
                                                      "exp": str(exp_dir)})
    write_json(out_dir / "summary.json", {"meta": meta, "selection": sel,
                                          "naps": _clean({k: v for k, v in result.items()}) | {"bundle": str(path)}})
    result["bundle"] = str(path)
    if install is not None:
        install = Path(install)
        install.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, install)
        result["installed"] = str(install)
    write_json(out_dir / "final" / "result.json", _clean(result))
    return result


def verify_implementation(inp: Inputs, cli: AlgoCli, exp_dir: Path, out_dir: Path, final_bundle: Path | None = None,
                          jobs: int = 4) -> dict:
    """With a build that implements ``hybrid.shortSessionMaxMin`` / ``stageLogBiasShort`` and its defaults: every
    fold bundle carrying b_g AND b_short_g(T) of the adopted C-T, decoded with the DEFAULT shortSessionMaxMin (not
    passed), must reproduce A on all nights and C-T on all proxies session by session; the final bundle must be
    accepted on every night and proxy (resubstitution, not a validation result).

    A build whose sanity check skips sessions shorter than ``post.proportionsMinSessionMin`` (read from the
    parameters its metrics report, :func:`sanity_check_min_session_min`; added after the experiment) must reproduce
    the stored confusion matrices and every other warning, without the ``SANITY_*`` codes of those shorter sessions
    (:func:`expected_warnings`). The summary counts the sessions that lost such codes and gives the build's
    low-confidence rate of the adopted candidate per dataset, variant and proxy length."""
    exp_dir, out_dir = Path(exp_dir), Path(out_dir)
    s = read_json(exp_dir / "summary.json")
    adopted = s["decision"]["adopted"]
    if not adopted.startswith("C-"):
        raise NapError(f"verify_implementation covers an adopted C candidate, not {adopted}")
    threshold = int(adopted.split("-")[1])
    fits = {(f["variant"], f["key"], f["T"]): f["bias"] for f in read_json(exp_dir / "fits.json")["fits"]}
    stored = _read_jsonl(exp_dir / "results.jsonl")
    want_proxy = {(r["variant"], r["night"]): r for r in assemble([r for r in stored if r["kind"] in HYBRID_KINDS])
                  if r["candidate"] == adopted}
    want_night = {}
    with open(inp.bias_dir / "results.jsonl", encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            if r["candidate"] == "B" and r["variant"] in inp.variants:
                want_night[(r["variant"], r["night"])] = r
    dec = Decoder(inp, cli, out_dir)

    def one(g: Group) -> list[tuple[dict, int, float]]:
        d = out_dir / g.variant
        fm = d / f"{g.key}.models"
        dec.biased_models(inp.folds(g.variant), g.heldout, g.bias, fm)
        for sub in g.heldout:
            q = read_json(fm / f"{sub}.json")
            q = with_stage_log_bias_short(q, fits[(g.variant, g.key, threshold)])
            (fm / f"{sub}.json").write_text(json.dumps(q, separators=(",", ":")), encoding="utf-8")
        ids = [n.id for n in inp.index.nights_of(g.variant, g.heldout)]
        nights, meta, _ = proxy_set(inp, g.variant, ids, LENGTHS)
        all_nights = nights + [inp.night(g.variant, i) for i in ids]
        session_ms = {n["id"]: int(n["sessionEndMs"]) - int(n["sessionStartMs"]) for n in all_nights}
        meta.update(night_meta(inp, g.variant, ids))
        nf = dec.write_nights(d / f"{g.key}.nights.jsonl", all_nights)
        try:
            recs = dec.evaluate(nf, meta, "HYBRID", inp.params(g.theta), d / f"{g.key}.json", fold_models=fm,
                                extra={"group": g.key})
        finally:
            nf.unlink(missing_ok=True)
            shutil.rmtree(fm, ignore_errors=True)
        check_min = sanity_check_min_session_min(read_json(d / f"{g.key}.json"))
        return [(r, session_ms[r["night"]], check_min) for r in recs]

    tagged = [x for xs in map_threads(one, inp.groups, jobs) for x in xs]
    recs = [r for r, _, _ in tagged]
    bad_p, bad_n, n_p, n_n, dropped = [], [], 0, 0, 0
    for r, session_ms, check_min in tagged:
        if r["L"] is None:
            n_n += 1
            o = want_night.get((r["variant"], r["night"]))
        else:
            n_p += 1
            o = want_proxy.get((r["variant"], r["night"]))
        want_w = None if o is None else expected_warnings(o["warnings"], session_ms, check_min)
        dropped += int(o is not None and want_w != list(o["warnings"]))
        same = (o is not None and o["confusion"] == r["confusion"] and want_w == r["warnings"]
                and o["estimated"] == r["estimated"])
        if not same:
            (bad_n if r["L"] is None else bad_p).append((r["variant"], r["night"]))
    proxies = [r for r in recs if r["L"] is not None]
    low_conf: dict = {}
    for ds in DATASETS:
        for v in inp.variants:
            per = {L: length_cell(rs)["lowConfidenceRate"] for L in LENGTHS
                   if (rs := [r for r in proxies if r["dataset"] == ds and r["variant"] == v and r["L"] == L])}
            if per:
                if all(L in per for L in RULE_LENGTHS):
                    per["pooled"] = float(np.mean([per[L] for L in RULE_LENGTHS]))
                low_conf.setdefault(ds, {})[v] = {str(k): x for k, x in per.items()}
    res = {"adopted": adopted, "threshold": threshold, "nights": n_n, "nightsIdenticalToA": n_n - len(bad_n),
           "proxies": n_p, "proxiesIdenticalToCandidate": n_p - len(bad_p),
           "sanityCheckMinSessionMin": sorted({c for _, _, c in tagged}), "sanityCodesDropped": dropped,
           "lowConfidenceRate": low_conf, "algoCli": cli.fingerprint(),
           "code": code_fingerprint(), "mismatches": {"nights": bad_n[:10], "proxies": bad_p[:10]}}
    if final_bundle is not None:
        resub = []
        for v in inp.variants:
            ids = [n.id for n in inp.index.for_variant(v)]
            nights, meta, _ = proxy_set(inp, v, ids, LENGTHS)
            meta.update(night_meta(inp, v, ids))
            nf = dec.write_nights(out_dir / "final" / f"{v}.nights.jsonl", nights + [inp.night(v, i) for i in ids])
            pf = write_json(out_dir / "final" / f"{v}.params.json", dict(inp.cfg.base_params))
            m = cli.evaluate(nf, "HYBRID", out_dir / "final" / f"{v}.json", params=pf, model=Path(final_bundle))
            nf.unlink(missing_ok=True)
            rr = report.parse_metrics(m)
            report.check_estimator_used(rr, "HYBRID")
            resub.append({"variant": v, "sessions": len(rr), "estimated": sum(1 for x in rr if x["estimated"]),
                          "hybrid": sum(1 for x in rr if x["estimatorUsed"] == "HYBRID")})
        res["finalBundle"] = {"path": str(final_bundle), "resubstitution": resub}
    write_json(out_dir / "summary.json", _clean(res))
    if bad_n or bad_p:
        raise NapError(f"implementation check failed: {len(bad_n)} nights differ from A, {len(bad_p)} proxies differ "
                       f"from {adopted}, e.g. {(bad_n + bad_p)[:3]}")
    return res


# ----------------------------------------------------------------------------------------------- markdown ----

def _f(x, nd: int = 3) -> str:
    return report._f(x, nd)


def _pct(x) -> str:
    return "—" if x is None or (isinstance(x, float) and not math.isfinite(x)) else f"{100 * x:.1f}"


def _shares(d: Mapping | None) -> str:
    return "—" if not d else " / ".join(_pct(d[s]) for s in STAGES)


def _ci(x: Mapping | None, nd: int = 3) -> str:
    if not x or not x.get("n"):
        return "—"
    return f"{_f(x['meanDiff'], nd)} [{_f(x['ciLow'], nd)}, {_f(x['ciHigh'], nd)}]"


def render(s: Mapping) -> str:
    m = s["meta"]
    variants = list(report.HEADLINE_VARIANTS)
    L = [f"# Nap proxies (pre-registered) — run `{m.get('run', '?')}`", "",
         f"{m.get('estimator', '')} [{m.get('config', '')}], outer LOSO fold bundles and per-group decoder choice of "
         f"the run; proxies = the first L min of every night's OHealth-like session (no data after the end). "
         f"Pre-registration: `{m['prereg']}`. E = mean per-session summed absolute stage-share error "
         f"(percentage points, AWAKE + LIGHT + DEEP + REM); accuracy = pooled over the chunks of a length; pooled "
         f"over L = mean over L in {m['ruleLengths']} of the per-length values; κ = mean per-subject Cohen's kappa "
         f"where defined (reported, not used).", ""]
    rp = s["repro"]
    L += ["## Night-level reproduction", "",
          f"{rp['identical']} of {rp['hybridNights']} HYBRID night decodes (A = fold bundle + b_g, BASE = no b; both "
          "headline variants, Walch + BIDSleep) identical to the stage-proportion experiment's results; context "
          "estimators vs the reference run: " + (", ".join(f"{k} {v['identical']}/{v['nights']}"
                                                         for k, v in rp["contextCheck"].items()) or "not checked")
          + ".", "", "| kind | dataset | variant | nights | κ mean per subject | κ pooled | accuracy |",
          "|---|---|---|---|---|---|---|"]
    for kind, dd in rp["summary"].items():
        for ds, vv in dd.items():
            for v, q in vv.items():
                L.append(f"| {kind} | {ds} | {v} | {q['nights']} | {_f(q['kappa'])} | {_f(q['kappaPooled'])} | "
                         f"{_f(q['accuracy'])} |")
    L.append("")
    d = s.get("decision")
    if d:
        L += ["## Decision (pre-registered rule, Walch)", "",
              f"Rule: {d['rule']}. Accuracy of A (worse variant, pooled) {_f(d['referenceAccuracy'])} → threshold "
              f"{_f(d['threshold'])}; qualifying: {', '.join(d['qualifying'])}; **adopted: {d['adopted']} "
              f"({CANDIDATE_LABEL[d['adopted']]})**.", "",
              "| candidate | accuracy inst. / mean2min (pooled over L) | **accuracy worse** | E inst. / mean2min | "
              "**E worse** | " + " | ".join(f"L={x}: acc / E (worse)" for x in LENGTHS) + " |",
              "|---|---|---|---|---|" + "---|" * len(LENGTHS)]
        for c, q in s["scores"].items():
            L.append(f"| {c} {CANDIDATE_LABEL[c]} | " + " / ".join(_f(q["accuracy"][v]) for v in variants)
                     + f" | **{_f(q['accWorse'])}** | " + " / ".join(_f(q["E"][v], 1) for v in variants)
                     + f" | **{_f(q['EWorse'], 1)}** | "
                     + " | ".join(f"{_f(q['perLength'][str(x)]['accWorse'])} / {_f(q['perLength'][str(x)]['EWorse'], 1)}"
                                  if str(x) in q["perLength"] else "—" for x in LENGTHS) + " |")
        L.append("")
    for ds in DATASETS:
        title = "Walch (PSG), headline" if ds == report.HEADLINE_DATASET else "BIDSleep (EEG headband), secondary"
        L += [f"## {title}", ""]
        for v in variants:
            L += [f"### {v}", "",
                  "| candidate / estimator | L | sessions (not est.) | κ (defined / subjects) | κ pooled | accuracy | "
                  "E (pp) | |ΔP| A / L / D / R (pp) | predicted shares A / L / D / R % | true shares | sanity / "
                  "low-conf. % |", "|---|---|---|---|---|---|---|---|---|---|---|"]
            rows = [(c, s["candidates"].get(c, {}).get(ds, {}).get(v)) for c in CANDIDATES]
            rows += [(e, s["kinds"].get(e, {}).get(ds, {}).get(v)) for e in CONTEXT_ESTIMATORS]
            for name, per in rows:
                if not per:
                    continue
                for key in [*map(str, LENGTHS), "pooled"]:
                    c = per.get(key)
                    if c is None:
                        continue
                    if key == "pooled":
                        L.append(f"| {name} | 30–120 pooled | {c['sessions']} ({c['notEstimated']}) | {_f(c['kappa'])} "
                                 f"| — | {_f(c['accuracy'])} | {_f(c['E'], 1)} | — | — | — | "
                                 f"{_pct(c['sanityAnyRate'])} / {_pct(c['lowConfidenceRate'])} |")
                        continue
                    se = " / ".join(_f(c["stageShareError"][x], 1) for x in STAGES)
                    L.append(f"| {name} | {key} | {c['sessions']} ({c['notEstimated']}) | {_f(c['kappa'])} "
                             f"({c['kappaDefined']} / {c['subjects']}) | {_f(c['kappaPooled'])} | {_f(c['accuracy'])} "
                             f"| {_f(c['E'], 1)} | {se} | {_shares(c['predShares'])} | {_shares(c['trueShares'])} | "
                             f"{_pct(c['sanityAnyRate'])} / {_pct(c['lowConfidenceRate'])} |")
            L.append("")
        L += ["Paired per-subject differences vs A (candidate − A, 95 % bootstrap CI, 10 000 resamples):", "",
              "| candidate | variant | L | ΔE pp | Δaccuracy | Δκ |", "|---|---|---|---|---|---|"]
        for x in s["comparisons"]:
            if x["dataset"] != ds:
                continue
            L.append(f"| {x['candidate']} | {x['variant']} | {x['L']} | {_ci(x['E'], 1)} | {_ci(x['accuracy'])} | "
                     f"{_ci(x.get('kappa'))} |")
        L.append("")
    L += ["## Flags and not-estimated sessions (A, by length)", "",
          "| dataset | variant | L | not estimated (reasons) | " + " | ".join(FLAG_CODES) + " | low-confidence |",
          "|---|---|---|---|" + "---|" * (len(FLAG_CODES) + 1)]
    for ds in DATASETS:
        for v in variants:
            per = s["candidates"].get("A", {}).get(ds, {}).get(v, {})
            for key in map(str, LENGTHS):
                c = per.get(key)
                if c is None:
                    continue
                reasons = ", ".join(f"{k} {n}" for k, n in c["notEstimatedReasons"].items()) or "—"
                L.append(f"| {ds} | {v} | {key} | {c['notEstimated']} ({reasons}) | "
                         + " | ".join(f"{c['flags'][x]} ({_pct(c['flagRates'][x])} %)" for x in FLAG_CODES)
                         + f" | {c['lowConfidence']} ({_pct(c['lowConfidenceRate'])} %) |")
    L += ["", "## Short-session bias fits (C)", "",
          "| variant | group | T | θ_g | fit sessions | b_short A / L / D / R | D | decodes | inner E / accuracy |",
          "|---|---|---|---|---|---|---|---|---|"]
    for f in s["fits"]:
        th = f["theta"]
        L.append(f"| {f['variant']} | {f['key']} | {f['T']} | {th['durationModel']}, {th['emissionWeight']:g}, "
                 f"{th['timePriorWeight']:g} | {f['sessions']} | " + " / ".join(f"{x:+.2f}" for x in f["bias"])
                 + f" | {_f(f['D'], 4)} | {f['decodes']} | {_f(f['innerE'], 1)} / {_f(f['innerAccuracy'])} |")
    L.append("")
    return "\n".join(L)


__all__ = ["CANDIDATES", "LENGTHS", "PREREG_PATH", "PREREG_SHA256", "RULE_LENGTHS", "THRESHOLDS", "Inputs",
           "NapError", "adopt", "assemble", "candidate_kind", "cells_of", "final_model", "fit_short_bias",
           "length_cell", "nap_proxy", "pooled_cell", "rerender", "run_experiment", "rule_scores", "share_error",
           "short_session", "summarize", "verify_implementation"]
