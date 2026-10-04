package com.padreset.epadreset.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.padreset.epadreset.R
import com.padreset.epadreset.data.BackupOrigin
import com.padreset.epadreset.data.BackupStore
import com.padreset.epadreset.data.EpsonPrinterCatalog
import com.padreset.epadreset.data.ModelSupportReport
import com.padreset.epadreset.data.PrinterStatusSnapshot
import com.padreset.epadreset.data.WasteBackupRecord
import com.padreset.epadreset.data.WasteCounterSpec
import com.padreset.epadreset.data.backupModelMatches
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
     * Already localized. Explains a connection that is taking a while, and is
     * null the rest of the time. Cleared when the job ends, whichever way.
     */
    val connectNote: String? = null,
    /**
     * Already localized. Shown in the error callout, so anything written here
     * must go through [MainViewModel.str].
     */
    val error: String? = null,
    /** Ready-to-paste report for a missing model. Null when there is nothing to report. */
    val modelReportBody: String? = null,
    /** Static GitHub template link without model/diagnostic parameters; optional. */
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
     * Saved pre-write backups, newest first.
     *
     * Read once when the ViewModel is built and refreshed on every save, so the
     * restore card is populated on a cold start. That is the whole point of
     * keeping them: the mistake a backup undoes is usually noticed long after the
     * app was closed, and an in-memory copy would be gone by then.
     */
    val backupHistory: List<WasteBackupRecord> = emptyList(),
    /**
     * The connected model's counter specifications, or empty when nothing is
     * connected.
     *
     * The status snapshot carries what each counter reads but not the addresses it
     * lives at, and the restore confirmation needs those addresses to work out
     * what a recorded set of bytes would turn each counter into.
     */
    val wasteCounterSpecs: List<WasteCounterSpec> = emptyList(),
    /**
     * The connected printer's catalog model name, or null when nothing is
     * connected.
     *
     * Kept apart from [PrinterStatusSnapshot.modelName], which is the printer's
     * raw MDL string: the restore guard compares against the catalog name, and the
     * restore list must gate its counter reading on exactly the same string, or
     * the two could disagree about which records can be restored.
     */
    val connectedModelName: String? = null,
    /**
     * The build's version name, shown in About.
     *
     * Read once at construction so About and a model report can never disagree
     * about which build produced them — a bug report that names a version the
     * user cannot see is worse than one that names none.
     */
    val appVersion: String = "",
    /**
     * A statement rather than a failure, shown in its own callout.
     *
     * Kept apart from [error] because the tone is different and so is the
     * meaning: a printer that was unplugged is not an operation that went wrong.
     */
    val notice: String? = null,
    val logs: List<String> = emptyList(),
    val actionSuccessMessage: String? = null,
    /**
     * Title for the dialog that shows [actionSuccessMessage].
     *
     * Per action rather than one fixed line, because the app knows different
     * amounts about each one. A counter reset is verified by reading the counters
     * back; a print job is only sent, and there is no acknowledgement to wait
     * for. A single "Operation completed" said the same thing about both, which
     * was true for one and a claim for the other.
     *
     * Already localized, like the message.
     */
    val actionSuccessTitle: String? = null,
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
    val reportVendorId: Int? = null,
    val reportProductId: Int? = null,
    val reportMaintenanceBox: Boolean = false,
    cause: Throwable? = null,
) : Exception(logMessage, cause)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val printerService = PrinterService(application, ::appendLog, ::noteSlowConnect)

    /**
     * Where the pre-write backups live between sessions.
     *
     * Private preferences, which need no permission and no manifest entry — the
     * same reason [com.padreset.epadreset.usb.UsbTransport] can remember the USB
     * interface that worked without asking the user for anything.
     */
    private val backupStore = BackupStore.from(application)

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

    init {
        // Read once, at construction, so the restore card is already populated the
        // first time the screen composes on a cold start. A store that cannot be
        // read answers with an empty list, so this cannot fail the launch.
        _uiState.value = _uiState.value.copy(backupHistory = backupStore.history())
    }

    private var connection: PrinterService.ConnectionState? = null

    /**
     * Whether the launch scan has already run.
     *
     * The ViewModel outlives a rotation, so this is what keeps it to one scan
     * per session rather than one per recreation.
     */
    private var autoScanDone = false

    /**
     * Scans once, when the app opens.
     *
     * The app used to sit on its empty state until the refresh button was
     * pressed, which reads as "this does not work" to anyone who does not
     * already know that the button is the way in. The button stays for a manual
     * rescan; what changes is that it is no longer the only way.
     *
     * Once per session, not on a timer. Presence is an event Android already
     * reports — the attach and detach broadcasts — so polling would be a
     * wake-up loop asking repeatedly for something the system hands over for
     * free.
     *
     * A miss is not reported as a failure here. The manual button does report
     * one, because there the user asked and deserves an answer; the launch scan
     * did not ask, and "No printer" with the hint under it is already the
     * answer.
     */
    fun autoScanOnce() {
        if (autoScanDone) return
        autoScanDone = true
        if (connection != null) return

        viewModelScope.launch {
            runJob("Scanning for an Epson printer on launch") {
                val device = printerService.detectPrinters().firstOrNull()
                if (device == null) {
                    appendLog("No Epson printer on the USB bus")
                } else {
                    connect(device)
                }
            }
        }
    }

    /**
     * Drops the connection when the printer goes away.
     *
     * Called from the detach broadcast. The stale reading matters more than the
     * missing connection: counters read from a printer that is no longer there
     * are still on screen, and the next job started against them fails in
     * whatever way the transport happens to fail — one path throwing, another
     * waiting on a permission dialog that can never arrive.
     *
     * The attachment is re-checked rather than trusted, because the broadcast
     * says a device was detached, not that it was ours.
     */
    fun onPrinterDetached() {
        val current = connection ?: return
        if (printerService.isStillAttached(current)) return

        appendLog("Printer detached; connection dropped")
        dropConnection()
    }

    /**
     * Called when the app comes back to the foreground.
     *
     * The detach broadcast is only heard while the app is started: the receiver
     * is registered in onStart and removed in onStop, so unplugging the printer
     * while the app sat in the background was never reported at all. The app
     * then came back showing a printer that was gone, and every operation
     * against it failed. Checking on the way back is what turns that into the
     * state it should have been in already.
     */
    fun onReturnedToForeground() {
        val current = connection ?: return
        if (printerService.isStillAttached(current)) return

        appendLog("Printer is no longer attached; dropping the session")
        dropConnection()
    }

    /**
     * Drops the session and puts the UI back to "no printer".
     *
     * Shared by every way the app can learn the printer is gone, so they cannot
     * drift into resetting different parts of the state.
     */
    private fun dropConnection() {
        val current = connection ?: return
        runCatching { printerService.close(current) }
        connection = null
        _uiState.value = _uiState.value.copy(
            status = null,
            connectedDeviceName = null,
            cleaningOptions = emptyList(),
            supportsGenericMaintenanceJobs = false,
            supportsWasteReset = false,
            usesMaintenanceBox = false,
            wasteCounterSpecs = emptyList(),
            connectedModelName = null,
            error = null,
            notice = str(R.string.notice_printer_disconnected),
        )
    }

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
                            reportVendorId = device.vendorId,
                            reportProductId = device.productId,
                            reportMaintenanceBox = (it as? PrinterService.ConnectException)?.failure ==
                                PrinterService.ConnectFailure.UNSUPPORTED_MODEL_MAINTENANCE_BOX,
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
                    wasteCounterSpecs = current.spec.wasteCounters,
                    connectedModelName = current.spec.modelName,
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
            runJob("Resetting waste counters", BackupOrigin.RESET) {
                // The previous write's backup describes a state this run is about
                // to change, so it goes now rather than when the new one arrives.
                _uiState.value = _uiState.value.copy(
                    wasteBackup = null, actionSuccessTitle = null, actionSuccessMessage = null,
                )
                val outcome = printerService.writeWasteCounters(
                    current, selectedCounterNames, targetPercentage,
                    onBackup = { backup ->
                        persistBackup(current.spec.modelName, backup, BackupOrigin.RESET)
                        _uiState.value = _uiState.value.copy(wasteBackup = formatBackup(backup))
                    },
                )
                // Returning from the service means every address in the exact
                // write plan verified, including models with no counter ceiling.
                appendLog("Waste counters read back: ${outcome.counters.joinToString { "${it.name} raw=${it.rawValue}" }}")
                _uiState.value = _uiState.value.copy(
                    actionSuccessTitle = str(R.string.dialog_counters_updated_title),
                    actionSuccessMessage = str(R.string.msg_counters_updated),
                    wasteBackup = formatBackup(outcome.backup),
                )
                refreshStatus()
            }
        }
    }

    /**
     * Writes a saved backup back to the printer.
     *
     * [model] is the record's model and is used only as a guard: the addresses in a
     * record belong to the model it came from, so restoring one printer's record
     * onto another would write to addresses that mean something else there. A blank
     * model — a paste that carried no header — is allowed, because the user may
     * hold only the address lines; the confirmation warns instead of blocking.
     */
    fun restoreBackup(model: String?, values: Map<Int, Byte>) {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected))
            return
        }
        viewModelScope.launch {
            runJob("Restoring EEPROM backup", BackupOrigin.RESTORE) {
                val connected = current.spec.modelName
                if (!backupModelMatches(model, connected)) {
                    throw UserFacingException(
                        str(R.string.error_restore_model_mismatch, model.orEmpty(), connected),
                    )
                }

                _uiState.value = _uiState.value.copy(
                    wasteBackup = null, actionSuccessTitle = null, actionSuccessMessage = null,
                )
                val outcome = printerService.restoreWasteBackup(
                    current, values,
                    onBackup = { backup ->
                        persistBackup(connected, backup, BackupOrigin.RESTORE)
                        _uiState.value = _uiState.value.copy(wasteBackup = formatBackup(backup))
                    },
                )

                _uiState.value = _uiState.value.copy(
                    actionSuccessTitle = str(R.string.dialog_backup_restored_title),
                    actionSuccessMessage = str(R.string.msg_backup_restored),
                    wasteBackup = formatBackup(outcome.backupBeforeRestore),
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
                    actionSuccessTitle = str(R.string.dialog_job_sent_title),
                    // The notice is read, so it names the mode the way the dialog
                    // did. The log line above keeps the database wording.
                    actionSuccessMessage = str(
                        R.string.msg_cleaning_sent_named,
                        cleaningModeLabel(getApplication(), cleaningName),
                    ),
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
                    actionSuccessTitle = str(R.string.dialog_job_sent_title),
                    actionSuccessMessage = str(R.string.msg_cleaning_sent),
                )
            }
        }
    }

    fun printNozzleCheck() {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected))
            return
        }
        viewModelScope.launch {
            runJob("Printing nozzle check") {
                printerService.printNozzleCheck(current)
                _uiState.value = _uiState.value.copy(
                    actionSuccessTitle = str(R.string.dialog_job_sent_title),
                    actionSuccessMessage = str(R.string.msg_nozzle_check_sent),
                )
            }
        }
    }

    fun dismissActionSuccess() {
        _uiState.value = _uiState.value.copy(
            actionSuccessMessage = null,
            actionSuccessTitle = null,
        )
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

            // Maintenance support may be investigated, but never promise a reset
            // of the maintenance-box chip based on adding a catalog entry.
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
     * Missing maintenance-box models can request cleaning/nozzle support too.
     * The report carries the classification so it never promises a box reset.
     */
    private fun reportableDeviceId(failure: Throwable): String? {
        val connect = failure as? PrinterService.ConnectException ?: return null
        return when (connect.failure) {
            PrinterService.ConnectFailure.UNSUPPORTED_MODEL,
            PrinterService.ConnectFailure.UNSUPPORTED_MODEL_MAINTENANCE_BOX -> connect.detail
            else -> null
        }
    }

    private data class ModelReport(val body: String, val url: String?)

    /** No log is exported; the builder uses an explicit field allowlist. */
    private fun buildModelReport(failure: UnhandledJobException): ModelReport? {
        val deviceId = failure.reportableDeviceId ?: return null
        val report = ModelSupportReport.build(
            ModelSupportReport.Source(
                deviceId = deviceId,
                appVersion = appVersion,
                androidVersion = Build.VERSION.RELEASE,
                vendorId = failure.reportVendorId,
                productId = failure.reportProductId,
                maintenanceBox = failure.reportMaintenanceBox,
            ),
        )
        return ModelReport(
            body = report.body,
            url = REPORT_REPO_URL?.let(ModelSupportReport::issueUrl),
        )
    }

    /**
     * Names why the write path stopped.
     *
     * Distinguishes an incomplete backup (nothing written), a rejected or failed
     * write, a failed re-read and bytes that did not match the exact plan.
     */
    private fun localizedWriteFailure(
        failure: PrinterService.WasteWriteException,
        origin: BackupOrigin?,
    ): String {
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

            PrinterService.WasteWriteException.Reason.WRITE_FAILED ->
                str(R.string.error_write_failed, address.orEmpty(), failure.writtenBefore)

            PrinterService.WasteWriteException.Reason.READ_BACK_FAILED ->
                if (address == null) {
                    str(R.string.error_read_back_failed)
                } else {
                    str(R.string.error_read_back_failed_at, address)
                }

            PrinterService.WasteWriteException.Reason.NOT_APPLIED -> {
                val addresses = failure.mismatches.keys.joinToString { "0x%04X".format(it) }
                str(
                    if (origin == BackupOrigin.RESTORE) R.string.error_restore_not_applied
                    else R.string.error_counters_not_applied,
                    addresses,
                )
            }
        }
    }

    /**
     * Saves a pre-write backup so it outlives the session.
     *
     * Called with the complete backup before the first EEPROM write, so a
     * failed re-read, mismatch or unplug cannot prevent it from being saved.
     * The history comes from the store's return value rather than a re-read, so
     * the list updates without a second trip to disk.
     *
     * [origin] says which write found the state, so the list can label it. It is
     * never guessed: a caller that does not know passes [BackupOrigin.UNKNOWN] and
     * the record is stored without one, which reads as "before a write" rather
     * than claiming something false.
     *
     * The store remains best effort if preferences cannot be written; the
     * session still keeps the complete backup as copyable text.
     */
    private fun persistBackup(model: String, backup: Map<Int, Byte>, origin: BackupOrigin) {
        if (backup.isEmpty()) return
        val outcome = backupStore.save(model, backup, origin)
        _uiState.value = _uiState.value.copy(backupHistory = outcome.history)
        val label = model.ifBlank { "unknown model" }
        // A restore writes back a value the list already knows, so it can add no
        // row at all. Saying so is what keeps that from looking like a save that
        // failed.
        if (outcome.deduplicated) {
            appendLog("Backup already saved: ${backup.size} bytes for $label; state already held")
        } else {
            appendLog("Backup saved: ${backup.size} bytes for $label")
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

    /**
     * [origin] selects the localized reset/restore verification error. It is a
     * job parameter rather than shared mutable state, since jobs can overlap.
     * Backups are persisted by the pre-write callback, not this failure handler.
     */
    private suspend fun runJob(
        message: String,
        origin: BackupOrigin? = null,
        block: suspend () -> Unit,
    ) {
        _uiState.value = _uiState.value.copy(
            isBusy = true,
            error = null,
            // A notice describes the state before this job. Once something is
            // running it is stale, and leaving it up would put "the printer was
            // unplugged" above a job that just connected to one.
            notice = null,
            // Same reasoning: an explanation of the last connection's wait does
            // not describe this one.
            connectNote = null,
            modelReportBody = null,
            modelReportUrl = null,
            // wasteBackup is deliberately NOT cleared here. It is a record of
            // what the last write overwrote, not a status, and clearing it with
            // every job meant the refresh that follows a successful write wiped
            // it in the next statement. A new write clears it instead, which is
            // the only moment it stops being true.
        )
        appendLog(message)
        runCatching { block() }
            .onFailure { failure ->
                // The printer may have gone away while the job was running. That
                // is not a failure the user can act on, and leaving the session
                // in place is what made every later operation fail against a
                // handle that reaches nothing: the refresh button kept re-reading
                // a dead connection instead of letting the app fall back to "no
                // printer" and offer to connect again.
                if (connection?.let { !printerService.isStillAttached(it) } == true) {
                    appendLog("Job failed and the printer is no longer attached; dropping the session")
                    dropConnection()
                    return@onFailure
                }

                // The log always keeps the raw, English diagnostic detail; the
                // callout only ever shows a string resource.
                val logged = failure.message ?: failure.javaClass.simpleName
                appendLog("Error: $logged")
                val uiMessage = when (failure) {
                    is UserFacingException -> failure.uiMessage
                    is UnhandledJobException -> failure.uiMessage
                    is PrinterService.WasteWriteException -> localizedWriteFailure(failure, origin)
                    else -> str(R.string.callout_operation_failed)
                }
                val report = (failure as? UnhandledJobException)?.let(::buildModelReport)
                // The complete backup was already saved before the first write.
                // Keep its copyable text too when verification or transport fails.
                val partialBackup = (failure as? PrinterService.WasteWriteException)?.backup
                _uiState.value = _uiState.value.copy(
                    error = uiMessage,
                    modelReportBody = report?.body,
                    modelReportUrl = report?.url,
                    wasteBackup = partialBackup?.let(::formatBackup) ?: _uiState.value.wasteBackup,
                )
            }
        _uiState.value = _uiState.value.copy(isBusy = false, connectNote = null)
    }

    /**
     * Explains the wait when a connection is about to spend seconds on a route
     * the printer did not answer on.
     *
     * Only while nothing is connected. A session that already exists means the
     * note describes a state that has passed, and [runJob] clears it when the
     * attempt ends either way, so a failure does not leave it above the error.
     */
    private fun noteSlowConnect() {
        if (_uiState.value.status != null) return
        _uiState.value = _uiState.value.copy(connectNote = str(R.string.connect_slow_note))
    }

    private fun appendLog(line: String) {
        _uiState.value = _uiState.value.copy(logs = (_uiState.value.logs + line).takeLast(200))
    }

    private fun appendError(message: String) {
        appendLog("Error: $message")
        _uiState.value = _uiState.value.copy(error = message)
    }

}
