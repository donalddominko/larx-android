package london.aipartner.echo.ui.detail

import london.aipartner.echo.transcribe.MisdecodeGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7 gate — the mis-decode guard (Layer 3 + the Layer-2 trigger). It must FIRE on the two
 * observed mismatch signatures — (1) a substantial phrase looped across advancing segments (the
 * 2026-07-01 Slovenian-on-`en` incident), and (2) whisper's foreign-speech annotation
 * `(speaks in foreign language)` (Donald's real 2026-07-02 recording) — and must NOT fire on
 * legitimate transcripts, including legitimately repetitive speech (chants) and legitimate
 * non-speech annotations whisper also emits (music/applause/laughter/silence). Conservative by
 * design: when unsure, stay silent rather than wrongly flag a real transcript.
 */
class MisdecodeGuardTest {

    /** Build a segment-text list from a list of phrases (timestamps are inherent/ordered). */
    private fun transcript(vararg phrases: String): List<String> = phrases.toList()

    // The combined Layer-2 trigger (either signal) — what the app actually calls.
    private fun fires(segmentTexts: List<String>) =
        MisdecodeGuard.suspectedMisdecode(segmentTexts)

    // ── FIRES: the incident signature ──────────────────────────────────────────

    @Test fun theIncident_onePhraseLoopedAcrossEightSegments_fires() {
        // Verbatim shape of the Slovenian-on-en incident: one confident English phrase
        // repeated across 8 advancing segments.
        val looped = "I was very happy to see how the record was going"
        assertTrue(fires(transcript(*Array(8) { looped })))
    }

    @Test fun nearIdentical_minorPunctuationAndCaseDrift_stillFires() {
        val segments = transcript(
            "I was very happy to see how the record was going",
            "I was very happy to see how the record was going.",
            "i was very happy to see how the record was going",
            "I was very happy, to see how the record was going",
            "I was very happy to see how the record was going",
        )
        assertTrue(fires(segments))
    }

    @Test fun loopDominatesButNotEverySegment_fires() {
        // Real loops often have a stray different line; dominance (not purity) is the test.
        val loop = "the meeting will continue after the short break"
        val segments = transcript(loop, loop, loop, loop, "and then we finished", loop)
        assertTrue(fires(segments))
    }

    // ── DOES NOT FIRE: legitimate transcripts ──────────────────────────────────

    @Test fun normalVariedTranscript_doesNotFire() {
        val segments = transcript(
            "Let's start with the budget numbers.",
            "Marketing is up twelve percent this quarter.",
            "We should revisit the hiring plan next week.",
            "Any questions before we wrap up?",
            "Thanks everyone, talk soon.",
        )
        assertFalse(fires(segments))
    }

    @Test fun legitShortRepetition_chantOrCountIn_doesNotFire() {
        // Short repeated words are NOT the failure signature (MIN_PHRASE_CHARS guards this).
        assertFalse(fires(transcript("yes", "yes", "yes", "yes", "yes")))
        assertFalse(fires(transcript("one", "two", "three", "four", "go", "go", "go")))
    }

    @Test fun emptyOrTiny_doesNotFire() {
        assertFalse(fires(emptyList()))
        assertFalse(fires(transcript("a single line")))
        // Below MIN_SEGMENTS even if repeated — too little signal.
        assertFalse(fires(transcript("repeated substantial phrase here", "repeated substantial phrase here")))
    }

    @Test fun blankSegmentsIgnored_notCountedAsRepeats() {
        // Blank lines shouldn't cluster into a false "loop".
        assertFalse(fires(transcript("", "  ", "", "the only real line of speech here", "")))
    }

    // ── FIRES: foreign-speech annotation (signal 2) ─────────────────────────────

    @Test fun theRealRecording_exactForeignAnnotation_fires() {
        // Verbatim output of Donald's real Slovenian-on-en recording.
        assertTrue(fires(transcript("(speaks in foreign language)")))
    }

    @Test fun foreignAnnotation_casingAndWordingVariants_fire() {
        assertTrue(fires(transcript("[Speaking in foreign language]")))
        assertTrue(fires(transcript("(SPEAKS IN FOREIGN LANGUAGE)")))
        assertTrue(fires(transcript("(foreign language)")))
        assertTrue(fires(transcript("(non-English speech)")))
        // Dominated across multiple segments.
        assertTrue(fires(transcript("(speaks in foreign language)", "(speaks in foreign language)")))
    }

    @Test fun foreignAnnotation_dominatesAmongSegments_fires() {
        // 2 of 2 non-blank are foreign annotations (the real recording was segments=2).
        assertTrue(fires(transcript("(speaks in foreign language)", "  ", "(foreign language)")))
    }

    // ── DOES NOT FIRE: legit non-speech annotations + incidental foreign mention ──

    @Test fun legitNonSpeechAnnotations_doNotFire() {
        assertFalse(fires(transcript("(music)")))
        assertFalse(fires(transcript("[applause]")))
        assertFalse(fires(transcript("(laughter)")))
        assertFalse(fires(transcript("(silence)")))
        assertFalse(fires(transcript("(music)", "[applause]", "(laughter)")))
    }

    @Test fun realSentenceWithOneIncidentalForeignTag_doesNotFire() {
        // A real transcript that merely mentions "foreign" or has one stray tag must not trip it
        // (domination, not presence). None of these are dominated by a foreign annotation.
        assertFalse(fires(transcript("We discussed the foreign exchange markets at length today.")))
        assertFalse(
            fires(
                transcript(
                    "Welcome back to the show.",
                    "(music)",
                    "Today we cover foreign policy in depth.",
                    "That's all for now, thanks for listening.",
                ),
            ),
        )
    }

    @Test fun foreignWordInsideRealSentence_notAnnotationSegment_doesNotFire() {
        // The bracketed group is foreign but the segment has substantial other speech → not an
        // annotation segment, so it doesn't dominate.
        assertFalse(fires(transcript("She said (something in a foreign tongue) and then left the room quietly.")))
    }
}
