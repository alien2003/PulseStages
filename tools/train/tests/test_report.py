import math

import numpy as np
import pytest

from pulsetrain import report


def conf(tp=(10, 40, 15, 20), off=2):
    c = np.full((4, 4), off, dtype=float)
    np.fill_diagonal(c, tp)
    return c


def test_kappa_and_per_stage():
    c = np.zeros((4, 4))
    c[:2, :2] = [[20, 5], [10, 15]]
    assert report.kappa(c) == pytest.approx(0.4)
    assert report.accuracy(c) == pytest.approx(0.7)
    assert report.kappa(np.diag([5, 5, 5, 5])) == 1.0
    assert math.isnan(report.kappa(np.zeros((4, 4))))
    single = np.zeros((4, 4))
    single[1, 1] = 9
    assert math.isnan(report.kappa(single))
    ps = report.per_stage(c)
    assert ps["AWAKE"]["precision"] == pytest.approx(20 / 30) and ps["AWAKE"]["recall"] == pytest.approx(0.8)
    assert ps["AWAKE"]["f1"] == pytest.approx(2 * (2 / 3) * 0.8 / (2 / 3 + 0.8))
    assert math.isnan(ps["DEEP"]["f1"])                                     # absent in truth and prediction
    assert report.macro_f1(c) == pytest.approx((ps["AWAKE"]["f1"] + ps["LIGHT"]["f1"]) / 2)
    z = c.copy()
    z[2, 0] = 3                                                             # DEEP in truth, never predicted
    assert report.per_stage(z)["DEEP"]["f1"] == 0.0
    np.testing.assert_allclose(report.row_normalize(c)[0], [0.8, 0.2, 0, 0])


def fake_metrics():
    """Metrics block in the layout of the real algo-cli evaluate (docs/INTERCHANGE.md section 2)."""
    n1, n2, n3 = conf(), conf((5, 30, 0, 10), 1), conf((8, 20, 12, 9), 3)
    return {
        "estimator": "HMM",
        "nights": {"total": 4, "evaluated": 3, "skipped": 1, "skipReasons": {"LOW_COVERAGE": 1}},
        "skipped": [{"id": "b3", "subject": "s3", "reason": "LOW_COVERAGE"}],
        "perNight": [
            {"id": "w1", "subject": "s1", "estimator": "HMM", "warnings": [], "confusion": n1.tolist(),
             "minutesError": {"deep": 3.0, "rem": 4.0, "awake": 5.0}, "kappa": report.kappa(n1)},
            {"id": "b1", "subject": "s2", "estimator": "RULES", "warnings": ["RULES_FALLBACK_LOW_COVERAGE"],
             "confusion": n2.tolist(), "minutesError": {"deep": 2.0, "rem": 6.0, "awake": 0.0}},
            {"id": "b2", "subject": "s2", "estimator": "HMM", "confusion": n3.tolist(),
             "minutesError": {"deep": None, "rem": None, "awake": None}},
        ],
        "pooled": {"confusion": (n1 + n2 + n3).tolist()},
    }


def test_parse_metrics_fields():
    recs = report.parse_metrics(fake_metrics())
    assert [r["id"] for r in recs] == ["w1", "b1", "b2", "b3"]
    assert [r["estimated"] for r in recs] == [True, True, True, False] and recs[3]["reason"] == "LOW_COVERAGE"
    assert recs[1]["warnings"] == ["RULES_FALLBACK_LOW_COVERAGE"] and recs[1]["estimatorUsed"] == "RULES"
    assert recs[1]["minutesError"] == {"DEEP": 2.0, "REM": 6.0, "AWAKE": 0.0}
    assert recs[2]["minutesError"] == {}
    bad = fake_metrics()
    bad["pooled"]["confusion"][0][0] += 1
    with pytest.raises(report.MetricsFormatError, match="pooled"):
        report.parse_metrics(bad)
    bad = fake_metrics()
    bad["nights"]["skipped"] = 2
    with pytest.raises(report.MetricsFormatError, match="disagree"):
        report.parse_metrics(bad)
    with pytest.raises(report.MetricsFormatError, match="confusion"):
        report.parse_metrics({"perNight": [{"id": "z"}]})
    with pytest.raises(report.MetricsFormatError, match="perNight"):
        report.parse_metrics({"nights": {"total": 0}, "pooled": {}})
    with pytest.raises(report.MetricsFormatError, match="4x4"):
        report.parse_metrics({"perNight": [{"id": "z", "confusion": [[1]]}]})


