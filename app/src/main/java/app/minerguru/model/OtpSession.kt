package app.minerguru.model

import org.json.JSONObject

/** A session the miner granted for a one-time code (NerdOS): sent as `X-OTP-Session` when writing settings. */
data class OtpSession(val token: String, val expiresAt: Long) {
    /** A session this close to its end could run out on the way to the miner. */
    fun isUsable(now: Long): Boolean = expiresAt - now > SAFETY_MARGIN_MS

    fun toJson(): String = JSONObject().put("token", token).put("expiresAt", expiresAt).toString()

    companion object {
        const val SAFETY_MARGIN_MS = 60_000L

        /** The miner answers `{token, ttlMs, expiresAt}`. Its clock may be wrong, so only the duration counts. */
        fun parseResponse(text: String, now: Long): OtpSession {
            val json = JSONObject(text)
            return OtpSession(json.getString("token"), now + json.getLong("ttlMs"))
        }

        fun fromJson(text: String): OtpSession? = runCatching {
            val json = JSONObject(text)
            OtpSession(json.getString("token"), json.getLong("expiresAt"))
        }.getOrNull()
    }
}
