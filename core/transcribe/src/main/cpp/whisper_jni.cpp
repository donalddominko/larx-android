// JNI shim over whisper.cpp for Echo's on-device transcriber.
//
// Phase 4 (`references/phase-04-transcription.md`). This is the native face of
// `NativeWhisperEngine.nativeTranscribe`. It is the single highest-risk seam in
// the phase: the PCM-16 -> float32 conversion below is the classic
// silent-corruption point. The pinned contract (do NOT "improve" it):
//
//   * `pcm` is mono, 16 kHz, signed PCM-16 little-endian, one sample per jshort.
//   * whisper.cpp wants 32-bit float normalised to [-1, 1].
//   * Conversion is EXACTLY  f32 = i16 / 32768.0f  (divide by 32768, not 32767 —
//     match whisper.cpp's own convention). Wrong divisor/sign/endianness yields
//     plausible garbage, not an error.
//   * 16 kHz is a hard precondition guaranteed upstream by MediaCodecPcmDecoder;
//     we never resample here. (A raw buffer carries no rate, so this side cannot
//     re-verify it — that guarantee lives in the decoder, by contract.)
//
// Pure, offline, no network, no keys. Caller (OnDeviceTranscriber) has ALREADY
// VAD-confirmed speech, so this is never handed silence.
//
// AUTHORED-BUT-UNCOMPILED: this file is written against the pinned contract but
// has not been compiled (no NDK on the authoring machine) and not validated
// (Gate B B1-B3 require a physical arm64 device). See PROGRESS.md "Known debt".

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "whisper.h"

#define LOG_TAG "EchoWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Single-entry model cache. The `base` weights are ~150 MB; re-initialising the
// context on every call would dominate latency. Keyed by model path so a model
// swap (e.g. a future model upgrade) transparently re-loads.
std::mutex g_model_mutex;
whisper_context *g_ctx = nullptr;
std::string g_ctx_path;

// 0..100 progress of the in-flight transcription, for an honest UI progress bar.
// Written by whisper's progress callback on the inference thread, read by
// nativeProgress() from another thread — a plain atomic, no JNI in the callback.
std::atomic<int> g_progress{0};

void progress_cb(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/,
                 int progress, void * /*user_data*/) {
    g_progress.store(progress, std::memory_order_relaxed);
}

// Wall-clock deadline for the current decode (the structural hang cap). whisper calls
// abort_cb frequently during generation; returning true aborts the run.
std::chrono::steady_clock::time_point g_abort_deadline;

// Test-only override (ms) for the abort budget; 0 = use the duration-aware default. Lets an
// instrumented test prove the cap fires by forcing a tiny budget. Never set in production.
std::atomic<long long> g_test_abort_budget_ms{0};

bool abort_cb(void * /*user_data*/) {
    if (std::chrono::steady_clock::now() >= g_abort_deadline) {
        LOGE("whisper decode aborted — exceeded wall-clock cap (hang guard)");
        return true;
    }
    return false;
}

// Language id whisper reported for the LAST decode (whisper_full_lang_id). This is the
// "free getter" spot-check for Layer 2 detect-to-warn: does it report the ACTUAL acoustic
// language, or merely echo the forced wparams.language? Captured after each whisper_full so
// Kotlin can read it (nativeLastLangId) without a second inference. -1 = none yet.
std::atomic<int> g_last_lang_id{-1};

// Returns a ready context for `model_path`, or nullptr on load failure.
// Caller holds g_model_mutex.
whisper_context *acquire_context_locked(const char *model_path) {
    if (g_ctx != nullptr && g_ctx_path == model_path) {
        return g_ctx;
    }
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_ctx_path.clear();
    }
    whisper_context_params cparams = whisper_context_default_params();
    // CPU/NNAPI-free path for v1; GPU/NNAPI delegates are a later optimisation.
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed for %s", model_path);
        return nullptr;
    }
    g_ctx = ctx;
    g_ctx_path = model_path;
    return g_ctx;
}

int recommended_threads() {
    unsigned int hc = std::thread::hardware_concurrency();
    if (hc == 0) return 4;
    if (hc > 8) hc = 8;  // diminishing returns + thermal on mobile
    return static_cast<int>(hc);
}

// whisper segment timestamps are in centiseconds (1/100 s); storage wants ms.
inline jlong centiseconds_to_ms(int64_t cs) { return static_cast<jlong>(cs * 10); }

