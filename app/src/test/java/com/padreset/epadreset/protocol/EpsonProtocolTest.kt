package com.padreset.epadreset.protocol

import com.padreset.epadreset.data.PrinterSpec
import com.padreset.epadreset.usb.UsbTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpsonProtocolTest {
    private val spec = PrinterSpec(
        modelName = "ET-2810 Series",
        controlModel = byteArrayOf(0x4A, 0x36),
        keyword = byteArrayOf(0x4E, 0x62, 0x73, 0x6A, 0x63, 0x62, 0x7A, 0x62),
        wasteCounters = emptyList(),
        resetMap = emptyMap(),
        // Required, and empty here on purpose: this spec exists to exercise
        // framing, and none of the assertions below touch cleaning. It was left
        // out when the field was added, which stopped the whole unit test
        // source set from compiling and took every test in it down with it.
        cleaningOptions = emptyList(),
    )
    private val protocol = EpsonProtocol(spec)

    @Test
    fun `invalid MTU refuses chunks instead of building impossible frames`() {
        listOf(0, 6, 65536).forEach { mtu ->
            assertThrows(IllegalArgumentException::class.java) {
                protocol.chunkForChannel(EpsonProtocol.D4ChannelState(2, 2, mtu, 1), byteArrayOf(1, 2))
            }
        }
        assertEquals(2, protocol.chunkForChannel(EpsonProtocol.D4ChannelState(2, 2, 7, 2), byteArrayOf(1, 2)).size)
    }

    @Test
    fun `status request matches ez-reset framing`() {
        val expected = byteArrayOf(0x73, 0x74, 0x01, 0x00, 0x01)
        assertArrayEquals(expected, protocol.buildStatusRequest())
    }

    @Test
    fun `factory write command matches service payload format`() {
        val command = protocol.buildWriteEepromCommand(0x30, 0x00)

        // The frame is "||" + little-endian length + body, and the length counts
        // everything in the body: the control model, the three action-code
        // bytes, the address, the value and the keyword.
        //
        // This used to be a hand-written 12-byte prefix whose length field read
        // 13 — written back when the action code sat outside the count. Nothing
        // caught it, because the field was added to PrinterSpec without updating
        // this file and the whole test source set stopped compiling, so no test
        // in it had run since. Deriving the frame from its parts is what keeps
        // the expectation from drifting away from the builder again.
        val body = spec.controlModel +
            byteArrayOf(0x42, 0xBD.toByte(), 0x21) +
            byteArrayOf(0x30, 0x00, 0x00) +
            spec.keyword

        val expected = "||".encodeToByteArray() +
            byteArrayOf((body.size and 0xFF).toByte(), ((body.size ushr 8) and 0xFF).toByte()) +
            body

        assertArrayEquals(expected, command)
        // Pinned separately so a change in what the body is made of shows up
        // here as well as in the byte comparison.
        assertEquals(16, body.size)
    }

    @Test
    fun `write keyword with bytes above 0x7F reaches the frame as one byte each`() {
        // Regression for a text-based keyword loader that turned each high byte
        // into two, producing a 12-byte keyword the printer refused. The SC-P600
        // key is 0x97 0x26 0x86 0x97 0x36 0x16 0x16 0xB6.
        val keyword = byteArrayOf(
            0x97.toByte(), 0x26, 0x86.toByte(), 0x97.toByte(), 0x36, 0x16, 0x16, 0xB6.toByte(),
        )
        val highKeyProtocol = EpsonProtocol(
            PrinterSpec(
                modelName = "SC-P600 Series",
                controlModel = byteArrayOf(0x4A, 0x36),
                keyword = keyword,
                wasteCounters = emptyList(),
                resetMap = emptyMap(),
                cleaningOptions = emptyList(),
            ),
        )

        val command = highKeyProtocol.buildWriteEepromCommand(0x30, 0x00)

        // Keyword is the tail of the frame, unchanged: 8 bytes, no expansion.
        assertArrayEquals(keyword, command.copyOfRange(command.size - keyword.size, command.size))
        // Length field counts the body: model (2) + action code (3) + address,
        // value (3) + keyword (8) = 16, so the frame is 2 + 2 + 16 bytes.
        val lengthField = (command[2].toInt() and 0xFF) or ((command[3].toInt() and 0xFF) shl 8)
        assertEquals(16, lengthField)
        assertEquals(20, command.size)
    }

    @Test
    fun `write replies are classified by their service markers`() {
        assertEquals(
            EpsonProtocol.WriteReply.Applied,
            protocol.classifyWriteReply("@BDC PS\r\n||:42:OK;".encodeToByteArray()),
        )
        assertEquals(
            EpsonProtocol.WriteReply.Rejected,
            protocol.classifyWriteReply("@BDC PS\r\n||:42:NG;".encodeToByteArray()),
        )
        assertEquals(
            EpsonProtocol.WriteReply.Rejected,
            protocol.classifyWriteReply("@BDC PS\r\n||:42:NA;".encodeToByteArray()),
        )
    }

    @Test
    fun `a service cleaning rejection includes NG as well as NA`() {
        // Cleaning used to look only for :NA;, so a printer that answered :NG;
        // was treated as success and the ESC/P2 fallback never ran.
        assertTrue(protocol.isNegativeMaintenanceReply("@BDC PS\r\n||:42:NG;".encodeToByteArray()))
        assertTrue(protocol.isNegativeMaintenanceReply("@BDC PS\r\n||:42:NA;".encodeToByteArray()))
        assertFalse(protocol.isNegativeMaintenanceReply("@BDC PS\r\n||:42:OK;".encodeToByteArray()))
    }

    @Test
    fun `an unrecognised write reply is not treated as a rejection`() {
        // The markers come from a sibling implementation talking over the raw
        // USB pipe rather than D4. Until a run on real hardware confirms the
        // shape here, an answer the app does not know must leave the write
        // alone: aborting on it would break models that work today.
        assertEquals(
            EpsonProtocol.WriteReply.Unknown,
            protocol.classifyWriteReply(byteArrayOf(0x00, 0x01, 0x02)),
        )
        assertEquals(
            EpsonProtocol.WriteReply.Unknown,
            protocol.classifyWriteReply(ByteArray(0)),
        )
    }

    @Test
    fun `waste counter follows ez-reset little-endian byte assembly`() {
        val value = protocol.parseWasteCounter(
            rawBytes = byteArrayOf(0xCA.toByte(), 0x18, 0x00),
            max = 9999,
            name = "Counter",
        )
        assertEquals(6346, value.rawValue)
    }

    @Test
    fun `wide counter groups report exact hex instead of a shifted mixture`() {
        // Five bytes no longer fit the Int fold (shifts wrap at 32 bits), so
        // the value saturates and the bytes travel alongside as hex.
        val first = protocol.parseWasteCounter(
            rawBytes = byteArrayOf(0x00, 0x00, 0x00, 0x5E, 0x5E),
            max = 0,
            name = "Platen pad",
        )
        val second = protocol.parseWasteCounter(
            rawBytes = byteArrayOf(0x02, 0x00, 0x00, 0x5E, 0x5E),
            max = 0,
            name = "Platen pad",
        )
        assertEquals("00 00 00 5E 5E", first.rawHex)
        assertEquals("02 00 00 5E 5E", second.rawHex)
        assertEquals(Int.MAX_VALUE, first.rawValue)
        // The old fold OR-ed byte 5 over byte 1 and read both inputs alike.
        assertTrue(first.rawHex != second.rawHex)
    }

    @Test
    fun `L365 paper light on maps status error 0x06 to paper feed error`() {
        // The live L365 reported ST2 parameter 0x02 = 0x06 while its paper
        // indicator was lit. Epson's L365 guide describes a steady paper light
        // as no paper loaded or multiple sheets fed at once; the wire status
        // code is documented in the shared Epson status table as paper out.
        val params = byteArrayOf(
            0x01, 0x01, 0x00,
            0x02, 0x01, 0x06,
        )
        val payload = byteArrayOf(
            (params.size and 0xFF).toByte(),
            ((params.size ushr 8) and 0xFF).toByte(),
        ) + params
        val snapshot = protocol.parseStatusResponse(
            "@BDC ST2\r\n".encodeToByteArray() + payload,
            "MFG:EPSON;MDL:L365;",
        )

        assertEquals(0x00, snapshot.stateCode)
        assertEquals(0x06, snapshot.errorCode)
        assertEquals("Error", snapshot.state)
        assertEquals("Paper feed error", snapshot.error)
    }

    @Test
    fun `zero ink entry size yields no levels instead of hanging`() {
        // A 0x0F parameter whose first byte is 0x00 used to freeze the parse
        // loop (index never advances) and hang the status read on IO.
        val params = byteArrayOf(0x0F, 0x01, 0x00)
        val statusPayload = byteArrayOf(
            (params.size and 0xFF).toByte(),
            ((params.size ushr 8) and 0xFF).toByte(),
        ) + params
        val payload = "@BDC ST2\r\n".encodeToByteArray() + statusPayload
        val snapshot = protocol.parseStatusResponse(payload, "MFG:EPSON;MDL:Test;")
        assertTrue(snapshot.inkLevels.isEmpty())
    }

    @Test
    fun `a truncated status payload is rejected instead of reading off the end`() {
        // The header check used to be the only guard. A body shorter than the
        // length field, or an element that claims more bytes than remain, threw
        // IndexOutOfBoundsException and surfaced as a generic operation failure.
        val header = "@BDC ST2\r\n".encodeToByteArray()
        val deviceId = "MFG:EPSON;MDL:Test;"
        assertThrows(IllegalArgumentException::class.java) {
            protocol.parseStatusResponse(header, deviceId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            protocol.parseStatusResponse(header + byteArrayOf(0x00), deviceId)
        }
        // Outer length matches (4 + 2), so the length require passes and the
        // element loop is what has to stop: it declares 4 bytes and has 2.
        val overrun = byteArrayOf(0x04, 0x00, 0x01, 0x04, 0x00, 0x00)
        assertThrows(IllegalArgumentException::class.java) {
            protocol.parseStatusResponse(header + overrun, deviceId)
        }
    }

    @Test
    fun `USB read buffer preserves and consumes partial transfer fragments in order`() {
        val buffer = UsbTransport.ReadBuffer()
        val frame = protocol.encodeD4Frame(
            EpsonProtocol.D4Frame(
                psid = 2,
                ssid = 3,
                credit = 0x7F,
                control = 0x02,
                payload = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x41),
            ),
        )
        val fragments = listOf(
            frame.copyOfRange(0, 1),
            frame.copyOfRange(1, 4),
            frame.copyOfRange(4, 6),
            frame.copyOfRange(6, 8),
            frame.copyOfRange(8, frame.size),
        )

        buffer.append(fragments[0])
        buffer.append(fragments[1])
        assertEquals(4, buffer.size)
        assertThrows(IllegalArgumentException::class.java) { buffer.take(6) }
        assertEquals(4, buffer.size)

        buffer.append(fragments[2])
        val header = buffer.take(6)
        assertArrayEquals(frame.copyOfRange(0, 6), header)
        assertEquals(0, buffer.size)

        buffer.append(fragments[3])
        assertEquals(2, buffer.size)
        assertThrows(IllegalArgumentException::class.java) { buffer.take(frame.size - 6) }
        assertEquals(2, buffer.size)

        buffer.append(fragments[4])
        val payload = buffer.take(frame.size - 6)
        assertArrayEquals(frame.copyOfRange(6, frame.size), payload)
        assertEquals(0, buffer.size)
        assertArrayEquals(frame, header + payload)
    }

    @Test
    fun `USB read buffer rejects negative or unavailable reads without consuming bytes`() {
        val buffer = UsbTransport.ReadBuffer()
        buffer.append(byteArrayOf(0x10, 0x20, 0x30))

        assertThrows(IllegalArgumentException::class.java) { buffer.take(-1) }
        assertThrows(IllegalArgumentException::class.java) { buffer.take(4) }
        assertEquals(3, buffer.size)
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30), buffer.take(3))
    }

    @Test
    fun `USB read buffer clear discards stale bytes before a new protocol session`() {
        val buffer = UsbTransport.ReadBuffer()
        buffer.append(byteArrayOf(0x40, 0x42, 0x44, 0x43))
        buffer.clear()
        buffer.append(byteArrayOf(0x02, 0x03, 0x00, 0x06, 0x01, 0x00))

        assertArrayEquals(byteArrayOf(0x02, 0x03, 0x00, 0x06, 0x01, 0x00), buffer.take(6))
        assertEquals(0, buffer.size)
    }

    @Test
    fun `D4 frame survives arbitrary USB partial-read boundaries`() {
        val frame = EpsonProtocol.D4Frame(
            psid = 2,
            ssid = 3,
            credit = 0x7F,
            control = 0x02,
            payload = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x41),
        )
        val wire = protocol.encodeD4Frame(frame)
        val buffer = UsbTransport.ReadBuffer()
        buffer.append(wire.copyOfRange(0, 1))
        buffer.append(wire.copyOfRange(1, 4))
        buffer.append(wire.copyOfRange(4, 6))
        buffer.append(wire.copyOfRange(6, wire.size))

        val header = buffer.take(6)
        assertTrue(protocol.isPlausibleD4Header(header, EpsonProtocol.D4ChannelState(2, 3, 64, 1)))
        val payloadSize = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val reconstructed = header + buffer.take(payloadSize - 6)

        assertArrayEquals(wire, reconstructed)
        assertEquals(frame.psid, protocol.decodeD4Frame(reconstructed).psid)
        assertEquals(frame.ssid, protocol.decodeD4Frame(reconstructed).ssid)
        assertArrayEquals(frame.payload, protocol.decodeD4Frame(reconstructed).payload)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `D4 decoder rejects truncated header, partial payload, and inconsistent declared length`() {
        val valid = protocol.encodeD4Frame(
            EpsonProtocol.D4Frame(2, 3, 1, 0, byteArrayOf(0x10, 0x20, 0x30)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            protocol.decodeD4Frame(valid.copyOfRange(0, 5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            protocol.decodeD4Frame(valid.copyOfRange(0, valid.lastIndex))
        }
        assertThrows(IllegalArgumentException::class.java) {
            protocol.decodeD4Frame(valid + byteArrayOf(0x40))
        }
    }

    @Test
    fun `D4 header plausibility rejects truncated, undersized, and oversized frames`() {
        val channel = EpsonProtocol.D4ChannelState(psid = 2, ssid = 3, mtu = 64, txCredits = 0)
        val tooLarge = byteArrayOf(0x02, 0x03, 0x20, 0x01, 0x01, 0x00) // 8193 bytes
        val tooSmall = byteArrayOf(0x02, 0x03, 0x00, 0x05, 0x01, 0x00)

        assertFalse(protocol.isPlausibleD4Header(byteArrayOf(0x02, 0x03), channel))
        assertFalse(protocol.isPlausibleD4Header(tooSmall, channel))
        assertFalse(protocol.isPlausibleD4Header(tooLarge, channel))
        assertTrue(protocol.isPlausibleD4Header(byteArrayOf(0x02, 0x03, 0x00, 0x06, 0x01, 0x00), channel))
    }

    @Test
    fun `stale stream bytes fail D4 header validation`() {
        // Bytes lifted from a real log: after several print jobs the next read
        // landed on an old `@BDC …` payload (`40 42 44 43 20 53`) and derived a
        // 17 kB "length" from it, then timed out chasing bytes never sent.
        val channel = EpsonProtocol.D4ChannelState(psid = 2, ssid = 2, mtu = 64, txCredits = 0)
        assertFalse(
            protocol.isPlausibleD4Header(
                byteArrayOf(0x40, 0x42, 0x44, 0x43, 0x20, 0x53),
                channel,
            ),
        )
        // Tail of an earlier ST2 payload, same story.
        assertFalse(
            protocol.isPlausibleD4Header(
                byteArrayOf(0x54, 0x32, 0x0D, 0x0A, 0x62, 0x00),
                channel,
            ),
        )
        // A header for another channel is equally unusable here.
        assertFalse(
            protocol.isPlausibleD4Header(
                byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x01, 0x00),
                channel,
            ),
        )
        // Real headers pass: control channel and management channel.
        assertTrue(
            protocol.isPlausibleD4Header(
                byteArrayOf(0x02, 0x02, 0x00, 0x76, 0x00, 0x01),
                channel,
            ),
        )
        assertTrue(
            protocol.isPlausibleD4Header(byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x01, 0x00)),
        )
        // A well-formed header for another channel is equally unusable here —
        // pinned as rejected until hardware with asymmetric ids says otherwise.
        assertFalse(
            protocol.isPlausibleD4Header(
                byteArrayOf(0x02, 0x03, 0x00, 0x76, 0x00, 0x01),
                channel,
            ),
        )
    }

    @Test
    fun `asymmetric channel distinguishes echo from swap`() {
        // With a symmetric (2, 2) channel, 02 02 passes whether the check
        // compares straight or swapped — the test cannot fail either way. An
        // asymmetric (2, 3) channel can: its own header must pass while the
        // swapped and the symmetric lookalikes must not.
        val asymmetric = EpsonProtocol.D4ChannelState(psid = 2, ssid = 3, mtu = 64, txCredits = 0)
        assertTrue(
            protocol.isPlausibleD4Header(
                byteArrayOf(0x02, 0x03, 0x00, 0x76, 0x00, 0x01),
                asymmetric,
            ),
        )
        assertFalse(
            protocol.isPlausibleD4Header(
                byteArrayOf(0x03, 0x02, 0x00, 0x76, 0x00, 0x01),
                asymmetric,
            ),
        )
        assertFalse(
            protocol.isPlausibleD4Header(
                byteArrayOf(0x02, 0x02, 0x00, 0x76, 0x00, 0x01),
                asymmetric,
            ),
        )
    }

    @Test
    fun `masked nibbles assemble only the owned half`() {
        // L3110 shape: Main owns the low nibble of the shared third byte,
        // Platen the high one. Bytes [0x34, 0x12, 0xAB]: Main reads
        // 0x34 | 0x12<<8 | (0xAB&0x0F)<<16, Platen the 0xA0 half instead.
        val main = protocol.parseWasteCounter(
            rawBytes = byteArrayOf(0x34, 0x12, 0xAB.toByte()),
            max = 6346,
            name = "Main pad",
            masks = listOf(0xFF, 0xFF, 0x0F),
        )
        assertEquals(0x34 or (0x12 shl 8) or (0x0B shl 16), main.rawValue)
        val platen = protocol.parseWasteCounter(
            rawBytes = byteArrayOf(0x34, 0x12, 0xAB.toByte()),
            max = 3416,
            name = "Platen pad",
            masks = listOf(0xFF, 0xFF, 0xF0),
        )
        assertEquals(0x34 or (0x12 shl 8) or (0xA0 shl 16), platen.rawValue)
    }
}
