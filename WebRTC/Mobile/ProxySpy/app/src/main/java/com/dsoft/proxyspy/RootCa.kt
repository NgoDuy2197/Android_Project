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
 *
 * AN TOÀN / IDEMPOTENT:
 *  - Gỡ overlay CŨ trước khi đọc kho CA gốc  → không bao giờ copy nhầm từ bundle đã bị đè.
 *  - Chỉ mount khi kho CA gốc còn đầy (>=100) và bundle mới tăng số lượng → không thể nuốt store.
 *  - Chạy lại nhiều lần / sau khi đã mount sẵn đều an toàn.
 */
object RootCa {

    private const val APEX = "/apex/com.android.conscrypt/cacerts"
    private const val SYS = "/system/etc/security/cacerts"

    /** sh function that fully unmounts an overlay on one target (global ns + every zygote ns). */
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

    /**
     * Installs [caDer] as a system-trusted CA on this rooted device (lost on reboot).
     * Idempotent + safe: aborts (without mounting) if the real CA store is already broken,
     * so it can never wipe the ~140 built-in roots like the old version did.
     */
    fun installSystemCa(ctx: Context, caDer: ByteArray, targetPkg: String = ""): Pair<Boolean, String> {
        return try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(caDer.inputStream()) as X509Certificate
            val hash = CertManager.subjectHashOld(cert)
            val f = File(ctx.cacheDir, "$hash.0")
            f.writeText(derToPem(caDer))
            val d = "/data/local/tmp/ca-bundle"
            val script = buildString {
                append("A=$APEX\n")
                append("S=$SYS\n")
                append("D=$d\n")
                append(unmountFn)
                // 1) gỡ overlay cũ → đọc được KHO CA GỐC thật (tránh bug copy từ bundle cũ)
                append("un \$A; un \$S\n")
                // 2) kho CA gốc phải còn đầy, nếu không thì DỪNG (không mount để khỏi phá thêm)
                append("N=\$(ls \$A 2>/dev/null | wc -l); echo CA_GOC=\$N\n")
                append("if [ \"\$N\" -lt 100 ]; then echo ABORT_LOW_CA; exit 1; fi\n")
                // 3) gom CA gốc + cert ProxySpy
                append("rm -rf \$D; mkdir -p \$D\n")
                append("cp \$A/* \$D/ 2>/dev/null\n")
                append("cp \$S/* \$D/ 2>/dev/null\n")
                append("cp '${f.absolutePath}' \$D/$hash.0\n")
                append("chown root:root \$D/* 2>/dev/null; chmod 644 \$D/*\n")
                append("chcon u:object_r:system_security_cacerts_file:s0 \$D/* 2>/dev/null\n")
                append("M=\$(ls \$D | wc -l); echo BUNDLE=\$M\n")
                append("if [ \"\$M\" -le \"\$N\" ]; then echo ABORT_NO_GROW; exit 1; fi\n")
                // 4) bind-mount (global ns + zygote ns)
                append("mount -o bind \$D \$A 2>/dev/null\n")
                append("mount -o bind \$D \$S 2>/dev/null\n")
                append("for pid in \$(pidof zygote64 zygote webview_zygote app_zygote 2>/dev/null); do\n")
                append("  nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$D \$A 2>/dev/null\n")
                append("  nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$D \$S 2>/dev/null\n")
                append("done\n")
                // 5) inject thẳng vào app đích ĐANG CHẠY → có cơ hội nhận CA mà KHÔNG cần đóng app
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

    /** Back to normal: clear the proxy AND fully unmount the system-CA bind-mounts (no reboot). */
    fun fullRestore(): Pair<Int, String> = runSu(buildString {
        append("A=$APEX\n")
        append("S=$SYS\n")
        append(unmountFn)
        append("settings put global http_proxy :0\n")
        append("un \$A; un \$S\n")
        append("echo CA_GOC=\$(ls \$A 2>/dev/null | wc -l)\n")
        append("echo 'proxy='; settings get global http_proxy\n")
    })

    fun forceStop(pkg: String) = runSu("am force-stop $pkg")

    /**
     * Cài CA BỀN bằng Magisk module: đặt CA vào kho hệ thống TỪ LÚC BOOT (post-fs-data, global
     * namespace, trước khi zygote khởi động) → MỌI tiến trình kể cả WebView/mini-app nạp đủ CA ngay
     * từ đầu → tin cert. Cần REBOOT 1 lần. Đây là cách duy nhất ăn với WebView (cache CA lúc start).
     */
    fun installMagiskModule(ctx: Context, caDer: ByteArray): Pair<Boolean, String> {
        return try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(caDer.inputStream()) as X509Certificate
            val hash = CertManager.subjectHashOld(cert)
            val f = File(ctx.cacheDir, "$hash.0")
            f.writeText(derToPem(caDer))
            val script = buildString {
                append("A=$APEX\nS=$SYS\nMOD=/data/adb/modules/proxyspy_ca\n")
                append(unmountFn)
                append("un \$A; un \$S\n")                       // bỏ overlay lúc-chạy để đọc kho gốc
                append("N=\$(ls \$A 2>/dev/null | wc -l); echo CA_GOC=\$N\n")
                append("if [ \"\$N\" -lt 100 ]; then echo ABORT_LOW_CA; exit 1; fi\n")
                append("rm -rf \$MOD; mkdir -p \$MOD/cacerts\n")
                append("cp \$A/* \$MOD/cacerts/ 2>/dev/null\n")
                append("cp \$S/* \$MOD/cacerts/ 2>/dev/null\n")
                append("cp '${f.absolutePath}' \$MOD/cacerts/$hash.0\n")
                append("chown -R 0:0 \$MOD/cacerts; chmod 644 \$MOD/cacerts/*\n")
                append("chcon u:object_r:system_security_cacerts_file:s0 \$MOD/cacerts/* 2>/dev/null\n")
                append("M=\$(ls \$MOD/cacerts | wc -l); echo BUNDLE=\$M\n")
                append("if [ \"\$M\" -le \"\$N\" ]; then echo ABORT_NO_GROW; exit 1; fi\n")
                // module.prop
                append("cat > \$MOD/module.prop <<'EOP'\n")
                append("id=proxyspy_ca\n")
                append("name=ProxySpy System CA\n")
                append("version=v1\nversionCode=1\nauthor=ProxySpy\n")
                append("description=Tin CA cua ProxySpy toan he thong (ke ca WebView) tu luc boot.\n")
                append("EOP\n")
                // post-fs-data.sh: bind-mount ở global ns trước khi zygote start
                append("cat > \$MOD/post-fs-data.sh <<'EOS'\n")
                append("MODDIR=\${0%/*}\n")
                append("mount -o bind \$MODDIR/cacerts /apex/com.android.conscrypt/cacerts 2>/dev/null\n")
                append("mount -o bind \$MODDIR/cacerts /system/etc/security/cacerts 2>/dev/null\n")
                append("EOS\n")
                append("chmod 755 \$MOD/post-fs-data.sh\n")
                append("rm -f \$MOD/remove \$MOD/disable\n")
                append("echo MODULE_OK\n")
            }
            val (_, out) = runSu(script)
            val ok = out.contains("MODULE_OK") && !out.contains("ABORT_LOW_CA") && !out.contains("ABORT_NO_GROW")
            ok to out
        } catch (e: Exception) {
            false to (e.message ?: "lỗi cài module")
        }
    }

    /** Gỡ Magisk module CA (có hiệu lực sau khi reboot). */
    fun removeMagiskModule(): Pair<Int, String> =
        runSu("touch /data/adb/modules/proxyspy_ca/remove 2>/dev/null; echo 'Đã đánh dấu gỡ module — reboot để hoàn tất.'")

    private fun derToPem(der: ByteArray): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val sb = StringBuilder("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < b64.length) { sb.append(b64, i, minOf(i + 64, b64.length)).append('\n'); i += 64 }
        return sb.append("-----END CERTIFICATE-----\n").toString()
    }
}
