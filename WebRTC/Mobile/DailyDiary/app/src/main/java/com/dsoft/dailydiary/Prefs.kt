package com.dsoft.dailydiary

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("diary", Context.MODE_PRIVATE)
    }

    /** Generate the daily PDF automatically at the end of the day. */
    var autoSummary: Boolean
        get() = sp.getBoolean("auto_summary", true)
        set(v) = sp.edit { putBoolean("auto_summary", v) }

    /** Minutes after midnight when the end-of-day job runs (default 23:30). */
    var summaryMinute: Int
        get() = sp.getInt("summary_minute", 23 * 60 + 30)
        set(v) = sp.edit { putInt("summary_minute", v) }

    var driveEmail: String?
        get() = sp.getString("drive_email", null)
        set(v) = sp.edit { putString("drive_email", v) }

    /** Upload a backup to Drive right after the end-of-day summary. */
    var driveAutoSync: Boolean
        get() = sp.getBoolean("drive_auto", true)
        set(v) = sp.edit { putBoolean("drive_auto", v) }

    var lastDriveSync: Long
        get() = sp.getLong("drive_last", 0)
        set(v) = sp.edit { putLong("drive_last", v) }
}
