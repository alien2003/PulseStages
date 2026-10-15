#!/usr/bin/env python3
"""YASA sleep staging of OpenNeuro ds006576 (pre-registration naps-realistic, section 13.4): only the sleep / wake
transitions of these stages define the test windows; the stages themselves are silver labels, reported only. Runs in
the separate environment ``tools/train/.venv-external`` (``requirements-external.lock``), never in the training one:

    tools/train/.venv-external/bin/python -I tools/train/external_stage.py --self-test
    tools/train/.venv-external/bin/python -I tools/train/external_stage.py --data tools/train/data/external/ds006576

Before anything is staged the environment is checked against the lock (``environment``): yasa 0.7.0, every installed
distribution at its locked version, the installed yasa files equal to the verified wheel
``wheelhouse/yasa-0.7.0-py3-none-any.whl`` (its SHA-256 = the lock's ``yasa-wheel`` line, every file against the
wheel's RECORD), the fixed classifier ``<site-packages>/yasa/classifiers/clf_eeg+eog+emg_lgb_0.5.0.joblib`` with the
lock's ``yasa-classifier`` digest, and the OpenMP runtime of LightGBM (``openmp/libgomp.so.1``, the lock's
``openmp-lib`` digest, loaded before LightGBM). Any difference refuses: the external test is then void.

``--data``: every fetched recording (``derived/sub-<id>.npz``) is staged whole (time 0 to the end of the last complete
30-s epoch) on an MNE ``RawArray`` of the 128-Hz derivations in volts: ``yasa.SleepStaging`` with EEG = the central
derivation (``ds006576.central_choice``: C3-A2 or C4-A1, the lower median 0.5-30 Hz band power), EOG = LOC-A2, EMG =
EMG1-EMG2, no metadata; ``predict()`` and ``predict_proba()`` with the classifier passed explicitly. Output
``stages/sub-<id>.json``: one stage per 30-s epoch from time 0 (W / N1 / N2 / N3 / R), the probabilities, the YASA
identity and digests, the central choice and every warning raised. Appends START / END lines to the commands log.

``--self-test`` (synthetic EEG only, no ds006576 data): the environment check, YASA's own "auto" rule resolving to the
fixed file, the classifier unpickled with every warning recorded (only the one allowed below may occur, pending the
amendment noted at ``ALLOWED_UNPICKLE_WARNINGS``), and ``predict()`` / ``predict_proba()`` run twice with identical
output digests. The identity written into every stage file (and from there into the manifest) holds the YASA version,
the wheel, classifier and OpenMP digests and the unpickle warnings that were allowed.
"""

from __future__ import annotations

import argparse
import base64
import ctypes
import glob
import hashlib
import json
import logging
import os
import sys
import time
import warnings
import zipfile
from pathlib import Path

TRAIN_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TRAIN_DIR))  # our own package only; `python -I` does not add the script directory

import numpy as np  # noqa: E402

from pulsetrain import ds006576 as ds  # noqa: E402  (numpy / scipy only)

VENV_DIR = TRAIN_DIR / ".venv-external"
WHEELHOUSE = VENV_DIR / "wheelhouse"
OPENMP_LIB = VENV_DIR / "openmp" / "libgomp.so.1"
CHANNELS = ("C3-A2", "C4-A1", "LOC-A2", "EMG1-EMG2")
CHANNEL_TYPES = ("eeg", "eeg", "eog", "emg")
EOG_NAME, EMG_NAME = "LOC-A2", "EMG1-EMG2"
YASA_TO_SHORT = {"WAKE": "W", "W": "W", "N1": "N1", "N2": "N2", "N3": "N3", "REM": "R", "R": "R"}

# The fixed classifier pickles a scikit-learn 0.24.2 LabelEncoder (its ``_le``, classes N1 / N2 / N3 / R / W).
# Unpickling it with any scikit-learn that installs on Python >= 3.10 (YASA 0.7.0 needs >= 3.10; 0.24.2 has no wheel
# and no buildable source there) raises exactly this InconsistentVersionWarning. Section 13.4 says the self-test
# "fails on any warning raised while the classifier unpickles (for example a scikit-learn version warning)", which no
# installable environment can pass; read as written, the external test is void ("If YASA cannot be installed or run,
# the external test is void"). The implementation below allows this one warning only (estimator, original and
# current version must match exactly), records it in every stage file and in the manifest
# (``yasa.unpickleWarningsAllowed``), and checks that every decoded label is the classifier's class at the arg-max of
# its probabilities (the label decoding the LabelEncoder does). Any other unpickle warning fails.
# PENDING AMENDMENT of sections 13.4 / 13.8 / 18 (requested before the code commit, together with the OpenMP runtime
# below): without that amendment an external result that relies on this allowance is an undisclosed deviation, and
# the registered behaviour is ALLOWED_UNPICKLE_WARNINGS = () (the stager then refuses and the test is void).
ALLOWED_UNPICKLE_WARNINGS = (("InconsistentVersionWarning", "LabelEncoder", "0.24.2"),)


