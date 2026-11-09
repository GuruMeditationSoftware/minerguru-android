package app.minerguru.ui.components

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.R
import app.minerguru.model.ConnectionState
import app.minerguru.model.tr
import app.minerguru.model.Privacy
import app.minerguru.model.LatencyQuality
import app.minerguru.model.MinedCoin
import app.minerguru.model.MinerFormatting
import app.minerguru.model.MinerSystemInfo
import app.minerguru.ui.theme.MG
import app.minerguru.ui.theme.MGType
import kotlin.math.ln
import kotlin.math.pow

/** Elevated-looking container used for every content block. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    highlight: Color? = null,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.large,
    content: @Composable () -> Unit
) {
    // A highlight (high temperature, unsaved changes) colors the hairline, not the surface.
    val card = if (highlight != null) modifier.border(1.dp, highlight.copy(alpha = 0.6f), shape) else modifier
    AppCard(card.fillMaxWidth(), color = color, shape = shape, onClick = onClick) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionTitle(text: String, icon: ImageVector? = null, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        // The trailing note moves to its own line in narrow windows, so it never squeezes the title.
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            if (trailing != null) {
                Text(trailing, style = MGType.mono(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Big-number KPI tile. */
@Composable
fun MetricCard(
    title: String,
    value: String,
    unit: String?,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    warning: Boolean = false
) {
    Card(
        modifier = modifier.fillMaxWidth().fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, if (warning) MG.bad().copy(alpha = 0.6f) else MG.hairline()),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Quiet icon: the figure is what matters; only a warning brings in color.
                Icon(icon, null, tint = if (warning) MG.bad() else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                // Shrinks long values (and large font scales) instead of clipping them.
                Text(
                    value, style = MaterialTheme.typography.headlineSmallNumeric(), maxLines = 1,
                    color = if (warning) MG.bad() else Color.Unspecified,
                    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = MaterialTheme.typography.headlineSmall.fontSize),
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun androidx.compose.material3.Typography.headlineSmallNumeric() =
    headlineSmall.copy(fontWeight = FontWeight.Medium, fontFamily = FontFamily.Default, fontFeatureSettings = "tnum")

/**
 * Responsive grid: as many equal columns as fit cells of at least [minCellWidth] (between [minColumns] and [maxColumns]),
 * so phones show two tiles per row and tablets or unfolded foldables three or four.
 */
@Composable
fun <T> CardGrid(
    items: List<T>,
    minCellWidth: androidx.compose.ui.unit.Dp = 160.dp,
    spacing: androidx.compose.ui.unit.Dp = 12.dp,
    minColumns: Int = 2,
    maxColumns: Int = 4,
    cell: @Composable (T) -> Unit
) = androidx.compose.foundation.layout.BoxWithConstraints {
    // Larger fonts need wider cells; from 150 % on a phone shows one tile per row.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    val columns = gridColumns(maxWidth + spacing, minCellWidth * fontScale + spacing, if (fontScale >= 1.5f) 1 else minColumns, maxColumns)
        .coerceAtMost(maxOf(items.size, 1))
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { Box(Modifier.weight(1f).fillMaxHeight()) { cell(it) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Quiet text badge: colored mark, neutral text, no pill. */
@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, dot: Boolean = false) {
    Row(modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun CoinBadge(coin: MinedCoin, percentage: Int? = null, showFullName: Boolean = false) {
    val label = buildString {
        append(if (showFullName) "${coin.fullName} (${coin.symbol})" else coin.symbol)
        if (percentage != null) append(" $percentage%")
    }
    Chip(label, MG.coin(coin), dot = true)
}

@Composable
fun HardwareBadge(title: String) = Chip(title, MG.hardware(title))

@Composable
fun LatencyBadge(latencyMs: Double?) {
    val q = LatencyQuality.evaluate(latencyMs)
    val text = if (latencyMs != null && latencyMs > 0) MinerFormatting.formatLatency(latencyMs) else "—"
    Chip(text, MG.latency(q), dot = true)
}

@Composable
fun PoolModeBadge(info: MinerSystemInfo, compact: Boolean = true) {
    when {
        info.isDualPoolMining -> Chip(
            if (compact) "Dual ${info.dualPoolPrimaryPercent}%/${info.dualPoolSecondaryPercent}%"
            else "Dual-Pool (${info.dualPoolPrimaryPercent}% / ${info.dualPoolSecondaryPercent}%)",
            MG.purple(), icon = Icons.Rounded.AltRoute
        )
        info.isUsingFallbackStratum -> Chip(if (compact) "Fallback" else tr(R.string.fallback_pool_active), MG.orange(), dot = true)
        else -> Chip(if (compact) tr(R.string.primary) else tr(R.string.primary_pool_active), MG.green(), dot = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveCoinsBadges(info: MinerSystemInfo, showFullName: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (info.isDualPoolMining && info.primaryPoolCoin != info.secondaryPoolCoin) {
            CoinBadge(info.primaryPoolCoin, info.dualPoolPrimaryPercent)
            CoinBadge(info.secondaryPoolCoin, info.dualPoolSecondaryPercent)
        } else if (info.isDualPoolMining) {
            CoinBadge(info.primaryPoolCoin, showFullName = showFullName)
        } else {
            CoinBadge(info.activePoolCoin, showFullName = showFullName)
        }
    }
}

/** Connection state of a miner as the app's status block. */
@Composable
fun ConnectionDot(state: ConnectionState, isHighTemp: Boolean = false, size: androidx.compose.ui.unit.Dp = 8.dp) {
    // State in words for screen readers, not only as shape and color.
    val description = connectionDescription(state, isHighTemp)
    Box(Modifier.size(size + 6.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        StatusBlock(state, isHighTemp = isHighTemp, size = size)
    }
}

/** Small label/value pair block. */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StatusMessage(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val color = if (isError) MG.red() else MG.green()
    Row(
        // Announced by screen readers when it appears (save results, errors).
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isError) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
            null, tint = color, modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// region Charts

data class ChartSeries(
    val values: List<Double?>,
    val color: Color,
    val dashed: Boolean = false,
    val fill: Boolean = false
)

/** Line chart with optional gradient fill and horizontal threshold lines. All series share the x index. */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    thresholds: List<Pair<Double, Color>> = emptyList(),
    includeZero: Boolean = false,
    showAxis: Boolean = true,
    axisFormat: (Double) -> String = { MinerFormatting.format1(it) },
    description: String? = null
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = textColor, fontSize = 10.sp)

    Canvas(modifier.fillMaxWidth().height(150.dp).chartSemantics(description ?: chartSummary(series.firstOrNull()?.values.orEmpty(), axisFormat))) {
        val all = series.flatMap { it.values }.filterNotNull() + thresholds.map { it.first }
        if (all.isEmpty()) return@Canvas
        var lo = all.min()
        var hi = all.max()
        if (includeZero) lo = minOf(lo, 0.0)
        if (hi - lo < 1e-9) { hi += 1.0; lo -= 1.0 }
        val pad = (hi - lo) * 0.08
        if (!includeZero || lo != 0.0) lo -= pad
        hi += pad

        val leftPad = if (showAxis) 44.dp.toPx() else 0f
        val chartW = size.width - leftPad
        val chartH = size.height
        val n = series.maxOf { it.values.size }
        fun x(i: Int) = leftPad + if (n <= 1) chartW / 2 else chartW * i / (n - 1)
        fun y(v: Double) = (chartH - ((v - lo) / (hi - lo) * chartH)).toFloat()

        if (showAxis) {
            for (k in 0..3) {
                val v = lo + (hi - lo) * k / 3
                val yy = y(v)
                drawLine(gridColor, Offset(leftPad, yy), Offset(size.width, yy), strokeWidth = 1f)
                val layout = measurer.measure(axisFormat(v), labelStyle)
                drawText(layout, topLeft = Offset(0f, (yy - layout.size.height / 2f).coerceIn(0f, chartH - layout.size.height)))
            }
        }

        thresholds.forEach { (v, c) ->
            drawLine(c.copy(alpha = 0.7f), Offset(leftPad, y(v)), Offset(size.width, y(v)), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }

        series.forEach { s ->
            val path = Path()
            // Each run of values gets its own fill, a gap stays empty.
            val runs = mutableListOf<MutableList<Offset>>()
            var started = false
            s.values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                val point = Offset(x(i), y(v))
                if (started) { path.lineTo(point.x, point.y); runs.last().add(point) }
                else { path.moveTo(point.x, point.y); runs.add(mutableListOf(point)); started = true }
            }
            if (s.fill) runs.filter { it.size >= 2 }.forEach { run ->
                val fillPath = Path().apply {
                    moveTo(run.first().x, run.first().y)
                    run.drop(1).forEach { lineTo(it.x, it.y) }
                    lineTo(run.last().x, chartH); lineTo(run.first().x, chartH); close()
                }
                drawPath(fillPath, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), Color.Transparent)))
            }
            drawPath(
                path, s.color,
                style = Stroke(
                    width = if (s.dashed) 3f else 5f, cap = StrokeCap.Round,
                    pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 9f)) else null
                )
            )
        }
    }
}

/** Compact sparkline without axes. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, format: (Double) -> String = { MinerFormatting.format1(it) }) {
    if (values.size < 2) return
    Canvas(modifier.chartSemantics(chartSummary(values, format))) {
        val lo = values.min()
        var hi = values.max()
        val range = if (hi - lo < 1e-9) 1.0 else hi - lo
        fun x(i: Int) = size.width * i / (values.size - 1)
        fun y(v: Double) = (size.height - ((v - lo) / range * size.height * 0.9 + size.height * 0.05)).toFloat()
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.12f), Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Log-scale scatter/line chart for share difficulties. */
@Composable
fun LogScatterChart(
    xs: List<Long>,
    ys: List<Double>,
    seriesKeys: List<String>,
    colors: List<Color>,
    submitted: List<Boolean>,
    refLines: List<Double>,
    modifier: Modifier = Modifier,
    axisFormat: (Double) -> String
) {
    val chartDescription = if (ys.isEmpty()) "" else
        tr(R.string.share_difficulty_values_highest, ys.size, axisFormat(ys.max()))
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = textColor, fontSize = 10.sp)

    Canvas(modifier.fillMaxWidth().height(190.dp).chartSemantics(chartDescription)) {
        if (ys.isEmpty()) return@Canvas
        val all = ys + refLines
        val lo = ln(all.min().coerceAtLeast(1e-3)) - 0.15
        val hi = ln(all.max().coerceAtLeast(1e-3)) + 0.15
        val rightPad = 46.dp.toPx()
        val chartW = size.width - rightPad
        val t0 = xs.min()
        val t1 = xs.max().coerceAtLeast(t0 + 1)
        fun x(t: Long) = (chartW * (t - t0) / (t1 - t0).toFloat())
        fun y(v: Double) = (size.height - ((ln(v.coerceAtLeast(1e-3)) - lo) / (hi - lo) * size.height)).toFloat()

        val ticks = generateSequence(Math.floor(lo / ln(10.0))) { it + 1 }
            .takeWhile { it <= hi / ln(10.0) }.map { 10.0.pow(it) }.filter { ln(it) in lo..hi }.toList()
        ticks.forEach { v ->
            drawLine(gridColor, Offset(0f, y(v)), Offset(chartW, y(v)), strokeWidth = 1f)
            val layout = measurer.measure(axisFormat(v), labelStyle)
            drawText(layout, topLeft = Offset(chartW + 6.dp.toPx(), (y(v) - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)))
        }
        refLines.forEach { v ->
            drawLine(textColor.copy(alpha = 0.6f), Offset(0f, y(v)), Offset(chartW, y(v)), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }

        val groups = ys.indices.groupBy { seriesKeys[it] }
        groups.values.forEach { idx ->
            val color = colors[idx.first()]
            val path = Path()
            idx.forEachIndexed { k, i -> if (k == 0) path.moveTo(x(xs[i]), y(ys[i])) else path.lineTo(x(xs[i]), y(ys[i])) }
            drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
            idx.filter { submitted[it] }.forEach { i -> drawCircle(color, 5.dp.toPx() / 1.6f, Offset(x(xs[i]), y(ys[i]))) }
        }
    }
}

/** Short text version of a chart for screen readers: latest value, range and trend. */
fun chartSummary(values: List<Double?>, format: (Double) -> String): String {
    val v = values.filterNotNull()
    if (v.isEmpty()) return tr(R.string.no_data)
    val trend = when {
        v.size < 2 -> ""
        v.last() > v.first() * 1.02 -> tr(R.string.rising)
        v.last() < v.first() * 0.98 -> tr(R.string.falling)
        else -> tr(R.string.steady)
    }
    return tr(R.string.now_range_to, format(v.last()), format(v.min()), format(v.max()), trend)
}

private fun Modifier.chartSemantics(description: String): Modifier =
    semantics { contentDescription = description; role = androidx.compose.ui.semantics.Role.Image }

// endregion

/** For input fields and raw data whose content cannot be replaced: blurred in privacy mode. */
fun Modifier.sensitive(): Modifier = if (Privacy.active) this.blur(6.dp) else this

/** App bar button that toggles the privacy mode (eye). */
@Composable
fun PrivacyToggle(store: app.minerguru.service.MinerStore) {
    androidx.compose.material3.IconButton(onClick = { store.togglePrivacy() }) {
        Icon(
            if (Privacy.active) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            if (Privacy.active) tr(R.string.show_sensitive_data) else tr(R.string.hide_sensitive_data)
        )
    }
}

/** Password input: hidden by default, with a button to show it, and the password keyboard. */
@Composable
fun PasswordField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var visible by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    AppTextField(
        value, onValueChange, modifier = modifier, singleLine = true,
        label = { Text(tr(R.string.password)) },
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        trailingIcon = {
            androidx.compose.material3.IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    if (visible) tr(R.string.hide_password) else tr(R.string.show_password)
                )
            }
        }
    )
}
