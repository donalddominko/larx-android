package london.aipartner.echo.core.sync.backup

import london.aipartner.echo.core.sync.backup.PassphraseStrength.RejectReason
import london.aipartner.echo.core.sync.backup.PassphraseStrength.SetupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fail-closed strength-guard gate item: empty / too-short / too-weak /
 * confirm-mismatch are ALL rejected (no key derivable); only a strong, confirmed
 * passphrase is accepted. Because the passphrase can't be reset, a weak/empty one is
 * a permanent footgun — so this is proven, not assumed.
 */
class PassphraseStrengthTest {

    @Test fun empty_isRejected() {
        assertEquals(SetupResult.Rejected(RejectReason.EMPTY), PassphraseStrength.validateSetup("", ""))
    }

    @Test fun belowMinLength_isRejected() {
        val short = "aB3\$xz" // 6 chars, < MIN_LENGTH
        assertEquals(SetupResult.Rejected(RejectReason.TOO_SHORT), PassphraseStrength.validateSetup(short, short))
    }

    @Test fun trivialCommonPassword_isRejected() {
        // ≥ MIN_LENGTH so it clears the length check and is rejected purely on weakness.
        assertEquals(SetupResult.Rejected(RejectReason.TOO_WEAK), PassphraseStrength.validateSetup("passphrase", "passphrase"))
    }

    @Test fun repeatedSingleChar_isRejected() {
        val weak = "aaaaaaaaaaaa"
        assertEquals(SetupResult.Rejected(RejectReason.TOO_WEAK), PassphraseStrength.validateSetup(weak, weak))
    }

    @Test fun pureLongDigitRun_isRejected() {
        val pin = "1357913579"
        assertEquals(SetupResult.Rejected(RejectReason.TOO_WEAK), PassphraseStrength.validateSetup(pin, pin))
    }

    @Test fun confirmMismatch_isRejected() {
        assertEquals(
            SetupResult.Rejected(RejectReason.MISMATCH),
            PassphraseStrength.validateSetup("correct horse battery", "correct horse batteryX"),
        )
    }

    @Test fun strongConfirmedPassphrase_isAccepted() {
        val strong = "correct horse battery staple"
        assertEquals(SetupResult.Accepted, PassphraseStrength.validateSetup(strong, strong))
        assertTrue(PassphraseStrength.evaluate(strong) is PassphraseStrength.Verdict.Acceptable)
    }
}
