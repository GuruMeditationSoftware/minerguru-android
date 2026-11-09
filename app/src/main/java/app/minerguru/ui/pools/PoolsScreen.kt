package app.minerguru.ui.pools

import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.platform.LocalLayoutDirection
import app.minerguru.R
import app.minerguru.service.PoolEditor
import app.minerguru.ui.fleet.RefreshAllMenu
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.minerguru.model.*
import app.minerguru.service.MinerStore
import app.minerguru.ui.components.*
import app.minerguru.ui.theme.MG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Pools tab: live connections of all miners, and the pool configuration (library + slot assignment). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoolsScreen(store: MinerStore, onOpenMiner: (String) -> Unit) {
    var section by rememberSaveable { mutableStateOf(0) }
    val tabStates = rememberSaveableStateHolder()
    ScreenScaffold(tr(R.string.pools), actions = { PrivacyToggle(store); RefreshAllMenu(store) }) { inner ->
        PageContent(inner, maxWidth = if (section == 0) 840.dp else 1280.dp, onRefresh = { store.refreshAllMiners() }) { pad ->
            val dir = LocalLayoutDirection.current
            Column(Modifier.fillMaxSize()) {
                ConnectedToggleGroup(
                    listOf(tr(R.string.connections), tr(R.string.configuration)), section, { section = it },
                    Modifier.fillMaxWidth().padding(start = pad.calculateStartPadding(dir), end = pad.calculateEndPadding(dir), top = 8.dp, bottom = 8.dp)
                )
                val listPadding = PaddingValues(start = pad.calculateStartPadding(dir), end = pad.calculateEndPadding(dir), bottom = pad.calculateBottomPadding())
                tabStates.SaveableStateProvider(section) {
                    if (section == 0) PoolConnections(store, onOpenMiner, listPadding) else PoolConfiguration(store, listPadding)
                }
            }
        }
    }
}

// region Connections

