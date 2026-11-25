package dev.towhedul.expresscast.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import dev.towhedul.expresscast.shared.VideoPacketHeader
import dev.towhedul.expresscast.shared.VideoStreamConfig

fun interface EncodedFrameListener {
    fun onEncodedFrame(data: ByteArray, ptsUs: Long, flags: Int)
}

/**
 * Hardware-accelerated H.264 (AVC) encoder configured for low-latency Surface input.
 * Strictly decoupled from networking and UI layers.
 */
class HardwareH264Encoder(
    requestedConfig: VideoStreamConfig = VideoStreamConfig.DEFAULT,
    private val listener: EncodedFrameListener
) {
    val activeConfig: VideoStreamConfig
    private val codec: MediaCodec
    val inputSurface: Surface

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    init {
        val (codecInfo, rawSelectedConfig) = selectEncoderAndConfig(requestedConfig)
        val selectedConfig = capBitrateForFps(rawSelectedConfig)
        activeConfig = selectedConfig
        Log.i(
            TAG,
            "Selected H.264 encoder=${codecInfo.name} config=${selectedConfig.width}x${selectedConfig.height}@${selectedConfig.fps}fps (${selectedConfig.bitrateBps / 1000} kbps)"
        )

        val format = createFormat(codecInfo, selectedConfig, includeOperatingRate = true)
        codec = MediaCodec.createByCodecName(codecInfo.name)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Encoder rejected operating-rate format, retrying safe baseline: ${e.message}")
            codec.configure(
                createFormat(codecInfo, selectedConfig, includeOperatingRate = false),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        }
        inputSurface = codec.createInputSurface()
    }

    private fun createFormat(
        codecInfo: MediaCodecInfo,
        config: VideoStreamConfig,
        includeOperatingRate: Boolean
    ): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (includeOperatingRate) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, (config.fps * 2).coerceAtLeast(120))
            }
            val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).encoderCapabilities
            if (capabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        codec.start()
        drainThread = Thread({
            drainLoop()
        }, "ExpressCast-H264Encoder").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Hardware H.264 encoder started")
    }

    fun requestSyncFrame() {
        if (!running) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
            Log.d(TAG, "Requested IDR sync frame")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request sync frame: ${e.message}")
        }
    }

    fun updateBitrate(bitrateBps: Int) {
        if (!running) return
        val clamped = bitrateBps.coerceIn(1_000_000, 100_000_000)
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped)
            }
            codec.setParameters(params)
            Log.d(TAG, "Updated encoder bitrate -> ${clamped / 1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update encoder bitrate: ${e.message}")
        }
    }

    fun stop() {
        running = false
        drainThread?.interrupt()
        try {
            drainThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping encoder: ${e.message}")
        }
        try {
            inputSurface.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing inputSurface: ${e.message}")
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing encoder: ${e.message}")
        }
        Log.i(TAG, "Hardware H.264 encoder stopped")
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running) {
            try {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "Encoder output format changed: ${codec.outputFormat}")
                    }
                    outputIndex >= 0 -> {
                        val data = try {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                ByteArray(bufferInfo.size).also { outputBuffer.get(it) }
                            } else {
                                null
                            }
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                        if (data != null) {
                            var flags = 0
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                flags = flags or VideoPacketHeader.FLAG_KEYFRAME
                            }
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                flags = flags or VideoPacketHeader.FLAG_CODEC_CONFIG
                            }
                            listener.onEncodedFrame(data, bufferInfo.presentationTimeUs, flags)
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                if (running) {
                    Log.w(TAG, "Encoder drain IllegalStateException: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Unexpected error in encoder drain loop", e)
                }
                break
            }
        }
    }

    companion object {
        private const val TAG = "ExpressCast.H264Enc"

        fun capBitrateForFps(config: VideoStreamConfig): VideoStreamConfig {
            if (config.fps > 30) return config
            val maxSafeBps = (config.fps * 33_334L * 8L).toInt().coerceAtLeast(2_000_000)
            return if (config.bitrateBps > maxSafeBps) {
                config.copy(bitrateBps = maxSafeBps)
            } else {
                config
            }
        }

        fun selectEncoderAndConfig(requested: VideoStreamConfig): Pair<MediaCodecInfo, VideoStreamConfig> {
            val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
                .sortedByDescending(::isHardwareEncoder)

            val candidates = listOf(
                requested,
                requested.copy(fps = minOf(requested.fps, 60)),
                requested.copy(fps = minOf(requested.fps, 30)),
                VideoStreamConfig(
                    width = 1280,
                    height = 720,
                    fps = minOf(requested.fps, 60),
                    bitrateBps = minOf(requested.bitrateBps, 6_000_000).coerceAtLeast(1_000_000),
                    keyframeIntervalSec = requested.keyframeIntervalSec
                ),
                VideoStreamConfig(
                    width = 1280,
                    height = 720,
                    fps = minOf(requested.fps, 30),
                    bitrateBps = minOf(requested.bitrateBps, 4_000_000).coerceAtLeast(1_000_000),
                    keyframeIntervalSec = requested.keyframeIntervalSec
                )
            ).distinct()

            for (encoder in encoders) {
                val videoCaps = encoder.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: continue
                for (candidate in candidates) {
                    if (videoCaps.areSizeAndRateSupported(candidate.width, candidate.height, candidate.fps.toDouble())) {
                        return encoder to candidate
                    }
                }
            }

            val fallbackEncoder = encoders.firstOrNull()
                ?: throw IllegalStateException("No H.264 encoder available on this device")
            return fallbackEncoder to candidates.last()
        }

        fun querySupportedFps(width: Int, height: Int, candidates: List<Int>): Set<Int> {
            return try {
                val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
                    .sortedByDescending(::isHardwareEncoder)
                val primaryCaps = encoders.firstOrNull()
                    ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    ?.videoCapabilities
                    ?: return candidates.filter { it <= 60 }.toSet()
                candidates.filterTo(LinkedHashSet()) { fps ->
                    primaryCaps.areSizeAndRateSupported(width, height, fps.toDouble())
                }.ifEmpty { setOf(15, 30) }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to query supported FPS for ${width}x$height: ${e.message}")
                candidates.filter { it <= 60 }.toSet()
            }
        }

        private fun isHardwareEncoder(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
            val name = info.name.lowercase()
            return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }
    }
}
