package app.minerguru.ui.power

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.model.*
import app.minerguru.service.MinerStore
import app.minerguru.service.PowerCenter
import app.minerguru.ui.components.*
import app.minerguru.ui.theme.MG
import app.minerguru.ui.theme.MGType
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/** Power tab (MG-14): own tariff, schedules that switch the miners, and costs per miner and fleet. */
@Composable
fun PowerScreen(store: MinerStore, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    // Follow the clock: current zone, "now" marker and costs move once a minute.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }

    var editingZone by remember { mutableStateOf<TariffZone?>(null) }
    var editingRule by remember { mutableStateOf<ScheduleRule?>(null) }
    val tariff = store.powerTariff
    val rules = store.powerRules
    val instant = PowerCenter.hasInstantMonitoring(context)

    ScreenScaffold(L("Strom", "Power"), actions = { PrivacyToggle(store) }) { inner ->
        PageContent(inner, onRefresh = { store.refreshAllMiners() }) { pad ->
            LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Figures(store, tariff, now) }
                item {
                    TariffCard(store, tariff, rules, now,
                        onAddZone = {
                            editingZone = TariffZone(name = L("Nacht", "Night"), pricePerKWh = tariff.basePricePerKWh,
                                window = WeeklyWindow(Weekdays.all, 22 * 60, 6 * 60))
                        },
                        onEditZone = { editingZone = it })
                }
                item {
                    SchedulesCard(store, tariff, rules, now, instant, onOpenSettings,
                        onAddRule = {
                            val trigger: ScheduleTrigger = tariff.zones.firstOrNull()?.let { ScheduleTrigger.Zone(it.id) }
                                ?: ScheduleTrigger.Window(WeeklyWindow(Weekdays.all, 22 * 60, 6 * 60))
                            editingRule = ScheduleRule(trigger = trigger, action = PowerAction.NIGHT_MODE)
                        },
                        onEditRule = { editingRule = it })
                }
                item { GroupHeader(L("Kosten je Miner", "Cost per miner")) }
                if (store.miners.isEmpty()) {
                    item { EmptyHint(L("Noch keine Miner. Füge Miner hinzu, um ihren Verbrauch und ihre Kosten zu sehen.",
                        "No miners yet. Add miners to see their consumption and costs.")) }
                } else {
                    items(store.miners, key = { it.id }) { MinerCostRow(store, it, tariff, rules, now) }
                }
            }
        }
    }

    editingZone?.let { zone ->
        ZoneEditorDialog(zone, isNew = tariff.zones.none { it.id == zone.id }, currencyCode = tariff.currencyCode,
            onDismiss = { editingZone = null }) { saved ->
            val zones = if (tariff.zones.any { it.id == saved.id }) tariff.zones.map { if (it.id == saved.id) saved else it }
            else tariff.zones + saved
            store.updatePowerTariff(tariff.copy(zones = zones))
            editingZone = null
        }
    }
    editingRule?.let { rule ->
        RuleEditorDialog(rule, isNew = rules.none { it.id == rule.id }, zones = tariff.zones, miners = store.miners,
            onDismiss = { editingRule = null }) { saved ->
            store.updatePowerRules(if (rules.any { it.id == saved.id }) rules.map { if (it.id == saved.id) saved else it } else rules + saved)
            editingRule = null
        }
    }
}

// region Figures

