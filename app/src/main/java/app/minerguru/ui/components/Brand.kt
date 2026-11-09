package app.minerguru.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import app.minerguru.model.ConnectionState
import app.minerguru.model.L
import app.minerguru.model.MinerDevice
import app.minerguru.model.MinerFirmware
import app.minerguru.model.MinerSystemInfo
import app.minerguru.ui.theme.MG
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// region Mark

/**
 * The app's mark: a square m made of wide bars. Its right leg stops short and runs into
 * a small ASIC chip with pins, the chip that does the mining. Drawn on a 24-unit grid.
 */
@Composable
fun MinerGuruMark(size: Dp, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, chip: Color = MG.accent()) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val letter = Path().apply {
            moveTo(3.8f * u, 20.2f * u); lineTo(3.8f * u, 3.8f * u); lineTo(20.2f * u, 3.8f * u); lineTo(20.2f * u, 15f * u)
            moveTo(12f * u, 3.8f * u); lineTo(12f * u, 14f * u)
        }
        drawPath(letter, color, style = Stroke(width = this.size.minDimension * 0.15f, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
        drawRoundRect(chip, Offset(17.8f * u, 16.4f * u), Size(4.8f * u, 4.8f * u), CornerRadius(0.5f * u))
        listOf(
            Rect(16.6f, 17.45f, 17.8f, 18.15f), Rect(16.6f, 19.45f, 17.8f, 20.15f),
            Rect(22.6f, 17.45f, 23.8f, 18.15f), Rect(22.6f, 19.45f, 23.8f, 20.15f),
            Rect(18.85f, 21.2f, 19.55f, 22.4f), Rect(20.85f, 21.2f, 21.55f, 22.4f)
        ).forEach { r -> drawRect(chip, Offset(r.left * u, r.top * u), Size(r.width * u, r.height * u)) }
    }
}

// endregion

// region Status block

/**
 * Connection state as the app's status block: a filled square when online, a diamond when too hot,
 * an outline while checking and an outline with a slash when offline. Shape and color both carry it.
 */
@Composable
fun StatusBlock(state: ConnectionState, modifier: Modifier = Modifier, isHighTemp: Boolean = false, size: Dp = 8.dp) {
    val color = when {
        state is ConnectionState.Online && isHighTemp -> MG.warning()
        state is ConnectionState.Online -> MG.good()
        state is ConnectionState.Offline -> MG.bad()
        else -> MG.idle()
    }
    Canvas(modifier.size(size)) { drawStatusBlock(state, isHighTemp, color, this.size.minDimension) }
}

/** Status block of side [s] at the current origin. */
private fun DrawScope.drawStatusBlock(state: ConnectionState, isHighTemp: Boolean, color: Color, s: Float) {
    val line = max(1f, s * 0.16f)
    when {
        state is ConnectionState.Online && isHighTemp -> rotate(45f, Offset(s / 2, s / 2)) {
            val d = s * 0.78f
            drawRect(color, Offset((s - d) / 2, (s - d) / 2), Size(d, d))
        }
        state is ConnectionState.Online -> drawRect(color, Offset.Zero, Size(s, s))
        state is ConnectionState.Offline -> {
            drawRect(color, Offset(line / 2, line / 2), Size(s - line, s - line), style = Stroke(line))
            drawLine(color, Offset(0f, s), Offset(s, 0f), line)
        }
        else -> drawRect(color, Offset(line / 2, line / 2), Size(s - line, s - line), style = Stroke(line))
    }
}

fun connectionDescription(state: ConnectionState, isHighTemp: Boolean): String = when {
    state is ConnectionState.Offline -> "Offline"
    state is ConnectionState.Online && isHighTemp -> L("Online, Temperatur hoch", "Online, temperature high")
    state is ConnectionState.Online -> "Online"
    else -> L("Verbindung wird geprüft", "Checking connection")
}

// endregion

// region Device card

