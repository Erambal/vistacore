package com.vistacore.launcher.iptv

import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Enables broad TLS compatibility for OkHttp clients.
 *
 * FireStick devices (Fire OS) have a different/limited certificate store
 * compared to Google TV and may fail TLS handshakes even on newer API
 * levels. This helper:
 *   1. Creates a custom SSLSocketFactory that enables ALL supported
 *      TLS protocol versions on every socket.
 *   2. Uses permissive connection specs (modern + compatible + cleartext).
 *
 * Applied on ALL API levels because Fire OS can diverge from AOSP
 * regardless of its reported SDK version.
 */
object TlsCompat {

    private const val TAG = "TlsCompat"

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
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

        // Accept both modern and compatible connection specs
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
 * the device supports. Fire OS and older Android TV builds may not
 * enable TLS 1.2/1.3 by default even when the underlying SSL library
 * supports them.
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
            // Enable every protocol the device supports
            socket.enabledProtocols = socket.supportedProtocols
            // Enable every cipher suite the device supports
            socket.enabledCipherSuites = socket.supportedCipherSuites
        }
    }
}
