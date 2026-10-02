package com.padreset.epadreset.service

import com.padreset.epadreset.protocol.EpsonProtocol

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
    fun write(plan: Map<Int, Int>): Map<Int, Byte> {
        require(plan.isNotEmpty()) { "Nothing to write" }

        val backup = readBackup(plan.keys)

        var written = 0
        for ((address, value) in plan.toSortedMap()) {
            val label = "0x${"%04X".format(address)} = 0x${"%02X".format(value and 0xFF)}"
            when (writeByte(address, value)) {
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

    private fun readBackup(addresses: Set<Int>): Map<Int, Byte> {
        val backup = linkedMapOf<Int, Byte>()
        for (address in addresses.sorted()) {
            val value = try {
                readByte(address)
            } catch (t: Throwable) {
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
