package com.padreset.epadreset.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A USB attach delivered to an activity that already holds a session.
 *
 * `deviceName` is the kernel path and changes on a real replug, so a match
 * plus a live bus is a re-delivery, not a new printer. A match that is no
 * longer attached must reconnect.
 */
class RepeatAttachTest {

    @Test
    fun `a live session for the same device is not torn down`() {
        assertTrue(RepeatAttach.shouldIgnore("/dev/bus/usb/001/004", "/dev/bus/usb/001/004", stillAttached = true))
    }

    @Test
    fun `the same name on a dead bus reconnects`() {
        assertFalse(RepeatAttach.shouldIgnore("/dev/bus/usb/001/004", "/dev/bus/usb/001/004", stillAttached = false))
    }

    @Test
    fun `a different device always connects`() {
        assertFalse(RepeatAttach.shouldIgnore("/dev/bus/usb/001/004", "/dev/bus/usb/001/005", stillAttached = true))
    }

    @Test
    fun `no session is not a repeat`() {
        assertFalse(RepeatAttach.shouldIgnore(null, "/dev/bus/usb/001/004", stillAttached = true))
    }
}
