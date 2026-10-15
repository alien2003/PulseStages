"""OpenNeuro ds006576 external test set (pre-registration naps-realistic, section 13): snapshot pin, range streaming
against a local HTTP server, BrainVision reduction, the frozen R-peak detector on a synthetic ECG with known beats,
heart-rate samples and variants, the central-derivation rule, the windows on synthetic hypnograms, night files,
manifest / summary digests, the NOTE logging and the freeze checks of G9, the lock and the key list, and that no
ds006576 recording can enter a training NightIndex. Synthetic data only; YASA is not imported here."""

import hashlib
import json
import threading
import urllib.parse
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import pytest
from scipy import signal

import external
import external_stage
from pulsetrain import ds006576 as ds
from pulsetrain import nightset, ohealth

REPO = Path(__file__).resolve().parents[3]
PREREG = REPO / "docs" / "prereg" / ds.PREREG_NAME
FS = ds.FS_RAW


# ================================================================================== pre-registered constants ====

def test_prereg_digest_is_the_committed_file():
    digest = hashlib.sha256(PREREG.read_bytes()).hexdigest()
    assert digest == ds.PREREG_SHA256
    assert (PREREG.parent / f"{ds.PREREG_NAME}.sha256").read_text().split() == [digest, ds.PREREG_NAME]


def test_constants_are_the_preregistered_ones():
    # section 13.2
    assert ds.DATASET_DOI == "doi:10.18112/openneuro.ds006576.v1.0.5"
    assert ds.CHANGES_FIRST_ENTRY == ("1.0.5", "2026-04-16")
    assert ds.GROUP_COUNTS == {"NREM": 21, "REM": 21, "WAKE": 23} and ds.N_EEG_OBJECTS == 65
    assert (ds.FRAME_BYTES, ds.RANGE_BYTES, ds.RANGE_FRAMES, ds.FS_RAW, ds.FS_EEG) == (292, 44_851_200, 153_600, 512,
                                                                                         128)
    assert ds.CHANNEL_LABELS == ("A13_C3", "B18_C4", "A1", "A2", "LOC", "EMG1", "EMG2", "ECG1", "ECG2")
    assert ds.DERIVATIONS == {"ecg": ("ECG1", "ECG2"), "c3": ("A13_C3", "A2"), "c4": ("B18_C4", "A1"),
                              "eog": ("LOC", "A2"), "emg": ("EMG1", "EMG2")}
    # section 13.3 (frozen)
    assert (ds.CHUNK_S, ds.BAND_HZ, ds.BUTTER_ORDER, ds.POLARITY_PCT) == (15, (5.0, 25.0), 3, (99.7, 0.3))
    assert (ds.MIN_PEAK_DISTANCE_S, ds.TOP_FRACTION, ds.TOP_PERCENTILE, ds.KEEP_FRACTION) == (0.3, 0.30, 90, 0.45)
    assert (ds.GOOD_MIN_BEATS, ds.RR_RANGE_S, ds.GOOD_MIN_RR_FRACTION, ds.GOOD_MIN_RR_TIME_FRACTION,
            ds.GOOD_HR_RANGE) == (8, (0.4, 1.6), 0.80, 0.60, (30.0, 180.0))
    assert (ds.SAMPLE_STEP_MS, ds.SAMPLE_OFFSET_MS, ds.SAMPLE_MIN_RR, ds.CONTEXT_MS) == (5000, 2500, 2, 1_800_000)
    # section 13.4 and 2.5
    assert ds.YASA_VERSION == "0.7.0" and ds.YASA_WHEEL == "yasa-0.7.0-py3-none-any.whl"
    assert ds.YASA_WHEEL_SHA256 == "114d9cab3dd8237847657b45540aa5df1edd27eea0bd590b8154a6fb2426b0c9"
    assert ds.YASA_CLASSIFIER == "clf_eeg+eog+emg_lgb_0.5.0.joblib"
    assert ds.YASA_CLASSIFIER_SHA256 == "3d27d440c94c61d8013c81c91693ad88810af2ddae85b62f4bc797f6e2464beb"
    assert ds.YASA_TO_STAGE == {"W": "AWAKE", "N1": "LIGHT", "N2": "LIGHT", "N3": "DEEP", "R": "REM"}
    assert (ds.CENTRAL_BAND_HZ, ds.CENTRAL_WELCH_S) == ((0.5, 30.0), 4)
    # section 13.5
    assert (ds.MARGIN_MS, ds.RUN_EPOCHS, ds.M60_BEFORE_MS, ds.M60_AFTER_MS) == (300_000, 3, 300_000, 3_300_000)
    assert ds.WINDOWS == ("oh", "oh1", "m60", "rec")


# ============================================================================== committed key list and lock ====

def test_committed_key_list_is_the_snapshot_of_the_preregistration():
    keys = ds.read_keys()
    assert len(keys) == 65
    assert {g: sum(k.group == g for k in keys) for g in ("NREM", "REM", "WAKE")} == {"NREM": 21, "REM": 21, "WAKE": 23}
    assert {k.subject for k in keys if k.truncated} == {"sub-139", "sub-176"}
    for k in keys:
        if not k.truncated:
            assert Decimal(k.bytes) == Decimal(k.duration_s) * 512 * 292
    longest = max(Decimal(k.duration_s) for k in keys if not k.truncated)
    assert longest < 180 * 60                                         # every window is a nap


def test_committed_lock_pins_yasa_and_its_digests():
    lock = ds.read_lock()
    assert lock.pins["yasa"] == "0.7.0" and len(lock.pins) == len(lock.hashes) >= 30
    assert (lock.yasa_wheel_name, lock.yasa_wheel_sha256) == (ds.YASA_WHEEL, ds.YASA_WHEEL_SHA256)
    assert (lock.classifier_name, lock.classifier_sha256) == (ds.YASA_CLASSIFIER, ds.YASA_CLASSIFIER_SHA256)
    assert (lock.openmp_deb_name, lock.openmp_deb_sha256, lock.openmp_lib_sha256) == (
        ds.OPENMP_DEB, ds.OPENMP_DEB_SHA256, ds.OPENMP_LIB_SHA256)
    assert lock.hashes["yasa"] == ds.YASA_WHEEL_SHA256
    req = (ds.TRAIN_DIR / "requirements-external.txt").read_text().split("\n")
    assert [x for x in req if x and not x.startswith("#")] == ["yasa==0.7.0"]
    assert ".venv-external" in (ds.TRAIN_DIR / ".gitignore").read_text().split()


def _report(pkgs):
    return {"pip_version": "26.2.1", "install": [
        {"metadata": {"name": n, "version": v}, "download_info": {"archive_info": {"hashes": {"sha256": h}}}}
        for n, v, h in pkgs]}


def test_lock_round_trip_and_check(tmp_path):
    pkgs = [("yasa", "0.7.0", ds.YASA_WHEEL_SHA256), ("Jinja2", "3.1.6", "a" * 64), ("scikit_learn", "1.9.1", "b" * 64)]
    text = external.format_lock(_report(pkgs), "2026-10-01T00:00:00Z", "26.2.1")
    lock = ds.parse_lock(text)
    assert lock.pins == {"yasa": "0.7.0", "jinja2": "3.1.6", "scikit-learn": "1.9.1"}
    assert lock.hashes["jinja2"] == "a" * 64 and lock.classifier_sha256 == ds.YASA_CLASSIFIER_SHA256

    def release(name, version, uploaded="2026-09-01T00:00:00Z", digest=None):
        return {"urls": [{"upload_time_iso_8601": uploaded, "digests": {"sha256": digest or lock.hashes[name]}}]}

    assert external.lock_check(lock, "2026-10-01T00:00:00Z", release) == []
    late = external.lock_check(lock, "2026-10-01T00:00:00Z",
                               lambda n, v: release(n, v, uploaded="2026-10-02T12:00:00Z"))
    assert len(late) == 2 * 3 and all("after" in f or "not before" in f for f in late)
    other = external.lock_check(lock, "2026-10-01T00:00:00Z", lambda n, v: release(n, v, digest="c" * 64))
    assert len(other) == 3 and all("no file with the locked hash" in f for f in other)
    bad = text.replace(ds.YASA_CLASSIFIER_SHA256, "d" * 64)
    assert any("classifier" in f for f in external.lock_check(ds.parse_lock(bad), "2026-10-01T00:00:00Z", release))
    with pytest.raises(ds.ExternalError):
        ds.parse_lock("numpy>=2\n")
    with pytest.raises(ds.ExternalError):
        ds.parse_lock("numpy==2.5.3\n")                              # no hash


