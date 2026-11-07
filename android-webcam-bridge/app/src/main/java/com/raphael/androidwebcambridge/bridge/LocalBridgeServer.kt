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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLContext

class LocalBridgeServer(
    private val port: Int = 8787,
    private val securePort: Int = 8788,
    sslContext: SSLContext? = null,
    private val onRemoteUpdate: (Map<String, String?>) -> Unit,
    private val onConnectionStatusChanged: (total: Int) -> Unit,
) {
    companion object {
        private const val TAG = "LocalBridge"
    }

    // ponytail: self-signed cert factory, user clicks through one-time browser warning
    private val listenerFactory = ServerSocketFactory.getDefault()
    private val secureListenerFactory = sslContext?.serverSocketFactory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latestFrame = AtomicReference(ByteArray(0))

    // ponytail: bumped on every submitFrame so clients only resend when a new frame lands
    private val frameSequence = AtomicLong(0L)
    private val currentState = AtomicReference(BridgeState())
    private val activeClients = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    private var secureServerSocket: ServerSocket? = null
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
        frameSequence.incrementAndGet()
    }

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob =
            scope.launch {
                try {
                    serverSocket = listenerFactory.createServerSocket(port) as ServerSocket
                    launch { acceptClients(serverSocket) }
                    try {
                        secureServerSocket = secureListenerFactory?.createServerSocket(securePort) as? ServerSocket
                        acceptClients(secureServerSocket)
                    } catch (error: Exception) {
                        Log.w(TAG, "secure server socket: ${error.message}")
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
        runCatching { secureServerSocket?.close() }.onFailure { Log.w(TAG, "secure stop: ${it.message}") }
        serverSocket = null
        secureServerSocket = null
        serverJob?.cancel()
        serverJob = null
        scope.cancel()
    }

    private suspend fun acceptClients(listener: ServerSocket?) {
        while (scope.isActive && listener != null) {
            val socket = listener.accept()
            scope.launch { handleClient(socket) }
        }
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

        // ponytail: only send once per new frame, skip repeats, drop frames if client lags
        var lastSentSequence = -1L
        try {
            while (socket.isConnected && !socket.isClosed && scope.isActive) {
                val sequence = frameSequence.get()
                if (sequence == lastSentSequence) {
                    // ponytail: no new frame — brief poll, no write
                    delay(2L)
                    continue
                }
                lastSentSequence = sequence
                val frame = latestFrame.get()
                if (frame.isNotEmpty()) {
                    output.write("--frame\r\n".toByteArray())
                    output.write("Content-Type: image/jpeg\r\n".toByteArray())
                    output.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                    output.write(frame)
                    output.write("\r\n".toByteArray())
                    output.flush()
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

    private suspend fun handleWebSocketStream(
        socket: Socket,
        output: BufferedOutputStream,
        client: Socket,
    ) {
        try {
            // Read WebSocket handshake headers to find Sec-WebSocket-Key
            val reader = BufferedReader(InputStreamReader(BufferedInputStream(client.getInputStream()), StandardCharsets.UTF_8))
            var wsKey = ""
            val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val headerName = line.substring(0, colonIndex).trim()
                    if (headerName.equals("Sec-WebSocket-Key", ignoreCase = true)) {
                        wsKey = line.substring(colonIndex + 1).trim()
                    }
                }
            }
            val acceptHash = java.util.Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-1").digest(
                    (wsKey + guid).toByteArray(Charsets.UTF_8)
                )
            )
            val handshakeResponse = (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptHash\r\n" +
                    "\r\n"
                )
            output.write(handshakeResponse.toByteArray(StandardCharsets.UTF_8))
            output.flush()

            onConnectionStatusChanged(activeClients.incrementAndGet())
            try {
                val inputStream = client.getInputStream()
                while (socket.isConnected && !socket.isClosed && scope.isActive) {
                    val frame = latestFrame.get()
                    if (frame.isNotEmpty()) {
                        val payload = frame
                        val len = payload.size
                        val header = ByteArray(2 + if (len < 126) 1 else 2)
                        header[0] = 0x82.toByte()
                        if (len < 126) {
                            header[1] = len.toByte()
                            output.write(header, 0, 2)
                        } else {
                            header[1] = 126.toByte()
                            val extLen = ByteArray(2)
                            extLen[0] = (len shr 8 and 0xFF).toByte()
                            extLen[1] = (len and 0xFF).toByte()
                            output.write(header, 0, 2)
                            output.write(extLen)
                        }
                        output.write(payload)
                        output.flush()
                    } else {
                        delay(100L)
                    }
                    if (inputStream.available() > 0) {
                        val discard = ByteArray(minOf(1024, inputStream.available()))
                        inputStream.read(discard)
                    } else {
                        delay(5L)
                    }
                }
            } finally {
                onConnectionStatusChanged(activeClients.decrementAndGet())
            }
        } catch (e: Exception) {
            Log.w(TAG, "websocket stream: ${e.message}")
        }
    }
}
