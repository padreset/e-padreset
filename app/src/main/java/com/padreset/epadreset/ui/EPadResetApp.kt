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
import kotlinx.coroutines.flow.StateFlow

/**
 * The project repository, opened from the About dialog and from the completion
 * dialog.
 *
 * This fork's own repository. It pointed at the upstream until the fork had one,
 * which was documented as temporary and was wrong for anything the user could
 * act on: the upstream is not maintained, and the fork's relicensing, its model
 * extension and its report action are not that author's to answer for.
 */
internal const val REPO_URL = "https://github.com/DSGM-GMSD/e-padreset"

/**
 * Where a model report is filed, or null when there is nowhere to file it.
 *
 * Kept apart from [REPO_URL] because the two answer different questions — one is
 * where to read the code, the other is where to send a report — and they have
 * not always had the same answer. While this fork had no repository of its own,
 * [REPO_URL] pointed at the upstream, and sending a user there to open an issue
 * would have landed a report about a build that author never made in a third
 * party's tracker.
 *
 * Null hides GitHub only; preview, copying, sharing and Discord remain available.
 * The issue URL selects a template without putting diagnostics in the URL.
 *
 * Not a `const`: a nullable constant is not something Kotlin allows, and an
 * empty string standing in for "no destination" would be a trap of exactly the
 * kind this file is trying to avoid.
 */
internal val REPORT_REPO_URL: String? = "https://github.com/DSGM-GMSD/e-padreset"

/** Opened by the user's browser/Discord app, never by an in-app network client. */
internal const val COMMUNITY_URL = "https://discord.gg/NYqqn4b25"

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
 */
internal val RELEASES_URL: String? = "https://github.com/DSGM-GMSD/e-padreset/releases"

/**
 * Where a donation is collected, or null while there is nowhere to collect it.
 *
 * Null is not a placeholder: the support block in About is hidden entirely while
 * this is null, so the app never asks for money it has nowhere to send. Setting
 * this is what turns the block on, and it is the only change needed.
 *
 * The button opens the address in the browser, which is deliberate on two counts.
 * It needs no INTERNET permission, because the browser does the connecting - the
 * same reason the repository and releases buttons work in an app that promises
 * never to go online. And it keeps the donation outside Google Play's billing
 * requirement, which covers payments taken inside the app.
 *
 * Prefer a destination a non-technical user can use without an account: this
 * app's audience owns an Epson printer, not a GitHub profile.
 */
internal val DONATE_URL: String? = "https://ko-fi.com/dantesgonzalez"

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
    /**
     * Passed as a flow rather than a list, and collected at the panel that draws
     * it. Collecting it here would recompose this whole tree on every log line,
     * which is the thing the separate flow exists to avoid.
     */
    logs: StateFlow<List<String>>,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onReset: (Set<String>, Int) -> Unit,
    onRestore: (String?, Map<Int, Byte>) -> Unit,
    onServiceClean: (String) -> Unit,
    onGenericClean: (Int, Boolean, Boolean) -> Unit,
    onNozzleCheck: () -> Unit,
    onDismissActionSuccess: () -> Unit,
) {
    var page by remember { mutableStateOf(AppPage.Status) }

    when (page) {
        AppPage.Status -> PrinterStatusScreen(
            uiState = uiState,
            logs = logs,
            onConnect = onConnect,
            onRefresh = onRefresh,
            onReset = onReset,
            onRestore = onRestore,
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
