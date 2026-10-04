package com.padreset.epadreset.service

import com.padreset.epadreset.protocol.EpsonProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the restore rules against the scripted fake printer.
 *
 * A restore is a reset run backwards, so most of what it has to get right it
 * inherits from [WasteWriter] and is pinned there. What is new here is the two
 * things specific to it: the byte widening, and the read-back that decides
 * whether the values actually landed. The second one is not a formality — an
 * unrecognised reply is let through on purpose, so for those writes the read-back
 * is the only evidence there is.
 */
class WasteRestoreTest {

    @Test
    fun `the plan widens bytes without changing them`() {
        val plan = WasteRestore.planFrom(
            mapOf(
                0x1C to 0x00.toByte(),
                0x1D to 0xFF.toByte(),
                0x1E to 0x7F.toByte(),
            ),
        )

        // 0xFF as a signed byte is -1; widened without `and 0xFF` it would go on
        // the wire as something else entirely.
        assertEquals(mapOf(0x1C to 0x00, 0x1D to 0xFF, 0x1E to 0x7F), plan)
    }

    @Test
    fun `a restore the printer applies verifies clean`() {
        val printer = FakePrinter(initial = mapOf(0x1C to 0x00, 0x1D to 0x00))
        val values = mapOf(0x1C to 0x3F.toByte(), 0x1D to 0x12.toByte())
        val plan = WasteRestore.planFrom(values)

        val before = printer.writer().write(plan)
        val mismatches = WasteRestore.verify(plan, printer::read)

        // The pre-restore bytes are what a user would need to undo the restore.
        assertEquals(0x00.toByte(), before[0x1C])
        assertEquals(0x3F.toByte(), printer.memory[0x1C])
        assertTrue("unexpected mismatches: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun `a write the printer ignores is reported as a mismatch`() {
        // The app does not recognise this answer, so it does not stop the run —
        // and the memory never changes. Only the read-back catches it.
        val printer = FakePrinter(
            initial = mapOf(0x1C to 0x00, 0x1D to 0x00),
            replies = mapOf(0x1C to EpsonProtocol.WriteReply.Unknown),
        )
        val plan = WasteRestore.planFrom(
            mapOf(0x1C to 0x3F.toByte(), 0x1D to 0x12.toByte()),
        )

        printer.writer().write(plan)
        val mismatches = WasteRestore.verify(plan, printer::read)

        assertEquals(setOf(0x1C), mismatches.keys)
        assertEquals(0x00.toByte(), mismatches[0x1C])
    }

    @Test
    fun `a refusal mid-restore stops the run and carries the pre-restore bytes`() {
        val printer = FakePrinter(
            initial = mapOf(0x1C to 0x11, 0x1D to 0x22, 0x1E to 0x33),
            replies = mapOf(0x1D to EpsonProtocol.WriteReply.Rejected),
        )
        val plan = WasteRestore.planFrom(
            mapOf(
                0x1C to 0x3F.toByte(),
                0x1D to 0x12.toByte(),
                0x1E to 0x44.toByte(),
            ),
        )

        val failure = runCatching { printer.writer().write(plan) }
            .exceptionOrNull() as? PrinterService.WasteWriteException

        assertEquals(PrinterService.WasteWriteException.Reason.REJECTED, failure?.reason)
        assertEquals(0x1D, failure?.address)
        assertEquals(0x11.toByte(), failure?.backup?.get(0x1C))
        assertEquals(0x22.toByte(), failure?.backup?.get(0x1D))
        assertTrue(
            "the plan carried on past the refusal: ${printer.calls}",
            printer.calls.none { it == "write 0x001E=0x44" },
        )
    }
}
