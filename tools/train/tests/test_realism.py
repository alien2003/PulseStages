"""Metrics and rules of the realistic-nap pre-registration (pulsetrain/realism.py, sections 7-9 and 13.8) on synthetic
records, confusion matrices and per-epoch rows: E-cap, per-epoch AUC with ties 1/2 and the cell rule, regime
contrasts on shares of sleep with the subject bootstrap, the featureless R1 quantities, the LATE tail, the LIT metrics
and LitDist, guard excess, LIT kappa, prior-matched metrics, evidence tracking, and rules (i) (both tiers), (ii) and
(iii) with each criterion failing alone."""

import copy
import csv
import math

import numpy as np
import pytest

from pulsetrain import litprior, realism
from pulsetrain.litprior import ANCHORS, MID_ANCHORS

V = ("instantaneous", "mean2min")


def rec(sid, subject="1", conf=None, *, pool="O", regime="EARLY", L=60, kind="eval", estimated=True,
        used="HYBRID", variant="instantaneous", dataset="walch", reason=None, **extra):
    c = np.diag([2.0, 10.0, 6.0, 2.0]) if conf is None else np.asarray(conf, float)
    return {"night": sid, "subject": subject, "pool": pool, "regime": regime, "L": float(L), "kind": kind,
            "estimated": estimated, "estimatorUsed": used if estimated else None, "reason": reason,
            "warnings": [], "confusion": None if conf is False else c.tolist(), "variant": variant,
            "dataset": dataset, **extra}


def diag(a, l, d, r):
    return np.diag([a, l, d, r]).astype(float)


# ------------------------------------------------------------------------------------- session quantities ----

def test_session_shares_minutes_and_guards():
    c = np.array([4.0, 50.0, 30.0, 16.0])
    assert realism.share_of_sleep(c, 2) == pytest.approx(30 / 96) and realism.window_share(c, 3) == 0.16
    assert math.isnan(realism.share_of_sleep(np.array([5.0, 0, 0, 0]), 2))
    m = realism.minutes(c)
    assert (m["tst"], m["deep"], m["awake"]) == (48.0, 15.0, 2.0)
    assert not realism.gl_flag(c)
    assert realism.gl_flag(np.array([0, 20.0, 120.0, 0]))                     # 60 min DEEP (> 54)
    assert realism.gl_flag(np.array([0, 50.0, 0, 6.0]))                       # 28 min of sleep, 10.7 % REM
    assert not realism.g_flag(np.array([0, 50.0, 0, 6.0]))


# --------------------------------------------------------------------------------------------- E-cap ----

def test_common_set_keeps_sessions_estimated_by_hybrid_with_a_scored_chunk_under_every_model():
    a = [rec("s1"), rec("s2"), rec("s3"), rec("s4"), rec("s5", pool="M", regime="LATE"), rec("s6")]
    b = [rec("s1"), rec("s2", estimated=False, reason="LOW_COVERAGE"), rec("s3", used="RULES"),
         rec("s4", conf=np.zeros((4, 4))), rec("s5", pool="M", regime="LATE")]
    c = [rec("s1"), rec("s2"), rec("s3"), rec("s4"), rec("s5", pool="M", regime="LATE"), rec("s6")]
    cs = realism.common_set({"N1": a, "C1": b, "C2": c})
    assert cs["members"] == {("instantaneous", "s1"), ("instantaneous", "s5")}
    assert cs["sessions"] == {"O|walch|instantaneous": 5, "M|walch|instantaneous": 1}
    assert cs["common"] == {"O|walch|instantaneous": 1, "M|walch|instantaneous": 1}
    assert cs["excluded"]["C1"]["O|walch|instantaneous"] == {"NOT_ESTIMATED:LOW_COVERAGE": 1, "ESTIMATOR:RULES": 1,
                                                             "NO_SCORED_CHUNK": 1, "MISSING": 1}
    assert cs["excluded"]["N1"] == {} and cs["excluded"]["C2"] == {}
    assert [r["night"] for r in realism.in_common(a, cs["members"])] == ["s1", "s5"]
    with pytest.raises(ValueError, match="duplicate"):
        realism.common_set({"N1": [rec("s1"), rec("s1")]})


# --------------------------------------------------------------------------------------------- AUC (D) ----

def test_mann_whitney_auc_counts_ties_as_one_half():
    assert realism.mann_whitney_auc([0.1, 0.4, 0.4, 0.8], [0, 1, 0, 1]) == 0.875
    assert realism.mann_whitney_auc([1, 2, 3], [0, 1, 1]) == 1.0
    assert realism.mann_whitney_auc([1, 2, 3], [1, 0, 0]) == 0.0
    assert realism.mann_whitney_auc([0.5] * 4, [0, 1, 0, 1]) == 0.5
    assert math.isnan(realism.mann_whitney_auc([1, 2], [1, 1]))
    rng = np.random.default_rng(0)
    s = rng.integers(0, 5, 60).astype(float)
    y = rng.random(60) < 0.4
    pos, neg = s[y], s[~y]
    brute = np.mean([(p > q) + 0.5 * (p == q) for p in pos for q in neg])
    assert realism.mann_whitney_auc(s, y) == pytest.approx(brute)


HEADER = ["grid_index", "night_id", "subject", "epoch", "start_ms", "has_data", "label", "stage", "p_awake", "p_light",
          "p_deep", "p_rem"]


