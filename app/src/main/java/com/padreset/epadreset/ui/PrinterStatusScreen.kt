package com.padreset.epadreset.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padreset.epadreset.R
import com.padreset.epadreset.data.InkLevel
import com.padreset.epadreset.data.PrinterStatusSnapshot
import com.padreset.epadreset.data.WasteCounterStatus
import com.padreset.epadreset.ui.components.PadAlertDialog
import com.padreset.epadreset.ui.components.PadButton
import com.padreset.epadreset.ui.components.PadButtonVariant
import com.padreset.epadreset.ui.components.PadBusyIndicator
import com.padreset.epadreset.ui.components.PadCallout
import com.padreset.epadreset.ui.components.PadCard
import com.padreset.epadreset.ui.components.PadHeaderBar
import com.padreset.epadreset.ui.components.PadIconButton
import com.padreset.epadreset.ui.components.PadMetaRow
import com.padreset.epadreset.ui.components.PadRule
import com.padreset.epadreset.ui.components.PadSectionLabel
import com.padreset.epadreset.ui.components.PadSelectableRow
import com.padreset.epadreset.ui.components.PadStatusDot
import com.padreset.epadreset.ui.components.PadTone
import com.padreset.epadreset.ui.theme.PadColors
import com.padreset.epadreset.ui.theme.PadMono
import com.padreset.epadreset.ui.theme.PadRadius
import com.padreset.epadreset.ui.theme.PadSeverity
import com.padreset.epadreset.ui.theme.PadSpace
import com.padreset.epadreset.ui.theme.PadTheme
import com.padreset.epadreset.ui.theme.severityFor
import java.util.Locale

/**
 * Icon for the maintenance entry point.
 *
 * PLACEHOLDER. Waiting on the Epson-style head-cleaning artwork; this is a
 * stand-in chosen because a wrench is the least ambiguous generic symbol. Swapping
 * it is a one-line change here.
 */
private val MaintenanceIcon = Icons.Outlined.Build

/**
 * The status page: what the printer is and how it is doing.
 *
 * Maintenance jobs live on [MaintenanceScreen], reached from the header. Only
 * the cleaning block moved there — the printer info and the ink levels stay
 * here, because they describe the printer's condition rather than act on it.
 */
