package london.aipartner.echo.core.transcribe

/**
 * A pure snapshot of the device facts the two gates need, decoupled from Android so
 * [MemoryPreGate] is unit-testable without a device. `:app` populates it from
 * `ActivityManager.MemoryInfo` + `Build.SUPPORTED_ABIS`; tests construct it directly.
 *
 * @property totalMemBytes `MemoryInfo.totalMem` — total physical RAM visible to the kernel.
 * @property lowRamDevice `ActivityManager.isLowRamDevice()` — the OEM/AOSP low-RAM flag.
 * @property isArm64 whether an `arm64-v8a` ABI is supported (the whisper `.so` is arm64-only;
 *   a non-arm64 device can load NO on-device model beyond… nothing — it fails honestly).
 */
data class DeviceMemory(
    val totalMemBytes: Long,
    val lowRamDevice: Boolean,
    val isArm64: Boolean,
)

/**
 * GATE 1 — the HARD memory pre-gate (Decision 1, `references/better-models-pro.md`).
 *
 * Decides — BEFORE a model is ever offered, downloaded, or loaded — whether the device can
 * hold it with real headroom below the LMK/OOM ceiling. Fail ⇒ the model is **NOT OFFERED AT
 * ALL** (not offered-with-warning, not offered-then-flagged). This is the gate that prevents
 * the A03 `small` scenario, where the memory harm (system-wide LMK eviction of the user's other
 * apps) lands DURING the attempt, before any post-hoc flag could fire.
 *
 * The budget is a **conservative fraction of total RAM**, calibrated to the measured A03 anchors:
 * `base` (~488 MB peak) is safe on the A03 while `small` (~1.0 GB peak) evicted the system there.
 * A flat fraction of total RAM makes exactly that call — admit `base`, withhold `small` — on the
 * A03, and stays conservative as RAM scales (a bigger phone earns a bigger model only with
 * genuine spare headroom). We compare against the model's **measured/estimated whole-app peak
 * PSS**, never model-size-vs-total-RAM (the A03 lesson: 466 MB "fits" 3 GB yet peaks ~1.0 GB).
 *
 * BASE is never gated (it is the proven floor and the universal fallback). Non-arm64 or
 * low-RAM devices get BASE only.
 */
object MemoryPreGate {

    /**
     * Fraction of total RAM the WHOLE app may be estimated to peak at before we consider it
     * unsafe (it would start evicting the system). Calibrated to the A03: at ~0.30 of the A03's
     * total RAM the budget lands between `base`'s 488 MB (admit) and `small`'s 1.0 GB (withhold),
     * matching the measured outcome, and scales conservatively upward. Deliberately low — the
     * design's posture is "withhold when unsure; better to withhold a better model than evict the
     * user's other apps for one transcription."
     */
    const val MEMORY_HEADROOM_FRACTION = 0.30

    /**
     * Safety multiplier applied to **estimated** (not-yet-measured) anchors before the gate compares
     * them to the budget. The harness found `base`'s real peak (591 MB) ran ~20% above its earlier
     * estimate (488 MB): estimates run OPTIMISTIC, and the gate decides admit/refuse against the anchor,
     * so an optimistic anchor that says "fits with room to spare" but really peaks 20% higher would be
     * wrongly ADMITTED — the exact OOM the gate exists to prevent. So an estimated anchor is treated as
     * a LOWER BOUND and padded here; a MEASURED anchor is used as-is (it already IS real peak). As the
     * harness benches a model on a real device, its measured peak replaces the estimate and the padding
     * falls away for that model. Conservative by design: withhold when the (padded) estimate is close.
     */
    const val ESTIMATE_SAFETY_FACTOR = 1.20

    /** The peak-PSS budget this device may spend on a transcription without harming the system. */
    fun budgetBytes(device: DeviceMemory): Long =
        (device.totalMemBytes * MEMORY_HEADROOM_FRACTION).toLong()

    /** The peak the gate compares to the budget: measured anchors as-is, estimates padded (lower-bound). */
    fun effectivePeakBytes(spec: ModelSpec): Long =
        if (spec.measured) spec.peakMemAnchorBytes
        else (spec.peakMemAnchorBytes * ESTIMATE_SAFETY_FACTOR).toLong()

    /** True iff [spec] passes Gate 1 on [device] — safe to OFFER (subject still to Gate 2). */
    fun passes(spec: ModelSpec, device: DeviceMemory): Boolean {
        if (spec.isFloor) return device.isArm64 // the floor passes everywhere arm64 whisper runs
        if (!device.isArm64) return false        // heavier models need the arm64 native path
        if (device.lowRamDevice) return false     // low-RAM devices get the floor only
        return effectivePeakBytes(spec) <= budgetBytes(device)
    }

    /** The subset of [catalog] that passes Gate 1 on [device], ascending by tier. */
    fun admitted(catalog: List<ModelSpec>, device: DeviceMemory): List<ModelSpec> =
        catalog.filter { passes(it, device) }.sortedBy { it.id.tier }
}

/**
 * Reads the real device facts into a [DeviceMemory] snapshot. The single production binding used
 * by `:app` (and the on-device Gate-1 refusal test) — everything above it is pure and testable
 * against hand-built snapshots. `totalMem`/`isLowRamDevice` come from `ActivityManager`; arm64 from
 * the supported ABIs (the whisper `.so` is arm64-only).
 */
object AndroidDeviceMemory {
    fun probe(context: android.content.Context): DeviceMemory {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val arm64 = android.os.Build.SUPPORTED_ABIS?.any { it == "arm64-v8a" } == true
        return DeviceMemory(
            totalMemBytes = info.totalMem,
            lowRamDevice = am.isLowRamDevice,
            isArm64 = arm64,
        )
    }
}
