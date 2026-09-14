package london.aipartner.echo.core.transcribe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 4 — **Gate B**, the headline gate: a real on-device transcript with the
 * network off. Runs ONLY on a physical arm64 device (the JNI `.so` + the ~142 MB
 * `ggml-base` weights are real). The weights and reference WAVs ship as
 * `src/androidTest/assets` and are copied to `filesDir` at runtime — no storage
 * permission or path juggling.
 *
 * Three ordered checks, each isolating a different failure surface (see
 * `references/phase-04-transcription.md` §"Gate B"):
 *
 *  - **B1** — known-good `jfk.wav` straight through the JNI/model path. Isolates the
 *    shim + model + PCM-16→float contract from the capture/decode path entirely.
 *    PASS = the produced text matches the known sentence.
 *  - **B2** — the SAME known audio re-encoded into a real capture container
 *    (AAC/MP4, what `RecordingService` produces) and run through the FULL
 *    `MediaCodecPcmDecoder → EnergyVad → NativeWhisperEngine` pipeline. This is the
 *    decoder's known-answer test: if B1 passed but B2 fails, the bug is in
 *    `MediaCodecPcmDecoder`, not whisper. (A live-mic recording can't be
 *    machine-verified headlessly; a known-answer compressed-container decode is the
 *    stronger, deterministic form of the same check — see PROGRESS.md.)
 *  - **B3** — a real silent file through the full path: VAD hard-rule must hold, the
 *    engine must NOT be invoked, and zero words may be fabricated.
 *
 * Run with the network OFF (the harness enables airplane mode before invoking).
 */
@RunWith(AndroidJUnit4::class)
class GateBOnDeviceWhisperTest {

    private lateinit var ctx: Context
    private lateinit var modelPath: String

    @Before
    fun setUp() {
        // The instrumentation context owns the androidTest assets.
        ctx = InstrumentationRegistry.getInstrumentation().context
        modelPath = copyAssetToFiles("ggml-base.bin")
    }

    @Test
    fun b1_referenceWav_throughJniPath_producesKnownTranscript() {
        val pcm = readWavPcm16Mono("jfk.wav")
        assertTrue("jfk.wav should decode to a non-trivial buffer", pcm.size > 16_000)

        val engine = NativeWhisperEngine()
        assertTrue(
            "Native whisper library must load on a physical arm64 device",
            engine.isAvailable(),
        )

        val segments = engine.transcribe(pcm, modelPath, "en")
        val text = segments.joinToString(" ") { it.text }.normalise()

        // Known transcript of jfk.wav (whisper.cpp's canonical sample):
        // "And so my fellow Americans, ask not what your country can do for you,
        //  ask what you can do for your country."
        assertTrue("transcript was: <$text>", text.contains("fellow americans"))
        assertTrue("transcript was: <$text>", text.contains("ask not what your country"))
        assertTrue("transcript was: <$text>", text.contains("do for your country"))

        // Timestamps must be present and monotonic for the Phase 6 karaoke player.
        assertTrue(segments.isNotEmpty())
        assertTrue(segments.all { it.tEndMs >= it.tStartMs })
        assertTrue(segments.any { it.tEndMs > 0 })
    }

    @Test
    fun b2_realCaptureContainer_fullPipeline_producesKnownTranscript() {
        val pcm = readWavPcm16Mono("jfk.wav")
        // Re-encode the known audio into the real capture container (AAC in MP4).
        val encoded = File(ctx.filesDir, "jfk_capture.m4a")
        encodePcm16MonoToAacMp4(pcm, SAMPLE_RATE, encoded)
        assertTrue("encoder produced an empty file", encoded.length() > 0)

        val transcriber = OnDeviceTranscriber(
            decoder = MediaCodecPcmDecoder(),
            vad = EnergyVad(),
            engine = NativeWhisperEngine(),
            modelProvisioner = fixedProvisioner(modelPath),
        )
        val transcript = runBlocking {
            transcriber.transcribe(AudioRef(encoded.absolutePath), TranscribeOpts(languageTag = "en"))
        }

        assertFalse("speech file must not be flagged no-speech", transcript.noSpeechDetected)
        val text = transcript.segments.joinToString(" ") { it.text }.normalise()
        // Decoder + decimation degrade quality slightly vs B1; assert the robust core.
        assertTrue("decoder-path transcript was: <$text>", text.contains("country"))
        assertTrue("decoder-path transcript was: <$text>", text.contains("fellow americans"))
    }

