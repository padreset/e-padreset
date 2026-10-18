package com.padreset.epadreset.data

/**
 * A backup the user handed back as text.
 *
 * [model] is null when the text named no printer — a paste of just the address
 * lines, or a header the app could not read. That is not an error: the restore
 * can still go ahead, it just cannot check the model, and the confirmation says so.
 */
data class ParsedBackup(
    val model: String?,
    val values: Map<Int, Byte>,
    val unitId: String? = null,
)

/**
 * Reads a backup out of text the user pasted.
 *
 * The input is whatever the status page's copy button produced, which is why the
 * address grammar here is the same one [BackupCodec] writes and the same one the
 * panel displays. Three shapes are accepted, because a backup can reach this
 * point by three routes:
 *
 * - the copied text, header and all;
 * - just the address lines, for someone who kept only those;
 * - a stored block, if a record is ever exported by hand.
 *
 * Anything else is refused rather than guessed at: writing the wrong bytes to the
 * wrong address is the one outcome worth being strict about.
 */
internal object BackupTextParser {
    /**
     * The header the status page writes above the address lines.
     *
     * Matched as a prefix because the rest of the line is the model name, which
     * is exactly what is wanted from it.
     */
    private const val HEADER_PREFIX = "EEPROM backup taken before the write"

    private const val MODEL_PREFIX = "model="

    /**
     * A ceiling on how much text is examined.
     *
     * A paste is user input and can be arbitrarily large; the largest real record
     * is a few dozen lines. This stops a pathological paste from being parsed
     * line by line for no benefit.
     */
    private const val MAX_LINES = 4096

    private val ADDRESS_LINE =
        Regex("""^\s*0x([0-9A-Fa-f]{1,4})\s*=\s*0x([0-9A-Fa-f]{1,2})\s*$""")

    /** Null when the text holds no usable address line. */
    fun parse(text: String): ParsedBackup? {
        if (text.isBlank() || text.length > 131_072) return null
        val lines = text.lines()
        if (lines.size > MAX_LINES) return null
        val magic = lines.count { it.trim() == BackupCodec.MAGIC }
        if (magic > 1) return null
        if (magic == 1) {
            val records = BackupCodec.decodeHistory(text)
            val record = records.singleOrNull() ?: return null
            return ParsedBackup(record.model.asModelName(), record.values, record.unitId)
        }

        var model: String? = null
        var unitId: String? = null
        var declaredCount: Int? = null
        val values = linkedMapOf<Int, Byte>()

        var headerSeen = false
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Unit

                trimmed == BackupCodec.MAGIC -> Unit

                trimmed.startsWith(HEADER_PREFIX) -> {
                    if (headerSeen) return null
                    headerSeen = true
                    model = modelFromHeader(trimmed)
                }

                trimmed.startsWith(MODEL_PREFIX) -> {
                    if (headerSeen) return null
                    headerSeen = true
                    model = BackupCodec.unescape(trimmed.substring(MODEL_PREFIX.length)).trim().asModelName()
                }

                trimmed.startsWith("unit=") -> {
                    val candidate = BackupCodec.unescape(trimmed.substring(5)).trim()
                    if (candidate.isNotEmpty()) {
                        unitId = candidate
                    }
                }

                trimmed.startsWith("count=") -> {
                    val count = trimmed.substring(6).trim().toIntOrNull() ?: return null
                    declaredCount = count
                }

                trimmed.startsWith("time=") ||
                trimmed.startsWith("sha256=") ||
                trimmed.startsWith("origin=") -> Unit

                else -> {
                    val match = ADDRESS_LINE.matchEntire(line) ?: return null
                    val address = match.groupValues[1].toInt(16)
                    val value = match.groupValues[2].toInt(16)
                    if (address in 0..0xFFFF && value in 0..0xFF) {
                        if (values[address]?.let { (it.toInt() and 0xFF) != value } == true) return null
                        values[address] = value.toByte()
                    }
                }
            }
        }

        if (values.isEmpty()) return null
        if (declaredCount != null && declaredCount != values.size) return null
        return ParsedBackup(model = model, values = values, unitId = unitId)
    }

    /**
     * The model name after the em dash.
     *
     * Only the em dash the app itself writes is accepted. A hyphen would be
     * wrong: model names contain them ("ET-2820"), so treating one as a separator
     * would read a printer as its own prefix. When there is no em dash the model
     * stays unknown, which the confirmation surfaces rather than hides.
     */
    private fun modelFromHeader(line: String): String? {
        val separator = line.indexOf('—')
        if (separator < 0) return null
        // The space after the dash is part of the header's punctuation, not the
        // model name. Without the trim the record would carry a model that never
        // equals the one the printer reports, and the guard would block a printer
        // it should have accepted.
        return line.substring(separator + 1).trim().asModelName()
    }

    /** Blank and the app's own placeholder both mean "no model named". */
    private fun String.asModelName(): String? =
        takeIf { it.isNotEmpty() && !it.equals("unknown model", ignoreCase = true) }
}
