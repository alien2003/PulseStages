# Pre-registration: realistic nap estimates — an evidence-following nap model with a literature-anchored stage mix, tested on real afternoon naps

Written 2026-10-15, BEFORE any candidate of this experiment (C0, C1, C2) has been trained, fitted, decoded or scored,
BEFORE the full fetch of OpenNeuro ds006576 and BEFORE any ds006576 recording has been decoded by any PulseStages model.
The text was revised before its commit after internal review; no candidate value was known at any revision. No
code of this experiment has been run on any data; the only computations made for this file are the labels-only and
literature checks of section 2.5. **This file is committed first, on its own (the pre-registration commit), before
any code of this experiment is written or run;** its analysis code follows in a second commit (the code commit) before
the first algo-cli call of the experiment proper (section 19). Worktree `realistic-naps`, branch `worktree-realistic-naps`, base commit `cad7a6f`
(the dedicated nap model of ALGORITHM.md section 13.12 is shipped, bundle SHA-256 `583df315…30f9`). Also appended
verbatim to `tools/train/out/commands.log`. Known when it was written: the published results of ALGORITHM.md section
13, the diagnosis of the shipped nap model, the verified literature notes and their cross-check against open nap
hypnograms, the ds006576 pilot and the checks of section 2.5 — all listed in section 2. Nothing is known about any
candidate. The reproduction-only gates of section 11 that run before the code commit (G1, G2, G3a, G4) reproduce
published results and fit no candidate; they run after this text is committed and its digest is logged, and their
START / RESULT lines are part of the committed log.

No personal data of any user enter this file, its analysis code, its tests or any fit. Every number of a target, a
corridor or a threshold below comes from the cited literature, from the arm table of Appendix B under the single
inclusion rule of section 5.0, or from section 13.12, each with its source; every fitted value comes from the
procedures below, on training folds only; synthetic sessions use a generic length grid. Where a verbatim rule and its
operational definition differ in length or detail, the operational definition governs.

## 1. Question

Naps (sessions shorter than `napMode.maxSessionMin` = 180 min) are decoded by the shipped nap model, called N1 here
(ALGORITHM.md 13.12). It beat the night model on nap proxies cut from nights, but a diagnosis made after it shipped
(section 2.2) shows that its stage mix is in effect a night-onset template: it reads where an epoch sits in the
session (`t`, `hoursSinceStart`, `hoursToEnd`), it was trained on rows that are 93 % night-onset proxies with the first
30 min of a night counted up to six times, its stage log-bias pulls every nap towards the onset-proxy mix (29 % DEEP,
8.7 % REM), and it barely follows the heart rate (session-level slope of predicted on true DEEP share 0.13–0.28, REM
about 0). A quiet one-hour session decodes as about 60 % DEEP and 0 % REM whatever it contains.

1. **Evidence.** Can a nap estimator be built whose stage mix follows the heart-rate evidence — REM higher and DEEP
   lower in late-night windows than in night-onset windows of the same length — without losing per-epoch
   discrimination relative to N1?
2. **Realism.** Can its average stage mix be anchored to published compositions of daytime naps instead of night
   onsets, at the anchor lengths and between them, with no more implausible single-nap outputs than genuine naps show,
   never further from the published composition than N1, and with a featureless session decoded to the published
   range rather than to a DEEP block?
3. **Real naps.** Are its estimates of real afternoon naps (OpenNeuro ds006576, test only, weak labels) within the
   published ranges and consistent with what those naps contained?
4. **Output.** Whatever is adopted, which nap output policy (STAGES / SLEEP_WAKE / SKIP) should be the default?

The available ground truths conflict, so every decision is matched to the ground truth that can support it:

| what is decided | ground truth | why this one |
|---|---|---|
| per-epoch discrimination (gate D) | labelled night-derived windows, within one session length | labelled; within one length any per-length level is constant, so the AUC measures ranking, not level |
| evidence-following (gate F) | night-onset vs late-night windows of the same length | the only labelled heart-rate data with a DEEP-rich and a REM-rich regime |
| realism of the level (gate R, selection) | published compositions of afternoon naps (Tier-1 band / Tier-2 guard), through featureless sessions and a pool of labelled windows reweighted to that composition, at the anchors and between them | real nap compositions are known only at group level |
| real naps (rule (iii)) | ds006576 group membership and its published Table 1 | real afternoon naps with heart rate, but no epoch labels |

Accuracy and summed share error against the truth of night-derived windows are reported for every pool but do not
gate: a candidate anchored to nap compositions must under-call DEEP on night-onset windows (section 8).

## 2. Known beforehand (disclosure)

### 2.1 Published results (ALGORITHM.md sections 13.10–13.12)

- Runs `bias-base`, `bias-base/bias`, `naps`, `naps-posthoc`, `nap-model`, `nap-model-final`, `nap-model-verify`, read
  only here.
- N1 on nap proxies v2 (onset + ends-in-wake), Walch, worse variant, pooled: accuracy 0.606, summed share error E 53.1
  pp, mean per-subject 4-class κ 0.356, sleep / wake κ 0.593; N0 (night model + `stageLogBiasShort`) 0.499 / 70.4 pp;
  "all LIGHT" 0.530 / 92.1 pp. Pooled shares (Walch, instantaneous, % AWAKE / LIGHT / DEEP / REM): PSG 9.3 / 53.0 /
  29.0 / 8.7, N1 8.5 / 53.8 / 28.6 / 9.2. Rule (ii) of 13.12 gave STAGES.
- The shipped nap section: MLP 2 × 32, nap stage log-bias (+0.339, +0.167, −0.228, −0.278), decoder EXPLICIT / 0.5 / 0,
  `classPriors` (0.099, 0.370, 0.395, 0.136).

### 2.2 Diagnosis of N1 (made after 13.12 and before this file, on the out-of-fold models of `nap-model`)

Every probe first reproduced the stored N1 records (3708 / 3708, 11 124 / 11 124 and 7149 / 7149 decodes). Results are
known for N1, N0, the night model and edited copies of N1 only; no retrained or re-biased candidate of this file was
run. Walch, instantaneous, group decoder θ_g unless stated.

| finding | numbers |
|---|---|
| training rows of N1 | 373 523 rows: 92.8 % onset-proxy rows, 89.3 % BIDSleep rows (headband labels), 49.4 % from the first 60 min of a night (the first 30 min appear in all six onset lengths); label mix 9.9 / 37.0 / 39.5 / 13.6 % |
| mix by window, predicted DEEP / REM % (true) | onset 30: 19.0 / 0.0 (15.2 / 1.1); onset 60: 43.8 / 0.0 (36.1 / 1.0); onset 90: 33.8 / 9.8 (33.4 / 6.0); windows starting ≥ 180 min after the session start, pooled: 24.4 / 3.5 (6.4 / 27.6), accuracy 0.436 |
| session-level tracking (onset + mid-night + late windows) | slope of predicted on true share: DEEP 0.13–0.28 (Spearman ρ 0.13–0.27), REM about 0.00 (ρ ≤ 0.08), both datasets and variants. Onset → late, pooled: truth DEEP 29.6 → 5.6 %, REM 8.5 → 26.4 %; N1 DEEP 28.7 → 24.0 %, REM 9.4 → 4.0 % (REM moves the wrong way). The night model inside its whole-night decode: slope DEEP 0.64, REM 0.58 |
| 60-min late windows (mid-night windows starting ≥ 180 min) | N1 decodes ≥ 50 % DEEP in 28 % (Walch) / 24 % (BIDSleep) of them (truth 3 % / 2 %) and 0 % REM in 92–96 % |
| what sets the level | removing the nap stage log-bias raises DEEP by +12 to +23 pp (its target was the Walch onset-proxy mix); the night model's class priors instead of the nap ones would add +17 to +32 pp; night-model durations in the nap HSMM −2 to −5 pp; bout transitions ≤ 2 pp; initial stage 0 pp; EXPLICIT vs GEOMETRIC ≤ 0.9 pp |
| session-time features (an ablation: set to their training mean, not a retrained model) | DEEP −8 to −13 pp on onset and late windows; accuracy −0.070 / −0.118 on onset proxies and +0.124 / +0.101 on windows starting ≥ 180 min (Walch / BIDSleep); `hoursToEnd` alone contributes nothing. Pooled onset → late with the ablation: DEEP 18.0 → 11.0 %, REM 6.3 → 9.2 %, i.e. about 0.29 (DEEP) and 0.16 (REM) of the true pooled differences |
| featureless synthetic sessions | the shipped bundle decodes a flat one-hour session (60 bpm ± 1 bpm, 2-min samples) as about 60 % DEEP / 0 % REM, more with a pre-context 10 bpm above the session; with 3-bpm noise about 18 % DEEP; the HSMM prior alone (emission weight 0) decodes 0 % DEEP |
| features | all 40 features are invariant to a constant heart-rate shift; amplitude × 0.5 adds +5 to +19 pp DEEP; one 1-min record of 3 steps (below the forced-AWAKE threshold) removes 6–12 pp DEEP (the nap step features had training std 0.029 / 0.091, so 3 steps sit about 48 SD out of distribution) |
| context | the app reads heart rate and steps over [start − 30 min, end + 30 min] (`NightInputReader.readFrom`, with the night's `preprocess.contextMarginMin`); every proxy has nothing after its end. A flat daytime-like pre-context 10 bpm above the session: +6.0 (onset), +8.7 (mid ≥ 180), +14.8 (late wake-anchored) pp DEEP; a post-context 20 bpm above: −2.9 / −7.1 pp; app-like context on both sides: −11.7 pp at 60 min, +8.4 pp at 90 min |
| session start | a start 10 / 20 min late: onset-60 accuracy 0.692 → 0.532 / 0.414; wake is invented at the start |
| return to sleep | decoding a late return-to-sleep window as the continuation of its night beats a stand-alone nap decode (BIDSleep accuracy +0.148 [0.092, 0.206]); the data cover wake bouts < 40 min only, i.e. inter-session gaps < 30 min, which the app already merges (`sources.mergeGapMin` = 30); merging beyond 30 min has no support |
| session boundaries, clock | how OHealth delimits nap sessions is undocumented (UNVERIFIED). No labelled heart-rate session has a daytime clock: Walch has none, BIDSleep windows lie at about 20:00–09:00 US Eastern Time (an inference) |

The candidate set, the gates and their thresholds were designed after these probes, knowing N1's values and the
ablation's. No candidate value is known.

### 2.3 Literature and open hypnograms

- The targets, bands and guards of section 5 come from three verified research notes made before this file (a
  literature synthesis, its gap-fill and a cross-check of the corridors against individual open nap hypnograms). Every
  number was read twice against its source. The notes are not committed (they contain one aggregate statement about one
  user's naps). For this file every arm-based quantity was recomputed from the notes' arm table under one inclusion rule
  (section 5.0); Appendix B lists every arm with its values, its DOI and, for every excluded arm, the reason, and
  `pulsetrain/litprior.py` holds that table so that a unit test recomputes every median, range, band, guard bound and
  target of section 5.
- Open individual nap hypnograms used for the guards and the genuine-nap rates (composition only, no heart rate):
  Zenodo 6642860 (CC BY 4.0), OSF chav7 (BSD-3-Clause), Sleep-EDF Expanded SC daytime bouts (ODC-By 1.0); together the
  93 "genuine" naps of the cross-check (23 + 35 + 35; OpenNeuro ds003768, in-scanner sleep, excluded as out of
  distribution).
- **Not used:** the OSF deposit of Bergamo et al. 2026 has no licence, so no number derived from it is used (neither its
  per-nap percentiles nor the 46–49 % zero-REM variant computed with it); the paper-level count is used. McDevitt et
  al. 2026, the paper that describes ds006576, is not a source of any target, band, guard bound or threshold; its Table
  1 is used only as weak labels of the test set (section 13), and its two arms are excluded by rule I5.
- **How the test set's paper touched the corridor work (disclosed).** The cross-check kept the auditory-cued Zenodo naps
  as genuine partly because McDevitt 2026's NREM group showed a similar DEEP tail (39.92 ± 25.37 % of TST), listed the
  McDevitt 2026 groups among the dispersion sources of its revised guard, and set that guard's 75 % and 60-min bounds
  just above the Zenodo maxima (70.7 %, 59.0 min). That guard (G, section 5.3) is therefore reported only. Every
  decision that uses a per-nap guard (R3 and rule (iii)(c)) uses the literature-only guard GL (section 5.3), whose
  bounds come from rule-I arms only (no McDevitt 2026, no Zenodo cohort, no individual data), and the threshold of
  rule (iii)(c) is GL's false-flag rate on the 70 genuine naps outside the Zenodo set, so neither path reaches it.
- Genuine-nap false-flag rates, recomputed for this file exactly as the guards are applied here (afternoon rows for
  every nap, no clock, rows by TST): G flags 0 / 93 (Wilson 95 % CI 0–4.0 %; in sample) and 0 / 70 outside Zenodo
  (0–5.2 %); GL flags 4 / 93 = 4.3 % (1.7–10.5 %) and 2 / 70 = 2.9 % (0.8–9.8 %). The cross-check's earlier 4 / 93
  belonged to a clock-aware literature-only guard that included the McDevitt 2026 arms; it is not used.
- Habitual sleep timing probably shifts circadian phase by about 1–1.5 h between late sleepers and the literature's
  conventional sleepers (an inference of the gap-fill note from relative clock times), and between-person spread dwarfs
  the mild-restriction shift; the defaults are therefore population-level (pooled rested and habitually short
  sleepers), never tuned to one person.

### 2.4 ds006576 pilot (no model output)

Five subjects chosen by a rule fixed before fetching (sub-104, sub-109 NREM group; sub-107, sub-108 REM group; sub-105
WAKE), sparse 15-s chunks every 120 s plus eight continuous 300-s ranges, about 0.89 GB. Header audit of all 65 `.eeg`
files: IEEE float32, multiplexed, 73 channels, 512 Hz; ECG1 / ECG2 / Status at channels 71–73; two label layouts for
A20–A32 (the channels used here have the same labels in both); WAKE files sub-139 and sub-176 truncated; no markers, no
time stamps, Status always 0; 21 complete nap recordings per group (Table 1 has 22 per group). The bipolar ECG1 − ECG2
gave a usable heart rate in 228 of 236 chunks (the rest an electrodes-off tail). The R-peak detector and the
chunk-quality constants of section 13.3 were written and checked on these five subjects' ECG (label-free contact with
the test set: no stage label, no model output); they are frozen at the values of section 13.3. A crude EEG / EMG / EOG
rule, with thresholds set while looking at these five subjects' indicator traces, placed onset and final awakening
plausibly in 3 of 4 naps; it is not used here. Session spread P50 − P10 of the nap heart rate was 0.8–1.2 × that of the
night-onset proxies (no amplitude compression), 2-min step SD 1.5–2.3 ×, median heart rate about 0.8 ×. The pilot also
found that 4 of the 23 WAKE sessions are failed nap attempts that may contain short N1 / N2 bouts (identities unknown).
**No PulseStages model, decoder or algo-cli was run on any ds006576 data.** The pilot subjects stay in the test set.

### 2.5 Computed for this file (labels, session boundaries, literature and public metadata only; no model, no decode)

- The pool counts of section 4.6 (computed twice during the design, with identical results) and the tile counts of
  section 4.3: Walch 142 tiles cover 12 994 of 12 994 session minutes, BIDSleep 1064 tiles cover 103 231 of 103 339.5
  (108.5 min in 5 nights stay uncovered).
- The rule-I recomputation of every arm-based quantity (section 5, Appendix B) and the targets and bands at the anchors,
  the mid-anchors and Table 1's group TSTs.
- The literature reweighting of section 5.5 on all 31 Walch subjects (instantaneous) with these targets: it converges
  at every anchor and mid-anchor with a Kish effective sample size of 44.0–108.7 sessions (anchors) and 46.0–104.8
  (mid-anchors). BFGS alone stopped at a relative constraint violation of 1.2 × 10⁻⁸ at the 45-min mid-anchor; one
  Newton step on the same dual reached < 10⁻⁹, hence the Newton polish of section 5.5. The reweighted pool's "all LIGHT"
  accuracy is 0.759 / 0.599 / 0.561 / 0.590 / 0.632 / 0.647 at 30 / 60 / 90 / 120 / 150 / 170 min, mean **0.631**
  (BIDSleep 0.634); at the mid-anchors mean 0.604 (both datasets); its mean marginals are about 9 / 63 / 18 / 10 %
  (A / L / D / R).
- The true regime contrasts of gate F at 60 and 90 min: on shares of sleep, over the Walch subjects with sessions in
  both regimes (27 subjects): DEEP 31.3 pp, REM 25.4 pp; BIDSleep (47 subjects) 48.7 / 26.2 pp. On window shares over
  all sessions (reported): Walch 29.9 / 23.6 pp, BIDSleep 42.4 / 25.1 pp.
- The true LATE DEEP tail of gate R2 (sessions with ≥ 50 % true DEEP): Walch 2 / 106 at 60 min and 0 / 101 at 90 min;
  BIDSleep 16 / 939 and 1 / 831.
- The genuine-nap false-flag rates of the guards G and GL (section 2.3) and the genuine-nap rate of "DEEP ≥ 50 % of the
  session window" (4 / 58 young lab naps; no nap sits at exactly 50 %).
- The epoch totals of the evaluation sessions, for the disk budget (section 18): per variant, Walch 93 449 session
  epochs + 12 994 night epochs, BIDSleep 739 147 + 103 340 (mid-anchor pools not included).
- YASA: PyPI release dates checked on 2026-10-15 (0.7.0 on 2026-03-08, 0.8.0 on 2026-10-02, less than two weeks old);
  the 0.7.0 wheel (`yasa-0.7.0-py3-none-any.whl`, 33 848 495 bytes, SHA-256
  `114d9cab3dd8237847657b45540aa5df1edd27eea0bd590b8154a6fb2426b0c9`) was downloaded and its file list read: the
  LightGBM classifiers ship inside it (`yasa/classifiers/*.joblib`), nothing is downloaded at staging, and
  `clf_eeg+eog+emg_lgb_0.5.0.joblib` has SHA-256 `3d27d440c94c61d8013c81c91693ad88810af2ddae85b62f4bc797f6e2464beb`. No
  YASA code was run.

### 2.6 Motivation

The experiment was motivated by nap estimates of one user with DEEP shares above the literature corridors. Those data
were never read; no number of this file comes from them.

### 2.7 Not known

Any number of C0, C1 or C2; any model's numbers on the M, R and mid-anchor pools at lengths other than 60 / 90 min, on
the tiles or on LATESTART sessions; N1's numbers under the metrics of section 7 that the diagnosis did not compute (AUC
per length cell, featureless check at the length grid, the reweighted pools, sleep-share contrasts); any decode of
ds006576; YASA's staging of ds006576.

