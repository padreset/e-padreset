package com.padreset.epadreset.usb

import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Two permission waits must not share a broadcast action.
 *
 * A result is an ordinary broadcast. Receivers registered for the same
 * action all run, so a shared action would resume every waiter with
 * whichever grant arrived first.
 */
class UsbPermissionActionTest {

    @Test
    fun `each request gets its own action`() {
        val first = usbPermissionAction("com.padreset.epadreset", 1)
        val second = usbPermissionAction("com.padreset.epadreset", 2)
        assertNotEquals(first, second)
        assertNotEquals("com.padreset.epadreset.USB_PERMISSION", first)
    }
}
