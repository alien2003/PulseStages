import stat
import sys
from pathlib import Path

import pytest

FAKE = Path(__file__).resolve().parent / "fake_algo_cli.py"


def make_launcher(directory: Path, body: str) -> Path:
    p = directory / "algo-cli"
    p.write_text("#!/bin/sh\n" + body + "\n")
    p.chmod(p.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return p


@pytest.fixture
def fake_cli(tmp_path, monkeypatch) -> Path:
    """Executable wrapper around tests/fake_algo_cli.py, exported as $ALGO_CLI."""
    d = tmp_path / "bin"
    d.mkdir()
    p = make_launcher(d, f'exec "{sys.executable}" -I "{FAKE}" "$@"')
    monkeypatch.setenv("ALGO_CLI", str(p))
    monkeypatch.delenv("FAKE_ALGO_CLI_NO_GRID", raising=False)
    return p
