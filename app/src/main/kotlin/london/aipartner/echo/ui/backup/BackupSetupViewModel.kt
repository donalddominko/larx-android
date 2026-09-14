package london.aipartner.echo.ui.backup

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import london.aipartner.echo.R
import london.aipartner.echo.core.sync.backup.BackupKeyManager
import london.aipartner.echo.core.sync.backup.PassphraseStrength.RejectReason

data class BackupSetupUiState(
    val configured: Boolean = false,
    val working: Boolean = false,
    /** A fail-closed rejection message, or null. Maps 1:1 to the strength guard. */
    @param:StringRes val errorRes: Int? = null,
    /**
     * One-shot counter bumped on every SUCCESSFUL save. The route observes this to
     * leave the flow — needed because a re-set (Change) doesn't change [configured]
     * (true→true), so observing [configured] alone would miss it.
     */
    val savedEvent: Int = 0,
)

/**
 * Backs the passphrase-setup flow. All validation is fail-closed through
 * [BackupKeyManager.setup] → [london.aipartner.echo.core.sync.backup.PassphraseStrength]:
 * an empty / too-short / too-weak / mismatched passphrase, or an un-acknowledged
 * warning, derives NO key and shows the mapped error. The raw passphrase never leaves
 * this call — it is not held in state.
 */
@HiltViewModel
class BackupSetupViewModel @Inject constructor(
    private val backupKeyManager: BackupKeyManager,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupSetupUiState(configured = backupKeyManager.isConfigured()))
    val state: StateFlow<BackupSetupUiState> = _state.asStateFlow()

    fun onSubmit(passphrase: String, confirm: String, acknowledged: Boolean) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, errorRes = null) }
        viewModelScope.launch {
            // Argon2id is deliberately costly; derive off the main thread.
            val outcome = withContext(Dispatchers.Default) {
                backupKeyManager.setup(passphrase, confirm, acknowledged)
            }
            _state.update {
                when (outcome) {
                    BackupKeyManager.SetupOutcome.Configured ->
                        it.copy(working = false, configured = true, errorRes = null, savedEvent = it.savedEvent + 1)
                    BackupKeyManager.SetupOutcome.NotAcknowledged ->
                        it.copy(working = false, errorRes = R.string.backup_error_not_acknowledged)
                    is BackupKeyManager.SetupOutcome.Rejected ->
                        it.copy(working = false, errorRes = outcome.reason.toErrorRes())
                }
            }
        }
    }

    private fun RejectReason.toErrorRes(): Int = when (this) {
        RejectReason.EMPTY -> R.string.backup_error_empty
        RejectReason.TOO_SHORT -> R.string.backup_error_too_short
        RejectReason.TOO_WEAK -> R.string.backup_error_too_weak
        RejectReason.MISMATCH -> R.string.backup_error_mismatch
    }
}
