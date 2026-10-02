package com.padreset.epadreset.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.padreset.epadreset.R
import com.padreset.epadreset.data.EpsonPrinterCatalog
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
    /**
     * The model to offer a report for, when the failure is one the user can
     * actually fix by reporting it.
     *
     * Null for every other outcome, and deliberately null for a
     * maintenance-box model: that one cannot be reset even after it is added,
     * so asking for a report there would be asking for work that changes
     * nothing.
     */
    val reportableModel: String? = null,
    /** Ready-to-paste report for a missing model. Null when there is nothing to report. */
    val modelReportBody: String? = null,
    /** The same report as a prefilled GitHub issue. Null alongside [modelReportBody]. */
    val modelReportUrl: String? = null,
    /**
     * The EEPROM bytes a write found, as text the user can keep.
     *
     * Set after any write, whether it succeeded or was refused partway through.
     * A refusal is exactly when it matters most: some addresses have already
     * been overwritten and this is the only record of what was there.
     */
    val wasteBackup: String? = null,
    /**
     * The build's version name, shown in About.
     *
     * Read once at construction so About and a model report can never disagree
     * about which build produced them — a bug report that names a version the
     * user cannot see is worse than one that names none.
     */
    val appVersion: String = "",
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
    /**
     * The IEEE 1284 device ID, set only when this failure is one the user can
     * fix by reporting their model. Everything else leaves it null.
     */
    val reportableDeviceId: String? = null,
    cause: Throwable? = null,
) : Exception(logMessage, cause)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val printerService = PrinterService(application, ::appendLog)

    /**
     * Read once, from the package manager rather than from `BuildConfig`: the
     * module does not enable `buildConfig`, and adding a generated class for a
     * single string is not worth the build setting.
     */
    private val appVersion: String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private val _uiState = MutableStateFlow(MainUiState(appVersion = appVersion))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var connection: PrinterService.ConnectionState? = null

    fun discoverAndConnect() {
        viewModelScope.launch {
            runJob("Scanning for Epson USB printers") {
                val device = printerService.detectPrinters().firstOrNull()
                    ?: throw UserFacingException(
                        // "No printer detected" is the wrong answer for someone
                        // holding a printer. Before falling back to it, check
                        // whether there is a printer of another brand attached
                        // and say so: the limitation is the app's, not a fault.
                        if (printerService.detectNonEpsonPrinters().isNotEmpty()) {
                            str(R.string.error_non_epson_printer_detected)
                        } else {
                            str(R.string.error_no_printer_detected)
                        },
                    )
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
                            reportableDeviceId = reportableDeviceId(it),
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
                val outcome = printerService.writeWasteCounters(current, selectedCounterNames, targetPercentage)
                val counters = outcome.counters
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
                // Only counters with a published ceiling can be checked this
                // way. The rest report no percentage, so comparing one would
                // pass trivially at a zero target and fail for the wrong reason
                // at any other. The write still goes out; what changes is that
                // the app stops claiming to have verified it, and says so in the
                // log rather than in a message the user cannot act on.
                val unverifiable = counters.filter {
                    it.name in selectedCounterNames && !it.scaleKnown
                }
                if (unverifiable.isNotEmpty()) {
                    appendLog(
                        "Not verifying ${unverifiable.joinToString { it.name }}: " +
                            "this model publishes no counter ceiling",
                    )
                }

                val notApplied = counters.filter { counter ->
                    counter.scaleKnown &&
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
                    wasteBackup = formatBackup(outcome.backup),
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
                val model = connect.detail?.let(EpsonPrinterCatalog::modelName)
                if (model.isNullOrBlank()) {
                    str(R.string.error_connect_unsupported_model)
                } else {
                    str(R.string.error_connect_unsupported_model_named, model)
                }
            }

            // Same shape as the branch above, opposite promise. The catalog
            // does not know this model either, but reinkpy classifies it as
            // collecting waste in a maintenance box, so adding it would enable
            // head cleaning and never a counter reset. Saying "this is not
            // permanent" here would be a promise the hardware cannot keep.
            PrinterService.ConnectFailure.UNSUPPORTED_MODEL_MAINTENANCE_BOX -> {
                val model = connect.detail?.let(EpsonPrinterCatalog::modelName)
                if (model.isNullOrBlank()) {
                    str(R.string.error_connect_unsupported_model_box)
                } else {
                    str(R.string.error_connect_unsupported_model_box_named, model)
                }
            }

            PrinterService.ConnectFailure.DOT4_NAK ->
                str(R.string.error_connect_dot4_nak)

            PrinterService.ConnectFailure.UNKNOWN ->
                str(R.string.error_connect_failed)
        }
    }

    /**
     * The device ID to carry into a report, for the one failure where a report
     * can change the outcome.
     *
     * Only a model missing from the catalog can be added. A maintenance-box
     * model returns null on purpose: its count lives on the box's chip, so no
     * amount of model data would make it resettable, and offering to report it
     * would send the user to do work that cannot pay off.
     */
    private fun reportableDeviceId(failure: Throwable): String? {
        val connect = failure as? PrinterService.ConnectException ?: return null
        return if (connect.failure == PrinterService.ConnectFailure.UNSUPPORTED_MODEL) {
            connect.detail
        } else {
            null
        }
    }

    private data class ModelReport(val model: String, val body: String, val url: String?)

    /**
     * Builds the report a user can hand over when their model is missing.
     *
     * Three things shape what goes in. The device ID is the only part of this
     * that identifies the printer, and IEEE 1284 device IDs can carry a serial
     * number, so any SN field is stripped before the text leaves the device.
     * The log is what makes the report actionable — a model name alone tells a
     * maintainer nothing about which protocol the printer answered with. And
     * the URL is a prefilled new-issue page rather than an API call, so nothing
     * is transmitted until the user presses submit in their own browser.
     */
    private fun buildModelReport(deviceId: String): ModelReport? {
        val model = EpsonPrinterCatalog.modelName(deviceId) ?: return null
        val safeId = redactDeviceId(deviceId)
        val log = _uiState.value.logs.takeLast(REPORT_LOG_LINES).joinToString("\n")
        val app = getApplication<Application>()

        val body = buildString {
            appendLine("Printer model: $model")
            appendLine("Device ID: $safeId")
            appendLine("App: ${app.getString(R.string.app_name)} $appVersion (Android ${Build.VERSION.RELEASE})")
            appendLine()
            appendLine("What happened")
            appendLine("The app could not find this model in its database, so it could not open a")
            appendLine("maintenance session. It is not classified as a maintenance-box model, so")
            appendLine("support can be added once the model's data is included.")
            appendLine()
            appendLine("Protocol log (last $REPORT_LOG_LINES lines)")
            appendLine("```")
            appendLine(log)
            appendLine("```")
        }

        // Only built when the fork has a repository of its own to file it in.
        // Without one the body is still assembled, because the clipboard path
        // does not need a destination — but nothing is addressed anywhere.
        val url = REPORT_REPO_URL?.let { base ->
            Uri.parse("$base/issues/new")
                .buildUpon()
                .appendQueryParameter("title", "Add support for $model")
                .appendQueryParameter("body", body)
                .build()
                .toString()
        }

        return ModelReport(model = model, body = body, url = url)
    }

    /**
     * Drops any serial-number field from an IEEE 1284 device ID.
     *
     * The ID is a semicolon-separated list of KEY:value pairs. The app needs
     * MDL, MFG, CMD and CLS to be useful; SN is the one field that identifies
     * the physical unit, so it is removed rather than sent.
     */
    private fun redactDeviceId(deviceId: String): String =
        deviceId.split(';')
            .filterNot { it.substringBefore(':').trim().equals("SN", ignoreCase = true) }
            .joinToString(";")

    /**
     * Names why the write path stopped.
     *
     * Both cases deserve their own line rather than a generic failure: one says
     * nothing was written at all, the other says the printer refused partway
     * through and how far it got.
     */
    private fun localizedWriteFailure(failure: PrinterService.WasteWriteException): String {
        val address = failure.address?.let { "0x%04X".format(it) }
        return when (failure.reason) {
            PrinterService.WasteWriteException.Reason.BACKUP_FAILED ->
                if (address == null) {
                    str(R.string.error_backup_failed)
                } else {
                    str(R.string.error_backup_failed_at, address)
                }

            PrinterService.WasteWriteException.Reason.REJECTED ->
                if (address == null) {
                    str(R.string.error_write_rejected)
                } else {
                    str(R.string.error_write_rejected_at, address, failure.writtenBefore)
                }
        }
    }

    /**
     * The backup as text the user can keep.
     *
     * Plain address and value lines, sorted, under the model they came from, so
     * a block pasted somewhere months later still says what it is.
     */
    private fun formatBackup(backup: Map<Int, Byte>): String? {
        if (backup.isEmpty()) return null
        val model = connection?.spec?.modelName.orEmpty()
        return buildString {
            appendLine(
                "EEPROM backup taken before the write — ${model.ifBlank { "unknown model" }}",
            )
            backup.entries.sortedBy { it.key }.forEach { (address, value) ->
                appendLine("0x%04X = 0x%02X".format(address, value.toInt() and 0xFF))
            }
        }.trimEnd()
    }

    private suspend fun runJob(message: String, block: suspend () -> Unit) {
        _uiState.value = _uiState.value.copy(
            isBusy = true,
            error = null,
            reportableModel = null,
            modelReportBody = null,
            modelReportUrl = null,
            wasteBackup = null,
        )
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
                    is PrinterService.WasteWriteException -> localizedWriteFailure(failure)
                    else -> str(R.string.callout_operation_failed)
                }
                val report = (failure as? UnhandledJobException)
                    ?.reportableDeviceId
                    ?.let(::buildModelReport)
                _uiState.value = _uiState.value.copy(
                    error = uiMessage,
                    reportableModel = report?.model,
                    modelReportBody = report?.body,
                    modelReportUrl = report?.url,
                    // Carried through on failure as well as success: a refused
                    // run has already overwritten part of what was there.
                    wasteBackup = (failure as? PrinterService.WasteWriteException)
                        ?.backup
                        ?.let(::formatBackup),
                )
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

    private companion object {
        /**
         * How much of the protocol log a model report carries. Enough to show
         * which commands the printer answered and where it stopped, without
         * pasting a full session into an issue.
         */
        const val REPORT_LOG_LINES = 40
    }
}
