package app.drawbridge.policy.blocklist

import app.drawbridge.policy.model.BlocklistFormat
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-disk format for a compiled blocklist: a 16-byte header followed by the
 * sorted [DomainHash] values, which can be memory-mapped and binary-searched in
 * place.
 *
 * ```
 * offset  size  field
 * 0       4     magic 'D' 'B' 'B' 'L'
 * 4       4     format version (currently 1)
 * 8       4     entry count
 * 12      4     reserved, zero
 * 16      8*n   entry hashes, ascending, big-endian
 * ```
 */
object BlocklistFile {

    private const val MAGIC = 0x4442424C // "DBBL"
    private const val FORMAT_VERSION = 1
    private const val HEADER_BYTES = 16

    /** Maps [file] read-only. The returned set must be closed. */
    @Throws(IOException::class)
    fun open(file: File): MappedDomainSet {
        val raf = RandomAccessFile(file, "r")
        try {
            val channel = raf.channel
            if (channel.size() < HEADER_BYTES) {
                throw IOException("Blocklist ${file.name} is truncated")
            }
            val mapped = channel
                .map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                .order(ByteOrder.BIG_ENDIAN)

            val magic = mapped.getInt(0)
            if (magic != MAGIC) throw IOException("Blocklist ${file.name} has a bad magic number")
            val version = mapped.getInt(4)
            if (version != FORMAT_VERSION) {
                throw IOException("Blocklist ${file.name} has unsupported format version $version")
            }
            val count = mapped.getInt(8)
            val expected = HEADER_BYTES.toLong() + count.toLong() * Long.SIZE_BYTES
            if (count < 0 || channel.size() < expected) {
                throw IOException("Blocklist ${file.name} declares $count entries but is too small")
            }

            mapped.position(HEADER_BYTES)
            val longs = mapped.slice().order(ByteOrder.BIG_ENDIAN).asLongBuffer()
            longs.limit(count)
            return MappedDomainSet(longs, raf)
        } catch (e: Throwable) {
            raf.close()
            throw e
        }
    }

    /** Writes [hashes] (already sorted and de-duplicated) to [file] atomically. */
    @Throws(IOException::class)
    fun write(file: File, hashes: LongArray) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        RandomAccessFile(temp, "rw").use { raf ->
            raf.setLength(0)
            raf.channel.use { channel ->
                val buffer = java.nio.ByteBuffer
                    .allocate(HEADER_BYTES + hashes.size * Long.SIZE_BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                buffer.putInt(MAGIC)
                buffer.putInt(FORMAT_VERSION)
                buffer.putInt(hashes.size)
                buffer.putInt(0)
                for (hash in hashes) buffer.putLong(hash)
                buffer.flip()
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Could not move compiled blocklist into place at ${file.path}")
        }
    }
}

/**
 * Accumulates domains from any number of source lists and compiles them into a
 * single [BlocklistFile].
 */
class BlocklistBuilder(initialCapacity: Int = 1 shl 16) {

    private var hashes = LongArray(initialCapacity)
    private var count = 0

    var acceptedLines: Int = 0
        private set
    var skippedLines: Int = 0
        private set

    fun add(domain: String): Boolean {
        val normalised = DomainSet.normalise(domain)
        if (!isPlausibleDomain(normalised)) {
            skippedLines++
            return false
        }
        if (count == hashes.size) hashes = hashes.copyOf(hashes.size * 2)
        hashes[count++] = DomainHash.of(normalised)
        acceptedLines++
        return true
    }

    fun addAll(domains: Iterable<String>) = domains.forEach { add(it) }

    /**
     * Parses a blocklist source. Tolerates the three shapes these lists come in:
     * bare domains, hosts-file lines, and Adblock-style `||domain^` rules, since
     * upstream lists are not always consistent with their own advertised format.
     */
    fun addSource(reader: BufferedReader, format: BlocklistFormat) {
        reader.forEachLine { rawLine ->
            val line = rawLine.substringBefore('#').substringBefore('!').trim()
            if (line.isEmpty()) return@forEachLine

            val domain = when {
                line.startsWith("||") -> line.removePrefix("||").substringBefore('^').substringBefore('$')
                format == BlocklistFormat.HOSTS || line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") -> {
                    val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                    if (parts.size < 2) return@forEachLine else parts[1]
                }
                else -> line.split(' ', '\t').first()
            }
            add(domain)
        }
    }

    fun build(): LongArray = DomainSet.sortedDistinct(hashes, count)

    fun writeTo(file: File) = BlocklistFile.write(file, build())

    private fun isPlausibleDomain(value: String): Boolean {
        if (value.length !in 2..253) return false
        if (value.indexOf('.') <= 0) return false
        if (value == "localhost" || value.endsWith(".localhost")) return false
        for (c in value) {
            val ok = c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_' || c.code > 127
            if (!ok) return false
        }
        return true
    }
}
