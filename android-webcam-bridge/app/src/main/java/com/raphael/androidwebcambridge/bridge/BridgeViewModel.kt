package com.raphael.androidwebcambridge.bridge

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.camera.core.MeteringPointFactory
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

class BridgeViewModel(application: Application) : AndroidViewModel(application), LifecycleOwner {
    private val app: Application = application
    private val prefs = application.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)
    private val accessTokenPrefs = application.getSharedPreferences("local_server_auth", Context.MODE_PRIVATE)
    private val accessToken =
        AccessTokenStore(
            readToken = { accessTokenPrefs.getString("access_token", null) },
            persistToken = { token ->
                check(accessTokenPrefs.edit().putString("access_token", token).commit()) {
                    "Unable to persist the local server access token"
                }
            },
        ).getOrCreate()
    private var networkCallbackRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ponytail: process-lifetime owner so CameraX keeps capturing after the Activity stops.
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val cameraController = CameraSessionController(application)
    private var previewView: PreviewView? = null
    private var previewLifecycleOwner: LifecycleOwner? = null
    private val previewLifecycleObserver =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> cameraController.setPreviewActive(true)
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> cameraController.setPreviewActive(false)
                else -> Unit
            }
        }

    private val relayManager =
        RelayManager(application, viewModelScope) { status ->
            _state.update { it.copy(relayDiscoveryStatus = status) }
        }.also { it.onTallyUpdate = { tally -> handleRelayTally(tally) } }

    private val sslContext: SSLContext? by lazy {
        try {
            val androidKeyStore = KeyStore.getInstance("AndroidKeyStore")
            androidKeyStore.load(null)
            val alias = "streamcam_server_v3"
            if (!androidKeyStore.containsAlias(alias)) {
                KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                            .setCertificateSubject(X500Principal("CN=StreamCam Pro"))
                            .setCertificateSerialNumber(BigInteger.ONE)
                            .setKeyValidityEnd(Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000))
                            .build(),
                    )
                }.generateKeyPair()
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(androidKeyStore, null)
            return@lazy SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        } catch (e: Exception) {
            Log.w("BridgeVM", "SSL init failed: ${e.message}")
            null
        }
    }

    private val server =
        LocalBridgeServer(
            sslContext = sslContext,
            accessToken = accessToken,
            onRemoteUpdate = ::applyRemoteUpdate,
            onConnectionStatusChanged = ::updateClientCount,
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
        audioRecorder =
            try {
                AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2) // ponytail: UNPROCESSED raw, MIC fallback
            } catch (_: Exception) {
                null
            }
        if (audioRecorder?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecorder?.release()
            audioRecorder = null
            return
        }
        try {
            audioRecorder?.startRecording()
        } catch (
            _: Exception,
        ) {
            audioRecorder?.release()
            audioRecorder = null
            _state.update { it.copy(operatorSpeaking = false) }
            return
        }
        runCatching { audioTrack?.pause() } // ponytail: best-effort mute, never break PTT
        _state.update { it.copy(operatorSpeaking = true) }
        pttJob =
            viewModelScope.launch(Dispatchers.IO) {
                val buf = ByteArray(4096) // ~46ms at 44100Hz 16-bit mono
                while (isActive && audioRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = audioRecorder?.read(buf, 0, buf.size) ?: 0
                    if (read > 0) server.broadcastPhoneAudio(buf.copyOf(read))
                }
            }
    }

    fun stopPTT() {
        audioRecorder?.stop() // ponytail: stop first to unblock the IO coroutine's read()
        pttJob?.cancel()
        pttJob = null
        audioRecorder?.release()
        audioRecorder = null
        audioTrack?.play()
        _state.update { it.copy(operatorSpeaking = false) }
    }

    private fun initAudioPlayback() {
        val sr = 44100
        val bufSize = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack =
            AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        audioTrack?.play()
        relayManager.onRelayAudio = { data ->
            synchronized(audioLock) {
                audioTrack?.write(data, 0, data.size)
            }
        }
        server.setPhoneAudioCallback { data ->
            synchronized(audioLock) {
                audioTrack?.write(data, 0, data.size)
            }
        }
    }

    private val _state =
        MutableStateFlow(
            BridgeState(
                serverRunning = false,
                localIpAddress = findLocalIpv4Address(),
                relayHost = prefs.getString("relay_host", "") ?: "",
                relaySourceName = prefs.getString("relay_source_name", "") ?: "",
                statusMessage = "Starting",
                settings =
                    BridgeSettings(
                        focusVelocity = prefs.getFloat("focus_velocity", 0.1f),
                        zoomVelocity = prefs.getFloat("zoom_velocity", 0.1f),
                    ),
            ),
        )
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    init {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        startServer()
        startNetworkWatch()
        startCameraSettingsObserver()
    }

    fun bindCamera(
        previewView: PreviewView,
        previewLifecycleOwner: LifecycleOwner,
    ) {
        observePreviewLifecycle(previewLifecycleOwner)
        this.previewView = previewView
        try {
            cameraController.bind(
                previewView = previewView,
                analysisLifecycleOwner = this,
                previewActive = previewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                settings = _state.value.settings,
                onFrame = ::onFrame,
                onFacesDetected = ::onFacesDetected,
                onStatus = { status -> markCameraReady(true, status) },
            )
        } catch (e: Exception) {
            reportCameraError(e.message ?: e::class.java.simpleName)
        }
    }

    fun onScreenDisposed(previewView: PreviewView) {
        if (this.previewView === previewView) {
            cameraController.releasePreview(previewView)
            this.previewView = null
        }
    }

    fun setFocusPeakingEnabled(enabled: Boolean) {
        cameraController.focusPeakingEnabled = enabled
        if (!enabled) cameraController.focusPeakingBitmap = null
    }

    val focusPeakingBitmap: Bitmap? get() = cameraController.focusPeakingBitmap

    fun focusAt(
        x: Float,
        y: Float,
        factory: MeteringPointFactory,
        onComplete: () -> Unit = {},
    ) = cameraController.focusAt(x, y, factory, onComplete)

    private fun observePreviewLifecycle(owner: LifecycleOwner) {
        if (previewLifecycleOwner === owner) return
        previewLifecycleOwner?.lifecycle?.removeObserver(previewLifecycleObserver)
        previewLifecycleOwner = owner
        owner.lifecycle.addObserver(previewLifecycleObserver)
    }

    // ponytail: apply camera controls from the ViewModel so dashboard changes also work while backgrounded.
    private fun startCameraSettingsObserver() {
        viewModelScope.launch {
            var previousSettings: BridgeSettings? = null
            var previousToken = _state.value.cameraRebindToken
            _state.collect { current ->
                if (current.settings != previousSettings) {
                    previousSettings = current.settings
                    cameraController.applyLiveControls(current.settings) { status ->
                        markCameraReady(true, status)
                    }
                }
                if (current.cameraRebindToken != previousToken) {
                    previousToken = current.cameraRebindToken
                    // rebind from the ViewModel: Compose effects may be paused while the Activity is stopped
                    rebindCameraIfPossible()
                }
            }
        }
    }

    private fun rebindCameraIfPossible() {
        val view = previewView ?: return
        val owner = previewLifecycleOwner ?: return
        bindCamera(view, owner)
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
            val cb =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        viewModelScope.launch { relayManager.registerWithRelay() }
                    }
                }
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
            networkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w("BridgeVM", "network watch: ${e.message}")
        }
    }

    private var lastStateUpdateAt = 0L

    fun onFrame(frame: ByteArray) {
        server.submitFrame(frame)

        val now = System.currentTimeMillis()
        if (now - lastStateUpdateAt > 1000L) {
            lastStateUpdateAt = now
            val battery =
                runCatching {
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
                settings =
                    current.settings.copy(
                        panX = nextPanX,
                        panY = nextPanY,
                        selectedFaceId =
                            if (current.settings.faceFollowEnabled && faces.isNotEmpty()) {
                                (faces.find { it.id == current.settings.selectedFaceId } ?: faces.first()).id
                            } else {
                                current.settings.selectedFaceId
                            },
                    ),
            )
        }
    }

    fun markCameraReady(
        ready: Boolean,
        message: String,
    ) {
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

    fun setZoom(combined: Float) {
        val maxPhys = _state.value.maxPhysicalZoom.coerceAtLeast(1.0f)
        val phys = combined.coerceAtMost(maxPhys)
        val digi = if (combined > maxPhys) combined / maxPhys else 1.0f
        updateSettings { it.copy(physicalZoomRatio = phys, zoomRatio = digi) }
    }

    fun setDashboardZoom(zoomRatio: Float) = updateSettings { it.copy(zoomRatio = zoomRatio) }

    fun setCamera(cameraId: String) {
        val lens = _state.value.availableLenses.find { it.cameraId == cameraId }
        val lensName = lens?.shortDisplayName() ?: ""
        _state.update {
            it.copy(
                lensDisplayName = lensName,
                maxPhysicalZoom = lens?.maxPhysicalZoom ?: 3.0f,
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
            val defaultId =
                lenses.firstOrNull { it.facing != CameraCharacteristics.LENS_FACING_FRONT }?.cameraId
                    ?: lenses.firstOrNull()?.cameraId
            val selectedId = it.settings.selectedCameraId ?: defaultId
            it.copy(
                availableLenses = lenses,
                maxPhysicalZoom = lenses.find { l -> l.cameraId == selectedId }?.maxPhysicalZoom ?: 3.0f,
                lensDisplayName = lenses.find { l -> l.cameraId == selectedId }?.shortDisplayName() ?: "",
                settings = it.settings.copy(selectedCameraId = selectedId),
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

    fun setScreenDimEnabled(enabled: Boolean) = updateSettings { it.copy(screenDimEnabled = enabled) }

    fun applyRemoteUpdate(query: Map<String, String?>) {
        query["relayHost"]?.let { rh -> if (rh.isNotBlank()) setRelayHost(rh) }
        query["relaySourceName"]?.let { rn -> if (rn.isNotBlank()) setRelaySourceName(rn) }
        val newState =
            _state.updateAndGet { current ->
                val nextSettings = BridgeSettings.fromQuery(query, current.settings)
                val tallyStr = query["tallyState"]
                val tally =
                    try {
                        if (tallyStr == null) current.tallyState else TallyState.valueOf(tallyStr)
                    } catch (_: Exception) {
                        current.tallyState
                    }

                val needsRebind =
                    nextSettings.selectedCameraId != current.settings.selectedCameraId ||
                        nextSettings.resolutionPreset != current.settings.resolutionPreset ||
                        nextSettings.frameRate != current.settings.frameRate

                current.copy(
                    settings = nextSettings,
                    tallyState = tally,
                    cameraRebindToken = if (needsRebind) current.cameraRebindToken + 1 else current.cameraRebindToken,
                    statusMessage = computeStatus(tally, current.connectedClients),
                )
            }
        server.updateState(newState)
    }

    private fun updateClientCount(total: Int) {
        _state.update { current ->
            current.copy(
                connectedClients = total,
                streaming = total > 0,
                statusMessage = computeStatus(current.tallyState, total),
            )
        }
    }

    private fun computeStatus(
        tally: TallyState,
        connectedClients: Int,
    ): String {
        return when (tally) {
            TallyState.PROGRAM -> "LIVE / ON AIR"
            TallyState.PREVIEW -> "OBS READY"
            TallyState.IDLE -> if (connectedClients > 0) "SOURCE CONNECTED" else "IDLE"
        }
    }

    // ponytail: WebSocket tally from relay — same logic as HTTP tally in applyRemoteUpdate
    private fun handleRelayTally(tally: TallyState) {
        applyRemoteUpdate(mapOf("tallyState" to tally.name))
    }

    fun setFocusAuto(auto: Boolean) = updateSettings { it.copy(focusAuto = auto) }

    fun updateSettings(
        rebind: Boolean = false,
        transform: (BridgeSettings) -> BridgeSettings,
    ) {
        val newState =
            _state.updateAndGet { current ->
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
            val dashboardPort = if (sslContext != null) 8788 else 8787
            _state.update {
                it.copy(
                    serverRunning = true,
                    statusMessage = "Server started",
                    localIpAddress = ip,
                    dashboardUrl = withAccessToken("$proto://$ip:$dashboardPort/dashboard", accessToken),
                    streamUrl = withAccessToken("$proto://$ip:$dashboardPort/stream.mjpg", accessToken),
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
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        }
        cameraController.close()
        previewLifecycleOwner?.lifecycle?.removeObserver(previewLifecycleObserver)
        previewLifecycleOwner = null
        previewView = null
        server.stop()
        relayManager.stopDiscovery()
        relayManager.pauseRelayHeartbeat()
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cb -> cm?.unregisterNetworkCallback(cb) }
        } catch (e: Exception) {
            Log.w("BridgeVM", "onCleared: ${e.message}")
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onCleared()
    }
}
