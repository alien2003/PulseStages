"""Literature prior of the pre-registered realistic-nap experiment (``docs/prereg/prereg-naps-realistic.md`` section 5
and Appendix B): the single source of every published number the experiment uses.

- :data:`ARMS` is the arm table of Appendix B (included and excluded arms, values as group means; DEEP and REM in % of
  TST either reported by the authors, basis ``R``, or the ratio of group means, basis ``D``), classed for the inclusion
  rule I of section 5.0 (:func:`criteria`, :func:`included`). Every arm-based quantity below is recomputed from it.
- Targets (section 5.1): the median DEEP and REM share of the rule-I arms per TST bin (:data:`BINS`, half-open
  ``[lo, hi)``), placed at the nominal TST of the row (30 / 60 / 90 min; the single arm of ``[0, 15)`` at its own TST),
  linear in TST up to 90 min, constant below the first row, and above 90 min the N3-minute plateau (DEEP minutes held at
  their 90-min value, REM held). Session length -> TST: :func:`tst_est` with the median WASO share :func:`a_waso`.
- Tier-1 band (section 5.2, :func:`band`): the range of the arm means the target at t is computed from.
- Tier-2 guards (section 5.3): GL (:func:`gl_violation`, literature only, the guard of every decision) and G
  (:func:`g_violation`, reported only).
- Genuine-nap reference rates (section 5.4, :data:`GENUINE`) and :func:`wilson` intervals.
- The literature reweighting of section 5.5: exponential tilting (:func:`tilt`, BFGS on the dual then Newton steps)
  with the feasibility rule (:func:`lit_weights`).

Nothing here reads data or a model output; ``tests/test_litprior.py`` recomputes every number of sections 5.1-5.3 and
Appendix B from this table.
"""

from __future__ import annotations

import math
import statistics
from dataclasses import dataclass, field
from typing import Iterable, Sequence

import numpy as np
from scipy.optimize import minimize

# ------------------------------------------------------------------------------------------------ arm table ----

AGE_PASS = "young"                     # I1: healthy adults of about 18-35 years
PRIOR_PASS = ("R0", "R1")              # I2: no restriction (R0) or habitual short / late sleepers (R1)
CLOCK_PASS = "aft"                     # I3: the nap starts in the afternoon (11:00-17:00), stated in the source
MEASURE_PASS = "psg"                   # I4: PSG scored by human scorers
INDEP_PASS = "ok"                      # I5: no stimulation, no probable duplicate, not the test set's paper
CRITERIA: tuple[str, ...] = ("I1", "I2", "I3", "I4", "I5", "I6")
REASON_TEXT = {
    "I1": {"older": "older adults", "adol": "adolescents", "athl": "athletes"},
    "I2": {"R2": "experimental restriction", "R3": "total sleep deprivation", "R?": "prior sleep not described"},
    "I3": {"unk": "start time not stated", "morn": "morning", "eve": "evening", "free": "no fixed clock",
           "any": "every circadian phase"},
    "I4": {"wear": "wearable or automatic staging"},
    "I5": {"dup": "probable duplicate sample", "stim": "stimulation during the nap", "test": "the test set's paper"},
    "I6": {None: "no value of any quantity"},
}
DISP_KINDS = ("SD%", "SEM%", "SD", "SEM")   # SD / SEM of the share (% of TST) or of the minutes (SEM x sqrt(n))


@dataclass(frozen=True)
class Disp:
    """A published dispersion: ``kind`` SD% / SEM% (of the share, % of TST) or SD / SEM (of the minutes)."""
    value: float
    kind: str


@dataclass(frozen=True)
class Arm:
    """One published nap arm (group means). Classification fields are ``None`` where the notes do not class the arm
    (only arms excluded by another criterion; :func:`criteria` leaves them unassessed). ``reported`` = (DEEP, REM) %
    of TST as reported (basis R), else the shares are the ratio of group means (basis D). ``appendix`` = the
    criterion codes Appendix B lists for an excluded arm."""
    id: str
    citation: str
    doi: str | None
    n: int | None
    age: str | None
    prior: str | None
    clock: str | None
    measure: str | None
    indep: str | None
    tst: float | None = None
    n3min: float | None = None
    remmin: float | None = None
    wasomin: float | None = None
    reported: tuple[float | None, float | None] | None = None
    n3disp: Disp | None = None
    remdisp: Disp | None = None
    gl_note: str | None = None
    appendix: tuple[str, ...] = ()
    note: str = ""

    @property
    def deep_pct(self) -> float | None:
        """DEEP (N3) % of TST: reported, else 100 * N3 minutes / TST."""
        if self.reported is not None and self.reported[0] is not None:
            return float(self.reported[0])
        return None if self.n3min is None or not self.tst else 100.0 * self.n3min / self.tst

    @property
    def rem_pct(self) -> float | None:
        if self.reported is not None and self.reported[1] is not None:
            return float(self.reported[1])
        return None if self.remmin is None or not self.tst else 100.0 * self.remmin / self.tst

    @property
    def basis(self) -> str:
        return "R" if self.reported is not None else "D"

    @property
    def waso_pct(self) -> float | None:
        """WASO % of the sleep period, 100 * WASO / (TST + WASO)."""
        return None if self.wasomin is None or self.tst is None else 100.0 * self.wasomin / (self.tst + self.wasomin)


def _pool_by_n(groups: Sequence[tuple[int, float, float, float]]) -> tuple[float, float, float]:
    """(TST, N3 min, REM min) of groups (n, TST, N3, REM) pooled by n (Cai et al. 2009)."""
    n = sum(g[0] for g in groups)
    return tuple(sum(g[0] * g[i] for g in groups) / n for i in (1, 2, 3))  # type: ignore[return-value]


# Cai et al. 2009, NREM group (n 12: TST 50.2 = S1 5.4 + S2 24.6 + SWS 20.0 + REM 0.2) and REM group (n 28: 72.9 =
# 3.9 + 31.4 + 23.3 + 14.3), pooled by n.
_CAI = _pool_by_n(((12, 50.2, 20.0, 0.2), (28, 72.9, 23.3, 14.3)))
# Cohen et al. 2010 (evening): stage shares 5.4 / 36.8 / 34.1 / 7.0 % summing to 83.3 % (of the recording).
_COHEN_SUM = 5.4 + 36.8 + 34.1 + 7.0
_L23 = "10.1093/sleep/zsad025"
_M02 = "10.1038/nn864"
_M03 = "10.1038/nn1078"
_SYN1A = None                           # older-adult arms listed in synthesis table 1a (no DOI in Appendix B)
_MORNING = ("10.1186/1471-2202-7-2", "10.3390/brainsci6010001", "10.5665/sleep.3220", "10.1093/sleep/19.7.570")


def _arm(id_, citation, doi, n, age, prior, clock, measure, indep, **kw) -> Arm:
    return Arm(id=id_, citation=citation, doi=doi, n=n, age=age, prior=prior, clock=clock, measure=measure,
               indep=indep, **kw)


