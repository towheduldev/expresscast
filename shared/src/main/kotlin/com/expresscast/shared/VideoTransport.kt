package com.expresscast.shared

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
        require(payloadSize in 0..payload.size) {
            "payloadSize $payloadSize out of bounds for array size ${payload.size}"
        }
        if (payloadSize == 0) {
            val header = VideoPacketHeader(
                frameId = frameId,
                fragmentIndex = 0,
                fragmentCount = 1,
                ptsUs = ptsUs,
                payloadLength = 0,
                flags = flags
            )
            val datagram = ByteArray(VideoPacketHeader.SIZE)
            header.writeTo(ByteBuffer.wrap(datagram))
            return listOf(datagram)
        }

        val maxPayload = VideoPacketHeader.MAX_PAYLOAD
        val fragmentCount = (payloadSize + maxPayload - 1) / maxPayload
        val packets = ArrayList<ByteArray>(fragmentCount)

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
            val datagram = ByteArray(VideoPacketHeader.SIZE + chunkSize)
            val buffer = ByteBuffer.wrap(datagram)
            header.writeTo(buffer)
            System.arraycopy(payload, offset, datagram, VideoPacketHeader.SIZE, chunkSize)
            packets.add(datagram)
            offset += chunkSize
        }

        return packets
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
    private var lastCompletedFrameId: Long = -1L
    private var droppedPreviousFrame: Boolean = false

    fun reset() {
        currentFrameId = -1L
        currentPtsUs = 0L
        currentFlags = 0
        expectedCount = 0
        receivedCount = 0
        fragments = emptyArray()
        lastCompletedFrameId = -1L
        droppedPreviousFrame = false
    }

    fun onPacket(datagram: ByteArray, length: Int = datagram.size): ReassemblyResult {
        if (length < VideoPacketHeader.SIZE || length > datagram.size) {
            return ReassemblyResult.Incomplete
        }
        val buf = ByteBuffer.wrap(datagram, 0, length)
        val header = VideoPacketHeader.readFrom(buf) ?: return ReassemblyResult.Incomplete
        if (buf.remaining() < header.payloadLength) {
            return ReassemblyResult.Incomplete
        }

        val frameId = header.frameId
        if (frameId <= lastCompletedFrameId && !isWrapAround(lastCompletedFrameId, frameId)) {
            return ReassemblyResult.DroppedStale
        }
        if (currentFrameId != -1L && frameId < currentFrameId && !isWrapAround(currentFrameId, frameId)) {
            return ReassemblyResult.DroppedStale
        }

        if (frameId != currentFrameId) {
            if (currentFrameId != -1L && receivedCount < expectedCount) {
                droppedPreviousFrame = true
            }
            currentFrameId = frameId
            currentPtsUs = header.ptsUs
            currentFlags = header.flags
            expectedCount = header.fragmentCount
            receivedCount = 0
            fragments = arrayOfNulls(expectedCount)
        }

        if (header.fragmentCount != expectedCount || header.fragmentIndex >= expectedCount) {
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
            val hadDrop = droppedPreviousFrame ||
                (lastCompletedFrameId != -1L && currentFrameId > lastCompletedFrameId + 1)

            lastCompletedFrameId = currentFrameId
            currentFrameId = -1L
            fragments = emptyArray()
            expectedCount = 0
            receivedCount = 0
            droppedPreviousFrame = false

            return ReassemblyResult.Complete(completed, droppedIncompletePriorFrame = hadDrop)
        }

        return ReassemblyResult.Incomplete
    }

    private fun isWrapAround(previousId: Long, nextId: Long): Boolean {
        return previousId > 0xFFFF_0000L && nextId < 0x0000_FFFFL
    }
}