// whisper segment text typically carries a single leading space; trim it.
std::string trim_leading_space(const char *raw) {
    if (raw == nullptr) return std::string();
    while (*raw == ' ') ++raw;
    return std::string(raw);
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_london_aipartner_echo_core_transcribe_NativeWhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jshortArray pcm, jstring modelPath, jstring languageTag) {

    // ---- Resolve the empty ArrayList we return on any non-fatal early-out ----
    jclass listCls = env->FindClass("java/util/ArrayList");
    jmethodID listCtor = env->GetMethodID(listCls, "<init>", "()V");
    jmethodID listAdd = env->GetMethodID(listCls, "add", "(Ljava/lang/Object;)Z");
    jobject result = env->NewObject(listCls, listCtor);

    if (pcm == nullptr || modelPath == nullptr) {
        LOGE("null pcm or modelPath");
        return result;
    }

    // ---- Load model (cached) ----
    const char *model_path_c = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context *ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        ctx = acquire_context_locked(model_path_c);
    }
    env->ReleaseStringUTFChars(modelPath, model_path_c);
    if (ctx == nullptr) {
        return result;  // honest empty; Kotlin treats as no-output, never fabricates
    }

    // ---- PCM-16 -> float32 in [-1, 1]  (THE pinned conversion) ----
    const jsize n = env->GetArrayLength(pcm);
    jshort *samples = env->GetShortArrayElements(pcm, nullptr);
    std::vector<float> pcmf32(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        pcmf32[static_cast<size_t>(i)] = static_cast<float>(samples[i]) / 32768.0f;
    }
    env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);  // read-only, no copy-back

    // ---- Run whisper ----
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = recommended_threads();
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_special = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.no_context = true;
    wparams.single_segment = false;
    // ---- HANG CAP (Phase 7, 2026-07-03) ----
    // A wrong-language decode makes whisper flail. The dominant cause is the temperature-fallback
    // RE-DECODE loop: on a window whose compression-ratio/logprob fails a threshold, whisper retries
    // at temperature += temperature_inc (up to ~5× work per window). Disabling it removes that
    // multiplier so a mismatch decode costs ~the same as a normal one (just garbage), not ~5× — the
    // primary fix for the ~26-min hang.
    wparams.temperature_inc = 0.0f;
    // Belt-and-suspenders STRUCTURAL cap: abort if wall-clock exceeds a generous, duration-aware
    // budget (12× realtime vs the ~6× normal on the A03), so no decode can EVER hang long even if a
    // pathological case slips past temperature_inc=0 / detect-first. On abort whisper_full returns
    // non-zero and we surface an honest failure (audio is already saved).
    {
        long long test_ms = g_test_abort_budget_ms.load(std::memory_order_relaxed);
        long long budget_ms = test_ms > 0 ? test_ms : std::max<long long>(
            30000, static_cast<long long>(static_cast<double>(n) / 16000.0 * 1000.0 * 12.0));
        g_abort_deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(budget_ms);
    }
    wparams.abort_callback = abort_cb;
    wparams.abort_callback_user_data = nullptr;
    // Live 0..100 progress for the UI ("Transcribing… NN%"). Reset before the run.
    g_progress.store(0, std::memory_order_relaxed);
    wparams.progress_callback = progress_cb;
    wparams.progress_callback_user_data = nullptr;

    // Language: empty -> auto-detect; otherwise the BCP-47/ISO tag's primary subtag.
    const char *lang_c = nullptr;
    std::string lang_str;
    if (languageTag != nullptr) {
        const char *raw = env->GetStringUTFChars(languageTag, nullptr);
        lang_str = raw ? raw : "";
        env->ReleaseStringUTFChars(languageTag, raw);
    }
    if (!lang_str.empty()) {
        // whisper wants the short code ("en"), not "en-GB"; take the primary subtag.
        size_t dash = lang_str.find('-');
        if (dash != std::string::npos) lang_str = lang_str.substr(0, dash);
        lang_c = lang_str.c_str();
    }
    wparams.language = lang_c;  // nullptr => auto-detect

    int rc;
    {
        // Serialise inference on the single cached context (whisper_full mutates it).
        std::lock_guard<std::mutex> lock(g_model_mutex);
        rc = whisper_full(ctx, wparams, pcmf32.data(), static_cast<int>(pcmf32.size()));
        if (rc != 0) {
            LOGE("whisper_full failed rc=%d", rc);
            return result;
        }

        // Capture the language whisper reported for this decode (free — no extra inference).
        // Spot-check whether this tracks the real language or just echoes the forced one.
        g_last_lang_id.store(whisper_full_lang_id(ctx), std::memory_order_relaxed);

        // ---- Map segments -> List<TranscriptSegment> ----
        jclass segCls = env->FindClass(
                "london/aipartner/echo/core/transcribe/TranscriptSegment");
        // primary ctor (text, tStartMs, tEndMs, speaker); speaker is null on-device v1.
        jmethodID segCtor = env->GetMethodID(
                segCls, "<init>", "(Ljava/lang/String;JJLjava/lang/String;)V");

        const int n_seg = whisper_full_n_segments(ctx);
        for (int i = 0; i < n_seg; ++i) {
            std::string text = trim_leading_space(whisper_full_get_segment_text(ctx, i));
            if (text.empty()) continue;
            jlong t0 = centiseconds_to_ms(whisper_full_get_segment_t0(ctx, i));
            jlong t1 = centiseconds_to_ms(whisper_full_get_segment_t1(ctx, i));
            jstring jtext = env->NewStringUTF(text.c_str());
            jobject seg = env->NewObject(segCls, segCtor, jtext, t0, t1,
                                         /*speaker=*/static_cast<jobject>(nullptr));
            env->CallBooleanMethod(result, listAdd, seg);
            env->DeleteLocalRef(seg);
            env->DeleteLocalRef(jtext);
        }
    }

    g_progress.store(100, std::memory_order_relaxed);
    LOGI("nativeTranscribe done (%d input samples)", static_cast<int>(n));
    return result;
}

