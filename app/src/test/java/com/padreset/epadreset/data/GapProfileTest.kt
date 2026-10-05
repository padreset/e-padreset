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

    @Test
    fun `invalid tokens are rejected instead of shifting address value pairs`() {
        listOf(
            SNIPPET.replace("addresses=\"24\"", "addresses=\"24 invalid\""),
            SNIPPET.replace("reset=\"0\"", "reset=\"invalid 0\""),
            SNIPPET.replace("0x47", "invalid 0x47"),
            SNIPPET.replace("addresses=\"24\" reset=\"0\"", "addresses=\"24 24\" reset=\"0 1\""),
            SNIPPET.replace("</model>", "<unexpected><pad addresses=\"25\" reset=\"1\"/></unexpected></model>"),
        ).forEach { text -> assertTrue(text, GapProfileText.parse(text, emptySet()) is GapParse.Refused) }
    }

    @Test
    fun `profile storage preserves groups and rejects overflow without eviction`() {
        val store = GapProfileStore(MemoryGapStorage())
        val text = SNIPPET.replace("</model>", "<pad kind=\"platen\" addresses=\"25\" reset=\"94\"/></model>")
        val original = (GapProfileText.parse(text, emptySet()) as GapParse.Accepted).profile
        store.save(original, false)
        assertEquals(original.wasteCounters, store.findExact(original.modelName)?.wasteCounters)
        assertEquals(original.resetMap, store.findExact(original.modelName)?.resetMap)
        repeat(9) { index -> store.save(original.copy(modelName = "ZZ-$index"), false) }
        assertEquals(GapSave.RefusedFull, store.save(original.copy(modelName = "overflow"), false))
        assertEquals(10, store.load().size)
        assertTrue(store.findExact(original.modelName) != null)
    }

    @Test
    fun `platen is an internal pad even on a maintenance box model`() {
        val parsed = GapProfileText.parse(SNIPPET.replace("kind=\"main\"", "kind=\"platen\""), setOf("ZZ-9999"))
        assertTrue(parsed is GapParse.Accepted)
        assertTrue((parsed as GapParse.Accepted).profile.usesMaintenanceBox)
    }

    @Test
    fun `stored profiles are reclassified against the current maintenance box catalog`() {
        val storage = MemoryGapStorage()
        val store = GapProfileStore(storage) { it == "ZZ-9999" }
        val text = SNIPPET.replace("kind=\"main\"", "kind=\"platen\"")
        val profile = (GapProfileText.parse(text, setOf("ZZ-9999")) as GapParse.Accepted).profile
        store.save(profile, false)
        assertTrue(store.findExact("ZZ-9999")?.usesMaintenanceBox == true)
        assertEquals("platen", store.findExact("ZZ-9999")?.wasteCounters?.single()?.kind)
        storage.blob = SNIPPET
        assertNull(store.findExact("ZZ-9999"))
    }

    @Test
    fun `oversized and deeply nested profiles are refused`() {
        assertTrue(GapProfileText.parse(" ".repeat(70_000) + SNIPPET, emptySet()) is GapParse.Refused)
        assertTrue(GapProfileText.parse("<root>".repeat(100) + SNIPPET + "</root>".repeat(100), emptySet()) is GapParse.Refused)
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
