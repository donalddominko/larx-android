package london.aipartner.echo

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.core.transcribe.AndroidDeviceMemory
import london.aipartner.echo.core.transcribe.AudioRef
import london.aipartner.echo.core.transcribe.EchoModelCatalog
import london.aipartner.echo.core.transcribe.EnergyVad
import london.aipartner.echo.core.transcribe.MediaCodecPcmDecoder
import london.aipartner.echo.core.transcribe.MemoryPreGate
import london.aipartner.echo.core.transcribe.ModelProvisioner
import london.aipartner.echo.core.transcribe.ModelSpec
import london.aipartner.echo.core.transcribe.NativeWhisperEngine
import london.aipartner.echo.core.transcribe.OnDeviceTranscriber
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.core.transcribe.TranscribeOpts
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ★ The REUSABLE multi-device model benchmark harness (`references/better-models-pro.md` Decision 7).
 *
 * Plug in ANY phone, run one command (`tools/bench-device.sh`), and this appends structured findings to
 * a committed results file — turning opportunistic device access (borrowed phones, shop handsets) into
 * accumulating, versioned ground-truth data for the two gates and the language×model axis.
 *
 * Per (device × model) — for every ggml model side-loaded into `files/models/`:
 *   - **loads without OOM** (y/n — a survived decode ⇒ yes; an OOM kills the process so the `result`
 *     row is simply absent while the `started` row remains, the honest failure signal)
 *   - **peak PSS** (whole-app, model context + MediaPipe + SQLCipher co-resident — the Gate-1 reality)
 *   - **RTF** (real-time factor on this CPU — the Gate-2 signal)
 *   - **completes under the 12× hang-cap** (y/n — an empty transcript ⇒ aborted, like `small` on the A03)
 *   - the real device snapshot + the **Gate-1 prediction** (so measured reality can be checked against
 *     what the gate would decide)
 *
 * Optional per (device × model × language): drop a `spotcheck-<lang>.wav` (mono 16 kHz PCM-16) into
 * `files/bench/` and each model transcribes it forced to `<lang>`; the transcript head is recorded for a
 * native-speaker accuracy judgment (the ground-truth check that AUTHORIZES a language combo — the CSV
 * only predicts where to look).
 *
 * SIDE-LOAD before running — into the app's INTERNAL storage (SELinux-clean and app-owned; adb-pushed
 * external-storage files are shell-owned and unreadable by the app, and /data/local/tmp is SELinux-denied
 * to apps on Android 13). `tools/bench-device.sh` does this for you, with NO large host stdin stream
 * (that segfaults adb on the big models) — `adb push` (chunked) to /data/local/tmp, then copy in ON-DEVICE:
 *   adb push .tmp/ggml-base.bin /data/local/tmp/x
 *   adb shell "run-as london.aipartner.echo.debug sh -c 'cat > files/models/ggml-base.bin' < /data/local/tmp/x"
 *   adb shell rm /data/local/tmp/x
 *
 * Output: internal `files/bench/model-bench.jsonl` (JSON-lines, appended). `tools/bench-device.sh`
 * pulls it (via `run-as cat`) and merges into `references/device-bench-results.jsonl`. Run offline.
 * NEVER fails on the numbers — it RECORDS them; the only hard failure is finding no model to bench.
 *
 * ⚠ RUN-TO-RUN VARIANCE — take the WORST sample, not a lucky one. Peak PSS and RTF drift between runs
 * (thermal state, scheduler, background load): the same base model on the A03 measured RTF 5.16/582 MB
 * one run and 6.06/591 MB another. For a TIGHT admit/refuse decision — where a single sample sits near a
 * Gate-1 memory or Gate-2 RTF threshold — run the model 2–3× and gate against the WORST observation
 * (highest peak PSS, worst RTF). The gate must be conservative against worst-observed, never admit on one
 * optimistic sample. A comfortably-clear or comfortably-failing model needs only one run.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ModelBenchHarnessTest {

    private val TAG = "EchoBench"
    private val SCHEMA = 1
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var recordingDao: RecordingDao
    @Inject lateinit var audioEncryptor: AudioEncryptor
    @Inject lateinit var semanticIndex: SemanticIndex

    private val touchedRecIds = mutableListOf<String>()
    private lateinit var outFile: File

    // Side-load location = the app's INTERNAL storage (same filesDir/models path production loads base
    // from). Populated via `run-as dd` by tools/bench-device.sh — SELinux-clean and app-owned, unlike
    // adb-pushed external-storage files (shell-owned, unreadable by the app under scoped storage).
    private val modelsDir get() = File(ctx.filesDir, "models")
    private val benchDir get() = File(ctx.filesDir, "bench")

    @Before fun setUp() {
        hiltRule.inject()
        outFile = File(benchDir, "model-bench.jsonl").apply { parentFile?.mkdirs() }
    }

    @After fun tearDown() = runBlocking {
        touchedRecIds.forEach { id ->
            recordingDao.getById(id)?.let { old ->
                old.localAudioRef?.let { runCatching { File(it).delete() } }
                recordingDao.deleteById(id)
            }
        }
    }

    @Test fun benchAllSideLoadedModels(): Unit = runBlocking {
        val device = AndroidDeviceMemory.probe(ctx)
        val deviceJson = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("totalMemMB", device.totalMemBytes / 1_048_576)
            put("lowRamDevice", device.lowRamDevice)
            put("isArm64", device.isArm64)
            put("abis", Build.SUPPORTED_ABIS?.joinToString(","))
            put("gate1BudgetMB", MemoryPreGate.budgetBytes(device) / 1_048_576)
        }
        Log.i(TAG, "device=$deviceJson outFile=${outFile.absolutePath}")

        // Which catalog models are physically present in internal files/models to bench.
        val present: List<Pair<ModelSpec, File>> = EchoModelCatalog.all.mapNotNull { spec ->
            val f = File(modelsDir, spec.ggmlFileName)
            if (f.isFile && f.length() > 1_000_000L) spec to f else null
        }
        Log.i(TAG, "present models: ${present.map { it.first.id }} (dir=$modelsDir)")
        assertTrue(
            "no ggml model side-loaded — push at least one into ${modelsDir.path} (see class KDoc)",
            present.isNotEmpty(),
        )

        // Optional language spot-check fixtures: spotcheck-<lang>.wav in internal files/bench.
        val spotchecks: List<Pair<String, ShortArray>> = (benchDir
            .listFiles { f -> f.name.startsWith("spotcheck-") && f.name.endsWith(".wav") } ?: emptyArray())
            .mapNotNull { f ->
                val lang = f.name.removePrefix("spotcheck-").removeSuffix(".wav").ifBlank { null } ?: return@mapNotNull null
                runCatching { lang to BenchAudio.readWavPcm16Mono(f.readBytes()) }.getOrNull()
            }
        Log.i(TAG, "spot-check languages: ${spotchecks.map { it.first }}")

        // English anchor clip (~60 s) from the test assets, through the real encrypt path.
        val jfk = BenchAudio.readWavPcm16Mono(testCtx.assets.open("jfk.wav").use { it.readBytes() })
        val enPcm = BenchAudio.repeatToAtLeast(jfk, 16_000, targetMs = 60_000)

        for ((spec, file) in present) {
            benchOne(deviceJson, device, spec, file, lang = "en", pcm16k = enPcm, spotcheck = false)
            for ((lang, pcm) in spotchecks) {
                benchOne(deviceJson, device, spec, file, lang = lang, pcm16k = pcm, spotcheck = true)
            }
        }
        // Completion sentinel — lets tools/bench-device.sh detect "done" by polling the FILE (resilient
        // to a dropped adb connection), rather than relying on a live `am instrument -w` staying up.
        appendRow(JSONObject().apply {
            put("schema", SCHEMA); put("phase", "done"); put("ts", System.currentTimeMillis())
            put("benched", JSONObject().put("models", present.joinToString(",") { it.first.id.name }))
        })
        Log.i(TAG, "DONE — appended to ${outFile.absolutePath}")
    }

    private fun benchOne(
        deviceJson: JSONObject,
        device: london.aipartner.echo.core.transcribe.DeviceMemory,
        spec: ModelSpec,
        modelFile: File,
        lang: String,
        pcm16k: ShortArray,
        spotcheck: Boolean,
    ) = runBlocking {
        val audioMs = pcm16k.size * 1000L / 16_000
        val gate1Pass = MemoryPreGate.passes(spec, device)
        // `started` marker BEFORE loading — if the model OOM-kills the process, this row survives while
        // the `result` row never lands, which is exactly how we detect "attempted but couldn't hold it".
        appendRow(baseRow(deviceJson, spec, lang, spotcheck).apply {
            put("phase", "started"); put("audioMs", audioMs); put("gate1Predicted", gate1Pass)
        })

        val recId = "bench-${spec.id}-${lang}-${if (spotcheck) "sc" else "en"}"
        touchedRecIds += recId

        // Encode → encrypt → decrypt (touch SQLCipher-backed crypto + DAO), mirroring real coexistence.
        val pcm44k = BenchAudio.upsample(pcm16k, 16_000, 44_100)
        val m4a = File(ctx.cacheDir, "$recId.m4a")
        BenchAudio.encodeAacMp4(pcm44k, 44_100, m4a)
        val encrypted = audioEncryptor.encryptFrom(m4a, "$recId.m4a")
        recordingDao.insert(
            RecordingEntity(
                id = recId, createdAt = 1_000L, durationMs = audioMs,
                captureSource = "MIC", fidelity = "HD", captureMode = "ON_DEMAND",
                contactHash = null, localAudioRef = encrypted.absolutePath,
                encryptionMeta = "codec=AAC", syncState = "LOCAL_ONLY",
                transcriptionStatus = TranscriptionStatus.PENDING.name,
            ),
        )
        val decrypted = audioEncryptor.decryptToTemp(encrypted)

        // Warm MediaPipe so the peak reflects the real Phase-5 coexistence (whisper + USE + SQLCipher).
        semanticIndex.query("warmup query", k = 1)
        Runtime.getRuntime().gc()
        val baselinePss = Debug.getPss()

        val engine = NativeWhisperEngine()
        if (!engine.isAvailable()) {
            appendRow(baseRow(deviceJson, spec, lang, spotcheck).apply {
                put("phase", "result"); put("error", "native whisper lib unavailable (non-arm64?)")
            })
            return@runBlocking
        }
        val transcriber = OnDeviceTranscriber(
            decoder = MediaCodecPcmDecoder(),
            vad = EnergyVad(),
            engine = engine,
            modelProvisioner = fixedProvisioner(modelFile.absolutePath),
        )

        var peakPss = 0L
        var segments = 0
        var textHead = ""
        val decodeMs = kotlin.system.measureTimeMillis {
            val transcript = transcriber.transcribe(AudioRef(decrypted.absolutePath), TranscribeOpts(languageTag = lang))
            peakPss = Debug.getPss() // model context still resident here
            segments = transcript.segments.size
            textHead = transcript.segments.joinToString(" ") { it.text }.trim().take(240)
        }
        engine.release()
        Runtime.getRuntime().gc()
        val afterReleasePss = Debug.getPss()

        val rtf = decodeMs.toDouble() / audioMs.coerceAtLeast(1)
        val completedUnderCap = segments > 0 // native aborts at 12× → empty transcript

        val row = baseRow(deviceJson, spec, lang, spotcheck).apply {
            put("phase", "result")
            put("audioMs", audioMs)
            put("decodeMs", decodeMs)
            put("rtf", "%.3f".format(rtf).toDouble())
            put("loadsWithoutOom", true) // reached here ⇒ process survived the load+decode
            put("completesUnderHangCap", completedUnderCap)
            put("segments", segments)
            put("baselinePssKB", baselinePss)
            put("peakPssKB", peakPss)
            put("afterReleasePssKB", afterReleasePss)
            put("gate1Predicted", gate1Pass)
            put("gate1BudgetMB", MemoryPreGate.budgetBytes(device) / 1_048_576)
            put("modelPeakAnchorMB", spec.peakMemAnchorBytes / 1_048_576)
            put("gate2RtfThresholdMax", spec.gate2RtfThresholdMax)
            // For a spot-check, the transcript is for a HUMAN native-speaker judgment (not auto-scored).
            put("transcriptHead", textHead)
        }
        appendRow(row)
        Log.i(TAG, "MODEL=${spec.id} lang=$lang spotcheck=$spotcheck rtf=%.2f peakPss=%dKB segs=%d completed=%b"
            .format(rtf, peakPss, segments, completedUnderCap))
    }

    private fun baseRow(deviceJson: JSONObject, spec: ModelSpec, lang: String, spotcheck: Boolean) =
        JSONObject().apply {
            put("schema", SCHEMA)
            put("ts", System.currentTimeMillis())
            put("device", deviceJson)
            put("modelId", spec.id.name)
            put("modelFile", spec.ggmlFileName)
            put("lang", lang)
            put("spotcheck", spotcheck)
        }

    private fun appendRow(row: JSONObject) {
        outFile.appendText(row.toString() + "\n")
    }

    private fun fixedProvisioner(path: String) = object : ModelProvisioner {
        override fun isModelReady() = true
        override fun modelPathOrNull() = path
        override suspend fun ensureModel() = path
    }
}
