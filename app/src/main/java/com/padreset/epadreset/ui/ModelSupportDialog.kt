package com.padreset.epadreset.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.padreset.epadreset.R
import com.padreset.epadreset.ui.components.PadAlertDialog
import com.padreset.epadreset.ui.components.PadButton
import com.padreset.epadreset.ui.components.PadButtonVariant
import com.padreset.epadreset.ui.theme.PadSpace

/** A local draft. Opening a channel neither posts nor copies it automatically. */
@Composable
internal fun ModelSupportDialog(
    reportBody: String,
    reportUrl: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var draft by remember(reportBody) { mutableStateOf(reportBody) }
    var showPreview by remember { mutableStateOf(true) }
    val copied = stringResource(R.string.report_copied)
    val unavailable = stringResource(R.string.report_external_unavailable)
    val shareTitle = stringResource(R.string.action_share_report)
    val openExternal: (Intent) -> Unit = { intent ->
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, unavailable, Toast.LENGTH_LONG).show()
        }
    }

    PadAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_model_support_title)) },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PadSpace.sm),
            ) {
                Text(stringResource(R.string.report_privacy_note))
                if (showPreview) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.field_model_report)) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        minLines = 5,
                        maxLines = 8,
                    )
                    PadButton(
                        text = stringResource(R.string.action_choose_report_channel),
                        onClick = { showPreview = false },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    PadButton(
                        text = stringResource(R.string.action_review_report),
                        variant = PadButtonVariant.Secondary,
                        onClick = { showPreview = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PadButton(
                        text = stringResource(R.string.action_copy_report),
                        onClick = {
                            clipboard.setText(AnnotatedString(draft))
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.report_discord_help))
                    PadButton(
                        text = stringResource(R.string.action_report_discord),
                        variant = PadButtonVariant.Secondary,
                        onClick = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(COMMUNITY_URL))) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (reportUrl != null) {
                        Text(stringResource(R.string.report_github_help))
                        PadButton(
                            text = stringResource(R.string.action_report_github),
                            variant = PadButtonVariant.Secondary,
                            onClick = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(reportUrl))) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(stringResource(R.string.report_share_help))
                    PadButton(
                        text = shareTitle,
                        variant = PadButtonVariant.Secondary,
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "E-Pad Reset — model support request")
                                putExtra(Intent.EXTRA_TEXT, draft)
                            }
                            openExternal(Intent.createChooser(send, shareTitle))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}