## 3. Design in brief

The evidence model (C1, C2) may learn only from the heart rate: the session-time and step features are masked with the
existing masked-column mechanism (`FEATURE_SET_VERSION` 1 and the bundle format unchanged); it is trained on **tiles**
— every night cut into consecutive, non-overlapping nap-length windows, so every night minute counts once and every
position of the night is represented — with each dataset given half of the weight; its HSMM statistics are those of
the night model (position-neutral); naps are decoded **without heart-rate context on either side** (a new nap-only
parameter `napMode.contextMarginMin` = 0, which training and the app match exactly); the time prior is off. Its stage
mix is anchored either to the night truth of the tiles (C1, one constant bias) or to the published composition of
afternoon naps by session length (C2, a per-length bias table fitted on labelled windows reweighted to that
composition). C0 keeps N1's classifier and changes only its level to the literature table. Rule (i) admits a candidate
only if it keeps N1's per-epoch discrimination (D), follows the evidence across regimes (F) and is realistic on
featureless sessions, late windows and the reweighted pools at and between the anchors, and no further from the
literature than N1 (R); among those it picks the one closest to the literature. If none passes all three, a
candidate that passes D and R and does not follow the evidence worse than N1 (F0) is adopted in a second tier: its
level is realistic but its evidence-following is not shown, so rule (ii) cannot give it STAGES. Rule (ii) sets the output policy, for
N1 and for the adopted candidate. Real naps of ds006576 are decoded only after both rules are written; they can block an
installation or demote STAGES, never choose or promote (rule (iii)).

| name | classifier | statistics | level (nap stage log-bias) | context | decoder |
|---|---|---|---|---|---|
| N1 (reference) | 13.12 nap MLP (with time / step features) | 13.12 nap statistics | constant, fitted to onset-proxy truth | night's (30 min) | θ_nap,g of 13.12 |
| C0 | as N1 | as N1 | per-length table, literature target | night's | as N1 |
| C1 | evidence model | night model's | constant, fitted to tile truth | none (0) | θ_E,g (8-point grid) |
| C2 | evidence model | night model's | per-length table, literature target | none (0) | θ_E,g |

## 4. Data, folds and sessions

### 4.1 Datasets, folds, night parts

- Pool: the 78 subjects of `walch+bid-zero` (31 Walch = PSG, headline; 47 BIDSleep = Dreem headband, secondary), both
  headline variants (`tools/train/data/nights/{instantaneous,mean2min}/`). Leave one subject out: everything fitted for
  a held-out subject excludes every session of every night of that subject, of every kind.
- Outer groups, selection sets and inner folds: those of run `bias-base` (`--selection per-group`, 5 dataset-stratified
  groups, seed 0), read as `napmodel.Inputs` reads them (selection set = the pool minus the group; inner folds =
  `innerFolds`; inner scoring on the selection set's Walch subjects; inner folds without a Walch subject are skipped).
- Night side of every fold bundle: the subject's outer fold night bundle of `bias-base` + b_g (`bias-base/bias`,
  candidate B) + b_short_g(180) (`naps/fits.json`), night decoder θ_g, β = 1 — the N0 fold bundle of 13.12. It is never
  changed; every candidate is added to it as its `nap` section (`napmodel.n1_bundle`, the app's code path).
- Read only: `bias-base` (with its kept inner fold night models `work/walch+bid-zero/<variant>/mlp/<key>/models`),
  `bias-base/bias`, `naps`, `naps-posthoc`, `nap-model`, `nap-model-final`, `nap-model-verify`.

### 4.2 Common cut

Every session is cut from the OHealth-like session [t0, t1] of a night of both variants by `napmodel.cut_window`
(`naps.nap_proxy` for onset sessions at the anchors), unchanged: heart rate with s0 − 30 min ≤ t ≤ s1, step records
ending in (s0 − 30 min, s1], label epochs fully inside [s0, s1], nothing after s1. Both variants get identical windows
(the cut depends on the labels and the session boundaries only; checked). MIN = 60 000 ms; wake bouts are
`napmodel.wake_bouts` (maximal runs of AWAKE label epochs; another stage or a label gap ends a run). Anchor lengths
A = {30, 60, 90, 120, 150, 170} min; mid-anchor lengths A_mid = {45, 75, 105, 135, 160} min (the midpoints of A).

### 4.3 Training tiles (kind `tile`)

Per night: k = 0, t = t0; while t1 − t ≥ 30 min: i_k = (the SHA-256 hex digest of the UTF-8 string
`"<night key>|tile|<k>"`, read as a base-16 integer) mod 6, L = A[i_k]; if t + L > t1, L = the largest length of A
with t + L ≤ t1; tile k = [t, t + L], id `<night id>-tile<k>`; t ← t + L; k ← k + 1. A remainder r = t1 − t > 0 is
added to the last tile when that tile's length + r < 180 min, otherwise it stays uncovered. `night key` is the night id
without its variant suffix (`napmodel.night_key`). Every covered night minute lies in exactly one tile; the first tile
of a night starts at the OHealth-like session start, the others wherever the previous one ended.

### 4.4 Evaluation pools (EVAL) and the mid-anchor pool

| pool | rule | id |
|---|---|---|
| **O** onset | s0 = t0, s1 = t0 + L, L ∈ A (`naps.nap_proxy`) | `<night>-nap<L>` |
| **W** ends-in-wake | `napmodel.sample_windows`, unchanged (13.12) | `<night>-ew<m>` |
| **M** mid-night | s0 = t0 + o, s1 = s0 + L, o ∈ {120, 180, 240, 300} min, L ∈ A, kept when s1 ≤ t1 | `<night>-mid<o>x<L>` |
| **R** return to sleep | for every wake bout [b0, b1) with b1 − b0 ≥ 2 min and b1 − t0 ≥ 180 min: s0 = b1 − min(5 min, b1 − b0), s1 = s0 + L, L ∈ A, kept when s0 ≥ t0 and s1 ≤ t1 | `<night>-rs<(s0 − t0)/MIN:g>x<L>` |
| **MIDANCHOR** | the O, M and R rules with L ∈ A_mid (onset: s0 = t0, s1 = t0 + L, the same cut), not part of EVAL | `<night>-nap<L>`, `<night>-mid<o>x<L>`, `<night>-rs<…>x<L>` |

Regimes: **EARLY** = O; **LATE** = M with o ≥ 180 ∪ R; **OTHER** = M with o = 120 ∪ W; **ANCHOR** = O ∪ M ∪ R (all
at anchor lengths); **MATCHED** = EARLY and LATE sessions with L ∈ {60, 90}. Windows of different pools may overlap in
time; each is a session of its own and the subject is the unit of every interval.

### 4.5 Reported-only and synthetic sessions

The committed `pulsetrain/perturb.py` (perturbations) and `pulsetrain/pools.py` (featureless sessions, LATESTART) are
the authoritative definitions; the prose below describes them, and a unit test asserts each prose example against the
code.

- **TILE**: the held-out subject's own tiles.
- **LATESTART**: for L ∈ {60, 90} and δ ∈ {10, 20} min, [t0 + δ, t0 + L] with the labels inside, id
  `<night>-ls<δ>x<L>` (an onset window whose session begins late).
- **Perturbed** O and LATE sessions with L ∈ {60, 90} (both datasets), by the definitions of the diagnosis: `noctx`
  (heart rate before s0 and step records ending ≤ s0 removed); `ctxsyn+10` (the pre-context replaced by 15 samples every
  2 min from s0 − 30 min at the session median + 10 bpm, −1 / +1 bpm alternating starting with −1; step records ending
  ≤ s0 removed); `post+20` (samples every 2 min after the last sample up to s1 + 30 min, those after s1 kept, at the
  session median + 20 bpm, ± 1 alternating); `app+15steps` (`ctxsyn` and `post` at median + 15 bpm, with 2-min step
  records of 30 steps in [s0 − 30 min, s0] and (s1, s1 + 30 min]); `step3mid` (one 1-min record of 3 steps at s0 +
  ⌊L / 2⌋ min); `amp0.5`, `amp1.5` (every sample m + a · (h − m), m = the median of the samples at or after s0). The
  session median is that of the samples at or after s0. A perturbed copy has the id `<original id>~<perturbation>` and
  its metadata names the original.
- **FEATURELESS** (synthetic; gate R1): every combination of L ∈ A ∪ A_mid, design d ∈ {`flat`, `pre10` (+10 bpm on
  every sample before s0), `post12` (+12 bpm on every sample after s1)}, noise SD σ ∈ {1, 2, 3} bpm and seed k ∈ {0, …,
  19}: 1980 sessions; s0 = 3 600 000 ms, s1 = s0 + L min; heart-rate samples every 120 s from s0 − 30 min + 60 s while
  t ≤ s1 + 30 min, value round(60 + ε) + the design's shift, ε ~ N(0, σ²) from
  `numpy.random.default_rng([L, i_d, σ, k])` (i_d = 0 / 1 / 2); no steps; a placeholder label LIGHT for every 30-s epoch
  of [s0, s1) (only the predicted column sums of the confusion matrix are used). Each session is copied once per Walch
  subject (subject set to that subject) with the id `syn-<d>-L<L>-sd<σ>-k<k>-<subject>`, so that every id is unique;
  the copies of a group's held-out Walch subjects are decoded in one call per (variant, group, model) with that group's
  parameters and each subject's fold bundle (61 380 decodes per model and variant).
- Every session file written by the run has unique ids (unit-tested; algo-cli refuses duplicates).

### 4.6 Counts to expect (per variant; both variants have the same windows; section 2.5)

| pool | Walch sessions (subjects) | BIDSleep sessions (subjects) |
|---|---|---|
| O | 186 (31) | 1518 (47) |
| W | 11 (9) | 139 (45) |
| M, o = 120 (L 30 / 60 / 90 / 120 / 150 / 170) | 171 (31): 31 / 31 / 30 / 27 / 26 / 26 | 1505 (47): 253 / 253 / 253 / 253 / 249 / 244 |
| M, o ≥ 180 | 439 (30): 82 / 78 / 75 / 74 / 69 / 61 | 3359 (47): 722 / 684 / 616 / 535 / 433 / 369 |
| R | 141 (15): 32 / 28 / 26 / 23 / 18 / 14 | 1208 (47): 314 / 255 / 215 / 180 / 136 / 108 |
| **EVAL** | **948** | **7729** |
| MIDANCHOR (L 45 / 75 / 105 / 135 / 160) | 789 (31): 173 / 165 / 161 / 152 / 138 | 6353 (47): 1498 / 1389 / 1290 / 1144 / 1032 |
| tiles | 142 (31 nights) | 1064 (253 nights) |
| whole nights | 31 | 253 |

A session is counted once even if it is later NOT_ESTIMATED; the run reports both. A count that differs from this
table is reported with its reason; it does not stop the run (gate G7).

## 5. Literature targets, bands and guards

Stage mapping: N1 + N2 = LIGHT, N3 (R&K 3 + 4) = DEEP, REM = REM. Shares are % of total sleep time (TST = LIGHT + DEEP
+ REM) unless stated: reported by the authors where they report them, otherwise the ratio of group means (the
convention of the notes). The arm table is Appendix B, held by `litprior.py`; "derive" = recomputed from it by the unit
test. Bins of TST for arm medians and ranges are half-open, **[lo, hi)** (an arm with TST exactly 80 belongs to
[80, 110)); the per-nap guard rows of section 5.3 are written as explicit inequalities.

### 5.0 Inclusion rule I (one rule for every arm-based quantity)

A published arm enters a median, range, band, guard bound or the WASO share only if all of the following hold; every
other arm of the notes is listed in Appendix B with the first reason that excludes it.

| | criterion | excluded by it (Appendix B) |
|---|---|---|
| I1 population | healthy adults of about 18–35 years | older adults, adolescents, athletes (Sargent 2026, Boukhris 2026) |
| I2 prior sleep | class R0 (no restriction: habitual or prior sleep about 7 h or more, or a regular schedule described without restriction) or R1 (habitual short or late sleepers, 5–7 h, no acute restriction), as classed in the synthesis's arm table | R2 (experimental restriction to 4–6.5 h), R3 (total deprivation), and arms whose prior sleep is not described (R?, Evans 1977) |
| I3 clock | the nap starts in the afternoon (11:00–17:00) by a time or protocol stated in the source | morning and evening arms; arms whose start time is not stated (Lau, Alger & Fishbein 2011) |
| I4 measurement | polysomnography (laboratory or home) scored by human scorers (R&K or AASM) | wearable or automatic staging (Boukhris 2026) |
| I5 independence | no stimulation during the nap; not a probable duplicate of an included sample; not the test set's paper | Nicolas 2022 (auditory cueing), Subramoniam 2026 (overlaps Chen 2021), Cellini 2016 (overlaps Whitehurst 2016 / 2018), McDevitt 2026 |
| I6 value | the quantity is reported or derivable as a ratio of group means | arms without it (e.g. Lee 2017: only a bound on SWS + REM) |

Rule I keeps 26 arms (Appendix B). Under it: the [0, 15) bin has 1 arm, [15, 45) 2 arms (both R1), [45, 80) 18 arms
(14 R0, 4 R1), [80, 110) 5 arms for DEEP and 4 for REM (all R0), and no arm has TST ≥ 110 min.

### 5.1 Population default composition of an afternoon nap (targets of C0 and C2)

Every row is the median of the rule-I arms of its TST bin (derive), placed at the nominal TST of the synthesis's row
(30, 60, 90 min) or, for the single arm of [0, 15), at that arm's TST:

| TST row (min) | bin | DEEP % of TST | REM % of TST | arms (k) |
|---|---|---|---|---|
| 11.53 | [0, 15) | 2.57 | 1.50 | Leong et al. 2023, 10-min arm (reported % of TST; k = 1) |
| 30 | [15, 45) | 25.07 | 3.85 | Leong 2023 30-min (27.45 / 0.83, reported), Mednick et al. 2002 short (22.68 / 6.87, Table 1) (k = 2; both R1) |
| 60 | [45, 80) | 28.55 | 11.62 | 18 arms (14 R0, 4 R1; Appendix B) |
| 90 | [80, 110) | 23.00 | 15.01 | DEEP 5 arms, REM 4 arms (all R0; Appendix B) |
| > 90 | — | 100 · 20.7 / TST | 15.01 | **N3-minute plateau**: DEEP minutes held at their 90-min value 0.2300 × 90 = 20.7 min. Support: the rule-I arms with TST ≥ 45 min have 11.4–47.2 min of N3, median 19.7 (k = 20); 1-h vs 2-h naps 30.4 vs 36.2 min, p = 0.110 (Sargent et al. 2026, athletes, cited for the shape only); SWS falls from high initial levels at any clock time (Åkerstedt & Gillberg 1981) and declines exponentially with time asleep (Knowles et al. 1986). REM held at the 90-min row (no rule-I arm beyond 110 min) |

Linear interpolation in TST between the rows up to 90 min; constant below 11.53 min. The rows are % of TST throughout;
the synthesis's 120- and 150–180-min central values, which are shares of the sleep period (AWAKE included), are not
used. LIGHT = 100 − DEEP − REM.

The [0, 15) and [15, 45) rows rest on R1 arms only, because no R0 arm with stage numbers exists at these lengths (the
rested evidence there is review-level: no SWS in 8 of 13 well-rested short-nap studies, up to 23 % of TST, Hilditch et
al. 2017). This is the arm-median rule applied without exception; the previous draft's 30-min row (DEEP 10.0, REM 1.0,
a rounded judgement of the cross-check from the rested review level, the R1 arms and the acutely restricted OSF cohort)
is replaced by it. Reported only (section 7.5): LitDist under a sensitivity table without the 30-min row (11.53 → 60
linear: D* / R* at the anchors 5.79 / 2.75, 19.89 / 8.24, 26.67 / 12.76, then as below).

