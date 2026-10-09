package com.padreset.epadreset.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loads the bundled printer catalog with the device's own XML parser.
 *
 * JVM unit tests parse the same files with the desktop parser, which accepts
 * factory settings Android's built-in parser rejects: a hardening change once
 * passed every unit test and still failed all three asset parses on device
 * (`This parser does not support specification "Unknown" version "0.0"`),
 * leaving the catalog empty and no printer able to connect. This test runs
 * where the app runs, so that class of breakage fails here instead of on a
 * user's desk.
 */
class AssetCatalogOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledCatalogLoadsOnDevice() {
        val specs = EpsonPrinterCatalog.load(context)
        assertTrue("devices.xml parsed to ${specs.size} specs", specs.size >= 500)
    }

    @Test
    fun streamingAndDomCatalogsMatchEveryModelFieldOnDevice() {
        val maintenanceModels = context.assets.open("maintenance_boxes.xml").use {
            StreamingPrinterCatalog.parseMaintenanceBoxes(it)
        }
        val devices = context.assets.open("devices.xml").use {
            StreamingPrinterCatalog.parseDevices(it, maintenanceModels)
        }
        val known = devices.mapTo(HashSet()) { EpsonPrinterCatalog.normalizeModelName(it.modelName) }
        val extras = context.assets.open("reinkpy_models.xml").use {
            StreamingPrinterCatalog.parseReinkpy(it, maintenanceModels)
        }.filterNot { EpsonPrinterCatalog.normalizeModelName(it.modelName) in known }
        val streaming = devices + extras
        val dom = EpsonPrinterCatalog.load(context)
        assertEquals("streaming catalog model names/order", dom.map { it.modelName }, streaming.map { it.modelName })
        assertEquals("streaming catalog fields", dom.map(::signature), streaming.map(::signature))
    }

    private fun signature(spec: PrinterSpec): List<Any> = listOf(
        spec.modelName,
        spec.controlModel.toList(),
        spec.keyword.toList(),
        spec.wasteCounters.map { listOf(it.name, it.addresses, it.max, it.masks.toSortedMap(), it.kind) },
        spec.resetMap.toSortedMap(),
        spec.cleaningOptions.map { listOf(it.name, it.type, it.payload.toList()) },
        spec.usesMaintenanceBox,
    )

    @Test
    fun maintenanceBoxListLoadsOnDevice() {
        // EC-4020 is on the reinkpy platen-only list; a broken parse answers
        // false for everything, so a true here proves the file parsed.
        assertTrue(EpsonPrinterCatalog.usesMaintenanceBox(context, "EC-4020"))
        assertEquals(false, EpsonPrinterCatalog.usesMaintenanceBox(context, "L365 Series"))
    }

    @Test
    fun l365ResolvesOnDevice() {
        // Real IEEE 1284 ID from an L365, the model that caught the parser
        // regression: resolution must find it, or nothing downstream can run.
        val deviceId = "MFG:EPSON;CMD:ESCPL2,BDC,D4,D4PX,ESCPR1,END4;" +
            "MDL:L365 Series;CLS:PRINTER;DES:EPSON L365 Series;"
        val spec = EpsonPrinterCatalog.resolve(context, deviceId)
        assertNotNull("L365 Series resolves", spec)
        assertTrue(
            "resolved spec belongs to the L365, got ${spec?.modelName}",
            spec?.modelName?.contains("L365") == true,
        )
    }

    @Test
    fun reinkpyExtensionParsesOnDevice() {
        // DX3850 lives only in reinkpy_models.xml: exercises the third parse
        // path (same factory) and the extension wiring in load().
        val spec = EpsonPrinterCatalog.resolve(context, "MFG:EPSON;MDL:DX3850;CLS:PRINTER;")
        assertNotNull("DX3850 resolves", spec)
        assertTrue(
            "resolved spec belongs to the DX3850, got ${spec?.modelName}",
            spec?.modelName?.contains("DX3850") == true,
        )
    }
}
