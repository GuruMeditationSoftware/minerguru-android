package app.minerguru.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** A reboot or an app update ends the monitoring; this brings it back without opening the app. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        // The system may refuse a foreground start here; the next app start then does it.
        runCatching { BackgroundMonitor.sync(context) }
            .onFailure { Log.w(TAG, "monitoring not restarted: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "RestartReceiver"
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
