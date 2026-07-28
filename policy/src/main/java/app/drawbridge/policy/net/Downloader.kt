package app.drawbridge.policy.net

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Minimal HTTPS fetcher built on [HttpURLConnection].
 *
 * Deliberately not OkHttp: this module is linked into an always-on foreground
 * service, and policy fetching is a handful of plain GETs a day.
 */
class Downloader(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
    private val maxBytes: Long = 64L * 1024 * 1024,
) {

    /** Fetches [url] as text. */
    @Throws(IOException::class)
    fun getText(url: String): String = open(url) { stream ->
        stream.readCappedBytes(maxBytes).toString(Charsets.UTF_8)
    }

    /**
     * Downloads [url] to [destination], returning the lowercase hex SHA-256 of
     * what was written. Writes to a sibling temp file and renames, so a killed
     * download never leaves a half-written list in place.
     */
    @Throws(IOException::class)
    fun getToFile(url: String, destination: File): String {
        val temp = File(destination.parentFile, "${destination.name}.download")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            open(url) { stream ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IOException("Download from $url exceeded $maxBytes bytes")
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (!temp.renameTo(destination)) {
                throw IOException("Could not move download into place at ${destination.path}")
            }
        } finally {
            temp.delete()
        }
        return digest.digest().toHex()
    }

    private fun <T> open(url: String, block: (InputStream) -> T): T {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Policy and blocklist URLs must be https, got '${parsed.protocol}'"
        }
        val connection = (parsed.openConnection() as HttpsURLConnection).apply {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("GET $url returned HTTP $status")
            }
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                java.util.zip.GZIPInputStream(raw)
            } else {
                raw
            }
            return stream.use(block)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val USER_AGENT = "drawbridge-policy/1"

        private fun InputStream.readCappedBytes(limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IOException("Response exceeded $limit bytes")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
