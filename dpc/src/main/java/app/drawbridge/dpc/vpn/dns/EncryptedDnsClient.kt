package app.drawbridge.dpc.vpn.dns

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Forwards DNS queries over TLS (DoT, RFC 7858).
 *
 * **Why DoT and not DoH.** DoH was the obvious choice, but Mullvad's DoH
 * endpoint only accepts HTTP/2 — an HTTP/1.1 request is closed without a
 * response — and Android's built-in `HttpURLConnection` is HTTP/1.1 only. Using
 * DoH would therefore mean bundling a full HTTP/2 client into a service that has
 * to stay resident on the device forever. DoT gives exactly the same property
 * (the local network can neither read the lookups nor forge answers) over a
 * protocol that is a TLS socket plus a two-byte length prefix, using nothing
 * beyond the platform.
 *
 * Each forwarder thread keeps its own connection, so a query never waits on
 * another thread's socket and responses never need matching up by transaction
 * id. Connections are re-established transparently when the server drops them.
 */
class EncryptedDnsClient(
    upstream: String,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 5_000,
) {

    /** Hostname of the upstream, so the resolver can avoid resolving it through itself. */
    val host: String

    private val port: Int

    init {
        val uri = runCatching { URI(upstream) }.getOrNull()
        require(uri?.scheme?.lowercase() == SCHEME) {
            "An encrypted DNS upstream must look like '$SCHEME://host[:port]', got '$upstream'"
        }
        host = requireNotNull(uri?.host?.lowercase()) { "No host in '$upstream'" }
        port = uri?.port?.takeIf { it > 0 } ?: DEFAULT_PORT
    }

    private val connections = ThreadLocal<SSLSocket?>()

    /**
     * Sends [query] and returns the raw response, or null if the upstream could
     * not be reached. Callers fall back to plain DNS on null rather than leaving
     * the device unable to resolve anything at all.
     */
    fun query(query: ByteArray): ByteArray? {
        if (query.size > MAX_MESSAGE_BYTES) return null

        // Two attempts: an idle connection the server has already closed fails on
        // the first write or read, and the retry gets a fresh one. Without this,
        // a steady trickle of lookups would silently lose the encrypted hop.
        repeat(2) { attempt ->
            val socket = connection() ?: return null
            try {
                return exchange(socket, query)
            } catch (e: IOException) {
                discard()
                if (attempt == 1) {
                    Log.w(TAG, "Encrypted DNS query failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        return null
    }

    /** Closes this thread's connection. Called when the filter shuts down. */
    fun close() = discard()

    @Throws(IOException::class)
    private fun exchange(socket: SSLSocket, query: ByteArray): ByteArray {
        val output = socket.getOutputStream()
        output.write((query.size shr 8) and 0xFF)
        output.write(query.size and 0xFF)
        output.write(query)
        output.flush()

        val input = socket.getInputStream()
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) throw IOException("upstream closed the connection")

        val length = (high shl 8) or low
        if (length !in DnsMessage.HEADER_BYTES..MAX_MESSAGE_BYTES) {
            throw IOException("upstream announced an implausible $length-byte answer")
        }

        val response = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(response, read, length - read)
            if (count < 0) throw IOException("upstream truncated its answer")
            read += count
        }
        return response
    }

    private fun connection(): SSLSocket? {
        connections.get()?.let { existing ->
            if (!existing.isClosed && existing.isConnected) return existing
            discard()
        }

        return try {
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket() as SSLSocket
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            socket.soTimeout = readTimeoutMillis
            socket.tcpNoDelay = true

            // Without this the certificate is negotiated but never checked
            // against the hostname, which would leave the connection open to
            // exactly the interception DoT exists to prevent.
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }

            socket.startHandshake()
            connections.set(socket)
            socket
        } catch (e: IOException) {
            Log.w(TAG, "Could not open an encrypted DNS connection to $host:$port: ${e.message}")
            null
        }
    }

    private fun discard() {
        connections.get()?.let { runCatching { (it as Socket).close() } }
        connections.set(null)
    }

    private companion object {
        const val TAG = "EncryptedDns"
        const val SCHEME = "tls"
        const val DEFAULT_PORT = 853

        /** A DNS message is length-prefixed with two bytes, so this is the ceiling. */
        const val MAX_MESSAGE_BYTES = 65_535
    }
}
