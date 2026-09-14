package london.aipartner.echo.core.consent

/**
 * The real (Phase 3) ConsentGate for a self-recording voice/memo app. The model
 * is deliberately light: the only thing the gate enforces is that the runtime
 * RECORD_AUDIO permission is held. It is a PURE FUNCTION of [RecordRequest] — it
 * does NOT consider the first-run disclosure flag, app settings, or any other
 * state, so nothing UI-side can either block or fake a verdict. The disclosure is
 * a separate, acknowledge-only UI surface; it never feeds this gate.
 *
 * This is the single chokepoint before capture (no bypass). A future kill-switch
 * or storage-full check would live here, still as a pure verdict.
 */
class RecordAudioConsentGate : ConsentGate {
    override fun evaluate(req: RecordRequest): ConsentVerdict =
        if (req.hasRecordAudioPermission) ConsentVerdict.ALLOW else ConsentVerdict.DENY
}
