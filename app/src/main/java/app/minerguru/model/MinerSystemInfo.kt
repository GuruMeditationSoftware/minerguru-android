package app.minerguru.model

import app.minerguru.R
import org.json.JSONArray
import org.json.JSONObject

/** Detailed per-pool status from `stratum.pools[]` (NerdOS and newer AxeOS). */
data class StratumPoolStatus(
    val active: Boolean? = null,
    val connected: Boolean? = null,
    val poolDifficulty: Double? = null,
    val networkDifficulty: Double? = null,
    val poolDiffErr: Boolean? = null,
    val accepted: Int? = null,
    val rejected: Int? = null,
    val pingRtt: Double? = null,
    val pingLoss: Double? = null,
    val bestDiff: Double? = null,
    val responseTime: Double? = null
) {
    /** `pingRtt` on NerdQAxe++ or `responseTime` on AxeOS. */
    val effectiveLatencyMs: Double?
        get() = pingRtt?.takeIf { it > 0 } ?: responseTime?.takeIf { it > 0 }
}

data class StratumDetail(
    /** 0 = Primary/Fallback failover, 1 = simultaneous dual-pool mining. */
    val poolMode: Int? = null,
    val activePoolMode: Int? = null,
    /** Percentage of hashrate for pool 1 in dual-pool mode. */
    val poolBalance: Int? = null,
    val usingFallback: Boolean? = null,
    val totalBestDiff: Double? = null,
    val pools: List<StratumPoolStatus>? = null
)

data class ShareRejectionReason(val message: String, val count: Int)

