package com.raphael.androidwebcambridge.bridge

import org.json.JSONArray
import org.json.JSONObject

enum class TallyState {
    IDLE,
    PREVIEW,
    PROGRAM,
}

enum class ResolutionPreset(val label: String, val width: Int, val height: Int) {
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080),
    P1440("1440p", 2560, 1440),
    P4K("4k", 3840, 2160),
}

data class LensInfo(
    val cameraId: String,
    val label: String,
    val facing: Int,
    val megapixels: Int,
    val focalLengthMm: Float,
    val aperture: Float?,
    val maxPhysicalZoom: Float = 1.0f,
) {
    fun shortDisplayName(): String = "$label ($megapixels MP)"

    fun toJson(): JSONObject =
        JSONObject()
            .put("cameraId", cameraId)
            .put("label", label)
            .put("facing", facing)
            .put("megapixels", megapixels)
            .put("focalLengthMm", focalLengthMm)
            .put("aperture", aperture ?: JSONObject.NULL)
}

data class BridgeSettings(
    val selectedCameraId: String? = null,
    val physicalZoomRatio: Float = 1f,
    val zoomRatio: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val faceFollowEnabled: Boolean = false,
    val selectedFaceId: Int? = null,
    val exposureCompensation: Int = 0,
    val iso: Int = 0,
    val shutterSpeedMs: Int = 0,
    val whiteBalanceKelvin: Int = 0,
    val focusDistanceDiopters: Float = 0f,
    val focusAuto: Boolean = true,
    val frameRate: Int = 24,
    val resolutionPreset: ResolutionPreset = ResolutionPreset.P720,
    val jpegQuality: Int = 60, // ponytail: 60 still looks fine on OBS, halves bandwidth vs 95
    val bitrateMbps: Int = 4,
    val focusVelocity: Float = 0.1f,
    val zoomVelocity: Float = 0.1f,
    val screenDimEnabled: Boolean = false,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("selectedCameraId", selectedCameraId ?: JSONObject.NULL)
            .put("physicalZoomRatio", physicalZoomRatio)
            .put("zoomRatio", zoomRatio)
            .put("panX", panX)
            .put("panY", panY)
            .put("faceFollowEnabled", faceFollowEnabled)
            .put("selectedFaceId", selectedFaceId ?: JSONObject.NULL)
            .put("exposureCompensation", exposureCompensation)
            .put("iso", iso)
            .put("shutterSpeedMs", shutterSpeedMs)
            .put("whiteBalanceKelvin", whiteBalanceKelvin)
            .put("focusDistanceDiopters", focusDistanceDiopters)
            .put("focusAuto", focusAuto)
            .put("frameRate", frameRate)
            .put("resolutionPreset", resolutionPreset.name)
            .put("jpegQuality", jpegQuality)
            .put("bitrateMbps", bitrateMbps)
            .put("focusVelocity", focusVelocity)
            .put("zoomVelocity", zoomVelocity)
            .put("screenDimEnabled", screenDimEnabled)

    companion object {
        fun fromQuery(
            query: Map<String, String?>,
            current: BridgeSettings,
        ): BridgeSettings {
            fun <T> read(
                name: String,
                parser: (String) -> T,
                fallback: T,
            ): T {
                val raw = query[name] ?: return fallback
                return runCatching { parser(raw) }.getOrElse { fallback }
            }

            return current.copy(
                selectedCameraId = query["selectedCameraId"] ?: current.selectedCameraId,
                physicalZoomRatio = read("physicalZoomRatio", String::toFloat, current.physicalZoomRatio),
                zoomRatio = read("zoomRatio", String::toFloat, current.zoomRatio),
                panX = read("panX", String::toFloat, current.panX),
                panY = read("panY", String::toFloat, current.panY),
                faceFollowEnabled = query["faceFollowEnabled"]?.let { it == "true" || it == "1" } ?: current.faceFollowEnabled,
                selectedFaceId = read("selectedFaceId", String::toInt, current.selectedFaceId ?: -1).let { if (it == -1) null else it },
                exposureCompensation = read("exposureCompensation", String::toInt, current.exposureCompensation),
                iso = read("iso", String::toInt, current.iso),
                shutterSpeedMs = read("shutterSpeedMs", String::toInt, current.shutterSpeedMs),
                whiteBalanceKelvin = read("whiteBalanceKelvin", String::toInt, current.whiteBalanceKelvin),
                focusDistanceDiopters = read("focusDistanceDiopters", String::toFloat, current.focusDistanceDiopters),
                focusAuto =
                    query["focusAuto"]?.let {
                        it.lowercase() == "true" || it == "1"
                    } ?: current.focusAuto,
                frameRate = read("frameRate", String::toInt, current.frameRate),
                resolutionPreset = read("resolutionPreset", ResolutionPreset::valueOf, current.resolutionPreset),
                jpegQuality = read("jpegQuality", String::toInt, current.jpegQuality),
                bitrateMbps = read("bitrateMbps", String::toInt, current.bitrateMbps),
                focusVelocity = read("focusVelocity", String::toFloat, current.focusVelocity),
                zoomVelocity = read("zoomVelocity", String::toFloat, current.zoomVelocity),
                screenDimEnabled = query["screenDimEnabled"]?.let { it == "true" || it == "1" } ?: current.screenDimEnabled,
            )
        }
    }
}

data class DetectedFace(
    val id: Int,
    val x: Float, // Normalized 0..1
    val y: Float, // Normalized 0..1
    val width: Float,
    val height: Float,
) {
    fun toJson(): JSONObject =
        JSONObject()
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
    val relayHost: String = "",
    val relaySourceName: String = "",
    val lensDisplayName: String = "",
    val availableLenses: List<LensInfo> = emptyList(),
    val activeRail: RailType? = null,
    val relayDiscoveryStatus: String = "",
    val batteryLevel: Int = -1,
    val operatorSpeaking: Boolean = false,
    val maxPhysicalZoom: Float = 3.0f, // ponytail: safe default, updated when lens selected
) {
    enum class RailType { FOCUS, ZOOM, ISO, SHUTTER }

    fun toJson(): JSONObject =
        JSONObject()
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
            .put("availableLenses", JSONArray(availableLenses.map { it.toJson() }))
            .put("relayHost", relayHost)
            .put("relaySourceName", relaySourceName)
            .put("relayDiscoveryStatus", relayDiscoveryStatus)
            .put("batteryLevel", batteryLevel)
            .put("operatorSpeaking", operatorSpeaking)
}

// ponytail: camera-style 1/<value> format
fun formatShutter(ms: Int): String = if (ms == 0) "AUTO" else "1/$ms"

fun dashboardPath(): String = "/dashboard"
