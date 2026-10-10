package com.padreset.epadreset.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.os.Build.VERSION.SDK_INT
import com.padreset.epadreset.data.EpsonPrinterCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicInteger

/**
 * Broadcast action for one permission ask.
 *
 * A result is an ordinary broadcast. Every receiver registered for the same
 * action runs, so a shared action would resume every waiter with whichever
 * grant arrived first. The request code is part of the action, and of the
 * [PendingIntent], so two live asks cannot hear each other.
 */
internal fun usbPermissionAction(packageName: String, requestCode: Int): String =
    "$packageName.USB_PERMISSION.$requestCode"

class UsbTransport(private val context: Context) {
    private val permissionRequestCode = AtomicInteger(0)
    data class InterfaceInfo(
        val id: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val endpointDescriptions: List<String>,
    )

    data class ReadChunk(
        val offsetMs: Long,
        val bytes: ByteArray,
    )

    /**
     * FIFO for USB stream fragments; incomplete reads stay queued for the next call.
     *
     * A byte ring instead of a list of boxed bytes. The list boxed every byte on the
     * way in and shifted the whole queue on every read. Not thread-safe: every use
     * happens under MainViewModel.sessionMutex, and nothing else may touch a session.
     */
    class ReadBuffer {
        private var storage = ByteArray(INITIAL_CAPACITY)
        private var head = 0

        var size: Int = 0
            private set

        fun append(chunk: ByteArray) {
            ensureCapacity(size + chunk.size)
            val tail = (head + size) % storage.size
            val firstPart = minOf(chunk.size, storage.size - tail)
            System.arraycopy(chunk, 0, storage, tail, firstPart)
            System.arraycopy(chunk, firstPart, storage, 0, chunk.size - firstPart)
            size += chunk.size
        }

        fun take(count: Int): ByteArray {
            require(count >= 0) { "Read count cannot be negative" }
            require(count <= size) { "Insufficient buffered bytes: requested=$count available=$size" }
            val result = ByteArray(count)
            val firstPart = minOf(count, storage.size - head)
            System.arraycopy(storage, head, result, 0, firstPart)
            System.arraycopy(storage, 0, result, firstPart, count - firstPart)
            size -= count
            head = if (size == 0) 0 else (head + count) % storage.size
            return result
        }

        fun clear() {
            head = 0
            size = 0
        }

        private fun ensureCapacity(required: Int) {
            if (required <= storage.size) return
            var capacity = storage.size
            while (capacity < required) capacity *= 2
            val grown = ByteArray(capacity)
            val firstPart = minOf(size, storage.size - head)
            System.arraycopy(storage, head, grown, 0, firstPart)
            System.arraycopy(storage, 0, grown, firstPart, size - firstPart)
            storage = grown
            head = 0
        }

        private companion object {
            const val INITIAL_CAPACITY = 1024
        }
    }

    /**
     * The device opened but its interface is held elsewhere — on Android
     * typically a print service or another printer app. Carries the open
     * connection so the caller can release it instead of leaking it.
     */
    class UsbClaimException(
        val connection: UsbDeviceConnection,
        val interfaceId: Int,
    ) : Exception("USB interface $interfaceId is held by another app")

    data class Session(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint?,
        val bulkOut: UsbEndpoint?,
        val readBuffer: ReadBuffer = ReadBuffer(),
        var controlModeInvalidated: Boolean = false,
    ) {
        /**
         * Best effort, single swallowing layer: release must never throw past
         * a close, and close itself runs even when release failed. Failures are
         * reported once via [onError] (nothing actionable — retrying a release
         * is meaningless) instead of propagating, so callers must NOT wrap this
         * in another `runCatching`.
         */
        fun close(onError: (Throwable) -> Unit = {}) {
            runCatching { connection.releaseInterface(usbInterface) }
                .onFailure(onError)
            runCatching { connection.close() }
                .onFailure(onError)
        }
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * Which USB interface last worked for a given printer, kept across runs.
     *
     * A printer exposes more than one interface with bulk endpoints, and which
     * one carries D4 varies by model: an L365 answers on its printer-class
     * interface and ignores the vendor-specific one that the default order
     * tries first. Getting it wrong is not fatal, because the next candidate is
     * tried, but the failed attempt has to run into its read timeouts before
     * that happens, and the user spends those seconds watching a spinner.
     *
     * Keyed on vendor and product id rather than on the device path, which
     * changes every time the printer is unplugged and plugged back in. A
     * preference keyed on the path would never be found again.
     */
    private val interfacePreference: SharedPreferences =
        context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    fun findEpsonDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.filter { it.vendorId == EpsonPrinterCatalog.EPSON_VENDOR_ID }
    }

