package com.screenmirror.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val streamPort: Int
) {
    companion object {
        private const val MIME_TYPE = "video/avc" // H.264
        private const val FRAME_RATE = 60
        private const val I_FRAME_INTERVAL = 2
        private const val BIT_RATE = 8_000_000 // 8 Mbps
    }

    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var isRunning = false
    private var udpSocket: DatagramSocket? = null

    fun start() {
        handlerThread = HandlerThread("ScreenEncoderThread")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)

        // Calculate scaled resolution for performance (max 1080p)
        var targetWidth = width
        var targetHeight = height
        val maxDim = 1920
        if (targetWidth > maxDim || targetHeight > maxDim) {
            val scale = maxDim.toFloat() / maxOf(targetWidth, targetHeight)
            targetWidth = (targetWidth * scale / 2).toInt() * 2
            targetHeight = (targetHeight * scale / 2).toInt() * 2
        }

        val format = MediaFormat.createVideoFormat(MIME_TYPE, targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            // Low latency settings
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger("max-fps", FRAME_RATE)
            setInteger("repeat-previous-frame-after", 100000)
            setInteger("max-consecutive-bframes", 0)
        }

        try {
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    val outputBuffer = codec.getOutputBuffer(index) ?: return
                    if (info.size > 0) {
                        val data = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        outputBuffer.get(data)

                        sendFrame(data, info.flags, info.presentationTimeUs)

                        codec.releaseOutputBuffer(index, false)
                    } else {
                        codec.releaseOutputBuffer(index, false)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    e.printStackTrace()
                }
            }, handler!!)

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenMirror",
                targetWidth, targetHeight, dpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, handler
            )

            isRunning = true

            // UDP socket for streaming
            udpSocket = DatagramSocket()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var targetHost: String? = null

    fun setTargetHost(host: String) {
        targetHost = host
    }

    private fun sendFrame(data: ByteArray, flags: Int, pts: Long) {
        try {
            // Broadcast to local network - receiver listens on STREAM_PORT
            // If we have a specific target, use it; otherwise broadcast
            val host = targetHost ?: "255.255.255.255"
            val address = InetAddress.getByName(host)
            
            // Header: [magic(2)][flags(4)][pts(8)][size(4)][data]
            val packet = ByteArray(2 + 4 + 8 + 4 + data.size)
            packet[0] = 0x53 // 'S'
            packet[1] = 0x4D // 'M'
            
            // flags
            packet[2] = (flags shr 24).toByte()
            packet[3] = (flags shr 16).toByte()
            packet[4] = (flags shr 8).toByte()
            packet[5] = flags.toByte()
            
            // pts
            val ptsBytes = ByteBuffer.allocate(8).putLong(pts).array()
            System.arraycopy(ptsBytes, 0, packet, 6, 8)
            
            // size
            val size = data.size
            packet[14] = (size shr 24).toByte()
            packet[15] = (size shr 16).toByte()
            packet[16] = (size shr 8).toByte()
            packet[17] = size.toByte()
            
            // data
            System.arraycopy(data, 0, packet, 18, data.size)
            
            val dp = DatagramPacket(packet, packet.size, address, streamPort)
            udpSocket?.send(dp)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning = false
        try {
            virtualDisplay?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
            udpSocket?.close()
            handlerThread?.quitSafely()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay = null
        mediaCodec = null
        udpSocket = null
    }
}
