package com.padreset.epadreset.service

import com.padreset.epadreset.protocol.EpsonProtocol

/**
 * A printer that answers from memory, so the write path can be driven from a desk.
 *
 * Shared by [WasteWriterTest] and [WasteRestoreTest] rather than copied into each:
 * the two exercise the same transport seam, and a fake that drifted between them
 * would let one suite's assumptions quietly stop matching the other's.
 *
 * The fake records every call in order, so assertions can be about the sequence
 * and not only the outcome. The order is the safety property — a backup that ran
 * after the first write would still produce a plausible-looking map.
 */
internal class FakePrinter(
    initial: Map<Int, Int> = emptyMap(),
    private val unreadable: Set<Int> = emptySet(),
    private val replies: Map<Int, EpsonProtocol.WriteReply> = emptyMap(),
    private val ignoredWrites: Set<Int> = emptySet(),
    private val failedWrites: Set<Int> = emptySet(),
    private val unreadableAfterWrite: Set<Int> = emptySet(),
    private val applyUnknown: Boolean = false,
) {
    val memory: MutableMap<Int, Byte> =
        initial.mapValues { (_, value) -> value.toByte() }.toMutableMap()
    val calls = mutableListOf<String>()
    val lines = mutableListOf<String>()
    private var writeStarted = false

    fun read(address: Int): Byte {
        calls += "read 0x%04X".format(address)
        if (address in unreadable || (writeStarted && address in unreadableAfterWrite)) {
            throw IllegalStateException("no answer for 0x%04X".format(address))
        }
        return memory[address] ?: 0x00
    }

    fun write(address: Int, value: Int): EpsonProtocol.WriteReply {
        calls += "write 0x%04X=0x%02X".format(address, value)
        writeStarted = true
        if (address in failedWrites) error("write timeout")
        val reply = replies[address] ?: EpsonProtocol.WriteReply.Applied
        val applied = reply == EpsonProtocol.WriteReply.Applied ||
            (applyUnknown && reply == EpsonProtocol.WriteReply.Unknown)
        if (address !in ignoredWrites && applied) memory[address] = value.toByte()
        return reply
    }

    fun writer() = WasteWriter(::read, ::write) { line -> lines += line }
}
