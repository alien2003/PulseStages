#!/usr/bin/env bash
# On-device end-to-end suite of PulseStages against the app INSTALLED on a phone (tools/device-test/README.md).
#
#   mise exec -- tools/device-test/run-device-tests.sh                 # build the test APK, run every step
#   mise exec -- tools/device-test/run-device-tests.sh cleanup-only    # only remove the suite's test records
#
# What this script does to the phone, and nothing else:
#   - installs ONLY the test APK (io.github.alien2003.pulsestages.test) and uninstalls ONLY that package at the end;
#     the app itself is never installed, updated, downgraded, cleared or uninstalled;
#   - runs the test classes one by one with `am instrument` (each run force-stops the app first: Android does that);
#   - sets "stay awake while charging over USB" for the run and restores the original setting on exit;
#   - pulls the reports from /sdcard/Android/data/io.github.alien2003.pulsestages/files/e2e and removes that
#     directory afterwards;
#   - opens PulseStages once at the end and returns to the home screen, so that WorkManager registers the app's
#     hourly and daily runs again after the force-stops.
# Every adb call is time-limited. The exit trap (EXIT, INT, TERM) always runs the cleanup step, then the "after"
# snapshot and the comparison when the run stopped early.

set -Eeuo pipefail

readonly APP_ID="io.github.alien2003.pulsestages"
readonly TEST_ID="${APP_ID}.test"
readonly RUNNER="${TEST_ID}/androidx.test.runner.AndroidJUnitRunner"
readonly E2E_PACKAGE="${APP_ID}.e2e"
readonly DEVICE_E2E_DIR="/sdcard/Android/data/${APP_ID}/files/e2e"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPO_ROOT
readonly TEST_APK="${REPO_ROOT}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
readonly OUT_ROOT="${REPO_ROOT}/tools/device-test/out"

# The installed app: the v0.1.0 release's debug build.
EXPECTED_VERSION_CODE="${EXPECTED_VERSION_CODE:-10099}"
EXPECTED_VERSION_NAME="${EXPECTED_VERSION_NAME:-0.1.0}"

MODE="run"
SKIP_BUILD=0
KEEP_GOING=0
REARM=1
SERIAL="${ANDROID_SERIAL:-}"
UNLOCK_TIMEOUT=300
INSTRUMENT_ARGS=()

# State for the exit trap.
TEST_APK_INSTALLED=0
INSTRUMENTED=0
SNAPSHOT_BEFORE_OK=0
AFTER_DONE=0
CLEANUP_OK=0
STAY_ON_ORIGINAL=""
STAY_ON_CHANGED=0
OUT_DIR=""
RUN_ID=""
DEVICE_START=""
RESULTS=()
FAILED=0

usage() {
  cat <<'EOF'
Usage: tools/device-test/run-device-tests.sh [run|cleanup-only] [options]

  run            build the test APK and run every step (default)
  cleanup-only   only delete the suite's test records (ps-e2e:) from Health Connect and verify

Options:
  --skip-build               use the test APK already built
  --keep-going               continue with the next steps after a failed one
  --serial SERIAL            the phone to use (default: the only one connected)
  --unlock-timeout SECONDS   how long to wait for the phone to be unlocked (default 300)
  --shadow-history-days N    days of history the shadow pipeline covers (0 = all; default: all that fits the budget)
  --read-budget N            Health Connect read calls the whole run may make (default 6000)
  --reads-per-15min N        read calls per 15 minutes (default 1500)
  --no-rearm                 do not open the app at the end
  -h, --help                 this help
EOF
}

