import json
import sys

import pytest

from conftest import make_launcher
from pulsetrain import algocli
from pulsetrain.algocli import AlgoCli, AlgoCliError, AlgoCliNotFound, AlgoCliUsageError
from synthnights import write_dataset

ECHO = """import json, sys
args = sys.argv[1:]
open(sys.argv[0] + '.args', 'w').write(json.dumps(args))
if '--out' in args:
    open(args[args.index('--out') + 1], 'w').write(json.dumps({'nights': [], 'args': args}))
"""


@pytest.fixture
def echo_cli(tmp_path):
    script = tmp_path / "echo.py"
    script.write_text(ECHO)
    launcher = make_launcher(tmp_path, f'exec "{sys.executable}" -I "{script}" "$@"')
    return AlgoCli(launcher, commands_log=tmp_path / "commands.log"), script


def test_locate_reports_build_hint(tmp_path, monkeypatch):
    monkeypatch.delenv("ALGO_CLI", raising=False)
    with pytest.raises(AlgoCliNotFound, match="installDist"):
        algocli.locate_launcher(tmp_path / "nope")
    monkeypatch.setenv("ALGO_CLI", str(tmp_path / "nope2"))
    with pytest.raises(AlgoCliNotFound, match=r"\$ALGO_CLI"):
        algocli.locate_launcher()
    f = tmp_path / "noexec"
    f.write_text("#!/bin/sh\n")
    f.chmod(0o644)
    with pytest.raises(AlgoCliNotFound, match="not executable"):
        algocli.locate_launcher(f)
    ok = make_launcher(tmp_path, "exit 0")
    monkeypatch.setenv("ALGO_CLI", str(ok))
    assert algocli.locate_launcher() == ok.resolve()
    assert any("pulsestages-algo" in str(p) for p in algocli.DEFAULT_LAUNCHERS)


def test_failures_carry_command_exit_code_and_stderr(tmp_path):
    bad = AlgoCli(make_launcher(tmp_path, 'echo "boom: bad night" >&2; exit 3'), commands_log=tmp_path / "c.log")
    with pytest.raises(AlgoCliError) as e:
        bad.features(tmp_path, tmp_path / "f.csv")
    msg = str(e.value)
    assert "exit code: 3" in msg and "boom: bad night" in msg and "features --nights" in msg
    assert " rc=3 " in (tmp_path / "c.log").read_text()
    (tmp_path / "u").mkdir()
    usage = AlgoCli(make_launcher(tmp_path / "u", "echo usage >&2; exit 64"))
    with pytest.raises(AlgoCliUsageError):
        usage.run(["evaluate"])
    silent = AlgoCli(make_launcher(tmp_path, "exit 0"))
    with pytest.raises(AlgoCliError, match="did not write"):
        silent.features(tmp_path, tmp_path / "missing.csv")
    with pytest.raises(AlgoCliError, match="did not write"):
        silent.evaluate(tmp_path, "RULES", tmp_path / "m.json")


def test_evaluate_command_line(echo_cli, tmp_path):
    cli, script = echo_cli
    (tmp_path / "folds").mkdir()
    res = cli.evaluate(tmp_path / "n.jsonl", "HYBRID", tmp_path / "m.json", params=tmp_path / "p.json",
                       fold_models=tmp_path / "folds", per_epoch=tmp_path / "pe.csv")
    assert res["args"] == ["evaluate", "--nights", str(tmp_path / "n.jsonl"), "--estimator", "HYBRID",
                           "--params", str(tmp_path / "p.json"), "--fold-models", str(tmp_path / "folds"),
                           "--out", str(tmp_path / "m.json"), "--per-epoch", str(tmp_path / "pe.csv")]
    cli.evaluate(tmp_path, "RULES", tmp_path / "m2.json", model=None)
    assert json.loads((tmp_path / "echo.py.args").read_text())[:5] == ["evaluate", "--nights", str(tmp_path),
                                                                        "--estimator", "RULES"]
    with pytest.raises(ValueError, match="HYBRID needs"):
        cli.evaluate(tmp_path, "HYBRID", tmp_path / "m.json")
    with pytest.raises(ValueError, match="mutually exclusive"):
        cli.evaluate(tmp_path, "HYBRID", tmp_path / "m.json", model=tmp_path / "a", fold_models=tmp_path / "folds")
    with pytest.raises(ValueError, match="estimator"):
        cli.evaluate(tmp_path, "HSMM", tmp_path / "m.json")
    with pytest.raises(ValueError, match="directory"):
        cli.evaluate(tmp_path, "HYBRID", tmp_path / "m.json", fold_models=tmp_path / "nofolds")
    lines = (tmp_path / "commands.log").read_text().splitlines()
    assert len(lines) == 2 and all(" rc=0 " in ln and "evaluate --nights" in ln for ln in lines)


