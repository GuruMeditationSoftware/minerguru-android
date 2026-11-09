package app.minerguru.ui.fleet

import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.minerguru.R
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.minerguru.model.*
import app.minerguru.service.MinerStore
import app.minerguru.ui.components.*
import app.minerguru.ui.theme.MG
import app.minerguru.ui.theme.MGType
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** Fleet cockpit: status, fleet KPIs and solo-mining chances. */
@Composable
fun DashboardScreen(
    store: MinerStore,
    onOpenScanner: (startOnScan: Boolean) -> Unit,
    onOpenMiners: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenReports: () -> Unit
) {
    val gate = LocalNetworkGate.current
    ScreenScaffold(
        "MinerGuru", large = true,
        actions = {
            PrivacyToggle(store)
            IconButton(onClick = { gate.run { onOpenScanner(true) } }) { Icon(Icons.Rounded.Radar, tr(R.string.scan_network)) }
            RefreshAllMenu(store)
        }
    ) { inner ->
        PageContent(inner, maxWidth = 1040.dp, onRefresh = if (store.miners.isEmpty()) null else ({ store.refreshAllMiners() })) { pad ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!store.localNetworkGranted) NetworkAccessCard()
                app.minerguru.ui.support.UpdateBanner(store.updates)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { app.minerguru.ui.support.SupportDevelopmentButton() }
                if (store.miners.isEmpty()) {
                    Onboarding(store, onOpenScanner)
                    return@Column
                }
                FleetSection(store, onOpenMiners)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val due = store.maintenanceTasks.count { it.daysUntilDue() <= 0 }
                    SectionCard(Modifier.weight(1f), onClick = onOpenMaintenance) {
                        Column {
                            Text(tr(R.string.maintenance), style = MaterialTheme.typography.titleSmall)
                            Text(if (due > 0) tr(R.string.due, due) else tr(R.string.nothing_due),
                                style = MaterialTheme.typography.bodySmall, color = if (due > 0) MG.warning() else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SectionCard(Modifier.weight(1f), onClick = onOpenReports) {
                        Column {
                            Text(tr(R.string.reports), style = MaterialTheme.typography.titleSmall)
                            Text(tr(R.string.pdf_and_csv), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SoloChanceSection(store)
            }
        }
    }
}

/** Overflow menu with "refresh all" – the menu path for people who cannot use pull-to-refresh. */
@Composable
fun RefreshAllMenu(store: MinerStore) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, tr(R.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(tr(R.string.refresh_all)) },
                leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                enabled = store.miners.isNotEmpty(),
                onClick = { open = false; scope.launch { store.refreshAllMiners() } }
            )
        }
    }
}

@Composable
private fun Onboarding(store: MinerStore, onOpenScanner: (Boolean) -> Unit) {
    val gate = LocalNetworkGate.current
    SectionCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceCardStack(
                listOf(
                    DeviceCardSpec("nerdqaxe++", "", 4, "BM1370", tft = true) to null,
                    DeviceCardSpec("bitaxe", "Gamma 601", 1, "BM1370", tft = false) to null
                ),
                width = 120.dp
            )
            Text(tr(R.string.welcome_to_minerguru), style = MaterialTheme.typography.headlineSmall)
            Text(
                tr(R.string.your_dashboard_for_bitaxe_and),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (store.isScanning) {
                CircularProgressIndicator()
                Text(
                    tr(R.string.checking_ips_in) + " ${Privacy.ip(store.subnetPrefix)}.1–254… (${store.scanCompleted}/${store.scanTotal})",
                    style = MaterialTheme.typography.bodySmall
                )
                if (store.discoveredMiners.isNotEmpty()) {
                    Text(trp(R.plurals.miners_count, store.discoveredMiners.size) + tr(R.string.found), color = MG.text(MG.green()),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                FilledTonalButton(onClick = { store.stopScan() }) { Text(tr(R.string.stop)) }
            } else {
                Button(onClick = { gate.run { store.startScan(); onOpenScanner(true) } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Radar, null); Spacer(Modifier.width(8.dp)); Text(tr(R.string.scan_network_now))
                }
                FilledTonalButton(onClick = { onOpenScanner(false) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.AddCircle, null); Spacer(Modifier.width(8.dp)); Text(tr(R.string.add_miner_by_ip))
                }
            }
        }
    }
}

/**
 * The fleet in one card, as on the Mac: total hashrate as the big figure, its course since the
 * app opened, the strongest miners as a fan of device cards and a strip of key figures below.
 */
@Composable
private fun FleetSection(store: MinerStore, onOpenMiners: () -> Unit) {
    val online = store.onlineMiners.size
    val total = store.miners.size
    val accent = MG.accent()
    val meta = buildList {
        add(tr(R.string.of_online, online, total))
        if (total > online) add("${total - online} offline")
        store.lastGlobalRefresh?.let { add(tr(R.string.updated) + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))) }
    }.joinToString(" · ")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(R.string.fleet), style = MaterialTheme.typography.titleMedium)
        Text(meta, style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        SectionCard(onClick = onOpenMiners) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.total_hashrate), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val (value, unit) = MinerFormatting.splitHashrate(store.totalHashrateGHs)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(value, style = MGType.figure(40.sp), maxLines = 1)
                            Spacer(Modifier.width(6.dp))
                            Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp))
                        }
                    }
                    val stack = store.miners
                        .sortedByDescending { if (it.connectionState.isOnline) it.latestInfo?.hashRate ?: 0.0 else -1.0 }
                        .take(3)
                        .map { m ->
                            DeviceCardSpec.of(m) to DeviceLed(m.connectionState, (m.latestInfo?.temp ?: 0.0) >= store.tempWarningThresholdC)
                        }
                    if (stack.isNotEmpty()) DeviceCardStack(stack, width = 84.dp)
                }
                if (store.fleetHistory.size >= 2) {
                    Sparkline(store.fleetHistory, accent, Modifier.fillMaxWidth().height(64.dp), format = MinerFormatting::formatHashrate)
                }
                HorizontalDivider(color = MG.hairline())
                FleetFigures(store)
            }
        }
    }
}

