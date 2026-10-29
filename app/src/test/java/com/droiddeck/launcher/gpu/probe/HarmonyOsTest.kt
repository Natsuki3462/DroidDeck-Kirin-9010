package com.droiddeck.launcher.gpu.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The target device runs HarmonyOS 4.x, which is Android 12 under the hood (API 31/32) and installs
 * this app. What the detection has to get right is the version wording, because "EMUI 14" and
 * "HarmonyOS 4" are the same release and users know the second one.
 */
class HarmonyOsTest {

    private fun huawei(props: Map<String, String>, sdk: Int = 31) =
        HarmonyOs.detect(FakeEnv(props = props, manufacturer = "HUAWEI", sdkInt = sdk))

    @Test fun harmonyOs4IsDetectedAndIsSessionCapable() {
        val os = huawei(mapOf("hw_sc.build.platform.version" to "4.2.0"))
        assertTrue(os.detected)
        assertEquals("4.2.0", os.version)
        assertEquals(31, os.apiLevel)
        assertTrue(os.sessionCapable)
        assertEquals("HarmonyOS 4.2.0 (Android API 31)", os.label())
    }

    @Test fun emuiIsTheSameReleaseNumberedTenHigher() {
        // EMUI 12/13/14 are HarmonyOS 2/3/4. Reporting "14.0.0" to a user would be wrong.
        assertEquals("4.0.0", HarmonyOs.fromEmui("EmotionUI_14.0.0"))
        assertEquals("3.1.0", HarmonyOs.fromEmui("EmotionUI_13.1.0"))
        assertEquals("2.0.0", HarmonyOs.fromEmui("EmotionUI_12.0.0"))
        // Already HarmonyOS-numbered: left alone.
        assertEquals("4.2.0", HarmonyOs.fromEmui("4.2.0"))
        assertEquals("", HarmonyOs.fromEmui(""))
        assertEquals("", HarmonyOs.fromEmui("EmotionUI"))
    }

    @Test fun anEmuiOnlyBuildStillReportsAHarmonyVersion() {
        val os = huawei(mapOf("ro.build.version.emui" to "EmotionUI_14.0.0"))
        assertEquals("4.0.0", os.version)
        assertEquals("HarmonyOS 4.0.0 (Android API 31)", os.label())
    }

    @Test fun aKirinPlatformIsEnoughEvenWithoutTheManufacturerProp() {
        val os = HarmonyOs.detect(FakeEnv(props = mapOf("ro.board.platform" to "kirin9010"), sdkInt = 31))
        assertTrue(os.detected)
    }

    @Test fun aNonHuaweiDeviceIsNotCalledHarmonyOs() {
        val os = HarmonyOs.detect(
            FakeEnv(props = mapOf("ro.board.platform" to "kalama"), manufacturer = "Google", sdkInt = 34)
        )
        assertFalse(os.detected)
        assertEquals("", os.label())
    }

    @Test fun theVersionIsBlankRatherThanInventedWhenTheBuildSaysNothing() {
        val os = huawei(emptyMap())
        assertTrue(os.detected)
        assertEquals("", os.version)
        assertEquals("HarmonyOS (Android API 31)", os.label())
    }
}
