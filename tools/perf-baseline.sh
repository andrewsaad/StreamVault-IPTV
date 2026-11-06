#!/usr/bin/env bash
# Perf baseline for a connected Android TV device (debug build, package com.streamvault.app.debug).
# Usage: tools/perf-baseline.sh <adb-serial> <label> [apk-path] [out-dir]
# Measures: cold start (am start -W), memory (dumpsys meminfo), frame jank after DPAD scrolling (gfxinfo),
# APK size. Zap latency and EPG scroll need a configured provider; see the notes printed at the end.
set -euo pipefail

SERIAL="${1:?adb serial, e.g. 192.168.68.150:38189}"
LABEL="${2:?label, e.g. gtv-before}"
APK="${3:-app/build/outputs/apk/debug/app-debug.apk}"
OUT="${4:-build/perf-baseline}"
PKG="com.streamvault.app.debug"
RUNS="${RUNS:-5}"

adb_() { adb -s "$SERIAL" "$@"; }
mkdir -p "$OUT"
REPORT="$OUT/${LABEL}.txt"
: > "$REPORT"
say() { echo "$*" | tee -a "$REPORT"; }

say "== perf baseline: $LABEL =="
say "date: $(date -u +%FT%TZ)"
say "git: $(git rev-parse --short HEAD) ($(git rev-parse --abbrev-ref HEAD))"
say "device: $(adb_ shell getprop ro.product.manufacturer | tr -d '\r') $(adb_ shell getprop ro.product.model | tr -d '\r'), Android $(adb_ shell getprop ro.build.version.release | tr -d '\r'), $(adb_ shell grep MemTotal /proc/meminfo | tr -d '\r')"
say "apk bytes: $(stat -c %s "$APK" 2>/dev/null || wc -c < "$APK")"

adb_ install -r "$APK" >/dev/null
say "installed: $PKG"

# The TV must be awake and showing the app, otherwise start-time and frame numbers are meaningless.
wake() {
  adb_ shell input keyevent KEYCODE_WAKEUP
  sleep 1
  adb_ shell input keyevent KEYCODE_HOME   # leave any screensaver/ambient mode
  sleep 1
}
require_awake() {
  local w
  # No early-exit in awk: with pipefail a closed pipe would abort the whole script under set -e.
  w=$(adb_ shell dumpsys power | tr -d '\r' | awk -F= '/mWakefulness=/{print $2}' | head -1) || w="unknown"
  if [ "$w" != "Awake" ]; then
    say "ABORT: device wakefulness is '$w', not Awake. Numbers would be invalid."
    exit 2
  fi
}
require_foreground() {
  local top
  top=$(adb_ shell dumpsys activity activities | tr -d '\r' | grep -E 'topResumedActivity|ResumedActivity' || true)
  if ! echo "$top" | grep -q "$PKG"; then
    say "ABORT: $PKG is not the resumed activity. Numbers would be invalid."
    exit 3
  fi
}
wake
require_awake

# Warm-up: first launch can seed a provider and kick off a catalog sync, which would skew cold start.
# WARMUP_SECONDS lets that settle (default 90). Set 0 to skip.
say "-- warm-up launch (${WARMUP_SECONDS:-90}s) --"
adb_ shell am force-stop "$PKG"
adb_ shell am start -n "$PKG/com.streamvault.app.MainActivity" >/dev/null
sleep "${WARMUP_SECONDS:-90}"
require_awake
require_foreground

say "-- cold start (am start -W), $RUNS runs, TotalTime ms --"
times=()
for i in $(seq 1 "$RUNS"); do
  adb_ shell am force-stop "$PKG"
  sleep 2
  require_awake
  t=$(adb_ shell am start -W -n "$PKG/com.streamvault.app.MainActivity" | tr -d '\r' | awk '/TotalTime/{print $2}')
  sleep 4
  require_foreground
  say "run $i: ${t:-NA}"
  [ -n "${t:-}" ] && times+=("$t")
done
if [ "${#times[@]}" -gt 0 ]; then
  sorted=$(printf '%s\n' "${times[@]}" | sort -n)
  median=$(echo "$sorted" | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')
  say "cold start median ms: $median  (min $(echo "$sorted" | head -1), max $(echo "$sorted" | tail -1))"
fi

say "-- memory after start (PSS KB) --"
adb_ shell dumpsys meminfo "$PKG" | tr -d '\r' | grep -E "TOTAL PSS|Java Heap|Native Heap|Graphics" | head -4 | tee -a "$REPORT" || true

say "-- frame stats while pressing DPAD_DOWN x20 (jank is only meaningful on a screen with a list/guide) --"
adb_ shell dumpsys gfxinfo "$PKG" reset >/dev/null
for _ in $(seq 1 20); do adb_ shell input keyevent KEYCODE_DPAD_DOWN; sleep 0.15; done
sleep 1
adb_ shell dumpsys gfxinfo "$PKG" | tr -d '\r' | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile" | tee -a "$REPORT" || true

say "-- notes --"
say "Zap latency: needs a provider configured in the debug app. Use logcat first-frame lines while changing channels (see AGENTS.md validation)."
say "Report saved to $REPORT"