@Composable
private fun PoolConnections(store: MinerStore, onOpenMiner: (String) -> Unit, contentPadding: PaddingValues) {
    if (store.miners.isEmpty()) {
        EmptyHint(tr(R.string.no_miners_yet_add_a))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(tr(R.string.live_comparison_of_all_miners),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(store.miners, key = { it.id }) { miner -> PoolRowCard(miner, store.tempWarningThresholdC, Modifier.animateItem()) { onOpenMiner(miner.id) } }
    }
}

@Composable
private fun PoolRowCard(miner: MinerDevice, tempWarningC: Double, modifier: Modifier, onClick: () -> Unit) {
    val info = miner.latestInfo
    SectionCard(modifier, onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(miner.connectionState, (info?.temp ?: 0.0) >= tempWarningC)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(miner.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Privacy.ip(miner.ipAddress), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (info != null) PoolModeBadge(info) else Text("Offline", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (info == null) return@Column
            HorizontalDivider()
            if (info.isDualPoolMining) {
                PoolLine(info.primaryPoolCoin, info.dualPoolPrimaryPercent, info.primaryPoolEndpoint, info.stratumUser,
                    info.primaryPoolSessionBestDiff, miner.primaryPoolEffectiveLatencyMs)
                PoolLine(info.secondaryPoolCoin, info.dualPoolSecondaryPercent, info.fallbackPoolEndpoint, info.fallbackStratumUser,
                    info.secondaryPoolSessionBestDiff, miner.secondaryPoolEffectiveLatencyMs)
            } else {
                PoolLine(info.activePoolCoin, null, info.activePoolEndpoint, info.activePoolUser, info.sessionBestDiff, miner.activePoolLatencyMs)
            }
        }
    }
}

@Composable
private fun PoolLine(coin: MinedCoin, percent: Int?, endpoint: String, user: String, bestDiff: Double?, latencyMs: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoinBadge(coin, percent)
            Text(endpoint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            LatencyBadge(latencyMs)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Privacy.user(user).ifEmpty { "—" }, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Best: ${MinerFormatting.formatSessionBest(bestDiff)}", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// endregion

// region Configuration


@Composable
private fun PoolConfiguration(store: MinerStore, contentPadding: PaddingValues) {
    // Unsaved pool settings per miner live in the view model, so they survive rotation and switching tabs.
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDelete = store.poolLibrary.firstOrNull { it.id == pendingDeleteId }

    // First visit: fill the library with the pools that are already on the miners.
    LaunchedEffect(Unit) { if (store.poolLibrary.isEmpty()) store.importPoolsFromMiners() }
    LaunchedEffect(store.miners) { store.syncPoolDrafts() }

    val configurable = store.miners.filter { store.poolDrafts[it.id] != null }
    val dirty = configurable.map { it.id }.filter { store.isPoolDraftDirty(it) }

    fun assignEverywhere(pool: PoolConfig, primary: Boolean) {
        configurable.forEach { m ->
            val d = store.poolDrafts[m.id] ?: return@forEach
            store.updatePoolDraft(m.id, if (primary) d.makePrimary(pool) else d.makeFallback(pool))
        }
    }

    val library: LazyListScope.() -> Unit = {
        item {
            LibraryCard(
                store = store,
                hasMiners = configurable.isNotEmpty(),
                usage = { pool -> configurable.count { m -> store.poolBaselines[m.id]?.slots?.any { pool.matches(it) } == true } },
                onImport = {
                    val added = store.importPoolsFromMiners()
                    store.showBanner(
                        if (added == 0) tr(R.string.no_new_pools_found)
                        else trp(R.plurals.pools_count, added) + tr(R.string.added_to_the_library), false
                    )
                },
                onNew = { store.poolEditor = PoolEditor(PoolConfig.create("", ""), true) },
                onEdit = { store.poolEditor = PoolEditor(it, false) },
                onDuplicate = { store.duplicatePool(it.id)?.let { copy -> store.poolEditor = PoolEditor(copy, false) } },
                onDelete = { pendingDeleteId = it.id },
                onAssign = ::assignEverywhere
            )
        }
        item { PoolSchedulesCard(store) }
    }
    val assignments: LazyListScope.() -> Unit = {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.assignment_to_miners), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { store.applyPoolDrafts(dirty) },
                    enabled = dirty.isNotEmpty() && dirty.none { store.poolDrafts[it]?.poolValidationMessage != null }
                ) { Text(tr(R.string.apply_all) + " (${dirty.size})") }
            }
        }
        if (configurable.isEmpty()) {
            item { EmptyHint(tr(R.string.as_soon_as_a_miner)) }
        }
        items(configurable, key = { it.id }) { miner ->
            MinerPoolAssignmentCard(
                miner = miner,
                library = store.poolLibrary,
                form = store.poolDrafts[miner.id] ?: MinerSettingsForm(),
                onForm = { store.updatePoolDraft(miner.id, it) },
                isDirty = store.isPoolDraftDirty(miner.id),
                isApplying = store.poolApplying[miner.id] == true,
                errorMessage = store.poolErrors[miner.id],
                onApply = { store.applyPoolDrafts(listOf(miner.id)) },
                onReset = { store.reloadPoolDraft(miner.id, true) },
                onSaveToLibrary = { endpoint ->
                    store.poolEditor = PoolEditor(
                        PoolConfig.fromEndpoint(PoolConfig.suggestedName(endpoint.host, endpoint.port, endpoint.user), endpoint), true
                    )
                },
                modifier = Modifier.animateItem()
            )
        }
    }

    // Wide windows: library on the left, assignment to the miners on the right.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(Modifier.weight(0.42f).fillMaxHeight(), contentPadding = contentPadding.onlyStart(), verticalArrangement = Arrangement.spacedBy(12.dp), content = library)
                LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), contentPadding = contentPadding.onlyEnd(), verticalArrangement = Arrangement.spacedBy(12.dp), content = assignments)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                library()
                assignments()
            }
        }
    }

    store.poolEditor?.let { e ->
        PoolConfigEditorDialog(e.config, e.isNew, onDismiss = { store.poolEditor = null }) { saved ->
            if (e.isNew) store.addPool(saved) else store.updatePool(saved)
        }
    }
    pendingDelete?.let { pool ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(tr(R.string.delete_pool_configuration)) },
            text = {
                Text(tr(R.string.is_only_removed_from_the, pool.name))
            },
            confirmButton = {
                TextButton(onClick = { store.removePool(pool.id); pendingDeleteId = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text(tr(R.string.cancel)) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryCard(
    store: MinerStore,
    hasMiners: Boolean,
    usage: (PoolConfig) -> Int,
    onImport: () -> Unit,
    onNew: () -> Unit,
    onEdit: (PoolConfig) -> Unit,
    onDuplicate: (PoolConfig) -> Unit,
    onDelete: (PoolConfig) -> Unit,
    onAssign: (PoolConfig, Boolean) -> Unit
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(tr(R.string.pool_library), Icons.Rounded.Storage, "${store.poolLibrary.size}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onImport) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.read_from_miners))
                }
                FilledTonalButton(onClick = onNew) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.new_pool))
                }
            }
            if (store.poolLibrary.isEmpty()) {
                Text(tr(R.string.no_pools_saved_yet_read),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Pools as a grouped list of rounded segments instead of rows with dividers.
            Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                store.poolLibrary.forEachIndexed { index, pool ->
                    AppCard(
                        Modifier.fillMaxWidth(), shape = segmentShape(index, store.poolLibrary.size, outer = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Box(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                            LibraryRow(pool, usage(pool), hasMiners, onEdit, onDuplicate, onDelete, onAssign)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    pool: PoolConfig, usage: Int, hasMiners: Boolean,
    onEdit: (PoolConfig) -> Unit, onDuplicate: (PoolConfig) -> Unit, onDelete: (PoolConfig) -> Unit, onAssign: (PoolConfig, Boolean) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CoinBadge(MinedCoin.detect(pool.host, pool.user))
        Column(Modifier.weight(1f)) {
            Text(pool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (pool.user.isEmpty()) pool.hostAndPort else "${pool.hostAndPort} • ${Privacy.user(pool.user)}",
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (usage > 0) Text("$usage Miner", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr(R.string.actions)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(tr(R.string.as_primary_pool_on_all)) },
                    enabled = hasMiners, onClick = { menu = false; onAssign(pool, true) })
                DropdownMenuItem(text = { Text(tr(R.string.as_fallback_pool_2_on)) },
                    enabled = hasMiners, onClick = { menu = false; onAssign(pool, false) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(tr(R.string.edit)) }, onClick = { menu = false; onEdit(pool) })
                DropdownMenuItem(text = { Text(tr(R.string.duplicate)) }, onClick = { menu = false; onDuplicate(pool) })
                DropdownMenuItem(text = { Text(tr(R.string.delete)) }, onClick = { menu = false; onDelete(pool) })
            }
        }
    }
}

/**
 * Pool slots of one miner: library pool per slot, primary / fallback selection (AxeOS 2.15+)
 * and dual-pool split (NerdOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinerPoolAssignmentCard(
    miner: MinerDevice,
    library: List<PoolConfig>,
    form: MinerSettingsForm,
    onForm: (MinerSettingsForm) -> Unit,
    isDirty: Boolean,
    isApplying: Boolean,
    errorMessage: String?,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onSaveToLibrary: (MinerSettingsForm.PoolEndpoint) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDual = miner.supportsDualPool && form.dualPool
    SectionCard(modifier, highlight = if (isDirty) MaterialTheme.colorScheme.primary else null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionDot(miner.connectionState)
                Column(Modifier.weight(1f)) {
                    Text(miner.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Privacy.ip(miner.ipAddress) + " • " + when {
                            form.poolApi == MinerSettingsForm.PoolApi.SLOTS -> "${AxeOSPool.SLOT_COUNT} " + tr(R.string.pool_slots)
                            miner.supportsDualPool -> tr(R.string.failover_or_dual_pool)
                            else -> tr(R.string.primary_fallback)
                        },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HardwareBadge(miner.hardwareTitle)
            }

            if (miner.supportsDualPool) DualPoolControls(form, onForm)

            form.visibleSlots.forEach { index -> SlotRow(miner, library, form, index, isDual, onForm, onSaveToLibrary) }

            if (form.poolApi == MinerSettingsForm.PoolApi.SLOTS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlotRolePicker(tr(R.string.primary), form, library, form.primarySlot, Modifier.weight(1f)) {
                        onForm(form.copy(primarySlot = it))
                    }
                    SlotRolePicker("Fallback", form, library, form.secondarySlot, Modifier.weight(1f)) {
                        onForm(form.copy(secondarySlot = it))
                    }
                }
            }

            if (isDirty) form.poolValidationMessage?.let { StatusMessage(it, true) }
            if (isDirty) form.poolCoinWarning?.let { StatusMessage(it, true) }
            errorMessage?.let { StatusMessage(it, true) }
            if (form.slotsToClear.isNotEmpty()) {
                Text(tr(R.string.will_be_cleared) + form.slotsToClear.joinToString(", ") { form.slotTitle(it) },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset, enabled = isDirty && !isApplying) { Text(tr(R.string.reset)) }
                Button(
                    onClick = onApply,
                    enabled = isDirty && !isApplying && form.poolValidationMessage == null && miner.connectionState.isOnline
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    }
                    Text(tr(R.string.apply_restart))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualPoolControls(form: MinerSettingsForm, onForm: (MinerSettingsForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ConnectedToggleGroup(listOf("Failover", "Dual-Pool"), if (form.dualPool) 1 else 0, { onForm(form.copy(dualPool = it == 1)) },
            Modifier.fillMaxWidth())
        if (form.dualPool) {
            Row {
                Text("P1 ${form.primaryPercent}%", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(tr(R.string.hashrate_split), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("P2 ${form.secondaryPercent}%", fontWeight = FontWeight.SemiBold)
            }
            Slider(value = form.poolBalance, onValueChange = { onForm(form.copy(poolBalance = it)) }, valueRange = 1f..99f,
                modifier = Modifier.semantics { contentDescription = tr(R.string.hashrate_split) })
        }
    }
}

@Composable
private fun SlotRow(
    miner: MinerDevice, library: List<PoolConfig>, form: MinerSettingsForm, index: Int, isDual: Boolean,
    onForm: (MinerSettingsForm) -> Unit, onSaveToLibrary: (MinerSettingsForm.PoolEndpoint) -> Unit
) {
    val endpoint = form.slots[index]
    val label = when (form.poolApi) {
        MinerSettingsForm.PoolApi.SLOTS -> form.slotTitle(index)
        MinerSettingsForm.PoolApi.LEGACY -> if (index == 0) (if (isDual) "Pool 1" else tr(R.string.primary)) else (if (isDual) "Pool 2" else "Fallback")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (endpoint.hasContent) CoinBadge(slotCoin(miner, form, index))
            Spacer(Modifier.weight(1f))
            when {
                form.poolApi == MinerSettingsForm.PoolApi.SLOTS && index == form.primarySlot -> RoleBadge(tr(R.string.primary), MG.green())
                form.poolApi == MinerSettingsForm.PoolApi.SLOTS && index == form.secondarySlot -> RoleBadge("Fallback", MG.orange())
                form.poolApi == MinerSettingsForm.PoolApi.SLOTS && endpoint.hasContent -> RoleBadge("Reserve", MaterialTheme.colorScheme.onSurfaceVariant)
                form.poolApi == MinerSettingsForm.PoolApi.LEGACY && isDual ->
                    RoleBadge("${if (index == 0) form.primaryPercent else form.secondaryPercent}%", MG.blue())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LibraryPoolPicker(library, endpoint, Modifier.weight(1f)) { cfg ->
                onForm(if (cfg != null) form.assign(cfg, index) else form.clearSlot(index))
            }
            if (endpoint.hasContent && library.none { it.matches(endpoint) }) {
                IconButton(onClick = { onSaveToLibrary(endpoint) }) {
                    Icon(Icons.Rounded.SaveAlt, tr(R.string.save_to_library))
                }
            }
        }
    }
}

/** Coin from pool host and wallet; the miner's network difficulty helps only while the slot holds the pool it is connected to. */
fun slotCoin(miner: MinerDevice, form: MinerSettingsForm, index: Int): MinedCoin {
    val endpoint = form.slots[index]
    val host = StratumHost.clean(endpoint.host).lowercase()
    val info = miner.latestInfo
    val difficulty = when {
        info == null -> null
        index == form.primarySlot && host == StratumHost.clean(info.stratumURL).lowercase() -> info.primaryPoolStatus?.networkDifficulty
        index == form.secondarySlot && host == StratumHost.clean(info.fallbackStratumURL).lowercase() -> info.secondaryPoolStatus?.networkDifficulty
        else -> null
    }
    return MinedCoin.detect(endpoint.host, endpoint.user, difficulty)
}

// endregion

@Composable
private fun PaddingValues.onlyStart(): PaddingValues =
    PaddingValues(start = calculateStartPadding(LocalLayoutDirection.current), bottom = calculateBottomPadding())

@Composable
private fun PaddingValues.onlyEnd(): PaddingValues =
    PaddingValues(end = calculateEndPadding(LocalLayoutDirection.current), bottom = calculateBottomPadding())
