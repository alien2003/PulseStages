"""Stage-proportion experiment (pulsetrain/bias.py): the fixed point, the inner / adoption rules, and the experiment
end to end on a tiny run of the FAKE algo-cli (tests/fake_algo_cli.py, which implements priorExponent and
stageLogBias like core-algo)."""

import json
import sys

import numpy as np
import pytest

import train
from conftest import FAKE, make_launcher
from pulsetrain import bias, bundle
from pulsetrain.util import read_json, write_json
from synthnights import write_dataset


# ------------------------------------------------------------------------------------------------ helpers ----

def _toy_decoder(truth_counts=(60, 560, 140, 240), scale=1.0, seed=0):
    """decode(b) for 1000 epochs with fixed truth: predicted stage = argmax(score + scale * b), scores favour DEEP/REM
    (like the scaled likelihoods)."""
    rng = np.random.default_rng(seed)
    truth = np.repeat(np.arange(4), truth_counts)
    score = rng.normal(size=(truth.size, 4)) + 1.5 * np.eye(4)[truth] + np.array([0.0, -0.8, 0.6, 0.5])
    calls = []

    def decode(b):
        calls.append(np.array(b, float))
        pred = np.argmax(score + scale * np.asarray(b), axis=1)
        c = np.zeros((4, 4))
        np.add.at(c, (truth, pred), 1)
        return c

    return decode, calls


def test_shares_and_minutes_errors():
    c = np.array([[2, 0, 0, 2], [0, 10, 4, 2], [0, 0, 4, 0], [0, 2, 0, 6]], float)
    t, p = bias.shares(c)
    assert np.allclose(t, [4 / 32, 16 / 32, 4 / 32, 8 / 32]) and np.allclose(p, [2 / 32, 12 / 32, 8 / 32, 10 / 32])
    me = bias.stage_minutes_errors(c)
    assert me == {"AWAKE": 1.0, "LIGHT": 2.0, "DEEP": 2.0, "REM": 1.0}
    assert bias.night_error(c) == 6.0
    with pytest.raises(ValueError):
        bias.shares(np.zeros((4, 4)))


@pytest.mark.parametrize("scale", [0.5, 1.0, 3.0])
def test_fixed_point_matches_shares_deterministically(scale):
    decode, calls = _toy_decoder(scale=scale)
    fit = bias.fit_stage_bias(decode)
    assert len(calls) == fit["decodes"] <= bias.FP_MAX_DECODES
    assert fit["D"] < fit["trace"][0]["D"] / 3                 # much closer to the true shares than b = 0
    assert abs(sum(fit["bias"])) < 1e-12                        # centred
    accepted = [t for t in fit["trace"] if t["accepted"]]
    assert [t["D"] for t in accepted] == sorted((t["D"] for t in accepted), reverse=True)
    assert fit["trace"][fit["bestIndex"]]["bias"] == fit["bias"]
    assert all(t["eta"] is None or t["eta"] in (1.0, 0.5, 0.25, 0.125, 0.0625) for t in fit["trace"])
    again = bias.fit_stage_bias(_toy_decoder(scale=scale)[0])
    assert again["bias"] == fit["bias"] and again["trace"] == fit["trace"]


def test_fixed_point_backtracks_and_stops_early():
    decode, _ = _toy_decoder(scale=6.0)                        # very elastic: eta = 1 overshoots
    fit = bias.fit_stage_bias(decode, max_decodes=12)
    assert any(not t["accepted"] for t in fit["trace"])
    assert fit["D"] < fit["trace"][0]["D"]
    exact = np.diag([10.0, 50.0, 20.0, 20.0])                   # already matching shares: no update at all
    fit0 = bias.fit_stage_bias(lambda b: exact)
    assert fit0["decodes"] == 1 and fit0["bias"] == [0.0, 0.0, 0.0, 0.0]


