"""Nap-proxy evaluation (pulsetrain/naps.py): proxies, per-session metrics, the candidate assembly and the
pre-registered rule, and the experiment end to end on a tiny run of the FAKE algo-cli (tests/fake_algo_cli.py,
which implements stageLogBias, stageLogBiasShort, the nap section and napMode.maxSessionMin like core-algo)."""

import json
import shutil
import sys

import numpy as np
import pytest

import train
from conftest import FAKE, make_launcher
from pulsetrain import bundle, naps
from pulsetrain.util import read_json, write_json
from synthnights import write_dataset

MIN = 60_000


def _night(minutes=240):
    s0 = 600_000
    return {"id": "walch-1-inst", "subject": "1", "variant": "instantaneous", "sessionStartMs": s0,
            "sessionEndMs": s0 + minutes * MIN,
            "hr": [[s0 - 31 * MIN, 70.0], [s0 - 30 * MIN, 69.0], [s0, 60.0], [s0 + 30 * MIN, 58.0],
                   [s0 + 30 * MIN + 1, 57.0], [s0 + 200 * MIN, 55.0]],
            "steps": [[s0 - 31 * MIN, s0 - 30 * MIN, 9], [s0 - 2 * MIN, s0 - MIN, 4], [s0 + 29 * MIN, s0 + 30 * MIN, 3],
                      [s0 + 29 * MIN + 30_000, s0 + 30 * MIN + 30_000, 5]],
            "labels": [[s0 + k * 30_000, s0 + (k + 1) * 30_000, "LIGHT"] for k in range(2 * minutes)]}


# ------------------------------------------------------------------------------------------- pure helpers ----

def test_nap_proxy_keeps_the_context_before_and_nothing_after_the_end():
    n = _night()
    p = naps.nap_proxy(n, 30)
    s0 = n["sessionStartMs"]
    assert p["id"] == "walch-1-inst-nap30" and p["subject"] == "1" and p["variant"] == "instantaneous"
    assert (p["sessionStartMs"], p["sessionEndMs"]) == (s0, s0 + 30 * MIN)
    assert [h[0] for h in p["hr"]] == [s0 - 30 * MIN, s0, s0 + 30 * MIN]          # [start - 30 min, end]
    assert p["steps"] == [[s0 - 2 * MIN, s0 - MIN, 4], [s0 + 29 * MIN, s0 + 30 * MIN, 3]]  # ending in (c0, end]
    assert len(p["labels"]) == 60 and all(a >= s0 and b <= s0 + 30 * MIN for a, b, _ in p["labels"])
    assert naps.nap_proxy(n, 240)["sessionEndMs"] == n["sessionEndMs"]
    assert naps.nap_proxy(n, 241) is None                                          # longer than the night
    with pytest.raises(naps.NapError, match="unexpected night fields"):
        naps.nap_proxy({**n, "spo2": [[s0, 97.0]]}, 30)


def test_session_metrics():
    c = [[2, 0, 0, 2], [0, 10, 4, 2], [0, 0, 4, 0], [0, 2, 0, 6]]
    t, p = naps.session_shares(c)
    assert np.allclose(t, [4 / 32, 16 / 32, 4 / 32, 8 / 32]) and np.allclose(p, [2 / 32, 12 / 32, 8 / 32, 10 / 32])
    assert naps.share_error(c) == pytest.approx(100 * (2 + 4 + 4 + 2) / 32)
    assert naps.session_accuracy(c) == pytest.approx(22 / 32)
    assert naps.share_error(np.zeros((4, 4))) is None and naps.session_accuracy(np.zeros((4, 4))) is None


