"""Reader for the per-epoch CSV written by ``algo-cli features`` (docs/INTERCHANGE.md section 2).

Columns: ``night_id, subject, epoch, start_ms, has_data, label``, then the hybrid feature columns in
``HybridFeatureExtractor.FEATURE_NAMES`` order, then the HMM base features (``hrNorm, hrVar, hrSlope, t,
cyclePhase, steps``) that are NOT already among the hybrid columns — with feature set 1 that is ``cyclePhase,
steps``, because hrNorm / hrVar / hrSlope / t are hybrid features with identical values. The hybrid feature list is
data: it is whatever the header has between the six meta columns and that trailing block; base features are taken
from the trailing block or, by name, from the hybrid columns. Empty field = NaN.

``featureSetVersion`` is looked up in this order: explicit argument, a sidecar ``<csv>.meta.json`` (or
``<stem>.meta.json``) with ``{"featureSetVersion": N, ...}``, a leading comment line ``# featureSetVersion=N``.
The real ``algo-cli features`` writes neither; the pipeline then asks ``algo-cli feature-names`` (and checks that
its ``featureNames`` equal the CSV header). If none is found it stays ``None`` and the caller decides.
"""

from __future__ import annotations

import csv
import json
import re
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np
import pandas as pd

from .util import STAGE_INDEX

META_COLUMNS: tuple[str, ...] = ("night_id", "subject", "epoch", "start_ms", "has_data", "label")
BASE_FEATURES: tuple[str, ...] = ("hrNorm", "hrVar", "hrSlope", "t", "cyclePhase", "steps")
HMM_EMISSION_FEATURES: tuple[str, ...] = ("hrNorm", "hrVar", "hrSlope")
_TRUE = {"1", "true", "t", "yes", "y", "1.0"}
_FALSE = {"0", "false", "f", "no", "n", "0.0", ""}


class FeatureFileError(ValueError):
    pass


@dataclass
class FeatureTable:
    """Per-epoch rows of one or more feature CSVs (one night variant each)."""

    night_id: np.ndarray            # str
    subject: np.ndarray             # str
    epoch: np.ndarray               # int64
    start_ms: np.ndarray            # int64
    has_data: np.ndarray            # bool
    y: np.ndarray                   # int8, stage index 0..3 or -1 (no label / UNKNOWN)
    X: np.ndarray                   # float64 (n, F) hybrid features
    base: np.ndarray                # float64 (n, 6) BASE_FEATURES
    feature_names: list[str]
    feature_set_version: int | None = None
    epoch_min: float = 1.0
    variant: np.ndarray | None = None   # str per row (set by the loader / concat)
    meta: dict = field(default_factory=dict)

    def __len__(self) -> int:
        return int(self.y.shape[0])

    def take(self, mask_or_idx) -> "FeatureTable":
        sel = np.asarray(mask_or_idx)
        return replace(
            self, night_id=self.night_id[sel], subject=self.subject[sel], epoch=self.epoch[sel],
            start_ms=self.start_ms[sel], has_data=self.has_data[sel], y=self.y[sel], X=self.X[sel],
            base=self.base[sel], variant=None if self.variant is None else self.variant[sel],
        )

    def subjects_mask(self, subjects: Iterable[str]) -> np.ndarray:
        return np.isin(self.subject, np.array(sorted(set(subjects)), dtype=object))

    def for_subjects(self, subjects: Iterable[str]) -> "FeatureTable":
        return self.take(self.subjects_mask(subjects))

    def base_column(self, name: str) -> np.ndarray:
        return self.base[:, BASE_FEATURES.index(name)]

    def training_rows(self) -> np.ndarray:
        """Rows usable for supervised fitting: HR data present and a scored label."""
        return self.has_data & (self.y >= 0)

    def label_sequences(self) -> dict[str, np.ndarray]:
        """night_id -> stage-index sequence on the epoch grid (-1 where unlabeled), ordered by epoch."""
        out: dict[str, np.ndarray] = {}
        if len(self) == 0:
            return out
        order = np.lexsort((self.epoch, self.night_id))
        nid, ep, y = self.night_id[order], self.epoch[order], self.y[order]
        bounds = np.flatnonzero(nid[1:] != nid[:-1]) + 1
        for a, b in zip(np.r_[0, bounds], np.r_[bounds, len(nid)]):
            e = ep[a:b] - ep[a:b].min()
            seq = np.full(int(e.max()) + 1, -1, dtype=np.int8)
            seq[e] = y[a:b]
            out[str(nid[a])] = seq
        return out

    def nights(self) -> list[str]:
        return sorted(set(self.night_id.tolist()))


