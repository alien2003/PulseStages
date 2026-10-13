from pathlib import Path

import numpy as np
import pytest

from pulsetrain import loso
from pulsetrain.features import FeatureTable
from pulsetrain.loso import Fold, inner_folds, make_folds, selection_sets
from pulsetrain.nightset import NightIndex, NightRef

WALCH = [str(1000 + i) for i in range(10)]
BID = [f"bid-{i:02d}" for i in range(7)]
POOL = WALCH + BID


def ds(s):
    return "bidsleep" if s.startswith("bid-") else "walch"


def test_make_folds_never_leaks():
    folds = make_folds(WALCH, WALCH, BID)
    assert len(folds) == 11 and folds[-1] == Fold("external", tuple(sorted(BID)), tuple(sorted(WALCH)), "external")
    for f in folds[:-1]:
        assert f.test == (f.name,) and f.name not in f.train and set(f.train) | {f.name} == set(WALCH)
    with pytest.raises(ValueError):
        make_folds(WALCH, ["x"])
    with pytest.raises(ValueError):
        make_folds(WALCH, WALCH, WALCH[:1])
    assert [f.name for f in make_folds(POOL, WALCH)] == sorted(WALCH)              # held-out restricted


def test_inner_folds_grouped_partition_stratified_deterministic():
    f = inner_folds(POOL, 5, seed=0, strata=ds)
    tests = [set(te) for _, te in f]
    assert len(f) == 5 and set().union(*tests) == set(POOL) and sum(map(len, tests)) == len(POOL)
    for tr, te in f:
        assert not set(tr) & set(te) and set(tr) | set(te) == set(POOL)
        assert sum(ds(s) == "walch" for s in te) == 2                             # 10 Walch over 5 folds
        assert 1 <= sum(ds(s) == "bidsleep" for s in te) <= 2
    assert f == inner_folds(list(reversed(POOL)), 5, seed=0, strata=ds)            # order-independent
    assert f != inner_folds(POOL, 5, seed=1, strata=ds)
    assert len(inner_folds(WALCH[:3], 5, 0)) == 3
    with pytest.raises(ValueError):
        inner_folds(["a"], 5, 0)


@pytest.mark.parametrize("mode", ["per-subject", "per-group"])
def test_selection_sets_exclude_the_held_out_subject(mode):
    folds = make_folds(WALCH, WALCH, BID)
    ss = selection_sets(folds, WALCH, mode, 5, 0, ds)
    for f in folds:
        assert not set(ss[f.name]) & set(f.test) and set(ss[f.name]) <= set(f.train)
    assert ss["external"] == tuple(sorted(WALCH))
    if mode == "per-group":
        assert len({ss[f.name] for f in folds[:-1]}) == 5 and all(len(ss[f.name]) == 8 for f in folds[:-1])
    else:
        assert all(len(ss[f.name]) == 9 for f in folds[:-1])
    with pytest.raises(ValueError):
        selection_sets(folds, WALCH, "nested", 5, 0, ds)


def test_variants_and_grid():
    assert loso.twin_of("instantaneous") == "mean2min" and loso.twin_of("mean2min-psg") == "instantaneous-psg"
    assert loso.twin_of("other") is None
    assert loso.train_variants_for("mean2min", "pair", loso.ALL_VARIANTS) == ("mean2min", "instantaneous")
    assert loso.train_variants_for("mean2min", "pair", ["mean2min"]) == ("mean2min",)
    assert loso.train_variants_for("mean2min", "same", loso.ALL_VARIANTS) == ("mean2min",)
    full = loso.grid_points(loso.GRIDS["full"], {"post": {"minDeepMin": 4.0}})
    assert len(full) == 32 and len({p["id"] for p in full}) == 32
    assert full[0]["hybrid"] == {"emissionWeight": 1.0, "timePriorWeight": 1.0, "durationModel": "EXPLICIT"}
    assert full[5]["params"] == {"post": {"minDeepMin": 4.0}, "estimator": "HYBRID", "hybrid": full[5]["hybrid"]}
    hy = {(p["hybrid"]["emissionWeight"], p["hybrid"]["timePriorWeight"], p["hybrid"]["durationModel"])
          for p in full}
    assert hy == {(e, t, d) for e in (0.3, 0.5, 0.7, 1.0) for t in (0.0, 0.5, 1.0, 2.0)
                  for d in ("EXPLICIT", "GEOMETRIC")}
    assert len(loso.grid_points(loso.GRIDS["small"])) == 8


