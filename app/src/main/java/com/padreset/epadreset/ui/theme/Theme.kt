package com.padreset.epadreset.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Shapes mirroring padreset's radius scale: 10 / 14 / 20 px.
 */
val PadShapes = Shapes(
    extraSmall = RoundedCornerShape(PadRadius.s),
    small = RoundedCornerShape(PadRadius.s),
    medium = RoundedCornerShape(PadRadius.m),
    large = RoundedCornerShape(PadRadius.m),
    extraLarge = RoundedCornerShape(PadRadius.l),
)

/**
 * Projects the padreset palette onto Material 3's colour slots so that stock
 * components (dialogs, checkboxes, text fields) inherit the brand instead of the
 * default purple, which is what the original build was showing.
 */
private fun padScheme(p: PadColors): ColorScheme {
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.accentStrong,
        inversePrimary = p.accentStrong,
        secondary = p.muted,
        onSecondary = p.card,
        secondaryContainer = p.sunken,
        onSecondaryContainer = p.fg,
        tertiary = p.ok,
        onTertiary = p.card,
        background = p.bg,
        onBackground = p.fg,
        surface = p.card,
        onSurface = p.fg,
        surfaceVariant = p.sunken,
        onSurfaceVariant = p.note,
        // Material 3 washes elevated surfaces (dialogs, menus, sheets) with this
        // colour at an alpha derived from the elevation. Two traps here:
        //   - pointing it at the brand accent turned every dialog blue-grey;
        //   - Color.Transparent does NOT disable it, because Transparent is
        //     (0,0,0,0) and Material only overrides the alpha, leaving a black wash.
        // Dialog surfaces set tonalElevation to zero explicitly (see PadAlertDialog);
        // this value is the safety net for the surfaces that do not.
        surfaceTint = p.card,
        surfaceContainerLowest = p.bg,
        surfaceContainerLow = p.card,
        surfaceContainer = p.card,
        // Material 3 paints AlertDialog with surfaceContainerHigh.
        surfaceContainerHigh = p.dialog,
        surfaceContainerHighest = p.dialog,
        inverseSurface = p.fg,
        inverseOnSurface = p.bg,
        error = p.danger,
        onError = if (p.isDark) Color(0xFF2A0A07) else Color(0xFFFFFFFF),
        errorContainer = p.dangerBg,
        onErrorContainer = p.danger,
        outline = p.borderStrong,
        outlineVariant = p.border,
        // Neutral black rather than a navy tint, so the dimmed layer behind a
        // dialog does not read as a blue-grey wash.
        scrim = Color(0x66000000),
    )
}

@Composable
fun EPadResetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val pad = if (darkTheme) PadDark else PadLight
    CompositionLocalProvider(LocalPadColors provides pad) {
        MaterialTheme(
            colorScheme = padScheme(pad),
            typography = PadTypography,
            shapes = PadShapes,
            content = content,
        )
    }
}

/** Convenience accessor so screens read `PadTheme.colors` rather than the local. */
object PadTheme {
    val colors: PadColors
        @Composable get() = LocalPadColors.current
}
