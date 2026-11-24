package dev.towhedul.expresscast.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class AudioPacketHeaderTest {
    @Test
    fun roundTripAudioPacketHeader() {
        val original = AudioPacketHeader(
            sequence = 12345L,
            sampleRate = 48000,
            channelCount = 2,
            payloadLength = 960
        )
        val buf = ByteBuffer.allocate(AudioPacketHeader.SIZE)
        original.writeTo(buf)
        buf.flip()

        assertEquals(original, AudioPacketHeader.readFrom(buf))
    }

    @Test
    fun rejectsBadMagicOrTruncatedBuffer() {
        val shortBuf = ByteBuffer.allocate(AudioPacketHeader.SIZE - 1)
        assertNull(AudioPacketHeader.readFrom(shortBuf))

        val badMagic = ByteBuffer.allocate(AudioPacketHeader.SIZE).putShort(0x1234)
        badMagic.flip()
        assertNull(AudioPacketHeader.readFrom(badMagic))
    }
}
