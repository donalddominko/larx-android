package london.aipartner.echo.debug

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.capture.CaptureJob
import london.aipartner.echo.core.capture.RecordingService
import london.aipartner.echo.core.consent.CaptureMode

/**
 * DEBUG-ONLY Gate-B trigger. Lives in the `directDebug` source set — it is in NO
 * `play` build and NO release build, mirroring the flavor-source-set discipline
 * used for PrivilegedCallRecorder.
 *
 * It hits the REAL [RecordingService.startIntent] path: ConsentGate stays intact
 * (the permissive stub still DENYs without RECORD_AUDIO), so this adds no
 * consent-skipping shortcut. It requests RECORD_AUDIO exactly like a real
 * recording would; without the grant, the service starts, the gate DENYs, and no
 * file is produced.
 */
class DebugRecordActivity : Activity() {

    private lateinit var status: TextView
    private var seedInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()

        status = TextView(this).apply {
            text = "Idle. The recording indicator is a silent ongoing notification\n" +
                "(pull down the shade). v1 records voice memos (mic); call recording\n" +
                "was dropped — see capture-strategy.md."
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(TextView(this@DebugRecordActivity).apply {
                text = "Echo — DEBUG capture trigger (directDebug only)"
            })
            addView(button("Start MEMO recording") { startCapture(CaptureJob.MEMO) })
            addView(button("Stop recording") { stopCapture() })
            addView(button("Export newest (decrypted) to external") { exportNewest() })
            addView(button("Seed karaoke DEMO recording") { seedDemo() })
            addView(status)
        }
        setContentView(layout)

        // Fallback for the adb recipe when starting the FGS directly is blocked:
        // `am start -n .../DebugRecordActivity --es autostart MEMO` launches this
        // foreground activity, which then starts the FGS from the app's own uid.
        handleExtras(intent)
    }

    // The activity is single-task in practice (launcher); a re-delivered intent (e.g.
    // the adb `--es seed demo` recipe) arrives here, not onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExtras(intent)
    }

    private fun handleExtras(intent: Intent) {
        if (intent.getStringExtra("autostart") == "MEMO") startCapture(CaptureJob.MEMO)
        if (intent.getStringExtra("export") != null) exportNewest()
        if (intent.getStringExtra("seed") != null) seedDemo()
        handleSyncVerifyExtras(intent)
    }

    /**
     * DEBUG-ONLY Phase-8 verify harness triggers (directDebug only). Drives the real-VPS
     * A03 end-to-end out-of-band because the destination-selection UI is a later step.
     * `--es write_webdav 1 --es url <URL> --es user <U> --es pass <P>` writes the encrypted
     * destination; `--es set_egress on|off`, `--es set_pro on|off` (relaunch after),
     * `--es enqueue 1`, `--es verify_remote 1` (run AFTER airplane-mode-mid-upload→retry),
     * `--es clear_webdav 1`.
     */
    private fun handleSyncVerifyExtras(intent: Intent) {
        intent.getStringExtra("write_webdav")?.let {
            val url = intent.getStringExtra("url").orEmpty()
            val user = intent.getStringExtra("user").orEmpty()
            val pass = intent.getStringExtra("pass").orEmpty()
            if (url.isBlank() || user.isBlank() || pass.isBlank()) {
                setStatus("write_webdav: need --es url/--es user/--es pass")
            } else {
                setStatus(SyncVerifyHarness.writeConfig(this, url, user, pass))
            }
        }
        intent.getStringExtra("set_passphrase")?.let { setStatus(SyncVerifyHarness.setPassphrase(this, it)) }
        intent.getStringExtra("set_egress")?.let { setStatus(SyncVerifyHarness.setEgress(this, it == "on")) }
        intent.getStringExtra("set_pro")?.let { setStatus(SyncVerifyHarness.setPro(this, it == "on")) }
        intent.getStringExtra("enqueue")?.let { setStatus(SyncVerifyHarness.enqueue(this)) }
        intent.getStringExtra("clear_webdav")?.let { setStatus(SyncVerifyHarness.clearConfig(this)) }
        intent.getStringExtra("verify_remote")?.let {
            setStatus("verify_remote running… (see logcat EchoSyncVerify + pulled report)")
            CoroutineScope(Dispatchers.IO).launch {
                val report = SyncVerifyHarness.verifyRemote(this@DebugRecordActivity)
                runOnUiThread { setStatus(report.lines().lastOrNull { it.isNotBlank() } ?: report) }
            }
        }
    }

    private fun setStatus(msg: String) {
        if (::status.isInitialized) runOnUiThread { status.text = msg }
    }

    /**
     * DEBUG-ONLY: decrypts the newest recording to the app's external files dir so
     * it can be pulled with `adb pull` and listened to. Uses the same on-device
     * Keystore-backed key as capture; the at-rest file stays encrypted.
     */
    private fun exportNewest() {
        val enc = AudioEncryptor(this)
        val newest = enc.recordingsDir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
        if (newest == null) { toast("No recordings to export"); return }
        val decrypted = enc.decryptToTemp(newest)
        val outDir = File(getExternalFilesDir(null), "export").apply { mkdirs() }
        val out = File(outDir, newest.name)
        decrypted.copyTo(out, overwrite = true)
        decrypted.delete()
        status.text = "Exported decrypted ${newest.name} (${out.length()} bytes) to\n${out.absolutePath}"
        toast("Exported ${newest.name}")
    }

    /**
     * DEBUG-ONLY: seeds one demo recording (real audible tone audio + matching segment
     * timings + title/summary) so the karaoke highlight/auto-scroll/tap-to-seek can be
     * felt before transcription-on-record-stop exists (Step 4). Open it from the Library.
     */
    private fun seedDemo() {
        if (seedInFlight) return // guard against onCreate+onNewIntent double-fire
        seedInFlight = true
        status.text = "Seeding demo recording…"
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { DemoSeeder.seed(this@DebugRecordActivity) }
            runOnUiThread {
                seedInFlight = false
                result.fold(
                    onSuccess = {
                        android.util.Log.i("EchoDemo", "Seeded $it demo recordings")
                        status.text = "Seeded $it demo recordings.\n" +
                            "Open the Library (main launcher) to see them."
                        toast("Seeded $it demo recordings — open the Library")
                    },
                    onFailure = {
                        android.util.Log.e("EchoDemo", "Demo seed FAILED", it)
                        status.text = "Demo seed FAILED: ${it.message}"
                        toast("Seed failed: ${it.message}")
                    },
                )
            }
        }
    }

    private fun startCapture(job: CaptureJob) {
        // The real, gated path — same intent the production UI will send.
        ContextCompat.startForegroundService(
            this, RecordingService.startIntent(this, job, CaptureMode.ON_DEMAND),
        )
        toast("Started $job — check the notification shade")
        status.text = "Recording ($job)… see the ongoing notification. Tap Stop to finish."
    }

    private fun stopCapture() {
        startService(
            Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )
        toast("Stopped — saved (encrypted)")
        status.text = "Stopped. Recording saved to app-private storage (encrypted)."
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun requestPermissionsIfNeeded() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@DebugRecordActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@DebugRecordActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }
}
