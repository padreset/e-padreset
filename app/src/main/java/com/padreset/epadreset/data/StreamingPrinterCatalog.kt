package com.padreset.epadreset.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/** Parses bundled printer data incrementally using Android's XML pull parser. */
internal object StreamingPrinterCatalog {
    private val normalizeModel = EpsonPrinterCatalog::normalizeModelName

    private data class PrinterRecord(val model: String, val specs: List<String>, val brand: String)
    private data class SpecData(
        val controlModel: ByteArray,
        val keyword: ByteArray,
        val counters: List<WasteCounterSpec>,
        val resetMap: Map<Int, Int>,
        val cleaning: List<CleaningSpec>,
        val cleaningDeclared: Boolean,
    )

    fun parseDevices(input: InputStream, maintenance: Set<String>): List<PrinterSpec> {
        val parser = newParser(input)
        val printers = mutableListOf<PrinterRecord>()
        val specs = linkedMapOf<String, SpecData>()
        var devicesSeen = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "devices" -> if (!devicesSeen) {
                    devicesSeen = true
                    parser.forEachChild("devices") { node ->
                        val specName = node.name
                        if (specName !in specs) specs[specName] = parseSpec(node, specName)
                        else node.skipElement()
                    }
                }
                "printer" -> {
                    printers += PrinterRecord(
                        parser.attr("model").trim(),
                        parser.attr("specs").split(',').map(String::trim).filter(String::isNotEmpty),
                        parser.attr("brand"),
                    )
                    parser.skipElement()
                }
            }
        }
        return printers.mapNotNull { buildPrinter(it, specs, maintenance) }.distinctBy { it.modelName }
    }

    fun parseReinkpy(input: InputStream, maintenance: Set<String>): List<PrinterSpec> {
        val parser = newParser(input)
        val result = mutableListOf<PrinterSpec>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "model") continue
            val model = parser.attr("name").trim()
            val readKey = parser.attr("rkey").trim().toIntOrNull()
            val keyword = hexBytes(parser.attr("keyword"))
            val counters = mutableListOf<WasteCounterSpec>()
            val reset = linkedMapOf<Int, Int>()
            var padIndex = 0
            parser.forEachChild("model") { pad ->
                if (pad.name == "pad") {
                    val kind = pad.attr("kind")
                    val addresses = decimalValues(pad.attr("addresses"))
                    val resetValues = decimalValues(pad.attr("reset"))
                    if (addresses.isNotEmpty()) {
                        addresses.forEachIndexed { i, address -> resetValues.getOrNull(i)?.let { reset[address] = it } }
                        counters += WasteCounterSpec(counterName(kind, padIndex), addresses, 0, kind = kind.trim().lowercase())
                    }
                    padIndex++
                }
                pad.skipElement()
            }
            if (model.isNotEmpty() && readKey != null && keyword.isNotEmpty()) {
                result += PrinterSpec(
                    model,
                    byteArrayOf((readKey and 0xFF).toByte(), ((readKey shr 8) and 0xFF).toByte()),
                    keyword,
                    uniqueCounterNames(counters.distinctBy { it.name to it.addresses }),
                    reset,
                    emptyList(),
                    maintenance.contains(normalizeModel(model)),
                )
            }
        }
        return result.distinctBy { it.modelName }
    }

    fun parseMaintenanceBoxes(input: InputStream): Set<String> {
        val parser = newParser(input)
        val result = linkedSetOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "model") {
                normalizeModel(parser.readText("model")).takeIf(String::isNotEmpty)?.let(result::add)
            }
        }
        return result
    }

    private fun parseSpec(parser: XmlPullParser, tag: String): SpecData {
        var control = byteArrayOf()
        var keyword = byteArrayOf()
        val counters = mutableListOf<WasteCounterSpec>()
        val reset = linkedMapOf<Int, Int>()
        var cleaning = emptyList<CleaningSpec>()
        var serviceSeen = false
        var wasteSeen = false
        var cleaningDeclared = false
        parser.forEachChild(tag) { child ->
            when (child.name) {
                "service" -> if (!serviceSeen) {
                    serviceSeen = true
                    child.forEachChild("service") { field ->
                        when (field.name) {
                            "factory" -> if (control.isEmpty()) control = hexBytes(field.readText("factory")) else field.skipElement()
                            "keyword" -> if (keyword.isEmpty()) keyword = hexBytes(field.readText("keyword")) else field.skipElement()
                            else -> field.skipElement()
                        }
                    }
                } else child.skipElement()
                "waste" -> if (!wasteSeen) {
                    wasteSeen = true
                    val parts = parseWaste(child)
                    counters += parts.first
                    reset.putAll(parts.second)
                } else child.skipElement()
                "cleaning" -> if (!cleaningDeclared) {
                    cleaningDeclared = true
                    cleaning = parseCleaning(child)
                } else child.skipElement()
                else -> child.skipElement()
            }
        }
        return SpecData(control, keyword, counters, reset, cleaning, cleaningDeclared)
    }

    private fun parseWaste(parser: XmlPullParser): Pair<List<WasteCounterSpec>, Map<Int, Int>> {
        val counters = mutableListOf<WasteCounterSpec>()
        var reset = emptyMap<Int, Int>()
        var querySeen = false
        var resetSeen = false
        parser.forEachChild("waste") { child ->
            when (child.name) {
                "query" -> if (!querySeen) {
                    querySeen = true
                    var index = 0
                    child.forEachChild("query") { counter ->
                        if (counter.name == "counter") parseCounter(counter, index++)?.let(counters::add)
                        else counter.skipElement()
                    }
                } else child.skipElement()
                "reset" -> if (!resetSeen) {
                    resetSeen = true
                    reset = addressValuePairs(child.readDirectText())
                } else child.skipElement()
                else -> child.skipElement()
            }
        }
        return counters to reset
    }

    private fun parseCounter(parser: XmlPullParser, index: Int): WasteCounterSpec? {
        val depth = parser.depth
        val direct = StringBuilder()
        val entries = mutableListOf<Pair<String, Int?>>()
        var maxText: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF ->
                    if (parser.depth == depth) direct.append(parser.text.orEmpty()).append(' ')
                XmlPullParser.START_TAG -> if (parser.depth == depth + 1) {
                    when (parser.name) {
                        "entry" -> entries += parseEntry(parser)
                        "max" -> if (maxText == null) maxText = parser.readText("max") else parser.skipElement()
                        else -> parser.skipElement()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.depth == depth) break
                XmlPullParser.END_DOCUMENT -> error("Unexpected end of document inside <counter>")
            }
        }
        val addresses = mutableListOf<Int>()
        val masks = mutableMapOf<Int, Int>()
        if (entries.isEmpty()) {
            addresses += direct.toString().split(Regex("\\s+")).filter(String::isNotBlank)
                .map { numeric(it, "counter address $index") }
        } else {
            entries.forEach { (text, mask) ->
                val values = text.split(Regex("\\s+")).filter(String::isNotBlank)
                    .map { numeric(it, "counter address $index") }
                addresses += values
                if (mask != null && mask and 0xFF != 0xFF) values.forEach { masks[it] = mask }
            }
        }
        val maximum = maxText?.let { numeric(it.trim(), "counter maximum") } ?: 0
        if (addresses.isEmpty() || maximum <= 0) return null
        return WasteCounterSpec(defaultCounterName(index), addresses, maximum, masks)
    }

    private fun parseEntry(parser: XmlPullParser): Pair<String, Int?> {
        val depth = parser.depth
        val direct = StringBuilder()
        var filter: Int? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF ->
                    if (parser.depth == depth) direct.append(parser.text.orEmpty()).append(' ')
                XmlPullParser.START_TAG -> if (parser.depth == depth + 1) {
                    if (parser.name == "filter" && filter == null) filter = hexInts(parser.readText("filter")).firstOrNull()
                    else parser.skipElement()
                }
                XmlPullParser.END_TAG -> if (parser.depth == depth) return direct.toString() to filter
                XmlPullParser.END_DOCUMENT -> error("Unexpected end of document inside <entry>")
            }
        }
    }

    private fun parseCleaning(parser: XmlPullParser): List<CleaningSpec> {
        val result = mutableListOf<CleaningSpec>()
        parser.forEachChild("cleaning") { job ->
            if (job.name != "cleaning") {
                job.skipElement()
            } else {
                var type = ""
                var data = ""
                var name = ""
                val seen = mutableSetOf<String>()
                job.forEachChild("cleaning") { field ->
                    if (field.name in setOf("type", "data", "name") && seen.add(field.name)) {
                        val value = field.readText(field.name).trim()
                        when (field.name) {
                            "type" -> type = value
                            "data" -> data = value
                            "name" -> name = value
                        }
                    } else field.skipElement()
                }
                val payload = hexBytes(data)
                if (type.equals("service", ignoreCase = true) && payload.isNotEmpty()) {
                    result += CleaningSpec(name.ifBlank { "Head Cleaning" }, type, payload)
                }
            }
        }
        return result
    }

    private fun buildPrinter(record: PrinterRecord, specs: Map<String, SpecData>, maintenance: Set<String>): PrinterSpec? {
        if (!record.brand.equals("epson", ignoreCase = true) || record.model.isEmpty()) return null
        var control = byteArrayOf()
        var keyword = byteArrayOf()
        val counters = mutableListOf<WasteCounterSpec>()
        val reset = linkedMapOf<Int, Int>()
        var cleaning = emptyList<CleaningSpec>()
        var cleaningDeclared = false
        record.specs.forEach { name ->
            val spec = specs[name] ?: return@forEach
            if (spec.controlModel.isNotEmpty()) control = spec.controlModel
            if (spec.keyword.isNotEmpty()) keyword = spec.keyword
            counters += spec.counters
            reset.putAll(spec.resetMap)
            if (!cleaningDeclared && spec.cleaningDeclared) {
                cleaningDeclared = true
                cleaning = spec.cleaning
            }
        }
        val hasWaste = counters.isNotEmpty() && reset.isNotEmpty()
        val canOpenSession = control.isNotEmpty() && keyword.isNotEmpty()
        val hasUsefulJob = hasWaste || cleaning.isNotEmpty()
        if (!canOpenSession || !hasUsefulJob) return null
        return PrinterSpec(
            record.model,
            control,
            keyword,
            uniqueCounterNames(counters.distinctBy { it.name to it.addresses }),
            reset,
            cleaning.distinctBy { it.name to it.payload.contentHashCode() },
            maintenance.contains(normalizeModel(record.model)),
        )
    }

    private fun newParser(input: InputStream) = XmlPullParserFactory.newInstance()
        .apply { isNamespaceAware = false }.newPullParser().apply { setInput(input, "UTF-8") }

    private fun XmlPullParser.attr(name: String): String = getAttributeValue(null, name).orEmpty()

    private fun XmlPullParser.forEachChild(tag: String, block: (XmlPullParser) -> Unit) {
        require(eventType == XmlPullParser.START_TAG && name == tag) { "Expected <$tag>" }
        val depth = depth
        while (true) {
            when (next()) {
                XmlPullParser.START_TAG -> if (this.depth == depth + 1) block(this)
                XmlPullParser.END_TAG -> if (this.depth == depth) return
                XmlPullParser.END_DOCUMENT -> error("Unexpected end of document in <$tag>")
            }
        }
    }

    private fun XmlPullParser.skipElement() {
        if (eventType != XmlPullParser.START_TAG) return
        val depth = depth
        while (true) {
            when (next()) {
                XmlPullParser.END_TAG -> if (this.depth == depth) return
                XmlPullParser.END_DOCUMENT -> error("Unexpected end of document in <$name>")
            }
        }
    }

    private fun XmlPullParser.readText(tag: String): String {
        require(eventType == XmlPullParser.START_TAG && name == tag) { "Expected <$tag>" }
        val depth = depth
        val text = StringBuilder()
        while (true) {
            when (next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> text.append(this.text.orEmpty())
                XmlPullParser.END_TAG -> if (this.depth == depth) return text.toString()
                XmlPullParser.START_TAG -> skipElement()
                XmlPullParser.END_DOCUMENT -> error("Unexpected end of document in <$tag>")
            }
        }
    }

    private fun XmlPullParser.readDirectText(): String {
        val depth = depth
        val text = StringBuilder()
        while (true) {
            when (next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (this.depth == depth) text.append(this.text.orEmpty())
                XmlPullParser.END_TAG -> if (this.depth == depth) return text.toString()
                XmlPullParser.START_TAG -> skipElement()
                XmlPullParser.END_DOCUMENT -> error("Unexpected end of document in <$name>")
            }
        }
    }

    private fun hexBytes(text: String): ByteArray = hexInts(text).map(Int::toByte).toByteArray()
    private fun hexInts(text: String): List<Int> = HEX.findAll(text).map { numeric(it.value, "hex token") }.toList()

    private fun numeric(text: String, context: String): Int {
        val value = text.trim()
        return runCatching {
            when {
                value.startsWith("-0x", true) -> -value.drop(3).toInt(16)
                value.startsWith("+0x", true) -> value.drop(3).toInt(16)
                value.startsWith("0x", true) -> value.drop(2).toInt(16)
                else -> value.toInt()
            }
        }.getOrElse { throw IllegalArgumentException("Invalid numeric token '$value' in $context", it) }
    }

    fun decimalValues(text: String): List<Int> = text.split(Regex("\\s+")).filter(String::isNotBlank).mapNotNull(String::toIntOrNull)

    private fun addressValuePairs(text: String): Map<Int, Int> = buildMap {
        hexInts(text).chunked(2).forEach { if (it.size == 2) put(it[0], it[1]) }
    }

    fun counterName(kind: String, index: Int): String = when (kind.trim().lowercase()) {
        "main" -> "Main pad"
        "platen" -> "Platen pad"
        "borderless" -> "Borderless pad"
        else -> defaultCounterName(index)
    }

    fun defaultCounterName(index: Int): String = when (index) {
        0 -> "Main pad"
        1 -> "Platen pad"
        2 -> "Borderless pad"
        else -> "Waste counter ${index + 1}"
    }

    private val HEX = Regex("0x[0-9A-Fa-f]+")
}
