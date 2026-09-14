package london.aipartner.echo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.core.transcribe.AudioRef
import london.aipartner.echo.core.transcribe.EnergyVad
import london.aipartner.echo.core.transcribe.MediaCodecPcmDecoder
import london.aipartner.echo.core.transcribe.ModelProvisioner
import london.aipartner.echo.core.transcribe.NativeWhisperEngine
import london.aipartner.echo.core.transcribe.OnDeviceTranscriber
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.core.transcribe.TranscribeOpts
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ★ `better-models-pro.md` BLOCKER measurement (2026-07-07) — does whisper **`small`**
 * (~466 MB) run on the A03 (SM-A035F, ~3 GB RAM) without OOM, and is it usably fast?
 *
 * This is the single result that gates Decisions 2 & 3 (the model ladder / free-Pro line):
 * can `base+small` be a viable FREE tier, or does the floor device already fall over?
 *
 * It measures the load-bearing coexistence that OOM'd in Phase 5 — the **`small` whisper
 * context + MediaPipe (USE embedder) + SQLCipher** all resident in ONE process — plus the
 * real-time factor of the decode. NOT the SHA-pinned production `base` path: the `small`
 * weights are side-loaded to the app's external files dir and loaded by absolute path
 * through a fixed provisioner (measurement only; not a shipping wiring).
 *
 * SIDE-LOAD FIRST (a fresh install does not wipe external files):
 *   adb push .tmp/ggml-small.bin \
 *     /sdcard/Android/data/london.aipartner.echo.debug/files/models/ggml-small.bin
 *
 * Reads the numbers off logcat tag `EchoSmallBench`. Run offline.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SmallModelBenchTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val recId = "small-bench-jfk"

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var recordingDao: RecordingDao
    @Inject lateinit var audioEncryptor: AudioEncryptor
    @Inject lateinit var semanticIndex: SemanticIndex

    @Before fun setUp() { hiltRule.inject() }

    @After fun tearDown() {
        runBlocking {
            recordingDao.getById(recId)?.let { old ->
                old.localAudioRef?.let { runCatching { File(it).delete() } }
                recordingDao.deleteById(recId)
            }
        }
    }

    @Test
    fun small_onA03_peakPssAndRtf() = runBlocking {
        val candidates = listOf(
            File(ctx.getExternalFilesDir(null), "models/ggml-small.bin"),
            File("/sdcard/Android/data/london.aipartner.echo.debug/files/models/ggml-small.bin"),
            File("/storage/emulated/0/Android/data/london.aipartner.echo.debug/files/models/ggml-small.bin"),
            File(ctx.filesDir, "models/ggml-small.bin"),
        )
        candidates.forEach {
            Log.i("EchoSmallBench", "candidate ${it.absolutePath} isFile=${it.isFile} len=${it.length()}")
        }
        val modelFile = candidates.firstOrNull { it.isFile && it.length() > 400_000_000L }
        assertTrue(
            "side-load ggml-small.bin (~466MB) first (see class KDoc); none of the candidates resolved",
            modelFile != null,
        )
        modelFile!!

        // A ~66 s clip (jfk repeated 6×) — a realistic memo length so the RTF and the peak
        // are representative of real use, not an 11 s toy.
        val jfk = readWavPcm16Mono("jfk.wav")
        val reps = 6
        val long = ShortArray(jfk.size * reps)
        for (r in 0 until reps) System.arraycopy(jfk, 0, long, r * jfk.size, jfk.size)
        val audioMs = long.size * 1000L / 16_000

        // Encode → encrypt (touch SQLCipher-backed crypto + DAO) → decrypt, mirroring the app.
        val pcm44k = upsample(long, 16_000, 44_100)
        val m4a = File(ctx.cacheDir, "$recId.m4a")
        encodeAacMp4(pcm44k, 44_100, m4a)
        val encrypted = audioEncryptor.encryptFrom(m4a, "$recId.m4a")
        recordingDao.insert(
            RecordingEntity(
                id = recId, createdAt = 1_000L, durationMs = audioMs,
                captureSource = "MIC", fidelity = "HD", captureMode = "ON_DEMAND",
                contactHash = null, localAudioRef = encrypted.absolutePath,
                encryptionMeta = "codec=AAC", syncState = "LOCAL_ONLY",
                transcriptionStatus = TranscriptionStatus.PENDING.name,
            ),
        )
        val decrypted = audioEncryptor.decryptToTemp(encrypted)

        // Warm MediaPipe (USE embedder resident) so the peak reflects real coexistence.
        semanticIndex.query("warmup query", k = 1)

        Runtime.getRuntime().gc()
        val baselinePss = Debug.getPss()

        val engine = NativeWhisperEngine()
        assertTrue("native whisper lib must load on arm64", engine.isAvailable())
        val transcriber = OnDeviceTranscriber(
            decoder = MediaCodecPcmDecoder(),
            vad = EnergyVad(),
            engine = engine,
            modelProvisioner = fixedProvisioner(modelFile.absolutePath),
        )

        // ★ The decode with `small` — timed for RTF, PSS sampled while the context is resident.
        val elapsedMs = kotlin.system.measureTimeMillis {
            val transcript = transcriber.transcribe(
                AudioRef(decrypted.absolutePath),
                TranscribeOpts(languageTag = "en"),
            )
            val peakPss = Debug.getPss() // whisper `small` context still held here.
            val text = transcript.segments.joinToString(" ") { it.text }
                .lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            Log.i(
                "EchoSmallBench",
                "TRANSCRIPT ok=${text.contains("country")} segs=${transcript.segments.size} " +
                    "peakPss_KB=$peakPss text.head=<${text.take(60)}>",
            )
            // Index while whisper context is (about to be) freed — MediaPipe + result coexist.
            semanticIndex.index(recId, transcript)
        }
        val afterIndexPss = Debug.getPss()
        engine.release()
        Runtime.getRuntime().gc()
        val afterReleasePss = Debug.getPss()

        val rtf = elapsedMs.toDouble() / audioMs
        Log.i(
            "EchoSmallBench",
            "RESULT model=small audioMs=$audioMs decodeMs=$elapsedMs RTF=${"%.2f".format(rtf)} " +
                "baselinePss_KB=$baselinePss afterIndexPss_KB=$afterIndexPss " +
                "afterReleasePss_KB=$afterReleasePss",
        )

        // Not a pass/fail gate on the numbers — this RECORDS them (the OOM-kill itself is the
        // only hard failure; if the process survives to here, small did NOT OOM on the A03).
        assertTrue("reached end without OOM-kill", afterReleasePss > 0)
    }

    private fun fixedProvisioner(path: String) = object : ModelProvisioner {
        override fun isModelReady() = true
        override fun modelPathOrNull() = path
        override suspend fun ensureModel() = path
    }

    private fun readWavPcm16Mono(name: String): ShortArray {
        val bytes = testCtx.assets.open(name).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val cid = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (cid == "data") { dataOffset = pos + 8; dataLen = size; break }
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
