package dev.towhedul.expresscast.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTransportTest {
    @Test
    fun incompleteFrameExpiresWithoutAnotherPacketAndCannotBeRevived() {
        val fragments = VideoPacketizer.packetize(3, 1000, 0, ByteArray(VideoPacketHeader.MAX_PAYLOAD + 1))
        val reassembler = FrameReassembler()

        assertEquals(ReassemblyResult.Incomplete, reassembler.onPacket(fragments[0], nowNs = 0))
        assertFalse(reassembler.expireIncompleteFrame(10_000_000))
        assertTrue(reassembler.expireIncompleteFrame(60_000_000))
        assertFalse(reassembler.expireIncompleteFrame(70_000_000))
        assertEquals(ReassemblyResult.DroppedStale, reassembler.onPacket(fragments[1], nowNs = 70_000_000))
    }

    @Test
    fun rolloverGapIsReportedBeforeNextFrameCompletes() {
        val oldFrame = VideoPacketizer.packetize(0xFFFF_FFFFL, 1000, 0, byteArrayOf(1)).single()
        val newFrame = VideoPacketizer.packetize(1, 2000, 0, ByteArray(VideoPacketHeader.MAX_PAYLOAD + 1))
        val reassembler = FrameReassembler()
        var losses = 0

        assertTrue(reassembler.onPacket(oldFrame) is ReassemblyResult.Complete)
        assertEquals(ReassemblyResult.Incomplete, reassembler.onPacket(newFrame[0]) { losses++ })
        assertEquals(1, losses)
        assertEquals(ReassemblyResult.DroppedStale, reassembler.onPacket(oldFrame))
        val complete = reassembler.onPacket(newFrame[1]) { losses++ } as ReassemblyResult.Complete
        assertTrue(complete.droppedIncompletePriorFrame)
        assertEquals(1, losses)
    }

    @Test
    fun packetizeAndReassembleMultiFragmentOutOfOrder() {
        val payload = ByteArray(VideoPacketHeader.MAX_PAYLOAD * 2 + 123) { (it % 251).toByte() }
        val packets = VideoPacketizer.packetize(
            frameId = 42L,
            ptsUs = 987654L,
            flags = VideoPacketHeader.FLAG_KEYFRAME,
            payload = payload
        )

        assertEquals(3, packets.size)

        val reassembler = FrameReassembler()
        assertEquals(ReassemblyResult.Incomplete, reassembler.onPacket(packets[2]))
        assertEquals(ReassemblyResult.Incomplete, reassembler.onPacket(packets[0]))
        val result = reassembler.onPacket(packets[1])

        assertTrue(result is ReassemblyResult.Complete)
        val complete = result as ReassemblyResult.Complete
        assertEquals(42L, complete.frame.frameId)
        assertEquals(987654L, complete.frame.ptsUs)
        assertEquals(VideoPacketHeader.FLAG_KEYFRAME, complete.frame.flags)
        assertFalse(complete.droppedIncompletePriorFrame)
        assertArrayEquals(payload, complete.frame.data)
    }

    @Test
    fun newerFrameDropsIncompleteOlderFrameAndFlagsDrop() {
        val largePayload = ByteArray(VideoPacketHeader.MAX_PAYLOAD * 2) { 7 }
        val frame1Packets = VideoPacketizer.packetize(1L, 1000L, 0, largePayload)
        val frame2Packets = VideoPacketizer.packetize(2L, 2000L, VideoPacketHeader.FLAG_KEYFRAME, byteArrayOf(1, 2, 3, 4))

        val reassembler = FrameReassembler()
        assertEquals(ReassemblyResult.Incomplete, reassembler.onPacket(frame1Packets[0]))

        val result = reassembler.onPacket(frame2Packets[0])
        assertTrue(result is ReassemblyResult.Complete)
        val complete = result as ReassemblyResult.Complete
        assertEquals(2L, complete.frame.frameId)
        assertTrue(complete.droppedIncompletePriorFrame)

        // Late fragment from frame 1 is ignored as stale
        assertEquals(ReassemblyResult.DroppedStale, reassembler.onPacket(frame1Packets[1]))
    }
}
