# Pre-registration: stage-proportion bias of the HYBRID decoder (worktree train2, branch wip/train2)

Written 2026-10-13T09:01:31+02:00, BEFORE any candidate result exists (no candidate code has been written or run;
the only numbers known are the published `loso-v2` baseline numbers of ALGORITHM.md section 13). Also appended
verbatim to `tools/train/out/commands.log`.

## Problem

Shipped default (HYBRID-MLP [walch+bid-zero], EXPLICIT HSMM, emissionWeight 0.5, timePriorWeight 0; run `loso-v2`):
Walch LOSO kappa 0.334 (worse variant, instantaneous), but pooled predicted shares DEEP 25 % / REM 34 % / LIGHT 34 %
vs PSG 14 % / 23 % / 57 %; per-night minute errors DEEP 52 / REM 54 min; 12 of 31 Walch nights get the sanity
low-confidence flag. Goal: the fix with the smallest per-night minute error that does not lose kappa.

## Decoder change (Kotlin core-algo; defaults = current behaviour)

Emission score of epoch k with heart-rate data, stage s (everything else unchanged):

    e_k(s) = w_e * ( ln p(s | x_k) - beta * ln pi_s ) + b_s        (+ time prior + log factors as before)

- `beta` = new AlgoParams field `hybrid.priorExponent` (default 1.0 = today's p / pi), validated finite and >= 0.
- `b` = new OPTIONAL bundle field `stageLogBias` = [b_AWAKE, b_LIGHT, b_DEEP, b_REM] (absent = all 0), validated
  (exactly 4 finite numbers), documented in docs/INTERCHANGE.md, part of the bundle fingerprint when present (a
  bundle without it keeps its current fingerprint, so personal models keyed to the shipped bundle stay valid).
  Choice of the bundle over AlgoParams: b is a statistic FITTED to one classifier's outputs (like classPriors,
  durations, timePrior, which all live in the bundle), not a hyper-parameter chosen from a grid; the app persists
  the full AlgoParams JSON (encodeDefaults = true), so an AlgoParams vector fitted for one bundle would silently
  survive into a future bundle; in the bundle it ships with, and is hashed with, the classifier it corrects.
