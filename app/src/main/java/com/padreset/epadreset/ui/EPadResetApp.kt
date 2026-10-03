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

/**
 * Where a model report is filed, or null when there is nowhere to file it.
 *
 * Kept apart from [REPO_URL] on purpose. Linking to the upstream repository from
 * the About dialog is a display choice, and it is documented as temporary. Sending
 * a user there to *open an issue* is a different act: the report would land in a
 * third party's tracker, describing a build that author never made — this fork's
 * GPL-3.0 relicensing, its 313-model maintenance-box list, its report button.
 * That is noise for them and a dead end for the user.
 *
 * Null until this fork has a repository of its own. While it is null the report
 * block offers only the clipboard: the text is assembled and handed to the user,
 * and nothing is addressed anywhere. Set this to the fork's own repository and
 * the prefilled-issue button appears on its own.
 *
 * Not a `const`: a nullable constant is not something Kotlin allows, and an
 * empty string standing in for "no destination" would be a trap of exactly the
 * kind this file is trying to avoid.
 */
internal val REPORT_REPO_URL: String? = null

/**
 * Where releases are published, or null while there is nowhere to publish them.
 *
 * The app cannot check for updates on its own. Comparing its version against a
 * published one needs the INTERNET permission, which this app does not hold and
 * is not going to: it is offline by design, its privacy policy says it
 * transmits nothing, and in a category full of utilities that phone home, not
 * holding the permission is worth more than the feature.
 *
 * What it can do is hand the address to the browser. That is what every
 * sideloaded app does, and it is honest — the user watches a browser open,
 * rather than a spinner that decided something on its own.
 *
 * Null until this fork publishes releases of its own. While it is null the About
 * dialog shows the version and nothing else.
 */
internal val RELEASES_URL: String? = null

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
    onNozzleCheck: () -> Unit,
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
    //
    // Both parts are required. The title says what the app actually knows — a
    // verified reset, or a job it could only send — and a message without one
    // would fall back to claiming more than that.
    val successTitle = uiState.actionSuccessTitle
    val successMessage = uiState.actionSuccessMessage
    if (successTitle != null && successMessage != null) {
        ActionSuccessDialog(
            title = successTitle,
            message = successMessage,
            onDismiss = onDismissActionSuccess,
        )
    }
}

@Composable
private fun ActionSuccessDialog(title: String, message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    PadAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
