package com.droiddeck.launcher.gpu.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording of a real device, replayed.
 *
 * Captured from a **Kirin 9010 handset on HarmonyOS (Android 12, API 31)** with `probe-device.sh`.
 * Every value below was read off real hardware, not invented, and the
 * point of the file is that it disagrees with two things the design assumed before hardware arrived:
 *
 *  1. The Maleoon Vulkan driver is `/vendor/lib64/hw/vulkan.hvgr_v200.so` - `hvgr`, not `maleoon`.
 *  2. `ro.soc.model` is just `"Kirin"`; the model number lives in `ro.board.platform` (`Kirin9010`).
 *
 * It also records what the device does *not* have: no `/dev/mali0`, no `/sys/class/misc/mali0`, no
 * `/dev/dri`, no `/sys/class/kgsl`. Those absences are what make "software rendering only" a fact
 * about this phone rather than an assumption about the vendor.
 */
class Kirin9010RealDeviceTest {

    /** The device as it described itself. */
    private fun pura70() = FakeEnv(
        files = setOf("/vendor/lib64/hw/vulkan.hvgr_v200.so"),
        props = mapOf(
            "ro.board.platform" to "Kirin9010",
            "ro.soc.model" to "Kirin",
            "ro.soc.manufacturer" to "Hisilicon",
            "ro.hardware" to "Kirin9010",
            "hw_sc.build.platform.version" to "4.2.0",
            "ro.build.version.emui" to "EmotionUI_14.2.0",
        ),
        sdkInt = 31,
        hardware = "Kirin9010",
        manufacturer = "HUAWEI",
        vulkanDeviceName = "Maleoon 910",
    )

    @Test fun thePhoneIsIdentifiedAsAMaleoonKirin() {
        val probe = GpuProbe.probe(pura70())
        assertEquals(GpuVendor.HUAWEI_MALEOON, probe.vendor)
        assertEquals("Maleoon 910", probe.deviceName)
        assertEquals("kirin9010", probe.kirin?.id)
        assertEquals(KirinSoc.Era.MALEOON, probe.kirin?.era)
        assertTrue(probe.isMaleoonKirin)
    }

    @Test fun theGpuIsReachedThroughNoKernelNodeAtAll() {
        // This is the fact that decides the whole support story: there is no kbase and no DRM node.
        val probe = GpuProbe.probe(pura70())
        assertNull(probe.kernelNode)
        assertEquals(KbaseInterface.NONE, probe.kbase)
    }

    @Test fun theDriverIsFoundUnderItsRealNameNotTheMarketingOne() {
        val probe = GpuProbe.probe(pura70())
        assertEquals("/vendor/lib64/hw/vulkan.hvgr_v200.so", probe.driverLib)
    }

    @Test fun hvgrIsClassifiedAsHuawei() {
        assertEquals(GpuVendor.HUAWEI_MALEOON, GpuProbe.vendorOfDriverFile("vulkan.hvgr_v200.so"))
        assertEquals(GpuVendor.HUAWEI_MALEOON, GpuProbe.vendorOfDriverFile("vulkan.maleoon.so"))
        assertEquals(GpuVendor.ARM_MALI, GpuProbe.vendorOfDriverFile("vulkan.mali.so"))
        assertEquals(GpuVendor.QUALCOMM, GpuProbe.vendorOfDriverFile("vulkan.adreno.so"))
        // Not a Vulkan HAL driver: must not be attributed to anyone.
        assertNull(GpuProbe.vendorOfDriverFile("libGLES_mali.so"))
        assertNull(GpuProbe.vendorOfDriverFile("vulkan.hvgr_v200.txt"))
    }

    @Test fun theSocModelPropertyAloneWouldNotHaveNamedTheChip() {
        // ro.soc.model is "Kirin" on this build, so the model has to come from ro.board.platform.
        assertNull(KirinCatalog.identify("Kirin"))
        assertEquals("kirin9010", KirinCatalog.identify("Kirin9010")?.id)
    }

    @Test fun theDriverDirectoryListingFindsItEvenWithNoKnownPathMatch() {
        // A driver name no version of this code has seen: only the directory listing can find it.
        val env = FakeEnv(
            files = setOf("/vendor/lib64/hw/vulkan.hvgr_v210.so"),
            vulkanDeviceName = "",
            hardware = "unknown",
        )
        val probe = GpuProbe.probe(env)
        assertEquals(GpuVendor.HUAWEI_MALEOON, probe.vendor)
        assertEquals("/vendor/lib64/hw/vulkan.hvgr_v210.so", probe.driverLib)
    }

    @Test fun theGlRendererStringTheDeviceActuallyPrintsIsParsed() {
        // dumpsys SurfaceFlinger: "GLES: HUAWEI, Maleoon 910, OpenGL ES 3.2 B236.95f3eca3..."
        assertEquals(
            "Maleoon 910",
            GpuProbe.maleoonName("HUAWEI, Maleoon 910, OpenGL ES 3.2 B236.95f3eca3c57559aa9ed21461acd7e5da485113ec"),
        )
    }

    @Test fun theDefaultPolicyKeepsThisGpuOutOfTheGuest() {
        val decision = BackendRegistry.select(GpuProbe.probe(pura70()), GpuPolicy.AUTO)
        assertEquals("lavapipe", decision.chosen.backendId)
        assertFalse(decision.chosen.gpuInGuest)
        assertTrue(decision.chosen.binds.isEmpty())
    }

    @Test fun askingForHardwareCannotBeGrantedAndSaysSo() {
        val decision = BackendRegistry.select(GpuProbe.probe(pura70()), GpuPolicy.PREFER_HARDWARE)
        assertEquals("lavapipe", decision.chosen.backendId)
        val bridge = decision.candidates.first { it.backendId == "vendor-bridge" }
        assertEquals(Risk.BLOCKED, bridge.risk)
        assertTrue(bridge.reason.contains("bionic"))
    }

    @Test fun theTuningMatchesThePhoneItWasWrittenFor() {
        // 8 cores on this device (4x2.3 + 3x2.18 + 1x1.55 per the captured /proc/cpuinfo shape).
        val tuning = DeviceProfiles.forProbe(GpuProbe.probe(pura70()), cpuCores = 8)!!
        assertEquals("Kirin 9010 (software)", tuning.label)
        assertEquals(60, tuning.resolutionScalePercent)
        assertEquals("6", tuning.env["LP_NUM_THREADS"])
    }

    @Test fun harmonyOs4Point2IsReadFromThePlatformProperty() {
        val os = HarmonyOs.detect(pura70())
        assertTrue(os.detected)
        assertEquals("4.2.0", os.version)
        assertEquals(31, os.apiLevel)
        assertTrue(os.sessionCapable)
    }

    @Test fun aMaliEraKirinWithTheSamePropertiesShapeStillReadsAsMali() {
        // The same detection code against a Kirin 9000: kbase node present, so it is Mali, not Maleoon.
        val env = FakeEnv(
            files = setOf("/dev/mali0"),
            props = mapOf("ro.board.platform" to "Kirin9000", "ro.soc.model" to "Kirin"),
            sdkInt = 29,
            hardware = "Kirin9000",
            manufacturer = "HUAWEI",
        )
        val probe = GpuProbe.probe(env)
        assertEquals(GpuVendor.ARM_MALI, probe.vendor)
        assertEquals("kirin9000", probe.kirin?.id)
        assertEquals(KbaseInterface.JM, probe.kbase)
    }
}
