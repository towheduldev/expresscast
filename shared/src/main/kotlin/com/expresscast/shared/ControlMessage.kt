package com.expresscast.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Role of an ExpressCast endpoint on the control channel.
 */
enum class DeviceRole(val code: Byte) {
    PHONE(1),
    TV(2);

    companion object {
        fun fromCode(code: Byte): DeviceRole? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Messages exchanged over the reliable TCP control channel.
 */
sealed interface ControlMessage {
    data class Hello(
        val protocolVersion: Int,
        val deviceName: String,
        val role: DeviceRole
    ) : ControlMessage

    data class HelloAck(
        val protocolVersion: Int,
        val deviceName: String,
        val accepted: Boolean,
        val requiresPairing: Boolean,
        val error: ExpressCastError? = null
    ) : ControlMessage

    data class PairRequest(val pin: String) : ControlMessage

    data class PairResult(
        val accepted: Boolean,
        val error: ExpressCastError? = null
    ) : ControlMessage

    data class Ping(val timestampMs: Long) : ControlMessage

    data class Pong(val timestampMs: Long) : ControlMessage

    data class TextMessage(val text: String) : ControlMessage

    data class StartStream(
        val config: VideoStreamConfig,
        val videoPort: Int = NetworkConfig.DEFAULT_VIDEO_PORT
    ) : ControlMessage

    data object StopStream : ControlMessage

    data object RequestKeyframe : ControlMessage

    data class Disconnect(val reason: String = "") : ControlMessage
}

/**
 * Length-prefixed binary framing for [ControlMessage] over TCP streams.
 *
 * Wire layout per frame:
 * - `length` (4-byte big-endian int): byte count of `[type + payload]`
 * - `type` (1-byte): message type identifier
 * - `payload` (`length - 1` bytes): big-endian fields
 */
object ControlFraming {
    const val MAX_FRAME_BYTES: Int = 64 * 1024

    private const val TYPE_HELLO: Byte = 1
    private const val TYPE_HELLO_ACK: Byte = 2
    private const val TYPE_PAIR_REQUEST: Byte = 3
    private const val TYPE_PAIR_RESULT: Byte = 4
    private const val TYPE_PING: Byte = 5
    private const val TYPE_PONG: Byte = 6
    private const val TYPE_TEXT: Byte = 7
    private const val TYPE_DISCONNECT: Byte = 8
    private const val TYPE_START_STREAM: Byte = 9
    private const val TYPE_STOP_STREAM: Byte = 10
    private const val TYPE_REQUEST_KEYFRAME: Byte = 11

    fun encode(message: ControlMessage): ByteArray {
        val bodyStream = ByteArrayOutputStream()
        val body = DataOutputStream(bodyStream)
        when (message) {
            is ControlMessage.Hello -> {
                body.writeByte(TYPE_HELLO.toInt())
                body.writeInt(message.protocolVersion)
                body.writeUTF(message.deviceName)
                body.writeByte(message.role.code.toInt())
            }
            is ControlMessage.HelloAck -> {
                body.writeByte(TYPE_HELLO_ACK.toInt())
                body.writeInt(message.protocolVersion)
                body.writeUTF(message.deviceName)
                body.writeBoolean(message.accepted)
                body.writeBoolean(message.requiresPairing)
                body.writeInt(message.error?.code ?: 0)
            }
            is ControlMessage.PairRequest -> {
                body.writeByte(TYPE_PAIR_REQUEST.toInt())
                body.writeUTF(message.pin)
            }
            is ControlMessage.PairResult -> {
                body.writeByte(TYPE_PAIR_RESULT.toInt())
                body.writeBoolean(message.accepted)
                body.writeInt(message.error?.code ?: 0)
            }
            is ControlMessage.Ping -> {
                body.writeByte(TYPE_PING.toInt())
                body.writeLong(message.timestampMs)
            }
            is ControlMessage.Pong -> {
                body.writeByte(TYPE_PONG.toInt())
                body.writeLong(message.timestampMs)
            }
            is ControlMessage.TextMessage -> {
                body.writeByte(TYPE_TEXT.toInt())
                body.writeUTF(message.text)
            }
            is ControlMessage.StartStream -> {
                body.writeByte(TYPE_START_STREAM.toInt())
                body.writeInt(message.config.width)
                body.writeInt(message.config.height)
                body.writeInt(message.config.fps)
                body.writeInt(message.config.bitrateBps)
                body.writeInt(message.config.keyframeIntervalSec)
                body.writeInt(message.videoPort)
            }
            ControlMessage.StopStream -> {
                body.writeByte(TYPE_STOP_STREAM.toInt())
            }
            ControlMessage.RequestKeyframe -> {
                body.writeByte(TYPE_REQUEST_KEYFRAME.toInt())
            }
            is ControlMessage.Disconnect -> {
                body.writeByte(TYPE_DISCONNECT.toInt())
                body.writeUTF(message.reason)
            }
        }
        body.flush()
        return bodyStream.toByteArray()
    }

    fun decode(frameBytes: ByteArray): ControlMessage {
        if (frameBytes.isEmpty()) {
            throw IOException("Empty control frame")
        }
        val input = DataInputStream(ByteArrayInputStream(frameBytes))
        return when (val type = input.readByte()) {
            TYPE_HELLO -> {
                val version = input.readInt()
                val name = input.readUTF()
                val roleCode = input.readByte()
                val role = DeviceRole.fromCode(roleCode)
                    ?: throw IOException("Unknown device role code: $roleCode")
                ControlMessage.Hello(version, name, role)
            }
            TYPE_HELLO_ACK -> {
                val version = input.readInt()
                val name = input.readUTF()
                val accepted = input.readBoolean()
                val requiresPairing = input.readBoolean()
                val errorCode = input.readInt()
                val error = if (errorCode == 0) null else ExpressCastError.fromCode(errorCode)
                ControlMessage.HelloAck(version, name, accepted, requiresPairing, error)
            }
            TYPE_PAIR_REQUEST -> ControlMessage.PairRequest(input.readUTF())
            TYPE_PAIR_RESULT -> {
                val accepted = input.readBoolean()
                val errorCode = input.readInt()
                val error = if (errorCode == 0) null else ExpressCastError.fromCode(errorCode)
                ControlMessage.PairResult(accepted, error)
            }
            TYPE_PING -> ControlMessage.Ping(input.readLong())
            TYPE_PONG -> ControlMessage.Pong(input.readLong())
            TYPE_TEXT -> ControlMessage.TextMessage(input.readUTF())
            TYPE_START_STREAM -> {
                val width = input.readInt()
                val height = input.readInt()
                val fps = input.readInt()
                val bitrateBps = input.readInt()
                val keyframeIntervalSec = input.readInt()
                val videoPort = input.readInt()
                ControlMessage.StartStream(
                    config = VideoStreamConfig(width, height, fps, bitrateBps, keyframeIntervalSec),
                    videoPort = videoPort
                )
            }
            TYPE_STOP_STREAM -> ControlMessage.StopStream
            TYPE_REQUEST_KEYFRAME -> ControlMessage.RequestKeyframe
            TYPE_DISCONNECT -> ControlMessage.Disconnect(input.readUTF())
            else -> throw IOException("Unknown control message type: $type")
        }
    }

    fun writeFrame(output: OutputStream, message: ControlMessage) {
        val payload = encode(message)
        if (payload.size > MAX_FRAME_BYTES) {
            throw IOException("Control frame too large: ${payload.size} bytes")
        }
        val dataOut = DataOutputStream(output)
        dataOut.writeInt(payload.size)
        dataOut.write(payload)
        dataOut.flush()
    }

    /**
     * Reads one framed [ControlMessage], or returns null on clean EOF before the frame header.
     */
    fun readFrame(input: InputStream): ControlMessage? {
        val dataIn = DataInputStream(input)
        val length = try {
            dataIn.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw IOException("Invalid control frame length: $length")
        }
        val payload = ByteArray(length)
        dataIn.readFully(payload)
        return decode(payload)
    }
}