def test_candidate_kinds_and_threshold():
    assert naps.short_session(179.9, 180) and not naps.short_session(180, 180) and not naps.short_session(120, 120)
    got = {(c, L): naps.candidate_kind(c, L) for c in naps.CANDIDATES for L in (30, 90, 120, 180, 400)}
    assert {k for k, v in got.items() if v != "A"} == {
        ("B-120", 30), ("B-120", 90), ("B-180", 30), ("B-180", 90), ("B-180", 120),
        ("C-120", 30), ("C-120", 90), ("C-180", 30), ("C-180", 90), ("C-180", 120)}
    assert got[("B-180", 120)] == "BASE" and got[("C-120", 90)] == "C-120" and got[("C-180", 120)] == "C-180"


def test_expected_warnings_of_a_build_that_skips_the_sanity_check_on_short_sessions():
    assert naps.sanity_check_min_session_min({"params": {"post": {"proportionsMinSessionMin": 180.0}}}) == 180.0
    assert naps.sanity_check_min_session_min({"params": {"post": {}}}) == 0.0          # builds before the rule
    # builds with the two processing modes: naps (napMode.maxSessionMin) are not checked unless checkProportions
    assert naps.sanity_check_min_session_min({"params": {"napMode": {"maxSessionMin": 180.0}}}) == 180.0
    assert naps.sanity_check_min_session_min({"params": {"napMode": {"maxSessionMin": 120.0, "post": {
        "checkProportions": False}}, "post": {"proportionsMinSessionMin": 999.0}}}) == 120.0
    assert naps.sanity_check_min_session_min({"params": {"napMode": {"maxSessionMin": 180.0, "post": {
        "checkProportions": True}}}}) == 0.0
    assert naps.sanity_check_min_session_min({"params": {"napMode": {"maxSessionMin": 0.0}}}) == 0.0
    assert naps.sanity_check_min_session_min({}) == 0.0
    stored = ["LOW_HR_RANGE", "SANITY_DEEP_LOW", "DATA_GAPS", "SANITY_REM_HIGH"]
    assert naps.expected_warnings(stored, 120 * MIN, 180.0) == ["LOW_HR_RANGE", "DATA_GAPS"]
    assert naps.expected_warnings(stored, 180 * MIN, 180.0) == stored                  # at least as long: checked
    assert naps.expected_warnings(stored, 180 * MIN - 1, 180.0) == ["LOW_HR_RANGE", "DATA_GAPS"]
    assert naps.expected_warnings(stored, 30 * MIN, 0.0) == stored                     # 0 = every session
    assert naps.expected_warnings(stored, 29_999, 0.5) == ["LOW_HR_RANGE", "DATA_GAPS"]  # whole ms, rounded up
    assert naps.expected_warnings(stored, 30_000, 0.5) == stored
    assert naps.expected_warnings(["SANITY_DEEP_HIGH"], 60 * MIN, 90.0) == []


def test_adoption_rule():
    s = {"A": {"accWorse": 0.413, "EWorse": 93.1}, "B-120": {"accWorse": 0.353, "EWorse": 60.0},
         "B-180": {"accWorse": 0.404, "EWorse": 80.0}, "C-120": {"accWorse": 0.473, "EWorse": 75.5},
         "C-180": {"accWorse": 0.488, "EWorse": 75.53}}
    d = naps.adopt(s)
    # B-120 has the lowest E but loses more than 0.01 accuracy; C-120 / C-180 tie on E (< 0.05 pp) -> accuracy
    assert d["qualifying"] == ["A", "B-180", "C-120", "C-180"] and d["tied"] == ["C-120", "C-180"]
    assert d["adopted"] == "C-180" and d["threshold"] == pytest.approx(0.403)
    s["C-180"]["accWorse"] = 0.473
    assert naps.adopt(s)["adopted"] == "C-120"                     # full tie -> candidate order
    keep = {"A": {"accWorse": 0.5, "EWorse": 50.0}, "C-120": {"accWorse": 0.48, "EWorse": 10.0}}
    assert naps.adopt(keep)["adopted"] == "A"


def _rec(kind, night, subject, L, conf, variant="instantaneous", dataset="walch", warnings=()):
    return {"kind": kind, "night": night, "base": night.rsplit("-nap", 1)[0], "subject": subject, "dataset": dataset,
            "variant": variant, "L": L, "estimated": conf is not None, "reason": None if conf is not None else
            "NO_HR_DATA", "warnings": list(warnings), "confusion": conf, "group": "g"}


