"""Metrics aggregation, selection of defaults and the markdown report (for ALGORITHM.md).

Input: per-night results parsed from ``algo-cli evaluate`` JSON (see :func:`parse_metrics` for the fields relied
on). All pooled / per-subject / per-dataset numbers are recomputed here from the per-night 4x4 confusion matrices
(rows = truth, columns = prediction, AWAKE/LIGHT/DEEP/REM, counts of scored 30-s label chunks), so results of many
evaluate calls (one per fold group) can be combined and split by the held-out subject's dataset.

Selection rule (spec section 9: "the default estimator is the best by kappa", "report the worse variant"):
score(estimator, training config) = min over the OHealth-like variants (instantaneous, mean2min) of the mean
per-subject Cohen's kappa on Walch (PSG) held-out subjects. The same rule picks the training configuration per
classifier, LR vs MLP, HMM-trained vs the physiology table, mask vs zero for BIDSleep and the default estimator.
The trained HMM estimates are adopted only if they also pass a validity gate (:func:`hmm_validity`: the
specification's hrNorm order DEEP < LIGHT < REM < AWAKE for the estimates on all subjects, and pooled predicted
stage shares inside the post-processing sanity corridors on both headline variants).
Final decoder params: the most frequent inner-CV choice over the outer folds of the chosen (configuration,
classifier) on the headline variants; ties -> higher mean inner-CV kappa, then grid order. The evidence is also
reported per distinct inner selection (in ``per-group`` mode one selection is shared by the folds of an outer
group), with the inner-CV margin of each winner over the runner-up.

Per-subject kappa summaries: mean, sample sd (n − 1) and median over subjects. Differences between candidates are
reported as paired per-subject differences with a percentile bootstrap CI (:func:`paired_bootstrap`).
"""

from __future__ import annotations

import math
from collections import Counter, defaultdict
from typing import Any, Iterable, Mapping, Sequence

import numpy as np

from .util import N_STAGES, STAGES, canonical_json

HEADLINE_VARIANTS = ("instantaneous", "mean2min")
HEADLINE_DATASET = "walch"
MINUTE_STAGES = ("DEEP", "REM", "AWAKE")
CHUNK_MIN = 0.5                      # scoring resolution (30-s label chunks)
ESTIMATOR_ORDER = ("RULES", "HMM-table", "HMM-trained", "HYBRID-LR", "HYBRID-MLP")
ESTIMATOR_TYPE = {"RULES": "RULES", "HMM-table": "HMM", "HMM-trained": "HMM", "HYBRID-LR": "HYBRID",
                  "HYBRID-MLP": "HYBRID"}
CLASSIFIER_LABEL = {"lr": "HYBRID-LR", "mlp": "HYBRID-MLP"}
BID_STEP_CONFIGS = ("walch+bid-mask", "walch+bid-zero", "walch+bid-nonstep")   # nonstep: retired, old runs only
# core-algo PostParams sanity defaults (sanityDeepMin / sanityDeepMax / sanityRemMax): the HMM validity gate
SANITY = {"deepMin": 0.03, "deepMax": 0.35, "remMax": 0.40}
BOOTSTRAP_SAMPLES = 10_000


class MetricsFormatError(ValueError):
    pass


# --------------------------------------------------------------------------------------------- parsing ----

def _confusion(v: Any) -> np.ndarray | None:
    if v is None:
        return None
    a = np.asarray(v, dtype=float)
    if a.shape != (N_STAGES, N_STAGES):
        raise MetricsFormatError(f"confusion matrix must be 4x4, got shape {a.shape}")
    return a


def _stage_dict(v: Any) -> dict[str, float] | None:
    if not isinstance(v, Mapping):
        return None
    return {str(k).upper(): float(x) for k, x in v.items() if x is not None}


def _warnings(v: Any) -> list[str]:
    return [str(w) for w in v] if v else []


