package com.github.caiheyu.farview.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color

val Paper = Color(0xFFF8FAF4)
val Ink = Color(0xFF222F29)
val Sage = Color(0xFF77896C)
val Accent = Color(0xFFD5EFB8)
val Soft = Color(0xFFEAF0E2)
val Muted = Color(0xFF83907B)
val RecordingRed = Color(0xFFE47770)

@Composable
fun FarviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, onPrimary = Paper,
        secondary = Sage, secondaryContainer = Soft, tertiary = Accent,
        background = Paper, surface = Paper, onBackground = Ink, onSurface = Ink,
        surfaceVariant = Soft, outline = Color(0xFFDCE3D4)), typography = Typography) {
        CompositionLocalProvider(LocalContentColor provides Ink, content = content)
    }
}
