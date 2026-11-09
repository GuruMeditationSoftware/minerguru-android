package app.minerguru.model

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Who caused a write to a miner. */
enum class ChangeSource {
    MANUAL, SCHEDULE, WATCHDOG;

    val title: String
        get() = when (this) {
            MANUAL -> L("Von Hand", "Manual")
            SCHEDULE -> L("Zeitplan", "Schedule")
            WATCHDOG -> L("Automatik", "Automatic")
        }
}

/** One value a write changes. [before] is `null` where the miner does not report the field. */
data class ChangeField(val key: String, val before: String?, val after: String) {
    val title: String get() = ChangeLog.title(key)
    /** The line a person reads, in the confirmation and the change log. */
    val summary: String get() = "$title: ${before?.let { ChangeLog.shown(it, key) } ?: "–"} → ${ChangeLog.shown(after, key)}"
}

data class ChangeEntry(
    val date: Long,
    val host: String,
    val action: String,
    val fields: List<ChangeField>,
    val source: ChangeSource,
    /** `null` when the miner accepted the write, otherwise the reason it failed. */
    val failure: String?
) {
    fun toJson(): JSONObject = JSONObject().put("date", date).put("host", host).put("action", action)
        .put("source", source.name).put("failure", failure)
        .put("fields", JSONArray(fields.map { JSONObject().put("key", it.key).put("before", it.before).put("after", it.after) }))

    companion object {
        fun fromJson(o: JSONObject): ChangeEntry {
            val array = o.optJSONArray("fields") ?: JSONArray()
            val fields = (0 until array.length()).map { i ->
                val f = array.getJSONObject(i)
                ChangeField(f.getString("key"), f.optString("before").takeIf { f.has("before") }, f.getString("after"))
            }
            return ChangeEntry(
                o.getLong("date"), o.getString("host"), o.getString("action"), fields,
                runCatching { ChangeSource.valueOf(o.getString("source")) }.getOrDefault(ChangeSource.MANUAL),
                o.optString("failure").takeIf { o.has("failure") }
            )
        }
    }
}

/**
 * What MinerGuru changed on miners, and the optional read-only mode. [app.minerguru.service.MinerApiClient]
 * is the only place that writes to a miner, so everything lands here, automatic actions included.
 */
class ChangeLog(private var folder: File? = null) {
    private val entries = mutableListOf<ChangeEntry>()
    private val values = mutableMapOf<String, Map<String, String>>()
    private var loaded = false

