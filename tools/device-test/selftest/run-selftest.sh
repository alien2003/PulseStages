#!/usr/bin/env bash
# Self-test of run-device-tests.sh with a fake adb (no phone): the runner's control flow on a pseudo-terminal, as a
# user starts it — a normal run, Ctrl-C during a step, the terminal closed during the cleanup, a cleanup that has to be
# retried, a setting that cannot be restored (and its repair by the next run), the reads of an earlier run, a locked
# phone. Needs bash, python3 and coreutils; takes about two minutes.
#
#   tools/device-test/selftest/run-selftest.sh

set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="${HERE}/../run-device-tests.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILURES=0

# A fake checkout around a copy of the runner, and the fake phone's state.
setup() {
  ROOT="${WORK}/$1"
  mkdir -p "$ROOT/tools/device-test" "$ROOT/app/build/outputs/apk/androidTest/debug" "$ROOT/sdk/build-tools/36.0.0" "$ROOT/home" \
    "$ROOT/state"
  cp "$RUNNER" "$ROOT/tools/device-test/"
  echo apk >"$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  echo apk >"$ROOT/fake.apk"
  printf '#!/usr/bin/env bash\necho "Signer #1 certificate SHA-256 digest: aabbcc"\n' >"$ROOT/sdk/build-tools/36.0.0/apksigner"
  chmod +x "$ROOT/sdk/build-tools/36.0.0/apksigner"
}

# run CTRL_C_AT CLOSE_AT TOTAL [ENV=VALUE...] -- RUNNER_ARGS...: the runner on a pseudo-terminal; its screen in $ROOT/screen.
run() {
  local ctrl_c="$1" close="$2" total="$3"
  shift 3
  local vars=()
  while [[ "$1" != "--" ]]; do
    vars+=("$1")
    shift
  done
  shift
  (cd "$ROOT" && env FAKE_STATE="$ROOT/state" FAKE_APK="$ROOT/fake.apk" ADB_WIN="${HERE}/fake-adb" ANDROID_HOME="$ROOT/sdk" \
    HOME="$ROOT/home" E2E_CLEANUP_PAUSES="1 2" "${vars[@]}" \
    python3 -I "${HERE}/pty_drive.py" "$ctrl_c" "$close" "$total" bash tools/device-test/run-device-tests.sh --skip-build "$@" \
    >"$ROOT/screen" 2>"$ROOT/driver" </dev/null) || true
}

expect() {
  local what="$1"
  shift
  if "$@"; then
    printf '  ok    %s\n' "$what"
  else
    printf '  FAIL  %s\n' "$what"
    FAILURES=$((FAILURES + 1))
  fi
}

shows() { grep -qF -- "$1" "$ROOT/screen"; }
logged() { grep -qF -- "$1" "$ROOT"/tools/device-test/out/2*/runner.log; }
called() { grep -qF -- "$1" "$ROOT/state/calls.log"; }
not() { ! "$@"; }
classes() { cut -d' ' -f1 "$ROOT/state/instrumentations.log" | tr '\n' ' ' | sed 's/ $//'; }
stay_on_is() { [ "$(cat "$ROOT/state/stay-on" 2>/dev/null)" == "$1" ]; }
installed() { test -f "$ROOT/state/installed"; }
history_is() { [ "$(cut -f2 "$ROOT/tools/device-test/out/.read-history-FAKE123.tsv" | sort -n | tr '\n' ' ')" == "$1" ]; }
lasted_at_most() { awk -v limit="$1" '{ for (i = 1; i <= NF; i++) if ($i == "after") t = $(i + 1) } END { exit !(t + 0 <= limit) }' "$ROOT/driver"; }

echo "a normal run (adb reads its stdin, as adb shell does)"
setup normal
run -1 -1 120 FAKE_READ_STDIN=1 --
expect "every step, in order" [ "$(classes)" == "E2eSnapshotBeforeTest ShadowPipelineE2eTest RealWriteE2eTest ReadOnlyUiTourE2eTest E2eSnapshotAfterTest E2eCompareTest CleanupE2eTest" ]
expect "Result: PASSED on the terminal" shows "Result: PASSED"
expect "Result: PASSED in runner.log" logged "Result: PASSED"
expect "the test package is uninstalled" not installed
expect "stay awake is restored" stay_on_is 0
expect "the app is opened at the end" called "am start -W"
expect "its jobs are registered again" shows "registered again (2)"
expect "the instrumentation is E2eRunner" grep -q "E2eRunner" "$ROOT/state/instrumentations.log"
expect "this run's reads are kept on the computer, not another run's" history_is "200 300 "