log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '%s WARNING: %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die() {
  printf '%s ERROR: %s\n' "$(date '+%H:%M:%S')" "$*" >&2
  exit 1
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      run | cleanup-only) MODE="$1" ;;
      --skip-build) SKIP_BUILD=1 ;;
      --keep-going) KEEP_GOING=1 ;;
      --no-rearm) REARM=0 ;;
      --serial)
        SERIAL="${2:?--serial needs a value}"
        shift
        ;;
      --unlock-timeout)
        UNLOCK_TIMEOUT="${2:?--unlock-timeout needs a value}"
        shift
        ;;
      --shadow-history-days)
        INSTRUMENT_ARGS+=(-e e2eShadowHistoryDays "${2:?--shadow-history-days needs a value}")
        shift
        ;;
      --read-budget)
        INSTRUMENT_ARGS+=(-e e2eReadBudget "${2:?--read-budget needs a value}")
        shift
        ;;
      --reads-per-15min)
        INSTRUMENT_ARGS+=(-e e2eReadsPer15Min "${2:?--reads-per-15min needs a value}")
        shift
        ;;
      -h | --help)
        usage
        exit 0
        ;;
      *)
        usage >&2
        die "unknown argument: $1"
        ;;
    esac
    shift
  done
  [[ "$UNLOCK_TIMEOUT" =~ ^[0-9]+$ ]] || die "--unlock-timeout must be a number of seconds"
}

# --- adb ---------------------------------------------------------------------------------------------------------

ADB="${ADB_WIN:-adb.exe}"
ADB_TARGET=()

resolve_adb() {
  if ! command -v "$ADB" >/dev/null 2>&1; then
    if command -v adb >/dev/null 2>&1; then
      ADB="adb"
    else
      die "adb not found: run through 'mise exec --' (ADB_WIN from .mise.toml) or set ADB_WIN to adb.exe's path"
    fi
  fi
}

# A path on this machine as adb can read it: the Windows adb.exe (WSL interop) needs a Windows path.
host_path() {
  if [[ "$ADB" == *.exe ]] && command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"
  else
    printf '%s' "$1"
  fi
}

# adb_t SECONDS ARGS...: every adb call is time-limited.
adb_t() {
  local seconds="$1"
  shift
  timeout --kill-after=10 "$seconds" "$ADB" "${ADB_TARGET[@]}" "$@"
}

# shell_t SECONDS COMMAND...: adb shell with the carriage returns of adb.exe removed.
shell_t() {
  local seconds="$1"
  shift
  adb_t "$seconds" shell "$@" | tr -d '\r'
}

select_device() {
  local devices unauthorized
  devices="$(adb_t 60 devices | tr -d '\r' | awk 'NR > 1 && $2 == "device" { print $1 }')" || die "adb devices failed"
  unauthorized="$(adb_t 30 devices | tr -d '\r' | awk 'NR > 1 && $2 == "unauthorized" { print $1 }')" || true
  if [[ -n "$unauthorized" ]]; then
    warn "a phone is connected but not authorized: unlock it and accept the 'Allow USB debugging' prompt"
  fi
  if [[ -n "$SERIAL" ]]; then
    grep -qx -- "$SERIAL" <<<"$devices" || die "the phone $SERIAL is not connected (connected: ${devices:-none})"
  else
    local count
    count="$(grep -c . <<<"$devices" || true)"
    [[ "$count" == "1" ]] || die "exactly one phone must be connected (found ${count}: ${devices//$'\n'/, }); use --serial"
    SERIAL="$devices"
  fi
  ADB_TARGET=(-s "$SERIAL")
  log "phone: $SERIAL ($(shell_t 20 getprop ro.product.manufacturer) $(shell_t 20 getprop ro.product.model), Android $(shell_t 20 getprop ro.build.version.release))"
}

# --- preflight ---------------------------------------------------------------------------------------------------

check_installed_app() {
  local dump version_code version_name
  dump="$(shell_t 60 dumpsys package "$APP_ID")" || die "dumpsys package failed"
  version_code="$(grep -o -m1 'versionCode=[0-9]*' <<<"$dump" | cut -d= -f2 || true)"
  version_name="$(grep -o -m1 'versionName=[^ ]*' <<<"$dump" | cut -d= -f2 || true)"
  [[ -n "$version_code" ]] || die "PulseStages ($APP_ID) is not installed; this suite never installs it"
  [[ "$version_code" == "$EXPECTED_VERSION_CODE" ]] ||
    die "installed PulseStages has versionCode $version_code (name $version_name), expected $EXPECTED_VERSION_CODE (set EXPECTED_VERSION_CODE to override)"
  log "installed PulseStages: versionName $version_name, versionCode $version_code"
}

