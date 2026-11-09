package app.minerguru.model

import java.security.MessageDigest

/**
 * Converts between addresses and output scripts for the SHA-256 coins MinerGuru knows:
 * Base58 (P2PKH, P2SH), Bech32/Bech32m (SegWit, Taproot) and CashAddr (BCH, eCash).
 * Bytes are kept as `List<Int>` (0…255), so scripts compare with `==`.
 */
object CoinAddress {
    private class Format(val p2pkh: Int, val p2sh: Int, val bech32Hrp: String?, val cashAddrPrefix: String?)

    private fun format(coin: MinedCoin): Format = when (coin) {
        MinedCoin.BCH -> Format(0x00, 0x05, null, "bitcoincash")
        MinedCoin.XEC -> Format(0x00, 0x05, null, "ecash")
        MinedCoin.BSV -> Format(0x00, 0x05, null, null)
        MinedCoin.DGB -> Format(0x1e, 0x3f, "dgb", null)
        MinedCoin.NMC -> Format(0x34, 0x0d, "nc", null)
        MinedCoin.PPC -> Format(0x37, 0x75, "pc", null)
        MinedCoin.BTC, MinedCoin.FB, MinedCoin.UNKNOWN -> Format(0x00, 0x05, "bc", null)
    }

    // region Address → script

    /**
     * Output script that pays [address], independent of the coin: a CashAddr and the legacy address
     * of the same key give the same script. `null` for account names and anything unreadable.
     */
    fun scriptPubKey(address: String): List<Int>? {
        var value = address.trim()
        // Some pools want a mode in front (`solo:<address>` at Kryptex).
        val lower = value.lowercase()
        val colon = value.lastIndexOf(':')
        if (colon >= 0 && !lower.startsWith("bitcoincash:") && !lower.startsWith("ecash:")) {
            value = value.substring(colon + 1)
        }
        return cashAddrScript(value) ?: segwitScript(value) ?: base58Script(value)
    }

    private val p2pkhVersions = setOf(0x00, 0x1e, 0x34, 0x37, 0x6f)
    private val p2shVersions = setOf(0x05, 0x3f, 0x0d, 0x75, 0xc4)

    private fun p2pkh(hash: List<Int>) = listOf(0x76, 0xa9, 0x14) + hash + listOf(0x88, 0xac)
    private fun p2sh(hash: List<Int>) = listOf(0xa9, 0x14) + hash + listOf(0x87)

    private fun base58Script(address: String): List<Int>? {
        val payload = base58CheckDecode(address)?.takeIf { it.size == 21 } ?: return null
        val hash = payload.drop(1)
        return when (payload[0]) {
            in p2pkhVersions -> p2pkh(hash)
            in p2shVersions -> p2sh(hash)
            else -> null
        }
    }

    private fun segwitScript(address: String): List<Int>? {
        val (_, version, program) = segwitDecode(address) ?: return null
        val opcode = if (version == 0) 0x00 else 0x50 + version
        return listOf(opcode, program.size) + program
    }

    private fun cashAddrScript(address: String): List<Int>? {
        val lower = address.lowercase()
        val colon = lower.indexOf(':')
        // Pools accept CashAddr without prefix; the checksum tells which coin it belongs to.
        val candidates = if (colon >= 0) listOf(lower.substring(0, colon) to lower.substring(colon + 1))
        else listOf("bitcoincash", "ecash", "bchtest", "ectest").map { it to lower }
        for ((prefix, payload) in candidates) {
            val (type, hash) = cashAddrDecode(prefix, payload) ?: continue
            return when {
                type == 0 && hash.size == 20 -> p2pkh(hash)
                type == 1 && hash.size == 20 -> p2sh(hash)
                else -> null
            }
        }
        return null
    }

    // endregion

    // region Script → address

    /** Address an output script pays, in the usual notation of [coin]; `null` for scripts without one. */
    fun address(script: List<Int>, coin: MinedCoin): String? {
        val format = format(coin)
        if (script.size == 25 && script[0] == 0x76 && script[1] == 0xa9 && script[2] == 0x14 && script[23] == 0x88 && script[24] == 0xac) {
            val hash = script.subList(3, 23)
            format.cashAddrPrefix?.let { return cashAddrEncode(it, 0, hash) }
            return base58CheckEncode(listOf(format.p2pkh) + hash)
        }
        if (script.size == 23 && script[0] == 0xa9 && script[1] == 0x14 && script[22] == 0x87) {
            val hash = script.subList(2, 22)
            format.cashAddrPrefix?.let { return cashAddrEncode(it, 1, hash) }
            return base58CheckEncode(listOf(format.p2sh) + hash)
        }
        val hrp = format.bech32Hrp
        if (hrp != null && script.size >= 4 && script[1] == script.size - 2 && (script[0] == 0x00 || script[0] in 0x51..0x60)) {
            val version = if (script[0] == 0) 0 else script[0] - 0x50
            return segwitEncode(hrp, version, script.drop(2))
        }
        return null
    }

    // endregion

    // region Base58Check

    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun doubleSha256(bytes: List<Int>): List<Int> {
        val sha = MessageDigest.getInstance("SHA-256")
        val once = sha.digest(ByteArray(bytes.size) { bytes[it].toByte() })
        return sha.digest(once).map { it.toInt() and 0xff }
    }

