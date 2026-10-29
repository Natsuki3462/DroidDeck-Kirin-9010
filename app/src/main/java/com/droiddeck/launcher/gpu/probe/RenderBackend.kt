package com.droiddeck.launcher.gpu.probe

/**
 * How much is known about a backend actually working, as opposed to how likely it looks.
 *
 * [SAFE] is reserved for paths that have been validated on hardware this project ships support for.
 * [BLOCKED] is not "we have not tried it": it means the path cannot work at all on this device, and
 * offering it would only produce a black screen with nothing in it to read.
 */
enum class Risk { SAFE, EXPERIMENTAL, BLOCKED }

/** What a backend needs installed before it can draw anything. */
enum class DriverRequirement {
    /** The driver built into the Linux runtime. Adreno's Turnip. */
    RUNTIME_DEFAULT,

    /** A driver the user has to import explicitly, because shipping it is not defensible. */
    IMPORTED_ICD,

    /** A CPU renderer, so no GPU driver is involved at all. */
    CPU_SOFTWARE,

    /** Nothing can draw. The backend exists only to explain why. */
    NONE_AVAILABLE,
}

/**
 * A backend's answer for one device: what it would do, and what it costs.
 *
 * This is data, not behaviour, so the choice between backends can be asserted in a unit test on a
 * machine with no phone attached - which is the whole reason the layer exists.
 */
data class BackendPlan(
    val backendId: String,
    val displayName: String,
    val risk: Risk,
    /** Whether the guest is handed the GPU's kernel node at all. */
    val gpuInGuest: Boolean,
    val driverRequirement: DriverRequirement,
    /** Guest environment this backend adds. The ICD path is not here: the caller composes it. */
    val env: Map<String, String> = emptyMap(),
    /** DXVK settings this backend needs, as `dxvk.conf` keys. */
    val dxvk: Map<String, String> = emptyMap(),
    /** Host-to-guest binds, in `src:dst` form. */
    val binds: List<String> = emptyList(),
    /** Why this is the answer, in one sentence a bug report can quote. */
    val reason: String,
)

/** One way of drawing a Linux session on an Android GPU. */
interface RenderBackend {
    val id: String

    /** What this backend can do on [probe], or null when the device is not its business. */
    fun evaluate(probe: GpuProbeResult): BackendPlan?
}
