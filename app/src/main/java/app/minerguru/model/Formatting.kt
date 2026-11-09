package app.minerguru.model

import java.util.Locale
import kotlin.math.abs

/** Normalizes pool addresses as entered in AxeOS / NerdOS (`stratum+tcp://pool.example:3333/x`). */
object StratumHost {
    private val schemes = listOf("stratum+tcp://", "stratum+ssl://", "stratum+tls://", "stratum://", "tcp://", "http://", "https://")

    /** Strips scheme and path. With `keepPort = false` (default) a trailing `:port` is removed as well. */
    fun clean(raw: String, keepPort: Boolean = false): String {
        var s = raw.trim()
        schemes.firstOrNull { s.lowercase().startsWith(it) }?.let { s = s.drop(it.length) }
        s.indexOf('/').takeIf { it >= 0 }?.let { s = s.substring(0, it) }
        if (!keepPort) s.indexOf(':').takeIf { it >= 0 }?.let { s = s.substring(0, it) }
        return s
    }
}

/**
 * Number formatting for percentages, odds and durations; follows the device region (`1.234,56` in Germany).
 * Technical miner metrics keep the dot notation of the AxeOS web UI, see [MinerFormatting].
 */
object DisplayFormat {
    /** Device region; unit tests set German notation. */
    var locale: Locale = Locale.getDefault()

    fun decimal(value: Double, fractionDigits: Int): String =
        String.format(locale, "%,.${fractionDigits}f", value)

    /** Up to [maxFractionDigits] fraction digits, trailing zeros dropped. */
    fun decimalMax(value: Double, maxFractionDigits: Int): String {
        val nf = java.text.NumberFormat.getNumberInstance(locale)
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = maxFractionDigits
        return nf.format(value)
    }

    fun percent(value: Double, fractionDigits: Int): String = "${decimal(value, fractionDigits)} %"

    fun integer(value: Int): String = String.format(locale, "%,d", value)
}

object MinerFormatting {
    private fun f(format: String, vararg args: Any?): String = String.format(Locale.US, format, *args)

    fun formatHashrate(ghs: Double): String {
        if (ghs <= 0) return "0 GH/s"
        return if (ghs >= 1000.0) f("%.2f TH/s", ghs / 1000.0) else f("%.0f GH/s", ghs)
    }

    fun splitHashrate(ghs: Double): Pair<String, String> {
        if (ghs <= 0) return "0" to "GH/s"
        return if (ghs >= 1000.0) f("%.2f", ghs / 1000.0) to "TH/s" else f("%.0f", ghs) to "GH/s"
    }

    fun formatUptime(seconds: Int): String {
        if (seconds <= 0) return "—"
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${maxOf(1, minutes)}m"
        }
    }

    fun formatLatency(ms: Double?): String {
        if (ms == null || ms <= 0) return "—"
        return if (ms < 10) f("%.1f ms", ms) else f("%.0f ms", ms)
    }

    fun format0(value: Double): String = f("%.0f", value)
    fun format1(value: Double): String = f("%.1f", value)
    fun format2(value: Double): String = f("%.2f", value)

    /** `38.7M`, `4.40G`, `1.2K` – difficulty with unit suffix. */
    fun formatDifficulty(value: Double): String {
        if (value <= 0) return "0"
        val units = listOf(1e15 to "P", 1e12 to "T", 1e9 to "G", 1e6 to "M", 1e3 to "K")
        for ((threshold, suffix) in units) {
            if (value >= threshold) {
                val scaled = value / threshold
                return f(if (scaled >= 100) "%.0f%s" else "%.2f%s", scaled, suffix)
            }
        }
        return f("%.0f", value)
    }

    fun parseDifficultyString(raw: String): Double {
        val trimmed = raw.trim().uppercase()
        if (trimmed.isEmpty()) return 0.0
        val multipliers = mapOf('K' to 1e3, 'M' to 1e6, 'G' to 1e9, 'T' to 1e12, 'P' to 1e15)
        multipliers[trimmed.last()]?.let { mult ->
            trimmed.dropLast(1).trim().toDoubleOrNull()?.let { return it * mult }
        }
        return trimmed.toDoubleOrNull() ?: 0.0
    }

    fun formatSessionBest(value: Double?): String = value?.let { formatDifficulty(it) } ?: "—"
}
