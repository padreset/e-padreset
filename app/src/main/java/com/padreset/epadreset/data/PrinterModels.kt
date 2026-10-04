package com.padreset.epadreset.data

import android.content.Context
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

data class WasteCounterSpec(
    val name: String,
    val addresses: List<Int>,
    val max: Int,
)

data class CleaningSpec(
    val name: String,
    val type: String,
    val payload: ByteArray,
)

data class PrinterSpec(
    val modelName: String,
    val controlModel: ByteArray,
    val keyword: ByteArray,
    val wasteCounters: List<WasteCounterSpec>,
    val resetMap: Map<Int, Int>,
    val cleaningOptions: List<CleaningSpec>,
    /**
     * True when this model is known to collect waste ink in a user-replaceable
     * maintenance box, so the count lives on the box's chip.
     *
     * Set from assets/maintenance_boxes.xml, which carries the provenance for
     * each claim. False means "not known to use one", not "known not to": the
     * list is a seed and only ever makes the explanation more specific.
     */
    val usesMaintenanceBox: Boolean = false,
) {
    /**
     * Whether this model exposes waste ink counters the app can read and reset.
     *
     * False means the count is not in the printer's own memory. Epson collects
     * waste ink in one of two ways: on models with an internal absorbent pad the
     * fill level lives in the printer's EEPROM and can be rewritten, while on
     * models with a maintenance box the ink goes into the box and the level is
     * kept on the box's own chip, so there is nothing in the printer to reset.
     *
     * A model without counters can still be connected to for head cleaning and
     * the nozzle check — the control model and the cleaning jobs are present in
     * the catalog either way.
     */
    val supportsWasteReset: Boolean get() = wasteCounters.isNotEmpty() && resetMap.isNotEmpty()
}

object EpsonPrinterCatalog {
    const val EPSON_VENDOR_ID: Int = 0x04B8

    @Volatile
    private var cachedSpecs: List<PrinterSpec>? = null

    @Volatile
    private var cachedMaintenanceBoxModels: Set<String>? = null

    @Volatile
    var logger: ((String) -> Unit)? = null

    fun resolve(context: Context, deviceId: String): PrinterSpec? {
        val mdl = modelName(deviceId).orEmpty()
        val specs = load(context)

        return specs.firstOrNull { it.modelName.equals(mdl, ignoreCase = true) }
            ?: specs.firstOrNull { mdl.contains(it.modelName.removeSuffix(" Series"), ignoreCase = true) }
            ?: specs.firstOrNull { deviceId.contains(it.modelName, ignoreCase = true) }
    }

    /**
     * The printer's own model string, from the MDL field of its IEEE 1284
     * device ID.
     *
     * Public because two callers need it apart from resolution: the service, to
     * ask the maintenance-box list about a model the catalog does not know, and
     * the ViewModel, to name the model in a connection failure.
     */
    fun modelName(deviceId: String): String? =
        parseDeviceId(deviceId)["MDL"]?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Whether reinkpy classifies this model as collecting waste ink in a
     * maintenance box.
     *
     * This is asked when resolution has already failed, which is the one case
     * where the answer changes what the user is told. A model that is merely
     * missing from devices.xml can be added and then reset; a model whose count
     * lives on the box's chip cannot be reset by any software even after it is
     * added. The list holds reinkpy's complete classification rather than only
     * the intersection with our catalog, so it can answer for models
     * devices.xml has never heard of.
     *
     * It is still a classification, not something read from the connected
     * printer, so callers should say where it comes from instead of asserting
     * it as fact.
     */
    fun usesMaintenanceBox(context: Context, modelName: String?): Boolean {
        val normalized = modelName?.let(::normalizeModelName).orEmpty()
        return normalized.isNotEmpty() &&
            loadMaintenanceBoxModels(context).contains(normalized)
    }

    fun load(context: Context): List<PrinterSpec> {
        cachedSpecs?.let { return it }

        synchronized(this) {
            cachedSpecs?.let { return it }
            val maintenanceBoxModels = loadMaintenanceBoxModels(context)
            logger?.invoke("Loaded ${maintenanceBoxModels.size} known maintenance box models")

            logger?.invoke("Loading Epson model database from assets/devices.xml")
            val fromDevices = context.assets.open("devices.xml")
                .use { parseDevicesXml(it, maintenanceBoxModels) }
            logger?.invoke("Loaded ${fromDevices.size} Epson printer specs from devices.xml")

            // The second file is an extension, never an override: a model that
            // devices.xml already knows keeps its entry, because that one also
            // carries the counter ceiling and the cleaning jobs, which reinkpy
            // does not publish.
            val known = fromDevices.mapTo(HashSet()) { normalizeModelName(it.modelName) }
            val fromReinkpy = runCatching {
                context.assets.open("reinkpy_models.xml")
                    .use { parseReinkpyModels(it, maintenanceBoxModels) }
            }.getOrElse {
                logger?.invoke("No reinkpy model extension available: ${it.message}")
                emptyList()
            }.filterNot { normalizeModelName(it.modelName) in known }
            logger?.invoke("Loaded ${fromReinkpy.size} extra specs from reinkpy_models.xml")

            val parsed = fromDevices + fromReinkpy
            cachedSpecs = parsed
            return parsed
        }
    }

