package com.padreset.epadreset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins what the paste box accepts and what it refuses.
 *
 * The input is whatever the copy button produced, so the parser is tied to that
 * output by test and not by memory. The refusals matter as much as the
 * acceptances: writing the wrong bytes to an address is the failure this whole
 * feature exists to undo, and a parser that guesses is how it would happen.
 */
class BackupTextParserTest {

    @Test
    fun `it parses the text the copy button produces`() {
        val copied = buildString {
            appendLine("EEPROM backup taken before the write — L365")
            appendLine("0x001C = 0x3F")
            appendLine("0x001D = 0x00")
        }

        val parsed = BackupTextParser.parse(copied)

        assertEquals("L365", parsed?.model)
        assertEquals(mapOf(0x1C to 0x3F.toByte(), 0x1D to 0x00.toByte()), parsed?.values)
    }

    @Test
    fun `a paste with only the address lines has no model`() {
        val parsed = BackupTextParser.parse("0x001C = 0x3F\n0x001D = 0x00")

        assertNull(parsed?.model)
        assertEquals(2, parsed?.values?.size)
    }

    @Test
    fun `the app's own placeholder means no model was named`() {
        val copied = "EEPROM backup taken before the write — unknown model\n0x001C = 0x3F"

        assertNull(BackupTextParser.parse(copied)?.model)
    }

    @Test
    fun `lowercase hex, stray spaces and blank lines are tolerated`() {
        val pasted = "  0x001c=0x3f  \n\n\t0x001D   =   0x00\n"

        val parsed = BackupTextParser.parse(pasted)

        assertEquals(mapOf(0x1C to 0x3F.toByte(), 0x1D to 0x00.toByte()), parsed?.values)
    }

    @Test
    fun `it accepts a stored block, magic line and all`() {
        val stored = buildString {
            appendLine(BackupCodec.MAGIC)
            appendLine("model=ET-2820")
            appendLine("time=1759500000000")
            appendLine("0x001C = 0x3F")
        }

        val parsed = BackupTextParser.parse(stored)

        assertEquals("ET-2820", parsed?.model)
        assertEquals(mapOf(0x1C to 0x3F.toByte()), parsed?.values)
    }

    @Test
    fun `prose with no address line is refused`() {
        assertNull(BackupTextParser.parse("hola, esto es lo que copié ayer"))
        assertNull(BackupTextParser.parse(""))
        assertNull(BackupTextParser.parse("   \n  "))
    }

    @Test
    fun `an invalid byte refuses the entire backup`() {
        // 0x1FF needs nine bits, and 0x1FFFF does not fit an address either.
        val pasted = "0x1C = 0x1FF\n0x1FFFF = 0x01\n0x1D = 0x00"

        val parsed = BackupTextParser.parse(pasted)

        assertNull(parsed)
    }

    @Test
    fun `nothing valid at all is refused rather than returned empty`() {
        assertNull(BackupTextParser.parse("0x1C = 0x1FF"))
    }

    @Test
    fun `contradictory repeated addresses are refused`() {
        val pasted = "0x001C = 0x11\n0x001C = 0x22"

        val parsed = BackupTextParser.parse(pasted)

        assertNull(parsed)
    }

    @Test
    fun `an origin line in a pasted block is ignored`() {
        // A pasted backup is never stored, so it carries no origin and the parser
        // has nothing to do with one. The line must not stop the block parsing.
        val stored = buildString {
            appendLine(BackupCodec.MAGIC)
            appendLine("model=L365")
            appendLine("time=1759500000000")
            appendLine("origin=reset")
            appendLine("0x001C = 0x3F")
        }

        val parsed = BackupTextParser.parse(stored)

        assertEquals("L365", parsed?.model)
        assertEquals(mapOf(0x1C to 0x3F.toByte()), parsed?.values)
    }

    @Test
    fun `a stored block without magic header is accepted and extracts unit and metadata`() {
        val clipped = buildString {
            appendLine("model=ET-2820")
            appendLine("time=1759500000000")
            appendLine("unit=SERIAL-12345")
            appendLine("count=2")
            appendLine("sha256=abcdef0123456789")
            appendLine("origin=reset")
            appendLine("0x001C = 0x3F")
            appendLine("0x001D = 0x00")
        }

        val parsed = BackupTextParser.parse(clipped)

        assertEquals("ET-2820", parsed?.model)
        assertEquals("SERIAL-12345", parsed?.unitId)
        assertEquals(mapOf(0x1C to 0x3F.toByte(), 0x1D to 0x00.toByte()), parsed?.values)
    }

    @Test
    fun `a stored block with mismatched count is refused`() {
        val clipped = buildString {
            appendLine("model=ET-2820")
            appendLine("count=3")
            appendLine("0x001C = 0x3F")
            appendLine("0x001D = 0x00")
        }

        assertNull(BackupTextParser.parse(clipped))
    }

    @Test
    fun `model and unit in stored blocks are unescaped`() {
        val clipped = buildString {
            appendLine("""model=ET-2820\\Special\nSeries""")
            appendLine("""unit=SERIAL\\12345\nREV""")
            appendLine("0x001C = 0x3F")
        }

        val parsed = BackupTextParser.parse(clipped)

        assertEquals("ET-2820\\Special\nSeries", parsed?.model)
        assertEquals("SERIAL\\12345\nREV", parsed?.unitId)
        assertEquals(mapOf(0x1C to 0x3F.toByte()), parsed?.values)
    }
}
