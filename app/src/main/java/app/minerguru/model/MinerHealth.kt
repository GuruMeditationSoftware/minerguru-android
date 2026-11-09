package app.minerguru.model

import org.json.JSONObject
import kotlin.math.max

/**
 * Watches one miner for overheating, flatline (ESP-Miner #1053: the miner answers and draws power,
 * but no shares arrive and its averages freeze), a fallback pool it does not leave (#1618) and
 * restarts nobody asked for. All but the restart need more than one poll, to avoid false alarms.
 * The state lives in the alert JSON because background polls run in short-lived workers.
 */
class MinerHealth {
    enum class AutoRestartReason { FLATLINE, STUCK_ON_FALLBACK }

    private var shares: Int? = null
    private var sharesChangedAt: Long? = null
    private var hashRate: Double? = null
    private var hashRateChangedAt: Long? = null
    private var samePolls = 0
    private var hotPolls = 0
    private var coolPolls = 0
    private var lastSnapshotAt: Long? = null
    private var peakStallMs = 0L
    private var fallbackSince: Long? = null
    private var primaryUpSince: Long? = null
    private var lastUptime: Int? = null

    var isOverheating = false
        private set
    var isFlatlining = false
        private set
    var isOnFallback = false
        private set
    var isStuckOnFallback = false
        private set
    /** The last poll found a restart MinerGuru did not ask for. */
    var restartedUnexpectedly = false
        private set

    var overheatAlerted = false
    var lastOverheatAlertAt: Long? = null
    var flatlineAlerted = false
    var lastFlatlineAlertAt: Long? = null
    var fallbackAlerted = false
    var lastFallbackAlertAt: Long? = null
    var stuckAlerted = false
    /** Hash of the last profile in the diagnostic log. */
    var lastProfile = 0

    /** Minutes without a new share, for the alarm text. */
    fun minutesWithoutShares(now: Long): Long = (now - (sharesChangedAt ?: now)) / 60_000

    fun update(info: MinerSystemInfo, now: Long, primaryReachable: Boolean = false, restartExpected: Boolean = false) {
        updateOverheat(info)
        updateFlatline(info, now)
        updateFallback(info, now, primaryReachable)
        restartedUnexpectedly = lastUptime?.let { info.uptimeSeconds + UPTIME_TOLERANCE_SECONDS < it } == true && !restartExpected
        lastUptime = info.uptimeSeconds
    }

    private fun updateFallback(info: MinerSystemInfo, now: Long, primaryReachable: Boolean) {
        if (!info.isUsingFallbackStratum || info.isDualPoolMining) {
            fallbackSince = null
            primaryUpSince = null
            isOnFallback = false
            isStuckOnFallback = false
            return
        }
        // Right after a start any miner may try the fallback pool, and a paused one proves nothing.
        if (info.uptimeSeconds < STARTUP_GRACE_SECONDS || info.miningPaused) {
            fallbackSince = null
            primaryUpSince = null
            isStuckOnFallback = false
            return
        }
        val since = fallbackSince ?: now
        fallbackSince = since
        primaryUpSince = if (primaryReachable) primaryUpSince ?: now else null
        isOnFallback = isOnFallback || now - since >= FALLBACK_ALARM_MS
        isStuckOnFallback = isOnFallback && primaryUpSince?.let { now - it >= STUCK_ON_FALLBACK_MS } == true
    }

    /** What the watchdog should do now, if the user allowed it. Never with an overheated miner. */
    fun dueAutoRestart(onFlatline: Boolean, toPrimary: Boolean, lastRestart: Long?, now: Long): AutoRestartReason? {
        if (isOverheating) return null
        if (lastRestart != null && now - lastRestart < AUTO_RESTART_INTERVAL_MS) return null
        if (onFlatline && isFlatlining) return AutoRestartReason.FLATLINE
        if (toPrimary && isStuckOnFallback) return AutoRestartReason.STUCK_ON_FALLBACK
        return null
    }

