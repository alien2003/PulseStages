"""Session pools of the pre-registered realistic-nap experiment (``docs/prereg/prereg-naps-realistic.md`` section 4):
the authoritative definitions of the training tiles, the evaluation pools, the mid-anchor pool, the LATESTART sessions
and the synthetic FEATURELESS sessions. Pure functions on night files (docs/INTERCHANGE.md section 1); no algo-cli.

Every session is cut from the OHealth-like session [t0, t1] of a night by ``napmodel.cut_window`` (``naps.nap_proxy``
for the onset sessions at the anchors), unchanged: heart rate with s0 - 30 min <= t <= s1, step records ending in
(s0 - 30 min, s1], label epochs fully inside [s0, s1], nothing after s1. The cut depends on the labels and the session
boundaries only, so both variants of a night get identical windows. Wake bouts are ``napmodel.wake_bouts``.

- **TILE** (:func:`tiles`, section 4.3): consecutive, non-overlapping tiles from t0, the length of tile k chosen by the
  SHA-256 of ``"<night key>|tile|<k>"`` mod 6 among the anchors (the largest anchor that still fits when it does not),
  while at least 30 min remain; a remainder joins the last tile when the result stays below 180 min.
- **EVAL** (:func:`eval_sessions`, section 4.4): O onset (``-nap<L>``), W ends-in-wake (``napmodel.sample_windows``,
  ``-ew<m>``), M mid-night (``-mid<o>x<L>``, o in 120 / 180 / 240 / 300 min) and R return to sleep (``-rs<m>x<L>``) at
  the anchors; MIDANCHOR (:func:`midanchor_sessions`) the O, M and R rules at the mid-anchors.
- Regimes: EARLY = O; LATE = M with o >= 180 and R; OTHER = M with o = 120 and W; MATCHED = EARLY and LATE at 60 / 90.
- **LATESTART** (:func:`late_start`, section 4.5): [t0 + delta, t0 + L], delta in 10 / 20 min, L in 60 / 90.
- **FEATURELESS** (:func:`featureless`, :func:`featureless_set`): flat synthetic heart rate, three designs, three
  noise levels, seeds 0-19, one copy per Walch subject with the subject in the id.

:func:`count_pools` recounts every pool from the labels and session boundaries (gate G7, section 4.6).
"""

from __future__ import annotations

import functools
import hashlib
from collections import Counter, defaultdict
from typing import Iterable, Mapping, Sequence

import numpy as np

from . import napmodel, naps
from .nightset import dataset_from_id
from .util import STAGE_INDEX

MIN = 60_000
EPOCH_MS = 30_000
ANCHORS: tuple[int, ...] = (30, 60, 90, 120, 150, 170)            # A, minutes
MID_ANCHORS: tuple[int, ...] = (45, 75, 105, 135, 160)            # A_mid, the midpoints of A
M_OFFSETS: tuple[int, ...] = (120, 180, 240, 300)                 # mid-night window starts after t0, minutes
LATE_OFFSET_MIN = 180                                             # M windows starting at or after this are LATE
R_MIN_BOUT_MS = 2 * MIN                                           # a return-to-sleep bout lasts at least 2 min
R_MIN_END_MS = 180 * MIN                                          # ... and ends at least 180 min after t0
R_MARGIN_MS = 5 * MIN                                             # s0 = bout end - min(5 min, bout length)
MATCHED_LENGTHS: tuple[int, ...] = (60, 90)
LATESTART_LENGTHS: tuple[int, ...] = (60, 90)
LATESTART_DELAYS: tuple[int, ...] = (10, 20)
TILE_MIN_MS = 30 * MIN                                            # a new tile starts while >= 30 min remain
TILE_MAX_MS = 180 * MIN                                           # a remainder joins the last tile below 180 min
FEATURELESS_DESIGNS: tuple[str, ...] = ("flat", "pre10", "post12")
FEATURELESS_SHIFT = {"flat": (0.0, 0.0), "pre10": (10.0, 0.0), "post12": (0.0, 12.0)}   # (before s0, after s1), bpm
FEATURELESS_SDS: tuple[int, ...] = (1, 2, 3)
FEATURELESS_SEEDS: tuple[int, ...] = tuple(range(20))
FEATURELESS_S0_MS = 3_600_000
FEATURELESS_HR_EVERY_MS = 120_000
FEATURELESS_CONTEXT_MS = 30 * MIN
FEATURELESS_LEVEL = 60.0
POOLS = ("O", "W", "M", "R")


