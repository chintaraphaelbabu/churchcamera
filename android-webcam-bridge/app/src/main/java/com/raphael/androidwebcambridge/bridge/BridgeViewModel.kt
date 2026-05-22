package com.raphael.androidwebcambridge.bridge

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application
    private val prefs = application.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)
    private var networkCallbackRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val relayManager = RelayManager(application, viewModelScope)
    private val server = LocalBridgeServer(
        onRemoteUpdate = ::applyRemoteUpdate,
        onConnectionStatusChanged = ::updateClientCount
    )

    private val _state = MutableStateFlow(
        BridgeState(
            serverRunning = false,
            localIpAddress = findLocalIpv4Address(),
            statusMessage = "Starting",
            settings = BridgeSettings(
                focusVelocity = prefs.getFloat("focus_velocity", 0.1f),
                zoomVelocity = prefs.getFloat("zoom_velocity", 0.1f)
            )
        ),
    )
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    init {
        startServer()
        startNetworkWatch()
    }

    fun setRelayHost(host: String) {
        relayManager.setRelayHost(host)
    }

    fun setRelaySourceName(sourceName: String) {
        relayManager.setRelaySourceName(sourceName)
    }

    fun refreshRelayRegistration() {
        relayManager.refreshRelayRegistration()
    }

    fun pauseRelayHeartbeat() {
        relayManager.pauseRelayHeartbeat()
    }

    fun pingRelayNow() {
        relayManager.pingRelayNow()
    }

    private fun startNetworkWatch() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (networkCallbackRegistered) return
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    viewModelScope.launch { relayManager.registerWithRelay() }
                }
            }
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
            networkCallbackRegistered = true
        } catch (_: Exception) {}
    }

    private var lastStateUpdateAt = 0L
    private var tallyHoldJob: Job? = null

    fun onFrame(frame: ByteArray) {
        server.submitFrame(frame)
        
        val now = System.currentTimeMillis()
        if (now - lastStateUpdateAt > 1000L) {
            lastStateUpdateAt = now
            _state.update {
                it.copy(
                    cameraReady = true,
                    lastFrameAt = now,
                )
            }
            server.updateState(_state.value)
        }
    }

    fun onFacesDetected(faces: List<DetectedFace>) {
        _state.update { current ->
            var nextPanX = current.settings.panX
            var nextPanY = current.settings.panY

            if (current.settings.faceFollowEnabled && faces.isNotEmpty()) {
                val target = faces.find { it.id == current.settings.selectedFaceId } ?: faces.first()
                nextPanX = (target.x - 0.5f) * 2f
                nextPanY = (target.y - 0.5f) * 2f
            }

            current.copy(
                detectedFaces = faces,
                settings = current.settings.copy(
                    panX = nextPanX,
                    panY = nextPanY,
                    selectedFaceId = if (current.settings.faceFollowEnabled && faces.isNotEmpty()) 
                        (faces.find { it.id == current.settings.selectedFaceId } ?: faces.first()).id 
                        else current.settings.selectedFaceId
                )
            )
        }
    }

    fun markCameraReady(ready: Boolean, message: String) {
        _state.update { it.copy(cameraReady = ready, cameraStatus = message, statusMessage = message) }
        server.updateState(_state.value)
    }

    fun reportCameraError(message: String) {
        _state.update {
            it.copy(
                cameraReady = false,
                cameraStatus = message,
                errorMessage = message,
                statusMessage = "Camera startup failed",
            )
        }
        server.updateState(_state.value)
    }

    fun setZoom(zoomRatio: Float) = updateSettings { it.copy(physicalZoomRatio = zoomRatio) }

    fun setDashboardZoom(zoomRatio: Float) = updateSettings { it.copy(zoomRatio = zoomRatio) }

    fun setLens(lensFacing: LensFacingOption) = updateSettings(rebind = true) { it.copy(lensFacing = lensFacing, panX = 0f, panY = 0f, zoomRatio = 1.0f) }

    fun setResolution(preset: ResolutionPreset) = updateSettings(rebind = true) { it.copy(resolutionPreset = preset) }

    fun setFrameRate(frameRate: Int) = updateSettings(rebind = true) { it.copy(frameRate = frameRate) }

    fun setIso(value: Int) = updateSettings { it.copy(iso = value) }

    fun setActiveRail(rail: BridgeState.RailType?) {
        _state.update { it.copy(activeRail = rail) }
    }

    fun setShutterSpeed(valueMs: Int) = updateSettings { it.copy(shutterSpeedMs = valueMs) }

    fun setFocusDistance(valueDiopters: Float) = updateSettings { it.copy(focusDistanceDiopters = valueDiopters, focusAuto = false) }

    fun setFocusVelocity(velocity: Float) {
        prefs.edit().putFloat("focus_velocity", velocity).apply()
        updateSettings { it.copy(focusVelocity = velocity) }
    }

    fun setZoomVelocity(velocity: Float) {
        prefs.edit().putFloat("zoom_velocity", velocity).apply()
        updateSettings { it.copy(zoomVelocity = velocity) }
    }

    fun applyRemoteUpdate(query: Map<String, String?>) {
        query["relayHost"]?.let { rh -> if (rh.isNotBlank()) setRelayHost(rh) }
        query["relaySourceName"]?.let { rn -> if (rn.isNotBlank()) setRelaySourceName(rn) }
        val newState = _state.updateAndGet { current ->
            val nextSettings = BridgeSettings.fromQuery(query, current.settings)
            val tallyStr = query["tallyState"]
            val tally = try {
                if (tallyStr == null) current.tallyState else TallyState.valueOf(tallyStr)
            } catch (_: Exception) { current.tallyState }

            val needsRebind = nextSettings.lensFacing != current.settings.lensFacing ||
                nextSettings.resolutionPreset != current.settings.resolutionPreset ||
                nextSettings.frameRate != current.settings.frameRate

            current.copy(
                settings = nextSettings,
                tallyState = tally,
                cameraRebindToken = if (needsRebind) current.cameraRebindToken + 1 else current.cameraRebindToken,
                statusMessage = computeStatus(tally, current.connectedClients)
            )
        }
        server.updateState(newState)

        if (newState.tallyState == TallyState.IDLE) {
            if (_state.value.tallyState == TallyState.PROGRAM) {
                tallyHoldJob?.cancel()
                tallyHoldJob = viewModelScope.launch {
                    delay(1500L)
                    _state.update { cur -> cur.copy(tallyState = TallyState.IDLE, statusMessage = computeStatus(TallyState.IDLE, cur.connectedClients)) }
                    server.updateState(_state.value)
                }
            }
        } else {
            tallyHoldJob?.cancel()
            tallyHoldJob = null
        }
    }

    private fun updateClientCount(total: Int) {
        _state.update { current ->
            current.copy(
                connectedClients = total,
                streaming = total > 0,
                statusMessage = computeStatus(current.tallyState, total)
            )
        }
    }

    private fun computeStatus(tally: TallyState, connectedClients: Int): String {
        return when (tally) {
            TallyState.PROGRAM -> "LIVE / ON AIR"
            TallyState.PREVIEW -> "OBS READY"
            TallyState.IDLE -> if (connectedClients > 0) "SOURCE CONNECTED" else "IDLE"
        }
    }

    fun setFocusAuto(auto: Boolean) = updateSettings { it.copy(focusAuto = auto) }

    fun updateSettings(rebind: Boolean = false, transform: (BridgeSettings) -> BridgeSettings) {
        val newState = _state.updateAndGet { current ->
            current.copy(
                settings = transform(current.settings),
                cameraRebindToken = if (rebind) current.cameraRebindToken + 1 else current.cameraRebindToken,
                statusMessage = "Settings updated",
            )
        }
        server.updateState(newState)
    }

    private fun startServer() {
        viewModelScope.launch {
            val ip = findLocalIpv4Address()
            server.start()
            _state.update {
                it.copy(
                    serverRunning = true,
                    statusMessage = "Server started",
                    localIpAddress = ip,
                    dashboardUrl = "http://$ip:8787/dashboard",
                    streamUrl = "http://$ip:8787/stream.mjpg",
                )
            }
            server.updateState(_state.value)
            relayManager.registerWithRelay()
        }
    }

    override fun onCleared() {
        server.stop()
        relayManager.pauseRelayHeartbeat()
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cb -> cm?.unregisterNetworkCallback(cb) }
        } catch (_: Exception) {}
        super.onCleared()
    }
}
