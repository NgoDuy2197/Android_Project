package com.dsoft.noti_forward

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Lists launchable installed apps for the Flutter app picker. Icons are returned
 * as tiny base64 PNG thumbnails so the UI can show real app icons without a
 * native image plugin.
 */
object AppCatalog {

    data class AppRow(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val iconBase64: String?,
    )

    fun listLaunchableApps(
        pm: PackageManager,
        includeIcons: Boolean = true,
        iconSizePx: Int = 48,
    ): List<Map<String, Any?>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val rows = ArrayList<AppRow>(activities.size)

        for (ri in activities) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val ai = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                continue
            }
            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg
            }
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val icon = if (includeIcons) {
                try {
                    drawableToBase64Png(pm.getApplicationIcon(ai), iconSizePx)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            rows.add(AppRow(pkg, label, isSystem, icon))
        }

        rows.sortWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.packageName }
        )

        return rows.map {
            mapOf(
                "package" to it.packageName,
                "label" to it.label,
                "isSystem" to it.isSystem,
                "icon" to it.iconBase64,
            )
        }
    }

    private fun drawableToBase64Png(drawable: Drawable, size: Int): String? {
        val bitmap = when (drawable) {
            is BitmapDrawable -> {
                val src = drawable.bitmap ?: return null
                Bitmap.createScaledBitmap(src, size, size, true)
            }
            else -> {
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                bmp
            }
        }
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }
}
