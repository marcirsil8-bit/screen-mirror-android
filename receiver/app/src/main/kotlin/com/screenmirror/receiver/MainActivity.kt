package com.screenmirror.receiver

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val VIDEO_PORT = 50001
        private const val AUDIO_PORT = 50002
    }

    private var streamReceiver: StreamReceiver? = null
    private var audioReceiver: AudioReceiver? = null

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)

        btnStop.isEnabled = false
        tvInfo.text = getDeviceInfo()

        val surfaceView = findViewById<android.view.SurfaceView>(R.id.surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                tvStatus.text = "✅ Pronto - IP acima"
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })

        btnStart.setOnClickListener {
            startReceiving()
        }

        btnStop.setOnClickListener {
            stopReceiving()
        }
    }

    private fun getDeviceInfo(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = intToIp(wm.connectionInfo.ipAddress)
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        return "📡 IP da TV Box: $ip\n" +
               "Resolução: ${dm.widthPixels}x${dm.heightPixels}\n" +
               "Android: ${android.os.Build.VERSION.RELEASE}\n" +
               "Modelo: ${android.os.Build.MODEL}\n\n" +
               "Digite este IP no celular"
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    private fun startReceiving() {
        val surfaceView = findViewById<android.view.SurfaceView>(R.id.surfaceView)
        
        // Start video receiver (TCP server)
        streamReceiver = StreamReceiver(VIDEO_PORT, surfaceView)
        streamReceiver?.start()

        // Start audio receiver (TCP server on next port)
        audioReceiver = AudioReceiver(AUDIO_PORT)
        audioReceiver?.start()

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "📡 Aguardando conexão..."
    }

    private fun stopReceiving() {
        streamReceiver?.stop()
        audioReceiver?.stop()
        streamReceiver = null
        audioReceiver = null

        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "⏹️ Parado"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiving()
    }
}
