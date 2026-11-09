package app.minerguru.ui.miner

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import app.minerguru.service.WriteDeclined
import app.minerguru.service.MinerStore
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.minerguru.model.*
import app.minerguru.ui.components.*
import app.minerguru.ui.theme.MG
import app.minerguru.ui.theme.MGType
import app.minerguru.service.BlockProbeStore
import app.minerguru.service.BlockProbeTarget
import app.minerguru.ui.fleet.openUrl
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewTab(
    store: MinerStore,
    miner: MinerDevice,
    tempWarningC: Double,
    contentPadding: PaddingValues,
    onConfigurePool: () -> Unit,
    probes: BlockProbeStore,
    probeEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    val info = miner.latestInfo ?: return
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Identity
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceCard(DeviceCardSpec.of(miner), 112.dp, led = DeviceLed(miner.connectionState, info.temp >= tempWarningC))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(miner.hardwareTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${info.asicCount}× ${info.asicModel}", style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBlock(miner.connectionState, isHighTemp = info.temp >= tempWarningC)
                            Spacer(Modifier.width(6.dp))
                            Text(connectionDescription(miner.connectionState, info.temp >= tempWarningC),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Meta("IP", Privacy.ip(miner.ipAddress), mono = true)
                    Meta("Uptime", MinerFormatting.formatUptime(info.uptimeSeconds))
                    info.ssid?.takeIf { it.isNotEmpty() }?.let { Meta(L("WLAN", "Wi-Fi"), Privacy.ssid(it) + (info.wifiRSSI?.let { r -> " ($r dBm)" } ?: "")) }
                    if (info.firmwareVersion.isNotEmpty()) Meta("Firmware", info.firmwareVersion, mono = true)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PoolModeBadge(info, compact = false)
                    ActiveCoinsBadges(info, showFullName = true)
                }
            }
        }

        // KPIs
        val (hv, hu) = MinerFormatting.splitHashrate(info.hashRate)
        val hot = info.temp >= tempWarningC
        val latency = miner.activePoolLatencyMs
        val kpis: List<@Composable () -> Unit> = listOf(
            { MetricCard("Live Hashrate", hv, hu, info.hashrateAveragesText, Icons.Rounded.Bolt, if (miner.isNerdOS) MG.purple() else MG.orange()) },
            { MetricCard(L("ASIC Temperatur", "ASIC temperature"), MinerFormatting.format1(info.temp), "°C", info.coolingText(tempWarningC), Icons.Rounded.Thermostat,
                if (hot) MG.red() else if (info.temp > 62) MG.orange() else MG.green(), warning = hot || info.overheatMode) },
            { MetricCard(L("Verbrauch", "Power"), MinerFormatting.format1(info.power), "W", info.powerDetailText, Icons.Rounded.Bolt, MG.yellow()) },
            { MetricCard(L("Effizienz", "Efficiency"), if (info.efficiencyJPerTH > 0) MinerFormatting.format1(info.efficiencyJPerTH) else "—", "J/TH", info.dailyEnergyText, Icons.Rounded.Eco, MG.green()) },
            { MetricCard(L("Kühlung & Lüfter", "Cooling & fan"), info.fanPercentText, "%", info.fanRPMText, Icons.Rounded.Air, MG.blue()) },
            { MetricCard(L("ASIC Takt & VCore", "ASIC clock & VCore"), "${info.frequencyMHz}", "MHz", info.coreVoltageText, Icons.Rounded.GraphicEq, MG.teal()) },
            { MetricCard(if (info.isDualPoolMining) L("Pool-Latenz (Dual)", "Pool latency (dual)") else L("Pool-Latenz (RTT)", "Pool latency (RTT)"), latency?.let { MinerFormatting.format0(it) } ?: "—",
                if (latency != null) "ms" else null, miner.poolLatencyDetailText,
                if (info.isDualPoolMining) Icons.Rounded.AltRoute else Icons.Rounded.Public, MG.latency(LatencyQuality.evaluate(latency))) },
            { MetricCard(L("Best Diff (Neustart)", "Best diff (restart)"), info.sessionBestDiffText, null, L("Allzeit-Best", "All-time best") + ": ${info.bestDiffFormatted}", Icons.Rounded.EmojiEvents, MG.indigo()) },
            { MetricCard("Shares", "${info.sharesAccepted}", null, info.sharesText, Icons.Rounded.Verified, MG.blue()) }
        )
        CardGrid(kpis, spacing = 10.dp) { it() }

        PoolInformationCard(miner, onConfigurePool)
        PayoutCheckCard(store, miner)
        // The firmware reports the block from AxeOS 2.13 on; otherwise the probe asks the pool.
        val firmwareBlock = BlockTemplateSummary.from(info)
        if (firmwareBlock != null) {
            BlockTemplateCard(firmwareBlock, onOpenWebInterface = { openUrl(context, miner.webInterfaceUrl) })
        } else {
            ProbedBlockSection(probes, BlockProbeTarget.targets(info), probeEnabled, onOpenSettings)
        }
        HardwareSection(miner, info)
    }
}