_Y, _A, _P, _OK = "young", "aft", "psg", "ok"

ARMS: tuple[Arm, ...] = (
    # ------------------------------------------------------------------ included by rule I (26 arms)
    _arm("Leong23-10", "Leong et al. 2023, 10-min", _L23, 32, _Y, "R1", _A, _P, _OK, tst=11.53, n3min=0.31,
         remmin=0.19, wasomin=2.72, reported=(2.57, 1.50), n3disp=Disp(round(10.8 - 2.57, 2), "SD%"),
         note="DEEP SD 8.23 % from the reported mean + 1 SD 10.8 %; no REM dispersion"),
    _arm("Leong23-30", "Leong 2023, 30-min", _L23, 32, _Y, "R1", _A, _P, _OK, tst=31.68, n3min=8.52, remmin=0.29,
         wasomin=4.45, reported=(27.45, 0.83), n3disp=Disp(22.63, "SD%"), remdisp=Disp(4.38, "SD%")),
    _arm("Mednick02-S", "Mednick et al. 2002, short", _M02, None, _Y, "R1", _A, _P, _OK, tst=29.1, n3min=6.6,
         remmin=2.0, gl_note="n not verified", note="Table 1; the text gives 31.6 / 2.7"),
    _arm("Leong23-60", "Leong 2023, 60-min", _L23, 32, _Y, "R1", _A, _P, _OK, tst=61.88, n3min=21.57, remmin=7.31,
         wasomin=6.48, reported=(35.12, 11.68), n3disp=Disp(21.16, "SD%"), remdisp=Disp(14.26, "SD%")),
    _arm("Mednick02-L", "Mednick 2002, long", _M02, None, _Y, "R1", _A, _P, _OK, tst=59.6, n3min=27.4, remmin=8.9,
         gl_note="n not verified"),
    _arm("McDevitt12", "McDevitt, Alaynick & Mednick 2012", "10.1016/j.physbeh.2012.05.021", 27, _Y, "R1", _A, _P,
         _OK, tst=74.2, n3min=25.9, remmin=12.0, reported=(34.9, 15.8), n3disp=Disp(16.3, "SD%"),
         remdisp=Disp(13.8, "SD%")),
    _arm("Cai09", "Cai et al. 2009, NREM + REM groups pooled by n", "10.1073/pnas.0900271106", 40, _Y, "R1", _A, _P,
         _OK, tst=_CAI[0], n3min=_CAI[1], remmin=_CAI[2], gl_note="group-wise SEMs"),
    _arm("Mednick03-60", "Mednick, Nakayama & Stickgold 2003, 60-min", _M03, 26, _Y, "R0", _A, _P, _OK, tst=59.3,
         n3min=20.2, remmin=4.2, gl_note="dispersion labelled s.d., probably SEM"),
    _arm("Nishida07", "Nishida & Walker 2007 (stage-sum basis)", "10.1371/journal.pone.0000341", 14, _Y, "R0", _A,
         _P, _OK, tst=61.98, n3min=28.78, remmin=10.50, gl_note="stage minutes inconsistent with the total"),
    _arm("Cousins19", "Cousins et al. 2019", "10.1093/sleep/zsy207", 27, _Y, "R0", _A, _P, _OK, tst=60.35,
         n3min=20.23, remmin=5.21, n3disp=Disp(13.15, "SD"), remdisp=Disp(6.13, "SD")),
    _arm("Bergamo26", "Bergamo et al. 2026 (published paper only)", "10.1111/jsr.70253", 19, _Y, "R0", _A, _P, _OK,
         tst=62.06, n3min=14.92, remmin=4.10, n3disp=Disp(11.03, "SD"), remdisp=Disp(5.60, "SD")),
    _arm("Ong20", "Ong et al. 2020", "10.1093/sleep/zsaa058", 20, _Y, "R0", _A, _P, _OK, tst=63.2, n3min=14.3,
         remmin=7.3, wasomin=11.2, n3disp=Disp(2.5, "SEM"), remdisp=Disp(2.1, "SEM")),
    _arm("Chen21-Y", "Chen et al. 2021, young", "10.1111/psyp.13701", 60, _Y, "R0", _A, _P, _OK, tst=61.50,
         n3min=19.22, remmin=6.24, wasomin=6.56, n3disp=Disp(1.96, "SEM"), remdisp=Disp(1.09, "SEM")),
    _arm("Scullin17-Y", "Scullin et al. 2017, young", "10.1093/sleep/zsx035", 30, _Y, "R0", _A, _P, _OK, tst=73.20,
         n3min=18.92, remmin=10.12, wasomin=12.90, n3disp=Disp(17.16, "SD"), remdisp=Disp(6.95, "SD")),
    _arm("Mednick08", "Mednick et al. 2008", "10.1016/j.bbr.2008.04.028", 19, _Y, "R0", _A, _P, _OK, tst=69.38,
         n3min=12.55, remmin=8.88, n3disp=Disp(13.0, "SD"), remdisp=Disp(12.0, "SD")),
    _arm("Ujma15", "Ujma et al. 2015", "10.1038/srep17159", 79, _Y, "R0", _A, _P, _OK, tst=70.39, n3min=11.43,
         remmin=5.73, n3disp=Disp(12.07, "SD"), remdisp=Disp(6.60, "SD")),
    _arm("Fogel14-Y", "Fogel et al. 2014, young", "10.1002/hbm.22426", 13, _Y, "R0", _A, _P, _OK, tst=72.08,
         n3min=11.45, remmin=9.44, wasomin=10.87, reported=(15.41, 12.10), n3disp=Disp(3.10, "SEM%"),
         gl_note="no REM dispersion"),
    _arm("Lau15", "Lau et al. 2015", "10.1371/journal.pone.0125752", 40, _Y, "R0", _A, _P, _OK, tst=77.56,
         n3min=16.50, remmin=8.34, wasomin=9.80, reported=(19.63, 9.34), n3disp=Disp(15.79, "SD%"),
         remdisp=Disp(9.62, "SD%")),
    _arm("Lo14", "Lo, Dijk & Groeger 2014", "10.1371/journal.pone.0108100", 34, _Y, "R0", _A, _P, _OK, tst=64.1,
         n3min=20.2, remmin=12.0, n3disp=Disp(14.0, "SD"), remdisp=Disp(12.5, "SD")),
    _arm("Fudge24-1h", "Fudge et al. 2024, 1-h lunch", "10.3390/clockssleep6030027", 40, _Y, "R0", _A, _P, _OK,
         tst=53.5, reported=(13.0, 5.1), n3disp=Disp(19.5, "SD%"), remdisp=Disp(8.3, "SD%")),
    _arm("Fudge24-2h", "Fudge 2024, 2-h lunch", "10.3390/clockssleep6030027", 40, _Y, "R0", _A, _P, _OK, tst=58.9,
         reported=(17.3, 5.2), n3disp=Disp(20.4, "SD%"), remdisp=Disp(10.2, "SD%")),
    _arm("Mednick03-90", "Mednick 2003, 90-min", _M03, 19, _Y, "R0", _A, _P, _OK, tst=96.3, n3min=47.2, remmin=25.6,
         gl_note="dispersion labelled s.d., probably SEM"),
    _arm("Whitehurst16", "Whitehurst et al. 2016", "10.1073/pnas.1518202113", 60, _Y, "R0", _A, _P, _OK, tst=83.19,
         n3min=22.71, remmin=13.15, wasomin=12.01, n3disp=Disp(14.83, "SD"), remdisp=Disp(10.58, "SD")),
    _arm("Whitehurst18", "Whitehurst, Naji & Mednick 2018", "10.1016/j.nbscr.2018.03.001", 32, _Y, "R0", _A, _P, _OK,
         tst=96.36, n3min=17.86, remmin=13.70, wasomin=12.36, n3disp=Disp(14.61, "SD"), remdisp=Disp(12.85, "SD"),
         note="largest individual N3 54 min"),
    _arm("Baran16-Y", "Baran, Mantua & Spencer 2016, young (home PSG)", "10.1162/jocn_a_00938", 13, _Y, "R0", _A, _P,
         _OK, tst=87.4, wasomin=5.0, reported=(23.0, 5.7), n3disp=Disp(12.3, "SD%"), remdisp=Disp(7.6, "SD%")),
    _arm("Dinges87-P6", "Dinges et al. 1987, group P6", "10.1093/sleep/10.4.313", 8, _Y, "R0", _A, _P, _OK, tst=80.0,
         n3min=14.6, gl_note="means only"),
    # ------------------------------------------------------------------ excluded (Appendix B, first reason first)
    _arm("Sargent26-1h", "Sargent et al. 2026, 1 h", "10.3390/clockssleep8030044", 12, "athl", "R0", _A, _P, _OK,
         tst=52.4, n3min=30.4, remmin=2.1, wasomin=1.0, reported=(56.7, 3.6), appendix=("I1",)),
    _arm("Sargent26-2h", "Sargent et al. 2026, 2 h", "10.3390/clockssleep8030044", 12, "athl", "R0", _A, _P, _OK,
         tst=105.0, n3min=36.2, remmin=13.4, wasomin=7.4, reported=(35.3, 12.0), appendix=("I1",)),
    _arm("Boukhris26-90", "Boukhris, Suppiah & Driller 2026, 90-min", "10.1111/jsr.70120", 14, "athl", "R0", _A,
         "wear", _OK, tst=56.0, n3min=9.0, remmin=7.0, appendix=("I1", "I4")),
    _arm("Lau11-I", "Lau, Alger & Fishbein 2011, immediate", "10.1371/journal.pone.0027139", 15, _Y, "R0", "unk", _P,
         _OK, tst=63.17, n3min=19.77, remmin=5.67, appendix=("I3",)),
    _arm("Lau11-D", "Lau, Alger & Fishbein 2011, delayed", "10.1371/journal.pone.0027139", 11, _Y, "R0", "unk", _P,
         _OK, tst=77.45, n3min=22.18, remmin=12.86, appendix=("I3",)),
    _arm("Evans77-non", "Evans et al. 1977, non-appetitive group", "10.1126/science.17922", 9, _Y, "R?", _A, _P, _OK,
         tst=33.0, n3min=13.0, remmin=0.0, appendix=("I2",)),
    _arm("Evans77-app", "Evans et al. 1977, appetitive group", "10.1126/science.17922", 11, _Y, "R?", _A, _P, _OK,
         tst=40.0, n3min=16.0, remmin=0.0, appendix=("I2",)),
    _arm("Evans77-rep", "Evans et al. 1977, replacement group", "10.1126/science.17922", 10, _Y, "R?", _A, _P, _OK,
         tst=42.0, n3min=19.0, remmin=0.0, appendix=("I2",)),
    _arm("Saletin17", "Saletin et al. 2017", "10.1093/sleep/zsx118", 9, _Y, "R2", _A, _P, _OK, tst=33.5,
         n3min=51 / 6, remmin=4 / 6, appendix=("I2",), note="5-h nights"),
    _arm("BrooksLack06", "Brooks & Lack 2006 (via Hilditch 2017)", "10.1093/sleep/29.6.831", None, None, "R2", None,
         None, None, appendix=("I2",), note="5-30 min naps (exact), DEEP 0-38 %; 5-h night; secondary source"),
    _arm("Takahashi00", "Takahashi & Arito 2000", "10.1093/sleep/23.6.1h", None, None, "R2", None, None, None,
         tst=10.2, n3min=0.0, appendix=("I2",), note="4-h night"),
    _arm("Cousins21-5h", "Cousins et al. 2021, 5-h nights", "10.1038/s41598-021-84625-8", 29, "adol", "R2", _A, _P,
         _OK, tst=77.41, n3min=30.55, appendix=("I1", "I2")),
    _arm("Cousins21-6.5h", "Cousins et al. 2021, 6.5-h nights", "10.1038/s41598-021-84625-8", 24, "adol", "R2", _A,
         _P, _OK, tst=75.811, n3min=30.39, appendix=("I1", "I2")),
    _arm("Dinges87-P30", "Dinges 1987, P30", "10.1093/sleep/10.4.313", 8, _Y, "R3", _A, _P, _OK, tst=117.0,
         n3min=63.3, appendix=("I2",)),
    _arm("Dinges87-other", "Dinges 1987, T18 / T42 / P54", "10.1093/sleep/10.4.313", None, None, "R3", None, None,
         None, appendix=("I2",)),
    _arm("Li25-TSD", "Li et al. 2025", "10.2147/NSS.S499702", 45, _Y, "R3", "morn", _P, _OK, tst=28.48, n3min=18.43,
         remmin=1.40, appendix=("I2", "I3")),
    _arm("Chen21-O", "Chen et al. 2021, older", "10.1111/psyp.13701", 46, "older", "R0", _A, _P, _OK, tst=46.99,
         n3min=7.47, remmin=2.72, wasomin=19.01, appendix=("I1",)),
    _arm("Scullin17-O", "Scullin et al. 2017, older", "10.1093/sleep/zsx035", 29, "older", "R0", _A, _P, _OK,
         tst=54.12, n3min=5.23, remmin=1.47, wasomin=27.04, appendix=("I1",)),
    _arm("Fogel14-O", "Fogel et al. 2014, older", "10.1002/hbm.22426", 15, "older", "R0", _A, _P, _OK, tst=46.00,
         n3min=4.60, remmin=4.20, wasomin=31.13, reported=(7.01, 5.45), appendix=("I1",)),
    _arm("Baran16-O", "Baran 2016, older", "10.1162/jocn_a_00938", 13, "older", "R0", _A, _P, _OK, tst=71.8,
         wasomin=4.4, reported=(12.6, 0.5), appendix=("I1",)),
    _arm("Backhaus16-SE", "Backhaus et al. 2016, group 1", "10.3389/fnagi.2016.00125", 33, "older", "R0", _A, _P, _OK,
         tst=59.7, n3min=15.9, remmin=8.4, appendix=("I1",)),
    _arm("Backhaus16-MA", "Backhaus et al. 2016, group 2", "10.3389/fnagi.2016.00125", 30, "older", "R0", _A, _P,
         _OK, tst=60.4, n3min=7.8, remmin=7.0, appendix=("I1",)),
    _arm("Campbell11", "Campbell et al. 2011", _SYN1A, None, "older", None, None, None, None, appendix=("I1",)),
    _arm("Monk01", "Monk et al. 2001", _SYN1A, None, "older", None, None, None, None, appendix=("I1",)),
    _arm("Tinguely06-M", "Tinguely et al. 2006, morning", _MORNING[0], 16, _Y, "R2", "morn", _P, _OK, tst=165.76,
         n3min=20.06, remmin=47.21, reported=(12.16, 28.36), appendix=("I2", "I3")),
    _arm("Gilson16-Ls", "Gilson et al. 2016, arm Ls", _MORNING[1], 12, _Y, "R2", "morn", _P, _OK, tst=89.21,
         n3min=11.58, remmin=17.67, appendix=("I2", "I3")),
    _arm("Gilson16-Ln", "Gilson et al. 2016, arm Ln", _MORNING[1], 12, _Y, "R2", "morn", _P, _OK, tst=91.08,
         n3min=6.21, remmin=21.33, appendix=("I2", "I3")),
    _arm("Gilson16-Ss", "Gilson et al. 2016, arm Ss", _MORNING[1], 12, _Y, "R2", "morn", _P, _OK, tst=46.08,
         n3min=7.92, remmin=3.42, appendix=("I2", "I3")),
    _arm("Gilson16-Sn", "Gilson et al. 2016, arm Sn", _MORNING[1], 12, _Y, "R2", "morn", _P, _OK, tst=44.71,
         n3min=1.66, remmin=0.87, appendix=("I2", "I3")),
    _arm("Deliens13", "Deliens et al. 2013", _MORNING[2], 25, _Y, "R2", "morn", _P, _OK, tst=32.5,
         n3min=13 * 6.6 / 25, remmin=10 * 5.7 / 25, appendix=("I2", "I3")),
    _arm("Gillberg96", "Gillberg et al. 1996", _MORNING[3], 8, _Y, "R2", "morn", _P, _OK, tst=19.8, n3min=3.7,
         appendix=("I2", "I3")),
    _arm("Cohen10", "Cohen et al. 2010", "10.5664/jcsm.27990", 10, _Y, "R0", "eve", _P, _OK, tst=100.3,
         n3min=34.1 / _COHEN_SUM * 100.3, remmin=7.0 / _COHEN_SUM * 100.3, appendix=("I3",)),
    _arm("CampbellZulley89", "Campbell & Zulley 1989 (spontaneous naps, disentrained)",
         "10.1111/j.1469-8986.1989.tb00714.x", None, None, None, "free", None, None, appendix=("I3",),
         note="26 naps of 30-225 min, starts 06:33-18:48; cited only as context"),
    _arm("Munch07-Chellappa09", "Münch et al. 2007 / Chellappa et al. 2009 (naps across 40 h)",
         "10.1093/sleep/30.6.781; 10.1093/sleep/32.9.1201", None, None, None, "any", None, None, appendix=("I3",)),
    _arm("Mollicone07", "Mollicone et al. 2007", "10.1016/j.actaastro.2006.09.022", None, None, "R2", None, None,
         None, appendix=("I2", "I6"), note="no nap-only stage values"),
    _arm("Lee17", "Lee et al. 2017", "10.1371/journal.pone.0185616", None, None, None, None, None, None, tst=59.5,
         appendix=("I6",), note="only a bound on SWS + REM (<= 18.3 %)"),
    _arm("Subram26-Y", "Subramoniam et al. 2026, young", "10.1093/sleepadvances/zpag083", 59, _Y, "R0", _A, _P, "dup",
         tst=61.02, n3min=19.11, remmin=6.35, wasomin=6.51, appendix=("I5",), note="probable duplicate of Chen 2021"),
    # Appendix B lists the young / older pair under the pair's shared reason I5; the older arm also fails I1 first.
    _arm("Subram26-O", "Subramoniam et al. 2026, older", "10.1093/sleepadvances/zpag083", 52, "older", "R0", _A, _P,
         "dup", tst=45.29, n3min=6.80, remmin=2.58, wasomin=18.46, appendix=("I5",),
         note="probable duplicate of Chen 2021"),
    _arm("Cellini16", "Cellini et al. 2016", "10.1111/psyp.12595", 66, _Y, "R0", _A, _P, "dup", tst=80.05,
         n3min=21.82, remmin=12.18, wasomin=11.86, appendix=("I5",),
         note="probable duplicate of Whitehurst 2016 / 2018"),
    _arm("Nicolas22", "Nicolas et al. 2022 (the Zenodo cohort)", "10.7554/eLife.73930", 24, _Y, "R0", _A, _P, "stim",
         tst=79.1, n3min=17.5, remmin=9.6, appendix=("I5",), note="auditory cueing during NREM2-3"),
    _arm("McDevitt26-NREM", "McDevitt et al. 2026, NREM group", "10.1162/jocn.a.82", 22, _Y, "R0", _A, _P, "test",
         tst=49.27, n3min=20.18, remmin=0.11, wasomin=8.75, reported=(39.92, 0.25), appendix=("I5",),
         note="the test set's paper (weak labels only)"),
    _arm("McDevitt26-REM", "McDevitt et al. 2026, REM group", "10.1162/jocn.a.82", 22, _Y, "R0", _A, _P, "test",
         tst=89.91, n3min=21.95, remmin=16.70, wasomin=14.05, reported=(24.90, 17.90), appendix=("I5",),
         note="the test set's paper (weak labels only)"),
)
ARM_BY_ID: dict[str, Arm] = {a.id: a for a in ARMS}
NOT_USED: tuple[dict, ...] = (
    {"source": "Bergamo 2026 OSF deposit (individual naps)", "url": "osf.io/z5r2j", "reason": "no licence; not used"},
)


