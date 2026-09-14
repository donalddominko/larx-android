package london.aipartner.echo.core.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The v1 capture engine: raw [AudioRecord] PCM → **live gain** → [MediaCodec] AAC
 * encode → [MediaMuxer] (m4a). Replaces the old MediaRecorder engine so the user's
 * **mic-boost gain is applied to the live stream** — the waveform meter reflects it as
 * you record (immediate proof it's working) and the saved file is louder with **no
 * post-capture re-encode** (no "Saving…" delay, none of the re-encode fragility).
 *
 * Gain is read **per buffer** from [gainProvider] (the persisted `CapturePreferences`
 * slider), so moving the slider mid-recording changes the audio + meter immediately.
 * Hard-clipped per sample (a boost can never wrap a loud sample to the opposite sign).
 *
 * Capture runs on a dedicated thread (real-time audio). [pause]/[resume] keep the mic
 * open but stop feeding the encoder, so paused time is excluded from the duration.
 */
internal class AudioRecordCaptureHandle(
    private val outputFile: File,
    private val sampleRateHz: Int,
    private val audioSource: Int,
    private val gainProvider: () -> Float,
) : CaptureHandle {

    private val _amplitude = MutableStateFlow(0f)

    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var finalizeMuxer = true
    @Volatile private var producedFrames = 0L // mono frames actually encoded (excludes pauses)
    private val done = CountDownLatch(1)
    private var thread: Thread? = null

    private lateinit var audioRecord: AudioRecord
    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var presentationUs = 0L

    /** Initialises the mic + encoder and starts the capture thread. Throws on init failure
     *  (the controller's start path handles it); nothing is persisted in that case. */
    fun begin() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRateHz, CHANNEL, ENCODING)
        val bufBytes = maxOf(minBuf, sampleRateHz / 5 * 2) // ~200ms floor
        @Suppress("MissingPermission") // ConsentGate already verified RECORD_AUDIO before start
        audioRecord = AudioRecord(audioSource, sampleRateHz, CHANNEL, ENCODING, bufBytes)
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        audioRecord.startRecording()
        thread = Thread({ captureLoop() }, "EchoCapture-pcm").also { it.start() }
    }

    private fun captureLoop() {
        val pcm = ShortArray(sampleRateHz / 20) // ~50ms mono — responsive meter
        val bytes = ByteArray(pcm.size * 2)
        try {
            while (!stopRequested) {
                val n = audioRecord.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                if (paused) { _amplitude.value = 0f; continue }

                // Live gain + peak (drives the meter so the slider is visibly working).
                val gain = gainProvider().coerceIn(CapturePreferences.MIN_GAIN, CapturePreferences.MAX_GAIN)
                var peak = 0
                for (i in 0 until n) {
                    val s = (pcm[i] * gain).toInt().coerceIn(-32768, 32767)
                    pcm[i] = s.toShort()
                    val a = if (s < 0) -s else s
                    if (a > peak) peak = a
                }
                // nudge so an unchanged level still re-emits (StateFlow conflates equals).
                _amplitude.value = (peak / 32767f).coerceIn(0f, 1f) + jitter()

                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, n)
                encodeInput(bytes, n * 2, endOfStream = false)
                drainOutput(endOfStream = false)
            }
            // Flush: signal end-of-stream and drain the encoder's tail into the muxer.
            encodeInput(bytes, 0, endOfStream = true)
            drainOutput(endOfStream = true)
        } catch (t: Throwable) {
            Log.e(TAG, "PCM capture loop failed", t)
        } finally {
            cleanup()
            done.countDown()
        }
    }

    private var jitterToggle = false
    /** A vanishing alternation so the conflated amplitude StateFlow always re-emits each
     *  buffer even when the level is unchanged (a flat meter would otherwise stop updating). */
    private fun jitter(): Float { jitterToggle = !jitterToggle; return if (jitterToggle) 1e-4f else 0f }

    /**
     * Queues a PCM chunk into the encoder, splitting across input buffers when the chunk
     * exceeds an input buffer's capacity (the original BufferOverflow cause). Advances the
     * presentation clock + frame count by what's actually queued, so duration excludes
     * paused gaps and the AAC timestamps are correct.
     */
    private fun encodeInput(bytes: ByteArray, len: Int, endOfStream: Boolean) {
        var offset = 0
        while (true) {
            val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) { drainOutput(endOfStream = false); continue }
            val buf = encoder.getInputBuffer(index)!!
            buf.clear()
            val chunk = minOf(len - offset, buf.remaining())
            if (chunk > 0) buf.put(bytes, offset, chunk)
            offset += chunk
            val last = endOfStream && offset >= len
            val flags = if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            encoder.queueInputBuffer(index, 0, chunk, presentationUs, flags)
            presentationUs += (chunk / 2).toLong() * 1_000_000L / sampleRateHz
            producedFrames += chunk / 2
            if (offset >= len) return
            drainOutput(endOfStream = false)
        }
    }

    private fun drainOutput(endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) DEQUEUE_TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return // no pending output; keep capturing
                    // else: still waiting for the EOS buffer — loop.
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val out = encoder.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, out, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun cleanup() {
        runCatching { audioRecord.stop() }
        runCatching { audioRecord.release() }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        // A real process kill never reaches MediaMuxer.stop(), so the .m4a is left with
        // mdat sample data but NO moov atom (unplayable). [abandonForTest] reproduces that
        // exact state so process-death recovery is tested against a real killed partial,
        // not a synthetic temp.
        if (finalizeMuxer) runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
    }

    /**
     * Test-only: simulate a process kill mid-capture. Stops the capture thread and
     * releases the mic/encoder but deliberately does NOT finalize the muxer, leaving a
     * real moov-less `.m4a` partial on disk exactly as an OS process death would. The
     * file holds genuine captured AAC frames in `mdat` but is undecodable.
     */
    @androidx.annotation.VisibleForTesting
    fun abandonForTest() {
        finalizeMuxer = false
        stopRequested = true
        done.await()
    }

    override fun amplitude(): Flow<Float> = _amplitude

    override suspend fun pause() { paused = true }

    override suspend fun resume() { paused = false }

    override suspend fun stop(): CaptureResult {
        stopRequested = true
        withContext(Dispatchers.IO) { done.await() }
        val durationMs = producedFrames * 1000L / sampleRateHz
        return CaptureResult(outputFile, Codec.AAC, durationMs)
    }

    private companion object {
        const val TAG = "EchoCapture"
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BIT_RATE = 128_000
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
