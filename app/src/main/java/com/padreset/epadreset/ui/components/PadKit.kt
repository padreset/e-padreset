package com.padreset.epadreset.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.padreset.epadreset.ui.theme.PadRadius
import com.padreset.epadreset.ui.theme.PadSpace
import com.padreset.epadreset.ui.theme.PadTheme

/** Button emphasis levels used across the redesign. */
enum class PadButtonVariant { Primary, Secondary, Danger }

/** Tone of an informational block. */
enum class PadTone { Warn, Danger, Ok, Info }

/**
 * The surface used for every block of content.
 *
 * padreset builds its layout from soft cards on a tinted page background, with a
 * hairline border instead of a heavy outline. The original app used a 1dp grey
 * border on a flat grey fill with square corners.
 */
@Composable
fun PadCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = PadSpace.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pad = PadTheme.colors
    val shape = RoundedCornerShape(PadRadius.l)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pad.card)
            .border(1.dp, pad.border, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(PadSpace.md),
        content = content,
    )
}

/** Small uppercase label that opens a card, mirroring the site's eyebrow text. */
@Composable
fun PadSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val pad = PadTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = pad.muted,
        )
        trailing?.invoke()
    }
}

/**
 * Buttons.
 *
 * Primary is the single most likely next action. Danger is deliberately an
 * outlined style rather than a solid red block: writing waste counters is
 * destructive and should read as serious, not as an inviting big button.
 */
@Composable
fun PadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: PadButtonVariant = PadButtonVariant.Secondary,
    height: Dp = 46.dp,
) {
    val pad = PadTheme.colors
    val shape = RoundedCornerShape(PadRadius.s)
    val disabledContainer = pad.sunken
    val disabledContent = pad.muted.copy(alpha = 0.6f)
    val buttonModifier = modifier.height(height)

    when (variant) {
        PadButtonVariant.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = buttonModifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = pad.accent,
                contentColor = pad.onAccent,
                disabledContainerColor = disabledContainer,
                disabledContentColor = disabledContent,
            ),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }

        PadButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = buttonModifier,
            border = BorderStroke(1.dp, if (enabled) pad.borderStrong else pad.border),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = pad.card,
                contentColor = pad.fg,
                disabledContainerColor = disabledContainer,
                disabledContentColor = disabledContent,
            ),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }

        PadButtonVariant.Danger -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = buttonModifier,
            border = BorderStroke(1.dp, if (enabled) pad.dangerBorder else pad.border),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (enabled) pad.dangerBg else disabledContainer,
                contentColor = pad.danger,
                disabledContainerColor = disabledContainer,
                disabledContentColor = disabledContent,
            ),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** A compact status pill: a solid coloured dot. */
@Composable
fun PadStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Dialog wrapper with the padreset surface.
 *
 * The important part is `tonalElevation = 0.dp`. Material 3 tints elevated
 * surfaces with `colorScheme.surfaceTint`, which left every dialog washed with a
 * tint colour instead of sitting on a clean white surface. Setting the elevation
 * to zero removes the wash at the source rather than trying to neutralise it by
 * picking a clever tint colour.
 */
@Composable
fun PadAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val pad = PadTheme.colors
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = RoundedCornerShape(PadRadius.l),
        containerColor = pad.dialog,
        tonalElevation = 0.dp,
        titleContentColor = pad.fg,
        textContentColor = pad.note,
    )
}

/**
 * Circular icon-only action.
 *
 * Refresh used to be a full-width button sitting at the bottom of a scrolling
 * page, which buried the app's primary action below the fold. It now lives on
 * the status row, next to the state it refreshes.
 *
 * `filled = false` gives a quieter treatment for secondary actions, where a
 * solid accent circle would compete with the primary one.
 *
 * The description is still required: the icon carries the meaning visually, but
 * screen readers need something to announce.
 */
@Composable
fun PadIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    filled: Boolean = true,
) {
    val pad = PadTheme.colors
    val shape = CircleShape
    val background = when {
        !enabled -> pad.sunken
        filled -> pad.accent
        else -> pad.card
    }
    val borderColor = when {
        !enabled -> pad.border
        filled -> Color.Transparent
        else -> pad.borderStrong
    }
    val tint = when {
        !enabled -> pad.muted.copy(alpha = 0.55f)
        filled -> pad.onAccent
        else -> pad.note
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/**
 * Informational or warning block, using the site's tinted-surface pattern:
 * soft background, matching hairline border, coloured title.
 */
@Composable
fun PadCallout(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: PadTone = PadTone.Warn,
) {
    val pad = PadTheme.colors
    val background: Color
    val borderColor: Color
    val titleColor: Color
    when (tone) {
        PadTone.Warn -> {
            background = pad.warnSoft
            borderColor = pad.warnBorder
            titleColor = pad.warn
        }
        PadTone.Danger -> {
            background = pad.dangerBg
            borderColor = pad.dangerBorder
            titleColor = pad.danger
        }
        PadTone.Ok -> {
            background = pad.okSoft
            borderColor = pad.ok.copy(alpha = 0.35f)
            titleColor = pad.ok
        }
        PadTone.Info -> {
            background = pad.accentSoft
            borderColor = pad.accent.copy(alpha = 0.3f)
            titleColor = pad.accent
        }
    }
    val shape = RoundedCornerShape(PadRadius.m)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = PadSpace.md, vertical = PadSpace.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = pad.note)
    }
}

/** Key/value line used for serial numbers and technical metadata. */
@Composable
fun PadMetaRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val pad = PadTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = pad.muted)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: pad.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 230.dp),
        )
    }
}