**Session length → TST.** TST_est(L) = (1 − a) · (L − 10 min), a = **0.12317** = the median WASO share of the sleep
period, WASO / (TST + WASO), over the 11 rule-I arms that report WASO (derive: the median arm is Leong 2023 30-min,
4.45 / (31.68 + 4.45); range 5.4–19.1 %); 10 min = the two 5-min OHealth-like margins of the training windows (an
assumption about OHealth, section 2.2). D*(L) and R*(L) = the rows above at TST_est(L). At the anchors and mid-anchors
(computed by the code from these rules; this table is the unit test's reference, two decimals):

| L (min) | TST_est (min) | D* % of TST | R* % of TST | DEEP minutes |
|---|---|---|---|---|
| 30 | 17.54 | 9.89 | 2.26 | 1.7 |
| 45 (mid) | 30.69 | 25.15 | 4.03 | 7.7 |
| 60 | 43.84 | 26.67 | 7.43 | 11.7 |
| 75 (mid) | 56.99 | 28.20 | 10.84 | 16.1 |
| 90 | 70.15 | 26.67 | 12.76 | 18.7 |
| 105 (mid) | 83.30 | 24.24 | 14.25 | 20.2 |
| 120 | 96.45 | 21.46 | 15.01 | 20.7 |
| 135 (mid) | 109.60 | 18.89 | 15.01 | 20.7 |
| 150 | 122.76 | 16.86 | 15.01 | 20.7 |
| 160 (mid) | 131.53 | 15.74 | 15.01 | 20.7 |
| 170 | 140.29 | 14.75 | 15.01 | 20.7 |

Reported only (not fitted): the same table under the alternative mapping TST = (1 − a) · L (no margins) at the anchors:
20.56 / 3.38, 27.69 / 9.70, 25.05 / 13.76, 19.67 / 15.01, 15.74 / 15.01, 13.89 / 15.01.

These are **afternoon** targets of healthy young adults pooled over rested and habitually short sleepers. Morning naps
are REM-richer and DEEP-poorer (Bes et al. 2009: SOREM 83 % at 08:00 vs 0 % at 20:00–22:00; Tinguely et al. 2006:
morning sleep 0.51 × the SWS and 2.0 × the REM of the night onset). The app has no clock input here (Appendix A), so
every nap gets the afternoon default.

### 5.2 Tier-1 band (range of study means; for pooled or mean shares of many sessions, never for one nap)

The band at TST t is the range of the rule-I arm means the target at t is computed from:

| t (min of sleep) | DEEP % of TST | REM % of TST | arms |
|---|---|---|---|
| t ≤ 11.53 | 2.57–2.57 | 1.50–1.50 | [0, 15) |
| 11.53 < t ≤ 30 | 2.57–27.45 | 0.83–6.87 | [0, 15) ∪ [15, 45) |
| 30 < t ≤ 60 | 13.00–46.43 | 0.83–18.72 | [15, 45) ∪ [45, 80) |
| 60 < t ≤ 90 | 13.00–49.01 | 5.10–26.58 | [45, 80) ∪ [80, 110) |
| t > 90 | 100 · 11.43 / t – 100 · 47.20 / t | 5.70–26.58 | DEEP: the N3-minute range of the 20 rule-I arms with TST ≥ 45 min, over t (the plateau rule); REM: the [80, 110) arms (REM held) |

The target always lies inside its own band (unit-tested). Values used below (two decimals in the code):

| where | t | DEEP band | REM band |
|---|---|---|---|
| L = 30 | 17.54 | 2.57–27.45 | 0.83–6.87 |
| L = 45, 60, 75 | 30.69, 43.84, 56.99 | 13.00–46.43 | 0.83–18.72 |
| L = 90, 105 | 70.15, 83.30 | 13.00–49.01 | 5.10–26.58 |
| L = 120 / 135 / 150 / 160 / 170 | 96.45 / 109.60 / 122.76 / 131.53 / 140.29 | 11.85–48.94 / 10.43–43.07 / 9.31–38.45 / 8.69–35.89 / 8.15–33.64 | 5.70–26.58 |
| ds006576 NREM group, Table 1 TST | 49.27 | 13.00–46.43 | 0.83–18.72 |
| ds006576 REM group, Table 1 TST | 89.91 | 13.00–49.01 | 5.10–26.58 |

**Lower bounds** apply only where most naps contain the stage: at t ≥ 45 min, where the rule-I arms' zero-REM rate is
below one half (69 / 158 = 43.7 % at about 60 min: Mednick 2003 60-min 13 / 26, Chen 2021 31 / 60, Bergamo 2026
10 / 19, Cai 2009 12 / 40, Fogel 2014 3 / 13; 21 / 80 = 26.2 % at 80–105 min: Whitehurst 2016 13 / 48, Baran 2016
6 / 13, Mednick 2003 90-min 2 / 19) and zero N3 is uncommon (section 5.4). Below 45 min of sleep a nap without N3 or REM
is common (N3 latency median 21.5 min, REM latency ≥ 37 min in afternoon naps; no N3 in 0 of 9 genuine naps with
≤ 15 min of sleep), so only the upper bounds are used there.

### 5.3 Tier-2 guards (per nap; a composition violates a guard when any of its rules holds)

**GL — the Tier-2 guard of every decision** (R3, rule (iii)(c); reported in R1(c)). A literature-only guard built
under rule I: per TST row, the largest mean + 2 SD of the DEEP and REM shares over the rule-I arms of that row whose
dispersion converts to an SD of the share without assumption (a reported SD of the share or of the minutes over the
arm's TST, or a SEM × √n with a verified n). Not convertible, hence not used for GL: Mednick 2002 (n not verified),
Mednick 2003 (dispersion labelled s.d., probably SEM), Cai 2009 (group-wise SEMs), Dinges 1987 (means only), Nishida
2007 (stage minutes inconsistent with the total; the synthesis also left it out of its dispersion bounds), Fogel 2014
REM (no dispersion). No individual nap data, no McDevitt 2026, no Zenodo cohort enter GL. Afternoon rows, applied to
every nap; rows by TST.

| TST (min) | violation | bound from (the rule-I arm with the largest mean + 2 SD; all eligible arms in Appendix B) |
|---|---|---|
| TST ≤ 15 | DEEP > 19.03 % of TST; DEEP > 54.0 min | Leong 2023 10-min (mean 2.57 %, mean + 1 SD 10.8 %); no REM dispersion at this length |
| 15 < TST ≤ 45 | DEEP > 72.71 %; REM > 9.59 %; DEEP > 54.0 min | Leong 2023 30-min (27.45 ± 22.63; 0.83 ± 4.38 % of TST) |
| 45 < TST ≤ 75 | DEEP > 80.62 %; REM > 57.72 %; DEEP > 54.0 min | Chen et al. 2021 young (N3 SEM 1.96 min × √60 over TST 61.50); Lo et al. 2014 (REM 12.0 ± 12.5 min over 64.1) |
| TST > 75 | DEEP > 62.95 %; REM > 41.24 %; DEEP > 54.0 min | Whitehurst 2016 (22.71 ± 14.83 and 13.15 ± 10.58 min over 83.19); no rule-I arm with a dispersion beyond 105 min, so the row is carried |
| every row | DEEP > 54.0 min | the larger of the largest rule-I arm mean + 2 SD of N3 minutes (Scullin et al. 2017 young, 53.24) and the largest published individual value in a non-deprived daytime nap (54 min, Whitehurst et al. 2018) |

GL's rows are (lo, hi] by TST, as in the cross-check. False-flag rate of GL as applied here (out of sample: GL uses no
individual data): 2 / 70 genuine naps outside the Zenodo set = 2.9 % (Wilson 95 % CI 0.8–9.8 %) and 4 / 93 = 4.3 %
(1.7–10.5 %) with it. The flagged naps are two Sleep-EDF bouts with 10.5 % and 11.4 % REM in 28.5 and 22 min of sleep
and two Zenodo naps with 70.7 / 69.7 % DEEP (59.0 / 57.5 min).

**G — the revised guard of the cross-check** (reported only, for comparison with the notes; afternoon rows applied to
every nap). G is not a rule-I quantity: it uses Nicolas 2022, Gilson 2016 (morning, restricted) and Sargent 2026
(athletes), and it was set partly in sample (its 75 % / 60-min / LIGHT bounds sit just above the Zenodo values; its
REM ≤ 45 bound was set after two Sleep-EDF bouts with 10.5 and 11.4 % REM). False-flag rate as applied here: 0 / 93
(Wilson 95 % CI 0–4.0 %), 0 / 70 outside Zenodo (0–5.2 %).

| TST (min) | violation | sources |
|---|---|---|
| TST ≤ 15 | DEEP > 11 % of TST | Leong 2023 10-min arm mean + 1 SD 10.8 %; Brooks & Lack 2006 10-min 9 %; no SWS below 15 min of sleep (Fushimi & Hayashi 2008, n = 101) |
| 15 < TST ≤ 105 | DEEP > 75 % of TST; DEEP > 60 min; LIGHT < 25 % of TST; REM > 30 % of TST (TST ≤ 45) or > 40 % (TST > 45) | DEEP share mean + 2 SD: Leong 2023 72.7 % (30 min) / 77.4 % (60 min), Nicolas et al. 2022 74.5 %; individual maximum 70.7 % (Zenodo). DEEP minutes: Nicolas 2022 mean + 2 SD 58.9 min; Zenodo maxima 59.0 / 57.5 min. LIGHT: three Zenodo naps at 29.3–31.0 %. REM ≤ 45: Gilson et al. 2016 short arm mean + 2 SD 38.6 %; REM > 45: mean + 2 SD Leong 2023 60 min 40.2 %, Whitehurst et al. 2016 41.2 %, McDevitt et al. 2012 43.4 % |
| TST > 105 | DEEP > 60 min; LIGHT < 25 % of TST; REM > 25 % of TST | DEEP minutes as above; REM: Sargent 2026 2-h arm mean + 1 SD 25.2 % |

### 5.4 Genuine-nap reference rates (reported next to the corresponding model rates; nothing is fitted to them)

| quantity | value | source |
|---|---|---|
| DEEP ≥ 50 % of the session window | 4 / 58 = 6.9 % (2.7–16.4 %) of genuine young-adult lab naps (no nap at exactly 50 %) | Zenodo + OSF chav7 (recomputed for this file) |
| zero REM, ~60-min afternoon naps | 69 / 158 = 43.7 % (paper-level counts of five rule-I arms) | Mednick 2003, Chen 2021, Bergamo 2026, Cai 2009, Fogel 2014 |
| zero REM, TST 80–105 min | 21 / 80 = 26.2 % | Whitehurst 2016, Baran 2016, Mednick 2003 90-min |
| zero DEEP, TST ≥ 55 min | 7 / 60 = 11.7 % (Chen 2021, rule-I arm); 4 / 23 = 17.4 % (Zenodo, individual) | |
| N3 latency after sleep onset | minimum 8.5 min, median 21.5 (n = 35) | open hypnograms |
| REM latency | minimum 43.5, median 59.0 min (n = 18), rare sleep-onset REM exceptions | Zenodo; Sleep-EDF |
| AWAKE (WASO) share of the sleep period | rule-I arms 5.4–19.1 %, median 12.3 % (k = 11) | section 5.1 |
| heart rate in afternoon naps | wake → N2 / N3 −3 to −5 bpm; N2 ≈ N3; REM +2 to +5 bpm; nap NREM 8–19 bpm above night NREM in the same people (figure read) | Chen 2021; Cellini 2016 (quoted for physiology only; excluded from the arm medians by I5); Whitehurst 2018 |

### 5.5 The literature-reweighted pools (LIT, LIT_mid)

**Goal.** A labelled pool whose true stage mix equals the published nap composition, built from the ANCHOR sessions
(LIT) and from the MIDANCHOR sessions (LIT_mid); W is excluded because its lengths are not anchors.

**Weights**, per (dataset, variant, set of sessions, length L):

1. Base weights q_i = 1 / (n_s(i) · n_subj): n_s(i) = the number of length-L sessions of session i's subject in the set,
   n_subj = the number of subjects; every subject has equal mass.
2. Tilting: w_i ∝ q_i · exp(λ1 g1_i + λ2 g2_i), the KL(w ‖ q)-minimal weights with Σ_i w_i g1_i = 0 and
   Σ_i w_i g2_i = 0, where g1_i = deep_i − D*(L) · sleep_i and g2_i = rem_i − R*(L) · sleep_i (deep_i, rem_i, sleep_i =
   session i's true DEEP, REM and non-AWAKE 30-s label chunks).
3. λ by BFGS on the convex dual log Σ q_i exp(λ · g_i), with g scaled column-wise by its maximum absolute value, then
   Newton steps on the same dual (at most 50) until the largest relative constraint violation
   |Σ w g| / Σ |w| |g| is < 10⁻⁹; converged when it is. The weighted pooled true DEEP and REM shares of sleep then equal
   the target; AWAKE and LIGHT are not constrained.

**Feasibility rule.** If the dual does not converge or the Kish effective sample size (Σw)² / Σw² is below 10 sessions,
the target is replaced by T(λ) = (1 − λ) · T* + λ · T_q (T_q = the base-weighted pooled composition), with λ the
smallest value in {0.05, 0.10, …, 1} that converges with an effective sample size ≥ 10; λ is reported. (On all 31
Walch subjects every anchor and mid-anchor converges without it, section 2.5.)

**Pooling.** The LIT confusion matrix of a model over a set of sessions: per length, Σ_i w_i C_i (C_i = the session's
4 × 4 matrix of scored chunks), normalised to total 1; then the mean of the length matrices. The weights depend on the
labels only (computed from all label chunks of the session), so every model is scored on the same weighted pool.

**Uses.** In the inner folds (C0 / C2 table fits) the weights come from the labels of the inner held-out Walch
subjects' ANCHOR sessions that the inner fold model estimates with HYBRID (estimation status does not depend on the
level, so the set is fixed after the first decode); in the evaluation, from the labels of the held-out Walch subjects'
ANCHOR (MIDANCHOR) sessions in the common set E∩ of section 7.1, per variant; BIDSleep likewise, reported only.

## 6. Reference and candidates

### 6.1 N1 (reference; also what is kept if nothing is adopted)

13.12's N1 at fold level, unchanged: the subject's N0 fold bundle with the stored outer nap model
`nap-model/outer/<variant>/nap/<subject>.json` as its nap section and the group's nap stage log-bias
(`nap-model/selections.json`), nap decoder θ_nap,g, `napMode.contextMarginMin` absent (null = the night's 30 min).

### 6.2 The evidence model (shared by C1 and C2)

- **Context policy.** `napMode.contextMarginMin` = 0 in every feature computation, fit and decode (section 16): in NAP
  mode core-algo keeps only heart-rate (and SpO2, respiratory-rate, HRV) samples with s0 ≤ t ≤ s1 and step records
  ending in (s0, s1], and builds the epoch grid without context epochs. Training and serving then see exactly the same
  input, whatever the app or a cut supplies around the session; a daytime pre-context and a post-nap context cannot
  reach the features. Nights are unaffected (NIGHT mode never reads the field).
- **Masked features** (5 of 40, by name): `t`, `hoursSinceStart`, `hoursToEnd`, `stepsLog1p`, `stepsWin5Log1p`, through
  `models.fit_classifier(..., masked=...)`: standardization mean 0 / std 1e12, the training column 0, the first-layer
  weights exactly 0 after fitting (logistic regression: the coefficient columns 0). The explicit step rules of the
  decoder (≥ 5 steps → forced AWAKE, 1–4 steps → ln 3 for AWAKE) stay.
- **Training rows.** Per fit, the epochs with heart-rate data and a label of all tiles of the fit's training subjects,
  rows of both variants (instantaneous first, then mean2min), the 40 features exactly as `algo-cli features` computes
  them for the tile session files with the candidate parameters (section 6.6). The session files are checked byte for
  byte against the decoded tiles (`napmodel.check_sessions_file`).
- **Row weights.** For training subject set S, dataset d, S_d = its subjects in S, n_s = the training rows of subject s
  (both variants together): u_i = ½ · 1 / (|S_d| · n_s(i)), w_i = u_i / mean(u) over the fit's rows. Each dataset
  carries half of the weight and each subject the same weight within its dataset (the Dreem headband over-calls N3
  against PSG by +9.1 ± 19.5 min per night, Arnal et al. 2020; 89 % of 13.12's rows were BIDSleep). Passed as
  `sample_weight`; `class_weight` none (weights and class weighting together are refused).
- **Classifier.** Multinomial logistic regression (lbfgs, L2, C ∈ {0.01, 0.1, 1}; C = the lowest unweighted mean
  log-loss on the inner held-out Walch rows of the evaluated variant, as in `napmodel.select_group`) or an MLP 2 × 32
  (relu, adam, α 1e-4, batch 256, early stopping on 10 %, seed 0); type and C chosen per (variant, group) in the nested
  inner CV.
- **Class priors** = the weighted class frequencies of the training rows (the distribution the posterior is calibrated
  to); the exported prior shift is 0.
- **HSMM statistics** (`durations`, `boutTransitions`, `initialStage`, `timePrior`) are **copied** from the night model
  fitted on the same training subjects and variant: for an inner fold, from `bias-base`'s kept inner fold night model
  of that fold (`work/walch+bid-zero/<variant>/mlp/<key>/models/<subject>.json`, any Walch subject of the fold; the
  copies of all its Walch subjects must be equal); for an outer fold, from the subject's outer fold night bundle; for
  the final model, from the shipped main model. Whole-night statistics are position-neutral (every statistic fitted on
  night-derived windows inherits their position mix), they start a session in wake (`initialStage` about 0.98 AWAKE, as
  a nap starts), and the diagnosis found their durations closer to the evidence (−2 to −5 pp DEEP).
- **Decoder grid** (8 points, as `napMode.hybrid`): durationModel {EXPLICIT, GEOMETRIC} × emissionWeight {1, 0.7, 0.5,
  0.3} × timePriorWeight {0}, in the order of `loso.grid_points` over a new grid `nap-evidence`. The time prior is
  P(stage | time since the session start), the template that the masked features remove; 13.12's nested CV chose
  weight 0 in all 10 selections.
- **Nested inner CV** per (variant v, group g), never touching g's subjects: for every inner fold, logistic regression
  (each C) and the MLP fitted on the inner training subjects' tile rows (both variants, weights recomputed for that
  subject set), statistics copied as above; one `algo-cli evaluate --param-grid` per classifier on the inner held-out
  Walch subjects' tiles of variant v, each decoded by its inner fold model as a standalone bundle (main model and nap
  section both that model, no stage log-bias; `napmodel.standalone`) with the candidate parameters. Inner score = mean
  per-subject Cohen's κ (4 classes, each subject's HYBRID-estimated tiles pooled). Highest score wins; ties → logistic
  regression before the MLP, then grid order. C1 and C2 share this choice (classifier, C, θ_E,g).
