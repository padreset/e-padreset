package com.padreset.epadreset.service

import com.padreset.epadreset.data.PrinterSpec
import com.padreset.epadreset.protocol.EpsonProtocol
import com.padreset.epadreset.service.PrinterService.WasteByteMismatch
import com.padreset.epadreset.service.PrinterService.WasteWriteException
import com.padreset.epadreset.service.PrinterService.WasteWriteException.Reason
import kotlinx.coroutines.CancellationException

/**
 * The rules the write path follows, with the transport left outside as two
 * functions.
 *
 * Split out of [PrinterService] because these rules are the whole of the write
 * path's safety and none of them can be reached from a desk: what has to happen
 * before the first write, what happens when a read fails, and what happens when
 * the printer refuses one. With the transport as a parameter they can be driven
 * by a scripted fake instead, which is the only way to watch them without a
 * printer on the end of a cable.
 *
 * The order is the point. The backup covers every address in the plan and is
 * taken before the first write; a backup that cannot be finished aborts the run,
 * because a partial one reads as a way back and is not one.
 */
internal class WasteWriter(
    private val readByte: (Int) -> Byte,
    private val writeByte: (Int, Int) -> EpsonProtocol.WriteReply,
    private val log: (String) -> Unit,
) {
    /**
     * Applies [plan], address to value, and returns what those addresses held
     * beforehand.
     *
     * @throws PrinterService.WasteWriteException before writing anything if the
     *   backup cannot be completed, or at the address the printer refuses.
     */
    fun write(
        plan: Map<Int, Int>,
        onBackup: (Map<Int, Byte>) -> Unit = {},
    ): Map<Int, Byte> {
        require(plan.isNotEmpty()) { "Nothing to write" }
        val expected = plan.toSortedMap().mapValues { (_, value) -> value and 0xFF }
        val backup = readBackup(expected.keys)
        // Publish the complete backup before the first write, not after a status
        // read that might fail. The caller can persist it even if USB disappears.
        onBackup(backup)

        var written = 0
        for ((address, value) in expected) {
            val label = "0x${"%04X".format(address)} = 0x${"%02X".format(value)}"
            val reply = try {
                writeByte(address, value)
            } catch (t: Exception) {
                if (t is CancellationException) throw t
                throw WasteWriteException(
                    reason = Reason.WRITE_FAILED,
                    address = address,
                    writtenBefore = written,
                    backup = backup,
                    cause = t,
                )
            }
            when (reply) {
                EpsonProtocol.WriteReply.Applied -> log("Write $label applied")

                EpsonProtocol.WriteReply.Rejected -> {
                    log("Write $label refused after $written address(es); stopping here")
                    throw PrinterService.WasteWriteException(
                        reason = PrinterService.WasteWriteException.Reason.REJECTED,
                        address = address,
                        writtenBefore = written,
                        backup = backup,
                    )
                }

                // Not a failure. The reply shape comes from a sibling
                // implementation that talks over a different transport, so an
                // answer this app does not recognise must not stop a write that
                // may well have worked.
                EpsonProtocol.WriteReply.Unknown -> log(
                    "Write $label: reply not recognised, continuing",
                )
            }
            written++
        }

        log("Write complete: $written addresses")
        return backup
    }

    companion object {
        /** The same exact plan is backed up, sent and verified; never rebuild it from a readout. */
        fun planFor(
            spec: PrinterSpec,
            selectedCounterNames: Set<String>,
            targetPercentage: Int,
        ): Map<Int, Int> {
            require(targetPercentage in 0..100) { "Target percentage must be between 0 and 100" }
            val selected = spec.wasteCounters.filter { it.name in selectedCounterNames }
            require(selected.isNotEmpty()) { "No waste counter selected" }
            if (targetPercentage == 0) {
                // The model's own reset values — usually but not always zero
                // (195 of 299 reset blocks publish nonzero baselines). A partial
                // selection used to fall through to the percentage maths below
                // and write 0x00, contradicting the documented promise and the
                // rest of the ecosystem. Intersect instead: only the selected
                // counters' addresses, at the model's values. A full selection
                // keeps the whole map, including auxiliary reset addresses that
                // live outside every counter's query range.
                require(spec.resetMap.isNotEmpty()) { "This model publishes no reset values" }
                if (selected.size == spec.wasteCounters.size) return spec.resetMap.toSortedMap()
                val selectedAddresses = selected.flatMap { it.addresses }.toSet()
                val plan = spec.resetMap.filterKeys { it in selectedAddresses }.toSortedMap()
                require(plan.isNotEmpty()) { "Selected counters publish no reset values" }
                return plan
            }
            require(selected.all { it.max > 0 && it.addresses.size <= 4 }) {
                "Percentage writes require a published ceiling and counters of at most four bytes"
            }
            return buildMap {
                for (counter in selected) {
                    val target = ((counter.max.toLong() * targetPercentage) / 100L).toInt()
                    counter.addresses.forEachIndexed { position, address ->
                        put(address, (target ushr (position * 8)) and 0xFF)
                    }
                }
            }.toSortedMap()
        }
    }

    data class VerifiedWrite<T>(val value: T, val backup: Map<Int, Byte>)

    /**
     * Verifies the exact plan, never a percentage or a packed Int. Counter groups
     * may exceed four bytes, and resets also write addresses outside those groups.
     *
     * [readBack] builds the normal status with the supplied reader. Its post-write
     * bytes are cached by address; verification reuses those and reads every plan
     * address not covered by status. No diagnostic retry can erase a mismatch.
     */
    fun <T> writeAndVerify(
        plan: Map<Int, Int>,
        onBackup: (Map<Int, Byte>) -> Unit = {},
        readBack: ((Int) -> Byte) -> T,
    ): VerifiedWrite<T> {
        val expected = plan.toSortedMap().mapValues { (_, value) -> value and 0xFF }
        val backup = write(expected, onBackup)
        val actual = linkedMapOf<Int, Byte>()
        val readCached: (Int) -> Byte = { address ->
            actual.getOrPut(address) {
                try {
                    readByte(address)
                } catch (t: Exception) {
                    if (t is CancellationException) throw t
                    throw WasteWriteException(
                        reason = Reason.READ_BACK_FAILED,
                        address = address,
                        writtenBefore = expected.size,
                        backup = backup,
                        cause = t,
                    )
                }
            }
        }
        val value = try {
            readBack(readCached)
        } catch (t: Exception) {
            if (t is CancellationException || t is WasteWriteException) throw t
            throw WasteWriteException(
                reason = Reason.READ_BACK_FAILED,
                writtenBefore = expected.size,
                backup = backup,
                cause = t,
            )
        }
        val mismatches = WasteRestore.verify(expected, readCached).mapValues { (address, byte) ->
            WasteByteMismatch(expected = expected.getValue(address), actual = byte.toInt() and 0xFF)
        }
        if (mismatches.isNotEmpty()) {
            mismatches.forEach { (address, mismatch) ->
                log("EEPROM mismatch at 0x%04X: expected 0x%02X, read 0x%02X".format(
                    address, mismatch.expected, mismatch.actual,
                ))
            }
            throw WasteWriteException(
                reason = Reason.NOT_APPLIED,
                writtenBefore = expected.size,
                backup = backup,
                mismatches = mismatches,
            )
        }
        log("Verified ${expected.size} EEPROM bytes against the write plan")
        return VerifiedWrite(value, backup)
    }

    private fun readBackup(addresses: Set<Int>): Map<Int, Byte> {
        val backup = linkedMapOf<Int, Byte>()
        for (address in addresses.sorted()) {
            val value = try {
                readByte(address)
            } catch (t: Exception) {
                if (t is CancellationException) throw t
                // No partial backup travels with the failure: nothing has been
                // written, so there is nothing to put back, and a half-filled
                // map shown to the user would say otherwise.
                log("Backup failed at 0x${"%04X".format(address)}; nothing written")
                throw PrinterService.WasteWriteException(
                    reason = PrinterService.WasteWriteException.Reason.BACKUP_FAILED,
                    address = address,
                    cause = t,
                )
            }
            backup[address] = value
            log("Backup 0x${"%04X".format(address)} = 0x${"%02X".format(value.toInt() and 0xFF)}")
        }
        log("Backup taken: ${backup.size} bytes, before any write")
        return backup
    }
}
