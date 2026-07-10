package netboost

import org.xbill.DNS.Message

object PacketUtils {

    data class IpHeader(
        val version: Int,
        val ihl: Int,
        val protocol: Int
    )

    fun parseIpHeader(packet: ByteArray, offset: Int = 0): IpHeader {
        val v = packet[offset].toInt() and 0xFF
        val ihl = (v and 0x0F) * 4
        val protocol = packet[offset + 9].toInt() and 0xFF
        return IpHeader(
            version = v shr 4,
            ihl = ihl,
            protocol = protocol
        )
    }

    fun buildDnsResponse(request: ByteArray, reqIhl: Int, dnsResponse: Message): ByteArray {
        val dnsBytes = dnsResponse.toWire()
        val udpLen = 8 + dnsBytes.size
        val totalLen = 20 + udpLen

        val out = ByteArray(totalLen)

        out[0] = 0x45
        out[2] = (totalLen shr 8).toByte()
        out[3] = totalLen.toByte()
        out[8] = 64.toByte()
        out[9] = 17.toByte()

        out[12] = request[16]
        out[13] = request[17]
        out[14] = request[18]
        out[15] = request[19]

        out[16] = request[12]
        out[17] = request[13]
        out[18] = request[14]
        out[19] = request[15]

        val ipSum = ipChecksum(out, 0, 20)
        out[10] = (ipSum shr 8).toByte()
        out[11] = ipSum.toByte()

        var off = 20
        out[off] = request[reqIhl + 2]
        out[off + 1] = request[reqIhl + 3]
        out[off + 2] = request[reqIhl]
        out[off + 3] = request[reqIhl + 1]
        out[off + 4] = (udpLen shr 8).toByte()
        out[off + 5] = udpLen.toByte()
        out[off + 6] = 0
        out[off + 7] = 0

        System.arraycopy(dnsBytes, 0, out, off + 8, dnsBytes.size)

        return out
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
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
}
