package com.droiddeck.launcher.gpu.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuProbeTest {

    private fun probe(env: FakeEnv) = GpuProbe.probe(env)

    @Test fun adrenoIsRecognisedByItsOwnNodeAndNothingElse() {
        val result = probe(
            FakeEnv(
                files = setOf(DriverPaths.KGSL, DriverPaths.ADRENO_VULKAN),
                contents = mapOf("/sys/class/kgsl/kgsl-3d0/gpu_model" to "Adreno740v2"),
                hardware = "kalama",
            )
        )
        assertEquals(GpuVendor.QUALCOMM, result.vendor)
        assertEquals(740, result.adrenoModel)
        assertEquals("Adreno 740", result.deviceName)
        assertEquals(DriverPaths.KGSL, result.kernelNode)
        assertTrue(result.isAdreno)
    }

    @Test fun adrenoWinsEvenWhenAMaliNodeIsAlsoPresent() {
        // A device that exposes both is a Qualcomm one with a stray node; picking Mali there would
        // hand a working phone a driver it does not need.
        val result = probe(FakeEnv(files = setOf(DriverPaths.KGSL, DriverPaths.MALI_DEV)))
        assertEquals(GpuVendor.QUALCOMM, result.vendor)
    }

    @Test fun aKirin9000IsMaliOnJobManager() {
        val result = probe(
            FakeEnv(
                files = setOf(DriverPaths.MALI_DEV),
                props = mapOf("ro.board.platform" to "kirin9000"),
                hardware = "kirin9000",
                manufacturer = "HUAWEI",
            )
        )
        assertEquals(GpuVendor.ARM_MALI, result.vendor)
        assertEquals("Mali-G78 MP24", result.deviceName)
        assertEquals(KbaseInterface.JM, result.kbase)
        assertEquals("kirin9000", result.kirin?.id)
        assertTrue(result.isMaliKirin)
    }

    @Test fun aKirin970IsStillMaliButAnOlderOne() {
        val result = probe(
            FakeEnv(
                files = setOf(DriverPaths.MALI_DEV),
                props = mapOf("ro.board.platform" to "hi3670"),
            )
        )
        assertEquals(GpuVendor.ARM_MALI, result.vendor)
        assertEquals("kirin970", result.kirin?.id)
        assertEquals("Mali-G72 MP12", result.deviceName)
        assertEquals(KbaseInterface.JM, result.kbase)
    }

    @Test fun csfIsReadFromTheKernelNotGuessedFromTheName() {
        val result = probe(
            FakeEnv(
                files = setOf(DriverPaths.MALI_DEV, DriverPaths.MALI_CSF),
                contents = mapOf(DriverPaths.MALI_CSF to "1"),
                vulkanDeviceName = "Mali-G615 MC6",
            )
        )
        assertEquals(GpuVendor.ARM_MALI, result.vendor)
        assertEquals(KbaseInterface.CSF, result.kbase)
        assertEquals("Mali-G615 MC6", result.deviceName)
    }

    @Test fun aKirin9000sIsMaleoonWithNoMaliNode() {
        val result = probe(
            FakeEnv(
                props = mapOf("ro.board.platform" to "kirin9000s", "ro.soc.model" to "Kirin 9000S"),
                manufacturer = "HUAWEI",
            )
        )
        assertEquals(GpuVendor.HUAWEI_MALEOON, result.vendor)
        assertEquals("Maleoon 910", result.deviceName)
        assertEquals("kirin9000s", result.kirin?.id)
        assertTrue(result.isMaleoonKirin)
        assertNull(result.kernelNode)
    }

    @Test fun maleoonIsAlsoRecognisedFromTheGpusOwnName() {
        // No SoC table hit, no Huawei library path: the GPU still names itself.
        val result = probe(FakeEnv(vulkanDeviceName = "Maleoon 910"))
        assertEquals(GpuVendor.HUAWEI_MALEOON, result.vendor)
        assertEquals("Maleoon 910", result.deviceName)
    }

    @Test fun maleoonLibrariesAreEnoughWithoutAName() {
        val result = probe(FakeEnv(files = setOf(DriverPaths.MALEOON_VULKAN)))
        assertEquals(GpuVendor.HUAWEI_MALEOON, result.vendor)
        assertEquals(DriverPaths.MALEOON_VULKAN, result.driverLib)
    }

    @Test fun otherVendorsAreNamedRatherThanCalledUnknown() {
        assertEquals(GpuVendor.SAMSUNG_XCLIPSE, probe(FakeEnv(vulkanDeviceName = "Samsung Xclipse 920")).vendor)
        assertEquals(GpuVendor.IMAGINATION, probe(FakeEnv(vulkanDeviceName = "PowerVR D-Series DXT-48")).vendor)
    }

    @Test fun anEmptyDeviceIsUnknownAndSaysSo() {
        val result = probe(FakeEnv(hardware = "qcom"))
        assertEquals(GpuVendor.UNKNOWN, result.vendor)
        assertEquals("qcom", result.deviceName)
        assertEquals(KbaseInterface.NONE, result.kbase)
    }

    @Test fun maliNamesAreReadFromWhatTheGpuReports() {
        assertEquals("Mali-G78", GpuProbe.maliName("Mali-G78 r0p1"))
        assertEquals("Mali-G615 MC6", GpuProbe.maliName("Mali-G615 MC6"))
        assertEquals("Immortalis-G720 MC12", GpuProbe.maliName("Immortalis-G720 MC12"))
        assertNull(GpuProbe.maliName("Adreno (TM) 740"))
        assertNull(GpuProbe.maliName(""))
    }

    @Test fun maleoonNamesAreReadFromWhatTheGpuReports() {
        assertEquals("Maleoon 910", GpuProbe.maleoonName("Maleoon 910"))
        assertEquals("Maleoon 920", GpuProbe.maleoonName("Maleoon920"))
        assertNull(GpuProbe.maleoonName("Mali-G78"))
    }

    @Test fun theAdrenoNumberRuleIsUnchanged() {
        // The same rule the Adreno probe has always used, including refusing Mali's numbers.
        assertEquals(0, GpuProbe.threeDigits("Mali-G850"))
        assertEquals(0, GpuProbe.threeDigits("Maleoon 910"))
        assertEquals(740, GpuProbe.threeDigits("Adreno740v2"))
    }
}
