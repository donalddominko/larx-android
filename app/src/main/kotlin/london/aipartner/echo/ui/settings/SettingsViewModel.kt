package london.aipartner.echo.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import london.aipartner.echo.core.consent.ConsentPreferences
import london.aipartner.echo.core.sync.backup.BackupKeyManager
import london.aipartner.echo.core.transcribe.ExportStyle
import london.aipartner.echo.transcribe.LanguageCatalog
import london.aipartner.echo.transcribe.LanguageOption
import london.aipartner.echo.transcribe.TranscriptExportPreferences
import london.aipartner.echo.transcribe.TranscriptionPreferences

data class SettingsUiState(
    /** The current transcription-language override; `null` = follow the device locale. */
    val languageTagOverride: String? = null,
    /** The curated picker options (Device default + spot-checked languages). */
    val languageOptions: List<LanguageOption> = LanguageCatalog.options,
    /** Phase 8 — whether cloud backup (data egress) has been turned on. Off by default. */
    val cloudBackupOn: Boolean = false,
    /** Whether a backup passphrase has been set (its derived key cached). */
    val passphraseConfigured: Boolean = false,
    /** The persisted transcript export style — used by both Copy and Save. */
    val exportStyle: ExportStyle = ExportStyle.TIMESTAMPED,
) {
    /** The option currently selected — matched by tag, defaulting to Device default. */
    val selectedLanguage: LanguageOption
        get() = languageOptions.firstOrNull { it.tag == languageTagOverride }
            ?: LanguageOption(null)

    /** Egress on but no passphrase yet — the honest "finish turning it on" state. */
    val backupNeedsPassphrase: Boolean get() = cloudBackupOn && !passphraseConfigured
}

/**
 * Backs the Settings surface. In v1 its one piece of real state is the global transcription
 * **language override** (Layer 1 of the language design) — it reads/writes
 * [TranscriptionPreferences.languageTagOverride], which the engine already honours. Setting
 * it fixes **future** recordings; existing transcripts are untouched (the UI copy says so).
 *
 * Everything else on the screen is honest static/resolved state (on-device processing, the
 * cloud-off status line, About) and needs no VM state.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val transcriptionPreferences: TranscriptionPreferences,
    private val consentPreferences: ConsentPreferences,
    private val backupKeyManager: BackupKeyManager,
    private val exportPreferences: TranscriptExportPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun snapshot() = SettingsUiState(
        languageTagOverride = transcriptionPreferences.languageTagOverride,
        cloudBackupOn = consentPreferences.dataEgressAllowed,
        passphraseConfigured = backupKeyManager.isConfigured(),
        exportStyle = exportPreferences.style,
    )

    /** Re-read persisted state — e.g. after returning from the passphrase-setup screen. */
    fun refresh() {
        _state.value = snapshot()
    }

    /** Set (or clear, with `null`) the global default transcription language. */
    fun onLanguageSelected(tag: String?) {
        transcriptionPreferences.languageTagOverride = tag
        // Re-read through the pref so we reflect exactly what was persisted (blank → null).
        _state.update { it.copy(languageTagOverride = transcriptionPreferences.languageTagOverride) }
    }

    /** Set the transcript export style (Timestamped / Plain). Read by both Copy and Save. */
    fun onExportStyleSelected(style: ExportStyle) {
        exportPreferences.style = style
        _state.update { it.copy(exportStyle = exportPreferences.style) }
    }

    /**
     * Flip data egress on/off — the Phase-3 chokepoint this phase first *uses*. Turning
     * it off stops future uploads. Turning it on doesn't upload anything until a passphrase
     * (and, later, a destination) is set; the honest status line reflects that.
     */
    fun onCloudBackupToggled(on: Boolean) {
        consentPreferences.dataEgressAllowed = on
        _state.update { it.copy(cloudBackupOn = consentPreferences.dataEgressAllowed) }
    }
}
