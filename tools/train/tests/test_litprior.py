"""Literature prior (pulsetrain/litprior.py): every number tabled in sections 5.0-5.4 and Appendix B of the
realistic-nap pre-registration (docs/prereg/prereg-naps-realistic.md) is recomputed from the arm table under rule I, and
the literature reweighting of section 5.5 (exponential tilting, BFGS + Newton polish, feasibility rule) is checked on
synthetic counts."""

import math

import numpy as np
import pytest

from pulsetrain import litprior as lp


def r2(x):
    return round(float(x) + 1e-12, 2)


# ------------------------------------------------------------------------------------------- Appendix B ----

# id, DOI, n, prior class, TST, DEEP %, REM % (None = not available), basis, N3 min, WASO % of the sleep period,
# uses (row = the target row / band of its bin, W = the WASO share, P = the N3-minute plateau, GL = a GL dispersion arm)
INCLUDED = [
    ("Leong23-10", "10.1093/sleep/zsad025", 32, "R1", 11.53, 2.57, 1.50, "R", 0.31, 19.09, "row W GL"),
    ("Leong23-30", "10.1093/sleep/zsad025", 32, "R1", 31.68, 27.45, 0.83, "R", 8.52, 12.32, "row W GL"),
    ("Mednick02-S", "10.1038/nn864", None, "R1", 29.1, 22.68, 6.87, "D", 6.6, None, "row"),
    ("Leong23-60", "10.1093/sleep/zsad025", 32, "R1", 61.88, 35.12, 11.68, "R", 21.57, 9.48, "row W P GL"),
    ("Mednick02-L", "10.1038/nn864", None, "R1", 59.6, 45.97, 14.93, "D", 27.4, None, "row P"),
    ("McDevitt12", "10.1016/j.physbeh.2012.05.021", 27, "R1", 74.2, 34.9, 15.8, "R", 25.9, None, "row P GL"),
    ("Cai09", "10.1073/pnas.0900271106", 40, "R1", 66.09, 33.76, 15.24, "D", 22.31, None, "row P"),
    ("Mednick03-60", "10.1038/nn1078", 26, "R0", 59.3, 34.06, 7.08, "D", 20.2, None, "row P"),
    ("Nishida07", "10.1371/journal.pone.0000341", 14, "R0", 61.98, 46.43, 16.94, "D", 28.78, None, "row P"),
    ("Cousins19", "10.1093/sleep/zsy207", 27, "R0", 60.35, 33.52, 8.63, "D", 20.23, None, "row P GL"),
    ("Bergamo26", "10.1111/jsr.70253", 19, "R0", 62.06, 24.04, 6.61, "D", 14.92, None, "row P GL"),
    ("Ong20", "10.1093/sleep/zsaa058", 20, "R0", 63.2, 22.63, 11.55, "D", 14.3, 15.05, "row W P GL"),
    ("Chen21-Y", "10.1111/psyp.13701", 60, "R0", 61.50, 31.25, 10.15, "D", 19.22, 9.64, "row W P GL"),
    ("Scullin17-Y", "10.1093/sleep/zsx035", 30, "R0", 73.20, 25.85, 13.83, "D", 18.92, 14.98, "row W P GL"),
    ("Mednick08", "10.1016/j.bbr.2008.04.028", 19, "R0", 69.38, 18.09, 12.80, "D", 12.55, None, "row P GL"),
    ("Ujma15", "10.1038/srep17159", 79, "R0", 70.39, 16.24, 8.14, "D", 11.43, None, "row P GL"),
    ("Fogel14-Y", "10.1002/hbm.22426", 13, "R0", 72.08, 15.41, 12.10, "R", 11.45, 13.10, "row W P GL"),
    ("Lau15", "10.1371/journal.pone.0125752", 40, "R0", 77.56, 19.63, 9.34, "R", 16.50, 11.22, "row W P GL"),
    ("Lo14", "10.1371/journal.pone.0108100", 34, "R0", 64.1, 31.51, 18.72, "D", 20.2, None, "row P GL"),
    ("Fudge24-1h", "10.3390/clockssleep6030027", 40, "R0", 53.5, 13.0, 5.1, "R", None, None, "row GL"),
    ("Fudge24-2h", "10.3390/clockssleep6030027", 40, "R0", 58.9, 17.3, 5.2, "R", None, None, "row GL"),
    ("Mednick03-90", "10.1038/nn1078", 19, "R0", 96.3, 49.01, 26.58, "D", 47.2, None, "row P"),
    ("Whitehurst16", "10.1073/pnas.1518202113", 60, "R0", 83.19, 27.30, 15.81, "D", 22.71, 12.62, "row W P GL"),
    ("Whitehurst18", "10.1016/j.nbscr.2018.03.001", 32, "R0", 96.36, 18.53, 14.22, "D", 17.86, 11.37, "row W P GL"),
    ("Baran16-Y", "10.1162/jocn_a_00938", 13, "R0", 87.4, 23.0, 5.7, "R", None, 5.41, "row W GL"),
    ("Dinges87-P6", "10.1093/sleep/10.4.313", 8, "R0", 80.0, 18.25, None, "D", 14.6, None, "row P"),
]
# GL dispersion column of Appendix B: (N3, REM, kind); None = not given
GL_DISP = {
    "Leong23-10": ((8.23, "SD%"), None), "Leong23-30": ((22.63, "SD%"), (4.38, "SD%")),
    "Leong23-60": ((21.16, "SD%"), (14.26, "SD%")), "McDevitt12": ((16.3, "SD%"), (13.8, "SD%")),
    "Cousins19": ((13.15, "SD"), (6.13, "SD")), "Bergamo26": ((11.03, "SD"), (5.60, "SD")),
    "Ong20": ((2.5, "SEM"), (2.1, "SEM")), "Chen21-Y": ((1.96, "SEM"), (1.09, "SEM")),
    "Scullin17-Y": ((17.16, "SD"), (6.95, "SD")), "Mednick08": ((13, "SD"), (12, "SD")),
    "Ujma15": ((12.07, "SD"), (6.60, "SD")), "Fogel14-Y": ((3.10, "SEM%"), None),
    "Lau15": ((15.79, "SD%"), (9.62, "SD%")), "Lo14": ((14.0, "SD"), (12.5, "SD")),
    "Fudge24-1h": ((19.5, "SD%"), (8.3, "SD%")), "Fudge24-2h": ((20.4, "SD%"), (10.2, "SD%")),
    "Whitehurst16": ((14.83, "SD"), (10.58, "SD")), "Whitehurst18": ((14.61, "SD"), (12.85, "SD")),
    "Baran16-Y": ((12.3, "SD%"), (7.6, "SD%")),
}
# excluded arms of Appendix B: TST / DEEP % / REM % as tabled (decimals as tabled) and the listed reasons
EXCLUDED = {
    "Sargent26-1h": ((52.4, 1), (56.7, 1), (3.6, 1), ("I1",)),
    "Sargent26-2h": ((105.0, 1), (35.3, 1), (12.0, 1), ("I1",)),
    "Boukhris26-90": ((56, 0), (16.1, 1), (12.5, 1), ("I1", "I4")),
    "Lau11-I": ((63.17, 2), (31.30, 2), (8.98, 2), ("I3",)), "Lau11-D": ((77.45, 2), (28.64, 2), (16.60, 2), ("I3",)),
    "Saletin17": ((33.5, 1), (25.4, 1), (2.0, 1), ("I2",)), "Takahashi00": ((10.2, 1), (0, 0), None, ("I2",)),
    "Cousins21-5h": ((77.41, 2), (39.5, 1), None, ("I1", "I2")),
    "Cousins21-6.5h": ((75.81, 2), (40.1, 1), None, ("I1", "I2")),
    "Dinges87-P30": ((117, 0), (54.1, 1), None, ("I2",)), "Li25-TSD": ((28.48, 2), (64.7, 1), (4.9, 1), ("I2", "I3")),
    "Cohen10": ((100.3, 1), (40.9, 1), (8.4, 1), ("I3",)),
    "Subram26-Y": ((61.02, 2), (31.32, 2), (10.41, 2), ("I5",)),
    "Cellini16": ((80.05, 2), (27.26, 2), (15.22, 2), ("I5",)),
    "Nicolas22": ((79.1, 1), (22.1, 1), (12.1, 1), ("I5",)),
    "McDevitt26-NREM": ((49.27, 2), (39.92, 2), (0.25, 2), ("I5",)),
    "McDevitt26-REM": ((89.91, 2), (24.90, 2), (17.90, 2), ("I5",)),
}
# Appendix B lists the young / older Subramoniam pair under their shared reason I5; the older arm fails I1 first
FIRST_REASON_EXCEPTIONS = {"Subram26-O"}


