package london.aipartner.echo.core.transcribe

/**
 * Provisions the on-device whisper `base` weights.
 *
 * ### Model download is NOT audio egress (do not re-litigate — Phase 4)
 * Fetching the weights moves a **model file ONTO the device** — assets coming
 * *in*, app-infrastructure. `ConsentPreferences.dataEgressAllowed` governs **user
 * audio going OUT**. Different directions, different consents. This component
 * therefore has — and must keep — **no reference** to the egress consent, to
 * `Entitlements`, or to any user audio. A future change that gates model download
 * behind `dataEgressAllowed` is a bug. The egress-distinction test guards this.
 *
 * ### Delivery: INSTALL-TIME asset pack (revised Phase 10, 2026-07-18)
 * The ~142 MB `base` weights ship as an **install-time** Play Asset Delivery pack
 * (`:whisper_base_assets`) — present at first launch, **no runtime download, no
 * `INTERNET` permission**. This was revised from the original on-demand plan because
 * v1 ships with **no `INTERNET`** (OS-enforced no-egress, the privacy USP) and the
 * store promises **offline / airplane-mode** transcription: an on-demand download
 * would fail on a device that's offline at first run, and would re-couple the app to
 * `INTERNET`. Install-time delivery is the only mode consistent with both.
 * [InstallTimeAssetModelProvisioner] copies the pack's asset to a real file path on
 * first run (whisper.cpp mmaps by path; asset-pack assets are stream-only).
 *
 * ### Future: on-demand PAD for the LARGER models (better-models ladder)
 * [PlayAssetDeliveryModelProvisioner] (kept below, still a stub) is the on-demand
 * path for the `small`/`medium` ladder — that IS a runtime download and, if the
 * asset-delivery lib needs `INTERNET`, couples to the Data-Safety/privacy gate. See
 * `references/better-models-pro.md` Decision 5-PRE. PAD is Play-only; a future
 * F-Droid `foss` flavor needs a non-PAD path. Noted, not solved here.
 */
interface ModelProvisioner {
    /** Whether the weights are already present locally and integrity-verified. */
    fun isModelReady(): Boolean

    /** Local filesystem path to the verified `base` weights, or null if not ready. */
    fun modelPathOrNull(): String?

    /**
     * Ensure the weights are present, fetching the on-demand asset pack if needed.
     * Returns the verified local path. Suspends while downloading. Touches the
     * network for the MODEL ONLY — never user content.
     */
    suspend fun ensureModel(): String
}

/** Raised when the model can't be provisioned (download failed / integrity fail). */
class ModelUnavailable(message: String) : IllegalStateException(message)

/**
 * Resolves the weights from a **known local file first**, delegating to [fallback]
 * (Play Asset Delivery) only when the local copy is absent. This is the stable path
 * the on-device transcriber actually loads from: PAD's job is to *place* the verified
 * file at [modelFile], and a future F-Droid `foss` flavor (no PAD) bundles/side-loads
 * the same file. Until the PAD AAB wiring lands (Phase 9/10), the local file is also
 * how a debug build gets the model on-device (push it once via adb) so the on-device
 * transcription path is exercisable now.
 *
 * Integrity is enforced: a present file must match [expectedSha256] or it is rejected
 * (a corrupt/truncated model yields plausible garbage, not an error). The verified
 * path is cached so the SHA-256 of the ~142 MB file is computed at most once per process.
 *
 * No reference to egress consent / Entitlements / user audio — model-in, not audio-out.
 */
