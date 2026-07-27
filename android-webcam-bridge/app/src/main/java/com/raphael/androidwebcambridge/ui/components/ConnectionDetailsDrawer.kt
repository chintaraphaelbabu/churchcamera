package com.raphael.androidwebcambridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raphael.androidwebcambridge.bridge.BridgeState
import com.raphael.androidwebcambridge.bridge.LensInfo

@Composable
fun ConnectionDetailsDrawer(
    state: BridgeState,
    onRelayHostChange: (String) -> Unit,
    onFrameRateChange: (Int) -> Unit,
    onLensChange: (String) -> Unit,
    onScreenDimToggle: (Boolean) -> Unit = {},
) {
    var relayHostDraft by remember(state.relayHost) { mutableStateOf(state.relayHost) }

    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(420.dp)
                .background(Color(0xFFF5F5F5))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "CONNECTION DETAILS",
            color = Color.Black,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        ConnectionField(label = "Local IP Address", value = state.localIpAddress.ifBlank { "Unknown" })

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(text = "Tally IP Address", color = Color(0xFF666666), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = relayHostDraft,
                onValueChange = { relayHostDraft = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedBorderColor = Color(0xFF4ADE80),
                        unfocusedBorderColor = Color(0xFF666666),
                        focusedContainerColor = Color(0xFF1E1E1E),
                        unfocusedContainerColor = Color(0xFF1E1E1E),
                    ),
            )
            if (state.relayDiscoveryStatus.isNotBlank()) {
                Text(
                    text = state.relayDiscoveryStatus,
                    color = Color(0xFF4ADE80),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        FrameRateSelector(
            current = state.settings.frameRate,
            onSelect = onFrameRateChange,
        )

        val currentLens = state.availableLenses.find { it.cameraId == state.settings.selectedCameraId }
        LensSelector(
            current = currentLens?.shortDisplayName() ?: state.lensDisplayName.ifBlank { "Select lens" },
            lenses = state.availableLenses,
            onSelect = onLensChange,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(
                "Dim on inactivity",
                color = Color(0xFF333333),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.settings.screenDimEnabled,
                onCheckedChange = onScreenDimToggle,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF4ADE80),
                        checkedTrackColor = Color(0xFF4ADE80).copy(alpha = 0.4f),
                    ),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { onRelayHostChange(relayHostDraft) }
    }
}

@Composable
private fun FrameRateSelector(
    current: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(24, 30, 60)

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = "Frame Rate", color = Color(0xFF666666), style = MaterialTheme.typography.bodySmall)
        Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(text = "$current FPS", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { fps ->
                    DropdownMenuItem(
                        text = { Text("$fps FPS") },
                        onClick = {
                            onSelect(fps)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LensSelector(
    current: String,
    lenses: List<LensInfo>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = "Lens", color = Color(0xFF666666), style = MaterialTheme.typography.bodySmall)
        Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(text = current, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                lenses.forEach { lens ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(lens.shortDisplayName(), color = Color.White)
                                Text(
                                    "${lens.focalLengthMm}mm${lens.aperture?.let { "  f/$it" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                )
                            }
                        },
                        onClick = {
                            onSelect(lens.cameraId)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionField(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = label, color = Color(0xFF666666), style = MaterialTheme.typography.bodySmall)
        Row(
            modifier =
                Modifier.fillMaxWidth().padding(top = 4.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(text = value, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
