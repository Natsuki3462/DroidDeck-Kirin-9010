package com.droiddeck.launcher.gpu.probe.backends

import com.droiddeck.launcher.gpu.probe.BackendPlan
import com.droiddeck.launcher.gpu.probe.DriverRequirement
import com.droiddeck.launcher.gpu.probe.GpuProbeResult
import com.droiddeck.launcher.gpu.probe.GpuVendor
import com.droiddeck.launcher.gpu.probe.RenderBackend
import com.droiddeck.launcher.gpu.probe.Risk

/**
 * The placeholder for a GPU whose driver exists only as a bionic-linked vendor blob.
 *
 * This is a Kirin 9000S, 9010, 9020 - Huawei's Maleoon. It has a working Vulkan driver on Android and
 * nothing a glibc guest can load: the blob is built against bionic, so `dlopen` in the container
 * fails, and there is no Mesa backend for the architecture at all. The honest answer is that the
 * guest cannot draw with this GPU today.
 *
 * The backend exists as a declared [Risk.BLOCKED] plan rather than as an absent one so the app can
 * say *why* instead of falling through to "not an Adreno GPU", which is true but useless. The route
 * out is a host-side Vulkan bridge, which is a separate project - see the roadmap.
 */
object VendorBridgeBackend : RenderBackend {
    const val ID = "vendor-bridge"

    override val id = ID

    override fun evaluate(probe: GpuProbeResult): BackendPlan? {
        if (probe.vendor != GpuVendor.HUAWEI_MALEOON) return null
        return BackendPlan(
            backendId = ID,
            displayName = "Vendor bridge (${probe.deviceName})",
            risk = Risk.BLOCKED,
            gpuInGuest = false,
            driverRequirement = DriverRequirement.NONE_AVAILABLE,
            reason = "Maleoon has no open guest driver: Huawei's Vulkan blob is bionic-linked and cannot " +
                "load in the glibc runtime, and no Mesa backend targets it. " +
                "A host-side Vulkan bridge is required, and is not built yet.",
        )
    }
}
