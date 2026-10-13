# PulseStages — sleep-stage estimation algorithm

This document describes how PulseStages estimates sleep stages from heart rate, with the formulas of the code that
runs on the phone (`:core-algo`, `ALGO_VERSION` = 1), every parameter and its default, the validation protocol on open
datasets and all validation numbers. Interchange formats between Kotlin and the training scripts are in
[docs/INTERCHANGE.md](docs/INTERCHANGE.md); dataset details in [tools/train/DATASETS.md](tools/train/DATASETS.md);
synthetic regression results in [core-algo/SYNTHETIC_RESULTS.md](core-algo/SYNTHETIC_RESULTS.md).

**Summary.** Default estimator **HYBRID** — a 32 × 32 MLP on 40 multi-scale heart-rate / step / time features, trained
on Walch (PSG) + BIDSleep, whose posteriors are decoded by an explicit-duration HSMM with gamma bout lengths
(`durationModel` EXPLICIT; the plain-HMM GEOMETRIC decoder is a setting, and the nested CV could not separate the
two), plus a per-stage log-bias stored in the model bundle (**proportion matching**, section 7) that brings the
decoded stage shares to the PSG shares. Leave-one-subject-out on the 31 Walch PSG nights with OHealth-like 2-minute
heart rate (worse of the instantaneous / 2-min-mean emulations, here mean2min): **Cohen's κ = 0.339 ± 0.169** (mean ±
sample sd over subjects; instantaneous 0.348; pooled 0.350, accuracy 0.612, macro-F1 0.522; mean absolute error per
night DEEP 33 min, REM 41 min, AWAKE 19 min, LIGHT 53 min; predicted shares DEEP 13–15 % / REM 20–24 % / LIGHT 57–59 %
vs PSG 14 / 23 / 57 %), versus 0.070 for the HMM (the specification's physiology table, which stays the HMM default)
and 0.076 for the rules. Without proportion matching (`loso-v2`) the same classifier scored κ 0.334 but predicted
25 % DEEP / 34 % REM / 34 % LIGHT with per-night errors of 52 / 54 / 18 / 95 min (DEEP / REM / AWAKE / LIGHT); the
pre-registered experiment of section 13.10 adopted the correction (summed per-night error 147 instead of 219 min;
κ 0.339 vs 0.334 is within noise, Δκ +0.003 [−0.037, 0.038] on mean2min, and b costs 0.03 κ on the BIDSleep headband
nights). Over four training seeds the uncorrected setting averages κ 0.317 ± 0.019, the conservative figure. The
output is an estimate, not a measurement.

Contents: 1 Estimate, not measurement · 2 Pipeline · 3 Preprocessing · 4 Features · 5 RULES · 6 HMM · 7 HYBRID · 8
Post-processing · 9 Gates and confidence · 10 Personalization · 11 Parameters · 12 Validation protocol · 13 Results ·
14 Published results · 15 Synthetic validation · 16 Limitations · 17 How to retrain

## 1. What the app computes, and why it is an estimate

PulseStages estimates a four-stage hypnogram (AWAKE / LIGHT / DEEP / REM) for a sleep session that a watch app wrote
to Health Connect without stages. The only inputs are heart rate about once every two minutes (OPPO Watch X3 via
OHealth), step counts and, when present, SpO2, RMSSD and respiratory rate. Sleep stages are *defined* by EEG, EOG and
EMG (polysomnography, AASM rules). None of these signals is available here, and a two-minute heart-rate series carries
only indirect, slow information about them: deep sleep tends to have the lowest and steadiest heart rate, REM and wake
a higher and more variable one, and stages follow a typical ultradian timing. The output is therefore a statistical
**estimate**, not a measurement:

- every record the app writes is labelled as an estimate (default title "Sleep stages from heart rate (estimate)"), carries
  `algoVersion` and the `paramsHash` of the exact parameters / model / personal model in its `notes`, and uses
  `recordingMethod = UNKNOWN` (no sensor measured the stages);
- the validation below quantifies how often the estimate agrees with PSG: Cohen's kappa ≈ 0.34 for the default
  estimator ("fair" agreement, 61–62 % of 30-s epochs correct), with mean errors of 18–41 minutes per night for the
  AWAKE, DEEP and REM totals (38–53 for LIGHT) and large differences between people;
- any night can be recomputed later with a better algorithm, because the source data are never modified and the params
  hash identifies what produced a record.

All code paths below are implemented once, in Kotlin (`:core-algo`, package `io.github.alien2003.pulsestages.algo`),
and the validation numbers are produced by exactly that code through `:algo-cli`. Python (`tools/train`) only prepares
the datasets, fits the classifier and the duration / transition statistics, and aggregates the metrics.

## 2. Pipeline overview

```
Health Connect night ──► preprocessing ──► night statistics ──► base features ──┬─► RULES ───────────────┐
 (HR, steps, SpO2,        (range, Hampel,     (P10, P50, nadir,    (hrNorm, hrVar,  ├─► HMM (Viterbi + FB) ──┤
  HRV, resp. rate,         resampling,         elevated night)      hrSlope, t,     └─► HYBRID features ──┐  │
  session ± 30 min)        smoothing)                               cycle, steps)       classifier → HSMM ┴──┴─► post-processing
                                                                                                                (bouts, gaps,
                                                                                                                 sanity) ──► hypnogram
```

`SleepStager.run(input, params)` applies the coverage gates (section 9), prepares the night once (`NightPreparer`:
preprocessing, statistics, base features), runs the configured estimator and the shared post-processing, and returns a
`NightEstimate` (intervals covering the session exactly, per-epoch posteriors and features, coverage, confidence,
warnings, `algoVersion` = 1, `paramsHash`).

## 3. Preprocessing (`preprocess/`)

Epoch grid: epoch length E = `epochSec` = 60 s. Session epoch k (k = 0 … N−1, N = ⌈(end − start)/E⌉) covers [start +
kE, start + (k+1)E); the last epoch may be partial and is clipped to the session end. C = ⌈30 min / E⌉ context epochs
on each side (`contextMarginMin` = 30) feed smoothing, windows and the cycle search but are never decoded or written.

1. **Range filter**: heart-rate samples outside [`minBpm`, `maxBpm`] = [30, 180] bpm or outside the grid window are
   dropped; samples are sorted and duplicate timestamps collapsed to their mean.
2. **Hampel filter** on the sample sequence: for each sample x_i, the centred window of `hampelWindow` = 5 samples
   (truncated at the ends) gives the median m_i and MAD_i; if |x_i − m_i| > `hampelThreshold` · max(1.4826 · MAD_i,
   `hampelMinScaleBpm`) with threshold 3 and floor 1 bpm, x_i is replaced by m_i. (The 1-bpm floor keeps the classic
   filter from "replacing" every 1-bpm deviation of integer-bpm data when MAD = 0.)
3. **Resampling**: the epoch value is the mean of the samples inside the epoch. An epoch without samples gets the
   linear interpolation at its midpoint between the nearest samples before and after, if these are at most
   `maxInterpGapMin` = 10 min apart; otherwise it has **no data** (NaN). Epochs before the first / after the last
   sample have no data. The result is `hrRaw`.
4. **Smoothing**: `hrSmooth` = centred moving average (`smoothWindowEpochs` = 5) of the centred running median
   (`medianWindowEpochs` = 3) of `hrRaw`; both windows are clipped to the contiguous run of epochs with data, so
   nothing is smoothed across a gap. `hrRaw` is kept for the variability features.
5. **Coverage** = share of session epochs with data. With 2-minute sampling every epoch between two samples is
   interpolated, so coverage is ≈ 1 unless the watch stopped recording for more than 10 minutes.

## 4. Night statistics and base features (`features/`)

Night statistics over the session epochs with data (`NightStatistics`): P10 and P50 of `hrSmooth` (linear-
interpolation percentiles, `normLowPercentile` = 10, `normHighPercentile` = 50), range = P50 − P10, nadir = min
`hrSmooth`. If range < `minNormRangeBpm` = 2 bpm the night gets `LOW_HR_RANGE`, is flagged low-confidence and the
denominator is 2 bpm.

Per-epoch base features (session and context epochs; NaN where undefined):

| feature | definition |
|---|---|
| hrNorm | (hrSmooth − P10) / max(P50 − P10, 2 bpm) — the night is normalized to itself: P10 ≈ the nadir, P50 ≈ light sleep |
| hrVar | population std of `hrRaw` over the window ±`varHalfWindowEpochs` = ±3 epochs (≥ 3 values), divided by the median of that std over the session (1 if the median is 0 / undefined): a coarse stand-in for HRV |
| hrSlope | least-squares slope of `hrSmooth` over ±`slopeHalfWindowEpochs` = ±2 epochs (≥ 3 values), bpm/min |
| t | (k + ½)/N clipped to [0, 1], the elapsed fraction of the session |
| cyclePhase | ultradian phase: local minima of `hrSmooth` found like `scipy.signal.find_peaks(−hrSmooth, distance = ⌈cycleMinDistanceMin / E⌉, prominence ≥ cycleMinProminenceBpm)` (60 min, 2 bpm; interior NaN gaps linearly filled for the search only). With ≥ 2 minima m_1 < … < m_M: φ = (i − m_j)/(m_{j+1} − m_j) between consecutive minima, 0 at the last minimum, undefined outside; fewer than two minima → `CYCLE_PRIOR_DISABLED` |
| steps | steps per epoch; each step record's count is spread uniformly over its interval |
| forcedAwake | epoch midpoint inside an externally forced wake span (merged-fragment gaps) |

Optional channels, used only when data are present and the flag is on (`useSpo2`, `useHrv`, `useRespiratoryRate`):
SpO2 drop = rolling `spo2MedianWindowMin` = 60-min median − epoch SpO2 (a drop ≥ `spo2DropPct` = 3 points multiplies
the AWAKE and LIGHT likelihoods by `spo2AwakeLightFactor` = 2); HRV v = median_night(RMSSD) / RMSSD of the nearest
sample within `hrvMaxDistanceMin` = 5 min, which replaces hrVar in the HMM emission with σ / `hrvWeight` (1.5);
respiratory-rate variability = std of the per-epoch rate over ±`respiratoryHalfWindowEpochs` = ±5 epochs / its median,
added to the HMM emission as `respiratoryRateWeight` (0.5) · log N(v; hrVar Gaussian of the state). OHealth currently
writes none of these at night, so they are inactive in the validation.

**Elevated-HR night (long-term context).** The app keeps the nadir of every estimated night in Room; the baseline of a
night is the median nadir of the previous `baselineNights` = 14 nights (at least 3, otherwise no baseline). If nadir >
baseline · (1 + `elevatedNightPct`/100), `elevatedNightPct` = 12, the night is `ELEVATED_HR` (illness, alcohol, heat):
the AWAKE threshold rises by `elevatedAwakeShift` = 0.3 hrNorm (RULES threshold, HMM μ_AWAKE,hrNorm), the HYBRID AWAKE
likelihood is multiplied by `elevatedAwakeFactor` = 0.6 (note: < 1 *lowers* AWAKE, compensating for the generally
higher heart rate) and the night confidence by `elevatedConfidenceFactor` = 0.8. The open datasets have one night per
subject (Walch) or no long-term context, so this rule is not exercised by the validation.

**Naps and the baseline** (`baselineMinSessionMin` = 180 min, session length = end − start of the merged session,
gaps included). A session shorter than that is a nap for the long-term context: its nadir does not enter the baseline
(the app still stores it, and the baseline query of `NightBaselineDao` counts only rows whose `estimated_nights`
session is long enough, so a changed threshold applies to the stored history without a recompute), and it gets no
baseline itself, so it is never `ELEVATED_HR`: the app passes `baselineNadirBpm = null` for it (`NightInputReader`,
used by the pipeline, the night detail, the reference comparison and personalization), and `NightPreparer` ignores a
baseline given for a nap anyway. Reason: when naps are estimated (source setting `minSessionMin` lowered, e.g. to 30)
a daytime nap's nadir is several bpm above a night's; counting it would raise the median (fewer correct flags on
real nights), and comparing the nap with a nights' baseline would flag almost every nap. `baselineMinSessionMin` = 0
restores the rule without naps (every session counts and is compared). A short real night (e.g. 2.5 h after a night
shift) is treated as a nap too; lower the threshold if such nights matter more than daytime naps.

**Steps.** An epoch with ≥ `awakeStepThreshold` = 5 steps is forced to AWAKE for every estimator (all other states get
probability 0); 1 … 4 whole steps multiply the AWAKE likelihood by `fewStepsAwakeFactor` = 3 (fractions of a step left
by spreading a long record do not count).

## 5. RULES estimator (`rules/RulesEstimator`)

Per session epoch, in this order: AWAKE if forced or ≥ 1 whole step; LIGHT if the epoch has no heart-rate data (before
the gap policy); AWAKE if hrNorm > `awakeMinHrNorm` = 2.2 (+ 0.3 on elevated nights); DEEP if hrNorm < `deepMaxHrNorm`
= 0.45 and hrVar < `deepMaxHrVar` = 0.8; REM if hrNorm > `remMinHrNorm` = 1.3 and hrVar > `remMinHrVar` = 1.1 and t >
`remMinT` = 0.25; otherwise LIGHT. Pseudo-posteriors: `pseudoPosterior` = 0.85 for the chosen stage, 0.05 for each
other (they only drive the post-processing and the confidence). RULES is used for comparison and automatically for
nights with 0.4 ≤ coverage < 0.6.

## 6. HMM estimator (`hmm/`)

Hidden states AWAKE, LIGHT, DEEP, REM on 1-minute epochs.

**Emissions.** Diagonal Gaussians over x = (hrNorm, hrVar, hrSlope): log b_s(x) = Σ_d log N(x_d; μ_{s,d}, σ_{s,d})
over the available dimensions, 0 for every state on epochs without heart-rate data (uninformative). The
specification's physiology table (`EmissionTable.PHYSIOLOGY`, part of `HmmParams.PHYSIOLOGY`), which is also the
shipped default — the supervised estimates of section 13 scored higher but failed the validity gate (section 13.2):

| state | hrNorm μ / σ | hrVar μ / σ | hrSlope μ / σ |
|---|---|---|---|
| DEEP | 0.1 / 0.35 | 0.6 / 0.3 | 0 / 0.3 |
| LIGHT | 1.0 / 0.45 | 0.9 / 0.4 | 0 / 0.4 |
| REM | 1.7 / 0.55 | 1.4 / 0.5 | 0 / 0.5 |
| AWAKE | 2.8 / 0.9 | 1.8 / 0.7 | 0 / 0.8 |

Added to the log emission: the cycle prior w · cos(2π(φ − c_s)) for DEEP (c = `cycleDeepPhase` = 0) and REM (c =
`cycleRemPhase` = 0.7) where φ is defined, w = `cyclePriorWeight` = 0.5; the step / SpO2 log factors (section 4); −∞
for all states but AWAKE on forced epochs. On elevated nights μ_AWAKE,hrNorm += 0.3.

**Transitions** (into epoch k, minute-epochs): self-transition p_ss = clamp(1 − E_min / `meanBout`_s, 0.5, 0.9999);
the remainder 1 − p_ss is shared by the normalized `TransitionWeights` (non-positive weights → ε = `epsilon` = 1e-4,
then renormalized). Specification values (`HmmParams.PHYSIOLOGY`, also the shipped defaults): mean bouts AWAKE 5,
LIGHT 20, DEEP 20, REM 20 min; AWAKE → LIGHT 0.9, DEEP 0, REM 0.1; LIGHT → DEEP 0.45, REM 0.35, AWAKE 0.2; DEEP →
LIGHT 0.85, AWAKE 0.13, REM 0.02; REM → LIGHT 0.7, AWAKE 0.3, DEEP 0. Time-dependent priors in the
LIGHT row (t = (k + ½)/N): LIGHT→DEEP share × max(`deepTimeFloor`, `deepTimeIntercept` − `deepTimeSlope` · t) =
max(0.3, 1.6 − 1.2t), LIGHT→REM share × (`remTimeIntercept` + `remTimeSlope` · t) = 0.4 + 1.2t, then the off-diagonal
of the row is renormalized to the same remainder (the self-transition is unchanged). **REM lockout**: while k · E <
`remLockoutMin` = 45 min, every transition into REM (and the initial REM probability) is multiplied by
`remLockoutFactor` = 0.05 and the freed mass goes to the other targets proportionally. **Initial distribution**: AWAKE
`initialAwake` = 0.5, LIGHT `initialLight` = 0.5, DEEP and REM ε, normalized. (The time-dependent priors and the
initial distribution are grouped as `HmmTimePrior`; `TrainedDefaults.HMM_TIME_PRIOR` is the specification's
`HmmTimePrior()`.)

**Decoding**: Viterbi in log space for the path; forward–backward (log-sum-exp) for the per-epoch posteriors γ_k(s).

**Per-night EM adaptation** (`emAdapt` = true): `emIterations` = 3 Baum–Welch iterations on the night's epochs with
data, updating only μ and σ with MAP shrinkage towards the starting table (τ = `emPriorStrength` = 20 epoch
equivalents):

```text
μ'  = (Σ_k γ_k(s) x_k + τ μ_0) / (Σ_k γ_k(s) + τ)
σ'² = (Σ_k γ_k(s) (x_k − μ')² + τ (σ_0² + (μ_0 − μ')²)) / (Σ_k γ_k(s) + τ),   σ' ≥ 0.05
```

After each iteration μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE (hrNorm) must hold; otherwise the night falls back to the
starting table with the warning `EM_ORDER_VIOLATION`. Transitions are never adapted.

## 7. HYBRID estimator (`hybrid/`)

A trained discriminative classifier gives p(stage | features) per epoch; dividing by the class priors turns the
posteriors into scaled likelihoods for an explicit-duration hidden semi-Markov model (the "hybrid HMM" approach).

**Features** (`HybridFeatureExtractor`, `FEATURE_SET_VERSION` = 1, 40 columns, session epochs; windows centred, over
session + context, non-NaN values only):

| group | features |
|---|---|
| base | hrNorm, hrVar, hrSlope, t; cycleSin = sin 2πφ, cycleCos = cos 2πφ, cycleAvailable (1 where φ is defined); stepsLog1p = ln(1 + steps) |
| relative HR | hrRelSmooth = hrSmooth − nadir, hrRelRaw = hrRaw − nadir |
| multi-scale, W ∈ {5, 15, 30, 60} min | relMeanW, relStdW (≥ 3 values), relMinW, relMaxW of hrRaw − nadir over ±⌊W/2⌋ min; slopeW = least-squares slope of hrSmooth (bpm/min) — 20 features |
| autocorrelation | acfLag1..3: Pearson correlation of hrRaw at lags 1–3 epochs over the pairs inside ±15 min (≥ 3 pairs) |
| extrema | minToHrMin, minToHrMax: minutes to the nearest local minimum / maximum of hrSmooth (find_peaks with the cycle distance and prominence), capped at 180 |
| movement / gaps | stepsWin5Log1p = ln(1 + steps in ±5 min); noDataFrac15 = share of epochs without HR in ±15 min; hasData |
| time | hoursSinceStart, hoursToEnd (epoch midpoint, capped at 12 h) |

The window lengths, lags and caps are fixed by the feature-set version (not AlgoParams): the trained weights are valid
only for exactly these definitions.

**Classifier** (weights from `assets/models/hybrid_model.json`, pure-Kotlin inference): z_j = (x_j − mean_j)/std_j
with the training statistics stored in the bundle (NaN → 0, the training mean); multinomial logistic regression p =
softmax(W z + b), or an MLP p = softmax(W₃ relu(W₂ relu(W₁ z + b₁) + b₂) + b₃) with 32 × 32 hidden units.

**Emission scores** per epoch k and stage s:

```text
e_k(s) = w_e · (log p(s | x_k) − β · log π_s) + b_s   (epochs with HR data; 0 otherwise)
       + w_t · (log max(P(s | bin_k), ε) − log π_s)   (time-of-night prior)
       + log-factors (1–4 steps: ln 3 for AWAKE; elevated night: ln 0.6 for AWAKE; SpO2: ln 2 for AWAKE and LIGHT)
```

with π the training class frequencies (`classPriors`), w_e = `hybrid.emissionWeight`, β = `hybrid.priorExponent` (the
scaled likelihood is p(s | x) / π_s^β; β = 1 is the classic hybrid HMM and the default), b = the bundle's optional
per-stage log-bias `stageLogBias` (absent = 0), w_t = `hybrid.timePriorWeight`, bin_k = ⌊(k + ½) · E_min / 30⌋ (bins
past the end of the table use the last row); forced epochs get −∞ for every state but AWAKE. P(s | 30-min bin) is a
table estimated on the training nights (not a linear function of t).

**Proportion matching** (`stageLogBias`). Dividing the posteriors by π favours the rare stages: without b the decoder
predicts 25 % DEEP and 34 % REM on Walch where PSG has 14 % and 23 % (section 13.10). b is a per-stage constant added
to the emission score of every epoch with heart-rate data, fitted by the training scripts (`tools/train/pulsetrain/
bias.py`) so that the decoded stage shares of out-of-fold decodes match the true PSG shares: deterministic fixed point
b ← b + η · ln(T_s / P_s(b)) (T = true, P = decoded shares over the scored 30-s chunks of the fit nights, b centred, η
= 1 halved whenever a step does not reduce Σ_s |T_s − P_s|, at most 8 decodes through `algo-cli`). The shipped bundle
carries **b = (+0.090, +0.216, −0.318, +0.012)** for AWAKE / LIGHT / DEEP / REM (fitted on the LOSO out-of-fold
decodes of all 31 Walch subjects, both 2-minute variants, at the shipped decoder parameters; their decoded shares 6.2
/ 56.7 / 14.4 / 22.7 % vs PSG 6.3 / 56.9 / 14.2 / 22.6 %): relative to LIGHT, the per-epoch DEEP likelihood is scaled
by e^(−0.534) ≈ 0.59 and the REM likelihood by e^(−0.204) ≈ 0.82. b lives in the bundle, not in AlgoParams, because it
is a statistic of the classifier it corrects (like `classPriors`); the app persists the full AlgoParams, so an
AlgoParams vector would outlive its bundle. A bundle without `stageLogBias` keeps its earlier fingerprint. On epochs
with HR data −w_e · β · log π_s is itself a per-stage constant, so changing β by d equals adding −w_e · d · log π_s to
b (unit-tested, `HybridEmissionsTest`): a fitted b absorbs any β, and β stays 1.

**Explicit-duration decoder** (`durationModel` = EXPLICIT, HSMM). A path is a sequence of bouts; a bout of stage j
lasting d epochs (1 ≤ d ≤ D, D = ⌊`maxBoutMin` / E_min⌋ = 120) scores log P_j(d) + Σ e_k(j); consecutive bouts i → j
score log A_ij (the bundle's `boutTransitions`, off-diagonal zeros → ε, rows renormalized; the diagonal is used as
given — trained from bouts longer than 120 min only — so a bout cannot exceed D unless the matrix lists a
self-transition); the first bout scores log `initialStage`_j (zeros → ε). Bout lengths are gamma distributions (shape
k_j, scale θ_j, minutes) discretized on the epoch lattice with midpoint bins (F = gamma CDF, m = E_min):

```text
P_j(1) = F(1.5 m),   P_j(d) = F((d + ½) m) − F((d − ½) m) for 1 < d < D,   P_j(D) = 1 − F((D − ½) m)
S_j(d) = P(len ≥ d) = 1 − F((d − ½) m) for d > 1,   S_j(1) = 1          (survival)
```

Censoring: the first bout (already under way at the session start) uses the remaining-length distribution S_j(d) /
Σ_{d'} S_j(d') (this keeps the first epoch's marginal equal to `initialStage`); the last bout (cut by the session end)
uses S_j(d); a single bout covering the whole night uses P(R ≥ d) with R the remaining length. Viterbi over segments
with prefix sums of e (O(N·S·D + N·S²)) gives the path; explicit-duration forward–backward in log space gives the
per-epoch posteriors. If no segmentation is feasible (a forced-awake span longer than D) the night is decoded again
with the diagonal floored at ε.

**Geometric decoder** (`durationModel` = GEOMETRIC): a plain HMM with self-transition clamp(1 − E_min/(k_j θ_j), ε,
0.9999), off-diagonal shares ∝ the bundle's bout transitions without the diagonal (zeros → ε), the same initial
distribution and emission scores; Viterbi + forward–backward.

Shipped decoder defaults (chosen by nested cross-validation, section 13.2): EXPLICIT, w_e = 0.5, w_t = 0, β = 1
(section 13.10), with the bundle's b above. An incompatible or missing bundle (`featureSetVersion` or `featureNames`
differ from this build) is never used: the night falls back to the HMM with the warning `HYBRID_MODEL_UNAVAILABLE`.

**Training statistics stored in the bundle** (`tools/train/pulsetrain/stats.py`, fitted on the training subjects
only): gamma MLE (location 0) on the *uncensored* bout lengths of each stage (first / last bout of a labelled segment
are censored; censored bouts are added only if < 5 uncensored ones exist; method-of-moments fallback); bout-transition
counts between consecutive bouts inside labelled segments, a bout of L > 120 min counting as ⌈L/120⌉ pieces (⌈L/120⌉ −
1 self-transitions, the only diagonal mass), + 0.5 pseudo-count off the diagonal; `initialStage` = stage of the first
labelled epoch of each night + 0.5 pseudo-count; P(stage | 30-min bin) with Laplace α = 1, bins after the last bin
with ≥ 300 labelled epochs merged into it; `classPriors` = class frequencies of the classifier's training rows;
`stageLogBias` = the proportion-matching fixed point above (out-of-fold decodes of the Walch PSG nights only, never of
the night being evaluated in a validation).

## 8. Post-processing (`post/`, shared by all estimators)

In this order: (1) **low-confidence policy**: epochs whose max posterior < `lowConfidenceThreshold` = 0.45 keep the
decoder's label (`lowConfidencePolicy` = KEEP, default) or become LIGHT (LIGHT); forced epochs are exempt. (2) **Gap
policy** for epochs without heart-rate data (gaps > `maxInterpGapMin`): `gapPolicy` = UNKNOWN (default, nothing is
claimed there), LIGHT, or NEAREST (label of the nearest epoch with data, earlier on ties). (3) **Minimum bouts**:
repeatedly the shortest bout below its minimum — DEEP `minDeepMin` = 5, REM `minRemMin` = 5, AWAKE `minAwakeMin` = 2
min (AWAKE only inside sleep, i.e. with sleep epochs before and after it; LIGHT and UNKNOWN have no minimum) — is
relabelled to the neighbouring bout whose stage has the larger mean posterior over the short bout (the only scored
neighbour at an edge; UNKNOWN gaps never absorb; bouts with forced epochs are never absorbed). A final partial epoch
takes the label of the epoch before it. (4) **Sanity check** (coverage > `sanityMinCoverage` = 0.8 and a session of at
least `proportionsMinSessionMin` = 180 min): DEEP > `sanityDeepMax` = 35 % (`SANITY_DEEP_HIGH`), REM > `sanityRemMax` =
40 % (`SANITY_REM_HIGH`) or DEEP < `sanityDeepMin` = 3 % (`SANITY_DEEP_LOW`) of scored time → low-confidence,
confidence × `sanityConfidenceFactor` = 0.7. (5) **Proportion calibration** (`calibrateProportions` = false by default;
sessions of at least `proportionsMinSessionMin` only): per-stage log-prior offsets start at 0; up to
`calibrationMaxIterations` = 5 times, DEEP is raised / lowered by `calibrationStep` = 0.2 while outside [10 %, 25 %],
REM likewise for [15 %, 30 %], AWAKE lowered while > 15 %; the night is re-decoded with the offsets and steps 1–4
re-run (`PROPORTIONS_CALIBRATED`, `flags.calibrated`; RULES shifts its thresholds by 0.25 per unit offset instead).
(6) **Merging** into intervals on the epoch grid; the last interval ends exactly at the session end, so the stages
cover the session without gaps or overlaps and each is at least one epoch long.

**Naps skip steps 4 and 5.** Both compare the stage shares with whole-night ranges. A 30–90-min nap often has no
DEEP or no REM at all (or mostly DEEP after sleep loss), so on sessions shorter than `proportionsMinSessionMin` = 180
min the sanity check would only add misleading `SANITY_*` low-confidence flags, and the calibration would push the nap
towards a night's 10–25 % DEEP / 15–30 % REM. Shorter sessions are therefore neither checked nor calibrated (0 = every
session, as before); the other steps are unchanged.

