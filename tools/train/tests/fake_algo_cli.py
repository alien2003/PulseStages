#!/usr/bin/env python3
"""FAKE algo-cli for the tests (stdlib only, fast start-up). NOT the PulseStages algorithm.

Implements the command lines of docs/INTERCHANGE.md section 2 that tools/train relies on, with toy features and
decoders, in the file formats of the real Kotlin algo-cli (header layout, metrics block, --param-grid array):

    features --nights <dir|jsonl> --out <csv> [--params <json>]
    evaluate --nights <dir|jsonl> --estimator RULES|HMM|HYBRID [--params] [--model | --fold-models <dir>]
             [--param-grid <grid.json>] --out <metrics.json> [--per-epoch <csv>]
    feature-names

Environment: FAKE_ALGO_CLI_NO_GRID=1 rejects --param-grid with exit code 64 (tests the fallback);
FAKE_ALGO_CLI_LOG=<file> appends one JSON line per call (argv).
"""

from __future__ import annotations

import csv
import json
import math
import os
import sys
from pathlib import Path

FEATURE_SET_VERSION = 7
# hybrid features include some base names (hrNorm, hrVar), like the real list (hrNorm, hrVar, hrSlope, t): the CSV
# then lists only the base features that are not hybrid features after them
FEATURE_NAMES = ["hrNorm", "hrVar", "stepsLog1p", "hrRel", "minutesSinceStart"]
BASE = ["hrNorm", "hrVar", "hrSlope", "t", "cyclePhase", "steps"]
TRAILING = [b for b in BASE if b not in FEATURE_NAMES]
STAGES = ["AWAKE", "LIGHT", "DEEP", "REM"]
EPOCH_MS = 60_000
DEFAULT_EMISSIONS = {"awake": (2.8, 0.9), "light": (1.0, 0.45), "deep": (0.1, 0.35), "rem": (1.7, 0.55)}


class Usage(Exception):
    pass


def parse_args(argv, allowed):
    out, i = {}, 0
    while i < len(argv):
        a = argv[i]
        if not a.startswith("--") or a[2:] not in allowed or i + 1 >= len(argv):
            raise Usage(f"unknown or incomplete option {a}")
        out[a[2:]] = argv[i + 1]
        i += 2
    return out


def load_nights(path):
    p = Path(path)
    if p.is_dir():
        return [json.loads(f.read_text()) for f in sorted(p.glob("*.night.json"))]
    return [json.loads(line) for line in p.read_text().splitlines() if line.strip()]


def percentile(vals, q):
    v = sorted(vals)
    if not v:
        return float("nan")
    pos = (len(v) - 1) * q / 100.0
    lo = int(math.floor(pos))
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (pos - lo)


