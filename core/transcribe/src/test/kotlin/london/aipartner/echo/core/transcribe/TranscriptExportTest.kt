package london.aipartner.echo.core.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure [TranscriptExport] renderer — both styles, both formats, the
 * timestamp edge cases, the honesty no-speech contract, and filename sanitization. No Android.
 */
class TranscriptExportTest {

    private fun seg(text: String, start: Long, end: Long, speaker: String? = null) =
        TranscriptSegment(text = text, tStartMs = start, tEndMs = end, speaker = speaker)

    private val two = listOf(
        seg("Hello there.", 0, 1500),
        seg("How are you?", 1500, 3000),
    )

    // ── timestamp() edge cases ─────────────────────────────────────────────
    @Test fun timestamp_zero() = assertEquals("[00:00]", TranscriptExport.timestamp(0))
    @Test fun timestamp_subMinute() = assertEquals("[00:07]", TranscriptExport.timestamp(7_000))
    @Test fun timestamp_minutes() = assertEquals("[01:23]", TranscriptExport.timestamp(83_000))
    @Test fun timestamp_justUnderHour() = assertEquals("[59:59]", TranscriptExport.timestamp(3_599_000))
    @Test fun timestamp_exactlyOneHour() = assertEquals("[1:00:00]", TranscriptExport.timestamp(3_600_000))
    @Test fun timestamp_overHour() = assertEquals("[2:05:09]", TranscriptExport.timestamp(7_509_000))
    @Test fun timestamp_negativeClampsToZero() = assertEquals("[00:00]", TranscriptExport.timestamp(-5))

    // ── TIMESTAMPED, both formats ──────────────────────────────────────────
    @Test fun timestamped_txt() {
        val out = TranscriptExport.format(
            "My Memo", "12 Sep 2026 · 0:03", two, ExportStyle.TIMESTAMPED, ExportFormat.TXT,
        )
        assertEquals(
            "My Memo\n12 Sep 2026 · 0:03\n\n[00:00] Hello there.\n[00:01] How are you?\n",
            out,
        )
    }

    @Test fun timestamped_markdown_hasHeadingAndBoldStamps() {
        val out = TranscriptExport.format(
            "My Memo", "12 Sep 2026 · 0:03", two, ExportStyle.TIMESTAMPED, ExportFormat.MARKDOWN,
        )
        assertTrue(out.startsWith("# My Memo\n\n12 Sep 2026 · 0:03\n\n"))
        assertTrue(out.contains("**[00:00]** Hello there."))
        assertTrue(out.contains("**[00:01]** How are you?"))
    }

    @Test fun timestamped_includesSpeaker() {
        val out = TranscriptExport.format(
            "T", "d", listOf(seg("Hi", 0, 1000, speaker = "Alice")),
            ExportStyle.TIMESTAMPED, ExportFormat.TXT,
        )
        assertTrue(out.contains("[00:00] Alice: Hi"))
    }

    // ── PLAIN, both formats ────────────────────────────────────────────────
    @Test fun plain_txt_hasNoTimestamps() {
        val out = TranscriptExport.format(
            "My Memo", "d", two, ExportStyle.PLAIN, ExportFormat.TXT,
        )
        assertFalse(out.contains("[00:00]"))
        assertFalse(out.contains("[00:01]"))
        // Small gap (0ms) → one paragraph.
        assertEquals("My Memo\nd\n\nHello there. How are you?\n", out)
    }

    @Test fun plain_markdown_hasHeading() {
        val out = TranscriptExport.format(
            "My Memo", "d", two, ExportStyle.PLAIN, ExportFormat.MARKDOWN,
        )
        assertTrue(out.startsWith("# My Memo\n\nd\n\n"))
        assertFalse(out.contains("**["))
    }

    /** Split the PLAIN body of [out] (after the "d\n\n" header) into paragraphs. */
    private fun plainParagraphs(out: String): List<String> =
        out.substringAfter("d\n\n").trimEnd('\n').split("\n\n")

