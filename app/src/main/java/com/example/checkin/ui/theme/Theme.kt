package com.example.checkin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = Color.White,
    secondary = AmberAccent,
    error = RedError,
    surface = SurfaceLight,
    background = SurfaceLight
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF003300),
    secondary = AmberAccent,
    error = Color(0xFFEF9A9A)
)

@Composable
fun CheckInTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