# ========================================================================================= snapshot pin ====

def _keys(sizes=None):
    rows = []
    for i in range(65):
        group = "NREM" if i < 21 else "REM" if i < 42 else "WAKE"
        dur = 3000 + i
        rows.append(ds.KeyRow(f"sub-{100 + i}", group, (sizes or {}).get(i, dur * 512 * 292), str(dur)))
    return rows


def test_snapshot_pin_passes_and_fails_on_every_condition():
    keys = _keys()
    desc = {"DatasetDOI": ds.DATASET_DOI}
    changes = "1.0.5 2026-04-16\n  - Added sub-180 thru sub-188.\n1.0.4 2026-04-09\n"
    listing = {k.subject: k.bytes for k in keys}
    groups = {k.subject: k.group for k in keys}
    durs = {k.subject: k.duration_s for k in keys}
    assert ds.snapshot_pin(desc, changes, listing, keys, groups, durs) == []
    assert ds.snapshot_pin({"DatasetDOI": "doi:10.18112/openneuro.ds006576.v1.0.6"}, changes, listing, keys)
    assert ds.snapshot_pin(desc, "1.0.6 2026-06-01\n" + changes, listing, keys)
    assert ds.snapshot_pin(desc, "1.0.5 2026-04-17\n", listing, keys)
    assert ds.snapshot_pin(desc, changes, {**listing, "sub-999": 292}, keys)
    assert ds.snapshot_pin(desc, changes, {k: v for k, v in listing.items() if k != "sub-100"}, keys)
    assert ds.snapshot_pin(desc, changes, {**listing, "sub-100": listing["sub-100"] + 292}, keys)
    assert ds.snapshot_pin(desc, changes, listing, keys, {**groups, "sub-100": "WAKE"})
    assert ds.snapshot_pin(desc, changes, listing, keys, None, {**durs, "sub-100": "1"})
    assert ds.snapshot_pin(desc, changes, listing, keys[1:])            # 64 rows
    assert ds.snapshot_pin(desc, changes, listing, [ds.KeyRow(k.subject, "NREM", k.bytes, k.duration_s)
                                                    for k in keys])  # group counts


def test_truncation_rule():
    assert not ds.KeyRow("sub-1", "WAKE", 10 * 512 * 292, "10").truncated
    assert ds.KeyRow("sub-1", "WAKE", 10 * 512 * 292 - 292, "10").truncated            # whole frames, too short
    assert ds.KeyRow("sub-1", "WAKE", 297_648_058, "5514").truncated                    # not a multiple of 292
    assert not ds.KeyRow("sub-1", "WAKE", int(Decimal("10.5") * 512 * 292), "10.5").truncated


def test_key_list_format_round_trip_and_rejections():
    keys = _keys()
    assert ds.parse_keys(ds.format_keys(keys, ["comment", ""])) == sorted(keys, key=lambda k: k.subject)
    with pytest.raises(ds.ExternalError):
        ds.parse_keys("subject\tgroup\tbytes\n")
    with pytest.raises(ds.ExternalError):
        ds.parse_keys("subject\tgroup\tbytes\trecordingDurationS\nsub-1\tNAP\t1\t1\n")
    with pytest.raises(ds.ExternalError):
        ds.parse_keys("subject\tgroup\tbytes\trecordingDurationS\nsub-1\tREM\t1\t1\nsub-1\tREM\t1\t1\n")


def test_listing_pages_and_eeg_objects():
    page1 = ('<?xml version="1.0"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
             '<IsTruncated>true</IsTruncated><NextContinuationToken>tok/+=</NextContinuationToken>'
             '<Contents><Key>ds006576/CHANGES</Key><Size>627</Size></Contents>'
             '<Contents><Key>ds006576/sub-104/eeg/sub-104_task-rest_eeg.eeg</Key><Size>820328448</Size></Contents>'
             '<Contents><Key>ds006576/sub-104/eeg/sub-104_task-rest_eeg.vhdr</Key><Size>2000</Size></Contents>'
             '</ListBucketResult>')
    items, token = ds.parse_listing_page(page1)
    assert token == "tok/+=" and items["ds006576/CHANGES"] == 627
    listing = {**items, "ds006576/sub-105/eeg/sub-106_task-rest_eeg.eeg": 5,
               "ds006576/sub-107/anat/sub-107_T1w.nii.gz": 9}
    assert ds.eeg_sizes(listing) == {"sub-104": 820328448}
    last = page1.replace("<IsTruncated>true", "<IsTruncated>false")
    assert ds.parse_listing_page(last)[1] is None
    assert ds.changes_first_entry("\n1.0.5 2026-04-16\n  - x\n") == ("1.0.5", "2026-04-16")
    assert ds.parse_participants("participant_id\tsex\tgroup\nsub-104\tf\tNREM\nsub-105\tm\tWAKE") == {
        "sub-104": "NREM", "sub-105": "WAKE"}


# ======================================================================================== BrainVision =====

def _vhdr(labels, res="1", unit=None):
    ch = "\n".join(f"Ch{i}={lab},,{res}" + (f",{unit}" if unit is not None else "") for i, lab in
                   enumerate(labels, 1))
    return ("Brain Vision Data Exchange Header File Version 1.0\n[Common Infos]\nDataFile=x.eeg\n"
            "DataFormat=BINARY\nDataOrientation=MULTIPLEXED\nNumberOfChannels=" + str(len(labels)) +
            "\nSamplingInterval=1.953125e+03\n\n[Binary Infos]\nBinaryFormat=IEEE_FLOAT_32\n\n[Channel Infos]\n" + ch +
            "\n")


def _labels(layout_a=True):
    """73 labels with the two A20-A32 layouts of the audit; the used channels at their audited positions."""
    a = [f"A{i}_X{i}" for i in range(1, 33)]
    a[12] = "A13_C3"
    if not layout_a:
        a[19:32] = a[19:32][::-1]
    b = [f"B{i}_Y{i}" for i in range(1, 33)]
    b[17] = "B18_C4"
    return a + b + ["A1", "A2", "LOC", "ROC", "EMG1", "EMG2", "ECG1", "ECG2", "Status"]


def test_channel_layout_by_label_both_layouts_and_exclusions():
    for layout_a in (True, False):
        lay, why = ds.channel_layout(ds.parse_vhdr(_vhdr(_labels(layout_a))))
        assert why is None
        assert {k: v[0] for k, v in lay.items()} == {"A13_C3": 12, "B18_C4": 49, "A1": 64, "A2": 65, "LOC": 66,
                                                     "EMG1": 68, "EMG2": 69, "ECG1": 70, "ECG2": 71}
        assert all(v[1] == 1.0 for v in lay.values())
    lab = _labels()
    lab[66] = "LOC2"
    assert ds.channel_layout(ds.parse_vhdr(_vhdr(lab)))[1] == "label LOC found 0 times"
    lab = _labels()
    lab[67] = "LOC"
    assert ds.channel_layout(ds.parse_vhdr(_vhdr(lab)))[1] == "label LOC found 2 times"
    lay, _ = ds.channel_layout(ds.parse_vhdr(_vhdr(_labels(), res="0.5", unit="mV")))
    assert lay["ECG1"][1] == 500.0
    assert ds.channel_layout(ds.parse_vhdr(_vhdr(_labels(), unit="furlong")))[0] is None
    assert ds.channel_layout(ds.parse_vhdr(_vhdr(_labels()).replace("IEEE_FLOAT_32", "INT_16")))[0] is None
    assert ds.channel_layout(ds.parse_vhdr(_vhdr(_labels()).replace("1.953125e+03", "1000")))[0] is None


