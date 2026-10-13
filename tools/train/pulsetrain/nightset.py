"""Index of the prepared night files (docs/INTERCHANGE.md section 1) and subset files for algo-cli.

Dataset of a night: ``summary.csv`` (column ``dataset``) when present, else the night-id prefix (``walch-`` ->
walch, ``bid-`` -> bidsleep). Step channel: a night has one when its ``summary.csv`` row has a non-empty
``steps_file`` (prepare.py leaves it empty when the accelerometer was not available); without summary.csv, nights
of ``no_steps_datasets`` have none. Subjects are the LOSO grouping key and must be unique across datasets.
"""

from __future__ import annotations

import csv
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

PREFIX_DATASET = {"walch": "walch", "bid": "bidsleep"}
SAFE_SUBJECT = re.compile(r"^[A-Za-z0-9._-]+$")


@dataclass(frozen=True)
class NightRef:
    id: str
    subject: str
    dataset: str
    variant: str
    path: Path
    has_steps: bool


def dataset_from_id(night_id: str) -> str:
    prefix = night_id.split("-", 1)[0].lower()
    return PREFIX_DATASET.get(prefix, prefix)


def read_summary(path: Path) -> dict[str, dict]:
    if not Path(path).is_file():
        return {}
    with open(path, encoding="utf-8", newline="") as f:
        return {r["night_id"]: r for r in csv.DictReader(f) if r.get("night_id")}


class NightIndex:
    def __init__(self, nights: Sequence[NightRef]):
        self.nights = sorted(nights, key=lambda n: (n.variant, n.id))
        by_id: dict[tuple[str, str], NightRef] = {}
        subj_ds: dict[str, str] = {}
        for n in self.nights:
            if (n.variant, n.id) in by_id:
                raise ValueError(f"duplicate night id {n.id} in variant {n.variant}")
            by_id[(n.variant, n.id)] = n
            if subj_ds.setdefault(n.subject, n.dataset) != n.dataset:
                raise ValueError(f"subject {n.subject} appears in datasets {subj_ds[n.subject]} and {n.dataset}")
            if not SAFE_SUBJECT.match(n.subject):
                raise ValueError(f"subject id {n.subject!r} is not usable as a fold-model file name")
        self._by_id = by_id
        self._subject_dataset = subj_ds
        self._night_meta = {n.id: n for n in self.nights}

    @property
    def variants(self) -> list[str]:
        return sorted({n.variant for n in self.nights})

    def for_variant(self, variant: str) -> list[NightRef]:
        return [n for n in self.nights if n.variant == variant]

    def get(self, variant: str, night_id: str) -> NightRef:
        return self._by_id[(variant, night_id)]

    def night(self, night_id: str) -> NightRef:
        """Any variant's entry for ``night_id`` (ids are unique per variant by construction)."""
        return self._night_meta[night_id]

    def has_night(self, night_id: str) -> bool:
        return night_id in self._night_meta

    def dataset_of(self, subject: str) -> str:
        return self._subject_dataset[subject]

    def subjects(self, datasets: Iterable[str] | None = None, variant: str | None = None) -> list[str]:
        ds = None if datasets is None else set(datasets)
        return sorted({n.subject for n in self.nights if (ds is None or n.dataset in ds)
                       and (variant is None or n.variant == variant)})

    def datasets(self) -> list[str]:
        return sorted(set(self._subject_dataset.values()))

    def nights_of(self, variant: str, subjects: Iterable[str]) -> list[NightRef]:
        s = set(subjects)
        return [n for n in self.for_variant(variant) if n.subject in s]

    def write_subset(self, variant: str, subjects: Iterable[str], out: Path) -> Path:
        """JSONL (one compact night object per line) with the nights of ``subjects`` in ``variant``."""
        nights = self.nights_of(variant, subjects)
        if not nights:
            raise ValueError(f"no nights of variant {variant} for subjects {sorted(set(subjects))[:5]}")
        out = Path(out)
        out.parent.mkdir(parents=True, exist_ok=True)
        tmp = out.with_name(out.name + ".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            for n in nights:
                obj = json.loads(n.path.read_text(encoding="utf-8"))
                f.write(json.dumps(obj, separators=(",", ":")) + "\n")
        tmp.replace(out)
        return out


def load_index(nights_root: Path, variants: Sequence[str], summary_csv: Path | None = None,
               no_steps_datasets: Sequence[str] = ("bidsleep",)) -> NightIndex:
    nights_root = Path(nights_root)
    summary = read_summary(summary_csv if summary_csv is not None else nights_root / "summary.csv")
    refs: list[NightRef] = []
    for v in variants:
        d = nights_root / v
        files = sorted(d.glob("*.night.json"))
        if not files:
            raise FileNotFoundError(f"no *.night.json files in {d}")
        for p in files:
            obj = json.loads(p.read_text(encoding="utf-8"))
            nid, subject = str(obj["id"]), str(obj["subject"])
            row = summary.get(nid)
            dataset = row["dataset"] if row and row.get("dataset") else dataset_from_id(nid)
            if row is not None and "steps_file" in row:
                has_steps = str(row["steps_file"]).strip() != ""
            else:
                has_steps = dataset not in set(no_steps_datasets)
            refs.append(NightRef(id=nid, subject=subject, dataset=dataset, variant=v, path=p, has_steps=has_steps))
    return NightIndex(refs)
