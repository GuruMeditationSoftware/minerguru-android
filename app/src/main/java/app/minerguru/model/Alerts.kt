package app.minerguru.model

import app.minerguru.R
import org.json.JSONObject

/** Kinds of alerts the user can switch on or off. Later watchdogs add entries here. */
enum class AlertKind {
    OFFLINE,
    MAINTENANCE,
    OVERHEAT,
    FLATLINE,
    FALLBACK,
    RESTART,
    PAYOUT;

    val title: String
        get() = when (this) {
            OFFLINE -> tr(R.string.miner_offline_back_online)
            MAINTENANCE -> tr(R.string.maintenance_due)
            OVERHEAT -> tr(R.string.overheating)
            FLATLINE -> tr(R.string.no_more_shares_flatline)
            FALLBACK -> tr(R.string.fallback_pool_active)
            RESTART -> tr(R.string.unexpected_restart)
            PAYOUT -> tr(R.string.payout_address)
        }
}

/** How the app watches the miners while it is closed. */
enum class BackgroundMode {
    /** Only while the app is open. */
    OFF,
    /** WorkManager about every 15 minutes, battery-friendly. */
    SAVER,
    /** Foreground service every 60 seconds with a permanent notification. */
    INSTANT;

    val title: String
        get() = when (this) {
            OFF -> tr(R.string.only_while_the_app_is)
            SAVER -> tr(R.string.battery_saver_about_every_15)
            INSTANT -> tr(R.string.instant_every_minute_permanent_notification)
        }
}

/** One message for the user. Only alarms, never news or advertising. */
data class AlertEvent(
    val kind: AlertKind,
    val minerId: String,
    val title: String,
    val body: String,
    /** `true` for the all-clear after an alarm. */
    val isRecovery: Boolean,
    /** Who it is about, for a message that names several. */
    val minerName: String = "",
    /** Says what happened to one miner, so it is never folded into a message about several. */
    val standsAlone: Boolean = false
) {
    companion object {
        /**
         * One message per kind and direction for the alerts of one poll, so a power cut does not send one mail
         * per miner. A combined message has no single miner: its [minerId] is empty.
         */
        fun combine(events: List<AlertEvent>): List<AlertEvent> {
            val (alone, foldable) = events.partition { it.standsAlone }
            return foldable.groupBy { it.kind to it.isRecovery }.map { (key, group) ->
                if (group.size == 1) group[0]
                else AlertEvent(key.first, "", combinedTitle(key.first, key.second, group.size), group.map { it.minerName }.distinct().joinToString(", "), key.second)
            } + alone
        }

        private fun combinedTitle(kind: AlertKind, recovery: Boolean, count: Int): String = when (kind) {
            AlertKind.MAINTENANCE -> tr(R.string.maintenance_tasks_are_due, count)
            AlertKind.OVERHEAT -> if (recovery) tr(R.string.miners_have_cooled_down, count)
            else tr(R.string.miners_are_overheating, count)
            AlertKind.FLATLINE -> if (recovery) tr(R.string.miners_are_delivering_shares_again, count)
            else tr(R.string.miners_have_stopped_delivering_shares, count)
            AlertKind.FALLBACK -> if (recovery) tr(R.string.miners_are_back_on_the, count)
            else tr(R.string.miners_are_on_the_fallback, count)
            AlertKind.RESTART -> tr(R.string.miners_restarted, count)
            AlertKind.PAYOUT -> tr(R.string.payout_address_warnings, count)
            AlertKind.OFFLINE -> if (recovery) tr(R.string.miners_are_back_online, count)
            else tr(R.string.miners_are_offline, count)
        }
    }
}

/** What the evaluator needs from the user's settings. */
data class WatchSettings(
    val expectedAddresses: Map<String, String> = emptyMap(),
    /** Off by default: restart a miner without shares, at most once an hour. */
    val restartsOnFlatline: Boolean = false,
    /** Off by default: restart a miner that stays on the fallback pool although the primary one answers. */
    val restartsToPrimary: Boolean = false
)

/**
 * Turns poll results into alerts (overheating and flatline: see [MinerHealth]): offline after [OFFLINE_FAILED_POLLS] failed polls spanning at least
 * [OFFLINE_MIN_DURATION_MS], all-clear when back online, the same alarm at most every [REPEAT_INTERVAL_MS].
 * Pure logic; the state survives process death as JSON because background polls run in short-lived workers.
 */
