package com.padreset.epadreset.ui

import com.padreset.epadreset.data.PrinterStatusSnapshot

/**
 * Refuses a new printer job while the last status read says the machine is
 * still working (printing, busy, waiting, cleaning).
 *
 * Called inside [MainViewModel.runJob] so the refusal lands in the error
 * callout like any other user-facing refusal. Refresh is deliberately not
 * gated: it is how the user learns the printer settled.
 *
 * Lives here rather than as a method so [MainViewModel] stays under the
 * large-class gate.
 */
internal fun requirePrinterIdle(status: PrinterStatusSnapshot?, busyMessage: String) {
    if (status?.needsPolling == true) {
        throw UserFacingException(
            busyMessage,
            "Refused: printer reports ${status.state}",
        )
    }
}
