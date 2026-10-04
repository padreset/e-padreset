package com.padreset.epadreset.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Which write found the state a record holds.
 *
 * The bytes are the pre-write state; the origin says which write found them.
 * Without it the restore list shows two records that look identical and gives the
 * user no way to tell which one undoes the mistake they just made.
 */
enum class BackupOrigin {
    /** The state found before a counter reset. */
    RESET,

    /** The state found before a restore was written back. */
    RESTORE,

    /**
     * Written by a build that did not record the origin — every record saved
     * before this field existed. Never chosen for a new record; only read, and
     * never written back, so a block with no origin line stays as it was.
     */
    UNKNOWN,
}

/**
 * One write's pre-write EEPROM state, as it is kept on disk.
 *
 * [timestamp] is the record's identity and its sort key, and it means when this
 * state was first captured. [model] is the name the catalog resolved for the
 * printer the bytes came from, and it is what the restore guard compares against;
 * it is blank only for a record built from text the user pasted without a header.
 */
data class WasteBackupRecord(
    val timestamp: Long,
    val model: String,
    val values: Map<Int, Byte>,
    val origin: BackupOrigin = BackupOrigin.UNKNOWN,
)

/**
 * Whether a record's model may be restored onto the connected printer.
 *
 * Compared through the catalog's own normalisation, because that is the
 * comparison the catalog makes when it resolves a printer. A second
 * implementation would drift from it, and a drifted comparison would either block
 * a printer that matches or — worse — allow one that does not.
 *
 * Shared because two callers need it and they must not disagree: the restore
 * guard, and the list row that decides whether a record's counter reading can be
 * shown at all.
 */
internal fun backupModelMatches(recordModel: String?, connectedModel: String): Boolean {
    if (recordModel.isNullOrBlank()) return true
    return EpsonPrinterCatalog.normalizeModelName(recordModel) ==
        EpsonPrinterCatalog.normalizeModelName(connectedModel)
}

/**
 * The one thing [BackupStore] needs from its backing store.
 *
 * The interface exists so the store can be driven by a fake on the JVM, for the
 * same reason [com.padreset.epadreset.service.WasteWriter] takes its transport as
 * lambdas: the logic worth testing is the ordering, the trimming and the parsing,
 * and none of those need an Android device.
 */
internal interface BackupStorage {
    fun read(): String?

    fun write(blob: String)
}

/**
 * The production backing store: a private preferences file.
 *
 * `MODE_PRIVATE` needs no permission and no manifest entry, which is the whole
 * reason this feature can exist in an app that declares none. The write callback runs on the service IO dispatcher before EEPROM changes.
 * Commit synchronously there so a queued preferences write is not mistaken for
 * a backup already on disk. A storage failure is still logged by [BackupStore].
 */
private class SharedPreferencesBackupStorage(
    private val preferences: SharedPreferences,
) : BackupStorage {
    override fun read(): String? = preferences.getString(BackupStore.KEY_HISTORY, null)

    override fun write(blob: String) {
        check(preferences.edit().putString(BackupStore.KEY_HISTORY, blob).commit()) {
            "Preferences did not commit the EEPROM backup"
        }
    }
}

/**
 * The text format the history is stored in.
 *
 * Hand-rolled rather than JSON on purpose. The module has no JSON library and no
 * `testOptions { unitTests.isReturnDefaultValues = true }`, so `org.json` — which
 * is part of the framework, not the language — would throw "Method not mocked" in
 * every JVM test and make this codec unverifiable without a device. Plain text
 * keeps encode and decode pure Kotlin, testable exactly like the XML parsers.
 *
 * A record is a magic line followed by the model, the time and one line per
 * address:
 *
 * ```
 * #EPADRESET-BACKUP v1
 * model=L365
 * time=1759500000000
 * 0x001C = 0x3F
 * ```
 *
 * The address line is deliberately the same shape the status page already shows
 * and copies (`0x%04X = 0x%02X`), so what a user pastes back parses with the same
 * grammar it was produced with.
 */
internal object BackupCodec {
    const val MAGIC = "#EPADRESET-BACKUP v1"

    private const val MODEL_PREFIX = "model="
    private const val TIME_PREFIX = "time="