def test_rule_i_keeps_exactly_the_26_arms_of_appendix_b_with_their_values():
    inc = lp.included_arms()
    assert [a.id for a in inc] == [x[0] for x in INCLUDED]
    for (aid, doi, n, prior, tst, deep, rem, basis, n3, waso, uses) in INCLUDED:
        a = lp.ARM_BY_ID[aid]
        assert (a.doi, a.n, a.prior, a.age, a.clock, a.measure, a.indep) == (doi, n, prior, "young", "aft", "psg",
                                                                            "ok"), aid
        assert r2(a.tst) == tst and r2(a.deep_pct) == deep and a.basis == basis, aid
        assert (a.rem_pct is None) == (rem is None) and (rem is None or r2(a.rem_pct) == rem), aid
        assert (a.n3min is None) == (n3 is None) and (n3 is None or r2(a.n3min) == n3), aid
        assert (a.waso_pct is None) == (waso is None) and (waso is None or r2(a.waso_pct) == waso), aid
        use = set(uses.split())
        assert ("W" in use) == (a.waso_pct is not None), aid
        assert ("P" in use) == (a.n3min is not None and a.tst >= 45), aid
        gl = lp.share_dispersion(a, "DEEP") is not None or lp.share_dispersion(a, "REM") is not None
        assert ("GL" in use) == gl, aid
        want = GL_DISP.get(aid)
        got = (None if a.n3disp is None else (a.n3disp.value, a.n3disp.kind),
               None if a.remdisp is None else (a.remdisp.value, a.remdisp.kind))
        assert got == (want if want else (None, None)), aid


