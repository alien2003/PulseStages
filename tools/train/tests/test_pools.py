"""Session pools of the realistic-nap pre-registration (pulsetrain/pools.py, section 4): training tiles with the SHA-256
and remainder rules, the EVAL pools O / W / M / R and their regimes, MIDANCHOR, LATESTART, the FEATURELESS sessions
with unique ids, and the labels-only counts of section 4.6 (on synthetic nights; the real counts are a run gate)."""

import hashlib
import json

import numpy as np
import pytest

from pulsetrain import napmodel, naps, pools
from synthnights import labels_from_spec, night_from_spec, write_dataset

MIN = 60_000
S0 = 3_600_000


def _tiles_by_spec(night):
    """The tile rule of section 4.3 written out independently of pools.py."""
    t0, t1 = night["sessionStartMs"], night["sessionEndMs"]
    key = night["id"].rsplit("-", 1)[0]
    out, t, k = [], t0, 0
    while t1 - t >= 30 * MIN:
        i = int(hashlib.sha256(f"{key}|tile|{k}".encode("utf-8")).hexdigest(), 16) % 6
        L = (30, 60, 90, 120, 150, 170)[i]
        if t + L * MIN > t1:
            L = max(x for x in (30, 60, 90, 120, 150, 170) if t + x * MIN <= t1)
        out.append([t, t + L * MIN])
        t += L * MIN
        k += 1
    r = t1 - t
    if r > 0 and out and out[-1][1] - out[-1][0] + r < 180 * MIN:
        out[-1][1] = t1
        r = 0
    return [tuple(x) for x in out], r


def _night(minutes, nid="walch-7-inst"):
    return night_from_spec([("AWAKE", 5), ("LIGHT", minutes - 5)], night_id=nid)


# --------------------------------------------------------------------------------------------- tiles ----

