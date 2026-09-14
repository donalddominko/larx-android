package london.aipartner.echo.core.transcribe

/**
 * Seam (Phase 5) — **semantic search** across the transcript library.
 *
 * Locus is LOCKED: **ON-DEVICE, free, offline** (Donald's call, 2026-06-25). A
 * small embedding model is a tractable on-device task even where generation isn't,
 * so search is the genuinely-free, genuinely-offline AI win in the free tier. It
 * therefore has **no [EgressConsent]/Entitlements dependency by construction** —
 * the structural egress-distinction test asserts this (search content never leaves
 * the device).
 *
 * The concrete impl is [MediaPipeSemanticIndex] (bundled Universal Sentence Encoder
 * via [MediaPipeTextEmbedder]; brute-force cosine over a regenerable `embeddings`
 * table). See `references/phase-05-ai-features.md`.
 */
interface SemanticIndex {
    /** Provenance of the embeddings this index produces (matches [Embedder.model] and the
     *  `model` column on each stored embedding). The startup backfill uses it to re-index rows
     *  written by a different embedder, not just missing ones. */
    val model: String

    /** Make [transcript] searchable for [recordingId]. Pure on-device work. */
    suspend fun index(recordingId: String, transcript: Transcript)

    /** Return up to [k] recordings most relevant to [query], best first. */
    suspend fun query(query: String, k: Int = 10): List<SearchHit>
}

/** A search result: which recording matched, and how strongly (higher = better). */
data class SearchHit(val recordingId: String, val score: Float)

/**
 * Turns text into a dense vector, fully **on-device**. Concrete impl
 * [MediaPipeTextEmbedder] runs the bundled Universal Sentence Encoder; tests fake
 * it deterministically. [model] is provenance recorded on each stored embedding so
 * a stale embedding (model change) is detectable and re-indexable. By contract this
 * sees **no** `EgressConsent`/`Entitlements` — embedding never leaves the device.
 */
interface Embedder {
    val model: String
    suspend fun embed(text: String): FloatArray
}
