package com.padreset.epadreset.service

import com.padreset.epadreset.data.PrinterSpec
import com.padreset.epadreset.data.WasteCounterSpec
import com.padreset.epadreset.protocol.EpsonProtocol
import com.padreset.epadreset.service.PrinterService.WasteByteMismatch
import com.padreset.epadreset.service.PrinterService.WasteWriteException
import com.padreset.epadreset.service.PrinterService.WasteWriteException.Reason
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WasteVerificationTest {
    private fun spec(
        counters: List<WasteCounterSpec> = listOf(WasteCounterSpec("Main pad", listOf(0x1C, 0x1D), 13000)),
        reset: Map<Int, Int> = mapOf(0x1C to 0, 0x1D to 0),
    ) = PrinterSpec("Test", byteArrayOf(0, 0), ByteArray(8), counters, reset, emptyList())

    @Test
    fun `the complete backup is published before the first write`() {
        val printer = FakePrinter(initial = mapOf(0x1C to 63, 0x1D to 18))
        val result = printer.writer().writeAndVerify(
            mapOf(0x1C to 0, 0x1D to 0),
            onBackup = { backup ->
                assertEquals(mapOf(0x1C to 63.toByte(), 0x1D to 18.toByte()), backup)
                assertEquals(listOf("read 0x001C", "read 0x001D"), printer.calls)
                printer.calls += "persist backup"
            },
        ) { read -> listOf(read(0x1C), read(0x1D)) }

        assertEquals(listOf(0.toByte(), 0.toByte()), result.value)
        assertEquals(
            listOf("read 0x001C", "read 0x001D", "persist backup",
                "write 0x001C=0x00", "write 0x001D=0x00", "read 0x001C", "read 0x001D"),
            printer.calls,
        )
        assertEquals(63.toByte(), result.backup[0x1C])
    }

    @Test
    fun `verification only reads plan addresses missing from status`() {
        val printer = FakePrinter(initial = mapOf(0x1C to 11, 0x1D to 22, 0x32 to 33))
        val result = printer.writer().writeAndVerify(mapOf(0x1C to 0, 0x1D to 0, 0x32 to 95)) { read ->
            read(0x1C)
            read(0x1C) // Repeated status addresses share one post-write read too.
            read(0x1D)
        }
        assertEquals(3, result.backup.size)
        assertEquals(2, printer.calls.count { it == "read 0x001C" })
        assertEquals(2, printer.calls.count { it == "read 0x001D" })
        assertEquals(2, printer.calls.count { it == "read 0x0032" })
        assertEquals("read 0x0032", printer.calls.last())
    }

    @Test
    fun `ignored bytes outside displayed counters fail with expected and actual values`() {
        val printer = FakePrinter(initial = mapOf(0x32 to 0x7F), ignoredWrites = setOf(0x32))
        val failure = runCatching {
            printer.writer().writeAndVerify(mapOf(0x1C to 0, 0x32 to 0xFF)) { read -> read(0x1C) }
        }.exceptionOrNull() as? WasteWriteException

        assertEquals(Reason.NOT_APPLIED, failure?.reason)
        assertEquals(mapOf(0x32 to WasteByteMismatch(0xFF, 0x7F)), failure?.mismatches)
        assertEquals(0x7F.toByte(), failure?.backup?.get(0x32))
        assertTrue(failure?.message?.contains("0x0032 expected 0xFF, read 0x7F") == true)
    }

    @Test
    fun `both accepted and unknown ignored writes fail verification`() {
        for (reply in listOf(EpsonProtocol.WriteReply.Applied, EpsonProtocol.WriteReply.Unknown)) {
            val printer = FakePrinter(
                initial = mapOf(0x1C to 2), replies = mapOf(0x1C to reply), ignoredWrites = setOf(0x1C),
            )
            val failure = runCatching {
                printer.writer().writeAndVerify(mapOf(0x1C to 0)) { read -> read(0x1C) }
            }.exceptionOrNull() as? WasteWriteException
            assertEquals(Reason.NOT_APPLIED, failure?.reason)
            assertEquals(WasteByteMismatch(0, 2), failure?.mismatches?.get(0x1C))
        }
    }

    @Test
    fun `unknown acknowledgements can succeed when bytes really applied`() {
        val printer = FakePrinter(
            initial = mapOf(0x1C to 2), replies = mapOf(0x1C to EpsonProtocol.WriteReply.Unknown),
            applyUnknown = true,
        )
        val result = printer.writer().writeAndVerify(mapOf(0x1C to 0)) { read -> read(0x1C) }
        assertEquals(0.toByte(), result.value)
        assertEquals(2.toByte(), result.backup[0x1C])
    }

    @Test
    fun `ET1111 packed Int collision cannot hide a byte mismatch without a ceiling`() {
        val addresses = listOf(28, 52, 53, 54, 55)
        val plan = addresses.zip(listOf(0, 0, 0, 94, 94)).toMap()
        val model = spec(listOf(WasteCounterSpec("Platen pad", addresses, 0)), plan)
        val protocol = EpsonProtocol(model)
        // Pin the collision found in the real catalog. Verification must not use this Int.
        assertEquals(
            protocol.parseWasteCounter(byteArrayOf(0, 0, 0, 94, 94), 0, "Platen pad").rawValue,
            protocol.parseWasteCounter(byteArrayOf(2, 0, 0, 94, 94), 0, "Platen pad").rawValue,
        )
        val printer = FakePrinter(initial = mapOf(28 to 2), ignoredWrites = setOf(28))
        val failure = runCatching {
            printer.writer().writeAndVerify(WasteWriter.planFor(model, setOf("Platen pad"), 0)) { read ->
                protocol.parseWasteCounter(addresses.map(read).toByteArray(), 0, "Platen pad")
            }
        }.exceptionOrNull() as? WasteWriteException
        assertEquals(Reason.NOT_APPLIED, failure?.reason)
        assertEquals(mapOf(28 to WasteByteMismatch(0, 2)), failure?.mismatches)
    }

    @Test
    fun `ten byte groups verify every address including unsigned high bytes`() {
        val addresses = (100..109).toList()
        val plan = addresses.associateWith { 0xFF }
        val printer = FakePrinter()
        val result = printer.writer().writeAndVerify(plan) { read -> addresses.map(read) }
        assertEquals(List(10) { 0xFF.toByte() }, result.value)
        assertEquals(20, printer.calls.count { it.startsWith("read") })
    }

    @Test
    fun `C58 nonzero reset baseline verifies against the model plan not zero percent`() {
        val counter = WasteCounterSpec("Main pad", listOf(0x0D, 0x0C), 7800)
        val model = spec(listOf(counter), mapOf(0x0D to 0xF4, 0x0C to 0x01, 0x35 to 0x28))
        val printer = FakePrinter(initial = mapOf(0x0D to 0xFF))
        val plan = WasteWriter.planFor(model, setOf("Main pad"), 0)
        assertEquals(model.resetMap, plan)
        val result = printer.writer().writeAndVerify(plan) { read ->
            EpsonProtocol(model).parseWasteCounter(counter.addresses.map(read).toByteArray(), counter.max, counter.name)
        }
        assertEquals(500, result.value.rawValue)
        assertTrue(result.value.percentage > 2.0)
    }

    @Test
    fun `reset without a published ceiling verifies against the model plan`() {
        // Reinkpy-style model: no counter ceiling (max = 0, raw readout), but a
        // published reset baseline. planFor(target 0) must return the resetMap
        // without looking at max, and a printer that applies it verifies clean.
        val counter = WasteCounterSpec("Platen pad", listOf(28, 52, 53, 54, 55), 0)
        val model = spec(listOf(counter), mapOf(28 to 0, 52 to 0, 53 to 0, 54 to 94, 55 to 94))
        val plan = WasteWriter.planFor(model, setOf("Platen pad"), 0)
        assertEquals(model.resetMap, plan)
        val printer = FakePrinter(initial = mapOf(28 to 2, 54 to 10, 55 to 20))
        val result = printer.writer().writeAndVerify(plan) { read ->
            EpsonProtocol(model).parseWasteCounter(counter.addresses.map(read).toByteArray(), counter.max, counter.name)
        }
        // The backup still holds what was there before the write.
        assertEquals(2.toByte(), result.backup[28])
        assertEquals(10.toByte(), result.backup[54])
        assertEquals(20.toByte(), result.backup[55])
    }

    @Test
    fun `partial and debug percentage plans verify only bytes actually sent`() {
        val model = spec(
            listOf(WasteCounterSpec("Main pad", listOf(28, 29), 9999), WasteCounterSpec("Platen pad", listOf(30, 31), 7000)),
            mapOf(28 to 0xF4, 29 to 1, 30 to 0, 31 to 0, 50 to 95),
        )
        assertEquals(mapOf(28 to 0xF4, 29 to 1), WasteWriter.planFor(model, setOf("Main pad"), 0))
        val plan = WasteWriter.planFor(model, setOf("Main pad"), 33)
        assertEquals(mapOf(28 to 0xE3, 29 to 0x0C), plan) // 9999 * 33 / 100 = 3299.
        val printer = FakePrinter(initial = mapOf(30 to 0xAA, 31 to 0xBB, 50 to 0xCC))
        printer.writer().writeAndVerify(plan) { read -> listOf(28, 29, 30, 31).map(read) }
        assertFalse(printer.calls.any { it.startsWith("write 0x001E") || it.startsWith("write 0x001F") })
        assertFalse(printer.calls.any { it.contains("0x0032") })
        assertEquals(0xAA.toByte(), printer.memory[30])
    }

    @Test
    fun `partial reset writes the model values not zeros`() {
        // 195 of 299 reset blocks publish nonzero baselines. A partial reset
        // with target 0 used to fall into the percentage maths and write 0x00;
        // it must write the model's own values for the selected addresses.
        val model = spec(
            listOf(WasteCounterSpec("Main pad", listOf(28, 29), 9999), WasteCounterSpec("Platen pad", listOf(30, 31), 7000)),
            mapOf(28 to 0xF4, 29 to 1, 30 to 0, 31 to 0, 50 to 95),
        )
        assertEquals(mapOf(28 to 0xF4, 29 to 1), WasteWriter.planFor(model, setOf("Main pad"), 0))
        // Full selection keeps the whole map, including the auxiliary address
        // outside every counter's query range.
        assertEquals(model.resetMap, WasteWriter.planFor(model, setOf("Main pad", "Platen pad"), 0))
        val printer = FakePrinter()
        printer.writer().writeAndVerify(WasteWriter.planFor(model, setOf("Main pad"), 0)) { read ->
            listOf(28, 29).map(read)
        }
        assertEquals(0xF4.toByte(), printer.memory[28])
        assertEquals(0x01.toByte(), printer.memory[29])
        assertFalse(printer.calls.any { it.startsWith("write 0x001E") || it.startsWith("write 0x0032") })
    }

    @Test
    fun `partial reset on shared bytes refuses instead of certifying residue`() {
        // A nibble-shared address cannot be half-cleared without a
        // read-modify-write the write path does not perform — and the
        // byte-exact verification would still pass on the written bytes. So a
        // partial selection on such a model must fail loud, never silently.
        val model = spec(
            listOf(
                WasteCounterSpec("Main pad", listOf(0x30, 0x31, 0x2F), 6346, mapOf(0x2F to 0x0F)),
                WasteCounterSpec("Platen pad", listOf(0x32, 0x33, 0x2F), 3416, mapOf(0x2F to 0xF0)),
            ),
            mapOf(0x30 to 0, 0x31 to 0, 0x2F to 0, 0x32 to 0, 0x33 to 0),
        )
        assertTrue(
            runCatching { WasteWriter.planFor(model, setOf("Main pad"), 0) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { WasteWriter.planFor(model, setOf("Main pad"), 50) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        // The full set still plans every published value, shared byte included.
        assertEquals(model.resetMap, WasteWriter.planFor(model, setOf("Main pad", "Platen pad"), 0))
    }

    @Test
    fun `partial reset on whole shared bytes refuses without any mask`() {
        // RX700 shape: 0x2E/0x2F plain entries in two counters, no <filter>
        // anywhere. Masks are empty on both sides, so only the overlap rule
        // can see it — a mask-based guard certifies this corruption.
        val model = spec(
            listOf(
                WasteCounterSpec("Main pad", listOf(0x1C, 0x1D, 0x2E, 0x2F), 11000),
                WasteCounterSpec("Platen pad", listOf(0x1E, 0x1F, 0x2E, 0x2F), 14000),
            ),
            mapOf(0x1C to 0, 0x1D to 0, 0x2E to 0, 0x2F to 0),
        )
        assertEquals(setOf(0x2E, 0x2F), model.sharedAddresses)
        assertTrue(
            runCatching { WasteWriter.planFor(model, setOf("Main pad"), 0) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertEquals(model.resetMap, WasteWriter.planFor(model, setOf("Main pad", "Platen pad"), 0))
    }

    @Test
    fun `percentage writes without a ceiling cannot silently write zeros`() {
        val model = spec(listOf(WasteCounterSpec("Main pad", listOf(28, 29), 0)))
        assertTrue(runCatching { WasteWriter.planFor(model, setOf("Main pad"), 50) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `readback failures keep the published complete backup and never return success`() {
        for (statusAddresses in listOf(listOf(28, 29), listOf(28))) {
            val printer = FakePrinter(initial = mapOf(28 to 63, 29 to 18), unreadableAfterWrite = setOf(29))
            var saved: Map<Int, Byte>? = null
            val failure = runCatching {
                printer.writer().writeAndVerify(mapOf(28 to 0, 29 to 0), onBackup = { saved = it }) { read ->
                    statusAddresses.map(read)
                }
            }.exceptionOrNull() as? WasteWriteException
            assertEquals(Reason.READ_BACK_FAILED, failure?.reason)
            assertEquals(29, failure?.address)
            assertEquals(mapOf(28 to 63.toByte(), 29 to 18.toByte()), saved)
            assertEquals(saved, failure?.backup)
            assertNotNull(failure?.cause)
        }
    }

    @Test
    fun `invalid status after writes still carries the backup`() {
        val printer = FakePrinter(initial = mapOf(28 to 63))
        val failure = runCatching {
            printer.writer().writeAndVerify(mapOf(28 to 0)) { throw IllegalArgumentException("Unexpected status response") }
        }.exceptionOrNull() as? WasteWriteException
        assertEquals(Reason.READ_BACK_FAILED, failure?.reason)
        assertEquals(63.toByte(), failure?.backup?.get(28))
        assertEquals("Unexpected status response", failure?.cause?.message)
    }

    @Test
    fun `transport failure stops writes and carries the backup already published`() {
        val printer = FakePrinter(initial = mapOf(28 to 11, 29 to 22, 30 to 33), failedWrites = setOf(29))
        var saved: Map<Int, Byte>? = null
        val failure = runCatching {
            printer.writer().writeAndVerify(mapOf(28 to 0, 29 to 0, 30 to 0), onBackup = { saved = it }) { read -> read(28) }
        }.exceptionOrNull() as? WasteWriteException
        assertEquals(Reason.WRITE_FAILED, failure?.reason)
        assertEquals(29, failure?.address)
        assertEquals(1, failure?.writtenBefore)
        assertEquals(saved, failure?.backup)
        assertEquals(3, saved?.size)
        assertFalse(printer.calls.any { it.startsWith("write 0x001E") })
    }

    @Test
    fun `an observed mismatch is not erased by a later successful read`() {
        var reads = 0
        val writer = WasteWriter(
            readByte = { reads++; if (reads <= 2) 1 else 0 },
            writeByte = { _, _ -> EpsonProtocol.WriteReply.Applied }, log = {},
        )
        val failure = runCatching {
            writer.writeAndVerify(mapOf(28 to 0)) { read -> read(28); read(28) }
        }.exceptionOrNull() as? WasteWriteException
        assertEquals(Reason.NOT_APPLIED, failure?.reason)
        assertEquals(2, reads) // One backup read and one post-write read, no diagnostic retry.
    }

    @Test
    fun `restore verifies its exact unsigned backup plan through the same path`() {
        val restored = mapOf(28 to 0xFF.toByte(), 29 to 0x80.toByte())
        val printer = FakePrinter(initial = mapOf(28 to 63, 29 to 18), ignoredWrites = setOf(29))
        var before: Map<Int, Byte>? = null
        val failure = runCatching {
            printer.writer().writeAndVerify(WasteRestore.planFrom(restored), onBackup = { before = it }) { read ->
                listOf(read(28), read(29))
            }
        }.exceptionOrNull() as? WasteWriteException
        assertEquals(Reason.NOT_APPLIED, failure?.reason)
        assertEquals(mapOf(29 to WasteByteMismatch(0x80, 18)), failure?.mismatches)
        assertEquals(mapOf(28 to 63.toByte(), 29 to 18.toByte()), before)
        assertEquals(before, failure?.backup)
    }

    @Test
    fun `an incomplete backup never publishes a partial record or starts verification`() {
        val printer = FakePrinter(initial = mapOf(28 to 63), unreadable = setOf(29))
        var published = false
        var verified = false
        val failure = runCatching {
            printer.writer().writeAndVerify(mapOf(28 to 0, 29 to 0), onBackup = { published = true }) {
                verified = true
            }
        }.exceptionOrNull() as? WasteWriteException
        assertEquals(Reason.BACKUP_FAILED, failure?.reason)
        assertFalse(published)
        assertFalse(verified)
        assertTrue(failure?.backup?.isEmpty() == true)
        assertFalse(printer.calls.any { it.startsWith("write") })
    }

    @Test
    fun `cancellation propagates after backup publication`() {
        val cancellation = CancellationException("cancelled")
        var saved: Map<Int, Byte>? = null
        val writer = WasteWriter(
            readByte = { 63 }, writeByte = { _, _ -> throw cancellation }, log = {},
        )
        val failure = runCatching {
            writer.writeAndVerify(mapOf(28 to 0), onBackup = { saved = it }) { read -> read(28) }
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertEquals(mapOf(28 to 63.toByte()), saved)
    }
}
