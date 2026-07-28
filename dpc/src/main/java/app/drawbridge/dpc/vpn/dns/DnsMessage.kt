package app.drawbridge.dpc.vpn.dns

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Just enough DNS wire format to decide what a query is asking for and to answer
 * it ourselves.
 *
 * Responses that are merely forwarded are passed through untouched — this parses
 * questions, not answers, because rewriting an answer section means fixing up
 * every compression pointer in the message and that is a rich source of subtle
 * corruption. Where policy needs to change an answer (blocking, safe search) we
 * synthesise a whole new message instead.
 */
object DnsMessage {

    const val HEADER_BYTES = 12

    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28

    /** SVCB/HTTPS records carry ECH keys; see [DnsFilter] for why they are refused. */
    const val TYPE_HTTPS = 65
    const val TYPE_SVCB = 64

    const val CLASS_IN = 1

    const val RCODE_NO_ERROR = 0
    const val RCODE_FORMAT_ERROR = 1
    const val RCODE_SERVER_FAILURE = 2
    const val RCODE_NAME_ERROR = 3

    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_LENGTH = 255
    private const val POINTER_MASK = 0xC0
    private const val MAX_POINTER_HOPS = 16

    data class Question(
        val name: String,
        val type: Int,
        val klass: Int,
        /** Offset just past the question, i.e. the start of the answer section. */
        val endOffset: Int,
    )

    /** Returns the first question, or null if the message is malformed or has none. */
    fun parseQuestion(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): Question? {
        if (length < HEADER_BYTES) return null
        val end = offset + length

        val questionCount = readUShort(packet, offset + 4)
        if (questionCount < 1) return null

        val nameStart = offset + HEADER_BYTES
        val name = StringBuilder()
        var cursor = nameStart
        var hops = 0

        while (true) {
            if (cursor >= end) return null
            val length1 = packet[cursor].toInt() and 0xFF

            when {
                length1 == 0 -> {
                    cursor++
                    break
                }
                length1 and POINTER_MASK == POINTER_MASK -> {
                    // A question name should never be compressed, but a malformed
                    // or hostile packet can still contain a pointer; follow it with
                    // a hop limit so a pointer loop cannot hang the filter thread.
                    if (cursor + 1 >= end || ++hops > MAX_POINTER_HOPS) return null
                    val target = ((length1 and 0x3F) shl 8) or (packet[cursor + 1].toInt() and 0xFF)
                    cursor = offset + target
                    continue
                }
                length1 > MAX_LABEL_LENGTH -> return null
                else -> {
                    if (cursor + 1 + length1 > end) return null
                    if (name.isNotEmpty()) name.append('.')
                    for (i in 0 until length1) {
                        name.append((packet[cursor + 1 + i].toInt() and 0xFF).toChar())
                    }
                    if (name.length > MAX_NAME_LENGTH) return null
                    cursor += 1 + length1
                }
            }
        }

        if (cursor + 4 > end) return null
        val type = readUShort(packet, cursor)
        val klass = readUShort(packet, cursor + 2)

        return Question(
            name = name.toString().lowercase(),
            type = type,
            klass = klass,
            endOffset = cursor + 4,
        )
    }

    /**
     * Builds a response to [query] carrying [answers], or an error if
     * [rcode] is non-zero.
     *
     * Names are written out in full rather than compressed. A DNS answer for a
     * single name is a few dozen bytes either way, and uncompressed messages
     * cannot be malformed by an off-by-one in a pointer offset.
     */
    fun buildResponse(
        query: ByteArray,
        question: Question,
        rcode: Int = RCODE_NO_ERROR,
        answers: List<Answer> = emptyList(),
    ): ByteArray {
        val questionBytes = question.endOffset - HEADER_BYTES
        val answerBytes = answers.sumOf { it.encodedSize() }
        val response = ByteArray(HEADER_BYTES + questionBytes + answerBytes)

        // Transaction id.
        response[0] = query[0]
        response[1] = query[1]

        val queryFlagsHigh = query[2].toInt() and 0xFF
        val recursionDesired = queryFlagsHigh and 0x01
        // QR=1 (response), opcode copied, AA=0, TC=0, RD copied.
        response[2] = (0x80 or (queryFlagsHigh and 0x78) or recursionDesired).toByte()
        // RA=1 (recursion available), Z=0, RCODE.
        response[3] = (0x80 or (rcode and 0x0F)).toByte()

        writeUShort(response, 4, 1)
        writeUShort(response, 6, answers.size)
        writeUShort(response, 8, 0)
        writeUShort(response, 10, 0)

        System.arraycopy(query, HEADER_BYTES, response, HEADER_BYTES, questionBytes)

        var cursor = HEADER_BYTES + questionBytes
        for (answer in answers) {
            cursor = answer.writeTo(response, cursor)
        }
        return response
    }

    data class Answer(
        val name: String,
        val type: Int,
        val ttlSeconds: Int,
        val rdata: ByteArray,
    ) {
        fun encodedSize(): Int = encodedNameSize(name) + 2 + 2 + 4 + 2 + rdata.size

        fun writeTo(target: ByteArray, offset: Int): Int {
            var cursor = writeName(target, offset, name)
            writeUShort(target, cursor, type); cursor += 2
            writeUShort(target, cursor, CLASS_IN); cursor += 2
            writeUInt(target, cursor, ttlSeconds); cursor += 4
            writeUShort(target, cursor, rdata.size); cursor += 2
            System.arraycopy(rdata, 0, target, cursor, rdata.size)
            return cursor + rdata.size
        }

        override fun equals(other: Any?): Boolean =
            other is Answer && name == other.name && type == other.type &&
                ttlSeconds == other.ttlSeconds && rdata.contentEquals(other.rdata)

        override fun hashCode(): Int =
            (((name.hashCode() * 31 + type) * 31) + ttlSeconds) * 31 + rdata.contentHashCode()

        companion object {
            fun address(name: String, address: InetAddress, ttlSeconds: Int): Answer = Answer(
                name = name,
                type = if (address is Inet6Address) TYPE_AAAA else TYPE_A,
                ttlSeconds = ttlSeconds,
                rdata = address.address,
            )

            fun canonicalName(name: String, target: String, ttlSeconds: Int): Answer {
                val encoded = ByteArray(encodedNameSize(target))
                writeName(encoded, 0, target)
                return Answer(name, TYPE_CNAME, ttlSeconds, encoded)
            }
        }
    }

    internal fun encodedNameSize(name: String): Int {
        if (name.isEmpty()) return 1
        return name.split('.').filter { it.isNotEmpty() }.sumOf { it.length + 1 } + 1
    }

    internal fun writeName(target: ByteArray, offset: Int, name: String): Int {
        var cursor = offset
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            target[cursor++] = label.length.toByte()
            for (c in label) target[cursor++] = c.code.toByte()
        }
        target[cursor++] = 0
        return cursor
    }

    internal fun readUShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    internal fun writeUShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    internal fun writeUInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    /** True if [address] is an IPv4 address, used when choosing a record type. */
    fun isIpv4(address: InetAddress): Boolean = address is Inet4Address
}
