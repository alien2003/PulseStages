# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Release tags are `vMAJOR.MINOR.PATCH`, and
`vMAJOR.MINOR.PATCH-alpha.N`, `-beta.N` or `-rc.N` for pre-releases. The release workflow derives versionName and
versionCode from the tag and publishes the tag's section of this file as the release notes, so links inside a version
section are absolute ([docs/development.md](docs/development.md#releases)).

## [Unreleased]

### Added

- **Install and update with Obtainium**: a "Get it on Obtainium" button in the README and the user guide adds
  PulseStages to [Obtainium](https://github.com/ImranR98/Obtainium) with one tap and a confirmation; Obtainium then
  installs it and every later release as an update. The import takes final releases only, selects
  `PulseStages-<version>.apk` with the APK filter `^PulseStages-[0-9.]+\.apk$` (never the `-debug` APK) and expects
  the release signing certificate. The app itself still has no update check and no internet access
  ([user guide](https://github.com/alien2003/PulseStages/blob/main/docs/user-guide.md#install-and-update-with-obtainium)).
  The release notes link to that section.
- **Recent nights and naps are updated automatically when more heart rate arrives.** Heart rate often reaches Health
  Connect hours after the sleep session, and a night written from part of it (for example with the rules estimator at
  40–60 % coverage) stayed so until it was recomputed by hand. Every sync now also reads the heart rate, steps and
  optional channels of recent written nights and naps again and compares them with a fingerprint stored at the write:
  on the schedule of the retry of nights without an estimate (every run for 36 hours after the session ended, growing
  gaps for late arrivals), at most 8 per run. When the input changed, the night is estimated again, and its session is
  rewritten only when it would change (stages, title or notes), under the same record key with the next version. Never
  under "Never overwrite"; stale nights are left to "Recompute stale nights"; a session deleted outside the app is not
  written again; fewer heart-rate samples than at the write never make a session worse. The night detail shows
  "Updated automatically" with the reason (for example "more heart rate arrived (46 → 57 samples)"), "Estimate new"
  adds "… nights updated with late data.", and the log has a line per update
  ([user guide](docs/user-guide.md#automatic-runs),
  [architecture.md §3.6](docs/architecture.md#36-refreshing-written-nights), [PLAN.md §2](PLAN.md#2-decisions)).
- **Ledger schema 3**: `estimated_nights.inputFingerprint` and `inputCheckedAt`, with the migration 2 → 3 and the
  exported schema `3.json`. The fingerprint holds per channel the number of samples and a SHA-256 digest of them, never
  the samples; rows written before have none and are compared by their heart-rate sample count and coverage until
  their first check ([privacy.md](docs/privacy.md#3-what-is-stored-on-the-device),
  [architecture.md §4.2](docs/architecture.md#42-schema-versions-and-migrations)).
- **On-device suite**: the shadow pipeline's `refresh-recent` scenario runs the refresh over the real recent nights
  with nothing written to Health Connect ([tools/device-test/README.md](tools/device-test/README.md)).

## [0.1.1] - 2026-10-15

Naps are written to Health Connect as asleep / awake by default. A pre-registered experiment tried to make the
stage breakdown of naps realistic and found no nap stage model that passed its checks (per-epoch discrimination,
following the heart rate across sleep regimes, realism against published compositions of afternoon naps), so the
nap model stays and its stages, still shown in the app, are not validated. Nights are unchanged. Details in
[ALGORITHM.md §13.13](https://github.com/alien2003/PulseStages/blob/v0.1.1/ALGORITHM.md#1313-realistic-naps-pre-registered-not-adopted)
and the [user guide](https://github.com/alien2003/PulseStages/blob/v0.1.1/docs/user-guide.md#updating-from-010).

### Added

- **On-device end-to-end suite** (`app/src/androidTest`, `tools/device-test/run-device-tests.sh`). Runs against the
  debug build installed on a phone and its real Health Connect data without reinstalling the app: snapshots of
  everything the suite could touch before and after the run (the app's own records must stay byte-identical, other
  apps' records unchanged); the backfill, sync and recompute pipeline over the real history with every write captured
  in memory, with invariant, idempotency, recompute and policy checks and a per-session report; one test record in the
  `ps-e2e:` namespace that is upserted, versioned and deleted within a minute; a read-only tour of every screen with
  screenshots, whose clicks an allow-list restricts to what writes nothing; the app's job schedule before and after. A
  safety guard refuses every write or delete of the suite outside `ps-e2e:`, an in-memory WorkManager keeps the app's
  own background work out of the test processes, the read budget keeps the suite within Health Connect's background
  quota across runs, and a cleanup step that always runs removes every test record (first by the ids recorded before
  the write, without a read) and puts back the demo-mode flag
  ([tools/device-test/README.md](https://github.com/alien2003/PulseStages/blob/v0.1.1/tools/device-test/README.md)).
- **Context margin of naps** (Settings → "Naps: algorithm" → "Heart rate around naps" → "Context margin of naps",
  `algo.napMode.contextMarginMin`): how many minutes of heart rate and steps (and of the optional channels) before and
  after a nap its estimate uses. Empty, the default, is the night's "Context margin" (30 min), as before; 0 is the nap
  itself only; at most the night's value, which sets how much is read from Health Connect. Nap-only: nights never read
  it. Checked by the core-algo and settings validators and by the import; English and Russian texts
  ([settings reference](https://github.com/alien2003/PulseStages/blob/v0.1.1/docs/settings-reference.md#naps-algorithm)).
- **Nap stage bias per session length in the model bundle**: an optional `nap.stageLogBiasByLength` table (lengths
  and one log-bias row per length, linear interpolation in the session length, the first or last row outside;
  exclusive with the nap section's `stageLogBias`). It is left out of the canonical JSON when absent, so existing
  bundles keep their fingerprints and personal models keyed to them stay valid. The shipped bundle does not use it
  ([docs/INTERCHANGE.md](https://github.com/alien2003/PulseStages/blob/v0.1.1/docs/INTERCHANGE.md)).
- **Realistic-nap experiment** (pre-registered:
  [docs/prereg/prereg-naps-realistic.md](https://github.com/alien2003/PulseStages/blob/v0.1.1/docs/prereg/prereg-naps-realistic.md)
  with its Amendment 1, digest and command log;
  [ALGORITHM.md §13.13](https://github.com/alien2003/PulseStages/blob/v0.1.1/ALGORITHM.md#1313-realistic-naps-pre-registered-not-adopted)):
  `train.py naps-realistic-gates`, `naps-realistic-timing`, `naps-realistic`, `naps-realistic-report`,
  `naps-realistic-final`, `naps-realistic-external`, `naps-realistic-install` and `naps-realistic-verify`, with
  `pulsetrain/napsrealistic.py`, `pools.py` (training tiles, evaluation pools, featureless sessions), `litprior.py` (the
  published nap compositions: arm table, inclusion rule, targets, bands and guards), `realism.py` (per-epoch AUC, regime
  contrasts, realism metrics, the decision rules) and `perturb.py`; sample weights in `models.fit_classifier`, a
  per-length bias fit (`bias.fit_stage_bias_table`); `algo-cli evaluate` shares a preparation only between grid points
  with the same mode and effective preprocessing, so a grid over the nap context margin prepares only the naps again
  ([tools/train/README.md](https://github.com/alien2003/PulseStages/blob/v0.1.1/tools/train/README.md)).
- **Test-only pipeline for OpenNeuro ds006576** (real afternoon naps with ECG and EEG, CC0): `tools/train/external.py`
  (snapshot pin against the committed key list `external-ds006576-v1.0.5.keys.tsv`, a streamed fetch that keeps only
  reduced signals, ECG → heart rate, test windows, night files, the digests that freeze the test set) and
  `tools/train/external_stage.py` (YASA 0.7.0 sleep staging, which only places the test windows, in its own environment
  `tools/train/.venv-external` pinned with hashes by `requirements-external.lock`, OpenMP runtime included). The data
  are never used for training, for any parameter or for choosing between models, and are never committed
  ([tools/train/DATASETS.md](https://github.com/alien2003/PulseStages/blob/v0.1.1/tools/train/DATASETS.md)).
- Tests: the nap input restriction and preparation key, the length-bias table and its validation, the shipped bundle's
  fingerprints pinned, and a personalized-nights test showing that personal bias deltas still apply after a bundle
  update that keeps the night model; the training tests run on synthetic data only.

### Changed

- **Naps are written to Health Connect as asleep / awake by default.** "Written for a nap" (`algo.napMode.outputPolicy`)
  now defaults to "Asleep / awake" (`SLEEP_WAKE`) instead of "Stages", because no nap stage model passed the
  pre-registered realistic-nap experiment
  ([ALGORITHM.md §13.13](https://github.com/alien2003/PulseStages/blob/v0.1.1/ALGORITHM.md#1313-realistic-naps-pre-registered-not-adopted)).
  Rule (i) adopted no candidate in either tier, so the shipped nap model (N1) and the bundle stay unchanged. Rule (ii)
  gives N1 the asleep / awake output: it fails the evidence check F and the realism check R, and on the
  literature-reweighted pool its accuracy is 0.055 below calling every epoch light sleep (at least +0.03 was required
  for stages; 4-class κ 0.246), while its sleep / wake κ is 0.269 (at least 0.20 required). Failing criteria (Walch,
  held-out subjects, worse sampling variant):
  - N1: F, REM contrast r = 0.012 (the predicted late-night minus sleep-onset difference of the REM share of sleep, as a
    fraction of the true difference; ≥ 0.25 required), the lower bound of its 95 % bootstrap interval −3.1 pp and its
    BIDSleep difference −1.8 pp (> 0 required); R, featureless sessions of 90–135 min with 3.5–5.2 % REM of sleep, below
    the lower bound of the published range (5.1 % at 90–105 min, 5.7 % at 120–135 min), and a late-night deep-sleep tail
    of 0.226 (the share of 60-min windows from late in the night decoded as at least
    half deep sleep, minus the true share; ≤ 0.10).
  - C0 (N1's classifier with a literature-anchored level per length): F, REM r = 0.122, lower bound −3.4 pp, BIDSleep
    −1.6 pp; R, featureless REM above the range at 30–75 min and deep sleep below it at 105 and 135–170 min (mean2min),
    late-night deep-sleep tail 0.104.
  - C1 (a heart-rate-only evidence model with a night-level bias): D, sleep-onset deep-sleep AUC 0.066 below N1's (at
    most 0.05 allowed); R, featureless REM above and deep sleep below the range, guard excess 0.170 (implausible
    single-nap compositions beyond those of the true compositions; ≤ 0.05), mid-anchor excess 2.46 pp (distance from the
    published composition between the anchor lengths minus at them; ≤ 2.0), distance from the published composition
    14.31 pp, above N1's 12.66.
  - C2 (the evidence model with a literature-anchored level per length): D, sleep-onset deep-sleep AUC 0.057 below
    N1's; F, REM r = 0.070, lower bound −6.3 pp; R, featureless deep sleep below the range at 75–170 min, late-night
    deep-sleep tail 0.132, guard excess 0.104, mid-anchor excess 2.10 pp.

  The test on real afternoon naps (OpenNeuro ds006576) changed nothing: N1 stays in effect with the asleep / awake
  output. The app still estimates the stages of a nap and shows them as its own estimate; they are not validated, and
  "Stages" writes them anyway. Settings store only changed values, so the new default applies wherever the setting was
  left at "Stages" (an explicit "Stages" of 0.1.0 included, since it was the default). Nap sessions already in Health
  Connect keep their stages until they are recomputed: the new default and the new setting change the params hash, so
  every estimate is offered for recomputation, and nights decode exactly as before (568 of 568 validation nights, per
  epoch) ([user guide](https://github.com/alien2003/PulseStages/blob/v0.1.1/docs/user-guide.md#updating-from-010)).
- **Nap caution in the night detail**: an estimated nap now says "Nap stages are not validated: the nap model failed
  pre-registered checks, and on test sessions with the stage mix of published naps it was less accurate than calling
  everything light sleep." (other estimators: "Nap stages are not validated; take them as the app's estimate only.")
  instead of "Nap estimates are rough: … about 6 in 10 …", and the hint of "Written for a nap" says that nap stages are
  not validated instead of "rough"; English and Russian.
- **Documentation of naps**: the README, the user guide and PLAN.md describe the asleep / awake default and say that the
  nap stages shown in the app are not validated; the earlier figure of about 6 in 10 nap epochs right
  ([ALGORITHM.md §13.12](https://github.com/alien2003/PulseStages/blob/v0.1.1/ALGORITHM.md#1312-a-dedicated-nap-model-pre-registered-adopted))
  is no longer given as the accuracy of naps, because it came almost entirely from windows at sleep onset.
  tools/train/README.md and DATASETS.md cover the new experiment, ds006576 and the open nap hypnograms used for the
  guards.

## [0.1.0] - 2026-10-14

First release. PulseStages estimates sleep stages (awake, light, deep, REM) from the heart rate that OHealth (OPPO
Watch) writes to Health Connect, and writes them back to Health Connect as a separate sleep session marked as an
estimate. The stages are estimated, not measured: on the open validation data, agreement with the sleep lab is fair
for nights (Cohen's κ about 0.32–0.34) and rough for naps (about 6 in 10 epochs right); see
[ALGORITHM.md §13](https://github.com/alien2003/PulseStages/blob/v0.1.0/ALGORITHM.md#13-results) and the
[limitations](https://github.com/alien2003/PulseStages/blob/v0.1.0/ALGORITHM.md#16-limitations). Start with the
[user guide](https://github.com/alien2003/PulseStages/blob/v0.1.0/docs/user-guide.md).

### Added

#### App

- **Screens.** Compose Material 3 UI with four tabs: Home, Nights, Compare and Settings; Sources and Log open from
  Settings. Dynamic colour on Android 12+, light and dark theme, edge-to-edge. On wide screens (the unfolded
  foldable), Nights shows the list and the detail side by side and Settings uses two columns. The UI is in English and
  Russian; choices whose labels do not fit side by side are shown as radio buttons.
- **Home**: Health Connect status (with install / update on Android 9–13); required and optional permissions, with
  separate indicators for history access, background access and notifications; enabled sources, last and next run;
  counters of found, estimated, skipped, stale and failed sessions, and of nights and naps written. Buttons "Estimate
  new", "Backfill history" (progress, cancel, resume), "Recompute all", "Recompute stale nights" and "Delete my
  sessions from Health Connect".
- **Nights**: the list shows duration, stage shares, confidence and coverage, with "Stale", "Elevated heart rate",
  "Low confidence" and "Nap" markers, and a filter All / Nights / Naps. The night detail shows a hypnogram, the
  heart-rate curve, per-epoch probabilities and features, the warnings, and whether the session was processed as a
  night or a nap. Actions: "Recompute", "Compare with RULES", "Export CSV" and "Delete my session".
- **Compare** scores the estimates against sessions with real stages from other apps: kappa, accuracy, macro F1,
  per-stage metrics, a confusion matrix, per-night scores, and nights and naps apart. "Calibrate" fits a personal model
  on these nights (kappa before and after on held-out nights); "Reset to global model" removes it.
- **Settings** has every parameter in groups, each with a hint, JSON export and import with validation, reset to
  defaults, reset of the HMM emissions to the physiology table, and an offer to recompute the sessions a change made
  stale. The last card, "Versions and licences", shows the app, algorithm and model versions, the licence and the
  ODC-By attribution notice of the training datasets.
- **Sources** lists the apps that wrote sleep sessions to Health Connect (icon, name, session count, last session),
  with an enable switch and a priority order for each; packages can be added by name, and a switch takes heart rate
  and steps from any app.
- **Log** with a filter by level and sharing as a text file.
- **Privacy**: a permission rationale and privacy screen, reachable from Home and from Health Connect
  (`ACTION_SHOW_PERMISSIONS_RATIONALE`, and on Android 14+ the `VIEW_PERMISSION_USAGE` activity-alias). The app has no
  `INTERNET` permission.
- **Icon**: adaptive vector launcher icon with a monochrome layer, and `docs/icon.svg`.
- **Demo mode** (debug builds only): Settings → "Debug build" → "Demo mode" switches the Nights list, the night detail
  and Compare to synthetic nights and naps, for screenshots. The release build contains none of its code or text.

#### Nights and naps

- Every session is processed as a **night** or a **nap**. Settings → **Naps** → "Nap threshold" (180 min; 0 = no
  naps) decides which; sessions shorter than the source setting "Minimum session length" (90 min) are ignored.
- "Written for a nap": **Stages** (the default), **Asleep / awake** (only AWAKE and SLEEPING are written to Health
  Connect) or **Nothing** (not estimated and not written; "Recompute stale nights" writes them after switching back).
  "Estimator for naps" defaults to "Same as nights". The nap values of the algorithm are in the collapsed group
  "Naps: algorithm" (empty = the value of nights).
- Naps never enter the elevated-heart-rate baseline and are never compared with it. The stage-share sanity check and
  the proportion calibration skip naps unless "Check the stage shares of naps" is on. Calibration on reference
  nights and the population EM use nights only.
- The bundled model has a dedicated nap model, chosen by a pre-registered leave-one-subject-out test on nap-length
  pieces of the validation nights
  ([ALGORITHM.md §13.12](https://github.com/alien2003/PulseStages/blob/v0.1.0/ALGORITHM.md#1312-a-dedicated-nap-model-pre-registered-adopted)).
  The night detail of a written nap says that its stages are rough.

#### Health Connect and storage

- **Repository** over connect-client 1.1.0: paginated reads; upsert by `clientRecordId`, verified by reading the
  record back, with delete-and-insert as the fallback; deletes of the app's own records only; Changes API; scan of
  sleep origins; error mapping with backoff on rate limits and transient errors.
- **Session pipeline**:
  - source allowlist (default OHealth); the app's own sessions and sessions that already have stages are skipped;
  - fragments closer than "Merge gap" (30 min) are merged, with the gaps written as AWAKE;
  - priority between sources, also against a higher-priority source that recorded the night with its own stages; a
    session that lies mostly inside a longer session of another app and covers at most half of it is a piece of that
    night and does not replace it;
  - deterministic `clientRecordId` (`ps:` + SHA-256 of the sorted source uuids) and `clientRecordVersion` =
    `ALGO_VERSION · 1000 + recompute count`;
  - replaced outputs are superseded, deleted sources are handled, records left from before a reinstall are
    de-duplicated, and a failure on one session does not stop the others;
  - overwrite policies "Skip up-to-date nights", "Recompute everything" and "Never overwrite";
  - the notes of every written session are JSON: estimator, mode (`NIGHT` / `NAP`), algorithm version, params hash,
    coverage, confidence, source session uuids, heart-rate sample count and whether the stage proportions were
    calibrated; the CSV export has a last column `mode`.
- **Sync and backfill**: Changes-API sync that falls back to re-reading the last 7 days; history backfill in 7-day
  windows that resumes after a restart; a guard that refuses background reads without
  `READ_HEALTH_DATA_IN_BACKGROUND`; deletion of all PulseStages sessions or of one session. Deleting cancels a running
  backfill and recompute, and neither writes a deleted session again.
- **Room ledger** `estimated_nights`, `night_sources`, `source_sessions`, `night_baselines`, `log_entries`,
  `backfill_state`, `personal_models` (schema version 2, with exported schemas).
- **DataStore** settings (JSON export, import and reset; the file stores only changed values, so trained defaults
  follow app updates) and the changes token.
- **Internal log**, mirrored to logcat with the tag `PulseStages`.
- Data of development builds made before 0.1.0: the ledger migrates from schema version 1 and keeps every row, and a settings file
  exported by such a build loads with the retired keys `features.baselineMinSessionMin`, `hybrid.shortSessionMaxMin`
  and `post.proportionsMinSessionMin` ignored (replaced by `napMode.maxSessionMin`).

#### Background work (`:work`)

- `EstimateNewNightsWorker`: an hourly Changes-API check, a daily run at a configurable time (default 09:00), and
  "Estimate new" as expedited work.
- `BackfillWorker` with progress, cancel and resume, and `RecomputeWorker`.
- The hourly and daily works wait for a battery that is not low (configurable); all works use exponential backoff,
  and the runs the user starts show a `dataSync` foreground notification.

#### Algorithm (`:core-algo`, `:algo-cli`)

- **Estimators** behind `StageEstimator`, `ALGO_VERSION` 1: RULES (thresholds); HMM (Viterbi and forward-backward,
  time-of-night priors, REM lockout, ultradian-cycle prior, per-night EM); HYBRID, the default (MLP emissions on
  multi-scale features, decoded by an explicit-duration HSMM or a geometric HMM).
- **Bundled model** `pulsestages-walch+bidsleep-mlp-nap-model-final` in `app/src/main/assets/models/hybrid_model.json`:
  the night model with a per-stage log-bias that matches the stage shares to the sleep lab (pre-registered,
  [ALGORITHM.md §13.10](https://github.com/alien2003/PulseStages/blob/v0.1.0/ALGORITHM.md#1310-stage-proportions-prior-exponent-and-proportion-matching-pre-registered-v3-adopted)),
  and the nap model above.
- **Processing**: preprocessing (range filter, Hampel filter, bounded interpolation, smoothing, coverage), shared
  post-processing, confidence and an elevated-heart-rate baseline; personalization by calibration on reference nights
  and population EM.
- **Tooling**: metrics (kappa, accuracy, F1, confusion matrix, minute errors), a synthetic night generator, the params
  hash, CSV export, and `algo-cli` with `features`, `evaluate` (metrics by mode, nap proxies, fold bundles with nap
  sections) and `estimate`.

#### Training and validation (`tools/train`)

- Licence-checked download of PhysioNet sleep-accel 1.0.0 and BIDSleep 1.0.1 (both ODC-By 1.0). DREAMT is not used,
  because its access is restricted.
- OHealth emulation: one heart-rate value per 2 minutes (instantaneous and 2-minute mean), steps from the
  accelerometer, 4-class labels.
- Leave-one-subject-out validation with nested selection, computed through `algo-cli`; the generated
  `TrainedDefaults.kt`; the pre-registrations of the stage-proportion, nap and nap-model experiments, committed under
  `docs/prereg/` with their SHA-256 files and command logs and checked by the Python tests.
- pytest suite on synthetic data.

#### Build, CI and documentation

- **Toolchain**: Gradle 9.8.1 wrapper, AGP 9.4.1, Kotlin 2.4.21; compileSdk 37, targetSdk 36, minSdk 28; versions in
  `gradle/libs.versions.toml`; a project-local toolchain through `.mise.toml` and `.tooling/`.
- **Quality checks**: ktlint, detekt (KDoc required on the public API of `:core-algo`) and JUnit 5 tests; the
  module-graph check, an Android-free runtime classpath and no Android imports in `:core-algo`; merged-manifest tests
  (no `INTERNET`); a check that the release APK contains no demo code or text; English / Russian string parity and
  valid format patterns in every module; Robolectric tests of the production Hilt / WorkManager graph and of the UI
  wiring with a fake Health Connect; render tests of every screen and every Settings group in English and Russian, at
  font scale 2 and in a wide window; the ledger migration against the exported schemas; and a regression test that
  compares 767 estimates with those recorded before the night and nap modes.
- **GitHub Actions**: CI runs ktlint, detekt, the tests, `assembleDebug` and the Python tests on pushes, pull requests
  and manual runs, skips documentation-only changes, and keeps the debug APK of pushes to `main` for 2 days. The
  release workflow builds the signed release APK and the debug APK for `v*` tags (and `-alpha.N`, `-beta.N`, `-rc.N`
  pre-release tags), verifies both and publishes them with SHA-256 checksums and the tag's changelog section; a manual
  dry run checks the signing secrets without publishing. Every action is pinned to a commit SHA, and Dependabot keeps
  the pins current.
- **Documentation**: README, the user guide, the settings reference (generated from the code; a test fails when it
  is out of date), architecture, Health Connect, privacy, development, CONTRIBUTING, SECURITY, issue and pull request
  templates, ALGORITHM.md, PLAN.md, docs/INTERCHANGE.md, docs/SCREENSHOTS.md, and tools/train/README.md and
  DATASETS.md. Screenshots in `docs/screenshots/`, including the two-pane Nights layout and the Settings group "Naps",
  are rendered on the JVM from synthetic data by `ScreenshotsTest`. The release notes give the SHA-256 of the release
  and of the debug signing certificate.
- **Licence**: WTFPL.

[Unreleased]: https://github.com/alien2003/PulseStages/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/alien2003/PulseStages/releases/tag/v0.1.1
[0.1.0]: https://github.com/alien2003/PulseStages/releases/tag/v0.1.0
