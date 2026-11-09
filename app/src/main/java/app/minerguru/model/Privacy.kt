package app.minerguru.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Screenshot-safe mode: IP and MAC addresses, wallet addresses, the Wi-Fi name and the subnet are replaced
 * by scrambled characters of the same kind (digit → digit, letter → letter), so the layout stays the same
 * but nothing real is visible. Worker names stay readable. Text fields and raw data are blurred instead.
 */
object Privacy {
    var active by mutableStateOf(false)

    private const val DIGITS = "0123456789"
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val HEX_LOWER = "0123456789abcdef"
    private const val HEX_UPPER = "0123456789ABCDEF"

    /** Same input always gives the same output (no flicker), across app launches (FNV-1a + LCG). */
    fun scramble(text: String, hex: Boolean = false): String {
        var state = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in text.toByteArray(Charsets.UTF_8)) {
            state = state xor (b.toLong() and 0xff)
            state *= 0x100000001b3L
        }
        fun pick(alphabet: String): Char {
            state = state * 6364136223846793005L + 1442695040888963407L
            return alphabet[((state ushr 33) % alphabet.length).toInt()]
        }
        return buildString {
            for (c in text) append(
                when (c) {
                    in '0'..'9' -> pick(if (hex) HEX_LOWER else DIGITS)
                    in 'a'..'z' -> pick(if (hex) HEX_LOWER else LOWER)
                    in 'A'..'Z' -> pick(if (hex) HEX_UPPER else UPPER)
                    else -> c
                }
            )
        }
    }

    /** `wallet.worker`: only the wallet part is scrambled, the worker name stays readable. */
    fun scrambleStratumUser(user: String): String {
        val dot = user.indexOf('.')
        return if (dot < 0) scramble(user) else scramble(user.substring(0, dot)) + user.substring(dot)
    }

    private val freeTextPatterns = listOf(
        Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""") to true, // MAC address
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""") to false,                // IPv4 address
        Regex("""\b[A-Za-z0-9]{25,}\b""") to false                        // wallet address / long token
    )

    /** Scrambles IP and MAC addresses and wallet-like tokens inside free text such as log lines. */
    fun scrambleFreeText(text: String): String =
        freeTextPatterns.fold(text) { acc, (regex, hex) -> regex.replace(acc) { scramble(it.value, hex) } }

    // Display helpers: identity while the mode is off.
    fun ip(v: String) = if (active) scramble(v) else v
    fun user(v: String) = if (active) scrambleStratumUser(v) else v
    fun mac(v: String) = if (active) scramble(v, hex = true) else v
    fun ssid(v: String) = if (active) scramble(v) else v
    fun freeText(v: String) = if (active) scrambleFreeText(v) else v
}