def test_gl_dispersions_not_convertible_are_left_out_with_their_reason():
    notes = {a.id: a.gl_note for a in lp.included_arms() if a.gl_note}
    assert notes == {"Mednick02-S": "n not verified", "Mednick02-L": "n not verified", "Cai09": "group-wise SEMs",
                     "Mednick03-60": "dispersion labelled s.d., probably SEM",
                     "Mednick03-90": "dispersion labelled s.d., probably SEM",
                     "Nishida07": "stage minutes inconsistent with the total", "Dinges87-P6": "means only",
                     "Fogel14-Y": "no REM dispersion"}
    for aid in notes:
        a = lp.ARM_BY_ID[aid]
        assert lp.share_dispersion(a, "REM") is None
        assert (lp.share_dispersion(a, "DEEP") is not None) == (aid == "Fogel14-Y")


def test_every_excluded_arm_is_excluded_by_the_reason_listed_in_appendix_b():
    excluded = [a for a in lp.ARMS if not lp.included(a)]
    assert len(lp.ARMS) == 26 + len(excluded)
    for a in excluded:
        f = lp.failing(a)
        assert a.appendix and set(a.appendix) <= set(f), a.id
        if a.id not in FIRST_REASON_EXCEPTIONS:
            assert f[0] == a.appendix[0], a.id
    assert lp.failing(lp.ARM_BY_ID["Subram26-O"]) == ["I1", "I5"]
    for aid, (tst, deep, rem, reasons) in EXCLUDED.items():
        a = lp.ARM_BY_ID[aid]
        assert a.appendix == reasons, aid
        assert round(a.tst, tst[1]) == tst[0] and round(a.deep_pct, deep[1]) == deep[0], aid
        assert rem is None or round(a.rem_pct, rem[1]) == rem[0], aid
    evans = [lp.ARM_BY_ID[f"Evans77-{k}"] for k in ("non", "app", "rep")]
    assert (min(a.tst for a in evans), max(a.tst for a in evans)) == (33, 42)
    assert (round(min(a.deep_pct for a in evans), 1), round(max(a.deep_pct for a in evans), 1)) == (39.4, 45.2)
    assert all(a.rem_pct == 0 for a in evans)
    sargent = [lp.ARM_BY_ID["Sargent26-1h"], lp.ARM_BY_ID["Sargent26-2h"]]
    assert [round(a.waso_pct, 1) for a in sargent] == [1.9, 6.6]
    assert round(lp.ARM_BY_ID["Cellini16"].waso_pct, 1) == 12.9
    for aid in ("BrooksLack06", "Dinges87-other", "Campbell11", "Monk01", "CampbellZulley89", "Munch07-Chellappa09",
                "Mollicone07", "Lee17"):
        assert not lp.included(lp.ARM_BY_ID[aid])
    assert lp.first_reason(lp.ARM_BY_ID["Boukhris26-90"]) == "I1 athletes"
    assert lp.first_reason(lp.ARM_BY_ID["Lau11-I"]) == "I3 start time not stated"
    assert lp.first_reason(lp.ARM_BY_ID["Evans77-non"]) == "I2 prior sleep not described"
    assert lp.first_reason(lp.ARM_BY_ID["Nicolas22"]) == "I5 stimulation during the nap"
    assert lp.first_reason(lp.ARM_BY_ID["McDevitt26-REM"]) == "I5 the test set's paper"


