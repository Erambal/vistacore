package com.vistacore.launcher

import android.app.Application
import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Security

class VistaCoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.LEGACY_TLS) {
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
                Log.d("VistaCoreApp", "Conscrypt TLS provider installed")
            } catch (e: Exception) {
                Log.w("VistaCoreApp", "Failed to install Conscrypt", e)
            }
        }
    }
}
