"""Perturbed copies of nap sessions (``docs/prereg/prereg-naps-realistic.md`` section 4.5; the authoritative
definitions, ported unchanged from the diagnosis of section 2.2 and restricted to the seven perturbations the
pre-registration names). Every function takes a session in the night-file format (docs/INTERCHANGE.md section 1) and
returns a new one; the input is never modified.

The session median m is the median of the heart-rate samples at or after s0 (a cut session has none after s1).

- ``noctx``: heart rate before s0 and step records ending <= s0 removed.
- ``ctxsyn+10``: the pre-context replaced by 15 samples every 2 min from s0 - 30 min at m + 10 bpm, -1 / +1 bpm
  alternating starting with -1; step records ending <= s0 removed.
- ``post+20``: samples every 2 min after the last sample up to s1 + 30 min, those after s1 kept, at m + 20 bpm, +1 / -1
  alternating (the k-th sample after the last one gets +1 for odd k).
- ``app+15steps``: ``ctxsyn`` and ``post`` at m + 15 bpm, with 2-min step records of 30 steps over [s0 - 30 min, s0]
  and over [s1, s1 + 30 min] (ending in (s1, s1 + 30 min]).
- ``step3mid``: one 1-min record of 3 steps starting at s0 + floor(L / 2) min.
- ``amp0.5``, ``amp1.5``: every sample h -> m + a (h - m).

A session without a heart-rate sample at or after s0 has no median: the median-based perturbations return an unchanged
copy (as the diagnosis did). :func:`perturbed_copy` gives the copy the id ``<original id>~<perturbation>`` and returns
metadata that names the original.
"""

from __future__ import annotations

import copy
from typing import Callable, Mapping

import numpy as np

MIN = 60_000
STEP_MS = 2 * MIN
CONTEXT_MS = 30 * MIN
SYN_SAMPLES = 15                       # 2-min samples / step records over the 30-min context
APP_STEPS = 30                         # steps per 2-min record of app+15steps
MID_STEPS = 3                          # steps of the step3mid record


def session_median(s: Mapping) -> float:
    """Median of the heart-rate samples at or after the session start (NaN without one)."""
    s0 = int(s["sessionStartMs"])
    v = [float(h[1]) for h in s["hr"] if int(h[0]) >= s0]
    return float(np.median(v)) if v else float("nan")


def _syn_pre(s0: int, level: float) -> list[list]:
    """15 samples at s0 - 30 min + 2k min (k = 0 ... 14), level - 1 for even k, level + 1 for odd k."""
    return [[s0 - CONTEXT_MS + k * STEP_MS, level + (1 if k % 2 else -1)] for k in range(SYN_SAMPLES)]


def _syn_post(s: Mapping, level: float) -> list[list]:
    """Samples at last + 2k min (k = 1, 2, ...) while <= s1 + 30 min, kept only after s1; level + 1 for odd k, level
    - 1 for even k (last = the last heart-rate sample, s1 without one)."""
    s1 = int(s["sessionEndMs"])
    last = max(int(h[0]) for h in s["hr"]) if s["hr"] else s1
    out, k = [], 1
    while (t := last + k * STEP_MS) <= s1 + CONTEXT_MS:
        if t > s1:
            out.append([t, level + (1 if k % 2 else -1)])
        k += 1
    return out


def no_ctx(s: Mapping) -> dict:
    """``noctx``: heart rate before s0 and step records ending <= s0 removed."""
    s = copy.deepcopy(dict(s))
    s0 = int(s["sessionStartMs"])
    s["hr"] = [h for h in s["hr"] if int(h[0]) >= s0]
    s["steps"] = [x for x in s.get("steps", []) if int(x[1]) > s0]
    return s