def test_assembly_cells_and_pooling():
    good = [[2, 0, 0, 0], [0, 6, 0, 0], [0, 0, 2, 0], [0, 0, 0, 0]]          # E 0, accuracy 1
    bad = [[0, 2, 0, 0], [0, 6, 0, 0], [0, 0, 0, 2], [0, 0, 0, 0]]           # AWAKE/DEEP missed: E 80, accuracy 0.6
    recs = []
    for kind in naps.HYBRID_KINDS:
        for subj in ("s1", "s2"):
            for L in naps.LENGTHS:
                conf = good if (kind == "C-180" or L == 180) else bad
                recs.append(_rec(kind, f"walch-{subj}-inst-nap{L}", subj, L, conf, warnings=(
                    ("SANITY_DEEP_LOW",) if conf is bad else ())))
    recs += [_rec(kind, "walch-s3-inst-nap30", "s3", 30, None) for kind in naps.HYBRID_KINDS]   # not estimated
    cand = naps.assemble([r for r in recs if r["kind"] in naps.HYBRID_KINDS])
    assert {r["candidate"] for r in cand} == set(naps.CANDIDATES)
    by = {(r["candidate"], r["night"]): r for r in cand}
    assert by[("C-180", "walch-s1-inst-nap120")]["kind"] == "C-180"
    assert by[("C-120", "walch-s1-inst-nap120")]["kind"] == "A"       # 120 is not < 120
    assert by[("B-180", "walch-s1-inst-nap180")]["kind"] == "A"
    cells = naps.cells_of(cand, "candidate", naps.CANDIDATES, ("instantaneous",))
    a30 = cells["A"]["walch"]["instantaneous"][30]
    assert a30["sessions"] == 3 and a30["notEstimated"] == 1 and a30["notEstimatedReasons"] == {"NO_HR_DATA": 1}
    assert a30["E"] == pytest.approx(80.0) and a30["accuracy"] == pytest.approx(0.6)
    assert a30["sanityAnyRate"] == pytest.approx(1.0) and a30["lowConfidenceRate"] == pytest.approx(1.0)
    c = cells["C-180"]["walch"]["instantaneous"]
    assert c["pooled"]["E"] == 0.0 and c["pooled"]["accuracy"] == 1.0 and c[30]["kappa"] == pytest.approx(1.0)
    c120 = cells["C-120"]["walch"]["instantaneous"]
    assert c120["pooled"]["E"] == pytest.approx(80.0) and c120[120]["E"] == pytest.approx(80.0)
    sc = naps.rule_scores(cells, ("instantaneous",))
    assert sc["C-180"]["EWorse"] == 0.0 and sc["A"]["accWorse"] == pytest.approx(0.6)
    assert sc["C-180"]["perLength"]["180"]["EWorse"] == 0.0
    assert naps.adopt(sc)["adopted"] == "C-180"
    comp = {(x["candidate"], x["L"]): x for x in naps.comparisons(cells, ("instantaneous",))}
    assert comp[("C-180", "pooled")]["E"]["meanDiff"] == pytest.approx(-80.0)
    assert comp[("C-120", 120)]["E"]["meanDiff"] == 0.0


# -------------------------------------------------------------------------------------- end to end (fake) ----

