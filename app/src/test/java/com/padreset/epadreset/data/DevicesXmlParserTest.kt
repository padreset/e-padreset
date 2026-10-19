package com.padreset.epadreset.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the devices.xml parser against the file the app actually ships.
 *
 * `verifyPrinterData` already parses the asset on every build, but with its own
 * reader, so it cannot catch a regression in the parser the app runs. This one
 * can, and it exists because the waste-counter extraction was split out of the
 * printer loop into `parseWasteSection` and `parseCounter`.
 *
 * A counter that silently disappears there is the failure worth guarding: it is
 * not a crash, not a type error, and not a bad level reading. The user just sees
 * a model with no counters and no explanation. Nothing else in the build notices.
 */
class DevicesXmlParserTest {

    @Test
    fun `parses the bundled file into a populated catalog`() {
        val specs = parse()

        assertTrue(
            "expected devices.xml to hold models, got ${specs.size}",
            specs.size >= 500,
        )
    }

    @Test
    fun `model names are unique after the parser's own distinctBy`() {
        val names = parse().map { it.modelName }

        assertEquals(
            "distinctBy should have removed the duplicates",
            names.size,
            names.distinct().size,
        )
    }

    @Test
    fun `waste counters keep their addresses and a positive ceiling`() {
        val counters = parse().flatMap { it.wasteCounters }

        assertTrue(
            "expected the catalog to carry waste counters, got ${counters.size}",
            counters.size >= 500,
        )

        // A zero maximum would make the level calculation divide by zero rather
        // than report something odd, so the parser drops those entries instead
        // of keeping them. Nothing in the app defends against one arriving.
        val unusable = counters.filter { it.addresses.isEmpty() || it.max <= 0 }
        assertTrue(
            "counters with no address or no positive ceiling: ${unusable.map { it.name }}",
            unusable.isEmpty(),
        )
    }

    /**
     * The counter maths reads a counter as a multi-byte little-endian value
     * across its addresses, so the address count is what decides whether a
     * model reports a sane level. A parser that kept one address where the file
     * lists four would cap every reading at 255.
     */
    @Test
    fun `multi byte counters keep every address the file lists`() {
        val counters = parse().flatMap { it.wasteCounters }
        val byWidth = counters.groupBy { it.addresses.size }

        // The catalog is known to carry counters wider than one byte; if this
        // group is empty the parser is truncating rather than reading.
        assertTrue(
            "expected at least one counter wider than a single byte, got widths ${byWidth.keys.sorted()}",
            byWidth.keys.any { it > 1 },
        )
        // And the widths the file uses are not all the same, which is the whole
        // reason the reader has to be told how many bytes to combine.
        assertTrue(
            "expected more than one distinct counter width, got ${byWidth.keys.sorted()}",
            byWidth.keys.size > 1,
        )
    }

    @Test
    fun `reset maps pair an address with a value`() {
        val withResets = parse().filter { it.resetMap.isNotEmpty() }

        assertTrue(
            "expected models carrying reset values, got ${withResets.size}",
            withResets.isNotEmpty(),
        )
        // The list is a flat hex stream, so a trailing odd element is dropped
        // rather than guessed at. An empty map on a model that has one in the
        // file means the reset button silently does nothing.
        assertTrue(
            "a reset map with no entry",
            withResets.none { it.resetMap.isEmpty() },
        )
    }

