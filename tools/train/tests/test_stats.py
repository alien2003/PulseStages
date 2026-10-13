import numpy as np
import pytest
from scipy import stats as sps

from kotlin_contract import data_class_defaults, data_class_fields, physiology_table
from pulsetrain import stats
from pulsetrain.stats import Bout
from pulsetrain.util import AWAKE, DEEP, LIGHT, REM

A, L, D, R, X = AWAKE, LIGHT, DEEP, REM, -1


def test_segments_and_censoring():
    seq = [X, A, A, L, L, L, X, D, D, R]
    segs = stats.segments(seq)
    assert segs == [[Bout(A, 1, 2, True, False), Bout(L, 3, 3, False, True)],
                    [Bout(D, 7, 2, True, False), Bout(R, 9, 1, False, True)]]
    assert stats.extract_bouts([L, L]) == [Bout(L, 0, 2, True, True)]
    assert stats.extract_bouts([]) == [] and stats.extract_bouts([X, X]) == []
    with pytest.raises(ValueError):
        stats.extract_bouts([5])


def test_bout_lengths_uncensored_only_and_duration_fallback():
    seq = [A] * 3 + [L] * 10 + [D] * 4 + [L] * 6 + [R] * 5
    unc = stats.bout_lengths([seq], include_censored=False)
    assert unc[L].tolist() == [10, 6] and unc[D].tolist() == [4] and unc[A].size == 0 and unc[R].size == 0
    allb = stats.bout_lengths([seq], include_censored=True)
    assert allb[A].tolist() == [3] and allb[R].tolist() == [5]
    samples, used = stats._duration_samples([seq], 0.5, min_uncensored=2)
    assert samples[L].tolist() == [5.0, 3.0] and used[L] == "uncensored"
    assert samples[A].tolist() == [1.5] and used[A].startswith("all")       # only censored bouts -> use them


def test_gamma_mle_recovers_parameters():
    x = sps.gamma.rvs(2.5, scale=8.0, size=4000, random_state=np.random.default_rng(0))
    g = stats.fit_gamma(x, default_mean=20)
    assert g.method == "mle" and g.n == 4000
    assert g.shape == pytest.approx(2.5, rel=0.06) and g.scale == pytest.approx(8.0, rel=0.06)
    a, _, sc = sps.gamma.fit(x, floc=0)
    assert (g.shape, g.scale) == pytest.approx((a, sc))


def test_gamma_fallbacks(monkeypatch):
    assert stats.fit_gamma([], 5.0) == stats.GammaFit(1.0, 5.0, 0, "default")
    assert stats.fit_gamma([7.0, np.nan, -1], 5.0) == stats.GammaFit(1.0, 7.0, 1, "exponential")
    g = stats.fit_gamma([4.0, 4.0, 4.0], 5.0)
    assert g.method == "moments" and g.shape == 50.0 and g.mean == pytest.approx(4.0)

    def boom(*a, **k):
        raise RuntimeError("optimizer failed")

    monkeypatch.setattr(stats.sps.gamma, "fit", boom)
    x = np.array([2.0, 4.0, 6.0, 8.0])
    g = stats.fit_gamma(x, 5.0)
    assert g.method == "moments"
    assert g.shape == pytest.approx(x.mean() ** 2 / x.var()) and g.mean == pytest.approx(x.mean())


def test_bout_transitions_split_long_bouts_and_ignore_gaps():
    # 1-min epochs, max bout 60 min: a 130-min LIGHT bout = 3 pieces -> 2 self transitions
    seq = [A] * 3 + [L] * 130 + [D] * 5 + [L] * 2 + [X] + [R] * 4 + [A] * 2
    c = stats.bout_transition_counts([seq], epoch_min=1.0, max_bout_min=60)
    expected = np.zeros((4, 4))
    expected[A, L] = 1
    expected[L, L] = 2
    expected[L, D] = 1
    expected[D, L] = 1
    expected[R, A] = 1               # second segment; no L->R across the gap
    np.testing.assert_array_equal(c, expected)
    # exactly Dmax long -> no split; 30-s epochs: Dmax = 120 epochs
    assert stats.bout_transition_counts([[L] * 120 + [D]], 0.5, 60).sum() == 1
    assert stats.bout_transition_counts([[L] * 121 + [D]], 0.5, 60)[L, L] == 1
    assert stats.max_bout_epochs(120, 1.0) == 120 and stats.max_bout_epochs(1, 2.0) == 1


