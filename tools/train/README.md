# tools/train — dataset preparation, training and LOSO validation for PulseStages

Offline Python tooling that turns open wearable + sleep-stage datasets into PulseStages night files
(`*.night.json`, the interchange format read by `:algo-cli`), fits the hybrid model bundle and the supervised HMM
defaults, and validates every estimator leave-one-subject-out. Python only prepares data, fits classifiers /
statistics and writes reports; features and decoders are implemented once, in Kotlin (`:core-algo`), and are
always computed by calling `algo-cli` — the numbers in ALGORITHM.md come from the code that runs on the phone.

Datasets, licences, citations and the label-code checks are documented in [DATASETS.md](DATASETS.md).

## Setup (mise, no global installs)

Python 3.12 and the virtual environment `tools/train/.venv` come from the repository's `.mise.toml`
(`_.python.venv = { path = "tools/train/.venv", create = true }`). Run everything from the repository root through
`mise exec`:

```sh
cd /path/to/PulseStages
mise exec -- pip install -r tools/train/requirements.txt     # numpy, scipy, pandas, scikit-learn, threadpoolctl, pytest
mise exec -- python -I -m pytest tools/train/tests -q        # unit + end-to-end tests (synthetic, no algo-cli)
```

## Download

```sh
mise exec -- python -I tools/train/download.py                       # sleep-accel 1.0.0 (Walch), ~2.2 GB
mise exec -- python -I tools/train/download.py --dataset bidsleep    # BIDSleep 1.0.1: hr.csv + labels.mat (~40 MB)
mise exec -- python -I tools/train/download.py --dataset bidsleep --parts all --connections 4   # + motion (6.35 GB zip)
mise exec -- python -I tools/train/download.py --dataset dreamt      # only explains why DREAMT is not used
```

- Data goes to `tools/train/data/raw/<dataset>/<version>/`. Every file is checked against the dataset's
  `SHA256SUMS.txt`; verified files are skipped, so re-running is cheap and resumes interrupted downloads.
- Walch files come from the official PhysioNet AWS open-data mirror (physionet.org itself is throttled to
  ~10-20 kB/s per connection); `--source physionet` uses physionet.org only. Checksums always come from physionet.org.
- BIDSleep has no mirror; files are fetched with resumable `curl -C -`. The accelerometer is only available at a
  practical size through the project zip (byte ranges, resumable, members verified while extracting).
- The script prints the licence and the citations at the end.

## Prepare night files

```sh
mise exec -- python -I tools/train/prepare.py                 # all datasets that are downloaded
mise exec -- python -I tools/train/prepare.py --dataset walch  # or one dataset (other rows of summary.csv are kept)
```

Writes `tools/train/data/nights/<variant>/<stem>.night.json` for four variants that emulate OHealth (one HR value
per 2 minutes):

| variant | HR | session window |
|---|---|---|
| `instantaneous` | raw sample nearest to each point of a 2-min grid, within ±30 s (else no value) | OHealth-like |
| `mean2min` | mean of all raw samples per 2-min window, at the window centre | OHealth-like |
| `instantaneous-psg` | as `instantaneous` | full scored PSG / label range |
| `mean2min-psg` | as `mean2min` | full scored PSG / label range |

- The 2-min grid phase is deterministic per night (SHA-256 of the subject / night key), so results are reproducible
  but the grid is not aligned to PSG start.
- OHealth-like session: first non-wake epoch − 5 min to the end of the last non-wake epoch + 5 min, clipped to the
  scored range. HR and steps are included for `[sessionStart − 30 min, sessionEnd + 30 min]` where data exist.
- Labels: scored 30-s epochs inside the session, mapped to `AWAKE` / `LIGHT` (N1 + N2) / `DEEP` (N3, N4) / `REM`;
  unscored epochs are omitted.
- Steps: conservative wrist-pedometer emulation on the raw accelerometer (`pulsetrain/pedometer.py`: magnitude,
  50 Hz resampling without interpolating across gaps > 1 s, 0.5-3 Hz Butterworth band-pass, peak detection, steps
  only inside regular walking bouts of >= 8 peaks), as 1-minute samples `[startMs, endMs, count]` with count > 0.
- Walch: `subject` = Walch id, files `<id>.night.json`, ids `walch-<id>-inst|mean2min[-psg]`, times in ms since PSG
  start (negative before it). BIDSleep: expert-corrected labels, `subject` = `bid-<NN>` (folder `Bidslab<NN>`, so a
  leave-one-subject-out split keeps all nights of a person together), one file per night `bid-<NN>-<night>.night.json`,
  ids `bid-<NN>-<night>-inst|mean2min[-psg]`, Unix epoch ms.

