package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.gpu.probe.GpuBackends
import com.droiddeck.launcher.gpu.probe.GpuProbeResult
import com.droiddeck.launcher.gpu.probe.GpuVendor
import java.io.File

/**
 * What GPU this is, in the terms the driver lists are sorted by.
 *
 * KGSL names the model ("Adreno740v2", "Adreno825"); the family decides which Turnip builds run on it
 * at all, and the support level is what the app has actually been tested on (Adreno 650, 725 and
 * newer) - an Adreno 610 may start, but it is not "supported" just because it is a Qualcomm chip.
 *
 * Non-Qualcomm parts carry their own families now, and are named by their vendor rather than folded
 * into "not an Adreno": a Kirin 9000 is ARM Mali and can run with an imported driver, a Kirin 9000S
 * is Maleoon and can only run on the CPU. Both are useful answers, and the driver lists and the
 * support card need to tell them apart.
 */
data class GpuInfo(
    /** "Adreno 740", or the vendor's own name when this is not an Adreno. */
    val name: String,
    /** The three-digit Adreno model (740, 825), or 0 when KGSL does not say. */
    val model: Int,
    val family: Family,
    /** The SoC as a person would say it ("Snapdragon 8 Gen 2 (QCS8550)"), or "" when the phone does not say. */
    val soc: String,
    /** Samsung's One UI on an 8 Gen 2: its Turnip needs the OneUI build, or frames tear and flicker. */
    val oneUi8Gen2: Boolean,
    /** KGSL's own name for the GPU, as the kernel spells it ("Adreno740v2", "Adreno33v2"). */
    val kgslName: String = "",
    /** Where [model] came from: "kernel", "vulkan" or "platform", "" when unknown. */
    val modelSource: String = "",
    val kgslChipId: String = "",
    val platformGpuModel: Int = 0,
    /** Who makes it. Defaults to Qualcomm so every existing construction still means what it meant. */
    val vendor: GpuVendor = GpuVendor.QUALCOMM,
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        /** Adreno 710/720/722: gen 7 cores cut down enough to need their own patches. */
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        /** An Adreno whose model KGSL does not give: treated as the newest family it could be. */
        ADRENO_UNKNOWN("Adreno"),
        /** ARM's licensed Mali, as on a Kirin 9000 and older. PanVK can be made to drive it. */
        MALI("ARM Mali"),
        /** Huawei's own GPU, as on a Kirin 9000S and newer. Nothing open drives it. */
        MALEOON("Huawei Maleoon"),
        NOT_ADRENO("Not an Adreno GPU");

        /** [label] in the app's language: the Adreno families are names, the rest is words. */
        fun label(context: Context): String = if (this == NOT_ADRENO) context.getString(R.string.gpuinfo_not_adreno) else label
    }

    enum class Support { TESTED, UNTESTED, UNSUPPORTED }

    private val isA850: Boolean get() = model == 850 || platformGpuModel == 850

    val support: Support
        get() = when {
            // Mali runs, but on a driver the user imports and nobody has validated for Steam here.
            family == Family.MALI -> Support.UNTESTED
            family == Family.MALEOON -> Support.UNSUPPORTED
            family == Family.NOT_ADRENO -> Support.UNSUPPORTED
            isA850 -> Support.UNTESTED
            family == Family.A6XX && model == 650 -> Support.TESTED
            family == Family.A8XX -> Support.TESTED
            family == Family.A7XX && model >= 725 -> Support.TESTED
            else -> Support.UNTESTED
        }

    /** One line for the device card and the system check, in English for the device report. */
    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> when {
                family == Family.MALI -> "Experimental ARM Mali (Kirin 9000 and older): needs an imported " +
                    "PanVK-kbase driver, and Steam is not validated on this device"
                isA850 -> "Experimental Adreno 850 support: requires compatible experimental display and game drivers; not verified on this device"
                family == Family.A7XX_LOW -> "Experimental: its drivers are test builds"
                else -> "Outside tested hardware (Adreno 650, 725 and newer): it may not run"
            }
            Support.UNSUPPORTED -> when {
                family == Family.MALEOON ->
                    "Huawei Maleoon (Kirin 9000S and newer) has no Linux driver: sessions can run, but only with CPU rendering"
                else -> "Not supported: DroidDeck needs an Adreno (Snapdragon) GPU"
            }
        }

    /** [supportText] in the app's language. */
    fun supportText(context: Context): String = context.getString(when {
        family == Family.MALI -> R.string.gpuinfo_mali_experimental
        family == Family.MALEOON -> R.string.gpuinfo_maleoon_software
        else -> when (support) {
            Support.TESTED -> R.string.gpuinfo_supported
            Support.UNTESTED -> when {
                isA850 -> R.string.gpuinfo_a850_experimental
                family == Family.A7XX_LOW -> R.string.gpuinfo_experimental
                else -> R.string.gpuinfo_below_tested
            }
            Support.UNSUPPORTED -> R.string.gpuinfo_unsupported
        }
    })

    fun displayName(context: Context): String = if (name == UNNAMED) context.getString(R.string.gpu_this_gpu) else name

    companion object {
        private const val UNNAMED = "this GPU"

        /**
         * Detect from the device itself.
         *
         * Adreno keeps the probe it always had, down to where the model number came from, because
         * every driver list in the app is keyed on it. Everything else is described by the backend
         * probe, which is the only thing that knows a Mali from a Maleoon.
         */
        fun detect(): GpuInfo {
            val probe = GpuBackends.probe()
            return when (probe.vendor) {
                GpuVendor.QUALCOMM -> adreno(probe)
                GpuVendor.ARM_MALI -> nonAdreno(probe, Family.MALI, probe.deviceName)
                GpuVendor.HUAWEI_MALEOON -> nonAdreno(probe, Family.MALEOON, probe.deviceName)
                // Xclipse, PowerVR and anything unnamed stay NOT_ADRENO: it is the family the app
                // has always used to mean "nothing here can drive it", and every caller already
                // handles it. A new family for the same fact would only add a case to each of them.
                else -> nonAdreno(probe, Family.NOT_ADRENO, probe.deviceName)
            }
        }

        private fun adreno(probe: GpuProbeResult): GpuInfo {
            val raw = FileUtils.readString(File("/sys/class/kgsl/kgsl-3d0/gpu_model"))?.trim()
            val chipId = FileUtils.readString(File("/sys/class/kgsl/kgsl-3d0/gpu_chipid"))?.trim().orEmpty()
            val platformGpuModel = platformModel()
            val (model, source) = modelFromNames(
                raw,
                { VulkanInfo.cachedOrNull()?.get("device") },
                { platformGpuModel },
            ).let { if (it.first > 0) it else probe.adrenoModel to "probe" }
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            return GpuInfo(
                name = if (model > 0) "Adreno $model" else "Adreno",
                model = model, family = familyOf(true, model), soc = SocNames.label(),
                oneUi8Gen2 = samsung && model == 740,
                kgslName = raw.orEmpty(), modelSource = source, kgslChipId = chipId,
                platformGpuModel = platformGpuModel,
                vendor = GpuVendor.QUALCOMM,
            )
        }

        private fun nonAdreno(probe: GpuProbeResult, family: Family, name: String): GpuInfo = GpuInfo(
            name = name.ifBlank { UNNAMED },
            model = 0,
            family = family,
            soc = probe.kirin?.let { "${it.marketing} (${it.gpu})" } ?: SocNames.label(),
            oneUi8Gen2 = false,
            vendor = probe.vendor,
        )

        internal fun threeDigits(text: String): Int = Regex("""(?i)\badreno\s*(?:\(TM\)\s*)?(\d{3})(?!\d)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        internal fun modelFromNames(
            kernelName: String?,
            vulkanName: () -> String?,
            fallback: () -> Int,
        ): Pair<Int, String> {
            kernelName?.let { threeDigits(it) }?.takeIf { it > 0 }?.let { return it to "kernel" }
            vulkanName()?.let { threeDigits(it) }?.takeIf { it > 0 }?.let { return it to "vulkan" }
            return fallback().let { if (it > 0) it to "platform" else 0 to "" }
        }

        private val PLATFORMS = mapOf(
            "msmnile" to 640, "kona" to 650, "lahaina" to 660, "taro" to 730, "cape" to 730,
            "kalama" to 740, "pineapple" to 750, "sun" to 830,
        )

        internal fun platformModel(
            platform: String = systemProperty("ro.board.platform"),
            qtiSocName: String = systemProperty("ro.vendor.qti.soc_name"),
            socModel: String = SocNames.model(),
        ): Int = if (SocNames.normalizedModel(socModel) == "SM8975" || SocNames.normalizedModel(qtiSocName) == "SM8975") 850
            else PLATFORMS[platform.lowercase()] ?: PLATFORMS[qtiSocName.lowercase()] ?: 0

        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }

        internal fun familyOf(adreno: Boolean, model: Int): Family = when {
            !adreno -> Family.NOT_ADRENO
            model == 0 -> Family.ADRENO_UNKNOWN
            model >= 800 -> Family.A8XX
            model in listOf(710, 720, 722) -> Family.A7XX_LOW
            model >= 700 -> Family.A7XX
            model >= 600 -> Family.A6XX
            else -> Family.ADRENO_UNKNOWN
        }
    }
}
