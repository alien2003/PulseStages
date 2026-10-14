"""Hybrid model bundle (docs/INTERCHANGE.md section 3): build, validate, read, write.

The ``hmm`` block holds the supervised HMM estimates in the AlgoParams JSON shape (EmissionTable / MeanBouts /
TransitionWeights field names of core-algo/.../params/AlgoParams.kt). ``stageLogBias`` (optional) is the per-stage
log-bias of the scaled likelihoods fitted by the proportion matching of :mod:`pulsetrain.bias`;
``stageLogBiasShort`` (optional) replaces it on naps, sessions shorter than AlgoParams ``napMode.maxSessionMin`` (the
nap experiment of :mod:`pulsetrain.naps`), when the bundle has no ``nap`` section. ``nap`` (optional) is a complete
second model for naps (:data:`NAP_KEYS`: classifier, standardization, feature list, class priors, durations, bout
transitions, initial stage, time prior, optional ``stageLogBias`` and ``training``), validated like the main model;
core-algo decodes naps with it instead of the main model (docs/INTERCHANGE.md section 3).
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any, Mapping, Sequence

import numpy as np

from .models import TrainedClassifier
from .stats import EMISSION_DIMS, TRANSITION_KEYS, LabelStats, mean_bout_key
from .util import N_STAGES, PARAM_STAGE_KEYS, STAGES, dumps_pretty, read_json, write_json

SCHEMA_VERSION = 1
CLASS_ORDER = list(STAGES)
TOP_LEVEL_KEYS = ("schemaVersion", "modelId", "featureSetVersion", "featureNames", "standardization", "classOrder",
                  "classifier", "classPriors", "durations", "boutTransitions", "initialStage", "timePrior", "hmm",
                  "training", "stageLogBias", "stageLogBiasShort", "nap")
OPTIONAL_KEYS = ("hmm", "stageLogBias", "stageLogBiasShort", "nap")
COMPONENT_KEYS = ("featureSetVersion", "featureNames", "standardization", "classifier", "classPriors", "durations",
                  "boutTransitions", "initialStage", "timePrior")
NAP_KEYS = (*COMPONENT_KEYS, "stageLogBias", "training")       # Kotlin NapSubModel, in declaration order
NAP_OPTIONAL_KEYS = ("stageLogBias", "training")
STAGE_BIAS_KEYS = ("stageLogBias", "stageLogBiasShort")
REQUIRED_KEYS = tuple(k for k in TOP_LEVEL_KEYS if k not in OPTIONAL_KEYS)
TRAINING_KEYS = ("dataset", "license", "subjects", "nights", "variants", "createdAt", "notes")
MEAN_BOUT_KEYS = tuple(mean_bout_key(s) for s in range(N_STAGES))


class BundleError(ValueError):
    pass


def hmm_block(emissions: Mapping, mean_bouts: Mapping, transitions: Mapping) -> dict:
    return {
        "emissions": {k: {d: {"mean": float(emissions[k][d]["mean"]), "sd": float(emissions[k][d]["sd"])}
                          for d in EMISSION_DIMS} for k in PARAM_STAGE_KEYS},
        "meanBouts": {k: float(mean_bouts[k]) for k in MEAN_BOUT_KEYS},
        "transitions": {k: float(transitions[k]) for k in TRANSITION_KEYS},
    }


def build_bundle(*, model_id: str, feature_set_version: int, feature_names: Sequence[str],
                 classifier: TrainedClassifier, label_stats: LabelStats, hmm: Mapping | None,
                 training: Mapping[str, Any]) -> dict:
    st = classifier.standardizer
    b: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "modelId": str(model_id),
        "featureSetVersion": int(feature_set_version),
        "featureNames": list(feature_names),
        "standardization": {"mean": [float(v) for v in st.mean], "std": [float(v) for v in st.std]},
        "classOrder": list(CLASS_ORDER),
        "classifier": {"type": classifier.type, "layers": classifier.layers_json()},
        "classPriors": [float(v) for v in classifier.class_priors],
        "durations": label_stats.durations_json(),
        "boutTransitions": np.asarray(label_stats.bout_transitions, float).tolist(),
        "initialStage": [float(v) for v in label_stats.initial],
        "timePrior": {"binMinutes": int(label_stats.bin_minutes) if float(label_stats.bin_minutes).is_integer()
                      else float(label_stats.bin_minutes),
                      "table": np.asarray(label_stats.time_prior, float).tolist()},
    }
    if hmm is not None:
        b["hmm"] = hmm_block(hmm["emissions"], hmm["meanBouts"], hmm["transitions"])
    b["training"] = {k: training[k] for k in TRAINING_KEYS}
    validate_bundle(b, expected_feature_names=feature_names)
    return b


def _is_num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def _prob_vector(v: Any, n: int, where: str, errs: list[str], tol: float = 1e-6) -> None:
    if not isinstance(v, list) or len(v) != n or not all(_is_num(x) for x in v):
        errs.append(f"{where}: expected {n} finite numbers")
        return
    if any(x < 0 for x in v) or abs(sum(v) - 1.0) > tol:
        errs.append(f"{where}: not a probability vector (sum {sum(v):.6g})")


def validate_bundle(b: Mapping, expected_feature_names: Sequence[str] | None = None,
                    expected_feature_set_version: int | None = None) -> None:
    """Raises BundleError listing every violation of the schema."""
    errs: list[str] = []
    if not isinstance(b, Mapping):
        raise BundleError("bundle must be a JSON object")
    missing = [k for k in REQUIRED_KEYS if k not in b]
    extra = [k for k in b if k not in TOP_LEVEL_KEYS]
    if missing:
        errs.append(f"missing keys {missing}")
    if extra:
        errs.append(f"unknown keys {extra}")
    if missing:
        raise BundleError("; ".join(errs))
    if b["schemaVersion"] != SCHEMA_VERSION:
        errs.append(f"schemaVersion must be {SCHEMA_VERSION}")
    if not isinstance(b["modelId"], str) or not b["modelId"]:
        errs.append("modelId must be a non-empty string")
    if b["classOrder"] != CLASS_ORDER:
        errs.append(f"classOrder must be {CLASS_ORDER}")
    _validate_components(b, "", errs, expected_feature_names, expected_feature_set_version)
    if "hmm" in b:
        _validate_hmm(b["hmm"], errs)
    for key in STAGE_BIAS_KEYS:
        if key in b:
            v = b[key]
            if not isinstance(v, list) or len(v) != N_STAGES or not all(_is_num(x) for x in v):
                errs.append(f"{key} must be {N_STAGES} finite numbers (AWAKE, LIGHT, DEEP, REM)")
    if "nap" in b:
        _validate_nap(b["nap"], errs)
    tr = b["training"]
    if not isinstance(tr, Mapping) or set(tr) != set(TRAINING_KEYS):
        errs.append(f"training must have exactly {list(TRAINING_KEYS)}")
    else:
        for k in ("dataset", "license", "createdAt", "notes"):
            if not isinstance(tr[k], str):
                errs.append(f"training.{k} must be a string")
        for k in ("subjects", "nights"):
            if not isinstance(tr[k], int) or isinstance(tr[k], bool) or tr[k] < 0:
                errs.append(f"training.{k} must be a non-negative integer")
        if not isinstance(tr["variants"], list) or not all(isinstance(v, str) for v in tr["variants"]):
            errs.append("training.variants must be a list of strings")
    if errs:
        raise BundleError("invalid hybrid model bundle: " + "; ".join(errs))


def _validate_components(b: Mapping, prefix: str, errs: list[str], expected_feature_names: Sequence[str] | None = None,
                         expected_feature_set_version: int | None = None) -> None:
    """The checks shared by the main model and the ``nap`` section (``prefix`` names the section in messages)."""
    if not isinstance(b["featureSetVersion"], int) or isinstance(b["featureSetVersion"], bool):
        errs.append(f"{prefix}featureSetVersion must be an integer")
    elif expected_feature_set_version is not None and b["featureSetVersion"] != expected_feature_set_version:
        errs.append(f"{prefix}featureSetVersion {b['featureSetVersion']} != expected {expected_feature_set_version}")
    names = b["featureNames"]
    nf = len(names) if isinstance(names, list) else -1
    if nf <= 0 or not all(isinstance(n, str) for n in names) or len(set(names)) != nf:
        errs.append(f"{prefix}featureNames must be a non-empty list of unique strings")
    elif expected_feature_names is not None and list(names) != list(expected_feature_names):
        errs.append(f"{prefix}featureNames differ from the expected feature list")
    std = b["standardization"]
    if not isinstance(std, Mapping) or set(std) != {"mean", "std"}:
        errs.append(f"{prefix}standardization must have exactly mean and std")
    else:
        for k in ("mean", "std"):
            v = std[k]
            if not isinstance(v, list) or len(v) != nf or not all(_is_num(x) for x in v):
                errs.append(f"{prefix}standardization.{k}: expected {nf} finite numbers")
        if isinstance(std["std"], list) and any(_is_num(x) and x <= 0 for x in std["std"]):
            errs.append(f"{prefix}standardization.std must be > 0")
    clf = b["classifier"]
    if not isinstance(clf, Mapping) or set(clf) != {"type", "layers"}:
        errs.append(f"{prefix}classifier must have exactly type and layers")
    else:
        layers = clf["layers"]
        if clf["type"] not in ("logreg", "mlp"):
            errs.append(f"{prefix}classifier.type must be logreg or mlp")
        if not isinstance(layers, list) or not layers:
            errs.append(f"{prefix}classifier.layers must be a non-empty list")
        else:
            if clf["type"] == "logreg" and len(layers) != 1:
                errs.append(f"{prefix}logreg must have exactly one layer")
            if clf["type"] == "mlp" and len(layers) < 2:
                errs.append(f"{prefix}mlp must have hidden layers")
            n_in = nf
            for i, layer in enumerate(layers):
                w = f"classifier.layers[{i}]"
                if not isinstance(layer, Mapping) or set(layer) != {"weights", "bias", "activation"}:
                    errs.append(f"{prefix}{w} must have exactly weights, bias, activation")
                    break
                W, bias = layer["weights"], layer["bias"]
                last = i == len(layers) - 1
                if layer["activation"] != ("softmax" if last else "relu"):
                    errs.append(f"{prefix}{w}.activation must be {'softmax' if last else 'relu'}")
                if (not isinstance(W, list) or not W or not all(isinstance(r, list) and len(r) == n_in
                                                                  and all(_is_num(x) for x in r) for r in W)):
                    errs.append(f"{prefix}{w}.weights must be out x {n_in} finite numbers")
                    break
                if not isinstance(bias, list) or len(bias) != len(W) or not all(_is_num(x) for x in bias):
                    errs.append(f"{prefix}{w}.bias must have {len(W)} finite numbers")
                n_in = len(W)
            else:
                if n_in != N_STAGES:
                    errs.append(f"{prefix}last layer must have {N_STAGES} outputs")
    _prob_vector(b["classPriors"], N_STAGES, f"{prefix}classPriors", errs)
    if isinstance(b["classPriors"], list) and any(_is_num(x) and x <= 0 for x in b["classPriors"]):
        errs.append(f"{prefix}classPriors must be > 0 (posteriors are divided by them)")
    dur = b["durations"]
    if not isinstance(dur, Mapping) or list(dur) != CLASS_ORDER:
        errs.append(f"{prefix}durations must have keys {CLASS_ORDER}")
    else:
        for s, g in dur.items():
            if (not isinstance(g, Mapping) or set(g) != {"shape", "scale"} or not all(_is_num(g[k]) and g[k] > 0
                                                                                      for k in g)):
                errs.append(f"{prefix}durations.{s} must be {{shape > 0, scale > 0}}")
    bt = b["boutTransitions"]
    if not isinstance(bt, list) or len(bt) != N_STAGES:
        errs.append(f"{prefix}boutTransitions must be 4x4")
    else:
        for i, row in enumerate(bt):
            _prob_vector(row, N_STAGES, f"{prefix}boutTransitions[{i}]", errs)
    _prob_vector(b["initialStage"], N_STAGES, f"{prefix}initialStage", errs)
    tp = b["timePrior"]
    if not isinstance(tp, Mapping) or set(tp) != {"binMinutes", "table"}:
        errs.append(f"{prefix}timePrior must have exactly binMinutes and table")
    else:
        if not _is_num(tp["binMinutes"]) or tp["binMinutes"] <= 0:
            errs.append(f"{prefix}timePrior.binMinutes must be > 0")
        if not isinstance(tp["table"], list) or not tp["table"]:
            errs.append(f"{prefix}timePrior.table must be a non-empty list")
        else:
            for i, row in enumerate(tp["table"]):
                _prob_vector(row, N_STAGES, f"{prefix}timePrior.table[{i}]", errs)


def _validate_nap(nap: Any, errs: list[str]) -> None:
    if not isinstance(nap, Mapping):
        errs.append("nap must be a JSON object")
        return
    missing = [k for k in NAP_KEYS if k not in nap and k not in NAP_OPTIONAL_KEYS]
    extra = [k for k in nap if k not in NAP_KEYS]
    if missing or extra:
        errs.append(f"nap: missing keys {missing}, unknown keys {extra}" if missing else f"nap: unknown keys {extra}")
        if missing:
            return
    _validate_components(nap, "nap.", errs)
    if "stageLogBias" in nap:
        v = nap["stageLogBias"]
        if not isinstance(v, list) or len(v) != N_STAGES or not all(_is_num(x) for x in v):
            errs.append(f"nap.stageLogBias must be {N_STAGES} finite numbers (AWAKE, LIGHT, DEEP, REM)")
    if "training" in nap and (not isinstance(nap["training"], Mapping) or not set(nap["training"]) <= set(TRAINING_KEYS)):
        errs.append(f"nap.training may only have {list(TRAINING_KEYS)}")


def _validate_hmm(h: Any, errs: list[str]) -> None:
    if not isinstance(h, Mapping) or set(h) != {"emissions", "meanBouts", "transitions"}:
        errs.append("hmm must have exactly emissions, meanBouts, transitions")
        return
    em = h["emissions"]
    if not isinstance(em, Mapping) or set(em) != set(PARAM_STAGE_KEYS):
        errs.append(f"hmm.emissions must have keys {list(PARAM_STAGE_KEYS)}")
    else:
        for k, st in em.items():
            if not isinstance(st, Mapping) or set(st) != set(EMISSION_DIMS):
                errs.append(f"hmm.emissions.{k} must have keys {list(EMISSION_DIMS)}")
                continue
            for d, g in st.items():
                if (not isinstance(g, Mapping) or set(g) != {"mean", "sd"} or not _is_num(g["mean"])
                        or not _is_num(g["sd"]) or g["sd"] <= 0):
                    errs.append(f"hmm.emissions.{k}.{d} must be {{mean, sd > 0}}")
    mb = h["meanBouts"]
    if not isinstance(mb, Mapping) or set(mb) != set(MEAN_BOUT_KEYS) or not all(_is_num(v) and v > 0
                                                                                   for v in mb.values()):
        errs.append(f"hmm.meanBouts must have positive {list(MEAN_BOUT_KEYS)}")
    tw = h["transitions"]
    if not isinstance(tw, Mapping) or set(tw) != set(TRANSITION_KEYS) or not all(_is_num(v) and v >= 0
                                                                                   for v in tw.values()):
        errs.append(f"hmm.transitions must have non-negative {list(TRANSITION_KEYS)}")


def with_stage_log_bias(bundle: Mapping, bias: Sequence[float] | None) -> dict:
    """A copy of ``bundle`` with ``stageLogBias`` set to ``bias`` (AWAKE, LIGHT, DEEP, REM), or removed for None.

    core-algo adds ``stageLogBias[s]`` to the emission score of every epoch with heart-rate data (docs/INTERCHANGE.md
    section 3); the key goes last, after ``training``, like the Kotlin field."""
    out = {k: v for k, v in bundle.items() if k not in (*STAGE_BIAS_KEYS, "nap")}
    if bias is not None:
        out["stageLogBias"] = [float(x) for x in bias]
    if "stageLogBiasShort" in bundle:
        out["stageLogBiasShort"] = bundle["stageLogBiasShort"]
    if "nap" in bundle:
        out["nap"] = bundle["nap"]
    validate_bundle(out)
    return out


def with_stage_log_bias_short(bundle: Mapping, bias: Sequence[float] | None) -> dict:
    """A copy of ``bundle`` with ``stageLogBiasShort`` set to ``bias`` (AWAKE, LIGHT, DEEP, REM), or removed for None.

    core-algo uses it instead of ``stageLogBias`` on naps, sessions shorter than ``napMode.maxSessionMin`` minutes,
    when the bundle has no ``nap`` section (docs/INTERCHANGE.md section 3); the key goes after ``stageLogBias``, like
    the Kotlin field."""
    out = {k: v for k, v in bundle.items() if k not in ("stageLogBiasShort", "nap")}
    if bias is not None:
        out["stageLogBiasShort"] = [float(x) for x in bias]
    if "nap" in bundle:
        out["nap"] = bundle["nap"]
    validate_bundle(out)
    return out


def nap_section(model: Mapping, training: Mapping | None = None) -> dict:
    """The ``nap`` section made of the components of the bundle ``model`` (a bundle trained on nap-length sessions,
    e.g. by :func:`build_bundle`): its classifier, standardization, feature list, class priors, durations, bout
    transitions, initial stage, time prior and ``stageLogBias`` (when it has one), plus ``training`` (default: the
    model's)."""
    validate_bundle(model)
    out = {k: model[k] for k in COMPONENT_KEYS}
    if "stageLogBias" in model:
        out["stageLogBias"] = model["stageLogBias"]
    out["training"] = dict(training if training is not None else model["training"])
    return out


def with_nap_model(bundle: Mapping, nap: Mapping | None) -> dict:
    """A copy of ``bundle`` with the ``nap`` section ``nap`` (see :func:`nap_section`), or without one for None. The
    key goes last, like the Kotlin field; the nap section enters the bundle fingerprint, a bundle without it keeps
    its fingerprint."""
    out = {k: v for k, v in bundle.items() if k != "nap"}
    if nap is not None:
        out["nap"] = {k: nap[k] for k in NAP_KEYS if k in nap}
    validate_bundle(out)
    return out


def write_bundle(path: Path, bundle: Mapping) -> Path:
    validate_bundle(bundle)
    return write_json(Path(path), bundle)


def read_bundle(path: Path) -> dict:
    b = read_json(Path(path))
    validate_bundle(b)
    return b


def bundle_text(bundle: Mapping) -> str:
    return dumps_pretty(bundle)
