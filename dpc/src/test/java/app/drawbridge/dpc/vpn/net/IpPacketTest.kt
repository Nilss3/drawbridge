package app.drawbridge.dpc.vpn.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class IpPacketTest {

    @Test
    fun `parses an IPv4 UDP datagram`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = ipv4Udp(
            source = "10.0.0.2",
            destination = "10.111.222.2",
            sourcePort = 41234,
            destinationPort = 53,
            payload = payload,
        )

        val datagram = IpPacket.parseUdp(packet, packet.size)!!
        assertEquals(InetAddress.getByName("10.0.0.2"), datagram.source)
        assertEquals(InetAddress.getByName("10.111.222.2"), datagram.destination)
        assertEquals(41234, datagram.sourcePort)
        assertEquals(53, datagram.destinationPort)
        assertArrayEquals(payload, datagram.payload)
    }

    @Test
    fun `ignores non-UDP packets`() {
        val packet = ipv4Udp("10.0.0.2", "10.0.0.3", 1, 2, byteArrayOf(0))
        packet[9] = 6 // TCP
        assertNull(IpPacket.parseUdp(packet, packet.size))
    }

    @Test
    fun `ignores fragmented packets rather than mis-parsing them`() {
        val packet = ipv4Udp("10.0.0.2", "10.0.0.3", 1, 2, byteArrayOf(0))
        IpPacket.writeUShort(packet, 6, 0x2000) // more-fragments
        assertNull(IpPacket.parseUdp(packet, packet.size))
    }

    @Test
    fun `ignores a packet that claims a longer header than it has`() {
        val packet = ipv4Udp("10.0.0.2", "10.0.0.3", 1, 2, byteArrayOf(0))
        packet[0] = 0x4F // 15 words of header, longer than the packet
        assertNull(IpPacket.parseUdp(packet, packet.size))
    }

    @Test
    fun `round-trips an IPv4 reply with valid checksums`() {
        val request = IpPacket.parseUdp(
            ipv4Udp("10.0.0.2", "10.111.222.2", 41234, 53, byteArrayOf(9, 9)),
            36,
        )!!

        val reply = IpPacket.buildUdpReply(request, byteArrayOf(7, 7, 7))!!
        val parsed = IpPacket.parseUdp(reply, reply.size)!!

        // Endpoints are swapped, so the answer goes back to the querying app.
        assertEquals(request.destination, parsed.source)
        assertEquals(request.source, parsed.destination)
        assertEquals(53, parsed.sourcePort)
        assertEquals(41234, parsed.destinationPort)
        assertArrayEquals(byteArrayOf(7, 7, 7), parsed.payload)

        assertEquals("IPv4 header checksum", 0, onesComplementSum(reply, 0, 20))
    }

    @Test
    fun `round-trips an IPv6 reply`() {
        val request = IpPacket.parseUdp(
            ipv6Udp("2001:db8::2", "fd00:d8ba:d8ba::2", 41234, 53, byteArrayOf(1)),
            49,
        )!!

        val reply = IpPacket.buildUdpReply(request, byteArrayOf(5, 5))!!
        val parsed = IpPacket.parseUdp(reply, reply.size)!!

        assertEquals(request.destination, parsed.source)
        assertEquals(request.source, parsed.destination)
        assertArrayEquals(byteArrayOf(5, 5), parsed.payload)

        // A zero checksum is illegal over IPv6, so this must be non-zero.
        val checksum = IpPacket.readUShort(reply, 40 + 6)
        assertNotNull(checksum)
        assert(checksum != 0)
    }

    @Test
    fun `refuses to build a reply across address families`() {
        val mixed = IpPacket.UdpDatagram(
            source = InetAddress.getByName("10.0.0.1"),
            destination = InetAddress.getByName("2001:db8::1"),
            sourcePort = 1,
            destinationPort = 53,
            payload = ByteArray(0),
        )
        assertNull(IpPacket.buildUdpReply(mixed, ByteArray(1)))
    }

    private fun ipv4Udp(
        source: String,
        destination: String,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(20 + 8 + payload.size)
        packet[0] = 0x45
        IpPacket.writeUShort(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = IpPacket.PROTOCOL_UDP.toByte()
        System.arraycopy(InetAddress.getByName(source).address, 0, packet, 12, 4)
        System.arraycopy(InetAddress.getByName(destination).address, 0, packet, 16, 4)
        IpPacket.writeUShort(packet, 20, sourcePort)
        IpPacket.writeUShort(packet, 22, destinationPort)
        IpPacket.writeUShort(packet, 24, 8 + payload.size)
        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun ipv6Udp(
        source: String,
        destination: String,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(40 + 8 + payload.size)
        packet[0] = 0x60
        IpPacket.writeUShort(packet, 4, 8 + payload.size)
        packet[6] = IpPacket.PROTOCOL_UDP.toByte()
        packet[7] = 64
        System.arraycopy(InetAddress.getByName(source).address, 0, packet, 8, 16)
        System.arraycopy(InetAddress.getByName(destination).address, 0, packet, 24, 16)
        IpPacket.writeUShort(packet, 40, sourcePort)
        IpPacket.writeUShort(packet, 42, destinationPort)
        IpPacket.writeUShort(packet, 44, 8 + payload.size)
        System.arraycopy(payload, 0, packet, 48, payload.size)
        return packet
    }

    /** A correct header sums (including its own checksum field) to zero. */
    private fun onesComplementSum(packet: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i + 1 < offset + length) {
            sum += IpPacket.readUShort(packet, i)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
