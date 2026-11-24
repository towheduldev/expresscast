package dev.towhedul.expresscast.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed 16-byte big-endian UDP audio packet header for low-latency PCM frames.
 *
 * Wire layout:
 * - Bytes 0..1   : magic (`Short`, `0x4543`)
 * - Byte  2      : protocol version (`UByte`)
 * - Byte  3      : channelCount (`UByte`, 1 or 2)
 * - Bytes 4..7   : sequence (`UInt` stored as `Long`)
 * - Bytes 8..11  : sampleRate (`Int`, e.g. 48000)
 * - Bytes 12..13 : payloadLength (`UShort` stored as `Int`, `0..MAX_PAYLOAD`)
 * - Bytes 14..15 : reserved (`Short`, 0)
 */
data class AudioPacketHeader(
    val sequence: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val payloadLength: Int
) {
    init {
        require(sequence in 0L..0xFFFF_FFFFL) { "sequence out of UInt range: $sequence" }
        require(sampleRate in 8000..192_000) { "sampleRate out of range: $sampleRate" }
        require(channelCount in 1..2) { "channelCount must be 1 or 2, got $channelCount" }
        require(payloadLength in 0..MAX_PAYLOAD) { "payloadLength $payloadLength exceeds MAX_PAYLOAD ($MAX_PAYLOAD)" }
    }

    fun writeTo(buf: ByteBuffer) {
        require(buf.remaining() >= SIZE) { "Buffer needs at least $SIZE bytes, has ${buf.remaining()}" }
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putShort(ProtocolInfo.MAGIC)
        buf.put(ProtocolInfo.PROTOCOL_VERSION.toByte())
        buf.put(channelCount.toByte())
        buf.putInt(sequence.toInt())
        buf.putInt(sampleRate)
        buf.putShort(payloadLength.toShort())
        buf.putShort(0)
    }

    companion object {
        const val SIZE: Int = 16
        const val MAX_PAYLOAD: Int = NetworkConfig.MAX_DATAGRAM_SIZE - SIZE

        fun readFrom(buf: ByteBuffer): AudioPacketHeader? {
            if (buf.remaining() < SIZE) return null
            buf.order(ByteOrder.BIG_ENDIAN)
            val magic = buf.short
            if (magic != ProtocolInfo.MAGIC) return null
            val version = buf.get().toInt() and 0xFF
            if (version != ProtocolInfo.PROTOCOL_VERSION) return null
            val channelCount = buf.get().toInt() and 0xFF
            val sequence = buf.int.toLong() and 0xFFFF_FFFFL
            val sampleRate = buf.int
            val payloadLength = buf.short.toInt() and 0xFFFF
            buf.short // reserved

            if (channelCount !in 1..2) return null
            if (sampleRate !in 8000..192_000) return null
            if (payloadLength > MAX_PAYLOAD) return null

            return AudioPacketHeader(
                sequence = sequence,
                sampleRate = sampleRate,
                channelCount = channelCount,
                payloadLength = payloadLength
            )
        }
    }
}
