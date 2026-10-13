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
| 5b | `tools/train`: dataset download (license-checked), OHealth emulation, steps from accelerometer, LOSO training/validation through `:algo-cli`, model bundle → `app/src/main/assets/models/`, numbers → `ALGORITHM.md` | |
| 6 | `:data-local` (Room, DataStore, logger), `:data-hc` (repository, night pipeline, Changes API sync, backfill, fake repository, idempotency tests) | |
| 7 | `:work` (workers, scheduling, backoff), `:app` (Compose M3 UI, adaptive layouts, permissions, rationale activity, icon) | |
| 8 | README (en + ru), ALGORITHM.md, CHANGELOG.md, screenshots; full check; install on the phone via `$ADB_WIN` | |
| 9 | Known limitations and ideas for the next version | end of README / report |

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
- **Trained HMM defaults.** If the supervised MLE estimates beat the physiology table on LOSO kappa, they become the
  `AlgoParams()` defaults (generated Kotlin constants with a provenance header); the table stays available as
  `EmissionTable.PHYSIOLOGY` ("reset to physiology table").
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
  title says "(estimate)". Full rationale in README.
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
- **Screenshots in docs/** are rendered from synthetic nights, never from real health data.
- **Spec vs. "prefer what wins on synthetic tests".** Where a choice can be measured on the open PSG dataset, the
  dataset decides (spec §9 forbids tuning on synthetic data); synthetic tests only guard invariants and regressions.

## 3. Versions (verified latest stable, `gradle/libs.versions.toml`)

Gradle 9.8.1 · AGP 9.4.1 (built-in Kotlin) · Kotlin 2.4.21 · KSP 2.3.12 · Hilt 2.60.1 · androidx.hilt 1.4.0 ·
Room 2.8.5 · WorkManager 2.12.0 · DataStore 1.2.1 · Compose BOM 2026.09.00 (material3 1.4.0, adaptive 1.3.0) ·
navigation-compose 2.10.2 · activity-compose 1.13.0 · lifecycle 2.11.0 · core-ktx 1.19.1 ·
**connect-client 1.1.0** (only stable; 1.2.0-alpha06 is the newest pre-release) · kotlinx-coroutines 1.11.0 ·
kotlinx-serialization 1.11.0 · JUnit 5.14.4 · ktlint 1.8.0 via org.jlleitschuh.gradle.ktlint 14.2.0 · detekt 1.23.8.

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

Published expectations (Walch 2019 Table 6): 3-class kappa with HR only 0.04–0.17, motion + HR + clock ≈ 0.30.
4-class kappa ≈ 0.2–0.3 is a realistic target for 2-minute HR without an accelerometer; > 0.5 would indicate leakage.
Attribution notice for produced works (ODC-By §4.3) goes into README and the app's About text.

## 6. Open questions (none blocking)

- Sleep as Android behaviour when OHealth's and our session overlap — verify on the device after install.
- Whether OHealth writes SpO2 / respiratory rate at night — the app works with or without them.
- OHealth HeartRateRecord granularity (one sample per record or series) — handled either way by the 24 h left
  padding.
