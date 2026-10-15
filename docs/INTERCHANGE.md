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
                   [--nap-proxies <minutes,...>]
    one row per SESSION epoch (with --nap-proxies also the rows of every night's nap proxies, see below):
    night_id, subject, epoch, start_ms, has_data, label (majority of overlapping
    label intervals, ties → the label covering the epoch midpoint, else the lowest stage index; empty if none), then
    the hybrid feature columns in HybridFeatureExtractor.FEATURE_NAMES order, then the HMM base features that are
    not already among them: cyclePhase (empty if unavailable), steps. (hrNorm, hrVar, hrSlope and t are hybrid
    columns with identical values, so they are not repeated.) Empty field = NaN.

algo-cli evaluate  --nights <dir|jsonl> [--estimator RULES|HMM|HYBRID] [--params <algo-params.json>]
                   [--param-grid <grid.json>]
                   [--model <hybrid-model.json> | --fold-models <dir with <subject>.json>]
                   [--nap-proxies <minutes,...>] --out <metrics.json> [--per-epoch <predictions.csv>]
    runs SleepStager on every night (with the fold model of the night's subject when --fold-models is given; a
    bundle with a `nap` section decodes the naps with it, so a fold bundle carries its fold's nap model) and scores
    predictions against labels at the resolution of the label intervals (each 30-s PSG epoch is compared with the
    predicted stage covering its midpoint). Output JSON: pooled confusion matrix, kappa, accuracy, per-stage
    precision/recall/F1, per-night kappa, mean/median per-subject kappa, mean absolute minutes error for
    DEEP/REM/AWAKE, number of nights skipped and why, and all of it split by mode (`byMode`).

algo-cli estimate  --night <file> [--id <night id>] [--estimator ...] [--model ...] [--params ...] --csv <out.csv>
    same CSV as the in-app export (CsvExporter: `epoch,start_ms,has_data,hr_raw,hr_smooth,<features>,p_awake,
    p_light,p_deep,p_rem,stage,mode`, mode = NIGHT or NAP); --id selects a night when the file holds several.

algo-cli synthesize --out <dir> [--count N] [--seed S] [--subjects K]
    labelled synthetic nights as *.night.json (pipeline smoke tests only, never for choosing parameters).

algo-cli feature-names
    {"algoVersion": 1, "featureSetVersion": 1, "featureNames": [...]} — what a bundle must declare.
```

`--estimator` overrides `estimator` and `napMode.estimator` of the base parameters (the estimator of nights and of
naps). `--nap-proxies 30,60,90,120` (features and evaluate) adds, for every night and every length L, the nap proxy
of the night's first L minutes, cut exactly like `tools/train`'s `nap_proxy` (`pulsetrain/naps.py`): session `[start,
start + L)`, heart rate (and SpO2 / HRV / respiratory rate) of `[start − 30 min, start + L]`, step records ending in
`(start − 30 min, start + L]`, forced-awake spans clipped to the session, the label intervals lying fully inside it,
nothing after the end; the same subject (and fold model), variant and baseline; id `<night id>-nap<L>` (`-nap90.5`
for a fractional length); a night shorter than L gives none. Proxies follow the nights in the output, length by
length. `--param-grid` is a JSON array of partial AlgoParams
objects ("patches"); each is deep-merged over the base parameters (objects merge key by key, other values replace;
unknown keys are errors) and produces one metrics block, all in one JVM run. Nights are parsed once; preprocessing,
features, hybrid features and classifier outputs are shared by all grid entries with the same preparation key
(`NightPreparer.preparationKey`: the session's mode, the `preprocess` parameters in effect for that mode, the
`features` parameters and, for a nap, `napMode.contextMarginMin`), so grids over decoder / post-processing parameters
are cheap and a grid over `napMode.contextMarginMin` prepares only the naps again.

**Nap context margin** (`napMode.contextMarginMin`, AlgoParams; `null` by default = the night's
`preprocess.contextMarginMin`). With a value m (whole minutes, 0 ≤ m ≤ `preprocess.contextMarginMin`; a larger value
is refused by the validator, exit 65, because the app reads heart rate and steps with the night's margin and a nap
can never use more context than it receives) a nap is prepared, in `features`, `evaluate` and `estimate` alike and
exactly as in the app, from its input restricted to heart-rate / SpO2 / respiratory-rate / HRV samples with
`start − m ≤ t ≤ end + m` and step records ending in `(start − m, end + m]` (the cut convention of the nap proxies
above), on an epoch grid with m minutes of context; what a night file or a proxy carries beyond that is ignored, so a
nap decodes the same whatever the reader's margin. Nights never read the field: their preparation, features and
decodes are identical for every value.

Skip reasons: `NO_LABELS` (night has no labels), `NO_FOLD_MODEL` (no `<subject>.json` in --fold-models), and the
NotEstimatedReason names (`NO_HR_DATA`, `LOW_COVERAGE`, `SESSION_TOO_SHORT` for sessions shorter than
`preprocess.minSessionMin` minutes — that gate is off by default, `minSessionMin` = 0; the app ignores sessions below
its own source setting before estimating — and `NAP_SKIPPED` for a nap under `napMode.outputPolicy` SKIP). Without
`--model`/`--fold-models` HYBRID falls back to HMM (visible in `estimatorsUsed` and `warnings`).

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
  "byMode": {"NIGHT": {"nights": {...}, "estimatorsUsed": {...}, "warnings": {...}, "pooled": {...},
                       "meanNightKappa": 0.29, "medianNightKappa": 0.3, "perSubject": {...}, "minutesError": {...}},
             "NAP":   { /* the same keys for the naps (sessions shorter than napMode.maxSessionMin) */ }},
  "perNight": [{"id": "...", "subject": "...", "variant": "...", "mode": "NIGHT", "estimator": "HYBRID", "kappa": 0.3, "accuracy": 0.6,
                "macroF1": 0.4, "coverage": 0.97, "confidence": 0.55, "scoredChunks": 900,
                "confusion": [[...4], ...],                                    // this night's 4×4 counts
                "truthMinutes": {"AWAKE": 40.0, "LIGHT": 230.0, "DEEP": 60.0, "REM": 90.0},
                "predictedMinutes": {...}, "minutesError": {"deep": 5.0, "rem": 12.5, "awake": 3.0},
                "warnings": ["DATA_GAPS"]}, ...]
}
```

`skipped` entries carry the `mode` as well. The modes of `byMode` follow `napMode.maxSessionMin` of the block's
parameters; the two pooled matrices add up to `pooled.confusion`. The per-night `confusion` matrices add up to
`pooled.confusion`, so the results of several runs (e.g. one per
cross-validation fold) can be pooled again by the caller. Undefined values (e.g. the kappa of a night with a single
class on both sides) are `null`. Kappas are unweighted
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
                "createdAt": "ISO-8601", "notes": "..." },
  "stageLogBias": [bA, bL, bD, bR],                       // optional per-stage log-bias of the scaled likelihoods
  "stageLogBiasShort": [bA, bL, bD, bR],                  // optional: replaces stageLogBias on naps without a nap model
  "nap": {                                                // optional nap model: decodes naps instead of the main model
    "featureSetVersion": 1, "featureNames": ["..."],      // same rules as the main model
    "standardization": { "mean": [..], "std": [..] },
    "classifier": { "type": "mlp", "layers": [ { "weights": [[...]], "bias": [...], "activation": "relu" } ] },
    "classPriors": [pA, pL, pD, pR],
    "durations": { "AWAKE": {"shape": k, "scale": theta}, "LIGHT": {...}, "DEEP": {...}, "REM": {...} },
    "boutTransitions": [[...4], [...4], [...4], [...4]],
    "initialStage": [pA, pL, pD, pR],
    "timePrior": { "binMinutes": 30, "table": [[pA, pL, pD, pR], ...] },
    "stageLogBias": [bA, bL, bD, bR],                     // optional log-bias of the nap classifier
    "training": { "dataset": "...", "notes": "..." },     // optional provenance (any TrainingInfo fields)
    "stageLogBiasByLength": {                             // optional, instead of stageLogBias: log-bias by session length
      "lengthsMin": [30, 60, 90, 120, 150, 170],          // anchor lengths in minutes, strictly increasing
      "bias": [[bA, bL, bD, bR], ...]                     // one row per anchor
    }
  }
}
```

`HybridModel.fingerprint()` = SHA-256 of the canonical bundle bytes (the parsed bundle re-encoded as JSON with every
field, object keys sorted, no whitespace — so formatting and key order do not matter); it is part of the params hash.
Optional fields added after the first shipped bundles (`stageLogBias`, `stageLogBiasShort`, `nap`, and inside `nap`
the `stageLogBiasByLength` table added after the first nap models) are left out of the canonical JSON when they are
absent, so a bundle without them keeps its fingerprint (and the personal models keyed to it stay valid); the `nap`
section is part of the canonical JSON when present (a bundle with a nap model is a different model), with its other
keys as always (a null nap `stageLogBias` or `training` included).

`stageLogBias` (optional; absent = all 0): per-stage additive log-bias b_s, AWAKE / LIGHT / DEEP / REM, added to the
emission score of every epoch with heart-rate data, `e_k(s) = w_e · (ln p(s | x_k) − β · ln classPriors_s) + b_s`
(w_e = `hybrid.emissionWeight`, β = `hybrid.priorExponent`, ALGORITHM.md section 7). The training scripts fit it
(`tools/train/pulsetrain/bias.py`) so that the decoded stage shares of out-of-fold decodes of the training nights
match the true shares (proportion matching); it belongs to the classifier it corrects, which is why it lives in the
bundle and enters its fingerprint rather than being an AlgoParams value.

`stageLogBiasShort` (optional; absent = naps keep `stageLogBias`): the per-stage log-bias used INSTEAD of
`stageLogBias` on naps — sessions shorter than `napMode.maxSessionMin` minutes (AlgoParams, 180 by default; 0 = no
naps, every session uses `stageLogBias`; session length = `sessionEndMs − sessionStartMs`, strictly shorter) — when the
bundle has no `nap` section. Fitted by the same fixed point on out-of-fold decodes of nap-length sessions cut from the
training nights (`tools/train/pulsetrain/naps.py`, ALGORITHM.md section 13.11): a nap has a different stage mix from a
whole night, so the whole-night b does not match its shares.

`nap` (optional; absent = naps are decoded by the main model with `stageLogBiasShort`, exactly as before the two
processing modes): a complete second model for naps (`NapSubModel`; ALGORITHM.md section 18) with the keys
`featureSetVersion`, `featureNames`, `standardization`, `classifier`, `classPriors`, `durations`, `boutTransitions`,
`initialStage`, `timePrior` (same meaning and rules as the main model's), optional `stageLogBias` (its own log-bias;
`stageLogBiasShort` is then not used) and optional `training` (provenance, any `training` fields). It shares the
bundle's `schemaVersion`, `classOrder` and `modelId`. When present, every nap (`SessionMode.NAP`) is decoded with it:
its classifier and standardization give the posteriors, its class priors scale them, its durations, bout transitions,
initial stage and time prior build the HSMM / HMM, with the nap decoder parameters (`napMode.hybrid` overrides of
`hybrid`); nights always use the main model. Personal classifier deltas (section 4) are fitted for the main classifier
and never apply to the nap model. A nap section whose `featureSetVersion` / `featureNames` differ from this build makes
the naps (only) fall back to the HMM with `HYBRID_MODEL_UNAVAILABLE`. `tools/train/pulsetrain/bundle.py` builds it
(`nap_section(model)` from a bundle trained on nap-length sessions, `with_nap_model(bundle, nap)`); with
`--fold-models` each fold bundle carries its own fold's nap section. The shipped bundle has one: the nap MLP of the
pre-registered nap-model experiment (`tools/train/pulsetrain/napmodel.py`, ALGORITHM.md section 13.12) with its own
`stageLogBias` and a `training` block describing the nap proxies it was trained on.

`nap.stageLogBiasByLength` (optional; absent = the nap's constant `stageLogBias`, or none): a per-stage log-bias that
depends on the session length (`LengthBiasTable`; docs/prereg/prereg-naps-realistic.md). `lengthsMin` are anchor
lengths in minutes, `bias` one row `[bA, bL, bD, bR]` per anchor. A nap of L = (`sessionEndMs` − `sessionStartMs`) /
60 000 minutes gets the row interpolated linearly in L between the two anchors around it, exactly the anchor's row at
an anchor length, and the first / last row below the first / above the last anchor; that row is the nap's b in the
emission score. A nap section has at most one of `stageLogBias` and `stageLogBiasByLength`. Nights never read it (it
is not part of the night-model fingerprint, section 4).

Validation (`HybridModel.parse`, HybridModelException on failure): `schemaVersion` 1; `classOrder` exactly
`["AWAKE","LIGHT","DEEP","REM"]` (every probability vector is in this order); distinct `featureNames`;
`standardization` with one finite mean and one positive std per feature; layers chaining from `featureNames.size`
inputs to 4 outputs, `relu` on hidden layers and `softmax` on the last, `logreg` = exactly one layer; finite weights;
`classPriors` positive, `initialStage`, every `boutTransitions` row and every `timePrior.table` row non-negative,
each summing to 1 (±1e-3); gamma `shape`/`scale` positive; `timePrior.binMinutes` positive; `stageLogBias` and
`stageLogBiasShort`, when present, exactly 4 finite numbers each; a `nap` section passes every check of the main model
(messages prefixed `nap.`), and its `stageLogBiasByLength`, when present, has at least one anchor, finite, positive and
strictly increasing `lengthsMin`, one row of exactly 4 finite numbers per anchor, and no `stageLogBias` beside it.
Unknown keys are ignored.
A bundle whose `featureSetVersion` or `featureNames` differ from this build's `HybridFeatureExtractor` is valid but
unusable: the estimator falls back to the HMM with `HYBRID_MODEL_UNAVAILABLE`. The `hmm` section is informational
(the HMM always uses `AlgoParams.hmm`; the training pipeline may turn those estimates into the defaults).

Decoding ([ALGORITHM.md §7](../ALGORITHM.md#7-hybrid-estimator-hybrid)): zero bout transitions off the diagonal and zero initial probabilities are replaced
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

## 4. Personal model (`PersonalModel.toJson()`, stored by :data-local in the Room table `personal_models`)

```json
{
  "schemaVersion": 1,
  "biasDeltas": [dA, dL, dD, dR],          // added to the hybrid classifier's output logits (or null)
  "baseModelFingerprint": "…",              // night model the deltas were fitted for (see below); ignored with any other
  "hmmMeans": {"awake": {"hrNorm": 2.9, "hrVar": 1.8, "hrSlope": 0.0}, "light": {…}, "deep": {…}, "rem": {…}},  // or null
  "hmmSds":   { /* same shape, σ > 0 */ },  // or null
  "provenance": {"kind": "CALIBRATED" | "EM", "nightsUsed": 8, "kappaBefore": 0.31, "kappaAfter": 0.34,
                 "heldOutNights": 4, "estimator": "HYBRID"}
}
```

Applied by `SleepStager(hybridModel, personalModel)` only when `AlgoParams.personal.enabled`; `fingerprint()` (SHA-256
of the canonical JSON) then enters the params hash. `PersonalCalibrator` (reference nights with true stages) fits
`biasDeltas` and `hmmMeans`; `PopulationEmAdapter` (unsupervised, many nights) fits `hmmMeans` and `hmmSds` and keeps
the deltas of the model it starts from. `baseModelFingerprint` is `HybridModel.nightModelFingerprint()` of the bundle
the deltas were fitted with: the SHA-256 of its canonical JSON without `modelId`, `training`, `stageLogBiasShort` and
`nap`, i.e. of the parts that decode a night, so a bundle that differs only in its nap model, its short-session bias or
its metadata keeps the deltas. A model written before this key existed holds the bundle's whole `fingerprint()`, which
still matches that bundle.

## 5. Night / nap modes in the app's output (`napMode.outputPolicy`)

What the app writes to Health Connect for a nap (`SessionMode.NAP`, a session shorter than `napMode.maxSessionMin`;
nights always get STAGES), the same `SleepSessionRecord` metadata, title and notes either way:

| policy | written stages | ledger row |
|---|---|---|
| `STAGES` (default) | AWAKE → `STAGE_TYPE_AWAKE` (1), LIGHT → `STAGE_TYPE_LIGHT` (4), DEEP → `STAGE_TYPE_DEEP` (5), REM → `STAGE_TYPE_REM` (6), UNKNOWN (data gaps) → `STAGE_TYPE_UNKNOWN` (0), like a night | WRITTEN, `mode` NAP |
| `SLEEP_WAKE` | AWAKE → `STAGE_TYPE_AWAKE` (1); LIGHT, DEEP, REM and UNKNOWN → `STAGE_TYPE_SLEEPING` (2) (the source session already says the whole span was sleep); adjacent equal types merged; with `awakeAsLight` AWAKE is written as SLEEPING too | WRITTEN, `mode` NAP, `sleepWakeOnly` = true (the row keeps the estimated stages and minutes) |
| `SKIP` | nothing; an output written for the nap earlier is deleted when the nap is processed again | NOT_ESTIMATED, reason `NAP_SKIPPED: …`, `mode` NAP |

Under `SKIP` a nap writes nothing, so it replaces nothing that another source wrote:

- **Its own earlier records are deleted** when it is processed again: its clientRecordId (also when only Health Connect
  knows it, e.g. after a reinstall) and any record of ours whose notes list one of its source uuids.
- **Nights of the same sleep are superseded**: those sharing a source session with it (the sleep cut differently, e.g. a
  night that became this nap when a fragment was deleted; its remaining fragments are linked to the nap) and
  overlapping nights of its own package.
- **Another source's night is never removed or blocked by it.** An overlapping night of a lower-priority source keeps its
  output, and the source-priority rule does not make that night a duplicate of the skipped nap: it is estimated as if
  the nap were not there. Under `STAGES` and `SLEEP_WAKE` the higher-priority nap replaces it as usual.
- **No retries, but stale.** A skipped nap is not retried for late heart rate (nothing it could read changes the
  outcome); when the settings change it counts as stale, so after "Written for a nap" is switched back, "Recompute
  stale" writes it.

The notes JSON of every written session has `"mode": "NIGHT"` or `"NAP"` after `"algo"`:
`{"algo":"HYBRID","mode":"NAP","version":1,"paramsHash":"…",…}`.