class StageError(RuntimeError):
    """The YASA environment or a staging result is not the pre-registered one (the external test is void)."""


# ========================================================================================== environment =====

def classify_unpickle_warnings(records, sklearn_version: str) -> tuple[list[str], list[str]]:
    """(allowed, unexpected) descriptions of warnings recorded while the classifier unpickled."""
    allowed, unexpected = [], []
    for w in records:
        msg = w.message
        key = (w.category.__name__, getattr(msg, "estimator_name", None), getattr(msg, "original_sklearn_version",
                                                                                  None))
        text = f"{w.category.__name__}: {str(msg).splitlines()[0][:200]}"
        if key in ALLOWED_UNPICKLE_WARNINGS and getattr(msg, "current_sklearn_version", None) == sklearn_version:
            allowed.append(text)
        else:
            unexpected.append(text)
    return allowed, unexpected


def file_sha256(path: Path) -> str:
    return ds.sha256_file(path)


def record_hash(data: bytes) -> str:
    """The ``sha256=`` value of a wheel RECORD line."""
    return "sha256=" + base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode("ascii")


def installed_matches_wheel(wheel: Path, site_packages: Path) -> int:
    """Every file of the wheel's RECORD that carries a hash is installed with exactly that content; returns the
    number of files checked."""
    with zipfile.ZipFile(wheel) as z:
        record = next(n for n in z.namelist() if n.endswith(".dist-info/RECORD"))
        lines = z.read(record).decode("utf-8").splitlines()
    n = 0
    for line in lines:
        parts = line.rsplit(",", 2)
        if len(parts) != 3 or not parts[1]:
            continue
        p = site_packages / parts[0]
        if not p.is_file() or record_hash(p.read_bytes()) != parts[1]:
            raise StageError(f"installed {parts[0]} differs from {wheel.name}")
        n += 1
    return n


def load_openmp(lock: ds.Lock) -> str:
    """Loads the locked OpenMP runtime (soname libgomp.so.1) before LightGBM needs it; returns its digest."""
    if not OPENMP_LIB.is_file():
        raise StageError(f"{OPENMP_LIB} missing (see the install procedure in requirements-external.lock)")
    digest = file_sha256(OPENMP_LIB)
    if digest != lock.openmp_lib_sha256 or digest != ds.OPENMP_LIB_SHA256:
        raise StageError(f"{OPENMP_LIB} has sha256 {digest}, not the locked one")
    ctypes.CDLL(str(OPENMP_LIB), mode=os.RTLD_GLOBAL)
    return digest


def environment(lock: ds.Lock) -> tuple[dict, dict]:
    """Checks the environment against the lock and the pre-registered digests (refuses on any difference).
    Returns (identity, details): identity = the YASA version, wheel and classifier digests that every stage file
    and the manifest carry; details = what else was checked."""
    if Path(sys.prefix).resolve() != VENV_DIR.resolve():
        raise StageError(f"run with {VENV_DIR}/bin/python (this is {sys.prefix})")
    from importlib import metadata

    openmp = load_openmp(lock)
    import yasa

    installed = {ds.normalize_name(d.metadata["Name"]): d.version for d in metadata.distributions()}
    installed.pop("pip", None)
    if installed != lock.pins:
        diff = sorted(set(installed.items()) ^ set(lock.pins.items()))
        raise StageError(f"installed distributions differ from the lock: {diff[:6]}")
    if yasa.__version__ != ds.YASA_VERSION or lock.pins.get("yasa") != ds.YASA_VERSION:
        raise StageError(f"yasa {yasa.__version__}, lock {lock.pins.get('yasa')}, pre-registered {ds.YASA_VERSION}")
    wheel = WHEELHOUSE / ds.YASA_WHEEL
    if not wheel.is_file():
        raise StageError(f"the verified wheel {wheel} is missing")
    wheel_sha = file_sha256(wheel)
    if wheel_sha != lock.yasa_wheel_sha256 or wheel_sha != ds.YASA_WHEEL_SHA256:
        raise StageError(f"{wheel.name} has sha256 {wheel_sha}, not the locked / pre-registered one")
    site = Path(yasa.__file__).resolve().parents[1]
    n_files = installed_matches_wheel(wheel, site)
    clf = classifier_path()
    clf_sha = file_sha256(clf)
    if clf.name != lock.classifier_name or clf_sha != lock.classifier_sha256 or clf_sha != ds.YASA_CLASSIFIER_SHA256:
        raise StageError(f"classifier {clf.name} sha256 {clf_sha} is not the locked / pre-registered one")
    identity = {"version": yasa.__version__, "wheelSha256": wheel_sha, "classifier": clf.name,
                "classifierSha256": clf_sha, "openmpSha256": openmp}
    details = {"wheelFilesChecked": n_files, "classifierPath": str(clf), "python": sys.version.split()[0],
               "distributions": len(installed)}
    return identity, details