FAIL_VALUE = {"age": "older", "prior": "R2", "clock": "morn", "measure": "wear", "indep": "dup"}
PASS_VALUE = {"age": "young", "prior": "R0", "clock": "aft", "measure": "psg", "indep": "ok"}
FIELD_OF = {"I1": "age", "I2": "prior", "I3": "clock", "I4": "measure", "I5": "indep"}


def test_changing_one_criterion_at_a_time_flips_the_inclusion():
    from dataclasses import replace
    for a in lp.included_arms():
        for fld, bad in FAIL_VALUE.items():
            assert not lp.included(replace(a, **{fld: bad})), (a.id, fld)
        assert not lp.included(replace(a, n3min=None, remmin=None, wasomin=None, reported=None))
    for a in lp.ARMS:
        f = lp.failing(a)
        if len(f) == 1 and f[0] != "I6" and all(v is not None for v in (a.age, a.prior, a.clock, a.measure, a.indep)):
            assert lp.included(replace(a, **{FIELD_OF[f[0]]: PASS_VALUE[FIELD_OF[f[0]]]})), a.id


def test_section_5_0_bin_counts():
    s = {b: lp.bin_stats(b) for b in lp.BINS}
    assert len(s[(0.0, 15.0)].arms) == 1
    assert len(s[(15.0, 45.0)].arms) == 2 and {lp.ARM_BY_ID[i].prior for i in s[(15.0, 45.0)].arms} == {"R1"}
    a = [lp.ARM_BY_ID[i] for i in s[(45.0, 80.0)].arms]
    assert len(a) == 18 and Counter_of(x.prior for x in a) == {"R0": 14, "R1": 4}
    b = s[(80.0, 110.0)]
    assert (len(b.deep), len(b.rem)) == (5, 4) and {lp.ARM_BY_ID[i].prior for i in b.arms} == {"R0"}
    assert not [x for x in lp.included_arms() if x.tst >= 110]
    assert lp.in_bin(80.0, (80.0, 110.0)) and not lp.in_bin(80.0, (45.0, 80.0))     # half-open [lo, hi)


def Counter_of(xs):
    from collections import Counter
    return dict(Counter(xs))


# ------------------------------------------------------------------------------------------ section 5.1 ----

def test_section_5_1_rows_and_plateau():
    rows = lp.ROWS
    assert [(r2(r.tst), r2(r.deep), r2(r.rem)) for r in rows] == [(11.53, 2.57, 1.50), (30.0, 25.07, 3.85),
                                                                  (60.0, 28.55, 11.62), (90.0, 23.00, 15.01)]
    s = lp.bin_stats((15.0, 45.0))
    assert sorted((i, r2(d)) for d, i in s.deep) == [("Leong23-30", 27.45), ("Mednick02-S", 22.68)]
    assert sorted((i, r2(r)) for r, i in s.rem) == [("Leong23-30", 0.83), ("Mednick02-S", 6.87)]
    assert r2(lp.plateau_deep_min()) == 20.70 and abs(lp.plateau_deep_min() - 0.2300 * 90) < 1e-9
    n3 = lp.n3_minutes_long()
    assert [r2(x) for x, _ in n3] == [11.43, 11.45, 12.55, 14.3, 14.6, 14.92, 16.5, 17.86, 18.92, 19.22, 20.2, 20.2,
                                      20.23, 21.57, 22.31, 22.71, 25.9, 27.4, 28.78, 47.2]
    import statistics
    assert r2(statistics.median(x for x, _ in n3)) == 19.71
    # "1-h vs 2-h naps 30.4 vs 36.2 min" (Sargent 2026, cited for the shape only; excluded by I1)
    assert (lp.ARM_BY_ID["Sargent26-1h"].n3min, lp.ARM_BY_ID["Sargent26-2h"].n3min) == (30.4, 36.2)


def test_waso_share_a_is_the_median_of_the_11_rule_i_arms():
    w = sorted((r2(a.waso_pct), a.id) for a in lp.included_arms() if a.waso_pct is not None)
    assert [x for x, _ in w] == [5.41, 9.48, 9.64, 11.22, 11.37, 12.32, 12.62, 13.10, 14.98, 15.05, 19.09]
    assert w[5][1] == "Leong23-30"
    assert round(lp.A_WASO, 5) == 0.12317 and lp.A_WASO == pytest.approx(4.45 / (31.68 + 4.45))
    assert (round(w[0][0], 1), round(w[-1][0], 1)) == (5.4, 19.1)


