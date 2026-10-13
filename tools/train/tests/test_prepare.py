import json

import numpy as np

import prepare
from pulsetrain import ohealth
from pulsetrain.pedometer import PedometerParams

CONTRACT_KEYS = ["id", "subject", "variant", "sessionStartMs", "sessionEndMs", "hr", "steps", "labels"]


def _recording(with_motion=True):
    # PSG: 20 min wake, 6 h N2/N3/REM, 20 min wake (seconds since PSG start, Walch style)
    codes = [0] * 40 + ([2] * 240 + [3] * 240 + [5] * 240) + [0] * 40
    t_lab = 30.0 * np.arange(len(codes))
    epochs = ohealth.label_epochs(t_lab, np.array(codes, float))
    t_hr = np.arange(-3600.0, t_lab[-1] + 3600.0, 5.0)           # HR every 5 s from 1 h before PSG start
    bpm = 60 + 5 * np.sin(t_hr / 900.0)
    motion = None
    if with_motion:
        # still wrist, plus a 60-s walk (1.8 Hz) 10 min after PSG start (inside the first wake period)
        t = np.arange(-600.0, 3000.0, 0.02)
        z = -np.ones_like(t)
        walk = (t >= 600.0) & (t < 660.0)
        z[walk] -= 0.3 * np.sin(2 * np.pi * 1.8 * t[walk])
        motion = np.column_stack([t, np.zeros_like(t), np.zeros_like(t), z])
    return prepare.Recording(
        dataset="walch", subject="123", night="1", night_key="walch-123", file_stem="123", phase_key="123",
        epochs=epochs, hr_t_ms=np.round(t_hr * 1000).astype(np.int64), hr_bpm=bpm, motion=motion,
        apple_steps=(np.array([0.0]), np.array([10.0])),
    )


def test_process_writes_all_variants_following_the_contract(tmp_path):
    for v in prepare.VARIANTS:
        (tmp_path / v).mkdir()
    res = prepare.process(_recording(), tmp_path, PedometerParams())
    assert len(res.written) == 4 and len(res.rows) == 4
    nights = {v: json.loads((tmp_path / v / "123.night.json").read_text()) for v in prepare.VARIANTS}
    assert {v: n["id"] for v, n in nights.items()} == {
        "instantaneous": "walch-123-inst", "mean2min": "walch-123-mean2min",
        "instantaneous-psg": "walch-123-inst-psg", "mean2min-psg": "walch-123-mean2min-psg"}
    sleep_start, sleep_end = 40 * 30_000, (40 + 720) * 30_000
    for v, n in nights.items():
        assert list(n.keys()) == CONTRACT_KEYS
        assert n["subject"] == "123" and n["variant"] == v
        s0, s1 = n["sessionStartMs"], n["sessionEndMs"]
        if v.endswith("-psg"):
            assert (s0, s1) == (0, 800 * 30_000)
        else:
            assert (s0, s1) == (sleep_start - 300_000, sleep_end + 300_000)
        hr_t = [h[0] for h in n["hr"]]
        assert hr_t == sorted(hr_t)
        assert min(hr_t) >= s0 - 30 * 60_000 and max(hr_t) <= s1 + 30 * 60_000
        assert min(hr_t) < s0 and max(hr_t) > s1            # context margin included where data exists
        assert all(isinstance(t, int) for t in hr_t)
        assert all(a >= s0 and b <= s1 and b - a == 30_000 and st in ohealth.STAGES for a, b, st in n["labels"])
        assert all(b - a == 60_000 and c > 0 for a, b, c in n["steps"])
        # the walk at 600-660 s is in the context of every variant
        assert 95 <= sum(c for _, _, c in n["steps"]) <= 115
    row = res.rows[0]
    assert row["coverage_proxy"] == 1.0 and row["deep_min"] == 120.0 and row["rem_min"] == 120.0


def test_process_without_motion_notes_it(tmp_path):
    for v in prepare.VARIANTS:
        (tmp_path / v).mkdir()
    res = prepare.process(_recording(with_motion=False), tmp_path, PedometerParams())
    assert all("motion" in r["notes"] for r in res.rows)
    n = json.loads((tmp_path / "mean2min" / "123.night.json").read_text())
    assert n["steps"] == []


def test_process_skips_night_without_labels(tmp_path):
    rec = _recording()
    rec.epochs = ohealth.label_epochs(np.array([0.0, 30.0]), np.array([-1.0, -1.0]))
    res = prepare.process(rec, tmp_path, PedometerParams())
    assert res.written == [] and res.rows[0]["notes"].startswith("SKIPPED: no usable labels")
