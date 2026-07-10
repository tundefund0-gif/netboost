package netboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import org.xbill.DNS.Message
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class NetboostVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    private val resolver = DnsResolver()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (tunFd != null) return START_STICKY

        val builder = Builder()
        builder.setSession("Netboost DNS")
        builder.setMtu(1500)
        builder.addAddress(VPN_IP, 32)
        builder.addRoute(VPN_IP, 32)
        builder.addDnsServer(VPN_IP)

        val fd = builder.establish()
        if (fd == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        tunFd = fd
        running.set(true)

        workerThread = thread(start = true, name = "tun-reader") {
            processTun(fd)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        running.set(false)
        workerThread?.interrupt()
        tunFd?.close()
        tunFd = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun processTun(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(65535)

        var lastStats = 0L
        resolver.resetStats()
        updateNotification("0 hits / 0 misses")

        while (running.get()) {
            try {
                val len = input.read(buf)
                if (len <= 0) break

                val ipHdr = PacketUtils.parseIpHeader(buf, 0)
                if (ipHdr.protocol != 17 || ipHdr.ihl < 20) continue

                val udpOff = ipHdr.ihl
                val dport = ((buf[udpOff + 2].toInt() and 0xFF) shl 8) or
                        (buf[udpOff + 3].toInt() and 0xFF)
                if (dport != 53) continue

                val udpLen = ((buf[udpOff + 4].toInt() and 0xFF) shl 8) or
                        (buf[udpOff + 5].toInt() and 0xFF)
                val dnsStart = udpOff + 8
                val dnsLen = udpLen - 8
                if (dnsLen <= 0) continue

                val queryBytes = buf.copyOfRange(dnsStart, dnsStart + dnsLen)
                val query = Message(queryBytes)
                val result = resolver.resolve(query)

                val responsePkt = PacketUtils.buildDnsResponse(buf, ipHdr.ihl, result.response)
                output.write(responsePkt)
                output.flush()

                val now = System.nanoTime()
                if (now - lastStats > 2_000_000_000L) {
                    lastStats = now
                    val (h, m) = resolver.getStats()
                    statsHits = h
                    statsMisses = m
                    updateNotification("$h hits / $m misses")
                }

            } catch (_: InterruptedException) {
                break
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running.get()) {
                    android.util.Log.e("Netboost", "TUN error", e)
                }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Netboost DNS", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, NetboostVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Netboost DNS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val VPN_IP = "198.18.0.1"
        private const val CHANNEL_ID = "netboost_dns"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "netboost.STOP"
        @Volatile
        var statsHits = 0
        @Volatile
        var statsMisses = 0
    }
}
