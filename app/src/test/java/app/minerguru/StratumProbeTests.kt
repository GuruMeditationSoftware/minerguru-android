package app.minerguru

import app.minerguru.model.*
import app.minerguru.model.BlockTemplateSummary.PayoutState
import app.minerguru.service.BlockProbeStore
import app.minerguru.service.BlockProbeTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Stratum probe (MG-04): addresses, coinbase decoding and the 17 recorded pools of the macOS app. */
class StratumProbeTests {
    private val genesis = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val genesisScript = "76a91462e907b15cbf27d5425399ebf6f0fb50ebb88f1888ac"

    /** One recorded pool endpoint from `stratum-captures/expected.json`. */
    private class Capture(val o: JSONObject) {
        val file: String = o.getString("file")
        val coin: MinedCoin = MinedCoin.entries.firstOrNull { it.symbol == o.getString("coin") } ?: MinedCoin.UNKNOWN
        val user: String = o.getString("user")
        val authorized: Boolean = o.getBoolean("authorized")
        val outputs: List<JSONObject> = o.optJSONArray("outputs")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

        fun lines(): List<String> = resource("stratum-captures/$file").lines().filter { it.isNotBlank() }
    }

    private val captures: List<Capture> = JSONArray(resource("stratum-captures/expected.json")).let { a ->
        (0 until a.length()).map { Capture(a.getJSONObject(it)) }
    }

    private fun capture(file: String) = captures.single { it.file == file }

    private fun job(capture: Capture): StratumJob? {
        val session = StratumProbeSession(capture.user, "test")
        for (line in capture.lines()) (session.receive(line) as? StratumProbeSession.Event.Job)?.let { return it.job }
        return null
    }

    private fun block(capture: Capture) = job(capture)?.let { BlockTemplateSummary.from(it, capture.coin, capture.user) }

    // region Addresses

    @Test fun sameKeyGivesTheSameScript() {
        listOf(
            genesis,
            "bitcoincash:qp3wjpa3tjlj042z2wv7hahsldgwhwy0rq9sywjpyy",
            "qp3wjpa3tjlj042z2wv7hahsldgwhwy0rq9sywjpyy",
            "ecash:qp3wjpa3tjlj042z2wv7hahsldgwhwy0rquas9fmzn",
            "solo:$genesis"
        ).forEach { assertEquals(it, genesisScript, CoinAddress.scriptPubKey(it)?.let(CoinbaseDecoder::hex)) }
    }

    @Test fun addressesSurviveTheRoundTrip() {
        listOf(
            "bc1q28kkr5hk4gnqe3evma6runjrd2pvqyp8fpwfzu" to MinedCoin.BTC,
            "bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297" to MinedCoin.BTC,
            "3LyFQTKbPwXtgrsGpMbbEECY7ViBeFbonU" to MinedCoin.BTC,
            "DRNiw2k4iFuHGS2fF1wMahounXo6H9iGbh" to MinedCoin.DGB,
            "dgb1qdxxrkdx4m9r7hlqa6smwzwawfen56nndrnze36" to MinedCoin.DGB,
            "bitcoincash:qp3wjpa3tjlj042z2wv7hahsldgwhwy0rq9sywjpyy" to MinedCoin.BCH,
            "ecash:qp3wjpa3tjlj042z2wv7hahsldgwhwy0rquas9fmzn" to MinedCoin.XEC
        ).forEach { (address, coin) ->
            val script = CoinAddress.scriptPubKey(address)
            assertNotNull(address, script)
            assertEquals(address, CoinAddress.address(script!!, coin))
        }
    }

