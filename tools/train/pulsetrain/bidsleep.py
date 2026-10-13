"""Readers for PhysioNet BIDSleep v1.0.1 ("A Multi-Night Instantaneous Heart Rate and Accelerometry Dataset with EEG
Sleep Stage Labels").

Layout: Bidslab<NN>/<night>/{hr.csv, motion.csv, labels.mat} (dataset README):
    hr.csv       no header; "unix_seconds,bpm" (Apple Watch instantaneous HR, ~0.2 Hz)
    motion.csv   header "Timestamp,x,y,z"; unix seconds, g
    labels.mat   MATLAB 5 file: recStart (string, U.S. Eastern Time, "YYYY-mm-dd HH:MM:SS"),
                 dreem_label / expert_label (uint8, one per 30-s epoch from recStart;
                 0 Wake, 1 N1, 2 N2, 3 N3, 4 REM, 5 Unknown)

Times are kept as Unix epoch milliseconds. The files are untrusted input: CSVs are parsed only as numbers and the
.mat file with scipy.io.loadmat (a data parser; nothing is executed).
"""

from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass
from pathlib import Path
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd
import scipy.io

SUBJECT_DIR_RE = re.compile(r"^Bidslab(\d{1,4})$")
NIGHT_DIR_RE = re.compile(r"^(\d{1,3})$")
REC_START_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")
EASTERN = ZoneInfo("America/New_York")


@dataclass
class BidNight:
    subject_dir: str          # "Bidslab00"
    subject: str              # LOSO key, "bid-00"
    night: str                # "1"
    rec_start_ms: int | None
    expert_label: np.ndarray  # codes, one per 30-s epoch
    hr_t_s: np.ndarray
    hr_bpm: np.ndarray
    notes: list[str]


def list_nights(root: Path) -> list[tuple[str, str]]:
    out = []
    if not root.is_dir():
        return out
    for sdir in sorted(root.iterdir()):
        m = SUBJECT_DIR_RE.match(sdir.name)
        if not (m and sdir.is_dir()):
            continue
        for ndir in sdir.iterdir():
            if NIGHT_DIR_RE.match(ndir.name) and ndir.is_dir():
                out.append((sdir.name, ndir.name))
    return sorted(out, key=lambda sn: (int(SUBJECT_DIR_RE.match(sn[0]).group(1)), int(sn[1])))


def subject_key(subject_dir: str) -> str:
    return "bid-" + SUBJECT_DIR_RE.match(subject_dir).group(1)


def parse_rec_start(text: str, first_sample_s: float | None) -> tuple[int, str | None]:
    """Eastern-Time recStart -> Unix ms. In the repeated hour of the autumn DST change the wall time is ambiguous;
    the offset closest to the first HR sample is chosen (and reported)."""
    if not REC_START_RE.match(text):
        raise ValueError(f"unexpected recStart {text!r}")
    naive = dt.datetime.strptime(text, "%Y-%m-%d %H:%M:%S")
    cands = sorted({int(round(naive.replace(tzinfo=EASTERN, fold=f).timestamp() * 1000)) for f in (0, 1)})
    if len(cands) == 1:
        return cands[0], None
    if first_sample_s is None:
        return cands[0], "recStart in the DST-ambiguous hour; no HR to disambiguate, used the earlier offset"
    best = min(cands, key=lambda c: abs(c - first_sample_s * 1000))
    return best, "recStart in the DST-ambiguous hour; offset chosen by the first HR sample"


def _numeric(path: Path, ncols: int, header: bool) -> np.ndarray:
    if not path.is_file():
        return np.empty((0, ncols))
    df = pd.read_csv(path, sep=",", header=0 if header else None, engine="c", usecols=range(ncols), dtype=str,
                     skip_blank_lines=True, on_bad_lines="skip")
    arr = df.apply(pd.to_numeric, errors="coerce").to_numpy(dtype=float)
    return arr[np.all(np.isfinite(arr), axis=1)]


def load_motion(root: Path, subject_dir: str, night: str) -> np.ndarray | None:
    path = root / subject_dir / night / "motion.csv"
    if not path.is_file():
        return None
    try:
        df = pd.read_csv(path, sep=",", header=0, engine="c", usecols=range(4), dtype=np.float64,
                         skip_blank_lines=True, on_bad_lines="skip")
        arr = df.to_numpy(dtype=float)
    except ValueError:
        arr = _numeric(path, 4, header=True)
    return arr[np.all(np.isfinite(arr), axis=1)]


def load_night(root: Path, subject_dir: str, night: str) -> BidNight:
    ndir = root / subject_dir / night
    notes: list[str] = []
    hr = _numeric(ndir / "hr.csv", 2, header=False)
    labels = np.empty(0)
    rec_start_ms = None
    mat_path = ndir / "labels.mat"
    if mat_path.is_file():
        try:
            mat = scipy.io.loadmat(mat_path, squeeze_me=True)
            raw = mat.get("expert_label")
            if raw is None:
                notes.append("labels.mat has no expert_label")
            else:
                labels = np.atleast_1d(np.asarray(raw, dtype=float)).ravel()
            rs = mat.get("recStart")
            if rs is None:
                notes.append("labels.mat has no recStart")
            else:
                first = float(np.min(hr[:, 0])) if hr.size else None
                rec_start_ms, note = parse_rec_start(str(np.atleast_1d(rs).ravel()[0]).strip(), first)
                if note:
                    notes.append(note)
        except Exception as exc:  # noqa: BLE001 - a broken .mat only disqualifies this night
            notes.append(f"labels.mat unreadable ({type(exc).__name__})")
    else:
        notes.append("labels.mat missing")
    return BidNight(subject_dir=subject_dir, subject=subject_key(subject_dir), night=night,
                    rec_start_ms=rec_start_ms, expert_label=labels,
                    hr_t_s=hr[:, 0], hr_bpm=hr[:, 1], notes=notes)
