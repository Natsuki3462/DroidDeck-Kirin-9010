package com.droiddeck.launcher.gpu.probe

/**
 * What the probe concluded about this device's GPU, in the terms a backend is chosen by.
 *
 * Everything here is read-only fact plus one derived judgement ([vendor]); no policy lives in it.
 * The rule that decides whether the GPU is handed to a guest belongs to [RenderBackend].
 */
data class GpuProbeResult(
    val vendor: GpuVendor,
    /** The GPU as this device names it: "Adreno 740", "Mali-G78 MP24", "Maleoon 910". */
    val deviceName: String,
    /** The three-digit Adreno model, or 0. Unchanged from the Adreno-only probe, for the driver lists. */
    val adrenoModel: Int,
    /** Which kbase generation the Mali kernel driver speaks, when there is one. */
    val kbase: KbaseInterface,
    /** The Kirin SoC this is, when the device is a Kirin at all. */
    val kirin: KirinSoc?,
    /** The kernel node the GPU is reached through, when one was found. */
    val kernelNode: String?,
    /** The vendor's Vulkan library, when one was found. */
    val driverLib: String?,
    val sdkInt: Int,
) {
    /** Whether this is the one path the app has actually been validated on. */
    val isAdreno: Boolean get() = vendor == GpuVendor.QUALCOMM

    /** A Mali-era Kirin (Kirin 9000 and older): licensed Mali, so a Mesa backend can exist. */
    val isMaliKirin: Boolean get() = vendor == GpuVendor.ARM_MALI

    /** A Maleoon-era Kirin (Kirin 9000S and newer): Huawei's own GPU, no Mesa backend exists. */
    val isMaleoonKirin: Boolean get() = vendor == GpuVendor.HUAWEI_MALEOON

    /** One line for the device card and the report. */
    fun summary(): String = buildString {
        append(deviceName)
        kirin?.let { append(" · ").append(it.marketing).append(" (").append(it.era.label).append(')') }
        if (kbase != KbaseInterface.NONE) append(" · ").append(kbase.label)
    }
}

/**
 * Reads a device and says what its GPU is.
 *
 * Detection is by evidence, never by brand: a Huawei phone whose GPU reports "Maleoon 910" is Maleoon
 * whatever its SoC table says, and a phone with only `/dev/mali0` and no SoC match is still Mali. The
 * order below is the priority order - Adreno first because it is the only vendor whose node and
 * driver are unambiguous, then Huawei's own GPU, then ARM's.
 *
 * Driver libraries are **enumerated, not guessed**. A Kirin 9010 handset ships its Vulkan driver as
 * `/vendor/lib64/hw/vulkan.hvgr_v200.so` - `hvgr`, not `maleoon`, and the device has no `/dev/mali0`
 * and no `/dev/dri` at all. Hard-coding the name the marketing material suggests would have missed
 * the device entirely, so [driverLibs] lists the HAL directory and classifies what it finds.
 */
object GpuProbe {

    fun probe(env: ProbeEnv): GpuProbeResult {
        val vulkanName = env.vulkanDeviceName.orEmpty()
        val kirin = KirinCatalog.identify(
            env.property("ro.board.platform"),
            env.property("ro.soc.model"),
            env.property("ro.hardware"),
            env.hardware,
            env.property("ro.vendor.product.device"),
        )
        val libs = driverLibs(env)
        val maliNode = listOf(DriverPaths.MALI_DEV, DriverPaths.MALI_SYS).firstOrNull { env.exists(it) }

        // 1. Adreno: kgsl's node, or Qualcomm's own Vulkan driver, is proof on its own.
        if (env.exists(DriverPaths.KGSL) || libs.containsKey(GpuVendor.QUALCOMM)) {
            val model = adrenoModel(env, vulkanName)
            return GpuProbeResult(
                vendor = GpuVendor.QUALCOMM,
                deviceName = if (model > 0) "Adreno $model" else "Adreno",
                adrenoModel = model,
                kbase = KbaseInterface.NONE,
                kirin = kirin,
                kernelNode = DriverPaths.KGSL.takeIf { env.exists(it) },
                driverLib = libs[GpuVendor.QUALCOMM],
                sdkInt = env.sdkInt,
            )
        }

        // 2. Maleoon: the GPU's own name, Huawei's driver, or a Maleoon-era Kirin with no Mali node.
        val maleoonNamed = vulkanName.contains("maleoon", ignoreCase = true)
        if (maleoonNamed || libs.containsKey(GpuVendor.HUAWEI_MALEOON) ||
            (kirin?.era == KirinSoc.Era.MALEOON && maliNode == null)
        ) {
            return GpuProbeResult(
                vendor = GpuVendor.HUAWEI_MALEOON,
                deviceName = maleoonName(vulkanName) ?: kirin?.gpu ?: "Maleoon",
                adrenoModel = 0,
                kbase = KbaseInterface.NONE,
                kirin = kirin,
                kernelNode = null,
                driverLib = libs[GpuVendor.HUAWEI_MALEOON],
                sdkInt = env.sdkInt,
            )
        }

        // 3. ARM Mali: a kbase node, or ARM's own driver libraries.
        if (maliNode != null || libs.containsKey(GpuVendor.ARM_MALI)) {
            return GpuProbeResult(
                vendor = GpuVendor.ARM_MALI,
                deviceName = maliName(vulkanName) ?: kirin?.gpu ?: "Mali",
                adrenoModel = 0,
                kbase = kbaseInterface(env, maliNode, kirin),
                kirin = kirin,
                kernelNode = maliNode,
                driverLib = libs[GpuVendor.ARM_MALI],
                sdkInt = env.sdkInt,
            )
        }

        // 4. Named-but-unsupported vendors, so the card can say why rather than "unknown".
        val named = when {
            libs.containsKey(GpuVendor.SAMSUNG_XCLIPSE) || vulkanName.contains("xclipse", true) ->
                GpuVendor.SAMSUNG_XCLIPSE
            libs.containsKey(GpuVendor.IMAGINATION) || vulkanName.contains("powervr", true) ->
                GpuVendor.IMAGINATION
            else -> GpuVendor.UNKNOWN
        }
        return GpuProbeResult(
            vendor = named,
            deviceName = vulkanName.ifBlank { env.hardware.ifBlank { "this GPU" } },
            adrenoModel = 0,
            kbase = KbaseInterface.NONE,
            kirin = kirin,
            kernelNode = null,
            driverLib = libs[named],
            sdkInt = env.sdkInt,
        )
    }