private data class Figure(val title: String, val value: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun Figures(store: MinerStore, tariff: PowerTariff, now: Long) {
    val context = LocalContext.current
    val zone = tariff.zoneAt(now)
    val watts = store.totalPowerWatts
    val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val today = store.onlineMiners.sumOf { PowerCenter.projectedCost(context, it, 24.0, midnight, tariff, store.powerRules) }
    val month = store.onlineMiners.sumOf { PowerCenter.projectedCost(context, it, 24.0 * 30, now, tariff, store.powerRules) }
    val th = store.totalHashrateGHs / 1000
    val figures = listOf(
        Figure(L("Preis jetzt", "Price now"), tariff.formatPrice(tariff.priceAt(now), 3),
            zone?.let { "${it.name} · ${it.window.timeRangeText}" } ?: if (tariff.hasZones) L("Standardpreis", "Standard price") else L("Festpreis", "Fixed price"),
            Icons.Rounded.Euro),
        Figure(L("Leistung", "Power"), "${MinerFormatting.format0(watts)} W",
            Lp(store.onlineMiners.size, "Miner online", "Miner online", "miner online", "miners online"), Icons.Rounded.Bolt),
        Figure(L("Kosten pro Stunde", "Cost per hour"), tariff.formatPrice(watts / 1000 * tariff.priceAt(now), 3),
            L("bei aktueller Leistung", "at the current draw"), Icons.Rounded.Schedule),
        Figure(L("Heute", "Today"), tariff.formatPrice(today), L("0–24 Uhr, hochgerechnet", "midnight to midnight, projected"), Icons.Rounded.Today),
        Figure(L("30 Tage", "30 days"), tariff.formatPrice(month),
            if (th > 0.01) L("${tariff.formatPrice(month / th)} pro TH/s", "${tariff.formatPrice(month / th)} per TH/s")
            else L("mit Preisen und Pausen", "with prices and pauses"), Icons.Rounded.CalendarMonth)
    )
    CardGrid(figures) { MetricCard(it.title, it.value, null, it.subtitle, it.icon, MG.accent()) }
}

// endregion

// region Tariff

@Composable
private fun TariffCard(
    store: MinerStore, tariff: PowerTariff, rules: List<ScheduleRule>, now: Long,
    onAddZone: () -> Unit, onEditZone: (TariffZone) -> Unit
) {
    var templates by remember { mutableStateOf(false) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(L("Tarif", "Tariff"), Icons.Rounded.Bolt)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                var currency by rememberSaveable(tariff.currencyCode) { mutableStateOf(tariff.currencyCode) }
                AppTextField(
                    value = currency,
                    onValueChange = { v ->
                        currency = v.uppercase().filter(Char::isLetter).take(3)
                        if (currency.length == 3) store.updatePowerTariff(tariff.copy(currencyCode = currency))
                    },
                    label = { Text(L("Währung", "Currency")) }, singleLine = true, modifier = Modifier.width(110.dp)
                )
                PriceField(
                    tariff.basePricePerKWh,
                    if (tariff.hasZones) L("Standardpreis pro kWh", "Standard price per kWh") else L("Preis pro kWh", "Price per kWh"),
                    Modifier.weight(1f)
                ) { store.updatePowerTariff(tariff.copy(basePricePerKWh = it)) }
            }

            if (tariff.hasZones) {
                Column {
                    tariff.zones.forEachIndexed { index, zone ->
                        ZoneRow(zone, index, tariff, rules.count { (it.trigger as? ScheduleTrigger.Zone)?.zoneId == zone.id },
                            isNow = tariff.zoneAt(now)?.id == zone.id,
                            onUp = {
                                val list = tariff.zones.toMutableList()
                                java.util.Collections.swap(list, index, index - 1)
                                store.updatePowerTariff(tariff.copy(zones = list))
                            },
                            onEdit = { onEditZone(zone) },
                            onDelete = { store.removePowerZone(zone.id) })
                        if (index < tariff.zones.lastIndex) HorizontalDivider(color = MG.hairline())
                    }
                }
            } else {
                Text(
                    L("Ein Festpreis gilt rund um die Uhr. Mit Zonen bekommen Nacht, Wochenende oder Spitzenzeiten eigene Preise, und Zeitpläne können sich danach richten.",
                        "A fixed price applies around the clock. With zones, nights, weekends or peak hours get prices of their own, and schedules can follow them."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAddZone) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L("Zone", "Zone"))
                }
                Box {
                    TextButton(onClick = { templates = true }) { Text(L("Vorlage", "Template")) }
                    DropdownMenu(templates, onDismissRequest = { templates = false }) {
                        val cheaper = Math.round(tariff.basePricePerKWh * 0.8 * 1000) / 1000.0
                        DropdownMenuItem(text = { Text(L("Nacht und Tag (HT/NT)", "Night and day (off-peak/peak)")) }, onClick = {
                            templates = false
                            store.updatePowerTariff(tariff.copy(zones = tariff.zones +
                                TariffZone(name = L("Nacht", "Night"), pricePerKWh = cheaper, window = WeeklyWindow(Weekdays.all, 22 * 60, 6 * 60)) +
                                TariffZone(name = L("Tag", "Day"), pricePerKWh = tariff.basePricePerKWh, window = WeeklyWindow(Weekdays.workdays, 6 * 60, 22 * 60))))
                        })
                        DropdownMenuItem(text = { Text(L("Wochenende günstiger", "Cheaper weekends")) }, onClick = {
                            templates = false
                            store.updatePowerTariff(tariff.copy(zones = tariff.zones +
                                TariffZone(name = L("Wochenende", "Weekend"), pricePerKWh = cheaper, window = WeeklyWindow(Weekdays.weekend, 0, 0))))
                        })
                    }
                }
            }

            WeekBar(tariff, now)

            tariff.overlappingZones.forEach { (a, b) ->
                Hint(L("„${a.name}“ und „${b.name}“ überschneiden sich. Dort gilt „${a.name}“, weil sie weiter oben steht.",
                    "“${a.name}” and “${b.name}” overlap. There “${a.name}” applies because it is higher up."), MG.warning())
            }
            if (tariff.hasGaps) {
                Hint(L("Zeiten ohne Zone kosten den Standardpreis (grau im Wochenbalken).",
                    "Times without a zone cost the standard price (grey in the week bar)."), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ZoneRow(
    zone: TariffZone, index: Int, tariff: PowerTariff, ruleCount: Int, isNow: Boolean,
    onUp: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).padding(1.dp)) {
            val color = zoneColor(index)
            Canvas(Modifier.size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx())) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(zone.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isNow) {
                    Spacer(Modifier.width(6.dp))
                    Text(L("jetzt", "now"), style = MaterialTheme.typography.labelSmall, color = MG.text(MG.accent()))
                }
            }
            Text("${Weekdays.summary(zone.window.weekdays)} · ${zone.window.timeRangeText} · ${tariff.formatPrice(zone.pricePerKWh, 3)}/kWh",
                style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ruleCount > 0) {
                Text(Lp(ruleCount, "Zeitplan", "Zeitpläne", "schedule", "schedules"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (index > 0) IconButton(onClick = onUp) { Icon(Icons.Rounded.KeyboardArrowUp, L("Nach oben", "Move up")) }
        IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, L("Bearbeiten", "Edit")) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, L("Zone löschen", "Delete zone")) }
    }
}

