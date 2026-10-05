package com.padreset.epadreset.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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
import com.padreset.epadreset.BuildConfig
import com.padreset.epadreset.R
import com.padreset.epadreset.data.BackupOrigin
import com.padreset.epadreset.data.BackupTextParser
import com.padreset.epadreset.data.InkLevel
import com.padreset.epadreset.data.ParsedBackup
import com.padreset.epadreset.data.PrinterStatusSnapshot
import com.padreset.epadreset.data.WasteBackupRecord
import com.padreset.epadreset.data.WasteCounterSpec
import com.padreset.epadreset.data.WasteCounterStatus
import com.padreset.epadreset.data.backupModelMatches
import com.padreset.epadreset.ui.components.BUSY_PILL_DELAY_MILLIS
import com.padreset.epadreset.ui.components.BUSY_PILL_FADE_MILLIS
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
import java.text.DateFormat
import java.util.Date
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
    logs: StateFlow<List<String>>,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onReset: (Set<String>, Int) -> Unit,
    onRestore: (String?, Map<Int, Byte>) -> Unit,
    onOpenMaintenance: () -> Unit,
    onPasteGap: (String) -> String? = { null },
    onDeleteGap: () -> Unit = {},
) {
    val pad = PadTheme.colors
    val context = LocalContext.current
    var showWarning by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showWasteUnsupported by remember { mutableStateOf(false) }
    var showPasteGap by remember { mutableStateOf(false) }
    var showPasteBackup by remember { mutableStateOf(false) }
    var showModelReport by remember(uiState.modelReportBody) { mutableStateOf(false) }
    // The record waiting on a confirmation. Held here rather than in the card so
    // the dialog survives the card recomposing, and so the BackHandler can see it.
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    val availableCounters = uiState.status?.wasteCounters.orEmpty()
    // Keyed by content, not by list identity: every status refresh builds a new
    // list, and keying on the list itself wiped the user's deselection after
    // each read. A different model (different names) still resets the choice.
    var selectedCounters by remember(availableCounters.map { it.name }) {
        mutableStateOf(availableCounters.map { it.name }.toSet())
    }
    var targetPercentageText by remember { mutableStateOf("0") }
    // A percentage target needs a ceiling to take a fraction of. When the model
    // does not publish one — every model that comes from reinkpy rather than
    // from devices.xml — the reset writes the values that model's own record
    // publishes, and there is nothing for the user to aim at.
    val percentageWritable = availableCounters.isNotEmpty() && availableCounters.all { it.scaleKnown }
    // Per-counter choice additionally needs bytes that belong to this counter
    // alone: touching one counter must not rewrite another, whether whole shared
    // bytes (RX700's 0x2E/0x2F in two counters), shared nibbles (L3110's 0x2F
    // halves) or masked bytes. On such models the dialog offers the full set or
    // nothing, like the no-ceiling case above.
    //
    // Read from the state rather than recomputed here. This used to redo the
    // overlap arithmetic inline, which meant a second copy of the rule that the
    // service could drift from — and it never saw the masks.
    val selectableCounters = percentageWritable && uiState.untouchableAddresses.isEmpty()

    // The target field is a development tool, not a feature.
    //
    // It was the only way to write a counter up as well as down, and nothing in
    // the ecosystem allows that: Epson's own Adjustment Program, WIC Reset and
    // every free tool write the model's own reset values, usually zero, and give
    // the user no say in it. Raising a counter has no legitimate use — it does
    // not make the pads hold more ink, it only brings the block closer — and the
    // one real use it had was testing the write path on hardware.
    //
    // So the reset writes the model's own reset values, from whatever the
    // counter currently reads. Those values are often zero, but not always.
    // BuildConfig.DEBUG is a compile-time constant, so the branch below is dead
    // in a release build and the field cannot be reached. Release minification
    // is on, so R8 may also remove that branch. The gate guarantees it cannot
    // be reached, not that the bytes are absent.
    val targetValueAllowed = BuildConfig.DEBUG && percentageWritable

    BackHandler(
        enabled = !showWarning && !showAbout && !showExitDialog && !showWasteUnsupported &&
            !showPasteBackup && !showModelReport && pendingRestore == null,
    ) {
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
            // Animated in and out rather than snapped: the banner pushes the
            // cards below it down, and an unanimated arrival reads as the page
            // jumping — the same glitch the Working headline had. The reveal is
            // explicitly top-down, and content swaps crossfade instead of
            // replaying the expand: a refresh replaces the banner within
            // milliseconds, and an expand interrupted by another expand reads
            // as sliding in from an edge.
            AnimatedVisibility(
                visible = uiState.notice != null || !uiState.error.isNullOrBlank(),
                enter = fadeIn(tween(BUSY_PILL_FADE_MILLIS)) +
                    expandVertically(tween(BUSY_PILL_FADE_MILLIS), expandFrom = Alignment.Top),
                exit = fadeOut(tween(BUSY_PILL_FADE_MILLIS)) +
                    shrinkVertically(tween(BUSY_PILL_FADE_MILLIS), shrinkTowards = Alignment.Top),
            ) {
                Crossfade(
                    targetState = Triple(
                        uiState.notice,
                        uiState.error,
                        uiState.modelReportBody != null,
                    ),
                ) { (notice, error, hasReport) ->
                    Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                        notice?.let {
                            PadCallout(text = it, tone = PadTone.Info)
                        }

                        if (!error.isNullOrBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                                PadCallout(
                                    title = stringResource(R.string.callout_operation_failed),
                                    text = error,
                                    tone = PadTone.Danger,
                                )
                                if (hasReport) {
                                    ModelReportActions(
                                        onReview = { showModelReport = true },
                                    )
                                }
                            }
                        }
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
                    readOnlySession = uiState.readOnlySession,
                    unverifiedLocal = uiState.unverifiedLocalProfile,
                    busy = uiState.isBusy,
                    onReset = { showWarning = true },
                    onExplainUnsupported = { showWasteUnsupported = true },
                    onPasteGap = { showPasteGap = true },
                    onDeleteGap = onDeleteGap,
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

            // The backups that were saved to disk, as opposed to the one taken
            // just above. A sibling card rather than part of that block, because
            // that one only exists after a write in this session — and the record
            // a user reaches for is usually the one from a session that has
            // already ended. That is the whole reason the history is persisted.
            if (uiState.status != null && uiState.supportsWasteReset) {
                RestoreBackupCard(
                    history = uiState.backupHistory,
                    connectedModel = uiState.connectedModelName.orEmpty(),
                    specs = uiState.wasteCounterSpecs,
                    busy = uiState.isBusy,
                    onRestore = { record ->
                        pendingRestore = PendingRestore(
                            model = record.model,
                            values = record.values,
                            timestamp = record.timestamp,
                        )
                    },
                    onPaste = { showPasteBackup = true },
                )
            }

            ProtocolLogCard(logs)

            Spacer(Modifier.height(PadSpace.lg))
        }

        PadBusyIndicator(
            busy = uiState.isBusy,
            modifier = Modifier.align(Alignment.Center),
            // Same condition as the card's detail line, and the same split:
            // the pill is the headline the user sees first, the card carries
            // the explanation. Kept short because the pill is only as wide as
            // its text.
            label = uiState.connectNote?.let { stringResource(R.string.connect_slow_brief) },
        )
    }

    if (showModelReport) {
        uiState.modelReportBody?.let { report ->
            ModelSupportDialog(
                reportBody = report,
                reportUrl = uiState.modelReportUrl,
                onDismiss = { showModelReport = false },
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
                            if (selectableCounters) {
                                selectedCounters
                            } else {
                                availableCounters.map { it.name }.toSet()
                            },
                            if (targetValueAllowed) {
                                targetPercentageText.toIntOrNull()?.coerceIn(0, 100) ?: 0
                            } else {
                                0
                            },
                        )
                    },
                    enabled = if (selectableCounters) {
                        selectedCounters.isNotEmpty()
                    } else {
                        availableCounters.isNotEmpty()
                    },
                ) {
                    // "Reset" is the word the whole ecosystem uses (WIC Reset,
                    // Adjustment Program, ez-reset, padreset itself) and the one
                    // users look for.
                    Text(stringResource(
                        if (uiState.unverifiedLocalProfile) {
                            R.string.action_reset_counters_unverified
                        } else {
                            R.string.action_reset_counters
                        },
                    ))
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
                    if (targetValueAllowed) {
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
                    }
                    if (selectableCounters) {
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
                        // No per-counter selection: the reset covers every address
                        // the model publishes, so a choice here would be one the
                        // service ignores — and on shared-byte models a partial
                        // choice would certify an incomplete reset.
                        Text(stringResource(R.string.dialog_reset_model_values))
                    }
                }
            },
        )
    }

    if (showWasteUnsupported) {
        WasteExplanationDialog(
            uiState = uiState,
            onDismiss = { showWasteUnsupported = false },
            onReviewReport = {
                showWasteUnsupported = false
                showModelReport = true
            },
        )
    }

    if (showPasteGap) {
        PasteGapDialog(
            onDismiss = { showPasteGap = false },
            onPaste = onPasteGap,
        )
    }

    if (showPasteBackup) {
        PasteBackupDialog(
            onDismiss = { showPasteBackup = false },
            onParsed = { parsed ->
                showPasteBackup = false
                // No timestamp: this record was never saved, so there is no time
                // to show and nothing to select in the list afterwards.
                pendingRestore = PendingRestore(
                    model = parsed.model,
                    values = parsed.values,
                    timestamp = null,
                )
            },
        )
    }

    pendingRestore?.let { pending ->
        RestoreConfirmDialog(
            pending = pending,
            counters = availableCounters,
            specs = uiState.wasteCounterSpecs,
            onDismiss = { pendingRestore = null },
            onConfirm = {
                pendingRestore = null
                onRestore(pending.model, pending.values)
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

                    // Same shape as the releases button above, and for the same
                    // reason: an ask only appears next to the means to answer it.
                    // While DONATE_URL is null none of this renders, so the app
                    // never asks for money it has nowhere to send.
                    if (DONATE_URL != null) {
                        PadButton(
                            text = stringResource(R.string.action_support_development),
                            variant = PadButtonVariant.Secondary,
                            onClick = {
                                showAbout = false
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.about_donation),
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

    // Same threshold as the floating pill, for the same reason: a job faster
    // than this never announces itself, so the card must not flash "Working"
    // for it either. Without the delay, a tap with no printer attached flips
    // the headline for a few frames and the error banner below pops in and
    // out, which reads as the whole page jumping.
    var workingVisible by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isBusy) {
        if (uiState.isBusy) {
            delay(BUSY_PILL_DELAY_MILLIS)
            workingVisible = true
        } else {
            workingVisible = false
        }
    }

    val label: String
    val detail: String
    val accentColor: Color
    when {
        status == null && workingVisible -> {
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
                // The one icon here that means "do it again", so it is the one
                // that turns when pressed.
                spinOnPress = true,
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
 * The log card, and the only place that collects the log flow.
 *
 * Collecting here rather than at the top of the tree is the whole point of the
 * flow being separate: a log line redraws this card and nothing else. The write
 * path logs per byte, so during a reset that is a hundred redraws of one panel
 * instead of a hundred recompositions of the screen.
 */
@Composable
private fun ProtocolLogCard(logs: StateFlow<List<String>>) {
    val lines by logs.collectAsStateWithLifecycle()
    PadCard {
        PadSectionLabel(
            text = stringResource(R.string.section_protocol_log),
            trailing = { CopyLogButton(lines) },
        )
        ProtocolLogPanel(lines)
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
                return if (hasLinesOutOfView) Offset(0f, available.y) else Offset.Zero
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
                // A log reads top to bottom: the first event at the top, the
                // last one at the bottom. The data arrives oldest first, so it
                // is reversed to put the newest item at index 0, and the layout
                // is reversed to draw index 0 at the bottom. Together they give
                // the reading order, and the list stays anchored at the newest
                // line, so a new entry appears where the eye already is instead
                // of pushing the whole panel down.
                reverseLayout = true,
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
 * A record waiting on the user's confirmation.
 *
 * [timestamp] is null for a pasted backup, which was never saved and so has no
 * time to show.
 */
private data class PendingRestore(
    val model: String?,
    val values: Map<Int, Byte>,
    val timestamp: Long?,
)

/**
 * The backups saved to disk, and the two ways to put one back.
 *
 * The note says the backup is saved automatically rather than offering a save
 * button, because the feature only works if the record already exists by the time
 * the user knows they need it — which is usually long after the app was closed.
 *
 * The restore button is a danger variant for the same reason the reset one is:
 * both write to the printer, and this one writes the counters upward.
 */
@Composable
private fun RestoreBackupCard(
    history: List<WasteBackupRecord>,
    connectedModel: String,
    specs: List<WasteCounterSpec>,
    busy: Boolean,
    onRestore: (WasteBackupRecord) -> Unit,
    onPaste: () -> Unit,
) {
    val pad = PadTheme.colors
    // The newest record starts selected: it is the one a user who has just
    // realised a mistake almost always wants.
    var selected by remember(history) { mutableStateOf(history.firstOrNull()?.timestamp) }

    PadCard {
        PadSectionLabel(stringResource(R.string.section_restore_backup))
        Text(
            text = stringResource(R.string.restore_note),
            style = MaterialTheme.typography.bodySmall,
            color = pad.muted,
        )

        if (history.isEmpty()) {
            Text(
                text = stringResource(R.string.restore_no_history),
                style = MaterialTheme.typography.bodySmall,
                color = pad.muted,
            )
        } else {
            history.forEach { record ->
                // What produced this record, and what it holds. Without both, two
                // records of the same model a few minutes apart are
                // indistinguishable, and the user cannot tell which one undoes
                // their mistake without restoring it to find out.
                val origin = stringResource(record.origin.labelRes())
                val reading = recordCounterReadout(record, connectedModel, specs)
                PadSelectableRow(
                    label = stringResource(
                        R.string.restore_record_label,
                        record.model.ifBlank { stringResource(R.string.restore_unknown_model) },
                        formatBackupTimestamp(record.timestamp),
                    ),
                    supporting = if (reading == null) {
                        origin
                    } else {
                        stringResource(
                            R.string.restore_record_detail,
                            origin,
                            stringResource(R.string.restore_record_reads, reading),
                        )
                    },
                    selected = record.timestamp == selected,
                    multi = false,
                    onSelect = { selected = record.timestamp },
                )
            }
        }

        PadButton(
            text = stringResource(R.string.action_restore_selected),
            variant = PadButtonVariant.Danger,
            enabled = !busy && history.isNotEmpty() && selected != null,
            onClick = { history.firstOrNull { it.timestamp == selected }?.let(onRestore) },
            modifier = Modifier.fillMaxWidth(),
        )
        PadButton(
            text = stringResource(R.string.action_restore_paste),
            enabled = !busy,
            onClick = onPaste,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Takes a backup the user pasted.
 *
 * The text is parsed as it is typed and the result is what enables the confirm
 * button, so a paste that cannot be read says so where the user is looking
 * instead of failing later with no explanation. One dialog rather than a chain
 * for the same reason.
 */
@Composable
private fun PasteBackupDialog(
    onDismiss: () -> Unit,
    onParsed: (ParsedBackup) -> Unit,
) {
    val pad = PadTheme.colors
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { BackupTextParser.parse(text) }

    PadAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_paste_backup_title)) },
        confirmButton = {
            Button(
                onClick = { parsed?.let(onParsed) },
                enabled = parsed != null,
            ) {
                Text(stringResource(R.string.action_restore))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                Text(
                    text = stringResource(R.string.paste_backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = pad.muted,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.field_paste_backup)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.isNotBlank()) {
                    Text(
                        text = if (parsed == null) {
                            stringResource(R.string.paste_backup_invalid)
                        } else {
                            stringResource(R.string.paste_backup_parsed, parsed.values.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (parsed == null) pad.danger else pad.ok,
                    )
                }
            }
        },
    )
}

/**
 * The confirmation before a restore, and the change it would make.
 *
 * Restoring raises the counters, which is the opposite of everything else the app
 * does, so the numbers are shown rather than described. The delta is worked out
 * from the record's bytes over each counter's own addresses — the same
 * little-endian packing the write path uses — so what is promised here is what the
 * printer will read afterwards.
 *
 * A counter the record has no bytes for is skipped rather than shown as a guess.
 */
@Composable
private fun RestoreConfirmDialog(
    pending: PendingRestore,
    counters: List<WasteCounterStatus>,
    specs: List<WasteCounterSpec>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PadAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_restore_title)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.action_restore))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                val model = pending.model
                Text(
                    text = if (model.isNullOrBlank()) {
                        stringResource(R.string.dialog_restore_no_model)
                    } else {
                        stringResource(R.string.dialog_restore_model, model)
                    },
                )
                Text(stringResource(R.string.dialog_restore_addresses, pending.values.size))

                counters.forEach { counter ->
                    val spec = specs.firstOrNull { it.name == counter.name }
                    val restoredText = spec?.let {
                        if (it.max > 0) {
                            restoredCounterValue(it, pending.values)
                                ?.let { restored -> restoredReadout(counter, restored) }
                        } else {
                            restoredCounterHex(it, pending.values)
                        }
                    }
                    if (restoredText != null) {
                        Text(
                            stringResource(
                                R.string.dialog_restore_counter_delta,
                                counter.name,
                                counterReadout(counter),
                                restoredText,
                            ),
                        )
                    }
                }

                PadCallout(
                    text = stringResource(R.string.dialog_restore_body),
                    tone = PadTone.Danger,
                )
            }
        },
    )
}

/**
 * The value a recorded backup would give a counter.
 *
 * Little-endian over the counter's own addresses, which is how the write path
 * packs a value into them. Null when the record holds no byte for one of them: a
 * counter that would be written only in part has no single value to show, and
 * showing the part that is present would be a number the printer never reports.
 * Also null past four bytes: the packing no longer fits an Int (same reason
 * the status parser saturates there), and the hex variant below covers it.
 */
private fun restoredCounterValue(spec: WasteCounterSpec, values: Map<Int, Byte>): Int? {
    if (spec.addresses.size > 4) return null
    var packed = 0
    spec.addresses.forEachIndexed { position, address ->
        val byte = values[address] ?: return null
        // Same mask the live reading applies: a shared byte only counts the
        // nibble this counter owns.
        val masked = (byte.toInt() and 0xFF) and (spec.masks[address] ?: 0xFF)
        packed = packed or (masked shl (position * 8))
    }
    return packed
}

/**
 * The bytes a recorded backup would give a counter, in address order.
 *
 * The wide-counter twin of [restoredCounterValue]: exact at any width, used
 * wherever the ceiling is unknown and no Int packing is honest.
 */
private fun restoredCounterHex(spec: WasteCounterSpec, values: Map<Int, Byte>): String? {
    val bytes = spec.addresses.map { values[it] ?: return null }
    return bytes.joinToString(" ") { "%02X".format(it) }
}

/** A counter as it reads now, in the units the restore preview uses. */
private fun counterReadout(counter: WasteCounterStatus): String =
    if (counter.scaleKnown) {
        counter.percentage.formatPercent()
    } else {
        counter.rawHex.ifBlank { counter.rawValue.toString() }
    }

/** A counter as it would read after the restore, in the same units. */
private fun restoredReadout(counter: WasteCounterStatus, restoredValue: Int): String =
    readoutFor(restoredValue, counter.maxValue)

/**
 * A value in the units the model publishes: a percentage when it publishes a
 * ceiling, the raw reading otherwise.
 *
 * One function so the list row and the confirmation dialog can never disagree
 * about what a record holds. `scaleKnown` is `maxValue > 0`, so the two callers
 * agree by construction.
 */
private fun readoutFor(value: Int, max: Int): String =
    if (max > 0) {
        (value.toDouble() * 100.0 / max.toDouble()).formatPercent()
    } else {
        value.toString()
    }

@StringRes
private fun BackupOrigin.labelRes(): Int = when (this) {
    BackupOrigin.RESET -> R.string.restore_origin_reset
    BackupOrigin.RESTORE -> R.string.restore_origin_restore
    BackupOrigin.UNKNOWN -> R.string.restore_origin_unknown
}

/**
 * What the counters in [record] read, as text for the restore list.
 *
 * Null when the record's model is not the connected one — the addresses mean
 * something else on another printer, which is also why restoring such a record is
 * blocked — or when the record holds no counter in full. The reading is the
 * pre-write state the record stores, so it is what the printer would go back to.
 */
private fun recordCounterReadout(
    record: WasteBackupRecord,
    connectedModel: String,
    specs: List<WasteCounterSpec>,
): String? {
    if (specs.isEmpty() || !backupModelMatches(record.model, connectedModel)) return null
    val readings = specs.mapNotNull { spec ->
        val text = if (spec.max > 0) {
            val value = restoredCounterValue(spec, record.values) ?: return@mapNotNull null
            readoutFor(value, spec.max)
        } else {
            restoredCounterHex(spec, record.values) ?: return@mapNotNull null
        }
        "${spec.name} $text"
    }
    return readings.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** A saved record's time, in the device's own locale and format. */
private fun formatBackupTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

/**
 * The way out of a "model not in the database" failure.
 *
 * Opens a local preview, not a browser. Investigation is possible, not promised;
 * the user reviews the limited report before choosing a sharing channel.
 */
@Composable
internal fun ModelReportActions(onReview: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
        Text(
            text = stringResource(R.string.report_model_note),
            style = MaterialTheme.typography.bodySmall,
            color = PadTheme.colors.muted,
        )
        PadButton(
            text = stringResource(R.string.action_report_model),
            onClick = onReview,
            modifier = Modifier.fillMaxWidth(),
        )
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
            // An unknown level (-1, printer reported nothing) is an em dash,
            // never "0%": zero reads as an empty cartridge.
            text = if (connected && level.percentage >= 0) {
                stringResource(R.string.percent_value, level.percentage)
            } else {
                "—"
            },
            color = if (connected && level.percentage >= 0) inkLevelColor(level.percentage, pad) else pad.muted,
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
    readOnlySession: Boolean,
    unverifiedLocal: Boolean,
    busy: Boolean,
    onReset: () -> Unit,
    onExplainUnsupported: () -> Unit,
    onPasteGap: () -> Unit,
    onDeleteGap: () -> Unit,
) {
    val pad = PadTheme.colors

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
                    when {
                        readOnlySession && !usesMaintenanceBox -> R.string.partial_session_title
                        usesMaintenanceBox -> R.string.waste_box_title
                        else -> R.string.waste_unsupported_title
                    },
                ),
                text = stringResource(
                    when {
                        readOnlySession && !usesMaintenanceBox -> R.string.partial_session_body
                        usesMaintenanceBox -> R.string.waste_box_body
                        else -> R.string.waste_unsupported_body
                    },
                ),
                tone = PadTone.Info,
            )
            PadButton(
                text = stringResource(R.string.action_more_info),
                variant = PadButtonVariant.Secondary,
                onClick = onExplainUnsupported,
                modifier = Modifier.fillMaxWidth(),
            )
            if (readOnlySession && !usesMaintenanceBox) {
                PadButton(
                    text = stringResource(R.string.action_paste_profile),
                    onClick = onPasteGap,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }

    val displayCounters = if (counters.isEmpty()) {
        listOf(
            WasteCounterStatus(stringResource(R.string.waste_counter_default_name, 0), 0, 100),
            WasteCounterStatus(stringResource(R.string.waste_counter_default_name, 1), 0, 100),
        )
    } else {
        // stringResource must run in composition, and forEachIndexed is inline,
        // so its lambda can call it where mapIndexed's could not.
        buildList {
            counters.forEachIndexed { index, counter ->
                val normalizedName = counter.name
                    .takeIf { it.isNotBlank() }
                    ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    ?: stringResource(R.string.waste_counter_default_name, index)
                add(counter.copy(name = normalizedName))
            }
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
            text = stringResource(
                if (unverifiedLocal) R.string.action_reset_counters_unverified else R.string.action_reset_counters,
            ),
            variant = PadButtonVariant.Danger,
            enabled = connected && !busy,
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
        )
        if (unverifiedLocal) {
            PadButton(
                text = stringResource(R.string.action_delete_profile),
                variant = PadButtonVariant.Secondary,
                enabled = !busy,
                onClick = onDeleteGap,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
                    // Exact bytes, not a packed Int: wide groups do not fit one
                    // (and a zero here would read as an empty pad).
                    else -> counter.rawHex.ifBlank { counter.rawValue.toString() }
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
