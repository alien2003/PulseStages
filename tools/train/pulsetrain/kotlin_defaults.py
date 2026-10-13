"""Kotlin source of the trained AlgoParams defaults (``core-algo/.../params/TrainedDefaults.kt``).

``train.py defaults --run <run> [--write]`` renders ``<run>/trained_defaults.json`` (written by
:func:`pulsetrain.train_final.train_final`) together with the run's selection summary into a generated Kotlin
``object TrainedDefaults``. ``AlgoParams`` takes its estimator, hybrid decoder and HMM defaults from that object:

- ``ESTIMATOR``, ``HYBRID_DURATION_MODEL``, ``HYBRID_EMISSION_WEIGHT``, ``HYBRID_TIME_PRIOR_WEIGHT``: the values
  chosen by the LOSO selection rule (``report.select_defaults``);
- ``HYBRID_PRIOR_EXPONENT``: ``hybrid.priorExponent`` of the patch when a run chose it (the pre-registered
  stage-proportion experiment, :mod:`pulsetrain.bias`; ``selection.priorExponent.note`` explains the choice),
  else 1.0;
- ``HMM_EMISSIONS``, ``HMM_MEAN_BOUTS``, ``HMM_TRANSITIONS``, ``HMM_TIME_PRIOR``: the supervised MLE estimates when
  the trained HMM scored higher than the physiology table and passed the validity gate (``report.hmm_validity``),
  otherwise references to the specification's defaults (``EmissionTable.PHYSIOLOGY``, ``MeanBouts()``,
  ``TransitionWeights()``, ``HmmTimePrior()``), which stay available in either case. A trained patch without
  time-prior fields (runs before the time prior was estimated) keeps ``HmmTimePrior()``.

Numbers are written with ``repr`` (shortest round-trip form), so the generated defaults equal the JSON exactly.
"""

from __future__ import annotations

import math
import re
from pathlib import Path
from typing import Any, Mapping

from .stats import EMISSION_DIMS, TIME_PRIOR_KEYS, TRANSITION_KEYS, mean_bout_key
from .util import N_STAGES

REPO_ROOT = Path(__file__).resolve().parents[3]
KOTLIN_PATH = (REPO_ROOT / "core-algo" / "src" / "main" / "kotlin" / "io" / "github" / "alien2003" / "pulsestages"
               / "algo" / "params" / "TrainedDefaults.kt")
ESTIMATORS = ("RULES", "HMM", "HYBRID")
DURATION_MODELS = ("EXPLICIT", "GEOMETRIC")
STATE_ORDER = ("awake", "light", "deep", "rem")       # EmissionTable constructor order


class DefaultsError(ValueError):
    pass


def _num(v: Any, what: str) -> str:
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(float(v)):
        raise DefaultsError(f"{what} must be a finite number, got {v!r}")
    return repr(float(v))


def _fmt(v: Any, nd: int = 3) -> str:
    return "n/a" if v is None or (isinstance(v, float) and not math.isfinite(v)) else f"{v:.{nd}f}"


def _kdoc(text: str, indent: str = "", width: int = 116) -> list[str]:
    """Wraps ``text`` into KDoc lines (``/** ... */``)."""
    words, lines, cur = re.findall(r"`[^`]*`[^\s`]*|[^\s`]+", text), [], ""   # `code spans` stay on one line
    for w in words:
        if cur and len(indent) + 3 + len(cur) + 1 + len(w) > width:
            lines.append(cur)
            cur = w
        else:
            cur = f"{cur} {w}" if cur else w
    if cur:
        lines.append(cur)
    if len(lines) == 1 and len(indent) + 7 + len(lines[0]) <= width:
        return [f"{indent}/** {lines[0]} */"]
    return [f"{indent}/**"] + [f"{indent} * {ln}" for ln in lines] + [f"{indent} */"]


def _emission_table(em: Mapping) -> list[str]:
    out = ["        EmissionTable("]
    for state in STATE_ORDER:
        if state not in em:
            raise DefaultsError(f"hmm.emissions lacks {state}")
        dims = []
        for d in EMISSION_DIMS:
            g = em[state][d]
            sd = _num(g["sd"], f"{state}.{d}.sd")
            if not float(g["sd"]) > 0:
                raise DefaultsError(f"{state}.{d}.sd must be > 0")
            dims.append(f"{d} = Gaussian(mean = {_num(g['mean'], f'{state}.{d}.mean')}, sd = {sd})")
        out.append(f"            {state} =")
        out.append("                StateEmission(")
        out += [f"                    {x}," for x in dims]
        out.append("                ),")
    out.append("        )")
    return out


