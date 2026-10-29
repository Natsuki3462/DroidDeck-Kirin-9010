package com.droiddeck.launcher.gpu.probe

/**
 * Session settings a particular device needs, as opposed to the ones a person would pick.
 *
 * This exists because of one device class in particular: a Kirin 9010 has no Linux GPU driver, so
 * every session on it is drawn by the CPU. On that path the defaults the app ships - full panel
 * resolution, frame generation, FSR - are not "a bit heavy", they are the difference between a
 * session that comes up and one that does not, and the user has no way to know which knob mattered.
 *
 * Null means "nothing device-specific": the app's own defaults are already right.
 */
data class SessionTuning(
    /** Short name for the device card: "Kirin 9010 (software)". */
    val label: String,
    /** Render scale the session should start at, in percent of the panel. */
    val resolutionScalePercent: Int,
    /** Refresh rate to cap the session at, in Hz. */
    val maxRefreshHz: Int,
    /** Whether frame generation is worth offering at all. */
    val frameGeneration: Boolean,
    /** Whether the upscaler is worth offering at all. */
    val upscaling: Boolean,
    /** Guest environment this device needs on top of its backend's. */
    val env: Map<String, String> = emptyMap(),
    /** Why these numbers, in a sentence a bug report can quote. */
    val notes: List<String> = emptyList(),
)

/**
 * The per-device settings worth having an opinion about.
 *
 * Keyed by SoC rather than by phone model: the phones a SoC ships in differ in screen and RAM, but
 * the reason the tuning is needed is the GPU, which they share.
 */
object DeviceProfiles {

    /** SoCs whose only path today is CPU rendering: Maleoon 910 (9000S, 9010) and 920 (9020). */
    private val MALEOON_SOCS = setOf("kirin9000s", "kirin9010", "kirin9020", "kirin9020a", "kirin9030")

    fun forProbe(probe: GpuProbeResult, cpuCores: Int = 8): SessionTuning? {
        val soc = probe.kirin?.id ?: return null
        if (soc in MALEOON_SOCS) return maleoon(probe.kirin, cpuCores)
        return null
    }

    /**
     * Maleoon: software rendering, and tuned for it.
     *
     * The render scale is the one number that matters most - lavapipe's cost is close to linear in
     * pixels, so 60% of a 1220p panel is roughly 2.8x less work per frame than 100%. Threads are
     * capped below the core count because Android's own compositor and the Steam client need cores
     * too, and starving them turns a slow session into a stuttering one.
     */
    private fun maleoon(soc: KirinSoc, cpuCores: Int): SessionTuning {
        val threads = (cpuCores - 2).coerceIn(2, 6)
        return SessionTuning(
            label = "${soc.marketing} (software)",
            resolutionScalePercent = 60,
            maxRefreshHz = 60,
            frameGeneration = false,
            upscaling = false,
            env = mapOf(
                // lavapipe worker threads: leave the rest of the SoC for Android and Steam.
                "LP_NUM_THREADS" to threads.toString(),
                // Shader compilation is the main stall on a CPU renderer; keep it on disk.
                "MESA_SHADER_CACHE_DIR" to "/var/cache/droiddeck/mesa",
                "MESA_SHADER_CACHE_DISABLE" to "false",
            ),
            notes = listOf(
                "${soc.marketing} carries ${soc.gpu}, which has no Linux driver: the session draws on the CPU.",
                "Frame generation and the upscaler are off because both need a Vulkan device.",
                "Expect the launcher and desktop to be usable and games to be slow; that is the renderer, not a setting.",
                // Every Maleoon device is a Huawei one, and Huawei's killer is not AOSP's.
                "HarmonyOS's low-memory killer is stricter than AOSP's: the container's processes need a " +
                    "non-zero oom_score_adj, or one spike kills the whole session rather than one game.",
                // Measured on a Kirin 9010 handset: 1260x2844, so a landscape session is 2844x1260 and
                // 60% of that is 1706x756. The figure is a starting point, not a measurement of what
                // the phone can hold; it is the first thing to raise if the session feels soft.
                "60% of a 2844x1260 landscape panel is 1706x756; raise the scale only after measuring.",
            ),
        )
    }
}
