import json

import numpy as np
import pytest

from pulsetrain import features
from pulsetrain.features import FeatureFileError, read_features

# real algo-cli layout: hybrid features (here hrNorm, stepsLog1p, mean_15, t), then the base features that are not
# hybrid features (hrVar, hrSlope, cyclePhase, steps)
HEADER = "night_id,subject,epoch,start_ms,has_data,label,hrNorm,stepsLog1p,mean_15,t,hrVar,hrSlope,cyclePhase,steps"
ROWS = [
    "n1,s1,0,1000,1,AWAKE,0.5,0.0,1.0,0.05,1.1,0.0,,0",
    "n1,s1,1,61000,true,LIGHT,0.6,0.7,,0.15,1.2,0.1,0.3,2",
    "n1,s1,2,121000,0,,,,,0.25,,,,0",
    "n1,s1,4,241000,False,REM,,,,0.45,,,,0",
    "n2,s2,0,0,1,DEEP,0.1,0.0,2.0,0.5,0.6,-0.1,0.1,0",
    "n2,s2,1,60000,1,UNKNOWN,0.2,0.0,2.0,0.9,0.6,-0.1,0.1,0",
]
REAL_HEADER_START = "night_id,subject,epoch,start_ms,has_data,label,hrNorm,hrVar,hrSlope,t,cycleSin"


def _write(path, rows=ROWS, header=HEADER, comments=()):
    path.write_text("".join(c + "\n" for c in comments) + header + "\n" + "\n".join(rows) + "\n")
    return path


def test_reads_hybrid_and_base_columns(tmp_path):
    t = read_features(_write(tmp_path / "f.csv"), variant="v")
    assert t.feature_names == ["hrNorm", "stepsLog1p", "mean_15", "t"]
    np.testing.assert_allclose(t.X[0], [0.5, 0.0, 1.0, 0.05])
    np.testing.assert_allclose(t.base_column("hrNorm")[:2], [0.5, 0.6])        # from the hybrid column
    np.testing.assert_allclose(t.base_column("hrVar")[:2], [1.1, 1.2])         # from the trailing block
    np.testing.assert_allclose(t.base_column("t")[:2], [0.05, 0.15])
    np.testing.assert_allclose(t.base_column("steps")[:2], [0, 2])
    assert np.isnan(t.X[1, 2]) and np.isnan(t.base_column("cyclePhase")[0])
    assert t.has_data.tolist() == [True, True, False, False, True, True]
    assert t.y.tolist() == [0, 1, -1, 3, 2, -1]
    assert t.training_rows().tolist() == [True, True, False, False, True, False]
    assert t.epoch_min == 1.0 and t.feature_set_version is None and set(t.variant) == {"v"}
    seq = t.label_sequences()
    assert seq["n1"].tolist() == [0, 1, -1, -1, 3] and seq["n2"].tolist() == [2, -1]
    assert t.for_subjects(["s2"]).night_id.tolist() == ["n2", "n2"]


def test_feature_set_version_sources(tmp_path):
    p = _write(tmp_path / "f.csv", comments=["# featureSetVersion=4", "# algoVersion: 2"])
    t = read_features(p)
    assert t.feature_set_version == 4 and t.meta["comments"]["algoVersion"] == "2" and len(t) == 6
    (tmp_path / "f.csv.meta.json").write_text(json.dumps({"featureSetVersion": 5}))
    assert read_features(p).feature_set_version == 5                          # sidecar before comment
    assert read_features(p, feature_set_version=9).feature_set_version == 9   # explicit wins
    (tmp_path / "f.csv.meta.json").write_text(json.dumps({"featureNames": ["x"]}))
    with pytest.raises(FeatureFileError, match="sidecar"):
        read_features(p)


def test_split_header_real_feature_set():
    names = ["hrNorm", "hrVar", "hrSlope", "t", "cycleSin", "relMean5", "stepsWin5Log1p", "hoursToEnd"]
    assert features.split_header(names + ["cyclePhase", "steps"]) == (names, ["cyclePhase", "steps"])
    with pytest.raises(FeatureFileError, match="duplicate"):            # the old layout repeating hrNorm .. t
        features.split_header(names + ["hrNorm", "hrVar", "hrSlope", "t", "cyclePhase", "steps"])
    with pytest.raises(FeatureFileError, match="base features"):
        features.split_header(names + ["steps"])


@pytest.mark.parametrize("header,msg", [
    (HEADER.replace("night_id,subject", "subject,night_id"), "first columns"),
    (HEADER.replace(",steps", ",stepsX"), "base features"),
    (HEADER.replace(",hrSlope,", ",slope,"), "base features"),
    (HEADER.replace("mean_15", "stepsLog1p"), "duplicate"),
])
def test_bad_headers(tmp_path, header, msg):
    with pytest.raises(FeatureFileError, match=msg):
        read_features(_write(tmp_path / "f.csv", header=header))


def test_bad_values(tmp_path):
    with pytest.raises(FeatureFileError, match="label"):
        read_features(_write(tmp_path / "a.csv", rows=[ROWS[0].replace("AWAKE", "N3")]))
    with pytest.raises(FeatureFileError, match="has_data"):
        read_features(_write(tmp_path / "b.csv", rows=[ROWS[0].replace(",1,AWAKE", ",maybe,AWAKE")]))
    with pytest.raises(FeatureFileError, match="not found"):
        read_features(tmp_path / "missing.csv")


def test_epoch_length_and_concat(tmp_path):
    rows = [r.replace(",61000,", ",31000,").replace(",121000,", ",61000,").replace(",241000,", ",121000,")
            .replace(",60000,", ",30000,") for r in ROWS]
    t30 = read_features(_write(tmp_path / "a.csv", rows=rows))
    assert t30.epoch_min == 0.5
    t = read_features(_write(tmp_path / "b.csv"), variant="x")
    u = read_features(_write(tmp_path / "c.csv"), variant="y")
    c = features.concat([t, u])
    assert len(c) == 12 and c.variant.tolist() == ["x"] * 6 + ["y"] * 6
    with pytest.raises(FeatureFileError, match="epoch"):
        features.concat([t, t30])


def test_step_feature_names():
    assert features.step_feature_names(["hrNorm", "stepsLog1p", "stepsSum5Log1p", "Steps"]) == [
        "stepsLog1p", "stepsSum5Log1p", "Steps"]
