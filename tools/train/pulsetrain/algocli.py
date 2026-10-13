"""Thin wrapper around the Kotlin ``algo-cli`` launcher (docs/INTERCHANGE.md section 2).

Kotlin is the single source of truth for features and decoders; this module only builds command lines, runs them,
logs every command (with duration and exit code) and parses the JSON they write.

Launcher lookup (first hit wins): explicit path, ``$ALGO_CLI``, then the ``installDist`` outputs
``<repo>/algo-cli/build/install/algo-cli/bin/algo-cli`` and ``<repo>/algo-cli/build/install/pulsestages-algo/bin/
pulsestages-algo`` (the Gradle ``applicationName``). Build it with ``mise exec -- ./gradlew :algo-cli:installDist``.

``evaluate --param-grid <grid.json>`` (one JVM run for many decoder settings) reads a JSON array of partial
AlgoParams objects ("patches", each deep-merged over the base parameters) and writes ``{"grid": [block, ...]}``,
one metrics block per patch with ``index`` (position in the array) and ``patch`` added (docs/INTERCHANGE.md
section 2). :meth:`AlgoCli.evaluate_grid` returns ``{"grid": [{"id", "params", "metrics"}, ...]}`` in the order of
the points. With ``grid_mode="auto"`` an algo-cli that rejects the flag (exit code 64 = EX_USAGE) is detected once
per process and the grid is evaluated point by point with ``--params`` instead (same output structure).
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import shlex
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Sequence

from .util import write_json

log = logging.getLogger(__name__)

ENV_VAR = "ALGO_CLI"
REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_LAUNCHERS = (
    REPO_ROOT / "algo-cli" / "build" / "install" / "algo-cli" / "bin" / "algo-cli",
    REPO_ROOT / "algo-cli" / "build" / "install" / "pulsestages-algo" / "bin" / "pulsestages-algo",
)
ESTIMATORS = ("RULES", "HMM", "HYBRID")
EX_USAGE = 64
BUILD_HINT = "build it with `mise exec -- ./gradlew :algo-cli:installDist` or point $ALGO_CLI at the launcher"


class AlgoCliError(RuntimeError):
    """algo-cli failed (non-zero exit, timeout, missing or invalid output)."""

    def __init__(self, message: str, cmd: Sequence[str] | None = None, returncode: int | None = None,
                 stderr: str = ""):
        self.cmd = list(cmd) if cmd else []
        self.returncode = returncode
        self.stderr = stderr
        parts = [message]
        if cmd:
            parts.append("command: " + shlex.join(self.cmd))
        if returncode is not None:
            parts.append(f"exit code: {returncode}")
        tail = "\n".join(stderr.strip().splitlines()[-40:])
        if tail:
            parts.append("stderr (last lines):\n" + tail)
        super().__init__("\n".join(parts))


class AlgoCliNotFound(AlgoCliError):
    pass


class AlgoCliUsageError(AlgoCliError):
    """Exit code 64: the launcher does not understand the command line (e.g. an unsupported flag)."""


def locate_launcher(explicit: str | Path | None = None) -> Path:
    candidates: list[tuple[str, Path]] = []
    if explicit:
        candidates.append(("explicit path", Path(explicit)))
    elif os.environ.get(ENV_VAR):
        candidates.append((f"${ENV_VAR}", Path(os.environ[ENV_VAR])))
    else:
        candidates.extend(("default", p) for p in DEFAULT_LAUNCHERS)
    for _, p in candidates:
        if p.is_file():
            if not os.access(p, os.X_OK):
                raise AlgoCliNotFound(f"algo-cli launcher {p} is not executable (chmod +x, or rebuild: {BUILD_HINT})")
            return p.resolve()
    tried = ", ".join(f"{p} ({how})" for how, p in candidates)
    raise AlgoCliNotFound(f"algo-cli launcher not found; tried {tried}; {BUILD_HINT}")


@dataclass
class AlgoCli:
    launcher: Path
    commands_log: Path | None = None
    timeout_s: float | None = None
    grid_mode: str = "auto"                   # auto | native | loop
    extra_env: dict[str, str] = field(default_factory=dict)
    _grid_native_ok: bool | None = field(default=None, repr=False)

    @classmethod
    def locate(cls, explicit: str | Path | None = None, **kw) -> "AlgoCli":
        return cls(launcher=locate_launcher(explicit), **kw)

    def fingerprint(self) -> dict:
        """Identity of the build behind the launcher: SHA-256 over the launcher script and, for the ``installDist``
        layout, every ``../lib/*.jar`` (name + content, sorted by name). Recorded in each run's ``config.json`` and
        re-checked after every phase (a rebuild during a run is an error)."""
        files = [self.launcher]
        lib = self.launcher.parent.parent / "lib"
        if lib.is_dir():
            files += sorted(lib.glob("*.jar"))
        h = hashlib.sha256()
        newest = 0.0
        for f in files:
            h.update(f.name.encode() + b"\0")
            h.update(hashlib.sha256(f.read_bytes()).digest())
            newest = max(newest, f.stat().st_mtime)
        return {"launcher": str(self.launcher), "sha256": h.hexdigest(), "files": len(files),
                "newestFileTime": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(newest))}

    # ------------------------------------------------------------------------------------------- execution ----

    def _log_command(self, cmd: list[str], rc: int | None, seconds: float) -> None:
        line = (f"{time.strftime('%Y-%m-%dT%H:%M:%S')} pid={os.getpid()} rc={rc} {seconds:.2f}s :: "
                f"{shlex.join(cmd)}\n")
        if self.commands_log is not None:
            self.commands_log.parent.mkdir(parents=True, exist_ok=True)
            with open(self.commands_log, "a", encoding="utf-8") as f:
                f.write(line)

    def run(self, args: Sequence[str]) -> subprocess.CompletedProcess:
        cmd = [str(self.launcher), *map(str, args)]
        log.debug("algo-cli: %s", shlex.join(cmd))
        env = dict(os.environ, **self.extra_env)
        t0 = time.monotonic()
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=self.timeout_s, env=env)
        except subprocess.TimeoutExpired as e:
            self._log_command(cmd, None, time.monotonic() - t0)
            raise AlgoCliError(f"algo-cli timed out after {self.timeout_s} s", cmd,
                               stderr=(e.stderr or b"").decode() if isinstance(e.stderr, bytes) else (e.stderr or ""))
        except OSError as e:
            self._log_command(cmd, None, time.monotonic() - t0)
            raise AlgoCliNotFound(f"cannot execute algo-cli launcher {self.launcher}: {e}; {BUILD_HINT}", cmd)
        self._log_command(cmd, proc.returncode, time.monotonic() - t0)
        if proc.returncode == EX_USAGE:
            raise AlgoCliUsageError("algo-cli rejected the command line (usage error)", cmd, proc.returncode,
                                    proc.stderr)
        if proc.returncode != 0:
            raise AlgoCliError("algo-cli failed", cmd, proc.returncode, proc.stderr)
        return proc

    # -------------------------------------------------------------------------------------------- commands ----

    def features(self, nights: Path, out: Path, params: Path | None = None) -> Path:
        """``algo-cli features --nights <dir|jsonl> --out <csv> [--params <json>]``."""
        out = Path(out)
        out.parent.mkdir(parents=True, exist_ok=True)
        args = ["features", "--nights", str(nights), "--out", str(out)]
        if params is not None:
            args += ["--params", str(params)]
        self.run(args)
        if not out.is_file():
            raise AlgoCliError(f"algo-cli features did not write {out}", [str(self.launcher), *args])
        return out

    def feature_names(self) -> dict:
        """``algo-cli feature-names`` -> ``{"algoVersion", "featureSetVersion", "featureNames"}``."""
        proc = self.run(["feature-names"])
        try:
            info = json.loads(proc.stdout)
        except json.JSONDecodeError as e:
            raise AlgoCliError(f"algo-cli feature-names printed invalid JSON: {e}", [str(self.launcher), "feature-names"])
        if (not isinstance(info, dict) or not isinstance(info.get("featureSetVersion"), int)
                or not isinstance(info.get("featureNames"), list)):
            raise AlgoCliError("algo-cli feature-names: expected {featureSetVersion: int, featureNames: [...]}",
                               [str(self.launcher), "feature-names"])
        return info

    def evaluate(self, nights: Path, estimator: str, out: Path, *, params: Path | None = None,
                 model: Path | None = None, fold_models: Path | None = None, per_epoch: Path | None = None,
                 param_grid: Path | None = None) -> dict:
        """``algo-cli evaluate``; returns the parsed metrics JSON."""
        if estimator not in ESTIMATORS:
            raise ValueError(f"estimator must be one of {ESTIMATORS}, got {estimator!r}")
        if model is not None and fold_models is not None:
            raise ValueError("--model and --fold-models are mutually exclusive")
        if estimator == "HYBRID" and model is None and fold_models is None:
            raise ValueError("HYBRID needs --model or --fold-models (algo-cli would silently fall back to HMM)")
        if fold_models is not None and not Path(fold_models).is_dir():
            raise ValueError(f"--fold-models must be a directory with <subject>.json files: {fold_models}")
        out = Path(out)
        out.parent.mkdir(parents=True, exist_ok=True)
        args = ["evaluate", "--nights", str(nights), "--estimator", estimator]
        if params is not None:
            args += ["--params", str(params)]
        if model is not None:
            args += ["--model", str(model)]
        if fold_models is not None:
            args += ["--fold-models", str(fold_models)]
        if param_grid is not None:
            args += ["--param-grid", str(param_grid)]
        args += ["--out", str(out)]
        if per_epoch is not None:
            args += ["--per-epoch", str(per_epoch)]
        self.run(args)
        try:
            return json.loads(out.read_text(encoding="utf-8"))
        except FileNotFoundError:
            raise AlgoCliError(f"algo-cli evaluate did not write {out}", [str(self.launcher), *args])
        except json.JSONDecodeError as e:
            raise AlgoCliError(f"algo-cli evaluate wrote invalid JSON to {out}: {e}", [str(self.launcher), *args])

    def evaluate_grid(self, nights: Path, estimator: str, out: Path, points: Sequence[dict], *,
                      model: Path | None = None, fold_models: Path | None = None) -> dict:
        """Evaluates every grid point (each ``{"id", "params"}`` with a complete AlgoParams patch).

        Returns ``{"grid": [{"id", "params", "metrics"}, ...]}`` in the order of ``points``.
        """
        ids = [p["id"] for p in points]
        if len(set(ids)) != len(ids):
            raise ValueError("grid point ids must be unique")
        out = Path(out)
        mode = self.grid_mode
        if mode == "auto":
            mode = "loop" if self._grid_native_ok is False else "native"
        if mode == "native":
            grid_file = out.with_name(out.stem + ".grid-input.json")
            write_json(grid_file, [p["params"] for p in points])
            try:
                res = self.evaluate(nights, estimator, out, model=model, fold_models=fold_models,
                                    param_grid=grid_file)
                self._grid_native_ok = True
                return _order_grid(res, points)
            except AlgoCliUsageError:
                if self.grid_mode != "auto":
                    raise
                log.warning("algo-cli does not accept --param-grid; evaluating the grid point by point")
                self._grid_native_ok = False
        if mode not in ("native", "loop"):
            raise ValueError(f"grid_mode must be auto|native|loop, got {self.grid_mode!r}")
        results = []
        for p in points:
            pf = out.with_name(f"{out.stem}.{p['id']}.params.json")
            write_json(pf, p["params"])
            m = self.evaluate(nights, estimator, out.with_name(f"{out.stem}.{p['id']}.json"), params=pf,
                              model=model, fold_models=fold_models)
            results.append({"id": p["id"], "params": p["params"], "metrics": m})
        res = {"grid": results}
        write_json(out, res, pretty=False)
        return res


def _order_grid(res: Any, points: Sequence[dict]) -> dict:
    """Validates a native --param-grid answer (``{"grid": [block, ...]}``, each block with ``index``) and returns
    it as ``{"grid": [{"id", "params", "metrics"}]}`` ordered like ``points``."""
    items = res.get("grid") if isinstance(res, dict) else None
    if not isinstance(items, list):
        raise AlgoCliError("--param-grid output has no 'grid' list")
    if len(items) != len(points):
        raise AlgoCliError(f"--param-grid output has {len(items)} entries for {len(points)} points")
    by_index: dict[int, dict] = {}
    for i, it in enumerate(items):
        if not isinstance(it, dict):
            raise AlgoCliError("--param-grid entries must be objects")
        idx = it.get("index", i)
        if not isinstance(idx, int) or isinstance(idx, bool) or idx in by_index:
            raise AlgoCliError(f"--param-grid entry {i}: invalid or repeated index {idx!r}")
        by_index[idx] = it
    missing = [i for i in range(len(points)) if i not in by_index]
    if missing:
        raise AlgoCliError(f"--param-grid output lacks entries {missing[:5]}")
    return {"grid": [{"id": p["id"], "params": p["params"], "metrics": by_index[i]} for i, p in enumerate(points)]}