def test_hybrid_fallback_detected():
    recs = report.parse_metrics({"perNight": [{"id": "a", "confusion": conf().tolist(), "estimator": "HMM",
                                               "warnings": ["HYBRID_MODEL_UNAVAILABLE"]}]})
    report.check_estimator_used(recs, "HMM")
    with pytest.raises(report.MetricsFormatError, match="HYBRID_MODEL_UNAVAILABLE"):
        report.check_estimator_used(recs, "HYBRID")


def _records(metrics, subjects, **tags):
    out = []
    for r in report.parse_metrics(metrics):
        out.append({**r, "subject": subjects[r["id"]], "confusion": None if r["confusion"] is None
                    else r["confusion"].tolist(), **tags})
    return out


def test_aggregate_on_fake_metrics():
    recs = _records(fake_metrics(), {"w1": "s1", "b1": "s2", "b2": "s2", "b3": "s3"})
    a = report.aggregate(recs)
    m = fake_metrics()
    n1, n2, n3 = (np.array(m["perNight"][i]["confusion"]) for i in range(3))
    assert a["nights"] == 4 and a["nightsEstimated"] == 3 and a["nightsSkipped"] == 1
    assert a["skipReasons"] == {"LOW_COVERAGE": 1} and a["rulesFallbackNights"] == 1
    assert a["kappaPooled"] == pytest.approx(report.kappa(n1 + n2 + n3))
    k1, k2 = report.kappa(n1), report.kappa(n2 + n3)                       # subject = pooled over its nights
    assert a["subjectKappa"] == {"s1": pytest.approx(k1), "s2": pytest.approx(k2)}
    assert a["kappaSubjectMean"] == pytest.approx((k1 + k2) / 2)
    assert a["kappaSubjectSd"] == pytest.approx(np.std([k1, k2], ddof=1))
    assert a["kappaSubjectMedian"] == pytest.approx((k1 + k2) / 2)
    # night 3 has no minutes -> from its confusion (0.5 min per 30-s chunk)
    me3 = {s: abs(n3[:, i].sum() - n3[i, :].sum()) * 0.5 for s, i in (("DEEP", 2), ("REM", 3), ("AWAKE", 0))}
    for s in ("DEEP", "REM", "AWAKE"):
        assert a["minutesError"][s] == pytest.approx(np.mean([m["perNight"][0]["minutesError"][s.lower()],
                                                              {"DEEP": 2, "REM": 6, "AWAKE": 0}[s], me3[s]]))
    assert a["confusion"] == (n1 + n2 + n3).tolist()
    np.testing.assert_allclose(np.array(a["confusionRowNorm"]).sum(axis=1), 1.0)


PLAUSIBLE = [[50, 30, 5, 15], [40, 500, 80, 100], [5, 40, 120, 10], [10, 60, 10, 200]]   # DEEP 16 %, REM 26 %


def _row(est, cfg, var, k, ds="walch", confusion=PLAUSIBLE, subject_kappa=None):
    return {"estimator": est, "config": cfg, "variant": var, "dataset": ds, "evaluation": "loso",
            "kappaSubjectMean": k, "confusion": confusion, "subjectKappa": subject_kappa or {}}


