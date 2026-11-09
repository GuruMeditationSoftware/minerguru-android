package app.minerguru.ui.support

import app.minerguru.model.Privacy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.minerguru.model.L
import app.minerguru.service.MinerStore
import app.minerguru.service.OtpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Asks for the one-time code whenever the store needs one. Sits once at the root of the app. */
@Composable
fun OtpCodeDialog(store: MinerStore) {
    val request = store.otpRequest ?: return
    var code by remember(request) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { request.answer.complete(null) },
        title = { Text(L("Einmalcode für ${request.minerName}", "One-time code for ${request.minerName}")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.problem ?: L(
                    "Dieser Miner schützt seine Einstellungen mit einem Einmalcode. Gib den 6-stelligen Code aus deiner Authenticator-App ein. Die Sitzung gilt bis zu 24 Stunden.",
                    "This miner protects its settings with a one-time code. Enter the 6-digit code from your authenticator app. The session lasts up to 24 hours."
                ))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    placeholder = { Text("123456") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { request.answer.complete(code) }) { Text(L("Bestätigen", "Confirm")) }
        },
        dismissButton = {
            TextButton(onClick = { request.answer.complete(null) }) { Text(L("Abbrechen", "Cancel")) }
        }
    )
}

/** Shows old and new values before a write by hand. Sits once at the root of the app. */
@Composable
fun WriteConfirmDialog(store: MinerStore) {
    val request = store.writeRequest ?: return
    AlertDialog(
        onDismissRequest = { request.answer.complete(false) },
        title = { Text(L("An ${request.minerName} übertragen?", "Send to ${request.minerName}?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (request.fields.isEmpty()) Text(L("Es ändert sich kein Wert.", "No value changes."))
                request.fields.forEach { field ->
                    val line = field.summary
                    Text(if (Privacy.active) Privacy.freeText(line) else line)
                }
                if (request.restarts) Text(L("Danach startet der Miner neu.", "The miner restarts afterwards."))
            }
        },
        confirmButton = {
            TextButton(onClick = { request.answer.complete(true) }) { Text(L("Übertragen", "Send")) }
        },
        dismissButton = {
            TextButton(onClick = { request.answer.complete(false) }) { Text(L("Abbrechen", "Cancel")) }
        }
    )
}

/** Schedules skip miners that have no valid session: this names them and lets the user enter a code. */
@Composable
fun OtpWaitingNotice(store: MinerStore) {
    val waiting = store.minersAwaitingOtp
    if (waiting.isEmpty()) return
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            L("Zeitpläne laufen auf diesen Minern nur mit gültiger Sitzung (bis zu 24 Stunden).",
                "Schedules only run on these miners with a valid session (up to 24 hours)."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (miner in waiting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(miner.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        problem = try {
                            store.enterOtpCode(miner)
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: OtpException) {
                            null
                        } catch (e: Exception) {
                            e.message
                        }
                    }
                }) { Text(L("Code eingeben", "Enter code")) }
            }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
