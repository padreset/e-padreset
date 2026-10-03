package com.padreset.epadreset.protocol

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the nozzle check sequence.
 *
 * This exists because the sequence was wrong in a way nothing else would catch.
 * The app offered `NC 00 10` and `NC 00 00` as two selectable tests, which is how
 * Ircama's encoder presents them; the reference implementation sends both, in
 * that order, as one job. Sent alone the first one leaves the printer waiting for
 * the rest, and it gives up without reporting anything — so the failure is a
 * printer that lights up and does nothing, which no assertion about a single
 * command would have flagged.
 */
class Escp2MaintenanceProtocolTest {

    /**
     * Bytes as Ints. `ByteArray.toList()` yields `List<Byte>`, and comparing that
     * against a `List<Int>` is always false — it compiles, and it fails, which is
     * how this test first went in.
     */
    private fun jobBytes(): List<Int> =
        Escp2MaintenanceProtocol.buildNozzleCheckJob().map { it.toInt() and 0xFF }

    @Test
    fun `nozzle check sends VI, then both NC commands, in that order`() {
        val expected = listOf(
            0x56, 0x49, 0x02, 0x00, 0x00, 0x00, // VI 00 00
            0x4E, 0x43, 0x02, 0x00, 0x00, 0x10, // NC 00 10
            0x4E, 0x43, 0x02, 0x00, 0x00, 0x00, // NC 00 00
        )

        val job = jobBytes()

        assertTrue(
            "the three commands must appear together and in this order",
            job.windowed(expected.size).any { it == expected },
        )
    }

    @Test
    fun `the first NC command is never sent without the second`() {
        val firstStep = listOf(0x4E, 0x43, 0x02, 0x00, 0x00, 0x10)
        val secondStep = listOf(0x4E, 0x43, 0x02, 0x00, 0x00, 0x00)

        val job = jobBytes()

        val firstAt = job.windowed(firstStep.size).indexOfFirst { it == firstStep }
        val secondAt = job.windowed(secondStep.size).indexOfFirst { it == secondStep }

        assertTrue("the leading NC command must be present", firstAt >= 0)
        assertTrue("the trailing NC command must be present", secondAt >= 0)
        assertTrue("the leading one must come first", firstAt < secondAt)
    }

    @Test
    fun `the job carries a form feed, which is what ejects the page`() {
        val job = jobBytes()

        assertTrue(
            "without it the sheet stays in the printer",
            job.contains(0x0C),
        )
    }
}
