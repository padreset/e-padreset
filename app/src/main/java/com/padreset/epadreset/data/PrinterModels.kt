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
    /**
     * Per-address read mask for counters that share a byte with another
     * counter (nibble split, e.g. L3110's `0x2F` low/high halves). Absent
     * means `0xFF`: the whole byte belongs to this counter — a `<filter>0xFF`
     * is stored as absent for the same reason. Carried so reads stop
     * understating shared counters; writes never go through it (a partial
     * write cannot touch a shared byte without a read-modify-write the app
     * does not perform — see `WasteWriter.planFor`).
     */
    val masks: Map<Int, Int> = emptyMap(),
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

    /**
     * EEPROM addresses appearing in more than one waste counter of this model.
     *
     * Shared whole bytes (RX700's 0x2E/0x2F in two counters) and shared nibbles
     * (L3110's 0x2F halves) alike: writing one counter's selection would touch
     * another counter, so partial resets touching these are refused and only
     * the full set is offered. Derived, so every construction path agrees.
     */
    val sharedAddresses: Set<Int>
        get() = wasteCounters.flatMap { it.addresses }
            .groupingBy { it }.eachCount()
            .filter { it.value > 1 }.keys

    /**
     * The addresses a partial write must not touch.
     *
     * Two ways a byte can belong to more than the counter being written: it is
     * shared with another counter ([sharedAddresses]), or the counter carries a
     * mask and owns only part of it. Clearing a counter's own part would need a
     * read-modify-write preserving the sibling, which the write path does not
     * perform — and the byte-exact verification would still pass on the bytes
     * written, certifying an incomplete reset.
     *
     * The shipped catalog only produces a mask where the address is already
     * shared, so the masked half adds nothing today. It is derived rather than
     * assumed so that a catalog which stops holding stays caught: a partial
     * write that clobbers a neighbour's nibble is the same silent corruption
     * either way, and the guard should not depend on the data behaving.
     *
     * Single source for the UI gate, the ViewModel's localized refusal and the
     * service backstop, so the three cannot disagree about which models may be
     * partially reset.
     */
    val untouchableAddresses: Set<Int>
        get() = sharedAddresses + wasteCounters.flatMap { it.masks.keys }
}

object EpsonPrinterCatalog {
    const val EPSON_VENDOR_ID: Int = 0x04B8

    @Volatile
    private var cachedSpecs: List<PrinterSpec>? = null

    @Volatile
    private var cachedMaintenanceBoxModels: Set<String>? = null

    @Volatile
    var logger: ((String) -> Unit)? = null

    /**
     * Plain parser factory — deliberately NOT hardened with XXE features.
     *
     * Do not add `disallow-doctype-decl`, `external-*-entities`, XInclude or
     * entity-expansion switches here: Android's built-in JAXP parser does not
     * accept them and fails every asset parse on device with
     * `This parser does not support specification "Unknown" version "0.0"`,
     * which drops the whole catalog and no printer connects (broke L365
     * detection in Oct 2026 while JVM unit tests stayed green, because the
     * desktop parser accepts the same settings). The assets are local files
     * shipped with the app — two generated by our own script — so there is no
     * untrusted XML to defend against.
     */
    private fun secureFactory() = DocumentBuilderFactory.newInstance().apply {
        isIgnoringComments = true
        isNamespaceAware = false
    }

    fun resolve(context: Context, deviceId: String): PrinterSpec? {
        val mdl = modelName(deviceId).orEmpty()
        if (mdl.isBlank()) return null
        val specs = load(context)
        val name = resolveName(
            mdl = mdl,
            deviceId = deviceId,
            names = specs.map { it.modelName },
            onAmbiguous = { logger?.invoke(it) },
        ) ?: return null
        return specs.firstOrNull { it.modelName == name }
    }

