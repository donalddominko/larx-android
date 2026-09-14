package london.aipartner.echo.core.consent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate is a pure function of RECORD_AUDIO. Phase 3 invariant: the disclosure
 * flag (and any other UI state) CANNOT gate the verdict — structurally, the gate
 * has no input for it. These tests pin the verdict to permission alone.
 */
class RecordAudioConsentGateTest {

    private val gate = RecordAudioConsentGate()

    @Test
    fun deniesWithoutRecordAudio() {
        val verdict = gate.evaluate(
            RecordRequest(CaptureMode.ON_DEMAND, hasRecordAudioPermission = false),
        )
        assertEquals(ConsentVerdict.DENY, verdict)
    }

    @Test
    fun allowsWithRecordAudio() {
        val verdict = gate.evaluate(
            RecordRequest(CaptureMode.ON_DEMAND, hasRecordAudioPermission = true),
        )
        assertEquals(ConsentVerdict.ALLOW, verdict)
    }
}