Also written to `tools/train/data/nights/`:
- `summary.csv` — per night and variant: session minutes, HR samples in the session / file, coverage proxy (fraction
  of 1-min session epochs within 10 min of an HR sample), stage minutes, steps in the session / file, notes
  (missing motion, HR gaps, unscored epochs, absent stages, `SKIPPED: ...` rows for unusable nights);
- `steps_comparison_walch.csv` — accelerometer steps vs. the Apple Watch's own step bins (see DATASETS.md);
- `manifest.json` — dataset versions, licences, citations, counts and all preparation parameters.

## Training and leave-one-subject-out validation

### Run (PART B)

```sh
cd /path/to/PulseStages
mise exec -- ./gradlew :algo-cli:installDist                      # builds the Kotlin launcher (never during a run)
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py run --run loso-v2 --selection per-group --jobs 24
# read tools/train/out/loso-v2/report.md, then put the final model into the app and the defaults into core-algo:
mise exec -- python -I tools/train/train.py final --run loso-v2 --install-model
mise exec -- python -I tools/train/train.py defaults --run loso-v2 --write   # core-algo params/TrainedDefaults.kt
mise exec -- python -I tools/train/train.py compare --runs loso-v2,exp-v2-e2-mlp-alpha   # paired CIs between runs
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py sensitivity --run loso-v2   # session boundaries / steps
```

`JAVA_OPTS=-Xmx2g` only caps the heap of each algo-cli JVM (24 of them run in parallel); it does not change results.
The shipped model and defaults come from the steps listed with their exact commands in ALGORITHM.md, section 17
(run `loso-v2`, the two pre-registered experiments re-applied to its selection, the adopted setting with the
complete protocol and `--install-model`, then `defaults --write`). The earlier runs `loso-v1*` used the retired
`walch+bid-nonstep` configuration (see Protocol, item 5) and are superseded.

Provenance: every run records a fingerprint of the algo-cli build it used (SHA-256 over the launcher and
`lib/*.jar`) in `config.json` and `summary.json`, and fails if the build changes during the run (re-checked after
every phase). Rebuild algo-cli only between runs and log the rebuild in `tools/train/out/commands.log`.

`defaults` renders `out/<run>/trained_defaults.json` into the generated Kotlin object `TrainedDefaults`
(`pulsetrain/kotlin_defaults.py`): `AlgoParams` takes its estimator, hybrid decoder (`durationModel`,
`emissionWeight`, `timePriorWeight`) and HMM (`emissions`, `meanBouts`, `transitions`) defaults from it. The HMM
fields hold the supervised estimates only when HMM-trained beat the physiology table by the selection rule;
otherwise they point to `EmissionTable.PHYSIOLOGY` / `MeanBouts()` / `TransitionWeights()`, and `HmmParams.PHYSIOLOGY`
always gives the specification's table.

`run ... --install-model` does both in one go. `train.py report --run <name>` re-renders `report.md` /
`summary.json` from the stored per-night results. The launcher is looked up as `$ALGO_CLI`, else
`algo-cli/build/install/algo-cli/bin/algo-cli`, else `algo-cli/build/install/pulsestages-algo/bin/pulsestages-algo`
(the Gradle `applicationName`); `--algo-cli <path>` overrides.

**Runtime** (measured in PART B on an Intel Core Ultra 9 275HX, 8 performance + 16 efficiency cores, `--jobs 24`):
the default `--selection per-subject` (1504 inner-CV runs) is dominated by the pooled Walch + BIDSleep
configurations, whose inner runs fit 5–15 classifiers on ≈ 180 k rows each (unloaded probe: LR ≈ 50 s, MLP ≈ 95 s per
run; ≈ 3× slower with all cores busy). The run was stopped after 6 minutes; the logged projection
(`tools/train/out/commands.log`, 05:37:49) is "> 2.5 h under full load" for the nested selection alone (the probe
outputs were not kept, so no more precise figure is claimed).
`--selection per-group` (128 inner-CV runs, still leak-free: every outer group's selection excludes that group's
subjects) took 32 min for the full run `loso-v2` (features 4 s, baselines 2 s, HMM-trained 37 s including the
time-prior fits, nested selection 9 min, 1504 outer fold models and their evaluation 21 min, final model 25 s). A
single configuration and classifier (`--configs walch+bid-zero --classifiers mlp`) takes 5–6 min for two variants
with the Walch subjects held out (`--heldout-datasets walch`), `--configs walch --classifiers lr` about a minute;
`sensitivity` ≈ 2 min. The run log shows per-phase
timings; `summary.json` records them.

