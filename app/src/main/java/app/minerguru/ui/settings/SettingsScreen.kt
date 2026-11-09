package app.minerguru.ui.settings

import app.minerguru.R
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
import app.minerguru.model.tr
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
    2 -> tr(R.string.interval_2_s_real_time)
    5 -> tr(R.string.interval_5_s_recommended)
    else -> "$sec s"
}

private fun languageLabel(lang: AppLanguage) = when (lang) {
    AppLanguage.SYSTEM -> tr(R.string.system_language)
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
        else store.showBanner(tr(R.string.without_notification_permission_minerguru_cannot), true)
    }

    // Asked again on return from the system settings.
    var notificationsReachUser by remember { mutableStateOf(AlertCenter.reachesUser(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsReachUser = AlertCenter.reachesUser(context)
        onPauseOrDispose { }
    }

    ScreenScaffold(tr(R.string.settings)) { inner ->
        PageContent(inner, maxWidth = 640.dp) { pad ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsGroup(tr(R.string.appearance), listOf(
                    { shape -> SettingsRow(shape, Icons.Rounded.Language, tr(R.string.language), languageLabel(store.language)) { dialog = "language" } },
                    { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.VisibilityOff, tr(R.string.privacy_mode),
                            tr(R.string.scramble_ip_wallet_and_mac),
                            Privacy.active
                        ) { store.togglePrivacy() }
                    }
                ))

                SettingsGroup(tr(R.string.refresh_monitoring), listOf(
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.Timer, tr(R.string.polling_interval), intervalLabel(store.pollingIntervalSeconds)) {
                            dialog = "interval"
                        }
                    },
                    { shape ->
                        val tempLabel = tr(R.string.temperature_warning_asic)
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
                                            tr(R.string.this_polling_only_runs_while),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                ))

                SettingsGroup(tr(R.string.expected_addresses), listOf(
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
                                            { Text(tr(R.string.this_is_not_a_valid_address)) }
                                        } else null,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Text(
                                    tr(R.string.minerguru_warns_when_a_miner),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                ))

                SettingsGroup(tr(R.string.interventions), listOf(
                    { shape ->
                        SwitchRow(shape, Icons.Rounded.Lock, tr(R.string.read_only),
                            tr(R.string.change_nothing_on_miners), store.isReadOnly) { store.updateReadOnly(it) }
                    },
                    { shape ->
                        SwitchRow(shape, Icons.Rounded.RestartAlt, tr(R.string.restart_without_shares),
                            tr(R.string.at_most_once_an_hour),
                            store.restartsOnFlatline) { store.updateRestartsOnFlatline(it) }
                    },
                    { shape ->
                        SwitchRow(shape, Icons.Rounded.RestartAlt, tr(R.string.bring_back_to_primary_pool),
                            tr(R.string.at_most_once_an_hour),
                            store.restartsToPrimary) { store.updateRestartsToPrimary(it) }
                    },
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.History, tr(R.string.change_log),
                            tr(R.string.every_change_on_miners)) { dialog = "changes" }
                    }
                ))

                SettingsGroup(tr(R.string.notifications), buildList<@Composable (Shape) -> Unit> {
                    add { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.NotificationsActive, tr(R.string.notify_me_about_problems),
                            tr(R.string.alerts_only_no_advertising),
                            store.alertsEnabled
                        ) { enabled ->
                            if (enabled && !AlertCenter.canPostNotifications(context)) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else store.updateAlertsEnabled(enabled)
                        }
                    }
                    if (store.alertsEnabled && !notificationsReachUser) {
                        add { shape ->
                            SettingsRow(shape, Icons.Rounded.NotificationsOff, tr(R.string.notifications_are_off_in_the),
                                tr(R.string.minerguru_cannot_show_you_anything)) {
                                val systemSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                context.startActivity(systemSettings)
                            }
                        }
                    }
                    if (store.alertsEnabled) {
                        AlertKind.entries.forEach { kind ->
                            add { shape ->
                                SwitchRow(shape, Icons.Rounded.WifiOff, kind.title, tr(R.string.mute_single_miners_in_the),
                                    kind !in store.disabledAlertKinds) { store.updateAlertKind(kind, it) }
                            }
                        }
                        add { shape ->
                            SettingsRow(shape, Icons.Rounded.BatteryChargingFull, tr(R.string.monitoring_in_background), store.backgroundMode.title) {
                                dialog = "background"
                            }
                        }
                        add { shape ->
                            val active = store.emailSettings.isEnabled && store.emailSettings.isComplete
                            SettingsRow(shape, Icons.Rounded.Email, tr(R.string.email_alerts), if (active) tr(R.string.active) else tr(R.string.off)) {
                                dialog = "email"
                            }
                        }
                    }
                })

                SettingsGroup(tr(R.string.block_view), listOf(
                    { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.ViewInAr, tr(R.string.fetch_the_block_template_from),
                            tr(R.string.for_miners_that_do_not),
                            store.stratumProbeEnabled
                        ) { store.updateStratumProbe(it) }
                    },
                    { shape -> Segment(shape) { ProbeDescription() } }
                ))

                SettingsGroup(tr(R.string.network_scan), listOf(
                    { shape ->
                        SettingsRow(
                            shape, Icons.Rounded.TravelExplore, tr(R.string.default_subnet_24),
                            "${Privacy.ip(store.subnetPrefix)}.1 – .254"
                        ) { dialog = "subnet" }
                    },
                    { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.Radar, tr(R.string.scan_automatically_at_launch),
                            tr(R.string.when_no_miners_are_configured),
                            store.autoScanOnEmptyLaunch
                        ) { store.updateAutoScan(it) }
                    }
                ))

                SettingsGroup(tr(R.string.updates), listOf(
                    { shape ->
                        SwitchRow(
                            shape, Icons.Rounded.SystemUpdate,
                            tr(R.string.check_for_new_versions_daily),
                            tr(R.string.minerguru_asks_github_for_the),
                            store.updates.isEnabled
                        ) { store.updates.updateEnabled(it) }
                    },
                    { shape ->
                        val updates = store.updates
                        val available = updates.availableVersion
                        SettingsRow(
                            shape, Icons.Rounded.Refresh,
                            if (available != null) tr(R.string.go_to_download) else tr(R.string.check_now),
                            when {
                                updates.status == UpdateStatus.CHECKING -> tr(R.string.checking)
                                updates.status == UpdateStatus.FAILED -> tr(R.string.github_cannot_be_reached_right)
                                available != null -> tr(R.string.minerguru_is_available, available)
                                updates.status == UpdateStatus.UP_TO_DATE -> tr(R.string.minerguru_is_up_to_date)
                                else -> tr(R.string.not_checked_yet)
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

                SettingsGroup(tr(R.string.about), listOf(
                    { shape ->
                        Segment(shape) {
                            ListItem(
                                leadingContent = { Icon(Icons.Rounded.Info, null) },
                                headlineContent = { Text("MinerGuru ${BuildConfig.VERSION_NAME}") },
                                supportingContent = {
                                    Column {
                                        Text(AppInfo.MADE_BY)
                                        Text(tr(R.string.monitoring_and_control_of_bitaxe))
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    },
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.BugReport, tr(R.string.export_diagnostic_log),
                            tr(R.string.only_on_this_device_contains)) {
                            exportLog.launch("minerguru-log.txt")
                        }
                    },
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.Feedback, tr(R.string.send_feedback), tr(R.string.on_github)) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.FEEDBACK_URL))) }
                        }
                    },
                    { shape ->
                        SettingsRow(shape, Icons.Rounded.Description, tr(R.string.license), tr(R.string.license_and_third_party_notices)) {
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
            tr(R.string.language), AppLanguage.entries, store.language, ::languageLabel,
            onDismiss = { dialog = null },
            extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                {
                    TextButton(onClick = {
                        dialog = null
                        context.startActivity(
                            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text(tr(R.string.system_settings)) }
                }
            } else null
        ) { dialog = null; store.updateLanguage(it) }
        "interval" -> ChoiceDialog(tr(R.string.polling_interval), intervals, store.pollingIntervalSeconds, ::intervalLabel,
            onDismiss = { dialog = null }) { dialog = null; store.updatePollingInterval(it) }
        "email" -> EmailAlertsDialog(store) { dialog = null }
        "background" -> ChoiceDialog(tr(R.string.monitoring_in_background), BackgroundMode.entries, store.backgroundMode, { it.title },
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
        title = { Text(tr(R.string.change_log)) },
        text = {
            if (entries.isEmpty()) Text(tr(R.string.no_changes_yet))
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.close)) } },
        dismissButton = if (entries.isEmpty()) null else {
            { TextButton(onClick = onExport) { Text(tr(R.string.export)) } }
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
            tr(R.string.minerguru_briefly_connects_from_this),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            tr(R.string.no_shares_are_submitted_none),
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
        dismissButton = extra
    )
}

@Composable
private fun SubnetDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(current) }
    val valid = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}$""").matches(value.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.default_subnet)) },
        text = {
            AppTextField(
                value = value, onValueChange = { value = it.trim() }, singleLine = true,
                label = { Text(tr(R.string.subnet_prefix_24)) },
                supportingText = { Text(tr(R.string.e_g_192_168_1)) },
                isError = value.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().sensitive()
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }, enabled = valid) { Text(tr(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } }
    )
}
