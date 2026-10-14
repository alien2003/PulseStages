# On-device end-to-end suite

Tests the whole app on a real phone, against the app that is **already installed** there and the user's real Health
Connect data, without polluting Health Connect. The suite lives in `app/src/androidTest/kotlin/…/e2e/`; the runner
`run-device-tests.sh` builds and installs only the test APK and runs the test classes one by one.

- [What is tested](#what-is-tested)
- [Safety guarantees](#safety-guarantees)
- [Every Health Connect call the suite can make](#every-health-connect-call-the-suite-can-make)
- [What else changes on the phone](#what-else-changes-on-the-phone)
- [Remaining risks](#remaining-risks)
- [How to run](#how-to-run)
- [What you have to do](#what-you-have-to-do)
- [Reports](#reports)
- [How it works](#how-it-works)

## What is tested

The runner runs these steps in this order, each as its own `am instrument` call; `CleanupE2eTest` always runs in the
runner's exit trap, and when a step fails the trap also takes the "after" snapshot and compares.

| Step | Class | What it does | Writes to Health Connect |
|---|---|---|---|
| 1 | `E2eSnapshotBeforeTest` | Snapshot: the app's own sleep sessions of all history (own-package DataOrigin filter: id, clientRecordId, version, lastModifiedTime, stages hash, title, notes hash); every other app's sleep sessions of all history, heart rate and steps of the last 30 days (per package: count and SHA-256 over the sorted id/lastModified pairs) | no |
| 2 | `ScheduleReadOnlyE2eTest` | The app's WorkManager works: state, next run, constraints, intervals | no |
| 3 | `ShadowPipelineE2eTest` | The app's whole pipeline over the real history with nothing written (below) | no |
| 4 | `RealWriteE2eTest` | The only real write: one test record, upserts and delete, gone again within a minute (below) | yes, one record in `ps-e2e:` |
| 5 | `ReadOnlyUiTourE2eTest` | Every screen of the real app, read-only (below) | no |
| 6 | `E2eSnapshotAfterTest` | The snapshot again, over exactly the ranges of step 1 | no |
| 7 | `E2eCompareTest` | Before vs after: other apps' records unchanged, the app's own records byte-identical, no `ps-e2e:` record left | no |
| trap | `CleanupE2eTest` | Deletes every `ps-e2e:` record and verifies that none is left | only deletes `ps-e2e:` |

**Shadow pipeline (step 3).** The real `HealthConnectRepository` serves the reads; `ShadowWriteRepository` captures
every write and delete in memory and never forwards one (the app's own namespace is simulated, starting empty like a
fresh install). The ledger is an in-memory Room database of the app's own database class, the settings and sync-state
DataStores are temporary files below the app's cache directory, the estimator is the real SleepStager adapter with the
bundled model. The user's settings are copied read-only from the app's settings file. Scenarios:

1. the backfill engine over the history with the user's settings; every captured write must pass `WriteInvariants`
   (stages sorted, contiguous, exactly inside the session, at least one epoch each, only `STAGE_TYPE_*` values the
   app writes; clientRecordId `ps:` + sha256 of the source uuids; version `ALGO_VERSION·1000 + n`; the notes' JSON
   keys and values; the title) and the ledger must agree with the simulated Health Connect (one output per night);
2. idempotency: a second backfill and a Changes-API sync on the same ledger must write and delete nothing;
3. "recompute all": every written night rewritten exactly once, with clientRecordVersion + 1;
4. the history again with the default settings;
5. policies on the most recent weeks: naps SKIP and SLEEP_WAKE, `awakeAsLight`, `gapPolicy` LIGHT, `hrSources` ANY.

It reports per session: local date, start, length, NIGHT / NAP, outcome, estimator, coverage, confidence, stage
shares, warnings, heart-rate samples and their median interval, the estimate's time, and whether the result equals the
record the app really wrote for that night.

**Real write (step 4).** A real night of the enabled sources that ended at least 30 days ago and that no record of the
app overlaps is estimated from the real data before anything is written. Then, with clientRecordId
`ps-e2e:<random uuid>` and a title starting with "[PulseStages E2E test record, deleted within a minute]":
insert v1 (exactly one record with that id; stages, title, notes and zone offsets as written) → the same v1 again
(still one, unchanged) → v2 with other stages (one record, version 2, the new stages, the same metadata.id) → a plain
`insertRecords` of v1 (Health Connect ignores the lower version) → the app's verified write of v1 (it replaces the
stale higher version: delete and insert) → delete by clientRecordId → none left. The record's lifetime is asserted
under 60 s. Without a suitable night the test is skipped and nothing is written.

**UI tour (step 5).** MainActivity of the installed app with its own Hilt graph and data: Home (Health Connect available,
every permission granted, the counters), the privacy page (PermissionsRationaleActivity), Nights (every night of the
app listed, the All / Nights / Naps filter), one night's detail with the epoch panel and "Compare with RULES", Compare,
Settings with every group expanded and collapsed again, Sources, Log, About, demo mode on (Nights shows synthetic
nights) and off again. Screenshots of every screen. Any crash of the app fails it.

## Safety guarantees

- **Nothing but `ps-e2e:` can be written or deleted.** Tests never hold the raw repository: `E2eHealth` builds it and
  wraps it at once in `SafetyGuardRepository`, which forwards a write or delete only when every clientRecordId starts
  with `ps-e2e:` and throws `SafetyViolation` (an Error, so the pipeline's per-night `catch (e: Exception)` cannot
  swallow it) otherwise. The app's own records (`ps:` + sha256) and other apps' records can therefore not be written or
  deleted; Health Connect would not allow the latter anyway. Deletes are by clientRecordId only, never by time range;
  "Delete my sessions" (`MyDataEraser`) is never called. Only `SleepSessionRecord` is ever written.
- **The shadow pipeline writes nothing.** Its writes end in `ShadowWriteRepository`; the test asserts that the guard
  forwarded no write and refused none.
- **Every real write is preceded by a snapshot** (`RealWriteE2eTest` refuses to run without the "before" snapshot of the
  same run) **and followed by its removal:** the id is recorded on the phone before the first insert, the record is
  deleted at the end, again in `finally`, and again by the runner's cleanup step, which reads the app's own sessions
  of all history with the own-package DataOrigin filter and deletes every `ps-e2e:` id it finds. A verification read
  must find none. `cleanup-only` mode does the same at any time.
- **The app's own nights stay byte-identical**, checked by `E2eCompareTest` (and by the real-write and UI tests on
  their own): same metadata.id, clientRecordVersion, lastModifiedTime, start, end, zone offsets, stages, title, notes.
- **The app is never installed, updated, downgraded, cleared, uninstalled or stripped of a permission.** The runner
  installs only `io.github.alien2003.pulsestages.test` (`adb install -r -t`), checks that the app's versionCode is
  10099 and that it is signed with the same certificate as the test APK (your `~/.android/debug.keystore`), and at the
  end uninstalls only the test package. It never uses `connectedAndroidTest` or `installDebug`.
- **The app's data stay as they were.** Its database and DataStore files are never opened for writing: the settings
  file is read as bytes and its SHA-256 is checked again at the end; the shadow pipeline runs on an in-memory ledger
  and temporary DataStores. No setting is changed. The UI tour never presses Estimate, Backfill, Recompute, Delete,
  Calibrate, Reset, Import, Export, Share, a permission button, a switch or a text field: every click goes through
  `SafeClicks`, which refuses those (looked up by name in the installed app's resources) and fails the test instead.
  The only toggle it may press is the debug build's demo-mode switch, and its persisted value is restored exactly
  (also when the key did not exist) in `finally`.
- **No WorkManager work is started.** The suite only queries WorkManager; the UI test fails when a user-requested work
  (estimate now, backfill, recompute) appears.
- **Health Connect's quota is respected.** The instrumentation runs as the app, so its reads count against the app's
  quota (AOSP defaults: 2000 reads per 15 minutes and 16000 per day in the foreground). `ReadBudget` keeps the suite
  at most at 1500 reads per 15 minutes (waiting when needed) and 6000 per run; the shadow pipeline caches every read,
  so its later scenarios hardly read at all, and covers only as much history as fits (the report says when it is
  capped). On a real rate-limit error it pauses for 15 minutes and resumes.
- **No network.** Nothing is added to the app; the test APK requests no INTERNET permission either
  (`MergedManifestTest` still checks the app).

## Every Health Connect call the suite can make

All calls are made by the test code inside the app's process, as the app. The app's own components that the UI tour
shows make their usual calls in addition (the same as when you open those screens yourself).

| Call | Record type, filter | Made by |
|---|---|---|
| `readRecords` | SleepSessionRecord, own package, all history | snapshots, shadow pipeline (once, to compare with the app's records), real write, UI tour, cleanup (`readOwnSessions`) |
| `readRecords` | SleepSessionRecord, any origin, all history (keys only) | snapshots |
| `readRecords` | HeartRateRecord and StepsRecord, any origin, last 30 days (keys only) | snapshots |
| `readRecords` | SleepSessionRecord of the enabled sources (window reads, one single-record read of the oldest session) | shadow pipeline, real write |
| `readRecords` | HeartRateRecord, StepsRecord, OxygenSaturationRecord, RespiratoryRateRecord, HeartRateVariabilityRmssdRecord around each night (the night's source, or any origin with `hrSources` ANY) | shadow pipeline, real write (≤ 8 nights) |
| `getChangesToken` | SleepSessionRecord of the enabled sources | shadow pipeline (sync scenario) |
| `getChanges` | with that token, only when one is stored (not in a fresh run) | shadow pipeline |
| `getGrantedPermissions`, `getSdkStatus`, `getFeatureStatus` | — | every test that builds the repository |
| `insertRecords` | ONE SleepSessionRecord, clientRecordId `ps-e2e:<uuid>`: six inserts (v1, v1, v2, plain v1, verified v1 = insert + insert) | real write |
| `deleteRecords` | SleepSessionRecord by clientRecordIds, only `ps-e2e:` ids | real write (the verified write's repair, the final delete), its `finally` and the cleanup step |

Never: `deleteRecords` with a time range, `updateRecords`, `aggregate`, any write of another record type, any call with
another app's ids, `MyDataEraser`, the app's "delete my sessions" paths, any WorkManager enqueue, cancel or run.

## What else changes on the phone

- The test package `io.github.alien2003.pulsestages.test` is installed for the run and uninstalled at the end.
- Android force-stops the app whenever an instrumentation starts and ends; PulseStages' scheduled jobs are cancelled by
  that, so the runner opens PulseStages once at the end and returns to the home screen: WorkManager then registers the
  hourly and daily runs again (the same happens whenever you open the app). `--no-rearm` skips this; then open the
  app once yourself.
- While a test runs, the app's process is started like an app start, so the app does what it always does then: it
  keeps its schedule, and its own scheduled hourly check may run (it writes only when your sources have new nights).
- "Stay awake while charging" is set for USB during the run and restored to its original value on exit.
- Reports are written to `/sdcard/Android/data/io.github.alien2003.pulsestages/files/e2e/`, pulled, and removed from
  the phone (kept only when the cleanup could not be confirmed). The shadow pipeline's temporary files live in
  `cache/ps-e2e-shadow/` of the app and are deleted after each scenario.
- The app's own internal log gets the lines its own components log while the UI tour shows its screens, as always.

## Remaining risks

- During the real write's lifetime (a few seconds, asserted under a minute) other apps allowed to read sleep could see
  the test record; its title says it is a test record, and an app that follows Health Connect's change log sees its
  deletion right after. The night is at least 30 days old, so it is outside the "recent" range most apps sync.
- If PulseStages' own hourly or daily work runs during a test and your sources have new data, it may write a new night
  of its own; the comparison then reports that the app's records changed and names the WorkManager work that ran.
  The runner waits while the app's work is running before it starts; do not start the suite shortly before the daily
  run (the time set in the app's schedule settings).
- If another app syncs during the run, its added records are reported as warnings; changed or deleted ones fail the
  comparison (PulseStages cannot change other apps' data, so such a failure points at that app).

## How to run

Prerequisites: the project set up as in [docs/development.md](../../docs/development.md) (mise, the SDK), `adb.exe` on
Windows reachable as `ADB_WIN` (WSL) or `adb` (Linux), USB debugging allowed for this computer, and the installed
debug build of v0.1.0 (versionCode 10099) signed with your `~/.android/debug.keystore`. The checkout must be at v0.1.0
(the test APK is compiled against the same sources as the installed app).

```sh
mise exec -- tools/device-test/run-device-tests.sh                  # everything
mise exec -- tools/device-test/run-device-tests.sh cleanup-only     # only delete leftover ps-e2e: records
```

The runner builds the test APK with `./gradlew :app:assembleDebugAndroidTest -PversionName=0.1.0
-PversionCode=10099` (the app APK is not built for the phone and never installed; the same version properties keep
everything compiled exactly like the installed app). Options: `--skip-build`, `--keep-going` (continue after a failed
step), `--serial SERIAL`, `--unlock-timeout SECONDS` (default 300), `--shadow-history-days N` (0 = all history),
`--read-budget N` (default 6000), `--reads-per-15min N` (default 1500), `--no-rearm`. `EXPECTED_VERSION_CODE`
overrides the versionCode check. Every adb call has a timeout; a step that runs into its timeout is ended by
force-stopping the app, and the trap still cleans up. Ctrl-C is safe: the trap cleans up (a second Ctrl-C is ignored
while it does).

## What you have to do

1. Connect the phone over USB and allow USB debugging for this computer (tick "Always allow").
2. Close PulseStages (swipe it away in the recent apps) and make sure OHealth and the other health apps are not
   syncing; do not start the run shortly before PulseStages' daily run.
3. **Unlock the phone** with your PIN, pattern, fingerprint or face and leave it on the home screen. adb cannot unlock
   a secure lock screen and the runner does not try: it only wakes the screen. When it reaches a step that needs the
   screen while the phone is locked, it prints "UNLOCK IT NOW" and waits (5 minutes by default).
4. When ColorOS asks whether to install "Tests for io.github.alien2003.pulsestages" over USB, tap **Install** (it may
   ask for your OPPO account password or show a countdown first).
5. Then leave the phone alone until the summary appears: keep it connected, unlocked and on the same screen (folded or
   unfolded — the UI tour works on both displays, but do not fold or unfold it during the run), do not press the power
   button (the runner keeps the screen on while it is on USB) and do not use it. At the end PulseStages opens for a
   few seconds and the phone returns to the home screen.

Duration: building takes 1–3 minutes; the snapshots about a minute each; the shadow pipeline from a few minutes to
half an hour or more for a long history (it waits whenever it would exceed 1500 reads in 15 minutes); the real write
under a minute; the UI tour 3–6 minutes (the Compare tab scores the reference nights of 90 days). Plan for 15–45
minutes.

## Reports

Everything is pulled to `tools/device-test/out/<run>/` (git-ignored): `runner.log`, the raw output of every
instrumentation, `logcat.txt` (the app's and the suite's tags only) and `e2e/`:

| File | Content |
|---|---|
| `compare.md`, `compare.json` | The verdict: Health Connect before vs after |
| `snapshot-before.json`, `snapshot-after.json` | The snapshots |
| `schedule.json` | The app's WorkManager works |
| `shadow/summary.md`, `shadow/report.json` | History census, scenarios, problems, every session |
| `realwrite.json` | The test record's id, steps, ids and versions read back, lifetime |
| `ui.json`, `ui/*.png` | The UI tour's steps and screenshots |
| `cleanup.json` | What the cleanup found, deleted and left (must be nothing) |
| `status/*.json` | PASSED / FAILED / SKIPPED per test |
| `read-ledger.tsv` | Every Health Connect read call the suite charged |

The reports contain your own data (dates, stage shares, package names, screenshots of the app); they stay on this
computer.

## How it works

The test APK is an instrumentation of the installed app (`targetPackage` `io.github.alien2003.pulsestages`), so the
test code runs inside the app's process with the app's uid and Health Connect permissions, next to the app's own
classes, which come from the installed APK. That is why the test APK has to be compiled against the same sources
(v0.1.0) and signed with the same key, and why it never needs the app to be reinstalled. The UI tour reads every text
by resource name from the installed app, so it does not depend on resource ids matching between builds.
