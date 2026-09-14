# Consumer ProGuard/R8 rules for :core:transcribe — travel with the module into any
# app that consumes it, because they protect the JNI boundary this module owns.
#
# WHY (launch blocker, 2026-07-18): whisper_jni.cpp's nativeTranscribe looks up Java
# types BY HARDCODED NAME via FindClass/GetMethodID to build its List<TranscriptSegment>
# result. R8 runs only on release, so it obfuscated `TranscriptSegment`; the native
# FindClass then returned null → ART "java_class == null" → SIGABRT on every decode.
# Debug tests never saw it (isMinifyEnabled=false). Keep EVERY Java type the native
# code resolves by name. Audited set of JNI lookups in whisper_jni.cpp:
#   - "java/util/ArrayList"                          → system class, never obfuscated (no rule needed)
#   - "london/.../transcribe/TranscriptSegment" + <init>(String,long,long,String) → KEEP below
# If a future JNI FindClass/GetFieldID targets a new class, ADD IT HERE or the same
# silent release-only abort returns.

# The class the JNI constructs, and the exact constructor it calls
# (Ljava/lang/String;JJLjava/lang/String;)V. Keep the name + members.
-keep class london.aipartner.echo.core.transcribe.TranscriptSegment {
    <init>(...);
    <fields>;
}

# The class whose native methods the JNI symbols bind to. Native method names must not
# be renamed or the Java_..._nativeTranscribe symbol won't resolve.
-keepclasseswithmembernames class london.aipartner.echo.core.transcribe.NativeWhisperEngine {
    native <methods>;
}

# ── MediaPipe Text Embedder (semantic search) + its protobuf runtime. ──────────────────
# WHY: MediaPipe Tasks resolves its options/graph via protobuf-lite REFLECTION over field
# names ("platform_", etc.). R8 renames those proto fields/classes → reflection fails with
# "Field <x>_ for <Class> not found" → the embedder throws at init → semantic search silently
# indexes NOTHING (a listed store feature, non-functional, with the error swallowed). Same
# failure shape as the JNI TranscriptSegment bug, one subsystem over. Keep protobuf-lite
# message classes intact (fields + the schema plumbing), and MediaPipe's own classes.
# ROOT CAUSE (why this keeps recurring): the MediaPipe Tasks AARs (tasks-text, tasks-core
# 0.10.35) ship NO consumer ProGuard rules of their own (verified: no proguard.txt inside the
# AARs). So R8 freely obfuscates/strips BOTH MediaPipe's own classes AND its reflection- and
# stack-walk-dependent TRANSITIVE deps, and each casualty only shows up at runtime, one slow
# build→install→record→logcat cycle at a time. We instead keep the whole reflection-prone set
# MediaPipe drags in (enumerate with `:core:transcribe:dependencies --configuration
# releaseRuntimeClasspath`). The standing gate `verify-release-mediapipe-reflection.sh` asserts
# every class below survives R8 un-renamed, so a version bump can't silently re-break embedding.
#
# 1) protobuf-lite (4.26.1): MediaPipe resolves options/graph via REFLECTION over field names
#    ("platform_", etc.). Renaming → "Field <x>_ for <Class> not found" at embedder init.
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
-keep,allowobfuscation class * extends com.google.protobuf.GeneratedMessageLite$Builder { *; }
# 2) MediaPipe's own classes (Graph, TaskRunner, TextEmbedder, …).
-keep class com.google.mediapipe.** { *; }
-keep class mediapipe.** { *; }
# 3) Google Flogger (com.google.flogger:flogger + flogger-system-backend 0.6, package
#    `com.google.common.flogger`): MediaPipe's Graph.<clinit> creates a FluentLogger, whose
#    stack-based caller-finder throws "no caller found on the stack" when R8 obfuscates/strips it
#    (seen: classes renamed to s2.*/t2.* and some R8$$REMOVED$$CLASS). Its DefaultPlatform backend
#    is also loaded BY CLASS-NAME STRING, so the name must survive too. Keep the whole package.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn com.google.mediapipe.**
-dontwarn com.google.common.flogger.**
