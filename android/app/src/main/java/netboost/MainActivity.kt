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

    private lateinit var status: TextView
    private lateinit var btn: Button
    private val h = Handler(Looper.getMainLooper())
    private var r: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Netboost\nDNS Cache"; gravity = 1; textSize = 18f }
        btn = Button(this).apply { text = "Start"; setOnClickListener {
            if (text == "Stop") {
                stopService(Intent(this@MainActivity, NetboostVpnService::class.java))
                setStatus(false)
            } else {
                val i = VpnService.prepare(this@MainActivity)
                if (i != null) startActivityForResult(i, 100) else startVpn()
            }
        }}
        val ll = android.widget.LinearLayout(this).apply {
            orientation = 1; gravity = 17; setPadding(32, 0, 32, 0)
            addView(status)
            addView(btn, android.widget.LinearLayout.LayoutParams(280, 120).apply { setMargins(0, 32, 0, 0) })
        }
        setContentView(ll)
    }

    override fun onResume() {
        super.onResume()
        val active = VpnService.prepare(this) == null
        setStatus(active)
        if (active) startRefresh()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
    }

    override fun onPause() { super.onPause(); r?.let { h.removeCallbacks(it) }; r = null }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 100 && res == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        startForegroundService(Intent(this, NetboostVpnService::class.java))
        setStatus(true); startRefresh()
    }

    private fun startRefresh() {
        r = Runnable {
            status.text = "${NetboostVpnService.hits_}h ${NetboostVpnService.misses_}m"
            h.postDelayed(r!!, 2000)
        }; h.post(r!!)
    }

    private fun setStatus(active: Boolean) {
        status.text = if (active) "0h 0m" else "Inactive"
        status.setTextColor(if (active) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
        btn.text = if (active) "Stop" else "Start"
    }
}
