package netboost

import org.xbill.DNS.Message
import org.xbill.DNS.Section
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

    @Synchronized
    fun get(key: String): CacheEntry? {
        val entry = cache[key] ?: return null
        if (isExpired(entry)) {
            cache.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: String, entry: CacheEntry) {
        cache[key] = entry
    }

    @Synchronized
    val size: Int get() = cache.size

    private fun isExpired(entry: CacheEntry): Boolean {
        val now = System.currentTimeMillis()
        val answerRecords = entry.response.getSectionArray(Section.ANSWER)
        val additionalRecords = entry.response.getSectionArray(Section.ADDITIONAL)
        val allRecords = answerRecords + additionalRecords
        val minTtl = if (allRecords.isEmpty()) {
            60_000L
        } else {
            allRecords.minOf { it.ttl * 1000L }
        }
        return (now - entry.createdAt) > minTtl
    }
}
