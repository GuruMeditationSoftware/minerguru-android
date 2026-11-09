package app.minerguru.ui.miner

import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.minerguru.model.*
import app.minerguru.service.MinerStore
import app.minerguru.service.PoolLatencyService
import app.minerguru.ui.components.*
import app.minerguru.ui.fleet.openUrl
import app.minerguru.ui.pools.DualPoolControls
import app.minerguru.ui.pools.LibraryPoolPicker
import app.minerguru.ui.pools.SlotRolePicker
import app.minerguru.ui.pools.slotCoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Settings for one miner: clock & fan, pools, name and device actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlTab(
    store: MinerStore, miner: MinerDevice, form: MinerSettingsForm, contentPadding: PaddingValues,
    onForm: (MinerSettingsForm) -> Unit, onReloadForm: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf(0) }
    // Save state lives in the view model: a save keeps running when the page is left or rotated.
    val status = store.saveStatus(miner.id)
    val isSaving = status.isSaving
    val error = status.error
    val success = status.success
    fun run(ok: String, action: suspend () -> Unit) = store.runSave(miner.id, ok, action)

    val direction = LocalLayoutDirection.current
    val message = error ?: success
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
                bottom = if (message == null) contentPadding.calculateBottomPadding() else 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val sections = listOf(L("Takt & Lüfter", "Clock & fan"), "Pool", "System")
            ConnectedToggleGroup(sections, section, { section = it }, Modifier.fillMaxWidth())
    
            if (section == 0) {
                Text(L("Übertakten geschieht immer auf eigenes Risiko. Zu hoher Takt oder zu hohe Spannung können den Miner beschädigen, die Garantie erlischt.",
                    "Overclocking is always at your own risk. Too high a frequency or voltage can damage the miner and voids the warranty."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
    
            when (section) {
                0 -> PerformanceSection(miner, form, onForm, isSaving) {
                    run(L("Einstellungen übertragen. Takt und Spannung gelten erst nach einem Neustart des Miners.", "Settings sent. Clock and voltage only apply after the miner restarts.")) {
                        store.updateMinerSettings(miner.id, form.performancePayload(miner.latestInfo?.autoFanModeRaw))
                    }
                }
                1 -> PoolSection(store, miner, form, onForm, isSaving) {
                    run(L("Pool-Konfiguration gespeichert, Miner wurde neu gestartet.", "Pool configuration saved, miner was restarted.")) {
                        store.applyPoolSettings(form, miner.id)
                        onReloadForm()
                    }
                }
                else -> SystemSection(store, miner, form, onForm, isSaving,
                    onSaveAlias = {
                        store.updateCustomName(miner.id, form.customAlias)
                        status.error = null; status.success = L("Anzeigename gespeichert.", "Display name saved.")
                    },
                    onSaveHostname = { run(L("Hostname übertragen.", "Hostname sent.")) { store.updateMinerSettings(miner.id, form.hostnamePayload) } })
            }
    }
        // Below the scroll area: the buttons sit at the end of a long form, a message above them would stay out of sight.
        message?.let {
            Column(
                Modifier.padding(
                    start = contentPadding.calculateStartPadding(direction),
                    end = contentPadding.calculateEndPadding(direction),
                    bottom = contentPadding.calculateBottomPadding()
                )
            ) { StatusMessage(it, error != null) }
        }
    }
}

