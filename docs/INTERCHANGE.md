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

Optional extensions (all may be absent or null): `"hrv": [[timeMs, rmssdMs], ...]`,
`"respiratoryRate": [[timeMs, breathsPerMin], ...]`, `"forcedAwake": [[startMs, endMs], ...]`,
`"baselineNadirBpm": 54.0`. `subject` defaults to `id`. Times may be integers or floats (epoch ms). Label stages
are `AWAKE`, `LIGHT`, `DEEP`, `REM` or `UNKNOWN` (UNKNOWN intervals are ignored when scoring). A directory is read as
all its `*.night.json` and `*.jsonl` files in name order; night ids must be unique.

## 2. algo-cli commands

Launcher: `./gradlew :algo-cli:installDist` → `algo-cli/build/install/algo-cli/bin/algo-cli` (needs a Java 17+
runtime on `PATH` or `JAVA_HOME`). Exit codes: 0 ok, 2 night not estimated (`estimate`), 64 usage error, 65 malformed
or inconsistent input, 66 missing input, 74 output error. Parameter files (`--params`) hold a possibly partial
AlgoParams JSON object (missing fields = defaults, unknown keys = error). Every decoded parameter set (the base and
each `--param-grid` entry) is checked by `AlgoParamsValidator` (the ranges of the app's settings, e.g. `epochSec` ∈
{30, 60, 120}, `maxBoutMin` 10..600, odd smoothing/Hampel windows, non-negative windows and minima, σ > 0); a
violation is exit 65 with one line naming the file or grid entry and every offending field. Any other invalid input
that :core-algo rejects (IllegalArgumentException / IllegalStateException) is also reported as exit 65 with a
one-line message, never a stack trace.

```
algo-cli features  --nights <dir|jsonl> --out <features.csv> [--params <algo-params.json>]
    one row per SESSION epoch: night_id, subject, epoch, start_ms, has_data, label (majority of overlapping
    label intervals, ties → the label covering the epoch midpoint, else the lowest stage index; empty if none), then
    the hybrid feature columns in HybridFeatureExtractor.FEATURE_NAMES order, then the HMM base features that are
    not already among them: cyclePhase (empty if unavailable), steps. (hrNorm, hrVar, hrSlope and t are hybrid
    columns with identical values, so they are not repeated.) Empty field = NaN.

algo-cli evaluate  --nights <dir|jsonl> [--estimator RULES|HMM|HYBRID] [--params <algo-params.json>]
                   [--param-grid <grid.json>]
                   [--model <hybrid-model.json> | --fold-models <dir with <subject>.json>]
                   --out <metrics.json> [--per-epoch <predictions.csv>]
    runs SleepStager on every night (with the fold model of the night's subject when --fold-models is given)
    and scores predictions against labels at the resolution of the label intervals (each 30-s PSG epoch is
    compared with the predicted stage covering its midpoint). Output JSON: pooled confusion matrix, kappa,
    accuracy, per-stage precision/recall/F1, per-night kappa, mean/median per-subject kappa, mean absolute
    minutes error for DEEP/REM/AWAKE, number of nights skipped and why.

algo-cli estimate  --night <file> [--id <night id>] [--estimator ...] [--model ...] [--params ...] --csv <out.csv>
    same CSV as the in-app export (CsvExporter); --id selects a night when the file holds several.

algo-cli synthesize --out <dir> [--count N] [--seed S] [--subjects K]
    labelled synthetic nights as *.night.json (pipeline smoke tests only, never for choosing parameters).

algo-cli feature-names
    {"algoVersion": 1, "featureSetVersion": 1, "featureNames": [...]} — what a bundle must declare.
```

`--estimator` overrides `estimator` of the base parameters. `--param-grid` is a JSON array of partial AlgoParams
objects ("patches"); each is deep-merged over the base parameters (objects merge key by key, other values replace;
unknown keys are errors) and produces one metrics block, all in one JVM run. Nights are parsed once; preprocessing,
features, hybrid features and classifier outputs are shared by all grid entries with equal `preprocess` and
`features` parameters, so grids over decoder / post-processing parameters are cheap.