class AlertEvaluator(private val tracks: MutableMap<String, Track> = mutableMapOf()) {
    data class Track(
        val failedPolls: Int = 0,
        val firstFailureAt: Long? = null,
        /** An offline alarm went out and the all-clear is still due. */
        val offlineAlerted: Boolean = false,
        val lastOfflineAlertAt: Long? = null,
        val health: MinerHealth = MinerHealth(),
        val wasOnline: Boolean? = null,
        val payoutMissPolls: Int = 0,
        val payoutMissAlerted: Boolean = false,
        /** A due restart was left out because of read-only mode; said once an hour at most. */
        val lastSkippedRestartAt: Long? = null,
        /** The address last seen in each slot, to notice changes made elsewhere. */
        val seenAddresses: Map<String, String> = emptyMap(),
        /** The address each slot was last warned about. */
        val alertedAddresses: Map<String, String> = emptyMap()
    )

    /** Restarts the last [evaluate] found due; the caller carries them out. */
    var dueRestarts: List<Pair<MinerDevice, MinerHealth.AutoRestartReason>> = emptyList()
        private set

    /** Returns every alert that is due, before muting and settings are applied. */
    fun evaluate(miners: List<MinerDevice>, now: Long, settings: WatchSettings = WatchSettings(), log: ChangeLog = ChangeLog.shared): List<AlertEvent> {
        val events = mutableListOf<AlertEvent>()
        val restarts = mutableListOf<Pair<MinerDevice, MinerHealth.AutoRestartReason>>()
        for (miner in miners) {
            var track = tracks[miner.id] ?: Track()
            track = logState(miner, track, now)
            when (miner.connectionState) {
                is ConnectionState.Offline -> {
                    val firstFailure = track.firstFailureAt ?: now
                    track = track.copy(failedPolls = track.failedPolls + 1, firstFailureAt = firstFailure)
                    val longEnough = now - firstFailure >= OFFLINE_MIN_DURATION_MS
                    val notRepeated = track.lastOfflineAlertAt?.let { now - it >= REPEAT_INTERVAL_MS } ?: true
                    if (!track.offlineAlerted && track.failedPolls >= OFFLINE_FAILED_POLLS && longEnough && notRepeated) {
                        track = track.copy(offlineAlerted = true, lastOfflineAlertAt = now)
                        val minutes = (now - firstFailure) / 60_000
                        events += AlertEvent(
                            AlertKind.OFFLINE, miner.id,
                            tr(R.string.is_offline, miner.displayName),
                            tr(R.string.no_response_for_min_it, minutes),
                            isRecovery = false
                        )
                    }
                }
                ConnectionState.Online -> {
                    if (track.offlineAlerted) {
                        events += AlertEvent(
                            AlertKind.OFFLINE, miner.id,
                            tr(R.string.is_back_online, miner.displayName),
                            tr(R.string.the_miner_is_responding_again),
                            isRecovery = true
                        )
                    }
                    track = track.copy(failedPolls = 0, firstFailureAt = null, offlineAlerted = false)
                    miner.latestInfo?.let { info ->
                        val restartExpected = log.lastRestart(miner.ipAddress)?.let { now - it < OWN_RESTART_WINDOW_MS } ?: false
                        track.health.update(info, now, miner.poolLatency?.primaryPoolDirectMs != null, restartExpected)
                        events += healthEvents(miner, info, track.health, now)
                        val (updated, payout) = payoutEvents(miner, info, track, settings, log, now)
                        track = updated
                        events += payout
                        logHealth(miner, info, track.health, now)
                        val lastRestart = listOfNotNull(log.lastAutomaticRestart(miner.ipAddress), track.lastSkippedRestartAt).maxOrNull()
                        track.health.dueAutoRestart(
                            settings.restartsOnFlatline, settings.restartsToPrimary, lastRestart, now
                        )?.let { reason ->
                            if (log.isReadOnly) {
                                track = track.copy(lastSkippedRestartAt = now)
                                events += skippedRestartEvent(miner, reason)
                            } else {
                                restarts += miner to reason
                            }
                        }
                    }
                }
                else -> Unit
            }
            tracks[miner.id] = track
        }
        dueRestarts = restarts
        return events.map { event -> event.copy(minerName = miners.firstOrNull { it.id == event.minerId }?.displayName.orEmpty()) }
    }