    /**
     * The WP/PX families whose specs declare counter 0x14/0x15 with ceiling
     * 13500 but shipped no `<reset>` carried no reset profile, so 36 models
     * connected read-only. Their reset map ({0x14: 0, 0x15: 0}) was
     * corroborated against EWR's database (see NOTICE for the provenance
     * caveat) and added to the seven shared spec blocks. Pinned here so a
     * future catalog edit cannot silently drop it again.
     */
    @Test
    fun `WP families carry the corroborated reset map`() {
        val specs = parse().associateBy { it.modelName }
        val expected = mapOf(0x14 to 0, 0x15 to 0)
        val models = listOf(
            "WP-4020 Series",
            "WP-4090 Series",
            "WP-4520 Series",
            "WP-4540 Series",
            "WP-4590 Series",
            "WP-M4011 Series",
            "PX-B700",
            "PX-K701",
        )
        assertTrue(
            "expected the WP/PX families in devices.xml, got ${specs.size} models",
            models.all { specs.containsKey(it) },
        )
        models.forEach { name ->
            val spec = specs.getValue(name)
            assertEquals(
                "$name reset map",
                expected,
                spec.resetMap,
            )
            assertTrue(
                "$name should offer a waste reset",
                spec.supportsWasteReset,
            )
        }
    }

    /**
     * No model may keep waste counters without a reset map: that is exactly
     * the shape that left the WP families read-only (counters present, reset
     * absent). Cleaning-only and maintenance-box models carry no counters at
     * all, so they are unaffected by this invariant.
     */
    @Test
    fun `no model keeps counters without a reset map`() {
        val orphaned = parse().filter { it.wasteCounters.isNotEmpty() && it.resetMap.isEmpty() }

        assertTrue(
            "models with counters but no reset map: ${orphaned.map { it.modelName }}",
            orphaned.isEmpty(),
        )
    }

    /**
     * Only service-type jobs with a payload are kept. Anything else would be
     * dispatched as a head cleaning and send the wrong bytes to the printer,
     * which is a change no user can undo from the app.
     */
    @Test
    fun `cleaning jobs always carry a payload`() {
        val cleanings = parse().flatMap { it.cleaningOptions }

        assertTrue(cleanings.none { it.payload.isEmpty() })
    }

    /**
     * Shared-byte counters keep the shared address with its nibble mask.
     *
     * The L3110 splits 0x2F in halves between Main (low nibble) and Platen
     * (high nibble) via <filter> elements. The parser used to read only the
     * first <entry>, dropping 0x2F entirely: reads understated the level and a
     * partial reset could certify an incomplete job. Pinned against the real
     * file, since the shape lives there.
     */
    @Test
    fun `shared nibble counters keep the shared address and its mask`() {
        val spec = parse().firstOrNull { it.modelName == "L3110 Series" }
            ?: throw AssertionError("L3110 Series not in devices.xml")
        val main = spec.wasteCounters.firstOrNull { it.name == "Main pad" }
            ?: throw AssertionError("L3110 Main pad missing")
        val platen = spec.wasteCounters.firstOrNull { it.name == "Platen pad" }
            ?: throw AssertionError("L3110 Platen pad missing")

        assertEquals(listOf(0x30, 0x31, 0x2F), main.addresses)
        assertEquals(0x0F, main.masks[0x2F])
        assertEquals(listOf(0x32, 0x33, 0x2F), platen.addresses)
        assertEquals(0xF0, platen.masks[0x2F])
        assertTrue("shared 0x2F must be reported", 0x2F in spec.sharedAddresses)
    }

    /**
     * Whole shared bytes without any filter (RX700 shape): two counters
     * listing 0x2E/0x2F as plain entries. The partial-reset guard keys on
     * address overlap, not on masks, so this is the corruption path the L3110
     * nibble work would otherwise miss: no mask anywhere, same silent damage.
     */
    @Test
    fun `whole shared bytes without a filter still count as shared`() {
        val xml = """
            <data><records>
            <printer brand="epson" model="RX700 Test" specs="RX700T"/>
            </records><devices><RX700T>
            <service><factory>0x0E 0x37</factory><keyword>0x41 0x42 0x43 0x44 0x45 0x46 0x47 0x48</keyword></service>
            <waste><query>
            <counter><entry>0x1C 0x1D</entry><entry>0x2E 0x2F</entry><max>11000</max></counter>
            <counter><entry>0x1E 0x1F</entry><entry>0x2E 0x2F</entry><max>14000</max></counter>
            </query><reset>0x1C 0x00 0x2E 0x00</reset></waste>
            <cleaning><cleaning><type>service</type><data>0x01</data><name>Head Cleaning</name></cleaning></cleaning>
            </RX700T></devices></data>
        """.trimIndent()
        val specs = xml.byteInputStream().use { EpsonPrinterCatalog.parseDevicesXml(it) }
        val spec = specs.firstOrNull { it.modelName == "RX700 Test" }
            ?: throw AssertionError("RX700 Test fixture did not parse")
        assertEquals(setOf(0x2E, 0x2F), spec.sharedAddresses)
        assertTrue(spec.wasteCounters.all { it.masks.isEmpty() })
    }

