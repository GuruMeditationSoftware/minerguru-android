package app.minerguru

import app.minerguru.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FallbackWatchTests {
    @Before fun germanUi() = useGermanTexts()

    private val start = 1_800_000_000_000L
    private fun at(m: Int) = start + m * 60_000L

    private fun info(fallback: Boolean = true, uptime: Int = 7200, extra: Map<String, Any?> = emptyMap()) = Fixtures.info(mapOf(
        "isUsingFallbackStratum" to (if (fallback) 1 else 0), "uptimeSeconds" to uptime,
        "stratumURL" to "a.example", "fallbackStratumURL" to "b.example", "hashRate_1m" to 1000, "hashRate" to 1000
    ) + extra)

    @Test fun fallbackAlarmsAfterTenMinutesInARow() {
        val h = MinerHealth()
        h.update(info(), at(0))
        h.update(info(), at(9))
        assertFalse(h.isOnFallback)
        h.update(info(), at(10))
        assertTrue(h.isOnFallback)
        h.update(info(fallback = false), at(11))
        assertFalse(h.isOnFallback)
    }

    @Test fun startupIsForgiven() {
        val h = MinerHealth()
        h.update(info(uptime = 60), at(0))
        h.update(info(uptime = 310), at(5))
        h.update(info(uptime = 700), at(12))
        assertFalse(h.isOnFallback)
        h.update(info(uptime = 910), at(15))
        assertTrue(h.isOnFallback)
    }

    @Test fun stuckNeedsThePrimaryPoolReachableForFifteenMinutes() {
        val h = MinerHealth()
        h.update(info(), at(0), primaryReachable = false)
        h.update(info(), at(10), primaryReachable = true)
        h.update(info(), at(24), primaryReachable = true)
        assertTrue(h.isOnFallback && !h.isStuckOnFallback)
        h.update(info(), at(25), primaryReachable = true)
        assertTrue(h.isStuckOnFallback)
    }

    @Test fun stateSurvivesTheWorker() {
        val h = MinerHealth()
        h.update(info(), at(0), primaryReachable = true)
        val restored = MinerHealth.fromJson(org.json.JSONObject(h.toJson().toString()))
        restored.update(info(), at(10), primaryReachable = true)
        assertTrue(restored.isOnFallback)
    }

    @Test fun answersOutOfOrderAreNoRestart() {
        val h = MinerHealth()
        h.update(info(fallback = false, uptime = 5000), at(0))
        h.update(info(fallback = false, uptime = 4999), at(0))
        assertFalse(h.restartedUnexpectedly)
    }

    @Test fun droppingUptimeIsAnUnexpectedRestart() {
        val h = MinerHealth()
        h.update(info(fallback = false, uptime = 5000), at(0))
        h.update(info(fallback = false, uptime = 20), at(1))
        assertTrue(h.restartedUnexpectedly)
        h.update(info(fallback = false, uptime = 80), at(2))
        assertFalse(h.restartedUnexpectedly)
        h.update(info(fallback = false, uptime = 10), at(3), restartExpected = true)
        assertFalse(h.restartedUnexpectedly)
    }

    @Test fun resetReasonComesFromEitherFirmware() {
        assertEquals("Brownout", info(extra = mapOf("resetReason" to "Brownout")).resetReason)
        assertEquals("Watchdog", info(extra = mapOf("lastResetReason" to "Watchdog")).resetReason)
    }

    @Test fun watchdogLimits() {
        val h = MinerHealth()
        repeat(3) { h.update(info(), at(listOf(0, 10, 15)[it]), primaryReachable = true) }
        assertNull(h.dueAutoRestart(false, false, null, at(15)))
        assertEquals(MinerHealth.AutoRestartReason.STUCK_ON_FALLBACK, h.dueAutoRestart(false, true, null, at(15)))
        assertNull(h.dueAutoRestart(false, true, at(-30), at(15)))
        h.update(info(extra = mapOf("overheat_mode" to 1)), at(16), primaryReachable = true)
        assertNull(h.dueAutoRestart(true, true, null, at(16)))
    }
}

class PayoutCheckTests {
    @Before fun germanUi() = useGermanTexts()

    private val mine = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val other = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val cash = "qruvqq2x9vyd9gykre058r4lda4gulpres33nr9aml"
    private val start = 1_800_000_000_000L

    @Test fun verdicts() {
        assertEquals(PayoutVerdict.FACTORY_DEFAULT, PayoutCheck.verdict("replace-this-with-your-btc-address.bitaxe", null))
        assertEquals(PayoutVerdict.FACTORY_DEFAULT, PayoutCheck.verdict("bc1q7n70rumyv6lvu8avpml0c3uggvssfu52egum3q.nerdqaxe", mine))
        assertEquals(PayoutVerdict.EXPECTED, PayoutCheck.verdict("$mine.w", mine))
        assertEquals(PayoutVerdict.FOREIGN, PayoutCheck.verdict("$other.w", mine))
        assertEquals(PayoutVerdict.VALID, PayoutCheck.verdict("$other.w", null))
        assertEquals(PayoutVerdict.VALID, PayoutCheck.verdict("$other.w", "bc1qar0"))
        assertEquals(PayoutVerdict.EXPECTED, PayoutCheck.verdict("$cash.w", "bitcoincash:$cash"))
        assertEquals(PayoutVerdict.ACCOUNT, PayoutCheck.verdict("wicked_miner.1", mine))
        assertEquals(PayoutVerdict.ACCOUNT, PayoutCheck.verdict("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdx.w", null))
        assertEquals(PayoutVerdict.EMPTY, PayoutCheck.verdict("", mine))
    }

