package app.minerguru

import app.minerguru.model.HistoryMetric
import app.minerguru.model.HistoryPoint
import app.minerguru.model.MinedCoin
import app.minerguru.service.MinerApiClient
import org.junit.Assert.*
import org.junit.Test

class HostValidationTests {
    @Test fun `addresses and names pass`() {
        listOf("192.168.1.150", "192.168.1.150:8080", "bitaxe.local", "bitaxe.local:80", "http://10.0.2.2:48001/", "miner-1")
            .forEach { assertTrue(it, MinerApiClient.isValidHost(it)) }
    }

    @Test fun `broken input fails`() {
        listOf("", " ", "abc:99999", "abc:0", "abc:", "a b", "300.1.1.1", "1.2.3", "a:1:2", "-abc", "abc_def")
            .forEach { assertFalse(it, MinerApiClient.isValidHost(it)) }
    }
}

class CoinMismatchTests {
    @Test fun `a BCH address on a Bitcoin pool warns`() {
        val found = MinedCoin.mismatch("public-pool.io", "bitcoincash:qqqq.worker")
        assertEquals(MinedCoin.BTC to MinedCoin.BCH, found)
        assertNotNull(MinedCoin.mismatchWarning("public-pool.io", "bitcoincash:qqqq.worker"))
    }

    @Test fun `a Bitcoin address on a BCH pool warns`() {
        assertEquals(MinedCoin.BCH to MinedCoin.BTC, MinedCoin.mismatch("bch.solo.ckpool.org", "bc1qabc.worker"))
    }

    @Test fun `matching and unknown combinations stay quiet`() {
        assertNull(MinedCoin.mismatch("public-pool.io", "bc1qabc.worker"))
        assertNull(MinedCoin.mismatch("bch.solo.ckpool.org", "bitcoincash:qqqq.worker"))
        assertNull(MinedCoin.mismatch("pool.example.com", "bitcoincash:qqqq.worker"))
    }

    @Test fun `a legacy address fits several coins and never warns`() {
        assertNull(MinedCoin.mismatch("bch.solo.ckpool.org", "1BoatSLRHtKNngkdXEeobR76b53LETtpyT.worker"))
        assertNull(MinedCoin.mismatch("public-pool.io", "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy.worker"))
    }
}

class HistoryGapTests {
    private fun point(minute: Long) = HistoryPoint("m", minute * 60_000, 1, 1, hashRateGHs = 500.0)

    @Test fun `a stretch without rows stays empty`() {
        val values = HistoryMetric.HASHRATE.valuesWithGaps(listOf(point(0), point(1), point(10), point(11)), 60)
        assertEquals(listOf(500.0, 500.0) + List(8) { null } + listOf(500.0, 500.0), values)
    }

    @Test fun `steady rows have no gap`() {
        val values = HistoryMetric.HASHRATE.valuesWithGaps(listOf(point(0), point(2), point(5)), 60)
        assertEquals(3, values.size)
        assertFalse(values.contains(null))
    }

    @Test fun `no points give no values`() {
        assertTrue(HistoryMetric.HASHRATE.valuesWithGaps(emptyList(), 60).isEmpty())
    }
}