/** Key figures as one quiet strip instead of a grid of colored tiles. */
@Composable
private fun FleetFigures(store: MinerStore) {
    val acc = store.totalSharesAccepted
    val rej = store.totalSharesRejected
    val rejRate = if (acc + rej > 0) rej.toDouble() / (acc + rej) * 100.0 else 0.0
    val latency = store.averagePoolLatencyMs
    val best = store.bestSessionMiner
    val figures = listOf(
        Figure(tr(R.string.power), MinerFormatting.format1(store.totalPowerWatts), "W",
            "${MinerFormatting.format2(store.totalPowerWatts * 24 / 1000)} kWh/" + tr(R.string.day_in_text)),
        Figure(tr(R.string.efficiency), if (store.fleetEfficiencyJPerTH > 0) MinerFormatting.format1(store.fleetEfficiencyJPerTH) else "—", "J/TH", null),
        Figure("Shares", DisplayFormat.integer(acc), null, tr(R.string.rejected, rej) + " · ${MinerFormatting.format2(rejRate)} %"),
        Figure(tr(R.string.best_diff_since_restart), MinerFormatting.formatSessionBest(best?.second), null,
            tr(R.string.all_time) + store.highestBestDiffFormatted),
        Figure(tr(R.string.avg_pool_latency), latency?.let { MinerFormatting.format0(it) } ?: "—", if (latency != null) "ms" else null,
            LatencyQuality.evaluate(latency).label)
    )
    CardGrid(figures, minCellWidth = 128.dp, spacing = 16.dp, minColumns = 2, maxColumns = 5) { FigureCell(it) }
}

private data class Figure(val label: String, val value: String, val unit: String?, val detail: String?)

@Composable
private fun FigureCell(f: Figure) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(f.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(f.value, style = MGType.figure(20.sp), maxLines = 1)
            if (f.unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(f.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        if (f.detail != null) {
            Text(f.detail, style = MGType.mono(10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoloChanceSection(store: MinerStore) {
    val summaries = store.activeFleetCoinSummaries
    if (summaries.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr(R.string.solo_mining_chances), style = MaterialTheme.typography.titleMedium)
        Text(
            tr(R.string.probability_of_finding_a_block),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        var tfMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = tfMenu, onExpandedChange = { tfMenu = it }) {
            AppTextField(
                value = store.soloChanceTimeframe.label, onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text(tr(R.string.timeframe)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tfMenu) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).widthIn(min = 220.dp)
            )
            ExposedDropdownMenu(expanded = tfMenu, onDismissRequest = { tfMenu = false }) {
                SoloChanceTimeframe.entries.forEach { tf ->
                    DropdownMenuItem(text = { Text(tf.label) }, onClick = { store.updateSoloTimeframe(tf); tfMenu = false },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding)
                }
            }
        }

        CardGrid(summaries, minCellWidth = 320.dp, minColumns = 1, maxColumns = 3) { CoinSoloChanceCard(it, store.soloChanceTimeframe) }
    }
}

@Composable
private fun CoinSoloChanceCard(summary: FleetCoinMiningSummary, timeframe: SoloChanceTimeframe) {
    val accent = MG.coin(summary.coin)
    SectionCard(Modifier.fillMaxHeight()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(summary.coin.fullName, accent, dot = true, modifier = Modifier.weight(1f))
                Text(MinerFormatting.formatHashrate(summary.totalHashrateGHs), style = MGType.mono(12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                tr(R.string.solo_chance) + " · ${timeframe.label}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(summary.formattedOddsRatio(timeframe), style = MGType.figure(24.sp))
                Text("(${summary.formattedProbabilityPercent(timeframe)})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(
                tr(R.string.avg_1_block_every) + " ${summary.expectedTimePerBlockFormatted}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                tr(R.string.block_reward) + ": ${summary.blockRewardText}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(summary.contributingMinersText, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(tr(R.string.net_diff) + ": ${MinerFormatting.formatDifficulty(summary.networkDifficulty)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
