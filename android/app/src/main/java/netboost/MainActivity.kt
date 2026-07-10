package netboost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private var isRunning = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var refreshTask: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopService(Intent(this, NetboostVpnService::class.java).apply {
                    action = NetboostVpnService.ACTION_STOP
                })
                isRunning = false
                updateUi()
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, VPN_REQ)
                } else {
                    startVpn()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isRunning = VpnService.prepare(this) == null
        updateUi()
        if (isRunning) startRefresh()
        requestNotifPerm()
    }

    override fun onPause() {
        super.onPause()
        refreshTask?.let { uiHandler.removeCallbacks(it) }
        refreshTask = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQ && resultCode == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        startForegroundService(Intent(this, NetboostVpnService::class.java))
        isRunning = true
        updateUi()
        startRefresh()
    }

    private fun startRefresh() {
        refreshTask = Runnable {
            val hits = NetboostVpnService.statsHits
            val misses = NetboostVpnService.statsMisses
            runOnUiThread {
                statusText.text = "Running \u2022 ${hits}h ${misses}m"
            }
            uiHandler.postDelayed(refreshTask!!, 2000)
        }
        uiHandler.post(refreshTask!!)
    }

    private fun requestNotifPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERM
                )
            }
        }
    }

    private fun updateUi() {
        if (isRunning) {
            statusText.text = "Running at $VPN_IP:53"
            statusText.setTextColor(0xFF4CAF50.toInt())
            toggleButton.text = "Stop"
        } else {
            statusText.text = "Inactive"
            statusText.setTextColor(0xFF888888.toInt())
            toggleButton.text = "Start"
        }
    }

    companion object {
        private const val VPN_REQ = 100
        const val VPN_IP = "198.18.0.1"
        private const val NOTIF_PERM = 200
    }
}
