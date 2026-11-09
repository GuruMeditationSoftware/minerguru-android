package app.minerguru.model

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
            FIRMWARE -> L("Firmware-Update prüfen", "Check for firmware update")
            FAN_CLEANING -> L("Lüfter reinigen", "Clean the fan")
            DUST -> L("Kühlkörper entstauben", "Dust the heatsink")
            THERMAL_PASTE -> L("Wärmeleitpaste erneuern", "Renew thermal paste")
            FAN_REPLACEMENT -> L("Lüfter tauschen", "Replace the fan")
            POWER -> L("Netzteil und Stecker prüfen", "Check power supply and plugs")
            CABLING -> L("Standort und WLAN prüfen", "Check location and Wi-Fi")
            POOLS -> L("Pool und Auszahlungsadresse prüfen", "Check pool and payout address")
            BACKUP -> L("Einstellungen sichern", "Back up settings")
            CUSTOM -> L("Eigene Aufgabe", "Custom task")
        }

    val hint: String
        get() = when (this) {
            FIRMWARE -> L("Neue Firmware bringt Fehlerbehebungen. Vor dem Update Einstellungen sichern.", "New firmware brings fixes. Back up your settings before updating.")
            FAN_CLEANING -> L("Staub am Lüfter kostet Drehzahl und treibt die Temperatur hoch. Nur im Stillstand reinigen.", "Dust on the fan costs speed and drives up the temperature. Clean it only when it has stopped.")
            DUST -> L("Mit Druckluft oder Pinsel, Miner vorher vom Strom trennen.", "Use compressed air or a brush, and unplug the miner first.")
            THERMAL_PASTE -> L("Steigt die Temperatur bei gleichem Takt, ist die Paste oft trocken.", "If the temperature rises at the same frequency, the paste has often dried out.")
            FAN_REPLACEMENT -> L("Lager nutzen sich ab. Laute oder langsame Lüfter rechtzeitig ersetzen.", "Bearings wear out. Replace loud or slow fans in good time.")
            POWER -> L("Warme Stecker und knappe Netzteile sind die häufigste Brandursache.", "Warm plugs and undersized power supplies are the most common fire hazard.")
            CABLING -> L("Luftzufuhr, Umgebungstemperatur und WLAN-Empfang kontrollieren.", "Check airflow, room temperature and Wi-Fi reception.")
            POOLS -> L("Stimmen Pool und Adresse noch? Abgleich mit der Blockansicht.", "Are pool and address still right? Compare them with the block view.")
            BACKUP -> L("Pools und Takt notieren oder exportieren, bevor etwas schiefgeht.", "Note down or export pools and frequency before something goes wrong.")
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
