package com.droiddeck.launcher.gpu.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from a backend decision to what the session actually does.
 *
 * The first thing asserted is that Adreno is untouched: this layer exists to add vendors, not to
 * move the path the project has shipped for a year.
 */
class SessionGpuPlanTest {

    private val adreno = GpuProbe.probe(
        FakeEnv(files = setOf(DriverPaths.KGSL), contents = mapOf("/sys/class/kgsl/kgsl-3d0/gpu_model" to "Adreno740"))
    )

    /** A Kirin 9010 handset, as recorded from hardware. */
    private val kirin9010 = GpuProbe.probe(
        FakeEnv(
            files = setOf("/vendor/lib64/hw/vulkan.hvgr_v200.so"),
            props = mapOf("ro.board.platform" to "Kirin9010"),
            sdkInt = 31,
            hardware = "Kirin9010",
            manufacturer = "HUAWEI",
            vulkanDeviceName = "Maleoon 910",
        )
    )

    private val maliCsf = GpuProbe.probe(
        FakeEnv(
            files = setOf(DriverPaths.MALI_DEV, DriverPaths.MALI_CSF),
            contents = mapOf(DriverPaths.MALI_CSF to "1"),
            vulkanDeviceName = "Mali-G615 MC6",
        )
    )

    private fun plan(probe: GpuProbeResult, policy: GpuPolicy, icd: String? = "/opt/lvp/lvp_icd.json") =
        SessionGpu.plan(
            BackendRegistry.select(probe, policy),
            icd,
            DeviceProfiles.forProbe(probe, cpuCores = 8),
        )

    @Test fun adrenoKeepsWritingItsOwnEnvironment() {
        // keepAdrenoDefaults means SessionService emits exactly the three lines it always has.
        val plan = plan(adreno, GpuPolicy.AUTO)
        assertTrue(plan.keepAdrenoDefaults)
        assertFalse(plan.software)
        assertTrue(plan.env.isEmpty())
        assertNull(plan.icdPath)
        assertEquals(100, plan.resolutionScalePercent)
        assertEquals(0, plan.maxRefreshHz)
        assertFalse(plan.missingSoftwareDriver)
    }

