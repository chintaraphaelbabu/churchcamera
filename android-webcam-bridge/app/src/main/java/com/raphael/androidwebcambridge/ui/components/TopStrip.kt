package com.raphael.androidwebcambridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raphael.androidwebcambridge.bridge.BridgeState

@Composable
fun TopStrip(
    state: BridgeState,
    onLensClick: () -> Unit,
    onPttPress: () -> Unit = {},
    onPttRelease: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        TopInfoBar(state = state)

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PttButton(speaking = state.operatorSpeaking, onPress = onPttPress, onRelease = onPttRelease)
            CircleButton(
                icon = Icons.Filled.Cameraswitch,
                onClick = onLensClick,
                contentDescription = "Switch Camera"
            )
        }
    }
}

@Composable
fun PttButton(speaking: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    Surface(
        color = if (speaking) Color(0xFFEF4444) else Color(0x990F172A),
        shape = CircleShape,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        try { awaitRelease() } finally { onRelease() }
                    }
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Mic, contentDescription = "Push to Talk", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    contentDescription: String
) {
    Surface(
        color = Color(0x990F172A),
        shape = CircleShape,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TopInfoBar(state: BridgeState) {
    Surface(
        color = Color(0x990F172A),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoBadge(text = state.settings.resolutionPreset.label)
                InfoBadge(text = "ISO ${state.settings.iso}")
                InfoBadge(text = "${state.settings.frameRate} FPS")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                if (state.serverRunning) {
                    StatusBadge(text = "SERVER STARTED", color = Color(0xFF00FF37))
                }
                if (state.tallyState != com.raphael.androidwebcambridge.bridge.TallyState.IDLE || state.connectedClients > 0) {
                    StatusBadge(text = "TALLY RUNNING", color = Color(0xFF00FF37))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

@Composable
private fun InfoBadge(text: String) {
    Text(
        text = text,
        color = Color(0xFFCBD5E1),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium
    )
}
