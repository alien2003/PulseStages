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

Stage-proportion experiment (pre-registered: `docs/prereg/prereg-bias.md`, a verbatim copy of
`tools/train/out/prereg-bias.md` with SHA-256 in `docs/prereg/prereg-bias.md.sha256` and the worktree's timestamped
log in `docs/prereg/commands-train2.log`, checked by `tests/test_prereg.py`; ALGORITHM.md section 13.10) and its
final model, on a run made with `--keep-inner` (the inner-CV fold models are needed):

```sh
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py run --run bias-base --configs walch+bid-zero \
    --classifiers mlp --estimators HYBRID --variants instantaneous,mean2min --selection per-group --keep-inner --jobs 24
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py bias --run bias-base --jobs 24     # bias/report.md
mise exec -- python -I tools/train/train.py bias-report --run bias-base      # re-render it from results.jsonl
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py bias-final --run bias-base --out-run bias-final \
    --selection-from tools/train/out/loso-v2/summary.json --install-model
mise exec -- python -I tools/train/train.py defaults --run bias-final --write
```

`bias` (`pulsetrain/bias.py`) keeps the run's classifier, fold bundles and per-group decoder choice and changes only
the decoder's prior exponent β (`hybrid.priorExponent`, inner-CV grid {1, 0.75, 0.5, 0.25, 0}) and the bundle's
per-stage log-bias b (`stageLogBias`, fitted per outer group by a deterministic fixed point on the inner out-of-fold
decodes of the Walch selection subjects so that the decoded stage shares match the PSG shares): candidates BASE, (a)
β, (b) b, (c) both, the pre-registered adoption rule (lowest worse-variant summed per-night minute error among
candidates with κ ≥ BASE − 0.01), paired CIs, shares, sanity flags and BIDSleep secondary numbers. It fails unless
BASE reproduces the run night by night and the inner β = 1 kappas equal the run's selection scores. Its
`summary.json` / `report.md` also carry reporting-only numbers outside the rule: paired differences between the
corrections (`pairwise`, e.g. B − A), sanity flags per warning code, outer folds per choice with fallbacks told apart
(`choiceCounts`), the E split of nights whose group fell back to BASE (`fallbacks`) and the inner selections where a
candidate's inner κ is more than 0.01 below BASE's (`innerKappaBelowBase`); `bias-report` recomputes both files from
the stored `results.jsonl` / `decisions.json` without algo-cli. `bias-final` fits b for the final bundle on the LOSO
out-of-fold decodes of all Walch subjects at the final decoder parameters and writes
`out/<out-run>/{final/hybrid_model.json, trained_defaults.json, summary.json}` for `defaults`; with
`--selection-from` the other run's decoder parameters and chosen classifier / configuration must equal this run's
(the rest of its `selection.classifier`, e.g. scores of configurations this run did not train, may differ).

`bias` and `bias-final` decode with the algo-cli build recorded in the run's `config.json` and refuse any other: the
shipped experiment (`bias-base`) was made with build `0fe61172…`, the tree's later build is `033541ae…`. To re-run on
a newer build pass `--allow-other-build` (logged, recorded as `runAlgoCliSha256` in `summary.json`; BASE must still
reproduce the run night by night) and `--out-name` to keep the original results; `bias --run bias-base --out-name
bias-033541ae --allow-other-build` reproduced `bias/results.jsonl` and `decisions.json` byte for byte. Run outputs
are never committed: when a worktree is removed, move its `out/bias-base`, `out/bias-final`, `out/prereg-bias.md` and
`out/commands.log` next to the other runs of the main checkout's `tools/train/out`, since `defaults --run bias-final`
needs `bias-final` (likewise `out/naps`, `out/naps-final`, `out/naps-verify`, `out/naps-verify-merge`, `out/naps-rerun`,
`out/naps-posthoc` and `out/prereg-naps.md` of the nap experiment, and `out/nap-model`, `out/nap-model-final`,
`out/nap-model-verify` and `out/prereg-nap-model.md` of the nap model: `defaults --run nap-model-final` generates the
current `TrainedDefaults.kt`).

Nap experiment (pre-registered: `docs/prereg/prereg-naps.md`, a verbatim copy of `tools/train/out/prereg-naps.md`
with SHA-256 in `docs/prereg/prereg-naps.md.sha256` and the timestamped log `out/naps/commands.log` as
`docs/prereg/commands-naps.log`, checked by `tests/test_prereg.py`; ALGORITHM.md section 13.11), post hoc on the fold
artefacts of `bias-base` and `bias-base/bias` (read only, no retraining):

