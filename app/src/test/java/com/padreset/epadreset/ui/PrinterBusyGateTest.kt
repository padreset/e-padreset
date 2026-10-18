package com.padreset.epadreset.ui

import com.padreset.epadreset.data.PrinterStatusSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the action gate to the printer's own live state.
 *
 * Maintenance and EEPROM actions must stay off while the printer reports
 * printing, busy, waiting or cleaning — even though no app job is running
 * ([MainUiState.isBusy] is false then) — and come back on their own once it
 * reports idle. Refresh is never gated; it is the way out.
 */
class PrinterBusyGateTest {

    private fun snapshot(stateCode: Int) = PrinterStatusSnapshot(
        modelName = "Test",
        serialNumber = "",
        stateCode = stateCode,
        errorCode = PrinterStatusSnapshot.ERROR_NONE,
        state = "",
        error = "",
        inkLevels = emptyList(),
        wasteCounters = emptyList(),
    )

    private fun stateWith(status: PrinterStatusSnapshot?) = MainUiState(status = status)

    @Test
    fun `live printer states report busy`() {
        // Self printing, busy, waiting, cleaning.
        listOf(0x01, 0x02, 0x03, 0x07).forEach { code ->
            assertTrue("state 0x%02X is busy".format(code), stateWith(snapshot(code)).printerBusy)
        }
    }

    @Test
    fun `settled printer and no printer report idle`() {
        listOf(0x04, 0x05, 0x00, 0xFF).forEach { code ->
            assertFalse("state 0x%02X is idle".format(code), stateWith(snapshot(code)).printerBusy)
        }
        assertFalse("no status is idle", stateWith(null).printerBusy)
    }

    @Test
    fun `busy gate is independent of the app job flag`() {
        // The exact window the gate exists for: the app-side job finished
        // (isBusy false) but the machine is still cleaning.
        val cleaning = stateWith(snapshot(0x07)).copy(isBusy = false)
        assertTrue(cleaning.printerBusy)
        assertFalse(cleaning.isBusy)
    }
}
