"""Realistic nap estimates (pulsetrain/napsrealistic.py, docs/prereg/prereg-naps-realistic.md): the row weights, the
epoch matcher, the literature table fit, the sessions, the bundles and the gates on synthetic data, and the whole
pipeline (gates, experiment, report, final model, external step, installation, verification) on a small run of the
FAKE algo-cli (tests/fake_algo_cli.py, which implements napMode.contextMarginMin, the nap length table, duplicate-id
refusal and the per-epoch CSV like core-algo / algo-cli). Synthetic data only."""

import csv
import hashlib
import json
import math
import sys
from pathlib import Path

import numpy as np
import pytest

import train
from conftest import FAKE, make_launcher
from pulsetrain import bundle, ds006576 as ds, kotlin_defaults, litprior, napsrealistic as nr, perturb, pools, \
    realism
from pulsetrain.util import read_json, write_json
from synthnights import night_from_spec, write_dataset

MIN = 60_000
JVM_TEST_RECORD = nr.jvm_test_record          # the real one (the fake run of this module stands in for Gradle)


def _note_line(text: str) -> str:
    return f"{ds.now_stamp()} {text}\n"


# ---------------------------------------------------------------------------------------------- row weights ----

def test_subject_weights_give_each_dataset_half_and_each_subject_its_share():
    subject = np.array(["w1"] * 10 + ["w2"] * 30 + ["b1"] * 5 + ["b2"] * 20 + ["b3"] * 25, dtype=object)
    dataset = np.array(["walch"] * 40 + ["bidsleep"] * 50, dtype=object)
    w = nr.subject_weights(subject, dataset)
    assert w.mean() == pytest.approx(1.0)
    tot = w.sum()
    assert w[dataset == "walch"].sum() / tot == pytest.approx(0.5) and w[dataset == "bidsleep"].sum() / tot == \
        pytest.approx(0.5)
    for s, share in (("w1", 0.25), ("w2", 0.25), ("b1", 1 / 6), ("b2", 1 / 6), ("b3", 1 / 6)):
        assert w[subject == s].sum() / tot == pytest.approx(share)
        assert np.ptp(w[subject == s]) == 0                      # equal weight within a subject


# ------------------------------------------------------------------------------------------- epoch matcher ----

def _per_epoch_csv(path, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["grid_index", "night_id", "subject", "epoch", "start_ms", "has_data", "label", "stage", "p_awake",
                    "p_light", "p_deep", "p_rem"])
        for r in rows:
            w.writerow(r)
    return path


def test_matched_epochs_equal_realism_match_epochs(tmp_path):
    rng = np.random.default_rng(3)
    meta = {}
    rx, rr = [], []
    stages = ["AWAKE", "LIGHT", "DEEP", "REM"]
    for i, (pool, regime, L) in enumerate([("O", "EARLY", 60), ("M", "LATE", 60), ("W", "OTHER", 47.5),
                                           ("R", "LATE", 90), ("O", "EARLY", 90)]):
        sid = f"walch-{i % 2}-inst-s{i}"
        meta[sid] = {"kind": "eval", "pool": pool, "regime": regime, "L": L, "subject": str(i % 2)}
        for ep in range(12):
            label = "" if ep == 3 else stages[int(rng.integers(0, 4))]
            px = rng.dirichlet(np.ones(4)).round(3).tolist()
            pr = rng.dirichlet(np.ones(4)).round(3).tolist()
            rx.append([0, sid, i % 2, ep, ep * MIN, 0 if (ep == 5 and i == 1) else 1, label, "LIGHT", *px])
            if not (ep == 7 and i == 2):
                rr.append([0, sid, i % 2, ep, ep * MIN, 1, label, "DEEP", *pr])
    rx.append([0, "not-in-meta", 0, 0, 0, 1, "LIGHT", "LIGHT", 0.25, 0.25, 0.25, 0.25])
    rx.append([1, "walch-0-inst-s0", 0, 0, 0, 1, "REM", "LIGHT", 0.25, 0.25, 0.25, 0.25])    # another grid entry
    px_, pr_ = _per_epoch_csv(tmp_path / "x.csv", rx), _per_epoch_csv(tmp_path / "r.csv", rr)
    a = realism.match_epochs(realism.read_per_epoch(px_), realism.read_per_epoch(pr_), meta)
    b = nr.matched_epochs(nr.read_epochs([px_], set(meta)), nr.read_epochs([pr_], set(meta)), meta)
    for k in ("session", "subject", "length", "label", "p_x", "p_ref"):
        np.testing.assert_array_equal(getattr(a, k), getattr(b, k))
    assert a.excluded == b.excluded and set(a.excluded) == {"missingInOneModel", "noData", "noLabel"}
    for q in realism.AUC_GROUPS:
        np.testing.assert_array_equal(a.groups[q], b.groups[q])
    assert realism.auc_summary(a) == realism.auc_summary(b)
    bad = [list(r) for r in rr]
    bad[0][6] = "REM" if bad[0][6] != "REM" else "AWAKE"
    with pytest.raises(ValueError, match="labels differ"):
        nr.matched_epochs(nr.read_epochs([px_], set(meta)),
                          nr.read_epochs([_per_epoch_csv(tmp_path / "b.csv", bad)], set(meta)), meta)
    with pytest.raises(nr.NapsRealisticError, match="duplicate"):
        nr.read_epochs([px_, px_], set(meta))


# --------------------------------------------------------------------------------------- literature table ----

def _toy_records(rows_of, n_per=6, seed=0):
    """Records of ANCHOR sessions at every anchor for two subjects whose prediction responds to the table row of the
    session's length (argmax of fixed scores + the row)."""
    rng = np.random.default_rng(seed)
    sessions = []
    for L in nr.ANCHORS:
        for k in range(n_per):
            truth = rng.choice(4, size=40, p=[0.1, 0.5, 0.25, 0.15])
            score = rng.normal(size=(40, 4)) + 1.2 * np.eye(4)[truth] + np.array([0.0, -0.5, 0.6, 0.3])
            sessions.append((f"s{L}-{k}", f"subj{k % 3}", L, truth, score))
    calls = []

    def decode(table):
        calls.append(table)
        out = []
        for sid, subj, L, truth, score in sessions:
            row = np.asarray(bundle.length_bias_at(table, float(L)))
            pred = np.argmax(score + row, axis=1)
            c = np.zeros((4, 4))
            np.add.at(c, (truth, pred), 1)
            out.append({"night": sid, "subject": subj, "variant": "instantaneous", "L": float(L), "estimated": True,
                        "estimatorUsed": "HYBRID", "confusion": c.tolist(),
                        "labelCounts": np.bincount(truth, minlength=4).astype(float).tolist()})
        return out

    return decode, calls


def test_lit_table_fit_reuses_the_first_decode_and_fits_every_anchor(monkeypatch):
    monkeypatch.setattr(litprior, "FEASIBILITY_LAMBDAS", (0.5, 1.0))           # tiny toy pools: a coarse grid
    decode, calls = _toy_records(None)
    fit = nr.lit_table_fit(decode)
    assert len(calls) == fit["decodes"] - 1 + 1 <= 8                 # the first decode serves the fixed point's start
    assert all(not np.any(r) for r in calls[0]["bias"])
    assert fit["table"]["lengthsMin"] == [float(a) for a in nr.ANCHORS] and len(fit["table"]["bias"]) == 6
    for a, q in fit["anchors"].items():
        assert abs(sum(q["bias"])) < 1e-12 and q["D"] <= fit["trace"][0]["anchors"][a]["D"]
    assert set(fit["weights"]) == {"instantaneous"} and fit["sessions"] == {"instantaneous": 36}
    for a, w in fit["weights"]["instantaneous"].items():
        assert w["sessions"] == 6 and w["L"] == float(a)


