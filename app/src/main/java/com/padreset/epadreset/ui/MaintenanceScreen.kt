package com.padreset.epadreset.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.padreset.epadreset.R
import com.padreset.epadreset.ui.components.PadAlertDialog
import com.padreset.epadreset.ui.components.PadButton
import com.padreset.epadreset.ui.components.PadButtonVariant
import com.padreset.epadreset.ui.components.PadBusyIndicator
import com.padreset.epadreset.ui.components.PadCallout
import com.padreset.epadreset.ui.components.PadCard
import com.padreset.epadreset.ui.components.PadHeaderBar
import com.padreset.epadreset.ui.components.PadSelectableRow
import com.padreset.epadreset.ui.components.PadTone
import com.padreset.epadreset.ui.theme.PadSpace
import com.padreset.epadreset.ui.theme.PadTheme

/**
 * Secondary page holding the maintenance jobs.
 *
 * Head cleaning and the nozzle check used to sit at the bottom of the status
 * page, below the waste counters. They are a different kind of task — they act
 * on the print head rather than reporting state — so they moved to their own
 * page, reached from the header.
 *
 * The status page keeps the printer info and the ink levels, so nothing that
 * describes the printer's current condition moved away with them.
 */
@Composable
fun MaintenanceScreen(
    uiState: MainUiState,
    onBack: () -> Unit,
    onServiceClean: (String) -> Unit,
    onGenericClean: (Int, Boolean, Boolean) -> Unit,
    onNozzleCheck: () -> Unit,
) {
    val pad = PadTheme.colors
    val cleaningOptions = uiState.cleaningOptions
    var showServiceCleaningDialog by remember { mutableStateOf(false) }
    var showGenericCleaningDialog by remember { mutableStateOf(false) }
    var showNozzleCheckDialog by remember { mutableStateOf(false) }
    var selectedCleaning by remember(cleaningOptions) {
        mutableStateOf(cleaningOptions.firstOrNull().orEmpty())
    }
    var genericCleaningGroup by remember { mutableStateOf(0) }
    var genericPowerClean by remember { mutableStateOf(false) }

    val anyDialogOpen = showServiceCleaningDialog || showGenericCleaningDialog || showNozzleCheckDialog
    BackHandler(enabled = !anyDialogOpen) { onBack() }

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
                title = stringResource(R.string.section_maintenance),
                onBack = onBack,
            )

            // A job started on this page can fail, and the message lands on the
            // shared state. Without this block the user would press a button and
            // see nothing happen, because the callout only existed on the status
            // page.
            uiState.notice?.let { notice ->
                PadCallout(text = notice, tone = PadTone.Info)
            }

            if (!uiState.error.isNullOrBlank()) {
                PadCallout(
                    title = stringResource(R.string.callout_operation_failed),
                    text = uiState.error,
                    tone = PadTone.Danger,
                )
            }

            PadCard {
                MaintenancePanel(
                    cleaningOptions = cleaningOptions,
                    supportsGenericMaintenanceJobs = uiState.supportsGenericMaintenanceJobs,
                    readOnlySession = uiState.readOnlySession,
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

            Spacer(Modifier.height(PadSpace.lg))
        }

        PadBusyIndicator(
            busy = uiState.isBusy,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    if (showServiceCleaningDialog) {
        PadAlertDialog(
            onDismissRequest = { showServiceCleaningDialog = false },
            title = { Text(stringResource(R.string.dialog_cleaning_title)) },
            confirmButton = {
                Button(
                    onClick = {
                        showServiceCleaningDialog = false
                        onServiceClean(selectedCleaning)
                    },
                    enabled = selectedCleaning.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_start_cleaning))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showServiceCleaningDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    Text(stringResource(R.string.dialog_cleaning_body))
                    cleaningOptions.forEach { option ->
                        PadSelectableRow(
                            // Translated for the dialog only. The raw name still
                            // travels to the log and to the printer.
                            label = cleaningModeLabel(LocalContext.current, option),
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
            stringResource(R.string.cleaning_all_nozzles),
            stringResource(R.string.cleaning_black_nozzle),
            stringResource(R.string.cleaning_color_nozzles),
            stringResource(R.string.cleaning_alternative_mode),
        )
        PadAlertDialog(
            onDismissRequest = { showGenericCleaningDialog = false },
            title = { Text(stringResource(R.string.dialog_cleaning_title)) },
            confirmButton = {
                Button(
                    onClick = {
                        showGenericCleaningDialog = false
                        onGenericClean(genericCleaningGroup, genericPowerClean, genericCleaningGroup == 3)
                    },
                ) {
                    Text(stringResource(R.string.action_start_cleaning))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showGenericCleaningDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                    Text(stringResource(R.string.dialog_generic_cleaning_body))
                    cleaningModes.forEachIndexed { index, label ->
                        PadSelectableRow(
                            label = label,
                            selected = genericCleaningGroup == index,
                            multi = false,
                            onSelect = { checked ->
                                if (checked) genericCleaningGroup = index
                            },
                        )
                    }
                    if (uiState.allowsPowerClean) {
                        PadSelectableRow(
                            label = stringResource(R.string.cleaning_power_clean),
                            selected = genericPowerClean,
                            multi = true,
                            onSelect = { genericPowerClean = it },
                        )
                    }
                }
            },
        )
    }

    if (showNozzleCheckDialog) {
        PadAlertDialog(
            onDismissRequest = { showNozzleCheckDialog = false },
            title = { Text(stringResource(R.string.dialog_nozzle_check_title)) },
            confirmButton = {
                Button(
                    onClick = {
                        showNozzleCheckDialog = false
                        onNozzleCheck()
                    },
                ) {
                    Text(stringResource(R.string.action_print_test))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNozzleCheckDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            // A statement of what the button costs, not a set of options. There is
            // one nozzle check: the two entries that used to sit here were the two
            // halves of a single sequence, offered as if they were alternatives.
            text = { Text(stringResource(R.string.dialog_nozzle_check_body)) },
        )
    }
}

// ---------------------------------------------------------------------------
// Maintenance
// ---------------------------------------------------------------------------

@Composable
private fun MaintenancePanel(
    cleaningOptions: List<String>,
    supportsGenericMaintenanceJobs: Boolean,
    readOnlySession: Boolean,
    connected: Boolean,
    busy: Boolean,
    onClean: () -> Unit,
    onNozzleCheck: () -> Unit,
) {
    val pad = PadTheme.colors
    val enabled = connected && !busy
    Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
        // Nozzle check first: it is the diagnostic, and it costs almost no ink.
        // Head cleaning is the remedy, and it consumes ink and adds to the waste
        // counters, so it comes second — check before you clean.
        PadButton(
            text = stringResource(R.string.maintenance_nozzle_check),
            variant = PadButtonVariant.Secondary,
            enabled = enabled && supportsGenericMaintenanceJobs,
            onClick = onNozzleCheck,
            modifier = Modifier.fillMaxWidth(),
        )
        PadButton(
            text = stringResource(R.string.maintenance_head_cleaning),
            variant = PadButtonVariant.Secondary,
            enabled = enabled && (cleaningOptions.isNotEmpty() || supportsGenericMaintenanceJobs),
            onClick = onClean,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(
                if (readOnlySession) R.string.partial_cleaning_note else R.string.maintenance_note,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = pad.muted,
        )
    }
}
