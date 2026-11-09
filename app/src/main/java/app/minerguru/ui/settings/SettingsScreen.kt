package app.minerguru.ui.settings

import app.minerguru.model.MinedCoin
import app.minerguru.model.CoinAddress
import app.minerguru.model.ChangeLog
import androidx.compose.runtime.remember
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.History
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.minerguru.BuildConfig
import app.minerguru.model.AlertKind
import app.minerguru.model.DiagnosticLog
import app.minerguru.model.AppInfo
import app.minerguru.service.UpdateStatus
import app.minerguru.model.AppLanguage
import app.minerguru.model.BackgroundMode
import app.minerguru.model.L
import app.minerguru.model.Privacy
import app.minerguru.service.AlertCenter
import app.minerguru.service.MinerStore
import app.minerguru.ui.components.AppCard
import app.minerguru.ui.components.AppTextField
import app.minerguru.ui.components.GroupHeader
import app.minerguru.ui.components.PageContent
import app.minerguru.ui.components.ScreenScaffold
import app.minerguru.ui.components.SegmentGap
import app.minerguru.ui.components.segmentShape
import app.minerguru.ui.components.sensitive
import app.minerguru.ui.support.LicenseDialog

private val intervals = listOf(2, 5, 10, 15, 30)

private fun intervalLabel(sec: Int) = when (sec) {
    2 -> L("2 s (Echtzeit)", "2 s (real time)")
    5 -> L("5 s (empfohlen)", "5 s (recommended)")
    else -> "$sec s"
}

private fun languageLabel(lang: AppLanguage) = when (lang) {
    AppLanguage.SYSTEM -> L("Systemsprache", "System language")
    AppLanguage.GERMAN -> "Deutsch"
    AppLanguage.ENGLISH -> "English"
}