Skip reasons: `NO_LABELS` (night has no labels), `NO_FOLD_MODEL` (no `<subject>.json` in --fold-models), and the
NotEstimatedReason names (`NO_HR_DATA`, `LOW_COVERAGE`, and `SESSION_TOO_SHORT` for sessions shorter than
`preprocess.minSessionMin` minutes — that gate is off by default, `minSessionMin` = 0; the app drops naps by its own
source setting before estimating). Without `--model`/`--fold-models` HYBRID falls back to HMM (visible in
`estimatorsUsed` and `warnings`).

Metrics block (the whole output without `--param-grid`; with it `{"grid": [block, ...]}` where each block also has
`index` and the `patch`):

```json
{
  "estimator": "HYBRID", "params": { /* full effective AlgoParams */ },
  "nights": {"total": 31, "evaluated": 30, "skipped": 1, "skipReasons": {"NO_LABELS": 1}},
  "skipped": [{"id": "...", "subject": "...", "reason": "NO_LABELS"}],
  "estimatorsUsed": {"HYBRID": 29, "RULES": 1}, "warnings": {"DATA_GAPS": 3},   // nights per actual estimator / warning
  "pooled": {"labels": ["AWAKE", "LIGHT", "DEEP", "REM"], "confusion": [[...4], ...],   // rows truth, columns prediction
             "kappa": 0.31, "accuracy": 0.62, "macroF1": 0.45, "scoredChunks": 25000, "skippedChunks": 120,
             "perStage": {"AWAKE": {"precision": 0.5, "recall": 0.4, "f1": 0.44, "support": 3000, "predicted": 2400}, ...}},
  "meanNightKappa": 0.29, "medianNightKappa": 0.3,
  "perSubject": {"subjects": 30, "meanKappa": 0.29, "medianKappa": 0.3,
                 "values": [{"subject": "46343", "nights": 1, "kappa": 0.33, "accuracy": 0.6}, ...]},
  "minutesError": {"deep": 21.5, "rem": 30.1, "awake": 18.0},          // mean |estimate − truth| minutes per night
  "perNight": [{"id": "...", "subject": "...", "variant": "...", "estimator": "HYBRID", "kappa": 0.3, "accuracy": 0.6,
                "macroF1": 0.4, "coverage": 0.97, "confidence": 0.55, "scoredChunks": 900,
                "truthMinutes": {"AWAKE": 40.0, "LIGHT": 230.0, "DEEP": 60.0, "REM": 90.0},
                "predictedMinutes": {...}, "minutesError": {"deep": 5.0, "rem": 12.5, "awake": 3.0},
                "warnings": ["DATA_GAPS"]}, ...]
}
```

Undefined values (e.g. the kappa of a night with a single class on both sides) are `null`. Kappas are unweighted
Cohen's kappa over the scored 30-s chunks (UNKNOWN on either side is skipped and counted in `skippedChunks`); the
per-subject kappa pools the subject's nights.

`--per-epoch` CSV: `grid_index,night_id,subject,epoch,start_ms,has_data,label,stage,p_awake,p_light,p_deep,p_rem`, one
row per session epoch, evaluated night and grid entry (label as in `features`).

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

`HybridModel.fingerprint()` = SHA-256 of the canonical bundle bytes (the parsed bundle re-encoded as JSON with every
field, object keys sorted, no whitespace — so formatting and key order do not matter); it is part of the params hash.

Validation (`HybridModel.parse`, HybridModelException on failure): `schemaVersion` 1; `classOrder` exactly
`["AWAKE","LIGHT","DEEP","REM"]` (every probability vector is in this order); distinct `featureNames`;
`standardization` with one finite mean and one positive std per feature; layers chaining from `featureNames.size`
inputs to 4 outputs, `relu` on hidden layers and `softmax` on the last, `logreg` = exactly one layer; finite weights;
`classPriors` positive, `initialStage`, every `boutTransitions` row and every `timePrior.table` row non-negative,
each summing to 1 (±1e-3); gamma `shape`/`scale` positive; `timePrior.binMinutes` positive. Unknown keys are ignored.
A bundle whose `featureSetVersion` or `featureNames` differ from this build's `HybridFeatureExtractor` is valid but
unusable: the estimator falls back to the HMM with `HYBRID_MODEL_UNAVAILABLE`. The `hmm` section is informational
(the HMM always uses `AlgoParams.hmm`; the training pipeline may turn those estimates into the defaults).

