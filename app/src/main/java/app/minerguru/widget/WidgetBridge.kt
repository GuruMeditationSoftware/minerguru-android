package app.minerguru.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.minerguru.model.ConnectionState
import app.minerguru.model.MinerDevice
import app.minerguru.model.SoloChanceTimeframe
import app.minerguru.service.HistoryRecorder
import app.minerguru.service.MinerApiClient
import app.minerguru.service.MinerMonitor
import app.minerguru.service.MinerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Keeps the home-screen widgets in step with the miners: the app and the background monitor hand every poll
 * to [publish], which writes the snapshot and redraws the widgets when something changed or a minute has passed. Without either, the widgets ask
 * [WidgetRefreshWorker] to poll the miners when their data has grown old.
 */
object WidgetBridge {
    /** Widgets follow drifting figures this often; a miner going offline or hot redraws them at once. */
    private const val REDRAW_INTERVAL_MS = 60_000L
    /** The 24-hour chart changes slowly; the history database is asked this often. */
    private const val HOURLY_INTERVAL_MS = 10 * 60_000L
    /** Older data makes the widgets poll the miners themselves. */
    const val STALE_AFTER_MS = 10 * 60_000L

    private var lastRedraw = 0L
    private var lastState: List<String>? = null
    private var hourly: Pair<Long, List<Double>>? = null

    suspend fun publish(context: Context, miners: List<MinerDevice>, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        if (!hasWidgets(app)) return
        val hourlyNow = hourly?.takeIf { now - it.first < HOURLY_INTERVAL_MS }?.second ?: run {
            val points = runCatching { HistoryRecorder.hourlyPoints(app, now - 24 * 3600_000L, now) }.getOrDefault(emptyList())
            WidgetSnapshot.fleetHourly(points).also { hourly = now to it }
        }
        val snapshot = WidgetSnapshot.of(miners, tempWarning(app), hourlyNow, timeframe(app), now)
        val state = snapshot.miners.map { "${it.id}:${it.state}:${snapshot.isHot(it)}" }
        synchronized(this) {
            if (state == lastState && now - lastRedraw < REDRAW_INTERVAL_MS) return
            lastState = state
            lastRedraw = now
        }
        runCatching { WidgetSnapshot.write(app, snapshot) }.onFailure { return }
        redrawAll(app)
    }

    fun redrawAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        for (provider in providers) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isNotEmpty()) MinerWidgets.update(context, manager, provider, ids)
        }
    }

    /** Called when a widget draws: polls the miners in the background if nobody has for a while. */
    fun refreshIfStale(context: Context, snapshot: WidgetSnapshot?) {
        val age = System.currentTimeMillis() - (snapshot?.time ?: 0L)
        if (age < STALE_AFTER_MS) return
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            // Miners only answer in the local network, never over mobile data.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return providers.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    private val providers = listOf(FleetWidget::class.java, MinerWidget::class.java, SoloWidget::class.java)
    private const val WORK_NAME = "widgetRefresh"

    private fun prefs(context: Context) = context.getSharedPreferences("minerguru", Context.MODE_PRIVATE)
    private fun tempWarning(context: Context) = prefs(context).getFloat("tempWarningC", 68f).toDouble()
    private fun timeframe(context: Context) =
        runCatching { SoloChanceTimeframe.valueOf(prefs(context).getString("soloTimeframe", null) ?: "") }.getOrDefault(SoloChanceTimeframe.PER_DAY)

    /** One poll of the saved miners for the widgets alone: no history, alerts or schedules. */
    suspend fun pollForWidgets(context: Context) {
        val app = context.applicationContext
        MinerMonitor.applyLanguage(app)
        val miners = MinerStore.readPersistedMiners(app)
        if (miners.isEmpty()) return
        val polled = coroutineScope {
            miners.map { m ->
                async {
                    try {
                        val fetched = MinerApiClient.fetchSystemInfo(m.ipAddress)
                        m.copy(latestInfo = fetched.info, connectionState = ConnectionState.Online)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        m.copy(connectionState = ConnectionState.Offline(e.message ?: ""))
                    }
                }
            }.awaitAll()
        }
        // Away from home no miner answers: keep the last real figures, which the widgets then mark with their time.
        if (polled.none { it.connectionState is ConnectionState.Online }) return
        synchronized(this) { lastState = null }
        publish(app, polled)
    }
}

/** Polls the miners for the widgets when neither the app nor the background monitor has done so lately. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetBridge.pollForWidgets(applicationContext)
        return Result.success()
    }
}
