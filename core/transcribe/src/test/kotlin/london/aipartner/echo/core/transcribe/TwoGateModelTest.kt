package london.aipartner.echo.core.transcribe

import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAFETY-half proof of the two-gate better-models engine (`references/better-models-pro.md`,
 * Decision 1). Device-independent JVM tests: Gate 1 memory pre-gate, Gate 2 speed evaluator,
 * the combined offered set, and the model-selection policy's NO-BYPASS + Option-C + immutability
 * guarantees. The A03 refusal is asserted here against the A03's measured device snapshot AND
 * proven live in `SmallModelGate1RefusalTest` (androidTest).
 */
class TwoGateModelTest {

    private class FakeEntitlements(val pro: Boolean) : Entitlements {
        override fun has(feature: Feature) = pro && feature == Feature.PREMIUM_MODELS
    }

    // Device snapshots. A03: ~2.75 GB total RAM, arm64, not low-RAM-flagged.
    private val a03 = DeviceMemory(totalMemBytes = 2_750L * 1_048_576, lowRamDevice = false, isArm64 = true)
    // A capable mid device: ~6 GB → budget 0.30*6144 ≈ 1843 MB (admits small@1016, withholds medium@2400).
    private val mid = DeviceMemory(totalMemBytes = 6_144L * 1_048_576, lowRamDevice = false, isArm64 = true)
    // A flagship: ~12 GB → budget ≈ 3686 MB (admits medium@2400 too).
    private val flagship = DeviceMemory(totalMemBytes = 12_288L * 1_048_576, lowRamDevice = false, isArm64 = true)

    private fun store(vararg passing: ModelId) = BenchResultStore { id ->
        if (id in passing) BenchResult(id, measuredRtf = 1.0, passed = true) else null
    }

    // ---- GATE 1 (memory pre-gate) --------------------------------------------------------------

    @Test fun gate1_a03_admitsOnlyBase() {
        assertTrue(MemoryPreGate.passes(EchoModelCatalog.base, a03))
        assertFalse("small must be withheld on the A03 — measured 1.0 GB peak evicts the system",
            MemoryPreGate.passes(EchoModelCatalog.small, a03))
        assertFalse(MemoryPreGate.passes(EchoModelCatalog.medium, a03))
        assertEquals(listOf(ModelId.BASE), MemoryPreGate.admitted(EchoModelCatalog.all, a03).map { it.id })
    }

    @Test fun gate1_midAdmitsSmall_notMedium() {
        assertEquals(listOf(ModelId.BASE, ModelId.SMALL),
            MemoryPreGate.admitted(EchoModelCatalog.all, mid).map { it.id })
    }

    @Test fun gate1_flagshipAdmitsAll() {
        assertEquals(listOf(ModelId.BASE, ModelId.SMALL, ModelId.MEDIUM),
            MemoryPreGate.admitted(EchoModelCatalog.all, flagship).map { it.id })
    }

    @Test fun gate1_nonArm64_getsNothing_evenBaseNeedsArm64() {
        val x86 = a03.copy(isArm64 = false)
        assertFalse(MemoryPreGate.passes(EchoModelCatalog.base, x86))
        assertTrue(MemoryPreGate.admitted(EchoModelCatalog.all, x86).isEmpty())
    }

    @Test fun gate1_lowRamDevice_getsFloorOnly() {
        val lowRam = flagship.copy(lowRamDevice = true) // huge RAM but OEM-flagged low-RAM
        assertEquals(listOf(ModelId.BASE), MemoryPreGate.admitted(EchoModelCatalog.all, lowRam).map { it.id })
    }

    @Test fun gate1_padsEstimatedAnchors_measuredUsedAsIs() {
        // A device whose budget sits BETWEEN medium's raw estimate (2400 MB) and its padded value
        // (2400×1.2 = 2880 MB): raw would ADMIT, padding correctly REFUSES the not-yet-measured model.
        val total = 8_500L * 1_048_576 // budget 0.30× ≈ 2550 MB
        val between = DeviceMemory(totalMemBytes = total, lowRamDevice = false, isArm64 = true)
        val budget = MemoryPreGate.budgetBytes(between)
        assertTrue("raw medium anchor would fit", EchoModelCatalog.medium.peakMemAnchorBytes < budget)
        assertTrue("padded medium anchor exceeds budget", MemoryPreGate.effectivePeakBytes(EchoModelCatalog.medium) > budget)
        assertFalse("estimated medium must be REFUSED against its padded lower-bound",
            MemoryPreGate.passes(EchoModelCatalog.medium, between))
        // A MEASURED anchor (small) is used as-is — no padding penalty.
        assertEquals(EchoModelCatalog.small.peakMemAnchorBytes, MemoryPreGate.effectivePeakBytes(EchoModelCatalog.small))
    }

    // ---- GATE 2 (speed) ------------------------------------------------------------------------

    @Test fun gate2_withholdsTooSlow_keepsFastEnough() {
        assertTrue(SpeedBench.passes(EchoModelCatalog.small, rtf = 1.1))
        assertFalse(SpeedBench.passes(EchoModelCatalog.small, rtf = 2.5))
        // The floor is never speed-gated even if the CPU is slow (it's the fallback).
        assertTrue(SpeedBench.passes(EchoModelCatalog.base, rtf = 6.0))
    }

