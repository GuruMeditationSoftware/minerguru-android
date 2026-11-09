package app.minerguru.model

import app.minerguru.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

// Tariff, zones and schedules (MG-14), like the Mac app. Days are ISO numbers: 1 = Monday … 7 = Sunday.

object Weekdays {
    val all: Set<Int> = (1..7).toSet()
    val workdays: Set<Int> = (1..5).toSet()
    val weekend: Set<Int> = setOf(6, 7)

    fun previous(day: Int): Int = if (day == 1) 7 else day - 1

    /** Short name in the UI language, e.g. „Mo“ / "Mon". */
    fun shortName(day: Int): String {
        val name = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, if (Lang.english) Locale.ENGLISH else Locale.GERMAN)
        return name.removeSuffix(".")
    }

    /** „täglich“, „Mo–Fr“ or „Sa, So“. */
    fun summary(days: Set<Int>): String = when (days) {
        all -> tr(R.string.daily_in_text)
        workdays -> "${shortName(1)}–${shortName(5)}"
        else -> (1..7).filter { it in days }.joinToString(", ") { shortName(it) }
    }
}

/**
 * A recurring time window: on [weekdays] from [startMinute] to [endMinute] (minutes after midnight).
 * An end before the start runs over midnight into the next day; start == end covers the whole day.
 */
data class WeeklyWindow(val weekdays: Set<Int>, val startMinute: Int, val endMinute: Int) {
    val wrapsMidnight: Boolean get() = endMinute < startMinute
    val isWholeDay: Boolean get() = startMinute == endMinute

    fun contains(weekday: Int, minute: Int): Boolean = when {
        isWholeDay -> weekday in weekdays
        // The part after midnight belongs to the day the window started on.
        wrapsMidnight -> (minute >= startMinute && weekday in weekdays) ||
            (minute < endMinute && Weekdays.previous(weekday) in weekdays)
        else -> minute in startMinute until endMinute && weekday in weekdays
    }

    fun contains(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val t = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return contains(t.dayOfWeek.value, t.hour * 60 + t.minute)
    }

    val timeRangeText: String get() = if (isWholeDay) tr(R.string.all_day) else "${clock(startMinute)}–${clock(endMinute)}"

    fun toJson(): JSONObject = JSONObject()
        .put("weekdays", JSONArray(weekdays.sorted()))
        .put("startMinute", startMinute)
        .put("endMinute", endMinute)

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        fun clock(minute: Int): String = "%02d:%02d".format((minute / 60) % 24, minute % 60)

        fun fromJson(o: JSONObject): WeeklyWindow {
            val days = o.getJSONArray("weekdays")
            return WeeklyWindow((0 until days.length()).map { days.getInt(it) }.toSet(), o.getInt("startMinute"), o.getInt("endMinute"))
        }
    }
}

/** One price zone of a time-of-use tariff, e.g. „Nacht“ 22–6 Uhr for 0.24 per kWh. */
data class TariffZone(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pricePerKWh: Double,
    val window: WeeklyWindow
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("pricePerKWh", pricePerKWh).put("window", window.toJson())

    companion object {
        fun fromJson(o: JSONObject) = TariffZone(o.getString("id"), o.getString("name"), o.getDouble("pricePerKWh"),
            WeeklyWindow.fromJson(o.getJSONObject("window")))
    }
}

/** Stretch of a day with the same zone (`null` = base price), for the week bar. */
data class TariffSegment(val zoneId: String?, val startMinute: Int, val endMinute: Int)

/**
 * The user's tariff: a base price, optionally refined by zones. The first zone that covers a moment
 * sets its price; where no zone applies, the base price does.
 */
