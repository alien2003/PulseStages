"""Pre-registered merge-gap experiment (``docs/prereg/prereg-merge-gap.md``, :data:`PREREG_SHA256`): one night or two
when the watch splits sleep at an awakening of 30-59 min.

Arms (section 3.1): A = ``sources.mergeGapMin`` 30 min (the control, kept unless B or C is adopted), B = 60 min
(primary), C = 30 min, and 60 min (``sources.continuationGapMin``) after a group whose covered time K (the union of its
session spans, gaps excluded) is at least H = ``napMode.maxSessionMs`` (180 min); R45 and R90 are reported only.

Constructs (section 5) are built from the labelled open-dataset nights (Walch, BIDSleep): V1 stretches one real
awakening into a wake bout of g + 10 min (END, MID, START), V2a puts a copy of the night's first minutes before it
(DOZE), V2b after it (NAPAFTER), and the positive control starts the night 15 min late (PC). Each construct is decoded
by the model in effect at fold level (13.12's N1 fold bundles and parameters,
:func:`pulsetrain.napsrealistic.fold_bundle`): merged (M, the gap forced AWAKE), split (S: every piece on its own,
ignored below 90 min, a nap below 180 min) and, for NAPAFTER, the nap piece alone (S'). algo-cli is the single source of
truth for features, decoding and per-session scoring; this module builds the session files, emulates the app's grouping
(:func:`group`, gate G4), scores regions from the per-epoch CSV (:func:`score_part`, gate G5), and applies the
statistics (section 6) and the rule (section 11). Gate V3 replays the Sleep as Android gate in the Kotlin harness
(``MergeGapGateReplayTest``), V4 groups the developer's labelled pair.

Subcommands (``train.py``): :func:`run_base` (phase A, ``mergegap-base``), :func:`run_gates` (G0, G1, G2, G4,
``mergegap-gates``), :func:`run_experiment` (``mergegap``) and :func:`rerender` (``mergegap-report``). Each logs its
START / END / RESULT / NOTE lines in the commands log (append only; the pre-registration NOTE must precede them, and
the U1 NOTE must precede the first START of ``train.py mergegap``).
"""

from __future__ import annotations

import bisect
import hashlib
import json
import logging
import math
import re
import shutil
import statistics
import subprocess
import time
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Iterable, Mapping, Sequence

import numpy as np
import pandas as pd

from . import ds006576 as ds
from . import loso, napmodel, napsrealistic, pools, report
from .algocli import AlgoCli
from .nightset import dataset_from_id
from .util import code_fingerprint, read_json, write_json

log = logging.getLogger(__name__)

PREREG_PATH = "docs/prereg/prereg-merge-gap.md"
PREREG_NAME = "prereg-merge-gap.md"
PREREG_SHA256 = "1692a87ef59d92d6b2063488675f97e536848a608bb6b615e3b428c53bb29096"
REPO_ROOT = Path(__file__).resolve().parents[3]
PREREG_NOTE = f"NOTE {PREREG_NAME} sha256 {PREREG_SHA256}"
U1_RE = re.compile(r" NOTE merge-gap U1 DMAX (\d+): ")
# The developer's amendment of the limit: it applies when it is logged after the U1 NOTE and before the first RESULT
# of ``train.py mergegap`` (before any result of the experiment existed); the last such line counts.
AMENDMENT_RE = re.compile(r"^\S+ NOTE merge-gap AMENDMENT U1 DMAX (\d+): ")
EXPERIMENT_RESULT_RE = re.compile(r"^\S+ RESULT mergegap(?=\s|:)")
# Only a START line counts (its type follows the time stamp): a NOTE that mentions a start is not one.
EXPERIMENT_START_RE = re.compile(r"^\S+ START .*\btrain\.py mergegap(?=\s|$)")
ANY_START_RE = re.compile(r" START .*\btrain\.py mergegap(?:-base|-gates|-report)?(?=\s|$)")
BASE_COMMIT = "e7caaf4"
BUILD_NEUTRAL_PATHS: tuple[str, ...] = ("core-algo", "algo-cli", "app/src/main/assets/models")
# the build configuration shared by every module: with these and BUILD_NEUTRAL_PATHS unchanged, and algo-cli depending
# on no project but :core-algo, the algo-cli build of the working tree is the base commit's (phase A, section 14)
ROOT_BUILD_PATHS: tuple[str, ...] = ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle")
APP_BUNDLE = napsrealistic.APP_BUNDLE
FIXTURE_PATH = REPO_ROOT / "tools" / "train" / "tests" / "data" / "mergegap-grouping.json"

MIN = 60_000
CHUNK_MS = 30_000                    # label intervals and scoring chunks (IntervalScoring.DEFAULT_CHUNK_MS)
MARGIN_MS = 5 * MIN                  # the OHealth-like session margins of prepare.py
CONTEXT_MS = 30 * MIN                # the app reads heart rate and steps of [start - 30 min, end + 30 min]
HR_STEP_MS = 2 * MIN                 # the OHealth-like heart-rate grid
BOUT_MIN_MS = pools.R_MIN_BOUT_MS    # 2 min: naps-realistic's return-to-sleep bout
BOUT_MAX_MS = 40 * MIN
NIGHT_MIN_MS = 180 * MIN
PC_LATE_MS = 15 * MIN
HR10_BPM = 10.0
WALK_STEPS = 40
WALK_EVERY_MS = 5 * MIN
EPOCH_SEC_DEFAULT = 60
DECODE_BLOCK = 1000
MAX_CALLS = 10

STAGES: tuple[str, ...] = ("AWAKE", "LIGHT", "DEEP", "REM")
STAGE_INDEX = {s: i for i, s in enumerate(STAGES)}
SLEEP_STAGES = frozenset(("LIGHT", "DEEP", "REM"))
VARIANTS: tuple[str, ...] = report.HEADLINE_VARIANTS
DATASETS: tuple[str, ...] = ("walch", "bidsleep")          # bootstrap order: Walch first (section 6.2)

# ---------------------------------------------------------------------------------------------------- arms ----


@dataclass(frozen=True)
class Arm:
    """One arm of section 3.1: ``sources.mergeGapMin`` and ``sources.continuationGapMin`` (0 = off)."""
    name: str
    merge_gap_min: int
    continuation_gap_min: int = 0


ARMS: dict[str, Arm] = {"A": Arm("A", 30), "B": Arm("B", 60), "C": Arm("C", 30, 60), "R45": Arm("R45", 45),
                        "R90": Arm("R90", 90)}
ARM_ORDER: tuple[str, ...] = tuple(ARMS)
REPORTED_ARMS: tuple[str, ...] = ("R45", "R90")
MIN_SESSION_MIN = 90                 # sources.minSessionMin
NAP_MAX_SESSION_MIN = 180.0          # napMode.maxSessionMin
DEFAULT_NAP_MAX_SESSION_MS = 180 * MIN      # NapModeParams().maxSessionMs (TrainedDefaults.NAP_MAX_SESSION_MIN)


class MergeGapError(RuntimeError):
    """The merge-gap experiment cannot run (inputs missing, a refused order or build) or a check failed."""


class GateFailure(MergeGapError):
    """A fatal gate failed; ``gates`` holds the gate records reached so far."""

    def __init__(self, message: str, gates: Mapping):
        super().__init__(message)
        self.gates = dict(gates)


def minutes_to_whole_ms(minutes: float | None) -> int:
    """``minutesToWholeMs`` of core-algo: rounded up to whole ms, 0 for a non-positive or undefined value."""
    if minutes is None or not math.isfinite(float(minutes)) or float(minutes) <= 0.0:
        return 0
    return int(math.ceil(float(minutes) * MIN))


def covered_threshold_ms(nap_max_session_min: float | None) -> int:
    """H of arm C: ``napMode.maxSessionMs`` when positive, otherwise ``NapModeParams().maxSessionMs`` (180 min)."""
    h = minutes_to_whole_ms(nap_max_session_min)
    return h if h > 0 else DEFAULT_NAP_MAX_SESSION_MS


def threshold_after(covered_ms: int, arm: Arm, h_ms: int) -> int:
    """``MergeGaps.thresholdAfter``: the continuation gap when it is larger than the merge gap and the group covers at
    least ``h_ms``, else the merge gap (ms)."""
    m, c = arm.merge_gap_min * MIN, arm.continuation_gap_min * MIN
    return c if c > m and covered_ms >= h_ms else m


def gaps_between(spans: Sequence[tuple[int, int]]) -> list[list[int]]:
    """``gapsBetween``: the uncovered time between ``spans`` sorted by start (overlaps leave no gap)."""
    out: list[list[int]] = []
    if not spans:
        return out
    covered = spans[0][1]
    for a, b in spans[1:]:
        if a > covered:
            out.append([covered, a])
        covered = max(covered, b)
    return out


def group(sessions: Iterable[Mapping], arm: Arm | str, *, min_session_min: int = MIN_SESSION_MIN,
          nap_max_session_min: float | None = NAP_MAX_SESSION_MIN) -> dict:
    """``NightCandidateBuilder.build`` and ``continuationThresholds`` for the sessions of one package
    (``{"uuid", "startMs", "endMs"}``, no stages) under ``arm``: zero-length sessions dropped, the first of a repeated
    uuid kept, sorted by (start, end, uuid); a session joins the current group when its start minus the group's latest
    end so far is below :func:`threshold_after` the group's covered time before it joins. Returns ``groups`` (by start:
    sorted uuids, span, forced-AWAKE gaps, covered time and mode IGNORED / NAP / NIGHT) and ``thresholds`` (per uuid,
    the gap below which a later session would join its group, from the whole group's covered time)."""
    arm = ARMS[arm] if isinstance(arm, str) else arm
    h = covered_threshold_ms(nap_max_session_min)
    nap_ms = minutes_to_whole_ms(nap_max_session_min)
    seen: set[str] = set()
    usable: list[tuple[int, int, str]] = []
    for s in sessions:
        u, a, b = str(s["uuid"]), int(s["startMs"]), int(s["endMs"])
        if b <= a or u in seen:
            continue
        seen.add(u)
        usable.append((a, b, u))
    usable.sort()
    groups: list[dict] = []
    for a, b, u in usable:
        cur = groups[-1] if groups else None
        if cur is not None and a - cur["end"] < threshold_after(cur["covered"], arm, h):
            cur["sessions"].append((a, b, u))
            cur["covered"] += max(0, b - max(a, cur["end"]))
            cur["end"] = max(cur["end"], b)
        else:
            groups.append({"sessions": [(a, b, u)], "covered": b - a, "end": b})
    out, thresholds = [], {}
    for gr in groups:
        ss = gr["sessions"]
        start, end = ss[0][0], gr["end"]
        span = end - start
        mode = "IGNORED" if span < min_session_min * MIN else ("NAP" if span < nap_ms else "NIGHT")
        out.append({"uuids": sorted(u for _, _, u in ss), "startMs": start, "endMs": end,
                    "forcedAwake": gaps_between([(a, b) for a, b, _ in ss]), "coveredMs": gr["covered"],
                    "mode": mode})
        t = threshold_after(gr["covered"], arm, h)
        for _, _, u in ss:
            thresholds[u] = t
    return {"groups": out, "thresholds": thresholds}


def merged(pieces: Sequence[tuple[int, int]], arm: Arm | str) -> bool:
    """Whether ``arm`` groups ``pieces`` (one package, in time order) into one group."""
    res = group([{"uuid": f"p{i}", "startMs": a, "endMs": b} for i, (a, b) in enumerate(pieces)], arm)
    return len(res["groups"]) == 1


# ---------------------------------------------------------------------------------------- grouping fixture ----

FIXTURE_T0 = 1_791_849_600_000 + 60 * MIN       # 2026-10-13T01:00Z (PipelineHarness.DEFAULT_START_MS + 1 h)
LOCAL = timezone(timedelta(hours=2))             # the development phone in October 2026 (CEST)


def _local_ms(day: int, hour: int, minute: int) -> int:
    return int(datetime(2026, 10, day, hour, minute, tzinfo=LOCAL).timestamp() * 1000)


# Section 2.6 / 10: the OHealth sessions 00:18-07:12 and 07:50-08:19 of 21 Oct 2026 (one package), labelled one night
LABELLED_PAIR: tuple[dict, ...] = (
    {"uuid": "21oct-1", "startMs": _local_ms(21, 0, 18), "endMs": _local_ms(21, 7, 12)},
    {"uuid": "21oct-2", "startMs": _local_ms(21, 7, 50), "endMs": _local_ms(21, 8, 19)},
)


def _fixture_case(name: str, spans: Sequence[tuple[int, int]], *, uuids: Sequence[str] | None = None,
                  nap_max_session_min: float = NAP_MAX_SESSION_MIN, absolute: bool = False) -> dict:
    base = 0 if absolute else FIXTURE_T0
    ids = list(uuids) if uuids is not None else [f"s{i + 1}" for i in range(len(spans))]
    sessions = [{"uuid": u, "startMs": base + a, "endMs": base + b} for u, (a, b) in zip(ids, spans)]
    return {"name": name, "minSessionMin": MIN_SESSION_MIN, "napMaxSessionMin": nap_max_session_min,
            "sessions": sessions,
            "expected": {k: group(sessions, k, min_session_min=MIN_SESSION_MIN,
                                  nap_max_session_min=nap_max_session_min) for k in ARM_ORDER}}


def grouping_fixture() -> dict:
    """The cases of gate G4 (section 14), with every arm's expected grouping and thresholds by :func:`group`; the
    committed ``tools/train/tests/data/mergegap-grouping.json`` must equal it, and the data-hc tests
    ``MergeGapGroupingFixtureTest`` and ``SourceSettlementMergeGapTest`` check ``NightCandidateBuilder`` and
    ``SourceSettlement`` against the same file."""
    cases = []
    around = sorted({0, 1, 20 * MIN, 38 * MIN} | {t * MIN + d for t in (30, 45, 60, 90) for d in (-1, 0, 1)})
    for gap in around:                                            # a long group (K >= H), a 30-min fragment
        e = 240 * MIN
        cases.append(_fixture_case(f"long-then-fragment-gap-{gap}ms", [(0, e), (e + gap, e + gap + 30 * MIN)]))
    for gap in (30 * MIN - 1, 30 * MIN, 45 * MIN, 60 * MIN - 1, 60 * MIN):     # a short group (K < H)
        e = 120 * MIN
        cases.append(_fixture_case(f"short-then-fragment-gap-{gap}ms", [(0, e), (e + gap, e + gap + 100 * MIN)]))
    for k in (180 * MIN - 1, 180 * MIN):                          # K just below and exactly at H
        for gap in (45 * MIN, 59 * MIN):
            cases.append(_fixture_case(f"covered-{k}ms-gap-{gap}ms", [(0, k), (k + gap, k + gap + 30 * MIN)]))
    for gap in (20 * MIN, 45 * MIN):                              # a night by span (185 min) with K = 160 < H
        cases.append(_fixture_case(f"night-by-span-K160-then-gap-{gap}ms",
                                   [(0, 80 * MIN), (105 * MIN, 185 * MIN), (185 * MIN + gap, 215 * MIN + gap)]))
    cases.append(_fixture_case("overlap-union-190-then-gap-45min",            # K = 190 (union), sum 240
                               [(0, 100 * MIN), (50 * MIN, 190 * MIN), (235 * MIN, 265 * MIN)]))
    cases.append(_fixture_case("overlap-union-170-sum-245-then-gap-45min",    # K = 170 (union) < H < 245 (sum)
                               [(0, 100 * MIN), (20 * MIN, 90 * MIN), (95 * MIN, 170 * MIN),
                                (215 * MIN, 245 * MIN)]))
    for length in (120, 200):                                     # naps disabled: H falls back to 180 min
        e = length * MIN
        cases.append(_fixture_case(f"naps-disabled-{length}min-then-gap-45min", [(0, e), (e + 45 * MIN, e + 75 * MIN)],
                                   nap_max_session_min=0.0))
    cases.append(_fixture_case("chain-of-three-gaps-50min",
                               [(0, 200 * MIN), (250 * MIN, 280 * MIN), (330 * MIN, 360 * MIN)]))
    cases.append(_fixture_case("chain-K-reaches-H-on-the-second",
                               [(0, 150 * MIN), (170 * MIN, 200 * MIN), (250 * MIN, 300 * MIN)]))
    cases.append(_fixture_case("chain-K-1ms-below-H-after-the-second",
                               [(0, 150 * MIN), (170 * MIN, 200 * MIN - 1), (250 * MIN, 300 * MIN)]))
    for length in (90 * MIN - 1, 90 * MIN, 180 * MIN - 1, 180 * MIN):     # spans around 90 and 180 min
        cases.append(_fixture_case(f"single-{length}ms", [(0, length)]))
    cases.append(_fixture_case("merged-span-90min", [(0, 50 * MIN), (75 * MIN, 90 * MIN)]))
    cases.append(_fixture_case("merged-span-90min-1ms", [(0, 50 * MIN), (75 * MIN, 90 * MIN - 1)]))
    cases.append(_fixture_case("merged-span-180min", [(0, 100 * MIN), (125 * MIN, 180 * MIN)]))
    cases.append(_fixture_case("tie-order-and-duplicates",
                               [(0, 200 * MIN), (0, 100 * MIN), (0, 100 * MIN), (50 * MIN, 50 * MIN),
                                (240 * MIN, 270 * MIN), (300 * MIN, 310 * MIN)],
                               uuids=["b", "c", "a", "zero", "f", "c"]))
    cases.append(_fixture_case("labelled-pair-21-oct", [(s["startMs"], s["endMs"]) for s in LABELLED_PAIR],
                               uuids=[s["uuid"] for s in LABELLED_PAIR], absolute=True))
    return {"description": "Gate G4 of docs/prereg/prereg-merge-gap.md: per case and arm, the grouping of "
                           "NightCandidateBuilder (one package, OHealth) and the continuation thresholds (ms) that "
                           "SourceSettlement adds DATA_MARGIN_EXTRA_MS (30 min) to. Generated by "
                           "pulsetrain.mergegap.grouping_fixture(); do not edit by hand.",
            "arms": {k: {"mergeGapMin": a.merge_gap_min, "continuationGapMin": a.continuation_gap_min}
                     for k, a in ARMS.items()},
            "dataMarginExtraMs": 30 * MIN,
            "cases": cases}


