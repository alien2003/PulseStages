<!-- Links in this template are absolute: a pull request description does not resolve relative links. -->

## Summary

<!-- What does this change, and why? Link the issue it resolves (for example "Closes #12"). Write in English. -->

## Type of change

- [ ] Bug fix
- [ ] New feature or change of behaviour
- [ ] Algorithm, model or default change (changes estimates)
- [ ] Refactoring without a change of behaviour
- [ ] Documentation
- [ ] Build, CI or tooling

## How it was tested

<!-- The tests added or changed, and anything checked by hand (device, Android version). -->

## Checklist

- [ ] The pull request title follows Conventional Commits (`type(scope): summary`, see [CONTRIBUTING.md](https://github.com/alien2003/PulseStages/blob/main/CONTRIBUTING.md#commit-messages)).
- [ ] `mise exec -- ./gradlew assembleDebug test ktlintCheck detekt` passes.
- [ ] `mise exec -- python -I -m pytest tools/train/tests -q` passes, if `tools/train/` changed.
- [ ] New behaviour and every fixed bug have tests; test data is synthetic.
- [ ] New or changed user-visible strings exist in English (`values/`) and Russian (`values-ru/`).
- [ ] Generated files are up to date where affected: screenshots (`-PrecordScreenshots`), `docs/settings-reference.md` (`-PrecordSettingsReference`), Room schema.
- [ ] Documentation is updated (English only; UI labels quoted exactly; numbers linked to ALGORITHM.md).
- [ ] User-visible changes have an entry under `## [Unreleased]` in `CHANGELOG.md`.
- [ ] Algorithm defaults: changed only through a pre-registered evaluation ([CONTRIBUTING.md](https://github.com/alien2003/PulseStages/blob/main/CONTRIBUTING.md#changing-algorithm-defaults)), or not changed.
- [ ] No `INTERNET` permission or other network access is added.
- [ ] No real health data, dataset files, keystores or secrets are included.