    @Test
    fun b4_newEngineFormat_44100AacMono_fullPipeline_producesKnownTranscript() {
        // Capture-engine parity (2026-06-26): the engine was rewritten MediaRecorder →
        // AudioRecord/MediaCodec, and it now writes **44.1 kHz AAC-LC mono MP4** (the old
        // B2 used 16 kHz, an integer decimation with no resampling). This re-proves the
        // capture→decode PCM/AAC contract on the NEW engine's exact output format: 44.1 kHz
        // forces MediaCodecPcmDecoder's **non-integer** 44100→16000 decimation, the genuinely
        // untested seam. Known-answer (jfk) so the transcript is machine-verifiable headlessly;
        // the live AudioRecord→m4a half is proven decodable by RecorderControllerTest on device.
        val pcm16k = readWavPcm16Mono("jfk.wav")
        val pcm44k = upsampleLinear(pcm16k, fromRate = SAMPLE_RATE, toRate = ENGINE_SAMPLE_RATE)
        val encoded = File(ctx.filesDir, "jfk_engine_44k.m4a")
        encodePcm16MonoToAacMp4(pcm44k, ENGINE_SAMPLE_RATE, encoded)
        assertTrue("encoder produced an empty file", encoded.length() > 0)

        val transcriber = OnDeviceTranscriber(
            decoder = MediaCodecPcmDecoder(),
            vad = EnergyVad(),
            engine = NativeWhisperEngine(),
            modelProvisioner = fixedProvisioner(modelPath),
        )
        val transcript = runBlocking {
            transcriber.transcribe(AudioRef(encoded.absolutePath), TranscribeOpts(languageTag = "en"))
        }

        assertFalse("speech file must not be flagged no-speech", transcript.noSpeechDetected)
        val text = transcript.segments.joinToString(" ") { it.text }.normalise()
        assertTrue("new-engine-format transcript was: <$text>", text.contains("country"))
        assertTrue("new-engine-format transcript was: <$text>", text.contains("fellow americans"))
    }

    @Test
    fun b3_silentCaptureContainer_fullPipeline_yieldsNoFabricatedText() {
        val pcm = readWavPcm16Mono("silence.wav")
        val encoded = File(ctx.filesDir, "silence_capture.m4a")
        encodePcm16MonoToAacMp4(pcm, SAMPLE_RATE, encoded)
        assertTrue(encoded.length() > 0)

        // A spy engine proves the hard rule structurally: the engine is NEVER called.
        val spyEngine = object : WhisperEngine {
            var invoked = false
            override fun isAvailable() = true
            override fun transcribe(pcm: ShortArray, modelPath: String, languageTag: String?): List<TranscriptSegment> {
                invoked = true
                return emptyList()
            }
        }
        val transcriber = OnDeviceTranscriber(
            decoder = MediaCodecPcmDecoder(),
            vad = EnergyVad(),
            engine = spyEngine,
            modelProvisioner = fixedProvisioner(modelPath),
        )
        val transcript = runBlocking {
            transcriber.transcribe(AudioRef(encoded.absolutePath), TranscribeOpts(languageTag = "en"))
        }

        assertFalse("VAD must NOT invoke the engine on silence", spyEngine.invoked)
        assertTrue("silence must be flagged no-speech", transcript.noSpeechDetected)
        assertEquals("zero words may be fabricated on silence", 0, transcript.segments.size)
    }

