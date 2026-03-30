package com.vistacore.launcher.iptv

import android.os.Build
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.KeyStore
import javax.net.ssl.*

/**
 * Enables broad TLS compatibility for OkHttp clients.
 * FireStick and older Android TV devices (API 21-25) may not enable
 * TLS 1.2 by default. This forces all supported protocols and uses
 * a permissive set of connection specs so handshakes succeed on
 * devices with limited TLS stacks.
 */
object TlsCompat {

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            try {
                val trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
                )
                trustManagerFactory.init(null as KeyStore?)
                val trustManagers = trustManagerFactory.trustManagers
                val trustManager = trustManagers[0] as X509TrustManager

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustManager), null)

                val socketFactory = Tls12SocketFactory(sslContext.socketFactory)

                builder.sslSocketFactory(socketFactory, trustManager)
            } catch (_: Exception) {
                // Fall through to connection spec fallback
            }
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
 * SSLSocketFactory wrapper that explicitly enables TLS 1.2 on sockets.
 * On API 16-20 TLS 1.2 is available but not enabled by default.
 * On API 21-25 (FireStick) it may be inconsistently enabled.
 */
private class Tls12SocketFactory(
    private val delegate: SSLSocketFactory
) : SSLSocketFactory() {

    private val tlsVersions = arrayOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(
        s: java.net.Socket, host: String, port: Int, autoClose: Boolean
    ) = delegate.createSocket(s, host, port, autoClose).also { enableTls(it) }

    override fun createSocket(host: String, port: Int) =
        delegate.createSocket(host, port).also { enableTls(it) }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) =
        delegate.createSocket(host, port, localHost, localPort).also { enableTls(it) }

    override fun createSocket(host: java.net.InetAddress, port: Int) =
        delegate.createSocket(host, port).also { enableTls(it) }

    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int) =
        delegate.createSocket(address, port, localAddress, localPort).also { enableTls(it) }

    private fun enableTls(socket: java.net.Socket) {
        if (socket is SSLSocket) {
            val supported = socket.supportedProtocols
            val enabled = supported.filter { it in tlsVersions }.toTypedArray()
            if (enabled.isNotEmpty()) {
                socket.enabledProtocols = enabled
            }
        }
    }
}
