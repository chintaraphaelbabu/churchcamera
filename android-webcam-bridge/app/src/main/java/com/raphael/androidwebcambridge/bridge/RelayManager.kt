package com.raphael.androidwebcambridge.bridge

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class RelayManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDiscoveryStatus: (String) -> Unit = {},
) {
    var onTallyUpdate: ((TallyState) -> Unit)? = null

    private val prefs: SharedPreferences = context.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)
    private var relayHeartbeatJob: Job? = null
    private var tallyWs: WebSocket? = null
    private var tallyWsReconnectJob: Job? = null
    @Volatile
    private var tallyWsReconnectEnabled = true
    private val discovery = RelayDiscovery { relayUrl ->
        if (getRelayHost().isNullOrBlank()) {
            onDiscoveryStatus("Found: $relayUrl")
            setRelayHost(relayUrl)
        }
    }

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    fun setRelayHost(host: String) {
        val trimmedHost = normalizeRelayHost(host) ?: return
        val oldHost = getRelayHost()
        prefs.edit().putString("relay_host", trimmedHost).apply()
        discovery.stop()
        onDiscoveryStatus("Connecting: $trimmedHost")
        if (trimmedHost != oldHost) disconnectTallyWebSocket()
        scope.launch { registerWithRelay(trimmedHost, getRelaySourceName()) }
        startRelayHeartbeat()
    }

    fun setRelaySourceName(sourceName: String) {
        val trimmedSourceName = sourceName.trim()
        prefs.edit().putString("relay_source_name", trimmedSourceName).apply()
        scope.launch { registerWithRelay(getRelayHost(), trimmedSourceName) }
        startRelayHeartbeat()
    }

    fun refreshRelayRegistration() {
        scope.launch { registerWithRelay(getRelayHost(), getRelaySourceName()) }
        startRelayHeartbeat()
    }

    fun pauseRelayHeartbeat() {
        relayHeartbeatJob?.cancel()
        relayHeartbeatJob = null
        disconnectTallyWebSocket()
    }

    fun pingRelayNow() {
        scope.launch { sendRelayPing() }
    }

    fun startRelayHeartbeat() {
        if (relayHeartbeatJob?.isActive == true) return
        val host = normalizeRelayHost(getRelayHost()) ?: return

        relayHeartbeatJob = scope.launch {
            sendRelayPing()
            while (true) {
                delay(10_000L)
                sendRelayPing()
            }
        }
    }

    fun getRelayHost(): String? = prefs.getString("relay_host", null)
    private fun getRelaySourceName(): String? = prefs.getString("relay_source_name", null)
    private fun getRelayDeviceId(): String? = prefs.getString("relay_device_id", null)
    private fun saveRelayDeviceId(id: String?) = prefs.edit().putString("relay_device_id", id).apply()

    private fun getBatteryLevel(): Int = runCatching {
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }.getOrDefault(-1)

    private fun getCurrentSsid(): String = runCatching {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@runCatching ""
        wifi.connectionInfo.ssid?.trim('"') ?: ""
    }.getOrDefault("")

    private fun cacheRelayHost(ssid: String, host: String) {
        if (ssid.isBlank()) return
        prefs.edit().putString("relay_host_$ssid", host).apply()
    }

    private fun getCachedRelayHost(ssid: String): String? {
        if (ssid.isBlank()) return null
        return prefs.getString("relay_host_$ssid", null)?.takeIf { it.isNotBlank() }
    }

    fun trySsidCache(): Boolean {
        val ssid = getCurrentSsid()
        if (ssid.isBlank()) return false
        val cachedHost = getCachedRelayHost(ssid) ?: return false
        setRelayHost(cachedHost)
        return true
    }

    fun connectTallyWebSocket() {
        tallyWsReconnectEnabled = true
        val host = normalizeRelayHost(getRelayHost()) ?: return
        val deviceId = getRelayDeviceId() ?: return
        if (tallyWs != null) return
        val wsUrl = host.replace("http://", "ws://").replace("https://", "wss://").trimEnd('/') + "/tally"
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(wsUrl).build()
        tallyWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(JSONObject().put("type", "auth").put("deviceId", deviceId).toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "tally") {
                        val tallyStr = json.optString("tallyState")
                        val tally = runCatching { TallyState.valueOf(tallyStr) }.getOrDefault(TallyState.IDLE)
                        onTallyUpdate?.invoke(tally)
                    }
                } catch (_: Exception) {}
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                tallyWs = null
                scheduleWsReconnect()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                tallyWs = null
                scheduleWsReconnect()
            }
        })
    }

    fun disconnectTallyWebSocket() {
        tallyWsReconnectEnabled = false
        tallyWsReconnectJob?.cancel()
        tallyWsReconnectJob = null
        tallyWs?.close(1000, "shutdown")
        tallyWs = null
    }

    private fun scheduleWsReconnect() {
        if (!tallyWsReconnectEnabled) return
        if (tallyWsReconnectJob?.isActive == true) return
        val baseDelay = 1000L
        tallyWsReconnectJob = scope.launch {
            var attempt = 0
            while (isActive && getRelayDeviceId() != null) {
                delay(baseDelay shl attempt.coerceAtMost(5)) // exponential backoff 1s→32s
                if (tallyWs == null) connectTallyWebSocket()
                attempt++
            }
        }
    }
    private fun normalizeRelayHost(host: String?): String? {
        val trimmed = host?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
    }

    suspend fun registerWithRelay(hostOverride: String? = null, sourceNameOverride: String? = null) {
        val host = normalizeRelayHost(hostOverride) ?: normalizeRelayHost(getRelayHost()) ?: return
        val ip = findLocalIpv4Address()
        val callbackBase = "http://$ip:8787"
        val name = android.os.Build.MODEL ?: "Android Phone"
        val sourceName = sourceNameOverride?.trim()?.takeIf { it.isNotBlank() } ?: getRelaySourceName()?.takeIf { it.isNotBlank() } ?: name
        val existingId = getRelayDeviceId()
        val batteryLevel = getBatteryLevel()
        
        val payload = if (existingId.isNullOrBlank()) {
            "{\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\",\"batteryLevel\":$batteryLevel}"
        } else {
            "{\"id\":\"$existingId\",\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\",\"batteryLevel\":$batteryLevel}"
        }

        var lastError: String? = null
        for (attempt in 0 until 3) {
            var ok = false
            try {
                withContext(Dispatchers.IO) {
                    val url = URL(host.trimEnd('/') + "/api/register")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
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
                        startRelayHeartbeat()
                        connectTallyWebSocket()
                        conn.disconnect()
                        ok = true
                        return@withContext
                    }
                    lastError = "HTTP $code"
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                if (attempt < 2) delay(1500L)
            }
            if (ok) break
        }
        lastError?.let {
            if (hostOverride == null) {
                val ssid = getCurrentSsid()
                val cachedHost = getCachedRelayHost(ssid)
                if (cachedHost != null && normalizeRelayHost(cachedHost) != host) {
                    setRelayHost(cachedHost)
                    return
                }
            }
            onDiscoveryStatus("Register failed: $it")
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
                val url = URL(relayHost.trimEnd('/') + "/api/ping")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val batteryLevel = getBatteryLevel()
                val payload = if (deviceId.isNullOrBlank()) {
                    "{\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\",\"batteryLevel\":$batteryLevel}"
                } else {
                    "{\"id\":\"$deviceId\",\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\",\"batteryLevel\":$batteryLevel}"
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            }
            cacheRelayHost(getCurrentSsid(), relayHost)
        } catch (_: Exception) { }
    }
}