@Composable
private fun PerformanceSection(miner: MinerDevice, form: MinerSettingsForm, onForm: (MinerSettingsForm) -> Unit, saving: Boolean, onSave: () -> Unit) {
    val presets = miner.recommendedPresets
    if (presets.isNotEmpty()) SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L("Schnell-Profile für", "Quick profiles for") + " ${miner.hardwareTitle}", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) { presets.forEachIndexed { index, preset ->
                val current = form.isCurrent(preset)
                // Grouped segments; the active profile gets the secondary container.
                SectionCard(
                    onClick = { onForm(form.apply(preset)) }, shape = segmentShape(index, presets.size),
                    color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.semantics { selected = current }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.titleSmall)
                            if (preset.subtitle.isNotEmpty()) {
                                Text(preset.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("${preset.frequencyMHz} MHz\n${preset.coreVoltageMV} mV", style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.End,
                            color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } }
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L("Manuelle Feinabstimmung", "Manual fine-tuning"), style = MaterialTheme.typography.titleSmall)
            Stepper(L("Frequenz", "Frequency"), form.frequencyMHz, "MHz", miner.frequencyRange, 5) { onForm(form.copy(frequencyMHz = it)) }
            Stepper("Core Voltage", form.coreVoltageMV, "mV", miner.coreVoltageRange, 5) { onForm(form.copy(coreVoltageMV = it)) }
            Text(
                L("Bereich laut Firmware: ", "Firmware range: ") +
                    "${miner.frequencyRange.first}–${miner.frequencyRange.last} MHz • ${miner.coreVoltageRange.first}–${miner.coreVoltageRange.last} mV. " +
                    L("Zu hohe Werte können den ASIC überhitzen.", "Too high values can overheat the ASIC."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L("Lüftersteuerung", "Fan control"), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Automatische Temperaturregelung (Auto-Fan)", "Automatic temperature control (auto fan)"), Modifier.weight(1f))
                Switch(checked = form.autoFanSpeed, onCheckedChange = { onForm(form.copy(autoFanSpeed = it)) })
            }
            if (!form.autoFanSpeed) {
                Row {
                    Text(L("Manuelle Lüfterdrehzahl", "Manual fan speed"), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${form.manualFanPercent.toInt()} %", fontWeight = FontWeight.Bold)
                }
                Slider(
                    // 5 % steps without a row of tick marks.
                    value = form.manualFanPercent, onValueChange = { onForm(form.copy(manualFanPercent = Math.round(it / 5f) * 5f)) },
                    valueRange = MinerSettingsForm.manualFanRange,
                    modifier = Modifier.semantics { contentDescription = L("Manuelle Lüfterdrehzahl", "Manual fan speed") }
                )
            }
        }
    }
    SaveButton(L("Takt & Lüfter auf Miner anwenden", "Apply clock & fan to miner"), saving, onClick = onSave)
}

@Composable
private fun Stepper(label: String, value: Int, unit: String, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalIconButton(onClick = { onChange((value - step).coerceIn(range)) }) { Icon(Icons.Rounded.Remove, L("Weniger", "Less")) }
        Text("$value $unit", Modifier.width(92.dp), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onChange((value + step).coerceIn(range)) }) { Icon(Icons.Rounded.Add, L("Mehr", "More")) }
    }
}

@Composable
private fun SaveButton(text: String, saving: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !saving, modifier = Modifier.fillMaxWidth()) {
        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Icon(Icons.Rounded.CheckCircle, null)
        Spacer(Modifier.width(8.dp)); Text(text)
    }
}

