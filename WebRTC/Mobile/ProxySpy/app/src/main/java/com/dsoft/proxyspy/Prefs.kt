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
}
