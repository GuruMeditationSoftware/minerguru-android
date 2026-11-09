package app.minerguru

import app.minerguru.model.*
import org.json.JSONArray
import org.json.JSONObject

object Fixtures {
    fun info(fields: Map<String, Any?>): MinerSystemInfo = MinerSystemInfo.fromJson(JSONObject(fields))

    fun bitaxeFields(): MutableMap<String, Any?> = mutableMapOf(
        "hashRate" to 1000.0, "power" to 18.0, "temp" to 55.0, "sharesAccepted" to 900, "sharesRejected" to 3,
        "bestDiff" to 4_400_000_000L, "stratumURL" to "eusolo.ckpool.org", "stratumPort" to 3333,
        "stratumUser" to "bc1qexamplewallet.bitaxe", "fallbackStratumURL" to "public-pool.io", "fallbackStratumPort" to 21496,
        "fallbackStratumUser" to "bc1qexamplewallet.bitaxe", "isUsingFallbackStratum" to 0, "responseTime" to 25.0,
        "ASICModel" to "BM1370", "boardVersion" to "601", "hostname" to "bitaxe", "version" to "v2.15.3"
    )

    fun bitaxeInfo(overrides: Map<String, Any?> = emptyMap()) = info(bitaxeFields().apply { putAll(overrides) })

    /** Bitaxe on AxeOS 2.15+ with three pool slots (0 primary, 1 fallback, 4 reserve with TLS). */
    fun axeSlotsInfo(): MinerSystemInfo {
        val pools = JSONArray()
            .put(JSONObject(mapOf("id" to 0, "stratumProtocol" to "SV1", "stratumURL" to "eusolo.ckpool.org", "stratumPort" to 3333,
                "stratumUser" to "bc1qexamplewallet.bitaxe", "stratumPassword" to "*****", "stratumTLS" to 0,
                "stratumSuggestedDifficulty" to 1000, "stratumDecodeCoinbase" to true)))
            .put(JSONObject(mapOf("id" to 1, "stratumURL" to "public-pool.io", "stratumPort" to 21496,
                "stratumUser" to "bc1qexamplewallet.bitaxe", "stratumPassword" to "*****")))
            .put(JSONObject(mapOf("id" to 4, "stratumURL" to "mine.ocean.xyz", "stratumPort" to 3334, "stratumUser" to "bc1qocean.rig",
                "stratumPassword" to "*****", "stratumTLS" to 1, "stratumProtocol" to "SV2")))
            .put(JSONObject(mapOf("stratumURL" to "broken-slot-without-id")))
        val json = JSONObject(bitaxeFields()).put("primaryPoolIndex", 0).put("secondaryPoolIndex", 1).put("pools", pools)
        return MinerSystemInfo.fromJson(json)
    }

    /** NerdQAxe++ in dual-pool mode: 1 % BTC (ckpool) / 99 % BCH (solopool). */
    fun dualPoolInfo(): MinerSystemInfo {
        val stratum = JSONObject(mapOf("poolMode" to 1, "activePoolMode" to 1, "poolBalance" to 1, "usingFallback" to false))
            .put("pools", JSONArray()
                .put(JSONObject(mapOf("connected" to true, "pingRtt" to 20.0)))
                .put(JSONObject(mapOf("connected" to true, "pingRtt" to 30.0))))
        return MinerSystemInfo.fromJson(JSONObject().apply {
            put("deviceModel", "NerdQAxe++"); put("hashRate", 6000.0); put("stratum", stratum)
            put("stratumURL", "eusolo.ckpool.org"); put("stratumPort", 3333); put("stratumUser", "bc1qexample.nerdqaxe")
            put("fallbackStratumURL", "bch.solopool.eu"); put("fallbackStratumPort", 3333); put("fallbackStratumUser", "qqexample.nerdqaxe")
            put("asicCount", 4); put("ASICModel", "BM1370"); put("hostname", "nerdqaxe")
        })
    }

    fun device(info: MinerSystemInfo?, ip: String = "192.168.1.28") =
        MinerDevice(PersistedMinerConfig(ipAddress = ip), ConnectionState.Online).copy(latestInfo = info)

    fun variant(file: String): MinerSystemInfo {
        val text = Fixtures::class.java.classLoader!!.getResource("variants/$file.json")!!.readText()
        return MinerSystemInfo.parse(text)
    }
}
