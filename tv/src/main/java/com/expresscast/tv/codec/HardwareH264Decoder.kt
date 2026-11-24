package com.expresscast.tv.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.expresscast.shared.VideoPacketHeader
import com.expresscast.shared.VideoStreamConfig

/**
 * Hardware-accelerated H.264 (AVC) decoder bound directly to a [SurfaceView] surface.
 * Strictly decoupled from networking and UI layers.
 */
class HardwareH264Decoder(
    config: VideoStreamConfig,
    outputSurface: Surface
) {
    private val codec: MediaCodec
    private val lifecycleLock = Any()

    @Volatile
    private var released = false

    @Volatile
    private var hasReceivedConfig = false

    @Volatile
    private var hasReceivedKeyframe = false

    private var renderedFrames = 0L
    private val drainThread: Thread

    init {
        val codecInfo = selectDecoder()
        Log.i(TAG, "Selected H.264 decoder=${codecInfo.name} for ${config.width}x${config.height}")

        val format = createFormat(config, includeOperatingRate = true)
        codec = MediaCodec.createByCodecName(codecInfo.name)
        try {
            codec.configure(format, outputSurface, null, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Decoder rejected operating-rate format, retrying safe baseline: ${e.message}")
            codec.configure(
                createFormat(config, includeOperatingRate = false),
                outputSurface,
                null,
                0
            )
        }
        codec.start()

        drainThread = Thread({
            drainLoop()
        }, "ExpressCast-H264DecOut").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun createFormat(config: VideoStreamConfig, includeOperatingRate: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (includeOperatingRate) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, (config.fps * 2).coerceAtLeast(120))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
    }

    fun submitFrame(data: ByteArray, ptsUs: Long, flags: Int): Boolean {
        if (released) return false

        val isConfig = (flags and VideoPacketHeader.FLAG_CODEC_CONFIG) != 0
        val isKeyframe = (flags and VideoPacketHeader.FLAG_KEYFRAME) != 0

        if (isConfig) {
            hasReceivedConfig = true
            Log.i(TAG, "Received SPS/PPS codec config (${data.size} bytes)")
        } else {
            if (isKeyframe && !hasReceivedKeyframe) {
                Log.i(TAG, "Received first IDR keyframe (${data.size} bytes)")
            }
            if (isKeyframe) {
                hasReceivedKeyframe = true
            }
            if (!hasReceivedKeyframe) {
                return false
            }
        }

        return try {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex < 0) {
                return false
            }
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return false
            inputBuffer.clear()
            inputBuffer.put(data)

            var codecFlags = 0
            if (isConfig) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            }
            if (isKeyframe) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            codec.queueInputBuffer(inputIndex, 0, data.size, ptsUs, codecFlags)
            true
        } catch (e: IllegalStateException) {
            if (!released) {
                Log.w(TAG, "Decoder input state error: ${e.message}")
            }
            false
        } catch (e: Exception) {
            if (!released) {
                Log.e(TAG, "Unexpected decoder input error", e)
            }
            false
        }
    }

    fun onFrameDropped() {
        hasReceivedKeyframe = false
    }

    fun flush() {
        synchronized(lifecycleLock) {
            if (released) return
            try {
                codec.flush()
                hasReceivedKeyframe = false
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing decoder: ${e.message}")
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            if (released) return
            released = true
            drainThread.interrupt()
            try {
                codec.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping decoder: ${e.message}")
            }
            try {
                codec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing decoder: ${e.message}")
            }
            Log.i(TAG, "Hardware H.264 decoder stopped (rendered=$renderedFrames)")
        }
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (!released) {
            try {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "Decoder output format changed: ${codec.outputFormat}")
                    }
                    outputIndex >= 0 -> {
                        val render = bufferInfo.size > 0
                        if (render) {
                            codec.releaseOutputBuffer(outputIndex, System.nanoTime())
                            renderedFrames++
                            if (renderedFrames == 1L || renderedFrames % 120L == 0L) {
                                Log.i(TAG, "Rendered decoded frame #$renderedFrames")
                            }
                        } else {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                if (!released) {
                    Log.w(TAG, "Decoder drain state error: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (!released) {
                    Log.e(TAG, "Unexpected decoder drain error", e)
                }
                break
            }
        }
    }

    companion object {
        private const val TAG = "ExpressCast.H264Dec"

        fun selectDecoder(): MediaCodecInfo {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
                .sortedByDescending { isHardwareDecoder(it) }

            return decoders.firstOrNull()
                ?: throw IllegalStateException("No H.264 decoder available on this TV")
        }

        private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return info.isHardwareAccelerated
            }
            val name = info.name.lowercase()
            return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }
    }
}
