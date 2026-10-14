"""Dedicated nap model (pulsetrain/napmodel.py): nap proxies v2 (onset + ends-in-wake windows), the sleep / wake
metrics, the two pre-registered rules, the final choice, and the whole experiment (gate, nested inner CV, outer LOSO,
final nap section, verification) on a tiny run of the FAKE algo-cli (tests/fake_algo_cli.py, which implements the nap
section, stageLogBiasShort, napMode.maxSessionMin and the napMode.hybrid overrides like core-algo)."""

import json
import sys

import numpy as np
import pytest

import train
from conftest import FAKE, make_launcher
from pulsetrain import bundle, kotlin_defaults, napmodel, naps
from pulsetrain.util import read_json, write_json
from synthnights import write_dataset

MIN = 60_000
S0 = 3_600_000


def _labels(spec, s0=S0):
    """[(stage, minutes), ...] -> 30-s label epochs from s0."""
    out, t = [], s0
    for st, m in spec:
        for _ in range(int(m * 2)):
            out.append([t, t + 30_000, st])
            t += 30_000
    return out


def _night(spec, nid="walch-7-inst", hr_every=MIN):
    labels = _labels(spec)
    s1 = labels[-1][1]
    return {"id": nid, "subject": "7", "variant": "instantaneous", "sessionStartMs": S0, "sessionEndMs": s1,
            "hr": [[t, 60.0] for t in range(S0 - 30 * MIN, s1 + 30 * MIN, hr_every)],
            "steps": [[S0 - 40 * MIN, S0 - 39 * MIN, 5], [S0 - 10 * MIN, S0 - 9 * MIN, 3], [s1 - MIN, s1, 2]],
            "labels": labels}


# ------------------------------------------------------------------------------------------------ proxies ----

def test_wake_bouts_are_maximal_contiguous_awake_runs():
    labels = _labels([("AWAKE", 3), ("LIGHT", 10), ("AWAKE", 1), ("AWAKE", 1), ("DEEP", 5)])
    assert napmodel.wake_bouts(labels) == [(S0, S0 + 3 * MIN), (S0 + 13 * MIN, S0 + 15 * MIN)]
    b = napmodel.wake_bouts(labels[:4] + labels[5:])                # a missing (unscored) epoch splits the bout
    assert b[0] == (S0, S0 + 2 * MIN) and b[1] == (S0 + 2 * MIN + 30_000, S0 + 3 * MIN)
    assert napmodel.wake_bouts(list(reversed(labels))) == napmodel.wake_bouts(labels)   # order of the input


def test_ends_in_wake_windows_use_the_ohealth_margins_and_the_length_limits():
    spec = [("AWAKE", 3), ("LIGHT", 40), ("AWAKE", 2), ("DEEP", 30), ("AWAKE", 4), ("REM", 20), ("AWAKE", 6),
            ("LIGHT", 200), ("AWAKE", 5)]
    n = _night(spec)
    c = napmodel.ew_candidates(n)
    # opening bout 0-3 min: s0 = 0 (only 3 min of wake before the segment); closing bout = the 6-min awakening at
    # 99 min (the 4-min one at 75 min is too short); s1 = 99 + 5 = 104 min
    assert (S0, S0 + 104 * MIN) in c
    # opening bout 43-45 min: s0 = 45 - 2 = 43 min, same closing bout
    assert (S0 + 43 * MIN, S0 + 104 * MIN) in c
    # the 4-min bout at 75-79 opens a window to the 6-min awakening: 79 - 4 = 75 -> 104: 29 min, too short
    assert all(w[0] != S0 + 75 * MIN for w in c)
    # the 6-min awakening (99-105) opens a window to the final 5-min wake at 305: 105 - 5 = 100 -> 310 = 210 min,
    # too long (> 179)
    assert all(w[0] != S0 + 100 * MIN for w in c)
    assert len(c) == 2
    for s0, s1 in c:
        assert 30 * MIN <= s1 - s0 <= 179 * MIN