/** Settings as Material list rows in groups; each row is one touch target. */
@Composable
fun SettingsScreen(store: MinerStore) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(DiagnosticLog.shared.exportText().toByteArray()) }
        }
    }
    val exportChanges = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(ChangeLog.shared.exportText().toByteArray()) }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) store.updateAlertsEnabled(true)
        else store.showBanner(L("Ohne Erlaubnis für Mitteilungen kann MinerGuru nicht benachrichtigen.", "Without notification permission MinerGuru cannot notify you."), true)
    }

    // Asked again on return from the system settings.
    var notificationsReachUser by remember { mutableStateOf(AlertCenter.reachesUser(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsReachUser = AlertCenter.reachesUser(context)
        onPauseOrDispose { }
    }

    ScreenScaffold(L("Einstellungen", "Settings")) { inner ->
        PageContent(inner, maxWidth = 640.dp) { pad ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsGroup(L("Darstellung", "Appearance"), listOf(
                    { shape -> SettingsRow(shape, Icons.Rounded.Language, L("Sprache", "Language"), languageLabel(store.language)) { dialog = "language" } },
                    { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.VisibilityOff, L("Privatmodus", "Privacy mode"),
                            L("IP-, Wallet- und MAC-Adressen, WLAN-Name und Subnetz für Screenshots verschleiern. Worker-Namen bleiben lesbar.",
                                "Scramble IP, wallet and MAC addresses, Wi-Fi name and subnet for screenshots. Worker names stay readable."),
                            Privacy.active
                        ) { store.togglePrivacy() }
                    }
                ))

                SettingsGroup(L("Aktualisierung & Überwachung", "Refresh & monitoring"), listOf(
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.Timer, L("Abfrage-Intervall", "Polling interval"), intervalLabel(store.pollingIntervalSeconds)) {
                            dialog = "interval"
                        }
                    },
                    { shape ->
                        val tempLabel = L("Temperaturwarnung (ASIC)", "Temperature warning (ASIC)")
                        Segment(shape) {
                            ListItem(
                                leadingContent = { Icon(Icons.Rounded.Thermostat, null) },
                                headlineContent = { Text(tempLabel) },
                                supportingContent = {
                                    Column {
                                        // The value sits beside the slider, so the title has the full width.
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Slider(
                                                value = store.tempWarningThresholdC.toFloat(),
                                                onValueChange = { store.updateTempWarning(it.toInt().toDouble()) },
                                                valueRange = 55f..85f, // whole degrees without 30 tick marks
                                                modifier = Modifier.weight(1f).semantics { contentDescription = tempLabel }
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text("${store.tempWarningThresholdC.toInt()} °C", style = MaterialTheme.typography.labelLarge)
                                        }
                                        Text(
                                            L("Diese Abfrage läuft nur, solange die App geöffnet ist. Für Alarme bei geschlossener App: Überwachung im Hintergrund.",
                                                "This polling only runs while the app is open. For alerts while it is closed: background monitoring."),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                ))

                SettingsGroup(L("Soll-Adressen", "Expected addresses"), listOf(
                    { shape ->
                        Segment(shape) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(MinedCoin.BTC, MinedCoin.BCH, MinedCoin.DGB, MinedCoin.XEC).forEach { coin ->
                                    val text = store.expectedAddresses[coin.symbol].orEmpty()
                                    OutlinedTextField(
                                        value = if (Privacy.active) Privacy.scramble(text) else text,
                                        onValueChange = { store.updateExpectedAddress(coin, it) },
                                        readOnly = Privacy.active,
                                        singleLine = true,
                                        label = { Text(coin.fullName) },
                                        isError = text.isNotEmpty() && CoinAddress.scriptPubKey(text) == null,
                                        supportingText = if (text.isNotEmpty() && CoinAddress.scriptPubKey(text) == null) {
                                            { Text(L("Das ist keine gültige Adresse.", "This is not a valid address.")) }
                                        } else null,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Text(
                                    L("MinerGuru warnt, wenn ein Miner an eine andere Adresse zahlt, und schreibt nie von selbst eine Adresse auf einen Miner.",
                                        "MinerGuru warns when a miner pays to another address and never writes an address to a miner on its own."),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                ))

                SettingsGroup(L("Eingriffe", "Interventions"), listOf(
                    { shape ->
                        SwitchRow(shape, Icons.Rounded.Lock, L("Nur lesen", "Read-only"),
                            L("Nichts an Minern ändern", "Change nothing on miners"), store.isReadOnly) { store.updateReadOnly(it) }
                    },
                    { shape ->
                        SwitchRow(shape, Icons.Rounded.RestartAlt, L("Ohne Shares neu starten", "Restart without shares"),
                            L("Höchstens einmal pro Stunde je Miner, nie bei Überhitzung", "At most once an hour per miner, never when overheating"),
                            store.restartsOnFlatline) { store.updateRestartsOnFlatline(it) }
                    },
                    { shape ->
                        SwitchRow(shape, Icons.Rounded.RestartAlt, L("Zum Primär-Pool zurückholen", "Bring back to primary pool"),
                            L("Höchstens einmal pro Stunde je Miner, nie bei Überhitzung", "At most once an hour per miner, never when overheating"),
                            store.restartsToPrimary) { store.updateRestartsToPrimary(it) }
                    },
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.History, L("Änderungsprotokoll", "Change log"),
                            L("Jede Änderung an Minern", "Every change on miners")) { dialog = "changes" }
                    }
                ))

                SettingsGroup(L("Benachrichtigungen", "Notifications"), buildList<@Composable (Shape) -> Unit> {
                    add { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.NotificationsActive, L("Bei Problemen benachrichtigen", "Notify me about problems"),
                            L("Nur Alarme, keine Werbung.", "Alerts only, no advertising."),
                            store.alertsEnabled
                        ) { enabled ->
                            if (enabled && !AlertCenter.canPostNotifications(context)) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else store.updateAlertsEnabled(enabled)
                        }
                    }
                    if (store.alertsEnabled && !notificationsReachUser) {
                        add { shape ->
                            SettingsRow(shape, Icons.Rounded.NotificationsOff, L("Mitteilungen sind im System aus", "Notifications are off in the system"),
                                L("MinerGuru kann dir so nichts anzeigen. Tippen, um sie in den Android-Einstellungen einzuschalten.",
                                    "MinerGuru cannot show you anything like this. Tap to turn them on in the Android settings.")) {
                                val systemSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                context.startActivity(systemSettings)
                            }
                        }
                    }
                    if (store.alertsEnabled) {
                        AlertKind.entries.forEach { kind ->
                            add { shape ->
                                SwitchRow(shape, Icons.Rounded.WifiOff, kind.title, L("Einzelne Miner im Menü der Detailseite stummschalten", "Mute single miners in the menu of their detail page"),
                                    kind !in store.disabledAlertKinds) { store.updateAlertKind(kind, it) }
                            }
                        }
                        add { shape ->
                            SettingsRow(shape, Icons.Rounded.BatteryChargingFull, L("Überwachung im Hintergrund", "Background monitoring"), store.backgroundMode.title) {
                                dialog = "background"
                            }
                        }
                        add { shape ->
                            val active = store.emailSettings.isEnabled && store.emailSettings.isComplete
                            SettingsRow(shape, Icons.Rounded.Email, L("E-Mail-Warnungen", "Email alerts"), if (active) L("Aktiv", "Active") else L("Aus", "Off")) {
                                dialog = "email"
                            }
                        }
                    }
                })

                SettingsGroup(L("Blockansicht", "Block view"), listOf(
                    { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.ViewInAr, L("Block-Vorlage selbst beim Pool abfragen", "Fetch the block template from the pool"),
                            L("Für Miner, die ihren Block nicht selbst melden (NerdOS, AxeOS vor 2.13)", "For miners that do not report their block (NerdOS, AxeOS before 2.13)"),
                            store.stratumProbeEnabled
                        ) { store.updateStratumProbe(it) }
                    },
                    { shape -> Segment(shape) { ProbeDescription() } }
                ))

                SettingsGroup(L("Netzwerk-Scan", "Network scan"), listOf(
                    { shape ->
                        SettingsRow(
                            shape, Icons.Rounded.TravelExplore, L("Standard-Subnetz (/24)", "Default subnet (/24)"),
                            "${Privacy.ip(store.subnetPrefix)}.1 – .254"
                        ) { dialog = "subnet" }
                    },
                    { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.Radar, L("Beim Start automatisch scannen", "Scan automatically at launch"),
                            L("Wenn noch keine Miner konfiguriert sind", "When no miners are configured yet"),
                            store.autoScanOnEmptyLaunch
                        ) { store.updateAutoScan(it) }
                    }
                ))

                SettingsGroup(L("Updates", "Updates"), listOf(
                    { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.SystemUpdate,
                            L("Täglich nach neuen Versionen suchen", "Check for new versions daily"),
                            L(
                                "MinerGuru fragt dazu GitHub nach der neuesten Version. GitHub sieht dabei deine IP-Adresse und die App-Version. Installiert wird nichts automatisch.",
                                "MinerGuru asks GitHub for the latest version. GitHub sees your IP address and the app version. Nothing is installed automatically."
                            ),
                            store.updates.isEnabled
                        ) { store.updates.updateEnabled(it) }
                    },
                    { shape ->
                        val updates = store.updates
                        val available = updates.availableVersion
                        SettingsRow(
                            shape, Icons.Rounded.Refresh,
                            if (available != null) L("Zum Download", "Go to download") else L("Jetzt suchen", "Check now"),
                            when {
                                updates.status == UpdateStatus.CHECKING -> L("Suche läuft …", "Checking …")
                                updates.status == UpdateStatus.FAILED -> L("GitHub ist gerade nicht erreichbar.", "GitHub cannot be reached right now.")
                                available != null -> L("MinerGuru $available ist verfügbar", "MinerGuru $available is available")
                                updates.status == UpdateStatus.UP_TO_DATE -> L("MinerGuru ist aktuell.", "MinerGuru is up to date.")
                                else -> L("Noch nicht gesucht", "Not checked yet")
                            }
                        ) {
                            if (available != null) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.RELEASES_URL))) }
                            } else {
                                updates.checkNow()
                            }
                        }
                    }
                ))

                SettingsGroup(L("Über", "About"), listOf(
                    { shape ->
                        Segment(shape) {
                            ListItem(
                                leadingContent = { Icon(Icons.Rounded.Info, null) },
                                headlineContent = { Text("MinerGuru ${BuildConfig.VERSION_NAME}") },
                                supportingContent = {
                                    Column {
                                        Text(AppInfo.MADE_BY)
                                        Text(L("Überwachung und Steuerung von Bitaxe (AxeOS) und NerdOS-Minern im lokalen Netzwerk.",
                                            "Monitoring and control of Bitaxe (AxeOS) and NerdOS miners in the local network."))
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    },
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.BugReport, L("Diagnose-Log exportieren", "Export diagnostic log"),
                            L("Nur auf diesem Gerät. Enthält lokale IP-Adressen, aber keine Passwörter, Wallet-Adressen oder Codes. Wird nie automatisch gesendet.",
                                "Only on this device. Contains local IP addresses, but no passwords, wallet addresses or codes. Never sent automatically.")) {
                            exportLog.launch("minerguru-log.txt")
                        }
                    },
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.Feedback, L("Feedback geben", "Send feedback"), L("Auf GitHub", "On GitHub")) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.FEEDBACK_URL))) }
                        }
                    },
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.Description, L("Lizenz", "License"), L("Lizenz und Hinweise zu Drittanbietern", "License and third-party notices")) {
                            dialog = "license"
                        }
                    }
                ))
            }
        }
    }

    when (dialog) {
        "changes" -> ChangeLogDialog(onExport = { exportChanges.launch("minerguru-changes.txt") }, onDismiss = { dialog = null })
        "license" -> LicenseDialog(onDismiss = { dialog = null })
        "language" -> ChoiceDialog(
            L("Sprache", "Language"), AppLanguage.entries, store.language, ::languageLabel,
            onDismiss = { dialog = null },
            extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                {
                    TextButton(onClick = {
                        dialog = null
                        context.startActivity(
                            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text(L("Systemeinstellungen", "System settings")) }
                }
            } else null
        ) { dialog = null; store.updateLanguage(it) }
        "interval" -> ChoiceDialog(L("Abfrage-Intervall", "Polling interval"), intervals, store.pollingIntervalSeconds, ::intervalLabel,
            onDismiss = { dialog = null }) { dialog = null; store.updatePollingInterval(it) }
        "email" -> EmailAlertsDialog(store) { dialog = null }
        "background" -> ChoiceDialog(L("Überwachung im Hintergrund", "Background monitoring"), BackgroundMode.entries, store.backgroundMode, { it.title },
            onDismiss = { dialog = null }) { dialog = null; store.updateBackgroundMode(it) }
        "subnet" -> SubnetDialog(store.subnetPrefix, onDismiss = { dialog = null }) { dialog = null; store.updateSubnetPrefix(it) }
    }
}