class FileSystemModelProvisioner(
    private val modelFile: java.io.File,
    private val expectedSha256: String,
    private val fallback: ModelProvisioner,
) : ModelProvisioner {

    @Volatile private var verifiedPath: String? = null

    override fun isModelReady(): Boolean = verifiedPath != null || modelFile.isFile

    override fun modelPathOrNull(): String? = verifiedPath ?: fallback.modelPathOrNull()

    override suspend fun ensureModel(): String {
        verifiedPath?.let { return it }
        if (modelFile.isFile && modelFile.length() > 0) {
            val actual = sha256(modelFile)
            if (actual.equals(expectedSha256, ignoreCase = true)) {
                return modelFile.absolutePath.also { verifiedPath = it }
            }
            throw ModelUnavailable(
                "local model at ${modelFile.absolutePath} failed integrity check " +
                    "(sha=$actual, expected=$expectedSha256)",
            )
        }
        // No local copy — defer to Play Asset Delivery (throws until the AAB is wired).
        return fallback.ensureModel()
    }

    private fun sha256(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Install-time asset-pack provisioner for the `base` weights (Phase 10, 2026-07-18).
 *
 * The weights ride along in the `:whisper_base_assets` **install-time** PAD pack, so
 * they are present at first launch with no download and no `INTERNET`. Asset-pack
 * assets are only readable as a *stream* (`AssetManager.open`), but whisper.cpp loads
 * by **file path** (mmap), so on first use this copies the asset **once** to
 * [target] (`filesDir/models/ggml-base.bin` — the very path [FileSystemModelProvisioner]
 * checks first) and SHA-verifies it. Subsequent launches hit the local file directly.
 *
 * Context-free by design: the app supplies [openAsset] as `{ context.assets.open(name) }`,
 * keeping `:core:transcribe` off the Android `Context`. No egress / entitlement /
 * user-audio reference — model-in only (the Phase-4 boundary holds).
 */
class InstallTimeAssetModelProvisioner(
    private val openAsset: () -> java.io.InputStream,
    private val target: java.io.File,
    private val expectedSha256: String,
) : ModelProvisioner {

    @Volatile private var verifiedPath: String? = null

    override fun isModelReady(): Boolean = verifiedPath != null || target.isFile

    override fun modelPathOrNull(): String? = verifiedPath ?: target.takeIf { it.isFile }?.absolutePath

    override suspend fun ensureModel(): String {
        verifiedPath?.let { return it }
        // Already copied out on a previous run?
        if (target.isFile && target.length() > 0 && sha256(target).equals(expectedSha256, ignoreCase = true)) {
            return target.absolutePath.also { verifiedPath = it }
        }
        // First run (or a corrupt/partial prior copy): stream the pack asset to a temp
        // file, verify, then atomically rename into place — never leave a half-written model.
        target.parentFile?.mkdirs()
        val tmp = java.io.File(target.parentFile, "${target.name}.part")
        tmp.delete()
        openAsset().use { input -> tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) } }
        val actual = sha256(tmp)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            tmp.delete()
            throw ModelUnavailable(
                "install-time asset model failed integrity check (sha=$actual, expected=$expectedSha256)",
            )
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true); tmp.delete()
        }
        return target.absolutePath.also { verifiedPath = it }
    }

    private fun sha256(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Play Asset Delivery implementation.
 *
 * Wraps the Play `AssetPackManager` for the on-demand `whisper_base` pack and
 * verifies the SHA-256 of the delivered weights before reporting ready. The PAD
 * asset-pack module and AAB wiring are assembled in the release build (separate
 * from this Kotlin seam) — see PROGRESS.md "remaining native/Play work". Kept as
 * the documented integration point so the rest of the pipeline builds against a
 * stable interface.
 */
class PlayAssetDeliveryModelProvisioner(
    private val expectedSha256: String,
) : ModelProvisioner {

    @Volatile
    private var readyPath: String? = null

    override fun isModelReady(): Boolean = readyPath != null

    override fun modelPathOrNull(): String? = readyPath

    override suspend fun ensureModel(): String {
        readyPath?.let { return it }
        // TODO(Phase 4 release): drive Play AssetPackManager.fetch("whisper_base"),
        // await COMPLETED, resolve the pack's local path, then verify expectedSha256.
        throw ModelUnavailable(
            "PAD asset pack 'whisper_base' not yet wired into the AAB (expected sha=$expectedSha256)",
        )
    }
}
