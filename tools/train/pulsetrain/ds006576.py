"""OpenNeuro ds006576 (McDevitt, Kim, Turk-Browne & Norman 2026), the external test set of the pre-registration
``naps-realistic`` (docs/prereg/prereg-naps-realistic.md, section 13): real afternoon naps with ECG and EEG, weak
labels only, **test only** (never used for training, any parameter, prior, band, guard or threshold, or for choosing
between candidates).

Pipeline (no PulseStages model is involved in any of it; section 13.2-13.6):

1. ``external.py fetch`` (this environment): the snapshot pin (``snapshot_pin``) on the public metadata, before any
   ``.eeg`` byte is read; then every ``.eeg`` object streamed in HTTP range requests of 44 851 200 bytes
   (``range_plan``, every response checked by ``check_range_response``), its SHA-256 computed on the fly
   (``stream_recording``), the frames reduced to the derivations of section 13.2 (``Reducer``, ``derive``) and
   stored as ``derived/sub-<id>.npz`` (float32, no pickle); raw bytes are never stored.
2. ``external_stage.py`` (its own environment ``tools/train/.venv-external``): YASA 0.7.0 on the 128-Hz derivations,
   central derivation by ``central_choice`` -> ``stages/sub-<id>.json``.
3. ``external.py nights`` (this environment): heart rate from the ECG by the frozen detector of section 13.3
   (``rpeaks``, ``good_chunk``, ``raw_hr_samples``, ``hr_variants``), the windows of section 13.5
   (``windows_from_stages``), night files in the INTERCHANGE format (``night_object``), ``manifest.json`` and
   ``yasa-summary.json``; only their two digests are printed and logged (``NOTE`` lines, section 13.6).

``freeze_order`` / ``freeze_check`` are the test-set freeze checks of gate G9 (section 13.6), called by the external
step of ``napsrealistic`` and, on the committed log, by ``tests/test_prereg.py``.

Everything downloaded is untrusted input: it is parsed as data only (XML listing, JSON / TSV sidecars, BrainVision
header text, little-endian float32 frames), never executed; run the scripts with ``python -I``.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from typing import Callable, Iterable, Sequence

import numpy as np
from scipy import signal
from scipy.integrate import trapezoid

from pulsetrain import ohealth
from pulsetrain.util import write_json

TRAIN_DIR = Path(__file__).resolve().parents[1]
REPO_DIR = TRAIN_DIR.parents[1]
DATA_DIR = TRAIN_DIR / "data" / "external" / "ds006576"
KEYS_PATH = TRAIN_DIR / "external-ds006576-v1.0.5.keys.tsv"
LOCK_PATH = TRAIN_DIR / "requirements-external.lock"
COMMANDS_LOG = TRAIN_DIR / "out" / "commands.log"

# The pre-registration this module implements (committed on its own as 52517a7; napsrealistic.PREREG_SHA256 is the
# canonical record, tests/test_ds006576.py checks that the two agree with the committed file).
PREREG_NAME = "prereg-naps-realistic.md"
PREREG_SHA256 = "38c1589e3fee463e7495e4c2a952e1e212ce5eba9fb24bf25d1e7d3212a7c43a"  # the text with Amendment 1

# ------------------------------------------------------------------------------------- snapshot (section 13.2) ----

BASE_URL = "https://s3.amazonaws.com/openneuro.org"
DATASET = "ds006576"
DATASET_DOI = "doi:10.18112/openneuro.ds006576.v1.0.5"
CHANGES_FIRST_ENTRY = ("1.0.5", "2026-04-16")
GROUP_COUNTS = {"NREM": 21, "REM": 21, "WAKE": 23}
N_EEG_OBJECTS = 65
TOP_LEVEL_FILES = ("dataset_description.json", "CHANGES", "participants.tsv", "participants.json")
SUBJECT_RE = re.compile(r"^sub-\d+$")
EEG_KEY_RE = re.compile(rf"^{DATASET}/(sub-\d+)/eeg/(sub-\d+)_task-rest_eeg\.eeg$")
USER_AGENT = "PulseStages-train-external/1.0 (+https://openneuro.org/datasets/ds006576)"


def sidecar_key(subject: str, suffix: str) -> str:
    """S3 key of a subject's BIDS file, ``suffix`` one of ``eeg.eeg``, ``eeg.vhdr``, ``eeg.json``, ``channels.tsv``."""
    return f"{DATASET}/{subject}/eeg/{subject}_task-rest_{suffix}"


# ------------------------------------------------------------------------------------------- BrainVision layout ----

N_CHANNELS = 73
FRAME_BYTES = N_CHANNELS * 4                     # IEEE float32, MULTIPLEXED: 292 bytes per sample frame
FS_RAW = 512
RANGE_FRAMES = 153_600                           # 300 s at 512 Hz
RANGE_BYTES = RANGE_FRAMES * FRAME_BYTES         # 44 851 200 bytes per range request
assert RANGE_BYTES == 44_851_200
DECIMATION = 4
FS_EEG = FS_RAW // DECIMATION                    # 128 Hz
CHANNEL_LABELS = ("A13_C3", "B18_C4", "A1", "A2", "LOC", "EMG1", "EMG2", "ECG1", "ECG2")
# derivation -> (positive label, negative label); ECG stays at 512 Hz, the others are decimated to 128 Hz
DERIVATIONS = {
    "ecg": ("ECG1", "ECG2"),
    "c3": ("A13_C3", "A2"),
    "c4": ("B18_C4", "A1"),
    "eog": ("LOC", "A2"),
    "emg": ("EMG1", "EMG2"),
}
DERIVATION_NAMES = {"c3": "C3-A2", "c4": "C4-A1", "eog": "LOC-A2", "emg": "EMG1-EMG2"}
UNIT_TO_UV = {"": 1.0, "µV": 1.0, "μV": 1.0, "uV": 1.0, "mV": 1e3, "V": 1e6, "nV": 1e-3}

# ----------------------------------------------------------------------- ECG -> heart rate (section 13.3, frozen) ----
# Set on the pilot's five subjects (section 2.4) and frozen; the functions below are the authoritative
# implementation of steps 1-2 (ported unchanged from the pilot's extract.py / sessions.py).

CHUNK_S = 15
BAND_HZ = (5.0, 25.0)
BUTTER_ORDER = 3
POLARITY_PCT = (99.7, 0.3)
MIN_PEAK_DISTANCE_S = 0.3
TOP_FRACTION = 0.30
TOP_PERCENTILE = 90
KEEP_FRACTION = 0.45
GOOD_MIN_BEATS = 8
RR_RANGE_S = (0.4, 1.6)
GOOD_MIN_RR_FRACTION = 0.80
GOOD_MIN_RR_TIME_FRACTION = 0.60
GOOD_HR_RANGE = (30.0, 180.0)
SAMPLE_STEP_MS = 5_000                           # raw samples every 5 s (the ~0.2-Hz sampling of the training watches)
SAMPLE_OFFSET_MS = 2_500                         # t_j = 2.5 s + 5 s * j, RR midpoints in [t_j - 2.5 s, t_j + 2.5 s)
SAMPLE_MIN_RR = 2
CONTEXT_MS = 30 * 60_000                         # heart rate kept on both sides of a window, as prepare.py

# ------------------------------------------------------------------------------------ YASA (section 13.4) -----