    /**
     * Picks the catalog name for an MDL without touching Android or assets.
     *
     * 1. Exact name, e.g. MDL "PM-4000PX" -> "PM-4000PX".
     * 2. Suffixed name, e.g. MDL "XP-4200" -> "XP-4200 Series". A bare
     *    `contains` used to live here and matched "XP-420 Series" for "XP-4200"
     *    (and "PM-400 Series" for anything starting with it), handing the wrong
     *    controlModel/keyword/EEPROM addresses to the write path. Never match a
     *    shorter name inside a longer one.
     * 3. Longest catalog name found in the MDL at a word boundary (e.g. MDL
     *    "EPSON XP-420" contains "XP-420" whole). Longest wins so
     *    "XP-4200 Series" beats "XP-420 Series" if both ever match; ties keep
     *    catalog order and are reported via [onAmbiguous].
     * 4. Same rule against the whole device ID, for printers whose MDL is
     *    known but unmatched, or whose ID carries the model elsewhere.
     */
    internal fun resolveName(
        mdl: String,
        deviceId: String,
        names: List<String>,
        onAmbiguous: (String) -> Unit = {},
    ): String? {
        names.firstOrNull { it.equals(mdl, ignoreCase = true) }?.let { return it }
        names.firstOrNull { it.equals("$mdl Series", ignoreCase = true) }?.let { return it }
        val stripped = names.map { it to it.removeSuffix(" Series") }
        val candidates = stripped.filter { (_, name) -> boundaryContains(mdl, name) }
        if (candidates.size > 1) {
            onAmbiguous("Ambiguous model '$mdl' matches ${candidates.map { it.first }}; using longest")
        }
        candidates.maxByOrNull { it.second.length }?.let { return it.first }
        val idCandidates = (names.filter { boundaryContains(deviceId, it) } +
            stripped.filter { (_, name) -> boundaryContains(deviceId, name) }.map { it.first })
            .distinct()
        if (idCandidates.size > 1) {
            onAmbiguous("Ambiguous device ID for '$mdl' matches $idCandidates; using longest")
        }
        return idCandidates.maxByOrNull { it.length }
    }

