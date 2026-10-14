"""Post-hoc analyses of the nap experiment (:mod:`pulsetrain.naps`), written for its audit. REPORTING ONLY: they
were not pre-registered and no decision depends on them (the adopted candidate stays the one the pre-registered rule
chose). Several of the numbers were first computed by the auditors, so they were known before this code ran.

1. **Sessions between 120 and 180 min.** With the app's default source filter (``minSessionMin`` 90 min) short nights
   of 90-180 min are estimated, and with T = 180 they get b_short; the pre-registered grid had no length between
   120 and 180 min. Proxies of :data:`BETWEEN_LENGTHS` of every held-out subject are decoded exactly as in the
   experiment with A (fold bundle + b_g) and with the adopted C-T kind (fold bundle + b_short_g(T)); the proxies of
   :data:`CHECK_LENGTHS` are decoded again and must reproduce the stored records (a check of the cut and the build).
2. **Threshold.** Paired adopted C-T minus the other C candidate on the experiment's outer results (T was chosen on
   them by the pre-registered rule).
3. **Rule with the context estimators.** RULES, HMM-table and the trivial "always LIGHT" (every chunk LIGHT, from the
   labels) scored by the pre-registered rule as if they had been candidates.
4. Low-confidence rates per length; 5. pooled stage shares of both variants and the REM excess; 6. both kappa
   definitions (mean per subject, chunk-pooled) per length and averaged over the rule lengths; 7. the final b_short
   fit against the fold-level fits.
8. Optionally a re-run of the experiment with the current code (``train.py naps --out-run <rerun>``), compared file
   by file with the stored run.

Writes ``summary.json``, ``results.jsonl`` (the new decodes) and ``report.md`` into the output directory.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Mapping, Sequence

import numpy as np

from . import report
from .algocli import AlgoCli
from .loso import map_threads
from .naps import (CANDIDATES, CONTEXT_ESTIMATORS, DATASETS, HYBRID_KINDS, LENGTHS, RULE_LENGTHS, Decoder, Inputs,
                   NapError, _clean, _read_jsonl, _write_jsonl, adopt, assemble, cells_of, comparisons,
                   expected_warnings, proxy_set, rule_scores, sanity_check_min_session_min)
from .util import LIGHT, STAGES, code_fingerprint, read_json, write_json

BETWEEN_LENGTHS: tuple[int, ...] = (150, 170)      # between the longest evaluated short length and T = 180
CHECK_LENGTHS: tuple[int, ...] = (120,)            # decoded again: must reproduce the stored records
LIGHT_KIND = "always-LIGHT"
RERUN_FILES = ("results.jsonl", "fits.json", "report.md", "repro/summary.json")


def always_light(recs: Sequence[Mapping]) -> list[dict]:
    """The trivial reference: the A records with every scored chunk predicted LIGHT (kappa 0, no flags)."""
    out = []
    for r in recs:
        if r["kind"] != "A":
            continue
        c = None
        if r["confusion"] is not None:
            m = np.asarray(r["confusion"], dtype=float)
            z = np.zeros_like(m)
            z[:, LIGHT] = m.sum(axis=1)
            c = z.tolist()
        out.append({**r, "kind": LIGHT_KIND, "warnings": [], "bias": None, "confusion": c})
    return out


def other_c(adopted: str) -> str | None:
    """The C candidate that was not adopted (C-120 for C-180), None for A / B."""
    if not adopted.startswith("C-"):
        return None
    rest = [c for c in CANDIDATES if c.startswith("C-") and c != adopted]
    return rest[0] if rest else None


def rem_excess(cells: Mapping, ref: Mapping, lengths: Sequence[int] = RULE_LENGTHS) -> dict:
    """REM over-call of ``cells`` (one candidate, dataset, variant; L -> cell) in percentage points of the pooled
    predicted minus true REM share, per length and summed over ``lengths`` (positive parts only); the fraction of
    ``ref``'s (A's) excess that remains, per length; the lengths where REM is under-called."""
    per, remain, under = {}, {}, []
    for L in lengths:
        c, a = cells[L], ref[L]
        d = 100 * (c["predShares"]["REM"] - c["trueShares"]["REM"])
        da = 100 * (a["predShares"]["REM"] - a["trueShares"]["REM"])
        per[str(L)] = d
        remain[str(L)] = max(d, 0.0) / da if da > 0 else float("nan")
        if d < 0:
            under.append(L)
    return {"perLength": per, "sum": float(sum(max(x, 0.0) for x in per.values())), "remainingOfRef": remain,
            "underCalled": under}


def compare_rerun(exp_dir: Path, rerun_dir: Path) -> dict:
    """File-by-file comparison of a re-run of the experiment with the stored run: :data:`RERUN_FILES` byte for byte,
    ``summary.json`` without its ``meta`` (which holds the build and code identity)."""
    exp_dir, rerun_dir = Path(exp_dir), Path(rerun_dir)
    same = {}
    for name in RERUN_FILES:
        a, b = exp_dir / name, rerun_dir / name
        same[name] = a.is_file() and b.is_file() and a.read_bytes() == b.read_bytes()
    sa, sb = read_json(exp_dir / "summary.json"), read_json(rerun_dir / "summary.json")
    same["summary.json without meta"] = ({k: v for k, v in sa.items() if k != "meta"}
                                         == {k: v for k, v in sb.items() if k != "meta"})
    ma, mb = sa.get("meta", {}), sb.get("meta", {})
    return {"rerun": str(rerun_dir), "identical": same, "allIdentical": all(same.values()),
            "metaKeysDiffering": sorted(k for k in set(ma) | set(mb) if ma.get(k) != mb.get(k)),
            "algoCliStored": (ma.get("algoCli") or {}).get("sha256"), "algoCliRerun": (mb.get("algoCli") or {}).get(
                "sha256"), "codeRerun": mb.get("code")}


def final_fit_quality(final_dir: Path, fits: Sequence[Mapping], threshold: int) -> dict:
    """The final b_short fit (``naps-final``) next to the fold-level fits of the same threshold."""
    s = read_json(Path(final_dir) / "summary.json")["naps"]
    f = s["fit"]
    fold = [x for x in fits if x["T"] == threshold]
    lo = np.min([x["bias"] for x in fold], axis=0)
    hi = np.max([x["bias"] for x in fold], axis=0)
    accepted = [t for t in f.get("trace", []) if t.get("accepted")]
    return {"bias": f["bias"], "D": f["D"], "decodes": f["decodes"], "bestIndex": f["bestIndex"],
            "predShares": dict(zip(STAGES, f["predShares"])), "trueShares": dict(zip(STAGES, f["trueShares"])),
            "acceptedRemShares": [t["predShares"][3] for t in accepted],
            "allRemShares": [t["predShares"][3] for t in f.get("trace", [])],
            "foldD": [float(min(x["D"] for x in fold)), float(max(x["D"] for x in fold))],
            "foldBiasMin": lo.tolist(), "foldBiasMax": hi.tolist(), "foldBiasMean": np.mean(
                [x["bias"] for x in fold], axis=0).tolist(),
            "withinFoldRange": bool(np.all((np.asarray(f["bias"]) >= lo) & (np.asarray(f["bias"]) <= hi)))}


def decode_between(inp: Inputs, cli: AlgoCli, exp_dir: Path, out: Path, candidate: str,
                   lengths: Sequence[int] = BETWEEN_LENGTHS, check_lengths: Sequence[int] = CHECK_LENGTHS,
                   jobs: int = 4) -> tuple[list[dict], dict]:
    """Proxies of ``check_lengths`` + ``lengths`` of every held-out subject decoded with A and with ``candidate``'s
    kind (fold bundle + b_short_g(T) as its stageLogBias, i.e. the bias on every session, as in the experiment);
    returns the records and the reproduction check of ``check_lengths`` against ``exp_dir/results.jsonl``
    (:class:`NapError` on a mismatch; a build that skips the sanity check on naps, ``napMode.maxSessionMin``
    (``post.proportionsMinSessionMin`` before the two modes), must reproduce the stored warnings without the
    ``SANITY_*`` codes of those sessions, :func:`expected_warnings`)."""
    exp_dir, out = Path(exp_dir), Path(out)
    threshold = int(candidate.split("-")[1])
    fits = {(f["variant"], f["key"], f["T"]): f["bias"] for f in read_json(exp_dir / "fits.json")["fits"]}
    dec = Decoder(inp, cli, out)
    all_lengths = [*check_lengths, *lengths]

    def one(task):
        g, kind = task
        ids = [n.id for n in inp.index.nights_of(g.variant, g.heldout)]
        nights, meta, missing = proxy_set(inp, g.variant, ids, all_lengths)
        if missing:
            raise NapError(f"{g.variant}/{g.key}: proxies could not be cut: {missing[:3]}")
        bias = g.bias if kind == "A" else fits[(g.variant, g.key, threshold)]
        d = out / "decodes" / g.variant
        nf = dec.write_nights(d / f"{g.key}.{kind}.nights.jsonl", nights)
        fm = dec.biased_models(inp.folds(g.variant), g.heldout, bias, d / f"{g.key}.{kind}.models")
        try:
            recs = dec.evaluate(nf, meta, "HYBRID", inp.params(g.theta), d / f"{g.key}.{kind}.json", fold_models=fm,
                                extra={"kind": kind, "group": g.key, "bias": bias})
        finally:
            nf.unlink(missing_ok=True)
            shutil.rmtree(fm, ignore_errors=True)
        return recs, sanity_check_min_session_min(read_json(d / f"{g.key}.{kind}.json"))

    parts = map_threads(one, [(g, k) for g in inp.groups for k in ("A", candidate)], jobs)
    recs = [r for rs, _ in parts for r in rs]
    check_min = {(r["kind"], r["variant"], r["night"]): c for rs, c in parts for r in rs}
    stored = {(r["kind"], r["variant"], r["night"]): r for r in _read_jsonl(exp_dir / "results.jsonl")
              if r["kind"] in ("A", candidate) and r["L"] in check_lengths}
    checked = [r for r in recs if r["L"] in check_lengths]

    def same(r: Mapping) -> bool:
        k = (r["kind"], r["variant"], r["night"])
        o = stored.get(k)
        return (o is not None and o["confusion"] == r["confusion"] and o["estimated"] == r["estimated"]
                and expected_warnings(o["warnings"], round(r["L"] * 60_000), check_min[k]) == r["warnings"])

    bad = [(r["kind"], r["variant"], r["night"]) for r in checked if not same(r)]
    if bad or len(checked) != len(stored):
        raise NapError(f"post-hoc decodes of L {list(check_lengths)} differ from the stored records: {len(bad)} of "
                       f"{len(checked)} (stored {len(stored)}), e.g. {bad[:3]}")
    return recs, {"lengths": list(check_lengths), "sessions": len(checked), "identical": len(checked) - len(bad),
                  "sanityCheckMinSessionMin": sorted(set(check_min.values()))}


def run_posthoc(inp: Inputs, cli: AlgoCli | None, exp_dir: Path, out: Path, final_dir: Path | None = None,
                rerun_dir: Path | None = None, lengths: Sequence[int] = BETWEEN_LENGTHS, jobs: int = 4) -> dict:
    """Every analysis of the module docstring; ``cli`` None skips the new decodes (item 1)."""
    exp_dir, out = Path(exp_dir), Path(out)
    out.mkdir(parents=True, exist_ok=True)
    s = read_json(exp_dir / "summary.json")
    adopted = s["decision"]["adopted"]
    if not adopted.startswith("C-"):
        raise NapError(f"the post-hoc analyses cover an adopted C candidate, not {adopted}")
    threshold = int(adopted.split("-")[1])
    variants = list(inp.variants)
    stored = _read_jsonl(exp_dir / "results.jsonl")
    fits = read_json(exp_dir / "fits.json")["fits"]
    ccells = cells_of(assemble([r for r in stored if r["kind"] in HYBRID_KINDS]), "candidate", CANDIDATES, variants)
    ext_names = (*CONTEXT_ESTIMATORS, LIGHT_KIND)
    xcells = cells_of([r for r in stored if r["kind"] in CONTEXT_ESTIMATORS] + always_light(stored), "kind",
                      ext_names, variants)
    res: dict = {"meta": {"exp": str(exp_dir), "expMeta": {k: s["meta"].get(k) for k in (
        "run", "algoCli", "code", "prereg", "preregSha256")}, "adopted": adopted, "threshold": threshold,
        "code": code_fingerprint(), "algoCli": cli.fingerprint() if cli is not None else None,
        "preRegistered": False, "note": "reporting only; not pre-registered; no decision depends on these numbers"}}

    # 1. sessions between the longest evaluated short length and T
    if cli is not None:
        fp = cli.fingerprint()
        recs, check = decode_between(inp, cli, exp_dir, out, adopted, lengths=lengths, jobs=jobs)
        if cli.fingerprint().get("sha256") != fp.get("sha256"):
            raise NapError("algo-cli changed during the post-hoc decodes")
        _write_jsonl(out / "results.jsonl", recs)
        all_l = [*CHECK_LENGTHS, *lengths]
        bcells = cells_of(recs, "kind", ("A", adopted), variants, lengths=all_l)
        res["between"] = {"lengths": list(lengths), "check": check, "cells": _cells_brief(bcells, all_l),
                          "comparisons": comparisons(bcells, variants, ref="A", names=(adopted,), lengths=all_l)}

    # 2. threshold: adopted C-T minus the other C
    other = other_c(adopted)
    if other is not None:
        res["threshold"] = {"candidate": adopted, "reference": other,
                            "comparisons": comparisons(ccells, variants, ref=other, names=(adopted,)),
                            "scores": {c: v for c, v in rule_scores(ccells, variants).items() if c in (adopted, other)}}

    # 3. the rule with the context estimators and "always LIGHT" as if they had been candidates
    scores = {**rule_scores(ccells, variants), **rule_scores(xcells, variants, names=ext_names)}
    res["ruleWithContext"] = {"scores": {c: {k: v for k, v in q.items() if k != "perLength"} for c, q in scores.items()},
                              "decision": adopt(scores, order=(*CANDIDATES, *ext_names)),
                              "kappaPooledOverL": {c: {v: cells[c][report.HEADLINE_DATASET][v]["pooled"]["kappa"]
                                                       for v in variants}
                                                   for cells, names in ((ccells, CANDIDATES), (xcells, ext_names))
                                                   for c in names}}

    # 4.-6. flags, shares and the REM excess, kappa definitions (Walch and BIDSleep)
    show = ("A", other, adopted) if other else ("A", adopted)
    res["cells"] = {c: {ds: _cells_brief({c: {ds: ccells[c][ds]}}, LENGTHS)[c][ds] for ds in DATASETS
                        if ds in ccells[c]} for c in show}
    res["remExcess"] = {ds: {c: {v: rem_excess(ccells[c][ds][v], ccells["A"][ds][v]) for v in variants}
                             for c in show} for ds in DATASETS if ds in ccells["A"]}
    for ds, per in res["remExcess"].items():
        for c, q in per.items():
            q["total"] = float(sum(q[v]["sum"] for v in variants))
    if final_dir is not None and (Path(final_dir) / "summary.json").is_file():
        res["finalFit"] = final_fit_quality(final_dir, fits, threshold)
    if rerun_dir is not None:
        res["rerun"] = compare_rerun(exp_dir, rerun_dir)
    write_json(out / "summary.json", _clean(res))
    (out / "report.md").write_text(render(res, variants), encoding="utf-8")
    return res


def _cells_brief(cells: Mapping, lengths: Sequence[int]) -> dict:
    """The per-length numbers the report shows (no per-subject maps)."""
    keep = ("sessions", "estimated", "notEstimated", "subjects", "kappaDefined", "kappa", "kappaPooled", "accuracy",
            "E", "trueShares", "predShares", "lowConfidenceRate", "sanityAnyRate", "flagRates")
    out: dict = {}
    for c, dd in cells.items():
        for ds, vv in dd.items():
            for v, per in vv.items():
                tgt = out.setdefault(c, {}).setdefault(ds, {}).setdefault(v, {})
                for L in lengths:
                    if L in per:
                        tgt[str(L)] = {k: per[L][k] for k in keep}
                if "pooled" in per:
                    p = per["pooled"]
                    tgt["pooled"] = {"accuracy": p["accuracy"], "E": p["E"], "kappa": p["kappa"],
                                     "kappaPooled": float(np.mean([per[L]["kappaPooled"] for L in RULE_LENGTHS])),
                                     "lowConfidenceRate": p["lowConfidenceRate"], "sanityAnyRate": p["sanityAnyRate"]}
    return out


# ----------------------------------------------------------------------------------------------- markdown ----

def _f(x, nd: int = 3) -> str:
    return report._f(x, nd)


def _pct(x) -> str:
    return "—" if x is None or not np.isfinite(x) else f"{100 * x:.1f}"


def _ci(x: Mapping | None, nd: int = 3) -> str:
    if not x or not x.get("n"):
        return "—"
    return f"{_f(x['meanDiff'], nd)} [{_f(x['ciLow'], nd)}, {_f(x['ciHigh'], nd)}]"


def _sh(d: Mapping | None) -> str:
    return "—" if not d else " / ".join(_pct(d[s]) for s in STAGES)


def render(r: Mapping, variants: Sequence[str]) -> str:
    m = r["meta"]
    a, t = m["adopted"], m["threshold"]
    L = [f"# Nap experiment: post-hoc analyses (reporting only, not pre-registered) — `{m['exp']}`", "",
         f"Adopted candidate {a} (sessions shorter than {t} min use b_short). Nothing here changes the decision of the "
         "pre-registered rule; several numbers were first computed by the auditors. E = mean per-session summed "
         "absolute stage-share error (pp); accuracy pooled over the chunks of a length; κ subj = mean per-subject "
         "Cohen's kappa where defined (what `report.md` calls κ), κ chunks = kappa of the chunk-pooled confusion "
         "matrix (`report.md`: κ pooled).", ""]
    b = r.get("between")
    if b:
        ck = b["check"]
        L += [f"## 1. Sessions between {ck['lengths'][-1]} and {t} min (proxies of {', '.join(map(str, b['lengths']))} "
              "min)", "",
              f"A = fold bundle + b_g, {a} = fold bundle + b_short_g({t}) (what the shipped rule applies to a session "
              f"shorter than {t} min). Check: the {', '.join(map(str, ck['lengths']))}-min proxies decoded again, "
              f"{ck['identical']} of {ck['sessions']} identical to the stored records.", "",
              "| dataset | variant | L | A acc / E | " + a + " acc / E | A κ subj / chunks | " + a
              + " κ subj / chunks | REM % PSG / A / " + a + " | low-conf. % A / " + a + " |",
              "|---|---|---|---|---|---|---|---|---|"]
        for ds in DATASETS:
            for v in variants:
                for key in map(str, [*ck["lengths"], *b["lengths"]]):
                    ca = b["cells"].get("A", {}).get(ds, {}).get(v, {}).get(key)
                    cc = b["cells"].get(a, {}).get(ds, {}).get(v, {}).get(key)
                    if not ca or not cc:
                        continue
                    L.append(f"| {ds} | {v} | {key} | {_f(ca['accuracy'])} / {_f(ca['E'], 1)} | {_f(cc['accuracy'])} "
                             f"/ {_f(cc['E'], 1)} | {_f(ca['kappa'])} / {_f(ca['kappaPooled'])} | {_f(cc['kappa'])} / "
                             f"{_f(cc['kappaPooled'])} | {_pct(ca['trueShares']['REM'])} / "
                             f"{_pct(ca['predShares']['REM'])} / {_pct(cc['predShares']['REM'])} | "
                             f"{_pct(ca['lowConfidenceRate'])} / {_pct(cc['lowConfidenceRate'])} |")
        L += ["", f"Paired {a} − A (95 % bootstrap CI, 10 000 resamples of subjects, seed 0):", "",
              "| dataset | variant | L | ΔE pp | Δaccuracy | Δκ subj |", "|---|---|---|---|---|---|"]
        for x in b["comparisons"]:
            if x["L"] == "pooled":
                continue
            L.append(f"| {x['dataset']} | {x['variant']} | {x['L']} | {_ci(x['E'], 1)} | {_ci(x['accuracy'])} | "
                     f"{_ci(x.get('kappa'))} |")
        L.append("")
    th = r.get("threshold")
    if th:
        c, o = th["candidate"], th["reference"]
        L += [f"## 2. Threshold: {c} − {o} (outer held-out results, the data T was chosen on)", "",
              "| dataset | variant | L | ΔE pp | Δaccuracy | Δκ subj |", "|---|---|---|---|---|---|"]
        for x in th["comparisons"]:
            L.append(f"| {x['dataset']} | {x['variant']} | {x['L']} | {_ci(x['E'], 1)} | {_ci(x['accuracy'])} | "
                     f"{_ci(x.get('kappa'))} |")
        L.append("")
    rc = r["ruleWithContext"]
    d = rc["decision"]
    L += ["## 3. The pre-registered rule with the context estimators as if they had been candidates", "",
          f"Walch, worse variant, pooled over L 30–120. Threshold {_f(d['threshold'])}; qualifying: "
          f"{', '.join(d['qualifying'])}; the rule would pick **{d['adopted']}**. The rule does not use κ; κ subj "
          "(pooled over L, instantaneous / mean2min) is shown for context.", "",
          "| candidate / estimator | accuracy worse | E worse | κ subj inst. / mean2min |", "|---|---|---|---|"]
    for name, q in rc["scores"].items():
        k = rc["kappaPooledOverL"].get(name, {})
        L.append(f"| {name} | {_f(q['accWorse'])} | {_f(q['EWorse'], 1)} | "
                 + " / ".join(_f(k.get(v)) for v in variants) + " |")
    L.append("")
    L += ["## 4.–6. Flags, stage shares, κ (outer results of the experiment)", "",
          "| dataset | candidate | variant | L | accuracy | E | κ subj | κ chunks | low-conf. % | sanity % | "
          "LOW_HR_RANGE % | predicted A / L / D / R % | PSG A / L / D / R % |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for name, dd in r["cells"].items():
        for ds, vv in dd.items():
            for v in variants:
                for key, c in vv.get(v, {}).items():
                    if key == "pooled":
                        L.append(f"| {ds} | {name} | {v} | 30–120 (mean over L) | {_f(c['accuracy'])} | {_f(c['E'], 1)} "
                                 f"| {_f(c['kappa'])} | {_f(c['kappaPooled'])} | {_pct(c['lowConfidenceRate'])} | "
                                 f"{_pct(c['sanityAnyRate'])} | — | — | — |")
                        continue
                    L.append(f"| {ds} | {name} | {v} | {key} | {_f(c['accuracy'])} | {_f(c['E'], 1)} | {_f(c['kappa'])} "
                             f"| {_f(c['kappaPooled'])} | {_pct(c['lowConfidenceRate'])} | {_pct(c['sanityAnyRate'])} | "
                             f"{_pct(c['flagRates']['LOW_HR_RANGE'])} | {_sh(c['predShares'])} | "
                             f"{_sh(c['trueShares'])} |")
    L += ["", "REM over-call (pooled predicted − PSG REM share, pp; summed positive parts over L 30–120; remaining = "
          "share of A's excess left at that length; under-called = predicted below PSG):", "",
          "| dataset | candidate | variant | per L 30 / 60 / 90 / 120 | sum | remaining of A per L | under-called at L |",
          "|---|---|---|---|---|---|---|"]
    for ds, per in r["remExcess"].items():
        for name, q in per.items():
            for v in variants:
                x = q[v]
                L.append(f"| {ds} | {name} | {v} | " + " / ".join(f"{x['perLength'][str(k)]:+.1f}" for k in RULE_LENGTHS)
                         + f" | {x['sum']:.1f} | " + " / ".join(_pct(x['remainingOfRef'][str(k)]) for k in RULE_LENGTHS)
                         + f" | {', '.join(map(str, x['underCalled'])) or '—'} |")
            L.append(f"| {ds} | {name} | both | | **{q['total']:.1f}** | | |")
    L.append("")
    ff = r.get("finalFit")
    if ff:
        L += ["## 7. The shipped b_short against the fold-level fits", "",
              f"Final fit (all Walch subjects, both variants): b_short = ({', '.join(f'{x:+.3f}' for x in ff['bias'])}), "
              f"D = {_f(ff['D'], 4)} after {ff['decodes']} decodes (best step {ff['bestIndex']}); decoded shares "
              f"{_sh(ff['predShares'])} % vs PSG {_sh(ff['trueShares'])} % (in-sample). REM share over the accepted "
              f"steps: {', '.join(_pct(x) for x in ff['acceptedRemShares'])} %; over all decodes "
              f"{_pct(min(ff['allRemShares']))}–{_pct(max(ff['allRemShares']))} %. Fold-level fits (T = {t}): D "
              f"{_f(ff['foldD'][0], 4)}–{_f(ff['foldD'][1], 4)}; per stage "
              + ", ".join(f"{s} {lo:+.2f} … {hi:+.2f}" for s, lo, hi in zip(STAGES, ff['foldBiasMin'], ff['foldBiasMax']))
              + f"; mean ({', '.join(f'{x:+.3f}' for x in ff['foldBiasMean'])}); the shipped values are "
              + ("inside" if ff["withinFoldRange"] else "outside") + " that range.", ""]
    rr = r.get("rerun")
    if rr:
        L += ["## 8. Re-run of the experiment with the current code", "",
              f"`{rr['rerun']}` (algo-cli {str(rr['algoCliRerun'])[:8]}, stored run {str(rr['algoCliStored'])[:8]}; "
              f"code {str((rr.get('codeRerun') or {}).get('sha256'))[:12]}): "
              + ", ".join(f"{k} {'identical' if v else 'DIFFERENT'}" for k, v in rr["identical"].items())
              + f"; meta keys that differ: {', '.join(rr['metaKeysDiffering']) or 'none'}.", ""]
    return "\n".join(L)


__all__ = ["BETWEEN_LENGTHS", "CHECK_LENGTHS", "LIGHT_KIND", "always_light", "compare_rerun", "decode_between",
           "final_fit_quality", "other_c", "rem_excess", "run_posthoc"]
