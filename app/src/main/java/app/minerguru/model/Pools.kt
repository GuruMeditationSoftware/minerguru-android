package app.minerguru.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One pool slot of AxeOS 2.15+ (`pools[]` in `/api/system/info` and `PATCH /api/system`).
 *
 * The firmware rebuilds a slot from scratch on every write and resets fields that are not sent,
 * so the advanced settings read from the miner (TLS, SV2, difficulty, …) are written back unchanged.
 */
data class AxeOSPool(
    /** Slot index on the miner (`0 until SLOT_COUNT`). */
    val id: Int,
    val stratumURL: String,
    val stratumPort: Int,
    val stratumUser: String,
    /** The firmware reports `*****`; sending that value back keeps the stored password. */
    val stratumPassword: String? = null,
    /** Advanced per-slot settings as read from the miner. */
    val advanced: Map<String, Any> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        advanced.forEach { (k, v) -> put(k, v) }
        put("id", id)
        put("stratumURL", stratumURL)
        put("stratumPort", stratumPort)
        put("stratumUser", stratumUser)
        stratumPassword?.let { put("stratumPassword", it) }
    }

    companion object {
        /** `MAX_POOLS` in ESP-Miner. */
        const val SLOT_COUNT = 8
        /** Placeholder the firmware returns instead of the real password. */
        const val MASKED_PASSWORD = "*****"

        private val advancedKeys = listOf(
            "stratumProtocol", "stratumSuggestedDifficulty", "stratumExtranonceSubscribe", "stratumTLS", "stratumCert",
            "stratumDecodeCoinbase", "stratumShareWarning", "stratumV2ChannelType", "stratumV2AuthorityPubkey",
            "stratumV2RequireAuth"
        )

        /** Lenient: a malformed slot yields `null` instead of failing the whole list. */
        fun fromJson(o: JSONObject): AxeOSPool? {
            val id = o.int("id") ?: return null
            val advanced = advancedKeys.filter { o.has(it) && !o.isNull(it) }.associateWith { o.get(it) }
            return AxeOSPool(
                id = id,
                stratumURL = o.str("stratumURL") ?: "",
                stratumPort = o.int("stratumPort") ?: 3333,
                stratumUser = o.str("stratumUser") ?: "",
                stratumPassword = o.str("stratumPassword"),
                advanced = advanced
            )
        }

        fun listFrom(arr: JSONArray?): List<AxeOSPool>? = arr?.let {
            (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(::fromJson) }
        }
    }
}

/** A named stratum pool setup that can be assigned to miner pool slots. */
data class PoolConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 3333,
    val user: String = "",
    val password: String = "x"
) {
    val endpoint: MinerSettingsForm.PoolEndpoint get() = MinerSettingsForm.PoolEndpoint(host, port, user, password)
    val hostAndPort: String get() = "$host:$port"
    val isValid: Boolean get() = StratumHost.clean(host).isNotEmpty() && port in 1..65535

    /** Same pool target and account, ignoring name and password. */
    fun matches(endpoint: MinerSettingsForm.PoolEndpoint): Boolean =
        StratumHost.clean(endpoint.host).lowercase() == host.lowercase() &&
            endpoint.port == port && endpoint.user.trim() == user.trim()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port); put("user", user); put("password", password)
    }

    companion object {
        fun create(name: String, host: String, port: Int = 3333, user: String = "", password: String = "x", id: String = UUID.randomUUID().toString()) =
            PoolConfig(id, name, StratumHost.clean(host), port, user, password)

        /** From a pool read off a miner. Firmware does not report passwords (empty or `*****`); most pools ignore them. */
        fun fromEndpoint(name: String, endpoint: MinerSettingsForm.PoolEndpoint): PoolConfig {
            val password = if (endpoint.password == AxeOSPool.MASKED_PASSWORD || endpoint.password.isEmpty()) "x" else endpoint.password
            return create(name, endpoint.host, endpoint.port, endpoint.user, password)
        }

        fun fromJson(o: JSONObject): PoolConfig? = runCatching {
            PoolConfig(
                id = o.getString("id"), name = o.optString("name"), host = o.optString("host"),
                port = o.optInt("port", 3333), user = o.optString("user"), password = o.optString("password", "x")
            )
        }.getOrNull()

        /** Readable default name for a pool found on a miner, e.g. "CKPool EU (Solo) · rig1". */
        fun suggestedName(host: String, port: Int, user: String): String {
            val cleanHost = StratumHost.clean(host)
            val base = PoolPreset.commonPools.firstOrNull { it.host == cleanHost && it.port == port }?.name ?: cleanHost
            val dot = user.indexOf('.')
            val worker = if (dot >= 0 && dot < user.length - 1) user.substring(dot + 1) else null
            return worker?.let { "$base · $it" } ?: base
        }
    }
}