    /**
     * Phase 6 — Issue-1 source fix: idle progress is **0**, never a stale **100**.
     *
     * After a real pass the native `g_progress` ends at 100; if `release()` didn't reset it,
     * the NEXT recording's progress poller would read that leftover 100 during the decode
     * window (before whisper restarts), surfacing a nonsensical "100% then climbs". This
     * asserts the engine reads 100 right after a pass, then **0** after `release()`.
     */
    @Test
    fun progress_resetsToZeroOnRelease_soNextPassHasNoStale100() {
        val engine = NativeWhisperEngine()
        assertTrue("native lib must load on a physical arm64 device", engine.isAvailable())

        val pcm = readWavPcm16Mono("jfk.wav")
        engine.transcribe(pcm, modelPath, "en")
        // The JNI stores 100 at the end of a completed pass.
        assertEquals("a finished pass ends at 100%", 100, engine.progressPercent())

        engine.release()
        // Idle is 0 — what the next recording's poller will read before whisper restarts.
        assertEquals("idle progress after release must be 0, not a stale 100", 0, engine.progressPercent())
    }

    /**
     * ★ Phase 7 Layer-2 SPOT-CHECK (Donald's build condition): does `whisper_full_lang_id`
     * report the ACTUAL acoustic language, or merely ECHO the forced `wparams.language`?
     *
     * Clever fixture (no Slovenian audio needed): decode the **English** jfk clip while
     * **forcing the language to German ("de")**. Then read [WhisperEngine.lastDetectedLanguage].
     *  - If it returns "de" → it ECHOES the forced language → **useless** for detect-to-warn
     *    (the Slovenian-on-`en` user would get "en" back, never "sl"). Layer 2 then needs a
     *    dedicated auto-detect pass (option b).
     *  - If it returns "en" → it genuinely DETECTED despite the force → the free getter works.
     * The auto-detect control (empty language) proves the field CAN carry a real result.
     *
     * This test does not hard-fail on either outcome — it RECORDS the finding (logcat +
     * assertion message) so the Layer-2 approach is chosen on evidence, per the build condition.
     */
    @Test
    fun spotCheck_whisperFullLangId_echoesForcedLanguageOrDetects() {
        val engine = NativeWhisperEngine()
        assertTrue("native lib must load on a physical arm64 device", engine.isAvailable())
        val pcm = readWavPcm16Mono("jfk.wav") // English speech.

        // 1) Auto-detect control: no forced language → should report the real language ("en").
        engine.transcribe(pcm, modelPath, null)
        val autoDetected = engine.lastDetectedLanguage()
        android.util.Log.i("EchoLangSpotCheck", "auto-detect on English jfk → lastDetectedLanguage=$autoDetected")

        // 2) The real question: force "de" on English audio; what does the getter report?
        engine.transcribe(pcm, modelPath, "de")
        val underForcedDe = engine.lastDetectedLanguage()
        android.util.Log.i(
            "EchoLangSpotCheck",
            "forced=de on English jfk → lastDetectedLanguage=$underForcedDe " +
                "(=> ${if (underForcedDe == "de") "ECHOES forced (useless for warn)" else "DETECTS actual (free getter usable)"})",
        )

        // Record the finding; don't fail the build on the outcome (it drives the design choice).
        assertTrue(
            "spot-check inconclusive: auto=$autoDetected forcedDe=$underForcedDe (expected a non-null code)",
            autoDetected != null && underForcedDe != null,
        )
    }

    /**
     * ★ Phase 7 Layer 2 — the DEDICATED auto-detect pass (`nativeDetectLanguage`) names the ACTUAL
     * language, independent of any forced decode. This is the real detector that replaces the
     * proven-useless forced getter. On the English jfk clip it must report "en". (The value of a
     * dedicated pass over the forced getter: here there is NO forced language to echo.)
     */
    @Test
    fun detectLanguage_namesActualLanguage_notForced() {
        val engine = NativeWhisperEngine()
        assertTrue("native lib must load on a physical arm64 device", engine.isAvailable())
        val pcm = readWavPcm16Mono("jfk.wav") // English speech.

        val detected = engine.detectLanguage(pcm, modelPath, "en")
        android.util.Log.i("EchoLangSpotCheck", "nativeDetectLanguage on English jfk → $detected")
        assertEquals("dedicated auto-detect must name the real language", "en", detected?.tag)
    }

