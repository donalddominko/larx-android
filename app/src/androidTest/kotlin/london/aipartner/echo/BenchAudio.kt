package london.aipartner.echo

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared audio helpers for the on-device model benchmark harness (`ModelBenchHarnessTest`).
 * Pure WAV/PCM/AAC plumbing — no whisper, no gates — factored out so the harness reads cleanly.
 */
internal object BenchAudio {

    /** Parse a mono 16 kHz PCM-16 WAV's data chunk into samples. */
    fun readWavPcm16Mono(bytes: ByteArray): ShortArray {
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
        require(dataOffset >= 0) { "no data chunk in WAV" }
        val end = (dataOffset + dataLen).coerceAtMost(bytes.size)
        val out = ShortArray((end - dataOffset) / 2)
        ByteBuffer.wrap(bytes, dataOffset, end - dataOffset)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    /** Repeat a clip to at least [targetMs] so RTF/peak reflect a real memo length, not a toy. */
    fun repeatToAtLeast(pcm: ShortArray, sampleRate: Int, targetMs: Long): ShortArray {
        if (pcm.isEmpty()) return pcm
        val clipMs = pcm.size * 1000L / sampleRate
        val reps = ((targetMs + clipMs - 1) / clipMs).toInt().coerceAtLeast(1)
        val out = ShortArray(pcm.size * reps)
        for (r in 0 until reps) System.arraycopy(pcm, 0, out, r * pcm.size, pcm.size)
        return out
    }

    fun upsample(pcm: ShortArray, from: Int, to: Int): ShortArray {
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

    fun encodeAacMp4(pcm: ShortArray, sampleRate: Int, dest: File) {
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
