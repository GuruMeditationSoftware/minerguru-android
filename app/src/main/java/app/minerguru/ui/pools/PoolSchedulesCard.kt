package app.minerguru.ui.pools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.R
import app.minerguru.model.tr
import app.minerguru.model.MinerDevice
import app.minerguru.model.PoolConfig
import app.minerguru.model.PoolScheduleRule
import app.minerguru.model.Weekdays
import app.minerguru.model.WeeklyWindow
import app.minerguru.service.MinerStore
import app.minerguru.service.PowerCenter
import app.minerguru.ui.components.AppFilterChip
import app.minerguru.ui.components.SectionCard
import app.minerguru.ui.components.StatusMessage
import app.minerguru.ui.power.MinerScopeChecks
import app.minerguru.ui.power.TimeField
import app.minerguru.ui.power.WeekdayPicker
import app.minerguru.ui.theme.MGType
import androidx.compose.ui.platform.LocalContext

/** Switches miners to a saved pool at set times, for example to a lottery pool on weekend nights. */
@Composable
fun PoolSchedulesCard(store: MinerStore) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<PoolScheduleRule?>(null) }
    val rules = store.poolSchedules.sortedBy { it.minute }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(R.string.scheduled_pool_switching), style = MaterialTheme.typography.titleSmall)
            if (rules.isEmpty()) {
                Text(tr(R.string.move_miners_automatically_for_example),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rules.forEachIndexed { index, rule ->
                if (index > 0) HorizontalDivider()
                RuleRow(store, rule, onEdit = { editing = rule })
            }
            if (rules.isNotEmpty() && !PowerCenter.isSwitching(context)) {
                Text(tr(R.string.the_phone_only_switches_with),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            app.minerguru.ui.support.OtpWaitingNotice(store)
            store.poolScheduleError?.let { StatusMessage(tr(R.string.last_pool_switch_failed, it), true) }
            FilledTonalButton(
                onClick = { store.poolLibrary.firstOrNull()?.let { editing = PoolScheduleRule(poolId = it.id) } },
                enabled = store.poolLibrary.isNotEmpty() && store.miners.isNotEmpty()
            ) { Text(tr(R.string.schedule_a_switch)) }
        }
    }
    editing?.let { rule ->
        RuleEditor(rule, isNew = store.poolSchedules.none { it.id == rule.id }, pools = store.poolLibrary, miners = store.miners, onDismiss = { editing = null }) { saved ->
            store.updatePoolSchedules(if (store.poolSchedules.any { it.id == saved.id }) store.poolSchedules.map { if (it.id == saved.id) saved else it } else store.poolSchedules + saved)
            editing = null
        }
    }
}

@Composable
private fun RuleRow(store: MinerStore, rule: PoolScheduleRule, onEdit: () -> Unit) {
    val pool = store.poolLibrary.firstOrNull { it.id == rule.poolId }?.name ?: tr(R.string.pool_deleted)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(rule.isEnabled, { on -> store.updatePoolSchedules(store.poolSchedules.map { if (it.id == rule.id) it.copy(isEnabled = on) else it }) })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${Weekdays.summary(rule.weekdays)} ${WeeklyWindow.clock(rule.minute)} → $pool", style = MaterialTheme.typography.bodyMedium)
            val miners = if (rule.minerIds.isEmpty()) tr(R.string.all_miners) else store.miners.filter { it.id in rule.minerIds }.joinToString { it.displayName }.ifEmpty { tr(R.string.miner_removed) }
            Text(miners, style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, tr(R.string.edit)) }
        IconButton(onClick = { store.updatePoolSchedules(store.poolSchedules.filterNot { it.id == rule.id }) }) {
            Icon(Icons.Rounded.Delete, tr(R.string.delete_switch))
        }
    }
}

@Composable
private fun RuleEditor(rule: PoolScheduleRule, isNew: Boolean, pools: List<PoolConfig>, miners: List<MinerDevice>, onDismiss: () -> Unit, onSave: (PoolScheduleRule) -> Unit) {
    var draft by remember { mutableStateOf(rule) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr(R.string.schedule_a_pool_switch) else tr(R.string.edit_pool_switch)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pool", style = MaterialTheme.typography.labelLarge)
                pools.forEach { pool ->
                    AppFilterChip(selected = pool.id == draft.poolId, onClick = { draft = draft.copy(poolId = pool.id) }, label = { Text(pool.name) })
                }
                Text(tr(R.string.days), style = MaterialTheme.typography.labelLarge)
                WeekdayPicker(draft.weekdays) { draft = draft.copy(weekdays = it) }
                TimeField(tr(R.string.time), draft.minute) { draft = draft.copy(minute = it) }
                MinerScopeChecks(draft.minerIds, miners) { draft = draft.copy(minerIds = it) }
                Text(tr(R.string.the_miner_gets_the_pool),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (isNew) draft.copy(createdAt = System.currentTimeMillis()) else draft) }, enabled = draft.weekdays.isNotEmpty()) { Text(tr(R.string.save_entry)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } }
    )
}
