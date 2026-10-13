# Pre-registration: daytime naps (short sessions) of the HYBRID decoder (worktree nap-eval, branch fix/nap-eval)

Written 2026-10-13T22:24:35+02:00, BEFORE any result on nap proxies exists (no proxy has been built, decoded or
scored; no candidate code has been run). The only numbers known are the published night-level numbers of
ALGORITHM.md section 13 (runs `loso-v2`, `bias-base`, `bias-base/bias`, `bias-final`). Also appended verbatim to
`tools/train/out/naps/commands.log`.

## Question

A user who takes daytime naps sets the app's source filter `minSessionMin` to 30 min, so sessions of 30 min and
longer are estimated by the shipped default (HYBRID-MLP [walch+bid-zero], EXPLICIT HSMM, emissionWeight 0.5,
timePriorWeight 0, beta 1, bundle stageLogBias b = (+0.090, +0.216, -0.318, +0.012)). Is that good enough for naps,
or does it need a change for short sessions? b was fitted on whole nights (stage mix of a full night); a nap has a
different mix. No open dataset has labelled daytime naps, so the evaluation uses NAP PROXIES cut from the labelled
nights.

## Nap proxies

For every OHealth-like night file (`tools/train/data/nights/<variant>/`, variants `instantaneous` and `mean2min`;
Walch PSG = headline, BIDSleep EEG headband = secondary) and every length L in {30, 60, 90, 120, 180} min:

- session start s0 = the night's OHealth-like session start (first non-wake 30-s epoch - 5 min, clipped to the scored
  range, exactly as `prepare.py` builds it); session end s1 = s0 + L min. Every prepared session is at least 199 min
  long (Walch) / 242.5 min (BIDSleep), so s1 never passes the night's session end (a night where it would is skipped
  and counted);
- heart rate: the night file's samples with s0 - 30 min <= t <= s1 (the context BEFORE the session as available in
  the night file, which holds heart rate from s0 - 30 min where it exists); steps: the step records with
  end > s0 - 30 min and end <= s1; labels: the 30-s label epochs fully inside [s0, s1];
- NO data after s1. A real nap is followed by wakefulness (higher heart rate, often steps), which the nights cannot
  supply; dropping the post-session context instead of using the continuing sleep of the night is the conservative
  choice (the decoder gets no help from the context after the end, and no misleading sleep context either);
- proxy id = `<night id>-nap<L>`, subject and variant unchanged.

Stated limitations of the proxies (reported with the results): they are the first L minutes of a NIGHT (evening
circadian phase, after a full day awake, sleep onset taken from PSG - 5 min), not an afternoon nap; they end inside
sleep, whereas a real nap ends with an awakening (and the model was trained with sessions that end at the final
awakening + 5 min, so its time-to-end features expect wake near the end); a real nap's post-session wake context is
not represented.

## Fold artefacts, reproduction gate

No classifier is retrained. Every held-out subject is decoded with its LOSO outer fold bundle of run `bias-base`
(`hybrid/walch+bid-zero/<variant>/mlp/folds/<subject>.json`) and the decoder choice theta_g = (durationModel,
emissionWeight, timePriorWeight) of its (variant, outer group g) from the nested per-group selection, beta = 1.
The fold-level b_g of the adopted candidate (b) of the stage-proportion experiment (`bias-base/bias/decisions.json`,
`choices.B.bias`, fitted on the inner out-of-fold decodes of g's Walch selection subjects, never on g's subjects) is
the shipped default's b at fold level.

Gate before any proxy is decoded: with the worktree's algo-cli build (fingerprint recorded), the night-level
evaluation of (A) the shipped default (fold bundle + b_g) and of BASE (fold bundle, no b) on all 284 nights of both
headline variants must reproduce `bias-base/bias/results.jsonl` (candidates B and BASE) night by night (confusion
matrix, warnings, estimated / skipped); RULES and HMM-table (the physiology table) on the nights must reproduce the
per-night confusion matrices of `loso-v2/results.jsonl`. If the HYBRID check fails, stop and document; the context
estimators' check is reported either way.

## Candidates (sessions shorter than a threshold T, T in {120, 180} min)

Session length = (s1 - s0) in minutes; "short" = strictly below T. Sessions >= T are decoded exactly as by (A) in
every candidate, so whole nights (all >= 199 min) are unaffected by construction (verified by re-running the night
evaluation; see "If adopted").

- (A) shipped default: fold bundle + b_g for every session (no special case).
- (B-T) b off for short sessions: short sessions decoded with the fold bundle without b (= BASE); others as (A).
- (C-T) separate short-session bias b_short: short sessions decoded with the fold bundle carrying b_short_g(T)
  instead of b_g; others as (A). b_short_g(T) = the deterministic fixed point of `pulsetrain/bias.py`
  (`fit_stage_bias`, unchanged: start b = 0, eta = 1, proposal b + eta * ln(T_s / max(P_s, 1e-4)) centred, accepted
  when D = sum_s |T_s - P_s| decreases, else eta halved, at most 8 decodes, stop at D <= 0.005; shares over the
  pooled scored 30-s chunks of the fit set) per (variant, outer group g, T) on the TRAINING folds' proxies only: the
  proxies with L < T (L in {30, 60, 90} for T = 120; {30, 60, 90, 120} for T = 180) of the Walch nights of g's
  inner held-out selection subjects (`selection/<key>.json` evalSubjects / evalNights), each decoded by its inner
  fold model (`work/walch+bid-zero/<variant>/mlp/<key>/models`) at theta_g, beta = 1 — the same fit set as b_g, cut
  into proxies; never a subject of g.
