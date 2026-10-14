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
- [Testing the runner](#testing-the-runner)

## What is tested

The runner runs these steps in this order, each as its own `am instrument` call; `CleanupE2eTest` always runs in the
runner's exit trap (again after 1 and after 15 minutes when it cannot confirm), and when a step fails the trap also
takes the "after" snapshot and compares. Before the first step the runner saves the app's JobScheduler jobs
(`dumpsys jobscheduler`, `schedule-before.txt`), and after it opened the app at the end it checks that they are
registered again (`schedule-after.txt`).

| Step | Class | What it does | Writes to Health Connect |
|---|---|---|---|
| 1 | `E2eSnapshotBeforeTest` | Snapshot: the app's own sleep sessions of all history (own-package DataOrigin filter: id, clientRecordId, version, lastModifiedTime, stages hash, title, notes hash); every other app's sleep sessions of all history, heart rate and steps of the last 30 days (per package: count and SHA-256 over the sorted id/lastModified pairs); the SHA-256 of the app's settings and sync-state files and the demo-mode flag | no |
| 2 | `ShadowPipelineE2eTest` | The app's whole pipeline over the real history with nothing written (below) | no |
| 3 | `RealWriteE2eTest` | The only real write: one test record, upserts and delete, gone again within a minute (below) | yes, one record in `ps-e2e:` |
| 4 | `ReadOnlyUiTourE2eTest` | Every screen of the real app, read-only (below) | no |
| 5 | `E2eSnapshotAfterTest` | The snapshot again, over exactly the ranges of step 1 | no |
| 6 | `E2eCompareTest` | Before vs after: other apps' records unchanged, the app's own records byte-identical, no `ps-e2e:` record left, the app's settings file, sync state and demo-mode flag unchanged | no |
| trap | `CleanupE2eTest` | Puts back the demo-mode flag from its backup (if a tour was interrupted), deletes every `ps-e2e:` record and verifies that none is left | only deletes `ps-e2e:` |

**Shadow pipeline (step 2).** The real `HealthConnectRepository` serves the reads; `ShadowWriteRepository` captures
every write and delete in memory and never forwards one (the app's own namespace is simulated, starting empty like a
fresh install). The ledger is an in-memory Room database of the app's own database class, the settings and sync-state
DataStores are temporary files below the app's cache directory, the estimator is the real SleepStager adapter with the
bundled model. The user's settings are copied read-only from the app's settings file. The step may spend only what
the later steps do not need of the run's read budget (a hard cap), and covers only as much history as fits. Scenarios:

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

**Real write (step 3).** A real night of the enabled sources that ended at least 30 days ago and that no record of the
app overlaps is estimated from the real data before anything is written. Nothing is written unless Health Connect has
room for the reads that follow: at least 50 read calls must be left in the run's budget, the 15-minute window must have
room for them (waited for before the write), and a read right before the insert must succeed; otherwise the test is
skipped. Then, with clientRecordId `ps-e2e:<random uuid>` (recorded on the phone before the insert) and a title
starting with "[PulseStages E2E test record, deleted within a minute]": insert v1 (exactly one record with that id;
stages, title, notes and zone offsets as written) → the same v1 again (still one, unchanged) → v2 with other stages
(one record, version 2, the new stages, the same metadata.id) → a plain `insertRecords` of v1 (Health Connect ignores
the lower version) → the app's verified write of v1 (it replaces the stale higher version: delete and insert) → delete
by clientRecordId → none left. The reads while the record exists are never held back by the suite's budget, so the
record lives seconds; its lifetime is asserted under 60 s. Without a suitable night the test is skipped and nothing is
written.

**UI tour (step 4).** MainActivity of the installed app with its own Hilt graph and data: Home (Health Connect available,
every permission granted, the counters), the privacy page (PermissionsRationaleActivity), Nights (every night of the
app listed, the All / Nights / Naps filter), one night's detail with the epoch panel and "Compare with RULES", Compare,
Settings with every group expanded and collapsed again, Sources, Log, About, demo mode on (Nights shows synthetic
nights) and off again. Screenshots of every screen. Any crash of the app fails it. Afterwards the app's own records,
its settings file, its sync state and the demo-mode flag must be as before, and no screen may have asked for work.

## Safety guarantees

- **The suite's own code can write or delete nothing but `ps-e2e:`.** Every repository the test code builds comes from
  `E2eHealth`, which wraps it at once in `SafetyGuardRepository`: a write or delete is forwarded only when every
  clientRecordId starts with `ps-e2e:`, and otherwise throws `SafetyViolation` (an Error, so the pipeline's per-night
  `catch (e: Exception)` cannot swallow it). The app's own records (`ps:` + sha256) and other apps' records can
  therefore not be written or deleted through it; Health Connect would not allow the latter anyway. Deletes are by
  clientRecordId only, never by time range; "Delete my sessions" (`MyDataEraser`) is never called. Only
  `SleepSessionRecord` is ever written.
- **The UI tour is the exception, and an allow-list guards it.** It drives the real app: the app's screens hold the
  app's own, unguarded Health Connect repository, eraser and work scheduler, as when you use the app. What keeps the
  tour from writing is `SafeClicks`, the only way the tour clicks: it presses a node only when the node is exactly what
  the tour means to press (the four tabs, eight fixed labels: the privacy page, Close, Show epoch details, next epoch,
  Compare with RULES, Back, Sources, Log; the three options inside the Nights filter; the expand and collapse buttons of
  the settings groups by their titles; a night row that shows the date of one of the app's own records, inside the
  list; the debug build's demo-mode switch). On top of that it refuses every node that shows the label of an action
  that writes, deletes, schedules work, changes a setting, exports, shares or leaves the app, every text field, slider,
  dropdown, checkbox, switch and radio button where the kind does not expect one, and every selectable node outside the
  tabs, the Nights filter and the night rows. Texts are looked up by name in the installed app's resources. It runs the
  click action of exactly the node it checked, on the UI thread, while that node is still on the screen. A refused
  click fails the test instead. `E2eResourceNamesTest` (a JVM test) checks that every name exists, that the deny-list
  covers every writing action and that the allow-list holds none. The screens the tour opens write nothing by
  themselves (filters, expanded groups and the epoch panel are in-memory state); the demo-mode flag is the only
  persisted value it changes, and it is backed up on the phone before the first toggle (`e2e/demo-flag-before.json`),
  restored to its exact previous value in `finally`, and restored from the backup by the cleanup step if the tour was
  killed.
- **None of the app's background work runs during the run.** Every `am instrument` starts the app's process, and the
  app's `Application.onCreate` keeps its WorkManager schedule. The suite's instrumentation (`E2eRunner`) first replaces
  WorkManager in that process by work-testing's in-memory one (`E2eWorkManager`): its database is in memory (the app's
  WorkManager database is never opened), nothing is handed to JobScheduler, WorkManager's force-stop handling and
  component toggling never run, and its worker factory never creates a worker of the app (any work it would start gets a
  worker that does nothing). A test refuses to run without it. Android force-stops the app when an instrumentation
  starts, which cancels the app's JobScheduler jobs, so no worker of the app can run outside the test processes either
  until the runner opens the app at the end. The runner checks before every step that no job of the app is running.
- **Every real write is preceded by a snapshot** (`RealWriteE2eTest` refuses to run without the "before" snapshot of the
  same run) **and followed by its removal:** the id is recorded on the phone before the insert, the record is deleted
  at the end, again in `finally`, and again by the runner's cleanup step. The cleanup first deletes the recorded ids
  (no read needed, so a used-up read quota cannot keep a test record alive), then reads the app's own sessions of all
  history with the own-package DataOrigin filter and deletes every `ps-e2e:` id it finds; a verification read must
  find none. It is never held back by the suite's own read budget, waits out Health Connect's rate limit for up to 16
  minutes, and the runner tries it again after 1 and after 15 minutes. `cleanup-only` mode does the same at any time.
- **The app's own nights stay byte-identical**, checked by `E2eCompareTest` (and by the real-write and UI tests on
  their own): same metadata.id, clientRecordVersion, lastModifiedTime, start, end, zone offsets, stages, title, notes.
