# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Release tags are `vMAJOR.MINOR.PATCH`, and
`vMAJOR.MINOR.PATCH-alpha.N`, `-beta.N` or `-rc.N` for pre-releases. The release workflow derives versionName and
versionCode from the tag and takes the release notes from the tag's section of this file (see README, "GitHub: CI and
releases").

## [Unreleased]

### Changed

- **Demo mode** (debug builds only) is a switch in Settings → "Debug build" instead of a second launcher icon
  ("PulseStages demo"). The Nights list, the night detail and Compare follow it at once, without a restart
  (background estimation and the Home actions keep working with the real data); every build has a single launcher
  icon. The switch is a per-device debug convenience in its own preferences file, not a setting (not exported, not
  part of the params hash). `ReleaseApkTest` checks that the minified release APK has no demo class, resource or text.
- **Naps and the elevated-heart-rate baseline**: sessions shorter than `baselineMinSessionMin` (new, Settings →
  Features, 180 min; 0 = as before) no longer enter the rolling nadir baseline and are never compared with it, so
  estimated daytime naps neither inflate the baseline of the nights nor get flagged "elevated heart rate" themselves.
  Their nadir is still stored; the baseline query counts only long enough sessions, so a changed threshold applies to
  the whole history.
- **Naps and the stage-share checks**: the sanity check and the proportion calibration run only for sessions of at
  least `proportionsMinSessionMin` (new, Settings → Post-processing, 180 min; 0 = as before); naps no longer get
  `SANITY_DEEP_LOW` and a low-confidence flag for having no deep sleep.
- **Naps get their own stage correction.** Sessions shorter than 180 min (`hybrid.shortSessionMaxMin`, new setting
  "Short-session (nap) threshold"; 0 = off) are decoded with the bundle's new short-session log-bias
  `stageLogBiasShort` instead of the whole-night `stageLogBias`. Chosen by a pre-registered evaluation on nap-length
  segments of the validation nights (ALGORITHM.md §13.11): accuracy 0.41 → 0.49, summed stage-share error 93 → 72
  points, most of the false REM in naps removed. Sessions of 180 min or more are decoded exactly as before; with the
  default "Minimum session length" (90 min), short nights of 90–180 min also get the short-session correction. The
  pre-registered evaluation covered sessions up to 120 min; a post-hoc check at 150 and 170 min found no clear gain
  (share error better on one sampling variant, worse on the other; too little REM). Nap estimates remain rough
  (README, "Naps"). In the evaluation about 4 in 5 naps were flagged low-confidence, mostly by the stage-share sanity
  check; since that check now skips sessions shorter than 180 min (above), only a too-small heart-rate range flags a
  nap (about 1 in 3 to 2 in 5 of the evaluated 30–120-min naps; the stages are the same). New bundle
  `pulsestages-walch+bidsleep-mlp-naps-final` (same classifier); because the bundle and AlgoParams changed, earlier
  estimates get a new params hash and are offered for recomputation, and the classifier part of a personal
  calibration fitted for the previous bundle is ignored until you calibrate again.
- **Source priority**: a session lying mostly inside a longer session of another app while covering at most half of
  it (e.g. a 40-min fragment) is a piece of that night and no longer replaces it, whatever the priority — as long as
  that longer session is used itself; a piece of a session that lost to another night is weighed by priority as before.
- `baselineMinSessionMin` and `proportionsMinSessionMin` are part of the params hash: nights estimated before are
  offered for recomputation.

### Added

- Render tests of every Settings group (every field, option, the HMM tables and the About card) and of every other
  screen in its main states, in English and Russian, at font scale 2 and in a wide window; a test that resolves every
  text of the settings catalog; a scan of every string with `%` for invalid format patterns.

### Fixed

- **Settings**: opening the "Features" group crashed the app with `UnknownFormatConversionException`. Its percent
  fields have the unit "%" (`formatted="false"`), and a text without arguments was still resolved with
  `Resources.getString(id, *emptyArray())`, which runs `String.format`. Texts without arguments are now read as they
  are.

## [0.1.0] - 2026-10-13

