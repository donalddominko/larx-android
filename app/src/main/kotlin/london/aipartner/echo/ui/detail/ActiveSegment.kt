package london.aipartner.echo.ui.detail

/**
 * The pure heart of the karaoke sync: **which segment is active at time `t`**.
 *
 * Device-independent and JVM-unit-testable (Phase 6 gate). Semantics, with segments
 * ordered by `orderIdx` (as `TranscriptDao.segmentsFor` returns them):
 * - returns the index of the segment whose **half-open** interval `[tStartMs, tEndMs)`
 *   contains [positionMs];
 * - exact `tStartMs` is *inside* the segment; exact `tEndMs` is *not* (it belongs to
 *   the next segment, or to no segment if a gap follows) — no double-highlight at a
 *   boundary;
 * - before the first segment, in a gap between segments, or after the last segment,
 *   returns **-1** (no active segment) — honest, never a faked highlight;
 * - an empty transcript returns -1.
 *
 * Whisper segments are sequential and non-overlapping; the first containing match wins.
 */
fun activeSegmentIndexAt(positionMs: Long, segments: List<SegmentUi>): Int {
    for (i in segments.indices) {
        val s = segments[i]
        if (positionMs >= s.tStartMs && positionMs < s.tEndMs) return i
    }
    return -1
}
