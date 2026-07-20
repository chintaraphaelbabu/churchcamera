package com.raphael.androidwebcambridge.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

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

    @Volatile
    var focusPeakingEnabled: Boolean = false

    @Volatile
    var focusPeakingBitmap: Bitmap? = null

    @Volatile
    private var activeCamera = true

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        settings: BridgeSettings,
        onFrame: (ByteArray) -> Unit,
        onFacesDetected: (List<DetectedFace>) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        activeCamera = true
        activeSettings = settings
        provider = cameraProviderFuture.get()
        
        val targetResolution = Size(settings.resolutionPreset.width, settings.resolutionPreset.height)

        val previewBuilder = Preview.Builder().setTargetResolution(targetResolution)
        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        // ponytail: inline extender setup instead of two overloads
        Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(settings.frameRate, settings.frameRate))
        Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(settings.frameRate, settings.frameRate))
        // ponytail: AWB mode via Extender (highest priority) so CameraX can't override
        val awbMode = if (settings.whiteBalanceKelvin > 0) CaptureRequest.CONTROL_AWB_MODE_OFF else CaptureRequest.CONTROL_AWB_MODE_AUTO
        Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)

        val preview = previewBuilder.build()
        val analysis = analysisBuilder.build()

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            processImageProxy(imageProxy, onFrame, onFacesDetected)
        }

        provider?.unbindAll()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val selector = if (settings.selectedCameraId != null) {
            CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { Camera2CameraInfo.from(it).cameraId == settings.selectedCameraId }
                }
                .build()
        } else {
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
        }

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
        val mediaImage = imageProxy.image
        if (mediaImage != null && activeCamera) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (activeCamera) {
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
                .addOnFailureListener { /* ponytail: face detection failed silently, skip this frame */ }
                .addOnCompleteListener { finishFrame(imageProxy, onFrame) }
        } else {
            finishFrame(imageProxy, onFrame)
        }
    }

    private fun finishFrame(imageProxy: ImageProxy, onFrame: (ByteArray) -> Unit) {
        try {
            if (focusPeakingEnabled) {
                focusPeakingBitmap = computeFocusPeaking(imageProxy)
            }
            val jpeg = imageProxy.toConstantResolutionJpeg(activeSettings)
            onFrame(jpeg)
        } catch (_: Exception) {
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

        // White Balance
        // ponytail: Kelvin→RGB gains via Planckian approximation, sensor-specific but good enough
        if (settings.whiteBalanceKelvin > 0) {
            // ponytail: TRANSFORM_MATRIX forces HAL to use our gains instead of its own FAST/HQ mode
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            val g = kelvinToRggbGains(settings.whiteBalanceKelvin)
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g[0], g[1], g[2], g[3]))
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        // Manual Focus
        if (!settings.focusAuto) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            // ponytail: 0f = "don't override, just lock at current position"
            if (settings.focusDistanceDiopters > 0f) {
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistanceDiopters)
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }

        // ponytail: set (not add) forces CameraX to rebuild repeating request on every call
        runCatching {
            c2Control.setCaptureRequestOptions(builder.build())
        }
    }

    private fun kelvinToRggbGains(kelvin: Int): FloatArray {
        val t = kelvin.coerceIn(2500, 10000) / 100f
        val (rT, gT, bT) = if (t <= 66f) {
            Triple(255.0, 99.4708025861 * ln(t) - 161.1195681661,
                if (t <= 19) 0.0 else 138.5177312231 * ln(t - 10) - 305.0447927307)
        } else {
            Triple(329.698727446 * (t - 60f).pow(-0.1332047592f),
                288.1221695283 * (t - 60f).pow(-0.0755148492f), 255.0)
        }
        val d65r = 255.0; val d65g = 99.4708025861 * ln(65.0) - 161.1195681661; val d65b = 138.5177312231 * ln(55.0) - 305.0447927307
        return floatArrayOf(
            (d65r / rT).toFloat().coerceIn(0.1f, 10f),
            (d65g / gT).toFloat().coerceIn(0.1f, 10f),
            (d65g / gT).toFloat().coerceIn(0.1f, 10f),
            (d65b / bT).toFloat().coerceIn(0.1f, 10f)
        )
    }

    private fun computeFocusPeaking(image: ImageProxy): Bitmap? {
        val plane = image.planes[0]
        plane.buffer.rewind()
        val imgW = image.width; val imgH = image.height; val rowStride = plane.rowStride; val pxStride = plane.pixelStride
        val step = maxOf(1, imgW / 160, imgH / 90)
        val outW = imgW / step; val outH = imgH / step
        if (outW < 3 || outH < 3) return null
        val ys = IntArray(outW * outH)
        val row = ByteArray(rowStride)
        for (sy in 0 until outH) {
            plane.buffer.position(sy * step * rowStride)
            plane.buffer.get(row, 0, rowStride)
            for (sx in 0 until outW) ys[sy * outW + sx] = row[sx * step * pxStride].toInt() and 0xFF
        }
        val pixels = IntArray(outW * outH)
        for (y in 1 until outH - 1) {
            for (x in 1 until outW - 1) {
                val p5 = ys[y * outW + x]
                val edge = abs(p5 - ys[y * outW + x - 1]) + abs(p5 - ys[y * outW + x + 1]) +
                    abs(p5 - ys[(y - 1) * outW + x]) + abs(p5 - ys[(y + 1) * outW + x])
                if (edge > 80) pixels[y * outW + x] = Color.argb(0x99, 0xFF, 0x00, 0x00)
            }
        }
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    private fun ImageProxy.toConstantResolutionJpeg(settings: BridgeSettings): ByteArray {
        val yBuffer = planes[0].buffer.apply { rewind() }
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

    fun focusAt(x: Float, y: Float, factory: MeteringPointFactory, onComplete: () -> Unit) {
        try {
            // ponytail: re-enable AF before metering in case previous tap left it OFF
            camera2Control?.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    .build()
            )
            val point = factory.createPoint(x, y)
            val future = camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
            future?.addListener(onComplete, analysisExecutor)
        } catch (_: Exception) {}
    }

    fun close() {
        activeCamera = false
        try {
            provider?.unbindAll()
            camera?.cameraControl?.cancelFocusAndMetering()
        } catch (_: Exception) {}
        camera2Control = null
        analysisExecutor.shutdownNow()
    }

    companion object {
        fun discoverCameras(context: Context): List<LensInfo> {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val raw = manager.cameraIdList.map { id -> id to manager.getCameraCharacteristics(id) }
            val front = raw.filter { (_, c) -> c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            val back = raw.filter { (_, c) -> c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT }
            val sortedBack = back.sortedBy { (_, c) -> c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f }

            fun build(cameraId: String, chars: CameraCharacteristics, label: String): LensInfo {
                val pixelSize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val mp = if (pixelSize != null) (pixelSize.width * pixelSize.height) / 1_000_000 else 0
                val fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                val ap = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
                return LensInfo(cameraId, label, chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK, mp, fl, ap)
            }

            val lenses = mutableListOf<LensInfo>()
            sortedBack.forEachIndexed { i, (id, chars) ->
                val label = when {
                    sortedBack.size == 1 -> "Main"
                    i == 0 -> "Ultra Wide"
                    i == sortedBack.size - 1 -> "Telephoto"
                    else -> "Main"
                }
                lenses.add(build(id, chars, label))
            }
            front.forEach { (id, chars) -> lenses.add(build(id, chars, "Front")) }
            return lenses
        }
    }

}
