package com.padreset.epadreset.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens ported from padreset.com.
 *
 * The site exposes its palette as CSS custom properties. The values below are the
 * light theme defaults; the dark counterparts are taken from the same variables
 * under the site's dark scheme. Alpha channels use Compose's AARRGGBB order, so
 * padreset's `#1828401f` (RRGGBBAA) becomes `0x1F182840`.
 *
 * Semantic meaning matters here: this is the same domain as the website, so the
 * brand already has colours for "printer locked" (danger), "pads at the limit"
 * (warn) and "counter restored" (ok).
 */
@Immutable
data class PadColors(
    val bg: Color,
    val card: Color,
    /**
     * Surface for dialogs and other floating layers.
     *
     * Kept separate from [card] because Material 3 reads `surfaceContainerHigh`
     * for `AlertDialog`: pointing that at the tinted [sunken] colour gave every
     * popup a blue-grey background.
     */
    val dialog: Color,
    val sunken: Color,
    val fg: Color,
    val muted: Color,
    val note: Color,
    val accent: Color,
    val accentStrong: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val border: Color,
    val borderStrong: Color,
    val rule: Color,
    val danger: Color,
    val dangerBg: Color,
    val dangerBorder: Color,
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val warnBorder: Color,
    val isDark: Boolean,
)

val PadLight = PadColors(
    bg = Color(0xFFF6F8FB),
    card = Color(0xFFFAFBFE),
    dialog = Color(0xFFFFFFFF),
    sunken = Color(0xFFEAF0F7),
    fg = Color(0xFF131B26),
    muted = Color(0xFF4E5E75),
    note = Color(0xFF3F4E62),
    accent = Color(0xFF0B47C1),
    accentStrong = Color(0xFF09389A),
    onAccent = Color(0xFFF5F8FF),
    accentSoft = Color(0x170B47C1),
    border = Color(0x1F182840),
    borderStrong = Color(0x3D182840),
    rule = Color(0xFFE8EDF5),
    danger = Color(0xFFD02E1F),
    dangerBg = Color(0xFFFDECEA),
    dangerBorder = Color(0x47D02E1F),
    ok = Color(0xFF06844D),
    okSoft = Color(0xFFDCEEE3),
    warn = Color(0xFF7A5C00),
    warnSoft = Color(0xFFF3EDD8),
    warnBorder = Color(0x66E8BC3A),
    isDark = false,
)

val PadDark = PadColors(
    bg = Color(0xFF050814),
    card = Color(0xFF0B1120),
    dialog = Color(0xFF141B2B),
    sunken = Color(0xFF101828),
    fg = Color(0xFFE8EDF5),
    muted = Color(0xFFA3B4CA),
    note = Color(0xFFA3B4CA),
    accent = Color(0xFF3D7EFF),
    accentStrong = Color(0xFF6B9BFF),
    onAccent = Color(0xFF061024),
    accentSoft = Color(0x293D7EFF),
    border = Color(0x1AA8BEDC),
    borderStrong = Color(0x33A8BEDC),
    rule = Color(0xFF1B2438),
    danger = Color(0xFFF5685C),
    dangerBg = Color(0x1AF5685C),
    dangerBorder = Color(0x4DF5685C),
    ok = Color(0xFF59CF8E),
    okSoft = Color(0x2959CF8E),
    warn = Color(0xFFE8BC3A),
    warnSoft = Color(0x29E8BC3A),
    warnBorder = Color(0x4DE8BC3A),
    isDark = true,
)

val LocalPadColors = staticCompositionLocalOf { PadLight }

/** Spacing scale. padreset keeps its rhythm tight; these are the steps in use. */
object PadSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Corner radii, matching `--radius-s` / `--radius-m` / `--radius-l` on the site. */
object PadRadius {
    val s = 10.dp
    val m = 14.dp
    val l = 20.dp
}

/**
 * Severity of a waste ink counter reading.
 *
 * Every bar is drawn in the brand accent, so a row of counters reads as one calm
 * set rather than a traffic light. Colour is reserved for the single case that
 * genuinely needs action: a pad close enough to the limit that the printer is
 * about to lock itself.
 */
enum class PadSeverity { Normal, Critical }

fun severityFor(percentage: Double): PadSeverity =
    if (percentage >= 85.0) PadSeverity.Critical else PadSeverity.Normal
