package com.padreset.epadreset.data

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A profile the user pasted for a model the bundled catalog does not have.
 *
 * It is a gap, not an override: [selectGap] returns nothing when the catalog
 * already resolved the model, and it matches the MDL exactly. A shorter name
 * does not fill a longer one. The issue cites [source] and [version] only.
 */
internal data class GapProfile(
    val modelName: String,
    val controlModel: ByteArray,
    val keyword: ByteArray,
    val wasteCounters: List<WasteCounterSpec>,
    val resetMap: Map<Int, Int>,
    val source: String,
    val version: String,
    val usesMaintenanceBox: Boolean,
) {
    fun toSpec(): PrinterSpec = PrinterSpec(
        modelName = modelName,
        controlModel = controlModel,
        keyword = keyword,
        wasteCounters = wasteCounters,
        resetMap = resetMap,
        cleaningOptions = emptyList(),
        usesMaintenanceBox = usesMaintenanceBox,
    )
}

internal enum class GapRefusal {
    PROVENANCE,
    MALFORMED,
    MAINTENANCE_BOX,
    DOCTYPE,
    CATALOGUED,
    FULL,
}

internal sealed class GapParse {
    data class Accepted(val profile: GapProfile) : GapParse()
    data class Refused(val reason: GapRefusal, val detail: String = "") : GapParse()
}

internal enum class GapSave { Stored, RefusedCatalog, RefusedFull }

/**
 * Exact MDL only, and only when the bundled catalog missed.
 *
 * "PM-400" does not fill "PM-4000PX". A paste of a name the catalog already
 * has is ignored here even if it was stored: the catalog wins.
 */
internal data class OpenedProfile(
    val spec: PrinterSpec,
    val unverified: Boolean,
    val logLine: String,
)

/** Catalog first. A stored paste fills only a miss, and says so in the log line. */
internal fun openProfile(context: android.content.Context, deviceId: String): OpenedProfile? {
    val catalogued = EpsonPrinterCatalog.resolve(context, deviceId)
    val gap = if (catalogued == null) {
        GapProfileStore.from(context).findExact(EpsonPrinterCatalog.modelName(deviceId))
    } else {
        null
    }
    val spec = catalogued ?: gap?.toSpec() ?: return null
    val unverified = catalogued == null
    val logLine = if (unverified) {
        "Model '${spec.modelName}' is not in the catalog; using an unverified local profile"
    } else {
        "Resolved model spec: ${spec.modelName}, counters=${spec.wasteCounters.size}, " +
            "resetEntries=${spec.resetMap.size}, cleanings=${spec.cleaningOptions.size}"
    }
    return OpenedProfile(spec, unverified, logLine)
}

internal fun selectGap(mdl: String, catalogued: Boolean, gaps: List<GapProfile>): GapProfile? {
    if (catalogued || mdl.isBlank()) return null
    val want = EpsonPrinterCatalog.normalizeModelName(mdl)
    return gaps.firstOrNull { EpsonPrinterCatalog.normalizeModelName(it.modelName) == want }
}

internal sealed class GapCommit {
    data class Saved(val profile: GapProfile) : GapCommit()
    data class Failed(val reason: GapRefusal) : GapCommit()
}

/**
 * Parse, reject a box waste plan, and store only if the catalog missed.
 * The catalog check is a lambda so this stays free of Android.
 */
