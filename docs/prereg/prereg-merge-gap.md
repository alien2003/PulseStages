# Pre-registration: the merge gap — one night or two when the watch splits sleep at an awakening of 30–59 min

Written 2026-10-22, BEFORE any construct of this experiment (section 5) has been built, decoded or scored, BEFORE any
analysis code of this experiment has been written, and BEFORE any Sleep as Android replay of gate V3 has been run with a
merge gap other than today's. The text was revised before its commit after internal review; no construct, decode or
replay of this experiment existed at any revision. No code of this experiment has been run on any data. The only
computations made for this file (section 2.8) are host and subject counts from the night files' labels, session
boundaries, step records and heart-rate sample times (no heart-rate value, no model, no decode), one count from the
research's Sleep-EDF output, and a synthetic simulation of the decision rule's operating characteristics (section 15)
whose only inputs are those counts and the published intervals of ALGORITHM.md sections 13.8 and 13.12. Every other
number of section 2 comes from the research made before this text (listed there), from the published results of
ALGORITHM.md section 13, or from the code. **This file is committed first, on its own (the pre-registration commit),
with its SHA-256 in `docs/prereg/prereg-merge-gap.md.sha256`;** it is appended verbatim to `tools/train/out/commands.log`
with a timestamp, followed by a `NOTE prereg-merge-gap.md sha256 <digest>` line. Its analysis code follows in a second
commit (the code commit), which pins the same digest (`mergegap.PREREG_PATH`, `mergegap.PREREG_SHA256`) and is made
before the first run of the experiment proper (section 20). Base commit `e7caaf4` (main; all development is on main).
Known when it was written: everything listed in section 2. Nothing is known about any construct, decode or replay of
this experiment.

**Personal data.** Besides the open datasets, this file uses the developer's own data (the development phone of
docs/health-connect.md) in four places, all listed in section 2 and none of them fitted: the start and end times of the
development phone's OHealth sleep sessions of September–October 2026 (section 2.4, Appendix A), aggregate statistics of
the developer's earlier Apple Watch and mobvoi histories (section 2.4), one label the developer gave (section 2.6), and
the longest added Sleep as Android delay the developer will state before the run (U1, section 9.2). No heart rate or
other measurement of the developer enters any construct, decode, threshold or rule. Gates V1 and V2 use only the open
datasets; V3 uses the synthetic timelines of the Kotlin scenario tests (modelled on the development phone's log, as
committed) and U1; the label enters only the consistency check V4. Where a verbatim rule and its operational definition
differ in length or detail, the operational definition governs.

## 1. Question

A watch app writes one night as several Health Connect sessions when it records no sleep for a while: OHealth (the
default source) starts a new session after more than 20 min without sleep data. PulseStages merges the sessions of one
app whose gap is shorter than `sources.mergeGapMin` (30 min) into one night and forces the gap AWAKE
(`NightCandidateBuilder`). A merged group shorter than `sources.minSessionMin` (90 min) is ignored, one of 90–180 min is
a nap (written to Health Connect as asleep / awake only by default, `napMode.outputPolicy` SLEEP_WAKE), and one of
180 min or more is a night. On the development phone, 3 of 23 nights had a gap of 35–38 min followed by more sleep, and
the 30-min rule cut each of them: 30 Sep became a 3 h 19 min night plus a 1 h 43 min nap without stages, and the 23-
and 29-min re-dozes of 10 and 21 Oct were not written at all. The developer labelled 21 Oct as one night (section 2.6).

**Decision.** Should the default change from 30 min (arm A, the control) to 60 min (arm B, primary) or to an asymmetric
continuation rule (arm C: 30 min, but 60 min after a group that already covers at least 180 min)? 45 and 90 min are
reported only. The questions behind it:

1. **Staging harm (V1).** Does merging a fragment across a gap of 35–59 min, forced AWAKE, make the staging of the night
   worse than today's split handling — when the fragment comes at the end of the night, when the gap splits the night in
   the middle, and when the fragment comes at the start — and does it keep the sleep / wake reading of a fragment that
   today becomes a nap?
2. **A separate sleep merged into the night (V2).** A larger gap also merges sleep that is not part of the night: a doze
   that ended 35–59 min before the night (V2a; B only), which moves the night's session start before its real sleep
   onset, and a separate nap that starts 35–59 min after the night's end (V2b; B and C), which the merged night then
   writes with stages. Does either make the night's staging worse, and does the merge stage that nap worse than the nap
   model would?
3. **Sleep as Android (V3).** The gate that holds the Sleep as Android sync request waits for the source's heart rate to
   reach the night's end + `mergeGapMin` + 30 min. Is the measured added wait within the longest delay the developer
   accepts (U1, a number stated before the run), and do the replays still deliver what today's tests assert (a
   regression check)?
4. **Label (V4).** A consistency check: does the arm group the developer's labelled pair as labelled?

**What the decision rests on.** Some decisive inputs are known or near-certain before the run, and they are not
evidence for an arm: V4's outcome is known (section 10); V3a passes for A through the existing assertions and for B and C
whenever the changed-night notice fires, so it is a regression check (section 9.2); C merges no START or DOZE construct,
so those cells are vacuous for C and are not cells of C; and the V1b result at the end of the night is largely predicted
by the continuation decode of section 2.1 (expected direction stated in section 13). The decision therefore rests on the
main-region κ cells (V1a), the separate-sleep cells (V2a, V2b), their positive controls (sections 5.5, 6.2) and the
developer's delay limit (U1).

| what is decided | ground truth | why this one |
|---|---|---|
| staging harm of a merge (V1, V2) | labelled open-dataset nights (Walch PSG, BIDSleep headband) with a synthetic stretched awakening, a prepended doze or an appended nap, decoded merged and split by the model in effect | the only labelled heart-rate data; the merge acts on the session boundaries, which can be constructed exactly |
| Sleep as Android behaviour (V3) | the Kotlin scenario harness of `SleepAsAndroidGateScenarioTest`, replayed under each arm | the gate is app code; its replays are the single source of truth for what the app sends and when |
| acceptability of the longer wait (V3) | the developer's numeric limit U1, logged before the run, against the measured delay | a preference, turned into a fixed numeric pass rule |
| grouping of the real pair (V4) | the developer's label | the only label of a real split night; a consistency check |

What the arms cannot be told apart by: on the developer's 33 days of OHealth sessions every merge gap from 39 to 126 min,
and arm C, group the sessions identically (section 2.4). The developer's data therefore cannot choose between B and C or
between 45, 60 and 90 min; V1 and V2 decide, for every user.

## 2. Known beforehand (disclosure)

### 2.1 The code and the published results

- `sources.mergeGapMin` = 30 min (`data-local` `AppSettings.kt`) was set in the first pipeline commit and never
  validated. The settings allow 0–240 min. The value is not part of the params hash: a changed value regroups a night
  only when that night is processed again (a late-data refresh or Recompute), regrouped nights get a new
  `clientRecordId` and supersede the old records, and Sleep as Android never updates a record it imported.
- Three uses in production code: (1) `NightCandidateBuilder.merge` (per package, sessions sorted by start, end, uuid; a
  session joins the group when its start minus the group's latest end so far is `< mergeGapMin`; the uncovered gaps
  become forced-AWAKE spans; the group's span decides `minSessionMin` and the night / nap mode; the ledger key is the
  digest of the sorted uuids; sessions with their own stages are merged the same way). (2)
  `SourceSettlement.dataMarginMs` = `mergeGapMin` + 30 min (`DATA_MARGIN_EXTRA_MS`): the heart-rate threshold of the
  Sleep as Android gate, the quiet rule and the fine-grained-steps test, one value for every session
  (`SourceSettlement.kt` line 323); its KDoc says the 30 min cover "a back-to-sleep fragment that would merge with the
  session and the 20–27 min OHealth needs to report a sleep period". (3) `SleepAsAndroidSyncRequests.longestWaitMs` =
  max(`QUIET_MS`, `CLOCK_TOLERANCE_MS` + data margin).
- core-algo forces whole epochs: an epoch is forced AWAKE when its midpoint lies in a forced span
  (`BaseFeatureExtractor.forcedAwake`, `mid >= start && mid < end`); the 60-s epoch grid is anchored at the session start
  (`EpochGrid`, `preprocess.epochSec` 60); the last epoch is clipped at the session end (`IntervalMerger`).
- The Sleep as Android data margin at G = 30 / 45 / 60 / 90 / 120 min is 60 / 75 / 90 / 120 / 150 min; the user guide's
  advice to open OHealth "75 minutes or more after you wake up (with the default "Merge gap" of 30 minutes)" would
  become about 90 / 105 / 135 / 165 min.
- Merged gap epochs are forced AWAKE but enter the night's heart-rate normalization statistics (P10 / P50 of the
  session); the session-time features (`t`, `hoursSinceStart`, `hoursToEnd`) are measured from the merged session's
  boundaries; with `write.awakeAsLight` the gap is written as LIGHT. The research bounded the P50 shift by assuming
  every gap epoch above the old P50 (P50 becomes the old percentile 50 / (1 − f), f = gap share of the session): 21 Oct
  f = 0.079, P50 → P54.3, P10 → P10.9; 30 Sep f = 0.107, P50 → P56.0; 10 Oct f = 0.072, P50 → P53.9; a 60-min gap with
  6 h of sleep and a 30-min fragment f = 0.133, P50 → P57.7; a 120-min gap f = 0.235, P50 → P65.4, P10 → P13.1. The
  effect on accuracy is untested.
- ALGORITHM.md section 9 (source selection): a nap ending less than 30 min before bedtime becomes the first fragment of
  the night, "unchanged, because it cannot be told apart from a night that the watch split at an awakening".
- prereg-naps-realistic.md section 2.2 (diagnosis of the nap model N1): decoding a late return-to-sleep window as the
  continuation of its night beat a stand-alone nap decode (BIDSleep accuracy +0.148 [0.092, 0.206]); those data covered
  wake bouts < 40 min only, i.e. inter-session gaps < 30 min under the 5-min session margins, which the app already
  merges; hence "merging beyond 30 min has no support" (also its section 6.8) — no labelled data covered it, which is
  not evidence against it. The continuation decode was a probe script of the diagnosis and is not committed; arm M of
  section 5.6 specifies it operationally here. A nap-proxy session starting 10 / 20 min late lowered onset-60 accuracy
  from 0.692 to 0.532 / 0.414 (late start, the opposite direction of V2a).
- ALGORITHM.md section 13.8 (Walch, 31 subjects, outer fold models, mean per-subject κ, Δ vs base [95 % CI]): narrow-10
  (start 10 min later, end 10 min earlier) −0.020 [−0.037, −0.005] instantaneous, −0.012 [−0.026, 0.001] mean2min;
  late-start-15 −0.025 [−0.046, −0.003] / −0.023 [−0.039, −0.009]; wide-10 +0.009 [−0.002, 0.021] / +0.015 [−0.006,
  0.038]; wide-20 (start 20 min earlier, end 20 min later, more in-bed wake) +0.014 [0.003, 0.026] / +0.018 [0.002,
  0.038]. The half-widths imply per-subject SDs of the paired change of about 0.046 (narrow-10), 0.033 (wide-20) and
  0.061 (late-start-15), instantaneous (half-width × √31 / 1.96); section 15 uses them.
- ALGORITHM.md section 13.12: paired nap differences on Walch (N1 − N2) of κ +0.023 [−0.019, 0.061], sleep / wake κ
  −0.035 [−0.069, −0.009], accuracy +0.085 [0.041, 0.128] (instantaneous): per-subject SDs of about 0.11, 0.085 and 0.12.
  Section 13.13: no new nap model was adopted; N1 is in effect with the nap output policy SLEEP_WAKE (its rule (ii):
  STAGES needs accuracy ≥ "all LIGHT" + 0.03 and κ ≥ 0.15).
- On 21 Oct PulseStages wrote only the night 00:18–07:12; the 07:50–08:19 piece (29 min) was ignored as shorter than
  `minSessionMin`; the record of it in Sleep as Android is OHealth's own session (no stages). OHealth published the
  piece at 08:19:21, 67 min after the night's end; Sleep as Android was asked at 10:27 with heart rate up to 10:25 (no
  run between 09:32 and 10:27, Doze); with the night merged to 08:19, any merge gap ≤ 96 min would have given the same
  send time (one case).

### 2.2 Reference-scored nights (labels only; no model, no decode)

The research measured, in every night, each continuous non-sleep run (wake or unscored) between two sleep epochs —
what the merge rule acts on — in Walch (31 lab nights, 29 ± 9 y, cut at lights-on; 5 nights cropped by the watch
battery) and BIDSleep (253 home nights of 47 healthy adults, Dreem 2 headband, expert-corrected; ages not published),
with the repository's own readers. Share of nights (%) with at least one gap of T min or more inside the sleep period,
raw 30-s scoring (subject-bootstrap 95 % CI where computed):

| set | nights (subjects) | ≥ 10 | ≥ 20 | ≥ 30 | ≥ 38 | ≥ 45 | ≥ 60 | ≥ 90 | ≥ 120 |
|---|---|---|---|---|---|---|---|---|---|
| Walch | 31 (31) | 29.0 | 9.7 | 6.5 | 0 | 0 | 0 | 0 | 0 |
| BIDSleep | 253 (47) | 30.8 | 9.9 [5.9–14.7] | 4.7 [2.0–8.0] | 2.4 [0.4–4.7] | 2.0 [0.4–3.7] | 0.8 [0–2.0] | 0 | 0 |
| pooled | 284 (78) | 30.6 | 9.9 [6.0–14.3] | 4.9 [2.3–8.0] | 2.1 [0.4–4.2] | 1.8 [0.4–3.4] | 0.7 [0–1.8] | 0 | 0 |
| pooled, sleep runs < 5 min bridged | 284 (78) | 40.5 | 16.9 [11.8–22.9] | 8.1 [4.4–12.6] | 5.3 [2.4–9.0] | 3.9 [1.7–6.7] | 1.8 [0.4–3.4] | 0 | 0 |

