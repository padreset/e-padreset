package com.padreset.epadreset.data

import java.util.Locale

/**
 * A deliberately limited report, not a redacted copy of an arbitrary protocol log.
 * Logs can contain serial numbers in plain text, hex packets or old sessions.
 * Only the four catalog-relevant IEEE1284 fields and numeric USB IDs are included.
 */
internal object ModelSupportReport {
    const val ISSUE_TEMPLATE = "01-model-support.yml"
    private const val MAX_FIELD_LENGTH = 160
    private val allowedFields = listOf("MFG", "MDL", "CMD", "CLS")

    data class Report(val model: String, val body: String)

    /**
     * Everything a report needs, as one value.
     *
     * Six positional parameters of which three are strings is the shape that
     * gets two app versions transposed at the call site and nothing catches,
     * because `build(id, version, version, ...)` still compiles. Grouping them
     * makes the wrong call site a compile error.
     */
    data class Source(
        val deviceId: String,
        val appVersion: String,
        val androidVersion: String,
        val vendorId: Int?,
        val productId: Int?,
        val maintenanceBox: Boolean,
    )

    fun build(source: Source): Report {
        val fields = source.deviceId.split(';').mapNotNull { field ->
            val parts = field.split(':', limit = 2)
            val key = parts.first().trim().uppercase(Locale.ROOT)
            if (parts.size == 2 && key in allowedFields) key to safeField(parts[1]) else null
        }.toMap()
        val model = fields["MDL"].orEmpty().ifBlank { "Unknown Epson model" }
        val body = buildString {
            appendLine("E-Pad Reset — model support request / petición de modelo")
            appendLine("Printer model / Modelo: $model")
            appendLine("App version: ${safeField(source.appVersion)}")
            appendLine("Android: ${safeField(source.androidVersion)}")
            appendLine("USB VID/PID: ${usbId(source.vendorId)} / ${usbId(source.productId)}")
            appendLine("IEEE1284 (MFG, MDL, CMD, CLS only):")
            allowedFields.forEach { key -> fields[key]?.takeIf { it.isNotBlank() }?.let { appendLine("$key: $it") } }
            appendLine()
            appendLine("Result / Resultado: model not in the bundled catalog; maintenance session not opened.")
            if (source.maintenanceBox) {
                appendLine("Community catalog classification: maintenance box. Request maintenance support only;")
                appendLine("this app cannot reset a counter on the box chip / no reinicia el chip de la caja.")
            } else {
                appendLine("Maintenance box classification: not known. This does not prove the model has internal pads.")
            }
            appendLine("Support needs verified model data and hardware tests; it is not guaranteed.")
            appendLine()
            appendLine("Please complete / Completa lo que sepas:")
            appendLine("Wanted feature / Función: [read counters / reset / cleaning / nozzle check]")
            appendLine("Available to help / Puedes ayudar: [read-only tests / information only / not now]")
            appendLine("Commercial model name, if different / Nombre comercial distinto:")
            appendLine("Replaceable maintenance box / Caja reemplazable: [yes / no / unknown]")
            appendLine("Public source or tool supporting this model / Fuente pública o herramienta:")
            appendLine()
            appendLine("Privacy / Privacidad: no raw protocol log, serial number or EEPROM backup included.")
            appendLine("Review any text you add. Do not paste raw logs or perform experimental EEPROM writes.")
        }.trimEnd()
        return Report(model, body)
    }

    /** Opening the browser sends only this static template selection, never a report or model. */
    fun issueUrl(repositoryUrl: String): String =
        "${repositoryUrl.trimEnd('/')}/issues/new?template=$ISSUE_TEMPLATE"

    private fun safeField(text: String): String =
        text.filter { !it.isISOControl() && it !in "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069" }
            .trim().take(MAX_FIELD_LENGTH)

    private fun usbId(id: Int?): String =
        if (id != null && id in 0..0xFFFF) "0x%04X".format(id) else "unknown"
}