- **Outer models.** Per held-out subject s of group g: g's classifier (and C) fitted on the tiles of the 77 other
  subjects with their weights, statistics copied from s's outer fold night bundle (variant v), installed as the nap
  section of s's N0 fold bundle with C1's bias or C2's table, decoded at θ_E,g.

### 6.3 C1: evidence model, night-level constant bias

Nap `stageLogBias` = the fixed point of `bias.fit_stage_bias`, unchanged (start 0, η 1 halved on a non-improving step,
at most 8 decodes, stop at D ≤ 0.005), per (variant, group), on the inner out-of-fold decodes of the **tiles** of g's
inner held-out Walch subjects, each by its inner fold model of g's chosen classifier and C at θ_E,g; target = their true
pooled shares (every night position once). C1 asks whether an evidence-following model needs any nap-specific level.
Its level is fitted to night truth, not to nap compositions; rule (i)'s R5 keeps it from being adopted when that level
is further from the published nap composition than N1's.

### 6.4 C2: evidence model, literature-anchored per-length bias

A per-length table `stageLogBiasByLength` (section 16) with anchors A in the nap section, no scalar nap `stageLogBias`;
a session of length L (minutes, end − start) gets the row interpolated linearly in L, the first / last row outside
30–170 min. Fitted per (variant, group) on the inner out-of-fold decodes of the **ANCHOR** sessions of g's inner
held-out Walch subjects (each by its inner fold model of g's choice at θ_E,g), LIT-weighted with weights computed on
these subjects' labels (section 5.5): one algo-cli call per iteration decodes all anchors with the current table (a
session of length exactly L gets exactly row L), and each anchor runs the unchanged fixed point (start 0, η 1 halved on
a non-improving step, stop at D ≤ 0.005) on its weighted pooled confusion matrix, whose true shares equal the target
(`bias.fit_stage_bias_table`, at most 8 decodes in total; with one anchor and unit weights it reproduces
`fit_stage_bias`, unit-tested). Between the anchors the table is interpolated, not fitted; R1 and R4 check the result
at the mid-anchors.

### 6.5 C0: the level alone (optional arm)

N1's classifier, statistics, θ_nap,g and context, its scalar nap stage log-bias replaced by a per-length table fitted
exactly as for C2, on the inner out-of-fold decodes of N1's inner fold nap models of g's chosen classifier and C. Those
models were not stored by `nap-model`; they are refitted deterministically from `nap-model/features` with 13.12's
procedure (`napmodel.select_group`'s fits and `stats.fit_label_stats` on the inner training subjects' proxies), and gate
G4 (before the code commit) checks the refit. If G4 fails, C0 is dropped (reported) and the run continues. The diagnosis
predicts that C0 fails F (slope about 0); it is a full candidate, and if it passes every gate the smallest change wins
ties.

### 6.6 Common evaluation parameters

The night run's base parameters with the night decoder θ_g of `bias-base`, β = 1; `napMode.maxSessionMin` 180,
`napMode.estimator` HYBRID, `napMode.outputPolicy` STAGES (the stages are scored; the policy applies after the
estimate), `napMode.post` defaults, `napMode.hybrid` = the model's θ (θ_nap,g for N1 and C0, θ_E,g for C1 and C2),
`napMode.contextMarginMin` = 0 for C1 and C2, absent for N1 and C0 (`napmodel.Inputs.eval_params` plus this key). Every
evaluation of held-out subjects includes their whole nights (gate G6).

### 6.7 Context estimators and references (not candidates)

RULES (AlgoParams defaults) and HMM-table (`loso.physiology_hmm_patch`) as the nap estimator on every EVAL pool,
untrained; "all LIGHT" (every scored chunk predicted LIGHT, from the labels) on every pool and on LIT; every model also
scored as SLEEP_WAKE output (4 × 4 matrices collapsed to wake / sleep).

### 6.8 Considered and not candidates

| option | why not |
|---|---|
| clock time / time since the main sleep as a covariate | no labelled heart-rate session has a daytime clock (Walch none, BIDSleep about 20:00–09:00 ET, ds006576 one clock time); the morning literature comes from restricted sleepers only; such a covariate could only impose an untested prior. Data path specified in Appendix A |
| return-to-sleep merging beyond 30 min | the data support only gaps < 30 min, the existing `sources.mergeGapMin` |
| per-nap EM of the prior (Saerens et al. 2002) | needs session-level evidence about the mix, which the diagnosis found almost absent |
| a constant DEEP / REM offset | moves both regimes together (diagnosis) |
| runtime nap Tier-2 flags, SLEEP_WAKE under `LOW_HR_RANGE` | would need their own evaluation; `LOW_HR_RANGE` does not track the DEEP over-call consistently. Rates are reported |
| jittered session starts in training | a new hyper-parameter without a pre-specified value; LATESTART is reported |

## 7. Metrics (operational definitions)

### 7.1 Session record and the common set E∩

From algo-cli: the 4 × 4 confusion matrix C (rows truth AWAKE, LIGHT, DEEP, REM) over the session's scored 30-s chunks
(prediction at the chunk midpoint, `IntervalScoring`; chunks predicted UNKNOWN are not scored), estimated status and
estimator used, warnings. True shares T_s = row sums / total, predicted P_s = column sums / total ("window shares");
predicted TST = (LIGHT + DEEP + REM columns) × 0.5 min; shares of sleep DEEP%sleep = P_DEEP / (1 − P_AWAKE), REM
likewise, true values from T. "Pooled" = summed chunk counts.

**E∩.** The context margin of C1 / C2 changes the input and so the coverage gates of `SleepStager.run`: a session can
fall to RULES (`RULES_FALLBACK_LOW_COVERAGE`) or be NOT_ESTIMATED under one model and not another. Every gate, the
selection, rule (ii) and every paired comparison therefore use **E∩** = the sessions (per pool, dataset and variant)
estimated by HYBRID with at least one scored chunk under every model of the run (N1, C0 unless dropped, C1, C2); the
excluded counts are reported per model and reason. Reported metrics are given on E∩ and on each model's own estimated
set. RULES and HMM-table are reported on their own sets.

### 7.2 Per-epoch AUC (gate D)

From `algo-cli evaluate --per-epoch` (columns `label`, `p_awake`, `p_light`, `p_deep`, `p_rem`): rows = 60-s epochs of
Walch held-out sessions in E∩ with `has_data` = 1 and a non-empty `label` (the majority 30-s label) under **both**
compared models (rows matched by night id and epoch; excluded counts reported). For stage s, score = the decoded
posterior p_s, outcome = (label = s). Cell = (subject, anchor L) within a pool group Q; the Mann–Whitney AUC (ties ½) of
the cell's epochs when both outcomes occur. AUC_s(Q) = mean over subjects of the mean over the subject's defined cells
in Q; Q ∈ {ANCHOR, EARLY, LATE}. Within one cell every per-length level is constant, so the AUC measures ranking.
Reported: the same for BIDSleep.

### 7.3 Regime contrast (gate F)

S_F = the Walch subjects with at least one MATCHED session of E∩ in EARLY and at least one in LATE (27 subjects on the
labels, section 2.5). Over S_F's MATCHED sessions of E∩: P̄_s(K, L) = the pooled predicted share **of sleep** of stage
s (predicted s chunks / predicted LIGHT + DEEP + REM chunks) over the sessions of regime K with length L; T̄ likewise
from the labels (true s chunks / true sleep chunks). Δ_DEEP = mean over L ∈ {60, 90} of [P̄_DEEP(EARLY, L) −
P̄_DEEP(LATE, L)]; Δ_REM = mean over L ∈ {60, 90} of [P̄_REM(LATE, L) − P̄_REM(EARLY, L)]; Δ^true from T̄; ratio r_s =
Δ_s / Δ_s^true. CI: 10 000 resamples with replacement (`numpy.random.default_rng(0)`) of the subjects of S_F, each drawn
subject contributing all its MATCHED sessions; pooled shares recomputed; 95 % percentile interval. Point estimates,
Δ^true, r and the CI use the same subjects. A model's level is the same for both regimes within one length, so a prior
cannot produce the contrast; shares of sleep keep a model from meeting it by predicting more AWAKE in LATE windows.
Reported: the window-share version and the version over all MATCHED sessions. BIDSleep: the same on its subjects with
both regimes.

### 7.4 Realism metrics (gate R, selection, rule (ii))

- **Featureless check (R1).** Per length L ∈ A ∪ A_mid, variant and model, over the 180 sessions of length L (90 if
  the run uses seeds 0–9, section 18) × 31 Walch fold bundles: "no sleep" = a decode that is NOT_ESTIMATED, estimated by
  RULES, or has predicted sleep < 50 % of its scored chunks; (a) over the other decodes, the pooled predicted DEEP share
  of sleep and REM share of sleep; (b) the share of "no sleep" decodes among all of them; (c) reported: the share
  violating GL (and G), per design and σ.
- **LATE DEEP tail (R2).** For L ∈ {60, 90}: over the LATE sessions of length L in E∩, the share with P_DEEP ≥ 0.5 minus
  the share with T_DEEP ≥ 0.5 (window shares); tail = the larger of the two lengths.
- **LIT metrics (R3, R4, R5, LitDist, rule (ii)).** On the held-out Walch LIT pool: per anchor, weighted pooled
  predicted DEEP%sleep and REM%sleep; **LitDist** = mean over the six anchors of (|DEEP%sleep − D*(L)| + |REM%sleep −
  R*(L)|), pp; **LitDist_mid** the same over the five mid-anchors on LIT_mid. **Guard excess** = mean over anchors of
  the weighted share of sessions whose predicted composition violates GL (rows by predicted TST) minus the same for the
  true composition. **LIT accuracy** = trace of the LIT confusion matrix; **LIT all-LIGHT accuracy** = its LIGHT row
  sum; **LIT κ** = mean over Walch subjects of Cohen's κ of the subject's weighted matrix Σ_i w_i C_i over its ANCHOR
  sessions (subjects where defined).
- **Default-population scope (reported, pre-specified).** With the app's default `sources.minSessionMin` = 90 only
  sessions of 90–180 min are estimated as naps; LitDist, R1 and the guard excess restricted to the anchors ≥ 90 min
  (90, 120, 150, 170) are reported for every model.

### 7.5 Reported metrics (no decision)

- Per pool, regime, length, dataset and variant, as 13.12: accuracy (pooled), E = mean per-session 100 · Σ_s |P_s − T_s|
  (pp), mean per-subject and chunk-pooled 4-class κ, sleep / wake κ, wake F1, true and predicted shares, the share of
  sessions with P_DEEP ≥ 0.5, with P_REM = 0 and with P_DEEP = 0 (and the true ones), flags, NOT_ESTIMATED reasons;
  "all LIGHT" accuracy of the same chunks.
- The 13.12-style rule-(ii) margin: accuracy − "all LIGHT" accuracy over O ∪ W (nap proxies v2), Walch, worse variant.
- Prior-matched metrics (label-shift reading): per anchor cell (all pools; a session belongs to the nearest anchor,
  boundaries 45, 75, 105, 135 and 160 min), C_k = the pooled confusion row-normalised (a row with < 20 chunks replaced
  by the row pooled over all cells), π'_k = (A_k, (1 − A_k)(1 − D* − R*), (1 − A_k) D*, (1 − A_k) R*) with A_k the
  pooled true AWAKE share of the held-out O sessions at L_k; acc' = Σ_s π'_s C_k[s, s], q = π' C_k,
  E' = 100 Σ_j |q_j − π'_j|, κ' = (acc' − Σ π' q) / (1 − Σ π' q); means over anchors.
- Evidence-tracking: session-level OLS slope and Spearman ρ of predicted on true DEEP and REM share over O ∪ M ∪ R (the
  diagnosis statistic); within-length ρ and slope (per anchor, mean over anchors); pooled predicted vs true shares per
  10-min bin of the O-60 sessions; BIDSleep non-onset windows by US Eastern clock of the window start (before 03:00,
  03:00–05:00, after 05:00; an inference about where participants slept).
- Robustness: LATESTART accuracy and AWAKE share against the unshifted O sessions; the perturbations of 4.5 as paired
  per-session changes of predicted DEEP and REM share (subject bootstrap CIs); FEATURELESS per design, σ and length.
- Literature sensitivity: LitDist under the no-30-min-row table and under the no-margin mapping (section 5.1).
- Fits: every fixed point (bias, table) with its trace and D; the inner choices and margins; the effective sample sizes,
  Newton steps and λ of every LIT weighting.

### 7.6 Worse variant, paired comparisons

Each criterion uses its own worse variant: the minimum over {instantaneous, mean2min} for "≥" criteria, CI lower bounds
and ratios; the maximum for "≤" criteria, excesses and LitDist; a difference criterion takes the worse of the
per-variant differences. Paired per-subject bootstrap (`report.paired_bootstrap`, 10 000 resamples of subjects, seed 0,
95 % percentile CI) per dataset, variant and pool of X − N1 (X ∈ {C0, C1, C2}), C2 − C1, RULES − N1, HMM-table − N1, and
every model − "all LIGHT", on E∩: accuracy, E, 4-class κ, sleep / wake κ, wake F1; LIT metrics with the weights fixed at
their full-sample values.

## 8. Rule (i): the model (verbatim)

"On Walch, held-out subjects, worse sampling variant: a candidate (C0, C1, C2) qualifies if it passes D, F and R
against the reference N1.
D: its per-epoch AUC of AWAKE, DEEP and REM over the anchor sessions is at least N1's minus 0.02 each, its DEEP AUC over
the early pool and its REM AUC over the late pool at least N1's minus 0.05.
F: in sessions of 60 and 90 min, its predicted REM share of sleep is higher in the late than in the early pool and its
DEEP share of sleep lower; each difference is at least a quarter of the true difference and has a 95 %
subject-bootstrap interval above 0; and both differences are positive on BIDSleep.
R: on featureless sessions of every anchor and mid-anchor length, its pooled DEEP and REM shares of sleep lie within the
Tier-1 band of the length (upper bounds only below 45 min of sleep) and at most half of them are decoded without sleep;
on late sessions of 60 and of 90 min, its share of sessions decoded with at least 50 % DEEP exceeds the true share by at
most 0.10; on the literature-reweighted pool, its share of sessions beyond the Tier-2 guard exceeds the true share by
at most 0.05, its literature distance at the mid-anchor lengths exceeds that at the anchors by at most 2.0 pp, and its
literature distance is not larger than N1's.
Adopt the qualifying candidate with the smallest literature distance; ties within 1.0 pp go to C0, then C1, then C2.
If none qualifies, adopt in the same way among the candidates that pass D and R and whose regime contrasts are not
weaker than N1's where N1's are wrong and not in the wrong direction where N1's are right (a realistic level whose
evidence-following is not shown; its output policy cannot be STAGES). If none of these either, keep N1."

Operational definitions (they govern; all on E∩ of section 7.1 except R1, which is per model on the featureless
decodes):

- **D.** ΔAUC_s,v = AUC_s(X, v) − AUC_s(N1, v) (section 7.2); minimum over v. On ANCHOR: ΔAUC ≥ −0.02 for s = AWAKE,
  DEEP, REM; ΔAUC_DEEP(EARLY) ≥ −0.05; ΔAUC_REM(LATE) ≥ −0.05.
- **F.** r_REM ≥ 0.25 and r_DEEP ≥ 0.25 (shares of sleep, S_F, minimum over variants); the CI lower bounds of Δ_REM and
  Δ_DEEP > 0 (minimum over variants); on BIDSleep Δ_REM > 0 and Δ_DEEP > 0 in both variants (point estimates, shares
  of sleep); if Δ^true ≤ 0 the criterion fails.
- **R1.** At every L ∈ A ∪ A_mid and in both variants, with t = TST_est(L) and the band of section 5.2 at t: the pooled
  DEEP%sleep ≤ the band's DEEP upper bound and the pooled REM%sleep ≤ the band's REM upper bound; if t ≥ 45 min also
  ≥ the band's DEEP and REM lower bounds (this applies at L ≥ 75); the "no sleep" share ≤ 0.5; if every decode of a
  length is "no sleep", R1 fails.
- **R2.** LATE DEEP tail (the larger of L = 60 and L = 90) ≤ 0.10 (maximum over variants).
- **R3.** LIT guard excess ≤ 0.05 (maximum over variants).
- **R4.** LitDist_mid − LitDist ≤ 2.0 pp (the worse of the per-variant differences).
- **R5.** LitDist (maximum over variants) ≤ LitDist of N1 (maximum over variants).
- **Selection.** LitDist (maximum over variants); ties = LitDist within 1.0 pp of the lowest qualifying value, then the
  order C0, C1, C2 (fewest changes first). "Keep N1" = no nap-section change, `napMode.contextMarginMin` stays null.
- **Two tiers.** Tier 1 = the candidates passing D, F and R. Only if tier 1 is empty, tier 2 = the candidates passing
  D, R and **F0**: for s ∈ {DEEP, REM} and in each variant v, r_s(X, v) ≥ min(r_s(N1, v), 0) (point estimates, shares
  of sleep, S_F, section 7.3; F0 fails if Δ^true ≤ 0). The selection above is applied within the first non-empty tier.
  A tier-2 candidate fails F by definition, so rule (ii) gives it SLEEP_WAKE or SKIP, never STAGES; rule (iii)(b) is
  reported for it but does not gate its installation (section 13.8). `summary.json` records the tier.