def test_lit_table_fit_refuses_a_missing_anchor_or_a_changed_estimate(monkeypatch):
    monkeypatch.setattr(litprior, "FEASIBILITY_LAMBDAS", (0.5, 1.0))
    decode, _ = _toy_records(None)
    with pytest.raises(nr.NapsRealisticError, match="anchors"):
        nr.lit_table_fit(lambda t: [r for r in decode(t) if r["L"] != 150.0])
    state = {"n": 0}

    def flaky(t):
        recs = decode(t)
        state["n"] += 1
        if state["n"] > 1:
            recs[0] = dict(recs[0], estimatorUsed="RULES")
        return recs

    with pytest.raises(nr.NapsRealisticError, match="first decode"):
        nr.lit_table_fit(flaky)


# ------------------------------------------------------------------------------------------------ sessions ----

def test_perturbed_sessions_of_the_pre_registered_pools():
    spec = [("AWAKE", 5), ("LIGHT", 60), ("DEEP", 40), ("LIGHT", 60), ("AWAKE", 6), ("REM", 50), ("LIGHT", 120),
            ("AWAKE", 5)]
    w = night_from_spec(spec, night_id="walch-7-inst", subject="7")
    b = night_from_spec(spec, night_id="bid-01-1-inst", subject="bid-01", start_ms=1_600_000_000_000)
    ev = [*pools.eval_sessions(w), *pools.eval_sessions(b)]
    out = nr.perturbed_of(ev)
    ids = [s["id"] for s, _ in out]
    assert len(ids) == len(set(ids))
    by: dict = {}
    for s, m in out:
        base, name = perturb.original_of(s["id"])
        by.setdefault(base, set()).add(name)
        assert m["original"] == base and m["perturbation"] == name and m["kind"] == "perturbed"
    for s, m in ev:
        got = by.get(s["id"], set())
        if (m["pool"] == "O" or m["regime"] == "LATE") and m["L"] in (60, 90):
            assert got == set(perturb.NAMES)
        elif m["dataset"] == "walch":
            assert got == set(nr.G5_PERTURBATIONS)
        else:
            assert not got


def test_standalone_bundles_and_the_nap_level():
    rng = np.random.default_rng(0)
    from pulsetrain.models import ClassifierSpec, fit_classifier
    from pulsetrain import stats
    X = rng.normal(size=(600, 4))
    y = rng.integers(0, 4, size=600)
    tc = fit_classifier(X, y, ClassifierSpec(kind="lr"))
    seq = np.array(([0] * 5 + [1] * 20 + [2] * 15 + [1] * 10 + [3] * 12) * 4)
    ls = stats.fit_label_stats([seq], 1.0, stats.LabelStatsParams(time_prior_min_count=10))
    m = bundle.build_bundle(model_id="m", feature_set_version=1, feature_names=["a", "b", "c", "d"], classifier=tc,
                            label_stats=ls, hmm=None, training={k: (0 if k in ("subjects", "nights") else
                                                                    ([] if k == "variants" else "x"))
                                                                for k in bundle.TRAINING_KEYS})
    table = nr.zero_table()
    sa = nr.standalone(m, table=table)
    assert sa["nap"]["stageLogBiasByLength"] == table and "stageLogBias" not in sa["nap"] and "stageLogBias" not in sa
    sb = nr.standalone(m, [0.1, 0.0, -0.1, 0.0])
    assert sb["nap"]["stageLogBias"] == [0.1, 0.0, -0.1, 0.0] and "stageLogBiasByLength" not in sb["nap"]
    assert nr.statistics_of(m) == {k: m[k] for k in nr.STAT_KEYS}


# ------------------------------------------------------------------------------------------------- logging ----

def test_prereg_record_runlog_and_seed_note(tmp_path):
    log = tmp_path / "commands.log"
    rec = nr.prereg_record(log)
    assert rec["sha256"] == nr.PREREG_SHA256 == ds.PREREG_SHA256 and rec["sha256File"] and rec["noteInLog"] is False
    assert not rec["passed"]
    log.write_text(_note_line(nr.PREREG_NOTE))
    assert nr.prereg_record(log)["passed"] and nr.prereg_record(None)["passed"]
    rl = nr.RunLog(log, ["naps-realistic", "--out-run", "x y"])
    rl.start()
    rl.result("x", "rule (i): keep N1;\n multi  space")
    rl.note("something")
    rl.end(0)
    lines = log.read_text().splitlines()
    assert lines[1].endswith(" START python -I tools/train/train.py naps-realistic --out-run 'x y'")
    assert " RESULT x rule (i): keep N1; multi space" in lines[2] and " NOTE something" in lines[3]
    assert " END rc=0 " in lines[4] and all(ds.STAMP_RE.match(x) for x in lines)
    assert nr.logged_seeds(log) is None
    t = {"variant": "instantaneous", "timedSeeds": 20, "sessions": 61380, "seconds": 900.0, "calls": 5, "jobs": 16,
         "outputBytes": 10, "projectedSeconds": 9000.0, "seeds": 10}
    with open(log, "a") as f:
        f.write(_note_line("NOTE " + nr.timing_note(t)))
    assert nr.logged_seeds(log) == 10 and "-> seeds 0-9" in nr.timing_note(t) and "seeds 0-19," in nr.timing_note(t)


# ---------------------------------------------------------------------------------------------- G5, G6 ----

def _rec(model, night, conf, **kw):
    return {"model": model, "night": night, "variant": "instantaneous", "dataset": "walch", "kind": "eval",
            "estimated": True, "estimatorUsed": "HYBRID", "warnings": [], "confusion": conf, **kw}


def test_gate_g5_and_g6_logic():
    c1, c2 = [[1, 0, 0, 0]] * 4, [[0, 1, 0, 0]] * 4
    main = [_rec("C2", "a", c1), _rec("C2", "b", c1), _rec("N1", "a", c1)]
    pert = [_rec("C2", f"{x}~{p}", c1, original=x, perturbation=p, kind="perturbed")
            for x in ("a", "b") for p in nr.G5_PERTURBATIONS]
    assert nr.gate_g5(main, pert, ["C2"])["passed"]
    pert[1] = dict(pert[1], confusion=c2)
    g = nr.gate_g5(main, pert, ["C2"])
    assert not g["passed"] and g["decodes"]["C2|instantaneous|app+15steps"]["mismatches"] == ["a~app+15steps"]
    assert not nr.gate_g5(main, pert[:2], ["C2"])["passed"]                         # copies missing
    nights = {("instantaneous", "n1"): {"confusion": c1, "estimated": True, "warnings": []}}
    rec = _rec("C1", "n1", c1, kind="night", startMs=0, endMs=600 * MIN)
    assert nr.gate_g6([rec], nights, ["C1"])["passed"]
    assert not nr.gate_g6([dict(rec, warnings=["X"])], nights, ["C1"])["passed"]
    assert not nr.gate_g6([], nights, ["C1"])["passed"]


def test_cell_parts():
    assert nr.cell_parts({"kind": "eval", "pool": "O", "regime": "EARLY", "L": 60.0}) == [
        "EVAL", "pool:O", "regime:EARLY", "ANCHOR", "O-60", "EARLY-60"]
    assert nr.cell_parts({"kind": "eval", "pool": "W", "regime": "OTHER", "L": 47.5}) == ["EVAL", "pool:W",
                                                                                           "regime:OTHER"]
    assert nr.cell_parts({"kind": "midanchor", "L": 45.0}) == ["MIDANCHOR", "MIDANCHOR-45"]
    assert nr.cell_parts({"kind": "latestart", "delta": 10, "L": 60.0}) == ["LATESTART", "LATESTART-10x60"]
    assert nr.cell_parts({"kind": "night"}) == []


