"""Conservative wrist-pedometer emulation on raw tri-axial acceleration (in g).

OHealth reports steps from the watch's own pedometer; the Walch dataset gives the raw Apple Watch accelerometer for
the PSG night, so we emulate a pedometer on it:

1. magnitude |a| = sqrt(x² + y² + z²) (orientation independent; the vertical step impact shows up at step
   frequency);
2. split the (irregularly sampled) recording into segments wherever two samples are more than ``max_gap_s``
   (1 s) apart, and linearly resample each segment to a uniform ``fs`` (50 Hz) grid — never interpolating across a
   gap;
3. zero-phase Butterworth band-pass ``band`` (0.5–3 Hz, order 4; ``butter(output="sos")`` + ``sosfiltfilt``, the
   numerically stable second-order-sections form of ``filtfilt``);
4. peak detection (``find_peaks``) with a minimum height / prominence and a minimum peak distance;
5. a peak only counts as a step inside a *walking bout*: at least ``min_bout_peaks`` (8) consecutive peaks whose
   inter-peak intervals are all within [``min_interval_s``, ``max_interval_s``] = [0.3, 1.5] s (a run is split
   wherever an interval deviates more than ``max_local_deviation`` (70 %) from the run's median interval), and that
   are regular:
   * every bout: coefficient of variation (CV) of the step intervals <= ``short_bout_max_cv`` (0.2), or
   * sustained bouts of >= ``long_bout_peaks`` (20) peaks only: stride CV (CV of the sums of two consecutive
     intervals, which tolerates the left/right asymmetry typical at the wrist) <= ``long_bout_max_stride_cv`` (0.25)
     or step CV <= ``long_bout_max_cv`` (0.3).

The detector is deliberately conservative: false steps during sleep are worse for staging than missed steps,
because >= 5 steps per epoch force AWAKE in the estimator. The two-tier regularity rule was chosen on the Walch nights
(see DATASETS.md): candidate runs during PSG-scored sleep are short (8-20 peaks) and only moderately regular, while
real walking (arrival, bathroom trips, getting up) is either clearly rhythmic or sustained. Short, asymmetric walks
and arm-swing-dominated walking whose magnitude peaks at the stride rather than the step frequency are under-counted;
that is accepted.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
from scipy.signal import butter, find_peaks, sosfiltfilt

MINUTE_MS = 60_000


@dataclass(frozen=True)
class PedometerParams:
    fs: float = 50.0
    max_gap_s: float = 1.0
    band: tuple[float, float] = (0.5, 3.0)
    order: int = 4
    min_peak_height_g: float = 0.05
    min_peak_prominence_g: float = 0.08
    min_peak_distance_s: float = 0.3
    min_interval_s: float = 0.3
    max_interval_s: float = 1.5
    min_bout_peaks: int = 8
    max_local_deviation: float = 0.7
    short_bout_max_cv: float = 0.2
    long_bout_peaks: int = 20
    long_bout_max_stride_cv: float = 0.25
    long_bout_max_cv: float = 0.3
    min_segment_s: float = 3.0


@dataclass
class StepDetection:
    step_times_s: np.ndarray                      # time of every counted step (seconds, dataset clock)
    bouts: list[tuple[float, float, int]] = field(default_factory=list)  # (first peak s, last peak s, steps)
    segments: list[tuple[float, float]] = field(default_factory=list)    # motion coverage (start s, end s)
    peaks_total: int = 0                          # all band-pass peaks, counted or not (diagnostics)


def clean_motion(t: np.ndarray, x: np.ndarray, y: np.ndarray, z: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Returns (time s, magnitude g) sorted by time, finite only, duplicate timestamps removed (first kept)."""
    t = np.asarray(t, dtype=float)
    mag = np.sqrt(np.asarray(x, float) ** 2 + np.asarray(y, float) ** 2 + np.asarray(z, float) ** 2)
    ok = np.isfinite(t) & np.isfinite(mag)
    t, mag = t[ok], mag[ok]
    order = np.argsort(t, kind="stable")
    t, mag = t[order], mag[order]
    if t.size:
        keep = np.concatenate(([True], np.diff(t) > 0))
        t, mag = t[keep], mag[keep]
    return t, mag


def split_segments(t: np.ndarray, max_gap_s: float) -> list[tuple[int, int]]:
    """Index ranges [i, j) of runs without a gap > max_gap_s."""
    if t.size == 0:
        return []
    breaks = np.flatnonzero(np.diff(t) > max_gap_s) + 1
    starts = np.concatenate(([0], breaks))
    ends = np.concatenate((breaks, [t.size]))
    return list(zip(starts.tolist(), ends.tolist()))


