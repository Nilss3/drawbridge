package app.drawbridge.dpc.vpn.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Minimal IPv4/IPv6 + UDP packet handling.
 *
 * The filter is DNS-only: it never reassembles TCP or tracks connections, so
 * this only needs to recognise a UDP datagram, hand over its payload, and build
 * a reply that swaps the endpoints. Anything else on the tunnel is dropped.
 */
object IpPacket {

    const val PROTOCOL_UDP = 17

    private const val IPV4_MIN_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8

    data class UdpDatagram(
        val source: InetAddress,
        val destination: InetAddress,
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is UdpDatagram && source == other.source && destination == other.destination &&
                sourcePort == other.sourcePort && destinationPort == other.destinationPort &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (((source.hashCode() * 31 + destination.hashCode()) * 31 + sourcePort) * 31 +
                destinationPort) * 31 + payload.contentHashCode()
    }

    /**
     * Parses a UDP datagram out of a raw IP packet, or returns null if the packet
     * is not UDP, is fragmented, or is malformed.
     */
    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < 1) return null
        return when (packet[0].toInt() shr 4 and 0xF) {
            4 -> parseIpv4Udp(packet, length)
            6 -> parseIpv6Udp(packet, length)
            else -> null
        }
    }

    private fun parseIpv4Udp(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < IPV4_MIN_HEADER) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_MIN_HEADER || length < headerLength + UDP_HEADER) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        // Fragmented datagrams would need reassembly; DNS over UDP is not
        // fragmented in practice, so drop rather than mis-handle them.
        val fragmentField = readUShort(packet, 6)
        val moreFragments = fragmentField and 0x2000 != 0
        val fragmentOffset = fragmentField and 0x1FFF
        if (moreFragments || fragmentOffset != 0) return null

        val source = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val destination = InetAddress.getByAddress(packet.copyOfRange(16, 20))
        return parseUdpBody(packet, headerLength, length, source, destination)
    }

    private fun parseIpv6Udp(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < IPV6_HEADER + UDP_HEADER) return null
        // Extension headers are not walked: a plain UDP datagram has next-header
        // 17 directly, and anything else is not ours to handle.
        if ((packet[6].toInt() and 0xFF) != PROTOCOL_UDP) return null

        val source = InetAddress.getByAddress(packet.copyOfRange(8, 24))
        val destination = InetAddress.getByAddress(packet.copyOfRange(24, 40))
        return parseUdpBody(packet, IPV6_HEADER, length, source, destination)
    }

    private fun parseUdpBody(
        packet: ByteArray,
        headerLength: Int,
        length: Int,
        source: InetAddress,
        destination: InetAddress,
    ): UdpDatagram? {
        val sourcePort = readUShort(packet, headerLength)
        val destinationPort = readUShort(packet, headerLength + 2)
        val udpLength = readUShort(packet, headerLength + 4)
        if (udpLength < UDP_HEADER) return null

        val payloadLength = minOf(udpLength - UDP_HEADER, length - headerLength - UDP_HEADER)
        if (payloadLength < 0) return null

        val payloadStart = headerLength + UDP_HEADER
        return UdpDatagram(
            source = source,
            destination = destination,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payload = packet.copyOfRange(payloadStart, payloadStart + payloadLength),
        )
    }

    /**
     * Builds the IP packet carrying [payload] back to the sender of [request],
     * i.e. with source and destination swapped.
     */
    fun buildUdpReply(request: UdpDatagram, payload: ByteArray): ByteArray? = when {
        request.source is Inet4Address && request.destination is Inet4Address ->
            buildIpv4Udp(
                source = request.destination,
                destination = request.source,
                sourcePort = request.destinationPort,
                destinationPort = request.sourcePort,
                payload = payload,
            )

        request.source is Inet6Address && request.destination is Inet6Address ->
            buildIpv6Udp(
                source = request.destination,
                destination = request.source,
                sourcePort = request.destinationPort,
                destinationPort = request.sourcePort,
                payload = payload,
            )

        else -> null
    }

    private fun buildIpv4Udp(
        source: InetAddress,
        destination: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLength = IPV4_MIN_HEADER + UDP_HEADER + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45 // version 4, 5 words of header
        packet[1] = 0
        writeUShort(packet, 2, totalLength)
        writeUShort(packet, 4, 0) // identification
        writeUShort(packet, 6, 0x4000) // don't fragment
        packet[8] = 64 // ttl
        packet[9] = PROTOCOL_UDP.toByte()
        System.arraycopy(source.address, 0, packet, 12, 4)
        System.arraycopy(destination.address, 0, packet, 16, 4)
        writeUShort(packet, 10, checksum(packet, 0, IPV4_MIN_HEADER))

        val udpOffset = IPV4_MIN_HEADER
        writeUShort(packet, udpOffset, sourcePort)
        writeUShort(packet, udpOffset + 2, destinationPort)
        writeUShort(packet, udpOffset + 4, UDP_HEADER + payload.size)
        System.arraycopy(payload, 0, packet, udpOffset + UDP_HEADER, payload.size)
        writeUShort(
            packet,
            udpOffset + 6,
            udpChecksum(packet, udpOffset, UDP_HEADER + payload.size, source, destination),
        )
        return packet
    }

    private fun buildIpv6Udp(
        source: InetAddress,
        destination: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = UDP_HEADER + payload.size
        val packet = ByteArray(IPV6_HEADER + udpLength)

        packet[0] = 0x60 // version 6
        writeUShort(packet, 4, udpLength)
        packet[6] = PROTOCOL_UDP.toByte()
        packet[7] = 64 // hop limit
        System.arraycopy(source.address, 0, packet, 8, 16)
        System.arraycopy(destination.address, 0, packet, 24, 16)

        val udpOffset = IPV6_HEADER
        writeUShort(packet, udpOffset, sourcePort)
        writeUShort(packet, udpOffset + 2, destinationPort)
        writeUShort(packet, udpOffset + 4, udpLength)
        System.arraycopy(payload, 0, packet, udpOffset + UDP_HEADER, payload.size)
        // The UDP checksum is mandatory over IPv6 — zero is not a valid value.
        writeUShort(
            packet,
            udpOffset + 6,
            udpChecksum(packet, udpOffset, udpLength, source, destination),
        )
        return packet
    }

    private fun udpChecksum(
        packet: ByteArray,
        udpOffset: Int,
        udpLength: Int,
        source: InetAddress,
        destination: InetAddress,
    ): Int {
        var sum = 0L

        // Pseudo-header: addresses, protocol and UDP length.
        for (address in listOf(source.address, destination.address)) {
            var i = 0
            while (i < address.size) {
                sum += ((address[i].toInt() and 0xFF) shl 8) or (address[i + 1].toInt() and 0xFF)
                i += 2
            }
        }
        sum += PROTOCOL_UDP.toLong()
        sum += udpLength.toLong()

        sum += sumWords(packet, udpOffset, udpLength)

        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val result = (sum.inv() and 0xFFFF).toInt()
        // A computed checksum of zero is transmitted as all-ones, since zero
        // means "no checksum" in IPv4 and is illegal in IPv6.
        return if (result == 0) 0xFFFF else result
    }

    private fun checksum(packet: ByteArray, offset: Int, length: Int): Int {
        var sum = sumWords(packet, offset, length)
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun sumWords(packet: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8
        return sum
    }

    internal fun readUShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    internal fun writeUShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }
}