def parse_metrics(obj: Mapping) -> list[dict]:
    """Per-night records of one ``algo-cli evaluate`` metrics block (docs/INTERCHANGE.md section 2).

    Relied-on fields: ``perNight`` = the scored nights, each with ``id``, ``estimator`` (the estimator actually
    used), ``warnings`` (codes), ``kappa``, ``confusion`` (4x4 counts, rows = truth) and ``minutesError``
    ``{deep, rem, awake}``; ``skipped`` = the nights that were not scored (``id``, ``reason``); ``nights`` =
    ``{total, evaluated, skipped}`` (checked against both lists); ``pooled.confusion`` (must equal the sum of the
    per-night matrices). Returns scored nights first, then skipped ones.
    """
    nights = obj.get("perNight")
    skipped = obj.get("skipped", [])
    if not isinstance(nights, list):
        raise MetricsFormatError("metrics JSON has no 'perNight' list")
    if not isinstance(skipped, list):
        raise MetricsFormatError("metrics JSON: 'skipped' must be a list of {id, reason}")
    out = []
    for n in nights:
        nid = n.get("id") if isinstance(n, Mapping) else None
        if nid is None:
            raise MetricsFormatError("perNight entry without id")
        c = _confusion(n.get("confusion"))
        if c is None:
            raise MetricsFormatError(f"night {nid}: no confusion matrix (algo-cli older than the per-night "
                                     f"'confusion' field? rebuild it with ./gradlew :algo-cli:installDist)")
        out.append({"id": str(nid), "estimated": True, "reason": None, "estimatorUsed": n.get("estimator"),
                    "warnings": _warnings(n.get("warnings")), "kappa": n.get("kappa"), "confusion": c,
                    "minutesError": _stage_dict(n.get("minutesError"))})
    for n in skipped:
        nid = n.get("id") if isinstance(n, Mapping) else None
        if nid is None:
            raise MetricsFormatError("skipped entry without id")
        out.append({"id": str(nid), "estimated": False, "reason": n.get("reason"), "estimatorUsed": None,
                    "warnings": [], "kappa": None, "confusion": None, "minutesError": None})
    counts = obj.get("nights")
    if isinstance(counts, Mapping):
        if counts.get("evaluated", len(nights)) != len(nights) or counts.get("skipped", len(skipped)) != len(skipped):
            raise MetricsFormatError(f"metrics JSON: nights {dict(counts)} disagree with perNight ({len(nights)}) / "
                                     f"skipped ({len(skipped)})")
    pooled = obj.get("pooled")
    pc = _confusion(pooled.get("confusion")) if isinstance(pooled, Mapping) else None
    if pc is not None:
        total = sum((r["confusion"] for r in out if r["confusion"] is not None), np.zeros((N_STAGES, N_STAGES)))
        if not np.allclose(total, pc):
            raise MetricsFormatError("per-night confusion matrices do not add up to the pooled matrix")
    return out


def check_estimator_used(records: Sequence[dict], requested: str) -> None:
    """Fails loudly when algo-cli silently fell back from HYBRID to HMM (bundle rejected)."""
    if requested != "HYBRID":
        return
    bad = [r["id"] for r in records if r["estimated"] and ("HYBRID_MODEL_UNAVAILABLE" in r["warnings"]
                                                           or str(r["estimatorUsed"] or "HYBRID") == "HMM")]
    if bad:
        raise MetricsFormatError(f"algo-cli fell back to HMM (HYBRID_MODEL_UNAVAILABLE) for {len(bad)} nights, e.g. "
                                 f"{bad[:3]}: the bundle was rejected (featureNames / featureSetVersion mismatch?)")


# ------------------------------------------------------------------------------------------------ metrics ----

def kappa(c: np.ndarray) -> float:
    c = np.asarray(c, dtype=float)
    n = c.sum()
    if n <= 0:
        return float("nan")
    po = np.trace(c) / n
    pe = float(c.sum(axis=1) @ c.sum(axis=0)) / (n * n)
    if pe >= 1.0:
        return float("nan")
    return float((po - pe) / (1.0 - pe))


def accuracy(c: np.ndarray) -> float:
    n = float(np.sum(c))
    return float(np.trace(c) / n) if n > 0 else float("nan")


def per_stage(c: np.ndarray) -> dict[str, dict[str, float]]:
    c = np.asarray(c, dtype=float)
    out = {}
    for i, s in enumerate(STAGES):
        tp, pred, true = c[i, i], c[:, i].sum(), c[i, :].sum()
        p = tp / pred if pred > 0 else float("nan")
        r = tp / true if true > 0 else float("nan")
        if pred == 0 and true == 0:
            f1 = float("nan")
        elif tp == 0:
            f1 = 0.0
        else:
            f1 = 2 * p * r / (p + r)
        out[s] = {"precision": float(p), "recall": float(r), "f1": float(f1), "support": float(true)}
    return out


def macro_f1(c: np.ndarray) -> float:
    f = [v["f1"] for v in per_stage(c).values() if not math.isnan(v["f1"])]
    return float(np.mean(f)) if f else float("nan")


def row_normalize(c: np.ndarray) -> np.ndarray:
    c = np.asarray(c, dtype=float)
    s = c.sum(axis=1, keepdims=True)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(s > 0, c / np.where(s > 0, s, 1), 0.0)


def night_minutes_error(rec: Mapping, chunk_min: float = CHUNK_MIN) -> dict[str, float]:
    me = rec.get("minutesError")
    if me:
        return {s: float(me[s]) for s in MINUTE_STAGES}
    c = np.asarray(rec["confusion"], dtype=float)
    return {s: float(abs(c[:, STAGES.index(s)].sum() - c[STAGES.index(s), :].sum()) * chunk_min)
            for s in MINUTE_STAGES}