### Protocol

1. `algo-cli features` once per night variant → `features/<variant>.csv` (hybrid feature columns read from the CSV
   header; `featureSetVersion` from the `<csv>.meta.json` sidecar, a `# featureSetVersion=N` first line, or
   `--feature-set-version`).
2. Estimators: **RULES** (AlgoParams defaults) and **HMM-table** (the specification's table, mean bouts,
   transition shares and time prior passed explicitly as `baselines/hmm-table.params.json`, because the AlgoParams
   HMM defaults may be trained estimates; no training); **HMM-trained** (supervised MLE per fold: Gaussian emissions
   of hrNorm / hrVar / hrSlope on epochs with HR data, mean bout lengths, bout-transition shares, and the time prior
   — LIGHT→DEEP / LIGHT→REM time multipliers and the REM lockout factor by conditional MLE of the epoch transitions
   under the core-algo transition model, lockout length fixed at 45 min, initial AWAKE / LIGHT from the first
   labelled stage; `folds/<fold>.hmm-params.json`, `stats.hmm_time_prior`); **HYBRID-LR** (multinomial logistic
   regression, lbfgs, C from {0.01, 0.1, 1} by inner log-loss) and **HYBRID-MLP** (32×32 relu, adam, early stopping,
   fixed seed). The cycle-phase prior and the step factors of the HMM are not estimated (specification values).
3. Outer loop = leave one subject out of the pooled training set of a configuration. Everything is fitted on the
   other subjects only: standardization, classifier, gamma bout durations (minutes), bout transitions, initial bout
   distribution, time prior P(stage | 30-min bin), class priors, HMM MLE. Subjects of datasets outside the pool are
   scored once with the model of the whole pool (`external`, e.g. BIDSleep with the Walch-only model).
4. Nested selection, never touching the held-out subject: deterministic, dataset-stratified grouped 5-fold CV over the
   training subjects; one `algo-cli evaluate --fold-models <inner models> --param-grid` call scores the decoder grid
   `hybrid.emissionWeight ∈ {1, 0.7, 0.5, 0.3}` × `timePriorWeight ∈ {1, 0.5, 2, 0}` × `durationModel ∈ {EXPLICIT,
   GEOMETRIC}` (MLP: the 8-point `small` grid) on the Walch training subjects (`--select-datasets`); best mean
   per-subject kappa wins, ties → grid order (the first point is the AlgoParams default). Outer evaluation: one
   `evaluate --fold-models folds/ --params <chosen>` per group of held-out subjects sharing the chosen params.
5. Training configurations (`--configs`): `walch` (Walch only), `walch+bid-mask` (+ BIDSleep, step features masked
   for every night: standardization mean 0 / std 1e12, zero column in training, zero first-layer weights; listed in
   `training.notes`), `walch+bid-zero` (+ BIDSleep, whose nights have no step channel: their step features stay
   exactly what algo-cli computes for them, 0 steps — which is also what the app computes for a night without step
   records). Which one wins is part of the report (`bidSteps`). Invariant (checked in every fit,
   `loso.check_train_inference_parity`, and by `tests/test_loso.py`): the classifier is trained on exactly the
   feature values algo-cli / the app compute for the same epochs; only masked columns (never used) are exempt.
   The retired `walch+bid-nonstep` broke it — BIDSleep step features were "missing" (z = 0) in training but 0 steps
   (z = −0.04 / −0.11) at inference, which the MLP learned as a Walch-vs-BIDSleep switch — and is no longer
   accepted.
6. Variants: all four; training rows = evaluated variant + its sampling twin (`--train-variants pair`, i.e.
   instantaneous ∪ mean2min of the same session window; `same` = evaluated variant only). The final model is trained
   the same way on `--final-variants` (default instantaneous + mean2min).
7. Metrics (recomputed from the per-night confusion matrices of algo-cli): Cohen's kappa pooled and per subject
   (mean ± sd, median), accuracy, per-stage precision / recall / F1, macro-F1, confusion matrix (counts, row-normalized),
   mean absolute minutes error per night for DEEP / REM / AWAKE; split by the held-out subject's dataset (Walch = PSG
   headline, BIDSleep = EEG headband, secondary).
