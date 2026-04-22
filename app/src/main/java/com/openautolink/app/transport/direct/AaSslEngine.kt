package com.openautolink.app.transport.direct

import com.openautolink.app.diagnostics.OalLog
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * AA SSL/TLS handshake engine using Java SSLEngine.
 *
 * The AA protocol wraps TLS records inside AAP framing (channel 0, type 3).
 * This class handles the handshake by reading/writing AAP-framed TLS records,
 * then provides encrypt/decrypt for the post-handshake data stream.
 *
 * Uses Conscrypt if available (for TLS session resumption), falls back to
 * the platform TLS provider.
 *
 * The SSL certificate is a self-signed cert embedded in the app. The phone
 * doesn't verify it (AA uses SSL_VERIFY_NONE on the phone side).
 */
class AaSslEngine {

    companion object {
        private const val TAG = "AaSslEngine"
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L

        // Singleton SSLContext — survives across AaSslEngine instances for TLS session
        // resumption. JSSE's ClientSessionContext caches sessions by (host, port);
        // reusing the same SSLContext + synthetic ("android-auto", 5277) key means
        // reconnects can resume the previous TLS session, saving 1-3 round trips.
        @Volatile
        private var sharedSslContext: SSLContext? = null
    }

    private lateinit var sslEngine: SSLEngine
    private lateinit var netOutBuffer: ByteBuffer   // TLS records to send
    private lateinit var appInBuffer: ByteBuffer     // Decrypted app data

    private val sslContext: SSLContext by lazy {
        synchronized(AaSslEngine::class.java) {
            sharedSslContext ?: createSslContextInternal().also { sharedSslContext = it }
        }
    }
    private val codec = AaWireCodec()

    /**
     * Perform the TLS handshake over the AA protocol.
     * Reads/writes AAP-framed TLS records on the given streams.
     * @return true if handshake succeeded
     */
    fun performHandshake(input: InputStream, output: OutputStream): Boolean {
        sslEngine = sslContext.createSSLEngine("android-auto", 5277).apply {
            useClientMode = true
        }
        val session = sslEngine.session
        netOutBuffer = ByteBuffer.allocateDirect(session.packetBufferSize)
        appInBuffer = ByteBuffer.allocateDirect(session.applicationBufferSize + 64)

        sslEngine.beginHandshake()

        var pendingTlsData = ByteArray(0)
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS

        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                OalLog.e(TAG, "SSL handshake timed out")
                return false
            }

