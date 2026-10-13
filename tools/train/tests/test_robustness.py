"""Post-hoc analyses (pulsetrain.robustness): moved session windows, no-steps nights, cross-run comparisons."""

import json

import pytest

import train
from pulsetrain import robustness
from pulsetrain.robustness import Scenario, scenario_shifts, shift_night
from pulsetrain.util import read_json
from synthnights import write_dataset, write_psg_twins

MIN = 60_000


def _pair():
    psg = {"id": "n-psg", "subject": "s", "variant": "instantaneous", "sessionStartMs": 0, "sessionEndMs": 400 * MIN,
           "hr": [[t * MIN, 60.0] for t in range(-30, 431, 2)],
           "steps": [[10 * MIN, 11 * MIN, 5], [395 * MIN, 396 * MIN, 7], [-25 * MIN, -24 * MIN, 3]],
           "labels": [[t * 30_000, (t + 1) * 30_000, "LIGHT"] for t in range(800)]}
    oh = dict(psg, id="n", sessionStartMs=30 * MIN, sessionEndMs=370 * MIN,
              hr=[h for h in psg["hr"] if 0 <= h[0] <= 400 * MIN],
              steps=[s for s in psg["steps"] if s[1] > 0 and s[0] < 400 * MIN],
              labels=[x for x in psg["labels"] if x[0] >= 30 * MIN and x[1] <= 370 * MIN])
    return oh, psg


def test_unshifted_rebuild_reproduces_the_ohealth_night():
    oh, psg = _pair()
    assert shift_night(oh, psg, 0, 0) == oh


def test_shift_night_window_context_and_labels():
    oh, psg = _pair()
    n = shift_night(oh, psg, 20, -20)
    assert (n["sessionStartMs"], n["sessionEndMs"]) == (50 * MIN, 350 * MIN)
    assert n["id"] == "n" and n["subject"] == "s"
    assert min(h[0] for h in n["hr"]) == 20 * MIN and max(h[0] for h in n["hr"]) == 380 * MIN
    assert n["labels"][0][0] == 50 * MIN and n["labels"][-1][1] == 350 * MIN and len(n["labels"]) == 600
    assert n["steps"] == []                                   # 10-11 min and 395 min are outside 20..380 min
    wide = shift_night(oh, psg, -60, 60)                      # clipped to the scored range of the twin
    assert (wide["sessionStartMs"], wide["sessionEndMs"]) == (0, 400 * MIN)
    assert [10 * MIN, 11 * MIN, 5] in wide["steps"] and [-25 * MIN, -24 * MIN, 3] in wide["steps"]
    tiny = shift_night(oh, psg, 300, -300)                    # never shorter than one hour
    assert tiny["sessionEndMs"] - tiny["sessionStartMs"] == 60 * MIN
    assert shift_night(oh, psg, 0, 0, no_steps=True)["steps"] == []


def test_jitter_is_deterministic_and_bounded():
    s = Scenario("jitter-15", jitter_min=15)
    a = scenario_shifts(s, "walch-1-inst")
    assert a == scenario_shifts(s, "walch-1-inst") and a != scenario_shifts(s, "walch-2-inst")
    vals = [x for i in range(200) for x in scenario_shifts(s, f"n{i}")]
    assert all(-15 <= x <= 15 for x in vals) and min(vals) < -10 and max(vals) > 10
    assert scenario_shifts(Scenario("narrow-10", 10, -10), "x") == (10.0, -10.0)


@pytest.fixture
def finished_run(tmp_path, fake_cli):
    nights = write_psg_twins(write_dataset(tmp_path / "nights", n_walch=5, n_bid=2, low_coverage_night=False))
    base = ["run", "--out-root", str(tmp_path / "out"), "--nights-root", str(nights),
            "--variants", "instantaneous,mean2min", "--created-at", "2026-01-02T03:04:05Z", "--jobs", "2",
            "--inner-k", "3", "--mlp-max-iter", "30", "--lr-cs", "0.1,1", "--lr-grid", "small",
            "--time-prior-min-count", "20", "--configs", "walch+bid-zero", "--classifiers", "lr",
            "--estimators", "HYBRID", "--selection", "per-group"]
    assert train.main([*base, "--run", "a"]) == 0
    assert train.main([*base, "--run", "b", "--seed", "3"]) == 0
    return tmp_path / "out"


def test_sensitivity_reproduces_the_run_and_reports_every_scenario(finished_run, capsys):
    assert train.main(["sensitivity", "--run", "a", "--out-root", str(finished_run), "--jobs", "2"]) == 0
    md = capsys.readouterr().out
    res = read_json(finished_run / "a" / "sensitivity" / "summary.json")
    names = {r["scenario"] for r in res["rows"]}
    assert names == {"original"} | {s.name for s in robustness.SCENARIOS}
    for v, c in res["checks"].items():                       # original nights = the run's numbers = the rebuild
        assert c["original"] == pytest.approx(c["headline"], abs=1e-9) and c["base"] == c["original"]
    narrow = next(r for r in res["rows"] if r["scenario"] == "narrow-20" and r["variant"] == "instantaneous")
    base = next(r for r in res["rows"] if r["scenario"] == "base" and r["variant"] == "instantaneous")
    assert narrow["scoredChunks"] < base["scoredChunks"] and narrow["vsBase"]["n"] == 5
    wide = next(r for r in res["rows"] if r["scenario"] == "wide-20" and r["variant"] == "instantaneous")
    assert wide["scoredChunks"] == base["scoredChunks"] + 5 * 2 * 40          # 20 min of AWAKE labels each side
    assert "| narrow-10 (" in md and "Δ instantaneous" in md
    assert res["algoCli"]["sha256"] == read_json(finished_run / "a" / "config.json")["algoCli"]["sha256"]


def test_sensitivity_fails_loudly_when_it_cannot_reproduce(finished_run, capsys):
    s = read_json(finished_run / "a" / "summary.json")
    for r in s["rows"]:
        r["kappaSubjectMean"] += 0.01
    (finished_run / "a" / "summary.json").write_text(json.dumps(s))
    assert train.main(["sensitivity", "--run", "a", "--out-root", str(finished_run), "--jobs", "1"]) == 2
    assert "re-evaluating the original nights" in capsys.readouterr().err


def test_compare_runs(finished_run, capsys, tmp_path):
    out = tmp_path / "cmp.json"
    assert train.main(["compare", "--runs", "a,b,a", "--out-root", str(finished_run), "--output", str(out)]) == 0
    res = read_json(out)
    assert [r["run"] for r in res["runs"]] == ["a", "b", "a"]
    same = [c for c in res["comparisons"] if c["run"] == "a"]
    assert len(same) == 2 and all(c["meanDiff"] == 0 and c["ties"] == c["n"] for c in same)
    assert res["sdWorse"] >= 0 and "worse-variant κ over the runs" in capsys.readouterr().out
    assert train.main(["compare", "--runs", "a", "--out-root", str(finished_run), "--estimator", "HYBRID-MLP"]) == 2