def _nanstats(v: Sequence[float]) -> dict[str, float]:
    a = np.asarray([x for x in v if x is not None and not math.isnan(x)], dtype=float)
    if a.size == 0:
        return {"mean": float("nan"), "sd": float("nan"), "median": float("nan"), "n": 0}
    return {"mean": float(a.mean()), "sd": float(a.std(ddof=1)) if a.size > 1 else 0.0,
            "median": float(np.median(a)), "n": int(a.size)}


def subject_kappas(records: Iterable[Mapping]) -> dict[str, float]:
    by_subj: dict[str, np.ndarray] = {}
    for r in records:
        if r["estimated"]:
            by_subj[r["subject"]] = by_subj.get(r["subject"], np.zeros((N_STAGES, N_STAGES))) + np.asarray(
                r["confusion"], float)
    return {s: kappa(c) for s, c in sorted(by_subj.items())}


def mean_subject_kappa(records: Iterable[Mapping]) -> float:
    return _nanstats(list(subject_kappas(records).values()))["mean"]


def aggregate(records: Sequence[Mapping]) -> dict:
    est = [r for r in records if r["estimated"]]
    c = sum((np.asarray(r["confusion"], float) for r in est), np.zeros((N_STAGES, N_STAGES)))
    sk = subject_kappas(est)
    ks = _nanstats(list(sk.values()))
    me = [night_minutes_error(r) for r in est]
    skipped = Counter(str(r.get("reason") or "unknown") for r in records if not r["estimated"])
    ps = per_stage(c)
    return {
        "nights": len(records), "nightsEstimated": len(est), "nightsSkipped": sum(skipped.values()),
        "skipReasons": dict(sorted(skipped.items())), "subjects": len(sk),
        "rulesFallbackNights": sum(1 for r in est if "RULES_FALLBACK_LOW_COVERAGE" in r.get("warnings", ())),
        "kappaPooled": kappa(c), "kappaSubjectMean": ks["mean"], "kappaSubjectSd": ks["sd"],
        "kappaSubjectMedian": ks["median"], "kappaSubjectN": ks["n"], "accuracy": accuracy(c),
        "macroF1": macro_f1(c), "f1": {s: ps[s]["f1"] for s in STAGES},
        "precision": {s: ps[s]["precision"] for s in STAGES}, "recall": {s: ps[s]["recall"] for s in STAGES},
        "confusion": c.tolist(), "confusionRowNorm": row_normalize(c).tolist(),
        "minutesError": {s: (float(np.mean([m[s] for m in me])) if me else float("nan")) for s in MINUTE_STAGES},
        "subjectKappa": sk,
    }


GROUP_KEYS = ("estimator", "config", "variant", "dataset", "evaluation")


def summarize(records: Sequence[Mapping]) -> list[dict]:
    groups: dict[tuple, list] = defaultdict(list)
    for r in records:
        groups[tuple(r[k] for k in GROUP_KEYS)].append(r)
    rows = []
    for key in sorted(groups, key=_group_sort_key):
        rows.append({**dict(zip(GROUP_KEYS, key)), **aggregate(groups[key])})
    return rows


def _group_sort_key(key: tuple) -> tuple:
    est, cfg, var, ds, ev = key
    eo = ESTIMATOR_ORDER.index(est) if est in ESTIMATOR_ORDER else len(ESTIMATOR_ORDER)
    return (ds != HEADLINE_DATASET, ds, var, eo, est, cfg, ev)


# ---------------------------------------------------------------------------------------------- selection ----

def headline_score(rows: Sequence[Mapping], estimator: str, config: str, dataset: str = HEADLINE_DATASET,
                   variants: Sequence[str] = HEADLINE_VARIANTS) -> tuple[float, dict[str, float]]:
    """(worse-variant mean per-subject kappa, kappa by variant); -inf when a variant is missing."""
    by_var = {}
    for r in rows:
        if r["estimator"] == estimator and r["config"] == config and r["dataset"] == dataset:
            by_var[r["variant"]] = r["kappaSubjectMean"]
    vals = [by_var.get(v) for v in variants]
    if any(v is None or math.isnan(v) for v in vals):
        return float("-inf"), by_var
    return float(min(vals)), by_var


def _best(cands: Mapping[Any, float], order: Sequence[Any]) -> Any:
    """argmax with ties resolved by ``order`` (earlier wins)."""
    best = None
    for k in order:
        if k in cands and (best is None or cands[k] > cands[best]):
            best = k
    return best