    @Test fun aKirin9010BecomesACpuSessionWithItsProfile() {
        val plan = plan(kirin9010, GpuPolicy.AUTO)
        assertTrue(plan.software)
        assertFalse(plan.keepAdrenoDefaults)
        assertEquals("/opt/lvp/lvp_icd.json", plan.icdPath)
        // The loader finds lavapipe's LLVM and shims only if the pack's directory is named.
        assertEquals("/opt/lvp", plan.libraryDir)
        assertEquals(60, plan.resolutionScalePercent)
        assertEquals(60, plan.maxRefreshHz)
        // The lavapipe backend's own environment, plus the device profile's threading.
        assertEquals("1", plan.env["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals("llvmpipe", plan.env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("6", plan.env["LP_NUM_THREADS"])
        assertEquals("/var/cache/droiddeck/mesa", plan.env["MESA_SHADER_CACHE_DIR"])
    }

    @Test fun aCpuSessionWithNoImportedDriverSaysSo() {
        // Without a CPU Vulkan driver the guest has no Vulkan device at all. The plan must not
        // pretend otherwise - the session log has to carry the reason.
        val plan = plan(kirin9010, GpuPolicy.AUTO, icd = null)
        assertTrue(plan.software)
        assertTrue(plan.missingSoftwareDriver)
        assertNull(plan.icdPath)
    }

    @Test fun forcingSoftwareOnAdrenoDropsTheAdrenoEnvironment() {
        // zink/kopper point at the GPU; a forced-software session must not ask for them.
        val plan = plan(adreno, GpuPolicy.FORCE_SOFTWARE)
        assertTrue(plan.software)
        assertFalse(plan.keepAdrenoDefaults)
        assertEquals("llvmpipe", plan.env["MESA_LOADER_DRIVER_OVERRIDE"])
        // No device profile for Adreno, so nothing is scaled.
        assertEquals(100, plan.resolutionScalePercent)
    }

    @Test fun aMaliGpuSessionBringsItsOwnEnvironmentAndIsNotScaled() {
        val plan = plan(maliCsf, GpuPolicy.PREFER_HARDWARE)
        assertFalse(plan.software)
        assertFalse(plan.keepAdrenoDefaults)
        assertEquals("mali", plan.env["BL_GPU_FAMILY"])
        assertNull(plan.icdPath)
        assertEquals(100, plan.resolutionScalePercent)
    }

    @Test fun softwareDriversAreRecognisedByAnyNameTheirAuthorChose() {
        for (name in listOf("lavapipe", "mesa-lavapipe-24.1.0", "lvp", "LVP-icd", "llvmpipe", "swrast", "softpipe")) {
            assertTrue(name, SessionGpu.isSoftwareDriverName(name))
        }
        for (name in listOf("turnip", "mesa-turnip-25.1.0", "lvpx", "adreno", "", null)) {
            assertFalse("$name", SessionGpu.isSoftwareDriverName(name))
        }
    }

    @Test fun aGpuSessionNamesNoLibraryDirectory() {
        assertNull(plan(adreno, GpuPolicy.AUTO).libraryDir)
    }

    @Test fun theDriverPickedOnTheComponentsScreenWins() {
        // Two lavapipe packs installed side by side: alphabetical order must not decide.
        val installed = listOf("lavapipe_Mesa_20.3_", "lavapipe_Mesa_26.2_")
        val names = mapOf("lavapipe_Mesa_20.3_" to "lavapipe (Mesa 20.3)", "lavapipe_Mesa_26.2_" to "lavapipe (Mesa 26.2)")
        assertEquals(
            "lavapipe_Mesa_26.2_",
            SessionGpu.pickSoftwareDriver(installed, "lavapipe_Mesa_26.2_") { names[it].orEmpty() },
        )
        assertEquals(
            "lavapipe_Mesa_20.3_",
            SessionGpu.pickSoftwareDriver(installed, "lavapipe_Mesa_20.3_") { names[it].orEmpty() },
        )
    }

    @Test fun aChosenDriverThatIsNotACpuRendererIsNotUsedForSoftware() {
        // Someone selected a Turnip pack; a software session must not load it.
        val installed = listOf("mesa-turnip-25.1.0", "lavapipe_Mesa_26.2_")
        val names = mapOf("mesa-turnip-25.1.0" to "Turnip 25.1.0", "lavapipe_Mesa_26.2_" to "lavapipe (Mesa 26.2)")
        assertEquals(
            "lavapipe_Mesa_26.2_",
            SessionGpu.pickSoftwareDriver(installed, "mesa-turnip-25.1.0") { names[it].orEmpty() },
        )
    }

    @Test fun withNoChoiceTheFirstCpuRendererIsUsed() {
        val installed = listOf("mesa-turnip-25.1.0", "lavapipe_Mesa_26.2_")
        val names = mapOf("mesa-turnip-25.1.0" to "Turnip 25.1.0", "lavapipe_Mesa_26.2_" to "lavapipe (Mesa 26.2)")
        assertEquals("lavapipe_Mesa_26.2_", SessionGpu.pickSoftwareDriver(installed, "") { names[it].orEmpty() })
        assertNull(SessionGpu.pickSoftwareDriver(listOf("mesa-turnip-25.1.0"), "") { "Turnip 25.1.0" })
    }

    @Test fun theRenderScaleStaysEvenAndNeverCollapses() {
        // A Kirin 9010 panel: 2844x1260 landscape.
        assertEquals(1706, SessionGpu.scaledOutput(2844, 60))
        assertEquals(756, SessionGpu.scaledOutput(1260, 60))
        // Odd results are rounded down to an even number, so no half-pixel seam survives.
        assertEquals(1420, SessionGpu.scaledOutput(2843, 50))
        // Unscaled stays exactly as it was.
        assertEquals(2844, SessionGpu.scaledOutput(2844, 100))
        // A tiny scale cannot produce a size gamescope refuses.
        assertEquals(320, SessionGpu.scaledOutput(1000, 10))
    }

    @Test fun theLogLineSaysWhichPathDrewTheFrame() {
        val text = SessionGpu.describe(plan(kirin9010, GpuPolicy.AUTO))
        assertTrue(text.contains("lavapipe"))
        assertTrue(text.contains("software"))
        assertTrue(text.contains("60%"))
        assertTrue(text.contains("60 Hz"))
    }
}
