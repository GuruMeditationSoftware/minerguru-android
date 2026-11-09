package app.minerguru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.minerguru.model.L
import app.minerguru.service.EmailAlerts
import app.minerguru.service.MinerStore
import app.minerguru.service.SmtpSecurity
import app.minerguru.ui.components.AppTextField
import app.minerguru.ui.components.ConnectedToggleGroup
import app.minerguru.ui.components.StatusMessage
import kotlinx.coroutines.launch

/** Mail server, addresses and a test mail. The password is encrypted with a key in the Android Keystore. */
@Composable
fun EmailAlertsDialog(store: MinerStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(store.emailSettings) }
    var password by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var testing by remember { mutableStateOf(false) }
    // An untouched field keeps the stored password.
    fun save() = store.updateEmailSettings(draft, password.takeIf { it.isNotEmpty() })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("E-Mail-Warnungen", "Email alerts")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("MinerGuru schickt jede Warnung zusätzlich an diese Adressen, über deinen eigenen Mailserver. Das Passwort bleibt verschlüsselt auf diesem Gerät.",
                    "MinerGuru also sends every alert to these addresses through your own mail server. The password stays encrypted on this device."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Warnungen per E-Mail senden", "Send alerts by email"), Modifier.weight(1f))
                    Switch(draft.isEnabled, { draft = draft.copy(isEnabled = it) })
                }
                AppTextField(draft.host, { draft = draft.copy(host = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Server") }, placeholder = { Text("smtp.example.com") })
                val modes = SmtpSecurity.entries
                ConnectedToggleGroup(modes.map { if (it == SmtpSecurity.NONE) L("Keine", "None") else it.title }, modes.indexOf(draft.security), { i ->
                    draft = draft.copy(security = modes[i], port = modes[i].defaultPort)
                }, Modifier.fillMaxWidth(), compact = true)
                if (draft.security == SmtpSecurity.NONE) {
                    Text(L("Ohne Verschlüsselung gehen Passwort und Inhalt lesbar durchs Netz. Nur für einen Mailserver im eigenen Netz.",
                        "Without encryption, password and content cross the network in the clear. Only for a mail server on your own network."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                AppTextField(draft.port.toString(), { v -> draft = draft.copy(port = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) }, Modifier.fillMaxWidth(),
                    label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                AppTextField(draft.username, { draft = draft.copy(username = it.trim()) }, Modifier.fillMaxWidth(), label = { Text(L("Benutzername", "Username")) })
                AppTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(L("Passwort", "Password")) },
                    placeholder = { if (EmailAlerts.hasPassword(context)) Text(L("gespeichert", "saved")) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                AppTextField(draft.sender, { draft = draft.copy(sender = it.trim()) }, Modifier.fillMaxWidth(), label = { Text(L("Absender", "Sender")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                AppTextField(draft.recipients, { draft = draft.copy(recipients = it) }, Modifier.fillMaxWidth(), label = { Text(L("Empfänger, mit Komma getrennt", "Recipients, separated by commas")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                result?.let { (text, isError) -> StatusMessage(text, isError) }
            }
        },
        confirmButton = { TextButton(onClick = { save(); onDismiss() }) { Text(L("Sichern", "Save")) } },
        dismissButton = {
            TextButton(enabled = draft.isComplete && !testing, onClick = {
                save()
                testing = true
                result = null
                scope.launch {
                    result = try {
                        EmailAlerts.sendTest(context, draft)
                        L("Test-Mail versendet.", "Test mail sent.") to false
                    } catch (e: Exception) {
                        (e.message ?: L("Mailserver nicht erreichbar.", "Mail server not reachable.")) to true
                    }
                    testing = false
                }
            }) { Text(L("Test-Mail", "Test mail")) }
        }
    )
}
