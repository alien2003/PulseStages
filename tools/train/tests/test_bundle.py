import copy
import json

import numpy as np
import pytest

from kotlin_contract import bundle_doc_keys, data_class_fields
from pulsetrain import bundle, stats
from pulsetrain.models import ClassifierSpec, fit_classifier
from pulsetrain.util import dumps_pretty

NAMES = ["hrNorm", "hrVar", "stepsLog1p", "mean_15"]


def _make(kind="lr", hmm=True):
    rng = np.random.default_rng(0)
    y = rng.integers(0, 4, size=800)
    X = rng.normal(size=(800, 4)) + y[:, None]
    tc = fit_classifier(X, y, ClassifierSpec(kind=kind, mlp_max_iter=20), masked=[2])
    seq = np.array(([0] * 5 + [1] * 20 + [2] * 15 + [1] * 10 + [3] * 12) * 4)
    ls = stats.fit_label_stats([seq, seq[::-1]], 1.0, stats.LabelStatsParams(time_prior_min_count=10))
    em, _ = stats.hmm_emissions(rng.normal(size=(800, 3)), y)
    h = {"emissions": em, "meanBouts": ls.mean_bouts, "transitions": ls.transition_weights} if hmm else None
    return bundle.build_bundle(model_id="test", feature_set_version=1, feature_names=NAMES, classifier=tc,
                               label_stats=ls, hmm=h, training={
                                   "dataset": "x", "license": "y", "subjects": 3, "nights": 4,
                                   "variants": ["instantaneous"], "createdAt": "2026-01-01T00:00:00Z",
                                   "notes": "masked features: stepsLog1p", "extra": "ignored"})


def _keys(obj, skip=("emissions", "meanBouts", "transitions")):
    out = set()
    if isinstance(obj, dict):
        for k, v in obj.items():
            out.add(k)
            if k not in skip:
                out |= _keys(v, skip)
    elif isinstance(obj, list):
        for v in obj:
            out |= _keys(v, skip)
    return out


def test_bundle_keys_match_interchange_doc():
    b = _make("mlp")
    assert list(b) == [k for k in bundle.TOP_LEVEL_KEYS if k != "stageLogBias"]   # optional, absent by default
    biased = bundle.with_stage_log_bias(b, [0.1, 0.0, -0.2, 0.1])
    assert list(biased) == list(bundle.TOP_LEVEL_KEYS)
    assert _keys(biased) == bundle_doc_keys()
    assert b["classOrder"] == ["AWAKE", "LIGHT", "DEEP", "REM"] and b["schemaVersion"] == 1
    assert [layer["activation"] for layer in b["classifier"]["layers"]] == ["relu", "relu", "softmax"]


def test_hmm_block_uses_algo_params_field_names():
    h = _make()["hmm"]
    assert list(h["emissions"]) == data_class_fields("EmissionTable")
    for st in h["emissions"].values():
        assert list(st) == data_class_fields("StateEmission")
        assert all(list(g) == data_class_fields("Gaussian") for g in st.values())
    assert list(h["meanBouts"]) == data_class_fields("MeanBouts")
    assert list(h["transitions"]) == data_class_fields("TransitionWeights")
    assert list(data_class_fields("HmmParams")[:3]) == ["emissions", "meanBouts", "transitions"]


def test_round_trip(tmp_path):
    b = _make()
    p = bundle.write_bundle(tmp_path / "m.json", b)
    assert bundle.read_bundle(p) == b
    assert json.loads(p.read_text()) == b
    st = b["standardization"]
    assert st["mean"][2] == 0.0 and st["std"][2] == 1e12
    assert "hmm" not in _make(hmm=False)


@pytest.mark.parametrize("mutate,msg", [
    (lambda b: b.pop("timePrior"), "missing keys"),
    (lambda b: b.update(extra=1), "unknown keys"),
    (lambda b: b.update(classOrder=["LIGHT", "AWAKE", "DEEP", "REM"]), "classOrder"),
    (lambda b: b["standardization"]["std"].__setitem__(0, 0.0), "std must be > 0"),
    (lambda b: b["standardization"]["mean"].pop(), "standardization.mean"),
    (lambda b: b["classifier"]["layers"][0]["weights"][0].pop(), "weights"),
    (lambda b: b["classifier"]["layers"][0].update(activation="relu"), "activation"),
    (lambda b: b["boutTransitions"][1].__setitem__(0, 5.0), "boutTransitions[1]"),
    (lambda b: b["timePrior"]["table"].append([1, 0, 0]), "timePrior.table"),
    (lambda b: b["durations"]["DEEP"].update(shape=-1.0), "durations.DEEP"),
    (lambda b: b["hmm"]["emissions"]["rem"]["hrVar"].update(sd=0), "hmm.emissions.rem.hrVar"),
    (lambda b: b["hmm"]["transitions"].pop("remToDeep"), "hmm.transitions"),
    (lambda b: b["training"].update(subjects="3"), "training.subjects"),
    (lambda b: b.update(featureSetVersion=1.5), "featureSetVersion"),
    (lambda b: b.update(stageLogBias=[0.0, 0.1, 0.2]), "stageLogBias"),
    (lambda b: b.update(stageLogBias=[0.0, 0.1, float("inf"), 0.0]), "stageLogBias"),
])
def test_validation_rejects(mutate, msg):
    b = copy.deepcopy(_make())
    mutate(b)
    with pytest.raises(bundle.BundleError, match=msg.replace("[", r"\[").replace("]", r"\]")):
        bundle.validate_bundle(b)


def test_expected_features_and_version_checked():
    b = _make()
    with pytest.raises(bundle.BundleError, match="featureNames"):
        bundle.validate_bundle(b, expected_feature_names=NAMES[::-1])
    with pytest.raises(bundle.BundleError, match="featureSetVersion"):
        bundle.validate_bundle(b, expected_feature_set_version=2)


def test_nan_is_never_written():
    b = _make()
    b["classPriors"][0] = float("nan")
    with pytest.raises(ValueError, match="non-finite"):
        dumps_pretty(b)


def test_stage_log_bias_round_trip(tmp_path):
    b = _make()
    biased = bundle.with_stage_log_bias(b, [0.25, -0.5, 0.125, 0.0])
    assert biased["stageLogBias"] == [0.25, -0.5, 0.125, 0.0] and "stageLogBias" not in b
    p = bundle.write_bundle(tmp_path / "b.json", biased)
    assert bundle.read_bundle(p) == biased
    assert bundle.with_stage_log_bias(biased, None) == b
