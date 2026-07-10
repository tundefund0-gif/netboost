package netboost

import org.xbill.DNS.*
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class DnsResolver(
    private val upstream: String = "1.1.1.1",
    private val useTls: Boolean = false,
    private val protectSocket: ((java.net.DatagramSocket) -> Unit)? = null
) {
    private val dnsCache = DnsCache()
    @Volatile
    var statsHits = 0
        private set
    @Volatile
    var statsMisses = 0
        private set

    data class ResolveResult(
        val response: Message,
        val fromCache: Boolean
    )

    fun resolve(query: Message): ResolveResult {
        val q = query.getQuestion()
        val cacheKey = "${q.name}:${q.type}:${q.dClass}"

        dnsCache.get(cacheKey)?.let {
            statsHits++
            return ResolveResult(it.response, fromCache = true)
        }

        statsMisses++
        val response = if (useTls) resolveOverTls(query) else resolveOverUdp(query)

        if (response.getSectionArray(Section.ANSWER).isNotEmpty()) {
            dnsCache.put(cacheKey, CacheEntry(response, System.currentTimeMillis()))
        }

        return ResolveResult(response, fromCache = false)
    }

    fun getStats() = Pair(statsHits, statsMisses)

    fun resetStats() {
        statsHits = 0
        statsMisses = 0
    }

    private fun resolveOverUdp(query: Message): Message {
        val socket = DatagramSocket()
        protectSocket?.invoke(socket)
        socket.soTimeout = 5000
        val queryBytes = query.toWire()

        val packet = DatagramPacket(
            queryBytes, queryBytes.size,
            InetSocketAddress(upstream, 53)
        )
        socket.send(packet)

        val buf = ByteArray(4096)
        val reply = DatagramPacket(buf, buf.size)
        socket.receive(reply)

        socket.close()

        val len = reply.length
        val data = ByteArray(len)
        System.arraycopy(reply.data, reply.offset, data, 0, len)
        return Message(data)
    }

    private fun resolveOverTls(query: Message): Message {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket(upstream, 853) as SSLSocket
        socket.soTimeout = 5000
        socket.startHandshake()

        val queryBytes = query.toWire()
        val lenPrefixed = ByteArray(2 + queryBytes.size)
        lenPrefixed[0] = (queryBytes.size shr 8).toByte()
        lenPrefixed[1] = queryBytes.size.toByte()
        System.arraycopy(queryBytes, 0, lenPrefixed, 2, queryBytes.size)

        socket.getOutputStream().write(lenPrefixed)
        socket.getOutputStream().flush()

        val input = DataInputStream(socket.getInputStream())
        val lenBuf = ByteArray(2)
        input.readFully(lenBuf)
        val responseLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)

        val responseBuf = ByteArray(responseLen)
        var offset = 0
        while (offset < responseLen) {
            val read = input.read(responseBuf, offset, responseLen - offset)
            if (read == -1) break
            offset += read
        }

        socket.close()
        return Message(responseBuf)
    }
}