    /** Read-only mode wins over the watchdog, but the user should know a restart was left out. */
    private fun skippedRestartEvent(miner: MinerDevice, reason: MinerHealth.AutoRestartReason) = AlertEvent(
        kind = if (reason == MinerHealth.AutoRestartReason.FLATLINE) AlertKind.FLATLINE else AlertKind.FALLBACK,
        minerId = miner.id,
        title = tr(R.string.was_not_restarted, miner.displayName),
        body = tr(R.string.an_automatic_restart_was_due),
        isRecovery = false,
        minerName = miner.displayName,
        standsAlone = true
    )

    /** Default or foreign addresses, addresses changed outside MinerGuru and blocks that skip the miner. */
    private fun payoutEvents(
        miner: MinerDevice, info: MinerSystemInfo, track: Track, settings: WatchSettings, log: ChangeLog, now: Long
    ): Pair<Track, List<AlertEvent>> {
        val events = mutableListOf<AlertEvent>()
        val name = miner.displayName
        val ownChange = log.lastWrite(miner.ipAddress)?.let { now - it < OWN_CHANGE_WINDOW_MS } ?: false
        val seen = track.seenAddresses.toMutableMap()
        val alerted = track.alertedAddresses.toMutableMap()
        for (slot in PayoutCheck.slots(info, settings.expectedAddresses)) {
            if (slot.address.isEmpty()) continue
            val previous = seen[slot.key]
            seen[slot.key] = slot.address
            if (previous != null && previous != slot.address) {
                alerted[slot.key] = slot.address
                if (!ownChange) {
                    val old = PayoutCheck.shortened(previous)
                    val new = PayoutCheck.shortened(slot.address)
                    events += AlertEvent(
                        AlertKind.PAYOUT, miner.id,
                        tr(R.string.address_of_was_changed, name),
                        tr(R.string.this_did_not_come_from, slot.title, old, new),
                        isRecovery = false
                    )
                }
            } else if (slot.verdict.needsAttention && alerted[slot.key] != slot.address) {
                alerted[slot.key] = slot.address
                val short = PayoutCheck.shortened(slot.address)
                events += AlertEvent(
                    AlertKind.PAYOUT, miner.id,
                    if (slot.verdict == PayoutVerdict.FACTORY_DEFAULT) tr(R.string.uses_a_default_address, name)
                    else tr(R.string.pays_to_a_foreign_address, name),
                    tr(R.string.a_block_found_would_not, slot.title, short),
                    isRecovery = false
                )
            }
        }
        val misses = if (PayoutCheck.blockSkipsMiner(info)) track.payoutMissPolls + 1 else 0
        var missAlerted = misses > 0 && track.payoutMissAlerted
        if (misses >= PAYOUT_MISS_POLLS && !missAlerted) {
            missAlerted = true
            events += AlertEvent(
                AlertKind.PAYOUT, miner.id,
                tr(R.string.block_does_not_pay, name),
                tr(R.string.the_pool_s_block_template),
                isRecovery = false
            )
        }
        return track.copy(payoutMissPolls = misses, payoutMissAlerted = missAlerted, seenAddresses = seen, alertedAddresses = alerted) to events
    }

    /** The result of a watchdog restart, as a message. Noted in the change log like any other write. */
    fun restartEvent(miner: MinerDevice, reason: MinerHealth.AutoRestartReason, error: String?): AlertEvent {
        val name = miner.displayName
        val why = if (reason == MinerHealth.AutoRestartReason.FLATLINE) tr(R.string.no_more_shares)
        else tr(R.string.the_primary_pool_is_reachable)
        val kind = if (reason == MinerHealth.AutoRestartReason.FLATLINE) AlertKind.FLATLINE else AlertKind.FALLBACK
        return if (error == null) AlertEvent(kind, miner.id, tr(R.string.was_restarted_automatically, name), why, false, name, true)
        else AlertEvent(kind, miner.id, tr(R.string.could_not_be_restarted, name), "$why $error", false, name, true)
    }

