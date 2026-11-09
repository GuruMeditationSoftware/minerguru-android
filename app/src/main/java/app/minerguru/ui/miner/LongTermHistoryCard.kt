package app.minerguru.ui.miner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.minerguru.R
import app.minerguru.model.HistoryCsv
import app.minerguru.model.HistoryMetric
import app.minerguru.model.HistoryPoint
import app.minerguru.model.HistoryRange
import app.minerguru.model.tr
import app.minerguru.model.MinerDevice
import app.minerguru.model.MinerFormatting
import app.minerguru.service.HistoryRecorder
import app.minerguru.ui.components.AppFilterChip
import app.minerguru.model.HistoryQuery
import app.minerguru.ui.components.ChartSeries
import app.minerguru.ui.components.EmptyHint
import app.minerguru.ui.components.LabeledValue
import app.minerguru.ui.components.LineChart
import app.minerguru.ui.components.SectionCard
import app.minerguru.ui.components.SectionTitle
import app.minerguru.ui.theme.MG

/** Stored telemetry of one miner over days and months, from the local history database. */
@Composable
fun LongTermHistoryCard(miner: MinerDevice) {
    val context = LocalContext.current
    var range by rememberSaveable { mutableStateOf(HistoryRange.WEEK) }
    var metric by rememberSaveable { mutableStateOf(HistoryMetric.HASHRATE) }
    var points by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    LaunchedEffect(miner.id, range) {
        val now = System.currentTimeMillis()
        points = HistoryRecorder.points(context, miner.id, now - range.seconds * 1000, now)
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use { it.write(HistoryCsv.text(points) { miner.displayName }.toByteArray()) }
    }
    val values = points.mapNotNull(metric::value)

    SectionTitle(tr(R.string.long_term_history), Icons.Rounded.History)
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryRange.entries.forEach { r -> AppFilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.title) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HistoryMetric.entries.forEach { m ->
                    AppFilterChip(selected = m == metric, onClick = { metric = m }, label = { Text(m.title) })
                }
            }
            if (values.isEmpty()) {
                EmptyHint(tr(R.string.no_data_for_this_period))
            } else {
                LineChart(listOf(ChartSeries(metric.valuesWithGaps(points, HistoryQuery.step(range.seconds)), MG.accent(), fill = true)), axisFormat = { MinerFormatting.format1(it) })
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabeledValue("Ø", metric.format(values.average()), Modifier.weight(1f), mono = true)
                    LabeledValue("Min", metric.format(values.min()), Modifier.weight(1f), mono = true)
                    LabeledValue("Max", metric.format(values.max()), Modifier.weight(1f), mono = true)
                }
                val recorded = points.sumOf { it.recordedMinutes }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (recorded > 0) {
                        Text(tr(R.string.uptime) + MinerFormatting.format1(points.sumOf { it.onlineMinutes } * 100.0 / recorded) + " %",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = { csv.launch("${miner.displayName}-${range.name.lowercase()}.csv") }) { Text(tr(R.string.save_as_csv)) }
                }
            }
        }
    }
}
