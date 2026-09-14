package london.aipartner.echo.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 6 gate — the pure "active segment at time *t*" function across every boundary:
 * before the first segment, exact `tStartMs`, mid-segment, the gap between segments,
 * after the last segment, and a single-segment transcript. Device-independent.
 */
class ActiveSegmentTest {

    private fun seg(start: Long, end: Long) = SegmentUi("s", start, end, null)

    // Two segments with a deliberate GAP at [1000, 1500).
    private val segments = listOf(
        seg(0, 1000),
        seg(1500, 3000),
    )

    @Test fun beforeFirstSegment_isNone() {
        // Only possible if the first segment doesn't start at 0.
        val s = listOf(seg(500, 1000))
        assertEquals(-1, activeSegmentIndexAt(0, s))
        assertEquals(-1, activeSegmentIndexAt(499, s))
    }

    @Test fun exactStart_isInside() {
        assertEquals(0, activeSegmentIndexAt(0, segments))
        assertEquals(1, activeSegmentIndexAt(1500, segments))
    }

    @Test fun midSegment_matches() {
        assertEquals(0, activeSegmentIndexAt(500, segments))
        assertEquals(1, activeSegmentIndexAt(2200, segments))
    }

    @Test fun exactEnd_belongsToNextOrNone() {
        // Half-open: tEndMs is NOT inside this segment (no double-highlight).
        assertEquals(-1, activeSegmentIndexAt(1000, segments)) // end of seg0, in the gap
        assertEquals(-1, activeSegmentIndexAt(3000, segments)) // end of last seg → none
    }

    @Test fun gapBetweenSegments_isNone() {
        assertEquals(-1, activeSegmentIndexAt(1200, segments))
        assertEquals(-1, activeSegmentIndexAt(1499, segments))
    }

    @Test fun afterLastSegment_isNone() {
        assertEquals(-1, activeSegmentIndexAt(5000, segments))
    }

    @Test fun singleSegment() {
        val one = listOf(seg(0, 2000))
        assertEquals(0, activeSegmentIndexAt(0, one))
        assertEquals(0, activeSegmentIndexAt(1999, one))
        assertEquals(-1, activeSegmentIndexAt(2000, one))
        assertEquals(-1, activeSegmentIndexAt(2001, one))
    }

    @Test fun emptyTranscript_isNone() {
        assertEquals(-1, activeSegmentIndexAt(0, emptyList()))
        assertEquals(-1, activeSegmentIndexAt(1234, emptyList()))
    }
}