def test_choose_beta_rule():
    pts = [{"beta": 1.0, "kappa": 0.30, "E": 200.0}, {"beta": 0.75, "kappa": 0.295, "E": 150.0},
           {"beta": 0.5, "kappa": 0.285, "E": 100.0}, {"beta": 0.25, "kappa": 0.31, "E": 100.03},
           {"beta": 0.0, "kappa": float("nan"), "E": 10.0}]
    # 0.285 < 0.30 - 0.01; 0.25 and 0.75 qualify; lowest E 100.03 (0.25)
    assert bias.choose_beta(pts, 0.30) == 3
    # with a looser margin 0.5 qualifies: E 100.0 and 100.03 tie (< 0.05 min) -> higher kappa (0.25)
    assert bias.choose_beta(pts, 0.30, margin=0.02) == 3
    pts[3]["kappa"] = 0.28
    assert bias.choose_beta(pts, 0.30, margin=0.02) == 2
    assert bias.choose_beta(pts, 0.5) is None
    tie = [{"beta": 1.0, "kappa": 0.3, "E": 5.0}, {"beta": 0.5, "kappa": 0.3, "E": 5.0}]
    assert bias.choose_beta(tie, 0.3) == 0                    # equal kappa -> larger beta


def test_adoption_rule():
    s = {"BASE": {"kappaWorse": 0.334, "EWorse": 160.0}, "A": {"kappaWorse": 0.326, "EWorse": 120.0},
         "B": {"kappaWorse": 0.320, "EWorse": 90.0}, "C": {"kappaWorse": 0.3245, "EWorse": 120.02}}
    d = bias.adopt(s)
    assert d["qualifying"] == ["BASE", "A", "C"] and d["tied"] == ["A", "C"] and d["adopted"] == "A"
    assert abs(d["threshold"] - 0.324) < 1e-12
    s["C"]["kappaWorse"] = 0.33
    assert bias.adopt(s)["adopted"] == "C"
    only_base = {"BASE": {"kappaWorse": 0.334, "EWorse": 160.0}, "B": {"kappaWorse": 0.2, "EWorse": 10.0}}
    assert bias.adopt(only_base)["adopted"] == "BASE"


def test_mode_beta():
    ch = [{"beta": 0.5, "innerKappa": 0.3}] * 3 + [{"beta": 1.0, "innerKappa": 0.4}] * 3 + [
        {"beta": 0.0, "innerKappa": 0.9}]
    assert bias.mode_beta(ch) == 1.0                           # tie 3 / 3 -> higher mean inner kappa
    assert bias.mode_beta(ch + [{"beta": 0.5, "innerKappa": 0.1}]) == 0.5


# -------------------------------------------------------------------------------------- end to end (fake) ----

