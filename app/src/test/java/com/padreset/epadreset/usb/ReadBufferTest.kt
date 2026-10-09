package com.padreset.epadreset.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadBufferTest {
    @Test
    fun wrapsAroundTheEndOfStorageWithoutLosingOrder() {
        val buffer = UsbTransport.ReadBuffer()
        // Fill most of the initial 1024-byte ring, drain it, then write across the seam.
        buffer.append(ByteArray(1000) { (it % 251).toByte() })
        buffer.take(900)
        val tail = ByteArray(200) { (it + 7).toByte() }
        buffer.append(tail)
        assertEquals(300, buffer.size)
        val expected = ByteArray(100) { (it + 900 % 251).toByte() } + tail
        assertArrayEquals(expected, buffer.take(300))
        assertEquals(0, buffer.size)
    }

    @Test
    fun growsPastInitialCapacityKeepingContents() {
        val buffer = UsbTransport.ReadBuffer()
        buffer.append(ByteArray(600) { 1 })
        buffer.take(300)
        val big = ByteArray(5000) { (it and 0x7F).toByte() }
        buffer.append(big)
        assertEquals(5300, buffer.size)
        val all = buffer.take(5300)
        assertArrayEquals(ByteArray(300) { 1 } + big, all)
    }

    @Test
    fun takeRejectsNegativeAndOverdrawAndLeavesStateUnchanged() {
        val buffer = UsbTransport.ReadBuffer()
        buffer.append(byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { buffer.take(-1) }
        assertThrows(IllegalArgumentException::class.java) { buffer.take(4) }
        assertEquals(3, buffer.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), buffer.take(3))
    }

    @Test
    fun clearResetsForReuse() {
        val buffer = UsbTransport.ReadBuffer()
        buffer.append(ByteArray(2000) { 9 })
        buffer.clear()
        assertEquals(0, buffer.size)
        buffer.append(byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), buffer.take(2))
    }
}
