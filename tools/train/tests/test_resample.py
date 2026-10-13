import numpy as np
import pytest

from pulsetrain.ohealth import (
    GRID_MS,
    clean_hr,
    coverage_proxy,
    grid_phase_ms,
    grid_points,
    resample_instantaneous,
    resample_mean2min,
)


def _hr_every(step_ms, start_ms, end_ms, fn=lambda t: 60.0 + t / 60_000.0):
    t = np.arange(start_ms, end_ms + 1, step_ms, dtype=np.int64)
    return t, np.array([fn(x) for x in t], dtype=float)


def test_grid_phase_is_deterministic_in_range_and_varies():
    phases = {s: grid_phase_ms(s) for s in ["46343", "759667", "781756", "844359", "1066528", "bid-00-1"]}
    assert phases == {s: grid_phase_ms(s) for s in phases}          # reproducible
    assert all(0 <= p < GRID_MS for p in phases.values())
    assert len(set(phases.values())) == len(phases)                  # not all aligned to PSG start


def test_grid_points_cover_window_inclusive():
    g = grid_points(-300_000, 300_000, 7_000)
    assert g[0] == -233_000 and g[-1] == 247_000
    assert np.all(np.diff(g) == GRID_MS)
    assert grid_points(0, 1_000, 5_000).size == 0


def test_instantaneous_picks_nearest_within_30s_at_grid_time():
    # samples every 5 s, value = minute index -> nearest sample is exactly at the grid point
    t, v = _hr_every(5_000, 0, 3_600_000)
    out = resample_instantaneous(t, v, 0, 3_600_000, phase_ms=12_500)
    times = [o[0] for o in out]
    assert times == list(range(12_500, 3_600_001, GRID_MS))
    # nearest raw sample of 12 500 is 10 000 or 15 000 (tie -> earlier)
    assert out[0][1] == pytest.approx(60.0 + 10_000 / 60_000.0)
    for gt, val in out[1:]:
        nearest = round((gt - 2_500) / 5_000) * 5_000  # ties resolved to the earlier sample
        assert val == pytest.approx(60.0 + nearest / 60_000.0)


def test_instantaneous_respects_tolerance_and_gaps():
    t = np.array([0, 100_000, 200_000, 245_000, 600_000], dtype=np.int64)
    v = np.array([50.0, 51.0, 52.0, 53.0, 54.0])
    out = resample_instantaneous(t, v, 0, 600_000, phase_ms=0)
    # grid 0,120000,240000,360000,480000,600000
    assert out == [[0, 50.0], [120_000, 51.0], [240_000, 53.0], [600_000, 54.0]]
    # 120 000: nearest is 100 000 (20 s) -> kept; 360 000 / 480 000: nothing within 30 s -> no value


def test_mean2min_window_means_at_centres():
    t, v = _hr_every(10_000, 0, 1_200_000, fn=lambda x: 60.0 + x // 120_000)  # constant within aligned windows
    out = resample_mean2min(t, v, 0, 1_200_000, phase_ms=0)
    centres = [o[0] for o in out]
    assert centres == list(range(60_000, 1_200_000, GRID_MS))
    assert [o[1] for o in out] == [60.0 + k for k in range(10)]


def test_mean2min_averages_all_samples_and_skips_empty_windows():
    t = np.array([5_000, 10_000, 115_000, 400_000], dtype=np.int64)
    v = np.array([60.0, 70.0, 80.0, 90.0])
    out = resample_mean2min(t, v, 0, 600_000, phase_ms=0)
    assert out == [[60_000, 70.0], [420_000, 90.0]]


def test_mean2min_with_phase_and_negative_times():
    t, v = _hr_every(5_000, -600_000, 600_000, fn=lambda x: 70.0)
    out = resample_mean2min(t, v, -600_000, 600_000, phase_ms=31_000)
    assert all(o[1] == 70.0 for o in out)
    assert all((o[0] - 60_000 - 31_000) % GRID_MS == 0 for o in out)
    assert out[0][0] >= -600_000 and out[-1][0] <= 600_000


def test_variants_differ_on_noisy_series():
    rng = np.random.default_rng(0)
    t = np.sort(rng.integers(0, 3_600_000, 700)).astype(np.int64)
    v = 60 + rng.normal(0, 5, t.size)
    inst = resample_instantaneous(t, v, 0, 3_600_000, phase_ms=4_321)
    mean = resample_mean2min(t, v, 0, 3_600_000, phase_ms=4_321)
    assert 25 <= len(inst) <= 31 and 25 <= len(mean) <= 30
    # averaging reduces variance compared to single samples
    assert np.std([m[1] for m in mean]) < np.std([i[1] for i in inst])


def test_clean_hr_sorts_dedups_and_drops_invalid():
    t, v = clean_hr(np.array([30, 10, 10, 20, 40]), np.array([60.0, 50.0, 55.0, np.nan, 0.0]))
    assert t.tolist() == [10, 30] and v.tolist() == [50.0, 60.0]


def test_coverage_proxy():
    hr = [[0, 60.0], [1_200_000, 60.0]]                    # samples at 0 and 20 min
    # 60 one-minute epochs over 1 h; epoch mid-points within 10 min of 0 or 20 min -> minutes 0..29 (30 epochs)
    assert coverage_proxy(hr, 0, 3_600_000) == pytest.approx(30 / 60)
    assert coverage_proxy([], 0, 60_000) == 0.0
