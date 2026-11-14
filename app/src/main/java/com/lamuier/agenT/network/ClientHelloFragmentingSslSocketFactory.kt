package com.lamuier.agenT.network

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Wraps the system [SSLSocketFactory] to fragment an outbound single-record TLS ClientHello into
 * two RFC-compliant TLS plaintext handshake records so middleboxes inspecting only the first
 * record's SNI header do not terminate the handshake prematurely.
 */
internal class ClientHelloFragmentingSslSocketFactory(
    private val delegate: SSLSocketFactory,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = delegate.createSocket()

    override fun createSocket(
        s: Socket?,
        host: String?,
        port: Int,
        autoClose: Boolean,
    ): Socket {
        if (s == null) return delegate.createSocket(null, host, port, autoClose)
        return delegate.createSocket(FragmentingSocket(s), host, port, autoClose)
    }

    override fun createSocket(host: String?, port: Int): Socket {
        val target = host?.let { InetSocketAddress(it, port) } ?: InetSocketAddress(InetAddress.getByName(null), port)
        return connectAndWrap(target, localAddress = null, host = host, port = port)
    }

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket {
        val target = host?.let { InetSocketAddress(it, port) } ?: InetSocketAddress(InetAddress.getByName(null), port)
        return connectAndWrap(
            target,
            localAddress = InetSocketAddress(localHost, localPort),
            host = host,
            port = port,
        )
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port)

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = delegate.createSocket(address, port, localAddress, localPort)

    private fun connectAndWrap(
        remoteAddress: InetSocketAddress,
        localAddress: InetSocketAddress?,
        host: String?,
        port: Int,
    ): Socket {
        val raw = SocketFactory.getDefault().createSocket()
        return try {
            if (localAddress != null) raw.bind(localAddress)
            raw.connect(remoteAddress)
            createSocket(raw, host, port, autoClose = true)
        } catch (error: Throwable) {
            runCatching { raw.close() }
            throw error
        }
    }

    internal companion object {
        private const val TLS_CONTENT_TYPE_HANDSHAKE: Byte = 0x16
        private const val TLS_VERSION_MAJOR: Byte = 0x03
        private const val TLS_HANDSHAKE_CLIENT_HELLO: Byte = 0x01
        private const val TLS_RECORD_HEADER_BYTES = 5
        private const val TLS_HANDSHAKE_HEADER_BYTES = 4
        private const val FIRST_FRAGMENT_PAYLOAD_BYTES = 24
        private const val MIN_RECORD_LENGTH_TO_SPLIT = 48
        private const val MAX_TLS_PLAINTEXT_BYTES = 16_384

        @Volatile
        private var cachedDefault: Pair<SSLSocketFactory, SSLSocketFactory>? = null

        /** Reuses one wrapper per system default factory so HttpURLConnection can pool sockets. */
        fun forDefault(
            defaultFactory: SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory(),
        ): SSLSocketFactory {
            cachedDefault?.let { (base, wrapped) ->
                if (base === defaultFactory) return wrapped
            }
            return synchronized(this) {
                cachedDefault?.let { (base, wrapped) ->
                    if (base === defaultFactory) return wrapped
                }
                ClientHelloFragmentingSslSocketFactory(defaultFactory).also { wrapped ->
                    cachedDefault = defaultFactory to wrapped
                }
            }
        }

        /**
         * Splits an outbound single-record ClientHello buffer into two TLS Handshake records if
         * [bytes] at `[offset, offset + length)` starts with an unfragmented ClientHello record.
         * Returns `null` when the write should pass through unmodified.
         */
        internal fun fragmentClientHelloRecordIfNeeded(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): ByteArray? {
            if (offset < 0 || length < TLS_RECORD_HEADER_BYTES + MIN_RECORD_LENGTH_TO_SPLIT + 1) {
                return null
            }
            if (offset > bytes.size - length) return null
            if (bytes[offset] != TLS_CONTENT_TYPE_HANDSHAKE || bytes[offset + 1] != TLS_VERSION_MAJOR) {
                return null
            }
            val minor = bytes[offset + 2].toInt() and 0xFF
            if (minor !in 0..4) return null

            val recordLength =
                ((bytes[offset + 3].toInt() and 0xFF) shl 8) or (bytes[offset + 4].toInt() and 0xFF)
            if (recordLength <= MIN_RECORD_LENGTH_TO_SPLIT ||
                recordLength > MAX_TLS_PLAINTEXT_BYTES ||
                TLS_RECORD_HEADER_BYTES + recordLength > length
            ) {
                return null
            }
            if (bytes[offset + TLS_RECORD_HEADER_BYTES] != TLS_HANDSHAKE_CLIENT_HELLO) return null

            val handshakeLength =
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 8].toInt() and 0xFF)
            if (handshakeLength != recordLength - TLS_HANDSHAKE_HEADER_BYTES) return null

            val firstLen = FIRST_FRAGMENT_PAYLOAD_BYTES
            val secondLen = recordLength - firstLen
            val trailingOffset = offset + TLS_RECORD_HEADER_BYTES + recordLength
            val trailingLen = length - (TLS_RECORD_HEADER_BYTES + recordLength)

            val output = ByteArray(length + TLS_RECORD_HEADER_BYTES)
            // First TLS Handshake record (stops mid-Random before session_id / extensions / SNI).
            output[0] = TLS_CONTENT_TYPE_HANDSHAKE
            output[1] = TLS_VERSION_MAJOR
            output[2] = bytes[offset + 2]
            output[3] = ((firstLen ushr 8) and 0xFF).toByte()
            output[4] = (firstLen and 0xFF).toByte()
            System.arraycopy(
                bytes,
                offset + TLS_RECORD_HEADER_BYTES,
                output,
                TLS_RECORD_HEADER_BYTES,
                firstLen,
            )

            // Second TLS Handshake record carrying the rest of ClientHello (SNI & extensions).
            val secondHeader = TLS_RECORD_HEADER_BYTES + firstLen
            output[secondHeader] = TLS_CONTENT_TYPE_HANDSHAKE
            output[secondHeader + 1] = TLS_VERSION_MAJOR
            output[secondHeader + 2] = bytes[offset + 2]
            output[secondHeader + 3] = ((secondLen ushr 8) and 0xFF).toByte()
            output[secondHeader + 4] = (secondLen and 0xFF).toByte()
            System.arraycopy(
                bytes,
                offset + TLS_RECORD_HEADER_BYTES + firstLen,
                output,
                secondHeader + TLS_RECORD_HEADER_BYTES,
                secondLen,
            )

            if (trailingLen > 0) {
                System.arraycopy(
                    bytes,
                    trailingOffset,
                    output,
                    secondHeader + TLS_RECORD_HEADER_BYTES + secondLen,
                    trailingLen,
                )
            }
            return output
        }
    }

    /**
     * Subclasses [Socket] without a connected native FileDescriptor so Android's Conscrypt uses
     * its SSLEngine-backed socket over [getInputStream] and [getOutputStream].
     */
    private class FragmentingSocket(private val delegate: Socket) : Socket() {
        private val firstWriteHandled = AtomicBoolean(false)

        override fun connect(endpoint: SocketAddress?) = delegate.connect(endpoint)

        override fun connect(endpoint: SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)

        override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)

        override fun getInetAddress(): InetAddress? = delegate.inetAddress

        override fun getLocalAddress(): InetAddress = delegate.localAddress

        override fun getPort(): Int = delegate.port

        override fun getLocalPort(): Int = delegate.localPort

        override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

        override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress

        override fun getChannel(): SocketChannel? = delegate.channel

        override fun getInputStream(): InputStream = delegate.getInputStream()

        override fun getOutputStream(): OutputStream =
            ClientHelloFragmentingOutputStream(delegate.getOutputStream(), firstWriteHandled)

        override fun setTcpNoDelay(on: Boolean) {
            delegate.tcpNoDelay = on
        }

        override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

        override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)

        override fun getSoLinger(): Int = delegate.soLinger

        @Throws(IOException::class)
        override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)

        override fun setOOBInline(on: Boolean) {
            delegate.oobInline = on
        }

        override fun getOOBInline(): Boolean = delegate.oobInline

        override fun setSoTimeout(timeout: Int) {
            delegate.soTimeout = timeout
        }

        override fun getSoTimeout(): Int = delegate.soTimeout

        override fun setSendBufferSize(size: Int) {
            delegate.sendBufferSize = size
        }

        override fun getSendBufferSize(): Int = delegate.sendBufferSize

        override fun setReceiveBufferSize(size: Int) {
            delegate.receiveBufferSize = size
        }

        override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize

        override fun setKeepAlive(on: Boolean) {
            delegate.keepAlive = on
        }

        override fun getKeepAlive(): Boolean = delegate.keepAlive

        override fun setTrafficClass(tc: Int) {
            delegate.trafficClass = tc
        }

        override fun getTrafficClass(): Int = delegate.trafficClass

        override fun setReuseAddress(on: Boolean) {
            delegate.reuseAddress = on
        }

        override fun getReuseAddress(): Boolean = delegate.reuseAddress

        override fun close() = delegate.close()

        override fun shutdownInput() = delegate.shutdownInput()

        override fun shutdownOutput() = delegate.shutdownOutput()

        override fun isConnected(): Boolean = delegate.isConnected

        override fun isBound(): Boolean = delegate.isBound

        override fun isClosed(): Boolean = delegate.isClosed

        override fun isInputShutdown(): Boolean = delegate.isInputShutdown

        override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

        override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
            delegate.setPerformancePreferences(connectionTime, latency, bandwidth)

        override fun toString(): String = delegate.toString()
    }

    private class ClientHelloFragmentingOutputStream(
        private val raw: OutputStream,
        private val firstWriteHandled: AtomicBoolean,
    ) : OutputStream() {
        override fun write(b: Int) {
            firstWriteHandled.set(true)
            raw.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (firstWriteHandled.compareAndSet(false, true)) {
                val fragmented = fragmentClientHelloRecordIfNeeded(b, off, len)
                if (fragmented != null) {
                    raw.write(fragmented, 0, fragmented.size)
                    return
                }
            }
            raw.write(b, off, len)
        }

        override fun flush() = raw.flush()

        override fun close() = raw.close()
    }
}
