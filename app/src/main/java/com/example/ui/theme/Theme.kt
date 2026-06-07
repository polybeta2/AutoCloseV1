package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PremiumDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD4AF37), // Champagne Gold
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF2C2518),
    onPrimaryContainer = Color(0xFFFAE8B4),
    secondary = Color(0xFFB0BEC5), // Slate Silver
    onSecondary = Color(0xFF1C272B),
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color(0xFFCFD8DC),
    tertiary = Color(0xFFE0E0E0),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = Color(0xFFF5F5F5),
    background = Color(0xFF0D0D0D),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFEBEBEB),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFA1A1A1),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF222222)
)

private val PremiumLightColorScheme = lightColorScheme(
    primary = Color(0xFFB7860B), 
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF9E8AF),
    onPrimaryContainer = Color(0xFF2C2518),
    secondary = Color(0xFF607D8B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFD8DC),
    onSecondaryContainer = Color(0xFF263238),
    tertiary = Color(0xFF424242),
    tertiaryContainer = Color(0xFFEEEEEE),
    onTertiaryContainer = Color(0xFF1A1A1A),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF757575),
    outline = Color(0xFFE0E0E0),
    outlineVariant = Color(0xFFEEEEEE)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force premium dark theme
    dynamicColor: Boolean = false, // Disable dynamic colors (blue/purple)
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) PremiumDarkColorScheme else PremiumLightColorScheme

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
