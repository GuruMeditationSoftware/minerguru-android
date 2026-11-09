package app.minerguru.ui.pools

import app.minerguru.ui.components.StatusMessage
import app.minerguru.ui.components.AppTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import app.minerguru.ui.components.PasswordField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.minerguru.model.L
import app.minerguru.model.MinedCoin
import app.minerguru.model.MinerSettingsForm
import app.minerguru.model.PoolConfig
import app.minerguru.model.PoolPreset
import app.minerguru.model.Privacy
import app.minerguru.model.StratumHost
import app.minerguru.service.PoolLatencyService
import app.minerguru.ui.components.CoinBadge
import app.minerguru.ui.components.LatencyBadge
import app.minerguru.ui.components.sensitive
import kotlinx.coroutines.launch

/**
 * Dropdown that puts a library pool into a slot. Shows the matching library entry, "not in library"
 * for a pool that was only configured on the miner, or "empty".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPoolPicker(
    library: List<PoolConfig>,
    endpoint: MinerSettingsForm.PoolEndpoint,
    modifier: Modifier = Modifier,
    allowEmpty: Boolean = true,
    onSelect: (PoolConfig?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val match = if (endpoint.hasContent) library.firstOrNull { it.matches(endpoint) } else null
    val label = when {
        !endpoint.hasContent -> L("— leer —", "— empty —")
        match != null -> match.name
        else -> StratumHost.clean(endpoint.host).ifEmpty { Privacy.user(endpoint.user) } + L(" (nicht gespeichert)", " (not saved)")
    }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        AppTextField(
            value = label, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(L("Pool aus Bibliothek", "Pool from library")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (library.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(L("Bibliothek ist leer", "Library is empty")) }, onClick = { open = false }, enabled = false
                )
            }
            library.forEach { pool ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(pool.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pool.hostAndPort, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { CoinBadge(MinedCoin.detect(pool.host, pool.user)) },
                    onClick = { open = false; onSelect(pool) }
                )
            }
            if (allowEmpty && endpoint.hasContent) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(L("Slot leeren", "Clear slot")) }, onClick = { open = false; onSelect(null) })
            }
        }
    }
}

/** Create or edit one saved pool configuration. */
@Composable
fun PoolConfigEditorDialog(initial: PoolConfig, isNew: Boolean, onDismiss: () -> Unit, onSave: (PoolConfig) -> Unit) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var host by rememberSaveable(initial.id) { mutableStateOf(initial.host) }
    var port by rememberSaveable(initial.id) { mutableStateOf(initial.port.toString()) }
    var password by rememberSaveable(initial.id) { mutableStateOf(initial.password) }
    var user by rememberSaveable(initial.id) { mutableStateOf(initial.user) }
    var templates by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var latency by remember { mutableStateOf<Double?>(null) }
    val scope = rememberCoroutineScope()

    val portValue = port.toIntOrNull() ?: 0
    val valid = StratumHost.clean(host).isNotEmpty() && portValue in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) L("Neue Pool-Konfiguration", "New pool configuration") else L("Pool bearbeiten", "Edit pool")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    FilledTonalButton(onClick = { templates = true }) { Text(L("Vorlage…", "Template…")) }
                    DropdownMenu(expanded = templates, onDismissRequest = { templates = false }) {
                        PoolPreset.commonPools.forEach { p ->
                            DropdownMenuItem(text = { Text("${p.name} (${p.host}:${p.port})") }, onClick = {
                                templates = false
                                host = p.host; port = p.port.toString()
                                if (name.isBlank()) name = p.name
                            })
                        }
                    }
                }
                AppTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text(L("z. B. CKPool EU · rig1", "e.g. CKPool EU · rig1")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(host, { host = it }, label = { Text("Stratum Host / URL") }, placeholder = { Text("public-pool.io") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    PasswordField(password, { password = it }, Modifier.weight(1f))
                }
                AppTextField(user, { user = it }, label = { Text(L("Wallet-Adresse.Worker", "Wallet address.worker")) },
                    placeholder = { Text("bc1q…wallet.bitaxe") }, singleLine = true, modifier = Modifier.fillMaxWidth().sensitive())
                MinedCoin.mismatchWarning(host, user)?.let { StatusMessage(it, true) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (host.isNotBlank()) CoinBadge(MinedCoin.detect(host, user), showFullName = true)
                    latency?.let { LatencyBadge(it) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            testing = true
                            scope.launch { latency = PoolLatencyService.measureTcpConnectLatencyMs(host, portValue); testing = false }
                        },
                        enabled = valid && !testing
                    ) {
                        if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(L("Latenz testen", "Test latency"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleanHost = StratumHost.clean(host)
                val cleanUser = user.trim()
                val finalName = name.trim().ifEmpty { PoolConfig.suggestedName(cleanHost, portValue, cleanUser) }
                onSave(initial.copy(name = finalName, host = cleanHost, port = portValue, user = cleanUser, password = password.ifEmpty { "x" }))
                onDismiss()
            }, enabled = valid) { Text(L("Speichern", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Abbrechen", "Cancel")) } }
    )
}

@Composable
fun RoleBadge(text: String, color: androidx.compose.ui.graphics.Color) =
    app.minerguru.ui.components.Chip(text, color)

/** Primary / fallback slot dropdown for AxeOS 2.15+. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotRolePicker(
    label: String,
    form: MinerSettingsForm,
    library: List<PoolConfig>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val choices = (form.slots.indices.filter { form.slots[it].hasContent } + selected).distinct().sorted()
    fun title(i: Int): String {
        val slot = form.slots[i]
        if (!slot.hasContent) return form.slotTitle(i) + L(" (leer)", " (empty)")
        val name = library.firstOrNull { it.matches(slot) }?.name ?: StratumHost.clean(slot.host)
        return "${form.slotTitle(i)}: $name"
    }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        AppTextField(
            value = title(selected), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { i -> DropdownMenuItem(text = { Text(title(i)) }, onClick = { open = false; onSelect(i) }) }
        }
    }
}
