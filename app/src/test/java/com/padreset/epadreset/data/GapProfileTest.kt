package com.padreset.epadreset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pasted profile fills a model the bundled catalog does not have.
 * It never replaces a published entry, and it never travels in an issue.
 */
class GapProfileTest {
    @Test
    fun `a reinkpy snippet with provenance becomes a profile and nothing else`() {
        val parsed = GapProfileText.parse(SNIPPET, emptySet())
        assertEquals(parsed.toString(), true, parsed is GapParse.Accepted)
        val profile = (parsed as GapParse.Accepted).profile
        assertEquals("ZZ-9999", profile.modelName)
        assertEquals(2, profile.controlModel.size)
        assertEquals(8, profile.keyword.size)
        assertEquals("https://example.com/reinkpy", profile.source)
        assertEquals("2026-03-30", profile.version)
        assertEquals(listOf(24), profile.resetMap.keys.toList())
        assertFalse(profile.usesMaintenanceBox)
    }

    @Test
    fun `a snippet without provenance is refused`() {
        val bare = SNIPPET
            .replace(" source=\"https://example.com/reinkpy\"", "")
            .replace(" version=\"2026-03-30\"", "")
        val parsed = GapProfileText.parse(bare, emptySet())
        assertEquals(parsed.toString(), GapRefusal.PROVENANCE, (parsed as? GapParse.Refused)?.reason)
    }

    @Test
    fun `a maintenance-box model cannot carry a waste plan`() {
        val parsed = GapProfileText.parse(SNIPPET, setOf("ZZ-9999"))
        assertTrue(parsed is GapParse.Refused)
        assertEquals(GapRefusal.MAINTENANCE_BOX, (parsed as GapParse.Refused).reason)
    }

    @Test
    fun `a catalogued model is not a gap even when a paste uses the same name`() {
        val profile = (GapProfileText.parse(SNIPPET, emptySet()) as GapParse.Accepted).profile
        assertNull(selectGap(mdl = "ZZ-9999", catalogued = true, gaps = listOf(profile)))
        assertEquals(profile, selectGap(mdl = "zz-9999", catalogued = false, gaps = listOf(profile)))
    }

    @Test
    fun `a shorter pasted name does not fill a longer model`() {
        val shorter = SNIPPET.replace("ZZ-9999", "PM-400")
        val profile = (GapProfileText.parse(shorter, emptySet()) as GapParse.Accepted).profile
        assertNull(selectGap(mdl = "PM-4000PX", catalogued = false, gaps = listOf(profile)))
    }

    @Test
    fun `the store keeps a gap and refuses a name the catalog already has`() {
        val storage = MemoryGapStorage()
        val store = GapProfileStore(storage)
        val profile = (GapProfileText.parse(SNIPPET, emptySet()) as GapParse.Accepted).profile
        assertEquals(GapSave.RefusedCatalog, store.save(profile, catalogued = true))
        assertEquals(GapSave.Stored, store.save(profile, catalogued = false))
        assertEquals("ZZ-9999", store.findExact("zz-9999")?.modelName)
        store.delete("ZZ-9999")
        assertNull(store.findExact("ZZ-9999"))
    }

    @Test
    fun `an issue cites provenance and the status try, never the key or the address`() {
        val body = ModelSupportReport.build(
            ModelSupportReport.Source(
                deviceId = "MDL:ZZ-9999;",
                appVersion = "1",
                androidVersion = "15",
                vendorId = 0x04B8,
                productId = 1,
                maintenanceBox = false,
                statusOpened = true,
                localProfile = ModelSupportReport.Citation(
                    source = "https://example.com/reinkpy",
                    version = "2026-03-30",
                ),
            ),
        ).body
        assertTrue(body.contains("Generic status tried / Estado genérico probado: yes"))
        assertTrue(body.contains("https://example.com/reinkpy"))
        assertTrue(body.contains("2026-03-30"))
        assertFalse(body.contains("GAPKEY!!"))
        assertFalse(body.contains("1234"))
        assertFalse(body.contains("0x1234"))
    }

    private companion object {
        val SNIPPET = """
            <model name="ZZ-9999" rkey="1943" keyword="0x47 0x41 0x50 0x4b 0x45 0x59 0x21 0x21" source="https://example.com/reinkpy" version="2026-03-30">
                <pad kind="main" addresses="24" reset="0"/>
            </model>
        """.trimIndent()
    }
}

private class MemoryGapStorage : GapStorage {
    var blob: String? = null
    override fun read(): String? = blob
    override fun write(blob: String) { this.blob = blob }
}