def _frames(n, seed=0):
    rng = np.random.default_rng(seed)
    return rng.normal(0, 50, size=(n, 73)).astype("<f4")


def test_reducer_keeps_complete_frames_hashes_every_byte_and_derives():
    lay, _ = ds.channel_layout(ds.parse_vhdr(_vhdr(_labels())))
    fr = _frames(1000)
    blob = fr.tobytes() + b"\x01\x02\x03"                                   # a truncated trailing frame
    red = ds.Reducer(len(blob), lay)
    for a in range(0, len(blob), 4000):                                     # chunks not aligned to frames
        red.feed(blob[a:a + 4000])
    frames, digest = red.result()
    assert digest == hashlib.sha256(blob).hexdigest()
    assert frames.shape == (1000, 9)
    np.testing.assert_array_equal(frames[:, ds.CHANNEL_LABELS.index("ECG1")], fr[:, 70])
    d = ds.derive(frames, lay)
    np.testing.assert_allclose(d["ecg"], (fr[:, 70].astype(float) - fr[:, 71]).astype(np.float32))
    c3 = fr[:, 12].astype(float) - fr[:, 65]
    np.testing.assert_allclose(d["c3"], signal.decimate(c3, 4, ftype="fir", zero_phase=True).astype(np.float32),
                               rtol=1e-6)
    assert d["c3"].shape == (250,) and d["ecg"].dtype == np.float32 and d["emg"].dtype == np.float32
    with pytest.raises(ds.ExternalError):
        ds.Reducer(10, lay).result()


# ============================================================================== HTTP range streaming ====

class FakeS3:
    """A tiny path-style S3 bucket over http.server: listing (paginated), small files and Range requests; ``faults``
    maps a key to the list of the next responses' fault modes."""

    def __init__(self, objects: dict[str, bytes], page_size: int = 3):
        self.objects, self.page_size, self.faults, self.requests = objects, page_size, {}, []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                u = urllib.parse.urlsplit(self.path)
                fake.requests.append((u.path, self.headers.get("Range")))
                if u.path == "/openneuro.org" and "list-type=2" in u.query:
                    return self._listing(urllib.parse.parse_qs(u.query))
                key = u.path[len("/openneuro.org/"):]
                if key not in fake.objects:
                    self.send_response(404)
                    self.end_headers()
                    return
                body, rng = fake.objects[key], self.headers.get("Range")
                fault = (fake.faults.get(key) or [None]).pop(0) if fake.faults.get(key) else None
                if rng is None or fault == "status200":
                    self._send(200, body, {})
                    return
                a, b = (int(x) for x in rng.split("=")[1].split("-"))
                part = body[a:b + 1]
                cr = f"bytes {a}-{b}/{len(body)}"
                if fault == "badrange":
                    cr = f"bytes {a}-{b}/*"
                if fault == "short":
                    part = part[:-1]
                self._send(206, part, {"Content-Range": cr})

            def _listing(self, q):
                keys = sorted(k for k in fake.objects if k.startswith(q["prefix"][0]))
                start = int(q.get("continuation-token", ["0"])[0])
                page = keys[start:start + fake.page_size]
                more = start + fake.page_size < len(keys)
                xml = ('<?xml version="1.0"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
                       f"<IsTruncated>{'true' if more else 'false'}</IsTruncated>"
                       + (f"<NextContinuationToken>{start + fake.page_size}</NextContinuationToken>" if more else "")
                       + "".join(f"<Contents><Key>{k}</Key><Size>{len(fake.objects[k])}</Size></Contents>"
                                 for k in page) + "</ListBucketResult>")
                self._send(200, xml.encode(), {})

            def _send(self, status, body, headers):
                self.send_response(status)
                for k, v in headers.items():
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}/openneuro.org"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()


def _no_sleep(_s):
    pass


def test_fetch_range_checks_206_content_range_and_length_and_retries():
    body = bytes(range(256)) * 40
    key = "ds006576/sub-1/eeg/sub-1_task-rest_eeg.eeg"
    with FakeS3({key: body}) as s3:
        url = f"{s3.base}/{key}"
        assert ds.fetch_range(url, 100, 199, len(body), sleep=_no_sleep) == body[100:200]
        for fault in ("status200", "badrange", "short"):
            s3.faults[key] = [fault]
            assert ds.fetch_range(url, 0, 99, len(body), sleep=_no_sleep) == body[:100]
            s3.faults[key] = [fault] * 3
            with pytest.raises(ds.ExternalError):
                ds.fetch_range(url, 0, 99, len(body), retries=3, sleep=_no_sleep)
        with pytest.raises(ds.ExternalError):                       # Content-Range total must be the listed size
            ds.fetch_range(url, 0, 99, len(body) + 1, retries=1, sleep=_no_sleep)
    assert ds.check_range_response(206, "bytes 0-9/100", "10", 10, 0, 9, 100) is None
    assert "206" in ds.check_range_response(200, "bytes 0-9/100", "10", 10, 0, 9, 100)
    assert "Content-Length" in ds.check_range_response(206, "bytes 0-9/100", "11", None, 0, 9, 100)


def test_range_plan_whole_frames_last_shorter():
    plan = ds.range_plan(820_328_448)
    assert plan[0] == (0, 44_851_199) and plan[-1][1] == 820_328_447
    assert all(b - a + 1 == ds.RANGE_BYTES for a, b in plan[:-1]) and plan[-1][1] - plan[-1][0] + 1 < ds.RANGE_BYTES
    assert len(plan) == 19
    with pytest.raises(ValueError):
        ds.range_plan(1000, step=1000)


def test_stream_recording_over_http_equals_direct_reduction(monkeypatch):
    monkeypatch.setattr(ds, "RANGE_BYTES", 292 * 700)
    lay, _ = ds.channel_layout(ds.parse_vhdr(_vhdr(_labels())))
    fr = _frames(2000, seed=3)
    blob = fr.tobytes() + b"\x00" * 100                                     # truncated
    key = "ds006576/sub-2/eeg/sub-2_task-rest_eeg.eeg"
    with FakeS3({key: blob}) as s3:
        s3.faults[key] = ["short", None, "badrange"]
        arrays, digest, frames = ds.stream_recording(f"{s3.base}/{key}", len(blob), lay, sleep=_no_sleep)
        ranges = [r for p, r in s3.requests if r]
    assert digest == hashlib.sha256(blob).hexdigest() and frames == 2000
    assert ranges[0] == "bytes=0-204399" and ranges[-1] == f"bytes={2 * 204400}-{len(blob) - 1}"
    assert len(ranges) == 3 + 2                                             # two faulty responses repeated
    red = ds.Reducer(len(blob), lay)
    red.feed(blob)
    direct = ds.derive(red.result()[0], lay)
    assert ds.arrays_digest(arrays) == ds.arrays_digest(direct)


# ============================================================================ ECG -> heart rate (frozen) ====

def pilot_rpeaks(ecg, fs):
    """The pilot's detector, verbatim (ds006576-pilot/scripts/extract.py), the reference of the frozen port."""
    sos = signal.butter(3, [5.0, 25.0], btype="bandpass", fs=fs, output="sos")
    x = signal.sosfiltfilt(sos, ecg - np.mean(ecg))
    pol = 1.0 if np.percentile(x, 99.7) >= -np.percentile(x, 0.3) else -1.0
    y = pol * x
    cand, props = signal.find_peaks(y, distance=int(0.3 * fs))
    if len(cand) == 0:
        return np.array([], dtype=int), pol
    h = y[cand]
    top = h[h >= 0.3 * h.max()]
    ref = np.percentile(top, 90)
    keep = cand[h >= 0.45 * ref]
    return keep, pol


