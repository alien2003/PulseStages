import hashlib
import zipfile

import pytest

import download


def _sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def test_parse_sums_accepts_dataset_layout():
    text = f"{'a' * 64} LICENSE.txt\n{'b' * 64} Bidslab00/1/hr.csv\n\n{'c' * 64} motion/46343_acceleration.txt\n"
    assert download.parse_sums(text) == {
        "LICENSE.txt": "a" * 64, "Bidslab00/1/hr.csv": "b" * 64, "motion/46343_acceleration.txt": "c" * 64}


@pytest.mark.parametrize("bad", ["../evil.txt", "/etc/passwd", "a/../../b", "a b.txt", "~/x", ".hidden"])
def test_parse_sums_rejects_unsafe_paths(bad):
    with pytest.raises(ValueError):
        download.parse_sums(f"{'a' * 64} {bad}\n")


def test_parse_sums_rejects_garbage():
    with pytest.raises(ValueError):
        download.parse_sums("not a checksum line\n")
    with pytest.raises(ValueError):
        download.parse_sums("\n")


def test_extract_verified_from_prefixed_zip(tmp_path):
    files = {"Bidslab00/1/motion.csv": b"Timestamp,x,y,z\n1,0,0,1\n", "Bidslab00/2/motion.csv": b"other\n"}
    zpath = tmp_path / "ds.zip"
    with zipfile.ZipFile(zpath, "w") as zf:
        for rel, data in files.items():
            zf.writestr("some-project-1.0.1/" + rel, data)
        zf.writestr("some-project-1.0.1/../../escape.txt", b"x")  # never extracted: not in the checksum list
    entries = {rel: _sha(d) for rel, d in files.items()}
    out = tmp_path / "out"
    assert download.extract_verified(zpath, out, entries, set(files)) == (2, 0)
    assert (out / "Bidslab00/1/motion.csv").read_bytes() == files["Bidslab00/1/motion.csv"]
    assert not (tmp_path / "escape.txt").exists()
    assert download.extract_verified(zpath, out, entries, set(files)) == (0, 2)  # idempotent


def test_extract_verified_rejects_checksum_mismatch(tmp_path):
    zpath = tmp_path / "ds.zip"
    with zipfile.ZipFile(zpath, "w") as zf:
        zf.writestr("a/motion.csv", b"tampered")
    with pytest.raises(RuntimeError, match="checksum mismatch"):
        download.extract_verified(zpath, tmp_path / "out", {"a/motion.csv": _sha(b"original")}, {"a/motion.csv"})
    assert not (tmp_path / "out" / "a" / "motion.csv").exists()
