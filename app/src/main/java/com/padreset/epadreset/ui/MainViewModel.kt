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
import kotlin.math.abs
import kotlin.math.roundToInt

data class MainUiState(
    val connectedDeviceName: String? = null,
    val status: PrinterStatusSnapshot? = null,
    val cleaningOptions: List<String> = emptyList(),
    val supportsGenericMaintenanceJobs: Boolean = false,
    /**
     * False when the connected printer keeps its waste ink count somewhere the
     * app cannot write to — a maintenance box chip rather than the printer's own
     * memory. The status page uses it to explain the situation instead of
     * drawing empty counters.
     */
    val supportsWasteReset: Boolean = false,
    /**
     * True when the connected model is known to use a maintenance box, so the
     * explanation can name the cause instead of listing the possibilities.
     */
    val usesMaintenanceBox: Boolean = false,
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
 *
 * [logMessage] is what reaches the diagnostic log, so the two can differ: the
 * callout stays translatable while the log keeps the raw detail.
 */
private class UnhandledJobException(
    val uiMessage: String,
    logMessage: String,
    cause: Throwable? = null,
) : Exception(logMessage, cause)

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
                    .getOrElse {
                        throw UnhandledJobException(
                            uiMessage = localizedConnectFailure(it),
                            logMessage = it.message ?: it.javaClass.simpleName,
                            cause = it,
                        )
                    }
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
                    supportsWasteReset = current.spec.supportsWasteReset,
                    usesMaintenanceBox = current.spec.usesMaintenanceBox,
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
                appendLog("Waste counters read back: ${counters.joinToString { "${it.name}=${it.percentage}%" }}")

                // The service already reads the counters back after writing. Until
                // now nobody looked at them, so a write the printer rejected was
                // followed by a success message and a log line showing the old
                // values — the evidence was returned and thrown away.
                //
                // The comparison is against the value that was asked for, so it
                // needs no knowledge of the printer's reply format. The tolerance
                // absorbs the rounding in value -> percentage: the target is
                // stored as max * pct / 100 and read back through the inverse.
                val notApplied = counters.filter { counter ->
                    counter.name in selectedCounterNames &&
                        abs(counter.percentage - targetPercentage) > 2.0
                }
                if (notApplied.isNotEmpty()) {
                    val actual = notApplied.joinToString { "${it.name}=${it.percentage.roundToInt()}%" }
                    throw UnhandledJobException(
                        uiMessage = str(R.string.error_counters_not_applied, actual),
                        logMessage = "Counters not applied: asked for $targetPercentage%, read back $actual",
                    )
                }

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

    /**
     * Turns a connection failure into a message that names the cause.
     *
     * The callout used to say only "the operation failed", which told the user
     * nothing about whether the app, the cable or the printer was at fault. Each
     * known cause now gets its own line; anything unrecognised keeps the generic
     * wording, and the English detail still goes to the log.
     */
    private fun localizedConnectFailure(failure: Throwable): String {
        val connect = failure as? PrinterService.ConnectException
            ?: return str(R.string.error_connect_failed)
        return when (connect.failure) {
            PrinterService.ConnectFailure.PERMISSION_DENIED ->
                str(R.string.error_connect_permission_denied)

            PrinterService.ConnectFailure.NO_INTERFACES ->
                str(R.string.error_connect_no_interfaces)

            PrinterService.ConnectFailure.UNSUPPORTED_MODEL -> {
                val model = connect.detail?.let(::modelNameFromDeviceId)
                if (model.isNullOrBlank()) {
                    str(R.string.error_connect_unsupported_model)
                } else {
                    str(R.string.error_connect_unsupported_model_named, model)
                }
            }

            PrinterService.ConnectFailure.DOT4_NAK ->
                str(R.string.error_connect_dot4_nak)

            PrinterService.ConnectFailure.UNKNOWN ->
                str(R.string.error_connect_failed)
        }
    }

    /**
     * Pulls the human-readable model out of an IEEE 1284 device ID such as
     * "MFG:EPSON;CMD:ESCPL2,BDC;MDL:XP-420 Series;CLS:PRINTER;".
     *
     * The raw ID is a long semicolon-separated blob, so showing it whole in the
     * callout would be noise; the MDL field is the part a user recognises.
     */
    private fun modelNameFromDeviceId(deviceId: String): String? =
        deviceId.split(';')
            .firstOrNull { it.substringBefore(':').trim().equals("MDL", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

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
