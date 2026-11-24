package dev.towhedul.expresscast.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class VideoPacketHeaderTest {
    @Test
    fun roundTrip_preservesAllFields() {
        val original = VideoPacketHeader(
            frameId = 42L,
            fragmentIndex = 1,
            fragmentCount = 4,
            ptsUs = 123_456_789L,
            payloadLength = 1200,
            flags = VideoPacketHeader.FLAG_KEYFRAME or VideoPacketHeader.FLAG_CODEC_CONFIG
        )

        val buffer = ByteBuffer.allocate(VideoPacketHeader.SIZE)
        original.writeTo(buffer)
        assertEquals(VideoPacketHeader.SIZE, buffer.position())

        buffer.flip()
        val decoded = VideoPacketHeader.readFrom(buffer)
        assertEquals(original, decoded)
        assertNotNull(decoded)
        assertTrue(decoded!!.isKeyframe)
        assertTrue(decoded.isCodecConfig)
    }

    @Test
    fun readFrom_returnsNullOnBadMagic() {
        val buffer = ByteBuffer.allocate(VideoPacketHeader.SIZE)
        VideoPacketHeader(
            frameId = 1L,
            fragmentIndex = 0,
            fragmentCount = 1,
            ptsUs = 0L,
            payloadLength = 100
        ).writeTo(buffer)

        buffer.put(0, 0x00)
        buffer.flip()

        assertNull(VideoPacketHeader.readFrom(buffer))
    }

    @Test
    fun readFrom_returnsNullOnShortBuffer() {
        val buffer = ByteBuffer.allocate(VideoPacketHeader.SIZE - 1)
        assertNull(VideoPacketHeader.readFrom(buffer))
    }

    @Test
    fun roundTrip_handlesUnsignedMaxValues() {
        val original = VideoPacketHeader(
            frameId = 0xFFFF_FFFFL,
            fragmentIndex = 65_534,
            fragmentCount = 65_535,
            ptsUs = Long.MAX_VALUE,
            payloadLength = VideoPacketHeader.MAX_PAYLOAD,
            flags = 0xFF
        )

        val buffer = ByteBuffer.allocate(VideoPacketHeader.SIZE)
        original.writeTo(buffer)
        buffer.flip()

        val decoded = VideoPacketHeader.readFrom(buffer)
        assertEquals(original, decoded)
    }
}
