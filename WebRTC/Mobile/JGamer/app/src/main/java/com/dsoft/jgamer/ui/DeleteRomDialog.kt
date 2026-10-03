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
 * original file on the device too. Library only (row trash / long-press /
 * multi-delete); the in-game menu deliberately has no delete.
 */
object DeleteRomDialog {

    fun show(activity: Activity, entry: GameEntry, onDeleted: () -> Unit) {
        confirm(activity, listOf(entry), activity.getString(R.string.delete_confirm_title, entry.title), onDeleted)
    }

    /** Multi-select: tick games from [list], then the same confirm as [show]. */
    fun showMulti(activity: Activity, list: List<GameEntry>, onDeleted: () -> Unit) {
        if (list.isEmpty()) { Toast.makeText(activity, R.string.delete_none, Toast.LENGTH_SHORT).show(); return }
        val ticked = BooleanArray(list.size)
        AlertDialog.Builder(activity)
            .setTitle(R.string.delete_pick_title)
            .setMultiChoiceItems(list.map { it.title }.toTypedArray(), ticked) { _, w, on -> ticked[w] = on }
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val chosen = list.filterIndexed { i, _ -> ticked[i] }
                if (chosen.isEmpty()) return@setPositiveButton
                val title = activity.resources.getQuantityString(R.plurals.delete_confirm_multi, chosen.size, chosen.size)
                confirm(activity, chosen, title, onDeleted)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirm(activity: Activity, list: List<GameEntry>, title: String, onDeleted: () -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val hasSource = list.any { it.sourceUri != null }
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
            .setTitle(title)
            .setView(col)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val wantOriginal = check.isChecked
                val prefs = Prefs(activity)
                val repo = GameRepository.get(activity)
                var failed = 0
                list.forEach { e ->
                    if (prefs.lastGameId == e.id) prefs.lastGameId = null
                    val originalGone = repo.remove(e.id, deleteOriginal = wantOriginal && e.sourceUri != null)
                    if (wantOriginal && e.sourceUri != null && !originalGone) failed++
                    // Kept on the device: stop the folder scan from re-adding it.
                    if (!originalGone) e.sourceUri?.let { prefs.addScanIgnore(it) }
                }
                if (failed > 0) {
                    // Permission problem: explain instead of a toast that's easy to miss.
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.deleted_original_failed)
                        .setMessage(activity.getString(R.string.deleted_original_failed_msg, failed))
                        .setPositiveButton(android.R.string.ok, null).show()
                } else {
                    val msg = if (wantOriginal) R.string.deleted_with_original else R.string.deleted_library_only
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
                onDeleted()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