def _has_value(arm: Arm) -> bool:
    return any(x is not None for x in (arm.deep_pct, arm.rem_pct, arm.n3min, arm.waso_pct))


def criteria(arm: Arm) -> dict[str, bool | None]:
    """Rule I per criterion: True (holds), False (excludes) or None (not classed in the notes: unassessed)."""
    def test(value, ok):
        return None if value is None else ok(value)
    return {
        "I1": test(arm.age, lambda v: v == AGE_PASS),
        "I2": test(arm.prior, lambda v: v in PRIOR_PASS),
        "I3": test(arm.clock, lambda v: v == CLOCK_PASS),
        "I4": test(arm.measure, lambda v: v == MEASURE_PASS),
        "I5": test(arm.indep, lambda v: v == INDEP_PASS),
        "I6": _has_value(arm),
    }


def failing(arm: Arm) -> list[str]:
    """The criteria that exclude ``arm``, in the order I1 ... I6."""
    return [c for c, ok in criteria(arm).items() if ok is False]


def included(arm: Arm) -> bool:
    """Rule I: every criterion holds (an unassessed criterion never includes an arm)."""
    return all(ok is True for ok in criteria(arm).values())


def first_reason(arm: Arm) -> str | None:
    """The first excluding criterion with its text, e.g. ``"I1 athletes"``; None for an included arm."""
    f = failing(arm)
    if not f:
        return None
    c = f[0]
    key = {"I1": arm.age, "I2": arm.prior, "I3": arm.clock, "I4": arm.measure, "I5": arm.indep, "I6": None}[c]
    return f"{c} {REASON_TEXT[c].get(key, key)}"


