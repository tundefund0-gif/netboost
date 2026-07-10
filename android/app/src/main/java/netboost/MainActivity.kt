package netboost

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var toggleButton: Button
    private var isRunning = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        toggleButton = findViewById(R.id.toggleButton)

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopService(Intent(this, NetboostVpnService::class.java).apply {
                    action = NetboostVpnService.ACTION_STOP
                })
                isRunning = false
                updateUI()
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, VPN_REQUEST_CODE)
                } else {
                    startVpn()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isRunning = NetboostVpnService::class.java.name.let {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(Integer.MAX_VALUE).any { s ->
                s.service.className == NetboostVpnService::class.java.name
            }
        }
        updateUI()

        if (isRunning) {
            updateRunnable = Runnable {
                updateUI()
                uiHandler.postDelayed(updateRunnable!!, 2000)
            }
            uiHandler.post(updateRunnable!!)
        }
    }

    override fun onPause() {
        super.onPause()
        updateRunnable?.let { uiHandler.removeCallbacks(it) }
        updateRunnable = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpn()
            }
        }
    }

    private fun startVpn() {
        startForegroundService(Intent(this, NetboostVpnService::class.java))
        isRunning = true
        updateUI()
        updateRunnable = Runnable {
            updateUI()
            uiHandler.postDelayed(updateRunnable!!, 2000)
        }
        uiHandler.post(updateRunnable!!)
    }

    private fun updateUI() {
        if (isRunning) {
            statusText.text = "Running \u2022 DNS at $VPN_IP:53"
            statusText.setTextColor(0xFF4CAF50.toInt())
            toggleButton.text = "Stop"
            statsText.text = "..."
        } else {
            statusText.text = "Inactive"
            statusText.setTextColor(0xFF888888.toInt())
            toggleButton.text = "Start"
            statsText.text = ""
        }
    }

    companion object {
        private const val VPN_REQUEST_CODE = 100
        private const val VPN_IP = "198.18.0.1"
    }
}