    /**
     * ★ Phase 7 Layer-2 VERIFICATION (Donald's two load-bearing assumptions before trusting
     * always-on detect-compare). Records the evidence to logcat `EchoLangVerify`:
     *  1. COST — detect must be encoder-only (much faster than a full transcription of the same
     *     audio), so always-on detect is a small fixed cost, not a second transcription pass.
     *  2. CONFIDENCE GATE — on SHORT and QUIET valid English, detect must either still say "en"
     *     or return LOW confidence, so the gate (only warn when confident AND different) does not
     *     fire a spurious "sounds like X". This is the original auto-detect objection; prove it.
     */
    @Test
    fun layer2Verification_costIsEncoderOnly_andConfidenceGateHandlesShortQuietEnglish() {
        val engine = NativeWhisperEngine()
        assertTrue("native lib must load on a physical arm64 device", engine.isAvailable())
        val full = readWavPcm16Mono("jfk.wav") // ~11 s English.

        // (1) COST: time a full transcription vs a detect pass on the SAME audio.
        val tTranscribe = kotlin.system.measureTimeMillis { engine.transcribe(full, modelPath, "en") }
        engine.release() // memory discipline: free the transcription context before detect.
        var detFull: DetectedLanguage? = null
        val tDetect = kotlin.system.measureTimeMillis { detFull = engine.detectLanguage(full, modelPath, "en") }
        android.util.Log.i(
            "EchoLangVerify",
            "COST full-audio: transcribe=${tTranscribe}ms detect=${tDetect}ms " +
                "(detect/transcribe=${"%.2f".format(tDetect.toDouble() / tTranscribe)}) detected=$detFull",
        )
        assertEquals("detect names English on the full clip", "en", detFull?.tag)
        assertTrue("detect must be cheaper than a full transcription (encoder-only)", tDetect < tTranscribe)

        // (2) CONFIDENCE GATE inputs — SHORT (~2 s) and QUIET (0.15×) valid English.
        engine.release()
        val short = full.copyOf(minOf(full.size, 2 * 16_000))
        val detShort = engine.detectLanguage(short, modelPath, "en")
        engine.release()
        val quiet = ShortArray(full.size) { (full[it] * 0.15f).toInt().toShort() }
        val detQuiet = engine.detectLanguage(quiet, modelPath, "en")
        android.util.Log.i(
            "EchoLangVerify",
            "GATE short(~2s)=$detShort quiet(0.15x)=$detQuiet — a false warn needs tag!=en AND high confidence",
        )
        // No hard assert on the exact outcome (device/model dependent) — this RECORDS the numbers
        // that calibrate the confidence threshold. The build condition is judged from these logs.
        engine.release()
    }

    /**
     * ★ Phase 7 hang-cap VERIFICATION (2026-07-03). A wrong-language decode can make whisper flail
     * for tens of minutes. Beyond `temperature_inc=0`, a wall-clock abort_callback is the STRUCTURAL
     * cap. Force a tiny abort budget and confirm a decode that would take ~a minute instead
     * TERMINATES near the bound (not the full duration) — so no decode can ever hang. Audio/input is
     * untouched (the caller's recording is persisted before transcription regardless).
     */
    @Test
    fun hangCap_abortsDecodeAtWallClockBound() {
        val engine = NativeWhisperEngine()
        assertTrue("native lib must load on a physical arm64 device", engine.isAvailable())
        val pcm = readWavPcm16Mono("jfk.wav")

        // Baseline: a normal full decode is many seconds on the A03.
        val tNormal = kotlin.system.measureTimeMillis { engine.transcribe(pcm, modelPath, "en") }
        engine.release()

        // Force a 500 ms abort budget → the decode must bail far below the normal time.
        engine.nativeSetTestAbortBudgetMs(500)
        val tCapped = kotlin.system.measureTimeMillis { engine.transcribe(pcm, modelPath, "en") }
        engine.nativeSetTestAbortBudgetMs(0) // restore default so other tests are unaffected.
        engine.release()

        android.util.Log.i("EchoHangCap", "normal=${tNormal}ms capped=${tCapped}ms")
        assertTrue(
            "capped decode ($tCapped ms) must terminate well below the normal decode ($tNormal ms)",
            tCapped < tNormal / 2,
        )
    }