def included_arms(arms: Iterable[Arm] = ARMS) -> list[Arm]:
    return [a for a in arms if included(a)]


# ------------------------------------------------------------------------------------- targets (section 5.1) ----

BINS: tuple[tuple[float, float], ...] = ((0.0, 15.0), (15.0, 45.0), (45.0, 80.0), (80.0, 110.0))
NOMINAL_ROW_TST: tuple[float | None, ...] = (None, 30.0, 60.0, 90.0)  # None: the single arm's own TST
PLATEAU_MIN_TST = 45.0                  # N3-minute range of the rule-I arms with TST >= 45 min
LOWER_BOUND_MIN_TST = 45.0              # lower band bounds apply at t >= 45 min of sleep (section 5.2)
TRAINING_MARGINS_MIN = 10.0             # the two 5-min OHealth-like margins of the training windows
ANCHORS: tuple[int, ...] = (30, 60, 90, 120, 150, 170)
MID_ANCHORS: tuple[int, ...] = (45, 75, 105, 135, 160)


def in_bin(tst: float | None, b: tuple[float, float]) -> bool:
    """Half-open TST bin [lo, hi) (an arm with TST exactly 80 belongs to [80, 110))."""
    return tst is not None and b[0] <= tst < b[1]


@dataclass(frozen=True)
class BinStats:
    bin: tuple[float, float]
    deep: tuple[tuple[float, str], ...]           # (DEEP % of TST, arm id) of the bin's rule-I arms with the value
    rem: tuple[tuple[float, str], ...]

    @property
    def deep_values(self) -> list[float]:
        return [x for x, _ in self.deep]

    @property
    def rem_values(self) -> list[float]:
        return [x for x, _ in self.rem]

    @property
    def arms(self) -> list[str]:
        return sorted({i for _, i in self.deep} | {i for _, i in self.rem})


