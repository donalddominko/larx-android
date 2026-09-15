package london.aipartner.echo.core.transcribe

/** The two persisted export renderings. [TIMESTAMPED] prefixes each line with `[mm:ss]`;
 *  [PLAIN] drops timestamps and reflows the segments into readable paragraphs. */
enum class ExportStyle { TIMESTAMPED, PLAIN }

/** The container the rendered transcript is written into. [TXT] is plain text (also the
 *  format Copy always uses); [MARKDOWN] adds a heading + emphasis for `.md` files. */
enum class ExportFormat { TXT, MARKDOWN }

/**
 * Pure, Android-free transcript renderer. Turns a title + a caller-preformatted date line +
 * the transcript segments into the exact text we place on the clipboard or write to a file.
 *
 * **Honesty contract (prime directive #3):** a no-speech / empty transcript renders the header
 * followed by the EXACT on-screen string [NO_SPEECH_MESSAGE] — never a fabricated line, never a
 * crash. The rendering here must match what the detail screen shows.
 *
 * Kept deliberately free of any Android type so it is unit-testable on the JVM and so the
 * capture/consent seams stay the sole owners of platform concerns.
 */
object TranscriptExport {

    /**
     * PLAIN paragraphing is **sentence-boundary based**, NOT gap based. Whisper sets each
     * segment's t0 to the previous segment's t1, so inter-segment gaps are ~0 by construction
     * (acoustic pauses are absorbed into a segment's span) — a gap threshold could never fire and
     * produced one unbroken wall on real A03 output. So instead we accumulate text into a
     * paragraph and break at the next sentence end once the paragraph is long enough to read as
     * a paragraph.
     *
     * [PARAGRAPH_MIN_CHARS]: once the current paragraph reaches this length, the NEXT
     * sentence-ending punctuation (`.` `?` `!`) flushes it and starts a new paragraph — so
     * paragraphs always end on a sentence, never mid-thought. Tunable after judging real output.
     */
    const val PARAGRAPH_MIN_CHARS: Int = 240

    /**
     * Wall-guard: if a paragraph reaches this length WITHOUT any sentence punctuation to break on
     * (whisper occasionally omits punctuation for long stretches), force a break anyway so the
     * output never degrades back into one giant wall. Deliberately well above
     * [PARAGRAPH_MIN_CHARS] so it only fires in the punctuation-free pathological case.
     */
    const val PARAGRAPH_HARD_CHARS: Int = 600

    /** The exact honest string shown on-screen for a transcribed-but-silent recording. */
    const val NO_SPEECH_MESSAGE: String = "No speech was detected in this recording."

    /**
     * Render [segments] for [recordingTitle] (with a caller-preformatted [dateLine]) in [style]
     * and [format]. An empty [segments] list renders the header + [NO_SPEECH_MESSAGE].
     */
    fun format(
        recordingTitle: String,
        dateLine: String,
        segments: List<TranscriptSegment>,
        style: ExportStyle,
        format: ExportFormat,
    ): String {
        val header = renderHeader(recordingTitle, dateLine, format)
        val body = if (segments.isEmpty()) {
            NO_SPEECH_MESSAGE
        } else when (style) {
            ExportStyle.TIMESTAMPED -> renderTimestamped(segments, format)
            ExportStyle.PLAIN -> renderPlain(segments)
        }
        return header + "\n\n" + body + "\n"
    }