TARGETS = {30: (17.54, 9.89, 2.26, 1.7), 45: (30.69, 25.15, 4.03, 7.7), 60: (43.84, 26.67, 7.43, 11.7),
           75: (56.99, 28.20, 10.84, 16.1), 90: (70.15, 26.67, 12.76, 18.7), 105: (83.30, 24.24, 14.25, 20.2),
           120: (96.45, 21.46, 15.01, 20.7), 135: (109.60, 18.89, 15.01, 20.7), 150: (122.76, 16.86, 15.01, 20.7),
           160: (131.53, 15.74, 15.01, 20.7), 170: (140.29, 14.75, 15.01, 20.7)}


def test_section_5_1_targets_at_the_anchors_and_mid_anchors():
    for L, (tst, d, r, dmin) in TARGETS.items():
        t = lp.target(L)
        assert (r2(t.tst), r2(t.deep), r2(t.rem), round(t.deep_min, 1)) == (tst, d, r, dmin), L
    assert lp.deep_pct(5.0) == 2.57 and lp.rem_pct(5.0) == 1.50          # constant below 11.53 min
    assert lp.deep_pct(200.0) == pytest.approx(100 * 20.7 / 200) and lp.rem_pct(200.0) == lp.ROWS[-1].rem
    assert lp.deep_pct(45.0) == pytest.approx((25.065206 + 28.549514) / 2, abs=1e-5)     # linear between rows


def test_section_5_1_sensitivity_tables():
    no30 = {30: (5.79, 2.75), 60: (19.89, 8.24), 90: (26.67, 12.76), 120: (21.46, 15.01), 150: (16.86, 15.01),
            170: (14.75, 15.01)}
    nomargin = {30: (20.56, 3.38), 60: (27.69, 9.70), 90: (25.05, 13.76), 120: (19.67, 15.01), 150: (15.74, 15.01),
                170: (13.89, 15.01)}
    for L in lp.ANCHORS:
        a, b = lp.target(L, lp.ROWS_NO30), lp.target(L, margins=0.0)
        assert (r2(a.deep), r2(a.rem)) == no30[L], L
        assert (r2(b.deep), r2(b.rem)) == nomargin[L], L


def test_section_13_6_expected_levels_follow_from_the_targets():
    for tst, waso, (te, d, r) in ((49.27, 8.75, (50.9, 27.5, 9.3)), (89.91, 14.05, (91.2, 22.7, 15.0))):
        t = lp.target(tst + waso + 10)
        assert (round(t.tst, 1), round(t.deep, 1), round(t.rem, 1)) == (te, d, r)


# ------------------------------------------------------------------------------------------ section 5.2 ----

def _b(t):
    b = lp.band(t)
    return (r2(b.deep_lo), r2(b.deep_hi), r2(b.rem_lo), r2(b.rem_hi))


def test_section_5_2_band_definition_rows():
    assert _b(11.53) == _b(5.0) == (2.57, 2.57, 1.50, 1.50)
    assert _b(11.54) == _b(30.0) == (2.57, 27.45, 0.83, 6.87)
    assert _b(30.01) == _b(60.0) == (13.00, 46.43, 0.83, 18.72)
    assert _b(60.01) == _b(90.0) == (13.00, 49.01, 5.10, 26.58)
    for t in (90.01, 100.0, 150.0):
        assert _b(t) == (r2(100 * 11.43 / t), r2(100 * 47.20 / t), 5.70, 26.58)


def test_section_5_2_band_values_used():
    want = {30: (2.57, 27.45, 0.83, 6.87), 45: (13.00, 46.43, 0.83, 18.72), 60: (13.00, 46.43, 0.83, 18.72),
            75: (13.00, 46.43, 0.83, 18.72), 90: (13.00, 49.01, 5.10, 26.58), 105: (13.00, 49.01, 5.10, 26.58),
            120: (11.85, 48.94, 5.70, 26.58), 135: (10.43, 43.07, 5.70, 26.58), 150: (9.31, 38.45, 5.70, 26.58),
            160: (8.69, 35.89, 5.70, 26.58), 170: (8.15, 33.64, 5.70, 26.58)}
    for L, w in want.items():
        got = _b(lp.tst_est(L))
        if L == 135:
            # The table's 43.07 is 100 x 47.20 / 109.60 with the displayed (rounded) t; with t = TST_est(135) =
            # 109.604 the bound is 43.064 (reported as an amendment request; every other value is exact).
            assert got[:1] + got[2:] == w[:1] + w[2:] and got[1] == 43.06
            assert round(100 * 47.20 / 109.60, 2) == w[1]
        else:
            assert got == w, L
    assert _b(49.27) == (13.00, 46.43, 0.83, 18.72)                       # ds006576 NREM group, Table 1 TST
    assert _b(89.91) == (13.00, 49.01, 5.10, 26.58)                       # REM group
    assert not lp.band(43.84).lower_bounds_apply(43.84) and lp.band(56.99).lower_bounds_apply(56.99)
    assert lp.tst_est(60) < lp.LOWER_BOUND_MIN_TST <= lp.tst_est(75)      # lower bounds from L = 75 on


