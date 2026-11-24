package com.expresscast.phone.network

import android.util.Log
import com.expresscast.shared.NetworkConfig
import com.expresscast.shared.VideoPacketizer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Sends fragmented H.264 frames over UDP to the connected TV receiver.
 */
class UdpVideoSender(
    targetHost: String,
    private val targetPort: Int = NetworkConfig.DEFAULT_VIDEO_PORT
) {
    private val targetAddress: InetAddress = InetAddress.getByName(targetHost)
    private val socket = DatagramSocket().apply {
        trafficClass = 0x10 // IPTOS_LOWDELAY
        sendBufferSize = 512 * 1024
    }
    private val frameIdCounter = AtomicLong(0L)
    private val packetsSentCounter = AtomicLong(0L)

    @Volatile
    private var closed = false

    val packetsSent: Long
        get() = packetsSentCounter.get()

    fun sendFrame(data: ByteArray, ptsUs: Long, flags: Int) {
        if (closed) return
        val frameId = frameIdCounter.getAndIncrement() and 0xFFFF_FFFFL
        val packets = VideoPacketizer.packetize(
            frameId = frameId,
            ptsUs = ptsUs,
            flags = flags,
            payload = data
        )
        val total = packets.size
        for (index in 0 until total) {
            if (closed) return
            val datagramBytes = packets[index]
            try {
                val packet = DatagramPacket(datagramBytes, datagramBytes.size, targetAddress, targetPort)
                socket.send(packet)
                packetsSentCounter.incrementAndGet()
                // Micro-pace large multi-fragment frames (e.g. IDR keyframes) to avoid Wi-Fi TX ring drops
                if (total > 6 && (index + 1) % 6 == 0 && index + 1 < total) {
                    LockSupport.parkNanos(150_000L)
                }
            } catch (e: Exception) {
                if (!closed) {
                    Log.w(TAG, "UDP send failed: ${e.message}")
                }
            }
        }
    }

    fun close() {
        closed = true
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP video socket: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ExpressCast.UdpSender"
    }
}