Decoding (ALGO_DESIGN.md "Hybrid"): zero bout transitions off the diagonal and zero initial probabilities are replaced
by `hmm.epsilon` (rows renormalized); the diagonal of `boutTransitions` is used as given (0 = a bout is never followed
by a bout of the same stage, so a bout cannot exceed `maxBoutMin`), except that a night with no feasible
segmentation (e.g. a forced-awake span longer than `maxBoutMin`) is decoded again with the diagonal floored at ε.

Conventions shared by training and decoding (they must not drift apart):
- **Bout lengths.** `durations` are gamma fits (minutes) to bout lengths on the epoch lattice, `epochs × epochMin`
  (as `tools/train/pulsetrain/stats.py` does). The decoder therefore gives a bout of d epochs (m minutes each) the
  gamma mass of the midpoint bin `((d − ½)·m, (d + ½)·m]`: `F(1.5·m)` for d = 1, `F((d + ½)·m) − F((d − ½)·m)` for
  1 < d < Dmax and the tail `1 − F((Dmax − ½)·m)` at Dmax = ⌊maxBoutMin / epochMin⌋; survival
  `S(d) = P(len ≥ d) = 1 − F((d − ½)·m)` (1 at d = 1). A fit on `(length − ½)·epochMin` would need ceiling bins instead.
- **Censored bouts.** `initialStage` is P(stage at the first labelled epoch). The first bout (left-censored by the
  session start) is scored with the remaining-length distribution `S(d) / Σ_{d'} S(d')`, which keeps the first
  epoch's state distribution equal to `initialStage` (the raw survival would weight stage j by its mean bout
  length); the last bout (right-censored) with `S(d)`; a single bout covering the whole session with
  `P(R ≥ d)`, R the remaining length. The GEOMETRIC decoder uses `initialStage` as the state distribution of the first
  epoch, i.e. with the same meaning.
- **Feature definitions.** The window lengths (5/15/30/60 min), autocorrelation lags (1–3 epochs, ±15 min), step and
  gap windows (±5 / ±15 min) and caps (180 min, 12 h) of `FEATURE_NAMES` are fixed by `featureSetVersion` 1, not
  AlgoParams (a deliberate deviation from "every number is a parameter": the weights are only valid for exactly
  these features). Changing one means a new feature-set version and a retrained bundle.

## 4. Personal model (`PersonalModel.toJson()`, stored by :data-local in `personal_models.json`)

```json
{
  "schemaVersion": 1,
  "biasDeltas": [dA, dL, dD, dR],          // added to the hybrid classifier's output logits (or null)
  "baseModelFingerprint": "…",              // bundle the deltas were fitted for; ignored with any other bundle
  "hmmMeans": {"awake": {"hrNorm": 2.9, "hrVar": 1.8, "hrSlope": 0.0}, "light": {…}, "deep": {…}, "rem": {…}},  // or null
  "hmmSds":   { /* same shape, σ > 0 */ },  // or null
  "provenance": {"kind": "CALIBRATED" | "EM", "nightsUsed": 8, "kappaBefore": 0.31, "kappaAfter": 0.34,
                 "heldOutNights": 4, "estimator": "HYBRID"}
}
```

Applied by `SleepStager(hybridModel, personalModel)` only when `AlgoParams.personal.enabled`; `fingerprint()` (SHA-256
of the canonical JSON) then enters the params hash. `PersonalCalibrator` (reference nights with true stages) fits
`biasDeltas` and `hmmMeans`; `PopulationEmAdapter` (unsupervised, many nights) fits `hmmMeans` and `hmmSds` and keeps
the deltas of the model it starts from.
