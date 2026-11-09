package app.minerguru.service

import app.minerguru.R
import app.minerguru.model.ChangeLog
import app.minerguru.model.DiagnosticLog
import app.minerguru.model.tr
import app.minerguru.model.MinerSystemInfo
import app.minerguru.model.MinerSystemUpdatePayload
import app.minerguru.model.OtpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MinerApiException(message: String, val status: Int? = null, detail: String = "") : Exception(message) {
    private val unauthorizedDetail = detail.lowercase().takeIf { status == 401 }

    /** AxeOS also answers 401 (requests from outside the local network), so the text decides. */
    val isOtpRefused = unauthorizedDetail?.let { "otp" in it && "blocked" !in it } ?: false
    val isOtpBlocked = unauthorizedDetail?.let { "blocked" in it } ?: false
    val isOtpWrongCode = unauthorizedDetail?.let { "invalid" in it } ?: false

    companion object {
        fun ofResponse(status: Int, detail: String): MinerApiException {
            val plain = MinerApiException("", status, detail)
            val message = when {
                plain.isOtpBlocked -> tr(R.string.too_many_wrong_one_time)
                plain.isOtpRefused -> tr(R.string.the_miner_demands_a_valid)
                else -> tr(R.string.http_error) + " $status" + if (detail.isEmpty()) "" else ": $detail"
            }
            return MinerApiException(message, status, detail)
        }
    }
}

