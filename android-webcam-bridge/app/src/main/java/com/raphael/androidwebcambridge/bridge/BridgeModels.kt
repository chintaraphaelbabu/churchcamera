package com.raphael.androidwebcambridge.bridge

import org.json.JSONArray
import org.json.JSONObject

enum class LensFacingOption(val label: String) {
    BACK("Back camera"),
    FRONT("Front camera"),
}

enum class AiLensHint(val label: String) {
    BALANCED("Balanced"),
    FACE("Face framing"),
    LOW_LIGHT("Low light"),
    SHARPNESS("Sharpness"),
}

enum class TallyState {
    IDLE,
    PREVIEW,
    PROGRAM
}

enum class ResolutionPreset(val label: String, val width: Int, val height: Int) {
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080),
    P1440("1440p", 2560, 1440),
    P4K("4k", 3840, 2160),
}

data class BridgeSettings(
    val lensFacing: LensFacingOption = LensFacingOption.BACK,
    val aiHint: AiLensHint = AiLensHint.BALANCED,
    val physicalZoomRatio: Float = 1f,
    val zoomRatio: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val faceFollowEnabled: Boolean = false,
    val selectedFaceId: Int? = null,
    val exposureCompensation: Int = 0,
    val iso: Int = 0,
    val shutterSpeedMs: Int = 0,
    val focusDistanceDiopters: Float = 0f,
    val focusAuto: Boolean = true,
    val frameRate: Int = 24,
    val resolutionPreset: ResolutionPreset = ResolutionPreset.P720,
    val jpegQuality: Int = 72,
    val bitrateMbps: Int = 4,
    val focusVelocity: Float = 0.1f,
    val zoomVelocity: Float = 0.1f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("lensFacing", lensFacing.name)
        .put("aiHint", aiHint.name)
        .put("physicalZoomRatio", physicalZoomRatio)
        .put("zoomRatio", zoomRatio)
        .put("panX", panX)
        .put("panY", panY)
        .put("faceFollowEnabled", faceFollowEnabled)
        .put("selectedFaceId", selectedFaceId ?: JSONObject.NULL)
        .put("exposureCompensation", exposureCompensation)
        .put("iso", iso)
        .put("shutterSpeedMs", shutterSpeedMs)
        .put("focusDistanceDiopters", focusDistanceDiopters)
        .put("focusAuto", focusAuto)
        .put("frameRate", frameRate)
        .put("resolutionPreset", resolutionPreset.name)
        .put("jpegQuality", jpegQuality)
        .put("bitrateMbps", bitrateMbps)
        .put("focusVelocity", focusVelocity)
        .put("zoomVelocity", zoomVelocity)

    companion object {
        fun fromQuery(query: Map<String, String?>, current: BridgeSettings): BridgeSettings {
            fun <T> read(name: String, parser: (String) -> T, fallback: T): T {
                val raw = query[name] ?: return fallback
                return runCatching { parser(raw) }.getOrElse { fallback }
            }

            return current.copy(
                lensFacing = read("lensFacing", LensFacingOption::valueOf, current.lensFacing),
                aiHint = read("aiHint", AiLensHint::valueOf, current.aiHint),
                physicalZoomRatio = read("physicalZoomRatio", String::toFloat, current.physicalZoomRatio),
                zoomRatio = read("zoomRatio", String::toFloat, current.zoomRatio),
                panX = read("panX", String::toFloat, current.panX),
                panY = read("panY", String::toFloat, current.panY),
                faceFollowEnabled = query["faceFollowEnabled"]?.let { it == "true" || it == "1" } ?: current.faceFollowEnabled,
                selectedFaceId = read("selectedFaceId", String::toInt, current.selectedFaceId ?: -1).let { if (it == -1) null else it },
                exposureCompensation = read("exposureCompensation", String::toInt, current.exposureCompensation),
                iso = read("iso", String::toInt, current.iso),
                shutterSpeedMs = read("shutterSpeedMs", String::toInt, current.shutterSpeedMs),
                focusDistanceDiopters = read("focusDistanceDiopters", String::toFloat, current.focusDistanceDiopters),
                focusAuto = query["focusAuto"]?.let { 
                    it.lowercase() == "true" || it == "1" 
                } ?: current.focusAuto,
                frameRate = read("frameRate", String::toInt, current.frameRate),
                resolutionPreset = read("resolutionPreset", ResolutionPreset::valueOf, current.resolutionPreset),
                jpegQuality = read("jpegQuality", String::toInt, current.jpegQuality),
                bitrateMbps = read("bitrateMbps", String::toInt, current.bitrateMbps),
                focusVelocity = read("focusVelocity", String::toFloat, current.focusVelocity),
                zoomVelocity = read("zoomVelocity", String::toFloat, current.zoomVelocity),
            )
        }
    }
}

data class DetectedFace(
    val id: Int,
    val x: Float, // Normalized 0..1
    val y: Float, // Normalized 0..1
    val width: Float,
    val height: Float
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("x", x)
        .put("y", y)
        .put("width", width)
        .put("height", height)
}

data class BridgeState(
    val serverRunning: Boolean = false,
    val streaming: Boolean = false,
    val connectedClients: Int = 0,
    val lastFrameAt: Long? = null,
    val statusMessage: String = "Starting",
    val errorMessage: String? = null,
    val dashboardUrl: String = "",
    val streamUrl: String = "",
    val settings: BridgeSettings = BridgeSettings(),
    val detectedFaces: List<DetectedFace> = emptyList(),
    val selectedFaceId: Int? = null,
    val cameraReady: Boolean = false,
    val cameraStatus: String = "Waiting for preview",
    val obsActive: Boolean = false,
    val tallyState: TallyState = TallyState.IDLE,
    val cameraRebindToken: Int = 0,
    val localIpAddress: String = "",
    val activeRail: RailType? = null,
) {
    enum class RailType { FOCUS, ZOOM, ISO, SHUTTER }
    fun toJson(): JSONObject = JSONObject()
        .put("serverRunning", serverRunning)
        .put("streaming", streaming)
        .put("connectedClients", connectedClients)
        .put("lastFrameAt", lastFrameAt ?: JSONObject.NULL)
        .put("statusMessage", statusMessage)
        .put("errorMessage", errorMessage ?: JSONObject.NULL)
        .put("dashboardUrl", dashboardUrl)
        .put("streamUrl", streamUrl)
        .put("settings", settings.toJson())
        .put("detectedFaces", JSONArray(detectedFaces.map { it.toJson() }))
        .put("selectedFaceId", selectedFaceId ?: JSONObject.NULL)
        .put("cameraReady", cameraReady)
        .put("cameraStatus", cameraStatus)
        .put("obsActive", obsActive)
        .put("tallyState", tallyState.name)
        .put("cameraRebindToken", cameraRebindToken)
        .put("localIpAddress", localIpAddress)
}

fun aiHintToLensFacing(hint: AiLensHint): LensFacingOption {
    return when (hint) {
        AiLensHint.FACE -> LensFacingOption.FRONT
        AiLensHint.LOW_LIGHT, AiLensHint.SHARPNESS, AiLensHint.BALANCED -> LensFacingOption.BACK
    }
}

fun streamPath(): String = "/stream.mjpg"

fun dashboardPath(): String = "/dashboard"
