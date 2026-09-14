package london.aipartner.echo.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import london.aipartner.echo.ui.common.TranscribingProgress
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import london.aipartner.echo.core.capture.RecorderState
import london.aipartner.echo.core.data.TranscriptionStatus

/** Hilt entry point for the library destination. */
@Composable
fun LibraryRoute(
    recorderState: State<RecorderState>,
    onOpenRecording: (String) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A finished recording lands in the DB synchronously on stop; refresh the list
    // when the recorder returns to Idle so the new card appears without a restart.
    val recorder = recorderState.value
    androidx.compose.runtime.LaunchedEffect(recorder) {
        if (recorder is RecorderState.Idle) viewModel.refresh()
    }

    // Reload whenever the library comes back to the foreground — returning from the
    // player, or back to the app after an external write (e.g. the debug demo seeder).
    // Without this, a row added while the screen was backgrounded wouldn't appear until
    // a full restart.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LibraryScreen(
        state = state,
        recorderState = recorderState,
        onQueryChange = viewModel::onQueryChange,
        onOpenRecording = onOpenRecording,
        onRecord = onRecord,
        onSettings = onSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    recorderState: State<RecorderState>,
    onQueryChange: (String) -> Unit,
    onOpenRecording: (String) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
) {
    val recording = recorderState.value is RecorderState.Recording

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Larx", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            // The library never starts capture itself — it opens the gated record
            // surface, which owns the permission grant + service lifecycle.
            ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                text = { Text(if (recording) "Recording…" else "Record") },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search recordings" },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search your recordings") },
            )

            when {
                state.loading -> CenteredMessage("Loading…")
                state.recordings.isEmpty() && state.query.isBlank() ->
                    CenteredMessage("No recordings yet.\nTap Record to capture your first.")
                state.recordings.isEmpty() ->
                    CenteredMessage("No recordings match “${state.query}”.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.recordings, key = { it.id }) { card ->
                        val livePercent =
                            state.transcribing?.takeIf { it.first == card.id }?.second
                        RecordingCard(
                            card = card,
                            livePercent = livePercent,
                            onClick = { onOpenRecording(card.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(card: RecordingCardUi, livePercent: Int?, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = card.title ?: formatDateTime(card.createdAt),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatDate(card.createdAt)} · ${formatDuration(card.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (card.transcriptionStatus) {
                TranscriptionStatus.PENDING.name, TranscriptionStatus.RUNNING.name ->
                    TranscribingProgress(
                        percent = livePercent,
                        textStyle = MaterialTheme.typography.labelMedium,
                    )
                TranscriptionStatus.FAILED.name -> Text(
                    text = "Transcription failed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { contentDescription = message },
        )
    }
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

/** The date+time default name for a recording with no manual/smart title (item 4). */
private fun formatDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
