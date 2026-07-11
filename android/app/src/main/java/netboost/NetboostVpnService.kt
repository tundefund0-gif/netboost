package netboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class NetboostVpnService : VpnService() {

    private var fd: ParcelFileDescriptor? = null
    @Volatile private var running = false

    // Shared cache (pre-warm writes, loop reads/writes)
    private val cKeys = IntArray(65536) { -1 }
    private val cData = arrayOfNulls<ByteArray>(65536)

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("n", "Netboost", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
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
        if (fd == null) { note("VPN denied"); stopSelf(); return START_NOT_STICKY }
        running = true
        note("Ready 0h 0m")
        thread(name="w") { prewarm() }
        thread(name="t") { loop() }
        thread(name="n") { while(running){try{notifyNow("${hits.get()}h ${misses.get()}m");Thread.sleep(3000)}catch(_:Exception){break}} }
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

    private fun prewarm() {
        try {
            val s = DatagramSocket()
            protect(s)
            s.connect(InetSocketAddress("1.1.1.1", 53))
            s.soTimeout = 2000
            // Wire-format domains: length-byte + chars per label, all concatenated (no trailing dot)
            val domains = listOf(
                byteArrayOf(6,103,111,111,103,108,101, 3,99,111,109),             // google.com
                byteArrayOf(6,121,111,117,116,117,98,101, 3,99,111,109),          // youtube.com
                byteArrayOf(8,102,97,99,101,98,111,111,107, 3,99,111,109),        // facebook.com
                byteArrayOf(9,119,105,107,105,112,101,100,105,97, 3,111,114,103), // wikipedia.org
                byteArrayOf(6,97,109,97,122,111,110, 3,99,111,109)                // amazon.com
            )
            val q = ByteArray(256)
            for (d in domains) {
                if (!running) break
                var pos = 0
                val id = (System.nanoTime() and 0xFFFF).toInt()
                q[pos++] = (id shr 8).toByte(); q[pos++] = id.toByte()
                q[pos++] = 1; q[pos++] = 0  // flags: RD
                q[pos++] = 0; q[pos++] = 1  // QDCOUNT = 1
                q[pos++] = 0; q[pos++] = 0; q[pos++] = 0; q[pos++] = 0
                q[pos++] = 0; q[pos++] = 0
                System.arraycopy(d, 0, q, pos, d.size); pos += d.size
                q[pos++] = 0                // root label
                q[pos++] = 0; q[pos++] = 1  // QTYPE A
                q[pos++] = 0; q[pos++] = 1  // QCLASS IN
                try {
                    s.send(DatagramPacket(q, pos))
                    val rp = DatagramPacket(ByteArray(2048), 2048)
                    s.receive(rp)
                    if (u16(rp.data, 6) > 0) { // ANCOUNT > 0
                        val rw = ByteArray(rp.length)
                        System.arraycopy(rp.data, rp.offset, rw, 0, rp.length)
                        val h = hashBytes(d, 0, d.size) xor 1
                        val idx = h and 65535
                        cData[idx] = rw; cKeys[idx] = h
                    }
                } catch (_: Exception) { if (!running) break }
            }
            s.close()
            if (running) notifyNow("Ready ${hits.get()}h ${misses.get()}m")
        } catch (_: Exception) {}
    }

    private fun hashBytes(b: ByteArray, off: Int, len: Int): Int {
        var h = 0
        val end = off + len
        for (i in off until end) h = h * 31 + (b[i].toInt() and 0xFF)
        return h and 0x7FFFFFFF
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun loop() {
        val input = FileInputStream(fd!!.fileDescriptor)
        val output = FileOutputStream(fd!!.fileDescriptor)
        val buf = ByteArray(65535)

        val up1 = DatagramSocket().also { protect(it); it.connect(InetSocketAddress("1.1.1.1", 53)); it.soTimeout = 2500 }
        val up2 = DatagramSocket().also { protect(it); it.connect(InetSocketAddress("8.8.8.8", 53)); it.soTimeout = 2500 }
        val rB1 = ByteArray(4096)
        val rB2 = ByteArray(4096)
        val rp1 = DatagramPacket(rB1, rB1.size)
        val rp2 = DatagramPacket(rB2, rB2.size)

        while (running) {
            try {
                val n = input.read(buf)
                if (n <= 0) break
                if ((buf[0].toInt() and 0xF0) != 0x40) continue
                val ihl = (buf[0].toInt() and 0x0F) * 4
                if (ihl < 20 || buf[9].toInt() != 17) continue
                if (u16(buf, 2) > n) continue
                val uo = ihl
                if (u16(buf, uo + 2) != 53) continue
                val ul = u16(buf, uo + 4)
                if (ul < 20) continue
                val dl = ul - 8
                val rrs = uo + 8

                // Hash domain name + qtype
                var hash = 0
                var qi = rrs + 12
                val qEnd = rrs + dl
                while (qi < qEnd) {
                    val l = buf[qi].toInt() and 0xFF
                    if (l == 0) { qi++; break }
                    if (l and 0xC0 == 0xC0) { qi += 2; break }
                    for (j in 0..l) hash = hash * 31 + (buf[qi + j].toInt() and 0xFF)
                    qi += 1 + l
                }
                hash = (hash xor u16(buf, qi)) and 0x7FFFFFFF
                if (hash == -1) hash = 0
                val idx = hash and 65535

                // Cache lookup (open addressing, 8 probes max)
                var hit: ByteArray? = null
                var insertSlot = idx
                var foundEmpty = false
                for (j in 0 until 8) {
                    val s = (idx + j) and 65535
                    val k = cKeys[s]
                    if (k == -1) { if (!foundEmpty) { insertSlot = s; foundEmpty = true }; if (hit != null) break }
                    if (k == hash) { hit = cData[s]; break }
                }
                if (!foundEmpty) insertSlot = idx

                val rw: ByteArray
                if (hit != null) {
                    hits.incrementAndGet()
                    rw = hit
                } else {
                    misses.incrementAndGet()
                    val w = ByteArray(dl)
                    System.arraycopy(buf, rrs, w, 0, dl)
                    val pq = DatagramPacket(w, w.size)
                    up1.send(pq); up2.send(pq)
                    rp1.length = rB1.size; rp2.length = rB2.size
                    var resp: ByteArray? = null
                    try { up1.receive(rp1); resp = rB1.copyOf(rp1.length) } catch (_: Exception) {}
                    if (resp == null) try { up2.receive(rp2); resp = rB2.copyOf(rp2.length) } catch (_: Exception) {}
                    if (resp == null) {
                        up1.soTimeout = 5000
                        try { up1.receive(rp1); resp = rB1.copyOf(rp1.length) } catch (_: Exception) {}
                        if (resp == null) try { up2.receive(rp2); resp = rB2.copyOf(rp2.length) } catch (_: Exception) {}
                        up1.soTimeout = 2500
                    }
                    if (resp == null) continue
                    rw = resp
                    if (u16(rw, 6) > 0) { cKeys[insertSlot] = hash; cData[insertSlot] = rw }
                }

                val nu = 8 + rw.size
                val nl = ihl + nu
                val p = ByteArray(nl)
                System.arraycopy(buf, 0, p, 0, ihl)
                p[12] = buf[16]; p[13] = buf[17]; p[14] = buf[18]; p[15] = buf[19]
                p[16] = buf[12]; p[17] = buf[13]; p[18] = buf[14]; p[19] = buf[15]
                p[2] = (nl shr 8).toByte(); p[3] = nl.toByte()
                p[10] = 0; p[11] = 0
                p[uo] = buf[uo + 2]; p[uo + 1] = buf[uo + 3]
                p[uo + 2] = buf[uo]; p[uo + 3] = buf[uo + 1]
                p[uo + 4] = (nu shr 8).toByte(); p[uo + 5] = nu.toByte()
                p[uo + 6] = 0; p[uo + 7] = 0
                System.arraycopy(rw, 0, p, uo + 8, rw.size)

                var sum = 0
                for (i in 0..19 step 2) sum += u16(p, i)
                while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
                val ck = (sum.inv() and 0xFFFF)
                p[10] = (ck shr 8).toByte(); p[11] = ck.toByte()

                output.write(p); output.flush()

            } catch (_: SocketTimeoutException) {}
            catch (_: InterruptedException) { break }
            catch (_: Exception) { if (running) try { Thread.sleep(10) } catch(_:Exception){break} }
        }
        up1.close(); up2.close()
    }

    private fun notifyNow(text: String) {
        try {
            val pi = PendingIntent.getService(this, 0,
                Intent(this, NetboostVpnService::class.java).apply { action = "S" },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, "n")
                .setContentTitle("Netboost").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi).build()
            getSystemService(NotificationManager::class.java).notify(1, n)
        } catch (_: Exception) {}
    }

    private fun note(text: String): Notification {
        notifyNow(text)
        return NotificationCompat.Builder(this, "n")
            .setContentTitle("Netboost").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search).build()
    }

    companion object {
        val hits = AtomicInteger()
        val misses = AtomicInteger()
    }
}