    @Test fun workerStaysWhenTheAddressChanges() {
        assertEquals("new.worker", PayoutCheck.user("old.worker", "new"))
        assertEquals("new", PayoutCheck.user("old", "new"))
    }

    @Test fun slotsOfBothKinds() {
        val legacy = Fixtures.bitaxeInfo(mapOf("stratumUser" to "$mine.a", "fallbackStratumUser" to "$other.a", "fallbackStratumURL" to "public-pool.io"))
        val slots = PayoutCheck.slots(legacy, mapOf("BTC" to mine))
        assertEquals(listOf("primary", "fallback"), slots.map { it.key })
        assertEquals(listOf(PayoutVerdict.EXPECTED, PayoutVerdict.FOREIGN), slots.map { it.verdict })
        assertTrue(PayoutCheck.slots(Fixtures.axeSlotsInfo(), emptyMap()).all { it.key.startsWith("pool") })
    }

    @Test fun missingAddressCountsOnlyOnBitcoinSoloPools() {
        val fields = mapOf("coinbasePayoutStatus" to "not_found", "stratumUser" to "$mine.a", "stratumURL" to "solo.ckpool.org")
        assertTrue(PayoutCheck.blockSkipsMiner(Fixtures.bitaxeInfo(fields)))
        assertFalse(PayoutCheck.blockSkipsMiner(Fixtures.bitaxeInfo(fields + ("stratumURL" to "pool.braiins.com"))))
        assertFalse(PayoutCheck.blockSkipsMiner(Fixtures.bitaxeInfo(fields + ("coinbasePayoutStatus" to "verified"))))
    }

    private fun device(host: String, fields: Map<String, Any?>, reachable: Boolean = false) = MinerDevice(
        id = "m$host", ipAddress = host, customName = "Gamma", connectionState = ConnectionState.Online,
        latestInfo = Fixtures.bitaxeInfo(mapOf("uptimeSeconds" to 7200) + fields),
        poolLatency = if (reachable) PoolLatencyMeasurement(null, 20.0, null) else null
    )

    @Test fun changedAddressAlarmsAndOwnChangeStaysQuiet() {
        val log = ChangeLog()
        val e = AlertEvaluator()
        e.evaluate(listOf(device("10.0.0.1", mapOf("stratumUser" to "$mine.a"))), start, log = log)
        val sent = e.evaluate(listOf(device("10.0.0.1", mapOf("stratumUser" to "$other.a"))), start + 60_000, log = log)
        assertEquals(listOf(AlertKind.PAYOUT), sent.map { it.kind })
        log.recordWrite("10.0.0.1", "PATCH", "/api/system", null, null, start + 120_000)
        val own = e.evaluate(listOf(device("10.0.0.1", mapOf("stratumUser" to "$mine.a"))), start + 180_000, log = log)
        assertTrue(own.isEmpty())
    }

    @Test fun defaultAddressAlarmsOnceAcrossWorkers() {
        val log = ChangeLog()
        val stock = device("10.0.0.2", mapOf("stratumUser" to "replace-this-with-your-btc-address.bitaxe"))
        val first = AlertEvaluator()
        assertTrue(first.evaluate(listOf(stock), start, log = log).any { it.kind == AlertKind.PAYOUT })
        val second = AlertEvaluator.fromJson(first.toJson())
        assertTrue(second.evaluate(listOf(stock), start + 60_000, log = log).isEmpty())
    }

    @Test fun foreignAddressFollowsTheExpectedOne() {
        val log = ChangeLog()
        val value = device("10.0.0.3", mapOf("stratumUser" to "$other.a", "fallbackStratumUser" to "$mine.a"))
        val e = AlertEvaluator()
        assertTrue(e.evaluate(listOf(value), start, log = log).isEmpty())
        val sent = e.evaluate(listOf(value), start + 60_000, WatchSettings(mapOf("BTC" to mine)), log)
        assertEquals(listOf(AlertKind.PAYOUT), sent.map { it.kind })
    }

    @Test fun blockWithoutTheMinerNeedsThreePolls() {
        val log = ChangeLog()
        val value = device("10.0.0.4", mapOf("stratumUser" to "$mine.a", "fallbackStratumUser" to "$mine.a",
            "stratumURL" to "solo.ckpool.org", "coinbasePayoutStatus" to "not_found"))
        val e = AlertEvaluator()
        val counts = (0..3).map { e.evaluate(listOf(value), start + it * 60_000L, log = log).size }
        assertEquals(listOf(0, 0, 1, 0), counts)
    }

