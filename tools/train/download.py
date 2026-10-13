#!/usr/bin/env python3
"""Download the open wearable + sleep-stage datasets used to train / validate PulseStages (see DATASETS.md).

--dataset sleep-accel (default, primary)
    PhysioNet "Motion and heart rate from a wrist-worn wearable and labeled sleep from polysomnography"
    (Walch et al. 2019), v1.0.0, Open Data Commons Attribution License v1.0. 31 subjects, ~2.2 GB.
    Files land in data/raw/sleep-accel/1.0.0/. The checksum list is always fetched from physionet.org (the
    publisher); the files from the official PhysioNet open-data mirror on AWS S3 (s3://physionet-open, listed on the
    project page under "Download the files using AWS command line tools"), because physionet.org throttles bulk
    downloads to ~10-20 kB/s per connection. ``--source physionet`` fetches everything from physionet.org. A mirror
    file that does not match the publisher's checksum is rejected and re-fetched from physionet.org.

--dataset bidsleep
    PhysioNet "A Multi-Night Instantaneous Heart Rate and Accelerometry Dataset with EEG Sleep Stage Labels"
    (BIDSleep, Song et al. 2026), v1.0.1, ODC-By 1.0. 47 subjects / 253 nights, 27.9 GB uncompressed (motion.csv is
    ~99 % of it), 6.35 GB zip. No S3 mirror exists, so everything comes from physionet.org with resumable
    ``curl -C -``.
      --parts hr,labels   (default) per-file download of hr.csv + labels.mat for every night (~40 MB) and the
                          README / LICENSE.
      --parts all         additionally the motion (accelerometer, needed for steps): downloads the project zip
                          (resumable; ``--connections N`` splits it into N byte ranges, each resumable, max 8),
                          then verifies every member against SHA256SUMS.txt while extracting it.

--dataset dreamt
    Restricted access (PhysioNet Restricted Health Data License 1.5.0 + signed DUA): deliberately NOT downloadable
    here; the option only explains why.

Every file is verified against the dataset's SHA256SUMS.txt; files that already verify are skipped, so the script is
idempotent and resumable. Downloaded data is untrusted: this script only writes bytes to disk and hashes them;
nothing is executed or parsed beyond the checksum list (zip members are matched by name against it). Run it with
``python -I``.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path

TRAIN_DIR = Path(__file__).resolve().parent
DEFAULT_RAW_DIR = TRAIN_DIR / "data" / "raw"
USER_AGENT = "PulseStages-train-download/1.0 (+https://physionet.org/content/sleep-accel/)"
CHUNK = 1 << 20


@dataclass(frozen=True)
class Dataset:
    slug: str
    version: str
    title: str
    landing_url: str
    license_name: str
    license_url: str
    citation: str
    original_publication: str
    physionet_files_url: str
    s3_files_url: str


SLEEP_ACCEL = Dataset(
    slug="sleep-accel",
    version="1.0.0",
    title="Motion and heart rate from a wrist-worn wearable and labeled sleep from polysomnography",
    landing_url="https://physionet.org/content/sleep-accel/1.0.0/",
    license_name="Open Data Commons Attribution License v1.0 (ODC-By 1.0)",
    license_url="https://physionet.org/content/sleep-accel/view-license/1.0.0/",
    citation=(
        "Walch, O. (2019). Motion and heart rate from a wrist-worn wearable and labeled sleep from "
        "polysomnography (version 1.0.0). PhysioNet. RRID:SCR_007345. https://doi.org/10.13026/hmhs-py35"
    ),
    original_publication=(
        "Olivia Walch, Yitong Huang, Daniel Forger, Cathy Goldstein. Sleep stage prediction with raw acceleration "
        "and photoplethysmography heart rate data derived from a consumer wearable device. SLEEP 42(12), 2019, "
        "zsz180. https://doi.org/10.1093/sleep/zsz180"
    ),
    physionet_files_url="https://physionet.org/files/sleep-accel/1.0.0/",
    s3_files_url="https://physionet-open.s3.amazonaws.com/sleep-accel/1.0.0/",
)

BIDSLEEP = Dataset(
    slug="bidsleep-dataset",
    version="1.0.1",
    title="A Multi-Night Instantaneous Heart Rate and Accelerometry Dataset with EEG Sleep Stage Labels (BIDSleep)",
    landing_url="https://physionet.org/content/bidsleep-dataset/1.0.1/",
    license_name="Open Data Commons Attribution License v1.0 (ODC-By 1.0)",
    license_url="https://physionet.org/content/bidsleep-dataset/view-license/1.0.1/",
    citation=(
        "Song, T., Zhang, Y., Zhou, Z., & Dutta, J. (2026). A Multi-Night Instantaneous Heart Rate and Accelerometry "
        "Dataset with EEG Sleep Stage Labels (version 1.0.1). PhysioNet. RRID:SCR_007345. "
        "https://doi.org/10.13026/rees-1092"
    ),
    original_publication=(
        "Song TA, Zhang Y, Zhou Z, Hou L, Malekzadeh M, Behzad A, Dutta J. AI-driven sleep staging using "
        "instantaneous heart rate and accelerometry: insights from an Apple Watch study. IEEE Trans Biomed Eng. "
        "2026;73(4):1596-1608. https://doi.org/10.1109/TBME.2025.3612158"
    ),
    physionet_files_url="https://physionet.org/files/bidsleep-dataset/1.0.1/",
    s3_files_url="",
)
BIDSLEEP_ZIP_URL = "https://physionet.org/content/bidsleep-dataset/get-zip/1.0.1/"
BIDSLEEP_PARTS = {"hr": "hr.csv", "labels": "labels.mat", "motion": "motion.csv"}
MAX_CONNECTIONS = 8  # be polite to physionet.org


PHYSIONET_PLATFORM_CITATION = (
    "Pollard, T., Moody, B. E., Lehman, L., Gow, B., Fernandes, C., Xie, C., Johnson, A., Mark, R. G., & Heldt, T. "
    "(2026). PhysioNet as a global platform for biomedical research. Nature Health. "
    "https://doi.org/10.1038/s44360-026-00096-z"
)

DREAMT_NOTICE = """DREAMT (https://physionet.org/content/dreamt/, latest version 2.2.0) is a RESTRICTED-ACCESS PhysioNet
project: "Only registered users who sign the specified data use agreement can access the files."
License: PhysioNet Restricted Health Data License 1.5.0 + PhysioNet Restricted Health Data Use Agreement 1.5.0.
PulseStages only uses open-access data, so this script does not download it. See tools/train/DATASETS.md."""

# Only plain relative paths made of safe characters are accepted from the (untrusted) checksum list.
SAFE_PATH = re.compile(r"^(?!/)(?!.*\.\.)[A-Za-z0-9_][A-Za-z0-9_./-]*$")
SUMS_LINE = re.compile(r"^([0-9a-f]{64})\s+\*?(\S+)$")


def log(msg: str) -> None:
    print(msg, flush=True)


def http_get(url: str, dest: Path | None, timeout: float = 60.0) -> bytes | None:
    """GET [url]; stream to [dest] if given (returns None), else return the body."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 - fixed https URLs only
        if resp.status != 200:
            raise urllib.error.HTTPError(url, resp.status, "unexpected status", resp.headers, None)
        if dest is None:
            return resp.read()
        with open(dest, "wb") as fh:
            while True:
                buf = resp.read(CHUNK)
                if not buf:
                    break
                fh.write(buf)
    return None


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            buf = fh.read(CHUNK)
            if not buf:
                break
            h.update(buf)
    return h.hexdigest()


def parse_sums(text: str) -> dict[str, str]:
    entries: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        m = SUMS_LINE.match(line)
        if not m:
            raise ValueError(f"unparseable SHA256SUMS line: {line!r}")
        digest, rel = m.group(1), m.group(2)
        if not SAFE_PATH.match(rel):
            raise ValueError(f"refusing unsafe path in SHA256SUMS: {rel!r}")
        entries[rel] = digest
    if not entries:
        raise ValueError("empty SHA256SUMS")
    return entries


def fetch_sums(ds: Dataset, out_dir: Path) -> dict[str, str]:
    sums_path = out_dir / "SHA256SUMS.txt"
    try:
        body = http_get(ds.physionet_files_url + "SHA256SUMS.txt", None, timeout=120)
        assert body is not None
        source = "physionet.org"
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        if sums_path.exists():
            log(f"WARNING: could not refresh SHA256SUMS.txt from physionet.org ({exc}); using the local copy")
            return parse_sums(sums_path.read_text(encoding="ascii"))
        raise SystemExit(f"ERROR: cannot fetch SHA256SUMS.txt from physionet.org: {exc}") from exc
    entries = parse_sums(body.decode("ascii"))
    tmp = sums_path.with_suffix(".txt.part")
    tmp.write_bytes(body)
    os.replace(tmp, sums_path)
    log(f"SHA256SUMS.txt from {source}: {len(entries)} files listed")
    return entries


def download_one(ds: Dataset, out_dir: Path, rel: str, digest: str, source: str, retries: int = 3) -> str:
    dest = out_dir / rel
    if dest.exists() and sha256_of(dest) == digest:
        return "skipped"
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    bases = [ds.s3_files_url, ds.physionet_files_url] if source == "s3" else [ds.physionet_files_url]
    last_err = "unknown"
    for base in bases:
        for attempt in range(1, retries + 1):
            try:
                http_get(base + rel, part, timeout=300)
                got = sha256_of(part)
                if got == digest:
                    os.replace(part, dest)
                    return "downloaded"
                last_err = f"checksum mismatch from {base} (got {got[:12]}..., want {digest[:12]}...)"
                break  # a wrong file will not get better by retrying the same source
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                last_err = f"{base}: {exc}"
                time.sleep(2 * attempt)
            finally:
                if part.exists():
                    part.unlink()
    raise RuntimeError(f"{rel}: {last_err}")


def run_sleep_accel(args: argparse.Namespace) -> int:
    ds = SLEEP_ACCEL
    out_dir = Path(args.out).resolve() / ds.slug / ds.version
    out_dir.mkdir(parents=True, exist_ok=True)
    log(f"Dataset : {ds.title}")
    log(f"Version : {ds.version}  ({ds.landing_url})")
    log(f"Target  : {out_dir}")
    entries = fetch_sums(ds, out_dir)
    counts = {"skipped": 0, "downloaded": 0}
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(download_one, ds, out_dir, rel, dig, args.source): rel for rel, dig in entries.items()}
        for i, fut in enumerate(as_completed(futures), 1):
            rel = futures[fut]
            try:
                status = fut.result()
                counts[status] += 1
                if status == "downloaded" or args.verbose:
                    log(f"[{i:3d}/{len(entries)}] {status:10s} {rel}")
            except Exception as exc:  # noqa: BLE001 - report every failure, then exit non-zero
                failures.append(str(exc))
                log(f"[{i:3d}/{len(entries)}] FAILED     {exc}")
    log(f"Verified: {counts['skipped'] + counts['downloaded']}/{len(entries)} files "
        f"({counts['downloaded']} downloaded, {counts['skipped']} already present and verified)")
    log("")
    log(f"License : {ds.license_name}  ({ds.license_url}; full text in {out_dir / 'LICENSE.txt'})")
    log(f"Cite    : {ds.citation}")
    log(f"Paper   : {ds.original_publication}")
    log(f"PhysioNet: {PHYSIONET_PLATFORM_CITATION}")
    if failures:
        log(f"ERROR: {len(failures)} file(s) failed verification/download; re-run to resume.")
        return 1
    return 0


