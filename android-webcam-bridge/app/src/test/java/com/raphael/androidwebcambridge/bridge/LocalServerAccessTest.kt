package com.raphael.androidwebcambridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServerAccessTest {
    @Test
    fun accessTokenIsRandomUrlSafeAndReusedFromPersistence() {
        var persisted: String? = null
        val store = AccessTokenStore({ persisted }, { persisted = it })

        val token = store.getOrCreate()

        assertEquals(token, persisted)
        assertEquals(token, store.getOrCreate())
        assertEquals(43, token.length)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test
    fun authenticatedUrlsCarryTheAccessToken() {
        assertEquals(
            "http://192.168.1.5:8787/stream.mjpg?token=abc_def-123",
            withAccessToken("http://192.168.1.5:8787/stream.mjpg", "abc_def-123"),
        )
    }

    @Test
    fun invalidPersistedTokenIsReplaced() {
        var persisted = "not-a-generated-token"
        val store = AccessTokenStore({ persisted }, { persisted = it })

        val token = store.getOrCreate()

        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(token, persisted)
    }

    @Test
    fun allBrowserAndApiRoutesRequireTheMatchingToken() {
        val protectedRoutes = listOf("/", "/dashboard", "/obs-bridge", "/api/state", "/api/audio/out", "/stream.mjpg")
        protectedRoutes.forEach { route ->
            assertTrue("$route should require authorization", LocalBridgeAuthorization.requiresAuthorization(route))
            assertFalse(LocalBridgeAuthorization.hasValidAccessToken("secret", null))
            assertFalse(LocalBridgeAuthorization.hasValidAccessToken("secret", "wrong"))
            assertTrue(LocalBridgeAuthorization.hasValidAccessToken("secret", "secret"))
        }
        assertFalse(LocalBridgeAuthorization.requiresAuthorization("/favicon.ico"))
    }

    @Test
    fun browserPagesUseAndExposeAuthenticatedUrls() {
        val token = "test_token-123"

        val landingPage = BridgeHtmlAssets.landingPage(token)
        val dashboardPage = BridgeHtmlAssets.dashboardPage(token)
        val obsBridgePage = BridgeHtmlAssets.obsBridgePage(token)

        assertTrue(landingPage.contains("/dashboard?token=$token"))
        assertTrue(landingPage.contains("/stream.mjpg?token=$token"))
        assertTrue(dashboardPage.contains("/stream.mjpg?token=$token"))
        assertTrue(dashboardPage.contains("token=$token"))
        assertTrue(dashboardPage.contains("id=\"copyStreamUrl\""))
        assertTrue(obsBridgePage.contains("/stream.mjpg?token=$token"))
        assertFalse(dashboardPage.contains("__ACCESS_TOKEN__"))
    }
}
