"""The merge-gap experiment (pulsetrain/mergegap.py, docs/prereg/prereg-merge-gap.md) on synthetic data only: the
grouping of every arm and its fixture (gate G4), the hosts and constructs with their worked examples (sections 5.1-5.5;
gate G3), the cut, the per-epoch parser and the chunk scoring (gate G5, with the fake algo-cli), the statistics of
section 6.2, the estimator rule, the decode blocks (the positive controls decoded as nights), V3, V4, U1, every case of
the decision table of section 11, the phase-A build check and G2a's comparison of the existing test sources (in a
scratch git repository). No real night, no model and no Gradle build are used."""

import csv
import hashlib
import itertools
import json
import math
import stat
from collections import Counter

import numpy as np
import pytest

from pulsetrain import mergegap as mg
from pulsetrain import napmodel, report
from pulsetrain.algocli import AlgoCli

MIN = 60_000
T0 = 1_600_000_000_000

# the synthetic night of these tests: (stage, minutes) from t0; 9 host bouts, one of them starting 30 s off the minute
SPEC = [("AWAKE", 5), ("LIGHT", 15.5), ("AWAKE", 3), ("DEEP", 31.5), ("AWAKE", 4), ("LIGHT", 36), ("AWAKE", 2.5),
        ("REM", 37.5), ("AWAKE", 5), ("LIGHT", 100), ("AWAKE", 10), ("DEEP", 90), ("AWAKE", 6), ("REM", 34),
        ("AWAKE", 3), ("LIGHT", 37), ("AWAKE", 2), ("LIGHT", 33), ("AWAKE", 2), ("LIGHT", 18), ("AWAKE", 5)]


def labels_of(spec, start):
    out, t = [], start
    for st, m in spec:
        for _ in range(int(round(m * 2))):
            out.append([t, t + 30_000, st])
            t += 30_000
    return out


def make_night(variant="instantaneous", subject="7", key="walch-7", offset=0.0, start=T0, spec=SPEC, steps=None,
               presleep=True):
    labels = labels_of(spec, start)
    t1 = labels[-1][1]
    hr = []
    for k, t in enumerate(range(start - 30 * MIN, t1 + 30 * MIN + 1, 2 * MIN)):
        if not presleep and start - 30 * MIN <= t < start:
            continue
        hr.append([t, float(55 + (k % 7) + offset)])
    suffix = "inst" if variant == "instantaneous" else "mean2min"
    return {"id": f"{key}-{suffix}", "subject": subject, "variant": variant, "sessionStartMs": start,
            "sessionEndMs": t1, "hr": hr, "steps": list(steps if steps is not None else
                                                         [[start + MIN, start + 2 * MIN, 10],
                                                          [start + 242 * MIN, start + 243 * MIN, 12]]),
            "labels": labels}


@pytest.fixture
def night():
    return make_night()


@pytest.fixture
def twins():
    return {"instantaneous": make_night("instantaneous"), "mean2min": make_night("mean2min", offset=0.5)}


# ---------------------------------------------------------------------------------------------- grouping ----

def _s(u, a, b):
    return {"uuid": u, "startMs": a, "endMs": b}


def test_group_strict_threshold_and_forced_gap():
    for arm, gap, joined in (("A", 30 * MIN - 1, True), ("A", 30 * MIN, False), ("B", 60 * MIN - 1, True),
                             ("B", 60 * MIN, False), ("R45", 45 * MIN - 1, True), ("R90", 90 * MIN - 1, True)):
        r = mg.group([_s("a", 0, 240 * MIN), _s("b", 240 * MIN + gap, 270 * MIN + gap)], arm)
        assert (len(r["groups"]) == 1) is joined, (arm, gap)
        if joined:
            assert r["groups"][0]["forcedAwake"] == ([[240 * MIN, 240 * MIN + gap]] if gap else [])
            assert r["groups"][0]["mode"] == "NIGHT"


def test_group_arm_c_uses_covered_time_not_span_and_union_not_sum():
    # a night by span (185 min) whose covered time is 160 min: C keeps 30 min
    r = mg.group([_s("a", 0, 80 * MIN), _s("b", 105 * MIN, 185 * MIN), _s("c", 230 * MIN, 260 * MIN)], "C")
    assert [g["uuids"] for g in r["groups"]] == [["a", "b"], ["c"]]
    assert r["groups"][0]["coveredMs"] == 160 * MIN and r["groups"][0]["mode"] == "NIGHT"
    assert r["thresholds"] == {"a": 30 * MIN, "b": 30 * MIN, "c": 30 * MIN}
    # overlapping sessions: the union (170 min) counts, not the sum (245 min)
    r = mg.group([_s("a", 0, 100 * MIN), _s("b", 20 * MIN, 90 * MIN), _s("c", 95 * MIN, 170 * MIN),
                  _s("d", 215 * MIN, 245 * MIN)], "C")
    assert r["groups"][0]["coveredMs"] == 170 * MIN and len(r["groups"]) == 2
    # K exactly H joins, 1 ms below does not
    assert len(mg.group([_s("a", 0, 180 * MIN), _s("b", 225 * MIN, 255 * MIN)], "C")["groups"]) == 1
    assert len(mg.group([_s("a", 0, 180 * MIN - 1), _s("b", 225 * MIN - 1, 255 * MIN)], "C")["groups"]) == 2
    # chains: K reaches H after the second session, the third joins at 50 min
    r = mg.group([_s("a", 0, 150 * MIN), _s("b", 170 * MIN, 200 * MIN), _s("c", 250 * MIN, 300 * MIN)], "C")
    assert len(r["groups"]) == 1 and r["thresholds"]["a"] == 60 * MIN


def test_group_h_falls_back_to_180_min_when_naps_are_disabled():
    r = mg.group([_s("a", 0, 120 * MIN), _s("b", 165 * MIN, 195 * MIN)], "C", nap_max_session_min=0.0)
    assert len(r["groups"]) == 2 and r["groups"][0]["mode"] == "NIGHT"          # no naps: every kept group a night
    assert mg.covered_threshold_ms(0.0) == mg.covered_threshold_ms(None) == 180 * MIN
    assert mg.covered_threshold_ms(120.0) == 120 * MIN
    assert mg.minutes_to_whole_ms(0.5000001) == 30_001


def test_group_modes_order_and_duplicates():
    r = mg.group([_s("b", 0, 200 * MIN), _s("c", 0, 100 * MIN), _s("a", 0, 100 * MIN), _s("zero", 50, 50),
                  _s("c", 300 * MIN, 310 * MIN)], "A")
    assert [g["uuids"] for g in r["groups"]] == [["a", "b", "c"]]               # the first "c" kept, zero dropped
    assert [g["mode"] for g in mg.group([_s("x", 0, 90 * MIN - 1)], "A")["groups"]] == ["IGNORED"]
    assert [g["mode"] for g in mg.group([_s("x", 0, 90 * MIN)], "A")["groups"]] == ["NAP"]
    assert [g["mode"] for g in mg.group([_s("x", 0, 180 * MIN)], "A")["groups"]] == ["NIGHT"]


def test_committed_fixture_equals_the_generator_and_covers_the_cases_of_g4():
    rec = mg.check_grouping_fixture()
    assert rec["passed"], rec
    names = [c["name"] for c in mg.grouping_fixture()["cases"]]
    assert "labelled-pair-21-oct" in names and any("naps-disabled" in n for n in names)
    assert any(n.startswith("covered-10799999ms") for n in names) and any(n.startswith("covered-10800000ms")
                                                                          for n in names)


def test_v4_the_labelled_pair_is_one_night_for_every_arm_but_a():
    v4 = mg.v4_record()
    assert v4["passed"] and v4["gapMin"] == 38 and v4["coveredBeforeGapMin"] == 414
    assert v4["continuation"] == {"A": False, "B": True, "C": True, "R45": True, "R90": True}


# ----------------------------------------------------------------------------------------- hosts, pools ----

