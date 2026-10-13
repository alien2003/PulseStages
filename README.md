<p align="center"><img src="docs/icon.svg" width="112" height="112" alt="PulseStages icon"></p>

# PulseStages

PulseStages is an Android app. It estimates sleep stages (awake, light, deep, REM) from the heart rate that a watch
writes to Health Connect, then writes them back to Health Connect as a separate sleep session labelled as an
estimate.

License: [WTFPL](LICENSE). Do whatever you want with the code.

- [What it does](#what-it-does)
- [How accurate it is](#how-accurate-it-is)
- [Screenshots](#screenshots)
- [Build](#build)
- [Install on the phone from WSL](#install-on-the-phone-from-wsl)
- [First run and permissions](#first-run-and-permissions)
- [Sleep sources and priority](#sleep-sources-and-priority)
- [What Sleep as Android and other readers will see](#what-sleep-as-android-and-other-readers-will-see)
- [recordingMethod and device](#recordingmethod-and-device)
- [Idempotency, versions and stale nights](#idempotency-versions-and-stale-nights)
- [Automatic runs](#automatic-runs)
- [Deleting your data](#deleting-your-data)
- [Settings, log, CSV export, Compare and calibration](#settings-log-csv-export-compare-and-calibration)
- [Training, validation and dataset attribution](#training-validation-and-dataset-attribution)
- [GitHub: CI and releases](#github-ci-and-releases)
- [Project structure](#project-structure)
- [Known limitations and ideas for the next version](#known-limitations-and-ideas-for-the-next-version)

## What it does

The app was built for an OPPO Watch X3 paired with OHealth (`com.heytap.health.international`). OHealth writes
`SleepSessionRecord`s to Health Connect with no stages, only a start and an end. It also writes a `HeartRateRecord`
sample about every 2 minutes.

For every night, PulseStages does the following:

1. It reads the sleep session and the heart rate around it from Health Connect: 30 minutes before and after, plus
   steps. It also reads SpO2, respiratory rate and HRV (RMSSD) when they exist and you allowed them.
2. It estimates AWAKE / LIGHT / DEEP / REM for every one-minute epoch. The default estimator is HYBRID: a trained
   classifier whose output is decoded with explicit bout durations. HMM and RULES can be chosen in Settings.
3. It writes **its own** `SleepSessionRecord` with the stages. That record:
   - has the same start and end as the source session;
   - is titled "Sleep stages from heart rate (estimate)" (translated into Russian on a phone set to Russian);
   - carries the algorithm version and a hash of every parameter in its `notes`, so the night can be recomputed
     later.

Rules it follows:

- It never modifies or deletes data of other apps. Health Connect only lets an app delete its own records, and
  PulseStages deletes only its own, by `clientRecordId`.
- It has no `INTERNET` permission. The manifest removes it even if a library asks for it, and `MergedManifestTest`
  checks the merged debug and release manifests.
- Sources are selectable. OHealth is the default, and any app that writes sleep sessions to Health Connect can be
  enabled. New nights are processed automatically. The whole history is processed on request (backfill).
- It runs on Android 9 and later (minSdk 28; targetSdk 36; compileSdk 37). Health Connect is part of Android 14+. On
  Android 9–13 it is the separate Health Connect app from Google Play.

## How accurate it is

The output is an estimate, not a measurement. Do not use it for medical decisions.

The estimators were validated leave-one-subject-out on the 31 polysomnography (PSG) nights of PhysioNet sleep-accel
(Walch et al.). The heart rate was thinned to one value every 2 minutes, as OHealth delivers it, and the worse of two
emulations was reported.

For the default HYBRID estimator (worse of the two emulations):

- Cohen's κ is 0.339 ± 0.169 (mean ± sd over subjects; 0.348 on the other emulation). Accuracy is 0.612 and
  macro-F1 is 0.522.
- The mean absolute error per night is about 33 min for DEEP, 41 min for REM and 19 min for AWAKE. The predicted
  stage shares match PSG closely (about 13–15 % DEEP, 20–24 % REM, 57–59 % LIGHT vs 14 / 23 / 57 % in PSG), thanks to
  the pre-registered proportion-matching correction (ALGORITHM.md §13.10).
- For comparison, the HMM scores κ 0.070 and RULES 0.076.
- Averaged over four training seeds, the setup without the proportion correction scores κ 0.317. Treat about 0.32–0.34
  as the expected figure.

That is "fair" agreement; ALGORITHM.md §14 compares it with published results. It varies strongly between people:
per-subject κ ranges from about −0.03 to 0.65, and single nights can be far off.

The training subjects were healthy adults wearing an Apple Watch (the Walch subjects were 29 ± 9 years old). An OPPO
watch, older people, sleep disorders or heart medication may behave differently.

These numbers come from [ALGORITHM.md §13.1](ALGORITHM.md#131-headline-walch-psg-ohealth-like-session-window). That
section is the authoritative source; if it differs from this paragraph, it wins. Limitations are in
[§16](ALGORITHM.md#16-limitations).

## Screenshots

These screenshots contain no real health data. They are rendered on the JVM by `ScreenshotsTest` (Robolectric native
graphics, light theme with the app's own palette, a 411 × 891 dp phone): the Nights list, the night detail and Compare
show the debug build's demo data (synthetic nights from core-algo's generator, estimated by the real algorithm with
the bundled model), and Home, Settings and Sources show fixed example states. To render them again after a change:
`./gradlew :app:testDebugUnitTest --tests '*ScreenshotsTest' -PrecordScreenshots`.
[docs/SCREENSHOTS.md](docs/SCREENSHOTS.md) explains this and how to take screenshots on a phone.

| Home | Nights | Night detail |
|---|---|---|
| ![Home screen](docs/screenshots/home.png) | ![Nights list](docs/screenshots/nights.png) | ![Night detail with hypnogram](docs/screenshots/night-detail.png) |

| Compare | Settings | Sources |
|---|---|---|
| ![Compare screen](docs/screenshots/compare.png) | ![Settings screen](docs/screenshots/settings.png) | ![Sources screen](docs/screenshots/sources.png) |

## Build

### Prerequisites

- Linux, or WSL 2 (Ubuntu) on Windows. Keep the project in the Linux file system (`~/…`), not under `/mnt/c`.
- [mise](https://mise.jdx.dev/) installed.

Everything else lives inside the project folder. Nothing is installed system-wide.

- `.mise.toml` provides:
  - Temurin 17 and Python 3.12 (with the venv `tools/train/.venv`);
  - `ANDROID_HOME` and `ANDROID_SDK_ROOT` set to `.tooling/android-sdk`, and `GRADLE_USER_HOME` set to
    `.tooling/gradle`;
  - `ADB_WIN`, and `PATH` entries for `sdkmanager` and `platform-tools`.
- The Android SDK lives in `.tooling/android-sdk`. It is installed from Google's command-line tools 23.0 with
  `sdkmanager`.
- Gradle runs only through the wrapper `./gradlew` (Gradle 9.8.1).
- `.tooling/` and `local.properties` are git-ignored.

### One-time setup

```sh
cd ~/PulseStages
mise trust          # only if mise asks to trust the project's .mise.toml
mise install        # Temurin 17 and Python 3.12

# Android command-line tools 23.0 (official zip; SHA-1 from Google's repository2-3.xml)
mkdir -p .tooling/dl .tooling/android-sdk/cmdline-tools
curl -fL -o .tooling/dl/cmdline.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
echo "e025545c62a8e64c7559119566a569fb1dec5f60  .tooling/dl/cmdline.zip" | sha1sum -c -
unzip -q .tooling/dl/cmdline.zip -d .tooling/dl/extract
mv .tooling/dl/extract/cmdline-tools .tooling/android-sdk/cmdline-tools/latest

# SDK packages (as listed in PLAN.md)
yes | mise exec -- sdkmanager --licenses
mise exec -- sdkmanager "platform-tools" "platforms;android-36" "platforms;android-37.0" \
  "build-tools;36.0.0" "build-tools;37.0.0"
```

The project was set up with cmdline-tools 23.0, platform-tools 37.0.1, platforms `android-36` and `android-37.0`,
and build-tools 36.0.0 and 37.0.0 (PLAN.md §1). compileSdk 37 uses the minor-versioned platform `android-37.0`. AGP
9.4 uses build-tools 36.0.0 by default. The platform-tools are not needed for installing on the phone (see below).
The `unzip` step needs the `unzip` tool.

### Build, test and lint

```sh
mise exec -- ./gradlew assembleDebug test ktlintCheck detekt
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

`./gradlew assembleRelease` writes `app/build/outputs/apk/release/app-release-unsigned.apk`. To get a signed release
build, see [GitHub: CI and releases](#github-ci-and-releases).

Local builds use versionName `0.1.0-dev` and versionCode 1. To override them, pass
`-PversionName=1.2.3 -PversionCode=10203`.

## Install on the phone from WSL

The phone is connected to Windows, so the Linux `adb` in `.tooling/android-sdk/platform-tools` cannot see it. Use the
Windows adb instead.

`.mise.toml` sets `ADB_WIN=adb.exe`, the Windows adb found on `PATH` through WSL interop. If it is not on your `PATH`,
set `ADB_WIN` to a path such as `/mnt/c/Users/<you>/AppData/Local/Android/Sdk/platform-tools/adb.exe`.

Inside a shell with the project environment (`mise exec -- bash`), install the APK and follow the log:

```sh
"$ADB_WIN" install -r "$(wslpath -w app/build/outputs/apk/debug/app-debug.apk)"
"$ADB_WIN" logcat -s PulseStages:V
```

**Do not use `./gradlew installDebug`.** It uses the Linux adb, which cannot see the phone.

The app's internal log is mirrored to logcat with the tag `PulseStages`, at or above the level set in Settings →
Debugging → Log level (default INFO).

A release APK cannot be installed over a debug APK, because they are signed with different keys. You must uninstall
first, which revokes all Health Connect permissions (see [Deleting your data](#deleting-your-data)).

## First run and permissions

Open the app. The **Home** tab shows the state of Health Connect and of every permission.

### Health Connect itself

- **Android 14 and later.** Health Connect is part of the system and is no longer on the home screen. Google gives
  the path as Settings → Security and privacy → Privacy controls → Health Connect. Variants are "Security & Privacy →
  Privacy → Health Connect" and "… → Permission manager → Health Connect". You can also search Settings for
  "Health Connect".
- **Android 9–13.** Health Connect is an app from Google Play. "Install or update Health Connect" opens its Play
  page.
- **Work profiles.** Health Connect does not work in work profiles.
- **From PulseStages.** "Open Health Connect" opens PulseStages' own permission page in Health Connect on Android 14+,
  or Health Connect's settings on older versions.

### Permissions

| Permission | Kind | Without it |
|---|---|---|
| `READ_SLEEP`, `WRITE_SLEEP`, `READ_HEART_RATE`, `READ_STEPS` | required ("Grant permissions") | Nothing can be estimated. Home shows "Required permissions: n of 4". |
| `READ_OXYGEN_SATURATION`, `READ_RESPIRATORY_RATE`, `READ_HEART_RATE_VARIABILITY` | optional | Not used. Home shows "Optional: used when present." |
| `READ_HEALTH_DATA_HISTORY` | for the backfill ("Allow history") | History is limited (details below). |
| `READ_HEALTH_DATA_IN_BACKGROUND` | for automatic runs ("Allow background") | Background runs are blocked (details below). |
| Notifications (Android 13+) | progress of backfill / recompute ("Allow notifications") | "Not allowed: the backfill runs without a progress notification." |

"Grant permissions" asks for everything still missing in one Health Connect dialog. The two special permissions are
only requested when the installed Health Connect supports them. If it does not, Home says "This Health Connect
version cannot grant it."

**Without history access.** Health Connect returns other apps' data only from 30 days before the app's first
permission grant ([read data](https://developer.android.com/health-and-fitness/health-connect/read-data)). The app
shows this as follows:

- Home shows "Not granted: Health Connect returns no data older than 30 days before PulseStages was first given
  access, so the history backfill is limited."
- A finished backfill reports "History was limited to 30 days before PulseStages was first given access (no access to
  history)."
- The Sources screen scans the last 90 days instead of the whole history.

**Without background access.** In the background, Health Connect would silently return only PulseStages' own data.
The app checks the permission and refuses to read instead of producing empty nights:

- Home shows "Not granted: new nights are only processed while the app is open."
- Hourly and daily runs that start while the app is not in the foreground read nothing. "Last run" then shows "no
  background access".
- Runs that you start in the app ("Estimate new", backfill, recompute) run as a foreground service with a
  notification.

**If you cancel the permission dialog twice,** Health Connect stops showing it. Home then says "Health Connect no
longer shows the permission dialog. Open its settings to grant the permissions." and offers "Open Health Connect
settings".

### Privacy screen

The rationale / privacy screen ("PulseStages and your health data") lists what is read and written and how to delete
it. You can open it in two ways:

- from Home → "How PulseStages uses your data";
- from the privacy-policy link on Health Connect's permission screen.

Health Connect finds this screen through `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` up to Android 13. On
Android 14+ it uses an `activity-alias` with `android.intent.action.VIEW_PERMISSION_USAGE` and
`android.intent.category.HEALTH_PERMISSIONS`. Without that alias, Android 14+ grants no health permissions.

## Sleep sources and priority

### The Sources screen (Settings → "Sleep sources")

- **What it lists.** Every app that wrote sleep sessions to Health Connect: in the last 90 days, or in the whole
  history with history access. Each entry has the app's icon and name, the number of sessions, the date of the last
  one and a switch.
- **Default.** Only OHealth (`com.heytap.health.international`) is enabled.
- **Priority.** The order of the list is the priority (up / down buttons). If two enabled apps recorded the same night
  (overlap of more than half of the shorter session), the higher one is used. The other is skipped as a lower-priority
  duplicate. This holds also when the higher-priority app recorded the night with its own sleep stages: that night
  needs no estimate, so PulseStages writes nothing for it, and an estimate it wrote earlier for the lower-priority
  app's session is removed (unless "Existing sessions" is "Never overwrite"). If that staged session is deleted later,
  the next run estimates the lower-priority session again. Under "Recompute everything" sessions with stages are
  estimated too, so the priority decides between the two like for any other night.
- **Manual package.** "Add an app by package name" adds a watch app that has not written sleep yet.
- **Heart rate and steps.** "Heart rate and steps from any app" is the setting `hrSources`. The default `SAME_AS_SLEEP`
  reads heart rate and steps only from the app that wrote the sleep session. `ANY` reads them from every app.
- **Own sessions.** PulseStages' own sessions are never used as a source.

### Which source sessions become nights

- A session that already has real stages (any stage other than UNKNOWN or SLEEPING) is skipped. The exception is the
  overwrite policy "Recompute everything".
- Fragments of one night from one app with a gap shorter than `mergeGapMin` (30 min) are merged into one night. The
  gap is written as AWAKE.
- A merged night shorter than `minSessionMin` (90 min) is treated as a nap and skipped.

### Health Connect's own "Data sources and priority"

The path is Health Connect → Permissions and data → Manage data → Data sources and priority
([Google help](https://support.google.com/android/answer/12990553)). What that list does
([aggregate data](https://developer.android.com/health-and-fitness/health-connect/aggregate-data)):

- Health Connect de-duplicates only the Activity and Sleep data types, and only inside `aggregate()`. There it keeps
  the data of the app with the highest priority.
- `readRecords()` returns the sessions of every app, overlapping ones included. Apps that list sessions must
  de-duplicate them themselves.
- An app removed from the list still writes data, but its data no longer counts in the totals.

**Recommendation:** for Sleep, move PulseStages above OHealth.

- Effect: sleep totals in Health Connect, and in any reader that uses `aggregate()`, are then taken from PulseStages'
  staged session instead of OHealth's session without stages.
- What it does not change: the session readers that call `readRecords()` see, and the sessions PulseStages reads
  itself.

## What Sleep as Android and other readers will see

### Sleep as Android imports

Sleep as Android can import third-party sleep from Health Connect
([its docs](https://sleep.urbandroid.org/docs/services/health_connect.html)):

- turn it on in Settings → Services → Health Connect (marked beta) and grant the permissions;
- tap Sync once; after that it syncs in the background.

### Two sessions for every night

For every estimated night, Health Connect contains two overlapping sleep sessions:

- OHealth's session, without stages;
- PulseStages' session, with stages, titled from the template (default "Sleep stages from heart rate (estimate)").

**Sleep as Android does not document how it handles two overlapping sessions from different Health Connect apps.** It
documents duplicate filtering only for Samsung Health and Google Fit. This has not been verified on a device yet. In
practice:

- After the first Sleep as Android sync, check its list for that night.
- If both sessions appear, delete the stage-less one inside Sleep as Android. That removes only Sleep as Android's
  copy: Health Connect lets an app delete only its own records, so OHealth's data stays. Whether a later sync imports
  it again is not documented.
- If Sleep as Android shows awakenings badly, turn on Settings → Written sessions → "Write awake as light sleep"
  (`awakeAsLight`). This changes the written data and the params hash.
- The stages can always be viewed in PulseStages' Nights tab.

### Stage mapping

These are the stages PulseStages writes, using only `SleepSessionRecord.STAGE_TYPE_*` constants:

| PulseStages | Health Connect stage type |
|---|---|
| AWAKE | `STAGE_TYPE_AWAKE` (1); `STAGE_TYPE_LIGHT` with `awakeAsLight` |
| LIGHT | `STAGE_TYPE_LIGHT` (4) |
| DEEP | `STAGE_TYPE_DEEP` (5) |
| REM | `STAGE_TYPE_REM` (6) |
| no data: heart-rate gap longer than `maxInterpGapMin` (10 min) | `STAGE_TYPE_UNKNOWN` (0) with the default `gapPolicy` UNKNOWN; LIGHT or the neighbouring stage with `gapPolicy` LIGHT / NEAREST |
| gap between merged fragments | AWAKE |

Rules for the written stages:

- They cover the session exactly, with no gaps or overlaps.
- They are sorted, and each is at least one epoch (60 s) long.
- Adjacent equal stages are merged.

Sleep as Android's hypnogram has Awake, REM, Light and Deep levels. How it maps `STAGE_TYPE_UNKNOWN` on import is not
documented.

### Title and notes

**Title.** It comes from Settings → Written sessions → "Session title" (`titleTemplate`), with the placeholders
`{estimator}`, `{version}` and `{confidence}` (night confidence in %). Leave it empty for the localized default,
which follows the phone's language at write time. This is a deliberate choice: the default of the specification,
in Russian, is what a phone set to Russian writes, and other languages get its English translation, like
every other text of the app. A night rewritten after a change of the phone's language gets the title in the new
language; to keep one title whatever the language, type it into the field. The title is not part of the params hash,
so changing it does not make nights stale.

**Notes.** They are compact JSON, for example:

```json
{"algo":"HYBRID","version":1,"paramsHash":"…","coverage":0.93,"confidence":0.61,"sourceUuids":["…"],"hrSamples":231,"calibrated":false}
```

| Field | Meaning |
|---|---|
| `algo` | estimator used |
| `version` | `ALGO_VERSION` |
| `paramsHash` | see [Idempotency](#idempotency-versions-and-stale-nights) |
| `coverage` | share of epochs with heart rate |
| `confidence` | night confidence |
| `sourceUuids` | Health Connect ids of the source sessions |
| `hrSamples` | heart-rate samples in the session |
| `calibrated` | whether the stage proportions were calibrated (`calibrateProportions`) |

## recordingMethod and device

### What is written

PulseStages writes its records with:

```kotlin
Metadata.unknownRecordingMethod(
    clientRecordId = …, clientRecordVersion = …,
    device = Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL),
)
```

The code is `HcRecordMapper.toRecord`, with the device from `PlatformInfo.CURRENT` in `HealthConnectRepository`
(module `:data-hc`).

### What the official documentation says

The
[Metadata reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/metadata/Metadata)
defines four recording methods:

| Recording method | Official definition |
|---|---|
| `RECORDING_METHOD_ACTIVELY_RECORDED` | "Data actively recorded by the user" |
| `RECORDING_METHOD_AUTOMATICALLY_RECORDED` | "For data recorded passively by a device without user explicitly initiating the recording, or whenever it cannot be determined" |
| `RECORDING_METHOD_MANUAL_ENTRY` | "Data manually entered by the user" |
| `RECORDING_METHOD_UNKNOWN` | "Unknown recording method" |

A device is mandatory for actively and automatically recorded data, and optional for manual entries and the unknown
method. The [write-data guide](https://developer.android.com/health-and-fitness/health-connect/write-data) says:
"When writing data that has been imported or derived from another source, you are expected to correctly attribute its
origin and source device metadata." No recording method exists for data computed by an algorithm.

### Why UNKNOWN and the phone

- `AUTOMATICALLY_RECORDED` with the watch as the device would tell readers that a watch measured these stages. No
  sensor measured them: the phone inferred them from heart rate.
- The "cannot be determined" clause does not fit either. It is known exactly how the record was produced.
- `UNKNOWN` makes no claim that a sensor recorded the stages.
- The device is the phone that computed and wrote the record.

The origin is still attributed:

- `notes.sourceUuids` lists the source sessions, whose own metadata name OHealth and its device;
- the title says "estimate";
- `notes` carry the algorithm version and the params hash.

A reader that filters by recording method or device type may treat these records differently from watch data. This
has not been tested with readers.

## Idempotency, versions and stale nights

### Record keys

- **`clientRecordId`** is `"ps:" + SHA-256 hex` of the source session uuids, de-duplicated, sorted and joined with
  `,` (`NightKeys.clientRecordId`). The same source sessions always give the same key, so one night has one output.
- **`clientRecordVersion`** is `ALGO_VERSION · 1000 + recompute count`, so 1000, 1001, … while `ALGO_VERSION` is 1.
  It is never below the version already stored in Health Connect + 1.

### Upsert, checked in the AOSP sources

This is documented in PLAN.md §4 (AOSP HealthFitness `RecordHelper` / `TransactionManager`, identical in
android14…android17):

- On Android 14+, inserting a record with a known `clientRecordId` updates the stored row in place when the new
  version is ≥ the stored one.
- A lower version is silently ignored.
- The documentation itself only promises that the higher version wins.

PulseStages reads every write back and compares the version and the number of stages (`VerifiedUpsert`). If the
read-back does not match, it deletes its record by `clientRecordId`, inserts it again and verifies once more. A
mismatch happens when a higher stale version is stored, or when the Health Connect app on Android 9–13 ignored the
insert. That app is closed source, so only the documented "higher version wins" applies there.

### The ledger

A Room database keeps one row per night in `estimated_nights`, linked to all its source uuids in `night_sources`, plus
`source_sessions`. Each night row holds the `clientRecordId`, algorithm version, params hash, coverage, confidence,
status and timestamps.

This ledger handles the cases where source sessions change:

- **New fragment.** A fragment that joins a night gives a new set of uuids, and with it a new `clientRecordId`. The
  old output is deleted (only our own record) and marked "Replaced".
- **Deleted sources.** If every source session of a night was deleted in Health Connect, our record is deleted and
  the night shows "Source deleted". If only some were deleted, the night is rebuilt from the rest.
- **Reinstall or cleared data.** The ledger is gone in that case (backups are off). Our earlier records of the same
  night are found by reading our own data and deleted, so a night never ends up with two outputs.

### Params hash and stale nights

**`paramsHash`** is the SHA-256 of:

- all algorithm parameters;
- the bundled model's fingerprint;
- the personal model's fingerprint;
- the input and output options that change the result (`hrSources`, which optional channels are read,
  `awakeAsLight`).

**Stale** means that a written night was produced with another algorithm version or another params hash than the
current settings. Stale nights are shown in these places:

- the "Stale" marker in Nights;
- the "stale" counter on Home;
- Home → "Recompute stale nights";
- the Recompute offer that Settings shows after a change.

### Overwrite policy

Overwrite policy is Settings → Written sessions → "Existing sessions" (`overwritePolicy`):

| Option | Behaviour |
|---|---|
| Skip up-to-date nights (`SKIP_SAME_VERSION`, default) | Nights with the current version and hash are skipped. Stale nights are marked, never recomputed silently, and recomputed only when you ask. |
| Recompute everything (`RECOMPUTE_ALL`) | Every run estimates the nights it reads again and rewrites them. Source sessions that already have stages are included. |
| Never overwrite (`NEVER`) | An existing PulseStages session is never rewritten, not even by Recompute. Only nights without a session are estimated. |

If a source session's boundaries change, the night is recomputed under every policy except `NEVER`.

## Automatic runs

All runs use WorkManager.

- **Hourly check.** It runs every `periodicHours` (default 1), only when the battery is not low
  (`runOnlyWhenNotLowBattery`, default on). It asks the Changes API for new, changed or deleted sleep sessions of the
  enabled sources. The changes token is stored in DataStore.
  - It re-reads the last 7 days instead in these cases: on the first run, after the sources changed, or when the token
    expired or was not advanced for 25 days.
  - The re-read reaches back further, to the last successful sync, but at most 30 days.
- **Daily run.** It runs at "Daily run" (`dailyTimeMinutes`, default 09:00 local time), with the same battery
  constraint. The time is approximate, because Android may delay background work.
- **"Estimate new"** on Home runs the same check immediately, as expedited work with a notification.
- **Retries.** After each complete run, recent nights that could not be estimated yet are tried again, because their
  heart rate may arrive after the session.
- **"Backfill history"** processes the whole history, oldest first, in 7-day windows:
  - only the windows read sessions: the start of the range is found with one Health Connect request for a single
    record (the oldest session);
  - it shows progress ("Week x of y") and can be cancelled;
  - it continues after a restart ("Resume backfill");
  - its range is "History depth" (`backfillDays`; 0 = all available history);
  - it writes nights that you deleted before you started it again, but not a night you delete while it runs.
- **Recompute.** Home → "Recompute all" (with confirmation) or "Recompute stale nights", or "Recompute" on a single
  night. A recompute also writes a night you deleted before asking for it again, but not one you delete while it is
  pending or running.
- **Errors.** Health Connect quota errors and transient errors are retried with exponential backoff. WorkManager's
  backoff starts at 30 s and doubles up to 5 h. A failure on one night is logged and does not stop the others.

## Deleting your data

- **All sessions.** Home → "Delete my sessions from Health Connect" asks for confirmation, then:
  - deletes by `clientRecordId` every session in the ledger, plus any PulseStages session found by reading its own
    data (for example from before a reinstall);
  - cancels a running backfill and every recompute asked for before (a running one stops before its next night), so
    neither can write the erased nights again;
  - marks the nights "Deleted by you".

  Automatic runs do not write deleted nights again; a backfill or a recompute started afterwards does.
- **One night.** Nights → a night → "Delete my session" works the same way for that night.
- **In Health Connect.** Use Health Connect → Permissions and data → App permissions → PulseStages → Delete app data,
  choose a period, then Next → Delete ([Google help](https://support.google.com/android/answer/12201232)). The same
  page can revoke every permission.
- **Uninstalling** PulseStages deletes its private storage (ledger, settings, log) and revokes its permissions. **It
  does not delete its sessions from Health Connect.** Google's help lists an app that is "uninstalled but still has
  data stored in Health Connect" under Inactive apps, in Health Connect → Permissions and data → App permissions
  ([Google help](https://support.google.com/android/answer/12201872)). Delete the sessions before uninstalling.
- **OHealth's data** is never touched by any of these.

## Settings, log, CSV export, Compare and calibration

### Settings

- **The Settings tab** holds every parameter, grouped, each with a hint. On the unfolded screen they are shown in two
  columns. The groups are:
  - sources, schedule, written sessions;
  - algorithm, preprocessing, features;
  - rules, HMM emissions / transitions / priors, hybrid;
  - post-processing, personalization, debugging.
- **"Export JSON"** saves every setting, defaults included, through the system file picker (suggested name
  `pulsestages-settings.json`).
- **Stored settings** keep only the values you changed. The defaults that come from the open-dataset validation
  (estimator, hybrid decoder settings, ...) therefore follow app updates for everything you never changed; a value
  you changed stays as you set it.
- **"Import JSON"** validates the file first. If anything is wrong, nothing changes and every problem is listed.
  Unknown keys are ignored and missing keys take their defaults.
- **"Reset to defaults"** resets everything; "Reset to physiology table" resets only the HMM emissions.
- **"Versions and licences"**, the last card, shows the app version, the algorithm version, the id of the bundled
  model, the licence and the dataset attribution notice (see
  [Training, validation and dataset attribution](#training-validation-and-dataset-attribution)).
- **After changing an algorithm parameter,** Settings says how many nights are now stale and offers to recompute them.
- **Log** (Settings → Log) shows the internal log with a filter by level. "Share log" sends `pulsestages-log.txt`
  through the share sheet.

### Night detail

The detail screen of a night shows:

- the hypnogram as written;
- the heart-rate curve;
- the probabilities and features of an epoch when you tap or drag on the charts;
- the warnings.

Its actions:

- "Compare with RULES" shows how far the simple threshold estimator agrees (kappa, accuracy).
- "Export CSV" saves `pulsestages-yyyyMMdd-HHmm.csv` with the columns
  `epoch,start_ms,has_data,hr_raw,hr_smooth,<features…>,p_awake,p_light,p_deep,p_rem,stage`.

The curves, probabilities and CSV come from a fresh estimate with the current settings. Nothing is written by this,
and the result can differ from what was written.

### Compare and calibration

**Reference nights** are sessions with real stages that another app wrote to Health Connect, for example another
watch, a ring or Sleep as Android. OHealth's stage-less sessions cannot be references.

The Compare tab:

- searches 90 days, 1 year or the whole history;
- estimates the same nights from heart rate;
- shows kappa, accuracy, macro F1, the mean error in minutes, per-stage accuracy / precision / F1, a confusion matrix
  and a per-night list.

**"Calibrate"** fits a personal model on the reference nights (details in
[ALGORITHM.md §10](ALGORITHM.md#10-personalization-personal)):

- It adjusts the classifier's per-stage offsets for HYBRID and the emission means for the HMM.
- The latest 30 % of the nights are held out, and Compare shows kappa before → after on them.
- The model is stored in Room and used for every later estimate. Earlier nights become stale.
- It needs Settings → Personalization → "Personal model" (on by default).

**"Reset to global model"** switches the personal model off.

**Unsupervised adaptation.** Without reference nights, the HMM emissions are adapted to your nights every 30 new
nights. This runs only when the HMM is actually used: estimator HMM, or HYBRID falling back to it. With the default
HYBRID estimator it does not run.

## Training, validation and dataset attribution

- **Code.** Python in [tools/train/](tools/train/README.md) downloads the datasets, emulates OHealth's 2-minute heart
  rate, trains the hybrid model and runs the leave-one-subject-out validation. Features and decoders are always
  computed by `:algo-cli`, the same Kotlin code that runs on the phone.
- **Method and numbers.** The protocol is in [ALGORITHM.md](ALGORITHM.md) §12, the numbers in §13 and the retraining
  commands in §17.
- **Tests.** `mise exec -- pip install -r tools/train/requirements.txt`, then
  `mise exec -- python -I -m pytest tools/train/tests -q`. They use synthetic data only.
- **Datasets** ([DATASETS.md](tools/train/DATASETS.md)):
  - PhysioNet **sleep-accel 1.0.0** (Walch et al. 2019): PSG labels, the headline validation;
  - PhysioNet **BIDSleep 1.0.1** (Song et al. 2026): EEG-headband labels, extra training data and a second
    validation set;
  - both are open access under ODC-By 1.0;
  - DREAMT is not used, because its access is restricted.
- **Raw data** are never committed.

The bundled model `app/src/main/assets/models/hybrid_model.json` is a produced work of both datasets. Attribution
notice:

> Contains information from *Motion and heart rate from a wrist-worn wearable and labeled sleep from
> polysomnography* (https://physionet.org/content/sleep-accel/1.0.0/) and from *BIDSleep: A Multi-Night
> Instantaneous Heart Rate and Accelerometry Dataset with EEG Sleep Stage Labels*
> (https://physionet.org/content/bidsleep-dataset/1.0.1/), which are made available under the ODC Attribution
> License (https://opendatacommons.org/licenses/by/1-0/).

Citations:

- Walch O. (2019), sleep-accel v1.0.0, PhysioNet, https://doi.org/10.13026/hmhs-py35
- Walch O, Huang Y, Forger D, Goldstein C. Sleep 42(12):zsz180 (2019), https://doi.org/10.1093/sleep/zsz180
- Song T, Zhang Y, Zhou Z, Dutta J. (2026), BIDSleep v1.0.1, PhysioNet, https://doi.org/10.13026/rees-1092
- Song TA et al. IEEE TBME 73(4):1596–1608 (2026), https://doi.org/10.1109/TBME.2025.3612158
- Pollard T et al. PhysioNet as a global platform for biomedical research, Nature Health (2026),
  https://doi.org/10.1038/s44360-026-00096-z

## GitHub: CI and releases

### CI

The CI workflow (`.github/workflows/ci.yml`) runs on every push and pull request.

| Job | What it does |
|---|---|
| `build` | Sets up Temurin 17 and Gradle, installs missing SDK packages and runs `./gradlew ktlintCheck detekt test assembleDebug`. Uploads the debug APK as the artifact `pulsestages-debug-apk`, and the test / lint reports on failure. |
| `python` | Sets up Python 3.12, installs `tools/train/requirements.txt` and runs `python -I -m pytest tools/train/tests -q`. The tests need no datasets and no algo-cli build. |

### Release

The release workflow (`.github/workflows/release.yml`) runs on tags `v*`. The tag must have the form
`vMAJOR.MINOR.PATCH`.

- **versionName** is the tag without the `v`.
- **versionCode** is `MAJOR · 10000 + MINOR · 100 + PATCH`. MINOR and PATCH must be ≤ 99. For example, `v1.4.2` gives
  versionCode 10402.

The workflow then:

1. decodes the keystore;
2. builds `assembleRelease` signed;
3. verifies the APK with `apksigner`;
4. publishes a GitHub release "PulseStages <version>" with `PulseStages-<version>.apk` and generated notes.

It needs four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the release keystore, base64 without line breaks |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias in the keystore |
| `KEY_PASSWORD` | key password; for a PKCS12 keystore this is the keystore password |

**Create the keystore** outside the repository with the JDK's keytool:

```sh
mkdir -p ~/keys
mise exec -- keytool -genkeypair -v -storetype PKCS12 -keystore ~/keys/pulsestages-release.jks \
  -alias pulsestages -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 ~/keys/pulsestages-release.jks > ~/keys/pulsestages-release.jks.b64
```

For a PKCS12 keystore, keytool uses the store password for the key too. It ignores a different `-keypass` with a
warning, so `KEY_PASSWORD` = `KEYSTORE_PASSWORD`.

**Add the secrets** in the GitHub UI, or with the GitHub CLI:

```sh
gh secret set KEYSTORE_BASE64 < ~/keys/pulsestages-release.jks.b64
gh secret set KEYSTORE_PASSWORD        # prompts for the value
gh secret set KEY_ALIAS --body pulsestages
gh secret set KEY_PASSWORD
```

**Release** by pushing a tag: `git tag v0.1.0 && git push origin v0.1.0` gives versionName 0.1.0 and versionCode 100.

**Never commit the keystore or its base64.** `.gitignore` excludes `*.jks`, `*.keystore` and `keystore.properties`,
but not a `.b64` file, so keep them outside the repository. Back the keystore up: updates of an installed release
must be signed with the same key.

**Local signed build.** Set the four signing variables, then run Gradle with the configuration cache off, as the
workflow does:

```sh
export PULSESTAGES_KEYSTORE_PATH=~/keys/pulsestages-release.jks
export PULSESTAGES_KEYSTORE_PASSWORD=…
export PULSESTAGES_KEY_ALIAS=pulsestages
export PULSESTAGES_KEY_PASSWORD=…
mise exec -- ./gradlew --no-configuration-cache assembleRelease -PversionName=0.1.0 -PversionCode=100
```

This gives `app/build/outputs/apk/release/app-release.apk`. Without all four variables, the release build stays
unsigned.

## Project structure

| Module / folder | Contents |
|---|---|
| `:core-algo` | Pure Kotlin/JVM: preprocessing, features, RULES / HMM / HYBRID estimators, post-processing, metrics, synthetic night generator, personalization, params hash, CSV export |
| `:algo-cli` | Command-line front end of `:core-algo` used by `tools/train` (`features`, `evaluate`, `estimate`) |
| `:data-local` | Room ledger (`estimated_nights`, `night_sources`, `source_sessions`, `night_baselines`, `log_entries`, `backfill_state`, `personal_models`), DataStore settings and sync state, logger |
| `:data-hc` | `HealthRepository` / `HealthConnectRepository`, night pipeline, Changes-API sync, backfill engine, data eraser, source scanner, use cases; a fake repository for tests |
| `:work` | `EstimateNewNightsWorker`, `BackfillWorker`, `RecomputeWorker`, scheduling, notifications |
| `:app` | Compose Material 3 UI, Hilt, navigation, manifest, rationale activity, debug-only demo mode |
| `tools/train/` | Python training and validation |
| `docs/` | [INTERCHANGE.md](docs/INTERCHANGE.md) (Kotlin ↔ Python formats), [SCREENSHOTS.md](docs/SCREENSHOTS.md), `icon.svg` |

Dependencies point only downwards: `app → work → data-hc → data-local → core-algo`, and `algo-cli → core-algo`. A
module may also depend directly on any module below it. `core-algo` depends on no other module and on nothing
Android.

These checks enforce the rule:

- **`:verifyModuleGraph`** (root `build.gradle.kts`) rejects any project dependency outside the allowed edges, and any
  Android plugin on `:core-algo`. It runs with `check` and before `:core-algo:test`, so `./gradlew test` enforces it.
- **`:core-algo:verifyPureJvmRuntimeClasspath`** fails if `androidx.*`, `com.android.*` or `com.google.android.*`
  artifacts reach the runtime classpath of `:core-algo`.
- **`NoAndroidImportsTest`** rejects Android imports in `:core-algo` sources.
- **`MergedManifestTest`** (`:app`) checks the merged manifests: no `INTERNET`, the declared health permissions, the
  rationale activity and the Android 14+ alias, and the debug-only demo entry.
- **`ResourcesParityTest`** (`:app`) requires every string of every module (`:app`, `:work`, `:data-hc`, all source
  sets) in English and Russian, with the same format placeholders and the plural forms of each language.
- **`RealAppGraphTest`** (`:app`, Robolectric) starts the production Hilt graph: the startup schedule, WorkManager's
  `HiltWorkerFactory` creating every worker, and the chain down to `HealthConnectRepository` and the bundled model
  read through the app's assets. **`EndToEndWiringTest`** drives the ViewModels through the real graph with only
  Health Connect replaced by the in-memory fake.
- **`ScreenshotsTest`** (`:app`, Robolectric native graphics) renders the six screens of the
  [Screenshots](#screenshots) section on every test run.

## Known limitations and ideas for the next version

### Algorithm

The full list is in [ALGORITHM.md §16](ALGORITHM.md#16-limitations).

- **Signal.** The only signal is heart rate every 2 minutes, with no HRV or accelerometer from OHealth. Agreement with
  PSG is "fair" (κ ≈ 0.32–0.34), varies strongly between people and is weakest for wake inside the night.
- **Stage proportions.** On the validation data the proportion correction brings the stage shares close to PSG, but it
  was fitted on young healthy adults; on BIDSleep it costs about 0.03 κ. 2–7 of the 31 validation nights are still
  flagged low-confidence.
- **Training data.** The model was trained on Apple Watch heart rate from healthy adults (Walch: 29 ± 9 years). OPPO's
  2-minute values may be computed differently. Older people, sleep disorders, beta blockers, arrhythmias and athletes are not
  represented.
- **Session boundaries.** The validation used session boundaries derived from PSG. Real OHealth boundaries come from
  the watch, and a late start or early end costs about 0.01–0.04 κ.
- **Untested on real data.** The elevated-heart-rate rule and personalization could not be validated on the datasets.
  There is no absolute clock time, so there are no circadian features.

### App

- **Tests.** There are JVM unit tests only, with no instrumented or device UI tests. The only target device so far
  runs Android 16. The Android 9–13 path (Health Connect app from Play) has not been run on a device.
- **Sleep as Android.** How it handles OHealth's and PulseStages' overlapping sessions is not verified.
- **OHealth.** It is unknown whether OHealth writes SpO2 or respiratory rate at night. The app works either way.
- **Scheduling.** Background times are approximate; Android may delay WorkManager work.
- **targetSdk.** It is 36, the level of the test phone. Raising it to 37 is one line in `gradle/libs.versions.toml`.

### Ideas

- Verify the Sleep as Android behaviour on the device, and document it.
- Add instrumented tests against a real Health Connect.
- Train with jittered session windows.
- Freeze the pedometer on data outside the evaluation, such as the BIDSleep accelerometer.
- Use the full per-subject nested selection.
- Evaluate further datasets (ECG-derived heart rate, NSRR cohorts).
- Move to detekt 2 once it is stable.
