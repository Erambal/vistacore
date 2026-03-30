package com.vistacore.launcher.iptv

import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * TLS compatibility for OkHttp clients.
 *
 * Behavior is controlled by BuildConfig.LEGACY_TLS:
 *  - legacy build (b): enables all TLS versions/ciphers and wraps sockets
 *  - modern build (a): no TLS customization beyond trust-all for IPTV servers
 */
object TlsCompat {

    private const val TAG = "TlsCompat"

    /**
     * Standard TLS compat: on legacy builds enables all protocol versions
     * and cipher suites. On modern builds, returns the builder untouched.
     */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (!com.vistacore.launcher.BuildConfig.LEGACY_TLS) return builder

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
     * On legacy builds, also wraps with AllTlsSocketFactory for broad protocol support.
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

            if (com.vistacore.launcher.BuildConfig.LEGACY_TLS) {
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
 * and cipher suite the device supports. Only used in legacy builds.
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
            socket.enabledProtocols = socket.supportedProtocols
            socket.enabledCipherSuites = socket.supportedCipherSuites
        }
    }
}