    /**
     * USB devices that advertise themselves as printers but are not Epson.
     *
     * The Epson side is matched by vendor ID, but a printer from another vendor
     * cannot be: there is no "other vendor" constant. The USB-IF printer class
     * is the closest thing to one, so a device carrying a non-Epson vendor ID
     * and a printer-class interface is what the app has to explain instead of
     * silently ignoring.
     */
    fun findPrintersFromOtherVendors(): List<UsbDevice> {
        return usbManager.deviceList.values.filter { device ->
            device.vendorId != EpsonPrinterCatalog.EPSON_VENDOR_ID && device.hasPrinterInterface()
        }
    }

    private fun UsbDevice.hasPrinterInterface(): Boolean =
        (0 until interfaceCount).any { index ->
            getInterface(index).interfaceClass == UsbConstants.USB_CLASS_PRINTER
        }

    /**
     * Whether the device is still on the bus.
     *
     * A detached `UsbDevice` is still a usable-looking object — it can be passed
     * around and asked for its fields — but nothing it is asked to do will ever
     * answer. Bulk transfers return -1, and the permission broadcast never
     * arrives. Asking the manager for the current list is the only reliable way
     * to tell, and it is what stops a detached printer from becoming a spinner
     * that never stops.
     */
    fun isAttached(device: UsbDevice): Boolean =
        usbManager.deviceList.values.any { it.deviceName == device.deviceName }

    /**
     * Asks for permission to open the device, giving up if nothing answers.
     *
     * The wait is bounded because the broadcast that would end it is only sent
     * while the device is attached. Unbounded, unplugging the printer while a
     * job was starting left the app waiting for a dialog that could never
     * arrive — which is exactly what a nozzle check did.
     */
    suspend fun requestPermission(
        device: UsbDevice,
        timeoutMs: Long = PERMISSION_TIMEOUT_MS,
    ): Boolean {
        if (usbManager.hasPermission(device)) return true
        if (!isAttached(device)) return false
        return withTimeoutOrNull(timeoutMs) { awaitPermission(device) } ?: false
    }

