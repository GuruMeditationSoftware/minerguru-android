package app.minerguru.service

import android.annotation.SuppressLint
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.minerguru.BuildConfig
import app.minerguru.R
import app.minerguru.model.BlockTemplateSummary
import app.minerguru.model.tr
import app.minerguru.model.MinedCoin
import app.minerguru.model.MinerSystemInfo
import app.minerguru.model.StratumHost
import app.minerguru.model.StratumJob
import app.minerguru.model.StratumProbeError
import app.minerguru.model.StratumProbeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** One pool a miner works for, as far as the Stratum probe needs it. */
data class BlockProbeTarget(
    val host: String,
    val port: Int,
    val tls: Boolean,
    /** The miner's own Stratum user; the payout check runs against its address. */
    val user: String,
    val coin: MinedCoin,
    val slot: Slot,
    /** Hashrate share in dual pool mode, `null` when the miner works for one pool only. */
    val sharePercent: Int? = null
) {
    enum class Slot { PRIMARY, SECONDARY }

    /** The probe logs in as `<wallet>.minerguru`, so it never takes the miner's worker name. */
    val probeUser: String get() = user.substringBefore('.') + ".minerguru"

    companion object {
        /**
         * Pools to probe for a miner: none if the firmware reports the block itself (AxeOS 2.13+),
         * both in dual pool mode, otherwise the one delivering work.
         */
        fun targets(info: MinerSystemInfo): List<BlockProbeTarget> {
            if (BlockTemplateSummary.from(info) != null) return emptyList()
            val primary = create(info.stratumURL, info.stratumPort, info.stratumTLS, info.stratumUser, info.primaryPoolCoin, Slot.PRIMARY)
            val secondary = create(
                info.fallbackStratumURL, info.fallbackStratumPort, info.fallbackStratumTLS, info.fallbackStratumUser,
                info.secondaryPoolCoin, Slot.SECONDARY
            )
            if (info.isDualPoolMining) {
                return listOfNotNull(
                    primary?.copy(sharePercent = info.dualPoolPrimaryPercent),
                    secondary?.copy(sharePercent = info.dualPoolSecondaryPercent)
                )
            }
            return listOfNotNull(if (info.isUsingFallbackStratum) secondary else primary)
        }

        private fun create(url: String, port: Int, tlsFlag: Boolean?, user: String, coin: MinedCoin, slot: Slot): BlockProbeTarget? {
            val host = StratumHost.clean(url)
            if (host.isEmpty() || port !in 1..65535 || user.isEmpty()) return null
            val scheme = url.trim().lowercase()
            val tls = tlsFlag == true || scheme.startsWith("stratum+ssl://") || scheme.startsWith("stratum+tls://")
            return BlockProbeTarget(host, port, tls, user, coin, slot)
        }
    }
}

/** Opens a short Stratum connection from the phone to read the pool's current block template. */
object StratumProbe {
    val userAgent: String get() = "MinerGuru/${BuildConfig.VERSION_NAME} (block view, no mining)"

    /** Only public data travels here (address in, block template out); pools often use self-signed certificates. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    suspend fun fetchJob(rawHost: String, port: Int, tls: Boolean, user: String, timeoutMs: Int = 10_000): StratumJob =
        withContext(Dispatchers.IO) {
            val host = StratumHost.clean(rawHost)
            if (host.isEmpty() || port !in 1..65535) throw StratumProbeError.InvalidEndpoint
            val session = StratumProbeSession(user, userAgent)
            val deadline = System.currentTimeMillis() + timeoutMs
            val plain = Socket()
            try {
                try {
                    plain.connect(InetSocketAddress(host, port), timeoutMs)
                } catch (e: SocketTimeoutException) {
                    throw StratumProbeError.Timeout
                } catch (e: Exception) {
                    throw StratumProbeError.ConnectionFailed(e.message ?: e.javaClass.simpleName)
                }
                val socket = if (tls) {
                    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
                    (context.socketFactory.createSocket(plain, host, port, true) as SSLSocket).also {
                        it.soTimeout = timeoutMs
                        runCatching { it.startHandshake() }.onFailure { e ->
                            throw StratumProbeError.ConnectionFailed(e.message ?: e.javaClass.simpleName)
                        }
                    }
                } else plain
                socket.use { s ->
                    s.getOutputStream().apply {
                        write(session.openingLines.joinToString("") { "$it\n" }.toByteArray())
                        flush()
                    }
                    readJob(s, session, deadline)
                }
            } finally {
                runCatching { plain.close() }
            }
        }

    private fun readJob(socket: Socket, session: StratumProbeSession, deadline: Long): StratumJob {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8), 64 * 1024)
        var received = 0
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw StratumProbeError.Timeout
            socket.soTimeout = left.toInt()
            val line = try {
                reader.readLine()
            } catch (e: SocketTimeoutException) {
                throw StratumProbeError.Timeout
            } catch (e: Exception) {
                throw StratumProbeError.ConnectionFailed(e.message ?: e.javaClass.simpleName)
            } ?: throw StratumProbeError.Closed
            received += line.length
            if (received > 1_000_000) throw StratumProbeError.Closed
            when (val event = session.receive(line)) {
                is StratumProbeSession.Event.Job -> return event.job
                is StratumProbeSession.Event.Failed -> throw event.error
                null -> Unit
            }
        }
    }
}

/** Results of the Stratum probe per pool, fetched while a miner's detail page is open. */
class BlockProbeStore(
    private val scope: CoroutineScope,
    private val fetch: suspend (BlockProbeTarget) -> StratumJob = { t -> StratumProbe.fetchJob(t.host, t.port, t.tls, t.probeUser) },
    private val now: () -> Long = System::currentTimeMillis
) {
    sealed interface State {
        data object Loading : State
        data class Loaded(val block: BlockTemplateSummary) : State
        data class Failed(val message: String) : State
    }

    data class Entry(val state: State, val updatedAtMillis: Long)

    val entries = mutableStateMapOf<BlockProbeTarget, Entry>()
    var running by mutableStateOf(emptySet<BlockProbeTarget>())
        private set

    /** Probes [target] unless it ran within [MIN_INTERVAL_MS]; [force] skips that wait. */
    fun refresh(target: BlockProbeTarget, force: Boolean = false) {
        if (target in running) return
        val entry = entries[target]
        if (!force && entry != null && now() - entry.updatedAtMillis < MIN_INTERVAL_MS) return
        running = running + target
        if (entry == null) entries[target] = Entry(State.Loading, now())
        scope.launch {
            val state = try {
                val job = fetch(target)
                BlockTemplateSummary.from(job, target.coin, target.user)?.let { State.Loaded(it) }
                    ?: State.Failed(tr(R.string.the_pool_s_job_could))
            } catch (e: StratumProbeError) {
                State.Failed(e.text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                running = running - target
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
            entries[target] = Entry(state, now())
            running = running - target
        }
    }

    /** Forgets all results, e.g. when the probe is switched off. */
    fun reset() = entries.clear()

    companion object {
        /** At most one connection per pool in this time, unless the user asks. */
        const val MIN_INTERVAL_MS = 5 * 60 * 1000L
    }
}
