package com.padreset.epadreset.protocol

import com.padreset.epadreset.data.PrinterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