- **Why a second tier** (fixed now, before any candidate value is known): N1 itself does not pass F (section 10), so a
  candidate that only corrects the level — passing D (no loss of per-epoch discrimination) and R (featureless sessions,
  late tail, guard excess, interpolation and LitDist no worse than N1) — is no worse than N1 on any gated property and
  better on realism; keeping N1 would keep a level that R shows to be implausible. F0 keeps such a candidate from
  reversing a contrast N1 gets right or deepening one N1 gets wrong. Its stages are shown in the app; Health Connect
  gets only what rule (ii) allows without F.
- Everything else (κ, accuracy, E, BIDSleep beyond F, prior-matched metrics, slopes, RULES, HMM-table, robustness, the
  default-population scope, the literature sensitivities) is reported, not decided on.

**Why these thresholds** (fixed now, with N1's diagnosis values known and no candidate value known):

| threshold | reason |
|---|---|
| D −0.02 | a loss of per-epoch ranking small next to the 0.14–0.23 drop of N1's classifier DEEP AUC between onset (0.79–0.80) and late windows (0.57–0.65) |
| D −0.05 (early DEEP, late REM) | the onset template is genuinely informative on night onsets, and afternoon naps also have N3 latencies of about 15–20 min and REM latencies of about 40 min or more; a candidate may give up part, not most, of that discrimination |
| F 0.25 | the smallest response that makes the direction of the evidence visible: N1 reaches about 0.5 for DEEP and a negative value for REM; the night model inside its whole night 0.4–0.7. For Walch (shares of sleep, 27 subjects) it means Δ_DEEP ≥ 7.8 pp and Δ_REM ≥ 6.4 pp (true 31.3 / 25.4 pp, section 2.5) |
| R1 band, both sides at ≥ 45 min of sleep | section 5.2: a featureless session should decode to an average nap of its length, not to a DEEP block and not to a REM-rich night segment; the band is the range of the study means the target at that length comes from, so the target itself always passes. The REM side uses the band, not the per-nap guard: a pooled share is an average |
| R1 "no sleep" 0.5 | a featureless session carries no evidence against sleep; a model that calls most of them wake is not an average nap either |
| R2 0.10 | N1 exceeds the true rate by 22–25 pp on 60-min late windows (diagnosis); the threshold, applied at 60 and at 90 min separately, demands removing more than half of that excess |
| R3 0.05 | GL, which uses no individual data, flags 2.9 % of the 70 genuine naps outside the Zenodo set and 4.3 % of all 93 (section 5.3); a candidate may add about one genuine-nap false-flag rate of implausible outputs beyond those of the true compositions |
| R4 2.0 pp | the table is fitted at the anchors and interpolated between them; the decoded shares should follow the interpolated target to within twice the 1.0-pp resolution of the targets (the tie tolerance) |
| R5 | adoption must never move the level further from the published composition than the model it replaces; this matters for C1, whose level is night truth |
| LitDist tie 1.0 pp | below the resolution of the targets (rounded study medians) |

**Why accuracy and E against night truth do not gate.** C0 and C2 are calibrated to published nap compositions, which
differ from the truth of night-derived windows; on onset windows they must under-call DEEP (Walch onset windows of 60
min have 41.8 % DEEP of sleep, the target is 26.7 %). These metrics would reward the night-onset template. They are
reported for every pool and regime, with this disadvantage stated.

## 9. Rule (ii): the default output policy for naps (verbatim)

"STAGES if the nap estimator passes F and R of rule (i) and, on the literature-reweighted pool, its accuracy is at
least the 'all LIGHT' accuracy + 0.03 and its 4-class kappa at least 0.15; otherwise SLEEP_WAKE if its sleep/wake kappa
over all evaluation sessions is at least 0.20; otherwise SKIP. It is computed for N1 and for the adopted candidate, and
both results are written before ds006576 is decoded."

Operational definitions: computed for N1 always and for the candidate adopted by rule (i), if any; both, with every
criterion value, go to `out/naps-realistic/summary.json` in the same step as rule (i); F and R are evaluated for N1
exactly as for the candidates (R5 holds for N1 by definition); Walch, on E∩; margin = minimum over the variants of (LIT
accuracy − LIT all-LIGHT accuracy); 4-class κ = LIT κ, minimum over the variants; sleep / wake κ = mean per-subject
sleep / wake κ over all EVAL sessions of E∩ of each Walch subject (unweighted), minimum over the variants; the
thresholds 0.03 / 0.15 / 0.20 are 13.12's. The policy of the estimator in effect after rule (iii) (the adopted candidate
if installed, else N1) becomes `TrainedDefaults.NAP_OUTPUT_POLICY`, after any demotion by rule (iii). The 13.12-style
margin (section 7.5) is reported beside it. With SLEEP_WAKE the app still keeps the estimated stages in its ledger and
Night detail (ALGORITHM.md section 18); Health Connect gets asleep / awake only.

## 10. Expected outcome (stated in advance)

- N1 is expected to fail F (its REM contrast has the wrong sign) and R1 (a featureless one-hour session decodes to
  about 60 % DEEP); C0 is expected to fail F (session-level slope about 0).
- For C1 and C2 nothing is known. The ablation of section 2.2 (not a retrained model) reached about 0.29 (DEEP) and 0.16
  (REM) of the true pooled onset-to-late differences; if a retrained masked model behaves like it, F fails on REM, and D
  may fail on the early DEEP AUC, which N1 owes partly to its time features.
- The most likely outcome is therefore: the first tier of rule (i) is empty. If C0 or C2 passes D and R (and F0),
  it is adopted in the second tier, shown in the app, and rule (ii) gives it SLEEP_WAKE (no STAGES without F); if
  every candidate also fails D or R, N1 is kept and rule (ii) gives SLEEP_WAKE (N1's sleep / wake κ was 0.593 on the
  13.12 proxies). In both cases the reason is generated from the failing criteria. Either outcome is accepted in
  advance.
- Rule (ii)'s STAGES bar is high on purpose: on the reweighted pool "all LIGHT" already scores 0.631 (section 2.5), so
  STAGES needs a LIT accuracy of about 0.661 or more; at the pool's marginals (about 9 / 63 / 18 / 10 %) and with
  predicted marginals equal to the true ones that is a κ of roughly 0.39, above N1's 0.356 on the 13.12 proxies. An
  adopted candidate may therefore still get SLEEP_WAKE.

## 11. Gates

Any failure of a gate marked fatal stops the run; it is documented and nothing is installed. Gates G1, G2, G3a and G4
reproduce known results and fit no candidate; they run **after the pre-registration commit and before the code
commit** (`train.py naps-realistic-gates`, section 18) with the code that is then committed unchanged, and are repeated
identically by the full run.

| gate | check | when | on failure |
|---|---|---|---|
| **G0 provenance** | the algo-cli fingerprint (launcher + `lib/*.jar`) and `meta.code` (SHA-256 of `train.py` and every `pulsetrain/*.py`, git commit, modified files) recorded; `PREREG_SHA256` equals this file; the run refuses a build other than the one recorded at its start | every run | fatal |
| **G1 N1 reproduced** | N1's fold bundles and parameters reproduce every stored N1 decode of `nap-model/results.jsonl` — 3708 = 1854 per variant, onset proxies **and** ends-in-wake windows — with equal confusion, estimated status and warnings (same parameters, no warning adjustment); with each group's night θ_g, candidate B of `bias-base/bias/results.jsonl` on all 568 whole nights | before the code commit; repeated | fatal |
| **G2 build neutral** | `NapModeRegressionTest` passes with its recorded digests; the new build with the shipped bundle and default parameters decodes all 568 whole nights exactly as the build of the base commit `cad7a6f` (per-night confusion, warnings and estimated status; per-epoch CSV byte for byte), and identically with `napMode.contextMarginMin` = 0; the shipped bundle's `fingerprint()` and `nightModelFingerprint()` under the new build equal the values the base build computed before any code change (logged NOTE); reported: the base build reproduces `nap-model-verify/final/<variant>.installed.per-epoch.csv` | before the code commit; repeated against the stored base CSVs | fatal (the code is not committed) |
| **G3a features** | the Python cut of the onset proxies gives exactly (every row, every field) the features of `algo-cli features --nap-proxies 30,60,90,120,150,170`, with the base parameters and with `napMode.contextMarginMin` = 0; `FEATURE_SET_VERSION` 1 with the 40 names of `algo-cli feature-names` | before the code commit; repeated | fatal |
| **G3b sessions and exports** | the tile, EVAL and MIDANCHOR session files pass `check_sessions_file`; every session file has unique ids; every exported classifier reproduces scikit-learn to 1e-9; masked columns have std 1e12 and zero first-layer weights in every exported bundle; the copied statistics of every inner fold are equal among its Walch subjects | full run | fatal |
| **G4 C0 inner refit** | N1's refitted inner MLPs, decoded on 13.12's inner held-out proxies at the stored chosen point, reproduce the stored inner score of every (variant, group) of `nap-model/inner/<variant>/<group>/selection.json` exactly | before the code commit; repeated | C0 dropped, reported |
| **G5 context invariance** | for every C1 and C2 fold bundle, every Walch EVAL session decodes identically (confusion, estimated, warnings) as cut, under `noctx` and under `app+15steps` (section 4.5), both variants; the algo-cli features of the `noctx` copy equal those of the cut | full run | fatal |
| **G6 nights unchanged at fold level** | every candidate's evaluation includes the held-out subjects' whole nights, which reproduce candidate B night by night (568 / 568 per candidate) | full run | fatal |
| **G7 counts** | the recomputed counts of 4.3 and 4.6 | full run | reported |
| **G8 determinism** | single-threaded BLAS, fixed seeds; `--jobs` changes no result | every run | — |
| **G9 test-set freeze** | the order and uniqueness checks of section 13.6 | external step | the external step refuses; a deviation (section 19) |

## 12. Paired bootstrap, multiple comparisons

Three candidates are compared under one rule; the adopted candidate's LitDist, r and LIT metrics are its own selection
estimates and carry a small optimism, and they are labelled as such wherever they are quoted; no interval is adjusted
for multiplicity. One seed, as in 13.12.

## 13. External test: OpenNeuro ds006576 (test only)

### 13.1 Role, licence, weak labels

McDevitt EA, Kim G, Turk-Browne NB, Norman KA. J Cogn Neurosci 38(1):126–143 (2026), doi:10.1162/jocn.a.82; dataset
doi:10.18112/openneuro.ds006576.v1.0.5, licence CC0. **Test only**: never used for training, for any parameter,
hyper-parameter, prior, bias, band, guard or threshold, never for choosing between candidates. Stored apart from the
training night files under `tools/train/data/external/ds006576/` (never committed; a unit test asserts that no
`ds006576` subject can enter `NightIndex`). Nothing derived from it is committed except aggregate numbers in reports.