- Longest gap per night, pooled raw (p25 / median / p75 / p90 / p95 / p99 / max, min): 3.0 / 6.0 / 12.0 / 19.5 / 29.2 /
  50.5 / 78.0; bridged 4.0 / 7.5 / 15.0 / 27.0 / 38.3 / 64.5 / 82.0. Median WASO: Walch 16.5, BIDSleep 13.5 min; median
  sleep period: Walch 461, BIDSleep 403 min.
- Where the 15 raw gaps ≥ 30 min sit (Walch + BIDSleep): 8 mid-night, 3 initial (one with < 10 min of sleep before it), 4
  terminal (< 60 min of sleep after it until the final awakening; one with < 10 min); 10 of 15 contain no unscored
  epoch; 5 of the 13 BIDSleep ones contain 'Unknown' epochs and bid-18 night 2's 45-min gap is entirely 'Unknown'. Of
  the 6 raw gaps ≥ 38 min: 3 initial (right after sleep onset), 2 terminal, 1 mid-night.
- Nights in which a second piece with ≥ 10 min of sleep splits off at a threshold G = 20 / 30 / 38 / 45 / 60 / 90 /
  120 min: raw 26 / 13 / 5 / 4 / 1 / 0 / 0 of 284; bridged 40 / 19 / 12 / 8 / 3 / 0 / 0.
- Censoring: time recorded after the last sleep epoch: Walch median 1.0 min (24 of 31 under 5 min), BIDSleep median 0
  (127 of 253 exactly 0, 209 under 5 min). Both datasets end at or near the final awakening, so a return to sleep after
  getting up in the morning is almost unobservable in them.
- Scripts and outputs (not committed): the research's `wake_bouts.py`, `tabulate.py` and their outputs; no model was
  run.

### 2.3 Sleep-EDF Expanded SC (whole days, labels only; not used by the experiment)

153 home recordings of about 20 h of 78 healthy subjects aged 25–101 (1987–1991, Rechtschaffen & Kales, movement time
counted as non-sleep; ODC-By 1.0, Kemp et al. 2000; files checked against PhysioNet's SHA256SUMS; used for aggregate
counts only, as tools/train/DATASETS.md allows). Median time after the last sleep epoch 494 min (every recording ≥ 120
min), so mornings are observed.

| set | recordings (subjects) | ≥ 10 | ≥ 20 | ≥ 30 | ≥ 38 | ≥ 45 | ≥ 60 | ≥ 90 | ≥ 120 |
|---|---|---|---|---|---|---|---|---|---|
| all | 153 (78) | 61.4 | 35.3 | 28.8 [20.7–37.4] | 23.5 [15.9–32.0] | 19.6 | 12.4 [7.3–17.9] | 7.2 | 5.2 |
| under 60 y | 80 (41) | 46.3 | 20.0 | 13.8 [6.3–22.2] | 8.8 [3.7–15.0] | 6.3 | 6.3 [1.3–11.5] | 2.5 | 2.5 |
| 60 y and over | 73 (37) | 78.1 | 52.1 | 45.2 | 39.7 | 34.2 | 19.2 | 12.3 | 8.2 |

- Split simulation (as in 2.2) at G = 20 / 30 / 38 / 45 / 60 / 90 / 120: under 60 y 11 / 8 / 4 / 2 / 2 / 0 / 0 of 80;
  60 y and over 33 / 30 / 24 / 21 / 11 / 5 / 3 of 73. Median WASO: under 60 y 28 min, 60 y and over 89.5 min; median
  sleep period 476.5 min. Longest within-episode gap (main episode cut at gaps ≥ 180 min): under 60 y p90 35.7, p95
  76.9, max 144.5 min; 60 y and over p90 95, max 146 min.
