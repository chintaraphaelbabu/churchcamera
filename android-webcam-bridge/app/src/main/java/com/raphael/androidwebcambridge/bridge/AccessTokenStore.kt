package com.raphael.androidwebcambridge.bridge

import java.security.SecureRandom
import java.util.Base64

internal class AccessTokenStore(
    private val readToken: () -> String?,
    private val persistToken: (String) -> Unit,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun getOrCreate(): String {
        readToken()?.takeIf(::isValidToken)?.let { return it }

        val randomBytes = ByteArray(32)
        secureRandom.nextBytes(randomBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes).also(persistToken)
    }

    private fun isValidToken(token: String): Boolean =
        runCatching {
            val decoded = Base64.getUrlDecoder().decode(token)
            decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == token
        }.getOrDefault(false)
}

internal fun withAccessToken(
    url: String,
    accessToken: String,
): String = "$url?token=$accessToken"
