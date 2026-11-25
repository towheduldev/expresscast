package dev.towhedul.expresscast.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.towhedul.expresscast.shared.AudioPacketHeader
import dev.towhedul.expresscast.shared.NetworkConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Captures internal device/game audio via [AudioPlaybackCaptureConfiguration] (Android 10+)
 * and streams 5 ms 48 kHz stereo PCM chunks over UDP.
 */
class AudioCaptureSender(
    private val mediaProjection: MediaProjection,
    private val targetHost: String,
    private val targetPort: Int = NetworkConfig.DEFAULT_AUDIO_PORT
) {
    @Volatile
    private var running = false
    val isRunning: Boolean
        get() = running
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var socket: DatagramSocket? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true

        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_BYTES * 4)

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 8))
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize (state=${record.state})")
                record.release()
                running = false
                return
            }

            audioRecord = record
            socket = DatagramSocket().apply {
                trafficClass = 0x10 // IPTOS_LOWDELAY
            }
            val targetAddress = InetAddress.getByName(targetHost)

            record.startRecording()
            Log.i(TAG, "Audio playback capture started -> $targetHost:$targetPort (48kHz stereo PCM)")

            captureThread = Thread({
                captureLoop(record, targetAddress)
            }, "ExpressCast-AudioTx").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioCaptureSender", e)
            stop()
        }
    }

    fun stop() {
        running = false
        captureThread?.interrupt()
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null

        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing audio UDP socket: ${e.message}")
        }
        socket = null
    }

    private fun captureLoop(record: AudioRecord, targetAddress: InetAddress) {
        val pcmBuffer = ByteArray(CHUNK_BYTES)
        val packetBuffer = ByteArray(AudioPacketHeader.SIZE + CHUNK_BYTES)
        val headerBuf = ByteBuffer.wrap(packetBuffer)
        val datagram = DatagramPacket(packetBuffer, packetBuffer.size, targetAddress, targetPort)
        var seq = 0L

        while (running) {
            val read = record.read(pcmBuffer, 0, pcmBuffer.size, AudioRecord.READ_BLOCKING)
            if (read < 0) {
                if (running) Log.w(TAG, "Stopping audio capture after AudioRecord error $read")
                running = false
                break
            }
            if (read == 0) continue

            val header = AudioPacketHeader(
                sequence = seq++ and 0xFFFF_FFFFL,
                sampleRate = SAMPLE_RATE,
                channelCount = CHANNEL_COUNT,
                payloadLength = read
            )
            headerBuf.clear()
            header.writeTo(headerBuf)
            System.arraycopy(pcmBuffer, 0, packetBuffer, AudioPacketHeader.SIZE, read)

            try {
                datagram.length = AudioPacketHeader.SIZE + read
                socket?.send(datagram)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Audio UDP send failed: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "ExpressCast.AudioTx"
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 2
        const val CHUNK_BYTES = 960 // 5 ms @ 48kHz, 16-bit stereo
    }
}
