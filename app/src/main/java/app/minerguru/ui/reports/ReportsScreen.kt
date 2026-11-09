package app.minerguru.ui.reports

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.R
import app.minerguru.model.tr
import app.minerguru.model.MinerFormatting
import app.minerguru.model.MinerReport
import app.minerguru.model.PowerTariff
import app.minerguru.model.ReportPeriod
import app.minerguru.service.HistoryRecorder
import app.minerguru.service.MinerStore
import app.minerguru.ui.components.AppFilterChip
import app.minerguru.ui.components.ChartSeries
import app.minerguru.ui.components.EmptyHint
import app.minerguru.ui.components.LabeledValue
import app.minerguru.ui.components.LineChart
import app.minerguru.ui.components.PageContent
import app.minerguru.ui.components.ScreenScaffold
import app.minerguru.ui.components.SectionCard
import app.minerguru.ui.theme.MG
import app.minerguru.ui.theme.MGType
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date

private fun date(time: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))

private fun figures(report: MinerReport, tariff: PowerTariff): List<Pair<String, String>> {
    fun n(v: Double?, unit: String, format: (Double) -> String = MinerFormatting::format1) = v?.let { "${format(it)} $unit" } ?: "—"
    return listOf(
        tr(R.string.avg_hashrate) to (report.averageHashRateGHs?.let(MinerFormatting::formatHashrate) ?: "—"),
        tr(R.string.operating_time) to n(report.uptimePercent, "%"),
        tr(R.string.energy) to n(report.totalEnergyKWh, "kWh", MinerFormatting::format2),
        tr(R.string.electricity_cost) to tariff.formatPrice(report.totalCost),
        tr(R.string.avg_efficiency) to n(report.efficiencyJPerTH, "J/TH"),
        tr(R.string.highest_temperature) to n(report.maxTempC, "°C"),
        tr(R.string.accepted_shares) to report.sharesAccepted.toString(),
        tr(R.string.rejected_shares) to report.sharesRejected.toString()
    )
}

/** Reports over a period: output, uptime, energy and cost per miner, exportable as PDF and CSV. */
@Composable
fun ReportsScreen(store: MinerStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var period by rememberSaveable { mutableStateOf(ReportPeriod.LAST_7_DAYS) }
    var report by remember { mutableStateOf<MinerReport?>(null) }
    LaunchedEffect(period) {
        val (start, end) = period.interval()
        val points = HistoryRecorder.hourlyPoints(context, start, end)
        val tariff = store.powerTariff
        report = MinerReport.build(start, end, points, { id -> store.miner(id)?.displayName }, { tariff.priceAt(it) }, store.maintenanceRecords)
    }
    val fileName = "MinerGuru-Report-${period.name.lowercase()}"
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val r = report
        if (uri != null && r != null) context.contentResolver.openOutputStream(uri)?.use { it.write(r.csvText().toByteArray()) }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val r = report
        if (uri != null && r != null) context.contentResolver.openOutputStream(uri)?.use { ReportPdf.write(r, store.powerTariff, it) }
    }

    ScreenScaffold(
        tr(R.string.reports),
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) } },
        actions = {
            TextButton(onClick = { csv.launch("$fileName.csv") }, enabled = report?.rows?.isNotEmpty() == true) { Text("CSV") }
            TextButton(onClick = { pdf.launch("$fileName.pdf") }, enabled = report?.rows?.isNotEmpty() == true) { Text("PDF") }
        }
    ) { inner ->
        PageContent(inner) { pad ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReportPeriod.entries.forEach { p -> AppFilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.title) }) }
                }
                val r = report
                if (r == null || r.rows.isEmpty()) {
                    EmptyHint(tr(R.string.no_data_for_this_period_reports))
                    return@Column
                }
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${date(r.start)} – ${date(r.end)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        figures(r, store.powerTariff).chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { (title, value) -> LabeledValue(title, value, Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                if (r.daily.size > 1) {
                    SectionCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(tr(R.string.hashrate_per_day_gh_s), style = MaterialTheme.typography.titleSmall)
                            LineChart(listOf(ChartSeries(r.daily.map { it.second }, MG.accent(), fill = true)), includeZero = true)
                        }
                    }
                }
                r.rows.forEach { row ->
                    SectionCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.name, style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LabeledValue(tr(R.string.online), row.uptimePercent?.let { "${MinerFormatting.format1(it)} %" } ?: "—", Modifier.weight(1f), mono = true)
                                LabeledValue("Ø", row.averageHashRateGHs?.let(MinerFormatting::formatHashrate) ?: "—", Modifier.weight(1f), mono = true)
                                LabeledValue("kWh", MinerFormatting.format2(row.energyKWh), Modifier.weight(1f), mono = true)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LabeledValue(tr(R.string.cost), store.powerTariff.formatPrice(row.cost), Modifier.weight(1f), mono = true)
                                LabeledValue("J/TH", row.efficiencyJPerTH?.let(MinerFormatting::format1) ?: "—", Modifier.weight(1f), mono = true)
                                LabeledValue(tr(R.string.max_c), row.maxTempC?.let(MinerFormatting::format1) ?: "—", Modifier.weight(1f), mono = true)
                            }
                        }
                    }
                }
                if (r.maintenance.isNotEmpty()) {
                    SectionCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tr(R.string.maintenance_done), style = MaterialTheme.typography.titleSmall)
                            r.maintenance.forEach { Text("${date(it.doneAt)} · ${it.title}", style = MGType.mono(11.sp)) }
                        }
                    }
                }
            }
        }
    }
}

