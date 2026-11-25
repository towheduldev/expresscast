package dev.towhedul.expresscast.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val TvEmerald = Color(0xFF12B76A)
val TvEmeraldDark = Color(0xFF0E9F5B)
val TvFocusDeep = Color(0xFF065F46)
val TvMintTint = Color(0xFFE6F9EF)
val TvMintBorder = Color(0xFF16C172)
val TvMintWaveSoft = Color(0xFFD6F6E6)
val TvMintWaveLight = Color(0xFFEAFBF2)

val TvCanvasBg = Color(0xFFF5F8FA)
val TvCardWhite = Color(0xFFFFFFFF)
val TvCardBorder = Color(0xFFE5ECF2)
val TvPillNeutral = Color(0xFFF1F4F8)
val TvDividerSoft = Color(0xFFE8EEF3)

val TvInkPrimary = Color(0xFF0F172A)
val TvInkSecondary = Color(0xFF64748B)

val TvStatusGreen = TvEmerald
val TvStatusAmber = Color(0xFFF59E0B)
val TvStatusRed = Color(0xFFEF4444)

private val TvLightColorScheme = lightColorScheme(
    primary = TvEmerald,
    onPrimary = TvCardWhite,
    primaryContainer = TvMintTint,
    onPrimaryContainer = TvEmeraldDark,
    secondary = TvEmeraldDark,
    onSecondary = TvCardWhite,
    background = TvCanvasBg,
    onBackground = TvInkPrimary,
    surface = TvCardWhite,
    onSurface = TvInkPrimary,
    surfaceVariant = TvPillNeutral,
    onSurfaceVariant = TvInkSecondary,
    outline = TvCardBorder
)

private val TvTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    )
)

@Composable
fun TvTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TvLightColorScheme,
        typography = TvTypography,
        content = content
    )
}