// Current 0..100 progress of the in-flight transcription. Reset to 0 between passes
// (see nativeRelease) so an IDLE engine reads 0, not a stale 100 — otherwise the next
// pass's progress poller reads the previous pass's leftover 100 during the decode/VAD
// window (before whisper_full resets it), surfacing a nonsensical "100% then climbs".
extern "C" JNIEXPORT jint JNICALL
Java_london_aipartner_echo_core_transcribe_NativeWhisperEngine_nativeProgress(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(g_progress.load(std::memory_order_relaxed));
}

// The ISO code whisper reported for the LAST decode ("en", "sl", …), or null if none yet.
// The free Layer-2 spot-check getter (no extra inference). If this only ever echoes the
// forced language it is useless for detect-to-warn and a dedicated auto-detect pass is needed.
extern "C" JNIEXPORT jstring JNICALL
Java_london_aipartner_echo_core_transcribe_NativeWhisperEngine_nativeLastLangId(
        JNIEnv *env, jobject /*thiz*/) {
    int id = g_last_lang_id.load(std::memory_order_relaxed);
    if (id < 0) return nullptr;
    const char *code = whisper_lang_str(id);
    return code ? env->NewStringUTF(code) : nullptr;
}

// Test-only: force the decode abort budget (ms), or 0 to restore the duration-aware default.
extern "C" JNIEXPORT void JNICALL
Java_london_aipartner_echo_core_transcribe_NativeWhisperEngine_nativeSetTestAbortBudgetMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ms) {
    g_test_abort_budget_ms.store(static_cast<long long>(ms), std::memory_order_relaxed);
}

