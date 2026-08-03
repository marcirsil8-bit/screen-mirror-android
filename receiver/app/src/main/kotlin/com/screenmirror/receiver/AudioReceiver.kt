package com.screenmirror.receiver

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

class AudioReceiver(private val port: Int) {
    companion object {
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val SAMPLE_RATE = 44100
    }

    private var mediaCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var udpSocket: DatagramSocket? = null
    private var isRunning = false
    private var receiveThread: Thread? = null
    private var decodeThread: Thread? = null

    fun start() {
        isRunning = true

        receiveThread = Thread {
            try {
                // Init AAC decoder
                val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 2)
                mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE)
                mediaCodec?.configure(format, null, null, 0)
                mediaCodec?.start()

                // Init AudioTrack for playback
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

                udpSocket = DatagramSocket(port)
                udpSocket?.receiveBufferSize = 256 * 1024

                while (isRunning) {
                    val buffer = ByteArray(16 * 1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)

                    // Parse header
                    if (packet.length < 18) continue
                    if (packet.data[0] != 0x41.toByte() || packet.data[1] != 0x55.toByte()) continue

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
                }
            } catch (e: Exception) {
                if (isRunning) e.printStackTrace()
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
            udpSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
        mediaCodec = null
        udpSocket = null
    }
}
