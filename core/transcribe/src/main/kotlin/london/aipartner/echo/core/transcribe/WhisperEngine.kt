package london.aipartner.echo.core.transcribe

/**
 * The JNI boundary to the on-device transcription engine.
 *
 * Phase 4 decision (`phase-04-transcription.md`): **whisper.cpp `base` via JNI,
 * arm64-v8a only.** This interface is the Kotlin-side seam over the native
 * `libwhisper_jni.so`; everything above it (VAD, routing, storage) is pure Kotlin and
 * unit-testable against a fake engine, while the native build is swapped in
 * behind [NativeWhisperEngine].
 *
 * Contract: pure, offline, no network, no keys. Input is 16 kHz mono PCM-16 that
 * the VAD has ALREADY confirmed contains speech — the engine is never handed
 * silence (that's the caller's hard-rule responsibility, not the engine's).
 *
 * AUDIO-FORMAT CONTRACT (pinned — see `phase-04-transcription.md` §"JNI
 * audio-format contract"): `pcm` is mono, 16 kHz, signed PCM-16 little-endian,
 * one sample per `Short`. whisper.cpp wants 32-bit float in [-1, 1], so the C++
 * JNI shim MUST convert per sample `f32 = i16 / 32768.0f` (mono, 16 kHz asserted,
 * not silently resampled). That one conversion is the classic silent-corruption
 * point — wrong divisor/sign/endianness yields plausible garbage, not an error.
 */
interface WhisperEngine {
    /** Whether the native library AND model weights are present and loadable. */
    fun isAvailable(): Boolean

    /**
     * Batch-transcribe a speech-bearing PCM-16 mono 16 kHz buffer into timestamped
     * segments. [modelPath] is the on-device path to the `base` weights provisioned
     * by [ModelProvisioner]. Blocking/CPU-bound — call off the main thread.
     */
    fun transcribe(
        pcm: ShortArray,
        modelPath: String,
        languageTag: String?,
    ): List<TranscriptSegment>

    /**
     * Releases any resident native context/weights held by the engine. Called after a
     * transcription pass so the large model does not stay resident (low-RAM OOM
     * mitigation). Safe to call when nothing is loaded; the engine lazily re-acquires
     * on the next [transcribe]. No-op by default (the fake engines hold nothing).
     */
    fun release() {}

    /** Live 0..100 progress of the in-flight transcription, or -1 if unknown/unsupported.
     *  Read from another thread while [transcribe] blocks, to drive an honest UI bar. */
    fun progressPercent(): Int = -1

    /**
     * ISO code whisper reported for the LAST [transcribe] ("en", "sl", …), or null. NOTE: the
     * A03 spot-check proved this **echoes the forced language**, so it is NOT usable for
     * detect-to-warn when a language is forced (which production always does). Kept for a future
     * non-forced/auto path; [detectLanguage] is the real Layer-2 detector. Null by default.
     */
    fun lastDetectedLanguage(): String? = null

    /**
     * DEDICATED language auto-detect (Phase 7 Layer 2) — asks what language the audio actually IS,
     * independent of any forced decode. **Encoder-only** over the first 30 s (no autoregressive
     * decode), so it is far cheaper than a transcription pass. Returns the top ISO code + its
     * confidence, or null.
     *
     * MEMORY DISCIPLINE: the caller MUST [release] the transcription context before calling this,
     * so only one whisper context is ever resident (2 GB A03 OOM guard). Null by default (fakes).
     */
    fun detectLanguage(pcm: ShortArray, modelPath: String, forcedTag: String): DetectedLanguage? = null
}

/**
 * A language auto-detect result. [tag] is the top ISO code ("hr"), [confidence] its probability,
 * and [forcedProbability] the probability whisper assigned to the FORCED language ("en"). The
 * mismatch decision keys on [forcedProbability] being LOW (whisper doesn't hear the forced
 * language) — the top confidence can itself be low on clips whose language base confuses with a
 * neighbour, so it's not a reliable mismatch signal on its own.
 */
data class DetectedLanguage(
    val tag: String,
    val confidence: Float,
    val forcedProbability: Float,
)