/** Settings group as in the Android system settings: a header and rounded segments without dividers. */
/** Every write to a miner with old and new values. */
@Composable
private fun ChangeLogDialog(onExport: () -> Unit, onDismiss: () -> Unit) {
    val entries = remember { ChangeLog.shared.entries() }
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("Änderungsprotokoll", "Change log")) },
        text = {
            if (entries.isEmpty()) Text(L("Noch keine Änderungen.", "No changes yet."))
            else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                entries.forEach { entry ->
                    Column {
                        Text("${format.format(java.util.Date(entry.date))} · ${Privacy.ip(entry.host)} · ${ChangeLog.actionTitle(entry.action)} · ${entry.source.title}",
                            style = MaterialTheme.typography.titleSmall)
                        entry.fields.forEach { field ->
                            val line = field.summary
                            Text(if (Privacy.active) Privacy.freeText(line) else line, style = MaterialTheme.typography.bodySmall)
                        }
                        entry.failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("Schließen", "Close")) } },
        dismissButton = if (entries.isEmpty()) null else {
            { TextButton(onClick = onExport) { Text(L("Exportieren", "Export")) } }
        }
    )
}

@Composable
private fun SettingsGroup(title: String, rows: List<@Composable (Shape) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        GroupHeader(title, Modifier.semantics { heading() })
        rows.forEachIndexed { index, row -> row(segmentShape(index, rows.size)) }
    }
}