    /** A new expected address makes earlier warnings worth repeating. */
    fun forgetPayoutAlerts() {
        tracks.replaceAll { _, track -> track.copy(alertedAddresses = emptyMap()) }
    }

    private fun logState(miner: MinerDevice, track: Track, now: Long): Track {
        val state = miner.connectionState
        return when {
            state is ConnectionState.Offline && track.wasOnline != false -> {
                DiagnosticLog.shared.write("state", "${miner.ipAddress} offline: ${state.reason}", now)
                track.copy(wasOnline = false)
            }
            state == ConnectionState.Online && track.wasOnline != true -> {
                DiagnosticLog.shared.write("state", "${miner.ipAddress} online", now)
                track.copy(wasOnline = true)
            }
            else -> track
        }
    }

    private fun logHealth(miner: MinerDevice, info: MinerSystemInfo, health: MinerHealth, now: Long) {
        val snapshot = health.snapshot(info, now) ?: return
        DiagnosticLog.shared.write("health", "${miner.ipAddress} $snapshot", now)
        val profile = DiagnosticLog.profile(miner, info)
        if (profile.hashCode() != health.lastProfile) {
            health.lastProfile = profile.hashCode()
            DiagnosticLog.shared.write("miner", "${miner.ipAddress} $profile", now)
        }
    }

    private fun healthEvents(miner: MinerDevice, info: MinerSystemInfo, health: MinerHealth, now: Long): List<AlertEvent> {
        val events = mutableListOf<AlertEvent>()
        val name = miner.displayName
        if (health.isOverheating != health.overheatAlerted) {
            val due = health.lastOverheatAlertAt?.let { now - it >= REPEAT_INTERVAL_MS } ?: true
            if (health.overheatAlerted) {
                health.overheatAlerted = false
                events += AlertEvent(
                    AlertKind.OVERHEAT, miner.id,
                    tr(R.string.has_cooled_down, name),
                    tr(R.string.the_temperature_is_back_to),
                    isRecovery = true
                )
            } else if (due) {
                health.overheatAlerted = true
                health.lastOverheatAlertAt = now
                val temp = info.temp.toInt()
                val vr = (info.vrTemp ?: 0.0).toInt()
                val limit = (info.overheatTemp ?: 0.0).toInt()
                events += AlertEvent(
                    AlertKind.OVERHEAT, miner.id,
                    tr(R.string.is_overheating, name),
                    if (info.overheatMode) tr(R.string.the_miner_has_throttled_itself)
                    else tr(R.string.asic_c_voltage_regulator_c, temp, vr, limit),
                    isRecovery = false
                )
            }
        }
        if (health.isFlatlining != health.flatlineAlerted) {
            val due = health.lastFlatlineAlertAt?.let { now - it >= REPEAT_INTERVAL_MS } ?: true
            if (health.flatlineAlerted) {
                health.flatlineAlerted = false
                events += AlertEvent(
                    AlertKind.FLATLINE, miner.id,
                    tr(R.string.is_delivering_shares_again, name),
                    tr(R.string.new_shares_are_arriving_again),
                    isRecovery = true
                )
            } else if (due) {
                health.flatlineAlerted = true
                health.lastFlatlineAlertAt = now
                val minutes = health.minutesWithoutShares(now)
                events += AlertEvent(
                    AlertKind.FLATLINE, miner.id,
                    tr(R.string.has_stopped_delivering_shares, name),
                    tr(R.string.no_new_share_for_min, minutes),
                    isRecovery = false
                )
            }
        }
        if (health.isOnFallback != health.fallbackAlerted) {
            val due = health.lastFallbackAlertAt?.let { now - it >= REPEAT_INTERVAL_MS } ?: true
            if (health.fallbackAlerted) {
                health.fallbackAlerted = false
                events += AlertEvent(
                    AlertKind.FALLBACK, miner.id,
                    tr(R.string.is_back_on_the_primary, name),
                    tr(R.string.the_miner_is_using_its),
                    isRecovery = true
                )
            } else if (due) {
                health.fallbackAlerted = true
                health.lastFallbackAlertAt = now
                events += AlertEvent(
                    AlertKind.FALLBACK, miner.id,
                    tr(R.string.is_on_the_fallback_pool, name),
                    tr(R.string.on_for_at_least_10, info.activePoolHost),
                    isRecovery = false
                )
            }
        }
        if (health.isStuckOnFallback && !health.stuckAlerted) {
            health.stuckAlerted = true
            events += AlertEvent(
                AlertKind.FALLBACK, miner.id,
                tr(R.string.does_not_switch_back, name),
                tr(R.string.the_primary_pool_has_been),
                isRecovery = false
            )
        } else if (!health.isOnFallback) {
            health.stuckAlerted = false
        }
        if (health.restartedUnexpectedly) {
            val reason = info.resetReason?.let { tr(R.string.reason_given_by_the_miner, it) }
                ?: tr(R.string.the_miner_gives_no_reason)
            events += AlertEvent(
                AlertKind.RESTART, miner.id,
                tr(R.string.restarted, name),
                tr(R.string.minerguru_did_not_trigger_this, reason),
                isRecovery = false
            )
        }
        return events
    }

