package com.raphael.androidwebcambridge.bridge

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object LocalBridgeAuthorization {
    fun requiresAuthorization(uri: String): Boolean =
        uri == "/" ||
            uri == "/dashboard" ||
            uri == "/obs-bridge" ||
            uri.startsWith("/api/") ||
            uri.startsWith("/stream")

    fun hasValidAccessToken(
        expectedToken: String,
        providedToken: String?,
    ): Boolean {
        if (providedToken == null) return false
        return MessageDigest.isEqual(
            expectedToken.toByteArray(StandardCharsets.UTF_8),
            providedToken.toByteArray(StandardCharsets.UTF_8),
        )
    }
}
