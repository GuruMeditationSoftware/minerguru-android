package app.minerguru.model

import androidx.annotation.StringRes
import app.minerguru.R
import java.util.UUID

sealed interface ConnectionState {
    data object Unknown : ConnectionState
    data object Checking : ConnectionState
    data object Online : ConnectionState
    data class Offline(val reason: String) : ConnectionState

    val isOnline: Boolean get() = this is Online
}

data class TelemetrySample(
    val timestamp: Long = System.currentTimeMillis(),
    val hashRateGHs: Double,
    val asicTempC: Double,
    val vrTempC: Double? = null,
    val powerWatts: Double,
    val poolLatencyMs: Double? = null,
    val fanPercent: Double = 0.0
)

data class PoolLatencyMeasurement(
    /** Latency reported by the miner firmware itself. */
    val minerReportedMs: Double? = null,
    val primaryPoolDirectMs: Double? = null,
    val fallbackPoolDirectMs: Double? = null,
    val measuredAt: Long = System.currentTimeMillis()
) {
    fun effectiveLatencyMs(usingFallback: Boolean): Double? {
        if (minerReportedMs != null && minerReportedMs > 0) return minerReportedMs
        return if (usingFallback) fallbackPoolDirectMs ?: primaryPoolDirectMs else primaryPoolDirectMs
    }
}

data class MinerClockPreset(
    val name: String,
    val subtitle: String,
    val frequencyMHz: Int,
    val coreVoltageMV: Int
)

/** Persisted configuration entry for a miner. */
data class PersistedMinerConfig(
    val id: String = UUID.randomUUID().toString(),
    val ipAddress: String,
    val customName: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val cachedHostname: String? = null,
    val cachedHardwareModel: String? = null
)

