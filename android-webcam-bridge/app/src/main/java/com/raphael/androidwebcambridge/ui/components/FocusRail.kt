package com.raphael.androidwebcambridge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consumeAllChanges
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
    onActiveChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC1A1A2E))
            .border(
                1.dp,
                if (isActive) Color(0xFF4ADE80) else Color(0x33FFFFFF),
                RoundedCornerShape(16.dp)
            )
            .clickable { onActiveChange(true) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x66000000))
                    .clickable { onIncrease() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            VerticalSlider(
                value = valueDiopters,
                valueRange = 0f..10f,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                thumbColor = if (isActive) Color(0xFF4ADE80) else Color.White,
                activeTrackColor = Color(0xFF4ADE80),
                trackColor = Color(0x55FFFFFF)
            )

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x66000000))
                    .clickable { onDecrease() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "-",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = if (isAuto) "AUTO" else focusDistanceLabel(valueDiopters),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun VerticalSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color,
    activeTrackColor: Color,
    thumbColor: Color
) {
    Box(
        modifier = modifier
            .width(40.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->

                    val thumbRadius = 14f
                    val top = thumbRadius
                    val bottom = size.height - thumbRadius
                    val usableHeight = bottom - top

                    val y = change.position.y
                        .coerceIn(top, bottom)

                    val fraction = 1f - ((y - top) / usableHeight)

                    val newValue =
                        valueRange.start +
                                fraction * (valueRange.endInclusive - valueRange.start)

                    onValueChange(newValue)

                    change.consumeAllChanges()
                }
            }
    ) {

        Canvas(Modifier.fillMaxSize()) {

            val thumbRadius = 14f
            val trackWidth = 8f

            val centerX = size.width / 2f

            val top = thumbRadius
            val bottom = size.height - thumbRadius
            val usableHeight = bottom - top

            val fraction =
                ((value - valueRange.start) /
                        (valueRange.endInclusive - valueRange.start))
                    .coerceIn(0f, 1f)

            val thumbY = bottom - usableHeight * fraction

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(centerX - trackWidth / 2f, top),
                size = Size(trackWidth, usableHeight),
                cornerRadius = CornerRadius(trackWidth)
            )

            drawRoundRect(
                color = activeTrackColor,
                topLeft = Offset(centerX - trackWidth / 2f, thumbY),
                size = Size(trackWidth, bottom - thumbY),
                cornerRadius = CornerRadius(trackWidth)
            )

            drawCircle(
                color = thumbColor,
                radius = thumbRadius,
                center = Offset(centerX, thumbY)
            )
        }
    }
}

private fun focusDistanceLabel(diopters: Float): String {
    if (diopters <= 0.01f) return "Infinity"
    val meters = 1f / diopters
    return if (meters >= 10f) "Infinity"
    else String.format(Locale.US, "%.2fm", meters)
}