    private fun updateOverheat(info: MinerSystemInfo) {
        val limit = info.overheatTemp?.takeIf { it > 0 }
        val hot = info.overheatMode ||
            (limit != null && (info.temp >= limit - OVERHEAT_MARGIN || (info.vrTemp ?: 0.0) > limit))
        hotPolls = if (hot) hotPolls + 1 else 0
        coolPolls = if (hot) 0 else coolPolls + 1
        if (info.overheatMode || hotPolls >= OVERHEAT_POLLS) isOverheating = true
        else if (coolPolls >= OVERHEAT_POLLS) isOverheating = false
    }

    private fun updateFlatline(info: MinerSystemInfo, now: Long) {
        val rate = info.hashRate1m ?: 0.0
        if (rate != hashRate) {
            hashRate = rate
            hashRateChangedAt = now
            samePolls = 0
        } else {
            samePolls++
        }
        peakStallMs = max(peakStallMs, now - (sharesChangedAt ?: now))
        val restarting = info.uptimeSeconds < STARTUP_GRACE_SECONDS
        if (info.sharesAccepted != shares || restarting || info.miningPaused || rate <= 0) {
            shares = info.sharesAccepted
            sharesChangedAt = now
            isFlatlining = false
            return
        }
        val stalled = now - (sharesChangedAt ?: now) >= flatlineWindowMs(rate, info.activePoolDifficulty)
        val frozen = samePolls >= FROZEN_POLLS && now - (hashRateChangedAt ?: now) >= FROZEN_DURATION_MS
        isFlatlining = stalled && frozen
    }

    /** The measurements behind both alarms, once every [SNAPSHOT_INTERVAL_MS]: what thresholds are judged by. */
    fun snapshot(info: MinerSystemInfo, now: Long): String? {
        lastSnapshotAt?.let { if (now - it < SNAPSHOT_INTERVAL_MS) return null }
        lastSnapshotAt = now
        val rate = info.hashRate1m ?: 0.0
        val window = flatlineWindowMs(rate, info.activePoolDifficulty)
        val line = listOf(
            "temp=${info.temp}", "vr=${info.vrTemp ?: "-"}", "limit=${info.overheatTemp ?: "-"}",
            "flag=${if (info.overheatMode) 1 else 0}", "paused=${if (info.miningPaused) 1 else 0}", "rate1m=$rate",
            "shares=${info.sharesAccepted}", "uptime=${info.uptimeSeconds}", "diff=${info.activePoolDifficulty ?: "-"}",
            "stallMin=${minutesWithoutShares(now)}", "peakStallMin=${peakStallMs / 60_000}", "windowMin=${window / 60_000}",
            "frozenPolls=$samePolls", "hotPolls=$hotPolls"
        ).joinToString(" ")
        peakStallMs = 0
        return line
    }

    fun toJson(): JSONObject = JSONObject().apply {
        shares?.let { put("shares", it) }
        sharesChangedAt?.let { put("sharesChangedAt", it) }
        hashRate?.let { put("hashRate", it) }
        hashRateChangedAt?.let { put("hashRateChangedAt", it) }
        put("samePolls", samePolls)
        put("hotPolls", hotPolls)
        put("coolPolls", coolPolls)
        put("isOverheating", isOverheating)
        put("isFlatlining", isFlatlining)
        put("overheatAlerted", overheatAlerted)
        lastOverheatAlertAt?.let { put("lastOverheatAlertAt", it) }
        put("flatlineAlerted", flatlineAlerted)
        lastFlatlineAlertAt?.let { put("lastFlatlineAlertAt", it) }
        lastSnapshotAt?.let { put("lastSnapshotAt", it) }
        put("peakStallMs", peakStallMs)
        put("lastProfile", lastProfile)
        fallbackSince?.let { put("fallbackSince", it) }
        primaryUpSince?.let { put("primaryUpSince", it) }
        lastUptime?.let { put("lastUptime", it) }
        put("isOnFallback", isOnFallback)
        put("isStuckOnFallback", isStuckOnFallback)
        put("fallbackAlerted", fallbackAlerted)
        lastFallbackAlertAt?.let { put("lastFallbackAlertAt", it) }
        put("stuckAlerted", stuckAlerted)
    }

