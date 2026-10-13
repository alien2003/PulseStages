# Synthetic validation of the RULES, HMM and HYBRID estimators

Source: `SyntheticRecoveryTest` (`core-algo/src/test/kotlin/io/github/alien2003/pulsestages/algo/SyntheticRecoveryTest.kt`),
ALGO_VERSION 1, default `AlgoParams` except for `estimator` and the HMM, which is pinned to the specification's
physiology parameters (`HmmParams.PHYSIOLOGY`: `EmissionTable.PHYSIOLOGY`, mean bouts 5 / 20 / 20 / 20 min, the
specification's transition shares and time priors). These tests were calibrated with those values before the
open-dataset training set the shipped defaults (see "Pinned parameters" at the end).

Setup: 50 nights from `SyntheticNightGenerator(seed)` for seeds 1–50, run through `SleepStager.run`, scored against
the generating hypnogram with `IntervalScoring` (each truth interval split into 30-s chunks, prediction sampled at
the chunk midpoints). Kappa is unweighted Cohen's kappa over AWAKE / LIGHT / DEEP / REM, computed per night;
accuracy is pooled over all chunks of the 50 nights.

| Estimator | Mean kappa | Median kappa | Min kappa | Pooled accuracy |
|-----------|-----------:|-------------:|----------:|----------------:|
| HMM       | 0.4927     | 0.4961       | 0.3336    | 0.6556          |
| RULES     | 0.3975     | 0.3903       | 0.2376    | 0.6770          |

Re-measured after the review fixes (Hampel scale floor `hampelMinScaleBpm`, minimum bouts after the gap policy with
the AWAKE minimum only inside sleep, whole-step rule in RULES, HSMM censoring and midpoint duration bins); the first
run gave HMM 0.4924 / 0.4829 / 0.3372 / 0.6551 and RULES 0.3956 / 0.3851 / 0.1899 / 0.6730.

Regression thresholds asserted by the test (observed mean − 0.05, rounded down to 0.01): HMM ≥ 0.44, RULES ≥ 0.34,
and mean kappa(HMM) ≥ mean kappa(RULES). RULES has the higher raw accuracy because it labels most epochs LIGHT, the
majority class; kappa corrects for that.

**Caveat.** The synthetic nights come from the same model family the HMM assumes (stage-dependent heart-rate
offsets, AR(1) noise, a 2-minute lag, regular ultradian cycles; see `SyntheticNightGenerator`), so these numbers
overstate the accuracy to expect on real nights. They are regression guards only. Defaults are never tuned on
synthetic data; the real validation uses the open PSG datasets (leave-one-subject-out) reported in ALGORITHM.md.

## HYBRID (hybrid classifier + HSMM / HMM decoding)

Source: `HybridSyntheticRecoveryTest`
(`core-algo/src/test/kotlin/io/github/alien2003/pulsestages/algo/hybrid/HybridSyntheticRecoveryTest.kt`), ALGO_VERSION 1,
default `AlgoParams` except for `estimator`, `hybrid.durationModel` and the pinned pre-training decoder values
`timePriorWeight` = 1, `emissionWeight` = 1 (and `HmmParams.PHYSIOLOGY` for the HMM comparison).

Setup: a bundle is trained by the test fixture `SyntheticBundleTrainer` (test sources only; it stands in for the
Python pipeline) on 40 synthetic nights (seeds 1001–1040): multinomial logistic regression on the 40
`HybridFeatureExtractor` features (FEATURE_SET_VERSION 1), gamma bout durations by the method of moments, bout
transitions, initial stage and a 30-min time prior counted from the generating hypnograms. Evaluation on 30 held-out
nights (seeds 2001–2030) through `SleepStager.run`, scored like above.

| Estimator          | Mean kappa | Median kappa | Min kappa | Pooled accuracy |
|--------------------|-----------:|-------------:|----------:|----------------:|
| HYBRID / EXPLICIT  | 0.7349     | 0.7304       | 0.5663    | 0.8377          |
| HYBRID / GEOMETRIC | 0.7241     | 0.7233       | 0.5625    | 0.8301          |
| HMM (same nights)  | 0.5065     | 0.5195       | 0.2600    | 0.6663          |

Re-measured after the review fixes (first run: EXPLICIT 0.7411, GEOMETRIC 0.7348, HMM 0.5059 mean kappa). The
small HYBRID drop comes from the Hampel scale floor: with `hampelMinScaleBpm` = 0 (classic Hampel) the same build
gives EXPLICIT 0.7417 and GEOMETRIC 0.7337. The classic filter "replaces" 1-bpm deviations in windows with MAD = 0,
which smooths the generator's AR noise; the floor keeps genuine variability of whole-bpm readings. The default is not
chosen on synthetic data (the floor is the 1-bpm resolution of Health Connect heart rate); the open-dataset
evaluation can tune the parameter.

Regression thresholds asserted by the test (observed mean − 0.05, rounded down to 0.01): EXPLICIT ≥ 0.69,
GEOMETRIC ≥ 0.68, and mean kappa(HYBRID/EXPLICIT) ≥ mean kappa(HMM).

**Caveat.** Here the classifier and the duration/transition statistics are fitted on nights from the very same
generator, so the gap to the HMM is much larger than can be expected on real data. Real numbers come from the
leave-one-subject-out evaluation on the open PSG datasets (ALGORITHM.md).

### Personalization (`PersonalCalibratorTest`)

12 reference nights of a "shifted person" (baseline 65 bpm, DEEP −3.5, REM +12, AWAKE +15 bpm instead of the
generator's −5 / +6 / +15), seeds 3001–3012; the earliest 8 are training nights, the latest 4 are held out
(`personal.calibrationHoldout` = 0.3). Pooled held-out kappa of HYBRID with the bundle above:

| Calibration                           | Kappa before | Kappa after |
|---------------------------------------|-------------:|------------:|
| default regularization (λ = 10 h)     | 0.6980       | 0.7071      |
| weak regularization (λ = 0.5 h)       | 0.6980       | 0.7373      |

HMM-only calibration (no bundle, 8 nights: 6 training / 2 held out): 0.3116 → 0.4470. (Re-measured after the review
fixes; the first run gave 0.6736 → 0.6817 / 0.7255 and 0.3176 → 0.3701.)

## Pinned parameters and the shipped (trained) defaults

The leave-one-subject-out validation on the open datasets (ALGORITHM.md, sections 12–13; run `loso-v2` of
`tools/train`) sets the `AlgoParams` defaults through the generated `params/TrainedDefaults.kt`. It changed the hybrid
decoder (`durationModel` EXPLICIT — the pre-training value —, `emissionWeight` 0.5, `timePriorWeight` 0). The HMM
emissions / mean bouts / transitions / time priors also come from `TrainedDefaults`, but the supervised estimates
failed the selection's validity gate (their hrNorm means are not ordered DEEP < LIGHT < REM < AWAKE), so they are the
specification's values and `HmmParams()` equals `HmmParams.PHYSIOLOGY`. The synthetic tests above and
`PersonalCalibratorTest`, `PopulationEmAdapterTest`, `TransitionModelTest`, `EstimateAssemblerTest` and the algo-cli
`EvaluateCommandTest` pin `HmmParams.PHYSIOLOGY` and the pre-training decoder values explicitly, so their numbers and
thresholds do not depend on what a future training run adopts (re-run after the change: identical to the tables
above). The tests also print, without asserting, the same nights with the shipped defaults:

| Estimator (50 / 30 nights as above)                         | Mean kappa | Median kappa | Min kappa | Pooled accuracy |
|-------------------------------------------------------------|-----------:|-------------:|----------:|----------------:|
| HMM with the shipped HMM defaults (= the physiology table)   | 0.4927     | 0.4961       | 0.3336    | 0.6556          |
| HYBRID (synthetic bundle) with the shipped decoder defaults  | 0.7461     | 0.7590       | 0.5284    | 0.8493          |

The supervised HMM estimates illustrate the caveat above: an earlier run (`loso-v1`) shipped them as HMM defaults, and
on these synthetic nights they scored 0.2675 mean kappa, far below the physiology table (0.49), although they score
higher on real PSG nights (0.117 vs 0.070 worse-variant mean per-subject kappa on Walch in `loso-v2`): in the 2-minute
Walch data the per-night normalized heart rate (hrNorm) of DEEP epochs is on average *above* that of LIGHT (1.26 vs
0.91; BIDSleep 1.29 vs 0.91). DEEP is concentrated early in the night (mean t 0.29 vs 0.51 for LIGHT), while heart
rate is still falling, and even within each third of the night DEEP is not below LIGHT in these data; the generator,
like the physiology table, places DEEP 5 bpm below LIGHT. Synthetic data from the assumed model family say little about
real accuracy, which is why defaults are chosen on the open PSG data only.
