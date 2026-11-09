package app.minerguru

import app.minerguru.model.*
import app.minerguru.model.BlockTemplateSummary.PayoutState
import app.minerguru.service.BlockProbeTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Current block template from the AxeOS 2.13+ fields (MG-25). */
class BlockTemplateTests {
    /** ckpool-style template: 98 % to the miner, 2 % pool fee, witness commitment. */
    private fun decodedFields(): MutableMap<String, Any?> = mutableMapOf(
        "blockHeight" to 974_212,
        "scriptsig" to "/solo.ckpool.org/",
        "blockSignals" to JSONArray(listOf("BIP-54")),
        "stratumDecodeCoinbase" to true,
        "coinbaseValueTotalSatoshis" to 314_000_000,
        "coinbaseValueUserSatoshis" to 307_720_000,
        "coinbaseOutputs" to JSONArray()
            .put(JSONObject(mapOf("value" to 307_720_000, "address" to "bc1qexamplewallet", "isUserOutput" to 1)))
            .put(JSONObject(mapOf("value" to 6_280_000, "address" to "bc1qpoolfeeexample", "isUserOutput" to 0)))
            .put(JSONObject(mapOf("value" to 0, "address" to "OP_RETURN: .!..a.Xb..9.f..Z.", "isUserOutput" to 0))),
        "coinbaseOthersCount" to 0,
        "coinbaseOthersValueSatoshis" to 0
    )

    private fun block(fields: Map<String, Any?>) = BlockTemplateSummary.from(Fixtures.bitaxeInfo(fields))

    @Test fun decodesTheAxeOSBlockFields() {
        val block = block(decodedFields())!!
        assertEquals(974_212, block.height)
        assertEquals("/solo.ckpool.org/", block.poolTag)
        assertEquals(listOf("BIP-54"), block.signals)
        assertEquals(MinedCoin.BTC, block.coin)
        assertEquals(3, block.outputs.size)
        assertTrue(block.outputs[0].isUserOutput)
        assertEquals(PayoutState.VERIFIED, block.payoutState)
        assertEquals(1_500_000.0, block.feesSats!!, 0.0)
        assertEquals(0.98, block.userShare!!, 1e-9)
    }

    @Test fun witnessCommitmentIsDataNotPayout() {
        val commitment = CoinbaseOutput(0.0, "OP_RETURN: .!..a.Xb..9.f..Z.")
        assertTrue(commitment.isDataOutput)
        assertTrue(commitment.isWitnessCommitment)
        assertNull(commitment.dataText)

        val tag = CoinbaseOutput(0.0, "OP_RETURN: RSKBLOCK:abc123")
        assertFalse(tag.isWitnessCommitment)
        assertEquals("RSKBLOCK:abc123", tag.dataText)
    }

    @Test fun decodingOffKeepsTheSumButHidesRecipients() {
        val block = block(decodedFields().apply { put("stratumDecodeCoinbase", false) })!!
        assertEquals(PayoutState.DECODING_OFF, block.payoutState)
        assertTrue(block.outputs.isEmpty())
        assertEquals(314_000_000.0, block.totalSats!!, 0.0)
        assertNull(block.userSats)
    }

    @Test fun activeSlotDecidesAboutDecoding() {
        // AxeOS 2.15: the fallback slot is active and does not decode, the primary does.
        val fields = decodedFields().apply {
            put("isUsingFallbackStratum", 1)
            put("primaryPoolIndex", 0)
            put("secondaryPoolIndex", 1)
            put("pools", JSONArray()
                .put(JSONObject(mapOf("id" to 0, "stratumURL" to "eusolo.ckpool.org", "stratumUser" to "bc1qexamplewallet.bitaxe", "stratumDecodeCoinbase" to true)))
                .put(JSONObject(mapOf("id" to 1, "stratumURL" to "public-pool.io", "stratumUser" to "bc1qexamplewallet.bitaxe", "stratumDecodeCoinbase" to false))))
        }
        val info = Fixtures.bitaxeInfo(fields)
        assertEquals(false, info.activePoolDecodesCoinbase)
        assertEquals(PayoutState.DECODING_OFF, BlockTemplateSummary.from(info)?.payoutState)
    }

