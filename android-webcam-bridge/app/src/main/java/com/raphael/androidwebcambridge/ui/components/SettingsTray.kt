package com.raphael.androidwebcambridge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun SettingsTray(
    focusVelocity: Float,
    zoomVelocity: Float,
    onFocusVelocityChange: (Float) -> Unit,
    onZoomVelocityChange: (Float) -> Unit,
) {
    Surface(color = Color(0xCCFFFFFF), shape = RectangleShape) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("CONTROL VELOCITY", color = Color.Black, style = MaterialTheme.typography.titleMedium)
            
            Column {
                Text(
                    text = String.format(Locale.US, "Focus Sensitivity: %.2f", focusVelocity),
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = focusVelocity,
                    onValueChange = onFocusVelocityChange,
                    valueRange = 0.01f..0.5f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column {
                Text(
                    text = String.format(Locale.US, "Zoom Sensitivity: %.2f", zoomVelocity),
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = zoomVelocity,
                    onValueChange = onZoomVelocityChange,
                    valueRange = 0.01f..0.5f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
