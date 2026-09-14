package london.aipartner.echo.core.transcribe

/**
 * On-device, **deterministic** smart-title heuristic (item 4). Turns a finished transcript into a
 * short human title — the first salient phrase — with NO network and NO LLM.
 *
 * This is deliberately NOT the cloud generative TITLE of [AiEngine] (which is Pro, double-gated,
 * off by default). v1 is free-only, so the library needs an honest free name for every recording;
 * a pure phrase-extraction heuristic gives one at zero marginal cost and zero egress. It is stored
 * as the same regenerable TITLE `AiArtifact` (model = [MODEL]) so provenance is distinguishable and
 * a later cloud title cleanly replaces it.
 *
 * Honesty rule (mirrors the rest of the pipeline): produce a title only when there is faithful
 * speech to draw from. Empty, whitespace-only, or garbage-looking transcripts return **null** — the
 * caller then falls back to the date+time default, never a fabricated or nonsense name.
 */
object SmartTitle {
    /** Provenance recorded on the TITLE artifact so a cloud/LLM title is distinguishable. */
    const val MODEL = "on-device-heuristic"

    private const val MAX_WORDS = 8
    private const val MAX_CHARS = 60
    private const val MIN_CHARS = 3

    /**
     * A short title from [transcriptText], or null when there's nothing faithful to name.
     * Takes the first sentence (or the first [MAX_WORDS] words), trims to [MAX_CHARS] with an
     * ellipsis, and capitalizes the first letter. Rejects blank input and input with too little
     * real (letter/digit) content to be a meaningful title.
     */
    fun fromTranscript(transcriptText: String): String? {
        val normalized = transcriptText.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return null

        // Reject garbage: a title needs some alphanumeric substance. Guards against a transcript
        // that is only punctuation / stray symbols (e.g. a mis-decode's "[__] . . .").
        val alnum = normalized.count { it.isLetterOrDigit() }
        if (alnum < MIN_CHARS) return null

        // First sentence, if one ends early; otherwise the whole (normalized) text.
        val sentenceEnd = normalized.indexOfFirst { it == '.' || it == '?' || it == '!' }
        val firstSentence =
            if (sentenceEnd in 0 until normalized.length - 1) normalized.substring(0, sentenceEnd)
            else normalized

        val words = firstSentence.split(' ').filter { it.isNotBlank() }
        val clipped = words.take(MAX_WORDS).joinToString(" ")
        val truncatedByWords = words.size > MAX_WORDS

        var title = clipped
        var truncated = truncatedByWords
        if (title.length > MAX_CHARS) {
            title = title.take(MAX_CHARS).trimEnd()
            truncated = true
        }
        title = title.trimEnd { it == ',' || it == ';' || it == ':' || it == '-' }.trim()
        if (title.count { it.isLetterOrDigit() } < MIN_CHARS) return null

        val cased = title.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return if (truncated) "$cased…" else cased
    }
}

/**
 * Pure display-title precedence (item 4). A manual rename ([userTitle]) ALWAYS wins and is why a
 * rename survives re-transcription — re-transcription only replaces the smart TITLE artifact, never
 * the recording row's user title. A null result means "no name yet" → the UI renders the date+time
 * default (formatting is a locale/UI concern, kept out of this pure function).
 */
object DisplayTitle {
    fun resolve(userTitle: String?, smartTitle: String?): String? =
        userTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: smartTitle?.trim()?.takeIf { it.isNotEmpty() }

    /** A smart title is generated only when the user has NOT set a manual one (provenance). */
    fun shouldGenerateSmartTitle(userTitle: String?): Boolean = userTitle.isNullOrBlank()
}