def test_window_sampling_is_deterministic_non_overlapping_capped_and_shared_by_the_variants():
    spec = [("AWAKE", 5)] + [("LIGHT", 30), ("AWAKE", 10)] * 6     # 6 windows of 40 min, touching, not overlapping
    n = _night(spec)
    c = napmodel.ew_candidates(n)
    assert len(c) == 6 and all(s1 - s0 == 40 * MIN for s0, s1 in c)
    w = napmodel.sample_windows(n)
    assert len(w) == napmodel.EW_CAP and w == sorted(w)
    assert all(a[1] <= b[0] for a, b in zip(w, w[1:]))              # no overlap
    assert napmodel.sample_windows(n) == w
    twin = dict(n, id="walch-7-mean2min")
    assert napmodel.night_key(twin["id"]) == napmodel.night_key(n["id"]) == "walch-7"
    assert napmodel.sample_windows(twin) == w
    keys = {tuple(napmodel.sample_windows(dict(n, id=f"walch-{i}-inst"))) for i in range(20)}
    assert len(keys) > 1                                            # the order depends on the night key
    assert len(napmodel.sample_windows(n, cap=1)) == 1
    overlapping = _night([("AWAKE", 5)] + [("LIGHT", 25), ("AWAKE", 2), ("DEEP", 20), ("AWAKE", 6)] * 6)
    w = napmodel.sample_windows(overlapping)
    assert 1 <= len(w) <= napmodel.EW_CAP and all(a[1] <= b[0] for a, b in zip(w, w[1:]))


def test_cut_window_is_the_nap_proxy_cut_and_has_no_post_session_data():
    n = _night([("AWAKE", 5), ("LIGHT", 200), ("AWAKE", 5)])
    for L in napmodel.ONSET_LENGTHS:
        assert napmodel.cut_window(n, S0, S0 + L * MIN, f"{n['id']}-nap{L}") == naps.nap_proxy(n, L)
    s0, s1 = S0 + 50 * MIN, S0 + 120 * MIN
    p = napmodel.cut_window(n, s0, s1, "x")
    assert min(h[0] for h in p["hr"]) == s0 - 30 * MIN and max(h[0] for h in p["hr"]) <= s1
    assert all(s0 - 30 * MIN < st[1] <= s1 for st in p["steps"])
    assert p["labels"][0][0] == s0 and p["labels"][-1][1] == s1
    with pytest.raises(napmodel.NapModelError, match="outside"):
        napmodel.cut_window(n, S0 - MIN, s1, "x")
    with pytest.raises(napmodel.NapModelError, match="unexpected"):
        napmodel.cut_window(dict(n, spo2=[]), s0, s1, "x")


def test_proxies_of_a_night_and_their_parts():
    spec = [("AWAKE", 3), ("LIGHT", 40), ("AWAKE", 2), ("DEEP", 30), ("AWAKE", 6), ("REM", 120), ("AWAKE", 5)]
    n = _night(spec)
    items = napmodel.proxies_of(n)
    onset = [m for _, m in items if m["proxy"] == "onset"]
    ew = [(p, m) for p, m in items if m["proxy"] == "ew"]
    assert [m["L"] for m in onset] == list(napmodel.ONSET_LENGTHS)
    assert [p["id"] for p, _ in items][:2] == [f"{n['id']}-nap30", f"{n['id']}-nap60"]
    assert ew and all(p["id"] == f"{n['id']}-ew{m['offsetMin']:g}" for p, m in ew)
    assert all(p["labels"][-1][2] == "AWAKE" and p["labels"][0][2] == "AWAKE" for p, _ in ew)   # ends in wake
    r = {"proxy": "ew", "L": 75.5}
    assert napmodel.parts_of(r) == ["all", "ew", "ew-60-89"]
    assert napmodel.parts_of({"proxy": "onset", "L": 150}) == ["all", "onset", "onset-150"]
    assert napmodel.parts_of({"proxy": "night", "L": None}) == []
    with pytest.raises(napmodel.NapModelError):
        napmodel.ew_bin(180.0)
    with pytest.raises(napmodel.NapModelError, match="shorter"):
        napmodel.proxies_of(_night([("AWAKE", 5), ("LIGHT", 100)]))


# ------------------------------------------------------------------------------------------------ metrics ----

