package london.aipartner.echo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import london.aipartner.echo.R
import london.aipartner.echo.core.capture.RecorderState
import london.aipartner.echo.core.consent.ConsentPreferences
import london.aipartner.echo.ui.detail.RecordingDetailRoute
import london.aipartner.echo.ui.library.LibraryRoute
import london.aipartner.echo.ui.record.RecordRoute
import london.aipartner.echo.ui.settings.SettingsRoute
import london.aipartner.echo.ui.backup.BackupSetupRoute

private object Routes {
    const val LIBRARY = "library"
    const val DETAIL = "detail/{id}"
    const val RECORD = "record"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val BACKUP_PASSPHRASE = "backup_passphrase"
    fun detail(id: String) = "detail/$id"
}

/**
 * App root. The first-run disclosure shows before anything else — it is
 * **acknowledge-only** and does NOT unlock recording (the gated RecordingService +
 * ConsentGate remain the only path to capture). Once acknowledged, a Navigation
 * graph hosts the library → recording detail → settings/about flow (Phase 6).
 */
@Composable
fun EchoRoot(
    consentPreferences: ConsentPreferences,
    recorderState: State<RecorderState>,
) {
    var disclosureSeen by remember { mutableStateOf(consentPreferences.disclosureAcknowledged) }

    if (!disclosureSeen) {
        Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
            DisclosureScreen(Modifier.fillMaxSize().padding(inner).padding(24.dp)) {
                consentPreferences.disclosureAcknowledged = true
                disclosureSeen = true
            }
        }
        return
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryRoute(
                recorderState = recorderState,
                onOpenRecording = { id -> navController.navigate(Routes.detail(id)) },
                onRecord = { navController.navigate(Routes.RECORD) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            RecordingDetailRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.RECORD) {
            RecordRoute(onDone = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onAbout = { navController.navigate(Routes.ABOUT) },
                onOpenPassphrase = { navController.navigate(Routes.BACKUP_PASSPHRASE) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.BACKUP_PASSPHRASE) {
            BackupSetupRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun DisclosureScreen(modifier: Modifier, onAcknowledge: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.disclosure_firstrun_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.disclosure_firstrun_body_ondevice), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.disclosure_firstrun_body_responsibility), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.disclosure_firstrun_retrieval),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        // Acknowledge-only: a single button, no checkbox, no "I agree".
        Button(onClick = onAcknowledge) {
            Text(stringResource(R.string.disclosure_firstrun_acknowledge))
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.about_privacy_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.about_privacy_body_ondevice), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_privacy_body_responsibility), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}