## 9. Coverage gates, fallbacks and confidence (`SleepStager`)

Session shorter than `minSessionMin` (default 0 = off; the app drops naps by its own source setting, 90 min by
default and as low as 10 min when naps are wanted) → not estimated (`SESSION_TOO_SHORT`); no heart-rate sample in range in the session ± 30 min → `NO_HR_DATA`; coverage ≥
`minCoverage` = 0.6 → the configured estimator; `rulesMinCoverage` = 0.4 ≤ coverage < 0.6 → RULES with
`RULES_FALLBACK_LOW_COVERAGE`; coverage < 0.4 → not estimated (`LOW_COVERAGE`), reason logged.

**Confidence**: epochConfidence = max_s γ_k(s); nightConfidence = mean epochConfidence over the session epochs with
data × coverage × 0.8 (elevated night) × 0.7 (sanity check failed), clipped to [0, 1].

**Naps** (the app's source setting `minSessionMin` lowered below 180 min, e.g. 30). What changes for a session shorter
than 180 min, and what deliberately does not:

- no elevated-HR comparison and no contribution to the nadir baseline (`baselineMinSessionMin`, section 4), no sanity
  check or proportion calibration (`proportionsMinSessionMin`, section 8); so a nap's confidence is the mean posterior
  × coverage only;
- the coverage gates are unchanged: coverage is a share of the session epochs, so a 30-min nap with heart rate every 2
  min has the same coverage as a night, and a nap with sparse heart rate falls back to RULES or is not estimated like a
  night would be;
- `LOW_HR_RANGE` stays: a short session often has P50 − P10 < 2 bpm, and its hrNorm is then genuinely unreliable;
  `CYCLE_PRIOR_DISABLED` (HMM only) is expected on sessions shorter than about two cycles and has no effect on the
  confidence;
- the estimators run unchanged: the HYBRID classifier's elapsed / remaining-time features and the HSMM durations were
  learned on whole nights, so a nap looks like the beginning and the end of a night at once; no nap was in the
  validation data, so nap hypnograms are unvalidated;
- population EM (section 10; only when the HMM emissions are used, not with the default HYBRID bundle) pools the
  latest written sessions, naps included, and counts them towards `emEveryNights`: a nap is on its own hrNorm scale
  and weighs by its epoch count (a 45-min nap ≈ a tenth of a night); not changed;
- source selection (app, before the estimator; README "Which source sessions become nights"): fragments closer than
  `mergeGapMin` = 30 min are merged before the nap filter, so a nap ending less than 30 min before bedtime becomes the
  first fragment of the night (the gap is AWAKE) — unchanged, because it cannot be told apart from a night that the
  watch split at an awakening, and the merged session's nadir is the night's; for two sources, a session lying mostly
  inside a session of another source but covering at most half of it is a piece of that sleep and never replaces it,
  whatever the priority (otherwise a 40-min fragment of a higher-priority watch would replace a whole night).

## 10. Personalization (`personal/`)

- **Calibration on reference nights** (nights with real stages from another source and heart rate): nights sorted by
  start, the latest round(n · `calibrationHoldout`) = 30 % held out (at least one when n ≥ 2). On the training epochs
  (HR data, a reference stage, not forced) the classifier bias deltas δ minimize J(δ) = (1/E_h) Σ_i CE(softmax(z_i +
  δ), y_i) + λ ‖δ‖² (z_i = classifier logits, E_h = epochs per hour = 60, λ = `calibrationRegularization` = 10, i.e.
  the regularization counts in hours of evidence; gradient descent from 0 with step 1/L, L = M/(2E_h) + 2λ); the HMM
  means per state and dimension become μ = (Σx + κ μ_0)/(n + κ) with κ = λ E_h = 600 pseudo-epochs (elevated nights
  excluded), kept only if the hrNorm order holds. Kappa before / after on the held-out nights is shown; the result is
  stored in Room as the personal model ("reset to global" deactivates it; the row stays in Room, inactive).
- **Without reference** (every `emEveryNights` = 30 new nights): unsupervised population EM over all usable nights
  (coverage ≥ 0.6, not elevated), `emIterations` = 3 Baum–Welch iterations pooling the posteriors of the full HMM over
  the nights and updating μ and σ with MAP shrinkage to the *global* table (τ = `personal.emPriorStrength` = 200),
  with the same order constraint.
- The personal model applies only when `personal.enabled` (default true): δ is added to the classifier logits (only
  for the bundle it was fitted for) and μ/σ override the HMM table. Its fingerprint enters the params hash;
  `algoVersion` does not change.

`paramsHash` = SHA-256 of `canonicalJson(AlgoParams) + "|model=" + bundle fingerprint + "|personal=" + personal
fingerprint + "|" + output options` (fingerprints = SHA-256 of the canonical JSON; formatting and key order do not
matter).

## 11. Parameters (`params/AlgoParams.kt`) and their defaults

Every number used by the estimators is a field of `AlgoParams` (editable in the settings, exported / imported as JSON,
part of the params hash), except the feature definitions of the hybrid feature set, which are fixed by
`FEATURE_SET_VERSION` (section 7). Values marked **trained** come from the validation in section 13
(`params/TrainedDefaults.kt`, generated by `tools/train/train.py defaults`); all other values are the specification's
defaults. The HMM emissions, mean bouts, transition shares and time priors are also routed through
`TrainedDefaults` (`HMM_EMISSIONS`, `HMM_MEAN_BOUTS`, `HMM_TRANSITIONS`, `HMM_TIME_PRIOR`), but the supervised
estimates failed the validity gate, so they are the specification's values (`HmmParams()` = `HmmParams.PHYSIOLOGY`).

| group | parameter | default | meaning |
|---|---|---|---|
| — | `estimator` | **HYBRID** (trained) | RULES / HMM / HYBRID |
| preprocess | `minBpm` / `maxBpm` | 30 / 180 | valid heart-rate range (bpm) |
| preprocess | `hampelWindow` / `hampelThreshold` / `hampelMinScaleBpm` | 5 / 3.0 / 1.0 | Hampel window (samples), threshold (scaled MADs), scale floor (bpm) |
| preprocess | `epochSec` | 60 | epoch length (30, 60 or 120 s) |
| preprocess | `maxInterpGapMin` | 10 | longest gap that is interpolated (min) |
| preprocess | `medianWindowEpochs` / `smoothWindowEpochs` | 3 / 5 | running median, then moving average (epochs) |
| preprocess | `contextMarginMin` | 30 | context read on both sides of the session (min) |
| preprocess | `minCoverage` / `rulesMinCoverage` | 0.6 / 0.4 | coverage gates (section 9) |
| preprocess | `minSessionMin` | 0 | estimator-level nap gate (0 = off; the app's source setting `minSessionMin` = 90 filters naps) |
| features | `normLowPercentile` / `normHighPercentile` / `minNormRangeBpm` | 10 / 50 / 2.0 | hrNorm normalization |
| features | `varHalfWindowEpochs` / `slopeHalfWindowEpochs` | 3 / 2 | hrVar and hrSlope windows (± epochs) |
| features | `cycleMinDistanceMin` / `cycleMinProminenceBpm` | 60 / 2.0 | ultradian minima search |
| features | `baselineNights` / `elevatedNightPct` / `elevatedAwakeShift` / `elevatedConfidenceFactor` | 14 / 12 / 0.3 / 0.8 | elevated-HR night |
| features | `baselineMinSessionMin` | 180 | shorter sessions (naps) neither enter the nadir baseline nor are compared with it (0 = every session) |
| features | `useSpo2` / `spo2DropPct` / `spo2AwakeLightFactor` / `spo2MedianWindowMin` | true / 3.0 / 2.0 / 60 | SpO2 desaturations |
| features | `useHrv` / `hrvWeight` / `hrvMaxDistanceMin` | true / 1.5 / 5 | RMSSD replaces hrVar |
| features | `useRespiratoryRate` / `respiratoryRateWeight` / `respiratoryHalfWindowEpochs` | true / 0.5 / 5 | respiratory-rate variability |
| hmm | `emissions` | `EmissionTable.PHYSIOLOGY` (table of section 6; the supervised estimates — DEEP hrNorm 1.28 > LIGHT 0.90 — failed the validity gate, section 13.2) | μ / σ × 4 states × 3 features (section 6) |
| hmm | `meanBouts` (AWAKE / LIGHT / DEEP / REM) | 5 / 20 / 20 / 20 (supervised estimate, not adopted: 3.13 / 11.50 / 16.15 / 13.11) | mean bout lengths (min) → self-transitions |
| hmm | `transitions` | specification values of section 6 (supervised estimate not adopted) | shares of the non-self transition mass |
| hmm | `epsilon` | 1e-4 | replacement of zero probabilities |
| hmm | `deepTimeIntercept` / `deepTimeSlope` / `deepTimeFloor` | 1.6 / 1.2 / 0.3 (estimated 2.05 / 2.00 / 0.04, not adopted) | LIGHT→DEEP × max(floor, a − b·t) |
| hmm | `remTimeIntercept` / `remTimeSlope` | 0.4 / 1.2 (estimated 0.54 / 0.89, not adopted) | LIGHT→REM × (c + d·t) |
| hmm | `remLockoutMin` / `remLockoutFactor` | 45 / 0.05 (factor estimated 0.77 with 45 min, not adopted) | REM suppression after the session start |
| hmm | `initialAwake` / `initialLight` | 0.5 / 0.5 (estimated 0.98 / 0.02, not adopted) | initial distribution (DEEP, REM: ε) |
| hmm | `cyclePriorWeight` / `cycleDeepPhase` / `cycleRemPhase` | 0.5 / 0.0 / 0.7 | ultradian-cycle log-prior |
| hmm | `awakeStepThreshold` / `fewStepsAwakeFactor` | 5 / 3.0 | steps → forced AWAKE / AWAKE likelihood × 3 |
| hmm | `emAdapt` / `emIterations` / `emPriorStrength` | true / 3 / 20 | per-night Baum–Welch with MAP shrinkage |
| rules | `deepMaxHrNorm` / `deepMaxHrVar` | 0.45 / 0.8 | DEEP rule |
| rules | `awakeMinHrNorm` | 2.2 | AWAKE rule |
| rules | `remMinHrNorm` / `remMinHrVar` / `remMinT` | 1.3 / 1.1 / 0.25 | REM rule |
| rules | `pseudoPosterior` / `calibrationThresholdShift` | 0.85 / 0.25 | pseudo-posteriors; threshold shift per calibration offset |
| hybrid | `durationModel` | **EXPLICIT** (trained; also the pre-training value; GEOMETRIC was chosen in 4 of 10 inner selections) | EXPLICIT (HSMM, gamma durations) / GEOMETRIC (plain HMM) |
| hybrid | `timePriorWeight` | **0.0** (trained; pre-training 1.0) | weight of log P(stage \| 30-min bin) / P(stage) |
| hybrid | `emissionWeight` | **0.5** (trained; pre-training 1.0) | exponent of the scaled likelihoods |
| hybrid | `maxBoutMin` | 120 | maximum bout length of the HSMM (min) |
| hybrid | `elevatedAwakeFactor` | 0.6 | AWAKE likelihood multiplier on elevated nights |
| hybrid | `priorExponent` | **1.0** (pre-registered experiment, section 13.10: the share correction is the bundle's `stageLogBias`, β stays 1) | β of the scaled likelihood p(s \| x) / π_s^β |
| post | `minDeepMin` / `minRemMin` / `minAwakeMin` | 5 / 5 / 2 | minimum bouts (min) |
| post | `gapPolicy` | UNKNOWN | UNKNOWN / LIGHT / NEAREST for long gaps |
| post | `sanityMinCoverage` / `sanityDeepMax` / `sanityRemMax` / `sanityDeepMin` / `sanityConfidenceFactor` | 0.8 / 0.35 / 0.40 / 0.03 / 0.7 | sanity check |
| post | `proportionsMinSessionMin` | 180 | sanity check and proportion calibration only for sessions at least this long (0 = every session) |
| post | `calibrateProportions` | false | soft proportion calibration |
| post | `targetDeepMin` / `targetDeepMax` / `targetRemMin` / `targetRemMax` / `targetAwakeMax` | 0.10 / 0.25 / 0.15 / 0.30 / 0.15 | calibration corridors |
| post | `calibrationStep` / `calibrationMaxIterations` | 0.2 / 5 | calibration step and iterations |
| post | `lowConfidenceThreshold` / `lowConfidencePolicy` | 0.45 / KEEP | uncertain epochs: keep the decoder's label or write LIGHT |
| personal | `enabled` | true | apply the personal model |
| personal | `emEveryNights` / `emPriorStrength` | 30 / 200 | population EM cadence and shrinkage |
| personal | `calibrationRegularization` / `calibrationHoldout` | 10 / 0.3 | calibration strength (hours of evidence) and held-out share |

The model bundle itself (`assets/models/hybrid_model.json`: standardization, classifier weights, class priors, gamma
durations, bout transitions, initial distribution, time prior, proportion-matching `stageLogBias`) is not part of
`AlgoParams`; its SHA-256 fingerprint enters the params hash.

## 12. Validation protocol

Defaults are never tuned on synthetic data or by feel (specification §9). Everything below is reproducible with
`tools/train` (section 17); the raw data are downloaded by script and never committed.

### 12.1 Datasets and licences

| dataset | content | labels | used as |
|---|---|---|---|
| **PhysioNet sleep-accel 1.0.0** (Walch et al. 2019), <https://physionet.org/content/sleep-accel/1.0.0/> | 31 healthy adults (21 F, 29.4 ± 8.5 y), one in-lab night each; Apple Watch PPG heart rate (≈ every 5 s) and raw accelerometer (≈ 50 Hz) | in-lab **polysomnography**, AASM 30-s epochs (R&K N4 in 3 subjects → DEEP; unscored epochs omitted) | primary training set and **headline** validation |
| **PhysioNet BIDSleep 1.0.1** (Song et al. 2026), <https://physionet.org/content/bidsleep-dataset/1.0.1/> | 47 healthy adults, 253 nights at home (3–7 per person); Apple Watch instantaneous heart rate (≈ 0.2 Hz) | **Dreem 2 EEG headband**, auto-scored then expert-corrected (`expert_label`; "Unknown" omitted) | additional training data and secondary validation (its accelerometer, 6.35 GB, was not downloaded: no steps) |
| PhysioNet DREAMT 2.2.0 | — | — | **not used**: restricted access (signed DUA, "scientific research and no other") |

Both datasets are open access under the **Open Data Commons Attribution License v1.0 (ODC-By 1.0)**, which allows use,
derived databases and produced works (including commercial use) with attribution. The bundled model is a produced work
of both. Attribution notice (also in README and in the app: Settings → "Versions and licences"):

> Contains information from *Motion and heart rate from a wrist-worn wearable and labeled sleep from
> polysomnography* (https://physionet.org/content/sleep-accel/1.0.0/) and from *BIDSleep: A Multi-Night
> Instantaneous Heart Rate and Accelerometry Dataset with EEG Sleep Stage Labels*
> (https://physionet.org/content/bidsleep-dataset/1.0.1/), which are made available under the ODC Attribution
> License (https://opendatacommons.org/licenses/by/1-0/).

Citations: Walch O. (2019) sleep-accel v1.0.0, PhysioNet, https://doi.org/10.13026/hmhs-py35; Walch O, Huang Y, Forger
D, Goldstein C. Sleep 42(12):zsz180 (2019), https://doi.org/10.1093/sleep/zsz180; Song T, Zhang Y, Zhou Z, Dutta J.
(2026) BIDSleep v1.0.1, PhysioNet, https://doi.org/10.13026/rees-1092; Song TA et al. IEEE TBME 73(4):1596–1608
(2026), https://doi.org/10.1109/TBME.2025.3612158; Pollard T et al. PhysioNet as a global platform for biomedical
research, Nature Health (2026), https://doi.org/10.1038/s44360-026-00096-z. Details and label-code checks:
`tools/train/DATASETS.md`.

### 12.2 Emulating OHealth (`tools/train/prepare.py`)

- **Heart rate, one value per 2 minutes**, in two variants (the specification asks for both; the report takes the
  worse): `instantaneous` = the raw sample nearest to each point of a 2-minute grid, within ±30 s (else no value);
  `mean2min` = the mean of all raw samples of each 2-minute window, at the window centre. The grid phase is
  deterministic per night (SHA-256 of the night key), not aligned to the PSG start. This leaves ≈ 30 values per hour
  (Walch: median 233 per night; BIDSleep: 199).
- **Session window, OHealth-like**: from 5 minutes before the first non-wake epoch to 5 minutes after the last one,
  clipped to the scored range (watches report a sleep session, not the lights-off time); heart rate and steps are
  included for the session ± 30 min where they exist. This tells the model the true sleep onset and final awakening;
  section 13.8 measures what moving these boundaries costs. Variants `instantaneous-psg` / `mean2min-psg` use the full
  scored PSG / headband range instead (more pre-sleep wake; reported for completeness).
- **Steps from the accelerometer**: a conservative wrist pedometer on the raw Walch accelerometer (magnitude, 50 Hz
  resampling without interpolation across gaps > 1 s, 0.5–3 Hz band-pass, peak detection, steps only inside regular
  walking bouts of ≥ 8 peaks), as 1-minute counts. Over the 31 Walch sessions it found 196 steps (18 inside PSG sleep
  epochs). BIDSleep nights have no step channel. The pedometer rule was chosen by comparing steps in PSG sleep and
  wake epochs of all 31 Walch nights (`DATASETS.md`, section 4), i.e. outside the LOSO loop (a limitation, section 16;
  its effect is bounded in section 13.8).
- **Labels**: the scored 30-s epochs inside the session, mapped to AWAKE (W), LIGHT (N1 + N2), DEEP (N3, N4), REM.
  OHealth-like sessions: Walch 31 nights, 216.5 h labelled, AWAKE 6.3 % / LIGHT 56.9 % / DEEP 14.2 % / REM 22.6 %;
  BIDSleep 253 nights, 1704.9 h, 6.1 / 47.1 / 19.5 / 27.3 %.
- **Scoring**: each 30-s label epoch is compared with the predicted stage at its midpoint (`IntervalScoring`); label
  epochs that fall into long heart-rate gaps (predicted UNKNOWN by the default gap policy) are not scored: 1.0 % of
  the Walch label epochs for `instantaneous`, 0.1 % for `mean2min` (0.55 % of both together), 2.3 % / 2.2 % of the
  BIDSleep ones.

### 12.3 Leave-one-subject-out with nested selection (`tools/train/train.py`)

- **Estimators**: RULES and HMM-table (the specification's rules, physiology table, mean bouts, transition shares and
  time priors, nothing trained); **HMM-trained** (per fold, supervised estimation on the training subjects: Gaussian
  μ/σ of hrNorm/hrVar/hrSlope per stage on epochs with HR data, mean uncensored bout lengths, bout-transition shares,
  and the time prior — the LIGHT→DEEP multiplier max(floor, a − b·t), the LIGHT→REM multiplier c + d·t and the REM
  lockout factor by conditional maximum likelihood of the minute-to-minute label transitions under exactly the
  transition model of section 6 with the other transition parameters fixed at their own estimates, the lockout
  length kept at 45 min, and the initial AWAKE / LIGHT weights from the first labelled stage of each night; the
  cycle-phase prior and the step factors keep the specification's values); **HYBRID-LR** (multinomial logistic
  regression, L2, lbfgs, C ∈ {0.01, 0.1, 1} chosen by inner-CV log-loss) and **HYBRID-MLP** (32 × 32 relu, adam,
  batch 256, L2 α = 1e-4, early stopping on 10 % of the rows, fixed seed). Features and decoding are always computed
  by `algo-cli` (the app's Kotlin code).
- **Outer loop**: leave one subject out of the pooled training set of a configuration. Everything is fitted on the
  other subjects only: standardization, classifier, gamma durations, bout transitions, initial distribution, time
  prior, class priors and the HMM estimates. All nights of a BIDSleep subject stay together. Subjects of datasets
  outside the pool are scored once with the model of the whole pool (`external`: BIDSleep with the Walch-only model).
- **Training configurations**: `walch` (Walch only, steps used); `walch+bid-mask` (+ BIDSleep, step features masked
  for every night: standardization std 1e12, zero training column and zero first-layer weights, so the model never
  uses steps); `walch+bid-zero` (+ BIDSleep, whose nights have no step channel: their step features are exactly what
  `algo-cli` computes for them, 0 steps — which is also what the app computes for a night without step records).
  Mask vs zero is decided by the same validation rule. Invariant, checked in every fit and by the tests: the
  classifier is trained on exactly the feature values that `algo-cli` / the app compute for the same epochs (masked
  columns, which the model never uses, are the only exception). Training rows of a fold: epochs with HR data and a
  label from the evaluated variant **and its sampling twin** (instantaneous ∪ mean2min of the same session window);
  label statistics from the evaluated variant.
- **Nested selection of the decoder** (never touching the held-out subject): deterministic, dataset-stratified,
  grouped 5-fold CV over the selection subjects; inner fold models are fitted exactly like outer ones, and one
  `algo-cli evaluate --param-grid` call scores the decoder grid on the inner held-out *Walch* subjects:
  `hybrid.durationModel` ∈ {EXPLICIT, GEOMETRIC} × `emissionWeight` ∈ {1, 0.7, 0.5, 0.3} × `timePriorWeight` ∈ {1,
  0.5, 2, 0} for LR (32 points), {EXPLICIT, GEOMETRIC} × {1, 0.5} × {1, 0} for the MLP (8 points); best mean
  per-subject kappa wins, ties → grid order (the first point is the pre-training default). **Selection mode used:
  `per-group`**: the outer subjects are dealt into the same 5 dataset-stratified groups, and the selection for every
  subject of a group runs on the training subjects *outside that group* (≈ 80 % of the pool, never the held-out
  subject), so 5 inner selections per (configuration, variant, classifier) are shared by the subjects of a group
  instead of one per subject. It is leak-free; it uses ≈ 20 % fewer subjects for the choice of three decoder
  hyper-parameters (and LR's C) than the full per-subject mode (1 504 inner-CV runs), which was started, projected at
  more than 2.5 h for the nested selection alone under full load on this machine (the logged projection) and
  stopped.
- **Metrics** (recomputed from the per-night confusion matrices that `algo-cli` writes): Cohen's kappa per subject
  (the subject's nights pooled; mean, **sample sd (n − 1)** and median over subjects) and pooled over all chunks;
  accuracy; per-stage precision / recall / F1; macro-F1; confusion matrix; mean absolute error per night of the DEEP,
  REM and AWAKE minutes. Split by the held-out subject's dataset: **Walch (PSG) is the headline**, BIDSleep (EEG
  headband) is secondary. Differences between candidates are given as paired per-subject differences with a 95 %
  percentile bootstrap CI (10 000 resamples of the subjects).
- **Selection rule** (one rule for every choice): score = mean per-subject kappa on the Walch held-out subjects,
  **worse of the two OHealth-like variants** (instantaneous, mean2min). It picks the training configuration per
  classifier, LR vs MLP, HMM-trained vs the physiology table, BIDSleep mask vs zero and the default estimator. The
  trained HMM estimates become defaults only if they also pass a **validity gate**: the estimates on all subjects of
  the pool satisfy the specification's own order constraint μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE on hrNorm (otherwise
  the per-night EM and the HMM personalization of section 10 roll back on practically every night), and the pooled
  predicted DEEP share lies in [3 %, 35 %] and the REM share ≤ 40 % (the sanity corridors of section 8) on both
  headline variants. The final decoder parameters are the most frequent inner-CV choice over the outer folds of the
  chosen configuration on the two headline variants (ties: higher mean inner kappa, then grid order); LR's C
  likewise.
- **Final model**: fitted on all subjects of the chosen configuration, rows of both headline variants, and installed
  as `app/src/main/assets/models/hybrid_model.json`; a resubstitution run through `algo-cli` checks that the app
  accepts it (its numbers are not a validation result).
- **Proportion matching** (pre-registered v3, adopted, section 13.10): in the validation, the stage log-bias b of
  section 7 is fitted per outer group (the groups of the nested selection) on the inner out-of-fold decodes of the
  group's selection subjects (Walch, the selection-scoring PSG dataset) at the group's decoder choice, and the
  held-out subjects of the group (Walch and BIDSleep) are decoded with their LOSO fold bundle + that b. The final
  bundle's b is fitted on the LOSO out-of-fold decodes of all Walch subjects (both headline variants pooled) at the
  final decoder parameters.
- **Provenance**: each run records the SHA-256 of the `algo-cli` build it used (launcher + `lib/*.jar`) in its
  `config.json` and fails if the build changes during the run; every rebuild is logged in
  `tools/train/out/commands.log`.

## 13. Results

Runs of `tools/train` on 2026-10-13 (Intel Core Ultra 9 275HX, 24 cores; commands in section 17), all on one
`algo-cli` build (its SHA-256 is recorded in every run's `config.json`; the runs refuse to continue if it changes):
`loso-v2` — every estimator, training configuration, classifier and variant (`--selection per-group`, 31.7 min, all
`algo-cli` calls exit code 0); `exp-v2-e1-balanced` / `exp-v2-e2-mlp-alpha` — the two pre-registered experiments on
its selection (section 13.6); `seed-v2-s1` … `seed-v2-s3` — the adopted setting with three other seeds (section 13.7,
reporting only); `loso-v2/sensitivity` — moved session windows and nights without steps (section 13.8, reporting
only). Neither experiment was adopted, so `loso-v2` is also the complete-protocol run of the adopted classifier
setting; it trained the shipped classifier. Then, on a later build that adds the decoder options β and b (defaults
unchanged; it re-decodes all 568 `loso-v2` HYBRID-MLP [walch+bid-zero] nights identically): `bias-base` — the same
setting re-run with its inner fold models kept (6.4 min; reproduces `loso-v2` night by night, the 156 decoder choices
and the final bundle exactly), `bias-base/bias` — the pre-registered stage-proportion experiment (section 13.10, 433
`algo-cli` calls, all exit code 0; re-run on the final build with `--allow-other-build` as `bias-base/bias-033541ae`,
it reproduced every per-night result and inner fit byte for byte) and `bias-final` — the final bundle with b
(installed). Rows marked "+ b" below are
the adopted default with proportion matching; the other HYBRID rows are without it. All kappas are Cohen's kappa on
the 30-s label epochs over AWAKE / LIGHT / DEEP / REM; "κ mean ± sd" is the mean and the **sample sd (n − 1)** over
subjects (each subject's nights pooled), "κ pooled" is over all chunks; minute errors are mean absolute errors per
night.

Sanity checks: for every metrics file the confusion-matrix total plus the chunks predicted UNKNOWN equals the number
of 30-s label chunks of the evaluated nights (UNKNOWN = label chunks inside heart-rate gaps > 10 min, not scored: 1.0
% of the Walch chunks for `instantaneous`, 0.1 % for `mean2min`, 2.3 % / 2.2 % of the BIDSleep chunks); no night was
skipped; no HYBRID night fell back to the HMM (`HYBRID_MODEL_UNAVAILABLE` never occurred); **one BIDSleep night
(`bid-34` night 1, coverage < 0.6) is estimated by RULES (`RULES_FALLBACK_LOW_COVERAGE`) in every HMM and HYBRID
evaluation and in the resubstitution run, exactly as the app would do** (column "RULES fallback" below; Walch has no
such night); the Walch rows of the estimators and configurations that did not change since the superseded run
`loso-v1` (RULES, HMM-table, `walch`, `walch+bid-mask`) are identical to it; re-evaluating the `loso-v2` Walch fold
models with the final build (the one with the new `TrainedDefaults.kt`) reproduces every per-subject kappa exactly.
The headline κ ≈ 0.34 is in the expected range for 2-minute heart rate (section 14) and well below the ≈ 0.5 that
would suggest leakage.

### 13.1 Headline: Walch (PSG), OHealth-like session window

Leave-one-subject-out over the 31 Walch subjects; for the pooled configurations the 47 BIDSleep subjects are in every
training fold (and are held out in their own folds, section 13.5). **Bold** = the worse of the two OHealth emulations,
the number used by the selection rule; the median, pooled, accuracy, F1 and minute columns are for that worse variant.

| Estimator [training config] | κ instantaneous, mean ± sd | κ mean2min, mean ± sd | **κ worse variant** | κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE / LIGHT / DEEP / REM | abs. error min DEEP / REM / AWAKE |
|---|---|---|---|---|---|---|---|---|---|
| RULES | 0.076 ± 0.076 | 0.098 ± 0.099 | **0.076** (instantaneous) | 0.069 | 0.079 | 0.489 | 0.275 | 0.16 / 0.68 / 0.12 / 0.15 | 36.0 / 80.0 / 45.1 |
| HMM-table | 0.070 ± 0.103 | 0.092 ± 0.114 | **0.070** (instantaneous) | 0.077 | 0.072 | 0.370 | 0.292 | 0.17 / 0.52 / 0.14 / 0.35 | 44.7 / 43.8 / 55.1 |
| HMM-trained [walch] | 0.064 ± 0.092 | 0.073 ± 0.092 | **0.064** (instantaneous) | 0.042 | 0.062 | 0.258 | 0.250 | 0.21 / 0.21 / 0.29 / 0.29 | 237.0 / 58.1 / 21.1 |
| HMM-trained [walch+bidsleep] | 0.117 ± 0.131 | 0.165 ± 0.124 | **0.117** (instantaneous) | 0.087 | 0.111 | 0.365 | 0.309 | 0.20 / 0.47 / 0.31 / 0.26 | 134.6 / 48.6 / 23.1 |
| HYBRID-LR [walch] | 0.288 ± 0.156 | 0.308 ± 0.160 | **0.288** (instantaneous) | 0.304 | 0.287 | 0.515 | 0.468 | 0.32 / 0.55 / 0.48 / 0.52 | 47.8 / 62.4 / 19.0 |
| HYBRID-LR [walch+bid-mask] | 0.273 ± 0.101 | 0.287 ± 0.139 | **0.273** (instantaneous) | 0.282 | 0.282 | 0.486 | 0.443 | 0.28 / 0.50 / 0.45 / 0.54 | 65.3 / 64.9 / 20.7 |
| HYBRID-LR [walch+bid-zero] | 0.264 ± 0.092 | 0.286 ± 0.138 | **0.264** (instantaneous) | 0.282 | 0.273 | 0.483 | 0.441 | 0.29 / 0.51 / 0.45 / 0.52 | 68.1 / 55.9 / 22.4 |
| HYBRID-MLP [walch] | 0.247 ± 0.140 | 0.284 ± 0.161 | **0.247** (instantaneous) | 0.225 | 0.255 | 0.517 | 0.465 | 0.34 / 0.58 / 0.46 / 0.48 | 34.8 / 53.4 / 20.1 |
| HYBRID-MLP [walch+bid-mask] | 0.309 ± 0.104 | 0.334 ± 0.118 | **0.309** (instantaneous) | 0.315 | 0.307 | 0.514 | 0.471 | 0.30 / 0.53 / 0.50 / 0.56 | 50.6 / 58.7 / 23.0 |
| HYBRID-MLP [walch+bid-zero] (`loso-v2` default, no proportion matching) | 0.334 ± 0.133 | 0.336 ± 0.156 | **0.334** (instantaneous) | 0.353 | 0.333 | 0.540 | 0.498 | 0.35 / 0.57 / 0.50 / 0.57 | 52.4 / 53.5 / 18.0 |
| **HYBRID-MLP [walch+bid-zero] + b (default, proportion matching)** | 0.348 ± 0.141 | 0.339 ± 0.169 | **0.339** (mean2min) | 0.354 | 0.350 | 0.612 | 0.522 | 0.36 / 0.69 / 0.47 / 0.56 | 33.1 / 40.8 / 19.4 |

The shipped default scores **κ = 0.339** on the worse variant (0.348 on `instantaneous`) and its per-night LIGHT
error is 38 / 53 min (instantaneous / mean2min; 95 / 86 min without b). The classifier and the decoder choice are
those of `loso-v2`, the best of the candidates on these data with one seed; the mean over four seeds of that setting
without b is 0.317 (section 13.7), the conservative estimate of what this training procedure delivers. b was
adopted by the pre-registered rule of section 13.10, which selects on the minute error, not on κ.

### 13.2 Selected defaults (selection rule of section 12.3)

Paired per-subject comparisons behind each choice (Walch, chosen − alternative, 95 % bootstrap CI over the 31
subjects):

| a | b | variant | subjects | mean Δκ | 95 % CI | sd of Δ | a better / worse |
|---|---|---|---|---|---|---|---|
| HYBRID-MLP [walch+bid-zero] | HYBRID-MLP [walch] | instantaneous | 31 | 0.086 | [0.040, 0.134] | 0.138 | 22 / 9 |
| HYBRID-MLP [walch+bid-zero] | HYBRID-MLP [walch] | mean2min | 31 | 0.053 | [0.006, 0.101] | 0.136 | 20 / 11 |
| HYBRID-MLP [walch+bid-zero] | HYBRID-MLP [walch+bid-mask] | instantaneous | 31 | 0.024 | [-0.008, 0.057] | 0.094 | 21 / 10 |
| HYBRID-MLP [walch+bid-zero] | HYBRID-MLP [walch+bid-mask] | mean2min | 31 | 0.003 | [-0.025, 0.030] | 0.078 | 16 / 15 |
| HYBRID-MLP [walch+bid-zero] | HYBRID-LR [walch] | instantaneous | 31 | 0.046 | [-0.003, 0.094] | 0.139 | 17 / 14 |
| HYBRID-MLP [walch+bid-zero] | HYBRID-LR [walch] | mean2min | 31 | 0.028 | [-0.013, 0.069] | 0.118 | 18 / 13 |
| HYBRID-MLP [walch+bid-zero] | HMM-table | instantaneous | 31 | 0.263 | [0.212, 0.315] | 0.152 | 31 / 0 |
| HYBRID-MLP [walch+bid-zero] | HMM-table | mean2min | 31 | 0.244 | [0.191, 0.298] | 0.152 | 29 / 2 |
| HYBRID-MLP [walch+bid-zero] | RULES | instantaneous | 31 | 0.257 | [0.208, 0.307] | 0.145 | 31 / 0 |
| HYBRID-MLP [walch+bid-zero] | RULES | mean2min | 31 | 0.238 | [0.182, 0.297] | 0.164 | 30 / 1 |
| HMM-trained [walch+bidsleep] | HMM-table | instantaneous | 31 | 0.047 | [0.001, 0.102] | 0.148 | 18 / 13 |
| HMM-trained [walch+bidsleep] | HMM-table | mean2min | 31 | 0.073 | [0.018, 0.135] | 0.169 | 21 / 10 |

- **Default estimator: HYBRID** (worse-variant mean per-subject κ 0.334; HMM with the adopted physiology table 0.070,
  RULES 0.076; both clearly worse, CIs far from 0).
- **Emission classifier: MLP** (32 × 32 relu, L2 α = 1e-4) with training configuration **`walch+bid-zero`**. The best
  logistic regression (`walch`, 0.288) is lower, but the paired difference is not significant (+0.046 [−0.003,
  0.094] / +0.028 [−0.013, 0.069]); the MLP clearly gains from the pooled training set (78 subjects / 226 k epochs
  instead of 31 / 26 k: +0.086 [0.040, 0.134] over the Walch-only MLP), logistic regression does not (0.264–0.273
  pooled).
- **BIDSleep steps: `zero`** (0.334) over `mask` (0.309) by the rule, but the difference is not significant (+0.024
  [−0.008, 0.057] / +0.003 [−0.025, 0.030]), and on BIDSleep the two are equal (section 13.5). Steps carry almost no
  information in these data: only 25 of the ≈ 13 000 OHealth-like Walch session epochs have a step, and removing all
  steps from the Walch nights changes κ by −0.002 / −0.003 (section 13.8).
- **HMM: the physiology table stays the default.** The supervised estimates on Walch + BIDSleep scored higher (0.117 vs
  0.070; +0.047 [0.001, 0.102] / +0.073 [0.018, 0.135]) but **failed the validity gate**: their hrNorm means violate the
  specification's order (DEEP 1.28 > LIGHT 0.90 — a real property of these 2-minute data, not a label swap: DEEP comes
  early in the night while heart rate is still falling, and is not below LIGHT even within each third of the night),
  so the per-night EM and the HMM personalization would roll back on practically every night; and the HMM-trained
  predictions are 46.6 % / 42.4 % DEEP (truth 14 %), far outside the 35 % sanity corridor. `TrainedDefaults.HMM_*`
  therefore point to `EmissionTable.PHYSIOLOGY`, `MeanBouts()`, `TransitionWeights()` and `HmmTimePrior()`. The time
  prior was estimated this time (section 12.3); on all 78 subjects: LIGHT→DEEP × max(0.04, 2.05 − 2.00 t), LIGHT→REM ×
  (0.54 + 0.89 t), REM lockout factor 0.77 (45 min), initial AWAKE 0.98 / LIGHT 0.02. It is part of HMM-trained (0.117
  with it, 0.109 without in `loso-v1`), but not adopted with the rest.
- **Decoder: `durationModel` = EXPLICIT, `emissionWeight` = 0.5, `timePriorWeight` = 0** — the most frequent inner-CV
  choice, but the evidence is thin: in per-group mode there are 10 distinct inner selections (5 outer groups × 2
  headline variants), shared by the 15–16 outer folds of a group, and the winner of each beat its runner-up by only
  0.0005–0.0088 inner κ (median 0.004):

  | inner-CV choice (durationModel, emissionWeight, timePriorWeight) | inner selections | outer folds |
  |---|---|---|
  | EXPLICIT, 0.5, 0 | 4 | 64 |
  | GEOMETRIC, 0.5, 0 | 3 | 46 |
  | EXPLICIT, 1, 1 | 1 | 16 |
  | EXPLICIT, 1, 0 | 1 | 15 |
  | GEOMETRIC, 1, 0 | 1 | 15 |

  What the data do support: the time-of-night prior off (9 of 10 selections, 140 of 156 folds — the hybrid features
  already contain the elapsed and remaining time) and flattened emissions, w_e = 0.5 (7 of 10; the classifier's
  posteriors are over-confident for the decoder). EXPLICIT vs GEOMETRIC is not decided (6 vs 4 selections).
- **Stage proportions: proportion matching b, β = 1** — the pre-registered experiment of section 13.10 (prior exponent
  β, proportion matching b, both): b has the lowest summed per-night minute error on Walch (147 vs 219 min) with κ
  inside the rule's margin (0.339 vs 0.334; paired Δκ +0.003 [−0.037, 0.038] on mean2min — within noise, which does
  not show that no κ is lost; −0.03 on BIDSleep), and was adopted by its rule; β stays 1.
- **Final model** (`app/src/main/assets/models/hybrid_model.json`, `modelId`
  `pulsestages-walch+bidsleep-mlp-bias-final`): MLP fitted on all 78 subjects (225 973 epochs of the instantaneous and
  mean2min variants), gamma durations / bout transitions / initial distribution / time prior from the 284 nights —
  identical to the `loso-v2` final model, re-fitted by `bias-base` — plus `stageLogBias` = (+0.090, +0.216, −0.318,
  +0.012) (section 7); the exported weights reproduce scikit-learn's probabilities to 3e-16; the resubstitution run
  through `algo-cli` used the bundle on 283 nights and RULES on the one low-coverage BIDSleep night (no HMM fallback).

### 13.3 Confusion matrices of the default estimator (HYBRID-MLP [walch+bid-zero] + b, LOSO)

Rows = reference (PSG / headband), columns = estimate; counts of 30-s epochs (row %). Each held-out subject is decoded
with its LOSO fold bundle plus the b of its outer group (section 12.3).

**Walch (PSG), mean2min — the worse variant, headline** (31 nights):

| truth \ estimate | AWAKE | LIGHT | DEEP | REM | total | recall |
|---|---:|---:|---:|---:|---:|---:|
| AWAKE | 585 (35.8 %) | 711 (43.5 %) | 84 (5.1 %) | 255 (15.6 %) | 1635 | 0.358 |
| LIGHT | 675 (4.6 %) | 10269 (69.6 %) | 1531 (10.4 %) | 2276 (15.4 %) | 14751 | 0.696 |
| DEEP | 65 (1.8 %) | 1728 (46.9 %) | 1649 (44.7 %) | 243 (6.6 %) | 3685 | 0.447 |
| REM | 250 (4.2 %) | 2200 (37.4 %) | 62 (1.1 %) | 3372 (57.3 %) | 5884 | 0.573 |
| predicted total | 1575 | 14908 | 3326 | 6146 | 25955 | |
| precision | 0.371 | 0.689 | 0.496 | 0.549 | | |

**Walch (PSG), instantaneous** (31 nights):

| truth \ estimate | AWAKE | LIGHT | DEEP | REM | total | recall |
|---|---:|---:|---:|---:|---:|---:|
| AWAKE | 553 (34.2 %) | 744 (46.1 %) | 107 (6.6 %) | 211 (13.1 %) | 1615 | 0.342 |
| LIGHT | 599 (4.1 %) | 10554 (72.0 %) | 1715 (11.7 %) | 1799 (12.3 %) | 14667 | 0.720 |
| DEEP | 73 (2.0 %) | 1578 (43.5 %) | 1823 (50.2 %) | 154 (4.2 %) | 3628 | 0.502 |
| REM | 268 (4.6 %) | 2327 (40.0 %) | 169 (2.9 %) | 3047 (52.4 %) | 5811 | 0.524 |
| predicted total | 1493 | 15203 | 3814 | 5211 | 25721 | |
| precision | 0.370 | 0.694 | 0.478 | 0.585 | | |

**BIDSleep (EEG headband), instantaneous** (253 nights):

| truth \ estimate | AWAKE | LIGHT | DEEP | REM | total | recall |
|---|---:|---:|---:|---:|---:|---:|
| AWAKE | 3786 (31.5 %) | 5039 (42.0 %) | 910 (7.6 %) | 2273 (18.9 %) | 12008 | 0.315 |
| LIGHT | 4486 (4.8 %) | 68584 (73.8 %) | 6910 (7.4 %) | 13009 (14.0 %) | 92989 | 0.738 |
| DEEP | 1085 (2.7 %) | 17967 (45.2 %) | 18956 (47.6 %) | 1779 (4.5 %) | 39787 | 0.476 |
| REM | 1662 (3.0 %) | 30387 (55.2 %) | 2155 (3.9 %) | 20800 (37.8 %) | 55004 | 0.378 |
| predicted total | 11019 | 121977 | 28931 | 37861 | 199788 | |
| precision | 0.344 | 0.562 | 0.655 | 0.549 | | |

Most errors are confusions with LIGHT. With proportion matching the **Walch stage shares match PSG on average**:
predicted AWAKE / LIGHT / DEEP / REM 6.1 / 57.4 / 12.8 / 23.7 % (mean2min) and 5.8 / 59.1 / 14.8 / 20.3 %
(instantaneous) vs PSG 6.3 / 56.8 / 14.2 / 22.7 %. Without it (`loso-v2`) the decoder predicted 7 / 34 / 25 / 34 %
(instantaneous; recall DEEP 0.70, REM 0.71, LIGHT 0.45, precision DEEP 0.39, REM 0.48): it bought minority-stage
recall with a third of all LIGHT epochs; b trades some of that recall (DEEP 0.45–0.50, REM 0.52–0.57) for LIGHT recall
(0.70–0.72) and precision of DEEP / REM (0.48–0.59), with Walch κ within noise (CIs include 0) and accuracy 0.540 /
0.556 → 0.621 / 0.612 (instantaneous / mean2min). Per-night minute errors fall to 33–34 min (DEEP), 38–41 (REM), 18–19
(AWAKE) and 38–53 (LIGHT), from 47–52, 48–54, 18–21 and 86–95. The sanity check flags 2 / 7 of the 31 Walch nights
(instantaneous / mean2min; 12 / 11 without b; 3 of the 7 for DEEP < 3 %). b is fitted to the Walch PSG shares;
BIDSleep's headband labels have more N3 and REM (19.9 / 27.5 %), and there b predicts 14.5 / 19.0 % DEEP / REM
(instantaneous), lowers κ by 0.03 and flags 12 / 15 of the 253 nights for DEEP < 3 % (`SANITY_DEEP_LOW`; 3 / 0 without
b), most of its sanity flags there (section 13.10). Wake inside the session is the hardest stage (recall 0.32–0.36,
precision 0.34–0.37): heart rate rises only briefly during short awakenings and in-bed steps are rare.

### 13.4 All estimators and variants, Walch (PSG)

`*-psg` = the full scored PSG range as the session (more pre-sleep wake), for completeness; the selection uses only
the OHealth-like `instantaneous` / `mean2min` windows. Per-subject κ of the shipped default ranges from −0.03
(5383425, 3509524) to 0.65 (8258170) on mean2min and from 0.05 (1455390) to 0.63 (1449548) on instantaneous; 7 / 4
of 31 subjects exceed 0.5 and 5 / 4 are below 0.2 (without b, instantaneous: 0.08 (5383425) to 0.66 (4018081, one of
the five nights cut short by the watch battery), 2 above 0.5, 6 below 0.2). The stage-proportion experiment decoded
only the two OHealth-like variants, so the `*-psg` tables have no "+ b" row.

#### instantaneous

| Estimator [config] | evaluation | nights (skipped) | RULES fallback | subjects | κ mean ± sd | κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE | F1 LIGHT | F1 DEEP | F1 REM | abs. err. min DEEP | REM | AWAKE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULES | untrained | 31 (0) | 0 | 31 | 0.076 ± 0.076 | 0.069 | 0.079 | 0.489 | 0.275 | 0.156 | 0.677 | 0.118 | 0.148 | 36.0 | 80.0 | 45.1 |
| HMM-table | untrained | 31 (0) | 0 | 31 | 0.070 ± 0.103 | 0.077 | 0.072 | 0.370 | 0.292 | 0.169 | 0.515 | 0.136 | 0.346 | 44.7 | 43.8 | 55.1 |
| HMM-trained [walch] | loso | 31 (0) | 0 | 31 | 0.064 ± 0.092 | 0.042 | 0.062 | 0.258 | 0.250 | 0.206 | 0.213 | 0.292 | 0.289 | 237.0 | 58.1 | 21.1 |
| HMM-trained [walch+bidsleep] | loso | 31 (0) | 0 | 31 | 0.117 ± 0.131 | 0.087 | 0.111 | 0.365 | 0.309 | 0.196 | 0.472 | 0.306 | 0.264 | 134.6 | 48.6 | 23.1 |
| HYBRID-LR [walch] | loso | 31 (0) | 0 | 31 | 0.288 ± 0.156 | 0.304 | 0.287 | 0.515 | 0.468 | 0.320 | 0.553 | 0.478 | 0.520 | 47.8 | 62.4 | 19.0 |
| HYBRID-LR [walch+bid-mask] | loso | 31 (0) | 0 | 31 | 0.273 ± 0.101 | 0.282 | 0.282 | 0.486 | 0.443 | 0.282 | 0.501 | 0.454 | 0.535 | 65.3 | 64.9 | 20.7 |
| HYBRID-LR [walch+bid-zero] | loso | 31 (0) | 0 | 31 | 0.264 ± 0.092 | 0.282 | 0.273 | 0.483 | 0.441 | 0.293 | 0.508 | 0.445 | 0.520 | 68.1 | 55.9 | 22.4 |
| HYBRID-MLP [walch] | loso | 31 (0) | 0 | 31 | 0.247 ± 0.140 | 0.225 | 0.255 | 0.517 | 0.465 | 0.344 | 0.576 | 0.460 | 0.481 | 34.8 | 53.4 | 20.1 |
| HYBRID-MLP [walch+bid-mask] | loso | 31 (0) | 0 | 31 | 0.309 ± 0.104 | 0.315 | 0.307 | 0.514 | 0.471 | 0.296 | 0.530 | 0.498 | 0.561 | 50.6 | 58.7 | 23.0 |
| HYBRID-MLP [walch+bid-zero] | loso | 31 (0) | 0 | 31 | 0.334 ± 0.133 | 0.353 | 0.333 | 0.540 | 0.498 | 0.351 | 0.565 | 0.505 | 0.570 | 52.4 | 53.5 | 18.0 |
| **HYBRID-MLP [walch+bid-zero] + b (default)** | loso | 31 (0) | 0 | 31 | 0.348 ± 0.141 | 0.347 | 0.361 | 0.621 | 0.526 | 0.356 | 0.707 | 0.490 | 0.553 | 34.3 | 38.2 | 17.6 |

#### mean2min

| Estimator [config] | evaluation | nights (skipped) | RULES fallback | subjects | κ mean ± sd | κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE | F1 LIGHT | F1 DEEP | F1 REM | abs. err. min DEEP | REM | AWAKE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULES | untrained | 31 (0) | 0 | 31 | 0.098 ± 0.099 | 0.084 | 0.096 | 0.489 | 0.288 | 0.167 | 0.677 | 0.123 | 0.183 | 37.9 | 77.6 | 46.5 |
| HMM-table | untrained | 31 (0) | 0 | 31 | 0.092 ± 0.114 | 0.098 | 0.097 | 0.364 | 0.311 | 0.217 | 0.475 | 0.158 | 0.393 | 48.7 | 38.5 | 61.1 |
| HMM-trained [walch] | loso | 31 (0) | 0 | 31 | 0.073 ± 0.092 | 0.067 | 0.071 | 0.265 | 0.258 | 0.237 | 0.231 | 0.296 | 0.269 | 244.3 | 61.1 | 21.4 |
| HMM-trained [walch+bidsleep] | loso | 31 (0) | 0 | 31 | 0.165 ± 0.124 | 0.177 | 0.159 | 0.398 | 0.357 | 0.252 | 0.484 | 0.314 | 0.380 | 119.6 | 40.2 | 20.4 |
| HYBRID-LR [walch] | loso | 31 (0) | 0 | 31 | 0.308 ± 0.160 | 0.305 | 0.316 | 0.543 | 0.484 | 0.329 | 0.607 | 0.485 | 0.515 | 62.8 | 42.6 | 20.5 |
| HYBRID-LR [walch+bid-mask] | loso | 31 (0) | 0 | 31 | 0.287 ± 0.139 | 0.304 | 0.301 | 0.516 | 0.466 | 0.301 | 0.556 | 0.466 | 0.543 | 71.0 | 33.9 | 23.2 |
| HYBRID-LR [walch+bid-zero] | loso | 31 (0) | 0 | 31 | 0.286 ± 0.138 | 0.297 | 0.300 | 0.515 | 0.470 | 0.320 | 0.554 | 0.470 | 0.536 | 70.8 | 31.5 | 21.7 |
| HYBRID-MLP [walch] | loso | 31 (0) | 0 | 31 | 0.284 ± 0.161 | 0.291 | 0.287 | 0.538 | 0.472 | 0.294 | 0.604 | 0.484 | 0.507 | 37.9 | 41.0 | 22.3 |
| HYBRID-MLP [walch+bid-mask] | loso | 31 (0) | 0 | 31 | 0.334 ± 0.118 | 0.350 | 0.341 | 0.544 | 0.500 | 0.353 | 0.576 | 0.488 | 0.581 | 58.7 | 54.0 | 18.5 |
| HYBRID-MLP [walch+bid-zero] | loso | 31 (0) | 0 | 31 | 0.336 ± 0.156 | 0.310 | 0.345 | 0.556 | 0.511 | 0.367 | 0.592 | 0.519 | 0.566 | 46.6 | 47.8 | 21.4 |
| **HYBRID-MLP [walch+bid-zero] + b (default)** | loso | 31 (0) | 0 | 31 | 0.339 ± 0.169 | 0.354 | 0.350 | 0.612 | 0.522 | 0.364 | 0.692 | 0.470 | 0.561 | 33.1 | 40.8 | 19.4 |

#### instantaneous-psg

| Estimator [config] | evaluation | nights (skipped) | RULES fallback | subjects | κ mean ± sd | κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE | F1 LIGHT | F1 DEEP | F1 REM | abs. err. min DEEP | REM | AWAKE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULES | untrained | 31 (0) | 0 | 31 | 0.084 ± 0.076 | 0.107 | 0.082 | 0.483 | 0.281 | 0.193 | 0.669 | 0.109 | 0.153 | 35.8 | 78.4 | 43.7 |
| HMM-table | untrained | 31 (0) | 0 | 31 | 0.080 ± 0.104 | 0.087 | 0.079 | 0.367 | 0.302 | 0.227 | 0.503 | 0.133 | 0.346 | 48.1 | 40.2 | 50.8 |
| HMM-trained [walch] | loso | 31 (0) | 0 | 31 | 0.065 ± 0.083 | 0.039 | 0.061 | 0.254 | 0.252 | 0.238 | 0.209 | 0.287 | 0.274 | 241.6 | 58.8 | 27.9 |
| HMM-trained [walch+bidsleep] | loso | 31 (0) | 0 | 31 | 0.120 ± 0.133 | 0.100 | 0.112 | 0.355 | 0.314 | 0.233 | 0.451 | 0.307 | 0.265 | 140.7 | 47.9 | 29.7 |
| HYBRID-LR [walch] | loso | 31 (0) | 0 | 31 | 0.309 ± 0.158 | 0.298 | 0.310 | 0.520 | 0.501 | 0.449 | 0.547 | 0.488 | 0.519 | 47.4 | 62.3 | 24.3 |
| HYBRID-LR [walch+bid-mask] | loso | 31 (0) | 0 | 31 | 0.282 ± 0.105 | 0.276 | 0.288 | 0.481 | 0.465 | 0.400 | 0.484 | 0.450 | 0.525 | 69.1 | 62.5 | 25.4 |
| HYBRID-LR [walch+bid-zero] | loso | 31 (0) | 0 | 31 | 0.287 ± 0.107 | 0.274 | 0.292 | 0.484 | 0.469 | 0.412 | 0.487 | 0.450 | 0.528 | 69.2 | 61.3 | 25.2 |
| HYBRID-MLP [walch] | loso | 31 (0) | 0 | 31 | 0.268 ± 0.148 | 0.270 | 0.265 | 0.510 | 0.479 | 0.434 | 0.564 | 0.442 | 0.475 | 32.2 | 53.2 | 20.5 |
| HYBRID-MLP [walch+bid-mask] | loso | 31 (0) | 0 | 31 | 0.319 ± 0.126 | 0.335 | 0.324 | 0.523 | 0.503 | 0.448 | 0.542 | 0.467 | 0.556 | 55.6 | 57.0 | 17.8 |
| HYBRID-MLP [walch+bid-zero] | loso | 31 (0) | 0 | 31 | 0.341 ± 0.124 | 0.362 | 0.343 | 0.537 | 0.518 | 0.456 | 0.558 | 0.498 | 0.561 | 52.1 | 50.8 | 22.5 |

#### mean2min-psg

| Estimator [config] | evaluation | nights (skipped) | RULES fallback | subjects | κ mean ± sd | κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE | F1 LIGHT | F1 DEEP | F1 REM | abs. err. min DEEP | REM | AWAKE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULES | untrained | 31 (0) | 0 | 31 | 0.103 ± 0.098 | 0.117 | 0.100 | 0.483 | 0.297 | 0.213 | 0.667 | 0.127 | 0.179 | 36.7 | 77.2 | 43.5 |
| HMM-table | untrained | 31 (0) | 0 | 31 | 0.091 ± 0.110 | 0.097 | 0.092 | 0.358 | 0.313 | 0.267 | 0.467 | 0.154 | 0.365 | 50.1 | 40.1 | 55.4 |
| HMM-trained [walch] | loso | 31 (0) | 0 | 31 | 0.072 ± 0.099 | 0.051 | 0.069 | 0.260 | 0.255 | 0.242 | 0.228 | 0.289 | 0.261 | 250.0 | 60.6 | 31.4 |
| HMM-trained [walch+bidsleep] | loso | 31 (0) | 0 | 31 | 0.132 ± 0.139 | 0.153 | 0.121 | 0.350 | 0.323 | 0.252 | 0.426 | 0.295 | 0.318 | 147.0 | 38.2 | 30.9 |
| HYBRID-LR [walch] | loso | 31 (0) | 0 | 31 | 0.312 ± 0.172 | 0.310 | 0.322 | 0.542 | 0.504 | 0.428 | 0.606 | 0.480 | 0.503 | 60.3 | 50.3 | 26.6 |
| HYBRID-LR [walch+bid-mask] | loso | 31 (0) | 0 | 31 | 0.302 ± 0.138 | 0.293 | 0.310 | 0.511 | 0.489 | 0.415 | 0.539 | 0.462 | 0.540 | 75.3 | 41.7 | 24.5 |
| HYBRID-LR [walch+bid-zero] | loso | 31 (0) | 0 | 31 | 0.319 ± 0.143 | 0.333 | 0.327 | 0.529 | 0.502 | 0.423 | 0.567 | 0.467 | 0.550 | 74.1 | 36.4 | 25.5 |
| HYBRID-MLP [walch] | loso | 31 (0) | 0 | 31 | 0.312 ± 0.142 | 0.318 | 0.322 | 0.555 | 0.509 | 0.412 | 0.626 | 0.497 | 0.500 | 38.2 | 42.2 | 19.5 |
| HYBRID-MLP [walch+bid-mask] | loso | 31 (0) | 0 | 31 | 0.375 ± 0.153 | 0.386 | 0.385 | 0.582 | 0.551 | 0.470 | 0.616 | 0.513 | 0.605 | 48.7 | 40.0 | 21.0 |
| HYBRID-MLP [walch+bid-zero] | loso | 31 (0) | 0 | 31 | 0.359 ± 0.136 | 0.369 | 0.368 | 0.561 | 0.539 | 0.468 | 0.582 | 0.515 | 0.590 | 51.5 | 44.6 | 21.1 |

### 13.5 BIDSleep (EEG headband, secondary) and external results

`loso` = BIDSleep subject held out of a pooled Walch + BIDSleep configuration (its other nights never in training);
**`external`** = BIDSleep scored with the model trained on all 31 Walch subjects only (no BIDSleep data in training);
`untrained` = RULES / HMM-table. Labels come from a Dreem 2 headband, not PSG. With `walch+bid-zero` the BIDSleep
nights are scored with exactly the feature coding they were trained with and that the app uses.

#### instantaneous

| Estimator [config] | evaluation | nights (skipped) | RULES fallback | subjects | κ mean ± sd | κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE | F1 LIGHT | F1 DEEP | F1 REM | abs. err. min DEEP | REM | AWAKE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULES | untrained | 253 (0) | 0 | 47 | 0.047 ± 0.053 | 0.037 | 0.046 | 0.408 | 0.254 | 0.174 | 0.587 | 0.123 | 0.130 | 47.0 | 93.3 | 36.8 |
| HMM-table | untrained | 253 (0) | 1 | 47 | 0.053 ± 0.075 | 0.045 | 0.050 | 0.330 | 0.291 | 0.214 | 0.434 | 0.179 | 0.337 | 30.0 | 43.0 | 48.8 |
| HMM-trained [walch] | external | 253 (0) | 1 | 47 | 0.065 ± 0.049 | 0.057 | 0.063 | 0.286 | 0.258 | 0.245 | 0.198 | 0.376 | 0.212 | 212.5 | 81.6 | 15.3 |
| HMM-trained [walch+bidsleep] | loso | 253 (0) | 1 | 47 | 0.128 ± 0.070 | 0.136 | 0.131 | 0.381 | 0.341 | 0.253 | 0.446 | 0.395 | 0.269 | 105.9 | 61.7 | 17.8 |
| HYBRID-LR [walch] | external | 253 (0) | 1 | 47 | 0.277 ± 0.097 | 0.282 | 0.273 | 0.501 | 0.454 | 0.268 | 0.514 | 0.548 | 0.486 | 51.0 | 60.6 | 18.4 |
| HYBRID-LR [walch+bid-mask] | loso | 253 (0) | 1 | 47 | 0.297 ± 0.102 | 0.319 | 0.294 | 0.502 | 0.463 | 0.284 | 0.492 | 0.577 | 0.499 | 46.3 | 50.7 | 19.0 |
| HYBRID-LR [walch+bid-zero] | loso | 253 (0) | 1 | 47 | 0.295 ± 0.097 | 0.310 | 0.292 | 0.505 | 0.467 | 0.297 | 0.497 | 0.574 | 0.500 | 45.8 | 51.6 | 16.7 |
| HYBRID-MLP [walch] | external | 253 (0) | 1 | 47 | 0.271 ± 0.094 | 0.280 | 0.273 | 0.511 | 0.460 | 0.284 | 0.559 | 0.519 | 0.477 | 43.4 | 42.9 | 17.0 |
| HYBRID-MLP [walch+bid-mask] | loso | 253 (0) | 1 | 47 | 0.329 ± 0.099 | 0.337 | 0.332 | 0.532 | 0.491 | 0.303 | 0.524 | 0.601 | 0.537 | 40.5 | 50.2 | 17.5 |
| HYBRID-MLP [walch+bid-zero] | loso | 253 (0) | 1 | 47 | 0.331 ± 0.100 | 0.320 | 0.334 | 0.537 | 0.497 | 0.317 | 0.536 | 0.602 | 0.533 | 39.4 | 51.7 | 16.6 |
| **HYBRID-MLP [walch+bid-zero] + b (default)** | loso | 253 (0) | 1 | 47 | 0.302 ± 0.096 | 0.303 | 0.305 | 0.561 | 0.492 | 0.329 | 0.638 | 0.552 | 0.448 | 35.2 | 47.2 | 16.3 |

#### mean2min

| Estimator [config] | evaluation | nights (skipped) | RULES fallback | subjects | κ mean ± sd | κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE | F1 LIGHT | F1 DEEP | F1 REM | abs. err. min DEEP | REM | AWAKE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULES | untrained | 253 (0) | 0 | 47 | 0.054 ± 0.054 | 0.047 | 0.058 | 0.410 | 0.273 | 0.184 | 0.580 | 0.162 | 0.165 | 41.1 | 88.9 | 35.8 |
| HMM-table | untrained | 253 (0) | 1 | 47 | 0.068 ± 0.075 | 0.046 | 0.068 | 0.334 | 0.303 | 0.228 | 0.415 | 0.207 | 0.363 | 29.7 | 41.4 | 56.9 |
| HMM-trained [walch] | external | 253 (0) | 1 | 47 | 0.063 ± 0.047 | 0.053 | 0.063 | 0.289 | 0.261 | 0.259 | 0.226 | 0.372 | 0.188 | 214.5 | 83.5 | 15.9 |
| HMM-trained [walch+bidsleep] | loso | 253 (0) | 1 | 47 | 0.140 ± 0.074 | 0.147 | 0.144 | 0.390 | 0.357 | 0.283 | 0.439 | 0.408 | 0.298 | 107.9 | 59.8 | 16.9 |
| HYBRID-LR [walch] | external | 253 (0) | 1 | 47 | 0.265 ± 0.095 | 0.262 | 0.267 | 0.506 | 0.451 | 0.287 | 0.549 | 0.540 | 0.429 | 62.9 | 56.1 | 17.5 |
| HYBRID-LR [walch+bid-mask] | loso | 253 (0) | 1 | 47 | 0.323 ± 0.117 | 0.336 | 0.326 | 0.528 | 0.486 | 0.313 | 0.539 | 0.581 | 0.510 | 54.2 | 39.7 | 16.3 |
| HYBRID-LR [walch+bid-zero] | loso | 253 (0) | 1 | 47 | 0.323 ± 0.116 | 0.342 | 0.325 | 0.527 | 0.485 | 0.315 | 0.537 | 0.582 | 0.507 | 54.5 | 39.2 | 16.5 |
| HYBRID-MLP [walch] | external | 253 (0) | 1 | 47 | 0.258 ± 0.094 | 0.256 | 0.259 | 0.516 | 0.450 | 0.277 | 0.586 | 0.486 | 0.453 | 44.9 | 42.3 | 17.0 |
| HYBRID-MLP [walch+bid-mask] | loso | 253 (0) | 1 | 47 | 0.361 ± 0.100 | 0.376 | 0.362 | 0.557 | 0.516 | 0.341 | 0.563 | 0.609 | 0.550 | 44.6 | 39.4 | 15.0 |
| HYBRID-MLP [walch+bid-zero] | loso | 253 (0) | 1 | 47 | 0.357 ± 0.105 | 0.363 | 0.359 | 0.556 | 0.518 | 0.359 | 0.560 | 0.609 | 0.546 | 44.6 | 42.6 | 13.4 |
| **HYBRID-MLP [walch+bid-zero] + b (default)** | loso | 253 (0) | 1 | 47 | 0.325 ± 0.092 | 0.336 | 0.331 | 0.575 | 0.511 | 0.355 | 0.644 | 0.541 | 0.506 | 38.2 | 40.1 | 14.7 |

#### instantaneous-psg

| Estimator [config] | evaluation | nights (skipped) | RULES fallback | subjects | κ mean ± sd | κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE | F1 LIGHT | F1 DEEP | F1 REM | abs. err. min DEEP | REM | AWAKE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULES | untrained | 253 (0) | 0 | 47 | 0.075 ± 0.055 | 0.075 | 0.077 | 0.413 | 0.287 | 0.320 | 0.574 | 0.125 | 0.131 | 45.2 | 92.9 | 35.4 |
| HMM-table | untrained | 253 (0) | 1 | 47 | 0.080 ± 0.079 | 0.066 | 0.078 | 0.342 | 0.324 | 0.363 | 0.424 | 0.181 | 0.328 | 33.2 | 40.4 | 41.8 |
| HMM-trained [walch] | external | 253 (0) | 1 | 47 | 0.074 ± 0.050 | 0.069 | 0.071 | 0.285 | 0.270 | 0.310 | 0.199 | 0.368 | 0.203 | 220.6 | 81.7 | 28.3 |
| HMM-trained [walch+bidsleep] | loso | 253 (0) | 1 | 47 | 0.139 ± 0.067 | 0.151 | 0.142 | 0.375 | 0.359 | 0.360 | 0.430 | 0.380 | 0.266 | 117.0 | 62.5 | 25.6 |
| HYBRID-LR [walch] | external | 253 (0) | 1 | 47 | 0.309 ± 0.089 | 0.324 | 0.306 | 0.510 | 0.509 | 0.495 | 0.513 | 0.538 | 0.490 | 46.8 | 64.6 | 25.1 |
| HYBRID-LR [walch+bid-mask] | loso | 253 (0) | 1 | 47 | 0.332 ± 0.100 | 0.341 | 0.331 | 0.516 | 0.520 | 0.516 | 0.488 | 0.567 | 0.508 | 47.2 | 52.1 | 24.8 |
| HYBRID-LR [walch+bid-zero] | loso | 253 (0) | 1 | 47 | 0.332 ± 0.100 | 0.341 | 0.331 | 0.515 | 0.520 | 0.518 | 0.487 | 0.567 | 0.508 | 47.2 | 52.2 | 24.6 |
| HYBRID-MLP [walch] | external | 253 (0) | 1 | 47 | 0.304 ± 0.082 | 0.322 | 0.307 | 0.518 | 0.507 | 0.487 | 0.553 | 0.497 | 0.490 | 45.2 | 39.6 | 25.4 |
| HYBRID-MLP [walch+bid-mask] | loso | 253 (0) | 1 | 47 | 0.364 ± 0.098 | 0.369 | 0.363 | 0.543 | 0.545 | 0.540 | 0.525 | 0.570 | 0.544 | 42.2 | 52.7 | 23.5 |
| HYBRID-MLP [walch+bid-zero] | loso | 253 (0) | 1 | 47 | 0.363 ± 0.098 | 0.380 | 0.364 | 0.546 | 0.548 | 0.540 | 0.530 | 0.577 | 0.545 | 40.0 | 53.5 | 23.1 |

#### mean2min-psg

| Estimator [config] | evaluation | nights (skipped) | RULES fallback | subjects | κ mean ± sd | κ median | κ pooled | accuracy | macro-F1 | F1 AWAKE | F1 LIGHT | F1 DEEP | F1 REM | abs. err. min DEEP | REM | AWAKE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULES | untrained | 253 (0) | 0 | 47 | 0.082 ± 0.056 | 0.079 | 0.087 | 0.415 | 0.308 | 0.333 | 0.565 | 0.168 | 0.165 | 39.3 | 88.4 | 35.2 |
| HMM-table | untrained | 253 (0) | 1 | 47 | 0.097 ± 0.076 | 0.088 | 0.096 | 0.349 | 0.337 | 0.378 | 0.408 | 0.209 | 0.353 | 32.5 | 39.9 | 48.6 |
| HMM-trained [walch] | external | 253 (0) | 1 | 47 | 0.070 ± 0.047 | 0.066 | 0.069 | 0.285 | 0.266 | 0.307 | 0.223 | 0.363 | 0.171 | 223.3 | 83.3 | 30.5 |
| HMM-trained [walch+bidsleep] | loso | 253 (0) | 1 | 47 | 0.155 ± 0.066 | 0.150 | 0.158 | 0.385 | 0.374 | 0.384 | 0.424 | 0.396 | 0.293 | 121.3 | 59.3 | 25.9 |
| HYBRID-LR [walch] | external | 253 (0) | 1 | 47 | 0.306 ± 0.090 | 0.333 | 0.307 | 0.509 | 0.501 | 0.485 | 0.535 | 0.533 | 0.451 | 62.8 | 51.0 | 23.5 |
| HYBRID-LR [walch+bid-mask] | loso | 253 (0) | 1 | 47 | 0.348 ± 0.098 | 0.369 | 0.349 | 0.533 | 0.534 | 0.535 | 0.533 | 0.561 | 0.506 | 55.4 | 39.8 | 23.2 |
| HYBRID-LR [walch+bid-zero] | loso | 253 (0) | 1 | 47 | 0.351 ± 0.099 | 0.365 | 0.352 | 0.536 | 0.537 | 0.538 | 0.538 | 0.567 | 0.506 | 55.3 | 40.6 | 23.3 |
| HYBRID-MLP [walch] | external | 253 (0) | 1 | 47 | 0.309 ± 0.081 | 0.314 | 0.313 | 0.531 | 0.512 | 0.490 | 0.582 | 0.509 | 0.468 | 47.4 | 40.9 | 24.3 |
| HYBRID-MLP [walch+bid-mask] | loso | 253 (0) | 1 | 47 | 0.394 ± 0.093 | 0.416 | 0.395 | 0.570 | 0.567 | 0.549 | 0.575 | 0.587 | 0.557 | 45.2 | 43.0 | 23.8 |
| HYBRID-MLP [walch+bid-zero] | loso | 253 (0) | 1 | 47 | 0.385 ± 0.102 | 0.390 | 0.387 | 0.565 | 0.565 | 0.561 | 0.567 | 0.589 | 0.543 | 45.6 | 41.5 | 22.2 |

The Walch-only models transfer to BIDSleep with κ 0.27–0.28 (HYBRID-LR / MLP, instantaneous) — close to their Walch
LOSO values (0.29 / 0.25) — and adding BIDSleep to training raises BIDSleep κ to 0.30–0.33 (0.32–0.36 for mean2min);
`zero` and `mask` are equal there (0.331 / 0.329, 0.357 / 0.361). Proportion matching to the Walch PSG shares costs
0.03 κ on BIDSleep (0.302 / 0.325; paired −0.030 [−0.043, −0.016] / −0.032 [−0.045, −0.019]) and leaves its summed
minute error about unchanged (167 / 149 vs 171 / 157 min), because the headband-scored BIDSleep nights have more N3
and REM than the PSG-scored Walch nights (section 13.10).

### 13.6 Improvement experiments (pre-registered)

Pre-registration v1, verbatim from `tools/train/out/commands.log` (2026-10-13T05:42:00+02:00, written before any
HYBRID result of `loso-v1` was seen; the HYBRID-LR results of the Walch-only smoke run `smoke-walch-lr`, 05:24–05:26,
had been seen):

> E1 class weighting: retrain the chosen (classifier, training config) of loso-v1 with --class-weight balanced
> (sample weights n/(4 n_c); exported bias shifted back to the natural class priors), same LOSO protocol
> (--selection per-group, same grids / seeds), variants instantaneous + mean2min only.
> E2 MLP regularization: HYBRID-MLP with --mlp-alpha 1e-2 (100x the default L2 1e-4) on the training config chosen
> for the MLP in loso-v1, same protocol, variants instantaneous + mean2min only.
> Adoption rule (both): adopt only if the worse-variant (instantaneous / mean2min) mean per-subject Cohen's kappa on
> the Walch held-out subjects exceeds that of the loso-v1 default HYBRID configuration; report either way.

In `loso-v1` (retired configuration, section 13.9) E1 scored 0.335 < 0.347 and was not adopted, E2 0.361 > 0.347 and
was adopted (+0.014, 95 % CI [−0.023, 0.049]: within noise). Pre-registration v2 (07:42:29+02:00, before any `loso-v2`
result, **not blind** — the v1 outcomes were known; full text in the log): the same two experiments with the same
adoption rule on the `loso-v2` selection (if both had won, the higher one; no combination run), Walch subjects held
out, the headline variants; seeds and sensitivity analyses as reporting only.

| experiment | change | κ instantaneous | κ mean2min | **κ worse** | κ pooled | accuracy | macro-F1 | F1 W / L / D / R | abs. error min D / R / W | paired Δκ vs reference, inst / mean2min [95 % CI] | decision |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `loso-v2` (reference) | MLP, L2 α = 1e-4, no class weights | 0.334 ± 0.133 | 0.336 ± 0.156 | **0.334** | 0.333 | 0.540 | 0.498 | 0.35 / 0.57 / 0.50 / 0.57 | 52.4 / 53.5 / 18.0 | — | — |
| E1 `exp-v2-e1-balanced` | balanced class weights (n / 4n_c; exported bias shifted back to the natural priors) | 0.307 ± 0.109 | 0.329 ± 0.143 | **0.307** | 0.307 | 0.511 | 0.474 | 0.33 / 0.53 / 0.49 / 0.54 | 54.3 / 57.9 / 24.7 | −0.026 [−0.060, 0.005] / −0.007 [−0.036, 0.024] | not adopted |
| E2 `exp-v2-e2-mlp-alpha` | MLP L2 α = 1e-2 (100 × stronger) | 0.300 ± 0.117 | 0.355 ± 0.135 | **0.300** | 0.295 | 0.504 | 0.469 | 0.34 / 0.51 / 0.48 / 0.54 | 50.3 / 65.7 / 21.2 | −0.033 [−0.059, −0.009] / +0.019 [−0.008, 0.048] | not adopted |

Neither experiment beats the reference, so the shipped model is the α = 1e-4 MLP of `loso-v2`. E2 now lowers the
instantaneous variant and raises mean2min — the same L2 change that gained a little in v1 — and single subjects move
by about ± 0.07–0.09 κ (sd of the paired differences): with one seed per setting, differences of a few hundredths
between training settings are noise (section 13.7). (`tools/train/out/compare-v2-experiments.json`, `train.py
compare --runs loso-v2,exp-v2-e1-balanced,exp-v2-e2-mlp-alpha`.) A third pre-registered experiment, on the stage
proportions of the decoder, was adopted (section 13.10).

### 13.7 How much of the score is seed and selection luck (reporting only)

The adopted setting re-run with three other seeds (`--seed` changes the inner-fold assignment, hence the per-group
selections, and the MLP's initialization and early-stopping split; Walch subjects held out, headline variants, the
same protocol otherwise; `train.py compare --runs loso-v2,seed-v2-s1,seed-v2-s2,seed-v2-s3`):

| run | seed | κ instantaneous | κ mean2min | κ worse | decoder chosen (most frequent) | paired Δκ vs seed 0, inst / mean2min [95 % CI] |
|---|---|---|---|---|---|---|
| `loso-v2` (shipped) | 0 | 0.334 | 0.336 | **0.334** | EXPLICIT, 0.5, 0 | — |
| `seed-v2-s1` | 1 | 0.326 | 0.342 | **0.326** | GEOMETRIC, 0.5, 0 | −0.007 [−0.045, 0.029] / +0.006 [−0.021, 0.034] |
| `seed-v2-s2` | 2 | 0.291 | 0.356 | **0.291** | EXPLICIT, 0.5, 0 | −0.043 [−0.079, −0.007] / +0.020 [−0.002, 0.044] |
| `seed-v2-s3` | 3 | 0.318 | 0.333 | **0.318** | EXPLICIT, 0.5, 0 | −0.016 [−0.052, 0.021] / −0.003 [−0.035, 0.030] |
| **mean ± sample sd** | | 0.317 ± 0.019 | 0.342 ± 0.010 | **0.317 ± 0.019** | | |

The shipped seed is the best of the four, so its 0.334 is ≈ 0.02 above what the procedure delivers on average; in
addition, the configuration, the classifier and the experiments were chosen by the same outer-LOSO score (as the
specification's rule requires), and their differences are within noise (sections 13.2, 13.6), which makes the
winner's score optimistic again. **Conservative estimate for this training procedure: κ ≈ 0.32 on the worse variant
(≈ 0.34 on mean2min).** Changing only the seed moves single subjects by ± 0.07–0.11 κ (sd of the paired
differences). The decoder hyper-parameters within a configuration are chosen by nested CV only. The seeds were run
before proportion matching (section 13.10), i.e. without b; they measure the classifier's seed variance.

### 13.8 Session boundaries and steps (reporting only)

The OHealth-like session window is cut from the PSG labels (sleep onset − 5 min … final awakening + 5 min), and the
hybrid features include the elapsed and remaining session time, so the model is told where sleep begins and ends;
the bundle's initial-stage distribution is 98 % AWAKE. A watch detects the session itself. `train.py sensitivity
--run loso-v2` evaluates the outer fold models of the shipped setting (each held-out subject with its own fold model
and nested-CV decoder parameters) on the Walch nights with the session window moved, rebuilt from the `-psg` night
files exactly as `prepare.py` builds the OHealth-like files (heart rate and steps of the window ± 30 min, the label
epochs inside the window; the unshifted rebuild reproduces the original nights and the run's numbers exactly).
Mean per-subject κ, paired Δ vs `base` with 95 % bootstrap CI:

| scenario | κ instantaneous | Δ instantaneous [95 % CI] | κ mean2min | Δ mean2min [95 % CI] | κ worse |
|---|---|---|---|---|---|
| original | 0.334 ± 0.133 | — | 0.336 ± 0.156 | — | 0.334 |
| base (reference: the OHealth-like window rebuilt from the -psg files) | 0.334 ± 0.133 | — | 0.336 ± 0.156 | — | 0.334 |
| narrow-10 (start 10 min later, end 10 min earlier) | 0.313 ± 0.137 | -0.020 [-0.037, -0.005] | 0.324 ± 0.143 | -0.012 [-0.026, 0.001] | 0.313 |
| narrow-20 (start 20 min later, end 20 min earlier) | 0.292 ± 0.137 | -0.042 [-0.061, -0.023] | 0.309 ± 0.145 | -0.027 [-0.056, -0.002] | 0.292 |
| late-start-15 (start 15 min later) | 0.309 ± 0.145 | -0.025 [-0.046, -0.003] | 0.313 ± 0.144 | -0.023 [-0.039, -0.009] | 0.309 |
| early-end-15 (end 15 min earlier) | 0.332 ± 0.135 | -0.002 [-0.015, 0.012] | 0.344 ± 0.139 | 0.007 [-0.006, 0.023] | 0.332 |
| wide-10 (start 10 min earlier, end 10 min later: more in-bed wake) | 0.343 ± 0.134 | 0.009 [-0.002, 0.021] | 0.351 ± 0.129 | 0.015 [-0.006, 0.038] | 0.343 |
| wide-20 (start 20 min earlier, end 20 min later) | 0.348 ± 0.130 | 0.014 [0.003, 0.026] | 0.354 ± 0.139 | 0.018 [0.002, 0.038] | 0.348 |
| jitter-15 (start and end each moved uniformly within ±15 min) | 0.337 ± 0.129 | 0.004 [-0.018, 0.025] | 0.346 ± 0.133 | 0.010 [-0.009, 0.034] | 0.337 |
| no-steps (OHealth-like window, all steps removed) | 0.331 ± 0.132 | -0.002 [-0.007, 0.002] | 0.333 ± 0.153 | -0.003 [-0.010, 0.003] | 0.331 |

- **Late sleep-onset / early wake detection costs 0.01–0.04 κ**: a session that starts 10–20 min after sleep onset
  and ends 10–20 min before the final awakening loses 0.012–0.042 (the instantaneous variant more); a late start
  alone loses ≈ 0.02, an early end nothing measurable.
- **Wider windows (more pre-sleep / post-sleep wake) do not hurt** (+0.01–0.02; these extra epochs are mostly easy
  wake), and independent ±15-min jitter of both ends is neutral (+0.004 / +0.010, CIs include 0).
- **Steps hardly matter**: removing every step from the Walch nights changes κ by −0.002 / −0.003 (CIs include 0).
  This also bounds the effect of the pedometer having been tuned on the Walch PSG labels (section 16).
- Not done: training with randomly jittered windows (so that the model relies less on exact boundaries and on the
  AWAKE start), which would be the next step if real watch sessions turn out to start late.
- These scenarios used the fold models without proportion matching (b, section 13.10); b is a per-stage constant and
  does not change how the features respond to moved boundaries.

### 13.9 Superseded results (`loso-v1`, retired configuration)

An earlier version of this report shipped HYBRID-MLP α = 1e-2 [`walch+bid-nonstep`] (κ 0.361, GEOMETRIC decoder) and
the supervised HMM estimates as HMM defaults (runs `loso-v1`, `exp-e1-balanced`, `exp-e2-mlp-alpha`,
`loso-v1-final`). An audit found that `walch+bid-nonstep` set the step features of the BIDSleep nights to *missing*
in training (0 after standardization), while `algo-cli` and the app compute *0 steps* for a night without step
records (standardized −0.04 for `stepsLog1p`, −0.11 for `stepsWin5Log1p`). The MLP learned that difference as a
Walch-vs-BIDSleep switch — on the v1 bundle, changing only this coding changed the most probable stage of 16–17 % of
the epochs — so its BIDSleep results were produced in a regime it had learned from Walch rows only, and the stated
reason for nonstep beating mask (wake information in the Walch step features; only 25 epochs have steps) was not
supported. The configuration is retired (the pipeline now checks that training uses exactly the inference feature
values), the trained HMM is gated (section 12.3), the time prior is estimated, and everything above was re-run
(`loso-v2`). The v1 numbers belong to an invalid configuration and are not used for any default.

### 13.10 Stage proportions: prior exponent and proportion matching (pre-registered v3, adopted)

**Problem.** The `loso-v2` default divides the classifier's posteriors by the class priors (π = 6 / 48 / 19 / 27 %
AWAKE / LIGHT / DEEP / REM, dominated by the BIDSleep training rows) and decodes with w_e = 0.5. On Walch it predicted
25 % DEEP / 34 % REM / 34 % LIGHT vs PSG 14 / 23 / 57 %, per-night errors of 52 min (DEEP), 54 (REM) and 95 (LIGHT),
and 12 of 31 nights tripped the sanity flag: users would see inflated deep sleep (e.g. in Sleep as Android).

**Pre-registration v3** (written to `tools/train/out/prereg-bias.md` and appended to `tools/train/out/commands.log` at
2026-10-13T09:01:31+02:00, before any candidate code was written or run; the `loso-v2` baseline numbers were known;
committed verbatim as [docs/prereg/prereg-bias.md](docs/prereg/prereg-bias.md), SHA-256 `75499301…c288`, with the
worktree's log as `docs/prereg/commands-train2.log`; `tools/train/tests/test_prereg.py` checks that the two
agree).
Decoder term of section 7, e = w_e · (ln p − β · ln π) + b; everything else of `loso-v2` unchanged (classifier and
fold bundles of the reproduction run `bias-base`, per-group nested selection of the decoder grid θ_g); β and b only.

- **(a) prior exponent**: β ∈ {1, 0.75, 0.5, 0.25, 0} chosen per (variant, outer group) in the inner CV at θ_g by the
  adoption rule's objective (qualify: inner κ ≥ inner κ(β = 1) − 0.01; lowest inner E; ties → higher κ, larger β).
- **(b) proportion matching**: β = 1, b fitted per (variant, outer group) by the fixed point of section 7 on the inner
  out-of-fold decodes of the group's Walch selection subjects at θ_g (the training fold only; never the group's own
  subjects).
- **(c) both**: b(β) fitted for every β of the grid, β chosen by the (a) rule on (β, b(β)) (b in-sample), BASE if
  nothing qualifies. Stated beforehand: since −w_e · β · ln π_s is itself a per-stage constant, (c) was expected to
  coincide with (b).
- E = mean over nights of Σ over DEEP + REM + AWAKE + LIGHT of |predicted − true| minutes (scored 30-s chunks).
  **Adoption rule (verbatim):** "On Walch held-out subjects, worse sampling variant: adopt the candidate with the
  lowest mean absolute per-night minutes error summed over DEEP+REM+AWAKE+LIGHT among those whose mean per-subject
  kappa ≥ (baseline kappa − 0.01); ties → higher kappa; if none qualifies, keep the baseline." (κ worse = min, E worse
  = max over instantaneous / mean2min; ties = |ΔE| < 0.05 min.)

**Results, Walch (PSG) held-out subjects** (paired per-subject differences vs BASE with 95 % bootstrap CIs, 10 000
resamples; minute errors and accuracy of the worse-E variant):

| candidate | κ inst. / mean2min | **κ worse** | E inst. / mean2min (min) | **E worse** | abs. error min D / R / W / L | accuracy | sanity-flagged nights inst. / mean2min (of them DEEP < 3 %) | Δκ vs BASE, inst. / mean2min | ΔE vs BASE, inst. / mean2min | decision |
|---|---|---|---|---|---|---|---|---|---|---|
| BASE (`loso-v2`, β = 1, b = 0) | 0.334 / 0.336 | **0.334** | 219.2 / 201.4 | **219.2** | 52.4 / 53.5 / 18.0 / 95.3 | 0.540 | 12 / 11 of 31 (0 / 0) | — | — | baseline |
| (a) β | 0.360 / 0.350 | **0.350** | 141.5 / 156.6 | **156.6** | 36.3 / 41.7 / 18.7 / 60.0 | 0.600 | 3 / 5 (0 / 0) | +0.027 [−0.017, 0.066] / +0.014 [−0.005, 0.032] | −77.7 [−112.3, −40.6] / −44.8 [−71.1, −19.6] | qualifies |
| **(b) b** | 0.348 / 0.339 | **0.339** | 128.3 / 146.8 | **146.8** | 33.1 / 40.8 / 19.4 / 53.5 | 0.612 | 2 / 7 (0 / 3) | +0.014 [−0.032, 0.057] / +0.003 [−0.037, 0.038] | −90.8 [−133.1, −47.9] / −54.6 [−89.5, −18.6] | **adopted** |
| (c) β + b | 0.360 / 0.347 | **0.347** | 147.7 / 166.7 | **166.7** | 36.3 / 44.3 / 20.6 / 65.5 | 0.590 | 5 / 9 (0 / 1) | +0.026 [−0.011, 0.063] / +0.011 [−0.009, 0.032] | −71.4 [−113.0, −30.0] / −34.7 [−62.2, −8.6] | qualifies |

All four qualify (threshold 0.334 − 0.01 = 0.324); (b) has the lowest worse-variant E → **adopted**. Pooled stage
shares, % AWAKE / LIGHT / DEEP / REM (PSG: 6.3 / 57.0 / 14.1 / 22.6 instantaneous, 6.3 / 56.8 / 14.2 / 22.7 mean2min):

| candidate | instantaneous | mean2min |
|---|---|---|
| BASE | 7.3 / 34.1 / 25.0 / 33.6 | 6.8 / 37.2 / 24.6 / 31.4 |
| (a) β | 2.4 / 53.9 / 18.2 / 25.5 | 2.9 / 51.1 / 20.6 / 25.3 |
| **(b) b** | 5.8 / 59.1 / 14.8 / 20.3 | 6.1 / 57.4 / 12.8 / 23.7 |
| (c) β + b | 5.9 / 54.4 / 16.4 / 23.2 | 6.4 / 48.1 / 17.5 / 28.1 |

**BIDSleep (EEG headband, secondary)**, 253 nights / 47 subjects, true shares 6.0 / 46.5 / 19.9 / 27.5 %:

| candidate | κ inst. / mean2min | E inst. / mean2min | predicted shares inst. | sanity-flagged inst. / mean2min | of them DEEP > 35 % / REM > 40 % / DEEP < 3 %, inst.; mean2min | Δκ vs BASE | ΔE vs BASE |
|---|---|---|---|---|---|---|---|
| BASE | 0.331 / 0.357 | 171.4 / 157.2 | 6.3 / 33.0 / 25.8 / 34.9 | 103 / 100 | 36 / 76 / 3; 53 / 53 / 0 | — | — |
| (a) β | 0.331 / 0.358 | 146.7 / 136.5 | 2.1 / 54.7 / 19.2 / 24.0 | 29 / 31 | 6 / 17 / 6; 12 / 18 / 3 | +0.000 [−0.012, 0.013] / +0.001 [−0.012, 0.012] | −23.7 [−37.8, −10.4] / −22.5 [−35.9, −9.3] |
| **(b) b** | 0.302 / 0.325 | 166.5 / 149.0 | 5.5 / 61.1 / 14.5 / 19.0 | 18 / 28 | 1 / 5 / **12**; 0 / 14 / **15** | −0.030 [−0.043, −0.016] / −0.032 [−0.045, −0.019] | −3.8 [−21.3, 12.3] / −9.7 [−27.1, 7.9] |
| (c) β + b | 0.315 / 0.340 | 164.0 / 146.8 | 5.7 / 55.5 / 16.7 / 22.1 | 30 / 52 | 5 / 17 / 9; 21 / 26 / 7 | −0.016 [−0.028, −0.005] / −0.017 [−0.027, −0.007] | −7.2 [−22.8, 6.9] / −10.5 [−24.2, 3.2] |

(Sanity codes `SANITY_DEEP_HIGH` / `SANITY_REM_HIGH` / `SANITY_DEEP_LOW`; a night can carry more than one.) On
BIDSleep b removes most DEEP / REM over-calls but turns the flags into a new failure: 12 / 15 of the 253 nights get
less than 3 % DEEP (BASE: 3 / 0), the majority of b's flagged nights there; the headband labels have 20 % N3.

- **All three corrections reduce the bias; none was shown to keep κ.** On Walch the summed per-night error falls by
  35–91 min, the sanity flags from 12 / 11 to 2–9 nights, and accuracy rises from 0.54 to 0.59–0.62, because the
  epochs moved to LIGHT are mostly LIGHT. κ moves by +0.003 … +0.027, all CIs include 0, and for the adopted (b) the
  CI on mean2min is wide (+0.003 [−0.037, 0.038]; its lower end is far beyond the rule's −0.01 margin): (b) qualified
  on the point estimates (0.339 ≥ 0.324), as the rule specifies, which is not evidence that no κ is lost. On BIDSleep
  (b) costs κ −0.030 [−0.043, −0.016] / −0.032 [−0.045, −0.019]. In the inner CV, (b)'s inner κ is more than 0.01
  below BASE's in 4 of the 10 selections (instantaneous 0.285 vs 0.312 and 0.332 vs 0.350, mean2min 0.309 vs 0.354
  and 0.304 vs 0.328); there b is in-sample (flattering (b)) and BASE's inner κ is the winning decoder choice's on the
  same nights (flattering BASE). (a) and (c) qualify inside the inner CV by construction.
- **(b) vs (a) is not decided by the data**: paired (b) − (a) on Walch Δκ −0.013 [−0.036, 0.010] / −0.011
  [−0.046, 0.022], ΔE −13.2 [−33.3, 6.1] / −9.8 [−34.8, 16.9] (reporting only, not part of the rule; section
  "Paired per-subject differences between the corrections" of `bias-base/bias/report.md`, `pairwise` in its
  `summary.json`, regenerated by `train.py bias-report`); the rule picks (b). (b) matches each stage separately; (a)
  can only move along ln π, which penalizes the rarest stage AWAKE most (2–3 % predicted vs 6 %). On the
  headband-scored BIDSleep nights (a) is better (Δκ (b) − (a) −0.030 [−0.039, −0.021] / −0.032 [−0.044, −0.020], ΔE
  +20.0 [12.6, 27.7] / +12.8 [3.1, 22.2]): b is fitted to the Walch PSG shares (14 % DEEP), BIDSleep has 20 % N3 and
  28 % REM, so (b) under-calls DEEP and REM there.
- **(c) did not coincide with (b)**, contrary to the stated expectation, **mainly because (c) fell back to BASE**: in
  3 of the 10 inner selections (instantaneous 88ebca350d3d, mean2min 27aa53fb40d2 and 3c9ea7b7f463; 47 of the 156
  outer folds) no (β, b(β)) reached inner κ ≥ BASE − 0.01, so, as pre-registered, those groups kept BASE without any
  b — 7 of the 31 Walch nights on instantaneous and 12 on mean2min (the worse-E variant). There (c) has BASE's E
  (207.6 / 213.9 min vs (b) 115.3 / 171.2); on the other nights (c) and (b) are close (130.3 vs 132.1 instantaneous,
  136.8 vs 131.4 mean2min), so the fallbacks explain most of the gap in E worse (167 vs 147). The rest comes from
  the fixed point: the equivalence of β and b is exact only at its end point, the 8-decode budget left the 50 fits at
  D = Σ|T − P| of 0.001–0.12 depending on β, and (c) chose among those end points by their in-sample E. (c)'s β:
  1 with b in 16 folds, BASE fallback (β = 1, no b) in 47, 0.75 in 31, 0.5 in 47, 0 in 15 (`choiceCounts` and
  `fallbacks` in `bias-base/bias/summary.json`).
- Inner choices of (a): β = 0.75 in 16 of 156 outer folds, 0.5 in 46, 0.25 in 46, 0 in 48 — no stable value. Fitted b
  per group (b): LIGHT +0.18 … +0.96, DEEP −0.18 … −0.86, AWAKE −0.31 … +0.19, REM −0.06 … +0.16; the fixed point
  reached D ≤ 0.043 in 9 of the 10 groups (≤ 0.027 in 8; η halved to 0.5 after the first step at w_e = 0.5, where
  η = 1 over-shoots) and D = 0.123 in one GEOMETRIC group whose decoded LIGHT share oscillated between ≈ 48.5 and
  62.5 % over its accepted steps 3–6.
- **Post-result code change** (disclosed in `commands.log` at the audit): at 09:19:19, after the experiment's report
  had been read, `final_model`'s `--selection-from` check was narrowed from the whole `selection.classifier` of the
  two summaries to its `chosen` classifier and `config` (with the decoder parameters, as before), because `loso-v2`'s
  dict also holds the scores of configurations `bias-base` did not train. It has no effect on the candidates, the β /
  b fits or the decision; no other change to `pulsetrain/bias.py` was made between the experiment and its commit.
  The later audit fixes only add reporting (pairwise comparisons, per-code sanity flags, the fallback split, the
  inner-CV κ losses, `bias-report`) and the opt-in `--allow-other-build`; re-rendered from the stored per-night
  results, every earlier number of `summary.json` is unchanged.
- **Final model**: b fitted with the same fixed point on the LOSO out-of-fold decodes of all 31 Walch subjects (both
  variants pooled) at the final decoder (EXPLICIT, 0.5, 0, β = 1): b = (+0.090, +0.216, −0.318, +0.012), D = 0.006
  after 8 decodes; on those decodes (b in-sample, reporting only) κ 0.355 / 0.347, E 124 / 144 min, shares 6.6 / 56.7
  / 13.3 / 23.4 % (instantaneous). The validated numbers of the shipped default are the (b) row above (b fitted per
  outer group, never on the evaluated subject).

Commands: `train.py run --run bias-base --configs walch+bid-zero --classifiers mlp --estimators HYBRID --variants
instantaneous,mean2min --selection per-group --keep-inner`, `train.py bias --run bias-base` (report
`tools/train/out/bias-base/bias/report.md`, per-group fits `decisions.json`), `train.py bias-final --run bias-base
--selection-from <loso-v2>/summary.json --install-model`; after the audit `train.py bias-report --run bias-base`
(re-renders the summary / report from `results.jsonl` and `decisions.json`; originals kept in
`bias-base/bias/orig-0918/`) and `train.py bias --run bias-base --out-name bias-033541ae --allow-other-build` (the
whole experiment on the final build: identical `results.jsonl` and `decisions.json`).

## 14. Published results for context

None of these is directly comparable (other inputs, class sets, protocols); they calibrate expectations. Read from the
cited papers / repositories (`tools/train/DATASETS.md`, research notes); "4-class" = W / light / deep / REM.

| study | data | classes | inputs | protocol | accuracy | Cohen's κ |
|---|---|---|---|---|---|---|
| Walch et al. 2019, Table 6 | sleep-accel (this dataset), 31 subjects | 3 (W / NREM / REM) | HR only (≈ 5-s PPG) | Monte-Carlo CV, 20 × ≈ 70/30 subject splits, thresholds chosen with ground truth (optimistic) | NN 0.698, RF 0.676 | NN 0.04, RF 0.165, kNN 0.108, LR 0.033 |
| same | same | 3 | motion + HR | same | NN 0.723, RF 0.695 | NN 0.256, RF 0.293 |
| same | same | 3 | motion + HR + clock proxy | same | NN 0.723, RF 0.686 | RF 0.302 (best), NN 0.277 |
| Walch et al. 2019, Table 9 | trained on sleep-accel, tested on MESA (188) | 3 | actigraphy + pulse-oximeter HR + clock | external | 0.686 | 0.403 (HR only: 0.243) |
| Fonseca et al. 2017 (cited by Walch) | SIESTA-trained, wrist PPG + accelerometer | 4 | PPG HRV + motion | independent test set | 59 % | 0.42 |
| Beattie et al. 2017 (cited by Walch) | Fitbit, home PSG | 4 | raw motion + PPG | — | — | 0.52 |
| Sridhar et al. 2020, npj Digit Med | SHHS + MESA, > 10 000 nights | 4 | ECG beat-to-beat heart rate (upper bound) | held-out SHHS | 0.77 | 0.66 |
| Song et al. 2026, IEEE TBME | BIDSleep (this dataset) | 4 | instantaneous HR + accelerometer, seq2seq LSTM | — | 0.710 | not reported |
| unreviewed repository (sleep-accel-dl, 2026) | sleep-accel | 4 | HR only (≈ 5-s) | subject-wise 5-fold | 0.580 | 0.327 |

Trivial baselines on the Walch labels: always LIGHT = 55–57 % accuracy (4-class) with κ = 0. Accuracy is therefore not
a useful headline; kappa and macro-F1 are. With heart rate **every 2 minutes** (≈ 24 × sparser than the ≈ 5-s Apple
Watch series in these studies), **no accelerometer** and no clock time, the research notes expected a 4-class κ of ≈
0.2–0.3; the 0.34 obtained here (section 13; 0.32 averaged over seeds without b) is at the upper end of that, helped
by the pooled BIDSleep training data, the HMM / HSMM decoding and the PSG-derived session boundaries of the emulation
(sections 13.8, 16), and well below the ≈ 0.5 that would point to leakage.

## 15. Synthetic validation (regression tests only)

From `core-algo/SYNTHETIC_RESULTS.md` (`SyntheticRecoveryTest`, `HybridSyntheticRecoveryTest`): 50 synthetic nights
(seeds 1–50) from `SyntheticNightGenerator` — realistic hypnograms (4–5 cycles of 90–110 min, DEEP early, REM
lengthening towards the morning, 2–4 awakenings), stage-dependent heart-rate offsets, AR(1) noise, a 2-minute lag,
2-minute sampling with dropouts and outliers — scored like the real data:

| estimator (parameters pinned as in the tests) | mean κ | median κ | min κ | pooled accuracy |
|---|---:|---:|---:|---:|
| HMM, physiology table (50 nights) | 0.4927 | 0.4961 | 0.3336 | 0.6556 |
| RULES (50 nights) | 0.3975 | 0.3903 | 0.2376 | 0.6770 |
| HYBRID / EXPLICIT, synthetic bundle (30 held-out nights) | 0.7349 | 0.7304 | 0.5663 | 0.8377 |
| HYBRID / GEOMETRIC, synthetic bundle (30 held-out nights) | 0.7241 | 0.7233 | 0.5625 | 0.8301 |
| HMM on the same 30 nights | 0.5065 | 0.5195 | 0.2600 | 0.6663 |
| *informational:* HMM with the shipped HMM defaults (= the physiology table, 50 nights) | 0.4927 | 0.4961 | 0.3336 | 0.6556 |
| *informational:* HYBRID with the shipped decoder defaults (EXPLICIT, 0.5, 0; 30 nights) | 0.7461 | 0.7590 | 0.5284 | 0.8493 |

Regression thresholds asserted: HMM ≥ 0.44, RULES ≥ 0.34, HMM ≥ RULES; HYBRID/EXPLICIT ≥ 0.69, HYBRID/GEOMETRIC ≥
0.68, HYBRID ≥ HMM. The tests pin the specification's HMM (`HmmParams.PHYSIOLOGY`) and the pre-training decoder values
(timePriorWeight 1, emissionWeight 1) with which the thresholds were set. Personalization on synthetic reference
nights (`PersonalCalibratorTest`, 8 training / 4 held-out nights of a "shifted person"): HYBRID κ 0.698 → 0.707 (λ =
10 h) / 0.737 (λ = 0.5 h); HMM-only 0.312 → 0.447.

**These numbers overstate the accuracy to expect on real nights.** The synthetic generator belongs to the same model
family the HMM assumes (stage-dependent heart-rate offsets, regular ultradian cycles, Gaussian noise), and the
synthetic HYBRID bundle is trained on nights of the very same generator. They are regression guards for the code
(thresholds = first observed mean − 0.05), never a basis for defaults; compare the HMM's synthetic κ ≈ 0.49 with its
real PSG κ in section 13.

## 16. Limitations

- **Two-minute heart rate only.** Stage-specific physiology lives largely at the beat-to-beat level (HRV, respiratory
  sinus arrhythmia) and in EEG; a sample every 2 minutes conveys only slow trends. Agreement with PSG is "fair" (κ ≈
  0.34; ≈ 0.32 averaged over training seeds, measured without proportion matching) and varies strongly between people
  (per-subject sample sd ≈ 0.14–0.17, range −0.03 … 0.65); single nights can be far off.
- **Stage proportions are matched on average, not per night.** Dividing the posteriors by the class priors (the
  hybrid-HMM scaled likelihood the kappa-based selection chose) over-called DEEP and REM (Walch 25 % / 34 % predicted
  vs 14 % / 23 % PSG). The shipped bundle's proportion-matching log-bias b (sections 7, 13.10) brings the pooled Walch
  shares to the PSG shares (13–15 % DEEP, 20–24 % REM, 57–59 % LIGHT) and the sanity flags from 12 / 11 to 2 / 7 of 31
  nights, with Walch κ within noise (Δκ +0.003 [−0.037, 0.038] on mean2min, so a loss of up to ≈ 0.04 is not
  excluded); but it is one population-level constant per stage: per-night minute errors remain ≈ 33 min (DEEP), 41
  (REM), 19 (AWAKE) and 38–53 (LIGHT), and a person whose true stage mix differs from the Walch cohort's (31 young
  healthy adults, PSG in the lab) is pulled towards it. On the headband-scored BIDSleep nights (more N3 and REM) b
  under-calls DEEP and REM and costs 0.03 κ, and **12–15 of the 253 nights come out with less than 3 % DEEP**
  (`SANITY_DEEP_LOW`; 3 / 0 without b): users with more N3 than the Walch cohort may see almost no deep sleep. The
  prior-exponent alternative (β) was as good on Walch and better on BIDSleep but lost by the pre-registered rule
  (section 13.10). b is fitted on out-of-fold decodes of 31 Walch nights only; it should be re-fitted whenever the
  classifier or the decoder settings change (a user who edits `emissionWeight` or `durationModel` keeps the shipped b,
  which was fitted for EXPLICIT / 0.5 / 0).
- **No HRV, no accelerometer.** OHealth writes neither RMSSD nor raw motion to Health Connect; step counts are ~0 in
  bed, so wake detection in bed relies on heart rate alone (AWAKE F1 is the weakest of the four stages). The model is
  trained with accelerometer-derived steps for Walch, which are themselves an emulation of a watch pedometer; removing
  them changes κ by < 0.005 (section 13.8).
- **Pedometer tuned on the evaluation labels.** The pedometer's bout rule was chosen by counting steps in the PSG sleep
  and wake epochs of all 31 Walch nights, including each subject that a LOSO fold later holds out — label-informed
  preprocessing outside the LOSO loop. Its effect is bounded by the no-steps evaluation above (−0.002 / −0.003 κ); a
  clean protocol would freeze the pedometer on other data (e.g. the BIDSleep accelerometer, not downloaded:
  6.35 GB at ≈ 10–20 kB/s from PhysioNet).
- **Cohorts.** Walch: 31 young, healthy adults (age 29 ± 9), one lab night each, Apple Watch Series 2/3, with people
  with sleep apnea or RBD excluded. BIDSleep: 47 healthy adults at home. Older people, sleep disorders, medication
  (e.g. beta blockers), arrhythmias or very fit athletes with low resting heart rates are not represented.
- **Labels.** Walch is PSG (AASM, R&K N4 merged into DEEP); BIDSleep labels come from a Dreem 2 EEG headband
  (automatic scoring corrected by one expert), not full PSG, and are treated as secondary.
- **Domain shift.** The model is trained on Apple Watch PPG heart rate resampled to 2 minutes. OPPO Watch X3 / OHealth
  may sample, average or filter differently (its exact 2-minute value definition is not documented, which is why both
  instantaneous and 2-minute-mean emulations are validated and the worse one is reported).
- **Session boundaries from PSG.** The emulated session starts 5 min before the PSG sleep onset and ends 5 min after
  the final awakening, and the features include the elapsed / remaining session time, so the model is told where sleep
  begins and ends (initial stage 98 % AWAKE). Real OHealth sessions come from the watch's own sleep detection. Measured
  (section 13.8): a session that starts 10–20 min late and ends 10–20 min early costs 0.01–0.04 κ, a late start alone
  ≈ 0.02; earlier starts / later ends (more in-bed wake) and ±15-min jitter do not hurt. Not done: training with
  jittered windows.
- **No absolute clock time.** The public Walch files are relative to the PSG start, so time-of-day / circadian
  features cannot be used; the time prior and time features are relative to the session start.
- **Long-term context untested.** The elevated-HR rule and the personalization (calibration, population EM) cannot be
  validated on these datasets (one night per Walch subject); they are implemented as specified and unit-tested on
  synthetic data only.
- **Naps unvalidated.** Both datasets contain whole nights only. Naps (the source setting `minSessionMin` lowered, e.g.
  to 30) are estimated by models trained on nights (elapsed / remaining-time features, HSMM durations), so their
  hypnograms carry no validated accuracy; the nap thresholds of 180 min (`baselineMinSessionMin`,
  `proportionsMinSessionMin`, section 9) are a physiological choice (shorter than any regular night, longer than
  typical naps), not fitted.
- **Supervised HMM not usable as is.** On these 2-minute data the per-stage hrNorm means are not ordered as the
  specification's HMM assumes (DEEP 1.28 > LIGHT 0.90), so the supervised estimates — although they score higher than
  the physiology table (0.117 vs 0.070) — fail the validity gate and are not the defaults; the HMM (the app's
  fallback when no usable bundle is present) therefore keeps the specification's table, and its EM adaptation and
  personalization work as specified. The HMM's cycle-phase prior and step factors were not estimated.
- **Selection and seed optimism.** Configuration, classifier, BIDSleep step handling and the experiments were chosen
  by the same outer-LOSO score, and their differences are within the paired CIs; the shipped seed scored best of four
  (0.334 vs a mean of 0.317, without b). Read 0.32 as the expected κ of the procedure. The stage-proportion candidate
  was picked from four by the outer Walch minute error, so its E (147 min) is slightly optimistic as well; its κ was
  only a constraint.
- **Nested selection mode.** The decoder hyper-parameters were chosen with the cheaper per-group nested selection
  (section 12.3), which is leak-free but uses ≈ 80 % of the training subjects for the choice and yields only 10
  distinct inner selections; EXPLICIT vs GEOMETRIC is not decided by them.

## 17. How to retrain

Everything runs inside the project through mise (Python 3.12 + venv `tools/train/.venv`, Temurin 17); no global
installs. From the repository root (`JAVA_OPTS=-Xmx2g` only caps the heap of each of the up to 24 parallel `algo-cli`
JVMs; it does not change any number):

```sh
mise exec -- pip install -r tools/train/requirements.txt          # numpy, scipy, pandas, scikit-learn, pytest
mise exec -- python -I tools/train/download.py                    # sleep-accel 1.0.0 (S3 mirror, SHA-256 checked)
mise exec -- python -I tools/train/download.py --dataset bidsleep # BIDSleep 1.0.1 hr.csv + labels.mat
mise exec -- python -I tools/train/prepare.py                     # night files, 4 variants, summary.csv
mise exec -- ./gradlew :algo-cli:installDist                      # the Kotlin features / decoders (never during a run)
export JAVA_OPTS=-Xmx2g
# 1. all estimators / configurations / classifiers / variants (selection of section 13.2)
mise exec -- python -I tools/train/train.py run --run loso-v2 --selection per-group --jobs 24
# 2. the two pre-registered experiments on the selected configuration (section 13.6)
mise exec -- python -I tools/train/train.py run --run exp-v2-e1-balanced --configs walch+bid-zero --classifiers mlp \
    --estimators HYBRID --variants instantaneous,mean2min --selection per-group --heldout-datasets walch \
    --class-weight balanced --jobs 12
mise exec -- python -I tools/train/train.py run --run exp-v2-e2-mlp-alpha --configs walch+bid-zero --classifiers mlp \
    --estimators HYBRID --variants instantaneous,mean2min --selection per-group --heldout-datasets walch \
    --mlp-alpha 1e-2 --jobs 12
mise exec -- python -I tools/train/train.py compare --runs loso-v2,exp-v2-e1-balanced,exp-v2-e2-mlp-alpha
# 3. no experiment of section 13.6 won, so loso-v2 is the complete-protocol run of the adopted classifier setting
mise exec -- python -I tools/train/train.py final --run loso-v2 --install-model
mise exec -- python -I tools/train/train.py defaults --run loso-v2 --write   # core-algo params/TrainedDefaults.kt
# 4. reporting only: seeds and session-boundary / steps sensitivity (sections 13.7, 13.8)
for n in 1 2 3; do mise exec -- python -I tools/train/train.py run --run seed-v2-s$n --configs walch+bid-zero \
    --classifiers mlp --estimators HYBRID --variants instantaneous,mean2min --selection per-group \
    --heldout-datasets walch --seed $n --jobs 12; done
mise exec -- python -I tools/train/train.py compare --runs loso-v2,seed-v2-s1,seed-v2-s2,seed-v2-s3
mise exec -- python -I tools/train/train.py sensitivity --run loso-v2
# 5. stage proportions (pre-registered v3, section 13.10; a build with hybrid.priorExponent / bundle stageLogBias):
#    the adopted setting again with its inner fold models kept, the experiment, the final bundle with b
mise exec -- python -I tools/train/train.py run --run bias-base --configs walch+bid-zero --classifiers mlp \
    --estimators HYBRID --variants instantaneous,mean2min --selection per-group --keep-inner --jobs 24
mise exec -- python -I tools/train/train.py bias --run bias-base --jobs 24   # other build: --allow-other-build
mise exec -- python -I tools/train/train.py bias-report --run bias-base     # re-render its report (no algo-cli)
mise exec -- python -I tools/train/train.py bias-final --run bias-base --out-run bias-final \
    --selection-from tools/train/out/loso-v2/summary.json --install-model
mise exec -- python -I tools/train/train.py defaults --run bias-final --write   # TrainedDefaults.kt (beta = 1)
# 6. rebuild with the new defaults and check
mise exec -- ./gradlew :core-algo:test :algo-cli:test :algo-cli:installDist ktlintCheck detekt
mise exec -- python -I -m pytest tools/train/tests -q
```

`train.py run` writes `tools/train/out/<run>/` (never committed): `config.json` (with the `algo-cli` fingerprint),
`report.md` and `summary.json` (all numbers of section 13), `results.jsonl` (per night), `fold_selections.jsonl`
(inner-CV choice, selection key and inner margin per outer fold), `commands.log` (every `algo-cli` call with exit code
and duration), the fold bundles, `hmm-trained/<pool>/final.hmm-params.json` and the final model; `--install-model`
copies the final bundle to `app/src/main/assets/models/hybrid_model.json`. `train.py report --run <run>` re-renders
the report from the stored per-night results. Results do not depend on `--jobs` (fixed seeds, single-threaded BLAS).
Wall times on a 24-core (8P + 16E) laptop CPU: step 1 32 min, each experiment / seed run 5–6 min, the sensitivity
analysis ≈ 2 min; step 5 (from `bias-base/run.log`, `bias-base/bias/commands.log` and `tools/train/out/commands.log`):
`bias-base` 6.4 min (385 s), `bias` 1.6 min (433 `algo-cli` calls, 09:16:57–09:18:30), `bias-final` ≈ 0.4 min
(09:19:20–09:19:45, 17 `algo-cli` calls). `train.py bias` writes `<run>/bias/` (`summary.json`, `report.md`,
`decisions.json` with every inner grid point and fixed-point trace, `results.jsonl`, `commands.log`; `--out-name`
another directory); `train.py bias-report` re-renders its `summary.json` / `report.md` from `results.jsonl` and
`decisions.json` without `algo-cli`; `bias-final` writes `out/<out-run>/` (`final/hybrid_model.json`,
`trained_defaults.json`, `summary.json`). Both `bias` and `bias-final` refuse an `algo-cli` build other than the run's
unless `--allow-other-build` is given (then logged and recorded in `summary.json` as `runAlgoCliSha256`; BASE must
still reproduce the run night by night). The run directories are not committed: keep `tools/train/out/{bias-base,
bias-final}` (and the worktree's `commands.log`) with the other runs; the pre-registration and that log are also
committed under `docs/prereg/`. The full per-subject nested selection (`--selection per-subject`, 1504
inner-CV runs) was started once and stopped; the logged projection was more than 2.5 h for the nested selection alone.