/** What the Stratum probe does, in full: the switch opens a connection to a third party. */
@Composable
private fun ProbeDescription() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            L(
                "MinerGuru verbindet sich dazu von diesem Gerät aus kurz mit dem Pool des Miners, meldet sich mit dessen Adresse und dem Worker-Namen „minerguru“ an, liest den ersten Auftrag und trennt wieder. So siehst du Blockhöhe, Belohnung, Gebühren und Empfänger und ob deine Adresse dabei ist.",
                "MinerGuru briefly connects from this device to the miner's pool, logs in with its address and the worker name \"minerguru\", reads the first job and disconnects. You then see block height, reward, fees and recipients, and whether your address is among them."
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            L(
                "Es werden keine Shares eingereicht; bei 17 getesteten Solo-Pools erschien kein zusätzlicher Worker. Abgefragt wird nur bei offener Detailseite, höchstens alle 5 Minuten je Pool. Der Pool sieht die IP-Adresse deines Anschlusses, wie beim Miner auch.",
                "No shares are submitted; none of 17 tested solo pools showed an extra worker. The pool is only asked while the detail page is open, at most every 5 minutes per pool. The pool sees the IP address of your connection, as it does for the miner."
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One rounded segment of a settings group. */
@Composable
private fun Segment(shape: Shape, content: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) { content() }
}

@Composable
private fun SettingsRow(shape: Shape, icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Segment(shape) {
        ListItem(
            leadingContent = { Icon(icon, null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(value) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.selectable(selected = false, role = Role.Button, onClick = onClick)
        )
    }
}

/** Whole row toggles the switch (one target for touch and TalkBack). */
@Composable
private fun SwitchRow(shape: Shape, icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Segment(shape) {
        ListItem(
            leadingContent = { Icon(icon, null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        )
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String, options: List<T>, selected: T, label: (T) -> String,
    onDismiss: () -> Unit, extra: (@Composable () -> Unit)? = null, onSelect: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("Abbrechen", "Cancel")) } },
        dismissButton = extra
    )
}

@Composable
private fun SubnetDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(current) }
    val valid = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}$""").matches(value.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("Standard-Subnetz", "Default subnet")) },
        text = {
            AppTextField(
                value = value, onValueChange = { value = it.trim() }, singleLine = true,
                label = { Text(L("Subnetz-Präfix (/24)", "Subnet prefix (/24)")) },
                supportingText = { Text(L("z. B. 192.168.1 → scannt .1 bis .254", "e.g. 192.168.1 → scans .1 to .254")) },
                isError = value.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().sensitive()
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }, enabled = valid) { Text(L("Speichern", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Abbrechen", "Cancel")) } }
    )
}
