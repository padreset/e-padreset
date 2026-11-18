package com.padreset.epadreset.service

import com.padreset.epadreset.protocol.EpsonProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the write path's rules against a scripted fake printer.
 *
 * This is the harness the hardware cannot provide. The three things that make
 * the write path safe — back up before writing, stop at a refusal, do not stop
 * at an answer you do not recognise — only happen with a printer on the other
 * end of a cable, and none of them can be watched from a desk without one.
 *
 * The fake records every call in order, so the assertions can be about the
 * sequence and not only the outcome. The order is the safety property: a backup
 * that ran after the first write would still produce a plausible-looking map,
 * and a test that only checked the map would pass.
 *
 * The replies are scripted, not observed. What this proves is that the app
 * behaves correctly for a given answer; which answer a real printer gives is
 * still the open question, and the `Unknown` branch is where that lands.
 */
class WasteWriterTest {

    private fun failureFrom(block: () -> Unit): PrinterService.WasteWriteException? =
        runCatching(block).exceptionOrNull() as? PrinterService.WasteWriteException

    @Test
    fun `the whole plan is backed up before the first write`() {
        val printer = FakePrinter(initial = mapOf(0x1C to 0x3F, 0x1D to 0x00))

        printer.writer().write(mapOf(0x1C to 0x00, 0x1D to 0x00))

        assertEquals(
            listOf(
                "read 0x001C",
                "read 0x001D",
                "write 0x001C=0x00",
                "write 0x001D=0x00",
            ),
            printer.calls,
        )
    }

    @Test
    fun `the backup holds what was there before the write`() {
        val printer = FakePrinter(initial = mapOf(0x1C to 0x3F, 0x1D to 0x12))

        val backup = printer.writer().write(mapOf(0x1C to 0x00, 0x1D to 0x00))

        assertEquals(0x3F.toByte(), backup[0x1C])
        assertEquals(0x12.toByte(), backup[0x1D])
        // And the memory really did change, so the backup is not just an echo of
        // what was already there.
        assertEquals(0x00.toByte(), printer.memory[0x1C])
    }

    @Test
    fun `a backup that cannot be finished writes nothing at all`() {
        val printer = FakePrinter(initial = mapOf(0x1C to 0x3F), unreadable = setOf(0x1D))

        val failure = failureFrom {
            printer.writer().write(mapOf(0x1C to 0x00, 0x1D to 0x00))
        }

        assertEquals(PrinterService.WasteWriteException.Reason.BACKUP_FAILED, failure?.reason)
        assertEquals(0x1D, failure?.address)
        assertTrue(
            "nothing may be written when the backup is incomplete: ${printer.calls}",
            printer.calls.none { it.startsWith("write") },
        )
        assertEquals(0x3F.toByte(), printer.memory[0x1C])
        // No partial backup travels with the failure: nothing was written, so
        // there is nothing to put back, and showing a half-filled map would say
        // otherwise.
        assertTrue("expected an empty backup", failure?.backup?.isEmpty() == true)
    }

    @Test
    fun `a refused write stops the run and carries the backup`() {
        val printer = FakePrinter(
            initial = mapOf(0x1C to 0x11, 0x1D to 0x22, 0x1E to 0x33),
            replies = mapOf(0x1D to EpsonProtocol.WriteReply.Rejected),
        )

        val failure = failureFrom {
            printer.writer().write(mapOf(0x1C to 0x00, 0x1D to 0x00, 0x1E to 0x00))
        }

        assertEquals(PrinterService.WasteWriteException.Reason.REJECTED, failure?.reason)
        assertEquals(0x1D, failure?.address)
        assertEquals(1, failure?.writtenBefore)

        // The address after the refusal must be untouched. That is the whole
        // point of stopping there instead of finishing the plan and reporting at
        // the end.
        assertTrue(
            "the plan carried on past the refusal: ${printer.calls}",
            printer.calls.none { it == "write 0x001E=0x00" },
        )
        assertEquals(0x33.toByte(), printer.memory[0x1E])

        // And the backup survives the failure, because part of the memory has
        // already changed by then.
        assertEquals(0x11.toByte(), failure?.backup?.get(0x1C))
        assertEquals(0x22.toByte(), failure?.backup?.get(0x1D))

        // The log has to say what happened, since that is what a user pastes
        // when asking why a reset stopped.
        assertTrue(
            "no line explains the refusal: ${printer.lines}",
            printer.lines.any { it.contains("refused") },
        )
    }

    @Test
    fun `a refusal is retried once with the alternate key`() {
        // Firmware that answers :NG; to the model's write key and :OK; to the
        // alternate. reinkpy publishes both keys for 549 of 555 models, and this
        // is the case that made the fallback worth carrying.
        val printer = FakePrinter(initial = mapOf(0x1C to 0x11, 0x1D to 0x22))

        printer.writerWithAlternateKey(setOf(0x1C)).write(mapOf(0x1C to 0x00, 0x1D to 0x00))

        // The whole plan is repeated under the second key, not just the refused
        // address: the first pass may have written earlier bytes with a key the
        // printer did not honour.
        assertEquals(
            listOf(
                "read 0x001C",
                "read 0x001D",
                "write 0x001C=0x00",
                "write[alt] 0x001C=0x00",
                "write[alt] 0x001D=0x00",
            ),
            printer.calls,
        )
        assertEquals(0x00.toByte(), printer.memory[0x1C])
        assertEquals(0x00.toByte(), printer.memory[0x1D])
    }

