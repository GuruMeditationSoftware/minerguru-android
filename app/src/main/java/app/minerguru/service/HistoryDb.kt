package app.minerguru.service

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.minerguru.model.HistoryAggregator
import app.minerguru.model.HistoryPoint
import app.minerguru.model.HistoryQuery
import app.minerguru.model.HistoryReading
import app.minerguru.model.MinerDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Long-term telemetry in a local SQLite file (MG-08). Every poll goes into a per-minute average; minute rows stay
 * for [MINUTE_RETENTION_MS], hourly rollups for [HOUR_RETENTION_MS]. The view model, the worker and the foreground
 * service all write here, so every access is synchronized.
 */
object HistoryRecorder {
    const val MINUTE_RETENTION_MS = 14 * 86_400_000L
    const val HOUR_RETENTION_MS = 3 * 365 * 86_400_000L
    private const val ROLLUP_INTERVAL_MS = 600_000L
    private const val PREFS = "history"
    private const val KEY_STATE = "aggregator"

    private var db: Db? = null
    private var aggregator: HistoryAggregator? = null
    private var lastRollup = 0L
    private var hasRolledUp = false

    private class Db(context: Context) : SQLiteOpenHelper(context, "history.sqlite", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) = db.enableWriteAheadLogging().let { }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE samples(
                  resolution INTEGER NOT NULL, miner TEXT NOT NULL, ts INTEGER NOT NULL,
                  online INTEGER NOT NULL, total INTEGER NOT NULL,
                  hashrate REAL, asic REAL, asic_max REAL, vr REAL, power REAL, fan REAL, latency REAL, error REAL,
                  accepted INTEGER NOT NULL, rejected INTEGER NOT NULL, best REAL,
                  PRIMARY KEY(resolution, miner, ts)) WITHOUT ROWID
            """)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        // An older build on a newer file keeps reading the columns it knows, instead of the default crash.
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private fun db(context: Context): SQLiteDatabase =
        (db ?: Db(context.applicationContext).also { db = it }).writableDatabase

    private fun aggregator(context: Context): HistoryAggregator =
        aggregator ?: HistoryAggregator.fromJson(prefs(context).getString(KEY_STATE, null)).also { aggregator = it }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Adds one poll of the fleet. With [flush] the running minute goes to disk at once, for a worker that ends right after. */
    suspend fun record(context: Context, miners: List<MinerDevice>, flush: Boolean = false, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            synchronized(this@HistoryRecorder) {
                val db = db(context)
                val aggregator = aggregator(context)
                val finished = miners.mapNotNull { HistoryReading.of(it, now) }.mapNotNull { aggregator.add(it) }
                val rows = if (flush) finished + aggregator.flush(now) else finished
                if (rows.isNotEmpty()) {
                    db.inTransaction { rows.forEach { write(this, it) } }
                    prefs(context).edit().putString(KEY_STATE, aggregator.toJson().toString()).apply()
                }
                if (now - lastRollup >= ROLLUP_INTERVAL_MS) {
                    lastRollup = now
                    rollUp(db, now)
                }
            }
        }

    fun forget(context: Context, minerId: String) {
        synchronized(this) {
            aggregator(context).forget(minerId)
            db(context).delete("samples", "miner = ?", arrayOf(minerId))
        }
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun write(db: SQLiteDatabase, p: HistoryPoint) {
        val values = ContentValues().apply {
            put("resolution", 60); put("miner", p.minerId); put("ts", p.time / 1000)
            put("online", p.onlineMinutes); put("total", p.recordedMinutes)
            put("hashrate", p.hashRateGHs); put("asic", p.asicTempC); put("asic_max", p.maxAsicTempC); put("vr", p.vrTempC)
            put("power", p.powerWatts); put("fan", p.fanPercent); put("latency", p.poolLatencyMs); put("error", p.errorPercent)
            put("accepted", p.sharesAccepted); put("rejected", p.sharesRejected); put("best", p.bestDiff)
        }
        db.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Averages are weighted by online minutes, so a half hour online does not count like a whole one. */
    private fun rollUp(db: SQLiteDatabase, now: Long) {
        // The first round after a start also covers what the last run left unfinished.
        val window = if (hasRolledUp) 3 * 3600_000L else MINUTE_RETENTION_MS
        hasRolledUp = true
        db.execSQL("""
            INSERT OR REPLACE INTO samples
            SELECT 3600, miner, ts / 3600 * 3600, SUM(online), SUM(total),
              SUM(hashrate * online) / NULLIF(SUM(online), 0), SUM(asic * online) / NULLIF(SUM(online), 0), MAX(asic_max),
              AVG(vr), SUM(power * online) / NULLIF(SUM(online), 0), AVG(fan), AVG(latency), AVG(error),
              SUM(accepted), SUM(rejected), MAX(best)
            FROM samples WHERE resolution = 60 AND ts >= ? / 3600 * 3600 GROUP BY miner, ts / 3600
        """, arrayOf((now - window) / 1000))
        db.delete("samples", "resolution = 60 AND ts < ?", arrayOf(((now - MINUTE_RETENTION_MS) / 1000).toString()))
        db.delete("samples", "resolution = 3600 AND ts < ?", arrayOf(((now - HOUR_RETENTION_MS) / 1000).toString()))
    }

    /** Points of one miner between two times, oldest first. */
    suspend fun points(context: Context, minerId: String, from: Long, to: Long): List<HistoryPoint> = withContext(Dispatchers.IO) {
        synchronized(this@HistoryRecorder) { flushRunning(context); query(db(context), minerId, from, to, HistoryQuery.step((to - from) / 1000)) }
    }

    /** Hourly points of all miners, for reports. */
    suspend fun hourlyPoints(context: Context, from: Long, to: Long): List<HistoryPoint> = withContext(Dispatchers.IO) {
        synchronized(this@HistoryRecorder) {
            flushRunning(context)
            rollUp(db(context), System.currentTimeMillis())
            query(db(context), null, from, to, 3600)
        }
    }

    private fun flushRunning(context: Context) {
        val rows = aggregator(context).flush(System.currentTimeMillis())
        db(context).inTransaction { rows.forEach { write(this, it) } }
    }

    private fun query(db: SQLiteDatabase, minerId: String?, from: Long, to: Long, step: Int): List<HistoryPoint> {
        val resolution = if (step >= 3600) 3600 else 60
        val filter = if (minerId != null) "AND miner = ?" else ""
        val args = listOf(resolution.toString(), (from / 1000).toString(), (to / 1000).toString()) + listOfNotNull(minerId)
        db.rawQuery("""
            SELECT miner, ts / $step * $step, SUM(online), SUM(total),
              SUM(hashrate * online) / NULLIF(SUM(online), 0), SUM(asic * online) / NULLIF(SUM(online), 0), MAX(asic_max),
              AVG(vr), SUM(power * online) / NULLIF(SUM(online), 0), AVG(fan), AVG(latency), AVG(error),
              SUM(accepted), SUM(rejected), MAX(best)
            FROM samples WHERE resolution = ? AND ts >= ? AND ts <= ? $filter
            GROUP BY miner, ts / $step ORDER BY 2, 1
        """, args.toTypedArray()).use { c ->
            fun Cursor.double(i: Int) = if (isNull(i)) null else getDouble(i)
            val points = ArrayList<HistoryPoint>(c.count)
            while (c.moveToNext()) {
                points += HistoryPoint(c.getString(0), c.getLong(1) * 1000, c.getInt(2), c.getInt(3), c.double(4), c.double(5), c.double(6),
                    c.double(7), c.double(8), c.double(9), c.double(10), c.double(11), c.getInt(12), c.getInt(13), c.double(14))
            }
            return points
        }
    }
}