    /** `[mm:ss]`, or `[h:mm:ss]` once the offset reaches one hour. */
    fun timestamp(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours >= 1L) {
            "[%d:%02d:%02d]".format(hours, minutes, seconds)
        } else {
            "[%02d:%02d]".format(minutes, seconds)
        }
    }

    /**
     * A safe seed filename for the SAF save dialog: the title, stripped of characters no common
     * filesystem accepts, length-bounded, with the [format] extension. Falls back to
     * `transcript` when the title sanitizes to nothing.
     */
    fun suggestedFileName(recordingTitle: String, format: ExportFormat): String {
        val cleaned = recordingTitle
            .replace(ILLEGAL_FILENAME_CHARS, " ") // path separators, reserved chars, controls → space
            .replace(Regex("\\s+"), " ")           // collapse whitespace runs
            .trim()
            .trim('.')                             // no leading/trailing dots (hidden/ext confusion)
            .take(MAX_FILENAME_STEM)
            .trim()
        val stem = cleaned.ifBlank { "transcript" }
        return stem + extension(format)
    }

    private fun renderHeader(title: String, dateLine: String, format: ExportFormat): String =
        when (format) {
            ExportFormat.TXT -> title + "\n" + dateLine
            ExportFormat.MARKDOWN -> "# " + title + "\n\n" + dateLine
        }

    private fun renderTimestamped(segments: List<TranscriptSegment>, format: ExportFormat): String {
        val lines = segments.map { seg ->
            val stamp = timestamp(seg.tStartMs)
            val speaker = seg.speaker?.let { "$it: " }.orEmpty()
            when (format) {
                ExportFormat.TXT -> "$stamp $speaker${seg.text}"
                // Bold the timestamp so it reads as a label, not body text, in rendered markdown.
                ExportFormat.MARKDOWN -> "**$stamp** $speaker${seg.text}"
            }
        }
        // One blank line between lines in markdown (so each is its own paragraph); single
        // newlines in plain text keep the transcript compact.
        val joiner = if (format == ExportFormat.MARKDOWN) "\n\n" else "\n"
        return lines.joinToString(joiner)
    }

    /**
     * Reflow the segments into paragraphs by **sentence boundary** (see [PARAGRAPH_MIN_CHARS]):
     * consecutive segments join with a space; the paragraph breaks at the first sentence end
     * (`.` `?` `!`) once it has reached [PARAGRAPH_MIN_CHARS], or is force-broken at
     * [PARAGRAPH_HARD_CHARS] if no punctuation appears. A speaker change always starts a new
     * paragraph (diarization future). The same text in both TXT and MARKDOWN — a blank line
     * between paragraphs renders correctly in each.
     */
    private fun renderPlain(segments: List<TranscriptSegment>): String {
        val paragraphs = mutableListOf<StringBuilder>()
        var current: StringBuilder? = null
        var prevSpeaker: String? = null

        for (seg in segments) {
            val text = seg.text.trim()
            val speakerChanged = current != null && seg.speaker != prevSpeaker
            if (current == null || speakerChanged) {
                current = StringBuilder()
                paragraphs.add(current)
                // Label a paragraph with its speaker only when diarization actually gives one.
                seg.speaker?.let { current.append("$it: ") }
            } else {
                current.append(" ")
            }
            current.append(text)
            prevSpeaker = seg.speaker

            // Break AFTER this segment when the paragraph is long enough and ends a sentence, or
            // when it has grown past the wall-guard even without sentence punctuation.
            val len = current.length
            val broke = (len >= PARAGRAPH_MIN_CHARS && endsSentence(current)) ||
                len >= PARAGRAPH_HARD_CHARS
            if (broke) current = null
        }
        return paragraphs.joinToString("\n\n") { it.toString() }
    }

    /**
     * True if [sb] ends a sentence: a `.` `?` `!` optionally followed by a trailing closing
     * quote/bracket (e.g. `it."`, `done!)`). Kept deliberately simple — not a full sentence
     * tokenizer; abbreviations like "e.g." can break early, which is acceptable for readability.
     */
    private fun endsSentence(sb: StringBuilder): Boolean {
        var i = sb.length - 1
        while (i >= 0 && sb[i] in SENTENCE_TRAILING) i--
        return i >= 0 && sb[i] in SENTENCE_ENDERS
    }

    private fun extension(format: ExportFormat): String = when (format) {
        ExportFormat.TXT -> ".txt"
        ExportFormat.MARKDOWN -> ".md"
    }

    // Reserved on Windows/Android FAT + path separators + control chars.
    private val ILLEGAL_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private const val MAX_FILENAME_STEM = 100

    // Sentence-boundary detection for PLAIN paragraphing.
    private val SENTENCE_ENDERS = setOf('.', '?', '!')
    // Trailing chars allowed after the ender and still count as a sentence end (closing quotes/brackets).
    private val SENTENCE_TRAILING = setOf('"', '\'', ')', ']', '}', '”', '’', '»')
}
