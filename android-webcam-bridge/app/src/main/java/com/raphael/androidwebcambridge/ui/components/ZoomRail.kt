package com.raphael.androidwebcambridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                value = value,
                valueRange = 1f..5f,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                thumbColor =
                    if (isActive) Color(0xFF4ADE80)
                    else Color.White,
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
                text = String.format(Locale.US, "%.1fx", value),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}