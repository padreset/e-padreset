package com.padreset.epadreset.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which printer states keep the silent status poll alive.
 *
 * A cleaning started from the app must not leave "Cleaning" on screen
 * forever; idle, pause and errors must not poll pointlessly.
 */
class StatusPollingTest {

    private fun snapshot(stateCode: Int, errorCode: Int = PrinterStatusSnapshot.ERROR_NONE) =
        PrinterStatusSnapshot(
            modelName = "Test",
            serialNumber = "",
            stateCode = stateCode,
            errorCode = errorCode,
            state = "",
            error = "",
            inkLevels = emptyList(),
            wasteCounters = emptyList(),
        )

    @Test
    fun `live machine states keep polling`() {
        // Self printing, busy, waiting, cleaning.
        listOf(0x01, 0x02, 0x03, 0x07).forEach { code ->
            assertTrue("state 0x%02X polls".format(code), snapshot(code).needsPolling)
        }
    }

    @Test
    fun `terminal states stop polling`() {
        // Idle, pause, and unknown codes (surfaced as Error).
        listOf(0x04, 0x05, 0x00, 0x06, 0x08, 0xFF).forEach { code ->
            assertFalse("state 0x%02X stops".format(code), snapshot(code).needsPolling)
        }
    }

    @Test
    fun `any error stops polling even in a live state`() {
        listOf(0x01, 0x02, 0x03, 0x07).forEach { code ->
            assertFalse(snapshot(code, errorCode = 0x10).needsPolling)
            assertFalse(snapshot(code, errorCode = 0x00).needsPolling)
        }
    }
}
