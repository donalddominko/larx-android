package london.aipartner.echo.transcribe

/**
 * Detects the *signatures* of a whisper mis-decode on a language mismatch — the honest v1
 * backstop for the mismatched-language user, and the TRIGGER for Layer 2's language-detect pass.
 * Pure, post-hoc, no model run.
 *
 * TWO signals (learned on-device 2026-07-02 that a mismatch surfaces in more than one shape):
 *  1. **Repetition loop** — the model gets stuck emitting one substantial phrase across many
 *     segments (the original Slovenian-on-`en` incident: one English phrase × 8 segments).
 *  2. **Foreign-speech annotation** — the model instead emits its own non-lexical marker, e.g.
 *     `(speaks in foreign language)` (the exact output of Donald's real Slovenian recording).
 *
 * Either signal → a suspected mis-decode ([suspectedMisdecode]). Both are deliberately
 * CONSERVATIVE and domination-based: legitimate repetitive speech (chants) and legitimate
 * non-speech annotations whisper also emits — `(music)`, `[applause]`, `(laughter)`, `(silence)`
 * — must NOT trip the warning. When unsure, stay silent. It is a SOFT warn, never a hard verdict.
 */
object MisdecodeGuard {

    // ── Repetition-loop signal ──────────────────────────────────────────────────

    /** Don't judge very short transcripts — too little signal, too easy to false-positive. */
    private const val MIN_SEGMENTS = 4

    /** The dominant repeated phrase must recur at least this many times. */
    private const val MIN_REPEATS = 4

    /** …and cover at least this fraction of all (non-blank) segments. */
    private const val DOMINANCE = 0.6

    /** …and be a substantial phrase, not a short word/interjection (guards legit chants). */
    private const val MIN_PHRASE_CHARS = 15

    /** Two normalized phrases count as "the same loop" at/above this char-similarity. */
    private const val SIMILARITY = 0.85

    // ── Foreign-annotation signal ───────────────────────────────────────────────

    /** Foreign-annotation segments must make up at least this fraction (domination, not mere
     *  presence) — so a real sentence with one incidental "(music)"-style tag can't trip it. */
    private const val FOREIGN_DOMINANCE = 0.5

    /** After removing the bracketed annotation, an annotation segment has ~no other words left. */
    private const val ANNOTATION_REMAINDER_MAX_CHARS = 4

    /** Explicit sentinels NOT caught by the "foreign" keyword (e.g. "(non-English speech)"). */
    private val FOREIGN_SENTINELS = listOf("non-english", "non english")

    /** A bracketed/parenthetical group: `(…)` or `[…]`. */
    private val ANNOTATION = Regex("[\\(\\[]([^)\\]]*)[)\\]]")

    /**
     * The single Layer-2 TRIGGER: the transcript looks mis-decoded by EITHER signal. Callers
     * (the post-processor's on-hit detect and the detail VM's warning) use this one entry point.
     */
    fun suspectedMisdecode(segmentTexts: List<String>): Boolean =
        looksLikeRepetitionLoop(segmentTexts) || looksLikeForeignSpeechAnnotation(segmentTexts)

    /** Signal 1 — a substantial phrase looping across dominating, advancing segments. */
    fun looksLikeRepetitionLoop(segmentTexts: List<String>): Boolean {
        val phrases = segmentTexts.map { normalize(it) }.filter { it.isNotBlank() }
        if (phrases.size < MIN_SEGMENTS) return false

        // Greedy near-identity clustering: each phrase joins the first cluster it's similar to.
        val clusters = mutableListOf<MutableList<String>>()
        for (phrase in phrases) {
            val cluster = clusters.firstOrNull { similarity(it.first(), phrase) >= SIMILARITY }
            if (cluster != null) cluster.add(phrase) else clusters.add(mutableListOf(phrase))
        }

        val largest = clusters.maxByOrNull { it.size } ?: return false
        val dominates = largest.size >= MIN_REPEATS &&
            largest.size.toDouble() / phrases.size >= DOMINANCE
        val substantial = largest.first().length >= MIN_PHRASE_CHARS
        return dominates && substantial
    }

    /**
     * Signal 2 — the transcript is DOMINATED by whisper's foreign-speech annotation
     * (`(speaks in foreign language)` and casing/wording variants). Matches "foreign" inside a
     * bracketed group + a short sentinel list, case-insensitively. Fires on domination, not mere
     * presence, and only when the annotation is essentially the whole segment (so a real sentence
     * that merely contains a bracketed foreign word isn't flagged).
     */
    fun looksLikeForeignSpeechAnnotation(segmentTexts: List<String>): Boolean {
        val nonBlank = segmentTexts.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return false
        val foreign = nonBlank.count { isForeignAnnotationSegment(it) }
        return foreign > 0 && foreign.toDouble() / nonBlank.size >= FOREIGN_DOMINANCE
    }

    /** True if [segment] is essentially just a foreign-speech annotation. */
    private fun isForeignAnnotationSegment(segment: String): Boolean {
        val groups = ANNOTATION.findAll(segment).map { it.groupValues[1] }.toList()
        val hasForeignGroup = groups.any { g ->
            g.contains("foreign", ignoreCase = true) ||
                FOREIGN_SENTINELS.any { g.contains(it, ignoreCase = true) }
        }
        if (!hasForeignGroup) return false
        // The annotation must dominate the segment — almost no other spoken words remain.
        val remainder = segment.replace(ANNOTATION, " ")
            .replace(Regex("[^\\p{L}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return remainder.length <= ANNOTATION_REMAINDER_MAX_CHARS
    }

    // ── shared helpers ──────────────────────────────────────────────────────────

    /** Lowercase, strip punctuation, collapse whitespace — compare meaning, not formatting. */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Char-level similarity in [0,1] = 1 − levenshtein/maxLen. Cheap for short phrases. */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return prev[b.length]
    }
}
