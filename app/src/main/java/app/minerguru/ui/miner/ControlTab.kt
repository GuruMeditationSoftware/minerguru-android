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
import app.minerguru.R
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
            val sections = listOf(tr(R.string.clock_fan), "Pool", "System")
            ConnectedToggleGroup(sections, section, { section = it }, Modifier.fillMaxWidth())
    
            if (section == 0) {
                Text(tr(R.string.overclocking_is_always_at_your),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
    
            when (section) {
                0 -> PerformanceSection(miner, form, onForm, isSaving) {
                    run(tr(R.string.settings_sent_clock_and_voltage)) {
                        store.updateMinerSettings(miner.id, form.performancePayload(miner.latestInfo?.autoFanModeRaw))
                    }
                }
                1 -> PoolSection(store, miner, form, onForm, isSaving) {
                    run(tr(R.string.pool_configuration_saved_miner_was)) {
                        store.applyPoolSettings(form, miner.id)
                        onReloadForm()
                    }
                }
                else -> SystemSection(store, miner, form, onForm, isSaving,
                    onSaveAlias = {
                        store.updateCustomName(miner.id, form.customAlias)
                        status.error = null; status.success = tr(R.string.display_name_saved)
                    },
                    onSaveHostname = { run(tr(R.string.hostname_sent)) { store.updateMinerSettings(miner.id, form.hostnamePayload) } })
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
            Text(tr(R.string.quick_profiles_for) + " ${miner.hardwareTitle}", style = MaterialTheme.typography.titleSmall)
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
            Text(tr(R.string.manual_fine_tuning), style = MaterialTheme.typography.titleSmall)
            Stepper(tr(R.string.frequency), form.frequencyMHz, "MHz", miner.frequencyRange, 5) { onForm(form.copy(frequencyMHz = it)) }
            Stepper("Core Voltage", form.coreVoltageMV, "mV", miner.coreVoltageRange, 5) { onForm(form.copy(coreVoltageMV = it)) }
            Text(
                tr(R.string.firmware_range) +
                    "${miner.frequencyRange.first}–${miner.frequencyRange.last} MHz • ${miner.coreVoltageRange.first}–${miner.coreVoltageRange.last} mV. " +
                    tr(R.string.too_high_values_can_overheat),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(R.string.fan_control), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.automatic_temperature_control_auto_fan), Modifier.weight(1f))
                Switch(checked = form.autoFanSpeed, onCheckedChange = { onForm(form.copy(autoFanSpeed = it)) })
            }
            if (!form.autoFanSpeed) {
                Row {
                    Text(tr(R.string.manual_fan_speed), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${form.manualFanPercent.toInt()} %", fontWeight = FontWeight.Bold)
                }
                Slider(
                    // 5 % steps without a row of tick marks.
                    value = form.manualFanPercent, onValueChange = { onForm(form.copy(manualFanPercent = Math.round(it / 5f) * 5f)) },
                    valueRange = MinerSettingsForm.manualFanRange,
                    modifier = Modifier.semantics { contentDescription = tr(R.string.manual_fan_speed) }
                )
            }
        }
    }
    SaveButton(tr(R.string.apply_clock_fan_to_miner), saving, onClick = onSave)
}

