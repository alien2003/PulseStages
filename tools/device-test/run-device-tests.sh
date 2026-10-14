#!/usr/bin/env bash
# On-device end-to-end suite of PulseStages against the app INSTALLED on a phone (tools/device-test/README.md).
#
#   mise exec -- tools/device-test/run-device-tests.sh                 # build the test APK, run every step
#   mise exec -- tools/device-test/run-device-tests.sh cleanup-only    # only remove what the suite may have left
#
# What this script does to the phone, and nothing else:
#   - installs ONLY the test APK (io.github.alien2003.pulsestages.test) and uninstalls ONLY that package at the end;
#     the app itself is never installed, updated, downgraded, cleared or uninstalled;
#   - runs the test classes one by one with `am instrument` (Android force-stops the app when an instrumentation
#     starts and ends; inside the instrumentation the app's WorkManager is an in-memory one, so none of the app's
#     background work runs during the run);
#   - sets "stay awake while charging over USB" for the run and restores the original setting on exit (the original
#     is also kept in tools/device-test/out/, so that a later run restores it if this one could not);
#   - pulls the reports from /sdcard/Android/data/io.github.alien2003.pulsestages/files/e2e and removes that
#     directory once the cleanup is confirmed;
#   - opens PulseStages once at the end and returns to the home screen, so that WorkManager registers the app's
#     hourly and daily runs again after the force-stops.
# Every adb call is time-limited and reads no terminal input. The exit trap (EXIT; INT, TERM and HUP stop the current
# step at once) always runs the cleanup step, retried after a pause when it cannot confirm, then the "after" snapshot
# and the comparison when the run stopped early. INT, TERM and HUP are ignored while it cleans up.

set -Eeuo pipefail

readonly APP_ID="io.github.alien2003.pulsestages"
readonly TEST_ID="${APP_ID}.test"
readonly RUNNER="${TEST_ID}/io.github.alien2003.pulsestages.e2e.E2eRunner"
readonly E2E_PACKAGE="${APP_ID}.e2e"
readonly DEVICE_E2E_DIR="/sdcard/Android/data/${APP_ID}/files/e2e"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPO_ROOT
readonly TEST_APK="${REPO_ROOT}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
readonly OUT_ROOT="${REPO_ROOT}/tools/device-test/out"
MAIN_PID="$BASHPID"
readonly MAIN_PID

# The installed app: the v0.1.0 release's debug build.
EXPECTED_VERSION_CODE="${EXPECTED_VERSION_CODE:-10099}"
EXPECTED_VERSION_NAME="${EXPECTED_VERSION_NAME:-0.1.0}"

# Health Connect read calls one run may make (ReadBudget.DEFAULT_TOTAL), less what earlier runs on the same phone
# made in the last 24 hours; a run with less than MIN_RUN_BUDGET left does not start.
READ_BUDGET=4000
readonly MIN_RUN_BUDGET=1000
readonly WINDOW_MS=900000
readonly DAY_MS=86400000

MODE="run"
SKIP_BUILD=0
KEEP_GOING=0
REARM=1
SERIAL="${ANDROID_SERIAL:-}"
UNLOCK_TIMEOUT=300
INSTRUMENT_ARGS=()

# State for the exit trap.
TEST_APK_ATTEMPTED=0
INSTRUMENTED=0
STEP_ACTIVE=0
STEP_PID_FILE=""
SNAPSHOT_BEFORE_OK=0
AFTER_DONE=0
CLEANUP_OK=0
STAY_ON_ORIGINAL=""
STAY_ON_CHANGED=0
JOBS_BEFORE=-1
OUT_DIR=""
RUN_ID=""
DEVICE_START=""
TEE_PID=""
RESULTS=()
WARNINGS=()
LEFTOVERS=()
FAILED=0