def test_transition_matrix_pseudocount_off_diagonal_only():
    c = np.zeros((4, 4))
    c[A, L] = 9
    c[L, L] = 2
    c[L, D] = 4
    p = stats.transition_matrix(c, pseudocount=1.0)
    np.testing.assert_allclose(p.sum(axis=1), 1.0)
    assert p[A, A] == 0 and p[A, L] == pytest.approx(10 / 12) and p[A, D] == pytest.approx(1 / 12)
    assert p[L, L] == pytest.approx(2 / 9) and p[L, D] == pytest.approx(5 / 9)
    q = stats.transition_matrix(np.zeros((4, 4)), pseudocount=0.0)
    np.testing.assert_allclose(q, (1 - np.eye(4)) / 3)


def test_initial_distribution():
    p = stats.initial_distribution([[X, A, L], [A], [L, L], [X, X]], pseudocount=0.5)
    np.testing.assert_allclose(p, np.array([2.5, 1.5, 0.5, 0.5]) / 5.0)


def test_time_prior_bins_tail_merge_and_laplace():
    s1 = [A] * 30 + [L] * 30 + [D] * 30 + [R] * 5          # bins 0..3 (bin 3: 5 epochs)
    s2 = [A] * 10 + [L] * 50                               # bins 0..1
    c = stats.time_prior_counts([s1, s2], epoch_min=1.0, bin_minutes=30)
    np.testing.assert_array_equal(c, [[40, 20, 0, 0], [0, 60, 0, 0], [0, 0, 30, 0], [0, 0, 0, 5]])
    table, merged = stats.time_prior_table([s1, s2], 1.0, 30, alpha=1.0, min_count=30)
    np.testing.assert_array_equal(merged, [[40, 20, 0, 0], [0, 60, 0, 0], [0, 0, 30, 5]])
    np.testing.assert_allclose(table[2], np.array([1, 1, 31, 6]) / 39)
    np.testing.assert_allclose(table.sum(axis=1), 1.0)
    # 30-s epochs: 60 epochs per bin
    assert stats.time_prior_counts([[L] * 61], 0.5, 30).tolist() == [[0, 60, 0, 0], [0, 1, 0, 0]]
    # no bin reaches min_count -> one bin with everything
    t1, m1 = stats.time_prior_table([s1], 1.0, 30, min_count=1000)
    assert m1.tolist() == [[30, 30, 30, 5]] and t1.shape == (1, 4)
    t0, _ = stats.time_prior_table([[X, X]], 1.0)
    np.testing.assert_allclose(t0, [[0.25] * 4])


def test_class_priors():
    y = np.array([0, 1, 1, 2, 3, 3, 3, -1])
    np.testing.assert_allclose(stats.class_priors(y), [1 / 7, 2 / 7, 1 / 7, 3 / 7])
    w = np.array([2, 1, 1, 1, 1, 1, 1, 100.0])
    np.testing.assert_allclose(stats.class_priors(y, w), [2 / 8, 2 / 8, 1 / 8, 3 / 8])
    with pytest.raises(ValueError):
        stats.class_priors(np.array([-1]))


def test_hmm_emissions_mle_and_fallback():
    rng = np.random.default_rng(1)
    hr = np.vstack([rng.normal([2.5, 1.8, 0.0], [0.5, 0.4, 0.3], size=(500, 3)),
                    rng.normal([1.0, 1.0, 0.0], [0.4, 0.3, 0.2], size=(500, 3)),
                    rng.normal([0.2, 0.5, 0.0], [0.0, 0.0, 0.0], size=(500, 3)),
                    np.full((3, 3), 1.6)])
    hr[0, 1] = np.nan
    y = np.r_[np.full(500, A), np.full(500, L), np.full(500, D), np.full(3, R)]
    em, notes = stats.hmm_emissions(hr, y, sd_floor=0.05, min_n=10)
    assert list(em) == ["awake", "light", "deep", "rem"]
    assert em["awake"]["hrNorm"]["mean"] == pytest.approx(hr[:500, 0].mean())
    assert em["awake"]["hrNorm"]["sd"] == pytest.approx(hr[:500, 0].std())             # population sd (MLE)
    assert em["awake"]["hrVar"]["mean"] == pytest.approx(np.nanmean(hr[:500, 1]))
    assert em["deep"]["hrNorm"]["sd"] == 0.05                                          # floor
    assert em["rem"] == stats.PHYSIOLOGY_EMISSIONS["rem"]                              # too few rows
    assert any("rem.hrNorm" in n for n in notes)


