"""Bring PSG-night recordings to "OHealth conditions": one HR value per 2 minutes, a session window like the one
OHealth reports, and 4-class labels (see tools/train/README.md and the spec, algorithm part, section 9).

All times are integer milliseconds on the dataset clock (for Walch: 0 = PSG start, negative = before it).
"""

from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass

import numpy as np

GRID_MS = 120_000          # OHealth: one HR value per 2 minutes
NEAREST_TOL_MS = 30_000    # "instantaneous": nearest raw sample within ±30 s of the grid point
EPOCH_MS = 30_000          # PSG scoring epoch
SESSION_MARGIN_MS = 5 * 60_000
CONTEXT_MS = 30 * 60_000   # HR / steps context included around the session

# Walch et al. 2019 / PhysioNet sleep-accel label codes (dataset page: "stage (0-5, wake = 0, N1 = 1, N2 = 2,
# N3 = 3, REM = 5)"; ojwalch/sleep_classifiers source/sleep_stage.py + psg_converter.py additionally define
# N4 = 4 (R&K), unscored = -1 and 6 -> unscored).
WALCH_CODE_TO_STAGE: dict[int, str] = {0: "AWAKE", 1: "LIGHT", 2: "LIGHT", 3: "DEEP", 4: "DEEP", 5: "REM"}
# BIDSleep v1.0.1 (README "Sleep Stage Encoding", AASM, Dreem 2): Wake 0, N1 1, N2 2, N3 3, REM 4, Unknown 5.
BIDSLEEP_CODE_TO_STAGE: dict[int, str] = {0: "AWAKE", 1: "LIGHT", 2: "LIGHT", 3: "DEEP", 4: "REM"}
STAGES = ("AWAKE", "LIGHT", "DEEP", "REM")


# --------------------------------------------------------------------------------------------- heart rate ----

def grid_phase_ms(subject: str, grid_ms: int = GRID_MS) -> int:
    """Deterministic per-subject phase of the 2-min grid in [0, grid_ms): reproducible, but not aligned to PSG start
    for most subjects (it is derived from a hash of the subject id)."""
    digest = hashlib.sha256(f"pulsestages/ohealth-grid/{subject}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") % grid_ms


def grid_points(start_ms: int, end_ms: int, phase_ms: int, grid_ms: int = GRID_MS) -> np.ndarray:
    """All t = phase + k·grid with start <= t <= end."""
    k0 = math.ceil((start_ms - phase_ms) / grid_ms)
    k1 = math.floor((end_ms - phase_ms) / grid_ms)
    if k1 < k0:
        return np.empty(0, dtype=np.int64)
    return phase_ms + grid_ms * np.arange(k0, k1 + 1, dtype=np.int64)


