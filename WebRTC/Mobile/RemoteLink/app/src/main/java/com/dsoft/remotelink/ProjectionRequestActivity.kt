package com.dsoft.remotelink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/** Transparent activity: asks for the screen-capture consent, then starts [RemoteService] with the token. */
class ProjectionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("startActivityForResult is fine for this one-shot consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, RemoteService::class.java)
                .putExtra(RemoteService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(RemoteService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        } else {
            Log2.w("screen-capture consent denied")
        }
        finish()
    }

    companion object {
        private const val REQ = 7001
        /** Called from the UI to begin capture in MediaProjection mode. */
        fun launch(ctx: Context) {
            ctx.startActivity(Intent(ctx, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
