import numpy as np
import pytest

from pulsetrain.pedometer import PedometerParams, detect_steps, is_regular_bout, split_segments, steps_per_minute


def _irregular_times(duration_s, fs=50.0, jitter=0.25, t0=0.0, seed=0):
    rng = np.random.default_rng(seed)
    dt = (1.0 / fs) * (1.0 + rng.uniform(-jitter, jitter, size=int(duration_s * fs * 1.1) + 10))
    t = t0 + np.concatenate(([0.0], np.cumsum(dt)))
    return t[t <= t0 + duration_s]


def _walk(t, freq=1.8, amp=0.3, noise=0.01, seed=1, tilt=(0.2, 0.3)):
    """Gravity on a tilted axis + vertical step impacts at `freq` (with a harmonic) along gravity + sensor noise."""
    rng = np.random.default_rng(seed)
    g = np.array([tilt[0], tilt[1], -1.0])
    g = g / np.linalg.norm(g)
    phase = 2 * np.pi * freq * t
    vertical = amp * np.sin(phase) + 0.3 * amp * np.sin(2 * phase + 0.7)
    a = (1.0 + vertical)[:, None] * g[None, :]
    a += rng.normal(0, noise, size=a.shape)
    return a[:, 0], a[:, 1], a[:, 2]


def _still(t, noise=0.005, seed=2):
    rng = np.random.default_rng(seed)
    g = np.array([0.1, -0.2, -0.97])
    a = np.tile(g / np.linalg.norm(g), (t.size, 1)) + rng.normal(0, noise, size=(t.size, 3))
    return a[:, 0], a[:, 1], a[:, 2]


@pytest.mark.parametrize("freq", [1.5, 1.8, 2.2])
def test_pure_walking_counts_steps(freq):
    t = _irregular_times(120.0)
    x, y, z = _walk(t, freq=freq)
    det = detect_steps(t, x, y, z)
    expected = freq * 120.0
    assert abs(det.step_times_s.size - expected) <= 0.05 * expected, (det.step_times_s.size, expected)
    assert len(det.bouts) == 1


def test_walking_at_low_sampling_rate():
    t = _irregular_times(90.0, fs=10.0, jitter=0.05)
    x, y, z = _walk(t, freq=1.8)
    det = detect_steps(t, x, y, z)
    assert abs(det.step_times_s.size - 162) <= 0.05 * 162


def test_still_gives_zero():
    t = _irregular_times(600.0)
    det = detect_steps(t, *_still(t))
    assert det.step_times_s.size == 0


def test_white_noise_gives_zero():
    t = _irregular_times(600.0)
    rng = np.random.default_rng(3)
    x, y, z = _still(t)
    z = z + rng.normal(0, 0.05, size=t.size)
    x = x + rng.normal(0, 0.05, size=t.size)
    assert detect_steps(t, x, y, z).step_times_s.size == 0


def test_rolling_over_gives_zero():
    """Several turns in bed: the gravity vector rotates over ~2 s with a jerky body-acceleration transient."""
    t = _irregular_times(300.0)
    rng = np.random.default_rng(4)
    angle = np.zeros_like(t)
    extra = np.zeros_like(t)
    for start in (40.0, 120.0, 200.0, 260.0):
        ramp = np.clip((t - start) / 2.0, 0.0, 1.0)
        angle += (np.pi / 2) * (0.5 - 0.5 * np.cos(np.pi * ramp))   # smooth 90° turn over 2 s
        burst = (t >= start) & (t < start + 3.0)
        tt = t[burst] - start
        extra[burst] += 0.4 * np.sin(2 * np.pi * 1.2 * tt) * np.exp(-tt)  # jerk of the body during the turn
    x = np.sin(angle) + rng.normal(0, 0.01, t.size)
    y = rng.normal(0, 0.01, t.size)
    z = -np.cos(angle) + extra + rng.normal(0, 0.01, t.size)
    det = detect_steps(t, x, y, z)
    assert det.step_times_s.size == 0


@pytest.mark.parametrize("seed", range(10))
def test_isolated_arm_movements_give_zero(seed):
    """Sleep-like fidgeting: isolated movements of 1-5 cycles (0.7-2.8 Hz) separated by >= 1 s of rest."""
    t = _irregular_times(600.0)
    x, y, z = _still(t)
    rng = np.random.default_rng(seed)
    start = rng.uniform(0, 10)
    while start < 590:
        f = rng.uniform(0.7, 2.8)
        dur = rng.integers(1, 6) / f
        m = (t >= start) & (t < start + dur)
        z[m] += rng.uniform(0.1, 0.5) * np.sin(2 * np.pi * f * (t[m] - start))
        start += dur + rng.uniform(1.0, 20.0)
    assert detect_steps(t, x, y, z).step_times_s.size == 0


