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
import android.os.Build.VERSION.SDK_INT
import com.padreset.epadreset.data.EpsonPrinterCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class UsbTransport(private val context: Context) {
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

    data class Session(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint?,
        val bulkOut: UsbEndpoint?,
        val readBuffer: MutableList<Byte> = mutableListOf(),
    ) {
        fun close() {
            connection.releaseInterface(usbInterface)
            connection.close()
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

        val action = "${context.packageName}.USB_PERMISSION"
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val intent = PendingIntent.getBroadcast(context, 0, Intent(action), flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, receivedIntent: Intent?) {
                if (receivedIntent?.action == action) {
                    context.unregisterReceiver(this)
                    val granted = receivedIntent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    continuation.resume(granted)
                }
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
        usbManager.requestPermission(device, intent)
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
        require(connection.claimInterface(usbInterface, true)) { "Unable to claim interface" }

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
            val hasBulkIn = (0 until usbInterface.endpointCount).any { endpointIndex ->
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_IN
            }
            val hasBulkOut = (0 until usbInterface.endpointCount).any { endpointIndex ->
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
            }
            if (hasBulkIn && hasBulkOut) {
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
                val hasBulkOut = (0 until usbInterface.endpointCount).any { endpointIndex ->
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT
                }
                if (hasBulkOut) {
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
            val transferred = session.connection.bulkTransfer(
                endpoint,
                data.copyOfRange(offset, data.size),
                data.size - offset,
                timeoutMs,
            )
            require(transferred > 0) { "Bulk write failed: $transferred/${data.size - offset}" }
            offset += transferred
        }
    }

    fun bulkWriteChunked(
        session: Session,
        data: ByteArray,
        chunkSize: Int = 512,
        timeoutMs: Int = 4000,
    ) {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            var attempts = 0
            var written = false
            var currentChunkSize = end - offset
            while (!written && attempts < 4) {
                val chunk = data.copyOfRange(offset, offset + currentChunkSize)
                val result = runCatching {
                    bulkWrite(session, chunk, timeoutMs = timeoutMs)
                }
                if (result.isSuccess) {
                    written = true
                } else {
                    attempts += 1
                    currentChunkSize = maxOf(currentChunkSize / 2, session.bulkOut?.maxPacketSize ?: 64, 64)
                    if (currentChunkSize >= end - offset && attempts >= 4) {
                        throw result.exceptionOrNull() ?: error("Bulk write failed")
                    }
                }
            }
            require(written) { "Bulk write chunk failed" }
            offset = end
        }
    }

    fun bulkRead(session: Session, expectedBytes: Int, timeoutMs: Int = 4000): ByteArray {
        val endpoint = requireNotNull(session.bulkIn) { "Bulk IN endpoint missing" }
        val packetSize = maxOf(endpoint.maxPacketSize, 64)
        val chunk = ByteArray(maxOf(packetSize, expectedBytes))
        val deadline = System.currentTimeMillis() + timeoutMs

        while (session.readBuffer.size < expectedBytes && System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val transferTimeout = remaining.coerceAtMost(250L).toInt().coerceAtLeast(1)
            val transferred = session.connection.bulkTransfer(endpoint, chunk, chunk.size, transferTimeout)
            when {
                transferred > 0 -> {
                    repeat(transferred) { index ->
                        session.readBuffer += chunk[index]
                    }
                }
                transferred == 0 -> continue
                transferred < 0 -> continue
            }
        }

        require(session.readBuffer.size >= expectedBytes) {
            "Bulk read failed: expected=$expectedBytes buffered=${session.readBuffer.size}"
        }

        val read = session.readBuffer.take(expectedBytes).toByteArray()
        repeat(expectedBytes) {
            session.readBuffer.removeAt(0)
        }
        return read
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

    fun bulkReadUpTo(session: Session, maxBytes: Int, timeoutMs: Int = 2000): ByteArray {
        val endpoint = requireNotNull(session.bulkIn) { "Bulk IN endpoint missing" }
        val packetSize = maxOf(endpoint.maxPacketSize, 64)
        val collected = mutableListOf<Byte>()
        val chunk = ByteArray(maxOf(packetSize, maxBytes))
        val deadline = System.currentTimeMillis() + timeoutMs

        while (collected.size < maxBytes && System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val transferTimeout = remaining.coerceAtMost(250L).toInt().coerceAtLeast(1)
            val transferred = session.connection.bulkTransfer(endpoint, chunk, chunk.size, transferTimeout)
            if (transferred > 0) {
                repeat(minOf(transferred, maxBytes - collected.size)) { index ->
                    collected += chunk[index]
                }
            }
        }

        return collected.toByteArray()
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
        val startedAt = System.currentTimeMillis()
        var lastDataAt = startedAt

        while (System.currentTimeMillis() - startedAt < windowMs) {
            val transferred = session.connection.bulkTransfer(endpoint, buffer, buffer.size, idleTimeoutMs)
            if (transferred > 0) {
                val chunk = buffer.copyOf(transferred)
                chunk.forEach { session.readBuffer += it }
                chunks += ReadChunk(
                    offsetMs = System.currentTimeMillis() - startedAt,
                    bytes = chunk,
                )
                lastDataAt = System.currentTimeMillis()
                continue
            }

            if (chunks.isNotEmpty() && System.currentTimeMillis() - lastDataAt >= idleTimeoutMs) {
                break
            }
        }

        return chunks
    }

    fun drain(session: Session, timeoutMs: Int = 50) {
        session.readBuffer.clear()
        while (true) {
            val chunk = bulkReadAvailable(session, timeoutMs = timeoutMs)
            if (chunk.isEmpty()) {
                return
            }
        }
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
