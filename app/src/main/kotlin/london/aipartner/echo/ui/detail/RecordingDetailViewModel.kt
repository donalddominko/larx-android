package london.aipartner.echo.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.data.AiArtifactDao
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.TranscriptDao
import london.aipartner.echo.core.transcribe.ExportFormat
import london.aipartner.echo.core.transcribe.TranscriptExport
import london.aipartner.echo.core.transcribe.TranscriptSegment
import london.aipartner.echo.playback.PlaybackController
import london.aipartner.echo.recordings.RecordingDeleter
import london.aipartner.echo.transcribe.TranscriptExportPreferences
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** A single transcript line of the latest revision — the seam the karaoke player
 *  animates. Carries timings so the highlight is a pure function of position. */
data class SegmentUi(
    val text: String,
    val tStartMs: Long,
    val tEndMs: Long,
    val speaker: String?,
)

data class RecordingDetailUiState(
    val loading: Boolean = true,
    val title: String? = null,
    val createdAt: Long = 0L,
    val durationMs: Long = 0L,
    val summary: String? = null,
    val actions: String? = null,
    val segments: List<SegmentUi> = emptyList(),
    /** Honest warning shown above the transcript when a mis-decode is suspected (Layer 3),
     *  else null. Never blocks the transcript — sits above it. */
    val transcriptWarning: TranscriptWarning? = null,
    val noSpeech: Boolean = false,
    val notFound: Boolean = false,
    val hasAudio: Boolean = false,
    /** Live whisper progress 0..100 while this recording is transcribing, else null. */
    val transcribingPercent: Int? = null,
    /** Transcription lifecycle (one of [london.aipartner.echo.core.data.TranscriptionStatus])
     *  so the screen honestly shows transcribing / failed vs an absent transcript. */
    val transcriptionStatus: String = london.aipartner.echo.core.data.TranscriptionStatus.PENDING.name,
) {
    /**
     * True once a completed transcript exists — DONE, INCLUDING the no-speech case (which is a
     * legitimate finished result). This gates the Copy / Save menu items: honest to offer an
     * export of a silent recording (it exports the honest "no speech" line), never of a pending,
     * running, or failed one.
     */
    val transcriptExportable: Boolean
        get() = transcriptionStatus == london.aipartner.echo.core.data.TranscriptionStatus.DONE.name
}

/**
 * Read-only detail over the encrypted DB **plus** the karaoke playback clock.
 *
 * Renders the latest `TranscriptRevision` (highest `rev`), never a hardcoded rev0 —
 * deferred transcript editing (which appends a revision) drops in with zero rework.
 * No fabrication: absent title/summary render honest-empty; a no-speech recording
 * shows no faked lines.
 *
 * Playback decrypts the encrypted audio **transiently** via [AudioEncryptor] into
 * cache and hands the temp file to a [PlaybackController] that **shreds it on
 * teardown** — no plaintext audio outlives the session (Hard rule / prime directive #2).
 */