def ctx_syn(s: Mapping, d: float, steps: int = 0) -> dict:
    """The pre-context replaced by a flat level (session median + ``d``), step records ending <= s0 removed and, with
    ``steps``, 15 records of 2 min with ``steps`` steps each over [s0 - 30 min, s0]."""
    s = copy.deepcopy(dict(s))
    s0 = int(s["sessionStartMs"])
    m = session_median(s)
    if not np.isfinite(m):
        return s
    s["hr"] = _syn_pre(s0, m + d) + [h for h in s["hr"] if int(h[0]) >= s0]
    st = [x for x in s.get("steps", []) if int(x[1]) > s0]
    if steps:
        st = [[s0 - CONTEXT_MS + k * STEP_MS, s0 - CONTEXT_MS + (k + 1) * STEP_MS, steps]
              for k in range(SYN_SAMPLES)] + st
    s["steps"] = st
    return s


def post_syn(s: Mapping, d: float, steps: int = 0) -> dict:
    """30 min of post-session context at session median + ``d`` (:func:`_syn_post`) and, with ``steps``, 15 records
    of 2 min with ``steps`` steps each over [s1, s1 + 30 min]."""
    s = copy.deepcopy(dict(s))
    m = session_median(s)
    if not np.isfinite(m):
        return s
    s["hr"] = s["hr"] + _syn_post(s, m + d)
    if steps:
        s1 = int(s["sessionEndMs"])
        s["steps"] = list(s.get("steps", [])) + [[s1 + k * STEP_MS, s1 + (k + 1) * STEP_MS, steps]
                                                 for k in range(SYN_SAMPLES)]
    return s


def app_like(s: Mapping, d: float, steps: int = 0) -> dict:
    """App-like context on both sides: :func:`ctx_syn` then :func:`post_syn`, both at session median + ``d``."""
    return post_syn(ctx_syn(s, d, steps), d, steps)


def amp(s: Mapping, a: float) -> dict:
    """Every sample h -> m + a (h - m), m = the session median."""
    s = copy.deepcopy(dict(s))
    m = session_median(s)
    if not np.isfinite(m):
        return s
    s["hr"] = [[h[0], m + a * (float(h[1]) - m)] for h in s["hr"]]
    return s


def step_mid(s: Mapping, count: int = MID_STEPS) -> dict:
    """One 1-min step record of ``count`` steps starting at s0 + floor(L / 2) min (L = the session length in
    minutes)."""
    s = copy.deepcopy(dict(s))
    s0, s1 = int(s["sessionStartMs"]), int(s["sessionEndMs"])
    t = s0 + int(((s1 - s0) / MIN) // 2) * MIN
    s["steps"] = list(s.get("steps", [])) + [[t, t + MIN, count]]
    return s


PERTURBATIONS: dict[str, Callable[[Mapping], dict]] = {
    "noctx": no_ctx,
    "ctxsyn+10": lambda s: ctx_syn(s, 10),
    "post+20": lambda s: post_syn(s, 20),
    "app+15steps": lambda s: app_like(s, 15, steps=APP_STEPS),
    "step3mid": step_mid,
    "amp0.5": lambda s: amp(s, 0.5),
    "amp1.5": lambda s: amp(s, 1.5),
}
NAMES: tuple[str, ...] = tuple(PERTURBATIONS)
SEPARATOR = "~"


def perturbed_id(original_id: str, name: str) -> str:
    return f"{original_id}{SEPARATOR}{name}"


def perturbed_copy(session: Mapping, name: str, meta: Mapping | None = None) -> tuple[dict, dict]:
    """(copy, meta) of ``session`` under perturbation ``name``: id ``<original id>~<name>``; meta = ``meta`` with
    ``original`` (the original id), ``perturbation`` and ``kind`` = ``perturbed`` (the original kind kept as
    ``originalKind``)."""
    if name not in PERTURBATIONS:
        raise KeyError(f"unknown perturbation {name!r}; known: {list(PERTURBATIONS)}")
    out = PERTURBATIONS[name](session)
    out["id"] = perturbed_id(str(session["id"]), name)
    m = dict(meta or {})
    m.update({"original": str(session["id"]), "perturbation": name, "originalKind": m.get("kind"),
              "kind": "perturbed"})
    return out, m


def original_of(session_id: str) -> tuple[str, str | None]:
    """(original id, perturbation) of a session id (perturbation None for an unperturbed id)."""
    if SEPARATOR not in session_id:
        return session_id, None
    base, name = session_id.rsplit(SEPARATOR, 1)
    return base, name
