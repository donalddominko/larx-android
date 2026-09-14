package london.aipartner.echo.ui.record

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import london.aipartner.echo.core.capture.CapturePreferences
import london.aipartner.echo.core.capture.RecorderController
import london.aipartner.echo.core.capture.RecorderState

/**
 * Read-only window onto the gated capture core for the record surface. It exposes
 * the [RecorderController]'s state + amplitude so the UI can render a live,
 * **honest** recording experience — but it holds **no** way to start capture: that
 * stays exclusively in the `ConsentGate`-guarded `RecordingService`/`RecorderController`
 * path, reached only via a runtime permission grant + a service intent (no bypass,
 * Hard rule). The UI renders the *resolved* `CaptureCapability` from this state.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val controller: RecorderController,
    private val capturePreferences: CapturePreferences,
) : ViewModel() {

    val state: StateFlow<RecorderState> = controller.state

    /** Live input level (0..1-ish) for the meter; emits only while a handle is active. */
    fun amplitude(): Flow<Float> = controller.amplitude()

    // Device-calibrated mic boost, set live on the record surface. Persisted immediately
    // and read by the controller at stop, so the next take is captured at the new gain.
    private val _micGain = MutableStateFlow(capturePreferences.micGain)
    val micGain: StateFlow<Float> = _micGain.asStateFlow()

    val gainRange: ClosedFloatingPointRange<Float> =
        CapturePreferences.MIN_GAIN..CapturePreferences.MAX_GAIN

    fun setMicGain(value: Float) {
        capturePreferences.micGain = value
        _micGain.value = capturePreferences.micGain
    }
}