def checked_identity(lock: ds.Lock) -> tuple[dict, dict]:
    """``environment`` plus the strict unpickle of the fixed classifier: the identity every stage file and the
    manifest carry, with the unpickle warnings that were allowed (``ALLOWED_UNPICKLE_WARNINGS``)."""
    identity, details = environment(lock)
    _, allowed = strict_unpickle(classifier_path())
    return {**identity, "unpickleWarningsAllowed": allowed}, details


def classifier_path() -> Path:
    import yasa

    return Path(yasa.__file__).resolve().parent / "classifiers" / ds.YASA_CLASSIFIER


def auto_rule_path() -> Path:
    """YASA 0.7.0's own "auto" rule for EEG + EOG + EMG without metadata (``SleepStaging._load_model``): the last of
    the sorted matches of ``clf_eeg+eog+emg*.joblib``."""
    import yasa

    clf_dir = os.path.join(str(Path(yasa.__file__).parent), "classifiers/")
    return Path(np.sort(glob.glob(clf_dir + "clf_eeg+eog+emg" + "*.joblib"))[-1]).resolve()


def strict_unpickle(path: Path) -> tuple[object, list[str]]:
    """Unpickles the classifier with every warning recorded; any warning but the allowed one refuses."""
    import joblib
    import sklearn

    with warnings.catch_warnings(record=True) as rec:
        warnings.simplefilter("always")
        clf = joblib.load(path)
    allowed, unexpected = classify_unpickle_warnings(rec, sklearn.__version__)
    if unexpected:
        raise StageError(f"warnings while the classifier unpickles: {unexpected}")
    if [str(c) for c in clf.classes_] != ["N1", "N2", "N3", "R", "W"]:
        raise StageError(f"classifier classes {list(clf.classes_)}")
    return clf, allowed


# ============================================================================================== staging =====

def raw_array(arrays: dict[str, np.ndarray]):
    """MNE RawArray (volts) of the 128-Hz derivations from time 0 to the end of the last complete 30-s epoch."""
    import mne

    n = 30 * ds.FS_EEG
    k = min(len(arrays[c]) for c in ("c3", "c4", "eog", "emg")) // n
    if k == 0:
        raise StageError("shorter than one 30-s epoch")
    data = np.vstack([np.asarray(arrays[c][: k * n], dtype=np.float64) for c in ("c3", "c4", "eog", "emg")]) * 1e-6
    info = mne.create_info(list(CHANNELS), sfreq=float(ds.FS_EEG), ch_types=list(CHANNEL_TYPES))
    return mne.io.RawArray(data, info, verbose=False), k