@Composable
private fun Meta(label: String, value: String, mono: Boolean = false) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

/** Whom the pool slots of a miner pay. Writing the expected address is always the user's decision. */
@Composable
private fun PayoutCheckCard(store: MinerStore, miner: MinerDevice) {
    val info = miner.latestInfo ?: return
    val slots = PayoutCheck.slots(info, store.expectedAddresses)
    if (slots.isEmpty()) return
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(L("Auszahlung", "Payout"), Icons.Rounded.VerifiedUser)
            slots.forEach { slot ->
                val color = when (slot.verdict) {
                    PayoutVerdict.EXPECTED -> MG.green()
                    PayoutVerdict.FOREIGN, PayoutVerdict.FACTORY_DEFAULT -> MaterialTheme.colorScheme.error
                    PayoutVerdict.ACCOUNT -> MG.yellow()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
                    Column(Modifier.weight(1f)) {
                        Text("${slot.title} · ${slot.coin.symbol}", style = MaterialTheme.typography.titleSmall)
                        Text(Privacy.user(slot.address).ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.MiddleEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(slot.verdict.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val target = store.expectedAddresses[slot.coin.symbol]
                if (target != null && (slot.verdict.needsAttention || slot.verdict == PayoutVerdict.ACCOUNT)) {
                    FilledTonalButton(onClick = {
                        failure = null
                        scope.launch {
                            try {
                                store.applyExpectedAddress(target, slot.key, miner.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: WriteDeclined) {
                                // Nothing happened.
                            } catch (e: Exception) {
                                failure = e.message
                            }
                        }
                    }) { Text(L("Soll-Adresse eintragen", "Enter expected address")) }
                }
            }
            when (info.coinbasePayoutStatus) {
                "verified" -> L("Der aktuelle Block zahlt an diesen Miner.", "The current block pays this miner.")
                "not_found" -> L("Der aktuelle Block enthält die Adresse dieses Miners nicht.", "The current block does not contain this miner's address.")
                else -> null
            }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun PoolInformationCard(miner: MinerDevice, onConfigurePool: () -> Unit) {
    val info = miner.latestInfo ?: return
    val context = LocalContext.current
    val isDual = info.isDualPoolMining
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(L("Pool & Stratum-Verbindung", "Pool & stratum connection"), Icons.Rounded.Public)
            if (isDual) DualSplitBanner(info)

            PoolSlot(
                title = if (isDual) "Pool 1 (${info.dualPoolPrimaryPercent}% • ~${MinerFormatting.formatHashrate(info.hashRate * info.dualPoolPrimaryPercent / 100.0)})" else L("Primärer Stratum-Pool", "Primary stratum pool"),
                coin = info.primaryPoolCoin,
                isActive = if (isDual) info.primaryPoolStatus?.connected ?: true else !info.isUsingFallbackStratum,
                host = info.stratumURL, port = info.stratumPort, user = info.stratumUser,
                status = info.primaryPoolStatus, netDiff = info.primaryPoolNetworkDiffFormatted,
                sessionBest = info.primaryPoolSessionBestDiff,
                directMs = miner.poolLatency?.primaryPoolDirectMs, minerMs = info.primaryPoolMinerLatencyMs
            )
            PoolSlot(
                title = if (isDual) "Pool 2 (${info.dualPoolSecondaryPercent}% • ~${MinerFormatting.formatHashrate(info.hashRate * info.dualPoolSecondaryPercent / 100.0)})" else L("Fallback Stratum-Pool", "Fallback stratum pool"),
                coin = info.secondaryPoolCoin,
                isActive = if (isDual) info.secondaryPoolStatus?.connected ?: true else info.isUsingFallbackStratum,
                host = info.fallbackStratumURL, port = info.fallbackStratumPort, user = info.fallbackStratumUser,
                status = info.secondaryPoolStatus, netDiff = info.secondaryPoolNetworkDiffFormatted,
                sessionBest = info.secondaryPoolSessionBestDiff,
                directMs = miner.poolLatency?.fallbackPoolDirectMs, minerMs = info.secondaryPoolMinerLatencyMs
            )

            HorizontalDivider()
            info.activeWorkerName?.let { Meta("Worker", it, mono = true) }
            if (!isDual) info.activePoolDifficulty?.takeIf { it > 0 }?.let { Meta("Pool-Difficulty", MinerFormatting.formatDifficulty(it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onConfigurePool) { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L("Pool konfigurieren", "Configure pool")) }
                FilledTonalButton(onClick = { copyToClipboard(context, "Pool-User", info.stratumUser) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L("User kopieren", "Copy user"))
                }
            }
        }
    }
}

@Composable
private fun DualSplitBanner(info: MinerSystemInfo) {
    val c1 = MG.coin(info.primaryPoolCoin)
    val c2 = MG.coin(info.secondaryPoolCoin)
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MG.purple().copy(alpha = 0.08f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CoinBadge(info.primaryPoolCoin, info.dualPoolPrimaryPercent)
            Text(L("Dual-Pool Hashrate-Split", "Dual pool hashrate split"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CoinBadge(info.secondaryPoolCoin, info.dualPoolSecondaryPercent)
        }
        Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.weight(info.dualPoolPrimaryPercent.toFloat()).height(8.dp).clip(MaterialTheme.shapes.extraSmall).background(c1))
            Box(Modifier.weight(info.dualPoolSecondaryPercent.toFloat()).height(8.dp).clip(MaterialTheme.shapes.extraSmall).background(c2))
        }
    }
}

@Composable
private fun PoolSlot(
    title: String, coin: MinedCoin, isActive: Boolean, host: String, port: Int, user: String,
    status: StratumPoolStatus?, netDiff: String?, sessionBest: Double?, directMs: Double?, minerMs: Double?
) {
    val accent = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (isActive) accent.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .border(1.dp, accent.copy(alpha = 0.3f), MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (isActive) MG.green() else MaterialTheme.colorScheme.outline))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2)
        }
        FlowRowSimple {
            if (host.isNotEmpty()) CoinBadge(coin, showFullName = true)
            LatencyBadge(minerMs ?: directMs)
        }
        Row { Label("Server"); Text(if (host.isEmpty()) "—" else "$host:$port", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium) }
        Row { Label("User"); Text(Privacy.user(user).ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.MiddleEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (status != null) {
            Row {
                Label("Shares")
                Text(
                    buildString {
                        status.accepted?.let { append("${DisplayFormat.integer(it)} ok / ${status.rejected ?: 0} rej") }
                        status.poolDifficulty?.takeIf { it > 0 }?.let { append(" • Pool-Diff: ${MinerFormatting.formatDifficulty(it)}") }
                        netDiff?.let { append(" • Net-Diff: $it") }
                    }, style = MaterialTheme.typography.bodySmall
                )
            }
        } else if (netDiff != null) {
            Row { Label(L("Netzwerk", "Network")); Text("Net-Diff: $netDiff (${coin.fullName})", style = MaterialTheme.typography.bodySmall) }
        }
        Row { Label("Best Diff"); Text("${MinerFormatting.formatSessionBest(sessionBest)} " + L("seit Neustart", "since restart"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
        Row {
            Label(L("Latenz", "Latency"))
            val parts = listOfNotNull(
                minerMs?.takeIf { it > 0 }?.let { "Miner-RTT: ${MinerFormatting.formatLatency(it)}" },
                directMs?.takeIf { it > 0 }?.let { "TCP-Ping: ${MinerFormatting.formatLatency(it)}" }
            )
            Text(if (parts.isEmpty()) L("Wird gemessen…", "Measuring…") else parts.joinToString(" • "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowSimple(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }

@Composable
private fun Label(text: String) =
    Text(text, Modifier.width(70.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun HardwareSection(miner: MinerDevice, info: MinerSystemInfo) {
    val items = listOf(
        L("Modell / Typ", "Model / type") to miner.hardwareTitle,
        "ASIC-Chip" to "${info.asicCount}x ${info.asicModel}",
        "Board-Version" to (info.boardVersion ?: "—"),
        "Small Cores" to (info.smallCoreCount?.toString() ?: "—"),
        "Hostname" to info.hostname,
        L("MAC-Adresse", "MAC address") to (info.macAddress?.let { Privacy.mac(it) } ?: "—"),
        L("WLAN SSID", "Wi-Fi SSID") to (info.ssid?.let { Privacy.ssid(it) } ?: "—"),
        L("WLAN Signal", "Wi-Fi signal") to (info.wifiRSSI?.let { "$it dBm" } ?: info.wifiStatus ?: "—"),
        "Firmware" to info.firmwareVersion.ifEmpty { "—" },
        "ESP-IDF" to (info.idfVersion ?: "—"),
        L("Freier Heap RAM", "Free heap RAM") to (info.freeHeap?.let { "${it / 1024} KB" } ?: "—"),
        L("Best Diff Session / Allzeit", "Best diff session / all-time") to "${info.sessionBestDiffText} / ${info.bestDiffFormatted}"
    )
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(L("Hardware- & System-Telemetrie", "Hardware & system telemetry"), Icons.Rounded.Memory)
            CardGrid(items, minCellWidth = 140.dp) { (l, v) -> LabeledValue(l, v, mono = true) }
        }
    }
}
