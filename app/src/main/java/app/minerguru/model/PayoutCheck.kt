package app.minerguru.model

/** How the payout address of a pool slot looks. */
enum class PayoutVerdict {
    /** The address the user set for this coin. */
    EXPECTED,
    /** A valid address, and no expected address is set for the coin. */
    VALID,
    /** A valid address that is not the expected one. */
    FOREIGN,
    /** The firmware's placeholder or a default that pays someone else. */
    FACTORY_DEFAULT,
    /** No valid address: an account name at a pool, or a typing error. */
    ACCOUNT,
    EMPTY;

    val needsAttention: Boolean get() = this == FOREIGN || this == FACTORY_DEFAULT

    val description: String
        get() = when (this) {
            EXPECTED -> L("Entspricht deiner Soll-Adresse", "Matches your expected address")
            VALID -> L("Gültige Adresse, keine Soll-Adresse festgelegt", "Valid address, no expected address set")
            FOREIGN -> L("Weicht von deiner Soll-Adresse ab", "Differs from your expected address")
            FACTORY_DEFAULT -> L("Standard-Adresse der Firmware: Ein Fund ginge nicht an dich", "Firmware default: a block found would not go to you")
            ACCOUNT -> L("Konto bei einem Pool oder Tippfehler", "Pool account or typing error")
            EMPTY -> L("Nichts eingetragen", "Nothing entered")
        }
}

/** One configured pool slot and whom it pays. */
data class PayoutSlot(
    val key: String, val title: String, val host: String, val user: String,
    val address: String, val coin: MinedCoin, val verdict: PayoutVerdict
)

/** Checks the payout addresses of a miner. Only reads: MinerGuru never changes an address by itself. */
object PayoutCheck {
    /**
     * Shipped defaults of the firmwares: `replace-this-with-your-btc-address` on AxeOS,
     * and on NerdOS a real address of somebody else.
     */
    val KNOWN_DEFAULTS = setOf("bc1q7n70rumyv6lvu8avpml0c3uggvssfu52egum3q")
    private val PLACEHOLDERS = listOf("replace-this", "your-btc-address", "yourbtcaddress", "your_btc_address")

    /** The part of a Stratum user in front of the first dot. */
    fun address(user: String): String = user.trim().substringBefore('.')

    fun verdict(user: String, expected: String?): PayoutVerdict {
        val address = address(user)
        val lower = address.lowercase()
        if (address.isEmpty()) return PayoutVerdict.EMPTY
        if (lower in KNOWN_DEFAULTS || PLACEHOLDERS.any { it in lower }) return PayoutVerdict.FACTORY_DEFAULT
        val script = CoinAddress.scriptPubKey(address)
        if (script == null) {
            return if (!expected.isNullOrEmpty() && lower == expected.lowercase()) PayoutVerdict.EXPECTED else PayoutVerdict.ACCOUNT
        }
        // A half-typed expected address is no target yet.
        val target = expected?.let(CoinAddress::scriptPubKey) ?: return PayoutVerdict.VALID
        return if (target == script) PayoutVerdict.EXPECTED else PayoutVerdict.FOREIGN
    }

    /** All slots with something configured: AxeOS 2.15+ lists up to eight, others primary and fallback. */
    fun slots(info: MinerSystemInfo, expected: Map<String, String>): List<PayoutSlot> {
        fun slot(key: String, title: String, host: String, user: String): PayoutSlot? {
            if (host.isEmpty() && user.isEmpty()) return null
            val coin = MinedCoin.detect(host, user)
            return PayoutSlot(key, title, host, user, address(user), coin, verdict(user, expected[coin.symbol]))
        }
        val pools = info.axePools
        if (!pools.isNullOrEmpty()) return pools.mapNotNull { slot("pool${it.id}", "Pool ${it.id + 1}", it.stratumURL, it.stratumUser) }
        return listOfNotNull(
            slot("primary", L("Primär", "Primary"), info.stratumURL, info.stratumUser),
            slot("fallback", "Fallback", info.fallbackStratumURL, info.fallbackStratumUser)
        )
    }

    /** `worker` of the slot's user stays, the address in front of it changes. */
    fun user(user: String, address: String): String {
        val trimmed = user.trim()
        val dot = trimmed.indexOf('.')
        return if (dot >= 0) address + trimmed.substring(dot) else address
    }

    /** Only solo pools pay the block to the miner's own address; shared and donation pools show `not_found` by design. */
    fun isSoloPool(host: String, user: String): Boolean {
        val lower = host.lowercase()
        return "solo" in lower || "ckpool" in lower || "public-pool" in lower || user.lowercase().startsWith("solo:")
    }

    /** The firmware says the block it works on does not pay the miner. */
    fun blockSkipsMiner(info: MinerSystemInfo): Boolean =
        info.coinbasePayoutStatus == "not_found" && !info.isDualPoolMining &&
            info.activePoolCoin == MinedCoin.BTC && isSoloPool(info.activePoolHost, info.activePoolUser)

    /** Short form for notifications. */
    fun shortened(address: String): String =
        if (address.length > 14) "${address.take(6)}…${address.takeLast(5)}" else address
}