def test_irregular_movement_sequences_rarely_counted():
    """Sequences of 15 jerky movements at random intervals (uniform 0.3-1.5 s): every interval is in the walking range,
    but the timing is random. A random sequence occasionally looks rhythmic over 8 peaks by chance, so this is a
    statistical bound: over 50 sequences, < 5 % of the movements may be counted as steps."""
    counted = total = 0
    for seed in range(50):
        t = _irregular_times(60.0, seed=seed)
        x, y, z = _still(t, seed=seed)
        rng = np.random.default_rng(100 + seed)
        tc = 20.0
        for _ in range(15):
            z -= rng.uniform(0.2, 0.4) * np.exp(-0.5 * ((t - tc) / 0.06) ** 2)
            tc += rng.uniform(0.3, 1.5)
        counted += detect_steps(t, x, y, z).step_times_s.size
        total += 15
    assert counted / total < 0.05, (counted, total)


def test_asymmetric_sustained_gait_counted():
    """Left/right asymmetric wrist gait (alternating 0.40 / 0.70 s step intervals): step CV 0.27 fails the strict
    short-bout test, but a sustained bout passes on stride regularity."""
    t = _irregular_times(70.0)
    x, y, z = _still(t)
    rng = np.random.default_rng(7)
    times = [5.0]
    while times[-1] < 64.0:
        times.append(times[-1] + (0.40 if len(times) % 2 else 0.70) * (1 + rng.normal(0, 0.03)))
    for tc in times:
        z -= 0.3 * (1 + rng.uniform(-0.2, 0.2)) * np.exp(-0.5 * ((t - tc) / 0.06) ** 2)
    det = detect_steps(t, x, y, z)
    assert abs(det.step_times_s.size - len(times)) <= 0.05 * len(times)


def test_regularity_rule():
    p = PedometerParams()
    regular = np.cumsum(np.full(10, 0.55))
    assert is_regular_bout(regular, p)
    alternating_short = np.cumsum(np.tile([0.40, 0.70], 5))       # 10 peaks, step CV 0.27
    assert not is_regular_bout(alternating_short, p)
    alternating_long = np.cumsum(np.tile([0.40, 0.70], 12))       # 24 peaks: stride CV 0
    assert is_regular_bout(alternating_long, p)
    random_long = np.cumsum(np.random.default_rng(0).uniform(0.3, 1.5, 30))
    assert not is_regular_bout(random_long, p)


def test_short_walk_below_bout_length_not_counted():
    t = _irregular_times(60.0)
    x, y, z = _still(t)
    m = (t >= 20.0) & (t < 23.0)  # ~5 steps at 1.8 Hz
    z[m] -= 0.3 * np.sin(2 * np.pi * 1.8 * (t[m] - 20.0))
    assert detect_steps(t, x, y, z).step_times_s.size == 0


def test_gap_is_not_interpolated_and_both_walks_counted():
    t1 = _irregular_times(60.0, t0=0.0, seed=10)
    t2 = _irregular_times(60.0, t0=70.0, seed=11)   # 10-s recording gap between the two walks
    t = np.concatenate((t1, t2))
    x, y, z = _walk(t, freq=1.8)
    det = detect_steps(t, x, y, z)
    assert len(det.segments) == 2
    assert abs(det.step_times_s.size - 216) <= 0.06 * 216
    assert not np.any((det.step_times_s > t1[-1]) & (det.step_times_s < t2[0]))


def test_split_segments():
    t = np.array([0.0, 0.5, 1.0, 2.5, 3.0, 10.0])
    assert split_segments(t, 1.0) == [(0, 3), (3, 5), (5, 6)]


def test_steps_per_minute_aggregation_and_negative_times():
    steps = np.array([-61.0, -1.0, -0.5, 0.0, 30.0, 59.999, 60.0, 185.0])
    assert steps_per_minute(steps) == [
        [-120000, -60000, 1],
        [-60000, 0, 2],
        [0, 60000, 3],
        [60000, 120000, 1],
        [180000, 240000, 1],
    ]
    assert steps_per_minute(np.empty(0)) == []


def test_walk_then_rest_minutes():
    t = _irregular_times(300.0)
    x, y, z = _still(t)
    wx, wy, wz = _walk(t, freq=1.8)
    m = (t >= 60.0) & (t < 120.0)
    x[m], y[m], z[m] = wx[m], wy[m], wz[m]
    det = detect_steps(t, x, y, z, PedometerParams())
    minutes = steps_per_minute(det.step_times_s)
    assert [mm[0] for mm in minutes] == [60000]
    assert abs(minutes[0][2] - 108) <= 6