    /**
     * The origin line, and the two tokens it may carry.
     *
     * Optional on purpose. `decodeRecord` already skips any line it does not
     * recognise, so a record written without one decodes as [BackupOrigin.UNKNOWN]
     * and a record written with one is read by a build that does not know the line
     * as a record with no origin. That is what lets this be added without touching
     * [MAGIC] — and MAGIC must not change: `decodeHistory` splits the blob on it,
     * so a new magic string would make every block already on the device fail to
     * start and take the whole history with it.
     */
    private const val ORIGIN_PREFIX = "origin="
    private const val ORIGIN_RESET = "reset"
    private const val ORIGIN_RESTORE = "restore"

    private val ADDRESS_LINE =
        Regex("""^\s*0x([0-9A-Fa-f]{1,4})\s*=\s*0x([0-9A-Fa-f]{1,2})\s*$""")

    fun encodeHistory(records: List<WasteBackupRecord>): String =
        records.joinToString("\n") { encodeRecord(it) }

    fun encodeRecord(record: WasteBackupRecord): String = buildString {
        appendLine(MAGIC)
        appendLine(MODEL_PREFIX + escape(record.model))
        appendLine(TIME_PREFIX + record.timestamp)
        when (record.origin) {
            BackupOrigin.RESET -> appendLine(ORIGIN_PREFIX + ORIGIN_RESET)
            BackupOrigin.RESTORE -> appendLine(ORIGIN_PREFIX + ORIGIN_RESTORE)
            // Nothing. A record whose origin is unknown encodes to exactly the
            // bytes a build without this field would have written, so the two
            // stay interchangeable in both directions.
            BackupOrigin.UNKNOWN -> Unit
        }
        record.values.entries.sortedBy { it.key }.forEach { (address, value) ->
            appendLine(addressLine(address, value))
        }
    }.trimEnd()

    /**
     * Reads back whatever is usable, and never throws.
     *
     * A blob that has been truncated, corrupted or written by a future version
     * must not take the history down with it: each block is decoded on its own and
     * a block that cannot be trusted is dropped. Losing one record is survivable;
     * losing the list, or crashing on it, is not.
     */
    fun decodeHistory(blob: String?): List<WasteBackupRecord> {
        if (blob.isNullOrBlank()) return emptyList()

        val blocks = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in blob.lines()) {
            if (line.trim() == MAGIC) {
                current = mutableListOf<String>().also { blocks += it }
            } else {
                current?.add(line)
            }
        }

        return blocks.mapNotNull { block -> runCatching { decodeRecord(block) }.getOrNull() }
    }

    /** Null when the block carries no usable time or no address at all. */
    fun decodeRecord(block: List<String>): WasteBackupRecord? {
        var model = ""
        var timestamp: Long? = null
        var origin = BackupOrigin.UNKNOWN
        val values = linkedMapOf<Int, Byte>()

        for (line in block) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Unit

                trimmed.startsWith(MODEL_PREFIX) ->
                    model = unescape(trimmed.substring(MODEL_PREFIX.length))

                trimmed.startsWith(TIME_PREFIX) ->
                    timestamp = trimmed.substring(TIME_PREFIX.length).trim().toLongOrNull()

                trimmed.startsWith(ORIGIN_PREFIX) -> {
                    origin = when (trimmed.substring(ORIGIN_PREFIX.length).trim().lowercase()) {
                        ORIGIN_RESET -> BackupOrigin.RESET
                        ORIGIN_RESTORE -> BackupOrigin.RESTORE
                        // A token from a future build, or a mangled line. The
                        // record is still usable; only its label is missing.
                        else -> BackupOrigin.UNKNOWN
                    }
                }

                else -> {
                    val match = ADDRESS_LINE.matchEntire(line) ?: continue
                    val address = match.groupValues[1].toInt(16)
                    val value = match.groupValues[2].toInt(16)
                    if (address in 0..0xFFFF && value in 0..0xFF) {
                        values[address] = value.toByte()
                    }
                }
            }
        }

        val time = timestamp ?: return null
        if (values.isEmpty()) return null
        return WasteBackupRecord(timestamp = time, model = model, values = values, origin = origin)
    }

    private fun addressLine(address: Int, value: Byte): String =
        "0x%04X = 0x%02X".format(address, value.toInt() and 0xFF)

    /** A model name may contain `=`, a backslash or a line break, and must survive. */
    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun unescape(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '\\' && index + 1 < text.length) {
                when (text[index + 1]) {
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(character)
                }
                index += 2
            } else {
                append(character)
                index += 1
            }
        }
    }
}

