package london.aipartner.echo.core.sync.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The **client-side re-encryption layer** (Option B, core of the zero-knowledge
 * guarantee). Each artifact is decrypted on-device with the Keystore key, then
 * re-encrypted here with the Argon2id-derived backup key **before** it is handed to
 * any `CloudSink`. The sink only ever sees passphrase-encrypted bytes — no plaintext
 * path to the cloud exists.
 *
 * AES-256-GCM. Wire format: `[12-byte IV][ciphertext+16-byte GCM tag]`. Pure JCE, so
 * it round-trips identically in unit tests (the gate: wrong key fails to decrypt).
 */
object BackupCrypto {

    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    class WrongKeyOrCorruptData(cause: Throwable) :
        IllegalStateException("backup decrypt failed — wrong passphrase or corrupt data", cause)

    /** Encrypt [plaintext] with a 32-byte [key]. Returns `IV || ciphertext||tag`. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plaintext)
    }

    /** Decrypt a `IV || ciphertext||tag` [blob]. A wrong key throws [WrongKeyOrCorruptData]. */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > IV_LENGTH) { "blob too short to contain an IV" }
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val body = blob.copyOfRange(IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BITS, iv))
        }
        return try {
            cipher.doFinal(body)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongKeyOrCorruptData(e)
        }
    }

    private fun keySpec(key: ByteArray): SecretKeySpec {
        require(key.size == BackupKeyDeriver.KEY_LENGTH_BYTES) { "key must be 32 bytes" }
        return SecretKeySpec(key, "AES")
    }
}