def write_grouping_fixture(path: Path = FIXTURE_PATH) -> Path:
    """Writes :func:`grouping_fixture` (pretty JSON, one trailing newline)."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(grouping_fixture(), indent=1) + "\n", encoding="utf-8")
    return path


def check_grouping_fixture(path: Path = FIXTURE_PATH) -> dict:
    """G4 (Python side): the committed fixture equals :func:`grouping_fixture` (every case under every arm)."""
    want = grouping_fixture()
    try:
        got = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        return {"path": str(path), "error": str(e), "passed": False}
    bad = [c["name"] for c, d in zip(want["cases"], got.get("cases", [])) if c != d]
    ok = got == want
    return {"path": str(path), "cases": len(want["cases"]), "arms": list(ARM_ORDER),
            "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest(), "differing": bad[:10],
            "casesInFile": len(got.get("cases", [])), "passed": bool(ok)}


# -------------------------------------------------------------------------------------------- constructs ----

V1_F: tuple[int, ...] = (25, 60, 100, 140)
DOZE_F: tuple[int, ...] = (25, 60, 100)
NAPAFTER_F: tuple[int, ...] = (60, 100)
BANDS: dict[int, tuple[int, int]] = {25: (15, 40), 60: (40, 90), 100: (90, 125), 140: (125, 180)}
DESIGNS: tuple[str, ...] = (*(f"END-{f}" for f in V1_F), "MID", *(f"START-{f}" for f in V1_F),
                            *(f"DOZE-{f}" for f in DOZE_F), *(f"NAPAFTER-{f}" for f in NAPAFTER_F))
DECISIVE_GAPS: tuple[int, ...] = (35, 45, 59)
R90_GAPS: tuple[int, ...] = (75, 89)
DECISIVE_STRETCHES: tuple[str, ...] = ("base", "hr10")
REPORTED_STRETCHES: tuple[str, ...] = ("nohr", "presleep", "walk")
STRETCHES: tuple[str, ...] = (*DECISIVE_STRETCHES, *REPORTED_STRETCHES)
REPORTED_STRETCH_GAP = 45
# every (g, stretch variant) built for every hosting (night, design): section 19's 11 per pair
GAP_STRETCH: tuple[tuple[int, str], ...] = (*((g, h) for g in DECISIVE_GAPS for h in DECISIVE_STRETCHES),
                                            *((REPORTED_STRETCH_GAP, h) for h in REPORTED_STRETCHES),
                                            *((g, "base") for g in R90_GAPS))

# Section 2.8: hosting nights (subjects) per design, (Walch, BIDSleep); gate G7 compares and reports
EXPECTED_HOSTS: dict[str, tuple[tuple[int, int], tuple[int, int]]] = {
    "END-25": ((4, 4), (36, 25)), "END-60": ((4, 4), (63, 34)), "END-100": ((2, 2), (35, 22)),
    "END-140": ((7, 7), (62, 37)), "MID": ((8, 8), (61, 33)), "NAPAFTER-60": ((7, 7), (87, 42)),
    "NAPAFTER-100": ((5, 5), (51, 28)), "START-25": ((2, 2), (48, 30)), "START-60": ((7, 7), (87, 42)),
    "START-100": ((4, 4), (49, 28)), "START-140": ((4, 4), (84, 38)), "DOZE-25": ((2, 2), (48, 30)),
    "DOZE-60": ((7, 7), (87, 42)), "DOZE-100": ((5, 5), (51, 28))}
EXPECTED_NO_HOST = {"walch": 10, "bidsleep": 31}


def kind_of(design: str) -> str:
    return design.split("-")[0]


def _labels(night: Mapping) -> list[tuple[int, int, str]]:
    return sorted((int(a), int(b), str(s)) for a, b, s in night.get("labels", []))


def host_bouts(night: Mapping) -> list[dict]:
    """Section 5.1: the wake bouts (b0, b1) of ``napmodel.wake_bouts`` with 2 min <= w <= 40 min whose neighbouring
    label intervals (the one ending at b0, the one starting at b1) both exist and are sleep, with t0 < b0 and b1 < t1,
    and no step record with start < b1 < end; with the piece lengths lam1 = b0 + 5 min - t0 and
    lam2 = t1 - (b1 - 5 min)."""
    t0, t1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])
    labs = _labels(night)
    end_at = {b: s for _, b, s in labs}
    start_at = {a: s for a, _, s in labs}
    steps = [(int(r[0]), int(r[1])) for r in night.get("steps", [])]
    out = []
    for b0, b1 in napmodel.wake_bouts(night.get("labels", [])):
        w = b1 - b0
        if not BOUT_MIN_MS <= w <= BOUT_MAX_MS:
            continue
        if end_at.get(b0) not in SLEEP_STAGES or start_at.get(b1) not in SLEEP_STAGES:
            continue
        if not (t0 < b0 and b1 < t1):
            continue
        if any(s < b1 < e for s, e in steps):
            continue
        out.append({"b0": b0, "b1": b1, "w": w, "lam1": b0 + MARGIN_MS - t0, "lam2": t1 - (b1 - MARGIN_MS),
                    "t0": t0, "t1": t1})
    return out


def wake_pool(night: Mapping) -> dict[int, list[tuple[int, float]]]:
    """Section 5.1: the heart-rate samples with t0 <= t < t1 lying in an AWAKE label interval, in blocks by the wake
    bout that contains them (bout start -> samples in time order; empty blocks dropped)."""
    t0, t1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])
    awake = [(a, b) for a, b, s in _labels(night) if s == "AWAKE"]
    starts = [a for a, _ in awake]
    bouts = napmodel.wake_bouts(night.get("labels", []))
    bout_starts = [b0 for b0, _ in bouts]
    out: dict[int, list[tuple[int, float]]] = defaultdict(list)
    for t, v in sorted((int(h[0]), float(h[1])) for h in night.get("hr", [])):
        if not t0 <= t < t1:
            continue
        i = bisect.bisect_right(starts, t) - 1
        if i < 0 or t >= awake[i][1]:
            continue
        j = bisect.bisect_right(bout_starts, awake[i][0]) - 1
        out[bouts[j][0]].append((t, v))
    return {k: v for k, v in out.items() if v}


def block_key(key: str, design: str, bout_start: int, t0: int) -> str:
    """Block order of a construct: SHA-256 of ``<night key>|mergegap|<design>|<bout start - t0 in ms>``."""
    return hashlib.sha256(f"{key}|mergegap|{design}|{int(bout_start) - int(t0)}".encode("utf-8")).hexdigest()


def block_order(night: Mapping, design: str, pool: Mapping[int, Sequence] | None = None) -> list[int]:
    """The block order of a construct of ``design`` on ``night`` (section 5.1): its wake-pool blocks, as bout start
    - t0 in ms, by ascending :func:`block_key`."""
    pool = wake_pool(night) if pool is None else pool
    key, t0 = napmodel.night_key(str(night["id"])), int(night["sessionStartMs"])
    return [b0 - t0 for b0 in sorted(pool, key=lambda b0: (block_key(key, design, b0, t0), b0))]


def pool_values(night: Mapping, design: str) -> list[float]:
    """The wake-pool values of ``night`` in the block order of ``design`` (concatenated; ``base`` cycles them)."""
    pool = wake_pool(night)
    t0 = int(night["sessionStartMs"])
    return [v for off in block_order(night, design, pool) for _, v in pool[t0 + off]]


def presleep_values(night: Mapping) -> list[float]:
    """The night's own heart-rate values of [t0 - 30 min, t0) in time order (stretch variant ``presleep``)."""
    t0 = int(night["sessionStartMs"])
    return [float(v) for t, v in sorted((int(h[0]), float(h[1])) for h in night.get("hr", []))
            if t0 - CONTEXT_MS <= t < t0]


def in_band(lam: int, f: int) -> bool:
    lo, hi = BANDS[f]
    return lo * MIN <= lam < hi * MIN


def choose_hosts(night: Mapping) -> dict[str, dict]:
    """Section 5.2-5.4: one host bout per design (from the labels, the session boundaries and the step records only, so
    the same for every gap, stretch variant and sampling variant); designs without a host are left out."""
    bouts = host_bouts(night)
    t0, t1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])
    hosts: dict[str, dict] = {}

    def pick(design: str, cands: list[dict], key: Callable[[dict], tuple]) -> None:
        if cands:
            hosts[design] = dict(min(cands, key=key), design=design)

    for f in V1_F:
        pick(f"END-{f}", [b for b in bouts if in_band(b["lam2"], f) and b["lam1"] >= NIGHT_MIN_MS],
             lambda b, f=f: (abs(b["lam2"] - f * MIN), -b["b1"]))
    pick("MID", [b for b in bouts if b["lam1"] >= NIGHT_MIN_MS and b["lam2"] >= NIGHT_MIN_MS],
         lambda b: (abs(2 * b["b0"] - (t0 + t1)), b["b0"]))
    for f in V1_F:
        pick(f"START-{f}", [b for b in bouts if in_band(b["lam1"], f) and b["lam2"] >= NIGHT_MIN_MS],
             lambda b, f=f: (abs(b["lam1"] - f * MIN), b["b0"]))
    for f in DOZE_F:
        pick(f"DOZE-{f}", [b for b in bouts if in_band(b["lam1"], f) and t1 - t0 >= NIGHT_MIN_MS],
             lambda b, f=f: (abs(b["lam1"] - f * MIN), b["b0"]))
    for f in NAPAFTER_F:
        if f"DOZE-{f}" in hosts:
            hosts[f"NAPAFTER-{f}"] = dict(hosts[f"DOZE-{f}"], design=f"NAPAFTER-{f}")
    return {d: hosts[d] for d in DESIGNS if d in hosts}


def _invariant_part(night: Mapping) -> tuple:
    return (int(night["sessionStartMs"]), int(night["sessionEndMs"]), _labels(night),
            sorted(tuple(int(x) for x in r) for r in night.get("steps", [])))


def night_hosts(nights: Mapping[str, Mapping]) -> tuple[dict[str, dict], dict]:
    """The hosts of one night key given its night in every variant (``variant -> night``): none when a variant has no
    wake heart-rate pool (section 5.1); the variants must agree in their labels, boundaries and step records (G3)."""
    vs = list(nights)
    ref = _invariant_part(nights[vs[0]])
    for v in vs[1:]:
        if _invariant_part(nights[v]) != ref:
            raise MergeGapError(f"{nights[v]['id']}: labels, session boundaries or step records differ from "
                                f"{nights[vs[0]]['id']}")
    no_pool = [v for v in vs if not wake_pool(nights[v])]
    if no_pool:
        return {}, {"noWakePool": no_pool}
    return choose_hosts(nights[vs[0]]), {}


def piece_lengths(design: str, host: Mapping) -> tuple[int, int]:
    """The two pieces' lengths (ms) of a construct of ``design`` on ``host`` (independent of g and the stretch)."""
    k = kind_of(design)
    if k in ("END", "START", "MID"):
        return host["lam1"], host["lam2"]
    if k == "DOZE":
        return host["lam1"], host["t1"] - host["t0"]
    return host["t1"] - host["t0"], host["lam1"]


@dataclass
class Construct:
    """One construct (section 5): its timeline (heart rate, step records, labels), the two pieces as the source would
    write them, the inter-session gap (the forced-AWAKE span of M), the regions of section 6.1 and the stretch."""
    id: str
    night_id: str
    key: str
    subject: str
    variant: str | None
    dataset: str
    design: str
    g: int
    stretch: str
    hr: list
    steps: list
    labels: list
    pieces: tuple[tuple[int, int], ...]
    gap: tuple[int, int]
    regions: dict[str, tuple[tuple[int, int], ...]]
    stretch_span: tuple[int, int]
    shift: int
    host: dict = field(default_factory=dict)


def construct_id(night_id: str, design: str, g: int, stretch: str) -> str:
    return f"{night_id}~mg-{design}-g{g}-{stretch}"


def stretch_labels(x0: int, x1: int) -> list[list]:
    if (x1 - x0) % CHUNK_MS:
        raise MergeGapError(f"stretch [{x0}, {x1}) is not a whole number of 30-s label intervals")
    return [[a, a + CHUNK_MS, "AWAKE"] for a in range(x0, x1, CHUNK_MS)]


def stretch_data(x0: int, x1: int, c_last: int | None, stretch: str, values: Sequence[float],
                 presleep: Sequence[float]) -> tuple[list[list], list[list]]:
    """Section 5.1: heart-rate samples at tau_k = tau_1 + (k - 1) 2 min while tau_k < x1 - 1 min, tau_1 = max(c + 2 min,
    x0) (c = the construct's last sample before x0, x0 - 2 min if none), with the values of the stretch variant; and,
    for ``walk``, one-minute step records of 40 steps every 5 min from x0 + 5 min while the record ends before
    x1 - 5 min."""
    c = c_last if c_last is not None else x0 - HR_STEP_MS
    t = max(c + HR_STEP_MS, x0)
    times = []
    while t < x1 - MIN:
        times.append(t)
        t += HR_STEP_MS
    hr: list[list] = []
    if stretch != "nohr" and times:
        src = list(presleep) if stretch == "presleep" else list(values)
        if not src:
            raise MergeGapError(f"stretch variant {stretch}: no values")
        add = HR10_BPM if stretch == "hr10" else 0.0
        hr = [[tk, float(src[k % len(src)]) + add] for k, tk in enumerate(times)]
    steps: list[list] = []
    if stretch == "walk":
        s = x0 + WALK_EVERY_MS
        while s + MIN < x1 - 5 * MIN:
            steps.append([s, s + MIN, WALK_STEPS])
            s += WALK_EVERY_MS
    return hr, steps


def _hr(night: Mapping) -> list[list]:
    return sorted([int(h[0]), float(h[1])] for h in night.get("hr", []))


def _steps(night: Mapping) -> list[list]:
    return sorted([int(r[0]), int(r[1]), int(r[2])] for r in night.get("steps", []))


def build_construct(night: Mapping, design: str, g: int, stretch: str, host: Mapping) -> Construct | None:
    """The construct of ``design`` at gap ``g`` (minutes) with stretch variant ``stretch`` on ``host`` (sections
    5.2-5.4); None for ``presleep`` when the night has no heart rate in the 30 min before t0.

    Operational readings of the boundaries (where the text says "before", "at or after" or "after"): V1 shifts what lies
    at or after b1 (labels and records by start, samples by time) and keeps what lies before; DOZE keeps the night's
    labels, samples and records at or after t0 (records by start) and removes those before t0; NAPAFTER keeps the
    night's samples before t1 and its records starting before t1 and removes the rest ("after t1" read as "at or after
    t1": a sample at exactly t1 would otherwise share its time with the stretch's first sample, tau_1 = max(c + 2 min,
    t1) = t1)."""
    t0, t1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])
    b0, b1, w = int(host["b0"]), int(host["b1"]), int(host["b1"]) - int(host["b0"])
    gms = int(g) * MIN
    pre = presleep_values(night)
    if stretch == "presleep" and not pre:
        return None
    values = pool_values(night, design) if stretch != "nohr" else []
    hr, steps, labels = _hr(night), _steps(night), [list(x) for x in _labels(night)]
    k = kind_of(design)
    if k in ("END", "START", "MID"):
        d = gms + 10 * MIN - w
        x0, x1 = b1, b1 + d
        before_hr = [h for h in hr if h[0] < b1]
        s_hr, s_steps = stretch_data(x0, x1, before_hr[-1][0] if before_hr else None, stretch, values, pre)
        hr = before_hr + s_hr + [[t + d, v] for t, v in hr if t >= b1]
        labels = [x for x in labels if x[0] < b1] + stretch_labels(x0, x1) + \
            [[a + d, b + d, s] for a, b, s in labels if a >= b1]
        steps = [r for r in steps if r[0] < b1] + s_steps + [[a + d, b + d, n] for a, b, n in steps if a >= b1]
        e1, s2 = b0 + MARGIN_MS, b1 + d - MARGIN_MS
        pieces = ((t0, e1), (s2, t1 + d))
        gap, shift = (e1, s2), d
        regions = {"END": {"main": (pieces[0],), "fragment": (pieces[1],)},
                   "START": {"fragment": (pieces[0],), "main": (pieces[1],)},
                   "MID": {"main": pieces}}[k]
    elif k == "DOZE":
        dp = gms + 5 * MIN - w
        delta = (b1 - t0) + dp
        x0, x1 = t0 - dp, t0
        copy_hr = [[t - delta, v] for t, v in hr if t0 - CONTEXT_MS <= t < b1]
        s_hr, s_steps = stretch_data(x0, x1, copy_hr[-1][0] if copy_hr else None, stretch, values, pre)
        hr = copy_hr + s_hr + [h for h in hr if h[0] >= t0]
        labels = [[a - delta, b - delta, s] for a, b, s in labels if a >= t0 and b <= b1] + stretch_labels(x0, x1) + \
            [x for x in labels if x[0] >= t0]
        steps = [[a - delta, b - delta, n] for a, b, n in steps if a >= t0 - CONTEXT_MS and b <= b1] + s_steps + \
            [r for r in steps if r[0] >= t0]
        pieces = ((t0 - delta, b0 - delta + MARGIN_MS), (t0, t1))
        gap, shift = (b0 - delta + MARGIN_MS, t0), -delta
        regions = {"main": (pieces[1],), "fragment": (pieces[0],)}
    elif k == "NAPAFTER":
        dn = (t1 - t0) + gms
        x0, x1 = t1, t1 + gms
        kept_hr = [h for h in hr if h[0] < t1]
        s_hr, s_steps = stretch_data(x0, x1, kept_hr[-1][0] if kept_hr else None, stretch, values, pre)
        hr = kept_hr + s_hr + [[t + dn, v] for t, v in hr if t0 <= t < b1]
        labels = labels + stretch_labels(x0, x1) + [[a + dn, b + dn, s] for a, b, s in labels if a >= t0 and b <= b1]
        steps = [r for r in steps if r[0] < t1] + s_steps + \
            [[a + dn, b + dn, n] for a, b, n in steps if a >= t0 and b <= b1]
        pieces = ((t0, t1), (t1 + gms, b0 + MARGIN_MS + dn))
        gap, shift = (t1, t1 + gms), dn
        regions = {"main": (pieces[0],), "fragment": (pieces[1],)}
    else:
        raise ValueError(f"unknown design {design}")
    hr.sort(key=lambda h: h[0])
    steps.sort()
    labels.sort(key=lambda x: (x[0], x[1]))
    if any(hr[i][0] == hr[i - 1][0] for i in range(1, len(hr))):
        raise MergeGapError(f"{night['id']} {design} g{g} {stretch}: two heart-rate samples at one time")
    nid = str(night["id"])
    return Construct(id=construct_id(nid, design, g, stretch), night_id=nid, key=napmodel.night_key(nid),
                     subject=str(night["subject"]), variant=night.get("variant"), dataset=dataset_from_id(nid),
                     design=design, g=int(g), stretch=stretch, hr=hr, steps=steps, labels=labels, pieces=pieces,
                     gap=gap, regions=regions, stretch_span=(x0, x1), shift=shift, host=dict(host))


def cut(timeline: Mapping, s0: int, s1: int, sid: str, forced: Sequence[Sequence[int]] = ()) -> dict:
    """Section 5.6, the app's reader: heart rate of [s0 - 30 min, s1 + 30 min], the step records that overlap it, the
    label intervals lying in [s0, s1], the forced-AWAKE spans inside [s0, s1] (the key only when there is one)."""
    c0, c1 = int(s0) - CONTEXT_MS, int(s1) + CONTEXT_MS
    out = {"id": sid, "subject": timeline["subject"], "variant": timeline.get("variant"),
           "sessionStartMs": int(s0), "sessionEndMs": int(s1),
           "hr": [list(h) for h in timeline["hr"] if c0 <= h[0] <= c1],
           "steps": [list(r) for r in timeline.get("steps", []) if r[0] < c1 and r[1] > c0],
           "labels": [list(x) for x in timeline.get("labels", []) if x[0] >= s0 and x[1] <= s1]}
    spans = [[int(a), int(b)] for a, b in forced if s0 <= a and b <= s1 and a < b]
    if spans:
        out["forcedAwake"] = spans
    return out


def construct_timeline(c: Construct) -> dict:
    return {"subject": c.subject, "variant": c.variant, "hr": c.hr, "steps": c.steps, "labels": c.labels}


def night_timeline(night: Mapping) -> dict:
    return {"subject": night["subject"], "variant": night.get("variant"), "hr": _hr(night), "steps": _steps(night),
            "labels": [list(x) for x in _labels(night)]}


