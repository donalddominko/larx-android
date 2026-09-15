package london.aipartner.echo.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import london.aipartner.echo.core.transcribe.ExportFormat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import london.aipartner.echo.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.ui.common.TranscribingProgress
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

@Composable
fun RecordingDetailRoute(
    onBack: () -> Unit,
    viewModel: RecordingDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val positionMs by viewModel.playback.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.playback.durationMs.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playback.isPlaying.collectAsStateWithLifecycle()
    val ready by viewModel.playback.ready.collectAsStateWithLifecycle()
    val playbackFailed by viewModel.playback.failed.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()

    // Pop back to the library the moment the delete-everywhere contract completes.
    LaunchedEffect(deleted) { if (deleted) onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Write [format]'s rendering to the SAF-chosen [uri], off the main thread. NOTHING here
    // touches the network — a purely local content-resolver write. No storage permission needed;
    // SAF grants write to exactly the one document the user picked.
    fun writeTranscript(uri: Uri, format: ExportFormat) {
        scope.launch {
            val content = viewModel.exportContent(format)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    } ?: error("no output stream")
                }.isSuccess
            }
            if (ok) {
                // NAME the saved file in the toast — SAF may write anywhere (often Download, not
                // Documents), so telling the user WHAT was saved is how they find it. Prefer the
                // Uri's real display name (reflects any OS de-dup like "name (1).md").
                val name = withContext(Dispatchers.IO) { displayNameOf(context, uri) }
                    ?: viewModel.suggestedFileName(format)
                Toast.makeText(
                    context,
                    context.getString(R.string.transcript_saved_named, name),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(context, R.string.transcript_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // The txt launcher also serves the .md fallback: if a device's picker can't handle the
    // text/markdown mime, we relaunch it as text/plain but still WRITE markdown content — so we
    // remember which content to write here.
    var txtPendingFormat by remember { mutableStateOf(ExportFormat.TXT) }
    val txtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { writeTranscript(it, txtPendingFormat) } }
    val mdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri -> uri?.let { writeTranscript(it, ExportFormat.MARKDOWN) } }

    RecordingDetailScreen(
        state = state,
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        playbackReady = ready,
        playbackFailed = playbackFailed,
        transcriptExportable = state.transcriptExportable,
        onBack = onBack,
        onPlayPause = viewModel::playPause,
        onSeekToSegment = viewModel::seekToSegment,
        onSeekTo = viewModel::seekTo,
        onDelete = viewModel::delete,
        onRename = viewModel::rename,
        onCopyTranscript = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", viewModel.copyText()))
            // On API 33+ the OS shows its own "Copied" chip; a second toast would be redundant.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.transcript_copied, Toast.LENGTH_SHORT).show()
            }
        },
        onSaveTxt = {
            txtPendingFormat = ExportFormat.TXT
            txtLauncher.launch(viewModel.suggestedFileName(ExportFormat.TXT))
        },
        onSaveMd = {
            val name = viewModel.suggestedFileName(ExportFormat.MARKDOWN)
            try {
                mdLauncher.launch(name)
            } catch (_: ActivityNotFoundException) {
                // Picker rejects text/markdown — fall back to a text/plain picker with the .md
                // filename, still writing markdown content.
                txtPendingFormat = ExportFormat.MARKDOWN
                txtLauncher.launch(name)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    state: RecordingDetailUiState,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    playbackReady: Boolean,
    playbackFailed: Boolean = false,
    transcriptExportable: Boolean = false,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekToSegment: (SegmentUi) -> Unit,
    onSeekTo: (Long) -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onCopyTranscript: () -> Unit = {},
    onSaveTxt: () -> Unit = {},
    onSaveMd: () -> Unit = {},
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        DeleteConfirmDialog(
            title = state.title,
            fallback = formatDateTime(state.createdAt),
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }

    if (renaming) {
        RenameDialog(
            // Prefill with the current display name (manual/smart), NOT the date+time default —
            // the user edits a real name, or types over the placeholder to create one.
            current = state.title.orEmpty(),
            onConfirm = {
                renaming = false
                onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.title ?: formatDateTime(state.createdAt), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.loading && !state.notFound) {
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Rename recording")
                        }
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete recording")
                        }
                        // Overflow: transcript export. Items enabled only when a completed
                        // transcript exists (DONE, including no-speech) — honest never to offer an
                        // export of a pending/running/failed transcription.
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy transcript") },
                                enabled = transcriptExportable,
                                onClick = { menuOpen = false; onCopyTranscript() },
                            )
                            DropdownMenuItem(
                                text = { Text("Save as .txt") },
                                enabled = transcriptExportable,
                                onClick = { menuOpen = false; onSaveTxt() },
                            )
                            DropdownMenuItem(
                                text = { Text("Save as .md") },
                                enabled = transcriptExportable,
                                onClick = { menuOpen = false; onSaveMd() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!state.loading && !state.notFound && state.hasAudio) {
                if (playbackFailed) {
                    Surface(tonalElevation = 3.dp) {
                        Text(
                            "This recording's audio can't be played. You can still delete it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    TransportBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        isPlaying = isPlaying,
                        enabled = playbackReady,
                        onPlayPause = onPlayPause,
                        onSeekTo = onSeekTo,
                    )
                }
            }
        },
    ) { inner ->
        when {
            state.loading -> CenteredNote(inner, "Loading…")
            state.notFound -> CenteredNote(inner, "This recording is no longer available.")
            else -> DetailBody(
                inner = inner,
                state = state,
                positionMs = positionMs,
                onSeekToSegment = onSeekToSegment,
            )
        }
    }
}

@Composable
private fun DetailBody(
    inner: PaddingValues,
    state: RecordingDetailUiState,
    positionMs: Long,
    onSeekToSegment: (SegmentUi) -> Unit,
) {
    val listState = rememberLazyListState()

    // The segment the live playback position falls in — the natural highlight while playing.
    val positionIndex = remember(positionMs, state.segments) {
        activeSegmentIndexAt(positionMs, state.segments)
    }

    // TAP-TO-SEEK HIGHLIGHT DECOUPLING (the real karaoke-flash fix, 2026-07-26). The highlight must
    // NOT follow the live position during a tap-to-seek: `MediaPlayer` keeps advancing the OLD
    // position for the ~0.5–1s until the seek physically lands, so a position-derived highlight
    // shows the segment playback was LEAVING (a flash of the previous line) before snapping to the
    // tapped one. No amount of seek-timing logic fixes this — the highlight source is wrong. So on
    // tap we PIN the tapped segment as the highlight, authoritatively and independent of position,
    // and only release the pin once the live position actually enters the pinned segment's range
    // (or the user taps elsewhere). The tapped line IS the highlight for that window, by fiat.
    var pinnedIndex by remember(state.segments) { mutableStateOf(-1) }
    // PIN RELEASE (diagnosed on-device 2026-07-26, EchoKaraoke log): releasing on
    // `positionIndex == pinnedIndex` was defeated by MediaPlayer OPTIMISTICALLY reporting the seek
    // target (~15ms after tap) and then jittering a few ms BELOW it — and since a tap seeks to the
    // segment's EXACT start boundary, that dip maps to the PREVIOUS segment → the released
    // highlight flipped back for a frame (the flash). So release only when the position is
    // comfortably INSIDE the pinned segment (start + epsilon absorbs the post-seek jitter), or has
    // naturally played past its end. A tap on another segment re-pins (the onClick overwrites).
    LaunchedEffect(positionMs, pinnedIndex, state.segments) {
        val pinned = state.segments.getOrNull(pinnedIndex) ?: return@LaunchedEffect
        val insideByEpsilon = positionMs >= pinned.tStartMs + PIN_RELEASE_EPSILON_MS &&
            positionMs < pinned.tEndMs
        val playedPastEnd = positionMs >= pinned.tEndMs
        if (insideByEpsilon || playedPastEnd) pinnedIndex = -1
    }
    val activeIndex = if (pinnedIndex >= 0) pinnedIndex else positionIndex

    // Auto-scroll FOLLOWS playback but YIELDS to manual scroll: a user drag turns
    // following off; tapping a segment (an explicit choice of position) turns it back on.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) following = false
        }
    }
    // Keep the active line in view only when it would otherwise scroll off-screen —
    // no constant re-centering that would fight a reading user.
    LaunchedEffect(activeIndex, following) {
        if (following && activeIndex >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val onScreen = visible.any { it.index == activeIndex }
            if (!onScreen) listState.animateScrollToItem(activeIndex.coerceAtLeast(0))
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(inner)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item("meta") {
                Text(
                    "${formatDate(state.createdAt)} · ${formatDuration(state.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Summary / action items are STRUCTURALLY gated off in v1 (no generative AI, no
            // INTERNET) — never rendered, so the app can't surface a feature it doesn't ship.
            // Returns with the AI-summaries Pro fast-follow. See FeatureFlags.SUMMARIES.
            @Suppress("KotlinConstantConditions")
            if (london.aipartner.echo.FeatureFlags.SUMMARIES) {
                state.summary?.let { item("summary") { Section("Summary", it) } }
                state.actions?.let { item("actions") { Section("Action items", it) } }
            }

            item("transcript-heading") {
                Spacer(Modifier.height(4.dp))
                Text("Transcript", style = MaterialTheme.typography.titleMedium)
            }

            // Layer 3 honest warning — sits ABOVE the transcript, never replaces it, so the
            // user can still read/verify what was produced while being told it may be wrong.
            state.transcriptWarning?.let { warning ->
                item("transcript-warning") {
                    TranscriptWarningCard(warning)
                }
            }

            when {
                state.noSpeech -> item("nospeech") {
                    Text(
                        "No speech was detected in this recording.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.segments.isEmpty() -> item("notranscript") {
                    when (state.transcriptionStatus) {
                        TranscriptionStatus.PENDING.name,
                        TranscriptionStatus.RUNNING.name ->
                            // Shared indicator — identical honest progression to the library card.
                            TranscribingProgress(
                                percent = state.transcribingPercent,
                                textStyle = MaterialTheme.typography.bodyLarge,
                            )
                        TranscriptionStatus.FAILED.name -> Text(
                            "Transcription failed. The audio is safe — you can still play it.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Text(
                            "No transcript yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> items(
                    count = state.segments.size,
                    key = { i -> "seg-$i" },
                ) { i ->
                    KaraokeLine(
                        segment = state.segments[i],
                        active = i == activeIndex,
                        onClick = {
                            following = true
                            pinnedIndex = i // highlight the tapped line NOW, independent of position
                            onSeekToSegment(state.segments[i])
                        },
                    )
                }
            }
        }

        // "Jump to current" appears only when auto-follow has yielded to the user.
        AnimatedVisibility(visible = !following && activeIndex >= 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                FilledTonalButton(onClick = { following = true }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  Jump to current line")
                }
            }
        }
    }
}

@Composable
private fun KaraokeLine(segment: SegmentUi, active: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val resting = MaterialTheme.colorScheme.onSurfaceVariant
    // Expressive, smooth colour transition into/out of the active state — the texture.
    val textColor by animateColorAsState(if (active) accent else resting, label = "karaokeColor")
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        label = "karaokeBg",
    )

    val label = buildString {
        segment.speaker?.let { append("$it: ") }
        append(segment.text)
    }

    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // TalkBack: the active line announces itself; tapping any line seeks here.
            .semantics {
                contentDescription = if (active) "Now playing: $label" else label
                if (active) liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            modifier = Modifier
                .fillMaxWidth()
                .background(bg, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clearAndSetSemantics { }, // the Surface carries the semantics
        )
    }
}

@Composable
private fun TransportBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding() // clear the system gesture/3-button nav bar
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onPlayPause, enabled = enabled) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
            Text(formatDuration(positionMs), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = { frac -> if (durationMs > 0) onSeekTo((frac * durationMs).toLong()) },
                enabled = enabled && durationMs > 0,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Seek" },
            )
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Rename dialog (item 4). The user sets a MANUAL title — the one that always wins and survives
 * re-transcription. Saving a blank name clears the manual title (reverts to the smart/date+time
 * default); the ViewModel's [RecordingDetailViewModel.rename] handles the trim-to-null.
 */
@Composable
private fun RenameDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename recording") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeleteConfirmDialog(
    title: String?,
    fallback: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete recording?") },
        text = {
            Text(
                "“${title ?: fallback}” and its transcript, summary, and " +
                    "search index will be permanently deleted. This can't be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CenteredNote(inner: PaddingValues, text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(inner).padding(20.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The honest transcript-quality warning (post-decode [MisdecodeGuard]). A tonal card, distinct
 * from the transcript, shown ABOVE it. One generic shape
 * ([TranscriptWarning.LikelyUnsupportedLanguage]) — evidence-based, never names a language, never
 * references a tier (v1 is free-only). The pre-decode language-detect + one-tap "switch language"
 * prompt were removed (Direction A step 3, 2026-07-19 — unreliable signal).
 */
@Composable
private fun TranscriptWarningCard(warning: TranscriptWarning) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (warning) {
                is TranscriptWarning.LikelyUnsupportedLanguage -> {
                    WarnText(
                        title = stringResource(R.string.warn_unsupported_language_title),
                        body = stringResource(R.string.warn_unsupported_language_body),
                    )
                }
            }
        }
    }
}

@Composable
private fun WarnText(title: String, body: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun Section(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}

/** How far INSIDE a tapped segment playback must be before the tap-pin yields to the position-
 *  derived highlight — absorbs MediaPlayer's post-seek position jitter (a few-ms dip below the
 *  segment's exact start would otherwise map to the previous segment and flash it). */
/** The human-facing filename of a SAF document [uri] (its DISPLAY_NAME), or the last path
 *  segment as a fallback, or null if neither can be read. Used only to name the save toast. */
private fun displayNameOf(context: Context, uri: Uri): String? {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

private const val PIN_RELEASE_EPSILON_MS = 280L

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

/** The date+time default title for a recording with no manual/smart name (item 4). */
private fun formatDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
