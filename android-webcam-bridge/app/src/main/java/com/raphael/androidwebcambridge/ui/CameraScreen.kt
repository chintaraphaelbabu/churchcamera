package com.raphael.androidwebcambridge.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Surface
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import com.raphael.androidwebcambridge.bridge.BridgeState
import com.raphael.androidwebcambridge.bridge.BridgeViewModel
import com.raphael.androidwebcambridge.bridge.TallyState
import com.raphael.androidwebcambridge.bridge.CameraSessionController
import com.raphael.androidwebcambridge.bridge.LensFacingOption
import com.raphael.androidwebcambridge.bridge.ResolutionPreset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current as? Activity
    LaunchedEffect(Unit) {
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    val viewModel: BridgeViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val cameraController = remember { CameraSessionController(context) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var activeControl by remember { mutableStateOf<OverlayControl?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.refreshRelayRegistration()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.pingRelayNow()
            } else if (event == Lifecycle.Event.ON_STOP) {
                viewModel.pauseRelayHeartbeat()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!hasCameraPermission) {
        PermissionGate(onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        return
    }

    LaunchedEffect(previewView, state.cameraRebindToken) {
        runCatching {
            cameraController.bind(
                previewView = previewView,
                lifecycleOwner = lifecycleOwner,
                settings = state.settings,
                onFrame = viewModel::onFrame,
                onFacesDetected = viewModel::onFacesDetected,
                onStatus = { viewModel.markCameraReady(true, it) },
            )
        }.onFailure { error ->
            viewModel.reportCameraError(error.message ?: error::class.java.simpleName)
        }
    }

    LaunchedEffect(state.settings, state.cameraReady) {
        if (state.cameraReady) {
            cameraController.applyLiveControls(state.settings)
        }
    }

    DisposableEffect(Unit) {
        onDispose { cameraController.close() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                when (state.tallyState) {
                    TallyState.PROGRAM -> Modifier.border(8.dp, Color.Red, RectangleShape)
                    TallyState.PREVIEW -> Modifier.border(6.dp, Color(0xFFFACC15), RectangleShape)
                    else -> Modifier.border(6.dp, Color(0xFF4ADE80), RectangleShape)
                }
            )
    ) {
        // Camera preview fills the entire background
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
        )

        // Overlays drawn on top of the preview
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TopStrip(
                state = state,
                onSettingsClick = { activeControl = OverlayControl.SETTINGS },
                onLensClick = {
                    viewModel.setLens(if (state.settings.lensFacing == LensFacingOption.BACK) LensFacingOption.FRONT else LensFacingOption.BACK)
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FocusRail(
                    valueDiopters = state.settings.focusDistanceDiopters,
                    isAuto = state.settings.focusAuto,
                    onValueChange = viewModel::setFocusDistance,
                    onIncrease = { viewModel.setFocusDistance((state.settings.focusDistanceDiopters + 0.5f).coerceAtMost(10f)) },
                    onDecrease = { viewModel.setFocusDistance((state.settings.focusDistanceDiopters - 0.5f).coerceAtLeast(0f)) },
                    onFocusCommit = { },
                )

                Spacer(modifier = Modifier.weight(1f))

                ZoomRail(
                    value = state.settings.zoomRatio,
                    onValueChange = viewModel::setZoom,
                    onIncrease = { viewModel.setZoom((state.settings.zoomRatio + 0.2f).coerceAtMost(5f)) },
                    onDecrease = { viewModel.setZoom((state.settings.zoomRatio - 0.2f).coerceAtLeast(1f)) },
                )
            }

            if (activeControl == null) {
                BottomStrip(
                    state = state,
                    activeControl = activeControl,
                    onControlClick = { control ->
                        activeControl = if (activeControl == control) null else control
                    },
                )
            }
        }

        if (activeControl != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f))
                    .clickable { activeControl = null },
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when (activeControl) {
                    OverlayControl.ISO -> SelectionTray(
                        title = "ISO",
                        currentLabel = state.settings.iso.toString(),
                        options = listOf(50, 100, 200, 400, 800, 1600, 3200),
                        optionLabel = { it.toString() },
                        onSelect = {
                            viewModel.setIso(it)
                            activeControl = null
                        },
                    )

                    OverlayControl.SHUTTER -> SelectionTray(
                        title = "Shutter speed",
                        currentLabel = "${state.settings.shutterSpeedMs} ms",
                        options = listOf(1, 2, 4, 8, 15, 30, 60, 120),
                        optionLabel = { "$it ms" },
                        onSelect = {
                            viewModel.setShutterSpeed(it)
                            activeControl = null
                        },
                    )

                    OverlayControl.RESOLUTION -> SelectionTray(
                        title = "Resolution",
                        currentLabel = state.settings.resolutionPreset.label,
                        options = ResolutionPreset.entries,
                        optionLabel = { it.label },
                        onSelect = {
                            viewModel.setResolution(it)
                            activeControl = null
                        },
                    )

                    OverlayControl.FRAME_RATE -> SelectionTray(
                        title = "Frame rate",
                        currentLabel = "${state.settings.frameRate} fps",
                        options = listOf(24, 30, 60),
                        optionLabel = { "$it fps" },
                        onSelect = {
                            viewModel.setFrameRate(it)
                            activeControl = null
                        },
                    )

                    OverlayControl.FOCUS -> FocusOverlay(
                        valueDiopters = state.settings.focusDistanceDiopters,
                        onValueChange = viewModel::setFocusDistance,
                        onFocusCommit = { },
                    )

                    OverlayControl.SETTINGS -> InfoOverlay(
                        serverRunning = state.serverRunning,
                        streamUrl = state.streamUrl,
                        dashboardUrl = state.dashboardUrl,
                        localIp = state.localIpAddress,
                    )

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun TopInfoBar(state: BridgeState, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.padding(top = 8.dp), color = Color(0xB3000000), shape = RectangleShape) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.connectedClients > 0 || state.tallyState != TallyState.IDLE) {
                    Box(
                        modifier = Modifier
                            .background(
                                when (state.tallyState) {
                                    TallyState.PROGRAM -> Color.Red
                                    TallyState.PREVIEW -> Color(0xFFFACC15)
                                    else -> Color(0xFF4ADE80) // Green for Dashboard/Remote
                                }, 
                                RectangleShape
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = when(state.tallyState) {
                                TallyState.PROGRAM -> "LIVE / ON AIR"
                                TallyState.PREVIEW -> "OBS PREVIEW"
                                else -> "DASHBOARD ACTIVE"
                            },
                            color = if (state.tallyState == TallyState.PROGRAM) Color.White else Color.Black,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
                Text(text = state.settings.resolutionPreset.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(text = "ISO ${state.settings.iso}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(text = "Shutter ${state.settings.shutterSpeedMs}ms", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(text = "f/${1.8}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(text = "${state.settings.frameRate}fps", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = state.statusMessage,
                color = Color(0xFF4ADE80),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun TopStrip(
    state: BridgeState,
    onSettingsClick: () -> Unit,
    onLensClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopInfoBar(state = state)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = Color(0x66000000),
                shape = RectangleShape,
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onSettingsClick),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Open Settings",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Surface(
                color = Color(0x66000000),
                shape = RectangleShape,
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onLensClick),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

        }
    }
}

@Composable
private fun ZoomRail(
    value: Float,
    onValueChange: (Float) -> Unit,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(132.dp)
            .fillMaxHeight(),
        color = Color(0x66000000),
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

@Composable
private fun BottomStrip(
    state: BridgeState,
    activeControl: OverlayControl?,
    onControlClick: (OverlayControl) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Color(0x66000000), shape = RectangleShape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ControlTile(
                label = "ISO",
                reading = state.settings.iso.toString(),
                active = activeControl == OverlayControl.ISO,
                onClick = { onControlClick(OverlayControl.ISO) },
            )
            ControlTile(
                label = "Shutter speed",
                reading = "${state.settings.shutterSpeedMs} ms",
                active = activeControl == OverlayControl.SHUTTER,
                onClick = { onControlClick(OverlayControl.SHUTTER) },
            )
            ControlTile(
                label = "resolution",
                reading = state.settings.resolutionPreset.label,
                active = activeControl == OverlayControl.RESOLUTION,
                onClick = { onControlClick(OverlayControl.RESOLUTION) },
            )
            ControlTile(
                label = "frame rate",
                reading = "${state.settings.frameRate} fps",
                active = activeControl == OverlayControl.FRAME_RATE,
                onClick = { onControlClick(OverlayControl.FRAME_RATE) },
            )
        }
    }
}

@Composable
private fun RowScope.ControlTile(
    label: String,
    reading: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(56.dp),
        color = if (active) Color(0x88FFFFFF) else Color(0x44FFFFFF),
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = Color.Black, style = MaterialTheme.typography.labelSmall)
            Text(reading, color = Color.Black, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun <T> SelectionTray(
    title: String,
    currentLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Surface(color = Color(0xCCFFFFFF), shape = RectangleShape) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = Color.Black, style = MaterialTheme.typography.titleMedium)
            Text(currentLabel, color = Color.Black, style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    val label = optionLabel(option)
                    Surface(
                        color = Color(0x88FFFFFF),
                        shape = RectangleShape,
                        modifier = Modifier
                            .height(44.dp)
                            .clickable { onSelect(option) },
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = label, color = Color.Black, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusRail(
    valueDiopters: Float,
    isAuto: Boolean,
    onValueChange: (Float) -> Unit,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onFocusCommit: () -> Unit,
) {
    var draftValue by remember(valueDiopters) { mutableStateOf(valueDiopters) }
    Surface(modifier = Modifier.width(132.dp).fillMaxHeight(), color = Color(0x66000000), shape = RectangleShape) {
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


@Composable
private fun FocusOverlay(
    valueDiopters: Float,
    onValueChange: (Float) -> Unit,
    onFocusCommit: () -> Unit,
) {
    var draftValue by remember(valueDiopters) { mutableStateOf(valueDiopters) }
    Surface(color = Color(0xCCFFFFFF), shape = RectangleShape) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Focus", color = Color.Black)
            Text(focusDistanceLabel(draftValue), color = Color.Black, style = MaterialTheme.typography.headlineMedium)
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
                modifier = Modifier.width(240.dp),
            )
        }
    }
}

@Composable
private fun InfoOverlay(
    serverRunning: Boolean,
    streamUrl: String,
    dashboardUrl: String,
    localIp: String,
) {
    Surface(color = Color(0xCCFFFFFF), shape = RectangleShape) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Settings", color = Color.Black)
            Text("Server: ${if (serverRunning) "running" else "stopped"}", color = Color.Black)
            Text("Dashboard: $dashboardUrl", color = Color.Black)
            Text("Stream: $streamUrl", color = Color.Black)
            Text("IP: $localIp", color = Color.Black)
        }
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Camera permission is required", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Grant access to start the live preview and the local OBS bridge.")
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onGrant) { Text("Grant camera permission") }
    }
}

private enum class OverlayControl {
    ISO,
    SHUTTER,
    RESOLUTION,
    FRAME_RATE,
    FOCUS,
    SETTINGS,
}

