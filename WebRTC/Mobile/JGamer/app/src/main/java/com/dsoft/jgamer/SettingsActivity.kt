package com.dsoft.jgamer

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.dsoft.jgamer.model.GameSystem
import com.dsoft.jgamer.model.Prefs
import com.dsoft.jgamer.model.RomFolders

/** App settings: resume-on-launch, auto save-state, vibration, per-system ROM folders. Built in code. */
class SettingsActivity : AppCompatActivity() {

    // Folder row summaries, refreshed after pick / clear.
    private val folderSummaries = HashMap<GameSystem, TextView>()
    private var pendingSystem: GameSystem? = null

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> val sys = pendingSystem; if (uri != null && sys != null) onFolderPicked(sys, uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20); setPadding(p, p, p, p)
        }

        col.addView(header(getString(R.string.settings)))

        col.addView(switchRow(getString(R.string.opt_resume_launch), getString(R.string.opt_resume_launch_sum), prefs.resumeOnLaunch) {
            prefs.resumeOnLaunch = it
        })
        col.addView(switchRow(getString(R.string.opt_auto_save), getString(R.string.opt_auto_save_sum), prefs.autoSaveState) {
            prefs.autoSaveState = it
        })
        col.addView(switchRow(getString(R.string.opt_vibrate), getString(R.string.opt_vibrate_sum), prefs.vibrate) {
            prefs.vibrate = it
        })
        col.addView(choiceRow(getString(R.string.opt_pad), getString(R.string.opt_pad_sum),
            arrayOf(getString(R.string.pad_auto), getString(R.string.pad_on), getString(R.string.pad_off)),
            { prefs.padMode }, { prefs.padMode = it }))
        col.addView(choiceRow(getString(R.string.opt_display), getString(R.string.opt_display_sum),
            arrayOf(getString(R.string.display_fit), getString(R.string.display_fill), getString(R.string.display_integer)),
            { prefs.displayMode }, { prefs.displayMode = it }))

        // ---- Per-system ROM folders ----
        col.addView(header(getString(R.string.folders_header)).apply { setPadding(0, dp(24), 0, dp(4)) })
        col.addView(TextView(this).apply { text = getString(R.string.folders_info); alpha = 0.6f; textSize = 13f })
        GameSystem.entries.forEach { col.addView(folderRow(it)) }

        col.addView(TextView(this).apply {
            text = getString(R.string.settings_info)
            setPadding(0, dp(24), 0, 0); alpha = 0.7f; textSize = 13f
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setPadding(0, 0, 0, dp(12))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun switchRow(title: String, summary: String, value: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            val sw = SwitchCompat(this@SettingsActivity).apply {
                text = title; isChecked = value; textSize = 16f
                setOnCheckedChangeListener { _, b -> onChange(b) }
            }
            addView(sw)
            addView(TextView(this@SettingsActivity).apply { text = summary; alpha = 0.6f; textSize = 13f })
        }

    /** Tap (or OK on a remote) -> single-choice dialog; shows the current value. */
    private fun choiceRow(title: String, summary: String, names: Array<String>, get: () -> Int, set: (Int) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isFocusable = true; isClickable = true
            setBackgroundResource(R.drawable.focus_bg)
            val value = TextView(this@SettingsActivity).apply { textSize = 14f; setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.brand)) }
            fun show() { value.text = names[get().coerceIn(0, names.size - 1)] }
            addView(TextView(this@SettingsActivity).apply { text = title; textSize = 16f })
            addView(value)
            addView(TextView(this@SettingsActivity).apply { text = summary; alpha = 0.6f; textSize = 13f })
            show()
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(title)
                    .setSingleChoiceItems(names, get().coerceIn(0, names.size - 1)) { d, w -> set(w); show(); d.dismiss() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

    private fun folderRow(system: GameSystem) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        val texts = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply { text = system.displayName; textSize = 16f })
            addView(TextView(this@SettingsActivity).apply { alpha = 0.6f; textSize = 13f }
                .also { folderSummaries[system] = it })
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@SettingsActivity, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.folder_choose)
            setOnClickListener {
                pendingSystem = system
                runCatching { folderLauncher.launch(RomFolders.get(this@SettingsActivity, system)) }
                    .onFailure { toast(getString(R.string.import_failed)) }
            }
        })
        addView(Button(this@SettingsActivity, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.folder_clear)
            setOnClickListener { RomFolders.clear(this@SettingsActivity, system); updateFolder(system) }
        })
        updateFolder(system)
    }

    private fun updateFolder(system: GameSystem) {
        val uri = RomFolders.get(this, system)
        folderSummaries[system]?.text = uri?.let { RomFolders.label(it) } ?: getString(R.string.folder_not_set)
    }

    private fun onFolderPicked(system: GameSystem, uri: Uri) {
        if (RomFolders.set(this, system, uri)) toast(getString(R.string.folder_set, system.displayName, RomFolders.label(uri)))
        else toast(getString(R.string.folder_grant_failed))
        updateFolder(system)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
