# Synthetic validation of the RULES, HMM and HYBRID estimators

Source: `SyntheticRecoveryTest` (`core-algo/src/test/kotlin/io/github/alien2003/pulsestages/algo/SyntheticRecoveryTest.kt`),
ALGO_VERSION 1, default `AlgoParams` except for `estimator`.

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
default `AlgoParams` except for `estimator` and `hybrid.durationModel`.

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