/** Unified telemetry decoded from `GET /api/system/info` (Bitaxe AxeOS and NerdQAxe++ NerdOS). */
data class MinerSystemInfo(
    val hashRate: Double = 0.0,
    val hashRate1m: Double? = null,
    val hashRate10m: Double? = null,
    val hashRate1h: Double? = null,
    val hashRate1d: Double? = null,
    val expectedHashrate: Double? = null,

    val temp: Double = 0.0,
    val vrTemp: Double? = null,
    val fanSpeedPercent: Double = 0.0,
    val fanRPM: Int = 0,
    val fan2SpeedPercent: Double? = null,
    val fan2RPM: Int? = null,
    val autoFanSpeed: Boolean = true,
    val autoFanModeRaw: Int = 1,
    val targetTemp: Double? = null,
    val overheatMode: Boolean = false,
    /** NerdOS: the limit the firmware protects itself at; it has no overheat flag. */
    val overheatTemp: Double? = null,
    /** AxeOS 2.15+: paused on purpose. */
    val miningPaused: Boolean = false,
    /** What the pool really set; [stratumSuggestedDifficulty] is only what the miner asked for. */
    val reportedPoolDifficulty: Double? = null,
    /** NerdOS: settings can only be changed with a one-time code. */
    val otp: Boolean = false,

    val power: Double = 0.0,
    val voltageVolts: Double = 0.0,
    val currentAmps: Double = 0.0,
    val coreVoltageTargetMV: Int = 1200,
    val coreVoltageActualMV: Int = 1200,
    val frequencyMHz: Int = 525,
    val overclockEnabled: Boolean = false,

    val sharesAccepted: Int = 0,
    val sharesRejected: Int = 0,
    val sharesRejectedReasons: List<ShareRejectionReason> = emptyList(),
    /** Share of hashes the chips got wrong, as AxeOS reports it. */
    val errorPercentage: Double? = null,
    val bestDiffFormatted: String = "0",
    val bestDiffNumeric: Double = 0.0,
    val bestSessionDiffFormatted: String = "0",
    val bestSessionDiffNumeric: Double = 0.0,
    val networkDifficulty: Double? = null,

    val stratumURL: String = "",
    val stratumPort: Int = 3333,
    val stratumUser: String = "",
    val stratumSuggestedDifficulty: Double? = null,
    val fallbackStratumURL: String = "",
    val fallbackStratumPort: Int = 3333,
    val fallbackStratumUser: String = "",
    val isUsingFallbackStratum: Boolean = false,
    val minerReportedPoolLatencyMs: Double? = null,
    val stratumDetail: StratumDetail? = null,

    val deviceModel: String? = null,
    val asicModel: String = "BM1370",
    val boardVersion: String? = null,
    val asicCount: Int = 1,
    val smallCoreCount: Int? = null,
    val hostname: String = "miner",
    val macAddress: String? = null,
    val ssid: String? = null,
    val wifiStatus: String? = null,
    val wifiRSSI: Int? = null,
    val firmwareVersion: String = "",
    val axeOSVersion: String? = null,
    val idfVersion: String? = null,
    val uptimeSeconds: Int = 0,
    /** Why the miner last started, in the firmware's words (`resetReason`, NerdOS `lastResetReason`). */
    val resetReason: String? = null,
    val freeHeap: Int? = null,

    /** Configured pool slots on AxeOS 2.15+ (`pools[]`), `null` on older firmware and NerdOS. */
    val axePools: List<AxeOSPool>? = null,
    val primaryPoolIndex: Int? = null,
    val secondaryPoolIndex: Int? = null,
    /** Factory clock reported by NerdOS (`defaultFrequency` / `defaultCoreVoltage`). */
    val defaultFrequencyMHz: Int? = null,
    val defaultCoreVoltageMV: Int? = null,

    /** Coinbase decoding and TLS of the primary / fallback pool (AxeOS 2.13+). */
    val stratumDecodeCoinbase: Boolean? = null,
    val fallbackStratumDecodeCoinbase: Boolean? = null,
    val stratumTLS: Boolean? = null,
    val fallbackStratumTLS: Boolean? = null,
    /** Block the miner hashes on, from the coinbase the firmware decoded (AxeOS 2.13+). */
    val blockHeight: Int? = null,
    val blockScriptSig: String? = null,
    val blockSignals: List<String>? = null,
    val coinbaseOutputs: List<CoinbaseOutput>? = null,
    val coinbaseOthersCount: Int? = null,
    val coinbaseOthersValueSats: Double? = null,
    val coinbaseValueTotalSats: Double? = null,
    val coinbaseValueUserSats: Double? = null,
    /** `verified`, `not_found` or `not_applicable` (AxeOS 2.16+). */
    val coinbasePayoutStatus: String? = null
) {
    // region Computed metrics

    /** Energy efficiency in J/TH. */
    val efficiencyJPerTH: Double
        get() {
            val ths = hashRate / 1000.0
            return if (ths > 0.01 && power > 0) power / ths else 0.0
        }

    val shareRejectionRatePercent: Double
        get() {
            val total = sharesAccepted + sharesRejected
            return if (total > 0) sharesRejected.toDouble() / total * 100.0 else 0.0
        }

    /** `stratumDecodeCoinbase` of the slot that delivers the current job; the firmware default is on. */
    val activePoolDecodesCoinbase: Boolean?
        get() {
            val slotIndex = if (isUsingFallbackStratum) secondaryPoolIndex else primaryPoolIndex
            val slot = slotIndex?.let { i -> axePools?.firstOrNull { it.id == i } }
            slot?.advanced?.get("stratumDecodeCoinbase")?.let { v ->
                when (v) {
                    is Boolean -> return v
                    is Number -> return v.toInt() != 0
                }
            }
            return if (isUsingFallbackStratum) fallbackStratumDecodeCoinbase else stratumDecodeCoinbase
        }

    val isDualPoolMining: Boolean
        get() {
            val detail = stratumDetail ?: return false
            if (detail.activePoolMode == 1 || detail.poolMode == 1) return true
            val pools = detail.pools
            return pools != null && pools.size >= 2 &&
                pools[0].connected == true && pools[1].connected == true && detail.usingFallback != true
        }

    val dualPoolPrimaryPercent: Int get() = (stratumDetail?.poolBalance ?: 50).coerceIn(1, 99)
    val dualPoolSecondaryPercent: Int get() = 100 - dualPoolPrimaryPercent

    val primaryPoolStatus: StratumPoolStatus? get() = stratumDetail?.pools?.firstOrNull()
    val secondaryPoolStatus: StratumPoolStatus? get() = stratumDetail?.pools?.getOrNull(1)

    val primaryPoolMinerLatencyMs: Double?
        get() = primaryPoolStatus?.effectiveLatencyMs
            ?: minerReportedPoolLatencyMs?.takeIf { !isUsingFallbackStratum && it > 0 }

    val secondaryPoolMinerLatencyMs: Double?
        get() = secondaryPoolStatus?.effectiveLatencyMs
            ?: minerReportedPoolLatencyMs?.takeIf { isUsingFallbackStratum && it > 0 }

    val primaryPoolEndpoint: String
        get() = if (stratumURL.isEmpty()) tr(R.string.not_configured) else "${StratumHost.clean(stratumURL, true)}:$stratumPort"

    val fallbackPoolEndpoint: String
        get() = if (fallbackStratumURL.isEmpty()) tr(R.string.not_configured)
        else "${StratumHost.clean(fallbackStratumURL, true)}:$fallbackStratumPort"

    val activePoolEndpoint: String
        get() {
            if (isDualPoolMining) {
                val h1 = StratumHost.clean(stratumURL, true)
                val h2 = StratumHost.clean(fallbackStratumURL, true)
                return "$h1 ($dualPoolPrimaryPercent%) + $h2 ($dualPoolSecondaryPercent%)"
            }
            if (isUsingFallbackStratum && fallbackStratumURL.isNotEmpty()) return fallbackPoolEndpoint
            return primaryPoolEndpoint
        }

    val activePoolHost: String
        get() {
            if (isDualPoolMining) {
                return "${StratumHost.clean(stratumURL, true)} + ${StratumHost.clean(fallbackStratumURL, true)}"
            }
            if (isUsingFallbackStratum && fallbackStratumURL.isNotEmpty()) return StratumHost.clean(fallbackStratumURL, true)
            return StratumHost.clean(stratumURL, true)
        }

    val activePoolUser: String
        get() {
            if (isDualPoolMining) {
                val w1 = extractWorker(stratumUser) ?: stratumUser
                val w2 = extractWorker(fallbackStratumUser) ?: fallbackStratumUser
                return if (w1 == w2) stratumUser else "$w1 / $w2"
            }
            if (isUsingFallbackStratum && fallbackStratumUser.isNotEmpty()) return fallbackStratumUser
            return stratumUser
        }

    val activeWorkerName: String?
        get() = extractWorker(if (isUsingFallbackStratum) fallbackStratumUser else stratumUser)

    fun extractWorker(user: String): String? {
        val dot = user.lastIndexOf('.')
        return if (dot in 0 until user.length - 1) user.substring(dot + 1) else null
    }

    val primaryPoolCoin: MinedCoin
        get() = MinedCoin.detect(
            StratumHost.clean(stratumURL, true), stratumUser,
            primaryPoolStatus?.networkDifficulty ?: networkDifficulty.takeIf { !isUsingFallbackStratum }
        )

    val secondaryPoolCoin: MinedCoin
        get() = MinedCoin.detect(
            StratumHost.clean(fallbackStratumURL, true), fallbackStratumUser,
            secondaryPoolStatus?.networkDifficulty ?: networkDifficulty.takeIf { isUsingFallbackStratum }
        )

    val activePoolCoin: MinedCoin
        get() {
            if (isDualPoolMining) {
                return if (dualPoolSecondaryPercent > dualPoolPrimaryPercent) secondaryPoolCoin else primaryPoolCoin
            }
            return if (isUsingFallbackStratum) secondaryPoolCoin else primaryPoolCoin
        }

    val activeCoinsSummary: String
        get() {
            if (isDualPoolMining) {
                val c1 = primaryPoolCoin
                val c2 = secondaryPoolCoin
                if (c1 == c2) return "${c1.symbol} (Dual $dualPoolPrimaryPercent%/$dualPoolSecondaryPercent%)"
                return "${c1.symbol} ($dualPoolPrimaryPercent%) + ${c2.symbol} ($dualPoolSecondaryPercent%)"
            }
            return activePoolCoin.symbol
        }

    val primaryPoolNetworkDiffFormatted: String?
        get() = (primaryPoolStatus?.networkDifficulty ?: networkDifficulty.takeIf { !isUsingFallbackStratum })
            ?.takeIf { it > 0 }?.let { MinerFormatting.formatDifficulty(it) }

    val secondaryPoolNetworkDiffFormatted: String?
        get() = (secondaryPoolStatus?.networkDifficulty ?: networkDifficulty.takeIf { isUsingFallbackStratum })
            ?.takeIf { it > 0 }?.let { MinerFormatting.formatDifficulty(it) }

    val activePoolDifficulty: Double?
        get() {
            if (isDualPoolMining) {
                val d1 = primaryPoolStatus?.poolDifficulty
                val d2 = secondaryPoolStatus?.poolDifficulty
                return if (dualPoolSecondaryPercent >= dualPoolPrimaryPercent) d2 ?: d1 else d1 ?: d2
            }
            stratumDetail?.pools?.let { pools ->
                pools.firstOrNull { it.active == true }?.poolDifficulty?.takeIf { it > 0 }?.let { return it }
                pools.firstOrNull()?.poolDifficulty?.takeIf { it > 0 }?.let { return it }
            }
            return reportedPoolDifficulty?.takeIf { it > 0 } ?: stratumSuggestedDifficulty
        }

    // region Hardware

    val firmware: MinerFirmware
        get() = when {
            !boardVersion.isNullOrEmpty() -> MinerFirmware.AXE_OS
            !deviceModel.isNullOrEmpty() || stratumDetail != null -> MinerFirmware.NERD_OS
            else -> MinerFirmware.AXE_OS
        }

    val bitaxeFamily: BitaxeFamily?
        get() = if (firmware == MinerFirmware.AXE_OS) boardVersion?.let { BitaxeFamily.forBoard(it) } else null

    /** `"Bitaxe Gamma 601"`, `"Bitaxe Hex 302"`, `"NerdQAxe++"`, `"NerdOCTAXE-γ"` … */
    val detectedHardwareTitle: String
        get() {
            if (firmware == MinerFirmware.NERD_OS) {
                val model = deviceModel?.trim().orEmpty()
                if (model.isNotEmpty()) {
                    val known = NerdOSModel.forDeviceModel(model)?.name.orEmpty()
                    return known.ifEmpty { model }
                }
                return "NerdOS (${asicCount}x $asicModel)"
            }
            val board = boardVersion?.trim().orEmpty()
            bitaxeFamily?.let { return if (board.isEmpty()) it.name else "${it.name} $board" }
            // Very old AxeOS reported the family in `deviceModel` instead of a known board version.
            val model = deviceModel?.trim().orEmpty()
            if (model.isNotEmpty()) return "Bitaxe " + model.replaceFirstChar { it.uppercase() }
            return if (board.isEmpty()) "Bitaxe ($asicModel)" else "Bitaxe ($asicModel v$board)"
        }

    /** Clock steps of this miner; `null` for unknown ASICs. */
    val asicTuning: AsicTuning? get() = bitaxeFamily?.tuning ?: AsicTuning.forAsic(asicModel)

    /** Expected hashrate in GH/s at a clock: frequency × small cores × chips (as ESP-Miner computes it). */
    fun expectedHashrateGHs(frequencyMHz: Int): Double? {
        val cores = smallCoreCount ?: return null
        if (cores <= 0) return null
        return frequencyMHz.toDouble() * cores * asicCount / 1000.0
    }

    // endregion

    // region Session best difficulty

    val sessionBestDiff: Double? get() = bestSessionDiffNumeric.takeIf { it > 0 }

    val primaryPoolSessionBestDiff: Double?
        get() = primaryPoolStatus?.bestDiff?.takeIf { it > 0 }
            ?: sessionBestDiff.takeIf { !isDualPoolMining && !isUsingFallbackStratum }

    val secondaryPoolSessionBestDiff: Double?
        get() = secondaryPoolStatus?.bestDiff?.takeIf { it > 0 }
            ?: sessionBestDiff.takeIf { !isDualPoolMining && isUsingFallbackStratum }

    val sessionBestDiffText: String get() = MinerFormatting.formatSessionBest(sessionBestDiff)

    // endregion

    // region Display texts for KPI cards

    val hashrateAveragesText: String
        get() = when {
            hashRate1m != null && hashRate1h != null ->
                "1m: ${MinerFormatting.formatHashrate(hashRate1m)} • 1h: ${MinerFormatting.formatHashrate(hashRate1h)}"
            hashRate1m != null -> "1m Ø: ${MinerFormatting.formatHashrate(hashRate1m)}"
            else -> "${MinerFormatting.format0(hashRate)} GH/s Live"
        }

    val fanModeLabel: String
        get() = if (autoFanModeRaw == 2) "PID Auto" else if (autoFanSpeed) tr(R.string.auto_mode) else tr(R.string.manual)

    private val hasSecondFan: Boolean get() = (fan2RPM ?: 0) > 0

    val fanPercentText: String
        get() = if (hasSecondFan && fan2SpeedPercent != null)
            "${MinerFormatting.format0(fanSpeedPercent)} / ${MinerFormatting.format0(fan2SpeedPercent)}"
        else MinerFormatting.format0(fanSpeedPercent)

    val fanRPMText: String
        get() = if (hasSecondFan && fan2RPM != null) "$fanRPM & $fan2RPM RPM • $fanModeLabel" else "$fanRPM RPM • $fanModeLabel"

    fun coolingText(warningThresholdC: Double): String =
        if (vrTemp != null) tr(R.string.voltage_regulator_vr) + ": ${MinerFormatting.format1(vrTemp)} °C"
        else if (temp >= warningThresholdC) tr(R.string.cooling_elevated) else tr(R.string.cooling_optimal)

    val powerDetailText: String
        get() = "${MinerFormatting.format2(voltageVolts)} V • ${MinerFormatting.format2(currentAmps)} A"

    val dailyEnergyText: String
        get() = "~${MinerFormatting.format2(power * 24.0 / 1000.0)} kWh / 24h"

    val coreVoltageText: String
        get() = tr(R.string.target) + ": $coreVoltageTargetMV mV • " + tr(R.string.actual) + ": $coreVoltageActualMV mV"

    val sharesText: String
        get() = "Shares: $sharesAccepted ok / $sharesRejected rej (${MinerFormatting.format2(shareRejectionRatePercent)}%)"

    // endregion

    companion object {
        /** Lenient parser for AxeOS / NerdOS payloads: fields may be missing, renamed or sent as strings. */
        fun fromJson(json: JSONObject): MinerSystemInfo {
            val rawVr = json.dbl("vrTemp")
            val rawFan2 = json.int("fanrpm2") ?: json.int("fan2rpm")

            val autoFanRawInt = json.int("autofanspeed")
            val autoFanModeRaw = autoFanRawInt ?: if (json.bool("autofanspeed") == false) 0 else 1

            val rawVoltage = json.dbl("voltage") ?: 0.0
            val voltageVolts = if (rawVoltage > 100) rawVoltage / 1000.0 else rawVoltage

            val currentA = json.dbl("currentA")
            val currentAmps = if (currentA != null && currentA > 0) currentA else {
                val raw = json.dbl("current") ?: 0.0
                if (raw > 100) raw / 1000.0 else raw
            }

            val coreTarget = json.int("coreVoltage") ?: 1200
            val bestPair = decodeDifficulty(json, "bestDiff", "bestDifficulty")
            val sessionPair = decodeDifficulty(json, "bestSessionDiff", "bestSessionDifficulty")

            val reasons = json.optJSONArray("sharesRejectedReasons")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o ->
                        ShareRejectionReason(o.optString("message", ""), o.optInt("count", 0))
                    }
                }
            } ?: emptyList()

            val stratum = json.optJSONObject("stratum")?.let { parseStratumDetail(it) }
            val topLevelFallback = json.bool("isUsingFallbackStratum")
            val usingFallback = topLevelFallback ?: stratum?.usingFallback ?: false

            val latency: Double? = run {
                json.dbl("responseTime")?.takeIf { it > 0 }?.let { return@run it }
                json.dbl("lastpingrtt")?.takeIf { it > 0 }?.let { return@run it }
                val pools = stratum?.pools ?: return@run null
                pools.firstOrNull { it.active == true }?.effectiveLatencyMs?.let { return@run it }
                pools.firstOrNull { it.connected == true }?.effectiveLatencyMs?.let { return@run it }
                pools.firstOrNull()?.effectiveLatencyMs
            }

            val deviceModel = json.str("deviceModel")
            // AxeOS <= 2.9 and NerdOS report `asicCount`; AxeOS 2.12+ lists one entry per chip in
            // `hashrateMonitor.asics`; otherwise the board table knows the count.
            val rawAsicCount = json.int("asicCount")
            val monitorAsics = json.optJSONObject("hashrateMonitor")?.optJSONArray("asics")?.length()
            val boardVersion = json.str("boardVersion")
            val asicCount = when {
                rawAsicCount != null && rawAsicCount > 0 -> rawAsicCount
                monitorAsics != null && monitorAsics > 0 -> monitorAsics
                boardVersion != null && BitaxeFamily.forBoard(boardVersion) != null -> BitaxeFamily.forBoard(boardVersion)!!.asicCount
                deviceModel != null && NerdOSModel.forDeviceModel(deviceModel) != null -> NerdOSModel.forDeviceModel(deviceModel)!!.asicCount
                else -> 1
            }

            return MinerSystemInfo(
                hashRate = json.dbl("hashRate") ?: 0.0,
                hashRate1m = json.dbl("hashRate_1m"),
                hashRate10m = json.dbl("hashRate_10m"),
                hashRate1h = json.dbl("hashRate_1h"),
                hashRate1d = json.dbl("hashRate_1d"),
                expectedHashrate = json.dbl("expectedHashrate"),
                temp = json.dbl("temp") ?: 0.0,
                vrTemp = if ((rawVr ?: 0.0) > 0) rawVr else null,
                fanSpeedPercent = json.dbl("fanspeed") ?: 0.0,
                fanRPM = json.int("fanrpm") ?: 0,
                fan2SpeedPercent = json.dbl("fanspeed2"),
                fan2RPM = if ((rawFan2 ?: 0) > 0) rawFan2 else null,
                autoFanSpeed = autoFanModeRaw != 0,
                autoFanModeRaw = autoFanModeRaw,
                targetTemp = json.dbl("temptarget") ?: json.dbl("pidTargetTemp"),
                overheatMode = json.bool("overheat_mode") ?: false,
                overheatTemp = json.dbl("overheat_temp"),
                miningPaused = json.bool("miningPaused") ?: false,
                reportedPoolDifficulty = json.dbl("poolDifficulty"),
                otp = json.bool("otp") ?: false,
                power = json.dbl("power") ?: 0.0,
                voltageVolts = voltageVolts,
                currentAmps = currentAmps,
                coreVoltageTargetMV = coreTarget,
                coreVoltageActualMV = json.int("coreVoltageActual") ?: coreTarget,
                frequencyMHz = json.int("frequency") ?: json.int("actualFrequency") ?: 525,
                overclockEnabled = json.bool("overclockEnabled") ?: false,
                sharesAccepted = json.int("sharesAccepted") ?: 0,
                errorPercentage = json.dbl("errorPercentage"),
                sharesRejected = json.int("sharesRejected") ?: 0,
                sharesRejectedReasons = reasons,
                bestDiffFormatted = bestPair.first,
                bestDiffNumeric = bestPair.second,
                bestSessionDiffFormatted = sessionPair.first,
                bestSessionDiffNumeric = sessionPair.second,
                networkDifficulty = json.dbl("networkDifficulty"),
                stratumURL = json.str("stratumURL") ?: "",
                stratumPort = json.int("stratumPort") ?: 3333,
                stratumUser = json.str("stratumUser") ?: "",
                stratumSuggestedDifficulty = json.dbl("stratumSuggestedDifficulty") ?: json.dbl("poolDifficulty"),
                fallbackStratumURL = json.str("fallbackStratumURL") ?: "",
                fallbackStratumPort = json.int("fallbackStratumPort") ?: 3333,
                fallbackStratumUser = json.str("fallbackStratumUser") ?: "",
                isUsingFallbackStratum = usingFallback,
                minerReportedPoolLatencyMs = latency,
                stratumDetail = stratum,
                deviceModel = deviceModel,
                asicModel = json.str("ASICModel") ?: json.str("asicModel") ?: "BM1370",
                boardVersion = boardVersion,
                asicCount = asicCount,
                smallCoreCount = json.int("smallCoreCount"),
                hostname = json.str("hostname") ?: "miner",
                macAddress = json.str("macAddr"),
                ssid = json.str("ssid"),
                wifiStatus = json.str("wifiStatus"),
                wifiRSSI = json.int("wifiRSSI") ?: json.int("rssi"),
                firmwareVersion = json.str("version") ?: "",
                axeOSVersion = json.str("axeOSVersion"),
                idfVersion = json.str("idfVersion"),
                uptimeSeconds = json.int("uptimeSeconds") ?: json.int("uptime") ?: 0,
                resetReason = json.optString("resetReason").ifEmpty { json.optString("lastResetReason") }.ifEmpty { null },
                freeHeap = json.int("freeHeap"),
                axePools = AxeOSPool.listFrom(json.optJSONArray("pools")),
                primaryPoolIndex = json.int("primaryPoolIndex"),
                secondaryPoolIndex = json.int("secondaryPoolIndex"),
                defaultFrequencyMHz = json.int("defaultFrequency"),
                defaultCoreVoltageMV = json.int("defaultCoreVoltage"),
                stratumDecodeCoinbase = json.bool("stratumDecodeCoinbase"),
                fallbackStratumDecodeCoinbase = json.bool("fallbackStratumDecodeCoinbase"),
                stratumTLS = json.bool("stratumTLS"),
                fallbackStratumTLS = json.bool("fallbackStratumTLS"),
                blockHeight = json.int("blockHeight"),
                blockScriptSig = json.str("scriptsig"),
                blockSignals = json.optJSONArray("blockSignals")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } },
                coinbaseOutputs = CoinbaseOutput.listFrom(json.optJSONArray("coinbaseOutputs")),
                coinbaseOthersCount = json.int("coinbaseOthersCount"),
                coinbaseOthersValueSats = json.dbl("coinbaseOthersValueSatoshis"),
                coinbaseValueTotalSats = json.dbl("coinbaseValueTotalSatoshis"),
                coinbaseValueUserSats = json.dbl("coinbaseValueUserSatoshis"),
                coinbasePayoutStatus = json.str("coinbasePayoutStatus")
            )
        }

        fun parse(text: String): MinerSystemInfo = fromJson(JSONObject(text))

        private fun parseStratumDetail(o: JSONObject): StratumDetail {
            val pools = o.optJSONArray("pools")?.let { arr: JSONArray ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { p ->
                        StratumPoolStatus(
                            active = p.bool("active"),
                            connected = p.bool("connected"),
                            poolDifficulty = p.dbl("poolDifficulty"),
                            networkDifficulty = p.dbl("networkDifficulty"),
                            poolDiffErr = p.bool("poolDiffErr"),
                            accepted = p.int("accepted"),
                            rejected = p.int("rejected"),
                            pingRtt = p.dbl("pingRtt"),
                            pingLoss = p.dbl("pingLoss"),
                            bestDiff = p.dbl("bestDiff"),
                            responseTime = p.dbl("responseTime")
                        )
                    }
                }
            }
            return StratumDetail(
                poolMode = o.int("poolMode"),
                activePoolMode = o.int("activePoolMode"),
                poolBalance = o.int("poolBalance"),
                usingFallback = o.bool("usingFallback"),
                totalBestDiff = o.dbl("totalBestDiff"),
                pools = pools
            )
        }

        private fun decodeDifficulty(json: JSONObject, primary: String, secondary: String): Pair<String, Double> {
            for (key in listOf(primary, secondary)) {
                if (!json.has(key) || json.isNull(key)) continue
                when (val v = json.get(key)) {
                    is Number -> return MinerFormatting.formatDifficulty(v.toDouble()) to v.toDouble()
                    is String -> if (v.isNotEmpty()) return v.trim() to MinerFormatting.parseDifficultyString(v)
                }
            }
            return "0" to 0.0
        }
    }
}

