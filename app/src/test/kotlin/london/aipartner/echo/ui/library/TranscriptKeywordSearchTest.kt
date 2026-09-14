package london.aipartner.echo.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec for keyword-primary search — the property the semantic embedder could NOT provide: true
 * EXCLUSION. "tomato" must return the tomato clip and NOT the helicopter clip.
 */
class TranscriptKeywordSearchTest {

    private val texts = mapOf(
        "tomato" to "watching for the word tomato and if the search indexing works",
        "helicopter" to "searching for the World Helicopter, so if the world is there it should find it",
        "volcano" to "the quick brown pineapple jumped over the lazy volcano",
    )

    @Test fun singleWord_returnsOnlyTheMatch_excludesOthers() {
        val hits = TranscriptKeywordSearch.match("tomato", texts)
        assertEquals(setOf("tomato"), hits.keys)
        assertFalse("helicopter clip must be EXCLUDED, not just ranked lower", hits.containsKey("helicopter"))
    }

    @Test fun caseInsensitive() {
        assertEquals(setOf("helicopter"), TranscriptKeywordSearch.match("HELICOPTER", texts).keys)
        assertEquals(setOf("helicopter"), TranscriptKeywordSearch.match("Helicopter", texts).keys)
    }

    @Test fun wholeWordMatches() {
        // "pineapple" is a whole word in the volcano clip.
        assertEquals(setOf("volcano"), TranscriptKeywordSearch.match("pineapple", texts).keys)
    }

    @Test fun prefixMatchesWordStart_butNotMidWordFragment() {
        // THE bug this fixes: "he" must match only where "he" begins a word, never a fragment of
        // "the"/"there"/"when". helicopter clip has the word "Helicopter" (starts with "he") → match;
        // "world"/"searching" etc. never start with "he". tomato clip has NO word starting with "he".
        val hits = TranscriptKeywordSearch.match("he", texts)
        assertEquals("only word-start 'he' (Helicopter), not mid-word 'the'/'there'", setOf("helicopter"), hits.keys)
    }

    @Test fun midWordFragment_neverMatches() {
        // A transcript whose only words CONTAIN "he" as a fragment ("the", "there", "when") must NOT match.
        val onlyFragments = mapOf("frag" to "the cat was there when it happened")
        assertTrue("'he' inside the/there/when must not match", TranscriptKeywordSearch.match("he", onlyFragments).isEmpty())
    }

    @Test fun prefixMatch_incrementalTyping() {
        // "heli" should match "helicopter" (word starting with the typed prefix).
        assertEquals(setOf("helicopter"), TranscriptKeywordSearch.match("heli", texts).keys)
        // ...but "cano" is mid-word inside "volcano" (not a word start) → must NOT match.
        assertTrue("mid-word 'cano' in 'volcano' must not match", TranscriptKeywordSearch.match("cano", texts).isEmpty())
    }

    @Test fun wholeWordHe_stillMatches() {
        val hasWholeWordHe = mapOf("w" to "he said the volcano was loud")
        assertEquals(setOf("w"), TranscriptKeywordSearch.match("he", hasWholeWordHe).keys)
    }

    @Test fun unrelatedWord_returnsNothing() {
        // The exact failure of the semantic path: "aardvark" appears in NO clip → must return EMPTY,
        // not the whole library. (Semantic scored aardvark 0.75–0.85 against every clip.)
        assertTrue(TranscriptKeywordSearch.match("aardvark", texts).isEmpty())
    }

    @Test fun multiWord_requiresAllTermsPresent() {
        // Both terms in the helicopter clip.
        assertEquals(setOf("helicopter"), TranscriptKeywordSearch.match("world helicopter", texts).keys)
        // "tomato" is only in the tomato clip, "helicopter" only in the helicopter clip → no clip has both.
        assertTrue(TranscriptKeywordSearch.match("tomato helicopter", texts).isEmpty())
    }

    @Test fun blankQuery_returnsEmpty() {
        assertTrue(TranscriptKeywordSearch.match("", texts).isEmpty())
        assertTrue(TranscriptKeywordSearch.match("   ", texts).isEmpty())
    }

    @Test fun rankedByOccurrenceCount() {
        val docs = mapOf(
            "few" to "tomato once here",
            "many" to "tomato tomato tomato everywhere tomato",
        )
        val hits = TranscriptKeywordSearch.match("tomato", docs)
        assertEquals(setOf("few", "many"), hits.keys)
        assertTrue("more occurrences must score higher", hits.getValue("many") > hits.getValue("few"))
    }
}