def parts_of(design: str, lengths: tuple[int, int]) -> tuple[str, ...]:
    """The decoded sessions of a construct: M, each piece of at least 90 min (S1, S2), and S' for NAPAFTER."""
    out = ["M"]
    for i, n in enumerate(lengths):
        if n >= MIN_SESSION_MIN * MIN:
            out.append(f"S{i + 1}")
    if kind_of(design) == "NAPAFTER":
        out.append("Snap")
    return tuple(out)


def construct_sessions(c: Construct, parts: Iterable[str] | None = None) -> dict[str, dict]:
    """Section 5.6: arm M (one session over both pieces, the gap forced AWAKE), S1 / S2 (each piece alone, when it is
    decoded) and S' (``Snap``, the nap piece of NAPAFTER alone); ids ``<construct id>~<part>``."""
    tl = construct_timeline(c)
    want = set(parts_of(c.design, tuple(b - a for a, b in c.pieces)) if parts is None else parts)
    out = {}
    if "M" in want:
        out["M"] = cut(tl, c.pieces[0][0], c.pieces[-1][1], f"{c.id}~M", [c.gap])
    for i, (a, b) in enumerate(c.pieces):
        if f"S{i + 1}" in want:
            out[f"S{i + 1}"] = cut(tl, a, b, f"{c.id}~S{i + 1}")
    if "Snap" in want and kind_of(c.design) == "NAPAFTER":
        out["Snap"] = cut(tl, c.pieces[1][0], c.pieces[1][1], f"{c.id}~Snap")
    return out


def pc_sessions(night: Mapping) -> dict[str, dict]:
    """Section 5.5: the unmodified night [t0, t1] and its session starting 15 min late, [t0 + 15 min, t1]."""
    tl = night_timeline(night)
    t0, t1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])
    nid = str(night["id"])
    return {"PCbase": cut(tl, t0, t1, f"{nid}~mg-PC~base"), "PClate": cut(tl, t0 + PC_LATE_MS, t1, f"{nid}~mg-PC~late")}


def pc_params(params: Mapping) -> dict:
    """Section 5.5 ("both decoded as nights"): the group's parameters with ``napMode.maxSessionMin`` = 0, so that the
    positive control's sessions are nights whatever their length (``NapModeParams.modeOf``: 0 = every session a
    night). PClate = [t0 + 15 min, t1] of a host night shorter than 195 min would otherwise be a nap under 180 min.
    Everything else (theta_g) is unchanged; the session mode is the only use of ``maxSessionMin`` in core-algo, so
    PCbase (at least 180 min, a night either way) decodes as with ``params``."""
    out = json.loads(json.dumps(dict(params)))
    out["napMode"] = {**(out.get("napMode") or {}), "maxSessionMin": 0.0}
    return out


def check_pc(ps: Mapping[str, Mapping], night: Mapping) -> list[str]:
    """Gate G3 for one positive control (section 5.5), restating the app's reader of 5.6 on the night file: PCbase is
    the unmodified night's cut over [t0, t1] and PClate its cut over [t0 + 15 min, t1] (heart rate of [s0 - 30 min,
    t1 + 30 min], the step records overlapping it, the label intervals lying in [s0, t1], the night's subject and
    variant), apart from their ids; neither has a forced-AWAKE span. (That the cut over [t0, t1] equals the night file
    is G3's check of the unmodified nights, :func:`gate_g3_nights`.)"""
    p: list[str] = []
    t0, t1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])

    def night_cut(s0: int) -> dict:
        c0, c1 = s0 - CONTEXT_MS, t1 + CONTEXT_MS
        return _normal({"id": night["id"], "subject": night["subject"], "variant": night.get("variant"),
                        "sessionStartMs": s0, "sessionEndMs": t1,
                        "hr": [h for h in night.get("hr", []) if c0 <= int(h[0]) <= c1],
                        "steps": [r for r in night.get("steps", []) if int(r[0]) < c1 and int(r[1]) > c0],
                        "labels": [x for x in night.get("labels", []) if int(x[0]) >= s0 and int(x[1]) <= t1]})

    base, late = ps.get("PCbase"), ps.get("PClate")
    if base is None or late is None:
        return [f"{night['id']}~mg-PC: without PCbase or PClate"]
    if _normal(dict(base, id=night["id"])) != night_cut(t0):
        p.append("PCbase is not the unmodified night's cut [t0, t1]")
    if _normal(dict(late, id=night["id"])) != night_cut(t0 + PC_LATE_MS):
        p.append("PClate is not the night's cut [t0 + 15 min, t1]")
    if "forcedAwake" in base or "forcedAwake" in late:
        p.append("a positive-control session with a forced-AWAKE span")
    return [f"{night['id']}~mg-PC: {x}" for x in p]


# ================================================================================================== scoring ====

EPOCH_COLUMNS: tuple[str, ...] = ("grid_index", "night_id", "epoch", "start_ms", "label", "stage")


def stage_code(name: str) -> int:
    """AWAKE 0, LIGHT 1, DEEP 2, REM 3; anything else (UNKNOWN, empty) -1 (unscored)."""
    return STAGE_INDEX.get(str(name), -1)


def read_epochs(paths: Iterable[Path], sessions: set[str] | None = None) -> dict[str, tuple[np.ndarray, np.ndarray]]:
    """Section 6.1: the rows of grid index 0 of ``algo-cli evaluate --per-epoch`` CSVs (of ``sessions`` when given) as
    ``session id -> (start_ms, stage code)`` in epoch order; a duplicate (session, epoch) or an epoch sequence other
    than 0, 1, ... is an error. (``napsrealistic.read_epochs`` drops ``start_ms`` and stays unchanged.)"""
    frames = []
    for p in paths:
        df = pd.read_csv(p, usecols=list(EPOCH_COLUMNS), dtype={"night_id": str, "label": str, "stage": str},
                         keep_default_na=False)
        df = df[df["grid_index"] == 0]
        if sessions is not None:
            df = df[df["night_id"].isin(sessions)]
        frames.append(df)
    if not frames:
        return {}
    df = pd.concat(frames, ignore_index=True)
    if df.duplicated(["night_id", "epoch"]).any():
        raise MergeGapError("duplicate per-epoch rows (session, epoch)")
    df = df.sort_values(["night_id", "epoch"], kind="mergesort")
    out = {}
    codes = df["stage"].map(lambda x: STAGE_INDEX.get(x, -1)).to_numpy(np.int64)
    ep = df["epoch"].to_numpy(np.int64)
    st = df["start_ms"].to_numpy(np.int64)
    ids = df["night_id"].to_numpy(object)
    bounds = np.flatnonzero(np.r_[True, ids[1:] != ids[:-1], True])
    for i in range(len(bounds) - 1):
        a, b = bounds[i], bounds[i + 1]
        if not np.array_equal(ep[a:b], np.arange(b - a)):
            raise MergeGapError(f"{ids[a]}: per-epoch rows are not the epochs 0..{b - a - 1}")
        out[str(ids[a])] = (st[a:b].copy(), codes[a:b].copy())
    return out


def chunk_arrays(session: Mapping, epochs: tuple[np.ndarray, np.ndarray] | None, epoch_ms: int
                 ) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Section 6.1, algo-cli's scoring rule: every label interval [a, b) of the session (one 30-s chunk) is scored with
    the stage of the session epoch whose [start_ms, min(start_ms + epochSec, session end)) contains its midpoint
    a + (b - a) / 2; (a, b, truth code, predicted code), -1 = UNKNOWN / unscored."""
    labs = session.get("labels", [])
    n = len(labs)
    a = np.fromiter((int(x[0]) for x in labs), np.int64, n)
    b = np.fromiter((int(x[1]) for x in labs), np.int64, n)
    truth = np.fromiter((stage_code(x[2]) for x in labs), np.int64, n)
    pred = np.full(n, -1, np.int64)
    if epochs is not None and len(epochs[0]) and n:
        starts, stages = epochs
        mids = a + (b - a) // 2
        k = np.searchsorted(starts, mids, side="right") - 1
        ok = k >= 0
        kk = np.where(ok, k, 0)
        ends = np.minimum(starts[kk] + int(epoch_ms), int(session["sessionEndMs"]))
        ok &= mids < ends
        pred = np.where(ok, stages[kk], -1)
    return a, b, truth, pred


def confusion(truth: np.ndarray, pred: np.ndarray, mask: np.ndarray | None = None) -> np.ndarray:
    """4 x 4 counts (rows = truth AWAKE, LIGHT, DEEP, REM) of the chunks scored on both sides (and in ``mask``)."""
    m = (truth >= 0) & (pred >= 0)
    if mask is not None:
        m &= mask
    return np.bincount(truth[m] * 4 + pred[m], minlength=16).reshape(4, 4)


def truth_counts(truth: np.ndarray, mask: np.ndarray) -> np.ndarray:
    m = mask & (truth >= 0)
    return np.bincount(truth[m], minlength=4)


def in_spans(a: np.ndarray, b: np.ndarray, spans: Iterable[Sequence[int]]) -> np.ndarray:
    """Chunks [a, b) lying in one of ``spans`` ([start, end))."""
    m = np.zeros(len(a), bool)
    for s0, s1 in spans:
        m |= (a >= int(s0)) & (b <= int(s1))
    return m


def awake_matrix(truth: Sequence[int]) -> np.ndarray:
    """The estimator rule of section 6.1: a NOT_ESTIMATED decode writes nothing, so its labelled chunks count as
    predicted AWAKE."""
    m = np.zeros((4, 4), np.int64)
    m[:, 0] = np.asarray(truth, np.int64)
    return m


PART_PIECES: dict[str, tuple[int, ...]] = {"S1": (0,), "S2": (1,), "Snap": (1,)}


def score_part(session: Mapping, rec: Mapping, epochs: tuple[np.ndarray, np.ndarray] | None, epoch_ms: int,
               pieces: Sequence[Sequence[int]], regions: Mapping[str, Sequence[Sequence[int]]], part: str,
               gap: Sequence[int] | None) -> dict:
    """The matrices of one decoded session: per (region, piece) it covers (``<region>@<piece>`` and ``piece@<p>``)
    the truth counts of its labelled chunks and, when estimated, the 4 x 4 matrix; gate G5a (the chunk scoring of all
    its labels equals algo-cli's per-session confusion) and, for M, gate G5b (every epoch whose midpoint lies in the
    forced span is AWAKE; the gap chunks of an epoch that straddles a gap boundary are counted)."""
    a, b, truth, pred = chunk_arrays(session, epochs if rec["estimated"] else None, epoch_ms)
    covers = range(len(pieces)) if part == "M" else PART_PIECES[part]
    out: dict = {"regions": {}}
    if rec["estimated"]:
        got = confusion(truth, pred)
        out["g5a"] = bool(rec["confusion"] is not None and np.array_equal(got, np.asarray(rec["confusion"])))
    else:
        out["g5a"] = None
    for p in covers:
        pm = in_spans(a, b, [pieces[p]])
        for name, spans in (*regions.items(), ("piece", [pieces[p]])):
            mask = pm & in_spans(a, b, spans)
            if not mask.any():
                continue
            out["regions"][f"{name}@{p}"] = {
                "truth": truth_counts(truth, mask).tolist(),
                "matrix": confusion(truth, pred, mask).tolist() if rec["estimated"] else None}
    if part == "M" and gap is not None:
        f: dict = {"forcedEpochs": 0, "notAwake": 0, "boundaryGapChunks": 0}
        if rec["estimated"] and epochs is not None:
            starts, stages = epochs
            mids = starts + int(epoch_ms) // 2
            forced = (mids >= gap[0]) & (mids < gap[1])
            f["forcedEpochs"] = int(forced.sum())
            f["notAwake"] = int((forced & (stages != 0)).sum())
            in_gap = (a >= gap[0]) & (b <= gap[1])
            k = np.searchsorted(starts, a + (b - a) // 2, side="right") - 1
            ok = in_gap & (k >= 0)
            f["boundaryGapChunks"] = int((ok & ~forced[np.where(k >= 0, k, 0)]).sum())
        out["forced"] = f
    return out


def pc_chunks(session: Mapping, rec: Mapping, epochs: tuple[np.ndarray, np.ndarray] | None, epoch_ms: int,
              region: Sequence[int]) -> dict:
    """The positive control's chunks of [t0 + 15 min, t1): truth and predicted codes as strings ('.' = -1), so that the
    late and the base decode can be compared on the chunks scored under both (section 5.5)."""
    a, b, truth, pred = chunk_arrays(session, epochs if rec["estimated"] else None, epoch_ms)
    m = in_spans(a, b, [region])

    def enc(x: np.ndarray) -> str:
        return "".join("." if v < 0 else str(int(v)) for v in x)

    return {"a0": int(a[m][0]) if m.any() else None, "truth": enc(truth[m]), "pred": enc(pred[m]),
            "estimated": bool(rec["estimated"])}


def _dec(s: str) -> np.ndarray:
    return np.array([-1 if c == "." else int(c) for c in s], np.int64)


def pc_matrices(late: Mapping, base: Mapping) -> tuple[np.ndarray, np.ndarray] | None:
    """(late, base) 4 x 4 matrices of a positive control on the chunks scored under both; a NOT_ESTIMATED decode counts
    its labelled chunks as AWAKE (section 6.1); None when neither is estimated."""
    if not late["estimated"] and not base["estimated"]:
        return None
    if late["truth"] != base["truth"] or late["a0"] != base["a0"]:
        raise MergeGapError("positive control: the late and the base decode differ in their chunks of the region")
    truth = _dec(late["truth"])
    pl, pb = _dec(late["pred"]), _dec(base["pred"])
    if not late["estimated"]:
        pl = np.where(truth >= 0, 0, -1)
    if not base["estimated"]:
        pb = np.where(truth >= 0, 0, -1)
    both = (pl >= 0) & (pb >= 0)
    return confusion(truth, pl, both), confusion(truth, pb, both)


# ================================================================================================= decoding ====

@dataclass(frozen=True)
class Spec:
    """One decoded session: its id, construct (or ``<night id>~mg-PC``), part (M, S1, S2, Snap, PCbase, PClate)."""
    id: str
    construct: str
    part: str
    night: str
    key: str
    subject: str
    dataset: str
    variant: str
    design: str
    g: int | None = None
    stretch: str | None = None


def enumerate_specs(variant: str, nights: Sequence[Mapping], hosts: Mapping[str, Mapping[str, dict]]) -> list[Spec]:
    """Every session of ``nights`` (one variant) that the experiment decodes, sorted by id: per hosting (night, design)
    and (g, stretch variant) of :data:`GAP_STRETCH` the parts of :func:`parts_of` (no ``presleep`` construct for a
    night without heart rate in the 30 min before t0), and the positive control of every hosting night."""
    out = []
    for night in nights:
        nid = str(night["id"])
        key = napmodel.night_key(nid)
        hs = hosts.get(key) or {}
        if not hs:
            continue
        subject, ds_ = str(night["subject"]), dataset_from_id(nid)
        has_pre = bool(presleep_values(night))
        for design, host in hs.items():
            parts = parts_of(design, piece_lengths(design, host))
            for g, h in GAP_STRETCH:
                if h == "presleep" and not has_pre:
                    continue
                cid = construct_id(nid, design, g, h)
                out += [Spec(f"{cid}~{p}", cid, p, nid, key, subject, ds_, variant, design, g, h) for p in parts]
        pid = f"{nid}~mg-PC"
        out += [Spec(f"{pid}~{x}", pid, p, nid, key, subject, ds_, variant, "PC")
                for x, p in (("base", "PCbase"), ("late", "PClate"))]
    out.sort(key=lambda s: s.id)
    if len({s.id for s in out}) != len(out):
        raise MergeGapError(f"{variant}: duplicate session ids")
    return out


def blocks_of(specs: Sequence[Spec], size: int = DECODE_BLOCK) -> list[list[Spec]]:
    """Section 4: sorted by id, consecutive blocks of at most ``size`` sessions; depends on the ids only. The
    constructs' sessions come first, then the positive controls' sessions in blocks of their own, since those are
    decoded with :func:`pc_params` (section 5.5: both decoded as nights)."""
    ordered = sorted(specs, key=lambda s: s.id)
    out: list[list[Spec]] = []
    for pc in (False, True):
        part = [s for s in ordered if (s.design == "PC") == pc]
        out += [part[i:i + size] for i in range(0, len(part), size)]
    return out


def block_params(block: Sequence[Spec], params: Mapping) -> Mapping:
    """The parameters of one decode block: the group's (``params``) for constructs, :func:`pc_params` for positive
    controls; a block mixing both is an error."""
    kinds = {s.design == "PC" for s in block}
    if len(kinds) > 1:
        raise MergeGapError("a decode block mixes constructs and positive controls")
    return pc_params(params) if kinds == {True} else params


def _session_bytes(sessions: Sequence[Mapping]) -> bytes:
    return b"".join(json.dumps(s, separators=(",", ":"), allow_nan=False).encode("utf-8") + b"\n" for s in sessions)


def write_session_file(path: Path, sessions: Sequence[Mapping]) -> dict:
    """Writes a session file (compact JSON lines; ids unique) and re-reads it byte for byte (gate G3)."""
    pools.assert_unique_ids(sessions)
    data = _session_bytes(sessions)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    back = path.read_bytes()
    return {"sha256": hashlib.sha256(data).hexdigest(), "sessions": len(sessions), "bytes": len(data),
            "reReadIdentical": back == data}


@dataclass
class Material:
    """The sessions of one block, with the construct facts the scoring needs."""
    sessions: dict[str, dict]                      # session id -> session
    facts: dict[str, dict]                         # construct id -> pieces, gap, regions, ...
    g3: list[str]                                  # construct problems (gate G3)
    g6: list[str]                                  # arm-map problems (gate G6)
    digests: dict[str, str]                        # construct id -> variant-invariant digest
    blocks: dict[str, list[int]] = field(default_factory=dict)   # construct id -> block order (bout start - t0)


def construct_facts(c: Construct) -> dict:
    lengths = tuple(b - a for a, b in c.pieces)
    sleep_before = sum(1 for a, b, s in c.labels if s in SLEEP_STAGES and a >= c.pieces[0][0] and b <= c.pieces[0][1])
    return {"pieces": [list(p) for p in c.pieces], "gap": list(c.gap), "regions": {k: [list(x) for x in v]
                                                                                  for k, v in c.regions.items()},
            "lengths": list(lengths), "coveredBeforeGapMs": lengths[0], "sleepBeforeGapMs": sleep_before * CHUNK_MS,
            "stretchSpan": list(c.stretch_span), "host": {k: c.host[k] for k in ("b0", "b1", "w", "lam1", "lam2")}}


def invariant_digest(c: Construct) -> str:
    """What both sampling variants must share (G3): boundaries, labels, step records, the stretch and the regions."""
    obj = {"pieces": c.pieces, "gap": c.gap, "labels": c.labels, "steps": c.steps, "stretch": c.stretch_span,
           "regions": c.regions}
    return hashlib.sha256(json.dumps(obj, separators=(",", ":"), default=list).encode("utf-8")).hexdigest()


def forced_span(design: str, g: int, host: Mapping) -> tuple[int, int]:
    """Section 5.6, restated from the host for gate G3: the forced-AWAKE span of arm M, [e1, s2] with e1 = b0 + 5 min
    and s2 = b1 + D - 5 min, D = g + 10 min - w (V1); [b0 - Delta + 5 min, t0] with Delta = (b1 - t0) + D',
    D' = g + 5 min - w (V2a); [t1, t1 + g] (V2b)."""
    b0, b1, t0, t1 = (int(host[k]) for k in ("b0", "b1", "t0", "t1"))
    w, gms = b1 - b0, int(g) * MIN
    k = kind_of(design)
    if k in ("END", "START", "MID"):
        return b0 + MARGIN_MS, b1 + (gms + 10 * MIN - w) - MARGIN_MS
    if k == "DOZE":
        return b0 - ((b1 - t0) + gms + 5 * MIN - w) + MARGIN_MS, t0
    if k == "NAPAFTER":
        return t1, t1 + gms
    raise ValueError(f"unknown design {design}")