/** What a device card shows of a miner: the board family, its model line and how many ASICs sit on it. */
data class DeviceCardSpec(
    /** Silkscreen name on the board, e.g. `bitaxe` or `nerdqaxe++` */
    val family: String,
    /** Model line under the chips, e.g. `Gamma 601` */
    val model: String,
    val chipCount: Int,
    val chipModel: String?,
    val tft: Boolean
) {
    /** Chip grid as columns × rows. */
    val chipGrid: Pair<Int, Int>
        get() = when {
            chipCount <= 1 -> 1 to 1
            chipCount == 2 -> 2 to 1
            chipCount <= 4 -> 2 to 2
            chipCount <= 6 -> 3 to 2
            chipCount <= 8 -> 4 to 2
            else -> ceil(chipCount / 2.0).toInt() to 2
        }

    val modelLine: String
        get() = listOfNotNull(model, chipModel?.let { if (chipCount > 1) "$chipCount× $it" else it })
            .filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        /** Spec from the miner's current telemetry, or from the remembered hardware title while it is offline. */
        fun of(miner: MinerDevice) = of(miner.hardwareTitle, miner.latestInfo)

        /** Spec of a miner found by the network scan. */
        fun of(info: MinerSystemInfo) = of(info.detectedHardwareTitle, info)

        private fun of(title: String, info: MinerSystemInfo?): DeviceCardSpec {
            val words = title.split(" ", limit = 2)
            val lower = title.lowercase()
            return DeviceCardSpec(
                family = words.first().lowercase(),
                model = words.getOrElse(1) { "" },
                chipCount = info?.asicCount ?: guessedChipCount(lower),
                chipModel = info?.asicModel,
                tft = info?.let { it.firmware == MinerFirmware.NERD_OS } ?: lower.startsWith("nerd")
            )
        }

        private fun guessedChipCount(title: String): Int = when {
            "eko" in title -> 12
            "octaxe" in title -> 8
            "hex" in title || "haxe" in title -> 6
            "qaxe" in title || "qx" in title || "q137" in title -> 4
            "duo" in title || "gt" in title -> 2
            else -> 1
        }
    }
}

/** State the board's LED shows, the app's status block. */
data class DeviceLed(val state: ConnectionState, val isHighTemp: Boolean = false)

const val DEVICE_CARD_ASPECT = 1.586f

/**
 * A miner drawn as a card-sized board seen from above: dark solder mask, silver ASIC lids,
 * a small display and the model on the silkscreen. Like a bank card it is an object,
 * so it stays dark in light mode. Details drop away at small sizes.
 */