def _grid_setup(tmp_path):
    write_dataset(tmp_path / "nights", n_walch=3, n_bid=0, low_coverage_night=False)
    pts = [{"id": "a", "params": {"hmm": {}}}, {"id": "b", "params": {}}]
    return tmp_path / "nights" / "instantaneous", pts


def test_param_grid_native(fake_cli, tmp_path):
    nights, pts = _grid_setup(tmp_path)
    cli = AlgoCli.locate(commands_log=tmp_path / "c.log", grid_mode="native")
    res = cli.evaluate_grid(nights, "RULES", tmp_path / "g.json", pts)
    assert [g["id"] for g in res["grid"]] == ["a", "b"] and len(res["grid"][0]["metrics"]["perNight"]) == 3
    assert [g["metrics"]["index"] for g in res["grid"]] == [0, 1]
    # the real algo-cli reads a JSON array of AlgoParams patches (docs/INTERCHANGE.md section 2)
    assert json.loads((tmp_path / "g.grid-input.json").read_text()) == [p["params"] for p in pts]
    assert (tmp_path / "c.log").read_text().count("--param-grid") == 1


def test_feature_names(fake_cli):
    info = AlgoCli.locate().feature_names()
    assert info["featureSetVersion"] == 7 and info["featureNames"][:2] == ["hrNorm", "hrVar"]


def test_param_grid_auto_fallback_to_loop(fake_cli, tmp_path, monkeypatch):
    nights, pts = _grid_setup(tmp_path)
    monkeypatch.setenv("FAKE_ALGO_CLI_NO_GRID", "1")
    with pytest.raises(AlgoCliUsageError):
        AlgoCli.locate(grid_mode="native").evaluate_grid(nights, "RULES", tmp_path / "g.json", pts)
    cli = AlgoCli.locate(commands_log=tmp_path / "c.log", grid_mode="auto")
    r1 = cli.evaluate_grid(nights, "RULES", tmp_path / "g1.json", pts)
    r2 = cli.evaluate_grid(nights, "RULES", tmp_path / "g2.json", pts)
    assert [g["id"] for g in r1["grid"]] == ["a", "b"] and r1["grid"][1]["metrics"] == r2["grid"][1]["metrics"]
    log = (tmp_path / "c.log").read_text()
    assert log.count("--param-grid") == 1 and log.count(" rc=64 ") == 1   # detected once, then sticky
    assert json.loads((tmp_path / "g1.a.params.json").read_text()) == {"hmm": {}}
    with pytest.raises(ValueError, match="unique"):
        cli.evaluate_grid(nights, "RULES", tmp_path / "g.json", pts + pts)


def test_order_grid_validation():
    pts = [{"id": "a", "params": {}}, {"id": "b", "params": {}}]
    out = algocli._order_grid({"grid": [{"index": 1, "perNight": []}, {"index": 0, "perNight": [1]}]}, pts)
    assert out["grid"][0]["metrics"]["perNight"] == [1] and out["grid"][1]["metrics"]["perNight"] == []
    assert [g["id"] for g in out["grid"]] == ["a", "b"]
    with pytest.raises(AlgoCliError, match="entries"):
        algocli._order_grid({"grid": [{}]}, pts)
    with pytest.raises(AlgoCliError, match="repeated"):
        algocli._order_grid({"grid": [{"index": 0}, {"index": 0}]}, pts)
    with pytest.raises(AlgoCliError, match="lacks"):
        algocli._order_grid({"grid": [{"index": 0}, {"index": 5}]}, pts)
    with pytest.raises(AlgoCliError, match="'grid'"):
        algocli._order_grid({"results": []}, pts)


def test_fingerprint_covers_the_launcher_and_the_install_jars(tmp_path):
    inst = tmp_path / "install"
    (inst / "bin").mkdir(parents=True)
    (inst / "lib").mkdir()
    launcher = make_launcher(inst / "bin", "exit 0")
    for name, body in (("core-algo.jar", b"a"), ("algo-cli.jar", b"b")):
        (inst / "lib" / name).write_bytes(body)
    cli = AlgoCli(launcher)
    fp = cli.fingerprint()
    assert fp["files"] == 3 and len(fp["sha256"]) == 64 and fp["launcher"] == str(launcher)
    assert cli.fingerprint() == fp                                     # stable
    (inst / "lib" / "core-algo.jar").write_bytes(b"rebuilt")             # a rebuild changes it
    assert cli.fingerprint()["sha256"] != fp["sha256"]
    bare = make_launcher(tmp_path, "exit 0")                             # e.g. the fake CLI: launcher only
    assert AlgoCli(bare).fingerprint()["files"] == 1
