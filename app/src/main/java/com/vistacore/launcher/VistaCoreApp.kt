package com.vistacore.launcher

import android.app.Application
import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Security

class VistaCoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install Conscrypt only on Fire OS / older devices that have a broken
        // TLS stack. Newer Google TVs already have a working provider and
        // Conscrypt can conflict with it.
        if (needsConscrypt()) {
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
                Log.d("VistaCoreApp", "Conscrypt TLS provider installed")
            } catch (e: Exception) {
                Log.w("VistaCoreApp", "Failed to install Conscrypt", e)
            }
        } else {
            Log.d("VistaCoreApp", "Skipping Conscrypt — device TLS stack is fine")
        }
    }

    private fun needsConscrypt(): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        // Fire OS devices have the broken TLS renegotiation issue
        if (manufacturer == "amazon") return true
        // Android 7.0 and below may have outdated TLS stacks
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N) return true
        return false
    }
}
