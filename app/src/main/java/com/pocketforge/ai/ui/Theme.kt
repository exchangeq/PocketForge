package com.pocketforge.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ForgeDark = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF11403C),
    onPrimaryContainer = Color(0xFFB5F2EA),
    secondary = Color(0xFF8BE9A8),
    onSecondary = Color(0xFF00210F),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF121821),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B2430),
    onSurfaceVariant = Color(0xFFB9C4D0),
    error = Color(0xFFFF7A7A),
    outline = Color(0xFF2C3846),
)

private val ForgeLight = lightColorScheme(
    primary = Color(0xFF12897F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFF5EF),
    onPrimaryContainer = Color(0xFF00332E),
    secondary = Color(0xFF116B33),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF11161C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11161C),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF44505C),
    error = Color(0xFFB3261E),
    outline = Color(0xFFC9D2DB),
)

@Composable
fun PocketForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ForgeDark else ForgeLight,
        content = content,
    )
}
