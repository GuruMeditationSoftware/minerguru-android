package app.minerguru.ui.power

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import app.minerguru.R
import app.minerguru.model.*
import app.minerguru.service.PowerCenter
import app.minerguru.ui.components.AppFilterChip
import app.minerguru.ui.components.AppTextField
import app.minerguru.ui.components.ConnectedToggleGroup

// region Shared controls

/** Seven day chips, Monday first, with shortcuts for every day, workdays and the weekend. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeekdayPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..7).forEach { day ->
                AppFilterChip(selected = day in days, onClick = { onChange(if (day in days) days - day else days + day) },
                    label = { Text(Weekdays.shortName(day)) })
            }
        }
        Row {
            TextButton(onClick = { onChange(Weekdays.all) }) { Text(tr(R.string.daily)) }
            TextButton(onClick = { onChange(Weekdays.workdays) }) { Text(tr(R.string.mon_fri)) }
            TextButton(onClick = { onChange(Weekdays.weekend) }) { Text(tr(R.string.weekend)) }
        }
    }
}

/** A time of day in 15-minute steps, picked from a menu. */
@Composable
internal fun TimeField(label: String, minute: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) { Text("$label ${WeeklyWindow.clock(minute)}") }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            (0 until WeeklyWindow.MINUTES_PER_DAY step 15).forEach { m ->
                DropdownMenuItem(text = { Text(WeeklyWindow.clock(m)) }, onClick = { onChange(m); open = false })
            }
        }
    }
}

@Composable
private fun TimeWindowFields(window: WeeklyWindow, onChange: (WeeklyWindow) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeField(tr(R.string.from), window.startMinute) { onChange(window.copy(startMinute = it)) }
            TimeField(tr(R.string.to_in_text), window.endMinute) { onChange(window.copy(endMinute = it)) }
        }
        val note = when {
            window.isWholeDay -> tr(R.string.same_time_twice_applies_all)
            window.wrapsMidnight -> tr(R.string.runs_past_midnight_into_the)
            else -> null
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// endregion

// region Zone editor

@Composable
internal fun ZoneEditorDialog(zone: TariffZone, isNew: Boolean, currencyCode: String, onDismiss: () -> Unit, onSave: (TariffZone) -> Unit) {
    var draft by remember { mutableStateOf(zone) }
    val valid = draft.name.isNotBlank() && draft.window.weekdays.isNotEmpty() && draft.pricePerKWh >= 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr(R.string.new_tariff_zone) else tr(R.string.edit_tariff_zone)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text(tr(R.string.name)) },
                    placeholder = { Text(tr(R.string.e_g_night)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PriceField(draft.pricePerKWh, tr(R.string.price_per_kwh_in, currencyCode), Modifier.fillMaxWidth()) {
                    draft = draft.copy(pricePerKWh = it)
                }
                Text(tr(R.string.days), style = MaterialTheme.typography.labelLarge)
                WeekdayPicker(draft.window.weekdays) { draft = draft.copy(window = draft.window.copy(weekdays = it)) }
                Text(tr(R.string.time), style = MaterialTheme.typography.labelLarge)
                TimeWindowFields(draft.window) { draft = draft.copy(window = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft.copy(name = draft.name.trim())) }, enabled = valid) { Text(tr(R.string.save_entry)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } }
    )
}

// endregion

// region Schedule editor

