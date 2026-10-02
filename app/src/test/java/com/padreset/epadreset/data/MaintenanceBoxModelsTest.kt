package com.padreset.epadreset.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the bundled maintenance-box list.
 *
 * The Gradle task `verifyPrinterData` already parses both assets on every
 * build, but it parses them with its own XML reader. This exercises the parser
 * the app actually runs, which is the one wrapped in `runCatching`: if its
 * expectations ever drift from the file's shape, the loader answers with an
 * empty set and the only visible symptom is that maintenance-box models quietly
 * go back to the hedged two-cause wording. Nothing else would catch that.
 */
class MaintenanceBoxModelsTest {

    @Test
    fun `parses the bundled list into a populated set`() {
        val models = parse()

        assertTrue(
            "expected the curated list to hold models, got ${models.size}",
            models.size >= 100,
        )
    }

    @Test
    fun `recognises models that are in the file`() {
        val models = parse()

        // One from each end of the alphabet, so a truncated file fails here
        // rather than at whatever model happens to be read first.
        assertTrue("EC-4020 missing", models.contains("EC-4020"))
        assertTrue("ET-16600 missing", models.contains("ET-16600"))
        assertTrue("XP-970 missing", models.contains("XP-970"))
    }

    @Test
    fun `normalises model names to upper case`() {
        val models = parse()

        // The file mirrors reinkpy's spelling, which mixes case: the Workforce
        // ST models arrive as "Workforce ST-3000". The catalog compares names
        // upper-cased, so the parser has to do the same, or those models would
        // never match a connected printer and the list would look half-applied.
        assertTrue("WORKFORCE ST-3000 missing", models.contains("WORKFORCE ST-3000"))
        assertFalse("mixed case leaked through", models.contains("Workforce ST-3000"))
    }

    @Test
    fun `does not claim models that are not in the file`() {
        assertFalse("an invented model matched", parse().contains("NOT-A-REAL-MODEL"))
    }

    /**
     * Resolves the asset from the working directory, which is the module
     * directory under Gradle but the repository root under some IDE runners.
     * Walking up handles both instead of depending on which one is in use.
     */
    private fun parse(): Set<String> {
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
