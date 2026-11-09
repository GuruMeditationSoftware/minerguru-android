package app.minerguru.ui.miner

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import app.minerguru.model.L
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.minerguru.model.MinerDevice
import app.minerguru.model.MinerFormatting
import app.minerguru.model.ShareSample
import app.minerguru.service.MinerLogStream
import app.minerguru.service.ShareDifficultyTracker
import app.minerguru.service.StreamState
import app.minerguru.ui.components.*
import app.minerguru.ui.theme.MG
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryTab(
    miner: MinerDevice, tempWarningC: Double, pollingSeconds: Int,
    tracker: ShareDifficultyTracker, log: MinerLogStream, contentPadding: PaddingValues
) {
    val info = miner.latestInfo ?: return
    val h = miner.history
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(L("Live-Telemetrie & Verlauf", "Live telemetry & history"), Icons.Rounded.ShowChart, "${h.size} " + L("Messpunkte", "samples") + " • ${pollingSeconds}s")

        ChartCard("Hashrate (GH/s)", MinerFormatting.formatHashrate(info.hashRate), Icons.Rounded.Bolt, MG.orange()) {
            LineChart(listOf(ChartSeries(h.map { it.hashRateGHs }, MG.orange(), fill = true)))
        }
        ChartCard(L("ASIC & VR Temperatur (°C)", "ASIC & VR temperature (°C)"), "ASIC: ${MinerFormatting.format1(info.temp)} °C", Icons.Rounded.Thermostat, MG.green()) {
            LineChart(
                listOf(
                    ChartSeries(h.map { it.asicTempC }, MG.green()),
                    ChartSeries(h.map { s -> s.vrTempC?.takeIf { it > 0 } }, MG.teal(), dashed = true)
                ),
                thresholds = listOf(tempWarningC to MG.orange())
            )
        }
        val latencyValues = h.map { s -> s.poolLatencyMs?.takeIf { it > 0 } }
        ChartCard(L("Pool-Latenz (ms)", "Pool latency (ms)"), MinerFormatting.formatLatency(miner.activePoolLatencyMs), Icons.Rounded.Public, MG.mint()) {
            if (latencyValues.none { it != null }) EmptyHint(L("Noch keine Latenzdaten", "No latency data yet"))
            else LineChart(listOf(ChartSeries(latencyValues, MG.mint(), fill = true)), includeZero = true)
        }
        ChartCard(L("Leistungsaufnahme (Watt)", "Power draw (watts)"), "${MinerFormatting.format1(info.power)} W • ${MinerFormatting.format1(info.efficiencyJPerTH)} J/TH", Icons.Rounded.Bolt, MG.yellow()) {
            LineChart(listOf(ChartSeries(h.map { it.powerWatts }, MG.yellow(), fill = true)))
        }
        ShareDifficultyCard(tracker, log)
        LongTermHistoryCard(miner)
    }
}

@Composable
private fun ChartCard(title: String, value: String, icon: ImageVector, tint: Color, content: @Composable () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Text(" $title", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.labelLarge, color = tint)
            }
            content()
        }
    }
}

@Composable
private fun ShareDifficultyCard(tracker: ShareDifficultyTracker, log: MinerLogStream) {
    val samples by tracker.samples.collectAsState()
    val isSampling by tracker.isSampling.collectAsState()
    val lastSample by tracker.lastSample.collectAsState()
    val logState by log.state.collectAsState()
    val live = logState is StreamState.Streaming

    val hasPools = samples.any { it.pool != null }
    val keys = ShareSample.lineSeries(samples, 20_000)
    val p1 = MG.orange()
    val p2 = MG.green()
    val single = MG.indigo()
    val colors = samples.map { s -> if (!hasPools) single else if (s.pool == ShareSample.Pool.SECONDARY) p2 else p1 }

    ChartCard(
        L("Difficulty pro Share", "Difficulty per share"),
        samples.maxOfOrNull { it.difficulty }?.let { L("Bester", "Best") + ": ${MinerFormatting.formatDifficulty(it)}" } ?: "",
        Icons.Rounded.ScatterPlot, MG.indigo()
    ) {
        if (samples.isEmpty()) {
            EmptyHint(if (isSampling) L("Sammle Shares…", "Collecting shares…") else L("Noch keine Shares – die erste Stichprobe dauert etwa 10 Sekunden.", "No shares yet – the first sample takes about 10 seconds."))
        } else {
            LogScatterChart(
                xs = samples.map { it.date }, ys = samples.map { it.difficulty }, seriesKeys = keys, colors = colors,
                submitted = samples.map { it.isSubmitted },
                refLines = samples.mapNotNull { it.poolDifficulty }.distinct().sorted(),
                axisFormat = { MinerFormatting.formatDifficulty(it) }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (live) MG.green() else MaterialTheme.colorScheme.outline))
            Text(
                " " + when {
                    live -> L("Live aus dem Live-Log", "Live from the live log")
                    isSampling -> L("Stichprobe läuft…", "Sampling…")
                    lastSample != null -> L("Stichprobe alle 60 s • zuletzt", "Sample every 60 s • last") + " ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastSample!!))}"
                    else -> L("Stichprobe alle 60 s", "Sample every 60 s")
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
            Text("${samples.size} " + L("Ergebnisse", "results") + " • ${samples.count { it.isSubmitted }} " + L("über Pool-Diff.", "above pool diff."), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hasPools) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip("Pool 1", p1, dot = true); Chip("Pool 2", p2, dot = true)
        }
    }
}