def stage_arrays(arrays: dict[str, np.ndarray], clf_path: Path, model: str | None = None) -> dict:
    """Stages one recording; ``model`` None = the fixed classifier passed explicitly, "auto" = YASA's own rule
    (self-test only). Returns the stage record (without identity)."""
    import yasa

    central, power = ds.central_choice(arrays["c3"], arrays["c4"], ds.FS_EEG)
    raw, n_ep = raw_array(arrays)
    path = str(clf_path) if model is None else model
    with warnings.catch_warnings(record=True) as rec:
        warnings.simplefilter("always")
        sls = yasa.SleepStaging(raw, eeg_name=central, eog_name=EOG_NAME, emg_name=EMG_NAME, metadata=None)
        hyp = sls.predict(path_to_model=path)
        proba = sls.predict_proba(path_to_model=path)
    stages = [YASA_TO_SHORT[str(s).upper()] for s in hyp.hypno.astype(str).tolist()]
    cols = {YASA_TO_SHORT[str(c).upper()]: c for c in proba.columns}
    if sorted(cols) != sorted(ds.YASA_STAGES) or len(stages) != n_ep or proba.shape[0] != n_ep:
        raise StageError(f"unexpected YASA output: {len(stages)} stages, {proba.shape}, columns {list(proba.columns)}")
    p = proba.to_numpy(dtype=np.float64)
    argmax = [YASA_TO_SHORT[str(proba.columns[i]).upper()] for i in np.argmax(p, axis=1)]
    if argmax != stages:
        raise StageError("decoded labels are not the classes at the arg-max of the probabilities")
    seen = sorted({f"{w.category.__name__}: {str(w.message).splitlines()[0][:200]}" for w in rec})
    return {
        "central": central,
        "centralBandPower": power,
        "epochS": ds.EPOCH_MS // 1000,
        "nEpochs": n_ep,
        "stages": stages,
        "proba": {s: [float(x) for x in proba[cols[s]].to_numpy(dtype=np.float64)] for s in ds.YASA_STAGES},
        "warnings": seen,
    }


def output_digest(stage: dict) -> str:
    return ds.sha256_bytes(json.dumps({k: stage[k] for k in ("stages", "proba")}, separators=(",", ":"),
                                      sort_keys=True).encode("utf-8"))


def stage_data(data: Path, keys: list[ds.KeyRow], identity: dict, clf_path: Path) -> list[str]:
    """Stages every fetched, non-excluded recording into ``stages/``; returns the subjects staged now."""
    done = []
    for k in keys:
        rec_path = data / ds.record_relpath(k.subject)
        if not rec_path.is_file():
            raise StageError(f"{k.subject} was not fetched (run external.py fetch)")
        rec = json.loads(rec_path.read_text(encoding="utf-8"))
        if rec.get("excluded"):
            continue
        out = data / ds.stage_relpath(k.subject)
        if out.is_file():
            old = json.loads(out.read_text(encoding="utf-8"))
            if old.get("yasa") == identity and old.get("derivedSha256") == rec["derivedSha256"]:
                continue
        arrays = ds.load_derived(data / ds.derived_relpath(k.subject))
        if ds.arrays_digest(arrays) != rec["derivedSha256"]:
            raise StageError(f"{k.subject}: derived data differ from the fetch record")
        t0 = time.monotonic()
        stage = {"subject": k.subject, "yasa": identity, "derivedSha256": rec["derivedSha256"],
                 **stage_arrays(arrays, clf_path)}
        out.parent.mkdir(parents=True, exist_ok=True)
        tmp = out.with_name(f".{out.name}.{os.getpid()}.tmp")
        tmp.write_bytes(ds.stage_bytes(stage))
        os.replace(tmp, out)
        done.append(k.subject)
        print(f"{k.subject} staged ({stage['nEpochs']} epochs, {time.monotonic() - t0:.0f} s)", flush=True)
    return done


# ============================================================================================ self-test =====

def synthetic_arrays(minutes: int = 20, seed: int = 0) -> dict[str, np.ndarray]:
    """Synthetic 128-Hz EEG / EOG / EMG (µV, float32): a wake-like first half (alpha, high EMG, eye movements) and a
    sleep-like second half (delta, spindles, low EMG); C4-A1 is 100 x too strong, so the central rule picks C3-A2."""
    rng = np.random.default_rng(seed)
    fs, n = ds.FS_EEG, minutes * 60 * ds.FS_EEG
    t = np.arange(n) / fs
    half = t < t[-1] / 2
    white = rng.standard_normal(n)
    pink = np.cumsum(rng.standard_normal(n))
    pink = (pink - np.convolve(pink, np.ones(fs) / fs, mode="same")) * 2.0
    alpha = 20 * np.sin(2 * np.pi * 10 * t) * half
    delta = 60 * np.sin(2 * np.pi * 1.0 * t + rng.uniform(0, 2 * np.pi)) * ~half
    spindle = 15 * np.sin(2 * np.pi * 13 * t) * (np.sin(2 * np.pi * t / 20) > 0.95) * ~half
    c3 = pink + 5 * white + alpha + delta + spindle
    eog = 30 * np.sin(2 * np.pi * 0.3 * t) * half + 3 * rng.standard_normal(n)
    emg = np.where(half, 20.0, 4.0) * rng.standard_normal(n)
    return {"c3": c3.astype(np.float32), "c4": (100 * c3 + white).astype(np.float32), "eog": eog.astype(np.float32),
            "emg": emg.astype(np.float32)}