@Composable
private fun Stepper(label: String, value: Int, unit: String, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalIconButton(onClick = { onChange((value - step).coerceIn(range)) }) { Icon(Icons.Rounded.Remove, tr(R.string.less)) }
        Text("$value $unit", Modifier.width(92.dp), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onChange((value + step).coerceIn(range)) }) { Icon(Icons.Rounded.Add, tr(R.string.more)) }
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
                Text(tr(R.string.stratum_server_wallet), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Box {
                    FilledTonalButton(onClick = { presetMenu = true }) { Text(tr(R.string.template_menu)) }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        listOf(true to tr(R.string.as_primary_pool), false to tr(R.string.as_fallback_pool_2)).forEach { (primary, title) ->
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
                Text(tr(R.string.pool_operating_mode), style = MaterialTheme.typography.labelLarge)
                DualPoolControls(form, onForm)
            }
            if (slotsApi) {
                HorizontalDivider()
                Text(tr(R.string.pool_slots_axeos_up_to, AxeOSPool.SLOT_COUNT),
                    style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlotRolePicker(tr(R.string.primary), form, store.poolLibrary, form.primarySlot, Modifier.weight(1f)) {
                        onForm(form.copy(primarySlot = it))
                    }
                    SlotRolePicker("Fallback", form, store.poolLibrary, form.secondarySlot, Modifier.weight(1f)) {
                        onForm(form.copy(secondarySlot = it))
                    }
                }
                Text(tr(R.string.further_slots_stay_stored_on),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    form.visibleSlots.forEach { index ->
        val title = when {
            slotsApi && index == form.primarySlot -> "${form.slotTitle(index)} – " + tr(R.string.primary_pool)
            slotsApi && index == form.secondarySlot -> "${form.slotTitle(index)} – " + tr(R.string.fallback_pool)
            slotsApi -> "${form.slotTitle(index)} – Reserve"
            index == 0 -> if (isDual) tr(R.string.pool_1_primary) + " (${form.primaryPercent}%)" else tr(R.string.primary_stratum_pool)
            else -> if (isDual) tr(R.string.pool_2_secondary) + " (${form.secondaryPercent}%)" else tr(R.string.fallback_stratum_pool)
        }
        val canClear = form.slots[index].hasContent && (if (slotsApi) index != form.primarySlot else index != 0)
        PoolEndpointEditor(
            title = title,
            endpoint = form.slots[index],
            library = store.poolLibrary,
            coin = slotCoin(miner, form, index),
            hostHint = if (index == form.primarySlot) "public-pool.io" else "eusolo.ckpool.org",
            passwordHelp = if (slotsApi) tr(R.string.keeps_the_password_stored_on)
            else tr(R.string.leave_empty_to_keep_the),
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
        Text(tr(R.string.cleared_on_save) + form.slotsToClear.joinToString(", ") { form.slotTitle(it) },
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
        Spacer(Modifier.width(8.dp)); Text(tr(R.string.test_pool_latency))
    }
    SaveButton(tr(R.string.save_restart), saving, form.poolValidationMessage == null, onSave)
    Text(tr(R.string.the_miner_restarts_after_a),
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
                    IconButton(onClick = onSaveToLibrary) { Icon(Icons.Rounded.SaveAlt, tr(R.string.save_to_library)) }
                }
                if (onClear != null) {
                    IconButton(onClick = onClear) { Icon(Icons.Rounded.Delete, tr(R.string.clear_slot)) }
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
                endpoint.user, { onChange(endpoint.copy(user = it)) }, label = { Text(tr(R.string.wallet_address_worker)) },
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
            Text(tr(R.string.display_name_in_minerguru), style = MaterialTheme.typography.titleSmall)
            AppTextField(
                form.customAlias, { onForm(form.copy(customAlias = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(tr(R.string.empty_hostname_of_the_miner)) }
            )
            Button(onClick = onSaveAlias) { Text(tr(R.string.save)) }
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.miner_hostname_in_the_network), style = MaterialTheme.typography.titleSmall)
            AppTextField(form.deviceHostname, { onForm(form.copy(deviceHostname = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("bitaxe") })
            FilledTonalButton(onClick = onSaveHostname, enabled = form.trimmedHostname.isNotEmpty() && !saving) {
                Text(tr(R.string.send_hostname))
            }
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.device_actions), style = MaterialTheme.typography.titleSmall)
            FilledTonalButton(onClick = { confirmRestart = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(8.dp)); Text(tr(R.string.restart_miner_reboot))
            }
            FilledTonalButton(onClick = { openUrl(context, miner.webInterfaceUrl) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.OpenInBrowser, null); Spacer(Modifier.width(8.dp)); Text(tr(R.string.open_web_interface_in_browser))
            }
        }
    }
    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text(tr(R.string.really_restart_the_miner)) },
        text = { Text(tr(R.string.the_miner_briefly_stops_mining)) },
        confirmButton = { TextButton(onClick = { confirmRestart = false; store.restartMiner(miner.id) }) { Text(tr(R.string.restart_now)) } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text(tr(R.string.cancel)) } }
    )
}
