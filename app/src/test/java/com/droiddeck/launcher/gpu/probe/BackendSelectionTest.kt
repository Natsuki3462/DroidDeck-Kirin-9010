package com.droiddeck.launcher.gpu.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSelectionTest {

    private val adreno = GpuProbe.probe(
        FakeEnv(files = setOf(DriverPaths.KGSL), contents = mapOf("/sys/class/kgsl/kgsl-3d0/gpu_model" to "Adreno740"))
    )

    private val maliCsf = GpuProbe.probe(
        FakeEnv(
            files = setOf(DriverPaths.MALI_DEV, DriverPaths.MALI_CSF),
            contents = mapOf(DriverPaths.MALI_CSF to "1"),
            vulkanDeviceName = "Mali-G615 MC6",
        )
    )

    private val kirin9000 = GpuProbe.probe(
        FakeEnv(files = setOf(DriverPaths.MALI_DEV), props = mapOf("ro.board.platform" to "kirin9000"))
    )

    private val kirin9000s = GpuProbe.probe(
        FakeEnv(props = mapOf("ro.board.platform" to "kirin9000s"))
    )

    @Test fun adrenoIsUnchangedInEveryPolicy() {
        // The point of the whole layer: adding vendors must not move the validated path.
        for (policy in GpuPolicy.values()) {
            val chosen = BackendRegistry.select(adreno, policy).chosen
            if (policy == GpuPolicy.FORCE_SOFTWARE) {
                assertEquals("lavapipe", chosen.backendId)
            } else {
                assertEquals("turnip", chosen.backendId)
                assertEquals(Risk.SAFE, chosen.risk)
                assertTrue(chosen.gpuInGuest)
            }
        }
    }

    @Test fun theDefaultPolicyNeverHandsAnUnvalidatedGpuToTheGuest() {
        // Mali and Maleoon both default to CPU rendering: a slow session beats a kernel hang.
        assertEquals("lavapipe", BackendRegistry.select(maliCsf, GpuPolicy.AUTO).chosen.backendId)
        assertEquals("lavapipe", BackendRegistry.select(kirin9000, GpuPolicy.AUTO).chosen.backendId)
        assertEquals("lavapipe", BackendRegistry.select(kirin9000s, GpuPolicy.AUTO).chosen.backendId)
    }

    @Test fun theDefaultPolicyStillOffersTheExperimentalPathInTheCandidates() {
        // Auto does not use PanVK, but the device card has to be able to say it exists.
        val decision = BackendRegistry.select(maliCsf, GpuPolicy.AUTO)
        val panvk = decision.candidates.first { it.backendId == "panvk-kbase" }
        assertEquals(Risk.EXPERIMENTAL, panvk.risk)
        assertEquals(DriverRequirement.IMPORTED_ICD, panvk.driverRequirement)
    }

    @Test fun askingForHardwareOnAValidatedMaliGetsPanVk() {
        val chosen = BackendRegistry.select(maliCsf, GpuPolicy.PREFER_HARDWARE).chosen
        assertEquals("panvk-kbase", chosen.backendId)
        assertEquals(Risk.EXPERIMENTAL, chosen.risk)
        assertTrue(chosen.gpuInGuest)
    }

    @Test fun askingForHardwareStillDoesNotPickABlockedBackend() {
        // Kirin 9000 is Job Manager, and the public PanVK-kbase builds are CSF-only.
        val decision = BackendRegistry.select(kirin9000, GpuPolicy.PREFER_HARDWARE)
        assertEquals("lavapipe", decision.chosen.backendId)
        val blocked = decision.candidates.first { it.backendId == "panvk-kbase" }
        assertEquals(Risk.BLOCKED, blocked.risk)
        assertFalse(blocked.gpuInGuest)
    }

    @Test fun maleoonIsBlockedForHardwareAndExplainsWhy() {
        val decision = BackendRegistry.select(kirin9000s, GpuPolicy.PREFER_HARDWARE)
        assertEquals("lavapipe", decision.chosen.backendId)
        val bridge = decision.candidates.first { it.backendId == "vendor-bridge" }
        assertEquals(Risk.BLOCKED, bridge.risk)
        assertTrue(bridge.reason.contains("bionic"))
    }

    @Test fun theMaliGpuIsBoundAsMaliAndNotDisguisedAsADrmNode() {
        // Handing /dev/mali0 over as /dev/dri/renderD* invites DRM ioctls at a driver that never
        // implemented them. PanVK opens the kbase node itself.
        val panvk = BackendRegistry.select(maliCsf, GpuPolicy.PREFER_HARDWARE).chosen
        assertEquals(listOf("/dev/mali0:/dev/mali0"), panvk.binds)
        assertTrue(panvk.binds.none { it.contains("renderD") })
        assertTrue(panvk.binds.none { it.contains("dri") })
    }

    @Test fun theCpuBackendNeverBindsAGpuNodeWhateverTheDevice() {
        for (probe in listOf(adreno, maliCsf, kirin9000, kirin9000s)) {
            val chosen = BackendRegistry.select(probe, GpuPolicy.FORCE_SOFTWARE).chosen
            assertEquals("lavapipe", chosen.backendId)
            assertEquals(DriverRequirement.CPU_SOFTWARE, chosen.driverRequirement)
            assertTrue(chosen.binds.isEmpty())
            assertFalse(chosen.gpuInGuest)
        }
    }

    @Test fun thereIsAlwaysAnAnswer() {
        // No device may fall through to "nothing can draw": that is the black-screen bug.
        val unknown = GpuProbe.probe(FakeEnv())
        for (probe in listOf(adreno, maliCsf, kirin9000, kirin9000s, unknown)) {
            for (policy in GpuPolicy.values()) {
                val decision = BackendRegistry.select(probe, policy)
                assertTrue(decision.chosen.risk != Risk.BLOCKED)
                assertTrue(decision.candidates.isNotEmpty())
            }
        }
    }

    @Test fun theMaliPlanCarriesTheTuningThatKeepsDxvkAlive() {
        // Unified memory: without a capped heap DXVK allocates in 256 MiB chunks and dies.
        val panvk = BackendRegistry.select(maliCsf, GpuPolicy.PREFER_HARDWARE).chosen
        assertEquals("0.12", panvk.env["PANVK_HEAP_MEMORY_PERCENT"])
        assertEquals("384", panvk.dxvk["dxvk.maxMemoryBudget"])
        assertEquals("mali", panvk.env["BL_GPU_FAMILY"])
    }
}
