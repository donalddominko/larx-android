package london.aipartner.echo.core.transcribe

import london.aipartner.echo.core.data.AiArtifactDao
import london.aipartner.echo.core.data.AiArtifactEntity
import java.util.UUID

/**
 * Persists generative derivations as `AiArtifactEntity` rows (encrypted at rest
 * like everything else). A derivation is **regenerable and never the source of
 * truth** — so this writer only ever persists a *successful* generation, and a
 * regenerate **replaces** the prior machine artifact of the same kind for a
 * recording (deterministic id = recordingId + kind). Honest degradation is
 * enforced by construction: [AiArtifactResult.Failed] and
 * [AiArtifactResult.NothingToGenerate] persist **nothing** — there is no code path
 * here that writes fabricated or placeholder content.
 *
 * Deleting an artifact never cascades to the recording, its audio, or its
 * transcript (FK direction is artifact → recording, and we delete by artifact id).
 */
class AiArtifactWriter(
    private val dao: AiArtifactDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Stable id so a regenerate REPLACEs the same-kind artifact rather than duplicating. */
    private fun artifactId(recordingId: String, kind: AiKind): String = "$recordingId:$kind"

    /**
     * Persist [result] for [recordingId] if (and only if) it is a successful
     * generation. Returns the artifact id when written, or null when there was
     * nothing faithful to write (failed / nothing-to-generate) — the caller shows
     * **no artifact**, never a fabricated one.
     */
    suspend fun persist(recordingId: String, result: AiArtifactResult): String? {
        val generated = result as? AiArtifactResult.Generated ?: return null
        val id = artifactId(recordingId, generated.kind)
        dao.upsert(
            AiArtifactEntity(
                id = id,
                recordingId = recordingId,
                kind = generated.kind.name,
                model = generated.model,
                content = generated.content,
                createdAt = clock(),
            ),
        )
        return id
    }
}
