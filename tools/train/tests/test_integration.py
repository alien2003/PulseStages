"""End-to-end LOSO orchestration with the FAKE algo-cli (tests/fake_algo_cli.py) on tiny synthetic nights."""

import sys

import pytest

import train
from pulsetrain import bundle, loso, pipeline, report
from pulsetrain.algocli import AlgoCli
from pulsetrain.util import read_json
from conftest import FAKE, make_launcher
from synthnights import write_dataset

CREATED = "2026-01-02T03:04:05Z"


def ref_subject(night_id):
    """walch-1000-inst -> 1000, bid-01-2-mean2min -> bid-01 (synthnights naming)."""
    parts = night_id.split("-")
    return parts[1] if parts[0] == "walch" else "-".join(parts[:2])


def _args(tmp_path, *extra):
    return ["run", "--run", "t", "--out-root", str(tmp_path / "out"), "--nights-root", str(tmp_path / "nights"),
            "--variants", "instantaneous,mean2min", "--created-at", CREATED, "--jobs", "2", "--inner-k", "3",
            "--mlp-max-iter", "30", "--lr-cs", "0.1,1", "--lr-grid", "small", "--time-prior-min-count", "20",
            *extra]


@pytest.fixture(scope="module")
def run_dir(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("e2e")
    write_dataset(tmp / "nights")
    mp = pytest.MonkeyPatch()
    (tmp / "bin").mkdir()
    launcher = make_launcher(tmp / "bin", f'exec "{sys.executable}" -I "{FAKE}" "$@"')
    mp.setenv("ALGO_CLI", str(launcher))
    try:
        args = _args(tmp, "--keep-inner")
        args[args.index("--jobs") + 1] = "4"
        assert train.main(args) == 0
    finally:
        mp.undo()
    return tmp / "out" / "t"


def test_outputs_exist(run_dir):
    for p in ("config.json", "run.log", "commands.log", "results.jsonl", "fold_selections.jsonl", "summary.json",
              "report.md", "final/hybrid_model.json", "trained_defaults.json"):
        assert (run_dir / p).is_file(), p
    s = read_json(run_dir / "summary.json")
    assert s["meta"]["wallSeconds"] > 0 and "hybrid" in s["meta"]["phaseSeconds"]
    md = (run_dir / "report.md").read_text()
    assert "## Headline" in md and "HYBRID-LR" in md and "HMM-trained" in md and "RULES" in md


def test_every_command_logged(run_dir):
    lines = (run_dir / "commands.log").read_text().splitlines()
    assert lines and all(" rc=0 " in ln for ln in lines)
    assert any(" features " in ln for ln in lines)
    assert any("--param-grid" in ln for ln in lines)
    assert any("--fold-models" in ln for ln in lines)


def test_fold_bundles_valid_and_never_trained_on_held_out(run_dir):
    n = 0
    for meta_path in sorted(run_dir.glob("hybrid/*/*/*/folds-meta/*.meta.json")):
        m = read_json(meta_path)
        n += 1
        assert not set(m["test"]) & set(m["train"])
        folds = meta_path.parent.parent / "folds"
        for s in m["test"]:
            b = bundle.read_bundle(folds / f"{s}.json")
            assert b["featureSetVersion"] == 7 and b["featureNames"][0] == "hrNorm"
            assert b["training"]["subjects"] == len(m["train"]) and b["training"]["createdAt"] == CREATED
            hp = read_json(folds / f"{s}.hmm-params.json")
            assert set(hp["hmm"]) == {"emissions", "meanBouts", "transitions"}
        sel = read_json(meta_path.parent.parent / "selection" / f"{m['selectionKey']}.json")
        assert not set(sel["subjects"]) & set(m["test"])
        inner_tests = [set(t) for t in sel["innerFolds"]]
        assert set().union(*inner_tests) == set(sel["subjects"])
        assert sum(len(t) for t in inner_tests) == len(sel["subjects"])
        assert {ref_subject(x) for x in sel["evalNights"]} <= set(sel["evalSubjects"])
        # nights scored in the inner grid call belong to selection subjects only
        grid = read_json(run_dir / "work" / m["config"] / m["variant"] / m["classifier"] / m["selectionKey"]
                         / "grid-metrics.json")
        for g in grid["grid"]:
            subs = {x["subject"] for x in g["perNight"]}         # raw algo-cli --param-grid blocks
            assert subs <= set(sel["evalSubjects"]) and not subs & set(m["test"])
    # 3 configs x 2 variants x 2 classifiers; walch: 6 LOSO + external, pooled: 9 LOSO
    assert n == 2 * 2 * (7 + 9 + 9)


def test_results_split_by_dataset_and_evaluation(run_dir):
    rows = read_json(run_dir / "summary.json")["rows"]
    keys = {(r["estimator"], r["config"], r["dataset"], r["evaluation"]) for r in rows}
    assert ("HYBRID-LR", "walch", "walch", "loso") in keys
    assert ("HYBRID-LR", "walch", "bidsleep", "external") in keys
    assert ("HYBRID-MLP", "walch+bid-mask", "bidsleep", "loso") in keys
    assert ("HMM-trained", "walch+bidsleep", "walch", "loso") in keys
    assert ("RULES", "untrained", "walch", "untrained") in keys
    r = next(r for r in rows if r["estimator"] == "HYBRID-LR" and r["config"] == "walch" and
             r["dataset"] == "walch" and r["variant"] == "instantaneous")
    assert r["subjects"] == 6 and r["nightsEstimated"] == 6
    assert -1 <= r["kappaSubjectMean"] <= 1
    bid = next(r for r in rows if r["estimator"] == "RULES" and r["dataset"] == "bidsleep"
               and r["variant"] == "mean2min")
    assert bid["nightsSkipped"] == 1 and bid["skipReasons"] == {"LOW_COVERAGE": 1}


def test_selection_and_final(run_dir):
    s = read_json(run_dir / "summary.json")
    sel = s["selection"]
    assert sel["defaultEstimator"] in ("RULES", "HMM", "HYBRID")
    assert sel["classifier"]["chosen"] in ("lr", "mlp")
    assert sel["bidSteps"]["chosen"] in ("walch+bid-mask", "walch+bid-zero")
    dp = sel["decoderParams"]
    assert set(dp["params"]) == {"emissionWeight", "timePriorWeight", "durationModel"}
    b = bundle.read_bundle(run_dir / "final" / "hybrid_model.json")
    assert b["classifier"]["type"] == ("logreg" if sel["classifier"]["chosen"] == "lr" else "mlp")
    assert b["training"]["variants"] == ["instantaneous", "mean2min"]
    cfg = loso.CONFIGS[sel["classifier"]["config"]]
    assert b["training"]["subjects"] == (6 if cfg.datasets == ("walch",) else 9)
    if cfg.step_policy == "mask":
        steps_i = b["featureNames"].index("stepsLog1p")
        assert b["standardization"]["std"][steps_i] == 1e12 and "masked features" in b["training"]["notes"]
    d = read_json(run_dir / "trained_defaults.json")
    assert d["estimator"] == sel["defaultEstimator"] and d["hybrid"] == dp["params"]
    assert ("hmm" in d) == sel["hmm"]["useTrained"]
    assert s["final"]["resubstitutionNights"] > 0


def test_hmm_table_baseline_uses_the_physiology_table_explicitly(run_dir):
    # the AlgoParams HMM defaults can be trained estimates (TrainedDefaults.kt); HMM-table must not depend on them
    p = read_json(run_dir / "baselines" / "hmm-table.params.json")
    assert p == loso.physiology_hmm_patch()
    assert p["hmm"]["emissions"]["deep"]["hrNorm"] == {"mean": 0.1, "sd": 0.35}
    lines = (run_dir / "commands.log").read_text().splitlines()
    table = [ln for ln in lines if "HMM-table.metrics.json" in ln]
    assert table and all("hmm-table.params.json" in ln for ln in table)
    assert not any("hmm-table.params.json" in ln for ln in lines if "RULES.metrics.json" in ln)


def test_report_rerun_is_identical(run_dir):
    before = read_json(run_dir / "summary.json")
    pipeline.rerun_report(run_dir)
    after = read_json(run_dir / "summary.json")
    assert after["rows"] == before["rows"] and after["selection"] == before["selection"]


def test_grid_fallback_and_hybrid_rejection(tmp_path, fake_cli, monkeypatch, capsys):
    write_dataset(tmp_path / "nights", n_walch=4, n_bid=0, low_coverage_night=False)
    monkeypatch.setenv("FAKE_ALGO_CLI_NO_GRID", "1")
    args = _args(tmp_path, "--configs", "walch", "--classifiers", "lr", "--estimators", "HYBRID",
                 "--variants", "instantaneous", "--no-external", "--jobs", "1", "--lr-grid", "default")
    assert train.main(args) == 0
    log = (tmp_path / "out" / "t" / "commands.log").read_text()
    assert " rc=64 " in log and "--param-grid" in log      # native grid rejected once, then per-point
    # a wrong featureSetVersion makes the (fake) algo-cli fall back to HMM -> the pipeline must fail loudly
    args += ["--feature-set-version", "3"]
    args[args.index("--run") + 1] = "t2"
    assert train.main(args) == 2
    assert "HYBRID_MODEL_UNAVAILABLE" in capsys.readouterr().err
    with pytest.raises(report.MetricsFormatError, match="HYBRID_MODEL_UNAVAILABLE"):
        train._dispatch(train.build_parser().parse_args(args))


def test_per_group_selection_is_leak_free(tmp_path, fake_cli):
    write_dataset(tmp_path / "nights", n_walch=6, n_bid=0, low_coverage_night=False)
    args = _args(tmp_path, "--configs", "walch", "--classifiers", "lr", "--estimators", "HYBRID",
                 "--selection", "per-group", "--no-external")
    assert train.main(args) == 0
    run = tmp_path / "out" / "t"
    keys = set()
    for p in run.glob("hybrid/walch/*/lr/folds-meta/*.meta.json"):
        m = read_json(p)
        sel = read_json(p.parent.parent / "selection" / f"{m['selectionKey']}.json")
        assert not set(sel["subjects"]) & set(m["test"])
        keys.add((m["variant"], m["selectionKey"]))
    assert len(keys) == 2 * 3                                # 3 outer groups per variant, shared by their folds


def test_deterministic_regardless_of_parallelism(tmp_path, fake_cli):
    write_dataset(tmp_path / "nights", n_walch=5, n_bid=0, low_coverage_night=False)
    outs = []
    for jobs in ("1", "3"):
        args = _args(tmp_path, "--configs", "walch", "--estimators", "HMM-trained,HYBRID", "--variants",
                     "instantaneous", "--no-external")
        args[args.index("--jobs") + 1] = jobs
        args[args.index("--run") + 1] = f"j{jobs}"
        assert train.main(args) == 0
        outs.append(tmp_path / "out" / f"j{jobs}")
    a, b = outs
    assert (a / "results.jsonl").read_text() == (b / "results.jsonl").read_text()
    assert (a / "fold_selections.jsonl").read_text() == (b / "fold_selections.jsonl").read_text()
    fa_, fb_ = (read_json(x / "final" / "hybrid_model.json") for x in (a, b))
    for f in (fa_, fb_):                                     # the run name is part of the id / notes
        f.pop("modelId")
        f["training"].pop("notes")
    assert fa_ == fb_
    fa = sorted(p.relative_to(a) for p in a.glob("hybrid/**/folds/*.json"))
    assert fa and fa == sorted(p.relative_to(b) for p in b.glob("hybrid/**/folds/*.json"))
    assert all((a / p).read_bytes() == (b / p).read_bytes() for p in fa)


def test_inner_scratch_removed_without_keep_inner(tmp_path, fake_cli):
    write_dataset(tmp_path / "nights", n_walch=4, n_bid=0, low_coverage_night=False)
    args = _args(tmp_path, "--configs", "walch", "--classifiers", "lr", "--estimators", "HYBRID",
                 "--variants", "instantaneous", "--no-external")
    assert train.main(args) == 0
    work = tmp_path / "out" / "t" / "work"
    sels = list(work.glob("walch/instantaneous/lr/*/selection.json"))
    assert len(sels) == 4
    for s in sels:
        assert sorted(x.name for x in s.parent.iterdir()) == ["grid-metrics.grid-input.json", "selection.json"]
        assert len(read_json(s)["grid"]) == 8


def test_run_records_the_algo_cli_build_and_fails_if_it_changes(tmp_path, fake_cli, monkeypatch, capsys):
    write_dataset(tmp_path / "nights", n_walch=4, n_bid=0, low_coverage_night=False)
    args = _args(tmp_path, "--configs", "walch", "--classifiers", "lr", "--estimators", "RULES,HYBRID",
                 "--variants", "instantaneous", "--no-external", "--jobs", "1", "--lr-grid", "default")
    assert train.main(args) == 0
    run = tmp_path / "out" / "t"
    fp = read_json(run / "config.json")["algoCli"]
    assert fp["sha256"] == AlgoCli.locate().fingerprint()["sha256"]
    assert read_json(run / "summary.json")["meta"]["algoCli"] == fp
    assert loso.PipelineConfig.from_json(read_json(run / "config.json")).run == "t"   # extra key tolerated
    calls = {"n": 0}
    real = AlgoCli.fingerprint

    def rebuilt_after_first_phase(self):
        calls["n"] += 1
        d = real(self)
        return d if calls["n"] == 1 else dict(d, sha256="0" * 64)

    monkeypatch.setattr(AlgoCli, "fingerprint", rebuilt_after_first_phase)
    args[args.index("--run") + 1] = "t2"
    assert train.main(args) == 2
    assert "algo-cli changed during the run (after phase features)" in capsys.readouterr().err


def test_hmm_trained_estimates_the_time_prior_and_feeds_the_validity_gate(run_dir):
    from pulsetrain.stats import TIME_PRIOR_KEYS

    folds = sorted(run_dir.glob("hmm-trained/*/*/folds/*.hmm-params.json"))
    assert folds
    for f in folds:
        h = read_json(f)["hmm"]
        assert set(TIME_PRIOR_KEYS) <= set(h) and 0 <= h["remLockoutFactor"] <= 1
        meta = read_json(f.parent.parent / "folds-meta" / f.name.replace(".hmm-params.json", ".meta.json"))
        assert isinstance(meta["hrNormOrdered"], bool) and any("time prior" in n for n in meta["notes"])
    s = read_json(run_dir / "summary.json")
    est = s["meta"]["hmmFinalEstimates"]
    assert set(est) == {"walch", "walch+bidsleep"}
    for pool, e in est.items():
        assert (run_dir / "hmm-trained" / pool / "final.hmm-params.json").is_file()
        assert set(TIME_PRIOR_KEYS) <= set(e["hmm"])
    h = s["selection"]["hmm"]
    assert h["validity"]["hrNormOrdered"] == est[h["trainedPool"]]["hrNormOrdered"]
    assert h["useTrained"] == (h["betterKappa"] and h["validity"]["valid"])
    d = read_json(run_dir / "trained_defaults.json")
    if h["useTrained"]:
        assert d["hmm"] == loso.hmm_patch(est[h["trainedPool"]]["hmm"])["hmm"]
    dp = s["selection"]["decoderParams"]
    assert dp["selectionsTotal"] >= 1 and dp["selections"] >= 1
    fs = loso.read_jsonl(run_dir / "fold_selections.jsonl")
    assert all(f["selectionKey"] and "innerMargin" in f for f in fs)
