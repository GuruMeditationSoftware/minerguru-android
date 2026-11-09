package app.minerguru.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.minerguru.model.BackgroundMode
import java.util.concurrent.TimeUnit

/** Starts and stops the background monitoring that matches the settings. */
object BackgroundMonitor {
    private const val WORK_NAME = "minerMonitor"

    /** Call when a setting changes and when the app comes to the foreground (a stopped service is restarted). */
    fun sync(context: Context) {
        val app = context.applicationContext
        val active = AlertCenter.isEnabled(app) && AlertCenter.canPostNotifications(app)
        val mode = if (active) AlertCenter.backgroundMode(app) else BackgroundMode.OFF
        val work = WorkManager.getInstance(app)

        if (mode == BackgroundMode.SAVER) {
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
                // Miners are only reachable in the local network, which is never metered mobile data.
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            work.cancelUniqueWork(WORK_NAME)
        }

        if (mode == BackgroundMode.INSTANT) MonitorService.start(app) else MonitorService.stop(app)
    }
}
