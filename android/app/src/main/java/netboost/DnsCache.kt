package netboost

import org.xbill.DNS.Message
import java.util.LinkedHashMap

data class CacheEntry(
    val response: Message,
    val createdAt: Long
)

class DnsCache(private val maxSize: Int = 10000) {

    private val cache = object : LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean {
            return size > maxSize
        }
    }

    fun get(key: String): CacheEntry? {
        return cache[key]?.takeIf { !isExpired(it) }
    }

    fun put(key: String, entry: CacheEntry) {
        cache[key] = entry
    }

    val size: Int get() = cache.size

    fun clear() {
        cache.clear()
    }

    private fun isExpired(entry: CacheEntry): Boolean {
        val now = System.currentTimeMillis()
        val allRecords = entry.response.getSectionArray(org.xbill.DNS.Section.ANSWER)
            .plus(entry.response.getSectionArray(org.xbill.DNS.Section.ADDITIONAL))

        val minTtl = allRecords.minOfOrNull { it.ttl * 1000L }
            ?: (60 * 1000L)

        return (now - entry.createdAt) > minTtl
    }
}
