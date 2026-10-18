package com.lamuier.agenT.providers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AntigravityTransportTest {
    private class Connection(url: URL, private val status: Int = 200) : HttpURLConnection(url) {
        val output = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())
        override fun getOutputStream() = output
    }
    @Test fun refreshUsesEncodedFormAndNoBearerOrQueryString() = runBlocking {
        var connection: Connection? = null
        antigravityRequest(AntigravityRequest(AntigravityRoute.Token, form = mapOf(
            "grant_type" to "refresh_token", "refresh_token" to "fixture+refresh/value&x=y", "client_id" to "fixture.apps.googleusercontent.com"))) { url ->
            assertEquals("https://oauth2.googleapis.com/token", url.toString())
            Connection(url).also { connection = it }
        }
        val http = connection!!
        assertNull(http.getRequestProperty("Authorization"))
        assertEquals("POST", http.requestMethod)
        assertEquals("application/x-www-form-urlencoded", http.getRequestProperty("Content-Type"))
        assertTrue(http.output.toString("UTF-8").contains("refresh_token=fixture%2Brefresh%2Fvalue%26x%3Dy"))
        assertFalse(http.instanceFollowRedirects); assertFalse(http.useCaches); assertTrue(http.disconnected)
    }
    @Test fun redirectsDoNotForwardGrantAndErrorsNeverContainResponseBody() = runBlocking {
        var connections = 0
        try {
            antigravityRequest(AntigravityRequest(AntigravityRoute.Identity, "fixture-access-token")) { url ->
                connections++; Connection(url, 302).also { assertEquals("www.googleapis.com", url.host) }
            }
            fail("Expected redirect rejection")
        } catch (error: ProviderHttpException) {
            assertEquals(302, error.code); assertFalse(error.toString().contains("fixture-access-token"))
        }
        assertEquals(1, connections)
    }
    @Test fun tokenEndpointRejectsBearerEvenIfInternalCallerSuppliesIt() = runBlocking {
        var opened = false
        try {
            antigravityRequest(AntigravityRequest(AntigravityRoute.Token, "fixture-access-token", form = mapOf("x" to "fixture"))) {
                opened = true; Connection(it)
            }
            fail("Expected boundary rejection")
        } catch (_: IllegalArgumentException) {}
        assertFalse(opened)
    }
}
