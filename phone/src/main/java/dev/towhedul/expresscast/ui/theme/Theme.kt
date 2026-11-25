package dev.towhedul.expresscast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ExpressCastLightScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = CardWhite,
    primaryContainer = MintTint,
    onPrimaryContainer = EmeraldDark,
    secondary = EmeraldDark,
    onSecondary = CardWhite,
    background = CanvasBg,
    onBackground = InkPrimary,
    surface = CardWhite,
    onSurface = InkPrimary,
    surfaceVariant = PillNeutral,
    onSurfaceVariant = InkSecondary,
    outline = CardBorder
)

@Composable
fun ExpressCastTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ExpressCastLightScheme,
        typography = Typography,
        content = content
    )
}