def night_epochs(n):
    s0, s1 = n["sessionStartMs"], n["sessionEndMs"]
    N = int(math.ceil((s1 - s0) / EPOCH_MS))
    hr = sorted(n["hr"])
    steps = [0.0] * N
    for a, b, c in n.get("steps", []):
        k = int((a - s0) // EPOCH_MS)
        if 0 <= k < N:
            steps[k] += c
    raw = []
    j = 0
    for k in range(N):
        mid = s0 + (k + 0.5) * EPOCH_MS
        while j + 1 < len(hr) and abs(hr[j + 1][0] - mid) <= abs(hr[j][0] - mid):
            j += 1
        raw.append(hr[j][1] if hr and abs(hr[j][0] - mid) <= 5 * 60_000 else None)
    vals = [x for x in raw if x is not None]
    p10, p50 = percentile(vals, 10), percentile(vals, 50)
    den = max(p50 - p10, 2.0) if vals else 1.0
    nadir = min(vals) if vals else float("nan")
    diffs = [abs(raw[k] - raw[k - 1]) for k in range(1, N) if raw[k] is not None and raw[k - 1] is not None]
    med = percentile(diffs, 50) if diffs else 1.0
    med = med if med > 0 else 1.0
    labels = n.get("labels", [])
    lab = [None] * N
    votes = [dict() for _ in range(N)]
    for a, b, st in labels:
        k = int((a - s0) // EPOCH_MS)
        if 0 <= k < N:
            votes[k][st] = votes[k].get(st, 0) + (b - a)
    for k in range(N):
        if votes[k]:
            best = max(votes[k].values())
            tied = [s for s, w in votes[k].items() if w == best]
            lab[k] = tied[0] if len(tied) == 1 else sorted(tied)[0]
    rows = []
    for k in range(N):
        x = raw[k]
        has = x is not None
        prev = raw[k - 1] if k > 0 else None
        hr_norm = (x - p10) / den if has else float("nan")
        hr_var = (abs(x - prev) / med) if has and prev is not None else float("nan")
        slope = (x - prev) if has and prev is not None else float("nan")
        win = sum(steps[max(0, k - 5): k + 6])
        hybrid = [hr_norm, hr_var, math.log1p(win), (x - nadir) if has else float("nan"), float(k)]
        base = [hr_norm, hr_var, slope, (k + 0.5) / N, float("nan"), steps[k]]
        rows.append({"epoch": k, "start_ms": s0 + k * EPOCH_MS, "has_data": has, "label": lab[k],
                     "hybrid": hybrid, "base": base})
    return rows


def fmt(v):
    if isinstance(v, float) and not math.isfinite(v):
        return ""
    return repr(float(v))


def cmd_features(argv):
    a = parse_args(argv, {"nights", "out", "params"})
    out = Path(a["out"])
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["night_id", "subject", "epoch", "start_ms", "has_data", "label"] + FEATURE_NAMES + TRAILING)
        for n in load_nights(a["nights"]):
            for r in night_epochs(n):
                w.writerow([n["id"], n["subject"], r["epoch"], r["start_ms"], 1 if r["has_data"] else 0,
                            r["label"] or ""] + [fmt(v) for v in r["hybrid"]]
                           + [fmt(r["base"][BASE.index(b)]) for b in TRAILING])


def cmd_feature_names(argv):
    if argv:
        raise Usage("feature-names takes no options")
    print(json.dumps({"algoVersion": 1, "featureSetVersion": FEATURE_SET_VERSION, "featureNames": FEATURE_NAMES}))


def softmax(z):
    m = max(z)
    e = [math.exp(v - m) for v in z]
    s = sum(e)
    return [v / s for v in e]


def classify(bundle, x):
    z = []
    for v, m, s in zip(x, bundle["standardization"]["mean"], bundle["standardization"]["std"]):
        q = (v - m) / s if math.isfinite(v) else 0.0
        z.append(q if math.isfinite(q) else 0.0)
    a = z
    for layer in bundle["classifier"]["layers"]:
        a = [sum(w * v for w, v in zip(row, a)) + b for row, b in zip(layer["weights"], layer["bias"])]
        a = [max(v, 0.0) for v in a] if layer["activation"] == "relu" else softmax(a)
    return a


def bundle_ok(b):
    return b.get("featureNames") == FEATURE_NAMES and b.get("featureSetVersion") == FEATURE_SET_VERSION


def is_nap(n, params):
    """A session strictly shorter than napMode.maxSessionMin (0 = no naps, the fake's default when not given)."""
    short_max = (params.get("napMode") or {}).get("maxSessionMin", 0.0)
    return short_max > 0 and (n["sessionEndMs"] - n["sessionStartMs"]) / 60_000.0 < short_max


def predict(n, rows, estimator, params, bundle):
    warnings = []
    coverage = sum(r["has_data"] for r in rows) / max(1, len(rows))
    used = estimator
    if coverage < 0.4:
        return None, "LOW_COVERAGE", used, warnings
    if coverage < 0.6 and estimator != "RULES":
        used = "RULES"
        warnings.append("RULES_FALLBACK_LOW_COVERAGE")
    if used == "HYBRID" and (bundle is None or not bundle_ok(bundle)):
        used = "HMM"
        warnings.append("HYBRID_MODEL_UNAVAILABLE")
    pred = []
    if used == "RULES":
        for r in rows:
            h, st = r["base"][0], r["base"][5]
            if not r["has_data"]:
                pred.append("LIGHT")
            elif h > 2.0 or st > 0:
                pred.append("AWAKE")
            elif h < 0.4:
                pred.append("DEEP")
            elif h > 1.3:
                pred.append("REM")
            else:
                pred.append("LIGHT")
    elif used == "HMM":
        em = (params.get("hmm") or {}).get("emissions")
        for r in rows:
            if not r["has_data"]:
                pred.append("LIGHT")
                continue
            best, arg = -math.inf, "LIGHT"
            for s in STAGES:
                if em:
                    mu, sd = em[s.lower()]["hrNorm"]["mean"], em[s.lower()]["hrNorm"]["sd"]
                else:
                    mu, sd = DEFAULT_EMISSIONS[s.lower()]
                ll = -0.5 * ((r["base"][0] - mu) / sd) ** 2 - math.log(sd)
                if ll > best:
                    best, arg = ll, s
            pred.append(arg)
    else:
        hp = params.get("hybrid", {})
        ew, tw = hp.get("emissionWeight", 1.0), hp.get("timePriorWeight", 1.0)
        beta = hp.get("priorExponent", 1.0)
        # like core-algo: a nap (shorter than napMode.maxSessionMin; 0 here when not given) uses the bundle's nap
        # section when it has one, else the main model with stageLogBiasShort (HybridEstimator / HybridEmissions)
        short = is_nap(n, params)
        nap = bundle.get("nap") if short else None
        if nap is not None:
            bundle, bias = nap, nap.get("stageLogBias") or [0.0] * 4
        else:
            bias = ((bundle.get("stageLogBiasShort") or bundle.get("stageLogBias")) if short
                    else bundle.get("stageLogBias")) or [0.0] * 4
        prior = bundle["classPriors"]
        table, bm = bundle["timePrior"]["table"], bundle["timePrior"]["binMinutes"]
        for r in rows:
            if not r["has_data"]:
                pred.append("LIGHT")
                continue
            p = classify(bundle, r["hybrid"])
            tp = table[min(int(r["epoch"] // bm), len(table) - 1)]
            sc = [ew * (math.log(max(p[i], 1e-300)) - beta * math.log(prior[i])) + bias[i]
                  + tw * math.log(tp[i] / prior[i]) for i in range(4)]
            pred.append(STAGES[max(range(4), key=lambda i: sc[i])])
        if hp.get("durationModel", "EXPLICIT") == "GEOMETRIC":
            sm = list(pred)
            for k in range(1, len(pred) - 1):
                if pred[k - 1] == pred[k + 1] != pred[k]:
                    sm[k] = pred[k - 1]
            pred = sm
    return pred, None, used, warnings


def kappa(c):
    n = sum(map(sum, c))
    if n == 0:
        return None
    po = sum(c[i][i] for i in range(4)) / n
    pe = sum(sum(c[i]) * sum(c[j][i] for j in range(4)) for i in range(4)) / (n * n)
    return None if pe >= 1 else (po - pe) / (1 - pe)


def deep_merge(base, patch):
    out = dict(base)
    for k, v in patch.items():
        out[k] = deep_merge(out[k], v) if isinstance(out.get(k), dict) and isinstance(v, dict) else v
    return out


def evaluate_once(nights, estimator, params, model_path, fold_dir, per_epoch_rows):
    estimator = params.get("estimator", estimator)
    scored, skipped = [], []
    pooled = [[0] * 4 for _ in range(4)]
    for n in nights:
        rows = night_epochs(n)
        bundle = None
        if not n.get("labels"):
            skipped.append({"id": n["id"], "subject": n["subject"], "reason": "NO_LABELS"})
            continue
        if fold_dir is not None:
            mp = Path(fold_dir) / f"{n['subject']}.json"
            if not mp.is_file():
                skipped.append({"id": n["id"], "subject": n["subject"], "reason": "NO_FOLD_MODEL"})
                continue
            bundle = json.loads(mp.read_text())
        elif model_path:
            bundle = json.loads(Path(model_path).read_text())
        pred, reason, used, warns = predict(n, rows, estimator, params, bundle)
        if pred is None:
            skipped.append({"id": n["id"], "subject": n["subject"], "reason": reason})
            continue
        c = [[0] * 4 for _ in range(4)]
        s0 = n["sessionStartMs"]
        truth_min = dict.fromkeys(STAGES, 0.0)
        for a, b, st in n.get("labels", []):
            k = int(((a + b) / 2 - s0) // EPOCH_MS)
            if 0 <= k < len(pred):
                c[STAGES.index(st)][STAGES.index(pred[k])] += 1
                truth_min[st] += (b - a) / 60_000
                if per_epoch_rows is not None:
                    per_epoch_rows.append([n["id"], a, st, pred[k]])
        est_min = dict.fromkeys(STAGES, 0.0)
        for k, p in enumerate(pred):
            a = s0 + k * EPOCH_MS
            est_min[p] += (min(a + EPOCH_MS, n["sessionEndMs"]) - a) / 60_000
        for i in range(4):
            for j in range(4):
                pooled[i][j] += c[i][j]
        scored.append({"id": n["id"], "subject": n["subject"], "variant": n.get("variant"), "estimator": used,
                       "mode": "NAP" if is_nap(n, params) else "NIGHT",
                       "kappa": kappa(c), "scoredChunks": sum(map(sum, c)), "confusion": c,
                       "minutesError": {s.lower(): abs(est_min[s] - truth_min[s]) for s in ("DEEP", "REM", "AWAKE")},
                       "warnings": warns})
    reasons = {}
    for x in skipped:
        reasons[x["reason"]] = reasons.get(x["reason"], 0) + 1
    return {"estimator": estimator, "params": params,
            "nights": {"total": len(nights), "evaluated": len(scored), "skipped": len(skipped),
                       "skipReasons": reasons},
            "skipped": skipped, "pooled": {"labels": STAGES, "confusion": pooled, "kappa": kappa(pooled)},
            "perNight": scored}


def cmd_evaluate(argv):
    a = parse_args(argv, {"nights", "estimator", "params", "model", "fold-models", "out", "per-epoch",
                          "param-grid"})
    if "param-grid" in a and os.environ.get("FAKE_ALGO_CLI_NO_GRID") == "1":
        raise Usage("unknown option --param-grid")
    if a.get("estimator") not in ("RULES", "HMM", "HYBRID") or "out" not in a or "nights" not in a:
        raise Usage("evaluate needs --nights, --estimator RULES|HMM|HYBRID, --out")
    if "model" in a and "fold-models" in a:
        raise Usage("--model and --fold-models are mutually exclusive")
    nights = load_nights(a["nights"])
    params = json.loads(Path(a["params"]).read_text()) if "params" in a else {}
    params = dict(params, estimator=a["estimator"])
    pe = [] if "per-epoch" in a else None
    if "param-grid" in a:
        patches = json.loads(Path(a["param-grid"]).read_text())
        if not isinstance(patches, list) or not patches or not all(isinstance(p, dict) for p in patches):
            print("error: a parameter grid must be a non-empty JSON array of objects", file=sys.stderr)
            raise SystemExit(65)
        blocks = []
        for i, patch in enumerate(patches):
            block = evaluate_once(nights, a["estimator"], deep_merge(params, patch), a.get("model"),
                                  a.get("fold-models"), None)
            blocks.append({"index": i, "patch": patch, **block})
        res = {"grid": blocks}
    else:
        res = evaluate_once(nights, a["estimator"], params, a.get("model"), a.get("fold-models"), pe)
    Path(a["out"]).write_text(json.dumps(res))
    if pe is not None:
        with open(a["per-epoch"], "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["night_id", "start_ms", "truth", "predicted"])
            w.writerows(pe)


def main(argv):
    if os.environ.get("FAKE_ALGO_CLI_LOG"):
        with open(os.environ["FAKE_ALGO_CLI_LOG"], "a") as f:
            f.write(json.dumps(argv) + "\n")
    try:
        if not argv:
            raise Usage("no command")
        if argv[0] == "features":
            cmd_features(argv[1:])
        elif argv[0] == "evaluate":
            cmd_evaluate(argv[1:])
        elif argv[0] == "feature-names":
            cmd_feature_names(argv[1:])
        else:
            raise Usage(f"unknown command {argv[0]}")
    except Usage as e:
        print(f"usage error: {e}", file=sys.stderr)
        return 64
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
