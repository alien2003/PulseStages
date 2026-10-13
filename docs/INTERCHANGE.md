# Interchange formats between :core-algo / :algo-cli (Kotlin) and tools/train (Python)

Single source of truth for feature computation and decoding is Kotlin (`:core-algo`). Python only prepares
datasets, fits the emission classifier / HMM-HSMM statistics, and writes reports. Python never re-implements
features or decoders.

## 1. Night file (`*.night.json`, one JSON object per file; or `nights.jsonl`, one object per line)

```json
{
  "id": "walch-46343-inst",          // unique night id
  "subject": "46343",                 // subject id (LOSO grouping key)
  "variant": "instantaneous",         // "instantaneous" | "mean2min" | free text
  "sessionStartMs": 0,                // epoch ms (dataset nights may use an arbitrary origin, e.g. 0 = PSG start)
  "sessionEndMs": 28800000,
  "hr":    [[timeMs, bpm], ...],      // includes the context margin if available
  "steps": [[startMs, endMs, count], ...],
  "spo2":  [[timeMs, percent], ...],  // optional
  "labels": [[startMs, endMs, "LIGHT"], ...]   // optional ground truth, stage names of SleepStage; 30-s PSG epochs ok
}
```

## 2. algo-cli commands

```
algo-cli features  --nights <dir|jsonl> --out <features.csv> [--params <algo-params.json>]
    one row per SESSION epoch: night_id, subject, epoch, start_ms, has_data, label (majority of overlapping
    label intervals, ties → the label covering the epoch midpoint; empty if none), then the hybrid feature
    columns in HybridFeatureExtractor.FEATURE_NAMES order, then the HMM base features
    (hrNorm, hrVar, hrSlope, t, cyclePhase(NaN if unavailable), steps).

algo-cli evaluate  --nights <dir|jsonl> --estimator RULES|HMM|HYBRID [--params <algo-params.json>]
                   [--model <hybrid-model.json> | --fold-models <dir with <subject>.json>]
                   --out <metrics.json> [--per-epoch <predictions.csv>]
    runs SleepStager on every night (with the fold model of the night's subject when --fold-models is given)
    and scores predictions against labels at the resolution of the label intervals (each 30-s PSG epoch is
    compared with the predicted stage covering its midpoint). Output JSON: pooled confusion matrix, kappa,
    accuracy, per-stage precision/recall/F1, per-night kappa, mean/median per-subject kappa, mean absolute
    minutes error for DEEP/REM/AWAKE, number of nights skipped and why.

algo-cli estimate  --night <file> [--estimator ...] [--model ...] --csv <out.csv>
    same CSV as the in-app export.
```

## 3. Hybrid model bundle (`app/src/main/assets/models/hybrid_model.json`)

```json
{
  "schemaVersion": 1,
  "modelId": "walch2019-loso-final",          // free text, shown in UI
  "featureSetVersion": 1,                     // must equal HybridFeatureExtractor.FEATURE_SET_VERSION
  "featureNames": ["..."],                    // must equal HybridFeatureExtractor.FEATURE_NAMES exactly
  "standardization": { "mean": [..], "std": [..] },
  "classOrder": ["AWAKE", "LIGHT", "DEEP", "REM"],
  "classifier": {
    "type": "logreg" | "mlp",
    "layers": [                               // logreg = one softmax layer; mlp = relu, relu, softmax
      { "weights": [[...] /* out x in */], "bias": [...], "activation": "relu" | "softmax" }
    ]
  },
  "classPriors": [pA, pL, pD, pR],            // training class frequencies (posteriors are divided by these)
  "durations": {                              // gamma bout-length distributions, in MINUTES
    "AWAKE": {"shape": k, "scale": theta}, "LIGHT": {...}, "DEEP": {...}, "REM": {...}
  },
  "boutTransitions": [[...4], [...4], [...4], [...4]],   // P(next bout stage | current bout stage), row = from, index order
  "initialStage": [pA, pL, pD, pR],                       // P(first bout stage)
  "timePrior": { "binMinutes": 30, "table": [[pA, pL, pD, pR], ...] },  // P(stage | bin since session start)
  "hmm": {                                                // supervised MLE estimates for the HMM estimator (optional)
    "emissions": { /* EmissionTable JSON as in AlgoParams */ },
    "meanBouts": { /* MeanBouts JSON */ },
    "transitions": { /* TransitionWeights JSON */ }
  },
  "training": { "dataset": "...", "license": "...", "subjects": 31, "nights": 31, "variants": ["..."],
                "createdAt": "ISO-8601", "notes": "..." }
}
```

`HybridModel.fingerprint()` = SHA-256 of the canonical bundle bytes; it is part of the params hash.
