package app.minerguru.ui

import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import app.minerguru.R
import app.minerguru.ui.components.isCompactHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.minerguru.model.tr
import app.minerguru.service.MinerStore
import app.minerguru.ui.components.AppSnackbar
import app.minerguru.ui.components.AppSnackbarVisuals
import app.minerguru.ui.components.LocalNetworkGate
import app.minerguru.ui.components.rememberNetworkAccessGate
import app.minerguru.ui.fleet.DashboardScreen
import app.minerguru.ui.fleet.MinerDetailPlaceholder
import app.minerguru.ui.fleet.MinersScreen
import app.minerguru.ui.maintenance.MaintenanceScreen
import app.minerguru.ui.miner.MinerDetailScreen
import app.minerguru.ui.pools.PoolsScreen
import app.minerguru.ui.power.PowerScreen
import app.minerguru.ui.reports.ReportsScreen
import app.minerguru.ui.scanner.AddMinerScreen
import app.minerguru.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/** Navigation destinations. The back stack always starts with [Overview]; a tab sits on top of it. */
sealed interface Route {
    data object Overview : Route
    data object Miners : Route
    data object Pools : Route
    data object Power : Route
    data object Settings : Route
    data object Maintenance : Route
    data object Reports : Route
    data class MinerDetail(val minerId: String) : Route
    data class AddMiner(val startOnScan: Boolean) : Route

    companion object {
        val tabs = listOf(Overview, Miners, Pools, Power, Settings)

        fun encode(r: Route): String = when (r) {
            Overview -> "overview"
            Miners -> "miners"
            Pools -> "pools"
            Power -> "power"
            Settings -> "settings"
            Maintenance -> "maintenance"
            Reports -> "reports"
            is MinerDetail -> "miner:${r.minerId}"
            is AddMiner -> "add:${r.startOnScan}"
        }

        fun decode(s: String): Route = when {
            s == "miners" -> Miners
            s == "pools" -> Pools
            s == "power" -> Power
            s == "settings" -> Settings
            s == "maintenance" -> Maintenance
            s == "reports" -> Reports
            s.startsWith("miner:") -> MinerDetail(s.removePrefix("miner:"))
            s.startsWith("add:") -> AddMiner(s.removePrefix("add:").toBoolean())
            else -> Overview
        }
    }
}

/** Icons-only rail for windows with little height: fits five tabs in 360 dp and scrolls in anything smaller. */
@Composable
private fun CompactRail(currentTab: Route, onSelect: (Route) -> Unit) {
    Box(
        Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)).width(72.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
            Route.tabs.forEach { tab ->
                NavigationRailItem(
                    selected = currentTab == tab,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.tabIcon, tab.tabTitle) }
                )
            }
        }
    }
}

private fun NavigationSuiteType.isHorizontalBar() =
    this == NavigationSuiteType.ShortNavigationBarCompact || this == NavigationSuiteType.ShortNavigationBarMedium

private val Route.tabTitle: String
    get() = when (this) {
        Route.Overview -> tr(R.string.overview)
        Route.Miners -> tr(R.string.miners)
        Route.Pools -> tr(R.string.pools)
        Route.Power -> tr(R.string.power_page)
        else -> tr(R.string.settings)
    }

private val Route.tabIcon: ImageVector
    get() = when (this) {
        Route.Overview -> Icons.Rounded.Dashboard
        Route.Miners -> Icons.Rounded.Memory
        Route.Pools -> Icons.Rounded.Dns
        Route.Power -> Icons.Rounded.Bolt
        else -> Icons.Rounded.Settings
    }

