package com.dsoft.screengrab

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dsoft.screengrab.net.MediaCatalog
import com.dsoft.screengrab.net.MediaDownloader
import com.dsoft.screengrab.net.MediaItem
import com.dsoft.screengrab.ui.*

/** Lists the video/audio streams the sniffer detected; tap Tải to download (streams are merged). */
class MediaListActivity : Activity() {

    private val uiListener = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        MediaCatalog.addListener(uiListener)
        SniffService.addListener(uiListener)
        render()
    }

    override fun onPause() {
        super.onPause()
        MediaCatalog.removeListener(uiListener)
        SniffService.removeListener(uiListener)
    }

    private fun render() {
        val col = page("Video bắt được", "Bật 'Bắt video' rồi phát video trong app/Chrome. Link hiện ở đây — bấm Tải.")

        // status
        val on = SniffService.running
        val sub = when {
            !on -> "Đang tắt"
            SniffService.routeReady -> "Đang bắt · proxy ${SniffService.proxyAddr}"
            SniffService.lastError.isNotBlank() -> SniffService.lastError
            else -> "Proxy ${SniffService.proxyAddr}"
        }
        col.statusRow("Trình bắt video", sub, on && (SniffService.routeReady || Prefs(this).snifferRouting == "vpn"),
            if (on) "Dừng" else "Bật") {
            if (on) SniffService.stop(this) else { Prefs(this).snifferEnabled = true; SniffService.start(this) }
        }

        val items = MediaCatalog.all()
        col.addView(sectionHeader("ĐÃ PHÁT HIỆN (${items.size})"))
        if (items.isEmpty()) {
            col.addView(tv("Chưa có video nào. Mở app/web và phát thử một video.", 13.5f, Theme.secondary)
                .apply { setPadding(dp(4), dp(8), dp(4), 0) })
        } else {
            col.addView(filledButton("🗑  Xoá danh sách", Theme.card) { MediaCatalog.clear() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            for (item in items) col.addView(itemCard(item),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(12) })
        }
    }

    private fun itemCard(item: MediaItem): LinearLayout {
        val c = card()
        c.addView(tv(item.title, 15.5f, Theme.text, bold = true))
        val sizeTxt = if (item.sizeBytes > 0) " · ${item.sizeBytes / (1024 * 1024)}MB" else ""
        c.addView(tv("[${item.label}] ${item.host}$sizeTxt", 12f, Theme.secondary).apply { setPadding(0, dp(2), 0, 0) })
        c.addView(tv(item.url, 11f, Theme.secondary).apply { setPadding(0, dp(2), 0, 0); maxLines = 2 })

        when (item.state) {
            MediaItem.State.DOWNLOADING -> {
                val p = if (item.progress in 0..100) "${item.progress}%" else "…"
                c.addView(tv("⏬ $p  ${item.detail}", 12.5f, Theme.accent2).apply { setPadding(0, dp(6), 0, 0) })
                c.addView(filledButton("Huỷ", Theme.card) { MediaDownloader.cancel(item.id) },
                    btnLp())
            }
            MediaItem.State.DONE -> {
                c.addView(tv("✅ ${item.detail}", 12.5f, Theme.green).apply { setPadding(0, dp(6), 0, 0) })
                c.addView(filledButton("Mở", Theme.accent) { openUri(item.savedUri) }, btnLp())
            }
            MediaItem.State.ERROR -> {
                c.addView(tv("⚠ ${item.detail}", 12.5f, Theme.red).apply { setPadding(0, dp(6), 0, 0) })
                c.addView(filledButton("Thử lại", Theme.accent2) { startDownload(item) }, btnLp())
            }
            else -> {
                c.addView(filledButton("⬇  Tải", Theme.accent) { startDownload(item) }, btnLp())
            }
        }
        return c
    }

    private fun btnLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(8) }

    private fun startDownload(item: MediaItem) {
        Thread { MediaDownloader.download(this, item) }.start()
    }

    private fun openUri(uri: String?) {
        if (uri == null) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }
}
