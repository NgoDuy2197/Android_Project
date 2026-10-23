package com.dsoft.proxyspy

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The man-in-the-middle certificate authority. Generates (once, persisted) a root CA, then
 * mints a leaf certificate per host on demand — all signed with BouncyCastle so we present a
 * trusted-looking cert to the client. The client must install [caDer] to trust it.
 */
object CertManager {
    private val bc = BouncyCastleProvider()
    private val rnd = SecureRandom()
    private const val PASS = "proxyspy"

    private lateinit var caCert: X509Certificate
    private lateinit var caKey: PrivateKey
    private lateinit var leafKey: KeyPair                 // one key reused for every leaf (fast)
    private val ctxCache = ConcurrentHashMap<String, SSLContext>()

    @Synchronized
    fun init(ctx: Context) {
        if (::caCert.isInitialized) return
        val p12 = File(File(ctx.filesDir, "ca").apply { mkdirs() }, "ca.p12")
        val ks = KeyStore.getInstance("PKCS12")
        if (p12.exists()) {
            p12.inputStream().use { ks.load(it, PASS.toCharArray()) }
            caKey = ks.getKey("ca", PASS.toCharArray()) as PrivateKey
            caCert = ks.getCertificate("ca") as X509Certificate
        } else {
            val kp = genRsa()
            caKey = kp.private
            caCert = buildCaCert(kp)
            ks.load(null, null)
            ks.setKeyEntry("ca", caKey, PASS.toCharArray(), arrayOf(caCert))
            p12.outputStream().use { ks.store(it, PASS.toCharArray()) }
        }
        leafKey = genRsa()
    }

    private fun genRsa(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun serial() = BigInteger(64, rnd).abs().add(BigInteger.ONE)

    private fun buildCaCert(kp: KeyPair): X509Certificate {
        val name = X500Name("CN=ProxySpy Root CA,O=ProxySpy,OU=Dsoft")
        val now = System.currentTimeMillis()
        val ext = JcaX509ExtensionUtils()
        val b = JcaX509v3CertificateBuilder(
            name, serial(), Date(now - DAY), Date(now + 3650L * DAY), name, kp.public,
        )
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        b.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature))
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.public))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(kp.private)
        return JcaX509CertificateConverter().setProvider(bc).getCertificate(b.build(signer))
    }

    private fun buildLeafCert(host: String): X509Certificate {
        val now = System.currentTimeMillis()
        val issuer = X500Name.getInstance(caCert.subjectX500Principal.encoded)
        val ext = JcaX509ExtensionUtils()
        val b = JcaX509v3CertificateBuilder(
            issuer, serial(), Date(now - DAY), Date(now + 800L * DAY), X500Name("CN=$host"), leafKey.public,
        )
        b.addExtension(Extension.basicConstraints, false, BasicConstraints(false))
        b.addExtension(Extension.keyUsage, false,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        b.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        val gn = if (isIp(host)) GeneralName(GeneralName.iPAddress, host) else GeneralName(GeneralName.dNSName, host)
        b.addExtension(Extension.subjectAlternativeName, false, GeneralNames(gn))
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(caKey)
        return JcaX509CertificateConverter().setProvider(bc).getCertificate(b.build(signer))
    }

    /** SSLContext that serves a leaf cert for [host] (server side of the MITM). Cached per host. */
    fun serverContext(host: String): SSLContext = ctxCache.getOrPut(host) {
        val leaf = buildLeafCert(host)
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("leaf", leafKey.private, PASS.toCharArray(), arrayOf(leaf, caCert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASS.toCharArray())
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    fun caDer(): ByteArray = caCert.encoded

    fun caPem(): String {
        val b64 = Base64.encodeToString(caCert.encoded, Base64.NO_WRAP)
        val sb = StringBuilder("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < b64.length) { sb.append(b64, i, minOf(i + 64, b64.length)).append('\n'); i += 64 }
        return sb.append("-----END CERTIFICATE-----\n").toString()
    }

    private fun isIp(h: String) = h.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

    /** OpenSSL's old subject hash (MD5 of the subject DER, first 4 bytes little-endian) — the
     *  filename Android uses in its system CA store. Works for any certificate. */
    fun subjectHashOld(cert: X509Certificate): String {
        val der = cert.subjectX500Principal.encoded
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(der)
        val h = (md5[0].toInt() and 0xFF).toLong() or
            ((md5[1].toInt() and 0xFF).toLong() shl 8) or
            ((md5[2].toInt() and 0xFF).toLong() shl 16) or
            ((md5[3].toInt() and 0xFF).toLong() shl 24)
        return String.format("%08x", h)
    }

    private const val DAY = 24L * 3600 * 1000
}
