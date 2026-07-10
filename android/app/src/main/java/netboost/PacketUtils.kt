package netboost

import org.xbill.DNS.Message
import java.net.InetAddress

object PacketUtils {

    data class IpHeader(
        val version: Int,
        val ihl: Int,
        val totalLength: Int,
        val protocol: Int,
        val sourceIp: InetAddress,
        val destIp: InetAddress
    )

    data class UdpHeader(
        val sourcePort: Int,
        val destPort: Int,
        val length: Int
    )

    fun parseIpHeader(packet: ByteArray, offset: Int = 0): IpHeader {
        val versionAndIhl = packet[offset].toInt() and 0xFF
        val ihl = (versionAndIhl and 0x0F) * 4
        val totalLength = ((packet[offset + 2].toInt() and 0xFF) shl 8) or
                (packet[offset + 3].toInt() and 0xFF)
        val protocol = packet[offset + 9].toInt() and 0xFF

        val srcBytes = packet.sliceArray(offset + 12..offset + 15)
        val dstBytes = packet.sliceArray(offset + 16..offset + 19)

        return IpHeader(
            version = (versionAndIhl shr 4),
            ihl = ihl,
            totalLength = totalLength,
            protocol = protocol,
            sourceIp = InetAddress.getByAddress(srcBytes),
            destIp = InetAddress.getByAddress(dstBytes)
        )
    }

    fun parseUdpHeader(packet: ByteArray, offset: Int): UdpHeader {
        val sourcePort = ((packet[offset].toInt() and 0xFF) shl 8) or
                (packet[offset + 1].toInt() and 0xFF)
        val destPort = ((packet[offset + 2].toInt() and 0xFF) shl 8) or
                (packet[offset + 3].toInt() and 0xFF)
        val length = ((packet[offset + 4].toInt() and 0xFF) shl 8) or
                (packet[offset + 5].toInt() and 0xFF)

        return UdpHeader(sourcePort, destPort, length)
    }

    fun buildResponsePacket(
        request: ByteArray,
        dnsResponse: Message
    ): ByteArray {
        val ipHeader = parseIpHeader(request)
        val udpHeader = parseUdpHeader(request, ipHeader.ihl)
        val dnsBytes = dnsResponse.toWire()

        val udpLen = 8 + dnsBytes.size
        val totalLen = ipHeader.ihl + udpLen

        val response = ByteArray(totalLen)

        System.arraycopy(request, 0, response, 0, ipHeader.ihl)

        response[0] = (0x45).toByte()
        response[2] = (totalLen shr 8).toByte()
        response[3] = totalLen.toByte()
        response[4] = 0
        response[5] = 0
        response[6] = 0
        response[7] = 0
        response[8] = 64.toByte()
        response[9] = 17.toByte()

        val srcIp = request.sliceArray(ipHeader.ihl - 8 until ipHeader.ihl - 4)
        val dstIp = request.sliceArray(ipHeader.ihl - 4 until ipHeader.ihl)
        System.arraycopy(dstIp, 0, response, ipHeader.ihl - 8, 4)
        System.arraycopy(srcIp, 0, response, ipHeader.ihl - 4, 4)

        val ipChecksum = computeChecksum(response, 0, ipHeader.ihl)
        response[10] = (ipChecksum shr 8).toByte()
        response[11] = ipChecksum.toByte()

        var off = ipHeader.ihl
        response[off] = (udpHeader.destPort shr 8).toByte()
        response[off + 1] = udpHeader.destPort.toByte()
        response[off + 2] = (udpHeader.sourcePort shr 8).toByte()
        response[off + 3] = udpHeader.sourcePort.toByte()
        response[off + 4] = (udpLen shr 8).toByte()
        response[off + 5] = udpLen.toByte()
        response[off + 6] = 0
        response[off + 7] = 0

        System.arraycopy(dnsBytes, 0, response, off + 8, dnsBytes.size)

        val udpChecksum = computeUdpChecksum(response, ipHeader.ihl, udpLen,
            response.sliceArray(ipHeader.ihl - 8 until ipHeader.ihl - 4),
            response.sliceArray(ipHeader.ihl - 4 until ipHeader.ihl))
        response[off + 6] = (udpChecksum shr 8).toByte()
        response[off + 7] = udpChecksum.toByte()

        return response
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun computeUdpChecksum(
        packet: ByteArray,
        offset: Int,
        udpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Int {
        var sum = 0

        for (i in 0..1) {
            sum += ((srcIp[i * 2].toInt() and 0xFF) shl 8) or (srcIp[i * 2 + 1].toInt() and 0xFF)
        }
        for (i in 0..1) {
            sum += ((dstIp[i * 2].toInt() and 0xFF) shl 8) or (dstIp[i * 2 + 1].toInt() and 0xFF)
        }

        sum += 0x0011

        sum += udpLen

        var i = offset
        while (i < offset + udpLen - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + udpLen) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }

        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }

        return sum.inv() and 0xFFFF
    }
}
