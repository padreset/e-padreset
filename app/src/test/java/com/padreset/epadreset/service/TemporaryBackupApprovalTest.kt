package com.padreset.epadreset.service

import com.padreset.epadreset.data.BackupOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryBackupApprovalTest {
    @Test
    fun `approval is required and consumed once`() {
        val gate = TemporaryBackupApproval()
        val session = Any()
        val bytes = mapOf(24 to 1.toByte())
        gate.request(session, BackupOrigin.RESET, "reset", bytes)
        assertFalse(gate.consume(session, BackupOrigin.RESET, "reset", bytes))
        assertTrue(gate.approve())
        assertTrue(gate.consume(session, BackupOrigin.RESET, "reset", bytes))
        assertFalse(gate.consume(session, BackupOrigin.RESET, "reset", bytes))
    }

    @Test
    fun `changed session plan origin or prestate cannot reuse approval`() {
        val session = Any()
        val bytes = mapOf(24 to 1.toByte())
        repeat(4) { scenario ->
            val gate = TemporaryBackupApproval()
            gate.request(session, BackupOrigin.RESET, "reset", bytes)
            assertTrue(gate.approve())
            assertFalse(gate.consume(
                if (scenario == 0) Any() else session,
                if (scenario == 1) BackupOrigin.RESTORE else BackupOrigin.RESET,
                if (scenario == 2) "other" else "reset",
                if (scenario == 3) mapOf(24 to 2.toByte()) else bytes,
            ))
        }
    }

    @Test
    fun `cancel clears both pending and approved states`() {
        val gate = TemporaryBackupApproval()
        val session = Any()
        val bytes = mapOf(24 to 1.toByte())
        gate.request(session, BackupOrigin.RESET, "reset", bytes)
        gate.clear()
        assertFalse(gate.approve())
        gate.request(session, BackupOrigin.RESET, "reset", bytes)
        gate.approve()
        gate.clear()
        assertFalse(gate.consume(session, BackupOrigin.RESET, "reset", bytes))
    }
}
