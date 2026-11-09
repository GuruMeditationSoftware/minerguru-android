package app.minerguru.model

import androidx.annotation.StringRes
import app.minerguru.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MinerLogLevel(val raw: String, val shortLabel: String) {
    INFO("INFO", "I"), WARNING("WARN", "W"), ERROR("ERROR", "E"), DEBUG("DEBUG", "D")
}

enum class LogCategoryFilter(@StringRes private val labelRes: Int) {
    ALL(R.string.all),
    SHARES_AND_POOL(R.string.shares_pool),
    ASIC_AND_HARDWARE(R.string.asic_hardware),
    POWER_AND_THERMAL(R.string.power_cooling),
    WARNINGS_AND_ERRORS(R.string.warnings_errors),
    SYSTEM_AND_NETWORK(R.string.system_network);

    val label: String get() = tr(labelRes)
}

/** A single structured log line from a Bitaxe or NerdQAxe++ miner. */
data class MinerLogEntry(
    val receivedAt: Long,
    val espTimestamp: String?,
    val level: MinerLogLevel,
    val componentTag: String?,
    val message: String,
    val rawLine: String,
    val isShareAccepted: Boolean,
    val isShareRejected: Boolean
) {
    private fun haystack() = "${componentTag ?: ""} $message".lowercase()

    fun matches(category: LogCategoryFilter): Boolean {
        val h = haystack()
        return when (category) {
            LogCategoryFilter.ALL -> true
            LogCategoryFilter.WARNINGS_AND_ERRORS ->
                level == MinerLogLevel.WARNING || level == MinerLogLevel.ERROR || isShareRejected
            LogCategoryFilter.SHARES_AND_POOL -> isShareAccepted || isShareRejected ||
                listOf("stratum", "pool", "share", "mining.", "job", "work", "diff", "extranonce").any { h.contains(it) }
            LogCategoryFilter.ASIC_AND_HARDWARE ->
                listOf("asic", "bm13", "nonce", "chip", "freq", "hashrate", "serial").any { h.contains(it) }
            LogCategoryFilter.POWER_AND_THERMAL ->
                listOf("power", "temp", "fan", "thermal", "vcore", "voltage", "current", "ina260", "emc210", "pid").any { h.contains(it) }
            LogCategoryFilter.SYSTEM_AND_NETWORK ->
                listOf("wifi", "http", "ws", "system", "nvs", "heap", "ota", "net").any { h.contains(it) }
        }
    }

    fun matches(searchText: String): Boolean {
        val q = searchText.trim()
        if (q.isEmpty()) return true
        return message.contains(q, ignoreCase = true) ||
            componentTag?.contains(q, ignoreCase = true) == true ||
            level.raw.contains(q, ignoreCase = true)
    }

    fun exportLine(fmt: SimpleDateFormat): String {
        val tag = componentTag?.let { "[$it] " } ?: ""
        val esp = espTimestamp?.let { "($it) " } ?: ""
        return "${fmt.format(Date(receivedAt))} [${level.shortLabel}] $esp$tag$message"
    }

    companion object {
        private val espLogPattern = Regex("""^([IWEDV])\s*\(([^)]+)\)\s*([^:]+):\s*(.*)$""")
        private val ansiEsc = Regex("""\u001B\[[0-9;]*[a-zA-Z]""")
        private val ansiOrphan = Regex("""\[([0-9]{1,2};)?[0-9]{1,2}m""")

        fun stripAnsi(input: String): String = ansiOrphan.replace(ansiEsc.replace(input, ""), "")

        /** Parses a raw line from `ws://<ip>/api/ws`. */
        fun parse(rawLine: String, receivedAt: Long = System.currentTimeMillis()): MinerLogEntry? {
            val cleaned = stripAnsi(rawLine).trim()
            if (cleaned.isEmpty()) return null

            var level = MinerLogLevel.INFO
            var esp: String? = null
            var tag: String? = null
            var body = cleaned

            val m = espLogPattern.find(cleaned)
            if (m != null) {
                level = when (m.groupValues[1]) {
                    "W" -> MinerLogLevel.WARNING
                    "E" -> MinerLogLevel.ERROR
                    "D", "V" -> MinerLogLevel.DEBUG
                    else -> MinerLogLevel.INFO
                }
                esp = m.groupValues[2].trim()
                tag = m.groupValues[3].trim()
                body = m.groupValues[4].trim()
            } else {
                val lower = cleaned.lowercase()
                if (lower.contains("error") || lower.contains("failed") || lower.contains("rejected")) level = MinerLogLevel.ERROR
                else if (lower.contains("warn") || lower.contains("overheat")) level = MinerLogLevel.WARNING
            }

            val lowerMsg = body.lowercase()
            val lowerTag = (tag ?: "").lowercase()
            val accepted = lowerMsg.contains("accepted") || (lowerTag.contains("stratum") && lowerMsg.contains("result: true"))
            val rejected = lowerMsg.contains("rejected") || (lowerTag.contains("stratum") && lowerMsg.contains("result: false"))
            if (rejected && level == MinerLogLevel.INFO) level = MinerLogLevel.WARNING

            return MinerLogEntry(receivedAt, esp, level, tag, body.ifEmpty { cleaned }, cleaned, accepted, rejected)
        }

        fun exportFileName(minerName: String, ip: String, date: Long = System.currentTimeMillis()): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val safe = minerName.split(' ', '/', ':', '\\').filter { it.isNotEmpty() }.joinToString("-")
            return "MinerGuru-$safe-$ip-${fmt.format(Date(date))}.log"
        }
    }
}

