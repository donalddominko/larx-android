package london.aipartner.echo.core.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 4 — the on-device smart-title heuristic and the display-title precedence. */
class SmartTitleTest {

    // ── SmartTitle.fromTranscript ────────────────────────────────────────────────────────────

    @Test
    fun shortPhrase_fromFirstSentence() {
        // First sentence ends at "home." → title is that sentence (8 words, fits), no ellipsis,
        // and the trailing "Then call mum." is dropped.
        val title = SmartTitle.fromTranscript("Buy milk and eggs on the way home. Then call mum.")
        assertEquals("Buy milk and eggs on the way home", title)
    }

    @Test
    fun capitalizesFirstLetter() {
        val title = SmartTitle.fromTranscript("meeting notes")
        assertEquals("Meeting notes", title)
    }

    @Test
    fun shortWholeUtterance_returnedVerbatim_noEllipsis() {
        val title = SmartTitle.fromTranscript("Quick reminder")
        assertEquals("Quick reminder", title)
    }

    @Test
    fun longRunOn_clippedToWordsWithEllipsis() {
        val title = SmartTitle.fromTranscript(
            "so today I want to talk about the quarterly numbers and the plan for next year",
        )!!
        assertTrue("expected ellipsis on a long clip", title.endsWith("…"))
        assertTrue("expected at most 8 words", title.removeSuffix("…").trim().split(" ").size <= 8)
    }

    @Test
    fun empty_returnsNull() {
        assertNull(SmartTitle.fromTranscript(""))
        assertNull(SmartTitle.fromTranscript("   \n\t "))
    }

    @Test
    fun garbagePunctuationOnly_returnsNull() {
        assertNull(SmartTitle.fromTranscript("[ __ ] . . . - -"))
        assertNull(SmartTitle.fromTranscript("!?!?"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("Hello there", SmartTitle.fromTranscript("  hello    there  "))
    }

    // ── DisplayTitle.resolve — precedence (why a rename survives re-transcription) ────────────

    @Test
    fun resolve_manualTitleWins_evenWhenSmartChanges() {
        // A re-transcription replaces the smart title, but the manual one always wins →
        // the displayed name is stable across re-transcription.
        assertEquals("My Name", DisplayTitle.resolve(userTitle = "My Name", smartTitle = "smart v1"))
        assertEquals("My Name", DisplayTitle.resolve(userTitle = "My Name", smartTitle = "smart v2"))
    }

    @Test
    fun resolve_fallsBackToSmart_whenNoManual() {
        assertEquals("Smart", DisplayTitle.resolve(userTitle = null, smartTitle = "Smart"))
        assertEquals("Smart", DisplayTitle.resolve(userTitle = "  ", smartTitle = "Smart"))
    }

    @Test
    fun resolve_null_whenNeither() {
        assertNull(DisplayTitle.resolve(userTitle = null, smartTitle = null))
        assertNull(DisplayTitle.resolve(userTitle = "", smartTitle = "   "))
    }

    // ── DisplayTitle.shouldGenerateSmartTitle — smart title only when no manual ──────────────

    @Test
    fun shouldGenerateSmartTitle_onlyWhenNoManualTitle() {
        assertTrue(DisplayTitle.shouldGenerateSmartTitle(null))
        assertTrue(DisplayTitle.shouldGenerateSmartTitle("  "))
        assertTrue(!DisplayTitle.shouldGenerateSmartTitle("Manual name"))
    }
}