def pilot_good(beats, rr):
    """The pilot's quality rule (sessions.good_ecg on extract.py's chunk fields), verbatim in substance."""
    if beats < 8:
        return False
    plaus = (rr >= 0.4) & (rr <= 1.6)
    if np.mean(plaus) < 0.8:
        return False
    rr_ok = rr[plaus]
    hr15 = float(60.0 * len(rr_ok) / np.sum(rr_ok)) if len(rr_ok) else None
    return hr15 is not None and rr[plaus].sum() / rr.sum() >= 0.6 and 30 <= hr15 <= 180


def synthetic_ecg(seconds, beat_times, fs=FS, seed=0, amp=1000.0, invert=False):
    """QRS (Gaussian, 12 ms) + T wave (+250 ms) + baseline wander + noise at the given beat times (s), µV."""
    rng = np.random.default_rng(seed)
    n = int(seconds * fs)
    t = np.arange(n) / fs
    x = 150 * np.sin(2 * np.pi * 0.25 * t) + rng.normal(0, 15, n)
    for b in beat_times:
        i0, i1 = max(0, int((b - 0.1) * fs)), min(n, int((b + 0.5) * fs))
        tt = t[i0:i1] - b
        x[i0:i1] += amp * np.exp(-0.5 * (tt / 0.012) ** 2) + 0.25 * amp * np.exp(-0.5 * ((tt - 0.25) / 0.04) ** 2)
    return -x if invert else x


def beats(seconds, hr0=60.0, depth=8.0, period=40.0, start=0.4):
    out, t = [], start
    while t < seconds:
        out.append(t)
        t += 60.0 / (hr0 + depth * np.sin(2 * np.pi * t / period))
    return np.array(out)


@pytest.mark.parametrize("invert", [False, True])
def test_rpeaks_find_the_known_beats_and_equal_the_pilot(invert):
    truth = beats(15.0)
    ecg = synthetic_ecg(15.0, truth, invert=invert)
    pk, pol = ds.rpeaks(ecg, FS)
    assert pol == (-1.0 if invert else 1.0)
    inside = truth[(truth * FS > 5) & (truth * FS < 15 * FS - 5)]
    assert len(pk) == len(inside)
    assert np.max(np.abs(pk / FS - inside)) < 3 / FS
    ref, ref_pol = pilot_rpeaks(ecg, FS)
    np.testing.assert_array_equal(pk, ref)
    assert ref_pol == pol
    assert ds.good_chunk(pk, FS)


def test_rpeaks_equal_the_pilot_on_random_chunks():
    rng = np.random.default_rng(7)
    for i in range(20):
        hr = rng.uniform(45, 110)
        truth = beats(15.0, hr0=hr, depth=rng.uniform(0, 15), start=rng.uniform(0, 1))
        ecg = synthetic_ecg(15.0, truth, seed=i, amp=rng.uniform(200, 2000), invert=bool(i % 2))
        ecg += rng.normal(0, rng.uniform(5, 200), ecg.size)
        a, pa = ds.rpeaks(ecg, FS)
        b, pb = pilot_rpeaks(ecg, FS)
        np.testing.assert_array_equal(a, b)
        assert pa == pb
        rr = np.diff(a) / FS
        assert ds.good_chunk(a, FS) == pilot_good(len(a), rr)


def test_rpeaks_degenerate_chunks():
    assert len(ds.rpeaks(np.zeros(15 * FS), FS)[0]) == 0
    x = np.zeros(15 * FS)
    x[100] = np.nan
    assert len(ds.rpeaks(x, FS)[0]) == 0


def test_good_chunk_rules():
    one = lambda rr: np.concatenate([[0], np.cumsum(np.asarray(rr) * FS)]).astype(int)  # noqa: E731
    assert ds.good_chunk(one([1.0] * 7), FS)                                    # 8 beats
    assert not ds.good_chunk(one([1.0] * 6), FS)                                # 7 beats
    assert ds.good_chunk(one([1.0] * 8 + [0.3, 0.3]), FS)                       # 80 % plausible
    assert not ds.good_chunk(one([1.0] * 7 + [0.3, 0.3]), FS)                   # 7 / 9 < 80 %
    assert ds.good_chunk(one([1.0] * 8 + [2.0, 2.0]), FS)                       # RR time 8 / 12 >= 60 %
    assert not ds.good_chunk(one([1.0] * 8 + [3.0, 3.0]), FS)                   # RR time 8 / 14 < 60 %
    assert ds.good_chunk(one([0.41] * 10), FS) and ds.good_chunk(one([1.59] * 10), FS)


def test_ecg_rr_chunks_valid_range_and_partial_chunk_dropped():
    secs = 120 + 10                                                              # 8 chunks + a dropped 10-s tail
    truth = beats(secs, hr0=60.0, depth=0.0, start=0.5)
    ecg = synthetic_ecg(secs, truth, seed=1)
    ecg[: 15 * FS] = 0.0                                                         # first chunk: no signal
    ecg[45 * FS:60 * FS] = synthetic_ecg(15, np.arange(0.5, 15, 2.5), seed=3)  # 6 beats: missed beats
    er = ds.ecg_rr(ecg, FS)
    assert er.n_chunks == 8
    assert er.good.tolist() == [False, True, True, False, True, True, True, True]
    assert er.valid_range_ms == (15_000, 120_000)
    assert np.all((er.rr_s >= 0.4) & (er.rr_s <= 1.6))
    assert np.allclose(er.rr_s, 1.0, atol=2 / FS)
    assert er.rr_mid_ms.min() > 15_000 and er.rr_mid_ms.max() < 120_000
    assert not np.any((er.rr_mid_ms > 45_000) & (er.rr_mid_ms < 60_000))


def test_raw_hr_samples_every_5_s_median_of_at_least_two_intervals():
    mids = np.array([500, 1500, 2600, 3700, 6000, 9000, 9100, 16000, 17000.0])
    rr = np.array([1.0, 1.0, 0.8, 0.81, 1.0, 0.75, 0.7, 1.2, 1.2])
    t, v = ds.raw_hr_samples(mids, rr, 20_000)
    # [0, 5) s: 1.0, 1.0, 0.8, 0.81 -> median 0.905 -> 66.3 -> 66; [5, 10): 1.0, 0.75, 0.7 -> 0.75 -> 80;
    # [10, 15): none; [15, 20): 1.2, 1.2 -> 50
    assert t.tolist() == [2500, 7500, 17500]
    assert v.tolist() == [66.0, 80.0, 50.0]
    t, v = ds.raw_hr_samples(np.array([500.0]), np.array([1.0]), 10_000)
    assert t.size == 0                                                          # one interval -> no sample


def test_hr_variants_are_prepare_resamplers_over_the_clipped_context():
    t = np.arange(2500, 3_600_000, 5000, dtype=np.int64)
    v = 60 + np.round(5 * np.sin(t / 600_000.0))
    s0, s1, valid = 1_000_000, 2_500_000, (300_000, 3_000_000)
    out = ds.hr_variants(t, v, s0, s1, valid, "sub-104")
    phase = ohealth.grid_phase_ms("ds006576/sub-104")
    lo, hi = max(s0 - 1_800_000, valid[0]), min(s1 + 1_800_000, valid[1])
    assert out["instantaneous"] == ohealth.resample_instantaneous(t, v, lo, hi, phase)
    assert out["mean2min"] == ohealth.resample_mean2min(t, v, lo, hi, phase)
    for hr in out.values():
        assert hr and min(x[0] for x in hr) >= lo and max(x[0] for x in hr) <= hi


# ===================================================================================== central derivation ====

