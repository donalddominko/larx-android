package london.aipartner.echo.core.transcribe

/**
 * Placeholder generative provider until a concrete one is wired (and only after
 * Donald confirms any paid-API spend — CLAUDE.md §2). It is unreachable in normal
 * operation: the [CloudAiEngine] double gate (Pro + egress) is closed by default,
 * so [generate] is never called. If it ever is, it fails loudly rather than
 * silently fabricating a summary. Mirrors [NotConfiguredAsrProvider].
 */
class NotConfiguredLlmProvider : LlmProvider {
    override val model = "not-configured"

    override suspend fun generate(
        kind: AiKind,
        transcriptText: String,
        languageTag: String?,
    ): String? =
        throw NotImplementedError("No cloud LLM provider configured yet (Phase 9 / Pro).")
}
