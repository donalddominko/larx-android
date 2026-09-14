package london.aipartner.echo.core.sync.backup

/**
 * The zero-knowledge boundary in the sync path: re-encrypts a device-decrypted
 * artifact with the **user's backup key** (Argon2id-derived) before it reaches any
 * `CloudSink`. The sink only ever receives the output of this — never plaintext.
 *
 * Fail-closed: [encrypt] throws [BackupNotUnlocked] when no derived key is available
 * (no passphrase set yet), so a sync cannot proceed without the user key.
 */
fun interface BackupCipher {
    /** @throws BackupNotUnlocked when no backup key is available. */
    fun encrypt(plaintext: ByteArray): ByteArray
}

/** No backup passphrase has been set (no derived key), so nothing can be encrypted-for-upload. */
class BackupNotUnlocked : IllegalStateException("no backup key — set a backup passphrase first")

/**
 * [BackupCipher] backed by the cached derived key in [BackupKeyManager]. Pulls the
 * derived key (Keystore-backed) and AES-256-GCM-encrypts via [BackupCrypto]. The raw
 * passphrase is never involved here — only the derived key.
 */
class KeyManagerBackupCipher(private val keyManager: BackupKeyManager) : BackupCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = keyManager.derivedKeyOrNull() ?: throw BackupNotUnlocked()
        return BackupCrypto.encrypt(key, plaintext)
    }
}
