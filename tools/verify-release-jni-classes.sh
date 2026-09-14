#!/usr/bin/env bash
#
# verify-release-jni-classes.sh — STANDING RELEASE GATE (Phase 10, added 2026-07-19).
#
# The JNI (whisper_jni.cpp) resolves Java types BY HARDCODED NAME via FindClass to build its
# results. R8 runs ONLY on release, so a missing keep rule silently obfuscates such a class →
# native FindClass returns null → ART "java_class == null" → SIGABRT on every decode. The debug
# test suite can't see it (isMinifyEnabled=false). This gate asserts, on the ACTUAL minified
# release dex, that every JNI-referenced app class is present WITH ITS ORIGINAL NAME.
#
# Keep JNI_CLASSES in sync with the FindClass("london/...") calls in
# core/transcribe/src/main/cpp/whisper_jni.cpp. (System classes like java/util/ArrayList are
# never obfuscated and are intentionally NOT listed.) If you add a new FindClass on an app
# class, add it here AND add a keep rule in core/transcribe/consumer-rules.pro.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$REPO"
APK=$(find app/build/outputs/apk/release -name "*.apk" 2>/dev/null | head -1)
fail() { echo "FAIL (jni-classes gate): $*" >&2; exit 1; }
ok()   { printf '\033[1;32m[jni-classes]\033[0m %s\n' "$*"; }

[ -n "$APK" ] || fail "no release APK — run ./gradlew :app:assembleRelease first"

# App classes the native code looks up by name (dex descriptor form Lpkg/Name;).
JNI_CLASSES=(
  "london/aipartner/echo/core/transcribe/TranscriptSegment"
)

for cls in "${JNI_CLASSES[@]}"; do
  # dex stores the type descriptor "Lpkg/Name;" in its string pool. Stream the dex through
  # `strings` and count matches for the descriptor. Use grep -c (reads ALL input) rather than
  # grep -q: under `set -o pipefail`, grep -q exits at the first match and SIGPIPEs the upstream
  # unzip, which would make the pipeline (and the `if`) report failure despite a real match.
  count=$(unzip -p "$APK" 'classes*.dex' 2>/dev/null | strings | grep -c "L${cls};") || true
  if [ "${count:-0}" -gt 0 ]; then
    ok "JNI class present with original name: $cls ✓"
  else
    fail "JNI class '$cls' is MISSING from the release dex (R8 obfuscated/stripped it). " \
         "Add a keep rule in core/transcribe/consumer-rules.pro — otherwise nativeTranscribe's " \
         "FindClass(\"$cls\") returns null → SIGABRT on every decode."
  fi
done

ok "PASS — every JNI-referenced app class survives R8 with its original name."