@Composable
fun DeviceCard(spec: DeviceCardSpec, width: Dp, modifier: Modifier = Modifier, led: DeviceLed? = null) {
    val height = width / DEVICE_CARD_ASPECT
    val detailed = width >= 80.dp
    val radius = max(3f, width.value * 0.07f).dp
    val accent = MG.accent()
    val ledColor = led?.let {
        when {
            it.state is ConnectionState.Online && it.isHighTemp -> MG.warning()
            it.state is ConnectionState.Online -> MG.good()
            it.state is ConnectionState.Offline -> MG.bad()
            else -> MG.idle()
        }
    }
    val measurer = rememberTextMeasurer()
    val description = led?.let { connectionDescription(it.state, it.isHighTemp) }
    Canvas(
        modifier
            .requiredSize(width, height)
            .shadow(if (detailed) width * 0.06f else 1.dp, androidx.compose.foundation.shape.RoundedCornerShape(radius), clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
    ) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(radius.toPx())
        // Solder mask: graphite with a soft light from the top left. NerdOS boards are a touch cooler.
        val mask = if (spec.tft) listOf(Color(0xFF2B2E36), Color(0xFF17191F), Color(0xFF0F0F14))
        else listOf(Color(0xFF363636), Color(0xFF1C1C1C), Color(0xFF121212))
        drawRoundRect(Brush.linearGradient(mask, Offset.Zero, Offset(w, h)), cornerRadius = r)

        val area = if (detailed) Rect(w * 0.36f, h * 0.2f, w * 0.92f, h * 0.78f) else Rect(w * 0.12f, h * 0.16f, w * 0.88f, h * 0.84f)
        if (detailed) {
            drawTraces(w, h, area)
            drawPort(w, h)
            drawDisplay(w, h, spec.tft, accent)
        }
        drawChips(spec, area, h, measurer)

        if (width >= 120.dp) {
            drawText(
                measurer, spec.family, Offset(w * 0.07f, h * 0.08f),
                TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = (w * 0.062f).toSp(), fontWeight = FontWeight.Medium)
            )
            drawText(
                measurer, spec.modelLine, Offset(w * 0.07f, h * 0.83f),
                TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = (w * 0.046f).toSp(), fontFamily = FontFamily.Monospace),
                maxLines = 1, size = Size(w * 0.86f, h * 0.15f)
            )
        }

        if (led != null && ledColor != null) {
            val s = if (detailed) max(6.dp.toPx(), w * 0.04f) else 6.dp.toPx()
            val x = w - s - if (detailed) w * 0.07f else 3.dp.toPx()
            val y = if (detailed) h * 0.1f else 3.dp.toPx()
            translate(x, y) { drawStatusBlock(led.state, led.isHighTemp, ledColor, s) }
        }

        // Edge catches the light at the top
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))),
            topLeft = Offset(0.5f, 0.5f), size = Size(w - 1f, h - 1f), cornerRadius = r,
            style = Stroke(if (width >= 60.dp) 1.dp.toPx() else 0.5.dp.toPx())
        )
    }
}

private fun DrawScope.drawChips(spec: DeviceCardSpec, area: Rect, h: Float, measurer: TextMeasurer) {
    val (columns, rows) = spec.chipGrid
    val cell = min(area.width / columns, area.height / rows)
    val chip = min(cell * 0.74f, h * 0.38f)
    val originX = area.center.x - cell * columns / 2
    val originY = area.center.y - cell * rows / 2
    repeat(max(1, spec.chipCount)) { index ->
        val x = originX + (index % columns) * cell + (cell - chip) / 2
        val y = originY + (index / columns) * cell + (cell - chip) / 2
        // Shadow, nickel-plated lid, inner edge, laser-etched model
        drawRoundRect(Color.Black.copy(alpha = 0.4f), Offset(x, y + chip * 0.04f), Size(chip, chip), CornerRadius(max(1f, chip * 0.1f)))
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFFDBDBDB), Color(0xFF999999), Color(0xFFC2C2C2)), Offset(x, y), Offset(x + chip, y + chip)),
            Offset(x, y), Size(chip, chip), CornerRadius(max(1f, chip * 0.1f))
        )
        val inset = chip * 0.12f
        drawRoundRect(
            Color.Black.copy(alpha = 0.18f), Offset(x + inset, y + inset), Size(chip - 2 * inset, chip - 2 * inset),
            CornerRadius(max(1f, chip * 0.06f)), style = Stroke(max(0.5f, chip * 0.025f))
        )
        val label = spec.chipModel
        if (label != null && chip >= 30.dp.toPx()) {
            val style = TextStyle(color = Color.Black.copy(alpha = 0.38f), fontSize = (chip * 0.15f).toSp(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            val layout = measurer.measure(label, style, maxLines = 1)
            drawText(layout, topLeft = Offset(x + (chip - layout.size.width) / 2, y + (chip - layout.size.height) / 2))
        }
    }
}

private fun DrawScope.drawTraces(w: Float, h: Float, area: Rect) {
    val path = Path().apply {
        repeat(4) { step ->
            val y = area.top + area.height * (0.2f + 0.2f * step)
            moveTo(w * 0.04f, y); lineTo(w * 0.3f, y); lineTo(area.left - w * 0.02f, y + h * 0.03f)
        }
        moveTo(area.center.x, area.bottom + h * 0.02f); lineTo(area.center.x + w * 0.06f, h * 0.96f)
        moveTo(area.right - w * 0.04f, area.top - h * 0.02f); lineTo(area.right, h * 0.04f)
    }
    drawPath(path, Color(0xFFD99966).copy(alpha = 0.09f), style = Stroke(max(0.5f, w * 0.005f)))
}

