package app.minerguru.model

/** The coinbase transaction of a Stratum job, taken apart: height, pool tag and outputs. */
data class DecodedCoinbase(
    /** BIP34 height from the start of the scriptSig. */
    val height: Int?,
    val scriptSig: List<Int>,
    val outputs: List<Output>
) {
    data class Output(val valueSats: Long, val script: List<Int>) {
        val isDataOutput: Boolean get() = script.firstOrNull() == 0x6a
    }

    val totalSats: Long get() = outputs.sumOf { it.valueSats }

    /** Readable parts of the scriptSig after the height, e.g. `/solo.ckpool.org/`. */
    val poolTags: List<String>
        get() {
            val start = scriptSig.firstOrNull()?.plus(1) ?: 0
            if (start >= scriptSig.size) return emptyList()
            val tags = mutableListOf<String>()
            val run = StringBuilder()
            fun flush() {
                if (run.length >= 4) tags.add(run.toString())
                run.clear()
            }
            for (byte in scriptSig.subList(start, scriptSig.size)) {
                if (byte in 0x20..0x7e) run.append(byte.toChar()) else flush()
            }
            flush()
            return tags.map { it.trim() }.filter { it.isNotEmpty() }
        }
}

/** Builds the coinbase from `mining.notify` the way a miner does and parses it. */
object CoinbaseDecoder {
    /**
     * `coinb1 + extranonce1 + extranonce2 + coinb2` with a zero extranonce2. Without a usable
     * extranonce (pools that refuse the subscribe still send work), the gap is found by trying
     * every length until the transaction parses to its last byte.
     */
    fun decode(coinb1: String, coinb2: String, extranonce1: String?, extranonce2Size: Int?): DecodedCoinbase? {
        val head = bytes(coinb1) ?: return null
        val tail = bytes(coinb2) ?: return null
        val nonce1 = extranonce1?.let(::bytes)
        if (nonce1 != null && extranonce2Size != null && extranonce2Size in 0..32) {
            parse(head + nonce1 + List(extranonce2Size) { 0 } + tail)?.let { return it }
        }
        for (gap in 0..32) parse(head + List(gap) { 0 } + tail)?.let { return it }
        return null
    }

    /** Parses a legacy (non-witness) transaction with one input; `null` unless every byte is used. */
    fun parse(tx: List<Int>): DecodedCoinbase? {
        val reader = ByteReader(tx)
        if (!reader.skip(4) || reader.varInt() != 1L || !reader.skip(36)) return null
        val scriptLength = reader.varInt()?.takeIf { it <= 100 } ?: return null
        val scriptSig = reader.read(scriptLength.toInt()) ?: return null
        if (!reader.skip(4)) return null
        val outputCount = reader.varInt()?.takeIf { it in 1..1000 } ?: return null
        val outputs = (0 until outputCount.toInt()).map {
            val value = reader.uint64() ?: return null
            val length = reader.varInt()?.takeIf { it <= 10_000 } ?: return null
            DecodedCoinbase.Output(value, reader.read(length.toInt()) ?: return null)
        }
        if (!reader.skip(4) || !reader.isAtEnd) return null
        return DecodedCoinbase(bip34Height(scriptSig), scriptSig, outputs)
    }

    private fun bip34Height(scriptSig: List<Int>): Int? {
        val length = scriptSig.firstOrNull()?.takeIf { it in 1..8 && scriptSig.size > it } ?: return null
        return scriptSig.subList(1, length + 1).reversed().fold(0L) { acc, b -> (acc shl 8) or b.toLong() }.toInt()
    }

    fun bytes(hex: String): List<Int>? {
        if (hex.length % 2 != 0) return null
        return (hex.indices step 2).map { i ->
            val high = Character.digit(hex[i], 16)
            val low = Character.digit(hex[i + 1], 16)
            if (high < 0 || low < 0) return null
            (high shl 4) or low
        }
    }

    fun hex(bytes: List<Int>): String = bytes.joinToString("") { "%02x".format(it) }

    private class ByteReader(private val bytes: List<Int>) {
        private var offset = 0

        val isAtEnd: Boolean get() = offset == bytes.size

        fun skip(count: Int): Boolean = read(count) != null

        fun read(count: Int): List<Int>? {
            if (count < 0 || offset + count > bytes.size) return null
            return bytes.subList(offset, offset + count).toList().also { offset += count }
        }

        fun uint64(): Long? = read(8)?.reversed()?.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }

        fun varInt(): Long? {
            val first = read(1)?.first() ?: return null
            val width = when (first) {
                0xfd -> 2
                0xfe -> 4
                0xff -> 8
                else -> return first.toLong()
            }
            return read(width)?.reversed()?.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        }
    }
}
