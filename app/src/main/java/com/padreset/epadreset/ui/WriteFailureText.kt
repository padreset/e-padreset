package com.padreset.epadreset.ui

import com.padreset.epadreset.R
import com.padreset.epadreset.data.BackupOrigin
import com.padreset.epadreset.service.PrinterService

internal fun writeFailureText(failure: Throwable, origin: BackupOrigin?, context: android.content.Context): String {
    fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    return when (failure) {
        is PrinterService.WastePlanException -> text(when (failure.reason) {
            PrinterService.WastePlanException.Reason.NO_COUNTER_SELECTED -> R.string.error_reset_no_counter_selected
            PrinterService.WastePlanException.Reason.NO_RESET_VALUES -> R.string.error_reset_no_values
            PrinterService.WastePlanException.Reason.SHARED_BYTES -> R.string.error_reset_partial_shared
            PrinterService.WastePlanException.Reason.PERCENTAGE_UNSUPPORTED -> R.string.error_reset_percentage_unsupported
            PrinterService.WastePlanException.Reason.RESTORE_INCOMPLETE_COUNTER -> R.string.error_restore_incomplete_counter
            PrinterService.WastePlanException.Reason.RESTORE_NO_APPLICABLE_ADDRESSES -> R.string.error_restore_no_applicable_addresses
        })
        is PrinterService.WasteWriteException -> {
            val address = failure.address?.let { "0x%04X".format(it) }
            when (failure.reason) {
                PrinterService.WasteWriteException.Reason.BACKUP_FAILED ->
                    if (address == null) text(R.string.error_backup_failed) else text(R.string.error_backup_failed_at, address)
                PrinterService.WasteWriteException.Reason.REJECTED ->
                    if (address == null) text(R.string.error_write_rejected)
                    else text(R.string.error_write_rejected_at, address, failure.writtenBefore)
                PrinterService.WasteWriteException.Reason.WRITE_FAILED ->
                    text(R.string.error_write_failed, address.orEmpty(), failure.writtenBefore)
                PrinterService.WasteWriteException.Reason.READ_BACK_FAILED ->
                    if (address == null) text(R.string.error_read_back_failed)
                    else text(R.string.error_read_back_failed_at, address)
                PrinterService.WasteWriteException.Reason.NOT_APPLIED -> text(
                    if (origin == BackupOrigin.RESTORE) R.string.error_restore_not_applied else R.string.error_counters_not_applied,
                    failure.mismatches.keys.joinToString { "0x%04X".format(it) },
                )
            }
        }
        else -> text(R.string.callout_operation_failed)
    }
}
