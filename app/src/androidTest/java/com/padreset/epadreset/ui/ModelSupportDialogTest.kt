package com.padreset.epadreset.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.IntentCompat
import com.padreset.epadreset.R
import com.padreset.epadreset.data.ModelSupportReport
import com.padreset.epadreset.ui.theme.EPadResetTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ModelSupportDialogTest {
    @get:Rule
    val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val captured = mutableListOf<Intent>()
    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
            captured += intent
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }
    private var previousClip: ClipData? = null
    private val url = ModelSupportReport.issueUrl("https://github.com/padreset/e-padreset")

    @Before
    fun interceptExternalActivities() {
        // No browser requests, Discord posts or share deliveries during tests.
        instrumentation.addMonitor(monitor)
        compose.runOnIdle { previousClip = clipboard.primaryClip }
    }

    @After
    fun restoreClipboardAndRemoveMonitor() {
        instrumentation.removeMonitor(monitor)
        compose.runOnIdle {
            clipboard.setPrimaryClip(previousClip ?: ClipData.newPlainText("", ""))
        }
    }

    private fun render() {
        compose.setContent {
            EPadResetTheme {
                ModelSupportDialog("Printer model: ET-9999", url, onDismiss = {})
            }
        }
    }

    private fun click(id: Int) {
        compose.onNodeWithText(context.getString(id)).performScrollTo().performClick()
    }

    @Test
    fun openingAnIntentWithNoHandlerShowsFallbackInsteadOfThrowing() {
        instrumentation.removeMonitor(monitor)
        try {
            compose.runOnIdle {
                assertFalse(context.openExternal(Intent("com.padreset.epadreset.NO_HANDLER")))
            }
        } finally {
            instrumentation.addMonitor(monitor)
        }
    }

    @Test
    fun reviewIsLocalAndCopyUsesTheEditedDraft() {
        render()
        compose.onNodeWithText(context.getString(R.string.field_model_report)).assertIsDisplayed()
        assertTrue(captured.isEmpty())
        compose.onNodeWithText(context.getString(R.string.field_model_report))
            .performTextReplacement("Reviewed request: cleaning only")
        click(R.string.action_choose_report_channel)
        click(R.string.action_copy_report)
        compose.runOnIdle {
            assertEquals("Reviewed request: cleaning only", clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
        assertTrue(captured.isEmpty())
        click(R.string.action_review_report)
        compose.onNodeWithText("Reviewed request: cleaning only").assertIsDisplayed()
    }

    @Test
    fun githubAndDiscordLinksContainNoReportAndDoNotCopyAutomatically() {
        render()
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("test", "untouched clipboard")) }
        click(R.string.action_choose_report_channel)
        click(R.string.action_report_github)
        click(R.string.action_report_discord)
        compose.runOnIdle {
            assertEquals(2, captured.size)
            assertEquals(Intent.ACTION_VIEW, captured[0].action)
            assertEquals(url, captured[0].data.toString())
            assertFalse(captured[0].data.toString().contains("ET-9999"))
            assertFalse(captured[0].hasExtra(Intent.EXTRA_TEXT))
            assertEquals(COMMUNITY_URL, captured[1].data.toString())
            assertEquals("untouched clipboard", clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
    }

    @Test
    fun sharingUsesTheSystemChooserWithTheEditedTextAndNoFixedRecipient() {
        render()
        compose.onNodeWithText(context.getString(R.string.field_model_report))
            .performTextReplacement("Reviewed draft to relay")
        click(R.string.action_choose_report_channel)
        click(R.string.action_share_report)
        compose.runOnIdle {
            assertEquals(1, captured.size)
            val chooser = captured.single()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals("Reviewed draft to relay", send.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(null, send.component)
            assertEquals(null, send.`package`)
        }
    }
}