internal fun commitGapPaste(
    text: String,
    isBox: (String) -> Boolean,
    isCatalogued: (String) -> Boolean,
    store: GapProfileStore,
): GapCommit {
    val draft = GapProfileText.parse(text, emptySet())
    val named = draft as? GapParse.Accepted ?: return GapCommit.Failed((draft as GapParse.Refused).reason)
    val checked = if (isBox(named.profile.modelName)) {
        GapProfileText.parse(text, setOf(named.profile.modelName))
    } else {
        draft
    }
    val accepted = checked as? GapParse.Accepted ?: return GapCommit.Failed((checked as GapParse.Refused).reason)
    if (isCatalogued(accepted.profile.modelName)) return GapCommit.Failed(GapRefusal.CATALOGUED)
    return when (store.save(accepted.profile, catalogued = false)) {
        GapSave.Stored -> GapCommit.Saved(accepted.profile)
        GapSave.RefusedCatalog -> GapCommit.Failed(GapRefusal.CATALOGUED)
        GapSave.RefusedFull -> GapCommit.Failed(GapRefusal.FULL)
    }
}

internal object GapProfileText {
    private const val ADDRESS_MAX = 0x7FF

    fun parse(text: String, maintenanceBoxNames: Set<String>): GapParse {
        val trimmed = text.trim()
        if (trimmed.contains("<!DOCTYPE", ignoreCase = true) || trimmed.contains("<!ENTITY", ignoreCase = true)) {
            return GapParse.Refused(GapRefusal.DOCTYPE)
        }
        val model = try {
            readModel(trimmed)
        } catch (error: Exception) {
            return GapParse.Refused(GapRefusal.MALFORMED, error.toString())
        } ?: return GapParse.Refused(GapRefusal.MALFORMED, "no model element")
        val name = model.getAttribute("name").trim()
        val source = model.getAttribute("source").trim()
        val version = model.getAttribute("version").trim()
        if (name.isEmpty() || source.isEmpty() || version.isEmpty()) {
            return GapParse.Refused(GapRefusal.PROVENANCE)
        }
        if (name.length > 40 || source.length > 160 || version.length > 40) {
            return GapParse.Refused(GapRefusal.MALFORMED)
        }
        val rkey = model.getAttribute("rkey").trim().toIntOrNull()
        if (rkey == null || rkey !in 0..0xFFFF) return GapParse.Refused(GapRefusal.MALFORMED)
        val keyword = hexBytes(model.getAttribute("keyword"))
        if (keyword.size != 8) return GapParse.Refused(GapRefusal.MALFORMED)

        val counters = mutableListOf<WasteCounterSpec>()
        val resetMap = linkedMapOf<Int, Int>()
        val pads = model.childNodes
        var padIndex = 0
        for (i in 0 until pads.length) {
            val pad = pads.item(i) as? Element ?: continue
            if (pad.nodeName != "pad") continue
            val addresses = decimals(pad.getAttribute("addresses"))
            val reset = decimals(pad.getAttribute("reset"))
            if (addresses.isEmpty() || addresses.size != reset.size) {
                return GapParse.Refused(GapRefusal.MALFORMED)
            }
            if (addresses.any { it !in 0..ADDRESS_MAX } || reset.any { it !in 0..0xFF }) {
                return GapParse.Refused(GapRefusal.MALFORMED)
            }
            addresses.forEachIndexed { position, address -> resetMap[address] = reset[position] }
            counters += WasteCounterSpec(
                name = padName(pad.getAttribute("kind"), padIndex),
                addresses = addresses,
                max = 0,
            )
            padIndex += 1
        }
        val box = maintenanceBoxNames.any {
            EpsonPrinterCatalog.normalizeModelName(it) == EpsonPrinterCatalog.normalizeModelName(name)
        }
        if (box && (counters.isNotEmpty() || resetMap.isNotEmpty())) {
            return GapParse.Refused(GapRefusal.MAINTENANCE_BOX)
        }
        return GapParse.Accepted(
            GapProfile(
                modelName = name,
                controlModel = byteArrayOf((rkey and 0xFF).toByte(), ((rkey shr 8) and 0xFF).toByte()),
                keyword = keyword,
                wasteCounters = counters,
                resetMap = resetMap,
                source = source,
                version = version,
                usesMaintenanceBox = box,
            ),
        )
    }

