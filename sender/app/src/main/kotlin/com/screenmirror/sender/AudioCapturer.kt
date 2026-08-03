package com.screenmirror.sender

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.InetAddresses
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class AudioCapturer(private val port: Int) {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MIME_TYPE = "audio/mp4a-latm" // AAC
        private const val BIT_RATE = 128_000
    }

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false
    private var udpSocket: DatagramSocket? = null
    private var recordThread: Thread? = null
    private var encodeThread: Thread? = null

    private var minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private var bufferSize = minBufferSize * 2

    private var targetHost: String? = null

    fun setTargetHost(host: String) {
        targetHost = host
    }

    fun start() {
        try {
            // Setup AAC encoder
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            // Setup AudioRecord
            audioRecord = AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("AudioRecord init failed")
            }

            audioRecord?.startRecording()
            udpSocket = DatagramSocket()
            isRunning = true

            // Thread to feed audio into encoder
            recordThread = Thread {
                val buffer = ByteArray(minBufferSize)
                while (isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val inputIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
                        if (inputIndex >= 0) {
                            val inputBuffer = mediaCodec?.getInputBuffer(inputIndex)
                            inputBuffer?.clear()
                            inputBuffer?.put(buffer, 0, read)
                            mediaCodec?.queueInputBuffer(
                                inputIndex, 0, read,
                                System.nanoTime() / 1000, 0
                            )
                        }
                    }
                }
            }

            // Thread to read encoded audio and send via UDP
            encodeThread = Thread {
                val bufferInfo = MediaCodec.BufferInfo()
                while (isRunning) {
                    val outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    if (outputIndex >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(data)
                            sendAudio(data, bufferInfo.flags, bufferInfo.presentationTimeUs)
                        }
                        mediaCodec?.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            recordThread?.start()
            encodeThread?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendAudio(data: ByteArray, flags: Int, pts: Long) {
        try {
            val host = targetHost ?: "255.255.255.255"
            val address = InetAddress.getByName(host)
            
            // Header: [magic(2)][flags(4)][pts(8)][size(4)][data]
            val packet = ByteArray(2 + 4 + 8 + 4 + data.size)
            packet[0] = 0x41 // 'A'
            packet[1] = 0x55 // 'U'
            
            packet[2] = (flags shr 24).toByte()
            packet[3] = (flags shr 16).toByte()
            packet[4] = (flags shr 8).toByte()
            packet[5] = flags.toByte()
            
            val ptsBytes = ByteBuffer.allocate(8).putLong(pts).array()
            System.arraycopy(ptsBytes, 0, packet, 6, 8)
            
            val size = data.size
            packet[14] = (size shr 24).toByte()
            packet[15] = (size shr 16).toByte()
            packet[16] = (size shr 8).toByte()
            packet[17] = size.toByte()
            
            System.arraycopy(data, 0, packet, 18, data.size)
            
            val dp = DatagramPacket(packet, packet.size, address, port)
            udpSocket?.send(dp)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning = false
        try {
            recordThread?.join(500)
            encodeThread?.join(500)
            audioRecord?.stop()
            audioRecord?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
            udpSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        mediaCodec = null
        udpSocket = null
    }
}
