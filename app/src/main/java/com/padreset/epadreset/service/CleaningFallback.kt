package com.padreset.epadreset.service

/**
 * Where a rejected service cleaning goes on the ESC/P2 path.
 *
 * [powerClean] stays false. A catalog "strong" mode is not the power clean
 * the generic dialog asks for separately, and that one fills the pad harder.
 */
internal data class CleaningFallback(
    val groupIndex: Int,
    val powerClean: Boolean,
)

/**
 * The ESC/P2 group for a service mode the printer answered `:NA;` to.
 *
 * Color-group names keep their group. Intensity-only names ("Gentle %
 * Cleaning") are an all-nozzle clean: the service channel on an L365 rejects
 * them, and leaving the fallback unmatched is what showed the user an error
 * instead of cleaning. Charging is not a head clean.
 */
internal fun serviceCleaningFallback(name: String): CleaningFallback? {
    val upper = name.uppercase()
    if (upper.contains("CHARG")) return null
    val group = when {
        upper.startsWith("ALL") -> 0
        upper.startsWith("BLK") || upper.contains("BLACK") -> 1
        upper.startsWith("YMC") || upper.contains("COLOR") || upper.contains("COLOUR") -> 2
        upper.contains("GENTLE") || upper.contains("NORMAL") ||
            upper.contains("MEDIUM") || upper.contains("STRONG") -> 0
        else -> return null
    }
    return CleaningFallback(groupIndex = group, powerClean = false)
}
