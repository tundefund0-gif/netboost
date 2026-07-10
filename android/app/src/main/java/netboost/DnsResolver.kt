package netboost

import org.xbill.DNS.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class DnsResolver(
    private val upstream: String = "1.1.1.1",
    private val useTls: Boolean = false
) {
    private val dnsCache = DnsCache()
    private var statsHits = 0
    private var statsMisses = 0

    data class ResolveResult(
        val response: Message,
        val fromCache: Boolean
    )

    fun resolve(query: Message): ResolveResult {
        val question = query.question
        val cacheKey = buildCacheKey(question)

        dnsCache.get(cacheKey)?.let {
            statsHits++
            return ResolveResult(it.response, fromCache = true)
        }

        statsMisses++
        val response = if (useTls) resolveOverTls(query) else resolveOverUdp(query)

        val answer = response.getSectionArray(Section.ANSWER)
        if (answer.isNotEmpty()) {
            dnsCache.put(cacheKey, CacheEntry(response, System.currentTimeMillis()))
        }

        return ResolveResult(response, fromCache = false)
    }

    fun getStats() = Pair(statsHits, statsMisses)

    private fun resolveOverUdp(query: Message): Message {
        val socket = DatagramSocket()
        socket.soTimeout = 5000
        val queryBytes = query.toWire()

        val packet = DatagramPacket(
            queryBytes, queryBytes.size,
            InetSocketAddress(upstream, 53)
        )
        socket.send(packet)

        val buf = ByteArray(4096)
        val response = DatagramPacket(buf, buf.size)
        socket.receive(response)

        socket.close()
        return Message(response.data)
    }

    private fun resolveOverTls(query: Message): Message {
        val factory = SocketFactory.getDefault()
        val socket = factory.createSocket(upstream, 853) as SSLSocket
        socket.soTimeout = 5000
        socket.startHandshake()

        val queryBytes = query.toWire()
        val lenPrefixed = ByteArray(2 + queryBytes.size)
        lenPrefixed[0] = (queryBytes.size shr 8).toByte()
        lenPrefixed[1] = queryBytes.size.toByte()
        System.arraycopy(queryBytes, 0, lenPrefixed, 2, queryBytes.size)

        val out = socket.getOutputStream()
        out.write(lenPrefixed)
        out.flush()

        val input = java.io.DataInputStream(socket.getInputStream())
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

    private fun buildCacheKey(question: Question): String {
        return "${question.name}:${question.type}:${question.dClass}"
    }
}
