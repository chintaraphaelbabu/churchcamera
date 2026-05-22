package com.raphael.androidwebcambridge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun ZoomRail(
    value: Float,
    isActive: Boolean,
    onValueChange: (Float) -> Unit,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onActiveChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(132.dp)
            .fillMaxHeight()
            .clickable { onActiveChange(true) },
        color = if (isActive) Color(0x990F172A) else Color(0x66000000),
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("ZOOM", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Button(onClick = onIncrease) { Text("+") }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .width(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(color = Color(0x22000000), shape = RectangleShape) {
                    Slider(
                        value = value,
                        onValueChange = onValueChange,
                        valueRange = 1f..5f,
                        modifier = Modifier
                            .height(340.dp)
                            .width(260.dp)
                            .graphicsLayer { rotationZ = 270f },
                    )
                }
            }
            Text(String.format(Locale.US, "%.1fx", value), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Button(onClick = onDecrease) { Text("-") }
        }
    }
}