```sh
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps --run bias-base --out-run naps --context-run loso-v2
mise exec -- python -I tools/train/train.py naps-report --out-run naps        # re-render it from results.jsonl
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-final --exp naps --base-run bias-final \
    --out-run naps-final --install-model                                       # C adopted: bundle + stageLogBiasShort
mise exec -- python -I tools/train/train.py defaults --run naps-final --write  # + NAP_MAX_SESSION_MIN, NAP_*
mise exec -- ./gradlew :algo-cli:installDist                                   # the build implementing it
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-verify --exp naps --final naps-final --out-run naps-verify
# the same check on the build merged with post.proportionsMinSessionMin (no sanity check below 180 min):
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-verify --exp naps --final naps-final \
    --out-run naps-verify-merge
# and on the build with the two processing modes (one nap threshold napMode.maxSessionMin, ALGORITHM.md section 18):
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-verify --exp naps --final naps-final \
    --out-run naps-verify-modes
# audit follow-up, post hoc and reporting only (no decision depends on it):
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps --run bias-base --out-run naps-rerun --context-run loso-v2
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-posthoc --exp naps --final naps-final \
    --rerun naps-rerun --out-run naps-posthoc
```

`naps` (`pulsetrain/naps.py`) cuts nap proxies from the OHealth-like night files (the first L in {30, 60, 90, 120, 180}
min of every session, heart rate / steps of the 30 min before, nothing after the end, the labels inside), re-evaluates
the whole nights first (A = fold bundle + the fold-level b of candidate (b), BASE = no b, RULES, HMM-table) and fails
unless A / BASE reproduce `bias-base/bias/results.jsonl` night by night; then it fits the short-session bias b_short per
(variant, outer group, T in {120, 180}) with the fixed point of `bias.py` on the inner out-of-fold proxies (L < T) of
the group's Walch selection subjects, decodes every proxy of every held-out subject per decoding kind (A, BASE, C-120,
C-180, RULES, HMM-table) and assembles the candidates A, B-T (b off below T) and C-T (b_short below T); metrics per
session (accuracy, summed share error in percentage points, kappa reported only, NOT_ESTIMATED reasons, low-confidence /
sanity flags), per length and pooled over 30-120 min, paired bootstrap CIs vs A, the pre-registered rule (Walch, worse
variant: lowest error among candidates with accuracy >= A - 0.01). `naps-final` fits the final b_short on the LOSO
out-of-fold proxies of all Walch subjects and writes `out/naps-final/{final/hybrid_model.json, trained_defaults.json,
summary.json}` (the threshold as `napMode.maxSessionMin`); `naps-verify` checks that a build implementing the nap
threshold (`napMode.maxSessionMin`, before the two modes `hybrid.shortSessionMaxMin`) / `stageLogBiasShort` reproduces A
on every night and the adopted candidate on every proxy with its *default* parameters. The experiment's build checked
the stage shares of every session; a build that runs the sanity check only on sessions of at least a threshold (read
from the parameters its metrics report: `napMode.maxSessionMin` unless `napMode.post.checkProportions`, or
`post.proportionsMinSessionMin` of a build before the modes) must give the stored warnings without the `SANITY_*` codes
of shorter sessions, and the summary counts those sessions (`sanityCodesDropped`) and gives the build's low-confidence
rate per dataset, variant and length (`lowConfidenceRate`). `naps-posthoc` (`pulsetrain/naps_posthoc.py`; reporting
only, not pre-registered) decodes proxies of 150 and 170 min with A and the adopted C (and the 120-min proxies again,
which must equal the stored ones, with the same `SANITY_*` rule; on such a build its flag tables have no sanity flags
below 180 min), compares C-180 with C-120, scores RULES / HMM-table / "always LIGHT" by the rule as if they had been
candidates, tabulates flags, stage shares of both variants, the REM excess and both kappa definitions (mean per subject
vs chunk-pooled), checks the final b_short fit against the fold fits and, with `--rerun`, compares a re-run of `naps`
with the stored run file by file (`--no-decode` skips the algo-cli part).

