package app.minerguru.model

import org.json.JSONArray

/** One output of the coinbase transaction the miner is hashing on (`coinbaseOutputs[]`, AxeOS 2.13+). */
data class CoinbaseOutput(
    val valueSats: Double,
    /** Address decoded by the firmware, `OP_RETURN: <text>` for data outputs, `UNKNOWN:<hex>` otherwise. */
    val address: String,
    /** The firmware matched the scriptPubKey against the address in the Stratum user. */
    val isUserOutput: Boolean = false
) {
    /** Data output without value, e.g. the SegWit witness commitment or a merge-mining tag. */
    val isDataOutput: Boolean get() = address.startsWith("OP_RETURN")

    /** Payload of a data output as the firmware prints it (non-printable bytes come as `.`). */
    private val dataPayload: String get() = address.removePrefix("OP_RETURN").trimStart(':', ' ')

    /** SegWit witness commitment: payload starts with `aa21a9ed`, printed as `.!..`. */
    val isWitnessCommitment: Boolean get() = isDataOutput && dataPayload.startsWith(".!..")

    /** Readable text of a data output (pool or merge-mining tag), `null` for binary data. */
    val dataText: String?
        get() {
            if (!isDataOutput || isWitnessCommitment) return null
            val payload = dataPayload
            val readable = payload.count { it != '.' }
            return payload.takeIf { it.length >= 3 && readable >= it.length * 0.6 }
        }

    companion object {
        fun listFrom(arr: JSONArray?): List<CoinbaseOutput>? = arr?.let {
            (0 until it.length()).mapNotNull { i ->
                it.optJSONObject(i)?.let { o ->
                    CoinbaseOutput(o.dbl("value") ?: 0.0, o.str("address") ?: "", o.bool("isUserOutput") ?: false)
                }
            }
        }
    }
}

/**
 * What the miner knows about the block it is hashing on right now: the pool's template as seen
 * through the coinbase (height, pool tag, payout). The transaction list itself never reaches the miner.
 */
