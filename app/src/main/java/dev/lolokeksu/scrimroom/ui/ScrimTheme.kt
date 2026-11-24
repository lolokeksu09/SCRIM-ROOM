package dev.lolokeksu.scrimroom.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFCCE879)
val Muted = Color(0xFF98A0A2)
val Panel = Color(0xFF131719)
val Outline = Color(0xFF2B3235)

private val palette = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0C0E09),
    background = Color(0xFF080A0B),
    onBackground = Color(0xFFF3F5EE),
    surface = Color(0xFF111516),
    onSurface = Color(0xFFF3F5EE),
    surfaceVariant = Color(0xFF1C2224),
    onSurfaceVariant = Color(0xFFABB4B4),
    outline = Outline,
    error = Color(0xFFFF8A7D)
)

@Composable
fun ScrimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = palette,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
        ),
        content = content
    )
}
