package com.raphael.androidwebcambridge.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raphael.androidwebcambridge.bridge.BridgeState
import com.raphael.androidwebcambridge.bridge.BridgeViewModel
import com.raphael.androidwebcambridge.bridge.CameraSessionController
import com.raphael.androidwebcambridge.bridge.ResolutionPreset
import com.raphael.androidwebcambridge.bridge.TallyState
import com.raphael.androidwebcambridge.bridge.formatShutter
import com.raphael.androidwebcambridge.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

    LaunchedEffect(Unit) {
        activity?.window?.let { window ->
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    val previewView =
        remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }

    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasAudioPermission by remember { mutableStateOf(false) }
    var activeControl by remember { mutableStateOf<OverlayControl?>(null) }
    var showLeftDrawer by remember { mutableStateOf(false) }
    var showRightDrawer by remember { mutableStateOf(false) }
    var focusPeakingEnabled by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    val focusPeakingBitmap = remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var focusPos by remember { mutableStateOf<Offset?>(null) }
    var focusLocked by remember { mutableStateOf(false) }

    LaunchedEffect(focusPos) {
        if (focusPos != null) {
            delay(1500)
            focusPos = null
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { perms ->
            hasCameraPermission = perms[Manifest.permission.CAMERA] == true
            hasAudioPermission = perms[Manifest.permission.RECORD_AUDIO] == true
        }

    LaunchedEffect(Unit) {
        val perms = mutableSetOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(
                Manifest.permission.CAMERA,
            )
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(
                Manifest.permission.RECORD_AUDIO,
            )
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        } else {
            hasCameraPermission = true
            hasAudioPermission = true
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
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

    if (!hasCameraPermission || !hasAudioPermission) {
        PermissionGate(onGrant = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) })
        return
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            val lenses = CameraSessionController.discoverCameras(context)
            viewModel.initLenses(lenses)
        }
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

    LaunchedEffect(focusPeakingEnabled) {
        cameraController.focusPeakingEnabled = focusPeakingEnabled
        if (!focusPeakingEnabled) {
            cameraController.focusPeakingBitmap = null
            focusPeakingBitmap.value = null
        }
    }

    // ponytail: poll focus peaking bitmap at ~12fps, frame already processed
    LaunchedEffect(focusPeakingEnabled) {
        while (isActive && focusPeakingEnabled) {
            val bmp = cameraController.focusPeakingBitmap
            focusPeakingBitmap.value = bmp?.takeIf { !it.isRecycled }?.asImageBitmap()
            delay(83)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraController.close()
            runCatching { context.stopService(Intent(context, com.raphael.androidwebcambridge.bridge.ForegroundService::class.java)) }
        }
    }

    // ponytail: partial wake lock keeps CPU alive even when screen dims
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val wakeLock = remember { powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app:stream") }
    DisposableEffect(Unit) {
        wakeLock.acquire(600_000L) // ponytail: 10min safety timeout, OS auto-releases if app crashes
        onDispose { runCatching { if (wakeLock.isHeld) wakeLock.release() } }
    }

    // ponytail: foreground service + battery exemption — OxygenOS kills without either
    LaunchedEffect(Unit) {
        runCatching { context.startForegroundService(Intent(context, com.raphael.androidwebcambridge.bridge.ForegroundService::class.java)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        }
    }

    // ponytail: inactivity timer — dims screen after 30s no touch, tap restores
    var lastInteraction by remember { mutableStateOf(0L) }
    LaunchedEffect(state.settings.screenDimEnabled, lastInteraction) {
        if (!state.settings.screenDimEnabled) return@LaunchedEffect
        while (isActive) {
            delay(30_000L)
            if (System.currentTimeMillis() - lastInteraction >= 30_000L) {
                activity?.window?.let { w ->
                    val lp = w.attributes
                    lp.screenBrightness = 0.01f
                    w.attributes = lp
                }
            }
        }
    }

    fun touchRest() {
        lastInteraction = System.currentTimeMillis()
        activity?.window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = -1f
            w.attributes = lp
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .then(
                    when (state.tallyState) {
                        TallyState.PROGRAM -> Modifier.border(8.dp, Color(0xFFEF4444), RectangleShape)
                        TallyState.PREVIEW -> Modifier.border(6.dp, Color(0xFFF59E0B), RectangleShape)
                        else -> Modifier.border(2.dp, Color(0xFF10B981).copy(alpha = 0.3f), RectangleShape)
                    },
                )
                .pointerInput(Unit) {
                    var triggered = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            triggered = false
                            touchRest()
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            touchRest()
                            if (!triggered) {
                                if (dragAmount > 0) {
                                    showLeftDrawer = true
                                } else {
                                    showRightDrawer = true
                                }
                                triggered = true
                            }
                        },
                        onDragEnd = { },
                    )
                },
    ) {
        AndroidView(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(previewView) {
                        detectTapGestures(
                            onTap = { offset ->
                                touchRest()
                                viewModel.setActiveRail(null)
                                // ponytail: tap to focus, no lock — lock via focus rail if needed
                                cameraController.focusAt(offset.x, offset.y, previewView.meteringPointFactory)
                                focusPos = offset
                                focusLocked = false
                            },
                            onLongPress = { offset ->
                                touchRest()
                                viewModel.setActiveRail(null)
                                viewModel.setFocusAuto(false)
                                focusPos = offset
                                focusLocked = true
                            },
                        )
                    },
            factory = { previewView },
        )

        // Focus peaking red edge overlay
        focusPeakingBitmap.value?.let { img ->
            Image(bitmap = img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }

        // ponytail: rule-of-thirds lines, no deps
        if (showGrid) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.4f)
                drawLine(c, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), strokeWidth = 1f)
                drawLine(c, Offset(2f * size.width / 3f, 0f), Offset(2f * size.width / 3f, size.height), strokeWidth = 1f)
                drawLine(c, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), strokeWidth = 1f)
                drawLine(c, Offset(0f, 2f * size.height / 3f), Offset(size.width, 2f * size.height / 3f), strokeWidth = 1f)
            }
        }

        // Focus tap/lock indicator
        focusPos?.let { pos ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (focusLocked) {
                    drawRect(Color(0xFF4ADE80), Offset(pos.x - 30f, pos.y - 30f), Size(60f, 60f), style = Stroke(2f))
                    drawLine(Color(0xFF4ADE80), Offset(pos.x - 4f, pos.y), Offset(pos.x + 4f, pos.y), strokeWidth = 2f)
                } else {
                    drawCircle(Color(0xFF4ADE80), 20f, pos, style = Stroke(2f))
                    drawLine(Color(0xFF4ADE80), Offset(pos.x - 25f, pos.y), Offset(pos.x + 25f, pos.y), strokeWidth = 1.5f)
                    drawLine(Color(0xFF4ADE80), Offset(pos.x, pos.y - 25f), Offset(pos.x, pos.y + 25f), strokeWidth = 1.5f)
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TopStrip(
                state = state,
                onLensClick = viewModel::cycleCamera,
                onPttPress = viewModel::startPTT,
                onPttRelease = viewModel::stopPTT,
            )

            if (state.operatorSpeaking) {
                Text(
                    "Operator Speaking",
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
                )
            }

            Row(
                modifier =
                    Modifier
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
                    onActiveChange = { if (it) viewModel.setActiveRail(BridgeState.RailType.FOCUS) else viewModel.setActiveRail(null) },
                )

                ZoomRail(
                    value = state.settings.physicalZoomRatio * state.settings.zoomRatio,
                    isActive = state.activeRail == BridgeState.RailType.ZOOM,
                    onValueChange = viewModel::setZoom,
                    onIncrease = {
                        val cur = state.settings.physicalZoomRatio * state.settings.zoomRatio
                        viewModel.setZoom((cur + 0.2f).coerceAtMost(10f))
                    },
                    onDecrease = {
                        val cur = state.settings.physicalZoomRatio * state.settings.zoomRatio
                        viewModel.setZoom((cur - 0.2f).coerceAtLeast(1f))
                    },
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
                },
            )
        }

        AnimatedVisibility(
            visible = activeControl != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { activeControl = null },
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.clickable(enabled = false) { }) {
                    when (activeControl) {
                        OverlayControl.ISO ->
                            SelectionTray(
                                title = "ISO Sensitivity",
                                currentLabel = if (state.settings.iso == 0) "Auto" else state.settings.iso.toString(),
                                options = listOf(0, 100, 200, 400, 800, 1600, 3200, 6400),
                                optionLabel = { if (it == 0) "Auto" else it.toString() },
                                onSelect = {
                                    viewModel.setIso(it)
                                    activeControl = null
                                },
                            )

                        OverlayControl.SHUTTER ->
                            SelectionTray(
                                title = "Shutter Speed",
                                currentLabel = formatShutter(state.settings.shutterSpeedMs),
                                options = listOf(0, 1, 2, 4, 8, 15, 30, 60, 120),
                                optionLabel = { formatShutter(it) },
                                onSelect = {
                                    viewModel.setShutterSpeed(it)
                                    activeControl = null
                                },
                            )

                        OverlayControl.WB -> {
                            val k = state.settings.whiteBalanceKelvin
                            Surface(color = Color(0xCCFFFFFF), shape = RectangleShape) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            "White Balance",
                                            color = Color.Black,
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Surface(
                                            color = Color(0x88FFFFFF),
                                            shape = RectangleShape,
                                            modifier =
                                                Modifier.height(36.dp).clickable {
                                                    viewModel.setWhiteBalanceKelvin(0)
                                                    activeControl = null
                                                },
                                        ) {
                                            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    if (k == 0) "AUTO" else "RESET",
                                                    color = Color.Black,
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }
                                    }
                                    Text(if (k == 0) "Auto" else "${k}K", color = Color.Black, style = MaterialTheme.typography.labelLarge)
                                    Slider(
                                        value = if (k > 0) k.toFloat() else 5500f,
                                        onValueChange = { viewModel.setWhiteBalanceKelvin(it.toInt().coerceIn(2500, 10000)) },
                                        valueRange = 2500f..10000f,
                                        steps = 74,
                                        colors =
                                            SliderDefaults.colors(
                                                thumbColor = Color.Black,
                                                activeTrackColor = Color.Black.copy(alpha = 0.6f),
                                                inactiveTrackColor = Color.Black.copy(alpha = 0.2f),
                                            ),
                                    )
                                }
                            }
                        }

                        OverlayControl.RESOLUTION ->
                            SelectionTray(
                                title = "Output Resolution",
                                currentLabel = state.settings.resolutionPreset.label,
                                options = ResolutionPreset.entries,
                                optionLabel = { it.label },
                                onSelect = {
                                    viewModel.setResolution(it)
                                    activeControl = null
                                },
                            )

                        else -> Unit
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showLeftDrawer,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { showLeftDrawer = false },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .background(Color(0xEEFFFFFF)),
                ) {
                    SettingsTray(
                        focusVelocity = state.settings.focusVelocity,
                        zoomVelocity = state.settings.zoomVelocity,
                        focusPeakingEnabled = focusPeakingEnabled,
                        showGrid = showGrid,
                        onFocusVelocityChange = viewModel::setFocusVelocity,
                        onZoomVelocityChange = viewModel::setZoomVelocity,
                        onFocusPeakingToggle = { focusPeakingEnabled = it },
                        onGridToggle = { showGrid = it },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showRightDrawer,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { showRightDrawer = false },
                contentAlignment = Alignment.CenterEnd,
            ) {
                ConnectionDetailsDrawer(
                    state = state,
                    onRelayHostChange = viewModel::setRelayHost,
                    onFrameRateChange = viewModel::setFrameRate,
                    onLensChange = viewModel::setCamera,
                    onScreenDimToggle = viewModel::setScreenDimEnabled,
                )
            }
        }
    }
}
