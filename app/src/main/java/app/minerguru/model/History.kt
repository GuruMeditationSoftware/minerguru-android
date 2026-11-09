package app.minerguru.model

import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil

/** One poll of one miner; only the id, time and state for a miner that did not answer. */
data class HistoryReading(
    val minerId: String,
    val time: Long,
    val isOnline: Boolean,
    val hashRateGHs: Double = 0.0,
    val asicTempC: Double = 0.0,
    val vrTempC: Double? = null,
    val powerWatts: Double = 0.0,
    val fanPercent: Double = 0.0,
    val poolLatencyMs: Double? = null,
    val errorPercent: Double? = null,
    val sharesAccepted: Int = 0,
    val sharesRejected: Int = 0,
    val bestDiff: Double = 0.0
) {
    companion object {
        fun of(miner: MinerDevice, time: Long): HistoryReading? {
            val info = miner.latestInfo
            if (miner.connectionState.isOnline && info != null) {
                return HistoryReading(miner.id, time, true, info.hashRate, info.temp, info.vrTemp, info.power, info.fanSpeedPercent,
                    miner.activePoolLatencyMs, info.errorPercentage, info.sharesAccepted, info.sharesRejected, info.bestSessionDiffNumeric)
            }
            return if (miner.connectionState is ConnectionState.Offline) HistoryReading(miner.id, time, false) else null
        }
    }
}

/** One stored time bucket of a miner: averages over the online minutes, plus sums for shares and minutes. */
data class HistoryPoint(
    val minerId: String,
    val time: Long,
    /** Minutes the miner answered, and minutes MinerGuru was watching it. */
    val onlineMinutes: Int,
    val recordedMinutes: Int,
    val hashRateGHs: Double? = null,
    val asicTempC: Double? = null,
    val maxAsicTempC: Double? = null,
    val vrTempC: Double? = null,
    val powerWatts: Double? = null,
    val fanPercent: Double? = null,
    val poolLatencyMs: Double? = null,
    val errorPercent: Double? = null,
    val sharesAccepted: Int = 0,
    val sharesRejected: Int = 0,
    val bestDiff: Double? = null
) {
    /** J/TH; hashrate is stored in GH/s. */
    val efficiencyJPerTH: Double?
        get() = if (hashRateGHs != null && powerWatts != null && hashRateGHs > 0) powerWatts / (hashRateGHs / 1000) else null

    val energyKWh: Double get() = (powerWatts ?: 0.0) * onlineMinutes / 60 / 1000

    val rejectRate: Double?
        get() = (sharesAccepted + sharesRejected).takeIf { it > 0 }?.let { sharesRejected.toDouble() / it * 100 }
}

enum class HistoryRange(val seconds: Long) {
    DAY(86_400), WEEK(7 * 86_400), MONTH(30 * 86_400), QUARTER(90 * 86_400), YEAR(365 * 86_400);

    val title: String
        get() = when (this) {
            DAY -> L("24 Std.", "24 hrs")
            WEEK -> L("7 Tage", "7 days")
            MONTH -> L("30 Tage", "30 days")
            QUARTER -> L("90 Tage", "90 days")
            YEAR -> L("1 Jahr", "1 year")
        }
}

enum class HistoryMetric {
    HASHRATE, TEMPERATURE, EFFICIENCY, POWER, ERRORS;

    val title: String
        get() = when (this) {
            HASHRATE -> L("Hashrate", "Hashrate")
            TEMPERATURE -> L("Temperatur", "Temperature")
            EFFICIENCY -> L("Effizienz", "Efficiency")
            POWER -> L("Leistung", "Power")
            ERRORS -> L("Fehlerquote", "Error rate")
        }

    fun value(p: HistoryPoint): Double? = when (this) {
        HASHRATE -> p.hashRateGHs
        TEMPERATURE -> p.asicTempC
        EFFICIENCY -> p.efficiencyJPerTH
        POWER -> p.powerWatts
        ERRORS -> p.errorPercent ?: p.rejectRate
    }

