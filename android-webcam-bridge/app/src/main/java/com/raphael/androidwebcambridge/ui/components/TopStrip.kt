package com.raphael.androidwebcambridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import com.raphael.androidwebcambridge.bridge.TallyState

@Composable
fun TopStrip(
    state: BridgeState,
    onSettingsClick: () -> Unit,
    onLensClick: () -> Unit,
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
            CircleButton(
                icon = Icons.Filled.Cameraswitch,
                onClick = onLensClick,
                contentDescription = "Switch Camera"
            )
            CircleButton(
                icon = Icons.Filled.Settings,
                onClick = onSettingsClick,
                contentDescription = "Settings"
            )
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
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.connectedClients > 0 || state.tallyState != TallyState.IDLE) {
                    val statusColor = when (state.tallyState) {
                        TallyState.PROGRAM -> Color(0xFFEF4444)
                        TallyState.PREVIEW -> Color(0xFFF59E0B)
                        else -> Color(0xFF10B981)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = when(state.tallyState) {
                                TallyState.PROGRAM -> "LIVE"
                                TallyState.PREVIEW -> "PREVIEW"
                                else -> "ACTIVE"
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
                
                InfoBadge(text = state.settings.resolutionPreset.label)
                InfoBadge(text = "ISO ${state.settings.iso}")
                InfoBadge(text = "${state.settings.frameRate} FPS")
            }
            
            if (state.statusMessage.isNotBlank()) {
                Text(
                    text = state.statusMessage.uppercase(),
                    color = Color(0xFF4ADE80),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                    letterSpacing = 1.sp
                )
            }
        }
    }
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
