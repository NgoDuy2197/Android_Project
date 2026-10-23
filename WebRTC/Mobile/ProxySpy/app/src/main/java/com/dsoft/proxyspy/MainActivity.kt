package com.dsoft.proxyspy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.security.KeyChain
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: TextView
    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private val adapter = FlowAdapter()
    private var stick = true

    private val onFlows: () -> Unit = { runOnUiThread { refreshList() } }
    private val onState: () -> Unit = { runOnUiThread { refreshState() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        FlowStore.addListener(onFlows)
        ProxyService.listener = onState
        refreshState(); refreshList()
    }

    override fun onPause() {
        super.onPause()
        FlowStore.removeListener(onFlows)
        ProxyService.listener = null
    }

    private fun buildUi() {
        window.statusBarColor = p.card
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.card)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        header.addView(text("ProxySpy", 22f, p.text, 600))
        statusText = text("", 13f, p.sec).apply { setPadding(0, dp(2), 0, 0) }
        header.addView(statusText)

        // controls
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(2))
        }
        toggleBtn = pill("Bật proxy", p.green, android.graphics.Color.WHITE) { toggle() }
        controls.addView(toggleBtn, lpRow())
        controls.addView(pill("Cài CA", p.fill, p.blue) { installCaHere() }, lpRow())
        controls.addView(pill("QR", p.fill, p.blue) { showQr() }, lpRow())
        controls.addView(pill("Xoá", p.fill, p.red) { FlowStore.clear() }, lpRow())
        header.addView(controls)

        val controls2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
        }
        controls2.addView(pill("Máy khách (root)", p.fill, p.purple) {
            startActivity(Intent(this, ClientActivity::class.java))
        }, lpRow())
        controls2.addView(pill("⚙ Rules", p.fill, p.blue) {
            startActivity(Intent(this, RulesActivity::class.java))
        }, lpRow())
        header.addView(controls2)
        root.addView(header)

        // list
        lm = LinearLayoutManager(this)
        rv = RecyclerView(this).apply {
            layoutManager = lm
            adapter = this@MainActivity.adapter
            setBackgroundColor(p.bg)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                    stick = !r.canScrollVertically(1)   // bám đáy khi đã ở cuối
                }
            })
        }
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun lpRow() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { marginStart = dp(5); marginEnd = dp(5); if (leftMargin == 0) {} }

    private fun toggle() {
        if (ProxyService.running) ProxyService.stop(this)
        else { ProxyService.start(this); Toast.makeText(this, "Đang mở proxy…", Toast.LENGTH_SHORT).show() }
        App.main.postDelayed({ refreshState() }, 400)
    }

    private fun refreshState() {
        val running = ProxyService.running
        val ip = Net.primaryIp()
        statusText.text = if (running) "Proxy: $ip : ${ProxyService.port}   •   đặt proxy máy khác vào đây"
        else (ProxyService.error ?: "Đang tắt — bấm Bật proxy")
        toggleBtn.text = if (running) "Dừng" else "Bật proxy"
        toggleBtn.background = rounded(if (running) p.red else p.green, dp(12).toFloat())
    }

    private fun refreshList() {
        adapter.submit(FlowStore.all())
        if (stick && adapter.itemCount > 0) rv.scrollToPosition(adapter.itemCount - 1)
    }

    private fun installCaHere() {
        try {
            val i = KeyChain.createInstallIntent()
            i.putExtra(KeyChain.EXTRA_CERTIFICATE, CertManager.caDer())
            i.putExtra(KeyChain.EXTRA_NAME, "ProxySpy CA")
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Không mở được trình cài CA: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showQr() {
        val ip = Net.primaryIp()
        val port = if (ProxyService.running) ProxyService.port else Prefs.port(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        box.addView(text("Máy khác: đặt Wi-Fi proxy", 15f, p.text, 600))
        box.addView(text("$ip : $port", 20f, p.blue, 600).apply { setPadding(0, dp(4), 0, dp(10)) })
        val qr = Qr.bitmap("http://$ip:$port/", 560)
        if (qr != null) box.addView(ImageView(this).apply {
            setImageBitmap(qr)
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220))
        })
        box.addView(text("Máy khách quét mã này ở màn \"Máy khách (root)\", hoặc mở http://$ip:$port để tải CA.",
            12f, p.sec).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Đóng", null).show()
    }

    // ---- list adapter ----
    private inner class FlowAdapter : RecyclerView.Adapter<FlowAdapter.VH>() {
        private var items: List<Flow> = emptyList()
        private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        fun submit(list: List<Flow>) { items = list; notifyDataSetChanged() }

        inner class VH(val row: LinearLayout, val m: TextView, val host: TextView,
                       val path: TextView, val st: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val m = text("", 11f, p.sec, 600).apply {
                minWidth = dp(44); gravity = Gravity.CENTER
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }
            val host = text("", 12f, p.sec)
            val path = text("", 14f, p.text).apply { maxLines = 1 }
            val st = text("", 12f, p.sec, 600).apply { minWidth = dp(38); gravity = Gravity.END }
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(host); addView(path)
            }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = rounded(android.graphics.Color.TRANSPARENT, 0f)
                addView(m)
                addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(10); marginEnd = dp(8) })
                addView(st)
            }
            val sep = View(this@MainActivity).apply {
                setBackgroundColor(p.sep)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            }
            val wrap = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(row); addView(sep)
            }
            // use wrap as itemView but keep refs to inner views
            return VH(wrap, m, host, path, st)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = items[position]
            h.m.text = f.method
            h.m.setTextColor(methodColor(p, f.method))
            h.m.background = rounded(p.fill, dp(6).toFloat())
            h.host.text = timeFmt.format(f.time) + "  " + (if (f.scheme == "https") "🔒 " else "") + f.host
            h.path.text = f.path
            h.st.text = when { f.error != null -> "ERR"; f.pending -> "…"; else -> f.status.toString() }
            h.st.setTextColor(if (f.error != null) p.red else statusColor(p, f.status))
            h.row.setOnClickListener {
                startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra("id", f.id))
            }
        }
    }
}
