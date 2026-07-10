package netboost

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import org.xbill.DNS.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.LinkedHashMap
import kotlin.concurrent.thread

class NetboostVpnService : VpnService() {

    private var fd: ParcelFileDescriptor? = null
    private var running = true

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("n", "Netboost", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        startForeground(1, notif("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stop(); return START_NOT_STICKY }
        if (fd != null) return START_STICKY

        val b = Builder().apply {
            setSession("Netboost")
            setMtu(1500)
            addAddress("10.0.0.1", 24)
            addRoute("10.0.0.1", 32)
            addDnsServer("10.0.0.1")
        }
        fd = b.establish() ?: run { stopSelf(); return START_NOT_STICKY }
        running = true
        thread(name = "tun") { loop(fd!!) }
        return START_STICKY
    }

    override fun onDestroy() { stop(); super.onDestroy() }

    private fun stop() {
        running = false
        try { fd?.close() } catch (_: Exception) {}
        fd = null
        try { stopForeground(1) } catch (_: Exception) {}
        stopSelf()
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(65535)
        var cache = LruCache<String, Message>(10000)
        var lastT = 0L
        var hits = 0L; var misses = 0L

        while (running) {
            try {
                val n = input.read(buf)
                if (n <= 0) break
                val ihl = (buf[0].toInt() and 0x0F) * 4
                if (buf[9].toInt() != 17 || ihl < 20) continue
                val udpOff = ihl
                val dport = ((buf[udpOff+2].toInt() and 0xFF) shl 8) or (buf[udpOff+3].toInt() and 0xFF)
                if (dport != 53) continue
                val udpLen = ((buf[udpOff+4].toInt() and 0xFF) shl 8) or (buf[udpOff+5].toInt() and 0xFF)
                val dnsLen = udpLen - 8; if (dnsLen <= 0) continue

                val qBytes = ByteArray(dnsLen)
                System.arraycopy(buf, udpOff + 8, qBytes, 0, dnsLen)
                val query = Message(qBytes)
                val q = query.getQuestion()
                val key = "${q.name}:${q.type}"

                val cached = cache.get(key)
                val respMsg: Message

                if (cached != null) {
                    hits++
                    respMsg = cached
                } else {
                    misses++
                    val sock = DatagramSocket()
                    protect(sock)
                    sock.soTimeout = 5000
                    val wire = query.toWire()
                    sock.send(DatagramPacket(wire, wire.size, InetSocketAddress("1.1.1.1", 53)))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    sock.receive(rp)
                    sock.close()
                    val rd = ByteArray(rp.length)
                    System.arraycopy(rp.data, rp.offset, rd, 0, rp.length)
                    respMsg = Message(rd)
                    if (respMsg.getSectionArray(Section.ANSWER).isNotEmpty()) {
                        cache.put(key, respMsg)
                    }
                }

                val rBytes = respMsg.toWire()
                val newUdpLen = 8 + rBytes.size
                val newLen = ihl + newUdpLen
                val resp = ByteArray(newLen)

                System.arraycopy(buf, 0, resp, 0, ihl)

                resp[12] = buf[16]; resp[13] = buf[17]
                resp[14] = buf[18]; resp[15] = buf[19]
                resp[16] = buf[12]; resp[17] = buf[13]
                resp[18] = buf[14]; resp[19] = buf[15]

                resp[2] = (newLen shr 8).toByte(); resp[3] = newLen.toByte()
                resp[10] = 0; resp[11] = 0

                resp[udpOff] = buf[udpOff+2]; resp[udpOff+1] = buf[udpOff+3]
                resp[udpOff+2] = buf[udpOff]; resp[udpOff+3] = buf[udpOff+1]
                resp[udpOff+4] = (newUdpLen shr 8).toByte(); resp[udpOff+5] = newUdpLen.toByte()
                resp[udpOff+6] = 0; resp[udpOff+7] = 0

                System.arraycopy(rBytes, 0, resp, udpOff + 8, rBytes.size)

                var sum = 0
                for (i in 0..19 step 2)
                    sum += ((resp[i].toInt() and 0xFF) shl 8) or (resp[i+1].toInt() and 0xFF)
                while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
                val ck = (sum.inv() and 0xFFFF)
                resp[10] = (ck shr 8).toByte(); resp[11] = ck.toByte()

                output.write(resp)
                output.flush()

                val t = System.nanoTime()
                if (t - lastT > 2_500_000_000L) {
                    lastT = t
                    hits_ = hits.toInt(); misses_ = misses.toInt()
                    notif("${hits}h ${misses}m")
                }
            } catch (_: java.net.SocketTimeoutException) { }
            catch (_: InterruptedException) { break }
            catch (_: Exception) { if (running) Thread.sleep(100) }
        }
    }

    private fun notif(text: String) = NotificationCompat.Builder(this, "n")
        .setContentTitle("Netboost").setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop",
            PendingIntent.getService(this, 0, Intent(this, NetboostVpnService::class.java).apply { action = "STOP" },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build().also { getSystemService(NotificationManager::class.java).notify(1, it) }

    private class LruCache<K, V>(private val max: Int) {
        private val map = object : LinkedHashMap<K, V>(max, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        }
        @Synchronized fun get(k: K) = map[k]
        @Synchronized fun put(k: K, v: V) { map[k] = v }
    }

    companion object {
        @Volatile var hits_ = 0
        @Volatile var misses_ = 0
    }
}
