package london.aipartner.echo.core.sync.backup

/**
 * Fail-closed policy for the **backup passphrase** — the one element in Echo with
 * no recovery path (it is never uploaded and never escrowed, so it cannot be
 * reset). A lost or trivially-weak passphrase is a *permanent* loss / a weak-crypto
 * footgun, not a UX nit — so this guard rejects **before any key is derived**.
 *
 * Pure JVM, no Android types: this is the gate item unit-tested in
 * `PassphraseStrengthTest` (empty / too-short / too-weak / confirm-mismatch are all
 * rejected and yield NO derived key; only a strong, confirmed passphrase is accepted).
 */
object PassphraseStrength {

    /**
     * Minimum length. Deliberately passphrase-friendly (encourages a long memorable
     * phrase over a short cryptic one) rather than an arbitrary complexity ruleset.
     */
    const val MIN_LENGTH = 10

    /** A short deny-list of the most common trivially-weak choices (case-insensitive). */
    private val TRIVIAL = setOf(
        "password", "passphrase", "1234567890", "qwertyuiop", "0000000000",
        "1111111111", "letmein123", "iloveyou12", "adminadmin",
    )

    /** Verdict on a single passphrase (before the confirm-entry check). */
    sealed interface Verdict {
        data object Acceptable : Verdict
        sealed interface Rejected : Verdict { val reason: RejectReason }
        data object Empty : Rejected { override val reason = RejectReason.EMPTY }
        data object TooShort : Rejected { override val reason = RejectReason.TOO_SHORT }
        data object TooWeak : Rejected { override val reason = RejectReason.TOO_WEAK }
    }

    enum class RejectReason { EMPTY, TOO_SHORT, TOO_WEAK, MISMATCH }

    /** Evaluate a single passphrase. Whitespace is significant (not trimmed). */
    fun evaluate(passphrase: String): Verdict = when {
        passphrase.isEmpty() -> Verdict.Empty
        passphrase.length < MIN_LENGTH -> Verdict.TooShort
        isTooWeak(passphrase) -> Verdict.TooWeak
        else -> Verdict.Acceptable
    }

    /**
     * Full setup validation including the **confirm-entry** (typed twice, must match).
     * The single fail-closed entry point the setup flow calls before deriving a key.
     */
    fun validateSetup(passphrase: String, confirm: String): SetupResult =
        when (val v = evaluate(passphrase)) {
            is Verdict.Rejected -> SetupResult.Rejected(v.reason)
            Verdict.Acceptable ->
                if (passphrase == confirm) SetupResult.Accepted
                else SetupResult.Rejected(RejectReason.MISMATCH)
        }

    sealed interface SetupResult {
        data object Accepted : SetupResult
        data class Rejected(val reason: RejectReason) : SetupResult
    }

    /** Weak = on the deny-list, or a single repeated char, or a pure run of digits. */
    private fun isTooWeak(p: String): Boolean {
        val lower = p.lowercase()
        if (lower in TRIVIAL) return true
        if (p.toSet().size == 1) return true // "aaaaaaaaaa"
        if (p.all { it.isDigit() }) return true // long PINs are still brute-forceable
        return false
    }
}