    @Test fun watchdogAsksForOneRestart() {
        val log = ChangeLog()
        val stuck = device("10.0.0.5", mapOf("isUsingFallbackStratum" to 1), reachable = true)
        val settings = WatchSettings(restartsToPrimary = true)
        val e = AlertEvaluator()
        for (m in listOf(0, 10, 15)) e.evaluate(listOf(stuck), start + m * 60_000L, settings, log)
        assertEquals(1, e.dueRestarts.size)
        log.record(ChangeEntry(start + 15 * 60_000L, "10.0.0.5", "restart", emptyList(), ChangeSource.WATCHDOG, null))
        e.evaluate(listOf(stuck), start + 16 * 60_000L, settings, log)
        assertTrue(e.dueRestarts.isEmpty())
    }

    @Test fun readOnlySaysOnceAnHourThatARestartWasLeftOut() {
        val log = ChangeLog().apply { isReadOnly = true }
        val stuck = device("10.0.0.6", mapOf("isUsingFallbackStratum" to 1), reachable = true)
        val settings = WatchSettings(restartsToPrimary = true)
        val e = AlertEvaluator()
        var skipped = 0
        for (m in listOf(0, 10, 15, 16, 30, 76)) {
            val events = e.evaluate(listOf(stuck), start + m * 60_000L, settings, log)
            assertTrue(e.dueRestarts.isEmpty())
            skipped += events.count { "nicht neu gestartet" in it.title }
        }
        assertEquals(2, skipped)
    }

    /** AlertCenter stores the evaluator after every poll; the hour limit has to survive that. */
    @Test fun readOnlyLimitSurvivesStorage() {
        val log = ChangeLog().apply { isReadOnly = true }
        val stuck = device("10.0.0.7", mapOf("isUsingFallbackStratum" to 1), reachable = true)
        val settings = WatchSettings(restartsToPrimary = true)
        var stored = AlertEvaluator().toJson()
        var skipped = 0
        for (m in listOf(0, 10, 15, 16, 17, 30, 76)) {
            val e = AlertEvaluator.fromJson(stored)
            skipped += e.evaluate(listOf(stuck), start + m * 60_000L, settings, log).count { "nicht neu gestartet" in it.title }
            stored = e.toJson()
        }
        assertEquals(2, skipped)
    }
}

class ChangeLogTests {
    @Before fun germanUi() = useGermanTexts()

    @Test fun showsOnlyChangedValues() {
        val current = ChangeLog.flatten("""{"frequency":500,"coreVoltage":1150}""")!!
        assertEquals(listOf(ChangeField("frequency", "500", "600")), ChangeLog.changes("""{"frequency":600,"coreVoltage":1150}""", current))
    }

    @Test fun comparesPoolSlotsByID() {
        val current = ChangeLog.flatten("""{"pools":[{"id":2,"stratumUser":"old.a"}]}""")!!
        assertEquals(listOf(ChangeField("pools.2.stratumUser", "old.a", "new.a")),
            ChangeLog.changes("""{"pools":[{"id":2,"stratumUser":"new.a"}]}""", current))
    }

    @Test fun unchangedCertificateIsNoChange() {
        val current = ChangeLog.flatten("""{"pools":[{"id":0,"stratumCert":"PEM"}]}""")!!
        assertEquals(emptyList<ChangeField>(), ChangeLog.changes("""{"pools":[{"id":0,"stratumCert":"PEM"}]}""", current))
        assertEquals(
            listOf(ChangeField("pools.0.stratumCert", null, "•••")),
            ChangeLog.changes("""{"pools":[{"id":0,"stratumCert":"NEW"}]}""", current)
        )
    }

    @Test fun passwordsNeverShow() {
        assertEquals(listOf(ChangeField("stratumPassword", null, "•••")), ChangeLog.changes("""{"stratumPassword":"secret"}""", emptyMap()))
        assertTrue(ChangeLog.changes("""{"pools":[{"id":0,"stratumPassword":"*****"}]}""", emptyMap()).isEmpty())
    }

    @Test fun storesEntries() {
        val folder = java.nio.file.Files.createTempDirectory("changes").toFile()
        val log = ChangeLog()
        log.activate(folder)
        log.noteValues("1.2.3.4", """{"frequency":500}""")
        log.recordWrite("1.2.3.4", "PATCH", "/api/system", """{"frequency":600}""", null)
        log.recordWrite("1.2.3.4", "POST", "/api/system/restart", null, "HTTP 500")
        log.recordWrite("1.2.3.4", "POST", "/api/otp/session", null, null)
        val again = ChangeLog().apply { activate(folder) }
        assertEquals(2, again.entries().size)
        assertEquals("restart", again.entries().first().action)
        assertEquals("HTTP 500", again.entries().first().failure)
        assertTrue(again.exportText().contains("frequency: 500 -> 600"))
        assertNull(again.lastAutomaticRestart("1.2.3.4"))
        assertFalse(again.isReadOnly)
        again.isReadOnly = true
        assertTrue(ChangeLog().apply { activate(folder) }.isReadOnly)
        folder.deleteRecursively()
    }
}
