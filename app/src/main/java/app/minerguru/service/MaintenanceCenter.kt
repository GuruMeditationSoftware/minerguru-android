package app.minerguru.service

import android.content.Context
import app.minerguru.R
import app.minerguru.model.AlertEvent
import app.minerguru.model.AlertKind
import app.minerguru.model.tr
import app.minerguru.model.minerScopeWithout
import app.minerguru.model.MaintenanceRecord
import app.minerguru.model.MaintenanceTask
import org.json.JSONArray
import org.json.JSONObject

/** Planned maintenance and the record of what was done. Stored here so background polls can remind too. */
object MaintenanceCenter {
    private const val PREFS = "maintenance"
    private const val KEY_TASKS = "tasks"
    private const val KEY_RECORDS = "records"
    private const val KEY_REMINDED = "reminded"
    private const val REMIND_AGAIN_MS = 86_400_000L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tasks(context: Context): List<MaintenanceTask> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_TASKS, null) ?: return emptyList())
        (0 until arr.length()).map { MaintenanceTask.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun records(context: Context): List<MaintenanceRecord> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_RECORDS, null) ?: return emptyList())
        (0 until arr.length()).map { MaintenanceRecord.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun setTasks(context: Context, tasks: List<MaintenanceTask>) =
        prefs(context).edit().putString(KEY_TASKS, JSONArray().apply { tasks.forEach { put(it.toJson()) } }.toString()).apply()

    fun setRecords(context: Context, records: List<MaintenanceRecord>) =
        prefs(context).edit().putString(KEY_RECORDS, JSONArray().apply { records.forEach { put(it.toJson()) } }.toString()).apply()

    fun markDone(context: Context, task: MaintenanceTask, date: Long, notes: String) {
        setRecords(context, listOf(MaintenanceRecord(kind = task.kind, title = task.title, minerIds = task.minerIds, doneAt = date, notes = notes)) + records(context))
        val next = task.nextDueAfterDone(date)
        setTasks(context, if (next != null) tasks(context).map { if (it.id == task.id) it.copy(nextDue = next) else it }
        else tasks(context).filterNot { it.id == task.id })
    }

    fun forget(context: Context, minerId: String) =
        setTasks(context, tasks(context).map { it.copy(minerIds = minerScopeWithout(it.minerIds, minerId)) })

    /** Reminder for due tasks, at most once a day per task. */
    fun dueEvents(context: Context, now: Long = System.currentTimeMillis()): List<AlertEvent> = synchronized(this) {
        val tasks = tasks(context).filter { it.daysUntilDue(now) <= 0 }
        if (tasks.isEmpty()) return emptyList()
        val reminded = runCatching { JSONObject(prefs(context).getString(KEY_REMINDED, null) ?: "{}") }.getOrDefault(JSONObject())
        val events = tasks.filter { now - reminded.optLong(it.id, 0) >= REMIND_AGAIN_MS }.map { task ->
            reminded.put(task.id, now)
            AlertEvent(AlertKind.MAINTENANCE, task.id, tr(R.string.maintenance_due_for, task.title),
                task.kind.hint.ifEmpty { tr(R.string.mark_it_as_done_in) }, false, task.title)
        }
        if (events.isNotEmpty()) prefs(context).edit().putString(KEY_REMINDED, reminded.toString()).apply()
        events
    }
}