def clean_hr(t_ms: np.ndarray, bpm: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Sorted by time, finite positive values only, duplicate timestamps removed (first kept).

    Physiological range filtering ([30, 180] bpm) and outlier removal are the estimator's job (Kotlin), not ours.
    """
    t = np.asarray(t_ms, dtype=np.int64)
    v = np.asarray(bpm, dtype=float)
    ok = np.isfinite(v) & (v > 0)
    t, v = t[ok], v[ok]
    order = np.argsort(t, kind="stable")
    t, v = t[order], v[order]
    if t.size:
        keep = np.concatenate(([True], np.diff(t) > 0))
        t, v = t[keep], v[keep]
    return t, v


def resample_instantaneous(t_ms: np.ndarray, bpm: np.ndarray, start_ms: int, end_ms: int, phase_ms: int,
                           grid_ms: int = GRID_MS, tol_ms: int = NEAREST_TOL_MS) -> list[list]:
    """For every grid point in [start, end]: the raw sample nearest to it if within ±tol (ties -> earlier sample),
    reported at the grid time; grid points without such a sample produce no value."""
    t, v = clean_hr(t_ms, bpm)
    grid = grid_points(start_ms, end_ms, phase_ms, grid_ms)
    if t.size == 0 or grid.size == 0:
        return []
    right = np.searchsorted(t, grid, side="left")          # first sample >= grid
    left = right - 1
    right_c = np.clip(right, 0, t.size - 1)
    left_c = np.clip(left, 0, t.size - 1)
    d_right = np.where(right < t.size, t[right_c] - grid, np.iinfo(np.int64).max)
    d_left = np.where(left >= 0, grid - t[left_c], np.iinfo(np.int64).max)
    use_left = d_left <= d_right
    idx = np.where(use_left, left_c, right_c)
    dist = np.where(use_left, d_left, d_right)
    ok = dist <= tol_ms
    return [[int(g), float(v[i])] for g, i in zip(grid[ok], idx[ok])]


def resample_mean2min(t_ms: np.ndarray, bpm: np.ndarray, start_ms: int, end_ms: int, phase_ms: int,
                      grid_ms: int = GRID_MS) -> list[list]:
    """Mean of all raw samples in each window [phase + k·grid, phase + (k+1)·grid), reported at the window centre;
    windows whose centre lies in [start, end] are emitted when they contain at least one sample."""
    t, v = clean_hr(t_ms, bpm)
    half = grid_ms // 2
    centres = grid_points(start_ms - half, end_ms - half, phase_ms, grid_ms) + half
    centres = centres[(centres >= start_ms) & (centres <= end_ms)]
    if t.size == 0 or centres.size == 0:
        return []
    lo = np.searchsorted(t, centres - half, side="left")
    hi = np.searchsorted(t, centres + half, side="left")
    csum = np.concatenate(([0.0], np.cumsum(v)))
    n = hi - lo
    ok = n > 0
    means = (csum[hi[ok]] - csum[lo[ok]]) / n[ok]
    return [[int(c), round(float(m), 3)] for c, m in zip(centres[ok], means)]


VARIANT_RESAMPLERS = {
    "instantaneous": resample_instantaneous,
    "mean2min": resample_mean2min,
}


# ----------------------------------------------------------------------------------------- labels / session ----

def map_code(code: float, mapping: dict[int, str]) -> str | None:
    """Dataset stage code -> SleepStage name; codes not in [mapping] (unscored / unknown), NaN and non-integers ->
    None."""
    if code is None or not np.isfinite(code) or float(code) != int(code):
        return None
    return mapping.get(int(code))


def map_walch_code(code: float) -> str | None:
    """Walch PSG code -> SleepStage name; unscored (-1, 6, non-integers, anything unknown) -> None."""
    return map_code(code, WALCH_CODE_TO_STAGE)


@dataclass(frozen=True)
class LabelEpoch:
    start_ms: int
    end_ms: int
    code: int | None
    stage: str | None   # None = unscored


def label_epochs(t_s: np.ndarray, codes: np.ndarray, epoch_ms: int = EPOCH_MS,
                 mapping: dict[int, str] = WALCH_CODE_TO_STAGE) -> list[LabelEpoch]:
    """One 30-s epoch per label row, starting at its timestamp (seconds -> ms = round(s·1000))."""
    out = []
    for ts, c in zip(np.asarray(t_s, float), np.asarray(codes, float)):
        if not np.isfinite(ts):
            continue
        start = int(round(ts * 1000.0))
        stage = map_code(c, mapping)
        code = int(c) if np.isfinite(c) and float(c) == int(c) else None
        out.append(LabelEpoch(start, start + epoch_ms, code, stage))
    out.sort(key=lambda e: e.start_ms)
    return out


def label_epochs_from_start(start_ms: int, codes: np.ndarray, mapping: dict[int, str],
                            epoch_ms: int = EPOCH_MS) -> list[LabelEpoch]:
    """Consecutive epochs [start + k·30 s, start + (k+1)·30 s) for a code sequence (BIDSleep layout)."""
    out = []
    for k, c in enumerate(np.asarray(codes, float)):
        code = int(c) if np.isfinite(c) and float(c) == int(c) else None
        a = int(start_ms) + k * epoch_ms
        out.append(LabelEpoch(a, a + epoch_ms, code, map_code(c, mapping)))
    return out


def scored_range(epochs: list[LabelEpoch]) -> tuple[int, int] | None:
    """[start of the first scored epoch, end of the last scored epoch]; None if nothing is scored."""
    scored = [e for e in epochs if e.stage is not None]
    if not scored:
        return None
    return scored[0].start_ms, scored[-1].end_ms


def ohealth_session(epochs: list[LabelEpoch], margin_ms: int = SESSION_MARGIN_MS) -> tuple[int, int] | None:
    """OHealth-like session: first non-wake epoch − margin .. end of the last non-wake epoch + margin, clipped to the
    scored PSG range. None if the night has no scored sleep."""
    rng = scored_range(epochs)
    sleep = [e for e in epochs if e.stage is not None and e.stage != "AWAKE"]
    if rng is None or not sleep:
        return None
    start = max(rng[0], sleep[0].start_ms - margin_ms)
    end = min(rng[1], sleep[-1].end_ms + margin_ms)
    return start, end


def psg_session(epochs: list[LabelEpoch]) -> tuple[int, int] | None:
    """Full scored PSG range."""
    return scored_range(epochs)


SESSION_DEFINITIONS = {"ohealth": ohealth_session, "psg": psg_session}


def session_labels(epochs: list[LabelEpoch], start_ms: int, end_ms: int) -> list[list]:
    """Scored epochs fully inside [start, end] as [startMs, endMs, stage]; unscored epochs are omitted."""
    return [[e.start_ms, e.end_ms, e.stage] for e in epochs
            if e.stage is not None and e.start_ms >= start_ms and e.end_ms <= end_ms]


def stage_minutes(labels: list[list]) -> dict[str, float]:
    mins = {s: 0.0 for s in STAGES}
    for a, b, s in labels:
        mins[s] += (b - a) / 60_000.0
    return mins


def coverage_proxy(hr: list[list], start_ms: int, end_ms: int, max_dist_ms: int = 10 * 60_000,
                   epoch_ms: int = 60_000) -> float:
    """Fraction of 1-min session epochs whose midpoint is within max_dist of an HR sample."""
    n = max(0, math.ceil((end_ms - start_ms) / epoch_ms))
    if n == 0:
        return 0.0
    if not hr:
        return 0.0
    t = np.asarray([h[0] for h in hr], dtype=np.int64)
    mids = start_ms + epoch_ms * np.arange(n, dtype=np.int64) + epoch_ms // 2
    pos = np.searchsorted(t, mids)
    d_r = np.where(pos < t.size, t[np.clip(pos, 0, t.size - 1)] - mids, np.iinfo(np.int64).max)
    d_l = np.where(pos > 0, mids - t[np.clip(pos - 1, 0, t.size - 1)], np.iinfo(np.int64).max)
    return float(np.mean(np.minimum(d_l, d_r) <= max_dist_ms))