def test_selection_uses_the_worse_variant_and_the_same_rule_everywhere():
    rows = [
        _row("RULES", "untrained", "instantaneous", 0.30), _row("RULES", "untrained", "mean2min", 0.32),
        _row("HMM-table", "untrained", "instantaneous", 0.40), _row("HMM-table", "untrained", "mean2min", 0.41),
        _row("HMM-trained", "walch", "instantaneous", 0.43), _row("HMM-trained", "walch", "mean2min", 0.39),
        _row("HMM-trained", "walch+bidsleep", "instantaneous", 0.42), _row("HMM-trained", "walch+bidsleep",
                                                                          "mean2min", 0.42),
        # LR walch is better on instantaneous but worse on mean2min -> pooled config wins (0.50 > 0.45)
        _row("HYBRID-LR", "walch", "instantaneous", 0.60), _row("HYBRID-LR", "walch", "mean2min", 0.45),
        _row("HYBRID-LR", "walch+bid-mask", "instantaneous", 0.52), _row("HYBRID-LR", "walch+bid-mask",
                                                                        "mean2min", 0.50),
        _row("HYBRID-LR", "walch+bid-zero", "instantaneous", 0.51), _row("HYBRID-LR", "walch+bid-zero",
                                                                        "mean2min", 0.49),
        _row("HYBRID-MLP", "walch", "instantaneous", 0.49), _row("HYBRID-MLP", "walch", "mean2min", 0.48),
        # BIDSleep and PSG-window rows must not influence the selection
        _row("HYBRID-MLP", "walch", "instantaneous", 0.99, ds="bidsleep"),
        _row("HYBRID-MLP", "walch", "instantaneous-psg", 0.99),
    ]
    fs = [{"config": "walch+bid-mask", "classifier": "lr", "variant": v, "fold": f"s{i}", "params": p,
           "score": sc, "gridIndex": gi}
          for i, (v, p, sc, gi) in enumerate([
              ("instantaneous", {"emissionWeight": 0.7}, 0.5, 4), ("mean2min", {"emissionWeight": 0.7}, 0.5, 4),
              ("instantaneous", {"emissionWeight": 1.0}, 0.6, 0), ("mean2min", {"emissionWeight": 1.0}, 0.6, 0),
              ("instantaneous", {"emissionWeight": 0.5}, 0.9, 7),
              ("instantaneous-psg", {"emissionWeight": 0.3}, 0.9, 9), ("instantaneous-psg", {"emissionWeight": 0.3},
                                                                       0.9, 9), ("instantaneous-psg",
                                                                                 {"emissionWeight": 0.3}, 0.9, 9)])]
    fs.append({"config": "walch", "classifier": "lr", "variant": "instantaneous", "fold": "x",
               "params": {"emissionWeight": 0.3}, "score": 1.0, "gridIndex": 9})
    checks = {"walch+bidsleep": {"hrNormOrdered": True}, "walch": {"hrNormOrdered": True}}
    sel = report.select_defaults(rows, fs, configs_order=("walch", "walch+bid-mask", "walch+bid-zero"),
                                 hmm_checks=checks)
    assert sel["bestConfig"]["HYBRID-LR"] == {"config": "walch+bid-mask", "score": 0.50}
    assert sel["classifier"] == {"chosen": "lr", "scores": {"HYBRID-LR": 0.50, "HYBRID-MLP": 0.48},
                                 "config": "walch+bid-mask"}
    assert sel["bidSteps"]["chosen"] == "walch+bid-mask"
    h = sel["hmm"]
    assert (h["table"], h["trained"], h["trainedPool"], h["betterKappa"], h["useTrained"]) == (
        0.40, 0.42, "walch+bidsleep", True, True)
    assert h["validity"]["valid"] and h["validity"]["predictedShares"]["instantaneous"]["DEEP"] == pytest.approx(
        215 / 1275)
    assert sel["defaultEstimator"] == "HYBRID"
    assert sel["estimatorScores"] == {"RULES": 0.30, "HMM": 0.42, "HYBRID": 0.50}
    dp = sel["decoderParams"]
    # tie 2:2 between 0.7 and 1.0 -> higher mean inner score (0.6) wins; psg / other-config folds ignored
    assert dp["params"] == {"emissionWeight": 1.0} and dp["count"] == 2 and dp["folds"] == 5