apksigner_bin() {
  local sdk="${ANDROID_HOME:-${REPO_ROOT}/.tooling/android-sdk}"
  find "$sdk/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -n 1
}

cert_digest() {
  local signer="$1" apk="$2"
  # "Signer #1 certificate SHA-256 digest: …" (older apksigner) or "V2 Signer: certificate SHA-256 digest: …".
  "$signer" verify --print-certs "$apk" 2>/dev/null | sed -n -E 's/^.*[Ss]igner.*certificate SHA-256 digest:[[:space:]]*//p' |
    head -n 1 | tr -d ': ' | tr 'A-F' 'a-f'
}

check_signing() {
  local signer tmp device_path app_digest test_digest key_digest
  signer="$(apksigner_bin)"
  [[ -x "$signer" ]] || die "apksigner not found under \$ANDROID_HOME/build-tools (run through 'mise exec --')"
  tmp="$(mktemp -d)"
  device_path="$(shell_t 30 pm path "$APP_ID" | sed -n 's/^package://p' | grep 'base\.apk$' | head -n 1 || true)"
  [[ -n "$device_path" ]] || die "cannot find the installed APK of $APP_ID"
  adb_t 300 pull "$device_path" "$(host_path "$tmp/base.apk")" >/dev/null || die "cannot pull the installed APK to check its signature"
  app_digest="$(cert_digest "$signer" "$tmp/base.apk")"
  test_digest="$(cert_digest "$signer" "$TEST_APK")"
  rm -rf "$tmp"
  [[ -n "$app_digest" && -n "$test_digest" ]] || die "cannot read the signing certificates"
  [[ "$app_digest" == "$test_digest" ]] ||
    die "the installed app is signed with $app_digest, the test APK with $test_digest: the instrumentation would be refused"
  if [[ -f "${HOME}/.android/debug.keystore" ]] && command -v keytool >/dev/null 2>&1; then
    key_digest="$(keytool -list -v -keystore "${HOME}/.android/debug.keystore" -storepass android -alias androiddebugkey 2>/dev/null |
      sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ': ' | tr 'A-F' 'a-f' | head -n 1)"
    [[ "$key_digest" == "$app_digest" ]] ||
      die "the installed app is not signed with ~/.android/debug.keystore (${key_digest:-unreadable}): this suite is only for that build"
  else
    warn "cannot read ~/.android/debug.keystore with keytool; only the test APK's certificate was compared"
  fi
  log "signing certificate of the app and the test APK: $app_digest"
}

# 0 when the screen is on and no lock screen is shown (the UI tour checks this again on the phone itself).
phone_unlocked() {
  local power policy
  power="$(shell_t 20 dumpsys power)" || return 1
  grep -q 'mWakefulness=Awake' <<<"$power" || return 1
  policy="$(shell_t 30 dumpsys window policy)" || return 1
  if grep -Eq '^[[:space:]]*(showing|mIsShowing|mShowingLockscreen|mDreamingLockscreen|isKeyguardShowing|mKeyguardShowing)=true' <<<"$policy"; then
    return 1
  fi
  return 0
}

wait_for_unlock() {
  phone_unlocked && return 0
  log "The phone is locked or its screen is off. UNLOCK IT NOW (PIN, pattern, fingerprint or face) and leave it on;"
  log "waiting up to ${UNLOCK_TIMEOUT} s. Do not lock it again or fold/unfold it until the run is over."
  shell_t 15 input keyevent KEYCODE_WAKEUP >/dev/null || true
  local waited=0
  while ((waited < UNLOCK_TIMEOUT)); do
    sleep 3
    waited=$((waited + 3))
    if phone_unlocked; then
      log "unlocked"
      return 0
    fi
  done
  return 1
}

