package com.padreset.epadreset.ui

import android.content.Context
import com.padreset.epadreset.R

/**
 * Localized label for a service cleaning mode.
 *
 * The names come from the model database in English, and the protocol log keeps
 * them that way: the log is a diagnostic record, and someone asking for help
 * searches for the exact string that was sent to the printer. Everything the
 * user reads — the cleaning dialog, the notice after a job — goes through here
 * instead.
 *
 * The database wording also carries a leftover "%" where a placeholder was never
 * substituted ("Gentle % Cleaning"). These labels drop it.
 *
 * A name this table does not know is shown as the database has it. English is
 * worse than a translation and far better than an empty row, and a model with a
 * mode nobody has seen before still gets a usable dialog.
 *
 * Takes a [Context] rather than being a composable so the ViewModel can build
 * the notice with the same labels the dialog shows.
 */
fun cleaningModeLabel(context: Context, name: String): String = when (name) {
    "Gentle % Cleaning" -> context.getString(R.string.cleaning_mode_gentle)
    "Normal % Cleaning" -> context.getString(R.string.cleaning_mode_normal)
    "Medium % Cleaning" -> context.getString(R.string.cleaning_mode_medium)
    "Strong % Cleaning" -> context.getString(R.string.cleaning_mode_strong)
    "Gentle % Cleaning % Mode" -> context.getString(R.string.cleaning_mode_gentle_mode)
    "Normal % Cleaning % Mode" -> context.getString(R.string.cleaning_mode_normal_mode)
    "Strong % Cleaning % Mode" -> context.getString(R.string.cleaning_mode_strong_mode)
    "Strong % Cleaning A Mode" -> context.getString(R.string.cleaning_mode_strong_a)
    "Strong % Cleaning B Mode" -> context.getString(R.string.cleaning_mode_strong_b)
    "ALL % Gentle" -> context.getString(R.string.cleaning_all_gentle)
    "ALL % Normal" -> context.getString(R.string.cleaning_all_normal)
    "ALL % Medium" -> context.getString(R.string.cleaning_all_medium)
    "ALL % Strong" -> context.getString(R.string.cleaning_all_strong)
    "BLK % Gentle" -> context.getString(R.string.cleaning_black_gentle)
    "BLK % Normal" -> context.getString(R.string.cleaning_black_normal)
    "BLK % Medium" -> context.getString(R.string.cleaning_black_medium)
    "BLK % Strong" -> context.getString(R.string.cleaning_black_strong)
    "YMC % Gentle" -> context.getString(R.string.cleaning_color_gentle)
    "YMC % Normal" -> context.getString(R.string.cleaning_color_normal)
    "YMC % Medium" -> context.getString(R.string.cleaning_color_medium)
    "YMC % Strong" -> context.getString(R.string.cleaning_color_strong)
    else -> name
}
