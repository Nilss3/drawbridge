package app.drawbridge.dpc.vpn.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DnsMessageTest {

    @Test
    fun `parses a simple query`() {
        val query = query("www.example.com", DnsMessage.TYPE_A)
        val question = DnsMessage.parseQuestion(query)!!

        assertEquals("www.example.com", question.name)
        assertEquals(DnsMessage.TYPE_A, question.type)
        assertEquals(DnsMessage.CLASS_IN, question.klass)
        assertEquals(query.size, question.endOffset)
    }

    @Test
    fun `lowercases the queried name`() {
        val question = DnsMessage.parseQuestion(query("WWW.Example.COM", DnsMessage.TYPE_A))!!
        assertEquals("www.example.com", question.name)
    }

    @Test
    fun `returns null for a truncated message`() {
        val query = query("example.com", DnsMessage.TYPE_A)
        assertNull(DnsMessage.parseQuestion(query.copyOf(query.size - 3)))
    }

    @Test
    fun `returns null when there are no questions`() {
        val header = ByteArray(DnsMessage.HEADER_BYTES)
        assertNull(DnsMessage.parseQuestion(header))
    }

    @Test
    fun `survives a compression pointer loop`() {
        // A name whose pointer points back at itself would spin forever without
        // the hop limit.
        val packet = ByteArray(DnsMessage.HEADER_BYTES + 8)
        DnsMessage.writeUShort(packet, 4, 1) // one question
        packet[DnsMessage.HEADER_BYTES] = 0xC0.toByte()
        packet[DnsMessage.HEADER_BYTES + 1] = DnsMessage.HEADER_BYTES.toByte()

        assertNull(DnsMessage.parseQuestion(packet))
    }

    @Test
    fun `builds an NXDOMAIN response that echoes the question`() {
        val query = query("blocked.example", DnsMessage.TYPE_A)
        val question = DnsMessage.parseQuestion(query)!!

        val response = DnsMessage.buildResponse(
            query,
            question,
            rcode = DnsMessage.RCODE_NAME_ERROR,
        )

        assertEquals(query[0], response[0])
        assertEquals(query[1], response[1])
        assertTrue("QR bit must be set", response[2].toInt() and 0x80 != 0)
        assertEquals(DnsMessage.RCODE_NAME_ERROR, response[3].toInt() and 0x0F)
        assertEquals(1, DnsMessage.readUShort(response, 4))
        assertEquals(0, DnsMessage.readUShort(response, 6))

        // The question section is copied verbatim so the client can match it.
        assertArrayEquals(
            query.copyOfRange(DnsMessage.HEADER_BYTES, query.size),
            response.copyOfRange(DnsMessage.HEADER_BYTES, response.size),
        )

        val echoed = DnsMessage.parseQuestion(response)!!
        assertEquals("blocked.example", echoed.name)
    }

    @Test
    fun `preserves the recursion-desired bit and advertises recursion available`() {
        val query = query("example.com", DnsMessage.TYPE_A, recursionDesired = true)
        val response = DnsMessage.buildResponse(query, DnsMessage.parseQuestion(query)!!)

        assertTrue("RD must be echoed", response[2].toInt() and 0x01 != 0)
        assertTrue("RA must be set", response[3].toInt() and 0x80 != 0)
    }

    @Test
    fun `builds an answer carrying an IPv4 address`() {
        val query = query("safe.example", DnsMessage.TYPE_A)
        val question = DnsMessage.parseQuestion(query)!!
        val address = InetAddress.getByName("203.0.113.7")

        val response = DnsMessage.buildResponse(
            query,
            question,
            answers = listOf(DnsMessage.Answer.address("safe.example", address, 300)),
        )

        assertEquals(1, DnsMessage.readUShort(response, 6))

        // name(14) + type(2) + class(2) + ttl(4) + rdlength(2) + rdata(4)
        val answerStart = question.endOffset
        val nameLength = DnsMessage.encodedNameSize("safe.example")
        val typeOffset = answerStart + nameLength
        assertEquals(DnsMessage.TYPE_A, DnsMessage.readUShort(response, typeOffset))
        assertEquals(DnsMessage.CLASS_IN, DnsMessage.readUShort(response, typeOffset + 2))
        assertEquals(4, DnsMessage.readUShort(response, typeOffset + 8))
        assertArrayEquals(
            address.address,
            response.copyOfRange(typeOffset + 10, typeOffset + 14),
        )
    }

    @Test
    fun `builds an answer carrying an IPv6 address`() {
        val query = query("safe.example", DnsMessage.TYPE_AAAA)
        val question = DnsMessage.parseQuestion(query)!!
        val address = InetAddress.getByName("2001:db8::1")

        val response = DnsMessage.buildResponse(
            query,
            question,
            answers = listOf(DnsMessage.Answer.address("safe.example", address, 300)),
        )

        val typeOffset = question.endOffset + DnsMessage.encodedNameSize("safe.example")
        assertEquals(DnsMessage.TYPE_AAAA, DnsMessage.readUShort(response, typeOffset))
        assertEquals(16, DnsMessage.readUShort(response, typeOffset + 8))
    }

    @Test
    fun `encodes a CNAME target as a DNS name`() {
        val answer = DnsMessage.Answer.canonicalName(
            "www.google.com",
            "forcesafesearch.google.com",
            300,
        )
        assertEquals(DnsMessage.TYPE_CNAME, answer.type)
        assertEquals(
            DnsMessage.encodedNameSize("forcesafesearch.google.com"),
            answer.rdata.size,
        )
        assertEquals("forcesafesearch".length.toByte(), answer.rdata[0])
    }

    private fun query(
        name: String,
        type: Int,
        recursionDesired: Boolean = false,
    ): ByteArray {
        val nameBytes = DnsMessage.encodedNameSize(name)
        val packet = ByteArray(DnsMessage.HEADER_BYTES + nameBytes + 4)
        DnsMessage.writeUShort(packet, 0, 0x1234)
        if (recursionDesired) packet[2] = 0x01
        DnsMessage.writeUShort(packet, 4, 1)

        var cursor = DnsMessage.writeName(packet, DnsMessage.HEADER_BYTES, name)
        DnsMessage.writeUShort(packet, cursor, type)
        DnsMessage.writeUShort(packet, cursor + 2, DnsMessage.CLASS_IN)
        return packet
    }
}