- Emulating OHealth's fill of gaps ≤ 20 min and keeping blocks with ≥ 10 min of sleep, the gaps between blocks of the
  subjects under 60 fall at 20–90 min (within the night) or at 240 min and more (evening dozes, afternoon naps), none at
  90–240 min (20 gaps of 41 subjects; the fill is an emulation and OHealth's minimum session length is unknown). For 60 y
  and over, within-night gaps reach 146–175 min and the empty range shrinks to 180–240 min.
- A gap of ≥ 30 min near the end of the night followed by 10–60 min of sleep: 1 of 80 recordings under 60 y (SC4242, 54
  y: a 37.5-min gap at 05:13, then 46 min of sleep); 17 of 73 at 60 y and over.

### 2.4 The developer's own history (session boundaries only)

**OHealth, development phone** (the 39 OHealth-like sessions that Sleep as Android read from Health Connect on 21 Oct,
33 days, 23–24 nights; two early ones, 18 Sep 13:09 and 25 Sep 15:15, possibly of another app; the pairs of Appendix A
fall in 29 Sep–21 Oct). Between consecutive sessions less than 4 h apart: gaps of 21, 23, 23, 23, 27, 35, 36 and 38 min,
then 126 and 195 min; nothing between 39 and 125 min. The 21–38-min gaps look like returns to sleep within the night
(inference from position: three mid-night awakenings after 1 h 47 min–3 h 19 min of session, followed by 1 h 43 min–5 h
44 min more; five re-dozes after 5 h 42 min–7 h 08 min); the 126- and 195-min gaps are late-morning naps after 2–3 h
awake. Grouping of the 39 sessions (research `simulate.out`; chained, strict <, `minSessionMin` 90, nap threshold 180):

| merge gap G (min) | merged groups | nights | naps | ignored |
|---|---|---|---|---|
| 0 or 20 | 0 | 24 | 4 | 11 |
| 30 (A) | 4 | 23 | 2 | 9 |
| 39–126 (45, 60 = B, 90, 120 identical) | 7 | 23 | 1 | 7 |
| 127 | 8 | 23 | 1 | 6 |
| ≥ 196 | 9 | 23 | 0 | 6 |
| C (30; 45–120 after ≥ 180 min) | 7 | 23 | 1 | 7 (identical to 39–126) |

Compared with 30 min, every G from 39 to 126 min adds exactly three merges, each a fragment after the main sleep: 30 Sep
(gap 36; 02:37–08:15, 5 h 38 min), 10 Oct (gap 35; 03:15–11:21) and 21 Oct (gap 38; 00:18–08:19, 8 h 01 min). Sleep left
outside nights within 4 h after a night: 2.1 h at 30 min (2 naps, 3 ignored pieces) against 1.2 h at 45–120 min (the
separate naps of 2 Oct and 6 Oct). No daytime or evening session ended less than 465 min before the next night. OHealth's
own log of wake gaps (dataVersion 11) shows gaps of 18–19 min filled and of 21–23 min split, a cluster around its 20-min
limit; every gap over 20 min matched a split in Health Connect. A second simulation over 37 OHealth sessions of 23 days
(`simulate_g.out`) gave nights / naps / ignored 24 / 4 / 9 at G = 0, 23 / 2 / 7 at 30, 23 / 1 / 5 at 45–120, and no
merged wake of ≥ 45 min inside a night at any G.

**Apple Watch, 10 Jun–28 Sep 2026** (114 episodes of Apple's own data; sessions inferred from their creation dates). Wake
bouts between asleep samples: 30–45 min 4 (32.8, 39.6, 39.8, 42.7 min), 45–60 min 0, 60–90 min 1 (80.8), 90–120 min 0,
120–240 min 2 (147.2, 162.9). Apple's own session pairs less than 4 h apart: gaps 33 (4 Sep), 40 (14 Aug), 81 (26 Jun),
147 (6 Aug) and 163 min (23 Jul); the 33- and 40-min ones were morning re-dozes (inference), and Apple kept 40- and
43-min wake bouts inside one session. Simulated over 119 source sessions: G = 30: 111 nights, 5 naps, 3 ignored, 1.3 h
of sleep within 4 h after a night not written; G = 45 or 60: 111 / 4 / 2, 0.0 h; G = 90 or 120: 111 / 3 / 2 with one
merged wake of 81 min. The Apple Watch and AutoSleep sessions that an import tool of the developer wrote to Health
Connect in September had their fragments less than 60 min apart already glued: no pair less than 4 h apart (smallest
gaps 309–449 min), and PulseStages never estimated them.

**mobvoi, Nov 2024–Jun 2026** (912 sessions; the device splits at about 11 min). Pairs less than 4 h apart by gap band,
earlier period (to 14 Jan 2026) / later period: 0–20 min 99 / 12, 20–30 80 / 10, 30–45 54 / 9, 45–60 28 / 3, 60–90 22 / 3,
90–120 12 / 1, 120–180 9 / 9, 180–240 7 / 5. Simulated over all periods, G = 30 / 45 / 60 / 90 / 120: naps after a night
35 / 21 / 19 / 13 / 12, ignored pieces 30 / 21 / 16 / 11 / 9, sleep not written 38.6 / 27.4 / 20.7 / 13.9 / 11.2 h, nights
with ≥ 45 min awake inside 0 / 0 / 31 / 56 / 69; a night followed within 4 h by another night: earlier period 37 / 20 /
11 / 6 / 0, later period 7 / 5 / 4 / 3 / 3. In the later period mid-night awakenings of 47–77 min followed by hours of
sleep occur and separate naps start 121–216 min after a night, so the two ranges overlap at about 75–125 min; the
earlier period is fragmented without a valley (weak evidence). Two pre-bedtime evening naps in 19 months (gaps 107 and
170 min), found by a scan of first fragments starting 16:00–01:00, which would miss a doze starting after 01:00 (the
developer's nights start between 00:18 and 04:21).

### 2.5 Literature and other trackers (from the research notes; not re-read for this file; no threshold comes from them)

- No formal definition ends a night after a given time awake. PSG counts wake after sleep onset (WASO) from sleep onset
  to lights-on; the Consensus Sleep Diary (Carney et al. 2012) counts every awakening between the first sleep onset and
  the final awakening as WASO, without an upper limit (its worked example has a 35-min awakening), and ends the night at
  the final awakening and getting out of bed for the day.
- Wake-bout durations have a heavy tail without a characteristic length near 30 min (Lo et al. 2002: power law,
  α = 1.3 ± 0.1); beyond about 20 min awake the hazard of falling asleep again is roughly constant (Klerman et al. 2013,
  slow time constant 82–116 min; a forced-desynchrony protocol with fixed lights-out, in which nobody could get up for
  the day). Under that power law P(> 60 min) / P(> 30 min) = 0.41 (0.31–0.54 for α 1.7–0.9) and P(> 90) / P(> 30) =
  0.24: the same tail argues against any duration-only rule, 60 min included.
- Awakenings of ≥ 30 min are common (NSRR ANSWERS, n = 971: 63 % at least monthly); 15 % of adults have difficulty
  resuming sleep, with a mean awakening of 107 min (Ohayon 2010); snoozing averages 22 min (Sundelin 2023); the
  ~30-min insomnia cut-offs (Lichstein 2003) concern total WASO and assume the wake belongs to the night.
- Other rules: GGIR HDCZA merges rest blocks less than 60 min apart (van Hees et al. 2018), which its authors call an
  assumption made to avoid adding evening or afternoon naps; its sensitivity search over 30–90 min found the default
  robust, and its configuration #8 (73 min) did better than the default. Fitbit keeps one sleep log unless the wake
  lasts more than 1 h or holds 15 min or more of consistent steps, and stitches logs less than 1 h apart; Polar stops
  sleep tracking after a break of more than 1 h; WHOOP about 50 min (an unofficial community reply); Oura counts no nap
  within 4 h of the main sleep's end, Garmin none within 1 h of waking. No documented vendor rule uses 30 min.
- OHealth (decompiled code, dataVersion 11) keeps scored wake of any length inside a session and starts a new session
  only after more than 20 min without sleep data. An OHealth split therefore means that the watch stopped recording
  sleep, not that an in-bed wake bout reached a length; the wake-bout rates of 2.2 and 2.3 do not estimate how often
  OHealth splits a night. In-bed wake that the watch scores stays inside one session whatever its length; the likely
  contents of a real split are phone use or getting up (higher heart rate, steps) or the watch off the wrist (no heart
  rate). Section 13 states what this means for the constructs.

### 2.6 The developer's label

Before the label, the developer had been sent the research's summary (in Russian). It said that 30 min was never
validated; that the 35–38-min gaps of 30 Sep, 10 Oct and 21 Oct look like returns to sleep within the same night rather
than separate sleeps (an inference from where they fall); that physiology points to no duration either and that the real
end of a night is getting up for the day; and that a 60-min gap would move the advice to open OHealth from about 75 to
about 105 min after waking. It asked: "What did you do in these gaps: lie in bed or get up for the day? 30 Sep
05:56–06:32, 10 Oct 10:23–10:58, 21 Oct 07:12–07:50", and whether a later request would be acceptable if 60 min passed a
test. The developer answered (message dated "today, the 21st"; received 2026-10-22; translated from Russian): "I don't
remember the other nights, but today, the 21st, I actually slept until 8:19. I woke up, moved the alarm and slept on."
The delay question was not answered (U1, section 9.2, asks it as a number).

Label: the OHealth sessions 00:18–07:12 and 07:50–08:19 of 21 Oct (gap 38 min; covered time before the gap 414 min) are
**one night (CONTINUATION)**. The label does not say what the developer did during the 38 min (lying in bed, using the
phone or getting up); that OHealth recorded no sleep data for 38 min fits any of them. Because the label was given after
the research's view had been stated, a leading effect cannot be excluded; for a recall made the next day about that
morning it is judged small. 30 Sep (gap 36) and 10 Oct (gap 35) are unlabelled. No other label exists; labels given
after this text was written do not enter V4 (they are reported).

### 2.7 How the arms came about (review of the research before this text)

The research (physiology, the datasets of 2.2–2.3, the developer's history, the code) recommended B = 60 min as the
primary arm, C as a declared fallback and A as the control, with gates V1–V4 and "BIDSleep primary; Walch must not
contradict". A review of it corrected the following, and this text follows the corrections:

- **60 was chosen after seeing data**: the developer's gaps, the grouping table of 2.4 and the split counts of 2.2; it is
  "fixed in advance" only relative to this test. GGIR's authors call 60 min an assumption, and their configuration #8
  (73 min) did better than the default; the Sleep-EDF block gaps (20–90 min, then ≥ 240 min) and GGIR's 73 min fit about
  90 min at least as well. 60 min is preferred over 90 min only for the shorter Sleep as Android wait and the smaller
  evening-doze risk. 45 min would be the smallest value that covers the developer's largest observed gap (38 min), which
  is tuning to one person's sample. Hence 45 and 90 are reported, never adopted.
- Physiology does not point to 60 min either: it rejects every duration-only rule (2.5). An OHealth gap is not an
  in-bed wake bout (2.5), so the PSG and headband rates bound in-bed wake, not OHealth splits.
- V1 must stretch an awakening at the start, in the middle and at the end of the night (not only "after 180 min of
  sleep in the last third", which tests only C's subset); V2 needs its own early-start probe, because the late-start
  probes (2.1) move the start in the opposite direction; the decision tree must cover every pass / fail combination,
  including V3 and V4 failing.
- C's "180 min of sleep" must be defined: on 30 Sep the 199 min before the gap are session time, in-session wake
  included, not sleep (section 3.1). C splits early-night long awakenings: 3 of the 6 raw gaps ≥ 38 min of 2.2 came
  right after sleep onset. C also absorbs a deliberate morning nap that starts 30–59 min after a long night (V2b tests
  this).
- V4 cannot rest on recalled pairs weeks or months old (the developer did not remember 30 Sep and 10 Oct); at about 3 in
  23 nights, a 60-day prospective window would yield about 7 qualifying gaps. V4 here is one label (2.6).
- A walking veto (D: a 30–59-min gap with ≥ 15 min of steps at ≥ 5 steps / min is not merged) and a Sleep as Android
  margin decoupled from the merge gap are out of scope: each needs its own pre-registration or amendment (section 20).
- The shipped default stays 30 min until this test decides.

A second review, of an earlier draft of this file, found that the draft's per-F cells would have had 1–8 Walch subjects
and 16–33 BIDSleep subjects each and that its rule could hardly adopt anything even without harm (section 15); this
text pools fragment lengths and gaps per subject, weights subjects equally, adds the nap-after-the-night design (V2b),
a positive control, a numeric U1 and the heart-rate variants of section 5.1, and keeps "harm shown" apart from
"non-inferiority not shown".

### 2.8 Computed for this file

All with design scripts that are not committed (`final_counts.py`, `oc_final.py`); `mergegap.py` recomputes the counts
as gate G7 and reports any difference. No construct was built and nothing was decoded or scored.

**Host and subject counts** (the host rules of section 5; labels, session boundaries, step records and the presence of
heart-rate samples only). Both variants have identical labels, session boundaries and step records in all 284 nights.
Two BIDSleep nights have no heart-rate sample in an AWAKE label interval in at least one variant (bid-62-1
instantaneous, bid-68-2 both) and host nothing (a host needs a non-empty wake pool in both variants, so both variants
have identical constructs). 41 nights host no design (Walch 10, BIDSleep 31). Hosting nights (subjects) per design,
identical in both variants:

| design | Walch | BIDSleep | | design | Walch | BIDSleep |
|---|---|---|---|---|---|---|
| END-25 | 4 (4) | 36 (25) | | START-25 | 2 (2) | 48 (30) |
| END-60 | 4 (4) | 63 (34) | | START-60 | 7 (7) | 87 (42) |
| END-100 | 2 (2) | 35 (22) | | START-100 | 4 (4) | 49 (28) |
| END-140 | 7 (7) | 62 (37) | | START-140 | 4 (4) | 84 (38) |
| MID | 8 (8) | 61 (33) | | DOZE-25 | 2 (2) | 48 (30) |
| NAPAFTER-60 | 7 (7) | 87 (42) | | DOZE-60 | 7 (7) | 87 (42) |
| NAPAFTER-100 | 5 (5) | 51 (28) | | DOZE-100 | 5 (5) | 51 (28) |

Per decisive cell (section 6.2), constructs of one gap and heart-rate variant (subjects; BIDSleep median constructs per
subject): V1a-late (END-25…140, MID) Walch 25 (17), BIDSleep 257 (47; 5); V1a-early (START-25…140) 17 (11), 268 (46;
6); V1b-late (END-100, END-140) 9 (9), 97 (39; 2); V1b-early (START-100, START-140) 8 (7), 133 (41; 3); V2a (DOZE) 14
(11), 186 (46; 4); V2b (NAPAFTER) 12 (11), 138 (44; 3). Under the dataset rule of 6.2 (a dataset with fewer than 10
subjects in a cell is left out) Walch enters every cell except V1b-late and V1b-early, so the cells have 64, 57, 39, 41,
57 and 55 subjects. In 13 (instantaneous) and 11 (mean2min) BIDSleep nights there is no heart-rate sample in the 30 min
before t0 (the reported `presleep` variant cannot be built there).

**Sleep-EDF, the nap-after-the-night case** (labels only; the research's block-gap output of 2.3): a block gap of 30–59
min after a block spanning ≥ 180 min occurs in 3 of 80 recordings under 60 (starting 05:08–05:18; the next block spans
72.5–126 min) and in 16 of 73 at 60 and over (02:33–08:34; 7 followed by a block spanning ≥ 90 min). Labels cannot tell
a return to sleep from a separate nap, so these are upper bounds of how often V2b's case occurs; under 60 none starts
after 05:18.

**Operating characteristics** of the rule of section 11 under synthetic per-subject changes: section 15.

### 2.9 Not known

Any decode or score of any construct or positive control (M, S or S′, any design, gap or variant); the per-subject SDs
of the cells' changes (section 15 assumes them); any request time of a Sleep as Android replay with a margin other than
60 min or with the upload extensions of section 9; how OHealth delimits a session before and after a gap (the 5-min
margins are an assumption, as in every earlier experiment); what the developer was doing during the 30 Sep, 10 Oct and
21 Oct gaps; U1.

## 3. Arms and design in brief

### 3.1 Arms (fixed now)

| arm | `sources.mergeGapMin` | continuation | Sleep as Android data margin | role |
|---|---|---|---|---|
| **A** | 30 | none | 60 min | control: today's default, kept unless B or C is adopted |
| **B** | 60 | none | 90 min | primary |
| **C** | 30 | 60 min after a group that covers ≥ H | 90 min for a session whose group covers ≥ H, otherwise 60 min | declared fallback |
| R45 | 45 | none | 75 min | reported only |
| R90 | 90 | none | 120 min | reported only |

**Common grouping rule** (today's `NightCandidateBuilder`, unchanged for A, B, R45, R90). Per package, sessions sorted by
(start, end, uuid); a session joins the current group when its start minus E is below the arm's threshold, E = the
latest end of the group so far (an overlap always joins); otherwise it starts a new group. A group's span = [first
start, latest end]; its forced-AWAKE spans = the parts of the span not covered by any of its sessions; span < 90 min
(`sources.minSessionMin`) → ignored; 90 ≤ span < 180 min → nap (`napMode.maxSessionMin` 180; output policy SLEEP_WAKE
by default, i.e. no stages in Health Connect); span ≥ 180 min → night.

**C precisely.** All times in whole milliseconds, in Kotlin and in Python alike. The threshold for the next session is
60 min (`sources.continuationGapMin`) when the current group (before the session joins) has covered time K ≥ H, and
`sources.mergeGapMin` (30 min) otherwise. K = the measure of the union of the group's session spans [start, end) — the
span minus its forced-AWAKE gaps (overlapping sessions count once). H = `napMode.maxSessionMs` of the `AlgoParams` in
effect (`maxSessionMin` rounded up to whole ms, 180 min by default) when it is positive; when `napMode.maxSessionMin` is
0 (naps disabled) or no `AlgoParams` is passed, H = `NapModeParams().maxSessionMs` (`TrainedDefaults.NAP_MAX_SESSION_MIN`,
180 min), so C keeps its meaning instead of becoming B. **K is session time, not sleep:** it includes any wake the source
kept inside its sessions; OHealth's sessions carry no stages (a session with its own stages is not estimated at all), so
the app cannot see sleep. C reuses the number 180, not the night / nap boundary itself: that boundary uses the span,
gaps included, while K excludes the gaps, so a group can be a night by span with K < H (for example 80 min + a 25-min
gap + 80 min: span 185, K 160) and then still gets the 30-min threshold. K grows as OHealth publishes provisional and
final versions of a session, so C's threshold for a session may change from 30 to 60 min between runs (the 18 Oct
replay of section 9 does this: K = 165 min at 07:30, 211 min at 09:37). The covered time of 30 Sep before its gap is 199
min (19 min above H; its true sleep is unknown); of 10 Oct 428 min; of 21 Oct 414 min. Merges chain without a cap, as
today; there is no "one continuation per group" limit. The Sleep as Android margin of a session is the threshold that
would let a later fragment join that session's group (60 min if its group, with the session, has K ≥ H, else 30 min) +
30 min; `longestWaitMs` uses the larger threshold.

### 3.2 Design in brief

V1 and V2 compare two decodes of the same construct by the model in effect: **M** = what B or C does (one merged
session, the gap forced AWAKE) and **S** = what A does (each piece on its own: night, nap or ignored). Every construct
is built from a labelled open-dataset night: V1 stretches one real awakening into a wake bout of g + 10 min, so that
under the 5-min session margins the two sessions are g min apart (END: a fragment after the night, MID: the night split
in the middle, START: a fragment before it); V2a places a copy of the night's own first minutes before the night with a
g-min gap (a doze), V2b after the night's end (a separate nap). The arms differ only in which constructs they merge: B
merges every construct with g < 60 min; C merges END, MID and NAPAFTER (the group before the gap covers ≥ 180 min) and
no START or DOZE construct; A merges none (g ≥ 35 min). One set of decodes therefore evaluates every arm. V3 replays the
Sleep as Android gate in the Kotlin scenario harness under each arm. V4 applies each arm's grouping to the labelled pair.
Nothing is trained, fitted or tuned; the decision uses decodes and scores only.

**Why semi-synthetic constructs do not break the house rule.** A default that changes estimates is chosen on the open
datasets and never on synthetic data (PLAN.md section 2; docs/development.md, "Pre-registration"). Nothing here is
fitted or tuned on synthetic data: the labels and heart-rate values of every construct are the open nights' own, and
only the position of the gap, the doze or the nap and the heart rate inside the gap are constructed. No unmodified
open-dataset night can measure the question: Walch and BIDSleep end at the final awakening (2.2), BIDSleep has no
pieces split by an app, and Sleep-EDF, which observes mornings, has no heart rate. The test is a harm test of the
grouping on real nights with a constructed gap, not a tuning of a parameter on synthetic data; the margins are fixed
here, before any construct exists.

## 4. Data, model and decoding

- **Nights.** The 284 night files of the 78 subjects of `walch+bid-zero` (31 Walch = PSG; 47 BIDSleep = Dreem 2
  headband, expert-corrected), both variants (`tools/train/data/nights/{instantaneous,mean2min}/`): 568 night-variants.
  Each has the OHealth-like session [t0, t1] of `prepare.py` (first sleep epoch − 5 min to last sleep epoch + 5 min),
  labels in 30-s intervals over [t0, t1], heart rate on the 2-min OHealth-like grid with up to 30 min of context.
- **Model in effect** (ALGORITHM.md 13.13 kept N1), at fold level: each held-out subject's N1 fold bundle — the N0
  fold bundle of run `bias-base` (with b_g and b_short_g(180)) with 13.12's stored outer nap model and its group's nap
  stage log-bias as the nap section — `napsrealistic.fold_bundle(inp, "N1", variant, subject, None, None)`, decoded
  with `napsrealistic.Inputs.n1_params(g)` (the group's night decoder θ_g, β = 1, `napMode.maxSessionMin` 180,
  `napMode.estimator` HYBRID, `napMode.outputPolicy` STAGES so that naps are scored, `napMode.hybrid` = θ_nap,g,
  `napMode.contextMarginMin` absent = the night's 30 min). These are the bundles and parameters of naps-realistic gate
  G1, which reproduced every stored N1 decode and candidate B on all 568 nights. Leave one subject out: every construct
  of a subject is decoded only by that subject's fold bundle.
- **Read only:** runs `bias-base`, `bias-base/bias`, `naps` and `nap-model` (through `napsrealistic.Inputs`), the night
  files.
- **Decoding.** `algo-cli evaluate --estimator HYBRID --fold-models <dir> --params <json> --per-epoch <csv>`: per
  (variant, outer group), the group's sessions are sorted by id and cut into consecutive blocks of at most 1000
  sessions, one call per block, so the calls depend only on the ids and `--jobs` changes no result. At most 10 calls run
  at once (`JAVA_OPTS=-Xmx2g`), and no Gradle task (G2, G4, V3) runs while decodes run. algo-cli is the single source of
  truth for features, decoding and per-session scoring; Python builds the session files, emulates the app's grouping
  (gate G4) and scores regions from the per-epoch CSV (gate G5).

## 5. Constructs

The committed `pulsetrain/mergegap.py` is the authoritative definition; the prose below describes it and a unit test
asserts each worked example of the prose against the code on synthetic nights.

### 5.1 Common parts

- MIN = 60 000 ms. Night n: session [t0, t1]; labels [a, a + 30 s) with stage AWAKE, LIGHT, DEEP, REM or UNKNOWN; sleep
  = LIGHT, DEEP or REM; heart-rate samples (t, bpm); step records (start, end, count).
- **Host bout.** A wake bout (b0, b1) of `napmodel.wake_bouts(labels)` (a maximal run of consecutive AWAKE label
  intervals) with 2 MIN ≤ w = b1 − b0 ≤ 40 MIN (2 MIN = `pools.R_MIN_BOUT_MS`, the return-to-sleep bout of
  naps-realistic), whose neighbouring label intervals (the one ending at b0 and the one starting at b1) both exist and
  are sleep, with t0 < b0 and b1 < t1, and with no step record whose start < b1 < end. Piece lengths: λ1 = b0 + 5 MIN − t0
  (the session the source would write before the bout), λ2 = t1 − (b1 − 5 MIN) (after it). A night hosts only when its
  wake heart-rate pool (below) is non-empty in both variants.
- **Length bands** (minutes, for λ of the fragment, doze or nap piece; aligned with A's handling: < 90 ignored, 90–180
  nap): F = 25 → [15, 40), F = 60 → [40, 90), F = 100 → [90, 125), F = 140 → [125, 180).
- **Wake heart-rate pool** of night n and variant v: the heart-rate samples of that variant with t0 ≤ t < t1 lying in an
  AWAKE label interval [a, a + 30 s), grouped into blocks by the wake bout that contains them (time order within a
  block; empty blocks dropped). Block order of a construct: ascending SHA-256 hex digest of the UTF-8 string
  `"<night key>|mergegap|<design>|<bout start − t0 in ms>"` (`napmodel.night_key`: the night id without its variant
  suffix), so the order is the same for every gap g and both variants.
- **Synthetic wake stretch** [x0, x1) (x0, x1 multiples of 30 s from t0): AWAKE label intervals of 30 s tile it; no step
  record (except `walk`); heart-rate samples at τ_k = τ_1 + (k − 1) · 2 MIN while τ_k < x1 − 1 MIN, τ_1 = max(c + 2 MIN,
  x0), c = the time of the construct's last heart-rate sample before x0 (x0 − 2 MIN if none), with values by **stretch
  variant**:
  - `base` (decisive): the k-th value of the concatenated wake-pool blocks in block order, cycled from the first block
    when they are exhausted (values copied unchanged): the night's own in-night arousal heart rate.
  - `hr10` (decisive): `base` + 10 bpm (awake and active instead of lying awake).
  - `nohr` (reported, g = 45): no heart-rate sample in the stretch (the watch off the wrist or charging).
  - `presleep` (reported, g = 45): the night's own samples of [t0 − 30 MIN, t0) in time order, cycled (in-bed wake
    before sleep); a night without such samples has no construct of this variant (counted; 2.8).
  - `walk` (reported, g = 45): `base` plus one-minute step records of 40 steps every 5 min in the stretch, the first
    starting at x0 + 5 MIN, while the record ends before x1 − 5 MIN (someone who got up).
- Constructs keep the night's subject and variant; id `<night id>~mg-<design>-g<g>-<stretch variant>`. Every construct
  boundary, label and block order depends on the labels and the session boundaries only, so both variants get identical
  constructs apart from each variant's own heart-rate values and sample times.
- **Host choice.** One host per (night, design), the same for every g and every variant; a night without one has no
  construct of that design (counted, 2.8).

### 5.2 V1 designs: a stretched awakening at the end, in the middle or at the start

For gap g: D = g + 10 MIN − w (≥ 5 MIN, since w ≤ 40 MIN and g ≥ 35 MIN). Every label interval, heart-rate sample and
step record of n at or after b1 (by start or time) is shifted by +D, and [b1, b1 + D) becomes a synthetic wake stretch
(x0 = b1, x1 = b1 + D). In construct time: X1 = [t0, e1] with e1 = b0 + 5 MIN; X2 = [s2, t1 + D] with s2 = b1 + D − 5 MIN;
the inter-session gap s2 − e1 = w + D − 10 MIN = **g exactly**; the wake bout around it, [b0, b1 + D), lasts g + 10 MIN
(its first 5 min in X1, its last 5 min in X2, as the OHealth-like margins put them).

| design | host condition (λ in min) | host chosen | main region | fragment |
|---|---|---|---|---|
| END-F, F ∈ {25, 60, 100, 140} | λ2 in band F and λ1 ≥ 180 | smallest \|λ2 − F\|, then the latest b1 | X1 | X2 |
| START-F, F ∈ {25, 60, 100, 140} | λ1 in band F and λ2 ≥ 180 | smallest \|λ1 − F\|, then the earliest b0 | X2 | X1 |
| MID | λ1 ≥ 180 and λ2 ≥ 180 | smallest \|b0 − (t0 + t1) / 2\|, then the earliest b0 | X1 ∪ X2 | none |

- Gaps: **g ∈ {35, 45, 59} min** (decisive for B and C), **{75, 89} min** (reported, for R90, `base` only).
- Handling under A of the fragment piece: F = 25 or 60 → ignored; F = 100 or 140 → nap. The main piece is ≥ 180 min →
  night. MID: both pieces nights.
- What the designs stand for: END = a re-doze after the night (10 and 21 Oct; 30 Sep at F = 100), MID = a night split
  in two nights, START = an early awakening that cuts off the first part of the night (18 Oct had a 107-min first piece,
  gap 23).

### 5.3 V2a design: a doze before the night (early start)

DOZE-F, F ∈ {25, 60, 100}: host = a bout with λ1 in band F (no λ2 condition; the night stays whole, t1 − t0 ≥ 180 MIN),
chosen by smallest |λ1 − F|, then the earliest b0. For gap g: D′ = g + 5 MIN − w (≥ 0), Δ = (b1 − t0) + D′. Every label
interval lying in [t0, b1), every heart-rate sample with t0 − 30 MIN ≤ t < b1 and every step record with t0 − 30 MIN ≤
start and end ≤ b1 is copied, shifted by −Δ (the doze with the night's own pre-sleep context of 30 min before it, so the
merged session has the same kind of context as the night alone); [t0 − D′, t0) becomes a synthetic wake stretch; every
original label, sample and record at or after t0 is kept unchanged; the night's original samples and records before t0
are removed (their times now hold the copy and the stretch). In construct time: doze X0 = [t0 − Δ, b0 − Δ + 5 MIN]
(length λ1), gap = t0 − (b0 − Δ + 5 MIN) = w + D′ − 5 MIN = **g**, night = [t0, t1] unchanged; the wake between the
doze's last sleep and the night's first sleep lasts g + 10 MIN. The doze is a copy of the night's own first λ1 minutes:
the best labelled analogue of a sleep onset this data offers. Main region = the night [t0, t1); fragment = the doze.
Gaps as in 5.2. Under A the doze is ignored (F = 25, 60) or a nap (F = 100).

### 5.4 V2b design: a separate nap after the night

NAPAFTER-F, F ∈ {60, 100}: the host of DOZE-F (the same bout). For gap g: Δn = (t1 − t0) + g. Every label interval
lying in [t0, b1), every heart-rate sample with t0 ≤ t < b1 and every step record with t0 ≤ start and end ≤ b1 is
copied, shifted by +Δn (the nap, followed by the real wake bout that ended it); [t1, t1 + g) becomes a synthetic wake
stretch; the night's original samples and records after t1 are removed. In construct time: night = [t0, t1] unchanged,
gap = **g**, nap X3 = [t1 + g, b0 + 5 MIN + Δn] (length λ1); the wake between the night's last sleep and the nap's
first sleep lasts g + 10 MIN. The nap is a copy of the night's first λ1 minutes with their own heart rate: onset
architecture, which a real morning nap after a full night is less likely to show (section 13). Main region = the night
[t0, t1); fragment = the nap. Gaps as in 5.2. Under A the nap is ignored (F = 60) or a nap (F = 100); B and C merge it
at g < 60 (the night alone covers ≥ 180 min).

### 5.5 Positive control (assay sensitivity)

PC: the unmodified host night n with its session start 15 min late, [t0 + 15 MIN, t1] (13.8's late-start-15), against
the unmodified night [t0, t1], both decoded as nights by the subject's N1 fold bundle with the night's own heart rate;
compared on the chunks of [t0 + 15 MIN, t1) scored under both. One PC per (night, variant), used by every κ cell that the
night hosts (section 7).

### 5.6 Sessions, arms M, S and S′, and the grouping of every arm

- **Cut (the app's reader).** A session [s0, s1] of a construct gets the heart-rate samples with s0 − 30 MIN ≤ t ≤
  s1 + 30 MIN, the step records that overlap [s0 − 30 MIN, s1 + 30 MIN] (start < s1 + 30 MIN and end > s0 − 30 MIN; the
  app's reader returns overlapping records, while core-algo's own restriction of naps to records ending in
  (s0 − m, s1 + m] applies only with `napMode.contextMarginMin`, which is absent here), the label intervals lying in
  [s0, s1], the forced-AWAKE spans of its arm inside [s0, s1] (`forcedAwake`, docs/INTERCHANGE.md section 1), the
  night's subject and variant, and the id `<construct id>~M`, `~S1`, `~S2` or `~Snap` (S′). Unlike `napmodel.cut_window`,
  heart rate after s1 is kept: the app reads [start − 30 min, end + 30 min].
- **Arm M** (merged): one session from the first piece's start to the last piece's end, `forcedAwake` = [[e1, s2]]
  (V1), [[b0 − Δ + 5 MIN, t0]] (V2a) or [[t1, t1 + g]] (V2b); NIGHT, its span being ≥ 180 min.
- **Arm S** (split): each piece on its own: span < 90 min → not decoded (ignored, no output); 90 ≤ span < 180 min →
  decoded as a nap; ≥ 180 min → decoded as a night.
- **Arm S′** (V2b only; a staging reference, not an arm of the app): the nap piece X3 decoded on its own as a nap
  (N1, `outputPolicy` STAGES) whatever its length, so that the nap model's staging of it exists also where A ignores it.
- **Which arm each policy takes** (the consequence of the grouping rules of 3.1 for these constructs; gate G6): A → S
  always; B → M if g < 60, else S; C → M if g < 60 and K(the group before the gap) ≥ H (END, MID, NAPAFTER), else S
  (START, DOZE, g ≥ 60); R45 → M if g < 45; R90 → M if g < 90. The Python function `mergegap.group(sessions, arm)`
  takes the decision from the pieces' spans exactly as `NightCandidateBuilder` does (gate G4).

## 6. Metrics (operational definitions)

### 6.1 Scoring, regions, estimators

- **Per-epoch parser** (`mergegap.read_epochs`, new; `napsrealistic.read_epochs` drops `start_ms` and stays
  unchanged): reads `night_id`, `epoch`, `start_ms`, `label`, `stage` of grid index 0; the epoch length is
  `preprocess.epochSec` of the decode's parameters (60 s; `AlgoParams.forMode(NAP)` does not change it); the last epoch
  of a session is clipped at its session end, as algo-cli's intervals are. Vectorized: per session, `np.searchsorted`
  of the chunk midpoints in the sorted `start_ms`.
- **Chunk scoring.** A label interval [a, a + 30 s) of a decoded session's file is scored with the stage of the session
  epoch whose [start_ms, min(start_ms + epochSec, session end)) contains a + 15 s; it is unscored when its label is
  UNKNOWN, the stage is UNKNOWN or empty, or no epoch contains it — algo-cli's own scoring rule, reproduced exactly
  (gate G5a).
- **Regions** (construct time; a chunk belongs to a region when it lies in it): END main [t0, e1), fragment [s2, t1 + D);
  START fragment [t0, e1), main [s2, t1 + D); MID main [t0, e1) ∪ [s2, t1 + D); DOZE main [t0, t1), fragment [t0 − Δ,
  b0 − Δ + 5 MIN); NAPAFTER main [t0, t1), fragment X3; PC main [t0 + 15 MIN, t1). The gap belongs to no region. Under
  S a region's chunks are scored by the decode of the piece that contains them; chunks of an ignored piece have no
  prediction.
- **Forced epochs.** Under M the epochs forced AWAKE are those whose midpoint lies in a forced span (core-algo's rule,
  2.1); they must be predicted AWAKE (gate G5b). When a gap boundary lies 30 s off the session's 60-s grid, the epoch
  that straddles it is not forced and one 30-s chunk of the gap lies in it; such gap chunks belong to no region and are
  counted.
- **Estimators.** Each arm's decode is scored as the app would write it, whatever its estimator (HYBRID or RULES). A
  decode that is NOT_ESTIMATED writes nothing: when one arm's decode of a region is NOT_ESTIMATED and the other's is
  estimated, the region's labelled chunks (label not UNKNOWN) count for the NOT_ESTIMATED arm as predicted AWAKE (no
  sleep shown), so "M not estimated where S is" is a failure of that construct and lowers M's metric, not an exclusion;
  when neither is estimated, the region is excluded (counted). Estimator mismatches (HYBRID / RULES / NOT_ESTIMATED by
  arm) are reported per design, gap and variant.
- Per construct: 4 × 4 matrices (rows truth AWAKE, LIGHT, DEEP, REM) of the main region under M and S (PC: late and
  base), and of the fragment under M, under S where S decodes it, and under S′ (V2b).

### 6.2 Cells, per-subject statistics and the stratified bootstrap

- **Decisive cells** (each pools the fragment lengths of its designs and the gaps 35, 45, 59 per subject):

  | cell | designs | region, metric | margin | arms |
  |---|---|---|---|---|
  | V1a-late | END-25, END-60, END-100, END-140, MID | main, 4-class κ | −0.02 | B, C |
  | V1a-early | START-25, START-60, START-100, START-140 | main, 4-class κ | −0.02 | B |
  | V1b-late | END-100, END-140 | fragment, sleep / wake accuracy | −0.05 | B, C |
  | V1b-early | START-100, START-140 | fragment, sleep / wake accuracy | −0.05 | B |
  | V2a | DOZE-25, DOZE-60, DOZE-100 | main (the night), 4-class κ | −0.02 | B |
  | V2b-night | NAPAFTER-60, NAPAFTER-100 | main (the night), 4-class κ | −0.02 | B, C |
  | V2b-nap | NAPAFTER-60, NAPAFTER-100 | fragment (the nap), 4-class κ, M against S′ | −0.05 | B, C |

- **Per subject**, for a cell, sampling variant v, stretch variant h ∈ {base, hr10} and gap g: the matrices of the
  subject's constructs of the cell's designs, summed; metric = 4-class Cohen's κ of that matrix, or sleep / wake
  accuracy = trace of the 2 × 2 collapse (`napmodel.collapse_sleep_wake`) / total; Δ_s,g = metric(M) − metric(S) (S′ for
  V2b-nap). Δ_s = the mean of Δ_s,g over g ∈ {35, 45, 59}. A subject whose metric is undefined under either arm at any g
  is dropped from that (cell, v, h) (counted).
- **Datasets.** A dataset with fewer than 10 subjects in a (cell, v, h) is left out of it (its mean is reported); the
  others enter. **Evaluable**: at least 30 subjects enter, counted after that drop, in every (v, h).
- **Mean.** Δ̄ = the mean of Δ_s over all entering subjects (each subject one unit, whichever dataset).
- **CI.** For every (cell, v, h) separately, `numpy.random.default_rng(0)` is created anew; 10 000 resamples; in each,
  the entering Walch subjects are drawn with replacement among themselves (the 10 000 × n_W index array first) and the
  entering BIDSleep subjects among themselves (then 10 000 × n_B), and Δ̄* is the mean over all drawn subjects; 95 %
  percentile interval (`numpy.quantile` at 0.025 and 0.975). With one dataset entering it equals
  `report.paired_bootstrap` (unit-tested). Implemented as `mergegap.stratified_paired_bootstrap`.
- **Decisive value**: LB = the minimum of the lower bounds over v ∈ {instantaneous, mean2min} and h ∈ {base, hr10}.
- **Point checks** (no interval), in every (v, h): the mean change at each g; for V1a-late also the END designs' and the
  MID design's means (at the end, in the middle); every entering dataset with at least 20 subjects (with the counts of
  2.8, BIDSleep) its own mean.
- **Positive control** (κ cells V1a-late, V1a-early, V2a, V2b-night): per subject, κ of the summed PC matrices of the
  cell's host nights (each night once) late minus base; the same dataset rule, mean and bootstrap (rng anew per (cell,
  v)); **detected** iff its 95 % lower bound is below −0.02 in both variants, i.e. the same test would have refused
  non-inferiority for a 15-min late start. The V1b and V2b-nap cells have no positive control: no published harm of
  their metrics lies near their margin.
- **Outcome of a cell**: **pass** = evaluable, LB ≥ margin, every point check ≥ margin and (κ cells) the positive control
  detected; **harm shown** = Δ̄ or a point check below the margin in some (v, h); otherwise **non-inferiority not shown**
  (LB below the margin with no mean below it, not evaluable, or the positive control not detected; the reason is
  recorded).

### 6.3 Reported metrics (no decision)

- Per design (each F separately), g, dataset, variant and arm: accuracy, 4-class κ, sleep / wake κ and wake recall of the
  main region and of the fragment; for V1b also against an "all sleep" reference (every chunk predicted sleep), whose
  sleep / wake accuracy shows how close the metric is to its ceiling; the predicted DEEP and REM shares of sleep in the
  main region (M − S, paired); the fragment under M (which M writes with stages) against "all LIGHT", and against the
  nap decode of the same fragment where S decodes it (F = 100, 140) or S′ exists (V2b), so that M's 4-class staging of a
  fragment can be judged against 13.13's reference; for V2b-nap, M's accuracy minus "all LIGHT" and M's κ against
  13.13's STAGES bar (+0.03, 0.15).
- **Coverage** under each arm's grouping and the default output policies (night: stages; nap: SLEEP_WAKE, no stages;
  ignored: nothing): Cov_staged = the share of the construct's true-sleep chunks outside the gap that lie in a session
  written with stages; Cov_any = the share in any written session; E2E = the share written with the correct stage (a
  SLEEP_WAKE nap's "asleep" is not a stage). For V2a and V2b the separate sleep counted in the night is reported apart,
  as a grouping error.
- The stretch variants `nohr`, `presleep` and `walk` at g = 45; g = 75 and 89 min (R90); the R45 reading of g = 35;
  every cell per g, per position and per dataset (Walch included where it was left out); the PC per design; counts; the
  estimator mismatches; and how many constructs C would classify differently if K were the true sleep before the gap.

## 7. Gate V1: staging harm of merging (verbatim)

"Merging a fragment across a gap of 35, 45 or 59 min, with the gap forced awake, must not make the staging of the night
worse, whether the fragment comes at the end, the gap splits the night in the middle, or the fragment comes at the
start: the subject-bootstrap 95 % lower bound of the change in 4-class kappa of the night part, merged minus split,
averaged over the three gaps, is at least −0.02 in the worse sampling variant and the worse of the in-bed and +10 bpm
heart rate in the gap; no gap's, no position's and no large dataset's mean change is below −0.02; and the same test
refuses non-inferiority for a 15-min late start of the night. Where the split handling estimates the fragment as a nap
(90–180 min), merging must also keep its sleep / wake reading in the same test with the margin −0.05."

Operational definitions (they govern): the cells V1a-late, V1a-early, V1b-late and V1b-early of 6.2. **E** = V1a-late
and V1b-late pass (shared by B and C); **S_t** = V1a-early and V1b-early pass (B only; C merges no START construct, so
these cells are vacuous for C and not cells of C). Expected direction of V1b-late: positive (2.1: the continuation decode
beat the stand-alone nap decode on return-to-sleep windows of this kind); its pass is weak evidence.

## 8. Gate V2: a separate sleep merged into the night (verbatim)

"Merging a doze of 15–125 min that ended 35, 45 or 59 min before the night's session, or a nap of 40–125 min that
started 35, 45 or 59 min after it, must not make the night's staging worse, in the test of V1 with the margin −0.02 and
its positive control; and merging the nap must not stage it worse than the nap model staging it alone, in the same test
with the margin −0.05."

Operational definitions: the cells V2a (B only; C merges no DOZE construct, gate G6, so V2a holds for C by construction
and is recorded as "not applicable"), V2b-night and V2b-nap (B and C) of 6.2. **P2** = V2a passes; **N** = V2b-night and
V2b-nap pass.

## 9. Gate V3: the Sleep as Android request

### 9.1 Replays

A new test class `MergeGapGateReplayTest` (`work/src/test/kotlin/io/github/alien2003/pulsestages/work/saa/`) replays
the gate end to end with the harness of `SleepAsAndroidGateScenarioTest` (`PipelineHarness`, the simulated phone with
its runs, request work and notices, `ImportingSleepAsAndroid`, `write.startBeforeSource` on as there) under each arm
(A, B, C, R45, R90; set through `sources.mergeGapMin` and `sources.continuationGapMin`). To share the timelines, the
private top-level helpers of `SleepAsAndroidGateScenarioTest.kt` (`time`, `every`, `SimulatedWork`, `Phone`,
`heartRate`, `walk`, `eveningWalk`, `replay18Oct`, `replay19Oct`) move unchanged into an internal test file of the same
package (`SleepAsAndroidScenarioTimelines.kt`); the timelines of the tests `a sleep-in` and `hourly checks` (their
OHealth writes and `advanceTo` calls, not their assertions) move into internal helpers `replaySleepIn` (with a callback
at the point where the test asserts before the final version, as `replay18Oct`'s `beforeIntermediate`) and
`replayHourlyChecks` that those tests call; and `Phone` gets an optional schedule of extra OHealth writes (time →
callback), each run when the simulated clock reaches its time, after the runs, work and imports due at that time (as the
timelines' own writes are), so that a write can fall inside `replay19Oct`, which advances internally to 09:12. The
existing tests pass none of the new arguments.

- **V3.1 single nights** (the existing scenarios): `18 Oct` (walks off / on), `19 Oct` (walks off / on), `a sleep-in`,
  `hourly checks`. Under C, `18 Oct` crosses H between versions (3.1), so its request time shows C's threshold changing.
- **V3.2 back-to-sleep fragment**: the `19 Oct` replay (walks off; night 02:20–08:03, covered 343 min) plus an OHealth
  session [08:03 + g, 08:03 + g + 29 min], g ∈ {25, 35, 38, 45, 59} min, written at its end + d, d ∈ {1, 25} min (21 Oct:
  written 21 s after its end; the code comment: 20–27 min), with its heart rate up to its end (below).
- **V3.3 nap**: day 0 of the harness, start 13:00, runs every 15 min from 13:05; an OHealth session 14:00–15:40 written at
  15:41 with heart rate from 13:30 to 15:41 (walks off).
- **Heart rate written by the new class.** E = the end of the base timeline's last heart-rate write, made at E (09:12 in
  `19 Oct`; 13:19, 11:35 and 09:12 in `18 Oct`, `a sleep-in` and `hourly checks`). Every heart-rate write that the new
  class adds (the V3.2 fragment's or the extension's) covers [max(W, E), its end), W = the end of the latest heart rate
  written so far, and adds nothing when that is empty. So a fragment ending by 09:12 adds no heart rate (the base
  timeline's writes cover it, at their own times), a later one adds [09:12, its end) at its write time, and no sample is
  written twice.
- **Upload extension E(P)**, P ∈ {15, 60, 180} min, in every replay: U = E (the V3.2 fragment's write excluded); at
  U + kP (k = 1, 2, …) OHealth writes heart rate up to U + kP as above — in a walking scenario also the walk's step
  records over (U + (k − 1)P, U + kP] (the `walk` helper) — until 8 h after the scenario's latest session end;
  PulseStages' runs go on at the scenario's interval (15 min; 1 h in `hourly checks`) until then. (The scenarios' own
  last write comes 64–69 min after the final end, enough for a 60-min margin and not for 90.)
- Recorded per replay, to the JSON file named by the system property `pulsestages.mergegap.v3Report` (section 16):
  every request time; Sleep as Android's final records (start, end, package, version); the changed-night notices with
  their night's `clientRecordId`; PulseStages' final nights with their `clientRecordId`.
- The class carries the JUnit tag `mergegap-replay`, excluded from the default test tasks; `train.py mergegap` runs it
  with the tag included (section 16). Before the code commit it may be run for arm A only (gate G2b). Replays run before
  the decodes, never at the same time.

### 9.2 Pass rules

- **V3a (regression check; blocks adoption, not evidence), per arm X**, in every replay of X: (i) V3.1 and V3.3: Sleep
  as Android ends with PulseStages' record of the session's final version (the conditions of `assertImportedFinal`) and
  no changed-night notice was shown; (ii) V3.2: for every night PulseStages writes under X, Sleep as Android ends with
  PulseStages' record of its final version, or a notice was shown whose night has the `clientRecordId` of that final
  night (the documented fallback for a night that changes after the request); (iii) no replay throws. **R_B** = V3a(B),
  **R_C** = V3a(C). Known in advance: A passes (the existing assertions); A also passes in the case the developer
  complained about (V3.2 at g = 38: the ignored fragment leaves the cut night final), which V3a cannot see — hence V3c.
- **V3b (decisive against U1): the added delay** of arm X = the median over the six V3.1 replays at P = 60 of (the first
  request time under X − the first request time under A). Reported for every scenario, P and d.
- **U1: the developer's limit.** After the pre-registration NOTE and before the first START of `train.py mergegap` (that
  subcommand itself; `mergegap-gates` may come before), the developer is asked the question below (in their language)
  and the answer is logged once, in English, as `NOTE merge-gap U1 DMAX <minutes>: <answer>`:

  > PulseStages joins two sleep sessions that the same app wrote for one night only when the second starts less than
  > 30 min after the first ends. A later piece after a longer gap is written as a separate nap without stages (if it
  > lasts 90–180 min) or not at all (if it is shorter). A limit of 60 min would join such pieces to the night, with the
  > gap shown as awake. Because such a piece can still arrive after the night, PulseStages asks Sleep as Android to
  > import a night only once OHealth's heart rate reaches the night's end plus the limit plus 30 min: 60 min after the
  > end today, 90 min with a 60-min limit. Suppose OHealth uploads heart rate about once an hour after you wake up. By
  > how many minutes at most may the request to Sleep as Android come later than it does today? Please answer with one
  > number of minutes (0 if it must not come later).

  An answer that is not one number is asked once more; if the second answer is not one number either, D_max = 0 (a
  conditional answer counts as 0). U1 is decided before any result of this experiment exists. **U_X** = (V3b(X) ≤
  D_max).
- **V3c (reported; what the user sees)**, per arm: the share of the V3.2 replays (over g, d and P) in which Sleep as
  Android ends with a night ending at the fragment's end (the labelled truth of 21 Oct), with one ending at the first
  piece's end, or with neither, each with and without a changed-night notice.

**Expected (stated in advance).** A passes V3a. Under B and C the V3.1 requests go out about 30 / 60 / 180 min later at
P = 15 / 60 / 180 (the first extended write past the end + 90 min, give or take one run interval), so V3b(B) ≈ V3b(C) ≈
60 min and U holds roughly iff D_max ≥ 60; the nap of V3.3: B as nights, C as A. V3.2: A merges only g = 25; B and C
merge every fragment; with d = 1 it is written before heart rate can reach the end + 90 min (g + 29 + 1 ≤ 89 min), so
Sleep as Android should get the merged night; with d = 25 and g ≥ 38 it is written after the end + 90 min and may follow
the request, and then a changed-night notice is the expected path. V3a is expected to pass for B and C.

## 10. Gate V4: the developer's label (consistency check)

V4(X) holds iff arm X, applied by `mergegap.group` to the labelled pair of 2.6 (OHealth sessions 00:18–07:12 and
07:50–08:19, one package), merges it (CONTINUATION). There is no pair labelled SEPARATE. **Known in advance:** A fails V4
(38 ≥ 30), B (38 < 60), C (K = 414 ≥ 180 and 38 < 60), R45 and R90 hold. **L_B = L_C = true are constants**; any other
value is an implementation error and fails gate G6. V4 is a consistency requirement, not evidence of generalization, and
cannot tell B, C, R45 and R90 apart.

## 11. Decision rule (verbatim) and decision tree

"Adopt B (`sources.mergeGapMin` = 60 min) if every cell of V1 and V2 passes for B, the Sleep as Android regression check
V3a passes for B and B's measured added delay is within the limit the developer set before the run (U1). Otherwise
adopt C (merge gap 30 min, and 60 min after a group that already covers at least 180 min of session time) if every cell
of V1 and V2 that C merges passes, V3a passes for C and C's added delay is within the limit. Otherwise keep A (30 min).
A cell passes when the subject-bootstrap 95 % lower bound of the change, merged minus split, averaged over the gaps of
35, 45 and 59 min, is at least the margin in the worse sampling variant and the worse of the in-bed and +10 bpm heart
rate in the gap; no gap's, no position's and no large dataset's mean change is below the margin; and, for a cell of
night staging, the same test refuses non-inferiority for a 15-min late start. Margins: night 4-class kappa −0.02; sleep /
wake accuracy of a nap-length fragment −0.05; 4-class kappa of a merged separate nap against the nap model's own staging
−0.05. V4 (the labelled pair grouped as labelled) holds for B and C by construction and is checked as a gate. 45 and
90 min are reported, never adopted. A failed technical gate (G0–G6) stops the run and keeps A."

Operational definition, with E and S_t of section 7, P2 and N of section 8, R_B, R_C, U_B and U_C of section 9, and the
constants L_B = L_C = true of section 10 (a different value fails G6). G = the fatal gates G0–G6 passed; SC = E ∧ N
(every cell C merges); SB = S_t ∧ P2 (B's own cells). The cases are checked in this order; every combination of the
seven booleans G, SC, SB, R_B, R_C, U_B and U_C falls into exactly one:

| case | condition | outcome | reason recorded (generated from `summary.json`) |
|---|---|---|---|
| 0 | a fatal technical gate (G0–G6) failed | **A**, no decision | the gate and its detail |
| 1 | SC, SB, R_B, U_B | **B** | — |
| 2 | SC, not (SB ∧ R_B ∧ U_B), R_C, U_C | **C** | B's failing conditions, each cell as "harm shown" or "non-inferiority not shown (reason)", or "B's added delay V3b = … min > D_max", or "B's regression check failed: …" |
| 3 | SC, not (SB ∧ R_B ∧ U_B), not (R_C ∧ U_C) | **A** | B's and C's failing conditions, classified as in case 2 |
| 4 | not SC | **A** | the failing cells shared by B and C, classified as in case 2, and B's own |

- B before C: the order is fixed now, so the choice between them is not made after seeing which looks better. C is the
  answer to a failure of B at the start of the night or before it (S_t, P2) or to B's delay; a harm at the end, in the
  middle or with a nap after the night (not SC) rules out both.
- Every A outcome of cases 3 and 4 records next to its reason the coverage A keeps losing against B and against C
  (Cov_staged, Cov_any and E2E of 6.3, generated).
- R45 and R90 are never adopted, whatever their numbers.
- Every case is computed and written to `out/mergegap/summary.json` in one step, with every criterion value and each
  cell's outcome (pass, harm shown, non-inferiority not shown with its reason).

## 12. Why these thresholds (fixed now, with no construct known)

| threshold | reason |
|---|---|
| V1a, V2a, V2b-night: κ −0.02 | the loss of detecting the night's start 10 min late (13.8: narrow-10 −0.020; late-start-15 −0.025) and the D margin of naps-realistic: merging may cost the night no more than a 10-min error of its boundary |
| V1b: sleep / wake accuracy −0.05 | under A such a fragment reaches Health Connect as asleep / awake only (SLEEP_WAKE), so its sleep / wake reading is what merging can lose; 0.05 is about 5 min of a 100-min fragment and the looser D margin (−0.05) of naps-realistic |
| V2b-nap: κ −0.05 against S′ | under B or C a separate nap merged into the night is written with stages, which 13.13 withheld from naps (SLEEP_WAKE); the merge must not stage it worse than the nap model would; −0.05 is naps-realistic's looser margin, the nap κ being noisier (13.12: per-subject SD about 0.11) |
| pooling F and g per subject | the per-F, per-g cells of the draft would have had 1–8 Walch and 16–33 BIDSleep subjects and almost no chance to pass without harm (section 15); the point checks per g and per position keep a harm confined to one gap or one position from being averaged away |
| each subject one unit, stratified bootstrap | Walch has one night per subject and 7–17 subjects per cell; half the weight on so few subjects would make the interval too wide to show anything; stratification keeps each dataset's sample size fixed |
| a dataset enters with ≥ 10 subjects; evaluable with ≥ 30 | a percentile bootstrap of fewer than 10 subjects covers less than its nominal 95 %; below 30 subjects a cell would pass without harm with a probability under about 0.8 at the planning SD; every decisive cell has 39–64 (2.8) |
| dataset mean condition only with ≥ 20 subjects | a 7–17-subject Walch mean misses −0.02 by chance in up to about one cell in ten without any harm; with ≥ 20 subjects (BIDSleep) the condition costs almost nothing and still catches a harm confined to one dataset |
| worse of `base` and `hr10` | an OHealth split is more than 20 min without sleep data (2.5): in-bed arousal heart rate is the case closest to no split at all, so the decisive value also covers an awake, active heart rate; watch-off (`nohr`) and walking are reported (section 22) |
| positive control: late-start-15, LB < −0.02 in both variants | a non-inferiority pass is evidence only if the same cell would have refused a harm of the margin's size; 13.8's late-start-15 (−0.025 / −0.023) is the published harm closest to the margin |
| 95 %, 10 000 resamples, seed 0 | the repository's convention |
| g = 35, 45, 59 | the range that B (and C) merge and A does not, with its smallest observed value (35–38 on the development phone) and its largest (59) |
| F bands 25 / 60 / 100 / 140 (and 25 / 60 / 100, 60 / 100) | an ignored re-doze (21 and 10 Oct), an ignored longer piece, a nap piece (30 Sep, 103 min), a nap piece just below the night threshold; the bands follow A's handling exactly (< 90 ignored, 90–180 nap) |
| host bouts 2–40 min | 2 min: naps-realistic's return-to-sleep bout; 40 min keeps D ≥ 5 min at every decisive g (the stretch reaches into both session margins) |
| U1 as a number, V3b at P = 60 | the wait is a preference; asking for a number at a stated upload pattern turns it into a fixed pass rule that the replays measure |

**Coverage is never traded against staging.** The gates penalize only harm to the staging of scored chunks. What A
loses — re-doze fragments never written, a 1 h 43 min nap written without stages — counts only as coverage (6.3), which
is reported and decides nothing. A κ change of −0.025 in one cell keeps A even if Cov_staged rises by 10 points, and a
V2a failure rejects B on a case the developer's data never showed (no session ended within 465 min before a night). This
bias towards the status quo is accepted in advance; every A outcome states its coverage cost (section 11).

## 13. Expected outcome, known inputs and the direction of construction biases (stated in advance)

- **Known or near-certain inputs** (not evidence): V4 (A fails, B and C hold); V3a (a regression check, expected to pass
  for A, B and C); C's START and DOZE cells (vacuous, not cells of C); V1b-late (expected positive from 2.1). The
  decision rests on V1a, V2a, V2b, the positive controls and U.
- **V3b and U:** about +30 min with frequent uploads and about one OHealth upload interval otherwise; at hourly uploads
  about +60 min, so B and C need D_max of about 60 or more. The developer had been told "about 105 min instead of about
  75" (2.6), which corresponds to +30 min.
- **V1a-late:** the gap is forced AWAKE and the night keeps its onset; its normalization shifts by at most about P54–P58
  at these gaps (the upper bound of 2.1); under END the night's `hoursToEnd` grows by g + F, under MID S decodes two
  half-nights and M one night with a hole. A small harm is plausible; whether it exceeds 0.02 κ is unknown.
- **V1a-early and V2a:** the onset-sensitive model (13.8: a start 10–20 min late together with an end as early cost
  0.012–0.042 κ, a 15-min late start alone about 0.02; a start 10–20 min earlier with wake only +0.009 to +0.018) sees a
  block of sleep, then 35–59 min of forced wake, then the night: the session starts about 60–220 min before the night's
  main onset. A failure of S_t or P2 is plausible; that is the case C exists for.
- **V2b:** the night's end moves later by g + λ1 and the nap is decoded with late-night session-time features; the nap
  copy has onset architecture (DEEP-rich), which the nap model is built to decode at a session start. A failure of
  V2b-nap is plausible and would reject B and C alike.
- **Direction of the construction biases** (none is corrected; each is reported where it can be):
  - The decisive stretch (`base`, `hr10`) holds heart rate with no steps and no missing data, close to in-bed wake that
    OHealth would not have split (2.5). Its effect on M − S has no known sign; `nohr` and `walk` are reported, and the
    claims are restricted accordingly (section 18).
  - After the stretch the labels are the night's unchanged continuation after a short awakening. In MID and START this
    favours M: S decodes X2 alone with onset priors against labels that continue the night. It pushes V1a-late (MID)
    and V1a-early towards passing.
  - In DOZE the night keeps its fresh-night architecture as if no doze had happened, which is what S assumes. It favours
    S and pushes V2a towards failing.
  - In NAPAFTER the nap is onset-like, which S′ (the nap model) assumes and M's late-night features do not; a real nap
    after a full night is lighter. It favours S′ and pushes V2b-nap towards failing.
  - END changes nothing in the main region's labels; its bias is the continuation after a short awakening (sleep after a
    long awakening may restart with N1 / N2).
- **Most likely outcomes:** C (B fails at the start or before the night, the shared cells pass) or A (a shared cell fails,
  D_max below the measured delay, or non-inferiority not shown); B needs the night model to tolerate a session that
  starts 60–220 min before the night's onset and D_max ≥ about 60. Section 15 gives the rule's chances under assumed
  SDs. Every outcome is accepted in advance.

## 14. Technical gates

Any failure of a gate marked fatal stops the run; it is documented and A is kept. Gates G1, G2 and G4 reproduce known
results or check code on synthetic data; they run after this text is committed and before the code commit
(`train.py mergegap-gates`), with the code that is then committed unchanged, and are repeated by the full run.

| gate | check | when | on failure |
|---|---|---|---|
| **G0 provenance** | `meta.code` (SHA-256 of `train.py` and every `pulsetrain/*.py`, git commit, modified files) and the algo-cli fingerprint (launcher + `lib/*.jar`) recorded; `PREREG_SHA256` equals this file; for `train.py mergegap`: exactly one `NOTE merge-gap U1 DMAX …` line, after the pre-registration NOTE and before the first START of `train.py mergegap` (that subcommand itself, not `mergegap-gates`); the run refuses a build other than the one recorded at its start | every run | fatal |
| **G1 model reproduced** | `napsrealistic.gate_g1`, unchanged: the N1 fold bundles and parameters reproduce every stored N1 decode of `nap-model/results.jsonl` (3708) and candidate B of `bias-base/bias` on all 568 nights | before the code commit; repeated | fatal |
| **G2 build neutral** | (a) `git diff --quiet e7caaf4 -- core-algo algo-cli app/src/main/assets/models` (no change to `:core-algo`, `:algo-cli`, the bundle or `TrainedDefaults`); with the build of the working tree, the whole-night decodes of all 568 night-variants with the shipped bundle and the default parameters equal the base references saved in phase A (per night: confusion, warnings, estimator, status; per-epoch CSV byte for byte); `./gradlew test` passes with every existing test method and assertion unchanged (helpers only moved, made internal or given a parameter with a default, section 9.1); (b) `MergeGapGateReplayTest` under arm A, without the extension, reproduces the request times, imports and notices asserted by the existing scenario tests | before the code commit; repeated | fatal |
| **G3 constructs** | the app-like cut of every unmodified night [t0, t1] equals its night file; per construct: every original label, sample and record present (shifted exactly when 5.2–5.4 say; DOZE: the 30 min before t0 copied before the doze), synthetic labels and samples only in the stretch (values as the stretch variant says), inter-session gap exactly g, piece lengths within the design's band, both variants with identical boundaries, labels and block order, unique ids, `forcedAwake` as 5.6; PC sessions as 5.5; every session file re-read byte for byte | full run | fatal |
| **G4 grouping parity** | `mergegap.group` and `NightCandidateBuilder` give identical groups, spans, forced-AWAKE spans, modes and ignored groups for every case of the committed fixture `tools/train/tests/data/mergegap-grouping.json` under every arm (gaps 0–90 min around every threshold in ms, covered times around H including K exactly at `maxSessionMs` and 1 ms below, a group that is a night by span with K < H (80 + 25 + 80 min), overlapping sessions whose K is a union, `napMode.maxSessionMin` = 0, chains of three, spans around 90 and 180 min, the labelled pair); and `SourceSettlement`'s per-session margins equal the fixture's threshold(K) + 30 min: `test_mergegap.py` and two data-hc tests read the same file | before the code commit; repeated | fatal |
| **G5 scoring parity** | (a) for every decoded session, the chunk scoring of 6.1 from the per-epoch CSV reproduces algo-cli's per-session confusion matrix exactly; (b) under M every session epoch whose midpoint lies in a forced-AWAKE span is predicted AWAKE; the gap chunks in a straddling epoch are counted | full run | fatal |
| **G6 arm map** | `mergegap.group` assigns every construct as 5.6 states (B merges g < 60; C merges END, MID and NAPAFTER at g < 60 and no START or DOZE construct; A none; R45 g < 45; R90 g < 90); V4 gives L_A = false and L_B = L_C = L_R45 = L_R90 = true | full run | fatal |
| **G7 counts** | hosts and constructs per design, dataset and variant (compared with 2.8; a difference is reported with its reason); nights without a host or a wake pool; subjects per cell entering and left out; excluded and failed constructs by reason; boundary gap chunks | full run | reported |
| **G8 determinism** | fixed seeds and digest orders; the decode blocks of section 4 depend on the ids only (unit-tested with different `--jobs`) | by construction | reported |

Phase A (after the pre-registration commit, before any Kotlin edit of this experiment, with the code of `e7caaf4`; the
pre-registration commit changes only `docs/prereg/`): `./gradlew :algo-cli:installDist`; the base references of G2a
(`out/mergegap-base/ref/nights/<variant>.json` and `.per-epoch.csv`, decoded with the shipped bundle and the default
parameters); a `NOTE merge-gap base build fingerprint <sha256>` line. The fingerprint is reported, not compared: a
recompile of unchanged sources may change the jar bytes; G2a compares decodes, not jar bytes, so a `clean` does no harm.

## 15. Multiplicity and operating characteristics

B must pass 7 decisive cells (V1a-late, V1a-early, V1b-late, V1b-early, V2a, V2b-night, V2b-nap), C 4 (V1a-late,
V1b-late, V2b-night, V2b-nap, all of them among B's); every cell must pass (an intersection–union test), so no interval
is adjusted. B and C are tested in a fixed order (B first; C only if B fails), each with one-sided 2.5 % per cell; C's
cells are B's. One seed; the constructs are deterministic.

**Operating characteristics (computed for this file; synthetic, planning only).** Per-subject changes were drawn as
Normal(μ, (r · margin · m_s)²) with r = SD / margin from 13.8's intervals (wide-20 0.033 → r = 1.65; narrow-10 0.046 →
2.3, the planning value; late-start-15 0.061 → 3.05; for the −0.05 cells the same r, which 13.12's nap SDs of 0.085–0.12
support), m_s = √(ρ_w + (1 − ρ_w) / k_s), k_s = the subject's constructs in the cell (2.8; ρ_w = 0.5 planning, 1 = more
constructs bring nothing), correlations of a subject's changes 0.3 between cells, 0.8 between gaps, 0.9 between
sampling variants and 0.85 between `base` and `hr10`, the rule of sections 6.2 and 11 with a normal approximation of the
interval, the positive control at 13.8's −0.025 with r = 3.05, and V3, U and V4 met. 4000 runs for the first four rows,
2000 for the others, seed 1.

| assumption | P(adopt B) | P(adopt C) | P(keep A) | per-cell pass probability |
|---|---|---|---|---|
| no harm, r = 2.3, ρ_w = 0.5 (planning) | 0.68 | 0.08 | 0.24 | V1a-late 0.98, V1a-early 0.98, V1b-late 0.84, V1b-early 0.89, V2a 0.96, V2b-night 0.94, V2b-nap 0.96 |
| no harm, r = 1.65, ρ_w = 0.5 / 1 | 0.97 / 0.91 | 0.01 / 0.03 | 0.02 / 0.06 | 0.96–1.00 |
| no harm, r = 2.3, ρ_w = 1 | 0.40 | 0.15 | 0.45 | 0.72–0.92 |
| no harm, r = 3.05, ρ_w = 0.5 / 1 | 0.20 / 0.08 | 0.12 / 0.10 | 0.68 / 0.83 | 0.43–0.85 |
| harm = the margin in every cell (planning) | 0.000 | 0.000 | 1.000 | — |
| harm = the margin in B's own cells only | 0.000 | 0.77 | 0.23 | — |
| harm = half the margin in every cell | 0.015 | 0.03 | 0.96 | — |
| harm of 3 × the margin at g = 59 only | 0.000 | 0.000 | 1.000 | — |
| no harm, positive-control effect −0.015 / −0.010 instead of −0.025 | 0.53 / 0.32 | 0.08 / 0.07 | 0.39 / 0.62 | positive control missed in some cell: 0.24 / 0.54 (0.01 at −0.025) |
| no harm, decisive worse of three stretch variants (with `nohr`) | 0.61 | 0.11 | 0.28 | V1b-late 0.82 |
| the earlier draft's rule (per-F, per-g cells, ½ / ½ weights, ±10-min host windows; 1000 runs), no harm, r = 2.3 / 1.65 | 0.000 / 0.006 | 0.000 / 0.026 | 1.000 / 0.97 | 0.33–0.64 / ≥ 0.59 |

The V1b cells are the weakest (Walch left out, 39 and 41 subjects); their planning r is probably pessimistic, because
the sleep / wake accuracy of a fragment that is about 90 % sleep varies little (the reason 6.3 reports the "all sleep"
reference). With no harm, the rule keeps A about one time in four at the planning values and most of the time at
r = 3.05; a harm of the margin's size is practically never adopted. A kept A therefore means "non-inferiority not shown"
unless a cell shows harm; the two are kept apart in `summary.json`, the reasons and section 18.

## 16. Implementation (the code commit, before the first run of the experiment)

- **data-local.** `SourceSettings.continuationGapMin: Int = 0` (0 = off; arm C sets 60), validated 0–240
  (`SettingsLimits.continuationGapMin`), listed in the settings screen under Sources with English and Russian strings,
  and in the generated docs/settings-reference.md. No default changes.
- **data-hc.** `NightCandidateBuilder.merge` (and the merge of sessions with stages) applies C's rule of 3.1 when
  `continuationGapMin` > `mergeGapMin`, with H as 3.1 defines it (K and H in whole ms); with `continuationGapMin` = 0 its
  result is identical to today's. A new internal function `NightCandidateBuilder.continuationThresholds(sessions,
  sources, overwritePolicy, algo): Map<String, Long>` returns, per session uuid, the gap (ms) below which a later
  session would join that session's group, with the same allowlist, HAS_STAGES partition and per-package chaining as
  `build`; `NightWindowReader` exposes it (`continuationThresholds(sessions, settings)`, passing `settings.sources`,
  `settings.write.overwritePolicy` and `settings.algo`), since its builder is private. `SourceSettlement.dataAfterEnd`
  calls it on the sessions it read and gives each session its own margin (threshold + `DATA_MARGIN_EXTRA_MS`; a session
  missing from the map: `mergeGapMin` + 30 min) in `holdOf`; `writesFineGrainedSteps` uses the largest margin;
  `dataMarginMs(settings)` becomes max(`mergeGapMin`, `continuationGapMin`) + 30 min (used by `longestWaitMs`). With
  `continuationGapMin` = 0 every margin equals today's. Tests: `MergeGapGroupingFixtureTest` (the G4 fixture against
  `build`) and `SourceSettlementMergeGapTest` (its per-session margins equal threshold(K) + 30 min on the fixture).
- **work.** `SleepAsAndroidSyncRequests.longestWaitMs` uses `dataMarginMs` as above; `MergeGapGateReplayTest`
  (section 9; tag `mergegap-replay`; writes its JSON report to the path of the system property
  `pulsestages.mergegap.v3Report`); the helpers of `SleepAsAndroidGateScenarioTest.kt` moved to
  `SleepAsAndroidScenarioTimelines.kt`, the two timelines extracted and the write schedule added (section 9.1), with
  every existing test method and assertion unchanged.
- **Build files.** `work/build.gradle.kts`: on the unit-test tasks, the android-junit5 filters exclude the tag
  `mergegap-replay` unless the Gradle property `pulsestages.mergegapReplay` is set, in which case only that tag runs;
  the system property `pulsestages.mergegap.v3Report` = the property `pulsestages.mergegapReport` or
  `work/build/reports/mergegap-v3.json`. `data-hc/build.gradle.kts`: on the unit-test tasks,
  `inputs.file("../tools/train/tests/data/mergegap-grouping.json")` (relative path sensitivity) and the system property
  `pulsestages.mergegapGroupingFixture` with its absolute path, as `core-algo/build.gradle.kts` does for the bundle.
  Task names: V3 = `:work:testDebugUnitTest --tests '*MergeGapGateReplayTest' -Ppulsestages.mergegapReplay=true
  --rerun`, results in `work/build/test-results/testDebugUnitTest/`; G4 = `:data-hc:testDebugUnitTest --tests
  '*MergeGapGroupingFixtureTest' --tests '*SourceSettlementMergeGapTest' --rerun`, results in
  `data-hc/build/test-results/testDebugUnitTest/`.
- **tools/train.** `pulsetrain/mergegap.py` (constructs, cut, grouping of every arm, decodes in id-sorted blocks,
  per-epoch parser, scoring, bootstrap, cells, gates, V1–V4, the rule, report; `PREREG_PATH`, `PREREG_SHA256`;
  `jvm_test_record(task, classes, results_dir, gradle_args)`, a generalization of `napsrealistic.jvm_test_record`, which
  stays unchanged); `train.py mergegap-base | mergegap-gates | mergegap | mergegap-report`; `tests/test_mergegap.py`
  (synthetic nights only: constructs and their worked examples, among them a construct whose s2 lies 30 s off the
  session grid (G5b), the DOZE context copy, NAPAFTER, the PC cut, G3 invariants, the fixture of G4, the parser against a
  fake per-epoch CSV with a clipped last epoch, the stratified bootstrap against `report.paired_bootstrap`, the dataset
  and evaluability rules, the estimator rule, the decode blocks under different `--jobs`, every case of the decision
  table); `tests/test_prereg.py`: the digest in this file, the `.sha256` file, the log and the module agree; the logged
  text is this file verbatim; the log is append-only in time order; the pre-registration NOTE precedes every START of a
  `train.py mergegap*` subcommand; exactly one U1 NOTE between it and the first START of `train.py mergegap` (that
  subcommand itself).
- No change to `:core-algo`, `:algo-cli`, the bundle or `TrainedDefaults` (G2a); the experiment decodes with the build
  of the code commit.

## 17. Installation and verification (only after an adoption; a separate commit)

- **B:** `SourceSettings.mergeGapMin` = 60. **C:** `SourceSettings.continuationGapMin` = 60. The settings store keeps
  only values that differ from the defaults, so every install whose user never changed the field follows the new
  default; a value the user set stays.
- Texts that state the default or its consequences: ALGORITHM.md section 9 (source selection), the user guide (which
  sessions are estimated; the Sleep as Android advice "75 minutes or more after you wake up" → about 105 min; the
  troubleshooting rows), docs/settings-reference.md, docs/health-connect.md (the data margin), PLAN.md, the KDoc of
  `DATA_MARGIN_EXTRA_MS` ("60 min by default"), CHANGELOG. The release notes say that written nights regroup only when
  processed again (Recompute) and that Sleep as Android keeps what it imported.
- Tests updated to the new default and the V3 replays' recorded times (`NightCandidateBuilderTest`,
  `SleepAsAndroidGateScenarioTest`, `SourceSettlementTest`); then `./gradlew test ktlintCheck detekt` and
  `python -I -m pytest tools/train/tests -q` pass. No algorithm change: the bundle, `TrainedDefaults` and the core-algo
  and algo-cli sources are unchanged (checked).
- ALGORITHM.md section 13.14 reports the protocol, every gate, every cell and the decision, whatever it is.

## 18. What may be claimed afterwards

Every claim names the evidence: synthetic awakenings, dozes and naps placed in labelled open-dataset nights (Walch,
BIDSleep), with the night's own in-night wake heart rate or that heart rate + 10 bpm in the gap, decoded by the model in
effect at fold level; the Sleep as Android replays; one label of one person; one developer's delay limit. Reasons for
keeping A are generated from the failing criteria, never written as a fixed sentence, and each failing cell is named as
"harm shown" (a mean below the margin) or "non-inferiority not shown" (the interval too wide, too few subjects, or the
positive control missed). Every claim about B or C is limited to a gap with in-bed wake heart rate or that heart rate
+ 10 bpm: **not tested for a gap spent up and walking, nor for a gap without heart rate (watch off)** (reported only).

| outcome | may claim | may not claim |
|---|---|---|
| B adopted | "Fragments of one night from one app less than 60 min apart are merged, the gap shown as awake. On labelled nights with a synthetic awakening of 35–59 min at the start, in the middle or at the end, with in-bed or raised heart rate in the gap, merging changed the night's 4-class κ by Δ̄ [CI] (pre-registered bound −0.02; the same test refused a 15-min late start) and kept the sleep / wake reading of nap-length fragments; a doze before or a nap after the night merged into it changed the night's κ by … and the nap's κ by … Sleep as Android is asked once heart rate reaches 90 min after the night's end (V3b: … min later at hourly uploads, within the developer's limit of … min)." | that 60 min is physiologically right; that an awakening shorter than 60 min is always part of the night; that an OHealth split means lying awake; validity for a gap spent walking or without heart rate; validity for adults over 60 (their within-night gaps reach 146–175 min) |
| C adopted | "After a session that covers at least 3 h, a fragment starting within 60 min is merged into the night; otherwise within 30 min, as before (B was not adopted because …: generated). On labelled nights merging such a fragment at the end or in the middle, or a nap starting 35–59 min after the night, changed the night's κ by …" | that C's 3 h are 3 h of sleep (it is session time); anything about early-night awakenings, which C still splits; validity for a gap spent walking or without heart rate |
| A kept (cases 3, 4) | "30 min stays the default because …" (generated, each cell as harm shown or non-inferiority not shown, or the delay above the developer's limit), with the generated coverage cost: "under A, … % of the true sleep of these constructs is written with stages, against … % under B and … % under C (Cov_any …, E2E …)"; users whose watch splits nights at longer awakenings can raise "Merge gap", at the cost of a later Sleep as Android request | that 30 min is validated; that merging harms staging unless a cell showed harm; that the developer's labelled night of 21 Oct is handled correctly (it is split) |
| stopped by a gate (case 0) | the gate and its detail | any result of V1–V4 |

## 19. Commands, compute and disk

```
# after the pre-registration commit (this file and its digest) and its PRE-REGISTRATION and digest NOTE lines, before
# any Kotlin edit of this experiment (phase A: the base build and the base references of G2a; no construct)
mise exec -- ./gradlew :algo-cli:installDist
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py mergegap-base --out-run mergegap-base --jobs 10
# the implementation; then
mise exec -- ./gradlew test ktlintCheck detekt :algo-cli:installDist
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py mergegap-gates --out-run mergegap-gates --jobs 10
# U1 asked and logged (NOTE merge-gap U1 DMAX ...); the code commit (the code, the fixture, the tests, the log); the build
mise exec -- ./gradlew :algo-cli:installDist
# the experiment: G0-G8, V3 replays (Gradle, first), then V1, V2, PC (decodes), V4, the rule
JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py mergegap --out-run mergegap --jobs 10
mise exec -- python -I tools/train/train.py mergegap-report --out-run mergegap      # re-render, no algo-cli
mise exec -- python -I -m pytest tools/train/tests -q
```

Expected cost on the 24-core machine (estimates, not limits). Constructs: 917 hosting (night, design) pairs per variant
(2.8) × (3 gaps × 2 decisive stretch variants + 3 reported variants at g = 45 + 2 R90 gaps) ≈ 10 100 per variant, about
20 200 in all, with M, S (one or two pieces) and, for NAPAFTER, S′: about 6 × 10⁴ decodes, plus about 1 000 PC decodes
and G1. Decoding is cheap (the algo-cli calls of naps-realistic's gate run took 1–6 s each): about 10–20 min at 10
calls. The Python side dominates: about 2 GB of session JSON (about 34 KB each) and about 3 GB of per-epoch CSV written
and read over the run, and about 4 × 10⁷ label chunks scored, so the scoring is vectorized (6.1) and the CSVs are parsed
per call and deleted when the call's matrices are written. Transient disk peak: at most 10 calls × 1000 sessions ×
(session JSON + per-epoch CSV) ≈ 1–1.5 GB; the run directory stays below 1 GB. Gradle: the full `./gradlew test` of G2a
(about 1 100 JVM tests, Robolectric included) twice, G4's data-hc tests and 255 V3 replays (5 arms × 17 replays × 3
upload patterns): about 10–30 min. In all about 45–120 min. `--jobs` changes no result.

## 20. Order of steps, outputs, provenance, deviations

- **Order.** (1) The pre-registration commit: this file and `prereg-merge-gap.md.sha256`, on their own; the
  `PRE-REGISTRATION merge-gap` block (this text verbatim) and `NOTE prereg-merge-gap.md sha256 <digest>` appended to
  `tools/train/out/commands.log` at that time. (2) Phase A (section 14) with the code of `e7caaf4`. (3) The
  implementation; `train.py mergegap-gates` (G1, G2, G4) with the code that is then committed. (4) U1 asked and logged
  (at the latest before step 6). (5) The code commit: the analysis code (with `mergegap.PREREG_SHA256`), the Kotlin and
  build changes and tests of section 16, the fixture, `tests/test_prereg.py`, and the log so far as
  `docs/prereg/commands-merge-gap.log`. (6) `train.py mergegap` and its RESULT line. (7) The result commit (the log,
  ALGORITHM.md 13.14). (8) Only after an adoption, the installation commit of section 17 and its verification.
- **Outputs.** `mergegap-base`: `out/mergegap-base/ref/nights/` and its NOTE. `mergegap-gates`:
  `out/mergegap-gates/gates.json` and a RESULT line. `mergegap`: `out/mergegap/`: `gates.json`; `constructs/` (hosts,
  counts, construct digests; session files are deleted after decoding, their digests kept); `regions.jsonl` (per
  construct, arm and region: matrices, estimator, status, warnings); `v3/` (the replay JSON and its digest, the Gradle
  record); `cells.json` (every cell with subjects entering and left out, means, point checks, CI, positive control,
  outcome); `summary.json` (V1–V4, U1, the case of section 11 and its generated reasons and coverage costs); `report.md`;
  `algocli.log`; `run.log`.
- **Provenance.** Every run records the algo-cli fingerprint (and fails if the build changes during it), `meta.code`,
  this file's SHA-256, the U1 line and the digest of the replay JSON. Every START / END / NOTE / BUILD / RESULT line goes
  to `tools/train/out/commands.log`, committed as `docs/prereg/commands-merge-gap.log`.
- **Amendments.** A change of this text after the pre-registration commit is an amendment: allowed only before the code
  commit, only to make an implementation detail that cannot work as written workable (never an arm, design, gap,
  metric, margin, gate or rule), logged verbatim with its reason and new digest, kept in the git history and disclosed in
  ALGORITHM.md 13.14.
- **Follow-ups.** The V1 and V2 cells of this run are computed and reported whatever the outcome, also when the delay
  (U) decides. A later pre-registration that builds on this question — a Sleep as Android margin decoupled from the
  merge gap (2.7), a walking veto (D) or another gap — must list every V1 and V2 cell of this run as known and must use
  this file's margins unchanged (a margin chosen after seeing these cells is not allowed). D is never part of this
  decision.
- **Deviations.** Any change of the analysis code after the code commit is logged with its reason and disclosed. A change
  that touches a construct, a metric, a gate or the rule requires running the experiment again from the start and is a
  deviation: the decision of the run before it (if it reached one) is published next to the new one, with both runs'
  gates. A fix of an implementation error found by a gate is a deviation too unless the gate ran before the code commit.
- **Every run is reported**, including runs stopped by a gate, with the gates it reached and any decision it computed.

## 21. Limitations stated in advance

- **Synthetic gaps.** No labelled night with heart rate contains an awakening of 35–59 min between two OHealth
  sessions; V1 stretches real awakenings of 2–40 min, and V2 copies the night's own onset before or after it. The
  decisive gap holds the night's own in-night wake heart rate or that + 10 bpm, with no steps and no missing data; a
  gap spent walking or without heart rate is reported only. What follows the stretched awakening is the night's real
  continuation after a short awakening, not necessarily what follows a long one (sleep after a long awakening may
  restart with N1 / N2). The directions of these biases are stated in section 13.
- **Session boundaries.** The 5-min margins of the OHealth-like sessions and of the split pieces are an assumption (as in
  every earlier experiment); how OHealth delimits sessions around a gap is unverified. An OHealth split means no sleep
  data for more than 20 min, whatever the person did; the label of 2.6 does not say what the developer did during the
  38 min.
- **The doze and the nap** of V2 are copies of the night's own first minutes; a real evening doze happens earlier in the
  circadian day, with a higher heart rate, and a real nap after a full night is lighter than a sleep onset.
- **Population.** Walch adults of 29 ± 9 y in the lab, BIDSleep healthy adults at home (ages not published, headband
  labels); Walch has few hosts (7–17 subjects per cell) and is left out of the V1b cells, so BIDSleep carries most of the
  weight. Sleep-EDF shows that adults of 60 and over have within-night gaps up to 146–175 min, so any duration-only rule
  splits some of their nights. Every arm is duration-only; physiology supports none of them (2.5).
- **One person's label, history and delay limit**; the history cannot discriminate between B, C, 45 and 90 min.
- **V3** uses the scenario harness's synthetic timelines with assumed upload patterns, not the phone; real delays depend
  on when the user opens OHealth.
- **Conservative rule.** Coverage is not traded against staging, non-inferiority must be shown with a positive control,
  and a cell that cannot show it counts as a failure: at the planning SDs the rule keeps A about one time in four even
  without harm (section 15).
- **Model in effect only.** The decision is for the night model and N1 as they are; a later model change does not
  re-open it, and a later model is not tested here.

## 22. Considered and not adopted

| option | why not |
|---|---|
| `nohr` (watch off) as a third decisive stretch variant | it would lower the chance of adopting B without harm from 0.68 to 0.61 at the planning values (V1b-late 0.82, section 15); reported at g = 45, and the claims exclude a gap without heart rate (section 18) |
| `presleep` (pre-sleep in-bed heart rate) as the decisive stretch | 11–13 BIDSleep nights have no pre-sleep samples (2.8); `base` uses heart rate of the same night and the same awakenings as the labels, and `hr10` covers a higher heart rate; reported at g = 45 |
| a mean condition for every dataset with ≥ 5 or ≥ 10 subjects (Walch "must not contradict") | with 7–17 Walch subjects it fails up to about one cell in ten without harm; applied with ≥ 20 subjects, Walch's means are reported |
| BCa or studentized intervals for small strata | a dataset with fewer than 10 subjects is left out of the cell instead, so no stratum is that small |
| a positive control as a fatal technical gate | a missed control is a weakness of the evidence for that cell, not a failure of the code; it makes the cell "non-inferiority not shown", which keeps A or leads to C through the decision tree |
| a yes / no U1 | a numeric limit at a stated upload pattern needs no sorting of the answer after the fact and makes V3b a fixed pass rule |
| every qualifying bout of a night as a host | constructs from the same night share most of their main region, so they add little information and weigh nights with many bouts more |
| BIDSleep as the only decisive dataset | the subject-weighted pooled mean keeps Walch's PSG nights in every cell where they reach 10 subjects at a small cost in precision |

## Appendix A. The development phone's OHealth session pairs less than 4 h apart (29 Sep–21 Oct 2026)

Local times; "session" = from start to end, in-session wake included; G = merge gap; ignored = shorter than
`minSessionMin` (90 min), nothing written. The verdicts are the research's inferences from position, except 21 Oct
(labelled, 2.6).

| date | before the gap | gap (min) | after the gap | session before | at G = 30 | at G = 39–126 (45, 60, 90, 120) and under C | verdict |
|---|---|---|---|---|---|---|---|
| 30 Sep | 02:37–05:56 | 36 | 06:32–08:15 | 3 h 19 min | night 3 h 19 + nap 1 h 43 | night 02:37–08:15 | continuation (inference) |
| 2 Oct | 03:20–08:19 | 195 | 11:34–14:22 | 4 h 59 min | night + nap | night + nap | separate nap (inference) |
| 3 Oct | 03:17–08:59 | 27 | 09:26–12:55 | 5 h 42 min | one night 9 h 38 | same | continuation (inference) |
| 6 Oct | 00:55–08:10 | 126 | 10:16–11:30 | 7 h 15 min | night + ignored | night + ignored | separate nap (inference) |
| 9 Oct (a) | 01:09–03:36 | 23 | 03:59–08:00 | 2 h 27 min | one night 01:09–08:50 | same | continuation (inference) |
| 9 Oct (b) | 03:59–08:00 | 23 | 08:23–08:50 | (6 h 28 min in the group) | same night | same | continuation (inference) |
| 10 Oct | 03:15–10:23 | 35 | 10:58–11:21 | 7 h 08 min | night + ignored | night 03:15–11:21 | continuation (inference) |
| 16 Oct | 01:13–08:00 | 21 | 08:21–08:46 | 6 h 47 min | one night | same | continuation (inference) |
| 18 Oct | 04:21–06:08 | 23 | 06:31–12:15 | 1 h 47 min | one night | same | continuation (inference) |
| 21 Oct | 00:18–07:12 | 38 | 07:50–08:19 | 6 h 54 min | night + ignored | night 00:18–08:19 | **continuation (labelled)** |

With no merging at all (G = 0), 3 Oct would be two nights, 9 Oct a 2 h 27 min nap plus a 4 h 01 min night plus an ignored
piece, and 18 Oct a 1 h 47 min nap plus a 5 h 44 min night.

## Appendix B. Sources

As cited by the research notes (not re-read for this file; listed for orientation, no gate, margin or rule of this
file comes from them): Carney et al. 2012, the Consensus Sleep Diary (PMC3250369); Lo et al. 2002 (wake-bout power
law); Klerman et al. 2013 (PMC3469724); Ohayon 2010; Sundelin et al. 2023; Lichstein et al. 2003; NSRR ANSWERS; van
Hees et al. 2018 (PMC6113241; GGIR HDCZA and its configurations); the Fitbit Web API "Get Sleep Log" (dev.fitbit.com);
Kemp et al., IEEE-BME 47(9):1185–1194 (2000) (Sleep-EDF Expanded, PhysioNet, ODC-By 1.0). The open training datasets
(Walch, BIDSleep) as in tools/train/DATASETS.md.