YASA_VERSION = "0.7.0"
YASA_WHEEL = "yasa-0.7.0-py3-none-any.whl"
YASA_WHEEL_SHA256 = "114d9cab3dd8237847657b45540aa5df1edd27eea0bd590b8154a6fb2426b0c9"
YASA_CLASSIFIER = "clf_eeg+eog+emg_lgb_0.5.0.joblib"
YASA_CLASSIFIER_SHA256 = "3d27d440c94c61d8013c81c91693ad88810af2ddae85b62f4bc797f6e2464beb"
# LightGBM's Linux wheel needs the OpenMP runtime libgomp.so.1, which the build machine does not have (no root):
# Ubuntu 24.04 (noble-updates) libgomp1, the GCC 14.2 runtime of the machine's own gcc-14-base / libgcc-s1 (same
# source version; package built 2026-02-12), digest-pinned in the lock and loaded explicitly by external_stage.py.
# Licence: GPL-3.0 with the GCC Runtime Library Exception (used at run time only, never distributed).
OPENMP_VERSION = "14.2.0-4ubuntu2~24.04.1"
OPENMP_DEB = "libgomp1_14.2.0-4ubuntu2~24.04.1_amd64.deb"
OPENMP_DEB_SHA256 = "e8a95ec58125b4933597f30ff56c2ae10edf90f287262e366d4b6edea3019144"
OPENMP_LIB_SHA256 = "135f3c8f006d2fe5e68e51281c7974cb991a03de3bfb3593d68d174dfcf854d1"
YASA_STAGES = ("W", "N1", "N2", "N3", "R")
YASA_TO_STAGE = {"W": "AWAKE", "N1": "LIGHT", "N2": "LIGHT", "N3": "DEEP", "R": "REM"}
EPOCH_MS = 30_000
CENTRAL_BAND_HZ = (0.5, 30.0)
CENTRAL_WELCH_S = 4

# --------------------------------------------------------------------------------------- windows (section 13.5) ----

WINDOWS = ("oh", "oh1", "m60", "rec")
NAP_GROUPS = ("NREM", "REM")
MARGIN_MS = 5 * 60_000
RUN_EPOCHS = 3
M60_BEFORE_MS = 5 * 60_000
M60_AFTER_MS = 55 * 60_000
MAX_WINDOW_MS = 180 * 60_000                     # every window is a nap (napMode.maxSessionMin = 180)
VARIANTS = {"instantaneous": "inst", "mean2min": "mean2min"}

MANIFEST_NAME = "manifest.json"
SUMMARY_NAME = "yasa-summary.json"
NOTE_MANIFEST = "NOTE ds006576 manifest sha256 "
NOTE_SUMMARY = "NOTE ds006576 yasa-summary sha256 "
CODE_FILES = ("external.py", "external_stage.py", "pulsetrain/ds006576.py", "pulsetrain/ohealth.py",
              "pulsetrain/util.py")


