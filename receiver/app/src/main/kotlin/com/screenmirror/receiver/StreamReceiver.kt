package com.screenmirror.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.SurfaceView
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

class StreamReceiver(
    private val port: Int,
    private val surfaceView: SurfaceView
) {
    companion object {
        private const val TAG = "StreamReceiver"
        private const val MIME_TYPE = "video/avc"
        private const val MAX_QUEUE_SIZE = 30
    }

    private var mediaCodec: MediaCodec? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = false
    private var receiveThread: Thread? = null
    private var decodeThread: Thread? = null
    private val frameQueue = LinkedBlockingQueue<FrameData>(MAX_QUEUE_SIZE)
    private var hasFormat = false

    data class FrameData(val data: ByteArray, val flags: Int, val pts: Long)

    fun start() {
        isRunning = true

        // TCP server thread - accepts connection and receives frames
        receiveThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Listening on port $port")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d(TAG, "Sender connected: ${socket.inetAddress}")
                    clientSocket = socket
                    
                    val input = DataInputStream(socket.getInputStream())
                    
                    while (isRunning && !socket.isClosed) {
                        try {
                            // Read frame header: [type][flags][pts][size]
                            val type = input.readByte()
                            if (type != 0x56.toByte()) continue // 'V' for video
                            
                            val flags = input.readInt()
                            val pts = input.readLong()
                            val size = input.readInt()
                            
                            if (size <= 0 || size > 10 * 1024 * 1024) continue
                            
                            val data = ByteArray(size)
                            input.readFully(data)
                            
                            val frame = FrameData(data, flags, pts)
                            
                            // Handle codec config (SPS/PPS)
                            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                initDecoder(data)
                            } else {
                                // Drop old frames if queue full
                                if (!frameQueue.offer(frame)) {
                                    frameQueue.poll()
                                    frameQueue.offer(frame)
                                }
                            }
                        } catch (e: Exception) {
                            if (isRunning) Log.e(TAG, "Receive error: ${e.message}")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Server error: ${e.message}")
            }
        }

        // Decode thread
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
                    var outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    while (outputIndex >= 0) {
                        // Render to surface
                        mediaCodec?.releaseOutputBuffer(outputIndex, true)
                        outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Decode error: ${e.message}")
                }
            }
        }

        receiveThread?.start()
        decodeThread?.start()
    }

    private fun initDecoder(csdData: ByteArray) {
        if (mediaCodec != null) return
        
        try {
            // Parse SPS from NAL units to get width/height
            var width = 1920
            var height = 1080
            
            // Try to find SPS (NAL type 7) and PPS (NAL type 8)
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csdData))
            
            mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE)
            
            // Wait for surface to be ready
            var retries = 0
            while (surfaceView.holder.surface == null && retries < 50) {
                Thread.sleep(100)
                retries++
            }
            
            val surface = surfaceView.holder.surface
            if (surface != null && surface.isValid) {
                mediaCodec?.configure(format, surface, null, 0)
                mediaCodec?.start()
                hasFormat = true
                Log.d(TAG, "Decoder initialized with SPS/PPS")
            } else {
                Log.e(TAG, "Surface not ready after $retries retries")
                mediaCodec = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Init decoder failed: ${e.message}")
            mediaCodec = null
        }
    }

    fun stop() {
        isRunning = false
        try {
            receiveThread?.join(500)
            decodeThread?.join(500)
            mediaCodec?.stop()
            mediaCodec?.release()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaCodec = null
        clientSocket = null
        serverSocket = null
    }
}