/** Raised when transcription is requested but the engine/model isn't ready yet. */
class TranscriptionEngineUnavailable(message: String) : IllegalStateException(message)

/**
 * Native whisper.cpp binding.
 *
 * The native side (`libwhisper_jni.so` — our JNI shim with whisper.cpp linked in
 * statically, built via the NDK/CMake for `arm64-v8a` only) is produced by a
 * separate native build step — see the Phase 4 native work in PROGRESS.md. This
 * class is the stable Kotlin face of it: the `external` declarations below bind
 * to that library by JNI name.
 *
 * Loading is lazy and guarded so the app does not crash at class-load on a build
 * where the `.so` hasn't been assembled yet; [isAvailable] reports the truth and
 * callers fail honestly via [TranscriptionEngineUnavailable] rather than
 * fabricating output.
 *
 * NOTE: contains NO reference to networking, keys, `Entitlements`, or the egress
 * consent — the on-device path is local by construction (see the egress-distinction
 * test).
 */
class NativeWhisperEngine : WhisperEngine {

    override fun isAvailable(): Boolean = libraryLoaded

    override fun transcribe(
        pcm: ShortArray,
        modelPath: String,
        languageTag: String?,
    ): List<TranscriptSegment> {
        if (!libraryLoaded) {
            throw TranscriptionEngineUnavailable(
                "libwhisper_jni.so not loaded — native build not yet assembled for this ABI",
            )
        }
        // Native returns flat [startMs, endMs, <text bytes len>, …] decoded by JNI;
        // the actual marshalling lives in the C++ shim. Kotlin just forwards.
        return nativeTranscribe(pcm, modelPath, languageTag ?: "")
    }

    /** Frees the cached native whisper context (~150 MB). No-op if not loaded. */
    override fun release() {
        if (libraryLoaded) runCatching { nativeRelease() }
    }

    /** Live 0..100 progress of the current whisper run (0 when idle — reset on [release] —
     *  so the next pass's poller never reads a stale 100), -1 if unloaded. */
    override fun progressPercent(): Int =
        if (libraryLoaded) runCatching { nativeProgress() }.getOrDefault(-1) else -1

    override fun lastDetectedLanguage(): String? =
        if (libraryLoaded) runCatching { nativeLastLangId() }.getOrNull() else null

    override fun detectLanguage(pcm: ShortArray, modelPath: String, forcedTag: String): DetectedLanguage? {
        if (!libraryLoaded) return null
        val raw = runCatching { nativeDetectLanguage(pcm, modelPath, forcedTag) }.getOrNull()
            ?: return null
        // Native returns "topcode:topprob:forcedprob" (e.g. "hr:0.2880:0.0100").
        val parts = raw.split(':')
        val tag = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val confidence = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
        val forcedProbability = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
        return DetectedLanguage(tag, confidence, forcedProbability)
    }

    /** JNI entry point implemented in the C++ shim over whisper.cpp. */
    private external fun nativeTranscribe(
        pcm: ShortArray,
        modelPath: String,
        languageTag: String,
    ): List<TranscriptSegment>

    /** Frees the process-cached whisper context in the C++ shim. */
    private external fun nativeRelease()

    /** Current 0..100 progress of the in-flight transcription in the C++ shim. */
    private external fun nativeProgress(): Int

    /** ISO code (whisper_full_lang_id) of the last decode in the C++ shim, or null. */
    private external fun nativeLastLangId(): String?

    /** Dedicated auto-detect over [pcm]; returns "topcode:topprob:forcedprob" or null. */
    private external fun nativeDetectLanguage(pcm: ShortArray, modelPath: String, forcedTag: String): String?

    /** TEST-ONLY: force the decode abort budget (ms), 0 = default. Proves the hang cap fires. */
    external fun nativeSetTestAbortBudgetMs(ms: Long)

    private companion object {
        @JvmStatic
        val libraryLoaded: Boolean = try {
            // Single combined .so: our JNI shim + whisper.cpp linked statically.
            // (Named *_jni to avoid colliding with whisper.cpp's own `whisper` target.)
            System.loadLibrary("whisper_jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
