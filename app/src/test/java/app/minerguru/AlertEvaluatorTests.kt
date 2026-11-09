package app.minerguru

import app.minerguru.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AlertEvaluatorTests {
    @Before fun germanUi() = Lang.apply(AppLanguage.GERMAN)

    private val start = 1_800_000_000_000L
    private val off = ConnectionState.Offline("Timeout")

    private fun miner(state: ConnectionState) =
        MinerDevice(PersistedMinerConfig(id = "m1", ipAddress = "192.168.1.50", customName = "Gamma"), state)

    /** Polls every [stepMs] with the given states and returns all alerts. */
    private fun poll(e: AlertEvaluator, states: List<ConnectionState>, stepMs: Long = 60_000, fromMs: Long = 0) =
        states.flatMapIndexed { i, s -> e.evaluate(listOf(miner(s)), start + fromMs + i * stepMs) }

    @Test fun offlineAfterThreePollsAndRecovery() {
        val sent = poll(AlertEvaluator(), listOf(ConnectionState.Online, off, off, off, ConnectionState.Online))
        assertEquals(listOf(false, true), sent.map { it.isRecovery })
        assertEquals("Gamma ist offline", sent[0].title)
    }

    @Test fun shortOutageFromRestartIsIgnored() {
        assertTrue(poll(AlertEvaluator(), listOf(off, off, off, off, ConnectionState.Online), stepMs = 5_000).isEmpty())
    }

    @Test fun checkingStateDoesNotCount() {
        assertTrue(poll(AlertEvaluator(), listOf(off, ConnectionState.Checking, ConnectionState.Checking, off)).isEmpty())
    }

    @Test fun flappingIsThrottled() {
        val e = AlertEvaluator()
        assertEquals(2, poll(e, listOf(off, off, off, ConnectionState.Online)).size)
        assertTrue(poll(e, listOf(off, off, off, ConnectionState.Online), fromMs = 300_000).isEmpty())
    }

    @Test fun stateSurvivesJsonRoundTrip() {
        val e = AlertEvaluator()
        poll(e, listOf(off, off, off))
        val restored = AlertEvaluator.fromJson(e.toJson())
        val sent = restored.evaluate(listOf(miner(ConnectionState.Online)), start + 600_000)
        assertEquals(listOf(true), sent.map { it.isRecovery })
    }

    @Test fun brokenJsonStartsFresh() {
        assertTrue(AlertEvaluator.fromJson("not json").evaluate(listOf(miner(off)), start).isEmpty())
        assertTrue(AlertEvaluator.fromJson(null).evaluate(listOf(miner(off)), start).isEmpty())
    }
}

class AlertCombineTests {
    private fun event(name: String, recovery: Boolean = false, alone: Boolean = false) =
        AlertEvent(AlertKind.OFFLINE, name, name, "text", recovery, name, alone)

    @org.junit.Before fun germanUi() = Lang.apply(AppLanguage.GERMAN)

    @Test fun alertsOfOnePollCombinePerDirection() {
        val single = event("A")
        assertEquals(listOf(single), AlertEvent.combine(listOf(single)))
        val combined = AlertEvent.combine(listOf(event("A"), event("B"), event("C", recovery = true)))
        assertEquals(2, combined.size)
        assertEquals("2 Miner sind offline", combined[0].title)
        assertEquals("A, B", combined[0].body)
        assertEquals("", combined[0].minerId)
        assertEquals("C", combined[1].title)
    }

    @Test fun restartMessagesAreNotFolded() {
        val combined = AlertEvent.combine(listOf(event("A"), event("B", alone = true)))
        assertEquals(listOf("A", "B"), combined.map { it.title })
    }

    @Test fun confirmationUsesWordsNotFieldNames() {
        for (key in listOf("overclockEnabled", "poolMode", "poolBalance", "primaryPoolIndex", "secondaryPoolIndex", "temptarget", "pidTargetTemp", "pools.1.stratumTLS", "pools.1.stratumProtocol")) {
            assertTrue(ChangeLog.title(key) != key)
            assertTrue("stratum" !in ChangeLog.title(key))
        }
        assertEquals("Dual-Pool", ChangeLog.shown("1", "poolMode"))
        assertEquals("1", ChangeLog.shown("0", "primaryPoolIndex"))
        assertEquals("Eigene Taktwerte: aus → an", ChangeField("overclockEnabled", "0", "1").summary)
    }
}