    private suspend fun awaitPermission(device: UsbDevice): Boolean = suspendCancellableCoroutine { continuation ->
        if (usbManager.hasPermission(device)) {
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        val requestCode = permissionRequestCode.incrementAndGet()
        val action = usbPermissionAction(context.packageName, requestCode)
        // UsbManager fills EXTRA_PERMISSION_GRANTED into this intent. An
        // immutable PendingIntent drops that fill-in on API 31+, so the grant
        // never arrives. The intent is explicit (setPackage) because a mutable
        // implicit broadcast is rejected. The action includes the request code,
        // so a result wakes only the receiver that asked.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val explicit = Intent(action).setPackage(context.packageName)
        val intent = PendingIntent.getBroadcast(
            context,
            requestCode,
            explicit,
            flags,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, receivedIntent: Intent?) {
                if (receivedIntent?.action != action) return
                // The timeout path unregisters too. A second unregister, or a
                // resume of an already-finished continuation, must not crash
                // the process from inside onReceive.
                runCatching { context.unregisterReceiver(this) }
                if (!continuation.isActive) return
                val granted = receivedIntent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                runCatching { continuation.resume(granted) }
            }
        }
        if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(action))
        }
        continuation.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
        }
        try {
            usbManager.requestPermission(device, intent)
        } catch (failure: Exception) {
            runCatching { context.unregisterReceiver(receiver) }
            intent.cancel()
            throw failure
        }
    }

    suspend fun ensurePermission(device: UsbDevice, settleDelayMs: Long = 250): Boolean {
        if (usbManager.hasPermission(device)) {
            return true
        }
        if (!isAttached(device)) {
            return false
        }

        val granted = requestPermission(device)
        if (granted && usbManager.hasPermission(device)) {
            return true
        }

        repeat(4) {
            delay(settleDelayMs)
            if (!isAttached(device)) {
                return false
            }
            if (usbManager.hasPermission(device)) {
                return true
            }
        }

        return usbManager.hasPermission(device)
    }

    /**
     * Fails with the reason permission was not granted.
     *
     * The two cases read the same to `ensurePermission` and mean opposite things
     * to the user, so the message has to tell them apart: one is a dialog they
     * have to accept, the other is a printer that is no longer there.
     */
    private fun permissionFailure(device: UsbDevice): Nothing = throw IllegalStateException(
        if (isAttached(device)) "USB permission denied" else "Printer is no longer connected",
    )

    suspend fun open(device: UsbDevice): Session = withContext(Dispatchers.IO) {
        if (!ensurePermission(device)) permissionFailure(device)
        val usbInterface = findPrinterInterface(device) ?: error("Printer interface not found")
        openOnInterface(device, usbInterface, permissionAlreadyGranted = true)
    }

    suspend fun openPrintSession(device: UsbDevice): Session = withContext(Dispatchers.IO) {
        if (!ensurePermission(device)) permissionFailure(device)
        val usbInterface = findPrintInterface(device) ?: error("Printer bulk OUT interface not found")
        openOnInterface(device, usbInterface, permissionAlreadyGranted = true)
    }

    suspend fun openOnInterface(
        device: UsbDevice,
        usbInterface: UsbInterface,
        permissionAlreadyGranted: Boolean = false,
    ): Session = withContext(Dispatchers.IO) {
        if (!permissionAlreadyGranted) {
            if (!ensurePermission(device)) permissionFailure(device)
        }
        val connection = usbManager.openDevice(device) ?: error("Unable to open USB device")
        if (!connection.claimInterface(usbInterface, true)) {
            throw UsbClaimException(connection, usbInterface.id)
        }

        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) bulkIn = endpoint
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) bulkOut = endpoint
        }

        Session(device, connection, usbInterface, bulkIn, bulkOut)
    }

    fun listInterfaces(device: UsbDevice): List<InterfaceInfo> {
        return buildList {
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                val endpoints = buildList {
                    for (endpointIndex in 0 until usbInterface.endpointCount) {
                        val endpoint = usbInterface.getEndpoint(endpointIndex)
                        add(
                            "ep$endpointIndex addr=0x${endpoint.address.toString(16)} " +
                                "type=${endpoint.type} dir=${endpoint.direction} maxPacket=${endpoint.maxPacketSize}",
                        )
                    }
                }
                add(
                    InterfaceInfo(
                        id = usbInterface.id,
                        interfaceClass = usbInterface.interfaceClass,
                        interfaceSubclass = usbInterface.interfaceSubclass,
                        interfaceProtocol = usbInterface.interfaceProtocol,
                        endpointDescriptions = endpoints,
                    ),
                )
            }
        }
    }

    fun candidateInterfaces(device: UsbDevice): List<UsbInterface> {
        val candidates = mutableListOf<UsbInterface>()
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.hasBulkEndpoint(UsbConstants.USB_DIR_IN) &&
                usbInterface.hasBulkEndpoint(UsbConstants.USB_DIR_OUT)
            ) {
                candidates += usbInterface
            }
        }
        val preferredId = preferredInterfaceFor(device)
        return candidates.sortedWith(
            compareBy<UsbInterface> {
                when {
                    preferredId != null && it.id == preferredId -> 0
                    it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                        it.interfaceSubclass == 0xAA &&
                        it.interfaceProtocol == 0x01 -> 1
                    it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC -> 2
                    it.interfaceClass == UsbConstants.USB_CLASS_PRINTER -> 3
                    else -> 4
                }
            }.thenBy { it.id },
        )
    }

    fun rememberPreferredInterface(device: UsbDevice, interfaceId: Int) {
        interfacePreference.edit()
            .putInt(preferenceKey(device.vendorId, device.productId), interfaceId)
            .apply()
    }

    fun findPrintInterface(device: UsbDevice): UsbInterface? {
        val candidates = buildList {
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                if (usbInterface.hasBulkEndpoint(UsbConstants.USB_DIR_OUT)) {
                    add(usbInterface)
                }
            }
        }

        return candidates.minWithOrNull(
            compareBy<UsbInterface> {
                when {
                    it.interfaceClass == UsbConstants.USB_CLASS_PRINTER -> 0
                    it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC -> 1
                    else -> 2
                }
            }.thenBy { it.id },
        )
    }

    fun getDeviceId(session: Session, timeoutMs: Int = 2000): String {
        val buffer = ByteArray(1024)
        val requestType = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or USB_RECIPIENT_INTERFACE
        val read = session.connection.controlTransfer(
            requestType,
            REQUEST_GET_DEVICE_ID,
            0,
            session.usbInterface.id,
            buffer,
            buffer.size,
            timeoutMs,
        )
        require(read > 2) { "GET_DEVICE_ID failed" }
        val length = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        val payloadEnd = minOf(read, length + 2)
        return buffer.copyOfRange(2, payloadEnd).decodeToString().trimEnd('\u0000')
    }

    fun softReset(session: Session, timeoutMs: Int = 2000) {
        val requestType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIPIENT_INTERFACE
        session.connection.controlTransfer(
            requestType,
            REQUEST_SOFT_RESET,
            0,
            session.usbInterface.id,
            null,
            0,
            timeoutMs,
        )
    }

    fun bulkWrite(session: Session, data: ByteArray, timeoutMs: Int = 4000) {
        val endpoint = requireNotNull(session.bulkOut) { "Bulk OUT endpoint missing" }
        var offset = 0
        while (offset < data.size) {
            // One window per transfer. Copying the whole remainder each round trip
            // made a payload that needed many partial writes quadratic in its size.
            val window = data.copyOfRange(offset, minOf(data.size, offset + BULK_WRITE_WINDOW))
            val transferred = session.connection.bulkTransfer(endpoint, window, window.size, timeoutMs)
            require(transferred > 0) { "Bulk write failed: $transferred/${window.size}" }
            offset += transferred
        }
    }

    fun bulkRead(session: Session, expectedBytes: Int, timeoutMs: Int = 4000): ByteArray {
        val endpoint = requireNotNull(session.bulkIn) { "Bulk IN endpoint missing" }
        val packetSize = maxOf(endpoint.maxPacketSize, 64)
        val chunk = ByteArray(maxOf(packetSize, expectedBytes))
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        while (session.readBuffer.size < expectedBytes && SystemClock.elapsedRealtime() < deadline) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            val transferTimeout = remaining.coerceAtMost(250L).toInt().coerceAtLeast(1)
            val transferred = session.connection.bulkTransfer(endpoint, chunk, chunk.size, transferTimeout)
            when {
                transferred > 0 -> {
                    session.readBuffer.append(chunk.copyOf(transferred))
                }
                // Zero bytes without the call waiting out its timeout: pause like the error branch.
                transferred == 0 -> SystemClock.sleep(5)
                transferred < 0 -> {
                    check(isAttached(session.device)) { "Printer disconnected during USB read" }
                    // Some drivers fail immediately. Avoid a tight CPU loop until the deadline.
                    SystemClock.sleep(5)
                }
            }
        }

        require(session.readBuffer.size >= expectedBytes) {
            "Bulk read failed: expected=$expectedBytes buffered=${session.readBuffer.size}"
        }

        return session.readBuffer.take(expectedBytes)
    }

    fun bulkReadAvailable(session: Session, packetSize: Int = 16384, timeoutMs: Int = 250): ByteArray {
        val endpoint = requireNotNull(session.bulkIn) { "Bulk IN endpoint missing" }
        val buffer = ByteArray(packetSize)
        val transferred = session.connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        return if (transferred <= 0) {
            byteArrayOf()
        } else {
            buffer.copyOf(transferred)
        }
    }

    fun captureIncoming(
        session: Session,
        windowMs: Int = 1000,
        idleTimeoutMs: Int = 120,
        packetSize: Int = 16384,
    ): List<ReadChunk> {
        val endpoint = requireNotNull(session.bulkIn) { "Bulk IN endpoint missing" }
        val buffer = ByteArray(maxOf(packetSize, endpoint.maxPacketSize, 64))
        val chunks = mutableListOf<ReadChunk>()
        val startedAt = SystemClock.elapsedRealtime()
        var lastDataAt = startedAt

        while (SystemClock.elapsedRealtime() - startedAt < windowMs) {
            val transferred = session.connection.bulkTransfer(endpoint, buffer, buffer.size, idleTimeoutMs)
            if (transferred > 0) {
                val chunk = buffer.copyOf(transferred)
                session.readBuffer.append(chunk)
                chunks += ReadChunk(
                    offsetMs = SystemClock.elapsedRealtime() - startedAt,
                    bytes = chunk,
                )
                lastDataAt = SystemClock.elapsedRealtime()
                continue
            }

            // Zero bytes and failures both return at once. Without a pause either
            // one spun until the window closed, which is what the read loop was
            // fixed for and this loop was not.
            if (transferred <= 0) {
                if (transferred < 0) {
                    check(isAttached(session.device)) { "Printer disconnected during USB capture" }
                }
                SystemClock.sleep(5)
            }
            if (chunks.isNotEmpty() && SystemClock.elapsedRealtime() - lastDataAt >= idleTimeoutMs) {
                break
            }
        }

        return chunks
    }

    fun drain(session: Session, timeoutMs: Int = 50) {
        session.readBuffer.clear()
        val deadline = SystemClock.elapsedRealtime() + 2000
        var received = 0
        while (SystemClock.elapsedRealtime() < deadline && received < 262_144) {
            val chunk = bulkReadAvailable(session, timeoutMs = timeoutMs)
            if (chunk.isEmpty()) return
            received += chunk.size
        }
        error("USB drain exceeded its time or byte limit")
    }

    private fun UsbInterface.hasBulkEndpoint(direction: Int): Boolean =
        (0 until endpointCount).any { index ->
            val endpoint = getEndpoint(index)
            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction
        }

    private fun findPrinterInterface(device: UsbDevice): UsbInterface? {
        return candidateInterfaces(device).firstOrNull()
    }

    private fun preferredInterfaceFor(device: UsbDevice): Int? =
        interfacePreference.getInt(preferenceKey(device.vendorId, device.productId), NO_PREFERENCE)
            .takeIf { it != NO_PREFERENCE }

    private fun preferenceKey(vendorId: Int, productId: Int): String = "$vendorId:$productId"

    private companion object {
        const val USB_RECIPIENT_INTERFACE = 0x01
        const val REQUEST_GET_DEVICE_ID = 0
        const val REQUEST_SOFT_RESET = 2

        /** Largest slice handed to one bulkTransfer call; the rest waits for the next round. */
        const val BULK_WRITE_WINDOW = 16 * 1024

        /** Where [interfacePreference] is stored. */
        const val PREFERENCE_FILE = "usb_interface_preference"

        /** Returned by [SharedPreferences.getInt] when nothing is stored yet. */
        const val NO_PREFERENCE = -1

        /**
         * A backstop, not the usual path: a detached device is caught by
         * [isAttached] before the wait begins. This only covers a permission
         * dialog that was opened and then ignored.
         */
        const val PERMISSION_TIMEOUT_MS = 60_000L
    }
}
