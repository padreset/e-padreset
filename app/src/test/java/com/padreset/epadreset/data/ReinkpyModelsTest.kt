package com.padreset.epadreset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the reinkpy extension: the models devices.xml does not have.
 *
 * These are the entries with the least evidence behind them — unlike the shared
 * models, there is no second database to compare them against. What can be
 * checked is that the translation from reinkpy's field names to this app's is
 * faithful, because that translation is the whole reason the file is safe to
 * use at all: `rkey` is the read key devices.xml spells as two little-endian
 * bytes, and the keyword is the same eight bytes.
 */
class ReinkpyModelsTest {

    @Test
    fun `parses the bundled extension into specs`() {
        val specs = parse()
        assertTrue("expected a populated extension, got ${specs.size}", specs.size >= 100)
    }

    @Test
    fun `every spec carries what a session needs`() {
        val broken = parse().filter {
            it.controlModel.size != 2 || it.keyword.size != 8
        }
        assertTrue(
            "specs that could resolve but never open a session: " +
                broken.joinToString { "${it.modelName}(${it.controlModel.size}/${it.keyword.size})" },
            broken.isEmpty(),
        )
    }

    @Test
    fun `translates rkey into little-endian control bytes`() {
        val spec = parse().first { it.modelName == "ET-1111" }

        // reinkpy publishes rkey 1943 = 0x0797; devices.xml would spell the same
        // value as <factory>0x97 0x07</factory>.
        assertEquals(0x0797, 1943)
        assertArrayEqualsHex(byteArrayOf(0x97.toByte(), 0x07), spec.controlModel)

        // And the write keyword, which reinkpy publishes as an 8-character
        // string: "Nbsjcbzb".
        assertEquals("Nbsjcbzb", spec.keyword.decodeToString())
    }

    @Test
    fun `leaves the counter scale unknown rather than guessing one`() {
        val spec = parse().first { it.modelName == "ET-1111" }

        assertTrue("expected counters", spec.wasteCounters.isNotEmpty())
        // reinkpy publishes no ceiling, so max stays at zero and the UI shows
        // the raw reading. A zero here must never be read as "empty pad".
        assertTrue(
            "every counter must report an unknown scale",
            spec.wasteCounters.all { it.max == 0 },
        )
    }

    @Test
    fun `builds the reset map from the pads`() {
        val spec = parse().first { it.modelName == "ET-1111" }

        // <pad kind="platen" addresses="28 52 53 54 55" reset="0 0 0 94 94"/>
        assertEquals(94, spec.resetMap[54])
        assertEquals(94, spec.resetMap[55])
        assertEquals(0, spec.resetMap[28])
        // <pad kind="main" addresses="47 48 49 50 51" reset="0 0 0 0 0"/>
        assertEquals(0, spec.resetMap[47])
        assertEquals(10, spec.resetMap.size)
    }

    @Test
    fun `carries no cleaning payloads, because reinkpy publishes none`() {
        val withCleaning = parse().filter { it.cleaningOptions.isNotEmpty() }
        assertTrue(
            "unexpected cleaning jobs: ${withCleaning.map { it.modelName }}",
            withCleaning.isEmpty(),
        )
    }

    @Test
    fun `does not include models devices_xml already covers`() {
        // ET-16600 is in devices.xml, so it must not be duplicated here: the
        // devices.xml entry is the richer one and the merge keeps it.
        assertFalse(parse().any { it.modelName == "ET-16600" })
    }

    private fun assertArrayEqualsHex(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            expected.joinToString(" ") { "%02X".format(it) },
            actual.joinToString(" ") { "%02X".format(it) },
        )
    }

    private fun parse(): List<PrinterSpec> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "src/main/assets/reinkpy_models.xml")
            if (candidate.isFile) {
                return candidate.inputStream().use {
                    EpsonPrinterCatalog.parseReinkpyModels(it, emptySet())
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "reinkpy_models.xml not found above ${System.getProperty("user.dir")}",
        )
    }
}