# Waits (at most 10 minutes) while the app's own background work is running: the next instrumentation force-stops the app.
wait_for_app_work() {
  local waited=0 active
  while ((waited < 600)); do
    active="$(shell_t 60 dumpsys jobscheduler | sed -n '/^  Active jobs:/,/^  [A-Z]/p' | grep -c "$APP_ID" || true)"
    [[ "${active:-0}" == "0" ]] && return 0
    if ((waited == 0)); then
      log "PulseStages' own background work is running right now; waiting for it to finish (at most 10 min)"
    fi
    sleep 10
    waited=$((waited + 10))
  done
  warn "PulseStages' own background work is still running; continuing (Android stops it when the next test starts)"
}

set_stay_on() {
  STAY_ON_ORIGINAL="$(shell_t 20 settings get global stay_on_while_plugged_in)" || die "cannot read stay_on_while_plugged_in"
  shell_t 20 svc power stayon usb >/dev/null || die "cannot set 'svc power stayon usb'"
  STAY_ON_CHANGED=1
  log "stay awake over USB: on for the run (original stay_on_while_plugged_in=${STAY_ON_ORIGINAL})"
}

restore_stay_on() {
  ((STAY_ON_CHANGED)) || return 0
  if [[ -z "$STAY_ON_ORIGINAL" || "$STAY_ON_ORIGINAL" == "null" ]]; then
    shell_t 20 settings delete global stay_on_while_plugged_in >/dev/null || warn "could not restore stay_on_while_plugged_in"
  else
    shell_t 20 settings put global stay_on_while_plugged_in "$STAY_ON_ORIGINAL" >/dev/null || warn "could not restore stay_on_while_plugged_in"
  fi
  STAY_ON_CHANGED=0
  log "stay awake over USB: restored to ${STAY_ON_ORIGINAL}"
}

# --- build, install, uninstall -----------------------------------------------------------------------------------

build_test_apk() {
  ((SKIP_BUILD)) && return 0
  log "building the test APK (same sources and version as the installed app: ${EXPECTED_VERSION_NAME} / ${EXPECTED_VERSION_CODE})"
  local gradle=(./gradlew)
  command -v mise >/dev/null 2>&1 && gradle=(mise exec -- ./gradlew)
  (cd "$REPO_ROOT" && "${gradle[@]}" :app:assembleDebugAndroidTest \
    -PversionName="$EXPECTED_VERSION_NAME" -PversionCode="$EXPECTED_VERSION_CODE" --console=plain -q) || die "the test APK did not build"
}

install_test_apk() {
  [[ -f "$TEST_APK" ]] || die "no test APK at $TEST_APK (build it, or drop --skip-build)"
  log "installing ONLY the test APK ($TEST_ID). If the phone asks to confirm an installation over USB, tap Install."
  local output
  output="$(adb_t 300 install -r -t "$(host_path "$TEST_APK")" 2>&1 | tr -d '\r')" || true
  grep -q '^Success' <<<"$output" || die "installing the test APK failed: $output"
  TEST_APK_INSTALLED=1
  shell_t 30 pm list instrumentation | grep -q "$RUNNER" || die "the instrumentation $RUNNER is not registered"
}

uninstall_test_package() {
  local package="$TEST_ID"
  # Only the test package, never the app.
  if [[ "$package" != "${APP_ID}.test" || "$package" == "$APP_ID" ]]; then
    die "refusing to uninstall $package"
  fi
  if adb_t 120 uninstall "$package" >/dev/null 2>&1; then
    log "uninstalled the test package $package (the app stays installed)"
    TEST_APK_INSTALLED=0
  else
    warn "could not uninstall $package; remove it with: adb uninstall $package"
  fi
}

# --- steps -------------------------------------------------------------------------------------------------------

