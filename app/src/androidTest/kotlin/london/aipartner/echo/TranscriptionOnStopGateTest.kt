package london.aipartner.echo

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.data.DbPassphraseProvider
import london.aipartner.echo.core.data.EchoDatabase
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.core.transcribe.EnergyVad
import london.aipartner.echo.core.transcribe.FileSystemModelProvisioner
import london.aipartner.echo.core.transcribe.MediaCodecPcmDecoder
import london.aipartner.echo.core.transcribe.NativeWhisperEngine
import london.aipartner.echo.core.transcribe.OnDeviceTranscriber
import london.aipartner.echo.core.transcribe.PlayAssetDeliveryModelProvisioner
import london.aipartner.echo.core.transcribe.SearchHit
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.core.transcribe.Transcript
import london.aipartner.echo.core.transcribe.TranscriptWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 Step 4 — **BINDING live-mic / transcription-on-stop gate** (on-device, A03).
 *
 * Drives the REAL [london.aipartner.echo.transcribe.TranscribingPostProcessor] — the exact
 * orchestration the recording service invokes on stop — over a real encrypted recording,
 * with the REAL on-device whisper engine + the REAL `base` model, fully offline. This
 * closes the capture→encrypt→**decrypt→VAD→whisper→persist rev0→status** path end-to-end.
 *
 * Why this shape: a literal "human speaks into the mic" check can't be machine-verified
 * headlessly (live AudioRecord in a test lab is silence). The live AudioRecord→encrypted
 * m4a half is proven decodable by `RecorderControllerTest` on device; the whisper-on-the-
 * engine's-44.1kHz-format half is proven by `GateBOnDeviceWhisperTest.b4`. This test joins
 * them through the actual post-processor: known speech, encrypted exactly as capture stores
 * it, run through the real post-stop pass, asserting a correct transcript + the honest
 * status transitions. (A final human spoken-word spot-check on the installed build is the
 * review step.)
 *
 * Model: pushed to the app's external files dir via adb (the 142 MB weights are gitignored):
 *   adb push ggml-base.bin /sdcard/Android/data/london.aipartner.echo.debug/files/models/ggml-base.bin
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionOnStopGateTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private lateinit var db: EchoDatabase
    private lateinit var encryptor: AudioEncryptor
    private lateinit var modelFile: File

    private val expectedSha = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"

    private object NoopIndex : SemanticIndex {
        override val model = "noop"
        override suspend fun index(recordingId: String, transcript: Transcript) = Unit
        override suspend fun query(query: String, k: Int): List<SearchHit> = emptyList()
    }

    @Before
    fun setUp() {
        ctx.deleteDatabase(EchoDatabase.DB_NAME)
        db = EchoDatabase.build(ctx, DbPassphraseProvider(ctx).getOrCreate())
        encryptor = AudioEncryptor(ctx)
        // Sideloaded via adb into the app-owned external files root (a subdir created by
        // adb/shell would be shell-owned and untraversable by the app uid).
        modelFile = File(ctx.getExternalFilesDir(null), "ggml-base.bin")
        assertTrue(
            "On-device whisper model missing. Push it first:\n" +
                "adb push ggml-base.bin ${modelFile.absolutePath}",
            modelFile.isFile && modelFile.length() > 0,
        )
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteDatabase(EchoDatabase.DB_NAME)
    }

    private fun postProcessor() = london.aipartner.echo.transcribe.TranscribingPostProcessor(
        recordingDao = db.recordingDao(),
        transcriber = OnDeviceTranscriber(
            decoder = MediaCodecPcmDecoder(),
            vad = EnergyVad(),
            engine = NativeWhisperEngine(),
            modelProvisioner = FileSystemModelProvisioner(
                modelFile = modelFile,
                expectedSha256 = expectedSha,
                fallback = PlayAssetDeliveryModelProvisioner(expectedSha),
            ),
        ),
        transcriptWriter = TranscriptWriter(db.transcriptDao()),
        semanticIndex = NoopIndex,
        aiArtifactDao = db.aiArtifactDao(),
        encryptor = encryptor,
        progressBus = london.aipartner.echo.transcribe.TranscriptionProgressBus(),
        transcriptionPreferences = london.aipartner.echo.transcribe.TranscriptionPreferences(ctx),
    )

    /** Seed an encrypted recording from a known WAV asset, captured in the engine's real
     *  container (44.1 kHz AAC-LC mono MP4), and return its id. */
    private suspend fun seedEncrypted(id: String, wavAsset: String): String {
        val pcm44k = upsample(readWavPcm16Mono(wavAsset), 16_000, 44_100)
        val m4a = File(ctx.cacheDir, "$id.m4a")
        encodeAacMp4(pcm44k, 44_100, m4a)
        val encrypted = encryptor.encryptFrom(m4a, "$id.m4a")
        db.recordingDao().insert(
            RecordingEntity(
                id = id, createdAt = 1_000L, durationMs = 11_000L,
                captureSource = "MIC", fidelity = "HD", captureMode = "ON_DEMAND",
                contactHash = null, localAudioRef = encrypted.absolutePath,
                encryptionMeta = "codec=AAC", syncState = "LOCAL_ONLY",
                transcriptionStatus = TranscriptionStatus.PENDING.name,
            ),
        )
        return encrypted.absolutePath
    }

    @Test
    fun speech_throughRealPostProcessor_writesCorrectTranscript_andStatusDone() = runBlocking {
        val id = "gate-speech"
        val audioRef = seedEncrypted(id, "jfk.wav")

        postProcessor().process(id, audioRef)

        // Status reaches DONE.
        assertEquals(TranscriptionStatus.DONE.name, db.recordingDao().getById(id)!!.transcriptionStatus)
        // rev 0 written with correct, non-fabricated text.
        val rev = db.transcriptDao().revisionsFor(id).single()
        assertEquals(0, rev.rev)
        val text = db.transcriptDao().segmentsFor(rev.id)
            .joinToString(" ") { it.text }
            .lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        assertTrue("transcript was: <$text>", text.contains("country"))
        assertTrue("transcript was: <$text>", text.contains("fellow americans"))
        // Plaintext decrypt temp was shredded (no leftover in cache for this id).
        assertTrue(
            "no plaintext temp left behind",
            ctx.cacheDir.listFiles()?.none { it.name.contains(id) && it.extension != "m4a" } ?: true,
        )
    }

    @Test
    fun silence_throughRealPostProcessor_isNoSpeech_notFabricated_statusDone() = runBlocking {
        val id = "gate-silence"
        val audioRef = seedEncrypted(id, "silence.wav")

        postProcessor().process(id, audioRef)

        // DONE (transcription completed) but no fabricated words — VAD hard rule held.
        assertEquals(TranscriptionStatus.DONE.name, db.recordingDao().getById(id)!!.transcriptionStatus)
        val rev = db.transcriptDao().revisionsFor(id).single()
        assertTrue("no fabricated segments on silence", db.transcriptDao().segmentsFor(rev.id).isEmpty())
    }

    @Test
    fun transcriptionFailure_keepsAudio_setsFailedStatus() = runBlocking {
        // Audio that the decoder cannot read → the pass throws → FAILED, but the recording
        // (audio + row) is intact and retryable. Audio is persisted FIRST, never lost.
        val id = "gate-fail"
        val garbage = File(ctx.cacheDir, "$id.m4a").apply { writeBytes(ByteArray(4096) { 0x7f }) }
        val encrypted = encryptor.encryptFrom(garbage, "$id.m4a")
        db.recordingDao().insert(
            RecordingEntity(
                id = id, createdAt = 1_000L, durationMs = 1_000L,
                captureSource = "MIC", fidelity = "HD", captureMode = "ON_DEMAND",
                contactHash = null, localAudioRef = encrypted.absolutePath,
                encryptionMeta = "codec=AAC", syncState = "LOCAL_ONLY",
                transcriptionStatus = TranscriptionStatus.PENDING.name,
            ),
        )

        postProcessor().process(id, encrypted.absolutePath)

        assertEquals(TranscriptionStatus.FAILED.name, db.recordingDao().getById(id)!!.transcriptionStatus)
        assertTrue("audio survives a failed transcription", File(encrypted.absolutePath).exists())
        assertTrue("no transcript revision on failure", db.transcriptDao().revisionsFor(id).isEmpty())
    }

    // ---- audio helpers (mirror GateBOnDeviceWhisperTest; assets read from test ctx) ----

    private fun readWavPcm16Mono(name: String): ShortArray {
        val bytes = testCtx.assets.open(name).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") { dataOffset = pos + 8; dataLen = size; break }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset >= 0) { "no data chunk in $name" }
        val end = (dataOffset + dataLen).coerceAtMost(bytes.size)
        val out = ShortArray((end - dataOffset) / 2)
        ByteBuffer.wrap(bytes, dataOffset, end - dataOffset)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun upsample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || pcm.isEmpty()) return pcm
        val outLen = (pcm.size.toLong() * to / from).toInt()
        val out = ShortArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val src = i * step
            val i0 = src.toInt()
            val i1 = minOf(i0 + 1, pcm.size - 1)
            val frac = src - i0
            out[i] = (pcm[i0] * (1 - frac) + pcm[i1] * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Encode mono PCM-16 to AAC-LC in MP4 with the engine's format (44.1 kHz mono). */
    private fun encodeAacMp4(pcm: ShortArray, sampleRate: Int, dest: File) {
        if (dest.exists()) dest.delete()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val pcmBytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcmBytes.asShortBuffer().put(pcm); pcmBytes.position(0)
        val totalBytes = pcm.size * 2
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var bytesFed = 0
        var ptsUs = 0L
        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000L)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    inBuf.clear()
                    val chunk = minOf(inBuf.capacity(), totalBytes - bytesFed)
                    if (chunk > 0) {
                        val slice = ByteArray(chunk); pcmBytes.get(slice); inBuf.put(slice)
                        codec.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                        ptsUs += (chunk / 2).toLong() * 1_000_000L / sampleRate
                        bytesFed += chunk
                    } else {
                        codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000L)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                }
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
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
}