data class BlockTemplateSummary(
    val height: Int,
    val poolTag: String?,
    val signals: List<String>,
    val coin: MinedCoin,
    /** Coinbase decoding of the active pool slot; `null` if the firmware does not say. */
    val isDecoding: Boolean?,
    val outputs: List<CoinbaseOutput>,
    val othersCount: Int,
    val othersValueSats: Double,
    /** Sum of all outputs (subsidy + fees); `null` if missing or implausible. */
    val totalSats: Double?,
    val userSats: Double?,
    val payoutState: PayoutState,
    /** Transactions in the template, from the merkle branch count; only known from a Stratum probe. */
    val transactionRange: IntRange? = null,
    /** Set (epoch ms) when MinerGuru read the template from the pool itself instead of the miner reporting it. */
    val probedAtMillis: Long? = null
) {
    enum class PayoutState {
        /** The coinbase pays the address from the Stratum user. */
        VERIFIED,
        /** Address in the Stratum user, but no output pays it. */
        NOT_FOUND,
        /** No address in the Stratum user (account pool), nothing to check. */
        NOT_APPLICABLE,
        /** Coinbase decoding is switched off for the active pool. */
        DECODING_OFF,
        UNKNOWN
    }

    /** New coins the block creates, from height and coin; `null` for coins with another schedule. */
    val subsidySats: Double? get() = subsidySats(height, coin)

    /** Fees of the transactions in the template: total minus subsidy. */
    val feesSats: Double?
        get() {
            val total = totalSats ?: return null
            val subsidy = subsidySats ?: return null
            return if (total >= subsidy) total - subsidy else null
        }

    /** Share of the block reward that goes to the own address (0…1). */
    val userShare: Double?
        get() {
            val user = userSats ?: return null
            val total = totalSats?.takeIf { it > 0 } ?: return null
            return user / total
        }

    companion object {
        /** Recipients shown one by one; shared coinbases (Ocean) list the rest as a sum. */
        const val MAX_LISTED_RECIPIENTS = 6

        /** `null` if the firmware reports no block data (NerdOS, AxeOS < 2.13, Stratum V2 standard jobs). */
        fun from(info: MinerSystemInfo): BlockTemplateSummary? {
            val height = info.blockHeight?.takeIf { it > 0 } ?: return null
            val coin = info.activePoolCoin
            val tag = info.blockScriptSig?.trim().orEmpty()
            val isDecoding = info.activePoolDecodesCoinbase
            val outputs = if (isDecoding == false) emptyList() else info.coinbaseOutputs.orEmpty()
            val totalSats = info.coinbaseValueTotalSats?.takeIf { it > 0 && it <= maxPlausibleSats(coin) }
            val user = info.coinbaseValueUserSats ?: outputs.filter { it.isUserOutput }.sumOf { it.valueSats }
            val userSats = if (isDecoding == false || totalSats == null) null else minOf(user, totalSats)
            return BlockTemplateSummary(
                height = height,
                poolTag = tag.takeIf { it.isNotEmpty() && !it.startsWith("[") },
                signals = info.blockSignals.orEmpty(),
                coin = coin,
                isDecoding = isDecoding,
                outputs = outputs,
                othersCount = info.coinbaseOthersCount ?: 0,
                othersValueSats = info.coinbaseOthersValueSats ?: 0.0,
                totalSats = totalSats,
                userSats = userSats,
                payoutState = payoutState(
                    reported = info.coinbasePayoutStatus,
                    isDecoding = isDecoding,
                    outputs = outputs,
                    userSats = userSats,
                    stratumUser = if (info.isUsingFallbackStratum) info.fallbackStratumUser else info.stratumUser
                )
            )
        }

        /**
         * Template read by the Stratum probe. [stratumUser] is the miner's own user, so the check
         * answers whether the pool pays the miner's address, not the probe's worker name.
         */
        fun from(job: StratumJob, coin: MinedCoin, stratumUser: String, probedAtMillis: Long = System.currentTimeMillis()): BlockTemplateSummary? {
            val coinbase = job.coinbase ?: return null
            val height = coinbase.height?.takeIf { it > 0 } ?: return null
            val wallet = stratumUser.substringBefore('.')
            val userScript = CoinAddress.scriptPubKey(wallet)

            val all = coinbase.outputs.map { output ->
                CoinbaseOutput(
                    valueSats = output.valueSats.toDouble(),
                    address = label(output.script, coin),
                    isUserOutput = userScript != null && output.script == userScript
                )
            }
            val listedPayers = all.indices
                .filter { !all[it].isDataOutput && !all[it].isUserOutput }
                .sortedByDescending { all[it].valueSats }
                .take(MAX_LISTED_RECIPIENTS)
                .toSet()
            val listed = all.indices.filter { all[it].isDataOutput || all[it].isUserOutput || it in listedPayers }
            val others = all.indices.filter { it !in listed }

            val state = when {
                all.any { it.isUserOutput } -> PayoutState.VERIFIED
                userScript != null -> PayoutState.NOT_FOUND
                looksLikeAddress(wallet) -> PayoutState.UNKNOWN
                else -> PayoutState.NOT_APPLICABLE
            }
            // From the pool: a real block has about 13 branches; more than 30 would overflow the shift below.
            val branches = job.merkleBranchCount.coerceIn(0, 30)
            return BlockTemplateSummary(
                height = height,
                poolTag = coinbase.poolTags.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                signals = emptyList(),
                coin = coin,
                isDecoding = true,
                outputs = listed.map { all[it] },
                othersCount = others.size,
                othersValueSats = others.sumOf { all[it].valueSats },
                totalSats = coinbase.totalSats.toDouble().takeIf { it > 0 },
                userSats = all.filter { it.isUserOutput }.sumOf { it.valueSats },
                payoutState = state,
                transactionRange = if (branches == 0) 1..1 else ((1 shl (branches - 1)) + 1)..(1 shl branches),
                probedAtMillis = probedAtMillis
            )
        }

        /** Text for one output in the firmware's notation, so [CoinbaseOutput] reads both alike. */
        private fun label(script: List<Int>, coin: MinedCoin): String {
            if (script.firstOrNull() == 0x6a) {
                val payload = script.drop(1)
                val data = when (payload.firstOrNull()) {
                    null -> payload
                    in 0x01..0x4b -> payload.drop(1)
                    0x4c -> payload.drop(2)
                    0x4d -> payload.drop(3)
                    else -> payload
                }
                return "OP_RETURN: " + data.joinToString("") { if (it in 0x20..0x7e) it.toChar().toString() else "." }
            }
            return CoinAddress.address(script, coin) ?: ("UNKNOWN:" + CoinbaseDecoder.hex(script))
        }

        /** Bitcoin-style halving schedule: 50 coins, halved every 210,000 blocks. */
        fun subsidySats(height: Int, coin: MinedCoin): Double? = when (coin) {
            MinedCoin.BTC, MinedCoin.BCH, MinedCoin.BSV, MinedCoin.XEC -> {
                val halvings = height / 210_000
                if (halvings >= 63) 0.0 else (5_000_000_000L shr halvings).toDouble()
            }
            else -> null
        }

        /** Whole supply as upper bound: the firmware reports leftovers when it never decoded a job. */
        private fun maxPlausibleSats(coin: MinedCoin): Double = if (coin == MinedCoin.DGB) 21e9 * 1e8 else 21e6 * 1e8

        fun payoutState(
            reported: String?,
            isDecoding: Boolean?,
            outputs: List<CoinbaseOutput>,
            userSats: Double?,
            stratumUser: String
        ): PayoutState {
            when (reported?.lowercase()) {
                "verified" -> return PayoutState.VERIFIED
                "not_found" -> return PayoutState.NOT_FOUND
                "not_applicable" -> return PayoutState.NOT_APPLICABLE
            }
            // AxeOS < 2.16 has no status: derive it the way the firmware does.
            if (isDecoding == false) return PayoutState.DECODING_OFF
            if (outputs.isEmpty()) return PayoutState.UNKNOWN
            if (outputs.any { it.isUserOutput } || (userSats ?: 0.0) > 0) return PayoutState.VERIFIED
            return if (looksLikeAddress(stratumUser.substringBefore('.'))) PayoutState.NOT_FOUND else PayoutState.NOT_APPLICABLE
        }

        private const val BASE58_CHARS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /** Rough format check for the wallet part of a Stratum user; account names fail it. */
        fun looksLikeAddress(raw: String): Boolean {
            val value = raw.trim()
            val lower = value.lowercase()
            val bech32Prefixes = listOf("bc1", "tb1", "bcrt1", "dgb1", "bitcoincash:", "ecash:", "bchtest:")
            if (bech32Prefixes.any { lower.startsWith(it) }) return value.length >= 26
            if (value.length in 25..35 && value.all { it in BASE58_CHARS }) return true
            // CashAddr without prefix
            return value.length == 42 && (lower.startsWith("q") || lower.startsWith("p"))
        }

        /** Amount in whole coins with all decimals, e.g. `3,12841234 BTC`. */
        fun formatCoins(sats: Double, coin: MinedCoin): String {
            val xec = coin == MinedCoin.XEC
            val amount = DisplayFormat.decimal(sats / if (xec) 100.0 else 100_000_000.0, if (xec) 2 else 8)
            return "$amount ${coin.symbol}"
        }
    }
}
