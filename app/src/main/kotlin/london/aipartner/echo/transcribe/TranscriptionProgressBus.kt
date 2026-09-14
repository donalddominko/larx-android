package london.aipartner.echo.transcribe

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide live progress of the on-device transcription that runs after a recording
 * stops (Phase 6 Step 4). [TranscribingPostProcessor] publishes 0..100 here while whisper
 * runs; the library + detail screens observe it to show an honest "Transcribing… NN%" bar
 * and a real "done" transition. A `@Singleton` so the service-side writer and the UI-side
 * readers share one instance.
 *
 * Only one transcription runs at a time (one per record-stop, finalized in the FGS), so a
 * single current-progress slot is sufficient.
 */
@Singleton
class TranscriptionProgressBus @Inject constructor() {

    data class Progress(val recordingId: String, val percent: Int)

    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state.asStateFlow()

    fun update(recordingId: String, percent: Int) {
        _state.value = Progress(recordingId, percent.coerceIn(0, 100))
    }

    /** Clear progress for [recordingId] once its pass finishes (the screens then re-read
     *  the DB and show the final transcript/status). No-op if a different id is current. */
    fun clear(recordingId: String) {
        if (_state.value?.recordingId == recordingId) _state.value = null
    }
}
