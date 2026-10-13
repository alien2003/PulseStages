# Datasets

PulseStages trains and validates its estimators only on **open-access** data with a wearable heart-rate signal and
reference sleep stages. Raw data and derived night files are **never committed**; this repository contains only the
scripts (`download.py`, `prepare.py`) and the links below. Licences and access were checked on 2026-10-13 on the
PhysioNet project pages.

| Dataset | Version | Access | Licence | Used |
|---|---|---|---|---|
| PhysioNet sleep-accel (Walch et al.) | 1.0.0 (only version) | Open Access | ODC-By 1.0 | **yes, primary** (31 subjects) |
| PhysioNet BIDSleep (Song et al.) | 1.0.1 (latest) | Open Access | ODC-By 1.0 | yes, second dataset (47 subjects / 253 nights) |
| PhysioNet DREAMT | 2.2.0 (latest) | **Restricted Access** (DUA) | PhysioNet Restricted Health Data License 1.5.0 | **no** |

Both used datasets are under the Open Data Commons Attribution License v1.0: use, sharing and derived databases are
allowed with attribution (section 4 of the licence). Anything derived from them (night files, model weights, reports)
must cite them as below.

## 1. Motion and heart rate from a wrist-worn wearable and labeled sleep from polysomnography (sleep-accel)