- Every held-out subject (Walch and BIDSleep) is decoded with every candidate; BIDSleep numbers are secondary,
  outside the rule.
- Context only (not candidates): RULES (AlgoParams defaults) and HMM-table (the specification's physiology table,
  `loso.physiology_hmm_patch`), untrained, on the same proxies.

The five candidates are A, B-120, B-180, C-120, C-180. With T = 120 the L = 120 proxies are not short (L = 120 is
not < 120) and are decoded as (A); L = 180 proxies are (A) in every candidate (reported for context, outside the
rule).

## Metrics (per candidate, dataset, variant and L; and pooled over L in {30, 60, 90, 120})

Per session, on its scored 30-s chunks (prediction at the chunk midpoint, `IntervalScoring`; chunks predicted UNKNOWN
inside long heart-rate gaps are not scored): confusion matrix; accuracy = correct / scored chunks; true and predicted
shares T_s, P_s of AWAKE / LIGHT / DEEP / REM; summed share error E = 100 * sum over the four stages of |P_s - T_s|
(percentage points, 0-200); per-stage |P_s - T_s|; per-stage minute errors (informational).

- E (rule): mean over the sessions of the L; pooled over L = mean over the four L of the per-L mean E.
- accuracy (rule): per L the pooled accuracy over all scored chunks of the sessions of that L; pooled over L = mean
  over the four L of the per-L accuracy (each length counts equally). Also reported: mean per-session accuracy.
- Cohen's kappa: mean per-subject kappa over the subjects where it is defined (the subject's sessions of that L
  pooled; Walch = one session; undefined when the chance agreement is 1, i.e. truth and prediction are the same
  single stage), with the number of subjects where it is defined; also pooled over all chunks of the L. REPORTED,
  NOT USED for selection: it is unstable on 30-min segments that contain one or two stages.
- stage shares: pooled predicted vs true shares (chunks of all sessions of the L) and mean per-session shares.
- NOT_ESTIMATED: sessions not estimated (algo-cli `skipped`, by reason: SESSION_TOO_SHORT, NO_HR_DATA, LOW_COVERAGE,
  NO_LABELS) and estimated sessions with no scored chunk; rate per L.
- flags: share of estimated sessions with a sanity warning (SANITY_DEEP_HIGH / SANITY_REM_HIGH / SANITY_DEEP_LOW,
  each and any), with LOW_HR_RANGE, low-confidence (LOW_HR_RANGE or any sanity warning = the night's
  `flags.lowConfidence`), RULES_FALLBACK_LOW_COVERAGE, CYCLE_PRIOR_DISABLED (informational) and DATA_GAPS.
- paired per-subject differences candidate - (A) with 95 % percentile bootstrap CIs (`report.paired_bootstrap`,
  10 000 resamples of subjects, seed 0), per variant, pooled over L and per L: E (subject value = mean over the
  subject's sessions), accuracy (subject value = mean over L of the subject's pooled accuracy at L; per L the
  subject's pooled accuracy) and, per L, kappa (subjects where defined in both).

## Adoption rule (verbatim)

On Walch, worse sampling variant, pooled over L in {30, 60, 90, 120} (and reported per L): adopt the candidate with
the lowest mean summed share error among those whose accuracy >= (accuracy of A - 0.01); ties -> higher accuracy;
else keep A.

Operational definitions: accuracy worse = min over {instantaneous, mean2min} of the pooled-over-L accuracy; E worse
= max over the two variants of the pooled-over-L E (each criterion on its own worse variant); A competes (it
qualifies trivially); ties = E worse within 0.05 percentage points of the lowest; remaining ties -> the order A,
B-120, B-180, C-120, C-180 (A first). Adopting A means: no change.

## If B or C is adopted

- core-algo: `HybridParams.shortSessionMaxMin` (minutes; sessions strictly shorter use the short-session bias; 0 =
  off = today's behaviour), validated (finite, >= 0), in AlgoParams and hence in the params hash; for C an optional
  bundle field `stageLogBiasShort` = [bA, bL, bD, bR] (absent = no bias on short sessions, which is B), validated
  (exactly 4 finite numbers), part of the bundle fingerprint when present (a bundle without it keeps its
  fingerprint), documented in docs/INTERCHANGE.md. Default of `shortSessionMaxMin` = the adopted T.
- C's final b_short: the same fixed point on the LOSO out-of-fold proxies (L < T) of all 31 Walch subjects, both
  headline variants pooled, each decoded by its own outer fold bundle (no b) at the final decoder parameters
  (EXPLICIT, 0.5, 0, beta 1), added to the shipped bundle (`bias-final/final/hybrid_model.json`, otherwise
  unchanged) and installed; TrainedDefaults gains the threshold.
- Verification: the new build with the new defaults / bundle reproduces (A) night by night on all 284 nights of both
  headline variants (nights >= T unaffected) and the adopted candidate's proxy results exactly.
- Kotlin and Python tests, ALGORITHM.md section 13.11, INTERCHANGE.md, PLAN.md, README.

If A is kept: no code change to the decoder; ALGORITHM.md section 13.11 documents the experiment and the plain
conclusion either way.
