package com.screenmirror.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class StreamReceiver(
    private val port: Int,
    private val surfaceView: SurfaceView
) {
    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val MAX_QUEUE_SIZE = 60
    }

    private var mediaCodec: MediaCodec? = null
    private var udpSocket: DatagramSocket? = null
    private var isRunning = false
    private var receiveThread: Thread? = null
    private var decodeThread: Thread? = null
    private val frameQueue = LinkedBlockingQueue<FrameData>(MAX_QUEUE_SIZE)
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    data class FrameData(val data: ByteArray, val flags: Int, val pts: Long)

    fun start() {
        isRunning = true

        handlerThread = HandlerThread("StreamReceiverThread")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)

        // Configure decoder once we get SPS/PPS (first frame)
        receiveThread = Thread {
            try {
                udpSocket = DatagramSocket(port)
                udpSocket?.receiveBufferSize = 1024 * 1024
                
                while (isRunning) {
                    val buffer = ByteArray(1024 * 64) // 64KB max frame size
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)

                    // Parse header
                    if (packet.length < 18) continue
                    if (packet.data[0] != 0x53.toByte() || packet.data[1] != 0x4D.toByte()) continue

                    val flags = ((packet.data[2].toInt() and 0xFF) shl 24) or
                                ((packet.data[3].toInt() and 0xFF) shl 16) or
                                ((packet.data[4].toInt() and 0xFF) shl 8) or
                                (packet.data[5].toInt() and 0xFF)
                    
                    val pts = ByteBuffer.wrap(packet.data, 6, 8).long
                    val size = ((packet.data[14].toInt() and 0xFF) shl 24) or
                               ((packet.data[15].toInt() and 0xFF) shl 16) or
                               ((packet.data[16].toInt() and 0xFF) shl 8) or
                               (packet.data[17].toInt() and 0xFF)

                    if (size <= 0 || size > packet.length - 18) continue

                    val data = ByteArray(size)
                    System.arraycopy(packet.data, 18, data, 0, size)

                    val frame = FrameData(data, flags, pts)
                    
                    // Non-blocking add - drop oldest if queue full
                    if (!frameQueue.offer(frame)) {
                        frameQueue.poll()
                        frameQueue.offer(frame)
                    }

                    // Try to init decoder on first key frame
                    if (mediaCodec == null && (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) {
                        initDecoder(data)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) e.printStackTrace()
            }
        }

        decodeThread = Thread {
            while (isRunning) {
                val frame = frameQueue.poll() ?: continue
                if (mediaCodec == null) continue

                try {
                    val inputIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
                    if (inputIndex >= 0) {
                        val inputBuffer = mediaCodec?.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(frame.data)
                        mediaCodec?.queueInputBuffer(
                            inputIndex, 0, frame.data.size, frame.pts, frame.flags
                        )
                    }

                    val bufferInfo = MediaCodec.BufferInfo()
                    val outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    when (outputIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d("StreamReceiver", "Format changed")
                        }
                        in 0..Int.MAX_VALUE -> {
                            // Render to surface
                            mediaCodec?.releaseOutputBuffer(outputIndex, true)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        receiveThread?.start()
        decodeThread?.start()
    }

    private fun initDecoder(spsData: ByteArray) {
        try {
            // Extract SPS/PPS from the H.264 NAL units in the keyframe
            val format = MediaFormat.createVideoFormat(MIME_TYPE, 1920, 1080)
            
            mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE)
            mediaCodec?.configure(format, surfaceView.holder.surface, null, 0)
            mediaCodec?.start()
            
            Log.d("StreamReceiver", "Decoder initialized")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning = false
        try {
            receiveThread?.join(500)
            decodeThread?.join(500)
            mediaCodec?.stop()
            mediaCodec?.release()
            udpSocket?.close()
            handlerThread?.quitSafely()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaCodec = null
        udpSocket = null
    }
}
