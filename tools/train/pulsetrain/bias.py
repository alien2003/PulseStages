"""Pre-registered stage-proportion experiment (``docs/prereg/prereg-bias.md``, :data:`PREREG_SHA256`), post hoc
on a finished LOSO run.

The shipped HYBRID default over-calls DEEP and REM (scaled likelihoods p(s | x) / pi_s favour the rare stages). Two
decoder-side corrections and their combination are compared with the run's own default:

    e_k(s) = w_e * (ln p(s | x_k) - beta * ln pi_s) + b_s        (epochs with heart-rate data, core-algo)

with beta = AlgoParams ``hybrid.priorExponent`` and b = the bundle's optional ``stageLogBias``.

Everything except beta and b is the run's own (the run must have been made with ``--keep-inner``): classifier and
outer fold bundles, the decoder choice theta_g = (durationModel, emissionWeight, timePriorWeight) of every (variant,
outer group g) from the nested per-group selection, and the inner fold models with the inner held-out Walch subjects
of g (out-of-fold decodes that never contain the subjects of group g).

- ``BASE``: beta = 1, b = 0 (must reproduce the run's own outer results exactly).
- ``A``: inner grid beta in :data:`BETAS` at theta_g, chosen by :func:`choose_beta` (the adoption rule's objective
  inside the inner CV).
- ``B``: beta = 1, b_g = :func:`fit_stage_bias` on the inner out-of-fold decodes of g at theta_g.
- ``C``: b_g(beta) for every beta of the grid (each fit started at 0), beta_g by :func:`choose_beta` on
  (beta, b_g(beta)) with the BASE inner kappa as reference; no qualifying beta -> BASE.

E of a night = sum over AWAKE, LIGHT, DEEP, REM of |predicted - true| minutes over its scored 30-s chunks. Adoption
(:func:`adopt`, Walch held-out subjects): kappa = min over the headline variants of the mean per-subject kappa, E =
max over them of the mean per-night E; candidates with kappa >= kappa(BASE) - 0.01 qualify; the lowest E wins; ties
(|dE| < 0.05 min) -> higher kappa. :func:`final_model` then fits b on the LOSO out-of-fold decodes of all Walch
subjects (both headline variants) at the final decoder parameters and writes the final bundle / trained defaults.
:func:`rerender` recomputes the summary / report (with the reporting-only pairwise comparisons, the per-code sanity
flags, the fallback split and the inner-CV kappa losses) from the stored per-night results without algo-cli.
"""

from __future__ import annotations

import itertools
import json
import logging
import math
import shutil
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping, Sequence

import numpy as np

from . import report
from .algocli import AlgoCli, AlgoCliError
from .bundle import validate_bundle, with_stage_log_bias, write_bundle
from .loso import CONFIGS, PipelineConfig, map_threads
from .nightset import NightIndex, load_index
from .util import N_STAGES, STAGES, canonical_json, deep_merge, read_json, write_json

log = logging.getLogger(__name__)

BETAS: tuple[float, ...] = (1.0, 0.75, 0.5, 0.25, 0.0)       # grid order: today's value first
CANDIDATES: tuple[str, ...] = ("BASE", "A", "B", "C")
CANDIDATE_LABEL = {"BASE": "baseline (beta = 1, b = 0)", "A": "(a) prior exponent beta",
                   "B": "(b) proportion matching b", "C": "(c) beta + b"}
KAPPA_MARGIN = 0.01
TIE_MINUTES = 0.05
FP_MAX_DECODES = 8
FP_TOLERANCE = 0.005
SHARE_FLOOR = 1e-4
SANITY_CODES = ("SANITY_DEEP_HIGH", "SANITY_REM_HIGH", "SANITY_DEEP_LOW")
SECONDARY_DATASET = "bidsleep"
DEFAULT_OUT = "bias"
# the pre-registration v3, committed verbatim (written to tools/train/out/prereg-bias.md and commands.log first)
PREREG_PATH = "docs/prereg/prereg-bias.md"
PREREG_SHA256 = "75499301990a0bbea9f0403ee694d6e2487da7258ac043ba120da053cd85c288"


class BiasError(RuntimeError):
    """The experiment cannot run on this run directory (inputs missing, other build) or failed a consistency check."""


# ------------------------------------------------------------------------------------------- pure helpers ----

def shares(confusion) -> tuple[np.ndarray, np.ndarray]:
    """(true shares, predicted shares) over the scored chunks of a 4x4 confusion matrix (rows = truth)."""
    c = np.asarray(confusion, dtype=float)
    tot = c.sum()
    if tot <= 0:
        raise ValueError("empty confusion matrix")
    return c.sum(axis=1) / tot, c.sum(axis=0) / tot


def stage_minutes_errors(confusion, chunk_min: float = report.CHUNK_MIN) -> dict[str, float]:
    """|predicted - true| minutes per stage of one night (scored 30-s chunks)."""
    c = np.asarray(confusion, dtype=float)
    return {s: float(abs(c[:, i].sum() - c[i, :].sum()) * chunk_min) for i, s in enumerate(STAGES)}


def night_error(confusion, chunk_min: float = report.CHUNK_MIN) -> float:
    """E of one night: the summed absolute minutes error over AWAKE + LIGHT + DEEP + REM."""
    return float(sum(stage_minutes_errors(confusion, chunk_min).values()))


def fit_stage_bias(decode: Callable[[np.ndarray], np.ndarray], *, max_decodes: int = FP_MAX_DECODES,
                   tol: float = FP_TOLERANCE, floor: float = SHARE_FLOOR) -> dict:
    """Deterministic fixed point of the pre-registration: ``decode(b)`` returns the pooled 4x4 confusion matrix of
    the fit nights decoded with stage log-bias ``b``. Start b = 0, eta = 1; proposal b' = b + eta * ln(T / max(P,
    floor)), centred; accepted when D(b') = sum |T - P(b')| < D(b), otherwise eta is halved. At most ``max_decodes``
    decodes (b = 0 included); stops early when D <= ``tol``. Returns the accepted b with its trace."""
    b = np.zeros(N_STAGES)
    t, p = shares(decode(b))
    d = float(np.abs(t - p).sum())
    trace = [{"index": 0, "bias": b.tolist(), "eta": None, "D": d, "predShares": p.tolist(), "accepted": True}]
    best = 0
    eta = 1.0
    n = 1
    while n < max_decodes and d > tol:
        prop = b + eta * np.log(np.maximum(t, floor) / np.maximum(p, floor))
        prop = prop - prop.mean()
        t2, p2 = shares(decode(prop))
        if not np.allclose(t2, t, rtol=0, atol=1e-12):
            raise BiasError("true shares changed between decodes of the same fit set")
        d2 = float(np.abs(t2 - p2).sum())
        accepted = d2 < d
        trace.append({"index": n, "bias": prop.tolist(), "eta": eta, "D": d2, "predShares": p2.tolist(),
                      "accepted": accepted})
        if accepted:
            b, p, d, best = prop, p2, d2, n
        else:
            eta /= 2.0
        n += 1
    return {"bias": b.tolist(), "D": d, "trueShares": t.tolist(), "predShares": p.tolist(), "decodes": n,
            "bestIndex": best, "trace": trace}