class PoolError(RuntimeError):
    """A session pool cannot be built as defined (duplicate ids, inconsistent variants)."""


def _bounds(night: Mapping) -> tuple[int, int]:
    return int(night["sessionStartMs"]), int(night["sessionEndMs"])


def _meta(night: Mapping, kind: str, pool: str, s0: int, s1: int, *, regime: str | None = None,
          o: int | None = None, length: float | None = None, **extra) -> dict:
    t0, _ = _bounds(night)
    return {"kind": kind, "pool": pool, "regime": regime, "L": float((s1 - s0) / MIN if length is None else length),
            "o": o, "offsetMin": (s0 - t0) / MIN, "startMs": int(s0), "endMs": int(s1), "base": str(night["id"]),
            "subject": str(night["subject"]), "dataset": dataset_from_id(str(night["id"])),
            "variant": night.get("variant"), **extra}


# ------------------------------------------------------------------------------------- tiles (section 4.3) ----

def tile_index(night_key: str, k: int, n: int = len(ANCHORS)) -> int:
    """i_k = the SHA-256 hex digest of ``"<night key>|tile|<k>"`` (UTF-8) read as a base-16 integer, mod ``n``."""
    return int(hashlib.sha256(f"{night_key}|tile|{k}".encode("utf-8")).hexdigest(), 16) % n


def tile_bounds(night: Mapping) -> tuple[list[tuple[int, int]], int]:
    """([s0, s1] of every tile, uncovered remainder in ms) of ``night`` by the rule of section 4.3."""
    t0, t1 = _bounds(night)
    key = napmodel.night_key(str(night["id"]))
    tiles: list[list[int]] = []
    k, t = 0, t0
    while t1 - t >= TILE_MIN_MS:
        length = ANCHORS[tile_index(key, k)] * MIN
        if t + length > t1:
            length = max(a * MIN for a in ANCHORS if t + a * MIN <= t1)
        tiles.append([t, t + length])
        t += length
        k += 1
    r = t1 - t
    if r > 0 and tiles and (tiles[-1][1] - tiles[-1][0]) + r < TILE_MAX_MS:
        tiles[-1][1] = t1
        r = 0
    return [(a, b) for a, b in tiles], r


def tiles(night: Mapping) -> list[tuple[dict, dict]]:
    """(session, meta) of every tile of ``night``, ids ``<night id>-tile<k>``."""
    out = []
    for k, (s0, s1) in enumerate(tile_bounds(night)[0]):
        sid = f"{night['id']}-tile{k}"
        out.append((napmodel.cut_window(night, s0, s1, sid), _meta(night, "tile", "TILE", s0, s1, k=k)))
    return out


# --------------------------------------------------------------------------- evaluation pools (section 4.4) ----

def regime_of(pool: str, o: int | None) -> str:
    """EARLY = O; LATE = M with o >= 180 and R; OTHER = M with o = 120 and W."""
    if pool == "O":
        return "EARLY"
    if pool == "R" or (pool == "M" and o is not None and o >= LATE_OFFSET_MIN):
        return "LATE"
    if pool in ("M", "W"):
        return "OTHER"
    raise PoolError(f"no regime for pool {pool}")


