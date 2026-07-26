package com.alastor.orbitlauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OrbitColors = darkColorScheme(
    primary = OrbitPurple,
    secondary = OrbitRed,
    background = Void,
    surface = VoidRaised,
    onPrimary = Void,
    onSecondary = Void,
    onBackground = SoftWhite,
    onSurface = SoftWhite,
)

@Composable
fun OrbitLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OrbitColors,
        content = content,
    )
}
