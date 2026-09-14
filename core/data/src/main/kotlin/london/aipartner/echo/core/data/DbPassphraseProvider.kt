package london.aipartner.echo.core.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Sources the SQLCipher passphrase. A 256-bit random key is generated ONCE and
 * stored in [EncryptedSharedPreferences], itself encrypted by a key held in the
 * Android Keystore ([MasterKey], AES-256-GCM). The passphrase therefore never
 * exists as a literal in code and is never logged; at rest it is wrapped by a
 * hardware-backed key where the device supports it.
 */
class DbPassphraseProvider(private val context: Context) {

    fun getOrCreate(): ByteArray {
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        prefs.getString(KEY_PASSPHRASE, null)?.let {
            return Base64.decode(it, Base64.NO_WRAP)
        }

        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PASSPHRASE, Base64.encodeToString(fresh, Base64.NO_WRAP))
            .apply()
        return fresh
    }

    private companion object {
        const val PREFS_NAME = "echo_db_keys"
        const val KEY_PASSPHRASE = "db_passphrase"
    }
}