@HiltViewModel
class RecordingDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recordingDao: RecordingDao,
    private val transcriptDao: TranscriptDao,
    private val aiArtifactDao: AiArtifactDao,
    private val audioEncryptor: AudioEncryptor,
    private val recordingDeleter: RecordingDeleter,
    private val progressBus: london.aipartner.echo.transcribe.TranscriptionProgressBus,
    private val exportPreferences: TranscriptExportPreferences,
) : ViewModel() {

    private val recordingId: String = checkNotNull(savedStateHandle["id"])

    private val _state = MutableStateFlow(RecordingDetailUiState())
    val state: StateFlow<RecordingDetailUiState> = _state.asStateFlow()

    /** Flips true once the delete-everywhere contract has run; the screen pops on it. */
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    val playback = PlaybackController()

    /** Playback is prepared once; the transcription poll re-runs [load] but must not
     *  re-decrypt or reset the player. */
    private var playbackPrepared = false

    init {
        // Reactive observation (BUG A fix): the recording row + its transcript revisions are
        // observed as Room Flows, combined with the in-memory whisper-progress bus. When the
        // service flips `transcriptionStatus` to DONE and writes rev 0, Room's invalidation
        // tracker re-emits and the screen flips from "Transcribing…" to the transcript IN
        // PLACE — no manual delay-poll, no navigating away to force a re-read.
        viewModelScope.launch {
            combine(
                recordingDao.observeById(recordingId),
                transcriptDao.observeRevisions(recordingId),
                progressBus.state,
            ) { recording, revisions, progress -> Triple(recording, revisions, progress) }
                .collectLatest { (recording, revisions, progress) ->
                    rebuildState(recording, revisions, progress)
                }
        }
    }

    private suspend fun rebuildState(
        recording: london.aipartner.echo.core.data.RecordingEntity?,
        revisions: List<london.aipartner.echo.core.data.TranscriptRevisionEntity>,
        progress: london.aipartner.echo.transcribe.TranscriptionProgressBus.Progress?,
    ) {
        if (recording == null) {
            _state.value = RecordingDetailUiState(loading = false, notFound = true)
            return
        }

        // Latest revision = highest rev. observeRevisions() is ORDER BY rev ascending.
        val latest = revisions.lastOrNull()
        val segments = latest
            ?.let { transcriptDao.segmentsFor(it.id) }
            ?.map { SegmentUi(it.text, it.tStartMs, it.tEndMs, it.speaker) }
            .orEmpty()

        val artifacts = aiArtifactDao.forRecording(recordingId)
        fun artifact(kind: String) = artifacts.firstOrNull { it.kind == kind }
            ?.content?.takeIf { it.isNotBlank() }

        val encryptedPath = recording.localAudioRef
        val hasAudio = encryptedPath != null && File(encryptedPath).exists()

        val newState = RecordingDetailUiState(
            loading = false,
            // Item-4 precedence: manual userTitle → smart TITLE artifact → null (screen shows a
            // date+time default). A manual rename always wins and survives re-transcription.
            title = london.aipartner.echo.core.transcribe.DisplayTitle.resolve(
                userTitle = recording.userTitle,
                smartTitle = artifact("TITLE"),
            ),
            createdAt = recording.createdAt,
            durationMs = recording.durationMs,
            summary = artifact("SUMMARY"),
            actions = artifact("ACTIONS"),
            segments = segments,
            // Layers 2 & 3: a DONE transcript with the repetition-loop signature is flagged
            // honestly (the mismatched-language user's v1 safety net) rather than presented as
            // real. Layer 3 (the guard) is the trigger; Layer 2's persisted detected language
            // (populated by the post-processor on a hit) upgrades the copy from generic to named,
            // and picks the two-case shape: on-device → offer switch; else → "coming with Pro".
            transcriptWarning = warningFor(recording, segments),
            noSpeech = latest != null && segments.isEmpty(),
            hasAudio = hasAudio,
            transcriptionStatus = recording.transcriptionStatus,
            transcribingPercent = progress?.takeIf { it.recordingId == recordingId }?.percent,
        )
        // Room invalidation is table-level, so an unrelated write can re-emit identical
        // content. Only publish on a real change, so `segments` keeps a stable identity and
        // the karaoke active-segment isn't recomputed (no highlight flicker) for a no-op.
        if (newState != _state.value) _state.value = newState

        if (hasAudio && !playbackPrepared) {
            playbackPrepared = true
            preparePlayback(File(encryptedPath!!))
        }
    }

    private fun preparePlayback(encrypted: File) {
        viewModelScope.launch {
            val temp = withContext(Dispatchers.IO) {
                runCatching { audioEncryptor.decryptToTemp(encrypted) }.getOrNull()
            } ?: return@launch
            playback.load(temp)
        }
    }

    /**
     * The honest transcript warning, or null — sourced ONLY from [MisdecodeGuard] (post-decode).
     *
     * **Evidence-based, not detector-based (Direction A step 3, 2026-07-19).** The pre-decode
     * whisper-`base` language-detect was removed: its language ID is unreliable on real phone-mic
     * audio (genuine English at P(en) 0.12–0.31 vs ~0.95 on clean fixtures), so it produced false
     * "might not be English" warnings under correct transcripts. [MisdecodeGuard] instead inspects
     * the ACTUAL output (repetition / foreign-annotation signals) — it fires on real garbage, not
     * on a noisy probability, and costs nothing. Always generic ("may be inaccurate"); it never
     * names a language, and it never references a tier/Pro (v1 is free-only).
     * (`recording.detectedLanguageTag` is no longer written — it stays null; the column persists
     * for old rows / a future reliable detector. See references/better-models-pro.md.)
     */
    private fun warningFor(
        recording: london.aipartner.echo.core.data.RecordingEntity,
        segments: List<SegmentUi>,
    ): TranscriptWarning? {
        val done = recording.transcriptionStatus ==
            london.aipartner.echo.core.data.TranscriptionStatus.DONE.name
        if (!done) return null

        // The transcript has a mis-decode TEXT signature (repetition / foreign annotation) → the
        // honest generic "may be inaccurate" warn, shown ALONGSIDE the transcript.
        return if (
            london.aipartner.echo.transcribe.MisdecodeGuard.suspectedMisdecode(segments.map { it.text })
        ) {
            TranscriptWarning.LikelyUnsupportedLanguage
        } else {
            null
        }
    }

    /**
     * Manual rename (item 4). Persists the user's title on the recording row, so it wins over any
     * smart/date+time title and survives re-transcription (which only touches the TITLE artifact).
     * A blank/whitespace name clears it (null) → revert to the smart/date+time title. The
     * `observeById` Flow re-emits, so the screen updates in place. Never fabricates a title.
     */
    fun rename(newTitle: String) {
        val cleaned = newTitle.trim().takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recordingDao.updateUserTitle(recordingId, cleaned) }
        }
    }

    /**
     * Render the current transcript for export in [format], using the persisted [ExportStyle].
     * Pure text out — the caller (the screen) owns the Android side (clipboard / SAF write).
     * The header title mirrors the screen's own precedence: manual/smart title, else date+time.
     */
    fun exportContent(format: ExportFormat): String {
        val s = _state.value
        val title = s.title ?: formatDateTime(s.createdAt)
        val dateLine = "${formatDate(s.createdAt)} · ${formatDuration(s.durationMs)}"
        return TranscriptExport.format(
            recordingTitle = title,
            dateLine = dateLine,
            segments = s.segments.map { TranscriptSegment(it.text, it.tStartMs, it.tEndMs, it.speaker) },
            style = exportPreferences.style,
            format = format,
        )
    }

    /** The clipboard text — ALWAYS the plain-text (TXT) rendering with the persisted style;
     *  markdown syntax would be noise pasted into another app. */
    fun copyText(): String = exportContent(ExportFormat.TXT)

    /** The seed filename for the SAF save dialog for [format]. */
    fun suggestedFileName(format: ExportFormat): String {
        val s = _state.value
        val title = s.title ?: formatDateTime(s.createdAt)
        return TranscriptExport.suggestedFileName(title, format)
    }

    fun playPause() = playback.playPause()
    fun seekToSegment(segment: SegmentUi) = playback.seekTo(segment.tStartMs)
    fun seekTo(ms: Long) = playback.seekTo(ms)

    /**
     * Delete-everywhere (prime directive #2). Releases playback first (frees the
     * decrypted temp), then runs [RecordingDeleter]: propagate to every sink (a
     * no-op over the empty v1 sink set, but the contract is invoked unconditionally),
     * remove the local encrypted file, and cascade-delete the DB row (which clears
     * transcript revisions/segments, AI artifacts, embeddings, consent + sync rows
     * via FK CASCADE). Explicit and confirmed by the caller before this runs.
     */
    fun delete() {
        viewModelScope.launch {
            playback.dispose()
            withContext(Dispatchers.IO) { recordingDeleter.delete(recordingId) }
            _deleted.value = true
        }
    }

    override fun onCleared() {
        playback.dispose()
        super.onCleared()
    }
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

private fun formatDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
