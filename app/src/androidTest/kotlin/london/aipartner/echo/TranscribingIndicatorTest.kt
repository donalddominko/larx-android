package london.aipartner.echo

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.ui.detail.RecordingDetailScreen
import london.aipartner.echo.ui.detail.RecordingDetailUiState
import org.junit.Rule
import org.junit.Test

/**
 * Phase 6 — BUG B gate (**what the user SEES** while transcribing), driven against the
 * stateless [RecordingDetailScreen]:
 *  - while progress is unknown (null) or a real 0 (whisper reports per-30s chunk, so a
 *    sub-30s memo legitimately reads 0 the whole pass), the screen shows an animated
 *    **indeterminate** bar and the plain "Transcribing…" label — never a fake "0%";
 *  - only a real >0% switches to a **determinate** bar with the percentage.
 *
 * Asserting the indeterminate/determinate distinction via `ProgressBarRangeInfo` is the
 * point: the earlier gate passed while the bar was visibly a frozen 0% determinate.
 */
class TranscribingIndicatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val indeterminate = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
    )

    private fun setTranscribing(percent: Int?) {
        composeRule.setContent {
            RecordingDetailScreen(
                state = RecordingDetailUiState(
                    loading = false,
                    segments = emptyList(),
                    transcriptionStatus = TranscriptionStatus.RUNNING.name,
                    transcribingPercent = percent,
                    hasAudio = false,
                ),
                positionMs = 0L,
                durationMs = 0L,
                isPlaying = false,
                playbackReady = false,
                onBack = {}, onPlayPause = {}, onSeekToSegment = {}, onSeekTo = {}, onDelete = {}, onRename = {},
            )
        }
    }

    @Test fun unknownProgress_showsIndeterminate_noPercent() {
        setTranscribing(null)
        composeRule.onNodeWithText("Transcribing…").assertIsDisplayed()
        composeRule.onNode(indeterminate).assertExists()
    }

    @Test fun zeroProgress_showsIndeterminate_notAFakeZeroPercent() {
        // whisper's single 0% callback on a sub-30s memo must NOT render a frozen "0%".
        setTranscribing(0)
        composeRule.onNodeWithText("Transcribing…").assertIsDisplayed()
        composeRule.onNodeWithText("Transcribing… 0%").assertDoesNotExist()
        composeRule.onNode(indeterminate).assertExists()
    }

    @Test fun realProgress_showsDeterminateWithPercent() {
        setTranscribing(42)
        composeRule.onNodeWithText("Transcribing… 42%").assertIsDisplayed()
        // Determinate now — no indeterminate bar on screen.
        composeRule.onNode(indeterminate).assertDoesNotExist()
    }
}
