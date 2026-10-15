import numpy as np
import pytest

from pulsetrain import models
from pulsetrain.models import ClassifierSpec, fit_classifier, fit_standardizer, layers_predict


def _data(n=2000, f=6, seed=0, imbalance=False):
    rng = np.random.default_rng(seed)
    y = rng.choice(4, size=n, p=[0.1, 0.5, 0.15, 0.25] if imbalance else None)
    centers = rng.normal(0, 2, size=(4, f))
    X = centers[y] + rng.normal(0, 1.5, size=(n, f)) * rng.uniform(0.5, 3, size=f) + rng.uniform(-50, 50, size=f)
    return X, y


def test_standardizer_stats_nan_zero_std_and_masking():
    X = np.array([[1.0, 5.0, np.nan, 2.0, 7.0],
                  [3.0, 5.0, np.nan, np.nan, 9.0],
                  [5.0, 5.0, np.nan, 4.0, np.inf]])
    st = fit_standardizer(X, masked=[4])
    np.testing.assert_allclose(st.mean, [3.0, 5.0, 0.0, 3.0, 0.0])
    np.testing.assert_allclose(st.std, [np.std([1, 3, 5]), 1.0, 1.0, 1.0, models.MASK_STD])
    z = st.transform(X)
    assert np.isfinite(z).all() and z[1, 3] == 0.0 and z[0, 2] == 0.0
    assert abs(st.transform(np.array([[0, 0, 0, 0, 1e3]]))[0, 4]) <= 1e-9          # masked ~0 at inference
    assert (st.transform_train(X)[:, 4] == 0).all()                                  # exactly 0 in training


@pytest.mark.parametrize("kind", ["lr", "mlp"])
def test_exported_layers_equal_sklearn_predict_proba(kind):
    X, y = _data()
    tc = fit_classifier(X, y, ClassifierSpec(kind=kind, C=0.5, mlp_max_iter=60))
    Xn, _ = _data(n=500, seed=1)
    Z = tc.standardizer.transform(Xn)
    diff = np.abs(layers_predict(tc.layers, Z) - tc.model.predict_proba(Z)).max()
    assert diff <= 1e-9 and tc.info["export_max_abs_diff"] <= 1e-9
    np.testing.assert_allclose(tc.prior_shift, 0.0)
    np.testing.assert_allclose(tc.predict_proba(Xn), tc.model.predict_proba(Z), atol=1e-9, rtol=0)
    acts = [layer["activation"] for layer in tc.layers]
    assert acts == (["softmax"] if kind == "lr" else ["relu", "relu", "softmax"])
    shapes = [np.asarray(layer["weights"]).shape for layer in tc.layers]
    assert shapes == ([(4, 6)] if kind == "lr" else [(32, 6), (32, 32), (4, 32)])  # out x in
    assert tc.type == ("logreg" if kind == "lr" else "mlp")


def test_balanced_class_weights_shift_back_to_natural_priors():
    X, y = _data(imbalance=True, seed=2)
    tc = fit_classifier(X, y, ClassifierSpec(kind="lr", C=1.0, class_weight="balanced"))
    freq = np.bincount(y, minlength=4) / len(y)
    np.testing.assert_allclose(tc.class_priors, freq)
    np.testing.assert_allclose(tc.prior_shift, np.log(freq) - np.log(0.25), atol=1e-12)
    Z = tc.standardizer.transform(X[:300])
    p_w = tc.model.predict_proba(Z)
    expected = p_w * freq / 0.25
    expected /= expected.sum(axis=1, keepdims=True)
    np.testing.assert_allclose(layers_predict(tc.layers, Z), expected, atol=1e-9, rtol=0)
    # scaled likelihoods p/prior are the balanced posteriors up to normalisation
    sl = layers_predict(tc.layers, Z) / tc.class_priors
    np.testing.assert_allclose(sl / sl.sum(axis=1, keepdims=True), p_w, atol=1e-9)


@pytest.mark.parametrize("kind", ["lr", "mlp"])
def test_masked_feature_has_no_influence(kind):
    X, y = _data(seed=3)
    X[:, 2] = np.where(y == 0, 10.0, 0.0)          # a "steps" column that would be very informative
    tc = fit_classifier(X, y, ClassifierSpec(kind=kind, mlp_max_iter=40), masked=[2])
    assert tc.standardizer.mean[2] == 0 and tc.standardizer.std[2] == models.MASK_STD
    w0 = np.asarray(tc.layers[0]["weights"])
    assert (w0[:, 2] == 0).all()
    a, b = X[:200].copy(), X[:200].copy()
    a[:, 2], b[:, 2] = 0.0, 1e4
    np.testing.assert_allclose(tc.predict_proba(a), tc.predict_proba(b), atol=1e-12)