def select_defaults(rows: Sequence[Mapping], fold_selections: Sequence[Mapping] = (),
                    configs_order: Sequence[str] = (), variants: Sequence[str] = HEADLINE_VARIANTS,
                    dataset: str = HEADLINE_DATASET, hmm_checks: Mapping | None = None) -> dict:
    """Applies the selection rule (module doc) to summary rows; ``fold_selections`` are the per-outer-fold inner-CV
    choices (config, variant, classifier, fold, pointId, params, score, selectionKey, innerMargin); ``hmm_checks``
    maps an HMM pool to facts about its estimates on all subjects (``hrNormOrdered``) for the validity gate."""
    present = sorted({(r["estimator"], r["config"]) for r in rows if r["dataset"] == dataset},
                     key=lambda k: (ESTIMATOR_ORDER.index(k[0]) if k[0] in ESTIMATOR_ORDER else 99, k[1]))
    scores = {k: headline_score(rows, k[0], k[1], dataset, variants) for k in present}
    cfg_order = list(configs_order) or sorted({k[1] for k in present})
    sel: dict[str, Any] = {
        "criterion": (f"mean per-subject Cohen's kappa on {dataset} held-out subjects, worse of the variants "
                      f"{' / '.join(variants)}"),
        "candidates": [{"estimator": e, "config": c, "score": s, "byVariant": bv}
                       for (e, c), (s, bv) in scores.items()],
    }
    best_by_label: dict[str, tuple[str, float]] = {}
    for label in ESTIMATOR_ORDER:
        cands = {c: scores[(label, c)][0] for (e, c) in scores if e == label}
        if cands:
            order = [c for c in cfg_order if c in cands] + sorted(set(cands) - set(cfg_order))
            b = _best(cands, order)
            best_by_label[label] = (b, cands[b])
    sel["bestConfig"] = {k: {"config": v[0], "score": v[1]} for k, v in best_by_label.items()}
    hyb = {k: best_by_label[k][1] for k in ("HYBRID-LR", "HYBRID-MLP") if k in best_by_label}
    if hyb:
        lab = _best(hyb, ("HYBRID-LR", "HYBRID-MLP"))
        sel["classifier"] = {"chosen": "lr" if lab == "HYBRID-LR" else "mlp", "scores": hyb,
                             "config": best_by_label[lab][0]}
        clf_label = lab
        masks = {c: scores[(clf_label, c)][0] for c in BID_STEP_CONFIGS if (clf_label, c) in scores}
        if masks:
            sel["bidSteps"] = {"scores": masks, "chosen": _best(masks, BID_STEP_CONFIGS)}
    if "HMM-table" in best_by_label or "HMM-trained" in best_by_label:
        t = best_by_label.get("HMM-table", (None, float("-inf")))[1]
        tr_cfg, tr = best_by_label.get("HMM-trained", (None, float("-inf")))
        validity = hmm_validity(rows, tr_cfg, hmm_checks, dataset, variants) if tr_cfg is not None else None
        better = bool(tr > t)
        sel["hmm"] = {"table": t, "trained": tr, "trainedPool": tr_cfg, "betterKappa": better,
                      "validity": validity, "useTrained": bool(better and validity is not None and validity["valid"])}
    top = {}
    if "RULES" in best_by_label:
        top["RULES"] = best_by_label["RULES"][1]
    if "hmm" in sel:
        top["HMM"] = sel["hmm"]["trained"] if sel["hmm"]["useTrained"] else sel["hmm"]["table"]
    if hyb:
        top["HYBRID"] = max(hyb.values())
    if top:
        sel["defaultEstimator"] = _best(top, ("HYBRID", "HMM", "RULES"))
        sel["estimatorScores"] = top
    if "classifier" in sel:
        sel["decoderParams"] = choose_decoder_params(fold_selections, sel["classifier"]["config"],
                                                     sel["classifier"]["chosen"], variants)
    sel["comparisons"] = selection_comparisons(rows, sel, best_by_label, dataset, variants)
    return sel


def predicted_shares(row: Mapping) -> dict[str, float]:
    """Pooled predicted stage shares (columns of the confusion matrix) of a summary row."""
    c = np.asarray(row["confusion"], dtype=float)
    tot = c.sum()
    return {s: (float(c[:, i].sum() / tot) if tot > 0 else float("nan")) for i, s in enumerate(STAGES)}


def hmm_validity(rows: Sequence[Mapping], pool: str, hmm_checks: Mapping | None,
                 dataset: str = HEADLINE_DATASET, variants: Sequence[str] = HEADLINE_VARIANTS,
                 sanity: Mapping = SANITY) -> dict:
    """Validity gate for adopting the trained HMM estimates of ``pool`` as defaults.

    (1) The estimates on all subjects (``hmm_checks[pool]["hrNormOrdered"]``, computed by the pipeline) satisfy the
    specification's order mu_DEEP < mu_LIGHT < mu_REM < mu_AWAKE on hrNorm: otherwise the per-night EM and the HMM
    personalization roll back on practically every night. (2) The LOSO predictions of HMM-trained on the headline
    dataset have pooled stage shares inside the post-processing sanity corridors on every headline variant
    (DEEP in [sanityDeepMin, sanityDeepMax], REM <= sanityRemMax). Unknown facts count as failures."""
    problems: list[str] = []
    order = None
    if hmm_checks is not None and pool in hmm_checks and hmm_checks[pool].get("hrNormOrdered") is not None:
        order = bool(hmm_checks[pool]["hrNormOrdered"])
    if order is None:
        problems.append("hrNorm order of the final estimates unknown")
    elif not order:
        problems.append("hrNorm means violate DEEP < LIGHT < REM < AWAKE")
    shares: dict[str, dict[str, float]] = {}
    for r in rows:
        if (r["estimator"] == "HMM-trained" and r["config"] == pool and r["dataset"] == dataset
                and r["variant"] in variants):
            shares[r["variant"]] = predicted_shares(r)
    for v in variants:
        sh = shares.get(v)
        if sh is None:
            problems.append(f"no HMM-trained predictions for {v}")
            continue
        if not sanity["deepMin"] <= sh["DEEP"] <= sanity["deepMax"]:
            problems.append(f"{v}: predicted DEEP share {sh['DEEP']:.1%} outside "
                            f"[{sanity['deepMin']:.0%}, {sanity['deepMax']:.0%}]")
        if not sh["REM"] <= sanity["remMax"]:
            problems.append(f"{v}: predicted REM share {sh['REM']:.1%} > {sanity['remMax']:.0%}")
    return {"hrNormOrdered": order, "predictedShares": shares, "sanity": dict(sanity), "problems": problems,
            "valid": not problems}


