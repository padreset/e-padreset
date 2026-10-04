package com.padreset.epadreset.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.padreset.epadreset.R
import com.padreset.epadreset.data.InkLevel
import com.padreset.epadreset.data.PrinterStatusSnapshot
import com.padreset.epadreset.data.WasteCounterStatus
import com.padreset.epadreset.ui.theme.EPadResetTheme
import org.junit.Rule
import org.junit.Test

/**
 * Renders the status page against states the app cannot be driven into without
 * a printer on the other end of a USB cable.
 *
 * The three things this file covers were all built from a desk with no hardware
 * attached: the report block only appears when a connected model is missing from
 * the catalog, the backup card only after a write, and the raw-reading row only
 * for a model whose counter ceiling is not published. Compiling proved none of
 * them rendered, and a screenshot of the app on launch would have shown none of
 * them either, because all three need a printer that is not there.
 *
 * Assertions go through the app's own string resources rather than English
 * literals: the emulator's locale is not part of the test, and a test that
 * passes only in English is a test that will fail on a Spanish device.
 */
class PrinterStatusScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(@StringRes id: Int, vararg args: Any): String =
        context.getString(id, *args)

    private fun render(state: MainUiState) {
        compose.setContent {
            EPadResetTheme {
                EPadResetApp(
                    uiState = state,
                    onConnect = {},
                    onRefresh = {},
                    onReset = { _, _ -> },
                    onRestore = { _, _ -> },
                    onServiceClean = {},
                    onGenericClean = { _, _, _ -> },
                    onNozzleCheck = {},
                    onDismissActionSuccess = {},
                )
            }
        }
    }

    // -- the report block ---------------------------------------------------

    @Test
    fun aMissingModelRequiresLocalReviewBeforeOfferingChannels() {
        render(
            MainUiState(
                error = string(R.string.error_connect_unsupported_model_named, "ET-9999"),
                reportableModel = "ET-9999",
                modelReportBody = "Printer model: ET-9999",
                modelReportUrl = "https://example.test/issues/new",
            ),
        )

        compose.onNodeWithText(string(R.string.action_report_model))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_report_github)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.action_report_discord)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.action_report_model)).performClick()
        compose.onNodeWithText(string(R.string.field_model_report)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_report_github)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.action_choose_report_channel)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.action_copy_report)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_report_discord)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_report_github)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_share_report)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withNoGitHubDestinationOtherChannelsRemainAvailable() {
        render(
            MainUiState(
                error = string(R.string.error_connect_unsupported_model),
                reportableModel = "ET-9999",
                modelReportBody = "Printer model: ET-9999",
                modelReportUrl = null,
            ),
        )

        compose.onNodeWithText(string(R.string.action_report_model)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.action_choose_report_channel)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.action_copy_report)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_report_github)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.action_share_report)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aMaintenanceBoxModelCanRequestMaintenanceWithoutPromisingReset() {
        render(
            MainUiState(
                error = string(R.string.error_connect_unsupported_model_box_named, "ET-16600"),
                reportableModel = "ET-16600",
                modelReportBody = "Request maintenance support only; cannot reset a counter on the box chip.",
            ),
        )

        compose.onNodeWithText(string(R.string.error_connect_unsupported_model_box_named, "ET-16600"))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_report_model)).performScrollTo().performClick()
        compose.onNodeWithText("Request maintenance support only; cannot reset a counter on the box chip.")
            .assertIsDisplayed()
    }

    // -- the backup ---------------------------------------------------------

    @Test
    fun theBackupIsShownAfterAWrite() {
        render(
            MainUiState(
                status = snapshot(WasteCounterStatus("Main pad", 63, 13000)),
                supportsWasteReset = true,
                wasteBackup = "0x001C = 0x3F",
            ),
        )

        // ignoreCase because PadSectionLabel upper-cases what it is given, so
        // the node on screen reads "BACKUP BEFORE WRITE".
        compose.onNodeWithText(string(R.string.section_waste_backup), ignoreCase = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("0x001C = 0x3F").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aVerifiedWriteShowsTheSuccessDialog() {
        render(
            MainUiState(
                status = snapshot(WasteCounterStatus("Main pad", 500, 7800)),
                supportsWasteReset = true,
                wasteBackup = "0x000D = 0xFF",
                actionSuccessTitle = string(R.string.dialog_counters_updated_title),
                actionSuccessMessage = string(R.string.msg_counters_updated),
            ),
        )
        compose.onNodeWithText(string(R.string.dialog_counters_updated_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.msg_counters_updated)).assertIsDisplayed()
    }

    @Test
    fun aByteMismatchShowsItsAddressesAndKeepsTheBackupWithoutSuccess() {
        val error = string(R.string.error_counters_not_applied, "0x001C, 0x0032")
        render(
            MainUiState(
                status = snapshot(WasteCounterStatus("Main pad", 2, 0)),
                supportsWasteReset = true,
                error = error,
                wasteBackup = "0x001C = 0x3F",
                logs = listOf("EEPROM mismatch at 0x001C: expected 0x00, read 0x02"),
            ),
        )
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0x001C = 0x3F").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.dialog_counters_updated_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.msg_counters_updated)).assertDoesNotExist()
    }

    @Test
    fun aReadbackFailureSaysTheResultIsUnverifiedAndKeepsTheBackup() {
        val error = string(R.string.error_read_back_failed_at, "0x0037")
        render(
            MainUiState(
                status = snapshot(WasteCounterStatus("Main pad", 63, 13000)),
                supportsWasteReset = true,
                error = error,
                wasteBackup = "0x0037 = 0x5E",
            ),
        )
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0x0037 = 0x5E").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.msg_counters_updated)).assertDoesNotExist()
    }

    @Test
    fun withNoWriteYetThereIsNoBackupCard() {
        render(MainUiState(status = snapshot(WasteCounterStatus("Main pad", 63, 13000))))

        compose.onNodeWithText(string(R.string.section_waste_backup), ignoreCase = true)
            .assertDoesNotExist()
    }

    // -- the counter scale --------------------------------------------------

    @Test
    fun anUnpublishedCeilingShowsTheRawReadingAndNoPercentage() {
        render(
            MainUiState(
                status = snapshot(WasteCounterStatus("Main pad", rawValue = 1234, maxValue = 0)),
                supportsWasteReset = true,
            ),
        )

        // The raw byte value is the honest reading.
        compose.onNodeWithText("1234").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.waste_scale_unknown))
            .performScrollTo()
            .assertIsDisplayed()

        // And 0% must not appear: it would read as an empty pad, which is the
        // false all-clear this branch exists to prevent.
        compose.onNodeWithText("0.00%").assertDoesNotExist()
    }

    @Test
    fun theUnpublishedCeilingIsExplainedOnceNotPerCounter() {
        // These models usually carry two or three counters, and the same
        // sentence under each of them is a wall of text — which costs most to
        // the reader who does not have the language.
        render(
            MainUiState(
                status = snapshot(
                    WasteCounterStatus("Main pad", rawValue = 1234, maxValue = 0),
                    WasteCounterStatus("Platen pad", rawValue = 5678, maxValue = 0),
                ),
                supportsWasteReset = true,
            ),
        )

        compose.onAllNodesWithText(string(R.string.waste_scale_unknown))
            .assertCountEquals(1)
    }

    @Test
    fun aPublishedCeilingStillShowsAPercentage() {
        render(
            MainUiState(
                status = snapshot(WasteCounterStatus("Main pad", rawValue = 50, maxValue = 100)),
                supportsWasteReset = true,
            ),
        )

        // Two nodes carry it, and both should: the "highest pad" line in the
        // header card and the counter row itself.
        compose.onAllNodesWithText("50.00%").onFirst().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.waste_scale_unknown)).assertDoesNotExist()
    }

    // -- disconnection ------------------------------------------------------

    @Test
    fun aDisconnectedPrinterSaysSoInsteadOfShowingStaleReadings() {
        // The state the detach broadcast produces: no status, and a note that
        // says why. What must not happen is counters left on screen from a
        // printer that is no longer plugged in.
        render(
            MainUiState(
                status = null,
                notice = string(R.string.notice_printer_disconnected),
            ),
        )

        compose.onNodeWithText(string(R.string.notice_printer_disconnected))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.label_highest_pad)).assertDoesNotExist()
        compose.onNodeWithText("50.00%").assertDoesNotExist()
    }

    // -- about --------------------------------------------------------------

    @Test
    fun aboutShowsTheVersion() {
        render(MainUiState(appVersion = "9.9.9"))

        compose.onNodeWithContentDescription(string(R.string.action_about)).performClick()
        compose.onNodeWithText(string(R.string.about_version, "9.9.9")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.about_epson_only)).assertIsDisplayed()
    }

    @Test
    fun aboutOffersToCheckForUpdatesNowThatThereIsSomewhereToSendThem() {
        // This asserted the opposite while RELEASES_URL was null, which it was
        // for as long as the fork had no repository of its own. It is set now,
        // so the assertion flips — and what it checks is that the button appears
        // on its own, from the constant alone, with nothing else wired up.
        render(MainUiState(appVersion = "9.9.9"))

        compose.onNodeWithContentDescription(string(R.string.action_about)).performClick()
        compose.onNodeWithText(string(R.string.action_check_updates)).assertIsDisplayed()
        // And it says what it actually does, because the app cannot check by
        // itself: it hands the address to the browser.
        compose.onNodeWithText(string(R.string.about_updates_note)).assertIsDisplayed()
    }

    @Test
    fun aboutSurvivesAVersionItCouldNotRead() {
        // The package lookup is wrapped in runCatching; an empty string must not
        // render a dangling "Version " line. Asserted on a string unique to the
        // dialog: the app name appears in the header too, so matching on it
        // would pass whether the dialog opened or not.
        render(MainUiState(appVersion = ""))

        compose.onNodeWithContentDescription(string(R.string.action_about)).performClick()
        compose.onNodeWithText(string(R.string.about_epson_only)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.about_version, "")).assertDoesNotExist()
    }

    private fun snapshot(vararg counters: WasteCounterStatus) = PrinterStatusSnapshot(
        modelName = "ET-1111",
        serialNumber = "ABC123",
        stateCode = 0x04,
        errorCode = PrinterStatusSnapshot.ERROR_NONE,
        state = "Idle",
        error = "None",
        inkLevels = listOf(InkLevel("Black", 80)),
        wasteCounters = counters.toList(),
    )
}