def test_selection_ties_and_missing_variants():
    rows = [_row("HMM-table", "untrained", "instantaneous", 0.4), _row("HMM-table", "untrained", "mean2min", 0.4),
            _row("HMM-trained", "walch", "instantaneous", 0.4), _row("HMM-trained", "walch", "mean2min", 0.4),
            _row("HYBRID-LR", "walch", "instantaneous", 0.9)]                  # mean2min missing -> -inf
    sel = report.select_defaults(rows)
    assert sel["hmm"]["useTrained"] is False                                   # tie keeps the table
    assert sel["defaultEstimator"] == "HMM" and sel["classifier"]["scores"]["HYBRID-LR"] == -math.inf
    fs = [{"config": "walch", "classifier": "lr", "variant": "instantaneous", "params": {"e": k}, "score": 0.5,
           "gridIndex": gi} for k, gi in ((1, 3), (2, 1))]
    assert report.choose_decoder_params(fs, "walch", "lr")["params"] == {"e": 2}   # tie -> grid order
    assert report.choose_decoder_params(fs, "walch", "mlp") is None
    fs.append({"config": "walch", "classifier": "lr", "variant": "mean2min", "params": {"e": 1}, "score": None,
               "gridIndex": 3})
    assert report.choose_decoder_params(fs, "walch", "lr")["params"] == {"e": 1}   # 2 votes, None score ok


def test_summarize_and_markdown():
    m = fake_metrics()
    recs = []
    for est, cfg, ev in (("RULES", "untrained", "untrained"), ("HYBRID-LR", "walch", "loso")):
        for var in ("instantaneous", "mean2min"):
            recs += _records(m, {"w1": "s1", "b1": "s2", "b2": "s2", "b3": "s3"}, estimator=est, config=cfg,
                             variant=var, dataset="walch", evaluation=ev)
    rows = report.summarize(recs)
    assert [(r["estimator"], r["variant"]) for r in rows] == [
        ("RULES", "instantaneous"), ("HYBRID-LR", "instantaneous"), ("RULES", "mean2min"), ("HYBRID-LR", "mean2min")]
    sel = report.select_defaults(rows)
    md = report.render_markdown(rows, sel, {"run": "x", "notes": ["n1"]})
    assert "## Headline" in md and "| HYBRID-LR [walch] |" in md and "## Confusion matrices" in md
    assert "LOW_COVERAGE: 1" in md and "- n1" in md


def test_hmm_validity_gate_blocks_degenerate_estimates():
    deep_heavy = [[10, 10, 60, 20], [20, 300, 400, 80], [2, 30, 140, 8], [5, 50, 150, 75]]   # DEEP 55 %
    rows = [_row("HMM-table", "untrained", "instantaneous", 0.07), _row("HMM-table", "untrained", "mean2min", 0.09),
            _row("HMM-trained", "walch+bidsleep", "instantaneous", 0.11, confusion=deep_heavy),
            _row("HMM-trained", "walch+bidsleep", "mean2min", 0.16),
            _row("RULES", "untrained", "instantaneous", 0.08), _row("RULES", "untrained", "mean2min", 0.1)]
    ok = {"walch+bidsleep": {"hrNormOrdered": True}}
    sel = report.select_defaults(rows, hmm_checks=ok)
    h = sel["hmm"]
    assert h["betterKappa"] and not h["useTrained"] and not h["validity"]["valid"]
    assert any("instantaneous: predicted DEEP share 55.1% outside [3%, 35%]" in p for p in h["validity"]["problems"])
    assert sel["estimatorScores"]["HMM"] == 0.07            # the adopted HMM (table) is what competes
    assert sel["defaultEstimator"] == "RULES"
    rows[2] = _row("HMM-trained", "walch+bidsleep", "instantaneous", 0.11)
    order = report.select_defaults(rows, hmm_checks={"walch+bidsleep": {"hrNormOrdered": False}})["hmm"]
    assert not order["useTrained"] and order["validity"]["problems"] == [
        "hrNorm means violate DEEP < LIGHT < REM < AWAKE"]
    unknown = report.select_defaults(rows)["hmm"]            # facts unknown -> never adopted
    assert not unknown["useTrained"] and "unknown" in unknown["validity"]["problems"][0]
    assert report.select_defaults(rows, hmm_checks=ok)["hmm"]["useTrained"]
    md = report.render_markdown(report.summarize([]), sel, {"run": "x"})
    assert "validity gate failed" in md


