package com.droiddeck.launcher.gpu.probe.backends

import com.droiddeck.launcher.gpu.probe.BackendPlan
import com.droiddeck.launcher.gpu.probe.DriverRequirement
import com.droiddeck.launcher.gpu.probe.GpuProbeResult
import com.droiddeck.launcher.gpu.probe.RenderBackend
import com.droiddeck.launcher.gpu.probe.Risk

/**
 * Turnip, the Mesa Vulkan driver for Adreno, built into the Linux runtime.
 *
 * This is the path the app was written for and the only one it has been validated on, so it is the
 * only backend that may claim [Risk.SAFE]. Nothing about it changes for Adreno devices: the plan
 * describes what the runtime already does, and exists so the choice between backends can be read in
 * one place instead of inferred from four.
 */
object TurnipBackend : RenderBackend {
    const val ID = "turnip"

    override val id = ID

    override fun evaluate(probe: GpuProbeResult): BackendPlan? {
        if (!probe.isAdreno) return null
        return BackendPlan(
            backendId = ID,
            displayName = "Turnip (Adreno)",
            risk = Risk.SAFE,
            gpuInGuest = true,
            driverRequirement = DriverRequirement.RUNTIME_DEFAULT,
            env = mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "zink"),
            binds = listOf("/dev/kgsl-3d0:/dev/dri/renderD128"),
            reason = "Adreno with kgsl: the runtime's own Turnip drives it, as validated.",
        )
    }
}
