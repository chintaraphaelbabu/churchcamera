package com.raphael.androidwebcambridge.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DrawerState

import com.raphael.androidwebcambridge.bridge.BridgeViewModel
import com.raphael.androidwebcambridge.bridge.BridgeState
import com.raphael.androidwebcambridge.bridge.TallyState
import com.raphael.androidwebcambridge.bridge.CameraSessionController
import com.raphael.androidwebcambridge.bridge.LensFacingOption
import com.raphael.androidwebcambridge.bridge.ResolutionPreset
import com.raphael.androidwebcambridge.ui.components.*

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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
        val observer = LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = Color(0xEEFFFFFF),
                drawerShape = RectangleShape
            ) {
                SettingsTray(
                    focusVelocity = state.settings.focusVelocity,
                    zoomVelocity = state.settings.zoomVelocity,
                    onFocusVelocityChange = viewModel::setFocusVelocity,
                    onZoomVelocityChange = viewModel::setZoomVelocity
                )
            }
        },
        gesturesEnabled = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    when (state.tallyState) {
                        TallyState.PROGRAM -> Modifier.border(8.dp, Color(0xFFEF4444), RectangleShape)
                        TallyState.PREVIEW -> Modifier.border(6.dp, Color(0xFFF59E0B), RectangleShape)
                        else -> Modifier.border(2.dp, Color(0xFF10B981).copy(alpha = 0.3f), RectangleShape)
                    }
                )
        ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clickable { viewModel.setActiveRail(null) },
            factory = { previewView },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TopStrip(
                state = state,
                onSettingsClick = { activeControl = if (activeControl == OverlayControl.SETTINGS) null else OverlayControl.SETTINGS },
                onLensClick = {
                    viewModel.setLens(if (state.settings.lensFacing == LensFacingOption.BACK) LensFacingOption.FRONT else LensFacingOption.BACK)
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FocusRail(
                    valueDiopters = state.settings.focusDistanceDiopters,
                    isAuto = state.settings.focusAuto,
                    isActive = state.activeRail == BridgeState.RailType.FOCUS,
                    onValueChange = viewModel::setFocusDistance,
                    onIncrease = { viewModel.setFocusDistance((state.settings.focusDistanceDiopters + 0.5f).coerceAtMost(10f)) },
                    onDecrease = { viewModel.setFocusDistance((state.settings.focusDistanceDiopters - 0.5f).coerceAtLeast(0f)) },
                    onFocusCommit = { },
                    onActiveChange = { if (it) viewModel.setActiveRail(BridgeState.RailType.FOCUS) else viewModel.setActiveRail(null) },
                )

                ZoomRail(
                    value = state.settings.physicalZoomRatio,
                    isActive = state.activeRail == BridgeState.RailType.ZOOM,
                    onValueChange = viewModel::setZoom,
                    onIncrease = { viewModel.setZoom((state.settings.physicalZoomRatio + 0.2f).coerceAtMost(5f)) },
                    onDecrease = { viewModel.setZoom((state.settings.physicalZoomRatio - 0.2f).coerceAtLeast(1f)) },
                    onActiveChange = { if (it) viewModel.setActiveRail(BridgeState.RailType.ZOOM) else viewModel.setActiveRail(null) },
                )
            }

            BottomStrip(
                state = state,
                isControlActive = { activeControl == it },
                onControlClick = { control ->
                    activeControl = if (activeControl == control) null else control
                },
                activeRail = state.activeRail,
                onRailClick = { rail ->
                    viewModel.setActiveRail(if (state.activeRail == rail) null else rail)
                }
            )
        }

        AnimatedVisibility(
            visible = activeControl != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { activeControl = null },
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.clickable(enabled = false) { }) {
                    when (activeControl) {
                        OverlayControl.ISO -> SelectionTray(
                            title = "ISO Sensitivity",
                            currentLabel = if (state.settings.iso == 0) "Auto" else state.settings.iso.toString(),
                            options = listOf(0, 100, 200, 400, 800, 1600, 3200, 6400),
                            optionLabel = { if (it == 0) "Auto" else it.toString() },
                            onSelect = {
                                viewModel.setIso(it)
                                activeControl = null
                            },
                        )

                        OverlayControl.SHUTTER -> SelectionTray(
                            title = "Shutter Speed",
                            currentLabel = if (state.settings.shutterSpeedMs == 0) "Auto" else "${state.settings.shutterSpeedMs} ms",
                            options = listOf(0, 1, 2, 4, 8, 15, 30, 60, 120),
                            optionLabel = { if (it == 0) "Auto" else "$it ms" },
                            onSelect = {
                                viewModel.setShutterSpeed(it)
                                activeControl = null
                            },
                        )

                        OverlayControl.RESOLUTION -> SelectionTray(
                            title = "Output Resolution",
                            currentLabel = state.settings.resolutionPreset.label,
                            options = ResolutionPreset.entries,
                            optionLabel = { it.label },
                            onSelect = {
                                viewModel.setResolution(it)
                                activeControl = null
                            },
                        )

                        OverlayControl.FRAME_RATE -> SelectionTray(
                            title = "Target Frame Rate",
                            currentLabel = "${state.settings.frameRate} FPS",
                            options = listOf(24, 30, 60),
                            optionLabel = { "$it FPS" },
                            onSelect = {
                                viewModel.setFrameRate(it)
                                activeControl = null
                            },
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
    }
}