def bin_stats(b: tuple[float, float], arms: Iterable[Arm] = ARMS) -> BinStats:
    """The rule-I arms of TST bin ``b`` with their DEEP and REM shares (an arm enters a quantity only if it has it)."""
    sel = [a for a in included_arms(arms) if in_bin(a.tst, b)]
    return BinStats(b, tuple((a.deep_pct, a.id) for a in sel if a.deep_pct is not None),
                    tuple((a.rem_pct, a.id) for a in sel if a.rem_pct is not None))


@dataclass(frozen=True)
class Row:
    """A target row: TST (min of sleep), median DEEP and REM % of TST, the bin it comes from."""
    tst: float
    deep: float
    rem: float
    bin: tuple[float, float]


def target_rows(arms: Iterable[Arm] = ARMS) -> tuple[Row, ...]:
    """The rows of section 5.1: the median of each bin's rule-I arms at the row's nominal TST."""
    arms = tuple(arms)
    by_id = {a.id: a for a in arms}
    rows = []
    for b, nominal in zip(BINS, NOMINAL_ROW_TST):
        s = bin_stats(b, arms)
        if nominal is None:
            ts = {by_id[i].tst for i in s.arms}
            if len(ts) != 1:
                raise ValueError(f"bin {b}: a row without a nominal TST needs a single arm, has {s.arms}")
            nominal = float(ts.pop())
        rows.append(Row(nominal, statistics.median(s.deep_values), statistics.median(s.rem_values), b))
    return tuple(rows)


ROWS: tuple[Row, ...] = target_rows()
ROWS_NO30: tuple[Row, ...] = tuple(r for r in ROWS if r.bin != (15.0, 45.0))   # sensitivity of section 5.1


def plateau_deep_min(rows: Sequence[Row] = ROWS) -> float:
    """DEEP minutes held above the last row: its DEEP share x its TST (0.2300 x 90 = 20.7 min)."""
    return rows[-1].deep / 100.0 * rows[-1].tst


def n3_minutes_long(arms: Iterable[Arm] = ARMS) -> list[tuple[float, str]]:
    """N3 minutes of the rule-I arms with TST >= 45 min (the plateau support and band), sorted."""
    return sorted((a.n3min, a.id) for a in included_arms(arms)
                  if a.n3min is not None and a.tst is not None and a.tst >= PLATEAU_MIN_TST)


def a_waso(arms: Iterable[Arm] = ARMS) -> float:
    """a = the median WASO share of the sleep period, WASO / (TST + WASO), over the rule-I arms reporting WASO."""
    return statistics.median(a.waso_pct for a in included_arms(arms) if a.waso_pct is not None) / 100.0


A_WASO: float = a_waso()


def _interp(rows: Sequence[Row], t: float, attr: str) -> float:
    if t <= rows[0].tst:
        return getattr(rows[0], attr)
    for r0, r1 in zip(rows, rows[1:]):
        if t <= r1.tst:
            y0, y1 = getattr(r0, attr), getattr(r1, attr)
            return y0 + (t - r0.tst) / (r1.tst - r0.tst) * (y1 - y0)
    return getattr(rows[-1], attr)


def deep_pct(t: float, rows: Sequence[Row] = ROWS) -> float:
    """D at TST t (min of sleep), % of TST: linear between the rows, constant below the first, plateau above the last
    (100 x plateau minutes / t)."""
    return 100.0 * plateau_deep_min(rows) / t if t > rows[-1].tst else _interp(rows, t, "deep")


def rem_pct(t: float, rows: Sequence[Row] = ROWS) -> float:
    """R at TST t, % of TST: linear between the rows, constant outside (REM held above the last row)."""
    return rows[-1].rem if t > rows[-1].tst else _interp(rows, t, "rem")


def tst_est(length_min: float, margins: float = TRAINING_MARGINS_MIN, a: float | None = None) -> float:
    """TST_est(L) = (1 - a) (L - margins): the sleep a session of L minutes is expected to contain."""
    return (1.0 - (A_WASO if a is None else a)) * (float(length_min) - margins)


@dataclass(frozen=True)
class Target:
    length_min: float
    tst: float
    deep: float               # D*(L), % of TST
    rem: float                # R*(L), % of TST

    @property
    def deep_min(self) -> float:
        return self.deep / 100.0 * self.tst


