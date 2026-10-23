package com.dsoft.proxyspy

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * On-device root helper: installs another ProxySpy's CA as a SYSTEM certificate (Android 14+ apex
 * aware, via bind-mount into the zygote namespace), and sets/clears the global HTTP proxy — so a
 * rooted client phone needs no PC. All actions go through `su`, prompting Magisk the first time.
 */
object RootCa {

    fun runSu(script: String): Pair<Int, String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("su"))
        p.outputStream.use { it.write((script + "\nexit\n").toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        val code = p.waitFor()
        code to (out + err).trim()
    } catch (e: Exception) {
        -1 to (e.message ?: "su không chạy được")
    }

    fun hasRoot(): Boolean = runSu("id").second.contains("uid=0")

    /** Installs [caDer] as a system-trusted CA on this rooted device (lost on reboot). */
    fun installSystemCa(ctx: Context, caDer: ByteArray): Pair<Boolean, String> {
        return try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(caDer.inputStream()) as X509Certificate
            val hash = CertManager.subjectHashOld(cert)
            val f = File(ctx.cacheDir, "$hash.0")
            f.writeText(derToPem(caDer))
            val d = "/data/local/tmp/ca-bundle"
            val script = buildString {
                append("D=$d\n")
                append("rm -rf \$D; mkdir -p \$D\n")
                append("cp /apex/com.android.conscrypt/cacerts/* \$D/ 2>/dev/null\n")
                append("cp /system/etc/security/cacerts/* \$D/ 2>/dev/null\n")
                append("cp '${f.absolutePath}' \$D/$hash.0\n")
                append("chown root:root \$D/* 2>/dev/null; chmod 644 \$D/*\n")
                append("chcon u:object_r:system_security_cacerts_file:s0 \$D/* 2>/dev/null\n")
                append("mount -o bind \$D /apex/com.android.conscrypt/cacerts 2>/dev/null\n")
                append("mount -o bind \$D /system/etc/security/cacerts 2>/dev/null\n")
                append("for pid in \$(pidof zygote64 zygote 2>/dev/null); do\n")
                append("  nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$D /apex/com.android.conscrypt/cacerts 2>/dev/null\n")
                append("  nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$D /system/etc/security/cacerts 2>/dev/null\n")
                append("done\n")
                append("ls /apex/com.android.conscrypt/cacerts/$hash.0\n")
            }
            val (_, out) = runSu(script)
            (out.contains("$hash.0") && !out.contains("No such file")) to out
        } catch (e: Exception) {
            false to (e.message ?: "lỗi cài CA")
        }
    }

    fun setProxy(addr: String) = runSu("settings put global http_proxy $addr")
    fun clearProxy() = runSu("settings put global http_proxy :0")

    /** Back to normal: clear the proxy AND unmount the system-CA bind-mounts (no reboot needed). */
    fun fullRestore(): Pair<Int, String> = runSu(buildString {
        append("settings put global http_proxy :0\n")
        append("umount /apex/com.android.conscrypt/cacerts 2>/dev/null\n")
        append("umount /system/etc/security/cacerts 2>/dev/null\n")
        append("for pid in \$(pidof zygote64 zygote 2>/dev/null); do\n")
        append("  nsenter --mount=/proc/\$pid/ns/mnt -- umount /apex/com.android.conscrypt/cacerts 2>/dev/null\n")
        append("  nsenter --mount=/proc/\$pid/ns/mnt -- umount /system/etc/security/cacerts 2>/dev/null\n")
        append("done\n")
        append("echo 'proxy='; settings get global http_proxy\n")
    })

    fun forceStop(pkg: String) = runSu("am force-stop $pkg")

    private fun derToPem(der: ByteArray): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val sb = StringBuilder("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < b64.length) { sb.append(b64, i, minOf(i + 64, b64.length)).append('\n'); i += 64 }
        return sb.append("-----END CERTIFICATE-----\n").toString()
    }
}
