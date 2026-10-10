package com.padreset.epadreset.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.padreset.epadreset.R

/** Starts an external destination safely and returns false when no handler exists. */
internal fun Context.openExternal(intent: Intent): Boolean = openExternal(
    intent = intent,
    unavailableMessage = getString(R.string.report_external_unavailable),
)

internal fun Context.openExternal(intent: Intent, unavailableMessage: String): Boolean = try {
    val launchIntent = Intent(intent)
    if (this !is Activity) launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(launchIntent)
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(this, unavailableMessage, Toast.LENGTH_LONG).show()
    false
}
