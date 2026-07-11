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
        startForeground(1, note("Start"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "S") { stop(); return START_NOT_STICKY }
        if (fd != null) return START_STICKY
        val ip = InetAddress.getByName("10.0.0.1")
        val b = Builder()
        b.setSession("N")
        b.setMtu(1500)
        b.addAddress(ip, 24)
        b.addRoute(ip, 32)
        b.addDnsServer(ip)
        fd = b.establish()
        if (fd == null) { stopSelf(); return START_NOT_STICKY }
        running = true
        note("Ready")
        thread(name="t") { loop(fd!!) }
        thread(name="n") { while(running){try{note("${hits_}h ${misses_}m");Thread.sleep(3000)}catch(_:Exception){break}} }
        return START_STICKY
    }

    override fun onDestroy() { stop(); super.onDestroy() }

    private fun stop() {
        running = false
        try { fd?.close() } catch(_: Exception) {}
        fd = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch(_: Exception) {}
        stopSelf()
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(65535)
        val rBuf = ByteArray(4096)
        val cache = Array<CacheSlot?>(16384) { null }
        val up = DatagramSocket()
        protect(up)
        up.soTimeout = 3000
        val upAddr = InetSocketAddress("1.1.1.1", 53)
        var pktCount = 0L

        while (running) {
            try {
                val n = input.read(buf)
                if (n <= 0) break
                if ((buf[0].toInt() and 0xF0) != 0x40) continue
                val ihl = (buf[0].toInt() and 0x0F) * 4
                if (buf[9].toInt() != 17 || ihl < 20) continue
                val uo = ihl
                val dp = ((buf[uo+2].toInt() and 0xFF) shl 8) or (buf[uo+3].toInt() and 0xFF)
                if (dp != 53) continue
                val ul = ((buf[uo+4].toInt() and 0xFF) shl 8) or (buf[uo+5].toInt() and 0xFF)
                val dl = ul - 8; if (dl <= 12) continue
                var qnLen = 0
                var qi = uo + 8 + 12
                var hash = 0
                while (qi < uo + 8 + dl) {
                    val l = buf[qi].toInt() and 0xFF
                    if (l == 0) { qnLen = qi - (uo + 8) + 1; break }
                    hash = hash * 31 + l
                    qi++
                    for (j in 0 until l) {
                        val b = buf[qi + j].toInt() and 0xFF
                        hash = hash * 31 + b
                    }
                    qi += l
                }
                if (qnLen == 0) continue
                val qtype = ((buf[qi+1].toInt() and 0xFF) shl 8) or (buf[qi+2].toInt() and 0xFF)
                val key = (hash xor qtype) and 0x7FFFFFFF

                val si = key % 16384
                var hit: ByteArray? = null
                var ci = si
                var emptySlot = si
                var foundEmpty = false
                for (j in 0 until 32) {
                    val idx = (si + j) % 16384
                    val s = cache[idx]
                    if (s == null) { if (!foundEmpty) { emptySlot = idx; foundEmpty = true }; continue }
                    if (s.key == key) { hit = s.data; ci = idx; break }
                }
                if (hit == null && foundEmpty) ci = emptySlot

                val rw: ByteArray
                if (hit != null) {
                    hits_++; rw = hit
                } else {
                    misses_++
                    val wire = ByteArray(dl)
                    System.arraycopy(buf, uo + 8, wire, 0, dl)
                    up.send(DatagramPacket(wire, wire.size, upAddr))
                    val rp = DatagramPacket(rBuf, rBuf.size)
                    up.receive(rp)
                    val rd = ByteArray(rp.length)
                    System.arraycopy(rp.data, rp.offset, rd, 0, rp.length)
                    rw = rd
                    val answerSec = ((rd[6].toInt() and 0xFF) shl 8) or (rd[7].toInt() and 0xFF)
                    if (answerSec > 0) {
                        cache[ci] = CacheSlot(key, rd)
                    }
                }

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
                resp[uo] = buf[uo+2]; resp[uo+1] = buf[uo+3]
                resp[uo+2] = buf[uo]; resp[uo+3] = buf[uo+1]
                resp[uo+4] = (nu shr 8).toByte(); resp[uo+5] = nu.toByte()
                resp[uo+6] = 0; resp[uo+7] = 0
                System.arraycopy(rw, 0, resp, uo + 8, rw.size)

                var sum = 0
                for (i in 0..19 step 2)
                    sum += ((resp[i].toInt() and 0xFF) shl 8) or (resp[i+1].toInt() and 0xFF)
                while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
                val ck = (sum.inv() and 0xFFFF)
                resp[10] = (ck shr 8).toByte(); resp[11] = ck.toByte()

                output.write(resp)
                output.flush()
                pktCount++

            } catch (_: java.net.SocketTimeoutException) {}
            catch (_: InterruptedException) { break }
            catch (_: Exception) { if (running) try { Thread.sleep(10) } catch(_:Exception){break} }
        }
        up.close()
    }

    private fun note(text: String): android.app.Notification {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, NetboostVpnService::class.java).apply { action = "S" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, "n")
            .setContentTitle("Netboost").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi).build()
        try { getSystemService(NotificationManager::class.java).notify(1, n) } catch(_: Exception) {}
        return n
    }

    private class CacheSlot(val key: Int, val data: ByteArray)

    companion object {
        @Volatile var hits_ = 0
        @Volatile var misses_ = 0
    }
}
