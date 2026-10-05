package com.padreset.epadreset.ui

import com.padreset.epadreset.R
import com.padreset.epadreset.data.EpsonPrinterCatalog
import com.padreset.epadreset.service.PrinterService

/**
 * The connection-failure callout.
 *
 * Lives outside [MainViewModel] because that class is at the size the
 * analysis allows, and this mapping does not need the ViewModel to exist.
 */
internal fun connectFailureText(
    failure: Throwable,
    text: (Int) -> String,
    named: (Int, String) -> String,
): String {
    val connect = failure as? PrinterService.ConnectException
        ?: return text(R.string.error_connect_failed)
    return when (connect.failure) {
        PrinterService.ConnectFailure.PERMISSION_DENIED ->
            text(R.string.error_connect_permission_denied)

        PrinterService.ConnectFailure.NO_INTERFACES ->
            text(R.string.error_connect_no_interfaces)

        PrinterService.ConnectFailure.UNSUPPORTED_MODEL -> {
            val model = connect.detail?.let(EpsonPrinterCatalog::modelName)
            if (model.isNullOrBlank()) {
                text(R.string.error_connect_unsupported_model)
            } else {
                named(R.string.error_connect_unsupported_model_named, model)
            }
        }

        // Never promise a reset of the box chip based on adding a catalog entry.
        PrinterService.ConnectFailure.UNSUPPORTED_MODEL_MAINTENANCE_BOX -> {
            val model = connect.detail?.let(EpsonPrinterCatalog::modelName)
            if (model.isNullOrBlank()) {
                text(R.string.error_connect_unsupported_model_box)
            } else {
                named(R.string.error_connect_unsupported_model_box_named, model)
            }
        }

        PrinterService.ConnectFailure.DOT4_NAK ->
            text(R.string.error_connect_dot4_nak)

        PrinterService.ConnectFailure.DOT4_NO_REPLY ->
            text(R.string.error_connect_dot4_no_reply)

        PrinterService.ConnectFailure.INTERFACE_BUSY ->
            text(R.string.error_connect_interface_busy)

        PrinterService.ConnectFailure.DEVICE_DETACHED ->
            text(R.string.error_connect_device_detached)

        PrinterService.ConnectFailure.UNKNOWN ->
            text(R.string.error_connect_failed)
    }
}
