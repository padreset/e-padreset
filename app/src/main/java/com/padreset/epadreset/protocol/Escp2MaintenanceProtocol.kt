package com.padreset.epadreset.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

object Escp2MaintenanceProtocol {
    private val exitPacketMode = byteArrayOf(
        0x00,
        0x00,
        0x00,
        0x1B,
        0x01,
    ) + "@EJL 1284.4\n@EJL     \n".encodeToByteArray()

    private val initializePrinter = byteArrayOf(0x1B, 0x40)
    private val enterRemoteMode = initializePrinter + initializePrinter + byteArrayOf(0x1B) + remoteCommand("(R", "\u0000REMOTE1".encodeToByteArray())
    private val exitRemoteMode = byteArrayOf(0x1B, 0x00, 0x00, 0x00)
    private val jobEnd = remoteCommand("JE", byteArrayOf(0x00))
    private val printNozzleCheck = remoteCommand("NC", byteArrayOf(0x00, 0x00))

    /** The same command with its last argument byte set, which leads the pair. */
    private val nozzleCheckFirstStep = remoteCommand("NC", byteArrayOf(0x00, 0x10))

    private val versionInformation = remoteCommand("VI", byteArrayOf(0x00, 0x00))
    private val loadCommand = remoteCommand("LD", byteArrayOf())
    private val carriageReturn = byteArrayOf('\r'.code.toByte())
    private val formFeed = byteArrayOf(0x0C)

    /**
     * The nozzle check, which is three commands in one session, not one.
     *
     * There is no "alternative" form. This sequence is what `escputil` sends —
     * captured to a file with `escputil --nozzle-check --raw-device`, which is
     * the only way to read its bytes without a printer — and what yusnel-rojas
     * sends, byte for byte, proven end to end on an ET-2820.
     *
     * The two `NC` commands are the reason this is worth a comment. Ircama's
     * `epson_escp2` exposes them as two selectable modes, `check_nozzles(type=0)`
     * and `check_nozzles(type=1)`, differing only in the last argument byte. This
     * app inherited that split, and it is wrong: the `00 10` form is the *first*
     * step of the sequence, not a variant of it. Sent alone it leaves the printer
     * mid-sequence — the panel lights up, waits for the rest and gives up without
     * a word, which is exactly what it did on an L365.
     */
    fun buildNozzleCheckJob(): ByteArray {
        return exitPacketMode +
            enterRemoteMode +
            versionInformation +
            nozzleCheckFirstStep +
            printNozzleCheck +
            buildJobTrailer(includeFormFeed = true)
    }

    fun buildHeadCleaningJob(
        groupIndex: Int,
        powerClean: Boolean = false,
        alternativeMode: Boolean = false,
    ): ByteArray {
        if (alternativeMode) {
            return initializePrinter + if (powerClean) {
                byteArrayOf(0x1B, 0x7C, 0x00, 0x06, 0x00, 0x19, 0x07, 0x84.toByte(), 0x7B, 0x42, 0x0A)
            } else {
                byteArrayOf(0x1B, 0x7C, 0x00, 0x06, 0x00, 0x19, 0x07, 0x84.toByte(), 0x7B, 0x42, 0x02)
            }
        }

        require(groupIndex in 0..5) { "Cleaning group index must be between 0 and 5" }
        var group = groupIndex
        if (powerClean) {
            group = group or 0x10
        }

        return exitPacketMode +
            enterRemoteMode +
            setTimer() +
            remoteCommand("CH", byteArrayOf(0x00, group.toByte())) +
            buildJobTrailer(includeFormFeed = false)
    }

    private fun buildJobTrailer(includeFormFeed: Boolean): ByteArray {
        val builder = ByteArray(0) +
            exitRemoteMode +
            initializePrinter +
            carriageReturn
        val flushBytes = if (includeFormFeed) formFeed else byteArrayOf()
        return builder +
            flushBytes +
            initializePrinter +
            enterRemoteMode.drop(initializePrinter.size) +
            loadCommand +
            exitRemoteMode +
            initializePrinter +
            enterRemoteMode.drop(initializePrinter.size) +
            loadCommand +
            jobEnd +
            exitRemoteMode
    }

    // Calendar is available on Android 7 (minSdk 24); java.time requires API 26.
    internal fun setTimer(now: Calendar = Calendar.getInstance()): ByteArray {
        val payload = ByteArray(8)
        payload[0] = 0x00
        ByteBuffer.wrap(payload, 1, 2).order(ByteOrder.BIG_ENDIAN).putShort(now.get(Calendar.YEAR).toShort())
        payload[3] = (now.get(Calendar.MONTH) + 1).toByte()
        payload[4] = now.get(Calendar.DAY_OF_MONTH).toByte()
        payload[5] = now.get(Calendar.HOUR_OF_DAY).toByte()
        payload[6] = now.get(Calendar.MINUTE).toByte()
        payload[7] = now.get(Calendar.SECOND).toByte()
        return remoteCommand("TI", payload)
    }

    private fun remoteCommand(command: String, args: ByteArray): ByteArray {
        require(command.length == 2) { "Remote command must be 2 characters" }
        val length = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(args.size.toShort()).array()
        return command.encodeToByteArray() + length + args
    }
}