    /**
     * Parses the models reinkpy knows and devices.xml does not.
     *
     * The two files describe the same printers under different names: what
     * devices.xml spells as a two-byte little-endian `<factory>` and an
     * eight-byte `<keyword>`, reinkpy publishes as the integer `rkey` and the
     * `wkey` string. Where the two overlap they agree exactly, which is what
     * makes the translation below a verified correspondence rather than a guess.
     *
     * What these models do not have is the counter ceiling. reinkpy does not
     * publish it, so `max` stays at zero and the UI shows the raw counter bytes
     * instead of a percentage, rather than inventing a scale.
     */
    internal fun parseReinkpyModels(
        input: InputStream,
        maintenanceBoxModels: Set<String>,
    ): List<PrinterSpec> {
        val document = DocumentBuilderFactory.newInstance().apply {
            isIgnoringComments = true
            isNamespaceAware = false
        }.newDocumentBuilder().parse(input)

        val nodes = document.getElementsByTagName("model")
        val printers = mutableListOf<PrinterSpec>()

        for (index in 0 until nodes.length) {
            val model = nodes.item(index) as? Element ?: continue
            val modelName = model.getAttribute("name").trim()
            if (modelName.isEmpty()) continue

            val rkey = model.getAttribute("rkey").trim().toIntOrNull()
            if (rkey == null) {
                logger?.invoke("Skipping reinkpy model '$modelName': unreadable rkey")
                continue
            }
            val keyword = parseHexBytes(model.getAttribute("keyword"))
            if (keyword.isEmpty()) {
                logger?.invoke("Skipping reinkpy model '$modelName': no keyword")
                continue
            }

            val counters = mutableListOf<WasteCounterSpec>()
            val resetMap = linkedMapOf<Int, Int>()
            model.childElements("pad").forEachIndexed { padIndex, pad ->
                val addresses = parseDecimals(pad.getAttribute("addresses"))
                if (addresses.isEmpty()) return@forEachIndexed
                val reset = parseDecimals(pad.getAttribute("reset"))
                addresses.forEachIndexed { position, address ->
                    reset.getOrNull(position)?.let { resetMap[address] = it }
                }
                counters += WasteCounterSpec(
                    name = counterNameForKind(pad.getAttribute("kind"), padIndex),
                    addresses = addresses,
                    // Zero means "not published", not "zero capacity".
                    max = 0,
                )
            }

            printers += PrinterSpec(
                modelName = modelName,
                controlModel = byteArrayOf(
                    (rkey and 0xFF).toByte(),
                    ((rkey shr 8) and 0xFF).toByte(),
                ),
                keyword = keyword,
                wasteCounters = counters.distinctBy { it.name to it.addresses },
                resetMap = resetMap,
                // reinkpy carries no cleaning payloads, so the maintenance page
                // falls back to the generic ESC/P2 job it already uses for the
                // models devices.xml leaves without one.
                cleaningOptions = emptyList(),
                usesMaintenanceBox = maintenanceBoxModels.contains(normalizeModelName(modelName)),
            )
        }

        return printers.distinctBy { it.modelName }
    }