def onset(night: Mapping, lengths: Sequence[int], kind: str = "eval") -> list[tuple[dict, dict]]:
    """O: s0 = t0, s1 = t0 + L, id ``<night>-nap<L>``; ``naps.nap_proxy`` at the anchors, the same cut by
    ``napmodel.cut_window`` at other lengths; kept when s1 <= t1."""
    t0, t1 = _bounds(night)
    out = []
    for length in lengths:
        s1 = t0 + length * MIN
        if s1 > t1:
            continue
        if length in ANCHORS:
            s = naps.nap_proxy(night, length)
        else:
            s = napmodel.cut_window(night, t0, s1, f"{night['id']}-nap{length:g}")
        out.append((s, _meta(night, kind, "O", t0, s1, regime="EARLY", length=length)))
    return out


def ends_in_wake(night: Mapping) -> list[tuple[dict, dict]]:
    """W: ``napmodel.sample_windows`` (13.12), unchanged, ids ``napmodel.ew_id``."""
    out = []
    for s0, s1 in napmodel.sample_windows(night):
        s = napmodel.cut_window(night, s0, s1, napmodel.ew_id(night, s0))
        out.append((s, _meta(night, "eval", "W", s0, s1, regime="OTHER")))
    return out


def mid(night: Mapping, lengths: Sequence[int], kind: str = "eval") -> list[tuple[dict, dict]]:
    """M: s0 = t0 + o, s1 = s0 + L, o in :data:`M_OFFSETS`, kept when s1 <= t1; id ``<night>-mid<o>x<L>``."""
    t0, t1 = _bounds(night)
    out = []
    for o in M_OFFSETS:
        for length in lengths:
            s0 = t0 + o * MIN
            s1 = s0 + length * MIN
            if s1 <= t1:
                s = napmodel.cut_window(night, s0, s1, f"{night['id']}-mid{o}x{length:g}")
                out.append((s, _meta(night, kind, "M", s0, s1, regime=regime_of("M", o), o=o, length=length)))
    return out


def return_bouts(night: Mapping) -> list[tuple[int, int]]:
    """The wake bouts [b0, b1) that open a return-to-sleep session: b1 - b0 >= 2 min and b1 - t0 >= 180 min."""
    t0, _ = _bounds(night)
    return [(b0, b1) for b0, b1 in napmodel.wake_bouts(night.get("labels", []))
            if b1 - b0 >= R_MIN_BOUT_MS and b1 - t0 >= R_MIN_END_MS]


def return_to_sleep(night: Mapping, lengths: Sequence[int], kind: str = "eval") -> list[tuple[dict, dict]]:
    """R: for every bout of :func:`return_bouts`, s0 = b1 - min(5 min, b1 - b0), s1 = s0 + L, kept when s0 >= t0 and
    s1 <= t1; id ``<night>-rs<(s0 - t0) / MIN:g>x<L>``."""
    t0, t1 = _bounds(night)
    out = []
    for b0, b1 in return_bouts(night):
        s0 = b1 - min(R_MARGIN_MS, b1 - b0)
        for length in lengths:
            s1 = s0 + length * MIN
            if s0 >= t0 and s1 <= t1:
                sid = f"{night['id']}-rs{(s0 - t0) / MIN:g}x{length:g}"
                out.append((napmodel.cut_window(night, s0, s1, sid),
                            _meta(night, kind, "R", s0, s1, regime="LATE", length=length, boutStartMs=b0,
                                  boutEndMs=b1)))
    return out


def eval_sessions(night: Mapping) -> list[tuple[dict, dict]]:
    """The EVAL sessions of ``night``: O, W, M and R at the anchors."""
    return [*onset(night, ANCHORS), *ends_in_wake(night), *mid(night, ANCHORS), *return_to_sleep(night, ANCHORS)]


def midanchor_sessions(night: Mapping) -> list[tuple[dict, dict]]:
    """The MIDANCHOR sessions of ``night`` (not part of EVAL): the O, M and R rules at the mid-anchors."""
    return [*onset(night, MID_ANCHORS, "midanchor"), *mid(night, MID_ANCHORS, "midanchor"),
            *return_to_sleep(night, MID_ANCHORS, "midanchor")]