def choose_beta(points: Sequence[Mapping], ref_kappa: float, margin: float = KAPPA_MARGIN,
                tie: float = TIE_MINUTES) -> int | None:
    """Index of the chosen point among ``points`` (each ``{"beta", "kappa", "E"}``): qualifying = kappa >=
    ``ref_kappa`` - ``margin``; lowest E; ties (E within ``tie`` minutes of the lowest) -> higher kappa, then larger
    beta. None when nothing qualifies."""
    ok = [i for i, q in enumerate(points) if q["kappa"] is not None and math.isfinite(q["kappa"])
          and q["kappa"] >= ref_kappa - margin and q["E"] is not None and math.isfinite(q["E"])]
    if not ok:
        return None
    low = min(points[i]["E"] for i in ok)
    tied = [i for i in ok if points[i]["E"] - low < tie]
    return max(tied, key=lambda i: (points[i]["kappa"], points[i]["beta"]))


def adopt(scores: Mapping[str, Mapping], base: str = "BASE", margin: float = KAPPA_MARGIN, tie: float = TIE_MINUTES,
          order: Sequence[str] = CANDIDATES) -> dict:
    """The pre-registered adoption rule on ``scores`` (candidate -> ``{"kappaWorse", "EWorse"}``)."""
    ref = scores[base]["kappaWorse"]
    ok = [c for c in order if c in scores and scores[c]["kappaWorse"] >= ref - margin]
    low = min(scores[c]["EWorse"] for c in ok)
    tied = [c for c in ok if scores[c]["EWorse"] - low < tie]
    best = max(tied, key=lambda c: (scores[c]["kappaWorse"], -list(order).index(c)))
    return {"adopted": best, "qualifying": ok, "threshold": ref - margin, "baselineKappa": ref, "tied": tied,
            "rule": "lowest worse-variant E among candidates with worse-variant kappa >= baseline - 0.01; "
                    "ties (|dE| < 0.05 min) -> higher kappa; none -> baseline"}


def mode_beta(choices: Sequence[Mapping]) -> float:
    """Most frequent beta over outer folds (``{"beta", "innerKappa"}`` per fold); ties -> higher mean inner kappa,
    then larger beta."""
    cnt = Counter(float(c["beta"]) for c in choices)
    ks: dict[float, list[float]] = defaultdict(list)
    for c in choices:
        ks[float(c["beta"])].append(float(c["innerKappa"]))
    return sorted(cnt, key=lambda b: (-cnt[b], -float(np.mean(ks[b])), -b))[0]


# ------------------------------------------------------------------------------------------------ run data ----

@dataclass
class Group:
    """One inner selection of the run: (variant, outer group) with its decoder choice and inner-CV data."""
    variant: str
    key: str
    theta: dict
    inner_score: float
    eval_subjects: list[str]
    eval_nights: list[str]
    inner_models: Path
    nights_file: Path
    heldout: list[str]


