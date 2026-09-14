#!/usr/bin/env bash
#
# verify-release-mediapipe-reflection.sh — STANDING RELEASE GATE (Phase 10, added 2026-07-20).
#
# Sibling of verify-release-jni-classes.sh, for the OTHER silent-R8 subsystem: the MediaPipe
# Text Embedder (on-device semantic search). MediaPipe's AARs ship NO consumer ProGuard rules,
# so R8 obfuscates/strips its reflection- and stack-walk-dependent classes AND transitive deps
# (protobuf-lite, Google Flogger). Each casualty only surfaces at runtime — the embedder throws
# at init and search silently indexes NOTHING. This has already produced THREE blockers found
# serially: protobuf `platform_` fields, then Flogger's Graph.<clinit> stack-walk. This gate
# stops the serial discovery: it asserts, on the ACTUAL release mapping, that every class the
# embedder reflects on / loads by name survives R8 WITH ITS ORIGINAL NAME.
#
# WHY mapping.txt, not the dex (unlike the JNI gate): the failure here is RENAMING, not just
# removal — a class can be present in the dex under an obfuscated name (s2.d) and still break a
# by-name / stack-walk lookup. mapping.txt is the precise oracle: "com.x.Y -> com.x.Y:" means
# kept un-renamed; "com.x.Y -> s2.d:" or an "R8$$REMOVED" target means R8 touched it → FAIL.
#
# If a MediaPipe version bump adds a new reflected/by-name/stack-walked dependency, add it to
# REFLECTED_CLASSES here AND widen the keep in core/transcribe/consumer-rules.pro.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$REPO"
MAP=$(find app/build/outputs/mapping/release -name mapping.txt 2>/dev/null | head -1)
fail() { echo "FAIL (mediapipe-reflection gate): $*" >&2; exit 1; }
ok()   { printf '\033[1;32m[mediapipe-reflection]\033[0m %s\n' "$*"; }

[ -n "$MAP" ] || fail "no release mapping.txt — run ./gradlew :app:assembleRelease (or bundleRelease) first"

# Classes the embedder resolves by reflection / class-name string / stack walk. Each MUST map to
# itself in the release build. (protobuf field-level keeps are additionally covered by the
# GeneratedMessageLite member rule; here we spot-check the load-bearing anchors.)
REFLECTED_CLASSES=(
  # MediaPipe framework + tasks (own classes)
  "com.google.mediapipe.framework.Graph"
  "com.google.mediapipe.tasks.core.TaskRunner"
  "com.google.mediapipe.tasks.text.textembedder.TextEmbedder"
  # Google Flogger — created in Graph.<clinit>; backend loaded by class-name string
  "com.google.common.flogger.FluentLogger"
  "com.google.common.flogger.backend.system.DefaultPlatform"
  # protobuf-lite runtime base (reflected message plumbing)
  "com.google.protobuf.GeneratedMessageLite"
)

for cls in "${REFLECTED_CLASSES[@]}"; do
  # A kept class appears as "<orig> -> <orig>:" in mapping.txt. grep -c reads all input
  # (SIGPIPE-safe under pipefail, same reason as the JNI gate).
  identity=$(grep -cE "^${cls//./\\.} -> ${cls//./\\.}:" "$MAP") || true
  if [ "${identity:-0}" -gt 0 ]; then
    ok "reflected class kept un-renamed: $cls ✓"
    continue
  fi
  # Present but renamed/removed? Show what R8 did, for a fast fix.
  renamed=$(grep -E "^${cls//./\\.} ->" "$MAP" | head -1 || true)
  if [ -n "$renamed" ]; then
    fail "reflected class '$cls' was OBFUSCATED/REMOVED by R8: '${renamed}'. The embedder resolves " \
         "it by name/stack-walk → init throws → semantic search silently indexes nothing. Widen the " \
         "keep in core/transcribe/consumer-rules.pro."
  else
    fail "reflected class '$cls' not found in mapping.txt at all (dependency gone or renamed away). " \
         "Re-check the MediaPipe dependency tree and the keeps in core/transcribe/consumer-rules.pro."
  fi
done

ok "PASS — every MediaPipe-reflected class survives R8 with its original name."
