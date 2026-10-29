package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Which library inside an imported pack the loader should open.
 *
 * The importer used to accept exactly one file name, which was fine while Turnip was the only driver
 * anyone could import. A CPU renderer arrives as a directory of libraries, so the entry point has to
 * be chosen rather than assumed.
 */
// Robolectric, because the install check parses the manifest with org.json: the plain JVM test
// classpath only has Android's stubbed copy, which throws on construction.
@RunWith(RobolectricTestRunner::class)
class DriverPackEntryTest {

    private fun libs(vararg names: String) = names.associateWith { File(it) }

    @Test fun aPackThatNamesItsEntryPointGetsIt() {
        val pack = libs("libvulkan_lvp.so", "libLLVM-11.so.1", "libdrm.so.2")
        assertEquals("libvulkan_lvp.so", LinuxVulkanDriverManager.pickEntry(pack, "libvulkan_lvp.so"))
    }

    @Test fun aDeclaredEntryThatIsNotThereIsNotSilentlyReplaced() {
        // Better to fail the import than to open a different library than the pack asked for.
        val pack = libs("libvulkan_lvp.so", "libLLVM-11.so.1")
        assertNull(LinuxVulkanDriverManager.pickEntry(pack, "libvulkan_freedreno.so"))
    }

    @Test fun aTurnipPackWithoutAManifestStillWorks() {
        // Every pack published before this change looks like this, and must keep importing.
        assertEquals("libvulkan_freedreno.so", LinuxVulkanDriverManager.pickEntry(libs("libvulkan_freedreno.so"), ""))
        assertEquals(
            "libvulkan_freedreno.so",
            LinuxVulkanDriverManager.pickEntry(libs("libLLVM-11.so.1", "libvulkan_freedreno.so"), ""),
        )
    }

    @Test fun aCpuRendererPackWithoutAManifestIsRecognised() {
        assertEquals(
            "libvulkan_lvp.so",
            LinuxVulkanDriverManager.pickEntry(libs("libLLVM-11.so.1", "libvulkan_lvp.so"), ""),
        )
    }

    /**
     * The version the generated manifest declares.
     *
     * Declaring 1.1 for a 1.4 driver is not cosmetic: the loader treats the driver as 1.1, and a
     * compositor that requires 1.3 then reports no physical device while the driver is loaded and
     * enumerable. Measured on a Kirin 9010 with lavapipe 26.2.
     */
    @Test fun aPackStatesItsOwnVulkanVersion() {
        assertEquals("1.4.0", LinuxVulkanDriverManager.apiVersion("1.4.0"))
    }

    @Test fun aPackThatSaysNothingKeepsTheHistoricalVersion() {
        // Every Turnip pack published so far looks like this and must import unchanged.
        assertEquals("1.1.274", LinuxVulkanDriverManager.apiVersion(null))
        assertEquals("1.1.274", LinuxVulkanDriverManager.apiVersion(""))
        assertEquals("1.1.274", LinuxVulkanDriverManager.apiVersion("  "))
    }

    @Test fun aMalformedVersionFallsBackRatherThanWritingGarbage() {
        // A manifest with an unparsable version makes the loader drop the whole ICD.
        assertEquals("1.1.274", LinuxVulkanDriverManager.apiVersion("1.4"))
        assertEquals("1.1.274", LinuxVulkanDriverManager.apiVersion("latest"))
    }

    @Test fun aPackWithNoVulkanDriverIsRefused() {
        assertNull(LinuxVulkanDriverManager.pickEntry(libs("libLLVM-11.so.1", "libdrm.so.2"), ""))
        assertNull(LinuxVulkanDriverManager.pickEntry(emptyMap(), ""))
    }

    /**
     * An installed pack is recognised by what its own manifest points at.
     *
     * The first version of this checked for `libvulkan_freedreno.so` by name, which meant a
     * correctly imported CPU renderer existed on disk and was invisible in the driver list.
     */
    @Test fun anInstallIsRecognisedByItsManifestNotAFixedFileName() {
        val dir = Files.createTempDirectory("lvd-manifest").toFile()
        try {
            val lib = File(dir, "libvulkan_lvp.so").apply { writeText("not a real library") }
            // Forward slashes: a Windows temp path in a JSON string needs escaping otherwise, and
            // Android paths look like this anyway.
            val path = lib.absolutePath.replace('\\', '/')
            File(dir, "icd.json").writeText("""{"ICD":{"library_path":"$path"}}""")
            assertEquals(lib.absolutePath, LinuxVulkanDriverManager.libraryOf(dir)?.absolutePath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun anOlderInstallWithTheFixedNameStillCounts() {
        val dir = Files.createTempDirectory("lvd-legacy").toFile()
        try {
            val lib = File(dir, LinuxVulkanDriverManager.LIB_NAME).apply { writeText("x") }
            // No manifest at all: the historical layout is all there is to go on.
            assertEquals(lib.absolutePath, LinuxVulkanDriverManager.libraryOf(dir)?.absolutePath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aManifestPointingAtNothingFallsBackRatherThanClaimingInstalled() {
        val dir = Files.createTempDirectory("lvd-broken").toFile()
        try {
            File(dir, "icd.json").writeText("""{"ICD":{"library_path":"/nowhere/libvulkan_lvp.so"}}""")
            assertNull(LinuxVulkanDriverManager.libraryOf(dir))
            // And with the legacy name present, the fallback still finds it.
            val legacy = File(dir, LinuxVulkanDriverManager.LIB_NAME).apply { writeText("x") }
            assertEquals(legacy.absolutePath, LinuxVulkanDriverManager.libraryOf(dir)?.absolutePath)
        } finally {
            dir.deleteRecursively()
        }
    }
}
