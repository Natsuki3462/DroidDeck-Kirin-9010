package com.droiddeck.launcher.gpu.probe

import com.droiddeck.launcher.gpu.probe.backends.LavapipeBackend
import com.droiddeck.launcher.gpu.probe.backends.PanVkKbaseBackend
import com.droiddeck.launcher.gpu.probe.backends.TurnipBackend
import com.droiddeck.launcher.gpu.probe.backends.VendorBridgeBackend

/**
 * How much the user has asked for the GPU to be used.
 *
 * [AUTO] is what an install that never chose anything gets, and it is deliberately the conservative
 * one: hardware only where the path is validated. Asking for the experimental Mali driver is a
 * separate, explicit act, because the failure mode is not "a game runs badly" - it is a kernel hang.
 */
enum class GpuPolicy {
    /** Only validated backends; anything else falls back to CPU rendering. */
    AUTO,

    /** Best available backend, including experimental ones, but never a blocked one. */
    PREFER_HARDWARE,

    /** CPU rendering whatever the device is. */
    FORCE_SOFTWARE,
}

/** The chosen backend, plus every backend that had an opinion, for the device card and reports. */
data class BackendDecision(
    val chosen: BackendPlan,
    val candidates: List<BackendPlan>,
    val policy: GpuPolicy,
) {
    /** Whether the guest is kept away from the GPU entirely. */
    val software: Boolean get() = !chosen.gpuInGuest
}

/**
 * The ordered list of backends, and the one rule that picks between them.
 *
 * Order is the tie-break: when two backends carry the same risk, the earlier one wins, so the list
 * reads as a preference order and the selection stays deterministic without a scoring scheme nobody
 * can reason about.
 */
object BackendRegistry {

    /** Preference order. Adreno first because it is the validated path. */
    val backends: List<RenderBackend> = listOf(
        TurnipBackend,
        PanVkKbaseBackend,
        LavapipeBackend,
        VendorBridgeBackend,
    )

    fun evaluate(probe: GpuProbeResult, backends: List<RenderBackend> = this.backends): List<BackendPlan> =
        backends.mapNotNull { it.evaluate(probe) }

    /**
     * The backend this device should use under [policy].
     *
     * A blocked plan is never chosen, even by [GpuPolicy.PREFER_HARDWARE]: blocked means it cannot
     * work, not that it is untested. If nothing usable is left, CPU rendering is chosen - which is
     * always available, and is the reason this function has no null case.
     */
    fun select(
        probe: GpuProbeResult,
        policy: GpuPolicy = GpuPolicy.AUTO,
        backends: List<RenderBackend> = this.backends,
    ): BackendDecision {
        val candidates = evaluate(probe, backends)
        val usable = candidates.filter { it.risk != Risk.BLOCKED }

        val chosen = when (policy) {
            GpuPolicy.FORCE_SOFTWARE -> usable.firstOrNull { it.driverRequirement == DriverRequirement.CPU_SOFTWARE }
            GpuPolicy.AUTO -> usable.firstOrNull { it.risk == Risk.SAFE }
            // Asking for hardware means hardware: a SAFE CPU renderer must not win this one, or the
            // setting would do nothing on the only devices it exists for.
            GpuPolicy.PREFER_HARDWARE -> usable
                .filter { it.driverRequirement != DriverRequirement.CPU_SOFTWARE }
                .minByOrNull { rank(it.risk) }
        } ?: usable.firstOrNull { it.driverRequirement == DriverRequirement.CPU_SOFTWARE }
            ?: LavapipeBackend.evaluate(probe)

        return BackendDecision(chosen = chosen, candidates = candidates, policy = policy)
    }

    private fun rank(risk: Risk): Int = when (risk) {
        Risk.SAFE -> 0
        Risk.EXPERIMENTAL -> 1
        Risk.BLOCKED -> 2
    }
}
