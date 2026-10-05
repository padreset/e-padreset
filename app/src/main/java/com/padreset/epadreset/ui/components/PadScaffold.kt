package com.padreset.epadreset.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay

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
 * How long a job has to run before the pill is worth announcing.
 *
 * Reading the status of a printer that is already connected takes a fraction of
 * this, so the pill used to appear and vanish inside a frame or two, which reads
 * as a glitch rather than as progress. Below the threshold nothing is shown, and
 * the press is still acknowledged by the refresh icon turning.
 *
 * Note that this is a threshold and not a fixed wait: the timer is cancelled the
 * moment the job ends, so a fast job shows nothing at all rather than something
 * late.
 *
 * Shared with the status card, which applies the same rule to its "Working"
 * headline: one threshold for both, so they can never disagree about whether
 * a job was worth announcing.
 */
internal const val BUSY_PILL_DELAY_MILLIS = 300L

/**
 * How long the pill takes to fade in and out.
 *
 * The delay on its own would move the flicker rather than remove it. A job that
 * outlasts the threshold by only a few frames would still flash a hard-edged box
 * - just at a slightly later moment. Fading means that job shows a ghost that
 * never reaches full opacity, which is the difference between a glitch and
 * something nobody can name.
 *
 * Shared with the status card's notice/error banner for the same reason: an
 * unanimated banner arrival pushes the page down in one frame, which reads as
 * a jump.
 */
internal const val BUSY_PILL_FADE_MILLIS = 150

/**
 * Floating "working" pill, shown while a job runs.
 *
 * Sits above the content rather than pushing it down, so a refresh does not make
 * the page jump.
 *
 * [busy] is the raw job flag; the pill decides for itself whether the job has
 * run long enough to be worth announcing, so callers pass the flag rather than
 * guarding the call with their own `if`.
 *
 * [label] overrides the default wording when there is something more specific to
 * say. It is the pill's whole width, so keep it to a few words: anything longer
 * belongs in the card below, which has the room to explain.
 */
@Composable
fun PadBusyIndicator(
    busy: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) {
            delay(BUSY_PILL_DELAY_MILLIS)
            visible = true
        } else {
            visible = false
        }
    }

    val pad = PadTheme.colors
    val shape = RoundedCornerShape(PadRadius.m)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(BUSY_PILL_FADE_MILLIS)),
        exit = fadeOut(tween(BUSY_PILL_FADE_MILLIS)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
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
}

/**
 * A full-width selectable row.
 *
 * The original used a checkbox for every group, including the ones that are
 * single-choice. Radio buttons now carry that meaning, checkboxes are kept only
 * where multiple selections are genuinely allowed.
 *
 * [supporting] is a quieter second line under the label, for the detail a caller
 * needs to show without letting it compete with the label itself. Optional, so
 * the groups that have nothing to add are unchanged.
 */
@Composable
fun PadSelectableRow(
    label: String,
    selected: Boolean,
    multi: Boolean,
    onSelect: (Boolean) -> Unit,
    supporting: String? = null,
) {
    val pad = PadTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(if (multi) Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = onSelect)
                else Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(true) })),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = pad.fg,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    color = pad.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.width(PadSpace.sm))
        if (multi) {
            Checkbox(checked = selected, onCheckedChange = null)
        } else {
            RadioButton(selected = selected, onClick = null)
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
