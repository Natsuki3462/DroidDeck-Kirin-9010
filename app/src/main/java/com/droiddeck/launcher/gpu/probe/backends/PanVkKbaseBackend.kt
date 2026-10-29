package com.droiddeck.launcher.gpu.probe.backends

import com.droiddeck.launcher.gpu.probe.BackendPlan
import com.droiddeck.launcher.gpu.probe.DriverRequirement
import com.droiddeck.launcher.gpu.probe.GpuProbeResult
import com.droiddeck.launcher.gpu.probe.KbaseInterface
import com.droiddeck.launcher.gpu.probe.RenderBackend
import com.droiddeck.launcher.gpu.probe.Risk

/**
 * PanVK driving `mali_kbase` directly, for ARM Mali - which is what a Kirin 9000 and older is.
 *
 * Two things about this backend are deliberate and worth stating plainly:
 *
 *  1. **The GPU is bound as `/dev/mali0`, not disguised as a DRM render node.** PanVK opens the
 *     kbase node itself; handing it over as `/dev/dri/renderD*` only invites other software to
 *     issue DRM ioctls at a driver that never implemented them.
 *  2. **CSF and JM are not the same backend.** A build made for CSF (Mali-G615, G715, G710) cannot
 *     drive a Job Manager GPU (Mali-G78 in the Kirin 9000, G76 in the 980/990). Treating them as one
 *     is how a device gets a driver that cannot work.
 *
 * No PanVK build is shipped: the user imports one, because an unauditable 21 MB blob that can hang a
 * kernel is not something this project should hand out by default.
 */
object PanVkKbaseBackend : RenderBackend {
    const val ID = "panvk-kbase"

    override val id = ID

    override fun evaluate(probe: GpuProbeResult): BackendPlan? {
        if (probe.vendor != com.droiddeck.launcher.gpu.probe.GpuVendor.ARM_MALI) return null
        return when (probe.kbase) {
            KbaseInterface.CSF -> plan(
                probe = probe,
                risk = Risk.EXPERIMENTAL,
                reason = "Mali ${probe.deviceName} on kbase CSF: an imported PanVK-kbase build can drive it; " +
                    "frame pacing and stability are unverified on this device.",
            )

            KbaseInterface.JM, KbaseInterface.UNKNOWN -> BackendPlan(
                backendId = ID,
                displayName = "PanVK-kbase (JM)",
                risk = Risk.BLOCKED,
                gpuInGuest = false,
                driverRequirement = DriverRequirement.NONE_AVAILABLE,
                reason = "Mali ${probe.deviceName} on kbase ${probe.kbase.label}: the public PanVK-kbase " +
                    "builds are CSF-only, so a Job Manager GPU has no reproducible driver. " +
                    "Software rendering is the only path until a JM build is produced in CI.",
            )

            KbaseInterface.NONE -> null
        }
    }

    private fun plan(probe: GpuProbeResult, risk: Risk, reason: String) = BackendPlan(
        backendId = ID,
        displayName = "PanVK-kbase (${probe.deviceName})",
        risk = risk,
        gpuInGuest = true,
        driverRequirement = DriverRequirement.IMPORTED_ICD,
        env = mapOf(
            // gamescope and the session scripts read this to pick Mali-shaped defaults.
            "BL_GPU_FAMILY" to "mali",
            // PanVK-kbase builds cache compiled shaders on disk; without a stable dir Steam
            // recompiles its whole shader set on every launch.
            "MESA_SHADER_CACHE_DIR" to "/var/cache/droiddeck/mesa",
            // Unified memory: PanVK reports most of system RAM as DEVICE_LOCAL, which makes DXVK
            // allocate in 256 MiB chunks and die with VK_ERROR_OUT_OF_DEVICE_MEMORY. The kbase
            // builds that understand this knob cap the reported heap instead.
            "PANVK_HEAP_MEMORY_PERCENT" to "0.12",
        ),
        dxvk = mapOf(
            "dxvk.maxMemoryBudget" to "384",
            "dxvk.enableMemoryDefrag" to "False",
            "dxvk.trackPipelineLifetime" to "False",
        ),
        binds = listOf("/dev/mali0:/dev/mali0"),
        reason = reason,
    )
}