    /**
     * Whether [needle] occurs in [haystack] as a whole token: bounded on both
     * sides by a non-alphanumeric or a string edge. "XP-420" matches
     * "EPSON XP-420" but not "XP-4200"; "PM-400" matches neither "PM-4000PX"
     * nor "PM-400 Series"-less prefixes. Case-insensitive; pure for tests.
     */
    internal fun boundaryContains(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from, ignoreCase = true)
            if (i < 0) return false
            val beforeOk = i == 0 || !haystack[i - 1].isLetterOrDigit()
            val after = i + needle.length
            val afterOk = after >= haystack.length || !haystack[after].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = i + 1
        }
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
        val document = secureFactory().newDocumentBuilder().parse(input)

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

    /**
     * internal rather than private so [DevicesXmlParserTest] can reach it.
     *
     * It stays out of the public surface: [load] is how the app gets here, and
     * an exposed parser is an invitation to call it with the wrong arguments.
     * The test needs it because it is the only way to check this parser against
     * the bundled file without an Android Context, and the waste-counter
     * extraction below was split into helpers that no other test exercises.
     */
    internal fun parseDevicesXml(
        input: InputStream,
        maintenanceBoxModels: Set<String> = emptySet(),
    ): List<PrinterSpec> {
        val builder = secureFactory().newDocumentBuilder()
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
            parsePrinterElement(printer, specByName, maintenanceBoxModels)?.let { printers += it }
        }

        return printers.distinctBy { it.modelName }
    }

    /**
     * One `<printer>` entry, or null when it cannot mean anything.
     *
     * Split out of [parseDevicesXml] to keep that function's length and branch
     * count away from the detekt ceilings: the keep-rule below (session keys
     * plus some useful job) is what once dropped every maintenance-box model,
     * so it stays explained where it runs rather than inlined where it hides.
     */
    private fun parsePrinterElement(
        printer: Element,
        specByName: Map<String, Element>,
        maintenanceBoxModels: Set<String>,
    ): PrinterSpec? {
        if (!printer.getAttribute("brand").equals("epson", ignoreCase = true)) return null

        val modelName = printer.getAttribute("model").trim()
        if (modelName.isEmpty()) return null

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
                val (counters, resetValues) = parseWasteSection(waste, modelName)
                wasteCounters += counters
                resetMap.putAll(resetValues)
            }

            specNode.firstChildElement("cleaning")?.let { cleaningRoot ->
                // First spec that declares cleaning wins. A model lists the
                // same cleaning jobs under each of its specs, and taking
                // them from all of them would show every job several times.
                if (!cleaningDeclared) {
                    cleaningDeclared = true
                    cleaningOptions = parseCleaningSection(cleaningRoot)
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

        if (!canOpenSession || !(hasWasteJobs || hasCleaningJobs)) return null
        return PrinterSpec(
            modelName = modelName,
            controlModel = controlModel,
            keyword = keyword,
            wasteCounters = wasteCounters.distinctBy { it.name to it.addresses },
            resetMap = resetMap,
            cleaningOptions = cleaningOptions.distinctBy { it.name to it.payload.contentHashCode() },
            usesMaintenanceBox = maintenanceBoxModels.contains(normalizeModelName(modelName)),
        )
    }

    /**
     * Reads the `<cleaning>` subtree into the jobs this app can run.
     *
     * Only service-type jobs with a payload are kept: a generic job would be
     * dispatched as a head cleaning and send the wrong bytes to the printer,
     * which is worse than not offering it.
     */
    private fun parseCleaningSection(cleaningRoot: Element): List<CleaningSpec> =
        cleaningRoot.childElements("cleaning").mapNotNull { cleaning ->
            val type = cleaning.firstChildElement("type")?.textContent?.trim().orEmpty()
            val payload = parseHexBytes(cleaning.firstChildElement("data")?.textContent?.trim().orEmpty())
            if (!type.equals("service", ignoreCase = true) || payload.isEmpty()) {
                null
            } else {
                val name = cleaning.firstChildElement("name")?.textContent?.trim().orEmpty()
                CleaningSpec(
                    name = name.ifBlank { "Head Cleaning" },
                    type = type,
                    payload = payload,
                )
            }
        }

    /**
     * Reads one spec's `<waste>` element into its counters and its reset values.
     *
     * Split out of [parseDevicesXml] because it is a whole sub-format on its
     * own: the query subtree, the address parsing and the address/value pairing
     * of the reset list. Read together with the loop around it, the two were
     * one 120-line function and the loop's own logic was impossible to see.
     *
     * The model name is passed in only to make a bad address say which model it
     * came from. A wrong address in devices.xml is otherwise invisible: it just
     * produces a model that never reports a level.
     */
    private fun parseWasteSection(
        waste: Element,
        modelName: String,
    ): Pair<List<WasteCounterSpec>, Map<Int, Int>> {
        val counters = waste.firstChildElement("query")
            ?.let { query ->
                query.childElements("counter")
                    .mapIndexedNotNull { counterIndex, counter -> parseCounter(counter, modelName, counterIndex) }
            }
            .orEmpty()

        val resetValues = waste.firstChildElement("reset")
            ?.let { reset -> parseAddressValuePairs(directText(reset)) }
            .orEmpty()

        return counters to resetValues
    }

    /**
     * One `<counter>` entry, or null when it cannot mean anything.
     *
     * A counter with no address or no positive maximum is dropped rather than
     * kept with a zero: a zero maximum would divide by zero when the level is
     * worked out, and an address-less counter cannot be read at all.
     */
    private fun parseCounter(
        counter: Element,
        modelName: String,
        counterIndex: Int,
    ): WasteCounterSpec? {
        // The address list moved under <entry> in later revisions of the file,
        // so both shapes are still in the wild and the bare text is the old one.
        // Every <entry> counts, not just the first: a counter can spread over
        // several, and a later one may carry a <filter> narrowing one address
        // to a nibble it shares with another counter (L3110's 0x2F halves).
        // Reading only the first entry silently dropped those bytes, showing a
        // lower level than the printer holds.
        val entries = counter.childElements("entry")
        val addresses = mutableListOf<Int>()
        val masks = mutableMapOf<Int, Int>()
        if (entries.isEmpty()) {
            addresses += directText(counter)
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { parseNumeric(it, "waste address for model '$modelName' counter $counterIndex") }
        } else {
            entries.forEach { entry ->
                val entryAddresses = directText(entry)
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .map { parseNumeric(it, "waste address for model '$modelName' counter $counterIndex") }
                // A filter's first hex token is the read mask for the addresses
                // in its own entry (e.g. `0x2F` + filter `0x0F …`). Siblings
                // keep the full byte. A full-byte mask (0xFF) is no mask at all
                // and is not stored, so a non-empty map always means sharing.
                val mask = entry.firstChildElement("filter")
                    ?.let { parseHexInts(it.textContent).firstOrNull() }
                    ?.takeUnless { it and 0xFF == 0xFF }
                addresses += entryAddresses
                if (mask != null) entryAddresses.forEach { masks[it] = mask }
            }
        }
        val maxText = counter.firstChildElement("max")?.textContent?.trim()
        val max = maxText?.let {
            parseNumeric(it, "max for model '$modelName' counter $counterIndex")
        } ?: 0
        if (addresses.isEmpty() || max <= 0) return null

        return WasteCounterSpec(
            name = defaultCounterName(counterIndex),
            addresses = addresses,
            max = max,
            masks = masks,
        )
    }

    /**
     * `A0 B0 A1 B1 ...` as address to value pairs.
     *
     * A trailing odd element is dropped rather than guessed at: the list is a
     * flat hex stream and half a pair means the file is malformed.
     */
    private fun parseAddressValuePairs(text: String): Map<Int, Int> {
        val values = parseHexInts(text)
        return buildMap {
            values.chunked(2).forEach { pair ->
                if (pair.size == 2) put(pair[0], pair[1])
            }
        }
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
        val document = secureFactory().newDocumentBuilder().parse(input)
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
