package com.padreset.epadreset.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padreset.epadreset.R
import com.padreset.epadreset.ui.theme.PadRadius
import com.padreset.epadreset.ui.theme.PadSpace
import com.padreset.epadreset.ui.theme.PadTheme

/**
 * Page header, shared by every page.
 *
 * The title is the only text; everything else is an icon-only control, so the
 * header reads the same whatever language the app runs in. [onBack] turns the
 * leading edge into a back arrow, which is how a secondary page returns to the
 * status page.
 *
 * The trailing actions sit flush with the page margin so the header reads as
 * balanced against the title.
 */
@Composable
fun PadHeaderBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val pad = PadTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            PadIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
                size = 40.dp,
                filled = false,
            )
            Spacer(Modifier.width(PadSpace.sm))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = pad.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(PadSpace.sm))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PadSpace.sm),
                content = trailing,
            )
        }
    }
}

/**
 * Floating "working" pill, shown while a job runs.
 *
 * Sits above the content rather than pushing it down, so a refresh does not make
 * the page jump.
 *
 * [label] overrides the default wording when there is something more specific to
 * say. It is the pill's whole width, so keep it to a few words: anything longer
 * belongs in the card below, which has the room to explain.
 */
@Composable
fun PadBusyIndicator(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val pad = PadTheme.colors
    val shape = RoundedCornerShape(PadRadius.m)
    Row(
        modifier = modifier
            .clip(shape)
            .background(pad.card)
            .border(1.dp, pad.border, shape)
            .padding(horizontal = PadSpace.lg, vertical = PadSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PadSpace.sm),
    ) {
        CircularProgressIndicator(
            color = pad.accent,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label ?: stringResource(R.string.status_busy_indicator),
            style = MaterialTheme.typography.bodyMedium,
            color = pad.fg,
        )
    }
}

/**
 * A full-width selectable row.
 *
 * The original used a checkbox for every group, including the ones that are
 * single-choice. Radio buttons now carry that meaning, checkboxes are kept only
 * where multiple selections are genuinely allowed.
 */
@Composable
fun PadSelectableRow(
    label: String,
    selected: Boolean,
    multi: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val pad = PadTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = pad.fg,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(PadSpace.sm))
        if (multi) {
            Checkbox(checked = selected, onCheckedChange = onSelect)
        } else {
            RadioButton(selected = selected, onClick = { onSelect(!selected) })
        }
    }
}

/** Hairline divider used between blocks inside a card. */
@Composable
fun PadRule(modifier: Modifier = Modifier) {
    val pad = PadTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(pad.rule),
    )
}
