package com.pocketforge.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ForgeDark = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF86EFAC),
    onSecondary = Color(0xFF052E16),
    background = Color(0xFF0A1014),
    onBackground = Color(0xFFE7EEF3),
    surface = Color(0xFF111A21),
    onSurface = Color(0xFFE7EEF3),
    surfaceVariant = Color(0xFF19242D),
    onSurfaceVariant = Color(0xFFA9B8C4),
    error = Color(0xFFFF9A9A),
    errorContainer = Color(0xFF3B1518),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = Color(0xFF27343E),
    outlineVariant = Color(0xFF1C272F),
)

private val ForgeLight = lightColorScheme(
    primary = Color(0xFF0E7C6B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6F5EE),
    onPrimaryContainer = Color(0xFF04322C),
    secondary = Color(0xFF15803D),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF3F6F8),
    onBackground = Color(0xFF101820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101820),
    surfaceVariant = Color(0xFFF0F4F7),
    onSurfaceVariant = Color(0xFF5A6B78),
    error = Color(0xFFC02626),
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF6B1111),
    outline = Color(0xFFDDE5EB),
    outlineVariant = Color(0xFFEDF2F6),
)

private val PfShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PocketForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ForgeDark else ForgeLight,
        typography = PfTypography,
        shapes = PfShapes,
        content = content,
    )
}
