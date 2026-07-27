package com.raphael.androidwebcambridge.bridge

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLContext

class LocalBridgeServer(
    private val port: Int = 8787,
    sslContext: SSLContext? = null,
    private val onRemoteUpdate: (Map<String, String?>) -> Unit,
    private val onConnectionStatusChanged: (total: Int) -> Unit,
) {
    companion object {
        private const val TAG = "LocalBridge"
    }

    // ponytail: self-signed cert factory, user clicks through one-time browser warning
    private val listenerFactory = sslContext?.serverSocketFactory ?: ServerSocketFactory.getDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latestFrame = AtomicReference(ByteArray(0))
    private val currentState = AtomicReference(BridgeState())
    private val activeClients = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val audioClients = mutableListOf<BufferedOutputStream>()
    private var phoneAudioCallback: ((ByteArray) -> Unit)? = null

    fun setPhoneAudioCallback(cb: (ByteArray) -> Unit) {
        phoneAudioCallback = cb
    }

    // ponytail: shared audio bus, synchronized broadcast to all SSE clients
    private fun broadcastAudio(data: ByteArray) {
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        synchronized(audioClients) {
            val itr = audioClients.iterator()
            while (itr.hasNext()) {
                try {
                    itr.next().let { c ->
                        c.write("data:$b64\n\n".toByteArray())
                        c.flush()
                    }
                } catch (_: Exception) {
                    itr.remove()
                }
            }
        }
    }

    // phone-recorded audio → browser SSE clients
    fun broadcastPhoneAudio(data: ByteArray) {
        broadcastAudio(data)
    }

    fun updateState(state: BridgeState) {
        currentState.set(state)
    }

    fun submitFrame(frame: ByteArray) {
        latestFrame.set(frame)
    }

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob =
            scope.launch {
                try {
                    serverSocket = listenerFactory.createServerSocket(port) as ServerSocket
                    while (isActive) {
                        val socket = serverSocket?.accept() ?: break
                        launch { handleClient(socket) }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "server socket: ${error.message}")
                    currentState.get()?.copy(errorMessage = error.message, statusMessage = "Server failed")?.let {
                        currentState.set(it)
                    }
                }
            }
    }

    fun stop() {
        runCatching { serverSocket?.close() }.onFailure { Log.w(TAG, "stop: ${it.message}") }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        scope.cancel()
    }

    // ponytail: swallow SSL handshake failures from plain-HTTP clients hitting the SSL socket
    private suspend fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(BufferedInputStream(client.getInputStream()), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val requestParts = requestLine.split(Regex("\\s+"))
                if (requestParts.size < 2) return

                val pathWithQuery = requestParts[1]
                val uri = pathWithQuery.substringBefore("?")
                val query = parseQuery(pathWithQuery.substringAfter("?", ""))
                val output = BufferedOutputStream(client.getOutputStream())

                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isBlank()) break
                }

                when {
                    uri == "/" -> writeText(output, BridgeHtmlAssets.landingPage(), "text/html; charset=utf-8")
                    uri == "/dashboard" -> writeText(output, BridgeHtmlAssets.dashboardPage(), "text/html; charset=utf-8")
                    uri == "/obs-bridge" -> writeText(output, BridgeHtmlAssets.obsBridgePage(), "text/html; charset=utf-8")
                    uri == "/api/state" -> writeText(output, currentState.get().toJson().toString(), "application/json; charset=utf-8")
                    uri == "/api/tally" -> {
                        scope.launch { onRemoteUpdate(query) }
                        writeText(
                            output,
                            JSONObject().put("ok", true).put("message", "tally updated").toString(),
                            "application/json; charset=utf-8",
                        )
                    }
                    uri == "/api/settings" -> {
                        scope.launch { onRemoteUpdate(query) }
                        writeText(
                            output,
                            JSONObject().put("ok", true).put("message", "settings updated").toString(),
                            "application/json; charset=utf-8",
                        )
                    }
                    uri == "/api/audio/in" -> {
                        query["d"]?.let { raw ->
                            try {
                                val data = Base64.decode(raw, Base64.DEFAULT)
                                broadcastAudio(data)
                                phoneAudioCallback?.invoke(data)
                            } catch (e: Exception) {
                                Log.w(TAG, "audio/in: ${e.message}")
                            }
                        }
                        writeText(output, "{}", "application/json")
                    }
                    uri == "/api/audio/out" -> {
                        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        output.write("Content-Type: text/event-stream\r\n".toByteArray())
                        output.write("Cache-Control: no-cache\r\n\r\n".toByteArray())
                        output.flush()
                        synchronized(audioClients) { audioClients.add(output) }
                        try {
                            while (socket.isConnected && !socket.isClosed && scope.isActive) delay(500)
                        } catch (_: Exception) {
                        } finally {
                            synchronized(audioClients) { audioClients.remove(output) }
                        }
                    }
                    uri == "/stream.mjpg" || uri.startsWith("/stream") -> writeMjpeg(client, output)
                    else -> writeText(output, "Not found", "text/plain; charset=utf-8", code = 404)
                }
                // ponytail: graceful shutdown — FIN + delay so peer reads response before close() sends RST
                if (uri != "/stream.mjpg" && !uri.startsWith("/stream") && uri != "/api/audio/out") {
                    runCatching { client.shutdownOutput() }
                    delay(10)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleClient: ${e.message}")
        }
    }

    private suspend fun writeMjpeg(
        socket: Socket,
        output: BufferedOutputStream,
    ) {
        output.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n"
            ).toByteArray(),
        )
        output.flush()

        onConnectionStatusChanged(activeClients.incrementAndGet())

        try {
            while (socket.isConnected && !socket.isClosed && scope.isActive) {
                val frame = latestFrame.get()
                if (frame.isNotEmpty()) {
                    output.write("--frame\r\n".toByteArray())
                    output.write("Content-Type: image/jpeg\r\n".toByteArray())
                    output.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                    output.write(frame)
                    output.write("\r\n".toByteArray())
                    output.flush()
                    delay(16L)
                } else {
                    // ponytail: no frame — sleep longer, saves CPU vs 60Hz empty polling
                    delay(100L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mjpeg loop: ${e.message}")
        } finally {
            onConnectionStatusChanged(activeClients.decrementAndGet())
        }
    }

    private fun writeText(
        output: BufferedOutputStream,
        body: String,
        contentType: String,
        code: Int = 200,
    ) {
        val statusText =
            when (code) {
                200 -> "OK"
                404 -> "Not Found"
                else -> "Error"
            }
        val header =
            buildString {
                append("HTTP/1.1 ")
                append(code)
                append(" ")
                append(statusText)
                append("\r\n")
                append("Content-Type: ")
                append(contentType)
                append("\r\n")
                append("Content-Length: ")
                append(body.toByteArray(StandardCharsets.UTF_8).size)
                append("\r\n")
                append("Connection: close\r\n\r\n")
                append(body)
            }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun parseQuery(query: String): Map<String, String?> {
        if (query.isBlank()) return emptyMap()
        return query.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index < 0) return@mapNotNull null
                val key = URLDecoder.decode(part.substring(0, index), StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8.name())
                key to value
            }
            .toMap()
    }
}
