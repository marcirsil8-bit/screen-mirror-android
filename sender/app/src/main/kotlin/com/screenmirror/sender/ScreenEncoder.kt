package com.screenmirror.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val targetIp: String,
    private val targetPort: Int
) {
    companion object {
        private const val TAG = "ScreenEncoder"
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
        private const val BIT_RATE = 6_000_000
    }

    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var isRunning = false
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null

    fun connect(): Boolean {
        return try {
            Log.d(TAG, "Connecting to $targetIp:$targetPort")
            socket = Socket()
            socket?.connect(InetSocketAddress(targetIp, targetPort), 5000)
            socket?.tcpNoDelay = true
            socket?.sendBufferSize = 512 * 1024
            outputStream = DataOutputStream(socket?.getOutputStream())
            Log.d(TAG, "Connected!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            false
        }
    }

    fun start() {
        handlerThread = HandlerThread("ScreenEncoderThread")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)

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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger("max-consecutive-bframes", 0)
        }

        try {
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    // Send format info (SPS/PPS) to receiver
                    try {
                        val csd0 = format.getByteBuffer("csd-0")
                        val csd1 = format.getByteBuffer("csd-1")
                        if (csd0 != null) {
                            val csd0Data = ByteArray(csd0.remaining())
                            csd0.get(csd0Data)
                            sendFrame(csd0Data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0)
                        }
                        if (csd1 != null) {
                            val csd1Data = ByteArray(csd1.remaining())
                            csd1.get(csd1Data)
                            sendFrame(csd1Data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0)
                        }
                        Log.d(TAG, "Sent SPS/PPS to receiver")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send format: ${e.message}")
                    }
                }

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
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec error: ${e.message}")
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
            Log.d(TAG, "Screen encoder started: ${targetWidth}x${targetHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Start failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun sendFrame(data: ByteArray, flags: Int, pts: Long) {
        try {
            val out = outputStream ?: return
            // Frame format: [type=1 byte 'V'][flags=4][pts=8][size=4][data]
            synchronized(out) {
                out.writeByte(0x56) // 'V' for video
                out.writeInt(flags)
                out.writeLong(pts)
                out.writeInt(data.size)
                out.write(data)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send frame failed: ${e.message}")
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        try {
            virtualDisplay?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
            outputStream?.close()
            socket?.close()
            handlerThread?.quitSafely()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay = null
        mediaCodec = null
        socket = null
        outputStream = null
    }
}
