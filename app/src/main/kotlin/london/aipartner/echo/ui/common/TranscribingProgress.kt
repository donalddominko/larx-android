package london.aipartner.echo.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * The ONE honest transcription-progress indicator, shared by every surface (library card,
 * detail screen) so they can never drift into showing different progressions for the same
 * recording.
 *
 * Two honesty rules, both enforced here in one place:
 *  - **Indeterminate while unknown.** whisper's progress callback fires once per 30s decode
 *    chunk, so a sub-30s memo legitimately reads 0 the whole pass. Anything <= 0 (or null =
 *    no callback yet) shows an animated **indeterminate** bar — motion proves the slow A53
 *    pass is alive, not hung — and the plain "Transcribing…" label, never a fake "0%".
 *  - **Determinate only on a real >0%.** Only a genuine whisper-reported percent switches to
 *    a determinate bar with the number. (The native engine resets idle progress to 0 between
 *    passes, so a stale "100% then climbs" can't reach this.)
 */
@Composable
fun TranscribingProgress(
    percent: Int?,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    modifier: Modifier = Modifier,
) {
    val determinate = percent != null && percent > 0
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (determinate) "Transcribing… $percent%" else "Transcribing…",
            style = textStyle,
            color = MaterialTheme.colorScheme.primary,
        )
        if (determinate) {
            LinearProgressIndicator(
                progress = { percent!! / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
