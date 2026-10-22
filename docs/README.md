# PulseStages documentation

Every document of the repository, by reader. All documents are in English; the app's UI is available in English and
Russian.

## Using the app

| Document | Contents |
|---|---|
| [Project README](../README.md) | What the app does, screenshots, accuracy summary, quick start, feature list, project status, licence and dataset attribution |
| [User guide](user-guide.md) | The complete manual: installation, permissions, every screen, nights and naps, automatic runs, backfill and recompute, exports, deleting data, Health Connect priority, Sleep as Android, battery settings, troubleshooting, FAQ |
| [Settings reference](settings-reference.md) | Every setting with its key in the JSON export, default, allowed values, effect and whether it enters the params hash. Generated from the code |
| [Privacy](privacy.md) | What the app processes, where it stays, what it stores, logs and exports, and how to delete it |
| [Health Connect](health-connect.md) | The permissions and why each is needed, what is read and written (record fields, stage mapping, notes), the Changes API and why the app polls it, quotas, priority and what other readers such as Sleep as Android see, the sync request to Sleep as Android (on by default), deleting the app's data |
| [Changelog](../CHANGELOG.md) | Changes of every release |

## How it works

| Document | Contents |
|---|---|
| [Algorithm](../ALGORITHM.md) | The estimation algorithm with its formulas and parameters, the validation protocol and every validation number. The authoritative source for accuracy figures |
| [Architecture](architecture.md) | Modules and the dependency rule, data flow from Health Connect to the written session, the ledger, idempotency, background work, error handling, testing strategy |
| [Interchange formats](INTERCHANGE.md) | Night files, `algo-cli` commands, the model bundle and the personal model: the formats shared by Kotlin and the Python training tools |
| [Plan and decisions](../PLAN.md) | The build stages, the design decisions with their reasons, and the verified Health Connect facts and versions |

## Working on the code

| Document | Contents |
|---|---|
| [Development](development.md) | Setup, build, test and lint, module rules, conventions, running on a phone from WSL, CI, releases and signing, training and the pre-registration practice |
| [Contributing](../CONTRIBUTING.md) | Bug reports with logs, proposals, pull requests, commit style, tests, translations, changing algorithm defaults |
| [Security policy](../SECURITY.md) | Supported versions, private vulnerability reporting, scope, the no-network design, signing key policy, verifying downloads |
| [Screenshots and demo mode](SCREENSHOTS.md) | How the README screenshots are rendered from synthetic data, and the demo mode of debug builds |
| [On-device end-to-end suite](../tools/device-test/README.md) | Testing the installed app on a phone with real Health Connect data without polluting it: what is tested, every Health Connect call, the safety guarantees, how to run it and what to do on the phone |

## Training and validation

| Document | Contents |
|---|---|
| [Training tools](../tools/train/README.md) | Setup, dataset download and preparation, every `train.py` command, the validation protocol in detail, outputs and data policy |
| [Datasets](../tools/train/DATASETS.md) | The datasets used and not used, their licences and citations, label codes, steps, prepared nights |
| [Pre-registrations](prereg/) | The pre-registered experiments with their SHA-256 files and timestamped command logs, committed verbatim: `prereg-bias.md` with `commands-train2.log` ([ALGORITHM.md §13.10](../ALGORITHM.md#1310-stage-proportions-prior-exponent-and-proportion-matching-pre-registered-v3-adopted)), `prereg-naps.md` with `commands-naps.log` ([§13.11](../ALGORITHM.md#1311-daytime-naps-nap-proxies-and-a-short-session-bias-pre-registered-adopted)), `prereg-nap-model.md` with `commands-nap-model.log` ([§13.12](../ALGORITHM.md#1312-a-dedicated-nap-model-pre-registered-adopted)), `prereg-naps-realistic.md` with `commands-naps-realistic.log` ([§13.13](../ALGORITHM.md#1313-realistic-naps-pre-registered-not-adopted)), `prereg-merge-gap.md` with `commands-merge-gap.log` ([§13.14](../ALGORITHM.md#1314-the-merge-gap-of-split-nights-pre-registered-not-adopted)); [development.md](development.md#pre-registration) explains the practice |
| [Synthetic results](../core-algo/SYNTHETIC_RESULTS.md) | The synthetic recovery test of the estimators (regression guard only, not a validation) |

## Licence

The code is licensed under the [WTFPL](../LICENSE). The bundled model is a produced work of two PhysioNet datasets
under the ODC Attribution License; the attribution notice is in the [project README](../README.md) and in the app
(Settings → "Versions and licences").
