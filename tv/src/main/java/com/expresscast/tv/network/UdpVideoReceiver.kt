package com.expresscast.tv.network

import android.util.Log
import com.expresscast.shared.FrameReassembler
import com.expresscast.shared.NetworkConfig
import com.expresscast.shared.ReassembledFrame
import com.expresscast.shared.ReassemblyResult
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException

/**
 * Receives UDP video datagrams, reassembles H.264 frames with newest-frame-wins semantics,
 * and requests keyframes upon packet loss.
 */
class UdpVideoReceiver(
    private val port: Int = NetworkConfig.DEFAULT_VIDEO_PORT,
    private val onFrame: (ReassembledFrame) -> Unit,
    private val onPacketLoss: () -> Unit
) {
    private val reassembler = FrameReassembler()

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    private var receiveThread: Thread? = null
    private var lastKeyframeRequestMs: Long = 0L

    fun start() {
        if (running) return
        running = true
        reassembler.reset()

        receiveThread = Thread({
            runReceiveLoop()
        }, "ExpressCast-UdpVideoRx").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP video receiver socket: ${e.message}")
        }
        socket = null
        reassembler.reset()
    }

    private fun runReceiveLoop() {
        try {
            val rxSocket = DatagramSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 1024 * 1024
            }
            socket = rxSocket
            Log.i(TAG, "UDP video receiver listening on port $port")

            val buffer = ByteArray(NetworkConfig.MAX_DATAGRAM_SIZE + 64)
            val packet = DatagramPacket(buffer, buffer.size)
            var packetCount = 0L
            var frameCount = 0L

            while (running && !rxSocket.isClosed) {
                rxSocket.receive(packet)
                packetCount++
                if (packetCount == 1L) {
                    Log.i(TAG, "First UDP video packet received (${packet.length} bytes from ${packet.address.hostAddress})")
                }
                when (val result = reassembler.onPacket(packet.data, packet.length)) {
                    is ReassemblyResult.Complete -> {
                        frameCount++
                        if (frameCount == 1L || frameCount % 120L == 0L) {
                            Log.d(TAG, "Reassembled frame #$frameCount (bytes=${result.frame.data.size}, flags=${result.frame.flags})")
                        }
                        if (result.droppedIncompletePriorFrame) {
                            maybeRequestKeyframe()
                        }
                        onFrame(result.frame)
                    }
                    ReassemblyResult.Incomplete,
                    ReassemblyResult.DroppedStale -> Unit
                }
            }
        } catch (e: SocketException) {
            if (running) {
                Log.w(TAG, "UDP receiver socket error: ${e.message}")
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Unexpected error in UDP receiver loop", e)
            }
        }
    }

    private fun maybeRequestKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestMs >= KEYFRAME_REQUEST_COOLDOWN_MS) {
            lastKeyframeRequestMs = now
            onPacketLoss()
        }
    }

    private companion object {
        const val TAG = "ExpressCast.UdpRx"
        const val KEYFRAME_REQUEST_COOLDOWN_MS = 250L
    }
}
