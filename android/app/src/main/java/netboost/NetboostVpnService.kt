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

        // Cache: open-addressing hash table, stores raw DNS response bytes
        val cKeys = IntArray(65536) { -1 }
        val cData = arrayOfNulls<ByteArray>(65536)

        // Dual upstream sockets for racing
        val up1 = DatagramSocket(); protect(up1)
        up1.connect(InetSocketAddress("1.1.1.1", 53)); up1.soTimeout = 2500
        val up2 = DatagramSocket(); protect(up2)
        up2.connect(InetSocketAddress("8.8.8.8", 53)); up2.soTimeout = 2500

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

                // Build hash key from question name bytes
                var hash = 0
                var qi = uo + 8 + 12
                while (qi < uo + 8 + dl) {
                    val l = buf[qi].toInt() and 0xFF
                    if (l == 0) break
                    for (j in 0..l) { val b = buf[qi + j].toInt() and 0xFF; hash = hash * 31 + b }
                    qi += 1 + l
                }
                val qtype = ((buf[qi+1].toInt() and 0xFF) shl 8) or (buf[qi+2].toInt() and 0xFF)
                hash = hash xor qtype
                if (hash == -1) hash = 0
                val idx = hash and 65535

                // Check cache (open addressing, probe up to 8 slots)
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
                    hits_++
                    rw = hit!!
                } else {
                    misses_++
                    val wire = ByteArray(dl)
                    System.arraycopy(buf, uo + 8, wire, 0, dl)

                    // Race: send on both upstreams, take first response
                    up1.send(DatagramPacket(wire, wire.size))
                    up2.send(DatagramPacket(wire, wire.size))

                    val rp1 = DatagramPacket(rBuf, rBuf.size)
                    val rp2 = DatagramPacket(rBuf, rBuf.size)
                    var resp: ByteArray? = null

                    try { up1.receive(rp1); val d = ByteArray(rp1.length); System.arraycopy(rp1.data, rp1.offset, d, 0, rp1.length); resp = d } catch (_: Exception) {}
                    if (resp == null) try { up2.receive(rp2); val d = ByteArray(rp2.length); System.arraycopy(rp2.data, rp2.offset, d, 0, rp2.length); resp = d } catch (_: Exception) {}
                    if (resp == null) { // both timed out, retry with one
                        try { up1.receive(DatagramPacket(rBuf, rBuf.size)) } catch (_: Exception) {}
                        try { val d = ByteArray(rp2.length); System.arraycopy(rp2.data, rp2.offset, d, 0, rp2.length); resp = d } catch (_: Exception) {}
                    }
                    if (resp == null) continue

                    rw = resp!!
                    // Check answer count before caching
                    if (((rw[6].toInt() and 0xFF) shl 8 or (rw[7].toInt() and 0xFF)) > 0) {
                        cKeys[insertSlot] = hash
                        cData[insertSlot] = rw
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

            } catch (_: java.net.SocketTimeoutException) {}
            catch (_: InterruptedException) { break }
            catch (_: Exception) { if (running) try { Thread.sleep(10) } catch(_:Exception){break} }
        }
        up1.close(); up2.close()
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

    companion object {
        @Volatile var hits_ = 0
        @Volatile var misses_ = 0
    }
}