def test_host_bouts_and_host_choice(night):
    t0 = night["sessionStartMs"]
    bouts = mg.host_bouts(night)
    assert [((b["b0"] - t0) / MIN, (b["b1"] - t0) / MIN) for b in bouts] == [
        (20.5, 23.5), (55, 59), (95, 97.5), (135, 140), (240, 250), (340, 346), (380, 383), (420, 422), (455, 457)]
    hosts = mg.choose_hosts(night)
    at = {d: (h["b0"] - t0) / MIN for d, h in hosts.items()}
    assert at == {"END-25": 455, "END-60": 420, "END-100": 380, "END-140": 340, "MID": 240, "START-25": 20.5,
                  "START-60": 55, "START-100": 95, "START-140": 135, "DOZE-25": 20.5, "DOZE-60": 55, "DOZE-100": 95,
                  "NAPAFTER-60": 55, "NAPAFTER-100": 95}
    assert hosts["START-25"]["lam1"] == 25.5 * MIN and hosts["END-25"]["lam2"] == 28 * MIN


def test_a_step_record_across_b1_or_a_wake_neighbour_excludes_a_bout(night):
    t0 = night["sessionStartMs"]
    n = dict(night, steps=[[t0 + 58 * MIN, t0 + 60 * MIN, 10]])          # covers b1 = 59 min of the 60-min bout
    assert (55 * MIN + t0) not in [b["b0"] for b in mg.host_bouts(n)]
    short = make_night(spec=[("LIGHT", 30), ("AWAKE", 1.5), ("LIGHT", 200)])
    assert mg.host_bouts(short) == []                                      # 1.5 min < 2 min


def test_wake_pool_blocks_and_order_depend_on_labels_only(twins):
    a, b = twins["instantaneous"], twins["mean2min"]
    pa, pb = mg.wake_pool(a), mg.wake_pool(b)
    assert sorted(pa) == sorted(pb) and all(pa.values())
    t0 = a["sessionStartMs"]
    assert (240 * MIN + t0) in pa and len(pa[240 * MIN + t0]) == 5                    # samples at 240..248 min
    va, vb = mg.pool_values(a, "END-25"), mg.pool_values(b, "END-25")
    assert [x + 0.5 for x in va] == vb                                     # same blocks, same order
    assert mg.pool_values(a, "END-25") != mg.pool_values(a, "MID")         # the order depends on the design
    hosts, info = mg.night_hosts(twins)
    assert set(hosts) == set(mg.DESIGNS) and info == {}


def test_a_night_without_a_wake_pool_in_one_variant_hosts_nothing(twins):
    b = dict(twins["mean2min"], hr=[h for h in twins["mean2min"]["hr"]
                                    if not any(x[0] <= h[0] < x[1] and x[2] == "AWAKE"
                                               for x in twins["mean2min"]["labels"])])
    hosts, info = mg.night_hosts({"instantaneous": twins["instantaneous"], "mean2min": b})
    assert hosts == {} and info == {"noWakePool": ["mean2min"]}
    with pytest.raises(mg.MergeGapError):
        mg.night_hosts({"instantaneous": twins["instantaneous"], "mean2min": dict(b, labels=b["labels"][:-1])})


# ------------------------------------------------------------------------------------------- constructs ----

def _construct(night, design, g, stretch="base"):
    return mg.build_construct(night, design, g, stretch, mg.choose_hosts(night)[design])


def test_end_worked_example(night):
    t0, t1 = night["sessionStartMs"], night["sessionEndMs"]
    c = _construct(night, "END-25", 45)
    d = (45 + 10 - 2) * MIN                                   # w = 2 min
    assert c.shift == d and c.stretch_span == (t0 + 457 * MIN, t0 + 457 * MIN + d)
    assert c.pieces == ((t0, t0 + 460 * MIN), (t0 + 505 * MIN, t1 + d))
    assert c.gap == (t0 + 460 * MIN, t0 + 505 * MIN) and c.gap[1] - c.gap[0] == 45 * MIN
    assert c.pieces[1][1] - c.pieces[1][0] == 28 * MIN
    assert c.regions == {"main": ((t0, t0 + 460 * MIN),), "fragment": ((t0 + 505 * MIN, t1 + d),)}
    # the stretch: 30-s AWAKE labels, heart rate every 2 min from c + 2 min to before x1 - 1 min, the pool in order
    x0, x1 = c.stretch_span
    synth = [h for h in c.hr if x0 <= h[0] < x1]
    assert [h[0] for h in synth] == list(range(t0 + 458 * MIN, x1 - MIN, 2 * MIN))
    vals = mg.pool_values(night, "END-25")
    assert [h[1] for h in synth] == [vals[k % len(vals)] for k in range(len(synth))]
    assert all(s == "AWAKE" for a, b, s in c.labels if x0 <= a < x1)
    assert sum(1 for a, b, s in c.labels if x0 <= a < x1) == d // 30_000
    # everything at or after b1 is shifted by D
    orig_after = [x for x in night["labels"] if x[0] >= t0 + 457 * MIN]
    assert all([a + d, b + d, s] in c.labels for a, b, s in orig_after)
    assert mg.check_construct(c, night) == []


def test_stretch_variants(night):
    t0 = night["sessionStartMs"]
    base, hr10 = _construct(night, "MID", 35), _construct(night, "MID", 35, "hr10")
    x0, x1 = base.stretch_span
    sb = [h for h in base.hr if x0 <= h[0] < x1]
    s10 = [h for h in hr10.hr if x0 <= h[0] < x1]
    assert [h[1] + 10.0 for h in sb] == [h[1] for h in s10] and sb
    assert not [h for h in _construct(night, "MID", 35, "nohr").hr if x0 <= h[0] < x1]
    pre = _construct(night, "MID", 35, "presleep")
    pv = mg.presleep_values(night)
    assert [h[1] for h in pre.hr if x0 <= h[0] < x1] == [pv[k % len(pv)] for k in range(len(sb))]
    walk = _construct(night, "MID", 35, "walk")
    extra = sorted(set(map(tuple, walk.steps)) - set(map(tuple, base.steps)))
    assert extra and extra[0][0] == x0 + 5 * MIN and all(b < x1 - 5 * MIN and n == 40 for a, b, n in extra)
    assert all(mg.check_construct(c, night) == [] for c in (base, hr10, pre, walk))
    assert mg.build_construct(make_night(presleep=False), "MID", 35, "presleep",
                              mg.choose_hosts(night)["MID"]) is None
    assert t0 < x0


def test_doze_copies_the_onset_with_its_pre_sleep_context(night):
    t0, t1 = night["sessionStartMs"], night["sessionEndMs"]
    h = mg.choose_hosts(night)["DOZE-60"]                     # b0 = 55, b1 = 59 min, w = 4
    c = _construct(night, "DOZE-60", 45)
    dp = (45 + 5 - 4) * MIN
    delta = 59 * MIN + dp
    assert c.shift == -delta and c.stretch_span == (t0 - dp, t0)
    assert c.pieces == ((t0 - delta, h["b0"] - delta + 5 * MIN), (t0, t1))
    assert c.gap[1] - c.gap[0] == 45 * MIN and c.pieces[0][1] - c.pieces[0][0] == 60 * MIN
    copied = [[t - delta, v] for t, v in night["hr"] if t0 - 30 * MIN <= t < h["b1"]]
    assert all(x in c.hr for x in copied)
    assert [x for x in c.hr if x[0] >= t0] == [x for x in night["hr"] if x[0] >= t0]       # the night unchanged
    originals_before = [x for x in night["hr"] if x[0] < t0]
    assert not [x for x in originals_before if x in c.hr and x not in copied]                 # removed
    # the merged session has the doze's own 30 min of pre-sleep context
    m = mg.construct_sessions(c, ["M"])["M"]
    assert m["sessionStartMs"] == t0 - delta and m["forcedAwake"] == [list(c.gap)]
    assert min(x[0] for x in m["hr"]) >= t0 - delta - 30 * MIN
    assert any(x[0] < t0 - delta for x in m["hr"])
    assert mg.check_construct(c, night) == []


