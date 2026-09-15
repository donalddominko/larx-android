package london.aipartner.echo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import london.aipartner.echo.FeatureFlags
import london.aipartner.echo.R
import london.aipartner.echo.core.transcribe.ExportStyle
import london.aipartner.echo.transcribe.LanguageOption

/** Hilt entry point for the Settings destination. */
@Composable
fun SettingsRoute(
    onAbout: () -> Unit,
    onOpenPassphrase: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Re-read egress/passphrase state each time Settings returns to the foreground
    // (e.g. after setting a passphrase) so the status lines stay honest.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    SettingsScreen(
        state = state,
        onLanguageSelected = viewModel::onLanguageSelected,
        onExportStyleSelected = viewModel::onExportStyleSelected,
        onCloudBackupToggled = viewModel::onCloudBackupToggled,
        onOpenPassphrase = onOpenPassphrase,
        onAbout = onAbout,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onLanguageSelected: (String?) -> Unit,
    onExportStyleSelected: (ExportStyle) -> Unit,
    onCloudBackupToggled: (Boolean) -> Unit,
    onOpenPassphrase: () -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showExportStylePicker by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Group A — Transcription language (Layer 1: global default) ──
            SectionHeader(stringResource(R.string.settings_group_language))
            SettingRow(
                title = stringResource(R.string.settings_language_row_title),
                value = state.selectedLanguage.label(),
                subtitle = stringResource(R.string.settings_language_row_subtitle),
                onClick = { showLanguagePicker = true },
            )

            SectionDivider()

            // ── Group — Transcript export (style used by both Copy and Save) ──
            SectionHeader(stringResource(R.string.settings_group_transcript))
            SettingRow(
                title = stringResource(R.string.settings_export_style_row_title),
                value = stringResource(state.exportStyle.labelRes()),
                subtitle = stringResource(R.string.settings_export_style_row_subtitle),
                onClick = { showExportStylePicker = true },
            )

            SectionDivider()

            // ── Group B — Recording ──
            SectionHeader(stringResource(R.string.settings_group_recording))
            InfoRow(stringResource(R.string.settings_micboost_pointer))

            // ── Group C — Cloud backup — GATED OFF in v1 (FeatureFlags.CLOUD_BACKUP). ──
            // v1 ships no INTERNET permission, so cloud backup cannot work; exposing it would
            // advertise a guaranteed-to-fail feature and contradict the store's "recordings can't
            // leave your device" claim. The backend (:core:sync) + the passphrase-setup screen stay
            // in the codebase, unreachable, until the sync fast-follow flips the flag.
            @Suppress("KotlinConstantConditions")
            if (FeatureFlags.CLOUD_BACKUP) {
                SectionDivider()
                SectionHeader(stringResource(R.string.settings_backup_group))
                ToggleRow(
                    title = stringResource(R.string.settings_egress_toggle_title),
                    subtitle = stringResource(R.string.settings_egress_toggle_subtitle),
                    checked = state.cloudBackupOn,
                    onCheckedChange = onCloudBackupToggled,
                )
                if (state.cloudBackupOn) {
                    SettingRow(
                        title = stringResource(R.string.settings_passphrase_row_title),
                        value = null,
                        subtitle = stringResource(
                            if (state.passphraseConfigured) R.string.settings_passphrase_row_configured
                            else R.string.settings_passphrase_row_set,
                        ),
                        onClick = onOpenPassphrase,
                    )
                    InfoRow(
                        stringResource(
                            if (state.passphraseConfigured) R.string.settings_egress_on_ready
                            else R.string.settings_egress_on_needs_passphrase,
                        ),
                    )
                }
            }

            SectionDivider()

            // ── Group D — Storage & privacy ──
            SectionHeader(stringResource(R.string.settings_group_storage))
            InfoRow(stringResource(R.string.settings_storage_status))
            SettingRow(
                title = stringResource(R.string.settings_about_row_title),
                value = null,
                subtitle = stringResource(R.string.settings_about_row_subtitle),
                onClick = onAbout,
            )
        }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            options = state.languageOptions,
            selected = state.selectedLanguage,
            onSelect = {
                onLanguageSelected(it.tag)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false },
        )
    }

    if (showExportStylePicker) {
        ExportStylePickerDialog(
            selected = state.exportStyle,
            onSelect = {
                onExportStyleSelected(it)
                showExportStylePicker = false
            },
            onDismiss = { showExportStylePicker = false },
        )
    }
}

/** String label for an [ExportStyle], for the current row value. */
private fun ExportStyle.labelRes(): Int = when (this) {
    ExportStyle.TIMESTAMPED -> R.string.settings_export_style_timestamped
    ExportStyle.PLAIN -> R.string.settings_export_style_plain
}

private fun ExportStyle.descRes(): Int = when (this) {
    ExportStyle.TIMESTAMPED -> R.string.settings_export_style_timestamped_desc
    ExportStyle.PLAIN -> R.string.settings_export_style_plain_desc
}

@Composable
private fun ExportStylePickerDialog(
    selected: ExportStyle,
    onSelect: (ExportStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_export_style_picker_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                ExportStyle.entries.forEach { style ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = style == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(style) },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = style == selected, onClick = null)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(stringResource(style.labelRes()), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(style.descRes()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_export_style_picker_dismiss))
            }
        },
    )
}

@Composable
private fun LanguagePickerDialog(
    options: List<LanguageOption>,
    selected: LanguageOption,
    onSelect: (LanguageOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_picker_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_language_picker_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.selectableGroup().padding(top = 12.dp)) {
                    options.forEach { option ->
                        LanguageOptionRow(
                            selected = option.tag == selected.tag,
                            label = option.label(),
                            onClick = { onSelect(option) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_language_picker_dismiss))
            }
        },
    )
}

@Composable
private fun LanguageOptionRow(selected: Boolean, label: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
    )
}

/** A tappable settings row: title, optional current [value], and an explanatory [subtitle]. */
@Composable
private fun SettingRow(title: String, value: String?, subtitle: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A settings row with a trailing [Switch] — title, explanatory subtitle, toggle. */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A non-interactive honest-status line (e.g. the cloud-off state, the mic-boost pointer). */
@Composable
private fun InfoRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    )
}
