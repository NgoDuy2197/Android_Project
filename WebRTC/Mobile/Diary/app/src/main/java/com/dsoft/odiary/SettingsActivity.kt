package com.dsoft.odiary

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : FxActivity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    private fun build() {
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(30)) }
        col.addView(barTitle())

        val acct = Accounts.get(Prefs.currentAccount(this))
        section("Tài khoản")
        col.addView(card(p).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(rowItem("Đang dùng", (if (acct?.isGoogle == true) "☁ " else "👤 ") + (acct?.name ?: "Khách")) {})
            if (acct != null && !acct.isGoogle) {
                addView(sep())
                addView(rowItem("Đổi mã PIN", "••••") { changePin(acct) })
                addView(sep())
                addView(rowItem("Đổi tên hiển thị", acct.name) { renameAcct(acct) })
            }
            if (acct?.isGoogle == true) {
                addView(sep())
                addView(rowItem("Đồng bộ từ tài khoản khác", "gộp vào acc này") { syncFromOther() })
            }
            addView(sep())
            addView(rowItem("Đổi / thêm tài khoản", "➔") { startActivity(Intent(this@SettingsActivity, AccountActivity::class.java)); finish() })
        })

        addGoogleSection()

        section("Giao diện (theme)")
        col.addView(themeRow())
        section("Hiệu ứng rơi")
        col.addView(choiceRow(Prefs.fallEffect(this), fallOpts()) { Prefs.setFallEffect(this, it); recreate() })
        section("Hiệu ứng khi chạm")
        col.addView(choiceRow(Prefs.tapEffect(this), tapOpts()) { Prefs.setTapEffect(this, it); recreate() })

        section("Ảnh nền ứng dụng")
        col.addView(card(p).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(rowItem("Bật ảnh nền", if (Prefs.appBgOn(this@SettingsActivity)) "✓ bật" else "tắt") {
                Prefs.setAppBgOn(this@SettingsActivity, !Prefs.appBgOn(this@SettingsActivity)); recreate()
            })
            addView(sep())
            addView(rowItem("Chọn ảnh nền", if (Store.appWallpaperFile().exists()) "đã có ảnh" else "chạm để chọn") { pickWallpaper() })
            addView(sep())
            addView(wallSlider("Độ sáng", Prefs.appBgDim(this@SettingsActivity), -80, 80) { Prefs.setAppBgDim(this@SettingsActivity, it) })
            addView(sep())
            addView(wallSlider("Độ mờ (blur)", Prefs.appBgBlur(this@SettingsActivity), 0, 25) { Prefs.setAppBgBlur(this@SettingsActivity, it) })
        })

        section("Nền nhật ký chung")
        col.addView(card(p).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(rowItem("Kiểu nền chung", DiaryBg.label(Prefs.globalBg(this@SettingsActivity).ifEmpty { "none" })) { globalBgMenu() })
        })

        section("Giọng đọc (Google TTS)")
        col.addView(card(p).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(rowItem("Chọn giọng", "chạm để chọn") { pickVoice() })
            addView(sep())
            addView(sliderRow("Tốc độ", Prefs.ttsRate(this@SettingsActivity), 50, 200, "%") { Prefs.setTtsRate(this@SettingsActivity, it) })
            addView(sep())
            addView(sliderRow("Cao độ", Prefs.ttsPitch(this@SettingsActivity), 50, 200, "%") { Prefs.setTtsPitch(this@SettingsActivity, it) })
            addView(sep())
            addView(rowItem("Nghe thử", "▶") { Tts.read(this@SettingsActivity, "Xin chào, đây là giọng đọc nhật ký của bạn.") })
        })

        section("Sao lưu / Khôi phục")
        col.addView(card(p).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(rowItem("Sao lưu ra file ZIP", "năm/tháng/ngày") { exportZip() })
            addView(sep())
            addView(rowItem("Khôi phục từ ZIP", "tự merge") { importZip() })
        })

        setFxContent(ScrollView(this).apply { addView(col) })
    }

    private fun gBadge(): View = text("G", 15f, android.graphics.Color.WHITE, 700).apply {
        gravity = Gravity.CENTER
        background = rounded(0xFF4285F4.toInt(), dp(14).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
    }

    /** Google sign-in + Drive sync card (placed right under the Account section). */
    private fun addGoogleSection() {
        section("Đăng nhập & đồng bộ Google")
        col.addView(card(p).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val header = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            header.addView(gBadge())
            header.addView(text("Google Drive", 16f, p.text, 700))
            addView(header)
            addView(sep())
            if (Drive.signedIn(this@SettingsActivity)) {
                addView(rowItem("Tài khoản", Drive.email(this@SettingsActivity) ?: "đã đăng nhập") {})
                addView(sep())
                addView(quotaRow())
                addView(sep())
                addView(rowItem("Tự động đồng bộ", if (Prefs.autoSync(this@SettingsActivity)) "✓ bật" else "tắt") {
                    Prefs.setAutoSync(this@SettingsActivity, !Prefs.autoSync(this@SettingsActivity)); recreate()
                })
                addView(sep())
                addView(rowItem("Đồng bộ ngay", "⟳") { syncDrive() })
                addView(sep())
                addView(rowItem("Đăng xuất", "") { Drive.signOut(this@SettingsActivity) { recreate() } })
            } else {
                addView(rowItem("Đăng nhập Google để đồng bộ", "chạm") { signInDrive() })
            }
        })
        val last = Prefs.lastSync(this)
        if (last.isNotEmpty()) col.addView(text("Lần đồng bộ cuối: $last", 12f, p.accent).apply { setPadding(dp(4), dp(6), 0, 0) })
        col.addView(text("Mỗi tài khoản đẩy lên Drive của chính mình (thư mục ODiary). Ngày đã có trên Drive sẽ tự merge.", 12f, p.sub).apply { setPadding(dp(4), dp(6), 0, 0) })
    }

    private fun barTitle(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(6))
        addView(text("‹", 24f, p.accent, 700).apply { minWidth = dp(40); gravity = Gravity.CENTER; setOnClickListener { finish() } })
        addView(text("Cài đặt", 22f, p.text, 700))
    }

    private fun section(t: String) = col.addView(text(t.uppercase(), 12f, p.sub, 600).apply { setPadding(dp(4), dp(16), 0, dp(6)) })
    private fun sep() = View(this).apply { setBackgroundColor(p.sep); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(8); bottomMargin = dp(8) } }

    /** "Dung lượng Drive" row; value is fetched in the background and filled in when ready. */
    private fun quotaRow(): View {
        val value = text("đang tải…", 14f, p.sub).apply { gravity = Gravity.END }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
            addView(text("Dung lượng Drive", 16f, p.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }
        Thread {
            val q = Drive.quota(applicationContext)
            App.main.post {
                if (q == null) { value.text = "không lấy được"; return@post }
                val (used, limit) = q
                if (limit <= 0) { value.text = "đã dùng ${fmtSize(used)}" }
                else {
                    val pct = (used * 100.0 / limit).toInt()
                    value.text = "${fmtSize(used)} / ${fmtSize(limit)} ($pct%)"
                    value.setTextColor(if (pct >= 90) p.red else p.sub)
                }
            }
        }.start()
        return row
    }

    private fun fmtSize(b: Long): String {
        val gb = b / 1_000_000_000.0
        if (gb >= 1) return String.format(Locale.US, "%.2f GB", gb)
        val mb = b / 1_000_000.0
        return String.format(Locale.US, "%.0f MB", mb)
    }

    private fun rowItem(title: String, value: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
        isClickable = true; setOnClickListener { onClick() }
        addView(text(title, 16f, p.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(value, 14f, p.sub).apply { gravity = Gravity.END })
    }

    private fun themeRow(): View {
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(4), dp(2), dp(4)) }
        val curId = Prefs.themeId(this)
        THEMES.forEach { th ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = gradientD(dp(16).toFloat(), th.accent, th.accent2)
                layoutParams = LinearLayout.LayoutParams(dp(92), dp(96)).apply { marginEnd = dp(10) }
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { Prefs.setThemeId(this@SettingsActivity, th.id); recreate() }
            }
            box.addView(text(th.emoji + (if (th.id == curId) " ✓" else ""), 22f, Color.WHITE, 700))
            box.addView(text(th.name, 13f, Color.WHITE, 600).apply { setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER })
            strip.addView(box)
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
    }

    private fun fallOpts() = linkedMapOf("none" to "Không", "sakura" to "🌸 Hoa rơi", "hearts" to "💗 Trái tim",
        "stars" to "⭐ Ngôi sao", "snow" to "❄ Tuyết", "fairy" to "🧚 Nàng tiên / bụi phép", "bubbles" to "🫧 Bong bóng")
    private fun tapOpts() = linkedMapOf("none" to "Không", "sparkle" to "✨ Lấp lánh", "hearts" to "💗 Tim nổ",
        "fairy" to "🧚 Bụi tiên", "ripple" to "◎ Gợn sóng", "burst" to "🎆 Pháo hoa")

    private fun choiceRow(current: String, opts: Map<String, String>, onPick: (String) -> Unit): View {
        return card(p).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            addView(rowItem("Hiệu ứng", opts[current] ?: current) {
                val keys = opts.keys.toList(); val labels = opts.values.toTypedArray()
                AlertDialog.Builder(this@SettingsActivity).setTitle("Chọn hiệu ứng")
                    .setSingleChoiceItems(labels, keys.indexOf(current)) { d, w -> d.dismiss(); onPick(keys[w]) }
                    .setNegativeButton("Đóng", null).show()
            })
        }
    }

    private fun sliderRow(title: String, cur: Int, min: Int, max: Int, suffix: String, onChange: (Int) -> Unit): View {
        val label = text("$title: $cur$suffix", 15f, p.text, 600)
        val seek = SeekBar(this).apply {
            this.max = max - min; progress = cur - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { label.text = "$title: ${v + min}$suffix"; if (u) onChange(v + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(seek) }
    }

    /** Slider for wallpaper brightness/blur: live-applies (recreate) when the user lets go. */
    private fun wallSlider(title: String, cur: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val label = text("$title: $cur", 15f, p.text, 600)
        val seek = SeekBar(this).apply {
            this.max = max - min; progress = cur - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { label.text = "$title: ${v + min}"; if (u) onChange(v + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { if (Prefs.appBgOn(this@SettingsActivity)) recreate() }
            })
        }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(seek) }
    }

    private fun pickWallpaper() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_WALL) }
        catch (_: Exception) { toast("Không mở được thư viện") }
    }

    private fun globalBgMenu() {
        val specs = DiaryBg.templates + listOf("img")
        val labels = specs.map { if (it == "img") "Import ảnh nền chung" else DiaryBg.label(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Nền chung").setItems(labels) { _, w ->
            val spec = specs[w]
            if (spec == "img") pickGlobalBg()
            else { Prefs.setGlobalBg(this, if (spec == "none") "" else spec); recreate() }
        }.show()
    }

    private fun pickGlobalBg() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_GBG) }
        catch (_: Exception) { toast("Không mở được thư viện") }
    }

    private fun pickVoice() {
        toast("Đang tải danh sách giọng…")
        Tts.voices(this) { voices ->
            if (voices.isEmpty()) { toast("Không có giọng (cần cài Google TTS)"); return@voices }
            val labels = (listOf("Mặc định (Tiếng Việt)") + voices.map { "${it.locale.displayName} · ${it.name}" }).toTypedArray()
            val cur = voices.indexOfFirst { it.name == Prefs.ttsVoice(this) } + 1
            AlertDialog.Builder(this).setTitle("Chọn giọng đọc")
                .setSingleChoiceItems(labels, cur) { d, w ->
                    Prefs.setTtsVoice(this, if (w == 0) "" else voices[w - 1].name); d.dismiss()
                    Tts.read(this, "Đã chọn giọng này.")
                }.setNegativeButton("Đóng", null).show()
        }
    }

    // ---- backup ----
    private fun exportZip() {
        try {
            val name = "diary-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip"
            val f = File(cacheDir, name)
            f.outputStream().use { Backup.export(this, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Lưu/chia sẻ bản sao lưu"))
        } catch (e: Exception) { toast("Lỗi sao lưu: ${e.message}") }
    }

    private fun importZip() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_ZIP) }
        catch (_: Exception) { toast("Không mở được trình chọn file") }
    }

    private fun signInDrive() {
        try { startActivityForResult(Drive.signInClient(this).signInIntent, REQ_SIGNIN) }
        catch (e: Exception) { toast("Không mở được đăng nhập Google: ${e.message}") }
    }

    private fun handleSignIn(data: Intent?) {
        try {
            val acc = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val email = acc.email ?: run { toast("Không lấy được email"); return }
            // Create (or find) the Google account and switch into it.
            val ga = Accounts.upsertGoogle(email, acc.displayName ?: "")
            Prefs.setCurrentAccount(this, ga.id); Store.setAccount(ga.id)
            Prefs.setPendingPull(this, true)   // pull existing Drive data + push local
            toast("Đã đăng nhập ${ga.name}")
            recreate()
        } catch (e: ApiException) {
            toast("Đăng nhập lỗi (mã ${e.statusCode}). Kiểm tra Test users / SHA-1.")
        }
    }

    private var syncDialog: android.app.AlertDialog? = null
    private var syncBar: ProgressBar? = null
    private var syncText: TextView? = null

    private fun showSyncDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(14)) }
        syncText = text("Đang chuẩn bị…", 14f, p.text).apply { setPadding(0, 0, 0, dp(12)) }
        box.addView(syncText)
        syncBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        box.addView(syncBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        syncDialog = AlertDialog.Builder(this).setTitle("Đồng bộ Google Drive").setView(box).setCancelable(false).create()
        syncDialog?.show()
    }

    private fun updateSync(done: Int, total: Int, label: String) {
        val bar = syncBar ?: return
        if (total <= 0) { bar.isIndeterminate = true; syncText?.text = label }
        else {
            bar.isIndeterminate = false; bar.max = total; bar.progress = done
            syncText?.text = "$label\n$done / $total tệp"
        }
    }

    private fun dismissSync() { runCatching { syncDialog?.dismiss() }; syncDialog = null; syncBar = null; syncText = null }

    private fun syncDrive() {
        showSyncDialog()
        Prefs.setPendingSync(this, false)   // claim queued changes (a download-merge may re-set it)
        Thread {
            try {
                // manual sync = push outbox + pull remote (full reconcile)
                Drive.sync(applicationContext, pull = true,
                    progress = { done, total, label -> App.main.post { updateSync(done, total, label) } },
                    log = { m -> App.main.post { dismissSync(); Prefs.setLastSync(this, "$m (${hhmm()})"); toast(m) } })
                Prefs.setPendingPull(this, false)
            } catch (e: Drive.RecoverableException) { Prefs.setPendingSync(this, true); App.main.post { dismissSync(); Prefs.setLastSync(this, "⚠ Cần cấp lại quyền Google (${hhmm()})"); toast("Cấp lại quyền Google rồi thử đồng bộ lại"); runCatching { startActivityForResult(e.intent, REQ_RECOVER) } } }
            catch (e: Exception) { Prefs.setPendingSync(this, true); App.main.post { dismissSync(); Prefs.setLastSync(this, "⚠ Lỗi: ${e.message} (${hhmm()})"); toast("Lỗi đồng bộ: ${e.message}") } }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SIGNIN) { if (resultCode == RESULT_OK) handleSignIn(data); return }
        if (requestCode == REQ_RECOVER) { if (resultCode == RESULT_OK) syncDrive(); return }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_GBG -> if (Store.saveGlobalBg(this, uri)) { Prefs.setGlobalBg(this, "img"); recreate() } else toast("Không lưu được ảnh")
            REQ_WALL -> if (Store.saveScaled(this, uri, Store.appWallpaperFile(), 1600)) { Prefs.setAppBgOn(this, true); recreate() } else toast("Không lưu được ảnh")
            REQ_ZIP -> Thread {
                val n = try { contentResolver.openInputStream(uri)?.use { Backup.restore(this, it) } ?: 0 } catch (e: Exception) { -1 }
                App.main.post { toast(if (n >= 0) "Đã khôi phục $n mục" else "Lỗi khôi phục") }
            }.start()
        }
    }

    /** Merge another account's diary into the current (Google) account. Local sources need their PIN. */
    private fun syncFromOther() {
        val me = Prefs.currentAccount(this)
        val others = Accounts.all().filter { it.id != me }
        if (others.isEmpty()) { toast("Không có tài khoản khác để gộp"); return }
        val labels = others.map {
            val n = Store.dayCountOf(it.id)
            (if (it.isGoogle) "☁ " else "🔒 ") + it.name + "  ($n ngày)"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Gộp dữ liệu từ")
            .setItems(labels) { _, i ->
                val src = others[i]
                if (src.isGoogle) doMerge(src) else askPinThenMerge(src)
            }.setNegativeButton("Huỷ", null).show()
    }

    private fun askPinThenMerge(src: Account) {
        val et = EditText(this).apply {
            hint = "Nhập PIN của \"${src.name}\""; setTextColor(p.text)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Xác thực tài khoản").setView(et)
            .setPositiveButton("Đồng bộ") { _, _ ->
                if (Accounts.checkPin(src, et.text.toString())) doMerge(src)
                else toast("Sai PIN — không gộp được")
            }.setNegativeButton("Huỷ", null).show()
    }

    private fun doMerge(src: Account) {
        toast("Đang gộp từ ${src.name}…")
        Thread {
            val n = try { Store.mergeFromAccount(src.id) } catch (e: Exception) { -1 }
            App.main.post {
                if (n < 0) { toast("Lỗi gộp dữ liệu"); return@post }
                toast("Đã gộp $n ngày từ ${src.name}")
                if (Drive.signedIn(this)) AlertDialog.Builder(this)
                    .setTitle("Đẩy lên Drive?").setMessage("Đồng bộ dữ liệu vừa gộp lên Google Drive của tài khoản này?")
                    .setPositiveButton("Đồng bộ") { _, _ -> syncDrive() }.setNegativeButton("Để sau", null).show()
            }
        }.start()
    }

    private fun changePin(a: Account) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(4)) }
        fun pinField(hint: String) = EditText(this).apply {
            this.hint = hint; setTextColor(p.text)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val old = pinField("PIN hiện tại")
        val nw = pinField("PIN mới (tối thiểu 4 số)")
        val cf = pinField("Nhập lại PIN mới")
        box.addView(old); box.addView(nw); box.addView(cf)
        AlertDialog.Builder(this).setTitle("Đổi mã PIN").setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                when {
                    !Accounts.checkPin(a, old.text.toString()) -> toast("PIN hiện tại không đúng")
                    nw.text.toString().length < 4 -> toast("PIN mới tối thiểu 4 số")
                    nw.text.toString() != cf.text.toString() -> toast("Nhập lại PIN không khớp")
                    else -> { Accounts.setPin(a, nw.text.toString()); toast("Đã đổi mã PIN") }
                }
            }.setNegativeButton("Huỷ", null).show()
    }

    private fun renameAcct(a: Account) {
        val et = EditText(this).apply { setText(a.name); setTextColor(p.text) }
        AlertDialog.Builder(this).setTitle("Đổi tên hiển thị").setView(et)
            .setPositiveButton("Lưu") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { Accounts.rename(a, n); recreate() }
            }.setNegativeButton("Huỷ", null).show()
    }

    private fun hhmm() = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { private const val REQ_GBG = 11; private const val REQ_ZIP = 12; private const val REQ_WALL = 13; private const val REQ_SIGNIN = 21; private const val REQ_RECOVER = 22 }
}
