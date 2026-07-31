package com.dsoft.jgamer

import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.dsoft.jgamer.model.Prefs

/** App settings: resume-on-launch, auto save-state, vibration. Built in code. */
class SettingsActivity : AppCompatActivity() {

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