def paired_bootstrap(a: Mapping[str, float], b: Mapping[str, float], n_boot: int = BOOTSTRAP_SAMPLES,
                     seed: int = 0, level: float = 0.95) -> dict:
    """Paired per-subject difference a − b (subjects present and finite in both): mean, sample sd, percentile
    bootstrap CI of the mean (resampling subjects), wins / losses / ties."""
    subs = sorted(s for s in set(a) & set(b) if a[s] is not None and b[s] is not None
                  and math.isfinite(a[s]) and math.isfinite(b[s]))
    d = np.array([float(a[s]) - float(b[s]) for s in subs])
    n = int(d.size)
    if n == 0:
        return {"n": 0, "meanDiff": float("nan"), "sdDiff": float("nan"), "ciLow": float("nan"),
                "ciHigh": float("nan"), "wins": 0, "losses": 0, "ties": 0, "level": level}
    rng = np.random.default_rng(seed)
    means = d[rng.integers(0, n, size=(int(n_boot), n))].mean(axis=1)
    lo, hi = np.quantile(means, [(1 - level) / 2, 1 - (1 - level) / 2])
    return {"n": n, "meanDiff": float(d.mean()), "sdDiff": float(d.std(ddof=1)) if n > 1 else 0.0,
            "ciLow": float(lo), "ciHigh": float(hi), "wins": int((d > 0).sum()), "losses": int((d < 0).sum()),
            "ties": int((d == 0).sum()), "level": level}


def _subject_kappa_of(rows: Sequence[Mapping], estimator: str, config: str, dataset: str, variant: str
                      ) -> Mapping[str, float] | None:
    for r in rows:
        if (r["estimator"] == estimator and r["config"] == config and r["dataset"] == dataset
                and r["variant"] == variant):
            return r["subjectKappa"]
    return None


def selection_comparisons(rows: Sequence[Mapping], sel: Mapping, best_by_label: Mapping[str, tuple[str, float]],
                          dataset: str = HEADLINE_DATASET, variants: Sequence[str] = HEADLINE_VARIANTS) -> list[dict]:
    """Paired per-subject comparisons behind the selection (chosen candidate − alternative, per headline variant):
    the chosen classifier [config] vs every other configuration of that classifier and vs the other classifier's
    best configuration; HYBRID vs the adopted HMM and RULES; HMM-trained vs the physiology table."""
    pairs: list[tuple[tuple[str, str], tuple[str, str]]] = []
    if "classifier" in sel:
        lab = CLASSIFIER_LABEL[sel["classifier"]["chosen"]]
        chosen = (lab, sel["classifier"]["config"])
        for (e, c) in sorted({(r["estimator"], r["config"]) for r in rows if r["dataset"] == dataset}):
            if e == lab and c != chosen[1]:
                pairs.append((chosen, (e, c)))
        for other in ("HYBRID-LR", "HYBRID-MLP"):
            if other != lab and other in best_by_label:
                pairs.append((chosen, (other, best_by_label[other][0])))
        h = sel.get("hmm")
        if h:
            hmm_row = ("HMM-trained", h["trainedPool"]) if h.get("useTrained") else ("HMM-table", "untrained")
            pairs.append((chosen, hmm_row))
        if "RULES" in best_by_label:
            pairs.append((chosen, ("RULES", best_by_label["RULES"][0])))
    if "HMM-trained" in best_by_label and "HMM-table" in best_by_label:
        pairs.append((("HMM-trained", best_by_label["HMM-trained"][0]), ("HMM-table", best_by_label["HMM-table"][0])))
    out = []
    for (ea, ca), (eb, cb) in pairs:
        for v in variants:
            ka = _subject_kappa_of(rows, ea, ca, dataset, v)
            kb = _subject_kappa_of(rows, eb, cb, dataset, v)
            if ka is None or kb is None:
                continue
            out.append({"a": {"estimator": ea, "config": ca}, "b": {"estimator": eb, "config": cb}, "variant": v,
                        **paired_bootstrap(ka, kb)})
    return out