def target(length_min: float, rows: Sequence[Row] = ROWS, margins: float = TRAINING_MARGINS_MIN) -> Target:
    """D*(L) and R*(L): the rows at TST_est(L)."""
    t = tst_est(length_min, margins)
    return Target(float(length_min), t, deep_pct(t, rows), rem_pct(t, rows))


# --------------------------------------------------------------------------------- Tier-1 band (section 5.2) ----

@dataclass(frozen=True)
class Band:
    deep_lo: float
    deep_hi: float
    rem_lo: float
    rem_hi: float
    bins: tuple[tuple[float, float], ...]

    def lower_bounds_apply(self, t: float) -> bool:
        return t >= LOWER_BOUND_MIN_TST


def band(t: float, rows: Sequence[Row] = ROWS, arms: Iterable[Arm] = ARMS) -> Band:
    """The Tier-1 band at TST t: the range of the rule-I arm means the target at t is computed from (the bin of the
    first row at or below its TST; the bins of the two bracketing rows between rows; above the last row the N3-minute
    range of the rule-I arms with TST >= 45 min over t for DEEP and the last bin's REM range)."""
    arms = tuple(arms)
    last = rows[-1]
    if t > last.tst:
        n3 = [x for x, _ in n3_minutes_long(arms)]
        r = bin_stats(last.bin, arms).rem_values
        return Band(100.0 * min(n3) / t, 100.0 * max(n3) / t, min(r), max(r), (last.bin,))
    if t <= rows[0].tst:
        s = bin_stats(rows[0].bin, arms)
        return Band(min(s.deep_values), max(s.deep_values), min(s.rem_values), max(s.rem_values), (rows[0].bin,))
    for r0, r1 in zip(rows, rows[1:]):
        if t <= r1.tst:
            s0, s1 = bin_stats(r0.bin, arms), bin_stats(r1.bin, arms)
            d, r = s0.deep_values + s1.deep_values, s0.rem_values + s1.rem_values
            return Band(min(d), max(d), min(r), max(r), (r0.bin, r1.bin))
    raise AssertionError("unreachable")


# ------------------------------------------------------------------------------ Tier-2 guards (section 5.3) ----

GL_ROWS: tuple[tuple[float, float], ...] = ((0.0, 15.0), (15.0, 45.0), (45.0, 75.0), (75.0, math.inf))  # (lo, hi]
GL_MAX_INDIVIDUAL_N3_MIN = 54.0         # the largest published individual value in a daytime nap (Whitehurst 2018)


def share_dispersion(arm: Arm, stage: str) -> tuple[float, float] | None:
    """(mean, SD) of the DEEP or REM share (% of TST) of ``arm`` when its dispersion converts without assumption: a SD
    of the share, a SEM of the share x sqrt(n), a SD of the minutes over TST, a SEM of the minutes x sqrt(n) over
    TST; None otherwise."""
    d = arm.n3disp if stage == "DEEP" else arm.remdisp
    mean = arm.deep_pct if stage == "DEEP" else arm.rem_pct
    if d is None or mean is None:
        return None
    if d.kind in ("SEM%", "SEM") and arm.n is None:
        return None
    sd = {"SD%": d.value, "SEM%": d.value * math.sqrt(arm.n or 0), "SD": 100.0 * d.value / arm.tst,
          "SEM": 100.0 * d.value * math.sqrt(arm.n or 0) / arm.tst}[d.kind]
    return mean, sd


def minutes_dispersion(arm: Arm) -> tuple[float, float] | None:
    """(mean, SD) of the N3 minutes of ``arm`` when a dispersion of the minutes is published (SD, or SEM x sqrt(n))."""
    d = arm.n3disp
    if d is None or arm.n3min is None or d.kind not in ("SD", "SEM") or (d.kind == "SEM" and arm.n is None):
        return None
    return arm.n3min, d.value if d.kind == "SD" else d.value * math.sqrt(arm.n)


@dataclass(frozen=True)
class GuardRow:
    lo: float
    hi: float
    deep_pct: float | None
    deep_arm: str | None
    rem_pct: float | None
    rem_arm: str | None


def gl_rows(arms: Iterable[Arm] = ARMS) -> tuple[GuardRow, ...]:
    """GL per TST row (lo, hi]: the largest mean + 2 SD of the DEEP and REM shares over the rule-I arms of the row with
    a convertible dispersion (None: no such arm; the row has no rule of that stage)."""
    inc = included_arms(arms)
    out = []
    for lo, hi in GL_ROWS:
        sel = [a for a in inc if a.tst is not None and lo < a.tst <= hi]
        best = {}
        for stage in ("DEEP", "REM"):
            c = [(m + 2 * sd, a.id) for a in sel for m, sd in [share_dispersion(a, stage) or (None, None)]
                 if m is not None]
            best[stage] = max(c) if c else (None, None)
        out.append(GuardRow(lo, hi, best["DEEP"][0], best["DEEP"][1], best["REM"][0], best["REM"][1]))
    return tuple(out)


def gl_deep_min(arms: Iterable[Arm] = ARMS) -> tuple[float, float, str]:
    """(bound, largest rule-I arm mean + 2 SD of N3 minutes, its arm): the bound is the larger of that value and
    :data:`GL_MAX_INDIVIDUAL_N3_MIN`."""
    c = [(m + 2 * sd, a.id) for a in included_arms(arms) for m, sd in [minutes_dispersion(a) or (None, None)]
         if m is not None]
    best = max(c)
    return max(best[0], GL_MAX_INDIVIDUAL_N3_MIN), best[0], best[1]


GL: tuple[GuardRow, ...] = gl_rows()
GL_DEEP_MIN: float = gl_deep_min()[0]


def _pct(minutes: float, tst: float) -> float | None:
    return None if tst <= 0 else 100.0 * minutes / tst


def gl_rules(tst: float, deep_min: float, rem_min: float) -> list[str]:
    """The GL rules a nap of ``tst`` minutes of sleep with ``deep_min`` / ``rem_min`` violates (empty = plausible);
    rows by TST, (lo, hi]. A composition without sleep (TST 0) has no share rule."""
    out = []
    if deep_min > GL_DEEP_MIN:
        out.append("DEEP_MIN")
    dp, rp = _pct(deep_min, tst), _pct(rem_min, tst)
    row = next(r for r in GL if r.lo < tst <= r.hi) if tst > 0 else None
    if row is not None:
        if row.deep_pct is not None and dp is not None and dp > row.deep_pct:
            out.append("DEEP_PCT")
        if row.rem_pct is not None and rp is not None and rp > row.rem_pct:
            out.append("REM_PCT")
    return out


