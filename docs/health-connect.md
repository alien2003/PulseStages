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
| `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` | special, feature-gated | Read other apps' data while the app is not in the foreground: the periodic (hourly by default) and daily runs. | Background runs read nothing and end as blocked (`BACKGROUND_PERMISSION_MISSING`, sync outcome `BACKGROUND_READ_BLOCKED`); runs started in the app run as a foreground service and still read. |

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
  `com.google.android.apps.healthdata` (so `getSdkStatus` can see it on Android 11–13), OHealth, and every app that
  declares one of the two rationale intents, i.e. every Health Connect client, so the Sources, Home and Compare
  screens can show the label and icon of the apps that wrote the data.
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
| `SleepSessionRecord` (own) | write verification; the per-run lookup of our records (also by a refresh whose input changed, to compare with the stored record); "Delete my sessions"; the delete fallback on Android 9–13 | PulseStages only | ±60 s around the session start; ±24 h around the run's nights; from the epoch to now; from the epoch to now + 24 h |
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
| `startTime` | start of the night: the start of its first source fragment |
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
| `RETRY_RECENT_MS` | 36 hours | nights that ended this recently are retried (without an estimate) or refreshed (written) by every run; late arrivals are retried or refreshed for this long after they were first seen |
| `LATE_ARRIVAL_MAX_MS` | 30 days | nights first seen at most this long after they ended are retried or refreshed with growing gaps |
| `MAX_REFRESH_NIGHTS_PER_RUN` | 8 | written nights whose input one run reads again; the rest are left to later runs ([7.2](#72-quotas)) |

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
every run therefore:

- retries the recent nights that could not be estimated yet (NOT_ESTIMATED, FAILED);
- refreshes the recent written nights: it reads their heart rate, steps and optional channels again with the same
  reads as an estimate and compares them with the fingerprint stored at the write (sample counts and SHA-256 digests).
  When the input is unchanged, nothing else happens. A changed input is estimated again, and the record is rewritten
  under the same `clientRecordId` with the version + 1 only when its stages, title or notes change; a night with
  fewer heart-rate samples (in the estimate) than at the write keeps its record (a source replacing its records must not make it worse
  for a while); a record deleted outside PulseStages is not written again. Nothing is refreshed under `NEVER`, and stale nights are left to a
  recompute.

Both use the window of the constants above: nights that ended in the last 36 hours on every run, late arrivals with
growing gaps; at most 8 written nights are refreshed per run
([architecture.md §6.7](architecture.md#67-what-a-sync-does), [§3.6](architecture.md#36-refreshing-written-nights)).
Heart rate that arrives later than that is used only by a recompute.

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

Every sync also refreshes recent written nights ([5](#5-changes-api)). Per checked night it reads the heart rate and
the steps (one call per page of up to 1000 records: usually one each, up to about three with dense heart rate and the
24 h left padding) and each enabled and granted optional channel; per refresh run one read of the sleep sessions
around the targets and, when an input changed, one read of PulseStages' own sessions around them; a rewrite adds the
insert and its verification read. `MAX_REFRESH_NIGHTS_PER_RUN` = 8 keeps this under 100 read calls per run even in
the worst case (8 nights with dense heart rate, three optional channels and a rewrite each: 8 × 9 + 2 + 8 = 82),
far below the background quota of 1000 calls per 15 minutes, and about 2000 a day with 25 runs, below 8000; usually
only the one or two sessions of the last 36 hours are due. A backfill that makes many recent nights due at
once is spread over several runs.

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
has no stages) and PulseStages' session with stages, titled from the template. Any app the user allows to read sleep
can read PulseStages' session, including its title and notes.

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
[architecture.md §3.2](architecture.md#32-building-candidates)) independently of Health Connect's list.

### 8.3 Sleep as Android

Sleep as Android can import third-party sleep from Health Connect (Settings → Services → Health Connect, marked beta in
its documentation; [its docs](https://sleep.urbandroid.org/docs/services/health_connect.html)). **How it handles two
overlapping sessions from different Health Connect apps is not documented** (it documents duplicate filtering only
for Samsung Health and Google Fit), and it has **not been verified on a device**. How it maps `STAGE_TYPE_UNKNOWN` on
import is not documented either. Its hypnogram has awake, REM, light and deep levels. The setting
"Write awake as light sleep" (`write.awakeAsLight`) exists for readers that show awakenings poorly; it changes the
written data and the params hash. The recommended setup is described in the [user guide](user-guide.md).

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

Sleep as Android: [Health Connect](https://sleep.urbandroid.org/docs/services/health_connect.html).
