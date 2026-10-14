# Contributing to PulseStages

Thank you for helping. This file covers bug reports, feature proposals and pull requests. The build, test and release
details are in [docs/development.md](docs/development.md); the list of all documents is [docs/README.md](docs/README.md).

Issues, pull requests, commit messages, code comments and documentation are written in English. The app's UI ships
in English and Russian; the Russian text exists only as the translation in `res/values-ru/`.

- [Reporting a bug](#reporting-a-bug)
- [Reporting a security problem](#reporting-a-security-problem)
- [Proposing a change](#proposing-a-change)
- [Pull requests](#pull-requests)
- [Commit messages](#commit-messages)
- [Tests](#tests)
- [Strings and translations](#strings-and-translations)
- [Documentation](#documentation)
- [Changing algorithm defaults](#changing-algorithm-defaults)
- [What not to commit](#what-not-to-commit)
- [Licence of contributions](#licence-of-contributions)

## Reporting a bug

Open an issue with the **Bug report** form. It asks for the following; having it ready saves a round trip.

1. **Versions.** The last card of the Settings tab, "Versions and licences", shows a line of the form
   "Version … · algorithm … · model …". Copy it. Say whether you installed the release APK, the debug APK or a build
   of your own.
2. **Device.** Phone model, Android version and, if the phone maker has its own system (for example ColorOS), its
   version. On Android 9–13, also the version of the Health Connect app.
3. **Sources.** Which app wrote the sleep sessions (OHealth or another), and the order on the **Sources** screen.
4. **What happened and what you expected**, with the steps to reproduce it. Quote the exact text of any message the
   app showed.
5. **The log.** For most problems, the app's internal log is the most useful attachment:
   1. To capture the most detail, first set Settings → **Debugging** → "Log level" to **Debug**, then reproduce the
      problem. The default level is Info.
   2. Open the **Log** screen: Settings → the **Log** button in the "Backup and reset" card, or the **Log** entry in
      the **Debugging** group.
   3. Select the levels to include (Debug, Info, Warning, Error), then tap the share icon ("Share log"). The app writes
      every stored entry of the selected levels to `pulsestages-log.txt` and opens the share sheet; save the file or
      send it to an app from which you can attach it to the issue.
   4. **Read the file before you attach it. Issues are public.** Entries contain timestamps, the app's night keys,
      package names of the source apps, Health Connect record ids and error messages. Remove anything you do not want
      to publish.

   The app keeps the newest 10,000 log entries. Developers with adb can also follow the same log live:
   `adb logcat -s PulseStages:V` (see [docs/development.md](docs/development.md#running-on-a-phone)).

**About accuracy.** The stages are estimates from heart rate, not measurements, and a single night that looks wrong
is not necessarily a bug. The validated accuracy for nights and naps is in [ALGORITHM.md §13](ALGORITHM.md#13-results)
and the known limitations in [ALGORITHM.md §16](ALGORITHM.md#16-limitations). Reports that compare many nights with
a reference device (the **Compare** tab does this for apps that write real stages to Health Connect) are welcome as
issues. A night's CSV export (night detail → "Export CSV") contains your per-epoch heart rate; attach it only if you
are comfortable publishing it.

## Reporting a security problem

Do not open a public issue. Follow [SECURITY.md](SECURITY.md).

## Proposing a change

- **Small fixes** (typos, an obvious bug with a clear fix): open a pull request directly.
- **Anything larger** (a new feature, a new setting, a change of behaviour, a new dependency, a change to the
  algorithm or its defaults): open an issue with the **Feature request** form first, so that the approach can be
  agreed before you spend time on it.

Some constraints are deliberate and are not up for change in a pull request without a discussion first:

- The app has **no network access**. The manifest removes the `INTERNET` permission and `MergedManifestTest` fails if
  any library brings it back. Analytics, crash reporting, remote configuration and similar libraries are out of scope.
- PulseStages **never changes or deletes data of other apps**. It writes and deletes only its own sleep sessions.
- **Project dependencies point downwards only** (`app → work → data-hc → data-local → core-algo`, plus
  `algo-cli → core-algo`; a module may also depend directly on any module below it). The allowed edges are the
  `allowedEdges` map in the root `build.gradle.kts`, and `:core-algo` stays pure Kotlin/JVM
  ([docs/development.md](docs/development.md#modules-and-the-dependency-rule)).

## Pull requests

Before you open a pull request:

- [ ] `mise exec -- ./gradlew assembleDebug test ktlintCheck detekt` passes locally.
- [ ] If you changed `tools/train/`: `mise exec -- python -I -m pytest tools/train/tests -q` passes.
- [ ] New behaviour and every fixed bug have tests ([Tests](#tests)).
- [ ] Every new or changed user-visible string exists in English and Russian ([Strings and translations](#strings-and-translations)).
- [ ] Generated files are up to date: the screenshots if a screen shown in the README changed (`-PrecordScreenshots`),
      `docs/settings-reference.md` if a setting, default, range or settings text changed (`-PrecordSettingsReference`),
      the Room schema if the database changed ([docs/development.md](docs/development.md#generated-files-in-the-repository)).
- [ ] The documentation that describes the changed behaviour is updated, in English.
- [ ] A user-visible change has an entry under `## [Unreleased]` in `CHANGELOG.md`.
- [ ] A change to an algorithm default follows [Changing algorithm defaults](#changing-algorithm-defaults).
- [ ] Nothing from [What not to commit](#what-not-to-commit) is in the diff.

Keep a pull request to one topic. CI runs the same checks on every pull request (documentation-only changes skip the
build). The pull request template repeats this checklist.

## Commit messages

Commits follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```text
<type>(<scope>): <summary in the imperative, lower case, no final period>

<body: what changed and why, wrapped at about 72 columns>
```

- **Types** used in this repository: `feat`, `fix`, `test`, `docs`, `ci`, `chore`; `refactor`, `perf` and `build`
  where they fit.
- **Scopes** are the areas of the code: `app`, `work`, `data` (`:data-hc` and `:data-local`), `core-algo`, `train`
  (`tools/train`). Several scopes are separated by commas, as in `feat(work,app): …`. Leave the scope out when a change
  spans the whole project.
- One logical change per commit. The body explains the reason and any consequence a reader would not guess from the
  diff, for example that existing estimates get a new params hash and are offered for recomputation.
- Examples from the history: `feat(data): naps do not affect the elevated-HR baseline`,
  `fix(app): settings crash on percent units; render every screen and settings group in tests`,
  `ci: allow running CI manually`.

## Tests

- Add tests at the layer where the behaviour lives: `:core-algo` (plain JUnit 5), `:data-hc` and `:work` (with the
  fake Health Connect repository and in-memory ledger from the `testFixtures` of `:data-hc`), and `:app` (ViewModel
  tests with fakes; Robolectric render tests for what appears on screen).
- A bug fix comes with a test that fails without the fix.
- Use synthetic data only (`SyntheticNightGenerator` in `:core-algo`, or hand-made inputs). Real health data never goes
  into a test, a fixture or a screenshot.
- The structural tests (module graph, manifest, release APK, string parity, render tests) are described in
  [docs/development.md](docs/development.md#tests). Do not weaken them to make a change pass.

## Strings and translations

- Every user-visible text is a string resource, in `res/values/` (English) and `res/values-ru/` (Russian) of the module
  that uses it. `ResourcesParityTest` fails if one of the two is missing, if the format placeholders differ, or if a
  plural lacks a form (Russian needs `one`, `few`, `many` and `other`).
- A literal percent sign in a string with arguments is `%%`; a string without arguments that contains `%` is marked
  `formatted="false"` (`ResourceFormatsTest`).
- If you cannot write the Russian text, say so in the pull request description.

## Documentation

- English only, plain and precise. Quote UI labels exactly as the English strings show them.
- Every claim must be verifiable in the code or in a cited source. When something has not been verified (for example
  the behaviour of another app), say so.
- [ALGORITHM.md](ALGORITHM.md) is the authoritative source for algorithm numbers; link to its sections instead of
  copying them.
- Links between documents are relative, and anchors must exist.

## Changing algorithm defaults

A change that alters the estimates of existing users, such as a new bundled model, a regenerated `TrainedDefaults.kt`
or a new `AlgoParams` default, is decided on the open datasets by a rule fixed in advance, never on synthetic data and
never after looking at the results:

1. Write a pre-registration (question, data, candidates, metrics, the adoption rule, what is reporting only, what
   was known when it was written) before any candidate is trained, decoded or scored.
2. Commit it under `docs/prereg/` with its SHA-256 file, together with the analysis code it names, **before the first
   run**, and add its checks to `tools/train/tests/test_prereg.py`.
3. Run the evaluation through `algo-cli`, apply the rule as written, and commit the timestamped command log.
4. Report the outcome in [ALGORITHM.md §13](ALGORITHM.md#13-results), whether or not the candidate was adopted, and
   mark every analysis the pre-registration did not name as post hoc and reporting only.
5. Install the model and regenerate the defaults with the training tools, and note in `CHANGELOG.md` that existing
   estimates get a new params hash and are offered for recomputation.

The full procedure, with the existing pre-registrations as examples, is in
[docs/development.md](docs/development.md#pre-registration). Changes that leave every estimate unchanged (a refactoring,
a new optional setting whose default keeps the current behaviour) do not need a pre-registration, but must show that the
estimates are unchanged, for example with the existing regression tests.

## What not to commit

- Real health data of any kind: Health Connect exports, logs or CSV exports of real nights, screenshots taken on a
  phone with real data visible ([docs/SCREENSHOTS.md](docs/SCREENSHOTS.md)).
- Dataset files or anything derived from them. `tools/train/data/` and `tools/train/out/` are git-ignored; only the
  final model bundle and the pre-registrations with their command logs are committed.
- Keystores, their base64 encodings, passwords or other secrets.
- `.tooling/`, `local.properties`, IDE files and build outputs (see `.gitignore`).

## Licence of contributions

PulseStages is licensed under the [WTFPL](LICENSE). By contributing, you agree that your contribution is released under
the same licence. Do not contribute code or data that you are not allowed to release under it.