/**
 * One ASIC result with its difficulty, parsed from the live log.
 *  - AxeOS:  `asic_result: ID: …, Nonce F0AE9D21 diff 1284.3 of 874.`
 *  - NerdOS: `asic_result: (Sec) Job ID: 78 … diff 141114.6/3075/791M`
 */
data class ShareSample(
    val date: Long,
    val difficulty: Double,
    val poolDifficulty: Double?,
    val pool: Pool?
) {
    enum class Pool(val label: String) { PRIMARY("Pool 1"), SECONDARY("Pool 2") }

    /** Reached the pool difficulty, i.e. was submitted as a share. */
    val isSubmitted: Boolean get() = poolDifficulty == null || difficulty >= poolDifficulty

    companion object {
        private val diffPattern = Regex("""\bdiff\s+([0-9]+(?:\.[0-9]+)?)(?:\s+of\s+|/)([0-9]+(?:\.[0-9]+)?)""")
        private val poolPattern = Regex("""\((Pri|Sec)\)""")

        fun parse(entry: MinerLogEntry): ShareSample? {
            if (entry.componentTag?.lowercase() != "asic_result") return null
            return parse(entry.message, entry.receivedAt)
        }

        fun parse(message: String, date: Long): ShareSample? {
            val m = diffPattern.find(message) ?: return null
            val difficulty = m.groupValues[1].toDoubleOrNull() ?: return null
            val poolDiff = m.groupValues[2].toDoubleOrNull()
            val pool = poolPattern.find(message)?.let { if (it.groupValues[1] == "Pri") Pool.PRIMARY else Pool.SECONDARY }
            return ShareSample(date, difficulty, poolDiff, pool)
        }

        /** Series key per sample: one line per pool, broken where two results are more than [maxGapMs] apart. */
        fun lineSeries(samples: List<ShareSample>, maxGapMs: Long): List<String> {
            val segment = HashMap<String, Int>()
            val lastDate = HashMap<String, Long>()
            return samples.map { s ->
                val pool = s.pool?.label ?: "Share"
                lastDate[pool]?.let { if (s.date - it > maxGapMs) segment[pool] = (segment[pool] ?: 0) + 1 }
                lastDate[pool] = s.date
                "$pool#${segment[pool] ?: 0}"
            }
        }
    }
}
