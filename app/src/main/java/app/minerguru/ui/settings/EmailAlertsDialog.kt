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
import app.minerguru.R
import app.minerguru.model.tr
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
        title = { Text(tr(R.string.email_alerts)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(R.string.minerguru_also_sends_every_alert),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.send_alerts_by_email), Modifier.weight(1f))
                    Switch(draft.isEnabled, { draft = draft.copy(isEnabled = it) })
                }
                AppTextField(draft.host, { draft = draft.copy(host = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Server") }, placeholder = { Text("smtp.example.com") })
                val modes = SmtpSecurity.entries
                ConnectedToggleGroup(modes.map { if (it == SmtpSecurity.NONE) tr(R.string.none) else it.title }, modes.indexOf(draft.security), { i ->
                    draft = draft.copy(security = modes[i], port = modes[i].defaultPort)
                }, Modifier.fillMaxWidth(), compact = true)
                if (draft.security == SmtpSecurity.NONE) {
                    Text(tr(R.string.without_encryption_password_and_content),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                AppTextField(draft.port.toString(), { v -> draft = draft.copy(port = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) }, Modifier.fillMaxWidth(),
                    label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                AppTextField(draft.username, { draft = draft.copy(username = it.trim()) }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.username)) })
                AppTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.password)) },
                    placeholder = { if (EmailAlerts.hasPassword(context)) Text(tr(R.string.saved)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                AppTextField(draft.sender, { draft = draft.copy(sender = it.trim()) }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.sender)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                AppTextField(draft.recipients, { draft = draft.copy(recipients = it) }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.recipients_separated_by_commas)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                result?.let { (text, isError) -> StatusMessage(text, isError) }
            }
        },
        confirmButton = { TextButton(onClick = { save(); onDismiss() }) { Text(tr(R.string.save_entry)) } },
        dismissButton = {
            TextButton(enabled = draft.isComplete && !testing, onClick = {
                save()
                testing = true
                result = null
                scope.launch {
                    result = try {
                        EmailAlerts.sendTest(context, draft)
                        tr(R.string.test_mail_sent) to false
                    } catch (e: Exception) {
                        (e.message ?: tr(R.string.mail_server_not_reachable)) to true
                    }
                    testing = false
                }
            }) { Text(tr(R.string.test_mail)) }
        }
    )
}