def test_sleep_wake_collapse_wake_scores_and_all_light():
    c = np.array([[5, 3, 1, 1], [2, 20, 3, 1], [0, 4, 10, 0], [1, 2, 0, 7]])
    c2 = napmodel.collapse_sleep_wake(c)
    assert c2.tolist() == [[5, 5], [3, 47]] and c2.sum() == c.sum()
    w = napmodel.wake_scores(c2)
    assert w["precision"] == pytest.approx(5 / 8) and w["recall"] == pytest.approx(0.5)
    assert w["f1"] == pytest.approx(2 * 5 / (2 * 5 + 3 + 5))
    assert np.isnan(napmodel.wake_scores(np.array([[0, 0], [0, 4]]))["f1"])
    rec = {"candidate": "N0", "confusion": c.tolist(), "warnings": ["LOW_HR_RANGE"], "estimated": True}
    al = napmodel.all_light([rec, dict(rec, confusion=None, estimated=False)])
    assert al[0]["candidate"] == napmodel.LIGHT_KIND and al[0]["warnings"] == []
    assert np.asarray(al[0]["confusion"])[:, 1].tolist() == c.sum(axis=1).tolist() and al[1]["confusion"] is None


def _rec(name, subject, conf, proxy="onset", L=30, dataset="walch", variant="instantaneous"):
    return {"candidate": name, "night": f"{subject}-{proxy}{L}", "subject": subject, "dataset": dataset,
            "variant": variant, "proxy": proxy, "L": L, "estimated": True, "reason": None, "warnings": [],
            "confusion": conf}


def test_cell_pools_chunks_and_averages_subjects():
    a = [[4, 1, 0, 0], [1, 8, 1, 0], [0, 1, 2, 0], [0, 0, 0, 2]]
    b = [[0, 0, 0, 0], [0, 6, 0, 0], [0, 0, 0, 0], [0, 2, 0, 0]]
    c = napmodel.cell([_rec("N1", "s1", a), _rec("N1", "s2", b, proxy="ew", L=45)])
    pooled = np.asarray(a) + np.asarray(b)
    assert c["accuracy"] == pytest.approx(np.trace(pooled) / pooled.sum())
    assert c["lightAccuracy"] == pytest.approx(pooled[1].sum() / pooled.sum())
    assert c["subjectLightAccuracy"]["s2"] == pytest.approx(6 / 8)
    assert c["swKappaDefined"] == 1 and np.isnan(c["subjectSwKappa"]["s2"])   # s2: no wake at all, undefined
    assert c["swAccuracy"] == pytest.approx(np.trace(napmodel.collapse_sleep_wake(pooled)) / pooled.sum())
    cells = napmodel.cells_of([_rec("N1", "s1", a), _rec("N1", "s2", b, proxy="ew", L=45),
                               dict(_rec("N1", "s1", a), proxy="night", L=None)], names=("N1",))
    per = cells["N1"]["walch"]["instantaneous"]
    assert set(per) == {"all", "onset", "onset-30", "ew", "ew-30-59"} and per["all"]["sessions"] == 2


def _scores(acc, e, k=0.3, sw=0.4, light=0.5):
    v = ("instantaneous", "mean2min")
    return {"accuracy": dict.fromkeys(v, acc), "E": dict.fromkeys(v, e), "accWorse": acc, "EWorse": e,
            "kappaWorse": k, "swKappaWorse": sw, "accMarginWorse": acc - light}


def test_rule_i_adopts_the_most_accurate_qualifying_nap_model():
    s = {"N0": _scores(0.50, 70.0), "N1": _scores(0.55, 60.0), "N2": _scores(0.53, 65.0)}
    d = napmodel.adopt_model(s)
    assert d["adopted"] == "N1" and d["qualifying"] == ["N1", "N2"] and d["accuracyThreshold"] == pytest.approx(0.52)
    s["N1"] = _scores(0.60, 71.0)                                     # more accurate but a larger share error
    assert napmodel.adopt_model(s)["adopted"] == "N2"
    s["N2"] = _scores(0.515, 60.0)                                    # gain below 0.02
    assert napmodel.adopt_model(s)["adopted"] == "N0" and napmodel.adopt_model(s)["qualifying"] == []
    s = {"N0": _scores(0.50, 70.0), "N1": _scores(0.5502, 60.0), "N2": _scores(0.5500, 55.0)}   # accuracy tie
    assert napmodel.adopt_model(s)["adopted"] == "N2"
    s = {"N0": _scores(0.50, 70.0), "N1": _scores(0.55, 60.0), "N2": _scores(0.55, 60.0)}
    assert napmodel.adopt_model(s)["adopted"] == "N1"                 # full tie -> N1 first
    s = {"N0": _scores(0.50, 70.0), "N1": _scores(0.52, 70.0)}       # exactly at both limits qualifies
    assert napmodel.adopt_model(s)["adopted"] == "N1"


