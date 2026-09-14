package london.aipartner.echo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Debug
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
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
import london.aipartner.echo.core.data.TranscriptDao
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.transcribe.TranscriptionWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 gate — **★ Memory-pressure on the A03 (BINDING watch-item — Phase-5 carry).**
 *
 * The Phase-5 OOM only surfaced when **whisper.cpp + MediaPipe (TFLite/XNNPACK) +
 * SQLCipher coexist in ONE process** — every test passed alone. This drives the full
 * record→transcribe→index/search flow through the **REAL shipped
 * [london.aipartner.echo.transcribe.TranscriptionWorker]** (Phase-7 decouple) — built with the
 * production [HiltWorkerFactory] and run via `TestListenableWorkerBuilder.doWork()`, so this
 * measures the *actual* worker path (worker → on-device whisper `Transcriber` + real
 * `MediaPipeSemanticIndex` + encrypted Room/SQLCipher), not the pre-decouple inline path. Using the
 * worker builder runs `doWork()` synchronously in the shipping single process without needing
 * WorkManager initialised (HiltTestApplication has no `Configuration.Provider`). Asserts:
 *  - the pass completes (process NOT OOM-killed) with the correct transcript;
 *  - the MediaPipe embedder is alive alongside whisper — a semantic query ranks the
 *    recording back (whisper native context is released in the processor's `finally`, so
 *    ~150 MB does not stay resident, the Phase-5/6 mitigation);
 *  - peak PSS stays within a survivable bound on the 2 GB A03 (actual peak is logged).
 *
 * Run offline. The whisper `base` weights are read from the app's internal
 * `filesDir/models/ggml-base.bin` (production path) — side-load them first (see
 * PROGRESS.md); a fresh install wipes them.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MemoryPressureTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val recId = "mem-pressure-jfk"

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var recordingDao: RecordingDao
    @Inject lateinit var transcriptDao: TranscriptDao
    @Inject lateinit var audioEncryptor: AudioEncryptor
    @Inject lateinit var semanticIndex: SemanticIndex

    @Before fun setUp() {
        hiltRule.inject()
    }

    @After fun tearDown() {
        runBlocking {
            recordingDao.getById(recId)?.let { old ->
                old.localAudioRef?.let { runCatching { File(it).delete() } }
                recordingDao.deleteById(recId)
            }
        }
    }

    @Test
    fun fullFlow_coexistsInOneProcess_withoutOom_peakPssRecorded() = runBlocking {
        val audioRef = seedEncrypted(recId, "jfk.wav")

        Runtime.getRuntime().gc()
        val baselinePss = Debug.getPss()

        // Build + run the REAL shipped worker (whisper + MediaPipe + SQLCipher all live inside its
        // doWork → TranscribingPostProcessor.process). doWork() is suspend; call it directly.
        val worker = TestListenableWorkerBuilder<TranscriptionWorker>(ctx)
            .setWorkerFactory(workerFactory)
            .setInputData(
                workDataOf(
                    TranscriptionWorker.KEY_RECORDING_ID to recId,
                    TranscriptionWorker.KEY_AUDIO_REF to audioRef,
                ),
            )
            .build()
        val result = worker.doWork()

        val peakPss = Debug.getPss()
        assertTrue("worker must succeed, was $result", result is ListenableWorker.Result.Success)

        // 1. Whisper produced the correct, non-fabricated transcript; status DONE.
        assertEquals(TranscriptionStatus.DONE.name, recordingDao.getById(recId)!!.transcriptionStatus)
        val rev = transcriptDao.revisionsFor(recId).single()
        val text = transcriptDao.segmentsFor(rev.id).joinToString(" ") { it.text }
            .lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        assertTrue("transcript was: <$text>", text.contains("country"))

        // 2. The MediaPipe embedder is alive alongside whisper: the recording was indexed
        //    during the pass and a semantic query ranks it back.
        val hits = semanticIndex.query("what can you do for your nation", k = 5)
        assertTrue(
            "semantic search must return the indexed recording (MediaPipe coexisted)",
            hits.any { it.recordingId == recId },
        )

        // 3. Survivable peak on the 2 GB A03 — reaching here means no OOM-kill.
        Runtime.getRuntime().gc()
        val afterPss = Debug.getPss()
        Log.i(
            "EchoMemGate",
            "PSS KB — baseline=$baselinePss peak=$peakPss afterGc=$afterPss " +
                "(delta_peak=${peakPss - baselinePss})",
        )
        assertTrue(
            "peak PSS ${peakPss}KB exceeds survivable bound on the 2GB A03",
            peakPss < 1_400_000, // 1.4 GB — generous headroom; catches a real regression.
        )
    }

    // ---- audio seeding (mirrors TranscriptionOnStopGateTest: engine-format encrypted) ----

    private suspend fun seedEncrypted(id: String, wavAsset: String): String {
        val pcm44k = upsample(readWavPcm16Mono(wavAsset), 16_000, 44_100)
        val m4a = File(ctx.cacheDir, "$id.m4a")
        encodeAacMp4(pcm44k, 44_100, m4a)
        val encrypted = audioEncryptor.encryptFrom(m4a, "$id.m4a")
        recordingDao.insert(
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