@Composable
fun PrinterStatusScreen(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onReset: (Set<String>, Int) -> Unit,
    onOpenMaintenance: () -> Unit,
) {
    val pad = PadTheme.colors
    val context = LocalContext.current
    var showWarning by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showWasteUnsupported by remember { mutableStateOf(false) }
    val availableCounters = uiState.status?.wasteCounters.orEmpty()
    var selectedCounters by remember(availableCounters) {
        mutableStateOf(availableCounters.map { it.name }.toSet())
    }
    var targetPercentageText by remember { mutableStateOf("0") }

    BackHandler(enabled = !showWarning && !showAbout && !showExitDialog && !showWasteUnsupported) {
        showExitDialog = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pad.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PadSpace.lg, vertical = PadSpace.md),
            verticalArrangement = Arrangement.spacedBy(PadSpace.md),
        ) {
            PadHeaderBar(
                title = stringResource(R.string.app_name),
                trailing = {
                    PadIconButton(
                        icon = MaintenanceIcon,
                        contentDescription = stringResource(R.string.action_open_maintenance),
                        onClick = onOpenMaintenance,
                        size = 40.dp,
                        filled = false,
                    )
                    // Icon only, so the control stays recognisable whatever
                    // language the app is running in. About is not a critical
                    // path, so mild ambiguity is fine.
                    PadIconButton(
                        icon = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.action_about),
                        onClick = { showAbout = true },
                        size = 40.dp,
                        filled = false,
                    )
                },
            )

            StatusHeroCard(
                uiState = uiState,
                onRefresh = if (uiState.status == null) onConnect else onRefresh,
            )

            if (!uiState.error.isNullOrBlank()) {
                PadCallout(
                    title = stringResource(R.string.callout_operation_failed),
                    text = uiState.error,
                    tone = PadTone.Danger,
                )
            }

            PadCard {
                PadSectionLabel(stringResource(R.string.section_ink_levels))
                InkLevelsPanel(
                    levels = uiState.status?.inkLevels.orEmpty(),
                    connected = uiState.status != null,
                )
            }

            PadCard {
                PadSectionLabel(stringResource(R.string.section_waste_levels))
                WasteLevelsPanel(
                    counters = uiState.status?.wasteCounters.orEmpty(),
                    connected = uiState.status != null,
                    supportsWasteReset = uiState.supportsWasteReset,
                    busy = uiState.isBusy,
                    onReset = { showWarning = true },
                    onExplainUnsupported = { showWasteUnsupported = true },
                )
            }

            PadCard {
                PadSectionLabel(
                    text = stringResource(R.string.section_protocol_log),
                    trailing = { CopyLogButton(uiState.logs) },
                )
                ProtocolLogPanel(uiState.logs)
            }

            Spacer(Modifier.height(PadSpace.lg))
        }

        if (uiState.isBusy) {
            PadBusyIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }

    if (showWarning) {
        PadAlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(stringResource(R.string.dialog_reset_title)) },
            confirmButton = {
                Button(
                    onClick = {
                        showWarning = false
                        onReset(selectedCounters, targetPercentageText.toIntOrNull()?.coerceIn(0, 100) ?: 0)
                    },
                    enabled = selectedCounters.isNotEmpty(),
                ) {
                    // "Reset" is the word the whole ecosystem uses (WIC Reset,
                    // Adjustment Program, ez-reset, padreset itself) and the one
                    // users look for. The precise mechanic, including the fact
                    // that a non-zero target is a write rather than a reset,
                    // belongs in the dialog body where there is room to explain it.
                    Text(stringResource(R.string.action_reset_counters))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWarning = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    Text(stringResource(R.string.dialog_reset_warning))
                    OutlinedTextField(
                        value = targetPercentageText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 3) {
                                targetPercentageText = input
                            }
                        },
                        label = { Text(stringResource(R.string.field_target_percentage)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    availableCounters.forEach { counter ->
                        PadSelectableRow(
                            label = counter.name,
                            selected = counter.name in selectedCounters,
                            multi = true,
                            onSelect = { checked ->
                                selectedCounters = if (checked) {
                                    selectedCounters + counter.name
                                } else {
                                    selectedCounters - counter.name
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    if (showWasteUnsupported) {
        PadAlertDialog(
            onDismissRequest = { showWasteUnsupported = false },
            title = { Text(stringResource(R.string.waste_unsupported_dialog_title)) },
            text = { Text(stringResource(R.string.waste_unsupported_dialog_body)) },
            confirmButton = {
                Button(onClick = { showWasteUnsupported = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showAbout) {
        PadAlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.dialog_about_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    Text(stringResource(R.string.app_name))
                    Text(stringResource(R.string.about_repo_label))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAbout = false
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                    },
                ) {
                    Text(stringResource(R.string.action_open_repo))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showExitDialog) {
        PadAlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.dialog_exit_title)) },
            text = { Text(stringResource(R.string.dialog_exit_body, stringResource(R.string.app_name))) },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        (context as? Activity)?.finish()
                    },
                ) {
                    Text(stringResource(R.string.action_exit))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

}

// ---------------------------------------------------------------------------
// Layout blocks
// ---------------------------------------------------------------------------

/**
 * The headline status block, echoing the site's "Current status" panel.
 *
 * It answers the two questions a user actually has on opening the app: is a
 * printer connected, and how close is the waste pad to its limit.
 */
@Composable
private fun StatusHeroCard(
    uiState: MainUiState,
    onRefresh: () -> Unit,
) {
    val pad = PadTheme.colors
    val status = uiState.status
    // Compared by code, not by text. The old check tested the English word
    // "None" against a value the printer reported, which would have broken the
    // moment that label was translated.
    val hasPrinterError = status != null && status.errorCode != PrinterStatusSnapshot.ERROR_NONE

    // Resolved up front: stringResource cannot be called from inside the
    // non-composable ifBlank lambda below.
    val readyLabel = stringResource(R.string.status_ready)

    val label: String
    val detail: String
    val accentColor: Color
    when {
        status == null && uiState.isBusy -> {
            label = stringResource(R.string.status_working)
            detail = stringResource(R.string.status_scanning)
            accentColor = pad.accent
        }
        status == null -> {
            label = stringResource(R.string.status_no_printer)
            detail = stringResource(R.string.status_no_printer_hint)
            accentColor = pad.muted
        }
        hasPrinterError -> {
            label = printerErrorLabel(status.errorCode, status.error)
            detail = stringResource(R.string.status_printer_error)
            accentColor = pad.danger
        }
        else -> {
            label = printerStateLabel(status.stateCode, status.state).ifBlank { readyLabel }
            detail = stringResource(R.string.status_connected)
            accentColor = pad.ok
        }
    }

    PadCard {
        PadSectionLabel(stringResource(R.string.section_printer))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PadSpace.sm),
        ) {
            PadStatusDot(color = accentColor, size = 11.dp)
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = pad.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PadIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(
                    if (status == null) R.string.action_connect_printer else R.string.action_refresh_status,
                ),
                enabled = !uiState.isBusy,
                onClick = onRefresh,
            )
        }

        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = pad.muted,
        )

        if (status != null) {
            PadRule()

            PadMetaRow(label = stringResource(R.string.label_model), value = status.modelName)
            PadMetaRow(label = stringResource(R.string.label_serial), value = status.serialNumber)

            val worst = status.wasteCounters.maxByOrNull { it.percentage }
            if (worst != null) {
                PadMetaRow(
                    label = stringResource(R.string.label_highest_pad),
                    value = worst.percentage.formatPercent(),
                    valueColor = severityColor(severityFor(worst.percentage), pad),
                )
            }
        }
    }
}

/**
 * The protocol log.
 *
 * Rendered as a subtle inset panel on the card rather than a dark terminal: a
 * dark block inside an otherwise light screen read as a foreign element. The
 * tinted blue-grey keeps it visibly "technical" without breaking the surface.
 */
@Composable
private fun ProtocolLogPanel(logs: List<String>) {
    val pad = PadTheme.colors
    val shape = RoundedCornerShape(PadRadius.m)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(shape)
            .background(pad.sunken)
            .border(1.dp, pad.border, shape)
            .padding(PadSpace.md),
    ) {
        if (logs.isEmpty()) {
            Text(
                text = stringResource(R.string.log_empty),
                fontFamily = PadMono,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = pad.muted,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(logs.takeLast(80).asReversed()) { line ->
                    Text(
                        text = line,
                        fontFamily = PadMono,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = pad.note,
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyLogButton(logs: List<String>) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val allLogs = logs.joinToString(separator = "\n")
    // Resolved up front: the click lambda below is not a composable scope, so
    // stringResource cannot be called from inside it.
    val copiedMessage = stringResource(R.string.log_copied)

    // Icon only, matching the other two circular controls. The description is
    // what carries the meaning for screen readers, so it is not optional here.
    PadIconButton(
        icon = Icons.Outlined.ContentCopy,
        contentDescription = stringResource(R.string.action_copy_log),
        enabled = logs.isNotEmpty(),
        size = 40.dp,
        filled = false,
        onClick = {
            clipboard.setText(AnnotatedString(allLogs))
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        },
    )
}

// ---------------------------------------------------------------------------
// Ink levels
// ---------------------------------------------------------------------------

@Composable
private fun InkLevelsPanel(levels: List<InkLevel>, connected: Boolean) {
    // Placeholder row shown before a printer is connected. Kept in the same order
    // the real levels are sorted into, so the layout does not reshuffle on connect.
    val displayLevels = if (levels.isEmpty()) {
        listOf(
            InkLevel("Black", 0),
            InkLevel("Cyan", 0),
            InkLevel("Magenta", 0),
            InkLevel("Yellow", 0),
        )
    } else {
        levels.sortedBy { inkSortOrder(it.color) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val itemsPerRow = displayLevels.size.coerceAtLeast(1)
        val compact = displayLevels.size >= 7
        val spacing = if (compact) PadSpace.xs else PadSpace.sm
        val cellWidth = (maxWidth - spacing * (itemsPerRow - 1)) / itemsPerRow
        val gaugeHeight = if (compact) 62.dp else 74.dp
        val gaugeFillWidth = if (compact) 0.86f else 0.72f
        val labelFontSize = if (compact) 11.sp else TextUnit.Unspecified
        val valueFontSize = if (compact) 11.sp else TextUnit.Unspecified

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            displayLevels.forEach { level ->
                InkLevelCell(
                    level = level,
                    connected = connected,
                    modifier = Modifier.width(cellWidth),
                    gaugeHeight = gaugeHeight,
                    gaugeFillWidth = gaugeFillWidth,
                    labelFontSize = labelFontSize,
                    valueFontSize = valueFontSize,
                )
            }
        }
    }
}

@Composable
private fun InkLevelCell(
    level: InkLevel,
    connected: Boolean,
    modifier: Modifier = Modifier,
    gaugeHeight: Dp = 74.dp,
    gaugeFillWidth: Float = 0.72f,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    valueFontSize: TextUnit = TextUnit.Unspecified,
) {
    val pad = PadTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = inkShortLabel(level.color),
            color = pad.muted,
            style = MaterialTheme.typography.labelSmall.withFontSize(labelFontSize),
            maxLines = 1,
        )
        Spacer(Modifier.height(PadSpace.xs))
        VerticalInkGauge(
            percentage = level.percentage,
            fillColor = inkColor(level.color),
            gaugeHeight = gaugeHeight,
            modifier = Modifier.fillMaxWidth(gaugeFillWidth),
        )
        Spacer(Modifier.height(PadSpace.xs))
        Text(
            text = if (connected) stringResource(R.string.percent_value, level.percentage) else "—",
            color = if (connected) inkLevelColor(level.percentage, pad) else pad.muted,
            style = MaterialTheme.typography.labelMedium.withFontSize(valueFontSize),
            maxLines = 1,
        )
    }
}

@Composable
private fun VerticalInkGauge(
    percentage: Int,
    fillColor: Color,
    gaugeHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val pad = PadTheme.colors
    val target = (percentage.coerceIn(0, 100) / 100f)
    val fill by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 450),
        label = "inkGaugeFill",
    )
    val shape = RoundedCornerShape(PadRadius.s)

    Box(
        modifier = modifier
            .height(gaugeHeight)
            .clip(shape)
            .background(pad.sunken)
            .border(1.dp, pad.border, shape),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (fill > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gaugeHeight * fill)
                    .background(fillColor),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Waste levels
// ---------------------------------------------------------------------------

@Composable
private fun WasteLevelsPanel(
    counters: List<WasteCounterStatus>,
    connected: Boolean,
    supportsWasteReset: Boolean,
    busy: Boolean,
    onReset: () -> Unit,
    onExplainUnsupported: () -> Unit,
) {
    val context = LocalContext.current

    // Three states, not two. An empty list used to always draw the placeholders
    // that are meant for "no printer yet", so a connected model with no counter
    // of its own showed two counters reading 0% next to an armed reset button —
    // a false all-clear for a printer whose maintenance box may well be full.
    // "Connected but nothing to reset" is a different thing and now says so.
    if (connected && !supportsWasteReset) {
        Column(verticalArrangement = Arrangement.spacedBy(PadSpace.md)) {
            PadCallout(
                title = stringResource(R.string.waste_unsupported_title),
                text = stringResource(R.string.waste_unsupported_body),
                tone = PadTone.Info,
            )
            PadButton(
                text = stringResource(R.string.action_more_info),
                variant = PadButtonVariant.Secondary,
                onClick = onExplainUnsupported,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    val displayCounters = if (counters.isEmpty()) {
        listOf(
            WasteCounterStatus(context.getString(R.string.waste_counter_default_name, 0), 0, 100),
            WasteCounterStatus(context.getString(R.string.waste_counter_default_name, 1), 0, 100),
        )
    } else {
        counters.mapIndexed { index, counter ->
            val normalizedName = counter.name
                .takeIf { it.isNotBlank() }
                ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                ?: context.getString(R.string.waste_counter_default_name, index)
            counter.copy(name = normalizedName)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(PadSpace.md)) {
        displayCounters.forEach { counter ->
            WasteCounterRow(counter = counter, connected = connected)
        }

        PadCallout(
            title = stringResource(R.string.pad_warning_title),
            text = stringResource(R.string.pad_warning_body),
            tone = PadTone.Warn,
        )

        PadButton(
            text = stringResource(R.string.action_reset_counters),
            variant = PadButtonVariant.Danger,
            enabled = connected && !busy,
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WasteCounterRow(counter: WasteCounterStatus, connected: Boolean) {
    val pad = PadTheme.colors
    // Every bar is drawn in the brand accent, so a set of counters reads as one
    // calm group instead of a traffic light. Only the value text escalates, and
    // only once the pad is close enough to the limit to matter.
    val valueColor = when {
        !connected -> pad.muted
        severityFor(counter.percentage) == PadSeverity.Critical -> pad.danger
        else -> pad.fg
    }

    Column(verticalArrangement = Arrangement.spacedBy(PadSpace.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = counter.name,
                color = pad.fg,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(PadSpace.sm))
            Text(
                text = if (connected) counter.percentage.formatPercent() else "—",
                color = valueColor,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = PadMono,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalGauge(percentage = counter.percentage, fillColor = pad.accent)
    }
}

@Composable
private fun HorizontalGauge(percentage: Double, fillColor: Color) {
    val pad = PadTheme.colors
    val target = (percentage / 100.0).coerceIn(0.0, 1.0).toFloat()
    val fill by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 450),
        label = "wasteGaugeFill",
    )
    val shape = RoundedCornerShape(PadRadius.s)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(shape)
            .background(pad.sunken),
    ) {
        if (fill > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fill)
                    .height(10.dp)
                    .background(fillColor),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs helpers
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Maps the printer's own status code to a label.
 *
 * The mapping mirrors the code set in EpsonProtocol, so a code added there needs
 * adding here too. An unmapped code falls back to the protocol's English text
 * rather than showing something wrong.
 */
@Composable
private fun printerStateLabel(code: Int, fallback: String): String {
    val errorLabel = stringResource(R.string.printer_state_error)
    return when (code) {
        0x01 -> stringResource(R.string.printer_state_self_printing)
        0x02 -> stringResource(R.string.printer_state_busy)
        0x03 -> stringResource(R.string.printer_state_waiting)
        0x04 -> stringResource(R.string.printer_state_idle)
        0x05 -> stringResource(R.string.printer_state_pause)
        0x07 -> stringResource(R.string.printer_state_cleaning)
        else -> fallback.ifBlank { errorLabel }
    }
}

/** Same idea as [printerStateLabel], for the printer's error code. */
@Composable
private fun printerErrorLabel(code: Int, fallback: String): String = when (code) {
    0x01 -> stringResource(R.string.printer_error_interface)
    0x04 -> stringResource(R.string.printer_error_paper_jam)
    0x05 -> stringResource(R.string.printer_error_ink_out)
    0x10 -> stringResource(R.string.printer_error_service_required)
    0x25 -> stringResource(R.string.printer_error_cover_open)
    else -> fallback
}

/** Colour for a single headline value, such as the highest pad reading. */
private fun severityColor(severity: PadSeverity, pad: PadColors): Color = when (severity) {
    PadSeverity.Normal -> pad.fg
    PadSeverity.Critical -> pad.danger
}

/** Ink behaves the opposite way to waste: a low reading is the alarming one. */
private fun inkLevelColor(percentage: Int, pad: PadColors): Color = when {
    percentage <= 10 -> pad.danger
    else -> pad.fg
}

private fun inkSortOrder(color: String): Int = when (color.lowercase(Locale.ROOT)) {
    "black" -> 0
    "light black" -> 1
    "gray", "grey" -> 2
    "light gray", "light grey" -> 3
    "cyan" -> 4
    "light cyan" -> 5
    "blue" -> 6
    "magenta" -> 7
    "light magenta" -> 8
    "red" -> 9
    "yellow" -> 10
    "dark yellow" -> 11
    "orange" -> 12
    "gloss optimizer" -> 13
    else -> 99
}

private fun inkColor(color: String): Color = when (color.lowercase(Locale.ROOT)) {
    "black" -> Color(0xFF1A1A1A)
    "yellow" -> Color(0xFFE8BC3A)
    "magenta" -> Color(0xFFD6006E)
    "cyan" -> Color(0xFF00A6D6)
    "light cyan" -> Color(0xFF6FE8FF)
    "light magenta" -> Color(0xFFFF89E9)
    "dark yellow" -> Color(0xFFC08A00)
    "gray", "grey" -> Color(0xFF6B7280)
    "light gray", "light grey" -> Color(0xFFA8BEDC)
    "light black" -> Color(0xFF5A6A80)
    "red" -> Color(0xFFD02E1F)
    "blue" -> Color(0xFF0B47C1)
    "orange" -> Color(0xFFE07A2F)
    "gloss optimizer" -> Color(0xFF94A5BC)
    else -> Color(0xFF5A6A80)
}

private fun inkShortLabel(color: String): String = when (color.lowercase(Locale.ROOT)) {
    "black" -> "BK"
    "cyan" -> "CY"
    "magenta" -> "MG"
    "yellow" -> "YE"
    "light cyan" -> "LC"
    "light magenta" -> "LM"
    "dark yellow" -> "DY"
    "gray", "grey" -> "GY"
    "light black" -> "LK"
    "red" -> "RD"
    "blue" -> "BL"
    "gloss optimizer" -> "GO"
    "light gray", "light grey" -> "LG"
    "orange" -> "OR"
    else -> color.take(2).uppercase(Locale.ROOT)
}

private fun androidx.compose.ui.text.TextStyle.withFontSize(fontSize: TextUnit): androidx.compose.ui.text.TextStyle {
    return if (fontSize == TextUnit.Unspecified) this else copy(fontSize = fontSize)
}

private fun Double.formatPercent(): String = String.format(Locale.US, "%.2f%%", this)
