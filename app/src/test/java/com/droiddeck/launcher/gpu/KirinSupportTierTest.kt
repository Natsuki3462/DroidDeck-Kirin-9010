package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tiers a Kirin device lands in, and the words it is shown.
 *
 * Mali is UNTESTED rather than UNSUPPORTED because an imported PanVK driver can drive it; Maleoon is
 * UNSUPPORTED for hardware but must not be told "buy a Snapdragon", because the session does run -
 * on the CPU.
 */
class KirinSupportTierTest {

    private fun info(family: GpuInfo.Family, name: String = "x") =
        GpuInfo(name = name, model = 0, family = family, soc = "", oneUi8Gen2 = false)

    @Test fun maliIsExperimentalNotUnsupported() {
        val mali = info(GpuInfo.Family.MALI, "Mali-G78 MP24")
        assertEquals(GpuInfo.Support.UNTESTED, mali.support)
        assertTrue(mali.supportText.contains("PanVK"))
        assertTrue(mali.supportText.contains("not validated"))
    }

    @Test fun maleoonSaysSoftwareNotUnsupportedGpu() {
        val maleoon = info(GpuInfo.Family.MALEOON, "Maleoon 910")
        assertEquals(GpuInfo.Support.UNSUPPORTED, maleoon.support)
        assertTrue(maleoon.supportText.contains("CPU rendering"))
        // The old line would be technically true and practically useless here.
        assertTrue(!maleoon.supportText.contains("Snapdragon"))
    }

    @Test fun aVendorWithNoBackendKeepsTheOldWording() {
        // Xclipse, PowerVR and an unnamed GPU all stay NOT_ADRENO, so this wording is what they get.
        assertEquals("Not supported: DroidDeck needs an Adreno (Snapdragon) GPU", info(GpuInfo.Family.NOT_ADRENO).supportText)
    }

    @Test fun adrenoTiersAreUntouched() {
        // Same assertions the existing GpuDetectionTest makes, restated here so a change to the
        // shared `support` rule cannot pass by only one of the two files.
        val tested = GpuInfo("Adreno 830", 830, GpuInfo.familyOf(true, 830), "", false)
        assertEquals(GpuInfo.Support.TESTED, tested.support)
        assertEquals("Supported", tested.supportText)
        val unknown = GpuInfo("Adreno", 0, GpuInfo.familyOf(true, 0), "", false)
        assertEquals(GpuInfo.Support.UNTESTED, unknown.support)
    }

    @Test fun theVendorDefaultsToQualcommSoOldConstructionStillMeansAdreno() {
        val gpu = GpuInfo("Adreno 740", 740, GpuInfo.Family.A7XX, "", false)
        assertEquals(com.droiddeck.launcher.gpu.probe.GpuVendor.QUALCOMM, gpu.vendor)
    }
}
