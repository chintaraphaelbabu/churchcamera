package com.raphael.androidwebcambridge.bridge

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application
    private val prefs = application.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)
    private var networkCallbackRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val relayManager = RelayManager(application, viewModelScope) { status ->
        _state.update { it.copy(relayDiscoveryStatus = status) }
    }

    private val sslContext: SSLContext? by lazy {
        try {
            val ks = KeyStore.getInstance("PKCS12")
            val resId = app.resources.getIdentifier("keystore", "raw", app.packageName)
            if (resId == 0) return@lazy null
            app.resources.openRawResource(resId).use { stream -> ks.load(stream, "changeit".toCharArray()) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, "changeit".toCharArray())
            SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        } catch (_: Exception) { null }
    }

    private val server = LocalBridgeServer(
        sslContext = sslContext,
        onRemoteUpdate = ::applyRemoteUpdate,
        onConnectionStatusChanged = ::updateClientCount
    )

    // ponytail: AudioRecord/AudioTrack inline, thin class if complexity grows
    private var audioRecorder: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var pttJob: Job? = null
    private val audioLock = Any() // ponytail: protects write vs release race

    fun startPTT() {
        if (audioRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) return
        val sr = 44100
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val source = if (Build.VERSION.SDK_INT >= 26) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        audioRecorder = try {
            AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2) // ponytail: UNPROCESSED raw, MIC fallback
        } catch (_: Exception) { null }
        if (audioRecorder?.state != AudioRecord.STATE_INITIALIZED) { audioRecorder?.release(); audioRecorder = null; return }
        try { audioRecorder?.startRecording() } catch (_: Exception) { audioRecorder?.release(); audioRecorder = null; _state.update { it.copy(operatorSpeaking = false) }; return }
        runCatching { audioTrack?.pause() } // ponytail: best-effort mute, never break PTT
        _state.update { it.copy(operatorSpeaking = true) }
        pttJob = viewModelScope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096) // ~46ms at 44100Hz 16-bit mono
            while (isActive && audioRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecorder?.read(buf, 0, buf.size) ?: 0
                if (read > 0) server.broadcastPhoneAudio(buf.copyOf(read))
            }
        }
    }

    fun stopPTT() {
        audioRecorder?.stop() // ponytail: stop first to unblock the IO coroutine's read()
        pttJob?.cancel(); pttJob = null
        audioRecorder?.release(); audioRecorder = null
        audioTrack?.play()
        _state.update { it.copy(operatorSpeaking = false) }
    }

    private fun initAudioPlayback() {
        val sr = 44100
        val bufSize = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
        server.setPhoneAudioCallback { data ->
            synchronized(audioLock) {
                audioTrack?.write(data, 0, data.size)
            }
        }
    }

    private val _state = MutableStateFlow(
        BridgeState(
            serverRunning = false,
            localIpAddress = findLocalIpv4Address(),
            relayHost = prefs.getString("relay_host", "") ?: "",
            relaySourceName = prefs.getString("relay_source_name", "") ?: "",
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
        _state.update { it.copy(relayHost = host) }
    }

    fun setRelaySourceName(sourceName: String) {
        relayManager.setRelaySourceName(sourceName)
        _state.update { it.copy(relaySourceName = sourceName) }
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
            val battery = runCatching {
                (app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            }.getOrDefault(-1)
            _state.update {
                it.copy(
                    cameraReady = true,
                    lastFrameAt = now,
                    batteryLevel = battery,
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

    fun setLensDisplayName(name: String) {
        _state.update { it.copy(lensDisplayName = name) }
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

    fun setCamera(cameraId: String) {
        val lensName = _state.value.availableLenses.find { it.cameraId == cameraId }?.shortDisplayName() ?: ""
        _state.update {
            it.copy(
                lensDisplayName = lensName,
                settings = it.settings.copy(selectedCameraId = cameraId, panX = 0f, panY = 0f, zoomRatio = 1.0f),
                cameraRebindToken = it.cameraRebindToken + 1,
                statusMessage = "Settings updated",
            )
        }
        server.updateState(_state.value)
    }

    fun cycleCamera() {
        val current = _state.value
        val lenses = current.availableLenses
        if (lenses.isEmpty()) return
        val idx = lenses.indexOfFirst { it.cameraId == current.settings.selectedCameraId }
        val next = if (idx < 0 || idx >= lenses.size - 1) 0 else idx + 1
        setCamera(lenses[next].cameraId)
    }

    fun initLenses(lenses: List<LensInfo>) {
        _state.update {
            val defaultId = lenses.firstOrNull { it.facing != CameraCharacteristics.LENS_FACING_FRONT }?.cameraId
                ?: lenses.firstOrNull()?.cameraId
            it.copy(
                availableLenses = lenses,
                lensDisplayName = lenses.find { l -> l.cameraId == (it.settings.selectedCameraId ?: defaultId) }?.shortDisplayName() ?: "",
                settings = it.settings.copy(selectedCameraId = it.settings.selectedCameraId ?: defaultId),
            )
        }
    }

    fun setResolution(preset: ResolutionPreset) = updateSettings(rebind = true) { it.copy(resolutionPreset = preset) }

    fun setFrameRate(frameRate: Int) = updateSettings(rebind = true) { it.copy(frameRate = frameRate) }

    fun setIso(value: Int) = updateSettings { it.copy(iso = value) }

    fun setWhiteBalanceKelvin(kelvin: Int) {
        val wasAuto = _state.value.settings.whiteBalanceKelvin == 0
        val nowAuto = kelvin == 0
        updateSettings(rebind = wasAuto != nowAuto) { it.copy(whiteBalanceKelvin = kelvin) }
    }

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

            val needsRebind = nextSettings.selectedCameraId != current.settings.selectedCameraId ||
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
        initAudioPlayback()
        viewModelScope.launch {
            val ip = findLocalIpv4Address()
            server.start()
            val proto = if (sslContext != null) "https" else "http"
            _state.update {
                it.copy(
                    serverRunning = true,
                    statusMessage = "Server started",
                    localIpAddress = ip,
                    dashboardUrl = "$proto://$ip:8787/dashboard",
                    streamUrl = "$proto://$ip:8787/stream.mjpg",
                )
            }
            server.updateState(_state.value)
            val existingHost = prefs.getString("relay_host", null)
            relayManager.startDiscovery()
            if (existingHost.isNullOrBlank()) {
                if (!relayManager.trySsidCache()) {
                    _state.update { it.copy(relayDiscoveryStatus = "Searching for relay...") }
                }
            } else {
                relayManager.registerWithRelay()
            }
        }
    }

    override fun onCleared() {
        stopPTT()
        synchronized(audioLock) {
            audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        }
        server.stop()
        relayManager.stopDiscovery()
        relayManager.pauseRelayHeartbeat()
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cb -> cm?.unregisterNetworkCallback(cb) }
        } catch (_: Exception) {}
        super.onCleared()
    }
}
