package com.padreset.epadreset.ui

import com.padreset.epadreset.data.BackupCodec
import com.padreset.epadreset.data.BackupOrigin
import com.padreset.epadreset.data.BackupStore
import com.padreset.epadreset.data.WasteBackupRecord
import com.padreset.epadreset.service.BackupConfirmationRequired
import com.padreset.epadreset.service.TemporaryBackupApproval

/** Only durable entries belong in history; memory-only backups need a one-use approval. */
internal class WriteBackupController(
    private val store: BackupStore,
    private val publish: ((MainUiState) -> MainUiState) -> Unit,
    private val log: (String) -> Unit,
) {
    private val approval = TemporaryBackupApproval()
    private var retry: (() -> Unit)? = null

    fun capture(
        source: Source,
        bytes: Map<Int, Byte>,
        retryWrite: () -> Unit,
    ) {
        val result = store.save(source.model, bytes, source.origin, source.unit)
        val text = BackupCodec.encodeRecord(WasteBackupRecord(
            timestamp = System.currentTimeMillis(), model = source.model,
            values = bytes, origin = source.origin, unitId = source.unit,
        ))
        publish { it.copy(backupHistory = result.history, wasteBackup = text, backupIsTemporary = !result.persisted) }
        if (!result.persisted) {
            if (!approval.consume(source.session, source.origin, source.plan, bytes)) {
                approval.request(source.session, source.origin, source.plan, bytes)
                retry = retryWrite
                publish { it.copy(temporaryBackupConfirmation = true) }
                throw BackupConfirmationRequired()
            }
            log("User explicitly approved this plan with a memory-only backup")
        } else {
            approval.clear()
            log(if (result.deduplicated) "Backup already persisted for ${source.model}" else "Backup persisted for ${source.model}")
        }
    }

    fun answer(continueWrite: Boolean) {
        val operation = retry
        retry = null
        publish { it.copy(temporaryBackupConfirmation = false) }
        if (continueWrite && approval.approve()) operation?.invoke() else approval.clear()
    }

    fun invalidate() {
        approval.clear()
        retry = null
        publish { it.copy(temporaryBackupConfirmation = false) }
    }

    data class Source(
        val session: Any,
        val model: String,
        val unit: String?,
        val origin: BackupOrigin,
        val plan: String,
    )
}
