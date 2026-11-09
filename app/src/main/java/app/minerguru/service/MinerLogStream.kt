package app.minerguru.service

import app.minerguru.model.L
import app.minerguru.model.LogCategoryFilter
import app.minerguru.model.MinerLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed interface StreamState {
    data object Stopped : StreamState
    data object Connecting : StreamState
    data object Streaming : StreamState
    data object Paused : StreamState
    data class Error(val message: String) : StreamState
}

/** Live WebSocket log (`ws://<host>/api/ws`) of a single miner. */
class MinerLogStream(private val scope: CoroutineScope) {
    private val _entries = MutableStateFlow<List<MinerLogEntry>>(emptyList())
    val entries: StateFlow<List<MinerLogEntry>> = _entries.asStateFlow()
    private val _state = MutableStateFlow<StreamState>(StreamState.Stopped)
    val state: StateFlow<StreamState> = _state.asStateFlow()
    private val _totalLines = MutableStateFlow(0)
    val totalLines: StateFlow<Int> = _totalLines.asStateFlow()

    /** Called with every parsed batch (feeds the share-difficulty chart). */
    @Volatile var onEntries: ((List<MinerLogEntry>) -> Unit)? = null

    private var socket: WebSocket? = null
    private var currentHost = ""
    private val client: OkHttpClient = MinerApiClient.http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val isRunning: Boolean get() = _state.value is StreamState.Streaming || _state.value is StreamState.Connecting

    fun start(host: String) {
        val normalized = MinerApiClient.normalizeHost(host)
        if (normalized.isEmpty()) {
            _state.value = StreamState.Error(L("Ungültige IP-Adresse", "Invalid IP address"))
            return
        }
        if (currentHost != normalized) {
            _entries.value = emptyList()
            _totalLines.value = 0
            currentHost = normalized
        }
        closeSocket()
        _state.value = StreamState.Connecting

        val request = Request.Builder().url("ws://$normalized/api/ws").build()
        var confirmed = false
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
                if (!confirmed) {
                    confirmed = true
                    _state.value = StreamState.Streaming
                }
                append(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                val st = _state.value
                if (st is StreamState.Paused || st is StreamState.Stopped) return
                _state.value = StreamState.Error(L("Verbindung zu", "Connection to") + " ws://$normalized/api/ws " + L("getrennt", "lost") + " (${t.message ?: L("Fehler", "error")})")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== socket) return
                val st = _state.value
                if (st is StreamState.Streaming || st is StreamState.Connecting) {
                    _state.value = StreamState.Error(L("Verbindung vom Miner geschlossen", "Connection closed by the miner"))
                }
            }
        })
    }

    /** Closes the socket so the ESP32 is not loaded, but keeps the buffer for searching/exporting. */
    fun pause() {
        closeSocket()
        _state.value = StreamState.Paused
    }

    fun stop() {
        closeSocket()
        _state.value = StreamState.Stopped
    }

    fun clear() {
        _entries.value = emptyList()
        _totalLines.value = 0
    }

    private fun closeSocket() {
        socket?.close(1000, null)
        socket = null
    }

    private fun append(chunk: String) {
        val now = System.currentTimeMillis()
        val batch = chunk.lines().mapNotNull { MinerLogEntry.parse(it, now) }
        if (batch.isEmpty()) return
        _totalLines.update { it + batch.size }
        onEntries?.invoke(batch)
        _entries.update { (it + batch).takeLast(MAX_BUFFER) }
    }

    companion object {
        const val MAX_BUFFER = 1_500

        fun filtered(entries: List<MinerLogEntry>, category: LogCategoryFilter, search: String) =
            entries.filter { it.matches(category) && it.matches(search) }

        fun exportText(
            minerName: String, ip: String, hardware: String,
            entries: List<MinerLogEntry>, total: Int, category: LogCategoryFilter, search: String
        ): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val lines = mutableListOf(
                "# MinerGuru Live-Log Export",
                "# Miner: $minerName ($ip) — $hardware",
                "# " + L("Exportiert am", "Exported at") + ": ${fmt.format(Date())}",
                "# Filter: ${category.label}" + (if (search.isEmpty()) "" else " | " + L("Suche", "Search") + ": '$search'"),
                "# " + L("Zeilen", "Lines") + ": ${entries.size} (" + L("von", "of") + " $total " + L("im Puffer", "in buffer") + ")",
                "-".repeat(78)
            )
            entries.forEach { lines += it.exportLine(fmt) }
            return lines.joinToString("\n")
        }
    }
}

/** Tracks the difficulty of every ASIC result for the share chart: from the live log, or by short samples. */
class ShareDifficultyTracker(private val scope: CoroutineScope) {
    private val _samples = MutableStateFlow<List<app.minerguru.model.ShareSample>>(emptyList())
    val samples: StateFlow<List<app.minerguru.model.ShareSample>> = _samples.asStateFlow()
    private val _isSampling = MutableStateFlow(false)
    val isSampling: StateFlow<Boolean> = _isSampling.asStateFlow()
    private val _lastSample = MutableStateFlow<Long?>(null)
    val lastSample: StateFlow<Long?> = _lastSample.asStateFlow()

    private var job: Job? = null

    fun ingest(entries: List<MinerLogEntry>) {
        val new = entries.mapNotNull { app.minerguru.model.ShareSample.parse(it) }
        if (new.isEmpty()) return
        _samples.update { (it + new).sortedBy { s -> s.date }.takeLast(MAX_SAMPLES) }
    }

    /** Samples the log WebSocket for [SAMPLE_WINDOW_MS] once per [SAMPLE_INTERVAL_MS] while no live log runs. */
    fun startSampling(host: String, isLiveLogRunning: () -> Boolean) {
        job?.cancel()
        job = scope.launch {
            while (true) {
                if (!isLiveLogRunning()) sampleOnce(host, isLiveLogRunning)
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stopSampling() {
        job?.cancel()
        job = null
        _isSampling.value = false
    }

    private suspend fun sampleOnce(host: String, isLiveLogRunning: () -> Boolean) {
        _isSampling.value = true
        try {
            val collected = mutableListOf<MinerLogEntry>()
            val normalized = MinerApiClient.normalizeHost(host)
            val client = MinerApiClient.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val ws = client.newWebSocket(Request.Builder().url("ws://$normalized/api/ws").build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val now = System.currentTimeMillis()
                    synchronized(collected) { collected += text.lines().mapNotNull { MinerLogEntry.parse(it, now) } }
                }
            })
            try {
                delay(SAMPLE_WINDOW_MS)
            } finally {
                ws.close(1000, null)
            }
            if (!isLiveLogRunning()) {
                ingest(synchronized(collected) { collected.toList() })
                _lastSample.value = System.currentTimeMillis()
            }
        } finally {
            _isSampling.value = false
        }
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 60_000L
        const val SAMPLE_WINDOW_MS = 10_000L
        const val MAX_SAMPLES = 600
    }
}