def _bouts_from_peaks(peak_t: np.ndarray, p: PedometerParams) -> list[np.ndarray]:
    """Groups peak times into walking bouts; returns the peak times of each accepted bout."""
    if peak_t.size < p.min_bout_peaks:
        return []
    d = np.diff(peak_t)
    in_range = (d >= p.min_interval_s) & (d <= p.max_interval_s)
    bouts: list[np.ndarray] = []
    # runs of consecutive in-range intervals -> candidate peak runs
    i = 0
    n = d.size
    while i < n:
        if not in_range[i]:
            i += 1
            continue
        j = i
        while j < n and in_range[j]:
            j += 1
        run = peak_t[i: j + 1]  # peaks i..j
        bouts.extend(_regular_subruns(run, p))
        i = j
    return bouts


def interval_cvs(peak_t: np.ndarray) -> tuple[float, float]:
    """(step CV, stride CV) of a peak sequence with >= 3 peaks."""
    d = np.diff(peak_t)
    stride = d[:-1] + d[1:]
    return float(np.std(d) / np.mean(d)), float(np.std(stride) / np.mean(stride))


def is_regular_bout(peak_t: np.ndarray, p: PedometerParams) -> bool:
    step_cv, stride_cv = interval_cvs(peak_t)
    if step_cv <= p.short_bout_max_cv:
        return True
    return peak_t.size >= p.long_bout_peaks and (stride_cv <= p.long_bout_max_stride_cv or step_cv <= p.long_bout_max_cv)


def _regular_subruns(run: np.ndarray, p: PedometerParams) -> list[np.ndarray]:
    if run.size < p.min_bout_peaks:
        return []
    d = np.diff(run)
    med = float(np.median(d))
    ok = np.abs(d - med) <= p.max_local_deviation * med
    out: list[np.ndarray] = []
    i = 0
    while i < d.size:
        if not ok[i]:
            i += 1
            continue
        j = i
        while j < d.size and ok[j]:
            j += 1
        sub = run[i: j + 1]
        if sub.size >= p.min_bout_peaks and is_regular_bout(sub, p):
            out.append(sub)
        i = j
    return out


def detect_steps(t: np.ndarray, x: np.ndarray, y: np.ndarray, z: np.ndarray,
                 params: PedometerParams | None = None) -> StepDetection:
    """Runs the pedometer on raw samples (t in seconds, x/y/z in g; irregular sampling allowed)."""
    p = params or PedometerParams()
    t, mag = clean_motion(t, x, y, z)
    sos = butter(p.order, p.band, btype="bandpass", fs=p.fs, output="sos")
    min_dist = max(1, int(round(p.min_peak_distance_s * p.fs)))
    steps: list[np.ndarray] = []
    bouts: list[tuple[float, float, int]] = []
    segments: list[tuple[float, float]] = []
    peaks_total = 0
    for i, j in split_segments(t, p.max_gap_s):
        ts, ms = t[i:j], mag[i:j]
        segments.append((float(ts[0]), float(ts[-1])))
        if ts[-1] - ts[0] < p.min_segment_s:
            continue
        grid = np.arange(ts[0], ts[-1], 1.0 / p.fs)
        if grid.size < 4 * (2 * sos.shape[0] + 1):
            continue
        u = np.interp(grid, ts, ms)
        u = u - np.mean(u)
        f = sosfiltfilt(sos, u)
        pk, _ = find_peaks(f, height=p.min_peak_height_g, prominence=p.min_peak_prominence_g, distance=min_dist)
        peaks_total += int(pk.size)
        for bout in _bouts_from_peaks(grid[pk], p):
            steps.append(bout)
            bouts.append((float(bout[0]), float(bout[-1]), int(bout.size)))
    step_times = np.concatenate(steps) if steps else np.empty(0)
    return StepDetection(step_times_s=np.sort(step_times), bouts=bouts, segments=segments, peaks_total=peaks_total)


def steps_per_minute(step_times_s: np.ndarray) -> list[list[int]]:
    """Aggregates step times into 1-minute samples [startMs, endMs, count] (count > 0 only).

    Minutes are aligned to the dataset clock (multiples of 60 000 ms from its origin, i.e. PSG start for Walch).
    """
    if len(step_times_s) == 0:
        return []
    ms = np.round(np.asarray(step_times_s, dtype=float) * 1000.0).astype(np.int64)
    bins = np.floor_divide(ms, MINUTE_MS)
    uniq, counts = np.unique(bins, return_counts=True)
    return [[int(b) * MINUTE_MS, (int(b) + 1) * MINUTE_MS, int(c)] for b, c in zip(uniq, counts) if c > 0]
