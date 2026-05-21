package com.raphael.androidwebcambridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme()

@Composable
fun AndroidWebcamBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}