package com.padreset.epadreset.service

import android.hardware.usb.UsbDevice
import android.content.Context
import android.hardware.usb.UsbConstants
import com.padreset.epadreset.data.EpsonPrinterCatalog
import com.padreset.epadreset.data.openProfile
import com.padreset.epadreset.data.CleaningSpec
import com.padreset.epadreset.data.PrinterSpec
import com.padreset.epadreset.data.PrinterStatusSnapshot
import com.padreset.epadreset.data.WasteCounterStatus
import com.padreset.epadreset.protocol.Escp2MaintenanceProtocol
import com.padreset.epadreset.protocol.EpsonProtocol
import com.padreset.epadreset.usb.UsbTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrinterService(
    context: Context,
    private val log: (String) -> Unit = {},
    /**
     * Called when a connection attempt is about to spend seconds on an interface
     * the printer did not answer on.
     *
     * The failed attempt is not an error and not worth reporting as one, but it
     * is several seconds of the user watching a spinner, and this is the only
     * point where that is knowable before it happens.
     */
    private val onSlowConnect: () -> Unit = {},
) {
    /**
     * Why a connection attempt failed, in terms the user can act on.
     *
     * [detail] carries the variable part (the model name, the interface id) so
     * the UI can build a localized message; [english] is the diagnostic text for
     * the protocol log. Keeping the two apart is what lets the callout stay
     * translated without losing the reason.
     */
    enum class ConnectFailure(val english: String) {
        PERMISSION_DENIED("USB permission denied"),
        NO_INTERFACES("No USB interfaces with bulk IN/OUT endpoints found"),
        UNSUPPORTED_MODEL("Unsupported Epson model"),

        /**
         * The model is not in the catalog and reinkpy classifies it as using a
         * maintenance box. Kept apart from [UNSUPPORTED_MODEL] because the two
         * look identical to the user and mean the opposite thing: one is a gap
         * that can be filled, the other is a counter that lives on the box's
         * chip and no software can rewrite.
         */
        UNSUPPORTED_MODEL_MAINTENANCE_BOX("Unsupported Epson model (maintenance box)"),

        DOT4_NAK("Printer rejected IEEE 1284.4 enter sequence with NAK (0x15)"),

        /**
         * No handshake reply at all on this interface within the enter window.
         * Kept apart from [DOT4_NAK] because the retry policy differs: a NAK is
         * worth one retry without the Android soft reset, while silence means
         * the interface carries no D4 and the next candidate should be tried
         * immediately instead of paying the eight second D4 read.
         */
        DOT4_NO_REPLY("Printer did not answer the IEEE 1284.4 handshake"),

        /**
         * The device opened but another app holds its USB interface (on
         * Android typically a print service). Other interfaces are still tried;
         * if all are held, this is the error that must reach the user, because
         * only closing the other app fixes it.
         */
        INTERFACE_BUSY("USB interface is held by another app"),

        /**
         * The device is gone by the time permission or the interface was asked
         * for — unplugged mid-connect, typically.
         *
         * Kept apart from [PERMISSION_DENIED] and [INTERFACE_BUSY] because both
         * of those send the user somewhere that cannot help: one to a permission
         * dialog that will never appear, the other to close apps that are not
         * holding anything. Neither is true when the cable is out. The English
         * text matches what [UsbTransport.permissionFailure] already says, so
         * the two paths read the same.
         */
        DEVICE_DETACHED("Printer is no longer connected"),
        UNKNOWN("Unable to connect to printer"),
    }

    /**
     * A connection failure carrying a cause the UI can translate.
     *
     * [detail] is not user-facing on its own: it is interpolated into a string
     * resource, and for [ConnectFailure.UNSUPPORTED_MODEL] it is the raw IEEE
     * 1284 device ID, from which the model name is extracted for display.
     */
    class ConnectException(
        val failure: ConnectFailure,
        val detail: String? = null,
        cause: Throwable? = null,
    ) : Exception(
        if (detail.isNullOrBlank()) failure.english else "${failure.english}: $detail",
        cause,
    )

    private val appContext = context.applicationContext

    /**
     * An open printer session.
     *
     * [Catalogued] is the only variant that holds a profile, and the only one
     * write methods accept. [ReadOnly] is a printer that answered IEEE 1284.4
     * and is not in the catalog: status and gentle maintenance, no keys, no
     * stub profile to write through.
     */
    sealed class ConnectionState {
        abstract val session: UsbTransport.Session
        abstract val protocol: EpsonProtocol
        abstract val deviceId: String
        abstract val backend: EpsonProtocol.ControlBackend
        abstract val controlChannel: EpsonProtocol.D4ChannelState?

        data class Catalogued(
            override val session: UsbTransport.Session,
            val spec: PrinterSpec,
            override val protocol: EpsonProtocol,
            override val deviceId: String,
            override val backend: EpsonProtocol.ControlBackend,
            override val controlChannel: EpsonProtocol.D4ChannelState? = null,
            /**
             * True when this profile was pasted on this device and is not in
             * the bundled catalog. Writes are allowed; they are not verified.
             * Power cleaning stays off: it fills a pad this profile has not
             * been shown to put back.
             */
            val unverifiedLocal: Boolean = false,
        ) : ConnectionState()

        data class ReadOnly(
            override val session: UsbTransport.Session,
            override val protocol: EpsonProtocol,
            override val deviceId: String,
            override val backend: EpsonProtocol.ControlBackend,
            override val controlChannel: EpsonProtocol.D4ChannelState? = null,
            val modelName: String?,
            val usesMaintenanceBox: Boolean,
        ) : ConnectionState()
    }

    private val transport = UsbTransport(appContext)
    private var lastSeenDeviceId: String? = null

    init {
        EpsonPrinterCatalog.logger = log
    }

    /**
     * Suspends on IO: `deviceList` is a Binder call into the USB service and
     * does not belong on Main, where the launch scan and the detach receiver
     * run.
     */
    suspend fun detectPrinters(): List<UsbDevice> = withContext(Dispatchers.IO) {
        transport.findEpsonDevices()
    }

    /**
     * USB printers from any vendor other than Epson.
     *
     * Used only to explain a failed scan. Without it the app answers "no
     * printer detected" to someone who has one plugged in, which reads as a bug
     * rather than as a brand limitation.
     */
    suspend fun detectNonEpsonPrinters(): List<UsbDevice> = withContext(Dispatchers.IO) {
        transport.findPrintersFromOtherVendors()
    }

    /**
     * Whether the device behind an open connection is still on the bus.
     *
     * The connection object stays usable-looking after the cable is pulled, so
     * this is the only way to tell a live session from a dead one before
     * starting a job that will fail in whatever way the transport happens to
     * fail that day.
     */
    suspend fun isStillAttached(connection: ConnectionState): Boolean = withContext(Dispatchers.IO) {
        transport.isAttached(connection.session.device)
    }

    suspend fun connectPrinter(device: UsbDevice): ConnectionState = withContext(Dispatchers.IO) {
        log(
            "USB device detected: vendor=0x${device.vendorId.toString(16)} " +
                "product=0x${device.productId.toString(16)} " +
                "name=${device.productName ?: device.deviceName}",
        )
        if (!transport.ensurePermission(device)) {
            // `ensurePermission` answers false both for a refusal and for a
            // device that is no longer on the bus, and the two need different
            // words: one asks the user to accept a dialog, the other to plug the
            // printer back in. Reading it as a refusal sent people looking for a
            // dialog that was never going to arrive.
            throw ConnectException(
                if (transport.isAttached(device)) ConnectFailure.PERMISSION_DENIED
                else ConnectFailure.DEVICE_DETACHED,
            )
        }
        transport.listInterfaces(device).forEach { info ->
            log(
                "USB interface ${info.id}: class=0x${info.interfaceClass.toString(16)} " +
                    "subclass=0x${info.interfaceSubclass.toString(16)} protocol=0x${info.interfaceProtocol.toString(16)}",
            )
            info.endpointDescriptions.forEach { endpoint ->
                log("  $endpoint")
            }
        }
        val candidates = transport.candidateInterfaces(device)
        if (candidates.isEmpty()) {
            throw ConnectException(ConnectFailure.NO_INTERFACES)
        }

        var lastError: Throwable? = null
        lastSeenDeviceId = null
        candidates.forEachIndexed { index, usbInterface ->
            log("Trying USB interface ${usbInterface.id} (${index + 1}/${candidates.size})")

            val firstAttempt = runCatching { connectOnce(device, usbInterface.id, performSoftReset = true) }
            firstAttempt.getOrNull()?.let {
                transport.rememberPreferredInterface(device, usbInterface.id)
                return@withContext it
            }

            val firstError = firstAttempt.exceptionOrNull()
            if (firstError is CancellationException) throw firstError
            currentCoroutineContext().ensureActive()

            // A model the catalog does not know about cannot be fixed by trying
            // another USB interface, so stop here rather than letting the next
            // attempt overwrite the one error that actually explains the failure.
            if (firstError.unresolvedModel()) {
                log("Model is not in the catalog, no point trying the remaining interfaces")
                throw firstError!!
            }

            if (firstError?.message?.contains(ConnectFailure.DOT4_NAK.english) == true) {
                log("1284.4 enter was rejected after Android soft reset on interface ${usbInterface.id}, retrying without soft reset")
                val secondAttempt = runCatching { connectOnce(device, usbInterface.id, performSoftReset = false) }
                secondAttempt.getOrNull()?.let {
                    transport.rememberPreferredInterface(device, usbInterface.id)
                    return@withContext it
                }

                val secondError = secondAttempt.exceptionOrNull()
                if (secondError is CancellationException) throw secondError
                if (secondError.unresolvedModel()) {
                    log("Model is not in the catalog, no point trying the remaining interfaces")
                    throw secondError!!
                }
                lastError = secondError
                log("D4 failed on interface ${usbInterface.id}: ${lastError?.message}")
                return@forEachIndexed
            }

            lastError = firstError
            log("Interface ${usbInterface.id} failed before D4 fallback: ${lastError?.message}")
        }

        val seen = lastSeenDeviceId
        if (seen != null && EpsonPrinterCatalog.resolve(appContext, seen) == null) {
            throw unknownModelFailure(seen)
        }
        throw lastError ?: ConnectException(ConnectFailure.UNKNOWN)
    }

    /** True when the failure is an unknown model, which no retry can resolve. */
    private fun Throwable?.unresolvedModel(): Boolean =
        when ((this as? ConnectException)?.failure) {
            ConnectFailure.UNSUPPORTED_MODEL,
            ConnectFailure.UNSUPPORTED_MODEL_MAINTENANCE_BOX,
            -> true

            else -> false
        }

    /**
     * Names the reason an unknown model cannot be used.
     *
     * Both outcomes can request model investigation. Keeping the box
     * classification separate lets the UI scope those requests to maintenance,
     * rather than promising this app can reset a counter on the box chip.
     */
    private fun unknownModelFailure(deviceId: String): ConnectException {
        val model = EpsonPrinterCatalog.modelName(deviceId)
        val failure = if (EpsonPrinterCatalog.usesMaintenanceBox(appContext, model)) {
            ConnectFailure.UNSUPPORTED_MODEL_MAINTENANCE_BOX
        } else {
            ConnectFailure.UNSUPPORTED_MODEL
        }
        log("Unknown model '${model ?: "?"}' classified as ${failure.english}")
        return ConnectException(failure, detail = deviceId)
    }

    suspend fun readPrinterStatus(
        connection: ConnectionState,
        includeCounters: Boolean = true,
    ): PrinterStatusSnapshot = withContext(Dispatchers.IO) {
        restoreControlMode(connection)
        if (!includeCounters) return@withContext readStatusOnly(connection)
        when (connection) {
            is ConnectionState.Catalogued -> readStatus(connection) { address -> readEepromByte(connection, address) }
            is ConnectionState.ReadOnly -> readStatus(connection) { _ ->
                error("A read-only session has no EEPROM map")
            }
        }
    }

    private fun readStatus(
        connection: ConnectionState,
        readByte: (Int) -> Byte,
    ): PrinterStatusSnapshot {
        val baseSnapshot = readStatusOnly(connection)
        val wasteCounters = when (connection) {
            is ConnectionState.Catalogued -> connection.spec.wasteCounters.map { counter ->
                val bytes = counter.addresses.map(readByte).toByteArray()
                val masks = counter.addresses.map { counter.masks[it] ?: 0xFF }
                log(
                    "Waste counter '${counter.name}' addresses=${
                        counter.addresses.joinToString(prefix = "[", postfix = "]") { "0x%X".format(it) }
                    } bytes=${bytes.joinToString(" ") { "%02X".format(it) }}",
                )
                connection.protocol.parseWasteCounter(bytes, counter.max, counter.name, masks)
            }
            is ConnectionState.ReadOnly -> emptyList()
        }
        return baseSnapshot.copy(wasteCounters = wasteCounters)
    }

    /**
     * What a write did, and the state it found on the way in.
     *
     * [backup] holds the bytes that were at those addresses before anything was
     * written, keyed by address. It is the only record of how to get back.
     */
    private fun readStatusOnly(connection: ConnectionState): PrinterStatusSnapshot {
        log("Requesting printer status")
        val response = sendControlPayload(connection, connection.protocol.buildStatusRequest())
        return connection.protocol.parseStatusResponse(response, connection.deviceId)
    }

    data class WasteWriteOutcome(
        val counters: List<WasteCounterStatus>,
        val backup: Map<Int, Byte>,
        val snapshot: PrinterStatusSnapshot,
    )

    /**
     * What a restore did, and the state it found on the way in.
     *
     * [backupBeforeRestore] is the bytes the addresses held *before* the restore
     * ran. A restore is a write like any other, so it is backed up like any other
     * — and that backup is how a restore is itself undone, which is why the caller
     * is expected to keep it.
     *
     * [skipped] counts record addresses the connected model does not publish.
     * They are not written: a record is taken from a model the guard already
     * matched, but the catalog can change between builds, and writing an address
     * whose meaning has moved is the one outcome worth refusing.
     * A result is returned only after every byte in the applied plan verifies.
     */
    data class WasteRestoreOutcome(
        val counters: List<WasteCounterStatus>,
        val backupBeforeRestore: Map<Int, Byte>,
        val written: Int,
        val skipped: Int,
        val snapshot: PrinterStatusSnapshot,
    )

    /** Unsigned values retained for both the localized error and protocol log. */
    data class WasteByteMismatch(val expected: Int, val actual: Int)

    /**
     * The write path refusing to go on.
     *
     * [writtenBefore] is how many addresses the run had already written when it
     * stopped, so the message can say how far it got. [backup] is what was at
     * those addresses beforehand — on a rejection it is the only way back, so it
     * travels with the failure rather than being thrown away with the run.
     */
    class WasteWriteException(
        val reason: Reason,
        val address: Int? = null,
        val writtenBefore: Int = 0,
        val backup: Map<Int, Byte> = emptyMap(),
        val mismatches: Map<Int, WasteByteMismatch> = emptyMap(),
        cause: Throwable? = null,
    ) : Exception(
        buildString {
            append(reason.name)
            address?.let { append(" at 0x%04X".format(it)) }
            mismatches.forEach { (address, mismatch) ->
                append("; 0x%04X expected 0x%02X, read 0x%02X".format(
                    address, mismatch.expected, mismatch.actual,
                ))
            }
            cause?.message?.let { append(": $it") }
        },
        cause,
    ) {
        enum class Reason { BACKUP_FAILED, REJECTED, WRITE_FAILED, READ_BACK_FAILED, NOT_APPLIED }
    }

    /**
     * The write path refusing to build a plan, before anything is written.
     *
     * Kept apart from [WasteWriteException] on purpose. That one describes a
     * write that happened: it carries the backup, how far the run got, and the
     * bytes that did not match. A refusal here happens before anything is read,
     * backed up or written, so all of those would be empty — and two callers
     * read them for real (`runJob` takes the partial backup, [SessionGuard]
     * decides on the reason). A separate type also falls through
     * [SessionGuard]'s filter by not being a `WasteWriteException`, which is
     * exactly right: a deterministic validation refusal is never transport
     * fallout and must never be silenced as one.
     *
     * Every reason is something the user can act on, which is why each has its
     * own message rather than the generic "operation failed".
     */
    class WastePlanException(val reason: Reason) :
        Exception("Waste plan refused: ${reason.name}") {
        enum class Reason {
            NO_COUNTER_SELECTED,
            NO_RESET_VALUES,
            SHARED_BYTES,
            PERCENTAGE_UNSUPPORTED,
            RESTORE_NO_APPLICABLE_ADDRESSES,
            RESTORE_INCOMPLETE_COUNTER,
        }
    }

    suspend fun writeWasteCounters(
        connection: ConnectionState.Catalogued,
        selectedCounterNames: Set<String>,
        targetPercentage: Int,
        onBackup: (Map<Int, Byte>) -> Unit = {},
    ): WasteWriteOutcome = withContext(Dispatchers.IO) {
        restoreControlMode(connection)
        val plan = WasteWriter.planFor(connection.spec, selectedCounterNames, targetPercentage)
        log("Writing waste counter plan: ${plan.size} EEPROM bytes")

        val verified = applyWritePlan(connection, plan, onBackup)
        WasteWriteOutcome(verified.value.wasteCounters, verified.backup, verified.value)
    }

    /**
     * Writes a recorded backup back to the printer.
     *
     * This is the inverse of a reset, and the only operation in the app that
     * raises a counter. That is deliberate and bounded: the values are not chosen
     * by the user, they are the bytes this app itself read from this printer
     * before an earlier write, so the outcome is a state the hardware was already
     * in. The guards that keep it to that live above this call — the model match
     * and the confirmation — because they are policy, not protocol.
     *
     * Reuses [applyWritePlan], so the restore is backed up before it writes and
     * stops at the first address the printer refuses, exactly like a reset.
     */
    suspend fun restoreWasteBackup(
        connection: ConnectionState.Catalogued,
        backup: Map<Int, Byte>,
        onBackup: (Map<Int, Byte>) -> Unit = {},
    ): WasteRestoreOutcome = withContext(Dispatchers.IO) {
        require(backup.isNotEmpty()) { "Nothing to restore" }

        restoreControlMode(connection)
        val applicable = WasteRestore.applicable(connection.spec, backup)

        val plan = WasteRestore.planFrom(applicable)
        val skipped = backup.size - applicable.size
        log("Restoring ${plan.size} EEPROM bytes; $skipped address(es) not used by this model")

        val verified = applyWritePlan(connection, plan, onBackup)
        WasteRestoreOutcome(
            counters = verified.value.wasteCounters,
            backupBeforeRestore = verified.backup,
            written = plan.size,
            skipped = skipped,
            snapshot = verified.value,
        )
    }

    /**
     * Hands [plan] to the write path's rules, with this connection behind them.
     *
     * The rules live in [WasteWriter] so they can be exercised without a
     * printer; this binds them to the transport and nothing more.
     */
    private fun applyWritePlan(
        connection: ConnectionState.Catalogued,
        plan: Map<Int, Int>,
        onBackup: (Map<Int, Byte>) -> Unit,
    ): WasteWriter.VerifiedWrite<PrinterStatusSnapshot> = WasteWriter(
        readByte = { address -> readEepromByte(connection, address) },
        writeByte = { address, value -> writeEeprom(connection, address, value) },
        log = { line -> log(line) },
    ).writeAndVerify(plan, onBackup) { readByte -> readStatus(connection, readByte) }

    /** Reads one EEPROM byte. */
    private fun readEepromByte(connection: ConnectionState.Catalogued, address: Int): Byte {
        val response = sendControlPayload(connection, connection.protocol.buildReadEepromCommand(address))
        return parseEepromReadByte(response)
    }

    /**
     * Writes one EEPROM byte and reads the printer's answer.
     *
     * The answer is acted on only when it is a recognised rejection. Its shape
     * comes from a sibling implementation that talks over the raw USB pipe
     * rather than D4, so the framing differs even if the service payload should
     * not: an unrecognised answer is logged and the write counts as neither
     * accepted nor refused, because stopping a write that may well have worked
     * is the worse failure.
     */
    private fun writeEeprom(
        connection: ConnectionState.Catalogued,
        address: Int,
        value: Int,
    ): EpsonProtocol.WriteReply {
        val reply = sendControlPayload(
            connection,
            connection.protocol.buildWriteEepromCommand(address, value),
        )
        val verdict = connection.protocol.classifyWriteReply(reply)

        // Only the raw answer is logged here, because this is where the bytes
        // are. What the verdict means for the run is [WasteWriter]'s business.
        when (verdict) {
            EpsonProtocol.WriteReply.Applied -> Unit

            EpsonProtocol.WriteReply.Rejected -> log(
                "Write 0x${"%04X".format(address)} answered: ${reply.decodeToString().trim()}",
            )

            EpsonProtocol.WriteReply.Unknown -> log(
                "Write 0x${"%04X".format(address)} answered " +
                    "${reply.joinToString(" ") { "%02X".format(it) }}, which is not a marker this app knows",
            )
        }
        return verdict
    }

    suspend fun runHeadCleaning(
        connection: ConnectionState.Catalogued,
        cleaningName: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        restoreControlMode(connection)
        val cleaning = connection.spec.cleaningOptions.firstOrNull { it.name == cleaningName }
            ?: error("Unsupported cleaning mode: $cleaningName")
        require(cleaning.type.equals("service", ignoreCase = true)) {
            "Cleaning mode '${cleaning.name}' is not supported over USB D4"
        }
        log(
            "Starting head cleaning '${cleaning.name}' payload=${
                cleaning.payload.joinToString(" ") { "%02X".format(it) }
            }",
        )
        val reply = sendControlPayload(connection, connection.protocol.buildCleaningCommand(cleaning.payload))
        log("Head cleaning reply: ${reply.joinToString(" ") { "%02X".format(it) }}")
        if (connection.protocol.isNegativeMaintenanceReply(reply)) {
            val fallbackGroup = genericCleaningGroup(cleaning)
            if (fallbackGroup != null) {
                log("Service cleaning '${cleaning.name}' was rejected, falling back to ESC/P2 cleaning group=$fallbackGroup")
                runPrintJobHeadCleaning(
                    connection = connection,
                    groupIndex = fallbackGroup,
                    powerClean = false,
                    alternativeMode = false,
                )
            } else {
                error("Printer rejected cleaning mode '${cleaning.name}'")
            }
        }
        reply
    }

    suspend fun runPrintJobHeadCleaning(
        connection: ConnectionState,
        groupIndex: Int,
        powerClean: Boolean,
        alternativeMode: Boolean,
    ) = withContext(Dispatchers.IO) {
        val offer = when (connection) {
            is ConnectionState.ReadOnly -> SessionOffer.decide(null)
            is ConnectionState.Catalogued -> SessionOffer.decide(connection.spec, connection.unverifiedLocal)
        }
        SessionOffer.refusePowerClean(offer, powerClean)
        val payload = Escp2MaintenanceProtocol.buildHeadCleaningJob(
            groupIndex = groupIndex,
            powerClean = powerClean,
            alternativeMode = alternativeMode,
        )
        log(
            "Starting ESC/P2 head cleaning group=$groupIndex powerClean=$powerClean alternativeMode=$alternativeMode bytes=${payload.size}",
        )
        sendPrintJob(connection, payload)
    }

    suspend fun printNozzleCheck(connection: ConnectionState) = withContext(Dispatchers.IO) {
        val payload = Escp2MaintenanceProtocol.buildNozzleCheckJob()
        log("Printing nozzle check bytes=${payload.size}")
        sendPrintJob(connection, payload)
    }

    /**
     * Fast, Main-safe release: no D4 round-trip and no bulk transfer.
     *
     * It still performs synchronous USB-stack calls (`releaseInterface` /
     * `close`), so IO is preferred when available — but it never blocks on a
     * printer reply, unlike [closeBlocking] below.
     *
     * Use for detach, foreground-return and [onCleared]-style paths, where the
     * device is gone or the app is leaving. A D4 goodbye round-trip here would
     * both block Main (bulkWrite 4 s + reply 8 s + 8 s when the printer is mute
     * but still attached) and close the transport from under a concurrent read.
     * The detached case already fails fast (bulkTransfer returns -1
     * immediately), but the mute-but-attached case does not.
     */
    fun closeLocal(connection: ConnectionState) {
        connection.session.close { throwable ->
            log("USB release failed (best effort, nothing to retry): ${throwable.message}")
        }
    }

    /**
     * Full close with a D4 channel goodbye (0x02). Blocking: switches to IO
     * internally, so it is safe to call from a Main coroutine, but never call
     * it from a raw Main callback.
     *
     * Use when replacing an old session before a new connect, so the printer
     * closes the old channel before a new D4 session opens. Prefer
     * [closeLocal] where the device is gone or the app is leaving.
     */
    suspend fun closeBlocking(connection: ConnectionState) = withContext(Dispatchers.IO) {
        runCatching {
            if (connection.session.controlModeInvalidated) return@runCatching
            val channel = connection.controlChannel ?: return@runCatching
            sendD4Command(
                connection.session,
                connection.protocol,
                0x02,
                byteArrayOf(channel.psid.toByte(), channel.ssid.toByte()),
            )
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            log("D4 goodbye failed (best effort, closing anyway): ${throwable.message}")
        }
        connection.session.close { throwable ->
            log("USB release failed (best effort, nothing to retry): ${throwable.message}")
        }
    }

    private suspend fun connectOnce(device: UsbDevice, interfaceId: Int, performSoftReset: Boolean): ConnectionState {
        val usbInterface = transport.candidateInterfaces(device).firstOrNull { it.id == interfaceId }
            ?: error("USB interface $interfaceId not found")
        // A held interface is a named failure, not a generic one: the fix is
        // closing the other app, and the message must say so. Release the
        // half-open connection instead of leaking it.
        //
        // `claimInterface` returns a bare boolean and Android exposes no errno,
        // so a device that vanished between enumeration and the claim looks
        // exactly like one another app holds. The attachment re-check is what
        // tells them apart; without it the user is sent to close apps that are
        // holding nothing.
        val session = try {
            transport.openOnInterface(device, usbInterface, permissionAlreadyGranted = true)
        } catch (claimed: UsbTransport.UsbClaimException) {
            runCatching { claimed.connection.close() }
            throw ConnectException(
                if (transport.isAttached(device)) ConnectFailure.INTERFACE_BUSY
                else ConnectFailure.DEVICE_DETACHED,
                cause = claimed,
            )
        }
        try {
            log("USB session opened, claiming interface ${session.usbInterface.id}")
            if (performSoftReset) {
                transport.softReset(session)
                log("USB soft reset sent")
            } else {
                log("Skipping Android USB soft reset for this attempt")
            }

            val deviceId = transport.getDeviceId(session)
            log("IEEE1284 device ID: $deviceId")
            lastSeenDeviceId = deviceId
            // Handshake and status do not use a profile. Resolve only after the
            // channel is up, so a missing catalog entry does not throw away a
            // session the printer already accepted.
            val protocol = EpsonProtocol.statusOnly(EpsonPrinterCatalog.modelName(deviceId).orEmpty())

            val enterReply = enterDot4(session, protocol)
            // A lone 0x15 is the classic NAK, but a framed reply can carry it as
            // its first byte with trailing data; either way the printer refused
            // the handshake on this interface.
            if (enterReply.firstOrNull() == 0x15.toByte()) {
                throw ConnectException(ConnectFailure.DOT4_NAK)
            }
            if (enterReply.size < 8) {
                throw ConnectException(ConnectFailure.DOT4_NO_REPLY)
            }
            log("Entered IEEE 1284.4 mode")

            sendD4Command(session, protocol, 0x00, protocol.buildInitCommandPayload())
            log("D4 Init completed")
            val socketReply = sendD4Command(session, protocol, 0x09, protocol.buildGetSocketIdPayload("EPSON-CTRL"))
            val ssid = socketReply.firstOrNull()?.toInt()?.and(0xFF) ?: error("Missing EPSON-CTRL SSID")
            log("EPSON-CTRL socket ID = $ssid")

            val openReply = sendD4Command(session, protocol, 0x01, protocol.buildOpenChannelPayload(ssid))
            require(openReply.size >= 8) { "Invalid open channel response" }
            val psid = openReply[0].toInt() and 0xFF
            val mtu = ((openReply[2].toInt() and 0xFF) shl 8) or (openReply[3].toInt() and 0xFF)
            val credit = ((openReply[6].toInt() and 0xFF) shl 8) or (openReply[7].toInt() and 0xFF)
            val channel = EpsonProtocol.D4ChannelState(psid = psid, ssid = ssid, mtu = mtu, txCredits = credit)
            log("Channel opened: psid=$psid ssid=$ssid mtu=$mtu credit=$credit")

            sendD4Command(session, protocol, 0x03, protocol.buildCreditPayload(psid, ssid, channel.rxCreditsMax))
            log("Initial D4 credit granted=${channel.rxCreditsMax}")

            val opened = openProfile(appContext, deviceId)
            if (opened == null) {
                val name = EpsonPrinterCatalog.modelName(deviceId)
                log("Model '${name ?: "?"}' is not in the catalog; keeping a read-only session")
                return ConnectionState.ReadOnly(
                    session = session,
                    protocol = protocol,
                    deviceId = deviceId,
                    backend = EpsonProtocol.ControlBackend.D4,
                    controlChannel = channel,
                    modelName = name,
                    usesMaintenanceBox = EpsonPrinterCatalog.usesMaintenanceBox(appContext, name),
                )
            }
            log(opened.logLine)
            return ConnectionState.Catalogued(
                session = session,
                spec = opened.spec,
                protocol = EpsonProtocol(opened.spec),
                deviceId = deviceId,
                backend = EpsonProtocol.ControlBackend.D4,
                controlChannel = channel,
                unverifiedLocal = opened.unverified,
            )
        } catch (t: Throwable) {
            session.close()
            throw t
        }
    }

    private fun enterDot4(session: UsbTransport.Session, protocol: EpsonProtocol): ByteArray {
        transport.drain(session)
        log("Transport drained before entering IEEE 1284.4 mode")
        transport.bulkWrite(session, protocol.buildEnterDot4Packet())
        // Two seconds, not one: printers that do answer can take a while to get
        // around to it (EWR measured handshakes past the one-second mark on
        // real hardware), and only dead interfaces pay the full window — the
        // working one answers in milliseconds either way.
        val enterChunks = transport.captureIncoming(session, windowMs = 2000, idleTimeoutMs = 120)
        if (enterChunks.isEmpty()) {
            log("1284.4 enter reply: <no data>")
        } else {
            enterChunks.forEachIndexed { index, chunk ->
                log(
                    "1284.4 RX chunk[$index] +${chunk.offsetMs}ms: ${
                        chunk.bytes.joinToString(" ") { "%02X".format(it) }
                    }",
                )
            }
        }
        val buffered = session.readBuffer.size
        val enterReply = if (buffered >= 8) {
            transport.bulkRead(session, 8, timeoutMs = 1)
        } else {
            session.readBuffer.take(buffered).toByteArray().also {
                repeat(buffered) { session.readBuffer.removeAt(0) }
            }
        }
        log("1284.4 enter aggregate: ${enterReply.joinToString(" ") { "%02X".format(it) }}")
        if (enterReply.size < 8) {
            log("1284.4 enter reply shorter than expected (${enterReply.size}/8)")
            // The printer did not answer the handshake on this interface, which
            // is what a wrong interface looks like — and the handshake reply is
            // the verdict, so there is no point paying the eight second D4 read
            // that used to follow. Saying so now is the difference between an
            // explained wait and an unexplained one; the interface that works
            // answers this handshake in about two milliseconds.
            onSlowConnect()
        }
        return enterReply
    }

    private suspend fun sendPrintJob(connection: ConnectionState, payload: ByteArray) {
        val currentSession = connection.session
        // ESC/P2 exits packet mode at the printer, even if the USB handle stays open.
        currentSession.controlModeInvalidated = true
        val currentIsPrinterInterface = currentSession.usbInterface.interfaceClass == UsbConstants.USB_CLASS_PRINTER
        if (currentIsPrinterInterface && currentSession.bulkOut != null) {
            transport.bulkWrite(currentSession, payload, timeoutMs = 6000)
            log("Print job sent on current interface ${currentSession.usbInterface.id}")
            return
        }

        val printSession = transport.openPrintSession(currentSession.device)
        try {
            log("Print job session opened on interface ${printSession.usbInterface.id}")
            transport.bulkWrite(printSession, payload, timeoutMs = 6000)
            log("Print job sent on printer interface ${printSession.usbInterface.id}")
        } finally {
            printSession.close()
        }
    }

    /** Reopen D4 after an ESC/P2 job without resending that job or resetting the printer. */
    private fun restoreControlMode(connection: ConnectionState) {
        if (!connection.session.controlModeInvalidated) return
        val session = connection.session
        val protocol = connection.protocol
        val enter = enterDot4(session, protocol)
        require(enter.size >= 8 && enter.first() != 0x15.toByte()) { "Printer did not reopen D4 after the print job" }
        sendD4Command(session, protocol, 0x00, protocol.buildInitCommandPayload())
        val socket = sendD4Command(session, protocol, 0x09, protocol.buildGetSocketIdPayload("EPSON-CTRL"))
        val ssid = socket.firstOrNull()?.toInt()?.and(0xFF) ?: error("Missing EPSON-CTRL socket")
        val reply = sendD4Command(session, protocol, 0x01, protocol.buildOpenChannelPayload(ssid))
        require(reply.size >= 8) { "Invalid reopened channel response" }
        val channel = requireNotNull(connection.controlChannel)
        channel.psid = reply[0].toInt() and 0xFF
        channel.ssid = ssid
        channel.mtu = ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF)
        require(channel.mtu > 6) { "Invalid D4 MTU" }
        channel.txCredits = ((reply[6].toInt() and 0xFF) shl 8) or (reply[7].toInt() and 0xFF)
        channel.rxCredits = 0
        sendD4Command(session, protocol, 0x03, protocol.buildCreditPayload(channel.psid, ssid, channel.rxCreditsMax))
        session.controlModeInvalidated = false
        log("D4 control session reopened after print job")
    }

    private fun genericCleaningGroup(cleaning: CleaningSpec): Int? {
        val name = cleaning.name.uppercase()
        return when {
            name.startsWith("ALL") -> 0
            name.startsWith("BLK") || name.contains("BLACK") -> 1
            name.startsWith("YMC") || name.contains("COLOR") || name.contains("COLOUR") -> 2
            else -> null
        }
    }

    private fun sendControlPayload(connection: ConnectionState, payload: ByteArray): ByteArray {
        require(!connection.session.controlModeInvalidated) { "D4 session needs renegotiation" }
        log("CTRL TX: ${payload.joinToString(" ") { "%02X".format(it) }}")
        val channel = requireNotNull(connection.controlChannel) { "D4 control channel missing" }
        val frames = connection.protocol.chunkForChannel(channel, payload)
        frames.forEach { frame ->
            ensureChannelCredit(connection)
            log("D4 TX frame psid=${frame.psid} ssid=${frame.ssid} credit=${frame.credit} control=${frame.control} len=${frame.payload.size}")
            transport.bulkWrite(connection.session, connection.protocol.encodeD4Frame(frame))
            channel.txCredits -= 1
        }
        val reply = readD4Reply(connection.session, connection.protocol, channel)
        channel.txCredits += reply.credit
        channel.rxCredits = (channel.rxCredits - 1).coerceAtLeast(0)
        log("CTRL RX: ${reply.payload.joinToString(" ") { "%02X".format(it) }}")
        return reply.payload
    }

    private fun sendD4Command(
        session: UsbTransport.Session,
        protocol: EpsonProtocol,
        command: Int,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        val frame = protocol.buildD4Command(command, payload)
        log("D4 CMD TX command=0x${command.toString(16)} payload=${payload.joinToString(" ") { "%02X".format(it) }}")
        transport.bulkWrite(session, protocol.encodeD4Frame(frame))
        val reply = readD4Reply(session, protocol)
        log("D4 CMD RX command=0x${command.toString(16)} payload=${reply.payload.joinToString(" ") { "%02X".format(it) }}")
        require(reply.payload.size >= 2) { "Truncated D4 command response" }
        require((reply.payload[0].toInt() and 0xFF) == (command or 0x80)) { "Unexpected D4 response for 0x${command.toString(16)}" }
        require((reply.payload[1].toInt() and 0xFF) == 0x00) { "D4 command returned error" }
        return reply.payload.copyOfRange(2, reply.payload.size)
    }

    private fun ensureChannelCredit(connection: ConnectionState) {
        val channel = requireNotNull(connection.controlChannel) { "D4 control channel missing" }
        if (channel.txCredits > 0) {
            return
        }

        log("TX credit exhausted, requesting more D4 credit")
        val reply = sendD4Command(
            connection.session,
            connection.protocol,
            0x04,
            connection.protocol.buildCreditPayload(channel.psid, channel.ssid, 0xFFFF),
        )
        require(reply.size >= 4) { "Invalid credit request response" }
        val granted = ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF)
        require(granted > 0) { "Printer granted no D4 credit" }
        channel.txCredits += granted
        log("D4 credit request granted=$granted")
    }

    private fun readD4Reply(
        session: UsbTransport.Session,
        protocol: EpsonProtocol,
        channel: EpsonProtocol.D4ChannelState? = null,
    ): EpsonProtocol.D4Frame {
        // The first read can land on stale stream bytes (spontaneous printer
        // output after print jobs): validate before trusting the length, drain
        // once, retry once, then fail loud with a cause instead of chasing a
        // garbage length into a bulk-read timeout.
        val first = transport.bulkRead(session, 6, timeoutMs = 8000)
        require(first.size == 6) { "Incomplete D4 header" }
        // Name the expected endpoint ids on a mismatch: if hardware ever
        // answers with asymmetric ids, the protocol log alone must show it —
        // a bare hex dump cannot tell a swapped channel from garbage.
        val expectedIds = channel?.let { "expected psid/ssid=${it.psid}/${it.ssid}, " } ?: ""
        val header = if (protocol.isPlausibleD4Header(first, channel)) {
            first
        } else {
            log("D4 RX header not plausible (${expectedIds}got ${first.joinToString(" ") { "%02X".format(it) }}); draining once and retrying")
            transport.drain(session)
            transport.bulkRead(session, 6, timeoutMs = 8000)
        }
        require(header.size == 6) { "Incomplete D4 header" }
        require(protocol.isPlausibleD4Header(header, channel)) {
            "D4 reply out of framing (${expectedIds}got ${header.joinToString(" ") { "%02X".format(it) }}); the printer may still be working through print jobs"
        }
        val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        log("D4 RX header: ${header.joinToString(" ") { "%02X".format(it) }}")
        val payload = if (length > 6) transport.bulkRead(session, length - 6, timeoutMs = 8000) else byteArrayOf()
        return protocol.decodeD4Frame(header + payload)
    }

    private fun parseEepromReadByte(payload: ByteArray): Byte {
        val expected = "@BDC PS\r\n".encodeToByteArray()
        require(payload.size >= 18 && payload.copyOfRange(0, expected.size).contentEquals(expected)) { "Unexpected EEPROM response" }
        val hex = payload.copyOfRange(16, 18).decodeToString()
        return hex.toInt(16).toByte()
    }
}
