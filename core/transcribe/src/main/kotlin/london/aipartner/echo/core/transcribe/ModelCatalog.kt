package london.aipartner.echo.core.transcribe

/**
 * The pool of on-device whisper models Echo can ship PAD packs for. This is NOT a
 * static "tier → device-class" table — it is only the *candidate pool*. Which subset
 * a given phone may actually run is computed **on that phone** by the two gates
 * ([MemoryPreGate] then [SpeedBench]); which of those the user may *select* is then
 * decided by [ModelSelectionPolicy] against [london.aipartner.echo.core.billing.Feature.PREMIUM_MODELS].
 *
 * See `references/better-models-pro.md` (Decisions 1–3). The `base` floor is the only
 * ungated entry — it passes Gate 1 everywhere by definition and is the universal free
 * fallback. Every other model is subject to BOTH gates on every device.
 */
enum class ModelId {
    /** Universal free floor. ~142 MB. Proven on the A03 (peak ~488 MB PSS). */
    BASE,

    /** Better multilingual model. ~466 MB. On the A03 it FAILS both gates
     *  (peak ~1.0 GB PSS, RTF ≥12.3× → aborts) — correctly withheld there. */
    SMALL,

    /** Flagship candidate (`medium`, ~1.5 GB). Pro where a device passes both gates.
     *  Peak-mem anchor is an ESTIMATE pending on-device validation (no measured device
     *  yet runs it) — Gate 1 stays conservative until a mid/flagship device confirms it. */
    MEDIUM,
    ;

    /** Monotonic capability rank (BASE < SMALL < MEDIUM). Used only for ordering; a
     *  higher rank is "more capable / heavier", never an implication it's runnable. */
    val tier: Int get() = ordinal
}

/**
 * Static facts about one model. Memory/speed anchors drive the two gates.
 *
 * @property peakMemAnchorBytes the model's estimated **whole-app peak PSS** with the single
 *   whisper context + MediaPipe + SQLCipher co-resident — the number Gate 1 compares to device
 *   headroom. Anchored on MEASURED data (`base` ~488 MB, `small` ~1.0 GB on the A03), NOT model
 *   size vs total RAM (the A03 lesson: 466 MB "fits" 3 GB yet peaks ~1.0 GB and evicts everything).
 *   `medium` is an ESTIMATE until measured — [measured] records which.
 * @property gate2RtfThresholdMax the maximum real-time factor Gate 2 tolerates on-device
 *   (transcribe-seconds ÷ audio-seconds). Above this the model is memory-safe but too slow and
 *   Gate 2 withholds it (graceful — a post-hoc flag is fine for speed, never for memory).
 * @property sha256 integrity pin for the delivered weights, or null for a model whose PAD
 *   pack is not yet built/shipped (MEDIUM until Release wires it) — a null-SHA model is never
 *   resolvable, a fail-closed guard against offering an unshipped weight.
 */
data class ModelSpec(
    val id: ModelId,
    val displayName: String,
    val ggmlFileName: String,
    val padPackName: String,
    val approxSizeBytes: Long,
    val peakMemAnchorBytes: Long,
    val gate2RtfThresholdMax: Double,
    val multilingual: Boolean,
    val measured: Boolean,
    val sha256: String?,
) {
    /** True only for the proven floor: BASE ships and is never gated. */
    val isFloor: Boolean get() = id == ModelId.BASE
}

private const val MB = 1_048_576L

/**
 * The v1 candidate catalog. Order is by [ModelId.tier] (ascending). Sizes are approximate
 * ggml sizes; peak-mem anchors are the load-bearing Gate-1 inputs (measured where noted).
 */
object EchoModelCatalog {

    val base = ModelSpec(
        id = ModelId.BASE,
        displayName = "Standard",
        ggmlFileName = "ggml-base.bin",
        padPackName = "whisper_base",
        approxSizeBytes = 142 * MB,
        // MEASURED, A03: the bench harness recorded peak PSS 591 MB on a real 66 s decode (MediaPipe +
        // SQLCipher co-resident) — HIGHER than the earlier 488 MB Phase-6 point. Measured replaces the
        // estimate; the catalog converges on measured reality as the harness benches more devices.
        peakMemAnchorBytes = 591 * MB,
        gate2RtfThresholdMax = Double.MAX_VALUE, // floor is never speed-gated (it's the fallback)
        multilingual = true,
        measured = true,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
    )

    val small = ModelSpec(
        id = ModelId.SMALL,
        displayName = "Enhanced",
        ggmlFileName = "ggml-small.bin",
        padPackName = "whisper_small",
        approxSizeBytes = 466 * MB,
        peakMemAnchorBytes = 1_016 * MB, // MEASURED, A03 (~1.0 GB, evicted the system → withheld there)
        gate2RtfThresholdMax = 1.3,
        multilingual = true,
        measured = true,
        sha256 = null, // PAD pack not yet built (Release step) — unresolvable until then
    )

    val medium = ModelSpec(
        id = ModelId.MEDIUM,
        displayName = "Pro",
        ggmlFileName = "ggml-medium.bin",
        padPackName = "whisper_medium",
        approxSizeBytes = 1_536 * MB,
        peakMemAnchorBytes = 2_400 * MB, // ESTIMATE — conservative, validate on a capable device
        gate2RtfThresholdMax = 1.3,
        multilingual = true,
        measured = false,
        sha256 = null,
    )

    /** All candidates, ascending by tier. */
    val all: List<ModelSpec> = listOf(base, small, medium)

    fun spec(id: ModelId): ModelSpec = when (id) {
        ModelId.BASE -> base
        ModelId.SMALL -> small
        ModelId.MEDIUM -> medium
    }
}