def _parse_bool(values: pd.Series) -> np.ndarray:
    s = values.astype(str).str.strip().str.lower()
    bad = ~(s.isin(_TRUE) | s.isin(_FALSE) | s.eq("nan"))
    if bad.any():
        raise FeatureFileError(f"has_data: unexpected values {sorted(set(s[bad]))[:5]}")
    return s.isin(_TRUE).to_numpy()


def _parse_labels(values: pd.Series) -> np.ndarray:
    s = values.fillna("").astype(str).str.strip().str.upper()
    y = np.full(len(s), -1, dtype=np.int8)
    for name, idx in STAGE_INDEX.items():
        y[(s == name).to_numpy()] = idx
    known = s.isin(list(STAGE_INDEX) + ["", "UNKNOWN", "NAN"])
    if not known.all():
        raise FeatureFileError(f"label: unexpected values {sorted(set(s[~known]))[:5]}")
    return y


def _read_leading_comments(path: Path) -> tuple[dict[str, str], int]:
    meta: dict[str, str] = {}
    n = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.startswith("#"):
                break
            n += 1
            m = re.match(r"#\s*([A-Za-z_][\w.-]*)\s*[=:]\s*(.*?)\s*$", line)
            if m:
                meta[m.group(1)] = m.group(2)
    return meta, n


def sidecar_paths(path: Path) -> list[Path]:
    path = Path(path)
    return [Path(str(path) + ".meta.json"), path.with_suffix(".meta.json")]


def read_sidecar(path: Path) -> dict:
    for p in sidecar_paths(path):
        if p.is_file():
            return json.loads(p.read_text(encoding="utf-8"))
    return {}


def infer_epoch_min(night_id: np.ndarray, start_ms: np.ndarray, default: float = 1.0) -> float:
    if len(start_ms) < 2:
        return default
    order = np.lexsort((start_ms, night_id))
    nid, t = night_id[order], start_ms[order]
    same = nid[1:] == nid[:-1]
    d = np.diff(t)[same]
    d = d[d > 0]
    if d.size == 0:
        return default
    vals, counts = np.unique(d, return_counts=True)
    return float(vals[np.argmax(counts)]) / 60_000.0


def split_header(columns: Sequence[str], path: Path | str = "features CSV") -> tuple[list[str], list[str]]:
    """(hybrid feature names, trailing base columns) of the header after the meta columns: the trailing block is
    exactly the BASE_FEATURES (in order) that are not among the hybrid names (docs/INTERCHANGE.md section 2)."""
    cols = list(columns)
    for k in range(min(len(BASE_FEATURES), len(cols) - 1), -1, -1):
        names, trailing = cols[: len(cols) - k], cols[len(cols) - k:]
        if trailing == [b for b in BASE_FEATURES if b not in names]:
            if len(set(names)) != len(names):
                raise FeatureFileError(f"{path}: duplicate hybrid feature names {names}")
            return names, trailing
    raise FeatureFileError(f"{path}: the header must end with the base features {list(BASE_FEATURES)} that are not "
                           f"hybrid features, header ends with {cols[-len(BASE_FEATURES):]}")