def test_the_target_always_lies_inside_its_own_band():
    for t in np.arange(1.0, 200.0, 0.05):
        b = lp.band(float(t))
        d, r = lp.deep_pct(float(t)), lp.rem_pct(float(t))
        assert b.deep_lo - 1e-9 <= d <= b.deep_hi + 1e-9 and b.rem_lo - 1e-9 <= r <= b.rem_hi + 1e-9, t


def test_section_5_2_zero_rem_rates_of_the_lower_bound_paragraph():
    z = lp.GENUINE["zeroRem60"]
    assert (z["k"], z["n"], round(100 * z["k"] / z["n"], 1)) == (69, 158, 43.7)
    z = lp.GENUINE["zeroRem80to105"]
    assert (z["k"], z["n"], f"{100 * z['k'] / z['n']:.1f}") == (21, 80, "26.2")


# ------------------------------------------------------------------------------------------ section 5.3 ----

def test_section_5_3_gl_bounds():
    rows = lp.GL
    assert [(r.lo, r.hi) for r in rows] == [(0.0, 15.0), (15.0, 45.0), (45.0, 75.0), (75.0, math.inf)]
    assert (r2(rows[0].deep_pct), rows[0].deep_arm, rows[0].rem_pct) == (19.03, "Leong23-10", None)
    assert (r2(rows[1].deep_pct), rows[1].deep_arm, r2(rows[1].rem_pct), rows[1].rem_arm) == (72.71, "Leong23-30", 9.59,
                                                                                              "Leong23-30")
    assert (r2(rows[2].deep_pct), rows[2].deep_arm, r2(rows[2].rem_pct), rows[2].rem_arm) == (80.62, "Chen21-Y", 57.72,
                                                                                              "Lo14")
    assert (r2(rows[3].deep_pct), rows[3].deep_arm, r2(rows[3].rem_pct), rows[3].rem_arm) == (62.95, "Whitehurst16",
                                                                                              41.24, "Whitehurst16")
    bound, arm_max, arm = lp.gl_deep_min()
    assert (bound, r2(arm_max), arm) == (54.0, 53.24, "Scullin17-Y") and lp.GL_DEEP_MIN == 54.0
    # the sources named in the table
    assert r2(2.57 + 2 * (10.8 - 2.57)) == 19.03
    assert r2(31.25 + 2 * 100 * 1.96 * math.sqrt(60) / 61.50) == 80.62
    assert r2(100 * 12.0 / 64.1 + 2 * 100 * 12.5 / 64.1) == 57.72
    assert r2(100 * 22.71 / 83.19 + 2 * 100 * 14.83 / 83.19) == 62.95


def _gl(tst, deep_pct=0.0, rem_pct=0.0, deep_min=None):
    dm = tst * deep_pct / 100 if deep_min is None else deep_min
    return lp.gl_violation(tst, dm, tst * rem_pct / 100)


def test_gl_rules_just_below_and_above_each_bound():
    e = 0.01
    assert not _gl(15.0, 19.03 - e) and _gl(15.0, 19.03 + e) and not _gl(15.0, rem_pct=100.0)   # no REM rule <= 15
    assert not _gl(15.01, 72.71 - e) and _gl(15.01, 72.71 + e)
    assert not _gl(45.0, rem_pct=9.59 - e) and _gl(45.0, rem_pct=9.59 + e)
    assert not _gl(45.01, 80.62 - e) and _gl(45.01, 80.62 + e) and not _gl(45.01, rem_pct=57.72 - e)
    assert _gl(45.01, rem_pct=57.72 + e)
    assert not _gl(75.0, 72.0) and _gl(75.01, 72.0)                    # (45, 75] vs (75, inf) at 72 % = 54 min
    assert _gl(75.01, 62.95 + e) and not _gl(75.01, 62.95 - e)
    assert not _gl(200.0, rem_pct=41.24 - e) and _gl(200.0, rem_pct=41.24 + e)       # the > 75 row is carried
    assert not _gl(120.0, deep_min=54.0) and _gl(120.0, deep_min=54.01)               # every row
    assert not lp.gl_violation(0.0, 0.0, 0.0)
    assert lp.gl_rules(20.0, 19.0, 3.0) == ["DEEP_PCT", "REM_PCT"]