Dedicated nap model (pre-registered: `docs/prereg/prereg-nap-model.md`, a verbatim copy of
`tools/train/out/prereg-nap-model.md` with SHA-256 in `docs/prereg/prereg-nap-model.md.sha256` and the timestamped
main log `out/commands.log` as `docs/prereg/commands-nap-model.log`, checked by `tests/test_prereg.py`; committed with
its analysis code before the first run; ALGORITHM.md section 13.12):

```sh
mise exec -- ./gradlew :algo-cli:installDist                                   # the build of that commit
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py nap-model --out-run nap-model --jobs 16
mise exec -- python -I tools/train/train.py nap-model-report --out-run nap-model   # re-render it (no algo-cli)
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py nap-model-final --exp nap-model --out-run nap-model-final \
    --install-model                                                            # N1 adopted: the bundle's nap section
mise exec -- python -I tools/train/train.py defaults --run nap-model-final --write  # NAP_HYBRID_*, NAP_OUTPUT_POLICY
mise exec -- ./gradlew :algo-cli:installDist
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py nap-model-verify --exp nap-model --final nap-model-final \
    --out-run nap-model-verify                                                 # nights unchanged with the new build
```

`nap-model` (`pulsetrain/napmodel.py`) reads `bias-base` (outer fold night bundles, groups, the selection sets and
inner folds of its per-group selection), `bias-base/bias` (b_g), `naps` (b_short_g(180), the stored C-180 proxies) and
`naps-posthoc` (150 / 170-min proxies), all read only. It cuts nap proxies v2 from every night of both headline variants:
onset proxies of 30, 60, 90, 120, 150 and 170 min (`naps.nap_proxy`) and ends-in-wake windows (from the end of a wake
bout of >= 2 min to the start of the next wake bout of >= 5 min, with up to 5 min of the opening bout before and the first
5 min of the closing awakening after it, 30-179 min, up to 30 min of context before and nothing after the end; at most 3
non-overlapping windows per night in a SHA-256 order shared by the two variants). Before any fit it checks that the
current default at fold level (N0: night fold bundle + b_g + b_short_g(180)) reproduces `bias-base/bias` candidate B on
every night and `naps` / `naps-posthoc` C-180 on every onset proxy, that the Python cut gives exactly the features of
`algo-cli features --nap-proxies`, and that the session files the features come from hold byte for byte the proxies
that are decoded (`check_sessions_file`: the training rows are the algo-cli features as they are, so this is the
train / inference parity check of the nap pipeline). Then, per (variant, outer group), the nested inner CV fits logistic regression (C by
inner log-loss) and an MLP 2 x 32 on the inner training subjects' proxies (rows of both variants, instantaneous first;
statistics from the evaluated variant), scores the 32-point decoder grid as `napMode.hybrid` patches (mean per-subject
kappa of the inner held-out Walch subjects) and fits the nap stageLogBias with `bias.fit_stage_bias` on the inner
out-of-fold decodes; the outer LOSO nap model of every subject goes into the `nap` section of its N0 fold bundle (N1; N2
without the nap b), decoded together with the subject's whole nights, which must stay identical to candidate B. RULES,
HMM-table, "all LIGHT" and a sleep / wake scoring of every candidate are reported; rule (i) (model) and rule (ii) (output
policy) are applied on Walch, worse variant, both kinds pooled, with paired per-subject bootstrap CIs.
`nap-model-final` fits the final nap model (the most frequent classifier and decoder over the outer folds) on all
subjects and its nap b on the LOSO out-of-fold proxies of the Walch subjects, adds it as the `nap` section of
`naps-final`'s bundle and writes `trained_defaults.json` with `napMode.hybrid` and `napMode.outputPolicy` (with N0 kept:
the output policy only). `nap-model-verify` checks with the new build that the adopted candidate's fold bundles
reproduce candidate B on every night (default parameters except the night decoder of each group) and its proxy decodes,
that the installed bundle decodes every night exactly as the previous one (metrics and per-epoch CSV) and that it is
accepted on every proxy.

