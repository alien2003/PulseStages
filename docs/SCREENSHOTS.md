# Screenshots and demo mode

Screenshots in `docs/` are taken from **synthetic nights only**, never from real health data. Debug builds have a demo
mode for this, switched on and off inside the app (Settings → **Debug build** → **Demo mode**). Release builds contain
none of its code, texts or switch: it lives in `app/src/debug/`, `ReleaseApkTest` checks R8's mapping and aapt2's dump
of the minified release APK, and `MergedManifestTest` checks that every build has a single launcher icon.

## What demo mode does

- The **Nights** list, the **night detail** and the **Compare** tab show synthetic data instead of the ledger and
  Health Connect. A banner on these screens says so. They switch at once, without a restart, in both directions; a
  night detail opened from the other data returns to the list.
- The nights come from core-algo's `SyntheticNightGenerator` with fixed seeds: 14 nights starting at about 23:00 local
  time on the last 14 days, and two afternoon naps starting at 15:00 one day ago (150 min) and six days ago (100 min),
  estimated in memory by the real `SleepStager` with the bundled hybrid model and the current settings. The naps are
  between the default "Minimum session length" (90 min) and the default nap threshold (180 min), so an installation
  with the default settings would process them as naps too; the demo data do not go through the source filters. Some
  nights are varied so that every state can be shown:

  | Night (days ago) | Shows |
  |---|---|
  | 2 | "elevated heart rate" marker and warning |
  | 3 | a 75-minute heart-rate gap (UNKNOWN band in the hypnogram, data-gap warning) |
  | 5 | "stale" marker (written with other settings) |
  | 9 | a night that is not estimated (one heart-rate sample) |

- Compare shows 8 synthetic reference nights and one 120-minute reference nap whose "real" stages are the generator's
  true hypnograms; Calibrate runs core-algo's `PersonalCalibrator` and keeps the personal model **in memory only**.
- **These three screens read nothing from Health Connect and write nothing to it**, and store nothing in the app's
  database: Delete my session, Recompute and Calibrate only change the in-memory demo data.
- **The rest of the app is not switched.** Background estimation (the periodic and daily runs) and the Home actions
  (Estimate new, Backfill, Recompute all, Delete my sessions) keep reading Health Connect and writing your real nights
  to it while demo mode is on; Home, Settings, Sources and Log keep showing the real state.

## Switching it on and off

In a debug build, open **Settings** and scroll to the **Debug build** card near the end, just above "Versions and
licences". Turn on **Demo mode** ("The Nights and Compare tabs show synthetic nights and read nothing from Health
Connect. Background estimation, Home and Sources keep working with your real data."); turn it off to see the real data
again. The app keeps a single launcher icon: there is no separate demo entry, and no adb command for it.

The switch is a per-device debug convenience, not a setting: it is kept in its own private preferences file
(`pulsestages_demo_mode`), so it survives a restart until it is switched off again, and it is not part of the settings,
their JSON export or the params hash. Uninstalling the app or clearing its storage turns it off.

## The screenshots in the README

The README embeds these files from `docs/screenshots/`. They are rendered on the JVM, without a phone, by
`app/src/testDebug/.../screenshots/ScreenshotsTest.kt` (Robolectric with native graphics, light theme with the app's own
palette, a 411 × 891 dp phone at 2×, English; `nights-wide.png` in an 800 × 700 dp landscape window, the medium width
class of a foldable's inner screen): Nights, night detail and Compare use the demo sources described above with the
bundled model; Home, Settings and Sources use fixed example states (no phone data at all). Every test run
renders them into `app/build/screenshots/`; to replace the files in `docs/screenshots/`, run from the repository root:

```sh
mise exec -- ./gradlew :app:testDebugUnitTest --tests '*ScreenshotsTest' -PrecordScreenshots
```

Render them again whenever a screen, the bundled model or the default settings change.

| File | Screen | What it should show |
|---|---|---|
| `home.png` | Home tab | Health Connect and permission status, sources and schedule, counters, actions |
| `nights.png` | Nights tab | The demo nights and naps with stage shares and the "Nap" / "Stale" / "Elevated heart rate" / "Low confidence" markers |
| `nights-wide.png` | Nights tab, wide window | The list and, beside it, the detail of the newest demo night (two-pane layout of a foldable's inner screen) |
| `night-detail.png` | A night's detail | Hypnogram, heart-rate curve, an epoch's probabilities (tap or drag on the chart first), warnings and actions |
| `compare.png` | Compare tab | Kappa, per-stage metrics and confusion matrix of the 8 synthetic reference nights |
| `settings.png` | Settings tab | Backup and reset actions and the first parameter groups |
| `settings-naps.png` | Settings tab, "Naps" group | Nap threshold, what is written for a nap and the nap estimator (the group is expanded by default) |
| `sources.png` | Settings → Sleep sources | The app list with switches, priority buttons and the manual package field |

## Taking screenshots on the phone

On the phone, Home, Settings and Sources are not switched to demo data: they show the phone's real state, for example
the counters, the last run and the apps that wrote sleep sessions. Check those pictures for anything personal before
committing them.

Run these from the repository root, in a shell that has `ADB_WIN` (for example `mise exec -- bash`).
`docs/screenshots/` must exist before the pull: `wslpath -w` turns a missing directory into a relative Windows path.

```sh
mkdir -p docs/screenshots
"$ADB_WIN" install -r "$(wslpath -w app/build/outputs/apk/debug/app-debug.apk)"
# in the app: Settings → Debug build → Demo mode on; navigate to the screen, then:
"$ADB_WIN" shell screencap -p /sdcard/Download/nights.png
"$ADB_WIN" pull /sdcard/Download/nights.png "$(wslpath -w docs/screenshots/)"
"$ADB_WIN" shell rm /sdcard/Download/nights.png
```

Useful variations: unfold the phone for the list-and-detail layout, `"$ADB_WIN" shell cmd uimode night yes|no` for the
dark and light themes, `"$ADB_WIN" shell settings put system font_scale 1.3` for large fonts. In the night detail, tap
or drag on the hypnogram to show an epoch's probabilities before taking the picture. Switch demo mode off afterwards
(Settings → Debug build).
