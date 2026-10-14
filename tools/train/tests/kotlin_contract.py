"""Read-only helpers that extract contract facts from the Kotlin sources / docs (field names, defaults)."""

from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ALGO_PARAMS = REPO / "core-algo" / "src" / "main" / "kotlin" / "io" / "github" / "alien2003" / "pulsestages" / \
    "algo" / "params" / "AlgoParams.kt"
NAP_MODE_PARAMS = ALGO_PARAMS.with_name("NapModeParams.kt")
INTERCHANGE = REPO / "docs" / "INTERCHANGE.md"


def data_class_fields(name: str) -> list[str]:
    src = ALGO_PARAMS.read_text(encoding="utf-8") + NAP_MODE_PARAMS.read_text(encoding="utf-8")
    m = re.search(r"data class " + re.escape(name) + r"\((.*?)\n\)", src, re.S)
    assert m, f"data class {name} not found in {ALGO_PARAMS}"
    return re.findall(r"\bval\s+(\w+)\s*:", m.group(1))


def data_class_defaults(name: str) -> dict[str, float]:
    src = ALGO_PARAMS.read_text(encoding="utf-8")
    m = re.search(r"data class " + re.escape(name) + r"\((.*?)\n\)", src, re.S)
    return {k: float(v) for k, v in re.findall(r"\bval\s+(\w+)\s*:\s*Double\s*=\s*([-\d.eE]+)", m.group(1))}


def physiology_table() -> dict:
    src = ALGO_PARAMS.read_text(encoding="utf-8")
    out = {}
    for stage, body in re.findall(r"(\w+) = StateEmission\((.*?)\),\n", src):
        g = [tuple(map(float, x)) for x in re.findall(r"Gaussian\(([-\d.]+), ([-\d.]+)\)", body)]
        out[stage] = {d: {"mean": m, "sd": s} for d, (m, s) in zip(("hrNorm", "hrVar", "hrSlope"), g)}
    return out


def bundle_doc_keys() -> set[str]:
    """All JSON keys shown in the bundle schema of INTERCHANGE.md section 3."""
    text = INTERCHANGE.read_text(encoding="utf-8")
    sec = text.split("## 3.", 1)[1]
    block = sec.split("```json", 1)[1].split("```", 1)[0]
    block = re.sub(r"//[^\n]*", "", block)
    block = re.sub(r"/\*.*?\*/", "", block, flags=re.S)
    return set(re.findall(r'"(\w+)"\s*:', block))