def check_sessions(c: Construct, built: Mapping[str, Mapping]) -> list[str]:
    """Gate G3 for the sessions of one construct (section 5.6): the M session's ``forcedAwake`` is exactly [gap], the
    gap as 5.6 states it from the host, and inside M's span; S1, S2 and S' have no forced-AWAKE span."""
    p: list[str] = []
    want = list(forced_span(c.design, c.g, c.host))
    if list(c.gap) != want:
        p.append(f"gap {list(c.gap)} != section 5.6's {want}")
    m = built.get("M")
    if m is not None:
        if m.get("forcedAwake") != [want]:
            p.append(f"M forcedAwake {m.get('forcedAwake')} != [{want}]")
        if not m["sessionStartMs"] <= want[0] < want[1] <= m["sessionEndMs"]:
            p.append("the forced span is not inside the M session")
    p += [f"{part} has a forced-AWAKE span" for part, s in built.items() if part != "M" and "forcedAwake" in s]
    return [f"{c.id}: {x}" for x in p]


def expected_arm(design: str, g: int, arm: str) -> bool:
    """Section 5.6: which constructs each arm merges (gate G6)."""
    k = kind_of(design)
    return {"A": False, "B": g < 60, "C": g < 60 and k in ("END", "MID", "NAPAFTER"), "R45": g < 45,
            "R90": g < 90}[arm]


def check_construct(c: Construct, night: Mapping) -> list[str]:
    """Gate G3 for one construct: the inter-session gap is g exactly; the pieces' lengths are in the design's band;
    every original label, heart-rate sample and step record is present (shifted exactly as sections 5.2-5.4 say; DOZE:
    the 30 min before t0 copied before the doze); everything else lies in the stretch (AWAKE 30-s labels; heart rate on
    the 2-min grid with the stretch variant's values; step records only for ``walk``)."""
    p: list[str] = []
    t0, t1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])
    b1 = int(c.host["b1"])
    k = kind_of(c.design)
    if c.gap[1] - c.gap[0] != c.g * MIN:
        p.append(f"gap {c.gap[1] - c.gap[0]} ms != {c.g} min")
    lengths = [b - a for a, b in c.pieces]
    frag = {"END": 1, "START": 0, "DOZE": 0, "NAPAFTER": 1}.get(k)
    if k == "MID":
        if min(lengths) < NIGHT_MIN_MS:
            p.append(f"MID pieces {lengths}")
    else:
        f = int(c.design.split("-")[1])
        if not in_band(lengths[frag], f):
            p.append(f"fragment {lengths[frag]} ms outside band {f}")
        if lengths[1 - frag] < NIGHT_MIN_MS:
            p.append(f"main piece {lengths[1 - frag]} ms < 180 min")
    x0, x1 = c.stretch_span
    labs = {tuple(x) for x in c.labels}
    orig = _labels(night)
    want: set[tuple] = set()
    if k in ("END", "START", "MID"):
        want = {(a + c.shift, b + c.shift, s) if a >= b1 else (a, b, s) for a, b, s in orig}
    elif k == "DOZE":
        want = set(orig) | {(a + c.shift, b + c.shift, s) for a, b, s in orig if a >= t0 and b <= b1}
    else:
        want = set(orig) | {(a + c.shift, b + c.shift, s) for a, b, s in orig if a >= t0 and b <= b1}
    missing = want - labs
    extra = labs - want
    if missing:
        p.append(f"{len(missing)} original labels missing")
    if any(not (x0 <= a and b <= x1 and s == "AWAKE" and b - a == CHUNK_MS) for a, b, s in extra):
        p.append("a synthetic label outside the stretch or not a 30-s AWAKE interval")
    if len(extra) != (x1 - x0) // CHUNK_MS:
        p.append(f"{len(extra)} synthetic labels for a {x1 - x0}-ms stretch")
    hr = {int(t): float(v) for t, v in c.hr}
    if len(hr) != len(c.hr):
        p.append("duplicate heart-rate times")
    o_hr = _hr(night)
    if k in ("END", "START", "MID"):
        want_hr = {(t + c.shift if t >= b1 else t): v for t, v in o_hr}
    elif k == "DOZE":
        want_hr = {t: v for t, v in o_hr if t >= t0}
        want_hr.update({t + c.shift: v for t, v in o_hr if t0 - CONTEXT_MS <= t < b1})
    else:
        want_hr = {t: v for t, v in o_hr if t < t1}
        want_hr.update({t + c.shift: v for t, v in o_hr if t0 <= t < b1})
    if any(hr.get(t) != v for t, v in want_hr.items()):
        p.append("an original heart-rate sample is missing or changed")
    synth = sorted(t for t in hr if t not in want_hr)
    if any(not x0 <= t < x1 - MIN for t in synth):
        p.append("a synthetic heart-rate sample outside the stretch")
    if any(synth[i] - synth[i - 1] != HR_STEP_MS for i in range(1, len(synth))):
        p.append("synthetic heart rate not on a 2-min grid")
    if c.stretch == "nohr" and synth:
        p.append("nohr stretch with heart rate")
    if c.stretch in ("base", "hr10", "walk") and synth:
        vals = pool_values(night, c.design)
        add = HR10_BPM if c.stretch == "hr10" else 0.0
        if any(hr[t] != vals[i % len(vals)] + add for i, t in enumerate(synth)):
            p.append(f"{c.stretch} stretch values differ from the wake pool in block order")
    if c.stretch == "presleep" and synth:
        vals = presleep_values(night)
        if any(hr[t] != vals[i % len(vals)] for i, t in enumerate(synth)):
            p.append("presleep stretch values differ from the pre-sleep samples")
    steps = {tuple(r) for r in c.steps}
    o_steps = [tuple(r) for r in _steps(night)]
    if k in ("END", "START", "MID"):
        want_st = {(a + c.shift, b + c.shift, n) if a >= b1 else (a, b, n) for a, b, n in o_steps}
    elif k == "DOZE":
        want_st = {r for r in o_steps if r[0] >= t0} | {(a + c.shift, b + c.shift, n) for a, b, n in o_steps
                                                        if a >= t0 - CONTEXT_MS and b <= b1}
    else:
        want_st = {r for r in o_steps if r[0] < t1} | {(a + c.shift, b + c.shift, n) for a, b, n in o_steps
                                                       if a >= t0 and b <= b1}
    if want_st - steps:
        p.append("an original step record is missing")
    extra_st = steps - want_st
    if extra_st and (c.stretch != "walk" or any(not (x0 <= a and b < x1 - 5 * MIN and n == WALK_STEPS)
                                                 for a, b, n in extra_st)):
        p.append("a synthetic step record outside a walk stretch")
    return [f"{c.id}: {x}" for x in p]


def materialize(inp_night: Callable[[str], Mapping], block: Sequence[Spec], hosts: Mapping[str, Mapping[str, dict]],
                check: bool = True) -> Material:
    """The sessions of ``block`` (built from the night files through ``inp_night``), with the facts of their
    constructs, the G3 and G6 findings and the variant-invariant digests."""
    sessions: dict[str, dict] = {}
    facts: dict[str, dict] = {}
    g3: list[str] = []
    g6: list[str] = []
    digests: dict[str, str] = {}
    blocks: dict[str, list[int]] = {}
    by_construct: dict[str, list[Spec]] = defaultdict(list)
    for s in block:
        by_construct[s.construct].append(s)
    for cid, ss in by_construct.items():
        s0 = ss[0]
        night = inp_night(s0.night)
        if s0.design == "PC":
            ps = pc_sessions(night)
            t0, t1 = int(night["sessionStartMs"]), int(night["sessionEndMs"])
            facts[cid] = {"pc": [t0 + PC_LATE_MS, t1]}
            if check:
                g3 += check_pc(ps, night)
            for s in ss:
                sessions[s.id] = ps[s.part]
            continue
        host = hosts[s0.key][s0.design]
        c = build_construct(night, s0.design, int(s0.g), str(s0.stretch), host)
        if c is None:
            raise MergeGapError(f"{cid}: no construct (presleep without pre-sleep heart rate)")
        facts[cid] = construct_facts(c)
        digests[cid] = invariant_digest(c)
        blocks[cid] = block_order(night, c.design)
        if check:
            g3 += check_construct(c, night)
            for arm in ARM_ORDER:
                if merged(c.pieces, arm) != expected_arm(c.design, c.g, arm):
                    g6.append(f"{cid}: arm {arm} groups it {'merged' if merged(c.pieces, arm) else 'split'}")
        built = construct_sessions(c, [s.part for s in ss])
        if check:
            g3 += check_sessions(c, built)
        for s in ss:
            if s.part not in built:
                raise MergeGapError(f"{s.id}: part not built")
            sessions[s.id] = built[s.part]
    return Material(sessions=sessions, facts=facts, g3=g3, g6=g6, digests=digests, blocks=blocks)


def score_block(block: Sequence[Spec], mat: Material, recs: Mapping[str, Mapping],
                epochs: Mapping[str, tuple[np.ndarray, np.ndarray]], epoch_ms: int) -> tuple[list[dict], list[dict]]:
    """The region rows (constructs) and the positive-control rows (one per decode) of one decoded block."""
    rows, pcs = [], []
    for s in block:
        rec = recs[s.id]
        sess = mat.sessions[s.id]
        ep = epochs.get(s.id)
        if rec["estimated"] and ep is None:
            raise MergeGapError(f"{s.id}: estimated but no per-epoch rows")
        base = {"id": s.id, "construct": s.construct, "part": s.part, "night": s.night, "subject": s.subject,
                "dataset": s.dataset, "variant": s.variant, "design": s.design, "g": s.g, "stretch": s.stretch,
                "estimated": bool(rec["estimated"]), "reason": rec["reason"], "estimatorUsed": rec["estimatorUsed"],
                "warnings": list(rec["warnings"]), "mode": rec.get("mode")}
        f = mat.facts[s.construct]
        if s.design == "PC":
            ch = pc_chunks(sess, rec, ep, epoch_ms, f["pc"])
            if rec["estimated"]:
                a, b, truth, pred = chunk_arrays(sess, ep, epoch_ms)
                ch["g5a"] = bool(np.array_equal(confusion(truth, pred), np.asarray(rec["confusion"])))
            else:
                ch["g5a"] = None
            pcs.append({**base, **ch})
            continue
        sc = score_part(sess, rec, ep, epoch_ms, f["pieces"], f["regions"], s.part, f["gap"] if s.part == "M" else None)
        row = {**base, **sc}
        if s.part == "M":
            row["facts"] = f
        rows.append(row)
    return rows, pcs


def pair_pcs(pcs: Iterable[Mapping]) -> dict[tuple[str, str], dict]:
    """The positive-control rows by (night, variant) with both decodes, ``{"base": row, "late": row}``."""
    out: dict[tuple[str, str], dict] = defaultdict(dict)
    for r in pcs:
        out[(r["night"], r["variant"])]["base" if r["part"] == "PCbase" else "late"] = r
    bad = [k for k, v in out.items() if set(v) != {"base", "late"}]
    if bad:
        raise MergeGapError(f"positive controls without both decodes: {bad[:3]}")
    return dict(out)


def epoch_ms_of(params: Mapping) -> int:
    return int((params.get("preprocess") or {}).get("epochSec", EPOCH_SEC_DEFAULT)) * 1000


def session_modes(metrics: Mapping) -> dict[str, str | None]:
    """The mode (NIGHT / NAP) algo-cli gives each session of a metrics block (``perNight`` and ``skipped`` carry it,
    docs/INTERCHANGE.md section 2)."""
    return {str(n["id"]): n.get("mode") for n in [*metrics.get("perNight", []), *metrics.get("skipped", [])]
            if isinstance(n, Mapping) and "id" in n}


def decode_block(cli: AlgoCli, block: Sequence[Spec], mat: Material, params: Mapping, fold_dir: Path | None,
                 work: Path, name: str, estimator: str = "HYBRID", model: Path | None = None) -> dict:
    """One algo-cli call on the sessions of ``block`` (in id order) with ``--per-epoch``: session file (re-read byte for
    byte, deleted afterwards, its digest kept), per-session records (``napmodel.tag``), per-epoch rows, scoring; every
    file of the call is deleted when its rows are made."""
    sessions = [mat.sessions[s.id] for s in block]
    sf = work / f"{name}.sessions.jsonl"
    pe = work / f"{name}.per-epoch.csv"
    info = write_session_file(sf, sessions)
    meta = {s.id: {"construct": s.construct, "part": s.part, "subject": s.subject} for s in block}
    try:
        recs = napsrealistic.evaluate(cli, work / f"{name}.json", sf, meta, estimator, params, fold_models=fold_dir,
                                      model=model, per_epoch=pe, keep_metrics=True)
        modes = session_modes(read_json(work / f"{name}.json"))
        by_id = {r["night"]: dict(r, mode=modes.get(r["night"])) for r in recs}
        epochs = read_epochs([pe], set(by_id)) if pe.is_file() else {}
        rows, pcs = score_block(block, mat, by_id, epochs, epoch_ms_of(params))
    finally:
        sf.unlink(missing_ok=True)
        pe.unlink(missing_ok=True)
        (work / f"{name}.json").unlink(missing_ok=True)
        (work / f"{name}.params.json").unlink(missing_ok=True)
    return {"rows": rows, "pcs": pcs, "file": info}


# =============================================================================================== statistics ====

CELLS: dict[str, dict] = {
    "V1a-late": {"designs": ("END-25", "END-60", "END-100", "END-140", "MID"), "region": "main", "metric": "kappa",
                 "margin": -0.02, "arms": ("B", "C"), "comparator": "S", "pc": True},
    "V1a-early": {"designs": ("START-25", "START-60", "START-100", "START-140"), "region": "main", "metric": "kappa",
                  "margin": -0.02, "arms": ("B",), "comparator": "S", "pc": True},
    "V1b-late": {"designs": ("END-100", "END-140"), "region": "fragment", "metric": "swacc", "margin": -0.05,
                 "arms": ("B", "C"), "comparator": "S", "pc": False},
    "V1b-early": {"designs": ("START-100", "START-140"), "region": "fragment", "metric": "swacc", "margin": -0.05,
                  "arms": ("B",), "comparator": "S", "pc": False},
    "V2a": {"designs": ("DOZE-25", "DOZE-60", "DOZE-100"), "region": "main", "metric": "kappa", "margin": -0.02,
            "arms": ("B",), "comparator": "S", "pc": True},
    "V2b-night": {"designs": ("NAPAFTER-60", "NAPAFTER-100"), "region": "main", "metric": "kappa", "margin": -0.02,
                  "arms": ("B", "C"), "comparator": "S", "pc": True},
    "V2b-nap": {"designs": ("NAPAFTER-60", "NAPAFTER-100"), "region": "fragment", "metric": "kappa", "margin": -0.05,
                "arms": ("B", "C"), "comparator": "S'", "pc": False},
}
CELL_ORDER: tuple[str, ...] = tuple(CELLS)
MIN_DATASET_SUBJECTS = 10            # a dataset with fewer subjects in a (cell, v, h) is left out
MIN_EVALUABLE = 30                   # a cell is evaluable with at least 30 entering subjects in every (v, h)
DATASET_MEAN_MIN = 20                # a dataset's own mean is a point check from 20 subjects on
PC_MARGIN = -0.02                    # the positive control is detected when its lower bound is below this
N_BOOT = report.BOOTSTRAP_SAMPLES
SEED = 0


def metric_of(c: np.ndarray, metric: str) -> float:
    """4-class Cohen's kappa, or the sleep / wake accuracy (trace of the 2 x 2 collapse / total); NaN when undefined."""
    c = np.asarray(c, float)
    if metric == "kappa":
        return report.kappa(c)
    if metric == "swacc":
        c2 = napmodel.collapse_sleep_wake(c)
        n = c2.sum()
        return float(np.trace(c2) / n) if n > 0 else float("nan")
    raise ValueError(metric)


def comparator_part(comparator: str, piece: int) -> str:
    return "Snap" if comparator == "S'" else f"S{piece + 1}"


def region_pair(parts: Mapping[str, Mapping], region: str, comparator: str, counts: Counter | None = None
                ) -> tuple[np.ndarray, np.ndarray] | None:
    """The 4 x 4 matrices of ``region`` under M and the comparator (S: the piece's own decode; S': ``Snap``), summed
    over the pieces the region lies in, with the estimator rule of section 6.1: a NOT_ESTIMATED decode against an
    estimated one counts its labelled chunks as AWAKE; a (region, piece) that neither estimated is excluded (counted); a
    piece the comparator does not decode is excluded (counted). None when nothing is left."""
    counts = counts if counts is not None else Counter()
    m = parts.get("M")
    if m is None:
        counts["noMergedDecode"] += 1
        return None
    tot_x, tot_y, used = np.zeros((4, 4), np.int64), np.zeros((4, 4), np.int64), False
    for key, rx in sorted(m["regions"].items()):
        name, p = key.split("@")
        if name != region:
            continue
        row_y = parts.get(comparator_part(comparator, int(p)))
        if row_y is None:
            counts["comparatorNotDecoded"] += 1
            continue
        ry = row_y["regions"].get(key)
        if ry is None:
            counts["comparatorRegionMissing"] += 1
            continue
        ex, ey = bool(m["estimated"]), bool(row_y["estimated"])
        if not ex and not ey:
            counts["neitherEstimated"] += 1
            continue
        if ex != ey:
            counts["oneNotEstimated"] += 1
        tot_x += np.asarray(rx["matrix"], np.int64) if ex else awake_matrix(rx["truth"])
        tot_y += np.asarray(ry["matrix"], np.int64) if ey else awake_matrix(ry["truth"])
        used = True
    return (tot_x, tot_y) if used else None


class ConstructIndex:
    """The decoded parts of every construct (``construct id -> part -> row``) with lookups by (variant, stretch, g,
    design)."""

    def __init__(self, rows: Iterable[Mapping]):
        self.parts: dict[str, dict[str, Mapping]] = defaultdict(dict)
        for r in rows:
            self.parts[r["construct"]][r["part"]] = r
        self.by: dict[tuple, list[str]] = defaultdict(list)
        for cid, ps in self.parts.items():
            m = ps.get("M")
            if m is not None:
                self.by[(m["variant"], m["stretch"], m["g"], m["design"])].append(cid)
        for v in self.by.values():
            v.sort()

    def ids(self, variant: str, stretch: str, gaps: Iterable[int], designs: Iterable[str]) -> list[str]:
        return [c for g in gaps for d in designs for c in self.by.get((variant, stretch, g, d), [])]

    def merged_row(self, cid: str) -> Mapping:
        return self.parts[cid]["M"]


