package com.raphael.androidwebcambridge.bridge

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// ponytail: UDP broadcast listener — stdlib only, no deps
class RelayDiscovery(private val onRelayFound: (String) -> Unit) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (thread?.isAlive == true) return
        thread =
            Thread {
                try {
                    socket =
                        DatagramSocket(BROADCAST_PORT, InetAddress.getByName("0.0.0.0")).apply {
                            soTimeout = 0
                        }
                    val buf = ByteArray(256)
                    while (!Thread.currentThread().isInterrupted) {
                        val packet = DatagramPacket(buf, buf.size)
                        socket?.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        try {
                            val json = JSONObject(msg)
                            val url = json.getString("url")
                            Log.d(TAG, "relay found: $url")
                            onRelayFound(url)
                            return@Thread
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "broadcast listener error: ${e.message}")
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        thread?.interrupt()
        thread = null
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "RelayDiscovery"
        private const val BROADCAST_PORT = 9999
    }
}
