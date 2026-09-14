package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.test.runTest
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriberRouterTest {

    private val anyAudio = AudioRef("/tmp/memo.m4a")

    private class FakeEntitlements(val pro: Boolean) : Entitlements {
        override fun has(feature: Feature) = pro && feature == Feature.CLOUD_TRANSCRIPTION
    }

    private fun onDevice() = OnDeviceTranscriber(
        decoder = { ShortArray(16_000) { 8_000 } }.let { gen ->
            object : PcmDecoder {
                override fun decodeToPcm16Mono16k(audio: AudioRef) = gen()
            }
        },
        vad = EnergyVad(),
        engine = object : WhisperEngine {
            override fun isAvailable() = true
            override fun transcribe(pcm: ShortArray, modelPath: String, languageTag: String?) =
                listOf(TranscriptSegment("device", 0, 100))
        },
        modelProvisioner = object : ModelProvisioner {
            override fun isModelReady() = true
            override fun modelPathOrNull() = "/m"
            override suspend fun ensureModel() = "/m"
        },
    )

    private fun cloud(pro: Boolean, egress: Boolean) = CloudTranscriber(
        FakeEntitlements(pro), EgressConsent { egress },
        object : AsrProvider {
            override suspend fun transcribe(audio: AudioRef, opts: TranscribeOpts) =
                listOf(TranscriptSegment("cloud", 0, 100))
        },
    )

    @Test fun routesToOnDevice_byDefault() = runTest {
        val router = TranscriberRouter(onDevice(), cloud(pro = false, egress = false))
        assertEquals(Locus.ON_DEVICE, router.locus)
        assertEquals(listOf("device"), router.transcribe(anyAudio, TranscribeOpts()).segments.map { it.text })
    }

    @Test fun routesToCloud_onlyWhenBothGatesOpen() = runTest {
        val router = TranscriberRouter(onDevice(), cloud(pro = true, egress = true))
        assertEquals(Locus.CLOUD, router.locus)
        assertEquals(listOf("cloud"), router.transcribe(anyAudio, TranscribeOpts()).segments.map { it.text })
    }

    @Test fun staysOnDevice_withProButNoEgress() = runTest {
        val router = TranscriberRouter(onDevice(), cloud(pro = true, egress = false))
        assertEquals(Locus.ON_DEVICE, router.locus)
    }
}
