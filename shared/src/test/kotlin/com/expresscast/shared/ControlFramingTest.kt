package com.expresscast.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class ControlFramingTest {
    @Test
    fun roundTripAllControlMessages() {
        val messages = listOf(
            ControlMessage.Hello(
                protocolVersion = ProtocolInfo.PROTOCOL_VERSION,
                deviceName = "Pixel 8",
                role = DeviceRole.PHONE
            ),
            ControlMessage.HelloAck(
                protocolVersion = ProtocolInfo.PROTOCOL_VERSION,
                deviceName = "Living Room TV",
                accepted = true,
                requiresPairing = true,
                error = null
            ),
            ControlMessage.HelloAck(
                protocolVersion = 99,
                deviceName = "Old TV",
                accepted = false,
                requiresPairing = false,
                error = ExpressCastError.PROTOCOL_MISMATCH
            ),
            ControlMessage.PairRequest("4829"),
            ControlMessage.PairResult(accepted = true, error = null),
            ControlMessage.PairResult(accepted = false, error = ExpressCastError.PAIRING_REJECTED),
            ControlMessage.Ping(123456789L),
            ControlMessage.Pong(123456789L),
            ControlMessage.TextMessage("Hello from ExpressCast"),
            ControlMessage.StartStream(VideoStreamConfig.DEFAULT, NetworkConfig.DEFAULT_VIDEO_PORT),
            ControlMessage.StopStream,
            ControlMessage.RequestKeyframe,
            ControlMessage.Disconnect("User requested")
        )

        val out = ByteArrayOutputStream()
        messages.forEach { ControlFraming.writeFrame(out, it) }

        val input = ByteArrayInputStream(out.toByteArray())
        val decoded = mutableListOf<ControlMessage>()
        while (true) {
            val next = ControlFraming.readFrame(input) ?: break
            decoded.add(next)
        }

        assertEquals(messages, decoded)
    }

    @Test
    fun readFrameReturnsNullOnEmptyStream() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertNull(ControlFraming.readFrame(input))
    }

    @Test(expected = IOException::class)
    fun readFrameRejectsOversizedFrame() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeInt(ControlFraming.MAX_FRAME_BYTES + 1)
        ControlFraming.readFrame(ByteArrayInputStream(out.toByteArray()))
    }

    @Test(expected = IOException::class)
    fun decodeRejectsUnknownMessageType() {
        ControlFraming.decode(byteArrayOf(127))
    }
}