- Page: <https://physionet.org/content/sleep-accel/1.0.0/> (DOI v1.0.0 [10.13026/hmhs-py35](https://doi.org/10.13026/hmhs-py35),
  latest-version DOI [10.13026/50yh-tt09](https://doi.org/10.13026/50yh-tt09))
- Access policy (page): "Anyone can access the files, as long as they conform to the terms of the specified license."
- Licence: Open Data Commons Attribution License v1.0 — <https://physionet.org/content/sleep-accel/view-license/1.0.0/>
  (full text in the download, `LICENSE.txt`).
- Files: `https://physionet.org/files/sleep-accel/1.0.0/` (wget), zip `https://physionet.org/content/sleep-accel/get-zip/1.0.0/`
  (550 MB), AWS open data mirror `s3://physionet-open/sleep-accel/1.0.0/`. 2.2 GB uncompressed, 125 files incl. `LICENSE.txt`,
  verified with `SHA256SUMS.txt`.
- Companion code: <https://github.com/ojwalch/sleep_classifiers>.

**Citation** (dataset, as requested by PhysioNet):

> Walch, O. (2019). Motion and heart rate from a wrist-worn wearable and labeled sleep from polysomnography (version
> 1.0.0). PhysioNet. RRID:SCR_007345. https://doi.org/10.13026/hmhs-py35

**Original publication:**

> Walch O, Huang Y, Forger D, Goldstein C. Sleep stage prediction with raw acceleration and photoplethysmography heart
> rate data derived from a consumer wearable device. SLEEP 42(12), 2019, zsz180. https://doi.org/10.1093/sleep/zsz180

**PhysioNet platform** (standard citation requested on every PhysioNet page):

> Pollard T, Moody BE, Lehman L, Gow B, Fernandes C, Xie C, Johnson A, Mark RG, Heldt T. PhysioNet as a global
> platform for biomedical research. Nature Health (2026). https://doi.org/10.1038/s44360-026-00096-z

Content: Apple Watch (PPG heart rate ~every 5 s at night, raw tri-axial acceleration ~50 Hz; 10 Hz for 5383425 and
8258170, ~33-66 Hz for a few others) and in-lab PSG for one night of 31 subjects. All times are seconds relative to
PSG start (negative = before; HR and the Apple step counts extend back up to a week). Five subjects have short scored
nights (3.5-4.7 h: 3509524, 4018081, 46343, 5132496, 759667), consistent with the dataset note that data were cropped
to the window of valid watch collection when the battery ran out.

**Label codes** (verified):
- dataset page, "Data Description": `stage (0-5, wake = 0, N1 = 1, N2 = 2, N3 = 3, REM = 5)`;
- `sleep_classifiers/source/sleep_stage.py`: `wake 0, n1 1, n2 2, n3 3, n4 4, rem 5, unscored -1`;
  `source/preprocessing/psg/psg_converter.py`: ints `-1 -> unscored, 0..5 as above, 6 -> unscored`.
- Observed in the files: -1 (438 epochs), 0 (2429), 1 (1821), 2 (12954), 3 (3329), **4 (356, R&K N4 in subjects
  5383425, 8258170, 9618981)**, 5 (5884). All label rows are exactly 30 s apart and start at t = 0.
- Mapping used: 0 -> AWAKE, 1 + 2 -> LIGHT, 3 + 4 -> DEEP, 5 -> REM; -1 / 6 / anything else -> omitted.

Download note: physionet.org throttles to ~10-20 kB/s per connection (measured), so `download.py` takes the files from
the official S3 mirror (~14 MB/s) and verifies every byte against the `SHA256SUMS.txt` fetched from physionet.org.

## 2. BIDSleep — A Multi-Night Instantaneous Heart Rate and Accelerometry Dataset with EEG Sleep Stage Labels

- Page: <https://physionet.org/content/bidsleep-dataset/1.0.1/> (DOI v1.0.1 [10.13026/rees-1092](https://doi.org/10.13026/rees-1092),
  latest-version DOI [10.13026/hace-p847](https://doi.org/10.13026/hace-p847)); versions 1.0.0 (2026-05-12) and
  1.0.1 (2026-09-25, author list updated only).
- Access policy (page): "Anyone can access the files, as long as they conform to the terms of the specified license."
- Licence: Open Data Commons Attribution License v1.0 — <https://physionet.org/content/bidsleep-dataset/view-license/1.0.1/>.
- Files: `https://physionet.org/files/bidsleep-dataset/1.0.1/`, zip `https://physionet.org/content/bidsleep-dataset/get-zip/1.0.1/`
  (6.35 GB); 27.9 GB uncompressed (`motion.csv` ~ 99 %), 761 files verified with `SHA256SUMS.txt`. No S3 mirror.

**Citation:**

> Song, T., Zhang, Y., Zhou, Z., & Dutta, J. (2026). A Multi-Night Instantaneous Heart Rate and Accelerometry Dataset
> with EEG Sleep Stage Labels (version 1.0.1). PhysioNet. RRID:SCR_007345. https://doi.org/10.13026/rees-1092

**Original publication:**

> Song TA, Zhang Y, Zhou Z, Hou L, Malekzadeh M, Behzad A, Dutta J. AI-driven sleep staging using instantaneous heart
> rate and accelerometry: insights from an Apple Watch study. IEEE Trans Biomed Eng. 2026;73(4):1596-1608.
> https://doi.org/10.1109/TBME.2025.3612158

Plus the PhysioNet platform citation above.

Content: 47 healthy adults, 3-7 nights each at home (253 nights): Apple Watch instantaneous HR (~0.2 Hz, `hr.csv`,
no header) and tri-axial acceleration (`motion.csv`, header `Timestamp,x,y,z`), Unix seconds; Dreem 2 EEG headband
stages in `labels.mat` (MATLAB 5): `recStart` (string, **U.S. Eastern Time**), `dreem_label` (automatic) and
`expert_label` (reviewed by one sleep expert).

**Label codes** (README / page "Sleep Stage Encoding"): Wake 0, N1 1, N2 2, N3 3, **REM 4**, Unknown 5 — note REM
is 4 here, not 5 as in Walch. Mapping used: 0 -> AWAKE, 1 + 2 -> LIGHT, 3 -> DEEP, 4 -> REM, 5 -> omitted. Only the
**expert-corrected** labels are used. Epoch k (0-based) covers `[recStart + 30 k s, recStart + 30 (k + 1) s)`;
`recStart` is converted from America/New_York to Unix time (in the repeated autumn DST hour the offset closest to the
first HR sample is chosen and noted). Times stay Unix epoch ms in the night files. Labels come from an EEG headband
at home, not full PSG.

Download note: no mirror exists and physionet.org serves ~10-15 kB/s per connection, so `download.py --dataset
bidsleep` by default fetches only `hr.csv` + `labels.mat` (~40 MB, minutes). The accelerometer (`--parts all`) needs
the 6.35 GB zip: about 18 h with 4-8 connections, ~5 days with a single `curl -C -`. Until it is downloaded the
BIDSleep night files have `steps: []` (noted in `summary.csv`); re-running `prepare.py --dataset bidsleep` afterwards
fills them in.

## 3. DREAMT (not used)

- Page: <https://physionet.org/content/dreamt/> (latest 2.2.0, 2026-06-02; DOI [10.13026/3f7y-2d80](https://doi.org/10.13026/3f7y-2d80)).
- Page header: **"Restricted Access"**; access policy: "Only registered users who sign the specified data use
  agreement can access the files." Licence: PhysioNet Restricted Health Data License 1.5.0, Data Use Agreement:
  PhysioNet Restricted Health Data Use Agreement 1.5.0.
- Not downloaded and not used: PulseStages uses open-access data only, and a DUA-bound dataset cannot be redistributed
  or used by everyone who builds this project. `download.py --dataset dreamt` only prints this and exits with code 2.
- Reference: Wang K, Yang J, Shetty A, Dunn J. DREAMT: Dataset for Real-time sleep stage EstimAtion using Multisensor
  wearable Technology (version 2.2.0). PhysioNet (2026). https://doi.org/10.13026/3f7y-2d80

## 4. Steps: accelerometer pedometer vs. the Apple Watch's own step counts (Walch)

The night files take steps from the conservative accelerometer pedometer (`pulsetrain/pedometer.py`). The Walch
`steps/` files hold the Apple Watch's own counts in 10-min bins (mostly the week before the PSG; 8 subjects have no
bins overlapping the session). Apple bins are prorated into the window, so steps from just before/after it smear in.
Window "session" = the OHealth-like session; "±30 min" = the context window written to the night files.

Totals over 31 nights: accelerometer **196** steps in sessions vs. Apple **809**; in the ±30-min windows **1585** vs.
**4024** (Apple bins also cover times before the motion recording starts, e.g. 5383425 and 2598705). Accelerometer steps
that fall into PSG-scored *sleep* epochs: **18** in total (3997827: 9, 5797046: 9); in PSG *wake* epochs: 250.

| subject | session min | accel steps (session) | Apple steps (session, prorated) | accel steps (±30 min) | Apple steps (±30 min) | motion coverage (±30 min) | accel steps in PSG sleep / wake epochs |
|---|---:|---:|---:|---:|---:|---:|---:|
| 46343 | 253 | 0 | 30 | 47 | 40 | 0.98 | 0 / 34 |
| 759667 | 236 | 0 | n/a (no bins) | 55 | 113 | 0.96 | 0 / 0 |
| 781756 | 469 | 0 | n/a (no bins) | 132 | 0 | 0.98 | 0 / 9 |
| 844359 | 442 | 0 | 64 | 0 | 102 | 0.95 | 0 / 0 |
| 1066528 | 442 | 0 | n/a (no bins) | 0 | 0 | 0.81 | 0 / 0 |
| 1360686 | 443 | 0 | 72 | 0 | 92 | 0.95 | 0 / 0 |
| 1449548 | 454 | 0 | 30 | 40 | 108 | 0.90 | 0 / 0 |
| 1455390 | 476 | 0 | 40 | 90 | 99 | 0.98 | 0 / 0 |
| 1818471 | 479 | 0 | 10 | 52 | 84 | 0.99 | 0 / 0 |
| 2598705 | 477 | 0 | 10 | 87 | 662 | 0.84 | 0 / 0 |
| 2638030 | 458 | 51 | 75 | 86 | 184 | 0.99 | 0 / 51 |
| 3509524 | 199 | 0 | 0 | 0 | 33 | 1.00 | 0 / 0 |
| 3997827 | 474 | 9 | 22 | 59 | 45 | 0.98 | 9 / 0 |
| 4018081 | 227 | 11 | 20 | 63 | 120 | 0.94 | 0 / 11 |
| 4314139 | 474 | 0 | 19 | 0 | 205 | 0.95 | 0 / 0 |
| 4426783 | 482 | 42 | n/a (no bins) | 73 | 0 | 0.94 | 0 / 42 |
| 5132496 | 226 | 0 | n/a (no bins) | 37 | 75 | 1.00 | 0 / 0 |
| 5383425 | 484 | 0 | 64 | 11 | 579 | 0.91 | 0 / 11 |
| 5498603 | 372 | 8 | 55 | 89 | 115 | 1.00 | 0 / 8 |
| 5797046 | 468 | 17 | 16 | 55 | 76 | 0.98 | 9 / 8 |
| 6220552 | 473 | 0 | 13 | 41 | 45 | 0.99 | 0 / 10 |
| 7749105 | 385 | 0 | 38 | 20 | 98 | 0.17 | 0 / 0 |
| 8000685 | 476 | 0 | 26 | 43 | 309 | 0.98 | 0 / 8 |
| 8173033 | 474 | 21 | 49 | 85 | 391 | 0.98 | 0 / 21 |
| 8258170 | 450 | 0 | n/a (no bins) | 0 | 0 | 0.95 | 0 / 0 |
| 8530312 | 466 | 8 | 54 | 38 | 133 | 0.90 | 0 / 8 |
| 8686948 | 467 | 0 | 44 | 67 | 137 | 0.97 | 0 / 0 |
| 8692923 | 464 | 29 | 37 | 53 | 63 | 0.98 | 0 / 29 |
| 9106476 | 480 | 0 | 20 | 99 | 116 | 0.99 | 0 / 0 |
| 9618981 | 470 | 0 | n/a (no bins) | 131 | 0 | 0.95 | 0 / 0 |
| 9961348 | 351 | 0 | n/a (no bins) | 32 | 0 | 0.97 | 0 / 0 |

(Generated by `prepare.py` into `data/nights/steps_comparison_walch.csv`; "accel steps in PSG sleep / wake epochs"
counts the whole recording.)

How the pedometer was tuned: on these nights, runs of >= 8 band-pass peaks with 0.3-1.5 s intervals occur during PSG
sleep too (42 runs, 8-20 peaks, step-interval CV 0.24-0.63), while real walking at the wrist is often only moderately
regular at the step level (left/right asymmetry, CV 0.17-0.4) but sustained (37-74 peaks). Hence the two-tier rule:
short bouts must be rhythmic (step CV <= 0.2); bouts of >= 20 peaks may instead pass on stride regularity (stride CV
<= 0.25 or step CV <= 0.3); runs are split where an interval deviates > 70 % from the run median. For comparison
(with a 50 % split): a single step-CV threshold of 0.2 for all bouts gave 18 / 172 steps in PSG sleep / wake epochs,
0.3 gave 69 / 786; the chosen rule gives 18 / 250. On synthetic signals still / sensor noise / rolling over give 0
steps, steady 1.5-2.2 Hz walking is counted within 5 %, and random-timing movement sequences are counted < 5 % of
the time (tests/test_pedometer.py). The detector under-counts relative to the Apple Watch (by design:
>= 5 steps in an epoch force AWAKE in the estimator, so false steps during sleep are the costlier error).

**Protocol limitation.** The comparison above used the PSG sleep / wake labels of all 31 Walch subjects, including
the subject that each leave-one-subject-out fold later holds out, so the pedometer rule is label-informed
preprocessing chosen on the evaluation set (outside the LOSO loop). Its possible effect is small and bounded: only 25
of the ~13 000 OHealth-like Walch session epochs have a step, and ALGORITHM.md (section 13.8) reports the shipped
model's fold models re-evaluated on the same nights with all steps removed. A cleaner protocol would freeze the
pedometer on data outside the evaluation set (e.g. the BIDSleep accelerometer, once downloaded) without the Walch
labels.

## 5. Prepared nights (state of 2026-10-13)

`prepare.py` output (`data/nights/`, local only), 4 variants each (`instantaneous`, `mean2min`, `instantaneous-psg`,
`mean2min-psg`):

| dataset | subjects | nights | files per variant | skipped | coverage proxy (min / mean, `instantaneous`) | steps |
|---|---:|---:|---:|---:|---|---|
| Walch (sleep-accel 1.0.0) | 31 | 31 | 31 | 0 | 0.984 / 0.999 | accelerometer pedometer |
| BIDSleep 1.0.1 (expert labels) | 47 | 253 | 253 | 0 | 0.605 / 0.983 | **empty until the motion zip is downloaded** |

Nights to keep an eye on (details in `summary.csv` notes):
- Walch 7749105: sparse HR (62 instantaneous values in a 385-min session) and motion for only 12 % of the session;
  1066528: motion covers 85 % of the session, 14-min raw HR gap; 1449548, 2598705, 8530312: motion covers 85-92 %.
- Walch 1455390 has no N3 and 5132496 no REM in the session; 5383425, 8258170, 9618981 contain R&K N4 (-> DEEP).
- BIDSleep: no night below coverage 0.6; 3 below 0.8 (bid-01 night 3, bid-14 night 4, bid-34 night 1, longest raw
  HR gaps 154 / 122 / 248 min), 23 below 0.9, 39 with an HR gap > 10 min; 57 nights contain "Unknown" epochs inside
  the session (omitted from the labels); 5 nights without N3 (bid-15 nights 1 and 3, bid-34 nights 1 and 2, bid-35
  night 5). No `recStart` fell into the ambiguous DST hour.
