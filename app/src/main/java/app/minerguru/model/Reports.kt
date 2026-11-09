package app.minerguru.model

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class ReportPeriod {
    YESTERDAY, LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH, LAST_MONTH;

    val title: String
        get() = when (this) {
            YESTERDAY -> L("Gestern", "Yesterday")
            LAST_7_DAYS -> L("Letzte 7 Tage", "Last 7 days")
            LAST_30_DAYS -> L("Letzte 30 Tage", "Last 30 days")
            THIS_MONTH -> L("Dieser Monat", "This month")
            LAST_MONTH -> L("Letzter Monat", "Last month")
        }

    /** Start and end in milliseconds. */
    fun interval(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun millis(d: java.time.LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val month = date.withDayOfMonth(1)
        return when (this) {
            YESTERDAY -> millis(date.minusDays(1)) to millis(date)
            LAST_7_DAYS -> millis(date.minusDays(7)) to now
            LAST_30_DAYS -> millis(date.minusDays(30)) to now
            THIS_MONTH -> millis(month) to now
            LAST_MONTH -> millis(month.minusMonths(1)) to millis(month)
        }
    }
}

/** Figures of one miner over a report period. */
data class MinerReportRow(
    val id: String,
    val name: String,
    val uptimePercent: Double?,
    val averageHashRateGHs: Double?,
    val energyKWh: Double,
    val cost: Double,
    val efficiencyJPerTH: Double?,
    val averageTempC: Double?,
    val maxTempC: Double?,
    val sharesAccepted: Int,
    val sharesRejected: Int,
    val bestDiff: Double?
)

/** Fleet report built from the hourly history: uptime, output, energy and cost per miner, plus the maintenance done. */
data class MinerReport(
    val start: Long,
    val end: Long,
    val rows: List<MinerReportRow>,
    /** Total fleet hashrate per day: start of the day and GH/s. */
    val daily: List<Pair<Long, Double>>,
    val maintenance: List<MaintenanceRecord>
) {
    val totalEnergyKWh: Double get() = rows.sumOf { it.energyKWh }
    val totalCost: Double get() = rows.sumOf { it.cost }
    val sharesAccepted: Int get() = rows.sumOf { it.sharesAccepted }
    val sharesRejected: Int get() = rows.sumOf { it.sharesRejected }
    val bestDiff: Double? get() = rows.mapNotNull { it.bestDiff }.maxOrNull()
    val maxTempC: Double? get() = rows.mapNotNull { it.maxTempC }.maxOrNull()
    val uptimePercent: Double? get() = rows.mapNotNull { it.uptimePercent }.takeIf { it.isNotEmpty() }?.average()

    /** Sum of the miners' averages. */
    val averageHashRateGHs: Double? get() = rows.mapNotNull { it.averageHashRateGHs }.takeIf { it.isNotEmpty() }?.sum()

    /** Average of the miners' efficiency, weighted by their hashrate. */
    val efficiencyJPerTH: Double?
        get() {
            var watts = 0.0
            var terahashes = 0.0
            rows.forEach { r ->
                val e = r.efficiencyJPerTH ?: return@forEach
                val h = r.averageHashRateGHs ?: return@forEach
                watts += e * h / 1000
                terahashes += h / 1000
            }
            return if (terahashes > 0) watts / terahashes else null
        }

    /** Spreadsheet export, one line per miner. */
    fun csvText(): String {
        fun n(v: Double?, digits: Int = 2) = v?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: ""
        val header = "miner,uptime_percent,avg_hashrate_ghs,energy_kwh,cost,efficiency_j_th,avg_temp_c,max_temp_c,shares_accepted,shares_rejected,best_diff"
        val lines = rows.map { r ->
            listOf("\"${r.name.replace("\"", "\"\"")}\"", n(r.uptimePercent), n(r.averageHashRateGHs), n(r.energyKWh, 3), n(r.cost),
                n(r.efficiencyJPerTH), n(r.averageTempC), n(r.maxTempC), r.sharesAccepted.toString(), r.sharesRejected.toString(), n(r.bestDiff, 0)).joinToString(",")
        }
        return (listOf(header) + lines).joinToString("\n") + "\n"
    }

    companion object {
        /**
         * @param points Hourly history of all miners in the interval.
         * @param minerName Display name of a miner; deleted miners give `null` and are listed under a short id.
         * @param price Energy price per kWh at a time.
         */
        fun build(start: Long, end: Long, points: List<HistoryPoint>, minerName: (String) -> String?, price: (Long) -> Double,
                  maintenance: List<MaintenanceRecord>, zone: ZoneId = ZoneId.systemDefault()): MinerReport {
            val rows = points.groupBy { it.minerId }.map { (id, list) ->
                val online = list.sumOf { it.onlineMinutes }
                val recorded = list.sumOf { it.recordedMinutes }
                fun average(value: (HistoryPoint) -> Double?): Double? {
                    var sum = 0.0
                    var weight = 0.0
                    list.forEach { p ->
                        val v = value(p)
                        if (v != null && p.onlineMinutes > 0) { sum += v * p.onlineMinutes; weight += p.onlineMinutes }
                    }
                    return if (weight > 0) sum / weight else null
                }
                val hashRate = average { it.hashRateGHs }
                val power = average { it.powerWatts }
                MinerReportRow(
                    id, minerName(id) ?: id.take(8), if (recorded > 0) online.toDouble() / recorded * 100 else null, hashRate,
                    list.sumOf { it.energyKWh }, list.sumOf { it.energyKWh * price(it.time) },
                    if (hashRate != null && power != null && hashRate > 0) power / (hashRate / 1000) else null,
                    average { it.asicTempC }, list.mapNotNull { it.maxAsicTempC }.maxOrNull(),
                    list.sumOf { it.sharesAccepted }, list.sumOf { it.sharesRejected }, list.mapNotNull { it.bestDiff }.maxOrNull()
                )
            }.sortedBy { it.name.lowercase() }

            val daily = points.filter { it.onlineMinutes > 0 }
                .groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() }
                .map { (day, list) ->
                    // Average over the hours of the day, per miner, then the sum of the fleet.
                    day to list.groupBy { it.minerId }.values.sumOf { hours -> hours.mapNotNull { it.hashRateGHs }.sum() / hours.size }
                }.sortedBy { it.first }

            return MinerReport(start, end, rows, daily, maintenance.filter { it.doneAt in start until end }.sortedByDescending { it.doneAt })
        }
    }
}