def test_mean_bouts_and_transition_weights_layout():
    seq = [A] * 2 + [L] * 10 + [D] * 4 + [L] * 6 + [R] * 8 + [A] * 3 + [L] * 4
    mb, _ = stats.mean_bouts([seq], epoch_min=1.0, min_uncensored=1)
    assert mb == {"awakeMin": 3.0, "lightMin": 8.0, "deepMin": 4.0, "remMin": 8.0}
    assert list(mb) == data_class_fields("MeanBouts")
    c = stats.bout_transition_counts([seq], 1.0)
    tw, notes = stats.transition_weights(c)
    assert list(tw) == data_class_fields("TransitionWeights")
    assert tw["lightToDeep"] == pytest.approx(0.5) and tw["lightToRem"] == pytest.approx(0.5)
    assert tw["lightToAwake"] == 0.0 and tw["awakeToLight"] == 1.0
    assert tw["deepToLight"] == 1.0 and tw["remToAwake"] == 1.0
    mb2, notes2 = stats.mean_bouts([[L, L]], 1.0)
    assert mb2["deepMin"] == stats.DEFAULT_MEAN_BOUTS["deepMin"] and notes2


def test_python_defaults_match_kotlin_algo_params():
    assert stats.DEFAULT_MEAN_BOUTS == data_class_defaults("MeanBouts")
    assert stats.DEFAULT_TRANSITIONS == data_class_defaults("TransitionWeights")
    assert stats.PHYSIOLOGY_EMISSIONS == physiology_table()
    assert data_class_fields("EmissionTable") == ["awake", "light", "deep", "rem"]
    assert data_class_fields("StateEmission") == list(stats.EMISSION_DIMS)
    assert data_class_fields("Gaussian") == ["mean", "sd"]


def test_fit_label_stats_end_to_end():
    rng = np.random.default_rng(3)
    seqs = {}
    for k in range(20):
        s = [A] * int(rng.integers(3, 8))
        while len(s) < 300:
            s += [L] * int(rng.integers(5, 25)) + [D] * int(rng.integers(5, 20)) + [L] * int(rng.integers(3, 9))
            s += [R] * int(rng.integers(5, 20)) + [A] * int(rng.integers(1, 4))
        seqs[f"n{k}"] = np.array(s[:300])
    st = stats.fit_label_stats(seqs, epoch_min=1.0, params=stats.LabelStatsParams(time_prior_min_count=100))
    assert set(st.durations) == {"AWAKE", "LIGHT", "DEEP", "REM"}
    assert all(g.shape > 0 and g.scale > 0 for g in st.durations.values())
    assert st.durations["DEEP"].mean == pytest.approx(st.mean_bouts["deepMin"], rel=0.1)
    np.testing.assert_allclose(st.bout_transitions.sum(axis=1), 1.0)
    np.testing.assert_allclose(np.diag(st.bout_transitions), 0.0)                    # no bout > 120 min
    assert st.initial.argmax() == A and st.n_nights == 20
    assert st.time_prior.shape == (10, 4)                                            # 300 min / 30
    assert st.durations_json()["LIGHT"] == {"shape": st.durations["LIGHT"].shape,
                                            "scale": st.durations["LIGHT"].scale}


# ------------------------------------------------------------------------------------------ HMM time prior ----

MB = {"awakeMin": 4.0, "lightMin": 12.0, "deepMin": 15.0, "remMin": 14.0}
TW = dict(stats.DEFAULT_TRANSITIONS, awakeToDeep=0.05)


def _simulate(prior, n_nights, length, seed):
    """Label sequences drawn from the core-algo transition model (stats.hmm_transition_probs) with ``prior``."""
    rng = np.random.default_rng(seed)
    self_p, shares = stats._base_rows(MB, TW, 1.0, 1e-4)
    seqs = []
    for _ in range(n_nights):
        s = np.zeros(length, dtype=np.int64)
        s[0] = A if rng.random() < 0.9 else L
        for k in range(1, length):
            fr = np.full(4, s[k - 1])
            p = stats.hmm_transition_probs(fr, np.arange(4), np.full(4, (k + 0.5) / length),
                                           np.full(4, k * 1.0 < prior["remLockoutMin"]), prior, self_p, shares, 1e-4)
            s[k] = rng.choice(4, p=p / p.sum())
        seqs.append(s)
    return seqs


