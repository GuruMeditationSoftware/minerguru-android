package app.minerguru.service

import android.content.Context
import android.util.Log
import app.minerguru.model.L
import app.minerguru.model.minerScopeWithout
import app.minerguru.model.MinerDevice
import app.minerguru.model.MinerSettingsForm
import app.minerguru.model.PoolConfig
import app.minerguru.model.PoolScheduleRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stored pool schedules and their execution. Like the power schedules, the phone only switches with instant
 * monitoring: WorkManager runs too late for a set time.
 */
object PoolScheduleCenter {
    private const val PREFS = "poolSchedules"
    private const val KEY_RULES = "rules"
    private const val KEY_APPLIED = "applied"
    private const val TAG = "PoolSchedule"

    private val mutex = Mutex()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rules(context: Context): List<PoolScheduleRule> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_RULES, null) ?: return emptyList())
        (0 until arr.length()).map { PoolScheduleRule.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun setRules(context: Context, rules: List<PoolScheduleRule>) =
        prefs(context).edit().putString(KEY_RULES, JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()).apply()

    fun forget(context: Context, minerId: String) =
        setRules(context, rules(context).map { it.copy(minerIds = minerScopeWithout(it.minerIds, minerId)) })

    /** Last failed switch, shown on the pools page until the next one succeeds. */
    @Volatile var lastError: String? = null
        private set

    /** Call once per fleet poll, with the pool library. */
    suspend fun evaluate(context: Context, miners: List<MinerDevice>, pools: List<PoolConfig>, now: Long = System.currentTimeMillis()) {
        if (!PowerCenter.isSwitching(context)) return
        val rules = rules(context)
        if (rules.isEmpty()) return
        mutex.withLock {
            val applied = runCatching { JSONObject(prefs(context).getString(KEY_APPLIED, null) ?: "{}") }.getOrDefault(JSONObject())
            for (rule in rules) {
                val pool = pools.firstOrNull { it.id == rule.poolId } ?: continue
                for (miner in miners) {
                    if (!miner.connectionState.isOnline || miner.latestInfo == null || !rule.appliesTo(miner.id)) continue
                    val key = "${rule.id}|${miner.id}"
                    val moment = rule.dueOccurrence(now, if (applied.has(key)) applied.getLong(key) else null) ?: continue
                    val switches = !pool.matches(MinerSettingsForm.from(miner).primary)
                    // Stays due without a session, so it still runs if a code comes in time.
                    if (switches && !OtpCenter.get(context).canWriteUnattended(miner, now)) continue
                    applied.put(key, moment)
                    prefs(context).edit().putString(KEY_APPLIED, applied.toString()).apply()
                    if (switches) switch(context, miner, pool)
                }
            }
        }
    }

    private suspend fun switch(context: Context, miner: MinerDevice, pool: PoolConfig) {
        val updated = MinerSettingsForm.from(miner).makePrimary(pool)
        updated.poolValidationMessage?.let { lastError = "${miner.displayName}: $it"; return }
        try {
            OtpCenter.get(context).perform(miner, interactive = false) {
                MinerApiClient.updateSystemSettings(miner.ipAddress, updated.poolPayload(miner.supportsDualPool), it)
            }
            for (slot in updated.slotsToClear) MinerApiClient.deletePoolSlot(miner.ipAddress, slot)
            MinerApiClient.restartMiner(miner.ipAddress)
            Log.i(TAG, "${miner.ipAddress} → ${pool.name}")
            lastError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Switch for ${miner.ipAddress} failed: ${e.message}")
            lastError = "${miner.displayName}: ${e.message ?: L("Nicht erreichbar", "Unreachable")}"
        }
    }
}