    // ---- Combined offered set (both gates) -----------------------------------------------------

    @Test fun resolver_gate1PasserWithoutBench_isPendingNotOffered() {
        val resolver = ModelCapabilityResolver(benchResults = store(/* none */))
        assertEquals(listOf(ModelId.BASE), resolver.offered(mid).map { it.id })
        assertEquals(listOf(ModelId.SMALL), resolver.pendingBench(mid).map { it.id })
    }

    @Test fun resolver_offersSmall_onceBenchPasses() {
        val resolver = ModelCapabilityResolver(benchResults = store(ModelId.SMALL))
        assertEquals(listOf(ModelId.BASE, ModelId.SMALL), resolver.offered(mid).map { it.id })
        assertTrue(resolver.pendingBench(mid).isEmpty())
    }

    // ---- Selection policy: NO-BYPASS (the load-bearing safety guarantee) ------------------------

    @Test fun noBypass_a03_cannotForceSmall_evenWithPro() {
        val policy = ModelSelectionPolicy(FakeEntitlements(pro = true))
        val offered = ModelCapabilityResolver(benchResults = store(ModelId.SMALL)).offered(a03) // {base}
        // User prefers SMALL, holds Pro, pretends SMALL is downloaded — still clamped to BASE.
        val chosen = policy.selectFor(offered, downloaded = setOf(ModelId.SMALL, ModelId.MEDIUM), preferred = ModelId.SMALL)
        assertEquals(ModelId.BASE, chosen)
    }

    @Test fun noBypass_freeUser_cannotSelectProModel() {
        val policy = ModelSelectionPolicy(FakeEntitlements(pro = false))
        val offered = ModelCapabilityResolver(benchResults = store(ModelId.SMALL, ModelId.MEDIUM)).offered(flagship)
        // 3 tiers ⇒ medium is Pro. Free user prefers medium, has it downloaded ⇒ clamped to small (free ceiling).
        val chosen = policy.selectFor(offered, downloaded = setOf(ModelId.SMALL, ModelId.MEDIUM), preferred = ModelId.MEDIUM)
        assertEquals(ModelId.SMALL, chosen)
    }

    // ---- Selection policy: Option C free/Pro split ---------------------------------------------

    @Test fun optionC_twoTierDevice_hasNoProLever_smallIsFree() {
        val offered = ModelCapabilityResolver(benchResults = store(ModelId.SMALL)).offered(mid) // {base, small}
        // Even a free user gets SMALL free (ceiling one tier above base ⇒ no Pro lever).
        val freeChosen = ModelSelectionPolicy(FakeEntitlements(false))
            .selectFor(offered, downloaded = setOf(ModelId.SMALL), preferred = null)
        assertEquals(ModelId.SMALL, freeChosen)
    }

    @Test fun optionC_threeTierDevice_proUnlocksMedium() {
        val offered = ModelCapabilityResolver(benchResults = store(ModelId.SMALL, ModelId.MEDIUM)).offered(flagship)
        val downloaded = setOf(ModelId.SMALL, ModelId.MEDIUM)
        assertEquals(ModelId.MEDIUM,
            ModelSelectionPolicy(FakeEntitlements(true)).selectFor(offered, downloaded, preferred = null))
        assertEquals(ModelId.SMALL,
            ModelSelectionPolicy(FakeEntitlements(false)).selectFor(offered, downloaded, preferred = null))
    }

    // ---- Selection policy: availability + trial→revert (immutability is a property of the caller) ----

    @Test fun undownloadedProModel_fallsBackToBestDownloaded() {
        val offered = ModelCapabilityResolver(benchResults = store(ModelId.SMALL, ModelId.MEDIUM)).offered(flagship)
        // Pro user, but medium not yet downloaded ⇒ best available downloaded = small.
        val chosen = ModelSelectionPolicy(FakeEntitlements(true))
            .selectFor(offered, downloaded = setOf(ModelId.SMALL), preferred = ModelId.MEDIUM)
        assertEquals(ModelId.SMALL, chosen)
    }

    @Test fun trialExpiry_flipsSelectionForNewRecordings_notExisting() {
        val offered = ModelCapabilityResolver(benchResults = store(ModelId.SMALL, ModelId.MEDIUM)).offered(flagship)
        val downloaded = setOf(ModelId.SMALL, ModelId.MEDIUM)
        // During trial (Pro=true) a NEW recording picks MEDIUM.
        assertEquals(ModelId.MEDIUM,
            ModelSelectionPolicy(FakeEntitlements(true)).selectFor(offered, downloaded, preferred = ModelId.MEDIUM))
        // After expiry (Pro=false) the SAME preference on a NEW recording reverts to SMALL — the
        // downloaded MEDIUM file is untouched; only future selection changes. Existing transcripts
        // are never revisited (they carry their own recorded model provenance — caller's guarantee).
        assertEquals(ModelId.SMALL,
            ModelSelectionPolicy(FakeEntitlements(false)).selectFor(offered, downloaded, preferred = ModelId.MEDIUM))
    }

    @Test fun emptyOffered_stillFallsBackToBase() {
        val chosen = ModelSelectionPolicy(FakeEntitlements(true))
            .selectFor(offered = emptyList(), downloaded = emptySet(), preferred = ModelId.MEDIUM)
        assertEquals(ModelId.BASE, chosen)
    }
}
