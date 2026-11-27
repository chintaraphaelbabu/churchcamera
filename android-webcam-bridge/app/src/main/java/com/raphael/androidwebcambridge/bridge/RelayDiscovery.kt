package com.raphael.androidwebcambridge.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class RelayDiscovery(
    context: Context,
    private val onRelayFound: (String) -> Unit,
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile private var running = false

    @Volatile private var relayFound = false

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        relayFound = false
        startNsdDiscovery()

        val udpSocket =
            try {
                DatagramSocket(BROADCAST_PORT, InetAddress.getByName("0.0.0.0")).apply { soTimeout = 0 }
            } catch (e: Exception) {
                Log.w(TAG, "UDP listener unavailable: ${e.message}")
                return
            }
        socket = udpSocket
        thread =
            Thread {
                try {
                    val buf = ByteArray(256)
                    while (running && !Thread.currentThread().isInterrupted) {
                        val packet = DatagramPacket(buf, buf.size)
                        udpSocket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        try {
                            val json = JSONObject(msg)
                            val url = json.getString("url")
                            Log.d(TAG, "relay found: $url")
                            reportRelay(url)
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    if (running && !Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "broadcast listener error: ${e.message}")
                    }
                } finally {
                    udpSocket.close()
                }
            }.apply {
                isDaemon = true
                start()
            }
    }

    @Synchronized
    fun stop() {
        running = false
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            discoveryListener = null
        }
        thread?.interrupt()
        thread = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun startNsdDiscovery() {
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "DNS-SD discovery started for $serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)) return
                    nsdManager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(
                                failedServiceInfo: NsdServiceInfo,
                                errorCode: Int,
                            ) {
                                Log.w(TAG, "DNS-SD resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                                val url = relayUrlForAddress(resolvedServiceInfo.host?.hostAddress, resolvedServiceInfo.port)
                                if (url != null) {
                                    Log.d(TAG, "DNS-SD relay found: $url")
                                    reportRelay(url)
                                }
                            }
                        },
                    )
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "DNS-SD discovery stopped")
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "DNS-SD discovery start failed: $errorCode")
                    runCatching { nsdManager.stopServiceDiscovery(this) }
                    synchronized(this@RelayDiscovery) {
                        if (discoveryListener === this) discoveryListener = null
                    }
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "DNS-SD discovery stop failed: $errorCode")
                }
            }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            discoveryListener = null
            Log.w(TAG, "DNS-SD discovery unavailable: ${e.message}")
        }
    }

    private fun reportRelay(url: String) {
        synchronized(this) {
            if (!running || relayFound) return
            relayFound = true
        }
        onRelayFound(url)
    }

    companion object {
        private const val TAG = "RelayDiscovery"
        private const val BROADCAST_PORT = 9999
        private const val SERVICE_TYPE = "_churchcam._tcp."
    }
}

internal fun relayUrlForAddress(
    address: String?,
    port: Int,
): String? {
    val host = address?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (port !in 1..65535) return null
    val urlHost = if (host.contains(':')) "[${host.replace("%", "%25")}]" else host
    return "http://$urlHost:$port"
}
