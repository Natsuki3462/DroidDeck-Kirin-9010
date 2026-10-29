package com.droiddeck.launcher.gpu.probe

/**
 * HarmonyOS, as far as a Linux session on top of it is concerned.
 *
 * The distinction that matters is not the version number but whether the device still runs Android
 * applications at all. HarmonyOS 4.x is built on Android 12 (API 31/32), so this app installs and its
 * Android APIs work; HarmonyOS NEXT 5.x dropped that compatibility layer, and an APK cannot be
 * installed there in the first place - which is why there is no runtime check for it, only a note in
 * the support matrix.
 *
 * What *does* need handling at runtime is Huawei's process management: its low-memory killer is more
 * aggressive than AOSP's, and a proot container whose processes all sit at `oom_score_adj=0` is a
 * single spike away from being killed whole. That is a session concern, surfaced here so the device
 * card can warn before a user loses a 40-minute install to it.
 */
data class HarmonyOs(
    val detected: Boolean,
    /** Best-effort version string, "4.2.0" style; "" when the build does not say. */
    val version: String,
    /** API level the Android compatibility layer reports. */
    val apiLevel: Int,
) {
    /**
     * Whether a Linux session can run here at all.
     *
     * True for HarmonyOS 4.x: it carries an Android 12 compatibility layer. A device below API 26 is
     * refused by the app's own `minSdk` long before this, so this only guards against a build that
     * reports a Huawei SoC with no Android layer under it.
     */
    val sessionCapable: Boolean get() = detected && apiLevel >= 26

    /** One line for the device card, or "" when this is not a HarmonyOS device. */
    fun label(): String = when {
        !detected -> ""
        version.isNotEmpty() -> "HarmonyOS $version (Android API $apiLevel)"
        else -> "HarmonyOS (Android API $apiLevel)"
    }

    companion object {
        fun detect(env: ProbeEnv): HarmonyOs {
            val huawei = env.manufacturer.equals("HUAWEI", ignoreCase = true) ||
                env.property("ro.board.platform").contains("kirin", ignoreCase = true) ||
                env.property("ro.board.platform").startsWith("hi", ignoreCase = true)
            if (!huawei) return HarmonyOs(detected = false, version = "", apiLevel = env.sdkInt)

            // `hw_sc.build.platform.version` states the HarmonyOS version directly; older builds
            // only carry the EMUI one, which is the same release numbered ten higher.
            val direct = env.property("hw_sc.build.platform.version")
                .ifBlank { env.property("ro.build.version.harmony") }
            val version = if (direct.isNotBlank()) numbers(direct) else fromEmui(env.property("ro.build.version.emui"))
            return HarmonyOs(
                detected = true,
                version = version,
                apiLevel = env.sdkInt,
            )
        }

        internal fun numbers(raw: String): String =
            Regex("""\d+(?:\.\d+){1,3}""").find(raw)?.value.orEmpty()

        /**
         * "EmotionUI_14.0.0" is HarmonyOS 4.0.0; "EmotionUI_13.1.0" is 3.1.0.
         *
         * EMUI numbering ran ten ahead of the HarmonyOS numbering for the same release, so a build
         * that only reports EMUI can still be described in the terms a user recognises.
         */
        internal fun fromEmui(raw: String): String {
            if (raw.isBlank()) return ""
            val match = Regex("""(\d+)((?:\.\d+){1,3})""").find(raw) ?: return ""
            val major = match.groupValues[1].toIntOrNull() ?: return ""
            val rest = match.groupValues[2]
            return if (major < 10) "$major$rest" else "${major - 10}$rest"
        }
    }
}
