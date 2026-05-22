package com.raphael.androidwebcambridge.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raphael.androidwebcambridge.bridge.BridgeState

@Composable
fun BottomStrip(
    state: BridgeState,
    isControlActive: (OverlayControl) -> Boolean,
    onControlClick: (OverlayControl) -> Unit,
    activeRail: BridgeState.RailType?,
    onRailClick: (BridgeState.RailType) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        color = Color(0x990F172A),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ControlTile(
                label = "ISO",
                reading = if (state.settings.iso == 0) "AUTO" else state.settings.iso.toString(),
                active = isControlActive(OverlayControl.ISO),
                isRailActive = activeRail == BridgeState.RailType.ISO,
                onClick = { onControlClick(OverlayControl.ISO) },
                onLongClick = { onRailClick(BridgeState.RailType.ISO) },
                modifier = Modifier.weight(1f)
            )
            ControlTile(
                label = "SHUTTER",
                reading = if (state.settings.shutterSpeedMs == 0) "AUTO" else "${state.settings.shutterSpeedMs}ms",
                active = isControlActive(OverlayControl.SHUTTER),
                isRailActive = activeRail == BridgeState.RailType.SHUTTER,
                onClick = { onControlClick(OverlayControl.SHUTTER) },
                onLongClick = { onRailClick(BridgeState.RailType.SHUTTER) },
                modifier = Modifier.weight(1f)
            )
            ControlTile(
                label = "RES",
                reading = state.settings.resolutionPreset.label,
                active = isControlActive(OverlayControl.RESOLUTION),
                onClick = { onControlClick(OverlayControl.RESOLUTION) },
                modifier = Modifier.weight(1f)
            )
            ControlTile(
                label = "FPS",
                reading = "${state.settings.frameRate}",
                active = isControlActive(OverlayControl.FRAME_RATE),
                onClick = { onControlClick(OverlayControl.FRAME_RATE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlTile(
    label: String,
    reading: String,
    active: Boolean,
    isRailActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    isRailActive -> Color(0xFF0F172A) // Dark when active for volume
                    active -> Color(0xFF4ADE80)
                    else -> Color(0x33FFFFFF)
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = if (active) Color(0xFF0F172A) else Color(0xFF94A3B8),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = reading,
                color = if (active) Color(0xFF0F172A) else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