    /**
     * Realistic PLAIN paragraphing on the ACTUAL A03 transcript, modeled with real whisper
     * timings: segments are CONTIGUOUS (t1[i] == t0[i+1], gap = 0) — the very reason gap-based
     * breaking failed. Breaking is now by sentence boundary + [PARAGRAPH_MIN_CHARS].
     *
     * Asserts the new shape reads sensibly: two paragraphs, each ending on a sentence boundary,
     * the break falling at the FIRST sentence end PAST the length target — not at the earlier
     * sentence ends below it, and not "somewhere".
     */
    @Test fun plain_realTranscript_sentenceBoundaryParagraphs() {
        // Contiguous timings: each end equals the next start (whisper's construction).
        val segments = listOf(
            seg("So now I am using this to test whether or not I can copy transcript and if the transcript", 0, 9_000),
            seg("is good I will copy it.", 9_000, 11_000),
            seg("If I cannot copy transcript then I will not copy it.", 11_000, 16_000),
            seg("Now I will make it to second pause and now I will speak again and later on I will make", 16_000, 24_000),
            seg("another pause and we will see if then anything breaks when it copy-paste.", 24_000, 31_000),
            seg("Hopefully it should not but if it does we will know soon.", 31_000, 39_000),
            seg("Then again who knows you know two seconds might not be enough you know we will see.", 39_000, 47_000),
            seg("So here we are we are almost at one minute and it's gonna take forever to transcribe so", 47_000, 53_000),
            seg("I am just gonna stop now and let's see what we end up with.", 53_000, 59_000),
        )
        val paragraphs = plainParagraphs(
            TranscriptExport.format("Test", "d", segments, ExportStyle.PLAIN, ExportFormat.TXT),
        )

        assertEquals(2, paragraphs.size)
        // Each paragraph ends on a sentence boundary.
        paragraphs.forEach { assertTrue("paragraph must end a sentence: $it", it.trimEnd().last() in ".?!") }
        // Break falls at the first sentence end past the target, not at the earlier ones below it.
        assertTrue(paragraphs[0].length >= TranscriptExport.PARAGRAPH_MIN_CHARS)
        assertTrue(paragraphs[0].startsWith("So now I am using"))
        // The two sub-240 sentence ends stayed joined inside paragraph 1 (no premature break).
        assertTrue(paragraphs[0].contains("is good I will copy it. If I cannot copy transcript"))
        assertTrue(paragraphs[0].endsWith("when it copy-paste."))
        assertTrue(paragraphs[1].startsWith("Hopefully it should not"))
        assertTrue(paragraphs[1].endsWith("we end up with."))
    }

    /** Two full sentences well under the target stay ONE paragraph — no spurious break just
     *  because a sentence ended. */
    @Test fun plain_noBreakBelowTarget() {
        val segments = listOf(
            seg("One.", 0, 1_000),
            seg("Two.", 1_000, 5_000), // contiguous, gap = 0
        )
        val paragraphs = plainParagraphs(
            TranscriptExport.format("t", "d", segments, ExportStyle.PLAIN, ExportFormat.TXT),
        )
        assertEquals(listOf("One. Two."), paragraphs)
    }

    /** Wall-guard: many contiguous segments with NO sentence punctuation still break, at
     *  [PARAGRAPH_HARD_CHARS], so the output never degrades into one giant wall. */
    @Test fun plain_wallGuardBreaksWithoutPunctuation() {
        // 60 segments × ~14 chars, no punctuation → ~880 chars, must break at the hard guard.
        val segments = (0 until 60).map { i -> seg("word number $i", i * 1_000L, (i + 1) * 1_000L) }
        val paragraphs = plainParagraphs(
            TranscriptExport.format("t", "d", segments, ExportStyle.PLAIN, ExportFormat.TXT),
        )
        assertTrue("must break into >1 paragraph despite no punctuation", paragraphs.size > 1)
        // No paragraph runs away past the hard guard by more than one segment's worth.
        paragraphs.dropLast(1).forEach {
            assertTrue("paragraph exceeded hard guard: len=${it.length}", it.length <= TranscriptExport.PARAGRAPH_HARD_CHARS + 20)
        }
    }

    /** A speaker change always starts a new paragraph, even below the length target. */
    @Test fun plain_speakerChangeForcesBreak() {
        val segments = listOf(
            seg("Hello.", 0, 1_000, speaker = "Alice"),
            seg("Hi there.", 1_000, 2_000, speaker = "Bob"),
        )
        val paragraphs = plainParagraphs(
            TranscriptExport.format("t", "d", segments, ExportStyle.PLAIN, ExportFormat.TXT),
        )
        assertEquals(listOf("Alice: Hello.", "Bob: Hi there."), paragraphs)
    }

