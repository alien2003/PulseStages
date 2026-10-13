import numpy as np
import pytest

from pulsetrain.ohealth import (
    BIDSLEEP_CODE_TO_STAGE,
    WALCH_CODE_TO_STAGE,
    label_epochs,
    label_epochs_from_start,
    map_code,
    map_walch_code,
    ohealth_session,
    psg_session,
    scored_range,
    session_labels,
    stage_minutes,
)
from pulsetrain.bidsleep import parse_rec_start

MIN = 60_000


@pytest.mark.parametrize("code,stage", [
    (0, "AWAKE"), (1, "LIGHT"), (2, "LIGHT"), (3, "DEEP"), (4, "DEEP"), (5, "REM"),
    (-1, None), (6, None), (7, None), (2.5, None), (float("nan"), None),
])
def test_walch_label_mapping(code, stage):
    assert map_walch_code(code) == stage


@pytest.mark.parametrize("code,stage", [
    (0, "AWAKE"), (1, "LIGHT"), (2, "LIGHT"), (3, "DEEP"), (4, "REM"), (5, None), (-1, None),
])
def test_bidsleep_label_mapping(code, stage):
    """BIDSleep codes differ from Walch: 4 is REM, 5 is Unknown."""
    assert map_code(code, BIDSLEEP_CODE_TO_STAGE) == stage


def test_mappings_only_produce_sleepstage_names():
    names = {"AWAKE", "LIGHT", "DEEP", "REM"}
    assert set(WALCH_CODE_TO_STAGE.values()) == names
    assert set(BIDSLEEP_CODE_TO_STAGE.values()) == names


def _night(codes, t0_s=0.0):
    t = t0_s + 30.0 * np.arange(len(codes))
    return label_epochs(t, np.array(codes, dtype=float))


def test_label_epochs_are_30s_and_keep_seconds_origin():
    ep = _night([0, 1, -1], t0_s=-60.0)
    assert [(e.start_ms, e.end_ms, e.stage) for e in ep] == [
        (-60_000, -30_000, "AWAKE"), (-30_000, 0, "LIGHT"), (0, 30_000, None)]


def test_label_epochs_from_start_bidsleep_layout():
    ep = label_epochs_from_start(1_638_504_685_000, np.array([0, 4, 5, 3]), BIDSLEEP_CODE_TO_STAGE)
    assert [e.start_ms - 1_638_504_685_000 for e in ep] == [0, 30_000, 60_000, 90_000]
    assert [e.stage for e in ep] == ["AWAKE", "REM", None, "DEEP"]


def test_sessions_ohealth_and_psg():
    # 2 unscored, 30 wake (15 min), 100 sleep, 4 wake, 60 sleep, 40 wake (20 min), 3 unscored
    codes = [-1] * 2 + [0] * 30 + [2] * 100 + [0] * 4 + [5] * 60 + [0] * 40 + [-1] * 3
    ep = _night(codes)
    first_scored = 2 * 30_000
    last_scored_end = (2 + 30 + 100 + 4 + 60 + 40) * 30_000
    assert scored_range(ep) == (first_scored, last_scored_end)
    assert psg_session(ep) == (first_scored, last_scored_end)
    first_sleep = (2 + 30) * 30_000
    last_sleep_end = (2 + 30 + 100 + 4 + 60) * 30_000
    assert ohealth_session(ep) == (first_sleep - 5 * MIN, last_sleep_end + 5 * MIN)


def test_ohealth_session_is_clipped_to_scored_range():
    ep = _night([0] * 4 + [2] * 20 + [0] * 2)       # only 2 min of wake before / 1 min after sleep
    assert ohealth_session(ep) == (0, 26 * 30_000)


def test_no_sleep_means_no_ohealth_session():
    ep = _night([0] * 10 + [-1] * 3)
    assert ohealth_session(ep) is None
    assert psg_session(ep) == (0, 300_000)
    assert ohealth_session(_night([-1, -1])) is None and psg_session(_night([-1, -1])) is None


def test_session_labels_omit_unscored_and_outside_epochs():
    ep = _night([0, 0, 2, -1, 3, 5, 0, 0])
    labels = session_labels(ep, 30_000, 210_000)
    assert labels == [[30_000, 60_000, "AWAKE"], [60_000, 90_000, "LIGHT"], [120_000, 150_000, "DEEP"],
                      [150_000, 180_000, "REM"], [180_000, 210_000, "AWAKE"]]
    assert stage_minutes(labels) == {"AWAKE": 1.0, "LIGHT": 0.5, "DEEP": 0.5, "REM": 0.5}


def test_rec_start_eastern_time_and_dst_disambiguation():
    ms, note = parse_rec_start("2021-12-02 23:11:25", 1_638_504_593.03)
    assert ms == 1_638_504_685_000 and note is None
    # 2021-11-07 01:30 ET happens twice (EDT then EST); pick the one closest to the first HR sample
    edt = 1_636_263_000_000  # 01:30 EDT = 05:30 UTC
    est = edt + 3_600_000
    assert parse_rec_start("2021-11-07 01:30:00", est / 1000 - 60)[0] == est
    assert parse_rec_start("2021-11-07 01:30:00", edt / 1000 - 60)[0] == edt
    with pytest.raises(ValueError):
        parse_rec_start("02/12/2021 23:11", None)
