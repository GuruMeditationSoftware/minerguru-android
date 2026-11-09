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
import app.minerguru.R
import app.minerguru.model.BlockTemplateSummary
import app.minerguru.model.BlockTemplateSummary.PayoutState
import app.minerguru.model.CoinbaseOutput
import app.minerguru.model.DisplayFormat
import app.minerguru.model.tr
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
                    if (block.probedAtMillis == null) tr(R.string.the_pool_s_template_your) else tr(R.string.the_template_the_pool_is),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MG.hairline())

            val coins = { sats: Double -> BlockTemplateSummary.formatCoins(sats, block.coin) }
            val pairs = listOf(
                tr(R.string.block_height) to DisplayFormat.integer(block.height),
                tr(R.string.pool_tag) to (block.poolTag ?: "—"),
                tr(R.string.reward) to (block.totalSats?.let(coins) ?: "—"),
                tr(R.string.of_which_fees) to (block.feesSats?.let(coins) ?: "—"),
                tr(R.string.to_your_address) to userShareText(block)
            )
            SelectionContainer {
                CardGrid(pairs, minCellWidth = 140.dp, spacing = 12.dp, minColumns = 2, maxColumns = 5) { (label, value) -> BlockFigure(label, value) }
            }

            block.transactionRange?.let { range ->
                Text(
                    tr(R.string.transactions_in_the_block) + rangeText(range) + tr(R.string.from_the_depth_of_the),
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
            Text(tr(R.string.current_block), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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
        PayoutState.VERIFIED -> tr(R.string.your_address_is_in_the)
        PayoutState.NOT_FOUND -> tr(R.string.your_address_is_missing)
        PayoutState.NOT_APPLICABLE -> tr(R.string.account_pool)
        PayoutState.DECODING_OFF -> tr(R.string.not_checked)
        PayoutState.UNKNOWN -> tr(R.string.no_data_yet)
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
        Text(tr(R.string.signals), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(tr(R.string.recipients_of_the_reward), style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            block.payoutState == PayoutState.DECODING_OFF -> DecodingOffHint(onOpenWebInterface)
            block.outputs.isEmpty() -> Text(
                tr(R.string.the_miner_has_not_reported),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.outputs.forEach { OutputRow(it, block) }
                    if (block.othersCount > 0) {
                        InsetRow(Icons.Rounded.MoreHoriz, MaterialTheme.colorScheme.onSurfaceVariant, block.othersValueSats.takeIf { it > 0 }, block) {
                            Text(
                                tr(R.string.more_recipients, block.othersCount),
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
                output.isUserOutput -> tr(R.string.your_address)
                output.isWitnessCommitment -> tr(R.string.segwit_commitment_no_payout)
                output.isDataOutput -> tr(R.string.data_no_payout)
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
            Text(tr(R.string.coinbase_decoding_is_off_for),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                tr(R.string.without_it_the_miner_does),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onOpenWebInterface != null) {
                FilledTonalButton(onClick = onOpenWebInterface) { Text(tr(R.string.open_web_interface)) }
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
            tr(R.string.fetched_from_the_pool_by, time),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
        )
        if (isRefreshing) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (onRefresh != null) {
            TextButton(onClick = onRefresh) { Text(tr(R.string.fetch_again)) }
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
                        Text(tr(R.string.minerguru_is_asking_the_pool),
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
                        else TextButton(onClick = { probes.refresh(target, force = true) }) { Text(tr(R.string.try_again)) }
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
                Text(tr(R.string.block_view), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(R.string.this_miner_does_not_report),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onOpenSettings, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(tr(R.string.switch_on_in_settings))
                }
            }
        }
    }
}

// endregion
