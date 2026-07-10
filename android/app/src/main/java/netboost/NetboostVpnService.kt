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

class NetboostVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var running = false
    private var handlerThread: Thread? = null

    private val resolver = DnsResolver(
        upstream = "1.1.1.1",
        useTls = false
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
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

        running = true
        handlerThread = thread(start = true) {
            handleTun(fd)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        running = false
        handlerThread?.interrupt()
        tunFd?.close()
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleTun(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(65535)

        var lastStatsUpdate = 0L

        while (running) {
            try {
                val len = input.read(buf)
                if (len <= 0) break

                val packet = buf.copyOf(len)

                val ipHeader = PacketUtils.parseIpHeader(packet)
                if (ipHeader.protocol != 17) continue

                val udpHeader = PacketUtils.parseUdpHeader(packet, ipHeader.ihl)
                if (udpHeader.destPort != 53) continue

                val dnsOffset = ipHeader.ihl + 8
                val query = Message(packet.sliceArray(dnsOffset until ipHeader.ihl + udpHeader.length))

                val result = resolver.resolve(query)

                val responsePacket = PacketUtils.buildResponsePacket(packet, result.response)
                output.write(responsePacket)
                output.flush()

                val now = System.currentTimeMillis()
                if (now - lastStatsUpdate > 2000) {
                    lastStatsUpdate = now
                    updateNotification(result.fromCache)
                }

            } catch (_: InterruptedException) {
                break
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) {
                    android.util.Log.e("Netboost", "TUN error", e)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Netboost DNS",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, NetboostVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Netboost DNS")
            .setContentText("Caching DNS: 0 hits / 0 misses")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(fromCache: Boolean) {
        val (hits, misses) = resolver.getStats()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Netboost DNS")
            .setContentText("$hits hits / $misses misses")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val VPN_IP = "198.18.0.1"
        private const val CHANNEL_ID = "netboost_dns"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "netboost.STOP"
    }
}