/** Live runtime state and telemetry history of a miner. */
data class MinerDevice(
    val id: String,
    val ipAddress: String,
    val customName: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long? = null,
    val cachedHostname: String? = null,
    val cachedHardwareModel: String? = null,
    val connectionState: ConnectionState = ConnectionState.Unknown,
    val latestInfo: MinerSystemInfo? = null,
    val rawJSON: String? = null,
    val poolLatency: PoolLatencyMeasurement? = null,
    val history: List<TelemetrySample> = emptyList()
) {
    constructor(config: PersistedMinerConfig, connectionState: ConnectionState = ConnectionState.Unknown) : this(
        id = config.id,
        ipAddress = config.ipAddress,
        customName = config.customName,
        addedAt = config.addedAt,
        cachedHostname = config.cachedHostname,
        cachedHardwareModel = config.cachedHardwareModel,
        connectionState = connectionState
    )

    val persistedConfig: PersistedMinerConfig
        get() = PersistedMinerConfig(
            id = id,
            ipAddress = ipAddress,
            customName = customName,
            addedAt = addedAt,
            cachedHostname = latestInfo?.hostname ?: cachedHostname,
            cachedHardwareModel = latestInfo?.detectedHardwareTitle ?: cachedHardwareModel
        )

    val displayName: String
        get() {
            customName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            latestInfo?.hostname?.takeIf { it.isNotEmpty() }?.let { return it }
            cachedHostname?.takeIf { it.isNotEmpty() }?.let { return it }
            return ipAddress
        }

    val hardwareTitle: String
        get() = latestInfo?.detectedHardwareTitle ?: cachedHardwareModel ?: tr(R.string.unknown_miner)

    val requiresOtp: Boolean get() = latestInfo?.otp == true
    val isNerdOS: Boolean get() = latestInfo?.firmware == MinerFirmware.NERD_OS

    val isMultiChip: Boolean get() = (latestInfo?.asicCount ?: 1) > 1

    /** NerdOS reports its dual-pool settings in the `stratum` block; AxeOS has no dual-pool mode. */
    val supportsDualPool: Boolean get() = isNerdOS && latestInfo?.stratumDetail != null

    val primaryPoolEffectiveLatencyMs: Double?
        get() {
            latestInfo?.primaryPoolMinerLatencyMs?.takeIf { it > 0 }?.let { return it }
            poolLatency?.primaryPoolDirectMs?.takeIf { it > 0 }?.let { return it }
            val info = latestInfo
            if (info != null && !info.isUsingFallbackStratum) {
                info.minerReportedPoolLatencyMs?.takeIf { it > 0 }?.let { return it }
            }
            return null
        }

    val secondaryPoolEffectiveLatencyMs: Double?
        get() {
            latestInfo?.secondaryPoolMinerLatencyMs?.takeIf { it > 0 }?.let { return it }
            poolLatency?.fallbackPoolDirectMs?.takeIf { it > 0 }?.let { return it }
            val info = latestInfo
            if (info != null && info.isUsingFallbackStratum) {
                info.minerReportedPoolLatencyMs?.takeIf { it > 0 }?.let { return it }
            }
            return null
        }

    val activePoolLatencyMs: Double?
        get() {
            val info = latestInfo
            if (info != null && info.isDualPoolMining) {
                val p1 = primaryPoolEffectiveLatencyMs
                val p2 = secondaryPoolEffectiveLatencyMs
                if (p1 != null && p2 != null) {
                    return p1 * info.dualPoolPrimaryPercent / 100.0 + p2 * info.dualPoolSecondaryPercent / 100.0
                }
                return p2 ?: p1 ?: info.minerReportedPoolLatencyMs
            }
            poolLatency?.effectiveLatencyMs(info?.isUsingFallbackStratum ?: false)?.let { return it }
            return info?.minerReportedPoolLatencyMs
        }

    /**
     * Eco / standard / boost for this miner's chip, on the clock steps its firmware offers.
     * NerdOS reports its own factory clock, which becomes the standard profile.
     */
    val recommendedPresets: List<MinerClockPreset>
        get() {
            val info = latestInfo ?: return emptyList()
            val tuning = info.asicTuning ?: return emptyList()
            val nerdOS = info.firmware == MinerFirmware.NERD_OS
            return tuning.presets(
                if (nerdOS) info.defaultFrequencyMHz else null,
                if (nerdOS) info.defaultCoreVoltageMV else null
            ).map { p ->
                MinerClockPreset(
                    name = p.name,
                    subtitle = info.expectedHashrateGHs(p.frequencyMHz)?.let { "≈ " + MinerFormatting.formatHashrate(it) } ?: "",
                    frequencyMHz = p.frequencyMHz,
                    coreVoltageMV = p.voltageMV
                )
            }
        }

    /** Range of the frequency stepper: the chip's firmware steps, widened to the current and factory value. */
    val frequencyRange: IntRange
        get() = settingRange(latestInfo?.asicTuning?.frequencyOptions,
            listOf(latestInfo?.frequencyMHz, latestInfo?.defaultFrequencyMHz), MinerSettingsForm.frequencyRange)

    val coreVoltageRange: IntRange
        get() = settingRange(latestInfo?.asicTuning?.voltageOptions,
            listOf(latestInfo?.coreVoltageTargetMV, latestInfo?.defaultCoreVoltageMV), MinerSettingsForm.coreVoltageRange)

    private fun settingRange(options: List<Int>?, values: List<Int?>, fallback: IntRange): IntRange {
        if (options.isNullOrEmpty()) return fallback
        val known = options + values.filterNotNull().filter { it > 0 }
        return known.min()..known.max()
    }

    val poolLatencyDetailText: String
        get() {
            val info = latestInfo ?: return "—"
            if (info.isDualPoolMining) {
                val p1 = primaryPoolEffectiveLatencyMs?.let { MinerFormatting.format0(it) } ?: "—"
                val p2 = secondaryPoolEffectiveLatencyMs?.let { MinerFormatting.format0(it) } ?: "—"
                return "Dual (${info.dualPoolPrimaryPercent}%/${info.dualPoolSecondaryPercent}%) • P1: $p1 ms / P2: $p2 ms"
            }
            return "${LatencyQuality.evaluate(activePoolLatencyMs).label} • ${info.activePoolHost}"
        }

    val webInterfaceUrl: String get() = "http://$ipAddress"
}

data class PoolPreset(
    val name: String,
    val category: String,
    val host: String,
    val port: Int,
    val notes: String
) {
    companion object {
        val commonPools = listOf(
            PoolPreset("Public Pool (Solo)", "Solo Mining", "public-pool.io", 21496, "Beliebter Open-Source Solo-Pool für Bitaxe & NerdQAxe++"),
            PoolPreset("CKPool EU (Solo)", "Solo Mining", "eusolo.ckpool.org", 3333, "Europäischer CKPool Solo-Server mit niedriger Latenz in DE/AT/CH"),
            PoolPreset("CKPool Global (Solo)", "Solo Mining", "solo.ckpool.org", 3333, "Klassischer globaler CKPool Solo-Server"),
            PoolPreset("AtlasPool EU (Solo)", "Solo Mining", "solo.atlaspool.io", 3333, "Solo-Mining Pool mit niedriger Latenz"),
            PoolPreset("Braiins Pool", "FPPS / Pool", "stratum.braiins.com", 3333, "Regelmäßige Auszahlungen (Account-Name.Worker erforderlich)"),
            PoolPreset("OCEAN", "TIDES / Dezentral", "mine.ocean.xyz", 3334, "Transparenter, dezentraler Bitcoin-Pool")
        )
    }
}