echo "Ctrl-C during the shadow pipeline"
setup interrupt
run 4 -1 120 FAKE_SLEEP_CLASS=ShadowPipelineE2eTest FAKE_SLEEP=100 --
expect "the app is force-stopped" called "am force-stop"
expect "the step is stopped at once" lasted_at_most 30
expect "the steps after it do not run" not grep -q "RealWriteE2eTest" "$ROOT/state/instrumentations.log"
expect "cleanup, after snapshot and comparison run" [ "$(classes)" == "E2eSnapshotBeforeTest ShadowPipelineE2eTest CleanupE2eTest E2eSnapshotAfterTest E2eCompareTest" ]
expect "the interruption reaches the terminal" shows "interrupted:"
expect "the summary reaches the terminal" shows "Result: FAILED"
expect "the summary reaches runner.log" logged "Result: FAILED"

echo "Ctrl-C, then the terminal closed, during the cleanup"
setup hangup
run 2 4 120 FAKE_SLEEP_CLASS=CleanupE2eTest FAKE_SLEEP=6 --
expect "the runner still writes its summary" logged "Result:"
expect "it still uninstalls the test package" not installed
expect "it still restores stay awake" stay_on_is 0
expect "it still opens the app" called "am start -W"

echo "a cleanup that cannot confirm twice"
setup retry
run -1 -1 120 FAKE_CLEANUP_FAILS=2 --
expect "the first cleanup fails" shows "Cleanup: FAILED"
expect "the second one fails" shows "Cleanup-2: FAILED"
expect "the third one passes" shows "Cleanup-3: PASSED"
expect "the reports are removed from the phone only after the confirmed cleanup" called "rm -rf /sdcard/Android/data/io.github.alien2003.pulsestages/files/e2e"

echo "stay awake that cannot be restored, repaired by the next run (cleanup-only, phone locked)"
setup restore
echo 7 >"$ROOT/state/stay-on"
run -1 -1 120 FAKE_FAIL_RESTORE=1 --
expect "Result: INCOMPLETE" shows "Result: INCOMPLETE"
expect "with the command that fixes it" shows "stay_on_while_plugged_in 7"
expect "the original is kept on the computer" [ "$(cat "$ROOT/tools/device-test/out/.stay-on-original-FAKE123")" == "7" ]
: >"$ROOT/state/instrumentations.log"
run -1 -1 120 FAKE_LOCK=locked -- cleanup-only
expect "cleanup-only restores it first, with the phone locked" stay_on_is 7
expect "cleanup-only passes" shows "Result: PASSED"
expect "cleanup-only runs only the cleanup" [ "$(classes)" == "CleanupE2eTest" ]

echo "an earlier run read Health Connect 15.7 minutes ago"
setup history
mkdir -p "$ROOT/tools/device-test/out"
now=$(($(date +%s) * 1000))
printf '%s\t1200\n%s\t800\n%s\t5000\n' $((now - 942000)) $((now - 3600000)) $((now - 90000000)) >"$ROOT/tools/device-test/out/.read-history-FAKE123.tsv"
run -1 -1 120 --
expect "the runner waits for that window" shows "less than 15 minutes ago"
expect "then runs" shows "Result: PASSED"
expect "the budget is what is left of the last 24 hours" grep -q "e2eReadBudget 2000 " "$ROOT/state/instrumentations.log"

echo "a phone that stays locked"
setup locked
run -1 -1 120 FAKE_LOCK=locked -- --unlock-timeout 6
expect "it asks to unlock" shows "UNLOCK IT NOW"
expect "then stops" shows "stayed locked"
expect "before installing anything" not called " install "

echo "the app's own work running before the run"
setup busy
run -1 -1 120 FAKE_ACTIVE_UNTIL=$(($(date +%s) + 12)) --
expect "the runner waits for it" shows "waiting for it to finish"
expect "then runs" shows "Result: PASSED"

if ((FAILURES > 0)); then
  printf '%s check(s) failed\n' "$FAILURES"
  exit 1
fi
echo "all checks passed"
