package app.minerguru.ui.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.R
import app.minerguru.model.tr

/** The files wrap at 80 columns, a phone is narrower. Only full lines continue, so headings and lists stay as they are. */
private val WRAPPED_LINE = Regex("""(?m)^(.{60,})\n *(?=\S)""")

/** The app's license and the notices of the libraries inside it, from the assets. */
@Composable
fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        listOf("LICENSE.txt", "THIRD-PARTY-NOTICES.txt").joinToString("\n\n\n") { name ->
            context.assets.open(name).bufferedReader().use { it.readText() }.trim()
        }.replace(WRAPPED_LINE, "\$1 ")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.license)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr(R.string.open_source_under_the_mit),
                    style = MaterialTheme.typography.bodyMedium
                )
                SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.close)) } }
    )
}
