package london.aipartner.echo.core.transcribe

/**
 * Placeholder cloud ASR provider until a concrete one is wired (and only after
 * Donald confirms any paid-API spend — CLAUDE.md §2). It is unreachable in normal
 * operation: the [CloudTranscriber] double gate (Pro + egress) is closed by
 * default, so [transcribe] is never called. If it ever is, it fails loudly rather
 * than silently pretending to transcribe.
 */
class NotConfiguredAsrProvider : AsrProvider {
    override suspend fun transcribe(
        audio: AudioRef,
        opts: TranscribeOpts,
    ): List<TranscriptSegment> =
        throw NotImplementedError("No cloud ASR provider configured yet (Phase 9 / Pro).")
}
