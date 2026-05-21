package com.raphael.androidwebcambridge.bridge

import java.net.Inet4Address
import java.net.NetworkInterface

fun findLocalIpv4Address(): String {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { address -> address is Inet4Address && !address.isLoopbackAddress && address.hostAddress?.startsWith("169.254") != true }
            ?.hostAddress
            ?: "127.0.0.1"
    }.getOrDefault("127.0.0.1")
}
