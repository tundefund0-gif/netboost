package netboost

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
    private val cKeys = IntArray(16384) { -1 }
    private val cData = arrayOfNulls<ByteArray>(16384)

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("n", "Netboost", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        startForeground(1, note("Start"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "S") { stop(); return START_NOT_STICKY }
        if (fd != null) return START_STICKY
        val ip = InetAddress.getByName("10.0.0.1")
        val b = Builder()
        b.setSession("N")
        b.setMtu(10000)
        b.addAddress(ip, 24)
        b.addRoute(ip, 32)
        b.addDnsServer(ip)
        fd = b.establish()
        if (fd == null) { note("Fail"); stopSelf(); return START_NOT_STICKY }
        running = true
        note("Ready")
        thread(name="t") { loop() }
        thread(name="n") { while(running){try{note("${hits.get()}h ${misses.get()}m");Thread.sleep(3000)}catch(_:Exception){break}} }
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

    private fun u16(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun loop() {
        val input = FileInputStream(fd!!.fileDescriptor)
        val output = FileOutputStream(fd!!.fileDescriptor)
        val buf = ByteArray(65535)
        val u = DatagramSocket()
        protect(u)
        u.connect(InetSocketAddress("1.1.1.1", 53))
        u.soTimeout = 2000
        val rBuf = ByteArray(4096)
        val rPkt = DatagramPacket(rBuf, rBuf.size)

        while (running) {
            try {
                val n = input.read(buf); if (n <= 0) break
                if ((buf[0].toInt() and 0xF0) != 0x40) continue
                val ihl = (buf[0].toInt() and 0x0F) * 4
                if (ihl != 20 || buf[9].toInt() != 17) continue
                if (u16(buf, 2) < ihl + 20 || u16(buf, 2) > n) continue
                val uo = ihl
                if (u16(buf, uo + 2) != 53) continue
                val dl = u16(buf, uo + 4) - 8
                if (dl < 12 || dl > 4000) continue

                var hash = 0
                var qi = uo + 8 + 12
                val qe = uo + 8 + dl
                while (qi < qe) {
                    val lb = buf[qi].toInt() and 0xFF
                    if (lb == 0) { qi++; break }
                    if (lb and 0xC0 == 0xC0) { qi += 2; break }
                    for (j in 0..lb) hash = hash * 31 + (buf[qi + j].toInt() and 0xFF)
                    qi += 1 + lb
                }
                hash = (hash xor u16(buf, qi)) and 0x7FFF
                val idx = hash and 0x3FFF

                var hit: ByteArray? = null
                for (j in 0 until 4) {
                    val s = (idx + j) and 0x3FFF
                    val k = cKeys[s]
                    if (k == hash) { hit = cData[s]; break }
                    if (k == -1) break
                }

                val rw: ByteArray
                if (hit != null) {
                    hits.incrementAndGet()
                    rw = hit
                } else {
                    misses.incrementAndGet()
                    val w = ByteArray(dl)
                    System.arraycopy(buf, uo + 8, w, 0, dl)
                    u.send(DatagramPacket(w, w.size))
                    rPkt.length = rBuf.size
                    u.receive(rPkt)
                    rw = rBuf.copyOf(rPkt.length)
                    if (rw.size < 12) continue
                    if (u16(rw, 6) > 0) {
                        var ins = idx
                        for (j in 0 until 4) {
                            val s = (idx + j) and 0x3FFF
                            if (cKeys[s] == -1 || cKeys[s] == hash) { ins = s; break }
                        }
                        cKeys[ins] = hash; cData[ins] = rw
                    }
                }

                val nu = 8 + rw.size; val nl = ihl + nu
                if (nl > 10000) continue
                val p = ByteArray(nl)
                System.arraycopy(buf, 0, p, 0, ihl)
                p[12] = buf[16]; p[13] = buf[17]; p[14] = buf[18]; p[15] = buf[19]
                p[16] = buf[12]; p[17] = buf[13]; p[18] = buf[14]; p[19] = buf[15]
                p[2] = (nl shr 8).toByte(); p[3] = nl.toByte(); p[10] = 0; p[11] = 0
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
            catch (_: Exception) { if (running) try { Thread.sleep(5) } catch(_:Exception){break} }
        }
        u.close()
    }

    private fun note(text: String): android.app.Notification {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, NetboostVpnService::class.java).apply { action = "S" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, "n")
            .setContentTitle("Netboost").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi).build()
        try { getSystemService(NotificationManager::class.java).notify(1, n) } catch (_: Exception) {}
        return n
    }

    companion object {
        val hits = AtomicInteger()
        val misses = AtomicInteger()
    }
}