8. Selection rule (`report.py`): score = mean per-subject kappa on Walch, worse of the OHealth-like variants
   (instantaneous, mean2min). It picks the training configuration per classifier, LR vs MLP, HMM-trained vs table,
   BIDSleep mask vs zero and the default estimator. The trained HMM defaults are used only if they score higher
   **and** pass the validity gate (`report.hmm_validity`): the estimates on all pool subjects satisfy the
   specification's order μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE on hrNorm (otherwise per-night EM and the HMM
   personalization roll back on practically every night), and the pooled predicted DEEP share is within
   [3 %, 35 %] and REM ≤ 40 % (the post-processing sanity corridors) on both headline variants.
   Final decoder params = the most frequent inner-CV choice over the outer folds of the chosen configuration (the
   inner selection is not re-run on all subjects); LR's C likewise. The report also counts the choice per distinct
   inner selection (in `per-group` mode the folds of an outer group share one selection) with the inner-CV margin
   of each winner over the runner-up, and gives paired per-subject bootstrap CIs (10 000 resamples of subjects)
   for every comparison behind the selection.
9. Final model on all subjects → `out/<run>/final/hybrid_model.json` (+ app assets with `--install-model`) and
   `out/<run>/trained_defaults.json` (AlgoParams patch: estimator, hybrid decoder params, HMM emissions / meanBouts /
   transitions / time prior if the trained HMM was adopted). A resubstitution `evaluate --model` checks that
   algo-cli accepts the bundle (its numbers are not a validation result).