/** USB-C socket at the left edge. */
private fun DrawScope.drawPort(w: Float, h: Float) {
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFB3B3B3), Color(0xFF737373)), h * 0.43f, h * 0.57f),
        Offset(0f, h * 0.43f), Size(w * 0.035f, h * 0.14f), CornerRadius(w * 0.012f)
    )
}

/** Bitaxe boards carry a small OLED, NerdOS boards a colour TFT that shows the hashrate. */
private fun DrawScope.drawDisplay(w: Float, h: Float, tft: Boolean, accent: Color) {
    if (!tft) {
        val x = w * 0.09f
        val y = h * 0.42f
        val size = Size(w * 0.19f, h * 0.13f)
        drawRoundRect(Color.Black, Offset(x, y), size, CornerRadius(w * 0.008f))
        drawRoundRect(Color.White.copy(alpha = 0.1f), Offset(x, y), size, CornerRadius(w * 0.008f), style = Stroke(0.5f))
        val bar = max(0.5f, h * 0.018f)
        val cy = y + size.height / 2
        drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(x + w * 0.018f, cy - bar - h * 0.0125f), Size(w * 0.09f, bar), CornerRadius(bar / 2))
        drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(x + w * 0.018f, cy + h * 0.0125f), Size(w * 0.13f, bar), CornerRadius(bar / 2))
    } else {
        val x = w * 0.08f
        val y = h * 0.32f
        val size = Size(w * 0.21f, h * 0.36f)
        drawRoundRect(Color(0xFF080A0F), Offset(x, y), size, CornerRadius(w * 0.012f))
        drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(x, y), size, CornerRadius(w * 0.012f), style = Stroke(0.5f))
        val lw = w * 0.17f
        val lh = h * 0.26f
        val ox = x + (size.width - lw) / 2
        val oy = y + (size.height - lh) / 2
        val points = listOf(0f to 0.7f, 0.2f to 0.55f, 0.38f to 0.62f, 0.55f to 0.38f, 0.74f to 0.45f, 1f to 0.28f)
        val path = Path().apply {
            points.forEachIndexed { i, (px, py) -> if (i == 0) moveTo(ox + px * lw, oy + py * lh) else lineTo(ox + px * lw, oy + py * lh) }
        }
        drawPath(path, accent.copy(alpha = 0.9f), style = Stroke(max(0.75f, w * 0.007f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Up to three device cards fanned out like cards in a hand, the strongest miner in front. */
@Composable
fun DeviceCardStack(cards: List<Pair<DeviceCardSpec, DeviceLed?>>, width: Dp, modifier: Modifier = Modifier) {
    val shown = cards.take(3)
    val height = width / DEVICE_CARD_ASPECT
    // Back to front: rotation and offset of each card
    val poses = when (shown.size) {
        1 -> listOf(Triple(-8f, 0f, 0f))
        2 -> listOf(Triple(-2f, 0.16f, -0.12f), Triple(-11f, -0.04f, 0.04f))
        else -> listOf(Triple(4f, 0.26f, -0.16f), Triple(-3f, 0.1f, -0.06f), Triple(-12f, -0.06f, 0.06f))
    }
    Box(modifier.width(width * 1.4f).height(height * 1.5f), contentAlignment = Alignment.Center) {
        shown.indices.reversed().forEach { index ->
            val (angle, x, y) = poses[shown.size - 1 - index]
            val (spec, led) = shown[index]
            DeviceCard(spec, width, Modifier.offset(width * x, width * y).rotate(angle), led)
        }
    }
}

// endregion