    /**
     * ★ REAL-WORLD AUDIO regression (2026-07-18). The whole suite above detects/transcribes on
     * `jfk.wav` — a clean studio recording where P(en) ≈ 0.95. That fixture HID a launch blocker:
     * on real phone-mic English (room noise, close mic, a real device), whisper-`base` language ID
     * is unreliable — a genuine English clip returned **P(en)=0.116, top=ro** on the A03, so the old
     * detect-first gate SKIPPED the decode and the user got NO transcript for English (their best
     * language). Same fixture-vs-reality gap that hid the model-shipped miss.
     *
     * This test runs on a REAL phone-mic English clip (`real_en_phone.wav`) and proves the design
     * decision behind making detection ADVISORY (Direction A, 2026-07-18):
     *   (1) detection MAY be low/wrong on real audio (we only LOG it — no assertion on the value), and
     *   (2) a forced-`en` transcribe still produces a CORRECT transcript — so the transcript must
     *       NEVER be gated on the detect probability.
     *
     * The fixture is a real recording (committed to androidTest assets); it cannot be synthesised.
     * Until it's provided the test skips (assumeTrue) rather than pass on a clean fixture — a skip is
     * honest; a jfk-based pass would re-hide the bug. Set EXPECTED_PHRASE to a phrase spoken in the
     * clip for a strict content assertion; otherwise it asserts a coherent multi-word English result.
     */
    @Test
    fun realWorldEnglish_detectMayBeLow_butForcedTranscribeStillCorrect() {
        val haveFixture = ctx.assets.list("")?.contains(REAL_EN_ASSET) == true
        org.junit.Assume.assumeTrue(
            "real-world English fixture '$REAL_EN_ASSET' not present — add a real phone-mic clip " +
                "(10-15s, normal room) to core/transcribe/src/androidTest/assets to enable this gate.",
            haveFixture,
        )
        val pcm = readWavPcm16Mono(REAL_EN_ASSET)
        assertTrue("fixture should decode to a non-trivial buffer", pcm.size > 16_000)
        val engine = NativeWhisperEngine()
        assertTrue("native lib must load on a physical arm64 device", engine.isAvailable())

        // (1) DETECTION — log the real-world number; DO NOT gate on it. Reproduce the production
        // first-10 s window so the logged P(en) is comparable to the field log.
        val window = if (pcm.size > 10 * 16_000) pcm.copyOf(10 * 16_000) else pcm
        val detected = engine.detectLanguage(window, modelPath, "en")
        engine.release()
        android.util.Log.i(
            "EchoRealAudio",
            "real-mic English detect → top=${detected?.tag} topProb=${detected?.confidence} " +
                "P(en)=${detected?.forcedProbability} (this is EXACTLY what the old gate skipped on)",
        )

        // (2) FORCED-en TRANSCRIBE — must produce a correct transcript regardless of the detect value.
        val segments = engine.transcribe(pcm, modelPath, "en")
        engine.release()
        val text = segments.joinToString(" ") { it.text }.normalise()
        android.util.Log.i("EchoRealAudio", "forced-en transcript: <$text>")

        assertTrue("forced-en transcribe produced NO transcript for real English audio", text.isNotBlank())
        if (EXPECTED_PHRASE.isNotBlank()) {
            assertTrue("transcript <$text> should contain the known phrase '$EXPECTED_PHRASE'",
                text.contains(EXPECTED_PHRASE.lowercase()))
        } else {
            val words = text.split(Regex("\\s+")).filter { it.length >= 2 }
            assertTrue("transcript <$text> is not a coherent multi-word English result", words.size >= 4)
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private fun fixedProvisioner(path: String) = object : ModelProvisioner {
        override fun isModelReady() = true
        override fun modelPathOrNull() = path
        override suspend fun ensureModel() = path
    }

    private fun copyAssetToFiles(name: String): String {
        val dest = File(ctx.filesDir, name)
        if (!dest.exists() || dest.length() == 0L) {
            ctx.assets.open(name).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest.absolutePath
    }

    /** Read a canonical PCM-16 mono WAV asset into a ShortArray (LE). */
    private fun readWavPcm16Mono(name: String): ShortArray {
        val bytes = ctx.assets.open(name).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Skip "RIFF"(4) size(4) "WAVE"(4); then walk chunks to find "data".
        require(bytes.size > 44) { "$name too small to be a WAV" }
        var pos = 12
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") {
                dataOffset = pos + 8
                dataLen = size
                break
            }
            pos += 8 + size + (size and 1) // chunks are word-aligned
        }
        require(dataOffset >= 0) { "no data chunk in $name" }
        val end = (dataOffset + dataLen).coerceAtMost(bytes.size)
        val sampleCount = (end - dataOffset) / 2
        val out = ShortArray(sampleCount)
        val sb = ByteBuffer.wrap(bytes, dataOffset, end - dataOffset)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        sb.get(out)
        return out
    }

    /**
     * Encode mono PCM-16 into AAC inside an MP4 — the same encoded container family
     * the capture service writes — so B2/B3 exercise the real decode path.
     */
    private fun encodePcm16MonoToAacMp4(pcm: ShortArray, sampleRate: Int, dest: File) {
        if (dest.exists()) dest.delete()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val pcmBytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcmBytes.asShortBuffer().put(pcm)
        pcmBytes.position(0)
        val totalBytes = pcm.size * 2

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var bytesFed = 0
        var ptsUs = 0L

        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    inBuf.clear()
                    val chunk = minOf(inBuf.capacity(), totalBytes - bytesFed)
                    if (chunk > 0) {
                        val slice = ByteArray(chunk)
                        pcmBytes.get(slice)
                        inBuf.put(slice)
                        val samplesInChunk = chunk / 2
                        codec.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                        ptsUs += samplesInChunk.toLong() * 1_000_000L / sampleRate
                        bytesFed += chunk
                    } else {
                        codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0 // CSD already consumed by addTrack
                    }
                    if (info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, outBuf, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }

        codec.stop(); codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }

    /** Linear-interpolation upsample of mono PCM-16 so a 16 kHz asset plays at correct
     *  speed in a 44.1 kHz container (the engine's real rate). Good enough for an ASR
     *  known-answer; the decoder then decimates 44100→16000 (the seam under test). */
    private fun upsampleLinear(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || pcm.isEmpty()) return pcm
        val outLen = (pcm.size.toLong() * toRate / fromRate).toInt()
        val out = ShortArray(outLen)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outLen) {
            val src = i * step
            val i0 = src.toInt()
            val i1 = minOf(i0 + 1, pcm.size - 1)
            val frac = src - i0
            out[i] = (pcm[i0] * (1 - frac) + pcm[i1] * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun String.normalise(): String =
        lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val ENGINE_SAMPLE_RATE = 44_100 // AudioRecordCaptureHandle's real capture rate
        const val TIMEOUT_US = 10_000L

        // Real phone-mic English clip (16 kHz mono WAV) committed to androidTest assets.
        // NOT a synthetic/studio fixture — it must be an actual recording so it reproduces
        // real-world detection noise. The test skips until this exists.
        const val REAL_EN_ASSET = "real_en_phone.wav"
        // Optional: a known phrase spoken in the clip → strict content assertion. Blank ⇒
        // assert a coherent multi-word English result instead.
        const val EXPECTED_PHRASE = ""
    }
}