def test_missing_rows_do_not_inform_the_feature_statistics():
    X, y = _data(seed=4)
    Xn = X.copy()
    Xn[:1000, 1] = np.nan
    tc = fit_classifier(Xn, y, ClassifierSpec(kind="lr"))
    assert tc.standardizer.mean[1] == pytest.approx(X[1000:, 1].mean())
    assert tc.standardizer.std[1] == pytest.approx(X[1000:, 1].std())


def test_fit_requires_all_stages_and_known_kind():
    X, y = _data(n=200)
    with pytest.raises(ValueError, match="all 4 stages"):
        fit_classifier(X[y != 2], y[y != 2], ClassifierSpec())
    with pytest.raises(ValueError, match="lr\\|mlp"):
        fit_classifier(X, y, ClassifierSpec(kind="svm"))
    with pytest.raises(ValueError):
        layers_predict([{"weights": [[1.0]], "bias": [0.0], "activation": "tanh"}], np.zeros((1, 1)))


def test_deterministic_fits():
    X, y = _data(seed=5)
    for kind in ("lr", "mlp"):
        a = fit_classifier(X, y, ClassifierSpec(kind=kind, mlp_max_iter=30, seed=7))
        b = fit_classifier(X, y, ClassifierSpec(kind=kind, mlp_max_iter=30, seed=7))
        for la, lb in zip(a.layers, b.layers):
            np.testing.assert_array_equal(la["weights"], lb["weights"])


def test_log_loss():
    p = np.array([[0.7, 0.1, 0.1, 0.1], [0.25, 0.25, 0.25, 0.25]])
    assert models.log_loss(p, np.array([0, 3])) == pytest.approx(-(np.log(0.7) + np.log(0.25)) / 2)
    assert np.isnan(models.log_loss(p[:0], np.array([], dtype=int)))


# ------------------------------------------------------------- row weights (docs/prereg/prereg-naps-realistic.md) ----

@pytest.mark.parametrize("kind", ["lr", "mlp"])
def test_row_weights_give_weighted_priors_and_no_prior_shift(kind):
    X, y = _data(imbalance=True, seed=6)
    w = np.where(y == 2, 3.0, 1.0) * (1.0 + (np.arange(len(y)) % 3))     # up-weights DEEP rows
    tc = fit_classifier(X, y, ClassifierSpec(kind=kind, C=0.5, mlp_max_iter=40), masked=[1], sample_weight=w)
    expected = np.bincount(y, weights=w, minlength=4) / w.sum()
    np.testing.assert_allclose(tc.class_priors, expected, rtol=0, atol=1e-15)
    assert not np.allclose(tc.class_priors, np.bincount(y, minlength=4) / len(y))
    np.testing.assert_array_equal(tc.prior_shift, np.zeros(4))
    Z = tc.standardizer.transform(X[:400])
    np.testing.assert_allclose(layers_predict(tc.layers, Z), tc.model.predict_proba(Z), atol=1e-9, rtol=0)
    assert tc.info["export_max_abs_diff"] <= 1e-9 and tc.info["weighted"] and tc.info["weightSum"] == w.sum()
    assert (np.asarray(tc.layers[0]["weights"])[:, 1] == 0).all() and tc.standardizer.std[1] == models.MASK_STD


def test_uniform_row_weights_fit_the_unweighted_logistic_regression():
    X, y = _data(seed=7)
    a = fit_classifier(X, y, ClassifierSpec(kind="lr", C=0.2))
    b = fit_classifier(X, y, ClassifierSpec(kind="lr", C=0.2), sample_weight=np.ones(len(y)))
    np.testing.assert_allclose(b.class_priors, a.class_priors, atol=1e-15)
    np.testing.assert_allclose(np.asarray(b.layers[0]["weights"]), np.asarray(a.layers[0]["weights"]), atol=1e-6)


def test_row_weights_are_checked_and_refused_with_class_weighting():
    X, y = _data(n=300)
    with pytest.raises(ValueError, match="refused"):
        fit_classifier(X, y, ClassifierSpec(kind="lr", class_weight="balanced"), sample_weight=np.ones(len(y)))
    for bad in (np.ones(len(y) - 1), np.full(len(y), np.nan), -np.ones(len(y)), np.zeros(len(y))):
        with pytest.raises(ValueError, match="sample_weight"):
            fit_classifier(X, y, ClassifierSpec(kind="lr"), sample_weight=bad)


def test_weighted_fits_are_deterministic():
    X, y = _data(seed=8)
    w = 1.0 + (np.arange(len(y)) % 5)
    a = fit_classifier(X, y, ClassifierSpec(kind="mlp", mlp_max_iter=30, seed=3), sample_weight=w)
    b = fit_classifier(X, y, ClassifierSpec(kind="mlp", mlp_max_iter=30, seed=3), sample_weight=w)
    assert a.layers_json() == b.layers_json()
