package com.lamuier.agenT.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfficialStatusApiClientTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deepSeekUsesFragmentingFactoryWhileOtherProvidersKeepDefaultFactory() = runBlocking {
        val customFactory = ClientHelloFragmentingSslSocketFactory(SSLContext.getDefault().socketFactory)
        val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val seenFactories = mutableListOf<SSLSocketFactory>()

        val deepSeekClient = OfficialStatusApiClient(
            context = context,
            provider = OfficialStatusProvider.DeepSeek,
            deepSeekSslSocketFactoryProvider = { customFactory },
            connectionOpener = { url ->
                RecordingHttpsConnection(
                    url = url,
                    defaultFactory = defaultFactory,
                    onConnect = { conn ->
                        seenFactories += conn.sslSocketFactory
                        200 to """{"data":{"ok":true}}"""
                    },
                )
            },
        )
        val deepSeekResult = deepSeekClient.fetch(StatusResource.Summary)
        assertTrue(deepSeekResult.getJSONObject("data").getBoolean("ok"))
        assertEquals(listOf<SSLSocketFactory>(customFactory), seenFactories)

        val kimiFactories = mutableListOf<SSLSocketFactory>()
        val kimiClient = OfficialStatusApiClient(
            context = context,
            provider = OfficialStatusProvider.KimiCode,
            deepSeekSslSocketFactoryProvider = { customFactory },
            connectionOpener = { url ->
                RecordingHttpsConnection(
                    url = url,
                    defaultFactory = defaultFactory,
                    onConnect = { conn ->
                        kimiFactories += conn.sslSocketFactory
                        200 to """{"status":{"indicator":"none"}}"""
                    },
                )
            },
        )
        kimiClient.fetch(StatusResource.Summary)
        assertEquals(listOf<SSLSocketFactory>(defaultFactory), kimiFactories)
    }

    @Test
    fun deepSeekFallsBackOnceToStandardTlsOnPreResponseHandshakeOrEofFailure() = runBlocking {
        val customFactory = ClientHelloFragmentingSslSocketFactory(SSLContext.getDefault().socketFactory)
        val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val attempts = mutableListOf<Pair<SSLSocketFactory, Boolean>>()

        val client = OfficialStatusApiClient(
            context = context,
            provider = OfficialStatusProvider.DeepSeek,
            deepSeekSslSocketFactoryProvider = { customFactory },
            connectionOpener = { url ->
                lateinit var conn: RecordingHttpsConnection
                conn = RecordingHttpsConnection(
                    url = url,
                    defaultFactory = defaultFactory,
                    onConnect = {
                        val idx = attempts.size
                        attempts += conn.sslSocketFactory to conn.disconnected
                        if (idx == 0) {
                            throw SSLHandshakeException("Remote host terminated handshake")
                        }
                        200 to """{"fallback":true}"""
                    },
                    onDisconnect = {
                        if (attempts.isNotEmpty()) {
                            val last = attempts.lastIndex
                            attempts[last] = attempts[last].first to true
                        }
                    },
                )
                conn
            },
        )

        val json = client.fetch(StatusResource.Summary)
        assertTrue(json.getBoolean("fallback"))
        assertEquals(2, attempts.size)
        assertSame(customFactory, attempts[0].first)
        assertTrue(attempts[0].second)
        assertNotSame(customFactory, attempts[1].first)
        assertSame(defaultFactory, attempts[1].first)
        assertTrue(attempts[1].second)

        // Verify OkHttp-style generic pre-response EOF IOException also falls back once.
        var calls = 0
        val eofClient = OfficialStatusApiClient(
            context = context,
            provider = OfficialStatusProvider.DeepSeek,
            deepSeekSslSocketFactoryProvider = { customFactory },
            connectionOpener = { url ->
                RecordingHttpsConnection(
                    url = url,
                    defaultFactory = defaultFactory,
                    onConnect = {
                        calls += 1
                        if (calls == 1) throw EOFException("unexpected end of stream")
                        200 to """{"recovered":1}"""
                    },
                )
            },
        )
        assertEquals(1, eofClient.fetch(StatusResource.Summary).getInt("recovered"))
        assertEquals(2, calls)
    }

    @Test
    fun deepSeekDoesNotFallbackOnDnsTimeoutConnectRefusedCertFailureOrHttpError() = runBlocking {
        val customFactory = ClientHelloFragmentingSslSocketFactory(SSLContext.getDefault().socketFactory)
        val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

        val nonRetriedErrors = listOf<IOException>(
            UnknownHostException("offline"),
            SocketTimeoutException("timed out"),
            ConnectException("refused"),
            SSLPeerUnverifiedException("bad cert"),
        )
        for (expected in nonRetriedErrors) {
            var attempts = 0
            val client = OfficialStatusApiClient(
                context = context,
                provider = OfficialStatusProvider.DeepSeek,
                deepSeekSslSocketFactoryProvider = { customFactory },
                connectionOpener = { url ->
                    RecordingHttpsConnection(
                        url = url,
                        defaultFactory = defaultFactory,
                        onConnect = {
                            attempts += 1
                            throw expected
                        },
                    )
                },
            )
            try {
                client.fetch(StatusResource.Summary)
                fail("Expected ${expected::class.simpleName}")
            } catch (actual: IOException) {
                assertEquals(expected::class.java, actual::class.java)
                assertEquals(expected.message, actual.message)
                assertEquals(1, attempts)
            }
        }

        // Verify cancelling before fallback prevents starting the second standard-TLS attempt.
        var cancelledAttempts = 0
        val cancelJob = kotlinx.coroutines.Job()
        val cancelledClient = OfficialStatusApiClient(
            context = context,
            provider = OfficialStatusProvider.DeepSeek,
            deepSeekSslSocketFactoryProvider = { customFactory },
            connectionOpener = { url ->
                RecordingHttpsConnection(
                    url = url,
                    defaultFactory = defaultFactory,
                    onConnect = {
                        cancelledAttempts += 1
                        cancelJob.cancel()
                        throw SSLHandshakeException("terminated")
                    },
                )
            },
        )
        try {
            kotlinx.coroutines.withContext(cancelJob) {
                cancelledClient.fetch(StatusResource.Summary)
            }
            fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(1, cancelledAttempts)
        }

        var httpAttempts = 0
        val httpErrorClient = OfficialStatusApiClient(
            context = context,
            provider = OfficialStatusProvider.DeepSeek,
            deepSeekSslSocketFactoryProvider = { customFactory },
            connectionOpener = { url ->
                RecordingHttpsConnection(
                    url = url,
                    defaultFactory = defaultFactory,
                    onConnect = {
                        httpAttempts += 1
                        503 to ""
                    },
                )
            },
        )
        try {
            httpErrorClient.fetch(StatusResource.Summary)
            fail("Expected IOException for HTTP 503")
        } catch (_: IOException) {
            assertEquals(1, httpAttempts)
        }
    }

    private class RecordingHttpsConnection(
        url: String,
        defaultFactory: SSLSocketFactory,
        private val onConnect: (RecordingHttpsConnection) -> Pair<Int, String>,
        private val onDisconnect: () -> Unit = {},
    ) : HttpsURLConnection(URL(url)) {
        private var cachedResponse: Pair<Int, String>? = null
        var disconnected: Boolean = false
            private set

        init {
            sslSocketFactory = defaultFactory
        }

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
            onDisconnect()
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int {
            val result = cachedResponse ?: onConnect(this).also { cachedResponse = it }
            responseCode = result.first
            return result.first
        }

        override fun getInputStream(): InputStream {
            val body = (cachedResponse ?: onConnect(this).also { cachedResponse = it }).second
            return ByteArrayInputStream(body.encodeToByteArray())
        }

        override fun getCipherSuite(): String = "TLS_AES_128_GCM_SHA256"

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