# run_step NAME CLASS TIMEOUT_SECONDS: one test class in its own instrumentation; streams its progress lines.
run_step() {
  local name="$1" class="$2" seconds="$3" output="${OUT_DIR}/instrumentation-${1}.txt" result
  log "== ${name} (${class}, at most $((seconds / 60)) min)"
  INSTRUMENTED=1
  local errexit=0
  [[ $- == *e* ]] && errexit=1
  set +e
  adb_t "$seconds" shell am instrument -w -r -e class "${E2E_PACKAGE}.${class}" -e e2eRunId "$RUN_ID" "${INSTRUMENT_ARGS[@]}" "$RUNNER" 2>&1 |
    tr -d '\r' | tee "$output" | while IFS= read -r line; do
    case "$line" in
      "INSTRUMENTATION_STATUS: e2e="*) printf '      %s\n' "${line#INSTRUMENTATION_STATUS: e2e=}" ;;
    esac
  done
  local codes=("${PIPESTATUS[@]}")
  ((errexit)) && set -e
  if ((codes[0] == 124 || codes[0] == 137)); then
    result="TIMEOUT"
    # The instrumentation may still be running on the phone: stopping the app ends it.
    shell_t 30 am force-stop "$APP_ID" >/dev/null || true
  elif grep -q 'shortMsg=Process crashed' "$output"; then
    result="CRASHED"
  elif grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(1|2)$' "$output"; then
    result="FAILED"
  elif grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(3|4)$' "$output"; then
    result="SKIPPED"
  elif grep -q '^INSTRUMENTATION_CODE: -1$' "$output" && grep -q '^OK (' "$output"; then
    result="PASSED"
  else
    result="FAILED"
  fi
  RESULTS+=("${name}: ${result}")
  log "== ${name}: ${result}"
  if [[ "$result" != "PASSED" && "$result" != "SKIPPED" ]]; then
    sed -n '/^INSTRUMENTATION_STATUS: stack=/,/^INSTRUMENTATION_STATUS/p' "$output" | head -n 25 | sed 's/^/      /' || true
    FAILED=1
    return 1
  fi
  return 0
}

main_steps() {
  run_step SnapshotBefore E2eSnapshotBeforeTest 2400 || die "no snapshot before the run: nothing is written, stopping"
  SNAPSHOT_BEFORE_OK=1
  local step name class seconds
  for step in "Schedule ScheduleReadOnlyE2eTest 300" "ShadowPipeline ShadowPipelineE2eTest 7200" "RealWrite RealWriteE2eTest 600" \
    "UiTour ReadOnlyUiTourE2eTest 1800"; do
    read -r name class seconds <<<"$step"
    if [[ "$name" == "UiTour" ]] && ! wait_for_unlock; then
      RESULTS+=("UiTour: NOT RUN (the phone stayed locked)")
      FAILED=1
      ((KEEP_GOING)) || return 1
      continue
    fi
    [[ "$name" == "ShadowPipeline" ]] && log "   (this step reads the whole history; it may pause to stay within Health Connect's rate limit)"
    run_step "$name" "$class" "$seconds" || ((KEEP_GOING)) || return 1
  done
  run_step SnapshotAfter E2eSnapshotAfterTest 2400 || true
  run_step Compare E2eCompareTest 300 || true
  AFTER_DONE=1
}

pull_results() {
  [[ -n "$OUT_DIR" ]] || return 1
  if adb_t 600 pull "$DEVICE_E2E_DIR" "$(host_path "$OUT_DIR")" >/dev/null 2>&1; then
    log "reports pulled to ${OUT_DIR}/e2e"
  else
    warn "could not pull ${DEVICE_E2E_DIR}"
    return 1
  fi
  if [[ -n "$DEVICE_START" ]]; then
    adb_t 120 logcat -d -v threadtime -T "$DEVICE_START" -s PulseStagesE2E:V PulseStages:V TestRunner:V AndroidRuntime:E \
      >"${OUT_DIR}/logcat.txt" 2>/dev/null || warn "could not save logcat"
  fi
  return 0
}

remove_device_reports() {
  [[ "$DEVICE_E2E_DIR" == "/sdcard/Android/data/${APP_ID}/files/e2e" ]] || return 1
  if shell_t 60 rm -rf "$DEVICE_E2E_DIR" >/dev/null; then
    log "removed ${DEVICE_E2E_DIR} from the phone"
  else
    warn "could not remove ${DEVICE_E2E_DIR}"
  fi
}

