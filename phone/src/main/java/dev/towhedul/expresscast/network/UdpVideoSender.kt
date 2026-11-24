package dev.towhedul.expresscast.network

import android.util.Log
import dev.towhedul.expresscast.shared.NetworkConfig
import dev.towhedul.expresscast.shared.VideoPacketHeader
import dev.towhedul.expresscast.shared.VideoPacketizer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Sends fragmented H.264 frames over UDP to the connected TV receiver.
 */
class UdpVideoSender(
    targetHost: String,
    private val targetPort: Int = NetworkConfig.DEFAULT_VIDEO_PORT,
    private val requestSyncFrame: () -> Unit
) {
    private data class Frame(
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int,
        val config: ByteArray?,
        val queuedAtNs: Long
    )

    private val targetAddress: InetAddress = InetAddress.getByName(targetHost)
    private val socket = DatagramSocket().apply {
        trafficClass = 0x10 // IPTOS_LOWDELAY
        sendBufferSize = 512 * 1024
    }
    private val datagram = ByteArray(NetworkConfig.MAX_DATAGRAM_SIZE)
    private val packet = DatagramPacket(datagram, datagram.size, targetAddress, targetPort)
    private val scratch = ByteBuffer.wrap(datagram)
    private var nextFrameId = 0L
    private val packetsSentCounter = AtomicLong(0L)
    private val queueLock = Object()
    private val pending = ArrayDeque<Frame>(MAX_PENDING_FRAMES)
    private var latestConfig: ByteArray? = null
    private var waitingForKeyframe = true

    @Volatile
    private var closed = false
    private val worker = Thread(::sendLoop, "ExpressCast-UdpVideoTx").apply {
        isDaemon = true
        start()
    }

    val packetsSent: Long
        get() = packetsSentCounter.get()

    fun offerFrame(data: ByteArray, ptsUs: Long, flags: Int) {
        val isConfig = flags and VideoPacketHeader.FLAG_CODEC_CONFIG != 0
        val isKeyframe = flags and VideoPacketHeader.FLAG_KEYFRAME != 0
        val requestKeyframe = synchronized(queueLock) {
            if (closed) return
            if (isConfig && !isKeyframe) {
                latestConfig = data
                return
            }
            val overflow = pending.size == MAX_PENDING_FRAMES
            if (overflow) resetQueue()
            if (!waitingForKeyframe || isKeyframe) {
                pending.addLast(Frame(data, ptsUs, flags, latestConfig.takeIf { isKeyframe }, System.nanoTime()))
                waitingForKeyframe = false
                queueLock.notify()
            }
            overflow && !isKeyframe
        }
        if (requestKeyframe) requestSyncFrame()
    }

    private fun resetQueue() {
        pending.clear()
        waitingForKeyframe = true
    }

    private fun sendLoop() {
        try {
            while (!closed) {
                val frame = synchronized(queueLock) {
                    while (pending.isEmpty() && !closed) queueLock.wait()
                    if (closed) return
                    pending.removeFirst()
                }
                val sent = System.nanoTime() - frame.queuedAtNs <= MAX_QUEUE_AGE_NS &&
                    (frame.config == null ||
                        sendFrame(frame.config, frame.ptsUs, VideoPacketHeader.FLAG_CODEC_CONFIG)) &&
                    sendFrame(frame.data, frame.ptsUs, frame.flags)
                if (!sent && !closed) {
                    synchronized(queueLock) { resetQueue() }
                    requestSyncFrame()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!closed) Log.e(TAG, "Video sender stopped unexpectedly", e)
        }
    }

    private fun sendFrame(data: ByteArray, ptsUs: Long, flags: Int): Boolean {
        val frameId = nextFrameId++ and 0xFFFF_FFFFL
        return VideoPacketizer.writePackets(
            frameId = frameId,
            ptsUs = ptsUs,
            flags = flags,
            payload = data,
            scratch = scratch
        ) { index, total, size ->
            if (closed) return@writePackets false
            try {
                packet.length = size
                socket.send(packet)
                packetsSentCounter.incrementAndGet()
                // Micro-pace large multi-fragment frames (e.g. IDR keyframes) to avoid Wi-Fi TX ring drops
                if (total > 6 && (index + 1) % 6 == 0 && index + 1 < total) {
                    LockSupport.parkNanos(150_000L)
                }
                true
            } catch (e: Exception) {
                if (!closed) {
                    Log.w(TAG, "UDP send failed: ${e.message}")
                }
                false
            }
        }
    }

    fun close() {
        synchronized(queueLock) {
            closed = true
            resetQueue()
            latestConfig = null
            queueLock.notify()
        }
        worker.interrupt()
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP video socket: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ExpressCast.UdpSender"
        const val MAX_PENDING_FRAMES = 2
        val MAX_QUEUE_AGE_NS = TimeUnit.MILLISECONDS.toNanos(50)
    }
}