data class PowerTariff(
    val currencyCode: String = defaultCurrency(),
    val basePricePerKWh: Double = 0.30,
    val zones: List<TariffZone> = emptyList()
) {
    val hasZones: Boolean get() = zones.isNotEmpty()

    fun zoneAt(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): TariffZone? = zones.firstOrNull { it.window.contains(epochMillis, zone) }
    fun zoneAt(weekday: Int, minute: Int): TariffZone? = zones.firstOrNull { it.window.contains(weekday, minute) }
    fun priceAt(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Double = zoneAt(epochMillis, zone)?.pricePerKWh ?: basePricePerKWh

    fun segments(weekday: Int, stepMinutes: Int = 15): List<TariffSegment> {
        val result = mutableListOf<TariffSegment>()
        for (start in 0 until WeeklyWindow.MINUTES_PER_DAY step stepMinutes) {
            val id = zoneAt(weekday, start)?.id
            val last = result.lastOrNull()
            if (last != null && last.zoneId == id) result[result.lastIndex] = last.copy(endMinute = start + stepMinutes)
            else result += TariffSegment(id, start, start + stepMinutes)
        }
        return result
    }

    /** Pairs of zones that cover the same time somewhere in the week; the earlier one wins there. */
    val overlappingZones: List<Pair<TariffZone, TariffZone>>
        get() = zones.flatMapIndexed { i, a -> zones.drop(i + 1).filter { b -> overlap(a.window, b.window) }.map { a to it } }

    val hasGaps: Boolean
        get() = hasZones && (1..7).any { day -> (0 until WeeklyWindow.MINUTES_PER_DAY step 15).any { zoneAt(day, it) == null } }

    fun formatPrice(value: Double, digits: Int = 2): String {
        val format = java.text.NumberFormat.getCurrencyInstance(if (Lang.english) Locale.US else Locale.GERMANY)
        runCatching { format.currency = java.util.Currency.getInstance(currencyCode) }
        format.minimumFractionDigits = digits
        format.maximumFractionDigits = digits
        return format.format(value)
    }

    fun toJson(): JSONObject = JSONObject().put("currencyCode", currencyCode).put("basePricePerKWh", basePricePerKWh)
        .put("zones", JSONArray().apply { zones.forEach { put(it.toJson()) } })

    companion object {
        fun defaultCurrency(): String = runCatching { java.util.Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "EUR"

        private fun overlap(a: WeeklyWindow, b: WeeklyWindow): Boolean =
            (1..7).any { day -> (0 until WeeklyWindow.MINUTES_PER_DAY step 15).any { a.contains(day, it) && b.contains(day, it) } }

        fun fromJson(o: JSONObject): PowerTariff {
            val zones = o.optJSONArray("zones") ?: JSONArray()
            return PowerTariff(o.getString("currencyCode"), o.getDouble("basePricePerKWh"),
                (0 until zones.length()).map { TariffZone.fromJson(zones.getJSONObject(it)) })
        }
    }
}

/** What a schedule does with a miner while it applies. */
enum class PowerAction {
    /** The miner's own settings, as before any schedule changed them. */
    NORMAL,
    /** Eco clock and a fixed, quieter fan speed. */
    NIGHT_MODE,
    /** Stop hashing (AxeOS 2.15+); other firmware falls back to the night mode. */
    PAUSE;

    val title: String
        get() = when (this) {
            NORMAL -> tr(R.string.normal)
            NIGHT_MODE -> tr(R.string.night_mode)
            PAUSE -> tr(R.string.pause_noun)
        }
}

/** When a schedule applies: during a tariff zone (follows the zone when it changes) or its own time window. */
sealed interface ScheduleTrigger {
    data class Zone(val zoneId: String) : ScheduleTrigger
    data class Window(val window: WeeklyWindow) : ScheduleTrigger
}

/**
 * The miners of a rule or task after [minerId] was deleted. The last one stays: an empty set would mean all miners,
 * so the rule then applies to none and shows "Miner entfernt".
 */
fun minerScopeWithout(minerIds: Set<String>, minerId: String): Set<String> =
    if (minerIds.size > 1) minerIds - minerId else minerIds

/** Trigger + action + miners. Rules higher up in the list win when several apply at once. */
data class ScheduleRule(
    val id: String = UUID.randomUUID().toString(),
    val trigger: ScheduleTrigger,
    val action: PowerAction,
    /** Miners this rule switches; empty means all miners. */
    val minerIds: Set<String> = emptySet(),
    val isEnabled: Boolean = true
) {
    fun appliesTo(minerId: String): Boolean = minerIds.isEmpty() || minerId in minerIds

    fun isActive(epochMillis: Long, tariff: PowerTariff, zone: ZoneId = ZoneId.systemDefault()): Boolean = isEnabled && when (trigger) {
        is ScheduleTrigger.Zone -> tariff.zoneAt(epochMillis, zone)?.id == trigger.zoneId
        is ScheduleTrigger.Window -> trigger.window.contains(epochMillis, zone)
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("action", action.name).put("enabled", isEnabled)
        .put("minerIds", JSONArray(minerIds.toList()))
        .apply {
            when (trigger) {
                is ScheduleTrigger.Zone -> put("zoneId", trigger.zoneId)
                is ScheduleTrigger.Window -> put("window", trigger.window.toJson())
            }
        }

    companion object {
        fun fromJson(o: JSONObject): ScheduleRule {
            val ids = o.optJSONArray("minerIds") ?: JSONArray()
            val trigger = if (o.has("zoneId")) ScheduleTrigger.Zone(o.getString("zoneId"))
            else ScheduleTrigger.Window(WeeklyWindow.fromJson(o.getJSONObject("window")))
            return ScheduleRule(o.getString("id"), trigger, PowerAction.valueOf(o.getString("action")),
                (0 until ids.length()).map { ids.getString(it) }.toSet(), o.optBoolean("enabled", true))
        }
    }
}

object PowerSchedule {
    private const val QUARTER_HOUR_MS = 15 * 60 * 1000L

    /** The action the rules ask for at [epochMillis], [PowerAction.NORMAL] if none applies. */
    fun action(minerId: String, epochMillis: Long, rules: List<ScheduleRule>, tariff: PowerTariff, zone: ZoneId = ZoneId.systemDefault()): PowerAction =
        rules.firstOrNull { it.appliesTo(minerId) && it.isActive(epochMillis, tariff, zone) }?.action ?: PowerAction.NORMAL

    /**
     * Energy cost of drawing [watts] from [startMillis] for [hours], at the price valid in every quarter hour.
     * Quarter hours in which the rules pause the miner cost nothing (if its firmware can pause).
     */
    fun cost(
        watts: Double, startMillis: Long, hours: Double, tariff: PowerTariff,
        rules: List<ScheduleRule> = emptyList(), minerId: String? = null, canPause: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault()
    ): Double {
        if (watts <= 0 || hours <= 0) return 0.0
        val steps = Math.round(hours * 4).toInt()
        var total = 0.0
        for (i in 0 until steps) {
            val moment = startMillis + i * QUARTER_HOUR_MS
            if (minerId != null && canPause && action(minerId, moment, rules, tariff, zone) == PowerAction.PAUSE) continue
            total += watts / 1000 * 0.25 * tariff.priceAt(moment, zone)
        }
        return total
    }
}
