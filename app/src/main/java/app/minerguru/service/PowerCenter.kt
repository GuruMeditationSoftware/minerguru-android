package app.minerguru.service

import android.content.Context
import android.util.Log
import app.minerguru.model.BackgroundMode
import app.minerguru.model.L
import app.minerguru.model.minerScopeWithout
import app.minerguru.model.MinerDevice
import app.minerguru.model.MinerFirmware
import app.minerguru.model.MinerSystemInfo
import app.minerguru.model.MinerSystemUpdatePayload
import app.minerguru.model.PowerAction
import app.minerguru.model.PowerSchedule
import app.minerguru.model.PowerTariff
import app.minerguru.model.ScheduleRule
import app.minerguru.model.ScheduleTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** The miner's own clock and fan settings, saved before the night mode and written back afterwards. */
data class SavedMinerSettings(val frequencyMHz: Int, val coreVoltageMV: Int, val autoFanMode: Int, val fanPercent: Int) {
    val payload: MinerSystemUpdatePayload
        get() = MinerSystemUpdatePayload(frequency = frequencyMHz, coreVoltage = coreVoltageMV, autofanspeed = autoFanMode,
            fanspeed = if (autoFanMode == 0) fanPercent else null)

    fun toJson(): JSONObject = JSONObject().put("frequencyMHz", frequencyMHz).put("coreVoltageMV", coreVoltageMV)
        .put("autoFanMode", autoFanMode).put("fanPercent", fanPercent)

    companion object {
        fun of(info: MinerSystemInfo) = SavedMinerSettings(info.frequencyMHz, info.coreVoltageTargetMV,
            if (info.autoFanSpeed) maxOf(1, info.autoFanModeRaw) else 0, Math.round(info.fanSpeedPercent).toInt())

        fun fromJson(o: JSONObject) = SavedMinerSettings(o.getInt("frequencyMHz"), o.getInt("coreVoltageMV"), o.getInt("autoFanMode"), o.getInt("fanPercent"))
    }
}

/** What the schedules last did to one miner. */
data class MinerPowerState(val action: PowerAction, val since: Long, val saved: SavedMinerSettings? = null) {
    fun toJson(): JSONObject = JSONObject().put("action", action.name).put("since", since).apply { saved?.let { put("saved", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject) = MinerPowerState(PowerAction.valueOf(o.getString("action")), o.getLong("since"),
            o.optJSONObject("saved")?.let(SavedMinerSettings::fromJson))
    }
}

/**
 * Tariff, schedules and their execution (MG-14). Stored here (not in the view model) so the foreground service
 * switches the miners too. Android only switches with instant monitoring: exact alarms are refused from
 * Android 14 on and WorkManager runs ±15 minutes late.
 */
object PowerCenter {
    private const val PREFS = "power"
    private const val KEY_TARIFF = "tariff"
    private const val KEY_RULES = "rules"
    private const val KEY_RUNS = "runsSchedules"
    private const val KEY_NIGHT_FAN = "nightFan"
    private const val KEY_STATES = "states"
    private const val TAG = "PowerCenter"

    /** At most one switch per miner in this time, i.e. at most four per hour. */
    const val MIN_SWITCH_INTERVAL_MS = 15 * 60 * 1000L

    private val mutex = Mutex()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // region Settings

    fun tariff(context: Context): PowerTariff =
        prefs(context).getString(KEY_TARIFF, null)?.let { runCatching { PowerTariff.fromJson(JSONObject(it)) }.getOrNull() } ?: PowerTariff()
    fun setTariff(context: Context, tariff: PowerTariff) = prefs(context).edit().putString(KEY_TARIFF, tariff.toJson().toString()).apply()

