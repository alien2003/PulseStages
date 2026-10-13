"""Small shared helpers: stage order, JSON I/O (deterministic, NaN-free), deep merge of AlgoParams patches."""

from __future__ import annotations

import json
import math
import os
from pathlib import Path
from typing import Any

# SleepStage.index order of :core-algo (AWAKE=0, LIGHT=1, DEEP=2, REM=3).
STAGES: tuple[str, ...] = ("AWAKE", "LIGHT", "DEEP", "REM")
STAGE_INDEX: dict[str, int] = {s: i for i, s in enumerate(STAGES)}
N_STAGES = len(STAGES)
AWAKE, LIGHT, DEEP, REM = range(N_STAGES)

# Lower-case stage keys used by the AlgoParams JSON (EmissionTable fields).
PARAM_STAGE_KEYS: tuple[str, ...] = ("awake", "light", "deep", "rem")


def deep_merge(base: dict, patch: dict) -> dict:
    """Recursive merge of JSON objects; values of ``patch`` win, nested objects are merged, lists are replaced."""
    out = dict(base)
    for k, v in patch.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def check_finite(obj: Any, where: str = "$") -> None:
    """Raises ValueError if a NaN / infinity is anywhere in ``obj`` (Kotlin's JSON parser rejects them)."""
    if isinstance(obj, float):
        if not math.isfinite(obj):
            raise ValueError(f"non-finite number at {where}")
    elif isinstance(obj, dict):
        for k, v in obj.items():
            check_finite(v, f"{where}.{k}")
    elif isinstance(obj, (list, tuple)):
        for i, v in enumerate(obj):
            check_finite(v, f"{where}[{i}]")


def canonical_json(obj: Any) -> str:
    """Compact, key-sorted JSON (used to compare / group parameter patches)."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _is_scalar(v: Any) -> bool:
    return v is None or isinstance(v, (bool, int, float, str))


def dumps_pretty(obj: Any, indent: int = 2) -> str:
    """JSON with ``indent``-space nesting, but lists of scalars on one line (keeps weight matrices readable).

    Key order is preserved (callers build dicts in schema order). Floats use ``repr`` (round-trips exactly).
    """

    def enc(v: Any, level: int) -> str:
        pad = " " * (indent * (level + 1))
        end = " " * (indent * level)
        if isinstance(v, dict):
            if not v:
                return "{}"
            items = [f"{pad}{json.dumps(str(k))}: {enc(x, level + 1)}" for k, x in v.items()]
            return "{\n" + ",\n".join(items) + "\n" + end + "}"
        if isinstance(v, (list, tuple)):
            if not v:
                return "[]"
            if all(_is_scalar(x) for x in v):
                return "[" + ", ".join(json.dumps(x, allow_nan=False) for x in v) + "]"
            return "[\n" + ",\n".join(pad + enc(x, level + 1) for x in v) + "\n" + end + "]"
        return json.dumps(v, allow_nan=False)

    check_finite(obj)
    return enc(obj, 0) + "\n"


def write_json(path: Path, obj: Any, pretty: bool = True) -> Path:
    """Atomically writes ``obj`` as JSON (temporary file + rename)."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    text = dumps_pretty(obj) if pretty else json.dumps(obj, separators=(",", ":"), allow_nan=False) + "\n"
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)
    return path


def read_json(path: Path) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def to_float_list(a) -> list:
    """numpy array (any shape) -> nested lists of Python floats."""
    import numpy as np

    arr = np.asarray(a, dtype=float)
    return arr.tolist()
