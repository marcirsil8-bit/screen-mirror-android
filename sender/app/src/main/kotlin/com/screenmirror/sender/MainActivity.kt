package com.screenmirror.sender

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1001
        private const val SERVER_PORT = 50001
    }

    private var mediaProjection: MediaProjection? = null
    private var screenEncoder: ScreenEncoder? = null
    private var audioCapturer: AudioCapturer? = null
    private var isStreaming = false

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var etIp: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)
        etIp = findViewById(R.id.etIp)

        btnStop.isEnabled = false
        tvInfo.text = getDeviceInfo()

        btnStart.setOnClickListener {
            val ip = etIp.text.toString().trim()
            if (ip.isEmpty()) {
                tvStatus.text = "⚠️ Digite o IP da TV Box"
                return@setOnClickListener
            }
            requestScreenCapture()
        }

        btnStop.setOnClickListener {
            stopStreaming()
        }
    }

    private fun getDeviceInfo(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = intToIp(wm.connectionInfo.ipAddress)
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        return "Seu IP: $ip\nResolução: ${dm.widthPixels}x${dm.heightPixels}\nAndroid: ${Build.VERSION.RELEASE}"
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            startStreaming(resultCode, data)
        } else {
            tvStatus.text = "❌ Permissão negada"
        }
    }

    private fun startStreaming(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        val width = dm.widthPixels
        val height = dm.heightPixels
        val dpi = dm.densityDpi

        val targetIp = etIp.text.toString().trim()

        tvStatus.text = "🔗 Conectando a $targetIp..."

        // Start screen encoder (connects via TCP to receiver)
        screenEncoder = ScreenEncoder(mediaProjection!!, width, height, dpi, targetIp, SERVER_PORT)
        
        // Start audio capturer (uses MediaProjection audio capture)
        audioCapturer = AudioCapturer(mediaProjection!!, targetIp, SERVER_PORT + 1)

        // Connect screen first, then audio
        Thread {
            val connected = screenEncoder?.connect() ?: false
            if (connected) {
                screenEncoder?.start()
                Thread.sleep(200)
                audioCapturer?.connect()
                audioCapturer?.start()
                runOnUiThread {
                    isStreaming = true
                    btnStart.isEnabled = false
                    btnStop.isEnabled = true
                    tvStatus.text = "📡 Transmitindo para $targetIp"
                }
            } else {
                runOnUiThread {
                    tvStatus.text = "❌ Falha ao conectar. Verifique o IP."
                }
            }
        }.start()
    }

    private fun stopStreaming() {
        screenEncoder?.stop()
        screenEncoder = null
        audioCapturer?.stop()
        audioCapturer = null
        mediaProjection?.stop()
        mediaProjection = null
        isStreaming = false
        runOnUiThread {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            tvStatus.text = "⏹️ Parado"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }
}