def test_napafter_copies_the_onset_after_the_night(night):
    t0, t1 = night["sessionStartMs"], night["sessionEndMs"]
    h = mg.choose_hosts(night)["NAPAFTER-100"]                # b0 = 95, b1 = 97.5 min
    c = _construct(night, "NAPAFTER-100", 59)
    dn = (t1 - t0) + 59 * MIN
    assert c.shift == dn and c.gap == (t1, t1 + 59 * MIN)
    assert c.pieces == ((t0, t1), (t1 + 59 * MIN, h["b0"] + 5 * MIN + dn))
    assert c.pieces[1][1] - c.pieces[1][0] == 100 * MIN
    assert not [x for x in c.hr if t1 <= x[0] < t1 + 59 * MIN and x not in
                [y for y in c.hr if c.stretch_span[0] <= y[0] < c.stretch_span[1]]]
    assert all([t + dn, v] in c.hr for t, v in night["hr"] if t0 <= t < h["b1"])
    parts = mg.construct_sessions(c)
    assert set(parts) == {"M", "S1", "S2", "Snap"} and parts["S2"]["labels"] == parts["Snap"]["labels"]
    assert mg.check_construct(c, night) == []


def test_every_design_and_stretch_satisfies_g3_and_g6(twins):
    night = twins["instantaneous"]
    hosts = mg.choose_hosts(night)
    for d in mg.DESIGNS:
        for g, h in mg.GAP_STRETCH:
            c = mg.build_construct(night, d, g, h, hosts[d])
            assert mg.check_construct(c, night) == [], (d, g, h)
            assert tuple(c.gap) == mg.forced_span(d, g, hosts[d])                   # section 5.6's span, restated
            assert mg.check_sessions(c, mg.construct_sessions(c)) == [], (d, g, h)
            for arm in mg.ARM_ORDER:
                assert mg.merged(c.pieces, arm) == mg.expected_arm(d, g, arm), (d, g, arm)
            o = mg.build_construct(twins["mean2min"], d, g, h, hosts[d])
            assert mg.invariant_digest(c) == mg.invariant_digest(o)          # both variants: same boundaries


def test_g3_detects_a_missing_label_and_a_stray_sample(night):
    c = _construct(night, "END-60", 45)
    c.labels = c.labels[1:]
    assert any("labels missing" in x for x in mg.check_construct(c, night))
    c = _construct(night, "END-60", 45)
    c.hr = sorted(c.hr + [[night["sessionStartMs"] + 61_000, 70.0]])
    assert any("outside the stretch" in x for x in mg.check_construct(c, night))


def test_cut_of_an_unmodified_night_is_the_night_file_and_the_positive_control(night):
    t0, t1 = night["sessionStartMs"], night["sessionEndMs"]
    assert mg._normal(mg.cut(mg.night_timeline(night), t0, t1, night["id"])) == mg._normal(night)
    pc = mg.pc_sessions(night)
    assert pc["PCbase"]["sessionStartMs"] == t0 and pc["PClate"]["sessionStartMs"] == t0 + 15 * MIN
    assert pc["PClate"]["id"].endswith("~mg-PC~late") and "forcedAwake" not in pc["PCbase"]
    assert mg.check_pc(pc, night) == []
    assert min(h[0] for h in pc["PClate"]["hr"]) >= t0 - 15 * MIN
    assert all(x[0] >= t0 + 15 * MIN for x in pc["PClate"]["labels"])


def test_the_reader_keeps_heart_rate_after_the_end_and_overlapping_steps(night):
    t0 = night["sessionStartMs"]
    tl = dict(mg.night_timeline(night), steps=[[t0 + 100 * MIN, t0 + 131 * MIN, 5], [t0 + 140 * MIN, t0 + 141 * MIN,
                                                                                      5]])
    s = mg.cut(tl, t0 + 160 * MIN, t0 + 300 * MIN, "x")
    assert s["steps"] == [[t0 + 100 * MIN, t0 + 131 * MIN, 5], [t0 + 140 * MIN, t0 + 141 * MIN, 5]]
    assert max(h[0] for h in s["hr"]) == t0 + 330 * MIN


# ------------------------------------------------------------------------------------- parser, scoring ----

def _per_epoch(path, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["grid_index", "night_id", "subject", "epoch", "start_ms", "has_data", "label", "stage", "p_awake",
                    "p_light", "p_deep", "p_rem"])
        w.writerows(rows)
    return path


def test_parser_and_chunk_scoring_with_a_clipped_last_epoch(tmp_path):
    s0 = T0
    s1 = s0 + 3 * MIN + 30_000                                 # 3.5 min: the last epoch is clipped at the end
    session = {"id": "x", "sessionStartMs": s0, "sessionEndMs": s1,
               "labels": [[s0 + k * 30_000, s0 + (k + 1) * 30_000, st] for k, st in
                          enumerate(["AWAKE", "LIGHT", "LIGHT", "DEEP", "REM", "UNKNOWN", "LIGHT"])]}
    rows = [[0, "x", "7", k, s0 + k * MIN, 1, "", st, 0.25, 0.25, 0.25, 0.25]
            for k, st in enumerate(["AWAKE", "DEEP", "UNKNOWN", "LIGHT"])]
    rows += [[1, "x", "7", 0, s0, 1, "", "REM", 0.25, 0.25, 0.25, 0.25], [0, "y", "7", 0, s0, 1, "", "REM", 0, 0, 0, 1]]
    ep = mg.read_epochs([_per_epoch(tmp_path / "pe.csv", rows)], {"x"})
    assert list(ep) == ["x"] and ep["x"][0].tolist() == [s0 + k * MIN for k in range(4)]
    a, b, truth, pred = mg.chunk_arrays(session, ep["x"], 60_000)
    assert truth.tolist() == [0, 1, 1, 2, 3, -1, 1]
    assert pred.tolist() == [0, 0, 2, 2, -1, -1, 1]
    c = mg.confusion(truth, pred)
    assert c.sum() == 5 and c[1, 0] == 1 and c[1, 2] == 1 and c[2, 2] == 1 and c[1, 1] == 1
    with pytest.raises(mg.MergeGapError):
        mg.read_epochs([_per_epoch(tmp_path / "dup.csv", rows[:1] + rows[:1])])


def _fake_cli(tmp_path):
    from conftest import FAKE, make_launcher
    import sys
    d = tmp_path / "bin"
    d.mkdir(exist_ok=True)
    return AlgoCli(launcher=make_launcher(d, f'exec "{sys.executable}" -I "{FAKE}" "$@"'))


def _specs_for(nights_by_variant):
    hosts = {}
    keys = {napmodel.night_key(n["id"]) for ns in nights_by_variant.values() for n in ns}
    for k in keys:
        hs, _ = mg.night_hosts({v: next(n for n in ns if napmodel.night_key(n["id"]) == k)
                                for v, ns in nights_by_variant.items()})
        hosts[k] = hs
    return hosts