def is_matched(meta: Mapping) -> bool:
    """MATCHED = EARLY and LATE sessions of 60 or 90 min."""
    return meta.get("regime") in ("EARLY", "LATE") and meta.get("L") in MATCHED_LENGTHS


def is_anchor(meta: Mapping) -> bool:
    """ANCHOR = O, M and R sessions at the anchor lengths (EVAL without W)."""
    return meta.get("kind") == "eval" and meta.get("pool") in ("O", "M", "R")


# ------------------------------------------------------------------------- reported-only (section 4.5) ----

def late_start(night: Mapping) -> list[tuple[dict, dict]]:
    """LATESTART: [t0 + delta, t0 + L] with the labels inside, delta in 10 / 20 min, L in 60 / 90 min, the common cut;
    id ``<night>-ls<delta>x<L>``."""
    t0, t1 = _bounds(night)
    out = []
    for length in LATESTART_LENGTHS:
        for delta in LATESTART_DELAYS:
            s0, s1 = t0 + delta * MIN, t0 + length * MIN
            if s1 <= t1:
                s = napmodel.cut_window(night, s0, s1, f"{night['id']}-ls{delta}x{length}")
                out.append((s, _meta(night, "latestart", "LATESTART", s0, s1, length=length, delta=delta,
                                     original=f"{night['id']}-nap{length}")))
    return out


def featureless_id(design: str, length: int, sd: int, seed: int, subject: str) -> str:
    return f"syn-{design}-L{length}-sd{sd}-k{seed}-{subject}"


def featureless_hr(length: int, design: str, sd: int, seed: int) -> list[list]:
    """Heart-rate samples of a featureless session: every 120 s from s0 - 30 min + 60 s while t <= s1 + 30 min, value
    round(60 + eps) + the design's shift (``pre10``: +10 bpm before s0, ``post12``: +12 bpm after s1), eps ~ N(0, sd^2)
    drawn in time order from ``numpy.random.default_rng([L, i_d, sd, k])`` (i_d = the design's index)."""
    return [list(h) for h in _featureless_hr(int(length), design, int(sd), int(seed))]


@functools.lru_cache(maxsize=4096)
def _featureless_hr(length: int, design: str, sd: int, seed: int) -> tuple[tuple[int, float], ...]:
    if design not in FEATURELESS_SHIFT:
        raise PoolError(f"unknown featureless design {design!r}")
    s0 = FEATURELESS_S0_MS
    s1 = s0 + int(length) * MIN
    times = list(range(s0 - FEATURELESS_CONTEXT_MS + 60_000, s1 + FEATURELESS_CONTEXT_MS + 1, FEATURELESS_HR_EVERY_MS))
    eps = np.random.default_rng([int(length), FEATURELESS_DESIGNS.index(design), int(sd), int(seed)]).normal(
        0.0, float(sd), size=len(times))
    pre, post = FEATURELESS_SHIFT[design]
    out = []
    for t, e in zip(times, eps):
        shift = pre if t < s0 else (post if t > s1 else 0.0)
        out.append((int(t), float(round(FEATURELESS_LEVEL + float(e)) + shift)))
    return tuple(out)


def featureless(length: int, design: str, sd: int, seed: int, subject: str, variant: str | None = None
                ) -> tuple[dict, dict]:
    """One FEATURELESS session (section 4.5): s0 = 3 600 000 ms, s1 = s0 + L min, :func:`featureless_hr`, no steps, a
    placeholder LIGHT label for every 30-s epoch of [s0, s1) (only the predicted column sums are used); the copy of
    subject ``subject`` with id ``syn-<d>-L<L>-sd<sd>-k<k>-<subject>``."""
    s0 = FEATURELESS_S0_MS
    s1 = s0 + int(length) * MIN
    sid = featureless_id(design, length, sd, seed, subject)
    session = {"id": sid, "subject": str(subject), "variant": variant, "sessionStartMs": s0, "sessionEndMs": s1,
               "hr": featureless_hr(length, design, sd, seed), "steps": [],
               "labels": [[t, t + EPOCH_MS, "LIGHT"] for t in range(s0, s1, EPOCH_MS)]}
    meta = {"kind": "featureless", "pool": "FEATURELESS", "regime": None, "L": float(length), "o": None,
            "offsetMin": 0.0, "startMs": s0, "endMs": s1, "base": sid, "subject": str(subject), "dataset": "synthetic",
            "variant": variant, "design": design, "sd": int(sd), "seed": int(seed)}
    return session, meta