@pytest.fixture(scope="module")
def run_dir(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("bias")
    write_dataset(tmp / "nights", n_walch=6, n_bid=3)
    mp = pytest.MonkeyPatch()
    (tmp / "bin").mkdir()
    mp.setenv("ALGO_CLI", str(make_launcher(tmp / "bin", f'exec "{sys.executable}" -I "{FAKE}" "$@"')))
    try:
        assert train.main(["run", "--run", "b", "--out-root", str(tmp / "out"), "--nights-root", str(tmp / "nights"),
                           "--variants", "instantaneous,mean2min", "--configs", "walch+bid-zero", "--classifiers",
                           "mlp", "--estimators", "HYBRID", "--selection", "per-group", "--keep-inner", "--jobs", "4",
                           "--inner-k", "3", "--mlp-max-iter", "30", "--time-prior-min-count", "20",
                           "--created-at", "2026-01-02T03:04:05Z"]) == 0
        assert train.main(["bias", "--run", "b", "--out-root", str(tmp / "out"), "--jobs", "4"]) == 0
        yield tmp / "out" / "b"
    finally:
        mp.undo()


def test_experiment_outputs(run_dir):
    s = read_json(run_dir / "bias" / "summary.json")
    assert set(s["cells"]) == set(bias.CANDIDATES)
    assert set(s["scores"]["walch"]) == set(bias.CANDIDATES)
    w = s["cells"]["BASE"]["walch"]["instantaneous"]
    assert w["subjects"] == 6 and abs(sum(w["predShares"].values()) - 1) < 1e-9
    assert set(w["minutesError"]) == {"AWAKE", "LIGHT", "DEEP", "REM"}
    assert w["E"] == pytest.approx(np.mean([sum(bias.stage_minutes_errors(json.loads(json.dumps(c))).values())
                                            for c in [r["confusion"] for r in _base_records(run_dir, "walch")]]))
    assert s["decision"]["adopted"] in bias.CANDIDATES and "BASE" in s["decision"]["qualifying"]
    assert {c["candidate"] for c in s["comparisons"]} == {"A", "B", "C"}
    assert {(c["candidate"], c["reference"]) for c in s["pairwise"]} == {("B", "A"), ("C", "A"), ("C", "B")}
    for x in s["pairwise"]:                                        # reporting-only B - A etc. from the same cells
        a, r = s["cells"][x["candidate"]][x["dataset"]][x["variant"]], s["cells"][x["reference"]][x["dataset"]][
            x["variant"]]
        assert x["E"]["meanDiff"] == pytest.approx(np.mean([a["subjectE"][k] - r["subjectE"][k] for k in a["subjectE"]]))
    assert s["meta"]["prereg"] == bias.PREREG_PATH and s["meta"]["preregSha256"] == bias.PREREG_SHA256
    for c in bias.CANDIDATES:
        assert sum(s["choiceCounts"][c].values()) == sum(s["betaChoices"][c].values())
    assert set(s["innerKappaBelowBase"]) == {"A", "B", "C"} and not s["innerKappaBelowBase"]["A"]
    md = (run_dir / "bias" / "report.md").read_text()
    assert "## Walch (PSG), headline" in md and "## Decision" in md and "BIDSleep" in md
    assert "SANITY_DEEP_LOW" in md and "between the corrections" in md and "more than 0.01 below BASE" in md
    dec = read_json(run_dir / "bias" / "decisions.json")
    assert dec["betas"] == list(bias.BETAS)
    for g in dec["groups"]:
        assert g["innerGrid"][0]["beta"] == 1.0 and len(g["innerFits"]) == len(bias.BETAS)
        assert g["choices"]["BASE"] == {"beta": 1.0, "bias": None, "innerKappa": g["innerBase"]["kappa"],
                                        "innerE": g["innerBase"]["E"]}
        assert g["choices"]["B"]["bias"] == g["innerFits"][0]["bias"]
        assert g["choices"]["A"]["beta"] in bias.BETAS and abs(sum(g["choices"]["B"]["bias"])) < 1e-9


def _base_records(run_dir, dataset):
    out = []
    for line in (run_dir / "bias" / "results.jsonl").read_text().splitlines():
        r = json.loads(line)
        if r["candidate"] == "BASE" and r["dataset"] == dataset and r["variant"] == "instantaneous" and r["estimated"]:
            out.append(r)
    return out


def test_base_reproduces_the_run_and_inner_data_never_contain_the_group(run_dir):
    base = {(r["variant"], r["night"]): r["confusion"] for r in map(json.loads, (run_dir / "bias" / "results.jsonl")
                                                                     .read_text().splitlines())
            if r["candidate"] == "BASE"}
    own = {(r["variant"], r["night"]): r["confusion"] for r in map(json.loads, (run_dir / "results.jsonl")
                                                                    .read_text().splitlines())}
    assert base == own
    for g in read_json(run_dir / "bias" / "decisions.json")["groups"]:
        sel = read_json(run_dir / "hybrid" / "walch+bid-zero" / g["variant"] / "mlp" / "selection" / f"{g['key']}.json")
        assert not set(sel["evalSubjects"]) & set(g["heldout"])
    log = (run_dir / "bias" / "commands.log").read_text()
    assert "--param-grid" in log and "priorExponent" not in log      # betas travel in the grid / params files


def test_rerender_reproduces_the_summary_from_stored_results(run_dir):
    out = run_dir / "bias"
    before_s, before_md = (out / "summary.json").read_text(), (out / "report.md").read_text()
    try:
        s = bias.rerender(run_dir)
        assert (out / "summary.json").read_text() == before_s and (out / "report.md").read_text() == before_md
        assert train.main(["bias-report", "--run", run_dir.name, "--out-root", str(run_dir.parent)]) == 0
        assert read_json(out / "summary.json") == s
    finally:
        (out / "summary.json").write_text(before_s)
        (out / "report.md").write_text(before_md)


def test_other_build_needs_explicit_opt_in_and_is_recorded(run_dir, monkeypatch):
    want = read_json(run_dir / "config.json")["algoCli"]["sha256"]
    real = bias.AlgoCli.fingerprint
    monkeypatch.setattr(bias.AlgoCli, "fingerprint", lambda self: {**real(self), "sha256": "0" * 64})
    with pytest.raises(bias.BiasError, match="allow-other-build"):
        bias.Experiment(run_dir)
    with pytest.raises(bias.BiasError, match="allow-other-build"):
        bias.final_model(run_dir, run_dir.parent / "never")
    s = bias.run_experiment(run_dir, jobs=2, out_name="bias-other", allow_other_build=True)
    assert s["meta"]["runAlgoCliSha256"] == want and s["meta"]["algoCli"]["sha256"] == "0" * 64
    # the same decoder (the fake) behind the other fingerprint: every candidate reproduces night by night
    assert (run_dir / "bias-other" / "results.jsonl").read_text() == (run_dir / "bias" / "results.jsonl").read_text()
    assert s["decision"] == read_json(run_dir / "bias" / "summary.json")["decision"]


def test_fallback_split_inner_losses_and_choice_counts():
    conf = {"x": [[1, 0, 0, 0], [0, 2, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]],      # E 0
            "y": [[0, 1, 0, 0], [0, 2, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]}      # AWAKE -0.5 min, LIGHT +0.5: E 1
    def rec(c, g, night, k):
        return {"candidate": c, "group": g, "night": night, "subject": night, "dataset": "walch",
                "variant": "instantaneous", "estimated": True, "confusion": conf[k]}
    recs = [rec(c, "g1", "n1", "y" if c in ("BASE", "C") else "x") for c in bias.CANDIDATES]
    recs += [rec(c, "g2", "n2", "y" if c == "B" else "x") for c in bias.CANDIDATES]
    def ch(beta, k, bias_=None, fallback=None):
        d = {"beta": beta, "bias": bias_, "innerKappa": k, "innerE": 0.0}
        return d | ({"fallback": fallback} if fallback else {})
    decisions = [
        {"variant": "instantaneous", "key": "g1", "heldout": ["n1"], "choices": {
            "BASE": ch(1.0, 0.30), "A": ch(0.5, 0.31), "B": ch(1.0, 0.28, [0.1, -0.1, 0, 0]),
            "C": ch(1.0, 0.30, fallback="no beta qualified: BASE")}},
        {"variant": "instantaneous", "key": "g2", "heldout": ["n2", "n3"], "choices": {
            "BASE": ch(1.0, 0.30), "A": ch(1.0, 0.30), "B": ch(1.0, 0.295, [0.1, -0.1, 0, 0]),
            "C": ch(1.0, 0.31, [0.2, -0.2, 0, 0])}}]
    fb = bias.fallbacks(recs, decisions, variants=("instantaneous",))
    assert set(fb) == {"C"} and fb["C"]["folds"] == 1 and fb["C"]["groups"][0]["key"] == "g1"
    q = fb["C"]["split"]["walch"]["instantaneous"]
    assert (q["fallbackNights"], q["otherNights"]) == (1, 1)
    assert q["EFallback"] == {"BASE": 1.0, "A": 0.0, "B": 0.0, "C": 1.0}
    assert q["EOther"] == {"BASE": 0.0, "A": 0.0, "B": 1.0, "C": 0.0}
    below = bias.inner_kappa_below_base(decisions)
    assert below == {"A": [], "B": [{"variant": "instantaneous", "key": "g1", "innerKappa": 0.28,
                                     "baseInnerKappa": 0.30}], "C": []}
    cc = bias.choice_counts(decisions)
    assert cc["C"] == {"1 (fallback)": 1, "1 + b": 2} and cc["B"] == {"1 + b": 3} and cc["BASE"] == {"1": 3}
    cells = {c: {"walch": {"instantaneous": {"subjectKappa": {"s1": k, "s2": k + 0.1},
                                              "subjectE": {"s1": e, "s2": e + 2}}}}
             for c, k, e in (("BASE", 0.3, 10.0), ("A", 0.35, 8.0), ("B", 0.33, 6.0), ("C", 0.34, 7.0))}
    pw = bias.pairwise_comparisons(cells, variants=("instantaneous",))
    got = {(x["candidate"], x["reference"]): (round(x["kappa"]["meanDiff"], 9), x["E"]["meanDiff"]) for x in pw}
    assert got == {("B", "A"): (-0.02, -2.0), ("C", "A"): (-0.01, -1.0), ("C", "B"): (0.01, 1.0)}


def test_final_model_selection_from_compares_choice_not_scores(run_dir, tmp_path):
    path = run_dir / "bias" / "summary.json"
    orig = path.read_text()
    s = json.loads(orig)
    s["decision"]["adopted"] = "B"
    write_json(path, s)
    try:
        other = read_json(run_dir / "summary.json")
        other["selection"]["classifier"]["scores"] = {"some+other-config": 0.1}     # a run with more configs
        write_json(tmp_path / "other.json", other)
        bias.final_model(run_dir, tmp_path / "ok", selection_from=tmp_path / "other.json")
        assert read_json(tmp_path / "ok" / "summary.json")["selection"]["classifier"]["scores"] == {
            "some+other-config": 0.1}
        other["selection"]["classifier"]["config"] = "walch"
        write_json(tmp_path / "bad.json", other)
        with pytest.raises(bias.BiasError, match="differs"):
            bias.final_model(run_dir, tmp_path / "bad", selection_from=tmp_path / "bad.json")
    finally:
        path.write_text(orig)


def test_final_model_for_proportion_matching(run_dir, tmp_path):
    s = read_json(run_dir / "bias" / "summary.json")
    s["decision"]["adopted"] = "B"                              # force the candidate under test
    write_json(run_dir / "bias" / "summary.json", s)
    install = tmp_path / "assets" / "hybrid_model.json"
    res = bias.final_model(run_dir, tmp_path / "bias-final", install=install, jobs=2)
    b = bundle.read_bundle(tmp_path / "bias-final" / "final" / "hybrid_model.json")
    assert b["stageLogBias"] == res["fit"]["bias"] and abs(sum(b["stageLogBias"])) < 1e-9
    assert b["modelId"] == "pulsestages-walch+bidsleep-mlp-bias-final" and "candidate B" in b["training"]["notes"]
    base = bundle.read_bundle(run_dir / "final" / "hybrid_model.json")
    assert {k: v for k, v in b.items() if k not in ("stageLogBias", "modelId", "training")} == {
        k: v for k, v in base.items() if k not in ("modelId", "training")}
    td = read_json(tmp_path / "bias-final" / "trained_defaults.json")
    assert td["hybrid"]["priorExponent"] == 1.0 and td["estimator"] == "HYBRID"
    assert read_json(install) == b and res["resubstitutionNights"] > 0
    from pulsetrain import kotlin_defaults
    src = kotlin_defaults.render_run(tmp_path / "bias-final")
    assert "const val HYBRID_PRIOR_EXPONENT: Double = 1.0" in src and "proportion matching" in src
    s["decision"]["adopted"] = "BASE"
    write_json(run_dir / "bias" / "summary.json", s)
    with pytest.raises(bias.BiasError, match="baseline was kept"):
        bias.final_model(run_dir, tmp_path / "x")