private val backStackSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { stack -> stack.map(Route::encode) },
    restore = { saved -> mutableStateListOf<Route>().apply { addAll(saved.map(Route::decode)) } }
)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MinerGuruMainScreen(store: MinerStore) {
    val backStack = rememberSaveable(saver = backStackSaver) { mutableStateListOf<Route>(Route.Overview) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gate = rememberNetworkAccessGate(store)
    app.minerguru.ui.support.OtpCodeDialog(store)
    app.minerguru.ui.support.WriteConfirmDialog(store)

    LaunchedEffect(Unit) {
        store.banners.collect { b ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(AppSnackbarVisuals(b.text, b.isError, b.retry?.let { tr(R.string.retry) }))
                if (result == SnackbarResult.ActionPerformed) b.retry?.invoke()
            }
        }
    }

    val currentTab = backStack.lastOrNull { it in Route.tabs } ?: Route.Overview
    fun selectTab(tab: Route) {
        backStack.clear()
        backStack.add(Route.Overview)
        if (tab != Route.Overview) backStack.add(tab)
    }
    fun push(route: Route) {
        // Only one detail page at a time: a new miner replaces the one shown next to the list.
        if (backStack.lastOrNull() is Route.MinerDetail && route is Route.MinerDetail) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }
    fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    // A tap on a home-screen widget: a miner opens next to the list, anything else as its tab.
    LaunchedEffect(store.pendingRoute) {
        val route = store.pendingRoute?.let(Route::decode) ?: return@LaunchedEffect
        store.pendingRoute = null
        when {
            route is Route.MinerDetail -> {
                selectTab(Route.Miners)
                if (store.miner(route.minerId) != null) push(route)
            }
            route in Route.tabs -> selectTab(route)
            else -> push(route)
        }
    }

    // Phones hide the bottom bar on full-screen sub pages; rails stay visible.
    // Neither a bottom bar nor the standard rail fits the little height of a landscape phone: see CompactRail.
    val compactHeight = isCompactHeight()
    val suiteType = if (compactHeight) NavigationSuiteType.None else NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
    val horizontalBar = suiteType.isHorizontalBar()
    val suiteState = rememberNavigationSuiteScaffoldState()
    val hideSuite = horizontalBar && (backStack.last() is Route.AddMiner || backStack.last() is Route.MinerDetail)
    LaunchedEffect(hideSuite, suiteType) { if (hideSuite) suiteState.hide() else suiteState.show() }

    val listDetail = rememberListDetailSceneStrategy<Route>()
    // Scroll position and filters of each tab survive switching tabs (the back stack drops the tab itself).
    val tabStates = rememberSaveableStateHolder()

    CompositionLocalProvider(LocalNetworkGate provides gate) { Row(Modifier.fillMaxSize()) {
        if (compactHeight) CompactRail(currentTab, ::selectTab)
        NavigationSuiteScaffold(
            modifier = Modifier.weight(1f).then(
                if (compactHeight) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier
            ),
            navigationSuiteType = suiteType,
            state = suiteState,
            navigationItems = {
                Route.tabs.forEach { tab ->
                    NavigationSuiteItem(
                        selected = currentTab == tab,
                        onClick = { selectTab(tab) },
                        icon = { Icon(tab.tabIcon, null) },
                        // One line; shrinks at large font sizes instead of breaking the word.
                        label = { Text(tab.tabTitle, maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 12.sp)) },
                        navigationSuiteType = suiteType
                    )
                }
            }
        ) {
            Box(Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    onBack = ::pop,
                    sceneStrategies = listOf(listDetail),
                    entryProvider = entryProvider {
                        entry<Route.Overview> {
                            tabStates.SaveableStateProvider("overview") {
                                DashboardScreen(store,
                                    onOpenScanner = { push(Route.AddMiner(startOnScan = it)) },
                                    onOpenMiners = { selectTab(Route.Miners) },
                                    onOpenMaintenance = { push(Route.Maintenance) },
                                    onOpenReports = { push(Route.Reports) })
                            }
                        }
                        entry<Route.Miners>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { MinerDetailPlaceholder() })) {
                            tabStates.SaveableStateProvider("miners") {
                                MinersScreen(store,
                                    onOpenMiner = { push(Route.MinerDetail(it)) },
                                    onAdd = { push(Route.AddMiner(startOnScan = it)) })
                            }
                        }
                        entry<Route.Pools> { tabStates.SaveableStateProvider("pools") { PoolsScreen(store, onOpenMiner = { push(Route.MinerDetail(it)) }) } }
                        entry<Route.Power> { tabStates.SaveableStateProvider("power") { PowerScreen(store, onOpenSettings = { selectTab(Route.Settings) }) } }
                        entry<Route.Settings> { tabStates.SaveableStateProvider("settings") { SettingsScreen(store) } }
                        entry<Route.MinerDetail>(metadata = ListDetailSceneStrategy.detailPane()) { r ->
                            MinerDetailScreen(store, r.minerId, showBack = true, onBack = ::pop, onOpenSettings = { selectTab(Route.Settings) })
                        }
                        entry<Route.Maintenance> { MaintenanceScreen(store, onBack = ::pop) }
                        entry<Route.Reports> { ReportsScreen(store, onBack = ::pop) }
                        entry<Route.AddMiner> { r -> AddMinerScreen(store, startOnScan = r.startOnScan, onClose = ::pop) }
                    }
                )
                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .imePadding()
                ) { AppSnackbar(it) }
            }
        }
    } }
}
