package app.minerguru.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import app.minerguru.R
import app.minerguru.model.tr
import app.minerguru.service.MinerStore
import kotlinx.coroutines.launch

// region Screen scaffolding

/** True in windows with little height, such as a phone in landscape. */
@Composable
fun isCompactHeight(): Boolean =
    !currentWindowAdaptiveInfo().windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)

/**
 * Scaffold for a page: a top app bar that changes its color while content scrolls under it
 * (or collapses, for a [large] title), and the inner padding handed to the content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    // A large title would fill most of a landscape phone.
    val largeTitle = large && !isCompactHeight()
    val scrollBehavior = if (largeTitle) TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    else TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (largeTitle) LargeTopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = navigationIcon, actions = actions,
                scrollBehavior = scrollBehavior)
            else TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = navigationIcon, actions = actions,
                scrollBehavior = scrollBehavior)
        },
        floatingActionButton = floatingActionButton,
        content = content
    )
}

/** Inner padding without the top part (for content placed below the app bar). */
@Composable
fun PaddingValues.withoutTop(): PaddingValues {
    val dir = LocalLayoutDirection.current
    return PaddingValues(start = calculateStartPadding(dir), end = calculateEndPadding(dir), bottom = calculateBottomPadding())
}

/**
 * Lays out page content: pull-to-refresh (when [onRefresh] is set) below the app bar, and padding that keeps the content
 * at most [maxWidth] wide and centered on large screens while it still scrolls behind the transparent navigation bar.
 */
@Composable
fun PageContent(
    innerPadding: PaddingValues,
    maxWidth: Dp = 840.dp,
    onRefresh: (suspend () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val dir = LocalLayoutDirection.current
    val body: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = max(16.dp, (this.maxWidth - maxWidth) / 2)
            content(
                PaddingValues(
                    start = side + innerPadding.calculateStartPadding(dir),
                    end = side + innerPadding.calculateEndPadding(dir),
                    top = 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp
                )
            )
        }
    }
    val top = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())
    if (onRefresh == null) {
        androidx.compose.foundation.layout.Box(top) { body() }
    } else {
        var refreshing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; try { onRefresh() } finally { refreshing = false } } },
            modifier = top
        ) { body() }
    }
}

/** Number of grid columns that fit [width] with cells of at least [minCell]. */
fun gridColumns(width: Dp, minCell: Dp, min: Int = 1, max: Int = 4): Int =
    (width / minCell).toInt().coerceIn(min, max)

// endregion

// region Snackbar

/** Snackbar content with an error flag, so errors get the error colors instead of a text marker. */
class AppSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
    override val actionLabel: String? = null
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = isError
    override val duration: SnackbarDuration get() = if (isError || actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
}

@Composable
fun AppSnackbar(data: SnackbarData) {
    val isError = (data.visuals as? AppSnackbarVisuals)?.isError == true
    if (isError) {
        Snackbar(
            data,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            actionColor = MaterialTheme.colorScheme.error,
            dismissActionContentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    } else {
        Snackbar(data)
    }
}

// endregion

// region Local network permission

/**
 * Asks for the local network permission (Android 17) right before an action that needs it,
 * with an explanation first, and leads to the app settings once the user has declined it for good.
 */
class NetworkAccessGate internal constructor() {
    internal var pending: (() -> Unit)? = null
    internal var showRationale by mutableStateOf(false)
    internal var showSettingsHint by mutableStateOf(false)
    internal var granted: () -> Boolean = { true }

    /** Runs [action] now if access is granted, otherwise after the user allowed it. */
    fun run(action: () -> Unit) {
        if (granted()) { action(); return }
        pending = action
        showRationale = true
    }

    /** Asks for access without a follow-up action. */
    fun request() = run {}
}

val LocalNetworkGate = staticCompositionLocalOf { NetworkAccessGate() }

@Composable
fun rememberNetworkAccessGate(store: MinerStore): NetworkAccessGate {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val gate = remember { NetworkAccessGate() }
    gate.granted = { store.localNetworkGranted }
    var deniedForGood by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        store.refreshLocalNetworkPermission()
        if (granted) {
            gate.pending?.invoke()
        } else if (activity?.shouldShowRequestPermissionRationale(MinerStore.LOCAL_NETWORK_PERMISSION) == false) {
            deniedForGood = true
            gate.showSettingsHint = true
        } else {
            store.showBanner(deniedHint(), false)
        }
        gate.pending = null
    }

    if (gate.showRationale) AlertDialog(
        onDismissRequest = { gate.showRationale = false; gate.pending = null },
        icon = { Icon(Icons.Rounded.Lan, null) },
        title = { Text(tr(R.string.access_to_the_local_network)) },
        text = {
            Text(tr(R.string.minerguru_talks_directly_to_your))
        },
        confirmButton = {
            TextButton(onClick = {
                gate.showRationale = false
                if (deniedForGood) gate.showSettingsHint = true else launcher.launch(MinerStore.LOCAL_NETWORK_PERMISSION)
            }) { Text(tr(R.string.continue_label)) }
        },
        dismissButton = {
            TextButton(onClick = { gate.showRationale = false; gate.pending = null; store.showBanner(deniedHint(), false) }) {
                Text(tr(R.string.not_now))
            }
        }
    )
    if (gate.showSettingsHint) AlertDialog(
        onDismissRequest = { gate.showSettingsHint = false },
        icon = { Icon(Icons.Rounded.Lan, null) },
        title = { Text(tr(R.string.allow_access_in_settings)) },
        text = {
            Text(tr(R.string.access_to_the_local_network_was))
        },
        confirmButton = {
            TextButton(onClick = {
                gate.showSettingsHint = false
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text(tr(R.string.open_settings)) }
        },
        dismissButton = { TextButton(onClick = { gate.showSettingsHint = false }) { Text(tr(R.string.cancel)) } }
    )
    return gate
}

private fun deniedHint() = tr(R.string.without_access_to_the_local)

/** Shown on the overview and miner list while the permission is missing. */
@Composable
fun NetworkAccessCard(modifier: Modifier = Modifier) {
    val gate = LocalNetworkGate.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lan, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(tr(R.string.no_access_to_the_local), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                tr(R.string.without_this_permission_minerguru_cannot),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { gate.request() }) { Text(tr(R.string.allow_access)) }
        }
    }
}

// endregion