def gl_violation(tst: float, deep_min: float, rem_min: float) -> bool:
    """GL (section 5.3), the Tier-2 guard of every decision: TST <= 15: DEEP > 19.03 % or > 54 min; 15 < TST <= 45:
    DEEP > 72.71 %, REM > 9.59 % or DEEP > 54 min; 45 < TST <= 75: DEEP > 80.62 %, REM > 57.72 %, DEEP > 54 min;
    TST > 75: DEEP > 62.95 %, REM > 41.24 %, DEEP > 54 min (bounds computed from :data:`ARMS`)."""
    return bool(gl_rules(tst, deep_min, rem_min))


G_DEEP_PCT_SHORT = 11.0                # TST <= 15
G_DEEP_PCT = 75.0                      # 15 < TST <= 105
G_DEEP_MIN = 60.0                      # TST > 15
G_LIGHT_PCT_MIN = 25.0                 # TST > 15
G_REM_PCT = ((45.0, 30.0), (105.0, 40.0), (math.inf, 25.0))   # (TST upper bound, REM bound) for TST > 15


def g_rules(tst: float, deep_min: float, rem_min: float, light_min: float) -> list[str]:
    """The rules of G (the revised guard of the cross-check, reported only) a composition violates."""
    if tst <= 0:
        return []
    dp, rp, lp = (100.0 * x / tst for x in (deep_min, rem_min, light_min))
    if tst <= 15:
        return ["DEEP_PCT"] if dp > G_DEEP_PCT_SHORT else []
    out = []
    if tst <= 105 and dp > G_DEEP_PCT:
        out.append("DEEP_PCT")
    if deep_min > G_DEEP_MIN:
        out.append("DEEP_MIN")
    if lp < G_LIGHT_PCT_MIN:
        out.append("LIGHT_PCT")
    if rp > next(b for hi, b in G_REM_PCT if tst <= hi):
        out.append("REM_PCT")
    return out


def g_violation(tst: float, deep_min: float, rem_min: float, light_min: float) -> bool:
    """G: TST <= 15: DEEP > 11 %; 15 < TST <= 105: DEEP > 75 %, DEEP > 60 min, LIGHT < 25 %, REM > 30 % (TST <= 45)
    or > 40 % (TST > 45); TST > 105: DEEP > 60 min, LIGHT < 25 %, REM > 25 %."""
    return bool(g_rules(tst, deep_min, rem_min, light_min))


# ----------------------------------------------------------------- genuine-nap reference rates (section 5.4) ----

