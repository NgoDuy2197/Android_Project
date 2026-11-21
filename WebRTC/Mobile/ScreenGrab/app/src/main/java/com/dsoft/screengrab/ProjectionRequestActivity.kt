package com.dsoft.screengrab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/** Transparent activity: asks for the screen-capture consent, then starts [CaptureService] with the token + action. */
class ProjectionRequestActivity : Activity() {

    private var action: String = CaptureService.ACTION_SHOT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        action = intent.getStringExtra(CaptureService.EXTRA_ACTION) ?: CaptureService.ACTION_SHOT
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("startActivityForResult is fine for this one-shot consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_ACTION, action)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        } else {
            Log2.w("screen-capture consent denied")
        }
        finish()
    }

    companion object {
        private const val REQ = 7001
        /** Begin a projection-mode action; shows the consent dialog. */
        fun launch(ctx: Context, action: String) {
            ctx.startActivity(Intent(ctx, ProjectionRequestActivity::class.java)
                .putExtra(CaptureService.EXTRA_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