def choose_decoder_params(fold_selections: Sequence[Mapping], config: str, classifier: str,
                          variants: Sequence[str] = HEADLINE_VARIANTS) -> dict | None:
    """Mode of the inner-CV choices over the outer folds; ties -> higher mean inner score, then grid index."""
    fs = [f for f in fold_selections if f["config"] == config and f["classifier"] == classifier
          and f["variant"] in variants]
    if not fs:
        return None
    count: Counter = Counter()
    score: dict[str, list[float]] = defaultdict(list)
    params: dict[str, Any] = {}
    grid_index: dict[str, int] = {}
    for f in fs:
        key = canonical_json(f["params"])
        count[key] += 1
        sc = f.get("score")
        score[key].append(float(sc) if sc is not None else float("nan"))
        params[key] = f["params"]
        grid_index[key] = min(grid_index.get(key, 10**9), int(f.get("gridIndex", 10**9)))
    best = sorted(count, key=lambda k: (-count[k], -np.nanmean(score[k]) if not np.all(np.isnan(score[k]))
                                        else 0.0, grid_index[k]))[0]
    # distinct inner selections (per-group mode: one selection shared by all folds of an outer group)
    sel_choice: dict[tuple, str] = {}
    sel_margin: dict[tuple, float | None] = {}
    for f in fs:
        if f.get("selectionKey") is None:
            continue
        key = (f["variant"], f["selectionKey"])
        sel_choice[key] = canonical_json(f["params"])
        sel_margin[key] = f.get("innerMargin")
    sel_count = Counter(sel_choice.values())
    margins = [m for m in sel_margin.values() if m is not None and math.isfinite(m)]
    fields: dict[str, dict[str, dict[str, int]]] = {}
    for f in fs:
        for k, v in f["params"].items():
            fields.setdefault(k, {}).setdefault(str(v), {"folds": 0, "selections": 0})["folds"] += 1
    for key, pk in sel_choice.items():
        for k, v in params[pk].items():
            fields.setdefault(k, {}).setdefault(str(v), {"folds": 0, "selections": 0})["selections"] += 1
    return {"params": params[best], "count": count[best], "folds": len(fs),
            "selections": sel_count.get(best, 0) if sel_choice else None,
            "selectionsTotal": len(sel_choice) if sel_choice else None,
            "innerMargin": ({"min": float(min(margins)), "median": float(np.median(margins)),
                             "max": float(max(margins))} if margins else None),
            "byField": fields,
            "method": "most frequent inner-CV choice over outer folds (ties: mean inner kappa, grid order)",
            "frequencies": sorted(({"params": params[k], "count": n, "selections": sel_count.get(k, 0)}
                                   for k, n in count.items()), key=lambda d: -d["count"])}


# ----------------------------------------------------------------------------------------------- markdown ----

def _f(x: Any, nd: int = 3) -> str:
    if x is None or (isinstance(x, float) and (math.isnan(x) or math.isinf(x))):
        return "—"
    return f"{x:.{nd}f}"


def _pm(m: float, sd: float) -> str:
    return f"{_f(m)} ± {_f(sd)}" if not (m is None or math.isnan(m)) else "—"


def _row_key(r: Mapping) -> tuple:
    return (r["estimator"], r["config"])


def _label(r: Mapping) -> str:
    return r["estimator"] if r["config"] in ("untrained", "-") else f"{r['estimator']} [{r['config']}]"


