package london.aipartner.echo.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import london.aipartner.echo.R

/** Hilt entry point for the passphrase-setup destination. */
@Composable
fun BackupSetupRoute(
    onBack: () -> Unit,
    viewModel: BackupSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // When a passphrase is already set, show the "already set" surface (which re-shows
    // the unrecoverable warning and offers Change) rather than the entry form. Tapping
    // "Change passphrase" flips into edit mode.
    var editing by remember { mutableStateOf(false) }
    // On a SUCCESSFUL save (first-run OR Change) return to Settings. Keyed on the
    // one-shot savedEvent so a re-set (configured true→true) still fires. Skip the
    // initial 0 so merely opening an already-configured screen doesn't pop.
    LaunchedEffect(state.savedEvent) { if (state.savedEvent > 0) onBack() }

    if (state.configured && !editing) {
        BackupConfiguredScreen(onChange = { editing = true }, onBack = onBack)
    } else {
        BackupSetupScreen(state = state, onSubmit = viewModel::onSubmit, onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSetupScreen(
    state: BackupSetupUiState,
    onSubmit: (passphrase: String, confirm: String, acknowledged: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }

    val visual = if (show) VisualTransformation.None else PasswordVisualTransformation()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { inner ->
        // Outer column: scrollable content takes the available height; the CTA is pinned
        // at the bottom so it's always visible without scrolling. imePadding on the whole
        // column lifts both above the keyboard.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            Text(
                stringResource(R.string.backup_setup_intro),
                style = MaterialTheme.typography.bodyLarge,
            )

            // ── The binding unrecoverable warning — shown BEFORE the fields, in an
            //    error-toned card so it can't be skimmed past. ──
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.backup_setup_warning_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        stringResource(R.string.backup_setup_warning_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.backup_setup_field_label)) },
                visualTransformation = visual,
                singleLine = true,
                supportingText = { Text(stringResource(R.string.backup_setup_hint)) },
                isError = state.errorRes != null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text(stringResource(R.string.backup_setup_confirm_label)) },
                visualTransformation = visual,
                singleLine = true,
                isError = state.errorRes != null,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { show = !show }) {
                Text(stringResource(R.string.backup_setup_show))
            }

            // Acknowledgement gate — no key is derived unless this is checked.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                Text(
                    stringResource(R.string.backup_setup_ack_checkbox),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            } // end scrollable content

            // ── Pinned bottom CTA — always visible above the keyboard ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.errorRes != null) {
                    Text(
                        stringResource(state.errorRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { onSubmit(passphrase, confirm, acknowledged) },
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.working) {
                        CircularProgressIndicator(
                            modifier = Modifier.clearAndSetSemantics {}.padding(end = 12.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.backup_setup_save))
                }
            }
        }
    }
}

/**
 * Shown when a passphrase is already set. Re-shows the binding unrecoverable warning
 * (the gate's "re-findable in Settings" requirement) and states honestly that the
 * passphrase can't be displayed — Echo never stores it — only changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupConfiguredScreen(onChange: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.backup_configured_status),
                style = MaterialTheme.typography.bodyLarge,
            )

            // The unrecoverable warning is re-findable here (not a one-time flash).
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.backup_setup_warning_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        stringResource(R.string.backup_setup_warning_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Text(
                stringResource(R.string.backup_configured_cannot_show),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(onClick = onChange) {
                Text(stringResource(R.string.backup_change))
            }
        }
    }
}