# ------------------------------------------------------------------------------------------------- BIDSleep ----

def curl_fetch(url: str, dest: Path, digest: str, retries: int = 3) -> str:
    """Resumable single-file download with ``curl -C -`` into ``dest.part``, verified before it is renamed."""
    if dest.exists() and sha256_of(dest) == digest:
        return "skipped"
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    last = "unknown"
    for attempt in range(1, retries + 1):
        cmd = ["curl", "-sS", "-f", "-L", "-A", USER_AGENT, "--connect-timeout", "30", "--retry", "5",
               "--retry-delay", "5", "-C", "-", "-o", str(part), url]
        res = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if res.returncode == 0 or (res.returncode == 33 and part.exists()):  # 33: nothing left to resume
            if sha256_of(part) == digest:
                os.replace(part, dest)
                return "downloaded"
            last = "checksum mismatch"
            part.unlink(missing_ok=True)  # corrupt: restart from scratch
        else:
            last = f"curl exit {res.returncode}: {res.stderr.strip()[:200]}"
        time.sleep(2 * attempt)
    raise RuntimeError(f"{dest.name}: {last}")


def _remote_size(url: str) -> int:
    res = subprocess.run(["curl", "-sSIL", "-f", "-A", USER_AGENT, url], capture_output=True, text=True, check=True)
    sizes = re.findall(r"(?im)^content-length:\s*(\d+)", res.stdout)
    if not sizes:
        raise RuntimeError(f"no Content-Length for {url}")
    return int(sizes[-1])


