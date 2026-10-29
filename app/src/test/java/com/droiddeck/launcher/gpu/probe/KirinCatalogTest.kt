package com.droiddeck.launcher.gpu.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KirinCatalogTest {

    @Test fun theSameNameCoversTwoDifferentGpuStacks() {
        // The whole reason the catalog carries an era: these two are one generation apart in
        // marketing and nothing alike underneath.
        val nineThousand = KirinCatalog.identify("kirin9000")!!
        assertEquals(KirinSoc.Era.MALI, nineThousand.era)
        assertEquals(GpuVendor.ARM_MALI, nineThousand.vendor)
        assertEquals(KbaseInterface.JM, nineThousand.kbase)
        assertTrue(nineThousand.hasOpenGuestDriver)

        val nineThousandS = KirinCatalog.identify("kirin9000s")!!
        assertEquals(KirinSoc.Era.MALEOON, nineThousandS.era)
        assertEquals(GpuVendor.HUAWEI_MALEOON, nineThousandS.vendor)
        assertEquals("Maleoon 910", nineThousandS.gpu)
        assertTrue(!nineThousandS.hasOpenGuestDriver)
    }

    @Test fun aMoreSpecificIdBeatsItsOwnPrefix() {
        // "kirin9000s" contains "kirin9000"; reading it as the older part would pick the wrong GPU
        // and, with it, a driver that cannot work.
        assertEquals("kirin9000s", KirinCatalog.identify("kirin9000s")!!.id)
        assertEquals("kirin9000", KirinCatalog.identify("kirin9000")!!.id)
        assertEquals("kirin9000s", KirinCatalog.identify("kirin9000", "kirin9000s")!!.id)
        assertEquals("kirin9000e", KirinCatalog.identify("Kirin 9000E")!!.id)
    }

    @Test fun huaweiSpellsTheSameChipSeveralWays() {
        // ro.board.platform, Build.HARDWARE and the marketing string all disagree in the wild.
        for (text in listOf("kirin9000", "KIRIN9000", "Kirin 9000", " kirin-9000 ", "hisilicon kirin9000")) {
            assertEquals(text, "kirin9000", KirinCatalog.identify(text)?.id)
        }
        assertEquals("kirin9000s", KirinCatalog.identify("Kirin 9000S")?.id)
        assertEquals("kirin9020", KirinCatalog.identify("kirin 9020")?.id)
    }

    @Test fun olderPartsAreNamedByTheirHiNumbers() {
        assertEquals("kirin960", KirinCatalog.identify("hi3660")?.id)
        assertEquals("kirin970", KirinCatalog.identify("hi3670")?.id)
        assertEquals("kirin980", KirinCatalog.identify("hi3680")?.id)
        // And an hi number with no catalog entry stays unknown rather than being guessed.
        assertNull(KirinCatalog.identify("hi9999"))
    }

    @Test fun everyMaliEraPartIsJobManagerUntilTheValhallV10Split() {
        // Kirin 9000 and older: licensed Mali on Job Manager. A CSF-only build cannot drive these.
        for (id in listOf("kirin970", "kirin980", "kirin985", "kirin990", "kirin9000", "kirin9000e", "kirin820")) {
            val soc = KirinCatalog.byId(id)!!
            assertEquals(id, KbaseInterface.JM, soc.kbase)
            assertEquals(id, GpuVendor.ARM_MALI, soc.vendor)
        }
    }

    @Test fun aNonKirinDeviceIsNotForcedIntoTheCatalog() {
        assertNull(KirinCatalog.identify("kalama"))
        assertNull(KirinCatalog.identify("SM8650"))
        assertNull(KirinCatalog.identify(""))
        assertNull(KirinCatalog.identify(null))
        assertNull(KirinCatalog.identify("exynos2400"))
    }

    @Test fun tokensAreNormalisedNotSubstringMatched() {
        // "kirin" without a number, and numbers without "kirin", must not match.
        assertTrue(KirinCatalog.split("kirin").isEmpty())
        assertTrue(KirinCatalog.split("9000").isEmpty())
        assertEquals(listOf("kirin9000"), KirinCatalog.split("Kirin-9000"))
        // A five-digit run is not a Kirin model.
        assertTrue(KirinCatalog.split("kirin90000").isEmpty())
    }
}
