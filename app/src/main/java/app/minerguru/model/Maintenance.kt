package app.minerguru.model

import app.minerguru.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What a maintenance task is about. Each kind brings its name and a sensible interval. */
enum class MaintenanceKind(val defaultIntervalDays: Int) {
    FIRMWARE(30), FAN_CLEANING(90), DUST(180), THERMAL_PASTE(365), FAN_REPLACEMENT(730), POWER(180), CABLING(180), POOLS(30), BACKUP(90), CUSTOM(30);

    val title: String
        get() = when (this) {
            FIRMWARE -> tr(R.string.check_for_firmware_update)
            FAN_CLEANING -> tr(R.string.clean_the_fan)
            DUST -> tr(R.string.dust_the_heatsink)
            THERMAL_PASTE -> tr(R.string.renew_thermal_paste)
            FAN_REPLACEMENT -> tr(R.string.replace_the_fan)
            POWER -> tr(R.string.check_power_supply_and_plugs)
            CABLING -> tr(R.string.check_location_and_wi_fi)
            POOLS -> tr(R.string.check_pool_and_payout_address)
            BACKUP -> tr(R.string.back_up_settings)
            CUSTOM -> tr(R.string.custom_task)
        }

    val hint: String
        get() = when (this) {
            FIRMWARE -> tr(R.string.new_firmware_brings_fixes_back)
            FAN_CLEANING -> tr(R.string.dust_on_the_fan_costs)
            DUST -> tr(R.string.use_compressed_air_or_a)
            THERMAL_PASTE -> tr(R.string.if_the_temperature_rises_at)
            FAN_REPLACEMENT -> tr(R.string.bearings_wear_out_replace_loud)
            POWER -> tr(R.string.warm_plugs_and_undersized_power)
            CABLING -> tr(R.string.check_airflow_room_temperature_and)
            POOLS -> tr(R.string.are_pool_and_address_still)
            BACKUP -> tr(R.string.note_down_or_export_pools)
            CUSTOM -> ""
        }
}

enum class MaintenanceStatus { OVERDUE, DUE_SOON, PLANNED }

data class MaintenanceTask(
    val id: String = UUID.randomUUID().toString(),
    val kind: MaintenanceKind,
    /** Own title, for [MaintenanceKind.CUSTOM] or to rename a task. */
    val customTitle: String = "",
    /** Miners this task is for; empty means all miners. */
    val minerIds: Set<String> = emptySet(),
    /** Days until it is due again; 0 = once. */
    val intervalDays: Int,
    val nextDue: Long,
    val notes: String = ""
) {
    val title: String get() = customTitle.trim().ifEmpty { kind.title }

    fun daysUntilDue(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int =
        ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), Instant.ofEpochMilli(nextDue).atZone(zone).toLocalDate()).toInt()

    fun status(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): MaintenanceStatus {
        val days = daysUntilDue(now, zone)
        return when {
            days < 0 -> MaintenanceStatus.OVERDUE
            days <= DUE_SOON_DAYS -> MaintenanceStatus.DUE_SOON
            else -> MaintenanceStatus.PLANNED
        }
    }

    /** The next date after it was done on [date]; `null` for a task that runs once. */
    fun nextDueAfterDone(date: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        if (intervalDays > 0) Instant.ofEpochMilli(date).atZone(zone).plusDays(intervalDays.toLong()).toInstant().toEpochMilli() else null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("customTitle", customTitle)
        .put("miners", JSONArray(minerIds.toList())).put("intervalDays", intervalDays).put("nextDue", nextDue).put("notes", notes)

    companion object {
        const val DUE_SOON_DAYS = 7

        fun fromJson(o: JSONObject) = MaintenanceTask(o.getString("id"), MaintenanceKind.valueOf(o.getString("kind")), o.optString("customTitle"),
            o.getJSONArray("miners").strings(), o.getInt("intervalDays"), o.getLong("nextDue"), o.optString("notes"))
    }
}

/** One finished maintenance job, kept as a record per miner. */
data class MaintenanceRecord(
    val id: String = UUID.randomUUID().toString(),
    val kind: MaintenanceKind,
    val title: String,
    val minerIds: Set<String>,
    val doneAt: Long,
    val notes: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("title", title)
        .put("miners", JSONArray(minerIds.toList())).put("doneAt", doneAt).put("notes", notes)

    companion object {
        fun fromJson(o: JSONObject) = MaintenanceRecord(o.getString("id"), MaintenanceKind.valueOf(o.getString("kind")), o.getString("title"),
            o.getJSONArray("miners").strings(), o.getLong("doneAt"), o.optString("notes"))
    }
}

private fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()