def read_features(path: Path, feature_set_version: int | None = None, variant: str | None = None) -> FeatureTable:
    path = Path(path)
    if not path.is_file():
        raise FeatureFileError(f"features CSV not found: {path}")
    comments, skip = _read_leading_comments(path)
    with open(path, encoding="utf-8", newline="") as f:
        for _ in range(skip):
            f.readline()
        header = next(csv.reader([f.readline()]), [])
    header = [h.strip() for h in header]
    nm = len(META_COLUMNS)
    if tuple(header[:nm]) != META_COLUMNS:
        raise FeatureFileError(f"{path}: the first columns must be {list(META_COLUMNS)}, header is {header[:nm + 3]}")
    names, trailing = split_header(header[nm:], path)
    nb = len(trailing)
    cols = list(META_COLUMNS) + [f"f{i}" for i in range(len(names))] + [f"b{i}" for i in range(nb)]
    df = pd.read_csv(path, skiprows=skip + 1, header=None, names=cols, dtype={"night_id": str, "subject": str,
                     "label": str, "has_data": str}, keep_default_na=True, na_values=["NaN", "nan", ""])
    if df.empty:
        raise FeatureFileError(f"{path}: no rows")
    sidecar = read_sidecar(path)
    fsv = feature_set_version
    if fsv is None and "featureSetVersion" in sidecar:
        fsv = int(sidecar["featureSetVersion"])
    if fsv is None and "featureSetVersion" in comments:
        fsv = int(comments["featureSetVersion"])
    if sidecar.get("featureNames") is not None and list(sidecar["featureNames"]) != names:
        raise FeatureFileError(f"{path}: header feature names differ from the sidecar's featureNames")
    X = df[[f"f{i}" for i in range(len(names))]].to_numpy(dtype=np.float64)
    base = np.column_stack([df[f"b{trailing.index(b)}"].to_numpy(dtype=np.float64) if b in trailing
                            else X[:, names.index(b)] for b in BASE_FEATURES])
    night_id = df["night_id"].to_numpy(dtype=object)
    start_ms = df["start_ms"].to_numpy(dtype=np.int64)
    t = FeatureTable(
        night_id=night_id, subject=df["subject"].to_numpy(dtype=object),
        epoch=df["epoch"].to_numpy(dtype=np.int64), start_ms=start_ms,
        has_data=_parse_bool(df["has_data"]), y=_parse_labels(df["label"]), X=X, base=base,
        feature_names=names, feature_set_version=fsv, epoch_min=infer_epoch_min(night_id, start_ms),
        variant=None if variant is None else np.full(len(df), variant, dtype=object),
        meta={"source": str(path), "comments": comments, "sidecar": sidecar},
    )
    return t


def concat(tables: Sequence[FeatureTable]) -> FeatureTable:
    """Row-wise concatenation (same feature names, version and epoch length required)."""
    if not tables:
        raise ValueError("nothing to concatenate")
    first = tables[0]
    for t in tables[1:]:
        if t.feature_names != first.feature_names:
            raise FeatureFileError("feature tables have different feature columns")
        if t.feature_set_version != first.feature_set_version:
            raise FeatureFileError("feature tables have different featureSetVersion")
        if abs(t.epoch_min - first.epoch_min) > 1e-9:
            raise FeatureFileError("feature tables have different epoch lengths")
    if len(tables) == 1:
        return first
    var = None
    if all(t.variant is not None for t in tables):
        var = np.concatenate([t.variant for t in tables])
    return FeatureTable(
        night_id=np.concatenate([t.night_id for t in tables]), subject=np.concatenate([t.subject for t in tables]),
        epoch=np.concatenate([t.epoch for t in tables]), start_ms=np.concatenate([t.start_ms for t in tables]),
        has_data=np.concatenate([t.has_data for t in tables]), y=np.concatenate([t.y for t in tables]),
        X=np.vstack([t.X for t in tables]), base=np.vstack([t.base for t in tables]),
        feature_names=list(first.feature_names), feature_set_version=first.feature_set_version,
        epoch_min=first.epoch_min, variant=var, meta={"sources": [t.meta.get("source") for t in tables]},
    )


def step_feature_names(names: Sequence[str], pattern: str = r"(?i)step") -> list[str]:
    """Hybrid features derived from steps (masked / imputed when a dataset has no step channel)."""
    rx = re.compile(pattern)
    return [n for n in names if rx.search(n)]
