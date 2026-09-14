package london.aipartner.echo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import london.aipartner.echo.core.data.AiArtifactDao
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptDao

/** One library row — the editorial card model. [title] is the resolved display name with item-4
 *  precedence (manual userTitle → smart TITLE artifact), or null when there's no name yet
 *  (rendered as the date+time default from [createdAt], never faked). */
data class RecordingCardUi(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val durationMs: Long,
    val captureSource: String,
    val fidelity: String,
    /** Transcription lifecycle (one of [london.aipartner.echo.core.data.TranscriptionStatus]),
     *  so the card can honestly show "Transcribing…" / "Transcription failed". */
    val transcriptionStatus: String,
)

data class LibraryUiState(
    val loading: Boolean = true,
    val query: String = "",
    val searching: Boolean = false,
    val recordings: List<RecordingCardUi> = emptyList(),
    /** Live progress of the recording currently transcribing, if any: (recordingId, 0..100). */
    val transcribing: Pair<String, Int>? = null,
)

/**
 * Library list + **keyword-primary** search (2026-07-21). A blank query shows every recording
 * newest-first; a non-blank query returns ONLY recordings whose transcript literally contains the
 * query terms ([TranscriptKeywordSearch]), ranked by match count. This is exact, offline, and gives
 * true exclusion — the semantic embedder was dropped from the search path because its whole-document
 * cosines can't be thresholded (they only reorder, never exclude). The [london.aipartner.echo.core
 * .transcribe.SemanticIndex] is still populated at transcription time and kept for a future
 * paraphrase re-rank, but no longer gates search. This VM only reads the encrypted DB; it never
 * starts capture (that stays in the gated service) and never touches cloud/billing.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val recordingDao: RecordingDao,
    private val aiArtifactDao: AiArtifactDao,
    private val transcriptDao: TranscriptDao,
    private val progressBus: london.aipartner.echo.transcribe.TranscriptionProgressBus,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _query = MutableStateFlow("")

    init {
        observeQuery()
        observeTranscriptionProgress()
    }

    /**
     * Surface the live transcription progress, and — crucially — **re-read the DB when a
     * pass finishes** so a card flips from "Transcribing…" to its transcript/title without a
     * manual refresh (the bug Donald hit: it never refreshed when done). The bus emits NN%
     * while whisper runs, then null on completion → that's our cue to reload.
     */
    private fun observeTranscriptionProgress() {
        viewModelScope.launch {
            var wasTranscribing = false
            progressBus.state.collect { p ->
                _state.update { it.copy(transcribing = p?.let { pr -> pr.recordingId to pr.percent }) }
                if (p != null) {
                    wasTranscribing = true
                } else if (wasTranscribing) {
                    wasTranscribing = false
                    load(_query.value) // pass finished → pick up DONE/FAILED + title
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            _query.debounce(250).collectLatest { q -> load(q) }
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q, searching = q.isNotBlank()) }
        _query.value = q
    }

    /** Re-read after a new recording is captured/transcribed or one is deleted. */
    fun refresh() {
        viewModelScope.launch { load(_query.value) }
    }

    private suspend fun load(query: String) {
        val all = recordingDao.getAll() // already ORDER BY createdAt DESC
        val titles = all.associate { it.id to displayTitleFor(it) }

        val ordered: List<RecordingEntity> = if (query.isBlank()) {
            all
        } else {
            // Keyword-primary search over the transcript text (2026-07-21). This is exact and gives
            // TRUE exclusion — a recording matches only if its transcript literally contains the query
            // terms — unlike the semantic embedder, whose whole-document cosines bunch so tightly that
            // no threshold separates matches from non-matches (it only reordered the full set). It also
            // takes the fragile MediaPipe embedder out of the search path entirely. Reads only the DB.
            val texts = runCatching { transcriptDao.latestTranscriptTexts() }
                .onFailure { android.util.Log.e("EchoLibrary", "Transcript search read failed", it) }
                .getOrDefault(emptyList())
                .associate { it.recordingId to it.text }
            val scores = TranscriptKeywordSearch.match(query, texts)
            // `all` is newest-first; a stable sort by score keeps newest-first among equal scores.
            all.filter { scores.containsKey(it.id) }
                .sortedByDescending { scores.getValue(it.id) }
        }

        _state.value = LibraryUiState(
            loading = false,
            query = query,
            searching = false,
            recordings = ordered.map { it.toCard(titles[it.id]) },
            transcribing = _state.value.transcribing, // preserve live progress across reloads
        )
    }

    /** Display name with item-4 precedence: manual [RecordingEntity.userTitle] → smart TITLE
     *  artifact → null (the card renders a date+time default). A manual rename always wins. */
    private suspend fun displayTitleFor(recording: RecordingEntity): String? =
        london.aipartner.echo.core.transcribe.DisplayTitle.resolve(
            userTitle = recording.userTitle,
            smartTitle = smartTitleFor(recording.id),
        )

    private suspend fun smartTitleFor(recordingId: String): String? =
        aiArtifactDao.forRecording(recordingId)
            .firstOrNull { it.kind == "TITLE" }
            ?.content
            ?.takeIf { it.isNotBlank() }
}

private fun RecordingEntity.toCard(title: String?) = RecordingCardUi(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    captureSource = captureSource,
    fidelity = fidelity,
    transcriptionStatus = transcriptionStatus,
)
