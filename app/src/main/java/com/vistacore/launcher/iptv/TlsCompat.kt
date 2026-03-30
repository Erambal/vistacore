package com.vistacore.launcher.iptv

import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * TLS compatibility for OkHttp clients across Google TV, FireStick,
 * and other Android TV devices.
 *
 * Two modes:
 *  - [apply]       — enables all TLS versions/ciphers but still validates
 *                    server certificates. Use for first-party services
 *                    (GitHub, relay server, etc.).
 *  - [applyTrustAll] — additionally trusts ALL server certificates.
 *                    Use for user-configured IPTV servers, which commonly
 *                    have self-signed or poorly-chained certificates that
 *                    FireStick's limited CA store rejects.
 */
object TlsCompat {

    private const val TAG = "TlsCompat"

    /**
     * Standard TLS compat: enables all protocol versions and cipher suites
     * but still validates server certificates normally.
     */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (!needsCompat()) return builder

        try {
            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null as KeyStore?)
            val trustManagers = trustManagerFactory.trustManagers
            val trustManager = trustManagers[0] as X509TrustManager

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)

            val socketFactory = AllTlsSocketFactory(sslContext.socketFactory)
            builder.sslSocketFactory(socketFactory, trustManager)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install custom TLS socket factory", e)
        }

        return applyConnectionSpecs(builder)
    }

    /**
     * Permissive TLS: trusts ALL server certificates unconditionally.
     * Intended for user-configured IPTV/Xtream servers which often use
     * self-signed, expired, or improperly-chained certificates.
     */
    fun applyTrustAll(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        try {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)

            if (needsCompat()) {
                val socketFactory = AllTlsSocketFactory(sslContext.socketFactory)
                builder.sslSocketFactory(socketFactory, trustManager)
            } else {
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            }
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install trust-all TLS", e)
        }

        return applyConnectionSpecs(builder)
    }

    private fun needsCompat(): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        return manufacturer == "amazon" || android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N
    }

    private fun applyConnectionSpecs(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        builder.connectionSpecs(
            listOf(
                ConnectionSpec.MODERN_TLS,
                ConnectionSpec.COMPATIBLE_TLS,
                ConnectionSpec.CLEARTEXT
            )
        )
        return builder
    }
}

/**
 * SSLSocketFactory wrapper that explicitly enables every TLS version
 * and cipher suite the device supports.
 */
private class AllTlsSocketFactory(
    private val delegate: SSLSocketFactory
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(
        s: java.net.Socket, host: String, port: Int, autoClose: Boolean
    ) = delegate.createSocket(s, host, port, autoClose).also { enableAllTls(it) }

    override fun createSocket(host: String, port: Int) =
        delegate.createSocket(host, port).also { enableAllTls(it) }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) =
        delegate.createSocket(host, port, localHost, localPort).also { enableAllTls(it) }

    override fun createSocket(host: java.net.InetAddress, port: Int) =
        delegate.createSocket(host, port).also { enableAllTls(it) }

    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int) =
        delegate.createSocket(address, port, localAddress, localPort).also { enableAllTls(it) }

    private fun enableAllTls(socket: java.net.Socket) {
        if (socket is SSLSocket) {
            val manufacturer = android.os.Build.MANUFACTURER.lowercase()
            if (manufacturer == "amazon" || android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N) {
                // Only force all protocols/ciphers on old or broken TLS stacks
                socket.enabledProtocols = socket.supportedProtocols
                socket.enabledCipherSuites = socket.supportedCipherSuites
            }
            // Newer devices: leave defaults alone — they already negotiate correctly
        }
    }
}