    companion object {
        /** NerdOS has no overheat flag: warn this close to its limit … */
        const val OVERHEAT_MARGIN = 3.0
        /** … for this many polls in a row. As many polls below the limit end the alarm. */
        const val OVERHEAT_POLLS = 3
        /** A flatline needs at least this long without a share, and ten expected share intervals. */
        const val FLATLINE_MINIMUM_MS = 10 * 60_000L
        const val FLATLINE_SHARE_INTERVALS = 10.0
        /** `hashRate_1m` must also stand still this long and over this many polls. */
        const val FROZEN_DURATION_MS = 5 * 60_000L
        const val FROZEN_POLLS = 5
        /** Shares take a while after a restart. */
        const val STARTUP_GRACE_SECONDS = 5 * 60
        /** Uptime has to fall by more than this to count as a restart: two pollers can answer out of order. */
        const val UPTIME_TOLERANCE_SECONDS = 60
        /** On the fallback pool this long in a row … */
        const val FALLBACK_ALARM_MS = 10 * 60_000L
        /** … and with the primary pool answering this long, the miner should have switched back. */
        const val STUCK_ON_FALLBACK_MS = 15 * 60_000L
        /** The watchdog restarts a miner at most this often, then only alarms remain. */
        const val AUTO_RESTART_INTERVAL_MS = 60 * 60_000L
        /** How often the diagnostic log gets the measurements. */
        const val SNAPSHOT_INTERVAL_MS = 15 * 60_000L

        /** How long a healthy miner can go without a share: `difficulty × 2³² / hashrate` per share on average. */
        fun flatlineWindowMs(hashRateGHs: Double, poolDifficulty: Double?): Long {
            if (poolDifficulty == null || poolDifficulty <= 0 || hashRateGHs <= 0) return FLATLINE_MINIMUM_MS
            val intervalSeconds = poolDifficulty * 4_294_967_296.0 / (hashRateGHs * 1e9)
            return max(FLATLINE_MINIMUM_MS, (FLATLINE_SHARE_INTERVALS * intervalSeconds * 1000).toLong())
        }

        fun fromJson(o: JSONObject?): MinerHealth = MinerHealth().apply {
            if (o == null) return@apply
            shares = if (o.has("shares")) o.getInt("shares") else null
            sharesChangedAt = if (o.has("sharesChangedAt")) o.getLong("sharesChangedAt") else null
            hashRate = if (o.has("hashRate")) o.getDouble("hashRate") else null
            hashRateChangedAt = if (o.has("hashRateChangedAt")) o.getLong("hashRateChangedAt") else null
            samePolls = o.optInt("samePolls")
            hotPolls = o.optInt("hotPolls")
            coolPolls = o.optInt("coolPolls")
            isOverheating = o.optBoolean("isOverheating")
            isFlatlining = o.optBoolean("isFlatlining")
            overheatAlerted = o.optBoolean("overheatAlerted")
            lastOverheatAlertAt = if (o.has("lastOverheatAlertAt")) o.getLong("lastOverheatAlertAt") else null
            flatlineAlerted = o.optBoolean("flatlineAlerted")
            lastFlatlineAlertAt = if (o.has("lastFlatlineAlertAt")) o.getLong("lastFlatlineAlertAt") else null
            lastSnapshotAt = if (o.has("lastSnapshotAt")) o.getLong("lastSnapshotAt") else null
            peakStallMs = o.optLong("peakStallMs")
            lastProfile = o.optInt("lastProfile")
            fallbackSince = if (o.has("fallbackSince")) o.getLong("fallbackSince") else null
            primaryUpSince = if (o.has("primaryUpSince")) o.getLong("primaryUpSince") else null
            lastUptime = if (o.has("lastUptime")) o.getInt("lastUptime") else null
            isOnFallback = o.optBoolean("isOnFallback")
            isStuckOnFallback = o.optBoolean("isStuckOnFallback")
            fallbackAlerted = o.optBoolean("fallbackAlerted")
            lastFallbackAlertAt = if (o.has("lastFallbackAlertAt")) o.getLong("lastFallbackAlertAt") else null
            stuckAlerted = o.optBoolean("stuckAlerted")
        }
    }
}
