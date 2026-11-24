package dev.towhedul.expresscast.tv.network

import android.util.Log
import dev.towhedul.expresscast.shared.FrameReassembler
import dev.towhedul.expresscast.shared.NetworkConfig
import dev.towhedul.expresscast.shared.ReassembledFrame
import dev.towhedul.expresscast.shared.ReassemblyResult
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Owns the receive socket and reassembly state for one playback session. */
class UdpVideoReceiver(
    private val port: Int,
    private val peerAddress: InetAddress?,
    private val onFrame: (ReassembledFrame) -> Unit,
    private val onPacketLoss: () -> Unit,
    private val waitingForSync: () -> Boolean,
    private val requestKeyframe: () -> Unit,
    private val onError: () -> Unit
) {
    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    private var lastKeyframeRequestNs: Long? = null

    fun start() {
        if (running) return
        running = true
        Thread(::receiveLoop, "ExpressCast-UdpVideoRx").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        socket?.close()
    }

    private fun receiveLoop() {
        try {
            DatagramSocket(port).use { rx ->
                socket = rx
                rx.receiveBufferSize = 1024 * 1024
                rx.soTimeout = 25
                if (!running) return
                Log.i(TAG, "UDP video receiver listening on port $port")

                val reassembler = FrameReassembler()
                // One extra byte lets reassembly reject truncated oversized datagrams.
                val buffer = ByteArray(NetworkConfig.MAX_DATAGRAM_SIZE + 1)
                val packet = DatagramPacket(buffer, buffer.size)
                while (running) {
                    if (waitingForSync()) maybeRequestKeyframe(System.nanoTime())
                    packet.length = buffer.size
                    try {
                        rx.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        if (reassembler.expireIncompleteFrame()) onPacketLoss()
                        continue
                    }
                    val now = System.nanoTime()
                    if (reassembler.expireIncompleteFrame(now)) onPacketLoss()
                    if (packet.length > NetworkConfig.MAX_DATAGRAM_SIZE) continue
                    if (peerAddress != null && packet.address != peerAddress) continue
                    val result = reassembler.onPacket(buffer, packet.length, now, onPacketLoss)
                    if (result is ReassemblyResult.Complete) onFrame(result.frame)
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "UDP video receiver failed on port $port", e)
                onError()
            }
        }
    }

    private fun maybeRequestKeyframe(now: Long) {
        val previous = lastKeyframeRequestNs
        if (previous == null || now - previous >= KEYFRAME_REQUEST_COOLDOWN_NS) {
            lastKeyframeRequestNs = now
            requestKeyframe()
        }
    }

    private companion object {
        const val TAG = "ExpressCast.UdpRx"
        const val KEYFRAME_REQUEST_COOLDOWN_NS = 250_000_000L
    }
}
