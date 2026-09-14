#!/usr/bin/env bash
#
# verify-release-no-egress.sh — STANDING RELEASE GATE (Phase 10, Option A).
#
# Assert the shipping release artifact CANNOT phone home:
#   (1) it declares NO `android.permission.INTERNET`  → the OS forbids opening a socket;
#   (2) it registers NO known telemetry/transport COMPONENTS (services/receivers).
#
# WHY THIS IS A STANDING GATE, not a one-off: v1 shipped no egress of its own, yet
# `com.google.mediapipe:tasks-core` (an *on-device* embeddings lib) transitively
# smuggled in Google's datatransport CCT/Firelog telemetry — INTERNET + an upload
# scheduler — which the manifest merger silently re-added. We strip it with
# `tools:node="remove"`. ANY future dependency (or a MediaPipe/AGP version bump) can
# re-introduce the same, unnoticed. This gate FAILS THE RELEASE if that happens, so
# the Data Safety "nothing leaves your device" claim stays TRUE on every build — not
# just the one we happened to inspect by hand.
#
# Runs on the FINAL RELEASE ARTIFACT: the release merged manifest (produced by
# `bundleRelease`, authoritative for the AAB base module) AND, when present, the
# compiled release APK's binary manifest via aapt2 (the actually-packaged bytes).
#
# Usage:  ./tools/verify-release-no-egress.sh
#   Run AFTER `:app:bundleRelease` (and optionally `:app:assembleRelease`). Exits
#   non-zero — failing the release — if any egress surface is present.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
fail() { echo "FAIL (no-egress gate): $*" >&2; exit 1; }
ok()   { printf '\033[1;32m[no-egress]\033[0m %s\n' "$*"; }

# Telemetry/transport component class names + package fragments that must NEVER be
# registered as components in the shipping manifest. Extend as new phone-home libs
# are discovered. (These CLASSES may remain in the dex — MediaPipe references them —
# but with no manifest component AND no INTERNET they can neither schedule nor upload.)
TELEMETRY_DENYLIST='datatransport|TransportBackendDiscovery|JobInfoSchedulerService|AlarmManagerSchedulerBroadcastReceiver|firelog|crashlytics|firebase-perf|\.measurement\.'

# --- (1) release MERGED MANIFEST (text XML; produced by bundleRelease) ------------
MERGED=$(find app/build/intermediates -path '*merged_manifest*/release/*AndroidManifest.xml' 2>/dev/null | head -1)
[ -n "$MERGED" ] || fail "no release merged manifest found — run ./gradlew :app:bundleRelease first"
ok "checking merged manifest: $MERGED"
grep -q 'android.permission.INTERNET' "$MERGED" \
  && fail "INTERNET permission present in release merged manifest" || true
if grep -iEq "$TELEMETRY_DENYLIST" "$MERGED"; then
  echo "--- offending lines ---" >&2; grep -iEn "$TELEMETRY_DENYLIST" "$MERGED" >&2
  fail "telemetry/transport component registered in release merged manifest"
fi
ok "merged manifest: no INTERNET, no known telemetry components ✓"

# --- (2) compiled release APK binary manifest (the packaged bytes), if present ----
APK=$(find app/build/outputs/apk/release -name '*.apk' 2>/dev/null | head -1 || true)
if [ -n "${APK:-}" ]; then
  AAPT2=$(find "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools" -name aapt2 2>/dev/null | sort | tail -1 || true)
  if [ -n "${AAPT2:-}" ]; then
    ok "checking compiled APK manifest via aapt2: $APK"
    PERMS=$("$AAPT2" dump permissions "$APK" 2>/dev/null || true)
    # grep -c (reads ALL input), never grep -q: under `set -o pipefail`, grep -q exits at the
    # first match and SIGPIPEs the upstream, so a real match would spuriously read as "not found"
    # → a SILENT false PASS of the privacy gate. Count instead.
    if [ "$(printf '%s' "$PERMS" | grep -c 'android.permission.INTERNET')" -gt 0 ]; then
      fail "INTERNET permission present in compiled release APK"
    fi
    XMLTREE=$("$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null || true)
    if [ "$(printf '%s' "$XMLTREE" | grep -icE "$TELEMETRY_DENYLIST")" -gt 0 ]; then
      fail "telemetry/transport component registered in compiled release APK"
    fi
    ok "compiled APK: no INTERNET, no known telemetry components ✓"
  else
    echo "[no-egress] aapt2 not found — skipped compiled-APK check (merged-manifest check stands)" >&2
  fi
else
  echo "[no-egress] no release APK (assembleRelease not run) — merged-manifest check stands" >&2
fi

ok "PASS — release artifact cannot phone home (no INTERNET, no telemetry components)."