def cell_matrices(idx: ConstructIndex, cell: Mapping, variant: str, stretch: str, gaps: Sequence[int],
                  designs: Sequence[str] | None = None) -> tuple[dict[str, dict[int, list[np.ndarray]]], Counter]:
    """Per subject and gap, the summed matrices [M, comparator] of the cell's region over the subject's constructs of
    the cell's designs (or ``designs``)."""
    out: dict[str, dict[int, list[np.ndarray]]] = defaultdict(dict)
    counts: Counter = Counter()
    for cid in idx.ids(variant, stretch, gaps, designs or cell["designs"]):
        parts = idx.parts[cid]
        m = parts["M"]
        pair = region_pair(parts, cell["region"], cell["comparator"], counts)
        if pair is None:
            counts["constructsExcluded"] += 1
            continue
        acc = out[m["subject"]].setdefault(int(m["g"]), [np.zeros((4, 4), np.int64), np.zeros((4, 4), np.int64)])
        acc[0] += pair[0]
        acc[1] += pair[1]
        counts["constructs"] += 1
    return dict(out), counts


def subject_deltas(mats: Mapping[str, Mapping[int, Sequence[np.ndarray]]], metric: str, gaps: Sequence[int]
                   ) -> tuple[dict[str, float], dict[int, dict[str, float]], list[str]]:
    """Delta_s,g = metric(M) - metric(comparator); Delta_s = their mean over ``gaps``; a subject whose metric is
    undefined under either arm at any gap (or without constructs at a gap) is dropped."""
    deltas: dict[str, float] = {}
    per_g: dict[int, dict[str, float]] = {g: {} for g in gaps}
    dropped = []
    for s in sorted(mats):
        vals = {}
        for g in gaps:
            if g not in mats[s]:
                break
            x, y = metric_of(mats[s][g][0], metric), metric_of(mats[s][g][1], metric)
            if not (math.isfinite(x) and math.isfinite(y)):
                break
            vals[g] = x - y
        if len(vals) != len(gaps):
            dropped.append(s)
            continue
        deltas[s] = float(np.mean([vals[g] for g in gaps]))
        for g in gaps:
            per_g[g][s] = vals[g]
    return deltas, per_g, dropped


def stratified_paired_bootstrap(strata: Mapping[str, Mapping[str, float]], n_boot: int = N_BOOT, seed: int = SEED,
                                level: float = 0.95) -> dict:
    """Section 6.2: ``numpy.random.default_rng(seed)`` anew; per resample the subjects of each stratum (Walch first,
    then BIDSleep; subjects in id order) are drawn with replacement among themselves (one n_boot x n_d index array per
    stratum, in that order) and the statistic is the mean over all drawn subjects; percentile interval. With one
    stratum it equals ``report.paired_bootstrap`` of the same differences."""
    order = [d for d in DATASETS if d in strata] + sorted(d for d in strata if d not in DATASETS)
    arrays = [np.array([float(strata[d][s]) for s in sorted(strata[d])]) for d in order if strata[d]]
    n = int(sum(len(a) for a in arrays))
    if n == 0:
        return {"n": 0, "mean": float("nan"), "ciLow": float("nan"), "ciHigh": float("nan"), "level": level}
    rng = np.random.default_rng(seed)
    total = np.zeros(int(n_boot))
    for a in arrays:
        total += a[rng.integers(0, len(a), size=(int(n_boot), len(a)))].sum(axis=1)
    means = total / n
    lo, hi = np.quantile(means, [(1 - level) / 2, 1 - (1 - level) / 2])
    return {"n": n, "mean": float(np.mean(np.concatenate(arrays))), "ciLow": float(lo), "ciHigh": float(hi),
            "level": level}


def _mean(xs: Iterable[float]) -> float:
    xs = list(xs)
    return float(np.mean(xs)) if xs else float("nan")


def summarize_deltas(deltas: Mapping[str, float], dataset_of: Callable[[str], str], seed: int = SEED) -> dict:
    """The dataset rule (a dataset with fewer than 10 subjects is left out; its mean is reported), the mean over the
    entering subjects (each one unit) and the stratified bootstrap interval."""
    by_ds: dict[str, dict[str, float]] = defaultdict(dict)
    for s, v in deltas.items():
        by_ds[dataset_of(s)][s] = v
    entering = {d: x for d, x in by_ds.items() if len(x) >= MIN_DATASET_SUBJECTS}
    boot = stratified_paired_bootstrap(entering, seed=seed)
    return {"n": boot["n"], "mean": boot["mean"], "ciLow": boot["ciLow"], "ciHigh": boot["ciHigh"],
            "datasets": {d: {"n": len(x), "mean": _mean(x.values()), "entered": d in entering}
                         for d, x in sorted(by_ds.items())},
            "subjects": sorted(s for x in entering.values() for s in x)}


def cell_variant(idx: ConstructIndex, cell: Mapping, variant: str, stretch: str, dataset_of: Callable[[str], str],
                 gaps: Sequence[int] = DECISIVE_GAPS, positions: bool = False) -> dict:
    """One (cell, sampling variant, stretch variant): the per-subject changes, the dataset rule, the mean, the interval
    and the point checks (the mean at each gap; with ``positions`` the END designs' and the MID design's means; every
    entering dataset with at least 20 subjects its own mean)."""
    mats, counts = cell_matrices(idx, cell, variant, stretch, gaps)
    deltas, per_g, dropped = subject_deltas(mats, cell["metric"], gaps)
    rec = summarize_deltas(deltas, dataset_of)
    entering = set(rec.pop("subjects"))
    checks: dict[str, float] = {}
    for g in gaps:
        checks[f"g{g}"] = _mean(v for s, v in per_g[g].items() if s in entering)
    if positions:
        for name, ds_ in (("END", [d for d in cell["designs"] if d.startswith("END-")]),
                          ("MID", [d for d in cell["designs"] if d == "MID"])):
            pm, _ = cell_matrices(idx, cell, variant, stretch, gaps, ds_)
            pd_, _, _ = subject_deltas(pm, cell["metric"], gaps)
            checks[f"position-{name}"] = _mean(v for s, v in pd_.items() if s in entering)
    for d, q in rec["datasets"].items():
        if q["entered"] and q["n"] >= DATASET_MEAN_MIN:
            checks[f"dataset-{d}"] = q["mean"]
    rec.update({"variant": variant, "stretch": stretch, "gaps": list(gaps), "dropped": len(dropped),
                "counts": dict(counts), "pointChecks": checks, "perGap": {str(g): {"n": len(per_g[g]),
                                                                                   "mean": _mean(per_g[g].values())}
                                                                          for g in gaps}})
    return rec


def pc_variant(idx: ConstructIndex, pcs: Mapping[tuple[str, str], Mapping], cell: Mapping, variant: str,
               dataset_of: Callable[[str], str], designs: Sequence[str] | None = None) -> dict:
    """The positive control of a cell in one sampling variant: per subject, kappa of the summed late matrices minus
    kappa of the summed base matrices over the subject's host nights of the cell's designs (each night once); the same
    dataset rule, mean and bootstrap (rng anew)."""
    nights: dict[str, set[str]] = defaultdict(set)
    for (v, _, _, d), cids in idx.by.items():
        if v == variant and d in (designs or cell["designs"]):
            for cid in cids:
                m = idx.parts[cid]["M"]
                nights[m["subject"]].add(m["night"])
    deltas: dict[str, float] = {}
    excluded = 0
    for s, ns in sorted(nights.items()):
        late, base, used = np.zeros((4, 4), np.int64), np.zeros((4, 4), np.int64), False
        for n in sorted(ns):
            pair = pcs.get((n, variant))
            mats = pc_matrices(pair["late"], pair["base"]) if pair is not None else None
            if mats is None:
                excluded += 1
                continue
            late += mats[0]
            base += mats[1]
            used = True
        if not used:
            continue
        x, y = metric_of(late, "kappa"), metric_of(base, "kappa")
        if math.isfinite(x) and math.isfinite(y):
            deltas[s] = x - y
    rec = summarize_deltas(deltas, dataset_of)
    rec.pop("subjects")
    rec.update({"variant": variant, "nightsExcluded": excluded, "detected": bool(rec["ciLow"] < PC_MARGIN)})
    return rec


def cell_outcome(name: str, per_vh: Sequence[Mapping], pc: Sequence[Mapping] | None) -> dict:
    """Section 6.2: pass = evaluable (at least 30 entering subjects in every (v, h)), the lowest lower bound at least
    the margin, every point check at least the margin and (night cells) the positive control detected in both variants;
    harm shown = a mean or a point check below the margin in some (v, h); otherwise non-inferiority not shown (with
    its reasons)."""
    margin = CELLS[name]["margin"]
    lbs = [q["ciLow"] for q in per_vh]
    lb = float(min(lbs)) if lbs and all(math.isfinite(x) for x in lbs) else float("nan")
    evaluable = bool(per_vh) and all(q["n"] >= MIN_EVALUABLE for q in per_vh)
    checks = [(f"{q['variant']}/{q['stretch']}/{k}", v) for q in per_vh for k, v in q["pointChecks"].items()]
    means = [(f"{q['variant']}/{q['stretch']}/mean", q["mean"]) for q in per_vh]
    below = [(k, v) for k, v in means + checks if math.isfinite(v) and v < margin]
    undefined = [k for k, v in means + checks if not math.isfinite(v)]
    pc_ok = None if pc is None else bool(pc) and all(q["detected"] for q in pc)
    passed = (evaluable and math.isfinite(lb) and lb >= margin and not below and not undefined
              and (pc_ok is None or pc_ok))
    if passed:
        outcome, reasons = "pass", []
    elif below:
        outcome = "harm shown"
        reasons = [f"{k} = {v:+.4f} < {margin}" for k, v in below]
    else:
        outcome = "non-inferiority not shown"
        reasons = []
        if not evaluable:
            reasons.append("not evaluable (entering subjects " + ", ".join(
                f"{q['variant']}/{q['stretch']} {q['n']}" for q in per_vh) + f"; at least {MIN_EVALUABLE} needed)")
        if not math.isfinite(lb) or lb < margin:
            reasons.append(f"lower bound {lb:+.4f} below the margin {margin}")
        if undefined:
            reasons.append("undefined: " + ", ".join(undefined))
        if pc_ok is False:
            reasons.append("positive control not detected (lower bounds " + ", ".join(
                f"{q['variant']} {q['ciLow']:+.4f}" for q in pc) + f"; must be below {PC_MARGIN} in both)")
    return {"cell": name, "margin": margin, "lowerBound": lb, "evaluable": evaluable, "positiveControl": pc_ok,
            "outcome": outcome, "passed": bool(passed), "reasons": reasons}


def compute_cells(idx: ConstructIndex, pcs: Mapping[tuple[str, str], Mapping], dataset_of: Callable[[str], str],
                  variants: Sequence[str] = VARIANTS) -> dict:
    """Every decisive cell of section 6.2 with its outcome."""
    out = {}
    for name in CELL_ORDER:
        cell = CELLS[name]
        per_vh = [cell_variant(idx, cell, v, h, dataset_of, positions=(name == "V1a-late"))
                  for v in variants for h in DECISIVE_STRETCHES]
        pc = [pc_variant(idx, pcs, cell, v, dataset_of) for v in variants] if cell["pc"] else None
        out[name] = {"definition": {k: (list(x) if isinstance(x, tuple) else x) for k, x in cell.items()},
                     "perVariant": per_vh, "positiveControlRecords": pc, **cell_outcome(name, per_vh, pc)}
    return out


# ======================================================================================== V3, V4, U1, rule ====

V3_SETS = ("V3.1", "V3.2", "V3.3")
V3_UPLOAD_PATTERNS: tuple[int, ...] = (15, 60, 180)
V3B_UPLOAD_MIN = 60
V31_REPLAYS = 6


def _replay_ok_final(r: Mapping) -> bool:
    return bool(r.get("finalNights")) and all(n["importedFinal"] for n in r["finalNights"]) and not r.get("notices")


def evaluate_v3(rep: Mapping, d_max: int | None, arms: Sequence[str] = ARM_ORDER) -> dict:
    """Section 9.2 from the replay report of ``MergeGapGateReplayTest``: V3a per arm (the regression check: (i) V3.1
    and V3.3 end with PulseStages' record of the final version and no notice, (ii) V3.2: every final night imported or
    noticed, (iii) no replay threw), V3b (the median over the six V3.1 replays at hourly uploads of the first request
    under the arm minus under A, minutes; a replay without a request counts as infinite), U (V3b <= D_max) and V3c (what
    Sleep as Android ends with in the V3.2 replays)."""
    replays = list(rep.get("replays", []))
    out: dict = {"replays": len(replays), "V3a": {}, "V3b": {}, "U": {}, "V3c": {}, "dMax": d_max}
    first = {(r["arm"], r["scenario"], bool(r.get("walks")), r.get("uploadEveryMin")):
             (r.get("requestTimes") or [None])[0] for r in replays if r["set"] == "V3.1"}
    for arm in arms:
        rs = [r for r in replays if r["arm"] == arm]
        errors = [f"{r['scenario']} P{r.get('uploadEveryMin')} g{r.get('g')} d{r.get('d')}: {r['error']}" for r in rs
                  if r.get("error")]
        i_fail = [f"{r['scenario']}{' walks' if r.get('walks') else ''} P{r.get('uploadEveryMin')}" for r in rs
                  if r["set"] in ("V3.1", "V3.3") and not r.get("error") and not _replay_ok_final(r)]
        ii_fail = [f"g{r.get('g')} d{r.get('d')} P{r.get('uploadEveryMin')}" for r in rs
                   if r["set"] == "V3.2" and not r.get("error")
                   and not all(n["importedFinal"] or n["noticed"] for n in r.get("finalNights", []))]
        expected = len(V3_UPLOAD_PATTERNS) * (V31_REPLAYS + 10 + 1)
        out["V3a"][arm] = {"replays": len(rs), "expectedReplays": expected, "threw": errors, "finalNotImported": i_fail,
                           "changedNotImportedNorNoticed": ii_fail,
                           "passed": bool(rs) and len(rs) == expected and not errors and not i_fail and not ii_fail}
        diffs = []
        for (a, sc, w, p), t in sorted(first.items(), key=lambda kv: (kv[0][1], kv[0][2])):
            if a != arm or p != V3B_UPLOAD_MIN:
                continue
            t_a = first.get(("A", sc, w, p))
            diffs.append(float("inf") if t is None or t_a is None else (t - t_a) / MIN)
        v3b = float(statistics.median(diffs)) if len(diffs) == V31_REPLAYS else float("nan")
        out["V3b"][arm] = {"medianAddedDelayMin": v3b, "perReplayMin": diffs}
        out["U"][arm] = bool(d_max is not None and v3b <= d_max)        # inf and NaN never hold
        cnt: Counter = Counter()
        for r in rs:
            if r["set"] != "V3.2" or r.get("error"):
                continue
            main = [x for x in r.get("saaRecords", []) if x["startMs"] <= r["nightStartMs"] <= x["endMs"]]
            end = main[0]["endMs"] if len(main) == 1 else None
            what = "fragment" if end == r["fragmentEndMs"] else ("first" if end == r["firstPieceEndMs"] else "neither")
            cnt[(what, bool(r.get("notices")))] += 1
        tot = sum(cnt.values())
        out["V3c"][arm] = {f"{w}{'+notice' if n else ''}": (cnt[(w, n)] / tot if tot else float("nan"))
                           for w in ("fragment", "first", "neither") for n in (False, True)}
        out["V3c"][arm]["replays"] = tot
    return out


def v4_record() -> dict:
    """Section 10: each arm applied to the labelled pair; L_A must be false, L_B = L_C = L_R45 = L_R90 true (G6)."""
    got = {arm: merged([(s["startMs"], s["endMs"]) for s in LABELLED_PAIR], arm) for arm in ARM_ORDER}
    want = {arm: arm != "A" for arm in ARM_ORDER}
    gap = (LABELLED_PAIR[1]["startMs"] - LABELLED_PAIR[0]["endMs"]) / MIN
    covered = (LABELLED_PAIR[0]["endMs"] - LABELLED_PAIR[0]["startMs"]) / MIN
    return {"pair": [dict(s) for s in LABELLED_PAIR], "gapMin": gap, "coveredBeforeGapMin": covered,
            "continuation": got, "expected": want, "passed": got == want}


def u1_record(log_path: Path | None) -> dict:
    """Section 9.2 / G0: exactly one ``NOTE merge-gap U1 DMAX <minutes>: <answer>`` line, after the pre-registration
    NOTE and before the first START of ``train.py mergegap`` (that subcommand itself)."""
    lines = Path(log_path).read_text(encoding="utf-8").splitlines() if log_path and Path(log_path).is_file() else []
    stamped = [(i, x) for i, x in enumerate(lines) if ds.STAMP_RE.match(x)]
    pre = next((i for i, x in stamped if f" {PREREG_NOTE}" in x), None)
    u1 = [(i, m) for i, x in stamped if (m := U1_RE.search(x))]
    starts = [i for i, x in stamped if EXPERIMENT_START_RE.search(x)]
    first = starts[0] if starts else None
    ok = (pre is not None and len(u1) == 1 and u1[0][0] > pre and (first is None or u1[0][0] < first))
    d_max = int(u1[0][1].group(1)) if len(u1) == 1 else None
    rec = {"lines": len(u1), "dMax": d_max, "line": lines[u1[0][0]] if len(u1) == 1 else None,
           "afterPrereg": bool(u1 and pre is not None and u1[0][0] > pre),
           "beforeFirstStart": bool(u1 and (first is None or u1[0][0] < first)), "passed": bool(ok)}
    if len(u1) == 1:
        result = next((i for i, x in stamped if EXPERIMENT_RESULT_RE.search(x)), None)
        amended = [(i, m) for i, x in stamped if (m := AMENDMENT_RE.search(x)) and i > u1[0][0]
                   and (result is None or i < result)]
        if amended:
            rec.update({"dMax": int(amended[-1][1].group(1)), "loggedDMax": d_max, "amendment": lines[amended[-1][0]]})
    return rec


def decision_case(g: bool, sc: bool, sb: bool, r_b: bool, r_c: bool, u_b: bool, u_c: bool) -> int:
    """Section 11: the case (0-4) of the seven booleans, checked in order."""
    if not g:
        return 0
    if sc and sb and r_b and u_b:
        return 1
    if sc and r_c and u_c:
        return 2
    if sc:
        return 3
    return 4


CASE_OUTCOME = {0: "A", 1: "B", 2: "C", 3: "A", 4: "A"}


def _cell_reason(name: str, c: Mapping) -> str:
    if c["outcome"] == "harm shown":
        return f"{name}: harm shown ({'; '.join(c['reasons'])})"
    return f"{name}: non-inferiority not shown ({'; '.join(c['reasons']) or 'not passed'})"