- **The app is never installed, updated, downgraded, cleared, uninstalled or stripped of a permission.** The runner
  installs only `io.github.alien2003.pulsestages.test` (`adb install -r -t`), checks that the app's versionCode is
  10099 and that it is signed with the same certificate as the test APK (your `~/.android/debug.keystore`), and at the
  end uninstalls only the test package. It never uses `connectedAndroidTest` or `installDebug`.
- **The suite's code never writes the app's database or DataStore files.** It reads the settings file and the
  sync-state file as bytes (their SHA-256 is compared before and after the run, and around the UI tour); the shadow
  pipeline runs on an in-memory ledger and temporary DataStores. No setting is changed. The app's own components do
  write, as in any normal use: see [What else changes on the phone](#what-else-changes-on-the-phone).
- **Health Connect's quota is respected.** The instrumentation runs as the app, so its reads count against the app's
  quota, and it may count as a background caller (AOSP defaults: 1000 reads per 15 minutes and 8000 per day in the
  background, twice that in the foreground). `ReadBudget` keeps the suite at 600 reads per 15 minutes (waiting when
  needed) and 4000 per run, earlier runs on the same phone in the last 24 hours included (the runner keeps their totals
  and waits until 15 minutes after the last one); the shadow pipeline caches every read, so its later scenarios hardly
  read at all. The UI tour first waits until 400 reads are free in the window for the app's own screens and counts
  them afterwards. On a real rate-limit error the shadow pipeline pauses for 15 minutes and resumes.
- **No network.** Nothing is added to the app; the test APK requests no INTERNET permission either
  (`MergedManifestTest` still checks the app).

## Every Health Connect call the suite can make

The test code runs inside the app's process, as the app.

| Call | Record type, filter | Made by |
|---|---|---|
| `readRecords` | SleepSessionRecord, own package, all history | snapshots, shadow pipeline (once, to compare with the app's records), real write, UI tour (before and after), cleanup (`readOwnSessions`) |
| `readRecords` | SleepSessionRecord, own package, a few days around the test night | real write (the read before the insert, the six verification reads, and the app's VerifiedUpsert reading back each insert) |
| `readRecords` | SleepSessionRecord, any origin, all history (keys only) | snapshots |
| `readRecords` | HeartRateRecord and StepsRecord, any origin, last 30 days (keys only) | snapshots |
| `readRecords` | SleepSessionRecord of the enabled sources (window reads, one single-record read of the oldest session) | shadow pipeline, real write |
| `readRecords` | HeartRateRecord, StepsRecord, OxygenSaturationRecord, RespiratoryRateRecord, HeartRateVariabilityRmssdRecord around each night (the night's source, or any origin with `hrSources` ANY) | shadow pipeline, real write (≤ 8 nights) |
| `getChangesToken` | SleepSessionRecord of the enabled sources | shadow pipeline (sync scenario) |
| `getChanges` | with that token, only when one is stored (not in a fresh run) | shadow pipeline |
| `getGrantedPermissions`, `getSdkStatus`, `getFeatureStatus` | — | every test that builds the repository |
| `insertRecords` | ONE SleepSessionRecord, clientRecordId `ps-e2e:<uuid>`: six inserts (v1, v1, v2, plain v1, verified v1 = insert + insert) | real write |
| `deleteRecords` | SleepSessionRecord by clientRecordIds, only `ps-e2e:` ids | real write (the verified write's repair, the final delete), its `finally` and the cleanup step |

The app's own screens during the UI tour make the reads they always make when you open them (the app's code, not
guarded by the suite): `getSdkStatus`, `getGrantedPermissions`, `getFeatureStatus` (Home); heart rate, steps and the
optional channels around one night (its epoch panel and the comparison with RULES); sleep sessions of any origin of
the last 90 days and the heart rate, steps and optional channels of each reference night (Compare); sleep sessions of
any origin to count the sources (Sources). Their writes and deletes sit behind the buttons `SafeClicks` never presses.

Never: `deleteRecords` with a time range, `updateRecords`, `aggregate`, any write of another record type, any call with
another app's ids, `MyDataEraser`, the app's "delete my sessions" paths, any worker of the app, any WorkManager
enqueue, cancel or run by the suite.

## What else changes on the phone

- The test package `io.github.alien2003.pulsestages.test` is installed for the run and uninstalled at the end (also
  when the installation was interrupted or finished late).
- Android force-stops the app whenever an instrumentation starts and ends; that cancels PulseStages' scheduled jobs, so
  the runner opens PulseStages once at the end and returns to the home screen: its real WorkManager then registers the
  hourly and daily runs again, as whenever you open the app, and an hourly check that became due during the run may
  run right then (in the app's normal process, after the "after" snapshot). The runner checks that the jobs are back.
  `--no-rearm` skips this; then open the app once yourself.
- In every test process the app's start-up code runs as at any start of the app; it schedules its periodic and daily
  work into that process's in-memory WorkManager and, at the default log level, writes two lines to the app's internal
  log ("periodic check every … h", "daily run at … min after midnight"). During the UI tour the app's screens log what
  they always log. The log keeps its newest 10000 lines, so when it is full the oldest lines make room for these.
- "Stay awake while charging" is set for USB during the run and restored to its original value on exit. The original
  is also written to `tools/device-test/out/.stay-on-original-<serial>` first; if a run could not restore it, the next
  run (or `cleanup-only`) restores it before anything else.
- Reports are written to `/sdcard/Android/data/io.github.alien2003.pulsestages/files/e2e/`, pulled, and removed from
  the phone once the cleanup is confirmed (kept otherwise, for `cleanup-only`). The shadow pipeline's temporary files
  live in `cache/ps-e2e-shadow/` of the app and are deleted after each scenario and by the cleanup step.

## Remaining risks

- During the real write's lifetime (seconds, asserted under a minute) other apps allowed to read sleep could see the
  test record; its title says it is a test record, and an app that follows Health Connect's change log sees its
  deletion right after. The night is at least 30 days old, so it is outside the "recent" range most apps sync.
- If you open PulseStages yourself during the run, its real WorkManager starts its schedule again and may run a check
  that writes a night of its own; the runner reports a running job before the next step, and the comparison reports
  the changed records.
- If another app syncs during the run, its added records are reported as warnings; changed or deleted ones fail the
  comparison (PulseStages cannot change other apps' data, so such a failure points at that app).
- The UI tour's barrier is a check of what is on the screen. A future version of a screen that writes without a button
  press, or a writing button with a label the check does not know, would not be caught by it; the comparison of
  Health Connect and of the app's files afterwards would report the change. The checks are built for v0.1.0.

## How to run

Prerequisites: the project set up as in [docs/development.md](../../docs/development.md) (mise, the SDK), `adb.exe` on
Windows reachable as `ADB_WIN` (WSL) or `adb` (Linux), USB debugging allowed for this computer, and the installed
debug build of v0.1.0 (versionCode 10099) signed with your `~/.android/debug.keystore`. The checkout must be at v0.1.0
plus this suite (the test APK is compiled against the same sources as the installed app).

```sh
mise exec -- tools/device-test/run-device-tests.sh                  # everything
mise exec -- tools/device-test/run-device-tests.sh cleanup-only     # only remove what the suite may have left
```

The runner builds the test APK with `./gradlew :app:assembleDebugAndroidTest -PversionName=0.1.0
-PversionCode=10099` (the app APK is not built for the phone and never installed; the same version properties keep
everything compiled exactly like the installed app). Options: `--skip-build`, `--keep-going` (continue after a failed
step), `--serial SERIAL`, `--unlock-timeout SECONDS` (default 300), `--shadow-history-days N` (0 = all history),
`--read-budget N` (default 4000, earlier runs of the last 24 hours included), `--reads-per-15min N` (default 600),
`--no-rearm`. `EXPECTED_VERSION_CODE` overrides the versionCode check. Every adb call has a timeout and reads no
terminal input; a step that runs into its timeout is ended by force-stopping the app, and the trap still cleans up.
Ctrl-C is safe: it stops the current step at once (and the instrumentation on the phone), then the trap cleans up,
which takes a few minutes; Ctrl-C and closing the terminal are ignored while it does. A run started less than 15
minutes after an earlier one first waits until then.

The summary ends with `Result: PASSED`, `FAILED` (a step failed) or `INCOMPLETE` (everything passed, but something
could not be put back); everything not put back is listed with the command that fixes it.

## What you have to do

1. Connect the phone over USB and allow USB debugging for this computer (tick "Always allow"). In the developer
   options, "Install via USB" must be on (ColorOS asks for it).
2. Close PulseStages (swipe it away in the recent apps) and make sure OHealth and the other health apps are not
   syncing. Do not start while PulseStages runs a backfill or recompute (the runner waits up to 10 minutes and then
   stops without doing anything).
3. **Unlock the phone yourself before you start the runner**, with your PIN, pattern, fingerprint or face, and leave it
   on the home screen. Neither adb nor the suite can unlock a secure lock screen, and they never ask for your PIN; the
   runner only wakes the screen. If the phone is locked when the run starts, or before the UI tour, the runner (or the
   tour on the phone) prints "UNLOCK IT NOW" and waits up to 5 minutes (`--unlock-timeout`); unlock it then. The
   runner keeps the screen on while the phone is on USB.
4. When ColorOS asks whether to install "Tests for io.github.alien2003.pulsestages" over USB, tap **Install** (it may
   ask for your OPPO account password or show a countdown first).
5. Then leave the phone alone until the summary appears: keep it connected, unlocked and as it is (folded or unfolded —
   the UI tour works on both displays, but do not fold or unfold it during the run), do not press the power button,
   do not open PulseStages and do not use the phone. At the end PulseStages opens for a few seconds and the phone
   returns to the home screen.

`cleanup-only` needs none of this beyond the USB connection: it works with the phone locked.

Duration: building takes 1–3 minutes; the snapshots about a minute each; the shadow pipeline from a few minutes to an
hour or more for a long history (it waits whenever it would exceed 600 reads in 15 minutes); the real write under a
minute (plus a wait for room in the 15-minute window when the shadow pipeline used it up); the UI tour 3–6 minutes
(plus such a wait; the Compare tab scores the reference nights of 90 days). Plan for 30–90 minutes.

## Reports

Everything is pulled to `tools/device-test/out/<run>/` (git-ignored): `runner.log`, the raw output of every
instrumentation, `logcat.txt` (the app's and the suite's tags only), `schedule-before.txt` and `schedule-after.txt`
(the app's JobScheduler jobs) and `e2e/`:

| File | Content |
|---|---|
| `compare.md`, `compare.json` | The verdict: Health Connect and the app's local state before vs after |
| `snapshot-before.json`, `snapshot-after.json` | The snapshots |
| `shadow/summary.md`, `shadow/report.json` | History census, scenarios, problems, every session |
| `realwrite.json` | The test record's id, steps, ids and versions read back, lifetime, the cleanup after it |
| `ui.json`, `ui/*.png` | The UI tour's steps and screenshots |
| `cleanup.json` | What the cleanup found, deleted and left (must be nothing), and the demo-mode flag it restored |
| `status/*.json` | PASSED / FAILED / SKIPPED per test |
| `read-ledger.tsv` | Every Health Connect read call the suite charged |

`tools/device-test/out/.read-history-<serial>.tsv` keeps the read totals per minute of the last 24 hours. The reports
contain your own data (dates, stage shares, package names, screenshots of the app); they stay on this computer.

## How it works

The test APK is an instrumentation of the installed app (`targetPackage` `io.github.alien2003.pulsestages`), so the
test code runs inside the app's process with the app's uid and Health Connect permissions, next to the app's own
classes, which come from the installed APK. That is why the test APK has to be compiled against the same sources
(v0.1.0) and signed with the same key, and why it never needs the app to be reinstalled. Its instrumentation class
`E2eRunner` installs the in-memory WorkManager before Android calls the app's `Application.onCreate`. The UI tour reads
every text by resource name from the installed app, so it does not depend on resource ids matching between builds.

## Testing the runner

`tools/device-test/selftest/run-selftest.sh` runs `run-device-tests.sh` against a fake adb (`selftest/fake-adb`, no
phone) on a pseudo-terminal, as a user starts it (`selftest/pty_drive.py`), and checks its control flow: a normal run
with an adb that reads its stdin as `adb shell` does, Ctrl-C during a step (the step stops at once, the trap's output
reaches the terminal and `runner.log`), Ctrl-C and a closed terminal during the cleanup, a cleanup that has to be
retried, a setting that cannot be restored and its repair by the next run, the reads of an earlier run, a locked
phone, and the app's own work running before the run. It needs bash, python3 and coreutils and takes about three
minutes; `E2E_CLEANUP_PAUSES` shortens the cleanup's retry pauses there.