def test_rule_ii_output_policy():
    assert napmodel.choose_policy(_scores(0.60, 50, k=0.20, light=0.55))["policy"] == "STAGES"
    assert napmodel.choose_policy(_scores(0.60, 50, k=0.15, light=0.57))["policy"] == "STAGES"   # both at the limit
    assert napmodel.choose_policy(_scores(0.60, 50, k=0.14, sw=0.25, light=0.50))["policy"] == "SLEEP_WAKE"
    assert napmodel.choose_policy(_scores(0.60, 50, k=0.40, sw=0.20, light=0.58))["policy"] == "SLEEP_WAKE"
    p = napmodel.choose_policy(_scores(0.60, 50, k=0.40, sw=0.19, light=0.58))
    assert p["policy"] == "SKIP" and p["stagesCriteria"] == {"accMargin": False, "kappa": True}


def test_final_choice_counts_outer_folds():
    sel = [{"variant": "instantaneous", "key": "a", "chosen": {"score": 0.3}},
           {"variant": "instantaneous", "key": "b", "chosen": {"score": 0.2}},
           {"variant": "mean2min", "key": "a", "chosen": {"score": 0.25}}]
    h1 = {"durationModel": "EXPLICIT", "emissionWeight": 0.5, "timePriorWeight": 1.0}
    h2 = {"durationModel": "GEOMETRIC", "emissionWeight": 1.0, "timePriorWeight": 0.0}
    folds = ([{"variant": "instantaneous", "subject": f"s{i}", "group": "a", "classifier": "mlp", "C": None,
               "hybrid": h1} for i in range(3)]
             + [{"variant": "instantaneous", "subject": f"t{i}", "group": "b", "classifier": "lr", "C": 0.1,
                 "hybrid": h2} for i in range(2)]
             + [{"variant": "mean2min", "subject": f"s{i}", "group": "a", "classifier": "mlp", "C": None,
                 "hybrid": h2} for i in range(3)])
    ch = napmodel.final_choice(folds, sel)
    assert ch["classifier"] == "mlp" and ch["C"] is None and ch["foldsOfClassifier"] == 6
    assert ch["hybrid"] == h1 and ch["thetaCount"] == 3              # 3 : 3 tie -> higher mean inner score (0.3)
    folds = [dict(f, classifier="lr", C=0.1 if i % 3 else 1.0) for i, f in enumerate(folds)]
    ch = napmodel.final_choice(folds, sel)
    assert ch["classifier"] == "lr" and ch["C"] == 0.1


def test_grid_points_set_the_nap_decoder_only():
    pts = napmodel.grid_points({"estimator": "HYBRID", "hybrid": {"emissionWeight": 0.5, "durationModel": "EXPLICIT",
                                                                  "timePriorWeight": 0.0}})
    assert len(pts) == 32 and pts[0]["hybrid"] == {"emissionWeight": 1.0, "timePriorWeight": 1.0,
                                                   "durationModel": "EXPLICIT"}
    assert all(p["params"]["hybrid"]["emissionWeight"] == 0.5 for p in pts)
    assert pts[5]["params"]["napMode"]["hybrid"] == pts[5]["hybrid"]