First release.

### Added

#### App (`:app`)

- **Navigation and layout**
  - Compose Material 3 UI with four tabs: Home, Nights, Compare and Settings. Sources and Log open from Settings.
  - Dynamic colour on Android 12+, light and dark theme, edge-to-edge.
  - On wide screens (the unfolded foldable), Nights shows list and detail side by side, and Settings uses two columns.
- **Home**
  - Health Connect status, with install / update on Android 9–13.
  - Required and optional permissions, with separate indicators for history access, background access and
    notifications.
  - Enabled sources, last and next run, and counters of found, estimated, skipped, stale and failed nights.
  - Buttons: "Estimate new", "Backfill history" (progress, cancel, resume), "Recompute all", "Recompute stale nights"
    and "Delete my sessions from Health Connect".
- **Nights**
  - The list shows duration, stage shares, confidence and coverage, with "Stale", "Elevated heart rate" and "Low
    confidence" markers.
  - The night detail shows a Canvas hypnogram, the heart-rate curve, per-epoch probabilities and features, and the
    warnings.
  - Night actions: "Recompute", "Compare with RULES", "Export CSV" and "Delete my session".
- **Compare** works with reference nights that have real stages from other apps. It shows kappa, accuracy, macro F1,
  per-stage metrics, a confusion matrix and per-night scores. It offers "Calibrate" (personal model, kappa before and
  after on held-out nights) and "Reset to global model".
- **Settings** has every parameter, grouped, each with a hint. It offers JSON export and import with validation, reset
  to defaults, reset of the HMM emissions to the physiology table, and an offer to recompute nights that a change
  made stale. Its last card, "Versions and licences", shows the app, algorithm and model versions, the licence and
  the ODC-By attribution notice of the training datasets.
- **Sources** lists the apps that wrote sleep sessions to Health Connect, with icon, name, session count and last
  session. Each has an enable switch and a priority order, packages can be added manually, and a switch takes heart
  rate and steps from any app.
- **Log** has a filter by level and sharing as a text file.
- **Privacy**
  - Permission rationale / privacy screen, reachable from Home and from Health Connect
    (`ACTION_SHOW_PERMISSIONS_RATIONALE`, and on Android 14+ the `VIEW_PERMISSION_USAGE` activity-alias).
  - No `INTERNET` permission.
- **Strings** in English and Russian.
- **Icon**: adaptive vector launcher icon with a monochrome layer, plus the vector icon `docs/icon.svg`.
- **Demo mode** (debug builds only) with synthetic nights, for screenshots; the release build contains none of its
  code or text.
- **Screenshots** in `docs/screenshots/`, rendered on the JVM from synthetic data by `ScreenshotsTest`.

#### Health Connect and storage (`:data-hc`, `:data-local`)

- **Repository** over connect-client 1.1.0:
  - paginated reads;
  - upsert by `clientRecordId`, verified by reading it back, with delete-and-insert as the fallback;
  - deletes of the app's own records only;
  - Changes API, scan of sleep origins, error mapping with backoff on rate limits and transient errors.
- **Night pipeline**:
  - source allowlist (default OHealth); the app's own sessions and sessions that already have stages are skipped;
  - nap filter (`minSessionMin`), and fragment merging (`mergeGapMin`) with the gaps written as AWAKE;
  - priority between sources, also against a higher-priority source that recorded the night with its own stages;
  - deterministic `clientRecordId` (`ps:` + SHA-256 of the sorted source uuids), and `clientRecordVersion` =
    `ALGO_VERSION · 1000 + recompute count`;
  - replaced outputs are superseded, deleted sources are handled, records left from before a reinstall are
    de-duplicated, and a failure on one night does not stop the others;
  - overwrite policies "Skip up-to-date nights", "Recompute everything" and "Never overwrite".
- **Sync and backfill**:
  - Changes-API sync that falls back to re-reading the last 7 days;
  - history backfill in 7-day windows that resumes after a restart (the start of the range is a single-record
    lookup);
  - a guard that refuses background reads without `READ_HEALTH_DATA_IN_BACKGROUND`;
  - deletion of all PulseStages sessions or of one night; erasing cancels a running backfill and recompute, and
    neither writes a night again that was deleted after it was requested.