def test_central_choice_lower_band_power_ties_to_c3():
    rng = np.random.default_rng(0)
    x = rng.normal(0, 20, 128 * 600).astype(np.float32)
    assert ds.central_choice(x, 100 * x)[0] == "C3-A2"
    assert ds.central_choice(100 * x, x)[0] == "C4-A1"
    choice, power = ds.central_choice(x, x.copy())
    assert choice == "C3-A2" and power["C3-A2"] == power["C4-A1"]
    # 0.5-30 Hz only: a strong 50-Hz line on C3 does not count
    t = np.arange(x.size) / 128
    assert ds.central_choice(x + 500 * np.sin(2 * np.pi * 50 * t), 1.5 * x)[0] == "C3-A2"
    with pytest.raises(ds.ExternalError):
        ds.central_band_power(x[:100])


# ================================================================================================ windows ====

def hypnogram():
    s = (["W"] * 10 + ["N1"] + ["W"] * 5 + ["N1", "N2"] + ["W"] * 3 + ["N2"] * 40 + ["N3"] * 20 + ["W"] * 2
         + ["R"] * 10 + ["W"] * 4 + ["N1"] + ["W"] * 20)
    assert len(s) == 118
    return s


def test_windows_run_of_three_single_epochs_m60_and_rec():
    st = hypnogram()
    assert ds.run_bounds(st, 3) == (21, 92) and ds.run_bounds(st, 1) == (10, 97)
    w = ds.windows_from_stages(st, (0, 118 * 30_000), "NREM")
    assert list(w) == ["oh", "oh1", "rec"]                                    # m60 ends after the valid range
    assert w["oh"] == (21 * 30_000 - 300_000, 93 * 30_000 + 300_000)
    assert w["oh1"] == (0, min(98 * 30_000 + 300_000, 118 * 30_000))
    assert w["rec"] == (0, 118 * 30_000)
    long = st + ["W"] * 40
    w = ds.windows_from_stages(long, (0, len(long) * 30_000), "REM")
    assert w["m60"] == (21 * 30_000 - 300_000, 21 * 30_000 + 3_300_000)
    assert w["oh1"] == (0, 98 * 30_000 + 300_000)
    # clipped to the ECG-valid range; m60 needs to lie inside it
    w = ds.windows_from_stages(long, (600_000, 3_000_000), "NREM")
    assert w["oh"] == (600_000, 3_000_000) and "m60" not in w and w["rec"] == (600_000, 3_000_000)


def test_windows_wake_group_no_run_no_valid_range():
    st = hypnogram()
    assert list(ds.windows_from_stages(st, (0, 3_540_000), "WAKE")) == ["rec"]
    stray = ["W"] * 20 + ["N1", "N1"] + ["W"] * 10 + ["N2"] + ["W"] * 20
    w = ds.windows_from_stages(stray, (0, len(stray) * 30_000), "NREM")
    assert "oh" not in w and "m60" not in w and w["oh1"] == (20 * 30_000 - 300_000, 33 * 30_000 + 300_000)
    assert ds.windows_from_stages(st, None, "REM") == {}
    with pytest.raises(ds.ExternalError):
        ds.windows_from_stages(["N2"] * 400, (0, 400 * 30_000), "REM")       # 200 min: not a nap


def test_window_labels_fully_inside_and_mapped():
    st = ["W", "N1", "N2", "N3", "R", "W"]
    assert ds.window_labels(st, 30_000, 150_000) == [[30_000, 60_000, "LIGHT"], [60_000, 90_000, "LIGHT"],
                                                     [90_000, 120_000, "DEEP"], [120_000, 150_000, "REM"]]
    assert ds.window_labels(st, 31_000, 150_000)[0] == [60_000, 90_000, "LIGHT"]


def test_night_object_format():
    n = ds.night_object("sub-104", "oh", "mean2min", 1, 2, [[1, 60.0]], [[0, 30_000, "LIGHT"]])
    assert list(n) == ["id", "subject", "variant", "sessionStartMs", "sessionEndMs", "hr", "steps", "labels"]
    assert n["id"] == "ds006576-sub-104-oh-mean2min" and n["subject"] == "ds006576-sub-104"
    assert n["steps"] == [] and n["variant"] == "mean2min"
    assert ds.night_bytes(n).endswith(b"]]}\n") and b" " not in ds.night_bytes(n)
    assert ds.night_relpath("sub-104", "rec", "instantaneous") == "nights/instantaneous/ds006576-sub-104-rec.night.json"
    assert ds.night_id("sub-104", "m60", "instantaneous") == "ds006576-sub-104-m60-inst"


# ============================================================================ nights, manifest, NOTE lines ====

LOCK_TEXT = "\n".join([
    f"# yasa-wheel {ds.YASA_WHEEL} sha256 {ds.YASA_WHEEL_SHA256}",
    f"# yasa-classifier {ds.YASA_CLASSIFIER} sha256 {ds.YASA_CLASSIFIER_SHA256}",
    f"# openmp-deb {ds.OPENMP_DEB} sha256 {ds.OPENMP_DEB_SHA256}",
    f"# openmp-lib libgomp.so.1 sha256 {ds.OPENMP_LIB_SHA256}",
    f"yasa==0.7.0 \\\n    --hash=sha256:{ds.YASA_WHEEL_SHA256}", ""])
IDENTITY = {"version": "0.7.0", "wheelSha256": ds.YASA_WHEEL_SHA256, "classifier": ds.YASA_CLASSIFIER,
            "classifierSha256": ds.YASA_CLASSIFIER_SHA256, "openmpSha256": ds.OPENMP_LIB_SHA256,
            "unpickleWarningsAllowed": ["InconsistentVersionWarning: Trying to unpickle estimator LabelEncoder from "
                                        "version 0.24.2 when using version 1.9.1."]}
SMALL = {"NREM": 1, "REM": 1, "WAKE": 2}


def make_dataset(root: Path, monkeypatch, minutes=65) -> tuple[Path, Path, Path]:
    """A synthetic external data directory: 4 recordings (NREM, REM, WAKE, truncated WAKE) with a 60-bpm ECG,
    fetch records, derived arrays and stage files, plus a key list and a lock."""
    monkeypatch.setattr(ds, "GROUP_COUNTS", SMALL)
    monkeypatch.setattr(ds, "N_EEG_OBJECTS", 4)
    data = root / "ds006576"
    secs = minutes * 60
    rows = [("sub-201", "NREM", secs), ("sub-202", "REM", secs), ("sub-203", "WAKE", secs), ("sub-204", "WAKE", secs)]
    keys = []
    n_ep = secs // 30
    for i, (sub, group, s) in enumerate(rows):
        size = s * 512 * 292 - (100 if sub == "sub-204" else 0)
        keys.append(ds.KeyRow(sub, group, size, str(s)))
        ecg = synthetic_ecg(s, beats(s, hr0=60.0 + 5 * i, depth=6.0), seed=i).astype(np.float32)
        arrays = {"ecg": ecg, **{c: np.zeros(s * 128, np.float32) for c in ("c3", "c4", "eog", "emg")}}
        ds.save_derived(data / ds.derived_relpath(sub), arrays)
        rec = {"subject": sub, "group": group, "bytes": size, "truncated": keys[-1].truncated, "excluded": None,
               "sha256": hashlib.sha256(sub.encode()).hexdigest(), "derivedSha256": ds.arrays_digest(arrays)}
        (data / ds.record_relpath(sub)).write_text(json.dumps(rec))
        if group == "WAKE":
            stages = ["W"] * n_ep
        else:
            stages = ["W"] * 10 + ["N1"] * 4 + ["N2"] * 30 + ["N3"] * 20 + (["R"] * 6 if group == "REM" else
                                                                           ["N2"] * 6) + ["W"] * (n_ep - 70)
        stage = {"subject": sub, "yasa": IDENTITY, "derivedSha256": rec["derivedSha256"], "central": "C3-A2",
                 "stages": stages}
        (data / "stages").mkdir(parents=True, exist_ok=True)
        (data / ds.stage_relpath(sub)).write_bytes(ds.stage_bytes(stage))
    key_path = root / "keys.tsv"
    key_path.write_text(ds.format_keys(keys))
    lock_path = root / "lock.txt"
    lock_path.write_text(LOCK_TEXT)
    return data, key_path, lock_path


