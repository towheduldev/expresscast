package dev.towhedul.expresscast.tv.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import dev.towhedul.expresscast.shared.VideoPacketHeader
import dev.towhedul.expresscast.shared.VideoStreamConfig

/** Owns codec setup, input, output, and teardown on a single worker. */
class HardwareH264Decoder(
    private val config: VideoStreamConfig,
    private val outputSurface: Surface,
    private val onFpsUpdated: (Int) -> Unit,
    private val onVideoSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    private val onError: () -> Unit
) {
    private data class Frame(val data: ByteArray, val ptsUs: Long, val flags: Int, val enqueuedNs: Long) {
        val isKeyframe: Boolean get() = flags and VideoPacketHeader.FLAG_KEYFRAME != 0
        val isConfig: Boolean get() = flags and VideoPacketHeader.FLAG_CODEC_CONFIG != 0
    }

    private val queueLock = Object()
    private val pending = ArrayDeque<Frame>(MAX_PENDING_FRAMES)
    private var cachedConfig: Frame? = null
    private var generation = 0
    private var acceptsDelta = false

    @Volatile
    private var released = false

    @Volatile
    var waitingForSync = true
        private set

    @Volatile
    var ready = false
        private set

    private val worker = Thread(::runDecoder, "ExpressCast-H264Decoder").apply { isDaemon = true }

    fun start() = worker.start()

    fun offerFrame(data: ByteArray, ptsUs: Long, flags: Int) {
        synchronized(queueLock) {
            if (released) return
            val frame = Frame(data, ptsUs, flags, System.nanoTime())
            if (frame.isConfig && !frame.isKeyframe) {
                if (cachedConfig?.data?.contentEquals(data) == true) return
                if (cachedConfig != null) resetQueue()
                cachedConfig = frame
                queueLock.notify()
                return
            }
            if (pending.size == MAX_PENDING_FRAMES) resetQueue()
            if (!frame.isKeyframe && !acceptsDelta) return
            pending.addLast(frame)
            acceptsDelta = true
            queueLock.notify()
        }
    }

    fun onFrameDropped() {
        synchronized(queueLock) { resetQueue() }
    }

    private fun resetQueue() {
        if (acceptsDelta || pending.isNotEmpty()) generation++
        pending.clear()
        acceptsDelta = false
        waitingForSync = true
        queueLock.notify()
    }

    fun stop() {
        synchronized(queueLock) {
            released = true
            pending.clear()
            cachedConfig = null
            queueLock.notify()
        }
    }

    private fun runDecoder() {
        if (released) return
        val codec = try {
            openDecoder()
        } catch (e: Exception) {
            if (!released) {
                Log.e(TAG, "Unable to initialize AVC decoder for $config", e)
                onError()
            }
            return
        }
        try {
            ready = true
            decodeLoop(codec)
        } catch (e: Exception) {
            if (!released) {
                Log.e(TAG, "Decoder worker failed", e)
                onError()
            }
        } finally {
            ready = false
            releaseCodec(codec)
        }
    }

    private fun decodeLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var activeGeneration = 0
        var submittedConfig: Frame? = null
        var haveInput = false
        var lastInputNs = 0L
        var fpsSinceNs = System.nanoTime()
        var frames = 0
        var lastFps = -1

        while (!released) {
            val (frame, csd, currentGeneration) = synchronized(queueLock) {
                val recentlyQueued = haveInput && System.nanoTime() - lastInputNs < MAX_QUEUE_AGE_NS
                if (!released && pending.isEmpty() && generation == activeGeneration && !recentlyQueued) {
                    queueLock.wait(250)
                }
                Triple(pending.firstOrNull(), cachedConfig, generation)
            }
            if (released) return
            if (currentGeneration != activeGeneration) {
                if (haveInput) codec.flush()
                haveInput = false
                submittedConfig = null
                activeGeneration = currentGeneration
            }

            val now = System.nanoTime()
            val outputIndex = codec.dequeueOutputBuffer(
                info,
                if (frame == null && haveInput && now - lastInputNs < MAX_QUEUE_AGE_NS) 2_000L else 0L
            )
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = codec.outputFormat
                    val w = if (fmt.containsKey("crop-right") && fmt.containsKey("crop-left")) {
                        fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
                    } else if (fmt.containsKey(MediaFormat.KEY_WIDTH)) {
                        fmt.getInteger(MediaFormat.KEY_WIDTH)
                    } else {
                        0
                    }
                    val h = if (fmt.containsKey("crop-bottom") && fmt.containsKey("crop-top")) {
                        fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
                    } else if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) {
                        fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    } else {
                        0
                    }
                    if (w > 0 && h > 0) {
                        onVideoSizeChanged(w, h)
                    }
                    Log.i(TAG, "Decoder output format changed: ${w}x${h} ($fmt)")
                }
                outputIndex >= 0 -> {
                    val render = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        synchronized(queueLock) { generation == activeGeneration }
                    if (render) {
                        codec.releaseOutputBuffer(outputIndex, System.nanoTime())
                        frames++
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            val elapsed = now - fpsSinceNs
            if (elapsed >= 1_000_000_000L) {
                val fps = (frames * 1_000_000_000L / elapsed).toInt()
                if (fps != lastFps) onFpsUpdated(fps)
                lastFps = fps
                fpsSinceNs = now
                frames = 0
            }

            if (frame == null) continue
            if (now - frame.enqueuedNs > MAX_QUEUE_AGE_NS || (waitingForSync && !frame.isKeyframe)) {
                synchronized(queueLock) { if (generation == activeGeneration) resetQueue() }
                continue
            }
            if (waitingForSync && csd == null && !hasInlineParameterSets(frame.data)) {
                synchronized(queueLock) { if (generation == activeGeneration) resetQueue() }
                continue
            }
            val source = csd?.takeIf { it !== submittedConfig } ?: frame
            val input = codec.dequeueInputBuffer(2_000L)
            if (input < 0) continue
            haveInput = true
            val buffer = codec.getInputBuffer(input)
            if (buffer == null || buffer.capacity() < source.data.size) {
                Log.w(TAG, "AVC access unit exceeds decoder input capacity")
                synchronized(queueLock) { if (generation == activeGeneration) resetQueue() }
                continue
            }
            buffer.clear()
            buffer.put(source.data)
            val codecFlags = when {
                source.isConfig && !source.isKeyframe -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                source.isKeyframe -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                else -> 0
            }
            codec.queueInputBuffer(input, 0, source.data.size, source.ptsUs, codecFlags)
            lastInputNs = System.nanoTime()
            if (source !== frame) {
                submittedConfig = source
            } else {
                synchronized(queueLock) {
                    if (generation == activeGeneration && pending.firstOrNull() === frame) {
                        pending.removeFirst()
                        if (frame.isKeyframe) waitingForSync = false
                    }
                }
            }
        }
    }

    private fun hasInlineParameterSets(data: ByteArray): Boolean {
        var hasSps = false
        var hasPps = false
        for (i in 0 until data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                when (data[i + 3].toInt() and 0x1F) {
                    7 -> hasSps = true
                    8 -> hasPps = true
                }
                if (hasSps && hasPps) return true
            }
        }
        return false
    }

    private fun openDecoder(): MediaCodec {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) } }
            .sortedByDescending(::isHardwareDecoder)
        var lastFailure: Exception? = null
        for (info in decoders) {
            val caps = info.getCapabilitiesForType(MIME)
            val supported = caps.videoCapabilities?.areSizeAndRateSupported(config.width, config.height, config.fps.toDouble()) == true
            if (!supported) continue
            val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            val modes = if (lowLatency) listOf(true, false) else listOf(false)
            for (useLowLatency in modes) {
                val format = MediaFormat.createVideoFormat(MIME, config.width, config.height).apply {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && useLowLatency) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
                var candidate: MediaCodec? = null
                try {
                    candidate = MediaCodec.createByCodecName(info.name)
                    candidate.configure(format, outputSurface, null, 0)
                    candidate.start()
                    Log.i(TAG, "Started AVC decoder ${info.name}, lowLatency=$useLowLatency")
                    return candidate
                } catch (e: Exception) {
                    lastFailure = e
                    Log.w(TAG, "AVC decoder rejected $config, lowLatency=$useLowLatency", e)
                    candidate?.let(::releaseCodec)
                }
            }
        }
        throw IllegalStateException("No AVC decoder could start for $config", lastFailure)
    }

    private fun releaseCodec(codec: MediaCodec) {
        try {
            codec.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AVC decoder", e)
        }
    }

    private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
    }

    private companion object {
        const val TAG = "ExpressCast.H264Dec"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val MAX_PENDING_FRAMES = 3
        const val MAX_QUEUE_AGE_NS = 50_000_000L
    }
}
