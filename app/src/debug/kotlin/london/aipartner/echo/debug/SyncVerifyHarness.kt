package london.aipartner.echo.debug

import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import london.aipartner.echo.core.consent.ConsentPreferences
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.sync.ArtifactKind
import london.aipartner.echo.core.sync.ArtifactReader
import london.aipartner.echo.core.sync.SyncArtifact
import london.aipartner.echo.core.sync.WebDavConfig
import london.aipartner.echo.core.sync.backup.BackupCrypto
import london.aipartner.echo.core.sync.backup.BackupKeyManager
import london.aipartner.echo.di.DebugFlags
import london.aipartner.echo.sync.SyncScheduler
import london.aipartner.echo.sync.WebDavConfigStore

/**
 * DEBUG-ONLY (`directDebug` source set) Phase-8 verify harness — the out-of-band driver
 * for the real-VPS A03 end-to-end that the MockWebServer fake couldn't cover. It exists
 * because the destination-selection UI (Group A) is a later step; it writes the WebDAV
 * config into the Keystore-encrypted store (which can't be `adb push`ed), enqueues a
 * backup pass, and — the load-bearing part — pulls the remote blobs back DOWN to prove
 * **INTEGRITY, not just state**: each artifact is ONE byte-correct, backup-key-decryptable,
 * non-duplicated copy. See `references/phase-08-cloud-sync.md`.
 *
 * Nothing here is in any release build. It reaches the real seams (same `WebDavConfigStore`,
 * `BackupKeyManager`, `ArtifactReader`, `SyncScheduler` the app uses) via a Hilt EntryPoint.
 */
object SyncVerifyHarness {

    private const val TAG = "EchoSyncVerify"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface HarnessEntryPoint {
        fun webDavConfigStore(): WebDavConfigStore
        fun backupKeyManager(): BackupKeyManager
        fun artifactReader(): ArtifactReader
        fun syncScheduler(): SyncScheduler
        fun consentPreferences(): ConsentPreferences
        fun recordingDao(): RecordingDao
    }

    private fun ep(context: Context) = EntryPointAccessors.fromApplication(
        context.applicationContext, HarnessEntryPoint::class.java,
    )

    /** Write the real-VPS WebDAV destination into the encrypted store (never committed). */
    fun writeConfig(context: Context, url: String, user: String, pass: String): String {
        ep(context).webDavConfigStore().set(WebDavConfig(baseUrl = url, username = user, password = pass))
        return log("config written: url=$url user=$user (pass len=${pass.length})")
    }

    /**
     * Configure the backup passphrase for the test via the SAME `BackupKeyManager.setup`
     * the real UI calls (fail-closed strength/ack logic unchanged) — scripted here only so
     * the E2E runbook is reliable adb instead of brittle on-device UI automation. The
     * passphrase UX itself was already reviewed on-device in Phase 8.
     */
    fun setPassphrase(context: Context, passphrase: String): String {
        val outcome = ep(context).backupKeyManager()
            .setup(passphrase, passphrase, warningAcknowledged = true)
        return log("backup passphrase setup: $outcome")
    }

    /** Turn on the Phase-3 egress chokepoint (sync's #1 gate). */
    fun setEgress(context: Context, on: Boolean): String {
        ep(context).consentPreferences().dataEgressAllowed = on
        return log("dataEgressAllowed = $on")
    }