def run_nights(data, keys, lock, log, *extra):
    return external.main(["nights", "--data", str(data), "--keys", str(keys), "--lock", str(lock), "--log", str(log),
                          *extra])


def test_nights_files_manifest_summary_and_digest_only_output(tmp_path, monkeypatch, capsys):
    data, keys, lock = make_dataset(tmp_path, monkeypatch)
    log = tmp_path / "commands.log"
    assert run_nights(data, keys, lock, log) == 0
    out = capsys.readouterr().out.splitlines()
    assert [x.split()[0:2] for x in out] == [["manifest", "sha256"], ["yasa-summary", "sha256"]]
    man_bytes = (data / ds.MANIFEST_NAME).read_bytes()
    assert out[0].split()[-1] == hashlib.sha256(man_bytes).hexdigest()
    lines = log.read_text().splitlines()
    assert " START python -I tools/train/external.py nights " in lines[0]
    assert lines[1].split()[1:5] == ["NOTE", "ds006576", "manifest", "sha256"]
    assert lines[2].split()[1:5] == ["NOTE", "ds006576", "yasa-summary", "sha256"] and " END rc=0 " in lines[3]
    man = json.loads(man_bytes)
    assert man["dataset"]["doi"] == ds.DATASET_DOI and man["yasa"] == IDENTITY
    assert man["subjects"]["sub-204"]["truncated"] and not man["subjects"]["sub-201"]["truncated"]
    assert man["subjects"]["sub-201"]["group"] == "NREM"
    assert set(man["code"]["files"]) == set(ds.CODE_FILES)
    assert man["filesSha256"] == ds.digest_of_digests(man["files"])
    names = {Path(p).name for p in man["files"]}
    assert names == {f"ds006576-sub-{s}-{w}.night.json" for s in ("201", "202") for w in ("oh", "oh1", "m60", "rec")} \
        | {"ds006576-sub-203-rec.night.json", "ds006576-sub-204-rec.night.json"}
    assert len(man["files"]) == 2 * len(names)
    night = json.loads((data / "nights/instantaneous/ds006576-sub-201-oh.night.json").read_text())
    assert night["id"] == "ds006576-sub-201-oh-inst" and night["subject"] == "ds006576-sub-201"
    assert (night["sessionStartMs"], night["sessionEndMs"]) == (5 * 60_000 - 300_000, 70 * 30_000 + 300_000)
    assert night["steps"] == [] and night["labels"][0] == [0, 30_000, "AWAKE"]
    assert [x[2] for x in night["labels"]].count("DEEP") == 20
    vals = [v for _, v in night["hr"]]
    assert vals and abs(np.median(vals) - 60.0) <= 6.5
    m2 = json.loads((data / "nights/mean2min/ds006576-sub-201-oh.night.json").read_text())
    assert m2["id"].endswith("-mean2min") and m2["labels"] == night["labels"]
    summ = json.loads((data / ds.SUMMARY_NAME).read_text())
    nrem, rem, wake = summ["groups"]["NREM"], summ["groups"]["REM"], summ["groups"]["WAKE"]
    assert nrem["tstMin"]["mean"] == 30.0 and nrem["n3Min"]["mean"] == 10.0 and nrem["remMin"]["mean"] == 0.0
    assert rem["remPctTst"]["mean"] == 10.0 and rem["n3PctTst"]["mean"] == pytest.approx(100 * 10 / 30, abs=1e-4)
    assert wake["recordings"] == 1 and wake["sleepMin"]["mean"] == 0.0 and wake["below5MinSleep"] == 1


def test_nights_refuses_a_second_note_and_reproduce_compares(tmp_path, monkeypatch, capsys):
    data, keys, lock = make_dataset(tmp_path, monkeypatch, minutes=35)
    log = tmp_path / "commands.log"
    assert run_nights(data, keys, lock, log) == 0
    frozen = (data / ds.MANIFEST_NAME).read_bytes()
    n_lines = len(log.read_text().splitlines())
    assert run_nights(data, keys, lock, log) == 2                            # frozen: refused, nothing written
    assert len(log.read_text().splitlines()) == n_lines and (data / ds.MANIFEST_NAME).read_bytes() == frozen
    assert run_nights(data, keys, lock, log, "--reproduce") == 0
    assert "reproduces the logged manifest and yasa-summary digests" in log.read_text().splitlines()[-2]
    st = json.loads((data / ds.stage_relpath("sub-201")).read_text())
    st["stages"][40] = "W"
    (data / ds.stage_relpath("sub-201")).write_bytes(ds.stage_bytes(st))
    assert run_nights(data, keys, lock, log, "--reproduce") == 4
    assert "differs from the logged digests (deviation)" in log.read_text().splitlines()[-2]
    assert (data / ds.MANIFEST_NAME).read_bytes() == frozen                   # the frozen files are untouched
    assert ds.logged_digests(log.read_text().splitlines())[0] == [hashlib.sha256(frozen).hexdigest()]
    capsys.readouterr()


def test_nights_refuses_foreign_stage_files(tmp_path, monkeypatch):
    data, keys, lock = make_dataset(tmp_path, monkeypatch, minutes=35)
    st = json.loads((data / ds.stage_relpath("sub-202")).read_text())
    st["yasa"] = {**IDENTITY, "classifierSha256": "0" * 64}
    (data / ds.stage_relpath("sub-202")).write_bytes(ds.stage_bytes(st))
    log = tmp_path / "commands.log"
    assert run_nights(data, keys, lock, log) == 1
    assert ds.logged_digests(log.read_text().splitlines()) == ([], [])
    assert " END rc=1 " in log.read_text().splitlines()[-1]


# ================================================================================================ fetch =====