    /**
     * Values for a chart that places them by index: every missing step becomes an empty entry, so an offline stretch
     * stays a gap as wide as it lasted.
     */
    fun valuesWithGaps(points: List<HistoryPoint>, stepSeconds: Int): List<Double?> {
        val stepMillis = stepSeconds * 1000L
        val result = ArrayList<Double?>(points.size)
        points.forEachIndexed { i, point ->
            if (i > 0) {
                val distance = point.time - points[i - 1].time
                if (distance > stepMillis * 3) repeat((distance / stepMillis).toInt() - 1) { result.add(null) }
            }
            result.add(value(point))
        }
        return result
    }

    fun format(v: Double): String = when (this) {
        HASHRATE -> MinerFormatting.formatHashrate(v)
        TEMPERATURE -> "${MinerFormatting.format1(v)} °C"
        EFFICIENCY -> "${MinerFormatting.format1(v)} J/TH"
        POWER -> "${MinerFormatting.format1(v)} W"
        ERRORS -> "${MinerFormatting.format2(v)} %"
    }
}

object HistoryQuery {
    /** Charts need no more points than this. */
    const val MAX_POINTS = 720

    /** Bucket size in seconds: at least a minute, an hour beyond two days, and few enough for a chart. */
    fun step(spanSeconds: Long): Int {
        val wanted = ceil(spanSeconds.toDouble() / MAX_POINTS).toInt()
        val base = if (spanSeconds > 2 * 86_400) 3600 else 60
        return maxOf(base, (wanted + base - 1) / base * base)
    }
}

/**
 * Turns polls into per-minute rows. A background poll stands for the time since the last one, up to 15 minutes,
 * so the battery saver mode, which polls four times an hour, still counts whole hours. Pure logic; [toJson] keeps
 * the counters across short-lived worker processes.
 */