def test_gl_flags_the_four_genuine_naps_of_the_cross_check_and_g_none_of_them():
    # (TST, DEEP %, REM %): two Sleep-EDF bouts and two Zenodo naps (70.7 / 69.7 % DEEP, 59.0 / 57.5 min)
    for tst, dp, rp in ((28.5, 0.0, 10.5), (22.0, 0.0, 11.4), (59.0 / 0.707, 70.7, 0.0), (57.5 / 0.697, 69.7, 0.0)):
        assert _gl(tst, dp, rp)
        assert not lp.g_violation(tst, tst * dp / 100, tst * rp / 100, tst * (100 - dp - rp) / 100)


def _g(tst, deep_pct=0.0, rem_pct=0.0, light_pct=None, deep_min=None):
    light = 100 - deep_pct - rem_pct if light_pct is None else light_pct
    dm = tst * deep_pct / 100 if deep_min is None else deep_min
    return lp.g_violation(tst, dm, tst * rem_pct / 100, tst * light / 100)


def test_g_rules_just_below_and_above_each_bound():
    e = 0.01
    assert not _g(15.0, 11 - e) and _g(15.0, 11 + e) and not _g(15.0, 0, 50.0, light_pct=0.0)
    assert not _g(60.0, 75 - e, light_pct=25.0) and _g(60.0, 75 + e, light_pct=25.0)
    assert not _g(105.0, light_pct=25.0) and _g(105.0, light_pct=25.0 - e)
    assert not _g(45.0, rem_pct=30 - e) and _g(45.0, rem_pct=30 + e) and not _g(45.01, rem_pct=40 - e)
    assert _g(45.01, rem_pct=40 + e) and not _g(105.01, rem_pct=25 - e) and _g(105.01, rem_pct=25 + e)
    assert not _g(80.0, 50.0, deep_min=60.0) and _g(80.0, 50.0, deep_min=60.01)
    assert not _g(120.0, 80.0, light_pct=25.0, deep_min=50.0)           # no DEEP share rule above 105 min


# ------------------------------------------------------------------------------------- section 5.4 ----

def wil(k, n):
    lo, hi = lp.wilson(k, n)
    return round(100 * lo, 1), round(100 * hi, 1)


def test_section_5_4_and_2_3_wilson_intervals():
    assert wil(4, 58) == (2.7, 16.4) and round(100 * 4 / 58, 1) == 6.9
    assert wil(0, 93) == (0.0, 4.0) and wil(0, 70) == (0.0, 5.2)
    assert wil(4, 93) == (1.7, 10.5) and wil(2, 70) == (0.8, 9.8)
    assert round(100 * 4 / 93, 1) == 4.3 and round(100 * 2 / 70, 1) == 2.9
    g = lp.GENUINE
    assert round(100 * g["zeroDeep55Chen"]["k"] / g["zeroDeep55Chen"]["n"], 1) == 11.7
    assert round(100 * g["zeroDeep55Zenodo"]["k"] / g["zeroDeep55Zenodo"]["n"], 1) == 17.4
    assert g["n3Latency"] == {"minMin": 8.5, "medianMin": 21.5, "n": 35, "source": "open hypnograms"}
    assert (g["remLatency"]["minMin"], g["remLatency"]["medianMin"], g["remLatency"]["n"]) == (43.5, 59.0, 18)
    assert g["guardFalseFlags"]["GL"] == {"all": (4, 93), "withoutZenodo": (2, 70)}


def test_rule_iii_b_bound_is_the_rem_ceiling_of_short_rule_i_naps():
    v, aid = lp.rem_ceiling_short_naps()
    assert (r2(v), aid, round(v, 1)) == (6.87, "Mednick02-S", 6.9)
    assert sorted(r2(a.rem_pct) for a in lp.included_arms() if a.tst < 45) == [0.83, 1.50, 6.87]


# ------------------------------------------------------------------------------------- section 5.5 ----

def _sessions(n_subj=12, per=4, seed=0):
    rng = np.random.default_rng(seed)
    subjects, counts = [], []
    for s in range(n_subj):
        for _ in range(per):
            sleep = rng.integers(60, 120)
            deep = rng.integers(0, int(0.6 * sleep))
            rem = rng.integers(0, int(0.4 * (sleep - deep)) + 1)
            counts.append([rng.integers(0, 20), sleep - deep - rem, deep, rem])
            subjects.append(f"s{s}")
    return [f"id{i}" for i in range(len(subjects))], subjects, np.array(counts, float)


