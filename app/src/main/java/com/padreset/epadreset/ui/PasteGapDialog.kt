package com.padreset.epadreset.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.padreset.epadreset.R
import com.padreset.epadreset.data.GapParse
import com.padreset.epadreset.data.GapProfileText
import com.padreset.epadreset.ui.components.PadAlertDialog
import com.padreset.epadreset.ui.theme.PadSpace
import com.padreset.epadreset.ui.theme.PadTheme

/**
 * Paste a public profile. The button only checks that the text parses.
 * The ViewModel still refuses a catalog hit and a box waste plan.
 */
@Composable
internal fun PasteGapDialog(
    onDismiss: () -> Unit,
    onPaste: suspend (String) -> String?,
) {
    val pad = PadTheme.colors
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsed by produceState<GapParse>(GapParse.Refused(com.padreset.epadreset.data.GapRefusal.MALFORMED), text) {
        value = GapParse.Refused(com.padreset.epadreset.data.GapRefusal.MALFORMED)
        value = withContext(Dispatchers.Default) { GapProfileText.parse(text, emptySet()) }
    }
    PadAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_paste_profile_title)) },
        confirmButton = {
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val refused = onPaste(text)
                            if (refused == null) onDismiss() else error = refused
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving && parsed is GapParse.Accepted,
            ) {
                Text(stringResource(R.string.action_paste_profile))
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
                    text = stringResource(R.string.paste_profile_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = pad.muted,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(GapProfileText.MAX_TEXT_LENGTH + 1)
                        error = null
                    },
                    enabled = !saving,
                    label = { Text(stringResource(R.string.field_paste_profile)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val note = error ?: if (text.isNotBlank() && parsed !is GapParse.Accepted) {
                    stringResource(R.string.paste_profile_invalid)
                } else {
                    null
                }
                if (note != null) {
                    Text(text = note, color = pad.danger)
                }
            }
        },
    )
}
