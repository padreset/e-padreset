package com.padreset.epadreset.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the session-teardown filter used by `MainViewModel.runJob`.
 *
 * The rule is deliberately narrow: only transport-shaped write failures are
 * ever silenced, and only when the session actually moved underneath the job.
 * Printer verdicts and non-write failures always surface — silencing those
 * would hide information the user needs, including a job's own connect error
 * after it tore the old session down itself.
 */
class SessionGuardTest {

    private fun writeFailure(reason: PrinterService.WasteWriteException.Reason) =
        PrinterService.WasteWriteException(reason = reason)

    @Test
    fun `unchanged session never suppresses anything`() {
        PrinterService.WasteWriteException.Reason.values().forEach { reason ->
            assertFalse(SessionGuard.shouldSuppress(writeFailure(reason), 3, 3))
        }
        assertFalse(SessionGuard.shouldSuppress(RuntimeException("boom"), 3, 3))
    }

    @Test
    fun `only pre-write backup failure can be suppressed after teardown`() {
        listOf(
            PrinterService.WasteWriteException.Reason.BACKUP_FAILED,
            PrinterService.WasteWriteException.Reason.WRITE_FAILED,
            PrinterService.WasteWriteException.Reason.READ_BACK_FAILED,
        ).forEach { reason ->
            assertEquals(reason == PrinterService.WasteWriteException.Reason.BACKUP_FAILED,
                SessionGuard.shouldSuppress(writeFailure(reason), 3, 4))
        }
    }

    @Test
    fun `printer verdicts always surface even when the session moved`() {
        listOf(
            PrinterService.WasteWriteException.Reason.REJECTED,
            PrinterService.WasteWriteException.Reason.NOT_APPLIED,
        ).forEach { reason ->
            assertFalse(SessionGuard.shouldSuppress(writeFailure(reason), 3, 4))
        }
    }

    @Test
    fun `non-write failures always surface even when the session moved`() {
        assertFalse(SessionGuard.shouldSuppress(RuntimeException("boom"), 3, 4))
        assertFalse(SessionGuard.shouldSuppress(IllegalStateException("torn down"), 0, 2))
    }
}