def decide(gates_ok: bool, gate_detail: str | None, cells: Mapping[str, Mapping], v3: Mapping, v4: Mapping,
           coverage: Mapping | None = None) -> dict:
    """Section 11 in one step: E, S_t, P2, N from the cells; R_B, R_C, U_B, U_C from V3; L_B = L_C = true (G6); the
    case, the outcome and its generated reasons (with the coverage cost of every A outcome of cases 3 and 4)."""
    p = {k: bool(cells.get(k, {}).get("passed")) for k in CELL_ORDER}
    e, s_t = p["V1a-late"] and p["V1b-late"], p["V1a-early"] and p["V1b-early"]
    p2, n = p["V2a"], p["V2b-night"] and p["V2b-nap"]
    sc, sb = e and n, s_t and p2
    r_b, r_c = bool(v3.get("V3a", {}).get("B", {}).get("passed")), bool(v3.get("V3a", {}).get("C", {}).get("passed"))
    u_b, u_c = bool(v3.get("U", {}).get("B")), bool(v3.get("U", {}).get("C"))
    l_b, l_c = bool(v4.get("continuation", {}).get("B")), bool(v4.get("continuation", {}).get("C"))
    case = decision_case(gates_ok, sc, sb, r_b, r_c, u_b, u_c)
    shared = [k for k in ("V1a-late", "V1b-late", "V2b-night", "V2b-nap") if not p[k]]
    own_b = [k for k in ("V1a-early", "V1b-early", "V2a") if not p[k]]

    def delay(arm: str) -> list[str]:
        out = []
        if not v3.get("V3a", {}).get(arm, {}).get("passed"):
            q = v3.get("V3a", {}).get(arm, {})
            found = [*(f"threw: {x}" for x in q.get("threw", [])),
                     *(f"not final: {x}" for x in q.get("finalNotImported", [])),
                     *(f"changed night neither imported nor noticed: {x}"
                       for x in q.get("changedNotImportedNorNoticed", []))]
            out.append(f"{arm}'s regression check failed: " + "; ".join(found or ["no replay"]))
        if not v3.get("U", {}).get(arm):
            b = v3.get("V3b", {}).get(arm, {}).get("medianAddedDelayMin")
            out.append(f"{arm}'s added delay V3b = {b} min > D_max = {v3.get('dMax')} min")
        return out

    reasons: list[str] = []
    if case == 0:
        reasons = [f"stopped by a fatal gate: {gate_detail}"]
    elif case == 2:
        reasons = [f"B not adopted: {_cell_reason(k, cells[k])}" for k in own_b] + \
            [f"B not adopted: {x}" for x in delay("B")]
    elif case == 3:
        reasons = [f"B not adopted: {_cell_reason(k, cells[k])}" for k in own_b] + \
            [f"B not adopted: {x}" for x in delay("B")] + [f"C not adopted: {x}" for x in delay("C")]
    elif case == 4:
        reasons = [f"B and C not adopted: {_cell_reason(k, cells[k])}" for k in shared] + \
            [f"B not adopted: {_cell_reason(k, cells[k])}" for k in own_b]
    if case in (3, 4) and coverage:
        a = coverage.get("A", {})
        for arm in ("B", "C"):
            x = coverage.get(arm, {})
            reasons.append(f"coverage A keeps losing against {arm}: Cov_staged {a.get('staged', float('nan')):.3f} vs "
                           f"{x.get('staged', float('nan')):.3f}, Cov_any {a.get('any', float('nan')):.3f} vs "
                           f"{x.get('any', float('nan')):.3f}, E2E {a.get('e2e', float('nan')):.3f} vs "
                           f"{x.get('e2e', float('nan')):.3f}")
    return {"case": case, "adopted": CASE_OUTCOME[case], "decision": case != 0,
            "booleans": {"G": gates_ok, "E": e, "S_t": s_t, "P2": p2, "N": n, "SC": sc, "SB": sb, "R_B": r_b,
                         "R_C": r_c, "U_B": u_b, "U_C": u_c, "L_B": l_b, "L_C": l_c},
            "cells": {k: cells.get(k, {}).get("outcome") for k in CELL_ORDER}, "reasons": reasons}


# ========================================================================================= reported analyses ====

def _diag_sleep(m: Sequence[Sequence[int]] | None) -> int:
    if m is None:
        return 0
    a = np.asarray(m)
    return int(a[1, 1] + a[2, 2] + a[3, 3])


def construct_coverage(parts: Mapping[str, Mapping], arm: str) -> dict:
    """Section 6.3 for one construct under ``arm``'s grouping and the default output policies (night: stages; nap:
    asleep / awake only; ignored or not estimated: nothing): true-sleep chunks outside the gap, those in a session
    written with stages, in any written session, and written with the correct stage; and the fragment's sleep that a
    merged night writes with stages (V2a, V2b: a grouping error)."""
    m = parts["M"]
    f = m["facts"]
    sleep = {}
    for key, r in m["regions"].items():
        name, p = key.split("@")
        if name == "piece":
            sleep[int(p)] = int(sum(r["truth"][1:]))
    total = sum(sleep.values())
    rec = {"total": total, "staged": 0, "any": 0, "correct": 0, "separateInNight": 0}
    if merged([tuple(x) for x in f["pieces"]], arm):
        if m["estimated"]:
            rec["staged"] = rec["any"] = total
            rec["correct"] = sum(_diag_sleep(m["regions"][f"piece@{p}"]["matrix"]) for p in sleep)
            if kind_of(m["design"]) in ("DOZE", "NAPAFTER"):
                rec["separateInNight"] = sleep.get(0 if kind_of(m["design"]) == "DOZE" else 1, 0)
        return rec
    for p, n in sleep.items():
        length = f["lengths"][p]
        row = parts.get(f"S{p + 1}")
        if length < MIN_SESSION_MIN * MIN or row is None or not row["estimated"]:
            continue
        rec["any"] += n
        if length >= NIGHT_MIN_MS:
            rec["staged"] += n
            rec["correct"] += _diag_sleep(row["regions"][f"piece@{p}"]["matrix"])
    return rec


def coverage_summary(idx: ConstructIndex, variants: Sequence[str] = VARIANTS, gaps: Sequence[int] = DECISIVE_GAPS,
                     stretches: Sequence[str] = DECISIVE_STRETCHES) -> dict:
    """Pooled coverage per arm (all decisive constructs) and per arm and design."""
    tot: dict[str, Counter] = defaultdict(Counter)
    per: dict[tuple[str, str], Counter] = defaultdict(Counter)
    for v in variants:
        for h in stretches:
            for cid in idx.ids(v, h, gaps, DESIGNS):
                parts = idx.parts[cid]
                for arm in ARM_ORDER:
                    c = construct_coverage(parts, arm)
                    tot[arm].update(c)
                    per[(arm, parts["M"]["design"])].update(c)

    def shares(c: Counter) -> dict:
        t = c["total"]
        return {"staged": c["staged"] / t if t else float("nan"), "any": c["any"] / t if t else float("nan"),
                "e2e": c["correct"] / t if t else float("nan"), "sleepChunks": t,
                "separateSleepInNight": c["separateInNight"] / t if t else float("nan")}

    return {**{arm: shares(tot[arm]) for arm in ARM_ORDER},
            "byDesign": {f"{arm}/{d}": shares(c) for (arm, d), c in sorted(per.items())}}


def pooled_metrics(c: np.ndarray) -> dict:
    c = np.asarray(c, float)
    c2 = napmodel.collapse_sleep_wake(c)
    n = c.sum()
    sleep_pred = c[:, 1:].sum()
    return {"chunks": int(n), "accuracy": float(np.trace(c) / n) if n else float("nan"), "kappa": report.kappa(c),
            "swKappa": report.kappa(c2), "swAccuracy": float(np.trace(c2) / c2.sum()) if c2.sum() else float("nan"),
            "wakeRecall": napmodel.wake_scores(c2)["recall"],
            "deepShare": float(c[:, 2].sum() / sleep_pred) if sleep_pred else float("nan"),
            "remShare": float(c[:, 3].sum() / sleep_pred) if sleep_pred else float("nan"),
            "allSleepSwAccuracy": float(c[1:].sum() / n) if n else float("nan"),
            "allLightAccuracy": float(c[1].sum() / n) if n else float("nan")}


def design_table(idx: ConstructIndex) -> list[dict]:
    """Reported (section 6.3): per design, g, stretch variant, sampling variant and dataset, the pooled metrics of the
    main region and the fragment under M and S (and S' for NAPAFTER), with "all sleep" and "all LIGHT" references."""
    rows = []
    for (v, h, g, d), cids in sorted(idx.by.items(), key=lambda kv: (kv[0][3], kv[0][2], kv[0][1], kv[0][0])):
        for ds_ in DATASETS:
            sel = [c for c in cids if idx.parts[c]["M"]["dataset"] == ds_]
            if not sel:
                continue
            rec: dict = {"design": d, "g": g, "stretch": h, "variant": v, "dataset": ds_, "constructs": len(sel)}
            for region, comp in (("main", "S"), ("fragment", "S"), ("fragment", "S'")):
                if region == "fragment" and d == "MID":
                    continue
                if comp == "S'" and kind_of(d) != "NAPAFTER":
                    continue
                mx, my, cnt, n = np.zeros((4, 4)), np.zeros((4, 4)), Counter(), 0
                for c in sel:
                    pair = region_pair(idx.parts[c], region, comp, cnt)
                    if pair is not None:
                        mx += pair[0]
                        my += pair[1]
                        n += 1
                rec[f"{region}/{comp}"] = {"constructs": n, "M": pooled_metrics(mx), comp: pooled_metrics(my),
                                           "excluded": dict(cnt)}
            rows.append(rec)
    return rows


def estimator_mismatches(idx: ConstructIndex) -> dict:
    """Reported: per design, g, stretch variant and sampling variant, the estimators used (HYBRID / RULES /
    NOT_ESTIMATED) by part."""
    out: dict[str, Counter] = defaultdict(Counter)
    for (v, h, g, d), cids in idx.by.items():
        for c in cids:
            ps = idx.parts[c]
            tag = ",".join(f"{p}:{(r['estimatorUsed'] if r['estimated'] else 'NOT_ESTIMATED')}"
                           for p, r in sorted(ps.items()))
            out[f"{d}/g{g}/{h}/{v}"][tag] += 1
    return {k: dict(v) for k, v in sorted(out.items())}


def c_classification(idx: ConstructIndex, h_ms: int = DEFAULT_NAP_MAX_SESSION_MS) -> dict:
    """Reported: how many constructs C would classify differently if K (the covered time before the gap) were the true
    sleep before the gap."""
    cnt: Counter = Counter()
    differ = 0
    for cid, ps in idx.parts.items():
        m = ps.get("M")
        if m is None or m["g"] not in DECISIVE_GAPS or m["stretch"] != "base":
            continue
        f = m["facts"]
        k_ok = f["coveredBeforeGapMs"] >= h_ms
        s_ok = f["sleepBeforeGapMs"] >= h_ms
        cnt[f"{kind_of(m['design'])}:{'K>=H' if k_ok else 'K<H'}:{'sleep>=H' if s_ok else 'sleep<H'}"] += 1
        differ += int(k_ok != s_ok)
    return {"counts": dict(sorted(cnt.items())), "differ": differ}


def reported_cells(idx: ConstructIndex, pcs: Mapping[tuple[str, str], Mapping], dataset_of: Callable[[str], str],
                   variants: Sequence[str] = VARIANTS) -> dict:
    """Reported (section 6.3): the stretch variants nohr, presleep and walk at g = 45; R90's gaps 75 and 89 min; the R45
    reading of g = 35; and the positive control per design."""
    out: dict = {"stretchVariantsG45": {}, "R90": {}, "R45g35": {}, "pcByDesign": {}}
    for name, cell in CELLS.items():
        out["stretchVariantsG45"][name] = [cell_variant(idx, cell, v, h, dataset_of, gaps=(REPORTED_STRETCH_GAP,))
                                           for v in variants for h in REPORTED_STRETCHES]
        out["R90"][name] = [cell_variant(idx, cell, v, "base", dataset_of, gaps=R90_GAPS) for v in variants]
        out["R45g35"][name] = [cell_variant(idx, cell, v, h, dataset_of, gaps=(35,)) for v in variants
                               for h in DECISIVE_STRETCHES]
    for d in DESIGNS:
        out["pcByDesign"][d] = [pc_variant(idx, pcs, {"designs": (d,)}, v, dataset_of, designs=(d,))
                                for v in variants]
    return out


# ================================================================================== provenance and gates ====

REPLAY_CLASS = "io.github.alien2003.pulsestages.work.saa.MergeGapGateReplayTest"
REPLAY_TASK = ":work:testDebugUnitTest"
REPLAY_RESULTS = REPO_ROOT / "work" / "build" / "test-results" / "testDebugUnitTest"
G4_CLASSES: tuple[str, ...] = ("io.github.alien2003.pulsestages.data.hc.pipeline.MergeGapGroupingFixtureTest",
                               "io.github.alien2003.pulsestages.data.hc.pipeline.SourceSettlementMergeGapTest")
G4_TASK = ":data-hc:testDebugUnitTest"
G4_RESULTS = REPO_ROOT / "data-hc" / "build" / "test-results" / "testDebugUnitTest"
JVM_TEST_TIMEOUT_S = 4 * 3600


def prereg_record(log_path: Path | None) -> dict:
    """G0 (pre-registration): the committed text's SHA-256 equals :data:`PREREG_SHA256`, so does its ``.sha256`` file,
    and (with a log) the pre-registration NOTE line is in the commands log."""
    path = REPO_ROOT / PREREG_PATH
    digest = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
    side = path.with_name(path.name + ".sha256")
    side_ok = side.is_file() and side.read_text(encoding="utf-8").split() == [PREREG_SHA256, PREREG_NAME]
    noted = None
    if log_path is not None:
        lines = Path(log_path).read_text(encoding="utf-8").splitlines() if Path(log_path).is_file() else []
        noted = any(ds.STAMP_RE.match(x) and f" {PREREG_NOTE}" in x for x in lines)
    ok = digest == PREREG_SHA256 and side_ok and noted is not False
    return {"path": PREREG_PATH, "sha256": digest, "expected": PREREG_SHA256, "sha256File": side_ok,
            "noteInLog": noted, "passed": bool(ok)}


def gate_g0(cli: AlgoCli, log_path: Path | None, experiment: bool) -> dict:
    """G0 provenance (fatal): the algo-cli fingerprint, ``meta.code``, the pre-registration digest and, for
    ``train.py mergegap``, exactly one U1 NOTE after the pre-registration NOTE and before the first START."""
    pre = prereg_record(log_path)
    rec: dict = {"algoCli": cli.fingerprint(), "code": code_fingerprint(), "prereg": pre}
    ok = pre["passed"]
    if experiment:
        rec["u1"] = u1_record(log_path)
        ok = ok and rec["u1"]["passed"]
    rec["passed"] = bool(ok)
    if not ok:
        raise GateFailure(f"G0: the pre-registration or the U1 NOTE does not hold: {pre} {rec.get('u1')}",
                          {"G0": rec})
    return rec


def check_build(cli: AlgoCli, g0: Mapping, where: str) -> None:
    """G0: the run refuses a build other than the one recorded at its start."""
    now = cli.fingerprint().get("sha256")
    if now != g0["algoCli"].get("sha256"):
        raise GateFailure(f"G0: algo-cli changed during the run ({where}): {now} != {g0['algoCli'].get('sha256')}",
                          {"G0": dict(g0, passed=False, changedAt=where)})


def _git(*args: str, root: Path = REPO_ROOT) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, timeout=120, check=False)


def git_unchanged(paths: Sequence[str] = BUILD_NEUTRAL_PATHS, base: str = BASE_COMMIT) -> dict:
    """``git diff --quiet <base> -- <paths>`` (working tree against the base commit) and the changed files."""
    q = _git("diff", "--quiet", base, "--", *paths)
    names = _git("diff", "--name-only", base, "--", *paths)
    untracked = _git("ls-files", "--others", "--exclude-standard", "--", *paths)
    changed = sorted(set(names.stdout.split()) | set(untracked.stdout.split()))
    return {"base": base, "paths": list(paths), "exitCode": q.returncode, "changed": changed,
            "unchanged": q.returncode == 0 and not untracked.stdout.strip()}


TEST_SOURCE_PATHS: tuple[str, ...] = ("*/src/test/*", "*/src/testFixtures/*")
TEST_ANNOTATION_RE = re.compile(r"@(?:[\w.]+\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")
TEST_FUN_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(`[^`]+`|\w+)\s*\(")
ASSERTION_RE = re.compile(r"\b(?:assert\w*|fail)\s*(?:<[^<>()]*>\s*)?\(")


def junit_test_names(text: str) -> list[str]:
    """The names of the JUnit test methods of a Kotlin source (the first ``fun`` after each ``@Test``,
    ``@ParameterizedTest``, ``@RepeatedTest``, ``@TestFactory`` or ``@TestTemplate``), in source order."""
    out = []
    for m in TEST_ANNOTATION_RE.finditer(text):
        f = TEST_FUN_RE.search(text, m.end())
        if f:
            out.append(f.group(1).strip("`"))
    return out


