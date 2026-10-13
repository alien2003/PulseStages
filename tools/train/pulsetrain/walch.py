"""Readers for the PhysioNet sleep-accel (Walch et al. 2019) text files.

Formats (dataset page, "Data Description"; times are seconds since PSG start, negative = before it):
    heart_rate/<id>_heartrate.txt      "t,bpm"
    motion/<id>_acceleration.txt       "t x y z"        (g)
    steps/<id>_steps.txt               "t,steps"        (steps in [t, next t); 10-min bins in practice)
    labels/<id>_labeled_sleep.txt      "t stage"        (30-s epochs; 0 wake, 1 N1, 2 N2, 3 N3, 4 N4, 5 REM, -1 unscored)

The files are untrusted input: they are only parsed as numbers (pandas C parser); malformed rows become NaN and are
dropped by the consumers.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

SUBJECT_RE = re.compile(r"^(\d{1,12})_labeled_sleep\.txt$")


@dataclass
class WalchSubject:
    subject: str
    label_t_s: np.ndarray
    label_code: np.ndarray
    hr_t_s: np.ndarray
    hr_bpm: np.ndarray
    steps_t_s: np.ndarray
    steps_count: np.ndarray
    motion: np.ndarray | None  # (n, 4): t, x, y, z ; None if the file is missing


def list_subjects(root: Path) -> list[str]:
    out = []
    for p in sorted((root / "labels").iterdir()):
        m = SUBJECT_RE.match(p.name)
        if m and p.is_file():
            out.append(m.group(1))
    return sorted(out, key=int)


def _read_numeric(path: Path, sep: str, ncols: int) -> np.ndarray:
    if not path.is_file():
        return np.empty((0, ncols))
    df = pd.read_csv(path, sep=sep, header=None, engine="c", usecols=range(ncols), dtype=str,
                     skip_blank_lines=True, on_bad_lines="skip")
    arr = df.apply(pd.to_numeric, errors="coerce").to_numpy(dtype=float)
    return arr[np.all(np.isfinite(arr), axis=1)]


def _read_motion(path: Path) -> np.ndarray | None:
    if not path.is_file():
        return None
    try:
        df = pd.read_csv(path, sep=" ", header=None, engine="c", usecols=range(4), dtype=np.float64,
                         skip_blank_lines=True, on_bad_lines="skip")
        arr = df.to_numpy(dtype=float)
    except ValueError:  # a non-numeric token somewhere: slow path, coerce to NaN and drop
        return _read_numeric(path, " ", 4)
    return arr[np.all(np.isfinite(arr), axis=1)]


def load_subject(root: Path, subject: str, with_motion: bool = True) -> WalchSubject:
    lab = _read_numeric(root / "labels" / f"{subject}_labeled_sleep.txt", " ", 2)
    hr = _read_numeric(root / "heart_rate" / f"{subject}_heartrate.txt", ",", 2)
    st = _read_numeric(root / "steps" / f"{subject}_steps.txt", ",", 2)
    mo = _read_motion(root / "motion" / f"{subject}_acceleration.txt") if with_motion else None
    return WalchSubject(
        subject=subject,
        label_t_s=lab[:, 0], label_code=lab[:, 1],
        hr_t_s=hr[:, 0], hr_bpm=hr[:, 1],
        steps_t_s=st[:, 0], steps_count=st[:, 1],
        motion=mo,
    )


def apple_steps_in_window(t_s: np.ndarray, count: np.ndarray, start_ms: int, end_ms: int,
                          max_bin_s: float = 600.0) -> tuple[float, int]:
    """Apple Watch step bins prorated into [start, end]. A bin runs from its timestamp to the next one, capped at
    max_bin_s (the dataset's 10-min bins; longer distances are recording gaps). Returns (steps, bins touched)."""
    if t_s.size == 0:
        return 0.0, 0
    order = np.argsort(t_s, kind="stable")
    t, c = t_s[order] * 1000.0, count[order]
    nxt = np.concatenate((t[1:], [t[-1] + max_bin_s * 1000.0]))
    end = np.minimum(nxt, t + max_bin_s * 1000.0)
    length = np.maximum(end - t, 1.0)
    overlap = np.clip(np.minimum(end, end_ms) - np.maximum(t, start_ms), 0.0, None)
    frac = overlap / length
    return float(np.sum(c * frac)), int(np.sum(frac > 0))