def _jsonl(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


class Experiment:
    """Inputs and algo-cli access for one run directory (``<out>/<run>``).

    ``out_name`` is the experiment's output directory inside the run (default ``bias``). The algo-cli build must be
    the one that produced the run unless ``allow_other_build``: then the difference is logged and recorded
    (``run_build``), and the BASE night-by-night reproduction check of :func:`run_experiment` is the only guard."""

    def __init__(self, run_dir: Path, cli: AlgoCli | None = None, jobs: int = 4,
                 variants: Sequence[str] = report.HEADLINE_VARIANTS, out_name: str = DEFAULT_OUT,
                 allow_other_build: bool = False):
        self.run_dir = Path(run_dir)
        self.cfg = PipelineConfig.from_json(read_json(self.run_dir / "config.json"))
        self.summary = read_json(self.run_dir / "summary.json")
        sel = self.summary["selection"]["classifier"]
        self.clf, self.config = sel["chosen"], sel["config"]
        self.label = report.CLASSIFIER_LABEL[self.clf]
        self.variants = tuple(variants)
        self.out = self.run_dir / out_name
        self.jobs = jobs
        self.cli = cli or AlgoCli.locate(self.cfg.algo_cli, commands_log=self.out / "commands.log",
                                         timeout_s=self.cfg.timeout_s)
        want = read_json(self.run_dir / "config.json").get("algoCli", {}).get("sha256")
        self.fingerprint = self.cli.fingerprint()
        self.run_build = want
        if want and self.fingerprint.get("sha256") != want:
            if not allow_other_build:
                raise BiasError(f"algo-cli build {self.fingerprint.get('sha256')} differs from the run's {want}: the "
                                f"experiment must decode with the build that produced the run (or pass "
                                f"--allow-other-build; BASE must then still reproduce the run night by night)")
            log.warning("algo-cli build %s differs from the run's %s (allowed: --allow-other-build); BASE must "
                        "reproduce the run night by night", self.fingerprint.get("sha256"), want)
        self.index: NightIndex = load_index(self.cfg.nights_root, list(self.variants), self.cfg.summary_csv)
        self.results = [r for r in _jsonl(self.run_dir / "results.jsonl")
                        if r["estimator"] == self.label and r["config"] == self.config and r["variant"] in self.variants]
        self.groups = self._groups()
        self._bundle_cache: dict[Path, dict] = {}

    def hybrid_dir(self, variant: str) -> Path:
        return self.run_dir / "hybrid" / self.config / variant / self.clf

    def _groups(self) -> list[Group]:
        out = []
        for v in self.variants:
            metas = [read_json(p) for p in sorted((self.hybrid_dir(v) / "folds-meta").glob("*.meta.json"))]
            by_key: dict[str, list[dict]] = defaultdict(list)
            for m in metas:
                if m.get("kind") != "loso":
                    raise BiasError(f"fold {m.get('fold')}: only LOSO folds are supported (no external evaluation)")
                by_key[m["selectionKey"]].append(m)
            for key, ms in sorted(by_key.items()):
                s = read_json(self.hybrid_dir(v) / "selection" / f"{key}.json")
                theta = dict(s["chosen"]["hybrid"])
                if any(canonical_json(m["params"]) != canonical_json(theta) for m in ms):
                    raise BiasError(f"{v}/{key}: outer folds disagree with their selection's choice")
                work = self.run_dir / "work" / self.config / v / self.clf / key
                models, nights = work / "models", work / "nights.jsonl"
                if not models.is_dir() or not nights.is_file():
                    raise BiasError(f"{work}: inner fold models / nights missing (run with --keep-inner)")
                out.append(Group(variant=v, key=key, theta=theta, inner_score=float(s["chosen"]["score"]),
                                 eval_subjects=list(s["evalSubjects"]), eval_nights=list(s["evalNights"]),
                                 inner_models=models, nights_file=nights,
                                 heldout=sorted(x for m in ms for x in m["test"])))
        return out

    # ------------------------------------------------------------------------------------------- decoding ----

    def params(self, theta: Mapping, beta: float) -> dict:
        return deep_merge(dict(self.cfg.base_params), {"estimator": "HYBRID",
                                                       "hybrid": {**theta, "priorExponent": float(beta)}})

    def _bundle(self, path: Path) -> dict:
        b = self._bundle_cache.get(path)
        if b is None:
            b = read_json(path)
            validate_bundle(b)
            self._bundle_cache[path] = b
        return b

    def _write_biased(self, src_dir: Path, subjects: Sequence[str], bias: Sequence[float], dst: Path) -> Path:
        dst.mkdir(parents=True, exist_ok=True)
        for s in subjects:
            b = with_stage_log_bias(self._bundle(src_dir / f"{s}.json"), bias)
            (dst / f"{s}.json").write_text(json.dumps(b, separators=(",", ":"), allow_nan=False), encoding="utf-8")
        return dst

    def tag(self, metrics: Mapping, variant: str, expected: Sequence[str], extra: Mapping | None = None) -> list[dict]:
        """Per-night records of one evaluate block (scored and skipped nights; skipped ones carry no confusion)."""
        recs = report.parse_metrics(metrics)
        report.check_estimator_used(recs, "HYBRID")
        got = sorted(r["id"] for r in recs)
        if got != sorted(expected):
            raise BiasError(f"{variant}: algo-cli returned {len(got)} nights for {len(expected)} expected")
        no_model = [r["id"] for r in recs if r["reason"] == "NO_FOLD_MODEL"]
        if no_model:
            raise BiasError(f"{variant}: no fold model for {no_model[:3]}")
        for r in recs:
            ref = self.index.get(variant, r["id"])
            r.update(night=r["id"], subject=ref.subject, dataset=ref.dataset, variant=variant,
                     confusion=None if r["confusion"] is None else np.asarray(r["confusion"]).tolist(), **(extra or {}))
        return recs

    def decode(self, nights: Path, expected: Sequence[str], variant: str, theta: Mapping, beta: float,
               fold_models: Path, out: Path, extra: Mapping | None = None) -> list[dict]:
        pf = write_json(out.with_suffix(".params.json"), self.params(theta, beta))
        m = self.cli.evaluate(nights, "HYBRID", out, params=pf, fold_models=fold_models)
        return self.tag(m, variant, expected, extra)

    def decode_inner(self, g: Group, beta: float, bias: Sequence[float] | None, tag: str) -> list[dict]:
        d = self.out / "inner" / g.variant / g.key
        if bias is None:
            return self.decode(g.nights_file, g.eval_nights, g.variant, g.theta, beta, g.inner_models,
                               d / f"{tag}.json")
        models = self._write_biased(g.inner_models, g.eval_subjects, bias, d / f"{tag}.models")
        try:
            return self.decode(g.nights_file, g.eval_nights, g.variant, g.theta, beta, models, d / f"{tag}.json")
        finally:
            shutil.rmtree(models, ignore_errors=True)


def _scored(recs: Sequence[Mapping]) -> list[Mapping]:
    return [r for r in recs if r["estimated"]]


def inner_metrics(recs: Sequence[Mapping]) -> dict:
    rs = _scored(recs)
    t, p = shares(_pooled(rs))
    return {"kappa": report.mean_subject_kappa(rs), "E": float(np.mean([night_error(r["confusion"]) for r in rs])),
            "trueShares": t.tolist(), "predShares": p.tolist(), "nights": len(rs)}


def _pooled(recs: Sequence[Mapping]) -> np.ndarray:
    return sum((np.asarray(r["confusion"], float) for r in _scored(recs)), np.zeros((N_STAGES, N_STAGES)))


# ------------------------------------------------------------------------------------------ the experiment ----

def run_experiment(run_dir: Path, cli: AlgoCli | None = None, jobs: int = 4, out_name: str = DEFAULT_OUT,
                   allow_other_build: bool = False) -> dict:
    """Runs the candidates of the pre-registration on ``run_dir``; writes ``<run>/<out_name>/{summary.json,
    report.md, decisions.json, results.jsonl}`` (see :class:`Experiment` for ``allow_other_build``)."""
    ex = Experiment(run_dir, cli=cli, jobs=jobs, out_name=out_name, allow_other_build=allow_other_build)
    ex.out.mkdir(parents=True, exist_ok=True)
    log.info("bias experiment on %s: %d inner selections, algo-cli %s", ex.run_dir, len(ex.groups),
             ex.fingerprint.get("sha256"))

    # (a) and the BASE inner reference: one --param-grid call per group over BETAS
    def grid_task(g: Group) -> list[dict]:
        points = [{"id": f"beta{i}", "params": ex.params(g.theta, b)} for i, b in enumerate(BETAS)]
        res = ex.cli.evaluate_grid(g.nights_file, "HYBRID", ex.out / "inner" / g.variant / g.key / "beta-grid.json",
                                   points, fold_models=g.inner_models)
        out = []
        for b, item in zip(BETAS, res["grid"]):
            m = inner_metrics(ex.tag(item["metrics"], g.variant, g.eval_nights))
            out.append({"beta": b, **m})
        if not math.isclose(out[0]["kappa"], g.inner_score, rel_tol=0, abs_tol=1e-12):
            raise BiasError(f"{g.variant}/{g.key}: inner kappa at beta = 1 is {out[0]['kappa']}, the selection "
                            f"recorded {g.inner_score} (inner data differ from the run's)")
        return out

    grids = dict(zip([(g.variant, g.key) for g in ex.groups], map_threads(grid_task, ex.groups, jobs)))

    # (b) / (c): one fixed-point chain per (group, beta)
    chains = [(g, b) for g in ex.groups for b in BETAS]

    def chain_task(task) -> dict:
        g, beta = task
        decoded: dict[int, list[dict]] = {}
        counter = itertools.count()

        def decode(bias: np.ndarray) -> np.ndarray:
            n = next(counter)
            recs = ex.decode_inner(g, beta, None if not np.any(bias) else bias.tolist(), f"fp-beta{beta:g}-{n}")
            decoded[n] = recs
            return _pooled(recs)

        fit = fit_stage_bias(decode)
        m = inner_metrics(decoded[fit["bestIndex"]])
        return {"beta": beta, "fit": fit, **m}

    chain_res = map_threads(chain_task, chains, jobs)
    fits: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for (g, _), r in zip(chains, chain_res):
        fits[(g.variant, g.key)].append(r)

    decisions: list[dict] = []
    for g in ex.groups:
        grid = grids[(g.variant, g.key)]
        fp = fits[(g.variant, g.key)]
        ref = grid[0]["kappa"]
        ia = choose_beta(grid, ref)
        ic = choose_beta(fp, ref)
        b1 = fp[0]
        dec = {"variant": g.variant, "key": g.key, "theta": g.theta, "heldout": g.heldout,
               "evalSubjects": len(g.eval_subjects), "innerBase": grid[0], "innerGrid": grid,
               "innerFits": [{k: v for k, v in r.items() if k != "fit"} | {"bias": r["fit"]["bias"],
                              "D": r["fit"]["D"], "decodes": r["fit"]["decodes"], "trace": r["fit"]["trace"]}
                             for r in fp],
               "choices": {
                   "BASE": {"beta": 1.0, "bias": None, "innerKappa": grid[0]["kappa"], "innerE": grid[0]["E"]},
                   "A": {"beta": grid[ia]["beta"], "bias": None, "innerKappa": grid[ia]["kappa"],
                         "innerE": grid[ia]["E"]},
                   "B": {"beta": 1.0, "bias": b1["fit"]["bias"], "innerKappa": b1["kappa"], "innerE": b1["E"]},
                   "C": ({"beta": fp[ic]["beta"], "bias": fp[ic]["fit"]["bias"], "innerKappa": fp[ic]["kappa"],
                          "innerE": fp[ic]["E"]} if ic is not None else
                         {"beta": 1.0, "bias": None, "innerKappa": grid[0]["kappa"], "innerE": grid[0]["E"],
                          "fallback": "no beta qualified: BASE"}),
               }}
        if ia is None:
            raise BiasError(f"{g.variant}/{g.key}: beta = 1 must qualify against itself")
        decisions.append(dec)
    write_json(ex.out / "decisions.json", {"betas": list(BETAS), "groups": decisions})

    # outer evaluation of every candidate
    tasks = [(c, d) for c in CANDIDATES for d in decisions]

    def outer_task(task) -> list[dict]:
        cand, d = task
        ch = d["choices"][cand]
        v = d["variant"]
        folds = ex.hybrid_dir(v) / "folds"
        base = ex.out / "outer" / cand / v
        nights = ex.index.write_subset(v, d["heldout"], base / f"{d['key']}.nights.jsonl")
        expected = [n.id for n in ex.index.nights_of(v, d["heldout"])]
        fm = folds if ch["bias"] is None else ex._write_biased(folds, d["heldout"], ch["bias"],
                                                               base / f"{d['key']}.folds")
        try:
            return ex.decode(nights, expected, v, d["theta"], ch["beta"], fm, base / f"{d['key']}.json",
                             {"candidate": cand, "group": d["key"], "beta": ch["beta"], "bias": ch["bias"]})
        finally:
            nights.unlink(missing_ok=True)
            if fm != folds:
                shutil.rmtree(fm, ignore_errors=True)

    recs = [r for rs in map_threads(outer_task, tasks, jobs) for r in rs]
    _check_base_reproduces(ex, [r for r in recs if r["candidate"] == "BASE"])
    for r in recs:
        r.pop("kappa", None)
    _write_jsonl(ex.out / "results.jsonl", recs)
    if ex.cli.fingerprint().get("sha256") != ex.fingerprint.get("sha256"):
        raise BiasError("algo-cli changed during the bias experiment")
    summary = summarize(recs, decisions, meta=experiment_meta(ex))
    write_json(ex.out / "summary.json", _clean(summary))
    (ex.out / "report.md").write_text(render(summary), encoding="utf-8")
    return summary


def experiment_meta(ex: Experiment) -> dict:
    """Run identity recorded in the experiment's summary.json (kept as is by :func:`rerender`)."""
    meta = {"run": ex.cfg.run, "runDir": str(ex.run_dir), "estimator": ex.label, "config": ex.config,
            "algoCli": ex.fingerprint, "groups": len(ex.groups)}
    if ex.run_build and ex.fingerprint.get("sha256") != ex.run_build:
        meta["runAlgoCliSha256"] = ex.run_build
    return meta


def rerender(run_dir: Path, out_name: str = DEFAULT_OUT) -> dict:
    """Recomputes ``<run>/<out_name>/{summary.json, report.md}`` from the stored per-night ``results.jsonl`` and
    ``decisions.json`` of a finished experiment (no algo-cli; run identity from the previous summary's meta)."""
    out = Path(run_dir) / out_name
    old = read_json(out / "summary.json")
    keys = ("run", "runDir", "estimator", "config", "algoCli", "groups", "runAlgoCliSha256")
    meta = {k: old["meta"][k] for k in keys if k in old.get("meta", {})}
    summary = summarize(_jsonl(out / "results.jsonl"), read_json(out / "decisions.json")["groups"], meta=meta)
    write_json(out / "summary.json", _clean(summary))
    (out / "report.md").write_text(render(summary), encoding="utf-8")
    return summary


def _check_base_reproduces(ex: Experiment, base: Sequence[Mapping]) -> None:
    """BASE must equal the run's own outer evaluation night by night (confusion matrix and warnings)."""
    own = {(r["variant"], r["night"]): r for r in ex.results}
    if len(own) != len(base):
        raise BiasError(f"BASE has {len(base)} nights, the run {len(own)}")
    for r in base:
        o = own.get((r["variant"], r["night"]))
        if (o is None or o["confusion"] != r["confusion"] or list(o["warnings"]) != list(r["warnings"])
                or o["estimated"] != r["estimated"]):
            raise BiasError(f"BASE does not reproduce the run for {r['variant']} {r['night']}")


def _write_jsonl(path: Path, rows: Sequence[Mapping]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(_clean(r), separators=(",", ":"), allow_nan=False) + "\n")


def _clean(obj):
    if isinstance(obj, float):
        return obj if math.isfinite(obj) else None
    if isinstance(obj, np.ndarray):
        return _clean(obj.tolist())
    if isinstance(obj, (np.floating, np.integer)):
        return _clean(obj.item())
    if isinstance(obj, Mapping):
        return {str(k): _clean(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_clean(v) for v in obj]
    return obj


# ---------------------------------------------------------------------------------------------- summaries ----

def cell(recs: Sequence[Mapping]) -> dict:
    """Metrics of one (candidate, dataset, variant): report.aggregate plus E, per-stage minute errors of all four
    stages, pooled shares, sanity flags and per-subject E."""
    agg = report.aggregate(recs)
    t, p = shares(agg["confusion"])
    rs = _scored(recs)
    per_night = [stage_minutes_errors(r["confusion"]) for r in rs]
    e = [sum(x.values()) for x in per_night]
    subj: dict[str, list[float]] = defaultdict(list)
    for r, x in zip(rs, e):
        subj[r["subject"]].append(x)
    algo_me = max((abs(stage_minutes_errors(r["confusion"])[s] - float(r["minutesError"][s]))
                   for r in rs if r.get("minutesError") for s in report.MINUTE_STAGES), default=0.0)
    return {
        "nights": len(rs), "nightsSkipped": agg["nightsSkipped"], "subjects": agg["subjects"], "kappa": agg["kappaSubjectMean"],
        "kappaSd": agg["kappaSubjectSd"], "kappaMedian": agg["kappaSubjectMedian"], "kappaPooled": agg["kappaPooled"],
        "accuracy": agg["accuracy"], "macroF1": agg["macroF1"], "f1": agg["f1"], "recall": agg["recall"],
        "precision": agg["precision"], "E": float(np.mean(e)), "ESd": float(np.std(e, ddof=1)) if len(e) > 1 else 0.0,
        "minutesError": {s: float(np.mean([x[s] for x in per_night])) for s in STAGES},
        "trueShares": dict(zip(STAGES, t.tolist())), "predShares": dict(zip(STAGES, p.tolist())),
        "sanityNights": sum(1 for r in rs if set(r.get("warnings", ())) & set(SANITY_CODES)),
        "sanityByCode": {c: sum(1 for r in rs if c in r.get("warnings", ())) for c in SANITY_CODES},
        "rulesFallbackNights": agg["rulesFallbackNights"],
        "subjectKappa": agg["subjectKappa"], "subjectE": {s: float(np.mean(v)) for s, v in sorted(subj.items())},
        "confusion": agg["confusion"], "maxAbsDiffToAlgoCliMinutes": algo_me,
    }


def _compare(cells: Mapping, cand: str, ref: str, ds: str, v: str) -> dict | None:
    a, b = cells.get(cand, {}).get(ds, {}).get(v), cells.get(ref, {}).get(ds, {}).get(v)
    if a is None or b is None:
        return None
    return {"candidate": cand, "reference": ref, "dataset": ds, "variant": v,
            "kappa": report.paired_bootstrap(a["subjectKappa"], b["subjectKappa"]),
            "E": report.paired_bootstrap(a["subjectE"], b["subjectE"])}


def pairwise_comparisons(cells: Mapping, variants: Sequence[str] = report.HEADLINE_VARIANTS) -> list[dict]:
    """Paired per-subject differences between the corrections (later − earlier candidate of :data:`CANDIDATES`
    without BASE, e.g. B − A), per dataset and variant; reporting only, not part of the adoption rule."""
    out = []
    for ds in (report.HEADLINE_DATASET, SECONDARY_DATASET):
        for ref, cand in itertools.combinations(CANDIDATES[1:], 2):
            for v in variants:
                x = _compare(cells, cand, ref, ds, v)
                if x is not None:
                    out.append(x)
    return out


def fallbacks(recs: Sequence[Mapping], decisions: Sequence[Mapping],
              variants: Sequence[str] = report.HEADLINE_VARIANTS) -> dict:
    """Per candidate with fallback groups (``choices[c]["fallback"]``, e.g. C when no (beta, b(beta)) qualified):
    the groups, their outer folds, and per dataset / variant the mean night E of every candidate on the nights of
    the fallback groups and on the other nights (how much of a candidate's E comes from falling back)."""
    out: dict[str, dict] = {}
    for c in CANDIDATES:
        fb = [d for d in decisions if d["choices"][c].get("fallback")]
        if not fb:
            continue
        keys = {(d["variant"], d["key"]) for d in fb}
        split: dict[str, dict[str, dict]] = {}
        for ds in (report.HEADLINE_DATASET, SECONDARY_DATASET):
            for v in variants:
                rs = [r for r in _scored(recs) if r["dataset"] == ds and r["variant"] == v]
                if not rs:
                    continue
                parts: dict[bool, dict[str, list[float]]] = {True: defaultdict(list), False: defaultdict(list)}
                for r in rs:
                    parts[(r["variant"], r["group"]) in keys][r["candidate"]].append(night_error(r["confusion"]))
                split.setdefault(ds, {})[v] = {
                    "fallbackNights": len(parts[True][c]), "otherNights": len(parts[False][c]),
                    "EFallback": {k: float(np.mean(parts[True][k])) if parts[True][k] else None for k in CANDIDATES},
                    "EOther": {k: float(np.mean(parts[False][k])) if parts[False][k] else None for k in CANDIDATES}}
        out[c] = {"groups": [{"variant": d["variant"], "key": d["key"], "heldout": len(d["heldout"]),
                              "reason": d["choices"][c]["fallback"]} for d in fb],
                  "folds": sum(len(d["heldout"]) for d in fb), "split": split}
    return out


def inner_kappa_below_base(decisions: Sequence[Mapping], margin: float = KAPPA_MARGIN) -> dict[str, list[dict]]:
    """Per candidate, the inner selections whose inner kappa is more than ``margin`` below BASE's inner kappa (B's
    inner kappa is in-sample for b; BASE's is the selection winner's, so both sides are optimistic)."""
    out: dict[str, list[dict]] = {}
    for c in CANDIDATES[1:]:
        out[c] = [{"variant": d["variant"], "key": d["key"], "innerKappa": d["choices"][c]["innerKappa"],
                   "baseInnerKappa": d["choices"]["BASE"]["innerKappa"]}
                  for d in decisions
                  if d["choices"][c]["innerKappa"] < d["choices"]["BASE"]["innerKappa"] - margin]
    return out


def choice_counts(decisions: Sequence[Mapping]) -> dict[str, dict[str, int]]:
    """Outer folds per chosen beta and candidate; a fallback to BASE counts as ``"<beta> (fallback)"``, a beta with a
    fitted b as ``"<beta> + b"`` (so C's beta = 1 with b is told apart from C falling back to BASE)."""
    out = {c: Counter() for c in CANDIDATES}
    for d in decisions:
        for c in CANDIDATES:
            ch = d["choices"][c]
            label = f"{ch['beta']:g}"
            if ch.get("fallback"):
                label += " (fallback)"
            elif ch.get("bias") is not None:
                label += " + b"
            out[c][label] += len(d["heldout"])
    return {c: dict(v) for c, v in out.items()}


def summarize(recs: Sequence[Mapping], decisions: Sequence[Mapping], meta: Mapping | None = None,
              variants: Sequence[str] = report.HEADLINE_VARIANTS) -> dict:
    """summary.json of the experiment from its per-night records and per-group decisions (``meta``: run identity,
    :func:`experiment_meta`)."""
    cells: dict[str, dict[str, dict[str, dict]]] = {}
    for c in CANDIDATES:
        for ds in (report.HEADLINE_DATASET, SECONDARY_DATASET):
            for v in variants:
                rs = [r for r in recs if r["candidate"] == c and r["dataset"] == ds and r["variant"] == v]
                if rs:
                    cells.setdefault(c, {}).setdefault(ds, {})[v] = cell(rs)
    scores: dict[str, dict[str, dict]] = {}
    for ds in (report.HEADLINE_DATASET, SECONDARY_DATASET):
        for c in CANDIDATES:
            cv = cells.get(c, {}).get(ds)
            if not cv or not all(v in cv for v in variants):
                continue
            kv = {v: cv[v]["kappa"] for v in variants}
            ev = {v: cv[v]["E"] for v in variants}
            scores.setdefault(ds, {})[c] = {"kappaWorse": min(kv.values()), "kappaWorseVariant": min(kv, key=kv.get),
                                            "EWorse": max(ev.values()), "EWorseVariant": max(ev, key=ev.get),
                                            "kappa": kv, "E": ev}
    comparisons = []
    for ds in (report.HEADLINE_DATASET, SECONDARY_DATASET):
        for c in CANDIDATES[1:]:
            for v in variants:
                x = _compare(cells, c, "BASE", ds, v)
                if x is not None:
                    x.pop("reference")
                    comparisons.append(x)
    choices = {c: Counter() for c in CANDIDATES}
    for d in decisions:
        for c in CANDIDATES:
            choices[c][f"{d['choices'][c]['beta']:g}"] += len(d["heldout"])
    decision = adopt(scores[report.HEADLINE_DATASET]) if report.HEADLINE_DATASET in scores else None
    return {"meta": {**dict(meta or {}), "betas": list(BETAS), "kappaMargin": KAPPA_MARGIN, "tieMinutes": TIE_MINUTES,
                     "fixedPoint": {"maxDecodes": FP_MAX_DECODES, "tolerance": FP_TOLERANCE, "shareFloor": SHARE_FLOOR},
                     "prereg": PREREG_PATH, "preregSha256": PREREG_SHA256},
            "cells": cells, "scores": scores, "comparisons": comparisons,
            "pairwise": pairwise_comparisons(cells, variants),
            "betaChoices": {c: dict(v) for c, v in choices.items()},
            "choiceCounts": choice_counts(decisions),
            "fallbacks": fallbacks(recs, decisions, variants),
            "innerKappaBelowBase": inner_kappa_below_base(decisions),
            "decisions": [{k: d[k] for k in ("variant", "key", "theta", "choices", "innerBase")} |
                          {"innerGrid": [{k: q[k] for k in ("beta", "kappa", "E", "predShares")} for q in d["innerGrid"]],
                           "innerFits": [{k: q[k] for k in ("beta", "kappa", "E", "bias", "D", "decodes")}
                                         for q in d["innerFits"]], "heldout": len(d["heldout"])} for d in decisions],
            "decision": decision}


# ----------------------------------------------------------------------------------------------- markdown ----

def _f(x, nd: int = 3) -> str:
    return report._f(x, nd)


def _pct(x) -> str:
    return "—" if x is None else f"{100 * x:.1f}"


def _comparison_table(rows: Sequence[Mapping], with_reference: bool) -> list[str]:
    ref = "reference | " if with_reference else ""
    head = (f"| candidate | {ref}variant | Δκ [95 % CI] | κ better / worse | ΔE min [95 % CI] | "
            f"E lower / higher |")
    out = [head, "|---|" + ("---|" if with_reference else "") + "---|---|---|---|---|"]
    for x in rows:
        k, e = x["kappa"], x["E"]
        out.append(f"| {x['candidate']} | " + (f"{x['reference']} | " if with_reference else "")
                   + f"{x['variant']} | {_f(k['meanDiff'])} [{_f(k['ciLow'])}, {_f(k['ciHigh'])}] "
                   f"| {k['wins']} / {k['losses']} | {_f(e['meanDiff'], 1)} [{_f(e['ciLow'], 1)}, "
                   f"{_f(e['ciHigh'], 1)}] | {e['losses']} / {e['wins']} |")
    return out


def render(s: Mapping, variants: Sequence[str] = report.HEADLINE_VARIANTS) -> str:
    m = s["meta"]
    L = [f"# Stage-proportion experiment (pre-registered) — run `{m.get('run', '?')}`", "",
         f"{m.get('estimator', '')} [{m.get('config', '')}], outer LOSO fold models of the run; decoder choice theta_g "
         f"of each outer group from its nested selection; candidates change only beta (`hybrid.priorExponent`) and "
         f"b (bundle `stageLogBias`). Pre-registration: `{m['prereg']}`. E = mean per-night sum over AWAKE + LIGHT + "
         f"DEEP + REM of |predicted − true| minutes; κ = mean per-subject Cohen's kappa. Worse variant: min κ / max "
         f"E over {' / '.join(variants)}.", ""]
    for ds in (report.HEADLINE_DATASET, SECONDARY_DATASET):
        sc = s["scores"].get(ds)
        if not sc:
            continue
        title = "Walch (PSG), headline" if ds == report.HEADLINE_DATASET else "BIDSleep (EEG headband), secondary"
        L += [f"## {title}", "",
              "| candidate | κ " + " | κ ".join(variants) + " | **κ worse** | E " + " | E ".join(variants)
              + " | **E worse** | abs. error min D / R / W / L (worse-E variant) | accuracy | macro-F1 | "
              "sanity-flagged nights " + " / ".join(variants) + " |",
              "|---|" + "---|" * (2 * len(variants) + 6)]
        for c in CANDIDATES:
            if c not in sc:
                continue
            cs = s["cells"][c][ds]
            q = sc[c]
            wv = q["EWorseVariant"]
            me = " / ".join(_f(cs[wv]["minutesError"][x], 1) for x in ("DEEP", "REM", "AWAKE", "LIGHT"))
            L.append(f"| {c} {CANDIDATE_LABEL[c]} | " + " | ".join(f"{_f(cs[v]['kappa'])} ± {_f(cs[v]['kappaSd'])}"
                                                                    for v in variants)
                     + f" | **{_f(q['kappaWorse'])}** | " + " | ".join(_f(cs[v]["E"], 1) for v in variants)
                     + f" | **{_f(q['EWorse'], 1)}** | {me} | {_f(cs[wv]['accuracy'])} | {_f(cs[wv]['macroF1'])} | "
                     + " / ".join(f"{cs[v]['sanityNights']} of {cs[v]['nights']}" for v in variants) + " |")
        L += ["", "Pooled stage shares, % (AWAKE / LIGHT / DEEP / REM):", "",
              "| candidate | variant | predicted | true |", "|---|---|---|---|"]
        for c in CANDIDATES:
            for v in variants:
                cs = s["cells"].get(c, {}).get(ds, {}).get(v)
                if cs:
                    L.append(f"| {c} | {v} | " + " / ".join(_pct(cs["predShares"][x]) for x in STAGES) + " | "
                             + " / ".join(_pct(cs["trueShares"][x]) for x in STAGES) + " |")
        L += ["", "Sanity-flagged nights by warning code (a night can carry several):", "",
              "| candidate | variant | flagged nights | " + " | ".join(SANITY_CODES) + " |",
              "|---|---|---|" + "---|" * len(SANITY_CODES)]
        for c in CANDIDATES:
            for v in variants:
                cs = s["cells"].get(c, {}).get(ds, {}).get(v)
                if cs:
                    L.append(f"| {c} | {v} | {cs['sanityNights']} of {cs['nights']} | "
                             + " | ".join(str(cs["sanityByCode"][x]) for x in SANITY_CODES) + " |")
        L += ["", "Paired per-subject differences vs BASE (candidate − BASE, 95 % bootstrap CI, 10 000 resamples):", ""]
        L += _comparison_table([x for x in s["comparisons"] if x["dataset"] == ds], with_reference=False)
        pw = [x for x in s.get("pairwise", ()) if x["dataset"] == ds]
        if pw:
            L += ["", "Paired per-subject differences between the corrections (candidate − reference; reporting only, "
                      "not part of the adoption rule):", ""]
            L += _comparison_table(pw, with_reference=True)
        L.append("")
    d = s.get("decision")
    if d:
        L += ["## Decision (pre-registered rule, Walch)", "",
              f"Rule: {d['rule']}. Baseline κ worse {_f(d['baselineKappa'])} → threshold {_f(d['threshold'])}; "
              f"qualifying: {', '.join(d['qualifying'])}; **adopted: {d['adopted']} {CANDIDATE_LABEL[d['adopted']]}**.",
              ""]
    counts = s.get("choiceCounts") or s["betaChoices"]
    L += ["## Inner-CV choices", "", "Outer folds (held-out subjects) per chosen beta (\"+ b\": with a fitted b; "
          "\"(fallback)\": nothing qualified, BASE kept): " + "; ".join(
              f"{c}: " + ", ".join(f"beta {b} × {n}" for b, n in sorted(counts[c].items(), reverse=True))
              for c in CANDIDATES), ""]
    below = s.get("innerKappaBelowBase")
    if below is not None:
        n_groups = len(s["decisions"])
        L += ["Inner selections whose inner κ is more than " + _f(s["meta"]["kappaMargin"], 2) + " below BASE's "
              "(B's inner κ is in-sample for b, BASE's is the selection winner's): " + "; ".join(
                  f"{c}: {len(v)} of {n_groups}" + (" (" + ", ".join(
                      f"{q['variant']}/{q['key']} {_f(q['innerKappa'])} vs {_f(q['baseInnerKappa'])}" for q in v)
                      + ")" if v else "") for c, v in below.items()), ""]
    for c, fb in (s.get("fallbacks") or {}).items():
        L += [f"{c} fell back to BASE in {len(fb['groups'])} of {len(s['decisions'])} inner selections "
              f"({fb['folds']} outer folds): " + ", ".join(f"{g['variant']}/{g['key']} ({g['heldout']})"
                                                           for g in fb["groups"]) + ". Mean night E there and on "
              "the other nights:", "",
              "| dataset | variant | nights fallback / other | E fallback nights " + " / ".join(CANDIDATES)
              + " | E other nights " + " / ".join(CANDIDATES) + " |", "|---|---|---|---|---|"]
        for ds, vv in fb["split"].items():
            for v, q in vv.items():
                L.append(f"| {ds} | {v} | {q['fallbackNights']} / {q['otherNights']} | "
                         + " / ".join(_f(q["EFallback"][k], 1) for k in CANDIDATES) + " | "
                         + " / ".join(_f(q["EOther"][k], 1) for k in CANDIDATES) + " |")
        L.append("")
    L += ["| variant | group | theta_g | inner κ / E BASE | A: beta (κ / E) | B: b A/L/D/R (D, decodes) | "
          "C: beta, b (κ / E) |", "|---|---|---|---|---|---|---|"]
    for x in s["decisions"]:
        ch = x["choices"]
        th = x["theta"]
        b1 = next(q for q in x["innerFits"] if q["beta"] == 1.0)
        cb = ch["C"]["bias"]
        L.append(f"| {x['variant']} | {x['key']} | {th['durationModel']}, {th['emissionWeight']:g}, "
                 f"{th['timePriorWeight']:g} | {_f(ch['BASE']['innerKappa'])} / {_f(ch['BASE']['innerE'], 1)} | "
                 f"{ch['A']['beta']:g} ({_f(ch['A']['innerKappa'])} / {_f(ch['A']['innerE'], 1)}) | "
                 + " / ".join(f"{v:+.2f}" for v in b1["bias"]) + f" ({_f(b1['D'], 4)}, {b1['decodes']}) | "
                 + f"{ch['C']['beta']:g}, " + ("—" if cb is None else " / ".join(f"{v:+.2f}" for v in cb))
                 + f" ({_f(ch['C']['innerKappa'])} / {_f(ch['C']['innerE'], 1)}) |")
    L += ["", "Inner grid (a), per group: beta → κ / E / predicted DEEP %:", ""]
    for x in s["decisions"]:
        L.append(f"- {x['variant']} {x['key']}: " + "; ".join(
            f"{q['beta']:g} → {_f(q['kappa'])} / {_f(q['E'], 1)} / {_pct(q['predShares'][2])}" for q in x["innerGrid"]))
    L.append("")
    return "\n".join(L)


# ------------------------------------------------------------------------------------------------ final ----

def final_model(run_dir: Path, out_dir: Path, cli: AlgoCli | None = None, install: Path | None = None,
                jobs: int = 4, run_name: str | None = None, selection_from: Path | None = None,
                out_name: str = DEFAULT_OUT, allow_other_build: bool = False) -> dict:
    """Final bundle + trained defaults of the adopted candidate of ``<run>/<out_name>/summary.json``.

    The classifier is the run's final model (all subjects, fitted exactly like loso-v2's); b (candidates B, C) is
    :func:`fit_stage_bias` on the LOSO out-of-fold decodes of every Walch subject, both headline variants pooled
    (each night decoded by its own outer fold bundle), at the final decoder parameters (the run's most frequent
    inner choice; beta = :func:`mode_beta` over the outer folds of the headline variants). ``selection_from`` = the
    summary.json of the complete-protocol run whose selection (estimator scores, HMM gate) the generated
    TrainedDefaults documents; its decoder parameters and its chosen classifier / training configuration must equal
    this run's (the rest of ``selection.classifier``, e.g. the scores of configurations this run did not train, may
    differ). That check was narrowed from the whole ``selection.classifier`` after the experiment's results were
    known (ALGORITHM.md section 13.10); it does not affect the candidates or the decision."""
    ex = Experiment(run_dir, cli=cli, jobs=jobs, out_name=out_name, allow_other_build=allow_other_build)
    s = read_json(ex.out / "summary.json")
    dec = s["decision"]["adopted"]
    out_dir = Path(out_dir)
    name = run_name or out_dir.name
    if dec == "BASE":
        raise BiasError("the baseline was kept: there is no new final model to write")
    dp = ex.summary["selection"]["decoderParams"]
    theta = dict(dp["params"])
    selection = dict(ex.summary["selection"])
    if selection_from is not None:
        selection = dict(read_json(Path(selection_from))["selection"])
        other = selection.get("decoderParams", {}).get("params")
        mine = ex.summary["selection"]["classifier"]
        if (canonical_json(other) != canonical_json(theta) or selection["classifier"]["chosen"] != mine["chosen"]
                or selection["classifier"]["config"] != mine["config"]):
            raise BiasError(f"{selection_from}: decoder / classifier choice differs from {ex.run_dir}")
    decisions = read_json(ex.out / "decisions.json")["groups"]
    fold_choices = [{"beta": d["choices"][dec]["beta"], "innerKappa": d["choices"][dec]["innerKappa"]}
                    for d in decisions for _ in d["heldout"]]
    beta = mode_beta(fold_choices) if dec in ("A", "C") else 1.0
    result: dict = {"adopted": dec, "theta": theta, "beta": beta, "betaFolds": dict(Counter(
        f"{c['beta']:g}" for c in fold_choices))}
    bias = None
    if dec in ("B", "C"):
        walch = sorted({n.subject for v in ex.variants for n in ex.index.for_variant(v)
                        if n.dataset == report.HEADLINE_DATASET})
        counter = itertools.count()
        work = out_dir / "fit"

        def decode(b: np.ndarray) -> np.ndarray:
            n = next(counter)
            total = np.zeros((N_STAGES, N_STAGES))
            for v in ex.variants:
                folds = ex.hybrid_dir(v) / "folds"
                nights = ex.index.write_subset(v, walch, work / f"{v}.nights.jsonl")
                expected = [x.id for x in ex.index.nights_of(v, walch)]
                fm = folds if not np.any(b) else ex._write_biased(folds, walch, b.tolist(), work / f"{v}-{n}.folds")
                try:
                    recs = ex.decode(nights, expected, v, theta, beta, fm, work / f"{v}-{n}.json")
                finally:
                    if fm != folds:
                        shutil.rmtree(fm, ignore_errors=True)
                total += _pooled(recs)
            return total

        fit = fit_stage_bias(decode)
        bias = fit["bias"]
        result["fit"] = fit
    base_final = ex.run_dir / "final" / "hybrid_model.json"
    b = read_json(base_final)
    b = with_stage_log_bias(b, bias)
    b["modelId"] = f"pulsestages-{CONFIGS[ex.config].pool}-{ex.clf}-{name}"
    note = (f"stage-proportion experiment (tools/train/out/prereg-bias.md) on run {ex.cfg.run}: candidate {dec} "
            f"{CANDIDATE_LABEL[dec]} adopted; decoder {canonical_json({**theta, 'priorExponent': beta})}")
    if bias is not None:
        note += (f"; stageLogBias fitted on the LOSO out-of-fold decodes of the {report.HEADLINE_DATASET} subjects "
                 f"(both headline variants pooled) so that the decoded stage shares match the PSG shares")
    b["training"] = dict(b["training"], notes=b["training"]["notes"] + "; " + note)
    path = write_bundle(out_dir / "final" / "hybrid_model.json", b)
    defaults = {"estimator": "HYBRID", "hybrid": {**theta, "priorExponent": beta}}
    write_json(out_dir / "trained_defaults.json", defaults)
    sel = selection
    sel["priorExponent"] = {"note": f"pre-registered stage-proportion experiment, candidate {dec}: most frequent "
                                    f"inner-CV choice over the outer folds" if dec in ("A", "C") else
                                    f"pre-registered stage-proportion experiment, candidate {dec}: proportion "
                                    f"matching through the bundle's stageLogBias, beta stays 1"}
    meta = dict(ex.summary["meta"], run=name, createdAt=ex.summary["meta"].get("createdAt"))
    write_json(out_dir / "summary.json", {"meta": meta, "selection": sel, "bias": result | {"bundle": str(path)}})
    pf = write_json(out_dir / "final" / "final-params.json", deep_merge(dict(ex.cfg.base_params), defaults))
    m = ex.cli.evaluate(ex.cfg.nights_root / ex.variants[0], "HYBRID", out_dir / "final" / "resubstitution-metrics.json",
                        params=pf, model=path)
    recs = report.parse_metrics(m)
    report.check_estimator_used(recs, "HYBRID")
    result["resubstitutionNights"] = sum(1 for r in recs if r["estimated"])
    result["bundle"] = str(path)
    if install is not None:
        install = Path(install)
        install.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, install)
        result["installed"] = str(install)
    write_json(out_dir / "final" / "result.json", _clean(result))
    return result


__all__ = ["BETAS", "CANDIDATES", "PREREG_PATH", "PREREG_SHA256", "BiasError", "adopt", "choice_counts", "choose_beta",
           "fallbacks", "fit_stage_bias", "final_model", "inner_kappa_below_base", "mode_beta", "night_error",
           "pairwise_comparisons", "rerender", "run_experiment", "shares", "stage_minutes_errors", "summarize",
           "render", "AlgoCliError"]