            when (sslEngine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    OalLog.i(TAG, "SSL handshake complete")
                    netOutBuffer.clear()
                    appInBuffer.clear()
                    return true
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    // SSLEngine wants to produce TLS records → wrap and send
                    netOutBuffer.clear()
                    val result = sslEngine.wrap(arrayOf<ByteBuffer>(), netOutBuffer)
                    runDelegatedTasks()

                    if (result.status != SSLEngineResult.Status.OK &&
                        result.status != SSLEngineResult.Status.CLOSED) {
                        OalLog.e(TAG, "SSL wrap failed: ${result.status}")
                        return false
                    }

                    // Send TLS record wrapped in AAP frame (channel 0, type 3 = SSL_HANDSHAKE)
                    val tlsBytes = ByteArray(result.bytesProduced())
                    netOutBuffer.flip()
                    netOutBuffer.get(tlsBytes)
                    val msg = AaMessage.raw(AaChannel.CONTROL, AaMsgType.SSL_HANDSHAKE, tlsBytes)
                    codec.encode(msg, output)
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // SSLEngine needs to consume TLS records
                    if (pendingTlsData.isEmpty()) {
                        // Read an AAP message containing a TLS record
                        val msg = codec.decode(input)
                        if (msg.channel != AaChannel.CONTROL || msg.type != AaMsgType.SSL_HANDSHAKE) {
                            OalLog.e(TAG, "Expected SSL_HANDSHAKE, got ${msg}")
                            return false
                        }
                        pendingTlsData = if (msg.payloadOffset == 0 && msg.payloadLength == msg.payload.size) {
                            msg.payload
                        } else {
                            msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                        }
                    }

                    appInBuffer.clear()
                    val data = ByteBuffer.wrap(pendingTlsData)
                    val result = sslEngine.unwrap(data, appInBuffer)
                    runDelegatedTasks()

                    when (result.status) {
                        SSLEngineResult.Status.OK -> {
                            pendingTlsData = if (data.hasRemaining()) {
                                ByteArray(data.remaining()).also { data.get(it) }
                            } else ByteArray(0)
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            // Need more TLS data — read another AAP message and append
                            val msg = codec.decode(input)
                            if (msg.channel != AaChannel.CONTROL || msg.type != AaMsgType.SSL_HANDSHAKE) {
                                OalLog.e(TAG, "Expected SSL_HANDSHAKE during underflow, got ${msg}")
                                return false
                            }
                            val extra = if (msg.payloadOffset == 0 && msg.payloadLength == msg.payload.size) {
                                msg.payload
                            } else {
                                msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                            }
                            pendingTlsData += extra
                        }
                        else -> {
                            OalLog.e(TAG, "SSL unwrap failed: ${result.status}")
                            return false
                        }
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                }

                else -> {
                    OalLog.e(TAG, "Unexpected handshake status: ${sslEngine.handshakeStatus}")
                    return false
                }
            }
        }
    }

    /**
     * Encrypt plaintext data (AA message payload) into a TLS record.
     * @return encrypted bytes, or null on failure
     */
    fun encrypt(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray? {
        return try {
            netOutBuffer.clear()
            val result = sslEngine.wrap(ByteBuffer.wrap(data, offset, length), netOutBuffer)
            runDelegatedTasks()
            if (result.status != SSLEngineResult.Status.OK) {
                OalLog.e(TAG, "encrypt wrap failed: ${result.status}")
                return null
            }
            val out = ByteArray(result.bytesProduced())
            netOutBuffer.flip()
            netOutBuffer.get(out)
            out
        } catch (e: Exception) {
            OalLog.e(TAG, "encrypt error: ${e.message}")
            null
        }
    }

    /**
     * Decrypt a TLS record into plaintext AA message payload.
     * @return decrypted bytes, or null on failure
     */
    fun decrypt(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray? {
        return try {
            appInBuffer.clear()
            val result = sslEngine.unwrap(ByteBuffer.wrap(data, offset, length), appInBuffer)
            runDelegatedTasks()
            if (result.status != SSLEngineResult.Status.OK) {
                OalLog.e(TAG, "decrypt unwrap failed: ${result.status}")
                return null
            }
            val out = ByteArray(result.bytesProduced())
            appInBuffer.flip()
            appInBuffer.get(out)
            out
        } catch (e: Exception) {
            OalLog.e(TAG, "decrypt error: ${e.message}")
            null
        }
    }

    fun release() {
        try {
            if (::sslEngine.isInitialized) {
                sslEngine.closeOutbound()
            }
        } catch (_: Exception) {}
    }

    private fun runDelegatedTasks() {
        while (sslEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            sslEngine.delegatedTask?.run() ?: break
        }
    }

    private fun createSslContextInternal(): SSLContext {
        // Use a self-signed certificate. The phone doesn't verify it
        // (AA protocol uses SSL_VERIFY_NONE on the client/phone side).
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)

        // Check if we already have a key, if not generate one
        if (!ks.containsAlias("oal_headunit")) {
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                "oal_headunit",
                android.security.keystore.KeyProperties.PURPOSE_SIGN or
                    android.security.keystore.KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .setKeySize(2048)
                .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=OpenAutoLink"))
                .setCertificateNotBefore(java.util.Date())
                .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000))
                .build()

            val kpg = java.security.KeyPairGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore"
            )
            kpg.initialize(spec)
            kpg.generateKeyPair()
            OalLog.i(TAG, "Generated self-signed cert for AA SSL")
        }

        // AndroidKeyStore keys can't be exported to a regular KeyManagerFactory.
        // Instead, use a custom KeyManager that wraps the AndroidKeyStore entry.
        val privateKey = ks.getKey("oal_headunit", null) as java.security.PrivateKey
        val cert = ks.getCertificate("oal_headunit") as X509Certificate

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val tempKs = KeyStore.getInstance(KeyStore.getDefaultType())
        tempKs.load(null, null)
        tempKs.setKeyEntry("headunit", privateKey, charArrayOf(), arrayOf(cert))
        kmf.init(tempKs, charArrayOf())

        // Trust all certificates (phone's cert isn't verified by HU)
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        // Try Conscrypt first for TLS session resumption support
        val ctx = try {
            SSLContext.getInstance("TLS", "Conscrypt")
        } catch (_: Exception) {
            SSLContext.getInstance("TLS")
        }
        ctx.init(kmf.keyManagers, trustAll, null)
        return ctx
    }
}
