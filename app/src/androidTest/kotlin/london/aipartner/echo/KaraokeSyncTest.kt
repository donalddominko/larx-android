package london.aipartner.echo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import london.aipartner.echo.ui.detail.RecordingDetailScreen
import london.aipartner.echo.ui.detail.RecordingDetailUiState
import london.aipartner.echo.ui.detail.SegmentUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Phase 6 gate — **karaoke sync + seek (Compose)**, bidirectional:
 * - as `positionMs` advances, the correct segment becomes the active line (TalkBack
 *   announces it as "Now playing: …", the signature accent state);
 * - tapping a segment seeks the controller to that segment's `tStartMs`.
 *
 * Driven against the stateless [RecordingDetailScreen] with a hoisted position so the
 * sync is exercised without a real `MediaPlayer` (the clock itself is unit-covered by
 * the pure `activeSegmentIndexAt`). The full real-`MainActivity` end-to-end flow is the
 * separate BINDING gate item run at phase close.
 */
class KaraokeSyncTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val segments = listOf(
        SegmentUi("First line of speech", 0, 1000, null),
        SegmentUi("Second line of speech", 1000, 2000, null),
        SegmentUi("Third line of speech", 2000, 3000, null),
    )

    @Test fun positionAdvances_highlightFollows() {
        var position by mutableStateOf(200L)
        composeRule.setContent {
            RecordingDetailScreen(
                state = RecordingDetailUiState(
                    loading = false, durationMs = 3000, segments = segments, hasAudio = true,
                ),
                positionMs = position,
                durationMs = 3000,
                isPlaying = false,
                playbackReady = true,
                onBack = {}, onPlayPause = {}, onSeekToSegment = {}, onSeekTo = {}, onDelete = {}, onRename = {},
            )
        }

        // At 200ms the FIRST line is active.
        composeRule.onNodeWithContentDescription("Now playing: First line of speech").assertIsDisplayed()

        // Advance into the third segment; the highlight follows.
        position = 2500L
        composeRule.onNodeWithContentDescription("Now playing: Third line of speech").assertIsDisplayed()
    }

    @Test fun tappingSegment_seeksToItsStart() {
        var seekedTo = -1L
        composeRule.setContent {
            RecordingDetailScreen(
                state = RecordingDetailUiState(
                    loading = false, durationMs = 3000, segments = segments, hasAudio = true,
                ),
                positionMs = 0L,
                durationMs = 3000,
                isPlaying = false,
                playbackReady = true,
                onBack = {}, onPlayPause = {},
                onSeekToSegment = { seekedTo = it.tStartMs },
                onSeekTo = {},
                onDelete = {},
                onRename = {},
            )
        }

        // Inactive lines carry their text as the Surface contentDescription (the inner
        // Text clears its own semantics so the line reads as one node to TalkBack).
        composeRule.onNodeWithContentDescription("Second line of speech").performClick()
        assertEquals(1000L, seekedTo)
    }
}