    @Test fun leftoverSumFromAnUndecodedJobIsIgnored() {
        val block = block(decodedFields().apply { put("coinbaseValueTotalSatoshis", 3.179677500620704e17) })!!
        assertNull(block.totalSats)
        assertNull(block.feesSats)
        assertNull(block.userShare)
    }

    @Test fun firmwareStatusWins() {
        mapOf("verified" to PayoutState.VERIFIED, "not_found" to PayoutState.NOT_FOUND, "not_applicable" to PayoutState.NOT_APPLICABLE)
            .forEach { (raw, expected) ->
                assertEquals(raw, expected, block(decodedFields().apply { put("coinbasePayoutStatus", raw) })?.payoutState)
            }
    }

    @Test fun missingOwnAddressIsDerivedOnOlderFirmware() {
        val fields = decodedFields().apply {
            put("coinbaseValueUserSatoshis", 0)
            put("coinbaseOutputs", JSONArray().put(JSONObject(mapOf("value" to 314_000_000, "address" to "bc1qsomeoneelse", "isUserOutput" to 0))))
            put("stratumUser", "bc1q7n70rumyv6lvu8avpml0c3uggvssfu52egum3q.nerdqaxe")
        }
        assertEquals(PayoutState.NOT_FOUND, block(fields)?.payoutState)
        fields["stratumUser"] = "myaccount.worker1"
        assertEquals(PayoutState.NOT_APPLICABLE, block(fields)?.payoutState)
    }

    @Test fun noBlockDataWithoutHeight() {
        assertNull(BlockTemplateSummary.from(Fixtures.bitaxeInfo()))
        assertNull(block(mapOf("blockHeight" to 0)))
        assertNull(BlockTemplateSummary.from(Fixtures.dualPoolInfo()))
    }

    @Test fun subsidyFollowsTheHalvings() {
        assertEquals(312_500_000.0, BlockTemplateSummary.subsidySats(840_000, MinedCoin.BTC))
        assertEquals(312_500_000.0, BlockTemplateSummary.subsidySats(1_049_999, MinedCoin.BTC))
        assertEquals(156_250_000.0, BlockTemplateSummary.subsidySats(1_050_000, MinedCoin.BTC))
        assertEquals(312_500_000.0, BlockTemplateSummary.subsidySats(900_000, MinedCoin.BCH))
        assertNull(BlockTemplateSummary.subsidySats(20_000_000, MinedCoin.DGB))
    }

    @Test fun variantsReportBlockDataFromAxeOS213() {
        val dir = java.io.File(Fixtures::class.java.classLoader!!.getResource("variants")!!.toURI())
        dir.listFiles()!!.filter { it.extension == "json" }.forEach { file ->
            val info = MinerSystemInfo.parse(file.readText())
            val version = info.firmwareVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
            val newAxeOS = info.firmware == MinerFirmware.AXE_OS && (version.getOrElse(0) { 0 } > 2 ||
                (version.getOrElse(0) { 0 } == 2 && version.getOrElse(1) { 0 } >= 13))
            assertEquals(file.name, newAxeOS, BlockTemplateSummary.from(info) != null)
            // Miners without block data are the ones the probe covers.
            assertEquals(file.name, !newAxeOS, BlockProbeTarget.targets(info).isNotEmpty())
        }
    }

    @Test fun probeTargetsInDualPoolMode() {
        val targets = BlockProbeTarget.targets(Fixtures.dualPoolInfo())
        assertEquals(2, targets.size)
        assertEquals(BlockProbeTarget.Slot.PRIMARY, targets[0].slot)
        assertEquals(1, targets[0].sharePercent)
        assertEquals(99, targets[1].sharePercent)
        assertEquals("bc1qexample.minerguru", targets[0].probeUser)
        assertEquals("bc1qexample.nerdqaxe", targets[0].user)
    }

    @Test fun probeTargetReadsTls() {
        val info = Fixtures.bitaxeInfo(mapOf("version" to "v2.12.0", "stratumURL" to "stratum+ssl://solo.atlaspool.io", "stratumPort" to 4333))
        val target = BlockProbeTarget.targets(info).single()
        assertTrue(target.tls)
        assertEquals("solo.atlaspool.io", target.host)
    }
}