def _write_per_epoch(path, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(HEADER)
        for r in rows:
            w.writerow(r)
    return path


def test_read_per_epoch_and_match_epochs(tmp_path):
    x = _write_per_epoch(tmp_path / "x.csv", [
        [0, "a", "1", 0, 0, 1, "DEEP", "DEEP", 0.1, 0.1, 0.7, 0.1],
        [0, "a", "1", 1, 60000, 0, "DEEP", "UNKNOWN", 0.25, 0.25, 0.25, 0.25],
        [0, "a", "1", 2, 120000, 1, "", "LIGHT", 0.1, 0.7, 0.1, 0.1],
        [0, "a", "1", 3, 180000, 1, "LIGHT", "LIGHT", 0.1, 0.6, 0.2, 0.1],
        [0, "zz", "9", 0, 0, 1, "LIGHT", "LIGHT", 0.1, 0.6, 0.2, 0.1],
        [1, "a", "1", 0, 0, 1, "DEEP", "DEEP", 0.9, 0.0, 0.1, 0.0]])
    n1 = _write_per_epoch(tmp_path / "n.csv", [
        [0, "a", "1", 0, 0, 1, "DEEP", "LIGHT", 0.2, 0.5, 0.2, 0.1],
        [0, "a", "1", 1, 60000, 1, "DEEP", "LIGHT", 0.2, 0.5, 0.2, 0.1],
        [0, "a", "1", 2, 120000, 1, "", "LIGHT", 0.2, 0.5, 0.2, 0.1]])
    rx, rn = realism.read_per_epoch(x), realism.read_per_epoch(n1)
    assert len(rx) == 5 and rx[("a", 0)].p == (0.1, 0.1, 0.7, 0.1) and not rx[("a", 1)].has_data
    assert realism.read_per_epoch(x, sessions={"zz"}).keys() == {("zz", 0)}
    sessions = {"a": {"subject": "1", "L": 60.0, "kind": "eval", "pool": "O", "regime": "EARLY"}}
    m = realism.match_epochs(rx, rn, sessions)
    assert m.session.tolist() == ["a"] and m.label.tolist() == [2]
    assert m.excluded == {"noData": 1, "noLabel": 1, "missingInOneModel": 1}
    assert m.groups["ANCHOR"].tolist() == [True] and m.groups["EARLY"].tolist() == [True]
    assert m.groups["LATE"].tolist() == [False]


def _epochs(rng, n, frac_deep):
    lab = np.where(rng.random(n) < frac_deep, 2, 1)
    return lab


def test_auc_is_the_mean_over_subjects_of_the_mean_over_their_cells():
    rng = np.random.default_rng(1)
    rows_x, rows_n, sessions = {}, {}, {}
    expected_x: dict = {}
    for subj, sids in (("1", [("o60", "O", "EARLY", 60), ("m60", "M", "LATE", 60), ("o90", "O", "EARLY", 90)]),
                       ("2", [("o60b", "O", "EARLY", 60), ("r60", "R", "LATE", 60)])):
        for sid, pool, regime, L in sids:
            sid = f"{subj}-{sid}"
            sessions[sid] = {"subject": subj, "L": float(L), "kind": "eval", "pool": pool, "regime": regime}
            lab = _epochs(rng, 40, 0.4)
            for e, y in enumerate(lab):
                pd_x = 0.9 if y == 2 else 0.1                                 # X separates DEEP perfectly
                pd_n = rng.random()
                st = realism.STAGES[y]
                rows_x[(sid, e)] = realism.EpochRow(True, st, st, (0.0, 1 - pd_x, pd_x, 0.0))
                rows_n[(sid, e)] = realism.EpochRow(True, st, st, (0.0, 1 - pd_n, pd_n, 0.0))
    m = realism.match_epochs(rows_x, rows_n, sessions)
    s = realism.auc_summary(m)
    assert s["X"]["ANCHOR"]["DEEP"]["auc"] == 1.0
    # cells: subject 1 has (1, 60) pooling O-60 and M-60, and (1, 90); subject 2 has (2, 60)
    an = s["REF"]["ANCHOR"]["DEEP"]
    assert an["cells"] == 3 and an["subjects"] == 2
    keep = m.groups["ANCHOR"]
    cells = realism.cell_aucs(m.subject, m.length, keep, m.label, m.p_ref, 2)
    assert set(cells) == {("1", 60.0), ("1", 90.0), ("2", 60.0)}
    want = np.mean([np.mean([cells[("1", 60.0)], cells[("1", 90.0)]]), cells[("2", 60.0)]])
    assert an["auc"] == pytest.approx(want)
    assert s["delta"]["ANCHOR"]["DEEP"] == pytest.approx(1.0 - want)
    # EARLY uses the O sessions only; a cell where only one outcome occurs is undefined (REM never occurs)
    assert s["X"]["EARLY"]["DEEP"]["cells"] == 3 and math.isnan(s["X"]["ANCHOR"]["REM"]["auc"])
    assert s["X"]["LATE"]["DEEP"]["subjects"] == 2


# ----------------------------------------------------------------------------------- regime contrast (F) ----

def _contrast_records(model="truth"):
    """Subjects 1 and 2 have EARLY and LATE sessions at 60 and 90 min, subject 3 only EARLY ones."""
    out = []
    early_t, late_t = [4.0, 50.0, 30.0, 10.0], [4.0, 50.0, 10.0, 30.0]
    for subj in ("1", "2", "3"):
        for L in (60, 90):
            for regime, t in (("EARLY", early_t), ("LATE", late_t)):
                if subj == "3" and regime == "LATE":
                    continue
                k = 1.0 + int(subj) * 0.1 + L / 1000
                tt = np.array(t) * k
                if model == "truth":
                    c = np.diag(tt)
                elif model == "flat":                                   # every true chunk to the same mix
                    c = np.outer(tt, [0.1, 0.5, 0.2, 0.2])
                else:                                                   # more AWAKE in LATE, flat sleep mix
                    mix = [0.1, 0.5, 0.2, 0.2] if regime == "EARLY" else [0.55, 0.25, 0.1, 0.1]
                    c = np.outer(tt, mix)
                out.append(rec(f"{subj}-{regime}-{L}", subj, c, pool="O" if regime == "EARLY" else "M",
                               regime=regime, L=L))
    out.append(rec("1-other", "1", pool="M", regime="OTHER", L=60))     # not MATCHED
    out.append(rec("1-late120", "1", pool="M", regime="LATE", L=120))   # not MATCHED
    return out


def test_regime_contrast_on_shares_of_sleep_over_subjects_with_both_regimes():
    r = realism.regime_contrast(_contrast_records("truth"), n_boot=500)
    assert r["subjects"] == ["1", "2"] and r["n"] == 2
    assert r["deltaDeep"] == pytest.approx(20 / 90) and r["trueDeltaDeep"] == pytest.approx(20 / 90)
    assert r["rDeep"] == pytest.approx(1.0) and r["rRem"] == pytest.approx(1.0)
    assert r["ciDeep"][0] == pytest.approx(20 / 90) and r["pred"]["EARLY60"]["DEEP"] == pytest.approx(30 / 90)
    f = realism.regime_contrast(_contrast_records("flat"), n_boot=500)
    assert f["deltaDeep"] == pytest.approx(0, abs=1e-12) and f["rRem"] == pytest.approx(0, abs=1e-9)
    # predicting more AWAKE in LATE windows moves window shares but not shares of sleep
    w = realism.regime_contrast(_contrast_records("wake"), n_boot=500)
    assert w["deltaDeep"] == pytest.approx(0, abs=1e-12)
    ww = realism.regime_contrast(_contrast_records("wake"), kind="window", n_boot=500)
    assert ww["deltaDeep"] > 0.05
    allr = realism.regime_contrast(_contrast_records("truth"), both_regimes=False, n_boot=100)
    assert allr["subjects"] == ["1", "2", "3"]


def test_regime_contrast_bootstrap_resamples_subjects_with_all_their_sessions():
    recs = []
    rng = np.random.default_rng(5)
    for subj in range(6):
        for L in (60, 90):
            for regime in ("EARLY", "LATE"):
                c = rng.integers(0, 20, size=(4, 4)).astype(float)
                recs.append(rec(f"{subj}-{regime}-{L}", str(subj), c, regime=regime, L=L))
    r = realism.regime_contrast(recs, n_boot=300, seed=0)
    subjects = sorted({x["subject"] for x in recs})
    per = {s: [x for x in recs if x["subject"] == s] for s in subjects}
    idx = np.random.default_rng(0).integers(0, len(subjects), size=(300, len(subjects)))
    dd = []
    for row in idx:
        pooled = {}
        for i in row:
            for x in per[subjects[i]]:
                k = (x["regime"], int(x["L"]))
                pooled[k] = pooled.get(k, np.zeros(4)) + np.asarray(x["confusion"]).sum(axis=0)
        sh = {k: v[2] / v[1:].sum() for k, v in pooled.items()}
        dd.append(np.mean([sh[("EARLY", L)] - sh[("LATE", L)] for L in (60, 90)]))
    assert r["ciDeep"] == pytest.approx(list(np.quantile(dd, [0.025, 0.975])))
    assert r["nanResamples"] == {"DEEP": 0, "REM": 0} and r["resamples"] == 300
    assert r == realism.regime_contrast(recs, n_boot=300, seed=0)                  # deterministic


def test_undefined_bootstrap_contrasts_count_as_minus_infinity():
    x = np.random.default_rng(1).normal(1.0, 0.5, size=1000)
    q = [(1 - 0.95) / 2, 1 - (1 - 0.95) / 2]                 # as regime_contrast always computed them
    assert realism.undefined_low_ci(x) == [float(v) for v in np.quantile(x, q)]          # all defined: unchanged
    y = x.copy()
    y[:10] = np.nan                                     # 1 % undefined: below every defined value
    lo, hi = realism.undefined_low_ci(y)
    ref = np.sort(np.concatenate([np.full(10, -1e9), x[10:]]))
    assert lo == pytest.approx(float(np.quantile(ref, q[0]))) and lo < float(np.quantile(x[10:], q[0]))
    assert hi == pytest.approx(float(np.quantile(ref, q[1])))
    y[:30] = np.nan                                     # 3 % undefined: the lower bound is -inf (fails "> 0")
    assert realism.undefined_low_ci(y)[0] == float("-inf")
    assert realism.undefined_low_ci(np.full(5, np.nan)) == [float("-inf")] * 2
    assert all(math.isnan(v) for v in realism.undefined_low_ci(np.array([])))


def test_regime_contrast_counts_resamples_with_an_empty_cell_against_the_contrast():
    recs = []
    for subj in range(3):                     # only subject 0 has a LATE session of 90 min
        for L in (60, 90):
            for regime in ("EARLY", "LATE"):
                if regime == "LATE" and L == 90 and subj != 0:
                    continue
                c = np.diag([2.0, 10.0, 8.0 if regime == "EARLY" else 1.0, 1.0 if regime == "EARLY" else 6.0])
                recs.append(rec(f"{subj}-{regime}-{L}", str(subj), c, regime=regime, L=L))
    r = realism.regime_contrast(recs, n_boot=400, seed=0)
    assert r["deltaDeep"] > 0 and r["deltaRem"] > 0                 # defined on the full sample
    # (2/3)^3 = 30 % of the resamples miss subject 0, hence the LATE-90 cell: the lower bound is -inf
    assert r["nanResamples"]["DEEP"] > 0.2 * 400 and r["ciDeep"][0] == float("-inf")
    assert r["nanResampleRule"] == realism.UNDEFINED_RESAMPLE_RULE


def test_regime_contrast_without_subjects():
    r = realism.regime_contrast([rec("a", regime="EARLY", L=60)], n_boot=10)
    assert r["n"] == 0 and math.isnan(r["rDeep"])


# ------------------------------------------------------------------------------------- featureless (R1) ----

def fl(sid, L, pred, *, design="flat", sd=1, estimated=True, used="HYBRID"):
    c = np.zeros((4, 4))
    c[1] = pred                                       # every label is the LIGHT placeholder
    return rec(sid, "1", c, pool="FEATURELESS", regime=None, L=L, kind="featureless", estimated=estimated,
               used=used, design=design, sd=sd)


def test_no_sleep_rule():
    assert realism.no_sleep(fl("a", 60, [0, 10, 0, 0], estimated=False))
    assert realism.no_sleep(fl("a", 60, [0, 10, 0, 0], used="RULES"))
    assert realism.no_sleep(fl("a", 60, [6, 4, 0, 0]))
    assert not realism.no_sleep(fl("a", 60, [5, 5, 0, 0]))                  # exactly 50 % sleep
    assert realism.no_sleep(fl("a", 60, [0, 0, 0, 0]))
    with pytest.raises(ValueError):
        realism.no_sleep(fl("a", 60, [0, 10, 0, 0], used="HMM"))


def test_featureless_check_pools_the_decodes_with_sleep_per_length():
    recs = [fl("a", 60, [0, 60, 30, 10], design="flat", sd=1), fl("b", 60, [0, 40, 50, 10], design="pre10", sd=2),
            fl("c", 60, [90, 10, 0, 0]), fl("d", 60, [0, 0, 0, 0], estimated=False), fl("e", 75, [0, 90, 0, 0])]
    out = realism.featureless_check(recs, lengths=(60, 75))
    s = out["60"]
    assert (s["decodes"], s["noSleep"], s["sleepDecodes"]) == (4, 2, 2) and s["noSleepShare"] == 0.5
    assert s["deepPctSleep"] == pytest.approx(100 * 80 / 200) and s["remPctSleep"] == pytest.approx(10.0)
    assert s["tstEst"] == pytest.approx(litprior.tst_est(60)) and not s["lowerBounds"]
    assert s["byDesign"]["pre10"]["2"]["decodes"] == 1 and s["byDesign"]["flat"]["1"]["noSleep"] == 2
    assert s["glShare"] == 0.0
    assert out["75"]["lowerBounds"] and out["75"]["deepPctSleep"] == 0.0


def _fl_stats(L, deep=None, rem=None, no_sleep=0.1, decodes=100):
    t = litprior.target(L)
    b = litprior.band(t.tst)
    return {"decodes": decodes, "noSleepShare": no_sleep, "sleepDecodes": int(decodes * (1 - no_sleep)),
            "deepPctSleep": t.deep if deep is None else deep, "remPctSleep": t.rem if rem is None else rem,
            "tstEst": t.tst, "band": {"deepLo": b.deep_lo, "deepHi": b.deep_hi, "remLo": b.rem_lo, "remHi": b.rem_hi},
            "lowerBounds": t.tst >= 45}


def test_r1_criteria_upper_bounds_always_lower_bounds_from_45_min_of_sleep():
    assert realism._all(realism.r1_criteria(_fl_stats(60, deep=1.0, rem=0.0), 60, "v"))   # t = 43.84: no lower
    assert not realism._all(realism.r1_criteria(_fl_stats(75, deep=12.99), 75, "v"))       # t = 56.99: lower
    assert not realism._all(realism.r1_criteria(_fl_stats(75, rem=0.82), 75, "v"))
    assert not realism._all(realism.r1_criteria(_fl_stats(30, deep=27.46), 30, "v"))
    assert not realism._all(realism.r1_criteria(_fl_stats(150, rem=26.59), 150, "v"))
    assert realism._all(realism.r1_criteria(_fl_stats(150, no_sleep=0.5), 150, "v"))
    assert not realism._all(realism.r1_criteria(_fl_stats(150, no_sleep=0.51), 150, "v"))
    allno = dict(_fl_stats(60), sleepDecodes=0, noSleepShare=1.0, deepPctSleep=float("nan"))
    c = realism.r1_criteria(allno, 60, "v")
    assert not realism._all(c) and not next(x for x in c if x["name"] == "R1.someSleep")["pass"]
    names = [x["name"] for x in realism.r1_criteria(_fl_stats(90), 90, "v")]
    assert names == ["R1.deepHi", "R1.remHi", "R1.deepLo", "R1.remLo", "R1.noSleep", "R1.someSleep"]


# ------------------------------------------------------------------------------------------ LATE tail ----

def test_late_tail_per_length_and_the_larger_one():
    deep_heavy = np.array([[0, 0, 0, 0], [0, 4, 10, 0], [0, 0, 6, 0], [0, 0, 0, 0]], float)  # 80 % DEEP, true 30 %
    plain = np.diag([2.0, 10.0, 6.0, 2.0])
    truly_deep = np.diag([0.0, 4.0, 16.0, 0.0])
    recs = [rec("a", conf=deep_heavy, regime="LATE", pool="M", L=60), rec("b", conf=plain, regime="LATE", pool="R",
                                                                           L=60),
            rec("c", conf=truly_deep, regime="LATE", pool="M", L=90), rec("d", conf=plain, regime="LATE", L=90),
            rec("e", conf=deep_heavy, regime="EARLY", L=60)]
    t = realism.late_tail(recs)
    assert t["perLength"]["60"] == {"sessions": 2, "predShare": 0.5, "trueShare": 0.0, "excess": 0.5,
                                    "predCount": 1, "trueCount": 0}
    assert t["perLength"]["90"]["excess"] == 0.0 and t["tail"] == 0.5


# ------------------------------------------------------------------------------------------ LIT metrics ----

def _lit_sessions(seed=0, n_subj=8, per=4, lengths=ANCHORS):
    """Sessions whose DEEP / REM shares of sleep scatter around the literature target of their length."""
    rng = np.random.default_rng(seed)
    out = []
    for L in lengths:
        t = litprior.target(L)
        for s in range(n_subj):
            for k in range(per):
                n = 2 * L
                awake = int(rng.integers(2, 20))
                deep = int(round(rng.uniform(0.2, 1.8) * t.deep / 100 * (n - awake)))
                rem = int(round(rng.uniform(0.2, 1.8) * t.rem / 100 * (n - awake)))
                out.append({"id": f"L{L}-s{s}-{k}", "subject": f"s{s}", "L": float(L),
                            "counts": np.array([awake, n - awake - deep - rem, deep, rem], float)})
    return out


def test_lit_metrics_of_a_perfect_and_an_all_light_model():
    ss = _lit_sessions()
    w = realism.lit_weight_table(ss, ANCHORS)
    assert sorted(w) == list(ANCHORS) and all(x.feasible and x.lam == 0.0 for x in w.values())
    subjects = {s["id"]: s["subject"] for s in ss}
    perfect = {s["id"]: np.diag(s["counts"]) for s in ss}
    m = realism.lit_metrics(perfect, w, subjects)
    assert m["litDist"] == pytest.approx(0.0, abs=1e-6) and m["guardExcess"] == 0.0
    assert m["accuracy"] == pytest.approx(1.0) and m["kappa"] == pytest.approx(1.0)
    for L in ANCHORS:
        t = litprior.target(L)
        assert m["perLength"][str(L)]["deepPctSleep"] == pytest.approx(t.deep, abs=1e-6)
    light = {}
    for s in ss:
        c = np.zeros((4, 4))
        c[:, 1] = s["counts"]
        light[s["id"]] = c
    lm = realism.lit_metrics(light, w, subjects)
    assert lm["litDist"] == pytest.approx(np.mean([litprior.target(L).deep + litprior.target(L).rem
                                                   for L in ANCHORS]))
    assert lm["accuracy"] == pytest.approx(lm["allLightAccuracy"]) == pytest.approx(m["allLightAccuracy"])
    assert lm["kappa"] == pytest.approx(0.0)
    # the LIT matrix is the mean of the length matrices, each normalised to 1
    mats = []
    for L in ANCHORS:
        C = sum(wi * np.diag(next(s["counts"] for s in ss if s["id"] == i)) for i, wi in zip(w[L].ids, w[L].w))
        mats.append(C / C.sum())
    assert np.array(m["confusion"]) == pytest.approx(np.mean(mats, axis=0))
    with pytest.raises(ValueError, match="no confusion"):
        realism.lit_metrics({}, w, subjects)


def test_guard_excess_is_the_weighted_share_violating_gl_beyond_the_true_compositions():
    ss = _lit_sessions(seed=2, lengths=(60,))
    w = realism.lit_weight_table(ss, (60,))
    subjects = {s["id"]: s["subject"] for s in ss}
    conf = {}
    flagged = set()
    for i, s in enumerate(ss):
        c = np.diag(s["counts"])
        if i % 3 == 0:                                       # predict everything DEEP: > 80.62 % of sleep
            c = np.zeros((4, 4))
            c[:, 2] = s["counts"]
            flagged.add(s["id"])
        conf[s["id"]] = c
    m = realism.lit_metrics(conf, w, subjects)
    ws = dict(zip(w[60].ids, w[60].w))
    counts = {s["id"]: s["counts"] for s in ss}
    pred = sum(ws[i] for i in ws if realism.gl_flag(conf[i].sum(axis=0)))
    true = sum(ws[i] for i in ws if realism.gl_flag(counts[i]))
    assert all(realism.gl_flag(conf[i].sum(axis=0)) for i in flagged)
    assert m["perLength"]["60"]["guardPred"] == pytest.approx(pred)
    assert m["perLength"]["60"]["guardTrue"] == pytest.approx(true)
    assert m["guardExcess"] == pytest.approx(pred - true) and m["guardExcess"] > 0


def test_lit_kappa_is_the_mean_over_subjects_of_their_weighted_matrices():
    ss = _lit_sessions(seed=4, n_subj=5, lengths=(60, 90))
    w = realism.lit_weight_table(ss, (60, 90))
    subjects = {s["id"]: s["subject"] for s in ss}
    rng = np.random.default_rng(9)
    conf = {s["id"]: np.diag(s["counts"]) + rng.integers(0, 5, size=(4, 4)) for s in ss}
    m = realism.lit_metrics(conf, w, subjects)
    want = []
    for subj in sorted({s["subject"] for s in ss}):
        M = np.zeros((4, 4))
        for L in (60, 90):
            for i, wi in zip(w[L].ids, w[L].w):
                if subjects[i] == subj:
                    M += wi * conf[i]
        want.append(realism.report.kappa(M))
    assert m["kappa"] == pytest.approx(np.mean(want)) and m["kappaDefined"] == 5


def test_sleep_wake_kappa_and_default_scope():
    recs = [rec("a", "1", diag(5, 10, 5, 5)), rec("b", "2", np.array([[5, 5, 0, 0], [5, 10, 0, 0], [0, 0, 5, 0],
                                                                       [0, 0, 0, 5]], float))]
    k = realism.sleep_wake_kappa(recs)
    assert k["subjectKappa"]["1"] == 1.0 and k["kappa"] == pytest.approx(np.mean(list(k["subjectKappa"].values())))
    ss = _lit_sessions(seed=6)
    w = realism.lit_weight_table(ss, ANCHORS)
    perfect = {s["id"]: np.diag(s["counts"]) for s in ss}
    fls = {str(L): realism.featureless_check([fl(f"x{L}", L, [0, 80, 15, 5])], lengths=(L,))[str(L)]
           for L in ANCHORS}
    d = realism.default_scope(perfect, w, {s["id"]: s["subject"] for s in ss}, fls)
    assert d["lengths"] == [90, 120, 150, 170] and d["litDist"] == pytest.approx(0, abs=1e-6)
    assert all(c["L"] >= 90 for c in d["r1Criteria"])


# -------------------------------------------------------------------------------------- reported metrics ----

def test_nearest_anchor_cells():
    got = {L: realism.nearest_anchor(L) for L in (30, 44.5, 45, 60, 74.9, 75, 104, 105, 134, 135, 159.5, 160, 179)}
    assert got == {30: 30, 44.5: 30, 45: 60, 60: 60, 74.9: 60, 75: 90, 104: 90, 105: 120, 134: 120, 135: 150,
                   159.5: 150, 160: 170, 179: 170}


def test_prior_matched_metrics_by_hand():
    C = np.array([[8, 2, 0, 0], [2, 30, 6, 2], [0, 4, 16, 0], [0, 2, 0, 8]], float)
    recs = [rec("o", conf=C, pool="O", L=60), rec("w", conf=np.diag([1.0, 2, 3, 4]), pool="W", L=47)]
    p = realism.prior_matched(recs, min_row=1)
    pooled = C + np.diag([1.0, 2, 3, 4])
    Ck = pooled / pooled.sum(axis=1, keepdims=True)
    a = C.sum(axis=1)[0] / C.sum()                       # the O sessions' true AWAKE share
    t = litprior.target(60)
    pi = np.array([a, (1 - a) * (1 - t.deep / 100 - t.rem / 100), (1 - a) * t.deep / 100, (1 - a) * t.rem / 100])
    acc = pi @ np.diag(Ck)
    q = pi @ Ck
    assert p["perAnchor"]["60"]["accuracy"] == pytest.approx(acc)
    assert p["perAnchor"]["60"]["E"] == pytest.approx(100 * np.abs(q - pi).sum())
    assert p["perAnchor"]["60"]["kappa"] == pytest.approx((acc - pi @ q) / (1 - pi @ q))
    assert p["accuracy"] == pytest.approx(acc) and list(p["perAnchor"]) == ["60"]
    # a row with fewer than min_row chunks is replaced by the row pooled over all cells
    recs2 = recs + [rec("o90", conf=np.diag([5.0, 30, 10, 1]), pool="O", L=90)]
    p2 = realism.prior_matched(recs2, min_row=5)
    allrows = pooled + np.diag([5.0, 30, 10, 1])
    assert p2["perAnchor"]["90"]["predicted"][3] == pytest.approx(
        (np.array(p2["perAnchor"]["90"]["prior"]) @ np.vstack([np.eye(4)[:3], allrows[3] / allrows[3].sum()]))[3])


def test_tracking_slopes_of_a_perfect_and_a_constant_model():
    rng = np.random.default_rng(3)
    perfect, const = [], []
    for i in range(30):
        L = ANCHORS[i % 6]
        t = rng.integers(1, 40, size=4).astype(float)
        perfect.append(rec(f"p{i}", conf=np.diag(t), pool=("O", "M", "R")[i % 3], L=L))
        const.append(rec(f"c{i}", conf=np.outer(t, [0.1, 0.5, 0.3, 0.1]), pool=("O", "M", "R")[i % 3], L=L))
    tp = realism.tracking(perfect)
    assert tp["DEEP"]["slope"] == pytest.approx(1.0) and tp["DEEP"]["rho"] == pytest.approx(1.0)
    assert tp["REM"]["withinSlopeMean"] == pytest.approx(1.0) and tp["DEEP"]["n"] == 30
    tc = realism.tracking(const, share="sleep")
    assert tc["DEEP"]["slope"] == pytest.approx(0.0, abs=1e-9) and math.isnan(tc["DEEP"]["rho"])


def test_onset_bins_and_the_eastern_clock():
    rows = {("s", e): realism.EpochRow(True, "DEEP" if e >= 10 else "LIGHT", "DEEP" if e >= 15 else "LIGHT",
                                       (0, 0, 0, 0)) for e in range(60)}
    rows[("s", 5)] = realism.EpochRow(True, "LIGHT", "UNKNOWN", (0, 0, 0, 0))
    rows[("other", 0)] = realism.EpochRow(True, "REM", "REM", (0, 0, 0, 0))
    b = realism.onset_bins(rows, ["s"])
    assert list(b) == ["0-10", "10-20", "20-30", "30-40", "40-50", "50-60"]
    assert b["0-10"]["epochs"] == 9 and b["10-20"]["pred"]["DEEP"] == 0.5 and b["10-20"]["true"]["DEEP"] == 1.0
    assert realism.et_clock_bin(1610695800000) == "before 03:00"          # 2021-01-15 07:30 UTC = 02:30 EST
    assert realism.et_clock_bin(1610699400000) == "03:00-05:00"           # 03:30 EST
    assert realism.et_clock_bin(1626341400000) == "after 05:00"           # 2021-07-15 09:30 UTC = 05:30 EDT
    assert realism.et_clock_bin(1610661600000) == "before 03:00"          # 2021-01-14 22:00 UTC = 17:00 EST
    recs = [rec("m", pool="M", regime="LATE", startMs=1610699400000), rec("o", pool="O", startMs=1610699400000)]
    t = realism.clock_table(recs)
    assert list(t) == ["03:00-05:00"] and t["03:00-05:00"]["sessions"] == 1


def test_perturbation_effects_are_paired_per_session_changes():
    orig = [rec("a-nap60", "1", diag(2, 10, 6, 2)), rec("b-nap60", "2", diag(2, 10, 6, 2)),
            rec("c-nap60", "3", diag(2, 10, 6, 2), estimated=False)]
    more_deep = np.array([[2, 0, 0, 0], [0, 6, 4, 0], [0, 0, 6, 0], [0, 0, 0, 2]], float)
    pert = [rec("a-nap60~amp0.5", "1", more_deep), rec("b-nap60~amp0.5", "2", diag(2, 10, 6, 2)),
            rec("c-nap60~amp0.5", "3", more_deep), rec("a-nap60~noctx", "1", diag(2, 10, 6, 2))]
    e = realism.perturbation_effects(orig, pert)
    assert sorted(e) == ["amp0.5", "noctx"] and e["amp0.5"]["pairs"] == 2
    assert e["amp0.5"]["DEEP"]["meanChange"] == pytest.approx((0.2 + 0.0) / 2)
    b = e["amp0.5"]["DEEP"]["subjectBootstrap"]
    assert b["n"] == 2 and b["meanDiff"] == pytest.approx(0.1)
    assert e["noctx"]["REM"]["meanChange"] == 0.0


def test_late_start_effects_against_the_unshifted_onset_sessions():
    onset = [rec("n1-nap60", "1", diag(2, 10, 6, 2)), rec("n2-nap60", "2", diag(4, 10, 6, 0))]
    wake_start = np.array([[2, 0, 0, 0], [3, 7, 0, 0], [0, 0, 6, 0], [0, 0, 0, 2]], float)
    late = [rec("n1-ls10x60", "1", wake_start, original="n1-nap60", delta=10),
            rec("n2-ls10x60", "2", diag(4, 10, 6, 0), original="n2-nap60", delta=10),
            rec("n2-ls20x60", "2", diag(4, 10, 6, 0), original="nX-nap60", delta=20)]
    e = realism.late_start_effects(onset, late)
    assert list(e) == ["ls10x60"] and e["ls10x60"]["pairs"] == 2
    assert e["ls10x60"]["onset"]["accuracy"] == 1.0
    assert e["ls10x60"]["lateStart"]["accuracy"] == pytest.approx(37 / 40)
    assert e["ls10x60"]["lateStart"]["predAwake"] == pytest.approx(9 / 40)
    assert e["ls10x60"]["accuracyDiff"]["meanDiff"] == pytest.approx((17 / 20 - 1.0) / 2)


# --------------------------------------------------------------------------------------------- rule (i) ----

def _model(r=0.5, ci=0.1, bid=0.1, true_d=0.3, true_r=0.25, tail=0.0, ge=0.0, litdist=3.0, litmid=4.0,
           fl_over=None, acc=0.72, light=0.63, kappa=0.3):
    walch, bids = {}, {}
    for v in V:
        rr = r[v] if isinstance(r, dict) else r
        con = {"deltaDeep": rr * true_d, "deltaRem": rr * true_r, "trueDeltaDeep": true_d, "trueDeltaRem": true_r,
               "rDeep": rr, "rRem": rr, "ciDeep": [ci, 0.5], "ciRem": [ci, 0.5]}
        fl_ = {str(L): _fl_stats(L) for L in ANCHORS + MID_ANCHORS}
        for (fv, L), patch in (fl_over or {}).items():
            if fv == v:
                fl_[str(L)] = dict(fl_[str(L)], **patch)
        walch[v] = {"contrast": con, "featureless": fl_, "lateTail": {"tail": tail}, "lit": {
            "litDist": litdist, "guardExcess": ge, "accuracy": acc, "allLightAccuracy": light, "kappa": kappa},
            "litMid": {"litDist": litmid}}
        bids[v] = {"contrast": {"deltaDeep": bid, "deltaRem": bid, "trueDeltaDeep": 0.48, "trueDeltaRem": 0.26}}
    return {"walch": walch, "bidsleep": bids}


def _auc(over=None):
    over = over or {}
    base = {"ANCHOR": {"AWAKE": 0.0, "DEEP": 0.0, "REM": 0.0}, "EARLY": {"DEEP": 0.0}, "LATE": {"REM": 0.0}}
    out = {}
    for v in V:
        d = copy.deepcopy(base)
        for (fv, q, s), val in over.items():
            if fv == v:
                d[q][s] = val
        out[v] = {"delta": d}
    return out


def _metrics(**cands):
    m = {"N1": _model(r={"instantaneous": 0.5, "mean2min": -0.1}, litdist=12.0)}
    for c in ("C0", "C1", "C2"):
        m[c] = cands.get(c, _model())
    return m


def _rule(metrics=None, auc=None, **kw):
    metrics = metrics or _metrics()
    auc = auc or {c: _auc() for c in ("C0", "C1", "C2")}
    return realism.rule_i(metrics, auc, **kw)


def test_rule_i_all_pass_tier_1_and_ties_within_1_pp_go_to_the_fewest_changes():
    m = _metrics(C0=_model(litdist=4.0), C1=_model(litdist=5.5), C2=_model(litdist=3.0))
    r = _rule(m)
    assert r["tier"] == 1 and r["tier1"] == ["C0", "C1", "C2"] and r["qualifying"] == ["C0", "C1", "C2"]
    assert r["adopted"] == "C0" and r["tied"] == ["C0", "C2"]                 # 4.0 - 3.0 = 1.0 (inclusive)
    m = _metrics(C0=_model(litdist=4.01), C1=_model(litdist=10.0), C2=_model(litdist=3.0))
    assert _rule(m)["adopted"] == "C2"
    assert _rule(m, dropped={"C2": "G4 failed"})["adopted"] == "C0"
    assert "C2" not in _rule(m, dropped={"C2": "G4 failed"})["candidates"]


@pytest.mark.parametrize("key", [("instantaneous", "ANCHOR", "AWAKE"), ("mean2min", "ANCHOR", "DEEP"),
                                 ("mean2min", "ANCHOR", "REM")])
def test_rule_i_d_anchor_criteria_fail_alone(key):
    auc = {c: _auc() for c in ("C0", "C1", "C2")}
    auc["C2"] = _auc({key: -0.0201})
    r = _rule(auc=auc)
    assert not r["candidates"]["C2"]["D"]["pass"] and not r["candidates"]["C2"]["tier1"]
    auc["C2"] = _auc({key: -0.02})
    assert _rule(auc=auc)["candidates"]["C2"]["D"]["pass"]


@pytest.mark.parametrize("key", [("instantaneous", "EARLY", "DEEP"), ("mean2min", "LATE", "REM")])
def test_rule_i_d_early_deep_and_late_rem_allow_minus_0_05(key):
    auc = {c: _auc() for c in ("C0", "C1", "C2")}
    auc["C1"] = _auc({key: -0.05})
    assert _rule(auc=auc)["candidates"]["C1"]["D"]["pass"]
    auc["C1"] = _auc({key: -0.0501})
    assert not _rule(auc=auc)["candidates"]["C1"]["D"]["pass"]
    auc["C1"] = _auc({key: float("nan")})
    assert not _rule(auc=auc)["candidates"]["C1"]["D"]["pass"]


@pytest.mark.parametrize("model,fails", [
    (_model(r={"instantaneous": 0.5, "mean2min": 0.249}), "F.rDEEP"),
    (_model(r={"instantaneous": 0.5, "mean2min": 0.25}), None),
    (_model(ci=0.0), "F.ciLowDEEP"),
    (_model(bid=0.0), "F.bidsleepDEEP"),
    (_model(true_d=0.0), "F.rDEEP"),
    (_model(true_r=-0.1), "F.rREM"),
])
def test_rule_i_f_criteria_fail_alone(model, fails):
    r = _rule(_metrics(C2=model))
    f = r["candidates"]["C2"]["F"]
    if fails is None:
        assert f["pass"] and r["candidates"]["C2"]["tier1"]
    else:
        assert not f["pass"] and fails in [c["name"] for c in f["criteria"] if not c["pass"]]
        assert r["candidates"]["C2"]["D"]["pass"] and r["candidates"]["C2"]["R"]["pass"]


@pytest.mark.parametrize("over,ok", [
    ({("instantaneous", 150): {"deepPctSleep": 38.46}}, False),          # above the DEEP upper bound at 150
    ({("mean2min", 75): {"deepPctSleep": 12.99}}, False),                # below the DEEP lower bound (t >= 45)
    ({("mean2min", 60): {"deepPctSleep": 12.99, "remPctSleep": 0.0}}, True),   # no lower bounds below 45 min
    ({("mean2min", 45): {"remPctSleep": 18.73}}, False),                 # mid-anchors count
    ({("instantaneous", 170): {"noSleepShare": 0.51}}, False),
    ({("instantaneous", 170): {"noSleepShare": 0.5}}, True),
    ({("instantaneous", 30): {"sleepDecodes": 0, "noSleepShare": 0.4}}, False),
])
def test_rule_i_r1_criteria(over, ok):
    r = _rule(_metrics(C2=_model(fl_over=over)))
    assert r["candidates"]["C2"]["R"]["pass"] == ok


@pytest.mark.parametrize("model,name", [(_model(tail=0.101), "R2.lateTail"), (_model(ge=0.051), "R3.guardExcess"),
                                        (_model(litmid=5.01), "R4.midMinusAnchor"),
                                        (_model(litdist=12.01, litmid=12.5), "R5.litDistVsN1")])
def test_rule_i_r2_to_r5_fail_alone(model, name):
    r = _rule(_metrics(C2=model))
    bad = [c["name"] for c in r["candidates"]["C2"]["R"]["criteria"] if not c["pass"]]
    assert bad == [name]
    ok = {"R2.lateTail": _model(tail=0.1), "R3.guardExcess": _model(ge=0.05), "R4.midMinusAnchor": _model(litmid=5.0),
          "R5.litDistVsN1": _model(litdist=12.0, litmid=12.5)}[name]
    assert _rule(_metrics(C2=ok))["candidates"]["C2"]["R"]["pass"]


def test_rule_i_second_tier_only_when_the_first_is_empty():
    weak = _model(r={"instantaneous": 0.5, "mean2min": -0.1}, litdist=2.0)        # fails F, F0 holds (r >= N1's)
    m = _metrics(C0=weak, C1=_model(litdist=6.0), C2=_model(true_d=0.3, ci=-0.01, litdist=5.0))
    r = _rule(m)
    assert r["tier"] == 1 and r["adopted"] == "C1" and r["candidates"]["C0"]["tier2"] and r["tier2"] == []
    m = _metrics(C0=weak, C1=_model(ci=0.0, litdist=6.0), C2=_model(ci=0.0, litdist=1.5, litmid=3.0))
    r = _rule(m)
    assert r["tier1"] == [] and r["tier"] == 2 and r["tier2"] == ["C0", "C1", "C2"]
    assert r["adopted"] == "C0" and r["tied"] == ["C0", "C2"] and r["decision"] == "C0"
    m = _metrics(C0=_model(ci=0.0, litdist=20.0), C1=_model(ci=0.0, litdist=20.0), C2=_model(ci=0.0, litdist=20.0))
    r = _rule(m)
    assert r["tier"] is None and r["adopted"] is None and r["decision"] == "keep N1"


def test_rule_i_f0_against_the_reference():
    # N1: r = 0.5 (instantaneous, right direction) and -0.1 (mean2min, wrong direction)
    cases = [({"instantaneous": 0.0, "mean2min": -0.1}, True), ({"instantaneous": -0.001, "mean2min": -0.1}, False),
             ({"instantaneous": 0.0, "mean2min": -0.1001}, False), ({"instantaneous": 0.1, "mean2min": 0.0}, True)]
    for rr, ok in cases:
        r = _rule(_metrics(C2=_model(r=rr)))
        assert r["candidates"]["C2"]["F0"]["pass"] == ok, rr
        assert not r["candidates"]["C2"]["F"]["pass"]
    r = _rule(_metrics(C2=_model(r=0.3, true_d=0.0)))
    assert not r["candidates"]["C2"]["F0"]["pass"]                           # true contrast <= 0 fails F0 too


def test_rule_i_evaluates_f_and_r_for_n1_with_r5_by_definition():
    r = _rule()
    ref = r["reference"]
    assert not ref["F"]["pass"] and ref["R"]["pass"]
    r5 = next(c for c in ref["R"]["criteria"] if c["name"] == "R5.litDistVsN1")
    assert r5["pass"] and r5["note"] == "holds for N1 by definition"
    assert ref["litDist"] == 12.0
    reasons = realism.failure_reasons(r)
    assert any(x.startswith("N1 failed F: F.rDEEP") for x in reasons)


def test_failure_reasons_are_generated_from_the_criteria():
    m = _metrics(C1=_model(r=0.12), C2=_model(fl_over={("mean2min", 150): {"deepPctSleep": 40.0}}))
    r = _rule(m, dropped={"C0": "G4 failed: inner scores not reproduced"})
    reasons = realism.failure_reasons(r)
    assert "C0 dropped: G4 failed: inner scores not reproduced" in reasons
    c1 = next(x for x in reasons if x.startswith("C1 failed F"))
    assert "F.rDEEP = 0.120 (>= 0.250 required, worseVariant instantaneous)" in c1
    c2 = next(x for x in reasons if x.startswith("C2 failed R"))
    assert "R1.deepHi = 40.000" in c2 and "L 150" in c2 and "variant mean2min" in c2


# -------------------------------------------------------------------------------------------- rule (ii) ----

def _lit(acc=0.70, light=0.63, kappa=0.2):
    return {v: {"accuracy": acc, "allLightAccuracy": light, "kappa": kappa} for v in V}


def test_rule_ii_policies():
    sw = {v: 0.5 for v in V}
    assert realism.rule_ii("X", True, True, _lit(), sw)["policy"] == "STAGES"
    assert realism.rule_ii("X", True, True, _lit(acc=0.6599), sw)["policy"] == "SLEEP_WAKE"     # margin < 0.03
    assert realism.rule_ii("X", True, True, _lit(acc=0.66), sw)["policy"] == "STAGES"
    assert realism.rule_ii("X", True, True, _lit(kappa=0.149), sw)["policy"] == "SLEEP_WAKE"
    assert realism.rule_ii("X", False, True, _lit(), sw)["policy"] == "SLEEP_WAKE"              # tier 2: F failed
    assert realism.rule_ii("X", True, False, _lit(), sw)["policy"] == "SLEEP_WAKE"
    low = {"instantaneous": 0.5, "mean2min": 0.199}
    assert realism.rule_ii("N1", False, False, _lit(), low)["policy"] == "SKIP"
    r = realism.rule_ii("N1", True, True, _lit(), {"instantaneous": 0.5, "mean2min": 0.2})
    assert r["policy"] == "STAGES" and r["sleepWakeCriterion"]["value"] == 0.2
    assert [c["name"] for c in r["stagesCriteria"]] == ["ii.F", "ii.R", "ii.litMargin", "ii.litKappa"]


# ------------------------------------------------------------------------------------------- rule (iii) ----

def test_rule_iii_constants_come_from_the_literature_prior():
    assert realism.REM_FREE_MAX_PCT == round(litprior.rem_ceiling_short_naps()[0], 1)
    assert realism.FAIL_SHARE_MAX == round(litprior.wilson(2, 70)[1], 3)
    assert realism.FAIL_SHARE_ALL93 == round(litprior.wilson(4, 93)[1], 3)
    assert realism.MIN_USABLE == math.ceil(0.9 * 21) and realism.M60_MIN_USABLE == math.ceil(0.7 * 21)


def _win(group, window, deep, rem, light, awake=4.0, complete=True, usable=True):
    c = np.zeros((4, 4))
    c[1] = [awake, light, deep, rem]                     # silver labels do not matter: only column sums are used
    r = rec(f"{group}-{window}-{deep}-{rem}-{light}", conf=c, estimated=usable, group=group, window=window,
            complete=complete)
    return r


def test_external_summary_from_decoded_windows():
    wins = [_win("NREM", "oh", 40, 4, 76), _win("NREM", "oh", 20, 0, 100), _win("NREM", "oh", 4, 0, 20),   # 12 min
            _win("REM", "oh", 40, 40, 100), _win("REM", "oh", 120, 0, 40),                                 # 60 % DEEP
            _win("NREM", "rec", 40, 4, 76), _win("NREM", "rec", 30, 0, 90),
            _win("WAKE", "rec", 0, 0, 10, awake=100), _win("WAKE", "rec", 2, 0, 10, awake=100),
            _win("WAKE", "rec", 50, 0, 10, awake=10, complete=False), _win("REM", "oh", 1, 1, 1, usable=False)]
    e = realism.external_summary(wins)
    assert e["usable"] == {"NREM": 3, "REM": 2, "WAKE": 2}
    assert e["nremDeepPct"] == pytest.approx(np.mean([100 * 40 / 120, 100 * 20 / 120]))     # TST > 15 only
    assert e["nremRemPct"] == pytest.approx(np.mean([100 * 4 / 120, 0.0]))
    assert e["remDeepPct"] == pytest.approx(np.mean([100 * 40 / 180, 100 * 120 / 160]))
    # failures: the 12-min NREM nap (TST <= 15) and the REM-group nap with 60 min of DEEP (> 54 min)
    assert e["failShare"] == pytest.approx(2 / 5)
    nrem = [40 / 124, 30 / 124]
    wake = [0.0, 2 / 112]
    assert e["deepDiff"]["diff"] == pytest.approx(np.mean(nrem) - np.mean(wake))
    assert e["deepDiff"] == realism.group_diff_bootstrap(nrem, wake)


def test_group_diff_bootstrap_draws_within_groups():
    a, b = [0.3, 0.5, 0.4, 0.6], [0.1, 0.0, 0.2]
    r = realism.group_diff_bootstrap(a, b, n_boot=1000, seed=0)
    rng = np.random.default_rng(0)
    ma = np.array(a)[rng.integers(0, 4, size=(1000, 4))].mean(axis=1)
    mb = np.array(b)[rng.integers(0, 3, size=(1000, 3))].mean(axis=1)
    assert [r["ciLow"], r["ciHigh"]] == pytest.approx(list(np.quantile(ma - mb, [0.025, 0.975])))
    assert r["diff"] == pytest.approx(0.45 - 0.1) and math.isnan(realism.group_diff_bootstrap([], b)["diff"])


def _ext(nrem_deep=30.0, nrem_rem=3.0, rem_deep=24.0, rem_rem=15.0, fail=0.05, d_lo=0.05, usable=21, d_diff=0.2):
    return {v: {"usable": {"NREM": usable, "REM": 21, "WAKE": 21}, "nremDeepPct": nrem_deep, "nremRemPct": nrem_rem,
                "remDeepPct": rem_deep, "remRemPct": rem_rem, "failShare": fail, "failShareG": 0.0,
                "deepDiff": {"diff": d_diff, "ciLow": d_lo, "ciHigh": 0.3}} for v in V}


def _pol(policy, sw=True):
    return {"policy": policy, "sleepWakeCriterion": {"pass": sw}}


def test_rule_iii_installs_a_first_tier_candidate_that_passes_a_to_d():
    r = realism.rule_iii({"S": _ext(), "X": _ext()}, "C2", 1, _pol("SLEEP_WAKE"), _pol("STAGES"))
    assert r["install"] and r["inEffect"] == "X" and r["policy"] == "STAGES" and not r["demoted"]
    assert r["installGates"] == ["a", "c", "d"] + ["b"]


@pytest.mark.parametrize("ext,gate", [(_ext(nrem_deep=46.44), "a"), (_ext(rem_deep=12.99), "a"),
                                      (_ext(rem_rem=26.59), "a"), (_ext(nrem_rem=6.91), "b"), (_ext(fail=0.099), "c"),
                                      (_ext(d_lo=0.0), "d"), (_ext(d_diff=0.0), "d"), (_ext(d_diff=-0.01), "d")])
def test_rule_iii_each_gate_blocks_a_first_tier_installation(ext, gate):
    r = realism.rule_iii({"S": _ext(), "X": ext}, "C1", 1, _pol("STAGES"), _pol("STAGES"))
    assert not r["install"] and r["inEffect"] == "N1"
    assert not r["models"]["X"]["criteria"][gate]["pass"]
    assert r["policy"] == "STAGES" and not r["demoted"]                         # N1 passed its own checks


def test_rule_iii_b_does_not_gate_a_second_tier_candidate():
    r = realism.rule_iii({"S": _ext(), "X": _ext(nrem_rem=9.0)}, "C2", 2, _pol("SLEEP_WAKE"), _pol("SLEEP_WAKE"))
    assert r["install"] and r["installGates"] == ["a", "c", "d"] and not r["models"]["X"]["criteria"]["b"]["pass"]
    assert r["policy"] == "SLEEP_WAKE" and not r["demoted"]


def test_rule_iii_void_installs_nothing_and_demotes_nothing():
    r = realism.rule_iii({"S": _ext(), "X": _ext(usable=18)}, "C2", 1, _pol("STAGES"), _pol("STAGES"))
    assert not r["install"] and r["models"]["X"]["void"] and r["policy"] == "STAGES"
    r = realism.rule_iii({"S": _ext(fail=0.5), "X": _ext()}, "C2", 1, _pol("STAGES"), _pol("STAGES"),
                         global_void=["yasa-classifier digest mismatch"])
    assert not r["install"] and r["policy"] == "STAGES" and not r["demoted"]
    assert r["models"]["S"]["void"] == ["yasa-classifier digest mismatch"]


def test_rule_iii_demotes_stages_of_the_model_in_effect_that_fails_a_b_or_c():
    for ext in (_ext(fail=0.2), _ext(nrem_rem=7.0), _ext(nrem_deep=50.0)):
        r = realism.rule_iii({"S": ext}, None, None, _pol("STAGES", sw=True), None)
        assert r["inEffect"] == "N1" and r["demoted"] and r["policy"] == "SLEEP_WAKE"
        assert realism.rule_iii({"S": ext}, None, None, _pol("STAGES", sw=False), None)["policy"] == "SKIP"
    r = realism.rule_iii({"S": _ext(d_lo=-0.1)}, None, None, _pol("STAGES"), None)
    assert not r["demoted"] and r["policy"] == "STAGES"                          # (d) never demotes
    r = realism.rule_iii({"S": _ext(fail=0.2)}, None, None, _pol("SLEEP_WAKE"), None)
    assert r["policy"] == "SLEEP_WAKE" and not r["demoted"]                      # never raises a policy
    with pytest.raises(ValueError):
        realism.rule_iii({"S": _ext()}, "C2", 1, _pol("STAGES"), None)


def test_rule_iii_bands_at_the_table_1_tsts():
    c = realism.iii_criteria(_ext())
    a = {x["name"]: x["threshold"] for x in c["a"]["criteria"] if x["variant"] == "instantaneous"}
    assert {k: round(v, 2) for k, v in a.items()} == {
        "iii.a.nremDeepLo": 13.00, "iii.a.nremDeepHi": 46.43, "iii.a.remDeepLo": 13.00, "iii.a.remDeepHi": 49.01,
        "iii.a.remRemLo": 5.10, "iii.a.remRemHi": 26.58}
    assert c["b"]["criteria"][0]["threshold"] == 6.9 and c["c"]["criteria"][0]["threshold"] == 0.098
    # (d): the point estimate and the CI lower bound, each > 0, per variant
    assert [x["name"] for x in c["d"]["criteria"] if x["variant"] == "instantaneous"] == [
        "iii.d.deepDiff", "iii.d.deepDiffCiLow"] and c["d"]["pass"]
