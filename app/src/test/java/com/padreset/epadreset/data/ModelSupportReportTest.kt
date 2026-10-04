package com.padreset.epadreset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSupportReportTest {
    private fun report(id: String, box: Boolean = false) = ModelSupportReport.build(
        deviceId = id, appVersion = "1.0.0", androidVersion = "15",
        vendorId = 0x04B8, productId = 0x1234, maintenanceBox = box,
    )

    @Test
    fun `only explicitly allowed IEEE1284 fields are included`() {
        val result = report("MFG:EPSON;MDL:ET-9999;CMD:ESCPL2;CLS:PRINTER;SN:secret-one;SERN:secret-two;SERIALNUMBER:secret-three;DES:private-description;OTHER:secret-four;")
        assertEquals("ET-9999", result.model)
        assertTrue(result.body.contains("MFG: EPSON"))
        assertTrue(result.body.contains("CMD: ESCPL2"))
        assertTrue(result.body.contains("CLS: PRINTER"))
        listOf("secret-one", "secret-two", "secret-three", "secret-four", "private-description", "SN:", "SERN:").forEach {
            assertFalse("unexpected private field $it", result.body.contains(it))
        }
        assertTrue(result.body.contains("0x04B8 / 0x1234"))
        assertTrue(result.body.contains("1.0.0"))
    }

    @Test
    fun `field names are case insensitive and surrounding whitespace is ignored`() {
        val result = report(" mfg : Epson ; mdl : ET-1111 ; sn : PRIVATE;")
        assertEquals("ET-1111", result.model)
        assertFalse(result.body.contains("PRIVATE"))
    }

    @Test
    fun `missing or blank model names remain reportable without copying unknown fields`() {
        assertEquals("Unknown Epson model", report("MFG:EPSON;SN:PRIVATE;").model)
        assertEquals("Unknown Epson model", report("MDL: ;SN:PRIVATE;").model)
        assertFalse(report("SN:PRIVATE;").body.contains("PRIVATE"))
    }

    @Test
    fun `maintenance box report requests maintenance but explicitly rules out chip reset`() {
        val body = report("MDL:ET-16600;", box = true).body
        assertTrue(body.contains("Request maintenance support only"))
        assertTrue(body.contains("cannot reset a counter on the box chip"))
        assertTrue(body.contains("it is not guaranteed"))
    }

    @Test
    fun `unknown classification does not imply internal pads or guaranteed support`() {
        val body = report("MDL:ET-9999;").body
        assertTrue(body.contains("does not prove the model has internal pads"))
        assertTrue(body.contains("it is not guaranteed"))
        assertTrue(body.contains("read-only tests"))
        assertTrue(body.contains("Do not paste raw logs"))
    }

    @Test
    fun `control characters and excessively long fields cannot inflate the report`() {
        val result = report("MDL:ET-\n9999\r\u202E;CMD:${"X".repeat(10000)};")
        assertEquals("ET-9999", result.model)
        assertFalse(result.body.contains("\u202E"))
        assertTrue(result.body.length < 3500)
        assertFalse(result.body.contains("X".repeat(161)))
    }

    @Test
    fun `issue URL selects only the committed template and carries no report data`() {
        val url = ModelSupportReport.issueUrl("https://github.com/DSGM-GMSD/e-padreset/")
        assertEquals("https://github.com/DSGM-GMSD/e-padreset/issues/new?template=01-model-support.yml", url)
        assertFalse(url.contains("body="))
        assertFalse(url.contains("title="))
        assertFalse(url.contains("ET-"))
    }

    @Test
    fun `unavailable USB IDs are stated honestly`() {
        val body = ModelSupportReport.build("MDL:ET-9999;", "1", "15", null, -1, false).body
        assertTrue(body.contains("USB VID/PID: unknown / unknown"))
    }
}
