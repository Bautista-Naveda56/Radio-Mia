package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RadioMiaColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = OnAmberPrimary,
    primaryContainer = AmberPrimaryContainer,
    onPrimaryContainer = AmberPrimary,
    secondary = OnAirCoral,
    onSecondary = TextPrimary,
    secondaryContainer = OnAirCoralContainer,
    onSecondaryContainer = OnAirCoralLight,
    tertiary = TelemetryCyan,
    onTertiary = StudioBackground,
    tertiaryContainer = TelemetryCyanContainer,
    onTertiaryContainer = TelemetryCyan,
    background = StudioBackground,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DividerSubtle
)

@Composable
fun RadioMiaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RadioMiaColorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    RadioMiaTheme(content = content)
}
