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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.padreset.epadreset.data.WasteCounterStatus
import com.padreset.epadreset.ui.components.PadAlertDialog
import com.padreset.epadreset.ui.components.PadButton
import com.padreset.epadreset.ui.components.PadButtonVariant
import com.padreset.epadreset.ui.components.PadCallout
import com.padreset.epadreset.ui.components.PadCard
import com.padreset.epadreset.ui.components.PadIconButton
import com.padreset.epadreset.ui.components.PadMetaRow
import com.padreset.epadreset.ui.components.PadSectionLabel
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

private const val REPO_URL = "https://github.com/tekware-it/android-epson-reset"

@Composable
fun PrinterStatusScreen(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onReset: (Set<String>, Int) -> Unit,
    onServiceClean: (String) -> Unit,
    onGenericClean: (Int, Boolean, Boolean) -> Unit,
    onNozzleCheck: (Int) -> Unit,
    onDismissActionSuccess: () -> Unit,
) {
    val pad = PadTheme.colors
    val context = LocalContext.current
    var showWarning by remember { mutableStateOf(false) }
    var showServiceCleaningDialog by remember { mutableStateOf(false) }
    var showGenericCleaningDialog by remember { mutableStateOf(false) }
    var showNozzleCheckDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    val availableCounters = uiState.status?.wasteCounters.orEmpty()
    val cleaningOptions = uiState.cleaningOptions
    var selectedCounters by remember(availableCounters) {
        mutableStateOf(availableCounters.map { it.name }.toSet())
    }
    var selectedCleaning by remember(cleaningOptions) {
        mutableStateOf(cleaningOptions.firstOrNull().orEmpty())
    }
    var selectedNozzleCheckType by remember { mutableStateOf(0) }
    var genericCleaningGroup by remember { mutableStateOf(0) }
    var genericPowerClean by remember { mutableStateOf(false) }
    var targetPercentageText by remember { mutableStateOf("0") }

    BackHandler(enabled = !showWarning && !showServiceCleaningDialog && !showGenericCleaningDialog && !showNozzleCheckDialog && !showAbout && !showExitDialog) {
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
            HeaderBar(onAbout = { showAbout = true })

            StatusHeroCard(
                uiState = uiState,
                onRefresh = if (uiState.status == null) onConnect else onRefresh,
            )

            if (!uiState.error.isNullOrBlank()) {
                PadCallout(
                    title = "Operation failed",
                    text = uiState.error,
                    tone = PadTone.Danger,
                )
            }

            PadCard {
                PadSectionLabel("Ink levels")
                InkLevelsPanel(
                    levels = uiState.status?.inkLevels.orEmpty(),
                    connected = uiState.status != null,
                )
            }

            PadCard {
                PadSectionLabel("Waste levels")
                WasteLevelsPanel(
                    counters = uiState.status?.wasteCounters.orEmpty(),
                    connected = uiState.status != null,
                    busy = uiState.isBusy,
                    onReset = { showWarning = true },
                )
            }

            PadCard {
                PadSectionLabel("Maintenance")
                MaintenancePanel(
                    cleaningOptions = cleaningOptions,
                    supportsGenericMaintenanceJobs = uiState.supportsGenericMaintenanceJobs,
                    connected = uiState.status != null,
                    busy = uiState.isBusy,
                    onClean = {
                        if (cleaningOptions.isNotEmpty()) {
                            showServiceCleaningDialog = true
                        } else {
                            showGenericCleaningDialog = true
                        }
                    },
                    onNozzleCheck = { showNozzleCheckDialog = true },
                )
            }

            PadCard {
                PadSectionLabel(
                    text = "Protocol log",
                    trailing = { CopyLogButton(uiState.logs) },
                )
                ProtocolLogPanel(uiState.logs)
            }

            Spacer(Modifier.height(PadSpace.lg))
        }

        if (uiState.isBusy) {
            BusyIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }

    if (showWarning) {
        PadAlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text("Reset waste counters") },
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
                    Text("Reset Counters")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWarning = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    Text("Resetting waste ink counters modifies internal printer firmware values. Use at your own risk.")
                    OutlinedTextField(
                        value = targetPercentageText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 3) {
                                targetPercentageText = input
                            }
                        },
                        label = { Text("Target percentage") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    availableCounters.forEach { counter ->
                        SelectableRow(
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

    if (showServiceCleaningDialog) {
        PadAlertDialog(
            onDismissRequest = { showServiceCleaningDialog = false },
            title = { Text("Head cleaning") },
            confirmButton = {
                Button(
                    onClick = {
                        showServiceCleaningDialog = false
                        onServiceClean(selectedCleaning)
                    },
                    enabled = selectedCleaning.isNotBlank(),
                ) {
                    Text("Start Cleaning")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showServiceCleaningDialog = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    Text("Head cleaning consumes ink. Choose the cleaning level supported by this printer.")
                    cleaningOptions.forEach { option ->
                        SelectableRow(
                            label = option,
                            selected = option == selectedCleaning,
                            multi = false,
                            onSelect = { checked ->
                                if (checked) selectedCleaning = option
                            },
                        )
                    }
                }
            },
        )
    }

    if (showGenericCleaningDialog) {
        val cleaningModes = listOf(
            "Clean all nozzles",
            "Clean the black ink nozzle",
            "Clean the color ink nozzles",
            "Head cleaning (alternative mode)",
        )
        PadAlertDialog(
            onDismissRequest = { showGenericCleaningDialog = false },
            title = { Text("Head cleaning") },
            confirmButton = {
                Button(
                    onClick = {
                        showGenericCleaningDialog = false
                        onGenericClean(genericCleaningGroup, genericPowerClean, genericCleaningGroup == 3)
                    },
                ) {
                    Text("Start Cleaning")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showGenericCleaningDialog = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    Text("This mode sends a generic Epson ESC/P2 maintenance job. Support may vary by model.")
                    cleaningModes.forEachIndexed { index, label ->
                        SelectableRow(
                            label = label,
                            selected = genericCleaningGroup == index,
                            multi = false,
                            onSelect = { checked ->
                                if (checked) genericCleaningGroup = index
                            },
                        )
                    }
                    SelectableRow(
                        label = "Power Clean",
                        selected = genericPowerClean,
                        multi = true,
                        onSelect = { genericPowerClean = it },
                    )
                }
            },
        )
    }

    if (showNozzleCheckDialog) {
        PadAlertDialog(
            onDismissRequest = { showNozzleCheckDialog = false },
            title = { Text("Nozzle check") },
            confirmButton = {
                Button(
                    onClick = {
                        showNozzleCheckDialog = false
                        onNozzleCheck(selectedNozzleCheckType)
                    },
                ) {
                    Text("Print Test")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNozzleCheckDialog = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    listOf("Standard nozzle test", "Alternative nozzle test").forEachIndexed { index, label ->
                        SelectableRow(
                            label = label,
                            selected = selectedNozzleCheckType == index,
                            multi = false,
                            onSelect = { checked ->
                                if (checked) selectedNozzleCheckType = index
                            },
                        )
                    }
                }
            },
        )
    }

    if (showAbout) {
        PadAlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("About") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    Text("Android Epson Reset")
                    Text("GitHub: tekware-it/android-epson-reset")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAbout = false
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                    },
                ) {
                    Text("Open Repo")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAbout = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (showExitDialog) {
        PadAlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit app") },
            text = { Text("Do you want to exit and close Android Epson Reset?") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        (context as? Activity)?.finish()
                    },
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    uiState.actionSuccessMessage?.let { message ->
        PadAlertDialog(
            onDismissRequest = onDismissActionSuccess,
            title = { Text("Operation completed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    Text(message)
                    Text("Donate now to support development of this and my other free apps, thank you!")
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                        },
                    ) {
                        Text("Open GitHub Repo")
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismissActionSuccess) {
                    Text("OK")
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Layout blocks
// ---------------------------------------------------------------------------

@Composable
private fun HeaderBar(onAbout: () -> Unit) {
    val pad = PadTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = pad.fg,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(PadSpace.sm))
        // Icon only, so the control stays recognisable whatever language the app
        // is running in. About is not a critical path, so mild ambiguity is fine.
        //
        // Sits flush with the page margin so the header reads as balanced against
        // the title. That means it does not share a column with Refresh, which
        // lives one card inset further in. Tried matching them; the header ended
        // up looking off-centre, and balance won over the column match.
        PadIconButton(
            icon = Icons.Outlined.Info,
            contentDescription = stringResource(R.string.action_about),
            onClick = onAbout,
            size = 40.dp,
            filled = false,
        )
    }
}

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
    val printerError = status?.error?.takeIf { it.isNotBlank() && !it.equals("None", ignoreCase = true) }

    val label: String
    val detail: String
    val accentColor: Color
    when {
        status == null && uiState.isBusy -> {
            label = "Working"
            detail = "Scanning for an Epson printer over USB OTG"
            accentColor = pad.accent
        }
        status == null -> {
            label = "No printer"
            detail = "Connect an Epson printer over USB OTG, then tap the refresh button"
            accentColor = pad.muted
        }
        printerError != null -> {
            label = printerError
            detail = "The printer reported an error"
            accentColor = pad.danger
        }
        else -> {
            label = status.state.ifBlank { "Ready" }
            detail = "Connected over USB OTG"
            accentColor = pad.ok
        }
    }

    PadCard {
        PadSectionLabel("Printer")

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
            Rule()

            PadMetaRow(label = "Model", value = status.modelName)
            PadMetaRow(label = "Serial", value = status.serialNumber)

            val worst = status.wasteCounters.maxByOrNull { it.percentage }
            if (worst != null) {
                PadMetaRow(
                    label = "Highest pad",
                    value = worst.percentage.formatPercent(),
                    valueColor = severityColor(severityFor(worst.percentage), pad),
                )
            }
        }
    }
}

@Composable
private fun BusyIndicator(modifier: Modifier = Modifier) {
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
        Text("Working…", style = MaterialTheme.typography.bodyMedium, color = pad.fg)
    }
}

@Composable
private fun Rule() {
    val pad = PadTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(pad.rule),
    )
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
                text = "No log entries yet.",
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

    PadButton(
        text = "Copy",
        variant = PadButtonVariant.Secondary,
        enabled = logs.isNotEmpty(),
        height = 34.dp,
        onClick = {
            clipboard.setText(AnnotatedString(allLogs))
            Toast.makeText(context, "Protocol log copied", Toast.LENGTH_SHORT).show()
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
            text = if (connected) "${level.percentage}%" else "—",
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
    busy: Boolean,
    onReset: () -> Unit,
) {
    val displayCounters = if (counters.isEmpty()) {
        listOf(
            WasteCounterStatus("Waste ink counter 0", 0, 100),
            WasteCounterStatus("Waste ink counter 1", 0, 100),
        )
    } else {
        counters.mapIndexed { index, counter ->
            val normalizedName = counter.name
                .takeIf { it.isNotBlank() }
                ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                ?: "Waste ink counter $index"
            counter.copy(name = normalizedName)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(PadSpace.md)) {
        displayCounters.forEach { counter ->
            WasteCounterRow(counter = counter, connected = connected)
        }

        PadCallout(
            title = "Resetting does not clean the pad",
            text = "The counter is electronic; the pad is physical. Wash, replace or redirect the " +
                "waste ink before resetting, or ink will eventually leak inside the printer.",
            tone = PadTone.Warn,
        )

        PadButton(
            text = "Reset Counters",
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
// Maintenance
// ---------------------------------------------------------------------------

@Composable
private fun MaintenancePanel(
    cleaningOptions: List<String>,
    supportsGenericMaintenanceJobs: Boolean,
    connected: Boolean,
    busy: Boolean,
    onClean: () -> Unit,
    onNozzleCheck: () -> Unit,
) {
    val pad = PadTheme.colors
    val enabled = connected && !busy
    Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
        PadButton(
            text = "Head Cleaning",
            variant = PadButtonVariant.Secondary,
            enabled = enabled && (cleaningOptions.isNotEmpty() || supportsGenericMaintenanceJobs),
            onClick = onClean,
            modifier = Modifier.fillMaxWidth(),
        )
        PadButton(
            text = "Nozzle Check",
            variant = PadButtonVariant.Secondary,
            enabled = enabled && supportsGenericMaintenanceJobs,
            onClick = onNozzleCheck,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Head cleaning consumes ink and adds to the waste ink counters.",
            style = MaterialTheme.typography.bodySmall,
            color = pad.muted,
        )
    }
}

// ---------------------------------------------------------------------------
// Dialogs helpers
// ---------------------------------------------------------------------------

/**
 * A full-width selectable row.
 *
 * The original used a checkbox for every group, including the ones that are
 * single-choice. Radio buttons now carry that meaning, checkboxes are kept only
 * where multiple selections are genuinely allowed.
 */
@Composable
private fun SelectableRow(
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

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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