- Note (stated before any result): on epochs with HR data, -w_e * beta * ln pi_s and b_s are both per-stage
  constants, so changing beta by d is exactly the same as adding -w_e * d * ln pi_s to b. With b fitted to the
  stage shares, the decoded result is therefore (up to the fixed point's tolerance) independent of beta: candidate
  (c) is expected to coincide with (b). It is run as specified anyway.

## Protocol (identical to loso-v2 otherwise)

Baseline reproduction run (new algo-cli build, defaults = current behaviour):

    JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py run --run bias-base --configs walch+bid-zero \
        --classifiers mlp --estimators HYBRID --variants instantaneous,mean2min --selection per-group \
        --keep-inner --jobs 24

(MLP alpha 1e-4, seed 0, MLP grid `small`, per-group nested selection by inner mean per-subject kappa, all 78 pool
subjects held out so that BIDSleep secondary numbers exist.) Check: the HYBRID-MLP [walch+bid-zero] per-subject
kappas of Walch and BIDSleep, both variants, equal those of loso-v2 (0.334 / 0.336 Walch, 0.331 / 0.357 BIDSleep);
if not, the difference is documented and bias-base is the reference.

Candidates are evaluated post hoc on bias-base (`train.py bias --run bias-base`): the classifier, the outer fold
bundles and the decoder choice theta_g = (durationModel, emissionWeight, timePriorWeight) of every (variant, outer
group g) are those of the baseline; candidates change only beta and b. Inner-CV data per (variant, g): the kept inner
fold models and the inner held-out Walch subjects of the selection set of g (exactly the nights on which theta_g was
chosen; out-of-fold decodes, never the outer group's subjects).

- BASE: beta = 1, b = 0.
- (a) prior exponent: inner grid beta in {1, 0.75, 0.5, 0.25, 0} at theta_g. Inner rule (the adoption rule's
  objective inside the inner CV, per variant and group): qualifying = beta with inner mean per-subject kappa >=
  inner kappa of BASE (beta = 1) - 0.01; choose the lowest inner mean per-night E (below); ties (|dE| < 0.05 min) ->
  higher inner kappa, then larger beta.
- (b) proportion matching: beta = 1, b_g = FP(theta_g, beta = 1) on the inner out-of-fold decodes of g (fit set =
  the Walch PSG nights of the training fold: the selection-scoring dataset of the protocol and the PSG reference;
  BIDSleep labels are headband-scored with a different N3 share, 19.5 % vs 14.2 %). No selection step.
- (c) combination: for every beta of the (a) grid, b_g(beta) = FP(theta_g, beta) (each started at b = 0); the inner
  kappa / E of (beta, b_g(beta)) are computed on the same inner decodes (b in-sample); beta_g by the (a) inner rule
  with the BASE inner kappa as reference; if no beta qualifies the group keeps BASE.
- Outer evaluation: every held-out subject (Walch and BIDSleep) with its LOSO fold bundle (+ b_g of its group for
  (b), (c)) and theta_g + beta_g of its group.

FP (deterministic fixed point) on a fit set: T_s = true share, P_s(b) = predicted share of stage s over the scored
30-s chunks of the fit nights pooled (confusion row / column sums), decoded by algo-cli with bundles carrying
stageLogBias = b. Start b = 0, eta = 1. Proposal b' = b + eta * ln(T_s / max(P_s(b), 1e-4)), then centred (mean
subtracted). D(b) = sum_s |T_s - P_s(b)|. If D(b') < D(b) accept b <- b', else reject and halve eta. At most 8
decodes per fit (b = 0 included); stop early when D(b) <= 0.005. Result = the accepted b (smallest D decoded).

Final model, only if (b) or (c) is adopted: b_final = FP at the final decoder params on the LOSO out-of-fold decodes
of all 31 Walch subjects, both headline variants pooled (each night decoded by its own outer fold bundle); final
decoder params = the most frequent inner choice over the outer folds of the two headline variants, as in loso-v2
(beta likewise; ties -> higher mean inner kappa, then larger beta); final bundle = loso-v2-style final fit on all 78
subjects + stageLogBias. If (a) is adopted: beta* likewise, no b. TrainedDefaults gains HYBRID_PRIOR_EXPONENT.

## Metrics (Walch held-out subjects = headline; BIDSleep = secondary)

- kappa = mean per-subject Cohen's kappa per variant; worse = min over instantaneous / mean2min.
- E = mean over nights of sum over {DEEP, REM, AWAKE, LIGHT} of |predicted - true| minutes of the night (scored
  30-s chunks, from the per-night confusion matrix); worse = MAX over the two variants (each criterion on its own
  worse variant).
- Reported for every candidate either way: kappa per variant and worse, E and per-stage minute errors, accuracy,
  macro-F1, pooled predicted vs true stage shares, number of nights with a sanity flag (SANITY_DEEP_HIGH /
  SANITY_REM_HIGH / SANITY_DEEP_LOW), paired per-subject bootstrap 95 % CIs vs BASE for kappa and E (10 000
  resamples, seed 0, per variant), inner choices of beta and the fitted b per group, BIDSleep secondary numbers.

## Adoption rule (verbatim)

On Walch held-out subjects, worse sampling variant: adopt the candidate with the lowest mean absolute per-night
minutes error summed over DEEP+REM+AWAKE+LIGHT among those whose mean per-subject kappa >= (baseline kappa - 0.01);
ties -> higher kappa; if none qualifies, keep the baseline.

(Baseline kappa = BASE worse-variant mean per-subject kappa of bias-base; the baseline itself qualifies trivially and
competes; ties = |dE| < 0.05 min. If adopted: retrain / export the final model as above, install it, regenerate
TrainedDefaults.kt, update ALGORITHM.md / PLAN.md. If not: document the experiment in ALGORITHM.md anyway.)