usage() {
  cat <<'EOF'
Usage: tools/device-test/run-device-tests.sh [run|cleanup-only] [options]

  run            build the test APK and run every step (default)
  cleanup-only   only remove what the suite may have left (ps-e2e: records in Health Connect, the demo-mode flag)
                 and verify; needs no unlocked screen

Options:
  --skip-build               use the test APK already built
  --keep-going               continue with the next steps after a failed one
  --serial SERIAL            the phone to use (default: the only one connected)
  --unlock-timeout SECONDS   how long to wait for the phone to be unlocked (default 300)
  --shadow-history-days N    days of history the shadow pipeline covers (0 = all; default: all that fits the budget)
  --read-budget N            Health Connect read calls one run may make, earlier runs of the last 24 h included
                             (default 4000)
  --reads-per-15min N        read calls per 15 minutes (default 600)
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
        READ_BUDGET="${2:?--read-budget needs a value}"
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
  [[ "$READ_BUDGET" =~ ^[0-9]+$ ]] || die "--read-budget must be a number of read calls"
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

# adb_t SECONDS ARGS...: every adb call is time-limited, and none reads the terminal: `timeout` runs adb in a
# background process group, where a read from the terminal (adb shell forwards its stdin) would stop it until the
# timeout.
adb_t() {
  local seconds="$1"
  shift
  timeout --kill-after=10 "$seconds" "$ADB" "${ADB_TARGET[@]}" "$@" </dev/null 3>&- 4>&-
}

# adb_recorded PID_FILE SECONDS ARGS...: adb_t whose `timeout` process (the leader of its own process group) is written
# to PID_FILE, so that a signal can stop the step at once (stop_current_step).
adb_recorded() {
  local pid_file="$1" seconds="$2"
  shift 2
  timeout --kill-after=10 "$seconds" "$ADB" "${ADB_TARGET[@]}" "$@" </dev/null 3>&- 4>&- &
  local pid=$!
  printf '%s' "$pid" >"$pid_file"
  wait "$pid"
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

# The phone's serial as a file name.
serial_tag() { printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9._-' '_'; }

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

# Returns 0 when the screen is on and no lock screen is shown, 1 when the phone is locked or its screen is off, and 2
# when adb's output does not tell (the formats differ between Android versions and vendors; the UI tour checks the
# lock on the phone itself, with KeyguardManager, and waits there).
lock_state() {
  local power text
  power="$(shell_t 20 dumpsys power)" || return 2
  if grep -q 'mWakefulness=' <<<"$power" && ! grep -q 'mWakefulness=Awake' <<<"$power"; then
    return 1
  fi
  text="$(shell_t 30 dumpsys window policy || true)"$'\n'"$(shell_t 60 dumpsys activity activities || true)"
  if grep -Eq '^[[:space:]]*(showing|mIsShowing|mShowingLockscreen|mDreamingLockscreen|isKeyguardShowing|mKeyguardShowing)=true' <<<"$text"; then
    return 1
  fi
  if grep -Eq '^[[:space:]]*(showing|mIsShowing|mShowingLockscreen|isKeyguardShowing|mKeyguardShowing)=false' <<<"$text"; then
    return 0
  fi
  return 2
}

wake_screen() { shell_t 15 input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true; }

wait_for_unlock() {
  local state
  lock_state && state=0 || state=$?
  ((state == 0)) && return 0
  if ((state == 2)); then
    warn "adb cannot tell whether the phone is locked; continuing (the UI tour checks it on the phone and waits for it)"
    return 0
  fi
  log "The phone is locked or its screen is off. UNLOCK IT NOW (PIN, pattern, fingerprint or face) and leave it on"
  log "the home screen; waiting up to ${UNLOCK_TIMEOUT} s. Keep it unlocked and connected until the summary appears."
  wake_screen
  local waited=0
  while ((waited < UNLOCK_TIMEOUT)); do
    sleep 3
    waited=$((waited + 3))
    lock_state && state=0 || state=$?
    if ((state != 1)); then
      log "unlocked"
      return 0
    fi
    if ((waited % 30 == 0)); then
      log "still locked: UNLOCK IT NOW ($((UNLOCK_TIMEOUT - waited)) s left)"
      wake_screen
    fi
  done
  return 1
}

# The app's entries in JobScheduler's "Active jobs" and "Pending queue" (jobs running or about to run).
app_job_activity() {
  local dump
  dump="$(shell_t 60 dumpsys jobscheduler "$APP_ID")" || return 1
  {
    sed -n '/^  Active jobs:/,/^  [A-Z]/p' <<<"$dump"
    sed -n '/^  Pending queue:/,/^  [A-Z]/p' <<<"$dump"
  } | grep -c -F "$APP_ID" || true
}

# The app's jobs registered with JobScheduler (WorkManager's), or nothing when adb fails.
app_job_count() {
  local dump
  dump="$(shell_t 60 dumpsys jobscheduler "$APP_ID")" || return 1
  grep -F "JOB #" <<<"$dump" | grep -F "${APP_ID}/" | grep -oE '#u[0-9]+a?[0-9]*/[0-9-]+' | sort -u | grep -c . || true
}

# wait_for_app_work SECONDS: waits while the app's own background work runs or is about to run (the next
# instrumentation force-stops the app and would kill it). 1 when it is still running after SECONDS.
wait_for_app_work() {
  local limit="$1" waited=0 active
  while ((waited < limit)); do
    active="$(app_job_activity)" || return 0
    ((${active:-0} == 0)) && return 0
    if ((waited == 0)); then
      log "PulseStages' own background work is running or about to run; waiting for it to finish (at most $((limit / 60)) min)"
    fi
    sleep 10
    waited=$((waited + 10))
  done
  return 1
}

# Before each step: inside the run none of the app's jobs can be registered (the force-stops cancelled them); one that
# runs means the app was opened outside the suite. It is waited for and reported.
check_app_work_before_step() {
  local name="$1" active
  ((INSTRUMENTED)) || return 0
  active="$(app_job_activity)" || return 0
  ((${active:-0} == 0)) && return 0
  WARNINGS+=("PulseStages' own background job was running before the step ${name} (was the app opened during the run?)")
  warn "PulseStages' own background job is running before the step ${name}; the app was opened outside the suite?"
  wait_for_app_work 600 || warn "it is still running; the step ${name} stops it (Android force-stops the app)"
}

capture_schedule_before() {
  shell_t 60 dumpsys jobscheduler "$APP_ID" >"${OUT_DIR}/schedule-before.txt" 2>/dev/null || true
  JOBS_BEFORE="$(app_job_count)" || JOBS_BEFORE=-1
  log "PulseStages' background jobs registered before the run: ${JOBS_BEFORE} (JobScheduler, saved to schedule-before.txt)"
}

# --- stay awake --------------------------------------------------------------------------------------------------

stay_on_file() { printf '%s/.stay-on-original-%s' "$OUT_ROOT" "$(serial_tag)"; }

put_stay_on() {
  if [[ -z "$1" || "$1" == "null" ]]; then
    shell_t 20 settings delete global stay_on_while_plugged_in >/dev/null
  else
    shell_t 20 settings put global stay_on_while_plugged_in "$1" >/dev/null
  fi
}

# A run that could not restore the setting left its original in tools/device-test/out/: restore it first.
restore_leftover_stay_on() {
  local file value
  file="$(stay_on_file)"
  [[ -f "$file" ]] || return 0
  value="$(cat "$file")"
  log "an earlier run did not restore 'stay awake while charging' (stay_on_while_plugged_in=${value}): restoring it now"
  put_stay_on "$value" || die "cannot restore stay_on_while_plugged_in to ${value}"
  rm -f "$file"
}

set_stay_on() {
  local file
  file="$(stay_on_file)"
  STAY_ON_ORIGINAL="$(shell_t 20 settings get global stay_on_while_plugged_in)" || die "cannot read stay_on_while_plugged_in"
  [[ "$STAY_ON_ORIGINAL" =~ ^([0-9]+|null)$ ]] || die "unexpected stay_on_while_plugged_in: '${STAY_ON_ORIGINAL}'"
  mkdir -p "$OUT_ROOT"
  if ! { printf '%s' "$STAY_ON_ORIGINAL" >"${file}.tmp" && mv "${file}.tmp" "$file"; }; then
    die "cannot record the original stay_on_while_plugged_in"
  fi
  STAY_ON_CHANGED=1
  shell_t 20 svc power stayon usb >/dev/null || die "cannot set 'svc power stayon usb'"
  log "stay awake over USB: on for the run (original stay_on_while_plugged_in=${STAY_ON_ORIGINAL})"
}

restore_stay_on() {
  ((STAY_ON_CHANGED)) || return 0
  if put_stay_on "$STAY_ON_ORIGINAL"; then
    rm -f "$(stay_on_file)"
    STAY_ON_CHANGED=0
    log "stay awake over USB: restored to ${STAY_ON_ORIGINAL}"
  elif [[ "$STAY_ON_ORIGINAL" == "null" ]]; then
    LEFTOVERS+=("'stay awake while charging' is still on: $ADB -s $SERIAL shell settings delete global stay_on_while_plugged_in (the next run restores it too)")
  else
    LEFTOVERS+=("'stay awake while charging' is still on: $ADB -s $SERIAL shell settings put global stay_on_while_plugged_in ${STAY_ON_ORIGINAL} (the next run restores it too)")
  fi
}

# --- Health Connect reads of earlier runs ----------------------------------------------------------------------------

# tools/device-test/out/.read-history-<serial>.tsv: the suite's read calls on this phone, per minute (epoch ms, calls),
# from the ledgers of earlier runs. A new run waits until the last 15-minute window of an earlier one is over and may
# spend only what is left of READ_BUDGET in the last 24 hours; nothing of it is kept on the phone.
read_history_file() { printf '%s/.read-history-%s.tsv' "$OUT_ROOT" "$(serial_tag)"; }

device_now_ms() {
  local seconds
  seconds="$(shell_t 20 date +%s)" || return 1
  [[ "$seconds" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "$((seconds * 1000))"
}

apply_read_history() {
  local history now newest used budget wait_s oldest
  history="$(read_history_file)"
  if [[ ! -s "$history" ]]; then
    INSTRUMENT_ARGS+=(-e e2eReadBudget "$READ_BUDGET")
    return 0
  fi
  if ! now="$(device_now_ms)"; then
    warn "cannot read the phone's clock: the reads of earlier runs are not taken into account"
    INSTRUMENT_ARGS+=(-e e2eReadBudget "$READ_BUDGET")
    return 0
  fi
  awk -F'\t' -v now="$now" -v day="$DAY_MS" '$1 + 0 > now - day' "$history" >"${history}.tmp" && mv "${history}.tmp" "$history"
  newest="$(awk -F'\t' 'BEGIN { m = 0 } $1 + 0 > m { m = $1 + 0 } END { printf "%.0f", m }' "$history")"
  oldest="$(awk -F'\t' 'NR == 1 || $1 + 0 < m { m = $1 + 0 } END { printf "%.0f", m + 0 }' "$history")"
  used="$(awk -F'\t' '{ s += $2 } END { printf "%.0f", s }' "$history")"
  # A minute bucket holds reads made up to its end; a few seconds of margin for the clocks.
  wait_s=$(((newest + 60000 + WINDOW_MS + 5000 - now) / 1000))
  if ((newest > 0 && wait_s > 0)); then
    log "an earlier run of the suite read Health Connect less than 15 minutes ago: waiting $(((wait_s + 59) / 60)) min,"
    log "so that this run does not add to that 15-minute window (Ctrl-C stops; nothing has been done yet)"
    sleep "$wait_s"
  fi
  budget=$((READ_BUDGET - used))
  if ((used > 0)); then
    log "earlier runs on this phone made ${used} Health Connect read calls in the last 24 hours: this run may make ${budget}"
  fi
  if ((budget < MIN_RUN_BUDGET)); then
    die "only ${budget} of ${READ_BUDGET} read calls are left for the last 24 hours; the oldest of the earlier reads leave that window in about $(((oldest + DAY_MS - now) / 3600000 + 1)) h"
  fi
  INSTRUMENT_ARGS+=(-e e2eReadBudget "$budget")
}

# After the reports are pulled: this run's ledger, per minute, into the history.
record_read_history() {
  local ledger="${OUT_DIR}/e2e/read-ledger.tsv" history
  [[ -f "$ledger" ]] || return 0
  history="$(read_history_file)"
  awk -F'\t' -v run="$RUN_ID" '$3 == run { m = int($1 / 60000) * 60000; s[m] += $2 } END { for (k in s) printf "%.0f\t%.0f\n", k, s[k] }' \
    "$ledger" >>"$history" || warn "could not record this run's reads in $history"
}

# --- build, install, uninstall -----------------------------------------------------------------------------------

build_test_apk() {
  ((SKIP_BUILD)) && return 0
  log "building the test APK (same sources and version as the installed app: ${EXPECTED_VERSION_NAME} / ${EXPECTED_VERSION_CODE})"
  local gradle=(./gradlew)
  command -v mise >/dev/null 2>&1 && gradle=(mise exec -- ./gradlew)
  (cd "$REPO_ROOT" && "${gradle[@]}" :app:assembleDebugAndroidTest \
    -PversionName="$EXPECTED_VERSION_NAME" -PversionCode="$EXPECTED_VERSION_CODE" --console=plain -q </dev/null 3>&- 4>&-) || die "the test APK did not build"
}

instrumentation_registered() {
  local list
  list="$(shell_t 30 pm list instrumentation)" || return 1
  grep -qF "$RUNNER" <<<"$list"
}

install_test_apk() {
  [[ -f "$TEST_APK" ]] || die "no test APK at $TEST_APK (build it, or drop --skip-build)"
  log "installing ONLY the test APK ($TEST_ID). If the phone asks to confirm an installation over USB, tap Install."
  local output
  # Before the call: an install the phone completes late, or one interrupted, is uninstalled by the exit trap too.
  TEST_APK_ATTEMPTED=1
  output="$(adb_t 300 install -r -t "$(host_path "$TEST_APK")" 2>&1 | tr -d '\r')" || true
  grep -q '^Success' <<<"$output" || die "installing the test APK failed: $output"
  instrumentation_registered || die "the instrumentation $RUNNER is not registered"
}

# 0 installed, 1 not installed, 2 unknown (adb failed).
test_package_state() {
  local packages
  packages="$(shell_t 30 pm list packages "$TEST_ID")" || return 2
  grep -qx "package:${TEST_ID}" <<<"$packages" && return 0
  return 1
}

uninstall_test_package() {
  local package="$TEST_ID" state
  # Only the test package, never the app.
  if [[ "$package" != "${APP_ID}.test" || "$package" == "$APP_ID" ]]; then
    die "refusing to uninstall $package"
  fi
  adb_t 120 uninstall "$package" >/dev/null 2>&1 || true
  test_package_state && state=0 || state=$?
  case "$state" in
    1) log "the test package $package is not installed (the app stays installed)" ;;
    *) LEFTOVERS+=("the test package may still be installed: $ADB -s $SERIAL uninstall $package") ;;
  esac
}

# --- steps -------------------------------------------------------------------------------------------------------

# Prints the suite's progress lines of an instrumentation's output.
progress_lines() {
  local line
  while IFS= read -r line; do
    case "$line" in
      "INSTRUMENTATION_STATUS: e2e="*) printf '      %s\n' "${line#INSTRUMENTATION_STATUS: e2e=}" ;;
    esac
  done
}

# Stops the step that is running: its adb, and the instrumentation on the phone (it goes on without adb; the suite
# runs in the app's process, so stopping the app ends it).
stop_current_step() {
  ((STEP_ACTIVE)) || return 0
  local pid=""
  [[ -n "$STEP_PID_FILE" && -s "$STEP_PID_FILE" ]] && pid="$(cat "$STEP_PID_FILE")"
  if [[ "$pid" =~ ^[0-9]+$ ]]; then
    kill -TERM -- "-${pid}" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  fi
  shell_t 30 am force-stop "$APP_ID" >/dev/null 2>&1 || true
  STEP_ACTIVE=0
}

# run_step NAME CLASS TIMEOUT_SECONDS: one test class in its own instrumentation; streams its progress lines as they
# come (an unbuffered sed drops adb.exe's carriage returns). The instrumentation runs in the background and is waited
# for, so that a signal interrupts the wait at once.
run_step() {
  local name="$1" class="$2" seconds="$3" output="${OUT_DIR}/instrumentation-${1}.txt" result rc
  check_app_work_before_step "$name"
  log "== ${name} (${class}, at most $((seconds / 60)) min)"
  INSTRUMENTED=1
  local rc_file="${OUT_DIR}/.rc-${name}"
  STEP_PID_FILE="${OUT_DIR}/.pid-${name}"
  rm -f "$rc_file" "$STEP_PID_FILE"
  STEP_ACTIVE=1
  {
    set +e
    adb_recorded "$STEP_PID_FILE" "$seconds" shell am instrument -w -r -e class "${E2E_PACKAGE}.${class}" -e e2eRunId "$RUN_ID" \
      "${INSTRUMENT_ARGS[@]}" "$RUNNER" 2>&1
    printf '%s' "$?" >"$rc_file"
  } | sed -u 's/\r$//' | tee "$output" | progress_lines &
  wait "$!" || true
  STEP_ACTIVE=0
  rc="$(cat "$rc_file" 2>/dev/null || true)"
  [[ "$rc" =~ ^[0-9]+$ ]] || rc=255
  rm -f "$rc_file" "$STEP_PID_FILE"
  if ((rc == 124 || rc == 137)); then
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
  for step in "ShadowPipeline ShadowPipelineE2eTest 10800" "RealWrite RealWriteE2eTest 2700" "UiTour ReadOnlyUiTourE2eTest 2700"; do
    read -r name class seconds <<<"$step"
    if [[ "$name" == "UiTour" ]] && ! wait_for_unlock; then
      RESULTS+=("UiTour: NOT RUN (the phone stayed locked)")
      FAILED=1
      ((KEEP_GOING)) || return 1
      continue
    fi
    [[ "$name" == "ShadowPipeline" ]] && log "   (this step reads the whole history; it pauses whenever it would exceed the reads allowed per 15 minutes)"
    run_step "$name" "$class" "$seconds" || ((KEEP_GOING)) || return 1
  done
  run_step SnapshotAfter E2eSnapshotAfterTest 2400 || true
  run_step Compare E2eCompareTest 300 || true
  AFTER_DONE=1
}

# The cleanup step, tried again after 1 and after 15 minutes (a full rate-limit window) when it cannot confirm.
# E2E_CLEANUP_PAUSES overrides the pauses (seconds; for the runner's own tests with a fake adb).
run_cleanup() {
  local attempt pause step pauses
  read -r -a pauses <<<"${E2E_CLEANUP_PAUSES:-60 900}"
  for attempt in 1 2 3; do
    pause=0
    ((attempt > 1)) && pause="${pauses[attempt - 2]:-0}"
    [[ "$pause" =~ ^[0-9]+$ ]] || pause=0
    if ((pause > 0)); then
      if ((pause >= 60)); then
        log "the cleanup could not confirm that nothing of the suite is left; trying again in $((pause / 60)) min (keep the phone connected)"
      else
        log "the cleanup could not confirm that nothing of the suite is left; trying again in ${pause} s (keep the phone connected)"
      fi
      sleep "$pause"
    fi
    step="Cleanup"
    ((attempt > 1)) && step="Cleanup-${attempt}"
    if run_step "$step" CleanupE2eTest 1200; then
      return 0
    fi
  done
  return 1
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
    LEFTOVERS+=("the reports are still on the phone: $ADB -s $SERIAL shell rm -rf $DEVICE_E2E_DIR")
  fi
}

rearm_app() {
  ((REARM && INSTRUMENTED)) || return 0
  log "opening PulseStages once so that WorkManager registers its hourly and daily runs again, then the home screen"
  log "(as when you open it yourself: an hourly check that became due during the run may run right now)"
  local output count=0 waited=0
  output="$(shell_t 60 am start -W -n "${APP_ID}/.MainActivity" 2>&1)" || output="Error: $output"
  if grep -q '^Error' <<<"$output"; then
    LEFTOVERS+=("PulseStages could not be opened at the end: open it once on the phone, so that its hourly and daily runs are scheduled again")
    return 0
  fi
  sleep 5
  shell_t 15 input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  if ((JOBS_BEFORE > 0)); then
    while ((waited <= 30)); do
      count="$(app_job_count)" || count=0
      ((${count:-0} > 0)) && break
      sleep 5
      waited=$((waited + 5))
    done
    if ((${count:-0} > 0)); then
      log "PulseStages' background jobs are registered again (${count})"
    else
      LEFTOVERS+=("PulseStages' background jobs are not registered again: open the app once on the phone")
    fi
  fi
  shell_t 60 dumpsys jobscheduler "$APP_ID" >"${OUT_DIR}/schedule-after.txt" 2>/dev/null || true
}

summary() {
  printf '\n===== PulseStages on-device E2E: %s (%s) =====\n' "$RUN_ID" "$MODE"
  local entry
  for entry in "${RESULTS[@]}"; do printf '  %s\n' "$entry"; done
  for entry in "${WARNINGS[@]}"; do printf '  warning: %s\n' "$entry"; done
  if [[ -n "$OUT_DIR" && -d "${OUT_DIR}/e2e" ]]; then
    [[ -f "${OUT_DIR}/e2e/compare.md" ]] && printf '  Health Connect before/after: %s\n' "${OUT_DIR}/e2e/compare.md"
    [[ -f "${OUT_DIR}/e2e/shadow/summary.md" ]] && printf '  Shadow pipeline:             %s\n' "${OUT_DIR}/e2e/shadow/summary.md"
    [[ -d "${OUT_DIR}/e2e/ui" ]] && printf '  Screenshots:                 %s\n' "${OUT_DIR}/e2e/ui"
  fi
  [[ -n "$OUT_DIR" ]] && printf '  Everything:                  %s\n' "$OUT_DIR"
  if ((!CLEANUP_OK)) && ((INSTRUMENTED)); then
    LEFTOVERS+=("the cleanup did not confirm that no record of the suite (ps-e2e:) is left and the demo-mode flag is restored: tools/device-test/run-device-tests.sh cleanup-only")
  fi
  if ((${#LEFTOVERS[@]} > 0)); then
    printf '  Left behind, and the command that fixes it:\n'
    for entry in "${LEFTOVERS[@]}"; do printf '  !! %s\n' "$entry"; done
  fi
  if ((FAILED)); then
    printf '  Result: FAILED\n'
  elif ((${#LEFTOVERS[@]} > 0)); then
    printf '  Result: INCOMPLETE\n'
  else
    printf '  Result: PASSED\n'
  fi
}

finish() {
  local rc=$?
  # Only the script itself cleans up (never a subshell of it).
  [[ "$BASHPID" == "$MAIN_PID" ]] || exit "$rc"
  trap '' INT TERM HUP
  set +e
  # The trap may run inside a redirected command (bash runs it when that command ends): back to the logger.
  exec 1>&4 2>&4
  ((rc != 0)) && FAILED=1
  stop_current_step
  if ((TEST_APK_ATTEMPTED)); then
    if instrumentation_registered; then
      log "cleaning up (Ctrl-C and closing the terminal no longer stop this; every step is time-limited)"
      if run_cleanup; then CLEANUP_OK=1; fi
      if ((SNAPSHOT_BEFORE_OK && !AFTER_DONE)); then
        run_step SnapshotAfter E2eSnapshotAfterTest 2400
        run_step Compare E2eCompareTest 300
        AFTER_DONE=1
      fi
    fi
    if pull_results; then
      record_read_history
      if ((CLEANUP_OK)); then remove_device_reports; fi
    fi
    uninstall_test_package
  fi
  restore_stay_on
  rearm_app
  summary
  local code=0
  ((rc != 0 || FAILED || ${#LEFTOVERS[@]} > 0)) && code=1
  # Back to the terminal itself, and a few seconds for the logger to write out the rest before the script exits.
  exec 1>&3 2>&3
  local ticks=0
  while [[ -n "$TEE_PID" ]] && kill -0 "$TEE_PID" 2>/dev/null && ((ticks < 50)); do
    sleep 0.1
    ticks=$((ticks + 1))
  done
  exit "$code"
}

on_signal() {
  [[ "$BASHPID" == "$MAIN_PID" ]] || exit 130
  trap '' INT TERM HUP
  exec 1>&4 2>&4
  printf '\n'
  warn "interrupted: stopping the current step, then cleaning up (a few minutes; keep the phone connected)"
  stop_current_step
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
  # fd 3: the terminal; fd 4: the logger, which ignores the signals that stop the run, so that the cleanup's output
  # still reaches the terminal and the log. Neither is handed to adb or Gradle.
  exec 3>&1
  exec > >(trap '' INT TERM HUP; exec tee -a "${OUT_DIR}/runner.log" 3>&-) 2>&1
  TEE_PID=$!
  exec 4>&1
  log "PulseStages on-device E2E, run ${RUN_ID} (${MODE}); adb: ${ADB}"
  trap finish EXIT
  trap on_signal INT TERM HUP
  INSTRUMENT_ARGS+=(-e e2eUnlockTimeoutSec "$UNLOCK_TIMEOUT")

  build_test_apk
  select_device
  restore_leftover_stay_on
  check_installed_app
  check_signing
  if [[ "$MODE" == "run" ]]; then
    apply_read_history
    wait_for_unlock || die "the phone stayed locked: unlock it (PIN, pattern, fingerprint or face) and start again"
    wait_for_app_work 600 ||
      die "PulseStages' own background work (a backfill or recompute?) has been running for 10 minutes: start again when it has finished"
    capture_schedule_before
    set_stay_on
  else
    # The cleanup needs no screen; a locked phone is fine.
    wake_screen
    wait_for_app_work 600 || warn "PulseStages' own background work is still running; the cleanup stops it (Android force-stops the app)"
    capture_schedule_before
  fi
  # adb shell joins its arguments with spaces: the format is quoted for the phone's shell.
  DEVICE_START="$(shell_t 20 "date '+%m-%d %H:%M:%S.000'")" || DEVICE_START=""
  install_test_apk
  if [[ "$MODE" == "cleanup-only" ]]; then
    return 0
  fi
  log "from now on: keep the phone connected, unlocked and untouched until the summary appears"
  main_steps || FAILED=1
}

main "$@"
