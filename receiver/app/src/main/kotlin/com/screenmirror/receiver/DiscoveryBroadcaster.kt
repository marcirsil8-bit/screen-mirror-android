package com.screenmirror.receiver

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DiscoveryBroadcaster(private val port: Int) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private var thread: Thread? = null

    fun start() {
        isRunning = true
        thread = Thread {
            try {
                socket = DatagramSocket()
                socket?.broadcast = true

                while (isRunning) {
                    // Broadcast discovery packet
                    val msg = "SCREEN_MIRROR_DISCOVERY".toByteArray()
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(msg, msg.size, broadcastAddr, port)
                    socket?.send(packet)

                    // Wait for response
                    socket?.soTimeout = 2000
                    val responseBuffer = ByteArray(1024)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    try {
                        socket?.receive(responsePacket)
                        val response = String(responsePacket.data, 0, responsePacket.length)
                        if (response == "SCREEN_MIRROR_SENDER") {
                            // Found sender - could store IP for targeted streaming
                            Log("Discovered sender at ${responsePacket.address.hostAddress}")
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // No response, keep broadcasting
                    }

                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                if (isRunning) e.printStackTrace()
            }
        }
        thread?.start()
    }

    fun stop() {
        isRunning = false
        try {
            thread?.join(500)
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        socket = null
    }

    private fun Log(msg: String) {
        android.util.Log.d("DiscoveryBroadcaster", msg)
    }
}