def test_the_parity_check_fails_when_the_features_come_from_other_sessions(tmp_path):
    # The training rows are the algo-cli features of the session file; the decodes run on the proxies themselves.
    sessions = [_night([(1, 30)], nid="walch-7-inst-nap30"), _night([(1, 60)], nid="walch-7-inst-nap60")]
    path = napmodel.write_sessions(tmp_path / "proxies.jsonl", sessions)
    napmodel.check_sessions_file(path, sessions)
    with pytest.raises(napmodel.NapModelError, match="parity"):
        napmodel.check_sessions_file(path, sessions[:1])                      # a session missing
    changed = [dict(sessions[0], sessionEndMs=sessions[0]["sessionEndMs"] - MIN), sessions[1]]
    with pytest.raises(napmodel.NapModelError, match="parity"):
        napmodel.check_sessions_file(path, changed)                           # a session cut differently
    with pytest.raises(napmodel.NapModelError, match="parity"):
        napmodel.check_sessions_file(tmp_path / "missing.jsonl", sessions)    # no session file


# -------------------------------------------------------------------------------------- end to end (fake) ----

@pytest.fixture(scope="module")
def runs(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("napmodel")
    write_dataset(tmp / "nights", n_walch=6, n_bid=3, long_wakes=True, shared_labels=True)
    mp = pytest.MonkeyPatch()
    (tmp / "bin").mkdir()
    mp.setenv("ALGO_CLI", str(make_launcher(tmp / "bin", f'exec "{sys.executable}" -I "{FAKE}" "$@"')))
    out = tmp / "out"
    nights = tmp / "nights"
    try:
        assert train.main(["run", "--run", "b", "--out-root", str(out), "--nights-root", str(nights),
                           "--variants", "instantaneous,mean2min", "--configs", "walch+bid-zero", "--classifiers",
                           "mlp", "--estimators", "HYBRID,RULES,HMM-table", "--selection", "per-group", "--keep-inner",
                           "--jobs", "4", "--inner-k", "3", "--mlp-max-iter", "30", "--time-prior-min-count", "20",
                           "--created-at", "2026-01-02T03:04:05Z"]) == 0
        assert train.main(["bias", "--run", "b", "--out-root", str(out), "--jobs", "4"]) == 0
        base = out / "bf"
        b = bundle.with_stage_log_bias(read_json(out / "b" / "final" / "hybrid_model.json"), [0.1, 0.2, -0.3, 0.0])
        b = bundle.with_stage_log_bias_short(b, [0.0, 0.3, 0.1, -0.4])
        bundle.write_bundle(base / "final" / "hybrid_model.json", b)
        write_json(base / "trained_defaults.json", {"estimator": "HYBRID", "hybrid": {
            **read_json(out / "b" / "summary.json")["selection"]["decoderParams"]["params"], "priorExponent": 1.0},
            "napMode": {"maxSessionMin": 180.0}})
        write_json(base / "summary.json", read_json(out / "b" / "summary.json"))
        assert train.main(["naps", "--run", "b", "--out-root", str(out), "--out-run", "naps", "--nights-root",
                           str(nights), "--context-run", "b", "--jobs", "4"]) == 0
        assert train.main(["nap-model", "--run", "b", "--out-root", str(out), "--out-run", "nm", "--nights-root",
                           str(nights), "--jobs", "4"]) == 0
        yield out, nights
    finally:
        mp.undo()


def test_experiment_passes_the_gate_and_keeps_every_night(runs):
    out, _ = runs
    s = read_json(out / "nm" / "summary.json")
    g = s["meta"]["gate"]
    assert g["passed"] and g["nights"] == g["nightsIdentical"] == g["storedNights"] > 0
    assert set(g["byLength"]) == {"30", "60", "90", "120"}
    assert all(q["identical"] == q["sessions"] > 0 for q in g["byLength"].values())
    for cand in ("N1", "N2"):
        q = s["meta"]["nightsUnchanged"][cand]
        assert q["identical"] == q["nights"] == g["nights"]
    assert all(p["nDiffering"] == 0 and p["onsetProxies"] > 0 for p in s["meta"]["featureParity"])
    assert s["meta"]["prereg"] == napmodel.PREREG_PATH and s["meta"]["preregSha256"] == napmodel.PREREG_SHA256
    assert s["meta"]["code"]["files"]["pulsetrain/napmodel.py"]


def test_every_candidate_and_kind_is_scored_and_the_rules_are_applied(runs):
    out, _ = runs
    s = read_json(out / "nm" / "summary.json")
    assert set(s["cells"]) == set(napmodel.NAMES)
    w = s["cells"]["N1"]["walch"]["instantaneous"]
    assert {"all", "onset", "ew", *(f"onset-{L}" for L in napmodel.ONSET_LENGTHS)} <= set(w)
    assert w["all"]["sessions"] == w["onset"]["sessions"] + w["ew"]["sessions"] and w["ew"]["sessions"] > 0
    assert s["decision"]["adopted"] in napmodel.CANDIDATES and s["policy"]["policy"] in napmodel.POLICIES
    assert set(s["scores"]) == set(napmodel.NAMES)
    al = s["cells"][napmodel.LIGHT_KIND]["walch"]["mean2min"]["all"]
    n0 = s["cells"]["N0"]["walch"]["mean2min"]["all"]
    assert al["accuracy"] == pytest.approx(n0["lightAccuracy"]) and al["kappaPooled"] == pytest.approx(0.0)
    pairs = {(x["a"], x["b"]) for x in s["comparisons"]}
    assert set(napmodel.PAIRS) <= pairs
    assert all(x["accuracy"]["n"] > 0 for x in s["comparisons"] if x["part"] == "all" and x["dataset"] == "walch")
    md = (out / "nm" / "report.md").read_text()
    assert "## Rule (i)" in md and "## Rule (ii)" in md and "## Inner selection" in md and "BIDSleep" in md


def test_inner_selection_never_uses_the_group_and_folds_are_loso(runs):
    out, _ = runs
    sel = read_json(out / "nm" / "selections.json")["selections"]
    folds = read_json(out / "nm" / "folds.json")["folds"]
    dec = {(d["variant"], d["key"]): d for d in read_json(out / "b" / "bias" / "decisions.json")["groups"]}
    for q in sel:
        held = set(dec[(q["variant"], q["key"])]["heldout"])
        assert not held & set(q["evalSubjects"])
        assert q["chosen"]["classifier"] in napmodel.CLASSIFIER_ORDER and len(q["grid"]["lr"]) == 32
        assert abs(sum(q["napBias"]["bias"])) < 1e-9 and q["napBias"]["decodes"] <= 8
        assert (q["chosen"]["C"] is None) == (q["chosen"]["classifier"] == "mlp")
    assert len(folds) == sum(len(d["heldout"]) for d in dec.values())
    for f in folds:
        b = bundle.read_bundle(out / "nm" / "outer" / f["variant"] / "nap" / f"{f['subject']}.json")
        assert f"held out: {f['subject']}" in b["training"]["notes"] and "stageLogBias" not in b
        assert b["classifier"]["type"] == ("logreg" if f["classifier"] == "lr" else "mlp")


def test_records_and_proxies(runs):
    out, nights = runs
    recs = [json.loads(x) for x in (out / "nm" / "results.jsonl").read_text().splitlines()]
    names = {r["candidate"] for r in recs}
    assert names == {"N0", "N1", "N2", "RULES", "HMM-table"}
    for r in recs:
        if r["proxy"] == "ew":
            assert 30 <= r["L"] <= 179 and "-ew" in r["night"]
        elif r["proxy"] == "onset":
            assert r["L"] in napmodel.ONSET_LENGTHS
    by = {(r["candidate"], r["variant"], r["night"]) for r in recs}
    for r in recs:
        if r["candidate"] == "N1":
            assert ("N0", r["variant"], r["night"]) in by and ("N2", r["variant"], r["night"]) in by
    counts = read_json(out / "nm" / "proxies" / "counts.json")
    assert counts["instantaneous"]["walch"]["ew"] == counts["mean2min"]["walch"]["ew"] > 0


def test_rerender_reproduces_the_summary(runs):
    out, _ = runs
    before = (out / "nm" / "summary.json").read_text()
    assert train.main(["nap-model-report", "--out-root", str(out), "--out-run", "nm"]) == 0
    assert (out / "nm" / "summary.json").read_text() == before


def test_final_model_defaults_and_verification(runs, tmp_path):
    out, nights = runs
    path = out / "nm" / "summary.json"
    orig = path.read_text()
    s = json.loads(orig)
    try:
        for adopted in ("N1", "N2", "N0"):
            s["decision"]["adopted"] = adopted
            write_json(path, s)
            name = f"nmf-{adopted}"
            assert train.main(["nap-model-final", "--run", "b", "--exp", "nm", "--out-root", str(out), "--out-run",
                               name, "--base-run", "bf", "--nights-root", str(nights), "--jobs", "4",
                               "--created-at", "2026-01-03T00:00:00Z"]) == 0
            fb = bundle.read_bundle(out / name / "final" / "hybrid_model.json")
            base = bundle.read_bundle(out / "bf" / "final" / "hybrid_model.json")
            td = read_json(out / name / "trained_defaults.json")
            assert td["napMode"]["outputPolicy"] == s["policy"]["policy"] and td["napMode"]["maxSessionMin"] == 180.0
            src = kotlin_defaults.render_run(out / name)
            assert f"val NAP_OUTPUT_POLICY: NapOutputPolicy = NapOutputPolicy.{s['policy']['policy']}" in src
            if adopted == "N0":
                assert fb == base and "hybrid" not in td["napMode"]
                assert "val NAP_HYBRID_DURATION_MODEL: DurationModel? = null" in src
                continue
            res = read_json(out / name / "final" / "result.json")
            assert {k: v for k, v in fb.items() if k not in ("nap", "modelId", "training")} == {
                k: v for k, v in base.items() if k not in ("modelId", "training")}
            assert fb["nap"]["featureNames"] == base["featureNames"] and fb["modelId"].endswith(name)
            assert ("stageLogBias" in fb["nap"]) == (adopted == "N1")
            if adopted == "N1":
                assert fb["nap"]["stageLogBias"] == res["napBias"] and abs(sum(res["napBias"])) < 1e-9
            assert td["napMode"]["hybrid"] == res["hybrid"]
            assert f"DurationModel.{res['hybrid']['durationModel']}" in src
            assert fb["nap"]["training"]["subjects"] == 9
            # the verification with the same (fake) build: nights unchanged, proxies reproduced
            v_out = tmp_path / f"verify-{adopted}"
            assert train.main(["nap-model-verify", "--run", "b", "--exp", "nm", "--final", name, "--out-root",
                               str(out), "--out-run", v_out.name, "--previous",
                               str(out / "bf" / "final" / "hybrid_model.json"), "--nights-root", str(nights),
                               "--jobs", "4"]) == 0
            v = read_json(out / v_out.name / "summary.json")
            assert v["foldNights"] == v["foldNightsIdenticalToB"] > 0 and v["proxies"] == v["proxiesIdentical"] > 0
            assert v["installedVsPrevious"]["identical"] == v["installedVsPrevious"]["nights"] > 0
            assert v["installedVsPrevious"]["perEpochFilesIdentical"] == 2
            assert all(x["hybrid"] > 0 for x in v["finalBundle"]["resubstitution"])
    finally:
        path.write_text(orig)


def test_verification_fails_when_a_night_changes(runs, tmp_path):
    out, nights = runs
    path = out / "nm" / "summary.json"
    orig = path.read_text()
    s = json.loads(orig)
    try:
        s["decision"]["adopted"] = "N2"
        write_json(path, s)
        assert train.main(["nap-model-final", "--run", "b", "--exp", "nm", "--out-root", str(out), "--out-run",
                           "nmf-x", "--base-run", "bf", "--nights-root", str(nights), "--jobs", "4"]) == 0
        other = bundle.with_stage_log_bias(read_json(out / "bf" / "final" / "hybrid_model.json"), [0.5, 0.0, -0.5, 0.0])
        bundle.write_bundle(tmp_path / "other.json", other)
        assert train.main(["nap-model-verify", "--run", "b", "--exp", "nm", "--final", "nmf-x", "--out-root", str(out),
                           "--out-run", "verify-x", "--previous", str(tmp_path / "other.json"), "--nights-root",
                           str(nights), "--jobs", "4"]) == 2
    finally:
        path.write_text(orig)