- **Room ledger**: `estimated_nights`, `night_sources`, `source_sessions`, `night_baselines`, `log_entries`,
  `backfill_state`, `personal_models`.
- **DataStore**: settings (JSON export / import / reset; the file stores only changed values, so the trained
  defaults follow app updates) and the changes token.
- **Internal log**, mirrored to logcat with the tag `PulseStages`.

#### Background work (`:work`)

- `EstimateNewNightsWorker`: hourly Changes-API check, a daily run at a configurable time (default 09:00), and
  "Estimate new" as expedited work.
- `BackfillWorker` with progress, cancel and resume, and `RecomputeWorker`.
- All workers use a battery-not-low constraint (configurable), exponential backoff and a `dataSync` foreground
  notification for runs the user starts.

#### Algorithm (`:core-algo`, `:algo-cli`)

- **Estimators**
  - Pure Kotlin/JVM module behind `StageEstimator`, with `ALGO_VERSION` 1.
  - RULES: thresholds.
  - HMM: Viterbi and forward-backward, time-of-night priors, REM lockout, ultradian-cycle prior, per-night EM.
  - HYBRID (default): MLP emissions on multi-scale features, decoded by an explicit-duration HSMM or a geometric HMM.
- **Processing**
  - Preprocessing: range filter, Hampel filter, bounded interpolation, smoothing and coverage.
  - Shared post-processing, confidence, and an elevated-heart-rate baseline.
  - Personalization: calibration on reference nights, and population EM.
- **Tooling**
  - Metrics (kappa, accuracy, F1, confusion matrix, minute errors), synthetic night generator, params hash, CSV
    export.
  - `algo-cli` with `features`, `evaluate` and `estimate`, used by the training scripts.

#### Training and validation (`tools/train`)

- License-checked download of PhysioNet sleep-accel 1.0.0 and BIDSleep 1.0.1 (both ODC-By 1.0). DREAMT is not used,
  because its access is restricted.
- OHealth emulation: one heart-rate value per 2 minutes (instantaneous and 2-minute mean), steps from the
  accelerometer, 4-class labels.
- Leave-one-subject-out validation with nested selection, computed through `algo-cli`. The results are in
  ALGORITHM.md §13.
- Bundled model `app/src/main/assets/models/hybrid_model.json` and the generated `TrainedDefaults.kt`.
- pytest suite on synthetic data.

#### Build, CI and documentation

- **Toolchain**: Gradle 9.8.1 wrapper, AGP 9.4.1, Kotlin 2.4.21; compileSdk 37, targetSdk 36, minSdk 28; versions in
  `gradle/libs.versions.toml`; project-local toolchain through `.mise.toml` and `.tooling/`.
- **Quality checks**:
  - ktlint, detekt (with KDoc required on the public API of `:core-algo`) and JUnit 5 tests;
  - module-graph check, Android-free runtime classpath and no Android imports in `:core-algo`;
  - merged-manifest tests (no `INTERNET`) and English / Russian string parity of every module;
  - Robolectric tests of the production Hilt / WorkManager graph and of the UI wiring with a fake Health Connect.
- **GitHub Actions**:
  - CI runs ktlint, detekt, the tests and `assembleDebug`, and the Python tests. It skips documentation-only changes
    and keeps the debug APK of pushes to `main` for 2 days;
  - Release on `v*` tags (and `-alpha.N`, `-beta.N`, `-rc.N` pre-release tags) builds the signed release APK and the
    debug APK, verifies both and publishes them with SHA-256 checksums and the tag's changelog section, with the key
    taken from repository secrets. A manual dry run checks the signing secrets without publishing anything;
  - every action is pinned to a commit SHA, and Dependabot keeps the pins current.
- **Documentation**: README (English, with a Russian section), ALGORITHM.md, PLAN.md, docs/INTERCHANGE.md,
  docs/SCREENSHOTS.md, tools/train/README.md and DATASETS.md.
- **License**: WTFPL.