rearm_app() {
  ((REARM && INSTRUMENTED)) || return 0
  log "opening PulseStages once so that WorkManager registers its hourly and daily runs again, then the home screen"
  shell_t 60 am start -W -n "${APP_ID}/.MainActivity" >/dev/null || warn "could not open PulseStages: open it once by hand"
  sleep 5
  shell_t 15 input keyevent KEYCODE_HOME >/dev/null || true
}

summary() {
  printf '\n===== PulseStages on-device E2E: %s (%s) =====\n' "$RUN_ID" "$MODE"
  local entry
  for entry in "${RESULTS[@]}"; do printf '  %s\n' "$entry"; done
  if [[ -n "$OUT_DIR" && -d "${OUT_DIR}/e2e" ]]; then
    [[ -f "${OUT_DIR}/e2e/compare.md" ]] && printf '  Health Connect before/after: %s\n' "${OUT_DIR}/e2e/compare.md"
    [[ -f "${OUT_DIR}/e2e/shadow/summary.md" ]] && printf '  Shadow pipeline:             %s\n' "${OUT_DIR}/e2e/shadow/summary.md"
    [[ -d "${OUT_DIR}/e2e/ui" ]] && printf '  Screenshots:                 %s\n' "${OUT_DIR}/e2e/ui"
  fi
  [[ -n "$OUT_DIR" ]] && printf '  Everything:                  %s\n' "$OUT_DIR"
  if ((!CLEANUP_OK)) && ((INSTRUMENTED)); then
    printf '  !! The cleanup did not confirm that no test record is left. Run: tools/device-test/run-device-tests.sh cleanup-only\n'
  fi
  if ((FAILED)); then
    printf '  Result: FAILED\n'
  else
    printf '  Result: PASSED\n'
  fi
}

finish() {
  local rc=$?
  trap '' INT TERM
  set +e
  ((rc != 0)) && FAILED=1
  if ((TEST_APK_INSTALLED)); then
    log "cleaning up (Ctrl-C is ignored now; every step is time-limited)"
    if run_step Cleanup CleanupE2eTest 900; then CLEANUP_OK=1; fi
    if ((SNAPSHOT_BEFORE_OK && !AFTER_DONE)); then
      run_step SnapshotAfter E2eSnapshotAfterTest 2400
      run_step Compare E2eCompareTest 300
      AFTER_DONE=1
    fi
    if pull_results && ((CLEANUP_OK)); then remove_device_reports; fi
    uninstall_test_package
  fi
  restore_stay_on
  rearm_app
  summary
  if ((rc != 0 || FAILED)); then exit 1; fi
  exit 0
}

on_signal() {
  warn "interrupted: cleaning up"
  RESULTS+=("interrupted by a signal")
  FAILED=1
  exit 130
}

main() {
  parse_args "$@"
  resolve_adb
  RUN_ID="$(date '+%Y%m%d-%H%M%S')"
  OUT_DIR="${OUT_ROOT}/${RUN_ID}"
  mkdir -p "$OUT_DIR"
  exec > >(tee -a "${OUT_DIR}/runner.log") 2>&1
  log "PulseStages on-device E2E, run ${RUN_ID} (${MODE}); adb: ${ADB}"
  trap finish EXIT
  trap on_signal INT TERM

  build_test_apk
  select_device
  check_installed_app
  check_signing
  wait_for_unlock || die "the phone stayed locked"
  wait_for_app_work
  set_stay_on
  # adb shell joins its arguments with spaces: the format is quoted for the phone's shell.
  DEVICE_START="$(shell_t 20 "date '+%m-%d %H:%M:%S.000'")" || DEVICE_START=""
  install_test_apk
  if [[ "$MODE" == "cleanup-only" ]]; then
    return 0
  fi
  main_steps || FAILED=1
}

main "$@"