@pytest.fixture(scope="module")
def runs(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("naps")
    write_dataset(tmp / "nights", n_walch=6, n_bid=3)
    mp = pytest.MonkeyPatch()
    (tmp / "bin").mkdir()
    mp.setenv("ALGO_CLI", str(make_launcher(tmp / "bin", f'exec "{sys.executable}" -I "{FAKE}" "$@"')))
    out = tmp / "out"
    try:
        assert train.main(["run", "--run", "b", "--out-root", str(out), "--nights-root", str(tmp / "nights"),
                           "--variants", "instantaneous,mean2min", "--configs", "walch+bid-zero", "--classifiers",
                           "mlp", "--estimators", "HYBRID,RULES,HMM-table", "--selection", "per-group", "--keep-inner",
                           "--jobs", "4", "--inner-k", "3", "--mlp-max-iter", "30", "--time-prior-min-count", "20",
                           "--created-at", "2026-01-02T03:04:05Z"]) == 0
        assert train.main(["bias", "--run", "b", "--out-root", str(out), "--jobs", "4"]) == 0
        # a bias-final-like base run for naps-final: the run's final bundle with a b, defaults with beta 1
        base = out / "bf"
        b = bundle.with_stage_log_bias(read_json(out / "b" / "final" / "hybrid_model.json"), [0.1, 0.2, -0.3, 0.0])
        bundle.write_bundle(base / "final" / "hybrid_model.json", b)
        write_json(base / "trained_defaults.json", {"estimator": "HYBRID", "hybrid": {
            **read_json(out / "b" / "summary.json")["selection"]["decoderParams"]["params"], "priorExponent": 1.0}})
        write_json(base / "summary.json", read_json(out / "b" / "summary.json"))
        assert train.main(["naps", "--run", "b", "--out-root", str(out), "--out-run", "naps", "--nights-root",
                           str(tmp / "nights"), "--context-run", "b", "--jobs", "4"]) == 0
        yield out, tmp / "nights"
    finally:
        mp.undo()


def test_experiment_reproduces_the_nights_and_writes_every_candidate(runs):
    out, _ = runs
    s = read_json(out / "naps" / "summary.json")
    rp = s["repro"]
    assert rp["identical"] == rp["hybridNights"] > 0
    assert all(v["identical"] == v["nights"] for v in rp["contextCheck"].values()) and rp["contextCheck"]
    assert set(s["candidates"]) == set(naps.CANDIDATES) and set(s["scores"]) == set(naps.CANDIDATES)
    assert {"RULES", "HMM-table", "A", "BASE", "C-120", "C-180"} <= set(s["kinds"])
    w = s["candidates"]["A"]["walch"]["instantaneous"]
    assert set(w) == {*map(str, naps.LENGTHS), "pooled"} and w["30"]["sessions"] == 6
    assert s["decision"]["adopted"] in naps.CANDIDATES and "A" in s["decision"]["qualifying"]
    assert s["meta"]["prereg"] == naps.PREREG_PATH and s["meta"]["preregSha256"] == naps.PREREG_SHA256
    assert len(s["fits"]) == 2 * len([k for k in s["fits"] if k["T"] == 120]) > 0
    for f in s["fits"]:
        assert abs(sum(f["bias"])) < 1e-9 and f["decodes"] <= 8 and f["lengths"] == [x for x in naps.LENGTHS
                                                                                      if x < f["T"]]
    # whole nights and L >= T are A in every candidate (by construction)
    for c in naps.CANDIDATES:
        assert s["candidates"][c]["walch"]["mean2min"]["180"] == s["candidates"]["A"]["walch"]["mean2min"]["180"]
    md = (out / "naps" / "report.md").read_text()
    assert "## Night-level reproduction" in md and "## Decision" in md and "BIDSleep" in md and "b_short" in md


def test_inner_fits_never_use_the_group_and_proxies_have_no_post_session_data(runs):
    out, _ = runs
    fits = read_json(out / "naps" / "fits.json")["fits"]
    dec = read_json(out / "b" / "bias" / "decisions.json")["groups"]
    held = {(g["variant"], g["key"]): set(g["heldout"]) for g in dec}
    for f in fits:
        nights = [json.loads(x) for x in (out / "naps" / "fits" / f["variant"] / f["key"] / f"T{f['T']}"
                                          / "nights.jsonl").read_text().splitlines()]
        assert nights and not {n["subject"] for n in nights} & held[(f["variant"], f["key"])]
        for n in nights:
            assert n["id"].startswith("walch-")
            assert all(h[0] <= n["sessionEndMs"] for h in n["hr"]) and all(s[1] <= n["sessionEndMs"] for s in n["steps"])
            assert (n["sessionEndMs"] - n["sessionStartMs"]) / MIN < f["T"]


def test_rerender_reproduces_the_summary(runs):
    out, _ = runs
    d = out / "naps"
    before_s, before_md = (d / "summary.json").read_text(), (d / "report.md").read_text()
    try:
        naps.rerender(d)
        assert (d / "summary.json").read_text() == before_s and (d / "report.md").read_text() == before_md
        assert train.main(["naps-report", "--out-root", str(out), "--out-run", "naps"]) == 0
    finally:
        (d / "summary.json").write_text(before_s)
        (d / "report.md").write_text(before_md)


def test_final_model_and_implementation_check_for_an_adopted_c(runs, tmp_path):
    out, nights = runs
    path = out / "naps" / "summary.json"
    orig = path.read_text()
    s = json.loads(orig)
    s["decision"]["adopted"] = "C-180"                                    # force the candidate under test
    write_json(path, s)
    try:
        install = tmp_path / "assets" / "hybrid_model.json"
        assert train.main(["naps-final", "--run", "b", "--exp", "naps", "--out-root", str(out), "--out-run", "nf",
                           "--base-run", "bf", "--nights-root", str(nights)]) == 0
        inp = naps.Inputs(out / "b", nights)
        res = naps.final_model(inp, naps.AlgoCli.locate(), out / "naps", out / "bf", tmp_path / "nf2", install=install)
        b = bundle.read_bundle(tmp_path / "nf2" / "final" / "hybrid_model.json")
        assert b["stageLogBiasShort"] == res["fit"]["bias"] and abs(sum(b["stageLogBiasShort"])) < 1e-9
        base = bundle.read_bundle(out / "bf" / "final" / "hybrid_model.json")
        assert b["stageLogBias"] == base["stageLogBias"] and b["modelId"].endswith("-nf2")
        assert {k: v for k, v in b.items() if k not in ("stageLogBiasShort", "modelId", "training")} == {
            k: v for k, v in base.items() if k not in ("modelId", "training")}
        assert read_json(install) == b and "candidate C-180" in b["training"]["notes"]
        td = read_json(tmp_path / "nf2" / "trained_defaults.json")
        assert td["napMode"]["maxSessionMin"] == 180.0 and td["hybrid"]["priorExponent"] == 1.0
        assert "shortSessionMaxMin" not in td["hybrid"]
        from pulsetrain import kotlin_defaults
        src = kotlin_defaults.render_run(tmp_path / "nf2")
        assert "const val NAP_MAX_SESSION_MIN: Double = 180.0" in src and "C-180" in src
        # the fake implements the nap threshold like core-algo: with it as a default (and, like the experiment's
        # build, the sanity check on every session) the check passes
        v = naps.verify_implementation(inp, _FakeWithDefault(naps.AlgoCli.locate(), 180.0), out / "naps",
                                       tmp_path / "verify", final_bundle=tmp_path / "nf2" / "final" / "hybrid_model.json")
        assert v["nightsIdenticalToA"] == v["nights"] > 0 and v["proxiesIdenticalToCandidate"] == v["proxies"] > 0
        assert v["sanityCheckMinSessionMin"] == [0.0] and v["sanityCodesDropped"] == 0
        assert set(v["lowConfidenceRate"]["walch"]["instantaneous"]) == {"30", "60", "90", "120", "180", "pooled"}
        with pytest.raises(naps.NapError, match="implementation check failed"):
            naps.verify_implementation(inp, naps.AlgoCli.locate(), out / "naps", tmp_path / "verify0")  # default 0
        # a build that skips the sanity check below 180 min: a stored SANITY_* code of a shorter proxy is dropped,
        # every other difference still fails the check
        exp = tmp_path / "exp-sanity"
        shutil.copytree(out / "naps", exp)
        rows = [json.loads(x) for x in (exp / "results.jsonl").read_text().splitlines()]
        hit = next(r_ for r_ in rows if r_["kind"] == "C-180" and r_["L"] == 30 and r_["estimated"])
        hit["warnings"] = [*hit["warnings"], "SANITY_DEEP_LOW"]
        (exp / "results.jsonl").write_text("".join(json.dumps(x) + "\n" for x in rows))
        skips = _FakeWithDefault(naps.AlgoCli.locate(), 180.0, check_proportions=False)
        v = naps.verify_implementation(inp, skips, exp, tmp_path / "verify-skips")
        assert v["sanityCheckMinSessionMin"] == [180.0] and v["sanityCodesDropped"] == 1
        assert v["proxiesIdenticalToCandidate"] == v["proxies"]
        with pytest.raises(naps.NapError, match="1 proxies differ"):
            naps.verify_implementation(inp, _FakeWithDefault(naps.AlgoCli.locate(), 180.0), exp, tmp_path / "v-all")
        s["decision"]["adopted"] = "A"
        write_json(path, s)
        with pytest.raises(naps.NapError, match="kept"):
            naps.final_model(inp, naps.AlgoCli.locate(), out / "naps", out / "bf", tmp_path / "x")
    finally:
        path.write_text(orig)


class _FakeWithDefault:
    """The fake algo-cli with ``napMode.maxSessionMin`` defaulting to ``value`` (as a build whose TrainedDefaults
    carries it) and ``napMode.post.checkProportions`` to ``check_proportions`` (True = the nap experiment's build,
    which checked the stage shares of every session; False = the current default, no sanity check on naps, which the
    fake reports in its metrics but does not implement): merged into every params file the check writes."""

    def __init__(self, cli, value, check_proportions=True):
        self.cli, self.value, self.check = cli, value, check_proportions

    def fingerprint(self):
        return self.cli.fingerprint()

    def evaluate(self, nights, estimator, out, *, params=None, **kw):
        if params is not None:
            p = read_json(params)
            nap = p.setdefault("napMode", {})
            nap.setdefault("maxSessionMin", self.value)
            nap.setdefault("post", {}).setdefault("checkProportions", self.check)
            write_json(params, p)
        return self.cli.evaluate(nights, estimator, out, params=params, **kw)


def test_bundle_accepts_and_validates_the_short_session_bias(runs):
    out, _ = runs
    b = read_json(out / "b" / "final" / "hybrid_model.json")
    s = bundle.with_stage_log_bias_short(b, [0.1, -0.1, 0.2, -0.2])
    assert list(s)[-1] == "stageLogBiasShort"
    both = bundle.with_stage_log_bias(s, [0.0, 0.1, -0.1, 0.0])
    assert list(both)[-2:] == ["stageLogBias", "stageLogBiasShort"] and both["stageLogBiasShort"] == [0.1, -0.1, 0.2, -0.2]
    assert "stageLogBiasShort" not in bundle.with_stage_log_bias_short(s, None)
    with pytest.raises(bundle.BundleError, match="stageLogBiasShort must be 4"):
        bundle.validate_bundle({**b, "stageLogBiasShort": [0.0, 1.0]})


# ------------------------------------------------------------------------------ provenance and post hoc ----

def test_code_fingerprint_hashes_the_analysis_sources():
    import hashlib

    from pulsetrain.util import CODE_ROOT, code_fingerprint
    fp = code_fingerprint()
    assert {"train.py", "pulsetrain/naps.py", "pulsetrain/naps_posthoc.py", "pulsetrain/bias.py"} <= set(fp["files"])
    assert fp["files"]["pulsetrain/naps.py"] == hashlib.sha256((CODE_ROOT / "pulsetrain" / "naps.py").read_bytes()
                                                               ).hexdigest()
    assert len(fp["sha256"]) == 64 and fp == code_fingerprint()                  # deterministic
    assert fp["git"] is None or (len(fp["git"]["head"]) == 40 and isinstance(fp["git"]["modified"], list))


def test_runs_record_the_code_behind_them(runs):
    out, _ = runs
    from pulsetrain.util import code_fingerprint
    code = read_json(out / "naps" / "summary.json")["meta"]["code"]
    assert code["files"] == code_fingerprint()["files"]


def test_posthoc_helpers():
    from pulsetrain import naps_posthoc as ph
    a = _rec("A", "walch-s1-inst-nap30", "s1", 30, [[2, 1, 0, 1], [0, 6, 0, 2], [0, 0, 2, 0], [0, 0, 0, 0]])
    (light,) = ph.always_light([a, {**a, "kind": "C-180"}])
    c = np.asarray(light["confusion"])
    assert light["kind"] == ph.LIGHT_KIND and light["warnings"] == []
    assert c[:, 1].tolist() == [4, 8, 2, 0] and c.sum() == 14 and np.trace(c) == 8
    assert ph.other_c("C-180") == "C-120" and ph.other_c("C-120") == "C-180" and ph.other_c("A") is None

    def cell(pr, tr):
        return {"predShares": {"REM": pr}, "trueShares": {"REM": tr}}
    ref = {30: cell(0.30, 0.01), 60: cell(0.25, 0.01), 90: cell(0.20, 0.06), 120: cell(0.20, 0.09)}
    got = ph.rem_excess({30: cell(0.06, 0.01), 60: cell(0.16, 0.01), 90: cell(0.02, 0.06), 120: cell(0.04, 0.09)}, ref)
    assert got["perLength"]["30"] == pytest.approx(5.0) and got["sum"] == pytest.approx(5.0 + 15.0)
    assert got["remainingOfRef"]["60"] == pytest.approx(15 / 24) and got["underCalled"] == [90, 120]


def test_rerun_of_the_experiment_is_identical_and_compared_file_by_file(runs, tmp_path):
    out, nights = runs
    from pulsetrain import naps_posthoc as ph
    assert train.main(["naps", "--run", "b", "--out-root", str(out), "--out-run", "naps-rerun", "--nights-root",
                       str(nights), "--context-run", "b", "--jobs", "2"]) == 0
    cmp = ph.compare_rerun(out / "naps", out / "naps-rerun")
    assert cmp["allIdentical"] and set(cmp["identical"]) == {*ph.RERUN_FILES, "summary.json without meta"}
    bad = tmp_path / "bad"
    shutil.copytree(out / "naps-rerun", bad)
    (bad / "report.md").write_text((bad / "report.md").read_text() + "x")
    cmp = ph.compare_rerun(out / "naps", bad)
    assert not cmp["allIdentical"] and cmp["identical"]["report.md"] is False and cmp["identical"]["results.jsonl"]


def test_posthoc_decodes_between_lengths_and_reports_everything(runs, tmp_path):
    out, nights = runs
    from pulsetrain import naps_posthoc as ph
    path = out / "naps" / "summary.json"
    orig = path.read_text()
    s = json.loads(orig)
    s["decision"]["adopted"] = "C-180"                                    # force the candidate under test
    write_json(path, s)
    try:
        assert train.main(["naps-posthoc", "--run", "b", "--exp", "naps", "--out-root", str(out), "--out-run", "ph",
                           "--final", "nope", "--nights-root", str(nights), "--jobs", "2"]) == 0
        r = read_json(out / "ph" / "summary.json")
        assert r["meta"]["preRegistered"] is False and r["meta"]["adopted"] == "C-180"
        assert r["meta"]["code"]["files"]["pulsetrain/naps_posthoc.py"]
        b = r["between"]
        assert b["check"]["identical"] == b["check"]["sessions"] > 0 and b["lengths"] == list(ph.BETWEEN_LENGTHS)
        assert b["check"]["sanityCheckMinSessionMin"] == [0.0]                # the fake reports no such rule
        assert set(b["cells"]["A"]["walch"]["instantaneous"]) == {"120", "150", "170"}
        assert {x["L"] for x in b["comparisons"]} == {120, 150, 170}
        recs = [json.loads(x) for x in (out / "ph" / "results.jsonl").read_text().splitlines()]
        assert {r_["L"] for r_ in recs} == {120, 150, 170} and {r_["kind"] for r_ in recs} == {"A", "C-180"}
        assert r["threshold"]["reference"] == "C-120" and r["threshold"]["comparisons"]
        rc = r["ruleWithContext"]
        assert {"A", "C-180", "RULES", "HMM-table", ph.LIGHT_KIND} <= set(rc["scores"])
        assert rc["decision"]["adopted"] in rc["scores"]
        light = r["cells"]                                                # A / C-120 / C-180 with both kappas
        assert {"A", "C-120", "C-180"} == set(light) and "kappaPooled" in light["A"]["walch"]["mean2min"]["30"]
        assert "pooled" in light["A"]["walch"]["mean2min"] and r["remExcess"]["walch"]["A"]["total"] >= 0
        assert "finalFit" not in r                                       # --final nope: skipped
        md = (out / "ph" / "report.md").read_text()
        for h in ("## 1. Sessions between", "## 2. Threshold", "## 3. The pre-registered rule", "## 4.–6.",
                  "REM over-call"):
            assert h in md
        # without decodes (no algo-cli) the stored-results analyses still run
        r2 = ph.run_posthoc(naps.Inputs(out / "b", nights), None, out / "naps", tmp_path / "ph2")
        assert "between" not in r2 and r2["ruleWithContext"] == rc
        with pytest.raises(naps.NapError, match="differ from the stored"):
            bad = tmp_path / "exp-bad"
            shutil.copytree(out / "naps", bad)
            rows = [json.loads(x) for x in (bad / "results.jsonl").read_text().splitlines()]
            for row in rows:
                if row["kind"] == "A" and row["L"] == 120 and row["confusion"] is not None:
                    row["confusion"][0][0] += 1
                    break
            (bad / "results.jsonl").write_text("".join(json.dumps(x) + "\n" for x in rows))
            ph.decode_between(naps.Inputs(out / "b", nights), naps.AlgoCli.locate(), bad, tmp_path / "ph3", "C-180")
        s["decision"]["adopted"] = "A"
        write_json(path, s)
        with pytest.raises(naps.NapError, match="adopted C candidate"):
            ph.run_posthoc(naps.Inputs(out / "b", nights), None, out / "naps", tmp_path / "ph4")
    finally:
        path.write_text(orig)


def test_final_fit_quality_against_the_fold_fits(tmp_path):
    from pulsetrain import naps_posthoc as ph
    trace = [{"predShares": [0.2, 0.2, 0.3, 0.3], "accepted": True}, {"predShares": [0.0, 0.9, 0.1, 0.0],
                                                                      "accepted": False},
             {"predShares": [0.1, 0.6, 0.27, 0.03], "accepted": True}]
    write_json(tmp_path / "summary.json", {"naps": {"fit": {"bias": [-0.1, 0.4, 0.3, -0.6], "D": 0.13, "decodes": 3,
                                                            "bestIndex": 2, "predShares": [0.1, 0.6, 0.27, 0.03],
                                                            "trueShares": [0.1, 0.5, 0.3, 0.1], "trace": trace}}})
    fits = [{"T": 180, "D": 0.01, "bias": [-1.0, 0.1, 0.0, -0.9]}, {"T": 180, "D": 0.08, "bias": [0.1, 0.9, 0.8, -0.3]},
            {"T": 120, "D": 0.5, "bias": [5.0, 5.0, 5.0, -15.0]}]
    q = ph.final_fit_quality(tmp_path, fits, 180)
    assert q["foldD"] == [0.01, 0.08] and q["withinFoldRange"] and q["acceptedRemShares"] == [0.3, 0.03]
    assert q["allRemShares"] == [0.3, 0.0, 0.03] and q["foldBiasMean"] == pytest.approx([-0.45, 0.5, 0.4, -0.6])