    private fun parseDecimals(text: String): List<Int> =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }

    private fun counterNameForKind(kind: String, index: Int): String =
        when (kind.trim().lowercase()) {
            "main" -> "Main pad"
            "platen" -> "Platen pad"
            "borderless" -> "Borderless pad"
            else -> defaultCounterName(index)
        }

    private fun parseDevicesXml(
        input: InputStream,
        maintenanceBoxModels: Set<String>,
    ): List<PrinterSpec> {
        val builder = DocumentBuilderFactory.newInstance().apply {
            isIgnoringComments = true
            isNamespaceAware = false
        }.newDocumentBuilder()
        val document = builder.parse(input)
        val root = document.documentElement
        val devicesRoot = root.getElementsByTagName("devices").item(0) as? Element ?: return emptyList()

        val specByName = buildMap<String, Element> {
            val children = devicesRoot.childNodes
            for (index in 0 until children.length) {
                val node = children.item(index)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    put(node.nodeName, node as Element)
                }
            }
        }

        val printerNodes = root.getElementsByTagName("printer")
        val printers = mutableListOf<PrinterSpec>()
        for (index in 0 until printerNodes.length) {
            val printer = printerNodes.item(index) as? Element ?: continue
            if (!printer.getAttribute("brand").equals("epson", ignoreCase = true)) continue

            val modelName = printer.getAttribute("model").trim()
            if (modelName.isEmpty()) continue

            val specNames = printer.getAttribute("specs")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            var controlModel = byteArrayOf()
            var keyword = byteArrayOf()
            val wasteCounters = mutableListOf<WasteCounterSpec>()
            val resetMap = linkedMapOf<Int, Int>()
            var cleaningOptions: List<CleaningSpec> = emptyList()
            var cleaningDeclared = false

            specNames.forEach { specName ->
                val specNode = specByName[specName] ?: return@forEach

                specNode.firstChildElement("service")?.let { service ->
                    service.firstChildElement("factory")?.let { factory ->
                        val bytes = parseHexBytes(factory.textContent)
                        if (bytes.isNotEmpty()) controlModel = bytes
                    }
                    service.firstChildElement("keyword")?.let { key ->
                        val bytes = parseHexBytes(key.textContent)
                        if (bytes.isNotEmpty()) keyword = bytes
                    }
                }

                specNode.firstChildElement("waste")?.let { waste ->
                    waste.firstChildElement("query")?.let { query ->
                        val counters = query.childElements("counter").mapIndexedNotNull { counterIndex, counter ->
                            val firstEntry = counter.firstChildElement("entry")
                            val rawAddresses = if (firstEntry != null) {
                                directText(firstEntry)
                            } else {
                                directText(counter)
                            }
                            val addresses = rawAddresses
                                .split(Regex("\\s+"))
                                .filter { it.isNotBlank() }
                                .map { parseNumeric(it, "waste address for model '$modelName' counter $counterIndex") }
                            val maxText = counter.firstChildElement("max")?.textContent?.trim()
                            val max = maxText?.let {
                                parseNumeric(it, "max for model '$modelName' counter $counterIndex")
                            } ?: 0
                            if (addresses.isEmpty() || max <= 0) {
                                null
                            } else {
                                WasteCounterSpec(
                                    name = defaultCounterName(counterIndex),
                                    addresses = addresses,
                                    max = max,
                                )
                            }
                        }
                        wasteCounters += counters
                    }

                    waste.firstChildElement("reset")?.let { reset ->
                        val values = parseHexInts(directText(reset))
                        values.chunked(2).forEach { pair ->
                            if (pair.size == 2) {
                                resetMap[pair[0]] = pair[1]
                            }
                        }
                    }
                }

                specNode.firstChildElement("cleaning")?.let { cleaningRoot ->
                    if (!cleaningDeclared) {
                        cleaningDeclared = true
                        val parsedCleanings = cleaningRoot.childElements("cleaning").mapNotNull { cleaning ->
                            val type = cleaning.firstChildElement("type")?.textContent?.trim().orEmpty()
                            val payloadText = cleaning.firstChildElement("data")?.textContent?.trim().orEmpty()
                            val payload = parseHexBytes(payloadText)
                            if (type.equals("service", ignoreCase = true) && payload.isNotEmpty()) {
                                val name = cleaning.firstChildElement("name")?.textContent?.trim().orEmpty()
                                CleaningSpec(
                                    name = name.ifBlank { "Head Cleaning" },
                                    type = type,
                                    payload = payload,
                                )
                            } else {
                                null
                            }
                        }
                        cleaningOptions = parsedCleanings
                    }
                }
            }

            // A model is worth keeping if we can open a session with it and then
            // do something useful. Waste counters are NOT required: models that
            // collect waste ink in a maintenance box carry no counter, but they
            // do carry the control model, the keyword and their cleaning jobs,
            // and head cleaning and the nozzle check still work on them.
            //
            // Requiring counters here is what made those models answer
            // "Unsupported Epson model" instead of connecting — the spec was
            // dropped before it ever reached the catalog.
            val canOpenSession = controlModel.isNotEmpty() && keyword.isNotEmpty()
            val hasWasteJobs = wasteCounters.isNotEmpty() && resetMap.isNotEmpty()
            val hasCleaningJobs = cleaningOptions.isNotEmpty()

            if (canOpenSession && (hasWasteJobs || hasCleaningJobs)) {
                printers += PrinterSpec(
                    modelName = modelName,
                    controlModel = controlModel,
                    keyword = keyword,
                    wasteCounters = wasteCounters.distinctBy { it.name to it.addresses },
                    resetMap = resetMap,
                    cleaningOptions = cleaningOptions.distinctBy { it.name to it.payload.contentHashCode() },
                    usesMaintenanceBox = maintenanceBoxModels.contains(normalizeModelName(modelName)),
                )
            }
        }

        return printers.distinctBy { it.modelName }
    }

    /**
     * Reads the curated list of maintenance-box models, once per process.
     *
     * Kept apart from devices.xml on purpose: it carries claims with their
     * provenance, and it is regenerated from reinkpy's database by
     * `scripts/sync-maintenance-boxes.mjs` rather than from the upstream
     * devices.xml.
     *
     * The `runCatching` below is the reason the build runs `verifyPrinterData`.
     * A file that does not parse becomes an empty list here, and the only
     * symptom is that every maintenance-box model quietly falls back to the
     * hedged two-cause wording. Nothing reaches the user, so the failure has to
     * be caught before the asset ships.
     */
    private fun loadMaintenanceBoxModels(context: Context): Set<String> {
        cachedMaintenanceBoxModels?.let { return it }

        synchronized(this) {
            cachedMaintenanceBoxModels?.let { return it }
            val models = runCatching {
                context.assets.open("maintenance_boxes.xml").use(::parseMaintenanceBoxModels)
            }.getOrElse {
                logger?.invoke("No maintenance box list available: ${it.message}")
                emptySet()
            }
            cachedMaintenanceBoxModels = models
            return models
        }
    }

    /**
     * Parses the curated list.
     *
     * Separate from the asset lookup so the shape of the file can be exercised
     * without an Android Context, and so the one place that understands the
     * document stands on its own.
     */
    internal fun parseMaintenanceBoxModels(input: InputStream): Set<String> {
        val document = DocumentBuilderFactory.newInstance().apply {
            isIgnoringComments = true
            isNamespaceAware = false
        }.newDocumentBuilder().parse(input)
        val nodes = document.getElementsByTagName("model")
        return (0 until nodes.length)
            .map { nodes.item(it).textContent }
            .map(::normalizeModelName)
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Model names in the catalog carry a " Series" suffix that the curated list
     * does not, so both sides are compared without it.
     *
     * Visible beyond this file because the restore guard compares a saved record's
     * model against the connected printer's, and that comparison has to be the
     * same one the catalog makes. Duplicating it would let the two drift, and a
     * drifted comparison would either block a matching printer or, worse, allow a
     * mismatched one through.
     */
    internal fun normalizeModelName(name: String): String =
        name.trim().uppercase().removeSuffix(" SERIES").trim()

    private fun parseDeviceId(deviceId: String): Map<String, String> {
        return deviceId.split(';')
            .mapNotNull { part ->
                val pieces = part.split(':', limit = 2)
                if (pieces.size == 2) pieces[0].trim() to pieces[1].trim() else null
            }
            .toMap()
    }

    private fun parseHexBytes(text: String): ByteArray {
        return parseHexInts(text).map { it.toByte() }.toByteArray()
    }

    private fun parseHexInts(text: String): List<Int> {
        return HEX_TOKEN.findAll(text)
            .map { token -> parseNumeric(token.value, "hex token in XML block") }
            .toList()
    }

    private fun parseNumeric(text: String, context: String): Int {
        val value = text.trim()
        return runCatching {
            when {
                value.startsWith("-0x", ignoreCase = true) -> -value.drop(3).toInt(16)
                value.startsWith("+0x", ignoreCase = true) -> value.drop(3).toInt(16)
                value.startsWith("0x", ignoreCase = true) -> value.drop(2).toInt(16)
                else -> value.toInt()
            }
        }.getOrElse { cause ->
            val message = "Failed to parse numeric token '$value' in $context"
            logger?.invoke(message)
            throw IllegalArgumentException(message, cause)
        }
    }

    private fun directText(element: Element): String {
        val builder = StringBuilder()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                builder.append(node.nodeValue)
                builder.append(' ')
            }
        }
        return builder.toString()
    }

    private fun defaultCounterName(index: Int): String = when (index) {
        0 -> "Main pad"
        1 -> "Platen pad"
        2 -> "Borderless pad"
        else -> "Waste counter ${index + 1}"
    }

    private fun Element.firstChildElement(tagName: String): Element? {
        val children = childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tagName) {
                return node as Element
            }
        }
        return null
    }

    private fun Element.childElements(tagName: String): List<Element> {
        val items = mutableListOf<Element>()
        val children = childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tagName) {
                items += node as Element
            }
        }
        return items
    }

    private val HEX_TOKEN = Regex("0x[0-9A-Fa-f]+")
}
