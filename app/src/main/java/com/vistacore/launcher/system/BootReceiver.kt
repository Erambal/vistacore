package com.vistacore.launcher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.ui.MainActivity

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsManager(context)
            if (!prefs.autoLaunchOnBoot) return

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}
