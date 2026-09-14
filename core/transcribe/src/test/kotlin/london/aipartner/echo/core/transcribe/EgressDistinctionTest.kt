package london.aipartner.echo.core.transcribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guard for the Phase 4 hard rule: **model download is NOT audio
 * egress.** The on-device path and the model provisioner must never depend on the
 * egress consent or on `Entitlements` — those gate only the CLOUD path. A future
 * change that wires `EgressConsent`/`Entitlements` into model download or the
 * on-device transcriber is a bug; this test fails it.
 *
 * Enforced at the constructor-signature level (compile-time wiring), checked here
 * via reflection so the intent is asserted, not just commented.
 */
class EgressDistinctionTest {

    private fun ctorParamTypes(klass: Class<*>): List<String> =
        klass.declaredConstructors.flatMap { it.parameterTypes.toList() }.map { it.name }

    @Test fun onDeviceTranscriber_hasNoEgressOrEntitlementsDependency() {
        val params = ctorParamTypes(OnDeviceTranscriber::class.java)
        assertFalse("on-device path must not see EgressConsent",
            params.any { it.contains("EgressConsent") })
        assertFalse("on-device path must not see Entitlements",
            params.any { it.contains("Entitlements") })
    }

    @Test fun modelProvisioner_implsHaveNoEgressOrEntitlementsDependency() {
        val params = ctorParamTypes(PlayAssetDeliveryModelProvisioner::class.java)
        assertFalse("model download must not see EgressConsent (it is NOT egress)",
            params.any { it.contains("EgressConsent") })
        assertFalse("model download must not see Entitlements",
            params.any { it.contains("Entitlements") })
    }

    @Test fun cloudTranscriber_DOES_dependOnBothGates() {
        // The positive control: the egress + entitlement coupling lives ONLY here.
        val params = ctorParamTypes(CloudTranscriber::class.java)
        assertTrue(params.any { it.contains("EgressConsent") })
        assertTrue(params.any { it.contains("Entitlements") })
    }

    // --- Phase 5: the same distinction for the AI layer ---

    @Test fun semanticIndex_isOnDevice_noEgressOrEntitlementsDependency() {
        // Search is free/offline/on-device — content never leaves the device. Asserted
        // against the REAL MediaPipe impl (index + embedder), not a stub.
        val indexParams = ctorParamTypes(MediaPipeSemanticIndex::class.java)
        assertFalse("on-device search must not see EgressConsent",
            indexParams.any { it.contains("EgressConsent") })
        assertFalse("on-device search must not see Entitlements",
            indexParams.any { it.contains("Entitlements") })

        val embedderParams = ctorParamTypes(MediaPipeTextEmbedder::class.java)
        assertFalse("the embedder must not see EgressConsent",
            embedderParams.any { it.contains("EgressConsent") })
        assertFalse("the embedder must not see Entitlements",
            embedderParams.any { it.contains("Entitlements") })
    }

    @Test fun cloudAiEngine_DOES_dependOnBothGates() {
        // Generative AI sends transcript content off-device — gated exactly like the cloud ASR.
        val params = ctorParamTypes(CloudAiEngine::class.java)
        assertTrue(params.any { it.contains("EgressConsent") })
        assertTrue(params.any { it.contains("Entitlements") })
    }
}