def assertion_calls(text: str) -> list[str]:
    """Every ``assert...(...)`` and ``fail(...)`` call of a Kotlin source with its arguments (parentheses balanced
    outside string literals), whitespace collapsed, in source order; the declaration of an assertion helper
    (``fun assertX(...)``) is not a call."""
    out = []
    for m in ASSERTION_RE.finditer(text):
        if re.search(r"\bfun\s+(?:<[^<>]*>\s*)?$", text[max(0, m.start() - 40):m.start()]):
            continue
        i, depth, in_str, esc = m.end(), 1, False, False
        while i < len(text) and depth:
            ch = text[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            elif ch == '"':
                in_str = True
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            i += 1
        out.append(" ".join(text[m.start():i].split()))
    return out


def compare_test_source(old: str, new: str | None, others: Sequence[str] = ()) -> dict:
    """G2a for one existing test source changed since the base commit: every test method of ``old`` is still in
    ``new`` (by name), and every assertion call of ``old`` (counted) is still in ``new`` or in ``others`` (the test
    sources of the same module added or changed since, where helpers may have moved, section 9.1)."""
    was, now = junit_test_names(old), junit_test_names(new or "")
    missing = sorted(set(was) - set(now))
    pool = Counter(assertion_calls(new or ""))
    for t in others:
        pool += Counter(assertion_calls(t))
    calls = Counter(assertion_calls(old))
    lost = sorted((calls - pool).elements())
    return {"exists": new is not None, "methods": len(set(was)), "methodsNow": len(set(now)),
            "missingMethods": missing, "assertions": sum(calls.values()), "missingAssertions": lost[:10],
            "nMissingAssertions": len(lost), "passed": new is not None and not missing and not lost}


def existing_tests_check(base: str = BASE_COMMIT, paths: Sequence[str] = TEST_SOURCE_PATHS,
                       root: Path = REPO_ROOT) -> dict:
    """G2a, "every existing test method and assertion unchanged": for each Kotlin / Java test source of ``base`` that
    the working tree changed or deleted, :func:`compare_test_source` against its current text and the module's test
    sources added or changed since ``base``."""
    st = _git("diff", "--name-status", "--no-renames", base, "--", *paths, root=root)
    untracked = _git("ls-files", "--others", "--exclude-standard", "--", *paths, root=root)
    if st.returncode != 0 or untracked.returncode != 0:
        return {"base": base, "error": (st.stderr + untracked.stderr).strip()[-500:], "passed": False}
    src = (".kt", ".java")
    existing, touched = [], set(untracked.stdout.split())
    for line in st.stdout.splitlines():
        status, path = line.split("\t", 1)
        if not path.endswith(src):
            continue
        if status[0] in "MD":
            existing.append(path)
        if status[0] != "D":
            touched.add(path)
    files: dict[str, dict] = {}
    for p in sorted(existing):
        old = _git("show", f"{base}:{p}", root=root)
        if old.returncode != 0:
            files[p] = {"error": old.stderr.strip()[-300:], "passed": False}
            continue
        cur = Path(root) / p
        module = p.split("/src/")[0] + "/src/"
        others = [(Path(root) / q).read_text(encoding="utf-8") for q in sorted(touched)
                  if q != p and q.startswith(module) and q.endswith(src) and (Path(root) / q).is_file()]
        files[p] = compare_test_source(old.stdout, cur.read_text(encoding="utf-8") if cur.is_file() else None, others)
    return {"base": base, "paths": list(paths), "files": files, "nFiles": len(files),
            "methods": sum(q.get("methods", 0) for q in files.values()),
            "assertions": sum(q.get("assertions", 0) for q in files.values()),
            "passed": all(q["passed"] for q in files.values())}


def jvm_test_record(task: str, classes: Sequence[str] = (), results_dir: Path | None = None,
                    gradle_args: Sequence[str] = (), root: Path = REPO_ROOT, rerun: bool = True) -> dict:
    """A generalization of ``napsrealistic.jvm_test_record`` (which stays unchanged): runs ``./gradlew <gradle_args>
    <task> --tests <class> ... [--rerun]`` on the working tree and reads each class's ``TEST-<class>.xml`` in
    ``results_dir``: passed when Gradle succeeded and every class ran now with at least one test and no failure, error
    or skip (without classes: when Gradle succeeded)."""
    cmd = [str(root / "gradlew"), "--console=plain", *gradle_args, task,
           *[x for c in classes for x in ("--tests", c)], *(["--rerun"] if rerun else [])]
    t0 = time.time()
    try:
        proc = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=JVM_TEST_TIMEOUT_S)
        rc, text = proc.returncode, proc.stdout + proc.stderr
    except (OSError, subprocess.TimeoutExpired) as e:
        rc, text = None, str(e)
    rec: dict = {"command": " ".join(cmd[1:]), "exitCode": rc, "seconds": round(time.time() - t0, 1), "classes": {}}
    ok = rc == 0
    for c in classes:
        f = Path(results_dir or root) / f"TEST-{c}.xml"
        q: dict = {"file": str(f.relative_to(root)) if f.is_relative_to(root) else str(f)}
        try:
            fresh = f.stat().st_mtime >= t0 - 1.0
            suite = ET.parse(f).getroot()
            q.update({k: int(suite.get(k, "-1")) for k in ("tests", "failures", "errors", "skipped")},
                     timestamp=suite.get("timestamp"), writtenNow=fresh,
                     testcases=[{"name": tc.get("name"), "failed": tc.find("failure") is not None
                                 or tc.find("error") is not None} for tc in suite.iter("testcase")])
            q["passed"] = bool(fresh and q["tests"] > 0 and q["failures"] == q["errors"] == q["skipped"] == 0)
        except (OSError, ET.ParseError) as e:
            q.update(error=str(e), passed=False)
        rec["classes"][c] = q
        ok = ok and q["passed"]
    if not ok:
        rec["outputTail"] = text[-4000:]
    rec["passed"] = bool(ok)
    return rec


def _decode_key(recs: Iterable[Mapping]) -> dict[str, tuple]:
    return {r["id"]: (None if r["confusion"] is None else np.asarray(r["confusion"]).tolist(), list(r["warnings"]),
                      bool(r["estimated"]), r.get("estimatorUsed"), r.get("reason")) for r in recs}


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def base_reference(cli: AlgoCli, nights_root: Path, out: Path, variants: Sequence[str] = VARIANTS) -> dict:
    """Phase A: the whole nights of every variant decoded with the shipped bundle and the default parameters
    (``out/ref/nights/<variant>.json`` and ``.per-epoch.csv``)."""
    ref = out / "ref" / "nights"
    ref.mkdir(parents=True, exist_ok=True)
    rec: dict = {"bundle": str(APP_BUNDLE), "bundleSha256": _sha256(APP_BUNDLE), "variants": {}}
    for v in variants:
        mp, pe = ref / f"{v}.json", ref / f"{v}.per-epoch.csv"
        m = cli.evaluate(Path(nights_root) / v, "HYBRID", mp, model=APP_BUNDLE, per_epoch=pe)
        recs = report.parse_metrics(m)
        rec["variants"][v] = {"nights": len(recs), "skipped": sum(1 for r in recs if not r["estimated"]),
                              "jsonSha256": _sha256(mp), "perEpochSha256": _sha256(pe)}
    return rec


def gate_g2_nights(cli: AlgoCli, nights_root: Path, base_dir: Path, out: Path,
                   variants: Sequence[str] = VARIANTS) -> dict:
    """G2a: with the build of the working tree, the whole-night decodes with the shipped bundle and the default
    parameters equal the phase-A references (per night: confusion, warnings, estimator, status; per-epoch CSV byte for
    byte)."""
    work = out / "g2"
    work.mkdir(parents=True, exist_ok=True)
    rec: dict = {"base": str(base_dir), "variants": {}}
    ok = True
    for v in variants:
        ref_json, ref_pe = base_dir / "ref" / "nights" / f"{v}.json", base_dir / "ref" / "nights" / f"{v}.per-epoch.csv"
        if not ref_json.is_file() or not ref_pe.is_file():
            rec["variants"][v] = {"error": f"no phase-A reference in {base_dir} (run train.py mergegap-base)",
                                  "passed": False}
            ok = False
            continue
        ref = _decode_key(report.parse_metrics(read_json(ref_json)))
        pe, mp = work / f"{v}.per-epoch.csv", work / f"{v}.json"
        got = _decode_key(report.parse_metrics(cli.evaluate(Path(nights_root) / v, "HYBRID", mp, model=APP_BUNDLE,
                                                            per_epoch=pe)))
        same = sum(1 for k in ref if ref[k] == got.get(k))
        q = {"nights": len(ref), "identical": same, "refPerEpochSha256": _sha256(ref_pe),
             "perEpochSha256": _sha256(pe),
             "mismatches": sorted(k for k in set(ref) | set(got) if ref.get(k) != got.get(k))[:10]}
        q["passed"] = same == len(ref) == len(got) > 0 and q["perEpochSha256"] == q["refPerEpochSha256"]
        ok = ok and q["passed"]
        rec["variants"][v] = q
        pe.unlink(missing_ok=True)
        mp.unlink(missing_ok=True)
    rec["passed"] = bool(ok)
    return rec


def run_replays(report_path: Path, arms: Sequence[str] = ARM_ORDER) -> dict:
    """The Sleep as Android replays of section 9 in the Kotlin harness (``MergeGapGateReplayTest``, tag
    ``mergegap-replay``) under ``arms``; the JSON report is written to ``report_path``."""
    report_path = Path(report_path)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.unlink(missing_ok=True)
    rec = jvm_test_record(REPLAY_TASK, (REPLAY_CLASS,), REPLAY_RESULTS,
                          ("-Ppulsestages.mergegapReplay=true", f"-Ppulsestages.mergegapArms={','.join(arms)}",
                           f"-Ppulsestages.mergegapReport={report_path}"))
    rec["report"] = str(report_path)
    rec["reportSha256"] = _sha256(report_path) if report_path.is_file() else None
    return rec


def gate_g2(cli: AlgoCli, nights_root: Path, base_dir: Path, out: Path, full_tests: bool = True) -> dict:
    """G2 (fatal): (a) no change to core-algo, algo-cli or the bundle since the base commit; the whole-night decodes
    equal the phase-A references; every test method and assertion of the existing test sources is still there
    (:func:`existing_tests_check`); ``./gradlew test`` passes (the test sources changed since the base commit are
    listed);
    (b) ``MergeGapGateReplayTest`` under arm A reproduces the existing scenario tests' assertions (its method with
    the assertions; arm A only)."""
    rec: dict = {"buildNeutral": git_unchanged()}
    rec["nights"] = gate_g2_nights(cli, nights_root, base_dir, out)
    rec["testSourcesChanged"] = git_unchanged(TEST_SOURCE_PATHS)["changed"]
    rec["existingTests"] = existing_tests_check()
    rec["gradleTest"] = jvm_test_record("test", rerun=False) if full_tests else {"skipped": True, "passed": True}
    rec["G2b"] = run_replays(out / "g2b" / "mergegap-v3-arm-A.json", ("A",))
    rec["passed"] = bool(rec["buildNeutral"]["unchanged"] and rec["nights"]["passed"] and rec["existingTests"]["passed"]
                         and rec["gradleTest"]["passed"] and rec["G2b"]["passed"])
    return rec


def gate_g4() -> dict:
    """G4 (fatal): :func:`group` reproduces the committed fixture under every arm, and the data-hc tests check
    ``NightCandidateBuilder`` and ``SourceSettlement`` against the same file."""
    py = check_grouping_fixture()
    kt = jvm_test_record(G4_TASK, G4_CLASSES, G4_RESULTS)
    return {"python": py, "kotlin": kt, "passed": bool(py["passed"] and kt["passed"])}


def gate_g3_nights(inp: napsrealistic.Inputs, variants: Sequence[str] = VARIANTS) -> dict:
    """G3 (part): the app-like cut of every unmodified night [t0, t1] equals its night file."""
    bad, n = [], 0
    for v in variants:
        for ref in inp.index.for_variant(v):
            night = inp.night(v, ref.id)
            c = cut(night_timeline(night), int(night["sessionStartMs"]), int(night["sessionEndMs"]), str(night["id"]))
            n += 1
            if _normal(c) != _normal(night):
                bad.append(ref.id)
    return {"nights": n, "differing": bad[:10], "nDiffering": len(bad), "passed": not bad and n > 0}


def _normal(n: Mapping) -> dict:
    return {**{k: n[k] for k in n if k not in ("hr", "steps", "labels")},
            "hr": [(int(t), float(v)) for t, v in sorted(n.get("hr", []), key=lambda h: h[0])],
            "steps": sorted((int(a), int(b), int(c)) for a, b, c in n.get("steps", [])),
            "labels": sorted((int(a), int(b), str(s)) for a, b, s in n.get("labels", []))}


# =================================================================================================== pipeline ====

def all_hosts(inp: napsrealistic.Inputs, variants: Sequence[str] = VARIANTS) -> tuple[dict, dict]:
    """The hosts of every night key (both variants' nights), with the counts of gate G7."""
    keys: dict[str, dict[str, str]] = defaultdict(dict)
    for v in variants:
        for ref in inp.index.for_variant(v):
            keys[napmodel.night_key(ref.id)][v] = ref.id
    hosts: dict[str, dict[str, dict]] = {}
    no_pool, no_host = [], Counter()
    for key in sorted(keys):
        ids = keys[key]
        if set(ids) != set(variants):
            raise MergeGapError(f"night {key} is missing in a variant: {ids}")
        nights = {v: inp.night(v, ids[v]) for v in variants}
        hs, info = night_hosts(nights)
        if info.get("noWakePool"):
            no_pool.append({"night": key, "variants": info["noWakePool"]})
        if not hs:
            no_host[dataset_from_id(key)] += 1
        hosts[key] = hs
    per: dict = {}
    for d in DESIGNS:
        q = {}
        for ds_ in DATASETS:
            ks = [k for k, hs in hosts.items() if d in hs and dataset_from_id(k) == ds_]
            subs = {inp.index.night(keys[k][variants[0]]).subject for k in ks}
            q[ds_] = [len(ks), len(subs)]
        exp = EXPECTED_HOSTS.get(d)
        per[d] = {"hosts": q, "expected": {"walch": list(exp[0]), "bidsleep": list(exp[1])} if exp else None,
                  "same": exp is not None and q["walch"] == list(exp[0]) and q["bidsleep"] == list(exp[1])}
    counts = {"perDesign": per, "noWakePool": no_pool, "noHost": dict(no_host), "expectedNoHost": EXPECTED_NO_HOST,
              "nights": len(keys)}
    return hosts, counts


def run_block(cli: AlgoCli, block: Sequence[Spec], night_of: Callable[[str], Mapping], hosts: Mapping,
              params: Mapping, fold_dir: Path | None, work: Path, name: str, estimator: str = "HYBRID") -> dict:
    """Builds, decodes and scores one block (one algo-cli call, with :func:`block_params`); with its G3 / G6 findings,
    digests and block orders."""
    mat = materialize(night_of, block, hosts)
    res = decode_block(cli, block, mat, block_params(block, params), fold_dir, work, name, estimator)
    return {**res, "g3": mat.g3, "g6": mat.g6, "digests": mat.digests, "blocks": mat.blocks,
            "variant": block[0].variant if block else None, "block": name, "sessions": len(block)}


def decode_specs(cli: AlgoCli, specs: Sequence[Spec], night_of: Callable[[str], Mapping], hosts: Mapping,
                 params: Mapping, fold_dir: Path | None, work: Path, jobs: int, name: str,
                 block_size: int = DECODE_BLOCK, estimator: str = "HYBRID") -> list[dict]:
    """The blocks of ``specs`` (one variant and parameter set) decoded with at most ``jobs`` calls at once; the
    blocks depend on the ids only, so ``jobs`` changes no result."""
    tasks = list(enumerate(blocks_of(specs, block_size)))
    return loso.map_threads(lambda t: run_block(cli, t[1], night_of, hosts, params, fold_dir, work,
                                                f"{name}-{t[0]:04d}", estimator),
                            tasks, max(1, min(jobs, MAX_CALLS)))


def decode_all(inp: napsrealistic.Inputs, cli: AlgoCli, out: Path, hosts: Mapping, jobs: int,
               estimator: str = "HYBRID") -> dict:
    """Every construct and positive control of every (variant, outer group), decoded by the group's held-out subjects'
    N1 fold bundles with the group's N1 parameters, in id-sorted blocks of at most 1000 sessions per group (at most 10
    calls at once over all groups), and scored."""
    work = out / "work"
    tasks = []
    specs_per_variant: Counter = Counter()
    for g in inp.groups:
        v = g.variant
        nights = [inp.night(v, n.id) for n in inp.index.nights_of(v, g.heldout)]
        specs = enumerate_specs(v, nights, hosts)
        specs_per_variant[v] += len(specs)
        if not specs:
            continue
        bundles = {s: napsrealistic.fold_bundle(inp, napsrealistic.REFERENCE, v, s, None, None) for s in g.heldout}
        d = napsrealistic.write_bundles(work / "models" / v / g.key, bundles)
        params = inp.n1_params(g)
        for i, blk in enumerate(blocks_of(specs)):
            tasks.append((g, i, blk, d, params))

    def one(task):
        g, i, blk, d, params = task
        return run_block(cli, blk, lambda nid: inp.night(g.variant, nid), hosts, params, d, work / "calls",
                         f"{g.variant}-{g.key}-{i:04d}", estimator)

    t0 = time.monotonic()
    results = loso.map_threads(one, tasks, max(1, min(jobs, MAX_CALLS)))
    shutil.rmtree(work, ignore_errors=True)
    return {"results": results, "seconds": round(time.monotonic() - t0, 1), "calls": len(tasks),
            "sessionsPerVariant": dict(specs_per_variant)}


def twin_of(cid: str) -> str:
    """A construct id with its night id replaced by the night key: the same for both sampling variants."""
    nid = cid.split("~mg-")[0]
    return cid.replace(nid, napmodel.night_key(nid), 1)


def compare_block_orders(by_variant: Mapping[str, Sequence[int]]) -> dict:
    """G3 (block order, as the NOTE of the log reads section 14): the order of the wake-pool blocks that every variant
    of a construct shares is identical; a block present in some variants only (a wake bout with heart-rate samples in
    one variant only, section 2.8) is reported, not a failure."""
    common = set.intersection(*(set(o) for o in by_variant.values())) if by_variant else set()
    shared = {v: [b for b in o if b in common] for v, o in by_variant.items()}
    only = {v: sorted(set(o) - common) for v, o in by_variant.items() if set(o) - common}
    return {"sameOrder": len({tuple(x) for x in shared.values()}) <= 1, "shared": len(common), "onlyIn": only}


def gate_g3_constructs(results: Sequence[Mapping]) -> dict:
    """G3 (part): construct invariants (with the M session's forced span and the positive controls of 5.5, found in
    :func:`materialize`), every session file re-read byte for byte, both variants with identical boundaries, labels,
    step records and regions (the variant-invariant digest) and the same order of the wake-pool blocks they share
    (blocks of one variant only reported), every positive-control decode a night, unique ids."""
    problems = [x for r in results for x in r["g3"]]
    files = [r["file"] for r in results]
    by_twin: dict[str, dict[str, str]] = defaultdict(dict)
    orders: dict[str, dict[str, list[int]]] = defaultdict(dict)
    for r in results:
        for cid, dg in r["digests"].items():
            by_twin[twin_of(cid)][r["variant"]] = dg
        for cid, o in (r.get("blocks") or {}).items():
            orders[twin_of(cid)][r["variant"]] = list(o)
    differ = sorted(k for k, v in by_twin.items() if len(set(v.values())) > 1)
    cmp = {k: compare_block_orders(v) for k, v in sorted(orders.items()) if len(v) > 1}
    order_differs = [k for k, q in cmp.items() if not q["sameOrder"]]
    one_variant: dict[str, dict] = {}                 # per (night key, design): the block order is the design's
    for k, q in cmp.items():
        if q["onlyIn"]:
            key, rest = k.split("~mg-", 1)
            one_variant.setdefault(f"{key}|{rest.rsplit('-g', 1)[0]}", q["onlyIn"])
    pc_not_night = sorted(p["id"] for r in results for p in r.get("pcs", []) if p.get("mode") != "NIGHT")
    ok = (not problems and all(f["reReadIdentical"] for f in files) and not differ and not order_differs
          and not pc_not_night)
    return {"constructProblems": problems[:20], "nConstructProblems": len(problems),
            "sessionFiles": len(files), "sessionFileDigests": [f["sha256"] for f in files],
            "reReadIdentical": all(f["reReadIdentical"] for f in files), "variantsDiffer": differ[:10],
            "nVariantsDiffer": len(differ), "blockOrdersCompared": len(cmp), "blockOrderDiffers": order_differs[:10],
            "nBlockOrderDiffers": len(order_differs), "blocksInOneVariantOnly": dict(sorted(one_variant.items())),
            "nBlocksInOneVariantOnly": len(one_variant), "pcNotNight": pc_not_night[:10],
            "nPcNotNight": len(pc_not_night), "passed": bool(ok)}


def gate_g5(rows: Sequence[Mapping], pcs: Sequence[Mapping]) -> dict:
    """G5 (fatal): (a) the chunk scoring reproduces algo-cli's confusion of every estimated session; (b) under M every
    epoch whose midpoint lies in the forced span is AWAKE (boundary gap chunks counted)."""
    a_bad = [r["id"] for r in [*rows, *pcs] if r.get("g5a") is False]
    m = [r for r in rows if r["part"] == "M" and r.get("forced")]
    b_bad = [r["id"] for r in m if r["forced"]["notAwake"]]
    return {"sessionsCompared": sum(1 for r in [*rows, *pcs] if r.get("g5a") is not None), "g5aMismatch": a_bad[:10],
            "nG5aMismatch": len(a_bad), "forcedEpochs": sum(r["forced"]["forcedEpochs"] for r in m),
            "forcedNotAwake": b_bad[:10], "nForcedNotAwake": len(b_bad),
            "boundaryGapChunks": sum(r["forced"]["boundaryGapChunks"] for r in m),
            "passed": not a_bad and not b_bad}


