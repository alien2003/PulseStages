"""The committed pre-registration v3 (docs/prereg/) is the text that was logged before the experiment: its SHA-256
matches the one recorded in the commands log, in the .sha256 file and in pulsetrain.bias, and the logged copy differs
from it only in the 'Written' timestamp (as the 09:01:36 NOTE of the log states)."""

import hashlib
import re
from pathlib import Path

from pulsetrain import bias

REPO = Path(__file__).resolve().parents[3]
PREREG = REPO / bias.PREREG_PATH
LOG = PREREG.parent / "commands-train2.log"


def test_prereg_sha256_matches_every_record():
    digest = hashlib.sha256(PREREG.read_bytes()).hexdigest()
    assert digest == bias.PREREG_SHA256
    assert (PREREG.parent / "prereg-bias.md.sha256").read_text().split() == [digest, PREREG.name]
    assert f"NOTE prereg-bias.md sha256 {digest}" in LOG.read_text(encoding="utf-8")


def test_logged_prereg_equals_the_committed_text_and_precedes_the_runs():
    log = LOG.read_text(encoding="utf-8").splitlines()
    start = next(i for i, x in enumerate(log) if " PRE-REGISTRATION bias " in x)
    end = next(i for i, x in enumerate(log) if " NOTE prereg-bias.md sha256 " in x)
    logged = [x[2:] if x.startswith("  ") else x for x in log[start + 1:end]]
    text = PREREG.read_text(encoding="utf-8").splitlines()
    assert len(logged) == len(text)
    diff = [(a, b) for a, b in zip(text, logged) if a != b]
    assert len(diff) == 1 and all(x.startswith("Written 2026-10-13T09:01:3") for x in diff[0])
    stamp = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)")
    times = [m.group(1) for x in log if (m := stamp.match(x))]
    assert times == sorted(times)                                 # append-only, in time order
    first_bias = next(i for i, x in enumerate(log) if " START " in x and "train.py bias " in x)
    assert start < end < first_bias


# ------------------------------------------------------------------------------- nap pre-registration ----

from pulsetrain import naps  # noqa: E402

NAPS_PREREG = REPO / naps.PREREG_PATH
NAPS_LOG = NAPS_PREREG.parent / "commands-naps.log"


def test_naps_prereg_sha256_matches_every_record():
    digest = hashlib.sha256(NAPS_PREREG.read_bytes()).hexdigest()
    assert digest == naps.PREREG_SHA256
    assert (NAPS_PREREG.parent / "prereg-naps.md.sha256").read_text().split() == [digest, NAPS_PREREG.name]
    assert f"NOTE prereg-naps.md sha256 {digest}" in NAPS_LOG.read_text(encoding="utf-8")


def test_logged_naps_prereg_is_the_committed_text_and_precedes_every_run():
    log = NAPS_LOG.read_text(encoding="utf-8").splitlines()
    start = next(i for i, x in enumerate(log) if " PRE-REGISTRATION naps " in x)
    end = next(i for i, x in enumerate(log) if " NOTE prereg-naps.md sha256 " in x)
    logged = [x[2:] if x.startswith("  ") else x for x in log[start + 1:end]]
    assert logged == NAPS_PREREG.read_text(encoding="utf-8").splitlines()       # verbatim, timestamp included
    stamp = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)")
    times = [m.group(1) for x in log if (m := stamp.match(x))]
    assert times == sorted(times)                                               # append-only, in time order
    written = re.search(r"^Written (\S+),", NAPS_PREREG.read_text(encoding="utf-8"), re.M).group(1)
    assert log[start].startswith(written)
    first_run = next(i for i, x in enumerate(log) if " START " in x and "train.py naps " in x)
    assert start < end < first_run