    @Test fun accountNamesAndTyposAreNoAddress() {
        listOf("myaccount", "bc1q28kkr5hk4gnqe3evma6runjrd2pvqyp8fpwfzv", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb", "")
            .forEach { assertNull(it, CoinAddress.scriptPubKey(it)) }
    }

    // endregion

    // region Recorded pools

    @Test fun allCapturesAreThere() = assertEquals(17, captures.size)

    @Test fun probeReadsTheRecordedTemplate() = captures.forEach { capture ->
        val name = capture.file
        val session = StratumProbeSession(capture.user, "test")
        var event: StratumProbeSession.Event? = null
        for (line in capture.lines()) {
            if (event != null) break
            event = session.receive(line)
        }
        if (!capture.authorized) {
            assertEquals(name, StratumProbeSession.Event.Failed(StratumProbeError.Rejected("Invalid address")), event)
            return@forEach
        }
        val job = (event as? StratumProbeSession.Event.Job)?.job
        assertNotNull("$name: no job ($event)", job)
        assertEquals(name, capture.o.getInt("merkleBranches"), job!!.merkleBranchCount)

        val coinbase = job.coinbase
        assertNotNull(name, coinbase)
        assertEquals(name, capture.o.getInt("height"), coinbase!!.height)
        assertEquals(name, capture.o.getLong("totalSats"), coinbase.totalSats)
        assertEquals(name, capture.outputs.map { it.optLong("value") }, coinbase.outputs.map { it.valueSats })
        assertEquals(name, capture.outputs.map { it.optString("scriptPubKey") }, coinbase.outputs.map { CoinbaseDecoder.hex(it.script) })

        // The recording printed every address in Bitcoin notation; BCH and eCash use CashAddr here.
        if (capture.coin in listOf(MinedCoin.BTC, MinedCoin.DGB, MinedCoin.BSV)) {
            coinbase.outputs.zip(capture.outputs).filter { !it.second.isNull("address") }.forEach { (output, reference) ->
                assertEquals(name, reference.getString("address"), CoinAddress.address(output.script, capture.coin))
            }
        }

        val block = BlockTemplateSummary.from(job, capture.coin, capture.user)
        assertNotNull(name, block)
        val paysUs = capture.outputs.any { it.optBoolean("paysTestAddress") }
        assertEquals(name, if (paysUs) PayoutState.VERIFIED else PayoutState.NOT_FOUND, block!!.payoutState)
        assertEquals(name, paysUs, (block.userSats ?: 0.0) > 0)
        assertEquals(name, capture.outputs.size, block.outputs.size + block.othersCount)
    }

    @Test fun sharedCoinbaseListsTheLargestAndSumsTheRest() {
        val block = block(capture("ocean-btc.jsonl"))!!
        assertEquals(BlockTemplateSummary.MAX_LISTED_RECIPIENTS, block.outputs.count { !it.isDataOutput })
        assertTrue(block.othersCount > 0)
        assertEquals(block.totalSats!!, block.outputs.sumOf { it.valueSats } + block.othersValueSats, 0.0)
    }

    @Test fun nerdminersWorksWithoutExtranonce() {
        val block = block(capture("nerdminers-btc.jsonl"))!!
        assertEquals(PayoutState.VERIFIED, block.payoutState)
        assertTrue(block.poolTag!!.contains("nerdminers"))
        assertEquals(2049..4096, block.transactionRange)
    }

    /** The branch count comes from the pool; from 31 branches on the shift overflowed. */
    @Test fun oversizedMerkleBranchesStayInRange() {
        val job = job(capture("nerdminers-btc.jsonl"))!!
        for (branches in listOf(31, 32, 63, 1000)) {
            val block = BlockTemplateSummary.from(job.copy(merkleBranchCount = branches), MinedCoin.BTC, capture("nerdminers-btc.jsonl").user)!!
            assertEquals((1 shl 29) + 1..(1 shl 30), block.transactionRange)
        }
    }

    @Test fun deletedMinerNeverWidensARule() {
        assertEquals(setOf("a"), minerScopeWithout(setOf("a"), "a"))
        assertEquals(setOf("b"), minerScopeWithout(setOf("a", "b"), "a"))
        assertEquals(emptySet<String>(), minerScopeWithout(emptySet(), "a"))
        assertFalse(ScheduleRule(trigger = ScheduleTrigger.Zone("z"), action = PowerAction.PAUSE, minerIds = minerScopeWithout(setOf("a"), "a")).appliesTo("b"))
    }

    @Test fun witnessCommitmentReadsLikeTheFirmwareReportsIt() {
        val block = block(capture("ckpool-eu-btc.jsonl"))!!
        assertTrue(block.outputs.any { it.isWitnessCommitment })
        assertEquals(genesis, block.outputs.first().address)
    }

    @Test fun cashAddrForBitcoinCash() {
        val block = block(capture("solopooleu-bch.jsonl"))!!
        assertEquals(PayoutState.VERIFIED, block.payoutState)
        assertTrue(block.outputs.first { it.isUserOutput }.address.startsWith("bitcoincash:"))
    }

    @Test fun accountUserIsNotChecked() {
        val job = job(capture("kryptex-bsv.jsonl"))!!
        assertEquals(PayoutState.NOT_APPLICABLE, BlockTemplateSummary.from(job, MinedCoin.BSV, "myaccount.worker1")?.payoutState)
    }

    // endregion

    // region Session

    @Test fun jobBeforeAuthorizeResultCounts() {
        val session = StratumProbeSession("$genesis.minerguru", "test")
        assertEquals(2, session.openingLines.size)
        assertTrue(session.openingLines[1].contains("mining.authorize"))
        assertNull(session.receive("""{"id":1,"result":[[["mining.notify","ab"]],"f000000f",8],"error":null}"""))
        val event = session.receive("""{"id":null,"method":"mining.notify","params":["1","00","01","02",[],"20000000","1703a30c","6900f0a0",true]}""")
        val job = (event as StratumProbeSession.Event.Job).job
        assertEquals("f000000f", job.extranonce1)
        assertEquals(8, job.extranonce2Size)
    }

    @Test fun probeNeverSubmits() {
        assertFalse(StratumProbeSession("x", "test").openingLines.any { it.contains("mining.submit") })
    }

    @Test fun openingLinesAreValidJson() {
        val lines = StratumProbeSession("$genesis.minerguru", "MinerGuru/1.0 (block view, no mining)").openingLines
        assertEquals("mining.subscribe", JSONObject(lines[0]).getString("method"))
        assertEquals("$genesis.minerguru", JSONObject(lines[1]).getJSONArray("params").getString(0))
    }

    // endregion

    // region Store

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun storeThrottlesUnlessForced() = runTest {
        var clock = 0L
        var calls = 0
        val ckpool = capture("ckpool-eu-btc.jsonl")
        val store = BlockProbeStore(this, fetch = { calls++; job(ckpool)!! }, now = { clock })
        val target = BlockProbeTarget("eusolo.ckpool.org", 3333, false, "$genesis.bitaxe", MinedCoin.BTC, BlockProbeTarget.Slot.PRIMARY)

        store.refresh(target)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(store.entries[target]?.state is BlockProbeStore.State.Loaded)

        clock += 60_000
        store.refresh(target)
        advanceUntilIdle()
        assertEquals(1, calls)

        store.refresh(target, force = true)
        advanceUntilIdle()
        assertEquals(2, calls)

        clock += BlockProbeStore.MIN_INTERVAL_MS
        store.refresh(target)
        advanceUntilIdle()
        assertEquals(3, calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun storeReportsRejection() = runTest {
        val store = BlockProbeStore(this, fetch = { throw StratumProbeError.Rejected("Invalid address") })
        val target = BlockProbeTarget("solo-bch.2miners.com", 4040, false, "$genesis.x", MinedCoin.BCH, BlockProbeTarget.Slot.PRIMARY)
        store.refresh(target)
        advanceUntilIdle()
        val state = store.entries[target]?.state as BlockProbeStore.State.Failed
        assertTrue(state.message.contains("Invalid address"))
        assertTrue(store.running.isEmpty())
    }

    // endregion

    companion object {
        private fun resource(path: String): String =
            StratumProbeTests::class.java.classLoader!!.getResource(path)!!.readText()
    }
}
