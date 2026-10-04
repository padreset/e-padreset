package com.padreset.epadreset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stored format's round trip.
 *
 * The bytes this format carries are the only record of what an EEPROM held before
 * a write, so a value that does not survive the trip is a value that cannot be put
 * back. The boundary bytes get their own test for that reason: `0x00` is the
 * reset value and the common case, and `0xFF` is where a signed/unsigned slip
 * would show up.
 */
class BackupCodecTest {

    private fun record(
        timestamp: Long = 1_759_500_000_000L,
        model: String = "L365",
        values: Map<Int, Byte> = mapOf(0x1C to 0x3F.toByte()),
        origin: BackupOrigin = BackupOrigin.UNKNOWN,
    ) = WasteBackupRecord(timestamp = timestamp, model = model, values = values, origin = origin)

    @Test
    fun `a record round-trips losslessly`() {
        val original = record(
            values = mapOf(
                0x001C to 0x00.toByte(),
                0x001D to 0xFF.toByte(),
                0x001E to 0x7F.toByte(),
                0x001F to 0x01.toByte(),
            ),
        )

        val decoded = BackupCodec.decodeHistory(BackupCodec.encodeHistory(listOf(original)))

        assertEquals(listOf(original), decoded)
    }

    @Test
    fun `a history of several records round-trips in order`() {
        val records = listOf(
            record(timestamp = 3_000L, model = "L365"),
            record(timestamp = 2_000L, model = "ET-2820"),
            record(timestamp = 1_000L, model = "XP-2100"),
        )

        val decoded = BackupCodec.decodeHistory(BackupCodec.encodeHistory(records))

        assertEquals(records, decoded)
    }

    @Test
    fun `a model name with separators and newlines survives`() {
        // The model comes from the printer's own device ID, and the guard compares
        // it, so mangling it here would make a matching printer look like a
        // different one.
        val awkward = "ET-2820 = odd\\name\nsecond line"
        val original = record(model = awkward)

        val decoded = BackupCodec.decodeHistory(BackupCodec.encodeHistory(listOf(original)))

        assertEquals(awkward, decoded.single().model)
    }

    @Test
    fun `a corrupt blob keeps the records that are still valid`() {
        val good = BackupCodec.encodeRecord(record(model = "L365"))
        val blob = buildString {
            appendLine("prose that was never a backup")
            appendLine(good)
            appendLine(BackupCodec.MAGIC)
            appendLine("model=Broken")
            appendLine("time=not-a-number")
            appendLine("0x001C = 0x01")
        }

        val decoded = BackupCodec.decodeHistory(blob)

        assertEquals(1, decoded.size)
        assertEquals("L365", decoded.single().model)
    }

    @Test
    fun `a record with no address line is dropped`() {
        val blob = buildString {
            appendLine(BackupCodec.MAGIC)
            appendLine("model=L365")
            appendLine("time=1000")
        }

        assertTrue(BackupCodec.decodeHistory(blob).isEmpty())
    }

    @Test
    fun `an empty or absent blob yields an empty history`() {
        assertTrue(BackupCodec.decodeHistory(null).isEmpty())
        assertTrue(BackupCodec.decodeHistory("").isEmpty())
        assertTrue(BackupCodec.decodeHistory("   \n  ").isEmpty())
    }

    // -- the origin line -----------------------------------------------------

    @Test
    fun `an origin survives the round trip`() {
        listOf(BackupOrigin.RESET, BackupOrigin.RESTORE).forEach { origin ->
            val decoded = BackupCodec.decodeHistory(
                BackupCodec.encodeHistory(listOf(record(origin = origin))),
            )

            assertEquals(origin, decoded.single().origin)
        }
    }

    @Test
    fun `an unknown origin is not written to the blob`() {
        // A block with no origin line is byte for byte what a build without this
        // field wrote, so the two formats stay interchangeable both ways.
        val encoded = BackupCodec.encodeRecord(record(origin = BackupOrigin.UNKNOWN))

        assertFalse("unexpected origin line in:\n$encoded", encoded.contains("origin="))
    }

    @Test
    fun `a record written before origins existed reads as unknown and is kept`() {
        // Exactly what is already on the user's device: no origin line. Losing
        // this record, or failing to read it, would lose a state they may need.
        val legacy = buildString {
            appendLine(BackupCodec.MAGIC)
            appendLine("model=L365")
            appendLine("time=1759500000000")
            appendLine("0x001C = 0x3F")
        }

        val decoded = BackupCodec.decodeHistory(legacy)

        assertEquals(1, decoded.size)
        assertEquals(BackupOrigin.UNKNOWN, decoded.single().origin)
        assertEquals("L365", decoded.single().model)
        assertEquals(0x3F.toByte(), decoded.single().values[0x1C])
    }

    @Test
    fun `an unrecognised origin reads as unknown and the record is kept`() {
        val future = buildString {
            appendLine(BackupCodec.MAGIC)
            appendLine("model=L365")
            appendLine("time=1759500000000")
            appendLine("origin=something-else")
            appendLine("0x001C = 0x3F")
        }

        val decoded = BackupCodec.decodeHistory(future)

        assertEquals(1, decoded.size)
        assertEquals(BackupOrigin.UNKNOWN, decoded.single().origin)
    }
}
