package com.lamuier.agenT.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientHelloFragmentingSslSocketFactoryTest {

    @Test
    fun splitsSingleRecordClientHelloIntoTwoValidTlsRecordsAndPreservesTrailingBytes() {
        val handshakePayload = buildSyntheticClientHelloPayload("status.deepseek.com")
        val record = buildTlsRecord(minorVersion = 0x01, payload = handshakePayload)
        val trailingChangeCipherSpec = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
        val combined = record + trailingChangeCipherSpec

        val fragmented = ClientHelloFragmentingSslSocketFactory.fragmentClientHelloRecordIfNeeded(
            bytes = combined,
            offset = 0,
            length = combined.size,
        )

        assertNotNull(fragmented)
        val out = checkNotNull(fragmented)
        assertEquals(combined.size + 5, out.size)

        // First TLS Handshake record: 5-byte header + 24-byte ClientHello prefix.
        assertEquals(0x16.toByte(), out[0])
        assertEquals(0x03.toByte(), out[1])
        assertEquals(0x01.toByte(), out[2])
        val firstLen = ((out[3].toInt() and 0xFF) shl 8) or (out[4].toInt() and 0xFF)
        assertEquals(24, firstLen)

        val secondStart = 5 + firstLen
        assertEquals(0x16.toByte(), out[secondStart])
        assertEquals(0x03.toByte(), out[secondStart + 1])
        assertEquals(0x01.toByte(), out[secondStart + 2])
        val secondLen =
            ((out[secondStart + 3].toInt() and 0xFF) shl 8) or (out[secondStart + 4].toInt() and 0xFF)
        assertEquals(handshakePayload.size - 24, secondLen)

        val firstPayload = out.copyOfRange(5, secondStart)
        val secondPayload = out.copyOfRange(secondStart + 5, secondStart + 5 + secondLen)
        assertFalse(firstPayload.decodeToString().contains("status.deepseek.com"))
        assertTrue(secondPayload.decodeToString().contains("status.deepseek.com"))
        assertArrayEquals(handshakePayload, firstPayload + secondPayload)

        val actualTrailing = out.copyOfRange(secondStart + 5 + secondLen, out.size)
        assertArrayEquals(trailingChangeCipherSpec, actualTrailing)
    }

    @Test
    fun passesThroughNonClientHelloShortPartialAndInvalidWritesUnmodified() {
        val validPayload = buildSyntheticClientHelloPayload("status.deepseek.com")
        val validRecord = buildTlsRecord(minorVersion = 0x03, payload = validPayload)

        // Non-handshake (application data)
        val appData = validRecord.copyOf().apply { this[0] = 0x17 }
        assertNull(ClientHelloFragmentingSslSocketFactory.fragmentClientHelloRecordIfNeeded(appData, 0, appData.size))

        // ServerHello (0x02)
        val serverHello = validRecord.copyOf().apply { this[5] = 0x02 }
        assertNull(ClientHelloFragmentingSslSocketFactory.fragmentClientHelloRecordIfNeeded(serverHello, 0, serverHello.size))

        // Too short (48 bytes payload)
        val shortPayload = ByteArray(48).apply {
            this[0] = 0x01
            this[3] = 44
        }
        val shortRecord = buildTlsRecord(minorVersion = 0x01, payload = shortPayload)
        assertNull(ClientHelloFragmentingSslSocketFactory.fragmentClientHelloRecordIfNeeded(shortRecord, 0, shortRecord.size))

        // Truncated write shorter than TLS record length
        assertNull(
            ClientHelloFragmentingSslSocketFactory.fragmentClientHelloRecordIfNeeded(
                validRecord,
                offset = 0,
                length = validRecord.size - 1,
            ),
        )

        // Mismatched handshake length
        val mismatched = validRecord.copyOf().apply { this[8] = (this[8] + 1).toByte() }
        assertNull(ClientHelloFragmentingSslSocketFactory.fragmentClientHelloRecordIfNeeded(mismatched, 0, mismatched.size))

        // Out-of-bounds offset/length
        assertNull(ClientHelloFragmentingSslSocketFactory.fragmentClientHelloRecordIfNeeded(validRecord, -1, validRecord.size))
        assertNull(ClientHelloFragmentingSslSocketFactory.fragmentClientHelloRecordIfNeeded(validRecord, 2, validRecord.size))
    }

    @Test
    fun forDefaultReusesWrapperForSameBaseFactory() {
        val baseA = SSLContext.getDefault().socketFactory
        val baseB = SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory

        assertSame(
            ClientHelloFragmentingSslSocketFactory.forDefault(baseA),
            ClientHelloFragmentingSslSocketFactory.forDefault(baseA),
        )
        assertNotSame(
            ClientHelloFragmentingSslSocketFactory.forDefault(baseA),
            ClientHelloFragmentingSslSocketFactory.forDefault(baseB),
        )
    }

    @Test
    fun wireCaptureThroughCreateSocketFragmentsRealClientHelloAndLeavesLaterWritesUntouched() {
        val defaultFactory = SSLContext.getDefault().socketFactory
        val fragmentingFactory = ClientHelloFragmentingSslSocketFactory(defaultFactory)

        val captured = ByteArrayOutputStream()
        val serverReady = CountDownLatch(1)
        val serverFinished = CountDownLatch(1)
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = Thread {
            server.soTimeout = 5_000
            serverReady.countDown()
            server.accept().use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val header1 = input.readNBytesCompat(5)
                val len1 = ((header1[3].toInt() and 0xFF) shl 8) or (header1[4].toInt() and 0xFF)
                val body1 = input.readNBytesCompat(len1)
                val header2 = input.readNBytesCompat(5)
                val len2 = ((header2[3].toInt() and 0xFF) shl 8) or (header2[4].toInt() and 0xFF)
                val body2 = input.readNBytesCompat(len2)
                captured.write(header1)
                captured.write(body1)
                captured.write(header2)
                captured.write(body2)
                // Send a fatal TLS unexpected_message alert so the client terminates handshake cleanly.
                client.getOutputStream().write(byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x0A))
                client.getOutputStream().flush()
            }
            serverFinished.countDown()
        }.apply { isDaemon = true; start() }

        try {
            assertTrue(serverReady.await(3, TimeUnit.SECONDS))
            val raw = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val sslSocket = fragmentingFactory.createSocket(
                raw,
                "status.deepseek.com",
                server.localPort,
                true,
            ) as SSLSocket
            sslSocket.sslParameters = sslSocket.sslParameters.apply {
                serverNames = listOf(SNIHostName("status.deepseek.com"))
            }
            runCatching { sslSocket.startHandshake() }
            runCatching { sslSocket.close() }
            assertTrue(serverFinished.await(3, TimeUnit.SECONDS))
            serverThread.join(3_000)
        } finally {
            runCatching { server.close() }
        }

        val wire = captured.toByteArray()
        assertTrue(wire.size > 60)
        assertEquals(0x16.toByte(), wire[0])
        assertEquals(0x03.toByte(), wire[1])
        val rec1Len = ((wire[3].toInt() and 0xFF) shl 8) or (wire[4].toInt() and 0xFF)
        assertEquals(24, rec1Len)
        val rec2Offset = 5 + rec1Len
        assertEquals(0x16.toByte(), wire[rec2Offset])
        assertEquals(0x03.toByte(), wire[rec2Offset + 1])
        val rec2Len =
            ((wire[rec2Offset + 3].toInt() and 0xFF) shl 8) or (wire[rec2Offset + 4].toInt() and 0xFF)
        assertEquals(wire.size, rec2Offset + 5 + rec2Len)

        val reassembledPayload =
            wire.copyOfRange(5, rec2Offset) + wire.copyOfRange(rec2Offset + 5, wire.size)
        assertEquals(0x01.toByte(), reassembledPayload[0])
        val hsLen =
            ((reassembledPayload[1].toInt() and 0xFF) shl 16) or
                ((reassembledPayload[2].toInt() and 0xFF) shl 8) or
                (reassembledPayload[3].toInt() and 0xFF)
        assertEquals(reassembledPayload.size - 4, hsLen)
        assertFalse(wire.copyOfRange(5, rec2Offset).decodeToString().contains("status.deepseek.com"))
        assertTrue(wire.copyOfRange(rec2Offset + 5, wire.size).decodeToString().contains("status.deepseek.com"))

        // Verify a second identical ClientHello write on the same socket is not split again.
        val sink = ByteArrayOutputStream()
        val fakeDelegate = object : Socket() {
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream(): OutputStream = sink
            override fun connect(endpoint: SocketAddress?) = Unit
            override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
            override fun bind(bindpoint: SocketAddress?) = Unit
        }
        val capturingFactory = object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = emptyArray()
            override fun getSupportedCipherSuites(): Array<String> = emptyArray()
            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket = s!!
            override fun createSocket(host: String?, port: Int): Socket = fakeDelegate
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = fakeDelegate
            override fun createSocket(host: InetAddress?, port: Int): Socket = fakeDelegate
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = fakeDelegate
        }
        val wrappedSocket = ClientHelloFragmentingSslSocketFactory(capturingFactory)
            .createSocket(fakeDelegate, "status.deepseek.com", 443, true)
        val packet = buildTlsRecord(0x01, buildSyntheticClientHelloPayload("status.deepseek.com"))
        val stream = wrappedSocket.getOutputStream()
        stream.write(packet)
        val firstSize = sink.size()
        assertEquals(packet.size + 5, firstSize)
        stream.write(packet)
        assertEquals(firstSize + packet.size, sink.size())
    }

    private fun buildSyntheticClientHelloPayload(host: String): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x03, 0x03)) // client_version
            write(ByteArray(32) { 0x22 }) // random
            write(0) // session_id_len
            write(byteArrayOf(0x00, 0x02, 0x13, 0x01)) // cipher_suites
            write(byteArrayOf(0x01, 0x00)) // compression_methods
            val sniName = host.encodeToByteArray()
            val extLen = 4 + 2 + 1 + 2 + sniName.size
            write(byteArrayOf(((extLen ushr 8) and 0xFF).toByte(), (extLen and 0xFF).toByte()))
            write(byteArrayOf(0x00, 0x00)) // server_name extension
            val sniDataLen = 2 + 1 + 2 + sniName.size
            write(byteArrayOf(((sniDataLen ushr 8) and 0xFF).toByte(), (sniDataLen and 0xFF).toByte()))
            val listLen = 1 + 2 + sniName.size
            write(byteArrayOf(((listLen ushr 8) and 0xFF).toByte(), (listLen and 0xFF).toByte()))
            write(0x00) // host_name
            write(byteArrayOf(((sniName.size ushr 8) and 0xFF).toByte(), (sniName.size and 0xFF).toByte()))
            write(sniName)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(0x01)
            write((body.size ushr 16) and 0xFF)
            write((body.size ushr 8) and 0xFF)
            write(body.size and 0xFF)
            write(body)
        }.toByteArray()
    }

    private fun buildTlsRecord(minorVersion: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream(payload.size + 5).apply {
            write(0x16)
            write(0x03)
            write(minorVersion)
            write((payload.size ushr 8) and 0xFF)
            write(payload.size and 0xFF)
            write(payload)
        }.toByteArray()

    private fun InputStream.readNBytesCompat(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(result, offset, count - offset)
            if (read < 0) return result.copyOf(offset)
            offset += read
        }
        return result
    }
}
