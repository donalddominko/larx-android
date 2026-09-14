#!/usr/bin/env bash
#
# verify-release-model-shipped.sh — STANDING RELEASE GATE (Phase 10, added 2026-07-18).
#
# Deterministically assert the release AAB actually SHIPS the whisper `base` model in
# its install-time asset pack — the exact failure this session uncovered: the app built,
# passed 38 instrumented tests (which side-loaded the model themselves), and yet the
# shipping artifact contained NO model, so transcription failed for a real user.
#
# This is the cheap, device-free, every-release backstop that would have caught it:
# it inspects the AAB's bytes for the model at the expected path, size, and SHA-256.
# Runs in CI (no device needed). The on-device counterpart is verify-clean-install-e2e.sh.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$REPO"
AAB="app/build/outputs/bundle/release/app-release.aab"
ENTRY="whisper_base_assets/assets/ggml-base.bin"
EXPECTED_SHA="60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
EXPECTED_SIZE=147951465
fail() { echo "FAIL (model-shipped gate): $*" >&2; exit 1; }
ok()   { printf '\033[1;32m[model-shipped]\033[0m %s\n' "$*"; }

[ -f "$AAB" ] || fail "no release AAB at $AAB — run ./gradlew :app:bundleRelease first"

# 1. The model entry exists in the install-time asset pack.
LINE=$(unzip -l "$AAB" "$ENTRY" 2>/dev/null | awk -v e="$ENTRY" '$4==e {print}')
[ -n "$LINE" ] || fail "release AAB does not contain $ENTRY — the model is NOT shipped (transcription would fail on install)."
SIZE=$(echo "$LINE" | awk '{print $1}')
ok "found $ENTRY in the AAB (uncompressed $SIZE bytes)"

# 2. Size sanity (guards a truncated/placeholder file).
[ "$SIZE" = "$EXPECTED_SIZE" ] || fail "model size $SIZE != expected $EXPECTED_SIZE (wrong/truncated model shipped)."

# 3. SHA-256 of the actual shipped bytes (guards a wrong-but-same-size file).
ACTUAL_SHA=$(unzip -p "$AAB" "$ENTRY" | sha256sum | awk '{print $1}')
[ "$ACTUAL_SHA" = "$EXPECTED_SHA" ] || fail "shipped model SHA $ACTUAL_SHA != expected $EXPECTED_SHA."
ok "shipped model SHA-256 matches the pinned value ✓"

# 4. It's an INSTALL-TIME pack (no runtime download / INTERNET needed).
DELIVERY=$(unzip -p "$AAB" "whisper_base_assets/manifest/AndroidManifest.xml" 2>/dev/null \
  | strings | grep -oiE "install-time|on-demand|fast-follow" | head -1 || true)
if [ -n "$DELIVERY" ] && [ "$DELIVERY" != "install-time" ]; then
  fail "asset pack delivery is '$DELIVERY', expected install-time (on-demand would need INTERNET + break offline-first)."
fi
ok "delivery mode: install-time (ships with the app; no download, no INTERNET)"

ok "PASS — the release AAB ships the verified base model install-time; transcription works out of the box."