# --------------------------------------------------------------------------------------- the fake algo-cli ----

def test_fake_algo_cli_restricts_naps_and_refuses_a_wider_margin(fake_cli, tmp_path):
    from pulsetrain.algocli import AlgoCli, AlgoCliError
    cli = AlgoCli.locate()
    n = night_from_spec([("AWAKE", 5), ("LIGHT", 40), ("DEEP", 20)], night_id="walch-7-inst-nap65", subject="7",
                        hr_every_ms=60_000)
    s0 = n["sessionStartMs"]
    n["hr"] = [[t, 50.0 + (t // MIN) % 7] for t, _ in n["hr"] if not s0 <= t < s0 + 4 * MIN]   # a gap at the start
    nf = nr.write_sessions(tmp_path / "s.jsonl", [n])
    nf2 = nr.write_sessions(tmp_path / "s2.jsonl", [perturb.perturbed_copy(n, "noctx")[0]])
    p0 = write_json(tmp_path / "p0.json", {"napMode": {"maxSessionMin": 180, "contextMarginMin": 0}})
    a = cli.features(nf, tmp_path / "a.csv", params=p0).read_text().splitlines()
    b = cli.features(nf2, tmp_path / "b.csv", params=p0).read_text().splitlines()
    assert [x.split(",", 1)[1] for x in a] == [x.split(",", 1)[1] for x in b]
    c = cli.features(nf, tmp_path / "c.csv").read_text().splitlines()
    assert c != a                                                        # the context is read without the margin
    p31 = write_json(tmp_path / "p31.json", {"napMode": {"maxSessionMin": 180, "contextMarginMin": 31}})
    with pytest.raises(AlgoCliError) as e:
        cli.features(nf, tmp_path / "d.csv", params=p31)
    assert e.value.returncode == 65
    dup = nr.napmodel.write_sessions(tmp_path / "dup.jsonl", [n, n])
    with pytest.raises(AlgoCliError) as e:
        cli.evaluate(dup, "RULES", tmp_path / "m.json")
    assert e.value.returncode == 65
    m = cli.evaluate(nf, "RULES", tmp_path / "m.json", per_epoch=tmp_path / "pe.csv")
    rows = list(csv.DictReader(open(tmp_path / "pe.csv")))
    assert list(rows[0]) == ["grid_index", "night_id", "subject", "epoch", "start_ms", "has_data", "label", "stage",
                             "p_awake", "p_light", "p_deep", "p_rem"]
    assert len(rows) == 65 and m["perNight"][0]["id"] == n["id"] and set(nr.EPOCH_COLUMNS) <= set(rows[0])


# ----------------------------------------------------------------------------------- synthetic ds006576 ----

YASA_HR = {"W": 72.0, "N1": 64.0, "N2": 60.0, "N3": 56.0, "R": 66.0}


def _yasa_stages(group: str, rng) -> list[str]:
    """30-s YASA stages of a synthetic recording: quiet wake for WAKE, an afternoon-nap-like hypnogram otherwise."""
    if group == "WAKE":
        return ["W"] * 120
    spec = [("W", 16), ("N1", 6), ("N2", 30), ("N3", 40 if group == "NREM" else 30), ("N2", 20)]
    if group == "REM":
        spec += [("R", 30), ("N2", 40), ("N3", 20), ("R", 20)]
    spec += [("W", 12)]
    out = []
    for st, n in spec:
        out += [st] * (n + int(rng.integers(0, 4)))
    return out


def write_external(root, seed: int = 0, per_group: int = 3) -> tuple[str, str]:
    """A synthetic ds006576 data directory (night files of every window and variant, manifest.json with the YASA
    identity of the lock, yasa-summary.json); returns the two digests."""
    rng = np.random.default_rng(seed)
    files, subjects = {}, {}
    k = 200
    identity = {"version": ds.YASA_VERSION, "wheelSha256": ds.YASA_WHEEL_SHA256, "classifier": ds.YASA_CLASSIFIER,
                "classifierSha256": ds.YASA_CLASSIFIER_SHA256, "openmpSha256": ds.OPENMP_LIB_SHA256}
    for group in ("NREM", "REM", "WAKE"):
        for i in range(per_group):
            sub = f"sub-{k}"
            k += 1
            stages = _yasa_stages(group, rng)
            rec_ms = len(stages) * ds.EPOCH_MS
            truncated = group == "WAKE" and i == per_group - 1
            stage_raw = ds.stage_bytes({"subject": sub, "yasa": identity, "stages": stages})
            (root / ds.stage_relpath(sub)).parent.mkdir(parents=True, exist_ok=True)
            (root / ds.stage_relpath(sub)).write_bytes(stage_raw)
            subjects[sub] = {"group": group, "truncated": truncated, "excluded": None,
                             "stagesSha256": ds.sha256_bytes(stage_raw)}
            for w, (s0, s1) in ds.windows_from_stages(stages, (0, rec_ms), group).items():
                labels = ds.window_labels(stages, s0, s1)
                for v in ("instantaneous", "mean2min"):
                    hr = [[t, float(round(YASA_HR[stages[min(t // ds.EPOCH_MS, len(stages) - 1)]]
                                          + rng.normal(0, 1.5), 1))]
                          for t in range(max(0, s0 - 30 * MIN) + 60_000, min(rec_ms, s1 + 30 * MIN), 120_000)]
                    body = ds.night_bytes(ds.night_object(sub, w, v, s0, s1, hr, labels))
                    rel = ds.night_relpath(sub, w, v)
                    (root / rel).parent.mkdir(parents=True, exist_ok=True)
                    (root / rel).write_bytes(body)
                    files[rel] = ds.sha256_bytes(body)
    manifest = {"dataset": {"id": ds.DATASET}, "yasa": identity, "subjects": subjects,
                "files": dict(sorted(files.items())), "filesSha256": ds.digest_of_digests(files)}
    man = ds.write_json_atomic(root / ds.MANIFEST_NAME, manifest)
    summ = ds.write_json_atomic(root / ds.SUMMARY_NAME, {"dataset": ds.DATASET, "groups": {"NREM": {"n": per_group}}})
    return ds.sha256_bytes(man), ds.sha256_bytes(summ)


def external_log_lines(data, digests) -> str:
    """The extraction's lines of the commands log (section 13.6), in order."""
    return "".join(_note_line(x) for x in (
        f"START python -I tools/train/external.py fetch --data {data} --workers 4",
        f"START tools/train/.venv-external/bin/python -I tools/train/external_stage.py --data {data}",
        f"START python -I tools/train/external.py nights --data {data}",
        ds.NOTE_MANIFEST + digests[0], ds.NOTE_SUMMARY + digests[1]))


# -------------------------------------------------------------------------------------- end to end (fake) ----

def _force(out, name: str, model: str, tier: int):
    """A copy of the experiment ``naps-realistic`` with the decision forced to ``model`` (tier ``tier``) and its rule
    (ii) computed from the stored metrics (to exercise the final, external, install and verify paths)."""
    import shutil
    src, dst = out / "naps-realistic", out / name
    shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(src, dst)
    s = read_json(dst / "summary.json")
    rc = s["ruleI"]["candidates"][model]
    s["decision"].update({"adopted": model, "tier": tier, "decision": model, "qualifying": [model], "tied": [model]})
    m = s["metrics"][model]["walch"]
    s["ruleII"]["X"] = realism.rule_ii(model, rc["F"]["pass"] and tier == 1, rc["R"]["pass"],
                                       {v: m[v]["lit"] for v in m}, {v: m[v]["swKappa"]["kappa"] for v in m})
    write_json(dst / "summary.json", s)
    return dst


def _fake_probe(prev):
    """A stand-in for the fingerprint probe of the real build: the previous bundle has the base build's fingerprints,
    any other bundle its file digest as ``fingerprint()`` and the same night-model fingerprint."""
    def probe(cli, bundles, work):
        return {str(b): {"fingerprint": nr.BASE_FINGERPRINT if Path(b).read_bytes() == prev.read_bytes() else
                         hashlib.sha256(Path(b).read_bytes()).hexdigest(),
                         "nightModelFingerprint": nr.BASE_NIGHT_FINGERPRINT} for b in bundles}
    return probe


@pytest.fixture(scope="module")
def runs(tmp_path_factory):
    """The precursor runs of 13.10-13.12 on the FAKE algo-cli (bias-base like run, bias, naps, nap-model,
    nap-model-final with N1 forced), the base-build references of G2, a synthetic ds006576 directory, then
    naps-realistic-gates, the featureless dry run, the extraction's log lines and the experiment. Constants that depend
    on the real build or data are patched for the fake (feature set 7 with 5 names; 2 masked features; the previous
    bundle's digest; 13.12's stored decode count; one featureless seed and noise level; a coarse feasibility grid)."""
    tmp = tmp_path_factory.mktemp("naps-realistic")
    mp = pytest.MonkeyPatch()
    (tmp / "bin").mkdir()
    mp.setenv("ALGO_CLI", str(make_launcher(tmp / "bin", f'exec "{sys.executable}" -I "{FAKE}" "$@"')))
    mp.delenv("FAKE_ALGO_CLI_DEFAULTS", raising=False)
    nights, out = tmp / "nights", tmp / "out"
    try:
        write_dataset(nights, n_walch=6, n_bid=3, bid_nights=1, long_wakes=True, shared_labels=True, minutes=300)
        assert train.main(["run", "--run", "b", "--out-root", str(out), "--nights-root", str(nights), "--variants",
                           "instantaneous,mean2min", "--configs", "walch+bid-zero", "--classifiers", "mlp",
                           "--estimators", "HYBRID,RULES,HMM-table", "--selection", "per-group", "--keep-inner",
                           "--jobs", "8", "--inner-k", "3", "--mlp-max-iter", "30", "--time-prior-min-count", "20",
                           "--created-at", "2026-01-02T03:04:05Z"]) == 0
        assert train.main(["bias", "--run", "b", "--out-root", str(out), "--jobs", "8"]) == 0
        b = bundle.with_stage_log_bias(read_json(out / "b" / "final" / "hybrid_model.json"), [0.1, 0.2, -0.3, 0.0])
        b = bundle.with_stage_log_bias_short(b, [0.0, 0.3, 0.1, -0.4])
        bundle.write_bundle(out / "bf" / "final" / "hybrid_model.json", b)
        write_json(out / "bf" / "trained_defaults.json", {"estimator": "HYBRID", "hybrid": {
            **read_json(out / "b" / "summary.json")["selection"]["decoderParams"]["params"], "priorExponent": 1.0},
            "napMode": {"maxSessionMin": 180.0}})
        write_json(out / "bf" / "summary.json", read_json(out / "b" / "summary.json"))
        assert train.main(["naps", "--run", "b", "--out-root", str(out), "--out-run", "naps", "--nights-root",
                           str(nights), "--context-run", "b", "--jobs", "8"]) == 0
        assert train.main(["nap-model", "--run", "b", "--out-root", str(out), "--out-run", "nm", "--nights-root",
                           str(nights), "--jobs", "8"]) == 0
        p = out / "nm" / "summary.json"
        orig = p.read_text()
        s = json.loads(orig)
        s["decision"]["adopted"] = "N1"
        write_json(p, s)
        assert train.main(["nap-model-final", "--run", "b", "--exp", "nm", "--out-root", str(out), "--out-run", "nmf",
                           "--base-run", "bf", "--nights-root", str(nights), "--jobs", "8",
                           "--created-at", "2026-01-03T00:00:00Z"]) == 0
        p.write_text(orig)
        prev = out / "nmf" / "final" / "hybrid_model.json"
        stored = [json.loads(x) for x in (out / "nm" / "results.jsonl").read_text().splitlines()]
        mp.setattr(nr, "SHIPPED_BUNDLE_SHA256", hashlib.sha256(prev.read_bytes()).hexdigest())
        mp.setattr(nr, "G1_PER_VARIANT", sum(1 for r in stored if r["candidate"] == "N1" and r["variant"] ==
                                             "instantaneous" and r["proxy"] in ("onset", "ew")))
        mp.setattr(nr, "EXPECTED_FEATURE_SET_VERSION", 7)
        mp.setattr(nr, "EXPECTED_FEATURES", 5)
        mp.setattr(nr, "MASKED_FEATURES", ("stepsLog1p", "minutesSinceStart"))
        mp.setattr(nr, "FEATURELESS_SEED_CHOICES", (1, 20, 10))
        mp.setattr(pools, "FEATURELESS_SDS", (2,))
        mp.setattr(perturb, "NAMES", ("noctx", "amp0.5"))
        mp.setattr(litprior, "FEASIBILITY_LAMBDAS", (0.5, 1.0))
        # the fake launcher has no lib/*.jar and the fake tree no Gradle: G2's fingerprint probe and its run of the
        # core-algo tests are stood in for (their failures are tested separately)
        mp.setattr(nr, "probe_fingerprints", _fake_probe(prev))
        mp.setattr(nr, "jvm_test_record", lambda *a, **k: {"fake": True, "passed": True})
        base = out / "naps-realistic-base" / "ref" / "nights"
        from pulsetrain.algocli import AlgoCli
        cli = AlgoCli.locate()
        for v in ("instantaneous", "mean2min"):
            cli.evaluate(nights / v, "HYBRID", base / f"{v}.json", model=prev, per_epoch=base / f"{v}.per-epoch.csv")
        log = out / "commands.log"
        log.write_text(_note_line(nr.PREREG_NOTE))
        common = ["--out-root", str(out), "--run", "b", "--naps", "naps", "--nap-model", "nm", "--nights-root",
                  str(nights), "--previous", str(prev), "--jobs", "8"]
        rc = {"gates": train.main(["naps-realistic-gates", "--out-run", "naps-realistic-gates", *common]),
              "timing": train.main(["naps-realistic-timing", "--out-run", "timing", *common, "--seeds", "1"])}
        data = tmp / "ds006576"
        with open(log, "a") as f:
            f.write(external_log_lines(data, write_external(data)))
        rc["exp"] = train.main(["naps-realistic", "--out-run", "naps-realistic", *common, "--seeds", "1"])
        yield {"out": out, "nights": nights, "prev": prev, "common": common, "log": log, "data": data, "rc": rc,
               "tmp": tmp}
    finally:
        mp.undo()


def _log(runs) -> list[str]:
    return runs["log"].read_text().splitlines()


def test_gates_reproduce_13_12_and_fit_no_candidate(runs):
    out = runs["out"]
    assert runs["rc"]["gates"] == 0
    g = read_json(out / "naps-realistic-gates" / "gates.json")
    assert g["passed"] and all(g[k]["passed"] for k in ("G0", "G1", "G2", "G3a", "G4"))
    assert g["G1"]["nightsIdenticalToB"] == g["G1"]["storedNights"] == 18
    assert all(q["identical"] == q["stored"] == nr.G1_PER_VARIANT for q in g["G1"]["perVariant"].values())
    assert all(q["default"]["passed"] and q["margin0"]["passed"] for q in g["G2"]["variants"].values())
    assert g["G2"]["fingerprints"]["passed"] and g["G2"]["jvmTests"]["passed"]
    assert all(p["nDiffering"] == 0 and p["rowsCompared"] > 0 for p in g["G3a"]["parity"])
    assert {(p["variant"], p["params"]) for p in g["G3a"]["parity"]} == {
        (v, t) for v in ("instantaneous", "mean2min") for t in ("base", "margin0")}
    assert all(q["score"] == q["storedScore"] for q in g["G4"]["groups"])
    assert not (out / "naps-realistic-gates" / "selections.json").exists()       # nothing of C0 / C1 / C2 fitted
    lines = _log(runs)
    note = next(i for i, x in enumerate(lines) if nr.PREREG_NOTE in x)
    starts = [i for i, x in enumerate(lines) if " START " in x and "train.py naps-realistic" in x]
    assert starts and all(note < i for i in starts)
    assert any(" RESULT naps-realistic-gates G0, G1, G2, G3a passed; G4 passed" in x for x in lines)
    assert sum(1 for x in lines if " END rc=0 " in x and "naps-realistic-gates" in x) == 1


def test_featureless_dry_run_note_decides_the_seeds(runs, tmp_path):
    assert runs["rc"]["timing"] == 0
    t = read_json(runs["out"] / "timing" / "timing.json")
    assert t["seeds"] == 1 and t["sessions"] == 33 * sum(1 for s in range(6)) and t["calls"] > 0
    assert nr.logged_seeds(runs["log"]) == 1
    other = tmp_path / "commands.log"
    other.write_text(runs["log"].read_text())
    before = other.read_text()
    assert train.main(["naps-realistic", "--out-run", "refused", *runs["common"], "--log", str(other),
                       "--seeds", "20"]) == 2
    assert not (runs["out"] / "refused").exists()
    assert other.read_text() == before                    # refused before its START line: nothing logged
    # without --seeds the logged decision is used (the registered command has no --seeds)
    assert nr.experiment_preflight(other)["seeds"] == 1 and nr.experiment_preflight(other, 1)["seeds"] == 1


def test_experiment_passes_every_gate_and_records_both_rules(runs):
    out = runs["out"] / "naps-realistic"
    assert runs["rc"]["exp"] == 0
    s = read_json(out / "summary.json")
    g = s["gates"]
    assert g["passed"] and all(g[k]["passed"] for k in ("G0", "G1", "G2", "G3a", "G3b", "G4", "G5", "G6"))
    assert g["G6"]["models"].keys() == {"N1", "C0", "C1", "C2"}
    assert g["G5"]["passed"] and all(q["identical"] == q["sessions"] > 0 for q in g["G5"]["decodes"].values())
    assert g["G3b"]["exportMaxAbsDiff"] <= 1e-9 and not g["G3b"]["maskedProblems"]
    assert g["G7"]["diffs"]["instantaneous"]                               # toy data: counts differ from 4.6
    d = s["decision"]
    assert d["decision"] in ("keep N1", *nr.CANDIDATES) and set(s["ruleI"]["candidates"]) == {"C0", "C1", "C2"}
    assert s["ruleII"]["N1"]["policy"] in realism.POLICIES
    assert (s["ruleII"]["X"] is None) == (d["adopted"] is None)
    for c, q in s["ruleI"]["candidates"].items():
        assert {"D", "F", "F0", "R", "litDist", "tier1", "tier2"} <= set(q)
        assert {x["name"] for x in q["D"]["criteria"]} == {"D.ANCHOR.AWAKE", "D.ANCHOR.DEEP", "D.ANCHOR.REM",
                                                           "D.EARLY.DEEP", "D.LATE.REM"}
    assert set(s["auc"]) == {"C0", "C1", "C2"} and set(s["aucBidsleep"]) == {"C0", "C1", "C2"}
    for m in nr.MODELS:
        w = s["metrics"][m]["walch"]["instantaneous"]
        assert {"contrast", "featureless", "lateTail", "lit", "litMid", "swKappa", "sensitivity",
                "defaultScope"} <= set(w)
        assert set(w["featureless"]) == {str(L) for L in sorted(nr.ANCHORS + nr.MID_ANCHORS)}
        assert w["contrast"]["n"] > 0 and w["lit"]["litDist"] is not None
    assert all(isinstance(x, str) for x in s["reasons"])
    md = (out / "report.md").read_text()
    for h in ("## Gates", "## Rule (i)", "## Rule (ii)", "## Per-epoch AUC", "## Regime contrasts",
              "## Featureless sessions", "## Inner selection", "BIDSleep"):
        assert h in md
    lines = _log(runs)
    assert any(" RESULT naps-realistic rule (i): " in x for x in lines)


def test_records_sessions_and_per_epoch_outputs(runs):
    out = runs["out"] / "naps-realistic"
    every = [json.loads(x) for x in (out / "results.jsonl").read_text().splitlines()]
    recs = [r for r in every if r["kind"] not in ("perturbed", "featureless")]
    models = {r["model"] for r in recs}
    assert models == {*nr.MODELS, *nr.CONTEXT_ESTIMATORS}
    kinds = {(r["model"], r["kind"]) for r in recs}
    for m in nr.MODELS:
        assert {(m, k) for k in ("eval", "night", "tile", "latestart", "midanchor")} <= kinds
    assert {r["kind"] for r in recs if r["model"] in nr.CONTEXT_ESTIMATORS} == {"eval"}
    ids = [(r["model"], r["variant"], r["night"]) for r in recs]
    assert len(ids) == len(set(ids)) and all("labelCounts" in r for r in recs if r["kind"] != "night")
    fl = [r for r in every if r["kind"] == "featureless"]
    assert {r["dataset"] for r in fl} == {"synthetic"} and len(fl) == 4 * 2 * 6 * 33
    pert = [r for r in every if r["kind"] == "perturbed"]
    assert {r["perturbation"] for r in pert} == {"noctx", "amp0.5", "app+15steps"}
    assert all(r["night"] == perturb.perturbed_id(r["original"], r["perturbation"]) for r in pert)
    for m in nr.MODELS:
        for v in ("instantaneous", "mean2min"):
            files = nr.per_epoch_paths(out, m, v)
            assert len(files) == 3
            head = files[0].read_text().splitlines()[0]
            assert head.startswith("grid_index,night_id,subject,epoch")
    for v in ("instantaneous", "mean2min"):
        for kind in ("tile", "eval", "midanchor", "latestart", "perturbed"):
            assert (out / "sessions" / v / f"{kind}.jsonl").is_file()
    cs = read_json(out / "common-set.json")
    assert set(cs) == {"eval", "midanchor", "tile", "latestart"} and cs["eval"]["common"]
    # section 19: the featureless files' generator and digests, and lit/ (targets, evaluation and inner weights)
    fl_rec = read_json(out / "sessions" / "featureless.json")
    assert fl_rec["seeds"] == [0] and sum(q["sessions"] for q in fl_rec["files"].values()) == len(fl) // 4
    assert all(len(q["sha256"]) == 64 for q in fl_rec["files"].values())
    lit = read_json(out / "lit" / "eval.json")
    assert set(lit) == {"ANCHOR", "MIDANCHOR"} and set(lit["ANCHOR"]) == {"walch", "bidsleep"}
    for q in lit["ANCHOR"]["walch"]["instantaneous"].values():
        assert len(q["ids"]) == len(q["w"]) == q["sessions"] > 0 and {"lambda", "ess", "target"} <= set(q)
    assert read_json(out / "lit" / "targets.json")["aWaso"] == litprior.A_WASO
    inner = sorted((out / "lit" / "inner").rglob("*.json"))
    assert len(inner) == 6 and all({"c2", "c0"} <= set(read_json(f)) for f in inner)
    assert "weightValues" not in read_json(out / "selections.json")["selections"][0]["c2"]


def test_inner_selection_outer_models_and_folds(runs):
    out = runs["out"] / "naps-realistic"
    sel = read_json(out / "selections.json")["selections"]
    folds = read_json(out / "folds.json")["folds"]
    assert len(sel) == 6 and len(folds) == 18
    for q in sel:
        assert q["chosen"]["classifier"] in nr.CLASSIFIER_ORDER and len(q["grid"]["lr"]) == len(q["grid"]["mlp"]) == 8
        assert all(r["hybrid"]["timePriorWeight"] == 0.0 for r in q["grid"]["lr"])
        assert abs(sum(q["c1"]["bias"])) < 1e-9 and q["c1"]["decodes"] <= 8
        assert q["c2"]["table"]["lengthsMin"] == [float(a) for a in nr.ANCHORS] and q["c2"]["decodes"] <= 8
        assert q["c0"]["table"]["lengthsMin"] == [float(a) for a in nr.ANCHORS]
        assert all(c["equal"] for c in q["statistics"]) and not q["maskedProblems"]
        assert (q["chosen"]["C"] is None) == (q["chosen"]["classifier"] == "mlp")
    inp = nr.Inputs(runs["out"] / "b", runs["out"] / "naps", runs["out"] / "nm", runs["nights"])
    for f in folds:
        b = bundle.read_bundle(out / "outer" / f["variant"] / "nap" / f"{f['subject']}.json")
        assert "stageLogBias" not in b and f"held out: {f['subject']}" in b["training"]["notes"]
        assert nr.statistics_of(b) == nr.statistics_of(inp.fold(f["variant"], f["subject"]))
        assert not nr.masked_problems(b, nr.MASKED_FEATURES)
        assert b["classifier"]["type"] == ("logreg" if f["classifier"] == "lr" else "mlp")
        assert abs(sum(b["classPriors"]) - 1) < 1e-9


def test_rerender_reproduces_the_summary(runs):
    path = runs["out"] / "naps-realistic" / "summary.json"
    before = path.read_text()
    assert train.main(["naps-realistic-report", "--out-root", str(runs["out"]), "--out-run", "naps-realistic"]) == 0
    assert path.read_text() == before
    assert "naps-realistic-report re-rendered" in _log(runs)[-2]


def test_final_external_install_and_verify_of_the_decision(runs, tmp_path):
    out, common = runs["out"], runs["common"]
    s = read_json(out / "naps-realistic" / "summary.json")
    assert train.main(["naps-realistic-final", "--out-run", "naps-realistic-final", "--exp", "naps-realistic",
                       "--base-run", "nmf", *common]) == 0
    fr = read_json(out / "naps-realistic-final" / "final" / "result.json")
    assert fr["adopted"] == s["decision"]["adopted"]
    if fr["adopted"] is None:
        assert (out / "naps-realistic-final" / "final" / "hybrid_model.json").read_bytes() == runs["prev"].read_bytes()
    assert read_json(out / "naps-realistic-final" / "base_defaults.json") == read_json(out / "nmf" /
                                                                                       "trained_defaults.json")
    assert train.main(["naps-realistic-external", "--out-root", str(out), "--out-run", "naps-realistic-external",
                       "--exp", "naps-realistic", "--final", "naps-realistic-final", "--data", str(runs["data"]),
                       "--previous", str(runs["prev"])]) == 0
    e = read_json(out / "naps-realistic-external" / "summary.json")
    assert e["freeze"]["ok"] and not e["globalVoid"] and "S" in e["external"]
    assert e["ruleIII"]["models"]["S"]["void"]                         # 3 recordings per group (< 19)
    assert e["ruleIII"]["install"] is False and e["ruleIII"]["policy"] == e["ruleIII"]["policyBefore"]
    assert set(e["m60"]["S"]["variants"]) == {"instantaneous", "mean2min"} and "yasaSummary" in e
    # S is decoded with every nap parameter explicit (N1 whatever the build's defaults); the onsets are YASA's
    assert e["params"]["S"]["napMode"]["contextMarginMin"] is None and e["params"]["S"]["napMode"][
        "outputPolicy"] == "STAGES" and e["meta"]["buildIsTheExperiments"]
    assert e["onsets"] == {"recordings": 9, "withOnset": 6, "stageFileMissingOrChanged": []}
    assert e["reported"]["S"]["instantaneous"]["groups"]["REM"]["first50MinRem"]["n"] > 0
    assert "## Rule (iii)" in (out / "naps-realistic-external" / "report.md").read_text()
    assert train.main(["naps-realistic-install", "--out-root", str(out), "--exp", "naps-realistic", "--final",
                       "naps-realistic-final", "--external", "naps-realistic-external"]) == 0
    td = read_json(out / "naps-realistic-final" / "trained_defaults.json")
    assert td["napMode"]["outputPolicy"] == s["ruleII"]["N1"]["policy"] and "contextMarginMin" not in td["napMode"]
    fs = read_json(out / "naps-realistic-final" / "summary.json")
    assert fs["selection"]["napModel"] == read_json(out / "nmf" / "summary.json")["selection"]["napModel"]
    assert "realistic-nap experiment" in fs["selection"]["napOutputPolicy"]["note"]
    assert fs["installation"]["installed"] is False and fs["installation"]["model"] == "N1"
    src = kotlin_defaults.render_run(out / "naps-realistic-final")
    assert "val NAP_CONTEXT_MARGIN_MIN: Int? = null" in src and "realistic-nap experiment" in src.replace("\n", " ")
    assert train.main(["naps-realistic-verify", "--out-run", "naps-realistic-verify", "--final",
                       "naps-realistic-final", *common, "--installed", str(runs["prev"])]) == 0
    v = read_json(out / "naps-realistic-verify" / "summary.json")
    assert v["passed"] and v["model"] == "N1" and v["item1"]["identicalToB"] == 18 and not v["item8"]["applies"]
    lines = _log(runs)
    i = next(k for k, x in enumerate(lines) if " START " in x and "naps-realistic-external" in x)
    assert ds.freeze_order(lines[:i + 1])["ok"]


@pytest.mark.parametrize("model,tier", [("C2", 2), ("C1", 1), ("C0", 2)])
def test_final_external_install_and_verify_of_an_adopted_candidate(runs, model, tier):
    out, common = runs["out"], runs["common"]
    name = f"forced-{model}"
    _force(out, name, model, tier)
    assert train.main(["naps-realistic-final", "--out-run", f"{name}-final", "--exp", name, "--base-run", "nmf",
                       *common]) == 0
    fr = read_json(out / f"{name}-final" / "final" / "result.json")
    fb = bundle.read_bundle(out / f"{name}-final" / "final" / "hybrid_model.json")
    prev = bundle.read_bundle(runs["prev"])
    assert {k: v for k, v in fb.items() if k not in ("nap", "modelId", "training")} == {
        k: v for k, v in prev.items() if k not in ("nap", "modelId", "training")}
    assert fb["modelId"].endswith(f"{name}-final")
    if model == "C1":
        assert fb["nap"]["stageLogBias"] == fr["fit"]["bias"] and "stageLogBiasByLength" not in fb["nap"]
    else:
        assert fb["nap"]["stageLogBiasByLength"] == fr["fit"]["table"] and "stageLogBias" not in fb["nap"]
    if model in nr.EVIDENCE_MODELS:
        assert fr["refitIdentical"] and fr["contextMarginMin"] == 0 and fr["choice"]["classifier"] in ("lr", "mlp")
        assert nr.statistics_of(fb["nap"]) == nr.statistics_of(prev) and not nr.masked_problems(fb["nap"],
                                                                                              nr.MASKED_FEATURES)
    else:
        assert fb["nap"]["classifier"] == prev["nap"]["classifier"] and fr["contextMarginMin"] is None
    elog = out / f"{name}.log"
    lines = _log(runs)
    elog.write_text(lines[0] + "\n" + external_log_lines(runs["data"], (
        ds.sha256_file(runs["data"] / ds.MANIFEST_NAME), ds.sha256_file(runs["data"] / ds.SUMMARY_NAME)))
        + _note_line(f"START python -I tools/train/train.py naps-realistic --out-run {name}")
        + _note_line(f"RESULT {name} forced decision"))
    assert train.main(["naps-realistic-external", "--out-root", str(out), "--log", str(elog), "--out-run",
                       f"{name}-ext", "--exp", name, "--final", f"{name}-final", "--data", str(runs["data"]),
                       "--previous", str(runs["prev"])]) == 0
    e = read_json(out / f"{name}-ext" / "summary.json")
    assert set(e["external"]) == {"S", "X"} and set(e["xMinusS"]) == {"instantaneous", "mean2min"}
    assert e["params"]["X"]["napMode"] == {**e["params"]["S"]["napMode"], "hybrid": fr["hybrid"],
                                           "contextMarginMin": fr["contextMarginMin"]}
    assert e["ruleIII"]["installGates"] == ["a", "c", "d"] + (["b"] if tier == 1 else [])
    if model != "C2":
        return
    e["ruleIII"].update({"install": True, "inEffect": "X", "policy": "SLEEP_WAKE", "policyBefore": "SLEEP_WAKE"})
    write_json(out / f"{name}-ext-go" / "summary.json", e)                 # rule (iii) passed (toy data are void)
    assert train.main(["naps-realistic-install", "--out-root", str(out), "--exp", name, "--final", f"{name}-final",
                       "--external", f"{name}-ext-go"]) == 0
    td = read_json(out / f"{name}-final" / "trained_defaults.json")
    assert td["napMode"] == {"maxSessionMin": 180.0, "outputPolicy": "SLEEP_WAKE", "hybrid": fr["hybrid"],
                             "contextMarginMin": 0}
    fs = read_json(out / f"{name}-final" / "summary.json")
    assert fs["selection"]["napModel"] == fs["candidateNapModel"] and "napContextMargin" in fs["selection"]
    src = kotlin_defaults.render_run(out / f"{name}-final")
    assert "const val NAP_CONTEXT_MARGIN_MIN: Int = 0" in src
    assert "val NAP_OUTPUT_POLICY: NapOutputPolicy = NapOutputPolicy.SLEEP_WAKE" in src
    mp = pytest.MonkeyPatch()
    try:      # the build after `defaults --write`: naps at most 180 min, no context; SKIP as the most severe policy
        mp.setenv("FAKE_ALGO_CLI_DEFAULTS", json.dumps({"napMode": {"maxSessionMin": 180, "contextMarginMin": 0,
                                                                    "outputPolicy": "SKIP"}}))
        assert train.main(["naps-realistic-verify", "--out-run", f"{name}-verify", "--exp", name, "--final",
                           f"{name}-final", *common, "--installed",
                           str(out / f"{name}-final" / "final" / "hybrid_model.json")]) == 0
    finally:
        mp.undo()
    v = read_json(out / f"{name}-verify" / "summary.json")
    assert v["passed"] and v["model"] == "C2" and v["item8"]["applies"]
    # items 7 and 8 decode with STAGES whatever the default policy: neither fails nor is vacuous under SKIP
    assert all(q["identical"] == q["sessions"] > 0 and q["estimated"] > 0 for per in v["item8"]["variants"].values()
               for q in per.values())
    assert all(q["hybrid"] > 0 for q in v["item7"]["variants"])
    # item 4 decodes every registered value explicitly, null too, although the build's default margin is 0
    assert all(set(q["digests"]) == {"default", "null", "0", "30"} for q in v["item4"]["variants"].values())
    assert read_json(out / f"{name}-verify" / "final" / "margin-null.params.json") == {
        "napMode": {"contextMarginMin": None}}
    assert v["item5"]["passed"] and v["item5"]["installed"]["nightModelFingerprint"] == nr.BASE_NIGHT_FINGERPRINT


def test_external_voids_on_a_broken_freeze_and_refuses_without_the_rules(runs):
    out = runs["out"]
    if not (out / "naps-realistic-final" / "final" / "result.json").is_file():
        assert train.main(["naps-realistic-final", "--out-run", "naps-realistic-final", "--exp", "naps-realistic",
                           "--base-run", "nmf", *runs["common"]]) == 0
    bad = out / "unordered.log"
    bad.write_text(_log(runs)[0] + "\n")                               # no extraction lines, no experiment
    assert train.main(["naps-realistic-external", "--out-root", str(out), "--log", str(bad), "--out-run", "void-ext",
                       "--exp", "naps-realistic", "--final", "naps-realistic-final", "--data", str(runs["data"]),
                       "--previous", str(runs["prev"])]) == 0
    e = read_json(out / "void-ext" / "summary.json")
    assert not e["freeze"]["ok"] and e["globalVoid"] and "external" not in e
    assert e["ruleIII"]["install"] is False and not e["ruleIII"]["demoted"]
    s = read_json(out / "naps-realistic" / "summary.json")
    broken = out / "no-rules"
    broken.mkdir(exist_ok=True)
    write_json(broken / "summary.json", {k: v for k, v in s.items() if k != "ruleII"})
    assert train.main(["naps-realistic-external", "--out-root", str(out), "--out-run", "x", "--exp", "no-rules",
                       "--final", "naps-realistic-final", "--data", str(runs["data"])]) == 2


def test_a_fatal_gate_stops_the_run_and_is_logged(runs, tmp_path):
    other = tmp_path / "commands.log"
    other.write_text(runs["log"].read_text())
    wrong = tmp_path / "other.json"
    b = bundle.with_stage_log_bias(read_json(runs["prev"]), [0.5, 0.0, -0.5, 0.0])
    bundle.write_bundle(wrong, b)
    common = [x if x != str(runs["prev"]) else str(wrong) for x in runs["common"]]
    assert train.main(["naps-realistic-gates", "--out-run", "gates-wrong", *common, "--log", str(other)]) == 2
    g = read_json(runs["out"] / "gates-wrong" / "gates.json")
    assert not g["passed"] and g["G1"]["passed"] and not g["G2"]["passed"] and "G3a" not in g
    tail = other.read_text().splitlines()[-2:]
    assert " RESULT gates-wrong stopped by gate G2" in tail[0] and " END rc=2 " in tail[1]
    no_note = tmp_path / "empty.log"
    no_note.write_text("")
    assert train.main(["naps-realistic-gates", "--out-run", "gates-x", *runs["common"], "--log", str(no_note)]) == 2
    assert no_note.read_text() == ""                                       # refused before its START line


def test_verification_fails_when_a_night_changes(runs, tmp_path):
    out = runs["out"]
    if not (out / "naps-realistic-final" / "summary.json").is_file() or "installation" not in read_json(
            out / "naps-realistic-final" / "summary.json"):
        pytest.skip("needs the installation of test_final_external_install_and_verify_of_the_decision")
    other = tmp_path / "other.json"
    bundle.write_bundle(other, bundle.with_stage_log_bias(read_json(runs["prev"]), [0.5, 0.0, -0.5, 0.0]))
    assert train.main(["naps-realistic-verify", "--out-run", "verify-wrong", "--final", "naps-realistic-final",
                       *runs["common"], "--installed", str(other)]) == 2
    v = read_json(out / "verify-wrong" / "summary.json")
    assert not v["passed"] and not v["item3"]["passed"] and v["item1"]["passed"] and v["item2"]["passed"]


def test_rem_after_onset_uses_the_yasa_onset_also_in_a_clipped_window():
    rows = [realism.EpochRow(True, "", "REM" if k in (1, 2, 3, 54, 55) else "LIGHT", (0.0, 0.0, 0.0, 0.0))
            for k in range(80)]
    s0 = 600_000
    session = {"sessionStartMs": s0}
    assert nr._rem_after_onset(rows, session, s0 + 5 * MIN) == 1.0           # unclipped: epochs 5-54
    assert nr._rem_after_onset(rows, session, s0 + 2 * MIN) == 2.0           # window start clipped: epochs 2-51
    assert nr._rem_after_onset(rows, session, s0 - 3 * MIN) == 3.0           # onset before the window: epochs 0-46
    assert nr._rem_after_onset(rows, session, s0 + 90_000) == 2.0            # onset inside an epoch: epochs 2-51
    assert math.isnan(nr._rem_after_onset(rows, session, None))


# ------------------------------------------------------------------------- refusals before the START line ----

def test_experiment_preflight_seeds_and_frozen_test_set(tmp_path):
    log = tmp_path / "commands.log"
    log.write_text(_note_line(nr.PREREG_NOTE))
    with pytest.raises(nr.NapsRealisticError, match="not frozen"):
        nr.experiment_preflight(log)
    default = nr.FEATURELESS_SEED_CHOICES[0]                # 0-19 (patched for the fake run of this module)
    void = nr.experiment_preflight(log, external_void="snapshot pin failed")
    assert void["externalVoid"] == "snapshot pin failed" and not void["frozen"] and void["seeds"] == default
    with open(log, "a") as f:
        f.write(external_log_lines(tmp_path / "data", ("a" * 64, "b" * 64)))
    ok = nr.experiment_preflight(log, external_void="ignored when frozen")
    assert ok["frozen"] and ok["externalVoid"] is None and ok["seeds"] == default and ok["dryRunDecision"] is None
    assert nr.experiment_preflight(log, 10)["seeds"] == 10                  # no dry-run decision logged
    with open(log, "a") as f:
        f.write(_note_line(f"NOTE {nr.SEEDS_DECISION_TAG} (section 18 ...): projected 2.50 h (budget 2 h) "
                           "-> seeds 0-9"))
    assert nr.experiment_preflight(log)["seeds"] == 10 and nr.experiment_preflight(log)["dryRunDecision"] == 10
    with pytest.raises(nr.NapsRealisticError, match="differs from the featureless dry run"):
        nr.experiment_preflight(log, 20)
    with open(log, "a") as f:
        f.write(_note_line(ds.NOTE_MANIFEST + "c" * 64))                   # a second manifest NOTE
    with pytest.raises(nr.NapsRealisticError, match="not frozen"):
        nr.experiment_preflight(log)


def test_experiment_without_a_frozen_test_set_is_refused_before_its_start(runs, tmp_path):
    log = tmp_path / "commands.log"
    log.write_text(_note_line(nr.PREREG_NOTE))
    assert train.main(["naps-realistic", "--out-run", "unfrozen", *runs["common"], "--log", str(log)]) == 2
    assert log.read_text().splitlines() == [log.read_text().splitlines()[0]]      # nothing logged
    assert not (runs["out"] / "unfrozen").exists()
    # --external-void: the reason is logged before the START line (the run then stops here on a missing input)
    assert train.main(["naps-realistic", "--out-run", "unfrozen", *runs["common"], "--log", str(log), "--run",
                       "no-such-run", "--external-void", "snapshot pin failed"]) == 2
    lines = log.read_text().splitlines()
    assert " NOTE naps-realistic started without a frozen ds006576 test set" in lines[1]
    assert "reason: snapshot pin failed" in lines[1] and " START " in lines[2] and " END rc=2 " in lines[3]


# ----------------------------------------------------------------------------- G2 and verification probes ----

def test_g2_fails_when_the_probe_or_the_jvm_tests_fail(runs, tmp_path, monkeypatch):
    log = tmp_path / "commands.log"
    log.write_text(runs["log"].read_text())
    monkeypatch.setattr(nr, "probe_fingerprints", lambda cli, bundles, work: None)
    assert train.main(["naps-realistic-gates", "--out-run", "gates-noprobe", *runs["common"], "--log", str(log)]) == 2
    g = read_json(runs["out"] / "gates-noprobe" / "gates.json")
    assert g["G1"]["passed"] and not g["G2"]["passed"] and g["G2"]["fingerprints"]["passed"] is False
    assert all(q["default"]["passed"] for q in g["G2"]["variants"].values())     # only the probe is missing
    monkeypatch.setattr(nr, "probe_fingerprints", _fake_probe(runs["prev"]))
    monkeypatch.setattr(nr, "jvm_test_record", lambda *a, **k: {"passed": False, "exitCode": 1})
    assert train.main(["naps-realistic-gates", "--out-run", "gates-nojvm", *runs["common"], "--log", str(log)]) == 2
    g = read_json(runs["out"] / "gates-nojvm" / "gates.json")
    assert not g["G2"]["passed"] and g["G2"]["fingerprints"]["passed"] and not g["G2"]["jvmTests"]["passed"]


def _fake_gradle(root: Path, suites: dict[str, str], rc: int = 0) -> None:
    """A ``gradlew`` that writes the JUnit XML of ``suites`` ({class: attributes}) and exits with ``rc``."""
    results = root / "core-algo" / "build" / "test-results" / "test"
    lines = ["#!/bin/sh", f"mkdir -p '{results}'"]
    for c, attrs in suites.items():
        lines.append(f"printf '%s\\n' '<?xml version=\"1.0\"?><testsuite name=\"{c}\" {attrs}/>' "
                     f"> '{results}/TEST-{c}.xml'")
    lines.append(f"exit {rc}")
    (root / "gradlew").write_text("\n".join(lines) + "\n")
    (root / "gradlew").chmod(0o755)


def test_jvm_test_record_reads_fresh_junit_results(tmp_path):
    good = 'tests="4" skipped="0" failures="0" errors="0" timestamp="t"'
    classes = nr.G2_JVM_TESTS
    _fake_gradle(tmp_path, {c: good for c in classes})
    rec = JVM_TEST_RECORD(classes, tmp_path)
    assert rec["passed"] and rec["exitCode"] == 0 and all(q["tests"] == 4 and q["writtenNow"]
                                                          for q in rec["classes"].values())
    assert "--rerun" in rec["command"] and all(c in rec["command"] for c in classes)
    _fake_gradle(tmp_path, {classes[0]: good.replace('failures="0"', 'failures="1"'), classes[1]: good})
    assert not JVM_TEST_RECORD(classes, tmp_path)["passed"]
    _fake_gradle(tmp_path, {classes[0]: good.replace('skipped="0"', 'skipped="2"'), classes[1]: good})
    assert not JVM_TEST_RECORD(classes, tmp_path)["passed"]
    _fake_gradle(tmp_path, {c: good for c in classes}, rc=1)
    assert not JVM_TEST_RECORD(classes, tmp_path)["passed"]
    _fake_gradle(tmp_path, {classes[1]: good})                     # the first class's XML is stale (not written now)
    import os
    stale = tmp_path / "core-algo" / "build" / "test-results" / "test" / f"TEST-{classes[0]}.xml"
    os.utime(stale, (1, 1))
    rec = JVM_TEST_RECORD(classes, tmp_path)
    assert not rec["passed"] and rec["classes"][classes[0]]["writtenNow"] is False


def test_verification_fails_without_the_fingerprint_probe(runs, monkeypatch):
    out = runs["out"]
    fs = out / "naps-realistic-final" / "summary.json"
    if not fs.is_file() or "installation" not in read_json(fs):
        pytest.skip("needs the installation of test_final_external_install_and_verify_of_the_decision")
    monkeypatch.setattr(nr, "probe_fingerprints", lambda cli, bundles, work: None)
    assert train.main(["naps-realistic-verify", "--out-run", "verify-noprobe", "--final", "naps-realistic-final",
                       *runs["common"], "--installed", str(runs["prev"])]) == 2
    v = read_json(out / "verify-noprobe" / "summary.json")
    assert not v["passed"] and v["item5"]["passed"] is False and v["item3"]["passed"]
