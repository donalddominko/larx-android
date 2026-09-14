package london.aipartner.echo.core.transcribe

/**
 * GATE 2 — the one-time on-device SPEED benchmark (Decision 1, `references/better-models-pro.md`).
 *
 * A model that is memory-safe (passed [MemoryPreGate]) can still be too SLOW on this CPU. Unlike
 * memory, slowness degrades gracefully — a slow transcript, no system harm — so a post-hoc
 * flag/revert is acceptable here. Gate 2 transcribes a short bundled fixture once, measures the
 * real-time factor (RTF = transcribe-seconds ÷ audio-seconds) on THIS device, and keeps the model
 * only if it clears the model's [ModelSpec.gate2RtfThresholdMax]. There is no reliable declarative
 * CPU signal (whisper is CPU-bound; NPU presence is irrelevant), so the actual on-device run is the
 * only trustworthy speed signal.
 *
 * This object is the PURE evaluator + cache contract; the actual fixture decode (which loads a
 * whisper context) is driven on-device by `:app` and MUST only run for a Gate-1 passer, so the
 * bench never loads a model the device can't hold.
 */
object SpeedBench {

    /** Whether a measured [rtf] clears [spec]'s Gate-2 threshold. The floor is never speed-gated. */
    fun passes(spec: ModelSpec, rtf: Double): Boolean =
        spec.isFloor || rtf <= spec.gate2RtfThresholdMax
}

/** Outcome of the one-time on-device calibration for one model, cached across launches. */
data class BenchResult(
    val modelId: ModelId,
    val measuredRtf: Double,
    val passed: Boolean,
)

/**
 * Persistence for Gate-2 calibration results — the bench runs ONCE per (model × device) and the
 * verdict is cached (a device's CPU speed doesn't change). `:app` binds this to durable storage;
 * tests use an in-memory map. A model with no recorded result is treated as **not yet benched**
 * (pending), never as passed — conservative by default.
 */
fun interface BenchResultStore {
    fun result(modelId: ModelId): BenchResult?
}

/**
 * Combines both gates into the device's **offered set** — the models the UI/selection may use.
 * A model is offered iff it passes Gate 1 AND (it is the floor OR it has a cached passing Gate-2
 * result). Gate-1 passers with no bench result yet are reported separately as [pendingBench] so
 * `:app` can schedule the one-time calibration — they are NOT offered until benched.
 *
 * Pure and deterministic. No model/LLM decides membership; the inputs are the device snapshot,
 * the catalog, and cached bench results.
 */
class ModelCapabilityResolver(
    private val catalog: List<ModelSpec> = EchoModelCatalog.all,
    private val benchResults: BenchResultStore,
) {
    /** Models cleared by BOTH gates on [device] — safe and fast enough to offer/select. */
    fun offered(device: DeviceMemory): List<ModelSpec> =
        MemoryPreGate.admitted(catalog, device).filter { spec ->
            spec.isFloor || benchResults.result(spec.id)?.passed == true
        }

    /** Gate-1 passers awaiting their one-time on-device speed benchmark (not yet offerable). */
    fun pendingBench(device: DeviceMemory): List<ModelSpec> =
        MemoryPreGate.admitted(catalog, device).filter { spec ->
            !spec.isFloor && benchResults.result(spec.id) == null
        }
}