def test_pipeline_config_round_trip(tmp_path):
    cfg = loso.PipelineConfig(run="r", out_dir=tmp_path, nights_root=tmp_path / "n", heldout_datasets=("walch",),
                              install_model=tmp_path / "m.json")
    back = loso.PipelineConfig.from_json(cfg.to_json())
    assert back == cfg


# ------------------------------------------------------------------------------- training isolation ----

def _ctx(tmp_path, policy_steps=True):
    rng = np.random.default_rng(0)
    subjects = ["a", "b", "c", "bid-x"]
    rows = []
    for s in subjects:
        for e in range(240):
            y = (e // 15) % 4
            rows.append((f"{s}-n", s, e, y))
    n = len(rows)
    y = np.array([r[3] for r in rows], dtype=np.int8)
    X = rng.normal(size=(n, 3)) + y[:, None]
    X[:, 1] = rng.integers(0, 5, size=n)                                         # "stepsX" feature
    subj = np.array([r[1] for r in rows], dtype=object)
    X[subj == "c", 0] = 1e6                                                       # poisoned held-out subject
    base = np.column_stack([X[:, 0], np.abs(X[:, 2]), X[:, 2], np.zeros(n), np.full(n, np.nan), np.zeros(n)])
    t = FeatureTable(night_id=np.array([r[0] for r in rows], dtype=object), subject=subj,
                     epoch=np.array([r[2] for r in rows]), start_ms=np.array([r[2] * 60_000 for r in rows]),
                     has_data=np.ones(n, bool), y=y, X=X, base=base, feature_names=["f0", "stepsX", "f2"],
                     feature_set_version=1, epoch_min=1.0, variant=np.full(n, "v", dtype=object))
    refs = [NightRef(f"{s}-n", s, ds(s), "v", Path("/dev/null"), not s.startswith("bid-")) for s in subjects]
    cfg = loso.PipelineConfig(run="r", out_dir=tmp_path, nights_root=tmp_path,
                              stats=loso.LabelStatsParams(time_prior_min_count=10))
    return loso.Context(cfg=cfg, index=NightIndex(refs), tables={"v": t}, feature_names=t.feature_names,
                        feature_set_version=1, step_idx=(1,), cli=None, has_steps={r.id: r.has_steps for r in refs})


def test_fold_fit_uses_training_subjects_only(tmp_path):
    ctx = _ctx(tmp_path)
    train = ("a", "b", "bid-x")
    b, info = loso.fit_bundle(ctx, subjects=train, label_variant="v", train_variants=("v",), policy="none",
                              spec=ctx.cfg.classifier_spec("lr", 1.0), model_id="m")
    t = ctx.tables["v"]
    m = np.isin(t.subject, list(train))
    assert b["standardization"]["mean"][0] == pytest.approx(t.X[m, 0].mean())    # not the poisoned 1e6
    assert b["standardization"]["mean"][0] < 10
    np.testing.assert_allclose(b["classPriors"], np.bincount(t.y[m], minlength=4) / m.sum())
    assert b["training"]["subjects"] == 3 and b["training"]["nights"] == 3
    assert info["rows"] == m.sum() and info["exportMaxAbsDiff"] <= 1e-9


def test_step_policies(tmp_path):
    ctx = _ctx(tmp_path)
    subs = ("a", "bid-x")
    t = ctx.tables["v"]
    m = t.subjects_mask(subs) & t.training_rows()
    for policy in ("none", "zero"):                         # zero: the algo-cli features exactly as they are
        d = loso.training_data(ctx, subs, ("v",), policy)
        assert d.masked == () and np.isfinite(d.X).all()
        np.testing.assert_array_equal(d.X, t.X[m])
    mask = loso.training_data(ctx, subs, ("v",), "mask")
    assert mask.masked == (1,)
    np.testing.assert_array_equal(mask.X, t.X[m])          # masking happens in the standardization only
    bm, _ = loso.fit_bundle(ctx, subjects=subs, label_variant="v", train_variants=("v",), policy="mask",
                            spec=ctx.cfg.classifier_spec("lr"), model_id="m")
    assert bm["standardization"]["std"][1] == 1e12 and "masked features" in bm["training"]["notes"]
    for bad in ("nonstep", "drop"):                         # nonstep is retired (train/inference mismatch)
        with pytest.raises(ValueError, match="unknown step policy"):
            loso.training_data(ctx, subs, ("v",), bad)
    assert "nonstep" not in {c.step_policy for c in loso.CONFIGS.values()}
    assert loso.config_info("walch+bid-nonstep").description.startswith("RETIRED")


@pytest.mark.parametrize("config", sorted(loso.CONFIGS))
@pytest.mark.parametrize("kind", ["lr", "mlp"])
def test_training_inputs_equal_inference_inputs(tmp_path, config, kind):
    """What the classifier is trained on must be what the bundle computes at inference (algo-cli / the app) from
    the features of the same epochs; masked columns are exempt because their first-layer weights are 0. The retired
    `nonstep` policy broke this: BIDSleep step features were missing (z = 0) in training but 0 steps (z < 0) at
    inference, which the MLP learned as a Walch-vs-BIDSleep switch."""
    ctx = _ctx(tmp_path)
    t = ctx.tables["v"]
    t.X[t.subject == "bid-x", 1] = 0.0                      # a night without step records: algo-cli writes 0 steps
    policy = loso.CONFIGS[config].step_policy
    subs = ("a", "b", "bid-x")
    data = loso.training_data(ctx, subs, ("v",), policy)
    clf = loso.fit_classifier(data.X, data.y, ctx.cfg.classifier_spec(kind, 1.0)
                              if kind == "lr" else loso.ClassifierSpec(kind="mlp", mlp_max_iter=20), data.masked)
    b, _ = loso.fit_bundle(ctx, subjects=subs, label_variant="v", train_variants=("v",), policy=policy,
                           spec=clf.spec, model_id="m", data=data, classifier=clf)
    rows = t.subjects_mask(subs) & t.training_rows()
    mean, std = np.array(b["standardization"]["mean"]), np.array(b["standardization"]["std"])
    with np.errstate(invalid="ignore"):
        infer_z = (t.X[rows] - mean) / std                  # core-algo inference on the algo-cli features
    infer_z[~np.isfinite(infer_z)] = 0.0
    train_z = clf.standardizer.transform_train(data.X)
    keep = [i for i in range(t.X.shape[1]) if i not in data.masked]
    np.testing.assert_allclose(train_z[:, keep], infer_z[:, keep], rtol=0, atol=1e-12)
    w1 = np.array(b["classifier"]["layers"][0]["weights"])
    assert np.all(w1[:, list(data.masked)] == 0)


def test_parity_guard_rejects_the_retired_nonstep_imputation():
    X = np.column_stack([np.arange(6.0), np.zeros(6), np.arange(6.0) ** 2])   # column 1 = 0 steps (algo-cli)
    imputed = X.copy()
    imputed[3:, 1] = np.nan                                  # nonstep: "missing" for the nights without a channel
    with pytest.raises(loso.TrainInferenceMismatch, match=r"columns \[1\]"):
        loso.check_train_inference_parity(imputed, X)
    loso.check_train_inference_parity(imputed, X, masked=(1,))   # masked columns are never used
    loso.check_train_inference_parity(X, X.copy())
    nan = X.copy()
    nan[0, 0] = np.nan
    loso.check_train_inference_parity(nan, nan.copy())           # NaN where algo-cli wrote NaN is fine
    with pytest.raises(loso.TrainInferenceMismatch, match="shape"):
        loso.check_train_inference_parity(X[:, :2], X)
