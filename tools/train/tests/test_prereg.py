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


# ------------------------------------------------------------------------- dedicated nap model pre-registration ----

from pulsetrain import napmodel  # noqa: E402

NAP_MODEL_PREREG = REPO / napmodel.PREREG_PATH
NAP_MODEL_LOG = NAP_MODEL_PREREG.parent / "commands-nap-model.log"


def test_nap_model_prereg_sha256_matches_every_record():
    digest = hashlib.sha256(NAP_MODEL_PREREG.read_bytes()).hexdigest()
    assert digest == napmodel.PREREG_SHA256
    assert (NAP_MODEL_PREREG.parent / "prereg-nap-model.md.sha256").read_text().split() == [digest,
                                                                                          NAP_MODEL_PREREG.name]
    assert f"NOTE prereg-nap-model.md sha256 {digest}" in NAP_MODEL_LOG.read_text(encoding="utf-8")


def test_logged_nap_model_prereg_is_the_committed_text_and_precedes_every_run():
    log = NAP_MODEL_LOG.read_text(encoding="utf-8").splitlines()
    start = next(i for i, x in enumerate(log) if " PRE-REGISTRATION nap-model " in x)
    end = next(i for i, x in enumerate(log) if " NOTE prereg-nap-model.md sha256 " in x)
    logged = [x[2:] if x.startswith("  ") else x for x in log[start + 1:end]]
    assert logged == NAP_MODEL_PREREG.read_text(encoding="utf-8").splitlines()   # verbatim, timestamp included
    stamp = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)")
    times = [m.group(1) for x in log if (m := stamp.match(x))]
    assert times == sorted(times)                                                # append-only, in time order
    written = re.search(r"^Written (\S+),", NAP_MODEL_PREREG.read_text(encoding="utf-8"), re.M).group(1)
    assert log[start].startswith(written)
    runs = [i for i, x in enumerate(log) if " START " in x and "train.py nap-model" in x]
    assert all(start < end < i for i in runs)                                   # no run before the pre-registration


# ------------------------------------------------------------------- realistic nap estimates pre-registration ----

import pytest  # noqa: E402

from pulsetrain import ds006576, napsrealistic  # noqa: E402

NR_PREREG = REPO / napsrealistic.PREREG_PATH
NR_LOG = NR_PREREG.parent / "commands-naps-realistic.log"
NR_RUN = re.compile(r" START .*(\btrain\.py naps-realistic|\bexternal\.py |\bexternal_stage\.py)")


def _nr_log() -> list[str]:
    """The committed copy of tools/train/out/commands.log (made at the code commit; absent before it)."""
    if not NR_LOG.is_file():
        pytest.skip(f"{NR_LOG.name} is committed with the code commit (docs/prereg/prereg-naps-realistic.md "
                    "section 19)")
    return NR_LOG.read_text(encoding="utf-8").splitlines()


def test_naps_realistic_prereg_sha256_matches_the_code():
    digest = hashlib.sha256(NR_PREREG.read_bytes()).hexdigest()
    assert digest == napsrealistic.PREREG_SHA256 == ds006576.PREREG_SHA256
    assert (NR_PREREG.parent / "prereg-naps-realistic.md.sha256").read_text().split() == [digest, NR_PREREG.name]


def test_naps_realistic_prereg_sha256_is_logged():
    digest = hashlib.sha256(NR_PREREG.read_bytes()).hexdigest()
    assert any(f" NOTE prereg-naps-realistic.md sha256 {digest}" in x for x in _nr_log())


NR_GATES = re.compile(r" START .*\btrain\.py naps-realistic-(gates|timing)\b")


def _nr_block(log: list[str], head: str, digest: str) -> tuple[int, int, list[str]]:
    """The logged text between the line containing ``head`` and the NOTE line with ``digest``."""
    start = next(i for i, x in enumerate(log) if head in x)
    end = next(i for i, x in enumerate(log) if i > start and f" NOTE prereg-naps-realistic.md sha256 {digest}" in x)
    return start, end, [x[2:] if x.startswith("  ") else x for x in log[start + 1:end]]


def test_logged_naps_realistic_prereg_and_its_amendment_precede_every_run():
    """The original text (the pre-registration commit 52517a7) is logged verbatim before every run; Amendment 1 (the
    committed text) is logged verbatim after the reproduction-only gates and the featureless timing and before every
    other naps-realistic run and every ds006576 step (section 19 and 'Amendment 1' of the text)."""
    log = _nr_log()
    start, end, original = _nr_block(log, " PRE-REGISTRATION naps-realistic ", napsrealistic.ORIGINAL_PREREG_SHA256)
    original_text = "\n".join(original) + "\n"
    assert hashlib.sha256(original_text.encode("utf-8")).hexdigest() == napsrealistic.ORIGINAL_PREREG_SHA256
    a_start, a_end, amended = _nr_block(log, " AMENDMENT naps-realistic 1 ", napsrealistic.PREREG_SHA256)
    assert amended == NR_PREREG.read_text(encoding="utf-8").splitlines()       # verbatim, the committed text
    stamp = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)")
    times = [m.group(1) for x in log if (m := stamp.match(x))]
    assert times == sorted(times)                                               # append-only, in time order
    written = re.search(r"^Written (\S+),", NR_PREREG.read_text(encoding="utf-8"), re.M).group(1)
    assert log[start][:10] >= written[:10]
    runs = [i for i, x in enumerate(log) if stamp.match(x) and " START " in x[:40] and NR_RUN.search(x)]
    assert all(start < end < i for i in runs)          # no naps-realistic run and no ds006576 step before the text
    later = [i for i in runs if not NR_GATES.search(log[i])]
    assert all(end < a_start < a_end < i for i in later)    # only gates / timing before the amendment


def test_naps_realistic_test_set_freeze_order_of_the_committed_log():
    """Section 13.6: whenever the lines exist, exactly one ds006576 manifest NOTE and one yasa-summary NOTE, and the
    order pre-registration NOTE < fetch < staging < nights < the two NOTEs < the first START of train.py
    naps-realistic < its RESULT < the START of train.py naps-realistic-external."""
    rec = ds006576.freeze_order(_nr_log(), prereg_sha256=napsrealistic.PREREG_SHA256, complete=False)
    assert rec["ok"], rec["failures"]
