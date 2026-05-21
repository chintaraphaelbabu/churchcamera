package com.raphael.androidwebcambridge.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Surface
import org.webrtc.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebRtcManager(private val context: Context) {
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var inputSurface: Surface? = null
    
    @Volatile
    private var width: Int = 1280
    @Volatile
    private var height: Int = 720

    init {
        initializeWebRtc()
    }

    private fun initializeWebRtc() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )

            eglBase = EglBase.create()
            val options = PeerConnectionFactory.Options()
            val encoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            videoSource = peerConnectionFactory?.createVideoSource(false)
            videoTrack = peerConnectionFactory?.createVideoTrack("ARDMSv0", videoSource)

            surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcVideoCapturer", eglBase?.eglBaseContext)
            surfaceTextureHelper?.setTextureSize(width, height)
            surfaceTextureHelper?.startListening { videoFrame ->
                videoSource?.capturerObserver?.onFrameCaptured(videoFrame)
            }
            
            surfaceTextureHelper?.surfaceTexture?.let { texture ->
                inputSurface = Surface(texture)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setStreamSize(w: Int, h: Int) {
        if (width != w || height != h) {
            width = w
            height = h
            surfaceTextureHelper?.setTextureSize(w, h)
        }
    }

    fun injectBitmap(bitmap: Bitmap) {
        val surface = inputSurface ?: return
        try {
            val canvas = surface.lockCanvas(null) ?: return
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), null)
            surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            // Surface might be released or reconfigured
        }
    }

    fun handleOffer(sdpOffer: String, onAnswerReady: (String) -> Unit) {
        // Close existing connection if any
        closePeerConnection()

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val latch = CountDownLatch(1)
        var sdpAnswer = ""

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    val localDesc = peerConnection?.localDescription
                    if (localDesc != null) {
                        sdpAnswer = localDesc.description
                        latch.countDown()
                    }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        val pc = peerConnectionFactory?.createPeerConnection(rtcConfig, observer) ?: return
        peerConnection = pc

        // Add local video track
        videoTrack?.let { track ->
            pc.addTrack(track, listOf("ARDMSs0"))
        }

        // Set Remote SDP
        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                // Create Answer
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc != null) {
                            pc.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(d: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    // Wait for ICE gathering complete to bundle candidates in SDP
                                }
                                override fun onCreateFailure(error: String?) {
                                    latch.countDown()
                                }
                                override fun onSetFailure(error: String?) {
                                    latch.countDown()
                                }
                            }, desc)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        latch.countDown()
                    }
                    override fun onSetFailure(error: String?) {
                        latch.countDown()
                    }
                }, MediaConstraints())
            }
            override fun onCreateFailure(error: String?) {
                latch.countDown()
            }
            override fun onSetFailure(error: String?) {
                latch.countDown()
            }
        }, offerDesc)

        // Wait in a separate thread for ICE gathering to complete (up to 1.5 seconds)
        Thread {
            try {
                val success = latch.await(1500, TimeUnit.MILLISECONDS)
                if (!success || sdpAnswer.isEmpty()) {
                    // Fallback to current local description if timeout occurred
                    sdpAnswer = peerConnection?.localDescription?.description ?: ""
                }
                onAnswerReady(sdpAnswer)
            } catch (e: Exception) {
                e.printStackTrace()
                onAnswerReady("")
            }
        }.start()
    }

    private fun closePeerConnection() {
        try {
            peerConnection?.dispose()
            peerConnection = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun close() {
        closePeerConnection()
        try {
            surfaceTextureHelper?.stopListening()
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            inputSurface?.release()
            inputSurface = null
            videoSource?.dispose()
            videoSource = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            eglBase?.release()
            eglBase = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
