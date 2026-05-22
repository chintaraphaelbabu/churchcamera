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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun FocusRail(
    valueDiopters: Float,
    isAuto: Boolean,
    isActive: Boolean,
    onValueChange: (Float) -> Unit,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onFocusCommit: () -> Unit,
    onActiveChange: (Boolean) -> Unit,
) {
    var draftValue by remember(valueDiopters) { mutableStateOf(valueDiopters) }
    Surface(
        modifier = Modifier
            .width(132.dp)
            .fillMaxHeight()
            .clickable { onActiveChange(true) },
        color = if (isActive) Color(0x990F172A) else Color(0x66000000),
        shape = RectangleShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("FOCUS", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Button(onClick = onIncrease) { Text("+") }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .width(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(color = Color(0x22000000), shape = RectangleShape) {
                    Slider(
                        value = draftValue,
                        onValueChange = {
                            draftValue = it
                            onValueChange(it)
                        },
                        valueRange = 0f..10f,
                        onValueChangeFinished = {
                            onFocusCommit()
                        },
                        modifier = Modifier
                            .height(340.dp)
                            .width(260.dp)
                            .graphicsLayer { rotationZ = 270f },
                    )
                }
            }
            Text(if (isAuto) "AUTO" else focusDistanceLabel(draftValue), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Button(onClick = onDecrease) { Text("-") }
        }
    }
}

private fun focusDistanceLabel(diopters: Float): String {
    if (diopters <= 0.01f) return "Infinity"
    val meters = 1f / diopters
    return if (meters >= 10f) {
        "Infinity"
    } else {
        String.format(Locale.US, "%.2fm", meters)
    }
}
