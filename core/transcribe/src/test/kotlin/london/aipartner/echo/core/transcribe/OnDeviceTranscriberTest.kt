package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceTranscriberTest {

    private val anyAudio = AudioRef("/tmp/memo.m4a")

    private class FakeDecoder(val pcm: ShortArray) : PcmDecoder {
        override fun decodeToPcm16Mono16k(audio: AudioRef) = pcm
    }

    private class SpyEngine(val available: Boolean = true) : WhisperEngine {
        var invoked = false
        override fun isAvailable() = available
        override fun transcribe(pcm: ShortArray, modelPath: String, languageTag: String?) =
            run {
                invoked = true
                listOf(TranscriptSegment("hello", 0, 500))
            }
    }

    private class FakeProvisioner : ModelProvisioner {
        override fun isModelReady() = true
        override fun modelPathOrNull() = "/models/base.bin"
        override suspend fun ensureModel() = "/models/base.bin"
    }

    @Test fun silence_skipsEngine_andEmitsNoSpeechMarker_neverFabricates() = runTest {
        val engine = SpyEngine()
        val t = OnDeviceTranscriber(
            decoder = FakeDecoder(ShortArray(16_000)), // pure silence
            vad = EnergyVad(),
            engine = engine,
            modelProvisioner = FakeProvisioner(),
        )

        val result = t.transcribe(anyAudio, TranscribeOpts())

        assertFalse("engine MUST NOT be invoked on silence", engine.invoked)
        assertTrue(result.noSpeechDetected)
        assertTrue("no fabricated text", result.segments.isEmpty())
        assertEquals(Locus.ON_DEVICE, result.locus)
    }

    @Test fun speech_invokesEngine_andMapsSegments() = runTest {
        val engine = SpyEngine()
        val speech = ShortArray(16_000) { 8_000 } // strong constant energy
        val t = OnDeviceTranscriber(FakeDecoder(speech), EnergyVad(), engine, FakeProvisioner())

        val result = t.transcribe(anyAudio, TranscribeOpts())

        assertTrue(engine.invoked)
        assertFalse(result.noSpeechDetected)
        assertEquals(listOf("hello"), result.segments.map { it.text })
    }

    @Test(expected = TranscriptionEngineUnavailable::class)
    fun speech_butEngineUnavailable_failsHonestly() = runTest {
        val speech = ShortArray(16_000) { 8_000 }
        val t = OnDeviceTranscriber(
            FakeDecoder(speech), EnergyVad(), SpyEngine(available = false), FakeProvisioner(),
        )
        t.transcribe(anyAudio, TranscribeOpts())
    }
}