/** A4 pages in PostScript points, always black on white. */
private object ReportPdf {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val MARGIN = 40f

    fun write(report: MinerReport, tariff: PowerTariff, output: OutputStream) {
        val document = PdfDocument()
        val text = Paint().apply { isAntiAlias = true; textSize = 10f; color = android.graphics.Color.BLACK }
        val muted = Paint(text).apply { color = android.graphics.Color.GRAY; textSize = 9f }
        val title = Paint(text).apply { textSize = 20f }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 13f }
        val mono = Paint(text).apply { typeface = Typeface.MONOSPACE; textSize = 9f }
        val columns = listOf(MARGIN, 190f, 240f, 310f, 360f, 420f, 470f)
        var number = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, number).create())
        var y = MARGIN + 20

        fun newPageIfNeeded(space: Float = 16f) {
            if (y + space < HEIGHT - MARGIN) return
            document.finishPage(page)
            number++
            page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, number).create())
            y = MARGIN + 10
        }

        page.canvas.drawText(tr(R.string.minerguru_report), MARGIN, y, title)
        y += 18
        page.canvas.drawText("${date(report.start)} – ${date(report.end)}", MARGIN, y, muted)
        y += 28
        figures(report, tariff).chunked(4).forEach { row ->
            row.forEachIndexed { i, (name, value) ->
                val x = MARGIN + i * 130
                page.canvas.drawText(name, x, y, muted)
                page.canvas.drawText(value, x, y + 15, bold)
            }
            y += 38
        }
        y += 8
        val header = listOf(tr(R.string.miner), tr(R.string.online), tr(R.string.avg_hash), "kWh", tr(R.string.cost), "J/TH", tr(R.string.max_c))
        header.forEachIndexed { i, h -> page.canvas.drawText(h, columns[i], y, muted) }
        y += 14
        report.rows.forEach { row ->
            newPageIfNeeded()
            val cells = listOf(row.name.take(26), row.uptimePercent?.let(MinerFormatting::format1) ?: "—", row.averageHashRateGHs?.let(MinerFormatting::formatHashrate) ?: "—",
                MinerFormatting.format2(row.energyKWh), tariff.formatPrice(row.cost), row.efficiencyJPerTH?.let(MinerFormatting::format1) ?: "—", row.maxTempC?.let(MinerFormatting::format1) ?: "—")
            cells.forEachIndexed { i, c -> page.canvas.drawText(c, columns[i], y, mono) }
            y += 14
        }
        if (report.maintenance.isNotEmpty()) {
            y += 14
            newPageIfNeeded(40f)
            page.canvas.drawText(tr(R.string.maintenance_done), MARGIN, y, bold)
            y += 16
            report.maintenance.forEach {
                newPageIfNeeded()
                page.canvas.drawText("${date(it.doneAt)}  ${it.title}", MARGIN, y, text)
                y += 14
            }
        }
        document.finishPage(page)
        document.writeTo(output)
        document.close()
    }
}
