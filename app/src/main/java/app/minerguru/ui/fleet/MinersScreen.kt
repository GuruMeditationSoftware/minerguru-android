package app.minerguru.ui.fleet

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.R
import app.minerguru.model.ConnectionState
import app.minerguru.model.tr
import app.minerguru.model.Privacy
import app.minerguru.model.MinerDevice
import app.minerguru.model.MinerFormatting
import app.minerguru.service.MinerStore
import app.minerguru.ui.components.*
import app.minerguru.ui.theme.MG
import app.minerguru.ui.theme.MGType
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip

@Composable
fun MinersScreen(
    store: MinerStore,
    onOpenMiner: (String) -> Unit,
    onAdd: (startOnScan: Boolean) -> Unit
) {
    val gate = LocalNetworkGate.current
    ScreenScaffold(
        tr(R.string.miners),
        actions = {
            PrivacyToggle(store)
            IconButton(onClick = { gate.run { onAdd(true) } }) { Icon(Icons.Rounded.Radar, tr(R.string.scan_network)) }
            RefreshAllMenu(store)
        },
        floatingActionButton = {
            if (store.miners.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { onAdd(false) },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(tr(R.string.miner)) }
                )
            }
        }
    ) { inner ->
        if (store.miners.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(inner).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                if (!store.localNetworkGranted) NetworkAccessCard()
                Icon(Icons.Rounded.Memory, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr(R.string.no_miners_yet), style = MaterialTheme.typography.titleMedium)
                Text(tr(R.string.add_your_first_bitaxe_or), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onAdd(false) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(tr(R.string.add_miner)) }
            }
            return@ScreenScaffold
        }
        PageContent(inner, onRefresh = { store.refreshAllMiners() }) { pad ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = pad.calculateStartPadding(LocalLayoutDirection.current), end = pad.calculateEndPadding(LocalLayoutDirection.current),
                    top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 72.dp // room for the FAB
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!store.localNetworkGranted) item { NetworkAccessCard() }
                items(store.miners, key = { it.id }) { miner ->
                    MinerSummaryCard(
                        miner, store.tempWarningThresholdC,
                        onOpen = { onOpenMiner(miner.id) },
                        onRestart = { store.restartMiner(miner.id) },
                        onRemove = { store.removeMiner(miner.id) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

/** Shown in the detail pane on large screens until a miner is picked. */
@Composable
fun MinerDetailPlaceholder() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Rounded.Tune, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tr(R.string.pick_a_miner_to_see),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MinerSummaryCard(
    miner: MinerDevice,
    tempWarningC: Double,
    onOpen: () -> Unit,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val info = miner.latestInfo
    val isHighTemp = (info?.temp ?: 0.0) >= tempWarningC
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    SectionCard(modifier, onClick = onOpen, highlight = if (isHighTemp) MG.warning() else null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // The board's LED is the status block.
                DeviceCard(DeviceCardSpec.of(miner), 72.dp, Modifier.padding(top = 2.dp), DeviceLed(miner.connectionState, isHighTemp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(miner.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(miner.hardwareTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Privacy.ip(miner.ipAddress) + (info?.firmwareVersion?.takeIf { it.isNotEmpty() }?.let { "  $it" } ?: ""),
                        style = MGType.mono(11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp)
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr(R.string.actions)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(tr(R.string.details_control)) }, leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                            onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text(tr(R.string.axeos_nerdos_in_browser)) }, leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null) },
                            onClick = { menu = false; openUrl(context, miner.webInterfaceUrl) })
                        DropdownMenuItem(text = { Text(tr(R.string.restart_miner)) }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                            onClick = { menu = false; confirmRestart = true })
                        DropdownMenuItem(text = { Text(tr(R.string.remove_from_minerguru)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; confirmRemove = true })
                    }
                }
            }

            if (info != null) {
                val (value, unit) = MinerFormatting.splitHashrate(info.hashRate)
                val accent = MG.accent()
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Hashrate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            ActiveCoinsBadges(info)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(value, style = MGType.figure(28.sp))
                            Text(" $unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Text(tr(R.string.best_since_restart) + info.sessionBestDiffText + tr(R.string.all_time_separator) + info.bestDiffFormatted,
                            style = MGType.mono(10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Sparkline(miner.history.takeLast(30).map { it.hashRateGHs }, accent, Modifier.width(100.dp).height(40.dp)) { MinerFormatting.formatHashrate(it) }
                }

                // Three pills per row; two when the font is scaled up, so values are not cut off.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 2 else 3, modifier = Modifier.fillMaxWidth()
                ) {
                    // Neutral icons; only a temperature that is too high brings in color.
                    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
                    SensorPill(Icons.Rounded.Thermostat, "ASIC", "${MinerFormatting.format1(info.temp)} °C",
                        if (isHighTemp) MG.bad() else if (info.temp > 62) MG.warning() else neutral, Modifier.weight(1f))
                    SensorPill(Icons.Rounded.Bolt, "${MinerFormatting.format1(info.efficiencyJPerTH)} J/TH", "${MinerFormatting.format1(info.power)} W", neutral, Modifier.weight(1f))
                    SensorPill(Icons.Rounded.Air, if (info.fanRPM > 0) "${info.fanRPM} RPM" else tr(R.string.fan), "${info.fanPercentText} %", neutral, Modifier.weight(1f))
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(if (info.isDualPoolMining) Icons.Rounded.AltRoute else Icons.Rounded.Public, null,
                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(info.activePoolEndpoint, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PoolModeBadge(info)
                    LatencyBadge(miner.activePoolLatencyMs)
                }
            } else {
                when (val st = miner.connectionState) {
                    is ConnectionState.Offline -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.WifiOff, null, tint = MG.bad())
                        Column {
                            Text(tr(R.string.unreachable), fontWeight = FontWeight.Medium)
                            Text(st.reason, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(tr(R.string.connecting_to) + " ${Privacy.ip(miner.ipAddress)}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text(tr(R.string.restart_miner_question)) },
        text = { Text(tr(R.string.restart_miner_now_question, miner.displayName, Privacy.ip(miner.ipAddress))) },
        confirmButton = { TextButton(onClick = { confirmRestart = false; onRestart() }) { Text(tr(R.string.restart)) } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text(tr(R.string.cancel)) } }
    )
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(tr(R.string.remove_miner)) },
        text = { Text(tr(R.string.is_removed_from_minerguru_and, miner.displayName)) },
        confirmButton = {
            TextButton(onClick = { confirmRemove = false; onRemove() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr(R.string.remove)) }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(tr(R.string.cancel)) } }
    )
}

@Composable
private fun SensorPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
