package com.droiddeck.launcher.gpu.probe

/**
 * Who makes the GPU, as the driver stack is what actually differs - not the marketing name.
 *
 * The split matters because each vendor has a different kernel interface, a different set of
 * user-space drivers, and therefore a different answer to "can a Linux guest draw with it at all".
 */
enum class GpuVendor(val label: String) {
    /** Qualcomm Adreno: kgsl kernel driver, Mesa Turnip in the runtime. The tested path. */
    QUALCOMM("Qualcomm Adreno"),

    /** ARM Mali: mali_kbase kernel driver, PanVK (or the vendor's own blob). */
    ARM_MALI("ARM Mali"),

    /** Huawei Maleoon (Kirin 9000S and newer): closed driver, no Mesa backend exists. */
    HUAWEI_MALEOON("Huawei Maleoon"),

    /** Samsung Xclipse (RDNA-based): no guest driver path here either. */
    SAMSUNG_XCLIPSE("Samsung Xclipse"),

    /** Imagination PowerVR. */
    IMAGINATION("Imagination PowerVR"),

    /** Something the probe could not name. Never guessed into a vendor. */
    UNKNOWN("Unknown GPU"),
}
