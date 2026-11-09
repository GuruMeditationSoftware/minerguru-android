package app.minerguru.ui.maintenance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.R
import app.minerguru.model.tr
import app.minerguru.model.MaintenanceKind
import app.minerguru.model.MaintenanceStatus
import app.minerguru.model.MaintenanceTask
import app.minerguru.model.MinerDevice
import app.minerguru.service.MinerStore
import app.minerguru.ui.components.AppTextField
import app.minerguru.ui.components.EmptyHint
import app.minerguru.ui.components.MetricCard
import app.minerguru.ui.components.PageContent
import app.minerguru.ui.components.ScreenScaffold
import app.minerguru.ui.components.SectionCard
import app.minerguru.ui.power.MinerScopeChecks
import app.minerguru.ui.theme.MG
import app.minerguru.ui.theme.MGType
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

private fun dateText(time: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))

private fun today(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Plan of recurring maintenance per miner, and the record of what was done. */
@Composable
fun MaintenanceScreen(store: MinerStore, onBack: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MaintenanceTask?>(null) }
    var finishing by remember { mutableStateOf<MaintenanceTask?>(null) }
    val tasks = store.maintenanceTasks.sortedBy { it.nextDue }
    val overdue = tasks.count { it.status() == MaintenanceStatus.OVERDUE }
    val soon = tasks.count { it.status() == MaintenanceStatus.DUE_SOON }

    ScreenScaffold(
        tr(R.string.maintenance),
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) } },
        floatingActionButton = {
            FloatingActionButton(onClick = { choosing = true }) { Icon(Icons.Rounded.Add, tr(R.string.add_task)) }
        }
    ) { inner ->
        PageContent(inner) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Figure(tr(R.string.overdue), overdue, if (overdue > 0) MG.bad() else null, Modifier.weight(1f))
                        Figure(tr(R.string.in_7_days), soon, if (soon > 0) MG.warning() else null, Modifier.weight(1f))
                        Figure(tr(R.string.completed), store.maintenanceRecords.size, null, Modifier.weight(1f))
                    }
                }
                if (tasks.isEmpty()) {
                    item {
                        EmptyHint(tr(R.string.no_tasks_yet_use_the))
                    }
                }
                items(tasks, key = { it.id }) { task ->
                    TaskCard(task, store.miners, onDone = { finishing = task }, onEdit = { editing = task }, onDelete = { store.removeMaintenanceTask(task.id) })
                }
                if (store.maintenanceRecords.isNotEmpty()) {
                    item { Text(tr(R.string.completed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    items(store.maintenanceRecords.take(50), key = { it.id }) { record ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = MG.good())
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(record.title, style = MaterialTheme.typography.bodyMedium)
                                Text(listOf(dateText(record.doneAt), record.notes).filter { it.isNotEmpty() }.joinToString(" · "),
                                    style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { store.removeMaintenanceRecord(record.id) }) { Icon(Icons.Rounded.Delete, tr(R.string.delete_entry)) }
                        }
                    }
                }
            }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(tr(R.string.add_task)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MaintenanceKind.entries.forEach { kind ->
                        Text(kind.title, Modifier.fillMaxWidth().clickable {
                            choosing = false
                            editing = MaintenanceTask(kind = kind, intervalDays = kind.defaultIntervalDays, nextDue = today())
                        }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(tr(R.string.cancel)) } }
        )
    }
    editing?.let { task ->
        TaskEditor(task, isNew = store.maintenanceTasks.none { it.id == task.id }, miners = store.miners, onDismiss = { editing = null }) {
            store.upsertMaintenanceTask(it)
            editing = null
        }
    }
    finishing?.let { task ->
        var notes by remember(task.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { finishing = null },
            title = { Text(task.title) },
            text = { AppTextField(notes, { notes = it }, label = { Text(tr(R.string.what_was_done)) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    store.markMaintenanceDone(task, System.currentTimeMillis(), notes)
                    finishing = null
                }) { Text(tr(R.string.completed)) }
            },
            dismissButton = { TextButton(onClick = { finishing = null }) { Text(tr(R.string.cancel)) } }
        )
    }
}

@Composable
private fun Figure(title: String, count: Int, tint: Color?, modifier: Modifier) {
    SectionCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("$count", style = MaterialTheme.typography.headlineSmall, color = tint ?: MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun TaskCard(task: MaintenanceTask, miners: List<MinerDevice>, onDone: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val days = task.daysUntilDue()
    val due = when {
        days < 0 -> tr(R.string.overdue_by_days, -days)
        days == 0 -> tr(R.string.due_today)
        days <= MaintenanceTask.DUE_SOON_DAYS -> tr(R.string.in_days, days)
        else -> dateText(task.nextDue)
    }
    val color = when (task.status()) {
        MaintenanceStatus.OVERDUE -> MG.bad()
        MaintenanceStatus.DUE_SOON -> MG.warning()
        MaintenanceStatus.PLANNED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scope = if (task.minerIds.isEmpty()) tr(R.string.all_miners) else miners.filter { it.id in task.minerIds }.joinToString { it.displayName }.ifEmpty { tr(R.string.miner_removed) }
    val repeat = if (task.intervalDays > 0) tr(R.string.every_days, task.intervalDays) else tr(R.string.once)
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall)
            Text(due, style = MaterialTheme.typography.bodyMedium, color = color)
            Text(listOf(scope, repeat, task.notes).filter { it.isNotEmpty() }.joinToString(" · "), style = MGType.mono(11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone) { Text(tr(R.string.completed)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, tr(R.string.edit)) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, tr(R.string.delete_task)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditor(task: MaintenanceTask, isNew: Boolean, miners: List<MinerDevice>, onDismiss: () -> Unit, onSave: (MaintenanceTask) -> Unit) {
    var draft by remember { mutableStateOf(task) }
    var interval by remember { mutableStateOf(task.intervalDays.toString()) }
    var pickingDate by remember { mutableStateOf(false) }
    val valid = draft.kind != MaintenanceKind.CUSTOM || draft.customTitle.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr(R.string.add_maintenance_task) else tr(R.string.edit_maintenance_task)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (draft.kind.hint.isNotEmpty()) {
                    Text(draft.kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppTextField(draft.customTitle, { draft = draft.copy(customTitle = it) }, Modifier.fillMaxWidth(),
                    label = { Text(tr(R.string.title)) }, placeholder = { Text(draft.kind.title) })
                TextButton(onClick = { pickingDate = true }) { Text(tr(R.string.due_on) + dateText(draft.nextDue)) }
                AppTextField(interval, { interval = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(),
                    label = { Text(tr(R.string.repeat_days_0_once)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                MinerScopeChecks(draft.minerIds, miners) { draft = draft.copy(minerIds = it) }
                AppTextField(draft.notes, { draft = draft.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.note)) })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.copy(intervalDays = interval.toIntOrNull() ?: 0)) }, enabled = valid) { Text(tr(R.string.save_entry)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } }
    )
    if (pickingDate) {
        val zone = ZoneId.systemDefault()
        // The picker works in UTC midnights.
        val initial = Instant.ofEpochMilli(draft.nextDue).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        draft = draft.copy(nextDue = date.atStartOfDay(zone).toInstant().toEpochMilli())
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(tr(R.string.cancel)) } }
        ) { DatePicker(state) }
    }
}
