package com.droiddeck.launcher.gpu.probe

/**
 * Everything the probe is allowed to look at, behind one interface.
 *
 * The point is not abstraction for its own sake: it is that a Kirin phone cannot be plugged into a
 * build machine, so the only way a detection rule can be tested at all is to hand it a described
 * device instead of a real one. [AndroidProbeEnv] reads the phone; tests read a map.
 */
interface ProbeEnv {
    fun exists(path: String): Boolean

    /** The file's text, or null when it is missing or unreadable. */
    fun readText(path: String): String?

    /** A system property, or "" when it is unset. */
    fun property(name: String): String

    /** File names directly inside [dir], or empty when it cannot be listed. */
    fun list(dir: String): List<String>

    /** Build.VERSION.SDK_INT. */
    val sdkInt: Int

    /** Build.HARDWARE. */
    val hardware: String

    /** Build.MANUFACTURER. */
    val manufacturer: String

    /** The Vulkan device name, when it has already been queried; null otherwise. */
    val vulkanDeviceName: String?
}

/** The Vulkan driver libraries an Android device can carry, by vendor. */
internal object DriverPaths {
    /** Where Android's Vulkan HAL drivers live. Enumerated, not guessed - see [GpuProbe]. */
    const val HW_DIR = "/vendor/lib64/hw"

    const val ADRENO_VULKAN = "/vendor/lib64/hw/vulkan.adreno.so"
    const val MALI_VULKAN = "/vendor/lib64/hw/vulkan.mali.so"
    const val MALI_VULKAN_ALT = "/vendor/lib64/hw/vulkan.arm.mali.so"
    const val MALI_EGL = "/vendor/lib64/egl/libGLES_mali.so"
    const val MALEOON_VULKAN = "/vendor/lib64/hw/vulkan.maleoon.so"
    const val MALEOON_EGL = "/vendor/lib64/egl/libGLES_maleoon.so"

    /**
     * Huawei's own Vulkan driver, which is what a Kirin 9010 actually ships.
     *
     * The name is `hvgr` ("Huawei GPU renderer") and not `maleoon`, which is the GPU's marketing
     * name - a distinction only real hardware makes clear. Recorded from a Kirin 9010 handset:
     * `/vendor/lib64/hw/vulkan.hvgr_v200.so`, with no `/dev/mali0` and no `/dev/dri` on the device.
     */
    const val HVGR_VULKAN = "/vendor/lib64/hw/vulkan.hvgr_v200.so"

    const val XCLIPSE_VULKAN = "/vendor/lib64/hw/vulkan.xclipse.so"
    const val POWERVR_VULKAN = "/vendor/lib64/hw/vulkan.powerVR.so"

    const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    const val MALI_DEV = "/dev/mali0"
    const val MALI_SYS = "/sys/class/misc/mali0"

    /** Written by mali_kbase: "1" or a version string on CSF kernels. */
    const val MALI_CSF = "/sys/class/misc/mali0/device/csf_version"
    const val MALI_CSF_ALT = "/sys/class/misc/mali0/csf_version"
}