def featureless_set(subjects: Iterable[str], seeds: Sequence[int] = FEATURELESS_SEEDS,
                    lengths: Sequence[int] = ANCHORS + MID_ANCHORS, variant: str | None = None
                    ) -> list[tuple[dict, dict]]:
    """Every combination of length, design, noise SD and seed (1980 sessions with seeds 0-19), copied once per
    subject; ids unique (checked)."""
    out = []
    for subject in subjects:
        for length in sorted(lengths):
            for design in FEATURELESS_DESIGNS:
                for sd in FEATURELESS_SDS:
                    for seed in seeds:
                        out.append(featureless(length, design, sd, seed, subject, variant))
    assert_unique_ids(s for s, _ in out)
    return out


def assert_unique_ids(sessions: Iterable[Mapping]) -> int:
    """Raises :class:`PoolError` when two sessions share an id (algo-cli refuses duplicates); returns the count."""
    seen: Counter = Counter(str(s["id"]) for s in sessions)
    dup = sorted(i for i, n in seen.items() if n > 1)
    if dup:
        raise PoolError(f"{len(dup)} duplicate session ids, e.g. {dup[:3]}")
    return sum(seen.values())


def label_counts(session: Mapping) -> np.ndarray:
    """True AWAKE, LIGHT, DEEP, REM 30-s label chunks of ``session`` (all its label epochs)."""
    c = np.zeros(4)
    for a, b, st in session.get("labels", []):
        c[STAGE_INDEX[str(st)]] += (int(b) - int(a)) / EPOCH_MS
    return c


# ----------------------------------------------------------------------------- counts (section 4.6, G7) ----

EXPECTED_COUNTS: dict[str, dict] = {
    "walch": {"O": (186, 31), "W": (11, 9),
              "M120": (171, 31, (31, 31, 30, 27, 26, 26)), "MLATE": (439, 30, (82, 78, 75, 74, 69, 61)),
              "R": (141, 15, (32, 28, 26, 23, 18, 14)), "EVAL": 948,
              "MIDANCHOR": (789, 31, (173, 165, 161, 152, 138)), "tiles": (142, 31), "nights": 31,
              "tileMinutes": (12994.0, 12994.0)},
    "bidsleep": {"O": (1518, 47), "W": (139, 45),
                 "M120": (1505, 47, (253, 253, 253, 253, 249, 244)),
                 "MLATE": (3359, 47, (722, 684, 616, 535, 433, 369)),
                 "R": (1208, 47, (314, 255, 215, 180, 136, 108)), "EVAL": 7729,
                 "MIDANCHOR": (6353, 47, (1498, 1389, 1290, 1144, 1032)), "tiles": (1064, 253), "nights": 253,
                 "tileMinutes": (103231.0, 103339.5)},
}


def _count_key(meta: Mapping) -> str:
    p = meta["pool"]
    if p == "M":
        return "M120" if meta["o"] == 120 else "MLATE"
    return p


