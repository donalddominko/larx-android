package london.aipartner.echo.ui.library

/**
 * Keyword-primary library search (2026-07-21, Donald). Replaces semantic embeddings as the search
 * FILTER because the whole-document embedder can't be thresholded — measured cosines bunch into
 * ~0.72–0.91 with an UNRELATED control word out-scoring the correct match, so no cutoff separates
 * relevant from irrelevant (it only ever reorders the full set, never excludes). For a voice
 * recorder, people search for words they remember SAYING, which exact keyword match does perfectly
 * and offline — and it removes search's dependency on the fragile MediaPipe embedder entirely.
 *
 * Pure + deterministic (JVM-unit-tested): case-insensitive, **word-boundary + prefix** matching.
 * The transcript and the query are tokenized into words (punctuation stripped); a query term matches
 * only when some transcript word STARTS WITH it. So "he" matches the word "he" and "helicopter"
 * (a word starting with "he") but NOT "the"/"there"/"when" (where "he" is a mid-word fragment) —
 * the confusing-substring bug. "heli" → "helicopter". A recording matches only if EVERY query term
 * prefix-matches some word (AND). Ranked by total prefix-match count so the most-relevant clip leads;
 * the caller applies the scores as a stable re-sort over its newest-first list, so equal scores keep
 * newest-first.
 */
object TranscriptKeywordSearch {

    /**
     * @param query the raw search string.
     * @param texts recordingId -> full latest-revision transcript text.
     * @return recordingId -> relevance score, for MATCHING recordings only (every term prefix-matches
     *         some word). A blank/punctuation-only query yields an empty map (caller shows the full
     *         unfiltered list instead).
     */
    fun match(query: String, texts: Map<String, String>): Map<String, Int> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for ((id, text) in texts) {
            val words = tokenize(text)
            // Every query term must be the PREFIX of at least one transcript word (word-boundary match).
            if (terms.all { term -> words.any { it.startsWith(term) } }) {
                result[id] = terms.sumOf { term -> words.count { it.startsWith(term) } }
            }
        }
        return result
    }

    /** Lowercase, then split into word tokens on any non-letter/digit (punctuation, whitespace). */
    private fun tokenize(s: String): List<String> =
        WORD.findAll(s.lowercase()).map { it.value }.toList()

    /** A run of Unicode letters/digits — one "word". Apostrophes/hyphens/punctuation are separators. */
    private val WORD = Regex("[\\p{L}\\p{N}]+")
}