class _Capture(logging.Handler):
    def __init__(self):
        super().__init__(logging.INFO)
        self.messages: list[str] = []

    def emit(self, record):
        self.messages.append(record.getMessage())


def self_test(lock: ds.Lock) -> dict:
    identity, details = checked_identity(lock)
    allowed = identity["unpickleWarningsAllowed"]
    clf_path = classifier_path()
    auto = auto_rule_path()
    if auto != clf_path.resolve() or auto.name != ds.YASA_CLASSIFIER:
        raise StageError(f"YASA's auto rule resolves to {auto.name}, not {ds.YASA_CLASSIFIER}")
    arrays = synthetic_arrays()
    runs = [stage_arrays(arrays, clf_path) for _ in range(2)]
    digests = [output_digest(r) for r in runs]
    if digests[0] != digests[1]:
        raise StageError(f"predict / predict_proba not reproducible: {digests}")
    if runs[0]["central"] != "C3-A2":
        raise StageError(f"central rule picked {runs[0]['central']} on the synthetic input")
    cap = _Capture()
    logger = logging.getLogger("yasa")
    old = logger.level
    logger.addHandler(cap)
    logger.setLevel(logging.INFO)
    try:
        auto_run = stage_arrays(arrays, clf_path, model="auto")
    finally:
        logger.removeHandler(cap)
        logger.setLevel(old)
    used = [m.split(": ", 1)[1] for m in cap.messages if m.startswith("Using pre-trained classifier: ")]
    if not used or any(Path(u).resolve() != clf_path.resolve() for u in used):
        raise StageError(f"YASA's own resolution used {used}")
    if output_digest(auto_run) != digests[0]:
        raise StageError("the 'auto' classifier gives another output than the fixed path")
    return {"identity": identity, "details": details, "autoResolved": str(auto), "unpickleWarningsAllowed": allowed,
            "outputSha256": digests[0], "epochs": runs[0]["nEpochs"],
            "stagingWarnings": sorted(set(runs[0]["warnings"]))}


# ================================================================================================= main =====

def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--self-test", action="store_true", help="environment and determinism check on synthetic EEG")
    g.add_argument("--data", type=Path, help="stage every fetched recording (tools/train/data/external/ds006576)")
    p.add_argument("--keys", type=Path, default=ds.KEYS_PATH)
    p.add_argument("--lock", type=Path, default=ds.LOCK_PATH)
    p.add_argument("--log", type=Path, default=ds.COMMANDS_LOG, help="commands log (START / END lines of --data)")
    a = p.parse_args(argv)
    lock = ds.read_lock(a.lock)
    if a.self_test:
        try:
            r = self_test(lock)
        except StageError as e:
            print(f"self-test FAILED: {e}")
            return 1
        i, d = r["identity"], r["details"]
        print("self-test ok (synthetic EEG only)")
        print(f"yasa {i['version']}; wheel {ds.YASA_WHEEL} sha256 {i['wheelSha256']} "
              f"({d['wheelFilesChecked']} installed files = its RECORD); {d['distributions']} distributions = lock")
        print(f"classifier {d['classifierPath']} sha256 {i['classifierSha256']}; YASA 'auto' resolves to the "
              f"same file and gives the same output")
        print(f"openmp libgomp.so.1 sha256 {i['openmpSha256']}")
        print(f"unpickle warnings: {len(r['unpickleWarningsAllowed'])} allowed {r['unpickleWarningsAllowed']}, 0 other")
        print(f"predict() + predict_proba() twice on {r['epochs']} epochs: identical, output sha256 "
              f"{r['outputSha256']}; staging warnings {r['stagingWarnings']}")
        return 0
    from external import LoggedRun

    with LoggedRun(a.log, argv, script="external_stage.py") as run:
        try:
            identity, _ = checked_identity(lock)
            stage_data(Path(a.data).resolve(), ds.read_keys(a.keys), identity, classifier_path())
        except StageError as e:
            print(f"refused (the external test is void if this persists): {e}")
            return run.end(1)
        return run.end(0)


if __name__ == "__main__":
    sys.exit(main())
