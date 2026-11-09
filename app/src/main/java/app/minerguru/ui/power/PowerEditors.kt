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
            TextButton(onClick = { onChange(Weekdays.all) }) { Text(L("Täglich", "Daily")) }
            TextButton(onClick = { onChange(Weekdays.workdays) }) { Text(L("Mo–Fr", "Mon–Fri")) }
            TextButton(onClick = { onChange(Weekdays.weekend) }) { Text(L("Wochenende", "Weekend")) }
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
            TimeField(L("Von", "From"), window.startMinute) { onChange(window.copy(startMinute = it)) }
            TimeField(L("bis", "to"), window.endMinute) { onChange(window.copy(endMinute = it)) }
        }
        val note = when {
            window.isWholeDay -> L("Gleiche Zeit zweimal: gilt den ganzen Tag.", "Same time twice: applies all day.")
            window.wrapsMidnight -> L("Läuft über Mitternacht in den nächsten Tag.", "Runs past midnight into the next day.")
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
        title = { Text(if (isNew) L("Neue Tarifzone", "New tariff zone") else L("Tarifzone bearbeiten", "Edit tariff zone")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text(L("Name", "Name")) },
                    placeholder = { Text(L("z. B. Nacht", "e.g. Night")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PriceField(draft.pricePerKWh, L("Preis pro kWh ($currencyCode)", "Price per kWh ($currencyCode)"), Modifier.fillMaxWidth()) {
                    draft = draft.copy(pricePerKWh = it)
                }
                Text(L("Tage", "Days"), style = MaterialTheme.typography.labelLarge)
                WeekdayPicker(draft.window.weekdays) { draft = draft.copy(window = draft.window.copy(weekdays = it)) }
                Text(L("Uhrzeit", "Time"), style = MaterialTheme.typography.labelLarge)
                TimeWindowFields(draft.window) { draft = draft.copy(window = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft.copy(name = draft.name.trim())) }, enabled = valid) { Text(L("Sichern", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Abbrechen", "Cancel")) } }
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
        title = { Text(if (isNew) L("Neuer Zeitplan", "New schedule") else L("Zeitplan bearbeiten", "Edit schedule")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L("Wann", "When"), style = MaterialTheme.typography.labelLarge)
                val kinds = if (zones.isEmpty()) listOf(L("Uhrzeit", "Time")) else listOf(L("Tarifzone", "Tariff zone"), L("Uhrzeit", "Time"))
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
                            Text(L("Ändert sich die Zone, zieht der Zeitplan mit.", "If the zone changes, the schedule follows it."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is ScheduleTrigger.Window -> {
                        WeekdayPicker(t.window.weekdays) { draft = draft.copy(trigger = ScheduleTrigger.Window(t.window.copy(weekdays = it))) }
                        TimeWindowFields(t.window) { draft = draft.copy(trigger = ScheduleTrigger.Window(it)) }
                    }
                }

                Text(L("Aktion", "Action"), style = MaterialTheme.typography.labelLarge)
                ConnectedToggleGroup(actions.map { it.title }, actions.indexOf(draft.action), onSelect = { draft = draft.copy(action = actions[it]) },
                    modifier = Modifier.fillMaxWidth(), compact = true)
                if (draft.action == PowerAction.NORMAL) {
                    Text(L("„Normal“ hält Miner bei ihren eigenen Einstellungen, auch wenn ein Zeitplan weiter unten etwas anderes will.",
                        "“Normal” keeps miners on their own settings, even if a schedule further down wants something else."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(L("Miner", "Miners"), style = MaterialTheme.typography.labelLarge)
                CheckRow(L("Alle Miner", "All miners"), draft.minerIds.isEmpty()) { all ->
                    draft = draft.copy(minerIds = if (all) emptySet() else miners.map { it.id }.toSet())
                }
                if (draft.minerIds.isNotEmpty()) {
                    miners.forEach { miner ->
                        val note = if (draft.action == PowerAction.PAUSE && miner.latestInfo != null && !PowerCenter.canPause(miner))
                            L(" (kann nicht pausieren, Nachtmodus)", " (cannot pause, night mode)") else ""
                        CheckRow(miner.displayName + note, miner.id in draft.minerIds) { on ->
                            val ids = if (on) draft.minerIds + miner.id else draft.minerIds - miner.id
                            if (ids.isNotEmpty()) draft = draft.copy(minerIds = ids)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }, enabled = valid) { Text(L("Sichern", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Abbrechen", "Cancel")) } }
    )
}

/** "All miners" or a selection; an empty set means all. */
@Composable
internal fun MinerScopeChecks(ids: Set<String>, miners: List<MinerDevice>, onChange: (Set<String>) -> Unit) {
    Text(L("Miner", "Miners"), style = MaterialTheme.typography.labelLarge)
    CheckRow(L("Alle Miner", "All miners"), ids.isEmpty()) { all -> onChange(if (all) emptySet() else miners.map { it.id }.toSet()) }
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