enum class LatencyQuality(@StringRes private val labelRes: Int) {
    EXCELLENT(R.string.excellent), GOOD(R.string.good), FAIR(R.string.fair), POOR(R.string.high),
    UNAVAILABLE(R.string.no_data);

    val label: String get() = tr(labelRes)

    companion object {
        fun evaluate(ms: Double?): LatencyQuality = when {
            ms == null || ms <= 0 -> UNAVAILABLE
            ms < 45 -> EXCELLENT
            ms < 100 -> GOOD
            ms < 200 -> FAIR
            else -> POOR
        }
    }
}

/** Editable copy of a miner's settings and the `PATCH /api/system` payloads built from it. */
data class MinerSettingsForm(
    val frequencyMHz: Int = 525,
    val coreVoltageMV: Int = 1150,
    val autoFanSpeed: Boolean = true,
    val manualFanPercent: Float = 75f,
    val customAlias: String = "",
    val deviceHostname: String = "",
    val poolApi: PoolApi = PoolApi.LEGACY,
    val dualPool: Boolean = false,
    val poolBalance: Float = 50f,
    /** Two slots (primary, fallback) on older AxeOS and NerdOS, eight on AxeOS 2.15+. */
    val slots: List<PoolEndpoint> = listOf(PoolEndpoint(), PoolEndpoint()),
    val primarySlot: Int = 0,
    val secondarySlot: Int = 1,
    /** Slots that held a pool on the miner when the form was loaded (AxeOS 2.15+). */
    val storedSlots: Set<Int> = emptySet()
) {
    enum class PoolApi {
        /** `stratumURL` / `fallbackStratumURL` fields (AxeOS before 2.15, NerdOS). */
        LEGACY,
        /** `pools[]` with `primaryPoolIndex` / `secondaryPoolIndex` (AxeOS 2.15+). */
        SLOTS
    }

    data class PoolEndpoint(
        val host: String = "",
        val port: Int = 3333,
        val user: String = "",
        val password: String = "x",
        /** Slot as read from AxeOS 2.15+; its advanced fields are written back unchanged. */
        val advanced: AxeOSPool? = null
    ) {
        val hasContent: Boolean get() = host.isNotEmpty() || user.isNotEmpty()

        /** Host and user set – the minimum AxeOS accepts for a slot. */
        val isComplete: Boolean get() = StratumHost.clean(host).isNotEmpty() && user.trim().isNotEmpty()

        /** Slot payload for AxeOS 2.15+: the edited basics on top of the advanced settings read from the miner. */
        fun axePool(id: Int): AxeOSPool = (advanced ?: AxeOSPool(id, "", port, "")).copy(
            id = id,
            stratumURL = StratumHost.clean(host),
            stratumPort = port,
            stratumUser = user.trim(),
            stratumPassword = password.ifEmpty { "x" }
        )

        companion object {
            fun fromAxePool(p: AxeOSPool) = PoolEndpoint(
                StratumHost.clean(p.stratumURL), p.stratumPort, p.stratumUser,
                p.stratumPassword ?: AxeOSPool.MASKED_PASSWORD, p
            )
        }
    }

    val primary: PoolEndpoint get() = slots[primarySlot]
    val fallback: PoolEndpoint get() = slots[secondarySlot]

    fun withSlot(index: Int, endpoint: PoolEndpoint): MinerSettingsForm =
        if (index !in slots.indices) this else copy(slots = slots.toMutableList().also { it[index] = endpoint })

    fun withPrimary(endpoint: PoolEndpoint) = withSlot(primarySlot, endpoint)
    fun withFallback(endpoint: PoolEndpoint) = withSlot(secondarySlot, endpoint)

    /** Puts a library pool into a slot. Advanced AxeOS settings are kept only if the pool host stays the same. */
    fun assign(config: PoolConfig, index: Int): MinerSettingsForm {
        if (index !in slots.indices) return this
        var endpoint = config.endpoint
        if (StratumHost.clean(slots[index].host).lowercase() == config.host.lowercase()) {
            endpoint = endpoint.copy(advanced = slots[index].advanced)
        }
        return withSlot(index, endpoint)
    }

    /**
     * Makes [config] the primary pool. On AxeOS 2.15+ an existing slot with that pool is just selected,
     * otherwise the pool goes into a free slot so the previous pools stay stored on the miner.
     */
    fun makePrimary(config: PoolConfig): MinerSettingsForm {
        val target = slotIndex(config) ?: freeSlot ?: primarySlot
        var form = if (config.matches(slots[target])) this else assign(config, target)
        if (target == secondarySlot && poolApi == PoolApi.SLOTS) form = form.copy(secondarySlot = primarySlot)
        return form.copy(primarySlot = target)
    }

    /** Makes [config] the fallback (dual mode: pool 2), analogous to [makePrimary]. */
    fun makeFallback(config: PoolConfig): MinerSettingsForm {
        val target = slotIndex(config) ?: freeSlot ?: secondarySlot
        // Swapping roles only works if the current fallback can take over as primary.
        if (target == primarySlot && (poolApi == PoolApi.LEGACY || !slots[secondarySlot].hasContent)) return this
        var form = if (config.matches(slots[target])) this else assign(config, target)
        if (target == primarySlot) form = form.copy(primarySlot = secondarySlot)
        return form.copy(secondarySlot = target)
    }

    private fun slotIndex(config: PoolConfig): Int? =
        if (poolApi != PoolApi.SLOTS) null else slots.indexOfFirst { config.matches(it) }.takeIf { it >= 0 }

    private val freeSlot: Int?
        get() = if (poolApi != PoolApi.SLOTS) null
        else slots.indices.firstOrNull { !slots[it].hasContent && it != primarySlot && it != secondarySlot }

    /** Slots worth showing: filled ones, primary and fallback, plus one free slot to add a pool. */
    val visibleSlots: List<Int>
        get() {
            if (poolApi != PoolApi.SLOTS) return slots.indices.toList()
            val visible = slots.indices.filter { slots[it].hasContent || it == primarySlot || it == secondarySlot }.toMutableList()
            slots.indices.firstOrNull { it !in visible }?.let { visible += it }
            return visible.sorted()
        }

    fun clearSlot(index: Int) = withSlot(index, PoolEndpoint())

    /** Slots that were stored on the miner and have been emptied; AxeOS refuses to delete primary / fallback. */
    val slotsToClear: List<Int>
        get() = if (poolApi != PoolApi.SLOTS) emptyList()
        else storedSlots.filter { !slots[it].hasContent && it != primarySlot && it != secondarySlot }.sorted()

    /** Why the pool settings cannot be sent yet, or `null`. */
    /** A slot whose address does not fit its pool; sending stays possible. */
    val poolCoinWarning: String?
        get() = slots.indices.firstNotNullOfOrNull { i ->
            MinedCoin.mismatchWarning(slots[i].host, slots[i].user)?.let { "${slotTitle(i)}: $it" }
        }

    val poolValidationMessage: String?
        get() {
            if (StratumHost.clean(primary.host).isEmpty()) return tr(R.string.the_primary_pool_needs_a)
            slots.forEachIndexed { index, slot ->
                if (!slot.hasContent) return@forEachIndexed
                if (slot.port !in 1..65535) return "${slotTitle(index)}: " + tr(R.string.invalid_port) + " ${slot.port}."
                if (poolApi == PoolApi.SLOTS && !slot.isComplete) {
                    return "${slotTitle(index)}: " + tr(R.string.host_and_wallet_stratum_user)
                }
            }
            if (poolApi == PoolApi.SLOTS && !slots[secondarySlot].hasContent && secondarySlot != primarySlot && secondarySlot in storedSlots) {
                return tr(R.string.the_fallback_slot_is_empty)
            }
            return null
        }

    fun slotTitle(index: Int): String = when (poolApi) {
        PoolApi.SLOTS -> "Slot ${index + 1}"
        PoolApi.LEGACY -> if (index == 0) "Pool 1" else "Pool 2"
    }

    /** Hashrate share of pool 1 / pool 2 in dual-pool mode (1…99 %). */
    val primaryPercent: Int get() = poolBalance.toInt().coerceIn(1, 99)
    val secondaryPercent: Int get() = 100 - primaryPercent
    val trimmedHostname: String get() = deviceHostname.trim()

    fun isCurrent(p: MinerClockPreset) = frequencyMHz == p.frequencyMHz && coreVoltageMV == p.coreVoltageMV
    fun apply(p: MinerClockPreset) = copy(frequencyMHz = p.frequencyMHz, coreVoltageMV = p.coreVoltageMV)

    /** Clock, voltage and fan. Keeps the current auto-fan mode (e.g. NerdQAxe PID = 2) when auto stays on. */
    fun performancePayload(existingAutoFanMode: Int?) = MinerSystemUpdatePayload(
        frequency = frequencyMHz,
        coreVoltage = coreVoltageMV,
        overclockEnabled = 1,
        autofanspeed = if (autoFanSpeed) maxOf(1, existingAutoFanMode ?: 1) else 0,
        fanspeed = if (autoFanSpeed) null else manualFanPercent.toInt()
    )

    /**
     * Pool configuration. Dual-pool fields only for supporting firmware; an empty fallback is left untouched.
     * AxeOS 2.15+ gets all complete slots.
     */
    fun poolPayload(supportsDualPool: Boolean): MinerSystemUpdatePayload {
        if (poolApi == PoolApi.SLOTS) {
            return MinerSystemUpdatePayload(
                pools = slots.mapIndexedNotNull { i, slot -> if (slot.isComplete) slot.axePool(i) else null },
                primaryPoolIndex = primarySlot,
                secondaryPoolIndex = secondarySlot
            )
        }
        val fallbackHost = StratumHost.clean(fallback.host)
        val fallbackUser = fallback.user.trim()
        return MinerSystemUpdatePayload(
            poolMode = if (supportsDualPool) (if (dualPool) 1 else 0) else null,
            poolBalance = if (supportsDualPool && dualPool) primaryPercent else null,
            stratumURL = StratumHost.clean(primary.host),
            stratumPort = primary.port,
            stratumUser = primary.user.trim(),
            stratumPassword = primary.password.ifEmpty { null },
            fallbackStratumURL = fallbackHost.ifEmpty { null },
            fallbackStratumPort = if (fallbackHost.isEmpty()) null else fallback.port,
            fallbackStratumUser = fallbackUser.ifEmpty { null },
            fallbackStratumPassword = fallback.password.ifEmpty { null }
        )
    }

    /** Pool-related part of the form, to tell whether the user changed anything. */
    fun hasSamePoolSettings(other: MinerSettingsForm): Boolean =
        poolApi == other.poolApi && dualPool == other.dualPool && primaryPercent == other.primaryPercent &&
            slots == other.slots && primarySlot == other.primarySlot && secondarySlot == other.secondarySlot

    val hostnamePayload get() = MinerSystemUpdatePayload(hostname = trimmedHostname)

    companion object {
        val frequencyRange = 350..800
        val coreVoltageRange = 1000..1400
        val manualFanRange = 20f..100f

        fun from(miner: MinerDevice): MinerSettingsForm {
            val base = MinerSettingsForm(customAlias = miner.customName ?: "")
            val info = miner.latestInfo ?: return base
            var form = base.copy(
                frequencyMHz = info.frequencyMHz,
                coreVoltageMV = info.coreVoltageTargetMV,
                autoFanSpeed = info.autoFanSpeed,
                manualFanPercent = (if (info.fanSpeedPercent > 0) info.fanSpeedPercent.toFloat() else 75f).coerceIn(manualFanRange),
                deviceHostname = info.hostname,
                dualPool = info.isDualPoolMining || info.stratumDetail?.poolMode == 1,
                poolBalance = info.dualPoolPrimaryPercent.toFloat()
            )
            val axePools = info.axePools
            form = if (axePools != null) {
                val range = 0 until AxeOSPool.SLOT_COUNT
                form.copy(
                    poolApi = PoolApi.SLOTS,
                    slots = range.map { i -> axePools.firstOrNull { it.id == i }?.let { PoolEndpoint.fromAxePool(it) } ?: PoolEndpoint() },
                    primarySlot = (info.primaryPoolIndex ?: 0).coerceIn(range),
                    secondarySlot = (info.secondaryPoolIndex ?: 1).coerceIn(range),
                    storedSlots = axePools.map { it.id }.filter { it in range }.toSet()
                )
            } else {
                // The firmware does not report passwords; an empty one is not sent and stays unchanged on the miner.
                form.copy(
                    slots = listOf(
                        PoolEndpoint(StratumHost.clean(info.stratumURL), info.stratumPort, info.stratumUser, ""),
                        PoolEndpoint(StratumHost.clean(info.fallbackStratumURL), info.fallbackStratumPort, info.fallbackStratumUser, "")
                    )
                )
            }
            return form
        }
    }
}
