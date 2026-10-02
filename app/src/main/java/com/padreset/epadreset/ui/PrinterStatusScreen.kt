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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
    // A percentage target needs a ceiling to take a fraction of. When the model
    // does not publish one — every model that comes from reinkpy rather than
    // from devices.xml — the reset writes the values that model's own record
    // publishes, and there is nothing for the user to aim at.
    val percentageWritable = availableCounters.isNotEmpty() && availableCounters.all { it.scaleKnown }

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

            // A statement, not a failure: the printer was unplugged, which is
            // not an operation that went wrong. Sits above the error callout
            // because it explains the state the error would be read against.
            uiState.notice?.let { notice ->
                PadCallout(text = notice, tone = PadTone.Info)
            }

            if (!uiState.error.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    PadCallout(
                        title = stringResource(R.string.callout_operation_failed),
                        text = uiState.error,
                        tone = PadTone.Danger,
                    )
                    uiState.modelReportBody?.let { report ->
                        ModelReportActions(
                            reportBody = report,
                            reportUrl = uiState.modelReportUrl,
                        )
                    }
                }
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
                    usesMaintenanceBox = uiState.usesMaintenanceBox,
                    busy = uiState.isBusy,
                    onReset = { showWarning = true },
                    onExplainUnsupported = { showWasteUnsupported = true },
                )
            }

            // Only after a write, and on failure as well as success: a run the
            // printer refused partway through has already overwritten part of
            // what was there, and this is the only record of it.
            uiState.wasteBackup?.let { backup ->
                PadCard {
                    PadSectionLabel(
                        text = stringResource(R.string.section_waste_backup),
                        trailing = { CopyBackupButton(backup) },
                    )
                    Text(
                        text = stringResource(R.string.waste_backup_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = pad.muted,
                    )
                    BackupPanel(backup)
                }
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
            PadBusyIndicator(
                modifier = Modifier.align(Alignment.Center),
                // Same condition as the card's detail line, and the same split:
                // the pill is the headline the user sees first, the card carries
                // the explanation. Kept short because the pill is only as wide as
                // its text.
                label = uiState.connectNote?.let { stringResource(R.string.connect_slow_brief) },
            )
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
                        // Without a published ceiling the only meaningful write
                        // is the model's own reset values, which the service
                        // applies when every counter is selected and the target
                        // is zero. Passing anything else would compute
                        // max * pct / 100 with max at zero and write zeros.
                        onReset(
                            if (percentageWritable) {
                                selectedCounters
                            } else {
                                availableCounters.map { it.name }.toSet()
                            },
                            if (percentageWritable) {
                                targetPercentageText.toIntOrNull()?.coerceIn(0, 100) ?: 0
                            } else {
                                0
                            },
                        )
                    },
                    enabled = if (percentageWritable) {
                        selectedCounters.isNotEmpty()
                    } else {
                        availableCounters.isNotEmpty()
                    },
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
                    if (percentageWritable) {
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
                    } else {
                        // No percentage field and no per-counter selection: the
                        // reset covers every address the model publishes, so a
                        // choice here would be one the service ignores.
                        Text(stringResource(R.string.dialog_reset_model_values))
                    }
                }
            },
        )
    }

    if (showWasteUnsupported) {
        PadAlertDialog(
            onDismissRequest = { showWasteUnsupported = false },
            // Matches the callout that opened it. When the curated list knows the
            // cause, the dialog must not fall back to listing both possibilities:
            // the user would read a confident statement and then a hedged one.
            title = {
                Text(
                    stringResource(
                        if (uiState.usesMaintenanceBox) R.string.waste_box_dialog_title
                        else R.string.waste_unsupported_dialog_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (uiState.usesMaintenanceBox) R.string.waste_box_dialog_body
                        else R.string.waste_unsupported_dialog_body,
                    ),
                )
            },
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
                    // Guarded rather than always shown: if the package lookup
                    // ever fails this would otherwise render "Version " with
                    // nothing after it.
                    if (uiState.appVersion.isNotBlank()) {
                        Text(stringResource(R.string.about_version, uiState.appVersion))
                    }
                    Text(stringResource(R.string.about_epson_only))
                    Text(stringResource(R.string.about_repo_label))

                    // Only when there is somewhere to send them. The app has no
                    // way to compare versions itself — that needs the INTERNET
                    // permission it does not hold — so this hands the address to
                    // the browser and says so, instead of pretending to check.
                    if (RELEASES_URL != null) {
                        PadButton(
                            text = stringResource(R.string.action_check_updates),
                            variant = PadButtonVariant.Secondary,
                            onClick = {
                                showAbout = false
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.about_updates_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = pad.muted,
                        )
                    }
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
            // Once the printer has been found, "scanning" is no longer what is
            // happening: the connection is working through its routes, and one
            // of them can cost several seconds. The note says so where the eye
            // already is, instead of leaving the wait unexplained.
            detail = uiState.connectNote ?: stringResource(R.string.status_scanning)
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

            // Only counters with a published ceiling can be ranked. The rest
            // report no percentage, and letting one stand in as "highest pad"
            // would print 0.00% for a printer whose pad may well be full.
            val worst = status.wasteCounters.filter { it.scaleKnown }.maxByOrNull { it.percentage }
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
    val listState = rememberLazyListState()

    // The log is a scrolling list inside a page that also scrolls. By default
    // Compose hands the scroll the list did not use up to the parent, so once
    // the log reached either end the gesture started moving the whole page
    // instead of stopping. Reaching the end of the log and having the page jump
    // is the app reacting to the wrong thing.
    //
    // Claiming the leftover keeps the page still. It is claimed only while the
    // log has lines out of view: a log short enough to fit is not scrollable at
    // all, and swallowing the gesture there would block the page behind it.
    val holdScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val info = listState.layoutInfo
                val hasLinesOutOfView = info.totalItemsCount > info.visibleItemsInfo.size
                return if (hasLinesOutOfView) available else Offset.Zero
            }
        }
    }

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
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(holdScroll),
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

