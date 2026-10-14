"""Tiny synthetic night files (INTERCHANGE.md section 1) for the pipeline tests. Toy data, not physiology."""

from __future__ import annotations

import csv
import json
from pathlib import Path

import numpy as np

STAGE_HR = {"AWAKE": 74.0, "LIGHT": 60.0, "DEEP": 53.0, "REM": 66.0}
VARIANTS = ("instantaneous", "mean2min")


def hypnogram(rng: np.random.Generator, minutes: int, long_wakes: bool = False) -> list[str]:
    """Per-minute stages: wake, then cycles LIGHT-DEEP-LIGHT-REM with short awakenings (1-3 min; 1-8 min with
    ``long_wakes``, so that some awakenings last 5 min or more)."""
    seq = ["AWAKE"] * int(rng.integers(6, 12))
    cycle = 0
    while len(seq) < minutes:
        deep = int(rng.integers(12, 25)) if cycle < 2 else int(rng.integers(0, 6))
        rem = int(rng.integers(6, 12)) + 5 * cycle
        seq += ["LIGHT"] * int(rng.integers(10, 20)) + ["DEEP"] * deep + ["LIGHT"] * int(rng.integers(5, 12))
        seq += ["REM"] * rem + ["AWAKE"] * int(rng.integers(1, 9 if long_wakes else 4))
        cycle += 1
    return seq[:minutes]


def make_night(night_id: str, subject: str, variant: str, rng: np.random.Generator, *, minutes: int = 200,
               start_ms: int = 0, with_steps: bool = True, offset: float = 0.0, coverage: float = 1.0,
               long_wakes: bool = False, stages_seed: int | None = None) -> dict:
    """One night; ``stages_seed`` draws the hypnogram from its own generator (so that two variants of a night can
    share their labels, as the prepared OHealth-like variants do), otherwise from ``rng``."""
    stages = hypnogram(rng if stages_seed is None else np.random.default_rng(stages_seed), minutes, long_wakes)
    s0, s1 = start_ms, start_ms + minutes * 60_000
    labels = []
    for k, st in enumerate(stages):
        for h in (0, 30_000):
            labels.append([s0 + k * 60_000 + h, s0 + k * 60_000 + h + 30_000, st])
    hr = []
    t = s0 - 30 * 60_000 + int(rng.integers(0, 120_000))
    last = s0 + int(coverage * (s1 - s0))
    while t < s1 + 30 * 60_000:
        if t <= last:
            k = min(max(int((t - s0) // 60_000), 0), minutes - 1)
            st = stages[k] if s0 <= t < s1 else "AWAKE"
            noise = rng.normal(0, 2.0 if variant == "instantaneous" else 1.0)
            hr.append([int(t), float(round(STAGE_HR[st] + offset + noise, 1))])
        t += 120_000
    steps = []
    if with_steps:
        for k, st in enumerate(stages):
            if st == "AWAKE" and rng.random() < 0.5:
                steps.append([s0 + k * 60_000, s0 + (k + 1) * 60_000, int(rng.integers(5, 30))])
    return {"id": night_id, "subject": subject, "variant": variant, "sessionStartMs": s0, "sessionEndMs": s1,
            "hr": hr, "steps": steps, "labels": labels}


def write_dataset(root: Path, n_walch: int = 6, n_bid: int = 3, bid_nights: int = 2, seed: int = 1,
                  low_coverage_night: bool = True, long_wakes: bool = False, shared_labels: bool = False) -> Path:
    """Writes <root>/<variant>/*.night.json for two variants, summary.csv and manifest.json; returns root."""
    rng = np.random.default_rng(seed)
    rows = []
    specs = []
    for i in range(n_walch):
        specs.append(("walch", str(1000 + i), f"walch-{1000 + i}", str(1000 + i), True, 0))
    for i in range(n_bid):
        for k in range(1, bid_nights + 1):
            subj = f"bid-{i:02d}"
            specs.append(("bidsleep", subj, f"{subj}-{k}", f"{subj}-{k}", False, 1_600_000_000_000 + k * 86_400_000))
    for v in VARIANTS:
        (root / v).mkdir(parents=True, exist_ok=True)
    for j, (ds, subj, key, stem, steps, start) in enumerate(specs):
        offset = float(rng.normal(0, 3))
        cov = 0.3 if (low_coverage_night and ds == "bidsleep" and j == len(specs) - 1) else 1.0
        night_seed = int(rng.integers(0, 2**31))
        for v in VARIANTS:
            nid = f"{key}-{'inst' if v == 'instantaneous' else 'mean2min'}"
            n = make_night(nid, subj, v, np.random.default_rng(night_seed + (0 if v == "instantaneous" else 7)),
                           start_ms=start, with_steps=steps, offset=offset, coverage=cov, long_wakes=long_wakes,
                           stages_seed=night_seed if shared_labels else None)
            (root / v / f"{stem}.night.json").write_text(json.dumps(n))
            rows.append({"dataset": ds, "subject": subj, "night_id": nid, "variant": v,
                         "steps_file": str(sum(c for _, _, c in n["steps"])) if steps else ""})
    with open(root / "summary.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["dataset", "subject", "night_id", "variant", "steps_file"])
        w.writeheader()
        w.writerows(rows)
    (root / "manifest.json").write_text(json.dumps({"datasets": {
        "walch": {"name": "Synthetic Walch-like", "version": "0", "license": "test-only"},
        "bidsleep": {"name": "Synthetic BIDSleep-like", "version": "0", "license": "test-only"}}}))
    return root


def write_psg_twins(root: Path, margin_min: int = 20, seed: int = 5) -> Path:
    """For every night of <root>/<variant>, writes <root>/<variant>-psg/<same file>: the "full scored range" twin
    with ``margin_min`` of extra AWAKE labels before and after the session and extra HR samples only outside the
    session's ±30-min context, so that the OHealth-like file is exactly the twin cut to its window (prepare.py)."""
    rng = np.random.default_rng(seed)
    m = margin_min * 60_000
    for v in VARIANTS:
        (root / f"{v}-psg").mkdir(parents=True, exist_ok=True)
        for f in sorted((root / v).glob("*.night.json")):
            n = json.loads(f.read_text())
            s0, s1 = n["sessionStartMs"], n["sessionEndMs"]
            pre = [[t, t + 30_000, "AWAKE"] for t in range(s0 - m, s0, 30_000)]
            post = [[t, t + 30_000, "AWAKE"] for t in range(s1, s1 + m, 30_000)]
            c0, c1 = s0 - 30 * 60_000, s1 + 30 * 60_000
            extra = [[int(t), float(round(STAGE_HR["AWAKE"] + rng.normal(0, 2.0), 1))]
                     for t in list(range(c0 - m, c0, 120_000)) + list(range(c1 + 60_000, c1 + m, 120_000))]
            twin = dict(n, id=n["id"] + "-psg", sessionStartMs=s0 - m, sessionEndMs=s1 + m,
                        labels=pre + n["labels"] + post, hr=sorted(n["hr"] + extra),
                        steps=n["steps"] + ([[s1 + 60_000, s1 + 120_000, 12]] if n["steps"] else []))
            (root / f"{v}-psg" / f.name).write_text(json.dumps(twin))
    return root
