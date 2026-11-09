package app.minerguru.ui.scanner

import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.minerguru.R
import app.minerguru.model.ConnectionState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.minerguru.model.tr
import app.minerguru.model.trp
import app.minerguru.model.MinerFormatting
import app.minerguru.model.Privacy
import app.minerguru.model.MinerSystemInfo
import app.minerguru.service.DiscoveredMiner
import app.minerguru.service.MinerApiClient
import app.minerguru.service.MinerStore
import app.minerguru.ui.components.*
import app.minerguru.ui.theme.MG
import kotlinx.coroutines.launch

/** Full-screen flow to add miners: network scan or manual IP. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMinerScreen(store: MinerStore, startOnScan: Boolean, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (startOnScan) 0 else 1) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.add_miner)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) } }
            )
        }
    ) { padding ->
        // The keyboard shrinks the content area, so focused fields stay visible.
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)
        ) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr(R.string.network_scan)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr(R.string.by_ip_hostname)) })
            }
            if (tab == 0) ScanTab(store) else IpTab(store, onClose)
        }
    }
}

@Composable
private fun ScanTab(store: MinerStore) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(R.string.detects_all_bitaxe_and_nerdos),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(
                value = store.subnetPrefix, onValueChange = { store.updateSubnetPrefix(it.trim()) },
                label = { Text(tr(R.string.subnet_prefix)) }, suffix = { Text(".1 – .254") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).sensitive(),
                enabled = !store.isScanning
            )
            if (store.isScanning) {
                FilledTonalButton(onClick = { store.stopScan() }) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.stop)) }
            } else {
                val gate = LocalNetworkGate.current
                Button(onClick = { gate.run { store.startScan() } }) { Icon(Icons.Rounded.Radar, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.scan)) }
            }
        }
        Text(tr(R.string.own_ip) + ": ${Privacy.ip(store.detectedLocalIp)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (store.isScanning) {
            LinearProgressIndicator(progress = { store.scanCompleted / store.scanTotal.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(tr(R.string.scanning) + " ${Privacy.ip(store.subnetPrefix)}.1–254… (${store.scanCompleted} / ${store.scanTotal} " + tr(R.string.ips_checked) + ")", style = MaterialTheme.typography.bodySmall)
        } else if (store.scanFinished) {
            Text(trp(R.plurals.miners_count, store.discoveredMiners.size) + tr(R.string.found), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (store.discoveredMiners.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MG.text(MG.green()))
        }

        if (store.discoveredMiners.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.found_miners) + " (${store.discoveredMiners.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (store.discoveredMiners.any { !store.isIpAlreadyAdded(it.ipAddress) }) {
                    TextButton(onClick = { store.addAllDiscovered() }) { Text(tr(R.string.add_all_new)) }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(store.discoveredMiners, key = { it.ipAddress }) { item -> DiscoveredRow(store, item) }
            }
        } else if (!store.isScanning && store.scanFinished) {
            EmptyHint(tr(R.string.no_miners_found_check_the))
        }
    }
}

@Composable
private fun DiscoveredRow(store: MinerStore, item: DiscoveredMiner) {
    val added = store.isIpAlreadyAdded(item.ipAddress)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceCard(DeviceCardSpec.of(item.info), 52.dp, led = DeviceLed(ConnectionState.Online))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.info.hostname, style = MaterialTheme.typography.titleSmall)
                Text("${Privacy.ip(item.ipAddress)} • ${item.info.detectedHardwareTitle}", style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
                Text("${MinerFormatting.formatHashrate(item.info.hashRate)} • ${MinerFormatting.format1(item.info.temp)} °C • ${item.info.asicCount}x ${item.info.asicModel}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (added) {
                Chip(tr(R.string.added), MG.green(), icon = Icons.Rounded.CheckCircle)
            } else {
                Button(onClick = { store.addDiscovered(item) }) { Text(tr(R.string.add)) }
            }
        }
    }
}

@Composable
private fun IpTab(store: MinerStore, onClose: () -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testInfo by remember { mutableStateOf<MinerSystemInfo?>(null) }
    var testRaw by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gate = LocalNetworkGate.current
    val hostValid = MinerApiClient.isValidHost(host)
    var notMiner by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(R.string.connects_via_http_to_api),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppTextField(
            value = host, onValueChange = { host = it; testInfo = null; testError = null; notMiner = false },
            label = { Text(tr(R.string.ip_address_or_hostname)) }, placeholder = { Text("192.168.1.150 / bitaxe.local") },
            singleLine = true, modifier = Modifier.fillMaxWidth().sensitive(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        if (host.isNotBlank() && !hostValid) {
            StatusMessage(tr(R.string.this_is_not_a_valid), true)
        }
        AppTextField(
            value = name, onValueChange = { name = it }, label = { Text(tr(R.string.display_name_optional)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        FilledTonalButton(
            onClick = gate@{
                if (!store.localNetworkGranted) { gate.request(); return@gate }
                testing = true; testInfo = null; notMiner = false; testError = null
                scope.launch {
                    try {
                        val r = MinerApiClient.fetchSystemInfo(host)
                        if (r.isMiner) { testInfo = r.info; testRaw = r.rawPretty }
                        else notMiner = true
                    } catch (e: Exception) {
                        testError = e.message ?: tr(R.string.unreachable)
                    }
                    testing = false
                }
            },
            enabled = hostValid && !testing, modifier = Modifier.fillMaxWidth()
        ) {
            if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(tr(R.string.test_connection))
        }

        testInfo?.let { info ->
            StatusMessage(
                "${info.detectedHardwareTitle} " + tr(R.string.detected) + " • ${MinerFormatting.formatHashrate(info.hashRate)} • ${MinerFormatting.format1(info.temp)} °C • Pool: ${info.activePoolEndpoint}",
                isError = false
            )
        }
        if (notMiner) StatusMessage(tr(R.string.the_device_answers_but_is), true)
        testError?.let { StatusMessage(tr(R.string.miner_currently_unreachable) + ": $it — " + tr(R.string.you_can_still_add_the), isError = true) }

        Button(
            onClick = {
                store.addMiner(host, name, testInfo, testRaw)
                onClose()
            },
            enabled = hostValid, modifier = Modifier.fillMaxWidth()
        ) { Text(tr(R.string.add_miner)) }
    }
}
