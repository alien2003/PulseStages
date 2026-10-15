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
    assert list(b) == [k for k in bundle.TOP_LEVEL_KEYS if k not in (*bundle.STAGE_BIAS_KEYS, "nap")]   # optional
    biased = bundle.with_stage_log_bias_short(bundle.with_stage_log_bias(b, [0.1, 0.0, -0.2, 0.1]), [0.0, 0.2, 0.1, -0.3])
    full = bundle.with_nap_model(biased, bundle.nap_section(bundle.with_stage_log_bias(_make("lr"), [0.0, 0.1, 0.0, -0.1])))
    assert list(full) == list(bundle.TOP_LEVEL_KEYS)
    assert list(full["nap"]) == [k for k in bundle.NAP_KEYS if k != "stageLogBiasByLength"]
    # a nap section has a constant stageLogBias or a stageLogBiasByLength table, never both: the documented keys are
    # the union of the two forms
    table = bundle.length_bias_table([30, 60], [[0.0, 0.1, 0.0, -0.1], [0.1, 0.0, -0.1, 0.0]])
    tabled = bundle.with_nap_model(biased, bundle.nap_section(_make("lr"), length_bias=table))
    assert list(tabled["nap"]) == [k for k in bundle.NAP_KEYS if k != "stageLogBias"]
    assert _keys(full) | _keys(tabled) == bundle_doc_keys()
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
    (lambda b: b.update(stageLogBiasShort=[0.0, 0.1, 0.2]), "stageLogBiasShort"),
    (lambda b: b.update(stageLogBiasShort=[0.0, float("nan"), 0.0, 0.0]), "stageLogBiasShort"),
    (lambda b: b.update(nap=[]), "nap must be a JSON object"),
    (lambda b: b.update(nap={"featureNames": NAMES}), "nap: missing keys"),
    (lambda b: b.update(nap={**bundle.nap_section(_make()), "modelId": "x"}), "nap: unknown keys"),
    (lambda b: b.update(nap={**bundle.nap_section(_make()), "classPriors": [0.5, 0.5, 0.5, 0.5]}), "nap.classPriors"),
    (lambda b: b.update(nap={**bundle.nap_section(_make()), "stageLogBias": [0.0]}), "nap.stageLogBias"),
    (lambda b: b.update(nap={**bundle.nap_section(_make()), "featureNames": ["a", "a", "b", "c"]}), "nap.featureNames"),
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


def test_nap_section_round_trip_and_helpers(tmp_path):
    b = bundle.with_stage_log_bias_short(bundle.with_stage_log_bias(_make(), [0.1, 0.0, -0.2, 0.1]), [0.0, 0.2, 0.1, -0.3])
    nap_model = bundle.with_stage_log_bias(_make("mlp", hmm=False), [0.0, 0.3, 0.0, -0.3])
    nap = bundle.nap_section(nap_model, training={"notes": "nap model"})
    assert list(nap) == [k for k in bundle.NAP_KEYS if k != "stageLogBiasByLength"]   # absent: not written
    assert nap["classifier"] == nap_model["classifier"] and nap["stageLogBias"] == [0.0, 0.3, 0.0, -0.3]
    with_nap = bundle.with_nap_model(b, nap)
    assert list(with_nap)[-1] == "nap" and with_nap["stageLogBiasShort"] == b["stageLogBiasShort"]
    p = bundle.write_bundle(tmp_path / "nap.json", with_nap)
    assert bundle.read_bundle(p) == with_nap
    assert bundle.with_nap_model(with_nap, None) == b
    # replacing a bias keeps the nap section (and its position last)
    again = bundle.with_stage_log_bias(with_nap, [0.0, 0.0, 0.0, 0.0])
    assert list(again)[-1] == "nap" and again["nap"] == with_nap["nap"]
    assert list(bundle.with_stage_log_bias_short(with_nap, None))[-1] == "nap"
    without_bias = bundle.nap_section(_make())
    assert "stageLogBias" not in without_bias



# --------------------------------------------------- nap length table (docs/prereg/prereg-naps-realistic.md 6.4) ----

