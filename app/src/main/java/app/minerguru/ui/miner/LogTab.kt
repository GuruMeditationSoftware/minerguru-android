package app.minerguru.ui.miner

import app.minerguru.R
import app.minerguru.ui.components.AppTextField
import app.minerguru.ui.components.AppFilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.layout.PaddingValues
import app.minerguru.model.tr
import app.minerguru.model.Privacy
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.model.LogCategoryFilter
import app.minerguru.model.MinerDevice
import app.minerguru.model.MinerLogEntry
import app.minerguru.model.MinerLogLevel
import app.minerguru.service.MinerLogStream
import app.minerguru.service.StreamState
import app.minerguru.ui.components.Chip
import app.minerguru.ui.theme.MG

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LogTab(miner: MinerDevice, log: MinerLogStream, contentPadding: PaddingValues) {
    val entries by log.entries.collectAsState()
    val state by log.state.collectAsState()
    val total by log.totalLines.collectAsState()
    var category by rememberSaveable { mutableStateOf(LogCategoryFilter.ALL) }
    var search by rememberSaveable { mutableStateOf("") }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current

    val filtered = remember(entries, category, search) { MinerLogStream.filtered(entries, category, search) }
    val listState = rememberLazyListState()
    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) listState.scrollToItem(filtered.size - 1)
    }
    fun exportText() = MinerLogStream.exportText(miner.displayName, miner.ipAddress, miner.hardwareTitle, filtered, entries.size, category, search)

    Column(Modifier.fillMaxSize().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Wraps onto a second line at large font sizes instead of pushing buttons off screen.
        FlowRow(
            itemVerticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (log.isRunning) {
                FilledTonalButton(onClick = { log.pause() }) { Icon(Icons.Rounded.Pause, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.stop_action)) }
            } else {
                FilledTonalButton(onClick = { log.start(miner.ipAddress) }) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp))
                    Text(if (state is StreamState.Paused) tr(R.string.resume_short) else tr(R.string.start_live_log))
                }
            }
            val (label, color) = when (state) {
                StreamState.Streaming -> "Live" to MG.green()
                StreamState.Connecting -> tr(R.string.connecting) to MG.yellow()
                StreamState.Paused -> tr(R.string.paused) to MG.orange()
                StreamState.Stopped -> tr(R.string.stopped) to MaterialTheme.colorScheme.onSurfaceVariant
                is StreamState.Error -> tr(R.string.disconnected) to MG.red()
            }
            Chip(label, color, dot = true)
            Spacer(Modifier.weight(1f))
            FilledTonalIconToggleButton(checked = autoScroll, onCheckedChange = { autoScroll = it }) {
                Icon(Icons.Rounded.KeyboardDoubleArrowDown, "Auto-Scroll")
            }
            // Less frequent actions sit in a menu, so the row still fits at 360 dp and large font sizes.
            Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr(R.string.export)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        enabled = filtered.isNotEmpty(),
                        onClick = {
                            menu = false
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, MinerLogEntry.exportFileName(miner.displayName, miner.ipAddress))
                                putExtra(android.content.Intent.EXTRA_TEXT, exportText())
                            }
                            context.startActivity(android.content.Intent.createChooser(send, tr(R.string.export_log)))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr(R.string.copy)) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        enabled = filtered.isNotEmpty(),
                        onClick = { menu = false; copyToClipboard(context, "Log", exportText()) }
                    )
                    DropdownMenuItem(
                        text = { Text(tr(R.string.clear)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        enabled = entries.isNotEmpty(),
                        onClick = { menu = false; log.clear() }
                    )
                }
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogCategoryFilter.entries.forEach { c ->
                AppFilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
            }
        }
        AppTextField(
            value = search, onValueChange = { search = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(tr(R.string.search_log)) }
        )

        Column(
            Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.medium).padding(8.dp)
        ) {
            if (filtered.isEmpty()) {
                val hint = when {
                    state is StreamState.Error -> (state as StreamState.Error).message
                    state is StreamState.Connecting -> tr(R.string.connecting_to) + " ws://${Privacy.ip(miner.ipAddress)}/api/ws…"
                    entries.isNotEmpty() -> tr(R.string.no_entries_for_the_current)
                    state is StreamState.Streaming -> tr(R.string.connected_waiting_for_log_output)
                    else -> tr(R.string.the_live_console_is_off)
                }
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(state = listState) { items(filtered) { LogRow(it) } }
            }
        }
        Text("${filtered.size} " + tr(R.string.of) + " ${entries.size} " + tr(R.string.lines_in_text) + " • $total " + tr(R.string.received), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
private fun LogRow(e: MinerLogEntry) {
    // Time in the user's locale (24 h or AM/PM as set on the device).
    val timeFmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM) }
    val levelColor = when {
        e.isShareRejected -> MG.text(MG.red())
        e.level == MinerLogLevel.ERROR -> MG.text(MG.red())
        e.level == MinerLogLevel.WARNING -> MG.text(MG.orange())
        e.isShareAccepted -> MG.text(MG.green())
        e.level == MinerLogLevel.DEBUG -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        buildString {
            append(timeFmt.format(java.util.Date(e.receivedAt))).append(' ').append(e.level.shortLabel).append(' ')
            e.componentTag?.let { append('[').append(it).append("] ") }
            append(Privacy.freeText(e.message))
        },
        color = levelColor, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = if (e.isShareAccepted || e.isShareRejected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
