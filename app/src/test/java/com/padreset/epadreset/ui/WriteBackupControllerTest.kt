package com.padreset.epadreset.ui

import com.padreset.epadreset.data.BackupOrigin
import com.padreset.epadreset.data.BackupStorage
import com.padreset.epadreset.data.BackupStore
import com.padreset.epadreset.service.BackupConfirmationRequired
import com.padreset.epadreset.service.WasteWriter
import com.padreset.epadreset.protocol.EpsonProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteBackupControllerTest {
    @Test
    fun `storage failure sends no write until explicit one use confirmation`() {
        val storage = object : BackupStorage {
            override fun read(): String? = null
            override fun write(blob: String) = error("disk full")
        }
        var state = MainUiState()
        val controller = WriteBackupController(BackupStore(storage), { state = it(state) }, {})
        val memory = mutableMapOf(24 to 9.toByte())
        var writes = 0
        val source = WriteBackupController.Source(Any(), "TEST", null, BackupOrigin.RESET, "24=0")
        val writer = WasteWriter(
            readByte = { memory.getValue(it) },
            writeByte = { address, value -> writes++; memory[address] = value.toByte(); EpsonProtocol.WriteReply.Applied },
            log = {},
        )
        var retried = false
        fun execute() = writer.write(mapOf(24 to 0)) { controller.capture(source, it) { retried = true } }
        assertTrue(runCatching { execute() }.exceptionOrNull() is BackupConfirmationRequired)
        assertEquals(0, writes)
        assertTrue(state.backupHistory.isEmpty())
        assertTrue(state.backupIsTemporary)
        assertTrue(state.temporaryBackupConfirmation)
        assertTrue(state.wasteBackup?.contains("0x0018 = 0x09") == true)
        controller.answer(true)
        assertTrue(retried)
        assertFalse(state.temporaryBackupConfirmation)
        execute()
        assertEquals(1, writes)
        assertTrue(runCatching { execute() }.exceptionOrNull() is BackupConfirmationRequired)
        assertEquals(1, writes)
    }

    @Test
    fun `cancellation or invalidation never retries a pending write`() {
        val storage = object : BackupStorage {
            override fun read(): String? = null
            override fun write(blob: String) = error("disk full")
        }
        var state = MainUiState()
        val controller = WriteBackupController(BackupStore(storage), { state = it(state) }, {})
        val source = WriteBackupController.Source(Any(), "TEST", null, BackupOrigin.RESTORE, "24=9")
        var retried = false
        runCatching { controller.capture(source, mapOf(24 to 1.toByte())) { retried = true } }
        controller.answer(false)
        assertFalse(retried)
        runCatching { controller.capture(source, mapOf(24 to 1.toByte())) { retried = true } }
        controller.invalidate()
        controller.answer(true)
        assertFalse(retried)
    }
}
