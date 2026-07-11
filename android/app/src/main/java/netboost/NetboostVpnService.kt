package netboost

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import org.xbill.DNS.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*
import kotlin.concurrent.thread

class NetboostVpnService : VpnService() {

    private var fd: ParcelFileDescriptor? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        val c = NotificationChannel("n", "Netboost", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        startForeground(1, note("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "S") { stop(); return START_NOT_STICKY }
        if (fd != null) return START_STICKY

        val ip = InetAddress.getByName("10.0.0.1")
        val b = Builder()
        b.setSession("Netboost")
        b.setMtu(1500)
        b.addAddress(ip, 24)
        b.addRoute(ip, 32)
        b.addDnsServer(ip)

        fd = b.establish()
        if (fd == null) { note("VPN failed"); stopSelf(); return START_NOT_STICKY }

        running = true
        note("Waiting for DNS...")

        thread(name = "tun") { loop(fd!!) }

        thread(name = "notif") {
            while (running) {
                try { note("${hits_}h ${misses_}m"); Thread.sleep(3000) } catch (_: Exception) { break }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { stop(); super.onDestroy() }

    private fun stop() {
        running = false
        try { fd?.close() } catch (_: Exception) {}
        fd = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(65535)
        val cache = LruCache<String, Message>(10000)

        while (running) {
            try {
                val n = input.read(buf)
                if (n <= 0) break

                if (buf[0].toInt() != 0x45) continue
                val ihl = 20
                if (buf[9].toInt() != 17) continue

                val udpOff = ihl
                val dport = ((buf[udpOff+2].toInt() and 0xFF) shl 8) or (buf[udpOff+3].toInt() and 0xFF)
                if (dport != 53) continue

                val udpLen = ((buf[udpOff+4].toInt() and 0xFF) shl 8) or (buf[udpOff+5].toInt() and 0xFF)
                val dnsLen = udpLen - 8
                if (dnsLen <= 0) continue

                val qb = ByteArray(dnsLen)
                System.arraycopy(buf, udpOff + 8, qb, 0, dnsLen)
                val query = Message(qb)
                val q = query.getQuestion()
                val key = q.name.toString() + q.type

                val cached = cache.get(key)
                val respMsg: Message
                if (cached != null) {
                    hits_++
                    respMsg = cached
                } else {
                    misses_++
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
                    val parsed = Message(rd)
                    respMsg = parsed
                    if (parsed.getSectionArray(Section.ANSWER).isNotEmpty())
                        cache.put(key, parsed)
                }

                val rw = respMsg.toWire()
                val nu = 8 + rw.size
                val nl = ihl + nu
                val resp = ByteArray(nl)

                System.arraycopy(buf, 0, resp, 0, ihl)
                resp[12] = buf[16]; resp[13] = buf[17]
                resp[14] = buf[18]; resp[15] = buf[19]
                resp[16] = buf[12]; resp[17] = buf[13]
                resp[18] = buf[14]; resp[19] = buf[15]
                resp[2] = (nl shr 8).toByte(); resp[3] = nl.toByte()
                resp[10] = 0; resp[11] = 0
                resp[udpOff] = buf[udpOff+2]; resp[udpOff+1] = buf[udpOff+3]
                resp[udpOff+2] = buf[udpOff]; resp[udpOff+3] = buf[udpOff+1]
                resp[udpOff+4] = (nu shr 8).toByte(); resp[udpOff+5] = nu.toByte()
                resp[udpOff+6] = 0; resp[udpOff+7] = 0
                System.arraycopy(rw, 0, resp, udpOff + 8, rw.size)

                var sum = 0
                for (i in 0..19 step 2)
                    sum += ((resp[i].toInt() and 0xFF) shl 8) or (resp[i+1].toInt() and 0xFF)
                while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
                val ck = (sum.inv() and 0xFFFF)
                resp[10] = (ck shr 8).toByte(); resp[11] = ck.toByte()

                output.write(resp)
                output.flush()

            } catch (_: java.net.SocketTimeoutException) {}
            catch (_: InterruptedException) { break }
            catch (_: Exception) { if (running) Thread.sleep(50) }
        }
    }

    private fun note(text: String) {
        try {
            val pi = PendingIntent.getService(this, 0,
                Intent(this, NetboostVpnService::class.java).apply { action = "S" },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            NotificationCompat.Builder(this, "n")
                .setContentTitle("Netboost").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi)
                .build().let { getSystemService(NotificationManager::class.java).notify(1, it) }
        } catch (_: Exception) {}
    }

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
