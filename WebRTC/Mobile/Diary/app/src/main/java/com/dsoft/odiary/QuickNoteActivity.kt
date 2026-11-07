package com.dsoft.odiary

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Invisible trampoline for the home-screen "Ghi nhanh" widget: works out *today*
 * at tap time (so the widget never carries a stale date) and jumps straight into
 * the editor for a new moment. If no account is set up yet, it opens the app so
 * the user can pick/create one first.
 */
class QuickNoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cur = Prefs.currentAccount(this)
        val target = if (cur.isEmpty() || Accounts.get(cur) == null) {
            Intent(this, MainActivity::class.java)
        } else {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            Intent(this, EventEditorActivity::class.java).putExtra("date", today)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(target)
        finish()
    }
}
