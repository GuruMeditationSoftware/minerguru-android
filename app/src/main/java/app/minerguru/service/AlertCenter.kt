package app.minerguru.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.minerguru.MainActivity
import app.minerguru.R
import app.minerguru.model.AlertEvaluator
import app.minerguru.model.AlertEvent
import app.minerguru.model.AlertKind
import app.minerguru.model.ChangeLog
import app.minerguru.model.ChangeSource
import app.minerguru.model.DiagnosticLog
import app.minerguru.model.BackgroundMode
import app.minerguru.model.tr
import app.minerguru.model.MinedCoin
import app.minerguru.model.MinerDevice
import app.minerguru.model.WatchSettings
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Settings and delivery of miner alerts. Shared by the app, the worker and the foreground service,
 * so the debounce state lives in preferences and every access is synchronized.
 */
object AlertCenter {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_MONITOR = "monitor"

    private const val PREFS = "alerts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MUTED = "mutedMiners"
    private const val KEY_DISABLED_KINDS = "disabledKinds"
    private const val KEY_MODE = "backgroundMode"
    private const val KEY_STATE = "evaluatorState"
    private const val KEY_RESTART_FLATLINE = "restartOnFlatline"
    private const val KEY_RESTART_PRIMARY = "restartToPrimary"
    private const val KEY_EXPECTED = "expectedAddresses"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // region Settings

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun mutedMiners(context: Context): Set<String> = prefs(context).getStringSet(KEY_MUTED, emptySet()).orEmpty().toSet()
    fun setMuted(context: Context, minerId: String, muted: Boolean) {
        val updated = mutedMiners(context).let { if (muted) it + minerId else it - minerId }
        prefs(context).edit().putStringSet(KEY_MUTED, updated).apply()
    }

    fun disabledKinds(context: Context): Set<AlertKind> =
        prefs(context).getStringSet(KEY_DISABLED_KINDS, emptySet()).orEmpty()
            .mapNotNull { runCatching { AlertKind.valueOf(it) }.getOrNull() }.toSet()
    fun setKindEnabled(context: Context, kind: AlertKind, enabled: Boolean) {
        val updated = disabledKinds(context).let { if (enabled) it - kind else it + kind }
        prefs(context).edit().putStringSet(KEY_DISABLED_KINDS, updated.map { it.name }.toSet()).apply()
    }

    fun backgroundMode(context: Context): BackgroundMode =
        runCatching { BackgroundMode.valueOf(prefs(context).getString(KEY_MODE, null) ?: "") }.getOrDefault(BackgroundMode.OFF)
    fun setBackgroundMode(context: Context, mode: BackgroundMode) = prefs(context).edit().putString(KEY_MODE, mode.name).apply()

    fun restartsOnFlatline(context: Context) = prefs(context).getBoolean(KEY_RESTART_FLATLINE, false)
    fun setRestartsOnFlatline(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_RESTART_FLATLINE, on).apply()
    fun restartsToPrimary(context: Context) = prefs(context).getBoolean(KEY_RESTART_PRIMARY, false)
    fun setRestartsToPrimary(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_RESTART_PRIMARY, on).apply()