def test_decode_with_the_fake_cli_reproduces_its_confusions_and_forces_the_gap(tmp_path, twins):
    cli = _fake_cli(tmp_path)
    nights = {v: [n] for v, n in twins.items()}
    hosts = _specs_for(nights)
    v = "instantaneous"
    specs = [s for s in mg.enumerate_specs(v, nights[v], hosts)
             if s.design in ("START-25", "END-25", "NAPAFTER-60", "PC") and (s.g in (45, None))]
    assert len({s.id for s in specs}) == len(specs) and specs == sorted(specs, key=lambda s: s.id)
    of = {n["id"]: n for n in nights[v]}
    res = mg.decode_specs(cli, specs, of.__getitem__, hosts, {"napMode": {"maxSessionMin": 180.0}}, None,
                          tmp_path / "w", jobs=2, name="t", block_size=7, estimator="RULES")
    rows = [r for x in res for r in x["rows"]]
    pcs = [r for x in res for r in x["pcs"]]
    assert all(x["file"]["reReadIdentical"] for x in res) and not [p for x in res for p in x["g3"] + x["g6"]]
    g5 = mg.gate_g5(rows, pcs)
    assert g5["passed"] and g5["sessionsCompared"] == len(rows) + len(pcs), g5
    m = next(r for r in rows if r["construct"].endswith("START-25-g45-base") and r["part"] == "M")
    assert m["forced"]["boundaryGapChunks"] == 1                # s2 lies 30 s off the session's grid
    assert m["forced"]["forcedEpochs"] == 45
    assert set(m["regions"]) == {"fragment@0", "piece@0", "main@1", "piece@1"}
    s2 = next(r for r in rows if r["construct"] == m["construct"] and r["part"] == "S2")
    assert set(s2["regions"]) == {"main@1", "piece@1"}
    assert sum(sum(x) for x in m["regions"]["main@1"]["matrix"]) == sum(m["regions"]["main@1"]["truth"])
    pairs = mg.pair_pcs(pcs)
    late, base = mg.pc_matrices(pairs[(twins[v]["id"], v)]["late"], pairs[(twins[v]["id"], v)]["base"])
    assert late.sum() == base.sum() > 0
    assert {r["mode"] for r in pcs} == {"NIGHT"} and {r["mode"] for r in rows if r["part"] == "M"} == {"NIGHT"}
    assert {r["mode"] for r in rows if r["part"] == "Snap"} == {"NAP"}             # S': the nap piece alone
    # the blocks depend on the ids only: other jobs and the same block size give identical rows
    res1 = mg.decode_specs(cli, specs, of.__getitem__, hosts, {"napMode": {"maxSessionMin": 180.0}}, None,
                           tmp_path / "w1", jobs=1, name="t", block_size=7, estimator="RULES")
    assert [r for x in res1 for r in x["rows"]] == rows
    # the constructs' sessions in blocks of 7 by id, then the positive controls' sessions in blocks of their own
    n_c = sum(1 for s in specs if s.design != "PC")
    blocks = mg.blocks_of(specs, 7)
    assert [len(b) for b in blocks] == [7] * (n_c // 7) + ([n_c % 7] if n_c % 7 else []) + [2]
    assert [s.id for b in blocks for s in b] == [s.id for s in specs if s.design != "PC"] + \
        [s.id for s in specs if s.design == "PC"]


# a host night of 190.5 min: PClate = [t0 + 15 min, t1] lasts 175.5 min, under napMode.maxSessionMin 180 a nap
SHORT_SPEC = [("AWAKE", 5), ("LIGHT", 15.5), ("AWAKE", 3), ("DEEP", 60), ("AWAKE", 4), ("LIGHT", 50), ("AWAKE", 3),
              ("REM", 45), ("AWAKE", 5)]


def test_pc_params_force_night_mode_with_the_same_decoder():
    p = {"hybrid": {"emissionWeight": 0.7}, "napMode": {"maxSessionMin": 180.0, "outputPolicy": "STAGES",
                                                         "hybrid": {"timePriorWeight": 0.2}}}
    q = mg.pc_params(p)
    assert q["napMode"]["maxSessionMin"] == 0.0 and p["napMode"]["maxSessionMin"] == 180.0     # a copy
    assert {**q, "napMode": {k: v for k, v in q["napMode"].items() if k != "maxSessionMin"}} == \
        {**p, "napMode": {k: v for k, v in p["napMode"].items() if k != "maxSessionMin"}}
    assert mg.pc_params({})["napMode"] == {"maxSessionMin": 0.0}
    pc = mg.Spec("n~mg-PC~late", "n~mg-PC", "PClate", "n", "k", "1", "walch", "instantaneous", "PC")
    m = mg.Spec("n~mg-MID-g45-base~M", "n~mg-MID-g45-base", "M", "n", "k", "1", "walch", "instantaneous", "MID", 45,
                "base")
    assert mg.block_params([pc], p) == q and mg.block_params([m], p) is p
    with pytest.raises(mg.MergeGapError):
        mg.block_params([m, pc], p)


def test_the_positive_control_of_a_short_host_night_is_decoded_as_a_night(tmp_path):
    """Section 5.5: PClate of a host night shorter than 195 min is decoded as a night (the PC blocks use pc_params),
    while the group's parameters alone would make it a nap."""
    cli = _fake_cli(tmp_path)
    nights = {v: [make_night(v, spec=SHORT_SPEC, offset=0.5 if v == "mean2min" else 0.0,
                             steps=[[T0 + MIN, T0 + 2 * MIN, 10]])] for v in mg.VARIANTS}
    hosts = _specs_for(nights)
    v = "instantaneous"
    n = nights[v][0]
    assert 180 * MIN <= n["sessionEndMs"] - n["sessionStartMs"] < 195 * MIN
    assert set(hosts[napmodel.night_key(n["id"])]) == {"DOZE-25", "DOZE-60", "NAPAFTER-60"}
    params = {"napMode": {"maxSessionMin": 180.0}}
    specs = [s for s in mg.enumerate_specs(v, nights[v], hosts) if s.design == "PC" or s.construct.endswith(
        "DOZE-25-g45-base")]
    res = mg.decode_specs(cli, specs, {n["id"]: n}.__getitem__, hosts, params, None, tmp_path / "w", jobs=2,
                          name="t", estimator="RULES")
    pcs = [r for x in res for r in x["pcs"]]
    assert {r["part"]: r["mode"] for r in pcs} == {"PCbase": "NIGHT", "PClate": "NIGHT"}
    assert {r["part"]: r["mode"] for x in res for r in x["rows"]} == {"M": "NIGHT", "S2": "NIGHT"}
    assert mg.gate_g3_constructs(res)["passed"]
    # the control: the late session decoded with the group's parameters alone is a nap
    late = [s for s in specs if s.part == "PClate"]
    mat = mg.materialize({n["id"]: n}.__getitem__, late, hosts)
    out = mg.decode_block(cli, late, mat, params, None, tmp_path / "w2", "x", estimator="RULES")
    assert [r["mode"] for r in out["pcs"]] == ["NAP"]
    bad = [dict(res[-1], pcs=out["pcs"])]
    g3 = mg.gate_g3_constructs(bad)
    assert not g3["passed"] and g3["nPcNotNight"] == 1


def test_g3_checks_the_positive_control_sessions(night):
    pc = mg.pc_sessions(night)
    t0 = night["sessionStartMs"]
    assert mg.check_pc(pc, night) == []
    bad = dict(pc, PCbase=dict(pc["PCbase"], labels=pc["PCbase"]["labels"][1:]))
    assert any("PCbase is not the unmodified night's cut" in x for x in mg.check_pc(bad, night))
    bad = dict(pc, PClate=mg.cut(mg.night_timeline(night), t0 + 14 * MIN, night["sessionEndMs"], "x"))
    assert any("PClate is not the night's cut" in x for x in mg.check_pc(bad, night))
    bad = dict(pc, PClate=dict(pc["PClate"], forcedAwake=[[t0 + 20 * MIN, t0 + 21 * MIN]]))
    assert any("forced-AWAKE" in x for x in mg.check_pc(bad, night))
    assert mg.check_pc({"PCbase": pc["PCbase"]}, night)


def test_g3_checks_the_forced_span_of_m_and_none_elsewhere(night):
    hosts = mg.choose_hosts(night)
    for d in ("END-60", "MID", "START-100", "DOZE-25", "NAPAFTER-60"):
        c = mg.build_construct(night, d, 45, "base", hosts[d])
        built = mg.construct_sessions(c)
        assert built["M"]["forcedAwake"] == [list(c.gap)] and mg.check_sessions(c, built) == []
        wrong = dict(built, M=dict(built["M"], forcedAwake=[[c.gap[0], c.gap[1] - 30_000]]))
        assert any("M forcedAwake" in x for x in mg.check_sessions(c, wrong)), d
        s = next(p for p in built if p != "M")
        stray = dict(built, **{s: dict(built[s], forcedAwake=[list(c.gap)])})
        assert any(f"{s} has a forced-AWAKE span" in x for x in mg.check_sessions(c, stray)), d
        c.gap = (c.gap[0], c.gap[1] + 30_000)                    # a gap other than 5.6's is found as well
        assert any("section 5.6" in x for x in mg.check_sessions(c, mg.construct_sessions(c))), d
    spec = [s for s in mg.enumerate_specs("instantaneous", [night], {napmodel.night_key(night["id"]): hosts})
            if s.construct.endswith("END-60-g45-base")]
    mat = mg.materialize({night["id"]: night}.__getitem__, spec, {napmodel.night_key(night["id"]): hosts})
    assert mat.g3 == [] and set(mat.blocks) == {spec[0].construct}


def test_block_order_compared_on_the_blocks_both_variants_share():
    q = mg.compare_block_orders({"a": [5, 1, 9], "b": [5, 9]})
    assert q["sameOrder"] and q["shared"] == 2 and q["onlyIn"] == {"a": [1]}
    assert not mg.compare_block_orders({"a": [5, 1, 9], "b": [9, 5]})["sameOrder"]
    assert mg.compare_block_orders({"a": [1], "b": [1]}) == {"sameOrder": True, "shared": 1, "onlyIn": {}}


def _g3_results(nights_by_variant):
    """gate_g3_constructs' input from materialize alone (no decode): one block per variant."""
    hosts = _specs_for(nights_by_variant)
    out = []
    for v, ns in nights_by_variant.items():
        specs = [s for s in mg.enumerate_specs(v, ns, hosts) if s.design != "PC"]
        mat = mg.materialize({n["id"]: n for n in ns}.__getitem__, specs, hosts)
        out.append({"g3": mat.g3, "digests": mat.digests, "blocks": mat.blocks, "variant": v, "pcs": [],
                    "file": {"reReadIdentical": True, "sha256": v}})
    return out


def test_g3_block_order_with_a_wake_bout_without_heart_rate_in_one_variant(twins):
    """A wake bout with heart rate in one variant only (section 2.8, bid-62-1): its block is reported, the order of
    the shared blocks is checked, and a changed order fails G3."""
    t0 = twins["mean2min"]["sessionStartMs"]
    b0, b1 = t0 + 20.5 * MIN, t0 + 23.5 * MIN                    # the first host bout of SPEC (3 min)
    other = dict(twins["mean2min"], hr=[h for h in twins["mean2min"]["hr"] if not b0 <= h[0] < b1])
    assert len(mg.wake_pool(other)) == len(mg.wake_pool(twins["instantaneous"])) - 1
    res = _g3_results({"instantaneous": [twins["instantaneous"]], "mean2min": [other]})
    g3 = mg.gate_g3_constructs(res)
    assert g3["passed"] and g3["nBlockOrderDiffers"] == 0, g3
    assert g3["nBlocksInOneVariantOnly"] == len(mg.DESIGNS)
    assert all(q == {"instantaneous": [int(b0 - t0)]} for q in g3["blocksInOneVariantOnly"].values())
    assert set(g3["blocksInOneVariantOnly"]) == {f"walch-7|{d}" for d in mg.DESIGNS}
    cid = next(iter(res[1]["blocks"]))
    res[1]["blocks"][cid] = list(reversed(res[1]["blocks"][cid]))
    g3 = mg.gate_g3_constructs(res)
    assert not g3["passed"] and g3["nBlockOrderDiffers"] == 1


# ----------------------------------------------------------------------------------- estimator rule ----

def _row(part, estimated, regions):
    return {"part": part, "estimated": estimated, "regions": regions}


def test_estimator_rule():
    truth = [2, 5, 1, 1]
    mat = [[2, 0, 0, 0], [1, 4, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    parts = {"M": _row("M", False, {"main@0": {"truth": truth, "matrix": None}}),
             "S1": _row("S1", True, {"main@0": {"truth": truth, "matrix": mat}})}
    cnt = Counter()
    x, y = mg.region_pair(parts, "main", "S", cnt)
    assert x[:, 0].tolist() == truth and x[:, 1:].sum() == 0 and y.tolist() == mat and cnt["oneNotEstimated"] == 1
    parts["S1"] = _row("S1", False, {"main@0": {"truth": truth, "matrix": None}})
    assert mg.region_pair(parts, "main", "S", cnt) is None and cnt["neitherEstimated"] == 1
    # the comparator S' is the nap decode of the piece
    parts = {"M": _row("M", True, {"fragment@1": {"truth": truth, "matrix": mat}}),
             "Snap": _row("Snap", True, {"fragment@1": {"truth": truth, "matrix": mat}})}
    assert mg.region_pair(parts, "fragment", "S'")[1].tolist() == mat
    assert mg.region_pair(parts, "fragment", "S", cnt) is None and cnt["comparatorNotDecoded"] == 1


def test_positive_control_matrices_on_chunks_scored_under_both():
    late = {"estimated": True, "a0": 0, "truth": "0123.1", "pred": "011.21"}
    base = {"estimated": True, "a0": 0, "truth": "0123.1", "pred": "0.2331"}
    lm, bm = mg.pc_matrices(late, base)
    assert lm.sum() == bm.sum() == 3                    # chunks 0, 3, 5 (label, late and base scored)
    lm, bm = mg.pc_matrices(dict(late, estimated=False), base)
    assert lm[:, 0].sum() == lm.sum() == 4              # not estimated: every labelled chunk AWAKE
    assert mg.pc_matrices(dict(late, estimated=False), dict(base, estimated=False)) is None


# ------------------------------------------------------------------------------------------ statistics ----

def test_stratified_bootstrap_with_one_stratum_equals_paired_bootstrap():
    rng = np.random.default_rng(5)
    a = {f"s{i:02d}": float(x) for i, x in enumerate(rng.normal(0.5, 0.1, 37))}
    b = {f"s{i:02d}": float(x) for i, x in enumerate(rng.normal(0.49, 0.1, 37))}
    pb = report.paired_bootstrap(a, b)
    sb = mg.stratified_paired_bootstrap({"bidsleep": {s: a[s] - b[s] for s in a}})
    assert (sb["ciLow"], sb["ciHigh"]) == (pb["ciLow"], pb["ciHigh"]) and sb["mean"] == pytest.approx(pb["meanDiff"])


def test_stratified_bootstrap_draws_walch_first_then_bidsleep():
    w = {f"w{i}": float(i) for i in range(12)}
    b = {f"b{i:02d}": float(i) / 10 for i in range(31)}
    got = mg.stratified_paired_bootstrap({"bidsleep": b, "walch": w}, n_boot=200)
    rng = np.random.default_rng(0)
    aw = np.array([w[s] for s in sorted(w)])
    ab = np.array([b[s] for s in sorted(b)])
    tot = aw[rng.integers(0, 12, size=(200, 12))].sum(axis=1) + ab[rng.integers(0, 31, size=(200, 31))].sum(axis=1)
    lo, hi = np.quantile(tot / 43, [0.025, 0.975])
    assert (got["ciLow"], got["ciHigh"]) == (float(lo), float(hi)) and got["n"] == 43


def test_dataset_rule_and_evaluability():
    deltas = {**{f"w{i}": -0.5 for i in range(9)}, **{f"b{i:02d}": 0.01 for i in range(35)}}
    rec = mg.summarize_deltas(deltas, lambda s: "walch" if s.startswith("w") else "bidsleep")
    assert rec["n"] == 35 and rec["mean"] == pytest.approx(0.01)
    assert rec["datasets"]["walch"] == {"n": 9, "mean": -0.5, "entered": False}
    q = {"variant": "instantaneous", "stretch": "base", "n": 35, "mean": 0.01, "ciLow": -0.01, "ciHigh": 0.03,
         "pointChecks": {"g35": 0.0, "g45": 0.01, "g59": 0.02, "dataset-bidsleep": 0.01}}
    per = [dict(q, variant=v, stretch=h) for v in mg.VARIANTS for h in mg.DECISIVE_STRETCHES]
    pc = [{"variant": v, "ciLow": -0.04, "detected": True} for v in mg.VARIANTS]
    assert mg.cell_outcome("V1a-late", per, pc)["outcome"] == "pass"
    out = mg.cell_outcome("V1a-late", per[:3] + [dict(q, n=29)], pc)
    assert out["outcome"] == "non-inferiority not shown" and "not evaluable" in out["reasons"][0]
    out = mg.cell_outcome("V1a-late", per, [dict(pc[0], ciLow=-0.01, detected=False), pc[1]])
    assert out["outcome"] == "non-inferiority not shown" and "positive control" in out["reasons"][-1]
    out = mg.cell_outcome("V1a-late", per[:3] + [dict(q, ciLow=-0.03)], pc)
    assert out["outcome"] == "non-inferiority not shown" and "lower bound" in out["reasons"][0]
    out = mg.cell_outcome("V1a-late", per[:3] + [dict(q, pointChecks={**q["pointChecks"], "g59": -0.021})], pc)
    assert out["outcome"] == "harm shown"
    assert mg.cell_outcome("V1b-late", per, None)["outcome"] == "pass"     # no positive control in the V1b cells


def test_subject_deltas_drop_undefined_subjects():
    good = np.array([[5, 1, 0, 0], [1, 10, 1, 0], [0, 1, 5, 0], [0, 0, 1, 6]])
    undefined = np.zeros((4, 4), int)
    mats = {"s1": {35: [good, good * 2], 45: [good, good], 59: [good, good]},
            "s2": {35: [good, good], 45: [undefined, good], 59: [good, good]},
            "s3": {35: [good, good], 59: [good, good]}}
    deltas, per_g, dropped = mg.subject_deltas(mats, "kappa", (35, 45, 59))
    assert list(deltas) == ["s1"] and dropped == ["s2", "s3"]
    assert per_g[35]["s1"] == pytest.approx(0.0)
    sw = mg.metric_of(good, "swacc")
    assert sw == pytest.approx((5 + (10 + 1 + 1 + 5 + 1 + 6)) / good.sum())    # wake-wake + sleep-sleep


# ------------------------------------------------------------------------------------------------- V3, U1 ----

def _replay(arm, sc, first, walks=False, p=60, set_="V3.1", **kw):
    return {"arm": arm, "set": set_, "scenario": sc, "walks": walks, "uploadEveryMin": p, "requestTimes": [first],
            "error": None, "notices": [], "finalNights": [{"importedFinal": True, "noticed": False}], **kw}


def test_v3_regression_check_delay_and_u():
    scen = [("18Oct", False), ("18Oct", True), ("19Oct", False), ("19Oct", True), ("sleepIn", False),
            ("hourly", False)]
    reps = []
    for arm, extra in (("A", 0), ("B", 60), ("C", 45)):
        for p in (15, 60, 180):
            for i, (sc, w) in enumerate(scen):
                reps.append(_replay(arm, sc, T0 + (extra + i) * MIN, w, p))
            for g in (25, 35, 38, 45, 59):
                for d in (1, 25):
                    reps.append(_replay(arm, "fragment", None, p=p, set_="V3.2", g=g, d=d, nightStartMs=10,
                                        fragmentEndMs=100, firstPieceEndMs=50,
                                        saaRecords=[{"startMs": 9, "endMs": 100 if arm != "A" else 50}],
                                        finalNights=[{"importedFinal": arm != "A", "noticed": False}]))
            reps.append(_replay(arm, "nap", T0, p=p, set_="V3.3"))
    v3 = mg.evaluate_v3({"replays": reps}, 50, arms=("A", "B", "C"))
    assert v3["V3a"]["A"]["passed"] is False and v3["V3a"]["B"]["passed"] and v3["V3a"]["C"]["passed"]
    assert v3["V3b"]["B"]["medianAddedDelayMin"] == 60 and v3["V3b"]["C"]["medianAddedDelayMin"] == 45
    assert v3["U"] == {"A": True, "B": False, "C": True}
    assert v3["V3c"]["B"]["fragment"] == 1.0 and v3["V3c"]["A"]["first"] == 1.0
    reps[0]["error"] = "IllegalStateException: boom"
    assert mg.evaluate_v3({"replays": reps}, 50, arms=("A",))["V3a"]["A"]["threw"]


def test_u1_line_order(tmp_path):
    log = tmp_path / "commands.log"
    pre = f"2026-10-22T03:00:00+02:00 {mg.PREREG_NOTE}"
    u1 = "2026-10-22T04:00:00+02:00 NOTE merge-gap U1 DMAX 60: an hour is fine"
    start = "2026-10-22T05:00:00+02:00 START python -I tools/train/train.py mergegap --out-run mergegap"
    gates = "2026-10-22T03:30:00+02:00 START python -I tools/train/train.py mergegap-gates --out-run mergegap-gates"
    log.write_text("\n".join([pre, gates, u1, start]) + "\n")
    rec = mg.u1_record(log)
    assert rec["passed"] and rec["dMax"] == 60
    log.write_text("\n".join([pre, start, u1]) + "\n")
    assert not mg.u1_record(log)["passed"]
    log.write_text("\n".join([pre, u1, u1]) + "\n")
    assert not mg.u1_record(log)["passed"]
    log.write_text("\n".join([u1, pre]) + "\n")
    assert not mg.u1_record(log)["passed"]
    # A NOTE that mentions the start of the experiment is not a START line.
    note = "2026-10-22T03:45:00+02:00 NOTE merge-gap order: logged before the first START of train.py mergegap"
    log.write_text("\n".join([pre, note, u1, start]) + "\n")
    assert mg.u1_record(log)["passed"]


def test_u1_amendment_before_the_first_result_sets_the_limit(tmp_path):
    log = tmp_path / "commands.log"
    pre = f"2026-10-22T03:00:00+02:00 {mg.PREREG_NOTE}"
    u1 = "2026-10-22T04:00:00+02:00 NOTE merge-gap U1 DMAX 60: an hour is fine"
    start = "2026-10-22T05:00:00+02:00 START python -I tools/train/train.py mergegap --out-run mergegap"
    amend = "2026-10-22T05:01:00+02:00 NOTE merge-gap AMENDMENT U1 DMAX 90: the developer chose 90"
    gates = "2026-10-22T04:30:00+02:00 RESULT mergegap-gates G0 passed"
    result = "2026-10-22T05:10:00+02:00 RESULT mergegap case 1: adopt B"
    log.write_text("\n".join([pre, u1, gates, start, amend, result]) + "\n")
    rec = mg.u1_record(log)
    assert rec["passed"] and rec["dMax"] == 90 and rec["loggedDMax"] == 60 and rec["amendment"] == amend
    # An amendment after the first result of the experiment does not count.
    log.write_text("\n".join([pre, u1, start, result, amend]) + "\n")
    rec = mg.u1_record(log)
    assert rec["passed"] and rec["dMax"] == 60 and "amendment" not in rec


# ------------------------------------------------------------------------------------------ decision rule ----

def test_every_case_of_the_decision_table():
    seen = Counter()
    for g, sc, sb, rb, rc, ub, uc in itertools.product([False, True], repeat=7):
        case = mg.decision_case(g, sc, sb, rb, rc, ub, uc)
        seen[case] += 1
        if not g:
            want = 0
        elif sc and sb and rb and ub:
            want = 1
        elif sc and not (sb and rb and ub) and rc and uc:
            want = 2
        elif sc:
            want = 3
        else:
            want = 4
        assert case == want
    assert set(seen) == {0, 1, 2, 3, 4} and sum(seen.values()) == 128
    assert [mg.CASE_OUTCOME[k] for k in range(5)] == ["A", "B", "C", "A", "A"]


def _cells(**fails):
    out = {}
    for k in mg.CELL_ORDER:
        o = fails.get(k.replace("-", "_"), "pass")
        out[k] = {"outcome": o, "passed": o == "pass", "reasons": [] if o == "pass" else ["lower bound -0.03"]}
    return out


def _v3(rb=True, rc=True, ub=True, uc=True):
    return {"V3a": {"B": {"passed": rb}, "C": {"passed": rc}}, "U": {"B": ub, "C": uc}, "dMax": 60,
            "V3b": {"B": {"medianAddedDelayMin": 60.0}, "C": {"medianAddedDelayMin": 60.0}}}


def test_decide_generates_the_reasons():
    v4 = mg.v4_record()
    assert mg.decide(True, None, _cells(), _v3(), v4)["adopted"] == "B"
    d = mg.decide(True, None, _cells(V2a="harm shown"), _v3(), v4)
    assert d["case"] == 2 and d["adopted"] == "C" and "V2a: harm shown" in d["reasons"][0]
    d = mg.decide(True, None, _cells(), _v3(ub=False), v4)
    assert d["case"] == 2 and any("added delay" in r for r in d["reasons"])
    cov = {"A": {"staged": 0.9, "any": 0.95, "e2e": 0.6}, "B": {"staged": 0.99, "any": 0.99, "e2e": 0.65},
           "C": {"staged": 0.97, "any": 0.98, "e2e": 0.64}}
    d = mg.decide(True, None, _cells(V1a_early="non-inferiority not shown"), _v3(uc=False), v4, cov)
    assert d["case"] == 3 and d["adopted"] == "A" and any("coverage A keeps losing against C" in r
                                                          for r in d["reasons"])
    d = mg.decide(True, None, _cells(V2b_nap="harm shown"), _v3(), v4, cov)
    assert d["case"] == 4 and d["reasons"][0].startswith("B and C not adopted: V2b-nap: harm shown")
    d = mg.decide(False, "G3 failed", _cells(), _v3(), v4)
    assert d["case"] == 0 and not d["decision"] and d["adopted"] == "A"
    assert d["booleans"]["L_B"] and d["booleans"]["L_C"]


# -------------------------------------------------------------------------------------------- gradle ----

def test_jvm_test_record_reads_the_junit_xml(tmp_path):
    results = tmp_path / "results"
    results.mkdir()
    xml = ('<?xml version="1.0"?><testsuite name="X" tests="2" skipped="0" failures="0" errors="0" '
           'timestamp="2026-10-22T00:00:00"><testcase name="a"/><testcase name="b"/></testsuite>')
    script = tmp_path / "gradlew"
    script.write_text(f"#!/bin/sh\necho \"$@\" > {tmp_path}/args\ncat > {results}/TEST-X.xml <<'E'\n{xml}\nE\n")
    script.chmod(script.stat().st_mode | stat.S_IXUSR)
    rec = mg.jvm_test_record(":m:t", ("X",), results, ("-Pa=b",), root=tmp_path)
    assert rec["passed"] and rec["classes"]["X"]["tests"] == 2 and len(rec["classes"]["X"]["testcases"]) == 2
    assert (tmp_path / "args").read_text().split() == ["--console=plain", "-Pa=b", ":m:t", "--tests", "X", "--rerun"]


OLD_TEST = """
class T {
    @Test
    fun `a sleep-in - the walk sends it`() = harnessTest {
        assertEquals(1, f(g(2)), "one (request)")
        assertImportedFinal(time(10, 40), wake)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun hourly(n: Int) {
        assertTrue(n > 0)
    }

    @TestFactory fun dynamic() = listOf<DynamicTest>()

    @Test(timeout = 5) fun failing() { fail("never") }
}
"""


def test_junit_test_names_and_assertion_calls():
    assert mg.junit_test_names(OLD_TEST) == ["a sleep-in - the walk sends it", "hourly", "dynamic", "failing"]
    assert mg.junit_test_names("@TestInstance(PER_CLASS) class X { fun helper() {} }") == []
    assert mg.assertion_calls("fun assertFinal(a: Int) { assertEquals(1, a) }") == ["assertEquals(1, a)"]
    assert mg.assertion_calls(OLD_TEST) == ['assertEquals(1, f(g(2)), "one (request)")',
                                            "assertImportedFinal(time(10, 40), wake)", "assertTrue(n > 0)",
                                            'fail("never")']
    assert mg.assertion_calls('assertThrows<IllegalStateException>(\n    "x)",\n) { y() }') == [
        'assertThrows<IllegalStateException>( "x)", )']


def test_existing_test_methods_and_assertions_are_compared_with_the_base():
    assert mg.compare_test_source(OLD_TEST, OLD_TEST)["passed"]
    # a helper's assertion moved to another test source of the module, a new test added: still unchanged
    moved = OLD_TEST.replace("        assertImportedFinal(time(10, 40), wake)\n", "        replaySleepIn()\n") + \
        "@Test fun added() {}"
    q = mg.compare_test_source(OLD_TEST, moved, ["fun replaySleepIn() {\n assertImportedFinal(time(10,  40), wake)\n}"])
    assert q["passed"] and q["methods"] == 4 and q["methodsNow"] == 5 and q["assertions"] == 4
    assert not mg.compare_test_source(OLD_TEST, moved)["passed"]                          # the helper missing
    q = mg.compare_test_source(OLD_TEST, OLD_TEST.replace("fun hourly(", "fun hourlyChecks("))
    assert not q["passed"] and q["missingMethods"] == ["hourly"]
    q = mg.compare_test_source(OLD_TEST, OLD_TEST.replace('"one (request)"', '"one"'))
    assert not q["passed"] and q["nMissingAssertions"] == 1
    assert not mg.compare_test_source(OLD_TEST, None)["passed"]                            # deleted


def test_existing_tests_check_reads_the_changed_test_sources_from_git(tmp_path):
    """existing_tests_check in a scratch repository: a changed test source whose helper moved to an added file of the
    same module passes; a test source of another module does not lend it assertions; a deleted one fails."""
    import subprocess

    def git(*a):
        subprocess.run(["git", "-C", str(tmp_path), "-c", "user.name=t", "-c", "user.email=t@t", *a], check=True,
                       capture_output=True)

    t = tmp_path / "work" / "src" / "test" / "T.kt"
    t.parent.mkdir(parents=True)
    t.write_text(OLD_TEST)
    other = tmp_path / "app" / "src" / "test" / "U.kt"
    other.parent.mkdir(parents=True)
    other.write_text("class U { @Test fun u() { assertTrue(true) } }")
    git("init", "-q")
    git("add", ".")
    git("commit", "-q", "-m", "base")
    base = subprocess.run(["git", "-C", str(tmp_path), "rev-parse", "HEAD"], capture_output=True, text=True,
                          check=True).stdout.strip()
    assert mg.existing_tests_check(base, root=tmp_path) == {"base": base, "paths": list(mg.TEST_SOURCE_PATHS),
                                                            "files": {}, "nFiles": 0, "methods": 0, "assertions": 0,
                                                            "passed": True}
    t.write_text(OLD_TEST.replace("        assertImportedFinal(time(10, 40), wake)\n", "        replaySleepIn()\n"))
    (t.parent / "Timelines.kt").write_text("fun replaySleepIn() { assertImportedFinal(time(10, 40), wake) }")
    rec = mg.existing_tests_check(base, root=tmp_path)
    assert rec["passed"] and list(rec["files"]) == ["work/src/test/T.kt"] and rec["methods"] == 4, rec
    (t.parent / "Timelines.kt").unlink()
    (other.parent / "Timelines.kt").write_text("fun replaySleepIn() { assertImportedFinal(time(10, 40), wake) }")
    assert not mg.existing_tests_check(base, root=tmp_path)["passed"]          # another module's file does not count
    other.unlink()
    rec = mg.existing_tests_check(base, root=tmp_path)
    assert not rec["files"]["app/src/test/U.kt"]["passed"] and not rec["files"]["app/src/test/U.kt"]["exists"]


# ------------------------------------------------------------------------------------------ provenance ----

def test_the_module_pins_the_committed_pre_registration():
    path = mg.REPO_ROOT / mg.PREREG_PATH
    assert hashlib.sha256(path.read_bytes()).hexdigest() == mg.PREREG_SHA256
    assert mg.prereg_record(None)["passed"]


def _build_file(root, module, deps):
    d = root / module
    d.mkdir(parents=True, exist_ok=True)
    (d / "build.gradle.kts").write_text("dependencies {\n" + "".join(f"    {c}(project(\":{m}\"))\n" for c, m in deps)
                                        + "    testImplementation(testFixtures(project(\":core-algo\")))\n}\n")


def test_phase_a_decode_build_needs_algo_cli_on_core_algo_alone(tmp_path):
    _build_file(tmp_path, "algo-cli", [("implementation", "core-algo")])
    _build_file(tmp_path, "core-algo", [("testImplementation", "data-local")])
    q = mg.algo_cli_closure(tmp_path)
    assert q["closure"] == [":core-algo"] and q["onlyCoreAlgo"]
    assert q["modules"][":algo-cli"]["testProjects"] == [":core-algo"]
    _build_file(tmp_path, "core-algo", [("api", "data-local")])
    _build_file(tmp_path, "data-local", [])
    q = mg.algo_cli_closure(tmp_path)
    assert q["closure"] == [":core-algo", ":data-local"] and not q["onlyCoreAlgo"]
    _build_file(tmp_path, "core-algo", [])
    (tmp_path / "algo-cli" / "build.gradle.kts").write_text(
        "dependencies {\n    implementation(project(\":core-algo\"))\n    implementation(projects.dataLocal)\n}\n")
    q = mg.algo_cli_closure(tmp_path)
    assert q["closure"] == [":core-algo"] and q["unresolved"] and not q["onlyCoreAlgo"]


def test_phase_a_refuses_another_decode_build_and_records_the_equality(tmp_path, monkeypatch):
    monkeypatch.setattr(mg, "base_build_record", lambda root=mg.REPO_ROOT: {
        "buildEqualsBase": False, "buildNeutral": {"changed": ["core-algo/x.kt"]}, "rootBuild": {"changed": []},
        "algoCliDependencies": {"closure": [":core-algo"], "unresolved": False}})
    with pytest.raises(mg.MergeGapError, match="decode build of e7caaf4"):
        mg.run_base(None, tmp_path, tmp_path / "out")
    rec = {"algoCli": {"sha256": "f" * 64}, "reference": {"bundleSha256": "b" * 64, "variants": {
        "instantaneous": {"nights": 284, "jsonSha256": "j", "perEpochSha256": "p"}}},
           "baseBuild": {"buildEqualsBase": True, "buildNeutral": {"exitCode": 0}, "otherChangedFiles": ["a.kt"],
                         "algoCliDependencies": {"closure": [":core-algo"]}}}
    note = mg.base_note(rec)
    assert "decode build equal to e7caaf4's, checked now: git diff --quiet e7caaf4 -- core-algo algo-cli " \
           "app/src/main/assets/models exit 0" in note and "dependencies :core-algo only" in note
    assert "\n" not in note and note.startswith(f"merge-gap base build fingerprint {'f' * 64} (phase A; ")


def test_render_and_clean_handle_nan_and_a_stopped_run(tmp_path):
    s = mg.stopped_summary(tmp_path, {"G0": {"passed": True}, "G3": {"passed": False}}, mg.GateFailure("G3 failed", {}))
    assert s["decision"]["case"] == 0 and "G3" in s["decision"]["reasons"][0]
    assert "A kept, no decision" in (tmp_path / "report.md").read_text()
    assert mg._clean({"x": float("nan"), "y": np.int64(3), "z": (1.0, math.inf)}) == {"x": None, "y": 3,
                                                                                         "z": [1.0, None]}
    assert json.loads(json.dumps(mg._clean(mg.grouping_fixture())))["arms"]["C"] == {"mergeGapMin": 30,
                                                                                    "continuationGapMin": 60}


# ------------------------------------------------------------------------------ the analysis end to end ----

def test_finish_runs_the_cells_rule_and_report_on_fake_decodes(tmp_path, monkeypatch):
    """The analysis after the decodes (cells, positive controls, G7, coverage, reported tables, the rule, summary and
    report) on fake-algo-cli decodes of two Walch and two BIDSleep subjects, with the subject thresholds lowered so that
    every statistic is computed."""
    monkeypatch.setattr(mg, "GAP_STRETCH", tuple((g, h) for g in mg.DECISIVE_GAPS for h in mg.DECISIVE_STRETCHES))
    monkeypatch.setattr(mg, "MIN_DATASET_SUBJECTS", 1)
    monkeypatch.setattr(mg, "MIN_EVALUABLE", 1)
    monkeypatch.setattr(mg, "DATASET_MEAN_MIN", 1)
    cli = _fake_cli(tmp_path)
    keys = [("walch-1", "1", 0.0), ("walch-2", "2", 1.0), ("bid-01-1", "bid-01", 2.0), ("bid-02-1", "bid-02", -1.0)]
    nights = {v: [make_night(v, subject=s, key=k, offset=o + (0.5 if v == "mean2min" else 0.0)) for k, s, o in keys]
              for v in mg.VARIANTS}
    hosts = _specs_for(nights)
    rows, pcs = [], []
    for v in mg.VARIANTS:
        of = {n["id"]: n for n in nights[v]}
        res = mg.decode_specs(cli, mg.enumerate_specs(v, nights[v], hosts), of.__getitem__, hosts,
                              {"napMode": {"maxSessionMin": 180.0}}, None, tmp_path / "w", jobs=4, name=v,
                              block_size=200, estimator="RULES")
        rows += [r for x in res for r in x["rows"]]
        pcs += [r for x in res for r in x["pcs"]]
    v3 = mg.evaluate_v3({"replays": []}, 60)
    g0 = {"algoCli": {"sha256": "x"}, "code": {"sha256": "y"}, "u1": {"dMax": 60}}
    gates = {k: {"passed": True} for k in ("G0", "G1", "G2", "G3", "G4", "G5", "G6")}
    out = tmp_path / "out"
    out.mkdir()
    s = mg.finish(out, None, rows, pcs, gates, {"perDesign": {}}, v3, mg.v4_record(), g0, None, 60)
    assert set(s["cells"]) == set(mg.CELL_ORDER)
    assert s["decision"]["case"] in (3, 4) and s["decision"]["adopted"] == "A"      # V3 failed: no replay
    cells = json.loads((out / "cells.json").read_text())["cells"]
    q = cells["V1a-late"]["perVariant"][0]
    assert q["n"] == 4 and set(q["pointChecks"]) >= {"g35", "g45", "g59", "position-END", "position-MID"}
    assert len(cells["V2a"]["positiveControlRecords"]) == 2 and cells["V1b-late"]["positiveControlRecords"] is None
    assert set(s["coverage"]) >= {"A", "B", "C", "R45", "R90", "byDesign"}
    assert s["coverage"]["A"]["staged"] <= s["coverage"]["B"]["staged"]
    assert "## Cells" in (out / "report.md").read_text()
    again = mg.rerender(out)
    assert again["decision"] == json.loads((out / "summary.json").read_text())["decision"]


def test_hosts_night_cut_and_decodes_of_every_group(tmp_path, monkeypatch):
    """all_hosts, the night-cut part of G3, decode_all over outer groups (fold bundles, blocks, calls) and the G3, G5
    and G6 records, with night files on disk and the fake algo-cli (RULES; the fold bundles are placeholders)."""
    from types import SimpleNamespace

    from pulsetrain import napsrealistic, nightset

    monkeypatch.setattr(mg, "GAP_STRETCH", ((45, "base"), (59, "hr10"), (45, "presleep")))
    root = tmp_path / "nights"
    keys = [("walch-1", "1", 0.0), ("walch-2", "2", 1.0), ("bid-01-1", "bid-01", 2.0)]
    for v in mg.VARIANTS:
        (root / v).mkdir(parents=True)
        for k, s, o in keys:
            n = make_night(v, subject=s, key=k, offset=o + (0.5 if v == "mean2min" else 0.0),
                           presleep=(k != "walch-2"))
            (root / v / f"{k}.night.json").write_text(json.dumps(n))
    index = nightset.load_index(root, mg.VARIANTS)
    cache = {}

    def night(v, nid):
        return cache.setdefault((v, nid), json.loads(index.get(v, nid).path.read_text()))

    groups = [SimpleNamespace(variant=v, key=f"g{i}", heldout=hs) for v in mg.VARIANTS
              for i, hs in enumerate((["1", "bid-01"], ["2"]))]
    inp = SimpleNamespace(index=index, night=night, groups=groups, n1_params=lambda g: {"napMode": {"maxSessionMin":
                                                                                                    180.0}})
    monkeypatch.setattr(napsrealistic, "fold_bundle", lambda *a: {"placeholder": True})
    hosts, counts = mg.all_hosts(inp)
    assert counts["nights"] == 3 and counts["noHost"] == {} and set(hosts["walch-1"]) == set(mg.DESIGNS)
    assert counts["perDesign"]["MID"]["hosts"] == {"walch": [2, 2], "bidsleep": [1, 1]}
    assert mg.gate_g3_nights(inp)["passed"]
    dec = mg.decode_all(inp, _fake_cli(tmp_path), tmp_path / "out", hosts, jobs=3, estimator="RULES")
    results = dec["results"]
    assert dec["calls"] == 2 * len(groups) and not (tmp_path / "out" / "work").exists()   # constructs, then PC
    rows = [r for x in results for r in x["rows"]]
    pcs = [r for x in results for r in x["pcs"]]
    # walch-2 has no pre-sleep heart rate: no presleep construct in either variant
    assert not [r for r in rows if r["night"].startswith("walch-2") and r["stretch"] == "presleep"]
    assert [r for r in rows if r["night"].startswith("walch-1") and r["stretch"] == "presleep"]
    g3 = mg.gate_g3_constructs(results)
    assert g3["passed"] and g3["blockOrdersCompared"] > 0 and g3["nBlocksInOneVariantOnly"] == 0, g3
    assert {r["mode"] for r in pcs} == {"NIGHT"}
    assert mg.gate_g5(rows, pcs)["passed"]
    assert mg.gate_g6(results, mg.v4_record())["passed"]
    assert len(mg.pair_pcs(pcs)) == 3 * len(mg.VARIANTS)
