package app.minerguru.ui.miner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.model.BlockTemplateSummary
import app.minerguru.model.BlockTemplateSummary.PayoutState
import app.minerguru.model.CoinbaseOutput
import app.minerguru.model.DisplayFormat
import app.minerguru.model.L
import app.minerguru.model.Privacy
import app.minerguru.service.BlockProbeStore
import app.minerguru.service.BlockProbeTarget
import app.minerguru.ui.components.CardGrid
import app.minerguru.ui.components.SectionCard
import app.minerguru.ui.theme.MG
import app.minerguru.ui.theme.MGType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.DateFormat
import java.util.Date

/**
 * The block the miner is hashing on right now, as far as the coinbase shows it:
 * height, pool tag, reward and who gets it. Comes from the firmware (AxeOS 2.13+) or the Stratum probe.
 */
@Composable
fun BlockTemplateCard(
    block: BlockTemplateSummary,
    poolLabel: String? = null,
    onOpenWebInterface: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false
) {
    SectionCard(highlight = if (block.payoutState == PayoutState.NOT_FOUND) MG.bad() else null) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BlockCardTitle(poolLabel) { PayoutStateBadge(block.payoutState) }
                Text(
                    if (block.probedAtMillis == null) L(
                        "Die Vorlage des Pools, an der dein Miner gerade rechnet. Findet er den Block, zahlt die Coinbase so aus wie hier gezeigt. Die übrigen Transaktionen kennt nur der Pool.",
                        "The pool's template your miner is hashing on right now. If it finds the block, the coinbase pays out as shown here. Only the pool knows the other transactions."
                    ) else L(
                        "Die Vorlage, die der Pool gerade für deine Adresse baut. Der Miner meldet sie nicht, deshalb hat MinerGuru sie selbst beim Pool abgefragt, ohne mitzurechnen. Findet dein Miner den Block, zahlt die Coinbase so aus wie hier gezeigt.",
                        "The template the pool is building for your address right now. The miner does not report it, so MinerGuru asked the pool itself, without mining. If your miner finds the block, the coinbase pays out as shown here."
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MG.hairline())

            val coins = { sats: Double -> BlockTemplateSummary.formatCoins(sats, block.coin) }
            val pairs = listOf(
                L("Blockhöhe", "Block height") to DisplayFormat.integer(block.height),
                L("Pool-Kennung", "Pool tag") to (block.poolTag ?: "—"),
                L("Belohnung", "Reward") to (block.totalSats?.let(coins) ?: "—"),
                L("davon Gebühren", "of which fees") to (block.feesSats?.let(coins) ?: "—"),
                L("An deine Adresse", "To your address") to userShareText(block)
            )
            SelectionContainer {
                CardGrid(pairs, minCellWidth = 140.dp, spacing = 12.dp, minColumns = 2, maxColumns = 5) { (label, value) -> BlockFigure(label, value) }
            }

            block.transactionRange?.let { range ->
                Text(
                    L("Transaktionen im Block: ", "Transactions in the block: ") + rangeText(range) + L(
                        " (aus der Tiefe des Merkle-Baums; der Pool nennt nur die Größenordnung)",
                        " (from the depth of the merkle tree; the pool only gives the order of magnitude)"
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (block.signals.isNotEmpty()) SignalsRow(block.signals)

            HorizontalDivider(color = MG.hairline())
            Outputs(block, onOpenWebInterface)

            block.probedAtMillis?.let { ProbeFooter(it, onRefresh, isRefreshing) }
        }
    }
}

@Composable
private fun BlockFigure(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MGType.mono(12.sp, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Aktueller Block", with the pool in dual pool mode; the badge moves below when the line gets too long. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockCardTitle(poolLabel: String?, trailing: @Composable () -> Unit = {}) {
    FlowRow(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            Icon(Icons.Rounded.ViewInAr, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(L("Aktueller Block", "Current block"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (poolLabel != null) {
                Text(" · $poolLabel", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        trailing()
    }
}

/** Whether the block pays the own address, as a dot and quiet text. */
@Composable
private fun PayoutStateBadge(state: PayoutState) {
    val color = when (state) {
        PayoutState.VERIFIED -> MG.good()
        PayoutState.NOT_FOUND -> MG.bad()
        else -> MG.idle()
    }
    val title = when (state) {
        PayoutState.VERIFIED -> L("Deine Adresse ist im Block", "Your address is in the block")
        PayoutState.NOT_FOUND -> L("Deine Adresse fehlt", "Your address is missing")
        PayoutState.NOT_APPLICABLE -> L("Konto-Pool", "Account pool")
        PayoutState.DECODING_OFF -> L("Nicht geprüft", "Not checked")
        PayoutState.UNKNOWN -> L("Noch keine Daten", "No data yet")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            title, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (state == PayoutState.NOT_FOUND) MG.bad() else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (state == PayoutState.NOT_FOUND) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalsRow(signals: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Text(L("Signalisiert:", "Signals:"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        signals.forEach { signal ->
            Text(
                signal, style = MGType.mono(11.sp),
                modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun Outputs(block: BlockTemplateSummary, onOpenWebInterface: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L("Empfänger der Belohnung", "Recipients of the reward"), style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            block.payoutState == PayoutState.DECODING_OFF -> DecodingOffHint(onOpenWebInterface)
            block.outputs.isEmpty() -> Text(
                L("Der Miner hat noch keine Empfänger gemeldet.", "The miner has not reported any recipients yet."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.outputs.forEach { OutputRow(it, block) }
                    if (block.othersCount > 0) {
                        InsetRow(Icons.Rounded.MoreHoriz, MaterialTheme.colorScheme.onSurfaceVariant, block.othersValueSats.takeIf { it > 0 }, block) {
                            Text(
                                L("${block.othersCount} weitere Empfänger", "${block.othersCount} more recipients"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputRow(output: CoinbaseOutput, block: BlockTemplateSummary) {
    val icon = when {
        output.isUserOutput -> Icons.Rounded.Person
        output.isDataOutput -> Icons.Rounded.DataObject
        else -> Icons.Rounded.AccountBalance
    }
    val tint = if (output.isUserOutput) MG.good() else MaterialTheme.colorScheme.onSurfaceVariant
    InsetRow(icon, tint, output.valueSats.takeIf { it > 0 }, block) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(displayText(output), style = MGType.mono(11.sp), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val note = when {
                output.isUserOutput -> L("Deine Adresse", "Your address")
                output.isWitnessCommitment -> L("SegWit-Prüfsumme, keine Auszahlung", "SegWit commitment, no payout")
                output.isDataOutput -> L("Daten, keine Auszahlung", "Data, no payout")
                else -> null
            }
            if (note != null) {
                Text(note, style = MaterialTheme.typography.labelSmall,
                    color = if (output.isUserOutput) MG.good() else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InsetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    sats: Double?,
    block: BlockTemplateSummary,
    content: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) { content() }
        if (sats != null) {
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(BlockTemplateSummary.formatCoins(sats, block.coin), style = MGType.mono(11.sp, FontWeight.Medium), maxLines = 1)
                block.totalSats?.takeIf { it > 0 }?.let { total ->
                    Text(DisplayFormat.percent(sats / total * 100, 1), style = MGType.mono(10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DecodingOffHint(onOpenWebInterface: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L("Coinbase-Dekodierung ist für den aktiven Pool aus.", "Coinbase decoding is off for the active pool."),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                L(
                    "Ohne sie meldet der Miner nicht, wer die Belohnung bekommt, und prüft deine Adresse nicht. Du kannst sie in der Weboberfläche des Miners bei den Pool-Einstellungen einschalten.",
                    "Without it the miner does not report who gets the reward and does not check your address. You can switch it on in the miner's web interface under the pool settings."
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onOpenWebInterface != null) {
                FilledTonalButton(onClick = onOpenWebInterface) { Text(L("Weboberfläche öffnen", "Open web interface")) }
            }
        }
    }
}

@Composable
private fun ProbeFooter(probedAtMillis: Long, onRefresh: (() -> Unit)?, isRefreshing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CellTower, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(probedAtMillis))
        Text(
            L("Von diesem Gerät beim Pool abgefragt, $time Uhr", "Fetched from the pool by this device at $time"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
        )
        if (isRefreshing) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (onRefresh != null) {
            TextButton(onClick = onRefresh) { Text(L("Neu abfragen", "Fetch again")) }
        }
    }
}

private fun userShareText(block: BlockTemplateSummary): String {
    val user = block.userSats?.takeIf { it > 0 } ?: return "—"
    val amount = BlockTemplateSummary.formatCoins(user, block.coin)
    val share = block.userShare ?: return amount
    return "$amount (${DisplayFormat.percent(share * 100, 1)})"
}

private fun rangeText(range: IntRange): String =
    if (range.first == range.last) DisplayFormat.integer(range.first)
    else "${DisplayFormat.integer(range.first)}–${DisplayFormat.integer(range.last)}"

private fun displayText(output: CoinbaseOutput): String = when {
    output.isWitnessCommitment -> "OP_RETURN"
    output.isDataOutput -> output.dataText?.let { "OP_RETURN $it" } ?: "OP_RETURN"
    else -> Privacy.freeText(output.address)
}

// region Probe

/**
 * Block cards for miners whose firmware reports no block data: one per pool, filled by the
 * Stratum probe while the page is open. With the probe off, a short hint where to switch it on.
 */
@Composable
fun ProbedBlockSection(probes: BlockProbeStore, targets: List<BlockProbeTarget>, isEnabled: Boolean, onOpenSettings: () -> Unit) {
    if (targets.isEmpty()) return
    if (!isEnabled) {
        ProbeOffHint(onOpenSettings)
        return
    }
    LaunchedEffect(targets) {
        // Throttled in the store; the loop only picks up the next round after five minutes.
        while (isActive) {
            targets.forEach { probes.refresh(it) }
            delay(60_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        targets.forEach { target ->
            val label = poolLabel(target, targets.size)
            val running = target in probes.running
            when (val state = probes.entries[target]?.state ?: BlockProbeStore.State.Loading) {
                is BlockProbeStore.State.Loaded -> BlockTemplateCard(
                    state.block, poolLabel = label, onRefresh = { probes.refresh(target, force = true) }, isRefreshing = running
                )
                BlockProbeStore.State.Loading -> ProbeStatusCard(label, target.host) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(L("MinerGuru fragt den Pool nach seiner Vorlage …", "MinerGuru is asking the pool for its template …"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is BlockProbeStore.State.Failed -> ProbeStatusCard(label, target.host) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = MG.warning(), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = { probes.refresh(target, force = true) }) { Text(L("Erneut versuchen", "Try again")) }
                    }
                }
            }
        }
    }
}

private fun poolLabel(target: BlockProbeTarget, count: Int): String? {
    if (count <= 1 && target.sharePercent == null) return null
    val number = if (target.slot == BlockProbeTarget.Slot.PRIMARY) 1 else 2
    return target.sharePercent?.let { "Pool $number · $it %" } ?: "Pool $number"
}

/** Card frame while the probe runs or after it failed. */
@Composable
private fun ProbeStatusCard(poolLabel: String?, host: String, content: @Composable () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BlockCardTitle(poolLabel) {
                Text(Privacy.freeText(host), style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

/** Points to the opt-in setting instead of showing nothing. */
@Composable
private fun ProbeOffHint(onOpenSettings: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ViewInAr, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L("Blockansicht", "Block view"), style = MaterialTheme.typography.titleSmall)
                Text(
                    L(
                        "Dieser Miner meldet nicht, an welchem Block er rechnet. MinerGuru kann die Vorlage selbst beim Pool abfragen und prüfen, ob deine Adresse darin steht.",
                        "This miner does not report which block it is working on. MinerGuru can ask the pool for the template itself and check whether your address is in it."
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onOpenSettings, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(L("In den Einstellungen einschalten", "Switch on in settings"))
                }
            }
        }
    }
}

// endregion