/**
 * The bytes a write found before it changed anything.
 *
 * Plain text rather than a formatted table: it is meant to be copied out and
 * kept, not read on screen, and the same block is what someone would paste into
 * an issue when asking why a reset was refused.
 *
 * No inner scroll and no fixed height. The first version had both, which put a
 * scrollable region inside the page's own scroll: a drag that started on the
 * backup moved the box instead of the page, and the content below it could not
 * be reached. The page already scrolls, so the block just grows with its text.
 */
@Composable
private fun BackupPanel(backup: String) {
    val pad = PadTheme.colors
    val shape = RoundedCornerShape(PadRadius.m)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pad.sunken)
            .border(1.dp, pad.border, shape)
            .padding(PadSpace.md),
    ) {
        Text(
            text = backup,
            fontFamily = PadMono,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = pad.note,
        )
    }
}

@Composable
private fun CopyBackupButton(backup: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // Resolved up front: the click lambda below is not a composable scope.
    val copiedMessage = stringResource(R.string.backup_copied)

    PadIconButton(
        icon = Icons.Outlined.ContentCopy,
        contentDescription = stringResource(R.string.action_copy_backup),
        size = 40.dp,
        filled = false,
        onClick = {
            clipboard.setText(AnnotatedString(backup))
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        },
    )
}

/**
 * The way out of a "model not in the database" failure.
 *
 * The callout above it already says the situation is fixable. This is the part
 * that makes that true: without a way to hand over the model and the log, "we
 * would like to hear about it" is a sentence with no address on it.
 *
 * The note is shown before either action on purpose. The privacy policy says
 * the app transmits nothing, and it still does — the text goes to the clipboard
 * or to the browser, at the user's press, never on its own.
 */
@Composable
private fun ModelReportActions(reportBody: String, reportUrl: String?) {
    val pad = PadTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // Resolved up front: the click lambdas below are not composable scopes.
    val copiedMessage = stringResource(R.string.report_copied)

    Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
        Text(
            text = stringResource(R.string.report_model_note),
            style = MaterialTheme.typography.bodySmall,
            color = pad.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
            // Offered only when there is somewhere to file it. Until this fork
            // has a repository of its own there is no destination its maintainer
            // controls, and filing reports in the upstream's tracker would mean
            // opening issues about a build that author never made. Copying is
            // the honest fallback, and it is the whole feature while that holds.
            if (reportUrl != null) {
                PadButton(
                    text = stringResource(R.string.action_report_model),
                    variant = PadButtonVariant.Primary,
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reportUrl)))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            PadButton(
                text = stringResource(R.string.action_copy_report),
                variant = if (reportUrl != null) PadButtonVariant.Secondary else PadButtonVariant.Primary,
                onClick = {
                    clipboard.setText(AnnotatedString(reportBody))
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
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
    usesMaintenanceBox: Boolean,
    busy: Boolean,
    onReset: () -> Unit,
    onExplainUnsupported: () -> Unit,
) {
    val pad = PadTheme.colors
    val context = LocalContext.current

    // Three states, not two. An empty list used to always draw the placeholders
    // that are meant for "no printer yet", so a connected model with no counter
    // of its own showed two counters reading 0% next to an armed reset button —
    // a false all-clear for a printer whose maintenance box may well be full.
    // "Connected but nothing to reset" is a different thing and now says so.
    if (connected && !supportsWasteReset) {
        Column(verticalArrangement = Arrangement.spacedBy(PadSpace.md)) {
            PadCallout(
                // The curated list knows the cause for some models; for the rest
                // the wording names both possibilities rather than guessing.
                title = stringResource(
                    if (usesMaintenanceBox) R.string.waste_box_title else R.string.waste_unsupported_title,
                ),
                text = stringResource(
                    if (usesMaintenanceBox) R.string.waste_box_body else R.string.waste_unsupported_body,
                ),
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

        // Said once for the panel, not once per counter. These models routinely
        // have two or three counters, and the same sentence repeated under each
        // of them is a wall of text — which costs most to the reader who does
        // not have the language, because it reads as something important they
        // are failing to understand.
        if (connected && displayCounters.any { !it.scaleKnown }) {
            Text(
                text = stringResource(R.string.waste_scale_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = pad.muted,
            )
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
        counter.scaleKnown && severityFor(counter.percentage) == PadSeverity.Critical -> pad.danger
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
                // The raw byte value, not 0%, when the ceiling is unknown: a
                // zero would read as an empty pad.
                text = when {
                    !connected -> "—"
                    counter.scaleKnown -> counter.percentage.formatPercent()
                    else -> counter.rawValue.toString()
                },
                color = valueColor,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = PadMono,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (counter.scaleKnown) {
            HorizontalGauge(percentage = counter.percentage, fillColor = pad.accent)
        }
        // No bar when the ceiling is unknown: a bar is a fraction of a ceiling,
        // and there is none to take a fraction of. Why that is gets said once
        // for the whole panel rather than once per row.
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
