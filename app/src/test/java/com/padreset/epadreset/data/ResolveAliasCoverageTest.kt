package com.padreset.epadreset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The alias rule, checked against the catalog the app actually ships.
 *
 * [ResolveMatchTest] pins the rule on fixtures. This one exists because the
 * reason the rule was added is a property of the bundled data: devices.xml
 * spells a printer "Stylus DX4800" or "Epson Stylus Photo R2000" while reinkpy,
 * padzero and EWR — the projects that discovered the keys — call it "DX4800"
 * and "R2000". Every key for those models was already in the catalog and no
 * printer could reach it, which no fixture would have caught.
 *
 * The count assertion is deliberately loose. It is there to fail if the rule
 * stops matching real names, not to freeze how many models the catalog holds.
 */
class ResolveAliasCoverageTest {

    @Test
    fun `an MDL named the way the tools name it reaches the catalog entry`() {
        val names = catalogNames()

        assertEquals(
            "Stylus DX4800",
            EpsonPrinterCatalog.resolveName("DX4800", "MDL:DX4800;", names),
        )
        assertEquals(
            "Epson Stylus Photo R2000",
            EpsonPrinterCatalog.resolveName("R2000", "MDL:R2000;", names),
        )
        assertEquals(
            "Stylus SX200",
            EpsonPrinterCatalog.resolveName("SX200", "MDL:SX200;", names),
        )
    }

    @Test
    fun `the rule covers a meaningful share of the catalog, not one lucky name`() {
        val names = catalogNames()
        // The prefixes devices.xml uses: EPSON, STYLUS, STYLUS PHOTO, ME OFFICE.
        // Each of these is a name reinkpy publishes and devices.xml does not.
        val probes = listOf("DX4200", "CX8300", "NX620", "ME Office 82WD", "TX410", "C90")
        val resolved = probes.count { EpsonPrinterCatalog.resolveName(it, "MDL:$it;", names) != null }

        assertTrue("expected every probe to resolve, got $resolved of ${probes.size}", resolved == probes.size)
    }

    @Test
    fun `a real ambiguity in the catalog is still refused`() {
        // Both rows exist in devices.xml, and they carry different EEPROM maps.
        assertNull(EpsonPrinterCatalog.resolveName("NX230", "MDL:NX230;", catalogNames()))
    }

    private fun catalogNames(): List<String> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "src/main/assets/devices.xml")
            if (candidate.isFile) {
                return EpsonPrinterCatalog.parseDevicesXml(candidate.inputStream())
                    .map { it.modelName }
            }
            dir = dir.parentFile
        }
        throw AssertionError("devices.xml not found above ${System.getProperty("user.dir")}")
    }
}
