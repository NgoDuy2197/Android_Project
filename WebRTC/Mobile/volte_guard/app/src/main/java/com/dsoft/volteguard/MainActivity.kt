package com.dsoft.volteguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dsoft.volteguard.databinding.ActivityMainBinding
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val runtimePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPerms(); checkNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.tvVersion.text = "v${BuildConfig.VERSION_NAME} · Giữ HD Call (VoLTE) luôn sống"
        b.tvAdb.text = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n" +
            "adb shell pm grant $packageName android.permission.READ_PHONE_STATE"

        bindSettings()
        bindActions()
        observe()

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) requestRuntime()
        if (prefs.enabled && !Engine.serviceRunning.value) GuardService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        checkNow()
    }

    // ---- Settings ----------------------------------------------------------------------

    private fun bindSettings() {
        b.tgSim.check(if (prefs.simSlot == 1) R.id.btnSim2 else R.id.btnSim1)
        b.tgSim.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val slot = if (id == R.id.btnSim2) 1 else 0
            if (slot != prefs.simSlot) {
                prefs.simSlot = slot
                LogStore.i("Đổi SIM theo dõi → SIM ${slot + 1}")
                kickService(); checkNow()
            }
        }

        bindNumber(b.etInterval, prefs.intervalSec, 10..86_400) { prefs.intervalSec = it; kickService() }
        bindNumber(b.etConfirm, prefs.confirmCount, 1..20) { prefs.confirmCount = it }
        bindNumber(b.etSettle, prefs.settleSec, 5..600) { prefs.settleSec = it }
        bindNumber(b.etCooldown, prefs.cooldownSec, 0..86_400) { prefs.cooldownSec = it }

        bindSwitch(b.swCarrier, prefs.stepCarrierConfig) { prefs.stepCarrierConfig = it }
        bindSwitch(b.swSwitch, prefs.stepVolteSwitch) { prefs.stepVolteSwitch = it }
        bindSwitch(b.swResetIms, prefs.stepResetIms) { prefs.stepResetIms = it }
        bindSwitch(b.swData, prefs.stepData) { prefs.stepData = it }
        bindSwitch(b.swAirplane, prefs.stepAirplane) { prefs.stepAirplane = it }
        bindSwitch(b.swModem, prefs.stepModem) { prefs.stepModem = it }
        bindSwitch(b.swHeuristic, prefs.heuristicTrigger) { prefs.heuristicTrigger = it; checkNow() }
        bindSwitch(b.swNotify, prefs.notifyEvents) { prefs.notifyEvents = it }

        b.swEnabled.isChecked = prefs.enabled
        b.swEnabled.setOnCheckedChangeListener { _, on ->
            prefs.enabled = on
            if (on) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestRuntime()
                GuardService.start(this)
            } else {
                GuardService.stop(this)
            }
        }
    }

    private fun bindNumber(et: EditText, value: Int, range: IntRange, save: (Int) -> Unit) {
        et.setText(value.toString())
        et.doAfterTextChanged { s ->
            val v = s?.toString()?.toIntOrNull()
            if (v != null && v in range) save(v) else et.error = "${range.first}–${range.last}"
        }
    }

    private fun bindSwitch(sw: MaterialSwitch, value: Boolean, save: (Boolean) -> Unit) {
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, v -> save(v) }
    }

    // ---- Actions -----------------------------------------------------------------------

    private fun bindActions() {
        b.btnCheck.setOnClickListener { checkNow(log = true) }
        b.btnRecover.setOnClickListener {
            if (Engine.serviceRunning.value) GuardService.start(this, GuardService.ACTION_RECOVER)
            else Engine.scope.launch { Engine.recover(applicationContext, manual = true) }
        }
        b.btnShizuku.setOnClickListener {
            when {
                !ShizukuShell.isRunning() -> openShizuku()
                !ShizukuShell.hasPermission() -> ShizukuShell.requestPermission()
                else -> toast("Shizuku đã được cấp quyền")
            }
        }
        b.btnAutoGrant.setOnClickListener { autoGrant() }
        b.btnRuntime.setOnClickListener { requestRuntime() }
        b.btnBattery.setOnClickListener { requestBatteryExemption() }
        b.btnCopyLog.setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("log", LogStore.lines.value.joinToString("\n")))
            toast("Đã copy log")
        }
        b.btnClearLog.setOnClickListener { LogStore.clear() }
    }

    private fun checkNow(log: Boolean = false) {
        lifecycleScope.launch {
            val r = Engine.check(applicationContext)
            if (log) {
                LogStore.i("Kiểm tra thủ công: ${r.health} · ${r.summary}")
                r.items.forEach { LogStore.i("  [${it.status}] ${it.title}: ${it.detail}") }
            }
        }
    }

    private fun kickService() {
        if (Engine.serviceRunning.value) GuardService.start(this, GuardService.ACTION_KICK)
    }

    private fun requestRuntime() {
        runtimePerms.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS))
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Đã bỏ tối ưu pin")
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun openShizuku() {
        val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (i != null) startActivity(i) else toast("Chưa cài Shizuku")
    }

    /** One tap: grant everything the app can use through the shell uid. */
    private fun autoGrant() {
        if (!ShizukuShell.hasPermission()) {
            toast("Cần cấp quyền Shizuku trước"); return
        }
        lifecycleScope.launch {
            val pkg = packageName
            val cmds = listOf(
                "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
                "pm grant $pkg android.permission.READ_PHONE_STATE",
                "pm grant $pkg android.permission.POST_NOTIFICATIONS",
                "cmd deviceidle whitelist +$pkg",
                "appops set $pkg SCHEDULE_EXACT_ALARM allow",
            )
            withContext(Dispatchers.IO) {
                cmds.forEach { LogStore.i("$ $it → ${ShizukuShell.exec(it)}") }
            }
            refreshPerms(); checkNow()
            toast("Xong, xem nhật ký")
        }
    }

    // ---- Rendering ---------------------------------------------------------------------

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Engine.report.collect { render(it) } }
                launch {
                    Engine.phase.collect { ph ->
                        b.tvPhase.visibility = if (ph == null) View.GONE else View.VISIBLE
                        b.tvPhase.text = ph
                        b.btnRecover.isEnabled = ph == null
                    }
                }
                launch {
                    LogStore.lines.collect { lines ->
                        b.tvLog.text = if (lines.isEmpty()) "(trống)" else lines.takeLast(120).reversed().joinToString("\n")
                    }
                }
                launch { ShizukuShell.state.collect { refreshPerms() } }
            }
        }
    }

    private fun render(r: CheckReport?) {
        if (r == null) return
        val (label, color) = when (r.health) {
            Health.HEALTHY -> "HD Call OK" to R.color.ok
            Health.UNHEALTHY -> "Mất HD Call" to R.color.bad
            Health.NO_SERVICE -> "Không có dịch vụ" to R.color.warn
            Health.UNKNOWN -> "Chưa xác định" to R.color.muted
        }
        b.tvStatus.text = "SIM ${r.slot + 1} · $label"
        b.dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        b.tvSummary.text = r.summary + (r.blockReason?.let { "\n⛔ Không khắc phục: $it" } ?: "")
        b.tvTime.text = DateFormat.format("HH:mm:ss", Date(r.time))

        b.llChecks.removeAllViews()
        for (it in r.items) b.llChecks.addView(checkRow(it))
    }

    private fun checkRow(c: CheckItem): View {
        val (icon, color) = when (c.status) {
            St.PASS -> "✓" to R.color.ok
            St.FAIL -> "✕" to R.color.bad
            St.WARN -> "!" to R.color.warn
            St.UNKNOWN -> "?" to R.color.muted
            St.INFO -> "i" to R.color.info
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(TextView(this).apply {
            text = icon
            setTextColor(ContextCompat.getColor(context, color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = c.title + if (c.required) "" else "  ·  phụ"
                setTextColor(ContextCompat.getColor(context, R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                paint.isFakeBoldText = c.required
            })
            addView(TextView(context).apply {
                text = c.detail
                setTextColor(ContextCompat.getColor(context, R.color.text2))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        })
        return row
    }

    private fun refreshPerms() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val wss = Recovery.hasWriteSecureSettings(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        b.tvPerms.text = buildString {
            appendLine("${mark(ShizukuShell.hasPermission())} ${ShizukuShell.describe()}")
            appendLine("${mark(phone)} READ_PHONE_STATE")
            appendLine("${mark(notif)} Thông báo")
            appendLine("${mark(wss)} WRITE_SECURE_SETTINGS (máy bay khi không có Shizuku)")
            append("${mark(battery)} Bỏ tối ưu pin (chạy nền ổn định)")
        }
        b.btnShizuku.text = when {
            !ShizukuShell.isRunning() -> "Mở Shizuku (chưa chạy)"
            !ShizukuShell.hasPermission() -> "Cấp quyền Shizuku"
            else -> "Shizuku đã sẵn sàng"
        }
        b.btnAutoGrant.isEnabled = ShizukuShell.hasPermission()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