@Composable
internal fun RuleEditorDialog(
    rule: ScheduleRule, isNew: Boolean, zones: List<TariffZone>, miners: List<MinerDevice>,
    onDismiss: () -> Unit, onSave: (ScheduleRule) -> Unit
) {
    var draft by remember { mutableStateOf(rule) }
    val valid = when (val t = draft.trigger) {
        is ScheduleTrigger.Zone -> zones.any { it.id == t.zoneId }
        is ScheduleTrigger.Window -> t.window.weekdays.isNotEmpty()
    }
    val actions = listOf(PowerAction.NIGHT_MODE, PowerAction.PAUSE, PowerAction.NORMAL)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr(R.string.new_schedule) else tr(R.string.edit_schedule)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(R.string.when_label), style = MaterialTheme.typography.labelLarge)
                val kinds = if (zones.isEmpty()) listOf(tr(R.string.time)) else listOf(tr(R.string.tariff_zone), tr(R.string.time))
                val isZone = draft.trigger is ScheduleTrigger.Zone
                ConnectedToggleGroup(kinds, if (zones.isEmpty() || !isZone) kinds.lastIndex else 0, onSelect = { i ->
                    draft = draft.copy(trigger = if (i == 0 && zones.isNotEmpty()) ScheduleTrigger.Zone(zones.first().id)
                    else ScheduleTrigger.Window(WeeklyWindow(Weekdays.all, 22 * 60, 6 * 60)))
                }, modifier = Modifier.fillMaxWidth(), compact = true)

                when (val t = draft.trigger) {
                    is ScheduleTrigger.Zone -> {
                        Column {
                            zones.forEach { zone ->
                                AppFilterChip(selected = zone.id == t.zoneId, onClick = { draft = draft.copy(trigger = ScheduleTrigger.Zone(zone.id)) },
                                    label = { Text("${zone.name} · ${Weekdays.summary(zone.window.weekdays)} ${zone.window.timeRangeText}") })
                            }
                            Text(tr(R.string.if_the_zone_changes_the),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is ScheduleTrigger.Window -> {
                        WeekdayPicker(t.window.weekdays) { draft = draft.copy(trigger = ScheduleTrigger.Window(t.window.copy(weekdays = it))) }
                        TimeWindowFields(t.window) { draft = draft.copy(trigger = ScheduleTrigger.Window(it)) }
                    }
                }

                Text(tr(R.string.action), style = MaterialTheme.typography.labelLarge)
                ConnectedToggleGroup(actions.map { it.title }, actions.indexOf(draft.action), onSelect = { draft = draft.copy(action = actions[it]) },
                    modifier = Modifier.fillMaxWidth(), compact = true)
                if (draft.action == PowerAction.NORMAL) {
                    Text(tr(R.string.normal_keeps_miners_on_their),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(tr(R.string.miners), style = MaterialTheme.typography.labelLarge)
                CheckRow(tr(R.string.all_miners), draft.minerIds.isEmpty()) { all ->
                    draft = draft.copy(minerIds = if (all) emptySet() else miners.map { it.id }.toSet())
                }
                if (draft.minerIds.isNotEmpty()) {
                    miners.forEach { miner ->
                        val note = if (draft.action == PowerAction.PAUSE && miner.latestInfo != null && !PowerCenter.canPause(miner))
                            tr(R.string.cannot_pause_night_mode) else ""
                        CheckRow(miner.displayName + note, miner.id in draft.minerIds) { on ->
                            val ids = if (on) draft.minerIds + miner.id else draft.minerIds - miner.id
                            if (ids.isNotEmpty()) draft = draft.copy(minerIds = ids)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }, enabled = valid) { Text(tr(R.string.save_entry)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } }
    )
}

/** "All miners" or a selection; an empty set means all. */
@Composable
internal fun MinerScopeChecks(ids: Set<String>, miners: List<MinerDevice>, onChange: (Set<String>) -> Unit) {
    Text(tr(R.string.miners), style = MaterialTheme.typography.labelLarge)
    CheckRow(tr(R.string.all_miners), ids.isEmpty()) { all -> onChange(if (all) emptySet() else miners.map { it.id }.toSet()) }
    if (ids.isNotEmpty()) {
        miners.forEach { miner ->
            CheckRow(miner.displayName, miner.id in ids) { on ->
                val updated = if (on) ids + miner.id else ids - miner.id
                if (updated.isNotEmpty()) onChange(updated)
            }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// endregion
