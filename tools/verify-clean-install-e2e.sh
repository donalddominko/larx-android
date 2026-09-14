#!/usr/bin/env bash
#
# verify-clean-install-e2e.sh — STANDING RELEASE GATE (Phase 10, added 2026-07-18).
#
# The lesson that created this gate: the 38-test instrumented suite passed while the
# SHIPPING app could not transcribe at all — the tests SIDE-LOADED the whisper model
# themselves. Fixture-provisioned tests validate the code path while hiding a broken
# product (no model delivery wired). This gate installs the ACTUAL release artifact on
# a device with NOTHING side-loaded and has a human drive the real user flow
# (record → transcribe → play back), so "the model ships and transcription works out of
# the box" is proven, not assumed.
#
# Why partly manual: the release app is (correctly) NOT debuggable, so it can't be
# driven by instrumentation or inspected via run-as. The device-free, fully-automated
# backstop that catches the exact regression (model absent from the artifact) is
# `verify-release-model-shipped.sh`, which runs in CI on every build. THIS script does
# the faithful on-device half: a genuine clean install of the release bytes + a guided
# human record→transcribe→playback.
#
# ⚠️  CRITICAL ARTIFACT GOTCHA (2026-07-20): NEVER test via a plain assembleRelease APK.
#     The whisper model ships as an INSTALL-TIME Play Asset Delivery pack (:whisper_base_assets).
#     `./gradlew :app:assembleRelease` produces app-release.apk that DOES NOT contain the pack —
#     `adb install app-release.apk` yields an app that cannot transcribe:
#         E/EchoTranscribe java.io.FileNotFoundException: ggml-base.bin (AssetManager.open)
#     The pack is only merged into the base APK when you go through the AAB + bundletool (or Play).
#     THIS SCRIPT is the correct path: bundleRelease → bundletool build-apks --mode=universal →
#     install-apks. It asserts the universal APK actually carries assets/ggml-base.bin before
#     installing, and REFUSES to proceed otherwise (see the model_count check below). If you ever
#     find yourself `adb install`-ing an app-release.apk by hand, STOP — you are testing a
#     model-less artifact and any "transcription failed" you see is that, not a real regression.
#
# Usage:  ./tools/verify-clean-install-e2e.sh [ADB_SERIAL]
#   Requires: signed release AAB (./gradlew :app:bundleRelease), bundletool in .tmp/,
#             keystore/signing.properties.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$REPO"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"; [ -x "$ADB" ] || ADB=adb
SERIAL="${1:-}"; ADB_ARGS=(); [ -n "$SERIAL" ] && ADB_ARGS=(-s "$SERIAL")
APP_ID="london.aipartner.echo"
AAB="app/build/outputs/bundle/release/app-release.aab"
BT=$(ls "$REPO"/.tmp/bundletool*.jar 2>/dev/null | head -1 || true)
fail() { echo "FAIL (clean-install gate): $*" >&2; exit 1; }
say()  { printf '\033[1;35m[clean-install]\033[0m %s\n' "$*"; }
ks()   { grep "^$1=" keystore/signing.properties | cut -d= -f2-; }

[ -f "$AAB" ] || fail "no release AAB — run ./gradlew :app:bundleRelease first"
[ -n "$BT" ] || fail "bundletool not in .tmp/ — download bundletool-all-*.jar there"
[ -f keystore/signing.properties ] || fail "keystore/signing.properties missing"
"$ADB" "${ADB_ARGS[@]}" get-state >/dev/null 2>&1 || fail "no device attached"

say "clean slate: uninstalling app (+ .debug/.test) to wipe any side-loaded model/data…"
for id in "$APP_ID" "$APP_ID.debug" "$APP_ID.debug.test"; do
  "$ADB" "${ADB_ARGS[@]}" uninstall "$id" >/dev/null 2>&1 || true
done
# a stray external side-loaded model would invalidate the test — refuse if present
EXT="/storage/emulated/0/Android/data/$APP_ID/files/ggml-base.bin"
"$ADB" "${ADB_ARGS[@]}" shell rm -f "$EXT" >/dev/null 2>&1 || true

say "building + installing the release universal APK (includes the install-time asset pack)…"
APKS=".tmp/larx-release.apks"; rm -f "$APKS"
java -jar "$BT" build-apks --bundle="$AAB" --output="$APKS" --mode=universal \
  --ks="$(ks storeFile)" --ks-pass="pass:$(ks storePassword)" \
  --ks-key-alias="$(ks keyAlias)" --key-pass="pass:$(ks keyPassword)" >/dev/null
# Faithful-install assertion: the universal APK must actually carry the install-time
# model (merged into assets/ggml-base.bin). Extract to a real file first — `unzip -l`
# needs a seekable file, not a pipe (`/dev/stdin` never works) — and count with grep -c
# (reads ALL input) rather than grep -q, which under `set -o pipefail` SIGPIPEs the
# upstream unzip and yields a false "missing".
TMPX=$(mktemp -d); unzip -o -q "$APKS" -d "$TMPX"
model_count=$(unzip -l "$TMPX/universal.apk" | grep -c "assets/ggml-base.bin") || true
rm -rf "$TMPX"
[ "${model_count:-0}" -gt 0 ] \
  || fail "universal APK does NOT contain assets/ggml-base.bin — model not shipped"
say "faithful-install check: universal APK carries the install-time model ✓"
java -jar "$BT" install-apks --apks="$APKS" ${SERIAL:+--device-id="$SERIAL"} >/dev/null

# grep -c, not grep -q (SIGPIPE-safe under pipefail — same false-negative class as the model check above).
[ "$("$ADB" "${ADB_ARGS[@]}" shell pm list packages | grep -c "$APP_ID\$")" -gt 0 ] \
  || fail "release app not installed"
say "installed $APP_ID clean. No model was side-loaded — the install-time pack is the only source."

say "launching the app…"
"$ADB" "${ADB_ARGS[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" "${ADB_ARGS[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" "${ADB_ARGS[@]}" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

cat <<'EOF'

  ┌────────────────────────────────────────────────────────────────────────┐
  │  MANUAL STEP (release app is not debuggable — drive it as a user):       │
  │   1. Record a short clip (say a sentence or two).                        │
  │   2. Stop — wait for transcription to complete.                          │
  │   3. Confirm a transcript appears (NOT a failure), then play it back.    │
  │                                                                          │
  │  PASS  = transcript is produced on this fresh install with no adb push.  │
  │  FAIL  = "transcription failed" / no text → the model did not provision  │
  │          from the install-time pack; STOP and fix before submitting.     │
  └────────────────────────────────────────────────────────────────────────┘

EOF
say "on-device clean install ready for record→transcribe→playback review."
