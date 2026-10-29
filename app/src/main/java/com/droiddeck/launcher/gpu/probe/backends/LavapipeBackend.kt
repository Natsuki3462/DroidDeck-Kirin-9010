package com.droiddeck.launcher.gpu.probe.backends

import com.droiddeck.launcher.gpu.probe.BackendPlan
import com.droiddeck.launcher.gpu.probe.DriverRequirement
import com.droiddeck.launcher.gpu.probe.GpuProbeResult
import com.droiddeck.launcher.gpu.probe.RenderBackend
import com.droiddeck.launcher.gpu.probe.Risk

/**
 * lavapipe: Vulkan on the CPU, with no GPU driver and no GPU device node in the guest.
 *
 * It is slow, and it is the reason a Kirin device can run at all instead of showing sound over a
 * black screen. Everything that matters here is what it does *not* do: the guest is given no
 * `/dev/mali0`, no `/dev/kgsl-3d0` and no `/dev/dri`, so no session - not gamescope, not Proton, not
 * a probe - can reach a driver that might hang the kernel.
 */
object LavapipeBackend : RenderBackend {
    const val ID = "lavapipe"

    override val id = ID

    override fun evaluate(probe: GpuProbeResult): BackendPlan = BackendPlan(
        backendId = ID,
        displayName = "lavapipe (CPU)",
        risk = Risk.SAFE,
        gpuInGuest = false,
        driverRequirement = DriverRequirement.CPU_SOFTWARE,
        env = mapOf(
            "LIBGL_ALWAYS_SOFTWARE" to "1",
            // Keeps a Mesa GL path from quietly reaching for the vendor driver behind our back.
            "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
            "GALLIUM_DRIVER" to "llvmpipe",
        ),
        reason = "CPU Vulkan: no GPU node in the guest, so the session cannot touch the vendor driver.",
    )
}
