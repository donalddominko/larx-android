package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.test.runTest
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 gate — the two BINDING checks Donald called out, framed as the analogs
 * of Phase 2's forced-DENY-produces-no-file and Phase 4's whisper-on-silence:
 *
 *  1. **Double-gate egress proof:** the cloud generative engine is physically
 *     unreachable without BOTH Pro AND egress consent, and NO transcript text is
 *     read/handed to the provider until both pass (tripwire provider asserts zero
 *     calls — the analog of "no file written").
 *  2. **Honest degradation:** a failed/empty/timed-out call yields no artifact
 *     (Failed), an empty transcript yields NothingToGenerate — never a fabricated
 *     summary.
 */
class CloudAiEngineTest {

    private val input = AiInput(recordingId = "rec1", transcriptText = "we agreed to ship friday")

    private class FakeEntitlements(val pro: Boolean) : Entitlements {
        override fun has(feature: Feature) = pro && feature == Feature.AI_SUMMARY
    }

    /** Fails the test if its text is ever touched — proves no upload before gates. */
    private class TripwireProvider : LlmProvider {
        var called = false
        override val model = "tripwire-1"
        override suspend fun generate(kind: AiKind, transcriptText: String, languageTag: String?): String {
            called = true
            return "summary"
        }
    }

    private fun engine(pro: Boolean, egress: Boolean, provider: LlmProvider) =
        CloudAiEngine(FakeEntitlements(pro), EgressConsent { egress }, provider)

    // --- 1. Double-gate egress proof (the chokepoint holds before any data crosses) ---

    @Test fun refuses_whenNeitherGateOpen_andNeverReadsTranscript() = runTest {
        val p = TripwireProvider()
        assertRefused { engine(pro = false, egress = false, p).generate(input, AiKind.SUMMARY) }
        assertFalse("transcript must not be sent without gates", p.called)
    }

    @Test fun refuses_withProButNoEgress() = runTest {
        val p = TripwireProvider()
        assertRefused { engine(pro = true, egress = false, p).generate(input, AiKind.SUMMARY) }
        assertFalse(p.called)
    }

    @Test fun refuses_withEgressButNoPro() = runTest {
        val p = TripwireProvider()
        assertRefused { engine(pro = false, egress = true, p).generate(input, AiKind.SUMMARY) }
        assertFalse(p.called)
    }

    @Test fun gatesOpen_reflectsBothConditions() {
        assertFalse(engine(false, false, TripwireProvider()).gatesOpen())
        assertFalse(engine(true, false, TripwireProvider()).gatesOpen())
        assertFalse(engine(false, true, TripwireProvider()).gatesOpen())
        assertTrue(engine(true, true, TripwireProvider()).gatesOpen())
    }

    @Test fun permits_andRecordsProvenance_whenBothGatesOpen() = runTest {
        val p = TripwireProvider()
        val result = engine(pro = true, egress = true, p).generate(input, AiKind.SUMMARY)
        assertTrue(p.called)
        result as AiArtifactResult.Generated
        assertEquals(AiKind.SUMMARY, result.kind)
        assertEquals("summary", result.content)
        assertEquals("tripwire-1", result.model)
    }

    // --- 2. Honest degradation (no fabrication on empty / failure) ---

    @Test fun emptyTranscript_yieldsNothingToGenerate_neverInvents() = runTest {
        val p = TripwireProvider()
        val result = engine(pro = true, egress = true, p)
            .generate(input.copy(transcriptText = "   "), AiKind.SUMMARY)
        assertEquals(AiArtifactResult.NothingToGenerate, result)
        assertFalse("must not call provider for empty input", p.called)
    }

    @Test fun providerError_yieldsFailed_notFabricated() = runTest {
        val throwing = object : LlmProvider {
            override val model = "boom"
            override suspend fun generate(kind: AiKind, transcriptText: String, languageTag: String?): String =
                throw RuntimeException("network down")
        }
        val result = CloudAiEngine(FakeEntitlements(true), EgressConsent { true }, throwing)
            .generate(input, AiKind.SUMMARY)
        assertTrue(result is AiArtifactResult.Failed)
    }

    @Test fun providerBlankResult_yieldsFailed_notEmptyArtifact() = runTest {
        val blank = object : LlmProvider {
            override val model = "blank"
            override suspend fun generate(kind: AiKind, transcriptText: String, languageTag: String?): String? = "  "
        }
        val result = CloudAiEngine(FakeEntitlements(true), EgressConsent { true }, blank)
            .generate(input, AiKind.SUMMARY)
        assertTrue(result is AiArtifactResult.Failed)
    }

    private inline fun assertRefused(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected AiGenerationRefused")
        } catch (_: AiGenerationRefused) {
            // expected
        }
    }
}