    fun forget(minerId: String) {
        tracks.remove(minerId)
    }

    fun toJson(): String = JSONObject().apply {
        tracks.forEach { (id, t) ->
            put(id, JSONObject().apply {
                put("failedPolls", t.failedPolls)
                t.firstFailureAt?.let { put("firstFailureAt", it) }
                put("offlineAlerted", t.offlineAlerted)
                t.lastOfflineAlertAt?.let { put("lastOfflineAlertAt", it) }
                put("health", t.health.toJson())
                t.wasOnline?.let { put("wasOnline", it) }
                put("payoutMissPolls", t.payoutMissPolls)
                put("payoutMissAlerted", t.payoutMissAlerted)
                put("seenAddresses", JSONObject(t.seenAddresses))
                put("alertedAddresses", JSONObject(t.alertedAddresses))
                // Without it, read-only mode warned about the skipped restart at every poll.
                t.lastSkippedRestartAt?.let { put("lastSkippedRestartAt", it) }
            })
        }
    }.toString()

    companion object {
        const val OFFLINE_FAILED_POLLS = 3
        /** A manual restart takes 10–20 s and must not count as offline. */
        const val OFFLINE_MIN_DURATION_MS = 90_000L
        const val REPEAT_INTERVAL_MS = 30 * 60_000L
        /** A changed address this soon after a write from MinerGuru was the user's own change. */
        const val OWN_CHANGE_WINDOW_MS = 15 * 60_000L
        /** Restarts this soon after one from MinerGuru are expected. */
        const val OWN_RESTART_WINDOW_MS = 10 * 60_000L
        /** The miner has to report a block without its address this many polls in a row. */
        const val PAYOUT_MISS_POLLS = 3

        private fun strings(o: JSONObject?): Map<String, String> =
            o?.keys()?.asSequence()?.associateWith { o.getString(it) }.orEmpty()

        fun fromJson(text: String?): AlertEvaluator {
            val o = runCatching { JSONObject(text ?: return AlertEvaluator()) }.getOrNull() ?: return AlertEvaluator()
            val map = mutableMapOf<String, Track>()
            o.keys().forEach { id ->
                val t = o.getJSONObject(id)
                map[id] = Track(
                    failedPolls = t.optInt("failedPolls"),
                    firstFailureAt = if (t.has("firstFailureAt")) t.getLong("firstFailureAt") else null,
                    offlineAlerted = t.optBoolean("offlineAlerted"),
                    lastOfflineAlertAt = if (t.has("lastOfflineAlertAt")) t.getLong("lastOfflineAlertAt") else null,
                    health = MinerHealth.fromJson(t.optJSONObject("health")),
                    wasOnline = if (t.has("wasOnline")) t.getBoolean("wasOnline") else null,
                    payoutMissPolls = t.optInt("payoutMissPolls"),
                    payoutMissAlerted = t.optBoolean("payoutMissAlerted"),
                    seenAddresses = strings(t.optJSONObject("seenAddresses")),
                    alertedAddresses = strings(t.optJSONObject("alertedAddresses")),
                    lastSkippedRestartAt = if (t.has("lastSkippedRestartAt")) t.getLong("lastSkippedRestartAt") else null
                )
            }
            return AlertEvaluator(map)
        }
    }
}