def test_fetch_end_to_end_pin_streaming_exclusion_and_resume(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(ds, "GROUP_COUNTS", SMALL)
    monkeypatch.setattr(ds, "N_EEG_OBJECTS", 4)
    monkeypatch.setattr(ds, "RANGE_BYTES", 292 * 1500)
    objects, keys = {}, []
    groups = {"sub-301": "NREM", "sub-302": "REM", "sub-303": "WAKE", "sub-304": "WAKE"}
    for i, (sub, group) in enumerate(groups.items()):
        secs = 8
        fr = _frames(secs * 512, seed=10 + i)
        blob = fr.tobytes()[: -50 if sub == "sub-304" else None]
        labels = _labels(layout_a=i % 2 == 0)
        if sub == "sub-303":
            labels[64] = "M1"                                                    # A1 missing -> excluded
        objects[ds.sidecar_key(sub, "eeg.eeg")] = blob
        objects[ds.sidecar_key(sub, "eeg.vhdr")] = _vhdr(labels).encode()
        objects[ds.sidecar_key(sub, "eeg.json")] = json.dumps({"RecordingDuration": secs}).encode()
        objects[ds.sidecar_key(sub, "channels.tsv")] = b"name\ttype\n"
        keys.append(ds.KeyRow(sub, group, len(blob), str(secs)))
    objects.update({
        "ds006576/dataset_description.json": json.dumps({"DatasetDOI": ds.DATASET_DOI}).encode(),
        "ds006576/CHANGES": b"1.0.5 2026-04-16\n  - x\n",
        "ds006576/participants.tsv": ("participant_id\tsex\tgroup\n" + "".join(
            f"{s}\tf\t{g}\n" for s, g in groups.items())).encode(),
        "ds006576/participants.json": b"{}",
    })
    key_path = tmp_path / "keys.tsv"
    key_path.write_text(ds.format_keys(keys))
    data, log = tmp_path / "ds006576", tmp_path / "commands.log"
    args = ["fetch", "--data", str(data), "--keys", str(key_path), "--log", str(log), "--workers", "2"]
    with FakeS3(objects, page_size=5) as s3:
        assert external.main(args + ["--base-url", s3.base]) == 0
        eeg_requests = [r for r in s3.requests if r[0].endswith("_eeg.eeg")]
        assert all(r[1] is not None for r in eeg_requests)                    # only range requests
        assert not any("sub-303_task-rest_eeg.eeg" in p for p, _ in eeg_requests)
        for sub in ("sub-301", "sub-302", "sub-304"):
            rec = json.loads((data / ds.record_relpath(sub)).read_text())
            blob = objects[ds.sidecar_key(sub, "eeg.eeg")]
            assert rec["sha256"] == hashlib.sha256(blob).hexdigest()
            assert rec["completeFrames"] == len(blob) // 292 and rec["truncated"] == (sub == "sub-304")
            arrays = ds.load_derived(data / ds.derived_relpath(sub))
            assert ds.arrays_digest(arrays) == rec["derivedSha256"]
            assert arrays["ecg"].shape == (len(blob) // 292,) and arrays["c4"].shape == ((len(blob) // 292 + 3) // 4,)
        assert json.loads((data / ds.record_relpath("sub-303")).read_text())["excluded"].startswith("channels: ")
        assert not list((data / "derived").glob("*.tmp"))
        assert json.loads((data / "pin.json").read_text())["ok"]
        n = len(s3.requests)
        assert external.main(args + ["--base-url", s3.base]) == 0              # resume: nothing re-streamed
        assert not any(p.endswith("_eeg.eeg") for p, _ in s3.requests[n:])
        # a changed snapshot: the pin fails before any .eeg byte is read
        objects["ds006576/CHANGES"] = b"1.0.6 2026-09-01\n"
        n = len(s3.requests)
        assert external.main(args + ["--base-url", s3.base]) == 3
        assert not any(p.endswith("_eeg.eeg") for p, _ in s3.requests[n:])
    text = log.read_text()
    assert text.count(" START python -I tools/train/external.py fetch ") == 3
    assert "NOTE ds006576 snapshot pin failed (external test void)" in text
    assert not json.loads((data / "pin.json").read_text())["ok"]
    capsys.readouterr()


# ======================================================================================== freeze (G9) =====

def _log(*events):
    stamp = iter(f"2026-10-15T{h:02d}:{m:02d}:00+02:00" for h in range(4, 24) for m in range(0, 60, 5))
    return [f"{next(stamp)} {e}" for e in events]


PRE = [f"PRE-REGISTRATION naps-realistic (written before ...)", f"NOTE {ds.PREREG_NAME} sha256 {ds.PREREG_SHA256}"]
GATES = ["START mise exec -- python -I tools/train/train.py naps-realistic-gates --out-run naps-realistic-gates",
         "RESULT naps-realistic-gates G1 G2 G3a G4 passed",
         "START tools/train/.venv-external/bin/python -I tools/train/external_stage.py --self-test"]
FETCH = "START python -I tools/train/external.py fetch --data tools/train/data/external/ds006576 --workers 4"
STAGE = "START python -I tools/train/external_stage.py --data tools/train/data/external/ds006576"
NIGHTS = "START python -I tools/train/external.py nights --data tools/train/data/external/ds006576"
NOTES = ["NOTE ds006576 manifest sha256 " + "a" * 64, "NOTE ds006576 yasa-summary sha256 " + "b" * 64]
EXP = "START JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-realistic --out-run naps-realistic"
RES = "RESULT naps-realistic rule (i): C2 adopted (tier 2); rule (ii): N1 SLEEP_WAKE, C2 SLEEP_WAKE"
EXT = "START JAVA_OPTS=-Xmx2g mise exec -- python -I tools/train/train.py naps-realistic-external --exp naps-realistic"
GOOD = [*PRE, *GATES, FETCH, "END rc=0 3600s fetch", STAGE, NIGHTS, *NOTES, EXP, RES, EXT]


def test_freeze_order_accepts_the_preregistered_order():
    rec = ds.freeze_order(_log(*GOOD))
    assert rec["ok"], rec["failures"]
    assert rec["manifestSha256"] == "a" * 64 and rec["summarySha256"] == "b" * 64
    # a growing log is checked as far as it goes (one NOTE without the other is a failure, never a state to keep)
    for k in range(2, len(GOOD) + 1):
        partial = ds.freeze_order(_log(*GOOD[:k]), complete=False)
        assert partial["ok"] == (GOOD[k - 1] != NOTES[0]), (k, partial["failures"])
    assert not ds.freeze_order(_log(*GOOD[:-1]))["ok"]                        # complete: the external START missing


@pytest.mark.parametrize("events", [
    [*PRE, STAGE, FETCH, NIGHTS, *NOTES, EXP, RES, EXT],                     # staging before the fetch
    [*PRE, FETCH, STAGE, *NOTES, NIGHTS, EXP, RES, EXT],                     # NOTE before nights
    [*PRE, FETCH, STAGE, NIGHTS, NOTES[0], EXP, NOTES[1], RES, EXT],         # a NOTE after the experiment start
    [*PRE, FETCH, STAGE, NIGHTS, *NOTES, NOTES[0], EXP, RES, EXT],           # duplicate NOTE
    [*PRE, FETCH, STAGE, NIGHTS, *NOTES, EXP, EXT, RES],                     # external before the RESULT
    [PRE[0], FETCH, STAGE, NIGHTS, *NOTES, EXP, RES, EXT],                   # no pre-registration digest NOTE
    [FETCH, *PRE, STAGE, NIGHTS, *NOTES, EXP, RES, EXT],                     # fetch before the pre-registration
    [*PRE, FETCH, STAGE, NIGHTS, *NOTES, RES, EXP, EXT],                     # RESULT before the experiment
    [*PRE, FETCH, STAGE, NIGHTS, NOTES[0], EXP, RES, EXT],                   # one NOTE missing
    [*PRE, FETCH, STAGE, NIGHTS, FETCH, *NOTES, EXP, RES, EXT],              # a fetch after nights
])
def test_freeze_order_rejects(events):
    assert not ds.freeze_order(_log(*events))["ok"]


def test_freeze_order_partial_log_rejects_an_experiment_without_freeze():
    assert not ds.freeze_order(_log(*PRE, EXP), complete=False)["ok"]
    assert not ds.freeze_order(_log(*PRE, FETCH, STAGE, NIGHTS, EXP), complete=False)["ok"]
    assert ds.freeze_order(_log(*PRE, *GATES), complete=False)["ok"]
    gates_only = _log(*PRE, "START x train.py naps-realistic-gates --out-run g", "RESULT naps-realistic-gates ok")
    assert ds.freeze_order(gates_only, complete=False)["ok"]


def test_freeze_order_other_run_name_and_reproduce_lines():
    events = [*PRE, FETCH, STAGE, NIGHTS, *NOTES,
              "START python -I tools/train/external.py nights --data d --reproduce",
              "NOTE ds006576 re-extraction into reproduce-x reproduces the logged manifest and yasa-summary digests",
              EXP, "RESULT naps-realistic gate G2 failed (stopped)",
              EXP.replace("--out-run naps-realistic", "--out-run naps-realistic-r2"),
              "RESULT naps-realistic-r2 rule (i): ...", EXT.replace("--exp naps-realistic", "--exp naps-realistic-r2")]
    assert ds.freeze_order(_log(*events), exp_run="naps-realistic-r2")["ok"]
    assert not ds.freeze_order(_log(*events), exp_run="naps-realistic-r3")["ok"]


def test_freeze_check_digests_files_and_lock(tmp_path, monkeypatch, capsys):
    data, keys, lock = make_dataset(tmp_path, monkeypatch, minutes=35)
    log = tmp_path / "commands.log"
    log.write_text("\n".join(_log(*PRE, FETCH, STAGE)) + "\n")
    assert run_nights(data, keys, lock, log) == 0
    capsys.readouterr()
    with open(log, "a") as f:
        f.write("\n".join(_log(*["NOTE filler"] * 200, EXP, RES, EXT)[-3:]) + "\n")
    rec = ds.freeze_check(data, log_path=log, lock_path=lock)
    assert rec["ok"], rec["failures"]
    # (4) a lock that differs from the manifest
    other = tmp_path / "other.lock"
    other.write_text(LOCK_TEXT.replace(ds.YASA_CLASSIFIER_SHA256, "e" * 64))
    assert any("classifier" in f for f in ds.freeze_check(data, log_path=log, lock_path=other)["failures"])
    other.write_text(LOCK_TEXT.replace(ds.OPENMP_LIB_SHA256, "e" * 64))
    assert any("openmpSha256" in f for f in ds.freeze_check(data, log_path=log, lock_path=other)["failures"])
    # (3) a night file changed after the freeze
    p = next((data / "nights" / "instantaneous").glob("*.night.json"))
    p.write_bytes(p.read_bytes().replace(b'"steps":[]', b'"steps":[ ]'))
    assert any("night files differ" in f for f in ds.freeze_check(data, log_path=log, lock_path=lock)["failures"])
    # (3) a manifest that is not the logged one
    (data / ds.MANIFEST_NAME).write_bytes((data / ds.MANIFEST_NAME).read_bytes() + b"\n")
    assert any("manifest.json digest" in f for f in ds.freeze_check(data, log_path=log, lock_path=lock)["failures"])


# =============================================================================== training night index =====

def test_no_ds006576_recording_can_enter_a_training_night_index(tmp_path, monkeypatch):
    """Section 13.1: the external night files live under data/external/ds006576/nights/<variant>/, outside
    data/nights/<variant>/, which is all that load_index reads; and assert_not_in_index rejects any that slips in."""
    assert ds.DATA_DIR.relative_to(ds.TRAIN_DIR / "data" / "external")
    assert not ds.DATA_DIR.is_relative_to(ds.TRAIN_DIR / "data" / "nights")
    data_root = tmp_path / "data"
    walch = {"id": "walch-46343-inst", "subject": "46343", "variant": "instantaneous", "sessionStartMs": 0,
             "sessionEndMs": 1, "hr": [], "steps": []}
    (data_root / "nights" / "instantaneous").mkdir(parents=True)
    (data_root / "nights" / "instantaneous" / "46343.night.json").write_text(json.dumps(walch))
    ext, keys, lock = make_dataset(data_root / "external", monkeypatch, minutes=35)
    assert run_nights(ext, keys, lock, tmp_path / "log") == 0
    assert list((ext / "nights" / "instantaneous").glob("*.night.json"))
    index = nightset.load_index(data_root / "nights", ["instantaneous"])
    assert index.subjects() == ["46343"]
    ds.assert_not_in_index(index)
    foreign = json.loads(next((ext / "nights" / "instantaneous").glob("*.night.json")).read_text())
    (data_root / "nights" / "instantaneous" / "x.night.json").write_text(json.dumps(foreign))
    polluted = nightset.load_index(data_root / "nights", ["instantaneous"])
    assert polluted.dataset_of(foreign["subject"]) == "ds006576"
    with pytest.raises(ValueError, match="ds006576"):
        ds.assert_not_in_index(polluted)


# ========================================================================= stager helpers (no YASA here) ====

class _W:
    def __init__(self, category, message):
        self.category, self.message = category, message


def test_unpickle_warning_allowlist(monkeypatch):
    class InconsistentVersionWarning(UserWarning):
        def __init__(self, estimator_name, current_sklearn_version, original_sklearn_version):
            super().__init__(f"Trying to unpickle estimator {estimator_name} from version {original_sklearn_version}")
            self.estimator_name = estimator_name
            self.current_sklearn_version = current_sklearn_version
            self.original_sklearn_version = original_sklearn_version

    ok = _W(InconsistentVersionWarning, InconsistentVersionWarning("LabelEncoder", "1.9.1", "0.24.2"))
    allowed, unexpected = external_stage.classify_unpickle_warnings([ok], "1.9.1")
    assert len(allowed) == 1 and unexpected == []
    for bad in (InconsistentVersionWarning("LGBMClassifier", "1.9.1", "0.24.2"),
                InconsistentVersionWarning("LabelEncoder", "1.9.1", "1.0"),
                InconsistentVersionWarning("LabelEncoder", "1.8.0", "0.24.2")):
        assert external_stage.classify_unpickle_warnings([_W(InconsistentVersionWarning, bad)], "1.9.1")[1]
    assert external_stage.classify_unpickle_warnings([_W(DeprecationWarning, DeprecationWarning("x"))], "1.9.1")[1]
    # the registered behaviour (section 13.4 without the requested amendment): every unpickle warning refuses
    monkeypatch.setattr(external_stage, "ALLOWED_UNPICKLE_WARNINGS", ())
    assert external_stage.classify_unpickle_warnings([ok], "1.9.1") == ([], [
        "InconsistentVersionWarning: Trying to unpickle estimator LabelEncoder from version 0.24.2"])
    assert "yasa" not in __import__("sys").modules                           # the stager imports YASA lazily


def test_installed_files_are_checked_against_the_wheel_record(tmp_path):
    import zipfile

    site = tmp_path / "site"
    (site / "pkg").mkdir(parents=True)
    content = b"x = 1\n"
    (site / "pkg" / "a.py").write_bytes(content)
    wheel = tmp_path / "pkg-1.0-py3-none-any.whl"
    with zipfile.ZipFile(wheel, "w") as z:
        z.writestr("pkg/a.py", content)
        z.writestr("pkg-1.0.dist-info/RECORD",
                   f"pkg/a.py,{external_stage.record_hash(content)},6\npkg-1.0.dist-info/RECORD,,\n")
    assert external_stage.installed_matches_wheel(wheel, site) == 1
    (site / "pkg" / "a.py").write_bytes(b"x = 2\n")
    with pytest.raises(external_stage.StageError):
        external_stage.installed_matches_wheel(wheel, site)


def test_synthetic_selftest_input_is_deterministic():
    a, b = external_stage.synthetic_arrays(minutes=2), external_stage.synthetic_arrays(minutes=2)
    assert ds.arrays_digest(a) == ds.arrays_digest(b)
    assert ds.central_choice(a["c3"], a["c4"])[0] == "C3-A2"


def test_stage_file_check():
    lock = ds.parse_lock(LOCK_TEXT)
    good = {"yasa": IDENTITY, "stages": ["W", "N1", "N2", "N3", "R"]}
    assert ds.check_stage_file(good, lock) == []
    assert ds.check_stage_file({**good, "stages": ["WAKE"]}, lock)
    assert ds.check_stage_file({**good, "yasa": {**IDENTITY, "version": "0.8.0"}}, lock)
    assert ds.lock_identity_failures(IDENTITY, lock) == []
    assert ds.lock_identity_failures({**IDENTITY, "wheelSha256": "0" * 64}, lock)
    # the OpenMP runtime loaded before LightGBM is part of the identity: a mismatch fails as for the wheel
    assert ds.check_stage_file({**good, "yasa": {**IDENTITY, "openmpSha256": "0" * 64}}, lock)
    assert ds.lock_identity_failures({**IDENTITY, "openmpSha256": "0" * 64}, lock)
    assert ds.lock_identity_failures({k: v for k, v in IDENTITY.items() if k != "openmpSha256"}, lock)
    other = ds.parse_lock(LOCK_TEXT.replace(ds.OPENMP_LIB_SHA256, "e" * 64))
    assert ds.lock_identity_failures({**IDENTITY, "openmpSha256": "e" * 64}, other)     # not the registered digest
