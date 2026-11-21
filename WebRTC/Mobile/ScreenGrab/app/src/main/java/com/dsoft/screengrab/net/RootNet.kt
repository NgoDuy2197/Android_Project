package com.dsoft.screengrab.net

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Root helpers for the sniffer: install ScreenGrab's MITM CA as a system-trusted cert (bind-mount
 * into the live + zygote namespaces, Android 14 apex-aware) and set/clear the global HTTP proxy so
 * every app routes through our local proxy. All via `su`. Ported from ProxySpy's RootCa.
 */
object RootNet {

    private const val APEX = "/apex/com.android.conscrypt/cacerts"
    private const val SYS = "/system/etc/security/cacerts"

    private val unmountFn = buildString {
        append("un() {\n")
        append("  t=\$1; i=0\n")
        append("  while grep -q \" \$t \" /proc/self/mountinfo 2>/dev/null && [ \$i -lt 20 ]; do umount -l \"\$t\" 2>/dev/null || break; i=\$((i+1)); done\n")
        append("  for pid in \$(pidof zygote64 zygote webview_zygote app_zygote 2>/dev/null); do\n")
        append("    j=0\n")
        append("    while nsenter --mount=/proc/\$pid/ns/mnt -- grep -q \" \$t \" /proc/self/mountinfo 2>/dev/null && [ \$j -lt 20 ]; do nsenter --mount=/proc/\$pid/ns/mnt -- umount -l \"\$t\" 2>/dev/null || break; j=\$((j+1)); done\n")
        append("  done\n")
        append("}\n")
    }

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

    /** Install [caDer] as a system-trusted CA (lost on reboot). Idempotent + safe. */
    fun installSystemCa(ctx: Context, caDer: ByteArray, targetPkg: String = ""): Pair<Boolean, String> {
        return try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(caDer.inputStream()) as X509Certificate
            val hash = Mitm.subjectHashOld(cert)
            val f = File(ctx.cacheDir, "$hash.0")
            f.writeText(derToPem(caDer))
            val d = "/data/local/tmp/sg-ca-bundle"
            val script = buildString {
                append("A=$APEX\n")
                append("S=$SYS\n")
                append("D=$d\n")
                append(unmountFn)
                append("un \$A; un \$S\n")
                append("N=\$(ls \$A 2>/dev/null | wc -l); echo CA_GOC=\$N\n")
                append("if [ \"\$N\" -lt 100 ]; then echo ABORT_LOW_CA; exit 1; fi\n")
                append("rm -rf \$D; mkdir -p \$D\n")
                append("cp \$A/* \$D/ 2>/dev/null\n")
                append("cp \$S/* \$D/ 2>/dev/null\n")
                append("cp '${f.absolutePath}' \$D/$hash.0\n")
                append("chown root:root \$D/* 2>/dev/null; chmod 644 \$D/*\n")
                append("chcon u:object_r:system_security_cacerts_file:s0 \$D/* 2>/dev/null\n")
                append("M=\$(ls \$D | wc -l); echo BUNDLE=\$M\n")
                append("if [ \"\$M\" -le \"\$N\" ]; then echo ABORT_NO_GROW; exit 1; fi\n")
                append("mount -o bind \$D \$A 2>/dev/null\n")
                append("mount -o bind \$D \$S 2>/dev/null\n")
                append("for pid in \$(pidof zygote64 zygote webview_zygote app_zygote 2>/dev/null); do\n")
                append("  nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$D \$A 2>/dev/null\n")
                append("  nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$D \$S 2>/dev/null\n")
                append("done\n")
                if (targetPkg.isNotBlank()) {
                    append("for pid in \$(pidof $targetPkg 2>/dev/null); do\n")
                    append("  nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$D \$A 2>/dev/null && echo INJECT_$targetPkg=\$pid\n")
                    append("  nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$D \$S 2>/dev/null\n")
                    append("done\n")
                }
                append("ls \$A/$hash.0\n")
            }
            val (_, out) = runSu(script)
            val ok = out.contains("$hash.0") && !out.contains("No such file") &&
                !out.contains("ABORT_LOW_CA") && !out.contains("ABORT_NO_GROW")
            ok to out
        } catch (e: Exception) {
            false to (e.message ?: "lỗi cài CA")
        }
    }

    fun setProxy(addr: String) = runSu("settings put global http_proxy $addr")
    fun clearProxy() = runSu("settings put global http_proxy :0")

    /** Clear the proxy AND unmount the system-CA bind-mounts (no reboot). */
    fun fullRestore(): Pair<Int, String> = runSu(buildString {
        append("A=$APEX\n")
        append("S=$SYS\n")
        append(unmountFn)
        append("settings put global http_proxy :0\n")
        append("un \$A; un \$S\n")
        append("echo 'proxy='; settings get global http_proxy\n")
    })

    private fun derToPem(der: ByteArray): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val sb = StringBuilder("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < b64.length) { sb.append(b64, i, minOf(i + 64, b64.length)).append('\n'); i += 64 }
        return sb.append("-----END CERTIFICATE-----\n").toString()
    }
}
