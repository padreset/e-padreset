package com.padreset.epadreset.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copies [text] to the clipboard marked as sensitive.
 *
 * The protocol log carries the printer's IEEE 1284 device ID, serial number
 * included. Android 13+ shows a preview of whatever is copied, and keyboards
 * with clipboard history keep it; the sensitive flag hides it from both. The
 * support report needs none of this because it never carries the serial.
 *
 * Below API 33 the extra is ignored by the system but harmless, and some
 * keyboards honour the same key, so it is set on every version.
 */
internal fun Context.copySensitiveText(label: String, text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            SENSITIVE_EXTRA_KEY
        }
        putBoolean(key, true)
    }
    clipboard.setPrimaryClip(clip)
}

/** The literal value of [ClipDescription.EXTRA_IS_SENSITIVE], usable before API 33. */
private const val SENSITIVE_EXTRA_KEY = "android.content.extra.IS_SENSITIVE"
