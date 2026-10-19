package dev.pocketpaas.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB3EEE4),
    onPrimaryContainer = Color(0xFF003A34),
    secondary = Color(0xFF3E5E79),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4E7FA),
    onSecondaryContainer = Color(0xFF17334B),
    tertiary = Color(0xFF66587C),
    tertiaryContainer = Color(0xFFECDDFF),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF142538),
    surface = Color(0xFFFCFDFE),
    onSurface = Color(0xFF142538),
    surfaceVariant = Color(0xFFE3EBEE),
    onSurfaceVariant = Color(0xFF465962),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F4F7),
    surfaceContainer = Color(0xFFECF1F5),
    surfaceContainerHigh = Color(0xFFE7EDF2),
    surfaceContainerHighest = Color(0xFFE1E9EF),
    outline = Color(0xFF71828B),
    outlineVariant = Color(0xFFCDD8DF),
    error = Color(0xFFAD3034),
    errorContainer = Color(0xFFFFDAD8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF72D8C8),
    onPrimary = Color(0xFF003831),
    primaryContainer = Color(0xFF075048),
    onPrimaryContainer = Color(0xFFB3EEE4),
    secondary = Color(0xFFA5CBE9),
    onSecondary = Color(0xFF0A344F),
    secondaryContainer = Color(0xFF294960),
    onSecondaryContainer = Color(0xFFD4E7FA),
    tertiary = Color(0xFFD1BFE9),
    tertiaryContainer = Color(0xFF4A3C5D),
    background = Color(0xFF0B1421),
    onBackground = Color(0xFFE1EAF3),
    surface = Color(0xFF111E2C),
    onSurface = Color(0xFFE1EAF3),
    surfaceVariant = Color(0xFF263541),
    onSurfaceVariant = Color(0xFFBCCBD4),
    surfaceContainerLowest = Color(0xFF08111C),
    surfaceContainerLow = Color(0xFF111E2C),
    surfaceContainer = Color(0xFF172433),
    surfaceContainerHigh = Color(0xFF1E2C3C),
    surfaceContainerHighest = Color(0xFF273645),
    outline = Color(0xFF8799A5),
    outlineVariant = Color(0xFF394C5B),
    error = Color(0xFFFFB4AF),
    errorContainer = Color(0xFF73272B),
)

private val AppTypography = Typography().let { defaults ->
    Typography(
        headlineLarge = defaults.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
        titleLarge = defaults.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = defaults.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun PocketPaaSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