def test_transition_probs_follow_the_kotlin_transition_model():
    self_p, shares = stats._base_rows(MB, TW, 1.0, 1e-4)
    prior = dict(stats.SPEC_TIME_PRIOR)
    for t in (0.05, 0.5, 0.95):
        for lock in (False, True):
            rows = np.array([stats.hmm_transition_probs(np.full(4, i), np.arange(4), np.full(4, t), np.full(4, lock),
                                                        prior, self_p, shares, 1e-4) for i in range(4)])
            np.testing.assert_allclose(rows.sum(axis=1), 1.0)
            if not lock:
                assert rows[D, D] == pytest.approx(1 - 1 / 15) and rows[A, D] == pytest.approx(
                    (1 / 4) * 0.05 / (0.9 + 0.05 + 0.1))
    # LIGHT row at t: DEEP share x max(0.3, 1.6 - 1.2 t), REM share x (0.4 + 1.2 t), renormalized off-diagonal
    t = 0.9
    w = np.array([0.2, 0.0, 0.45 * max(0.3, 1.6 - 1.2 * t), 0.35 * (0.4 + 1.2 * t)])
    exp = (1 / 12) * w / w.sum()
    got = stats.hmm_transition_probs(np.full(4, L), np.arange(4), np.full(4, t), np.zeros(4, bool), prior, self_p,
                                     shares, 1e-4)
    np.testing.assert_allclose(got[[A, D, R]], exp[[A, D, R]])
    # lockout: REM target x 0.05, the rest scaled so that the row still sums to 1
    free = stats.hmm_transition_probs(np.full(4, A), np.arange(4), np.full(4, 0.01), np.zeros(4, bool), prior,
                                      self_p, shares, 1e-4)
    locked = stats.hmm_transition_probs(np.full(4, A), np.arange(4), np.full(4, 0.01), np.ones(4, bool), prior,
                                        self_p, shares, 1e-4)
    assert locked[R] == pytest.approx(free[R] * 0.05)
    np.testing.assert_allclose(locked[[A, L, D]], free[[A, L, D]] * (1 - locked[R]) / (1 - free[R]))


def test_time_prior_mle_recovers_simulated_parameters():
    true = dict(stats.SPEC_TIME_PRIOR, deepTimeIntercept=1.6, deepTimeSlope=2.4, deepTimeFloor=0.3,
                remTimeIntercept=0.3, remTimeSlope=1.8, remLockoutFactor=0.1)      # the floor binds for t > 0.54
    seqs = _simulate(true, 250, 480, seed=1)
    est, notes = stats.hmm_time_prior(seqs, 1.0, MB, TW)
    assert notes and "conditional MLE" in notes[-1]
    for k, tol in (("deepTimeIntercept", 0.3), ("deepTimeSlope", 0.6), ("deepTimeFloor", 0.15),
                   ("remTimeIntercept", 0.15), ("remTimeSlope", 0.5), ("remLockoutFactor", 0.06)):
        assert abs(est[k] - true[k]) < tol, (k, est[k], true[k])
    assert est["remLockoutMin"] == 45.0
    first_awake = sum(1 for s in seqs if s[0] == A)
    assert est["initialAwake"] == pytest.approx((first_awake + 0.5) / (len(seqs) + 1))
    assert est["initialAwake"] + est["initialLight"] == pytest.approx(1.0)
    # bounds = AlgoParamsValidator ranges (all >= 0, factor in [0, 1])
    assert all(est[k] >= 0 for k in stats.TIME_PRIOR_KEYS) and est["remLockoutFactor"] <= 1


def test_time_prior_without_light_exits_keeps_the_specification():
    est, notes = stats.hmm_time_prior([np.array([A] * 30 + [D] * 30)], 1.0, MB, TW)
    assert est == dict(stats.SPEC_TIME_PRIOR, initialAwake=0.75, initialLight=0.25)
    assert "no LIGHT exits" in notes[-1]


def test_spec_time_prior_matches_kotlin_and_order_check():
    assert stats.SPEC_TIME_PRIOR == data_class_defaults("HmmTimePrior")
    assert list(stats.TIME_PRIOR_KEYS) == data_class_fields("HmmTimePrior")
    assert set(stats.TIME_PRIOR_KEYS) <= set(data_class_fields("HmmParams"))
    assert stats.hr_norm_ordered(stats.PHYSIOLOGY_EMISSIONS)
    bad = {k: {d: dict(g) for d, g in v.items()} for k, v in stats.PHYSIOLOGY_EMISSIONS.items()}
    bad["deep"]["hrNorm"]["mean"] = 1.28                    # the degenerate loso-v1 estimate (DEEP > LIGHT)
    assert not stats.hr_norm_ordered(bad)
