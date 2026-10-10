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
import com.padreset.epadreset.data.GapProfileStore
import com.padreset.epadreset.data.ModelSupportReport
import com.padreset.epadreset.data.PrinterStatusSnapshot
import com.padreset.epadreset.data.WasteBackupRecord
import com.padreset.epadreset.data.WasteCounterSpec
import com.padreset.epadreset.data.backupModelMatches
import com.padreset.epadreset.service.PrinterService
import com.padreset.epadreset.service.RepeatAttach
import com.padreset.epadreset.service.SessionGuard
import com.padreset.epadreset.service.BackupConfirmationRequired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MainUiState(
    val connectedDeviceName: String? = null,
    val status: PrinterStatusSnapshot? = null,
    val cleaningOptions: List<String> = emptyList(),
    val supportsGenericMaintenanceJobs: Boolean = false,
    /**
     * False on a session that opened without a catalogued profile.
     *
     * Power cleaning fills the pad. Without a profile this app cannot put the
     * counter back, so the checkbox is not offered and the service refuses it.
     */
    val allowsPowerClean: Boolean = true,
    /**
     * True when the printer answered but is not in the catalog.
     *
     * Status and gentle maintenance are available. Waste reset is not, and the
     * waste panel must not claim the printer has no counter — only that this
     * app does not have a verified profile for it.
     */
    val readOnlySession: Boolean = false,
    /**
     * A pasted profile filled a model the catalog does not have.
     * The reset button says so. Power cleaning stays off.
     */
    val unverifiedLocalProfile: Boolean = false,
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
    val temporaryBackupConfirmation: Boolean = false,
    val backupIsTemporary: Boolean = false,
    val sessionToken: Int = 0,
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
    /** Alternate title for a specific error, such as a failed status refresh. */
    val errorTitle: String? = null,
    /** Raised when refreshing status failed while retaining the last snapshot. */
    val statusRefreshFailed: Boolean = false,
    /** True when [status] is retained from the last successful read after refresh failed. */
    val statusIsStale: Boolean = false,
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
     * Addresses a partial reset must not touch, for the connected model.
     *
     * Carried in the state so the screen gates its per-counter selection on the
     * same set the service enforces, instead of recomputing the rule from the
     * counter specs and drifting from it.
     */
    val untouchableAddresses: Set<Int> = emptySet(),
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
 *
 * [logMessage] is the English technical line for the diagnostic log, which is
 * always English no matter the UI language: never let UI copy reach the log
 * through here. Notes aimed at the user (like the no-Wi-Fi note on the
 * no-printer error) belong in [uiMessage] only.
 */
private class UserFacingException(
    val uiMessage: String,
    val logMessage: String,
) : Exception(uiMessage)

/**
 * Tells [MainViewModel.runJob] which localized line the callout should show for
 * an otherwise technical failure.
 *
 * [logMessage] is what reaches the diagnostic log, so the two can differ: the
 * callout stays translatable while the log keeps the raw detail.
 */
private class StatusRefreshException(cause: Throwable) :
    Exception(cause.message ?: cause.javaClass.simpleName, cause)

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

/** What one silent watch round found once it held the session. */
private sealed interface PollSample {
    data class Status(val snapshot: PrinterStatusSnapshot) : PollSample
    data object Busy : PollSample
    data object Ended : PollSample
}

/** Silent status re-read cadence while the printer reports a live state. */
private const val STATUS_POLL_INTERVAL_MS = 10_000L

/**
 * Silent watch ceiling: 90 rounds at the cadence above is 15 minutes, past
 * any cleaning cycle worth following from the app. Past it the watch stops
 * with a log line instead of polling a stuck printer — and its USB traffic —
 * forever, including in the background.
 */
private const val STATUS_POLL_MAX_ROUNDS = 90

class MainViewModel(application: Application) : AndroidViewModel(application) {
    /**
     * The protocol log, kept out of [MainUiState] on purpose.
     *
     * It is the only thing in this ViewModel that changes on every line, and the
     * write path logs per byte — a reset emits a hundred lines in seconds. While
     * it lived in the state, each one produced a new `MainUiState` and the whole
     * screen recomposed, animated gauges and log list included. On its own flow
     * only the log panel redraws.
     *
     * Declared before [printerService] and [backupStore] because both capture
     * `::appendLog` at construction. The reference resolves at invocation, so the
     * order is not load-bearing today, but nothing enforces that and a field
     * read during construction would.
     */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val printerService = PrinterService(application, ::appendLog, ::noteSlowConnect)

    /**
     * Where the pre-write backups live between sessions.
     *
     * Private preferences, which need no permission and no manifest entry — the
     * same reason [com.padreset.epadreset.usb.UsbTransport] can remember the USB
     * interface that worked without asking the user for anything.
     */
    private val backupStore = BackupStore.from(application, ::appendLog)
    private val gapStore = GapProfileStore.from(application)

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
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { backupStore.history() }
            _uiState.update { it.copy(backupHistory = history) }
        }
    }

    @Volatile
    internal var connection: PrinterService.ConnectionState? = null

    /**
     * The silent status watch, if one is running. Started when a status read
     * lands on a live machine state (printing, busy, waiting, cleaning) and
     * cancelled by any explicit job, a drop, or the ViewModel going away.
     * Never touches `isBusy`: it must not flicker the buttons every interval.
     */
    private var statusPoll: Job? = null

    /**
     * Serializes every use of the open session: connect, status read, write,
     * restore, cleaning and the nozzle check.
     *
     * A read overlapping a write does not "only show transient values". Both
     * share [com.padreset.epadreset.usb.UsbTransport.Session.readBuffer], so
     * interleaved D4 frames are consumed as each other's headers. `isBusy`
     * keeps the buttons off, and this lock is what still serializes a poll
     * that already passed that check, or a second job launched before the
     * first recomposition.
     *
     * Not reentrant. [readStatusHoldingLock] must be called by something that
     * already holds it, never lock again around that call. When a verified write
     * has a trailing status read, it stays inside the same lock as that write so
     * another job cannot write between them. ESC/P2 maintenance dispatches do not
     * immediately re-enter D4: printer startup can make that read fail.
     */
    internal val sessionMutex = Mutex()
    internal val writeBackups = WriteBackupController(backupStore, { change -> _uiState.update(change) }, ::appendLog)

    /**
     * How many [runJob] calls are inside. [MainUiState.isBusy] is true while
     * this is above zero.
     *
     * A boolean cleared by whichever job finishes first re-enables the buttons
     * while another job is still on the session. The count is only touched on
     * Main, which is where [runJob] starts and finishes.
     */
    private var busyCount = 0

    /**
     * Bumped every time the session is torn down or replaced ([dropConnection],
     * [onCleared], and both halves of [connect]). A job captures the value at
     * start; [SessionGuard] then judges a failure against both counters, and
     * only transport-shaped write failures (backup/read-back/write, never a
     * printer verdict) are silenced when it moved. The pre-write backup is
     * already persisted at that point and stays valid.
     */
    @Volatile
    private var sessionEpoch = 0

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
                    connectNow(device)
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
        // The check is a Binder round-trip; keep it off Main.
        viewModelScope.launch {
            if (printerService.isStillAttached(current)) return@launch

            appendLog("Printer detached; connection dropped")
            dropConnection(expected = current)
        }
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
        // Same Binder-off-Main reason as the detach path above.
        viewModelScope.launch {
            if (printerService.isStillAttached(current)) return@launch

            appendLog("Printer is no longer attached; dropping the session")
            dropConnection(expected = current)
        }
    }

    /**
     * Drops the session and puts the UI back to "no printer".
     *
     * Shared by every way the app can learn the printer is gone, so they cannot
     * drift into resetting different parts of the state.
     */
    private fun dropConnection(expected: PrinterService.ConnectionState? = null) {
        val current = connection
        // The attachment check ran on IO. A replug may have installed a new
        // session meanwhile, and that one must survive a drop meant for the old.
        if (expected != null && current !== expected) return
        stopPolling()
        // Local close only: no D4 round-trip, minimal race window with a job
        // that may still hold this session. closeLocal never throws (single
        // swallowing layer with logging inside), so no runCatching here.
        current?.let(printerService::closeLocal)
        connection = null
        writeBackups.invalidate()
        sessionEpoch++
        _uiState.update { it.copy(
            status = null,
            statusIsStale = false,
            statusRefreshFailed = false,
            errorTitle = null,
            connectedDeviceName = null,
            sessionToken = sessionEpoch,
            temporaryBackupConfirmation = false,
            actionSuccessTitle = null,
            actionSuccessMessage = null,
            cleaningOptions = emptyList(),
            supportsGenericMaintenanceJobs = false,
            allowsPowerClean = true,
            readOnlySession = false,
            unverifiedLocalProfile = false,
            supportsWasteReset = false,
            usesMaintenanceBox = false,
            wasteCounterSpecs = emptyList(),
            untouchableAddresses = emptySet(),
            connectedModelName = null,
            modelReportBody = null,
            modelReportUrl = null,
            error = null,
            notice = str(R.string.notice_printer_disconnected),
        ) }
    }

    fun discoverAndConnect() {
        viewModelScope.launch {
            runJob("Scanning for Epson USB printers") {
                val device = printerService.detectPrinters().firstOrNull()
                if (device == null) {
                    // "No printer detected" is the wrong answer for someone
                    // holding a printer. Before falling back to it, check
                    // whether there is a printer of another brand attached
                    // and say so: the limitation is the app's, not a fault.
                    // The log keeps the English technical line; UI-only
                    // notes (like the no-Wi-Fi note) stay out of it.
                    if (printerService.detectNonEpsonPrinters().isNotEmpty()) {
                        throw UserFacingException(
                            str(R.string.error_non_epson_printer_detected),
                            "Non-Epson USB printer detected; only Epson is supported",
                        )
                    } else {
                        throw UserFacingException(
                            str(R.string.error_no_printer_detected),
                            "No Epson printer detected on USB",
                        )
                    }
                }
                connectNow(device)
            }
        }
    }

    /**
     * Entry point for an explicit device (USB attach intent). Launches its own
     * job; the scan paths call [connectNow] inside theirs instead, so one user
     * action is always one job: a nested fire-and-forget connect used to let
     * the outer scan job finish first, clearing `isBusy` and the slow-connect
     * note while the inner connect still worked through its 8 s timeouts —
     * and re-enabled the button, inviting a second, interleaved connect loop
     * against the same printer.
     */
    fun connect(device: UsbDevice) {
        if (device.vendorId != EpsonPrinterCatalog.EPSON_VENDOR_ID) {
            appendError(
                str(R.string.error_non_epson_printer_detected),
                "Refusing non-Epson USB device vendor=0x${device.vendorId.toString(16)}",
            )
            return
        }
        viewModelScope.launch {
            // A re-delivered attach of the device we already hold must not
            // close the live session and handshake again. A real replug gets a
            // new kernel path, so it still falls through to connectNow.
            val already = sessionMutex.withLock {
                val current = connection ?: return@withLock false
                RepeatAttach.shouldIgnore(
                    current.session.device.deviceName,
                    device.deviceName,
                    printerService.isStillAttached(current),
                )
            }
            if (already) {
                appendLog("Already connected to ${device.deviceName}; ignoring repeat attach")
                return@launch
            }
            runJob("Connecting to ${device.productName ?: device.deviceName}") {
                connectNow(device)
            }
        }
    }

    /** The connect itself, as a suspending block for exactly one [runJob]. */
    private suspend fun connectNow(device: UsbDevice) {
        sessionMutex.withLock {
            // Goodbye on the old session before opening the new one, so the
            // printer closes the old D4 channel first. closeBlocking hops to
            // IO internally, so this is safe inside the coroutine. The epoch
            // bumps BEFORE the teardown (not after the new connect), closing
            // the hole where another job failing in between looked like a
            // real printer verdict. This cannot silence this job's own
            // failure: connect errors are UnhandledJobException, which
            // SessionGuard never suppresses.
            connection?.let {
                sessionEpoch++
                // No runCatching: closeBlocking logs its own best-effort
                // failures, and wrapping it here would swallow a
                // CancellationException and keep connecting a dead job.
                printerService.closeBlocking(it)
                if (connection === it) connection = null
            }
            dropConnection()
            val opened = runCatching { printerService.connectPrinter(device) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    // The old session is already torn down and nothing new
                    // replaced it: leave no dangling handle behind.
                    connection = null
                    sessionEpoch++
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
            connection = opened
            sessionEpoch++
            readStatusHoldingLock(opened)
        }
    }

    fun refreshStatus() {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected), "Printer not connected")
            return
        }
        viewModelScope.launch {
            runJob("Reading printer status") {
                sessionMutex.withLock {
                    if (connection !== current) return@withLock
                    try {
                        readStatusHoldingLock(current)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        // A failed refresh is different from a failed maintenance
                        // job: the status card still contains the last successful
                        // snapshot, which must not look like a current reading.
                        throw StatusRefreshException(failure)
                    }
                }
            }
        }
    }

    /**
     * Reads status into the UI. Caller holds [sessionMutex].
     *
     * Does not launch a job and does not touch [busyCount]. A trailing read
     * chained from a write used to call [refreshStatus], which started a second
     * [runJob]; the outer one then cleared `isBusy` while this read was still
     * on the wire.
     */
    private suspend fun readStatusHoldingLock(
        current: PrinterService.ConnectionState,
        verifiedStatus: PrinterStatusSnapshot? = null,
    ) {
        val fresh = verifiedStatus ?: printerService.readPrinterStatus(current)
        if (connection !== current) return
        // A refresh that got no counters (the printer reported an error and the
        // EEPROM did not answer) keeps the last readings from this same printer,
        // marked stale, instead of blanking the pad levels on the card.
        val previousCounters = _uiState.value.status?.wasteCounters.orEmpty()
        val keepCounters = verifiedStatus == null && fresh.wasteCounters.isEmpty() &&
            previousCounters.isNotEmpty() && _uiState.value.connectedDeviceName == current.deviceId
        val status = if (keepCounters) fresh.copy(wasteCounters = previousCounters) else fresh
        val modelName = when (current) {
            is PrinterService.ConnectionState.Catalogued -> current.spec.modelName
            is PrinterService.ConnectionState.ReadOnly -> current.modelName
        }
        val stored = withContext(Dispatchers.IO) { modelName?.let(gapStore::findExact) }
        if (connection !== current) return
        val citation = stored?.let { ModelSupportReport.Citation(it.source, it.version) }
        val screen = sessionScreen(
            current = current,
            appVersion = appVersion,
            androidVersion = Build.VERSION.RELEASE,
            reportUrl = REPORT_REPO_URL?.let(ModelSupportReport::issueUrl),
            citation = citation,
        )
        _uiState.update { it.copy(
            connectedDeviceName = current.deviceId,
            sessionToken = sessionEpoch,
            status = status,
            statusIsStale = keepCounters,
            statusRefreshFailed = false,
            cleaningOptions = screen.cleaningOptions,
            supportsGenericMaintenanceJobs = screen.supportsGenericMaintenanceJobs,
            allowsPowerClean = screen.allowsPowerClean,
            readOnlySession = screen.readOnlySession,
            unverifiedLocalProfile = screen.unverifiedLocalProfile,
            supportsWasteReset = screen.supportsWasteReset,
            usesMaintenanceBox = screen.usesMaintenanceBox,
            wasteCounterSpecs = screen.wasteCounterSpecs,
            untouchableAddresses = screen.untouchableAddresses,
            connectedModelName = screen.connectedModelName,
            error = null,
            modelReportBody = screen.modelReport?.body ?: if (screen.readOnlySession) it.modelReportBody else null,
            modelReportUrl = screen.modelReportUrl ?: if (screen.readOnlySession) it.modelReportUrl else null,
            errorTitle = null,
        ) }
        appendLog("Status read: ${status.state}, ${status.error}")
        startPollingIfBusy(status, current)
    }

    /**
     * Reads status after an ESC/P2 job, inside the job's lock.
     *
     * The job was already sent, and the printer may still be starting its
     * maintenance cycle, so this read can fail without the job failing. A failure
     * is logged and the card is marked stale with the refresh-failed notice.
     * Letting it throw would send it into runJob's error path, where it turns the
     * success message red.
     */
    private suspend fun readStatusAfterMaintenance(current: PrinterService.ConnectionState) {
        try {
            readStatusHoldingLock(current)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            appendLog("Status read after maintenance failed: ${failure.message ?: failure.javaClass.simpleName}")
            _uiState.update { it.copy(statusIsStale = true, statusRefreshFailed = true) }
        }
    }

    /**
     * Watches a live printer until it settles, without disturbing the UI.
     *
     * After an ESC/P2 maintenance job, a manual refresh can re-enter D4 once
     * the printer is ready. If that read reports a live state (printing, busy,
     * waiting, cleaning), the watch re-reads every [STATUS_POLL_INTERVAL_MS]
     * and updates only the status (plus a log line on state transitions), never
     * `isBusy`: a busy pill every ten seconds would flicker the buttons. When the printer settles, one full [refreshStatus]
     * follows so counters that moved meanwhile (cleaning feeds waste ink) are
     * re-read too.
     *
     * One watch at most: starting cancels the previous, and the token keeps a
     * cancelled predecessor's `finally` from clearing its successor. A round is
     * skipped while any explicit job runs, and the watch ends when the session
     * is replaced or dropped, on any read failure, or when the state stops
     * needing it.
     */
    private fun startPollingIfBusy(status: PrinterStatusSnapshot, session: PrinterService.ConnectionState) {
        stopPolling()
        if (!status.needsPolling) return
        appendLog("Printer is ${status.state}; watching until it settles")
        var mine: Job? = null
        mine = viewModelScope.launch {
            var settled = false
            var rounds = 0
            try {
                while (isActive) {
                    delay(STATUS_POLL_INTERVAL_MS)
                    if (_uiState.value.isBusy) continue
                    rounds++
                    if (rounds > STATUS_POLL_MAX_ROUNDS) {
                        appendLog("Watch ended: printer still busy after $STATUS_POLL_MAX_ROUNDS checks")
                        break
                    }
                    val sample = try {
                        sessionMutex.withLock {
                            val live = connection
                            when {
                                live == null || live !== session -> PollSample.Ended
                                _uiState.value.isBusy -> PollSample.Busy
                                else -> PollSample.Status(printerService.readPrinterStatus(live, includeCounters = false))
                            }
                        }
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Exception) {
                        appendLog("Watch ended: ${t.message ?: t.javaClass.simpleName}")
                        break
                    }
                    val next = when (sample) {
                        PollSample.Ended -> break
                        PollSample.Busy -> continue
                        is PollSample.Status -> sample.snapshot
                    }
                    if (connection !== session) break
                    val prev = _uiState.value.status ?: break
                    _uiState.update {
                        it.copy(status = next.copy(wasteCounters = it.status?.wasteCounters.orEmpty()), connectedDeviceName = session.deviceId)
                    }
                    if (prev.stateCode != next.stateCode || prev.errorCode != next.errorCode) {
                        appendLog("Printer state: ${prev.state} -> ${next.state}, ${next.error}")
                    }
                    if (!next.needsPolling) {
                        appendLog("Printer settled: ${next.state}, ${next.error}")
                        settled = true
                        break
                    }
                }
            } finally {
                if (statusPoll === mine) statusPoll = null
            }
            // Counters may have moved while working (cleaning feeds waste
            // ink): one full re-read now that the printer is still. Skipped if
            // the session went away meanwhile — refreshStatus would only report
            // "not connected" about a printer that is already gone.
            if (settled && connection != null) refreshStatus()
        }
        statusPoll = mine
    }

    private fun stopPolling() {
        statusPoll?.cancel()
        statusPoll = null
    }

    fun resetWasteCounters(selectedCounterNames: Set<String>, targetPercentage: Int) {
        val current = connection as? PrinterService.ConnectionState.Catalogued ?: run {
            appendError(str(R.string.error_printer_not_connected), "Waste writes need a catalogued profile")
            return
        }
        viewModelScope.launch {
            runJob("Resetting waste counters", BackupOrigin.RESET) {
                // No local copy of the shared-address rule any more. The service
                // refuses with a typed reason and the message is translated from
                // it. The copy that used to live here tested a slightly
                // different expression — the requested name count against the
                // catalog's, rather than the matched counters — so the two could
                // disagree about which selections were legal.
                _uiState.update { it.copy(
                    actionSuccessTitle = null, actionSuccessMessage = null,
                ) }
                sessionMutex.withLock {
                    if (connection !== current) return@withLock
                    val written = printerService.writeWasteCounters(
                        current, selectedCounterNames, targetPercentage,
                        onBackup = { backup ->
                            check(connection === current) { "Session changed before writing" }
                            writeBackups.capture(WriteBackupController.Source(
                                current, current.spec.modelName, _uiState.value.status?.serialNumber?.takeIf { it.isNotBlank() },
                                BackupOrigin.RESET, selectedCounterNames.sorted().joinToString() + ":$targetPercentage",
                            ), backup) { resetWasteCounters(selectedCounterNames, targetPercentage) }
                            // Replaced inside the callback, which only runs once
                            // the service has accepted a plan. Clearing it before
                            // the call meant a refused run wiped a backup that
                            // was still perfectly valid — nothing had been
                            // written, so it still described the last real write.
                        },
                    )
                    // Returning from the service means every address in the exact
                    // write plan verified, including models with no counter ceiling.
                    appendLog("Waste counters read back: ${written.counters.joinToString { "${it.name} raw=${it.rawHex.ifBlank { it.rawValue.toString() }}" }}")
                    check(connection === current) { "Session changed after writing" }
                    readStatusHoldingLock(current, written.snapshot)
                    _uiState.update { it.copy(
                        actionSuccessTitle = str(R.string.dialog_counters_updated_title),
                        actionSuccessMessage = str(R.string.msg_counters_updated),
                    ) }
                }
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
    fun restoreBackup(model: String?, values: Map<Int, Byte>, unitId: String? = null) {
        val current = connection as? PrinterService.ConnectionState.Catalogued ?: run {
            appendError(str(R.string.error_printer_not_connected), "Waste writes need a catalogued profile")
            return
        }
        viewModelScope.launch {
            runJob("Restoring EEPROM backup", BackupOrigin.RESTORE) {
                val connected = current.spec.modelName
                if (!backupModelMatches(model, connected)) {
                    throw UserFacingException(
                        str(R.string.error_restore_model_mismatch, model.orEmpty(), connected),
                        "Restore blocked: backup model '${model.orEmpty()}' != connected '$connected'",
                    )
                }

                _uiState.update { it.copy(
                    actionSuccessTitle = null, actionSuccessMessage = null,
                ) }
                sessionMutex.withLock {
                    if (connection !== current) return@withLock
                    val actualUnit = _uiState.value.status?.serialNumber?.takeIf { it.isNotBlank() }
                    if (unitId != null && actualUnit != null && unitId != actualUnit) {
                        throw UserFacingException(str(R.string.error_restore_unit_mismatch), "Backup belongs to a different printer unit")
                    }
                    val outcome = printerService.restoreWasteBackup(
                        current, values,
                        onBackup = { backup ->
                            check(connection === current) { "Session changed before restoring" }
                            writeBackups.capture(WriteBackupController.Source(
                                current, connected, _uiState.value.status?.serialNumber?.takeIf { it.isNotBlank() },
                                BackupOrigin.RESTORE, values.toSortedMap().toString(),
                            ), backup) { restoreBackup(model, values, unitId) }
                        },
                    )
                    check(connection === current) { "Session changed after restore" }
                    readStatusHoldingLock(current, outcome.snapshot)
                    _uiState.update { it.copy(
                        actionSuccessTitle = str(R.string.dialog_backup_restored_title),
                        actionSuccessMessage = str(R.string.msg_backup_restored),
                    ) }
                }
            }
        }
    }

    fun runHeadCleaning(cleaningName: String) {
        val current = connection as? PrinterService.ConnectionState.Catalogued ?: run {
            appendError(str(R.string.error_printer_not_connected), "Service cleaning needs a catalogued profile")
            return
        }
        viewModelScope.launch {
            runJob("Starting head cleaning") {
                sessionMutex.withLock {
                    if (connection !== current) return@withLock
                    printerService.runHeadCleaning(current, cleaningName)
                    _uiState.update { it.copy(
                        actionSuccessTitle = str(R.string.dialog_job_sent_title),
                        // The notice is read, so it names the mode the way the dialog
                        // did. The log line above keeps the database wording.
                        actionSuccessMessage = str(
                            R.string.msg_cleaning_sent_named,
                            cleaningModeLabel(getApplication(), cleaningName),
                        ),
                    ) }
                    // Re-read so the watch can follow the cycle back to idle. A
                    // failed read is contained in the helper and cannot fail the
                    // dispatch that was already sent.
                    readStatusAfterMaintenance(current)
                }
            }
        }
    }

    fun runGenericHeadCleaning(
        groupIndex: Int,
        powerClean: Boolean,
        alternativeMode: Boolean,
    ) {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected), "Printer not connected")
            return
        }
        viewModelScope.launch {
            runJob("Starting head cleaning") {
                if (powerClean && (current is PrinterService.ConnectionState.ReadOnly ||
                        (current as? PrinterService.ConnectionState.Catalogued)?.unverifiedLocal == true)) {
                    throw UserFacingException(
                        str(R.string.error_power_clean_unverified),
                        "Power cleaning refused: no verified profile",
                    )
                }
                sessionMutex.withLock {
                    if (connection !== current) return@withLock
                    printerService.runPrintJobHeadCleaning(current, groupIndex, powerClean, alternativeMode)
                    _uiState.update { it.copy(
                        actionSuccessTitle = str(R.string.dialog_job_sent_title),
                        actionSuccessMessage = str(R.string.msg_cleaning_sent),
                    ) }
                    // Same as head cleaning: the job is sent, and the re-read is
                    // contained so a race with the printer's startup stays a
                    // stale card rather than a red success.
                    readStatusAfterMaintenance(current)
                }
            }
        }
    }

    fun printNozzleCheck() {
        val current = connection ?: run {
            appendError(str(R.string.error_printer_not_connected), "Printer not connected")
            return
        }
        viewModelScope.launch {
            runJob("Printing nozzle check") {
                sessionMutex.withLock {
                    if (connection !== current) return@withLock
                    printerService.printNozzleCheck(current)
                    _uiState.update { it.copy(
                        actionSuccessTitle = str(R.string.dialog_job_sent_title),
                        actionSuccessMessage = str(R.string.msg_nozzle_check_sent),
                    ) }
                    // Sending the job is the success criterion. The printer leaves
                    // D4 to prepare the page, so the re-read may fail while printing
                    // is underway; readStatusAfterMaintenance contains that.
                    readStatusAfterMaintenance(current)
                }
            }
        }
    }

    fun dismissActionSuccess() {
        _uiState.update { it.copy(
            actionSuccessMessage = null,
            actionSuccessTitle = null,
        ) }
    }

    override fun onCleared() {
        // The ViewModel outlives rotation (see autoScanDone), so this runs on
        // finish/exit, not on rotate. A write may still be in flight in
        // viewModelScope: close locally without D4 round-trip so the transport
        // is not torn down mid-read as a multi-second goodbye, and bump the
        // epoch so SessionGuard reads a later torn-down transport failure for
        // what it is. closeLocal never throws, so no runCatching here. The
        // pre-write backup is already persisted and stays valid.
        stopPolling()
        connection?.let { printerService.closeLocal(it) }
        connection = null
        sessionEpoch++
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
    private fun localizedConnectFailure(failure: Throwable): String =
        connectFailureText(failure, ::str, ::str)

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
     * [origin] selects the localized reset/restore verification error. It is a
     * job parameter rather than shared mutable state, since jobs can overlap.
     * Backups are persisted by the pre-write callback, not this failure handler.
     */
    internal suspend fun runJob(
        message: String,
        origin: BackupOrigin? = null,
        block: suspend () -> Unit,
    ) {
        // An explicit job owns the channel: stop the silent watch first, so a
        // scheduled re-read never interleaves with it. Whatever needs watching
        // afterwards restarts it (see refreshStatus).
        stopPolling()
        busyCount++
        _uiState.update { it.copy(
            isBusy = true,
            error = null,
            errorTitle = null,
            statusIsStale = false,
            statusRefreshFailed = false,
            // A notice describes the state before this job. Once something is
            // running it is stale, and leaving it up would put "the printer was
            // unplugged" above a job that just connected to one.
            notice = null,
            // Same reasoning: an explanation of the last connection's wait does
            // not describe this one.
            connectNote = null,
            // A missing-model report is how the request reaches the catalog.
            // A nozzle check must not throw it away, including when a local
            // profile already filled the gap.
            modelReportBody = if (keepsModelReport(connection)) it.modelReportBody else null,
            modelReportUrl = if (keepsModelReport(connection)) it.modelReportUrl else null,
            // wasteBackup is deliberately NOT cleared here. It is a record of
            // what the last write overwrote, not a status, and clearing it with
            // every job meant the refresh that follows a successful write wiped
            // it in the next statement. A new write clears it instead, which is
            // the only moment it stops being true.
        ) }
        appendLog(message)
        val epochAtStart = sessionEpoch
        try {
            runCatching { block() }
                .onFailure { failure ->
                // Cancellation is lifecycle, not a printer verdict. Let the
                // scope handle it instead of turning it into a callout.
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // The session was replaced or dropped while this job ran (exit,
                // detach or reconnect during a write). Only transport-shaped
                // failures are silenced: REJECTED and NOT_APPLIED are printer
                // verdicts and always surface, as does anything that is not a
                // write failure at all (notably this job's own connect errors).
                // The pre-write backup is already persisted and stays valid.
                if (failure is BackupConfirmationRequired) return@onFailure
                if (SessionGuard.shouldSuppress(failure, epochAtStart, sessionEpoch)) {
                    appendLog("Job ended after the session was closed; ignoring torn-down transport result")
                    return@onFailure
                }
                // The printer may have gone away while the job was running. That
                // is not a failure the user can act on, and leaving the session
                // in place is what made every later operation fail against a
                // handle that reaches nothing: the refresh button kept re-reading
                // a dead connection instead of letting the app fall back to "no
                // printer" and offer to connect again.
                // Hold the instance that was checked. isStillAttached hops to IO,
                // and a replug may install a new session while it runs; that one
                // must survive a drop meant for the handle that just went away.
                val detached = connection
                if (detached != null && !printerService.isStillAttached(detached)) {
                    appendLog("Job failed and the printer is no longer attached; dropping the session")
                    dropConnection(expected = detached)
                    if (origin == null) return@onFailure
                }

                // The log always keeps the raw, English diagnostic detail; the
                // callout only ever shows a string resource.
                val logged = when (failure) {
                    is UserFacingException -> failure.logMessage
                    else -> failure.message ?: failure.javaClass.simpleName
                }
                appendLog("Error: $logged")
                val uiMessage = when (failure) {
                    is UserFacingException -> failure.uiMessage
                    is StatusRefreshException -> str(R.string.error_status_refresh_detail)
                    is UnhandledJobException -> failure.uiMessage
                    is PrinterService.WasteWriteException,
                    is PrinterService.WastePlanException,
                    -> writeFailureText(failure, origin, getApplication())
                    else -> str(R.string.callout_operation_failed)
                }
                val report = (failure as? UnhandledJobException)?.let(::buildModelReport)
                // The complete backup was already saved before the first write.
                // Keep its copyable text too when verification or transport fails.
                // The backup was already published with its source model before writing.
                _uiState.update { it.copy(
                    error = uiMessage,
                    errorTitle = if (failure is StatusRefreshException) {
                        str(R.string.error_status_refresh_title)
                    } else {
                        null
                    },
                    statusIsStale = failure is StatusRefreshException && it.status != null,
                    statusRefreshFailed = failure is StatusRefreshException,
                    modelReportBody = report?.body,
                    modelReportUrl = report?.url,
                    wasteBackup = it.wasteBackup,
                    actionSuccessTitle = null,
                    actionSuccessMessage = null,
                ) }
            }
        } finally {
            busyCount = (busyCount - 1).coerceAtLeast(0)
            if (busyCount == 0) {
                _uiState.update { it.copy(isBusy = false, connectNote = null) }
            }
        }
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
        _uiState.update {
            if (it.status != null) it else it.copy(connectNote = str(R.string.connect_slow_note))
        }
    }

    internal suspend fun replaceProfile(block: suspend () -> Unit) {
        runJob("Updating local profile") {
            sessionMutex.withLock {
                block()
                sessionEpoch++
                writeBackups.invalidate()
                _uiState.update { it.copy(sessionToken = sessionEpoch, temporaryBackupConfirmation = false) }
            }
        }
        refreshStatus()
    }

    private fun appendLog(line: String) {
        _logs.update { (it + line).takeLast(200) }
    }

    private fun appendError(uiMessage: String, logMessage: String) {
        appendLog("Error: $logMessage")
        _uiState.update { it.copy(error = uiMessage) }
    }

}