def test_tiles_follow_the_sha256_rule_and_cover_the_night_once():
    for i in range(40):
        n = _night(200 + 7 * i + (i % 2) * 0.5, nid=f"walch-{100 + i}-inst")
        got = pools.tile_bounds(n)
        assert got == _tiles_by_spec(n), n["id"]
        tb, r = got
        assert tb[0][0] == n["sessionStartMs"]
        assert all(a[1] == b[0] for a, b in zip(tb, tb[1:]))                 # consecutive, non-overlapping
        assert all((b - a) // MIN in pools.ANCHORS for a, b in tb[:-1])
        assert tb[-1][1] + r == n["sessionEndMs"]


def test_tile_index_is_the_digest_of_the_night_key_mod_6():
    d = hashlib.sha256(b"walch-7|tile|3").hexdigest()
    assert pools.tile_index("walch-7", 3) == int(d, 16) % 6


def test_tiles_of_both_variants_are_identical_and_use_the_night_key():
    a = _night(400, "bid-03-2-inst")
    b = dict(_night(400, "bid-03-2-mean2min"), variant="mean2min")
    assert pools.tile_bounds(a) == pools.tile_bounds(b)
    ta = pools.tiles(a)
    assert [s["id"] for s, _ in ta] == [f"bid-03-2-inst-tile{k}" for k in range(len(ta))]
    assert all(m["kind"] == "tile" and m["pool"] == "TILE" for _, m in ta)


def test_tile_remainder_joins_the_last_tile_below_180_min_and_stays_uncovered_otherwise():
    joined = uncovered = None
    for i in range(400):
        n = _night(205 + (i % 50) + 0.5, nid=f"walch-{i}-inst")
        tb, r = pools.tile_bounds(n)
        t = [tuple(x) for x in _tiles_by_spec(n)[0]]
        # the rule before the remainder: the remainder is what is left after the last tile
        if r == 0 and (tb[-1][1] - tb[-1][0]) // MIN not in pools.ANCHORS and joined is None:
            joined = (n, tb)
        if r > 0 and uncovered is None:
            uncovered = (n, tb, r)
        assert tb == t
    assert joined is not None and uncovered is not None
    n, tb = joined
    assert tb[-1][1] == n["sessionEndMs"] and tb[-1][1] - tb[-1][0] < 180 * MIN
    n, tb, r = uncovered
    assert 0 < r < 30 * MIN and (tb[-1][1] - tb[-1][0]) + r >= 180 * MIN


def test_a_tile_that_does_not_fit_takes_the_largest_anchor_that_fits():
    for i in range(200):
        n = _night(240, nid=f"walch-{i}-inst")
        tb, _ = pools.tile_bounds(n)
        key = napmodel.night_key(n["id"])
        t = n["sessionStartMs"]
        for k, (a, b) in enumerate(tb):
            want = pools.ANCHORS[pools.tile_index(key, k)] * MIN
            if a + want > n["sessionEndMs"]:
                fit = max(x * MIN for x in pools.ANCHORS if a + x * MIN <= n["sessionEndMs"])
                assert b - a in (fit, n["sessionEndMs"] - a)
                return
    pytest.fail("no night with a shortened tile found")


def test_a_night_shorter_than_30_min_has_no_tile():
    assert pools.tile_bounds(_night(29)) == ([], 29 * MIN)


# ------------------------------------------------------------------------------------------ EVAL pools ----

def _spec_night():
    # wake 3, light 100, deep 60, wake 4 (ends at 167 min), REM 40, wake 1 (ends at 208), light 60, wake 6 (ends at
    # 274), light 120 -> 400 min
    spec = [("AWAKE", 3), ("LIGHT", 100), ("DEEP", 60), ("AWAKE", 4), ("REM", 40), ("AWAKE", 1), ("LIGHT", 60),
            ("AWAKE", 6), ("LIGHT", 126)]
    return night_from_spec(spec, steps=[[S0 - 40 * MIN, S0 - 39 * MIN, 5], [S0 - 10 * MIN, S0 - 9 * MIN, 3],
                                        [S0 + 200 * MIN, S0 + 201 * MIN, 4]])


def test_onset_sessions_are_the_nap_proxies_at_the_anchors_and_the_same_cut_between():
    n = _spec_night()
    o = pools.onset(n, pools.ANCHORS)
    assert [s["id"] for s, _ in o] == [f"walch-7-inst-nap{L}" for L in pools.ANCHORS]
    for (s, m), L in zip(o, pools.ANCHORS):
        assert s == naps.nap_proxy(n, L) and m["regime"] == "EARLY" and m["L"] == L and m["pool"] == "O"
    mid = pools.onset(n, pools.MID_ANCHORS, "midanchor")
    for (s, m), L in zip(mid, pools.MID_ANCHORS):
        assert s["id"] == f"walch-7-inst-nap{L}" and m["kind"] == "midanchor"
        assert s == dict(naps.nap_proxy(n, L))                               # the same cut
    short = _night(50)
    assert [s["id"] for s, _ in pools.onset(short, pools.ANCHORS)] == ["walch-7-inst-nap30"]


def test_mid_night_sessions_start_at_the_offsets_and_end_inside_the_session():
    n = _spec_night()
    m = pools.mid(n, pools.ANCHORS)
    t0, t1 = n["sessionStartMs"], n["sessionEndMs"]
    want = [(o, L) for o in pools.M_OFFSETS for L in pools.ANCHORS if t0 + (o + L) * MIN <= t1]
    assert [(x["o"], x["L"]) for _, x in m] == want
    for s, meta in m:
        assert s["id"] == f"walch-7-inst-mid{meta['o']}x{int(meta['L'])}"
        assert s["sessionStartMs"] == t0 + meta["o"] * MIN
        assert meta["regime"] == ("OTHER" if meta["o"] == 120 else "LATE")
        assert s == napmodel.cut_window(n, s["sessionStartMs"], s["sessionEndMs"], s["id"])
        assert all(h[0] <= s["sessionEndMs"] for h in s["hr"])               # nothing after s1
        assert min(h[0] for h in s["hr"]) >= s["sessionStartMs"] - 30 * MIN


def test_return_to_sleep_needs_a_bout_of_2_min_ending_180_min_after_the_start():
    n = _spec_night()
    t0 = n["sessionStartMs"]
    # bouts: [0, 3) too early, [163, 167) ends at 167 < 180, [207, 208) too short, [268, 274) qualifies
    assert pools.return_bouts(n) == [(t0 + 268 * MIN, t0 + 274 * MIN)]
    r = pools.return_to_sleep(n, pools.ANCHORS)
    s0 = t0 + 269 * MIN                                                      # b1 - min(5 min, 6 min)
    assert [s["id"] for s, _ in r] == [f"walch-7-inst-rs269x{L}" for L in (30, 60, 90, 120)]
    assert all(s["sessionStartMs"] == s0 and m["regime"] == "LATE" and m["pool"] == "R" for s, m in r)
    # a 3-min bout: s0 = b1 - 3 min
    n2 = night_from_spec([("LIGHT", 200), ("AWAKE", 3), ("LIGHT", 100)])
    r2 = pools.return_to_sleep(n2, (30,))
    assert [s["id"] for s, _ in r2] == ["walch-7-inst-rs200x30"]


def test_return_to_sleep_session_must_start_at_or_after_the_session_start():
    n = night_from_spec([("AWAKE", 200), ("LIGHT", 100)])                    # one bout [0, 200): s0 = 195 min
    assert [s["id"] for s, _ in pools.return_to_sleep(n, (30,))] == ["walch-7-inst-rs195x30"]
    assert pools.return_to_sleep(n, (170,)) == []                            # s1 = 365 > t1 = 300


def test_eval_sessions_have_regimes_unique_ids_and_identical_windows_in_both_variants(tmp_path):
    root = write_dataset(tmp_path / "n", n_walch=3, n_bid=2, bid_nights=2, long_wakes=True, shared_labels=True,
                         low_coverage_night=False)
    for f in sorted((root / "instantaneous").glob("*.night.json")):
        a = json.loads(f.read_text())
        b = json.loads((root / "mean2min" / f.name).read_text())
        ea, eb = pools.eval_sessions(a), pools.eval_sessions(b)
        pools.assert_unique_ids(s for s, _ in ea)
        strip = (lambda sid: sid.replace("-inst", "").replace("-mean2min", ""))
        assert [(strip(s["id"]), s["sessionStartMs"], s["sessionEndMs"], s["labels"]) for s, _ in ea] == \
               [(strip(s["id"]), s["sessionStartMs"], s["sessionEndMs"], s["labels"]) for s, _ in eb]
        for s, m in ea:
            assert m["regime"] == pools.regime_of(m["pool"], m["o"])
            assert pools.is_anchor(m) == (m["pool"] != "W")
            assert pools.is_matched(m) == (m["regime"] in ("EARLY", "LATE") and m["L"] in (60, 90))
        ma = pools.midanchor_sessions(a)
        pools.assert_unique_ids(s for s, _ in ea + ma)
        assert {m["L"] for _, m in ma} <= set(map(float, pools.MID_ANCHORS))


def test_ends_in_wake_windows_are_the_13_12_windows():
    spec = [("AWAKE", 3), ("LIGHT", 40), ("AWAKE", 2), ("DEEP", 30), ("AWAKE", 4), ("REM", 20), ("AWAKE", 6),
            ("LIGHT", 200), ("AWAKE", 5)]
    n = night_from_spec(spec)
    w = pools.ends_in_wake(n)
    assert [(s["sessionStartMs"], s["sessionEndMs"]) for s, _ in w] == napmodel.sample_windows(n)
    assert all(s["id"] == napmodel.ew_id(n, s["sessionStartMs"]) and m["regime"] == "OTHER" for s, m in w)


def test_regimes():
    assert pools.regime_of("O", None) == "EARLY" and pools.regime_of("R", None) == "LATE"
    assert pools.regime_of("M", 120) == "OTHER" and pools.regime_of("M", 180) == "LATE"
    assert pools.regime_of("W", None) == "OTHER"
    with pytest.raises(pools.PoolError):
        pools.regime_of("TILE", None)


# ----------------------------------------------------------------------------------- reported-only pools ----

def test_late_start_sessions_begin_late_with_the_labels_inside():
    n = _spec_night()
    t0 = n["sessionStartMs"]
    ls = pools.late_start(n)
    assert [s["id"] for s, _ in ls] == ["walch-7-inst-ls10x60", "walch-7-inst-ls20x60", "walch-7-inst-ls10x90",
                                        "walch-7-inst-ls20x90"]
    s, m = ls[1]
    assert (s["sessionStartMs"], s["sessionEndMs"]) == (t0 + 20 * MIN, t0 + 60 * MIN)
    assert s["labels"][0][0] == t0 + 20 * MIN and len(s["labels"]) == 80
    assert min(h[0] for h in s["hr"]) >= t0 - 10 * MIN                       # the common cut from s0 - 30 min
    assert m["original"] == "walch-7-inst-nap60" and m["delta"] == 20


def test_featureless_session_definition():
    s, m = pools.featureless(45, "pre10", 2, 7, "1234", "instantaneous")
    assert s["id"] == "syn-pre10-L45-sd2-k7-1234" and s["subject"] == "1234" and s["steps"] == []
    s0, s1 = 3_600_000, 3_600_000 + 45 * MIN
    assert (s["sessionStartMs"], s["sessionEndMs"]) == (s0, s1)
    t = [h[0] for h in s["hr"]]
    assert t[0] == s0 - 30 * MIN + 60_000 and all(b - a == 120_000 for a, b in zip(t, t[1:]))
    assert t[-1] <= s1 + 30 * MIN < t[-1] + 120_000 and t[-1] == s1 + 30 * MIN      # odd L: a sample at s1 + 30
    eps = np.random.default_rng([45, 1, 2, 7]).normal(0.0, 2.0, size=len(t))
    for (tt, v), e in zip(s["hr"], eps):
        assert v == round(60 + e) + (10 if tt < s0 else 0)
    assert s["labels"] == [[x, x + 30_000, "LIGHT"] for x in range(s0, s1, 30_000)]
    assert m["kind"] == "featureless" and (m["design"], m["sd"], m["seed"], m["L"]) == ("pre10", 2, 7, 45.0)
    p = pools.featureless(60, "post12", 1, 0, "x")[0]
    assert all(v == round(60 + e) + (12 if tt > s0 + 60 * MIN else 0)
               for (tt, v), e in zip(p["hr"], np.random.default_rng([60, 2, 1, 0]).normal(0, 1.0, len(p["hr"]))))
    f = pools.featureless(60, "flat", 3, 19, "x")[0]
    assert all(abs(v - 60) < 30 and float(v).is_integer() for _, v in f["hr"])
    assert pools.featureless(60, "flat", 3, 19, "x") == pools.featureless(60, "flat", 3, 19, "x")   # deterministic
    with pytest.raises(pools.PoolError):
        pools.featureless(60, "steps", 1, 0, "x")


def test_featureless_set_has_1980_sessions_per_subject_with_unique_ids():
    fs = pools.featureless_set(["1", "2"])
    assert len(fs) == 2 * 1980
    assert pools.assert_unique_ids(s for s, _ in fs) == 3960
    assert len(pools.featureless_set(["1"], seeds=range(10))) == 990
    ids = {s["id"] for s, _ in fs}
    assert "syn-flat-L30-sd1-k0-1" in ids and "syn-post12-L170-sd3-k19-2" in ids
    # the same (L, design, sd, seed) gives the same heart rate in every subject copy
    a = {s["id"].rsplit("-", 1)[0]: s["hr"] for s, _ in fs if s["subject"] == "1"}
    b = {s["id"].rsplit("-", 1)[0]: s["hr"] for s, _ in fs if s["subject"] == "2"}
    assert a == b


def test_assert_unique_ids_refuses_duplicates():
    with pytest.raises(pools.PoolError, match="duplicate"):
        pools.assert_unique_ids([{"id": "a"}, {"id": "b"}, {"id": "a"}])


def test_label_counts_are_30s_chunks_of_every_label():
    s = {"labels": labels_from_spec([("AWAKE", 1), ("LIGHT", 2), ("DEEP", 0.5), ("REM", 3)], 0)}
    assert pools.label_counts(s).tolist() == [2, 4, 1, 6]


# ---------------------------------------------------------------------------------------- counts (G7) ----

def test_count_pools_and_compare_counts(tmp_path):
    root = write_dataset(tmp_path / "n", n_walch=3, n_bid=2, bid_nights=2, long_wakes=True, low_coverage_night=False)
    nights = [json.loads(f.read_text()) for f in sorted((root / "instantaneous").glob("*.night.json"))]
    c = pools.count_pools(nights)
    assert set(c) == {"walch", "bidsleep"} and c["walch"]["nights"] == 3 and c["bidsleep"]["nights"] == 4
    w = c["walch"]
    assert w["O"] == {"sessions": 18, "subjects": 3}
    n_eval = sum(len(pools.eval_sessions(n)) for n in nights if n["id"].startswith("walch"))
    assert w["EVAL"] == n_eval == w["O"]["sessions"] + w["W"]["sessions"] + w["M120"]["sessions"] \
        + w["MLATE"]["sessions"] + w["R"]["sessions"]
    assert sum(w["MLATE"]["perLength"]) == w["MLATE"]["sessions"] and len(w["MIDANCHOR"]["perLength"]) == 5
    assert w["tiles"]["coveredMin"] + w["tiles"]["uncoveredMin"] == pytest.approx(w["tiles"]["sessionMin"])
    diffs = pools.compare_counts(c)
    assert any(d.startswith("walch O:") for d in diffs) and any(d.startswith("bidsleep nights") for d in diffs)
    assert pools.compare_counts(c, {"walch": {**_expected_of(c["walch"])}}) == []


def _expected_of(c):
    e = {k: (c[k]["sessions"], c[k]["subjects"]) for k in ("O", "W")}
    e.update({k: (c[k]["sessions"], c[k]["subjects"], tuple(c[k]["perLength"]))
              for k in ("M120", "MLATE", "R", "MIDANCHOR")})
    e.update({"EVAL": c["EVAL"], "tiles": (c["tiles"]["tiles"], c["tiles"]["nights"]), "nights": c["nights"],
              "tileMinutes": (round(c["tiles"]["coveredMin"], 1), round(c["tiles"]["sessionMin"], 1))})
    return e


def test_expected_counts_are_those_of_section_4_6():
    e = pools.EXPECTED_COUNTS
    assert e["walch"]["EVAL"] == 186 + 11 + 171 + 439 + 141 == 948
    assert e["bidsleep"]["EVAL"] == 1518 + 139 + 1505 + 3359 + 1208 == 7729
    for ds in e:
        for k in ("M120", "MLATE", "R", "MIDANCHOR"):
            assert sum(e[ds][k][2]) == e[ds][k][0], (ds, k)
    assert e["bidsleep"]["tileMinutes"][1] - e["bidsleep"]["tileMinutes"][0] == 108.5
