package london.aipartner.echo.ui.record

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import london.aipartner.echo.core.capture.CaptureJob
import london.aipartner.echo.core.capture.CaptureCapability
import london.aipartner.echo.core.capture.Fidelity
import london.aipartner.echo.core.capture.RecorderState
import london.aipartner.echo.core.capture.RecordingService
import london.aipartner.echo.core.consent.CaptureMode

/** Hilt entry point for the record destination. */
@Composable
fun RecordRoute(
    onDone: () -> Unit,
    viewModel: RecordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val micGain by viewModel.micGain.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission denied is an honest dead-end, not a silent failure.
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            // The ONLY path to capture: a service intent into the gated controller.
            ContextCompat.startForegroundService(
                context,
                RecordingService.startIntent(context, CaptureJob.MEMO, CaptureMode.ON_DEMAND),
            )
        } else {
            permissionDenied = true
        }
    }

    // On entry, if nothing is recording yet, ask for the mic and start. If a
    // recording is already in flight (returning to the surface), reflect it as-is.
    LaunchedEffect(Unit) {
        if (state is RecorderState.Idle) permissionLauncher.launch(recordPermissions())
    }

    // The captured recording lands in the DB on stop; once we return to Idle after
    // a session, leave the surface so the library shows the new card.
    var sawRecording by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is RecorderState.Recording) sawRecording = true
        if (sawRecording && state is RecorderState.Idle) onDone()
    }

    // Live input level for the meter, collected only while a handle is active.
    var amplitude by remember { mutableFloatStateOf(0f) }
    val recording = state is RecorderState.Recording
    LaunchedEffect(recording) {
        amplitude = 0f
        if (recording) viewModel.amplitude().collect { amplitude = it }
    }

    RecordScreen(
        state = state,
        amplitude = amplitude,
        permissionDenied = permissionDenied,
        micGain = micGain,
        gainRange = viewModel.gainRange,
        onMicGainChange = viewModel::setMicGain,
        onPause = { context.serviceAction(RecordingService.ACTION_PAUSE) },
        onResume = { context.serviceAction(RecordingService.ACTION_RESUME) },
        onStop = { context.serviceAction(RecordingService.ACTION_STOP) },
        onBack = onDone,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    state: RecorderState,
    amplitude: Float,
    permissionDenied: Boolean,
    micGain: Float,
    gainRange: ClosedFloatingPointRange<Float>,
    onMicGainChange: (Float) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    val recording = state as? RecorderState.Recording

    // Elapsed clock: tick once a second while actively recording (paused freezes it).
    var elapsedSec by remember { mutableStateOf(0L) }
    LaunchedEffect(recording?.recordingId) { elapsedSec = 0L }
    LaunchedEffect(recording?.paused, recording?.recordingId) {
        while (recording != null && recording.paused == false && isActive) {
            delay(1000)
            elapsedSec += 1
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Recording") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                permissionDenied -> {
                    Text(
                        "Microphone permission is required to record. Larx can't capture audio without it.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    FilledTonalButton(onClick = onBack) { Text("Back to library") }
                }

                recording != null -> {
                    // Honest, resolved capability — never aspirational copy.
                    Text(
                        capabilityLabel(recording.capability),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        formatElapsed(elapsedSec),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "Recording, ${formatElapsed(elapsedSec)} elapsed"
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (recording.paused) "Paused" else "Recording…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (recording.paused) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(32.dp))
                    AmplitudeMeter(
                        amplitude = if (recording.paused) 0f else amplitude,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    )
                    Spacer(Modifier.height(40.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (recording.paused) {
                            FilledTonalButton(onClick = onResume) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text("  Resume")
                            }
                        } else {
                            FilledTonalButton(onClick = onPause) {
                                Icon(Icons.Filled.Pause, contentDescription = null)
                                Text("  Pause")
                            }
                        }
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Text("  Stop")
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                    GainControl(micGain, gainRange, onMicGainChange)
                }

                state is RecorderState.Finalizing -> {
                    // Capture stopped, mic released; encrypting + (if boosted) re-encoding.
                    Text("Saving…", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Finishing your recording.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    // Brief idle window while the permission prompt / service start lands.
                    Text(
                        "Preparing the microphone…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GainControl(
    micGain: Float,
    gainRange: ClosedFloatingPointRange<Float>,
    onMicGainChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mic boost", style = MaterialTheme.typography.labelLarge)
            Text(
                if (micGain <= 1.02f) "Off (1.0×)" else "%.1f×".format(micGain),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = micGain,
            onValueChange = onMicGainChange,
            valueRange = gainRange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Microphone boost, ${"%.1f".format(micGain)} times" },
        )
        Text(
            "Recordings on this phone can be quiet — raise this until the meter responds " +
                "well to your voice. It applies live as you record.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AmplitudeMeter(amplitude: Float, modifier: Modifier = Modifier) {
    // Smooth the raw level so the bar reads as motion, not jitter.
    val level by animateFloatAsState(amplitude.coerceIn(0f, 1f), label = "amplitude")
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.clip(CircleShape)) {
        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = "Input level" }) {
            drawRect(color = track)
            drawRect(color = accent, size = size.copy(width = size.width * level))
        }
    }
}

private fun android.content.Context.serviceAction(action: String) {
    startService(Intent(this, RecordingService::class.java).setAction(action))
}

private fun capabilityLabel(capability: CaptureCapability): String {
    val fidelity = when (capability.fidelity) {
        Fidelity.HD -> "High quality"
        Fidelity.LOSSY -> "Standard quality"
    }
    return "Microphone · $fidelity"
}

private fun recordPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun formatElapsed(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