    @Synchronized
    fun activate(folder: File) {
        if (this.folder != null) return
        this.folder = folder
        loaded = false
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val file = folder?.let { File(it, FILE_NAME) } ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            entries.clear()
            (0 until array.length()).mapTo(entries) { ChangeEntry.fromJson(array.getJSONObject(it)) }
        }
    }

    @get:Synchronized @set:Synchronized
    var isReadOnly: Boolean
        get() = folder?.let { File(it, READ_ONLY_FILE).exists() } ?: readOnlyInMemory
        set(value) {
            readOnlyInMemory = value
            folder?.let { File(it, READ_ONLY_FILE).run { if (value) createNewFile() else delete() } }
        }
    private var readOnlyInMemory = false

    /** Newest first. */
    @Synchronized
    fun entries(): List<ChangeEntry> { load(); return entries.reversed() }

    /** Remembers what a miner reports, to show the value a later write replaces. */
    @Synchronized
    fun noteValues(host: String, rawJSON: String?) {
        val current = rawJSON?.let { flatten(it) } ?: return
        values[host] = current
    }

    @Synchronized
    fun record(entry: ChangeEntry) {
        load()
        entries += entry
        if (entries.size > MAX_ENTRIES) entries.subList(0, entries.size - MAX_ENTRIES).clear()
        val file = folder?.let { File(it, FILE_NAME) } ?: return
        runCatching { file.writeText(JSONArray(entries.map { it.toJson() }).toString()) }
    }

    /** Records a write that went to the miner. Session requests for one-time codes change nothing. */
    fun recordWrite(host: String, method: String, path: String, body: String?, failure: String?, now: Long = System.currentTimeMillis()) {
        if ("/otp/" in path) return
        val current = synchronized(this) { values[host].orEmpty() }
        val fields = body?.let { changes(it, current) }.orEmpty()
        record(ChangeEntry(now, host, action(method, path), fields, currentSource.get() ?: ChangeSource.MANUAL, failure))
    }

    @Synchronized fun lastWrite(host: String): Long? { load(); return entries.lastOrNull { it.host == host }?.date }

    @Synchronized fun lastRestart(host: String): Long? {
        load(); return entries.lastOrNull { it.host == host && it.action == "restart" }?.date
    }

    /** The watchdog's last attempt on [host], successful or not. */
    @Synchronized fun lastAutomaticRestart(host: String): Long? {
        load(); return entries.lastOrNull { it.host == host && it.action == "restart" && it.source == ChangeSource.WATCHDOG }?.date
    }

    fun exportText(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return entries().reversed().joinToString("") { e ->
            val changes = e.fields.joinToString("; ") { "${it.key}: ${it.before ?: "-"} -> ${it.after}" }
            "${format.format(Date(e.date))} ${e.host} ${e.source.name.lowercase()} ${e.action} ${e.failure?.let { "failed: $it" } ?: "ok"} $changes\n"
        }
    }

    companion object {
        val shared = ChangeLog()
        const val MAX_ENTRIES = 500
        const val FILE_NAME = "changes.json"
        private const val READ_ONLY_FILE = "readonly"
        private const val SECRET_MASK = "•••"
        private const val FIRMWARE_MASK = "*****"
        private val currentSource = ThreadLocal<ChangeSource?>()

        /** Runs a rule's or the watchdog's writes under their [source]. */
        suspend fun <T> withSource(source: ChangeSource, block: suspend () -> T): T =
            withContext(currentSource.asContextElement(source)) { block() }

        /** `restart`, `pause`, `resume`, `settings` or `clearSlot`. */
        fun action(method: String, path: String): String = when {
            method == "DELETE" -> "clearSlot"
            path.endsWith("restart") -> "restart"
            path.endsWith("pause") -> "pause"
            path.endsWith("resume") -> "resume"
            else -> "settings"
        }

        fun actionTitle(action: String): String = when (action) {
            "restart" -> L("Neustart", "Restart")
            "pause" -> L("Pausieren", "Pause")
            "resume" -> L("Fortsetzen", "Resume")
            "clearSlot" -> L("Pool-Slot geleert", "Pool slot cleared")
            else -> L("Einstellungen", "Settings")
        }

        fun flatten(rawJSON: String): Map<String, String>? =
            runCatching { flatten(JSONObject(rawJSON)) }.getOrNull()

        /** Top-level values, and pool slots as `pools.<id>.<field>`, for comparing. Only [changes] leaves this class. */
        fun flatten(o: JSONObject): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (key in o.keys()) {
                val value = o.get(key)
                if (key == "pools" && value is JSONArray) {
                    for (i in 0 until value.length()) {
                        val pool = value.optJSONObject(i) ?: continue
                        val id = pool.optInt("id", i)
                        for (field in pool.keys()) {
                            if (field != "id") comparable(field, pool.get(field))?.let { result["pools.$id.$field"] = it }
                        }
                    }
                } else {
                    comparable(key, value)?.let { result[key] = it }
                }
            }
            return result
        }

        /** The fields of a write whose value differs from what the miner reports. */
        fun changes(body: String, current: Map<String, String>): List<ChangeField> {
            val sent = flatten(body) ?: return emptyList()
            return sent.entries.sortedBy { it.key }.mapNotNull { (key, after) ->
                when {
                    current[key] == after -> null
                    isSecret(key) -> ChangeField(key, null, SECRET_MASK)
                    else -> ChangeField(key, current[key], after)
                }
            }
        }

        private fun isSecret(key: String): Boolean {
            val lower = key.lowercase()
            return "pass" in lower || "token" in lower || "cert" in lower || "otp" in lower
        }

        /** The firmware's mask for a stored password means "keep it", so that is no change. */
        private fun comparable(key: String, value: Any?): String? {
            val text = when (value) {
                is String -> value
                is Boolean -> if (value) "1" else "0"
                is Number -> if (value.toDouble() % 1.0 == 0.0) value.toLong().toString() else value.toString()
                else -> return null
            }
            return if (isSecret(key) && (text == FIRMWARE_MASK || text.isEmpty())) null else text
        }

        /** A short name for a field; unknown fields keep the name the miner uses. */
        fun title(key: String): String {
            val parts = key.split(".")
            val isSlot = key.startsWith("pools.") && parts.size == 3
            val name = if (isSlot) parts[2] else key
            val prefix = if (isSlot) "Pool ${(parts[1].toIntOrNull() ?: 0) + 1}: " else ""
            return prefix + when (name) {
                "hostname" -> "Hostname"
                "frequency" -> L("Frequenz", "Frequency")
                "coreVoltage" -> L("Kernspannung", "Core voltage")
                "autofanspeed" -> L("Lüfterautomatik", "Automatic fan")
                "fanspeed", "manualFanSpeed" -> L("Lüfter", "Fan")
                "stratumURL" -> L("Pool-Adresse", "Pool address")
                "stratumPort" -> "Pool-Port"
                "stratumUser" -> L("Pool-Benutzer", "Pool user")
                "stratumPassword" -> L("Pool-Passwort", "Pool password")
                "fallbackStratumURL" -> L("Fallback-Adresse", "Fallback address")
                "fallbackStratumPort" -> "Fallback-Port"
                "fallbackStratumUser" -> L("Fallback-Benutzer", "Fallback user")
                "fallbackStratumPassword" -> L("Fallback-Passwort", "Fallback password")
                "overclockEnabled" -> L("Eigene Taktwerte", "Custom clock values")
                "temptarget", "pidTargetTemp" -> L("Zieltemperatur", "Target temperature")
                "poolMode" -> L("Pool-Modus", "Pool mode")
                "poolBalance" -> L("Pool-Aufteilung", "Pool split")
                "primaryPoolIndex" -> L("Primärer Pool-Platz", "Primary pool slot")
                "secondaryPoolIndex" -> L("Fallback-Platz", "Fallback slot")
                "id" -> L("Platz", "Slot")
                "stratumProtocol" -> L("Protokoll", "Protocol")
                "stratumTLS" -> "TLS"
                "stratumSuggestedDifficulty" -> L("Vorgeschlagene Difficulty", "Suggested difficulty")
                "stratumExtranonceSubscribe" -> "Extranonce-Subscribe"
                "stratumDecodeCoinbase" -> L("Coinbase auswerten", "Decode coinbase")
                "stratumShareWarning" -> L("Share-Warnung", "Share warning")
                "stratumV2ChannelType" -> L("SV2-Kanaltyp", "SV2 channel type")
                "stratumV2AuthorityPubkey" -> L("SV2-Authority-Key", "SV2 authority key")
                "stratumV2RequireAuth" -> L("SV2-Authentifizierung", "SV2 authentication")
                else -> name
            }
        }

        /** Values the firmware stores as numbers, in words. Everything else stays as it is. */
        fun shown(value: String, key: String): String = when (key.substringAfterLast('.')) {
            "overclockEnabled", "autofanspeed", "stratumExtranonceSubscribe", "stratumDecodeCoinbase", "stratumShareWarning", "stratumV2RequireAuth" -> when (value) {
                "1", "true" -> L("an", "on")
                "0", "false" -> L("aus", "off")
                else -> value
            }
            "poolMode" -> when (value) {
                "0" -> "Failover"
                "1" -> "Dual-Pool"
                else -> value
            }
            "poolBalance" -> "$value %"
            "primaryPoolIndex", "secondaryPoolIndex" -> value.toIntOrNull()?.let { "${it + 1}" } ?: value
            else -> value
        }
    }
}