// Dedicated language AUTO-DETECT pass (Phase 7 Layer 2). Unlike nativeTranscribe (which
// FORCES a language), this asks whisper what language the audio actually IS. Runs ONLY on a
// suspected mis-decode (the repetition-loop guard already fired), so its extra encoder cost is
// bounded to the rare failure case. Returns the top ISO code ("sl", …) or null.
//
// MEMORY DISCIPLINE (Phase-5/6 OOM): the caller RELEASES the transcription context before this
// runs, so acquire_context_locked here holds the ONLY resident whisper context — never two at
// once on the 2 GB A03. The context is freed again by the caller's release() after this returns.
extern "C" JNIEXPORT jstring JNICALL
Java_london_aipartner_echo_core_transcribe_NativeWhisperEngine_nativeDetectLanguage(
        JNIEnv *env, jobject /*thiz*/, jshortArray pcm, jstring modelPath, jstring forcedTag) {
    if (pcm == nullptr || modelPath == nullptr) return nullptr;

    const char *model_path_c = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context *ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        ctx = acquire_context_locked(model_path_c);
    }
    env->ReleaseStringUTFChars(modelPath, model_path_c);
    if (ctx == nullptr) return nullptr;

    // Forced language id (the language we WOULD decode in), to report its probability.
    int forced_id = -1;
    if (forcedTag != nullptr) {
        const char *ft = env->GetStringUTFChars(forcedTag, nullptr);
        if (ft != nullptr) { forced_id = whisper_lang_id(ft); env->ReleaseStringUTFChars(forcedTag, ft); }
    }

    // PCM-16 -> float32 (the pinned conversion, same as nativeTranscribe).
    const jsize n = env->GetArrayLength(pcm);
    jshort *samples = env->GetShortArrayElements(pcm, nullptr);
    std::vector<float> pcmf32(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        pcmf32[static_cast<size_t>(i)] = static_cast<float>(samples[i]) / 32768.0f;
    }
    env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);

    int lang_id;
    float top_prob = 0.0f;
    float forced_prob = 0.0f;
    std::vector<float> probs(static_cast<size_t>(whisper_lang_max_id() + 1));
    {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        const int n_threads = recommended_threads();
        // Auto-detect needs the mel computed first; it runs the ENCODER over the first 30 s
        // (NO autoregressive decode — this is the cheap encoder-only pass, not a transcription).
        if (whisper_pcm_to_mel(ctx, pcmf32.data(), static_cast<int>(pcmf32.size()), n_threads) != 0) {
            LOGE("whisper_pcm_to_mel failed for language detect");
            return nullptr;
        }
        lang_id = whisper_lang_auto_detect(ctx, 0, n_threads, probs.data());
        if (lang_id >= 0 && lang_id < static_cast<int>(probs.size())) top_prob = probs[lang_id];
        if (forced_id >= 0 && forced_id < static_cast<int>(probs.size())) forced_prob = probs[forced_id];
    }
    if (lang_id < 0) return nullptr;
    const char *code = whisper_lang_str(lang_id);
    if (code == nullptr) return nullptr;

    // Diagnostics: log the top-5 languages so the mismatch threshold can be calibrated on real
    // audio (whisper-base confuses close languages, so the TOP prob can be low even on a clear
    // non-English clip — what matters is that P(forced) is low).
    {
        std::vector<int> idx(probs.size());
        for (size_t i = 0; i < probs.size(); ++i) idx[i] = static_cast<int>(i);
        std::partial_sort(idx.begin(), idx.begin() + std::min<size_t>(5, idx.size()), idx.end(),
                          [&](int a, int b) { return probs[a] > probs[b]; });
        char top5[256]; int off = 0;
        for (size_t k = 0; k < std::min<size_t>(5, idx.size()); ++k) {
            const char *c = whisper_lang_str(idx[k]);
            off += snprintf(top5 + off, sizeof(top5) - off, "%s=%.3f ", c ? c : "?", probs[idx[k]]);
            if (off >= (int)sizeof(top5)) break;
        }
        LOGI("nativeDetectLanguage top5: %s | forced_prob=%.4f", top5, forced_prob);
    }

    // Return "topcode:topprob:forcedprob" (e.g. "hr:0.2880:0.0100"); Kotlin decides mismatch on
    // P(forced) being low, not on top_prob being high.
    char buf[96];
    snprintf(buf, sizeof(buf), "%s:%.4f:%.4f", code, top_prob, forced_prob);
    LOGI("nativeDetectLanguage -> %s", buf);
    return env->NewStringUTF(buf);
}

// Frees the cached whisper context. Called after a transcription pass completes so
// the ~150 MB model does NOT stay resident alongside capture's AudioRecord/MediaCodec
// stack or the MediaPipe/SQLCipher runtimes — the Phase-5/6 low-RAM (2 GB A03) OOM
// mitigation. The context is lazily re-acquired on the next nativeTranscribe call.
extern "C" JNIEXPORT void JNICALL
Java_london_aipartner_echo_core_transcribe_NativeWhisperEngine_nativeRelease(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    // Idle progress is 0, never a stale 100 left over from the pass that just finished —
    // so the next recording's poller doesn't briefly publish 100 before whisper starts.
    g_progress.store(0, std::memory_order_relaxed);
    g_last_lang_id.store(-1, std::memory_order_relaxed);
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_ctx_path.clear();
        LOGI("native whisper context released");
    }
}
