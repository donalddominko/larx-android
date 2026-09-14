package london.aipartner.echo.core.consent

/**
 * Seam 2 — WHETHER recording is permitted. A deterministic permission-to-record
 * verdict; no LLM/model decides consent. The recorder cannot start on
 * [ConsentVerdict.DENY], and there is no bypass path — every entry to capture
 * routes through here.
 *
 * Echo is a self-recording voice/memo app, so the consent model is light: the
 * gate's job is chiefly to enforce that RECORD_AUDIO is granted. The full
 * jurisdiction/all-party/notice machinery a call recorder needs does not apply
 * (call recording was removed). Phase 3 fills in the audit trail + the one-time
 * "you're responsible for recording others" disclosure.
 */
interface ConsentGate {
    fun evaluate(req: RecordRequest): ConsentVerdict
}

enum class ConsentVerdict { ALLOW, DENY }

/** v1 records on demand only. A future VOX/voice-activated mode would add here. */
enum class CaptureMode { ON_DEMAND }

/** Inputs to a consent decision. */
data class RecordRequest(
    val captureMode: CaptureMode,
    val hasRecordAudioPermission: Boolean,
)