def render(defaults: Mapping, selection: Mapping, run: str, created_at: str, dataset: str) -> str:
    """Kotlin source of ``TrainedDefaults`` for the AlgoParams patch ``defaults`` (trained_defaults.json), the run's
    ``selection`` (summary.json), the run name, its creation time and the training dataset(s) of the final model."""
    est = defaults.get("estimator")
    if est not in ESTIMATORS:
        raise DefaultsError(f"estimator must be one of {ESTIMATORS}, got {est!r}")
    hyb = defaults.get("hybrid") or {}
    for k in ("durationModel", "emissionWeight", "timePriorWeight"):
        if k not in hyb:
            raise DefaultsError(f"hybrid.{k} missing in trained defaults")
    if hyb["durationModel"] not in DURATION_MODELS:
        raise DefaultsError(f"hybrid.durationModel must be one of {DURATION_MODELS}")
    sel = selection
    date = str(created_at)[:10]
    scores = ", ".join(f"{k} {_fmt(v)}" for k, v in sel.get("estimatorScores", {}).items())
    dp = sel.get("decoderParams") or {}
    hmm_sel = sel.get("hmm") or {}
    trained_hmm = "hmm" in defaults
    if trained_hmm != bool(hmm_sel.get("useTrained", trained_hmm)):
        raise DefaultsError("trained_defaults.json and the selection disagree on the trained HMM")
    L: list[str] = [
        f"// Generated by tools/train/train.py defaults --run {run} from",
        f"// {dataset}, {date}; do not edit by hand.",
        "package io.github.alien2003.pulsestages.algo.params",
        "",
        "import io.github.alien2003.pulsestages.algo.model.EstimatorType",
        "",
    ]
    L += _kdoc(
        f"AlgoParams defaults chosen by the leave-one-subject-out validation of tools/train (run `{run}`, {date}): "
        "the rule is the best mean per-subject Cohen's kappa on the held-out Walch (PSG) subjects, taking the worse "
        "of the two OHealth-like 2-minute variants (instantaneous / mean2min); see ALGORITHM.md. Regenerate with "
        f"`mise exec -- python -I tools/train/train.py defaults --run <run> --write`, never edit by hand.")
    L.append("object TrainedDefaults {")
    L += _kdoc(f"Default estimator (selection scores: {scores}).", "    ")
    L.append(f"    val ESTIMATOR: EstimatorType = EstimatorType.{est}")
    L.append("")
    sels = (f"{dp.get('selections')} of {dp.get('selectionsTotal')} distinct inner selections, "
            if dp and dp.get("selectionsTotal") else "")
    method = (f"most frequent inner-CV choice: {sels}{dp.get('count')} of {dp.get('folds')} outer folds of "
              f"{sel.get('classifier', {}).get('chosen', '?').upper()} [{sel.get('classifier', {}).get('config', '?')}]"
              if dp else "selection summary not available")
    L += _kdoc(f"Hybrid decoder: duration model ({method}).", "    ")
    L.append(f"    val HYBRID_DURATION_MODEL: DurationModel = DurationModel.{hyb['durationModel']}")
    L.append("")
    L += _kdoc("Hybrid decoder: exponent of the scaled likelihoods.", "    ")
    L.append(f"    const val HYBRID_EMISSION_WEIGHT: Double = {_num(hyb['emissionWeight'], 'emissionWeight')}")
    L.append("")
    L += _kdoc("Hybrid decoder: weight of the time-of-night prior log P(stage | 30-min bin) / P(stage).", "    ")
    L.append(f"    const val HYBRID_TIME_PRIOR_WEIGHT: Double = {_num(hyb['timePriorWeight'], 'timePriorWeight')}")
    L.append("")
    if "priorExponent" in hyb:
        beta = _num(hyb["priorExponent"], "priorExponent")
        if not float(hyb["priorExponent"]) >= 0:
            raise DefaultsError("hybrid.priorExponent must be >= 0")
        why = str((sel.get("priorExponent") or {}).get("note") or f"chosen in run {run}")
    else:
        beta, why = "1.0", "not part of this run's selection: 1 = divide by the class priors, the pre-training value"
    L += _kdoc(f"Hybrid decoder: exponent beta of the class priors in the scaled likelihood p(s | x) / prior_s^beta "
               f"({why}).", "    ")
    L.append(f"    const val HYBRID_PRIOR_EXPONENT: Double = {beta}")
    L.append("")
    if trained_hmm:
        h = defaults["hmm"]
        pool = hmm_sel.get("trainedPool", "?")
        L += _kdoc(f"HMM emissions: supervised MLE on {pool} (kappa {_fmt(hmm_sel.get('trained'))} vs the physiology "
                   f"table {_fmt(hmm_sel.get('table'))}); the table stays available as EmissionTable.PHYSIOLOGY.",
                   "    ")
        L.append("    val HMM_EMISSIONS: EmissionTable =")
        L += _emission_table(h["emissions"])
        L.append("")
        L += _kdoc("HMM mean bout lengths (minutes): mean uncensored bout length of the training nights.", "    ")
        L.append("    val HMM_MEAN_BOUTS: MeanBouts =")
        L.append("        MeanBouts(")
        for s in range(N_STAGES):
            k = mean_bout_key(s)
            L.append(f"            {k} = {_num(h['meanBouts'][k], k)},")
        L.append("        )")
        L.append("")
        L += _kdoc("HMM transition shares: off-diagonal bout-transition frequencies of the training nights.", "    ")
        L.append("    val HMM_TRANSITIONS: TransitionWeights =")
        L.append("        TransitionWeights(")
        for k in TRANSITION_KEYS:
            L.append(f"            {k} = {_num(h['transitions'][k], k)},")
        L.append("        )")
        L.append("")
        tp = {k: h[k] for k in TIME_PRIOR_KEYS if k in h}
        if tp:
            if set(tp) != set(TIME_PRIOR_KEYS):
                raise DefaultsError(f"hmm time prior incomplete: missing {sorted(set(TIME_PRIOR_KEYS) - set(tp))}")
            L += _kdoc("HMM time-dependent priors and initial distribution: conditional MLE of the epoch transitions "
                       "of the training nights (REM lockout length kept at the specification's value).", "    ")
            L.append("    val HMM_TIME_PRIOR: HmmTimePrior =")
            L.append("        HmmTimePrior(")
            for k in TIME_PRIOR_KEYS:
                L.append(f"            {k} = {_num(tp[k], k)},")
            L.append("        )")
        else:
            L += _kdoc("HMM time-dependent priors: not estimated in this run, the specification's values "
                       "(HmmTimePrior()).", "    ")
            L.append("    val HMM_TIME_PRIOR: HmmTimePrior = HmmTimePrior()")
    else:
        if hmm_sel and hmm_sel.get("betterKappa") and hmm_sel.get("validity"):
            problems = "; ".join(hmm_sel["validity"].get("problems") or ["unknown"])
            why = (f"the supervised estimates scored {_fmt(hmm_sel.get('trained'))} vs {_fmt(hmm_sel.get('table'))} "
                   f"for the physiology table but failed the validity gate ({problems}), so the table stays the "
                   "default")
        elif hmm_sel:
            why = (f"the supervised estimates scored {_fmt(hmm_sel.get('trained'))} vs {_fmt(hmm_sel.get('table'))} "
                   "for the physiology table, so the table stays the default")
        else:
            why = "the physiology table"
        L += _kdoc(f"HMM emissions: {why}.", "    ")
        L.append("    val HMM_EMISSIONS: EmissionTable = EmissionTable.PHYSIOLOGY")
        L.append("")
        L += _kdoc("HMM mean bout lengths: the specification's defaults (MeanBouts()).", "    ")
        L.append("    val HMM_MEAN_BOUTS: MeanBouts = MeanBouts()")
        L.append("")
        L += _kdoc("HMM transition shares: the specification's defaults (TransitionWeights()).", "    ")
        L.append("    val HMM_TRANSITIONS: TransitionWeights = TransitionWeights()")
        L.append("")
        L += _kdoc("HMM time-dependent priors and initial distribution: the specification's defaults "
                   "(HmmTimePrior()).", "    ")
        L.append("    val HMM_TIME_PRIOR: HmmTimePrior = HmmTimePrior()")
    L.append("}")
    return "\n".join(L) + "\n"


def render_run(run_dir: Path) -> str:
    """:func:`render` for the run directory ``run_dir`` (trained_defaults.json, summary.json, final bundle)."""
    from .util import read_json

    run_dir = Path(run_dir)
    defaults = read_json(run_dir / "trained_defaults.json")
    summary = read_json(run_dir / "summary.json")
    bundle = run_dir / "final" / "hybrid_model.json"
    dataset = read_json(bundle)["training"]["dataset"] if bundle.is_file() else "tools/train/DATASETS.md datasets"
    meta = summary.get("meta", {})
    return render(defaults, summary.get("selection", {}), meta.get("run", run_dir.name), meta.get("createdAt", "?"),
                  dataset)
