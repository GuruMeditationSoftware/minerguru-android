package app.minerguru.service

import app.minerguru.model.MinerSystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredMiner(
    val ipAddress: String,
    val info: MinerSystemInfo,
    val rawJSON: String,
    val responseTimeMs: Double
)

object NetworkScanner {
    /** Detects the active local IPv4 `/24` prefix (e.g. `192.168.1`) and the device's own IP. */
    fun detectLocalSubnetPrefix(): Pair<String, String> {
        val fallback = "192.168.1" to "192.168.1.100"
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .map { nif.name to it.hostAddress.orEmpty() }
                }
                .filter { isPrivateIPv4(it.second) }
            val chosen = candidates.firstOrNull { it.first == "wlan0" }
                ?: candidates.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") }
                ?: candidates.firstOrNull()
            val parts = chosen?.second?.split(".")
            if (parts != null && parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" to chosen.second else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    fun isPrivateIPv4(ip: String): Boolean {
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split(".").getOrNull(1)?.toIntOrNull()
            return second != null && second in 16..31
        }
        return false
    }

    fun normalizePrefix(raw: String): String {
        val parts = raw.trim().split(".")
        return if (parts.size >= 3) "${parts[0]}.${parts[1]}.${parts[2]}" else raw.trim()
    }

    fun ipSortKey(ip: String): Long {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return 0
        return (parts[0].toLong() shl 24) or (parts[1].toLong() shl 16) or (parts[2].toLong() shl 8) or parts[3].toLong()
    }

    /** Scans `<prefix>.1`…`.254` concurrently for Bitaxe / NerdQAxe miners. */
    suspend fun scanSubnet(
        rawPrefix: String,
        concurrency: Int = 48,
        timeoutPerHostMs: Long = 1500,
        onProgress: (completed: Int, total: Int) -> Unit,
        onDiscovered: (DiscoveredMiner) -> Unit
    ): List<DiscoveredMiner> = coroutineScope {
        val prefix = normalizePrefix(rawPrefix)
        val hosts = (1..254).map { "$prefix.$it" }
        val client: OkHttpClient = MinerApiClient.http.newBuilder()
            .connectTimeout(timeoutPerHostMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutPerHostMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutPerHostMs + 500, TimeUnit.MILLISECONDS)
            .build()
        val semaphore = Semaphore(concurrency)
        val done = AtomicInteger(0)

        val jobs = hosts.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ensureActive()
                    val start = System.nanoTime()
                    val result = try {
                        val fetched = MinerApiClient.fetchSystemInfo(ip, client)
                        DiscoveredMiner(ip, fetched.info, fetched.rawPretty, (System.nanoTime() - start) / 1_000_000.0)
                            .takeIf { fetched.isMiner }
                    } catch (_: Exception) {
                        null
                    }
                    onProgress(done.incrementAndGet(), hosts.size)
                    result?.also(onDiscovered)
                }
            }
        }
        withContext(Dispatchers.Default) { jobs.mapNotNull { it.await() } }
            .sortedBy { ipSortKey(it.ipAddress) }
    }
}