    /** The address each coin should pay to, by [MinedCoin.symbol]. */
    fun expectedAddresses(context: Context): Map<String, String> {
        val o = runCatching { JSONObject(prefs(context).getString(KEY_EXPECTED, null) ?: "{}") }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** An empty address removes the coin. */
    fun setExpectedAddress(context: Context, coin: MinedCoin, address: String) {
        val trimmed = address.trim()
        val updated = expectedAddresses(context).toMutableMap()
        if (trimmed.isEmpty()) updated.remove(coin.symbol) else updated[coin.symbol] = trimmed
        synchronized(this) {
            val p = prefs(context)
            val evaluator = AlertEvaluator.fromJson(p.getString(KEY_STATE, null))
            evaluator.forgetPayoutAlerts()
            p.edit().putString(KEY_EXPECTED, JSONObject(updated as Map<*, *>).toString()).putString(KEY_STATE, evaluator.toJson()).apply()
        }
    }

    /** The app's permission and its switch in the system settings; before Android 13 there is only the switch. */
    fun canPostNotifications(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** `false` also when only the alert channel was switched off in the system settings. */
    fun reachesUser(context: Context): Boolean = canPostNotifications(context) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ALERTS)?.importance != NotificationManager.IMPORTANCE_NONE

    // endregion

    // region Evaluation

    /** Call once per fleet poll. Returns the alerts that were posted. */
    suspend fun evaluate(context: Context, miners: List<MinerDevice>, now: Long = System.currentTimeMillis()): List<AlertEvent> {
        DiagnosticLog.shared.activate(context.filesDir)
        ChangeLog.shared.activate(context.filesDir)
        val settings = WatchSettings(expectedAddresses(context), restartsOnFlatline(context), restartsToPrimary(context))
        val (evaluator, due) = synchronized(this) {
            val p = prefs(context)
            val evaluator = AlertEvaluator.fromJson(p.getString(KEY_STATE, null))
            val due = evaluator.evaluate(miners, now, settings)
            p.edit().putString(KEY_STATE, evaluator.toJson()).apply()
            evaluator to due
        }
        // The watchdog's only action, whether or not notifications are on.
        val restartEvents = evaluator.dueRestarts.map { (miner, reason) ->
            val error = try {
                ChangeLog.withSource(ChangeSource.WATCHDOG) { MinerApiClient.restartMiner(miner.ipAddress) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.shared.write("alert", "${miner.ipAddress} restart failed ${e.javaClass.simpleName}")
                e.message ?: e.javaClass.simpleName
            }
            evaluator.restartEvent(miner, reason, error)
        }
        // The restart message names the same problem: one message, not two at once.
        val events = due.filterNot { alarm -> !alarm.isRecovery && restartEvents.any { it.minerId == alarm.minerId && it.kind == alarm.kind } } + restartEvents
        if (!isEnabled(context)) return emptyList()
        val muted = mutedMiners(context)
        val disabled = disabledKinds(context)
        val deliverable = events.filter { it.minerId !in muted && it.kind !in disabled }
        events.forEach { event ->
            val address = miners.firstOrNull { it.id == event.minerId }?.ipAddress ?: "?"
            val outcome = if (event.isRecovery) "cleared" else "raised"
            val shown = event in deliverable && reachesUser(context)
            DiagnosticLog.shared.write("alert", "$address ${event.kind.name.lowercase()} $outcome delivered=${if (shown) 1 else 0}", now)
        }
        AlertEvent.combine(deliverable).forEach { post(context, it) }
        return deliverable
    }

    /** Posts alerts that do not come from polling, such as due maintenance. */
    fun deliver(context: Context, events: List<AlertEvent>): List<AlertEvent> {
        if (!isEnabled(context)) return emptyList()
        val disabled = disabledKinds(context)
        val deliverable = events.filter { it.kind !in disabled }
        AlertEvent.combine(deliverable).forEach { post(context, it) }
        return deliverable
    }

    fun forget(context: Context, minerId: String) {
        synchronized(this) {
            val p = prefs(context)
            val evaluator = AlertEvaluator.fromJson(p.getString(KEY_STATE, null))
            evaluator.forget(minerId)
            p.edit().putString(KEY_STATE, evaluator.toJson()).apply()
        }
        setMuted(context, minerId, false)
    }

    // endregion

    // region Notifications

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, tr(R.string.miner_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr(R.string.miners_going_offline_and_other)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, tr(R.string.background_monitoring), NotificationManager.IMPORTANCE_MIN).apply {
                description = tr(R.string.permanent_notification_while_monitoring_runs)
                setShowBadge(false)
            }
        )
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun post(context: Context, event: AlertEvent) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setPriority(if (event.isRecovery) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setSilent(event.isRecovery)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        // One notification per miner and kind: the all-clear replaces the alarm.
        val id = (event.kind.name + event.minerId).hashCode()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the call.
        }
    }

    // endregion
}
