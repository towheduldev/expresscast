package com.expresscast.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed 22-byte big-endian UDP video packet header (draft v1, to be finalized in Phase 4).
 *
 * Wire layout:
 * - Bytes 0..1   : magic (`Short`, `0x4543`)
 * - Byte  2      : protocol version (`UByte`)
 * - Byte  3      : flags (`UByte`: bit 0 = keyframe, bit 1 = codec config SPS/PPS)
 * - Bytes 4..7   : frameId (`UInt` stored as `Long`, `0..0xFFFFFFFF`)
 * - Bytes 8..9   : fragmentIndex (`UShort` stored as `Int`, `0..fragmentCount - 1`)
 * - Bytes 10..11 : fragmentCount (`UShort` stored as `Int`, `1..65535`)
 * - Bytes 12..19 : presentation timestamp in microseconds (`Long`)
 * - Bytes 20..21 : payloadLength (`UShort` stored as `Int`, `0..MAX_PAYLOAD`)
 */
data class VideoPacketHeader(
    val frameId: Long,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val ptsUs: Long,
    val payloadLength: Int,
    val flags: Int = 0
) {
    init {
        require(frameId in 0L..0xFFFF_FFFFL) {
            "frameId out of UInt range: $frameId"
        }
        require(fragmentCount in 1..0xFFFF) {
            "fragmentCount out of range (1..65535): $fragmentCount"
        }
        require(fragmentIndex in 0 until fragmentCount) {
            "fragmentIndex $fragmentIndex must be in 0 until $fragmentCount"
        }
        require(payloadLength in 0..MAX_PAYLOAD) {
            "payloadLength $payloadLength exceeds MAX_PAYLOAD ($MAX_PAYLOAD)"
        }
        require(flags in 0..0xFF) {
            "flags out of UByte range: $flags"
        }
    }

    val isKeyframe: Boolean
        get() = (flags and FLAG_KEYFRAME) != 0

    val isCodecConfig: Boolean
        get() = (flags and FLAG_CODEC_CONFIG) != 0

    /**
     * Serializes this header into [buf] in big-endian byte order, advancing its position by [SIZE].
     */
    fun writeTo(buf: ByteBuffer) {
        require(buf.remaining() >= SIZE) {
            "Buffer needs at least $SIZE bytes, has ${buf.remaining()}"
        }
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putShort(ProtocolInfo.MAGIC)
        buf.put(ProtocolInfo.PROTOCOL_VERSION.toByte())
        buf.put(flags.toByte())
        buf.putInt(frameId.toInt())
        buf.putShort(fragmentIndex.toShort())
        buf.putShort(fragmentCount.toShort())
        buf.putLong(ptsUs)
        buf.putShort(payloadLength.toShort())
    }

    companion object {
        /** Fixed header size in bytes. */
        const val SIZE: Int = 22

        /** Maximum video payload bytes per UDP datagram. */
        const val MAX_PAYLOAD: Int = NetworkConfig.MAX_DATAGRAM_SIZE - SIZE

        /** Flag bit indicating this frame is an IDR / sync keyframe. */
        const val FLAG_KEYFRAME: Int = 1

        /** Flag bit indicating this packet carries H.264 SPS/PPS codec config data. */
        const val FLAG_CODEC_CONFIG: Int = 2

        /**
         * Reads a v1 header from [buf] without throwing on malformed or truncated datagrams.
         * Returns `null` if the buffer is too short or fails magic/version/bounds validation.
         */
        fun readFrom(buf: ByteBuffer): VideoPacketHeader? {
            if (buf.remaining() < SIZE) return null

            buf.order(ByteOrder.BIG_ENDIAN)
            val magic = buf.short
            if (magic != ProtocolInfo.MAGIC) return null

            val version = buf.get().toInt() and 0xFF
            if (version != ProtocolInfo.PROTOCOL_VERSION) return null

            val flags = buf.get().toInt() and 0xFF
            val frameId = buf.int.toLong() and 0xFFFF_FFFFL
            val fragmentIndex = buf.short.toInt() and 0xFFFF
            val fragmentCount = buf.short.toInt() and 0xFFFF
            val ptsUs = buf.long
            val payloadLength = buf.short.toInt() and 0xFFFF

            if (fragmentCount < 1 || fragmentIndex >= fragmentCount) return null
            if (payloadLength > MAX_PAYLOAD) return null

            return VideoPacketHeader(
                frameId = frameId,
                fragmentIndex = fragmentIndex,
                fragmentCount = fragmentCount,
                ptsUs = ptsUs,
                payloadLength = payloadLength,
                flags = flags
            )
        }
    }
}