def test_nap_length_table_round_trip_interpolation_and_absence(tmp_path):
    anchors = [30, 60, 90, 120, 150, 170]
    rows = [[0.1 * i, -0.05 * i, 0.02 * i, -0.07 * i] for i in range(len(anchors))]
    table = bundle.length_bias_table(anchors, rows)
    sec = bundle.nap_section(_make("mlp", hmm=False), length_bias=table)
    assert list(sec)[-1] == "stageLogBiasByLength" and "stageLogBias" not in sec
    b = bundle.with_nap_model(_make(), sec)
    p = bundle.write_bundle(tmp_path / "t.json", b)
    assert bundle.read_bundle(p) == b and json.loads(p.read_text())["nap"]["stageLogBiasByLength"] == table
    assert bundle.length_bias_at(table, 60) == rows[1]                     # exactly an anchor's row
    assert bundle.length_bias_at(table, 10) == rows[0] and bundle.length_bias_at(table, 179) == rows[-1]
    np.testing.assert_allclose(bundle.length_bias_at(table, 75), [(a + c) / 2 for a, c in zip(rows[1], rows[2])])
    np.testing.assert_allclose(bundle.length_bias_at(table, 160),
                               [a + 0.5 * (c - a) for a, c in zip(rows[4], rows[5])])
    one = bundle.length_bias_table([45], [rows[3]])
    assert bundle.length_bias_at(one, 1) == bundle.length_bias_at(one, 500) == rows[3]
    # absent = not written; the level can be replaced in either direction
    plain = bundle.nap_section(_make("mlp", hmm=False))
    assert "stageLogBiasByLength" not in plain
    assert "stageLogBiasByLength" not in bundle.with_nap_model(_make(), plain)["nap"]
    scal = bundle.with_nap_level(sec, bias=[0.1, 0.0, -0.1, 0.0])
    assert scal["stageLogBias"] == [0.1, 0.0, -0.1, 0.0] and "stageLogBiasByLength" not in scal
    back = bundle.with_nap_level(scal, length_bias=table)
    assert back == sec and list(back) == list(sec)
    assert "stageLogBias" not in bundle.with_nap_level(scal)
    assert "stageLogBiasByLength" not in bundle.with_nap_level(sec)


@pytest.mark.parametrize("table,msg", [
    ({"lengthsMin": [], "bias": []}, "at least one"),
    ({"lengthsMin": [60, 30], "bias": [[0, 0, 0, 0]] * 2}, "strictly increasing"),
    ({"lengthsMin": [30, 30], "bias": [[0, 0, 0, 0]] * 2}, "strictly increasing"),
    ({"lengthsMin": [0, 30], "bias": [[0, 0, 0, 0]] * 2}, "positive and finite"),
    ({"lengthsMin": [30, float("inf")], "bias": [[0, 0, 0, 0]] * 2}, "positive and finite"),
    ({"lengthsMin": [30, 60], "bias": [[0, 0, 0, 0]]}, "one row per anchor"),
    ({"lengthsMin": [30], "bias": [[0, 0, 0]]}, r"bias\[0\] must be 4"),
    ({"lengthsMin": [30], "bias": [[0, 0, 0, float("nan")]]}, r"bias\[0\] must be 4"),
    ({"lengthsMin": [30]}, "exactly"),
])
def test_nap_length_table_validation(table, msg):
    b = bundle.with_nap_model(_make(), bundle.nap_section(_make("mlp", hmm=False)))
    b["nap"]["stageLogBiasByLength"] = table
    with pytest.raises(bundle.BundleError, match=msg):
        bundle.validate_bundle(b)


def test_nap_length_table_is_exclusive_with_the_constant_nap_bias():
    b = bundle.with_nap_model(_make(), bundle.nap_section(bundle.with_stage_log_bias(_make("lr"), [0.0] * 4)))
    b["nap"]["stageLogBiasByLength"] = bundle.length_bias_table([30], [[0.0] * 4])
    with pytest.raises(bundle.BundleError, match="mutually exclusive"):
        bundle.validate_bundle(b)
    with pytest.raises(bundle.BundleError, match="not both"):
        bundle.nap_section(bundle.with_stage_log_bias(_make("lr"), [0.0] * 4),
                           length_bias=bundle.length_bias_table([30], [[0.0] * 4]))
    with pytest.raises(bundle.BundleError, match="not both"):
        bundle.with_nap_level(b["nap"], bias=[0.0] * 4, length_bias=bundle.length_bias_table([30], [[0.0] * 4]))
