# Health Connect integration

PulseStages reads sleep sessions and heart rate that other apps (by default OHealth, `com.heytap.health.international`)
wrote to Health Connect, and writes its own sleep sessions with estimated stages back. This document lists every
permission, every read and write, the exact format of the written record, the use of the Changes API, quotas and
error handling, and what other apps see.

The app uses the Jetpack client `androidx.health.connect:connect-client` 1.1.0 (`gradle/libs.versions.toml`), minSdk 28,
targetSdk 36, compileSdk 37. On Android 14 and later Health Connect is a framework module; on Android 9–13 it is the
separate Health Connect app from Google Play. The platform behaviour described here was checked in the connect-client
1.1.0 sources and in the AOSP HealthFitness module (PLAN.md,
[§4 Health Connect — verified facts](../PLAN.md#4-health-connect--verified-facts-connect-client-110-sources--aosp-healthfitness)).
The Android 9–13 path is closed source and has not been run on a device; where its behaviour matters, this document
says so.

All Health Connect calls go through `HealthConnectRepository` in `:data-hc`, wrapped by
`BackgroundRuleHealthRepository` ([architecture.md §6.5](architecture.md#65-the-background-permission-guard)).

Contents:

- [1. Availability](#1-availability)
- [2. Permissions](#2-permissions)
- [3. Data read](#3-data-read)
- [4. Data written](#4-data-written)
- [5. Changes API](#5-changes-api)
- [6. Deleting records](#6-deleting-records)
- [7. Errors, quotas and backoff](#7-errors-quotas-and-backoff)
- [8. Priority and what other readers see](#8-priority-and-what-other-readers-see)
- [9. Deleting PulseStages data](#9-deleting-pulsestages-data)
- [10. Sources](#10-sources)

## 1. Availability

`HealthConnectRepository.availability()` maps `HealthConnectClient.getSdkStatus`:

| `getSdkStatus` | `HcAvailability` | Meaning |
|---|---|---|
| `SDK_AVAILABLE` (3) | `AVAILABLE` | Health Connect can be used. |
| `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` (2) | `PROVIDER_UPDATE_REQUIRED` | Android 9–13 only: the Health Connect app is missing, disabled or too old. Home offers "Install or update Health Connect", which opens the Play Store onboarding page `market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding` (or the web page when the Play Store app is missing). |
| `SDK_UNAVAILABLE` (1), or any exception | `NOT_SUPPORTED` | Not available on this device or profile; Health Connect does not work in work profiles. |

On Android 14+ `getSdkStatus` only returns available or unavailable. The client is created once with
`HealthConnectClient.getOrCreate(applicationContext)` and cached. The provider package is written as the literal
`com.google.android.apps.healthdata`, because the client's `DEFAULT_PROVIDER_PACKAGE_NAME` is not public.

"Open Health Connect" on Home opens PulseStages' own permission page in Health Connect on Android 14+
(`HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS` with the package name) and Health Connect's settings
(`HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS`) on older versions (`HealthConnectIntents`).

## 2. Permissions

### 2.1 Health permissions

The strings come from the Jetpack helpers (`HealthPermissions` in `:data-hc`).

| Permission | Kind | Why PulseStages needs it | Without it |
|---|---|---|---|
| `android.permission.health.READ_SLEEP` | required | Find the source sleep sessions; follow their changes (Changes API); read PulseStages' own sessions for write verification, repair and deletion; list the apps that write sleep (Sources); find reference nights (Compare). | No run reads anything; runs end as blocked with `PERMISSION_MISSING`. |
| `android.permission.health.WRITE_SLEEP` | required | Write PulseStages' own sleep sessions with stages. | The same. |
| `android.permission.health.READ_HEART_RATE` | required | Heart rate is the main input of the estimate. | The same. |
| `android.permission.health.READ_STEPS` | required | Steps are the movement input of the estimate. | The same. |
| `android.permission.health.READ_OXYGEN_SATURATION` | optional | Optional SpO2 channel (`algo.features.useSpo2`). | The channel is not read (`readSpo2` returns nothing). |
| `android.permission.health.READ_RESPIRATORY_RATE` | optional | Optional respiratory-rate channel (`algo.features.useRespiratoryRate`). | The channel is not read. |
| `android.permission.health.READ_HEART_RATE_VARIABILITY` | optional | Optional HRV (RMSSD) channel (`algo.features.useHrv`). | The channel is not read. |
| `android.permission.health.READ_HEALTH_DATA_HISTORY` | special, feature-gated | Read other apps' data older than 30 days before PulseStages was first given access: the history backfill, the full-history scan of the Sources screen, the long ranges of Compare. | Health Connect silently omits older data. The backfill state is flagged `historyLimited`; the Sources screen scans the last 90 days. |
| `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` | special, feature-gated | Read other apps' data while the app is not in the foreground: the periodic check (every 15 min to 24 h, hourly by default) and the daily run. | Background runs read nothing and end as blocked (`BACKGROUND_PERMISSION_MISSING`, sync outcome `BACKGROUND_READ_BLOCKED`); runs started in the app run as a foreground service and still read. |

Which optional channels are read (enabled **and** granted) is part of the params hash (`InputSelection`), so granting
SpO2 later makes the nights estimated without it stale.

**Feature gating.** The two special permissions are only requested when the installed Health Connect supports them:
`HealthConnectFeatures.getFeatureStatus(FEATURE_READ_HEALTH_DATA_HISTORY = 4 / FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
= 1) == FEATURE_STATUS_AVAILABLE` (`PermissionStatus.requestable` in `HomeStatus.kt`). According to the client
sources this is the case on platform builds with SDK extension 13 or later, and in Health Connect app versions with
versionCode 171302 or later on Android 9–13. Otherwise Home says that this Health Connect version cannot grant it.

**Requesting.** Home → "Grant permissions" requests every missing requestable permission in one Health Connect dialog
through `PermissionController.createRequestPermissionResultContract()`. The granted set is read again
(`permissionController.getGrantedPermissions()`) at the start of every run, and the optional channels are checked
before each read of them, because the user can revoke permissions at any time.

**What happens without each permission in the UI** (texts, buttons, the "dialog no longer shown" case) is described
in the [user guide](user-guide.md).

### 2.2 Manifest declarations

`app/src/main/AndroidManifest.xml`; `MergedManifestTest` checks the merged debug and release manifests.

- All nine health permissions are declared with `<uses-permission>` directly under `<manifest>` (some Google pages
  show them inside `<application>`; the test requires `<manifest>`).
- **Rationale activity.** `PermissionsRationaleActivity` handles `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`
  (Android 9–13), and the exported `activity-alias` `ViewPermissionUsageActivity` handles
  `android.intent.action.VIEW_PERMISSION_USAGE` with category `android.intent.category.HEALTH_PERMISSIONS`, protected
  by `android.permission.START_VIEW_PERMISSION_USAGE` (Android 14+). Health Connect opens it from the privacy-policy
  link of its permission screen. Without the alias Android 14+ does not grant health permissions, and Android 16
  revokes them. The screen ("PulseStages and your health data") lists what is read and written and how to delete it.
- **Package visibility** (`<queries>`, no `QUERY_ALL_PACKAGES`): the Health Connect app
  `com.google.android.apps.healthdata` (so `getSdkStatus` can see it on Android 11–13), OHealth, Sleep as Android
  (`com.urbandroid.sleep`), and every app that declares one of the two rationale intents, i.e. every Health Connect
  client, so the Sources, Home and Compare screens can show the label and icon of the apps that wrote the data. Sleep
  as Android is listed only so that the sync request (on by default) can check that it and its `REQUEST_SYNC`
  receiver are installed before it is sent ([8.3](#83-sleep-as-android)); the system delivers the broadcast itself
  without visibility.
- **No `INTERNET`.** The manifest removes it (`tools:node="remove"`) even if a library asks for it; the connect-client
  AAR itself merges no permission ([privacy.md §2](privacy.md#2-everything-stays-on-the-device)).

### 2.3 Other Android permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Backfill, recompute and "Estimate new" run as a WorkManager foreground service of type `dataSync`, so their Health Connect reads stay foreground reads when the user leaves the app. |
| `POST_NOTIFICATIONS` (runtime, Android 13+) | Shows the progress notification of these runs. Without it the work still runs; the notification is not shown. |
| `WAKE_LOCK` (merged from `androidx.work:work-runtime`) | WorkManager keeps the device awake while a work runs. |
| `RECEIVE_BOOT_COMPLETED` (merged from `androidx.work:work-runtime`) | WorkManager reschedules its work after a reboot. |
| `ACCESS_NETWORK_STATE` (merged from `androidx.work:work-runtime`) | WorkManager reads the connectivity state for works with network constraints. PulseStages sets none. This permission only reads whether a network is available; it does not allow opening network connections, which would need `INTERNET` ([2.2](#22-manifest-declarations)). |
| `io.github.alien2003.pulsestages.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (declared and used by `androidx.core:core`) | A permission with protection level `signature` that `androidx.core` uses to keep receivers registered at runtime private to the app on Android versions without `RECEIVER_NOT_EXPORTED`. |

These four come from the libraries' manifests and are merged into the APK; `app/build/outputs/logs/manifest-merger-release-report.txt` shows the origin of every entry.

The sync request to Sleep as Android ([8.3](#83-sleep-as-android)) needs no permission: its receiver is exported and
requires none.

## 3. Data read

### 3.1 What is read, from where, for which range

Every read uses `ReadRecordsRequest` with `ascendingOrder = true` and an origin filter (`DataOrigin` per package);
"any origin" means an empty filter. An empty package set reads nothing (the repository never sends an empty filter
when it means "no package").

| Record type | Read by | Origins | Range of record **start** times |
|---|---|---|---|
| `SleepSessionRecord` | `NightWindowReader`: sync re-reads, the window around a Changes-API page, backfill windows, recompute clusters, rebuilds after deletions, retries, the refresh of written nights | the enabled source packages (never PulseStages itself) | the requested range ± 1 day |
| `SleepSessionRecord` | backfill start (`oldestSleepSessionStartMs`) | the enabled source packages | `[now − backfillDays, now)`, or from the epoch when `backfillDays` = 0; **one request with page size 1**, never paged |
| `SleepSessionRecord` | `SourceScanner` (Settings → "Sleep sources") | any origin | the last 90 days, or the whole history with `READ_HEALTH_DATA_HISTORY` |
| `SleepSessionRecord` | `ReferenceComparison` (Compare) | any origin; PulseStages' own sessions are dropped afterwards | "90 days", "1 year" or "All history" |
| `SleepSessionRecord` (own) | write verification; the per-run lookup of our records (also by a refresh whose input changed, to compare with the stored record); "Delete my sessions"; the delete fallback on Android 9–13 | PulseStages only | ±60 s around the written record's start; ±24 h around the run's nights; from the epoch to now; from the epoch to now + 24 h |
| `HeartRateRecord` | `NightInputReader` (pipeline, including the refresh of written nights; night detail, Compare, personalization) | the night's package (`sources.hrSources` = `SAME_AS_SLEEP`, default) or any origin (`ANY`); Compare uses the enabled sources first and any origin when they have none | `[start − margin − 24 h, end + margin)`; samples are then clipped to `[start − margin, end + margin)` |
| `StepsRecord` | `NightInputReader` | as heart rate | as heart rate; records overlapping `[start − margin, end + margin)` are kept |
| `OxygenSaturationRecord`, `RespiratoryRateRecord`, `HeartRateVariabilityRmssdRecord` | `NightInputReader`, only when enabled in the parameters and granted | as heart rate | `[start − margin, end + margin)`; instantaneous records need no padding |

`margin` is `algo.preprocess.contextMarginMin` (30 min by default). `start` and `end` are those of the merged night. A
nap is read with the same margin; `algo.napMode.contextMarginMin`, when set, only narrows what the nap's estimate uses
(core-algo drops the rest), never what is read. Personalization's population EM re-reads the inputs of up to the latest
90 estimated nights when it runs ([ALGORITHM.md §10](../ALGORITHM.md#10-personalization-personal)).

PulseStages never calls `aggregate()`. Heart rate, steps and the optional channels are not stored by the app; every
reader of an estimation input reads them from Health Connect again. For each written night the ledger keeps only a
fingerprint of the input it was estimated from: per channel the number of samples and a SHA-256 digest of them
(`estimated_nights.inputFingerprint`, [architecture.md §3.6](architecture.md#36-refreshing-written-nights);
[privacy.md §3](privacy.md#3-what-is-stored-on-the-device)).

### 3.2 Start-time filtering and the 24-hour left padding

`readRecords` filters by the record's **start** time (`start >= filter.start AND start < filter.end`), not by overlap.
This holds in every AOSP branch checked, android14-release to android17-release
([RecordHelper.java, android16-qpr2-release](https://android.googlesource.com/platform/packages/modules/HealthFitness/+/refs/heads/android16-qpr2-release/service/java/com/android/server/healthconnect/fitness/recordhelpers/RecordHelper.java),
block "start/end time filter"). Consequences:

- A `HeartRateRecord` is a series: one record can hold many samples, and a series that starts before the window is
  not returned even if it has samples inside it. A `StepsRecord` interval that starts before the window is not
  returned either. PulseStages therefore reads heart rate and steps from **24 h before** the window
  (`SERIES_LEFT_PADDING_MS`) and clips the result to the window itself. How OHealth groups its roughly 2-minute heart
  rate into records (one sample per record or longer series) is not known (PLAN.md, open questions); the padding
  handles both.
- A sleep session that starts before a read window is not returned. The sleep reads are padded by one day on both
  sides (`NightWindowReader.OVERLAP_MS`) so that fragments of a night crossing a window boundary are merged
  correctly; a window then owns the nights whose first fragment starts in it.

### 3.3 Pagination

Reads use `pageSize = 1000` (the platform allows 1–5000) and loop while the page token is neither `null` nor empty.
On Android 14+ the last page returns `null`; the Health Connect app path (Android 9–13) returns `""`, and sending `""`
back would throw `NumberFormatException` on the platform. The ordering is set only on the first request, as the
platform requires.

### 3.4 History

Without `READ_HEALTH_DATA_HISTORY`, Health Connect returns other apps' data only from 30 days before the app's first
permission grant; reads of older data return nothing rather than failing (only a single-record `readRecord`, which
PulseStages does not use, fails). After a reinstall the 30 days count from the new grant. On Android 14+ an app's own
data has no history limit when the read is filtered to its own package, as PulseStages' reads of its own sessions are;
on Android 9–13 the 30-day limit applies to its own data as well
([read data](https://developer.android.com/health-and-fitness/health-connect/read-data)). There, after a reinstall
without history access, PulseStages cannot see its own sessions from more than 30 days before the new grant: it can
neither replace nor delete them, and they stay until history access is granted or they are deleted in Health Connect
([9](#9-deleting-pulsestages-data)).

### 3.5 Background reads

Without `READ_HEALTH_DATA_IN_BACKGROUND`, an app that is not in the foreground may only read its own data. Health
Connect built from android15-release and later rejects other reads with a `SecurityException`; android14-release
silently replaces the origin filter by the caller's own package and returns no error (AOSP
`HealthConnectServiceImpl`). PulseStages never relies on either behaviour: it refuses such reads itself and reports the
run as blocked ([architecture.md §6.5](architecture.md#65-the-background-permission-guard)).

## 4. Data written

PulseStages writes only `SleepSessionRecord`s, one per estimated night or nap, with
`insertRecords(listOf(record))`. It never writes heart rate, steps or any other type, never modifies a record of
another app, and never calls `updateRecords`.

### 4.1 The record

Built by `HcRecordMapper.toRecord` from `EstimatedSessionWrite`:

| Field | Value |
|---|---|
| `startTime` | start of the night: the start of its first source fragment; with `write.startBeforeSource` (Settings → "Written sessions" → "Sleep as Android compatibility", off by default) exactly 1 s earlier (`OutputOptions.START_BEFORE_SOURCE_MS` = 1000 ms, `OutputOptions.recordStartMs`), with the first stage extended back to it ([4.3](#43-stage-mapping), [8.3](#83-sleep-as-android)). The ledger keeps the night's own start. |
| `endTime` | the latest end of any of its source fragments (equal to the source session for a single fragment) |
| `startZoneOffset`, `endZoneOffset` | always explicit: the first fragment's start offset and the last-ending fragment's end offset; when the source has none, the offset already stored in the ledger for this night, otherwise the device zone's offset at that instant |
| `stages` | contiguous intervals covering the whole session ([4.3](#43-stage-mapping)) |
| `title` | from the title template ([4.4](#44-title)) |
| `notes` | compact JSON ([4.5](#45-notes)) |
| `metadata` | `Metadata.unknownRecordingMethod(clientRecordId, clientRecordVersion, device)` ([4.2](#42-metadata-recording-method-and-device)) |

Health Connect sets `metadata.id` (with a `clientRecordId`, a name-based UUID of package, record type and
`clientRecordId` on Android 14+), `metadata.dataOrigin` (`io.github.alien2003.pulsestages`) and
`metadata.lastModifiedTime`. The ledger keeps the returned id as `hcRecordId`.

Health Connect's own validation also applies: the start must be before the end, stages must not overlap and must lie
inside the session, and the start must not be in the future. PulseStages' own check is stricter
(`StageMapping.validate`, [architecture.md §3.5](architecture.md#35-writing)).

### 4.2 Metadata, recording method and device

| Metadata field | Value |
|---|---|
| `clientRecordId` | `"ps:"` + lower-case SHA-256 hex of the source session uuids, de-duplicated, sorted and joined with `,` (67 characters) |
| `clientRecordVersion` | `ALGO_VERSION · 1000 + recomputeCount` (1000, 1001, ... while `ALGO_VERSION` is 1), never below the stored version + 1; every rewrite adds one, whether a recompute or a refresh after late data ([5](#5-changes-api)) |
| `recordingMethod` | `RECORDING_METHOD_UNKNOWN` (0) |
| `device` | `Device(type = TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL)`: the phone that computed and wrote the record |

How these keys make every write idempotent is described in [architecture.md §5](architecture.md#5-idempotency).

**Why `UNKNOWN` and the phone.** The
[Metadata reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/metadata/Metadata)
defines four recording methods:

| Recording method | Official definition |
|---|---|
| `RECORDING_METHOD_ACTIVELY_RECORDED` (1) | "Data actively recorded by the user" |
| `RECORDING_METHOD_AUTOMATICALLY_RECORDED` (2) | "For data recorded passively by a device without user explicitly initiating the recording, or whenever it cannot be determined" |
| `RECORDING_METHOD_MANUAL_ENTRY` (3) | "Data manually entered by the user" |
| `RECORDING_METHOD_UNKNOWN` (0) | "Unknown recording method" |

None of them describes stages inferred by an algorithm. `AUTOMATICALLY_RECORDED` with the watch as the device would
tell readers that a watch measured these stages, which no sensor did; the "cannot be determined" clause does not fit
either, because it is known exactly how the record was produced. `UNKNOWN` makes no claim that a sensor recorded the
stages, and a device is optional for it. The origin is still attributed, as the
[write-data guide](https://developer.android.com/health-and-fitness/health-connect/write-data) asks for derived data:
`notes.sourceUuids` names the source sessions (whose own metadata name OHealth and its device), the title says
"estimate", and the notes carry the algorithm version and the params hash. This is the project's interpretation
([PLAN.md §2](../PLAN.md#2-decisions)); a reader that filters by recording method or device type may treat these records differently
from watch data, which has not been tested with readers.

The same guide also says that data an app has read from Health Connect "shouldn't be written back into Health
Connect". PulseStages does not copy data back; it writes a new, derived session that is marked as an estimate. Whether
this matches Google's intent is an interpretation, not a statement from Google.

### 4.3 Stage mapping

`StageMapping` uses only `SleepSessionRecord.STAGE_TYPE_*` constants:

| Estimated | Written (nights, and naps with "Written for a nap" = **Stages**) | Written with `write.awakeAsLight` |
|---|---|---|
| AWAKE (including the gaps between merged fragments) | `STAGE_TYPE_AWAKE` (1) | `STAGE_TYPE_LIGHT` (4) |
| LIGHT | `STAGE_TYPE_LIGHT` (4) | the same |
| DEEP | `STAGE_TYPE_DEEP` (5) | the same |
| REM | `STAGE_TYPE_REM` (6) | the same |
| UNKNOWN (an epoch inside a heart-rate gap longer than `algo.preprocess.maxInterpGapMin`, 10 min, with `algo.post.gapPolicy` = `UNKNOWN`, the default; `LIGHT` writes light sleep and `NEAREST` the stage of the nearest epoch with data instead) | `STAGE_TYPE_UNKNOWN` (0) | the same |

A nap with "Written for a nap" = **Asleep / awake** (`algo.napMode.outputPolicy` = `SLEEP_WAKE`, the default since
0.1.1) gets only `STAGE_TYPE_AWAKE` (1) and `STAGE_TYPE_SLEEPING` (2): LIGHT, DEEP, REM and UNKNOWN are written as
SLEEPING, and with `awakeAsLight` AWAKE is SLEEPING too. The ledger keeps the estimated stages for such a nap
(`sleepWakeOnly` = true), so the app still shows them as its estimate; they are not validated
([ALGORITHM.md §13.13](../ALGORITHM.md#1313-realistic-naps-pre-registered-not-adopted)). A nap with **Nothing**
(`SKIP`) is not written at all.

PulseStages never writes `STAGE_TYPE_OUT_OF_BED` (3) or `STAGE_TYPE_AWAKE_IN_BED` (7).

Invariants of every written hypnogram (checked before the write): at least one stage; sorted; contiguous, with no gap
and no overlap; the first stage starts at the session start and the last ends at the session end; every stage is at
least one epoch (`algo.preprocess.epochSec`, 60 s by default) long, unless the whole session is shorter than one
epoch; adjacent stages of the same type are merged.

With `write.startBeforeSource` the mapped stages are first validated against the night, `[night start, end)`, so a gap
before the first estimated stage is still reported and not filled. Then the record's start is set 1 s before the night
(`OutputOptions.recordStartMs`), only the first stage is extended back to it (`StageMapping.startingAt`), and the
extended stages are validated again against `[record start, end)`. Every invariant above therefore holds for the
written record. The ledger keeps the estimated stages in the night's times, not extended
([architecture.md §3.5](architecture.md#35-writing)).

### 4.4 Title

`SessionText.title`: the template of Settings → Written sessions → "Session title" (`write.titleTemplate`), or, when
it is empty, the localized default `Sleep stages from heart rate (estimate)` (the app ships an English and a Russian
text, chosen by the phone's language at write time). Placeholders: `{estimator}` (`RULES`, `HMM` or `HYBRID`),
`{version}` (`ALGO_VERSION`) and `{confidence}` (night confidence in whole percent). The result is cut to 1000
characters. The title is not part of the params hash, so changing it does not make nights stale; a night rewritten
after a change of the phone's language gets the title in the new language unless a template is set.

### 4.5 Notes

`SessionText.notes` writes compact JSON with the keys in this order, for example:

```json
{"algo":"HYBRID","mode":"NIGHT","version":1,"paramsHash":"3f6c…","coverage":0.93,"confidence":0.61,"sourceUuids":["1b2e…"],"hrSamples":231,"calibrated":false}
```

| Key | JSON type | Meaning |
|---|---|---|
| `algo` | string: `RULES`, `HMM` or `HYBRID` | the estimator that actually produced the stages; it can differ from the configured one (RULES when the coverage is too low for the configured estimator, HMM when HYBRID has no usable model bundle) |
| `mode` | string: `NIGHT` or `NAP` | the processing mode of the session ([ALGORITHM.md §18](../ALGORITHM.md#18-night-and-nap-modes-napmode)) |
| `version` | integer | `ALGO_VERSION` of the code that produced the estimate (1 in this release) |
| `paramsHash` | string, 64 lower-case hex characters | the params hash of the run: algorithm parameters, model fingerprints, input selection and output options ([architecture.md §5.8](architecture.md#58-params-hash-and-staleness)) |
| `coverage` | number, 0–1, three decimals | share of the session's epochs with heart-rate data |
| `confidence` | number, 0–1, three decimals | night confidence ([ALGORITHM.md §9](../ALGORITHM.md#9-coverage-gates-fallbacks-and-confidence-sleepstager)) |
| `sourceUuids` | array of strings | Health Connect ids (`metadata.id`) of the source sessions, sorted and distinct |
| `hrSamples` | integer | heart-rate samples used (in range, inside the analysis window, duplicates collapsed) |
| `calibrated` | boolean | whether the stage proportions were calibrated towards the target ranges (`algo.post.calibrateProportions`, off by default) |

Numbers are rounded half up to three decimals; a non-finite value is written as 0. PulseStages reads its own notes
back: `sourceUuids` identifies its earlier records of a night after a reinstall ([architecture.md §5.4](architecture.md#54-one-output-per-night)).

## 5. Changes API

`NewNightsSync` follows changes of the source sleep sessions through the Changes API instead of re-reading the whole
history on every run.

**Polling.** Health Connect cannot notify an app of new data ("your app can't get notified of new data",
[sync data](https://developer.android.com/health-and-fitness/health-connect/sync-data)), so PulseStages asks for
changes: the periodic work every `schedule.periodicMinutes` (Settings → "Schedule" → "Check for new nights every": 15
or 30 min or a whole number of hours up to 24, 1 h by default), the daily run and "Estimate new"
([architecture.md §6.1](architecture.md#61-works)). 15 min is WorkManager's minimum period
(`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` = 900000). A check that finds no change costs one change-log read
([7.2](#72-quotas)); the retry and the refresh of recent nights (below) form one late-data pass that runs at most
every 55 min, so they stay about hourly with any shorter interval.

**The token.** `getChangesToken(ChangesTokenRequest(recordTypes = {SleepSessionRecord}, dataOriginFilters = the
enabled source packages))`. PulseStages' own package is never in the filter, so its own writes do not appear in the
change log. The token is stored in the `sync_state` DataStore together with the exact package set it was created for
(`SyncStateStore`); for any other package set there is no token, so enabling or disabling a source always starts
with a new token and a re-read. A token is never requested for an empty package set (that would mean every app).
`getChanges` needs `READ_SLEEP`, and from the background `READ_HEALTH_DATA_IN_BACKGROUND`; without the latter it throws
`SecurityException`, which the background guard prevents.

**Following the log.** `getChanges(token)` is called page by page while `hasMore`. After a page has been processed
completely, its `nextChangesToken` is stored (never after a page that stopped early, so that page is fetched again
next time; processing is idempotent). When nothing changed the platform returns the same token.

- `UpsertionChange`s of sleep sessions from enabled packages: the window from the earliest start − 1 day to the latest
  end + 1 day is re-read so fragments merge, and only the nights containing a changed session (and the nights a
  changed session with stages outranks) are processed.
- `DeletionChange`s carry only the deleted record's id. The ledger maps source uuids to nights, so a deleted source
  session can be traced to our record ([architecture.md §5.5](architecture.md#55-deleted-source-sessions)).

**Re-reading instead.** A new token is taken and the recent nights are re-read by time range when there is no token
for the current package set (first run, sources changed), when `changesTokenExpired` is set, or when the stored token
was not advanced for 25 days. The re-read covers the last 7 days, extended back to one day before the last successful
sync when that is known, but at most 30 days. It also applies the deletions the abandoned change log would have
reported: sources of the ledger's nights in that range that Health Connect no longer returns. The old token, and with
it the start of the re-read, is kept until the re-read has completed, so a re-read that stopped early is repeated over
the same range.

| Constant (`NewNightsSync`) | Value | Why |
|---|---|---|
| `REREAD_MS` | 7 days | re-read window after a token reset |
| `MAX_REREAD_MS` | 30 days | Health Connect keeps change logs for about 30 days |
| `TOKEN_MAX_AGE_MS` | 25 days | tokens are documented as valid for 30 days; an older token is treated as expired |
| `RETRY_RECENT_MS` | 36 hours | nights that ended this recently are retried (without an estimate) or refreshed (written) by every late-data pass; late arrivals are retried or refreshed for this long after they were first seen |
| `MIN_RECHECK_GAP_MS` | 55 min | the late-data pass (the retry and the refresh of recent nights) runs at most this often, measured from the start of the run whose pass last completed (`late_data_pass_at` in `sync_state`), so a 15- or 30-minute check keeps the hourly cadence; 55 rather than 60 min so that an hourly run is never skipped by a few minutes of jitter. "Estimate new" runs the pass at once |
| `LATE_ARRIVAL_MAX_MS` | 30 days | nights first seen at most this long after they ended are retried or refreshed with growing gaps |
| `MAX_REFRESH_NIGHTS_PER_RUN` | 8 | written nights whose input one pass reads again; the rest are left to later passes ([7.2](#72-quotas)) |

**Token lifetime.** The client documentation says tokens are valid for 30 days; the platform purges change-log and
token rows older than 32 days (90 days with a cloud-backup flag;
[ChangeLogsRequestHelper.java](https://android.googlesource.com/platform/packages/modules/HealthFitness/+/refs/heads/android16-qpr2-release/service/java/com/android/server/healthconnect/common/changelog/ChangeLogsRequestHelper.java)).
On Android 14+ the client reports an expired, unknown or blank token as `changesTokenExpired = true`.

**Android 9–13.** The Health Connect app reports an outdated token as a plain `RemoteException`
(`CHANGES_TOKEN_OUTDATED`), which cannot be told apart from an IPC failure. `HealthConnectRepository.sleepChanges`
retries once, then treats the token as expired; the following re-read also covers whatever a merely transient failure
would have hidden.

**Late heart rate.** The token covers `SleepSessionRecord` only, so heart rate or steps that arrive after their
session (for example when the watch syncs hours later) produce no change. After following the log (or re-reading),
a sync therefore runs a late-data pass, at most every 55 min (below), which:

- retries the recent nights of an enabled source that could not be estimated yet (NOT_ESTIMATED, FAILED); a night of
  a source that was disabled is never found by the window read, so it is not retried;
- refreshes the recent written nights: it reads their heart rate, steps and optional channels again with the same
  reads as an estimate and compares them with the fingerprint stored at the write (sample counts and SHA-256 digests).
  When the input is unchanged, nothing else happens. A changed input is estimated again, and the record is rewritten
  under the same `clientRecordId` with the version + 1 only when its start, stages, title or notes change; a night with
  fewer heart-rate samples (in the estimate) than at the write keeps its record (a source replacing its records must not make it worse
  for a while); a record deleted outside PulseStages is not written again. Nothing is refreshed under `NEVER`, and stale nights are left to a
  recompute.

Both use the window of the constants above: nights that ended in the last 36 hours in every pass, late arrivals with
growing gaps, about 1, 2, 4, 8, 16 and 32 h after they were first seen; at most 8 written nights are refreshed per
pass ([architecture.md §6.7](architecture.md#67-what-a-sync-does),
[§3.6](architecture.md#36-refreshing-written-nights)). The pass as a whole runs at most every 55 min
(`MIN_RECHECK_GAP_MS`), measured from the start of the run whose pass last completed (`late_data_pass_at` in
`sync_state`), so it stays about hourly also with a 15- or 30-minute check, and a night whose attempt records nothing
(its source now has its own stages, or a written night can no longer be estimated) is read once per pass, not on
every check. A pass that stopped early (a quota error, a refused access) or ended with an exception (for example a
Health Connect error of the retry's window read) is repeated by the next run; a transient error of the refresh's
window read ends only the refresh, and the pass counts as done, so it is left to the next pass. "Estimate new" (`EstimateTrigger.NOW`, `userRequested`) runs the pass at once, also a few minutes after the
last one, and the next scheduled pass comes at least 55 min after it. Heart rate that arrives after these windows is
used only by a recompute.

## 6. Deleting records

PulseStages deletes only its own sessions, and only by `clientRecordId`:
`deleteRecords(SleepSessionRecord::class, recordIdsList = emptyList(), clientRecordIdsList = ids)`.

- Health Connect scopes deletion by `clientRecordId` to the calling app, so no other app's data can be touched.
  PulseStages never deletes by time range.
- Ids are de-duplicated and sent in chunks of 100 (`DELETE_CHUNK_SIZE`). A call with both lists empty would throw
  `IllegalArgumentException`, so an empty list sends nothing.
- On Android 14+ the platform ignores ids that do not exist (AOSP
  [FitnessRecordDeleteHelper.java](https://android.googlesource.com/platform/packages/modules/HealthFitness/+/refs/heads/android16-qpr2-release/service/java/com/android/server/healthconnect/fitness/FitnessRecordDeleteHelper.java)),
  although the client documentation says such a call fails.
- On Android 9–13 the client documentation says deleting a non-existent id fails the whole call with a
  `RemoteException`, which also looks like a rate limit or a dead binder there. Each chunk is therefore tried once
  without retries; after a `RemoteException` PulseStages reads its own sessions and deletes only the ids that still
  exist, with the normal retries. An id is dropped only when that read shows it is gone, so a delete that really
  failed is reported and the record is not forgotten.

Deletions happen when the user deletes data ([9](#9-deleting-pulsestages-data)), when a night is superseded, when all
sources of a night were deleted, when a nap is no longer written (`SKIP`), and in the write fallback of
`VerifiedUpsert` (delete and insert again after a mismatch).

## 7. Errors, quotas and backoff

### 7.1 Exception mapping

On Android 14+ the client converts a platform `HealthConnectException` by its error code: `ERROR_IO` →
`IOException`, `ERROR_REMOTE` → `RemoteException`, `ERROR_SECURITY` → `SecurityException`, `ERROR_INVALID_ARGUMENT` →
`IllegalArgumentException`, and every other code, including `ERROR_RATE_LIMIT_EXCEEDED` (7) and
`ERROR_DATA_SYNC_IN_PROGRESS` (8), → `IllegalStateException` with the platform exception as cause.
`HealthExceptionMapper` maps the result to one `HealthDataException`:

| Thrown by the client | `HealthDataException` | Retried in the call |
|---|---|---|
| `IllegalStateException` caused by a platform exception with code 7 or 8 | `QuotaExceeded` | yes |
| `IllegalStateException` caused by a platform exception with code 9 (`ERROR_UNSUPPORTED_OPERATION`), or `UnsupportedOperationException` | `Unavailable` | no |
| `SecurityException` (missing or revoked permission, background rule), platform code 5 | `PermissionDenied` | no |
| `IllegalArgumentException`, `NumberFormatException` (invalid request or record), platform code 3 | `InvalidData` | no |
| `RemoteException`, `IOException` | `Transient` | yes |
| other `IllegalStateException` | `Transient` while Health Connect is available, otherwise `Unavailable` (`getOrCreate` throws `IllegalStateException("Service not available")` when it is gone) | `Transient` only |
| anything else | `Transient` | yes |

The platform error code is read only on API 34+ (`PlatformErrorCodes`). On Android 9–13 the client has no rate-limit
error code; a provider-side limit can only arrive as another error, and an error code the client does not know becomes
a `RemoteException`, which is retried as `Transient`. This path cannot be verified (closed source).

### 7.2 Quotas

Google documents that reads, change-log reads and writes are rate-limited per period and per day, with stricter
limits in the background, and publishes no numbers
([rate limiting](https://developer.android.com/health-and-fitness/health-connect/rate-limiting)). The defaults in the
AOSP sources
([RateLimiter.java, android16-qpr2-release](https://android.googlesource.com/platform/packages/modules/HealthFitness/+/refs/heads/android16-qpr2-release/framework/java/android/health/connect/ratelimiter/RateLimiter.java))
are flag values that can change without an app update:

| Bucket | Foreground | Background |
|---|---|---|
| Read calls per 15 min | 2000 | 1000 |
| Read calls per 24 h | 16000 | 8000 |
| Write calls per 15 min | 1000 | 1000 |
| Write calls per 24 h | 8000 | 8000 |

Memory limits: 5,000,000 bytes per call and 1,000,000 bytes per record. A call that exceeds a quota fails with
`ERROR_RATE_LIMIT_EXCEEDED`.

What PulseStages spends: every page of every read is one call. Estimating a night reads its heart rate and steps and
up to three optional channels (one call per page of up to 1000 records); writing it is one insert plus one
verification read, and a mismatch adds a delete, a second insert and a second read. A run also reads the sleep
sessions of its window and, on its first write, PulseStages' own sessions around its nights. The backfill finds its
start with a single-record read and then reads the history one 7-day window at a time, so a backfill of a long history
makes the most calls.

A check that finds nothing new (a valid token, no change, no late-data pass due or no night due in it) makes one
call of the read quota: `getChanges`. Its other calls are not counted: `getSdkStatus` is answered in the client, and
`getGrantedPermissions` (made once more by a background run, through `BackgroundRuleHealthRepository`) takes no quota;
population EM reads nothing when it is not due. At the shortest interval, 15 min, that is 96 periodic checks and the
daily run, about 97 read calls a day.

A sync's late-data pass also refreshes the recent written nights that are due ([5](#5-changes-api)). Per checked
night it reads the heart rate and the steps (one call per page of up to 1000 records: usually one each, up to about
three with dense heart rate and the 24 h left padding) and each enabled and granted optional channel; per refresh one
read of the sleep sessions around the targets and, when an input changed, one read of PulseStages' own sessions
around them; a rewrite adds the insert and its verification read. `MAX_REFRESH_NIGHTS_PER_RUN` = 8 keeps this under
100 read calls per pass even in the worst case (8 nights with dense heart rate, three optional channels and a
rewrite each: 8 × 9 + 2 + 8 = 82), far below the background quota of 1000 calls per 15 minutes. The pass runs at most
every 55 min (`MIN_RECHECK_GAP_MS`), whatever the interval and the daily run included: at most about 26 passes a day
(1440 / 55), whose refresh makes at most 82 read calls, about 2100 read calls a day in that worst case; the retry in the
same pass, which has no per-pass limit, adds one read of the sleep sessions and the input reads of each due night
without an estimate (usually none or one; more after a backfill of nights without heart rate); plus the change-log
reads of the checks (about 97 a day at 15 min), below 8000; each "Estimate new" adds one pass. Usually only the one or two sessions
of the last 36 hours are due. A backlog of more than 8 due nights (a backfill that makes many recent nights due at
once) is worked off one pass at a time, as with hourly checks.

The Health Connect sync that "Ask Sleep as Android to sync" requests ([8.3](#83-sleep-as-android)) is Sleep as
Android's own: the quota is counted per app (`RateLimiter` keeps its buckets per caller uid), so its reads (among them
a 30-day heart-rate aggregate) and its writes count against Sleep as Android's quota, not PulseStages'.

### 7.3 Backoff

Two layers:

1. **In the call.** `QuotaExceeded` and `Transient` are retried after 1, 2, 4, 8 and 16 s (`RetryPolicy.DEFAULT`: at
   most six attempts and 31 s of waiting per call). Every retry is logged.
2. **In WorkManager.** When the call still fails, a quota error stops the run (`quotaHit`, sync outcome
   `QUOTA_EXCEEDED`, backfill state `WAITING_RETRY`; in a sync also during the retry or the refresh of recent nights)
   and the work returns `Result.retry()`. WorkManager retries with
   exponential backoff starting at 30 s, doubled per retry up to 5 hours, for up to 20 retries
   ([architecture.md §6.3](architecture.md#63-results-retries-and-backoff)). A backfill resumes from its persisted
   cursor; a recompute of "all" or "stale" skips the nights an interrupted attempt already recomputed. This follows
   Google's advice to retry from where the exception occurred.

## 8. Priority and what other readers see

### 8.1 Two sessions per night

For every estimated night Health Connect holds two overlapping sleep sessions: the source app's session (OHealth's
has no stages) and PulseStages' session with stages, titled from the template. Our session has the night's start and
end, or starts 1 s earlier with `write.startBeforeSource` ([4.1](#41-the-record)). Any app the user allows to read
sleep can read PulseStages' session, including its title and notes. Readers that list sessions decide themselves which
of the overlapping sessions to show; Sleep as Android, for example, imports only the one that starts first
([8.3](#83-sleep-as-android)).

### 8.2 Health Connect's "Data sources and priority"

Health Connect lets the user order the apps that write sleep (Google's help gives the path Health Connect →
"Permissions and data" → "Manage data" → "Data sources and priority";
[Manage your Health Connect data](https://support.google.com/android/answer/12990553)). According to the developer
documentation ([aggregate data](https://developer.android.com/health-and-fitness/health-connect/aggregate-data)):

- Health Connect de-duplicates only the Activity and Sleep data types, and only in `aggregate()`, where it keeps the
  data of the app with the highest priority;
- `readRecords()` returns the sessions of every app, overlapping ones included; apps that list sessions must
  de-duplicate them themselves;
- an app removed from the list still writes data, but its data no longer counts in the totals.

**Recommendation:** for Sleep, move PulseStages above OHealth. Sleep totals in Health Connect, and in readers that use
`aggregate()`, are then taken from PulseStages' staged session instead of the session without stages. This does not
change what readers that call `readRecords()` see, nor what PulseStages reads: PulseStages never reads its own
sessions as a source, and it applies its own source priority (Settings → "Sleep sources",
[architecture.md §3.2](architecture.md#32-building-candidates)) independently of Health Connect's list. Sleep as
Android does not use the list either ([8.3](#83-sleep-as-android)).

### 8.3 Sleep as Android

Sleep as Android (`com.urbandroid.sleep`) can import third-party sleep from Health Connect (Settings → Services → Health
Connect, marked beta in its documentation; [its docs](https://sleep.urbandroid.org/docs/services/health_connect.html)).
Its documentation does not say how it chooses between overlapping sessions of different apps. The import behaviour in
the bullets below was observed with Sleep as Android 20261006 and confirmed on the development phone (Android 16,
ColorOS 16), except where a point says it is not yet confirmed; other versions may behave differently.

- **When it imports.** Only when the user taps Settings → Services → "Sync now" (or the sync button of its Health
  Connect screen), when it saves a record (added, edited or saved automatically) and when its own tracking ends. "Sync
  now" syncs every enabled service its summary lists (for example "Health Connect, SleepCloud, SleepSync"). Its
  documentation says the integration then works in the background, but there is no periodic import for a user who
  only views data in it. Its syncs after saving a record and after its tracking send the public sync request of its
  Intent API (`com.urbandroid.sleep.REQUEST_SYNC`), the same request PulseStages can send
  (`write.requestSleepAsAndroidSync`, below); that a request from PulseStages starts the sync was observed with Sleep
  as Android 20261006 but is not yet confirmed on the development phone.
- **The "Health Connect" row is a switch.** While the row in Settings → Services says "Connected", tapping it does not
  sync: it turns the integration off, and Sleep as Android then revokes all its Health Connect permissions (they
  disappear once its process ends). Turning the integration on again asks for the permissions again, and "Access past
  data" must be switched on again in Health Connect → "App permissions" → "Sleep" → "Additional access". Turning it on
  (when it is off) opens its Health Connect screen. This happened on the development phone; the integration and all nine
  permissions were restored. The user guide therefore tells users to sync with "Sync now" only.
- **Selection.** It reads the sleep sessions of all apps, orders them by start time and keeps only the first session of
  every group of overlapping sessions; sessions that merely touch (one ends where the next starts) count as overlapping.
  The others are dropped silently. The priority list of [8.2](#82-health-connects-data-sources-and-priority) does not
  change what it imports.
- **Ties.** Health Connect returns sessions with the same start in the order they were inserted. Our record is written
  after its source session, so with the source's exact start it always comes second: Sleep as Android keeps OHealth's
  session without stages and drops ours. If another app's session starts even earlier, Sleep as Android keeps that one.
- **No re-import, no updates.** It never imports a session that overlaps a record it already has, including the
  records it imported earlier (shown with a cloud icon), and it never picks up later changes of an imported record:
  a refresh after late heart rate ([5](#5-changes-api)) or a recompute rewrites our record in Health Connect, not its
  copy. A night imported once stays as it was until the user deletes it in Sleep as Android and syncs again. Deleting
  a night in Sleep as Android removes nothing from Health Connect.
- **Heart rate and SpO2 come later.** The times and stages of an imported record never change from Health Connect (its
  own heart-rate awake detection can still add awake stretches, below), but heart rate and SpO2 do reach it: a sync attaches them only to the records Sleep as Android had before that sync, so a night gets
  them on the sync after its import (confirmed on the development phone: a second "Sync now" attached heart rate to
  PulseStages' nights). Values that reach Health Connect later are merged into the record on later syncs, as long as
  it started in the last 30 days (not yet confirmed on the development phone).
- **Read window.** "Sync now" and the syncs it starts itself (after saving a record or when its tracking ends,
  through the same request PulseStages can send) read Health Connect for the last 30 days (for its own syncs observed
  with Sleep as Android 20261006, not yet confirmed on the development phone); only the sync button of the Health Connect screen it opens when
  the integration is turned on reads up to 5 years back. Without Health Connect's history access ("Access past data"
  in Health Connect → "App permissions" → "Sleep" → "Additional access") it reads other apps' data only from the last
  29 days ([3.4](#34-history)), so an older night deleted there does not come back with "Sync now".
- **Stages.** On import, `STAGE_TYPE_AWAKE` (1), `STAGE_TYPE_LIGHT` (4), `STAGE_TYPE_DEEP` (5) and `STAGE_TYPE_REM` (6)
  become its awake, light, deep and REM; `STAGE_TYPE_UNKNOWN` (0) and `STAGE_TYPE_SLEEPING` (2) are dropped.
  - A record without light, deep or REM gets no hypnogram. Sleep as Android then shows only short awake bars from its
    own heart-rate-based awake detection, which is on by default
    ([awake detection](https://sleep.urbandroid.org/docs/sleep/awake_detection.html)): the thin stripes users see on
    OHealth's nights. They are not stages written by PulseStages. On a record with our stages the same detection runs
    once heart rate is attached, and the awake stretches it finds can cover parts of our stages (below; not yet
    confirmed on the development phone).
  - When Sleep as Android imports our session (with `write.startBeforeSource`), a nap written with `SLEEP_WAKE` (the
    default nap output) shows as a time span without phases (only its awake parts, if any), and with
    `write.awakeAsLight` without awake parts either. `algo.napMode.outputPolicy` = `STAGES` ("Written for a nap" =
    "Stages") writes the nap's stages, which Sleep as Android then shows as phases; they are not validated.
  - Spans written as `STAGE_TYPE_UNKNOWN` (heart-rate gaps, [4.3](#43-stage-mapping)) have no phase there.
- **Backup import.** Its Backup → "Download from Health Connect" creates records without the cloud icon, which Sleep as
  Android later exports to Health Connect as its own sessions: duplicates in the user's Health Connect archive. The
  user guide advises against it.

Confirmed on the development phone: without `write.startBeforeSource`, Sleep as Android showed OHealth's stage-less
session (cloud icon, OHealth's times, only thin awake stripes); deleting it in Sleep as Android and syncing brought
the stage-less session back every time; Apple Watch nights with stages that another app wrote to Health Connect showed a
full hypnogram. With `write.startBeforeSource` on, after a recompute and a sync with the sync button of its Health
Connect screen ("Sync now" runs the same import for the last 30 days), Sleep as Android 20261006 imported our
sessions (cloud icon) of the nights it held no record of (they had been deleted there before the sync): nights showed a
full hypnogram with the same phase shares as PulseStages (for one night 11 % deep, 20 % REM, 64 % light and 5 % awake
in both apps), and naps written with `SLEEP_WAKE` showed as a time span without phases. Sleep as Android shows whole
minutes, so a night whose session starts at 3:03:59 starts at 3:03 there, one minute before the watch app's 3:04.

**`write.startBeforeSource`** (Settings → "Written sessions" → "Sleep as Android compatibility", off by default) starts
our record `OutputOptions.START_BEFORE_SOURCE_MS` = 1 s before the night, with the first stage extended back to it
([4.1](#41-the-record), [4.3](#43-stage-mapping)), so it sorts before the source session. Sleep as Android then imports
our session instead of the source's (confirmed on the development phone with Sleep as Android 20261006, above). The
option applies to every written session, naps included. It changes the written data and is part of the params hash while
it is on ([architecture.md §5.8](architecture.md#58-params-hash-and-staleness)): turning it on, or off again, makes
every written night stale, and a recompute rewrites the records in place (same `clientRecordId`, version + 1). Nights
Sleep as Android already imported must be deleted there and synced again. `write.awakeAsLight` ("Write awake as light
sleep") remains for readers that show awakenings poorly; it changes the written data and the params hash, and Sleep as
Android does not need it, because it imports AWAKE as awake. The user's steps are in the
[user guide](user-guide.md#sleep-as-android).

**`write.requestSleepAsAndroidSync`** (Settings → "Written sessions" → "Ask Sleep as Android to sync", on by default)
lets PulseStages start that sync. After a run that wrote or rewrote at least one session (a periodic or daily check,
"Estimate new", a backfill or a recompute; also one that stopped after writing: a quota error, a refused access,
another Health Connect error, or WorkManager stopping it), `SleepAsAndroidSyncRequests.afterRun` sends Sleep as
Android's public sync request once: the broadcast
`Intent("com.urbandroid.sleep.REQUEST_SYNC").setPackage("com.urbandroid.sleep")` (`SleepAsAndroid.requestSyncIntent`)
with no extras, data, component or flags, so it carries nothing about the user. Its
[Intent API](https://sleep.urbandroid.org/docs/devs/intent_api.html) lists it among its broadcasts as "Request Backup
Sync" ("Triggers CSV export and cloud backup") and asks for the package on every intent. `BroadcastSyncRequestSender`
first looks up the receiver with `queryBroadcastReceivers` (hence the `<queries>` entry,
[2.2](#22-manifest-declarations)) and sends nothing when Sleep as Android is not installed. Android reports nothing
back, so a request counts as sent even when Sleep as Android does not get it: a stopped app (never opened, or
force-stopped) receives no broadcasts, and PulseStages deliberately does not wake it (no
`FLAG_INCLUDE_STOPPED_PACKAGES`). A run that wrote nothing sends nothing, and a failed request is logged and never
fails the run; once the request is out, a failure to store or schedule its follow-up (below) is logged as a warning of
its own and the request still counts as sent. Every session is counted as it is written (`WriteCount`,
[architecture.md §6.7](architecture.md#67-what-a-sync-does)), so only a process death loses the request; the next
request's sync, which reads the last 30 days, then covers that night too.

Observed with Sleep as Android 20261006, not yet confirmed on the development phone: its receiver
(`RequestSyncReceiver`, exported, no permission) reads no extras, schedules its backup and the syncs of its other
enabled services (a job that runs as soon as a network is available; requests made while there is none give one such
run) and, about 10 s later and only when its Health Connect integration is on, a Health Connect sync like that of
"Sync now", which needs no network: the last 30 days, imports with the cloud icon. Requests less than 10 s apart give one Health
Connect sync, and one that arrives while a sync runs is skipped. Its documentation describes only the backup, so the
Health Connect part may change in any version.

**A follow-up for a new or recomputed night.** Sleep as Android attaches heart rate and SpO2 only to the records it
had before a sync (above), so a night imported in the sync of a first request gets them only from a second one. A run
that wrote a new or recomputed night (`written`) therefore also schedules one follow-up request
`SleepAsAndroidSyncRequests.FOLLOW_UP_DELAY_MS` = 10 min later: the one-time unique work `sleep-as-android-follow-up`
(`SleepAsAndroidSyncWorker`, `ExistingWorkPolicy.REPLACE`, battery-not-low like the scheduled works), whose due time
is kept in `sync_state` (`sleep_as_android_follow_up_due_at`). It is a work rather than a step of the next run because
it then comes about 10 min after the import whatever the check interval (up to 24 h), needs no Health Connect access
and survives process death. REPLACE keeps at most one pending; the due time is cleared before the request is sent, so
a work that runs again sends nothing twice; a work that runs before the due time (one that REPLACE did not stop in
time, or after a clock change) sends nothing and schedules itself again for the rest of the wait, at most 10 min, so
that it does not use up a newer night's follow-up within Sleep as Android's first sync; the work sends nothing once
the option is off, and a run with the option off cancels a pending one. A run that only rewrote nights (`rewritten`:
a refresh after late heart rate) gets no follow-up: Sleep as Android imported those nights earlier, so the request's
own sync attaches the new heart rate, and every further sync may add another set of heart-rate values (below). A
night Sleep as Android did not have yet (the option was off when PulseStages wrote it, the first request did not reach
a stopped Sleep as Android, or the night was deleted there) is imported by that sync and gets its heart rate only from
the next request. A pending follow-up that is due by then counts as sent by that request.

**The first version stays in Sleep as Android.** Its copy of a night keeps the stages of the first import (above),
and with the option Sleep as Android imports a night seconds after PulseStages first wrote it. With 15- or 30-minute
checks that first version is more often an estimate from part of the night's heart rate, possibly the rules fallback
("Rules fallback (low coverage)"). The automatic update after late heart rate ([5](#5-changes-api)) then changes only
PulseStages' record in Health Connect; Sleep as Android gets only the new heart rate. To get the update into Sleep as
Android, the user deletes that night there and then recomputes it in PulseStages: with the option on, the request
after the recompute makes Sleep as Android import it again, and the follow-up attaches its heart rate.

**Side effects**, observed with Sleep as Android 20261006 and not yet confirmed on the development phone:

- every request also runs the syncs of Sleep as Android's other enabled services (Google Fit, Google Calendar, Samsung
  Health, SleepSync), as "Sync now" does, and, unlike "Sync now", its backup (a local `sleep-export.zip` by default): a
  new or recomputed night causes two of them while the phone is online, and every refresh after late heart rate one
  more;
- its sync reads every app's heart rate of the last 30 days as 10-minute averages (`BPM_AVG`), from its own quota
  ([7.2](#72-quotas));
- it can write heart rate (`HeartRateRecord`, `RestingHeartRateRecord`) and SpO2 to Health Connect (origin
  `com.urbandroid.sleep`) for the records of those 30 days that got no new values in the sync, mostly the nights it
  tracked itself; for other apps' nights PulseStages estimates from such heart rate only with "Heart rate and steps
  from" = "Any app" (`sources.hrSources` = `ANY`). It also reads it for Sleep as Android's own sessions when Sleep as
  Android is an enabled source, and in Compare for a reference night that has no heart rate from the enabled sources;
- heart rate on a record runs Sleep as Android's heart-rate awake detection (its Settings → "Sleep tracking" → "Awake
  detection" → "Heart rate monitoring", "Medium sensitivity" by default; "Disabled" turns it off), which can add awake
  stretches over our stages; they stay on that record;
- every sync probably adds another set of 10-minute heart-rate values to records that already have heart rate: the
  averaging windows start at "now − 30 days" of each sync, and Sleep as Android skips a value only when its time and
  value are the same (an inference, to be checked on the phone);
- the nights it imports in this sync get the cloud icon and are never exported back to Health Connect as its own
  sessions (unlike Backup → "Download from Health Connect"). Its log line "HealthConnect sessions found: total=…
  ours=… others=…" (logcat tag `SleepAsAndroid`) counts its own sessions in Health Connect of the last 30 days as
  `ours`, before that sync's export. Between the first and the follow-up request it must not grow by the nights it
  imported; nights tracked or added in Sleep as Android itself in between do add to it.

The option changes nothing that is written and is not part of the params hash (`OutputOptions.hashExtra` leaves it
out). It is on by default because it has no effect without Sleep as Android (nothing is sent) and the request carries
no data; the cost is that with Sleep as Android installed every write brings the side effects above, so the user guide
says when to turn it off. It is meant for use with `write.startBeforeSource`, which stays off by default: without
that, the session Sleep as Android imports in the requested sync is the source's, without stages, as in its own syncs.
PulseStages' log has "asked Sleep as Android to sync (written=N rewritten=M; follow-up in 10 min)" and, 10 min later,
"asked Sleep as Android to sync again: heart rate and SpO2 for the night it imported" (tag `SleepAsAndroid`); the
message of a run the user started adds "Sleep as Android was asked to sync.", and the work output carries
`sleepAsAndroidSyncRequested`. The user's steps are in the [user guide](user-guide.md#ask-sleep-as-android-to-sync).

## 9. Deleting PulseStages data

| Way | What it deletes |
|---|---|
| Home → "Delete my sessions from Health Connect" | Every session PulseStages wrote: the ids in the ledger plus every PulseStages session found by reading its own data over the whole range (for example from before a reinstall; on Android 9–13 without history access only those from at most 30 days before the current grant, [3.4](#34-history)). It first cancels a running backfill and every recompute requested before, and marks the nights "Deleted by you"; automatic runs do not write them again, a backfill or recompute started afterwards does. |
| Night detail → "Delete my session" | That night's session, the same way. |
| Health Connect → "App permissions" → PulseStages → "Delete app data" | Everything PulseStages wrote in a chosen period; the same page can revoke the permissions ([Google help](https://support.google.com/android/answer/12201232)). |
| Uninstalling PulseStages | Its private storage and its permissions, **not** its sessions in Health Connect: Google lists an app that "is uninstalled but still has data stored in Health Connect" under "Inactive apps" ([Google help](https://support.google.com/android/answer/12201872)). Delete the sessions before uninstalling. |

None of these touches data of OHealth or any other app. [privacy.md §8](privacy.md#8-deleting-your-data) covers the
app's local data as well.

## 10. Sources

Google documentation:

- [Get started](https://developer.android.com/health-and-fitness/health-connect/get-started) (manifest, rationale
  activity, availability)
- [Read data](https://developer.android.com/health-and-fitness/health-connect/read-data) (pagination, history,
  background reads)
- [Write data](https://developer.android.com/health-and-fitness/health-connect/write-data) (metadata, derived data,
  client record version)
- [Sync data](https://developer.android.com/health-and-fitness/health-connect/sync-data) (Changes API)
- [Rate limiting](https://developer.android.com/health-and-fitness/health-connect/rate-limiting)
- [Aggregate data](https://developer.android.com/health-and-fitness/health-connect/aggregate-data) (priority)
- [Metadata reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/metadata/Metadata),
  [SleepSessionRecord reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/SleepSessionRecord),
  [HealthConnectClient reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/HealthConnectClient)
- Android Help: [Manage your Health Connect data](https://support.google.com/android/answer/12990553),
  [Delete your data from Health Connect](https://support.google.com/android/answer/12201232),
  [Find your Health Connect data](https://support.google.com/android/answer/12201872)

AOSP HealthFitness module (branch android16-qpr2-release; the same logic was checked in android14-release to
android17-release where PLAN.md says so): `TransactionManager.java`, `fitness/recordhelpers/RecordHelper.java`,
`storage/utils/StorageUtils.java`, `fitness/FitnessRecordDeleteHelper.java`, `HealthConnectServiceImpl.java`,
`common/changelog/ChangeLogsRequestHelper.java`, `ratelimiter/RateLimiter.java` in
[platform/packages/modules/HealthFitness](https://android.googlesource.com/platform/packages/modules/HealthFitness/).

Sleep as Android: [Health Connect](https://sleep.urbandroid.org/docs/services/health_connect.html),
[Awake detection](https://sleep.urbandroid.org/docs/sleep/awake_detection.html),
[Intent API](https://sleep.urbandroid.org/docs/devs/intent_api.html) (the "Request Backup Sync" broadcast
`com.urbandroid.sleep.REQUEST_SYNC`). Its import behaviour ([8.3](#83-sleep-as-android)) is not described in its
documentation; it was observed with Sleep as Android 20261006 and confirmed on the development phone, except where
8.3 says it is not yet confirmed. The Health Connect sync that its sync request starts is not described either: the
Intent API mentions only the backup.
