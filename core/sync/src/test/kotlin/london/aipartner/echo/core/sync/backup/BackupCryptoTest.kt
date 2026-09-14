package london.aipartner.echo.core.sync.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zero-knowledge re-encryption gate (Option B): the uploaded payload contains no
 * plaintext and round-trips ONLY with the Argon2id passphrase key — a wrong
 * passphrase fails. Proven end-to-end from passphrase → derived key → ciphertext.
 */
class BackupCryptoTest {

    private val salt = ByteArray(BackupKeyDeriver.SALT_LENGTH_BYTES) { it.toByte() }
    private val plaintext = "Meeting notes: ship Phase 8 backup — passphrase is unrecoverable.".toByteArray()

    @Test fun roundTrips_withCorrectPassphrase() {
        val key = BackupKeyDeriver.deriveKey("correct horse battery".toCharArray(), salt)
        val blob = BackupCrypto.encrypt(key, plaintext)
        assertArrayEquals(plaintext, BackupCrypto.decrypt(key, blob))
    }

    @Test fun ciphertext_containsNoPlaintext() {
        val key = BackupKeyDeriver.deriveKey("correct horse battery".toCharArray(), salt)
        val blob = BackupCrypto.encrypt(key, plaintext)
        // No contiguous plaintext token survives in the encrypted blob.
        val haystack = String(blob, Charsets.ISO_8859_1)
        assertFalse(haystack.contains("Meeting"))
        assertFalse(haystack.contains("passphrase"))
        assertFalse(blob.asList().windowed(plaintext.size).any { it.toByteArray().contentEquals(plaintext) })
    }

    @Test fun wrongPassphrase_failsToDecrypt() {
        val right = BackupKeyDeriver.deriveKey("correct horse battery".toCharArray(), salt)
        val wrong = BackupKeyDeriver.deriveKey("wrong horse battery".toCharArray(), salt)
        val blob = BackupCrypto.encrypt(right, plaintext)
        assertThrows(BackupCrypto.WrongKeyOrCorruptData::class.java) { BackupCrypto.decrypt(wrong, blob) }
    }

    @Test fun differentPassphrases_deriveDifferentKeys() {
        val a = BackupKeyDeriver.deriveKey("correct horse battery".toCharArray(), salt)
        val b = BackupKeyDeriver.deriveKey("correct horse batteryX".toCharArray(), salt)
        assertFalse(a.contentEquals(b))
    }

    @Test fun sameInputs_deriveSameKey() {
        val a = BackupKeyDeriver.deriveKey("correct horse battery".toCharArray(), salt)
        val b = BackupKeyDeriver.deriveKey("correct horse battery".toCharArray(), salt)
        assertTrue(a.contentEquals(b))
    }

    private fun List<Byte>.toByteArray() = ByteArray(size) { this[it] }
}
