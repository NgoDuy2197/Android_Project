package com.dsoft.jgamer.ui

import android.app.Activity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dsoft.jgamer.R
import com.dsoft.jgamer.model.GameEntry
import com.dsoft.jgamer.model.GameRepository
import com.dsoft.jgamer.model.Prefs

/**
 * Confirm-and-delete for a ROM: removes it from the library (copy, states,
 * SRAM) and — ticked by default when the source is known — deletes the
 * original file on the device too. Shared by the library and the player.
 */
object DeleteRomDialog {

    fun show(activity: Activity, entry: GameEntry, onDeleted: () -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val hasSource = entry.sourceUri != null
        val check = CheckBox(activity).apply {
            setText(R.string.delete_original)
            isChecked = hasSource
            isEnabled = hasSource
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(activity).apply { setText(R.string.delete_confirm_msg) })
            addView(check)
            if (!hasSource) addView(TextView(activity).apply {
                setText(R.string.delete_original_unknown); alpha = 0.6f; textSize = 12f
            })
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.delete_confirm_title, entry.title))
            .setView(col)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val wantOriginal = check.isChecked
                val prefs = Prefs(activity)
                if (prefs.lastGameId == entry.id) prefs.lastGameId = null
                val originalGone = GameRepository.get(activity).remove(entry.id, deleteOriginal = wantOriginal)
                val msg = when {
                    originalGone -> R.string.deleted_with_original
                    wantOriginal -> R.string.deleted_original_failed
                    else -> R.string.deleted_library_only
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                onDeleted()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
