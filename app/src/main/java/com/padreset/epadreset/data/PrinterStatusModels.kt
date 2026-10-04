package com.padreset.epadreset.data

data class InkLevel(
    val color: String,
    val percentage: Int,
)

data class WasteCounterStatus(
    val name: String,
    val rawValue: Int,
    val maxValue: Int,
    /**
     * The counter's bytes as read, in address order, e.g. "00 00 00 5E 5E".
     *
     * Counter groups wider than four bytes do not fit an `Int` (and ten-byte
     * groups do not fit a `Long` either), so [rawValue] saturates at
     * [Int.MAX_VALUE] for those and this is the exact reading. The UI shows it
     * whenever the ceiling is unknown; blank only for hand-built instances in
     * tests, where the UI falls back to [rawValue].
     */
    val rawHex: String = "",
) {
    /**
     * False when the model's counter ceiling is not published.
     *
     * That is the case for every model coming from reinkpy rather than from
     * devices.xml: reinkpy gives the addresses and the reset values but not the
     * ceiling, and without a ceiling there is no percentage to compute.
     *
     * The distinction exists so the UI can show the raw byte value instead of
     * falling back to 0%. A zero would read as an empty pad, which is the same
     * false all-clear the waste panel already refuses to give for a printer
     * whose maintenance box may well be full.
     */
    val scaleKnown: Boolean get() = maxValue > 0

    val percentage: Double
        get() = if (!scaleKnown) 0.0 else ((rawValue.toDouble() * 100.0) / maxValue.toDouble()).coerceIn(0.0, 999.0)
}

data class PrinterStatusSnapshot(
    val modelName: String,
    val serialNumber: String,
    /**
     * Raw status code from the printer.
     *
     * [state] carries the same information as English text, which is what the
     * diagnostic log uses. The UI resolves [stateCode] to a translatable label
     * instead, so the two never drift: both come from the same code.
     */
    val stateCode: Int,
    /** Raw error code from the printer. [ERROR_NONE] means no error. */
    val errorCode: Int,
    val state: String,
    val error: String,
    val inkLevels: List<InkLevel>,
    val wasteCounters: List<WasteCounterStatus>,
) {
    companion object {
        /** The printer reports this when there is no error. */
        const val ERROR_NONE = -1
    }

    /**
     * Whether the printer is in a live machine state worth re-reading.
     *
     * Printing, busy, waiting and cleaning are transient: a cleaning started
     * from the app leaves the status page saying "Cleaning" with no way to
     * learn it finished except a manual refresh. Idle, pause and any error are
     * terminal for polling purposes — they either persist or need the user.
     * An error code alongside a live state (e.g. jammed mid-print) also stops:
     * re-reading will not clear it. Pure so the matrix is unit-testable.
     */
    val needsPolling: Boolean
        get() = stateCode in setOf(0x01, 0x02, 0x03, 0x07) && errorCode == ERROR_NONE
}
