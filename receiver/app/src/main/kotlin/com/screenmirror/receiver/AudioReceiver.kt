package com.screenmirror.receiver

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

class AudioReceiver(private val port: Int) {
    companion object {
        private const val TAG = "AudioReceiver"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val SAMPLE_RATE = 44100
    }

    private var mediaCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = false
    private var receiveThread: Thread? = null

    fun start() {
        isRunning = true

        receiveThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Audio listening on port $port")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d(TAG, "Audio sender connected: ${socket.inetAddress}")
                    clientSocket = socket
                    
                    val input = DataInputStream(socket.getInputStream())
                    
                    // Init AAC decoder
                    val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 2)
                    mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE)
                    mediaCodec?.configure(format, null, null, 0)
                    mediaCodec?.start()
                    
                    // Init AudioTrack
                    val minBufSize = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AndroidAudioFormat.CHANNEL_OUT_STEREO,
                        AndroidAudioFormat.ENCODING_PCM_16BIT
                    )
                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AndroidAudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(minBufSize * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    audioTrack?.play()

                    while (isRunning && !socket.isClosed) {
                        try {
                            // Read frame: [type][flags][pts][size][data]
                            val type = input.readByte()
                            if (type != 0x41.toByte()) continue // 'A' for audio
                            
                            val flags = input.readInt()
                            val pts = input.readLong()
                            val size = input.readInt()
                            
                            if (size <= 0 || size > 1024 * 1024) continue
                            
                            val data = ByteArray(size)
                            input.readFully(data)
                            
                            // Feed to decoder
                            val inputIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
                            if (inputIndex >= 0) {
                                val inputBuffer = mediaCodec?.getInputBuffer(inputIndex)
                                inputBuffer?.clear()
                                inputBuffer?.put(data)
                                mediaCodec?.queueInputBuffer(inputIndex, 0, size, pts, flags)
                            }
                            
                            // Read decoded audio and play
                            val bufferInfo = MediaCodec.BufferInfo()
                            var outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                            while (outputIndex >= 0) {
                                val outputBuffer = mediaCodec?.getOutputBuffer(outputIndex)
                                if (outputBuffer != null && bufferInfo.size > 0) {
                                    val pcmData = ByteArray(bufferInfo.size)
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    outputBuffer.get(pcmData)
                                    audioTrack?.write(pcmData, 0, pcmData.size)
                                }
                                mediaCodec?.releaseOutputBuffer(outputIndex, false)
                                outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                            }
                        } catch (e: Exception) {
                            if (isRunning) Log.e(TAG, "Audio receive error: ${e.message}")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Audio server error: ${e.message}")
            }
        }

        receiveThread?.start()
    }

    fun stop() {
        isRunning = false
        try {
            receiveThread?.join(500)
            audioTrack?.stop()
            audioTrack?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
        mediaCodec = null
        clientSocket = null
        serverSocket = null
    }
}
