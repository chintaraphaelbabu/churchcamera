package com.raphael.androidwebcambridge.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@ExperimentalCamera2Interop
class CameraSessionController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraProviderFuture = ProcessCameraProvider.getInstance(appContext)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
    )

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var camera2Control: Camera2CameraControl? = null
    
    @Volatile
    private var activeSettings: BridgeSettings = BridgeSettings()

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        settings: BridgeSettings,
        onFrame: (ByteArray) -> Unit,
        onFacesDetected: (List<DetectedFace>) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        activeSettings = settings
        provider = cameraProviderFuture.get()
        
        val analysisResolution = Size(2560, 1440) 
        val targetResolution = Size(settings.resolutionPreset.width, settings.resolutionPreset.height)

        val previewBuilder = Preview.Builder().setTargetResolution(targetResolution)
        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetResolution(analysisResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        configureInterop(previewBuilder, settings)
        configureInterop(analysisBuilder, settings)

        val preview = previewBuilder.build()
        val analysis = analysisBuilder.build()

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            processImageProxy(imageProxy, onFrame, onFacesDetected)
        }

        provider?.unbindAll()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val selector = CameraSelector.Builder()
            .requireLensFacing(
                when (settings.lensFacing) {
                    LensFacingOption.BACK -> CameraSelector.LENS_FACING_BACK
                    LensFacingOption.FRONT -> CameraSelector.LENS_FACING_FRONT
                },
            )
            .build()

        camera = provider?.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        camera2Control = camera?.cameraControl?.let { Camera2CameraControl.from(it) }
        applyLiveControls(settings, onStatus)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(
        imageProxy: ImageProxy,
        onFrame: (ByteArray) -> Unit,
        onFacesDetected: (List<DetectedFace>) -> Unit
    ) {
        try {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                detector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        val detected = faces.map { face ->
                            val box = face.boundingBox
                            val centerX = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) box.centerX().toFloat() else box.centerY().toFloat()
                            val centerY = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) box.centerY().toFloat() else box.centerX().toFloat()
                            val w = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height
                            val h = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.height else imageProxy.width

                            DetectedFace(
                                id = face.trackingId ?: 0,
                                x = centerX / w,
                                y = centerY / h,
                                width = box.width().toFloat() / w,
                                height = box.height().toFloat() / h
                            )
                        }
                        onFacesDetected(detected)
                    }
            }

            val jpeg = imageProxy.toConstantResolutionJpeg(activeSettings)
            onFrame(jpeg)
        } catch (e: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    fun applyLiveControls(settings: BridgeSettings, onStatus: (String) -> Unit = {}) {
        activeSettings = settings
        val activeCamera = camera ?: return
        val control = activeCamera.cameraControl
        val c2Control = camera2Control ?: Camera2CameraControl.from(control).also { camera2Control = it }

        // Apply Physical Hardware Zoom
        control.setZoomRatio(settings.physicalZoomRatio)

        val builder = CaptureRequestOptions.Builder()
        
        if (settings.physicalZoomRatio > 1.1f || settings.zoomRatio > 1.1f) {
            onStatus(String.format(java.util.Locale.US, "Zoom: %.1fx (Digital: %.1fx)", settings.physicalZoomRatio, settings.zoomRatio))
        }

        // Manual Exposure
        if (settings.iso > 0 || settings.shutterSpeedMs > 0) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            
            // Apply ISO if manual, otherwise default to a mid-range value (e.g., 400) or keep auto?
            // Usually, if we turn AE off, we must provide both. 
            // We'll use a sensible default (1/50s and ISO 400) if one is auto.
            val targetIso = if (settings.iso > 0) settings.iso else 400
            val targetShutterMs = if (settings.shutterSpeedMs > 0) settings.shutterSpeedMs else 20
            
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
            val exposureNs = (targetShutterMs.toLong() * 1_000_000L).coerceIn(100_000L, 1_000_000_000L)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            
            if (settings.iso > 0 && settings.shutterSpeedMs > 0) {
                onStatus("Manual Exposure: ISO $targetIso, ${targetShutterMs}ms")
            } else if (settings.iso > 0) {
                onStatus("Manual ISO: $targetIso")
            } else {
                onStatus("Manual Shutter: ${targetShutterMs}ms")
            }
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.exposureCompensation)
        }

        // Manual Focus
        if (!settings.focusAuto) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistanceDiopters)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }

        runCatching {
            c2Control.addCaptureRequestOptions(builder.build())
        }
    }

    private fun ImageProxy.toConstantResolutionJpeg(settings: BridgeSettings): ByteArray {
        val yBuffer = planes[0].buffer
        val vBuffer = planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        
        // 1. Calculate the base 16:9 crop area
        val targetAspect = 16f / 9f
        val sensorAspect = width.toFloat() / height.toFloat()
        
        val baseW = if (sensorAspect > targetAspect) height * targetAspect else width.toFloat()

        // 2. Calculate crop dimensions with grid-snapping
        val zoom = settings.zoomRatio.coerceAtLeast(1.0f)
        val cw = ((baseW / zoom).toInt() / 16) * 16
        val ch = (cw * 9) / 16
        
        // 3. Apply pan/tilt
        val maxScrollX = (width - cw) / 2
        val maxScrollY = (height - ch) / 2
        
        val offsetX = (settings.panX.coerceIn(-1f, 1f) * maxScrollX).toInt()
        val offsetY = (settings.panY.coerceIn(-1f, 1f) * maxScrollY).toInt()
        
        val left = (width - cw) / 2 + offsetX
        val top = (height - ch) / 2 + offsetY
        
        val cropRect = Rect(
            left.coerceIn(0, width - cw),
            top.coerceIn(0, height - ch),
            (left + cw).coerceIn(cw, width),
            (top + ch).coerceIn(ch, height)
        )

        val streamOutput = ByteArrayOutputStream()
        yuvImage.compressToJpeg(cropRect, settings.jpegQuality.coerceIn(20, 100), streamOutput)
        val jpegData = streamOutput.toByteArray()

        // 4. Force scaling if zoomed in, to keep MJPEG stream resolution constant for OBS
        if (zoom > 1.05f) {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            val scaled = Bitmap.createScaledBitmap(bitmap, settings.resolutionPreset.width, settings.resolutionPreset.height, true)
            val finalOutput = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality.coerceIn(20, 100), finalOutput)
            return finalOutput.toByteArray()
        }

        return jpegData
    }

    fun close() {
        runCatching { provider?.unbindAll() }
        runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
        runCatching { camera2Control = null }
        runCatching { analysisExecutor.shutdownNow() }
    }

    private fun configureInterop(builder: Preview.Builder, settings: BridgeSettings) {
        val extender = Camera2Interop.Extender(builder)
        applyCaptureRequestOptions(extender, settings)
    }

    private fun configureInterop(builder: ImageAnalysis.Builder, settings: BridgeSettings) {
        val extender = Camera2Interop.Extender(builder)
        applyCaptureRequestOptions(extender, settings)
    }

    private fun applyCaptureRequestOptions(
        extender: Camera2Interop.Extender<*>,
        settings: BridgeSettings,
    ) {
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(settings.frameRate, settings.frameRate),
        )
    }
}
