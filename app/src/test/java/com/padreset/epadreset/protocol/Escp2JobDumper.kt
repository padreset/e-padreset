package com.padreset.epadreset.protocol

import java.io.File

/**
 * Writes every remote-mode maintenance job variant to a directory, one `.bin`
 * each, for the escapy cross-check (`scripts/validate-escp2-with-escapy.py`).
 *
 * Invoked by the `dumpEscp2Jobs` Gradle task, never by the app: the bytes must
 * come from these builders rather than from pinned copies, or the check would
 * pass while the app drifted. Alternative-mode cleanings are raw service
 * bytes, not remote-mode traffic, so they are deliberately not dumped —
 * escapy has no grammar for them and a vacuous pass is worse than a stated gap.
 */
object Escp2JobDumper {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: error("output directory required")).apply { mkdirs() }
        out.resolve("nozzle-check.bin").writeBytes(Escp2MaintenanceProtocol.buildNozzleCheckJob())
        for (group in 0..5) {
            for (power in listOf(false, true)) {
                val variant = if (power) "power" else "normal"
                out.resolve("cleaning-group$group-$variant.bin").writeBytes(
                    Escp2MaintenanceProtocol.buildHeadCleaningJob(groupIndex = group, powerClean = power),
                )
            }
        }
    }
}
