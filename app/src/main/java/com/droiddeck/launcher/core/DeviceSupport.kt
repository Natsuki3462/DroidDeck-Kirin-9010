package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.probe.BackendRegistry
import com.droiddeck.launcher.gpu.probe.GpuBackends
import com.droiddeck.launcher.gpu.probe.GpuVendor
import com.droiddeck.launcher.gpu.probe.KirinSoc
import com.droiddeck.launcher.gpu.probe.Risk
import java.io.File

/**
 * Whether this device can draw a Linux session, and with what.
 *
 * The runtime draws with Turnip, an Adreno driver. On anything else the compositor gets no usable
 * Vulkan device and a session comes up as sound over a black screen - a failure with nothing in it
 * to read. So the question "is this an Adreno" was, until now, the whole answer.
 *
 * It no longer is. A Kirin 9000 and older carries ARM Mali, which an imported PanVK-kbase driver can
 * drive; a Kirin 9000S and newer carries Huawei's Maleoon, which nothing open can drive, but which
 * can still run a session on the CPU. Both are answers, and neither is "no". What decides is the
 * backend registry, not a brand check - [adreno] stays as it was because the Adreno path must not
 * change behaviour just because other vendors were added beside it.
 */
object DeviceSupport {
    fun adreno(): Boolean =
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()

    /** ARM Mali: a licensed Mali GPU, which is what a Kirin 9000 or older has. */
    @JvmStatic
    fun mali(): Boolean = GpuBackends.probe().vendor == GpuVendor.ARM_MALI

    /** Huawei Maleoon: Kirin 9000S, 9010 and 9020. */
    @JvmStatic
    fun maleoon(): Boolean = GpuBackends.probe().vendor == GpuVendor.HUAWEI_MALEOON

    /** The Kirin SoC this device is, or null when it is not a Kirin at all. */
    @JvmStatic
    fun kirin(): KirinSoc? = GpuBackends.probe().kirin

    /**
     * Whether a session can start at all.
     *
     * True on every device now: a GPU that no backend can drive still gets CPU rendering, and a
     * session that runs slowly is better than a card that refuses to start one. Kept as a function
     * rather than deleted because the launcher still asks, and the answer is what it acts on.
     */
    @JvmStatic
    fun renderable(): Boolean =
        BackendRegistry.select(GpuBackends.probe()).chosen.risk != Risk.BLOCKED

    /**
     * Whether starting a session here has to explain itself first.
     *
     * Adreno needs no explanation: it is the validated path. Everything else does, because the
     * session will draw on the CPU - and a user who finds that out after a 3 GB download has been
     * told the wrong thing at the wrong moment.
     *
     * This replaces the old "is this an Adreno" gate. The question was never whether the GPU is
     * Qualcomm's; it is whether anything here can draw, and what it will cost.
     */
    @JvmStatic
    fun needsGpuWarning(context: Context): Boolean {
        val probe = GpuBackends.probe()
        if (probe.isAdreno) return false
        return GpuBackends.decide(probe, GpuBackends.policy(context)).software
    }

    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
