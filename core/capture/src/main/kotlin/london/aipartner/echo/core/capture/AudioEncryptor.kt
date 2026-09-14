package london.aipartner.echo.core.capture

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Encrypts captured audio at rest. The recorder writes a plaintext temp file
 * (MediaRecorder needs a real seekable path), which is then encrypted into
 * app-private storage with Jetpack Security [EncryptedFile] (AES-256-GCM, keyed
 * by a Keystore [MasterKey] — the same hardware-backed key strategy as Phase 1's
 * DB passphrase) and the temp deleted. The DB stores only the encrypted path.
 */
class AudioEncryptor(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    val recordingsDir: File
        get() = File(context.filesDir, "recordings").apply { mkdirs() }

    /** Encrypts [temp] into the recordings dir as [name], deletes [temp], returns the dest. */
    fun encryptFrom(temp: File, name: String): File {
        val dest = File(recordingsDir, name)
        if (dest.exists()) dest.delete() // EncryptedFile refuses to overwrite
        val enc = encryptedFile(dest)
        enc.openFileOutput().use { out -> temp.inputStream().use { it.copyTo(out) } }
        temp.delete()
        return dest
    }

    /** Decrypts an encrypted recording to a fresh temp file (playback / verification). */
    fun decryptToTemp(encrypted: File): File {
        val temp = File.createTempFile("dec_", ".media", context.cacheDir)
        val enc = encryptedFile(encrypted)
        enc.openFileInput().use { input -> temp.outputStream().use { input.copyTo(it) } }
        return temp
    }

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
}
