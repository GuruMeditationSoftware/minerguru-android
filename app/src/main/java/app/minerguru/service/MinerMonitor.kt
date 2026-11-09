package app.minerguru.service

import app.minerguru.widget.WidgetBridge

import android.content.Context
import android.os.Build
import app.minerguru.model.AppLanguage
import app.minerguru.model.ChangeLog
import app.minerguru.model.ChangeSource
import app.minerguru.model.ConnectionState
import app.minerguru.model.Lang
import app.minerguru.model.MinerDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Polls the saved miners without the UI (worker and foreground service) and feeds the history, alerts and schedules. */
object MinerMonitor {
    /** Pause between the retries of a worker run, so three failed polls also span 90 s. */
    private const val RETRY_DELAY_MS = 45_000L

    /**
     * One poll of all miners. With [retries] > 0 offline miners are polled again after [RETRY_DELAY_MS],
     * so a single 15-minute worker run can confirm an outage.
     */
    suspend fun pollOnce(context: Context, retries: Int = 0) {
        val app = context.applicationContext
        applyLanguage(app)
        var miners = MinerStore.readPersistedMiners(app)
        if (miners.isEmpty()) return
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                if (miners.none { it.connectionState is ConnectionState.Offline }) return
                delay(RETRY_DELAY_MS)
            }
            miners = coroutineScope { miners.map { async { poll(it) } }.awaitAll() }
            HistoryRecorder.record(app, miners, flush = true)
            runCatching { WidgetBridge.publish(app, miners) }
            val events = AlertCenter.evaluate(app, miners) + AlertCenter.deliver(app, MaintenanceCenter.dueEvents(app))
            EmailAlerts.deliver(app, events)
            ChangeLog.withSource(ChangeSource.SCHEDULE) {
                PowerCenter.evaluate(app, miners)
                PoolScheduleCenter.evaluate(app, miners, MinerStore.readPoolLibrary(app))
            }
        }
    }

    private suspend fun poll(miner: MinerDevice): MinerDevice = try {
        val fetched = MinerApiClient.fetchSystemInfo(miner.ipAddress)
        ChangeLog.shared.noteValues(miner.ipAddress, fetched.rawPretty)
        // Only a miner on its fallback pool needs the check whether the primary one answers.
        val latency = if (fetched.info.isUsingFallbackStratum) PoolLatencyService.measureForMiner(fetched.info) else miner.poolLatency
        miner.copy(latestInfo = fetched.info, rawJSON = fetched.rawPretty, poolLatency = latency, connectionState = ConnectionState.Online)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        miner.copy(connectionState = ConnectionState.Offline(e.message ?: ""))
    }

    /** Texts in the app language also when no activity has started in this process. */
    fun applyLanguage(context: Context) {
        val stored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AppLanguage.SYSTEM
        else MinerStore.readStoredLanguage(context)
        Lang.apply(stored)
    }
}
