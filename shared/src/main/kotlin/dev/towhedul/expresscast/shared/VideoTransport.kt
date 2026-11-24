package dev.towhedul.expresscast.shared

import java.nio.ByteBuffer

/**
 * Splits encoded H.264 access units into MTU-safe UDP datagrams prefixed with [VideoPacketHeader].
 */
object VideoPacketizer {
    fun packetize(
        frameId: Long,
        ptsUs: Long,
        flags: Int,
        payload: ByteArray,
        payloadSize: Int = payload.size
    ): List<ByteArray> {
        val scratch = ByteBuffer.allocate(NetworkConfig.MAX_DATAGRAM_SIZE)
        val packets = ArrayList<ByteArray>()
        writePackets(frameId, ptsUs, flags, payload, scratch, payloadSize) { _, _, size ->
            packets.add(scratch.array().copyOf(size))
            true
        }
        return packets
    }

    /** Writes one datagram at a time, reusing scratch as soon as emit returns. */
    fun writePackets(
        frameId: Long,
        ptsUs: Long,
        flags: Int,
        payload: ByteArray,
        scratch: ByteBuffer,
        payloadSize: Int = payload.size,
        emit: (index: Int, count: Int, size: Int) -> Boolean
    ): Boolean {
        require(payloadSize in 0..payload.size) {
            "payloadSize $payloadSize out of bounds for array size ${payload.size}"
        }
        require(scratch.capacity() >= NetworkConfig.MAX_DATAGRAM_SIZE)

        val maxPayload = VideoPacketHeader.MAX_PAYLOAD
        val fragmentCount = if (payloadSize == 0) 1 else (payloadSize - 1) / maxPayload + 1

        var offset = 0
        for (fragmentIndex in 0 until fragmentCount) {
            val chunkSize = minOf(maxPayload, payloadSize - offset)
            val header = VideoPacketHeader(
                frameId = frameId,
                fragmentIndex = fragmentIndex,
                fragmentCount = fragmentCount,
                ptsUs = ptsUs,
                payloadLength = chunkSize,
                flags = flags
            )
            scratch.clear()
            header.writeTo(scratch)
            scratch.put(payload, offset, chunkSize)
            if (!emit(fragmentIndex, fragmentCount, scratch.position())) return false
            offset += chunkSize
        }

        return true
    }
}

data class ReassembledFrame(
    val frameId: Long,
    val ptsUs: Long,
    val flags: Int,
    val data: ByteArray
)

sealed interface ReassemblyResult {
    data object Incomplete : ReassemblyResult
    data object DroppedStale : ReassemblyResult
    data class Complete(val frame: ReassembledFrame, val droppedIncompletePriorFrame: Boolean) : ReassemblyResult
}

/**
 * Reassembles incoming UDP [VideoPacketHeader] fragments into complete video frames.
 *
 * Implements a low-latency "newest frame wins" policy: when a packet for a newer `frameId` arrives,
 * any older incomplete frame is discarded immediately and flagged so the caller can request a keyframe.
 */
class FrameReassembler {
    private var currentFrameId: Long = -1L
    private var currentPtsUs: Long = 0L
    private var currentFlags: Int = 0
    private var expectedCount: Int = 0
    private var receivedCount: Int = 0
    private var fragments: Array<ByteArray?> = emptyArray()
    private var lastStartedFrameId: Long = -1L
    private var firstFragmentNs = 0L
    private var droppedPreviousFrame: Boolean = false

    fun reset() {
        currentFrameId = -1L
        currentPtsUs = 0L
        currentFlags = 0
        expectedCount = 0
        receivedCount = 0
        fragments = emptyArray()
        lastStartedFrameId = -1L
        droppedPreviousFrame = false
    }

    fun expireIncompleteFrame(nowNs: Long = System.nanoTime()): Boolean {
        if (currentFrameId == -1L || nowNs - firstFragmentNs < FRAME_TIMEOUT_NS) return false
        clearCurrentFrame()
        droppedPreviousFrame = true
        return true
    }

    fun onPacket(
        datagram: ByteArray,
        length: Int = datagram.size,
        nowNs: Long = System.nanoTime(),
        onFrameLost: () -> Unit = {}
    ): ReassemblyResult {
        if (length < VideoPacketHeader.SIZE || length > datagram.size) {
            return ReassemblyResult.Incomplete
        }
        val buf = ByteBuffer.wrap(datagram, 0, length)
        val header = VideoPacketHeader.readFrom(buf) ?: return ReassemblyResult.Incomplete
        if (buf.remaining() < header.payloadLength || header.fragmentCount > MAX_FRAGMENTS) {
            return ReassemblyResult.Incomplete
        }

        val frameId = header.frameId
        val distance = (frameId - lastStartedFrameId) and 0xFFFF_FFFFL
        if (lastStartedFrameId != -1L &&
            (distance >= 0x8000_0000L || (distance == 0L && currentFrameId == -1L))
        ) {
            return ReassemblyResult.DroppedStale
        }

        if (frameId != currentFrameId) {
            if (currentFrameId != -1L || (lastStartedFrameId != -1L && distance > 1L)) {
                droppedPreviousFrame = true
                onFrameLost()
            }
            lastStartedFrameId = frameId
            firstFragmentNs = nowNs
            currentFrameId = frameId
            currentPtsUs = header.ptsUs
            currentFlags = header.flags
            expectedCount = header.fragmentCount
            receivedCount = 0
            fragments = arrayOfNulls(expectedCount)
        }

        if (header.fragmentCount != expectedCount || header.ptsUs != currentPtsUs || header.flags != currentFlags) {
            return ReassemblyResult.Incomplete
        }

        if (fragments[header.fragmentIndex] == null) {
            val slice = ByteArray(header.payloadLength)
            buf.get(slice)
            fragments[header.fragmentIndex] = slice
            receivedCount++
        }

        if (receivedCount == expectedCount) {
            var totalBytes = 0
            for (part in fragments) {
                totalBytes += part?.size ?: return ReassemblyResult.Incomplete
            }
            val assembled = ByteArray(totalBytes)
            var offset = 0
            for (part in fragments) {
                val bytes = part!!
                System.arraycopy(bytes, 0, assembled, offset, bytes.size)
                offset += bytes.size
            }

            val completed = ReassembledFrame(
                frameId = currentFrameId,
                ptsUs = currentPtsUs,
                flags = currentFlags,
                data = assembled
            )
            val hadDrop = droppedPreviousFrame
            clearCurrentFrame()
            droppedPreviousFrame = false

            return ReassemblyResult.Complete(completed, droppedIncompletePriorFrame = hadDrop)
        }

        return ReassemblyResult.Incomplete
    }

    private fun clearCurrentFrame() {
        currentFrameId = -1L
        fragments = emptyArray()
        expectedCount = 0
        receivedCount = 0
    }

    private companion object {
        const val FRAME_TIMEOUT_NS = 50_000_000L
        const val MAX_FRAGMENTS = 4096
    }
}
