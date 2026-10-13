"""Emission classifiers for the hybrid estimator and their export to the bundle ``classifier.layers`` format.

Inference semantics (identical to :core-algo): z = (x − mean) / std per feature, non-finite z -> 0 (= training mean),
then dense layers ``a = act(W a + b)`` with W stored out x in; hidden layers relu, the last one softmax.

Standardization statistics come from the training rows only (finite values; population std; std == 0 -> 1).

Masked features (e.g. step features when part of the training data has no step channel): the exported
standardization is mean 0, std 1e12 (so the standardized value is ~0 for any real input), the column is zero during
training and its first-layer weights are set to exactly 0 after fitting (a no-op on the zero training column).

Features can also be *missing per row* (NaN where algo-cli leaves a feature undefined, e.g. windows with too few
values): such values are excluded from the statistics and become 0 after standardization, exactly as core-algo does
at inference. The pipeline never sets values to NaN itself (loso.check_train_inference_parity): the retired
``nonstep`` policy did so for the step features of nights without a step channel, for which algo-cli and the app
compute 0 steps, i.e. a different value at inference.

Class weighting ``balanced`` trains with sample weights n / (4 n_c). To keep the bundle semantics ("posteriors are
divided by the training class frequencies") the exported final-layer bias is shifted by log(freq_c / eff_c), where
eff_c is the weighted class share (0.25 when balanced): softmax(z + log r) = p_w(c|x) r_c / Σ, i.e. the posterior
under the natural class frequencies. Without weighting the shift is 0 and the export equals predict_proba exactly.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

import numpy as np
from sklearn.exceptions import ConvergenceWarning
from sklearn.linear_model import LogisticRegression
from sklearn.neural_network import MLPClassifier
from threadpoolctl import threadpool_limits

from .stats import class_priors
from .util import N_STAGES

MASK_STD = 1e12
EXPORT_TOLERANCE = 1e-9


@dataclass(frozen=True)
class Standardizer:
    mean: np.ndarray
    std: np.ndarray
    masked: tuple[int, ...] = ()

    def transform(self, X: np.ndarray) -> np.ndarray:
        """Inference transform (what :core-algo does): (x − mean) / std, non-finite -> 0."""
        with np.errstate(invalid="ignore", over="ignore"):
            z = (np.asarray(X, dtype=float) - self.mean) / self.std
        z[~np.isfinite(z)] = 0.0
        return z

    def transform_train(self, X: np.ndarray) -> np.ndarray:
        """Training transform: as :meth:`transform`, masked columns exactly 0."""
        z = self.transform(X)
        if self.masked:
            z[:, list(self.masked)] = 0.0
        return z


def fit_standardizer(X: np.ndarray, masked: Sequence[int] = ()) -> Standardizer:
    X = np.asarray(X, dtype=float)
    finite = np.isfinite(X)
    n = finite.sum(axis=0)
    Xz = np.where(finite, X, 0.0)
    with np.errstate(invalid="ignore", divide="ignore"):
        mean = np.where(n > 0, Xz.sum(axis=0) / np.maximum(n, 1), 0.0)
        var = np.where(n > 0, (np.where(finite, X - mean, 0.0) ** 2).sum(axis=0) / np.maximum(n, 1), 1.0)
    std = np.sqrt(var)
    std[~np.isfinite(std) | (std <= 0)] = 1.0
    mean[~np.isfinite(mean)] = 0.0
    m = tuple(sorted(set(int(i) for i in masked)))
    for i in m:
        mean[i], std[i] = 0.0, MASK_STD
    return Standardizer(mean=mean, std=std, masked=m)


@dataclass(frozen=True)
class ClassifierSpec:
    kind: str = "lr"                       # lr | mlp
    C: float = 1.0                         # logistic regression inverse L2 strength
    class_weight: str = "none"             # none | balanced
    seed: int = 0
    lr_max_iter: int = 2000
    mlp_hidden: tuple[int, ...] = (32, 32)
    mlp_alpha: float = 1e-4
    mlp_batch_size: int = 256
    mlp_learning_rate_init: float = 1e-3
    mlp_max_iter: int = 200
    mlp_n_iter_no_change: int = 10
    mlp_validation_fraction: float = 0.1

    def describe(self) -> str:
        if self.kind == "lr":
            return f"multinomial logistic regression (lbfgs, L2, C={self.C:g}, class_weight={self.class_weight})"
        return (f"MLP {'x'.join(map(str, self.mlp_hidden))} relu (adam, alpha={self.mlp_alpha:g}, early stopping, "
                f"random_state={self.seed}, class_weight={self.class_weight})")


@dataclass
class TrainedClassifier:
    spec: ClassifierSpec
    standardizer: Standardizer
    layers: list[dict]                     # numpy: weights (out x in), bias (out), activation
    class_priors: np.ndarray               # natural class frequencies of the training rows
    model: object = field(repr=False, default=None)
    prior_shift: np.ndarray = field(default_factory=lambda: np.zeros(N_STAGES))
    info: dict = field(default_factory=dict)

    @property
    def type(self) -> str:
        return "logreg" if self.spec.kind == "lr" else "mlp"

    def predict_proba(self, X_raw: np.ndarray) -> np.ndarray:
        """Bundle inference on raw features (standardization + exported layers)."""
        return layers_predict(self.layers, self.standardizer.transform(X_raw))

    def layers_json(self) -> list[dict]:
        return [{"weights": np.asarray(l["weights"], float).tolist(), "bias": np.asarray(l["bias"], float).tolist(),
                 "activation": l["activation"]} for l in self.layers]


def softmax(z: np.ndarray) -> np.ndarray:
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def layers_predict(layers: Sequence[dict], Z: np.ndarray) -> np.ndarray:
    a = np.asarray(Z, dtype=float)
    for layer in layers:
        a = a @ np.asarray(layer["weights"], float).T + np.asarray(layer["bias"], float)
        act = layer["activation"]
        if act == "relu":
            a = np.maximum(a, 0.0)
        elif act == "softmax":
            a = softmax(a)
        else:
            raise ValueError(f"unknown activation {act!r}")
    return a


def class_sample_weights(y: np.ndarray, mode: str) -> np.ndarray | None:
    if mode == "none":
        return None
    if mode != "balanced":
        raise ValueError(f"class_weight must be none|balanced, got {mode!r}")
    counts = np.bincount(y, minlength=N_STAGES).astype(float)
    w = np.zeros(N_STAGES)
    nz = counts > 0
    w[nz] = len(y) / (N_STAGES * counts[nz])
    return w[y]


def export_layers(model, prior_shift: np.ndarray | None = None) -> list[dict]:
    """sklearn model -> layers (weights out x in). ``prior_shift`` is added to the final bias."""
    if isinstance(model, LogisticRegression):
        layers = [{"weights": model.coef_.copy(), "bias": model.intercept_.copy(), "activation": "softmax"}]
    elif isinstance(model, MLPClassifier):
        if model.activation != "relu" or model.out_activation_ != "softmax":
            raise ValueError("only relu hidden layers with a softmax output can be exported")
        n = len(model.coefs_)
        layers = [{"weights": W.T.copy(), "bias": b.copy(), "activation": "softmax" if i == n - 1 else "relu"}
                  for i, (W, b) in enumerate(zip(model.coefs_, model.intercepts_))]
    else:
        raise TypeError(f"cannot export {type(model).__name__}")
    if prior_shift is not None:
        layers[-1]["bias"] = layers[-1]["bias"] + np.asarray(prior_shift, float)
    return layers


def expected_proba(model, Z: np.ndarray, prior_shift: np.ndarray) -> np.ndarray:
    """sklearn predict_proba with the exported prior shift applied in probability space."""
    p = model.predict_proba(Z) * np.exp(prior_shift)
    return p / p.sum(axis=1, keepdims=True)


def verify_export(tc: TrainedClassifier, Z: np.ndarray, tol: float = EXPORT_TOLERANCE) -> float:
    """Max |exported-layer inference − sklearn predict_proba| on standardized rows ``Z``; raises above ``tol``."""
    if Z.shape[0] == 0:
        return 0.0
    diff = float(np.max(np.abs(layers_predict(tc.layers, Z) - expected_proba(tc.model, Z, tc.prior_shift))))
    if not diff <= tol:
        raise AssertionError(f"exported classifier differs from sklearn by {diff:.3g} > {tol:g}")
    return diff


def fit_classifier(X: np.ndarray, y: np.ndarray, spec: ClassifierSpec, masked: Sequence[int] = (),
                   verify_rows: int = 5000) -> TrainedClassifier:
    """Fits standardization + classifier on raw features ``X`` (NaN allowed) and stage labels ``y`` (0..3)."""
    import warnings

    y = np.asarray(y).astype(np.int64)
    if X.shape[0] != y.shape[0]:
        raise ValueError("X and y lengths differ")
    present = np.bincount(y, minlength=N_STAGES)[:N_STAGES] > 0
    if not present.all() or y.min() < 0 or y.max() >= N_STAGES:
        missing = [i for i in range(N_STAGES) if not present[i]]
        raise ValueError(f"training rows must contain all 4 stages (missing indices {missing}) and only 0..3")
    st = fit_standardizer(X, masked)
    Z = st.transform_train(X)
    sw = class_sample_weights(y, spec.class_weight)
    with threadpool_limits(1), warnings.catch_warnings():
        warnings.simplefilter("ignore", ConvergenceWarning)
        if spec.kind == "lr":
            model = LogisticRegression(C=spec.C, solver="lbfgs", max_iter=spec.lr_max_iter)
            model.fit(Z, y, sample_weight=sw)
            if st.masked:
                model.coef_[:, list(st.masked)] = 0.0
            n_iter = int(np.max(model.n_iter_))
        elif spec.kind == "mlp":
            model = MLPClassifier(hidden_layer_sizes=spec.mlp_hidden, activation="relu", solver="adam",
                                  alpha=spec.mlp_alpha, batch_size=spec.mlp_batch_size,
                                  learning_rate_init=spec.mlp_learning_rate_init, max_iter=spec.mlp_max_iter,
                                  early_stopping=True, validation_fraction=spec.mlp_validation_fraction,
                                  n_iter_no_change=spec.mlp_n_iter_no_change, random_state=spec.seed)
            model.fit(Z, y, sample_weight=sw)
            if st.masked:
                model.coefs_[0][list(st.masked), :] = 0.0
            n_iter = int(model.n_iter_)
        else:
            raise ValueError(f"classifier kind must be lr|mlp, got {spec.kind!r}")
    if list(model.classes_) != list(range(N_STAGES)):
        raise ValueError(f"unexpected class order {model.classes_}")
    priors = class_priors(y)
    eff = class_priors(y, sw) if sw is not None else priors
    shift = np.log(priors) - np.log(eff)
    tc = TrainedClassifier(spec=spec, standardizer=st, layers=export_layers(model, shift), class_priors=priors,
                           model=model, prior_shift=shift, info={"n_iter": n_iter, "rows": int(len(y))})
    if verify_rows:
        idx = np.linspace(0, len(y) - 1, num=min(verify_rows, len(y)), dtype=np.int64)
        tc.info["export_max_abs_diff"] = verify_export(tc, Z[idx])
    return tc


def log_loss(proba: np.ndarray, y: np.ndarray, eps: float = 1e-15) -> float:
    """Mean multinomial log-loss of probabilities ``proba`` (n, 4) for labels ``y``."""
    if len(y) == 0:
        return float("nan")
    p = np.clip(proba[np.arange(len(y)), np.asarray(y, dtype=np.int64)], eps, 1.0)
    return float(-np.mean(np.log(p)))
