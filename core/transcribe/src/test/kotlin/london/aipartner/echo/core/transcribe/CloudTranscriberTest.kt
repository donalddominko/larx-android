package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.test.runTest
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTranscriberTest {

    private val anyAudio = AudioRef("/tmp/memo.m4a")

    private class FakeEntitlements(val pro: Boolean) : Entitlements {
        override fun has(feature: Feature) = pro && feature == Feature.CLOUD_TRANSCRIPTION
    }

    /** Fails the test if its audio is ever touched — proves no upload before gates. */
    private class TripwireProvider : AsrProvider {
        var called = false
        override suspend fun transcribe(audio: AudioRef, opts: TranscribeOpts): List<TranscriptSegment> {
            called = true
            return listOf(TranscriptSegment("cloud", 0, 100))
        }
    }

    private fun cloud(pro: Boolean, egress: Boolean, provider: AsrProvider) =
        CloudTranscriber(FakeEntitlements(pro), EgressConsent { egress }, provider)

    @Test fun refuses_whenNeitherGateOpen_andNeverReadsAudio() = runTest {
        val p = TripwireProvider()
        assertRefused { cloud(pro = false, egress = false, p).transcribe(anyAudio, TranscribeOpts()) }
        assertFalse("audio must not be read without gates", p.called)
    }

    @Test fun refuses_withProButNoEgress() = runTest {
        val p = TripwireProvider()
        assertRefused { cloud(pro = true, egress = false, p).transcribe(anyAudio, TranscribeOpts()) }
        assertFalse(p.called)
    }

    @Test fun refuses_withEgressButNoPro() = runTest {
        val p = TripwireProvider()
        assertRefused { cloud(pro = false, egress = true, p).transcribe(anyAudio, TranscribeOpts()) }
        assertFalse(p.called)
    }

    @Test fun permits_whenBothGatesOpen() = runTest {
        val p = TripwireProvider()
        val result = cloud(pro = true, egress = true, p).transcribe(anyAudio, TranscribeOpts())
        assertTrue(p.called)
        assertEquals(Locus.CLOUD, result.locus)
        assertEquals(listOf("cloud"), result.segments.map { it.text })
    }

    @Test fun gatesOpen_reflectsBothConditions() {
        assertFalse(cloud(false, false, TripwireProvider()).gatesOpen())
        assertFalse(cloud(true, false, TripwireProvider()).gatesOpen())
        assertFalse(cloud(false, true, TripwireProvider()).gatesOpen())
        assertTrue(cloud(true, true, TripwireProvider()).gatesOpen())
    }

    private inline fun assertRefused(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected CloudTranscriptionRefused")
        } catch (_: CloudTranscriptionRefused) {
            // expected
        }
    }
}
