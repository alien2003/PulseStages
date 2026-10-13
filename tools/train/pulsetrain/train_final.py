"""Final model on ALL subjects with the configuration chosen by the LOSO selection (report.select_defaults).

Writes ``<run>/final/hybrid_model.json`` (copied to the app assets only when ``install`` is given, PART B) and
``<run>/trained_defaults.json``: an AlgoParams JSON patch with the default estimator, the hybrid decoder params
(most frequent inner-CV choice) and, if the trained HMM beat the physiology table and passed the validity gate
(report.hmm_validity), the HMM emissions / meanBouts / transitions / time-dependent priors (MLE on all subjects of
the winning pool, loso.final_hmm_estimates). LR's C is the most frequent inner-CV choice as well.

A resubstitution evaluation of the final bundle (``final/resubstitution-metrics.json``) checks that algo-cli
accepts it (no HYBRID_MODEL_UNAVAILABLE fallback); its numbers are NOT a validation result.
"""

from __future__ import annotations

import logging
import shutil
from collections import Counter
from pathlib import Path
from typing import Mapping, Sequence

from . import report
from .bundle import write_bundle
from .loso import CONFIGS, Context, final_hmm_estimates, fit_bundle, hmm_patch
from .util import canonical_json, deep_merge, write_json

log = logging.getLogger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[3]
APP_MODEL_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "models" / "hybrid_model.json"


def mode_C(fold_selections: Sequence[Mapping], config: str, classifier: str,
           variants: Sequence[str] = report.HEADLINE_VARIANTS) -> float | None:
    cs = [f["C"] for f in fold_selections if f["config"] == config and f["classifier"] == classifier
          and f["variant"] in variants and f.get("C") is not None]
    if not cs:
        return None
    cnt = Counter(cs)
    return sorted(cnt, key=lambda c: (-cnt[c], c))[0]


def _all_subjects(ctx: Context, datasets: Sequence[str], variants: Sequence[str]) -> list[str]:
    s: set[str] = set()
    for v in variants:
        s |= set(ctx.index.subjects(datasets, v))
    return sorted(s)


def train_final(ctx: Context, selection: Mapping, fold_selections: Sequence[Mapping],
                install: Path | None = None, smoke: bool = True, hmm_estimates: Mapping | None = None) -> dict:
    """Final bundle + trained_defaults.json; ``hmm_estimates`` = :func:`loso.final_hmm_estimates` of this run
    (computed here when not given)."""
    cfg = ctx.cfg
    out = cfg.out_dir / "final"
    fv = tuple(cfg.final_variants)
    result: dict = {"variants": list(fv)}
    defaults: dict = {}
    if selection.get("defaultEstimator"):
        defaults["estimator"] = selection["defaultEstimator"]
    dp = selection.get("decoderParams")
    if dp:
        defaults["hybrid"] = dict(dp["params"])
    cls = selection.get("classifier")
    if cls:
        clf, config = cls["chosen"], cls["config"]
        tc = CONFIGS[config]
        subjects = _all_subjects(ctx, tc.datasets, fv)
        C = mode_C(fold_selections, config, clf) if clf == "lr" else None
        if clf == "lr" and C is None:
            C = cfg.lr_Cs[len(cfg.lr_Cs) // 2]
        notes = [f"final model, run {cfg.run}: all {len(subjects)} subjects of {tc.pool}",
                 f"training configuration {config}: {tc.description}",
                 f"validated by LOSO (tools/train report); decoder defaults {canonical_json(defaults.get('hybrid', {}))}"]
        b, info = fit_bundle(ctx, subjects=subjects, label_variant=fv[0], train_variants=fv, policy=tc.step_policy,
                             spec=cfg.classifier_spec(clf, C), model_id=f"pulsestages-{tc.pool}-{clf}-{cfg.run}",
                             notes=notes)
        path = write_bundle(out / "hybrid_model.json", b)
        result.update({"bundle": str(path), "classifier": clf, "config": config, "C": C, "subjects": len(subjects),
                       **{k: info[k] for k in ("rows", "masked", "exportMaxAbsDiff")}})
        if smoke:
            pf = write_json(out / "final-params.json", deep_merge(cfg.base_params, {**defaults, "estimator": "HYBRID"}))
            m = ctx.cli.evaluate(cfg.nights_root / fv[0], "HYBRID", out / "resubstitution-metrics.json", params=pf,
                                 model=path)
            recs = report.parse_metrics(m)
            report.check_estimator_used(recs, "HYBRID")
            result["resubstitutionNights"] = sum(1 for r in recs if r["estimated"])
        if install is not None:
            install = Path(install)
            install.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, install)
            result["installed"] = str(install)
            log.info("installed the final model to %s", install)
    h = selection.get("hmm")
    if h and h.get("useTrained") and h.get("trainedPool"):
        est = (hmm_estimates or final_hmm_estimates(ctx))[h["trainedPool"]]
        defaults.update(hmm_patch(est["hmm"]))
        result["hmm"] = {"pool": h["trainedPool"], "subjects": est["subjects"], "notes": est["notes"]}
    write_json(cfg.out_dir / "trained_defaults.json", defaults)
    result["trainedDefaults"] = str(cfg.out_dir / "trained_defaults.json")
    return result
