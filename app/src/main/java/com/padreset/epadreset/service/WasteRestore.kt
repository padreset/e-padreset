package com.padreset.epadreset.service

/**
 * The restore path's rules, with the transport left outside as a function.
 *
 * Split out of [PrinterService] for the same reason [WasteWriter] is: the plan
 * and the verification are the whole of it, and neither can be watched from a
 * desk. With the read reduced to a lambda they can be driven by a scripted fake,
 * which is the only way to see them without a printer on the end of a cable.
 */
internal object WasteRestore {

    /**
     * Widens a record's bytes into the plan shape the write path takes.
     *
     * A backup is `Map<Int, Byte>` and a write plan is `Map<Int, Int>`, because
     * that is what the protocol command carries. The widening is the same
     * `and 0xFF` the rest of the write path uses, so a byte of `0xFF` does not
     * come back as `-1` and land on the wire as something else.
     */
    fun planFrom(backup: Map<Int, Byte>): Map<Int, Int> =
        backup.entries.associate { (address, value) -> address to (value.toInt() and 0xFF) }

    /** A touched multibyte/shared group must be restored in full, never as a guessed partial value. */
    fun applicable(spec: com.padreset.epadreset.data.PrinterSpec, backup: Map<Int, Byte>): Map<Int, Byte> {
        val known = spec.wasteCounters.flatMap { it.addresses }.toSet() + spec.resetMap.keys
        val values = backup.filterKeys { it in known }
        if (values.isEmpty()) {
            throw PrinterService.WastePlanException(PrinterService.WastePlanException.Reason.RESTORE_NO_APPLICABLE_ADDRESSES)
        }
        if (spec.wasteCounters.any { counter ->
                counter.addresses.any { it in values } && !values.keys.containsAll(counter.addresses)
            }) {
            throw PrinterService.WastePlanException(PrinterService.WastePlanException.Reason.RESTORE_INCOMPLETE_COUNTER)
        }
        return values
    }

    /**
     * Checks every address in [plan] and returns the ones that did not take.
     * The reader can reuse bytes captured by the post-write status read.
     *
     * Equality, not a percentage tolerance: both reset and restore know the exact
     * bytes they sent. This also works without a ceiling, with nonzero reset
     * baselines and with counter groups too wide to pack losslessly into an Int.
     *
     * This is also what catches a write the printer accepted but did not apply:
     * [WasteWriter] lets an unrecognised reply through on purpose, so for those
     * writes the read-back is the only evidence there is.
     */
    fun verify(plan: Map<Int, Int>, readByte: (Int) -> Byte): Map<Int, Byte> =
        plan.toSortedMap()
            .mapNotNull { (address, value) ->
                val actual = readByte(address)
                if ((actual.toInt() and 0xFF) == (value and 0xFF)) {
                    null
                } else {
                    address to actual
                }
            }
            .toMap()
}