def gate_g6(results: Sequence[Mapping], v4: Mapping) -> dict:
    problems = [x for r in results for x in r["g6"]]
    return {"armMapProblems": problems[:10], "nArmMapProblems": len(problems), "V4": v4["continuation"],
            "passed": not problems and v4["passed"]}


def gate_g7(counts: Mapping, rows: Sequence[Mapping], pcs: Sequence[Mapping], cells: Mapping) -> dict:
    """G7 (reported): hosts and constructs per design, dataset and variant (against section 2.8), nights without a host
    or a wake pool, subjects per cell, excluded constructs, estimators."""
    constructs: Counter = Counter()
    for r in rows:
        if r["part"] == "M":
            constructs[f"{r['design']}/{r['dataset']}/{r['variant']}/g{r['g']}/{r['stretch']}"] += 1
    not_est: Counter = Counter(f"{r['part']}:{r['reason']}" for r in [*rows, *pcs] if not r["estimated"])
    per_cell = {k: [{"variant": q["variant"], "stretch": q["stretch"], "n": q["n"], "datasets": q["datasets"],
                     "dropped": q["dropped"], "counts": q["counts"]} for q in c["perVariant"]]
                for k, c in cells.items()}
    hosts_same = all(q["same"] for q in counts["perDesign"].values())
    return {"hosts": counts, "hostsAsSection28": hosts_same, "constructs": dict(sorted(constructs.items())),
            "notEstimated": dict(not_est), "cells": per_cell, "passed": None}


PROJECT_DEP_RE = re.compile(r"""^\s*(\w+)\s*\(.*?\bproject\(\s*["'](:[^"']+)["']\s*\)""")


def project_dependencies(module: str, root: Path = REPO_ROOT) -> dict:
    """The projects that ``<module>/build.gradle.kts`` puts on a non-test configuration (``implementation(project(
    ":x"))``, ``api(...)``; ``test*`` and ``androidTest*`` configurations left out), and whether the file names a
    project any other way (type-safe ``projects.`` accessors), which this reader does not resolve."""
    text = (Path(root) / module.lstrip(":") / "build.gradle.kts").read_text(encoding="utf-8")
    deps, test_deps = set(), set()
    for line in text.splitlines():
        m = PROJECT_DEP_RE.match(line)
        if m:
            (test_deps if m.group(1).startswith(("test", "androidTest")) else deps).add(m.group(2))
    return {"module": module, "projects": sorted(deps), "testProjects": sorted(test_deps),
            "unresolved": bool(re.search(r"\bprojects\.", text))}


def algo_cli_closure(root: Path = REPO_ROOT) -> dict:
    """Phase A: the projects algo-cli's own (non-test) build depends on, transitively; the decode build equals the base
    commit's only when this is :core-algo alone (with core-algo, algo-cli, the bundle and the root build files
    unchanged)."""
    seen: dict[str, dict] = {}
    todo = [":algo-cli"]
    while todo:
        m = todo.pop()
        if m in seen:
            continue
        seen[m] = project_dependencies(m, root)
        todo += seen[m]["projects"]
    closure = sorted(k for k in seen if k != ":algo-cli")
    return {"closure": closure, "modules": seen, "unresolved": any(q["unresolved"] for q in seen.values()),
            "onlyCoreAlgo": closure == [":core-algo"] and not any(q["unresolved"] for q in seen.values())}


def base_build_record(root: Path = REPO_ROOT) -> dict:
    """Phase A (section 14, and the NOTE of the log on the order of the Kotlin edits): the decode build of the working
    tree equals the base commit's: ``git diff --quiet <base> -- core-algo algo-cli app/src/main/assets/models`` (no
    untracked file there either), the root build files unchanged, algo-cli depending on :core-algo alone."""
    neutral = git_unchanged()
    rootb = git_unchanged(ROOT_BUILD_PATHS)
    deps = algo_cli_closure(root)
    return {"base": BASE_COMMIT, "buildNeutral": neutral, "rootBuild": rootb, "algoCliDependencies": deps,
            "otherChangedFiles": git_unchanged(("*.kt", "*.kts"))["changed"],
            "buildEqualsBase": bool(neutral["unchanged"] and rootb["unchanged"] and deps["onlyCoreAlgo"])}


def run_base(cli: AlgoCli, nights_root: Path, out: Path) -> dict:
    """Phase A (section 14): refused unless the decode build equals the base commit's (:func:`base_build_record`,
    checked now and recorded), the base references of G2a; the algo-cli fingerprint for the NOTE line."""
    build = base_build_record()
    if not build["buildEqualsBase"]:
        raise MergeGapError(f"phase A needs the decode build of {BASE_COMMIT}: core-algo, algo-cli and the bundle "
                            f"changed {build['buildNeutral']['changed'][:10]}, root build files changed "
                            f"{build['rootBuild']['changed'][:10]}, algo-cli's project dependencies "
                            f"{build['algoCliDependencies']['closure']} (unresolved: "
                            f"{build['algoCliDependencies']['unresolved']})")
    rec = {"algoCli": cli.fingerprint(), "buildNeutral": build["buildNeutral"], "baseBuild": build,
           "otherChangedFiles": build["otherChangedFiles"], "reference": base_reference(cli, nights_root, out)}
    write_json(out / "ref" / "summary.json", rec)
    return rec


def base_note(rec: Mapping) -> str:
    v = rec["reference"]["variants"]
    b = rec.get("baseBuild") or {}
    eq = (f"decode build equal to {BASE_COMMIT}'s, checked now: git diff --quiet {BASE_COMMIT} -- "
          f"{' '.join(BUILD_NEUTRAL_PATHS)} exit {b['buildNeutral']['exitCode']} with no untracked file, root build "
          f"files ({', '.join(ROOT_BUILD_PATHS)}) unchanged, algo-cli's project dependencies "
          f"{', '.join(b['algoCliDependencies']['closure'])} only; {len(b['otherChangedFiles'])} other Kotlin and "
          f"Gradle files of the working tree differ from {BASE_COMMIT} (outside the decode build); "
          if b.get("buildEqualsBase") else "")
    return (f"merge-gap base build fingerprint {rec['algoCli'].get('sha256')} (phase A; {eq}shipped bundle sha256 "
            f"{rec['reference']['bundleSha256']}; " + "; ".join(
                f"{k}: {q['nights']} nights, json sha256 {q['jsonSha256']}, per-epoch sha256 {q['perEpochSha256']}"
                for k, q in v.items()) + ")")


def run_gates(inp: napsrealistic.Inputs, cli: AlgoCli, out: Path, base_dir: Path, jobs: int = 4,
              log_path: Path | None = None, full_tests: bool = True) -> dict:
    """``train.py mergegap-gates`` (before the code commit): G0 (without U1), G1, G2 (a and b; b under arm A only) and
    G4, with the code that is then committed; ``gates.json``."""
    out.mkdir(parents=True, exist_ok=True)
    gates: dict = {}

    def fatal(name: str, rec: Mapping) -> None:
        gates[name] = dict(rec)
        write_json(out / "gates.json", gates)
        if not rec.get("passed"):
            raise GateFailure(f"{name} failed", gates)

    g0 = gate_g0(cli, log_path, experiment=False)
    fatal("G0", g0)
    fatal("G1", napsrealistic.gate_g1(inp, cli, out, jobs)[0])
    check_build(cli, g0, "after G1")
    fatal("G2", gate_g2(cli, inp.nights_root, base_dir, out, full_tests=full_tests))
    fatal("G4", gate_g4())
    check_build(cli, g0, "after G4")
    return gates


def experiment_preflight(log_path: Path | None) -> dict:
    """Before the START line of ``train.py mergegap``: the pre-registration and exactly one U1 NOTE after it (and before
    an earlier START of the subcommand, if any)."""
    pre = prereg_record(log_path)
    u1 = u1_record(log_path)
    if not pre["passed"] or not u1["passed"]:
        raise MergeGapError(f"refused: the pre-registration NOTE and exactly one 'NOTE merge-gap U1 DMAX <minutes>: "
                            f"<answer>' line after it must be in {log_path} before the first START of train.py "
                            f"mergegap: {pre} {u1}")
    return {"prereg": pre, "u1": u1}


def run_experiment(inp: napsrealistic.Inputs, cli: AlgoCli, out: Path, base_dir: Path, jobs: int = 4,
                   log_path: Path | None = None, full_tests: bool = True) -> dict:
    """``train.py mergegap``: G0-G2 and G4, the V3 replays (Gradle, first), the constructs and their decodes (G3, G5,
    G6, G7, G8), V4, the cells, the rule; writes ``out/mergegap/`` (section 20)."""
    out.mkdir(parents=True, exist_ok=True)
    gates: dict = {}

    def save_gates() -> None:
        write_json(out / "gates.json", gates)

    def fatal(name: str, rec: Mapping) -> None:
        gates[name] = dict(rec)
        save_gates()
        if not rec.get("passed"):
            raise GateFailure(f"{name} failed", gates)

    g0 = gate_g0(cli, log_path, experiment=True)
    fatal("G0", g0)
    d_max = g0["u1"]["dMax"]
    fatal("G1", napsrealistic.gate_g1(inp, cli, out, jobs)[0])
    fatal("G2", gate_g2(cli, inp.nights_root, base_dir, out, full_tests=full_tests))
    fatal("G4", gate_g4())
    check_build(cli, g0, "before the replays")
    v3dir = out / "v3"
    v3run = run_replays(v3dir / "mergegap-v3.json", ARM_ORDER)
    write_json(v3dir / "gradle.json", v3run)
    v3rep = read_json(v3dir / "mergegap-v3.json") if (v3dir / "mergegap-v3.json").is_file() else {"replays": []}
    v3 = evaluate_v3(v3rep, d_max)
    v3["gradle"] = {k: v3run[k] for k in ("command", "exitCode", "passed", "reportSha256")}
    check_build(cli, g0, "after the replays")
    hosts, counts = all_hosts(inp)
    write_json(out / "constructs" / "hosts.json", {"hosts": hosts, "counts": counts})
    g3n = gate_g3_nights(inp)
    dec = decode_all(inp, cli, out, hosts, jobs)
    check_build(cli, g0, "after the decodes")
    results = dec["results"]
    rows = sorted((r for res in results for r in res["rows"]), key=lambda r: r["id"])
    pcs = sorted((r for res in results for r in res["pcs"]), key=lambda r: r["id"])
    napmodel._write_jsonl(out / "regions.jsonl", rows)
    napmodel._write_jsonl(out / "pc.jsonl", pcs)
    write_json(out / "constructs" / "digests.json", {"sessionFiles": [r["file"] for r in results],
                                                     "constructs": {k: v for r in results
                                                                    for k, v in sorted(r["digests"].items())}})
    g3c = gate_g3_constructs(results)
    fatal("G3", {"nights": g3n, "constructs": g3c, "passed": bool(g3n["passed"] and g3c["passed"])})
    fatal("G5", gate_g5(rows, pcs))
    v4 = v4_record()
    fatal("G6", gate_g6(results, v4))
    return finish(out, inp, rows, pcs, gates, counts, v3, v4, g0, dec, d_max)


def finish(out: Path, inp: napsrealistic.Inputs | None, rows: Sequence[Mapping], pcs: Sequence[Mapping], gates: dict,
           counts: Mapping, v3: Mapping, v4: Mapping, g0: Mapping, dec: Mapping | None, d_max: int | None) -> dict:
    """The cells, G7, G8, the rule and the outputs (``cells.json``, ``summary.json``, ``report.md``)."""
    subject_ds = {r["subject"]: r["dataset"] for r in rows}
    dataset_of = (lambda s: inp.index.dataset_of(s)) if inp is not None else (lambda s: subject_ds[s])
    idx = ConstructIndex(rows)
    pc_pairs = pair_pcs(pcs)
    cells = compute_cells(idx, pc_pairs, dataset_of)
    gates["G7"] = gate_g7(counts, rows, pcs, cells)
    gates["G8"] = {"seed": SEED, "resamples": N_BOOT, "decodeBlock": DECODE_BLOCK, "maxCalls": MAX_CALLS,
                   "calls": (dec or {}).get("calls"), "sessionsPerVariant": (dec or {}).get("sessionsPerVariant"),
                   "passed": None}
    write_json(out / "gates.json", gates)
    cov = coverage_summary(idx)
    decision = decide(all(g.get("passed") is not False for g in gates.values()), None, cells, v3, v4, cov)
    reported = {"designs": design_table(idx), "cells": reported_cells(idx, pc_pairs, dataset_of),
                "estimators": estimator_mismatches(idx), "cClassification": c_classification(idx), "coverage": cov}
    write_json(out / "cells.json", {"cells": cells, "reported": _clean(reported)})
    summary = {"meta": {"prereg": PREREG_PATH, "preregSha256": PREREG_SHA256, "algoCli": g0.get("algoCli"),
                        "code": g0.get("code"), "u1": g0.get("u1"), "baseCommit": BASE_COMMIT,
                        "replayReportSha256": (v3.get("gradle") or {}).get("reportSha256")},
               "gates": {k: g.get("passed") for k, g in gates.items()}, "dMax": d_max,
               "cells": {k: {x: c[x] for x in ("outcome", "passed", "lowerBound", "evaluable", "positiveControl",
                                                "reasons", "margin")} for k, c in cells.items()},
               "V3": v3, "V4": v4, "decision": decision, "coverage": cov}
    write_json(out / "summary.json", _clean(summary))
    (out / "report.md").write_text(render(_clean(summary), _clean(cells)), encoding="utf-8")
    return summary


def stopped_summary(out: Path, gates: Mapping, err: GateFailure, g0: Mapping | None = None) -> dict:
    """Case 0: a fatal gate failed; A is kept and no decision is made (written to ``summary.json``)."""
    failed = [k for k, g in gates.items() if isinstance(g, dict) and g.get("passed") is False]
    decision = {"case": 0, "adopted": "A", "decision": False,
                "reasons": [f"stopped by gate {', '.join(failed)}: {err}"]}
    summary = {"meta": {"prereg": PREREG_PATH, "preregSha256": PREREG_SHA256}, "gates": {k: g.get("passed")
                                                                                          for k, g in gates.items()},
               "decision": decision}
    write_json(out / "summary.json", _clean(summary))
    (out / "report.md").write_text(render(_clean(summary), {}), encoding="utf-8")
    return summary


def rerender(out: Path) -> dict:
    """``train.py mergegap-report``: re-render ``report.md`` from ``summary.json`` and ``cells.json`` (no algo-cli)."""
    summary = read_json(out / "summary.json")
    cells = read_json(out / "cells.json")["cells"] if (out / "cells.json").is_file() else {}
    (out / "report.md").write_text(render(summary, cells), encoding="utf-8")
    return summary


def _clean(obj):
    """JSON-safe: NaN / inf as None, numpy scalars as Python numbers, tuples as lists."""
    if isinstance(obj, dict):
        return {str(k): _clean(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_clean(v) for v in obj]
    if isinstance(obj, (np.integer,)):
        return int(obj)
    if isinstance(obj, (float, np.floating)):
        x = float(obj)
        return x if math.isfinite(x) else None
    if isinstance(obj, np.ndarray):
        return _clean(obj.tolist())
    return obj


def result_line(summary: Mapping) -> str:
    d = summary["decision"]
    cells = ", ".join(f"{k} {v['outcome']}" for k, v in (summary.get("cells") or {}).items())
    return (f"case {d['case']}: {'adopt ' + d['adopted'] if d['decision'] else 'no decision, A kept'}"
            + (f" ({cells})" if cells else "") + f"; gates {summary.get('gates')}; report out/mergegap/report.md")


# ==================================================================================================== report ====

def _f(x, nd: int = 3) -> str:
    return "n/a" if x is None or (isinstance(x, float) and not math.isfinite(x)) else f"{x:+.{nd}f}" \
        if isinstance(x, float) else str(x)


def render(summary: Mapping, cells: Mapping) -> str:
    """``report.md``: the decision, the gates, every cell (each (v, h), the point checks, the positive control), V3, V4
    and the coverage."""
    d = summary["decision"]
    lines = ["# Merge gap (pre-registered: docs/prereg/prereg-merge-gap.md)", "",
             f"Pre-registration SHA-256 `{summary.get('meta', {}).get('preregSha256')}`.", "",
             "## Decision", "",
             f"Case {d['case']}: **{'adopt ' + d['adopted'] if d.get('decision') else 'A kept, no decision'}**.", ""]
    lines += [f"- {r}" for r in d.get("reasons", [])] or ["- (no reason recorded)"]
    if d.get("booleans"):
        lines += ["", "Booleans: " + ", ".join(f"{k} {'yes' if v else 'no'}" for k, v in d["booleans"].items())]
    lines += ["", "## Gates", "", "| gate | passed |", "|---|---|"]
    lines += [f"| {k} | {v} |" for k, v in (summary.get("gates") or {}).items()]
    if cells:
        lines += ["", "## Cells", ""]
        for name, c in cells.items():
            lines += [f"### {name}: {c['outcome']}", "",
                      f"Margin {c['margin']}; lowest lower bound {_f(c['lowerBound'], 4)}; evaluable {c['evaluable']}; "
                      f"positive control {c['positiveControl']}.", ""]
            lines += [f"- {r}" for r in c["reasons"]]
            lines += ["", "| variant | stretch | subjects | mean | 95 % CI | point checks |",
                      "|---|---|---|---|---|---|"]
            for q in c["perVariant"]:
                pcs = ", ".join(f"{k} {_f(v, 4)}" for k, v in q["pointChecks"].items())
                lines.append(f"| {q['variant']} | {q['stretch']} | {q['n']} | {_f(q['mean'], 4)} | "
                             f"[{_f(q['ciLow'], 4)}, {_f(q['ciHigh'], 4)}] | {pcs} |")
            for q in c.get("positiveControlRecords") or []:
                lines.append(f"- positive control {q['variant']}: {_f(q['mean'], 4)} [{_f(q['ciLow'], 4)}, "
                             f"{_f(q['ciHigh'], 4)}], detected {q['detected']}")
            lines.append("")
    v3 = summary.get("V3")
    if v3:
        lines += ["## Sleep as Android (V3)", "", f"D_max (U1): {v3.get('dMax')} min.", "",
                  "| arm | V3a | V3b (median added delay, min) | U |", "|---|---|---|---|"]
        for arm in ARM_ORDER:
            a = (v3.get("V3a") or {}).get(arm, {})
            b = (v3.get("V3b") or {}).get(arm, {})
            u = (v3.get("U") or {}).get(arm)
            lines.append(f"| {arm} | {a.get('passed')} | {b.get('medianAddedDelayMin')} | {u} |")
        lines.append("")
    v4 = summary.get("V4")
    if v4:
        lines += ["## Label (V4)", "", ", ".join(f"{k}: {'one night' if v else 'split'}"
                                                 for k, v in v4["continuation"].items()), ""]
    cov = summary.get("coverage")
    if cov:
        lines += ["## Coverage (reported)", "", "| arm | Cov_staged | Cov_any | E2E |", "|---|---|---|---|"]
        for arm in ARM_ORDER:
            q = cov.get(arm, {})
            lines.append(f"| {arm} | {_f(q.get('staged'))} | {_f(q.get('any'))} | {_f(q.get('e2e'))} |")
        lines.append("")
    return "\n".join(lines) + "\n"
