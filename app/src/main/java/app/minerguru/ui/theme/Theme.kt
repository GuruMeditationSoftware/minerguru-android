package app.minerguru.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.minerguru.model.LatencyQuality
import app.minerguru.model.MinedCoin

/**
 * Graphite palette of the macOS app: neutral surfaces, one amber accent, four signal colors.
 * Color is carried by content (figures, lines, status blocks), never by surfaces.
 */
object MG {
    @Composable private fun pick(light: Long, dark: Long) = Color(if (isSystemInDarkTheme()) dark else light)

    // Signal colors
    @Composable fun good() = pick(0xFF14A06C, 0xFF3ECF8E)
    @Composable fun warning() = pick(0xFFC77A00, 0xFFF5B041)
    @Composable fun bad() = pick(0xFFC23B3B, 0xFFE66767)
    @Composable fun idle() = pick(0xFF8A8A8E, 0xFF6E6E73)
    @Composable fun accent() = pick(0xFFD9780A, 0xFFF0A03C)

    /** Hairline around cards and between rows. */
    @Composable fun hairline() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    // Older names, mapped onto the graphite palette.
    @Composable fun orange() = accent()
    @Composable fun green() = good()
    @Composable fun red() = bad()
    @Composable fun yellow() = warning()
    @Composable fun blue() = pick(0xFF2A78D6, 0xFF5AA2F5)
    @Composable fun teal() = pick(0xFF0E9F9F, 0xFF3CC9C9)
    @Composable fun purple() = pick(0xFF7C5CE0, 0xFFA48AF5)
    @Composable fun indigo() = pick(0xFF5B6AD0, 0xFF8D98F0)
    @Composable fun mint() = pick(0xFF4F9A2A, 0xFF7CCF5A)

    /**
     * The color for small text and icons on a light tint of itself: darker in light mode so it reaches
     * WCAG AA contrast (4.5:1); dark mode already uses light tones.
     */
    @Composable
    fun text(color: Color): Color = if (isSystemInDarkTheme()) color else lerp(color, Color.Black, 0.45f)

    @Composable
    fun latency(q: LatencyQuality): Color = when (q) {
        LatencyQuality.EXCELLENT -> green()
        LatencyQuality.GOOD -> mint()
        LatencyQuality.FAIR -> orange()
        LatencyQuality.POOR -> red()
        LatencyQuality.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    /** Each coin keeps its own color, shown only as a small dot next to its symbol. */
    @Composable
    fun coin(coin: MinedCoin): Color = when (coin) {
        MinedCoin.BTC -> accent()
        MinedCoin.BCH -> good()
        MinedCoin.DGB -> blue()
        MinedCoin.XEC -> teal()
        MinedCoin.BSV -> pick(0xFFB58A06, 0xFFF5C04A)
        MinedCoin.PPC -> mint()
        MinedCoin.NMC -> indigo()
        MinedCoin.FB -> purple()
        MinedCoin.UNKNOWN -> idle()
    }

    /** Hardware is named in quiet text; the board itself is shown by the device card. */
    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun hardware(title: String): Color = MaterialTheme.colorScheme.onSurfaceVariant
}

/** Monospaced type for figures in meta lines, axes, counts and addresses. */
object MGType {
    fun mono(size: TextUnit = 11.sp, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = size, fontWeight = weight)

    /** Large key figure, e.g. the fleet hashrate. */
    fun figure(size: TextUnit) = TextStyle(
        fontSize = size, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp
    )
}

// Surfaces as on the Mac: page, card, raised; the hairline is the only outline.
private val GraphiteLight = lightColorScheme(
    primary = Color(0xFFD9780A), onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E7D3), onPrimaryContainer = Color(0xFF4A2800),
    secondary = Color(0xFF5C5C61), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4E6), onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF14A06C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9F2E7), onTertiaryContainer = Color(0xFF00391F),
    error = Color(0xFFC23B3B), onError = Color.White,
    errorContainer = Color(0xFFF8DEDE), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFEDEDEE), onSurfaceVariant = Color(0xFF606065),
    surfaceDim = Color(0xFFE6E6E8), surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F2F3),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEDEDEE),
    surfaceContainerHighest = Color(0xFFE6E6E8),
    outline = Color(0xFF8A8A8E), outlineVariant = Color(0xFFE6E6E8),
    inverseSurface = Color(0xFF2C2C2E), inverseOnSurface = Color(0xFFF2F2F3), inversePrimary = Color(0xFFF0A03C),
    scrim = Color.Black
)

private val GraphiteDark = darkColorScheme(
    primary = Color(0xFFF0A03C), onPrimary = Color(0xFF2B1700),
    primaryContainer = Color(0xFF3A2A14), onPrimaryContainer = Color(0xFFF6D3A6),
    secondary = Color(0xFFA1A1A6), onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF2C2C2F), onSecondaryContainer = Color(0xFFF2F2F3),
    tertiary = Color(0xFF3ECF8E), onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF143326), onTertiaryContainer = Color(0xFFB8EED3),
    error = Color(0xFFE66767), onError = Color(0xFF410002),
    errorContainer = Color(0xFF3D1D1D), onErrorContainer = Color(0xFFF8DEDE),
    background = Color(0xFF141415), onBackground = Color(0xFFF2F2F3),
    surface = Color(0xFF141415), onSurface = Color(0xFFF2F2F3),
    surfaceVariant = Color(0xFF232325), onSurfaceVariant = Color(0xFFA1A1A6),
    surfaceDim = Color(0xFF0F0F10), surfaceBright = Color(0xFF2C2C2F),
    surfaceContainerLowest = Color(0xFF0F0F10), surfaceContainerLow = Color(0xFF0F0F10),
    surfaceContainer = Color(0xFF19191B), surfaceContainerHigh = Color(0xFF232325),
    surfaceContainerHighest = Color(0xFF2C2C2F),
    outline = Color(0xFF6E6E73), outlineVariant = Color(0xFF2A2A2D),
    inverseSurface = Color(0xFFF2F2F3), inverseOnSurface = Color(0xFF1C1C1E), inversePrimary = Color(0xFFD9780A),
    scrim = Color.Black
)

// Calmer corners than Material 3 Expressive, close to the 14 pt panels on the Mac.
private val MinerGuruShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MinerGuruTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) GraphiteDark else GraphiteLight
    // Two weights as on the Mac: regular and medium.
    val base = Typography()
    val typography = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Medium),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium)
    )
    MaterialTheme(colorScheme = colorScheme, shapes = MinerGuruShapes, typography = typography, content = content)
}