@Composable
private fun PoolSection(
    store: MinerStore, miner: MinerDevice, form: MinerSettingsForm, onForm: (MinerSettingsForm) -> Unit,
    saving: Boolean, onSave: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var testedPrimary by remember { mutableStateOf(miner.primaryPoolEffectiveLatencyMs) }
    var testedFallback by remember { mutableStateOf(miner.secondaryPoolEffectiveLatencyMs) }
    var testing by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    val isDual = miner.supportsDualPool && form.dualPool
    val slotsApi = form.poolApi == MinerSettingsForm.PoolApi.SLOTS

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Stratum-Server & Wallet", "Stratum server & wallet"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Box {
                    FilledTonalButton(onClick = { presetMenu = true }) { Text(L("Vorlage…", "Template…")) }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        listOf(true to L("Als Primär-Pool", "As primary pool"), false to L("Als Fallback / Pool 2", "As fallback / pool 2")).forEach { (primary, title) ->
                            Text(title, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            PoolPreset.commonPools.forEach { p ->
                                DropdownMenuItem(text = { Text("${p.name} (${p.host}:${p.port})") }, onClick = {
                                    presetMenu = false
                                    onForm(
                                        if (primary) form.withPrimary(form.primary.copy(host = p.host, port = p.port))
                                        else form.withFallback(form.fallback.copy(host = p.host, port = p.port))
                                    )
                                })
                            }
                        }
                    }
                }
            }
            if (miner.supportsDualPool) {
                HorizontalDivider()
                Text(L("Pool-Betriebsmodus", "Pool mode"), style = MaterialTheme.typography.labelLarge)
                DualPoolControls(form, onForm)
            }
            if (slotsApi) {
                HorizontalDivider()
                Text(L("Pool-Slots (AxeOS: bis zu ${AxeOSPool.SLOT_COUNT})", "Pool slots (AxeOS: up to ${AxeOSPool.SLOT_COUNT})"),
                    style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlotRolePicker(L("Primär", "Primary"), form, store.poolLibrary, form.primarySlot, Modifier.weight(1f)) {
                        onForm(form.copy(primarySlot = it))
                    }
                    SlotRolePicker("Fallback", form, store.poolLibrary, form.secondarySlot, Modifier.weight(1f)) {
                        onForm(form.copy(secondarySlot = it))
                    }
                }
                Text(L("Weitere Slots bleiben als Reserve auf dem Miner gespeichert – so wechselst du Pools ohne neu einzutippen.",
                    "Further slots stay stored on the miner as reserve – switch pools without retyping."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    form.visibleSlots.forEach { index ->
        val title = when {
            slotsApi && index == form.primarySlot -> "${form.slotTitle(index)} – " + L("Primärer Pool", "Primary pool")
            slotsApi && index == form.secondarySlot -> "${form.slotTitle(index)} – " + L("Fallback-Pool", "Fallback pool")
            slotsApi -> "${form.slotTitle(index)} – Reserve"
            index == 0 -> if (isDual) L("Pool 1 – Primär", "Pool 1 – primary") + " (${form.primaryPercent}%)" else L("Primärer Stratum-Pool", "Primary stratum pool")
            else -> if (isDual) L("Pool 2 – Sekundär", "Pool 2 – secondary") + " (${form.secondaryPercent}%)" else L("Fallback Stratum-Pool", "Fallback stratum pool")
        }
        val canClear = form.slots[index].hasContent && (if (slotsApi) index != form.primarySlot else index != 0)
        PoolEndpointEditor(
            title = title,
            endpoint = form.slots[index],
            library = store.poolLibrary,
            coin = slotCoin(miner, form, index),
            hostHint = if (index == form.primarySlot) "public-pool.io" else "eusolo.ckpool.org",
            passwordHelp = if (slotsApi) L("„*****“ behält das auf dem Miner gespeicherte Passwort.", "“*****” keeps the password stored on the miner.")
            else L("Leer lassen, um das gespeicherte Passwort zu behalten.", "Leave empty to keep the stored password."),
            latencyMs = when (index) { form.primarySlot -> testedPrimary; form.secondarySlot -> testedFallback; else -> null },
            onChange = { onForm(form.withSlot(index, it)) },
            onPick = { cfg -> onForm(if (cfg != null) form.assign(cfg, index) else form.clearSlot(index)) },
            onSaveToLibrary = {
                val e = form.slots[index]
                store.addPool(PoolConfig.fromEndpoint(PoolConfig.suggestedName(e.host, e.port, e.user), e))
            },
            onClear = if (canClear) ({ onForm(form.clearSlot(index)) }) else null
        )
    }

    form.poolValidationMessage?.let { StatusMessage(it, true) }
    form.poolCoinWarning?.let { StatusMessage(it, true) }
    if (form.poolValidationMessage == null && form.slotsToClear.isNotEmpty()) {
        Text(L("Beim Speichern geleert: ", "Cleared on save: ") + form.slotsToClear.joinToString(", ") { form.slotTitle(it) },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    FilledTonalButton(
        onClick = {
            testing = true
            val p = form.primary; val f = form.fallback
            scope.launch {
                val a = async { PoolLatencyService.measureTcpConnectLatencyMs(p.host, p.port) }
                val b = async { if (f.host.isBlank()) null else PoolLatencyService.measureTcpConnectLatencyMs(f.host, f.port) }
                testedPrimary = a.await(); testedFallback = b.await(); testing = false
            }
        },
        enabled = !testing, modifier = Modifier.fillMaxWidth()
    ) {
        if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Timeline, null)
        Spacer(Modifier.width(8.dp)); Text(L("Pool-Latenz testen", "Test pool latency"))
    }
    SaveButton(L("Speichern & neu starten", "Save & restart"), saving, form.poolValidationMessage == null, onSave)
    Text(L("Der Miner startet nach einer Pool-Änderung neu, damit sie aktiv wird.",
        "The miner restarts after a pool change so it takes effect."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PoolEndpointEditor(
    title: String,
    endpoint: MinerSettingsForm.PoolEndpoint,
    library: List<PoolConfig>,
    coin: MinedCoin,
    hostHint: String,
    passwordHelp: String,
    latencyMs: Double?,
    onChange: (MinerSettingsForm.PoolEndpoint) -> Unit,
    onPick: (PoolConfig?) -> Unit,
    onSaveToLibrary: () -> Unit,
    onClear: (() -> Unit)?
) {
    val inLibrary = endpoint.hasContent && library.any { it.matches(endpoint) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (endpoint.hasContent) CoinBadge(coin)
                latencyMs?.let { LatencyBadge(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LibraryPoolPicker(library, endpoint, Modifier.weight(1f), allowEmpty = onClear != null, onSelect = onPick)
                if (endpoint.hasContent && !inLibrary) {
                    IconButton(onClick = onSaveToLibrary) { Icon(Icons.Rounded.SaveAlt, L("In die Bibliothek übernehmen", "Save to library")) }
                }
                if (onClear != null) {
                    IconButton(onClick = onClear) { Icon(Icons.Rounded.Delete, L("Slot leeren", "Clear slot")) }
                }
            }
            AppTextField(
                endpoint.host, { onChange(endpoint.copy(host = it)) }, label = { Text("Stratum Host / URL") },
                placeholder = { Text(hostHint) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(
                    endpoint.port.toString(), { onChange(endpoint.copy(port = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) },
                    label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                PasswordField(endpoint.password, { onChange(endpoint.copy(password = it)) }, Modifier.weight(1f))
            }
            Text(passwordHelp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTextField(
                endpoint.user, { onChange(endpoint.copy(user = it)) }, label = { Text(L("Wallet-Adresse.Worker", "Wallet address.worker")) },
                placeholder = { Text("bc1q…wallet.bitaxe") }, singleLine = true, modifier = Modifier.fillMaxWidth().sensitive()
            )
        }
    }
}

@Composable
private fun SystemSection(
    store: MinerStore, miner: MinerDevice, form: MinerSettingsForm, onForm: (MinerSettingsForm) -> Unit, saving: Boolean,
    onSaveAlias: () -> Unit, onSaveHostname: () -> Unit
) {
    val context = LocalContext.current
    var confirmRestart by remember { mutableStateOf(false) }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L("Anzeigename in MinerGuru", "Display name in MinerGuru"), style = MaterialTheme.typography.titleSmall)
            AppTextField(
                form.customAlias, { onForm(form.copy(customAlias = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(L("leer = Hostname vom Miner", "empty = hostname of the miner")) }
            )
            Button(onClick = onSaveAlias) { Text(L("Speichern", "Save")) }
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L("Miner-Hostname im Netzwerk", "Miner hostname in the network"), style = MaterialTheme.typography.titleSmall)
            AppTextField(form.deviceHostname, { onForm(form.copy(deviceHostname = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("bitaxe") })
            FilledTonalButton(onClick = onSaveHostname, enabled = form.trimmedHostname.isNotEmpty() && !saving) {
                Text(L("Hostname übertragen", "Send hostname"))
            }
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L("Geräte-Aktionen", "Device actions"), style = MaterialTheme.typography.titleSmall)
            FilledTonalButton(onClick = { confirmRestart = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(8.dp)); Text(L("Miner neu starten (Reboot)", "Restart miner (reboot)"))
            }
            FilledTonalButton(onClick = { openUrl(context, miner.webInterfaceUrl) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.OpenInBrowser, null); Spacer(Modifier.width(8.dp)); Text(L("Weboberfläche im Browser öffnen", "Open web interface in browser"))
            }
        }
    }
    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text(L("Miner wirklich neu starten?", "Really restart the miner?")) },
        text = { Text(L("Der Miner unterbricht kurzzeitig das Mining und startet innerhalb weniger Sekunden neu.",
            "The miner briefly stops mining and restarts within a few seconds.")) },
        confirmButton = { TextButton(onClick = { confirmRestart = false; store.restartMiner(miner.id) }) { Text(L("Jetzt neu starten", "Restart now")) } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text(L("Abbrechen", "Cancel")) } }
    )
}
