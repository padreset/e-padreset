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

    private class FakePrinter(
        initial: Map<Int, Int> = emptyMap(),
        private val unreadable: Set<Int> = emptySet(),
        private val replies: Map<Int, EpsonProtocol.WriteReply> = emptyMap(),
    ) {
        val memory: MutableMap<Int, Byte> =
            initial.mapValues { (_, value) -> value.toByte() }.toMutableMap()
        val calls = mutableListOf<String>()
        val lines = mutableListOf<String>()

        fun read(address: Int): Byte {
            calls += "read 0x%04X".format(address)
            if (address in unreadable) {
                throw IllegalStateException("no answer for 0x%04X".format(address))
            }
            return memory[address] ?: 0x00
        }

        fun write(address: Int, value: Int): EpsonProtocol.WriteReply {
            calls += "write 0x%04X=0x%02X".format(address, value)
            val reply = replies[address] ?: EpsonProtocol.WriteReply.Applied
            if (reply == EpsonProtocol.WriteReply.Applied) memory[address] = value.toByte()
            return reply
        }

        fun writer() = WasteWriter(::read, ::write) { line -> lines += line }
    }

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
