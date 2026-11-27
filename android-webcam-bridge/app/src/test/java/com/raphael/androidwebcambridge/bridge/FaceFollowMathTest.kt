package com.raphael.androidwebcambridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceFollowMathTest {
    @Test
    fun mapsCroppedDetectionIntoFullSourceCoordinates() {
        val crop = CropWindow(sourceWidth = 1000, sourceHeight = 1000, left = 200, top = 100, width = 400, height = 800)

        val face =
            FaceFollowMath.mapToSource(
                id = 1,
                centerX = 200f,
                centerY = 200f,
                boxWidth = 80f,
                boxHeight = 160f,
                frameWidth = 400,
                frameHeight = 800,
                rotationDegrees = 0,
                crop = crop,
            )

        assertEquals(0.4f, face.x, 0.001f)
        assertEquals(0.3f, face.y, 0.001f)
        assertEquals(0.08f, face.width, 0.001f)
        assertEquals(0.16f, face.height, 0.001f)
    }

    @Test
    fun sourcePositionDoesNotChangeWhenCropMoves() {
        val cropOne = CropWindow(sourceWidth = 1280, sourceHeight = 720, left = 200, top = 100, width = 848, height = 477)
        val cropTwo = CropWindow(sourceWidth = 1280, sourceHeight = 720, left = 300, top = 200, width = 848, height = 477)
        val sourceCenterX = 640f
        val sourceCenterY = 360f

        val faceInFirstCrop =
            FaceFollowMath.mapToSource(
                id = 1,
                centerX = sourceCenterX - cropOne.left,
                centerY = sourceCenterY - cropOne.top,
                boxWidth = 100f,
                boxHeight = 100f,
                frameWidth = cropOne.width,
                frameHeight = cropOne.height,
                rotationDegrees = 0,
                crop = cropOne,
            )
        val faceInSecondCrop =
            FaceFollowMath.mapToSource(
                id = 1,
                centerX = sourceCenterX - cropTwo.left,
                centerY = sourceCenterY - cropTwo.top,
                boxWidth = 100f,
                boxHeight = 100f,
                frameWidth = cropTwo.width,
                frameHeight = cropTwo.height,
                rotationDegrees = 0,
                crop = cropTwo,
            )

        assertEquals(faceInFirstCrop.x, faceInSecondCrop.x, 0.001f)
        assertEquals(faceInFirstCrop.y, faceInSecondCrop.y, 0.001f)
        val panInFirstCrop = FaceFollowMath.panForFace(faceInFirstCrop, cropOne)
        val panInSecondCrop = FaceFollowMath.panForFace(faceInSecondCrop, cropTwo)
        assertEquals(panInFirstCrop.x, panInSecondCrop.x, 0.001f)
        assertEquals(panInFirstCrop.y, panInSecondCrop.y, 0.001f)
    }

    @Test
    fun panCentersFaceInTheCurrentCropWindow() {
        val face = DetectedFace(id = 1, x = 0.6f, y = 0.55f, width = 0.1f, height = 0.1f)
        val crop = CropWindow(sourceWidth = 1000, sourceHeight = 1000, left = 200, top = 100, width = 400, height = 800)

        val pan = FaceFollowMath.panForFace(face, crop)

        assertEquals(1f / 3f, pan.x, 0.001f)
        assertEquals(0.5f, pan.y, 0.001f)
    }

    @Test
    fun mapsNinetyDegreeRotatedFaceBackToSourceCoordinates() {
        val crop = CropWindow(sourceWidth = 1000, sourceHeight = 800, left = 100, top = 50, width = 400, height = 200)

        val face =
            FaceFollowMath.mapToSource(
                id = 1,
                centerX = 50f,
                centerY = 300f,
                boxWidth = 20f,
                boxHeight = 40f,
                frameWidth = 400,
                frameHeight = 200,
                rotationDegrees = 90,
                crop = crop,
            )

        assertEquals(0.4f, face.x, 0.001f)
        assertEquals(0.25f, face.y, 0.001f)
    }

    @Test
    fun stationaryFaceDetectionJitterDoesNotMoveTheCrop() {
        val smoother = FaceFollowSmoother()
        var pan = FollowPan(0f, 0f)
        val jitter = listOf(0.02f, -0.02f, 0.015f, -0.015f, 0.01f, -0.01f)

        smoother.reset(pan)
        jitter.forEachIndexed { index, value ->
            pan = smoother.update(FollowPan(value, -value), (index + 1) * 33L, 0.7f)
        }

        assertEquals(0f, pan.x, 0.005f)
        assertEquals(0f, pan.y, 0.005f)
    }

    @Test
    fun isolatedDetectionSpikeDoesNotMoveTheCrop() {
        val smoother = FaceFollowSmoother()
        var pan = FollowPan(0f, 0f)

        smoother.reset(pan)
        pan = smoother.update(FollowPan(0f, 0f), 0L, 0.7f)
        pan = smoother.update(FollowPan(0f, 0f), 33L, 0.7f)
        pan = smoother.update(FollowPan(0.8f, -0.8f), 66L, 0.7f)
        pan = smoother.update(FollowPan(0f, 0f), 99L, 0.7f)

        assertEquals(0f, pan.x, 0.005f)
        assertEquals(0f, pan.y, 0.005f)
    }

    @Test
    fun sustainedMovementIsFollowedWithinHalfASecond() {
        val smoother = FaceFollowSmoother()
        var pan = FollowPan(0f, 0f)

        smoother.reset(pan)
        pan = smoother.update(FollowPan(0f, 0f), 0L, 0.7f)
        repeat(15) { frame ->
            val target = if (frame >= 4) FollowPan(0.8f, -0.8f) else FollowPan(0f, 0f)
            pan = smoother.update(target, (frame + 1) * 33L, 0.7f)
        }

        assertTrue(pan.x > 0.6f)
        assertTrue(pan.y < -0.6f)
    }

    @Test
    fun greaterFollowStrengthRespondsFaster() {
        val gentle = FaceFollowSmoother()
        val responsive = FaceFollowSmoother()
        var gentlePan = FollowPan(0f, 0f)
        var responsivePan = FollowPan(0f, 0f)

        gentle.reset(gentlePan)
        responsive.reset(responsivePan)
        gentlePan = gentle.update(FollowPan(0f, 0f), 0L, 0.2f)
        responsivePan = responsive.update(FollowPan(0f, 0f), 0L, 0.8f)
        repeat(15) { frame ->
            gentlePan = gentle.update(FollowPan(0.8f, 0f), (frame + 1) * 33L, 0.2f)
            responsivePan = responsive.update(FollowPan(0.8f, 0f), (frame + 1) * 33L, 0.8f)
        }

        assertTrue(responsivePan.x > gentlePan.x)
    }
}
