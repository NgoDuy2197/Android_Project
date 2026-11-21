package com.dsoft.proxyspy

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Picks which apps ProxySpy captures.
 *
 * This is the *second* filter: LSPosed decides which apps the module is loaded into at all (its
 * scope list), and this screen decides which of those actually stream their traffic here. Apps
 * without INTERNET are hidden — they could never reach the Hub's loopback socket anyway.
 */
class AppPickerActivity : Activity() {

    private class Item(val pkg: String, val label: String, val system: Boolean)

    private lateinit var p: Palette
    private lateinit var rv: RecyclerView
    private lateinit var info: TextView
    private var all: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var query = ""
    private var selected: MutableSet<String> = HashSet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        window.statusBarColor = p.card
        selected = Prefs.targets.toMutableSet()

        val root = col().apply { setBackgroundColor(p.bg) }
        val header = col().apply {
            setBackgroundColor(p.card)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        header.addView(text("Chọn app để theo dõi", 22f, p.text, 600))
        info = text("Đang đọc danh sách app…", 12f, p.sec)
        header.addView(info, vlp().apply { topMargin = dp(3) })
        header.addView(switchRow(p, "Bắt mọi app LSPosed nạp module vào", Prefs.allApps) {
            Prefs.allApps = it
            Hub.broadcastConfig()
            updateInfo()
        })
        header.addView(switchRow(p, "Bắt frame WebSocket", Prefs.captureWs) {
            Prefs.captureWs = it
            Hub.broadcastConfig()
        })
        header.addView(switchRow(p, "Bắt tầng thô (socket) — chỉ ghi, chậm hơn", Prefs.captureRaw) {
            Prefs.captureRaw = it
            Hub.broadcastConfig()
        })

        val search = input(p, "Tìm theo tên hoặc package…", "")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                applyFilter()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        header.addView(search, vlp().apply { topMargin = dp(8) })
        root.addView(header, vlp())

        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AppPickerActivity)
            adapter = Adapter()
            setPadding(dp(10), dp(8), dp(10), dp(24))
            clipToPadding = false
        }
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Thread { load() }.start()
    }

    override fun onPause() {
        super.onPause()
        Prefs.targets = selected
        Hub.broadcastConfig()
    }

    private fun load() {
        val pm = packageManager
        val list = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        val items = list.mapNotNull { ai ->
            if (ai.packageName == packageName) return@mapNotNull null
            if (!hasInternet(pm, ai.packageName)) return@mapNotNull null
            Item(
                ai.packageName,
                runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName),
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }.sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
        runOnUiThread {
            all = items
            applyFilter()
            updateInfo()
        }
    }

    private fun hasInternet(pm: PackageManager, pkg: String): Boolean = runCatching {
        pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
    }.getOrDefault(false)

    private fun updateInfo() {
        info.text = if (Prefs.allApps) "Đang bắt MỌI app — danh sách dưới chỉ để tham khảo"
        else "${selected.size} app được chọn · ${all.size} app có quyền mạng"
    }

    private fun applyFilter() {
        shown = if (query.isEmpty()) all
        else all.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
        rv.adapter?.notifyDataSetChanged()
    }

    private inner class Holder(val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        val icon = ImageView(this@AppPickerActivity)
        val name = text("", 15f, p.text, 600)
        val pkg = text("", 11f, p.sec)
        val mark = text("", 20f, p.green, 600)

        init {
            box.orientation = LinearLayout.HORIZONTAL
            box.gravity = Gravity.CENTER_VERTICAL
            box.background = rounded(p.card, dp(12).toFloat())
            box.setPadding(dp(12), dp(10), dp(12), dp(10))
            box.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
            val c = col()
            c.addView(name)
            c.addView(pkg, vlp().apply { topMargin = dp(2) })
            box.addView(c, wlp())
            box.addView(mark)
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val box = LinearLayout(this@AppPickerActivity)
            box.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            return Holder(box)
        }

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val it = shown[position]
            h.name.text = it.label
            h.pkg.text = it.pkg + (if (it.system) "  · hệ thống" else "")
            h.mark.text = if (selected.contains(it.pkg)) "✓" else ""
            runCatching { h.icon.setImageDrawable(packageManager.getApplicationIcon(it.pkg)) }
                .onFailure { _ -> h.icon.setImageDrawable(null) }
            h.box.setOnClickListener { _ ->
                if (!selected.remove(it.pkg)) selected.add(it.pkg)
                Prefs.targets = selected
                Hub.broadcastConfig()
                notifyItemChanged(position)
                updateInfo()
            }
            h.box.setOnLongClickListener { _ ->
                restartDialog(it)
                true
            }
        }
    }

    /**
     * A hook only takes effect from the app's next start, so offer the restart right here.
     * `am force-stop` needs root; without it the user can swipe the app away by hand.
     */
    private fun restartDialog(item: Item) {
        android.app.AlertDialog.Builder(this)
            .setTitle(item.label)
            .setMessage(
                "Hook chỉ gắn khi app khởi động lại.\n\n" +
                    "Ép dừng ${item.pkg} bằng root để lần mở tới được hook?"
            )
            .setPositiveButton("Ép dừng") { _, _ ->
                Thread {
                    val r = Root.forceStop(item.pkg)
                    runOnUiThread {
                        toast(if (r.ok) "Đã dừng ${item.label}" else "Không chạy được su: ${r.out.take(80)}")
                    }
                }.start()
            }
            .setNegativeButton("Thôi", null)
            .show()
    }
}