    fun base58CheckEncode(payload: List<Int>): String {
        val bytes = payload + doubleSha256(payload).take(4)
        val digits = mutableListOf<Int>()
        for (byte in bytes) {
            var carry = byte
            for (i in digits.indices) {
                carry += digits[i] shl 8
                digits[i] = carry % 58
                carry /= 58
            }
            while (carry > 0) {
                digits.add(carry % 58)
                carry /= 58
            }
        }
        val zeros = bytes.takeWhile { it == 0 }.size
        return "1".repeat(zeros) + digits.reversed().map { BASE58[it] }.joinToString("")
    }

    fun base58CheckDecode(string: String): List<Int>? {
        if (string.isEmpty() || string.length > 64) return null
        val bytes = mutableListOf<Int>()
        for (char in string) {
            var carry = BASE58.indexOf(char).takeIf { it >= 0 } ?: return null
            for (i in bytes.indices) {
                carry += bytes[i] * 58
                bytes[i] = carry and 0xff
                carry = carry shr 8
            }
            while (carry > 0) {
                bytes.add(carry and 0xff)
                carry = carry shr 8
            }
        }
        val zeros = string.takeWhile { it == '1' }.length
        val decoded = List(zeros) { 0 } + bytes.reversed()
        if (decoded.size <= 4) return null
        val payload = decoded.dropLast(4)
        return payload.takeIf { decoded.takeLast(4) == doubleSha256(it).take(4) }
    }

    // endregion

    // region Bech32 / Bech32m (BIP 173, BIP 350)

    private const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3

    private fun bech32Polymod(values: List<Int>): Int {
        val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) if ((top shr i) and 1 == 1) chk = chk xor generator[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Int> {
        val codes = hrp.map { it.code and 0xff }
        return codes.map { it shr 5 } + 0 + codes.map { it and 31 }
    }

    private fun segwitEncode(hrp: String, version: Int, program: List<Int>): String? {
        val converted = convertBits(program, 8, 5, pad = true) ?: return null
        val data = listOf(version) + converted
        val constant = if (version == 0) BECH32_CONST else BECH32M_CONST
        val polymod = bech32Polymod(hrpExpand(hrp) + data + List(6) { 0 }) xor constant
        val checksum = (0 until 6).map { (polymod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum).map { BECH32[it] }.joinToString("")
    }

    private fun segwitDecode(address: String): Triple<String, Int, List<Int>>? {
        val lower = address.lowercase()
        if (lower != address && address.uppercase() != address) return null
        val separator = lower.lastIndexOf('1')
        if (separator < 0 || lower.length > 90) return null
        val hrp = lower.substring(0, separator)
        val rest = lower.substring(separator + 1)
        if (hrp.isEmpty() || rest.length < 7) return null
        val data = rest.map { c -> BECH32.indexOf(c).takeIf { it >= 0 } ?: return null }
        val polymod = bech32Polymod(hrpExpand(hrp) + data)
        val version = data[0]
        if (version > 16 || polymod != (if (version == 0) BECH32_CONST else BECH32M_CONST)) return null
        val program = convertBits(data.subList(1, data.size - 6), 5, 8, pad = false) ?: return null
        if (program.size !in 2..40 || (version == 0 && program.size != 20 && program.size != 32)) return null
        return Triple(hrp, version, program)
    }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxValue = (1 shl to) - 1
        for (value in data) {
            if (value shr from != 0) return null
            acc = ((acc shl from) or value) and 0xffffff
            bits += from
            while (bits >= to) {
                bits -= to
                result.add((acc shr bits) and maxValue)
            }
        }
        if (pad) {
            if (bits > 0) result.add((acc shl (to - bits)) and maxValue)
        } else if (bits >= from || ((acc shl (to - bits)) and maxValue) != 0) {
            return null
        }
        return result
    }

    // endregion

    // region CashAddr

    private fun cashAddrPolymod(values: List<Int>): Long {
        val generator = longArrayOf(0x98f2bc8e61L, 0x79b76d99e2L, 0xf33e5fb3c4L, 0xae2eabe2a8L, 0x1e4f43e470L)
        var c = 1L
        for (value in values) {
            val top = (c ushr 35).toInt()
            c = ((c and 0x07ffffffffL) shl 5) xor value.toLong()
            for (i in 0 until 5) if ((top shr i) and 1 == 1) c = c xor generator[i]
        }
        return c xor 1L
    }

    private fun cashAddrPrefixBits(prefix: String): List<Int> = prefix.map { it.code and 31 } + 0

    private fun cashAddrEncode(prefix: String, type: Int, hash: List<Int>): String? {
        // Version byte: type in bits 3–6, size code 0 for 160-bit hashes.
        val payload = convertBits(listOf(type shl 3) + hash, 8, 5, pad = true) ?: return null
        val polymod = cashAddrPolymod(cashAddrPrefixBits(prefix) + payload + List(8) { 0 })
        val checksum = (0 until 8).map { ((polymod ushr (5 * (7 - it))) and 31L).toInt() }
        return prefix + ":" + (payload + checksum).map { BECH32[it] }.joinToString("")
    }

    private fun cashAddrDecode(prefix: String, payload: String): Pair<Int, List<Int>>? {
        if (payload.length <= 8) return null
        val data = payload.map { c -> BECH32.indexOf(c).takeIf { it >= 0 } ?: return null }
        if (cashAddrPolymod(cashAddrPrefixBits(prefix) + data) != 0L) return null
        val bytes = convertBits(data.dropLast(8), 5, 8, pad = false) ?: return null
        val version = bytes.firstOrNull() ?: return null
        return (version shr 3) to bytes.drop(1)
    }

    // endregion
}
