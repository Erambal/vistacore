package com.vistacore.launcher.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vistacore.launcher.R
import com.vistacore.launcher.system.DeviceActivationManager
import kotlinx.coroutines.*

/**
 * Blocking screen shown when the admin has deactivated this device.
 * The user can tap Retry to re-check; if the device has been re-activated
 * the app proceeds normally.
 */
class LockScreenActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var activationManager: DeviceActivationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)

        activationManager = DeviceActivationManager(this)

        // Show the device ID so the user can read it to the admin
        findViewById<android.widget.TextView>(R.id.lock_device_id).text =
            activationManager.getDeviceId()

        findViewById<android.widget.Button>(R.id.btn_retry).apply {
            setOnClickListener { retryActivation() }
            setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
            requestFocus()
        }
    }

    private fun retryActivation() {
        val btn = findViewById<android.widget.Button>(R.id.btn_retry)
        btn.isEnabled = false
        btn.text = "Checking…"

        scope.launch {
            val active = activationManager.isDeviceActive()
            if (active) {
                Toast.makeText(this@LockScreenActivity, "Device activated!", Toast.LENGTH_SHORT).show()
                // Return to splash which will proceed normally
                val intent = android.content.Intent(this@LockScreenActivity, SplashActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } else {
                btn.isEnabled = true
                btn.text = "Retry"
                Toast.makeText(this@LockScreenActivity, "Device is still deactivated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Prevent back button from escaping the lock screen
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Do nothing — locked
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
