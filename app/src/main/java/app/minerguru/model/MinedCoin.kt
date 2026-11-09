package app.minerguru.model

/** SHA-256 coin detected for a stratum pool slot from pool host, wallet format and network difficulty. */
enum class MinedCoin(
    val symbol: String,
    val fullName: String,
    val targetBlockTimeSeconds: Double,
    val blockSubsidyCoins: Double,
    val fallbackNetworkDifficulty: Double,
    val coinGeckoId: String?,
    val binanceUsdtSymbol: String?
) {
    BTC("BTC", "Bitcoin", 600.0, 3.125, 144_080_230_085_156.0, "bitcoin", "BTCUSDT"),
    BCH("BCH", "Bitcoin Cash", 600.0, 3.125, 750_830_101_440.0, "bitcoin-cash", "BCHUSDT"),
    DGB("DGB", "DigiByte", 75.0, 275.0, 120_000_000.0, "digibyte", "DGBUSDT"),
    XEC("XEC", "eCash", 600.0, 1_812_500.0, 250_000_000_000.0, "ecash", "XECUSDT"),
    BSV("BSV", "Bitcoin SV", 600.0, 3.125, 85_000_000_000.0, "bitcoin-cash-sv", null),
    PPC("PPC", "Peercoin", 600.0, 35.0, 10_000_000_000_000.0, "peercoin", null),
    NMC("NMC", "Namecoin", 600.0, 3.125, 10_000_000_000_000.0, "namecoin", null),
    FB("FB", "Fractal Bitcoin", 30.0, 25.0, 5_000_000_000_000.0, "fractal-bitcoin", null),
    UNKNOWN("SHA-256", "SHA-256 Coin", 600.0, 3.125, 144_080_230_085_156.0, null, null);

    companion object {
        private const val CASH_ADDR_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

        private val knownBtcHosts = listOf(
            "ckpool.org", "public-pool.io", "ocean.xyz", "braiins.com",
            "slushpool.com", "kano.is", "vkbit.com", "antpool.com",
            "f2pool.com", "viabtc.com", "foundryusa.com", "luxor.tech", "btc."
        )

        /** Coins offered for quotes. */
        val marketCoins: List<MinedCoin> = listOf(BTC, BCH, DGB, XEC, BSV, PPC, NMC, FB)

        private fun fromHost(host: String): MinedCoin? {
            if (host.startsWith("bch.") || host.contains(".bch.") || host.contains("-bch") ||
                host.contains("bchpool") || host.contains("bitcoincash")
            ) return BCH
            if (host.contains("digibyte") || host.startsWith("dgb.") || host.contains(".dgb.") || host.contains("-dgb")) return DGB
            if (host.contains("ecash") || host.startsWith("xec.") || host.contains(".xec.") || host.contains("-xec")) return XEC
            if (host.startsWith("bsv.") || host.contains(".bsv.") || host.contains("bitcoinsv")) return BSV
            if (host.contains("peercoin") || host.startsWith("ppc.") || host.contains(".ppc.")) return PPC
            if (host.contains("namecoin") || host.startsWith("nmc.") || host.contains(".nmc.")) return NMC
            if (host.contains("fractal") || host.startsWith("fb.") || host.contains(".fb.")) return FB
            return null
        }

        private fun fromAddress(wallet: String): MinedCoin? {
            val lower = wallet.lowercase()
            if (lower.startsWith("bitcoincash:")) return BCH
            if (lower.startsWith("ecash:")) return XEC
            if (lower.startsWith("dgb1")) return DGB
            if (lower.startsWith("bc1q") || lower.startsWith("bc1p")) return BTC
            if (lower.startsWith("pc1")) return PPC
            if (lower.startsWith("nc1")) return NMC

            if ((wallet.startsWith("q") || wallet.startsWith("p")) && wallet.length == 42 &&
                wallet.all { it in CASH_ADDR_CHARSET }
            ) return BCH

            if (wallet.startsWith("D") && wallet.length in 26..35) return DGB
            return null
        }

        /**
         * The coins of pool and address when they clearly differ. Payouts to such an address may be lost, but a
         * multi-coin pool can still be right, so this only warns. Legacy addresses fit several coins and never count.
         */
        fun mismatch(poolHost: String, stratumUser: String): Pair<MinedCoin, MinedCoin>? {
            val host = poolHost.trim().lowercase()
            val wallet = stratumUser.trim().substringBefore('.')
            val pool = fromHost(host) ?: BTC.takeIf { knownBtcHosts.any { host.contains(it) } } ?: return null
            val address = fromAddress(wallet) ?: return null
            return (pool to address).takeIf { pool != address }
        }

        /** Warning text for [mismatch], or `null`. */
        fun mismatchWarning(poolHost: String, stratumUser: String): String? = mismatch(poolHost, stratumUser)?.let { (pool, address) ->
            L("Die Adresse gehört zu ${address.symbol}, der Pool schürft ${pool.symbol}. Auszahlungen könnten verloren gehen.",
                "This address belongs to ${address.symbol}, the pool mines ${pool.symbol}. Payouts could be lost.")
        }

        fun detect(poolHost: String, stratumUser: String, networkDifficulty: Double? = null): MinedCoin {
            val host = poolHost.trim().lowercase()
            val rawUser = stratumUser.trim()
            if (host.isEmpty() && rawUser.isEmpty()) return UNKNOWN

            val wallet = rawUser.indexOf('.').let { if (it >= 0) rawUser.substring(0, it) else rawUser }
            val walletLower = wallet.lowercase()

            fromHost(host)?.let { return it }
            fromAddress(wallet)?.let { return it }

            // 3. Network difficulty heuristic
            if (networkDifficulty != null && networkDifficulty > 0) {
                if (networkDifficulty >= 20_000_000_000_000.0) return BTC
                if (networkDifficulty >= 50_000_000_000.0 && networkDifficulty < 10_000_000_000_000.0) return BCH
                if (networkDifficulty >= 1_000_000.0 && networkDifficulty < 50_000_000_000.0) return DGB
            }

            // 4. Known BTC pools / legacy addresses
            if (knownBtcHosts.any { host.contains(it) }) return BTC
            if ((wallet.startsWith("1") || wallet.startsWith("3")) && wallet.length in 26..35) return BTC

            return UNKNOWN
        }
    }
}
