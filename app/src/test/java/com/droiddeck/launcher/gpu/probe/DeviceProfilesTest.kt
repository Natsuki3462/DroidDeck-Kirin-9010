package com.droiddeck.launcher.gpu.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kirin 9010 is the device this was built for first, so its tuning is asserted first.
 */
class DeviceProfilesTest {

    private fun kirin(platform: String) = GpuProbe.probe(FakeEnv(props = mapOf("ro.board.platform" to platform)))

    @Test fun kirin9010GetsSoftwareTuningAndSaysWhy() {
        val probe = kirin("kirin9010")
        val tuning = DeviceProfiles.forProbe(probe, cpuCores = 8)!!
        assertEquals("Kirin 9010 (software)", tuning.label)
        assertEquals(60, tuning.resolutionScalePercent)
        assertEquals(60, tuning.maxRefreshHz)
        // Both of these need a Vulkan device the device does not have.
        assertFalse(tuning.frameGeneration)
        assertFalse(tuning.upscaling)
        assertTrue(tuning.notes.any { it.contains("Maleoon 910") })
        assertTrue(tuning.notes.any { it.contains("CPU") })
    }

    @Test fun theThreadCountLeavesCoresForAndroidAndSteam() {
        // 8 cores -> 6 workers, never the whole SoC: starving the compositor makes it worse, not better.
        assertEquals("6", DeviceProfiles.forProbe(kirin("kirin9010"), cpuCores = 8)!!.env["LP_NUM_THREADS"])
        assertEquals("2", DeviceProfiles.forProbe(kirin("kirin9010"), cpuCores = 4)!!.env["LP_NUM_THREADS"])
        assertEquals("6", DeviceProfiles.forProbe(kirin("kirin9010"), cpuCores = 12)!!.env["LP_NUM_THREADS"])
    }

    @Test fun everyMaleoonEraPartSharesTheTuning() {
        // Same GPU family, same reason: 9000S, 9010 and 9020 are one problem.
        for (soc in listOf("kirin9000s", "kirin9010", "kirin9020", "kirin9020a")) {
            val tuning = DeviceProfiles.forProbe(kirin(soc))!!
            assertEquals(soc, 60, tuning.resolutionScalePercent)
            assertFalse(soc, tuning.frameGeneration)
        }
    }

    @Test fun maliEraKirinGetsNoOverride() {
        // Kirin 9000 and older can be handed to PanVK; overriding its resolution would be guessing.
        assertNull(DeviceProfiles.forProbe(kirin("kirin9000")))
        assertNull(DeviceProfiles.forProbe(kirin("kirin970")))
    }

    @Test fun adrenoIsNotProfiledHereAtAll() {
        val adreno = GpuProbe.probe(
            FakeEnv(files = setOf(DriverPaths.KGSL), contents = mapOf("/sys/class/kgsl/kgsl-3d0/gpu_model" to "Adreno740"))
        )
        assertNull(DeviceProfiles.forProbe(adreno))
    }

    @Test fun aNonKirinDeviceIsNotGivenKirinTuning() {
        assertNull(DeviceProfiles.forProbe(GpuProbe.probe(FakeEnv())))
    }
}