    private fun readModel(text: String): Element? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isIgnoringComments = true
            isNamespaceAware = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        val nodes = document.getElementsByTagName("model")
        if (nodes.length != 1) return null
        return nodes.item(0) as? Element
    }

    private fun hexBytes(text: String): ByteArray = text.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .mapNotNull { token ->
            val hex = token.removePrefix("0x").removePrefix("0X")
            hex.toIntOrNull(16)?.takeIf { it in 0..0xFF }?.toByte()
        }
        .toByteArray()

    private fun decimals(text: String): List<Int> =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }

    private fun padName(kind: String, index: Int): String = when (kind.trim().lowercase()) {
        "main" -> "Main pad"
        "platen" -> "Platen pad"
        "borderless" -> "Borderless pad"
        else -> "Waste counter ${index + 1}"
    }
}

internal interface GapStorage {
    fun read(): String?
    fun write(blob: String)
}

/**
 * Private preferences, ten profiles, one per exact model name.
 *
 * A name the bundled catalog already has is not stored. Keeping it would
 * only be useful as an override, and a paste must not replace a published key.
 */
internal class GapProfileStore(
    private val storage: GapStorage,
) {
    fun save(profile: GapProfile, catalogued: Boolean): GapSave {
        if (catalogued) return GapSave.RefusedCatalog
        val next = load().filterNot { same(it.modelName, profile.modelName) } + profile
        val capped = if (next.size > MAX) next.takeLast(MAX) else next
        if (next.size > MAX && capped.none { same(it.modelName, profile.modelName) }) {
            return GapSave.RefusedFull
        }
        storage.write(capped.joinToString("\n---\n") { encode(it) })
        return GapSave.Stored
    }

    fun findExact(mdl: String?): GapProfile? = selectGap(mdl.orEmpty(), catalogued = false, gaps = load())

    fun delete(modelName: String) {
        val left = load().filterNot { same(it.modelName, modelName) }
        storage.write(left.joinToString("\n---\n") { encode(it) })
    }

    fun load(): List<GapProfile> {
        val blob = storage.read()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return blob.split("\n---\n").mapNotNull { block ->
            (GapProfileText.parse(block, emptySet()) as? GapParse.Accepted)?.profile
        }
    }

    private fun encode(profile: GapProfile): String = buildString {
        append("<model name=\"").append(escape(profile.modelName)).append("\"")
        append(" rkey=\"").append(rkey(profile.controlModel)).append("\"")
        append(" keyword=\"")
        profile.keyword.forEach { byte ->
            append("0x").append("%02x".format(byte.toInt() and 0xFF)).append(' ')
        }
        append("\" source=\"").append(escape(profile.source)).append("\"")
        append(" version=\"").append(escape(profile.version)).append("\">")
        profile.wasteCounters.forEach { counter ->
            append("<pad kind=\"main\" addresses=\"")
            append(counter.addresses.joinToString(" "))
            append("\" reset=\"")
            append(counter.addresses.joinToString(" ") { address ->
                (profile.resetMap[address] ?: 0).toString()
            })
            append("\"/>")
        }
        append("</model>")
    }

    private fun rkey(control: ByteArray): Int =
        (control[0].toInt() and 0xFF) or ((control[1].toInt() and 0xFF) shl 8)

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private fun same(left: String, right: String): Boolean =
        EpsonPrinterCatalog.normalizeModelName(left) == EpsonPrinterCatalog.normalizeModelName(right)

    companion object {
        const val MAX = 10
        const val PREFERENCE_FILE = "gap_profiles"
        const val KEY = "profiles"

        fun from(context: Context): GapProfileStore = GapProfileStore(
            object : GapStorage {
                private val preferences: SharedPreferences =
                    context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
                override fun read(): String? = preferences.getString(KEY, null)
                override fun write(blob: String) {
                    check(preferences.edit().putString(KEY, blob).commit()) {
                        "Preferences did not commit the local profile"
                    }
                }
            },
        )
    }
}
