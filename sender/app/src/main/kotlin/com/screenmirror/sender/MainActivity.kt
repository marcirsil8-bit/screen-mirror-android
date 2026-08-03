// Sender (Phone) - Screen Mirror Sender
// Captures screen + audio and streams to TV Box over local network

package com.screenmirror.sender

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1001
        private const val DISCOVERY_PORT = 50000
        private const val STREAM_PORT = 50001
        private const val AUDIO_PORT = 50002
    }

    private var mediaProjection: MediaProjection? = null
    private var screenEncoder: ScreenEncoder? = null
    private var audioCapturer: AudioCapturer? = null
    private var isStreaming = false
    private var discoverySocket: DatagramSocket? = null

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)

        btnStop.isEnabled = false

        btnStart.setOnClickListener {
            requestScreenCapture()
        }

        btnStop.setOnClickListener {
            stopStreaming()
        }

        // Start discovery broadcast listener
        startDiscovery()

        tvInfo.text = getDeviceInfo()
    }

    private fun getDeviceInfo(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = intToIp(wm.connectionInfo.ipAddress)
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        return "IP: $ip\nResolução: ${dm.widthPixels}x${dm.heightPixels}\nDPI: ${dm.densityDpi}\nAndroid: ${Build.VERSION.RELEASE}"
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
            tvStatus.text = "Permissão negada"
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

        screenEncoder = ScreenEncoder(mediaProjection!!, width, height, dpi, STREAM_PORT)
        screenEncoder?.start()

        audioCapturer = AudioCapturer(AUDIO_PORT)
        audioCapturer?.start()

        isStreaming = true
        runOnUiThread {
            btnStart.isEnabled = false
            btnStop.isEnabled = true
            tvStatus.text = "📡 Transmitindo..."
        }
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

    private fun startDiscovery() {
        Thread {
            try {
                discoverySocket = DatagramSocket(DISCOVERY_PORT)
                discoverySocket?.broadcast = true
                val buffer = ByteArray(1024)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    discoverySocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    if (message == "SCREEN_MIRROR_DISCOVERY") {
                        // Respond with our IP so receiver can connect
                        val response = "SCREEN_MIRROR_SENDER".toByteArray()
                        val responsePacket = DatagramPacket(
                            response, response.size,
                            packet.address, packet.port
                        )
                        discoverySocket?.send(responsePacket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        discoverySocket?.close()
    }
}