def render_markdown(rows: Sequence[Mapping], selection: Mapping, meta: Mapping) -> str:
    L: list[str] = []
    L.append(f"# LOSO validation report — run `{meta.get('run', '?')}`")
    L.append("")
    L.append(f"Generated {meta.get('createdAt', '?')} by `tools/train/train.py`. Features and decoding: Kotlin "
             f"`algo-cli` (the code that runs on the phone). Protocol: leave-one-subject-out over the pooled "
             f"training set of each configuration; decoder hyper-parameters chosen by nested grouped "
             f"{meta.get('innerK', 5)}-fold CV over the training subjects only ({meta.get('selection', 'per-subject')}"
             f" selection, scored on {', '.join(meta.get('selectDatasets', ['walch']))}); metrics split by the "
             f"held-out subject's dataset. Kappa = Cohen's kappa on 30-s label epochs (AWAKE / LIGHT / DEEP / REM); "
             f"κ mean ± sd = mean and sample sd (n − 1) of the per-subject kappas.")
    L.append("")
    L.append("Configurations: " + "; ".join(f"`{k}` = {v}" for k, v in meta.get("configDescriptions", {}).items()))
    L.append("")
    # headline
    hv = list(meta.get("headlineVariants", HEADLINE_VARIANTS))
    L.append(f"## Headline: Walch (PSG), OHealth-like session window, worse of {' / '.join(hv)}")
    L.append("")
    L.append(f"| Estimator | κ {hv[0]} (mean ± sd) | κ {hv[1]} (mean ± sd) | **κ worse** | κ median | κ pooled | "
             f"accuracy | macro-F1 | F1 W / L / D / R | abs. error min DEEP / REM / AWAKE |")
    L.append("|---|---|---|---|---|---|---|---|---|---|")
    keys = []
    for r in rows:
        if r["dataset"] == HEADLINE_DATASET and r["variant"] in hv and _row_key(r) not in keys:
            keys.append(_row_key(r))
    for k in keys:
        rv = {r["variant"]: r for r in rows if _row_key(r) == k and r["dataset"] == HEADLINE_DATASET
              and r["variant"] in hv}
        if not all(v in rv for v in hv):
            continue
        worse = min(hv, key=lambda v: (rv[v]["kappaSubjectMean"] if not math.isnan(rv[v]["kappaSubjectMean"])
                                       else -9))
        w = rv[worse]
        f1 = " / ".join(_f(w["f1"][s], 2) for s in STAGES)
        me = " / ".join(_f(w["minutesError"][s], 1) for s in MINUTE_STAGES)
        L.append(f"| {_label(w)} | {_pm(rv[hv[0]]['kappaSubjectMean'], rv[hv[0]]['kappaSubjectSd'])} | "
                 f"{_pm(rv[hv[1]]['kappaSubjectMean'], rv[hv[1]]['kappaSubjectSd'])} | **{_f(w['kappaSubjectMean'])}**"
                 f" ({worse}) | {_f(w['kappaSubjectMedian'])} | {_f(w['kappaPooled'])} | {_f(w['accuracy'])} | "
                 f"{_f(w['macroF1'])} | {f1} | {me} |")
    L.append("")
    # selection
    L.append("## Selection")
    L.append("")
    L.append(f"Rule: {selection.get('criterion', '')}.")
    L.append("")
    if "defaultEstimator" in selection:
        L.append(f"- Default estimator: **{selection['defaultEstimator']}** (scores: "
                 + ", ".join(f"{k} {_f(v)}" for k, v in selection.get("estimatorScores", {}).items()) + ")")
    if "classifier" in selection:
        c = selection["classifier"]
        L.append(f"- Emission classifier: **{c['chosen'].upper()}** with training configuration `{c['config']}` ("
                 + ", ".join(f"{k} {_f(v)}" for k, v in c["scores"].items()) + ")")
    for lab, bc in selection.get("bestConfig", {}).items():
        if bc["config"] != "untrained":
            L.append(f"- Best training configuration for {lab}: `{bc['config']}` ({_f(bc['score'])})")
    if "bidSteps" in selection:
        b = selection["bidSteps"]
        L.append(f"- BIDSleep step handling: **{b['chosen']}** ("
                 + ", ".join(f"{k} {_f(v)}" for k, v in b["scores"].items()) + ")")
    if "hmm" in selection:
        h = selection["hmm"]
        val = h.get("validity") or {}
        if h["useTrained"]:
            verdict = "use the trained estimates"
        elif h.get("betterKappa"):
            verdict = "keep the table (validity gate failed: " + "; ".join(val.get("problems", ["unknown"])) + ")"
        else:
            verdict = "keep the table"
        L.append(f"- HMM: trained (MLE, `{h['trainedPool']}`) {_f(h['trained'])} vs physiology table "
                 f"{_f(h['table'])} → {verdict}")
    dp = selection.get("decoderParams")
    if dp:
        sel_txt = (f"{dp['selections']} of {dp['selectionsTotal']} distinct inner selections, "
                   if dp.get("selectionsTotal") else "")
        mg = dp.get("innerMargin")
        mg_txt = (f"; inner-CV margin of the winner over the runner-up {_f(mg['min'], 4)}–{_f(mg['max'], 4)} "
                  f"(median {_f(mg['median'], 4)})" if mg else "")
        L.append(f"- Decoder params: `{canonical_json(dp['params'])}` — chosen in {sel_txt}{dp['count']} of "
                 f"{dp['folds']} outer folds ({dp['method']}){mg_txt}")
        L.append("")
        L.append("| inner-CV choice | inner selections | outer folds |")
        L.append("|---|---|---|")
        for fr in dp["frequencies"][:10]:
            L.append(f"| `{canonical_json(fr['params'])}` | {fr.get('selections', '—')} | {fr['count']} |")
    L.append("")
    comps = selection.get("comparisons") or []
    if comps:
        L.append("### Paired per-subject comparisons (headline dataset; a − b, 95 % percentile bootstrap CI over "
                 "subjects)")
        L.append("")
        L.append("| a | b | variant | subjects | mean Δκ | 95 % CI | sd of Δ | a better / worse |")
        L.append("|---|---|---|---|---|---|---|---|")
        for c in comps:
            L.append(f"| {_label(c['a'])} | {_label(c['b'])} | {c['variant']} | {c['n']} | {_f(c['meanDiff'])} | "
                     f"[{_f(c['ciLow'])}, {_f(c['ciHigh'])}] | {_f(c['sdDiff'])} | {c['wins']} / {c['losses']} |")
        L.append("")
    # full tables
    L.append("## All results")
    L.append("")
    for ds in sorted({r["dataset"] for r in rows}, key=lambda d: (d != HEADLINE_DATASET, d)):
        for var in sorted({r["variant"] for r in rows if r["dataset"] == ds},
                          key=lambda v: (v.endswith("-psg"), v)):
            sub = [r for r in rows if r["dataset"] == ds and r["variant"] == var]
            L.append(f"### {ds} — {var}")
            L.append("")
            L.append("| Estimator | evaluation | nights (skipped) | RULES fallback | subjects | κ mean ± sd | "
                     "κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE | F1 LIGHT | F1 DEEP | F1 REM | "
                     "abs. err. DEEP min | REM | AWAKE |")
            L.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            for r in sub:
                L.append(f"| {_label(r)} | {r['evaluation']} | {r['nightsEstimated']} ({r['nightsSkipped']}) | "
                         f"{r.get('rulesFallbackNights', 0)} | "
                         f"{r['subjects']} | {_pm(r['kappaSubjectMean'], r['kappaSubjectSd'])} | "
                         f"{_f(r['kappaSubjectMedian'])} | {_f(r['kappaPooled'])} | {_f(r['accuracy'])} | "
                         f"{_f(r['macroF1'])} | " + " | ".join(_f(r["f1"][s]) for s in STAGES) + " | "
                         + " | ".join(_f(r["minutesError"][s], 1) for s in MINUTE_STAGES) + " |")
            L.append("")
    # confusion of the chosen estimator
    chosen = _chosen_rows(rows, selection, hv)
    if chosen:
        L.append("## Confusion matrices of the chosen estimator (rows = PSG/EEG truth, columns = estimate)")
        L.append("")
        for r in chosen:
            L.append(f"### {_label(r)} — {r['dataset']}, {r['variant']} ({r['evaluation']})")
            L.append("")
            L.append("| truth \\ estimate | " + " | ".join(STAGES) + " | | " + " | ".join(f"{s} %" for s in STAGES) + " |")
            L.append("|---|" + "---|" * (2 * N_STAGES + 1))
            for i, s in enumerate(STAGES):
                L.append(f"| {s} | " + " | ".join(f"{int(round(v))}" for v in r["confusion"][i]) + " | | "
                         + " | ".join(f"{100 * v:.1f}" for v in r["confusionRowNorm"][i]) + " |")
            L.append("")
    skipped = [r for r in rows if r["nightsSkipped"]]
    if skipped:
        L.append("## Skipped nights (not estimated by algo-cli)")
        L.append("")
        by_dv: dict[tuple, list] = defaultdict(list)
        for r in rows:
            by_dv[(r["dataset"], r["variant"])].append(r)
        for (d, v), rs in by_dv.items():
            sig = {(r["nightsSkipped"], tuple(r["skipReasons"].items())) for r in rs}
            if len(sig) == 1 and next(iter(sig))[0]:
                n, reasons = next(iter(sig))
                L.append(f"- {d} {v}: {n} by every estimator ({', '.join(f'{k}: {c}' for k, c in reasons)})")
            elif len(sig) > 1:
                for r in rs:
                    if r["nightsSkipped"]:
                        L.append(f"- {d} {v}, {_label(r)}: {r['nightsSkipped']} "
                                 f"({', '.join(f'{k}: {c}' for k, c in r['skipReasons'].items())})")
        L.append("")
    if meta.get("notes"):
        L.append("## Notes")
        L.append("")
        for n in meta["notes"]:
            L.append(f"- {n}")
        L.append("")
    return "\n".join(L)


def _chosen_rows(rows: Sequence[Mapping], selection: Mapping, hv: Sequence[str]) -> list[Mapping]:
    de = selection.get("defaultEstimator")
    if de is None:
        return []
    if de == "HYBRID" and "classifier" in selection:
        est, cfg = CLASSIFIER_LABEL[selection["classifier"]["chosen"]], selection["classifier"]["config"]
    elif de == "HMM" and "hmm" in selection:
        est, cfg = ("HMM-trained", selection["hmm"]["trainedPool"]) if selection["hmm"]["useTrained"] else (
            "HMM-table", "untrained")
    else:
        est, cfg = "RULES", "untrained"
    mine = [r for r in rows if r["estimator"] == est and r["config"] == cfg]
    head = [r for r in mine if r["dataset"] == HEADLINE_DATASET and r["variant"] in hv]
    if not head:
        return []
    worse = min(head, key=lambda r: r["kappaSubjectMean"] if not math.isnan(r["kappaSubjectMean"]) else -9)
    return [r for r in mine if r["variant"] == worse["variant"]]
