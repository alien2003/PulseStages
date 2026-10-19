# PulseStages user guide

This guide is for people who use PulseStages on their phone. It covers installation, permissions, every screen, the
night and nap modes, automatic runs, exports, deletion, what other apps see, and what to do when something does not
work. Labels in quotes are the exact English texts of the app. The app also ships a Russian translation
(`app/src/main/res/values-ru`) and follows the phone's language; any other language gets the English texts.

PulseStages estimates sleep stages from heart rate. The result is a statistical estimate, not a measurement, and it
is not meant for medical decisions. How accurate it is: [README, "How accurate it is"](../README.md#how-accurate-it-is)
and [ALGORITHM.md §13](../ALGORITHM.md#13-results).

## Contents

- [Terms](#terms)
- [Before you start](#before-you-start)
- [Install](#install)
  - [Install and update with Obtainium](#install-and-update-with-obtainium)
- [First run](#first-run)
- [Permissions](#permissions)
- [Health Connect settings that matter](#health-connect-settings-that-matter)
- [Home](#home)
- [Nights](#nights)
- [Night detail](#night-detail)
- [Compare and calibration](#compare-and-calibration)
- [Settings](#settings)
- [Sources](#sources)
- [Log](#log)
- [Nights and naps](#nights-and-naps)
- [Which sleep sessions are estimated](#which-sleep-sessions-are-estimated)
- [Automatic runs](#automatic-runs)
- [Stale nights and recomputing](#stale-nights-and-recomputing)
- [History backfill](#history-backfill)
- [CSV export of a night](#csv-export-of-a-night)
- [Settings export, import and reset](#settings-export-import-and-reset)
- [Deleting your data](#deleting-your-data)
- [What other apps see](#what-other-apps-see)
- [Sleep as Android](#sleep-as-android)
- [Battery and background restrictions](#battery-and-background-restrictions)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)

## Terms

| Term | Meaning |
|---|---|
| Epoch | The time step of an estimate: every epoch gets one stage. 60 s by default (Settings → "Preprocessing" → "Epoch length"). The sleep lab scores 30-second epochs, so the validation and the Compare tab score the estimate in 30-second chunks. |
| Hypnogram | The chart of the stages over the session, one band per stage. |
| Coverage | The share of a session's epochs that have heart-rate data. |
| Confidence | The mean probability of the chosen stages, multiplied by the coverage and lowered on elevated or implausible nights ([Night detail](#night-detail)). It is not the probability that the stages are right. |
| Kappa (Cohen's κ) | Agreement between two hypnograms beyond what chance would give: 0 is chance level, 1 is perfect agreement. [ALGORITHM.md](../ALGORITHM.md#1-what-the-app-computes-and-why-it-is-an-estimate) calls the default estimator's κ ≈ 0.34 "fair" agreement. |
| Accuracy | The share of epochs (or 30-second chunks) whose stage agrees with the reference. |
| F1, macro F1 | F1 combines precision and recall of one stage (their harmonic mean); macro F1 is the mean F1 over the stages. |
| Params hash | A fingerprint of every setting and model that affects an estimate, stored with each written session ([Stale nights and recomputing](#stale-nights-and-recomputing)). |
| Stale | Estimated with another algorithm version or another params hash than the current one. |
| Record key (`clientRecordId`) | The key of PulseStages' session of a night in Health Connect, derived from the night's source sessions; a rewrite keeps it. |

## Before you start

You need:

- **Android 9 or later.** PulseStages has minSdk 28.
- **Health Connect.** On Android 14 and later it is part of the system. On Android 9–13 it is a separate app from
  Google Play (package `com.google.android.apps.healthdata`); Google lists Google Play services as a requirement there
  ([availability](https://developer.android.com/health-and-fitness/health-connect/availability)). The app supports
  this path, but it has not been tested on a device; reports are welcome
  ([CONTRIBUTING.md](../CONTRIBUTING.md#reporting-a-bug)). Health Connect does not work in work profiles, so install
  PulseStages in your personal profile.
- **A watch app that writes sleep sessions and heart rate to Health Connect.** The default source is OHealth
  (`com.heytap.health.international`), the companion app of OPPO watches. Any other app that writes sleep sessions
  can be enabled on the [Sources](#sources) screen. In Health Connect, under "Permissions and data" → "App
  permissions", the watch app must be allowed to write sleep and heart rate (and steps, if it has them). How to turn
  on OHealth's own Health Connect sync depends on the OHealth version; its menu names were not verified for this
  guide.

PulseStages has no `INTERNET` permission. It cannot download anything, check for updates or send data anywhere; see
[privacy.md](privacy.md).

## Install

PulseStages is published on the [Releases page](https://github.com/alien2003/PulseStages/releases) of its GitHub
repository. Install it through Obtainium, which also installs every later release as an update, or download the APK
yourself ([Release or debug APK](#release-or-debug-apk) and the sections after it).

### Install and update with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) is a free, open-source Android app that installs apps from their
release pages and installs their new releases as updates. It is a separate project: PulseStages itself has no update
check and no internet access ([Updates](#updates)). Obtainium installs the same `PulseStages-<version>.apk` from the
Releases page that you would download by hand.

1. **Install Obtainium** from one of the sources its README lists under
   [Installation](https://github.com/ImranR98/Obtainium#installation) (GitHub, IzzyOnDroid or F-Droid).
2. **Add PulseStages.** Open this page on the phone and tap the button:

   [![Get it on Obtainium](get-it-on-obtainium.svg)][obtainium-import]

   The button opens a redirect page of Obtainium's app directory (apps.obtainium.imranr.dev), which hands the
   settings below to Obtainium; that page loads the directory's analytics and ad scripts. Without Obtainium on the
   phone, the page offers a link to get it. Obtainium asks "Import app" and shows the settings under "Raw JSON"; tap
   "Continue".
3. **Install PulseStages** from Obtainium. The first time, Android asks you to allow Obtainium to install apps. If
   the release build of PulseStages is already installed, Obtainium recognises it and shows its version, and there is
   nothing to install until a newer release comes out.

The button gives Obtainium these settings:

| Obtainium setting | Value | Why |
|---|---|---|
| "App source URL" | `https://github.com/alien2003/PulseStages` | The releases of this repository. |
| "Include prereleases" | Off | Final releases only; a tag such as `v0.2.0-beta.1` is a pre-release. |
| "Filter APKs by regular expression" | `^PulseStages-[0-9.]+\.apk$` | Each release has two APKs. This keeps `PulseStages-<version>.apk` and drops `PulseStages-<version>-debug.apk` and any `-alpha`, `-beta` or `-rc` APK. |
| "Expected signing certificate hashes" | `ce5546e83c03eac9837b93fc2c04d2e37abd1b778c3ef9e01b67cd1602e35be5` | The "Release signing certificate SHA-256" of the release notes: Obtainium refuses an APK signed with another key. Obtainium 1.6.16 and later check it; older versions ignore it. |

Obtainium's app id in the import is PulseStages' application id, `io.github.alien2003.pulsestages`, which is how
it recognises an installed copy.

**Updates.** Obtainium checks for new releases in the background (how often is set in its settings) and then notifies
you or installs the update. An update of a copy that Obtainium did not install itself needs your confirmation; after
that, on Android 12 and later, updates can be installed in the background
([Obtainium's conditions](https://wiki.obtainium.imranr.dev/app_tracking/#silent-installation)). Android checks the
signing certificate on every update and installs it only if it is signed with the same key as the installed app,
whoever installs it. As with a manual update, your data and the Health Connect permissions are kept, and nights
estimated before an update that changes the model or the defaults are marked stale
([Stale nights and recomputing](#stale-nights-and-recomputing)).

**Release, not debug.** Obtainium must pick `PulseStages-<version>.apk`. If it asks you to choose ("Pick an APK") or
offers the `-debug` APK, the filter is missing: set it as in the table. A debug build installed on the phone cannot
be updated with the release build; uninstall it first ([Release or debug APK](#release-or-debug-apk)).

**Adding it by hand.** If the button does not work (for example on a computer, or in a browser that does not pass the
link on to Obtainium), open Obtainium's "Add app" screen and enter `https://github.com/alien2003/PulseStages` as "App
source URL". Under "Additional options for GitHub", set "Filter APKs by regular expression" to
`^PulseStages-[0-9.]+\.apk$`, leave "Include prereleases" off, optionally paste the certificate SHA-256 from the
table into "Expected signing certificate hashes", and tap "Add". The labels are those of Obtainium 1.6.17 and may
differ in other versions.

### Release or debug APK

Every release on the [Releases page](https://github.com/alien2003/PulseStages/releases) has three files:

| File | What it is for |
|---|---|
| `PulseStages-<version>.apk` | The signed release build, for everyday use. Later releases install over it as updates. |
| `PulseStages-<version>-debug.apk` | The debug build, for testing. It has the demo mode (Settings → "Debug build" → "Demo mode"), see [SCREENSHOTS.md](SCREENSHOTS.md). |
| `SHA256SUMS.txt` | SHA-256 checksums of both APKs. |

Choose the release build unless you want to test. Releases with tags such as `v0.2.0-beta.1` are marked
"Pre-release" on GitHub and never become "Latest".

**Release and debug do not mix.** Both builds use the same application id (`io.github.alien2003.pulsestages`) but are
signed with different keys, so neither can update the other; Android refuses the update (adb reports
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). To switch, uninstall first. Uninstalling:

- deletes the app's own storage: the list of estimated nights, the settings, the personal model and the log (app
  backups are off, so Android does not restore them);
- revokes every Health Connect permission;
- keeps the sessions PulseStages wrote in Health Connect.

After the reinstall, grant the permissions again. Health Connect then counts its 30-day history limit from the new
grant ([read data](https://developer.android.com/health-and-fitness/health-connect/read-data)), so allow history
access again before a backfill. PulseStages finds its earlier sessions in Health Connect by reading its own data and
replaces them, so a night does not end up with two PulseStages sessions. On Android 9–13 Health Connect applies its
30-day limit to an app's own data too, so without history access PulseStages cannot see (or replace or delete) its own
sessions from more than 30 days before the new grant; allow history access, or delete them in Health Connect
([Deleting your data](#deleting-your-data)).

To keep your settings, use "Export JSON" before uninstalling and "Import JSON" afterwards. Import the file into the
same version; before importing it into a newer version, delete from the file every key you did not change yourself (a
partial file is accepted), otherwise the older defaults it contains replace the new ones
([Settings export, import and reset](#settings-export-import-and-reset)).

The release notes also give the "Debug signing certificate SHA-256". A later debug APK installs over an earlier one
only if the two values are the same; otherwise uninstall first. A debug APK you build yourself is signed with your own
debug key.

### Check the download

- **Checksums.** Put the APK and `SHA256SUMS.txt` in one folder and run `sha256sum -c --ignore-missing
  SHA256SUMS.txt` (Linux, GNU coreutils), or `shasum -a 256 -c SHA256SUMS.txt` on macOS. Without `--ignore-missing`
  the APK you did not download is reported as missing and the command fails; check the line of the file you
  downloaded.
- **Signing certificate.** The release notes end with "Release signing certificate SHA-256: …". The command
  `apksigner verify --print-certs PulseStages-<version>.apk` (apksigner is part of the Android SDK build-tools)
  prints a line `Signer #1 certificate SHA-256 digest: …` that must match it. Release-signed APKs are only published
  from version tags; the workflow's dry run discards its release APK.

### Install on the phone

Open the downloaded APK on the phone. The first time, Android asks you to allow the browser or file manager to
install apps; allow it for that app. From a computer with adb, `adb install -r PulseStages-<version>.apk` works as
well.

### Build it yourself

The project builds with mise (which provides a JDK 17) and an Android SDK under `.tooling/`.
[development.md](development.md) has the setup; after it, `mise exec -- ./gradlew assembleDebug` writes
`app/build/outputs/apk/debug/app-debug.apk`. A self-built debug APK is signed with your own debug key and cannot
update an installed release or published debug build either.

### Updates

There is no update check inside the app. Install a newer `PulseStages-<version>.apk` over the installed release
build, or let Obtainium do it ([Install and update with Obtainium](#install-and-update-with-obtainium)); Android keeps
the app's data and permissions on an update signed with the same key. An update can change the bundled model or the
defaults. Nights estimated before are then "stale": they are marked and offered for recomputation, never recomputed
silently ([Stale nights and recomputing](#stale-nights-and-recomputing)).

## First run

1. Open PulseStages. The **Home** tab shows the state of Health Connect and of every permission.
2. If the Health Connect card says "Health Connect needs to be installed or updated.", tap "Install or update Health
   Connect" (Android 9–13). If it says "Health Connect is not available on this device or profile.", PulseStages
   cannot work on this device or in this profile.
3. In the "Permissions" card tap "Grant permissions". Health Connect shows one dialog with everything that is still
   missing. Allow at least sleep (read and write), heart rate and steps; the card then says "Required permissions: 4 of
   4".
4. Tap "Allow history" and "Allow background" if they are offered. They are separate Health Connect permissions
   ([Permissions](#permissions)).
5. On Android 13 and later, tap "Allow notifications" to see the progress of long runs.
6. Tap "Estimate new". The first run reads the sleep sessions of the enabled sources from the last 7 days and
   estimates them; a message such as "Estimate new: 3 nights estimated (found 5, skipped 2)." reports the result. Open
   the **Nights** tab to see them.
7. In Health Connect, set the priority for sleep ([Data sources and priority](#data-sources-and-priority)). If
   PulseStages is not in the list yet, check again once it has written its first session.
8. Tap "Backfill history" to estimate all older nights ([History backfill](#history-backfill)).

The background schedule is set up when the app's process starts, so open the app once after installing it. From then
on new nights are processed automatically ([Automatic runs](#automatic-runs)).

## Permissions

| Permission (Health Connect) | Home label and button | Used for | Without it |
|---|---|---|---|
| Sleep: read (`READ_SLEEP`) | "Required permissions: n of 4" · "Grant permissions" | Reading the sleep sessions of the enabled apps, and PulseStages' own sessions | Nothing is estimated. Runs end with "Nothing was read: a required permission is missing.", and "Last run" shows "a permission is missing". |
| Sleep: write (`WRITE_SLEEP`) | same | Writing PulseStages' own sessions with stages | Same as above. |
| Heart rate: read (`READ_HEART_RATE`) | same | The main signal of the estimate, read during each session and 30 minutes around it | Same as above. |
| Steps: read (`READ_STEPS`) | same | Movement during the night | Same as above. |
| Blood oxygen (`READ_OXYGEN_SATURATION`) | "Blood oxygen (SpO2)" · "Optional: used when present." | An optional input when the watch app writes it | Not used. The estimate works without it. |
| Respiratory rate (`READ_RESPIRATORY_RATE`) | "Respiratory rate" · "Optional: used when present." | An optional input | Not used. |
| Heart-rate variability (`READ_HEART_RATE_VARIABILITY`) | "Heart-rate variability" · "Optional: used when present." | An optional input (RMSSD) | Not used. |
| Access to history (`READ_HEALTH_DATA_HISTORY`) | "Access to history" · "Allow history" | Reading data older than 30 days before PulseStages was first given access | "Not granted: Health Connect returns no data older than 30 days before PulseStages was first given access, so the history backfill is limited." The Sources screen scans only the last 90 days, and Compare offers only "90 days". |
| Background access (`READ_HEALTH_DATA_IN_BACKGROUND`) | "Background access" · "Allow background" | Automatic runs while the app is closed, and the check whether the nights are complete before the sync request to Sleep as Android | "Not granted: new nights are only processed while the app is open." Automatic runs that start in the background read nothing and "Last run" shows "no background access". The sync request to Sleep as Android relies on a check made while PulseStages runs in the foreground ([Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync)). |
| Notifications (Android 13+) | "Notifications (backfill progress)" · "Allow notifications" | Progress notifications of "Estimate new", the backfill and recompute | "Not allowed: the backfill runs without a progress notification." The runs still work. |

Details:

- **"Grant permissions"** asks for every missing permission in one Health Connect dialog: the required and optional
  ones, and history and background access when the installed Health Connect supports them. A message then says "All
  required permissions are granted." or how many required permissions are still missing.
- **"This Health Connect version cannot grant it."** appears instead of the history or background text when the
  installed Health Connect does not offer that permission. On Android 9–13, updating the Health Connect app may help.
- **Two cancelled dialogs.** When a permission request is cancelled twice in a row, Health Connect stops showing its
  dialog ([Google's guidance](https://developer.android.com/health-and-fitness/health-connect/ui/permissions)). Home
  then says "Health Connect no longer shows the permission dialog. Open its settings to grant the permissions." and
  offers "Open Health Connect settings", which opens PulseStages' permission page in Health Connect on Android 14 and
  later (Health Connect's settings on older versions).
- **The 30-day limit.** By default Health Connect returns other apps' data only from 30 days before an app's first
  permission grant ([read data](https://developer.android.com/health-and-fitness/health-connect/read-data)). Without
  history access the backfill and Compare cannot see older nights.
- **Background access.** Without it, Health Connect does not give other apps' data to a run in the background, so
  PulseStages refuses to read instead of producing empty nights. Runs you start in the app ("Estimate new", the
  backfill, recompute) run as a foreground service with a notification and work without background access, as long
  as Android lets them start in the foreground.
- **Optional data and stale nights.** "Use SpO2", "Use HRV" and "Use respiratory rate" (Settings → "Features" →
  "Optional channels") are on by default, so an optional channel is read as soon as its permission is granted.
  Granting or revoking one later changes the input of every night, and nights estimated before become
  [stale](#stale-nights-and-recomputing).
- **Revoking.** Permissions can be revoked any time in Health Connect → "Permissions and data" → "App permissions" →
  PulseStages. Runs then stop with the messages above.

### The privacy screen

"PulseStages and your health data" lists what is read and written and how to delete it. Open it from Home → "How
PulseStages uses your data", or from the privacy-policy link on Health Connect's permission screen for PulseStages.
The screen has the buttons "Open PulseStages" and "Open Health Connect".

## Health Connect settings that matter

### Where to find Health Connect

- **Android 14 and later:** Health Connect is in the system settings, not on the home screen. Google gives the path
  Settings → "Security and privacy" → "Privacy controls" → "Health Connect", with variants such as "Security &
  Privacy" → "Privacy" → "Health Connect" or "… → Permission manager → Health Connect"
  ([get started](https://developer.android.com/health-and-fitness/health-connect/get-started),
  [availability](https://developer.android.com/health-and-fitness/health-connect/availability)). Searching the
  settings for "Health Connect" also works. Phone makers can name these menus differently.
- **Android 9–13:** open the Health Connect app.
- **From PulseStages:** Home → "Open Health Connect" opens PulseStages' own permission page in Health Connect on
  Android 14 and later, and Health Connect's settings on older versions.

### Data sources and priority

The path is Health Connect → "Permissions and data" → "Manage data" → "Data sources and priority"
([Google help](https://support.google.com/android/answer/12990553)). What the list does
([aggregate data](https://developer.android.com/health-and-fitness/health-connect/aggregate-data)):

- Health Connect removes duplicates only for activity and sleep data, and only in its totals (the `aggregate()` API).
  There it keeps the data of the app with the highest priority.
- Apps that list sessions (`readRecords()`) still see the sessions of every app, overlapping ones included.
- An app removed from the list still writes data, but its data no longer counts in the totals.

**Recommendation:** for sleep, put PulseStages above OHealth. Sleep totals in Health Connect, and in apps that use
them, then come from PulseStages' staged session instead of OHealth's session without stages. This does not change
what apps that list sessions see (Sleep as Android, for example, chooses by start time,
[Sleep as Android](#sleep-as-android)), and it does not change which sessions PulseStages reads: PulseStages has its own
priority list on the [Sources](#sources) screen. Whether PulseStages appears in Health Connect's list before it has
written its first session was not verified; if it is missing, check again after the first estimate.

## Home

The Home tab has these cards (two columns on a wide window):

- **"Health Connect"**: "Health Connect is available.", "Health Connect needs to be installed or updated." (with
  "Install or update Health Connect"), or "Health Connect is not available on this device or profile."
- **"Permissions"**: one line per permission ([Permissions](#permissions)) and the buttons "Grant permissions", "Allow
  history", "Allow background", "Allow notifications", "Notification settings" and "Open Health Connect", each shown
  when it applies.
- **"Sources and schedule"**:
  - "Sleep sources": the enabled apps (or "No source enabled");
  - "Heart rate and steps": from the same app as the sleep session, or from every app;
  - "Last run": the time and outcome of the last check for new nights, "Running since …", or "Never". Outcomes:
    "success", "done, some nights failed", "rate limit reached, will retry", "a permission is missing", "Health
    Connect unavailable", "no background access", "failed", "cancelled";
  - "Next automatic run": the next scheduled run, or "Not scheduled yet";
  - "Model": "Global model", "Personal model (calibrated on reference nights)" or "Personal model (adapted to your
    nights)".
- **"Source sessions"**: counters of the other apps' sleep sessions in the app's records: "found", "estimated",
  "skipped" (for any reason, failures included), "stale", "failed", "nights written" and "naps written". "found",
  "estimated", "skipped" and "failed" count source sessions, including those of sources that were disabled later;
  "found" also counts source sessions that were deleted in Health Connect. "stale" and the two "written" counters
  count PulseStages' outputs.
- **"Actions"** ("These actions need Health Connect and the required permissions." while they cannot run):
  - "New nights" → "Estimate new": checks Health Connect for new sleep sessions now
    ([Automatic runs](#automatic-runs));
  - "History backfill" → "Backfill history", "Cancel" while it runs, "Resume backfill" after an interruption
    ([History backfill](#history-backfill));
  - "Recompute" → "Recompute stale nights" (only when there are stale nights) and "Recompute all"
    ([Stale nights and recomputing](#stale-nights-and-recomputing));
  - "Your sessions in Health Connect" → "Delete my sessions from Health Connect"
    ([Deleting your data](#deleting-your-data)).
- **"Sleep stages from heart rate"**: a short description and "How PulseStages uses your data" (the privacy screen).

While a run waits, the card shows "Waiting to run…", or "Waiting for a retry: Health Connect was busy." when Health
Connect's rate limit delayed it.

## Nights

The Nights tab lists every session PulseStages has processed, newest first. Each entry shows:

- the status: "Estimated", "Not estimated", "Failed", "Deleted by you" or "Source deleted" (in red when the session has
  no output because of a problem; a nap skipped because "Written for a nap" is "Nothing" is not shown as a problem);
- the date, the time range and the duration;
- for an estimated session, the stage shares ("Deep", "REM", "Light", "Awake", "No data"), a bar of the same shares,
  and "Confidence … · coverage …";
- markers: "Nap" (processed as a nap), "Stale" (estimated with other settings, see
  [Stale nights and recomputing](#stale-nights-and-recomputing)), "Elevated heart rate" (the night's lowest heart
  rate was more than "Elevated heart-rate threshold", 12 % by default, above your baseline of recent nights) and "Low
  confidence" (the heart-rate range was too small, or the stage shares failed the sanity check; the night detail
  lists the warnings).

Sessions whose output was replaced by a merged or changed night ("Replaced") are not listed.

**Filter.** "All", "Nights" and "Naps" above the list switch between all sessions, nights only and naps only. With
"Naps" and no naps, the list says "No naps in the list. Sessions shorter than the nap threshold appear here once they
are at least as long as the minimum session length of the sources."

**Empty list.** "No nights yet" means that PulseStages has not processed any session yet; see
[Troubleshooting](#troubleshooting).

**Wide windows.** On a window at least 600 dp wide (the inner screen of a foldable, a tablet, a phone in landscape),
the list and the selected night's detail are shown side by side. When the window becomes narrow again, the selected
night opens as its own screen.

![Nights tab in the two-pane layout of a foldable's inner screen](screenshots/nights-wide.png)

## Night detail

Tap an entry to open its detail. Its cards, from top to bottom (the terms are explained in [Terms](#terms)):

1. **Header**: the date, time range and duration, "Status", "Processed as" ("Night" or "Nap") and, for a session without
   output, "Reason". For an estimated nap with the hybrid estimator it says: "Nap stages are not validated: the nap
   model failed pre-registered checks, and on test sessions with the stage mix of published naps it was less accurate
   than calling everything light sleep."; with another estimator: "Nap stages are not validated; take them as the app's
   estimate only." ([Why naps are written as asleep / awake](#why-naps-are-written-as-asleep--awake)). A nap written as
   asleep / awake (the default) adds "Written to Health Connect as asleep / awake only; the stages shown here are the
   app's estimate." A stale session says "Stale: estimated with other settings or an older algorithm. Recompute it to
   update its session." A session that PulseStages rewrote on its own because more data arrived after the first
   estimate ([Automatic runs](#automatic-runs)) has the row "Updated automatically" with the reason: "more heart rate
   arrived (46 → 57 samples)", "the heart rate changed (… → … samples)" or "steps or other data changed". A later
   recompute removes the row.
2. **Hypnogram**, titled "Hypnogram (as written to Health Connect)", "Hypnogram (the app's estimate; written to
   Health Connect as asleep / awake)" for a nap written as asleep / awake, or "Hypnogram (a new estimate; nothing was
   written)" when nothing was written. Below it is the heart-rate curve ("Heart rate" and "Smoothed", in bpm) and a
   legend with the minutes per stage. "No data" spans are heart-rate gaps longer than the interpolation limit. The
   heart-rate curve appears once the new estimate of item 3 is ready.
3. **Epoch details.** The heart-rate curve and the probabilities come from a new estimate with the current settings,
   read from Health Connect when the detail opens ("Reading the heart rate from Health Connect…"). Tap or drag on the
   charts, or use "Show epoch details", to see one epoch: "Estimate now: … · written: …", the "Probabilities" of the
   four stages, the heart rate and the "Features". When the night is stale, the detail says "The heart-rate curve and
   the probabilities come from a new estimate with the current settings; it can differ from what was written."
4. **"Actions"**:
   - "Recompute" estimates this night again with the current settings and rewrites its session ("Night recomputed.").
     It is available for estimated, not estimated, failed and deleted sessions. With "Existing sessions" set to
     "Never overwrite" it is blocked: "“Existing sessions” is set to “Never overwrite” in Settings, so this night's
     session is not rewritten. Change that setting to recompute it."
   - "Compare with RULES" shows "Agreement with RULES: kappa … · accuracy …" between the current estimate and the
     simple threshold estimator, with both hypnograms. Both use the same heart rate; the difference shows what the
     model adds.
   - "Export CSV" saves the epoch table ([CSV export of a night](#csv-export-of-a-night)).
   - "Delete my session" deletes PulseStages' session of this night from Health Connect (only for an estimated
     session), after the confirmation "Delete this session?".
5. **"Estimate"**: "Estimator", "Confidence", "Heart-rate coverage" (share of epochs with heart rate), "Heart-rate
   samples", "Lowest heart rate", "Algorithm version", "Parameters hash" and "Model".
6. **"Warnings"**, when there are any: the flags "Elevated heart rate", "Low confidence", "Proportions calibrated" and
   "Rules fallback (low coverage)", and the warnings below.
7. **"Source"**: "App" (the source app), "Source sessions" (how many), "Record key (clientRecordId)"
   (the key of PulseStages' session) and "Last change" (when the session was last written, by the first estimate, a
   recompute or an automatic update, or when the night's status last changed; a check that rewrote nothing does not
   count).

| Warning | Meaning |
|---|---|
| "Heart-rate range of the night is very small; stages are unreliable." | The heart rate hardly changed, so the normalized heart rate the estimators use is unreliable. |
| "Night heart rate was above your baseline." | The night's lowest heart rate was above your recent baseline (nights only). |
| "Implausibly much deep sleep." / "Implausibly little deep sleep." / "Implausibly much REM sleep." | The stage shares fall outside the sanity ranges (nights; naps only with "Check the stage shares of naps"). |
| "Stage proportions were calibrated towards the target ranges." | "Calibrate stage proportions" (Post-processing, off by default) changed the night. |
| "Too few heart-rate data: the rules estimator was used." | Coverage was between "Rules fallback coverage" (40 %) and "Minimum coverage" (60 %). |
| "Hybrid model unavailable: the HMM was used." | The bundled model could not be loaded or does not match the build. |
| "The heart rate has gaps longer than the interpolation limit." | Some spans have no heart rate; they are written as unknown by default. |
| "No sleep cycles found in the heart rate; the cycle prior was off." | HMM only. |
| "Per-night adaptation produced an implausible order and was rolled back." | HMM only, with "Adapt emissions to each night". |

The meaning of every value is in [ALGORITHM.md](../ALGORITHM.md) (coverage gates and confidence in
[§9](../ALGORITHM.md#9-coverage-gates-fallbacks-and-confidence-sleepstager)). "Confidence" is the mean probability of
the chosen stages, multiplied by the coverage and lowered on elevated or implausible nights; it is not the probability
that the stages are right.

## Compare and calibration

**Reference nights** are sleep sessions that another app (another watch, a ring, a sleep-lab app) wrote to Health
Connect with real stages: light, deep, REM. OHealth's sessions without stages cannot be references. PulseStages'
own sessions are never references. Sessions with only awake / asleep stages are counted but not scored.

The Compare tab:

- searches "90 days" (default), "1 year" or "All history" ("Search range"); the longer ranges need history access;
- estimates the same nights from heart rate and shows "Kappa", "Accuracy" and "Macro F1" ([Terms](#terms)), "Mean error per night:
  deep …, REM …, awake …", a "Per stage" table (accuracy = recall, precision, F1), a "Confusion matrix" in 30-second
  chunks, and the "Reference nights" with their own scores;
- when the reference sessions include naps, "Nights and naps apart" shows kappa and accuracy of each, and the list
  marks naps with "Nap";
- has a refresh button in the top bar.

A reference is only as good as the app that wrote it: its stages come from that device's own algorithm, not from a
sleep lab.

**"Calibrate"** fits a personal model on the reference nights and uses it for every later estimate
([ALGORITHM.md §10](../ALGORITHM.md#10-personalization-personal)):

- it uses reference nights only (reference naps are left out);
- the latest 30 % of the nights ("Held-out share") are held out, and Compare shows "Kappa on held-out nights: … → …";
- every night estimated before becomes stale;
- it needs Settings → "Personalization" → "Personal model" (on by default); otherwise Compare says
  "Personalization is switched off (Settings → Personalization). Turn it on to calibrate."

**"Reset to global model"** ("Use the global model again?") switches the personal model off; nights estimated with
it become stale.

Without reference nights there is an unsupervised adaptation of the HMM to your nights every 30 new nights ("Adapt
every"). It runs only when the HMM is actually used (estimator "HMM" for nights or naps, or "Hybrid" falling back to
it), so with the default estimators it does not run.

## Settings

The Settings tab starts with the card **"Backup and reset"**: "Export JSON", "Import JSON", "Reset to defaults",
"Sources" and "Log". Then come the setting groups, each a card that you can expand or collapse; on a wide window they
are shown in two columns.

| Group (link: its settings) | Summary in the app | Expanded at first |
|---|---|---|
| ["Sources"](settings-reference.md#sources) | "Which sessions are estimated" (includes the link "Sleep sources") | yes |
| ["Schedule"](settings-reference.md#schedule) | "When new nights are processed" | yes |
| ["Written sessions"](settings-reference.md#written-sessions) | "What is written to Health Connect" | yes |
| ["Algorithm"](settings-reference.md#algorithm) | "Which estimator is used" | yes |
| ["Naps"](settings-reference.md#naps) | "Sessions shorter than the nap threshold: how they are estimated and written" | yes |
| ["Preprocessing"](settings-reference.md#preprocessing) | "Cleaning and resampling the heart rate" | no |
| ["Features"](settings-reference.md#features) | "Per-epoch features and optional channels" | no |
| ["Rules estimator"](settings-reference.md#rules-estimator) | "Thresholds of the simple fallback" | no |
| ["HMM emissions"](settings-reference.md#hmm-emissions) | "μ and σ of every state and feature" (includes "Reset to physiology table") | no |
| ["HMM transitions"](settings-reference.md#hmm-transitions) | "Bout lengths and transition weights" | no |
| ["HMM priors"](settings-reference.md#hmm-priors) | "Time of night, REM lockout, cycles, steps, adaptation" | no |
| ["Hybrid estimator"](settings-reference.md#hybrid-estimator) | "Classifier with explicit durations" | no |
| ["Post-processing"](settings-reference.md#post-processing) | "Minimum bouts, sanity check, calibration" | no |
| ["Naps: algorithm"](settings-reference.md#naps-algorithm) | "Heart-rate context, post-processing and hybrid decoder of naps; empty = the value of nights" | no |
| ["Personalization"](settings-reference.md#personalization) | "Personal model from your nights" | no |
| ["Debugging"](settings-reference.md#debugging) | "Internal log" (includes the link "Log") | yes |

After the groups, debug builds show the card "Debug build" with "Demo mode" ([SCREENSHOTS.md](SCREENSHOTS.md)); the
last card, "Versions and licences", shows the app, algorithm and model versions, the licence and the attribution
notice of the training datasets.

Every setting has a hint and, for numbers, its unit and allowed range ("Allowed: …"). A change is saved as soon as it
is valid. An invalid value stays in the field with the problem below it (for example "Out of range. Allowed: …") and
is not saved. After a change that makes estimated nights stale, a message says "… nights are now stale: they were
estimated with other settings." with the action "Recompute".

The settings most people use:

| Setting (group) | Default | What it does |
|---|---|---|
| "Heart rate and steps from" (Sources) | "Same app" | "Same app": from the app that wrote the sleep session; "Any app": from every app that writes them |
| "Minimum session length" (Sources) | 90 min | Shorter sessions (after merging fragments) are ignored; lower it to estimate short naps |
| "Merge gap" (Sources) | 30 min | Fragments of one night from one app with a shorter gap become one night; the gap is written as awake |
| "History depth" (Sources) | 0 days | How far back the backfill goes; 0 = all available history |
| "Daily run" (Schedule) | 09:00 | Approximate time of the daily run |
| "Check for new nights every" (Schedule) | 1 h | Period of the background check: "15 min", "30 min" or 1 to 24 hours. A check that finds nothing new costs almost nothing; recent nights are re-checked for late heart rate at most about once an hour, with a longer interval at every check ([Automatic runs](#automatic-runs)) |
| "Only when the battery is not low" (Schedule) | on | Automatic runs wait until the battery is not low |
| "Session title" (Written sessions) | empty | Title of the written sessions; placeholders `{estimator}`, `{version}`, `{confidence}`; empty = "Sleep stages from heart rate (estimate)" in the phone's language |
| "Existing sessions" (Written sessions) | "Skip up-to-date nights" | What runs do with nights that already have a session ([overwrite policy](#existing-sessions)) |
| "Write awake as light sleep" (Written sessions) | off | For apps that show awakenings poorly; changes the written data |
| "Sleep as Android compatibility" (Written sessions) | off | Starts each written session 1 second before the source session, for Sleep as Android, which imports only the session that starts first ([Sleep as Android compatibility](#sleep-as-android-compatibility)); changes the written data |
| "Ask Sleep as Android to sync" (Written sessions) | on | Once the nights PulseStages wrote or updated are complete (usually 1–2 hours after you wake up), asks Sleep as Android to sync with Health Connect, and once more 10 minutes later after a new or recomputed night, so that Sleep as Android adds its heart rate and SpO2 ([Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync)); does nothing while Sleep as Android is not installed; changes nothing that is written |
| "Estimator" (Algorithm) | "Hybrid" | "Hybrid", "HMM" or "Rules" |
| "Nap threshold" (Naps) | 180 min | Shorter sessions are naps; 0 = no naps |
| "Written for a nap" (Naps) | "Asleep / awake" | "Asleep / awake" (only sleep and awakenings), "Stages" or "Nothing" ([Nights and naps](#nights-and-naps)) |
| "Estimator for naps" (Naps) | "Same as nights" | The estimator of naps |
| "Log level" (Debugging) | "Info" | Minimum level of the internal log |

Every setting, with its key in the JSON export, default, allowed values, effect and whether it changes the parameters
hash, is in the [settings reference](settings-reference.md). The algorithm parameters are explained in
[ALGORITHM.md §11](../ALGORITHM.md#11-parameters-paramsalgoparamskt-and-their-defaults). Changing them away from the
validated defaults is at your own risk: the validation numbers apply to the defaults only.

<img src="screenshots/settings-naps.png" width="360" alt="Settings group Naps expanded">

## Sources

Settings → "Sleep sources" (or "Sources" in the "Backup and reset" card) opens the Sources screen:

- **The list.** "Apps that wrote sleep sessions to Health Connect in the last 90 days:" or, with history access,
  "(whole history):". Each app has its icon and name, "… sessions, last on …" and a switch. Only OHealth is enabled
  at first. Once PulseStages has written sessions, it is listed as "PulseStages (this app)" with "Its own estimates
  are never used as a source."
- **Priority.** The order of the list is the priority; the up and down buttons move an app. When two enabled apps
  recorded the same sleep, the one higher in the list is used ([Which sleep sessions are estimated](#which-sleep-sessions-are-estimated)).
- **"Heart rate and steps from any app".** Off: "Heart rate and steps are read from the same app as the sleep
  session." On: "Heart rate and steps are read from every app that writes them." This is the same setting as "Heart
  rate and steps from" in Settings, and changing it makes nights stale.
- **"Add an app by package name"** ("For a watch app that has not written sleep yet."): type a package name such as
  `com.example.watch` and tap "Add". A package added this way can be removed again while it has no sessions.
- **Warnings.** "No source is enabled: no nights will be estimated." and, when Health Connect could not be scanned,
  "Health Connect could not be scanned, so the configured sources are shown without their sessions. They can still
  be enabled, reordered and added."

Enabling or disabling a source makes the next run re-read the last 7 days (back to the last successful check, at most
30 days). Nights of a newly enabled app that are older than that are estimated by a [backfill](#history-backfill).

## Log

Settings → "Log" (or the "Log" link in "Debugging") shows PulseStages' internal log, newest first:

- filter chips per level ("Debug", "Info", "Warning", "Error"); all are selected at first;
- each entry shows time, level and component, the message, and "Night …" with the record key when it concerns one
  night; tap an entry to see all of it;
- the share button ("Share log") writes `pulsestages-log.txt` with every stored entry of the selected levels and opens
  the share sheet.

The log is kept on the phone only (up to 10,000 entries) and is mirrored to logcat with the tag `PulseStages`. Entries
below Settings → "Debugging" → "Log level" (default "Info") are not recorded; set it to "Debug" before reproducing a
problem. Before you share the file publicly, read it: it shows when the app ran, the package names of your sleep
sources, the record keys of your nights and details such as session lengths and heart-rate coverage
([privacy.md §5](privacy.md#5-logs)). Attach it to bug reports as described in
[CONTRIBUTING.md](../CONTRIBUTING.md#reporting-a-bug).

## Nights and naps

PulseStages processes every session in one of two modes. Settings → "Naps" → "Nap threshold" (180 min by default)
decides which: a session shorter than the threshold is a **nap**, a longer one a **night**; 0 turns naps off. The
length is that of the merged session (fragments and the gaps between them included). Sessions shorter than Settings →
"Sources" → "Minimum session length" (90 min by default) are ignored altogether.

With the defaults this means: sessions under 90 min are ignored, sessions of 90 min to 3 h (short nights and long
naps) are processed as naps, longer ones as nights. To estimate ordinary daytime naps, lower "Minimum session length",
for example to 30 min (the lowest allowed value is 10 min). New naps are then picked up automatically. Short sessions
that were ignored before are not estimated by "Recompute stale nights" (they have no entry to recompute) and the
setting does not make anything stale: tap "Backfill history" to estimate them.

A nap differs from a night in these ways:

- it is decoded by the bundle's dedicated nap model (with "Hybrid"), with its own decoder values ("Naps: algorithm";
  empty = the value of nights);
- its estimate uses the heart rate and steps of "Context margin of naps" minutes before and after it ("Naps:
  algorithm" → "Heart rate around naps"). Empty, the default, means the value of nights (Preprocessing → "Context
  margin", 30 min), as before; 0 means the nap itself only; the value cannot exceed that of nights, which sets how much
  is read from Health Connect. Like every algorithm setting it makes the estimated sessions stale, and the validation
  numbers apply to the default only;
- it is never compared with your heart-rate baseline and never counts for it, so it is never marked "Elevated heart
  rate";
- the stage-share sanity check skips it unless "Check the stage shares of naps" is on, so a nap without deep sleep is
  not flagged as implausible;
- it can have its own estimator ("Estimator for naps", "Same as nights" by default);
- it is left out of the calibration on reference nights and of the unsupervised adaptation;
- it is written as "Written for a nap" says:

| "Written for a nap" | Written to Health Connect | Shown in PulseStages |
|---|---|---|
| "Asleep / awake" (default) | Only awake and asleep: Health Connect's generic "sleeping" stage for light, deep, REM and no-data spans, "awake" for awakenings (with "Write awake as light sleep" the whole nap is "sleeping") | The estimated stages, labelled as the app's estimate, with the caution that nap stages are not validated |
| "Stages" | The estimated stages, like a night | The stages, with the caution that nap stages are not validated |
| "Nothing" | Nothing: the nap is not estimated | "Not estimated" with "Naps are not estimated (Settings, Naps)." (not shown as a problem) |

Switching to "Nothing" does not delete anything at once. The change makes earlier nap outputs stale, and an
earlier PulseStages session of a nap is removed when that nap is recomputed ("Recompute stale nights"). Another app's
night that overlaps a skipped nap is never removed or replaced because of the nap. After switching back from
"Nothing", "Recompute stale nights" writes the skipped naps.

The algorithm side of the two modes is in [ALGORITHM.md §18](../ALGORITHM.md#18-night-and-nap-modes-napmode).

### Why naps are written as asleep / awake

Up to version 0.1.0 naps were written with their stages by default. A later pre-registered test
([ALGORITHM.md §13.13](../ALGORITHM.md#1313-realistic-naps-pre-registered-not-adopted)) checked whether the stages of a
nap can be trusted, on nap-length windows cut from sleep-lab nights and against the published sleep-stage mix of
afternoon naps of healthy young adults. The nap model failed: its REM sleep hardly followed the heart rate (windows from
late in the night, which are rich in REM, got almost no more REM than windows at sleep onset), its mix of stages was not
realistic for naps (too little REM in sessions of 90–135 min with a flat heart rate that carries no information, too
much deep sleep in hour-long windows from late in the night), and on windows weighted to match the published stage mix
of naps it was less accurate than calling everything light sleep. Three new candidates did not pass either: one kept the
nap model and took the level of each stage, by session length, from the published naps (its REM still did not follow the
heart rate, and sessions without information got too much REM in short and too little deep sleep in long naps), one read
only the heart rate (it told deep sleep at sleep onset apart less well, and its stage mix was that of a night), and one
combined both (it failed all three kinds of check); ALGORITHM.md §13.13 lists every failing criterion of every model.
The earlier figure of about 6 in 10 epochs right
([ALGORITHM.md §13.12](../ALGORITHM.md#1312-a-dedicated-nap-model-pre-registered-adopted)) came almost entirely from
windows at sleep onset. Telling sleep from wake in a nap did pass the pre-registered bar (κ = 0.27 on nap-length windows
of sleep-lab nights, where at least 0.20 was required), so that is what is written. That κ was measured on windows cut
from nights; in afternoon recordings of young adults resting awake the model still decoded 60–85 min of sleep where an
EEG sleep stager found about 24 min, so the asleep / awake output relies on the watch's start and end of the nap. With
the default settings only sessions of 90 min to 3 h are estimated as naps (lower "Minimum session length" for shorter
ones); at those lengths, on windows weighted to the published stage mix of naps, the nap model's deep and REM shares
were 9.0–12.3 percentage points away from it, and sessions without information got too little REM at 90 and 120 min.

What this means:

- **In PulseStages** the Nights tab and the night detail still show the nap's estimated light, deep and REM sleep;
  the night detail says "Nap stages are not validated: …" and "Written to Health Connect as asleep / awake only; the
  stages shown here are the app's estimate." **These stages are not validated.** A nap shown with much deep sleep, or with none, tells you little about the nap itself.
- **In Health Connect** the nap's session has only "awake" and Health Connect's generic "sleeping" stage, which does
  not say whether the sleep was light, deep or REM. Apps that read it see when you slept and when you were awake, but
  no sleep stages ([What other apps see](#what-other-apps-see)).
- **In Sleep as Android** the nap has no sleep phases. Without "Sleep as Android compatibility" it imports the watch
  app's nap session, which has no stages ([Sleep as Android](#sleep-as-android)). With it, it imports PulseStages'
  session (confirmed on the development phone with Sleep as Android 20261006), but it drops Health Connect's generic
  "sleeping" stage, so a nap written as "Asleep / awake" shows as a time span without phases (only its awake parts, if
  any; with "Write awake as light sleep" not even those). To see nap phases there, turn on "Sleep as Android
  compatibility" and choose "Stages" (below); they are not validated. Other readers show the "sleeping" stage in their
  own way.
- Nights are not affected: they are still written with their stages.

**Writing nap stages anyway.** Set Settings → "Naps" → "Written for a nap" to "Stages", then tap "Recompute stale
nights" on Home to rewrite the naps written before. The stages are then written exactly as the app shows them; they
are still not validated.

### Updating from 0.1.0

- **The setting.** PulseStages stores only the settings that differ from the defaults, so the new default "Asleep /
  awake" applies wherever "Written for a nap" was left at the old default "Stages". That includes picking "Stages"
  explicitly in 0.1.0: it was the default then, so it was not stored. A choice of "Asleep / awake" or "Nothing" is
  kept. Importing a settings file exported by 0.1.0 brings "Stages" back, because an export contains every value
  ([Settings export, import and reset](#settings-export-import-and-reset)).
- **Sessions already in Health Connect** are not rewritten by the update: naps written by 0.1.0 keep their stages
  until they are recomputed.
- **Everything becomes stale.** The new default and the new setting "Context margin of naps" change the parameters
  hash, so every estimated night and nap is marked "Stale" after the update. With the default "Existing sessions" =
  "Skip up-to-date nights" nothing is rewritten until you ask: Home → "Recompute stale nights" rewrites the naps as
  asleep / awake and the nights with the same stages as before (the night model and its settings did not change). With
  "Never overwrite" the sessions are never rewritten ([Stale nights and recomputing](#stale-nights-and-recomputing)).

## Which sleep sessions are estimated

Each run turns the sleep sessions in Health Connect into nights and naps by these rules:

1. **Only enabled sources.** Sessions of apps that are not enabled on the Sources screen are ignored. PulseStages' own
   sessions are never a source.
2. **Sessions that already have stages** (any stage other than unknown or "sleeping") are skipped: they need no
   estimate. The exception is "Existing sessions" = "Recompute everything".
3. **Fragments are merged.** Sessions of one app with a gap shorter than "Merge gap" (30 min) become one night; the gap
   is written as awake. A nap that ends less than 30 min before the night therefore becomes part of the night.
4. **Too short is ignored.** A merged session shorter than "Minimum session length" (90 min) is not processed.
5. **Night or nap.** The rest is a night or a nap by the nap threshold ([Nights and naps](#nights-and-naps)).
6. **Priority between apps.** When sessions of two enabled apps overlap by more than half of the shorter one, they are
   one sleep, and the app higher on the Sources screen wins; the other is skipped as a lower-priority duplicate. This
   holds also when the higher-priority app recorded the sleep with its own stages: then PulseStages writes nothing for
   it, and an estimate it wrote earlier for the lower-priority session is removed (unless "Existing sessions" is
   "Never overwrite"). If that staged session is deleted later, the next run estimates the other session again.
7. **A piece of a night is not the night.** A session that lies mostly inside a longer session of another app but
   covers at most half of it (for example 40 minutes one watch recorded of a night another app recorded completely)
   never replaces that night, whatever the priority, as long as that longer session is used itself.

When the sessions of a night change (a new fragment, a deleted fragment), the night is rebuilt and its output is
rewritten; if every source session of a night is deleted, PulseStages deletes its own session too ("Source deleted").
The technical details are in [architecture.md §3.2](architecture.md#32-building-candidates).

## Automatic runs

PulseStages uses Android's WorkManager for all background work:

- **Periodic check** (every "Check for new nights every": "15 min", "30 min" or 1 to 24 hours, 1 h by default). Health
  Connect cannot tell an app that new data arrived, so PulseStages asks: the check asks Health Connect's Changes API
  for new, changed or deleted sleep sessions of the enabled sources. 15 minutes is the shortest period Android's
  WorkManager allows. A check that finds nothing new makes one request to Health Connect, so a short interval costs
  little; it mostly means more background wake-ups
  ([Battery and background restrictions](#battery-and-background-restrictions)). On the first run, after a source was
  enabled or disabled, when Health Connect reports the change token as expired, or when the token is older than 25
  days, it re-reads the last 7 days instead (back to the last successful check, at most 30 days).
- **Daily run** at "Daily run" (09:00 local time by default). The time is approximate: Android may delay background
  work.
- **Battery.** With "Only when the battery is not low" (on by default) both wait until the battery is not low.
- **Late heart rate.** The heart rate often reaches Health Connect hours after the sleep session (when the watch
  syncs), and Health Connect reports only new, changed and deleted sleep sessions. After the check, a run therefore
  also looks at recent sessions again, at most once every 55 minutes (below):
  - sessions that could not be estimated yet ("Not estimated", "Failed") are tried again, if their app is still
    enabled on the [Sources](#sources) screen;
  - nights and naps it already wrote are checked: their heart rate, steps and optional data are read again, and when
    they changed, the session is estimated again and updated.

  This look at recent sessions happens at most once every 55 minutes, so about once an hour also with a check every
  15 or 30 minutes; the checks in between only look for new sessions. Each time, sessions that ended in the last 36
  hours are looked at, and sessions that arrived late (up to 30 days after they ended) for 36 hours after they first
  appeared, with growing gaps (about 1, 2, 4, 8, 16 and 32 hours after). At most 8 written sessions are checked each
  time; the others wait for the next times. When a look was cut short (for example by Health Connect's rate limit),
  the next run looks again.
- **Updates of written sessions.** A written session is rewritten only when it would change: its start, its stages, its
  title or its notes, which hold the coverage, the confidence and the number of heart-rate samples. It keeps its record
  key and gets the next version, like a recompute, and the night detail says "Updated automatically" and why
  ([Night detail](#night-detail)). A session written from part of the heart rate is upgraded this way: a night estimated
  with the rules estimator because only 40–60 % of it had heart rate ("Rules fallback (low coverage)") gets the
  configured estimator ("Hybrid" by default) once at least 60 % has heart rate. Not updated: anything under "Existing
  sessions" = "Never overwrite"; stale nights (they wait for "Recompute stale nights"); sessions you deleted, in
  PulseStages or in Health Connect, which are not written again; a session never gets worse automatically: when the
  night now has fewer heart-rate samples than when it was written (the source app is replacing its data, or data was
  deleted), it stays as it is ("Recompute" uses the data as it is). Heart rate that arrives after these 36 hours is not
  picked up automatically: use "Recompute" on the night. The log has a line for every update, for example "updated: more
  heart rate arrived (46 -> 57 heart-rate samples)".
- **Background access.** Automatic runs read only with "Allow background" granted
  ([Permissions](#permissions)); "Last run" shows "no background access" otherwise.
- **"Estimate new"** runs the same check at once, as a foreground service with the notification "Estimating new
  nights", so it works without background access. It also looks at the recent sessions at once, even a few minutes
  after the last look; the automatic runs then wait 55 minutes before they look again. When it updated written
  sessions, its message says "… nights updated with late data."
- **Sleep as Android.** With "Ask Sleep as Android to sync" on (the default) and Sleep as Android installed, every run
  that wrote or updated a session (including a backfill or a recompute, and one that stopped after writing) leaves a
  sync request for Sleep as Android, which PulseStages sends once the recent nights are complete, usually 1–2 hours
  after you wake up; a new or recomputed session gets a second request 10 minutes after the first. While a request
  waits, the automatic runs also look for late heart rate once more after the nights first look complete, even within
  the hour ([Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync)).
- **Errors.** Health Connect rate-limit and transient errors are retried later with a growing delay (from 30 seconds,
  doubling up to 5 hours). A failure on one night is logged and does not stop the others.

Apart from these updates of recent sessions whose data changed, automatic runs never recompute nights that are already
up to date, and they never rewrite stale ones on their own (with the default "Existing sessions").

## Stale nights and recomputing

### What "stale" means

A night is **stale** when it was estimated with another algorithm version or with other parameters than the current
ones. PulseStages stores a version and a parameters hash with every session it writes; the hash covers every algorithm
setting, the bundled model, the personal model, "Heart rate and steps from", which optional channels are read, "Write
awake as light sleep" and "Sleep as Android compatibility" (only while it is on, so nights written without it keep
their hash). The title, the schedule and "Ask Sleep as Android to sync" are not part of it. Things that make nights
stale:

- a change of an algorithm setting (including the "Naps" groups and "Features" → "Optional channels");
- turning "Write awake as light sleep" or "Sleep as Android compatibility" on or off;
- an app update with a new model or new defaults;
- "Calibrate", "Reset to global model" or an unsupervised adaptation;
- granting or revoking an optional permission while its "Use …" setting is on.

Stale nights are marked "Stale" in the Nights list and in the night detail, counted on Home ("stale"), offered by Home
→ "Recompute stale nights", and by the "Recompute" action of the message Settings shows after a change. A nap skipped
under "Nothing" can be stale too.

### Existing sessions

Settings → "Written sessions" → "Existing sessions" decides what runs do with nights that already have a session:

| Option | Behaviour |
|---|---|
| "Skip up-to-date nights" (default) | Nights with the current version and hash are skipped; recent ones are rewritten only when more heart rate or other data arrived ([Automatic runs](#automatic-runs)). Stale nights are marked and recomputed only when you ask. |
| "Recompute everything" | Every run estimates the nights it reads again and rewrites their sessions, also for source sessions that already have stages. The check of recent written sessions for late data rewrites them only when their data changed, as with the default. |
| "Never overwrite" | An existing PulseStages session is never rewritten, not even by Recompute or when more heart rate arrives; only nights without a session are estimated. Home says so in the "Recompute" section. |

If a source session's start or end changes, the night is recomputed under every option except "Never overwrite".

### Recompute

- **Home → "Recompute stale nights"** recomputes only the stale nights, without confirmation.
- **Home → "Recompute all"** asks "Recompute all nights?" ("Every night is estimated again with the current settings
  and its session in Health Connect is rewritten. This can take a while.") and then recomputes every night.
- **Night detail → "Recompute"** recomputes one night.

A recompute runs as a foreground service with the notification "Recomputing nights" and the progress "Recomputing
nights: … of …". It also writes a night whose session you deleted before asking for it, but not a night you delete
while the recompute is pending or running. A recompute of many nights can reach Health Connect's quota: it then stops,
is retried automatically with a growing delay and skips the nights it already recomputed, so a long history can take
several runs until no night is stale ([health-connect.md §7](health-connect.md#7-errors-quotas-and-backoff)).

## History backfill

Home → "Backfill history" estimates every past night, oldest first, one week at a time:

- the range is "History depth" (Settings → "Sources"; 0 = all available history); without history access Health
  Connect returns nothing older than 30 days before PulseStages was first given access ("History was limited to 30
  days before PulseStages was first given access (no access to history)." after the run);
- it runs as a foreground service with the notification "Processing the sleep history" and shows "Week … of … ·
  nights found: …, estimated: …" on Home;
- "Cancel" stops it before its next night ("The last backfill was cancelled after week … of ….");
- it continues after a restart of the phone or the app; an interrupted backfill shows "Resume backfill";
- an error stops it ("The last backfill stopped with an error after week … of … (see the log).") and starting it again
  starts over;
- a finished backfill reports "The last backfill finished on …: … nights estimated.";
- it writes nights whose session you deleted before you started it, but not a night you delete while it runs.

A backfill skips nights that are already up to date. Nights that are stale stay stale (unless their source sessions
changed); use "Recompute stale nights" for them.

## CSV export of a night

Night detail → "Export CSV" saves one row per epoch through the system file picker, suggested name
`pulsestages-yyyyMMdd-HHmm.csv` (the local start time of the session). The columns are
`epoch,start_ms,has_data,hr_raw,hr_smooth,<features…>,p_awake,p_light,p_deep,p_rem,stage,mode`. The CSV comes from a
new estimate with the current settings, like the epoch details; nothing is written to Health Connect by it, and it can
differ from what was written earlier. The message "CSV saved: …" confirms the export. The file holds your heart rate:
treat it as health data.

## Settings export, import and reset

- **"Export JSON"** saves every setting, defaults included, through the system file picker (suggested name
  `pulsestages-settings.json`). The sources and their priority are part of it.
- **"Import JSON"** checks the whole file first. If anything is wrong, nothing changes and "Settings file rejected"
  lists every problem ("… problems were found; nothing was changed:"). Unknown keys are ignored and missing keys take
  their defaults, so files of older versions load; a file of a newer version loads unless it uses an option or a value
  this version does not accept.
- **"Reset to defaults"** ("Reset all settings?") returns every setting, including the sources and the algorithm
  parameters, to its default. Sessions already in Health Connect are not changed. "Reset to physiology table" (in "HMM
  emissions") resets only the HMM emission table.

PulseStages stores only the values you changed. Defaults that come from the validation (estimator, hybrid decoder
settings, "Written for a nap" and others) therefore follow app updates for every value you never changed
([Updating from 0.1.0](#updating-from-010) is an example). An exported file contains the
defaults of the version that exported it: importing it into a newer version keeps those older values wherever they
differ from the new defaults. To return a single value to its default, set it to the default shown in the
[settings reference](settings-reference.md).

The export does not contain the list of estimated nights, the personal model or the log.

## Deleting your data

- **All sessions.** Home → "Delete my sessions from Health Connect" asks "Delete all my sessions?" and then deletes
  every sleep session PulseStages wrote, including sessions it finds by reading its own data (for example from before
  a reinstall; on Android 9–13 only those Health Connect lets it read, which without history access excludes sessions
  from more than 30 days before the current permission grant). It cancels a running backfill and every recompute requested before, so they cannot write the deleted
  nights again, and marks the nights "Deleted by you". The result is "Deleted … sessions from Health Connect." or
  "PulseStages had no sessions in Health Connect." Automatic runs do not write these nights again; a backfill or a
  recompute started afterwards does.
- **One night.** Night detail → "Delete my session" works the same way for that night.
- **In Health Connect.** Health Connect → "Permissions and data" → "App permissions" → PulseStages → "Delete app
  data", choose a period, then "Next" → "Delete" ([Google help](https://support.google.com/android/answer/12201232)).
  The same page can revoke every permission. PulseStages does not watch its own sessions for deletions made outside
  the app (it follows only the enabled sources), so its Nights list keeps showing those nights as "Estimated"; the
  automatic update of recent sessions with late data does not write them again, but a recompute does. Prefer the
  buttons in the app when you want PulseStages to know.
- **Clearing the app's storage** in Android's app settings deletes the list of nights, the settings, the personal
  model and the log, but not the sessions in Health Connect. When PulseStages processes such a night again, it finds
  its earlier session and replaces it, so the night does not get a second one (on Android 9–13, as long as Health
  Connect lets it read that session, see above).
- **Uninstalling** deletes the app's own storage (the list of nights, settings, personal model and log) and revokes
  its permissions, but **does not delete its sessions from Health Connect**. Google's help lists an app that is
  uninstalled but still has data in Health Connect under "Inactive apps" in Health Connect → "Permissions and data" →
  "App permissions" ([Google help](https://support.google.com/android/answer/12201872)). Delete the sessions before
  uninstalling if you do not want to keep them.
- **Other apps' data** (OHealth's sessions, heart rate, steps) is never changed or deleted by PulseStages; Health
  Connect only lets an app delete its own records.
- **Copies in other apps.** An app that imported PulseStages' sessions keeps its own copy. Sleep as Android, for
  example, stores imported nights as its own records (with a cloud icon) and does not pick up later changes of them
  ([Sleep as Android](#sleep-as-android)). Deleting PulseStages' sessions does not remove these copies; delete them in
  that app.

What is stored where is described in [privacy.md](privacy.md).

## What other apps see

### Two sessions per night

For every estimated night, Health Connect contains two overlapping sleep sessions: the watch app's session (OHealth's
has no stages) and PulseStages' session with stages, titled "Sleep stages from heart rate (estimate)" (or the
"Session title" you set). PulseStages' session has the start and end of the watch app's session; with "Sleep as
Android compatibility" it starts 1 second earlier. Apps that list sessions see both and decide themselves which to
show (Sleep as Android imports only the one that starts first, [Sleep as Android](#sleep-as-android)); Health
Connect's own sleep totals use the priority you set in [Data sources and priority](#data-sources-and-priority).

PulseStages writes its sessions with the recording method "unknown" and the phone as the device, because no sensor
measured the stages; the notes of the record name the algorithm, its version, the parameters hash and the source
sessions. Apps that filter by recording method or device type may treat these sessions differently from watch data;
this was not tested. The full record format, including every key of the notes, is in
[health-connect.md §4](health-connect.md#4-data-written).

### Stage mapping

| PulseStages | Written as (Health Connect stage) |
|---|---|
| Awake | awake; light with "Write awake as light sleep" |
| Light | light |
| Deep | deep |
| REM | REM |
| No data (heart-rate gap longer than "Interpolation limit", 10 min) | unknown with the default "Data gaps" = "Unknown"; light or the neighbouring stage with "Light" / "Nearest" |
| Gap between merged fragments | awake |

A nap written as "Asleep / awake" (the default for naps) gets only awake and Health Connect's generic "sleeping"
stage. The written stages cover the session exactly, with no gaps or overlaps. With "Sleep as Android compatibility"
the session and its first stage start 1 second before the watch app's session, so the first stage is 1 second longer
than the estimate shows.

## Sleep as Android

Sleep as Android can import sleep that other apps wrote to Health Connect: in Sleep as Android → Settings → Services,
turn on "Health Connect" and grant the permissions; after that, sync with "Sync now" on the same screen, never by
tapping the "Health Connect" row while it says "Connected" ([Recommended setup](#recommended-setup), step 4). Its
documentation marks the integration beta and describes it as two-way (it also exports its own sleep to Health
Connect); it does not say how Sleep as Android chooses between overlapping sessions of different apps
([Sleep as Android docs](https://sleep.urbandroid.org/docs/services/health_connect.html)). The import behaviour under
[How Sleep as Android imports](#how-sleep-as-android-imports) was observed with Sleep as Android 20261006 and confirmed
on the development phone (Android 16, ColorOS 16), except where a point says it is not yet confirmed; other versions
may behave differently. With [Sleep as Android compatibility](#sleep-as-android-compatibility) it imports
PulseStages' sessions with their stages for nights it has not imported yet (confirmed on the development phone with
Sleep as Android 20261006), and [Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync) (on by default) lets
PulseStages start that sync once a night it wrote is complete.

### How Sleep as Android imports

- **Only when it syncs.** Sleep as Android syncs with Health Connect when you tap Settings → Services → "Sync now",
  when it saves a record (added, edited or saved automatically) and when its own sleep tracking ends. Its documentation
  says the integration then works in the background, but there is no periodic import: if you only view your sleep in
  it, tap "Sync now". That button syncs every service its summary lists (for example "Health Connect, SleepCloud,
  SleepSync") and reads Health Connect for the last 30 days, as do the syncs it starts itself after saving a record
  or when its tracking ends (observed with Sleep as Android 20261006, not yet confirmed on the development phone). Only the sync button of the
  Health Connect screen it opens when you turn the integration on reads further back (up to 5 years).
  Without Health Connect's history access ("Access past data") it reads other apps' data only from the last 29 days.
  PulseStages asks it to sync once the nights it wrote are complete unless you turn that off
  ([Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync)).
- **One session per night: the one that starts first.** It reads the sleep sessions of all apps, orders them by start
  time and keeps only the first of every group of overlapping sessions; sessions that merely touch (one ends where the
  next starts) count as overlapping. The others are dropped without a message. Health Connect's
  [Data sources and priority](#data-sources-and-priority) list does not change this.
- **A tie goes to the watch app.** Without "Sleep as Android compatibility", PulseStages' session starts at exactly the
  same moment as the watch app's session. Health Connect returns sessions with the same start in the order they were
  inserted, and the watch app's session is always the older one, so Sleep as Android imports OHealth's session, which
  has no stages, and drops PulseStages' session. If another app's session of the night starts even earlier, Sleep as
  Android keeps that one.
- **An imported night stays as it was imported.** Sleep as Android never imports a session that overlaps a record it
  already has, including the records it imported earlier (shown with a cloud icon), and it never picks up later changes
  of an imported record, such as PulseStages' [automatic update](#automatic-runs) after late heart rate or a recompute.
  A night stays as it was imported until you delete it in Sleep as Android and sync again.
- **Heart rate and SpO2 come with the next sync.** Only these come from Health Connect later (its own awake detection
  can then add awake stretches, below): a sync adds them only to
  the nights Sleep as Android already had before it, so a night it has just imported gets them on the next sync
  (confirmed on the development phone: a second "Sync now" added heart rate to PulseStages' nights). Heart rate that
  reaches Health Connect later is added to the night on later syncs while the night is less than 30 days old (not yet
  confirmed on the development phone).
- **Stages.** It imports Health Connect's awake, light, deep and REM stages as its own awake, light, deep and REM, and
  drops "unknown" and the generic "sleeping" stage. A night's "No data" spans, written as unknown by default, therefore
  have no phase there, and a nap written as "Asleep / awake" (the default for naps) shows as a time span without
  phases (only its awake parts, if any; with "Write awake as light sleep" not even those). To see nap phases in Sleep as
  Android, set Settings → "Naps" → "Written for a nap" to "Stages" and turn on
  [Sleep as Android compatibility](#sleep-as-android-compatibility); those stages are not validated
  ([Why naps are written as asleep / awake](#why-naps-are-written-as-asleep--awake)).
- **Thin stripes instead of phases.** A record without light, deep or REM sleep gets no hypnogram in Sleep as Android.
  It then shows only short awake bars from its own heart-rate-based awake detection, which is on by default
  ([awake detection](https://sleep.urbandroid.org/docs/sleep/awake_detection.html)). These thin stripes are not
  PulseStages' stages. Once heart rate is added to a night with PulseStages' stages, the same detection can put awake
  stretches over those stages (observed with Sleep as Android 20261006, not yet confirmed on the development phone);
  Sleep as Android → Settings → "Sleep tracking" → "Awake detection" → "Heart rate monitoring" sets how sensitive it
  is ("Medium sensitivity" by default; "Disabled" turns it off).
- **Deleting a night in Sleep as Android** removes only its own record; nothing is deleted from Health Connect.

On the development phone, without "Sleep as Android compatibility", Sleep as Android showed OHealth's stage-less
session for each night (cloud icon, OHealth's times, only thin awake stripes), and deleting such a night in Sleep as
Android and syncing brought the stage-less session back every time. Nights with stages from an Apple Watch that another
app wrote to Health Connect showed a full hypnogram.

### Sleep as Android compatibility

Settings → "Written sessions" → "Sleep as Android compatibility" (off by default): "Starts each written session 1
second before the source session, so that Sleep as Android imports it instead of the source's session without stages.
Nights Sleep as Android has already imported must be deleted there and synced again. Changes the written data."

- **What changes.** Every session PulseStages writes, naps included, starts exactly 1 second before the night's start
  (the start of its first source session), and its first stage starts there too, so that stage is 1 second longer than
  estimated. The end, the time-zone offsets, the record key and the title stay the same, and the notes keep their
  keys; only the parameters hash in the notes changes, because the setting is part of it. PulseStages' own screens
  keep showing the night's times and the estimated stages.
- **What it is for.** PulseStages' session then sorts before the watch app's session, so Sleep as Android imports it
  instead of the session without stages, for nights it has not imported yet. Confirmed on the development phone with
  Sleep as Android 20261006: after a recompute and a sync it showed PulseStages' sessions (cloud icon) of nights it
  held no record of (they had been deleted there before the sync), with a full hypnogram and the same phase shares as
  PulseStages (for one night 11 % deep, 20 % REM, 64 % light and 5 % awake in both apps). A session of another app
  that starts even earlier still wins.
- **The start in Sleep as Android.** Sleep as Android shows whole minutes, and the session starts 1 second before the
  watch app's, for example at 3:03:59, so Sleep as Android shows 3:03 where the watch app shows 3:04.
- **Stale nights.** The setting changes the written data and is part of the [parameters hash](#what-stale-means) only
  while it is on, so the update that added it makes no night stale. Turning it on, or off again, makes every written
  night stale. Settings then offers "Recompute"; a recompute rewrites each session in place, under the same record
  key with the next version. Stale nights are not updated automatically when more heart rate arrives, so recompute them.
- **"Never overwrite".** With "Existing sessions" = "Never overwrite" existing sessions are never rewritten; only
  nights written afterwards start earlier.

### Ask Sleep as Android to sync

Settings → "Written sessions" → "Ask Sleep as Android to sync" (on by default): "Once the nights PulseStages wrote or
updated are complete (usually 1–2 hours after you wake up), asks Sleep as Android to sync with Health Connect, and
once more 10 minutes later so that Sleep as Android adds their heart rate and SpO2. Works with “Sleep as Android
compatibility”. Each request also runs Sleep as Android's backup and its other syncs."

- **What it does.** Sleep as Android has no periodic import, and a night it imports gets its heart rate and SpO2 only
  on the following sync. With this setting, every PulseStages run that wrote or updated a session (the automatic
  checks, "Estimate new", a backfill, a recompute) leaves a request for Sleep as Android: its public sync request,
  which its documentation lists as "Request Backup Sync"
  ([Intent API](https://sleep.urbandroid.org/docs/devs/intent_api.html)). PulseStages sends it once the recent nights
  are complete (below). After a new or recomputed night it sends one more 10 minutes after the first, so that the night
  gets its heart rate and SpO2. An update after late heart rate gets only the one request: Sleep as Android already has
  that night and adds the new heart rate in that sync, but it keeps the stages it imported first. If it does not have
  the night yet (the setting was off when the night was written, or the night was deleted there), it imports it in
  that sync and adds its heart rate at the next request. A run that wrote and then stopped (Health Connect's rate
  limit, a refused access, another Health Connect error, or Android stopping it) still leaves the request; only when
  Android kills PulseStages' process is it lost, and then the next request covers that night, because Sleep as
  Android's sync reads the last 30 days.
- **Why it waits.** Sleep as Android imports a night once and keeps that version for good
  ([An imported night stays as it was imported](#how-sleep-as-android-imports)). The watch app, however, may publish a
  night in steps: on the development phone OHealth wrote a first version that ended at about 07:29 while the user was
  still asleep, and extended the same session to the real wake only later, on weekend days hours later. PulseStages
  0.4.0 asked Sleep as Android to sync right after its first write, and Sleep as Android kept nights that end at 07:29
  (17–19 October 2026, confirmed on the development phone). PulseStages still writes every version to Health Connect at
  once, but asks Sleep as Android only when the recent nights look complete: PulseStages has processed every recent
  session of the watch app as it is now, and the watch app's heart rate reaches about an hour past the session's end
  (with the default "Merge gap" of 30 minutes), uploaded after the session last changed. The nights must stay like that
  for 15 minutes and through one more look for late heart rate, which PulseStages then makes at its next check. This is
  usually 1–2 hours after you wake up, with checks every hour up to an hour more; a recompute waits at least 15 minutes.
  The message of a run you started says so: "Sleep as Android will be asked to sync once the nights are complete."
- **No "Sync now" in the morning.** Until PulseStages has asked, do not tap "Sync now" in Sleep as Android, and do
  not add or edit a record there (that syncs too): such a sync imports whatever Health Connect holds at that moment,
  possibly a night that is cut short or the watch app's session without stages, and Sleep as Android keeps it. The
  night appears in Sleep as Android by itself once it is complete; PulseStages' [Log](#log) then has "asked Sleep as
  Android to sync (…)".
- **A night that ends too early in Sleep as Android** (for example at 07:29, while PulseStages' Nights tab shows the
  whole night), or that shows an older version: delete that night in Sleep as Android (only the record with the cloud
  icon) and do not undo the deletion: Sleep as Android's undo writes the old record back, also over a night that was
  imported again since. Then either tap "Recompute" on that night in PulseStages ([Night detail](#night-detail)): once
  the nights are complete, the request after the recompute makes Sleep as Android import the whole night, and the
  second request adds its heart rate. Or, in the afternoon, when the watch app has finished the last night, tap
  Settings → Services → "Sync now" in Sleep as Android, and tap it again a few minutes later, once the first sync has
  finished, for the heart rate (a tap while a sync still runs does nothing). Do not use the sync button of its Health
  Connect screen, Backup → "Download from Health Connect" or the "Health Connect" row for this
  ([Recommended setup](#recommended-setup), "What not to do"). Only nights of the last 29 days come back. Confirmed on
  the development phone on 2026-10-19 for three nights that ended at 07:29 there: after they were deleted in Sleep as
  Android, two syncs imported the whole nights and added their heart rate.
- **Without background access.** Without "Allow background" ([Permissions](#permissions)) automatic runs read nothing,
  and PulseStages cannot check the nights in the background either. It checks them only when it runs in the
  foreground, for example for "Estimate new": if the nights are complete then, the request goes out about 15 minutes
  later, also when you have left the app, but without another check; if they are not complete yet, nothing more
  happens until the next run in the foreground. Tap "Estimate new" once the night is complete (1–2 hours after you
  wake up), or allow background access.
- **What is sent.** Only the request: no night, no heart rate, no other data
  ([privacy.md §2](privacy.md#2-everything-stays-on-the-device)). It is sent only when Sleep as Android is installed,
  and a run that wrote nothing leaves no request.
- **On by default.** Without Sleep as Android the setting does nothing, and the request carries no data. With Sleep as
  Android installed, every night PulseStages writes also starts Sleep as Android's syncs and its backup, with the side
  effects below. If you do not want that (for example because you prefer to sync Sleep as Android yourself), turn the
  setting off in Settings → "Written sessions"; Sleep as Android then imports only in its own syncs
  ([How Sleep as Android imports](#how-sleep-as-android-imports)).
- **What it needs.** Turn on Sleep as Android's Health Connect integration (Settings → Services → "Health Connect")
  and, in PulseStages, "Sleep as Android compatibility" ([Recommended setup](#recommended-setup), steps 1 to 4):
  without it the sync it requests imports the watch app's session without stages, as Sleep as Android's own syncs do,
  and Sleep as Android keeps it. Open Sleep as Android at least once after installing it: Android delivers nothing to
  an app that was never opened or was force-stopped, and PulseStages cannot tell. Sleep as Android must also be allowed
  to run in the background ([Battery and background restrictions](#battery-and-background-restrictions)).
- **What you see.** A new night appears in Sleep as Android once it is complete, usually 1–2 hours after you wake up
  (Sleep as Android starts its Health Connect sync about 10 seconds after the request), and its heart rate (and SpO2,
  if the watch writes it) about 10 minutes later. The message of a run you started adds "Sleep as Android was asked to
  sync." or, while the request waits, "Sleep as Android will be asked to sync once the nights are complete."
  PulseStages' [Log](#log) has "not asked yet: …" with what the request waits for (for example "no heart rate from …
  from 60 min after the session's end yet"), "asked Sleep as Android to sync (…)" and, for the second request, "asked
  Sleep as Android to sync again: heart rate and SpO2 for the nights it imported". A warning that ends with "Sleep as
  Android may keep the earlier version" means that a night changed after the request: check that night in Sleep as
  Android and repair it as described above if it ends too early.
- **Side effects.** Every request runs a full sync of Sleep as Android: its other enabled services (for example Google
  Fit, Samsung Health or SleepSync), as "Sync now" does, and also its backup (a local backup file by default), which
  "Sync now" does not write. With heart rate on a night, its heart-rate awake detection can put awake stretches over
  PulseStages' stages (Sleep as Android → Settings → "Sleep tracking" → "Awake detection" → "Heart rate monitoring";
  "Disabled" turns it off). Each sync probably adds another set of 10-minute heart-rate values to the nights that
  already have heart rate. Sleep as Android may also write heart rate and SpO2 of the nights it tracked itself to
  Health Connect. Its sync counts against its own Health Connect quota, not PulseStages'.
- **What is confirmed.** On the development phone (2026-10-17): Sleep as Android takes the request at once, runs its
  backup and its other syncs, starts its Health Connect sync of the last 30 days 10 seconds later and again after the
  second request, and the number of its own sessions in Health Connect does not grow; a second sync adds heart rate to
  the nights it imported, and the tested night shows its heart rate there. Its log also shows each sync offering heart
  rate again for nights that already have it. Observed with Sleep as Android 20261006 but not yet confirmed on the
  development phone: the other side effects above. Its documentation describes the request only as a
  backup sync, so the Health Connect part may change in any version. The wait for complete nights is tested with the
  nights of 18 and 19 October replayed in PulseStages' tests; it has not yet run on the development phone.
- **How to check.** A night imported this way has the cloud icon, as after "Sync now", and gets a heart-rate chart
  after the second request. Such nights are never exported back to Health Connect as Sleep as Android's own sessions.
  With adb, Sleep as Android's log (`adb logcat -s SleepAsAndroid`) has the line "HealthConnect sessions found:
  total=… ours=… others=…" for every sync. "ours" is the number of its own sessions in Health Connect of the last 30
  days, counted before that sync exports anything. It must not grow by the nights PulseStages wrote: compare the line
  of the first request with that of the second, and allow for nights you tracked or added in Sleep as Android itself
  in between.
- **Stale nights.** The setting changes nothing that is written and is not part of the
  [parameters hash](#what-stale-means): turning it on or off makes no night stale. Once it is off, nothing more is
  sent, not even a pending second request.

### Recommended setup

1. In PulseStages, turn on Settings → "Written sessions" → "Sleep as Android compatibility".
2. Recompute the nights: tap "Recompute" in the message Settings shows, which recomputes every stale night (with many
   nights Health Connect's write quota spreads this over several runs, see [Recompute](#recompute)), or later Home →
   "Recompute stale nights". For a single night, use "Recompute" in its [night detail](#night-detail).
3. In Sleep as Android, delete the affected nights. Delete only records with the cloud icon: they are the copies it
   imported. A record without the icon is Sleep as Android's own data (from its own tracking or a backup), and
   deleting it loses that data. Delete only nights of the last 29 days: "Sync now" (step 4) reads Health Connect only
   for the last 30 days, and without Health Connect's history access ("Access past data") Sleep as Android reads other
   apps' data only from the last 29 days, so an older night does not come back. Do not undo the deletion: Sleep as
   Android's undo writes the old record back, also after step 4 has imported the night again.
4. In Sleep as Android → Settings → Services, tap "Sync now" (its summary lists the enabled services, for example
   "Health Connect, SleepCloud, SleepSync"). It imports PulseStages' sessions of the deleted nights with their stages
   (confirmed on the development phone with Sleep as Android 20261006 through the sync button of its Health Connect
   screen; "Sync now" runs the same import for the last 30 days). Their heart rate and SpO2 come with the next sync:
   tap "Sync now" again a few minutes later (confirmed on the development phone for heart rate). **Do not tap the "Health Connect"
   row** on that screen while it says "Connected": the row is a switch, and tapping it turns the integration off.
   Sleep as Android then revokes all its Health Connect permissions (they disappear once its process ends); turning
   the integration on again asks for them again, and "Access past data" must then be switched on again in Health
   Connect → "App permissions" → "Sleep" → "Additional access".
5. For new nights, let Sleep as Android sync only once the night is complete: not before PulseStages has written it (the
   night is "Estimated" in the Nights tab), and not before the watch app has published the whole night, which on the
   development phone came only after the real wake, hours after a first version that ended at about 07:29. A sync before
   that imports the watch app's session or a night that ends too early, and Sleep as Android keeps it for good: steps 3
   and 4 are then needed again for that night. So do not tap "Sync now" in the morning, and remember that Sleep as
   Android also syncs when it saves a record and when its own tracking ends. Later versions of a night, such as an
   automatic update after late heart rate, reach Sleep as Android only through steps 3 and 4 as well.
   [Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync) (on by default) makes PulseStages start the sync once
   the nights it wrote are complete; leave it on for this, or, if you turned it off, sync yourself in the afternoon.
   Sleep as Android's own syncs still happen.
6. Keep Sleep as Android disabled on PulseStages' [Sources](#sources) screen (only OHealth is enabled by default)
   unless you also track sleep with Sleep as Android. Sessions it writes appear in the Sources list, and because they
   carry stages, an enabled and higher-priority Sleep as Android session would replace PulseStages' estimate for that
   night.
7. Sessions with stages from Sleep as Android also appear in Compare as reference nights. Its phases are derived from
   its own sensor model (the documentation describes light and deep phases from actigraphy,
   [sleep tracking theory](https://sleep.urbandroid.org/docs/sleep/sleep_tracking_theory.html)), not from a sleep
   lab. If Compare lists Sleep as Android sessions for nights you did not track with it, do not calibrate on them.

**What not to do.**

- Do not use Sleep as Android's Backup → "Download from Health Connect" for this. The records it creates get no cloud
  icon, and Sleep as Android later exports them to Health Connect as its own sessions: duplicates of your nights in
  Health Connect.
- Do not turn on "Write awake as light sleep" for Sleep as Android. It imports awake as awake, the thin stripes come
  from its own awake detection, and on a nap written as "Asleep / awake" the setting removes even the awake parts.
- Do not tap the "Health Connect" row in Sleep as Android → Settings → Services to sync: while it says "Connected" it
  turns the integration off and Sleep as Android revokes its Health Connect permissions. Use "Sync now" (step 4).
- Do not use the sync button of the Health Connect screen that Sleep as Android opens when you turn the integration on:
  it reads up to 5 years back and imports every session of that time it does not have, including nights you deleted
  there long ago. Use "Sync now" (step 4).
- Do not tap "Sync now" in the morning, while the watch app may still extend the night
  ([Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync)).
- Do not expect Health Connect's [Data sources and priority](#data-sources-and-priority) to change what Sleep as
  Android imports; it does not.

The stages are always visible in PulseStages' Nights tab, whatever another app shows.

## Battery and background restrictions

Automatic runs depend on Android letting PulseStages run in the background.

- **Android's battery setting per app.** Android offers "Unrestricted", "Optimized" (the default) and "Restricted" for
  each app ([background optimization](https://developer.android.com/topic/performance/background-optimization)).
  "Restricted" fully prevents background work, so automatic runs do not happen; "Optimized" lets Android delay them.
  On Pixel phones the setting is in Settings → Apps → See all apps → PulseStages → App battery usage → Allow background
  usage ([Pixel help](https://support.google.com/pixelphone/answer/7015477)); other phones name it differently. If
  automatic runs are rare or missing, choose "Unrestricted" for PulseStages.
- **Battery saver and "Only when the battery is not low".** With the PulseStages setting on (default), automatic runs
  wait while the battery is low.
- **Vendor battery managers (vendor-specific).** Some phone makers add their own background limits on top of Android's.
  ColorOS, on OPPO phones like the one PulseStages was developed on, has its own battery management with per-app
  options for background activity. The menu names differ between ColorOS versions and were not verified for this
  guide; look in the battery section of PulseStages' app info for options that allow background activity.
- **The check interval.** "Check for new nights every" = "15 min" or "30 min" wakes PulseStages up to four times as
  often as the default 1 h. A check that finds nothing new is short and makes one request to Health Connect, but every
  wake-up costs a little battery, and Android may delay frequent background work of an "Optimized" app. Late heart
  rate is still looked for at most about once an hour ([Automatic runs](#automatic-runs)), so a shorter interval
  mainly brings new nights sooner.
- **The watch app.** The same applies to OHealth: if it cannot sync to Health Connect in the background, PulseStages
  has nothing new to read.
- **Sleep as Android.** With [Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync) (on by default),
  PulseStages checks whether the nights are complete, and sends the request, in background work of its own, and Sleep
  as Android's sync runs in its own background work, so the same applies to both.

Without "Allow background", automatic runs never read, whatever the battery settings; "Estimate new" still works while
the app is open.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Home: "Health Connect is not available on this device or profile." | A work profile, or Health Connect is not supported on the device | Use PulseStages in the personal profile; it cannot work without Health Connect. |
| Home: "Health Connect needs to be installed or updated." | Android 9–13 without a current Health Connect app | Tap "Install or update Health Connect". |
| Home: "Required permissions: n of 4"; runs end with "Nothing was read: a required permission is missing." | Sleep, heart rate or steps permission missing or revoked | Tap "Grant permissions". |
| Home: "Health Connect no longer shows the permission dialog. Open its settings to grant the permissions." | The permission dialog was cancelled twice | Tap "Open Health Connect settings" and grant the permissions there. |
| "Last run" shows "no background access"; message "Nothing was read in the background: allow background access on the Home tab." | `READ_HEALTH_DATA_IN_BACKGROUND` not granted | Tap "Allow background". If Home says "This Health Connect version cannot grant it.", open the app regularly or use "Estimate new". |
| "Next automatic run" is late, or runs are missing | Android delays background work; battery low with "Only when the battery is not low"; app battery setting "Restricted"; a vendor battery manager | See [Battery and background restrictions](#battery-and-background-restrictions). |
| Nights: "No nights yet" | No source enabled ("No source is enabled: no nights will be estimated." on Sources); the watch app does not write sleep to Health Connect; sessions shorter than "Minimum session length"; no run yet | Enable the watch app on Sources, check the watch app's Health Connect access, tap "Estimate new"; for older nights "Backfill history". |
| Sources lists no app ("No app wrote sleep sessions in the scanned range.") | The watch app has not written sleep sessions to Health Connect in the scanned range (90 days without history access) | Check the watch app's Health Connect sync; add the package with "Add an app by package name" to enable it in advance. |
| Sources: "Reading sleep is not permitted. Grant the permissions on the Home tab." | Sleep read permission missing | Grant the permissions on Home. |
| Naps are missing | They are shorter than "Minimum session length" (90 min) and were ignored | Lower "Minimum session length", for example to 30 min. New naps are picked up automatically; tap "Backfill history" to estimate the earlier ones. |
| Naps are "Not estimated": "Naps are not estimated (Settings, Naps)." | "Written for a nap" is "Nothing" | Set "Written for a nap" to "Asleep / awake" (the default) or "Stages" and tap "Recompute stale nights". |
| A nap has no light, deep or REM sleep in Health Connect or in another app | "Written for a nap" is "Asleep / awake", the default since 0.1.1 | Expected: the stages are in PulseStages' Nights tab and are not validated ([Why naps are written as asleep / awake](#why-naps-are-written-as-asleep--awake)). To write them anyway, choose "Stages" and tap "Recompute stale nights". |
| A nap ends up inside a night | It ended less than "Merge gap" (30 min) before the night, so it was merged as a fragment | Expected; set "Merge gap" to 0 to keep them apart (this affects all fragments). |
| A night is "Not estimated": "There is no heart-rate data for this night." | The heart rate is not in Health Connect (yet), or it comes from another app | Wait: sessions are retried automatically for 36 hours ([Automatic runs](#automatic-runs)). Later use "Recompute". If another app writes the heart rate, turn on "Heart rate and steps from any app". |
| A night is "Not estimated": "Too little of the night has heart-rate data." | Less than 40 % of the epochs have heart rate (between 40 % and 60 % the rules estimator is used) | Check that the watch measures heart rate during sleep. Heart rate that arrives within 36 hours is picked up automatically; after that use "Recompute". |
| Warning "Too few heart-rate data: the rules estimator was used." (flag "Rules fallback (low coverage)") | Only 40–60 % of the night had heart rate when it was estimated, often because the rest had not reached Health Connect yet | Wait: a recent night is updated automatically once at least 60 % has heart rate; the night detail then says "Updated automatically". After 36 hours use "Recompute". |
| A recent night's stages changed by themselves; the night detail says "Updated automatically" | More heart rate (or steps or optional data) arrived after the first estimate, and the new estimate changed the session | Expected: the session now uses all the data. It keeps its record key and gets the next version ([Automatic runs](#automatic-runs)). |
| A night is "Not estimated": "The session is too short." | "Estimator session gate" (Preprocessing) is above 0 | Set it back to 0 (the default). |
| A night is "Failed" ("Estimating or writing this night failed. The log has the details; Recompute tries again.") | An error while estimating or writing; the log has "processing failed: …" | Read the [Log](#log), then "Recompute". Report it if it persists ([CONTRIBUTING.md](../CONTRIBUTING.md)). |
| "Health Connect's rate limit was reached. Try again later." or "Last run": "rate limit reached, will retry" | Health Connect's request quota | Wait; runs retry automatically with a growing delay. |
| "Health Connect stayed busy, so the run gave up. Try again later." | Repeated rate-limit or transient errors | Start the action again later. |
| "Another run was already in progress." | Another estimate, backfill or recompute was running | Wait for it to finish. |
| Night marked "Stale" | Settings, model or permissions changed since it was estimated | Home → "Recompute stale nights". |
| Recompute does nothing: "The night was not rewritten: it cannot be estimated, or the overwrite policy is “never”." | "Existing sessions" is "Never overwrite", or the night cannot be estimated with the current settings | Change "Existing sessions", or check the night's reason. |
| Backfill: "History was limited to 30 days before PulseStages was first given access (no access to history)." | No history access | Tap "Allow history", then "Backfill history" again. |
| Backfill: "A backfill was interrupted after week … of …; resume it to continue." | The phone or the app was restarted, or Android stopped the work | Tap "Resume backfill". |
| Night detail: "Health Connect did not respond. Try again." under the charts | A transient Health Connect error while reading the heart rate | Tap "Retry". |
| Night detail: "With the current settings this night is not estimated. …" | The fresh estimate with the current settings is not possible (reason follows) | See the reason; the written session is not affected. |
| Warning "Hybrid model unavailable: the HMM was used."; log "… HYBRID falls back to HMM" | The bundled model could not be read or does not match the build | Reinstall an official APK; report it. |
| Compare: "No reference nights" | No other app wrote sessions with real stages in the range | Expected with OHealth alone; choose a longer range (needs history access). |
| Compare: "Calibration was not possible: the reference nights have no labelled epochs with heart rate." | The reference sessions have no heart rate in Health Connect | Calibration needs reference nights with both stages and heart rate; check that a watch measured heart rate on those nights. |
| Settings: "The stored settings could not be read; the defaults are shown." | The settings store could not be read (a storage error) | Restart the app; if it persists, import an exported settings file or set your values again. |
| Settings are back at their defaults; the log has "Settings file was unreadable and has been reset to defaults" | The settings file was damaged, so PulseStages replaced it with the defaults | Import an exported settings file, or set your values again. |
| Settings import: "Settings file rejected" | A value has the wrong type or is out of range | Fix the listed values in the file; nothing was changed. |
| Sleep as Android shows no stages, only thin stripes | It imported the watch app's session, which has no stages: of overlapping sessions it keeps the one that starts first, and without "Sleep as Android compatibility" PulseStages' session starts at the same moment and loses the tie. The stripes are Sleep as Android's own heart-rate-based awake detection. | Turn on "Sleep as Android compatibility", recompute the nights, delete the nights of the last 29 days in Sleep as Android (only records with the cloud icon; older nights do not come back) and tap "Sync now" in its Settings → Services ([Recommended setup](#recommended-setup), steps 3 and 4). It then imports PulseStages' sessions of those nights with their stages. |
| Sleep as Android still shows nights without stages after turning on "Sleep as Android compatibility" | The nights were not recomputed yet (they are "Stale"); "Existing sessions" is "Never overwrite"; Sleep as Android still holds the night it imported earlier; or another app's session of the night starts even earlier | Recompute the stale nights; under "Never overwrite" existing sessions are never rewritten, so first set "Existing sessions" to "Skip up-to-date nights" ([Existing sessions](#existing-sessions)). Then delete the night in Sleep as Android (cloud icon; only nights of the last 29 days come back) and tap "Sync now" ([Recommended setup](#recommended-setup), steps 3 and 4). |
| Sleep as Android shows an older version of a night | It never picks up later changes of a night it imported (an automatic update after late heart rate, a recompute) | Delete the night in Sleep as Android (cloud icon; only nights of the last 29 days come back; do not undo the deletion), then tap "Recompute" on the night in PulseStages, or tap "Sync now" there twice in the afternoon ([Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync)). |
| A night in Sleep as Android ends too early, for example at 07:29, while PulseStages' Nights tab shows the whole night | Sleep as Android synced while the watch app had published only part of the night, which it extends later: after a "Sync now" or a record saved there in the morning, or after a request of PulseStages 0.4.0, which asked right after its first write. Sleep as Android keeps the version it imported first | Delete the night in Sleep as Android (cloud icon; do not undo the deletion), then tap "Recompute" on the night in PulseStages, or tap "Sync now" there twice in the afternoon ([Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync)). Do not tap "Sync now" in the morning. |
| A nap shows in Sleep as Android as a time span without phases | "Written for a nap" is "Asleep / awake", and Sleep as Android drops Health Connect's generic "sleeping" stage, so only the awake parts, if any, are left (with "Write awake as light sleep" not even those); or, without "Sleep as Android compatibility", it imported the watch app's nap session, which has no stages | Expected. To see nap phases, set "Written for a nap" to "Stages" (not validated) and turn on "Sleep as Android compatibility", recompute, then delete the nap in Sleep as Android (cloud icon; only naps of the last 29 days come back) and tap "Sync now" ([Recommended setup](#recommended-setup), steps 3 and 4). |
| Sleep as Android shows a night starting one minute earlier than the watch app (3:03 instead of 3:04) | With "Sleep as Android compatibility" the session starts 1 second before the watch app's (3:03:59), and Sleep as Android shows whole minutes | Expected ([Sleep as Android compatibility](#sleep-as-android-compatibility)). |
| Sleep as Android imports nothing any more, and Health Connect lists none of its permissions | The "Health Connect" row in its Settings → Services was tapped while it said "Connected": the row is a switch, so the integration was turned off and Sleep as Android revoked all its Health Connect permissions | Turn "Health Connect" on again there and grant the permissions, switch on "Access past data" again in Health Connect → "App permissions" → "Sleep" → "Additional access", and sync with "Sync now" from then on ([Recommended setup](#recommended-setup)). |
| Sleep as Android does not import new nights by itself although "Ask Sleep as Android to sync" is on | The nights are not complete yet: PulseStages asks only once the watch app's heart rate reaches about an hour past the night's end, usually 1–2 hours after you wake up; PulseStages has no background access, so it checks only when it runs in the foreground; Sleep as Android's Health Connect integration is off; Sleep as Android was not opened since it was installed, or it was force-stopped (Android then delivers no request to it); a battery restriction of either app; or no PulseStages run wrote a night yet | PulseStages' [Log](#log) has "not asked yet: …" with what the request waits for, and "asked Sleep as Android to sync (…)" once it was sent. Without background access tap "Estimate new" once the night is complete, or tap "Allow background" ([Permissions](#permissions)). Open Sleep as Android once and check that Settings → Services lists "Health Connect" as connected (do not tap that row); allow both apps to run in the background ([Battery and background restrictions](#battery-and-background-restrictions)). Tap "Sync now" in Sleep as Android yourself only in the afternoon, once the night is complete. |
| A night in Sleep as Android has stages but no heart rate | Sleep as Android adds heart rate only on the sync after the one that imported the night (or Health Connect has no heart rate of that night) | Tap "Sync now" there again; with [Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync) this happens by itself about 10 minutes after the first request for a new night. |
| Sleep as Android shows awake stretches over PulseStages' stages once a night has heart rate | Probably its own heart-rate awake detection, which runs when heart rate is added to a night (not yet confirmed on the development phone) | PulseStages' Nights tab shows the stages as written. To avoid it, lower or turn off Sleep as Android → Settings → "Sleep tracking" → "Awake detection" → "Heart rate monitoring" ("Disabled"). |
| The release APK does not install over an installed version | The installed build is a debug build (or a self-built one) with another signing key | Uninstall first ([Release or debug APK](#release-or-debug-apk)). |
| Obtainium asks to pick an APK, offers the `-debug` APK, or refuses an update because of the signing certificate | PulseStages was added to Obtainium without the APK filter, or the installed build is a debug build | Set the filter, or uninstall the debug build first ([Install and update with Obtainium](#install-and-update-with-obtainium)). |
| "No app on this device can open this." | No app handles the requested screen (Play Store, Health Connect settings, file picker) | Open Health Connect or the Play Store manually. |

If nothing here helps, set "Log level" to "Debug", reproduce the problem, and share the log with a bug report
([CONTRIBUTING.md](../CONTRIBUTING.md)).

## FAQ

**Is this a medical device?** No. The stages are estimated from heart rate every 2 minutes and agree only "fairly"
with a sleep lab on average, with large differences between people. Do not use them for medical decisions.

**Does PulseStages change OHealth's data?** No. It writes its own sessions and deletes only its own records; Health
Connect does not let an app change or delete other apps' data.

**Does it send my data anywhere?** No. It has no internet permission; all processing happens on the phone
([privacy.md](privacy.md)). With "Ask Sleep as Android to sync" on (the default) and Sleep as Android installed, it
sends Sleep as Android a sync request that carries no data.

**Why are there two sleep sessions for every night?** PulseStages cannot add stages to OHealth's record, so it writes
its own next to it. Set the priority in Health Connect so totals use the staged one
([Data sources and priority](#data-sources-and-priority)).

**Why does Sleep as Android show my nights without stages?** Of overlapping sessions it imports only the one that
starts first, and without "Sleep as Android compatibility" that is the watch app's session, which has no stages; the
thin stripes are its own awake detection. Turn the setting on, recompute, delete those nights of the last 29 days in
Sleep as Android (only records with the cloud icon; older nights do not come back) and tap Settings → Services →
"Sync now" there ([Recommended setup](#recommended-setup)); it then imports PulseStages' sessions of those nights with
their stages.

**Does PulseStages tell Sleep as Android when a night is ready?** Yes, with "Ask Sleep as Android to sync" (on by
default; turn it off in Settings → "Written sessions"): once the nights PulseStages wrote or updated are complete,
usually 1–2 hours after you wake up, it asks Sleep as Android to sync, and after a new or recomputed night asks again
10 minutes later so that the night also gets its heart rate and SpO2
([Ask Sleep as Android to sync](#ask-sleep-as-android-to-sync)). It waits because Sleep as Android keeps the first
version of a night it imports, and the watch app may publish a night that ends too early before the whole night.
Turn on "Sleep as Android compatibility" as well, or Sleep as Android imports the watch app's session without stages.

**Can I use another watch?** Yes, if its app writes sleep sessions and heart rate to Health Connect: enable it on the
Sources screen. The model was trained on Apple Watch heart rate thinned to 2 minutes; other watches were not
validated.

**Why does a night have "No data" minutes?** The heart rate had a gap longer than "Interpolation limit" (10 min).
Such spans are written as unknown by default; "Data gaps" (Post-processing) can write light sleep or the neighbouring
stage instead.

**Why did a night's stages change after an update?** An app update does not change them: the written session stays
until you recompute it. The night is marked "Stale" because the model or the defaults changed; "Recompute stale
nights" rewrites it with the new version. What does change a session on its own is late data: when more heart rate
(or steps or optional data) of a recent night reaches Health Connect within 36 hours, PulseStages estimates the night
again and rewrites its session if the result differs; the night detail then says "Updated automatically" and why
([Automatic runs](#automatic-runs)).

**Why does Health Connect show my naps without stages?** "Written for a nap" is "Asleep / awake" by default: no nap
stage model passed the pre-registered checks, so the stages of naps stay in the app and are not validated
([Why naps are written as asleep / awake](#why-naps-are-written-as-asleep--awake)).

**Why is a session that already has stages not estimated?** A source session with its own stages (from a watch
app that records them) needs no estimate and is skipped. Choose "Recompute everything" under "Existing sessions" to
estimate such sessions too.

**What does "Elevated heart rate" mean?** The night's lowest heart rate was more than 12 % above your baseline: the
median of the lowest heart rates of your previous 14 nights ("Baseline nights"; at least 3 nights are needed).
ALGORITHM.md names illness, alcohol and heat as typical causes
([§4](../ALGORITHM.md#4-night-statistics-and-base-features-features)). On such nights the estimators count a higher
heart rate less as a sign of being awake, and the confidence is lowered. Naps never get this mark.

**Can I make the estimates fit me better?** With reference nights from another device that records stages, Compare →
"Calibrate" fits a personal model ([Compare and calibration](#compare-and-calibration)).

**In which language is the session title written?** In the phone's language at the time of writing (English, or
Russian on a phone set to Russian). A title typed into "Session title" is used as typed. The title is not part of the
parameters hash, so changing it does not make nights stale.

**Does uninstalling remove my sessions from Health Connect?** No. Delete them first with "Delete my sessions from
Health Connect" ([Deleting your data](#deleting-your-data)).

**Where are the validation numbers and the method?** In [ALGORITHM.md](../ALGORITHM.md); the training code is in
[tools/train/README.md](../tools/train/README.md).

<!-- The "Get it on Obtainium" button: Obtainium's web redirect to an obtainium://app/ import link with the settings of
     "Install and update with Obtainium" (GitHub drops obtainium:// links). README.md has the same link. -->
[obtainium-import]: https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.alien2003.pulsestages%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Falien2003%2FPulseStages%22%2C%22author%22%3A%22alien2003%22%2C%22name%22%3A%22PulseStages%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5EPulseStages-%5B0-9.%5D%2B%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22allowedSigningCertHashes%5C%22%3A%5C%22ce5546e83c03eac9837b93fc2c04d2e37abd1b778c3ef9e01b67cd1602e35be5%5C%22%7D%22%7D
