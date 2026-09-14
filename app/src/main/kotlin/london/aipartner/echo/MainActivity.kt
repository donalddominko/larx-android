package london.aipartner.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import london.aipartner.echo.core.capture.RecorderController
import london.aipartner.echo.core.consent.ConsentPreferences
import london.aipartner.echo.core.ui.theme.EchoTheme
import london.aipartner.echo.ui.EchoRoot

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Same singletons the RecordingService uses — the UI observes state and reads
    // the disclosure flag, but capture still goes through the gated service.
    @Inject lateinit var consentPreferences: ConsentPreferences
    @Inject lateinit var recorderController: RecorderController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicit dark bar styling for Larx's dark UI. Transparent scrims: the Compose
        // content draws edge-to-edge under the bars, and Scaffold insets keep controls clear.
        // dark(...) forces light (white) status/nav icons regardless of system light/dark,
        // which is correct for the always-dark surface.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            EchoTheme {
                val state = recorderController.state.collectAsState()
                EchoRoot(consentPreferences = consentPreferences, recorderState = state)
            }
        }
    }
}
