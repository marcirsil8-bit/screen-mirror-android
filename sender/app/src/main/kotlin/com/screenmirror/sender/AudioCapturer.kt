package com.screenmirror.sender

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class AudioCapturer(
    private val mediaProjection: MediaProjection,
    private val targetIp: String,
    private val targetPort: Int
) {
    companion object {
        private const val TAG = "AudioCapturer"
        private const val SAMPLE_RATE = 44100
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val BIT_RATE = 128_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var recordThread: Thread? = null
    private var encodeThread: Thread? = null

    private val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    fun connect() {
        try {
            socket = Socket()
            socket?.connect(InetSocketAddress(targetIp, targetPort), 5000)
            socket?.tcpNoDelay = true
            outputStream = DataOutputStream(socket?.getOutputStream())
            Log.d(TAG, "Audio connected to $targetIp:$targetPort")
        } catch (e: Exception) {
            Log.e(TAG, "Audio connect failed: ${e.message}")
        }
    }

    fun start() {
        try {
            // Setup AAC encoder
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize * 2)
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            // Use MediaProjection to capture system audio (Android 10+)
            val audioConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .apply {
                    addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                    addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                }
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(audioConfig)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed - falling back to MIC")
                // Fallback to MIC if playback capture not available
                audioRecord = AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBufferSize * 2
                )
            }

            audioRecord?.startRecording()
            isRunning = true

            // Feed audio into encoder
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

            // Read encoded audio and send
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
            Log.d(TAG, "Audio capturer started")
        } catch (e: Exception) {
            Log.e(TAG, "Audio start failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun sendAudio(data: ByteArray, flags: Int, pts: Long) {
        try {
            val out = outputStream ?: return
            // Frame format: [type=1 byte 'A'][flags=4][pts=8][size=4][data]
            synchronized(out) {
                out.writeByte(0x41) // 'A' for audio
                out.writeInt(flags)
                out.writeLong(pts)
                out.writeInt(data.size)
                out.write(data)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send audio failed: ${e.message}")
            isRunning = false
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
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        mediaCodec = null
        socket = null
        outputStream = null
    }
}
