package com.raphael.androidwebcambridge.bridge

import kotlin.math.PI
import kotlin.math.abs

internal data class CropWindow(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal data class FaceDetectionResult(
    val faces: List<DetectedFace>,
    val crop: CropWindow,
)

internal data class FollowPan(
    val x: Float,
    val y: Float,
)

internal object FaceFollowMath {
    fun mapToSource(
        id: Int,
        centerX: Float,
        centerY: Float,
        boxWidth: Float,
        boxHeight: Float,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        crop: CropWindow,
    ): DetectedFace {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val rotatedWidth = if (rotation % 180 != 0) frameHeight else frameWidth
        val rotatedHeight = if (rotation % 180 != 0) frameWidth else frameHeight
        val x = centerX / rotatedWidth
        val y = centerY / rotatedHeight
        val (cropX, cropY) =
            when (rotation) {
                0 -> x to y
                90 -> y to (1f - x)
                180 -> (1f - x) to (1f - y)
                270 -> (1f - y) to x
                else -> throw IllegalArgumentException("Unsupported image rotation: $rotation")
            }
        val widthInCrop = if (rotation % 180 != 0) boxHeight / rotatedHeight else boxWidth / rotatedWidth
        val heightInCrop = if (rotation % 180 != 0) boxWidth / rotatedWidth else boxHeight / rotatedHeight

        return DetectedFace(
            id = id,
            x = (crop.left + cropX * crop.width) / crop.sourceWidth,
            y = (crop.top + cropY * crop.height) / crop.sourceHeight,
            width = widthInCrop * crop.width / crop.sourceWidth,
            height = heightInCrop * crop.height / crop.sourceHeight,
        )
    }

    fun panForFace(
        face: DetectedFace,
        crop: CropWindow,
    ): FollowPan {
        val maxScrollX = (crop.sourceWidth - crop.width) / 2f
        val maxScrollY = (crop.sourceHeight - crop.height) / 2f
        return FollowPan(
            x = if (maxScrollX <= 0f) 0f else ((face.x * crop.sourceWidth - crop.sourceWidth / 2f) / maxScrollX).coerceIn(-1f, 1f),
            y = if (maxScrollY <= 0f) 0f else ((face.y * crop.sourceHeight - crop.sourceHeight / 2f) / maxScrollY).coerceIn(-1f, 1f),
        )
    }
}

internal class FaceFollowSmoother {
    private val recentX = mutableListOf<Float>()
    private val recentY = mutableListOf<Float>()
    private var position = FollowPan(0f, 0f)
    private var previousTarget = FollowPan(0f, 0f)
    private var filteredVelocity = FollowPan(0f, 0f)
    private var lastTimestampMs: Long? = null

    @Synchronized
    fun reset(current: FollowPan) {
        position = current
        recentX.clear()
        recentY.clear()
        recentX.add(current.x)
        recentY.add(current.y)
        previousTarget = current
        filteredVelocity = FollowPan(0f, 0f)
        lastTimestampMs = null
    }

    @Synchronized
    fun hold(): FollowPan {
        recentX.clear()
        recentY.clear()
        recentX.add(position.x)
        recentY.add(position.y)
        previousTarget = position
        filteredVelocity = FollowPan(0f, 0f)
        lastTimestampMs = null
        return position
    }

    @Synchronized
    fun update(
        target: FollowPan,
        timestampMs: Long,
        strength: Float,
    ): FollowPan {
        val lastTimestamp = lastTimestampMs
        if (lastTimestamp == null || timestampMs <= lastTimestamp || timestampMs - lastTimestamp > MAX_GAP_MS) {
            seed(target, timestampMs)
            return position
        }

        val dt = ((timestampMs - lastTimestamp) / 1000f).coerceIn(MIN_FRAME_SECONDS, MAX_FRAME_SECONDS)
        val stableTarget = FollowPan(median(recentX, target.x), median(recentY, target.y))
        val responsiveness = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        val x = filterAxis(position.x, stableTarget.x, previousTarget.x, filteredVelocity.x, dt, responsiveness)
        val y = filterAxis(position.y, stableTarget.y, previousTarget.y, filteredVelocity.y, dt, responsiveness)

        previousTarget = stableTarget
        filteredVelocity = FollowPan(x.velocity, y.velocity)
        lastTimestampMs = timestampMs
        position = FollowPan(x.position, y.position)
        return position
    }

    private fun seed(
        target: FollowPan,
        timestampMs: Long,
    ) {
        recentX.clear()
        recentY.clear()
        recentX.add(target.x)
        recentY.add(target.y)
        recentX.add(target.x)
        recentY.add(target.y)
        previousTarget = target
        filteredVelocity = FollowPan(0f, 0f)
        lastTimestampMs = timestampMs
    }

    private fun median(
        samples: MutableList<Float>,
        nextValue: Float,
    ): Float {
        samples.add(nextValue)
        if (samples.size > MEDIAN_WINDOW_SIZE) samples.removeAt(0)
        return samples.sorted()[samples.size / 2]
    }

    private fun filterAxis(
        current: Float,
        target: Float,
        previousTarget: Float,
        previousVelocity: Float,
        dt: Float,
        strength: Float,
    ): AxisResult {
        val error = target - current
        if (abs(error) <= DEAD_BAND) {
            val derivativeAlpha = alpha(dt, DERIVATIVE_CUTOFF)
            return AxisResult(current, previousVelocity * (1f - derivativeAlpha))
        }

        val rawVelocity = (target - previousTarget) / dt
        val derivativeAlpha = alpha(dt, DERIVATIVE_CUTOFF)
        val velocity = previousVelocity + (rawVelocity - previousVelocity) * derivativeAlpha
        val cutoff = MIN_CUTOFF + VELOCITY_BETA * strength * abs(velocity)
        val response = error * alpha(dt, cutoff)
        val maxStep = (MIN_PAN_SPEED + MAX_EXTRA_PAN_SPEED * strength) * dt
        val movement = response.coerceIn(-maxStep, maxStep)
        return AxisResult((current + movement).coerceIn(-1f, 1f), velocity)
    }

    private fun alpha(
        dt: Float,
        cutoff: Float,
    ): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return dt / (dt + tau)
    }

    private data class AxisResult(
        val position: Float,
        val velocity: Float,
    )

    private companion object {
        const val DEAD_BAND = 0.035f
        const val DERIVATIVE_CUTOFF = 1f
        const val MAX_EXTRA_PAN_SPEED = 2f
        const val MAX_FRAME_SECONDS = 0.5f
        const val MAX_GAP_MS = 500L
        const val MAX_STRENGTH = 1f
        const val MEDIAN_WINDOW_SIZE = 3
        const val MIN_CUTOFF = 0.35f
        const val MIN_FRAME_SECONDS = 1f / 60f
        const val MIN_PAN_SPEED = 0.6f
        const val MIN_STRENGTH = 0.1f
        const val VELOCITY_BETA = 2.5f
    }
}
