package com.droiddeck.launcher.gpu.probe

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.session.SessionPrefs

/**
 * What the session needs from the backend decision, in the terms the guest understands.
 *
 * The point of this type is that the session should not have to reason about GPUs. It asks one
 * question - "what do I put in the guest's environment, and at what size" - and gets an answer that
 * is already specific to this device.
 *
 * [keepAdrenoDefaults] is the load-bearing field: when it is true the session writes exactly the
 * environment it has always written, character for character. Nothing about the Adreno path may
 * change because other vendors were added beside it.
 */
data class SessionGpuPlan(
    val backendId: String,
    val risk: Risk,
    /** The guest draws on the CPU: no GPU node, no vendor driver, a software Vulkan ICD. */
    val software: Boolean,
    /** True when the session should write its own long-standing Adreno environment unchanged. */
    val keepAdrenoDefaults: Boolean,
    /** Vulkan ICD manifest for the guest, or null to keep the runtime's own. */
    val icdPath: String?,
    /**
     * Directory holding the driver and the libraries it links against.
     *
     * A CPU renderer is not one file: lavapipe needs its LLVM and its X/Wayland shims beside it, and
     * the dynamic loader only looks in default paths. The session puts this directory on the guest's
     * `LD_LIBRARY_PATH` so those are found.
     */
    val libraryDir: String?,
    /** A software session was asked for but no CPU Vulkan driver has been imported. */
    val missingSoftwareDriver: Boolean,
    /** Guest environment this plan adds. Empty when [keepAdrenoDefaults] is set. */
    val env: Map<String, String>,
    /** Render scale for the session output, in percent of the chosen size. 100 leaves it alone. */
    val resolutionScalePercent: Int,
    /** Refresh rate to cap the session at. 0 leaves it alone. */
    val maxRefreshHz: Int,
    val reason: String,
)

/**
 * Turns a [BackendDecision] into a [SessionGpuPlan].
 *
 * Everything except [plan] itself is pure, so the mapping can be asserted on a build machine. The
 * Android-facing part only looks up two things: which driver packs are installed, and how many cores
 * the device has.
 */
object SessionGpu {
    private const val TAG = "SessionGpu"

    /** Backend ids whose environment the session already writes itself. */
    private const val ADRENO_BACKEND = "turnip"

    /**
     * Whether a driver pack is a CPU renderer, by whatever name its author gave it.
     *
     * These are matched as whole words: "lvp" inside another word is not evidence of anything.
     */
    fun isSoftwareDriverName(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        if (lower.contains("lavapipe") || lower.contains("llvmpipe") ||
            lower.contains("swrast") || lower.contains("softpipe")
        ) return true
        return lower.split(Regex("[^a-z0-9]+")).any { it == "lvp" }
    }

    /** The first imported driver pack that is a CPU renderer, or null. */
    fun softwareDriverId(context: Context): String? {
        val manager = LinuxVulkanDriverManager(context)
        return pickSoftwareDriver(
            installed = manager.enumerateInstalledDrivers(),
            chosen = SessionPrefs.linuxDriver(context),
            nameOf = manager::getDriverName,
        )
    }

    /**
     * Which CPU renderer to use when more than one is installed.
     *
     * The driver picked on the Components screen wins. Picking one there and then having the
     * session quietly use a different one is the sort of thing that makes people stop trusting the
     * screen - and it is what happened the first time two lavapipe packs were installed side by
     * side, because the old code simply took the first id in alphabetical order.
     */
    fun pickSoftwareDriver(installed: List<String>, chosen: String, nameOf: (String) -> String): String? {
        fun isSoftware(id: String) = isSoftwareDriverName(id) || isSoftwareDriverName(nameOf(id))
        if (chosen.isNotEmpty() && chosen in installed && isSoftware(chosen)) return chosen
        return installed.firstOrNull { isSoftware(it) }
    }

    /**
     * The session's output size after the device profile's render scale.
     *
     * Rounded to an even number because a half-pixel edge shows up as a seam once the compositor
     * scales the frame back to the panel, and floored at 320 so a small profile cannot produce a
     * size gamescope refuses.
     */
    fun scaledOutput(value: Int, percent: Int): Int =
        if (percent >= 100) value
        else ((value.toLong() * percent / 100).toInt() / 2 * 2).coerceAtLeast(320)

    /** The plan for this device under the user's current setting. */
    fun plan(context: Context): SessionGpuPlan {
        val probe = GpuBackends.probe()
        val decision = BackendRegistry.select(probe, GpuBackends.policy(context))
        val tuning = DeviceProfiles.forProbe(probe, CpuCores.all.size)
        val softwareIcd = if (decision.software) softwareDriverId(context)?.let {
            LinuxVulkanDriverManager(context).getIcdPath(it)
        } else {
            null
        }
        return plan(decision, softwareIcd, tuning)
    }

    fun plan(
        decision: BackendDecision,
        softwareIcd: String?,
        tuning: SessionTuning?,
    ): SessionGpuPlan {
        val chosen = decision.chosen
        if (!decision.software) {
            // A GPU session: Adreno keeps its own environment, and a Mali one brings its own.
            val adreno = chosen.backendId == ADRENO_BACKEND
            return SessionGpuPlan(
                backendId = chosen.backendId,
                risk = chosen.risk,
                software = false,
                keepAdrenoDefaults = adreno,
                icdPath = null,
                libraryDir = null,
                missingSoftwareDriver = false,
                env = if (adreno) emptyMap() else chosen.env,
                resolutionScalePercent = 100,
                maxRefreshHz = 0,
                reason = chosen.reason,
            )
        }
        // A CPU session: the backend's own environment, plus whatever the device profile adds.
        val env = LinkedHashMap<String, String>()
        env.putAll(chosen.env)
        tuning?.env?.let { env.putAll(it) }
        return SessionGpuPlan(
            backendId = chosen.backendId,
            risk = chosen.risk,
            software = true,
            keepAdrenoDefaults = false,
            icdPath = softwareIcd,
            libraryDir = softwareIcd?.substringBeforeLast('/', "")?.ifEmpty { null },
            missingSoftwareDriver = softwareIcd == null,
            env = env,
            resolutionScalePercent = tuning?.resolutionScalePercent?.coerceIn(10, 100) ?: 100,
            maxRefreshHz = tuning?.maxRefreshHz ?: 0,
            reason = chosen.reason,
        )
    }

    /** One line for the session log, so a bug report says which path drew the frame. */
    fun describe(plan: SessionGpuPlan): String = buildString {
        append("gpu: ").append(plan.backendId).append(" [").append(plan.risk.name).append(']')
        if (plan.software) {
            append(" · software")
            append(if (plan.icdPath != null) " (icd ${plan.icdPath})" else " (no CPU Vulkan driver imported)")
            if (plan.resolutionScalePercent != 100) append(" · ${plan.resolutionScalePercent}%")
            if (plan.maxRefreshHz > 0) append(" · ${plan.maxRefreshHz} Hz cap")
        } else {
            append(" · gpu in guest")
        }
    }

    fun log(plan: SessionGpuPlan) {
        if (plan.software && plan.missingSoftwareDriver) {
            Log.e(TAG, "software session but no CPU Vulkan driver is imported: the guest will have no " +
                "Vulkan device. Import a lavapipe pack under Components > GPU drivers.")
        }
        Log.i(TAG, describe(plan))
    }
}
