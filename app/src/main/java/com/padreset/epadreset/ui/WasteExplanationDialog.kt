package com.padreset.epadreset.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.padreset.epadreset.R
import com.padreset.epadreset.ui.components.PadAlertDialog
import com.padreset.epadreset.ui.theme.PadSpace

/**
 * Why there is nothing to reset, and — on a read-only session — the report
 * that asks for a profile. Kept out of the status screen so that screen
 * stays under the function ceiling.
 */
@Composable
internal fun WasteExplanationDialog(
    uiState: MainUiState,
    onDismiss: () -> Unit,
    onReviewReport: () -> Unit,
) {
    val title = when {
        uiState.readOnlySession && !uiState.usesMaintenanceBox -> R.string.partial_session_title
        uiState.usesMaintenanceBox -> R.string.waste_box_dialog_title
        else -> R.string.waste_unsupported_dialog_title
    }
    val body = when {
        uiState.readOnlySession && !uiState.usesMaintenanceBox -> R.string.partial_session_body
        uiState.usesMaintenanceBox -> R.string.waste_box_dialog_body
        else -> R.string.waste_unsupported_dialog_body
    }
    PadAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                Text(stringResource(body))
                if (uiState.readOnlySession && uiState.modelReportBody != null) {
                    ModelReportActions(onReview = onReviewReport)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