def count_pools(nights: Iterable[Mapping]) -> dict:
    """Labels-only counts of section 4.6 for the nights of one variant, per dataset: sessions and subjects per pool
    (M split into o = 120 and o >= 180, per length), EVAL, MIDANCHOR, tiles (count, nights, covered and session
    minutes, nights with an uncovered remainder) and whole nights. Builds every session (ids checked unique)."""
    sessions: dict[str, Counter] = defaultdict(Counter)
    per_len: dict[str, Counter] = defaultdict(Counter)
    subjects: dict[str, dict[str, set]] = defaultdict(lambda: defaultdict(set))
    tile: dict[str, dict] = defaultdict(lambda: {"tiles": 0, "nights": 0, "coveredMin": 0.0, "sessionMin": 0.0,
                                                 "uncoveredMin": 0.0, "nightsUncovered": 0})
    nights_n: Counter = Counter()
    ids: list[str] = []
    for night in nights:
        ds = dataset_from_id(str(night["id"]))
        nights_n[ds] += 1
        for s, m in eval_sessions(night):
            k = _count_key(m)
            sessions[ds][k] += 1
            sessions[ds]["EVAL"] += 1
            subjects[ds][k].add(m["subject"])
            if k != "W":
                per_len[ds][(k, int(m["L"]))] += 1
            ids.append(s["id"])
        for s, m in midanchor_sessions(night):
            sessions[ds]["MIDANCHOR"] += 1
            subjects[ds]["MIDANCHOR"].add(m["subject"])
            per_len[ds][("MIDANCHOR", int(m["L"]))] += 1
            ids.append(s["id"])
        tb, r = tile_bounds(night)
        t0, t1 = _bounds(night)
        tc = tile[ds]
        tc["tiles"] += len(tb)
        tc["nights"] += 1 if tb else 0
        tc["coveredMin"] += sum(b - a for a, b in tb) / MIN
        tc["sessionMin"] += (t1 - t0) / MIN
        tc["uncoveredMin"] += r / MIN
        tc["nightsUncovered"] += int(r > 0)
        ids.extend(f"{night['id']}-tile{k}" for k in range(len(tb)))
    assert_unique_ids({"id": i} for i in ids)
    out = {}
    for ds in sorted(nights_n):
        c = {"nights": nights_n[ds], "EVAL": sessions[ds]["EVAL"], "tiles": dict(tile[ds])}
        for k in ("O", "W", "M120", "MLATE", "R", "MIDANCHOR"):
            lens = MID_ANCHORS if k == "MIDANCHOR" else ANCHORS
            c[k] = {"sessions": sessions[ds][k], "subjects": len(subjects[ds][k])}
            if k not in ("O", "W"):
                c[k]["perLength"] = [per_len[ds][(k, L)] for L in lens]
        out[ds] = c
    return out


def compare_counts(counts: Mapping, expected: Mapping = EXPECTED_COUNTS) -> list[str]:
    """Differences between :func:`count_pools` and the counts of section 4.6 (empty = identical)."""
    diffs = []
    for ds, e in expected.items():
        c = counts.get(ds)
        if c is None:
            diffs.append(f"{ds}: no nights")
            continue
        for k in ("O", "W"):
            got = (c[k]["sessions"], c[k]["subjects"])
            if got != e[k]:
                diffs.append(f"{ds} {k}: {got} != {e[k]}")
        for k in ("M120", "MLATE", "R", "MIDANCHOR"):
            got = (c[k]["sessions"], c[k]["subjects"], tuple(c[k]["perLength"]))
            if got != e[k]:
                diffs.append(f"{ds} {k}: {got} != {e[k]}")
        if c["EVAL"] != e["EVAL"]:
            diffs.append(f"{ds} EVAL: {c['EVAL']} != {e['EVAL']}")
        t = c["tiles"]
        if (t["tiles"], t["nights"]) != e["tiles"]:
            diffs.append(f"{ds} tiles: {(t['tiles'], t['nights'])} != {e['tiles']}")
        if (round(t["coveredMin"], 1), round(t["sessionMin"], 1)) != e["tileMinutes"]:
            diffs.append(f"{ds} tile minutes: {(t['coveredMin'], t['sessionMin'])} != {e['tileMinutes']}")
        if c["nights"] != e["nights"]:
            diffs.append(f"{ds} nights: {c['nights']} != {e['nights']}")
    return diffs