def test_base_weights_give_every_subject_equal_mass():
    q = lp.base_weights(["a", "a", "b", "c", "c", "c"])
    assert q.tolist() == pytest.approx([1 / 6, 1 / 6, 1 / 3, 1 / 9, 1 / 9, 1 / 9]) and q.sum() == pytest.approx(1)


def test_tilting_reaches_the_target_exactly_with_kl_minimal_weights():
    ids, subj, c = _sessions()
    lw = lp.lit_weights(ids, subj, c, 60)
    t = lp.target(60)
    pooled = lw.w @ c
    assert lw.feasible and lw.lam == 0.0 and lw.info["converged"] and lw.info["maxViolation"] < 1e-9
    assert pooled[2] / pooled[1:].sum() == pytest.approx(t.deep / 100, abs=1e-8)
    assert pooled[3] / pooled[1:].sum() == pytest.approx(t.rem / 100, abs=1e-8)
    assert lw.w.sum() == pytest.approx(1.0) and lw.ess >= 10
    # exponential family: log(w / q) is affine in g (KL-minimal weights)
    q = lp.base_weights(subj)
    sleep = c[:, 1:].sum(axis=1)
    g = np.stack([c[:, 2] - t.deep / 100 * sleep, c[:, 3] - t.rem / 100 * sleep], axis=1)
    X = np.column_stack([g, np.ones(len(q))])
    resid = np.log(lw.w / q) - X @ np.linalg.lstsq(X, np.log(lw.w / q), rcond=None)[0]
    assert np.abs(resid).max() < 1e-8


def test_newton_polish_runs_until_the_relative_violation_is_below_the_tolerance():
    ids, subj, c = _sessions(seed=3)
    q = lp.base_weights(subj)
    sleep = c[:, 1:].sum(axis=1)
    g = np.stack([c[:, 2] - 0.25 * sleep, c[:, 3] - 0.12 * sleep], axis=1)
    w, info = lp.tilt(q, g, tol=1e-14)
    assert info["converged"] and info["maxViolation"] < 1e-14
    assert info["newtonSteps"] >= 1 or info["bfgsViolation"] < 1e-14
    assert info["newtonSteps"] <= lp.TILT_NEWTON_MAX
    w0, info0 = lp.tilt(q, g, newton_max=0, tol=0.0)
    assert info0["newtonSteps"] == 0 and not info0["converged"]
    assert lp.relative_violation(w, g) <= lp.relative_violation(w0, g) + 1e-15


def test_feasibility_rule_moves_the_target_towards_the_base_composition():
    ids, subj, c = _sessions(seed=1)
    sleep = c[:, 1:].sum(axis=1)
    deep_max = float((c[:, 2] / sleep).max())
    lw = lp.lit_weights(ids, subj, c, 60, lit_target=(min(0.99, deep_max + 0.2), 0.05))   # outside the hull
    assert lw.feasible and lw.lam > 0 and lw.lam in lp.FEASIBILITY_LAMBDAS and lw.ess >= 10
    assert [a["lambda"] for a in lw.info["attempts"]][0] == 0.0
    assert not lw.info["attempts"][0]["converged"] or lw.info["attempts"][0]["ess"] < 10
    tq = lw.base_target
    want = ((1 - lw.lam) * lw.lit_target[0] + lw.lam * tq[0], (1 - lw.lam) * lw.lit_target[1] + lw.lam * tq[1])
    assert lw.target == pytest.approx(want)
    pooled = lw.w @ c
    assert pooled[2] / pooled[1:].sum() == pytest.approx(want[0], abs=1e-8)


def test_feasibility_rule_with_too_few_sessions_keeps_the_base_weights_flagged():
    ids, subj, c = _sessions(n_subj=3, per=2, seed=2)         # 6 sessions: no weights with an ESS of 10
    lw = lp.lit_weights(ids, subj, c, 90)
    assert not lw.feasible and lw.lam == 1.0 and lw.w == pytest.approx(lp.base_weights(subj))
    assert len(lw.info["attempts"]) == 1 + len(lp.FEASIBILITY_LAMBDAS)


def test_lit_weights_refuse_inconsistent_input():
    with pytest.raises(ValueError):
        lp.lit_weights(["a"], ["s"], np.zeros((2, 4)), 60)
    with pytest.raises(ValueError):
        lp.lit_weights([], [], np.zeros((0, 4)), 60)


def test_summary_tables_are_json_ready():
    import json
    s = lp.summary_tables()
    assert len(s["includedArms"]) == 26 and s["excludedArms"]["Cohen10"] == "I3 evening"
    json.dumps(s, default=float)
