package app.minerguru.model

import app.minerguru.R
import org.json.JSONArray
import org.json.JSONObject

/** The first job a pool hands out, as far as the block template is concerned. */
data class StratumJob(
    val extranonce1: String?,
    val extranonce2Size: Int?,
    val prevHash: String,
    val coinb1: String,
    val coinb2: String,
    val merkleBranchCount: Int,
    val version: String,
    val nbits: String,
    val ntime: String
) {
    val coinbase: DecodedCoinbase? get() = CoinbaseDecoder.decode(coinb1, coinb2, extranonce1, extranonce2Size)
}

sealed class StratumProbeError(val reason: String = "") : Exception(reason) {
    object InvalidEndpoint : StratumProbeError()
    class ConnectionFailed(reason: String) : StratumProbeError(reason)
    /** The pool refused `mining.authorize`, with its message. */
    class Rejected(reason: String) : StratumProbeError(reason)
    object Timeout : StratumProbeError()
    object Closed : StratumProbeError()

    val text: String
        get() = when (this) {
            InvalidEndpoint -> tr(R.string.the_pool_address_is_incomplete)
            is ConnectionFailed -> tr(R.string.no_connection_to_the_pool, reason)
            is Rejected -> tr(R.string.the_pool_refuses_the_user, reason)
            Timeout -> tr(R.string.the_pool_sent_no_job)
            Closed -> tr(R.string.the_pool_closed_the_connection)
        }

    override fun equals(other: Any?): Boolean = other is StratumProbeError && other::class == this::class && other.reason == reason
    override fun hashCode(): Int = this::class.hashCode() * 31 + reason.hashCode()
}

/**
 * Stratum V1 conversation up to the first `mining.notify`, without the network: subscribe,
 * authorize, read the job. Never submits a share.
 */
class StratumProbeSession(val user: String, val userAgent: String, val password: String = "x") {
    sealed interface Event {
        data class Job(val job: StratumJob) : Event
        data class Failed(val error: StratumProbeError) : Event
    }

    var extranonce1: String? = null
        private set
    var extranonce2Size: Int? = null
        private set
    private var authorizeError: String? = null

    /** Lines to send right after connecting. */
    val openingLines: List<String>
        get() = listOf(
            request(1, "mining.subscribe", listOf(userAgent)),
            request(2, "mining.authorize", listOf(user, password))
        )

    /** Feeds one line from the pool; returns an event once the probe is done. */
    fun receive(line: String): Event? {
        val message = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val method = message.str("method")
        if (method != null) {
            val params = message.optJSONArray("params") ?: JSONArray()
            when (method) {
                "mining.set_extranonce" -> if (params.length() >= 2) readExtranonce(params.opt(0), params.opt(1))
                // Ocean sends the job before the authorize result, so a job is taken whenever it comes.
                "mining.notify" -> {
                    authorizeError?.let { return Event.Failed(StratumProbeError.Rejected(it)) }
                    return job(params, extranonce1, extranonce2Size)?.let { Event.Job(it) }
                }
            }
            return null
        }
        when (message.int("id")) {
            // [[subscriptions], extranonce1, extranonce2_size]; some pools refuse unknown agents and still send work.
            1 -> message.optJSONArray("result")?.takeIf { it.length() >= 3 }?.let { readExtranonce(it.opt(1), it.opt(2)) }
            2 -> {
                val accepted = message.bool("result") ?: false
                if (!accepted) {
                    val reason = errorText(message.opt("error")) ?: tr(R.string.no_reason_given)
                    authorizeError = reason
                    return Event.Failed(StratumProbeError.Rejected(reason))
                }
            }
        }
        return null
    }

    private fun readExtranonce(nonce1: Any?, size: Any?) {
        val n1 = nonce1 as? String ?: return
        val s = (size as? Number)?.toInt() ?: return
        extranonce1 = n1
        extranonce2Size = s
    }

    companion object {
        /** `[job_id, prevhash, coinb1, coinb2, merkle_branch, version, nbits, ntime, clean]` */
        fun job(params: JSONArray, extranonce1: String?, extranonce2Size: Int?): StratumJob? {
            if (params.length() < 8) return null
            fun s(i: Int) = params.opt(i) as? String
            return StratumJob(
                extranonce1 = extranonce1,
                extranonce2Size = extranonce2Size,
                prevHash = s(1) ?: return null,
                coinb1 = s(2) ?: return null,
                coinb2 = s(3) ?: return null,
                merkleBranchCount = (params.opt(4) as? JSONArray)?.length() ?: return null,
                version = s(5) ?: return null,
                nbits = s(6) ?: return null,
                ntime = s(7) ?: return null
            )
        }

        private fun errorText(error: Any?): String? = when (error) {
            is JSONArray -> if (error.length() >= 2) error.opt(1) as? String else error.opt(0)?.toString()
            is String -> error
            is JSONObject -> error.str("message")
            else -> null
        }

        private fun request(id: Int, method: String, params: List<String>): String =
            JSONObject().put("id", id).put("method", method).put("params", JSONArray(params)).toString().replace("\\/", "/")
    }
}