/**
 * The saved pre-write backups, newest first.
 *
 * The app writes these itself, before every EEPROM write, because the mistake a
 * backup undoes is almost never noticed at the moment it is made: it is noticed
 * later, usually with the app already closed. A backup that only lived in memory
 * would be gone by then, which is why persistence is not a convenience here but
 * the point.
 *
 * Nothing in here may throw or block a write. A store that cannot be read or
 * written logs and returns what it has: the printer write is the operation the
 * user asked for, and losing a record is survivable where aborting a reset is not.
 */
/**
 * What [BackupStore.save] did.
 *
 * [deduplicated] is true when the state was already held under the same model, so
 * nothing was added. The caller logs it, because a restore that produces no new
 * row would otherwise look like a save that failed.
 */
data class BackupSaveOutcome(
    val history: List<WasteBackupRecord>,
    val deduplicated: Boolean,
)

class BackupStore internal constructor(
    private val storage: BackupStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    /** Everything readable, newest first. Empty when there is nothing or it is corrupt. */
    fun history(): List<WasteBackupRecord> =
        runCatching { BackupCodec.decodeHistory(storage.read()) }
            .onFailure { log("Backup history unreadable: ${it.message}") }
            .getOrDefault(emptyList())

    /**
     * Records [values] for [model] and returns the new history.
     *
     * The new record is prepended and the list is trimmed to [MAX_RECORDS], so the
     * oldest is dropped exactly when a sixth distinct state arrives.
     *
     * A state that is already held under the same model is **not** recorded again.
     * Restoring a backup writes a value that is by definition already in the list,
     * and every such restore would otherwise add a row — so a reset, restore,
     * restore… sequence would fill the five slots with copies of the same two
     * states and push out the one the user actually needs. The states in that
     * sequence alternate, so skipping the repeat settles the list on the two that
     * matter instead of letting it churn.
     *
     * Nothing existing is touched when a duplicate arrives: no record is removed,
     * moved or re-stamped. A record's time and origin are facts about when and why
     * that state was captured, and re-writing them would quietly turn "the state
     * before my reset" into "the state before a restore" — losing the very label
     * the list is being given. The list can therefore only stay the same size or
     * grow, never shrink, and a duplicate can never evict a different record.
     *
     * The model is part of the identity because two different printers can
     * coincidentally hold identical bytes, and normalised because "L365" and
     * "L365 Series" are the same printer to the catalog.
     */
    fun save(
        model: String,
        values: Map<Int, Byte>,
        origin: BackupOrigin = BackupOrigin.UNKNOWN,
    ): BackupSaveOutcome {
        if (values.isEmpty()) return BackupSaveOutcome(history(), deduplicated = false)

        val existing = history()
        val normalized = EpsonPrinterCatalog.normalizeModelName(model)
        val duplicate = existing.any { record ->
            EpsonPrinterCatalog.normalizeModelName(record.model) == normalized &&
                record.values == values
        }
        if (duplicate) return BackupSaveOutcome(existing, deduplicated = true)

        val record = WasteBackupRecord(
            timestamp = nextTimestamp(existing),
            model = model,
            values = values,
            origin = origin,
        )
        val updated = (listOf(record) + existing).take(MAX_RECORDS)

        runCatching { storage.write(BackupCodec.encodeHistory(updated)) }
            .onFailure { log("Backup could not be saved: ${it.message}") }

        return BackupSaveOutcome(updated, deduplicated = false)
    }

    /**
     * A timestamp strictly newer than every record already held.
     *
     * The clock is not enough on its own: two writes inside the same millisecond
     * would collide, and the timestamp is the record's identity. Bumping past the
     * newest keeps the ordering total without a separate sequence.
     */
    private fun nextTimestamp(existing: List<WasteBackupRecord>): Long {
        val candidate = now()
        val newest = existing.maxOfOrNull { it.timestamp } ?: return candidate
        return if (candidate <= newest) newest + 1 else candidate
    }

    companion object {
        const val PREFERENCE_FILE = "waste_backup_history"
        const val KEY_HISTORY = "history"

        /**
         * How many records are kept.
         *
         * Enough to cover a bench session with more than one printer — which is
         * where restoring the wrong one actually happens — without turning the
         * store into a log nobody prunes.
         */
        const val MAX_RECORDS = 5

        fun from(context: Context): BackupStore = BackupStore(
            SharedPreferencesBackupStorage(
                context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE),
            ),
        )
    }
}
