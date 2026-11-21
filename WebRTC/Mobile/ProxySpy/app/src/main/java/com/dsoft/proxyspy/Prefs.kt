package com.dsoft.proxyspy

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("proxyspy", Context.MODE_PRIVATE)

    fun port(c: Context) = sp(c).getInt("port", 8080)
    fun setPort(c: Context, v: Int) = sp(c).edit().putInt("port", v.coerceIn(1024, 65535)).apply()

    /** Max bytes of a body kept per direction. */
    fun maxBody(c: Context) = sp(c).getInt("max_body", 1_000_000)

    // Client mode (rooted phone connecting through another phone running ProxySpy)
    fun clientAddr(c: Context): String = sp(c).getString("client_addr", "") ?: ""
    fun setClientAddr(c: Context, v: String) = sp(c).edit().putString("client_addr", v).apply()
    fun targetPkg(c: Context): String = sp(c).getString("target_pkg", "") ?: ""
    fun setTargetPkg(c: Context, v: String) = sp(c).edit().putString("target_pkg", v).apply()

    /** Ép đóng app đích sau khi cài CA. Mặc định TẮT để giữ phiên đăng nhập (vd 2FA Microsoft). */
    fun forceStop(c: Context): Boolean = sp(c).getBoolean("force_stop", false)
    fun setForceStop(c: Context, v: Boolean) = sp(c).edit().putBoolean("force_stop", v).apply()

    // ================= chế độ tự soi (hook / LSPosed) =================
    // Hub chạy không có Activity nào quanh nó, nên nhóm này dùng thẳng App.instance.

    private fun sp() = sp(App.instance)

    /** Các package người dùng đã tick ở màn "Chọn app". Rỗng = không bắt gì. */
    var targets: Set<String>
        get() = sp().getStringSet("targets", emptySet()) ?: emptySet()
        set(v) = sp().edit().putStringSet("targets", v).apply()

    /** Bắt mọi app mà LSPosed nạp module vào, thay vì danh sách đã chọn. */
    var allApps: Boolean
        get() = sp().getBoolean("all_apps", false)
        set(v) = sp().edit().putBoolean("all_apps", v).apply()

    fun isTarget(pkg: String) = allApps || targets.contains(pkg)

    var captureWs: Boolean
        get() = sp().getBoolean("cap_ws", true)
        set(v) = sp().edit().putBoolean("cap_ws", v).apply()

    /** Tầng thô (tee stream Conscrypt): bắt được traffic không qua OkHttp, chỉ đọc, chậm hơn. */
    var captureRaw: Boolean
        get() = sp().getBoolean("cap_raw", false)
        set(v) = sp().edit().putBoolean("cap_raw", v).apply()

    var brkReq: Boolean
        get() = sp().getBoolean("brk_req", false)
        set(v) = sp().edit().putBoolean("brk_req", v).apply()

    var brkRes: Boolean
        get() = sp().getBoolean("brk_res", false)
        set(v) = sp().edit().putBoolean("brk_res", v).apply()

    var brkWs: Boolean
        get() = sp().getBoolean("brk_ws", false)
        set(v) = sp().edit().putBoolean("brk_ws", v).apply()

    /** Chỉ dừng những URL chứa chuỗi này. Rỗng = mọi call. */
    var brkMatch: String
        get() = sp().getString("brk_match", "") ?: ""
        set(v) = sp().edit().putString("brk_match", v).apply()
}