10. Post-hoc analyses (reporting only, `pulsetrain/robustness.py`): `train.py compare --runs ref,a,b` (default rows
   of several runs, mean ± sd over runs, paired CIs against the first run, e.g. experiments or seeds);
   `train.py sensitivity --run <run>` (the run's outer fold models, each with its nested-CV decoder params, on Walch
   nights whose session window is moved — narrower / wider by 10–20 min, late start, early end, ±15 min jitter —
   rebuilt from the `-psg` night files, and on nights without steps; it first re-evaluates the original nights and
   fails unless they reproduce the run's numbers, and checks that the unshifted rebuild reproduces them too).

Bundle statistics conventions (also in `pulsetrain/stats.py`): bout durations are fitted on uncensored bouts (first /
last bout of a labelled segment are censored), unsplit; a bout longer than `maxBoutMin` (120) counts as
ceil(L / 120) consecutive pieces, i.e. ceil(L / 120) − 1 self-transitions in `boutTransitions` (the only diagonal
mass); off-diagonal pseudo-count 0.5; time-prior bins after the last bin with ≥ 300 labelled epochs are merged into
it, Laplace α = 1; class priors = class frequencies of the classifier's training rows (with `--class-weight
balanced` the final bias is shifted back to these frequencies so the bundle semantics hold).

Determinism: fixed seeds (`--seed`), single-threaded BLAS in every fit, results independent of `--jobs`; bundles
carry `--created-at` (default: now).

### Output (`tools/train/out/<run>/`, never committed)

```
config.json run.log commands.log            run configuration + algo-cli fingerprint, log, every algo-cli command
features/<variant>.csv                       algo-cli features
baselines/<variant>/{RULES,HMM-table}.metrics.json
hmm-trained/<pool>/<variant>/folds/<fold>.hmm-params.json   (+ folds-meta/, metrics/)
hmm-trained/<pool>/final.hmm-params.json     HMM MLE on all subjects of the pool (validity gate, defaults)
hybrid/<config>/<variant>/<lr|mlp>/folds/<subject>.json      fold bundles (+ <subject>.hmm-params.json)
hybrid/<config>/<variant>/<lr|mlp>/folds-meta/<fold>.meta.json   train / test subjects, chosen C and params
hybrid/<config>/<variant>/<lr|mlp>/selection/<key>.json      inner CV: C log-loss, kappa of every grid point
hybrid/<config>/<variant>/<lr|mlp>/metrics/<group>.json      outer evaluation
results.jsonl fold_selections.jsonl summary.json report.md   per-night results, per-fold choices, aggregates
final/hybrid_model.json trained_defaults.json
sensitivity/{summary.json,report.md}         train.py sensitivity
```

### algo-cli interface (verified against the real launcher in PART B)

Checked on real night files with the installDist launcher; `tests/fake_algo_cli.py` writes the same formats.

- `feature-names` prints `{"algoVersion", "featureSetVersion", "featureNames"}`. `algo-cli features` writes no
  version sidecar, so the pipeline takes `featureSetVersion` from here and fails if `featureNames` differ from the
  CSV header (`--feature-set-version` overrides it, for tests only).
- `features --nights <dir|jsonl> --out <csv> [--params <json>]`: header `night_id,subject,epoch,start_ms,has_data,
  label,<FEATURE_NAMES…>,cyclePhase,steps`. The base features hrNorm / hrVar / hrSlope / t are hybrid columns
  (identical values) and are not repeated: the trailing block is exactly the base features
  (`hrNorm, hrVar, hrSlope, t, cyclePhase, steps`) that are not hybrid features (`features.split_header`).
  `has_data` 1/0, `label` a stage name or empty, NaN = empty field. One row per session epoch (60 s).
- `--nights` accepts a directory and a `.jsonl` file (subsets are passed as JSONL); `--params` accepts a partial
  AlgoParams JSON (missing fields = defaults, unknown keys and out-of-range values = exit 65).
- `evaluate --nights … --estimator RULES|HMM|HYBRID [--params] [--model | --fold-models <dir>] [--param-grid <json>]
  --out <json> [--per-epoch <csv>]`; `--fold-models` resolves `<dir>/<subject>.json` (the directory also holds
  `<subject>.hmm-params.json`, which algo-cli ignores); a night whose subject has no fold model is skipped as
  `NO_FOLD_MODEL`, which the pipeline treats as an error. HMM-trained is evaluated per fold with `--params`
  `folds/<fold>.hmm-params.json` (one call per held-out subject), so no per-subject parameter option is needed.
- `--param-grid` input: a JSON array of partial AlgoParams patches, each deep-merged over the base parameters;
  output `{"grid": [block, …]}`, one metrics block per patch with `index` and `patch` added. Without the flag
  (exit code 64) the grid is evaluated point by point (`--grid-mode loop` forces that).
- Metrics block: `nights: {total, evaluated, skipped, skipReasons}`, `skipped: [{id, subject, reason}]`,
  `perNight: [{id, subject, variant, estimator (the one actually used), kappa, …, confusion: 4×4 counts (rows =
  truth AWAKE, LIGHT, DEEP, REM; columns = prediction; 30-s label chunks), minutesError: {deep, rem, awake},
  warnings: [codes]}]`, `pooled.confusion` (= the sum of the nights, checked), `pooled.skippedChunks` (label
  chunks whose prediction is UNKNOWN, i.e. inside long HR gaps with the default gap policy; not scored). The
  per-night `confusion` was added to algo-cli in PART B (it is what lets the pipeline pool many evaluate calls).
  A HYBRID night with `HYBRID_MODEL_UNAVAILABLE` or estimator `HMM` aborts the run (the bundle was rejected).
- Sanity checks on the real data (smoke run): for every metrics file, `Σ confusion + skippedChunks` = the number of
  30-s label chunks of the evaluated nights; no `HYBRID_MODEL_UNAVAILABLE`.

## Data policy

Raw downloads (`data/raw/`) and everything derived from them (`data/nights/`, `out/`, models in progress) stay local
and are never committed — only the scripts, `requirements.txt`, the tests and the links / citations in DATASETS.md
are (the final model is committed as an app asset). Downloaded files are treated as untrusted input: nothing from
them is executed, and every script that reads them is run with `python -I` (isolated mode: no current directory or
user site-packages on `sys.path`).

## Layout

```
download.py            dataset download + checksum verification
prepare.py             night-file generation (all variants), summary.csv, manifest.json
train.py               training / LOSO CLI (run | report | final | defaults | compare | sensitivity)
pulsetrain/ohealth.py  2-min HR resampling, session windows, label mapping, coverage proxy
pulsetrain/pedometer.py  accelerometer step detector
pulsetrain/walch.py    sleep-accel readers;  pulsetrain/bidsleep.py  BIDSleep readers
pulsetrain/algocli.py  algo-cli wrapper (features / evaluate / --param-grid, command log, errors)
pulsetrain/features.py features CSV reader;  pulsetrain/nightset.py  night index, datasets, JSONL subsets
pulsetrain/stats.py    bouts, gamma durations, bout transitions, time prior, class priors, HMM MLE
pulsetrain/models.py   standardization, masking, LR / MLP, export to bundle layers (verified to 1e-9)
pulsetrain/bundle.py   hybrid model bundle build / validation (INTERCHANGE.md section 3)
pulsetrain/loso.py     folds, nested selection, LOSO orchestration;  pulsetrain/pipeline.py  end-to-end run
pulsetrain/report.py   metrics, selection rule + HMM validity gate, paired bootstrap, report.md
pulsetrain/train_final.py  final model + trained_defaults.json;  pulsetrain/kotlin_defaults.py  TrainedDefaults.kt
pulsetrain/robustness.py   post-hoc: cross-run comparisons, session-boundary sensitivity, no-steps evaluation
tests/                 pytest (synthetic data only; tests/fake_algo_cli.py stands in for algo-cli)
```
