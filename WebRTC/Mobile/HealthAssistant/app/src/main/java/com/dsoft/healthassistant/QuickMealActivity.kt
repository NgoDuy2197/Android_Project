package com.dsoft.healthassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Invisible trampoline for the home-screen widget: opens a new meal for *today* at tap time. */
class QuickMealActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MealEditorActivity::class.java)
                .putExtra("date", Store.today())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