    @Test
    fun `both keys refused still reports the refusal`() {
        val printer = FakePrinter(initial = mapOf(0x1C to 0x11, 0x1D to 0x22))
        val writer = WasteWriter(
            readByte = printer::read,
            writeByte = { address, value ->
                printer.calls += "write 0x%04X=0x%02X".format(address, value)
                EpsonProtocol.WriteReply.Rejected
            },
            writeByteAlternate = { address, value ->
                printer.calls += "write[alt] 0x%04X=0x%02X".format(address, value)
                EpsonProtocol.WriteReply.Rejected
            },
            log = { line -> printer.lines += line },
        )

        val failure = failureFrom { writer.write(mapOf(0x1C to 0x00, 0x1D to 0x00)) }

        assertEquals(PrinterService.WasteWriteException.Reason.REJECTED, failure?.reason)
        assertEquals(0x1C, failure?.address)
        // The backup still travels with the failure, and it holds what was
        // there before either attempt.
        assertEquals(0x11.toByte(), failure?.backup?.get(0x1C))
        assertEquals(0x22.toByte(), failure?.backup?.get(0x1D))
    }

    @Test
    fun `a retry refused earlier still reports what the first pass wrote`() {
        // The primary key writes 0x1C and 0x1D and is refused at 0x1E; the
        // alternate is refused at the very first address. Both bytes the first
        // pass wrote have changed, and the message is what decides whether the
        // user reaches for the backup, so it must not say "0 written".
        val printer = FakePrinter(
            initial = mapOf(0x1C to 0x11, 0x1D to 0x22, 0x1E to 0x33),
            replies = mapOf(0x1E to EpsonProtocol.WriteReply.Rejected),
        )
        val writer = WasteWriter(
            readByte = printer::read,
            writeByte = printer::write,
            writeByteAlternate = { address, value ->
                printer.calls += "write[alt] 0x%04X=0x%02X".format(address, value)
                EpsonProtocol.WriteReply.Rejected
            },
            log = { line -> printer.lines += line },
        )

        val failure = failureFrom { writer.write(mapOf(0x1C to 0x00, 0x1D to 0x00, 0x1E to 0x00)) }

        assertEquals(PrinterService.WasteWriteException.Reason.REJECTED, failure?.reason)
        assertEquals(0x1C, failure?.address)
        assertEquals(2, failure?.writtenBefore)
        assertEquals(0x00.toByte(), printer.memory[0x1C])
        assertEquals(0x00.toByte(), printer.memory[0x1D])
    }

    @Test
    fun `a transport failure during the retry still reports what the first pass wrote`() {
        val printer = FakePrinter(
            initial = mapOf(0x1C to 0x11, 0x1D to 0x22, 0x1E to 0x33),
            replies = mapOf(0x1E to EpsonProtocol.WriteReply.Rejected),
        )
        val writer = WasteWriter(
            readByte = printer::read,
            writeByte = printer::write,
            writeByteAlternate = { _, _ -> error("write timeout") },
            log = { line -> printer.lines += line },
        )

        val failure = failureFrom { writer.write(mapOf(0x1C to 0x00, 0x1D to 0x00, 0x1E to 0x00)) }

        assertEquals(PrinterService.WasteWriteException.Reason.WRITE_FAILED, failure?.reason)
        assertEquals(0x1C, failure?.address)
        assertEquals(2, failure?.writtenBefore)
    }

    @Test
    fun `a reply the app does not recognise does not stop the run`() {
        val printer = FakePrinter(
            initial = mapOf(0x1C to 0x11, 0x1D to 0x22),
            replies = mapOf(0x1C to EpsonProtocol.WriteReply.Unknown),
        )

        printer.writer().write(mapOf(0x1C to 0x00, 0x1D to 0x00))

        // Both addresses were attempted: an answer the app cannot read is not
        // evidence of a refusal, and stopping on it would break models that work.
        assertEquals(
            listOf(
                "read 0x001C",
                "read 0x001D",
                "write 0x001C=0x00",
                "write 0x001D=0x00",
            ),
            printer.calls,
        )
    }

    @Test
    fun `addresses are written in a stable order`() {
        // The plan arrives as a map, whose iteration order is not something to
        // rely on. A run that wrote 0x1E before 0x1C would still be correct, but
        // it would make every log and every refusal message differ between runs.
        val printer = FakePrinter(initial = mapOf(0x1E to 0x00, 0x1C to 0x00, 0x1D to 0x00))

        printer.writer().write(mapOf(0x1E to 0x01, 0x1C to 0x01, 0x1D to 0x01))

        assertEquals(
            listOf("write 0x001C=0x01", "write 0x001D=0x01", "write 0x001E=0x01"),
            printer.calls.filter { it.startsWith("write") },
        )
    }
}
