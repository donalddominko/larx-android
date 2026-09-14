package london.aipartner.echo.core.capture

import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Seam 1 — HOW audio is captured. Pluggable capture strategy, resolved at
 * runtime by a [CaptureSourceResolver]. v1 ships a single source (mic); the seam
 * exists so a new source can be added without churn elsewhere.
 *
 * The recorder NEVER hardcodes a single capture method. The capability a source
 * reports is the honesty contract the UI renders; a source advertises a real
 * [CaptureCapability] or reports unavailable — it never pretends. A source owns
 * the encode/container engine; encryption-at-rest + persistence happen above it.
 */
interface CaptureSource {
    val id: CaptureSourceId

    /** What this source can actually do on this device — the honesty contract. */
    val capability: CaptureCapability

    fun isAvailable(ctx: DeviceContext): Boolean

    /** Begins capture, writing to [RecordingSpec.outputTempFile]. */
    suspend fun start(spec: RecordingSpec): CaptureHandle
}

/**
 * Capture sources. v1 ships [MIC] only (Echo is a voice/memo recorder; call
 * recording was removed). The seam can grow new sources without churn elsewhere.
 */
enum class CaptureSourceId { MIC }

/**
 * The job the user wants done; the resolver orders sources for it. v1 ships only
 * MEMO. A future job (e.g. VOX/voice-activated capture) slots in here alongside a
 * new CaptureSource without other churn.
 */
enum class CaptureJob { MEMO }

enum class Fidelity { LOSSY, HD }

/** Container/codec actually used for a recording. */
enum class Codec { OPUS, AAC }

/**
 * The honesty contract surfaced to the UI. The UI renders the *resolved*
 * capability so a user is never misled about what a recording will contain.
 */
data class CaptureCapability(
    val source: CaptureSourceId,
    val fidelity: Fidelity,
)

/** Device facts a resolver needs. Expands as later phases probe more capability. */
data class DeviceContext(
    val hasMicrophone: Boolean = true,
)

/** Parameters for a capture session. */
data class RecordingSpec(
    val outputTempFile: File,
    val sampleRateHz: Int = 44_100,
    /** Null = auto (prefer Opus, fall back to AAC). Set to force a codec (tests). */
    val preferredCodec: Codec? = null,
)

/** What a finished capture produced. Encryption + DB persistence happen above this. */
data class CaptureResult(
    val outputTempFile: File,
    val codec: Codec,
    val durationMs: Long,
)

/** Live handle to an in-progress capture. */
interface CaptureHandle {
    /** Normalized [0f,1f] amplitude for the waveform; cold, polled off the main thread. */
    fun amplitude(): Flow<Float>
    suspend fun pause()
    suspend fun resume()
    /** Stops, finalizes the container, and reports what was produced. */
    suspend fun stop(): CaptureResult
}