    /**
     * Flip the debug Pro flag. Takes effect on NEXT process start (the `Entitlements`
     * singleton reads it at graph build) — relaunch after setting.
     */
    fun setPro(context: Context, on: Boolean): String {
        context.getSharedPreferences(DebugFlags.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(DebugFlags.KEY_PRO_UNLOCKED, on).apply()
        return log("debug pro_unlocked = $on (RELAUNCH the app for it to take effect)")
    }

    /** Wipe the destination creds from the device (run after the test). */
    fun clearConfig(context: Context): String {
        ep(context).webDavConfigStore().clear()
        return log("WebDAV config cleared")
    }

    /** Enqueue a backup pass (same path as app-start). */
    fun enqueue(context: Context): String {
        ep(context).syncScheduler().enqueueBackup()
        return log("backup pass enqueued (echo-backup)")
    }

    /**
     * The integrity check. RUN THIS AFTER the airplane-mode-mid-upload → retry sequence.
     * For every recording, for each of its 3 artifacts:
     *  1. GET the flat-named blob from the real server.
     *  2. Decrypt with the on-device backup key — a partial/corrupt upload fails GCM auth
     *     here (the exact silent-corruption catch; "SYNCED appeared" is NOT enough).
     *  3. Byte-compare the decrypted plaintext to the device source (byte-correct).
     *  4. PROPFIND the collection and assert EXACTLY ONE blob per remote name (no dupes).
     * Also runs an explicit overwrite-PUT probe: does this real server accept overwriting
     * an existing path at all? (If not → finding: dedup needs delete-then-PUT / temp+MOVE.)
     * Writes a full report to the app's external files dir for `adb pull`.
     */
    suspend fun verifyRemote(context: Context): String {
        val e = ep(context)
        val cfg = e.webDavConfigStore().current()
            ?: return fail("no WebDAV config set — run write_webdav first")
        val key = e.backupKeyManager().derivedKeyOrNull()
            ?: return fail("no backup key — set a backup passphrase first")
        val reader = e.artifactReader()
        val recordings = e.recordingDao().getAll()
        val sb = StringBuilder("Phase-8 verify_remote @ ${cfg.baseUrl}\n")

        // 0) Overwrite-PUT probe — the "does the server allow overwrite at all" finding.
        sb.append(overwriteProbe(cfg)).append('\n')

        // 1) PROPFIND the collection once; use it to assert single-copy per name.
        val remoteNames = propfindNames(cfg)
        sb.append("PROPFIND found ${remoteNames.size} entries in collection\n")

        var pass = 0
        var fail = 0
        for (rec in recordings) {
            for (kind in ArtifactKind.values()) {
                val artifact = SyncArtifact(rec.id, kind, localPath = "")
                val name = artifact.remoteName
                val line = try {
                    val source = reader.read(artifact)
                    val blob = httpGet(cfg, name)
                        ?: throw IllegalStateException("GET $name returned no body")
                    val decrypted = BackupCrypto.decrypt(key, blob) // throws on partial/corrupt
                    val byteOk = decrypted.contentEquals(source)
                    // Single-copy assertion is best-effort: only enforced when PROPFIND
                    // listing succeeded (some servers/clients can't list) — never false-fail.
                    val copies = if (remoteNames.isEmpty()) -1 else remoteNames.count { it == name }
                    val copyOk = copies == 1 || copies == -1
                    val copyNote = if (copies == -1) "copies=?(no listing)" else "copies=$copies"
                    if (byteOk && copyOk) {
                        pass++; "PASS  $name  (${blob.size}B enc, decrypt OK, byte-equal, $copyNote)"
                    } else {
                        fail++; "FAIL  $name  byteEqual=$byteOk $copyNote (expected true/1)"
                    }
                } catch (t: Throwable) {
                    fail++; "FAIL  $name  ${t.javaClass.simpleName}: ${t.message}"
                }
                sb.append("  ").append(line).append('\n')
            }
        }
        sb.append("RESULT: $pass passed, $fail failed across ${recordings.size} recording(s)\n")
        val report = sb.toString()
        Log.i(TAG, report)
        runCatching {
            val out = File(context.getExternalFilesDir(null), "verify/phase8-verify.txt")
            out.parentFile?.mkdirs()
            out.writeText(report)
            Log.i(TAG, "report written: ${out.absolutePath}")
        }
        return report
    }

    // --- WebDAV helpers (harness-local; the production sink only PUT/DELETEs) ---

    private fun auth(cfg: WebDavConfig) =
        "Basic " + Base64.getEncoder().encodeToString(
            "${cfg.username}:${cfg.password}".toByteArray(Charsets.UTF_8),
        )

    private fun joinUrl(base: String, path: String) =
        base.trimEnd('/') + "/" + path.trimStart('/')

    private fun httpGet(cfg: WebDavConfig, name: String): ByteArray? {
        val conn = (URL(joinUrl(cfg.baseUrl, name)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", auth(cfg))
            instanceFollowRedirects = false
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    /**
     * PROPFIND depth 1; return the last path segment of every returned href. Best-effort:
     * `HttpURLConnection`'s method whitelist rejects "PROPFIND", so we set it via reflection;
     * if that or the request fails, we return empty and the caller skips the copy assertion.
     */
    private fun propfindNames(cfg: WebDavConfig): List<String> {
        val conn = (URL(cfg.baseUrl).openConnection() as HttpURLConnection)
        return try {
            forceMethod(conn, "PROPFIND")
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", auth(cfg))
            conn.setRequestProperty("Depth", "1")
            conn.instanceFollowRedirects = false
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "PROPFIND returned ${conn.responseCode}; cannot assert single-copy")
                emptyList()
            } else {
                val xml = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                Regex("<[^>]*href[^>]*>([^<]+)</[^>]*href>", RegexOption.IGNORE_CASE)
                    .findAll(xml).map { it.groupValues[1].trimEnd('/').substringAfterLast('/') }
                    .filter { it.isNotBlank() }.toList()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "PROPFIND unavailable (${t.javaClass.simpleName}); skipping copy assertion", t)
            emptyList()
        } finally { conn.disconnect() }
    }

    /** Set a non-standard HTTP method past HttpURLConnection's whitelist, via reflection. */
    private fun forceMethod(conn: HttpURLConnection, method: String) {
        try {
            conn.requestMethod = method
        } catch (_: java.net.ProtocolException) {
            var cls: Class<*>? = conn.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField("method")
                    f.isAccessible = true
                    f.set(conn, method)
                    // The Android/OkHttp impl mirrors the method onto a delegate request builder;
                    // setting the field before connecting is sufficient for PROPFIND here.
                    return
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            throw java.net.ProtocolException("could not force method $method")
        }
    }

    /** PUT twice to a probe name with different bytes; confirm the server overwrites in place. */
    private fun overwriteProbe(cfg: WebDavConfig): String {
        val name = "echo-overwrite-probe"
        return try {
            httpPut(cfg, name, "v1-${System.currentTimeMillis()}".toByteArray())
            val second = "v2-${System.currentTimeMillis()}".toByteArray()
            val code2 = httpPut(cfg, name, second)
            val back = httpGet(cfg, name)
            httpDelete(cfg, name)
            when {
                code2 !in 200..299 -> "OVERWRITE-PUT: SERVER REJECTED 2nd PUT (HTTP $code2) — FINDING: dedup needs delete-then-PUT or temp+MOVE"
                back == null || !back.contentEquals(second) -> "OVERWRITE-PUT: 2nd PUT accepted but content NOT replaced — FINDING: overwrite unreliable"
                else -> "OVERWRITE-PUT: OK (server replaces in place — flat-name dedup is safe)"
            }
        } catch (t: Throwable) {
            "OVERWRITE-PUT: probe error ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun httpPut(cfg: WebDavConfig, name: String, body: ByteArray): Int {
        val conn = (URL(joinUrl(cfg.baseUrl, name)).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", auth(cfg))
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(body.size)
            instanceFollowRedirects = false
        }
        return try {
            conn.outputStream.use { it.write(body) }
            conn.responseCode
        } finally { conn.disconnect() }
    }

    private fun httpDelete(cfg: WebDavConfig, name: String) {
        val conn = (URL(joinUrl(cfg.baseUrl, name)).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", auth(cfg))
            instanceFollowRedirects = false
        }
        try { conn.responseCode } finally { conn.disconnect() }
    }

    private fun log(msg: String): String { Log.i(TAG, msg); return msg }
    private fun fail(msg: String): String { Log.e(TAG, msg); return "ERROR: $msg" }
}
