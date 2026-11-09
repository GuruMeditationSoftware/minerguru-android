package app.minerguru.ui.miner

import app.minerguru.R
import app.minerguru.model.tr
import app.minerguru.model.Privacy
import app.minerguru.ui.components.sensitive
import app.minerguru.ui.components.PrivacyToggle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.max
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.minerguru.model.ConnectionState
import app.minerguru.model.MinerSettingsForm
import app.minerguru.service.MinerLogStream
import app.minerguru.service.MinerStore
import app.minerguru.service.ShareDifficultyTracker
import app.minerguru.ui.components.SectionCard
import app.minerguru.ui.fleet.openUrl
import app.minerguru.ui.theme.MG

private val TABS get() = listOf(tr(R.string.overview), tr(R.string.history), "Live-Log", tr(R.string.control))

/** Detail page of one miner, split into Overview, History, Live log and Control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinerDetailScreen(store: MinerStore, minerId: String, showBack: Boolean, onBack: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val miner = store.miner(minerId)
    if (miner == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Swiping and the tab row share this state; pages keep their scroll position while off screen.
    val pager = rememberPagerState { TABS.size }
    fun openTab(index: Int) { scope.launch { pager.animateScrollToPage(index) } }
    var menu by remember { mutableStateOf(false) }
    var showJson by rememberSaveable { mutableStateOf(false) }
    var confirmRestart by rememberSaveable { mutableStateOf(false) }

    val logStream = remember(minerId) { MinerLogStream(scope) }
    val shareTracker = remember(minerId) { ShareDifficultyTracker(scope) }
    // The control form lives in the view model, so unsaved edits survive rotation, folding and leaving the page.
    val form = store.controlForm(minerId) ?: MinerSettingsForm()

    DisposableEffect(minerId) {
        logStream.onEntries = { shareTracker.ingest(it) }
        shareTracker.startSampling(miner.ipAddress) { logStream.isRunning }
        onDispose {
            shareTracker.stopSampling()
            logStream.onEntries = null
            logStream.stop()
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(miner.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                            Text("${miner.hardwareTitle} • ${Privacy.ip(miner.ipAddress)}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                    },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        PrivacyToggle(store)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr(R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(tr(R.string.refresh)) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                    onClick = { menu = false; store.refreshMinerAsync(minerId) })
                                DropdownMenuItem(text = { Text(tr(R.string.open_web_interface)) }, leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null) },
                                    onClick = { menu = false; openUrl(context, miner.webInterfaceUrl) })
                                DropdownMenuItem(text = { Text(tr(R.string.raw_json)) }, leadingIcon = { Icon(Icons.Rounded.Code, null) },
                                    onClick = { menu = false; showJson = true })
                                DropdownMenuItem(text = { Text(tr(R.string.restart_miner)) }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                                    onClick = { menu = false; confirmRestart = true })
                                if (store.alertsEnabled) {
                                    val muted = minerId in store.mutedMiners
                                    DropdownMenuItem(
                                        text = { Text(if (muted) tr(R.string.unmute_notifications) else tr(R.string.mute_notifications)) },
                                        leadingIcon = { Icon(if (muted) Icons.Rounded.Notifications else Icons.Rounded.NotificationsOff, null) },
                                        onClick = { menu = false; store.setMinerMuted(minerId, !muted) }
                                    )
                                }
                            }
                        }
                    }
                )
                PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp, containerColor = TopAppBarDefaults.topAppBarColors().let {
                    if (scrollBehavior.state.overlappedFraction > 0.01f) it.scrolledContainerColor else it.containerColor
                }) {
                    TABS.forEachIndexed { i, title -> Tab(selected = pager.currentPage == i, onClick = { openTab(i) }, text = { Text(title) }) }
                }
            }
        }
    ) { padding ->
        // Content runs behind the navigation bar; the keyboard shrinks the area so focused fields stay visible.
        BoxWithConstraints(
            Modifier.padding(top = padding.calculateTopPadding()).consumeWindowInsets(padding).imePadding().fillMaxSize()
        ) {
            val width = maxWidth
            val bottom = padding.calculateBottomPadding()
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val maxContent = if (page == 3) 640.dp else 1040.dp
                val side = max(16.dp, (width - maxContent) / 2)
                val contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = bottom + 16.dp)
                val info = miner.latestInfo
                if (info == null && page != 3) {
                    OfflinePlaceholder(miner.displayName, miner.ipAddress, miner.connectionState) { store.refreshMinerAsync(minerId) }
                } else when (page) {
                    0 -> Refreshable({ store.refreshMiner(minerId) }) {
                        OverviewTab(
                            store,
                            miner, store.tempWarningThresholdC, contentPadding, onConfigurePool = { openTab(3) },
                            probes = store.blockProbes, probeEnabled = store.stratumProbeEnabled, onOpenSettings = onOpenSettings
                        )
                    }
                    1 -> Refreshable({ store.refreshMiner(minerId) }) {
                        HistoryTab(miner, store.tempWarningThresholdC, store.pollingIntervalSeconds, shareTracker, logStream, contentPadding)
                    }
                    2 -> LogTab(miner, logStream, PaddingValues(start = side, end = side, top = 8.dp, bottom = bottom + 8.dp))
                    else -> ControlTab(store, miner, form, contentPadding, { store.updateControlForm(minerId, it) }) { store.reloadControlForm(minerId) }
                }
            }
        }
    }

    if (showJson) AlertDialog(
        onDismissRequest = { showJson = false },
        title = { Text(tr(R.string.raw_api_data)) },
        text = {
            Column {
                Text("http://${Privacy.ip(miner.ipAddress)}/api/system/info", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.size(8.dp))
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(miner.rawJSON ?: tr(R.string.no_data), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.sensitive())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showJson = false }) { Text(tr(R.string.close)) } },
        dismissButton = {
            TextButton(onClick = { copyToClipboard(context, "JSON", miner.rawJSON.orEmpty()) }) { Text(tr(R.string.copy)) }
        }
    )
    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text(tr(R.string.restart_miner_question)) },
        text = { Text(tr(R.string.restart_now_mining_is_interrupted, miner.displayName)) },
        confirmButton = { TextButton(onClick = { confirmRestart = false; store.restartMiner(minerId) }) { Text(tr(R.string.restart)) } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text(tr(R.string.cancel)) } }
    )
}

/** Pull-to-refresh around a scrolling tab. */
@Composable
private fun Refreshable(onRefresh: suspend () -> Unit, content: @Composable () -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; try { onRefresh() } finally { refreshing = false } } },
        modifier = Modifier.fillMaxSize()
    ) { content() }
}

@Composable
private fun OfflinePlaceholder(name: String, ip: String, state: ConnectionState, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.WifiOff, null, tint = MG.red(), modifier = Modifier.size(40.dp))
                Text(tr(R.string.is_not_responding, name, Privacy.ip(ip)), style = MaterialTheme.typography.titleSmall)
                (state as? ConnectionState.Offline)?.let {
                    Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onRetry) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text(tr(R.string.reconnect)) }
            }
        }
    }
}

fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}
