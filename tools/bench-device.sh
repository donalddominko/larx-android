#!/usr/bin/env bash
#
# bench-device.sh — one command to benchmark whisper models on ANY plugged-in phone and
# accumulate the findings in git (references/better-models-pro.md Decision 7).
#
#   Plug phone in  →  ./tools/bench-device.sh  →  results appended to the committed file.
#
# What it does:
#   1. Side-loads any local model weights (.tmp/ggml-*.bin) + language spot-check clips
#      (.tmp/spotcheck-<lang>.wav, mono 16 kHz PCM-16) onto the device.
#   2. Runs the on-device battery (ModelBenchHarnessTest) — per (device × model): loads-without-OOM,
#      peak PSS, RTF, completes-under-hang-cap, Gate-1 prediction; per spot-check language: a transcript
#      for native-speaker judgment.
#   3. Pulls the device JSON-lines and MERGES new rows (deduped) into
#      references/device-bench-results.jsonl (committed, versioned, accumulating).
#
# Usage:  ./tools/bench-device.sh [ADB_SERIAL]
#   ADB_SERIAL optional — needed only if more than one device is attached.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="london.aipartner.echo.debug"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB="adb"
SERIAL="${1:-}"
ADB_ARGS=(); [ -n "$SERIAL" ] && ADB_ARGS=(-s "$SERIAL")

# Side-load into the app's INTERNAL storage via `run-as` — SELinux-clean and app-owned. (Adb-pushed
# external-storage files are shell-owned and unreadable by the app; /data/local/tmp is SELinux-denied
# to apps on Android 13. `run-as dd` streams the bytes so the APP writes its own internal file.)
RESULTS="$REPO/references/device-bench-results.jsonl"
runas() { "$ADB" "${ADB_ARGS[@]}" shell run-as "$APP_ID" "$@"; }

say() { printf '\033[1;36m[bench]\033[0m %s\n' "$*"; }

# 0. Sanity — a device must be attached, and the app must be debuggable (run-as must work).
"$ADB" "${ADB_ARGS[@]}" get-state >/dev/null 2>&1 || { echo "No device (adb get-state failed)."; exit 1; }
say "device: $("$ADB" "${ADB_ARGS[@]}" shell getprop ro.product.model | tr -d '\r')"

# 1. Install app + test APK. Both are needed before run-as (the app) and am instrument (the test).
say "installing app + test APK…"
( cd "$REPO" && ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" ./gradlew :app:installDebug :app:installDebugAndroidTest )
runas true 2>/dev/null || { echo "run-as $APP_ID failed — is this a debuggable build on this device?"; exit 1; }

# 2. Side-load weights + spot-check clips into internal files/models and files/bench (skip if none staged).
#    Method (NO large host stdin stream — that segfaults adb on multi-hundred-MB/GB models, the exact
#    files we most need to bench on a borrowed device): `adb push` the file to a shell-readable /data/
#    local/tmp using the robust chunked sync protocol, then copy it into the app's internal storage
#    ENTIRELY ON-DEVICE — the device shell (shell uid, can read /data/local/tmp) pipes it into `run-as`
#    (app uid) which writes its own file. No big stream crosses the adb host link; nothing depends on a tty.
runas mkdir files 2>/dev/null || true
runas mkdir files/models 2>/dev/null || true
runas mkdir files/bench 2>/dev/null || true
# side_load <local-file> <app-relative-dest>
side_load() {
  local src="$1" dest="$2" tmp="/data/local/tmp/echo-bench-$(basename "$1")"
  "$ADB" "${ADB_ARGS[@]}" push "$src" "$tmp" >/dev/null
  # `< $tmp` binds to the device's outer shell (shell can read /data/local/tmp); the inner `cat >`
  # runs inside run-as and writes as the app. Both ends are on-device — the transfer above (adb push)
  # is the only host-side move, and it is chunked and size-robust.
  "$ADB" "${ADB_ARGS[@]}" shell "run-as $APP_ID sh -c 'cat > $dest' < $tmp"
  "$ADB" "${ADB_ARGS[@]}" shell rm -f "$tmp" >/dev/null 2>&1 || true
}
shopt -s nullglob
for f in "$REPO"/.tmp/ggml-*.bin; do
  say "side-load $(basename "$f") → files/models/ (adb push + on-device cat)"
  side_load "$f" "files/models/$(basename "$f")"
done
for f in "$REPO"/.tmp/spotcheck-*.wav; do
  say "side-load $(basename "$f") → files/bench/ (adb push + on-device cat)"
  side_load "$f" "files/bench/$(basename "$f")"
done
shopt -u nullglob

# Fresh device output so we only pull THIS run's rows.
runas rm files/bench/model-bench.jsonl 2>/dev/null || true

# 3. Start the battery DETACHED, then poll the JSONL for completion — do NOT hold a long-lived adb
#    connection open across a multi-minute decode. Device access is opportunistic (borrowed/shop phones)
#    and adb is least reliable exactly there; small/medium decodes run many minutes. `am instrument`
#    WITHOUT -w starts the test and returns immediately — the test runs to completion in the app process
#    regardless of the adb link — and we poll the on-device file (each poll a fresh, short, retryable adb
#    command) until the harness writes its {"phase":"done"} sentinel. An adb hiccup costs a poll, not the run.
say "starting ModelBenchHarnessTest (detached; offline/airplane mode gives the cleanest numbers)…"
"$ADB" "${ADB_ARGS[@]}" shell am instrument -r \
  -e class london.aipartner.echo.ModelBenchHarnessTest \
  "$APP_ID.test/london.aipartner.echo.HiltTestRunner" >/dev/null 2>&1 || true

POLL_SECS="${BENCH_POLL_SECS:-15}"
TIMEOUT_SECS="${BENCH_TIMEOUT_SECS:-3600}"   # generous: a slow medium decode can run 30 min+
deadline=$(( $(date +%s) + TIMEOUT_SECS ))
TMP="$(mktemp)"
say "polling every ${POLL_SECS}s (timeout ${TIMEOUT_SECS}s)…"
while :; do
  sleep "$POLL_SECS"
  # Fresh short adb call each time; failures (transient disconnect) just retry next tick.
  if runas cat files/bench/model-bench.jsonl > "$TMP" 2>/dev/null; then
    results=$(grep -c '"phase":"result"' "$TMP" 2>/dev/null || echo 0)
    if grep -q '"phase":"done"' "$TMP" 2>/dev/null; then
      say "harness reported done — $results result row(s)."
      break
    fi
    say "…$results result row(s) so far"
  else
    say "…poll failed (transient?) — retrying"
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    say "TIMEOUT — no done sentinel after ${TIMEOUT_SECS}s. A 'started' row with no 'result' = the model"
    say "OOM-killed the process (couldn't be held). Merging whatever rows landed."
    break
  fi
done

# 4. Merge (deduped by exact line) into the committed results file. Rows already on disk from a
#    dropped-connection run are recovered here too (the file survives; only the adb link doesn't).
if [ -s "$TMP" ]; then
  touch "$RESULTS"
  added=0
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    if ! grep -qxF -- "$line" "$RESULTS"; then echo "$line" >> "$RESULTS"; added=$((added+1)); fi
  done < "$TMP"
  say "merged $added new row(s) into references/device-bench-results.jsonl"
  say "review then: git add references/device-bench-results.jsonl && git commit"
else
  echo "No device output pulled — did any model side-load? (see ModelBenchHarnessTest KDoc)"; exit 1
fi
rm -f "$TMP"
