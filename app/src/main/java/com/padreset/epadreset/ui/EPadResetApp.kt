package com.padreset.epadreset.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.padreset.epadreset.R
import com.padreset.epadreset.ui.components.PadAlertDialog
import com.padreset.epadreset.ui.theme.PadSpace

/**
 * The project repository, opened from the About dialog and from the completion
 * dialog.
 *
 * Still points at the upstream project: the fork has no repository of its own
 * yet. Change this, the About label and the README together once it does.
 */
internal const val REPO_URL = "https://github.com/tekware-it/android-epson-reset"

/** The pages the app can show. There are two, so a navigation library would be overkill. */
private enum class AppPage { Status, Maintenance }

/**
 * Holds the page state and renders the current page.
 *
 * Maintenance used to be a card at the bottom of the status page. It is now a
 * page of its own, reached from the header, which keeps the status page about
 * the printer's condition and the maintenance page about acting on it.
 */
@Composable
fun EPadResetApp(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onReset: (Set<String>, Int) -> Unit,
    onServiceClean: (String) -> Unit,
    onGenericClean: (Int, Boolean, Boolean) -> Unit,
    onNozzleCheck: (Int) -> Unit,
    onDismissActionSuccess: () -> Unit,
) {
    var page by remember { mutableStateOf(AppPage.Status) }

    when (page) {
        AppPage.Status -> PrinterStatusScreen(
            uiState = uiState,
            onConnect = onConnect,
            onRefresh = onRefresh,
            onReset = onReset,
            onOpenMaintenance = { page = AppPage.Maintenance },
        )

        AppPage.Maintenance -> MaintenanceScreen(
            uiState = uiState,
            onBack = { page = AppPage.Status },
            onServiceClean = onServiceClean,
            onGenericClean = onGenericClean,
            onNozzleCheck = onNozzleCheck,
        )
    }

    // Held here rather than on a page: a job can be started from either one, and
    // the confirmation should not vanish because the page changed underneath it.
    uiState.actionSuccessMessage?.let { message ->
        ActionSuccessDialog(message = message, onDismiss = onDismissActionSuccess)
    }
}

@Composable
private fun ActionSuccessDialog(message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    PadAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_action_completed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                Text(message)
                Text(stringResource(R.string.about_donation))
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                    },
                ) {
                    Text(stringResource(R.string.action_open_github))
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}
