package com.bydmatecn.app.local

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dedicated car-tool palette, independent of the trip dashboard theme. */
@Composable
internal fun VehicleToolTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFF00D4D8), onPrimary = Color(0xFF092538),
        primaryContainer = Color(0xFF234C68), onPrimaryContainer = Color.White,
        secondary = Color(0xFF00BBFF), onSecondary = Color(0xFF092538),
        background = Color(0xFF101A2D), onBackground = Color.White,
        surface = Color(0xFF14243A), onSurface = Color.White,
        surfaceVariant = Color(0xFF203450), onSurfaceVariant = Color(0xFFB8C9E0),
        outline = Color(0xFF587BA7), error = Color(0xFFFF9090),
    )
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
}