def download_zip(url: str, dest: Path, connections: int) -> None:
    """Resumable zip download. connections == 1: ``curl -C -``; otherwise N byte ranges (each resumable: a range
    restarts from the bytes already on disk) that are concatenated when all are complete."""
    if dest.exists():
        log(f"zip present: {dest}")
        return
    total = _remote_size(url)
    log(f"zip: {total / 1e9:.2f} GB from {url} with {connections} connection(s)")
    part = dest.with_name(dest.name + ".part")
    if connections <= 1:
        cmd = ["curl", "-sS", "-f", "-L", "-A", USER_AGENT, "--retry", "20", "--retry-delay", "10", "-C", "-",
               "-o", str(part), url]
        for _ in range(100):
            res = subprocess.run(cmd, check=False)
            if part.exists() and part.stat().st_size >= total:
                break
            if res.returncode not in (0, 18, 28, 56, 92):  # partial file / timeout / recv error / stream error
                raise RuntimeError(f"curl exit {res.returncode}")
        else:
            raise RuntimeError("zip download did not complete; re-run to resume")
        os.replace(part, dest)
        return
    bounds = [(i * total // connections, (i + 1) * total // connections - 1) for i in range(connections)]

    def fetch_range(i: int) -> None:
        a, b = bounds[i]
        seg = dest.with_name(f"{dest.name}.seg{i:02d}")
        want = b - a + 1
        for _ in range(100):
            have = seg.stat().st_size if seg.exists() else 0
            if have == want:
                return
            if have > want:  # the server ignored the Range header: discard this segment
                seg.unlink()
                raise RuntimeError(f"segment {i}: got more bytes than requested (Range not honoured)")
            with open(seg, "ab") as fh:
                subprocess.run(["curl", "-sS", "-f", "-L", "-A", USER_AGENT, "--retry", "20", "--retry-delay",
                                "10", "-r", f"{a + have}-{b}", url], stdout=fh, check=False)
        raise RuntimeError(f"segment {i} did not complete; re-run to resume")

    with ThreadPoolExecutor(max_workers=connections) as pool:
        for fut in as_completed([pool.submit(fetch_range, i) for i in range(connections)]):
            fut.result()
    with open(part, "wb") as out:
        for i in range(connections):
            seg = dest.with_name(f"{dest.name}.seg{i:02d}")
            with open(seg, "rb") as fh:
                shutil.copyfileobj(fh, out, CHUNK)
    if part.stat().st_size != total:
        raise RuntimeError(f"zip size {part.stat().st_size} != {total}")
    os.replace(part, dest)
    for i in range(connections):
        dest.with_name(f"{dest.name}.seg{i:02d}").unlink(missing_ok=True)


def extract_verified(zip_path: Path, out_dir: Path, entries: dict[str, str], wanted: set[str]) -> tuple[int, int]:
    """Extracts the wanted checksum-list paths from the zip, hashing while writing; returns (extracted, skipped)."""
    extracted = skipped = 0
    with zipfile.ZipFile(zip_path) as zf:
        by_rel: dict[str, zipfile.ZipInfo] = {}
        for info in zf.infolist():
            name = info.filename
            # members may sit under a top-level folder named after the project; match by checksum-list path
            for rel in (name, name.split("/", 1)[-1]):
                if rel in entries:
                    by_rel[rel] = info
                    break
        for rel in sorted(wanted):
            dest = out_dir / rel
            if dest.exists() and sha256_of(dest) == entries[rel]:
                skipped += 1
                continue
            info = by_rel.get(rel)
            if info is None:
                raise RuntimeError(f"{rel}: not in zip")
            dest.parent.mkdir(parents=True, exist_ok=True)
            part = dest.with_name(dest.name + ".part")
            h = hashlib.sha256()
            with zf.open(info) as src, open(part, "wb") as dst:
                while True:
                    buf = src.read(CHUNK)
                    if not buf:
                        break
                    h.update(buf)
                    dst.write(buf)
            if h.hexdigest() != entries[rel]:
                part.unlink(missing_ok=True)
                raise RuntimeError(f"{rel}: checksum mismatch in zip")
            os.replace(part, dest)
            extracted += 1
    return extracted, skipped


def run_bidsleep(args: argparse.Namespace) -> int:
    ds = BIDSLEEP
    out_dir = Path(args.out).resolve() / ds.slug / ds.version
    out_dir.mkdir(parents=True, exist_ok=True)
    parts = {p.strip() for p in args.parts.split(",") if p.strip()}
    if "all" in parts:
        parts = set(BIDSLEEP_PARTS)
    unknown = parts - set(BIDSLEEP_PARTS)
    if unknown:
        raise SystemExit(f"unknown --parts {sorted(unknown)}; choose from hr,labels,motion,all")
    log(f"Dataset : {ds.title}")
    log(f"Version : {ds.version}  ({ds.landing_url})")
    log(f"Target  : {out_dir}  parts={sorted(parts)}")
    entries = fetch_sums(ds, out_dir)
    base_files = [rel for rel in entries if "/" not in rel]                      # README.md, LICENSE.txt
    per_file = [rel for rel in entries if rel.rsplit("/", 1)[-1] in {BIDSLEEP_PARTS[p] for p in parts - {"motion"}}]
    failures: list[str] = []
    counts = {"skipped": 0, "downloaded": 0}
    todo = base_files + per_file
    with ThreadPoolExecutor(max_workers=min(args.jobs, MAX_CONNECTIONS)) as pool:
        futures = {pool.submit(curl_fetch, ds.physionet_files_url + rel, out_dir / rel, entries[rel]): rel
                   for rel in todo}
        for i, fut in enumerate(as_completed(futures), 1):
            rel = futures[fut]
            try:
                status = fut.result()
                counts[status] += 1
                if status == "downloaded" or args.verbose:
                    log(f"[{i:3d}/{len(todo)}] {status:10s} {rel}")
            except Exception as exc:  # noqa: BLE001
                failures.append(str(exc))
                log(f"[{i:3d}/{len(todo)}] FAILED     {exc}")
    log(f"Verified: {counts['skipped'] + counts['downloaded']}/{len(todo)} files "
        f"({counts['downloaded']} downloaded, {counts['skipped']} already present and verified)")
    if "motion" in parts:
        motion = {rel for rel in entries if rel.endswith("/motion.csv")}
        missing = {rel for rel in motion if not ((out_dir / rel).exists() and sha256_of(out_dir / rel) == entries[rel])}
        if missing:
            zip_path = out_dir.parent / f"bidsleep-dataset-{ds.version}.zip"
            download_zip(BIDSLEEP_ZIP_URL, zip_path, max(1, min(args.connections, MAX_CONNECTIONS)))
            ext, skp = extract_verified(zip_path, out_dir, entries, missing)
            log(f"motion  : {ext} extracted and verified from the zip, {skp} already present")
            if not args.keep_zip:
                zip_path.unlink()
        else:
            log(f"motion  : all {len(motion)} files present and verified")
    log("")
    log(f"License : {ds.license_name}  ({ds.license_url}; full text in {out_dir / 'LICENSE.txt'})")
    log(f"Cite    : {ds.citation}")
    log(f"Paper   : {ds.original_publication}")
    log(f"PhysioNet: {PHYSIONET_PLATFORM_CITATION}")
    if failures:
        log(f"ERROR: {len(failures)} file(s) failed verification/download; re-run to resume.")
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--dataset", choices=["sleep-accel", "bidsleep", "dreamt"], default="sleep-accel")
    p.add_argument("--out", default=str(DEFAULT_RAW_DIR), help="raw data root (default: %(default)s)")
    p.add_argument("--source", choices=["s3", "physionet"], default="s3",
                   help="where to fetch files from; checksums always come from physionet.org (default: %(default)s)")
    p.add_argument("--jobs", type=int, default=4, help="parallel downloads (default: %(default)s)")
    p.add_argument("--parts", default="hr,labels",
                   help="bidsleep only: comma list of hr,labels,motion or 'all' (default: %(default)s)")
    p.add_argument("--connections", type=int, default=4,
                   help=f"bidsleep motion zip: parallel byte ranges, max {MAX_CONNECTIONS} (default: %(default)s)")
    p.add_argument("--keep-zip", action="store_true", help="bidsleep: keep the zip after extraction")
    p.add_argument("--verbose", action="store_true", help="also list skipped files")
    args = p.parse_args(argv)
    if args.dataset == "dreamt":
        log(DREAMT_NOTICE)
        return 2
    if args.dataset == "bidsleep":
        return run_bidsleep(args)
    return run_sleep_accel(args)


if __name__ == "__main__":
    sys.exit(main())
