package com.padreset.epadreset.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.padreset.epadreset.R
import com.padreset.epadreset.data.PrinterStatusSnapshot
import com.padreset.epadreset.service.PrinterService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val connectedDeviceName: String? = null,
    val status: PrinterStatusSnapshot? = null,
    val cleaningOptions: List<String> = emptyList(),
    val supportsGenericMaintenanceJobs: Boolean = false,
    val isBusy: Boolean = false,
    /**
     * Already localized. Shown in the error callout, so anything written here
     * must go through [MainViewModel.str].
     */
    val error: String? = null,
    val logs: List<String> = emptyList(),
    val actionSuccessMessage: String? = null,
)

/**
 * A failure that already carries a localized message destined for the UI.
 *
 * Anything else thrown inside [MainViewModel.runJob] is treated as a technical
 * fault: its exception message goes to the diagnostic log only, and the callout
 * shows the generic "operation failed" line instead of untranslated English.
 */
private class UserFacingException(val uiMessage: String) : Exception(uiMessage)

/**
 * Tells [MainViewModel.runJob] which localized line the callout should show for
 * an otherwise technical failure.
 */
private class UnhandledJobException(val uiMessage: String, cause: Throwable) : Exception(cause)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val printerService = PrinterService(application, ::appendLog)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var connection: PrinterService.ConnectionState? = null

    fun discoverAndConnect() {
        viewModelScope.launch {
            runJob("Scanning for Epson USB printers") {
                val device = printerService.detectPrinters().firstOrNull()
                    ?: throw UserFacingException(str(R.string.error_no_printer_detected))
                connect(device)
            }
        }
    }

    fun connect(device: UsbDevice) {
        viewModelScope.launch {
            runJob("Connecting to ${device.productName ?: device.deviceName}") {
                connection?.let(printerService::close)
                connection = runCatching { printerService.connectPrinter(device) }
                    .getOrElse { throw UnhandledJobException(str(R.string.error_connect_failed), it) }
                    .also { appendLog("Connected to ${it.deviceId}") }
                refreshStatus()
            }
        }
    }

    fun refreshStatus() {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected))
            return
        }
        viewModelScope.launch {
            runJob("Reading printer status") {
                val status = printerService.readPrinterStatus(current)
                _uiState.value = _uiState.value.copy(
                    connectedDeviceName = current.deviceId,
                    status = status,
                    cleaningOptions = current.spec.cleaningOptions.map { it.name },
                    supportsGenericMaintenanceJobs = true,
                    error = null,
                )
                appendLog("Status read: ${status.state}, ${status.error}")
            }
        }
    }

    fun resetWasteCounters(selectedCounterNames: Set<String>, targetPercentage: Int) {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected))
            return
        }
        viewModelScope.launch {
            runJob("Resetting waste counters") {
                val counters = printerService.writeWasteCounters(current, selectedCounterNames, targetPercentage)
                appendLog("Waste counters written: ${counters.joinToString { "${it.name}=${it.percentage}%" }}")
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = str(R.string.msg_counters_updated),
                )
                refreshStatus()
            }
        }
    }

    fun runHeadCleaning(cleaningName: String) {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected))
            return
        }
        viewModelScope.launch {
            runJob("Starting head cleaning") {
                printerService.runHeadCleaning(current, cleaningName)
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = str(R.string.msg_cleaning_sent_named, cleaningName),
                )
            }
        }
    }

    fun runGenericHeadCleaning(
        groupIndex: Int,
        powerClean: Boolean,
        alternativeMode: Boolean,
    ) {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected))
            return
        }
        viewModelScope.launch {
            runJob("Starting head cleaning") {
                printerService.runPrintJobHeadCleaning(current, groupIndex, powerClean, alternativeMode)
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = str(R.string.msg_cleaning_sent),
                )
            }
        }
    }

    fun printNozzleCheck(type: Int) {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected))
            return
        }
        viewModelScope.launch {
            runJob("Printing nozzle check") {
                printerService.printNozzleCheck(current, type)
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = str(R.string.msg_nozzle_check_sent),
                )
            }
        }
    }

    fun dismissActionSuccess() {
        _uiState.value = _uiState.value.copy(actionSuccessMessage = null)
    }

    override fun onCleared() {
        connection?.let(printerService::close)
        super.onCleared()
    }

    /** Resolves a user-facing string. The log stays in English on purpose. */
    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private suspend fun runJob(message: String, block: suspend () -> Unit) {
        _uiState.value = _uiState.value.copy(isBusy = true, error = null)
        appendLog(message)
        runCatching { block() }
            .onFailure { failure ->
                // The log always keeps the raw, English diagnostic detail; the
                // callout only ever shows a string resource.
                val logged = failure.message ?: failure.javaClass.simpleName
                appendLog("Error: $logged")
                val uiMessage = when (failure) {
                    is UserFacingException -> failure.uiMessage
                    is UnhandledJobException -> failure.uiMessage
                    else -> str(R.string.callout_operation_failed)
                }
                _uiState.value = _uiState.value.copy(error = uiMessage)
            }
        _uiState.value = _uiState.value.copy(isBusy = false)
    }

    private fun appendLog(line: String) {
        _uiState.value = _uiState.value.copy(logs = (_uiState.value.logs + line).takeLast(200))
    }

    private fun appendError(message: String) {
        appendLog("Error: $message")
        _uiState.value = _uiState.value.copy(error = message)
    }
}