// region Lenient JSON accessors

internal fun JSONObject.dbl(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val v = get(key)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
}

internal fun JSONObject.int(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val v = get(key)) {
        is Int -> v
        is Number -> Math.round(v.toDouble()).toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }
}

internal fun JSONObject.bool(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val v = get(key)) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> when (v.lowercase().trim()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
        else -> null
    }
}

internal fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return get(key) as? String
}

// endregion

/** Payload sent via `PATCH /api/system`; only non-null fields are serialized. */
data class MinerSystemUpdatePayload(
    val hostname: String? = null,
    val frequency: Int? = null,
    val coreVoltage: Int? = null,
    val overclockEnabled: Int? = null,
    val autofanspeed: Int? = null,
    val fanspeed: Int? = null,
    val manualFanSpeed: Int? = null,
    val temptarget: Int? = null,
    val pidTargetTemp: Int? = null,
    val poolMode: Int? = null,
    val poolBalance: Int? = null,
    val stratumURL: String? = null,
    val stratumPort: Int? = null,
    val stratumUser: String? = null,
    val stratumPassword: String? = null,
    val fallbackStratumURL: String? = null,
    val fallbackStratumPort: Int? = null,
    val fallbackStratumUser: String? = null,
    val fallbackStratumPassword: String? = null,
    /** AxeOS 2.15+: slots to write (merged by `id`) and which slots act as primary / fallback. */
    val pools: List<AxeOSPool>? = null,
    val primaryPoolIndex: Int? = null,
    val secondaryPoolIndex: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        hostname?.let { put("hostname", it) }
        frequency?.let { put("frequency", it) }
        coreVoltage?.let { put("coreVoltage", it) }
        overclockEnabled?.let { put("overclockEnabled", it) }
        autofanspeed?.let { put("autofanspeed", it) }
        fanspeed?.let { put("fanspeed", it) }
        manualFanSpeed?.let { put("manualFanSpeed", it) }
        temptarget?.let { put("temptarget", it) }
        pidTargetTemp?.let { put("pidTargetTemp", it) }
        poolMode?.let { put("poolMode", it) }
        poolBalance?.let { put("poolBalance", it) }
        stratumURL?.let { put("stratumURL", it) }
        stratumPort?.let { put("stratumPort", it) }
        stratumUser?.let { put("stratumUser", it) }
        stratumPassword?.let { put("stratumPassword", it) }
        fallbackStratumURL?.let { put("fallbackStratumURL", it) }
        fallbackStratumPort?.let { put("fallbackStratumPort", it) }
        fallbackStratumUser?.let { put("fallbackStratumUser", it) }
        fallbackStratumPassword?.let { put("fallbackStratumPassword", it) }
        pools?.let { put("pools", JSONArray().apply { it.forEach { p -> put(p.toJson()) } }) }
        primaryPoolIndex?.let { put("primaryPoolIndex", it) }
        secondaryPoolIndex?.let { put("secondaryPoolIndex", it) }
    }
}