    fun rules(context: Context): List<ScheduleRule> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_RULES, null) ?: return emptyList())
        (0 until arr.length()).map { ScheduleRule.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun setRules(context: Context, rules: List<ScheduleRule>) =
        prefs(context).edit().putString(KEY_RULES, JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()).apply()

    /** Off by default: the Mac is the device that switches, the phone only when the user says so. */
    fun runsSchedules(context: Context) = prefs(context).getBoolean(KEY_RUNS, false)
    fun setRunsSchedules(context: Context, runs: Boolean) = prefs(context).edit().putBoolean(KEY_RUNS, runs).apply()

    fun nightFanPercent(context: Context) = prefs(context).getInt(KEY_NIGHT_FAN, 40)
    fun setNightFanPercent(context: Context, percent: Int) = prefs(context).edit().putInt(KEY_NIGHT_FAN, percent).apply()

    fun states(context: Context): Map<String, MinerPowerState> = runCatching {
        val o = JSONObject(prefs(context).getString(KEY_STATES, null) ?: return emptyMap())
        o.keys().asSequence().associateWith { MinerPowerState.fromJson(o.getJSONObject(it)) }
    }.getOrDefault(emptyMap())
    private fun setStates(context: Context, states: Map<String, MinerPowerState>) =
        prefs(context).edit().putString(KEY_STATES, JSONObject().apply { states.forEach { (k, v) -> put(k, v.toJson()) } }.toString()).apply()

    /** Instant monitoring is on and allowed to run: the only way the phone switches reliably. */
    fun hasInstantMonitoring(context: Context): Boolean =
        AlertCenter.isEnabled(context) && AlertCenter.canPostNotifications(context) && AlertCenter.backgroundMode(context) == BackgroundMode.INSTANT

    fun isSwitching(context: Context) = runsSchedules(context) && hasInstantMonitoring(context)

    /** Removes a zone and the schedules that follow it. */
    fun removeZone(context: Context, zoneId: String) {
        val tariff = tariff(context)
        setTariff(context, tariff.copy(zones = tariff.zones.filterNot { it.id == zoneId }))
        setRules(context, rules(context).filterNot { (it.trigger as? ScheduleTrigger.Zone)?.zoneId == zoneId })
    }

    fun forget(context: Context, minerId: String) {
        setStates(context, states(context) - minerId)
        setRules(context, rules(context).map { it.copy(minerIds = minerScopeWithout(it.minerIds, minerId)) })
    }

    // endregion

    // region Execution

    /** Pause needs AxeOS 2.15+; there `pools[]` is present. */
    fun canPause(miner: MinerDevice): Boolean = miner.latestInfo?.firmware == MinerFirmware.AXE_OS && miner.latestInfo?.axePools != null

    /** The action really used for [miner]: pause becomes night mode where the firmware cannot pause (unknown while offline). */
    fun effective(action: PowerAction, miner: MinerDevice): PowerAction =
        if (action == PowerAction.PAUSE && miner.latestInfo != null && !canPause(miner)) PowerAction.NIGHT_MODE else action

    fun projectedCost(context: Context, miner: MinerDevice, hours: Double, startMillis: Long,
                      tariff: PowerTariff = tariff(context), rules: List<ScheduleRule> = rules(context)): Double =
        PowerSchedule.cost(miner.latestInfo?.power ?: 0.0, startMillis, hours, tariff, rules, miner.id, canPause(miner))

    /** Call once per fleet poll. Switches every online miner whose state differs from what the rules ask for now. */
    suspend fun evaluate(context: Context, miners: List<MinerDevice>, now: Long = System.currentTimeMillis()) {
        if (!isSwitching(context)) return
        // The view model and the service may poll at the same time.
        mutex.withLock {
            val tariff = tariff(context)
            val rules = rules(context)
            val nightFan = nightFanPercent(context)
            var states = states(context)
            for (miner in miners) {
                if (!miner.connectionState.isOnline || miner.latestInfo == null) continue
                val wanted = effective(PowerSchedule.action(miner.id, now, rules, tariff), miner)
                val current = states[miner.id]
                if ((current?.action ?: PowerAction.NORMAL) == wanted) continue
                if (current != null && now - current.since < MIN_SWITCH_INTERVAL_MS) continue
                // No request without a session: repeated refusals would lock the miner.
                if (!OtpCenter.get(context).canWriteUnattended(miner, now)) continue
                val next = switchMiner(context, miner, current, wanted, nightFan, now) ?: continue
                states = states + (miner.id to next)
                setStates(context, states)
            }
        }
    }

    private suspend fun switchMiner(context: Context, miner: MinerDevice, previous: MinerPowerState?, action: PowerAction, nightFan: Int, now: Long): MinerPowerState? {
        val info = miner.latestInfo ?: return null
        val host = miner.ipAddress
        return try {
            // Leave the old state first.
            if (previous?.action == PowerAction.PAUSE) MinerApiClient.resumeMiner(host)
            var saved = previous?.saved
            when (action) {
                PowerAction.NORMAL -> {
                    saved?.let { applySettings(context, it.payload, miner) }
                    saved = null
                }
                PowerAction.NIGHT_MODE -> {
                    val keep = saved ?: SavedMinerSettings.of(info)
                    saved = keep
                    applySettings(context, nightPayload(miner, keep, nightFan), miner)
                }
                PowerAction.PAUSE -> {
                    // Night values stay until NORMAL: restoring them restarts AxeOS, and the restart would end the pause.
                    MinerApiClient.pauseMiner(host)
                }
            }
            Log.i(TAG, "Zeitplan: $host → $action")
            lastError = null
            MinerPowerState(action, now, saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Zeitplan für $host fehlgeschlagen: ${e.message}")
            lastError = "${miner.displayName}: ${e.message ?: L("Nicht erreichbar", "Unreachable")}"
            null
        }
    }

    /** Last failed switch, shown on the power page until the next one succeeds. */
    @Volatile var lastError: String? = null
        private set

    /** Eco clock and the fixed night fan speed. */
    private fun nightPayload(miner: MinerDevice, saved: SavedMinerSettings, nightFan: Int): MinerSystemUpdatePayload {
        val eco = miner.recommendedPresets.firstOrNull { it.name == "Eco" }
        return MinerSystemUpdatePayload(
            frequency = minOf(eco?.frequencyMHz ?: saved.frequencyMHz, saved.frequencyMHz),
            coreVoltage = minOf(eco?.coreVoltageMV ?: saved.coreVoltageMV, saved.coreVoltageMV),
            autofanspeed = 0,
            fanspeed = nightFan
        )
    }

    /** AxeOS uses new clock settings only after a reboot, NerdOS at once. */
    private suspend fun applySettings(context: Context, payload: MinerSystemUpdatePayload, miner: MinerDevice) {
        OtpCenter.get(context).perform(miner, interactive = false) {
            MinerApiClient.updateSystemSettings(miner.ipAddress, payload, it)
        }
        if (!miner.isNerdOS) MinerApiClient.restartMiner(miner.ipAddress)
    }

    // endregion
}