def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson score interval of k / n (fractions)."""
    if n <= 0:
        return float("nan"), float("nan")
    p = k / n
    d = 1.0 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return max(0.0, c - h), min(1.0, c + h)


ZERO_REM_60 = (("Mednick 2003 60-min", 13, 26), ("Chen 2021", 31, 60), ("Bergamo 2026", 10, 19),
               ("Cai 2009", 12, 40), ("Fogel 2014", 3, 13))
ZERO_REM_80_105 = (("Whitehurst 2016", 13, 48), ("Baran 2016", 6, 13), ("Mednick 2003 90-min", 2, 19))
GENUINE: dict[str, dict] = {
    "deepHalfWindow": {"k": 4, "n": 58, "source": "Zenodo + OSF chav7 young-adult lab naps (no nap at exactly 50 %)"},
    "zeroRem60": {"k": sum(x[1] for x in ZERO_REM_60), "n": sum(x[2] for x in ZERO_REM_60),
                  "source": "paper-level counts of five rule-I arms", "arms": ZERO_REM_60},
    "zeroRem80to105": {"k": sum(x[1] for x in ZERO_REM_80_105), "n": sum(x[2] for x in ZERO_REM_80_105),
                       "source": "Whitehurst 2016, Baran 2016, Mednick 2003 90-min", "arms": ZERO_REM_80_105},
    "zeroDeep55Chen": {"k": 7, "n": 60, "source": "Chen 2021 (rule-I arm), TST >= 55 min"},
    "zeroDeep55Zenodo": {"k": 4, "n": 23, "source": "Zenodo (individual), TST >= 55 min"},
    "n3Latency": {"minMin": 8.5, "medianMin": 21.5, "n": 35, "source": "open hypnograms"},
    "remLatency": {"minMin": 43.5, "medianMin": 59.0, "n": 18, "source": "Zenodo; Sleep-EDF"},
    "guardFalseFlags": {"G": {"all": (0, 93), "withoutZenodo": (0, 70)},
                        "GL": {"all": (4, 93), "withoutZenodo": (2, 70)},
                        "source": "93 genuine open naps (Zenodo 23, OSF chav7 35, Sleep-EDF 35), rows by TST"},
}


def rem_ceiling_short_naps(arms: Iterable[Arm] = ARMS, below_tst: float = 45.0) -> tuple[float, str]:
    """The highest study-mean REM share among the rule-I arms with TST < 45 min (Mednick 2002 short, 6.87 %), the
    borrowed bound of rule (iii)(b)."""
    return max((a.rem_pct, a.id) for a in included_arms(arms)
               if a.rem_pct is not None and a.tst is not None and a.tst < below_tst)


# ------------------------------------------------------------ literature-reweighted pools (section 5.5) ----

TILT_TOL = 1e-9                        # largest relative constraint violation |sum w g| / sum |w| |g|
TILT_NEWTON_MAX = 50
MIN_ESS = 10.0                         # Kish effective sample size (sessions)
FEASIBILITY_LAMBDAS: tuple[float, ...] = tuple(round(0.05 * k, 2) for k in range(1, 21))


def relative_violation(w: np.ndarray, g: np.ndarray) -> float:
    """max over the constraints of |sum_i w_i g_i| / sum_i |w_i| |g_i| (0 where the denominator is 0)."""
    num = np.abs(w @ g)
    den = np.abs(w) @ np.abs(g)
    return float(np.max(np.where(den > 0, num / np.where(den > 0, den, 1.0), 0.0)))


def kish_ess(w: np.ndarray) -> float:
    w = np.asarray(w, float)
    s2 = float(np.sum(w * w))
    return float(w.sum() ** 2 / s2) if s2 > 0 else 0.0


def tilt(q: np.ndarray, g: np.ndarray, tol: float = TILT_TOL, newton_max: int = TILT_NEWTON_MAX
         ) -> tuple[np.ndarray, dict]:
    """KL(w || q)-minimal weights w proportional to q exp(g lambda) with sum_i w_i g_i = 0 (columns of ``g``): BFGS on
    the convex dual log sum_i q_i exp(lambda . g_i) with g scaled column-wise by its maximum absolute value, then at
    most ``newton_max`` Newton steps on the same dual until the largest relative constraint violation is < ``tol``.
    Returns (w normalised to sum 1, info with converged, maxViolation, newtonSteps, bfgsIterations, lambda in the
    units of ``g``)."""
    q = np.asarray(q, float)
    q = q / q.sum()
    g = np.asarray(g, float)
    scale = np.maximum(np.abs(g).max(axis=0), 1e-12)
    gs = g / scale

    def weights(lam: np.ndarray) -> np.ndarray:
        z = gs @ lam
        e = q * np.exp(z - z.max())
        return e / e.sum()

    def dual(lam: np.ndarray) -> float:
        z = gs @ lam
        m = float(z.max())
        return m + math.log(float(np.sum(q * np.exp(z - m))))

    def grad(lam: np.ndarray) -> np.ndarray:
        return weights(lam) @ gs

    with np.errstate(over="ignore", invalid="ignore"):
        res = minimize(dual, np.zeros(g.shape[1]), jac=grad, method="BFGS", options={"gtol": 1e-12, "maxiter": 10_000})
    lam = np.asarray(res.x, float)
    w = weights(lam)
    viol = relative_violation(w, g)
    bfgs_viol = viol
    steps = 0
    while not viol < tol and steps < newton_max and np.all(np.isfinite(lam)):
        mu = w @ gs
        hess = (gs * w[:, None]).T @ gs - np.outer(mu, mu)
        try:
            lam = lam - np.linalg.solve(hess, mu)
        except np.linalg.LinAlgError:
            break
        steps += 1
        w = weights(lam)
        viol = relative_violation(w, g)
    ok = bool(viol < tol and np.all(np.isfinite(w)))
    return w, {"converged": ok, "maxViolation": viol, "bfgsViolation": bfgs_viol, "newtonSteps": steps,
               "bfgsIterations": int(res.nit), "lambda": (lam / scale).tolist()}


def base_weights(subjects: Sequence[str]) -> np.ndarray:
    """q_i = 1 / (n_s(i) n_subj): every subject has equal mass, split equally over its sessions."""
    subjects = list(subjects)
    n_subj = len(set(subjects))
    per = {s: subjects.count(s) for s in set(subjects)}
    return np.array([1.0 / (per[s] * n_subj) for s in subjects], float)


@dataclass
class LitWeights:
    """Weights of one (dataset, variant, set of sessions, length L) and how they were obtained."""
    length_min: float
    ids: list[str]
    w: np.ndarray
    target: tuple[float, float]                    # (DEEP, REM) share of sleep the weights reach (fractions)
    lit_target: tuple[float, float]                # (D*(L), R*(L)) as fractions
    base_target: tuple[float, float]               # T_q
    lam: float                                     # feasibility lambda (0 = the literature target)
    feasible: bool
    info: dict = field(default_factory=dict)

    @property
    def ess(self) -> float:
        return kish_ess(self.w)

    def as_dict(self) -> dict:
        return {"L": self.length_min, "sessions": len(self.ids), "target": list(self.target),
                "litTarget": list(self.lit_target), "baseTarget": list(self.base_target), "lambda": self.lam,
                "feasible": self.feasible, "ess": self.ess, **self.info}


def lit_weights(ids: Sequence[str], subjects: Sequence[str], counts: np.ndarray, length_min: float,
                lit_target: tuple[float, float] | None = None, min_ess: float = MIN_ESS) -> LitWeights:
    """Section 5.5 weights for the sessions ``ids`` of one length: ``counts`` (n x 4) are their true AWAKE, LIGHT,
    DEEP, REM 30-s label chunks. Tilted to (D*(L), R*(L)) (``lit_target`` as fractions overrides it); if the dual
    does not converge or the Kish ESS is below ``min_ess``, the target is T(lambda) = (1 - lambda) T* + lambda T_q with
    the smallest lambda in {0.05, ..., 1} that converges with ESS >= ``min_ess``."""
    c = np.asarray(counts, float)
    if c.ndim != 2 or c.shape[1] != 4 or c.shape[0] != len(ids) or len(ids) != len(subjects):
        raise ValueError("lit_weights needs one row of 4 counts and one subject per session")
    if len(ids) == 0:
        raise ValueError(f"no sessions of length {length_min}")
    t = target(length_min)
    lt = (t.deep / 100.0, t.rem / 100.0) if lit_target is None else tuple(map(float, lit_target))
    q = base_weights(subjects)
    deep, rem, sleep = c[:, 2], c[:, 3], c[:, 1:].sum(axis=1)
    qs = float(q @ sleep)
    tq = (float(q @ deep) / qs, float(q @ rem) / qs) if qs > 0 else (0.0, 0.0)
    attempts = []
    for lam in (0.0, *FEASIBILITY_LAMBDAS):
        tgt = ((1 - lam) * lt[0] + lam * tq[0], (1 - lam) * lt[1] + lam * tq[1])
        g = np.stack([deep - tgt[0] * sleep, rem - tgt[1] * sleep], axis=1)
        w, info = tilt(q, g)
        ess = kish_ess(w)
        attempts.append({"lambda": lam, "converged": info["converged"], "ess": ess})
        if info["converged"] and ess >= min_ess:
            return LitWeights(float(length_min), list(ids), w, tgt, lt, tq, lam, True,
                              {**info, "attempts": attempts})
    # Not covered by the pre-registration (section 5.5 names no outcome when even lambda = 1 fails, i.e. the base
    # weights have an ESS below 10): the closest faithful choice is the end of its grid, lambda = 1 (the base weights,
    # whose target T_q they reach exactly), flagged infeasible and reported. Amendment requested for section 5.5
    # before the code commit ("if no lambda reaches ESS >= 10, the base weights (lambda = 1) are used and the length
    # is flagged infeasible and reported"); on all 31 Walch subjects every length converges at lambda = 0 (2.5).
    return LitWeights(float(length_min), list(ids), q, tq, lt, tq, 1.0, False,
                      {"converged": True, "maxViolation": 0.0, "newtonSteps": 0, "attempts": attempts,
                       "note": "no lambda reached ESS >= 10; base weights used"})


def summary_tables() -> dict:
    """Every number of sections 5.1-5.3 recomputed from :data:`ARMS` (for reports and the run's ``lit/`` record)."""
    inc = included_arms()
    rows = [{"tst": r.tst, "deep": r.deep, "rem": r.rem, "bin": list(r.bin)} for r in ROWS]
    tgt = {L: target(L) for L in sorted(ANCHORS + MID_ANCHORS)}
    return {
        "includedArms": [a.id for a in inc], "excludedArms": {a.id: first_reason(a) for a in ARMS if not included(a)},
        "aWaso": A_WASO, "rows": rows, "plateauDeepMin": plateau_deep_min(),
        "targets": {str(L): {"tstEst": t.tst, "deep": t.deep, "rem": t.rem, "deepMin": t.deep_min,
                             "band": band(t.tst).__dict__ | {"bins": [list(b) for b in band(t.tst).bins]}}
                    for L, t in tgt.items()},
        "targetsNoMargin": {str(L): {"deep": target(L, margins=0.0).deep, "rem": target(L, margins=0.0).rem}
                            for L in ANCHORS},
        "targetsNo30Row": {str(L): {"deep": target(L, ROWS_NO30).deep, "rem": target(L, ROWS_NO30).rem}
                           for L in ANCHORS},
        "GL": [r.__dict__ for r in GL], "GLDeepMin": GL_DEEP_MIN,
    }