    /** Sentence ender followed by a closing quote still counts as a sentence end. */
    @Test fun plain_sentenceEndWithTrailingQuote() {
        // Long enough to cross the target, then a quote-terminated sentence must break.
        val long = "a".repeat(TranscriptExport.PARAGRAPH_MIN_CHARS)
        val segments = listOf(
            seg("$long and then he said \"done.\"", 0, 5_000),
            seg("Next paragraph starts here now.", 5_000, 9_000),
        )
        val paragraphs = plainParagraphs(
            TranscriptExport.format("t", "d", segments, ExportStyle.PLAIN, ExportFormat.TXT),
        )
        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs[0].endsWith("\"done.\""))
        assertEquals("Next paragraph starts here now.", paragraphs[1])
    }

    // ── Single segment ─────────────────────────────────────────────────────
    @Test fun singleSegment_plain() {
        val out = TranscriptExport.format(
            "t", "d", listOf(seg("Just one line.", 0, 2000)), ExportStyle.PLAIN, ExportFormat.TXT,
        )
        assertEquals("t\nd\n\nJust one line.\n", out)
    }

    @Test fun singleSegment_timestamped() {
        val out = TranscriptExport.format(
            "t", "d", listOf(seg("Just one line.", 65_000, 67_000)),
            ExportStyle.TIMESTAMPED, ExportFormat.TXT,
        )
        assertEquals("t\nd\n\n[01:05] Just one line.\n", out)
    }

    // ── No-speech / empty (honesty contract) ───────────────────────────────
    @Test fun noSpeech_txt_emitsExactMessage() {
        val out = TranscriptExport.format(
            "Silent memo", "12 Sep · 0:10", emptyList(), ExportStyle.TIMESTAMPED, ExportFormat.TXT,
        )
        assertEquals(
            "Silent memo\n12 Sep · 0:10\n\nNo speech was detected in this recording.\n",
            out,
        )
    }

    @Test fun noSpeech_markdown_emitsExactMessage() {
        val out = TranscriptExport.format(
            "Silent memo", "d", emptyList(), ExportStyle.PLAIN, ExportFormat.MARKDOWN,
        )
        assertEquals(
            "# Silent memo\n\nd\n\nNo speech was detected in this recording.\n",
            out,
        )
    }

    @Test fun noSpeech_messageMatchesConstant() {
        assertEquals("No speech was detected in this recording.", TranscriptExport.NO_SPEECH_MESSAGE)
    }

    // ── Long transcript (~2000 segments) ───────────────────────────────────
    @Test fun longTranscript_rendersAllSegments_noCrash() {
        val n = 2000
        val segments = (0 until n).map { i ->
            seg("Segment number $i.", i * 1000L, i * 1000L + 800L)
        }
        val out = TranscriptExport.format(
            "Long one", "d", segments, ExportStyle.TIMESTAMPED, ExportFormat.TXT,
        )
        assertTrue(out.contains("Segment number 0."))
        assertTrue(out.contains("Segment number 1999."))
        assertTrue(out.contains("[33:19] Segment number 1999."))
        // Every segment on its own line (+ header line + date line).
        assertEquals(n, out.trimEnd('\n').lines().count { it.startsWith("[") })
    }

    // ── suggestedFileName() sanitization ───────────────────────────────────
    @Test fun filename_basic() =
        assertEquals("My Meeting.txt", TranscriptExport.suggestedFileName("My Meeting", ExportFormat.TXT))

    @Test fun filename_markdownExtension() =
        assertEquals("Notes.md", TranscriptExport.suggestedFileName("Notes", ExportFormat.MARKDOWN))

    @Test fun filename_stripsIllegalChars() {
        val out = TranscriptExport.suggestedFileName("Q3: budget/plan \"final\"?", ExportFormat.TXT)
        assertFalse(out.contains(":"))
        assertFalse(out.contains("/"))
        assertFalse(out.contains("\""))
        assertFalse(out.contains("?"))
        assertTrue(out.endsWith(".txt"))
    }

    @Test fun filename_collapsesWhitespaceAndTrims() =
        assertEquals("a b.txt", TranscriptExport.suggestedFileName("  a   b  ", ExportFormat.TXT))

    @Test fun filename_stripsLeadingTrailingDots() =
        assertEquals("hidden.md", TranscriptExport.suggestedFileName("...hidden...", ExportFormat.MARKDOWN))

    @Test fun filename_blankFallsBackToTranscript() {
        assertEquals("transcript.txt", TranscriptExport.suggestedFileName("", ExportFormat.TXT))
        assertEquals("transcript.txt", TranscriptExport.suggestedFileName("///???", ExportFormat.TXT))
        assertEquals("transcript.md", TranscriptExport.suggestedFileName("   ", ExportFormat.MARKDOWN))
    }

    @Test fun filename_boundsLength() {
        val out = TranscriptExport.suggestedFileName("x".repeat(500), ExportFormat.TXT)
        // 100-char stem cap + ".txt".
        assertEquals(104, out.length)
        assertTrue(out.endsWith(".txt"))
    }
}