def test_paired_bootstrap_and_comparisons():
    a = {f"s{i}": 0.3 + 0.01 * i for i in range(20)}
    b = {k: v - 0.05 for k, v in a.items()}
    b["s0"] = a["s0"] + 0.01                                  # one loss
    b["extra"] = 0.9                                          # unpaired subjects are ignored
    r = report.paired_bootstrap(a, b, n_boot=2000, seed=1)
    assert r["n"] == 20 and r["wins"] == 19 and r["losses"] == 1
    assert r["meanDiff"] == pytest.approx((19 * 0.05 - 0.01) / 20)
    assert r["ciLow"] < r["meanDiff"] < r["ciHigh"] and r["ciLow"] > 0
    assert report.paired_bootstrap(a, b, n_boot=2000, seed=1) == r                       # deterministic
    same = report.paired_bootstrap(a, a)
    assert same["meanDiff"] == 0 and same["ciLow"] == 0 == same["ciHigh"] and same["ties"] == 20
    assert report.paired_bootstrap({}, {})["n"] == 0
    rows = [_row("HYBRID-MLP", c, v, 0.31 if c == "walch+bid-zero" else 0.3,
                 subject_kappa=(a if c == "walch+bid-zero" else b))
            for c in ("walch+bid-zero", "walch+bid-mask") for v in ("instantaneous", "mean2min")]
    rows += [_row("HYBRID-LR", "walch", v, 0.2, subject_kappa=b) for v in ("instantaneous", "mean2min")]
    sel = report.select_defaults(rows)
    assert sel["classifier"]["config"] == "walch+bid-zero" and sel["bidSteps"]["chosen"] == "walch+bid-zero"
    pairs = {(c["b"]["estimator"], c["b"]["config"], c["variant"]): c for c in sel["comparisons"]}
    assert set(pairs) == {("HYBRID-MLP", "walch+bid-mask", "instantaneous"), ("HYBRID-MLP", "walch+bid-mask",
                          "mean2min"), ("HYBRID-LR", "walch", "instantaneous"), ("HYBRID-LR", "walch", "mean2min")}
    assert pairs[("HYBRID-MLP", "walch+bid-mask", "mean2min")]["meanDiff"] == pytest.approx(r["meanDiff"])
    md = report.render_markdown(report.summarize([]), sel, {"run": "x"})
    assert "Paired per-subject comparisons" in md
    assert "| HYBRID-MLP [walch+bid-zero] | HYBRID-MLP [walch+bid-mask]" in md


def test_decoder_evidence_counts_distinct_inner_selections():
    p1, p2 = {"durationModel": "GEOMETRIC", "timePriorWeight": 0.0}, {"durationModel": "EXPLICIT",
                                                                       "timePriorWeight": 0.0}
    fs = []
    for v in ("instantaneous", "mean2min"):                  # per-group: 3 selections per variant, shared by folds
        for key, params, n, margin in (("k1", p1, 16, 0.004), ("k2", p1, 15, 0.001), ("k3", p2, 15, 0.007)):
            fs += [{"config": "c", "classifier": "mlp", "variant": v, "fold": f"{key}-{i}", "params": params,
                    "score": 0.4, "gridIndex": 4 if params is p1 else 0, "selectionKey": key, "innerMargin": margin}
                   for i in range(n)]
    dp = report.choose_decoder_params(fs, "c", "mlp")
    assert dp["params"] == p1 and dp["count"] == 62 and dp["folds"] == 92
    assert dp["selections"] == 4 and dp["selectionsTotal"] == 6
    assert dp["innerMargin"] == {"min": 0.001, "median": pytest.approx(0.004), "max": 0.007}
    assert dp["byField"]["timePriorWeight"]["0.0"] == {"folds": 92, "selections": 6}
    assert dp["byField"]["durationModel"]["EXPLICIT"] == {"folds": 30, "selections": 2}
    assert dp["frequencies"][0] == {"params": p1, "count": 62, "selections": 4}
    legacy = [{k: v for k, v in f.items() if k not in ("selectionKey", "innerMargin")} for f in fs]
    old = report.choose_decoder_params(legacy, "c", "mlp")
    assert old["selections"] is None and old["innerMargin"] is None and old["params"] == p1
