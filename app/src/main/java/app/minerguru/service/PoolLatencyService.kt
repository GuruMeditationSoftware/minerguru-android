package app.minerguru.service

import app.minerguru.model.MinerSystemInfo
import app.minerguru.model.PoolLatencyMeasurement
import app.minerguru.model.StratumHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Measures the TCP connect round trip (SYN → SYN-ACK) to stratum pool servers, excluding DNS time. */
object PoolLatencyService {
    suspend fun measureTcpConnectLatencyMs(rawHost: String, port: Int, timeoutMs: Int = 2500): Double? {
        val host = StratumHost.clean(rawHost)
        if (host.isEmpty() || port !in 1..65535) return null
        return withContext(Dispatchers.IO) {
            try {
                val address = InetAddress.getByName(host)
                Socket().use { socket ->
                    val start = System.nanoTime()
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                    (System.nanoTime() - start) / 1_000_000.0
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Pings primary and fallback pool of a miner and combines the result with the miner-reported latency. */
    suspend fun measureForMiner(info: MinerSystemInfo): PoolLatencyMeasurement = coroutineScope {
        val sameEndpoint = StratumHost.clean(info.fallbackStratumURL) == StratumHost.clean(info.stratumURL) &&
            info.fallbackStratumPort == info.stratumPort

        val primary = async {
            if (info.stratumURL.isEmpty()) null else measureTcpConnectLatencyMs(info.stratumURL, info.stratumPort)
        }
        val fallback = async {
            if (info.fallbackStratumURL.isEmpty() || sameEndpoint) null
            else measureTcpConnectLatencyMs(info.fallbackStratumURL, info.fallbackStratumPort)
        }
        val p = primary.await()
        val f = fallback.await() ?: if (info.fallbackStratumURL.isNotEmpty() && sameEndpoint) p else null
        PoolLatencyMeasurement(info.minerReportedPoolLatencyMs, p, f)
    }
}
