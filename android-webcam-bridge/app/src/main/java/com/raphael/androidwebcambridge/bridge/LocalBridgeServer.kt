package com.raphael.androidwebcambridge.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

class LocalBridgeServer(
    private val port: Int = 8787,
    private val onRemoteUpdate: (Map<String, String?>) -> Unit,
    private val onConnectionStatusChanged: (total: Int) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latestFrame = AtomicReference(ByteArray(0))
    private val currentState = AtomicReference(BridgeState())
    private val activeClients = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    fun updateState(state: BridgeState) {
        currentState.set(state)
    }

    fun submitFrame(frame: ByteArray) {
        latestFrame.set(frame)
    }

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                }
            } catch (error: Exception) {
                currentState.get()?.copy(errorMessage = error.message, statusMessage = "Server failed")?.let {
                    currentState.set(it)
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
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
                    writeText(output, JSONObjectFactory.ok("tally updated"), "application/json; charset=utf-8")
                }
                uri == "/api/settings" -> {
                  scope.launch { onRemoteUpdate(query) }
                    writeText(output, JSONObjectFactory.ok("settings updated"), "application/json; charset=utf-8")
                }
                uri == "/stream.mjpg" || uri.startsWith("/stream") -> writeMjpeg(client, output)
                else -> writeText(output, "Not found", "text/plain; charset=utf-8", code = 404)
            }
        }
    }

    private suspend fun writeMjpeg(socket: Socket, output: BufferedOutputStream) {
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
                }
                delay(33L)
            }
        } catch (_: Exception) {
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
        val statusText = when(code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = buildString {
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

    private object JSONObjectFactory {
        fun ok(message: String) = org.json.JSONObject()
            .put("ok", true)
            .put("message", message)
            .toString()
    }
}
