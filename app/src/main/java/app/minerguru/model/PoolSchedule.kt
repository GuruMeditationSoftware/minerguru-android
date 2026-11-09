package app.minerguru.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Moves miners to a pool of the library at a set time on set weekdays, for example to a lottery pool on weekend
 * nights and back on Monday morning. A rule is one switch; going back is a second rule.
 */
data class PoolScheduleRule(
    val id: String = UUID.randomUUID().toString(),
    val poolId: String,
    /** Miners this rule switches; empty means all miners. */
    val minerIds: Set<String> = emptySet(),
    val weekdays: Set<Int> = Weekdays.all,
    /** Minutes after midnight. */
    val minute: Int = 22 * 60,
    val isEnabled: Boolean = true,
    /** A rule never fires for a time before it existed. */
    val createdAt: Long = System.currentTimeMillis()
) {
    fun appliesTo(minerId: String): Boolean = minerIds.isEmpty() || minerId in minerIds

    /** The last set time at or before [now], looking back one week at most. */
    fun lastOccurrence(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (back in 0L..7L) {
            val day = today.minusDays(back)
            if (day.dayOfWeek.value !in weekdays) continue
            val moment = day.atStartOfDay(zone).withHour(minute / 60).withMinute(minute % 60).toInstant().toEpochMilli()
            if (moment <= now) return moment
        }
        return null
    }

    /** The occurrence that should be carried out now, if it was not carried out for this miner yet. */
    fun dueOccurrence(now: Long, lastApplied: Long?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!isEnabled) return null
        val moment = lastOccurrence(now, zone) ?: return null
        if (now - moment > CATCH_UP_MS || moment < createdAt) return null
        return moment.takeIf { lastApplied == null || it > lastApplied }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("poolId", poolId).put("enabled", isEnabled).put("minute", minute)
        .put("createdAt", createdAt).put("miners", JSONArray(minerIds.toList())).put("weekdays", JSONArray(weekdays.toList()))

    companion object {
        /** A miner that was offline at the set time is still switched when it comes back within this time. */
        const val CATCH_UP_MS = 30 * 60 * 1000L

        fun fromJson(o: JSONObject): PoolScheduleRule {
            val miners = o.getJSONArray("miners").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val days = o.getJSONArray("weekdays").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
            return PoolScheduleRule(o.getString("id"), o.getString("poolId"), miners, days, o.getInt("minute"), o.getBoolean("enabled"), o.getLong("createdAt"))
        }
    }
}
