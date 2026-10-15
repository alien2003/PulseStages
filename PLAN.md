# PulseStages — plan, decisions, research

Working document for building PulseStages: what is done in which stage, why things are the way they are, and
what was verified where. All facts below were checked on 2026-10-13 against Maven metadata, library sources from
Google Maven, AOSP sources and developer.android.com (links inline). Nothing about the Health Connect API was taken
from memory.

## 1. Stages (one Conventional-Commits commit each)

| # | Stage | Result |
|---|---|---|
| 1 | Read the spec, write this plan | `PLAN.md` |
| 2 | Environment inside the project only | `.mise.toml` (Temurin 17, Python 3.12 + venv `tools/train/.venv`), Android SDK in `.tooling/android-sdk` (cmdline-tools 23.0, platform-tools 37.0.1, platforms android-36 and android-37.0, build-tools 36.0.0 and 37.0.0) |
| 3 | Versions and Health Connect docs research | §3–§5 below |
| 4 | Gradle multi-module skeleton, CI, LICENSE, structural tests, public API contracts | green `assembleDebug test ktlintCheck detekt assembleRelease` |
| 5a | `:core-algo` (TDD): synthetic generator → preprocessing/features → RULES → HMM → post-processing → metrics → hybrid features, classifier inference, HSMM → facade, params hash, CSV; `:algo-cli` | tests green |
| 5b | `tools/train`: dataset download (license-checked), OHealth emulation, steps from accelerometer, LOSO training/validation through `:algo-cli`, model bundle → `app/src/main/assets/models/`, numbers → `ALGORITHM.md` | done (run `loso-v2`, after the audit fixes): HYBRID-MLP (α = 1e-4, Walch + BIDSleep with BIDSleep steps coded as algo-cli computes them, EXPLICIT decoder, w_e 0.5, no time prior) κ 0.334 ± 0.133 on Walch PSG (worse 2-min variant, LOSO; 0.317 ± 0.019 over 4 seeds), but 25 % DEEP / 34 % REM predicted vs 14 / 23 % PSG; HMM defaults stay the physiology table (supervised estimates fail the validity gate). Then the pre-registered stage-proportion experiment (`docs/prereg/prereg-bias.md`; runs `bias-base`, `bias-base/bias`, `bias-final`; ALGORITHM.md §13.10): new decoder options `hybrid.priorExponent` (β, stays 1) and the bundle's `stageLogBias` (b); **proportion matching b adopted** — Walch κ 0.339 ± 0.169 (worse variant, mean2min; 0.348 instantaneous), summed per-night minute error 147 vs 219 min (DEEP 33, REM 41, AWAKE 19, LIGHT 53 min), shares DEEP 13–15 % / REM 20–24 % / LIGHT 57–59 % vs PSG 14 / 23 / 57 %, sanity flags 2–7 instead of 11–12 of 31 nights; κ only within noise of the baseline (Δκ +0.003 [−0.037, 0.038] mean2min; inner CV: b lost > 0.01 κ in 4 of 10 groups); cost: BIDSleep κ −0.03 and 12–15 of 253 BIDSleep nights with DEEP < 3 %. Audit fixes: `train.py bias-report` (pairwise B − A, per-code sanity flags, C's BASE fallbacks in 47 of 156 folds), `--allow-other-build` (the final build reproduces the whole experiment byte for byte), pre-registration committed under `docs/prereg/`. Bundle `pulsestages-walch+bidsleep-mlp-bias-final` installed; `TrainedDefaults.kt` regenerated; `ALGORITHM.md` |
| 5c | Naps: pre-registered nap-proxy evaluation (`docs/prereg/prereg-naps.md`; runs `naps`, `naps-final`, `naps-verify`; ALGORITHM.md §13.11) on the `bias-base` fold artefacts, no retraining | done: proxies = the first 30–180 min of every Walch / BIDSleep night, no data after the end; night-level reproduction 1136 / 1136 decodes identical. Shipped default on Walch nap proxies (worse variant, L 30–120 pooled): accuracy 0.413, summed share error 93 pp, 19–30 % REM predicted where PSG has 1–9 %. b off for naps (B) is worse; **short-session bias b_short below 180 min (C-180) adopted** by the rule: accuracy 0.488, error 72 pp (paired ΔE −12 / −21 pp, Δaccuracy +0.07 / +0.10, CIs exclude 0; κ unchanged within noise, 0.1–0.3); BIDSleep the same direction. Naps stay rough (a 30-min nap is no better than "all LIGHT"; about 4 in 5 flagged low-confidence on the experiment's build, 33–41 % (`LOW_HR_RANGE` only) once the sanity check skips sessions under 180 min). Audit follow-up (post hoc, reporting only, `naps-rerun` / `naps-posthoc`): the committed code reproduces every stored file of `naps` byte for byte; at 150 / 170 min, which T = 180 also covers (short nights under the default 90-min filter), C-180 is no clear gain (ΔE +6 / +8 pp instantaneous, −14 / −10 pp mean2min; too little REM); C-180 vs C-120 within noise; RULES would have won the rule as a candidate (κ ≈ 0). New `hybrid.shortSessionMaxMin` = 180 (TrainedDefaults), bundle `stageLogBiasShort`; bundle `pulsestages-walch+bidsleep-mlp-naps-final` installed; the new build reproduces A on 568 / 568 nights and C-180 on 2840 / 2840 proxies, and so does the build merged with `proportionsMinSessionMin` (`naps-verify-merge`: only the `SANITY_*` codes of the proxies shorter than 180 min are gone) |
| 5d | Night and nap processing modes (`napMode`, `SessionMode`; ALGORITHM.md §18): one nap threshold replacing three, nap estimator / output policy (STAGES, SLEEP_WAKE, SKIP) / post-processing and decoder overrides, optional nap model in the bundle, ledger schema 2, Naps settings group, nap badge and filter | done: default decodes unchanged (`NapModeRegressionTest`: 767 digests of the previous build; real nights + proxies byte-identical in `algo-cli`; `naps-verify-modes` 568 / 568 nights, 2840 / 2840 proxies); no nap model trained yet |
| 5e | Dedicated nap model: pre-registered LOSO on nap proxies v2 (`docs/prereg/prereg-nap-model.md`, committed with its analysis code `pulsetrain/napmodel.py` as `d0b7f7b` (the same patch is `72eb467` on `main`) before the first run; runs `nap-model`, `nap-model-final`, `nap-model-verify`; ALGORITHM.md §13.12) | done: proxies = onset (first 30–170 min) + ends-in-wake windows (wake bout ≥ 2 min → next wake bout ≥ 5 min, OHealth margins, 30–179 min, ≤ 3 per night; only 11 on Walch, 139 on BIDSleep); gate: N0 (= C-180 of 5c) reproduced 568 / 568 nights and all 3408 onset proxies, Python cut = Kotlin cut on every feature row. Inner CV chose the MLP in 10 / 10 selections. Walch, worse variant, both kinds pooled: N0 accuracy 0.499 / E 70.4 pp, **N1 (nap MLP + nap stageLogBias) 0.606 / 53.1 → adopted** by rule (i) (N2 without b 0.517 / 69.8 misses the +0.02 accuracy); paired N1 − N0 accuracy +0.106 [0.059, 0.148] / +0.121 [0.080, 0.162], κ 0.17 → 0.36; rule (ii): accuracy − all LIGHT +0.076, κ 0.356 → output policy **STAGES**. Final nap MLP on all 78 subjects, nap b (+0.339, +0.167, −0.228, −0.278), decoder EXPLICIT / 0.5 / 0 installed as the bundle's `nap` section (`pulsestages-walch+bidsleep-mlp-nap-model-final`); `TrainedDefaults` `NAP_HYBRID_*`, `NAP_OUTPUT_POLICY`; nights unchanged (568 / 568 fold level, installed vs previous bundle per epoch byte-identical). Naps stay rough (4 in 10 epochs wrong; 11 Walch windows ending in wake, on which it is no better than all LIGHT) |
| 5f | Realistic nap estimates: an evidence-following nap model with a literature-anchored stage mix, tested on real afternoon naps (pre-registered: `docs/prereg/prereg-naps-realistic.md`, committed on its own as `52517a7`; the analysis code, the app changes of its section 16 and Amendment 1 (implementation details only, logged with the new digest `38c1589e…` before any experiment step) committed as `c0b3471` before the ds006576 fetch and the first algo-cli call of the experiment; runs `naps-realistic-base`, `naps-realistic-g2-manual`, `naps-realistic-timing`, `naps-realistic-gates`, `naps-realistic-gates-a1`, `naps-realistic`, `naps-realistic-final`, `naps-realistic-external`, `naps-realistic-verify`; ALGORITHM.md §13.13) | done: a diagnosis first showed that N1 (the 5e nap model) is in effect a night-onset template (session-level slope of predicted on true DEEP share 0.13–0.28, REM about 0; a quiet hour decodes as about 60 % DEEP). Candidates: C0 (N1 + a per-length level fitted to published afternoon-nap compositions), C1 / C2 (evidence model: session-time and step features masked, trained on tiles of whole nights, night-model HSMM statistics, no heart-rate context around the nap; a night-level bias / the per-length literature level). Gates D (per-epoch AUC vs N1), F (late-night vs sleep-onset contrast of the REM and DEEP shares at least 0.25 of the truth), R (featureless sessions inside the published band, late-night DEEP tail, guard excess on the literature-reweighted pool, interpolation, literature distance not above N1's); every provenance and reproduction gate passed. **Rule (i): tiers 1 and 2 empty, N1 kept, bundle unchanged**: N1 fails F (REM r 0.012) and R (featureless REM below the band at 90–135 min, late DEEP tail 0.226); C0 fails F (REM r 0.122) and R; C1 fails D (sleep-onset DEEP AUC −0.066) and R (guard excess 0.170, literature distance 14.31 > 12.66 pp); C2 fails D (−0.057), F (REM r 0.070) and R; literature distance (worse variant) N1 12.66, C0 4.18, C1 14.31, C2 5.40 pp. **Rule (ii): N1 → SLEEP_WAKE** (literature-reweighted accuracy − all LIGHT −0.055 < 0.03, 4-class κ 0.246, sleep / wake κ 0.269 ≥ 0.20). Rule (iii) on OpenNeuro ds006576 (42 real afternoon naps and 21 complete quiet-wake recordings, test only): no change, N1 in effect and not demoted. `TrainedDefaults.NAP_OUTPUT_POLICY` = SLEEP_WAKE (`defaults --run naps-realistic-final`); new nap-only `napMode.contextMarginMin` (default null) and optional bundle field `nap.stageLogBiasByLength` (absent in the shipped bundle); nights unchanged (568 / 568 per epoch, bundle fingerprints unchanged, personal deltas still apply). The nap stages shown in the app are not validated |
| 6 | `:data-local` (Room, DataStore, logger), `:data-hc` (repository, night pipeline, Changes API sync, backfill, fake repository, idempotency tests) | |
| 7 | `:work` (workers, scheduling, backoff), `:app` (Compose M3 UI, adaptive layouts, permissions, rationale activity, icon) | |
| 8 | README (English), ALGORITHM.md, CHANGELOG.md, screenshots; full check; install on the phone via `$ADB_WIN` | |
| 9 | Known limitations and ideas for the next version | [ALGORITHM.md §16](ALGORITHM.md#16-limitations), README "Project status" |

## 2. Decisions

- **SDK levels.** `compileSdk 37` (latest stable platform `android-37.0`, AGP DSL `compileSdk { version = release(37) { minorApiLevel = 0 } }`),
  `targetSdk 36`, `minSdk 28`. The phone (OPPO Find N6, model CPH2765, ColorOS `CPH2765_16.0.10.500(EX01)`) reports
  `ro.build.version.release=16`, `ro.build.version.sdk=36`. targetSdk declares the platform behaviour the app was
  tested against; 36 is the highest level that can be verified on this device. Bumping to 37 is one line in
  `gradle/libs.versions.toml`. connect-client 1.1.0 requires `minCompileSdk=36` (AAR metadata), so 37 is fine.
- **Package / applicationId:** `io.github.alien2003.pulsestages`.
- **Module graph** (enforced by `:verifyModuleGraph`, `:core-algo:verifyPureJvmRuntimeClasspath` and
  `NoAndroidImportsTest`): `app → {work, data-hc, data-local, core-algo}`, `work → {data-hc, data-local, core-algo}`,
  `data-hc → {data-local, core-algo}`, `data-local → {core-algo}`, `algo-cli → {core-algo}`, `core-algo → ∅`.
  The night pipeline (selection, merging, idempotent writing) lives in `:data-hc` because the spec puts its tests
  there; it uses the Room ledger from `:data-local`.
- **Extra module `:algo-cli`** (pure JVM). Kotlin is the single source of truth for features and decoders. The
  Python training scripts call `algo-cli` to compute features and to run the LOSO evaluation, so the numbers in
  `ALGORITHM.md` come from exactly the code that runs on the phone. Formats: `docs/INTERCHANGE.md`.
- **Model bundle** in `app/src/main/assets/models/hybrid_model.json`; `:core-algo` receives it as a string (no
  Android assets in a JVM module); JVM tests and `algo-cli` read the same file by path.
- **Trained HMM defaults.** If the supervised estimates (emissions, bouts, transitions, time prior) beat the physiology
  table on LOSO kappa *and* pass a validity gate (the specification's hrNorm order DEEP < LIGHT < REM < AWAKE, and
  predicted stage shares inside the sanity corridors), they become the `AlgoParams()` defaults (generated Kotlin
  constants with a provenance header); the table stays available as `EmissionTable.PHYSIOLOGY` /
  `HmmParams.PHYSIOLOGY` ("reset to physiology table"). In `loso-v2` they scored higher but failed the gate, so the
  shipped HMM defaults are the table.
- **paramsHash** = SHA-256 of canonical JSON of `AlgoParams` + hybrid-model fingerprint + personal-model fingerprint
  + output options that change the written data (`awakeAsLight`). The title template does not change the hash.
- **Idempotency.** `clientRecordId = "ps:" + sha256(sorted source uuids)`, `clientRecordVersion = ALGO_VERSION·1000 +
  recomputeCount`, never below the version already stored in Health Connect (+1). Upsert by clientRecordId is
  guaranteed on Android 14+ (platform updates in place iff `newVersion ≥ storedVersion`, see §4); the write is
  verified by reading it back, and on mismatch (or on the closed-source APK path of Android 9–13) the record is
  deleted by clientRecordId and inserted again.
- **Merged / changed fragments** give a new set of source uuids and therefore a new clientRecordId; older outputs that
  share sources are deleted (our own records only) and marked SUPERSEDED, so one night never has two outputs.
- **Deleted sources** (DeletionChange): if all sources of a night are gone, our derived record is deleted and the
  night marked SOURCE_DELETED; if some fragments remain, the night is rebuilt from them.
- **recordingMethod / device.** `Metadata.unknownRecordingMethod(clientRecordId, clientRecordVersion,
  device = Device(TYPE_PHONE, Build.MANUFACTURER, Build.MODEL))`. None of ACTIVELY_RECORDED / AUTOMATICALLY_RECORDED /
  MANUAL_ENTRY describes algorithmic inference; UNKNOWN makes no claim that a sensor measured the stages, and the
  device is the phone on which the record was produced. The source app and session uuids are in `notes`, and the
  title says "(estimate)". Full rationale in [docs/health-connect.md §4.2](docs/health-connect.md#42-metadata-recording-method-and-device).
- **Background reads.** Without `READ_HEALTH_DATA_IN_BACKGROUND` the platform silently returns only our own data in
  the background (§4). The worker therefore checks the grant and refuses to read instead of producing empty nights.
- **Read windows.** Health Connect filters `readRecords` by record START time, so heart rate / steps / SpO2 are read
  with 24 h of left padding and clipped to the window afterwards.
- **Datasets.** Primary: PhysioNet sleep-accel (Walch et al., ODC-By 1.0, open). DREAMT is *restricted* (DUA signed
  personally, "scientific research and no other") → not used. Added: PhysioNet **BIDSleep** 1.0.1 (ODC-By 1.0, open;
  47 adults, 253 nights, Apple Watch HR + accelerometer, AASM labels from a Dreem 2 EEG headband, expert-corrected) as
  extra training data and a second validation set; PSG-scored Walch remains the headline result.
- **JUnit:** Jupiter 5.14.4 (latest 5.x; the user asked for JUnit 5) via `de.mannodermaus.android-junit` 2.0.1 in
  Android modules.
- **Screenshots in docs/** are rendered on the JVM by `ScreenshotsTest` (Robolectric native graphics) from synthetic
  nights and fixed example states (`./gradlew :app:testDebugUnitTest -PrecordScreenshots`), never from real health data.
- **Default session title** is localized: the specification's Russian title on a Russian system, the English translation
  otherwise; a title typed in the settings is used verbatim on every locale.
- **Source priority also applies to staged sessions:** when a higher-priority source recorded the night with its own
  stages, the overlapping stage-less night of a lower-priority source is a duplicate and is not estimated (our earlier
  output for it is superseded); if that staged session is deleted later, the night is estimated again.
- **Settings file stores only values that differ from the defaults**, so data-driven defaults (e.g. a retrained model's
  decoder parameters) still update after the user edited some other setting; export always writes every value.
- **A deletion wins:** "Delete my sessions" / "delete this session" cancels a running backfill or recompute, and a
  backfill or recompute requested before the deletion never re-writes the deleted nights.
- **Spec vs. "prefer what wins on synthetic tests".** Where a choice can be measured on the open PSG dataset, the
  dataset decides (spec §9 forbids tuning on synthetic data); synthetic tests only guard invariants and regressions.
- **Naps and the elevated-HR baseline** (introduced as `algo.features.baselineMinSessionMin`, default 180 min, 0..720, 0
  = the old rule; now the nap threshold `algo.napMode.maxSessionMin`, see "Two processing modes" below). With
  `minSessionMin` lowered (the user estimates naps from 30 min) a daytime nap's nadir is higher than a night's: it would
  inflate the 14-night median (fewer correct "elevated" flags on real nights) and be flagged itself. Sessions shorter
  than the threshold (end − start of the merged session, as `minSessionMin` measures it) therefore (a) do not
  contribute: their `night_baselines` row is still written, but `NightBaselineDao.recentNadirs` joins `estimated_nights`
  and counts only rows whose session is long enough (the limit applies after the filter, so the baseline is the last 14
  *nights*; a changed threshold applies to the stored history at once; no Room schema change, so no migration), and (b)
  get `baselineNadirBpm = null` in `NightInputReader`, the one reader used by the pipeline, the night detail, the
  reference comparison and personalization; `NightPreparer` in `:core-algo` also ignores a baseline handed in for a nap
  (algo-cli, tests). Part of the params hash (the user has no estimated nights yet).
- **Naps and the stage-share checks** (introduced as `algo.post.proportionsMinSessionMin`, default 180 min, 0..720, 0 =
  the old rule; now `algo.napMode.maxSessionMin` with `algo.napMode.post.checkProportions` = false). The sanity check
  (DEEP 3–35 %, REM ≤ 40 %) and the optional proportion calibration describe whole nights; a 30–90-min nap often has no
  DEEP or REM, so every such nap would get `SANITY_DEEP_LOW` and a low-confidence flag. Both run only for sessions at
  least that long. It was first a separate parameter, not `baselineMinSessionMin` (it belongs to the post-processing);
  the two modes below unified the thresholds.
- **Naps and the stage correction.** The app skips sessions under its source setting `minSessionMin` (90 min); a user
  who lowers it gets naps estimated. Sessions shorter than `hybrid.shortSessionMaxMin` = 180 min (now
  `napMode.maxSessionMin`) use the bundle's
  `stageLogBiasShort` instead of the whole-night `stageLogBias` (pre-registered nap experiment, ALGORITHM.md §13.11).
  It lives in the bundle for the same reason as b (a statistic of the classifier it corrects); the threshold is an
  AlgoParams value. A bundle without `stageLogBiasShort` keeps b on short sessions. With the default `minSessionMin`
  the threshold also covers short nights of 90–180 min; lengths between 120 and 180 min were only checked post hoc
  (ALGORITHM.md §13.11, §16). The experiment ran on a build that still checked the stage shares of every session; with
  `proportionsMinSessionMin` the stages are the same (`naps-verify-merge`), but only `LOW_HR_RANGE` flags a nap, so
  33–41 % of the 30–120-min proxies are low-confidence instead of about 4 in 5.
- **A piece of a night never replaces it** (source priority). Two sessions of different apps overlapping by more than
  half of the shorter are one sleep, and the priority decides — except when one lies mostly inside the other but covers
  at most half of it: that session is a piece of the other's sleep and becomes its duplicate whatever the priority (a
  staged piece blocks nothing). With naps estimated, a 40-min fragment of a higher-priority watch would otherwise
  replace the whole night of another source (with `minSessionMin` = 90 such fragments used to be dropped as naps).
  Only a container that is kept counts (estimated, or staged and not outranked): containers are decided first, and a
  piece whose containers all lost — e.g. to another night of the piece's own app — is decided by priority like any
  night, so the stretch only it covers is still estimated. A piece of a piece is decided after its container.
  The outcome stays `LOWER_PRIORITY_DUPLICATE` (persisted enum), with its own reason text.
- **Naps: deliberately unchanged.** Fragments closer than `mergeGapMin` are merged before the nap filter, so a nap
  ending < 30 min before bedtime becomes the first fragment of the night (it cannot be told from a night split at an
  awakening; the merged nadir is the night's; `mergeGapMin` = 0 keeps them apart). Coverage gates are shares of the
  session and work the same on a 30-min nap. `LOW_HR_RANGE` stays (a nap's small P50 − P10 really makes hrNorm
  unreliable). Population EM (HMM only, not with the default HYBRID bundle) still pooled naps; since the two
  processing modes it uses nights only. Details: ALGORITHM.md §4, §8, §9.
- **Two processing modes, night and nap** (`algo.napMode`, `SessionMode`; ALGORITHM.md §18). Three thresholds of 180 min
  each separated naps from nights (`features.baselineMinSessionMin`, `hybrid.shortSessionMaxMin`,
  `post.proportionsMinSessionMin`); a session could be a nap for one rule and a night for another, and nothing else
  could differ between the two. They are replaced by one threshold `napMode.maxSessionMin` (180 min from
  `TrainedDefaults`, 0..720, 0 = no naps; strictly shorter = nap; the source setting `minSessionMin` stays the global
  minimum) and a nap may get its own estimator (`napMode.estimator`, null = the night's), output policy
  (`napMode.outputPolicy`: STAGES / SLEEP_WAKE / SKIP; default STAGES until stage 5f, SLEEP_WAKE since), post-processing
  overrides (`napMode.post`: minimum bouts, sanity corridors, `checkProportions` = false, low-confidence threshold and
  policy) and HYBRID decoder overrides (`napMode.hybrid`: duration model, emission and time-prior weights). Decisions:
  - **Behaviour is unchanged by default.** All three old thresholds defaulted to 180, so one threshold of 180 decodes
    every session as before; this was proved on synthetic sessions (`NapModeRegressionTest`, 767 digests recorded with
    the previous build) and on the real nights and nap proxies of `tools/train` (old vs new `algo-cli`, per-epoch
    output byte-identical; `train.py naps-verify --out-run naps-verify-modes`). Settings files with the old keys load
    with them ignored (no migration: no value other than the shared default was ever recommended).
  - **Overrides are nullable** (null = the night's value) rather than copies of the night's defaults, so a parameter
    patch (θ_g grid, a user setting) moves naps with nights unless a nap value is set, and the defaults need no
    duplication. The nap estimator is not nullable: HYBRID is the validated choice for naps (§13.11), and `--estimator`
    of `algo-cli` sets both.
  - **Nap model in the bundle, optional.** `HybridModel.nap` (`NapSubModel`) carries a complete second set of
    components, validated like the main ones, part of the fingerprint only when present (the shipped bundle keeps its
    fingerprint). Without it naps are decoded by the main model with `stageLogBiasShort`, exactly as before; an
    incompatible nap section sends naps (only) to the HMM fallback. Personal bias deltas are fitted on nights for the
    main model and are never applied to a nap model. `algo-cli --fold-models` takes fold bundles with their own nap
    sections, so a nap model is validated out-of-fold. A nap model is shipped since stage 5e, after it passed its own
    pre-registered protocol (`docs/prereg/prereg-nap-model.md`, ALGORITHM.md §13.12); its decoder parameters are set
    as the nap overrides (`TrainedDefaults.NAP_HYBRID_*`) and the default output policy came from the same
    pre-registration (`TrainedDefaults.NAP_OUTPUT_POLICY`) until rule (ii) of the realistic-nap pre-registration set it
    to SLEEP_WAKE (stage 5f).
  - **SLEEP_WAKE writes AWAKE / SLEEPING only** (Health Connect's generic sleeping stage for LIGHT, DEEP, REM and
    no-data spans), for readers that want naps without the unvalidated stage breakdown, and the default since stage 5f;
    the ledger row keeps the estimated stages (`sleepWakeOnly` = true) so the night detail still shows them. **SKIP** is
    checked before any data is read: the session is NOT_ESTIMATED with `NAP_SKIPPED` and an earlier output for it is
    deleted on the next run.
  - **Ledger schema 2** (`estimated_nights.mode`, `sleepWakeOnly`; migration 1 → 2 with an exported schema and a
    migration test; rows of sessions shorter than 180 min, the version-1 threshold, become naps). The notes JSON has
    `"mode"`; the night detail, reference comparison and personalization take the mode of the session; calibration
    and population EM use nights only; Compare splits kappa / accuracy by mode when the reference sessions include
    naps; Home counts written nights and naps apart; the Nights list has a nap badge and an All / Nights / Naps filter.
- **Naps are written as asleep / awake, their stages stay in the app** (stage 5f). The realistic-nap pre-registration
  fixed in advance that a nap estimator that fails the evidence check F or the realism check R gets no stage output in
  Health Connect, and SLEEP_WAKE only with a sleep / wake κ of at least 0.20; N1 failed F and R and reached 0.269. As
  that rule specifies, the app still estimates and shows the stages of a nap (ledger and night detail), labelled as
  its estimate; the documentation says they are not validated, and "Stages" stays a user choice. The default is a
  `TrainedDefaults` value like the other trained defaults, so it follows the update wherever the user never changed
  it (the settings file stores only values that differ from the defaults). The update changes the params hash of
  every estimate (new default, new field), so every night and nap becomes stale and is rewritten only when the user
  recomputes; nights are rewritten with identical stages, and nap records keep their stages until then.
- **Nap context margin** (`napMode.contextMarginMin`, null = the night's `preprocess.contextMarginMin`). Added for the
  evidence model, which is trained without heart rate around a nap. core-algo restricts a nap's input to that margin
  itself (`NightPreparer`), so the app keeps one read window (± the night's margin) and the pipeline, night detail,
  CSV export and Compare give the same estimate; validators keep it between 0 and the night's margin, so a nap never
  uses more context than is read. Nights never read it (568 / 568 nights byte-identical with null, 0 and 30). It stays
  null because no model trained without context was adopted.
- **Nap level per session length** (`nap.stageLogBiasByLength` in the bundle): a table of nap stage log-biases by
  session length with linear interpolation, exclusive with the nap section's scalar `stageLogBias` and left out of the
  canonical JSON when absent, so every existing bundle keeps its fingerprint and personal deltas keyed to the night
  model stay valid. Built for the literature-anchored candidates of stage 5f; the shipped bundle has none.
- **Refresh of written nights (ledger schema 3).** A night is estimated as soon as its sleep session appears, but heart
  rate often reaches Health Connect later (watch → OHealth → Health Connect, sometimes hours), and the Changes API
  follows `SleepSessionRecord` only. Only nights without an estimate were retried, so a night written from part of its
  heart rate (RULES at coverage 0.4–0.6, or ≥ 0.6 with heart rate missing) stayed so until the user recomputed it; a
  real short session had 46 heart-rate samples at its first estimate and 57 a day later, and its record was not
  updated. Every sync now refreshes recent written nights after the retry (`NewNightsSync.refreshWritten`,
  `NightPipeline.refresh`, `NightRun.refresh`; docs/architecture.md §3.6):
  - **What is fingerprinted.** Every write stores the fingerprint of the input it was estimated from
    (`estimated_nights.inputFingerprint`, `InputFingerprint`) and `inputCheckedAt`: for heart rate, steps, SpO2,
    respiratory rate and HRV the sample count and the SHA-256 of the samples sorted canonically (so the order in which
    Health Connect returns records with equal start times does not matter), `-` for an optional channel that was not
    read, over exactly what `NightInputReader` read (session ± `contextMarginMin`, the origins of `hrSources`). A
    refresh reads the input the same way and compares. A digest rather than the counts alone, because replaced or
    corrected samples keep the count; a digest rather than the samples, because the app stores no raw samples
    (docs/privacy.md). Not part of it: the session bounds, zone offsets and forced-awake gaps (a change of the sources
    recomputes the night anyway) and the baseline nadir (it comes from the ledger, not from Health Connect).
  - **When.** The window and back-off of the retry of nights without an estimate: nights that ended in the last 36 h
    (`RETRY_RECENT_MS`) on every run; late arrivals (first seen in the last 36 h, at most 30 days
    (`LATE_ARRIVAL_MAX_MS`) after they ended) when the time since the last write or check is at least the time between
    the first sight and that one, about 1, 2, 4, 8, 16 and 32 h after first seen with hourly syncs. Nights written or
    checked earlier in the same run are left out.
  - **At most 8 nights per run** (`MAX_REFRESH_NIGHTS_PER_RUN`): recently ended nights first, then the late arrivals,
    each group checked-longest-ago first (then newest), so the limit rotates through all due nights; the rest are logged
    and left to later runs. A checked night costs its heart-rate
    and step reads (one call per page of 1000 records: usually one each, about three with dense heart rate and the 24 h
    left padding) plus one per enabled optional channel; a rewrite adds an insert and a verification read; a refresh run
    adds one sleep-session read and, when an input changed, one read of our own sessions. Worst case 8 × (3 + 3 + 3) +
    2 + 8 = 82 read calls per run, against the background quota of 1000 per 15 min and 8000 per day (§4; 25 runs a day
    ≈ 2000). A backfill that makes many recent nights due at once is spread over several runs. A quota error stops the
    run like any other step (`QUOTA_EXCEEDED`, WorkManager retries).
  - **Never refreshed.** Nothing under `NEVER` (not even read). Stale nights (only the current `ALGO_VERSION` and params
    hash are targets; stale ones wait for an explicit recompute, as everywhere). DELETED, SUPERSEDED, SOURCE_DELETED and
    NOT_ESTIMATED rows, so no nap skipped by `SKIP`. A target whose source got its own stages or became a lower-priority
    duplicate follows the normal rules (`retireStagedOutputs`). Under `RECOMPUTE_ALL` a current target is refreshed
    instead of being recomputed on every sync.
  - **Rewrite only on a changed record.** Unchanged fingerprint: nothing is estimated or written, only
    `inputCheckedAt` moves. Changed: the night is estimated again; when it can no longer be estimated, or the session
    estimate now has fewer heart-rate samples than at the last write (a source replacing its records, data deleted on
    purpose),
    the previous output stays: an automatic update never makes a night worse, a recompute uses the data as it is. The record is rewritten only when it differs from our stored record in Health Connect in its stages
    (sorted; for a sleep / wake nap the AWAKE / SLEEPING stages as written), title or notes; otherwise the ledger takes
    the new estimate and fingerprint (for a sleep / wake nap also the estimated stages) and keeps its record id,
    version, `statusReason` and `updatedAt`, and nothing is written to Health Connect.
  - **Same record, version + 1.** A rewrite keeps the `clientRecordId` and takes the next version
    (`nextRecomputeCount`), like a recompute, so Health Connect replaces the record in place; `statusReason` records
    why (`INPUT_UPDATED: MORE_HEART_RATE 46 57 - …`, shown as "Updated automatically" in the night detail), and a
    later regular write clears it.
  - **A record deleted outside the app is never recreated.** When our stored record of a target is missing from Health
    Connect, the refresh logs a warning and writes nothing; only a recompute writes it again.
  - **Rows from before schema 3** have no fingerprint: a new estimate with the stored heart-rate sample count and the
    same coverage (three decimals) is taken as unchanged, and the fingerprint is stored from then on. Migration 2 → 3
    adds the two nullable columns without default; exported schema `3.json`, `LedgerMigrationTest`.
  - **No delay before the first estimate.** The refresh makes an early write correct itself; the heart-rate delay is
    unbounded (minutes to hours), so no fixed delay would avoid rewrites; a delay would need a new "pending" ledger
    state and leave the user without a result; the cost of an early write is one rewrite (version + 1).
  - **No setting.** The refresh rewrites only when the data changed, so it needs no switch of its own; "Existing
    sessions" = "Never overwrite" already turns it off, with every other rewrite.

## 3. Versions (verified latest stable, `gradle/libs.versions.toml`)

Gradle 9.8.1 · AGP 9.4.1 (built-in Kotlin) · Kotlin 2.4.21 · KSP 2.3.12 · Hilt 2.60.1 · androidx.hilt 1.4.0 ·
Room 2.8.5 · WorkManager 2.12.0 · DataStore 1.2.1 · Compose BOM 2026.09.00 (material3 1.4.0, adaptive 1.3.0) ·
navigation-compose 2.10.2 · activity-compose 1.13.0 · lifecycle 2.11.0 · core-ktx 1.19.1 ·
**connect-client 1.1.0** (only stable; 1.2.0-alpha06 is the newest pre-release) · kotlinx-coroutines 1.11.0 ·
kotlinx-serialization 1.11.0 · JUnit 5.14.4 · ktlint 1.8.0 via org.jlleitschuh.gradle.ktlint 14.2.0 · detekt 1.23.8 ·
on-device suite (`app/src/androidTest`, JUnit 4.13.2): androidx.test runner 1.7.0, rules 1.7.0, core 1.7.0, ext-junit
1.3.0 (verified 2026-10-14), Compose ui-test-junit4 from the BOM.

Sources: `https://dl.google.com/android/maven2/<group>/<artifact>/maven-metadata.xml`,
`https://repo1.maven.org/maven2/...`, `https://plugins.gradle.org/m2/...`, `https://services.gradle.org/versions/current`.

Compatibility notes: detekt 1.23.8 runs with its own Kotlin 2.0.21 (pinned in the `detekt` configuration) and parses
with the Kotlin 2.0 PSI — avoid syntax newer than Kotlin 2.0 (guard conditions in `when`, multi-dollar
interpolation, context parameters). AGP 9 does not use `org.jetbrains.kotlin.android`. Only `testDebugUnitTest`
exists in Android modules (AGP 9 default). WorkManager 2.12 `Configuration.Provider.workManagerConfiguration` is a
property.

## 4. Health Connect — verified facts (connect-client 1.1.0 sources + AOSP HealthFitness)

- `getSdkStatus`: on API ≥ 34 returns only SDK_AVAILABLE(3) / SDK_UNAVAILABLE(1) (profile or no service); on 28–33
  checks the `com.google.android.apps.healthdata` APK → SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED(2). Install/update
  URI `market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding`.
  `DEFAULT_PROVIDER_PACKAGE_NAME` is internal — use the literal.
  ([reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/HealthConnectClient),
  [get started](https://developer.android.com/health-and-fitness/health-connect/get-started))
- Manifest: permissions under `<manifest>`; rationale activity with `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`;
  Android 14+ `activity-alias` with `android.intent.action.VIEW_PERMISSION_USAGE` +
  `android.intent.category.HEALTH_PERMISSIONS`, `android:permission="android.permission.START_VIEW_PERMISSION_USAGE"`
  (mandatory: without it Android 14+ does not grant health permissions; Android 16 revokes them);
  `<queries><package android:name="com.google.android.apps.healthdata"/></queries>`. The AAR merges no permissions
  and no INTERNET. ([get started](https://developer.android.com/health-and-fitness/health-connect/get-started),
  [HealthPermissions](https://developer.android.com/reference/android/health/connect/HealthPermissions),
  [Android 16 changes](https://developer.android.com/about/versions/16/behavior-changes-16))
- Permissions: `android.permission.health.READ_SLEEP`, `WRITE_SLEEP`, `READ_HEART_RATE`, `READ_STEPS`,
  `READ_OXYGEN_SATURATION`, `READ_RESPIRATORY_RATE`, `READ_HEART_RATE_VARIABILITY`, `READ_HEALTH_DATA_HISTORY`,
  `READ_HEALTH_DATA_IN_BACKGROUND`; history/background are gated by `features.getFeatureStatus(FEATURE_READ_HEALTH_DATA_HISTORY = 4 /
  FEATURE_READ_HEALTH_DATA_IN_BACKGROUND = 1) == FEATURE_STATUS_AVAILABLE (2)`. Request via
  `PermissionController.createRequestPermissionResultContract()`.
  ([data types](https://developer.android.com/health-and-fitness/health-connect/data-types),
  [read data](https://developer.android.com/health-and-fitness/health-connect/read-data))
- History: without READ_HEALTH_DATA_HISTORY reads silently omit data older than 30 days before the first grant
  (single `readRecord` errors); own data has no limit on Android 14+ when filtered by our package.
- Reads: `ReadRecordsRequest(recordType, timeRangeFilter, dataOriginFilter, ascendingOrder, pageSize = 1000, pageToken)`;
  platform page size 1..5000; loop `while (!pageToken.isNullOrEmpty())` (APK path returns `""`, platform `null`;
  sending `""` throws NumberFormatException). **Filtering is by record start time**, not overlap (AOSP RecordHelper,
  android14…android17).
- Background: without READ_HEALTH_DATA_IN_BACKGROUND, `readRecords` in background returns only own records (no
  exception); `getChanges` throws SecurityException. (AOSP `HealthConnectServiceImpl.shouldEnforceSelfRead`)
- Writes: `Metadata` constructor is internal; factories `autoRecorded/activelyRecorded(device, clientRecordId, version)`,
  `manualEntry/unknownRecordingMethod(clientRecordId, version, device?)`. `SleepSessionRecord(startTime, startZoneOffset,
  endTime, endZoneOffset, metadata, title, notes, stages)`; stages non-overlapping, inside the session, gaps allowed;
  STAGE_TYPE_UNKNOWN 0, AWAKE 1, SLEEPING 2, OUT_OF_BED 3, LIGHT 4, DEEP 5, REM 6, AWAKE_IN_BED 7. Start time must not be
  in the future. Always set zone offsets explicitly. ≤ 1000 records per insert.
  ([Metadata](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/metadata/Metadata),
  [SleepSessionRecord](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/SleepSessionRecord),
  [write data](https://developer.android.com/health-and-fitness/health-connect/write-data))
- **Upsert:** with a clientRecordId the platform derives `uuid = nameUUIDFromBytes(package + recordType + clientRecordId)`;
  a second insert updates the row in place iff `newVersion ≥ storedVersion` (equal overwrites), otherwise it is
  silently ignored (AOSP `RecordHelper.getUpsertTableRequest`, `TransactionManager.insertOrReplaceOnConflict`,
  identical in android14…android17). Docs only promise "higher version wins"
  ([sync data](https://developer.android.com/health-and-fitness/health-connect/sync-data)).
- Delete: `deleteRecords(SleepSessionRecord::class, recordIdsList, clientRecordIdsList)`; only own records; the
  platform ignores unknown ids (KDoc claims IPC failure); both lists empty → IAE.
- Changes API: `getChangesToken(ChangesTokenRequest(setOf(SleepSessionRecord::class), setOf(DataOrigin(pkg))))`,
  `getChanges(token)` → `changes` (UpsertionChange / DeletionChange(id only)), `nextChangesToken`, `hasMore`,
  `changesTokenExpired`; tokens valid 30 days (platform purges after 32); expired/invalid token → `changesTokenExpired = true`.
- Errors on API ≥ 34: SecurityException (permission), IllegalStateException with cause `HealthConnectException`
  errorCode 7 (RATE_LIMIT_EXCEEDED) / 8 (DATA_SYNC_IN_PROGRESS) → back off; RemoteException/IOException transient;
  IllegalArgumentException invalid. APK path: rate limit is indistinguishable (RemoteException).
  Platform quota defaults (AOSP `RateLimiter`): reads 2000/15 min & 16000/day foreground, 1000/15 min & 8000/day
  background; writes 1000/15 min & 8000/day. ([rate limiting](https://developer.android.com/health-and-fitness/health-connect/rate-limiting))
- Sleep priority: Health Connect dedupes sleep only in `aggregate()` by the user's "Data sources and priority" list;
  `readRecords` returns every app's overlapping sessions. ([aggregate data](https://developer.android.com/health-and-fitness/health-connect/aggregate-data))
- Sleep as Android imports third-party sleep from Health Connect ("Settings → Services → Health Connect", beta);
  duplicate handling for two overlapping HC sources is not documented → to be checked on the device.
  ([SaA docs](https://sleep.urbandroid.org/docs/services/health_connect.html))

Doc inconsistencies found (code follows the sources): the sleep-guide sample uses the internal `Metadata(...)`
constructor; pagination samples loop on `!= null`; some pages put `<uses-permission>` inside `<application>`.

## 5. Datasets (verified)

| Dataset | License / access | Use |
|---|---|---|
| [sleep-accel 1.0.0](https://physionet.org/content/sleep-accel/1.0.0/) (Walch 2019; 31 subjects, Apple Watch HR ~5 s, accel ~50 Hz, PSG AASM 30-s; codes −1 unscored, 0 W, 1 N1, 2 N2, 3 N3, 4 N4, 5 REM) | ODC-By 1.0, open | primary training + LOSO validation |
| [BIDSleep 1.0.1](https://physionet.org/content/bidsleep-dataset/) (47 subjects, 253 nights, Apple Watch HR + accel, Dreem 2 EEG, expert-corrected AASM) | ODC-By 1.0, open | extra training data, second validation set |
| [DREAMT 2.2.0](https://physionet.org/content/dreamt/) | restricted (DUA, research only) | **not used** |
| HMC, CAP, UCDDB, SLPDB (ECG + PSG), NSRR MESA/SHHS (DAUA) | open / DAUA | not used now (ECG-derived HR, clinical cohorts); noted as future work |
| [OpenNeuro ds006576 1.0.5](https://doi.org/10.18112/openneuro.ds006576.v1.0.5) (McDevitt et al. 2026; afternoon naps with ECG + EEG; 21 NREM-nap, 21 REM-nap and 23 quiet-wake recordings, group labels only) | CC0, open | **test only** (rule (iii) of the realistic-nap experiment, stage 5f); never for training, any parameter or a choice between models |
| Open nap hypnograms: Zenodo 6642860 (Nicolas et al. 2022), OSF chav7 (Mei et al. 2018), Sleep-EDF Expanded SC daytime bouts | CC BY 4.0; BSD-3-Clause; ODC-By 1.0 | composition only, no heart rate: false-flag rates of the nap guards and genuine-nap reference rates (aggregate counts in `pulsetrain/litprior.py`) |
| Bergamo et al. 2026 OSF deposit (individual naps) | no licence | **not used** (only the published paper's group means) |

Published expectations (Walch 2019 Table 6): 3-class kappa with HR only 0.04–0.17, motion + HR + clock ≈ 0.30.
4-class kappa ≈ 0.2–0.3 is a realistic target for 2-minute HR without an accelerometer; > 0.5 would indicate leakage.
Attribution notice for produced works (ODC-By §4.3) is in README and in the app (Settings → "Versions and licences").

## 6. Open questions (none blocking)

- Sleep as Android behaviour when OHealth's and our session overlap — verify on the device after install.
- Whether OHealth writes SpO2 / respiratory rate at night — the app works with or without them.
- OHealth HeartRateRecord granularity (one sample per record or series) — handled either way by the 24 h left
  padding.
- How OHealth delimits a nap session (start and end of a daytime nap) is undocumented; nap estimates depend on it.
- No open dataset has labelled naps with heart rate, and none has labelled heart-rate sessions at daytime clock times;
  a nap stage model that could be written by default needs such data (ALGORITHM.md §13.13).
