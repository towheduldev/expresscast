package com.expresscast.tv.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.expresscast.shared.AudioPacketHeader
import com.expresscast.shared.NetworkConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.nio.ByteBuffer

/**
 * Receives UDP PCM audio packets and plays them with low-latency [AudioTrack].
 */
class UdpAudioReceiver(
    private val port: Int = NetworkConfig.DEFAULT_AUDIO_PORT
) {
    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    private var receiveThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        receiveThread = Thread({
            runReceiveLoop()
        }, "ExpressCast-UdpAudioRx").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing audio UDP socket: ${e.message}")
        }
        socket = null
        releaseTrack()
    }

    private fun runReceiveLoop() {
        try {
            val rxSocket = DatagramSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 128 * 1024
            }
            socket = rxSocket
            Log.i(TAG, "UDP audio receiver listening on port $port")

            val buffer = ByteArray(NetworkConfig.MAX_DATAGRAM_SIZE + 64)
            val packet = DatagramPacket(buffer, buffer.size)
            var lastSeq = -1L

            while (running && !rxSocket.isClosed) {
                rxSocket.receive(packet)
                if (packet.length < AudioPacketHeader.SIZE) continue

                val buf = ByteBuffer.wrap(packet.data, 0, packet.length)
                val header = AudioPacketHeader.readFrom(buf) ?: continue
                if (buf.remaining() < header.payloadLength) continue

                if (lastSeq != -1L && header.sequence <= lastSeq && !isWrapAround(lastSeq, header.sequence)) {
                    continue
                }
                lastSeq = header.sequence

                val track = ensureAudioTrack(header.sampleRate, header.channelCount) ?: continue
                track.write(
                    packet.data,
                    AudioPacketHeader.SIZE,
                    header.payloadLength,
                    AudioTrack.WRITE_BLOCKING
                )
            }
        } catch (e: SocketException) {
            if (running) {
                Log.w(TAG, "Audio UDP socket error: ${e.message}")
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Unexpected error in audio receiver loop", e)
            }
        }
    }

    private fun ensureAudioTrack(sampleRate: Int, channelCount: Int): AudioTrack? {
        audioTrack?.let { return it }

        val channelOut = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelOut,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1920)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelOut)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        track.play()
        audioTrack = track
        Log.i(TAG, "Started AudioTrack (${sampleRate}Hz, ch=$channelCount, buf=${minBuf * 2})")
        return track
    }

    private fun releaseTrack() {
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
        }
        audioTrack = null
    }

    private fun isWrapAround(prev: Long, next: Long): Boolean {
        return prev > 0xFFFF_0000L && next < 0x0000_FFFFL
    }

    private companion object {
        const val TAG = "ExpressCast.AudioRx"
    }
}