    /**
     * Every vendor Vulkan driver installed, by vendor.
     *
     * Known paths first, because they are cheap; then the HAL directory is listed so a vendor that
     * names its driver something unexpected is still found. First match per vendor wins.
     */
    internal fun driverLibs(env: ProbeEnv): Map<GpuVendor, String> {
        val out = LinkedHashMap<GpuVendor, String>()
        val known = listOf(
            DriverPaths.ADRENO_VULKAN to GpuVendor.QUALCOMM,
            DriverPaths.MALEOON_VULKAN to GpuVendor.HUAWEI_MALEOON,
            DriverPaths.HVGR_VULKAN to GpuVendor.HUAWEI_MALEOON,
            DriverPaths.MALI_VULKAN to GpuVendor.ARM_MALI,
            DriverPaths.MALI_VULKAN_ALT to GpuVendor.ARM_MALI,
            DriverPaths.XCLIPSE_VULKAN to GpuVendor.SAMSUNG_XCLIPSE,
            DriverPaths.POWERVR_VULKAN to GpuVendor.IMAGINATION,
        )
        for ((path, vendor) in known) if (env.exists(path)) out.putIfAbsent(vendor, path)
        for (name in env.list(DriverPaths.HW_DIR)) {
            val vendor = vendorOfDriverFile(name) ?: continue
            out.putIfAbsent(vendor, "${DriverPaths.HW_DIR}/$name")
        }
        return out
    }

    /** Which vendor a HAL driver file belongs to, from its name alone. */
    internal fun vendorOfDriverFile(name: String): GpuVendor? {
        val n = name.lowercase()
        if (!n.startsWith("vulkan") || !n.endsWith(".so")) return null
        return when {
            n.contains("adreno") -> GpuVendor.QUALCOMM
            // Huawei ships its GPU driver as "hvgr" (GPU renderer); "maleoon" is the marketing name.
            n.contains("hvgr") || n.contains("maleoon") -> GpuVendor.HUAWEI_MALEOON
            n.contains("mali") -> GpuVendor.ARM_MALI
            n.contains("xclipse") -> GpuVendor.SAMSUNG_XCLIPSE
            n.contains("powervr") -> GpuVendor.IMAGINATION
            else -> null
        }
    }

    /**
     * CSF or JM.
     *
     * kbase does not expose the generation in one portable file, so this reads what it can and
     * falls back to what the SoC is known to be - and says "unknown" rather than guessing when both
     * are silent, because a wrong answer here picks a driver that cannot work.
     */
    internal fun kbaseInterface(env: ProbeEnv, node: String?, kirin: KirinSoc?): KbaseInterface {
        if (node == null) return KbaseInterface.NONE
        for (path in listOf(DriverPaths.MALI_CSF, DriverPaths.MALI_CSF_ALT)) {
            val text = env.readText(path)?.trim()
            if (!text.isNullOrEmpty() && text != "0") return KbaseInterface.CSF
        }
        // A CSF kernel names its firmware node; JM kernels do not.
        if (env.exists("/sys/class/misc/mali0/device/csf_fw_version")) return KbaseInterface.CSF
        return kirin?.kbase?.takeIf { it != KbaseInterface.NONE } ?: KbaseInterface.UNKNOWN
    }

    internal fun adrenoModel(env: ProbeEnv, vulkanName: String): Int {
        val fromKernel = threeDigits(env.readText("/sys/class/kgsl/kgsl-3d0/gpu_model").orEmpty())
        if (fromKernel > 0) return fromKernel
        val fromVulkan = threeDigits(vulkanName)
        if (fromVulkan > 0) return fromVulkan
        return 0
    }

    /** Same rule the Adreno probe has always used: "Adreno740v2", "Adreno (TM) 830". */
    internal fun threeDigits(text: String): Int =
        Regex("""(?i)\badreno\s*(?:\(TM\)\s*)?(\d{3})(?!\d)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** "Mali-G78" from a Vulkan name, when it carries one. */
    internal fun maliName(vulkanName: String): String? =
        Regex("""((?:Mali|Immortalis)-[GT]\d{2,4}(?:\s+MC\d+)?)""", RegexOption.IGNORE_CASE)
            .find(vulkanName)?.groupValues?.get(1)

    /** "Maleoon 910" from a Vulkan name, when it carries one. Spacing is normalised. */
    internal fun maleoonName(vulkanName: String): String? {
        val digits = Regex("""Maleoon\s*(\d{3})""", RegexOption.IGNORE_CASE)
            .find(vulkanName)?.groupValues?.get(1) ?: return null
        return "Maleoon $digits"
    }
}
