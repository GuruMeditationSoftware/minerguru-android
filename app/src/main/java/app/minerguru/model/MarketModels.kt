package app.minerguru.model

import kotlin.math.exp
import kotlin.math.roundToLong

enum class SoloChanceTimeframe(private val de: String, private val en: String) {
    PER_BLOCK("Pro Block", "Per block"),
    PER_HOUR("Pro Stunde", "Per hour"),
    PER_DAY("Pro Tag", "Per day"),
    PER_WEEK("Pro Woche", "Per week"),
    PER_MONTH("Pro Monat", "Per month"),
    PER_YEAR("Pro Jahr", "Per year");

    val label: String get() = L(de, en)

    fun durationSeconds(coin: MinedCoin): Double = when (this) {
        PER_BLOCK -> coin.targetBlockTimeSeconds
        PER_HOUR -> 3_600.0
        PER_DAY -> 86_400.0
        PER_WEEK -> 86_400.0 * 7
        PER_MONTH -> 86_400.0 * 30.4375
        PER_YEAR -> 86_400.0 * 365.25
    }
}

/** Aggregated fleet hashrate, difficulty and solo-mining probability for one coin. */
data class FleetCoinMiningSummary(
    val coin: MinedCoin,
    val totalHashrateGHs: Double,
    val networkDifficulty: Double,
    val contributingMinersText: String,
    val isLiveDifficulty: Boolean
) {
    /** Expected number of blocks (Poisson rate λ = H·T / (D·2^32)). */
    fun expectedBlocksLambda(timeframe: SoloChanceTimeframe): Double {
        if (totalHashrateGHs <= 0 || networkDifficulty <= 0) return 0.0
        val hashes = totalHashrateGHs * 1e9 * timeframe.durationSeconds(coin)
        return hashes / (networkDifficulty * 4_294_967_296.0)
    }

    fun probability(timeframe: SoloChanceTimeframe): Double {
        val lambda = expectedBlocksLambda(timeframe)
        if (lambda <= 0) return 0.0
        return if (lambda < 1e-7) lambda else 1.0 - exp(-lambda)
    }

    fun formattedProbabilityPercent(timeframe: SoloChanceTimeframe): String {
        val pct = probability(timeframe) * 100.0
        if (pct <= 0) return "0 %"
        val digits = when {
            pct >= 10 -> 1
            pct >= 1 -> 2
            pct >= 0.01 -> 3
            pct >= 0.0001 -> 5
            else -> 7
        }
        return DisplayFormat.percent(pct, digits)
    }

    fun formattedOddsRatio(timeframe: SoloChanceTimeframe): String {
        val p = probability(timeframe)
        if (p <= 0) return "—"
        val oneIn = maxOf(1.0, (1.0 / p).roundToLong().toDouble())
        return when {
            oneIn >= 1e9 -> "1 : ${DisplayFormat.decimal(oneIn / 1e9, 2)} " + L("Mrd.", "bn")
            oneIn >= 1e7 -> "1 : ${DisplayFormat.decimal(oneIn / 1e6, 1)} " + L("Mio.", "M")
            else -> "1 : ${DisplayFormat.decimal(oneIn, 0)}"
        }
    }

    val expectedTimePerBlockFormatted: String
        get() {
            val lambdaPerDay = expectedBlocksLambda(SoloChanceTimeframe.PER_DAY)
            if (lambdaPerDay <= 0) return "—"
            val days = 1.0 / lambdaPerDay
            if (days < 1.0) return "~${DisplayFormat.decimal(days * 24.0, 1)} " + L("Std.", "h")
            if (days < 60.0) return "~${DisplayFormat.decimal(days, 1)} " + L("Tage", "days")
            val years = days / 365.25
            if (years >= 1_000) return "~${DisplayFormat.decimal(years.roundToLong().toDouble(), 0)} " + L("J.", "yrs")
            return "~${DisplayFormat.decimal(years, 1)} " + L("Jahre", "years")
        }

    /** Block subsidy in coins, e.g. `"3,125 BTC"`. */
    val blockRewardText: String
        get() = "${DisplayFormat.decimalMax(coin.blockSubsidyCoins, 3)} ${coin.symbol}"

    companion object {
        /** Aggregates per coin; dual-pool miners split hashrate. Bitcoin first, others by hashrate. */
        fun summaries(onlineMiners: List<MinerDevice>): List<FleetCoinMiningSummary> {
            class Bucket(var hashrate: Double = 0.0, var liveDiff: Double? = null, val miners: MutableList<String> = mutableListOf())

            val buckets = linkedMapOf<MinedCoin, Bucket>()
            fun add(coin: MinedCoin, hashrate: Double, difficulty: Double?, label: String) {
                val b = buckets.getOrPut(coin) { Bucket() }
                b.hashrate += hashrate
                if (difficulty != null && difficulty > 0) b.liveDiff = difficulty
                b.miners += label
            }

            for (miner in onlineMiners) {
                val info = miner.latestInfo ?: continue
                if (info.isDualPoolMining) {
                    val p1 = info.dualPoolPrimaryPercent
                    val p2 = info.dualPoolSecondaryPercent
                    add(info.primaryPoolCoin, info.hashRate * p1 / 100.0, info.primaryPoolStatus?.networkDifficulty, "${miner.displayName} ($p1%)")
                    add(info.secondaryPoolCoin, info.hashRate * p2 / 100.0, info.secondaryPoolStatus?.networkDifficulty, "${miner.displayName} ($p2%)")
                } else {
                    val status = if (info.isUsingFallbackStratum) info.secondaryPoolStatus else info.primaryPoolStatus
                    add(info.activePoolCoin, info.hashRate, status?.networkDifficulty ?: info.networkDifficulty, miner.displayName)
                }
            }

            return buckets.map { (coin, b) ->
                FleetCoinMiningSummary(
                    coin = coin,
                    totalHashrateGHs = b.hashrate,
                    networkDifficulty = b.liveDiff ?: coin.fallbackNetworkDifficulty,
                    contributingMinersText = b.miners.joinToString(" + "),
                    isLiveDifficulty = b.liveDiff != null
                )
            }.sortedWith(
                compareByDescending<FleetCoinMiningSummary> { it.coin == MinedCoin.BTC }
                    .thenByDescending { it.totalHashrateGHs }
            )
        }
    }
}
