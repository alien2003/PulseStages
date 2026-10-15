"""Perturbations of the realistic-nap pre-registration (pulsetrain/perturb.py, section 4.5): each prose example of the
pre-registration asserted against the code, on a synthetic cut session."""

import copy

import numpy as np
import pytest

from pulsetrain import perturb, pools
from synthnights import night_from_spec

MIN = 60_000


def _session(length=60):
    """A cut onset session: heart rate every 2 min from s0 - 30 min to s1 with a varying level, steps before and
    inside the session."""
    n = night_from_spec([("AWAKE", 5), ("LIGHT", 100), ("DEEP", 100)],
                        steps=[[3_600_000 - 40 * MIN, 3_600_000 - 39 * MIN, 5],
                               [3_600_000 - 2 * MIN, 3_600_000 - MIN, 4], [3_600_000 - MIN, 3_600_000, 2],
                               [3_600_000 + 10 * MIN, 3_600_000 + 11 * MIN, 7]])
    n["hr"] = [[h[0], 50.0 + (h[0] // 120_000) % 7] for h in n["hr"]]
    s, _ = pools.onset(n, (length,))[0]
    return s


def _median(s):
    return float(np.median([h[1] for h in s["hr"] if h[0] >= s["sessionStartMs"]]))


def test_noctx_removes_the_heart_rate_before_s0_and_the_steps_ending_at_or_before_s0():
    s = _session()
    s0 = s["sessionStartMs"]
    p = perturb.no_ctx(s)
    assert p["hr"] == [h for h in s["hr"] if h[0] >= s0] and min(h[0] for h in p["hr"]) >= s0
    assert p["steps"] == [[s0 + 10 * MIN, s0 + 11 * MIN, 7]]                 # the record ending exactly at s0 removed
    assert any(st[1] == s0 for st in s["steps"])


def test_ctxsyn10_replaces_the_pre_context_by_15_alternating_samples_starting_with_minus_one():
    s = _session()
    s0, m = s["sessionStartMs"], _median(s)
    p = perturb.PERTURBATIONS["ctxsyn+10"](s)
    pre = [h for h in p["hr"] if h[0] < s0]
    assert [h[0] for h in pre] == [s0 - 30 * MIN + 2 * k * MIN for k in range(15)]
    assert [h[1] for h in pre] == [m + 10 + (1 if k % 2 else -1) for k in range(15)]
    assert pre[0][1] == m + 10 - 1
    assert [h for h in p["hr"] if h[0] >= s0] == [h for h in s["hr"] if h[0] >= s0]
    assert all(st[1] > s0 for st in p["steps"]) and len(p["steps"]) == 1


def test_post20_adds_samples_every_2_min_after_the_last_one_up_to_s1_plus_30_min():
    s = _session()
    s1, m = s["sessionEndMs"], _median(s)
    last = max(h[0] for h in s["hr"])
    p = perturb.PERTURBATIONS["post+20"](s)
    assert p["hr"][: len(s["hr"])] == s["hr"]
    post = p["hr"][len(s["hr"]):]
    assert all(h[0] > s1 for h in post) and post[-1][0] <= s1 + 30 * MIN < post[-1][0] + 2 * MIN
    k0 = (post[0][0] - last) // (2 * MIN)
    for i, h in enumerate(post):
        k = k0 + i
        assert h[0] == last + k * 2 * MIN and h[1] == m + 20 + (1 if k % 2 else -1)
    # with the last sample exactly at s1 the first added sample is s1 + 2 min with +1
    assert last == s1 and post[0] == [s1 + 2 * MIN, m + 21]
    # the samples between the last one and s1 are not added: only those after s1 are kept
    s2 = copy.deepcopy(s)
    s2["hr"] = [h for h in s2["hr"] if h[0] <= s1 - 5 * MIN]
    q = perturb.PERTURBATIONS["post+20"](s2)
    assert min(h[0] for h in q["hr"][len(s2["hr"]):]) > s1


def test_app15steps_has_context_on_both_sides_with_30_step_records():
    s = _session()
    s0, s1, m = s["sessionStartMs"], s["sessionEndMs"], _median(s)
    p = perturb.PERTURBATIONS["app+15steps"](s)
    pre = [h for h in p["hr"] if h[0] < s0]
    post = [h for h in p["hr"] if h[0] > s1]
    assert [h[1] for h in pre] == [m + 15 + (1 if k % 2 else -1) for k in range(15)]
    assert all(abs(h[1] - (m + 15)) == 1 for h in post) and len(post) == 15
    before = [st for st in p["steps"] if st[1] <= s0]
    after = [st for st in p["steps"] if st[1] > s1]
    assert before == [[s0 - 30 * MIN + 2 * k * MIN, s0 - 30 * MIN + 2 * (k + 1) * MIN, 30] for k in range(15)]
    assert after == [[s1 + 2 * k * MIN, s1 + 2 * (k + 1) * MIN, 30] for k in range(15)]
    assert before[0][0] == s0 - 30 * MIN and before[-1][1] == s0                       # [s0 - 30 min, s0]
    assert after[0][1] > s1 and after[-1][1] == s1 + 30 * MIN                          # ending in (s1, s1 + 30 min]
    assert [st for st in p["steps"] if s0 < st[1] <= s1] == [[s0 + 10 * MIN, s0 + 11 * MIN, 7]]


def test_step3mid_adds_one_1_min_record_of_3_steps_at_half_the_length():
    for length, half in ((60, 30), (45, 22), (90, 45)):
        s = _session(length)
        p = perturb.PERTURBATIONS["step3mid"](s)
        s0 = s["sessionStartMs"]
        assert p["steps"] == s["steps"] + [[s0 + half * MIN, s0 + (half + 1) * MIN, 3]]
        assert p["hr"] == s["hr"]


@pytest.mark.parametrize("name,a", [("amp0.5", 0.5), ("amp1.5", 1.5)])
def test_amplitude_scales_every_sample_around_the_session_median(name, a):
    s = _session()
    m = _median(s)
    p = perturb.PERTURBATIONS[name](s)
    assert [h[0] for h in p["hr"]] == [h[0] for h in s["hr"]]
    assert [h[1] for h in p["hr"]] == pytest.approx([m + a * (h[1] - m) for h in s["hr"]])
    assert any(h[0] < s["sessionStartMs"] and h[1] != h0[1] for h, h0 in zip(p["hr"], s["hr"]))   # context too


def test_the_session_median_uses_the_samples_at_or_after_s0():
    s = {"sessionStartMs": 100, "sessionEndMs": 200, "hr": [[0, 10.0], [99, 1000.0], [100, 60.0], [150, 64.0]]}
    assert perturb.session_median(s) == 62.0
    assert np.isnan(perturb.session_median({"sessionStartMs": 100, "hr": [[50, 60.0]]}))


def test_median_based_perturbations_leave_a_session_without_session_samples_unchanged():
    s = _session()
    s["hr"] = [h for h in s["hr"] if h[0] < s["sessionStartMs"]]
    for name in ("ctxsyn+10", "post+20", "app+15steps", "amp0.5"):
        assert perturb.PERTURBATIONS[name](s) == s


def test_perturbed_copies_have_unique_ids_that_map_back_and_never_modify_the_input():
    s = _session()
    frozen = copy.deepcopy(s)
    copies = [perturb.perturbed_copy(s, name, {"kind": "eval", "pool": "O"}) for name in perturb.NAMES]
    assert s == frozen
    assert perturb.NAMES == ("noctx", "ctxsyn+10", "post+20", "app+15steps", "step3mid", "amp0.5", "amp1.5")
    ids = [c["id"] for c, _ in copies]
    assert ids == [f"{s['id']}~{n}" for n in perturb.NAMES]
    pools.assert_unique_ids([s] + [c for c, _ in copies])
    for (c, m), name in zip(copies, perturb.NAMES):
        assert m["original"] == s["id"] and m["perturbation"] == name and m["kind"] == "perturbed"
        assert m["originalKind"] == "eval" and m["pool"] == "O"
        assert perturb.original_of(c["id"]) == (s["id"], name)
    assert perturb.original_of(s["id"]) == (s["id"], None)
    with pytest.raises(KeyError):
        perturb.perturbed_copy(s, "ctx+10")
