package london.aipartner.echo.core.transcribe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a finished recording file into 16 kHz mono PCM-16 — the format
 * whisper.cpp expects. Pulled behind an interface so [OnDeviceTranscriber] is
 * unit-testable against canned PCM without touching the Android codec stack.
 */
interface PcmDecoder {
    /** Decode the audio at [audio] to mono 16 kHz PCM-16 samples. */
    fun decodeToPcm16Mono16k(audio: AudioRef): ShortArray
}

/**
 * `MediaExtractor` + `MediaCodec` implementation. Decodes the recording's encoded
 * audio (AAC/Opus container from the capture service) to PCM-16, downmixing to
 * mono and decimating to 16 kHz with a simple integer step.
 *
 * Runs fully on-device, no network. **Unvalidated on a physical device** — the
 * codec loop is faithful but the Phase 4 gate's offline smoke test on real
 * hardware is the authority (see `phase-04-transcription.md`).
 */
class MediaCodecPcmDecoder(
    private val targetRateHz: Int = 16_000,
) : PcmDecoder {

    override fun decodeToPcm16Mono16k(audio: AudioRef): ShortArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audio.localPath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return ShortArray(0)

            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val srcRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            val out = ArrayList<Short>(srcRate) // grows; fine for memo-length audio
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            // Decimation: keep every Nth mono sample to approach targetRateHz.
            val step = (srcRate.toDouble() / targetRateHz).coerceAtLeast(1.0)
            var carry = 0.0

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0,
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    carry = downmixAndDecimate(shorts, channels, step, carry, out)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }

            codec.stop()
            codec.release()
            return out.toShortArray()
        } finally {
            extractor.release()
        }
    }

    /** Downmix interleaved frames to mono and keep ~1 of every [step] samples. */
    private fun downmixAndDecimate(
        shorts: java.nio.ShortBuffer,
        channels: Int,
        step: Double,
        carryIn: Double,
        out: ArrayList<Short>,
    ): Double {
        var carry = carryIn
        val frames = shorts.remaining() / channels
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += shorts.get()
            val mono = (sum / channels).toShort()
            carry += 1.0
            if (carry >= step) {
                out.add(mono)
                carry -= step
            }
        }
        return carry
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