@Composable
private fun zoneColor(index: Int): Color = listOf(MG.accent(), MG.blue(), MG.good(), MG.purple(), MG.teal(), MG.bad())[index % 6]

/** One row per weekday, colored by the zone that applies; base price in grey, the current moment marked. */
@Composable
private fun WeekBar(tariff: PowerTariff, now: Long) {
    val t = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val today = t.dayOfWeek.value
    val nowMinute = t.hour * 60 + t.minute
    val colors = tariff.zones.indices.map { zoneColor(it).copy(alpha = 0.85f) }
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val marker = MaterialTheme.colorScheme.onSurface
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics { contentDescription = L("Wochenübersicht des Tarifs", "Weekly overview of the tariff") }
    ) {
        (1..7).forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Weekdays.shortName(day), style = MGType.mono(11.sp), modifier = Modifier.width(32.dp),
                    color = if (day == today) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                val segments = tariff.segments(day)
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    val perMinute = size.width / WeeklyWindow.MINUTES_PER_DAY
                    segments.forEach { s ->
                        val color = tariff.zones.indexOfFirst { it.id == s.zoneId }.let { if (it >= 0) colors[it] else base }
                        drawRect(color, Offset(s.startMinute * perMinute, 0f), Size((s.endMinute - s.startMinute) * perMinute, size.height))
                    }
                    if (day == today) drawRect(marker, Offset(nowMinute * perMinute - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                }
            }
        }
        Row {
            Spacer(Modifier.width(32.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0, 6, 12, 18, 24).forEach { Text("$it", style = MGType.mono(10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// endregion

// region Schedules

@Composable
private fun SchedulesCard(
    store: MinerStore, tariff: PowerTariff, rules: List<ScheduleRule>, now: Long, instant: Boolean,
    onOpenSettings: () -> Unit, onAddRule: () -> Unit, onEditRule: (ScheduleRule) -> Unit
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(L("Zeitpläne", "Schedules"), Icons.Rounded.Schedule)
            if (rules.isEmpty()) {
                Text(
                    L("Noch keine Zeitpläne. Ein Zeitplan schaltet Miner während einer Tarifzone oder zu festen Uhrzeiten in den Nachtmodus oder pausiert sie; danach kehren sie zu ihren eigenen Einstellungen zurück.",
                        "No schedules yet. A schedule puts miners into night mode or pauses them during a tariff zone or at set times; afterwards they return to their own settings."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column {
                    rules.forEachIndexed { index, rule ->
                        RuleRow(store, rule, index, tariff, rules, now, onEdit = { onEditRule(rule) })
                        if (index < rules.lastIndex) HorizontalDivider(color = MG.hairline())
                    }
                }
            }
            FilledTonalButton(onClick = onAddRule, enabled = store.miners.isNotEmpty()) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L("Zeitplan", "Schedule"))
            }

            HorizontalDivider(color = MG.hairline())

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L("Dieses Gerät führt die Zeitpläne aus", "This device runs the schedules"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (instant) L("Nur ein Gerät sollte schalten, sonst arbeiten Mac und Handy gegeneinander.",
                            "Only one device should switch, otherwise the Mac and the phone work against each other.")
                        else L("Braucht die Überwachung „Sofort“ in den Einstellungen; ohne sie zeigt das Handy nur Kosten.",
                            "Needs “Instant” monitoring in Settings; without it the phone only shows costs."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = store.powerRunsSchedules && instant, enabled = instant, onCheckedChange = store::updatePowerRunsSchedules)
            }
            if (!instant) TextButton(onClick = onOpenSettings) { Text(L("Zu den Einstellungen", "Open Settings")) }

            Column {
                Text(L("Lüfter im Nachtmodus: ${store.powerNightFan} %", "Fan in night mode: ${store.powerNightFan} %"),
                    style = MaterialTheme.typography.bodyLarge)
                var fan by remember(store.powerNightFan) { mutableStateOf(store.powerNightFan.toFloat()) }
                Slider(value = fan, onValueChange = { fan = it }, valueRange = 20f..100f, steps = 15,
                    onValueChangeFinished = { store.updatePowerNightFan(Math.round(fan / 5) * 5) })
                Text(
                    L("Der Nachtmodus senkt den Takt auf Eco und hält den Lüfter fest. Pause gibt es ab AxeOS 2.15; ältere AxeOS und NerdOS gehen stattdessen in den Nachtmodus.",
                        "Night mode lowers the clock to Eco and fixes the fan speed. Pause needs AxeOS 2.15; older AxeOS and NerdOS go into night mode instead."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            app.minerguru.ui.support.OtpWaitingNotice(store)
            store.powerError?.let { Hint(L("Letzte Schaltung fehlgeschlagen: $it", "Last switch failed: $it"), MG.bad()) }
        }
    }
}

@Composable
private fun RuleRow(store: MinerStore, rule: ScheduleRule, index: Int, tariff: PowerTariff, rules: List<ScheduleRule>, now: Long, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = rule.isEnabled, onCheckedChange = { on -> store.updatePowerRules(rules.map { if (it.id == rule.id) it.copy(isEnabled = on) else it }) })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${rule.action.title} · ${triggerText(rule.trigger, tariff)}", style = MaterialTheme.typography.titleSmall,
                    color = if (rule.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (rule.isActive(now, tariff)) {
                    Spacer(Modifier.width(6.dp))
                    Text(L("aktiv", "active"), style = MaterialTheme.typography.labelSmall, color = MG.text(MG.accent()))
                }
            }
            Text(minersText(rule, store.miners), style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.sensitive())
        }
        if (index > 0) IconButton(onClick = {
            val list = rules.toMutableList(); java.util.Collections.swap(list, index, index - 1); store.updatePowerRules(list)
        }) { Icon(Icons.Rounded.KeyboardArrowUp, L("Nach oben", "Move up")) }
        IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, L("Bearbeiten", "Edit")) }
        IconButton(onClick = { store.updatePowerRules(rules.filterNot { it.id == rule.id }) }) {
            Icon(Icons.Rounded.Delete, L("Zeitplan löschen", "Delete schedule"))
        }
    }
}

internal fun triggerText(trigger: ScheduleTrigger, tariff: PowerTariff): String = when (trigger) {
    is ScheduleTrigger.Zone -> tariff.zones.firstOrNull { it.id == trigger.zoneId }
        ?.let { L("während „${it.name}“", "during “${it.name}”") } ?: L("Zone gelöscht", "zone deleted")
    is ScheduleTrigger.Window -> "${Weekdays.summary(trigger.window.weekdays)} ${trigger.window.timeRangeText}"
}

private fun minersText(rule: ScheduleRule, miners: List<MinerDevice>): String =
    if (rule.minerIds.isEmpty()) L("Alle Miner", "All miners")
    else miners.filter { it.id in rule.minerIds }.joinToString(", ") { it.displayName }.ifEmpty { L("Miner entfernt", "Miner removed") }

// endregion

// region Miners

@Composable
private fun MinerCostRow(store: MinerStore, miner: MinerDevice, tariff: PowerTariff, rules: List<ScheduleRule>, now: Long) {
    val context = LocalContext.current
    val info = miner.latestInfo
    val online = miner.connectionState.isOnline && info != null
    val current = store.powerStates[miner.id]?.action ?: PowerAction.NORMAL
    val planned = PowerCenter.effective(PowerSchedule.action(miner.id, now, rules, tariff), miner)
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Memory, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(miner.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).sensitive())
                Text(if (planned != current) "${current.title} → ${planned.title}" else current.title,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!online) {
                Text(L("Offline", "Offline"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val watts = info!!.power
                val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val month = PowerCenter.projectedCost(context, miner, 24.0 * 30, now, tariff, rules)
                val th = info.hashRate / 1000
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabeledValue(L("Leistung", "Power"), "${MinerFormatting.format1(watts)} W", mono = true)
                    LabeledValue(L("pro Stunde", "per hour"), tariff.formatPrice(watts / 1000 * tariff.priceAt(now), 3), mono = true)
                    LabeledValue(L("heute", "today"), tariff.formatPrice(PowerCenter.projectedCost(context, miner, 24.0, midnight, tariff, rules)), mono = true)
                    LabeledValue(L("30 Tage", "30 days"), tariff.formatPrice(month), mono = true)
                }
                if (th > 0.01) {
                    Text(L("${tariff.formatPrice(month / th)} pro TH/s in 30 Tagen", "${tariff.formatPrice(month / th)} per TH/s in 30 days"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// endregion

// region Shared

@Composable
internal fun PriceField(value: Double, label: String, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    // Not keyed on [value]: every keystroke saves, and re-keying would reformat the text under the cursor.
    var text by rememberSaveable { mutableStateOf(priceText(value)) }
    AppTextField(
        value = text,
        onValueChange = { v ->
            text = v
            v.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let(onChange)
        },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

/** 0.3 → "0.30", 0.2456 → "0.2456": at least two, at most four decimals. */
internal fun priceText(value: Double): String {
    val d = java.math.BigDecimal(value).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
    return (if (d.scale() < 2) d.setScale(2) else d).toPlainString()
}

@Composable
internal fun Hint(text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(6.dp)) {
            Canvas(Modifier.size(6.dp)) { drawCircle(color) }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// endregion
