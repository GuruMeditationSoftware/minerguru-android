package app.minerguru.service

import app.minerguru.R
import app.minerguru.widget.MinerWidgets
import app.minerguru.widget.WidgetBridge

import app.minerguru.BuildConfig
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.minerguru.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class BannerMessage(val text: String, val isError: Boolean, val retry: (() -> Unit)? = null)

/** Save state of a settings form (control tab, pool assignment) that outlives the screen. */
class SaveStatus {
    var isSaving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var success by mutableStateOf<String?>(null)
}

/** Pool configuration dialog that is open on the pools page. */
data class PoolEditor(val config: PoolConfig, val isNew: Boolean)

/** Central state: miner list, polling, settings, subnet scan and pool library. */
/** A one-time code the app waits for; the dialog completes [answer]. */
class OtpRequest(val minerName: String, val problem: String?) {
    val answer = CompletableDeferred<String?>()
}

/** A write by hand waiting for the user's yes; the dialog completes [answer]. */
class WriteRequest(val minerName: String, val fields: List<ChangeField>, val restarts: Boolean) {
    val answer = CompletableDeferred<Boolean>()
}

/** The user declined a write: nothing happened, so nothing to report. */
class WriteDeclined : Exception()

class MinerStore(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("minerguru", Context.MODE_PRIVATE)

    var miners by mutableStateOf<List<MinerDevice>>(emptyList())
        private set

    /** Where a tap on a widget asked the app to go, until the screen has opened it. */
    var pendingRoute by mutableStateOf<String?>(null)
    var isRefreshingAll by mutableStateOf(false)
        private set
    var lastGlobalRefresh by mutableStateOf<Long?>(null)
        private set
    /** Fleet hashrate after each refresh round, for the chart on the overview (GH/s). */
    var fleetHistory by mutableStateOf<List<Double>>(emptyList())
        private set

    // Settings
    var pollingIntervalSeconds by mutableStateOf(prefs.getInt(KEY_POLLING, 5).coerceAtLeast(2))
        private set
    var tempWarningThresholdC by mutableStateOf(prefs.getFloat(KEY_TEMP, 68f).toDouble())
        private set
    var autoScanOnEmptyLaunch by mutableStateOf(prefs.getBoolean(KEY_AUTOSCAN, true))
        private set

    /** Read the block template from the pool for miners that do not report it (opt-in, off by default). */
    var stratumProbeEnabled by mutableStateOf(prefs.getBoolean(KEY_STRATUM_PROBE, false))
        private set
    val blockProbes = BlockProbeStore(viewModelScope)
    val updates = UpdateChecker(prefs, viewModelScope)
    var soloChanceTimeframe by mutableStateOf(
        runCatching { SoloChanceTimeframe.valueOf(prefs.getString(KEY_TIMEFRAME, null) ?: "") }.getOrDefault(SoloChanceTimeframe.PER_DAY)
    )
        private set

    // Scan state
    var subnetPrefix by mutableStateOf(prefs.getString(KEY_SUBNET, null) ?: NetworkScanner.detectLocalSubnetPrefix().first)
        private set
    val detectedLocalIp: String = NetworkScanner.detectLocalSubnetPrefix().second
    var isScanning by mutableStateOf(false)
        private set
    var scanCompleted by mutableStateOf(0)
        private set
    var scanTotal by mutableStateOf(254)
        private set
    var discoveredMiners by mutableStateOf<List<DiscoveredMiner>>(emptyList())
        private set
    var scanFinished by mutableStateOf(false)
        private set

    // Pool library
    var poolLibrary by mutableStateOf(loadPoolLibrary())
        private set

    // Privacy & language
    var language by mutableStateOf(loadLanguage())
        private set

    // Notifications (MG-01); stored in AlertCenter so the worker and the service see them too.
    var alertsEnabled by mutableStateOf(AlertCenter.isEnabled(app))
        private set
    var mutedMiners by mutableStateOf(AlertCenter.mutedMiners(app))
        private set
    var disabledAlertKinds by mutableStateOf(AlertCenter.disabledKinds(app))
        private set
    var backgroundMode by mutableStateOf(AlertCenter.backgroundMode(app))
        private set

    // One-time codes of NerdOS miners.
    private val otp = OtpCenter.get(app).also { it.prompt = ::askForOtpCode }
    var otpRequest by mutableStateOf<OtpRequest?>(null)
        private set
    var writeRequest by mutableStateOf<WriteRequest?>(null)
        private set
    private val confirming = Mutex()
    var isReadOnly by mutableStateOf(false)
        private set
    var restartsOnFlatline by mutableStateOf(AlertCenter.restartsOnFlatline(app))
        private set
    var restartsToPrimary by mutableStateOf(AlertCenter.restartsToPrimary(app))
        private set
    var expectedAddresses by mutableStateOf(AlertCenter.expectedAddresses(app))
        private set
    private var otpRevision by mutableStateOf(0)

    // Power (MG-14); stored in PowerCenter so the foreground service switches too.
    var powerTariff by mutableStateOf(PowerCenter.tariff(app))
        private set
    var powerRules by mutableStateOf(PowerCenter.rules(app))
        private set
    var powerRunsSchedules by mutableStateOf(PowerCenter.runsSchedules(app))
        private set
    var powerNightFan by mutableStateOf(PowerCenter.nightFanPercent(app))
        private set
    var powerStates by mutableStateOf(PowerCenter.states(app))
        private set
    var powerError by mutableStateOf<String?>(null)
        private set

    // Pool schedules, maintenance and mail; stored in their centers so background polls see them too.
    var poolSchedules by mutableStateOf(PoolScheduleCenter.rules(app))
        private set
    var poolScheduleError by mutableStateOf<String?>(null)
        private set
    var maintenanceTasks by mutableStateOf(MaintenanceCenter.tasks(app))
        private set
    var maintenanceRecords by mutableStateOf(MaintenanceCenter.records(app))
        private set
    var emailSettings by mutableStateOf(EmailAlerts.settings(app))
        private set

    // Android 17: access to devices in the local network is a runtime permission.
    var localNetworkGranted by mutableStateOf(checkLocalNetworkPermission())
        private set

    // Drafts that survive rotation, folding and navigating away (keyed by miner id).
    val controlForms = mutableStateMapOf<String, MinerSettingsForm>()
    private val controlStatus = mutableMapOf<String, SaveStatus>()
    val poolDrafts = mutableStateMapOf<String, MinerSettingsForm>()
    val poolBaselines = mutableStateMapOf<String, MinerSettingsForm>()
    val poolApplying = mutableStateMapOf<String, Boolean>()
    val poolErrors = mutableStateMapOf<String, String>()
    var poolEditor by mutableStateOf<PoolEditor?>(null)

    private val _banners = MutableSharedFlow<BannerMessage>(extraBufferCapacity = 8)
    val banners: SharedFlow<BannerMessage> = _banners.asSharedFlow()

    private var pollingJob: Job? = null
    private var scanJob: Job? = null
    private val scanLock = Any()
    private var inForeground = false

    init {
        DiagnosticLog.shared.activate(app.filesDir)
        ChangeLog.shared.activate(app.filesDir)
        isReadOnly = ChangeLog.shared.isReadOnly
        DiagnosticLog.shared.write("app", "start version=${BuildConfig.VERSION_NAME} android=${android.os.Build.VERSION.SDK_INT}")
        Lang.apply(language)
        Privacy.active = prefs.getBoolean(KEY_PRIVACY, false)
        loadPersistedMiners()
        updates.start()
        runCatching { MinerWidgets.publishPreviews(app) }
        if (miners.isEmpty() && autoScanOnEmptyLaunch && localNetworkGranted) {
            viewModelScope.launch {
                delay(600)
                startScan()
            }
        }
    }

    // region Aggregations

    val onlineMiners: List<MinerDevice> get() = miners.filter { it.connectionState.isOnline && it.latestInfo != null }
    val totalHashrateGHs: Double get() = onlineMiners.sumOf { it.latestInfo?.hashRate ?: 0.0 }
    val totalPowerWatts: Double get() = onlineMiners.sumOf { it.latestInfo?.power ?: 0.0 }
    val fleetEfficiencyJPerTH: Double
        get() {
            val th = totalHashrateGHs / 1000.0
            return if (th > 0.01 && totalPowerWatts > 0) totalPowerWatts / th else 0.0
        }
    val totalSharesAccepted: Int get() = onlineMiners.sumOf { it.latestInfo?.sharesAccepted ?: 0 }
    val totalSharesRejected: Int get() = onlineMiners.sumOf { it.latestInfo?.sharesRejected ?: 0 }
    val highestBestDiffFormatted: String
        get() = onlineMiners.maxByOrNull { it.latestInfo?.bestDiffNumeric ?: 0.0 }?.latestInfo?.bestDiffFormatted ?: "—"
    val averagePoolLatencyMs: Double?
        get() = onlineMiners.mapNotNull { it.activePoolLatencyMs }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()
    val activeFleetCoinSummaries: List<FleetCoinMiningSummary> get() = FleetCoinMiningSummary.summaries(onlineMiners)
    val bestSessionMiner: Pair<MinerDevice, Double>?
        get() = onlineMiners.mapNotNull { m -> m.latestInfo?.sessionBestDiff?.let { m to it } }.maxByOrNull { it.second }

    fun miner(id: String): MinerDevice? = miners.firstOrNull { it.id == id }

    // endregion

    // region Settings

    fun updatePollingInterval(seconds: Int) {
        pollingIntervalSeconds = seconds
        prefs.edit().putInt(KEY_POLLING, seconds).apply()
    }

    fun updateTempWarning(c: Double) {
        tempWarningThresholdC = c
        prefs.edit().putFloat(KEY_TEMP, c.toFloat()).apply()
    }

    fun updateAutoScan(enabled: Boolean) {
        autoScanOnEmptyLaunch = enabled
        prefs.edit().putBoolean(KEY_AUTOSCAN, enabled).apply()
    }

    fun updateStratumProbe(enabled: Boolean) {
        stratumProbeEnabled = enabled
        prefs.edit().putBoolean(KEY_STRATUM_PROBE, enabled).apply()
        if (!enabled) blockProbes.reset()
    }

    fun updateSubnetPrefix(value: String) {
        subnetPrefix = value
        prefs.edit().putString(KEY_SUBNET, value).apply()
    }

    fun updateSoloTimeframe(tf: SoloChanceTimeframe) {
        soloChanceTimeframe = tf
        prefs.edit().putString(KEY_TIMEFRAME, tf.name).apply()
    }

    /** Turn alerts on only after the notification permission was granted. */
    fun updateAlertsEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        AlertCenter.setEnabled(app, enabled)
        alertsEnabled = enabled
        BackgroundMonitor.sync(app)
    }

    fun updateAlertKind(kind: AlertKind, enabled: Boolean) {
        AlertCenter.setKindEnabled(getApplication(), kind, enabled)
        disabledAlertKinds = AlertCenter.disabledKinds(getApplication())
    }

    fun setMinerMuted(minerId: String, muted: Boolean) {
        AlertCenter.setMuted(getApplication(), minerId, muted)
        mutedMiners = AlertCenter.mutedMiners(getApplication())
    }

    fun updateBackgroundMode(mode: BackgroundMode) {
        val app = getApplication<Application>()
        AlertCenter.setBackgroundMode(app, mode)
        backgroundMode = mode
        BackgroundMonitor.sync(app)
    }

    // endregion

    // region Persistence

    private fun loadPersistedMiners() {
        miners = readPersistedMiners(prefs)
    }

    private fun saveMiners() {
        val arr = JSONArray()
        miners.map { it.persistedConfig }.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("ipAddress", c.ipAddress); put("addedAt", c.addedAt)
                c.customName?.let { put("customName", it) }
                c.cachedHostname?.let { put("cachedHostname", it) }
                c.cachedHardwareModel?.let { put("cachedHardwareModel", it) }
            })
        }
        prefs.edit().putString(KEY_MINERS, arr.toString()).apply()
    }

    // endregion

    // region Miner CRUD

    fun isIpAlreadyAdded(ip: String): Boolean {
        val n = MinerApiClient.normalizeHost(ip).lowercase()
        return miners.any { it.ipAddress.lowercase() == n }
    }

    fun addMiner(
        rawIp: String, customName: String? = null,
        initialInfo: MinerSystemInfo? = null, rawJSON: String? = null
    ): MinerDevice? {
        val ip = MinerApiClient.normalizeHost(rawIp)
        if (!MinerApiClient.isValidHost(ip)) return null

        miners.indexOfFirst { it.ipAddress.lowercase() == ip.lowercase() }.takeIf { it >= 0 }?.let { idx ->
            if (!customName.isNullOrEmpty()) {
                miners = miners.toMutableList().also { it[idx] = it[idx].copy(customName = customName) }
                saveMiners()
            }
            return miners[idx]
        }

        val trimmed = customName?.trim()
        val config = PersistedMinerConfig(
            ipAddress = ip,
            customName = trimmed?.takeIf { it.isNotEmpty() },
            cachedHostname = initialInfo?.hostname,
            cachedHardwareModel = initialInfo?.detectedHardwareTitle
        )
        var device = MinerDevice(config, if (initialInfo != null) ConnectionState.Online else ConnectionState.Checking)
        if (initialInfo != null) {
            device = device.copy(
                latestInfo = initialInfo, rawJSON = rawJSON, lastSeenAt = System.currentTimeMillis(),
                history = listOf(sampleOf(initialInfo, initialInfo.minerReportedPoolLatencyMs))
            )
        }
        miners = (miners + device).sortedBy { NetworkScanner.ipSortKey(it.ipAddress) }
        saveMiners()
        viewModelScope.launch { refreshMiner(device.id) }
        return device
    }

    fun removeMiner(id: String) {
        miners = miners.filterNot { it.id == id }
        AlertCenter.forget(getApplication(), id)
        PowerCenter.forget(getApplication(), id)
        otp.invalidate(id)
        PoolScheduleCenter.forget(getApplication(), id)
        MaintenanceCenter.forget(getApplication(), id)
        HistoryRecorder.forget(getApplication(), id)
        poolSchedules = PoolScheduleCenter.rules(getApplication())
        maintenanceTasks = MaintenanceCenter.tasks(getApplication())
        reloadPower()
        mutedMiners = AlertCenter.mutedMiners(getApplication())
        saveMiners()
        // Polling stops with the last miner, so the widgets would keep showing it.
        val app = getApplication<Application>()
        val remaining = miners
        viewModelScope.launch { runCatching { WidgetBridge.publish(app, remaining) } }
    }

    fun updateCustomName(id: String, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        miners = miners.map { if (it.id == id) it.copy(customName = trimmed) else it }
        saveMiners()
    }

    // endregion

    // region Polling

    /** Polling runs only while the app is visible. */
    fun setForeground(foreground: Boolean) {
        inForeground = foreground
        if (foreground) {
            alertsEnabled = AlertCenter.isEnabled(getApplication())
            reloadPower() // the service may have switched miners meanwhile
            BackgroundMonitor.sync(getApplication())
            startPolling()
        } else {
            pollingJob?.cancel()
            pollingJob = null
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                refreshAllMiners()
                delay(maxOf(2, pollingIntervalSeconds) * 1000L)
            }
        }
    }

    private data class FetchResult(
        val id: String, val info: MinerSystemInfo?, val raw: String?,
        val latency: PoolLatencyMeasurement?, val error: String?
    )

    private suspend fun fetch(id: String, host: String): FetchResult = try {
        val fetched = MinerApiClient.fetchSystemInfo(host)
        ChangeLog.shared.noteValues(host, fetched.rawPretty)
        val latency = PoolLatencyService.measureForMiner(fetched.info)
        FetchResult(id, fetched.info, fetched.rawPretty, latency, null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult(id, null, null, null, e.message)
    }

    private fun sampleOf(info: MinerSystemInfo, latency: Double?) = TelemetrySample(
        hashRateGHs = info.hashRate, asicTempC = info.temp, vrTempC = info.vrTemp,
        powerWatts = info.power, poolLatencyMs = latency, fanPercent = info.fanSpeedPercent
    )

    private fun apply(res: FetchResult, device: MinerDevice): MinerDevice {
        val info = res.info
        val latency = res.latency
        if (info == null || latency == null) {
            val reason = if (!localNetworkGranted) tr(R.string.no_access_to_the_local)
            else res.error ?: tr(R.string.unreachable)
            return device.copy(connectionState = ConnectionState.Offline(reason))
        }
        val history = (device.history + sampleOf(info, latency.effectiveLatencyMs(info.isUsingFallbackStratum)))
            .takeLast(MAX_HISTORY)
        return device.copy(
            latestInfo = info, rawJSON = res.raw, poolLatency = latency,
            connectionState = ConnectionState.Online, lastSeenAt = System.currentTimeMillis(),
            cachedHostname = info.hostname, cachedHardwareModel = info.detectedHardwareTitle,
            history = history
        )
    }

    private fun applyResults(results: List<FetchResult>) {
        val byId = results.associateBy { it.id }
        var persistedChanged = false
        miners = miners.map { d ->
            val r = byId[d.id] ?: return@map d
            val updated = apply(r, d)
            if (updated.cachedHostname != d.cachedHostname || updated.cachedHardwareModel != d.cachedHardwareModel) persistedChanged = true
            updated
        }
        if (persistedChanged) saveMiners()
    }

    suspend fun refreshAllMiners() {
        if (miners.isEmpty()) return
        isRefreshingAll = true
        try {
            val targets = miners.map { it.id to it.ipAddress }
            val results = coroutineScope { targets.map { (id, host) -> async { fetch(id, host) } }.awaitAll() }
            applyResults(results)
            lastGlobalRefresh = System.currentTimeMillis()
            fleetHistory = (fleetHistory + totalHashrateGHs).takeLast(MAX_HISTORY)
            val app = getApplication<Application>()
            val snapshot = miners
            HistoryRecorder.record(app, snapshot)
            viewModelScope.launch { runCatching { WidgetBridge.publish(app, snapshot) } }
            val events = AlertCenter.evaluate(app, snapshot) + AlertCenter.deliver(app, MaintenanceCenter.dueEvents(app))
            // Switch and send mail in the background, so neither holds up the next poll.
            viewModelScope.launch { EmailAlerts.deliver(app, events) }
            viewModelScope.launch { ChangeLog.withSource(ChangeSource.SCHEDULE) { evaluatePower(snapshot) } }
            viewModelScope.launch {
                ChangeLog.withSource(ChangeSource.SCHEDULE) { PoolScheduleCenter.evaluate(app, snapshot, poolLibrary) }
                poolScheduleError = PoolScheduleCenter.lastError
            }
        } finally {
            isRefreshingAll = false
        }
    }

    suspend fun refreshMiner(id: String) {
        val host = miner(id)?.ipAddress ?: return
        applyResults(listOf(fetch(id, host)))
    }

    fun refreshMinerAsync(id: String) {
        viewModelScope.launch { refreshMiner(id) }
    }

    // endregion

    // region Control

    /** Writes settings to a miner, with a one-time code session where it demands one. */
    private suspend fun writeSettings(miner: MinerDevice, payload: MinerSystemUpdatePayload) {
        otp.perform(miner, interactive = true) { MinerApiClient.updateSystemSettings(miner.ipAddress, payload, it) }
        otpRevision++
    }

    /** Online miners with a one-time code and an enabled schedule that cannot run for lack of a session. */
    val minersAwaitingOtp: List<MinerDevice>
        get() {
            otpRevision
            val app = getApplication<Application>()
            val rules = PowerCenter.rules(app).filter { it.isEnabled }
            val poolRules = PoolScheduleCenter.rules(app).filter { it.isEnabled }
            return miners.filter { miner ->
                miner.connectionState.isOnline && !otp.canWriteUnattended(miner)
                    && (rules.any { it.appliesTo(miner.id) } || poolRules.any { it.appliesTo(miner.id) })
            }
        }

    suspend fun enterOtpCode(miner: MinerDevice) {
        otp.authorize(miner)
        otpRevision++
    }

    private suspend fun askForOtpCode(minerName: String, problem: String?): String? {
        val request = OtpRequest(minerName, problem)
        otpRequest = request
        return try {
            request.answer.await()
        } finally {
            otpRequest = null
        }
    }

    fun updateReadOnly(on: Boolean) {
        ChangeLog.shared.isReadOnly = on
        isReadOnly = on
    }

    fun updateRestartsOnFlatline(on: Boolean) {
        AlertCenter.setRestartsOnFlatline(getApplication(), on)
        restartsOnFlatline = on
    }

    fun updateRestartsToPrimary(on: Boolean) {
        AlertCenter.setRestartsToPrimary(getApplication(), on)
        restartsToPrimary = on
    }

    fun updateExpectedAddress(coin: MinedCoin, address: String) {
        val app = getApplication<Application>()
        AlertCenter.setExpectedAddress(app, coin, address)
        expectedAddresses = AlertCenter.expectedAddresses(app)
    }

    /** Asks before a write by hand, with the values it replaces. Schedules were agreed to when they were set up. */
    private suspend fun confirm(miner: MinerDevice, payload: MinerSystemUpdatePayload, clearing: List<Int> = emptyList(), restarts: Boolean = false) {
        if (ChangeLog.shared.isReadOnly) throw MinerApiException(MinerApiClient.readOnlyMessage())
        val current = miner.rawJSON?.let { ChangeLog.flatten(it) }.orEmpty()
        val fields = ChangeLog.changes(payload.toJson().toString(), current) +
            clearing.map { ChangeField("pools.$it.stratumURL", null, tr(R.string.cleared)) }
        val request = WriteRequest(miner.displayName, fields, restarts)
        // One dialog at a time: several miners can ask at once, and a second request must not replace the first.
        val accepted = confirming.withLock {
            writeRequest = request
            try { request.answer.await() } finally { writeRequest = null }
        }
        if (!accepted) throw WriteDeclined()
    }

    /** Puts the expected address into one slot of a miner, after the usual confirmation. */
    suspend fun applyExpectedAddress(address: String, slotKey: String, minerId: String) {
        val m = miner(minerId) ?: return
        val form = MinerSettingsForm.from(m)
        val index = when (slotKey) {
            "primary" -> form.primarySlot
            "fallback" -> form.secondarySlot
            else -> slotKey.removePrefix("pool").toIntOrNull() ?: -1
        }
        val slot = form.slots.getOrNull(index) ?: return
        val slots = form.slots.toMutableList().also { it[index] = slot.copy(user = PayoutCheck.user(slot.user, address)) }
        applyPoolSettings(form.copy(slots = slots), minerId)
    }

    suspend fun updateMinerSettings(minerId: String, payload: MinerSystemUpdatePayload) {
        val m = miner(minerId) ?: return
        confirm(m, payload)
        writeSettings(m, payload)
        showBanner(tr(R.string.settings_sent_to, m.displayName), false)
        delay(600)
        refreshMiner(minerId)
    }

    fun restartMiner(minerId: String) {
        val m = miner(minerId) ?: return
        viewModelScope.launch {
            try {
                MinerApiClient.restartMiner(m.ipAddress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showBanner(tr(R.string.restart_of_failed, m.displayName) + ": ${e.message}", true) {
                    restartMiner(minerId)
                }
                return@launch
            }
            showBanner(tr(R.string.restart_of_triggered, m.displayName), false)
            miners = miners.map { if (it.id == minerId) it.copy(connectionState = ConnectionState.Checking) else it }
            delay(4000)
            refreshMiner(minerId)
        }
    }

    fun showBanner(text: String, isError: Boolean, retry: (() -> Unit)? = null) {
        _banners.tryEmit(BannerMessage(text, isError, retry))
    }

    /** Control-tab form of a miner; created from its current settings on first use. */
    fun controlForm(minerId: String): MinerSettingsForm? =
        controlForms[minerId] ?: miner(minerId)?.takeIf { it.latestInfo != null }?.let { m ->
            MinerSettingsForm.from(m).also { controlForms[minerId] = it }
        }

    fun updateControlForm(minerId: String, form: MinerSettingsForm) { controlForms[minerId] = form }

    fun reloadControlForm(minerId: String) {
        miner(minerId)?.let { controlForms[minerId] = MinerSettingsForm.from(it) }
    }

    fun saveStatus(minerId: String): SaveStatus = controlStatus.getOrPut(minerId) { SaveStatus() }

    /** Runs a write to a miner in the view-model scope, so leaving the screen does not cancel it. */
    fun runSave(minerId: String, successText: String, action: suspend () -> Unit) {
        val status = saveStatus(minerId)
        status.isSaving = true; status.error = null; status.success = null
        viewModelScope.launch {
            try {
                action()
                status.success = successText
            } catch (e: CancellationException) {
                throw e
            } catch (_: WriteDeclined) {
                // Nothing happened.
            } catch (e: Exception) {
                status.error = e.message ?: tr(R.string.transfer_failed)
            } finally {
                status.isSaving = false
            }
        }
    }

    // endregion

    // region Network scan

    fun startScan() {
        scanJob?.cancel()
        discoveredMiners = emptyList()
        scanCompleted = 0
        scanTotal = 254
        scanFinished = false
        isScanning = true
        val prefix = subnetPrefix
        scanJob = viewModelScope.launch {
            try {
                val results = NetworkScanner.scanSubnet(
                    prefix,
                    // Both run on up to 48 IO threads at once: without the lock two finds could overwrite each other.
                    onProgress = { done, total ->
                        if (done % 12 == 0 || done == total) synchronized(scanLock) {
                            scanCompleted = maxOf(scanCompleted, done)
                            scanTotal = total
                        }
                    },
                    onDiscovered = { found ->
                        synchronized(scanLock) {
                            discoveredMiners = (discoveredMiners.filter { it.ipAddress != found.ipAddress } + found)
                                .sortedBy { NetworkScanner.ipSortKey(it.ipAddress) }
                        }
                    }
                )
                scanCompleted = scanTotal
                scanFinished = true
                if (results.isNotEmpty() && miners.isEmpty()) {
                    results.filterNot { isIpAlreadyAdded(it.ipAddress) }
                        .forEach { addMiner(it.ipAddress, null, it.info, it.rawJSON) }
                    showBanner(trp(R.plurals.miners_count, results.size) + tr(R.string.found_in_the_local_network), false)
                }
            } finally {
                isScanning = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        isScanning = false
    }

    fun addDiscovered(item: DiscoveredMiner) {
        if (!isIpAlreadyAdded(item.ipAddress)) addMiner(item.ipAddress, null, item.info, item.rawJSON)
    }

    fun addAllDiscovered() {
        var added = 0
        for (item in discoveredMiners) {
            if (!isIpAlreadyAdded(item.ipAddress)) {
                addMiner(item.ipAddress, null, item.info, item.rawJSON)
                added++
            }
        }
        if (added > 0) showBanner(trp(R.plurals.miners_count, added) + tr(R.string.added_to_the_dashboard), false)
    }

    // endregion

    // region Power

    fun updatePowerTariff(tariff: PowerTariff) {
        PowerCenter.setTariff(getApplication(), tariff)
        reloadPower()
    }

    fun updatePowerRules(rules: List<ScheduleRule>) {
        PowerCenter.setRules(getApplication(), rules)
        reloadPower()
        viewModelScope.launch { evaluatePower(miners) }
    }

    fun removePowerZone(zoneId: String) {
        PowerCenter.removeZone(getApplication(), zoneId)
        reloadPower()
    }

    fun updatePowerRunsSchedules(runs: Boolean) {
        PowerCenter.setRunsSchedules(getApplication(), runs)
        reloadPower()
        viewModelScope.launch { evaluatePower(miners) }
    }

    fun updatePowerNightFan(percent: Int) {
        PowerCenter.setNightFanPercent(getApplication(), percent)
        reloadPower()
    }

    fun updatePoolSchedules(rules: List<PoolScheduleRule>) {
        PoolScheduleCenter.setRules(getApplication(), rules)
        poolSchedules = rules
    }

    fun upsertMaintenanceTask(task: MaintenanceTask) {
        val updated = if (maintenanceTasks.any { it.id == task.id }) maintenanceTasks.map { if (it.id == task.id) task else it } else maintenanceTasks + task
        MaintenanceCenter.setTasks(getApplication(), updated)
        maintenanceTasks = updated
    }

    fun removeMaintenanceTask(id: String) {
        maintenanceTasks = maintenanceTasks.filterNot { it.id == id }
        MaintenanceCenter.setTasks(getApplication(), maintenanceTasks)
    }

    fun markMaintenanceDone(task: MaintenanceTask, date: Long, notes: String) {
        MaintenanceCenter.markDone(getApplication(), task, date, notes)
        maintenanceTasks = MaintenanceCenter.tasks(getApplication())
        maintenanceRecords = MaintenanceCenter.records(getApplication())
    }

    fun removeMaintenanceRecord(id: String) {
        maintenanceRecords = maintenanceRecords.filterNot { it.id == id }
        MaintenanceCenter.setRecords(getApplication(), maintenanceRecords)
    }

    fun updateEmailSettings(settings: EmailSettings, password: String?) {
        EmailAlerts.setSettings(getApplication(), settings)
        password?.let { EmailAlerts.setPassword(getApplication(), it) }
        emailSettings = settings
    }

    private suspend fun evaluatePower(snapshot: List<MinerDevice>) {
        PowerCenter.evaluate(getApplication(), snapshot)
        powerStates = PowerCenter.states(getApplication())
        powerError = PowerCenter.lastError
    }

    private fun reloadPower() {
        val app = getApplication<Application>()
        powerTariff = PowerCenter.tariff(app)
        powerRules = PowerCenter.rules(app)
        powerRunsSchedules = PowerCenter.runsSchedules(app)
        powerNightFan = PowerCenter.nightFanPercent(app)
        powerStates = PowerCenter.states(app)
    }

    // endregion

    // region Pool library

    private fun loadPoolLibrary(): List<PoolConfig> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_POOLS, null) ?: return emptyList())
        (0 until arr.length()).mapNotNull { PoolConfig.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun savePoolLibrary(list: List<PoolConfig>) {
        poolLibrary = list
        prefs.edit().putString(KEY_POOLS, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun poolConfig(matching: MinerSettingsForm.PoolEndpoint): PoolConfig? =
        if (!matching.hasContent) null else poolLibrary.firstOrNull { it.matches(matching) }

    fun addPool(config: PoolConfig) = savePoolLibrary(poolLibrary + config)

    fun updatePool(config: PoolConfig) = savePoolLibrary(poolLibrary.map { if (it.id == config.id) config else it })

    fun removePool(id: String) = savePoolLibrary(poolLibrary.filterNot { it.id == id })

    fun duplicatePool(id: String): PoolConfig? {
        val original = poolLibrary.firstOrNull { it.id == id } ?: return null
        val copy = original.copy(id = java.util.UUID.randomUUID().toString(), name = original.name + tr(R.string.copy_suffix))
        addPool(copy)
        return copy
    }

    /** Adds every pool configured on a miner (all slots) that is not in the library yet. Returns the number added. */
    fun importPoolsFromMiners(): Int {
        val added = mutableListOf<PoolConfig>()
        for (miner in miners) {
            if (miner.latestInfo == null) continue
            for (endpoint in MinerSettingsForm.from(miner).slots) {
                if (endpoint.host.isEmpty()) continue
                if (poolConfig(endpoint) != null || added.any { it.matches(endpoint) }) continue
                added += PoolConfig.fromEndpoint(PoolConfig.suggestedName(endpoint.host, endpoint.port, endpoint.user), endpoint)
            }
        }
        if (added.isNotEmpty()) savePoolLibrary(poolLibrary + added)
        return added.size
    }

    /**
     * Writes the pool part of [form] to the miner, empties slots the user cleared (AxeOS 2.15+) and restarts
     * the miner: AxeOS only stores pool changes and uses them after a reboot.
     */
    suspend fun applyPoolSettings(form: MinerSettingsForm, minerId: String) {
        val m = miner(minerId) ?: return
        confirm(m, form.poolPayload(m.supportsDualPool), form.slotsToClear, restarts = true)
        writeSettings(m, form.poolPayload(m.supportsDualPool))
        for (slot in form.slotsToClear) MinerApiClient.deletePoolSlot(m.ipAddress, slot)
        MinerApiClient.restartMiner(m.ipAddress)
        showBanner(tr(R.string.pool_configuration_sent_to_miner, m.displayName), false)
        miners = miners.map { if (it.id == minerId) it.copy(connectionState = ConnectionState.Checking) else it }
        // Wait until the miner answers again, so the caller reloads the configuration it really uses.
        delay(5000)
        repeat(10) {
            delay(3000)
            refreshMiner(minerId)
            if (miner(minerId)?.connectionState?.isOnline == true) return
        }
    }

    // endregion

    // region Pool assignment drafts

    fun isPoolDraftDirty(id: String): Boolean {
        val d = poolDrafts[id] ?: return false
        val b = poolBaselines[id] ?: return false
        return !d.hasSamePoolSettings(b)
    }

    /** Takes the miner's current pools as draft, unless the user is editing them (or [force]). */
    fun reloadPoolDraft(id: String, force: Boolean) {
        val m = miner(id) ?: return
        if (m.latestInfo == null) return
        if (!force && (isPoolDraftDirty(id) || poolApplying[id] == true)) return
        val fresh = MinerSettingsForm.from(m)
        val baseline = poolBaselines[id]
        if (!force && baseline != null && baseline.hasSamePoolSettings(fresh)) return
        poolBaselines[id] = fresh
        poolDrafts[id] = fresh
        poolErrors.remove(id)
    }

    fun syncPoolDrafts() {
        val ids = miners.map { it.id }.toSet()
        (poolDrafts.keys - ids).forEach { poolDrafts.remove(it); poolBaselines.remove(it) }
        miners.forEach { reloadPoolDraft(it.id, false) }
    }

    fun updatePoolDraft(id: String, form: MinerSettingsForm) {
        poolDrafts[id] = form
        poolErrors.remove(id)
    }

    fun applyPoolDrafts(ids: List<String>) {
        for (id in ids) {
            val draft = poolDrafts[id] ?: continue
            if (draft.poolValidationMessage != null) continue
            poolApplying[id] = true
            poolErrors.remove(id)
            viewModelScope.launch {
                try {
                    applyPoolSettings(draft, id)
                    poolApplying.remove(id)
                    reloadPoolDraft(id, true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: WriteDeclined) {
                    poolApplying.remove(id)
                } catch (e: Exception) {
                    poolApplying.remove(id)
                    poolErrors[id] = e.message ?: tr(R.string.transfer_failed)
                }
            }
        }
    }

    // endregion

    // region Privacy & language

    fun togglePrivacy() {
        Privacy.active = !Privacy.active
        prefs.edit().putBoolean(KEY_PRIVACY, Privacy.active).apply()
    }

    /**
     * From Android 13 the language is the system's per-app language (Settings → Apps → Language), so both
     * places stay in sync. Older versions keep it in the app's preferences.
     */
    private fun loadLanguage(): AppLanguage {
        val stored = runCatching { AppLanguage.valueOf(prefs.getString(KEY_LANGUAGE, null) ?: "") }.getOrDefault(AppLanguage.SYSTEM)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return stored
        val manager = getApplication<Application>().getSystemService(LocaleManager::class.java)
        val current = manager.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
        if (current == null && stored != AppLanguage.SYSTEM) {
            // One-time move of the old in-app setting to the system setting.
            manager.applicationLocales = LocaleList.forLanguageTags(if (stored == AppLanguage.GERMAN) "de" else "en")
            prefs.edit().remove(KEY_LANGUAGE).apply()
            return stored
        }
        return when (current) {
            null -> AppLanguage.SYSTEM
            "de" -> AppLanguage.GERMAN
            else -> AppLanguage.ENGLISH
        }
    }

    /** Picks up a language change made in the system settings (the activity is recreated, this view model is not). */
    fun syncLanguage() {
        language = loadLanguage()
        DisplayFormat.locale = java.util.Locale.getDefault()
        Lang.apply(language)
    }

    fun updateLanguage(setting: AppLanguage) {
        language = setting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the activity in the new language.
            getApplication<Application>().getSystemService(LocaleManager::class.java).applicationLocales = when (setting) {
                AppLanguage.SYSTEM -> LocaleList.getEmptyLocaleList()
                AppLanguage.GERMAN -> LocaleList.forLanguageTags("de")
                AppLanguage.ENGLISH -> LocaleList.forLanguageTags("en")
            }
        } else {
            Lang.apply(setting)
            prefs.edit().putString(KEY_LANGUAGE, setting.name).apply()
        }
    }

    // endregion

    // region Local network permission

    private fun checkLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_SDK ||
            getApplication<Application>().checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Re-checks the permission, e.g. after returning from the system settings. */
    fun refreshLocalNetworkPermission() {
        val was = localNetworkGranted
        localNetworkGranted = checkLocalNetworkPermission()
        if (!was && localNetworkGranted && inForeground) startPolling()
    }

    // endregion

    companion object {
        const val MAX_HISTORY = 240

        /** Saved miner list, also read by the background monitor. */
        fun readPoolLibrary(context: Context): List<PoolConfig> = runCatching {
            val prefs = context.applicationContext.getSharedPreferences("minerguru", Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_POOLS, null) ?: return emptyList())
            (0 until arr.length()).mapNotNull { PoolConfig.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun readPersistedMiners(context: Context): List<MinerDevice> =
            readPersistedMiners(context.applicationContext.getSharedPreferences("minerguru", Context.MODE_PRIVATE))

        private fun readPersistedMiners(prefs: android.content.SharedPreferences): List<MinerDevice> {
            val text = prefs.getString(KEY_MINERS, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(text)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    MinerDevice(
                        PersistedMinerConfig(
                            id = o.getString("id"),
                            ipAddress = o.getString("ipAddress"),
                            customName = o.optString("customName").takeIf { it.isNotEmpty() },
                            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                            cachedHostname = o.optString("cachedHostname").takeIf { it.isNotEmpty() },
                            cachedHardwareModel = o.optString("cachedHardwareModel").takeIf { it.isNotEmpty() }
                        ),
                        ConnectionState.Checking
                    )
                }
            }.getOrDefault(emptyList())
        }

        /** Language chosen in the app before Android 13 moved it to the system setting. */
        fun readStoredLanguage(context: Context): AppLanguage =
            runCatching {
                AppLanguage.valueOf(context.getSharedPreferences("minerguru", Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null) ?: "")
            }.getOrDefault(AppLanguage.SYSTEM)
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val LOCAL_NETWORK_PERMISSION_SDK = 37
        private const val KEY_MINERS = "miners.v1"
        private const val KEY_POLLING = "pollingIntervalSeconds"
        private const val KEY_TEMP = "tempWarningC"
        private const val KEY_AUTOSCAN = "autoScan"
        private const val KEY_STRATUM_PROBE = "stratumProbeEnabled.v1"
        private const val KEY_SUBNET = "subnetPrefix"
        private const val KEY_TIMEFRAME = "soloTimeframe"
        private const val KEY_POOLS = "poolLibrary.v1"
        private const val KEY_PRIVACY = "privacyMode"
        private const val KEY_LANGUAGE = "appLanguage"
    }
}
