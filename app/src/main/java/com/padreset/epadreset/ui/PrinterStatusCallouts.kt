package com.padreset.epadreset.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.padreset.epadreset.R
import com.padreset.epadreset.ui.components.BUSY_PILL_FADE_MILLIS
import com.padreset.epadreset.ui.components.PadCallout
import com.padreset.epadreset.ui.components.PadTone
import com.padreset.epadreset.ui.theme.PadSpace

@Composable
internal fun PrinterStatusCallouts(
    uiState: MainUiState,
    onReviewModelReport: () -> Unit,
) {
    AnimatedVisibility(
        visible = uiState.notice != null || !uiState.error.isNullOrBlank() || uiState.statusRefreshFailed,
        enter = fadeIn(tween(BUSY_PILL_FADE_MILLIS)) +
            expandVertically(tween(BUSY_PILL_FADE_MILLIS), expandFrom = Alignment.Top),
        exit = fadeOut(tween(BUSY_PILL_FADE_MILLIS)) +
            shrinkVertically(tween(BUSY_PILL_FADE_MILLIS), shrinkTowards = Alignment.Top),
    ) {
        Crossfade(
            targetState = Triple(uiState.notice, uiState.error, uiState.errorTitle) to
                (uiState.modelReportBody != null),
        ) { target ->
            Column(verticalArrangement = Arrangement.spacedBy(PadSpace.sm)) {
                target.first.first?.let { PadCallout(text = it, tone = PadTone.Info) }
                val error = target.first.second
                val errorTitle = target.first.third

                if (uiState.statusRefreshFailed) {
                    PadCallout(
                        title = errorTitle ?: stringResource(R.string.error_status_refresh_title),
                        text = error ?: stringResource(R.string.error_status_refresh_detail),
                        tone = PadTone.Info,
                    )
                } else if (!error.isNullOrBlank()) {
                    PadCallout(
                        title = errorTitle ?: stringResource(R.string.callout_operation_failed),
                        text = error,
                        tone = PadTone.Danger,
                    )
                    if (target.second) {
                        ModelReportActions(onReview = onReviewModelReport)
                    }
                }
            }
        }
    }
}
