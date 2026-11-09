package app.minerguru

import app.minerguru.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MinerHealthTests {
    @Before fun germanUi() = useGermanTexts()

    private val start = 1_800_000_000_000L
    private val minute = 60_000L

    private fun info(
        temp: Double = 55.0, vr: Double = 60.0, limit: Double? = null, flag: Boolean = false,
        shares: Int = 100, rate: Double = 1000.0, uptime: Int = 7200, paused: Boolean = false
    ) = Fixtures.info(mapOf(
        "temp" to temp, "vrTemp" to vr, "overheat_mode" to (if (flag) 1 else 0), "sharesAccepted" to shares,
        "hashRate_1m" to rate, "hashRate" to rate, "uptimeSeconds" to uptime, "miningPaused" to paused,
        "stratumSuggestedDifficulty" to 800, "overheat_temp" to limit
    ))

    private fun run(h: MinerHealth, minutes: IntRange, make: (Int) -> MinerSystemInfo) {
        for (m in minutes) h.update(make(m), start + m * minute)
    }

    private fun miner(i: MinerSystemInfo) = MinerDevice(
        id = "m1", ipAddress = "192.168.1.50", customName = "Gamma", connectionState = ConnectionState.Online, latestInfo = i
    )

    @Test fun axeOSFlagAlarmsAtOnce() {
        val h = MinerHealth()
        h.update(info(flag = true), start)
        assertTrue(h.isOverheating)
    }

    @Test fun nerdOSNeedsThreePollsNearTheLimit() {
        val h = MinerHealth()
        repeat(2) { h.update(info(temp = 72.0, limit = 75.0), start) }
        assertFalse(h.isOverheating)
        h.update(info(temp = 72.0, limit = 75.0), start)
        assertTrue(h.isOverheating)
    }

    @Test fun singleSpikeStaysQuiet() {
        val h = MinerHealth()
        listOf(72.0, 71.0, 60.0, 74.0, 70.0, 60.0).forEach { h.update(info(temp = it, limit = 75.0), start) }
        assertFalse(h.isOverheating)
    }

    @Test fun voltageRegulatorCountsOnlyAboveTheLimit() {
        val h = MinerHealth()
        repeat(3) { h.update(info(vr = 75.0, limit = 75.0), start) }
        assertFalse(h.isOverheating)
        repeat(3) { h.update(info(vr = 76.0, limit = 75.0), start) }
        assertTrue(h.isOverheating)
    }

    @Test fun coolingDownNeedsThreePolls() {
        val h = MinerHealth()
        repeat(3) { h.update(info(temp = 73.0, limit = 75.0), start) }
        repeat(2) { h.update(info(temp = 60.0, limit = 75.0), start) }
        assertTrue(h.isOverheating)
        h.update(info(temp = 60.0, limit = 75.0), start)
        assertFalse(h.isOverheating)
    }

    @Test fun flatlineWhenSharesAndHashrateStand() {
        val h = MinerHealth()
        run(h, 0..9) { info() }
        assertFalse(h.isFlatlining)
        run(h, 10..10) { info() }
        assertTrue(h.isFlatlining)
        assertEquals(10, h.minutesWithoutShares(start + 10 * minute))
    }

    @Test fun aNewShareEndsTheFlatline() {
        val h = MinerHealth()
        run(h, 0..11) { info() }
        run(h, 12..12) { info(shares = 101) }
        assertFalse(h.isFlatlining)
    }

    @Test fun movingHashrateIsNoFlatline() {
        val h = MinerHealth()
        run(h, 0..30) { info(rate = 1000.0 + it) }
        assertFalse(h.isFlatlining)
    }

    @Test fun pausedRestartingOrIdleMinersAreLeftAlone() {
        val paused = MinerHealth(); val restarted = MinerHealth(); val idle = MinerHealth()
        run(paused, 0..30) { info(paused = true) }
        run(restarted, 0..4) { info(uptime = 60 + it * 60) }
        run(idle, 0..30) { info(rate = 0.0) }
        assertFalse(paused.isFlatlining)
        assertFalse(restarted.isFlatlining)
        assertFalse(idle.isFlatlining)
    }

    @Test fun poolDifficultyBeatsTheSuggestedOne() {
        assertEquals(65536.0, Fixtures.info(mapOf("stratumSuggestedDifficulty" to 1000, "poolDifficulty" to 65536)).activePoolDifficulty!!, 0.0)
        assertEquals(1000.0, Fixtures.info(mapOf("stratumSuggestedDifficulty" to 1000)).activePoolDifficulty!!, 0.0)
    }

    @Test fun highDifficultyWaitsForTenExpectedShares() {
        assertEquals(42_949_672L, MinerHealth.flatlineWindowMs(1000.0, 1_000_000.0))
        assertEquals(600_000L, MinerHealth.flatlineWindowMs(1000.0, 800.0))
        assertEquals(600_000L, MinerHealth.flatlineWindowMs(1000.0, null))
    }

    @Test fun overheatAlarmsOnceAndGivesTheAllClear() {
        val e = AlertEvaluator()
        val sent = listOf(true, true, false, false, false).flatMapIndexed { i, flag ->
            e.evaluate(listOf(miner(info(flag = flag))), start + i * minute)
        }
        assertEquals(listOf(AlertKind.OVERHEAT, AlertKind.OVERHEAT), sent.map { it.kind })
        assertEquals(listOf(false, true), sent.map { it.isRecovery })
        assertEquals("Gamma ist überhitzt", sent[0].title)
    }

    @Test fun flatlineSurvivesProcessDeath() {
        var e = AlertEvaluator()
        val sent = (0..11).flatMap { m ->
            e = AlertEvaluator.fromJson(e.toJson())
            e.evaluate(listOf(miner(info())), start + m * minute)
        }
        assertEquals(listOf(AlertKind.FLATLINE), sent.map { it.kind })
        val after = AlertEvaluator.fromJson(e.toJson()).evaluate(listOf(miner(info(shares = 101))), start + 12 * minute)
        assertEquals(listOf(true), after.map { it.isRecovery })
    }
}
