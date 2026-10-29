package com.droiddeck.launcher.gpu.probe

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.VulkanInfo
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

/** The real device, read the way the rest of the app already reads it. */
object AndroidProbeEnv : ProbeEnv {
    override fun exists(path: String): Boolean = File(path).exists()

    override fun readText(path: String): String? = FileUtils.readString(File(path))

    override fun property(name: String): String = GpuInfo.systemProperty(name)

    override fun list(dir: String): List<String> = File(dir).list()?.toList().orEmpty()

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override val hardware: String get() = Build.HARDWARE

    override val manufacturer: String get() = Build.MANUFACTURER

    override val vulkanDeviceName: String? get() = VulkanInfo.cachedOrNull()?.get("device")
}

/**
 * The one place the app asks "what GPU is this, and what should draw with it".
 *
 * Callers should not reach for [GpuProbe] or [BackendRegistry] directly: the probe is cheap but not
 * free (it stats a handful of vendor paths), and the decision has to be the same everywhere or the
 * driver card and the session disagree about the device.
 */
object GpuBackends {

    /** Probe without policy. Cheap enough to call from a settings screen; not from a draw loop. */
    @JvmStatic
    fun probe(): GpuProbeResult = GpuProbe.probe(AndroidProbeEnv)

    /** The HarmonyOS build this device runs, for the card that explains the session's limits. */
    @JvmStatic
    fun harmonyOs(): HarmonyOs = HarmonyOs.detect(AndroidProbeEnv)

    @JvmStatic
    fun policy(context: Context): GpuPolicy = when (SessionPrefs.gpuBackend(context)) {
        SessionPrefs.GPU_BACKEND_HARDWARE -> GpuPolicy.PREFER_HARDWARE
        SessionPrefs.GPU_BACKEND_SOFTWARE -> GpuPolicy.FORCE_SOFTWARE
        else -> GpuPolicy.AUTO
    }

    @JvmStatic
    fun setPolicy(context: Context, policy: GpuPolicy) {
        SessionPrefs.setGpuBackend(context, when (policy) {
            GpuPolicy.AUTO -> SessionPrefs.GPU_BACKEND_AUTO
            GpuPolicy.PREFER_HARDWARE -> SessionPrefs.GPU_BACKEND_HARDWARE
            GpuPolicy.FORCE_SOFTWARE -> SessionPrefs.GPU_BACKEND_SOFTWARE
        })
    }

    /** The backend this device uses under the user's setting. */
    @JvmStatic
    fun decide(context: Context): BackendDecision = decide(probe(), policy(context))

    @JvmStatic
    fun decide(probe: GpuProbeResult, policy: GpuPolicy): BackendDecision =
        BackendRegistry.select(probe, policy)
}
