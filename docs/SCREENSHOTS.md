# Screenshots and demo mode

Screenshots in `docs/` are taken from **synthetic nights only**, never from real health data. Debug builds have a demo
mode for this; release builds contain none of its code (it lives in `app/src/debug/`, and `MergedManifestTest` checks
that the release manifest has no demo entry).

## What demo mode does

- The **Nights** list, the **night detail** and the **Compare** tab show synthetic data instead of the ledger and
  Health Connect. A banner on these screens says so.
- The nights come from core-algo's `SyntheticNightGenerator` with fixed seeds: 14 nights starting at about 23:00 local
  time on the last 14 days, estimated in memory by the real `SleepStager` with the bundled hybrid model and the current
  settings. Some nights are varied so that every state can be shown:

  | Night (days ago) | Shows |
  |---|---|
  | 2 | "elevated heart rate" marker and warning |
  | 3 | a 75-minute heart-rate gap (UNKNOWN band in the hypnogram, data-gap warning) |
  | 5 | "stale" marker (written with other settings) |
  | 9 | a night that is not estimated (one heart-rate sample) |

- Compare shows 8 synthetic reference nights whose "real" stages are the generator's true hypnograms; Calibrate runs
  core-algo's `PersonalCalibrator` and keeps the personal model **in memory only**.
- **Nothing is read from or written to Health Connect**, and nothing is stored in the app's database: Delete my session,
  Recompute and Calibrate only change the in-memory demo data. Home, Settings, Sources and Log keep showing the real
  state.

## Switching it on and off

- From the launcher: debug builds have a second icon, **PulseStages demo**. Each tap toggles demo mode and restarts the
  app.
- With adb (the Windows adb from WSL, see the README):

  ```sh
  "$ADB_WIN" shell am start -n io.github.alien2003.pulsestages/.demo.DemoActivity --ez enabled true
  "$ADB_WIN" shell am start -n io.github.alien2003.pulsestages/.demo.DemoActivity --ez enabled false
  ```

The mode is stored in the app's private preferences, so it survives a restart until it is switched off again.

## Taking the screenshots

```sh
"$ADB_WIN" install -r "$(wslpath -w app/build/outputs/apk/debug/app-debug.apk)"
"$ADB_WIN" shell am start -n io.github.alien2003.pulsestages/.demo.DemoActivity --ez enabled true
# navigate to the screen, then:
"$ADB_WIN" shell screencap -p /sdcard/Download/nights.png
"$ADB_WIN" pull /sdcard/Download/nights.png "$(wslpath -w docs/screenshots/)"
"$ADB_WIN" shell rm /sdcard/Download/nights.png
```

Useful variations: unfold the phone for the list-and-detail layout, `"$ADB_WIN" shell cmd uimode night yes|no` for the
dark and light themes, `"$ADB_WIN" shell settings put system font_scale 1.3` for large fonts. In the night detail, tap
or drag on the hypnogram to show an epoch's probabilities before taking the picture. Switch demo mode off afterwards.
