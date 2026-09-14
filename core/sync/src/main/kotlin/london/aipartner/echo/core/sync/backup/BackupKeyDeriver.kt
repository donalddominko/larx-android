package london.aipartner.echo.core.sync.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom

/**
 * Derives the **user-held backup key** from the passphrase via Argon2id (Option B,
 * zero-knowledge). The key is independent of the device Keystore master key, so a
 * backup is decryptable only by someone who knows the passphrase — never by Google
 * or a WebDAV/VPS host.
 *
 * Pure-JVM BouncyCastle: no JNI, so it derives byte-identically in unit tests and
 * on-device (and adds no second ABI to the arm64-only APK).
 */
object BackupKeyDeriver {

    const val KEY_LENGTH_BYTES = 32 // AES-256
    const val SALT_LENGTH_BYTES = 16

    // Argon2id parameters — a phone-interactive profile. 64 MB / 3 passes / 2 lanes
    // is a well-established mobile default (costly to brute-force, ~sub-second on the
    // A03). The salt is stored alongside the backup (public); the passphrase is not.
    private const val ITERATIONS = 3
    private const val MEMORY_KB = 64 * 1024
    private const val PARALLELISM = 2

    /** A fresh random 16-byte salt for a first-time setup. */
    fun newSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Derive the 32-byte backup key. [passphrase] is a `CharArray` so the caller can
     * zero it after use; BouncyCastle encodes it as UTF-8 internally.
     */
    fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == SALT_LENGTH_BYTES) { "salt must be $SALT_LENGTH_BYTES bytes" }
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ITERATIONS)
            .withMemoryAsKB(MEMORY_KB)
            .withParallelism(PARALLELISM)
            .withSalt(salt)
            .build()
        val out = ByteArray(KEY_LENGTH_BYTES)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(passphrase, out)
        return out
    }
}
