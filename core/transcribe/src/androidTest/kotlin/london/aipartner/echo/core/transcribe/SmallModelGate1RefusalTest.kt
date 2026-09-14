package london.aipartner.echo.core.transcribe

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ON-DEVICE proof of the two-gate SAFETY half (`references/better-models-pro.md`, Decision 1).
 *
 * Runs on the REAL device via its actual `ActivityManager` snapshot (no hand-built numbers). On the
 * A03 (SM-A035F, ~3 GB, ceiling = base) this asserts the load-bearing safety behavior:
 *  1. **Gate 1 REFUSES `small`** — the A03's real memory snapshot admits ONLY `base` (small's
 *     measured ~1.0 GB peak exceeds the headroom budget), so `small` is never offered/downloaded.
 *  2. **NO-BYPASS** — even holding Pro and pretending `small`/`medium` are downloaded, the
 *     selection policy clamps a `small` preference back to `base`. The user cannot force a model
 *     past the gate.
 *
 * The A03 is the IDEAL device to prove the REFUSAL. The ADMIT path (a capable phone surfacing a
 * larger tier) is the device-gated ENABLING half and is intentionally NOT tested here.
 *
 * On a device better than the A03 (which passes Gate 1 for `small`), the refusal assertions are
 * skipped honestly (logged), since this test's contract is the floor-device refusal.
 */
@RunWith(AndroidJUnit4::class)
class SmallModelGate1RefusalTest {

    private val TAG = "EchoGate1"

    private class FakeEntitlements(val pro: Boolean) : Entitlements {
        override fun has(feature: Feature) = pro && feature == Feature.PREMIUM_MODELS
    }

    @Test fun gate1_refusesSmall_andNoBypass_onThisDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val device = AndroidDeviceMemory.probe(context)
        val budgetMb = MemoryPreGate.budgetBytes(device) / 1_048_576
        val totalMb = device.totalMemBytes / 1_048_576
        Log.i(TAG, "device: totalMem=${totalMb}MB budget=${budgetMb}MB " +
            "lowRam=${device.lowRamDevice} arm64=${device.isArm64}")

        val admitted = MemoryPreGate.admitted(EchoModelCatalog.all, device).map { it.id }
        Log.i(TAG, "gate-1 admitted set: $admitted")

        // BASE is the universal floor — must always pass Gate 1 on an arm64 device.
        assertTrue("arm64 device", device.isArm64)
        assertTrue("base must always pass Gate 1", ModelId.BASE in admitted)

        if (ModelId.SMALL in admitted) {
            // Better-than-A03 device: the REFUSAL contract doesn't apply. Record and skip honestly.
            Log.w(TAG, "device passes Gate 1 for small — this is not the floor; refusal test N/A here.")
            return
        }

        // ---- FLOOR-DEVICE (A03) CONTRACT ----
        // Gate 1 REFUSES small (and medium): offered set is base-only.
        assertEquals("Gate 1 must admit ONLY base on the floor", listOf(ModelId.BASE), admitted)
        assertFalse("small must be REFUSED by Gate 1 on the floor",
            MemoryPreGate.passes(EchoModelCatalog.small, device))

        // NO-BYPASS: Pro + pretend-downloaded small/medium still clamps to base.
        val offered = ModelCapabilityResolver(
            benchResults = { id -> BenchResult(id, 1.0, passed = true) }, // even if everything "passed" Gate 2
        ).offered(device)
        assertEquals(listOf(ModelId.BASE), offered.map { it.id })

        val chosen = ModelSelectionPolicy(FakeEntitlements(pro = true))
            .selectFor(offered, downloaded = setOf(ModelId.SMALL, ModelId.MEDIUM), preferred = ModelId.SMALL)
        assertEquals("user cannot force small past the gate", ModelId.BASE, chosen)
        Log.i(TAG, "REFUSAL + NO-BYPASS proven: offered=base-only, forced-small selection resolved to $chosen")
    }
}
