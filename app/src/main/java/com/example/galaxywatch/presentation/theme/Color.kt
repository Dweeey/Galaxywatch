package com.example.galaxywatch.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors

val LightBlue = Color(0xFF5DA9FA)
val White = Color.White
val Black = Color.Black
val Grey = Color(0xFFE0E0E0)
val DarkGrey = Color(0xFF757575)

internal val wearColorPalette: Colors = Colors(
    primary = LightBlue,
    onPrimary = White,
    background = White,
    onBackground = Black,
    surface = White, // Cards will be white
    onSurface = Black,
    onSurfaceVariant = DarkGrey,
    secondary = Grey
)
