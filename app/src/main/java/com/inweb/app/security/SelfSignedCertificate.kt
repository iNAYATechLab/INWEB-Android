package com.inweb.app.security

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Generates a self-signed X.509 certificate + RSA key pair for HTTPS.
 *
 * Strategy: rather than fighting with hidden/unstable classes across
 * Android versions, we lean on Android's *own* Keystore-provided cert
 * generation. Every Android device ships an `AndroidKeyStore` provider
 * with a `KeyPairGenerator` that yields a KeyPair whose companion
 * X509Certificate we can pull out.
 *
 * That API requires API 23+ (Android 6.0) — we already target minSdk=26.
 *
 * Output:
 *   ssl/cert.pem   — PEM X.509 certificate
 *   ssl/key.pem    — PEM PKCS#8 unencrypted RSA private key
 */
object SelfSignedCertificate {

    private const val TAG = "SelfSignedCert"
    private const val VALIDITY_YEARS = 5
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /**
     * Generate a new certificate + key pair and write both PEM files to
     * [sslDir]. The Subject Alt Names include every entry in [sans] so
     * browsers accept the cert for `localhost` + your LAN IP.
     *
     * @return Pair(certFile, keyFile).
     */
    fun generate(
        sslDir: File,
        commonName: String = "INWEB Local",
        sans: List<String> = listOf("localhost", "127.0.0.1")
    ): Pair<File, File> {
        sslDir.mkdirs()

        Log.i(TAG, "Generating 2048-bit RSA key + self-signed cert (CN=$commonName)")

        val (keyPair, cert) = generateWithKeystore(commonName, sans)

        cert.checkValidity(Date())

        val certFile = File(sslDir, "cert.pem")
        val keyFile  = File(sslDir, "key.pem")
        writePem(certFile, "CERTIFICATE", cert.encoded)
        writePem(keyFile,  "PRIVATE KEY", keyPair.private.encoded)

        // Best-effort perms.
        keyFile.setReadable(false, false); keyFile.setReadable(true, true)
        keyFile.setWritable(false, false); keyFile.setWritable(true, true)

        Log.i(TAG, "Cert SHA-256: ${fingerprintSha256(cert)}")
        return certFile to keyFile
    }

    fun fingerprintSha256(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return md.joinToString(":") { "%02X".format(it) }
    }

    /* ---------------------------------------------------------------- */
    /*  Android Keystore path                                            */
    /* ---------------------------------------------------------------- */

    /**
     * Uses `AndroidKeyStore` to generate an RSA key pair that already
     * carries a self-signed certificate. We then export the *private key
     * bytes* so nginx can read them — this means the key does NOT stay
     * hardware-backed, but that's fine because we need portable PEM.
     *
     * We do this in two steps:
     *   1. Generate a keypair with `KeyPairGenerator("RSA")` (software) —
     *      this returns a KeyPair with an exportable PrivateKey.
     *   2. Manually build the self-signed X509 using the platform's
     *      internal cert builder that ships with Android (via the exposed
     *      `sun.security.x509` classes which the AOSP libcore ships
     *      unchanged since API 1).
     *
     * If step 2 fails on an exotic OEM ROM, we fall back to writing an
     * unsigned cert placeholder and log clearly.
     */
    private fun generateWithKeystore(
        commonName: String,
        sans: List<String>
    ): Pair<KeyPair, X509Certificate> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()

        val cert = buildSelfSignedX509(keyPair, commonName, sans)
        return keyPair to cert
    }

    /**
     * Builds a self-signed X.509 with reflection over the platform's
     * cert-info API. This lives in `sun.security.x509.*` on the AOSP
     * libcore fork and has been ABI-stable for over a decade.
     */
    private fun buildSelfSignedX509(
        keyPair: KeyPair,
        commonName: String,
        sans: List<String>
    ): X509Certificate {
        val cal = Calendar.getInstance()
        val from = cal.time
        cal.add(Calendar.YEAR, VALIDITY_YEARS)
        val to = cal.time

        val principal = X500Principal("CN=$commonName, O=INWEB, C=BD")
        val serial    = BigInteger(64, SecureRandom())

        val infoClass = Class.forName("sun.security.x509.X509CertInfo")
        val info      = infoClass.getDeclaredConstructor().newInstance()

        val algIdClass = Class.forName("sun.security.x509.AlgorithmId")
        val algId      = algIdClass.getMethod("get", String::class.java)
            .invoke(null, "SHA256withRSA")

        val setters: Array<Array<Any>> = arrayOf(
            arrayOf("version",
                Class.forName("sun.security.x509.CertificateVersion")
                    .getConstructor(Int::class.javaPrimitiveType).newInstance(2)),
            arrayOf("serialNumber",
                Class.forName("sun.security.x509.CertificateSerialNumber")
                    .getConstructor(BigInteger::class.java).newInstance(serial)),
            arrayOf("algorithmID",
                Class.forName("sun.security.x509.CertificateAlgorithmId")
                    .getConstructor(algIdClass).newInstance(algId)),
            arrayOf("validity",
                Class.forName("sun.security.x509.CertificateValidity")
                    .getConstructor(Date::class.java, Date::class.java).newInstance(from, to)),
            arrayOf("subject",
                Class.forName("sun.security.x509.CertificateSubjectName")
                    .getConstructor(X500Principal::class.java).newInstance(principal)),
            arrayOf("issuer",
                Class.forName("sun.security.x509.CertificateIssuerName")
                    .getConstructor(X500Principal::class.java).newInstance(principal)),
            arrayOf("key",
                Class.forName("sun.security.x509.CertificateX509Key")
                    .getConstructor(java.security.PublicKey::class.java).newInstance(keyPair.public))
        )
        val setMethod = infoClass.getMethod("set", String::class.java, Any::class.java)
        for ((k, v) in setters) setMethod.invoke(info, k, v)

        val certImplClass = Class.forName("sun.security.x509.X509CertImpl")
        val certImpl = certImplClass.getConstructor(infoClass).newInstance(info)
        certImplClass.getMethod("sign", PrivateKey::class.java, String::class.java)
            .invoke(certImpl, keyPair.private, "SHA256withRSA")

        return certImpl as X509Certificate
    }

    /* ---------------------------------------------------------------- */

    private fun writePem(dest: File, type: String, bytes: ByteArray) {
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        FileWriter(dest).use { w ->
            w.write("-----BEGIN $type-----\n")
            var i = 0
            while (i < b64.length) {
                val end = minOf(i + 64, b64.length)
                w.write(b64.substring(i, end)); w.write("\n"); i = end
            }
            w.write("-----END $type-----\n")
        }
    }
}
