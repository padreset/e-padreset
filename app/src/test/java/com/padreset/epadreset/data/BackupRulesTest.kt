package com.padreset.epadreset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the backup exclusion rules against the silent-failure mode this app
 * already shipped once: an <exclude> that does not match the file on disk is
 * not an error for the framework — it simply does not apply, and the data
 * uploads anyway. Nothing at runtime ever reports that.
 *
 * BackupStore.PREFERENCE_FILE is "waste_backup_history"; SharedPreferences
 * persists it as waste_backup_history.xml, and Android's backup rules for the
 * "sharedpref" domain require the on-disk file name, extension included (the
 * official example is path="device.xml"). A rule naming the bare preference
 * name therefore matches nothing, and the EEPROM backup history would ride
 * along to Google Drive and D2D transfers.
 */
class BackupRulesTest {

    @Test
    fun `every sharedpref exclude names the prefs file with its xml extension`() {
        for ((file, doc) in rules()) {
            val excludes = doc.getElementsByTagName("exclude")
            val paths = mutableSetOf<String>()
            for (i in 0 until excludes.length) {
                val rule = excludes.item(i) as? Element ?: continue
                if (rule.getAttribute("domain") != "sharedpref") continue
                paths += rule.getAttribute("path")
            }
            assertEquals(file, setOf("${BackupStore.PREFERENCE_FILE}.xml", "${GapProfileStore.PREFERENCE_FILE}.xml"), paths)
        }
    }

    @Test
    fun `data extraction rules also exclude the history from device to device transfers`() {
        val doc = rules().single { it.first == "data_extraction_rules.xml" }.second
        val sections = doc.getElementsByTagName("device-transfer")
        assertTrue(
            "data_extraction_rules.xml has no <device-transfer> section; without one " +
                "D2D transfers stay fully enabled and carry the EEPROM history " +
                "onto another install",
            sections.length > 0,
        )
        val excludes = (sections.item(0) as Element).getElementsByTagName("exclude")
        assertTrue("the <device-transfer> section excludes nothing", excludes.length > 0)
        val paths = (0 until excludes.length).mapNotNull { excludes.item(it) as? Element }
            .filter { it.getAttribute("domain") == "sharedpref" }.map { it.getAttribute("path") }.toSet()
        assertEquals(setOf("${BackupStore.PREFERENCE_FILE}.xml", "${GapProfileStore.PREFERENCE_FILE}.xml"), paths)
    }

    @Test
    fun `no rule names the bare preference name without the extension`() {
        for ((file, doc) in rules()) {
            val excludes = doc.getElementsByTagName("exclude")
            for (i in 0 until excludes.length) {
                val rule = excludes.item(i) as? Element ?: continue
                if (rule.getAttribute("domain") != "sharedpref") continue
                val path = rule.getAttribute("path")
                assertFalse(
                    "$file names the bare preference name without .xml — that rule matches nothing on disk",
                    path == BackupStore.PREFERENCE_FILE,
                )
                assertTrue(
                    "$file uses a sharedpref path that does not end in .xml: \"$path\"",
                    path.endsWith(".xml"),
                )
            }
        }
    }

    /**
     * Resolves the res/xml directory from the working directory, which is the
     * module directory under Gradle but the repository root under some IDE
     * runners. Walking up handles both, like MaintenanceBoxModelsTest does for
     * the assets.
     */
    private fun rules(): List<Pair<String, org.w3c.dom.Document>> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val backupRules = File(dir, "src/main/res/xml/backup_rules.xml")
            if (backupRules.isFile) {
                return listOf(
                    "backup_rules.xml" to parse(backupRules),
                    "data_extraction_rules.xml" to
                        parse(File(dir, "src/main/res/xml/data_extraction_rules.xml")),
                )
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "could not locate src/main/res/xml/backup_rules.xml walking up from ${System.getProperty("user.dir")}",
        )
    }

    /**
     * Plain settings matching verifyPrinterData: the Gradle JDK parser would
     * accept hardening features Android's built-in parser rejects on device, so
     * the test reads the files the same way the build guard does.
     */
    private fun parse(file: File): org.w3c.dom.Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
}