class ExternalError(RuntimeError):
    """The external test set cannot be fetched, staged or frozen as pre-registered (a void condition)."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def digest_of_digests(files: dict[str, str]) -> str:
    """One digest over ``{relative path: sha256}`` (sorted, ``path\\0digest\\n``; as util.code_fingerprint)."""
    return sha256_bytes("".join(f"{k}\0{v}\n" for k, v in sorted(files.items())).encode("utf-8"))


def code_digests(root: Path = TRAIN_DIR) -> dict:
    """Content digests of the extraction code (no git state, so that a re-extraction from the same code reproduces
    the manifest byte for byte whatever was committed since)."""
    files = {name: sha256_file(root / name) for name in CODE_FILES if (root / name).is_file()}
    return {"files": files, "sha256": digest_of_digests(files)}


# ======================================================================================== key list and pin ====

@dataclass(frozen=True)
class KeyRow:
    subject: str
    group: str
    bytes: int
    duration_s: str        # RecordingDuration as written in the sidecar (kept as text: exact)

    @property
    def expected_bytes(self) -> Decimal:
        return Decimal(self.duration_s) * FS_RAW * FRAME_BYTES

    @property
    def truncated(self) -> bool:
        """Section 13.2: a size that is not a multiple of 292 bytes or differs from RecordingDuration x 512 x 292."""
        return self.bytes % FRAME_BYTES != 0 or Decimal(self.bytes) != self.expected_bytes


KEYS_HEADER = ("subject", "group", "bytes", "recordingDurationS")


def format_keys(rows: Sequence[KeyRow], comments: Sequence[str] = ()) -> str:
    out = [f"# {c}" if c else "#" for c in comments]
    out.append("\t".join(KEYS_HEADER))
    out += [f"{r.subject}\t{r.group}\t{r.bytes}\t{r.duration_s}" for r in sorted(rows, key=lambda r: r.subject)]
    return "\n".join(out) + "\n"


def parse_keys(text: str) -> list[KeyRow]:
    lines = [x for x in text.splitlines() if x.strip() and not x.startswith("#")]
    if not lines or tuple(lines[0].split("\t")) != KEYS_HEADER:
        raise ExternalError(f"key list header must be {KEYS_HEADER}")
    rows = []
    for x in lines[1:]:
        parts = x.split("\t")
        if (len(parts) != 4 or not SUBJECT_RE.match(parts[0]) or parts[1] not in GROUP_COUNTS
                or not re.fullmatch(r"\d+", parts[2]) or not re.fullmatch(r"\d+(\.\d+)?", parts[3])):
            raise ExternalError(f"bad key-list row {x!r}")
        rows.append(KeyRow(parts[0], parts[1], int(parts[2]), parts[3]))
    if len({r.subject for r in rows}) != len(rows):
        raise ExternalError("duplicate subject in the key list")
    return rows


def read_keys(path: Path = KEYS_PATH) -> list[KeyRow]:
    return parse_keys(Path(path).read_text(encoding="utf-8"))


def parse_participants(text: str) -> dict[str, str]:
    """participants.tsv -> {participant_id: group}."""
    lines = [x for x in text.splitlines() if x.strip()]
    head = lines[0].split("\t")
    i_id, i_group = head.index("participant_id"), head.index("group")
    return {p[i_id].strip(): p[i_group].strip() for p in (x.split("\t") for x in lines[1:])}


S3_NS = "{http://s3.amazonaws.com/doc/2006-03-01/}"


def parse_listing_page(xml_text: str | bytes) -> tuple[dict[str, int], str | None]:
    """One ListObjectsV2 page -> ({key: size}, next continuation token or None)."""
    root = ET.fromstring(xml_text)
    ns = S3_NS if root.tag.startswith(S3_NS) else ""
    items = {}
    for c in root.findall(f"{ns}Contents"):
        key = c.findtext(f"{ns}Key")
        size = c.findtext(f"{ns}Size")
        if key is None or size is None:
            raise ExternalError("listing entry without Key / Size")
        items[key] = int(size)
    truncated = (root.findtext(f"{ns}IsTruncated") or "false").strip().lower() == "true"
    token = root.findtext(f"{ns}NextContinuationToken") if truncated else None
    if truncated and not token:
        raise ExternalError("truncated listing without a continuation token")
    return items, token


def eeg_sizes(listing: dict[str, int]) -> dict[str, int]:
    """{subject: size} of the ``sub-<id>/eeg/sub-<id>_task-rest_eeg.eeg`` objects of a bucket listing."""
    out = {}
    for key, size in listing.items():
        m = EEG_KEY_RE.match(key)
        if m and m.group(1) == m.group(2):
            out[m.group(1)] = size
    return out


def changes_first_entry(text: str) -> tuple[str, str] | None:
    """(version, date) of the first entry of a BIDS ``CHANGES`` file."""
    for line in text.splitlines():
        if line.strip():
            m = re.match(r"^\s*(\d+\.\d+\.\d+)\s+(\d{4}-\d{2}-\d{2})\b", line)
            return (m.group(1), m.group(2)) if m else None
    return None


def snapshot_pin(description: dict, changes_text: str, listing_sizes: dict[str, int], keys: Sequence[KeyRow],
                 groups: dict[str, str] | None = None, durations: dict[str, str] | None = None) -> list[str]:
    """Section 13.2: the reasons the snapshot pin fails (empty = pinned). ``listing_sizes`` = ``eeg_sizes`` of the
    bucket listing; ``groups`` (participants.tsv) and ``durations`` (each ``_eeg.json``'s RecordingDuration) are
    checked against the committed key list when given, because the key list is (subject, group, size,
    RecordingDuration) of v1.0.5 and both are public metadata of the same immutable snapshot."""
    fails = []
    doi = description.get("DatasetDOI") if isinstance(description, dict) else None
    if doi != DATASET_DOI:
        fails.append(f"DatasetDOI is {doi!r}, not {DATASET_DOI!r}")
    first = changes_first_entry(changes_text)
    if first != CHANGES_FIRST_ENTRY:
        fails.append(f"first CHANGES entry is {first}, not {CHANGES_FIRST_ENTRY}")
    key_sizes = {k.subject: k.bytes for k in keys}
    counts = {g: sum(1 for k in keys if k.group == g) for g in GROUP_COUNTS}
    if len(keys) != N_EEG_OBJECTS or counts != GROUP_COUNTS:
        fails.append(f"the key list has {len(keys)} rows {counts}, not {N_EEG_OBJECTS} {GROUP_COUNTS}")
    if set(listing_sizes) != set(key_sizes):
        extra = sorted(set(listing_sizes) - set(key_sizes))
        missing = sorted(set(key_sizes) - set(listing_sizes))
        fails.append(f"listing .eeg objects differ from the key list (extra {extra}, missing {missing})")
    diff = sorted(s for s in set(listing_sizes) & set(key_sizes) if listing_sizes[s] != key_sizes[s])
    if diff:
        fails.append(f"listing sizes differ from the key list for {diff}")
    if groups is not None:
        bad = sorted(k.subject for k in keys if groups.get(k.subject) != k.group)
        if bad:
            fails.append(f"participants.tsv groups differ from the key list for {bad}")
    if durations is not None:
        bad = sorted(k.subject for k in keys if k.subject in durations
                     and Decimal(str(durations[k.subject])) != Decimal(k.duration_s))
        if bad:
            fails.append(f"RecordingDuration differs from the key list for {bad}")
    return fails


# ================================================================================================== HTTP =====

def urlopen(url: str, headers: dict[str, str] | None = None, timeout: float = 300.0):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    return urllib.request.urlopen(req, timeout=timeout)


def get_bytes(url: str, retries: int = 6, opener: Callable = urlopen,
              sleep: Callable[[float], None] = time.sleep) -> bytes:
    """A small public file (listing page, sidecar), with retries; HTTP 200 required."""
    last: Exception | None = None
    for attempt in range(retries):
        try:
            with opener(url) as r:
                if r.status != 200:
                    raise ExternalError(f"HTTP {r.status} for {url}")
                return r.read()
        except (OSError, ExternalError) as e:
            last = e
            if attempt < retries - 1:
                sleep(min(30.0, 1.0 + 2.0 * attempt))
    raise ExternalError(f"cannot fetch {url}: {last}")


def list_bucket(prefix: str = f"{DATASET}/", base_url: str = BASE_URL, opener: Callable = urlopen) -> dict[str, int]:
    """Every object of the public bucket under ``prefix`` (ListObjectsV2, paginated) -> {key: size}."""
    items: dict[str, int] = {}
    token = None
    while True:
        q = {"list-type": "2", "prefix": prefix, "max-keys": "1000"}
        if token:
            q["continuation-token"] = token
        page, token = parse_listing_page(get_bytes(f"{base_url}?{urllib.parse.urlencode(q)}", opener=opener))
        items.update(page)
        if not token:
            return items


def range_plan(size: int, step: int | None = None) -> list[tuple[int, int]]:
    """Inclusive byte ranges [a, b] of ``step`` (default ``RANGE_BYTES``) bytes covering [0, size); the last one
    shorter."""
    step = RANGE_BYTES if step is None else step
    if step % FRAME_BYTES:
        raise ValueError("a range must hold whole frames")
    return [(a, min(a + step, size) - 1) for a in range(0, size, step)]


def check_range_response(status: int, content_range: str | None, content_length: str | None, body_len: int | None,
                         a: int, b: int, size: int) -> str | None:
    """Section 13.2: HTTP 206 with exactly ``Content-Range: bytes a-b/size`` and b - a + 1 bytes; None = accepted."""
    if status != 206:
        return f"HTTP {status}, not 206"
    if content_range != f"bytes {a}-{b}/{size}":
        return f"Content-Range {content_range!r}, not 'bytes {a}-{b}/{size}'"
    if content_length is not None and content_length.strip() != str(b - a + 1):
        return f"Content-Length {content_length}, not {b - a + 1}"
    if body_len is not None and body_len != b - a + 1:
        return f"{body_len} bytes, not {b - a + 1}"
    return None


def fetch_range(url: str, a: int, b: int, size: int, opener: Callable = urlopen, retries: int = 8,
                sleep: Callable[[float], None] = time.sleep) -> bytes:
    """Bytes [a, b] of ``url``; a response that fails ``check_range_response`` is discarded (its body is not read
    when the status or headers are already wrong) and the request repeated; fatal after ``retries`` attempts."""
    last = None
    for attempt in range(retries):
        try:
            with opener(url, {"Range": f"bytes={a}-{b}"}) as r:
                hdr = {k.lower(): v for k, v in r.headers.items()}
                err = check_range_response(r.status, hdr.get("content-range"), hdr.get("content-length"), None,
                                           a, b, size)
                if err is None:
                    body = r.read()
                    err = check_range_response(206, f"bytes {a}-{b}/{size}", None, len(body), a, b, size)
                    if err is None:
                        return body
            last = err
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
        except OSError as e:
            last = f"{type(e).__name__}: {e}"
        if attempt < retries - 1:
            sleep(min(60.0, 1.0 + 2.0 * attempt))
    raise ExternalError(f"range {a}-{b} of {url}: {last}")


# ======================================================================================= BrainVision header ====

@dataclass(frozen=True)
class VhdrChannel:
    label: str
    resolution: float
    unit: str


@dataclass(frozen=True)
class Vhdr:
    n_channels: int
    sampling_interval_us: float
    binary_format: str
    orientation: str
    channels: tuple[VhdrChannel, ...]

    @property
    def fs(self) -> float:
        return 1e6 / self.sampling_interval_us


def parse_vhdr(text: str) -> Vhdr:
    """The fields of a BrainVision header used here (``[Common Infos]``, ``[Binary Infos]``, ``[Channel Infos]``)."""
    def field_(name: str) -> str:
        m = re.search(rf"^{name}=(.*)$", text, flags=re.M)
        if not m:
            raise ExternalError(f".vhdr has no {name}")
        return m.group(1).strip()

    chans = {}
    for m in re.finditer(r"^Ch(\d+)=(.*)$", text, flags=re.M):
        parts = m.group(2).strip().split(",")
        label = parts[0].replace("\\1", ",")
        res = float(parts[2]) if len(parts) > 2 and parts[2].strip() else 1.0
        unit = parts[3].strip() if len(parts) > 3 else ""
        chans[int(m.group(1))] = VhdrChannel(label, res, unit)
    n = int(field_("NumberOfChannels"))
    if sorted(chans) != list(range(1, n + 1)):
        raise ExternalError(f".vhdr lists channels {sorted(chans)[:3]}..., not 1..{n}")
    return Vhdr(n, float(field_("SamplingInterval")), field_("BinaryFormat"), field_("DataOrientation"),
                tuple(chans[i] for i in range(1, n + 1)))


def channel_layout(vhdr: Vhdr) -> tuple[dict[str, tuple[int, float]] | None, str | None]:
    """Section 13.2: the channels of ``CHANNEL_LABELS`` found by label -> ({label: (0-based index, scale to µV)},
    None), or (None, reason) when the recording is excluded (a missing or duplicated label, an unknown unit, or a
    binary layout other than the audited one)."""
    if (vhdr.n_channels, vhdr.binary_format, vhdr.orientation) != (N_CHANNELS, "IEEE_FLOAT_32", "MULTIPLEXED"):
        return None, f"layout {vhdr.n_channels} / {vhdr.binary_format} / {vhdr.orientation}"
    if abs(vhdr.fs - FS_RAW) > 1e-9:
        return None, f"sampling rate {vhdr.fs} Hz"
    out = {}
    for label in CHANNEL_LABELS:
        idx = [i for i, c in enumerate(vhdr.channels) if c.label == label]
        if len(idx) != 1:
            return None, f"label {label} found {len(idx)} times"
        c = vhdr.channels[idx[0]]
        if c.unit not in UNIT_TO_UV:
            return None, f"unit {c.unit!r} of {label}"
        out[label] = (idx[0], c.resolution * UNIT_TO_UV[c.unit])
    return out, None


# ============================================================================================ reduction =====

class Reducer:
    """Receives the bytes of one ``.eeg`` object in order, hashes them and keeps the channels of ``CHANNEL_LABELS``
    (float32 as stored, unscaled) of every complete frame; a trailing partial frame (truncated file) is dropped."""

    def __init__(self, size: int, layout: dict[str, tuple[int, float]]):
        self.size = size
        self.layout = layout
        self.cols = [layout[label][0] for label in CHANNEL_LABELS]
        self.frames = np.zeros((size // FRAME_BYTES, len(CHANNEL_LABELS)), dtype=np.float32)
        self.n = 0
        self.received = 0
        self.sha = hashlib.sha256()
        self._carry = b""

    def feed(self, data: bytes) -> None:
        self.sha.update(data)
        self.received += len(data)
        buf = self._carry + data if self._carry else data
        k = len(buf) // FRAME_BYTES
        if k:
            a = np.frombuffer(buf, dtype="<f4", count=k * N_CHANNELS).reshape(k, N_CHANNELS)
            self.frames[self.n:self.n + k] = a[:, self.cols]
            self.n += k
        self._carry = bytes(buf[k * FRAME_BYTES:])

    def result(self) -> tuple[np.ndarray, str]:
        if self.received != self.size:
            raise ExternalError(f"received {self.received} of {self.size} bytes")
        return self.frames[: self.n], self.sha.hexdigest()


def derive(frames: np.ndarray, layout: dict[str, tuple[int, float]]) -> dict[str, np.ndarray]:
    """Section 13.2: the scaled (µV) derivations, time 0 = the first frame. ECG = ECG1 - ECG2 at 512 Hz; C3-A2,
    C4-A1, LOC-A2 and EMG1-EMG2 decimated to 128 Hz by ``scipy.signal.decimate(x, 4, ftype="fir",
    zero_phase=True)`` over the whole recording; stored as float32."""
    col = {label: i for i, label in enumerate(CHANNEL_LABELS)}

    def chan(label: str) -> np.ndarray:
        return frames[:, col[label]].astype(np.float64) * layout[label][1]

    out = {}
    for name, (pos, neg) in DERIVATIONS.items():
        x = chan(pos) - chan(neg)
        if name != "ecg":
            x = signal.decimate(x, DECIMATION, ftype="fir", zero_phase=True)
        out[name] = x.astype(np.float32)
    return out


def arrays_digest(arrays: dict[str, np.ndarray]) -> str:
    """Content digest of named arrays (name, dtype, shape and bytes, sorted by name); independent of the npz
    container, whose zip entries carry time stamps."""
    h = hashlib.sha256()
    for name in sorted(arrays):
        a = np.ascontiguousarray(arrays[name])
        h.update(f"{name}\0{a.dtype.str}\0{a.shape}\0".encode("utf-8"))
        h.update(a.tobytes())
    return h.hexdigest()


def save_derived(path: Path, arrays: dict[str, np.ndarray]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    with open(tmp, "wb") as f:
        np.savez_compressed(f, **arrays)
    os.replace(tmp, path)


def load_derived(path: Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as z:
        return {k: z[k] for k in z.files}


def stream_recording(url: str, size: int, layout: dict[str, tuple[int, float]], opener: Callable = urlopen,
                     retries: int = 8, sleep: Callable[[float], None] = time.sleep) -> tuple[dict, str, int]:
    """Streams one ``.eeg`` object in ``range_plan`` order -> (derivations, SHA-256 of all bytes, complete frames).
    No raw byte is written anywhere."""
    red = Reducer(size, layout)
    for a, b in range_plan(size):
        red.feed(fetch_range(url, a, b, size, opener=opener, retries=retries, sleep=sleep))
    frames, digest = red.result()
    return derive(frames, layout), digest, int(frames.shape[0])


# ========================================================================== ECG -> heart rate (frozen) =====

def bandpass(x: np.ndarray, fs: float, lo: float, hi: float, order: int = BUTTER_ORDER) -> np.ndarray:
    sos = signal.butter(order, [lo, hi], btype="bandpass", fs=fs, output="sos")
    return signal.sosfiltfilt(sos, x)


def rpeaks(ecg: np.ndarray, fs: float) -> tuple[np.ndarray, float]:
    """R-peaks of one 15-s ECG chunk -> (sample indices, polarity). Section 13.3 step 1: 3rd-order Butterworth 5-25 Hz
    band-pass (second-order sections, zero phase) of the mean-removed chunk; polarity = sign of the larger of the
    99.7th percentile and minus the 0.3rd percentile; candidate peaks of the polarity-corrected signal at least
    0.3 s apart; kept if their height is >= 45 % of the 90th percentile of the candidate heights that are >= 30 % of
    the largest. Frozen (pilot extract.rpeaks)."""
    x = np.asarray(ecg, dtype=np.float64)
    if x.size == 0 or not np.all(np.isfinite(x)):
        # guard (not in the pilot, which never met such a chunk): no finite signal, no beat
        return np.array([], dtype=int), 1.0
    x = bandpass(x - np.mean(x), fs, BAND_HZ[0], BAND_HZ[1])
    pol = 1.0 if np.percentile(x, POLARITY_PCT[0]) >= -np.percentile(x, POLARITY_PCT[1]) else -1.0
    y = pol * x
    cand, _ = signal.find_peaks(y, distance=int(MIN_PEAK_DISTANCE_S * fs))
    if len(cand) == 0:
        return np.array([], dtype=int), pol
    h = y[cand]
    top = h[h >= TOP_FRACTION * h.max()]
    if top.size == 0:
        # guard (not in the pilot, where it would raise): every candidate height is negative
        return np.array([], dtype=int), pol
    ref = np.percentile(top, TOP_PERCENTILE)
    return cand[h >= KEEP_FRACTION * ref], pol


def good_chunk(peaks: np.ndarray, fs: float) -> bool:
    """Section 13.3 step 1: >= 8 beats, >= 80 % of the RR intervals in [0.4, 1.6] s, >= 60 % of the RR time in such
    intervals and a mean heart rate over them (60 n / sum RR) in [30, 180] bpm. RR variability is never a quality
    criterion. Frozen (pilot sessions.good_ecg)."""
    if len(peaks) < GOOD_MIN_BEATS:
        return False
    rr = np.diff(np.asarray(peaks)) / fs
    ok = (rr >= RR_RANGE_S[0]) & (rr <= RR_RANGE_S[1])
    if np.mean(ok) < GOOD_MIN_RR_FRACTION or not ok.any():
        return False
    if rr[ok].sum() / rr.sum() < GOOD_MIN_RR_TIME_FRACTION:
        return False
    hr = 60.0 * ok.sum() / rr[ok].sum()
    return GOOD_HR_RANGE[0] <= hr <= GOOD_HR_RANGE[1]


@dataclass
class EcgResult:
    n_chunks: int
    good: np.ndarray                 # bool per chunk
    rr_mid_ms: np.ndarray            # RR interval midpoints (ms from the first frame), good chunks only
    rr_s: np.ndarray                 # RR intervals (s), kept in [0.4, 1.6]
    valid_range_ms: tuple[int, int] | None


def ecg_rr(ecg: np.ndarray, fs: int = FS_RAW) -> EcgResult:
    """Section 13.3 steps 1, 2 and 4: non-overlapping 15-s chunks from time 0 (a trailing partial chunk is dropped),
    R-peaks and quality per chunk; RR intervals between consecutive peaks of the same good chunk, kept in
    [0.4, 1.6] s, stamped at their midpoint; ECG-valid range = [start of the first good chunk, end of the last]."""
    n = CHUNK_S * fs
    k = len(ecg) // n
    good = np.zeros(k, dtype=bool)
    mids, rrs = [], []
    for i in range(k):
        pk, _ = rpeaks(ecg[i * n:(i + 1) * n], fs)
        if not good_chunk(pk, fs):
            continue
        good[i] = True
        pk = np.asarray(pk, dtype=np.int64)
        rr = np.diff(pk) / fs
        mid_ms = (i * n + (pk[:-1] + pk[1:]) / 2.0) * 1000.0 / fs
        ok = (rr >= RR_RANGE_S[0]) & (rr <= RR_RANGE_S[1])
        mids.append(mid_ms[ok])
        rrs.append(rr[ok])
    valid = None
    if good.any():
        idx = np.flatnonzero(good)
        valid = (int(idx[0]) * CHUNK_S * 1000, (int(idx[-1]) + 1) * CHUNK_S * 1000)
    return EcgResult(k, good, np.concatenate(mids) if mids else np.empty(0), np.concatenate(rrs) if rrs else
                     np.empty(0), valid)


def raw_hr_samples(rr_mid_ms: np.ndarray, rr_s: np.ndarray, duration_ms: int) -> tuple[np.ndarray, np.ndarray]:
    """Section 13.3 step 3: at t_j = 2.5 s + 5 s * j, 60 / the median of the RR intervals with midpoint in
    [t_j - 2.5 s, t_j + 2.5 s), rounded to whole bpm, when there are >= 2 such intervals; otherwise no sample.
    (np.rint; an exact half cannot occur for RR intervals of whole 512-Hz samples within [0.4, 1.6] s.)"""
    order = np.argsort(rr_mid_ms, kind="stable")
    mid, rr = np.asarray(rr_mid_ms)[order], np.asarray(rr_s)[order]
    t_out, v_out = [], []
    n = int(max(0, duration_ms - SAMPLE_OFFSET_MS) // SAMPLE_STEP_MS) + 1 if duration_ms > 0 else 0
    lo = np.searchsorted(mid, np.arange(n) * SAMPLE_STEP_MS, side="left")
    hi = np.searchsorted(mid, np.arange(n) * SAMPLE_STEP_MS + 2 * SAMPLE_OFFSET_MS, side="left")
    for j in np.flatnonzero(hi - lo >= SAMPLE_MIN_RR):
        t_out.append(SAMPLE_OFFSET_MS + SAMPLE_STEP_MS * int(j))
        v_out.append(float(np.rint(60.0 / np.median(rr[lo[j]:hi[j]]))))
    return np.asarray(t_out, dtype=np.int64), np.asarray(v_out, dtype=float)


def hr_variants(t_ms: np.ndarray, bpm: np.ndarray, s0: int, s1: int, valid_range_ms: tuple[int, int],
                subject: str) -> dict[str, list[list]]:
    """Section 13.3 step 5: both variants exactly as prepare.py (``ohealth.clean_hr``, then the resampler of the
    variant with the phase ``grid_phase_ms("ds006576/<subject>")``) over [s0 - 30 min, s1 + 30 min] intersected
    with the ECG-valid range."""
    lo, hi = max(s0 - CONTEXT_MS, valid_range_ms[0]), min(s1 + CONTEXT_MS, valid_range_ms[1])
    t, v = ohealth.clean_hr(t_ms, bpm)
    phase = ohealth.grid_phase_ms(f"{DATASET}/{subject}")
    return {variant: ohealth.VARIANT_RESAMPLERS[variant](t, v, lo, hi, phase) for variant in VARIANTS}


# ============================================================================ central derivation (13.4) =====

def central_band_power(x: np.ndarray, fs: int = FS_EEG) -> float:
    """Median over the complete 30-s epochs (from time 0) of the 0.5-30 Hz band power (Welch, 4-s windows)."""
    n = 30 * fs
    k = len(x) // n
    if k == 0:
        raise ExternalError("shorter than one 30-s epoch")
    ep = np.asarray(x[: k * n], dtype=np.float64).reshape(k, n)
    f, p = signal.welch(ep, fs=fs, nperseg=CENTRAL_WELCH_S * fs, axis=-1)
    m = (f >= CENTRAL_BAND_HZ[0]) & (f <= CENTRAL_BAND_HZ[1])
    return float(np.median(trapezoid(p[:, m], f[m], axis=-1)))


def central_choice(c3: np.ndarray, c4: np.ndarray, fs: int = FS_EEG) -> tuple[str, dict[str, float]]:
    """Section 13.4 (label-free): C3-A2 or C4-A1, whichever has the lower median 0.5-30 Hz band power over 30-s
    epochs; ties -> C3-A2."""
    p3, p4 = central_band_power(c3, fs), central_band_power(c4, fs)
    return ("C4-A1" if p4 < p3 else "C3-A2"), {"C3-A2": p3, "C4-A1": p4}


# ============================================================================================ windows (13.5) =====

def run_bounds(stages: Sequence[str], run: int) -> tuple[int, int] | None:
    """(first epoch that starts, last epoch that ends) a run of >= ``run`` consecutive non-W epochs; None if none."""
    sleep = np.array([s != "W" for s in stages], dtype=bool)
    n = len(sleep)
    if n < run:
        return None
    full = np.ones(n - run + 1, dtype=bool)
    for k in range(run):
        full &= sleep[k:n - run + 1 + k]
    idx = np.flatnonzero(full)
    if idx.size == 0:
        return None
    return int(idx[0]), int(idx[-1]) + run - 1


def windows_from_stages(stages: Sequence[str], valid_range_ms: tuple[int, int] | None, group: str
                        ) -> dict[str, tuple[int, int]]:
    """Section 13.5. ``stages``: one YASA stage per 30-s epoch from time 0. Returns {window: (s0, s1)} in ms:

    - oh (naps): [start(onset) - 5 min, end(final) + 5 min] clipped to the ECG-valid range, onset / final = the first
      epoch that starts / the last epoch that ends a run of >= 3 consecutive non-W epochs;
    - oh1 (naps): the same with single epochs (first and last non-W epoch);
    - m60 (naps): [start(onset) - 5 min, start(onset) + 55 min], only when it lies inside the ECG-valid range;
    - rec (naps and WAKE recordings): the whole ECG-valid range.

    No ECG-valid range -> no window; a nap without a run of 3 has no oh and no m60 window."""
    out: dict[str, tuple[int, int]] = {}
    if valid_range_ms is None:
        return out
    v0, v1 = valid_range_ms
    if group in NAP_GROUPS:
        for name, run in (("oh", RUN_EPOCHS), ("oh1", 1)):
            b = run_bounds(stages, run)
            if b is None:
                continue
            s0 = max(b[0] * EPOCH_MS - MARGIN_MS, v0)
            s1 = min((b[1] + 1) * EPOCH_MS + MARGIN_MS, v1)
            if s1 > s0:
                out[name] = (s0, s1)
        b = run_bounds(stages, RUN_EPOCHS)
        if b is not None:
            s0, s1 = b[0] * EPOCH_MS - M60_BEFORE_MS, b[0] * EPOCH_MS + M60_AFTER_MS
            if v0 <= s0 and s1 <= v1:
                out["m60"] = (s0, s1)
    if v1 > v0:
        out["rec"] = (v0, v1)
    for name, (s0, s1) in out.items():
        if s1 - s0 >= MAX_WINDOW_MS:
            raise ExternalError(f"window {name} of {(s1 - s0) / 60_000:.1f} min is not a nap")
    return {w: out[w] for w in WINDOWS if w in out}


def window_labels(stages: Sequence[str], s0: int, s1: int) -> list[list]:
    """Section 13.6: the YASA epochs fully inside [s0, s1] as [startMs, endMs, stage] (W -> AWAKE, N1 / N2 -> LIGHT,
    N3 -> DEEP, R -> REM)."""
    return [[i * EPOCH_MS, (i + 1) * EPOCH_MS, YASA_TO_STAGE[s]] for i, s in enumerate(stages)
            if i * EPOCH_MS >= s0 and (i + 1) * EPOCH_MS <= s1]


def night_id(subject: str, window: str, variant: str) -> str:
    return f"{DATASET}-{subject}-{window}-{VARIANTS[variant]}"


def night_relpath(subject: str, window: str, variant: str) -> str:
    return f"nights/{variant}/{DATASET}-{subject}-{window}.night.json"


def night_object(subject: str, window: str, variant: str, s0: int, s1: int, hr: list[list],
                 labels: list[list]) -> dict:
    """A night file (docs/INTERCHANGE.md section 1): times in ms since the first frame (no clock is known or read),
    no steps, silver labels."""
    return {
        "id": night_id(subject, window, variant),
        "subject": f"{DATASET}-{subject}",
        "variant": variant,
        "sessionStartMs": int(s0),
        "sessionEndMs": int(s1),
        "hr": hr,
        "steps": [],
        "labels": labels,
    }


def night_bytes(night: dict) -> bytes:
    """Serialization of prepare.py (compact JSON + newline)."""
    return (json.dumps(night, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


# ================================================================================ stage files (external_stage) ====

def stage_relpath(subject: str) -> str:
    return f"stages/{subject}.json"


def derived_relpath(subject: str) -> str:
    return f"derived/{subject}.npz"


def record_relpath(subject: str) -> str:
    return f"derived/{subject}.json"


def stage_bytes(stage: dict) -> bytes:
    return (json.dumps(stage, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


def check_stage_file(stage: dict, lock: "Lock") -> list[str]:
    """Reasons a stage file was not produced by the locked YASA environment (empty = ok)."""
    y = stage.get("yasa") or {}
    fails = []
    if y.get("version") != YASA_VERSION or lock.pins.get("yasa") != YASA_VERSION:
        fails.append(f"yasa version {y.get('version')!r} (lock {lock.pins.get('yasa')!r})")
    if y.get("wheelSha256") != lock.yasa_wheel_sha256:
        fails.append("yasa wheel digest differs from the lock")
    if y.get("classifier") != lock.classifier_name or y.get("classifierSha256") != lock.classifier_sha256:
        fails.append("yasa classifier differs from the lock")
    if y.get("openmpSha256") != lock.openmp_lib_sha256:
        fails.append("the OpenMP runtime (libgomp.so.1) differs from the lock")
    stages = stage.get("stages")
    if not isinstance(stages, list) or any(s not in YASA_STAGES for s in stages):
        fails.append("stages are not W / N1 / N2 / N3 / R")
    return fails


# ========================================================================================= summary (13.6) =====

def _mean_sd(values: Sequence[float]) -> dict:
    a = np.asarray(values, dtype=float)
    return {"n": int(a.size), "mean": round(float(a.mean()), 4) if a.size else None,
            "sd": round(float(a.std(ddof=1)), 4) if a.size > 1 else None}


def window_stage_minutes(stages: Sequence[str], s0: int, s1: int) -> dict[str, float]:
    """YASA minutes per stage over the epochs fully inside [s0, s1]; WASO = W between the first and last non-W."""
    inside = [stages[i] for i in range(len(stages)) if i * EPOCH_MS >= s0 and (i + 1) * EPOCH_MS <= s1]
    mins = {s: 0.5 * sum(1 for x in inside if x == s) for s in YASA_STAGES}
    sl = [i for i, x in enumerate(inside) if x != "W"]
    waso = 0.5 * sum(1 for x in inside[sl[0]:sl[-1] + 1] if x == "W") if sl else 0.0
    return {**mins, "TST": mins["N1"] + mins["N2"] + mins["N3"] + mins["R"], "WASO": waso,
            "window": (s1 - s0) / 60_000.0}


def yasa_summary(per_recording: dict[str, dict], identity: dict) -> dict:
    """YASA's own group summaries (section 13.6): NREM and REM groups from the oh windows (TST, N1 / N2 / N3 / REM
    and WASO minutes, N3 and REM % of TST, window length; per-recording means with SD), the WAKE group from the rec
    windows (YASA sleep minutes). ``per_recording``: {subject: {"group", "complete", "oh": minutes | None,
    "rec": minutes | None}}; truncated recordings are left out."""
    groups = {}
    for g in NAP_GROUPS:
        rows = [r["oh"] for r in per_recording.values() if r["group"] == g and r["complete"] and r.get("oh")]
        with_sleep = [m for m in rows if m["TST"] > 0]
        groups[g] = {
            "window": "oh",
            "recordings": sum(1 for r in per_recording.values() if r["group"] == g and r["complete"]),
            "withWindow": len(rows),
            "windowMin": _mean_sd([m["window"] for m in rows]),
            "tstMin": _mean_sd([m["TST"] for m in rows]),
            "n1Min": _mean_sd([m["N1"] for m in rows]),
            "n2Min": _mean_sd([m["N2"] for m in rows]),
            "n3Min": _mean_sd([m["N3"] for m in rows]),
            "remMin": _mean_sd([m["R"] for m in rows]),
            "wasoMin": _mean_sd([m["WASO"] for m in rows]),
            "n3PctTst": _mean_sd([100.0 * m["N3"] / m["TST"] for m in with_sleep]),
            "remPctTst": _mean_sd([100.0 * m["R"] / m["TST"] for m in with_sleep]),
        }
    wake = [r["rec"] for r in per_recording.values() if r["group"] == "WAKE" and r["complete"] and r.get("rec")]
    groups["WAKE"] = {
        "window": "rec",
        "recordings": sum(1 for r in per_recording.values() if r["group"] == "WAKE" and r["complete"]),
        "withWindow": len(wake),
        "sleepMin": _mean_sd([m["TST"] for m in wake]),
        "below5MinSleep": sum(1 for m in wake if m["TST"] < 5.0),
    }
    return {"dataset": DATASET, "yasa": identity, "groups": groups,
            "note": "YASA's stages are silver labels (reported only); section 13.4 of the pre-registration"}


# ============================================================================================== lock file =====

@dataclass
class Lock:
    pins: dict[str, str] = field(default_factory=dict)          # normalized name -> version
    hashes: dict[str, str] = field(default_factory=dict)        # normalized name -> sha256
    yasa_wheel_name: str | None = None
    yasa_wheel_sha256: str | None = None
    classifier_name: str | None = None
    classifier_sha256: str | None = None
    openmp_deb_name: str | None = None
    openmp_deb_sha256: str | None = None
    openmp_lib_sha256: str | None = None


def normalize_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


LOCK_COMMENT_RE = {
    "yasa-wheel": re.compile(r"^# yasa-wheel (\S+) sha256 ([0-9a-f]{64})$"),
    "yasa-classifier": re.compile(r"^# yasa-classifier (\S+) sha256 ([0-9a-f]{64})$"),
    "openmp-deb": re.compile(r"^# openmp-deb (\S+) sha256 ([0-9a-f]{64})$"),
    "openmp-lib": re.compile(r"^# openmp-lib libgomp\.so\.1 sha256 ([0-9a-f]{64})$"),
}


def parse_lock(text: str) -> Lock:
    lock = Lock()
    joined = re.sub(r"\\\n\s*", " ", text)
    for line in joined.splitlines():
        s = line.strip()
        if not s:
            continue
        if s.startswith("#"):
            for kind, rx in LOCK_COMMENT_RE.items():
                m = rx.match(s)
                if m and kind == "yasa-wheel":
                    lock.yasa_wheel_name, lock.yasa_wheel_sha256 = m.group(1), m.group(2)
                elif m and kind == "yasa-classifier":
                    lock.classifier_name, lock.classifier_sha256 = m.group(1), m.group(2)
                elif m and kind == "openmp-deb":
                    lock.openmp_deb_name, lock.openmp_deb_sha256 = m.group(1), m.group(2)
                elif m and kind == "openmp-lib":
                    lock.openmp_lib_sha256 = m.group(1)
            continue
        m = re.match(r"^([A-Za-z0-9][A-Za-z0-9._-]*)==(\S+)((?:\s+--hash=sha256:[0-9a-f]{64})*)\s*$", s)
        if not m:
            raise ExternalError(f"lock line is not 'name==version --hash=sha256:...': {s[:80]!r}")
        name = normalize_name(m.group(1))
        lock.pins[name] = m.group(2)
        hashes = re.findall(r"--hash=sha256:([0-9a-f]{64})", m.group(3))
        if len(hashes) != 1:
            raise ExternalError(f"lock line of {name} must carry exactly one sha256 hash")
        lock.hashes[name] = hashes[0]
    return lock


def read_lock(path: Path = LOCK_PATH) -> Lock:
    return parse_lock(Path(path).read_text(encoding="utf-8"))


def lock_identity_failures(identity: dict, lock: Lock) -> list[str]:
    """Section 13.6 (4): the YASA version, wheel and classifier digests (of a manifest or a stage file) equal the
    lock and the pre-registered values; so does the digest of the OpenMP runtime the stager loaded before LightGBM
    (``openmpSha256``, the lock's ``openmp-lib`` line: a mismatch fails exactly as a wheel mismatch does)."""
    fails = []
    want = {"version": (lock.pins.get("yasa"), YASA_VERSION),
            "wheelSha256": (lock.yasa_wheel_sha256, YASA_WHEEL_SHA256),
            "classifier": (lock.classifier_name, YASA_CLASSIFIER),
            "classifierSha256": (lock.classifier_sha256, YASA_CLASSIFIER_SHA256),
            "openmpSha256": (lock.openmp_lib_sha256, OPENMP_LIB_SHA256)}
    for key, (from_lock, prereg) in want.items():
        if identity.get(key) != from_lock or from_lock != prereg:
            fails.append(f"yasa {key}: {identity.get(key)!r} vs lock {from_lock!r} vs pre-registered {prereg!r}")
    return fails


# ========================================================================================= commands log =====

def now_stamp() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def append_log(log: Path, text: str) -> str:
    """Appends ``<time stamp> <text>`` to the commands log (append only, one line)."""
    if "\n" in text:
        raise ValueError("a log line must not contain a newline")
    line = f"{now_stamp()} {text}"
    log = Path(log)
    log.parent.mkdir(parents=True, exist_ok=True)
    with open(log, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    return line


def logged_digests(lines: Iterable[str]) -> tuple[list[str], list[str]]:
    """The digests of every ``NOTE ds006576 manifest sha256`` / ``... yasa-summary sha256`` line, in log order."""
    man, summ = [], []
    for x in lines:
        for marker, out in ((NOTE_MANIFEST, man), (NOTE_SUMMARY, summ)):
            i = x.find(" " + marker)
            if i >= 0 and STAMP_RE.match(x):
                out.append(x[i + 1 + len(marker):].split()[0])
    return man, summ


STAMP_RE = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d")


def _is(line: str, kind: str) -> bool:
    return bool(STAMP_RE.match(line)) and f" {kind} " in line[:40]


def _start_of(line: str, pattern: str) -> bool:
    return _is(line, "START") and re.search(pattern, line) is not None


def freeze_order(lines: Sequence[str], *, prereg_sha256: str = PREREG_SHA256, exp_run: str = "naps-realistic",
                 complete: bool = True) -> dict:
    """Section 13.6 (1) and (2) on the commands log: each of the two ds006576 NOTE lines exactly once, and

        the pre-registration NOTE < every START of ``external.py fetch`` < every START of ``external_stage.py``
        (staging, not ``--self-test``) < every START of ``external.py nights`` (not ``--reproduce``) < the two NOTE
        lines < the first START of ``train.py naps-realistic`` (that subcommand itself) < the RESULT line of the run
        ``exp_run`` < the (last) START of ``train.py naps-realistic-external``.

    ``complete`` = False checks a log that is still growing (the committed log of the code commit): an element may
    be missing, but every element that is present requires all earlier elements, in order. Returns
    ``{"ok", "failures", "positions", "manifestSha256", "summarySha256"}`` (line numbers from 1)."""
    lines = list(lines)
    fails: list[str] = []
    pos: dict[str, list[int]] = {
        "prereg": [i for i, x in enumerate(lines)
                   if _is(x, "NOTE") and f" NOTE {PREREG_NAME} sha256 {prereg_sha256}" in x],
        "fetch": [i for i, x in enumerate(lines) if _start_of(x, r"\bexternal\.py fetch\b")],
        "stage": [i for i, x in enumerate(lines) if _start_of(x, r"\bexternal_stage\.py\b") and "--self-test" not in x],
        "nights": [i for i, x in enumerate(lines)
                   if _start_of(x, r"\bexternal\.py nights\b") and "--reproduce" not in x],
        "manifest": [i for i, x in enumerate(lines) if _is(x, "NOTE") and f" {NOTE_MANIFEST}" in x],
        "summary": [i for i, x in enumerate(lines) if _is(x, "NOTE") and f" {NOTE_SUMMARY}" in x],
        "experiment": [i for i, x in enumerate(lines) if _start_of(x, r"\btrain\.py naps-realistic(?![\w-])")],
        "result": [i for i, x in enumerate(lines)
                   if _is(x, "RESULT") and re.search(rf" RESULT {re.escape(exp_run)}(?![\w-])", x)],
        "external": [i for i, x in enumerate(lines) if _start_of(x, r"\btrain\.py naps-realistic-external\b")],
    }
    for kind in ("manifest", "summary"):
        if len(pos[kind]) > 1:
            fails.append(f"NOTE ds006576 {kind} line occurs {len(pos[kind])} times, not once")
    chain = ["prereg", "fetch", "stage", "nights", "notes", "experiment", "result", "external"]
    present = {k: bool(pos[k]) for k in pos}
    present["notes"] = present["manifest"] and present["summary"]
    if present["manifest"] != present["summary"]:
        fails.append("only one of the two ds006576 NOTE lines is in the log")
    last_needed = max((i for i, k in enumerate(chain) if present[k]), default=-1)
    need = len(chain) - 1 if complete else last_needed
    for k in chain[: need + 1]:
        if not present[k]:
            fails.append(f"missing in the log: {k}")
    # (first, last) line of each element; the RESULT is the last one of exp_run before the external START
    span = {k: (min(v), max(v)) for k, v in pos.items() if v}
    if present["notes"]:
        span["notes"] = (min(pos["manifest"] + pos["summary"]), max(pos["manifest"] + pos["summary"]))
    if pos["experiment"]:
        span["experiment"] = (pos["experiment"][0], pos["experiment"][0])
    if pos["external"]:
        last_x = pos["external"][-1]
        span["external"] = (last_x, last_x)
        before = [i for i in pos["result"] if i < last_x]
        if before:
            span["result"] = (before[-1], before[-1])
        else:
            fails.append(f"no RESULT line of {exp_run} before the START of naps-realistic-external")
    elif pos["result"]:
        span["result"] = (pos["result"][-1], pos["result"][-1])
    if pos["prereg"]:
        span["prereg"] = (pos["prereg"][0], pos["prereg"][0])
    order = [k for k in chain if k in span]
    for a, b in zip(order, order[1:]):
        if not span[a][1] < span[b][0]:
            fails.append(f"order: {a} (line {span[a][1] + 1}) is not before {b} (line {span[b][0] + 1})")
    man, summ = logged_digests(lines)
    return {"ok": not fails, "failures": fails,
            "positions": {k: [i + 1 for i in v] for k, v in pos.items()},
            "manifestSha256": man[0] if len(man) == 1 else None,
            "summarySha256": summ[0] if len(summ) == 1 else None}


def freeze_check(data_dir: Path = DATA_DIR, *, log_path: Path = COMMANDS_LOG, lock_path: Path = LOCK_PATH,
                 prereg_sha256: str = PREREG_SHA256, exp_run: str = "naps-realistic") -> dict:
    """Gate G9 (section 13.6): (1) + (2) ``freeze_order`` on the commands log; (3) ``manifest.json`` and
    ``yasa-summary.json`` of ``data_dir`` have exactly the logged digests, and every night file listed in the
    manifest still has its digest; (4) the manifest's YASA version, wheel and classifier digests equal the lock.
    Reads digests only (no silver-label value). Returns the order record with ``ok`` and every failure."""
    data_dir = Path(data_dir)
    rec = freeze_order(Path(log_path).read_text(encoding="utf-8").splitlines(), prereg_sha256=prereg_sha256,
                       exp_run=exp_run, complete=True)
    fails = list(rec["failures"])
    man_path, sum_path = data_dir / MANIFEST_NAME, data_dir / SUMMARY_NAME
    if not man_path.is_file() or not sum_path.is_file():
        fails.append("manifest.json or yasa-summary.json missing")
    else:
        d_man, d_sum = sha256_file(man_path), sha256_file(sum_path)
        rec["dataManifestSha256"], rec["dataSummarySha256"] = d_man, d_sum
        if d_man != rec["manifestSha256"]:
            fails.append("manifest.json digest differs from the logged one")
        if d_sum != rec["summarySha256"]:
            fails.append("yasa-summary.json digest differs from the logged one")
        manifest = json.loads(man_path.read_text(encoding="utf-8"))
        bad = [p for p, d in sorted(manifest.get("files", {}).items())
               if not (data_dir / p).is_file() or sha256_file(data_dir / p) != d]
        if bad:
            fails.append(f"{len(bad)} night files differ from the manifest (first {bad[0]})")
        if digest_of_digests(manifest.get("files", {})) != manifest.get("filesSha256"):
            fails.append("the manifest's digest over its files does not match")
        try:
            fails += lock_identity_failures(manifest.get("yasa", {}), read_lock(lock_path))
        except (OSError, ExternalError) as e:
            fails.append(f"lock: {e}")
    rec["failures"], rec["ok"] = fails, not fails
    return rec


# ========================================================================================== night index =====

def assert_not_in_index(index) -> None:
    """Section 13.1: no ds006576 recording may enter a training ``NightIndex`` (raises ValueError)."""
    bad = sorted({n.id for n in index.nights
                  if n.subject.startswith(DATASET) or n.id.startswith(DATASET) or n.dataset == DATASET})
    if bad:
        raise ValueError(f"ds006576 recordings in a training night index: {bad[:3]}")


def write_json_atomic(path: Path, obj: dict) -> bytes:
    """Writes ``obj`` with util.write_json (deterministic pretty JSON, atomic) and returns the bytes written."""
    write_json(path, obj)
    return Path(path).read_bytes()
