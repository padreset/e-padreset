package com.padreset.epadreset.service

/**
 * A USB attach delivered to an activity that already holds a session.
 *
 * `deviceName` is the kernel path and changes on a real replug, so a match
 * plus a live bus is a re-delivery, not a new printer. A match that is no
 * longer attached must reconnect: the old handle reaches nothing.
 *
 * Pure and JVM-testable. See `RepeatAttachTest` and `MainViewModel.connect`.
 */
internal object RepeatAttach {
    fun shouldIgnore(
        connectedDeviceName: String?,
        incomingDeviceName: String,
        stillAttached: Boolean,
    ): Boolean =
        stillAttached && connectedDeviceName != null && connectedDeviceName == incomingDeviceName
}
