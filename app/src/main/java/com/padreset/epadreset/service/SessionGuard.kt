package com.padreset.epadreset.service

/**
 * Decides whether a job failure is transport fallout from a session that was
 * closed underneath it, rather than a verdict worth showing.
 *
 * Pure and JVM-testable: no USB, no Android, no coroutine scope. The epoch
 * counting lives in the ViewModel; this object only judges one failure
 * against two counters. See `SessionGuardTest` and `MainViewModel.runJob`.
 *
 * Only a failed pre-write backup read is silenced after teardown. A write
 * failure or read-back failure always surfaces: changes may already have happened. `REJECTED` and `NOT_APPLIED` are printer verdicts: the printer
 * answered and said no, or the bytes read back differ from the plan. Those
 * always surface, even when the session died later, because silencing them
 * would hide information the user needs (and the pre-write backup stays
 * valid either way). Anything that is not a [PrinterService.WasteWriteException]
 * at all — connect errors, validation errors, unexpected faults — is likewise
 * never silenced here: connect failures are what [sessionEpoch]-bumping jobs
 * themselves report, and suppressing them would eat a job's own error.
 */
internal object SessionGuard {
    fun shouldSuppress(failure: Throwable, epochAtStart: Int, currentEpoch: Int): Boolean {
        if (currentEpoch == epochAtStart) return false
        val reason = (failure as? PrinterService.WasteWriteException)?.reason ?: return false
        // Only a failed pre-write read can be dismissed as teardown. Once a write
        // was attempted, transport loss means possibly partial changes, not silence.
        return reason == PrinterService.WasteWriteException.Reason.BACKUP_FAILED
    }
}
