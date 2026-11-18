package com.padreset.epadreset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the catalog-name decision behind [EpsonPrinterCatalog.resolve].
 *
 * A bare `contains` used to match "XP-420 Series" for an "XP-4200" printer and
 * hand the wrong controlModel/keyword/EEPROM addresses to the write path. The
 * fixtures below replay that exact catalog order ("XP-420 Series" before
 * "XP-4200 Series", as in devices.xml) and require the longer, correct entry.
 */
class ResolveMatchTest {
    private val names = listOf(
        "XP-420 Series",
        "XP-4200 Series",
        "XP-210 Series",
        "XP-2100 Series",
        "PM-400 Series",
        "PM-4000PX",
        "WF-C5290 Series",
        "WF-C5290BA",
        "PictureMate",
        "PictureMate PM 240",
    )

    @Test
    fun `exact name wins`() {
        assertEquals(
            "PM-4000PX",
            EpsonPrinterCatalog.resolveName("PM-4000PX", "MFG:EPSON;MDL:PM-4000PX;", names),
        )
    }

    @Test
    fun `suffixed series entry serves a bare MDL`() {
        assertEquals(
            "XP-4200 Series",
            EpsonPrinterCatalog.resolveName("XP-4200", "MFG:EPSON;MDL:XP-4200;", names),
        )
    }

    @Test
    fun `shorter series never shadows a longer MDL`() {
        // Under the old contains(), both XP rows matched and document order
        // returned "XP-420 Series". The correct entry must win regardless of order.
        assertEquals(
            "XP-2100 Series",
            EpsonPrinterCatalog.resolveName("XP-2100", "MFG:EPSON;MDL:XP-2100;", names),
        )
    }

    @Test
    fun `prefix of another model does not match`() {
        assertNull(
            EpsonPrinterCatalog.resolveName(
                "PM-4000PX",
                "MFG:EPSON;MDL:PM-4000PX;",
                listOf("PM-400 Series"),
            ),
        )
    }

    @Test
    fun `whole token inside a longer MDL still matches`() {
        assertEquals(
            "PictureMate",
            EpsonPrinterCatalog.resolveName("PictureMate", "MFG:EPSON;MDL:PictureMate;", names),
        )
    }

    @Test
    fun `an MDL that is a whole token of one catalog name matches it`() {
        // The gap this rule closes: devices.xml calls a printer "Stylus DX4800"
        // or "Epson Stylus Photo R2000", while reinkpy, padzero and EWR — the
        // tools that discovered the keys — call it "DX4800" and "R2000". The
        // keys were already in the catalog under the long name.
        assertEquals(
            "Stylus DX4800",
            EpsonPrinterCatalog.resolveName("DX4800", "MDL:DX4800;", listOf("Stylus DX4800")),
        )
        assertEquals(
            "Epson Stylus Photo R2000",
            EpsonPrinterCatalog.resolveName(
                "R2000",
                "MDL:R2000;",
                listOf("Epson Stylus Photo R2000"),
            ),
        )
    }

    @Test
    fun `a contained MDL still refuses numeric continuations`() {
        // The mirror rule must not undo the reason the forward one exists.
        assertNull(
            EpsonPrinterCatalog.resolveName("R300", "MDL:R300;", listOf("Stylus R3000")),
        )
        assertNull(
            EpsonPrinterCatalog.resolveName("XP-42", "MDL:XP-42;", listOf("XP-4200 Series")),
        )
    }

    @Test
    fun `a contained MDL matching two catalog entries is refused`() {
        // "NX230" names both rows, and picking either would hand a different
        // EEPROM map to the write path. Refusing is the only safe answer.
        val ambiguous = mutableListOf<String>()
        val got = EpsonPrinterCatalog.resolveName(
            "NX230",
            "MDL:NX230;",
            listOf("Epson Stylus NX230", "NX230 TX230"),
            onAmbiguous = ambiguous::add,
        )
        assertNull(got)
        assertTrue(ambiguous.isNotEmpty())
    }

    @Test
    fun `a very short MDL is never matched as a fragment`() {
        assertNull(EpsonPrinterCatalog.resolveName("R2", "MDL:R2;", listOf("Stylus R2000")))
    }

    @Test
    fun `an exact catalog name still wins over a containing one`() {
        assertEquals(
            "NX230",
            EpsonPrinterCatalog.resolveName(
                "NX230",
                "MDL:NX230;",
                listOf("Epson Stylus NX230", "NX230"),
            ),
        )
    }

    @Test
    fun `unknown MDL finds nothing`() {
        assertNull(
            EpsonPrinterCatalog.resolveName("ZZ-9999", "MFG:EPSON;MDL:ZZ-9999;", names),
        )
    }

    @Test
    fun `another model in DES or serial cannot override an unknown MDL`() {
        assertNull(EpsonPrinterCatalog.resolveName("ZZ-9999", "MDL:ZZ-9999;DES:XP-420 Series;SN:XP-420;", names))
    }

    @Test
    fun `boundary matching rejects numeric continuation`() {
        assertTrue(EpsonPrinterCatalog.boundaryContains("EPSON XP-420", "XP-420"))
        assertTrue(EpsonPrinterCatalog.boundaryContains("MFG:EPSON;MDL:XP-420;", "XP-420"))
        assertEquals(false, EpsonPrinterCatalog.boundaryContains("XP-4200", "XP-420"))
        assertEquals(false, EpsonPrinterCatalog.boundaryContains("PM-4000PX", "PM-400"))
    }

    @Test
    fun `ambiguity is reported and refuses a write profile`() {
        val ambiguous = mutableListOf<String>()
        val got = EpsonPrinterCatalog.resolveName(
            "My AB-100 Plus",
            "MFG:EPSON;MDL:My AB-100 Plus;",
            listOf("AB-100", "AB-100 Plus"),
            onAmbiguous = ambiguous::add,
        )
        assertNull(got)
        assertTrue(ambiguous.isNotEmpty())
    }
}
