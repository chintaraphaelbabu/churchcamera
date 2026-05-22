package com.raphael.androidwebcambridge.bridge

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class RelayManager(private val context: Context, private val scope: CoroutineScope) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)
    private var relayHeartbeatJob: Job? = null

    fun setRelayHost(host: String) {
        val trimmedHost = normalizeRelayHost(host) ?: return
        prefs.edit().putString("relay_host", trimmedHost).apply()
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

    private fun getRelayHost(): String? = prefs.getString("relay_host", null)
    private fun getRelaySourceName(): String? = prefs.getString("relay_source_name", null)
    private fun getRelayDeviceId(): String? = prefs.getString("relay_device_id", null)
    private fun saveRelayDeviceId(id: String?) = prefs.edit().putString("relay_device_id", id).apply()

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
        
        val payload = if (existingId.isNullOrBlank()) {
            "{\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\"}"
        } else {
            "{\"id\":\"$existingId\",\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\"}"
        }

        repeat(3) { attempt ->
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
                        conn.disconnect()
                        return@withContext
                    }
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (attempt < 2) delay(1500L)
            }
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
                val payload = if (deviceId.isNullOrBlank()) {
                    "{\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\"}"
                } else {
                    "{\"id\":\"$deviceId\",\"name\":\"$name\",\"sourceName\":\"$sourceName\",\"url\":\"$callbackBase\"}"
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            }
        } catch (_: Exception) { }
    }
}