Weak labels: group membership per recording (`participants.json`: NREM = "a nap containing non-rapid eye movement sleep
only", REM = "a nap containing rapid eye movement sleep", WAKE = "remained awake and rested quietly") and Table 1 of the
paper (n = 22 per group; percentages of TST; mean (SD)):

| | NREM group | REM group |
|---|---|---|
| TIB, min | 65.20 (23.51) | 113.86 (10.72) |
| TST, min | 49.27 (9.41) | 89.91 (13.63) |
| stage 3 (DEEP), min | 20.18 (14.33) | 21.95 (9.86) |
| REM, min | 0.11 (0.34) | 16.70 (9.49) |
| WASO, min | 8.75 (16.06) | 14.05 (13.42) |
| % stage 1 / 2 / 3 / REM of TST | 13.74 / 46.09 / 39.92 (25.37) / 0.25 (0.75) | 10.61 / 46.59 / 24.90 (10.94) / 17.90 (9.29) |

Protocol: nap opportunity from about 13:30; the 50-min condition woken after 50 min of TST or at the first sign of REM,
the 90-min condition after 90 min of TST but no later than 120 min after the start; at least 8 h in bed the night
before, not tested if less than 6.5 h of sleep was reported; 18–35 years (mean 20.4). The deposit has 21 complete nap
recordings per group, so group membership in Table 1 and in the deposit may differ by 1–3 subjects. The WAKE group
"remained awake", but 4 of its 23 sessions are failed nap attempts that may contain short N1 / N2 bouts (pilot,
section 2.4; identities unknown); YASA's sleep minutes are reported for every WAKE recording (section 13.10).

### 13.2 Fetch and reduction (no model involved)

`https://s3.amazonaws.com/openneuro.org/ds006576/…` without signing: `participants.tsv`, `participants.json`,
`dataset_description.json`, `CHANGES` and, for every subject with an `.eeg` file, its `_eeg.vhdr`, `_eeg.json`,
`_channels.tsv` and `_eeg.eeg` (key list from the bucket listing, recorded). **Snapshot pin**: the fetch stops before
any `.eeg` byte is read unless `dataset_description.json` has `DatasetDOI` = `doi:10.18112/openneuro.ds006576.v1.0.5`,
the first entry of `CHANGES` is the 1.0.5 entry of 2026-04-16, and the listing has exactly the 65 `_eeg.eeg` objects of
the committed key list `tools/train/external-ds006576-v1.0.5.keys.tsv` (subject, group, size in bytes,
`RecordingDuration`; public metadata from the pilot's header audit; 21 NREM, 21 REM and 23 WAKE files, sizes equal). A
failed pin makes the external test void and is reported before any decode. Each `.eeg` is streamed in HTTP range
requests of 44 851 200 bytes (153 600 frames of 292 bytes = 300 s; the last one shorter); every response must be HTTP
206 with the exact `Content-Range` and length; the file's SHA-256 is computed on the fly; a size that is not a multiple
of 292 bytes or differs from `RecordingDuration × 512 × 292` marks the file truncated (its complete frames are used; it
is excluded from every rule and reported). Raw bytes are not stored. Channels are found by their labels in each file's
`.vhdr` (`A13_C3`, `B18_C4`, `A1`, `A2`, `LOC`, `EMG1`, `EMG2`, `ECG1`, `ECG2`; a missing label excludes the recording),
scaled by the `.vhdr` resolution. Stored per recording (`derived/sub-<id>.npz`, float32, no pickle): ECG = ECG1 − ECG2
at 512 Hz; C3 = A13_C3 − A2, C4 = B18_C4 − A1, EOG = LOC − A2, EMG = EMG1 − EMG2 decimated to 128 Hz
(`scipy.signal.decimate(x, 4, ftype="fir", zero_phase=True)` over the whole recording); time 0 = the first frame.

### 13.3 ECG → heart rate (frozen; never touched by any stage label)

The detector constants below were set on the pilot's five subjects (section 2.4) and are frozen; the committed
`pulsetrain/ds006576.py` (`rpeaks`, `good_chunk`) is the authoritative implementation of steps 1–2.

1. Non-overlapping 15-s chunks of ECG from time 0; a trailing partial chunk shorter than 15 s is dropped. Per chunk:
   3rd-order Butterworth 5–25 Hz band-pass (second-order sections, zero phase) of the mean-removed chunk; polarity =
   sign of the larger of the 99.7th percentile and minus the 0.3rd percentile; candidate peaks of the
   polarity-corrected signal at least 0.3 s apart (`scipy.signal.find_peaks`); kept if their height is ≥ 45 % of the
   90th percentile of the candidate heights that are ≥ 30 % of the largest. A chunk is **good** with ≥ 8 beats, ≥ 80 %
   of its RR intervals in [0.4, 1.6] s, ≥ 60 % of its RR time in such intervals and a mean heart rate over them
   (60 · n / Σ RR) in [30, 180] bpm. RR variability is never a quality criterion.
2. RR intervals between consecutive peaks of the same good chunk, kept in [0.4, 1.6] s, stamped at their midpoint.
3. Raw samples every 5 s (the ≈ 0.2-Hz sampling of the training watches): at t_j = 2.5 s + 5 s · j, 60 / the median
   of the RR intervals with midpoint in [t_j − 2.5 s, t_j + 2.5 s), rounded to whole bpm, when there are ≥ 2 such
   intervals; otherwise no sample.
4. ECG-valid range = [start of the first good chunk, end of the last good chunk].
5. The two variants exactly as `prepare.py`: `ohealth.clean_hr`, then `ohealth.resample_instantaneous` and
   `ohealth.resample_mean2min` with phase `ohealth.grid_phase_ms("ds006576/sub-<id>")`, over [s0 − 30 min, s1 + 30 min]
   ∩ the ECG-valid range of each window below. No steps.

### 13.4 Sleep staging for the windows: YASA, never the heart rate

YASA (Vallat R, Walker MP, eLife 10:e70092 (2021), doi:10.7554/eLife.70092; BSD-3-Clause), **`yasa==0.7.0`** (released
2026-03-08; 0.8.0 is less than two weeks old), installed with every dependency pinned by
`tools/train/requirements-external.lock` (committed in the code commit; every pinned release at least two weeks old at
that commit) in its own environment `tools/train/.venv-external`, never in the training environment. YASA 0.7.0 ships its
LightGBM classifiers inside the wheel and downloads nothing. **The classifier is fixed**: the stager passes
`path_to_model` = `<site-packages>/yasa/classifiers/clf_eeg+eog+emg_lgb_0.5.0.joblib` explicitly (the file YASA's own
"auto" rule selects for EEG + EOG + EMG without metadata: the last of the sorted matches of `clf_eeg+eog+emg*.joblib`).
The lock records, as comment lines, the SHA-256 of the yasa wheel (`114d9cab…b0c9`, section 2.5) and of that classifier
file (`3d27d440…4beb`), as obtained by the self-test of the installed environment on synthetic input, which also asserts
the resolved file name, fails on any warning raised while the classifier unpickles (for example a scikit-learn version
warning), and runs `predict()` and `predict_proba()` twice on synthetic EEG requiring identical output hashes. At
staging, a different yasa version, wheel digest or classifier digest makes the external test **void**. Applied to the
whole recording (time 0 to the end of the last complete 30-s epoch) on an MNE `RawArray` of the 128-Hz derivations in
volts: `yasa.SleepStaging` with the EEG = the central derivation, EOG = `LOC-A2`, EMG = `EMG1-EMG2`, no metadata;
`predict()` and `predict_proba()`. The central derivation is C3−A2 or C4−A1, whichever has the lower median over 30-s
epochs of its 0.5–30 Hz band power (Welch, 4-s windows); ties → C3−A2 (label-free; the pilot found one derivation
10²–10⁴ times too strong). Output: one stage per 30-s epoch from time 0 (W / N1 / N2 / N3 / R) with probabilities,
stored with the YASA version and the digests. If YASA cannot be installed or run, the external test is **void**; no
other window rule replaces it. Disclosed limits: YASA was trained on NSRR nights (used here for validation only), its
performance on naps is unknown (Vallat & Walker, Discussion), and its features include the time since the recording
start; only its sleep / wake transitions define the windows; its stages are silver labels, reported only.

### 13.5 Windows

- **oh** (OHealth-like, naps only, primary): the training definition (first sleep epoch − 5 min to last sleep epoch +
  5 min) **with a run-of-3 rule**: onset = the first 30-s epoch that starts a run of ≥ 3 consecutive non-W epochs; final
  = the last epoch that ends such a run; window = [start(onset) − 5 min, end(final) + 5 min] clipped to the ECG-valid
  range. The run of 3 keeps a stray epoch from opening or closing the window and is a deliberate difference from the
  training cut. A nap without such a run has no oh window (counted).
- **oh1** (reported sensitivity, never decisive): the same with single epochs (the first and last non-W epoch).
- **m60** (length-matched, naps only): [start(onset) − 5 min, start(onset) + 55 min], kept only when it lies inside the
  ECG-valid range.
- **rec** (TIB-like, naps and WAKE recordings): the whole ECG-valid range.
- Every window is shorter than 180 min (the longest recording is 154 min), i.e. a nap.

### 13.6 Night files, freezing, digests before any decode

`tools/train/data/external/ds006576/nights/<variant>/ds006576-sub-<id>-<oh|oh1|m60|rec>.night.json` in the night-file
format (docs/INTERCHANGE.md section 1): id `ds006576-sub-<id>-<window>-<inst|mean2min>`, subject `ds006576-sub-<id>`,
times in ms since the first frame (no clock is known or read), `hr` of section 13.3, `steps` = [], `labels` = the YASA
epochs fully inside the window (W → AWAKE, N1 / N2 → LIGHT, N3 → DEEP, R → REM; silver; they make `algo-cli evaluate`
return each session's predicted stage counts as the column sums of its confusion matrix). A manifest holds every file's
SHA-256, one digest over them, the group of every subject, the truncation flags, the YASA version, wheel and classifier
digests and the code fingerprint. YASA's own group summaries (TST, N3 and REM minutes and % of TST per group, from the
oh windows) are written to `yasa-summary.json`.

**Freeze.** The extraction runs after the code commit and before the experiment, without any model. Exactly one
`NOTE ds006576 manifest sha256 <digest>` line and exactly one `NOTE ds006576 yasa-summary sha256 <digest>` line are
written to `tools/train/out/commands.log`, both before the first START of `train.py naps-realistic`; only digests are
logged and printed, never a silver-label value, and nobody opens `yasa-summary.json` or a stage or night file of
ds006576 before the external step. The external step (G9) refuses unless (1) each of the two NOTE lines occurs exactly
once, (2) the log order is: the pre-registration NOTE < the START lines of `external.py fetch`, `external_stage.py` and
`external.py nights` < the two NOTE lines < the first START of `train.py naps-realistic` (that subcommand itself, not
`naps-realistic-gates`) < the RESULT line, holding rules (i) and (ii), of the run given to the external step < the START
of `train.py naps-realistic-external`, (3) the manifest and summary of `--data` have exactly the logged digests, and (4)
the YASA version, wheel and classifier digests equal the lock. Only after these checks and after `summary.json` holds
rules (i) and (ii) does the external step render YASA's group summaries. `test_prereg.py` asserts the same order on the
committed log. A re-extraction that reproduces the logged digests exactly is not a deviation (the extraction is
deterministic); one that does not is a deviation: the test is run on the files of the original manifest if they still
exist, otherwise rule (iii) is void; the decision remains the one from the original manifest, and any deviating result
is reported next to it.

**Expectation stated in advance.** An adopted C2 decodes an NREM-group oh window (about 68 min: TST 49 + WASO 9 + 10;
TST_est 50.9 min) at about the literature level, DEEP about 27.5 % (12 pp below Table 1's 39.9 %, inside the band) and
REM about 9.3 % unless the heart rate lowers it; for the REM group (windows of about 114 min; TST_est 91.2 min) DEEP
about 22.7 % (Table 1 24.9 %) and REM about 15.0 % (Table 1 17.9 %). N1 is expected to over-call NREM-group DEEP
(template about 50–60 %) and to give REM-group naps little REM.

### 13.7 Decodes (only after rules (i) and (ii) for N1 and the adopted candidate are in `summary.json`)

`algo-cli evaluate --model <bundle> --params <json> --per-epoch`, both variants: **S** = the shipped bundle at the
shipped defaults (`napMode.contextMarginMin` null: the files carry up to 30 min of heart rate on both sides of a window
where the recording has it — the app's input for N1, including a real post-nap context); **X** = the adopted
candidate's final bundle (section 14) at its final parameters (`napMode.hybrid` = θ_X, `napMode.contextMarginMin` 0 for
C1 / C2); `napMode.outputPolicy` STAGES in both. If no candidate is adopted, only S is decoded. The external step
refuses unless `summary.json` holds rule (ii) for N1 and, when a candidate is adopted, for it. A **usable** window is
estimated by the HYBRID nap model (not RULES, not skipped) and has at least one scored chunk.

### 13.8 Quantities and rule (iii)

Per usable window of a model and variant: predicted AWAKE, LIGHT, DEEP and REM minutes (scored chunks × 0.5 min);
TST_p = LIGHT + DEEP + REM minutes; shares of TST_p d = DEEP / TST_p and m = REM / TST_p; window shares = minutes /
scored minutes. Group means of d and m are means over the group's usable windows with TST_p > 15 min (the others fail
(c)).

Rule (iii), verbatim: "ds006576 is decoded only after rules (i) and (ii) are written for N1 and for the adopted
candidate. An adopted candidate is installed only if, on the real naps of ds006576, in both sampling variants and with
a test that is not void, (a) the NREM group's mean DEEP share of predicted sleep, and the REM group's mean DEEP and REM
shares of predicted sleep, lie within the Tier-1 bands at the groups' published total sleep times, (b) for a candidate
adopted in the first tier of rule (i), the NREM group's mean REM share of predicted sleep is at most 6.9 %, (c) at most 9.8 % of the naps are decoded with 15 minutes of sleep
or less or are flagged by the literature-only guard, and (d) NREM-group recordings get more predicted DEEP than
quiet-wake recordings (95 % bootstrap interval above 0); otherwise the shipped nap model stays with its rule-(ii)
policy. If the test is not void and the nap estimator in effect has STAGES but fails (a), (b) or (c), its policy is set
by rule (ii) as if it had failed the STAGES condition. ds006576 never chooses between candidates, never changes a fitted
value and never raises an output policy."

Operational definitions (they govern):

- **(a)** Bands of section 5.2 at Table 1's TST (model-independent): NREM group 49.27 min → mean 100 · d ∈ [13.00,
  46.43]; REM group 89.91 min → mean 100 · d ∈ [13.00, 49.01] and mean 100 · m ∈ [5.10, 26.58]. The NREM group's REM is
  tested by (b), not by the band. For C2, (a) is close to satisfied by construction (the anchored level at about 68 and
  114 min lies inside both bands whatever the heart rate); (b), (d) and the m60 endpoint carry the evidence.
- **(b)** NREM group mean 100 · m ≤ 6.9. The bound is borrowed: the highest study-mean REM share among the rule-I arms
  of naps with less than 45 min of sleep (Mednick 2002 short nap, 6.87 %; Leong 2023 10- and 30-min 1.50 / 0.83 %), i.e.
  the Tier-1 REM ceiling of naps in which REM is typically absent; no study reports the REM share that a stager should
  give naps without REM. At the literature level alone a 68-min window would decode to about 9.3 % REM, so (b) needs
  the heart rate to lower REM in REM-free naps. (b) is an evidence criterion: it gates the installation of a
  first-tier candidate (rule (i), which passed F) and is reported for a second-tier candidate, whose evidence-following
  is not claimed and whose output policy cannot be STAGES; it also enters the demotion of a STAGES policy below.
- **(c)** Share of usable oh windows of both groups that have TST_p ≤ 15 min or whose predicted composition violates GL
  (section 5.3; rows by TST_p) ≤ 0.098: the upper end of the Wilson 95 % CI of GL's false-flag rate on the 70 genuine
  naps outside the Zenodo set (2 / 70), so that neither McDevitt 2026 nor the Zenodo naps touch it. A decode with at
  most 15 min of sleep counts as a failure because every ds006576 window was opened and closed by a sleep run on the
  EEG and the protocol targeted 50 or 90 min of sleep; this can only make (c) stricter. Reported: the same share with
  G, and against 0.105 (GL on all 93 genuine naps).
- **(d)** rec windows: mean predicted DEEP window share of the NREM-group recordings minus that of the complete WAKE
  recordings > 0, with the 95 % percentile CI (10 000 resamples of recordings within each group, seed 0) above 0.
  Reported: the same against the WAKE recordings with < 5 min of YASA sleep.
- **Void** (per model): fewer than 19 usable recordings in the NREM group, the REM group (oh windows) or the complete
  WAKE recordings (rec windows), in either variant — 19 = ⌈0.9 × 21⌉, at least 90 % of the 21 complete recordings of
  each group —; or YASA unavailable, a version / wheel / classifier / manifest / summary digest mismatch, a failed
  snapshot pin, or a failed freeze check (section 13.6). A void test installs nothing new and demotes nothing.
- The demotion applies to whichever model is in effect after the installation decision (the adopted candidate, which
  passed (a)–(c), or N1, whose S decodes are always made): STAGES becomes SLEEP_WAKE if its sleep / wake κ of rule (ii)
  is ≥ 0.20, otherwise SKIP.

### 13.9 Length-matched REM endpoint (it conditions a claim, section 17; no installation depends on it)

On the m60 windows: mean predicted REM minutes of the REM group minus the NREM group, with the 95 % CI (10 000 resamples
within groups, seed 0) above 0 in both variants. Both groups get the same window length, hence the same per-length
level; the NREM group was woken at the first sign of REM. Not evaluable (and the claim is not made) if either group has
fewer than 15 usable m60 windows (⌈0.7 × 21⌉; NREM recordings may end before onset + 55 min). Reported with it: YASA's
REM minutes in the same windows per group; an OLS of predicted REM minutes on group and oh-window length (the groups
differ in length, so length alone separates them partly); predicted REM minutes in the first 50 min after the onset
within the oh decode.

### 13.10 Reported, not decided on

Every group mean with 95 % CI against Table 1 (TST, DEEP minutes and %, REM minutes and %, predicted WASO = AWAKE
between the first and last predicted sleep chunk) and their published half-widths 1.96 · SD / √22 (NREM: TST 3.93,
DEEP 5.99 min / 10.60 pp; REM: TST 5.70, DEEP 4.12 min / 4.57 pp, REM 3.97 min); the summed deviation dev_T1 =
|mean d_NREM − 39.92| + |mean d_REM − 24.90| + |mean m_REM − 17.90| (biased toward the onset template, because the NREM
group was cut at the first REM); X − S differences on the same windows (paired bootstrap over windows); the share of
windows with predicted DEEP ≥ 50 % of the window (genuine reference 6.9 %), with REM = 0 and with DEEP = 0; the
distribution of TST_p per group; REM presence (≥ 5 min) vs group: sensitivity, specificity, balanced accuracy, AUC of
predicted REM minutes; the same on rec and oh1 windows; WAKE recordings (YASA sleep minutes; predicted sleep, DEEP, REM
minutes; the two truncated files separately); silver agreement with YASA per epoch (accuracy, κ; not ground truth);
YASA's own group means against Table 1; `LOW_HR_RANGE` and other warnings; the heart-rate statistics of every window
(P50 − P10, 2-min step SD, range, median) against the onset proxies of the nearest length.

### 13.11 Limits

21 naps per group, young rested adults, one clock time (about 13:30), naps cut by protocol, weak labels only (the expert
hypnograms are not deposited), windows from an automatic stager, clean ECG instead of watch PPG, a lab setting. Some
WAKE sessions are failed nap attempts (section 13.1). The R-peak detector was tuned label-free on five test subjects
(section 2.4). The test set's paper influenced how the corridor cross-check classed the Zenodo naps and its guard G; G
is reported only, and the decisive guard GL and its threshold avoid both paths (section 2.3). It tests afternoon-nap
realism, not morning naps, evening naps or returns to sleep.

## 14. Final model and installation

- **C1 / C2.** Classifier type = the most frequent choice over the 156 outer folds (ties → higher mean inner score, then
  logistic regression); C = the most frequent C of the folds of that type; θ_X = the most frequent θ_E,g of those folds
  (ties → higher mean inner score, then grid order) — `napmodel.final_choice`. Fitted on the tiles of all 78 subjects
  (rows of both variants, instantaneous first, weights over all subjects); statistics copied from the shipped main
  model; weighted class priors. **C1 bias**: the fixed point on the LOSO out-of-fold decodes of the tiles of the 31
  Walch subjects, both variants pooled, each decoded by its outer fold nap model of the final type and C (refitted on
  the same 77 subjects where its group chose another), at θ_X with `napMode.contextMarginMin` 0. **C2 table**: the
  per-anchor fixed point on the same decodes of the ANCHOR sessions, LIT weights computed per variant on all 31 Walch
  subjects, the weighted matrices of the two variants summed per anchor.
- **C0.** The shipped nap section's classifier and statistics, its scalar stage log-bias removed; the table fitted on
  the LOSO out-of-fold decodes of the 31 Walch subjects' ANCHOR sessions by their stored N1 outer models (no bias) at
  EXPLICIT / 0.5 / 0 (13.12's final nap decoder), context null, LIT weights as for C2.
- Every fixed point keeps the unchanged budget of 8 decodes; the final D per anchor is reported next to the range of the
  fold fits; a final fit worse than every fold fit is reported, not re-run (13.11 precedent).
- Assembled with `bundle.nap_section` into the shipped bundle (main model, `stageLogBias` and `stageLogBiasShort`
  unchanged), `modelId` `pulsestages-walch+bidsleep-mlp-naps-realistic-final`, `training` notes naming the masked
  features, the tiles, the weights, the copied statistics, the context policy and the target table. Re-fitting with
  the same seed gives byte-identical layers (checked).
- **Installation** (`naps-realistic-install`, after rule (iii)): if X passes, its bundle goes to
  `app/src/main/assets/models/hybrid_model.json` and `trained_defaults.json` sets `napMode.hybrid` = θ_X,
  `napMode.contextMarginMin` = 0 (C1 / C2; C0 none) and `napMode.outputPolicy` = X's rule (ii) policy; `train.py
  defaults --write` renders `NAP_HYBRID_*`, `NAP_CONTEXT_MARGIN_MIN` and `NAP_OUTPUT_POLICY`. Otherwise the bundle is
  unchanged, `NAP_CONTEXT_MARGIN_MIN` stays null and only `NAP_OUTPUT_POLICY` is set, by N1's rule (ii) policy (already
  in `summary.json`) and any demotion of rule (iii). When no candidate is adopted, `naps-realistic-final` writes the
  shipped bundle unchanged and the base defaults of `nap-model-final`, so that the installation step has one input in
  every outcome.

## 15. Verification: nights unchanged (with the final build; `naps-realistic-verify`, fails on any difference)

1. The fold bundles of the model in effect (N0 bundles + its nap sections; or N1's) decode all 568 whole nights with
   each group's night θ_g and otherwise the build's **default** parameters exactly as candidate B of
   `bias-base/bias/results.jsonl`.
2. The adopted candidate's EVAL decodes are reproduced session by session (θ_X,g, `napMode.contextMarginMin` and STAGES
   passed explicitly).
3. The installed bundle and the previous shipped bundle (`583df315…30f9`) decode all 568 whole nights with the default
   parameters identically: confusion, warnings, estimated status and the per-epoch CSV byte for byte.
4. The 568 nights decode byte-identically (per-epoch CSV) with `napMode.contextMarginMin` ∈ {null, 0, 30}: the
   parameter is nap-only.
5. Under the final build, the previous bundle's `fingerprint()` and `nightModelFingerprint()` equal the values the
   **base build** computed before any code change (logged NOTE; an absent `nap.stageLogBiasByLength` is left out of the
   canonical JSON), and the installed bundle's `nightModelFingerprint()` equals the previous bundle's.
6. **Personalized nights.** A core-algo test decodes synthetic nights with a `PersonalModel` whose non-zero `biasDeltas`
   are keyed (`baseModelFingerprint`) to the previous bundle's `nightModelFingerprint()` (the logged base-build value),
   with personalization enabled, under the previous bundle and under the installed bundle, and requires identical
   estimate digests and that the deltas are applied under both (the digest differs from the unpersonalized decode).
   algo-cli never decodes with a personal model, so items 1–4 alone would not see a change of the key.
7. The installed bundle is accepted on every EVAL session (HYBRID used; a resubstitution, not a validation result).
8. If C1 or C2 is installed: with the default parameters every EVAL nap decodes identically with and without its
   context records and with `app+15steps` (the app path ignores nap context).
9. `NapModeRegressionTest` (previous bundle and previous nap defaults pinned, `napMode.contextMarginMin` null) keeps its
   recorded digests; the core-algo, algo-cli, data-local, data-hc and app unit tests, ktlint and detekt pass.

## 16. Implementation (the code commit, before the first algo-cli call of the experiment)

- **core-algo.** `NapModeParams.contextMarginMin: Int? = TrainedDefaults.NAP_CONTEXT_MARGIN_MIN` (null = the night's
  `preprocess.contextMarginMin`); `AlgoParams.forMode(NAP)` also sets the effective `preprocess.contextMarginMin`;
  `NightPreparer.prepare` decides the mode first and, for a nap with the override set, restricts the input to heart-rate
  / SpO2 / respiratory-rate / HRV samples with s0 − m ≤ t ≤ s1 + m and step records ending in (s0 − m, s1 + m] before
  preprocessing (m = the override in minutes; the cut convention of 4.2) and builds the grid with m; `withParams`
  reuses a preparation only when the mode, the effective preprocessing and the feature parameters are equal; validator:
  when non-null, 0 ≤ `napMode.contextMarginMin` ≤ `preprocess.contextMarginMin` (the app reads heart rate and steps with
  the night's margin, so a nap can never use more context than it receives); `TrainedDefaults.NAP_CONTEXT_MARGIN_MIN`
  (generated, null). `NapSubModel.stageLogBiasByLength: LengthBiasTable? = null` (`lengthsMin`, `bias`; linear
  interpolation in the session length, the first / last row outside; validated: ≥ 1 anchor, finite, > 0, strictly
  increasing, one row of 4 finite numbers per anchor, exclusive with the nap `stageLogBias`; left out of the canonical
  JSON when absent, so every existing bundle keeps its fingerprint); `HybridEstimator` uses it for naps when present.
  Nights never read either field. Tests pin the shipped bundle's `fingerprint()` and `nightModelFingerprint()` to the
  values the base build computed before any code change (logged NOTE), and the personalized-nights test of section 15
  item 6 runs in every `gradlew test` (against the bundled asset, i.e. the previous bundle until an installation).
- **algo-cli.** `evaluate` caches prepared sessions by (effective preprocessing for the session's mode, features), so
  a `--param-grid` that changes `napMode.contextMarginMin` re-prepares naps only. No new option.
- **data-local.** The settings validator checks `algo.napMode.contextMarginMin` (empty, or 0 ≤ value ≤
  `algo.preprocess.contextMarginMin`).
- **data-hc.** No change: core-algo restricts a nap's input itself, so the app, the Night detail, the CSV export and
  Compare give the same estimate from the reader's usual ± `preprocess.contextMarginMin` input; the validator keeps the
  nap margin within it. docs/INTERCHANGE.md and the settings reference state the constraint.
- **app.** The nap algorithm settings list every `napMode` field, so `algo.napMode.contextMarginMin` gets an optional
  whole-minute field (empty = as for nights; at most the night value), English and Russian strings, and the generated
  settings reference.
- **tools/train.** `pulsetrain/napsrealistic.py` (the experiment, gates, rules, final, external decode, install,
  verify), `pulsetrain/pools.py` (pools, tiles, LATESTART, featureless sessions with unique ids),
  `pulsetrain/litprior.py` (the arm table of Appendix B with rule I, targets, bands, G, GL, tilting with the Newton
  polish), `pulsetrain/realism.py` (AUC, contrasts, featureless, LIT and prior-matched metrics, E∩, the rules),
  `pulsetrain/perturb.py`, `pulsetrain/ds006576.py` (fetch, snapshot pin, reduction, R-peaks, heart rate, windows, night
  files, manifest); `models.fit_classifier(..., sample_weight=None)` (weights given: weighted class priors, prior shift
  0; the existing path unchanged without weights); `bias.fit_stage_bias_table`; `bundle.py` (the nap table);
  `kotlin_defaults.py` (`NAP_CONTEXT_MARGIN_MIN`); `loso.GRIDS["nap-evidence"]`; `train.py naps-realistic-gates |
  naps-realistic | naps-realistic-report | naps-realistic-final | naps-realistic-external | naps-realistic-install |
  naps-realistic-verify`; `tools/train/external.py` (fetch, nights, digests; training environment),
  `tools/train/external_stage.py` (YASA; its own environment), `tools/train/requirements-external.txt` and `.lock`,
  `tools/train/external-ds006576-v1.0.5.keys.tsv`; tests on synthetic data only.

The new AlgoParams field and any new bundle change the params hash of every estimate; the app offers every estimate
for recomputation, as after every earlier bundle update (nights' outputs identical by section 15). Personal classifier
deltas are keyed by the unchanged night-model fingerprint and stay valid (section 15 items 5–6).

## 17. What may be claimed afterwards (ALGORITHM.md 13.13, README, user guide)

Every claim names the population (healthy young adults, afternoon naps), labels LitDist and r of the adopted candidate
as selection estimates, and, for the app's defaults, states that only sessions of 90–180 min are estimated as naps
unless the user lowers `sources.minSessionMin`, quoting the default-population numbers of section 7.4. Reasons for a
negative outcome are generated from the failing criteria recorded in `summary.json` (e.g. "C1 failed F: REM contrast r =
…; C2 failed R1 at 150 min …"), never from a fixed sentence.

| outcome | may claim | may not claim |
|---|---|---|
| C2 (or C0) adopted, installed, STAGES | "Nap stage shares are anchored to published averages of afternoon naps of healthy young adults, by session length; on held-out night-derived sessions reweighted to that composition they are within LitDist pp of it (a selection estimate)." "The estimate follows the heart rate: in sessions of the same length it shows more REM and less deep sleep late in the night than at sleep onset, r × the true difference (a selection estimate)." "On 42 real afternoon naps without epoch labels, REM-free naps get at most … % REM, deep sleep is higher in naps than in quiet rest, … % of naps are flagged implausible; the group-mean deep-sleep and REM shares lie within the published ranges, which the anchored level satisfies largely by construction." "It places REM where real naps had REM" only if the m60 endpoint (13.9) passed | that the stage breakdown of a single nap is accurate; any epoch-level accuracy on real naps; validity for morning or evening naps, a return to sleep after the night, older adults or one person's pattern; "realistic" without the qualifier "anchored to published averages"; that a high DEEP share in a nap is impossible (genuine naps reach about 70 %) |
| C0 or C2 adopted in the second tier (F failed), installed, SLEEP_WAKE or SKIP | "Nap stage shares shown in the app are anchored to published averages of afternoon naps of healthy young adults, by session length (LitDist, a selection estimate); a featureless session no longer decodes as a deep-sleep block"; the external numbers that were gated ((a), (c), (d)) and (b) as reported; "naps are written to Health Connect as asleep / awake (or not at all) because the estimate did not show that it follows the heart rate across sleep regimes (generated F failure)" | that the stage breakdown follows the heart rate; that any single nap's stages are accurate; stage information in Health Connect for naps |
| C1 adopted, installed, STAGES | the evidence claim and the external numbers | that the stage mix matches published nap compositions (it is calibrated to night windows; R5 only ensures it is not further from them than N1) |
| X adopted and installed, rule (ii) SLEEP_WAKE | the claims above about the estimate shown in the app; "naps are written to Health Connect as asleep / awake because the stage estimate did not beat 'all light sleep' by the pre-registered margin on a nap-like composition" (or the generated reason) | stage information in Health Connect for naps |
| X adopted and installed, rule (ii) SKIP | the claims above about the estimate shown in the app; "naps are not written to Health Connect because even the sleep / wake output did not reach the pre-registered κ" | any nap output in Health Connect |
| X adopted, rule (iii) failed or void, N1 kept (policy from N1's rule (ii), possibly demoted) | "The new model was not installed because on real afternoon naps it failed the pre-registered checks (generated list) / because the real-nap test was void (reason)"; N1's policy and its reason | anything about X on real naps beyond those checks |
| no candidate qualifies, N1 kept, STAGES (N1 passed F, R and rule (ii) and was not demoted) | 13.12's claims plus the new checks it passed | realism beyond them |
| no candidate qualifies, N1 kept, STAGES demoted by rule (iii) to SLEEP_WAKE or SKIP | "Nap stages were kept out of Health Connect because the nap model failed pre-registered checks on real afternoon naps (generated list)" | any stage information for naps; the documentation must say that the stages of naps shown in the app are not validated |
| no candidate qualifies, N1 kept, SLEEP_WAKE by rule (ii) | "Naps are written to Health Connect as asleep / awake only, because no nap stage model passed the pre-registered checks (generated list of failing criteria per candidate); sleep / wake κ = …" | any stage information for naps; the documentation must say that the stages of naps shown in the app are not validated |
| no candidate qualifies, N1 kept, SKIP | "Naps are not written to Health Connect: no nap model passed the checks and the sleep / wake output did not reach the pre-registered κ" | any nap output in Health Connect |

## 18. Commands, compute and disk

```
# after the pre-registration commit (this file and its digest) and its PRE-REGISTRATION and digest NOTE lines, before
# the code commit (reproduction-only gates; nothing of C0 / C1 / C2 is fitted)
mise exec -- ./gradlew test ktlintCheck detekt :algo-cli:installDist
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-realistic-gates --out-run naps-realistic-gates \
    --jobs 16
# the code commit (the code, the lock, the key list, the log); then the build of that commit
mise exec -- ./gradlew :algo-cli:installDist
# ds006576, test only, no model involved; frozen before the experiment
mise exec -- python -I tools/train/external.py fetch --data tools/train/data/external/ds006576 --workers 4
mise exec -- python -m venv tools/train/.venv-external
tools/train/.venv-external/bin/python -m pip download yasa==0.7.0 --no-deps -d tools/train/.venv-external/wheelhouse
sha256sum tools/train/.venv-external/wheelhouse/yasa-0.7.0-py3-none-any.whl  # = the lock's yasa-wheel digest
tools/train/.venv-external/bin/python -m pip install --no-deps --find-links tools/train/.venv-external/wheelhouse \
    -r tools/train/requirements-external.lock
tools/train/.venv-external/bin/python -I tools/train/external_stage.py --data tools/train/data/external/ds006576
mise exec -- python -I tools/train/external.py nights --data tools/train/data/external/ds006576  # digests only
# the experiment: gates, LOSO, rules (i) and (ii) for N1 and the adopted candidate
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-realistic --out-run naps-realistic --jobs 16
mise exec -- python -I tools/train/train.py naps-realistic-report --out-run naps-realistic      # re-render, no algo-cli
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-realistic-final --exp naps-realistic \
    --out-run naps-realistic-final
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-realistic-external --exp naps-realistic \
    --final naps-realistic-final --data tools/train/data/external/ds006576 --out-run naps-realistic-external
mise exec -- python -I tools/train/train.py naps-realistic-install --exp naps-realistic --final naps-realistic-final \
    --external naps-realistic-external --install-model
mise exec -- python -I tools/train/train.py defaults --run naps-realistic-final --write
mise exec -- ./gradlew :algo-cli:installDist
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-realistic-verify --exp naps-realistic \
    --final naps-realistic-final --out-run naps-realistic-verify
mise exec -- ./gradlew test ktlintCheck detekt
mise exec -- python -I -m pytest tools/train/tests -q
```

Expected cost on the 24-core machine (estimates, not limits): `naps-realistic-gates` about 5–10 min; `naps-realistic`
about 45–75 min at 16 jobs (13.12's run took 501 s with 373 523 training rows; here about 2.3 × 10⁵ rows per fit, about
100 inner and up to 156 outer fits, about 25 C0 inner refits; about 1.3 × 10⁵ EVAL and MIDANCHOR decodes ((8677 + 7142)
sessions × 2 variants × N1, C0, C1, C2) plus 3.5 × 10⁴ RULES / HMM-table decodes, about 1.5 × 10⁵ perturbed and
4.9 × 10⁵ featureless decodes, plus the inner grids and fixed points; the diagnosis decoded about 3 × 10³ sessions per
second); the ds006576 fetch about 1–2 h (59.6 GB streamed at the pilot's 9.4–18.4 MB/s); staging about 0.5 h. Before the
code commit, the featureless decodes of one variant are timed with the shipped bundle (timing and output size only, no
metric computed, outputs deleted, logged as a NOTE); if that projects more than 2 h for all featureless decodes of the
run, the run uses seeds 0–9 only (990 sessions per subject copy), and that choice is logged before the code commit. Disk:
per-epoch CSVs about 135 bytes per epoch (measured on `nap-model-verify`), about 1.0 GB for N1, C0, C1 and C2 in both
variants on EVAL (MIDANCHOR decodes keep no per-epoch CSV); the run directory otherwise below 1.5 GB; ds006576
derivations about 2 GB; raw ds006576 bytes are not stored. `--jobs` changes no result.

## 19. Outputs, provenance, deviations

- `naps-realistic-gates` writes `out/naps-realistic-gates/gates.json` (G1, G2 record, G3a, G4) and a RESULT line.
  `naps-realistic` writes `out/naps-realistic/`: `gates.json`; `sessions/` (tile, EVAL, MIDANCHOR, LATESTART, perturbed
  and featureless files and counts); `features/`; `lit/` (weights, targets, λ, Newton steps, effective sample sizes);
  `inner/<variant>/<group>/selection.json` (every grid point, C log-losses, C1 and C2 fixed points with traces; C0's
  refit check and table); `selections.json`; `outer/<variant>/nap/<subject>.json` (C1 / C2 fold nap models without
  level); `folds.json`; `common-set.json` (E∩ and the excluded counts per model and reason); `results.jsonl` (every
  decoded session of N1, C0, C1, C2, RULES, HMM-table, nights included); `per-epoch/`; `summary.json` (rule (i) and rule
  (ii) for N1 and the adopted candidate, every criterion with its value); `report.md`; `algocli.log`; `run.log`.
  `naps-realistic-final` writes `final/hybrid_model.json`, `final/result.json`, `summary.json`;
  `naps-realistic-external` writes `summary.json` (rule (iii), void status, freeze checks), `report.md`, the decodes;
  `naps-realistic-install` writes `trained_defaults.json` into the final run; `naps-realistic-verify` writes
  `summary.json`.
- Every run records the algo-cli fingerprint (and fails if the build changes during the run), `meta.code`, this file's
  SHA-256, the ds006576 manifest and summary digests, the YASA version and the wheel and classifier digests.
- **Two commits.** (1) The pre-registration commit: this file and its SHA-256
  (`docs/prereg/prereg-naps-realistic.md.sha256`), committed on their own before any code of this experiment is written
  or run; the PRE-REGISTRATION and digest NOTE lines (`NOTE prereg-naps-realistic.md sha256 …`) are appended to the log
  at that time. (2) The code commit: the analysis code (with `napsrealistic.PREREG_SHA256`), the core-algo / algo-cli /
  data-local / app changes of section 16 (with `NAP_CONTEXT_MARGIN_MIN` = null; G2 shows they change no decode),
  `requirements-external.lock`, the ds006576 key list, the test of the pre-registration and the log, committed before
  the full ds006576 fetch and before the first algo-cli call of the experiment proper. Between the two: the
  implementation, then the reproduction-only gates (`naps-realistic-gates`) and the featureless timing of section 18,
  logged. algo-cli is built from the code commit. A change of this text after the pre-registration commit is an
  amendment: allowed only before the code commit, only to make an implementation detail that cannot work as written
  workable (never a candidate, pool, metric, threshold or rule), logged with its reason, kept in the git history and
  disclosed in ALGORITHM.md section 13.13. The START / RESULT / NOTE lines of every run and rebuild
  are logged in `tools/train/out/commands.log`, committed as `docs/prereg/commands-naps-realistic.log`;
  `tools/train/tests/test_prereg.py` checks that file, log and digest agree, that the pre-registration precedes every
  START of a `train.py naps-realistic*` subcommand and of the ds006576 extraction, and the order and uniqueness of
  section 13.6.
- **Every run is reported**, including runs stopped by a gate or aborted, with the gates it reached, any decision it
  computed and its log lines; ALGORITHM.md 13.13 lists them all.
- Any change to the analysis code after the code commit is logged with its reason and disclosed in ALGORITHM.md section
  13.13. A change that touches a candidate, a fit, a pool, a metric or a rule requires re-running the experiment from
  the start and is a deviation: the decision of the run before the change (if it reached one) is published next to the
  decision of the deviating run, with both runs' gates. A fix of an implementation error found by a gate is a deviation
  too unless the gate ran before the code commit. ALGORITHM.md section 13.13 documents the protocol, every table, every gate
  and decision and a plain conclusion whatever the outcome, including N1 kept with SLEEP_WAKE.

## 20. Limitations stated in advance

- No labelled nap with heart rate exists: every labelled number comes from night-derived windows; the reweighted pools
  are a label-shift construction (they assume that heart rate given the stage does not depend on the time of day, while
  nap NREM heart rate is higher than night NREM heart rate in the same people; the features are level-invariant, but
  variability may differ); ds006576 gives group-level weak labels only.
- The targets are afternoon averages of healthy young adults under rule I. The rows below 45 min of sleep rest on R1
  arms only (no R0 arm with stage numbers exists there); above 90 min of sleep they follow the N3-minute plateau, an
  extrapolation with weak direct support (no rule-I arm beyond 110 min). The length → TST mapping assumes the training
  windows' 5-min margins and the median nap WASO; how OHealth delimits a nap is unverified. Morning naps, evening naps,
  returns to sleep and short nights of 90–180 min get a prior that does not describe them; a user whose 2–3-h sessions
  are nights can lower `napMode.maxSessionMin`.
- **Default population.** With default settings the app estimates only sessions of 90–180 min as naps
  (`sources.minSessionMin` = 90), exactly where the targets follow the 60–90-min interpolation and the plateau and where
  short nights mix in; the gates average all anchors, and the ds006576 NREM windows (about 68 min) would not be
  estimated by the default app. The default-population numbers (section 7.4) say which part of the evidence applies.
- The decisive Tier-2 guard GL is, per row, the maximum of mean + 2 SD over a few arms with approximate (normal)
  dispersions of zero-inflated shares; G, reported only, was set partly on the open hypnograms that estimate its
  false-flag rate and was touched by the test set's paper. Both use the afternoon rows for every nap.
- Bias and table fits use 31 young healthy Walch subjects; BIDSleep headband labels are N3-richer and are secondary.
- The candidate set, the gates and their thresholds were designed after the diagnosis probes (section 2.2), with N1's
  values known.
- Night windows are not naps: LATE windows start inside sleep or after a nocturnal awakening, with nocturnal context;
  they test evidence-following, not daytime composition.

## Appendix A. Deferred covariates: local clock time and time since the main sleep (specified, not built here)

**Decision for this experiment: no clock covariate.** Every nap gets the afternoon population default.

| for | against |
|---|---|
| Morning sleep is REM-richer and SWS-poorer: ~90-min morning naps N3 13.0 / 6.8 %, REM 19.8 / 23.4 % of TST (Gilson 2016) vs afternoon 23.0 / 15.0 %; 150–180-min morning sleep REM 28.4 % (Tinguely 2006); SOREM 83 % at 08:00 to 0 % at 20:00–22:00 (Bes 2009); young adults' nap REM 28.0 / 18.5 % at relative clock 09 / 11 h vs 9.5–11.7 % at 13–14 h (Chellappa 2009, figure, ± 1 pp). Evening: one study (Cohen 2010, n = 10, N3 40.9 %, REM 8.4 %) | Clock is not circadian phase: SWS % of spontaneous naps tracks proximity to the temperature maximum (partial r −0.508), not clock bins (Campbell & Zulley 1989); the temperature minimum lies between 03:50 and 06:01 by chronotype (Baehr 2000), DLMO varies with SD 1.25 h among late sleepers (Sletten 2010), so a fixed clock boundary misassigns anyone not on the literature participants' schedule. The phase-correct covariate is the time since the main sleep, with ordering, missing-night and recompute costs (below). The morning numbers come from three studies, all after nights restricted to 4–6.5 h; evening from one. No labelled session with heart rate has a daytime clock, so a clock-conditioned table would ship unvalidated morning and evening behaviour. The default is where the evidence is: 17 of 26 spontaneous naps began 11:00–17:00 (Campbell & Zulley 1989) |

Data path a later experiment would need (nights unaffected: NIGHT mode never reads the field):

| layer | requirement |
|---|---|
| core-algo | `NightInput.context: SessionContext? = null` with `startZoneOffsetSec: Int?`, `previousMainSleepStartMs: Long?`, `previousMainSleepEndMs: Long?`; null = unknown → the unconditional default; read only on the nap path |
| data-hc | the start offset from `NightCandidate.startZoneOffsetSec` (device-zone fallback flagged by `startOffsetIsFallback`; the ledger already stores it); a **main sleep** = the latest merged session ≥ `napMode.maxSessionMin` ending before the nap, of any enabled package, staged or estimated, from Health Connect (one extra read of [start − 36 h, start), since the candidate set drops other nights); several sources resolved by the existing duplicate rule; a non-enabled package's night counts as missing |
| ledger, recompute | the covariate (or its hash) stored in `estimated_nights` (Room migration 2 → 3) and compared in `writtenSkipReason`; a rule "after a night is written or changed, re-process the naps starting within H h after its end"; Night detail, CSV export and Compare re-derive it identically |
| algo-cli, interchange | optional night-file keys `startZoneOffsetSec` and `previousMainSleep` = [startMs, endMs], absent = unknown, documented in docs/INTERCHANGE.md section 1 |
| training data | night files unchanged; the covariates in a sidecar `data/nights/covariates.json` keyed by night id, so every earlier byte-for-byte session gate keeps passing; Walch = unknown for every session; BIDSleep = the America/New_York offset at the session start (an inference about where participants slept); only 88 BIDSleep nights have a previous night within 24 h |
| validation | needs labelled heart-rate data with daytime clock times, which do not exist openly today |

## Appendix B. Literature arms (the table of `litprior.ARMS`) and references

Values are copied from the verified notes (synthesis tables 1a / 1b, gap-fill, cross-check). TST and minutes are group
means; DEEP and REM are % of TST, reported (R) or the ratio of group means (D); WASO% = WASO / (TST + WASO) of the sleep
period; class = population / prior-sleep class / clock. "Uses": row = the target row and band of its bin; W = the WASO
share a; P = the N3-minute plateau and its band; GL = a dispersion arm of the literature-only guard (dispersion given).

**Included by rule I (26 arms).**

| arm | DOI | n | class | TST | DEEP % | REM % | N3 min | WASO % | uses (GL dispersion: N3 ±, REM ±, kind) |
|---|---|---|---|---|---|---|---|---|---|
| Leong et al. 2023, 10-min | 10.1093/sleep/zsad025 | 32 | young / R1 / aft | 11.53 | 2.57 R | 1.50 R | 0.31 | 19.09 | row [0, 15), W, GL (DEEP SD 8.23 % from mean + 1 SD 10.8 %; no REM dispersion) |
| Leong 2023, 30-min | same | 32 | young / R1 / aft | 31.68 | 27.45 R | 0.83 R | 8.52 | 12.32 | row [15, 45), W, GL (22.63, 4.38, SD %) |
| Mednick et al. 2002, short | 10.1038/nn864 | n.v. | young / R1 / aft | 29.1 | 22.68 D | 6.87 D | 6.6 | — | row [15, 45) (Table 1; the text gives 31.6 / 2.7) |
| Leong 2023, 60-min | 10.1093/sleep/zsad025 | 32 | young / R1 / aft | 61.88 | 35.12 R | 11.68 R | 21.57 | 9.48 | row [45, 80), W, P, GL (21.16, 14.26, SD %) |
| Mednick 2002, long | 10.1038/nn864 | n.v. | young / R1 / aft | 59.6 | 45.97 D | 14.93 D | 27.4 | — | row [45, 80), P |
| McDevitt, Alaynick & Mednick 2012 | 10.1016/j.physbeh.2012.05.021 | 27 | young / R1 / aft | 74.2 | 34.9 R | 15.8 R | 25.9 | — | row [45, 80), P, GL (16.3, 13.8, SD %) |
| Cai et al. 2009, NREM + REM groups pooled by n | 10.1073/pnas.0900271106 | 40 | young / R1 / aft | 66.09 | 33.76 D | 15.24 D | 22.31 | — | row [45, 80), P |
| Mednick, Nakayama & Stickgold 2003, 60-min | 10.1038/nn1078 | 26 | young / R0 / aft | 59.3 | 34.06 D | 7.08 D | 20.2 | — | row [45, 80), P |
| Nishida & Walker 2007 (stage-sum basis) | 10.1371/journal.pone.0000341 | 14 | young / R0 / aft | 61.98 | 46.43 D | 16.94 D | 28.78 | — | row [45, 80), P |
| Cousins et al. 2019 | 10.1093/sleep/zsy207 | 27 | young / R0 / aft | 60.35 | 33.52 D | 8.63 D | 20.23 | — | row [45, 80), P, GL (13.15, 6.13, SD min) |
| Bergamo et al. 2026 (published paper only) | 10.1111/jsr.70253 | 19 | young / R0 / aft | 62.06 | 24.04 D | 6.61 D | 14.92 | — | row [45, 80), P, GL (11.03, 5.60, SD min) |
| Ong et al. 2020 | 10.1093/sleep/zsaa058 | 20 | young / R0 / aft | 63.2 | 22.63 D | 11.55 D | 14.3 | 15.05 | row [45, 80), W, P, GL (2.5, 2.1, SEM min) |
| Chen et al. 2021, young | 10.1111/psyp.13701 | 60 | young / R0 / aft | 61.50 | 31.25 D | 10.15 D | 19.22 | 9.64 | row [45, 80), W, P, GL (1.96, 1.09, SEM min) |
| Scullin et al. 2017, young | 10.1093/sleep/zsx035 | 30 | young / R0 / aft | 73.20 | 25.85 D | 13.83 D | 18.92 | 14.98 | row [45, 80), W, P, GL (17.16, 6.95, SD min) |
| Mednick et al. 2008 | 10.1016/j.bbr.2008.04.028 | 19 | young / R0 / aft | 69.38 | 18.09 D | 12.80 D | 12.55 | — | row [45, 80), P, GL (13, 12, SD min) |
| Ujma et al. 2015 | 10.1038/srep17159 | 79 | young / R0 / aft | 70.39 | 16.24 D | 8.14 D | 11.43 | — | row [45, 80), P, GL (12.07, 6.60, SD min) |
| Fogel et al. 2014, young | 10.1002/hbm.22426 | 13 | young / R0 / aft | 72.08 | 15.41 R | 12.10 R | 11.45 | 13.10 | row [45, 80), W, P, GL (3.10 SEM %; no REM dispersion) |
| Lau et al. 2015 | 10.1371/journal.pone.0125752 | 40 | young / R0 / aft | 77.56 | 19.63 R | 9.34 R | 16.50 | 11.22 | row [45, 80), W, P, GL (15.79, 9.62, SD %) |
| Lo, Dijk & Groeger 2014 | 10.1371/journal.pone.0108100 | 34 | young / R0 / aft | 64.1 | 31.51 D | 18.72 D | 20.2 | — | row [45, 80), P, GL (14.0, 12.5, SD min) |
| Fudge et al. 2024, 1-h lunch | 10.3390/clockssleep6030027 | 40 | young / R0 / aft | 53.5 | 13.0 R | 5.1 R | — | — | row [45, 80), GL (19.5, 8.3, SD %) |
| Fudge 2024, 2-h lunch | same | 40 | young / R0 / aft | 58.9 | 17.3 R | 5.2 R | — | — | row [45, 80), GL (20.4, 10.2, SD %) |
| Mednick 2003, 90-min | 10.1038/nn1078 | 19 | young / R0 / aft | 96.3 | 49.01 D | 26.58 D | 47.2 | — | row [80, 110), P |
| Whitehurst et al. 2016 | 10.1073/pnas.1518202113 | 60 | young / R0 / aft | 83.19 | 27.30 D | 15.81 D | 22.71 | 12.62 | row [80, 110), W, P, GL (14.83, 10.58, SD min) |
| Whitehurst, Naji & Mednick 2018 | 10.1016/j.nbscr.2018.03.001 | 32 | young / R0 / aft | 96.36 | 18.53 D | 14.22 D | 17.86 | 11.37 | row [80, 110), W, P, GL (14.61, 12.85, SD min); largest individual N3 54 min |
| Baran, Mantua & Spencer 2016, young (home PSG) | 10.1162/jocn_a_00938 | 13 | young / R0 / aft | 87.4 | 23.0 R | 5.7 R | — | 5.41 | row [80, 110), W, GL (12.3, 7.6, SD %) |
| Dinges et al. 1987, group P6 | 10.1093/sleep/10.4.313 | 8 | young / R0 / aft | 80 | 18.25 D | — | 14.6 | — | row [80, 110) (DEEP), P |

("n.v." = n not verified.) Medians and ranges (derive): bins as in section 5.1; WASO share k = 11: 5.41, 9.48, 9.64,
11.22, 11.37, 12.32, 12.62, 13.10, 14.98, 15.05, 19.09 → a = 0.12317; N3 minutes of the 20 arms with TST ≥ 45 min:
11.43, 11.45, 12.55, 14.3, 14.6, 14.92, 16.5, 17.86, 18.92, 19.22, 20.2, 20.2, 20.23, 21.57, 22.31, 22.71, 25.9, 27.4,
28.78, 47.2 → median 19.71, range 11.43–47.20.

**Excluded (first reason; their values stay in `litprior.ARMS` for the unit test of the exclusion).**

| arm | DOI | TST / DEEP % / REM % | reason |
|---|---|---|---|
| Sargent et al. 2026, 1 h / 2 h | 10.3390/clockssleep8030044 | 52.4 / 56.7 / 3.6; 105.0 / 35.3 / 12.0 (WASO 1.9 / 6.6 %) | I1 athletes after morning training |
| Boukhris, Suppiah & Driller 2026, 90-min | 10.1111/jsr.70120 | 56 / 16.1 / 12.5 | I1 athletes; I4 wearable forehead EEG |
| Lau, Alger & Fishbein 2011, immediate / delayed | 10.1371/journal.pone.0027139 | 63.17 / 31.30 / 8.98; 77.45 / 28.64 / 16.60 | I3 start time not stated |
| Evans et al. 1977, three groups | 10.1126/science.17922 | 33–42 / 39.4–45.2 / 0 | I2 prior sleep not described (R?) |
| Saletin et al. 2017 | 10.1093/sleep/zsx118 | 33.5 / 25.4 / 2.0 | I2 5-h nights (R2) |
| Brooks & Lack 2006 (via Hilditch 2017) | 10.1093/sleep/29.6.831 | 5–30 (exact) / 0–38 / — | I2 5-h night (R2); secondary source |
| Takahashi & Arito 2000 | 10.1093/sleep/23.6.1h | 10.2 / 0 / — | I2 4-h night (R2) |
| Cousins et al. 2021, 5-h / 6.5-h nights | 10.1038/s41598-021-84625-8 | 77.41 / 39.5; 75.81 / 40.1 | I1 adolescents; I2 |
| Dinges 1987, P30 (and T18 / T42 / P54) | 10.1093/sleep/10.4.313 | 117 / 54.1 / — | I2 total sleep deprivation |
| Li et al. 2025 | 10.2147/NSS.S499702 | 28.48 / 64.7 / 4.9 | I2 24-h deprivation; I3 morning |
| Chen 2021 older, Scullin 2017 older, Fogel 2014 older, Baran 2016 older, Backhaus et al. 2016 (two groups; 10.3389/fnagi.2016.00125), Campbell et al. 2011, Monk et al. 2001 | (synthesis 1a) | older adults | I1 older adults |
| Tinguely et al. 2006 morning; Gilson et al. 2016 (four arms); Deliens et al. 2013; Gillberg et al. 1996 | 10.1186/1471-2202-7-2; 10.3390/brainsci6010001; 10.5665/sleep.3220; 10.1093/sleep/19.7.570 | morning, after 4–6.5-h nights | I2 restriction; I3 morning |
| Cohen et al. 2010 | 10.5664/jcsm.27990 | 100.3 / 40.9 / 8.4 | I3 evening |
| Campbell & Zulley 1989 (spontaneous naps, disentrained) | 10.1111/j.1469-8986.1989.tb00714.x | 26 naps of 30–225 min, SWS 4.7–26.9 % in the 8 longest | I3 06:33–18:48, no fixed clock; cited only as context |
| Münch et al. 2007 / Chellappa et al. 2009 (naps across 40 h) | 10.1093/sleep/30.6.781; 10.1093/sleep/32.9.1201 | pooled day and night naps | I3 every circadian phase |
| Mollicone et al. 2007 | 10.1016/j.actaastro.2006.09.022 | no nap-only stage values | I2 restricted anchor sleep; I6 |
| Lee et al. 2017 | 10.1371/journal.pone.0185616 | 59.5 / SWS + REM ≤ 18.3 | I6 no separate value |
| Subramoniam et al. 2026, young / older | 10.1093/sleepadvances/zpag083 | 61.02 / 31.32 / 10.41 | I5 probable duplicate of Chen 2021 |
| Cellini et al. 2016 | 10.1111/psyp.12595 | 80.05 / 27.26 / 15.22 (WASO 12.9 %) | I5 probable duplicate of Whitehurst 2016 / 2018 |
| Nicolas et al. 2022 (the Zenodo cohort) | 10.7554/eLife.73930 | 79.1 / 22.1 / 12.1 | I5 auditory cueing during NREM2–3 |
| McDevitt et al. 2026, NREM / REM group | 10.1162/jocn.a.82 | 49.27 / 39.92 / 0.25; 89.91 / 24.90 / 17.90 | I5 the test set's paper (weak labels only) |
| Bergamo 2026 OSF deposit (individual naps) | osf.io/z5r2j | — | no licence; not used at all |

Other sources: Hilditch, Dorrian & Banks 2017 (10.1016/j.sleep.2016.12.016); Fushimi & Hayashi 2008
(10.1111/j.1479-8425.2008.00350.x); Bes, Jobert & Schulz 2009 (10.1093/sleep/32.3.392); Baehr, Revelle & Eastman 2000
(10.1046/j.1365-2869.2000.00196.x); Sletten et al. 2010 (10.3389/fneur.2010.00137); Åkerstedt & Gillberg 1981
(10.1093/sleep/4.2.159); Knowles et al. 1986 (10.1177/074873048600100404); Arnal et al. 2020 (10.1093/sleep/zsaa097);
Saerens, Latinne & Decaestecker 2002 (10.1162/089976602753284446); Vallat & Walker 2021 (10.7554/eLife.70092). Open
individual hypnograms (composition only): Nicolas et al. 2022, eLife 11:e73930 (10.7554/eLife.73930), Zenodo 6642860 (CC
BY 4.0); Mei et al. 2018, Data in Brief 18:1513 (10.1016/j.dib.2018.04.073), OSF chav7 (BSD-3-Clause); Sleep-EDF
Expanded SC (PhysioNet, ODC-By 1.0). The test set's paper, used only as weak labels: McDevitt et al. 2026
(10.1162/jocn.a.82).
