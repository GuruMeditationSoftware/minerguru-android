package app.minerguru.model

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
            OFFLINE -> L("Miner offline / wieder online", "Miner offline / back online")
            MAINTENANCE -> L("Wartung fällig", "Maintenance due")
            OVERHEAT -> L("Überhitzung", "Overheating")
            FLATLINE -> L("Keine Shares mehr (Flatline)", "No more shares (flatline)")
            FALLBACK -> L("Fallback-Pool aktiv", "Fallback pool active")
            RESTART -> L("Unerwarteter Neustart", "Unexpected restart")
            PAYOUT -> L("Auszahlungsadresse", "Payout address")
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
            OFF -> L("Nur bei geöffneter App", "Only while the app is open")
            SAVER -> L("Sparsam (etwa alle 15 min)", "Battery saver (about every 15 min)")
            INSTANT -> L("Sofort (jede Minute, dauerhafte Mitteilung)", "Instant (every minute, permanent notification)")
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
            AlertKind.MAINTENANCE -> L("$count Wartungen sind fällig", "$count maintenance tasks are due")
            AlertKind.OVERHEAT -> if (recovery) L("$count Miner sind wieder kühl", "$count miners have cooled down")
            else L("$count Miner sind überhitzt", "$count miners are overheating")
            AlertKind.FLATLINE -> if (recovery) L("$count Miner liefern wieder Shares", "$count miners are delivering shares again")
            else L("$count Miner liefern keine Shares mehr", "$count miners have stopped delivering shares")
            AlertKind.FALLBACK -> if (recovery) L("$count Miner sind zurück auf dem Primär-Pool", "$count miners are back on the primary pool")
            else L("$count Miner laufen auf dem Fallback-Pool", "$count miners are on the fallback pool")
            AlertKind.RESTART -> L("$count Miner sind neu gestartet", "$count miners restarted")
            AlertKind.PAYOUT -> L("$count Warnungen zur Auszahlungsadresse", "$count payout address warnings")
            AlertKind.OFFLINE -> if (recovery) L("$count Miner sind wieder online", "$count miners are back online")
            else L("$count Miner sind offline", "$count miners are offline")
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
                            L("${miner.displayName} ist offline", "${miner.displayName} is offline"),
                            L("Keine Antwort seit $minutes min. Vielleicht ohne Strom, außer WLAN-Reichweite oder im Hotspot-Modus.",
                                "No response for $minutes min. It may have lost power, be out of Wi-Fi range or be in hotspot mode."),
                            isRecovery = false
                        )
                    }
                }
                ConnectionState.Online -> {
                    if (track.offlineAlerted) {
                        events += AlertEvent(
                            AlertKind.OFFLINE, miner.id,
                            L("${miner.displayName} ist wieder online", "${miner.displayName} is back online"),
                            L("Der Miner antwortet wieder.", "The miner is responding again."),
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
        title = L("${miner.displayName} wurde nicht neu gestartet", "${miner.displayName} was not restarted"),
        body = L("Ein automatischer Neustart war fällig, aber „Nur lesen“ ist an.", "An automatic restart was due, but read-only mode is on."),
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
                        L("Adresse von $name wurde geändert", "Address of $name was changed"),
                        L("${slot.title}: $old → $new. Das kam nicht von MinerGuru.", "${slot.title}: $old → $new. This did not come from MinerGuru."),
                        isRecovery = false
                    )
                }
            } else if (slot.verdict.needsAttention && alerted[slot.key] != slot.address) {
                alerted[slot.key] = slot.address
                val short = PayoutCheck.shortened(slot.address)
                events += AlertEvent(
                    AlertKind.PAYOUT, miner.id,
                    if (slot.verdict == PayoutVerdict.FACTORY_DEFAULT) L("$name nutzt eine Standard-Adresse", "$name uses a default address")
                    else L("$name zahlt an eine fremde Adresse", "$name pays to a foreign address"),
                    L("${slot.title}: $short. Ein Fund ginge nicht an dich.", "${slot.title}: $short. A block found would not go to you."),
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
                L("Block zahlt nicht an $name", "Block does not pay $name"),
                L("Die Block-Vorlage des Pools enthält die Adresse des Miners nicht. Prüfe die Pool-Einstellungen.",
                    "The pool's block template does not contain the miner's address. Check the pool settings."),
                isRecovery = false
            )
        }
        return track.copy(payoutMissPolls = misses, payoutMissAlerted = missAlerted, seenAddresses = seen, alertedAddresses = alerted) to events
    }

    /** The result of a watchdog restart, as a message. Noted in the change log like any other write. */
    fun restartEvent(miner: MinerDevice, reason: MinerHealth.AutoRestartReason, error: String?): AlertEvent {
        val name = miner.displayName
        val why = if (reason == MinerHealth.AutoRestartReason.FLATLINE) L("Keine Shares mehr.", "No more shares.")
        else L("Der Primär-Pool ist wieder erreichbar, der Miner blieb aber auf dem Fallback-Pool.",
            "The primary pool is reachable again, but the miner stayed on the fallback pool.")
        val kind = if (reason == MinerHealth.AutoRestartReason.FLATLINE) AlertKind.FLATLINE else AlertKind.FALLBACK
        return if (error == null) AlertEvent(kind, miner.id, L("$name wurde automatisch neu gestartet", "$name was restarted automatically"), why, false, name, true)
        else AlertEvent(kind, miner.id, L("$name ließ sich nicht neu starten", "$name could not be restarted"), "$why $error", false, name, true)
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
                    L("$name ist wieder kühl", "$name has cooled down"),
                    L("Die Temperatur ist wieder im normalen Bereich.", "The temperature is back to normal."),
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
                    L("$name ist überhitzt", "$name is overheating"),
                    if (info.overheatMode) L("Der Miner hat sich gedrosselt und bleibt so bis zum Neustart. Prüfe vorher die Kühlung.",
                        "The miner has throttled itself and stays that way until it restarts. Check the cooling first.")
                    else L("ASIC $temp °C, Spannungsregler $vr °C, Grenze $limit °C. Prüfe die Kühlung.",
                        "ASIC $temp °C, voltage regulator $vr °C, limit $limit °C. Check the cooling."),
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
                    L("$name liefert wieder Shares", "$name is delivering shares again"),
                    L("Es kommen wieder neue Shares an.", "New shares are arriving again."),
                    isRecovery = true
                )
            } else if (due) {
                health.flatlineAlerted = true
                health.lastFlatlineAlertAt = now
                val minutes = health.minutesWithoutShares(now)
                events += AlertEvent(
                    AlertKind.FLATLINE, miner.id,
                    L("$name liefert keine Shares mehr", "$name has stopped delivering shares"),
                    L("Seit $minutes min kein neuer Share, obwohl der Miner antwortet. Ein Neustart hilft meist.",
                        "No new share for $minutes min although the miner responds. A restart usually helps."),
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
                    L("$name ist zurück auf dem Primär-Pool", "$name is back on the primary pool"),
                    L("Der Miner nutzt wieder seinen Primär-Pool.", "The miner is using its primary pool again."),
                    isRecovery = true
                )
            } else if (due) {
                health.fallbackAlerted = true
                health.lastFallbackAlertAt = now
                events += AlertEvent(
                    AlertKind.FALLBACK, miner.id,
                    L("$name läuft auf dem Fallback-Pool", "$name is on the fallback pool"),
                    L("Seit mindestens 10 min auf ${info.activePoolHost}. Der Primär-Pool ist vielleicht gestört.",
                        "On ${info.activePoolHost} for at least 10 min. The primary pool may be down."),
                    isRecovery = false
                )
            }
        }
        if (health.isStuckOnFallback && !health.stuckAlerted) {
            health.stuckAlerted = true
            events += AlertEvent(
                AlertKind.FALLBACK, miner.id,
                L("$name wechselt nicht zurück", "$name does not switch back"),
                L("Der Primär-Pool ist seit 15 min erreichbar, der Miner bleibt aber auf dem Fallback-Pool. Ein Neustart holt ihn zurück.",
                    "The primary pool has been reachable for 15 min, but the miner stays on the fallback pool. A restart brings it back."),
                isRecovery = false
            )
        } else if (!health.isOnFallback) {
            health.stuckAlerted = false
        }
        if (health.restartedUnexpectedly) {
            val reason = info.resetReason?.let { L("Grund laut Miner: $it.", "Reason given by the miner: $it.") }
                ?: L("Der Miner nennt keinen Grund.", "The miner gives no reason.")
            events += AlertEvent(
                AlertKind.RESTART, miner.id,
                L("$name ist neu gestartet", "$name restarted"),
                L("MinerGuru hat das nicht ausgelöst. $reason", "MinerGuru did not trigger this. $reason"),
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