class HistoryAggregator(
    private val lastCounters: MutableMap<String, Pair<Int, Int>> = mutableMapOf(),
    private val lastMinutes: MutableMap<String, Long> = mutableMapOf()
) {
    private class Bucket(val minute: Long, val weight: Int) {
        var polls = 0; var onlinePolls = 0
        var hashRate = 0.0; var temp = 0.0; var maxTemp = 0.0; var power = 0.0; var fan = 0.0
        var vr = 0.0; var vrCount = 0; var latency = 0.0; var latencyCount = 0; var error = 0.0; var errorCount = 0
        var accepted = 0; var rejected = 0; var best = 0.0
    }

    private val buckets = mutableMapOf<String, Bucket>()

    /** Adds a poll; returns the row of a minute that just ended. */
    fun add(r: HistoryReading): HistoryPoint? {
        val minute = r.time / 60_000
        var finished: HistoryPoint? = null
        var bucket = buckets[r.minerId]
        if (bucket == null || bucket.minute != minute) {
            bucket?.let { finished = row(r.minerId, it) }
            val weight = lastMinutes[r.minerId]?.let { (minute - it).coerceIn(1, MAX_GAP_MINUTES).toInt() } ?: 1
            lastMinutes[r.minerId] = minute
            bucket = Bucket(minute, weight)
            buckets[r.minerId] = bucket
        }
        bucket.polls++
        if (r.isOnline) {
            bucket.onlinePolls++
            bucket.hashRate += r.hashRateGHs
            bucket.temp += r.asicTempC
            bucket.maxTemp = maxOf(bucket.maxTemp, r.asicTempC)
            r.vrTempC?.takeIf { it > 0 }?.let { bucket.vr += it; bucket.vrCount++ }
            bucket.power += r.powerWatts
            bucket.fan += r.fanPercent
            r.poolLatencyMs?.takeIf { it > 0 }?.let { bucket.latency += it; bucket.latencyCount++ }
            r.errorPercent?.let { bucket.error += it; bucket.errorCount++ }
            bucket.best = maxOf(bucket.best, r.bestDiff)
            // Counters restart with the miner; what it counted since then is new.
            lastCounters[r.minerId]?.let { (accepted, rejected) ->
                bucket.accepted += if (r.sharesAccepted >= accepted) r.sharesAccepted - accepted else r.sharesAccepted
                bucket.rejected += if (r.sharesRejected >= rejected) r.sharesRejected - rejected else r.sharesRejected
            }
            lastCounters[r.minerId] = r.sharesAccepted to r.sharesRejected
        }
        return finished
    }

    /** The running minutes as they stand; a minute that is over is done with. */
    fun flush(now: Long): List<HistoryPoint> {
        val rows = buckets.map { (id, bucket) -> row(id, bucket) }
        val minute = now / 60_000
        buckets.values.removeAll { it.minute < minute }
        return rows
    }

    fun forget(minerId: String) {
        buckets.remove(minerId); lastCounters.remove(minerId); lastMinutes.remove(minerId)
    }

    private fun row(id: String, b: Bucket): HistoryPoint {
        val online = b.onlinePolls > 0
        fun avg(sum: Double, n: Int) = if (n > 0) sum / n else null
        return HistoryPoint(
            id, b.minute * 60_000, if (online) b.weight else 0, b.weight,
            avg(b.hashRate, b.onlinePolls), avg(b.temp, b.onlinePolls), b.maxTemp.takeIf { online },
            avg(b.vr, b.vrCount), avg(b.power, b.onlinePolls), avg(b.fan, b.onlinePolls), avg(b.latency, b.latencyCount),
            avg(b.error, b.errorCount), b.accepted, b.rejected, b.best.takeIf { online && it > 0 }
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("counters", JSONObject().apply { lastCounters.forEach { (k, v) -> put(k, "${v.first},${v.second}") } })
        .put("minutes", JSONObject().apply { lastMinutes.forEach { (k, v) -> put(k, v) } })

    companion object {
        const val MAX_GAP_MINUTES = 15L

        fun fromJson(text: String?): HistoryAggregator {
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return HistoryAggregator()
            val counters = mutableMapOf<String, Pair<Int, Int>>()
            o.optJSONObject("counters")?.let { c ->
                c.keys().forEach { k -> c.getString(k).split(",").let { counters[k] = it[0].toInt() to it[1].toInt() } }
            }
            val minutes = mutableMapOf<String, Long>()
            o.optJSONObject("minutes")?.let { m -> m.keys().forEach { k -> minutes[k] = m.getLong(k) } }
            return HistoryAggregator(counters, minutes)
        }
    }
}

object HistoryCsv {
    private const val HEADER = "time,miner,online_minutes,recorded_minutes,hashrate_ghs,asic_temp_c,max_asic_temp_c,vr_temp_c,power_w,efficiency_j_th,fan_percent,pool_latency_ms,error_percent,shares_accepted,shares_rejected,best_diff"

    /** Machine-readable export: ISO times, dots as decimal separator, empty fields for missing values. */
    fun text(points: List<HistoryPoint>, minerName: (String) -> String): String {
        fun n(v: Double?) = v?.let { String.format(Locale.ROOT, "%.3f", it) } ?: ""
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val rows = points.map { p ->
            listOf(Instant.ofEpochMilli(p.time).toString(), q(minerName(p.minerId)), p.onlineMinutes.toString(), p.recordedMinutes.toString(),
                n(p.hashRateGHs), n(p.asicTempC), n(p.maxAsicTempC), n(p.vrTempC), n(p.powerWatts), n(p.efficiencyJPerTH), n(p.fanPercent),
                n(p.poolLatencyMs), n(p.errorPercent), p.sharesAccepted.toString(), p.sharesRejected.toString(), n(p.bestDiff)).joinToString(",")
        }
        return (listOf(HEADER) + rows).joinToString("\n") + "\n"
    }
}
