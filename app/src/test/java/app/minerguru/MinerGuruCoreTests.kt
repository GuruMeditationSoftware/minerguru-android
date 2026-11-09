package app.minerguru

import app.minerguru.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MinerGuruCoreTests {
    @Before fun germanUi() {
        DisplayFormat.locale = java.util.Locale.GERMANY
        useGermanTexts()
    }

    private fun bitaxe(overrides: Map<String, Any?> = emptyMap(), hashRate: Double = 1000.0, power: Double = 18.0): MinerSystemInfo {
        val fields = mutableMapOf<String, Any?>(
            "hashRate" to hashRate, "power" to power, "temp" to 55.0, "sharesAccepted" to 900, "sharesRejected" to 3,
            "bestDiff" to 4_400_000_000L, "stratumURL" to "eusolo.ckpool.org", "stratumPort" to 3333,
            "stratumUser" to "bc1qexamplewallet.bitaxe", "fallbackStratumURL" to "public-pool.io", "fallbackStratumPort" to 21496,
            "fallbackStratumUser" to "bc1qexamplewallet.bitaxe", "isUsingFallbackStratum" to 0, "responseTime" to 25.0,
            "ASICModel" to "BM1370", "boardVersion" to "601", "hostname" to "bitaxe", "version" to "v2.15.3"
        )
        fields.putAll(overrides)
        return MinerSystemInfo.fromJson(JSONObject(fields))
    }

    private fun dualPool(btcNetDiff: Double? = null): MinerSystemInfo {
        val pool1 = JSONObject(mapOf("connected" to true, "pingRtt" to 20.0, "accepted" to 10, "rejected" to 0))
        btcNetDiff?.let { pool1.put("networkDifficulty", it) }
        val pool2 = JSONObject(mapOf("connected" to true, "pingRtt" to 30.0, "accepted" to 1000, "rejected" to 2))
        val stratum = JSONObject(mapOf("poolMode" to 1, "activePoolMode" to 1, "poolBalance" to 1, "usingFallback" to false))
        stratum.put("pools", org.json.JSONArray().put(pool1).put(pool2))
        return MinerSystemInfo.fromJson(JSONObject().apply {
            put("deviceModel", "NerdQAxe++"); put("hashRate", 6000.0); put("power", 77.0); put("temp", 55.0)
            put("stratum", stratum); put("stratumURL", "eusolo.ckpool.org"); put("stratumPort", 3333)
            put("stratumUser", "bc1qexample.nerdqaxe"); put("fallbackStratumURL", "bch.solopool.eu")
            put("fallbackStratumPort", 3333); put("fallbackStratumUser", "qqexample.nerdqaxe")
            put("asicCount", 4); put("ASICModel", "BM1370"); put("hostname", "nerdqaxe")
        })
    }

    @Test fun decodesBitaxePayload() {
        val i = bitaxe()
        assertEquals(1000.0, i.hashRate, 0.001)
        assertEquals("4.40G", i.bestDiffFormatted)
        assertEquals("Bitaxe Gamma 601", i.detectedHardwareTitle)
        assertEquals(25.0, i.minerReportedPoolLatencyMs!!, 0.001)
        assertFalse(i.isUsingFallbackStratum)
        assertEquals(18.0 / 1.0, i.efficiencyJPerTH, 0.001)
    }

    @Test fun leniencyForStringsAndMillivolts() {
        val i = bitaxe(mapOf("hashRate" to "1234.5", "voltage" to 5148, "current" to 3200, "autofanspeed" to "true", "bestDiff" to "38.7M"))
        assertEquals(1234.5, i.hashRate, 0.001)
        assertEquals(5.148, i.voltageVolts, 0.0001)
        assertEquals(3.2, i.currentAmps, 0.0001)
        assertEquals(38_700_000.0, i.bestDiffNumeric, 1.0)
    }

    @Test fun dualPoolDetectionAndCoins() {
        val i = dualPool()
        assertTrue(i.isDualPoolMining)
        assertEquals(1, i.dualPoolPrimaryPercent)
        assertEquals(99, i.dualPoolSecondaryPercent)
        assertEquals(MinedCoin.BTC, i.primaryPoolCoin)
        assertEquals(MinedCoin.BCH, i.secondaryPoolCoin)
        assertEquals("NerdQAxe++", i.detectedHardwareTitle)
        assertEquals("BTC (1%) + BCH (99%)", i.activeCoinsSummary)
        assertEquals(20.0, i.primaryPoolMinerLatencyMs!!, 0.001)
    }

    @Test fun coinDetection() {
        assertEquals(MinedCoin.BTC, MinedCoin.detect("solo.ckpool.org", "bc1qabc.worker"))
        assertEquals(MinedCoin.BCH, MinedCoin.detect("pool.example", "bitcoincash:qq123.w"))
        assertEquals(MinedCoin.DGB, MinedCoin.detect("pool.example", "dgb1qxyz"))
        assertEquals(MinedCoin.UNKNOWN, MinedCoin.detect("", ""))
        assertEquals(MinedCoin.BTC, MinedCoin.detect("x.example", "foo", 100_000_000_000_000.0))
    }

    @Test fun stratumHostCleaning() {
        assertEquals("eusolo.ckpool.org", StratumHost.clean("stratum+tcp://eusolo.ckpool.org:3333/x"))
        assertEquals("eusolo.ckpool.org:3333", StratumHost.clean("stratum+tcp://eusolo.ckpool.org:3333/x", keepPort = true))
    }

    @Test fun soloChanceMath() {
        val s = FleetCoinMiningSummary(MinedCoin.BTC, 1000.0, 144_080_230_085_156.0, "x", false)
        val p = s.probability(SoloChanceTimeframe.PER_YEAR)
        assertTrue(p > 0 && p < 1e-3)
        assertEquals(0.0, FleetCoinMiningSummary(MinedCoin.BTC, 0.0, 1.0, "x", false).probability(SoloChanceTimeframe.PER_DAY), 0.0)
        assertTrue(s.formattedOddsRatio(SoloChanceTimeframe.PER_DAY).startsWith("1 : "))
    }

    @Test fun fleetSummariesSplitDualPool() {
        val miner = MinerDevice(PersistedMinerConfig(ipAddress = "1.2.3.4"), ConnectionState.Online).copy(latestInfo = dualPool())
        val s = FleetCoinMiningSummary.summaries(listOf(miner))
        assertEquals(listOf(MinedCoin.BTC, MinedCoin.BCH), s.map { it.coin })
        assertEquals(60.0, s[0].totalHashrateGHs, 0.001)
        assertEquals(5940.0, s[1].totalHashrateGHs, 0.001)
    }

    @Test fun shareSampleParsing() {
        val axe = ShareSample.parse("asic_result: ID: 0, Nonce F0AE9D21 diff 1284.3 of 874.", 1)!!
        assertEquals(1284.3, axe.difficulty, 0.001); assertEquals(874.0, axe.poolDifficulty!!, 0.001); assertTrue(axe.isSubmitted)
        val nerd = ShareSample.parse("(Sec) Job ID: 78 diff 141114.6/3075/791M", 1)!!
        assertEquals(ShareSample.Pool.SECONDARY, nerd.pool)
        assertNull(ShareSample.parse("something else", 1))
    }

    @Test fun logParsing() {
        val e = MinerLogEntry.parse("\u001B[0;32mI (123456) stratum: share accepted\u001B[0m")!!
        assertEquals(MinerLogLevel.INFO, e.level); assertEquals("stratum", e.componentTag); assertTrue(e.isShareAccepted)
        val w = MinerLogEntry.parse("W (1) asic_result: share rejected: low diff")!!
        assertTrue(w.isShareRejected); assertEquals(MinerLogLevel.WARNING, w.level)
        assertNull(MinerLogEntry.parse("   "))
    }

    @Test fun settingsPayloads() {
        val f = MinerSettingsForm(frequencyMHz = 600, coreVoltageMV = 1200, autoFanSpeed = false, manualFanPercent = 80f)
        val p = f.performancePayload(2).toJson()
        assertEquals(600, p.getInt("frequency")); assertEquals(0, p.getInt("autofanspeed")); assertEquals(80, p.getInt("fanspeed"))
        val auto = MinerSettingsForm().performancePayload(2).toJson()
        assertEquals(2, auto.getInt("autofanspeed")); assertFalse(auto.has("fanspeed"))

        val pool = MinerSettingsForm(
            slots = listOf(MinerSettingsForm.PoolEndpoint("stratum+tcp://a.b:3333", 3333, " u "), MinerSettingsForm.PoolEndpoint()),
            dualPool = true, poolBalance = 30f
        ).poolPayload(supportsDualPool = true).toJson()
        assertEquals("a.b", pool.getString("stratumURL")); assertEquals(1, pool.getInt("poolMode")); assertEquals(30, pool.getInt("poolBalance"))
        assertFalse(pool.has("fallbackStratumURL"))
        assertFalse(MinerSettingsForm().poolPayload(false).toJson().has("poolMode"))
    }

    @Test fun formatting() {
        assertEquals("1.05 TH/s", MinerFormatting.formatHashrate(1050.0))
        assertEquals("2d 3h 4m", MinerFormatting.formatUptime(2 * 86400 + 3 * 3600 + 4 * 60))
        assertEquals("1.234,50", DisplayFormat.decimal(1234.5, 2))
        assertEquals("0,45 %", DisplayFormat.percent(0.45, 2))
        assertEquals(LatencyQuality.GOOD, LatencyQuality.evaluate(60.0))
    }
}