/** REST client for AxeOS / NerdOS (`/api/system/info`, `PATCH /api/system`, `POST /api/system/restart`). */
object MinerApiClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    /** Strips http(s):// and path from a user-entered IP or hostname. */
    /** What a miner's answer has and any other JSON service has not; the model's defaults would hide that. */
    private val MINER_KEYS = listOf("ASICModel", "hashRate", "stratumURL")

    /** Host name or IPv4 address with an optional port, the only forms requests can reach. */
    fun isValidHost(raw: String): Boolean {
        val parts = normalizeHost(raw).split(':')
        if (parts.size > 2) return false
        val name = parts[0]
        if (parts.size == 2 && parts[1].toIntOrNull() !in 1..65535) return false
        if (name.isEmpty() || !name.matches(Regex("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?"))) return false
        // Digits only means an address, not a name.
        return !name.matches(Regex("[0-9.]+")) ||
            name.split('.').let { o -> o.size == 4 && o.all { (it.toIntOrNull() ?: 256) <= 255 } }
    }

    fun normalizeHost(raw: String): String {
        var cleaned = raw.trim()
        for (prefix in listOf("http://", "https://")) {
            if (cleaned.lowercase().startsWith(prefix)) cleaned = cleaned.drop(prefix.length)
        }
        cleaned.indexOf('/').takeIf { it >= 0 }?.let { cleaned = cleaned.substring(0, it) }
        return cleaned
    }

    private fun url(host: String, path: String): String {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty()) throw MinerApiException(tr(R.string.invalid_address) + ": $host")
        return "http://$normalized$path"
    }

    private suspend fun execute(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(MinerApiException(e.message ?: tr(R.string.unreachable)))
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    /** Result of one `GET /api/system/info`. */
    data class Fetched(val info: MinerSystemInfo, val rawPretty: String, val isMiner: Boolean = true)

    suspend fun fetchSystemInfo(host: String, client: OkHttpClient = http): Fetched {
        val request = Request.Builder().url(url(host, "/api/system/info")).header("Accept", "application/json").build()
        val body = execute(client, request).use { resp ->
            val text = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
            if (!resp.isSuccessful) throw MinerApiException(tr(R.string.http_error) + " ${resp.code}${if (text.isEmpty()) "" else ": $text"}")
            text
        }
        return try {
            val obj = JSONObject(body)
            Fetched(MinerSystemInfo.fromJson(obj), prettySorted(obj), MINER_KEYS.any(obj::has))
        } catch (e: Exception) {
            throw MinerApiException(tr(R.string.invalid_response_from_miner) + ": ${e.message}")
        }
    }

    private fun prettySorted(obj: JSONObject): String {
        fun sorted(v: Any?): Any? = when (v) {
            is JSONObject -> {
                val out = java.util.TreeMap<String, Any?>()
                v.keys().forEach { out[it] = sorted(v.opt(it)) }
                out
            }
            is org.json.JSONArray -> (0 until v.length()).map { sorted(v.opt(it)) }
            else -> v
        }
        return JSONObject(sorted(obj) as Map<*, *>).toString(2)
    }

    suspend fun updateSystemSettings(host: String, payload: MinerSystemUpdatePayload, otpSession: String? = null) {
        val request = Request.Builder().url(url(host, "/api/system"))
            .patch(payload.toJson().toString().toRequestBody(json))
            .apply { otpSession?.let { header("X-OTP-Session", it) } }
            .build()
        val fields = payload.toJson().keys().asSequence().toList().sorted().joinToString(",")
        sendChecked(request, " fields=$fields", payload.toJson().toString())
    }

    /** Exchanges a one-time code for a session via `POST /api/otp/session` (NerdOS). */
    suspend fun createOtpSession(host: String, code: String): OtpSession {
        val request = Request.Builder().url(url(host, "/api/otp/session"))
            .post("{}".toRequestBody(json)).header("X-TOTP", code).build()
        return executeWrite(request).use { resp ->
            val text = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
            if (!resp.isSuccessful) throw MinerApiException.ofResponse(resp.code, text)
            runCatching { OtpSession.parseResponse(text, System.currentTimeMillis()) }.getOrElse {
                throw MinerApiException(tr(R.string.invalid_response_from_the_miner))
            }
        }
    }

    suspend fun restartMiner(host: String) {
        val request = Request.Builder().url(url(host, "/api/system/restart"))
            .post("{}".toRequestBody(json)).build()
        sendChecked(request)
    }

    /** Stops hashing without a reboot (AxeOS 2.15+). */
    suspend fun pauseMiner(host: String) {
        sendChecked(Request.Builder().url(url(host, "/api/system/pause")).post("{}".toRequestBody(json)).build())
    }

    /** Starts hashing again after [pauseMiner] (AxeOS 2.15+). */
    suspend fun resumeMiner(host: String) {
        sendChecked(Request.Builder().url(url(host, "/api/system/resume")).post("{}".toRequestBody(json)).build())
    }

    /** Empties one pool slot (AxeOS 2.15+); the firmware refuses to delete the primary or fallback slot. */
    suspend fun deletePoolSlot(host: String, index: Int) {
        sendChecked(Request.Builder().url(url(host, "/api/system/pools/$index")).delete().build())
    }

    /**
     * Sends something that changes a miner and notes the outcome in the diagnostic log: the names of
     * the fields sent, never their values or the miner's answer, which can hold passwords.
     */
    private suspend fun executeWrite(request: Request, fields: String = "", body: String? = null): Response {
        if (ChangeLog.shared.isReadOnly) throw MinerApiException(readOnlyMessage())
        val target = "${request.url.host}:${request.url.port} ${request.method} ${request.url.encodedPath}$fields"
        val host = if (request.url.port == 80) request.url.host else "${request.url.host}:${request.url.port}"
        return try {
            execute(http, request).also {
                DiagnosticLog.shared.write("write", "$target status=${it.code}")
                ChangeLog.shared.recordWrite(host, request.method, request.url.encodedPath, body, if (it.isSuccessful) null else "HTTP ${it.code}")
            }
        } catch (e: MinerApiException) {
            DiagnosticLog.shared.write("write", "$target failed")
            ChangeLog.shared.recordWrite(host, request.method, request.url.encodedPath, body, e.message ?: "")
            throw e
        }
    }

    fun readOnlyMessage() = tr(R.string.read_only_mode_is_on)

    /** On IO: closing a response whose body is still in transit reads the socket, which the main thread may not. */
    private suspend fun sendChecked(request: Request, fields: String = "", body: String? = null) = withContext(Dispatchers.IO) {
        executeWrite(request, fields, body).use { resp ->
            if (!resp.isSuccessful) throw MinerApiException.ofResponse(resp.code, resp.body?.string().orEmpty())
        }
    }
}
