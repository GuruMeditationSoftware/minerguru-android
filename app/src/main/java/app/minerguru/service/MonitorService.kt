package app.minerguru.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.minerguru.R
import app.minerguru.model.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Instant monitoring: foreground service of type `connectedDevice` (talks to devices in the LAN),
 * polls every [INTERVAL_MS]. Unlike `dataSync` it has no daily time limit.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AlertCenter.ensureChannels(this)
        val notification = NotificationCompat.Builder(this, AlertCenter.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(L("MinerGuru überwacht deine Miner", "MinerGuru is watching your miners"))
            .setContentText(L("Abfrage jede Minute. Ausschalten in den Einstellungen.", "Polling every minute. Turn off in Settings."))
            .setContentIntent(AlertCenter.openAppIntent(this))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    MinerMonitor.pollOnce(this@MonitorService)
                    delay(INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val INTERVAL_MS = 60_000L

        fun start(context: Context) = context.startForegroundService(Intent(context, MonitorService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, MonitorService::class.java))
    }
}
