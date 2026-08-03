package com.screenmirror.receiver

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DISCOVERY_PORT = 50000
        private const val STREAM_PORT = 50001
        private const val AUDIO_PORT = 50002
    }

    private var streamReceiver: StreamReceiver? = null
    private var audioReceiver: AudioReceiver? = null
    private var discoveryBroadcaster: DiscoveryBroadcaster? = null

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Fullscreen for TV
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)

        btnStop.isEnabled = false

        btnStart.setOnClickListener {
            startReceiving()
        }

        btnStop.setOnClickListener {
            stopReceiving()
        }

        tvInfo.text = getDeviceInfo()
    }

    private fun getDeviceInfo(): String {
        val dm = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        return "Resolução TV: ${dm.widthPixels}x${dm.heightPixels}\n" +
               "Android: ${android.os.Build.VERSION.RELEASE}\n" +
               "Modelo: ${android.os.Build.MODEL}"
    }

    private fun startReceiving() {
        // Start discovery to find sender
        discoveryBroadcaster = DiscoveryBroadcaster(DISCOVERY_PORT)
        discoveryBroadcaster?.start()

        // Start video stream receiver
        streamReceiver = StreamReceiver(STREAM_PORT, findViewById(R.id.surfaceView))
        streamReceiver?.start()

        // Start audio stream receiver
        audioReceiver = AudioReceiver(AUDIO_PORT)
        audioReceiver?.start()

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "📡 Aguardando transmissão..."
    }

    private fun stopReceiving() {
        discoveryBroadcaster?.stop()
        streamReceiver?.stop()
        audioReceiver?.stop()
        discoveryBroadcaster = null
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
