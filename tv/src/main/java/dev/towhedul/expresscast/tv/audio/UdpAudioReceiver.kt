package dev.towhedul.expresscast.tv.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import dev.towhedul.expresscast.shared.AudioPacketHeader
import dev.towhedul.expresscast.shared.NetworkConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

/** Receives PCM without waiting for the output device to accept audio. */
class UdpAudioReceiver(
    private val port: Int,
    private val peerAddress: InetAddress?
) {
    private data class Chunk(val header: AudioPacketHeader, val data: ByteArray, val arrivedNs: Long)

    private val chunks = ArrayBlockingQueue<Chunk>(3)

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    private val playbackWorker = Thread(::playbackLoop, "ExpressCast-AudioPlayback").apply { isDaemon = true }

    fun start() {
        if (running) return
        running = true
        playbackWorker.start()
        Thread(::receiveLoop, "ExpressCast-UdpAudioRx").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        socket?.close()
        chunks.clear()
        playbackWorker.interrupt()
    }

    private fun receiveLoop() {
        try {
            DatagramSocket(port).use { rx ->
                socket = rx
                rx.receiveBufferSize = 32 * 1024
                val buffer = ByteArray(NetworkConfig.MAX_DATAGRAM_SIZE)
                val bytes = ByteBuffer.wrap(buffer)
                val packet = DatagramPacket(buffer, buffer.size)
                var lastSeq: Long? = null

                while (running) {
                    packet.length = buffer.size
                    rx.receive(packet)
                    val arrival = System.nanoTime()
                    if (peerAddress != null && packet.address != peerAddress) continue
                    bytes.clear()
                    bytes.limit(packet.length)
                    val header = AudioPacketHeader.readFrom(bytes) ?: continue
                    val sampleBytes = header.channelCount * 2
                    if (header.payloadLength != bytes.remaining() || header.payloadLength == 0 ||
                        header.payloadLength % sampleBytes != 0
                    ) continue

                    val previous = lastSeq
                    if (previous != null) {
                        val distance = (header.sequence - previous) and 0xFFFF_FFFFL
                        if (distance == 0L || distance >= 0x8000_0000L) continue
                    }
                    lastSeq = header.sequence

                    val chunk = Chunk(header, buffer.copyOfRange(AudioPacketHeader.SIZE, packet.length), arrival)
                    if (!chunks.offer(chunk)) {
                        chunks.poll()
                        chunks.offer(chunk)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Audio UDP receiver failed", e)
        } finally {
            running = false
            playbackWorker.interrupt()
        }
    }

    private fun playbackLoop() {
        var track: AudioTrack? = null
        var sampleRate = 0
        var channels = 0
        try {
            while (running) {
                val chunk = chunks.take()
                if (System.nanoTime() - chunk.arrivedNs > MAX_CHUNK_AGE_NS) continue
                if (chunk.header.sampleRate != sampleRate || chunk.header.channelCount != channels) {
                    track?.let(::releaseTrack)
                    track = null
                    sampleRate = chunk.header.sampleRate
                    channels = chunk.header.channelCount
                    track = createTrack(sampleRate, channels)
                    track.play()
                }
                val sink = checkNotNull(track)
                var offset = 0
                while (running && offset < chunk.data.size && System.nanoTime() - chunk.arrivedNs <= MAX_CHUNK_AGE_NS) {
                    val written = sink.write(chunk.data, offset, chunk.data.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    if (written < 0) throw IllegalStateException("AudioTrack write failed with code $written")
                    if (written == 0) Thread.sleep(2) else offset += written
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Audio playback failed", e)
        } finally {
            running = false
            socket?.close()
            track?.let(::releaseTrack)
        }
    }

    private fun createTrack(sampleRate: Int, channels: Int): AudioTrack {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "Unsupported audio output format $sampleRate Hz, $channels channels" }

        return AudioTrack.Builder()
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
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBytes.coerceAtLeast(1920) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            track.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack", e)
        }
    }

    private companion object {
        const val TAG = "ExpressCast.AudioRx"
        const val MAX_CHUNK_AGE_NS = 25_000_000L
    }
}
