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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application
    // --- Relay registration state must exist before init() runs because startup may trigger registration.
    private val prefs = app.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)
    private var networkCallbackRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var relayHeartbeatJob: Job? = null

    private val server = LocalBridgeServer(
        onRemoteUpdate = ::applyRemoteUpdate,
        onConnectionStatusChanged = ::updateClientCount
    )

    private val _state = MutableStateFlow(
        BridgeState(
            serverRunning = false,
            dashboardUrl = "http://<phone-ip>:8787/dashboard",
            streamUrl = "http://<phone-ip>:8787/stream.mjpg",
            localIpAddress = findLocalIpv4Address(),
            statusMessage = "Starting",
        ),
    )
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    init {
        startServer()
        startNetworkWatch()
    }

    fun setRelayHost(host: String) {
        val trimmedHost = normalizeRelayHost(host) ?: return
        prefs.edit().putString("relay_host", trimmedHost).apply()
        // attempt immediate register
        viewModelScope.launch { registerWithRelay(trimmedHost, getRelaySourceName()) }
        startRelayHeartbeat()
    }

    fun setRelaySourceName(sourceName: String) {
        val trimmedSourceName = sourceName.trim()
        prefs.edit().putString("relay_source_name", trimmedSourceName).apply()
        viewModelScope.launch { registerWithRelay(getRelayHost(), trimmedSourceName) }
        startRelayHeartbeat()
    }

    fun refreshRelayRegistration() {
        viewModelScope.launch { registerWithRelay(getRelayHost(), getRelaySourceName()) }
        startRelayHeartbeat()
    }

    fun pauseRelayHeartbeat() {
        relayHeartbeatJob?.cancel()
        relayHeartbeatJob = null
    }

    fun pingRelayNow() {
        viewModelScope.launch {
            sendRelayPing()
        }
    }

    private fun getRelayHost(): String? = prefs.getString("relay_host", null)
    private fun getRelaySourceName(): String? = prefs.getString("relay_source_name", null)
    private fun getRelayDeviceId(): String? = prefs.getString("relay_device_id", null)
    private fun saveRelayDeviceId(id: String?) {
        prefs.edit().putString("relay_device_id", id).apply()
    }

    private fun normalizeRelayHost(host: String?): String? {
        val trimmed = host?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
    }

    private fun startNetworkWatch() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (networkCallbackRegistered) return
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    viewModelScope.launch { registerWithRelay() }
                }

                override fun onLost(network: Network) {
                    // no-op
                }
            }
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
            networkCallbackRegistered = true
        } catch (_: Exception) {}
    }

    private suspend fun registerWithRelay(hostOverride: String? = null, sourceNameOverride: String? = null) {
        val host = normalizeRelayHost(hostOverride) ?: normalizeRelayHost(getRelayHost()) ?: return
        val ip = findLocalIpv4Address()
        val callbackBase = "http://$ip:8787"
        val name = android.os.Build.MODEL ?: "Android Phone"
        val sourceName = sourceNameOverride?.trim()?.takeIf { it.isNotBlank() } ?: getRelaySourceName()?.takeIf { it.isNotBlank() } ?: name
        val existingId = getRelayDeviceId()
        val payload = if (existingId.isNullOrBlank()) {
            "{\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\"}"
        } else {
            "{\"id\":\"$existingId\",\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\"}"
        }

        repeat(3) { attempt ->
            try {
                withContext(Dispatchers.IO) {
                    val url = java.net.URL(host.trimEnd('/') + "/api/register")
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connectTimeout = 10000
                        readTimeout = 10000
                    }
                    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val idMatch = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(responseText)
                        saveRelayDeviceId(idMatch?.groupValues?.getOrNull(1) ?: existingId)
                        _state.update { it.copy(statusMessage = "Registered with relay") }
                        startRelayHeartbeat()
                        conn.disconnect()
                        return@withContext
                    }
                    conn.disconnect()
                    _state.update { it.copy(statusMessage = "Relay register failed: $code") }
                }
            } catch (e: Exception) {
                val message = e.message ?: e::class.java.simpleName
                _state.update { it.copy(statusMessage = "Relay register error: $message") }
                if (attempt < 2) {
                    delay(1500L)
                }
            }
        }
    }

    private suspend fun sendRelayPing() {
        val relayHost = normalizeRelayHost(getRelayHost()) ?: return
        val deviceId = getRelayDeviceId()
        val ip = findLocalIpv4Address()
        val callbackBase = "http://$ip:8787"
        val name = android.os.Build.MODEL ?: "Android Phone"
        val sourceName = getRelaySourceName()?.takeIf { it.isNotBlank() } ?: name

        try {
            withContext(Dispatchers.IO) {
                val url = java.net.URL(relayHost.trimEnd('/') + "/api/ping")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val payload = if (deviceId.isNullOrBlank()) {
                    "{\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\"}"
                } else {
                    "{\"id\":\"$deviceId\",\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\"}"
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            }
        } catch (_: Exception) {
            // best effort only
        }
    }

    private fun startRelayHeartbeat() {
        val host = normalizeRelayHost(getRelayHost()) ?: return
        if (relayHeartbeatJob?.isActive == true) return

        relayHeartbeatJob = viewModelScope.launch {
            sendRelayPing()
            while (true) {
                delay(10_000L)
                sendRelayPing()
            }
        }
    }

    private var lastStateUpdateAt = 0L
    private var tallyHoldJob: Job? = null

    fun onFrame(frame: ByteArray) {
        server.submitFrame(frame)
        
        // Update frame timestamp occasionally to keep UI reactive without flooding
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

    fun setZoom(zoomRatio: Float) = updateSettings { it.copy(zoomRatio = zoomRatio) }

    fun setExposure(index: Int) = updateSettings { it.copy(exposureCompensation = index) }

    fun setLens(lensFacing: LensFacingOption) = updateSettings(rebind = true) { it.copy(lensFacing = lensFacing, panX = 0f, panY = 0f, zoomRatio = 1.0f) }

    fun setAiHint(hint: AiLensHint) = updateSettings(rebind = true) { it.copy(aiHint = hint, lensFacing = aiHintToLensFacing(hint)) }

    fun setResolution(preset: ResolutionPreset) = updateSettings(rebind = true) { it.copy(resolutionPreset = preset) }

    fun setFrameRate(frameRate: Int) = updateSettings(rebind = true) { it.copy(frameRate = frameRate) }

    fun setIso(value: Int) = updateSettings { it.copy(iso = value) }

    fun setShutterSpeed(valueMs: Int) = updateSettings { it.copy(shutterSpeedMs = valueMs) }

    fun setFocusDistance(valueDiopters: Float) = updateSettings { it.copy(focusDistanceDiopters = valueDiopters, focusAuto = false) }

    fun setAutoFocus(enabled: Boolean) = updateSettings { it.copy(focusAuto = enabled) }

    fun setJpegQuality(value: Int) = updateSettings { it.copy(jpegQuality = value) }

    fun applyRemoteUpdate(query: Map<String, String?>) {
        // allow remote setting of relay host via query param `relayHost`
        query["relayHost"]?.let { rh -> if (!rh.isNullOrBlank()) setRelayHost(rh) }
        query["relaySourceName"]?.let { rn -> if (!rn.isNullOrBlank()) setRelaySourceName(rn) }
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

        // If the new tally is IDLE but we were previously PROGRAM, hold briefly
        // to avoid flicker when OBS temporarily stops sending events.
        if (newState.tallyState == TallyState.IDLE) {
            if (_state.value.tallyState == TallyState.PROGRAM) {
                // schedule a delayed downgrade unless another update arrives
                tallyHoldJob?.cancel()
                tallyHoldJob = viewModelScope.launch {
                    delay(1500L)
                    _state.update { cur -> cur.copy(tallyState = TallyState.IDLE, statusMessage = computeStatus(TallyState.IDLE, cur.connectedClients)) }
                    server.updateState(_state.value)
                }
            }
        } else {
            // any non-IDLE tally cancels pending hold
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

    private fun updateSettings(rebind: Boolean = false, transform: (BridgeSettings) -> BridgeSettings) {
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
            // if a relay host was already saved, register and start heartbeating now
            registerWithRelay()
        }
    }

    override fun onCleared() {
        server.stop()
        relayHeartbeatJob?.cancel()
        // unregister network callback
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cb -> cm?.unregisterNetworkCallback(cb) }
        } catch (_: Exception) {}
        super.onCleared()
    }
}
