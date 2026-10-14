<p align="center"><img src="docs/icon.svg" width="112" height="112" alt="PulseStages icon"></p>

# PulseStages

[![CI](https://github.com/alien2003/PulseStages/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alien2003/PulseStages/actions/workflows/ci.yml)

[Latest release](https://github.com/alien2003/PulseStages/releases/latest) ·
[Licence: WTFPL](LICENSE) · [User guide](docs/user-guide.md) · [Documentation index](docs/README.md)

PulseStages is an Android app that estimates sleep stages (awake, light, deep, REM) from the heart rate that a watch
app writes to [Health Connect](https://developer.android.com/health-and-fitness/health-connect), and writes the result
back to Health Connect as its own sleep session, clearly labelled as an estimate. It was built for an OPPO Watch paired
with OHealth (`com.heytap.health.international`): OHealth writes sleep sessions without stages, only a start and an
end, plus a heart-rate sample about every 2 minutes. PulseStages turns those into a stage-by-stage hypnogram on the
phone, without network access, and never changes or deletes the data of other apps. Daytime naps and nights are
processed in two separate modes. The output is a statistical estimate, not a measurement, and it is not meant for
medical decisions.

- [Screenshots](#screenshots)
- [How accurate it is](#how-accurate-it-is)
- [Quick start](#quick-start)
- [Nights and naps](#nights-and-naps)
- [Features](#features)
- [Documentation](#documentation)
- [Project status](#project-status)
- [Licence](#licence)
- [Acknowledgements and dataset attribution](#acknowledgements-and-dataset-attribution)

## Screenshots

The screenshots contain no real health data. They are rendered on the JVM by `ScreenshotsTest` from synthetic nights
(the debug build's demo data, estimated by the real algorithm with the bundled model) and fixed example states;
[docs/SCREENSHOTS.md](docs/SCREENSHOTS.md) explains how they are made.

| Home | Nights | Night detail |
|---|---|---|
| ![Home tab with Health Connect status, permissions, schedule, counters and actions](docs/screenshots/home.png) | ![Nights list with stage shares, a nap badge and markers](docs/screenshots/nights.png) | ![Night detail with hypnogram, heart-rate curve and epoch probabilities](docs/screenshots/night-detail.png) |

| Compare | Settings | Sources |
|---|---|---|
| ![Compare tab with kappa, per-stage metrics and confusion matrix](docs/screenshots/compare.png) | ![Settings tab with backup actions and the first groups](docs/screenshots/settings.png) | ![Sources screen with app list, switches and priority buttons](docs/screenshots/sources.png) |

On a wide window (the inner screen of a foldable, 600 dp or wider) the Nights tab shows the list and the night detail
side by side:

![Nights tab in the two-pane layout of a foldable's inner screen](docs/screenshots/nights-wide.png)

The Settings group "Naps" decides which sessions are naps and what is written for them:

<img src="docs/screenshots/settings-naps.png" width="360" alt="Settings group Naps expanded: nap threshold, Written for a nap, Estimator for naps">

## How accurate it is

**Nights.** The estimators were validated leave-one-subject-out on the 31 polysomnography (PSG) nights of PhysioNet
sleep-accel (Walch et al.), with the heart rate thinned to one value every 2 minutes as OHealth delivers it; the worse
of two emulations of OHealth's 2-minute value is reported. The default HYBRID estimator reaches Cohen's κ = 0.339 ±
0.169 (mean ± sd over subjects), accuracy 0.612 and macro-F1 0.522; the mean absolute error per night is about 33 min
for deep sleep, 41 min for REM and 19 min for awake. Averaged over four training seeds, the same setup without its
stage-proportion correction scores κ 0.317, so treat about 0.32–0.34 as the expected figure. This is "fair"
agreement. It varies strongly between people (per-subject κ from about −0.03 to 0.65), and single nights can be far
off. The simpler HMM and RULES estimators score κ 0.070 and 0.076.

**Naps.** No public dataset has labelled naps. A dedicated nap model was trained and tested, in a pre-registered
experiment, on nap-length pieces cut from the sleep-lab and EEG-headband nights. On the PSG pieces about 6 in 10 of the
30-second epochs matched PSG (accuracy 0.61, against 0.50 for the earlier night-model approach and 0.53 for calling
the whole nap light sleep), κ rose from 0.17 to 0.36 and the summed stage-share error fell from 70 to 53 percentage
points. Nap stages are still rough: 4 in 10 epochs are wrong, deep sleep tends to be over-called in the first hour,
and the test pieces were night sleep, with only 11 that end with an awakening as a real nap does.

**Who it was tested on.** The training and test subjects were healthy adults wearing an Apple Watch (the Walch subjects
were 29 ± 9 years old). An OPPO watch, older people, sleep disorders, heart medication or arrhythmias may behave
differently.

[ALGORITHM.md](ALGORITHM.md) is the authoritative source for every number: nights in
[§13.1](ALGORITHM.md#131-headline-walch-psg-ohealth-like-session-window), the stage-proportion correction in
[§13.10](ALGORITHM.md#1310-stage-proportions-prior-exponent-and-proportion-matching-pre-registered-v3-adopted), naps in
[§13.12](ALGORITHM.md#1312-a-dedicated-nap-model-pre-registered-adopted), published results for comparison in
[§14](ALGORITHM.md#14-published-results-for-context) and the limitations in
[§16](ALGORITHM.md#16-limitations). If this summary differs from ALGORITHM.md, ALGORITHM.md wins.

## Quick start

1. **Download** `PulseStages-<version>.apk` (the signed release build) from the
   [latest release](https://github.com/alien2003/PulseStages/releases/latest). Optionally check it against
   `SHA256SUMS.txt` with `sha256sum -c --ignore-missing SHA256SUMS.txt` (GNU coreutils; without `--ignore-missing`
   the debug APK you did not download is reported as missing).
2. **Install** it on the phone (Android 9 or later). Android asks you to allow installing apps from the browser or file
   manager you opened it with.
3. **Health Connect.** On Android 14 and later it is part of the system. On Android 9–13, open PulseStages and tap
   "Install or update Health Connect" on the Home tab; this path is supported but has not been tested on a device, and
   reports are welcome. Make sure your watch app (OHealth by default) writes sleep and heart rate to Health Connect.
4. **Permissions.** On the Home tab tap "Grant permissions" and allow sleep (read and write), heart rate and steps;
   then "Allow history" (older nights) and "Allow background" (automatic runs). On Android 13 and later, "Allow
   notifications" shows the progress of long runs.
5. **First estimate.** Tap "Estimate new" on the Home tab, then open the Nights tab. The first run reads the sleep
   sessions of the last 7 days. New nights are processed automatically from then on (every hour and once a day).
6. **Priority in Health Connect.** In Health Connect → "Permissions and data" → "Manage data" → "Data sources and
   priority", put PulseStages above OHealth for sleep, so that readers that use Health Connect's sleep totals take the
   staged session. If PulseStages is not in the list yet, check again after its first session was written.
7. **Backfill.** Tap "Backfill history" on the Home tab to estimate every older night, one week at a time.

The [user guide](docs/user-guide.md) covers every screen, permission and setting, and has a
[troubleshooting table](docs/user-guide.md#troubleshooting).

## Nights and naps

A session shorter than the nap threshold (Settings → "Naps" → "Nap threshold", 180 min by default) is a nap, a longer
one a night. Sessions shorter than "Minimum session length" (Settings → "Sources", 90 min by default) are ignored
altogether. To estimate short daytime naps, lower that setting (for example to 30 min): new naps are then picked up
automatically, and "Backfill history" estimates the short sessions that were ignored before. By default a nap is
written with its estimated stages ("Written for a nap": "Stages"); "Asleep / awake" writes only sleep and awakenings,
and "Nothing" skips naps. The [user guide](docs/user-guide.md#nights-and-naps) explains the two modes; the algorithm
side is in [ALGORITHM.md §18](ALGORITHM.md#18-night-and-nap-modes-napmode).

## Features

- **Stage estimation on the phone** from Health Connect heart rate and steps, with SpO2, respiratory rate and HRV
  (RMSSD) used when present and permitted. Three estimators: HYBRID (default: a trained classifier decoded with
  explicit bout durations), HMM and RULES.
- **Two processing modes**, night and nap, with a dedicated nap model and a choice of what is written for a nap
  (stages, asleep / awake only, or nothing).
- **Its own, clearly marked Health Connect record** per night: title "Sleep stages from heart rate (estimate)", the
  algorithm version and a hash of every parameter in its notes; the source data are never modified.
- **Idempotent writes**: one output per night, keyed by the source sessions and rewritten under the same key on
  recompute, verified by reading it back; fragments of a night are merged, and deleted sources remove the output.
- **Selectable sources** with a priority order: OHealth by default, any app that writes sleep sessions to Health
  Connect can be enabled.
- **Automatic runs** (hourly check through Health Connect's Changes API and a daily run), a resumable **history
  backfill**, and **recompute** of stale nights after a settings or app update.
- **Nights list and night detail**: hypnogram as written, heart-rate curve, per-epoch probabilities and features,
  warnings, a comparison with the RULES estimator, CSV export; nap badge and an All / Nights / Naps filter; list and
  detail side by side on a foldable's inner screen.
- **Compare and calibrate** against reference nights with real stages from another app (kappa, accuracy, F1,
  confusion matrix), with an optional personal model.
- **Every parameter in Settings**, grouped, with hints and validation; JSON export and import; reset to defaults.
- **Deletion of everything it wrote** to Health Connect, from the app, for all nights or one night. Uninstalling
  PulseStages does not remove its sessions from Health Connect: tap "Delete my sessions from Health Connect" first
  ([deleting your data](docs/user-guide.md#deleting-your-data)).
- **No `INTERNET` permission** (removed in the manifest and checked by a test), app backups off, internal log only on
  the device.
- **English and Russian** user interface (the Russian translation is in `res/values-ru`); the app follows the phone's
  language.

## Documentation

| Document | What it covers |
|---|---|
| [docs/user-guide.md](docs/user-guide.md) | End-user manual: install, permissions, every screen, naps, automatic runs, backfill, export, deletion, Sleep as Android, troubleshooting, FAQ |
| [docs/settings-reference.md](docs/settings-reference.md) | Every setting with its JSON key, default, allowed values and effect (generated from the code) |
| [docs/architecture.md](docs/architecture.md) | Modules, data flow, the Room ledger, idempotency, background work, error handling, testing strategy |
| [docs/health-connect.md](docs/health-connect.md) | Permissions, data read and written, notes schema, Changes API, quotas, priority |
| [docs/privacy.md](docs/privacy.md) | What is processed and stored, where it stays, logs, exports, deletion |
| [docs/development.md](docs/development.md) | Toolchain setup, build, tests, lint, running on a phone, CI and releases |
| [ALGORITHM.md](ALGORITHM.md) | The algorithm, every parameter, the validation protocol and all validation numbers |
| [PLAN.md](PLAN.md) | Design decisions and verified research |
| [docs/INTERCHANGE.md](docs/INTERCHANGE.md) | Formats shared by the Kotlin code and the training scripts |
| [docs/SCREENSHOTS.md](docs/SCREENSHOTS.md) | How the screenshots are rendered, and the debug build's demo mode |
| [tools/train/README.md](tools/train/README.md) | Dataset preparation, training and leave-one-subject-out validation |
| [tools/train/DATASETS.md](tools/train/DATASETS.md) | The datasets, their licences and how they are used |
| [docs/prereg/](docs/prereg/) | Pre-registrations of the algorithm experiments, with their checksums and timestamped command logs |
| [core-algo/SYNTHETIC_RESULTS.md](core-algo/SYNTHETIC_RESULTS.md) | Regression results on synthetic nights |
| [CHANGELOG.md](CHANGELOG.md) | Changes per release |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Bug reports, pull requests, commit style and the pre-registration rule |
| [SECURITY.md](SECURITY.md) | How to report a vulnerability, signing key policy |
| [docs/README.md](docs/README.md) | Index of all documents |

## Project status

- **First release: 0.1.0.** See the [changelog](CHANGELOG.md) and the
  [releases](https://github.com/alien2003/PulseStages/releases).
- **Platform.** Android 9 and later (minSdk 28, targetSdk 36, compileSdk 37). The phone used during development was
  an OPPO Find N6 (foldable) with Android 16 and ColorOS 16. The Android 9–13 path, with Health Connect as a separate
  app from Google Play, has not been run on a device.
- **Tests.** JVM unit, Robolectric and render tests only; there are no instrumented tests against a real Health
  Connect.
- **Not verified yet.** How Sleep as Android handles the two overlapping sessions of a night (OHealth's and
  PulseStages'); whether OHealth writes SpO2 or respiratory rate at night (the app works either way); the behaviour
  of readers that filter by recording method or device type.
- **Known limitations** of the algorithm are listed in [ALGORITHM.md §16](ALGORITHM.md#16-limitations).

## Licence

WTFPL — do whatever you want with the code. See [LICENSE](LICENSE).

The bundled model is a produced work of the datasets below and carries their attribution notice.

## Acknowledgements and dataset attribution

The bundled model `app/src/main/assets/models/hybrid_model.json` was trained on two open-access PhysioNet datasets,
both available under the Open Data Commons Attribution License (ODC-By 1.0). The app shows the same notice in Settings
→ "Versions and licences".

> Contains information from *Motion and heart rate from a wrist-worn wearable and labeled sleep from
> polysomnography* (https://physionet.org/content/sleep-accel/1.0.0/) and from *BIDSleep: A Multi-Night
> Instantaneous Heart Rate and Accelerometry Dataset with EEG Sleep Stage Labels*
> (https://physionet.org/content/bidsleep-dataset/1.0.1/), which are made available under the ODC Attribution
> License (https://opendatacommons.org/licenses/by/1-0/).

The model holds parameters derived from these datasets, not the recordings. Citations:

- Walch O. (2019), sleep-accel v1.0.0, PhysioNet, https://doi.org/10.13026/hmhs-py35
- Walch O, Huang Y, Forger D, Goldstein C. Sleep 42(12):zsz180 (2019), https://doi.org/10.1093/sleep/zsz180
- Song T, Zhang Y, Zhou Z, Dutta J. (2026), BIDSleep v1.0.1, PhysioNet, https://doi.org/10.13026/rees-1092
- Song TA et al. IEEE TBME 73(4):1596–1608 (2026), https://doi.org/10.1109/TBME.2025.3612158
- Pollard T et al. PhysioNet as a global platform for biomedical research, Nature Health (2026),
  https://doi.org/10.1038/s44360-026-00096-z

Dataset details, licences and the reasons another dataset (DREAMT) is not used are in
[tools/train/DATASETS.md](tools/train/DATASETS.md).

OHealth, OPPO, Health Connect and Sleep as Android are names of their respective owners. PulseStages is an independent
open-source project.