Provenance: every nap run records `meta.code` in its `summary.json` (SHA-256 of `train.py` and each `pulsetrain/*.py`,
one digest over them, the git commit and the hashed files that differ from it) next to the algo-cli fingerprint and
the pre-registration digest. Commit a pre-registration, with the analysis code it names, before the first run: the
nap pre-registration was committed together with its results, so its timing rests on file times and the run's log
(ALGORITHM.md section 13.11).

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
`emissionWeight`, `timePriorWeight`, `priorExponent` — 1 unless the run chose it), nap mode (`napMode.maxSessionMin` —
180 unless the run chose it, a legacy `hybrid.shortSessionMaxMin` of an earlier run read as it; `napMode.estimator` —
null, i.e. the night's estimator, unless chosen; `napMode.outputPolicy` — STAGES unless chosen; the nap decoder overrides
`napMode.hybrid.*` — null = the night's values unless chosen, a chosen weight is written as a `const val`) and HMM (`emissions`, `meanBouts`, `transitions`) defaults from it. The HMM
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
bias/{summary.json,report.md,decisions.json,results.jsonl}   train.py bias (stage-proportion experiment; bias-report)
```

`train.py naps` writes `out/<out-run>/` (default `naps`): `repro/` (night-level reproduction), `fits.json`,
`results.jsonl` (per decoded session and kind), `summary.json`, `report.md`, `algocli.log`, `run.log`; its
`commands.log` holds the pre-registration and the START / RESULT lines. `train.py naps-posthoc` writes
`out/<out-run>/` (default `naps-posthoc`): `summary.json`, `report.md`, `results.jsonl` (the new decodes),
`algocli.log`. `train.py nap-model` writes `out/<out-run>/` (default `nap-model`): `proxies/` (the proxy sessions per
variant, `counts.json`), `features/`, `gate.json`, `inner/<variant>/<group>/selection.json` (every grid point of both
classifiers, C log-losses, the nap-bias fixed point), `selections.json`, `outer/<variant>/nap/<subject>.json` (the LOSO
nap models, no b), `folds.json`, `results.jsonl` (every decoded session of N0, N1, N2, RULES and HMM-table, the N0 / N1
/ N2 nights included), `summary.json`, `report.md`, `algocli.log`, `run.log`; `nap-model-final` writes
`out/<out-run>/{final/hybrid_model.json, final/result.json, trained_defaults.json, summary.json}`; `nap-model-verify`
writes `out/<out-run>/summary.json`.

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
  `--estimator` sets the estimator of nights and of naps (`estimator`, `napMode.estimator`).
- Night / nap modes (ALGORITHM.md section 18): `features` and `evaluate` take `--nap-proxies <minutes,…>` (for every
  night and length L also its nap proxy `<id>-nap<L>`, cut like `pulsetrain.naps.nap_proxy`); every `perNight` /
  `skipped` entry has `mode` (NIGHT / NAP by the block's `napMode.maxSessionMin`) and every metrics block has `byMode`
  (`NIGHT`, `NAP`: the same keys for the sessions of each mode). A bundle may carry a nap model, the `nap` section
  (`bundle.nap_section(model)` of a bundle trained on nap-length sessions, `bundle.with_nap_model(bundle, nap)`);
  it decodes the naps instead of the main model, and with `--fold-models` each fold bundle carries its own fold's
  nap section, so a nap model is evaluated out-of-fold like the main one. A bundle without the section decodes naps
  with the main model and `stageLogBiasShort`, exactly as before the modes.
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
are (the final model is committed as an app asset; the stage-proportion, nap and nap-model pre-registrations and their
command logs, which hold no data but aggregate numbers, are committed under `docs/prereg/`). Downloaded files are treated as untrusted input: nothing from
them is executed, and every script that reads them is run with `python -I` (isolated mode: no current directory or
user site-packages on `sys.path`).

## Layout

```
download.py            dataset download + checksum verification
prepare.py             night-file generation (all variants), summary.csv, manifest.json
train.py               training / LOSO CLI (run | report | final | defaults | compare | sensitivity | bias | bias-report |
                       bias-final | naps | naps-report | naps-final | naps-verify | naps-posthoc | nap-model |
                       nap-model-report | nap-model-final | nap-model-verify)
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
pulsetrain/bias.py     pre-registered stage-proportion experiment (prior exponent, proportion matching) + its final model
pulsetrain/naps.py     pre-registered nap-proxy experiment (short-session bias) + its final model and implementation check
pulsetrain/naps_posthoc.py  post-hoc analyses of the nap experiment (reporting only)
pulsetrain/napmodel.py pre-registered dedicated nap model (nap proxies v2, LOSO, rules) + its nap section and checks
tests/                 pytest (synthetic data only; tests/fake_algo_cli.py stands in for algo-cli)
```
