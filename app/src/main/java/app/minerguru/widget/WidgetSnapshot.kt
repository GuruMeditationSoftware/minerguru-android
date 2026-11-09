package app.minerguru.widget

import android.content.Context
import app.minerguru.model.ConnectionState
import app.minerguru.model.FleetCoinMiningSummary
import app.minerguru.model.HistoryPoint
import app.minerguru.model.MinerDevice
import app.minerguru.model.SoloChanceTimeframe
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the home-screen widgets show: one JSON file, rewritten by the app and by the background monitor
 * after each poll. The widgets only read it, so drawing them never waits for the network.
 */
data class WidgetSnapshot(
    val time: Long,
    val miners: List<Miner>,
    val tempWarningC: Double,
    /** Fleet hashrate per hour over the last 24 hours, oldest first. */
    val fleetHourlyGHs: List<Double>,
    val bestDiff: String?,
    /** Solo odds of every coin the fleet mines, Bitcoin first, then by hashrate. */
    val solo: List<Solo>
) {
    data class Miner(
        val id: String,
        val name: String,
        val model: String,
        val host: String,
        val state: State,
        val hashRateGHs: Double,
        val tempC: Double?,
        val powerW: Double?
    ) {
        enum class State { ONLINE, OFFLINE, CHECKING }
    }

    /** Solo odds of one coin, already formatted; [key] is the [app.minerguru.model.MinedCoin] name. */
    data class Solo(val key: String, val coin: String, val odds: String, val period: String, val blockEvery: String)

    val online: List<Miner> get() = miners.filter { it.state == Miner.State.ONLINE }
    val offline: List<Miner> get() = miners.filter { it.state == Miner.State.OFFLINE }
    val hot: List<Miner> get() = online.filter { (it.tempC ?: 0.0) >= tempWarningC }
    val totalHashRateGHs: Double get() = online.sumOf { it.hashRateGHs }
    val totalPowerW: Double get() = online.sumOf { it.powerW ?: 0.0 }
    val efficiencyJPerTH: Double?
        get() {
            val th = totalHashRateGHs / 1000
            return if (th > 0.01 && totalPowerW > 0) totalPowerW / th else null
        }

    fun isHot(m: Miner) = m.state == Miner.State.ONLINE && (m.tempC ?: 0.0) >= tempWarningC

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("time", time)
        put("tempWarningC", tempWarningC)
        put("fleetHourlyGHs", JSONArray(fleetHourlyGHs))
        bestDiff?.let { put("bestDiff", it) }
        put("solo", JSONArray(solo.map {
            JSONObject().put("key", it.key).put("coin", it.coin).put("odds", it.odds).put("period", it.period).put("blockEvery", it.blockEvery)
        }))
        put("miners", JSONArray(miners.map { m ->
            JSONObject().put("id", m.id).put("name", m.name).put("model", m.model).put("host", m.host)
                .put("state", m.state.name).put("hashRateGHs", m.hashRateGHs)
                .also { o -> m.tempC?.let { o.put("tempC", it) }; m.powerW?.let { o.put("powerW", it) } }
        }))
    }

    companion object {
        const val VERSION = 2
        private const val FILE = "widgets/snapshot.json"

        val EMPTY = WidgetSnapshot(0, emptyList(), 68.0, emptyList(), null, emptyList())

        fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

        fun load(context: Context): WidgetSnapshot? = runCatching { fromJson(JSONObject(file(context).readText())) }.getOrNull()

        fun write(context: Context, snapshot: WidgetSnapshot) {
            val target = file(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(snapshot.toJson().toString())
            tmp.renameTo(target)
        }

        fun fromJson(o: JSONObject): WidgetSnapshot? {
            if (o.optInt("version") != VERSION) return null
            val miners = o.getJSONArray("miners").let { arr ->
                (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    Miner(
                        id = m.getString("id"), name = m.getString("name"), model = m.optString("model"), host = m.optString("host"),
                        state = runCatching { Miner.State.valueOf(m.getString("state")) }.getOrDefault(Miner.State.CHECKING),
                        hashRateGHs = m.optDouble("hashRateGHs", 0.0),
                        tempC = m.optDouble("tempC").takeIf { !it.isNaN() },
                        powerW = m.optDouble("powerW").takeIf { !it.isNaN() }
                    )
                }
            }
            val hourly = o.optJSONArray("fleetHourlyGHs")?.let { arr -> (0 until arr.length()).map { arr.getDouble(it) } } ?: emptyList()
            val solo = o.optJSONArray("solo")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val it = arr.getJSONObject(i)
                    Solo(it.optString("key"), it.getString("coin"), it.getString("odds"), it.getString("period"), it.getString("blockEvery"))
                }
            } ?: emptyList()
            return WidgetSnapshot(
                time = o.getLong("time"), miners = miners, tempWarningC = o.optDouble("tempWarningC", 68.0),
                fleetHourlyGHs = hourly, bestDiff = o.optString("bestDiff").takeIf { it.isNotEmpty() }, solo = solo
            )
        }

        /** The snapshot of a poll: [miners] as polled, [hourly] from the history database. */
        fun of(
            miners: List<MinerDevice>, tempWarningC: Double, hourly: List<Double>,
            timeframe: SoloChanceTimeframe, now: Long = System.currentTimeMillis()
        ): WidgetSnapshot {
            val rows = miners.map { d ->
                val state = when (d.connectionState) {
                    is ConnectionState.Online -> if (d.latestInfo != null) Miner.State.ONLINE else Miner.State.CHECKING
                    is ConnectionState.Offline -> Miner.State.OFFLINE
                    else -> Miner.State.CHECKING
                }
                val info = d.latestInfo
                Miner(
                    id = d.id, name = d.displayName, model = d.hardwareTitle, host = d.ipAddress, state = state,
                    hashRateGHs = if (state == Miner.State.ONLINE) info?.hashRate ?: 0.0 else 0.0,
                    tempC = info?.temp?.takeIf { it > 0 }, powerW = info?.power?.takeIf { it > 0 }
                )
            }
            val online = miners.filter { it.connectionState.isOnline && it.latestInfo != null }
            val solo = FleetCoinMiningSummary.summaries(online).filter { it.totalHashrateGHs > 0 }.map {
                Solo(it.coin.name, it.coin.symbol.uppercase(), it.formattedOddsRatio(timeframe), timeframe.label, it.expectedTimePerBlockFormatted)
            }
            val best = online.maxByOrNull { it.latestInfo?.bestDiffNumeric ?: 0.0 }?.latestInfo?.bestDiffFormatted
            return WidgetSnapshot(now, rows, tempWarningC, hourly, best?.takeIf { online.isNotEmpty() }, solo)
        }

        /** Sum of the miners' hourly averages, weighted by the share of the hour each was online. */
        fun fleetHourly(points: List<HistoryPoint>): List<Double> {
            val byHour = sortedMapOf<Long, Double>()
            for (p in points) {
                val rate = p.hashRateGHs ?: continue
                if (p.recordedMinutes <= 0) continue
                byHour[p.time] = (byHour[p.time] ?: 0.0) + rate * p.onlineMinutes / p.recordedMinutes
            }
            return byHour.values.toList().takeLast(24)
        }
    }
}