    /**
     * A full-byte filter is no mask: 0xFF narrows nothing, so it must not
     * mark the address as shared nor hide per-counter selection downstream.
     */
    @Test
    fun `full byte filter stores no mask`() {
        val xml = """
            <data><records>
            <printer brand="epson" model="FF Test" specs="FFT"/>
            </records><devices><FFT>
            <service><factory>0x0E 0x37</factory><keyword>0x41 0x42 0x43 0x44 0x45 0x46 0x47 0x48</keyword></service>
            <waste><query>
            <counter><entry>0x30 0x31</entry><entry>0x2F<filter>0xFF -1 254</filter></entry><max>6346</max></counter>
            </query><reset>0x30 0x00</reset></waste>
            <cleaning><cleaning><type>service</type><data>0x01</data><name>Head Cleaning</name></cleaning></cleaning>
            </FFT></devices></data>
        """.trimIndent()
        val specs = xml.byteInputStream().use { EpsonPrinterCatalog.parseDevicesXml(it) }
        val spec = specs.firstOrNull { it.modelName == "FF Test" }
            ?: throw AssertionError("FF Test fixture did not parse")
        val counter = spec.wasteCounters.firstOrNull()
            ?: throw AssertionError("FF Test counter missing")
        assertEquals(listOf(0x30, 0x31, 0x2F), counter.addresses)
        assertTrue("0xFF filter must store no mask", counter.masks.isEmpty())
        assertTrue("unshared addresses must not count", spec.sharedAddresses.isEmpty())
    }

    /**
     * The SC-P600 keyword has three bytes in the 0x80-0xFF range (0x86, 0x97,
     * 0xB6). Those are the bytes an earlier text-based loader turned into two
     * bytes each, so the first write was refused. Pinned against the real file
     * so the parser cannot lose that shape without a failing test.
     */
    @Test
    fun `SC-P600 keyword keeps its high bytes as single bytes`() {
        val spec = parse().firstOrNull { it.modelName == "SC-P600 Series" }
            ?: throw AssertionError("SC-P600 Series not in devices.xml")

        assertArrayEquals(
            byteArrayOf(0x97.toByte(), 0x26, 0x86.toByte(), 0x97.toByte(), 0x36, 0x16, 0x16, 0xB6.toByte()),
            spec.keyword,
        )
    }

    /**
     * Resolves the asset from the working directory, which is the module
     * directory under Gradle but the repository root under some IDE runners.
     * Walking up handles both instead of depending on which one is in use.
     */
    private fun parse(): List<PrinterSpec> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "src/main/assets/devices.xml")
            if (candidate.isFile) {
                return candidate.inputStream().use {
                    EpsonPrinterCatalog.parseDevicesXml(it, parseMaintenanceBoxes())
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("devices.xml not found above ${System.getProperty("user.dir")}")
    }

    private fun parseMaintenanceBoxes(): Set<String> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "src/main/assets/maintenance_boxes.xml")
            if (candidate.isFile) {
                return candidate.inputStream().use(EpsonPrinterCatalog::parseMaintenanceBoxModels)
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "maintenance_boxes.xml not found above ${System.getProperty("user.dir")}",
        )
    }
}
