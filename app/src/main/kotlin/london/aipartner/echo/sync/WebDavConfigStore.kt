package london.aipartner.echo.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import london.aipartner.echo.core.sync.WebDavConfig

/**
 * On-device store for the user's WebDAV backup destination (URL + credentials). The
 * password is an app-specific credential the user enters; like the DB passphrase and the
 * backup key it lives ONLY in Keystore-backed [EncryptedSharedPreferences] — never in
 * plain prefs, never committed, never uploaded.
 *
 * **No real credentials are baked in.** Until the destination-selection UI lands (Group A,
 * a later step) this is populated out-of-band for the real-VPS A03 end-to-end (the same
 * adb-write pattern used to test the language override), then the on-device Settings flow
 * writes it. Empty by default ⇒ [current] is null ⇒ the sync worker does nothing.
 */
@Singleton
class WebDavConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "echo_webdav_dest",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The configured destination, or null when none is set (⇒ no sync attempted). */
    fun current(): WebDavConfig? {
        val url = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        return WebDavConfig(baseUrl = url, username = user, password = pass)
    }

    fun set(config: WebDavConfig) {
        prefs.edit()
            .putString(KEY_URL, config.baseUrl)
            .putString(KEY_USER, config.username)
            .putString(KEY_PASS, config.password)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_URL = "webdav_base_url"
        const val KEY_USER = "webdav_username"
        const val KEY_PASS = "webdav_password"
    }
}
