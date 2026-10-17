# Privacy

PulseStages processes health data: sleep sessions, heart rate, steps and, if you allow them, blood oxygen,
respiratory rate and heart-rate variability. This document describes, from the code, what the app reads, what it
stores, what it writes, where data can leave the app and how to delete everything. In short:

- All processing happens on the phone. The app has **no `INTERNET` permission**; a test of the merged manifests
  fails if one appears.
- It reads only from Health Connect, and writes only its own sleep sessions there. It never changes or deletes
  another app's data.
- It keeps derived results (which nights it estimated, their stages and summary values) in its private storage. It
  does not store your raw heart rate, steps or other samples; it reads them from Health Connect again when needed. For
  each written night it keeps only a fingerprint of them (sample counts and one-way digests), to notice data that
  arrives late.
- Data leaves the app only through Health Connect, through files you export or share yourself, and through the system
  log (logcat). By default it also asks Sleep as Android, if installed, to sync after writing a night ("Ask Sleep as
  Android to sync", which you can turn off); that request carries no data.
- Its private data is excluded from Android backups and device transfer.

The same points, in shorter form, are on the app's own privacy screen "PulseStages and your health data" (Home →
"How PulseStages uses your data", or the privacy-policy link on Health Connect's permission screen). The app's texts
are available in English and Russian.

Contents:

- [1. What is processed](#1-what-is-processed)
- [2. Everything stays on the device](#2-everything-stays-on-the-device)
- [3. What is stored on the device](#3-what-is-stored-on-the-device)
- [4. What is written to Health Connect](#4-what-is-written-to-health-connect)
- [5. Logs](#5-logs)
- [6. Exports and sharing](#6-exports-and-sharing)
- [7. Backups and device transfer](#7-backups-and-device-transfer)
- [8. Deleting your data](#8-deleting-your-data)
- [9. Uninstalling and reinstalling](#9-uninstalling-and-reinstalling)
- [10. Debug builds and demo mode](#10-debug-builds-and-demo-mode)
- [11. Reporting a privacy problem](#11-reporting-a-privacy-problem)

## 1. What is processed

| Data | Read from | Used for | Kept by PulseStages |
|---|---|---|---|
| Sleep sessions of the sources you enable (by default OHealth) | Health Connect | finding the nights to estimate | the session ids, app package, start and end, and what happened to each session (ledger) |
| Heart rate around each night | Health Connect | the main input of the estimate | not the samples; derived values only: the night's lowest smoothed heart rate (for the rolling baseline), the share of the night with heart-rate data, the number of samples used, and for each written night the number of samples read and a SHA-256 digest of them (input fingerprint, [3](#3-what-is-stored-on-the-device)) |
| Steps around each night | Health Connect | movement input of the estimate | not the samples; for each written night only their number and a SHA-256 digest (input fingerprint) |
| SpO2, respiratory rate, HRV (optional; only when enabled in Settings and allowed) | Health Connect | optional inputs of the estimate | not the samples; for each written night only their number and a SHA-256 digest of each channel that was read (input fingerprint) |
| Sleep sessions of every app (Sources screen) | Health Connect | listing the apps that write sleep, with session counts and the date of the last one | no; only the sources you enable, their priority and manually added packages are saved in the settings |
| Sleep sessions with stages of other apps (Compare tab) | Health Connect | comparing PulseStages' estimate with them | no; "Calibrate" stores the resulting personal model (a few model parameters) |
| PulseStages' own sessions | Health Connect | verifying writes, avoiding duplicates, deleting them | the id, version and summary of each written night (ledger) |
| Names and icons of installed health apps | Android `PackageManager` | showing which app wrote the data | no |
| The phone's manufacturer and model | Android `Build` | the device field of the sessions PulseStages writes | written to Health Connect ([4](#4-what-is-written-to-health-connect)) |

The exact read ranges and filters are listed in [health-connect.md §3](health-connect.md#3-data-read), and the
permissions with the reason for each in [health-connect.md §2](health-connect.md#2-permissions).

## 2. Everything stays on the device

**No network access.** The app manifest removes the `INTERNET` permission even if a library asks for it:

```xml
<uses-permission
    android:name="android.permission.INTERNET"
    tools:node="remove" />
```

`MergedManifestTest` (`:app`) parses the merged debug **and** release manifests that the build produces and fails if
either requests `android.permission.INTERNET`. It runs with every `./gradlew test`, also in CI. Without this permission
the app cannot open network sockets, so it cannot send data anywhere by itself. The merged manifest does contain
`ACCESS_NETWORK_STATE`, added by WorkManager: it only lets the app read whether a network is available and does not
allow network connections ([health-connect.md §2.3](health-connect.md#23-other-android-permissions)).

**No third-party services.** The app's dependencies (`gradle/libs.versions.toml`) are AndroidX libraries (Compose,
Room, DataStore, WorkManager, Health Connect client, ...), Hilt, kotlinx coroutines and serialization, and test
libraries. There is no analytics, advertising or crash-reporting library.

**Package visibility.** The app declares `<queries>` for the Health Connect app, OHealth and the apps that declare a
Health Connect permission-rationale screen (that is, Health Connect clients), only to show their names and icons, and
for Sleep as Android, only to check that it is installed before the sync request (below). It does not request
`QUERY_ALL_PACKAGES` (also checked by `MergedManifestTest`).

**How data can still leave the app**, on your action, through Android, or through the sync request to Sleep as
Android, which is on by default:

- Health Connect: the sessions PulseStages writes can be read by every app you allow to read sleep
  ([4](#4-what-is-written-to-health-connect)).
- Files you export or share ([6](#6-exports-and-sharing)).
- The system log (logcat), which receives a copy of the app's log ([5](#5-logs)).
- With Settings → "Written sessions" → "Ask Sleep as Android to sync" (on by default) and Sleep as Android
  installed: a broadcast to Sleep as Android (`com.urbandroid.sleep.REQUEST_SYNC`, restricted to its package) after a
  run that wrote or updated a session, and once more about 10 minutes after a new or recomputed one. Nothing is sent
  when Sleep as Android is not installed. It carries no extras and no data: it tells Sleep as Android only that a sync
  is wanted, and with that when PulseStages wrote. Sleep as Android then reads Health Connect with its own
  permissions, like after its own "Sync now" ([health-connect.md §8.3](health-connect.md#83-sleep-as-android)). To
  stop it, turn the setting off.

What Health Connect and other apps do with data they hold is outside PulseStages' control.

## 3. What is stored on the device

Everything below is in the app's private storage, which other apps cannot read.

| Store | Location | Content |
|---|---|---|
| Room database (the ledger) | `databases/pulsestages.db` | `estimated_nights`: one row per estimated night or nap: the ids of its source sessions, the source app, start and end with time-zone offsets, the id of PulseStages' record in Health Connect, algorithm version, params hash, estimator, coverage, confidence, minutes per stage, the stages, flags and warnings, the lowest heart rate, the number of heart-rate samples, status and timestamps, and the input fingerprint with the time it was last checked (below). `night_sources`: night ↔ source session links. `source_sessions`: every source session seen (id, app, start, end, outcome). `night_baselines`: the lowest heart rate of each written session, for the rolling baseline. `personal_models`: personal models from "Calibrate" or the unsupervised adaptation. `backfill_state`: the progress of the history backfill. `log_entries`: the internal log ([5](#5-logs)). |
| Settings (DataStore) | `files/datastore/app_settings.json` | the settings that differ from the defaults: enabled sleep sources and their priority, schedule, title template, algorithm parameters, log level. No health data. |
| Sync state (DataStore) | `files/datastore/sync_state.preferences_pb` | the Health Connect changes token and the source packages it belongs to, when the last sync started and ended, its outcome and a short English detail (counters), when recent nights were last re-checked for late heart rate, a counter of the unsupervised adaptation, and, with "Ask Sleep as Android to sync", when a pending second sync request to Sleep as Android is due. |
| WorkManager's database | managed by WorkManager | the scheduled and recent background work: its type, when it was requested, the id of a night to recompute, and the result of finished work (counters, outcome, an English detail). |
| Shared log file | `cache/shared/pulsestages-log.txt` | only after you tap "Share log"; replaced by the next share; Android may delete cache files at any time. |
| Demo-mode switch (debug builds only) | shared preferences `pulsestages_demo_mode` | one on/off value. |

Raw heart-rate, step, SpO2, respiratory-rate and HRV samples are never stored. The night detail, the CSV export,
Compare and the personalization read them from Health Connect again each time
([architecture.md §4](architecture.md#4-the-room-ledger-and-other-local-state) describes the ledger for developers).

**Input fingerprint.** For each written night the ledger keeps a fingerprint of the input the night was estimated
from (`estimated_nights.inputFingerprint`): for the heart rate, the steps and each optional channel that was read, the
number of samples and a SHA-256 digest of the samples; and when that input was last checked (`inputCheckedAt`). It
lets later runs tell whether more data arrived after the night was written
([user guide, "Automatic runs"](user-guide.md#automatic-runs)). It does not contain the samples: a digest is one-way,
so the samples cannot be computed back from it; it can only confirm that a given set of samples is exactly the one that
was read. It is part of the ledger and is deleted with it when you clear the app's storage or uninstall the app
([8](#8-deleting-your-data)); the rows of deleted nights (below) keep it like their other values.

The ledger keeps rows of deleted nights (status "Deleted by you", "Source deleted" or "Replaced") so that automatic
runs do not write them again; they no longer have a record in Health Connect.

## 4. What is written to Health Connect

For every estimated night (and, depending on "Written for a nap", every nap) PulseStages writes one
`SleepSessionRecord`:

- the start and end of the source session, with time-zone offsets (with "Sleep as Android compatibility" the start is
  1 second earlier);
- the estimated stages (awake, light, deep, REM, unknown; for a nap written as "Asleep / awake" only awake and
  asleep);
- a title, by default "Sleep stages from heart rate (estimate)";
- notes in JSON: the estimator, night or nap, the algorithm version, the params hash, the heart-rate coverage, the
  night confidence, the Health Connect ids of the source sessions, the number of heart-rate samples, and whether the
  stage proportions were calibrated;
- metadata: recording method "unknown", and the phone (manufacturer and model) as the device.

The full format is in [health-connect.md §4](health-connect.md#4-data-written). These sessions are visible in Health
Connect under PulseStages, and **every app that you allow to read sleep data can read them**, including the title and
the notes. PulseStages writes nothing else: no heart rate, no steps, no other data type.

## 5. Logs

The app keeps an internal log for troubleshooting (Settings → Log).

- **What is logged.** Each line has a time, a level, the component and a message about processing: which runs
  started and how they ended, counters, why a night was or was not estimated (with its heart-rate coverage), why a
  night was updated after more data arrived (with its heart-rate sample counts before and after), the package names of
  the sleep sources, Health Connect errors with their English message and stack trace. Lines about a
  night carry the night's key (`ps:` and a hash of its source session ids). The code does not log heart-rate samples
  or the sequence of stages.
- **How much.** Lines below the level of Settings → Debugging → "Log level" (default Info) are not recorded. The
  database keeps the newest 10,000 lines; a message is cut at 8,000 characters.
- **Logcat.** Every recorded line is also written to the Android system log with the tag `PulseStages`. The system log
  can be read with `adb` when USB debugging is enabled on the phone.
- **Sharing.** "Share log" writes the lines of the selected levels to `cache/shared/pulsestages-log.txt` and opens the
  Android share sheet. The file is offered through the app's private `FileProvider`: only the app you pick gets a
  temporary read permission for that one file. Review the file before you post it anywhere public: it shows when the
  app ran, your sleep sources and identifiers of your nights.

## 6. Exports and sharing

| Action | What it contains | Where it goes |
|---|---|---|
| Settings → "Export JSON" | every setting, defaults included (no health data) | a document you choose through the Android file picker; suggested name `pulsestages-settings.json` |
| Night detail → "Export CSV" | one row per epoch of that night: raw and smoothed heart rate, the features, the stage probabilities, the stage and the mode. **This is health data.** It is computed again from Health Connect with the current settings. | a document you choose through the file picker; suggested name `pulsestages-yyyyMMdd-HHmm.csv` |
| Settings → Log → "Share log" | the log ([5](#5-logs)) | the app you pick in the share sheet |
| Settings → "Import JSON" | reads a settings file you choose; nothing is applied if it is invalid | |

PulseStages writes only to the document you pick and shares only the file you asked to share. Once a file is saved or
shared, it is outside the app's control; delete it yourself when you no longer need it.

## 7. Backups and device transfer

PulseStages' private data is never included in Android backups or device-to-device transfers:

- the manifest sets `android:allowBackup="false"`;
- `res/xml/data_extraction_rules.xml` (Android 12 and later) excludes the `root`, `file`, `database`, `sharedpref`
  and `external` domains from both cloud backup and device transfer;
- `res/xml/backup_rules.xml` (`android:fullBackupContent`, for older Android versions) excludes the same domains.

A new phone, a reinstall or a restore therefore starts with an empty ledger and default settings. The sessions
PulseStages wrote to Health Connect are Health Connect's data; whether and how Health Connect itself backs them up or
transfers them is governed by Health Connect, not by PulseStages.

## 8. Deleting your data

| Action | What is deleted | What stays |
|---|---|---|
| Home → "Delete my sessions from Health Connect" (asks for confirmation) | every sleep session PulseStages wrote to Health Connect: those in the ledger plus any PulseStages session found by reading its own data (for example from before a reinstall; on Android 9–13 without history access only those from at most 30 days before the current permission grant, [health-connect.md §3.4](health-connect.md#34-history)). A running backfill and pending recomputes are cancelled first. | the local ledger, which marks these nights "Deleted by you" so automatic runs do not write them again (a backfill or a recompute started afterwards does); settings; log |
| Night detail → "Delete my session" | that night's session in Health Connect | the same, for that night |
| Health Connect → "Permissions and data" → "App permissions" → PulseStages → "Delete app data", choose a period, then "Next" → "Delete" ([Google help](https://support.google.com/android/answer/12201232)) | PulseStages' data in Health Connect for that period; the same page can revoke all permissions | the local ledger. PulseStages is not notified of this deletion: its Nights list keeps showing these nights as "Estimated", and "Recompute" writes them again. The in-app deletion keeps both in step. |
| Clearing the app's storage in Android's app settings | the ledger, settings, sync state, personal models and log | the sessions in Health Connect. On later runs PulseStages finds its own records of a night it processes again and replaces or removes them, so the night does not get a second record ([architecture.md §5.6](architecture.md#56-reinstall-and-cleared-data)); on Android 9–13 only the records Health Connect lets it read ([health-connect.md §3.4](health-connect.md#34-history)). |
| Uninstalling | see [9](#9-uninstalling-and-reinstalling) | |
| Exported or shared files | nothing; delete them yourself | |

No action in PulseStages ever deletes or changes data of OHealth or any other app: Health Connect only lets an app
delete its own records, and PulseStages deletes only its own, by their `clientRecordId`
([health-connect.md §6](health-connect.md#6-deleting-records)). Copies that other apps made of PulseStages' sessions
are not deleted either: Sleep as Android, for example, keeps the nights it imported as its own records
([user guide](user-guide.md#sleep-as-android)). Delete them in those apps.

## 9. Uninstalling and reinstalling

- **Uninstalling** deletes the app's private storage (ledger, settings, sync state, log, personal models) and revokes
  its Health Connect permissions.
- It does **not** delete the sessions PulseStages wrote to Health Connect. Google lists an app that "is uninstalled
  but still has data stored in Health Connect" under "Inactive apps" in Health Connect → "Permissions and data" →
  "App permissions" ([Google help](https://support.google.com/android/answer/12201872)). To remove everything, use
  "Delete my sessions from Health Connect" before uninstalling, or delete the data in Health Connect afterwards.
- **After a reinstall** you grant the permissions again. Without the history permission, Health Connect then returns
  other apps' data only from 30 days before the new grant
  ([read data](https://developer.android.com/health-and-fitness/health-connect/read-data)). PulseStages' earlier
  sessions are still in Health Connect; when PulseStages processes those nights again it updates or replaces its own
  records instead of adding second ones. On Android 9–13 the 30-day limit also applies to PulseStages' own data: without
  history access it cannot see, replace or delete its sessions from more than 30 days before the new grant; delete
  them in Health Connect (→ "App permissions" → PulseStages → "Delete app data") if you do not want to keep them.
- The release and debug APKs are signed with different keys, so switching between them requires an uninstall, with
  the consequences above.

## 10. Debug builds and demo mode

Debug builds process your data exactly like release builds. In addition they have a demo mode (Settings → "Debug
build" → "Demo mode") that switches the Nights list, the night detail and Compare to synthetic nights generated on the
phone. In demo mode those screens read nothing from and write nothing to Health Connect or the ledger; background runs
and the Home actions keep working with your real data. The switch is stored in its own preferences file and is not
part of the settings or their export. Release builds contain no demo code (`ReleaseApkTest` checks the minified
release APK). The screenshots in the documentation are rendered from synthetic data, never from real health data
([SCREENSHOTS.md](SCREENSHOTS.md)).

## 11. Reporting a privacy problem

If you find that PulseStages reads, stores or exposes data in a way this document does not describe, please report it
privately as described in [SECURITY.md](../SECURITY.md).
