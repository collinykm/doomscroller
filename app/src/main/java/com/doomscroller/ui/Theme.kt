package com.doomscroller.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0E0B12)
val Card = Color(0xFF1A1520)
val CardHi = Color(0xFF241D2C)
val Pink = Color(0xFFFF4D7A)
val Amber = Color(0xFFFFB020)
val Teal = Color(0xFF2EC4B6)
val Violet = Color(0xFF9B6BFF)
val TextHi = Color(0xFFF3EEF7)
val Muted = Color(0xFF9B92A6)

@Composable
fun DoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pink,
            secondary = Amber,
            tertiary = Teal,
            background = Bg,
            surface = Card,
            surfaceVariant = CardHi,
            onBackground = TextHi,
            onSurface = TextHi,
            onSurfaceVariant = Muted,
            onPrimary = Color.White,
        ),
        content = content,
    )
}
