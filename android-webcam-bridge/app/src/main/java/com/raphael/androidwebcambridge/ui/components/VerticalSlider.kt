package com.raphael.androidwebcambridge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun VerticalSlider(
    modifier: Modifier = Modifier,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    trackColor: Color = Color(0x55FFFFFF),
    activeTrackColor: Color = Color(0xFF4ADE80),
    thumbColor: Color = Color.White,
    trackWidth: Float = 8f,
    thumbRadius: Float = 16f,
) {

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .width(40.dp)
            .pointerInput(Unit) {

                detectDragGestures { change, _ ->

                    val y = change.position.y.coerceIn(0f, size.height.toFloat())

                    val fraction = 1f - (y / size.height.toFloat())

                    val newValue =
                        valueRange.start +
                                fraction * (valueRange.endInclusive - valueRange.start)

                    onValueChange(newValue)

                    change.consume()
                }

            },
        contentAlignment = Alignment.Center
    ) {

        Canvas(modifier = Modifier.fillMaxSize()) {

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
                topLeft = Offset(
                    centerX - trackWidth / 2,
                    top
                ),
                size = Size(
                    trackWidth,
                    usableHeight
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackWidth)
            )

            drawRoundRect(
                color = activeTrackColor,
                topLeft = Offset(
                    centerX - trackWidth / 2,
                    thumbY
                ),
                size = Size(
                    trackWidth,
                    bottom - thumbY
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackWidth)
            )

            drawCircle(
                color = thumbColor,
                radius = thumbRadius,
                center = Offset(centerX, thumbY)
            )
        }
    }
}