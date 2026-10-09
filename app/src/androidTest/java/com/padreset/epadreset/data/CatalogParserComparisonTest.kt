package com.padreset.epadreset.data

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

/** Compares complete production catalog semantics and measures full catalog construction. */
class CatalogParserComparisonTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetNames = listOf("devices.xml", "reinkpy_models.xml", "maintenance_boxes.xml")

    @Test
    fun domAndStreamingCatalogsMatchAndStreamingPerformanceIsMeasured() {
        val inputs = assetNames.associateWith { name -> context.assets.open(name).use(InputStream::readBytes) }

        fun parseCatalog(streaming: Boolean): List<PrinterSpec> {
            val maintenance = ByteArrayInputStream(inputs.getValue("maintenance_boxes.xml")).use {
                if (streaming) {
                    StreamingPrinterCatalog.parseMaintenanceBoxes(it)
                } else {
                    EpsonPrinterCatalog.parseMaintenanceBoxModelsDomForBenchmark(it)
                }
            }
            val devices = ByteArrayInputStream(inputs.getValue("devices.xml")).use {
                if (streaming) {
                    StreamingPrinterCatalog.parseDevices(it, maintenance)
                } else {
                    EpsonPrinterCatalog.parseDevicesXmlDomForBenchmark(it, maintenance)
                }
            }
            val extras = ByteArrayInputStream(inputs.getValue("reinkpy_models.xml")).use {
                if (streaming) {
                    StreamingPrinterCatalog.parseReinkpy(it, maintenance)
                } else {
                    EpsonPrinterCatalog.parseReinkpyModelsDomForBenchmark(it, maintenance)
                }
            }
            val known = devices.mapTo(HashSet()) { EpsonPrinterCatalog.normalizeModelName(it.modelName) }
            return devices + extras.filterNot { EpsonPrinterCatalog.normalizeModelName(it.modelName) in known }
        }

        var running = true
        var overallPeak = usedHeapBytes()
        val sampler = Thread {
            while (running) {
                overallPeak = maxOf(overallPeak, usedHeapBytes())
                SystemClock.sleep(1)
            }
        }.apply { start() }
        val dom = parseCatalog(streaming = false)
        val streamed = parseCatalog(streaming = true)
        running = false
        sampler.join()
        assertTrue("DOM parsed a populated catalog: ${dom.size}", dom.size >= 1_400)
        assertEquals("streaming and DOM catalog model order/count", dom.map { it.modelName }, streamed.map { it.modelName })
        assertEquals("every parsed model field matches DOM", dom.map(::signature), streamed.map(::signature))
        android.util.Log.i(
            "CatalogParserComparison",
            String.format(Locale.ROOT, "equivalence ok models=%d initialDomAndStreamingSampledHeapPeak=%.1fMiB", dom.size, overallPeak / 1048576.0),
        )

        // Warm each implementation once, then alternate five timings. Inputs are the same asset
        // bytes and include all parser work: maintenance list, devices records/specs, reinkpy,
        // merge/dedup, and construction of every PrinterSpec and nested field.
        parseCatalog(streaming = false)
        parseCatalog(streaming = true)
        val samples = mapOf("DOM full catalog" to mutableListOf<Long>(), "Streaming full catalog" to mutableListOf<Long>())
        val heapPeaks = mutableMapOf("DOM full catalog" to mutableListOf<Long>(), "Streaming full catalog" to mutableListOf<Long>())
        repeat(5) { iteration ->
            val order = if (iteration % 2 == 0) listOf(false, true) else listOf(true, false)
            order.forEach { streaming ->
                Runtime.getRuntime().gc()
                SystemClock.sleep(100)
                val label = if (streaming) "Streaming full catalog" else "DOM full catalog"
                var running = true
                var peak = usedHeapBytes()
                val sampler = Thread {
                    while (running) {
                        peak = maxOf(peak, usedHeapBytes())
                        SystemClock.sleep(1)
                    }
                }.apply { start() }
                val start = SystemClock.elapsedRealtimeNanos()
                val result = parseCatalog(streaming)
                val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                running = false
                sampler.join()
                assertEquals("$label catalog equivalence", dom.map(::signature), result.map(::signature))
                samples.getValue(label) += elapsed
                heapPeaks.getValue(label) += peak
            }
        }

        samples.forEach { (label, times) ->
            val sorted = times.sorted()
            val peakMiB = heapPeaks.getValue(label).maxOrNull()!! / 1048576.0
            android.util.Log.i(
                "CatalogParserComparison",
                String.format(
                    Locale.ROOT,
                    "%s ms=%s median=%d range=%d..%d sampledJavaHeapPeak=%.1fMiB models=%d",
                    label,
                    times,
                    sorted[sorted.size / 2],
                    sorted.first(),
                    sorted.last(),
                    peakMiB,
                    dom.size,
                ),
            )
        }
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

    private fun usedHeapBytes(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
}
