package dev.towhedul.expresscast.capture

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import dev.towhedul.expresscast.audio.AudioCaptureSender
import dev.towhedul.expresscast.codec.HardwareH264Encoder
import dev.towhedul.expresscast.network.UdpVideoSender
import dev.towhedul.expresscast.settings.AspectCropMode
import dev.towhedul.expresscast.shared.NetworkConfig
import dev.towhedul.expresscast.shared.VideoStreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptureStatus(
    val isStreaming: Boolean = false,
    val activeConfig: VideoStreamConfig? = null,
    val requestId: Long = 0L,
    val audioActive: Boolean = false,
    val framesEncoded: Long = 0L,
    val error: String? = null
)

/**
 * Foreground service owning [MediaProjection], [VirtualDisplay], [HardwareH264Encoder],
 * and [UdpVideoSender]. Started only after explicit user MediaProjection consent.
 */
class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var cropProcessor: CropSurfaceProcessor? = null
    private var encoder: HardwareH264Encoder? = null
    private var udpSender: UdpVideoSender? = null
    private var audioSender: AudioCaptureSender? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var activeCropMode: AspectCropMode = AspectCropMode.RATIO_16_9
    private var activeEncodeWidth: Int = 0
    private var activeEncodeHeight: Int = 0
    private var currentCaptureWidth: Int = 0
    private var currentCaptureHeight: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSyncRequestNs: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val targetHost = intent.getStringExtra(EXTRA_TARGET_HOST)
                val requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
                val videoPort = intent.getIntExtra(EXTRA_VIDEO_PORT, NetworkConfig.DEFAULT_VIDEO_PORT)
                val audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, true)
                val audioPort = intent.getIntExtra(EXTRA_AUDIO_PORT, NetworkConfig.DEFAULT_AUDIO_PORT)
                val streamConfig = VideoStreamConfig(
                    width = intent.getIntExtra(EXTRA_STREAM_WIDTH, VideoStreamConfig.DEFAULT.width),
                    height = intent.getIntExtra(EXTRA_STREAM_HEIGHT, VideoStreamConfig.DEFAULT.height),
                    fps = intent.getIntExtra(EXTRA_STREAM_FPS, VideoStreamConfig.DEFAULT.fps),
                    bitrateBps = intent.getIntExtra(EXTRA_STREAM_BITRATE, VideoStreamConfig.DEFAULT.bitrateBps),
                    keyframeIntervalSec = VideoStreamConfig.DEFAULT.keyframeIntervalSec
                )
                val cropMode = AspectCropMode.fromName(intent.getStringExtra(EXTRA_CROP_MODE))

                if (resultCode != Activity.RESULT_OK || resultData == null || targetHost.isNullOrBlank()) {
                    Log.e(TAG, "Invalid ACTION_START parameters; stopping service")
                    _status.value = CaptureStatus(requestId = requestId, error = "Missing MediaProjection consent or TV address")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundWithNotification()
                startCapturePipeline(resultCode, resultData, targetHost, videoPort, audioEnabled, audioPort, streamConfig, cropMode, requestId)
            }

            ACTION_REQUEST_KEYFRAME -> {
                requestEncoderSync()
            }

            ACTION_STOP -> {
                stopCapturePipeline()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (mediaProjection != null || encoder != null) stopCapturePipeline()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ExpressCast Screen Streaming",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ExpressCast Streaming")
            .setContentText("Casting game screen to Android TV")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapturePipeline(
        resultCode: Int,
        resultData: Intent,
        targetHost: String,
        videoPort: Int,
        audioEnabled: Boolean,
        audioPort: Int,
        requestedConfig: VideoStreamConfig,
        cropMode: AspectCropMode,
        requestId: Long
    ) {
        stopCapturePipeline()
        _status.value = CaptureStatus(requestId = requestId)
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                _status.value = CaptureStatus(requestId = requestId, error = "MediaProjection returned null")
                stopSelf()
                return
            }
            mediaProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (mediaProjection !== projection) return
                    Log.i(TAG, "MediaProjection stopped by system/user")
                    stopCapturePipeline()
                    stopSelf()
                }
            }, null)

            acquireLowLatencyWifiLock()

            lateinit var sender: UdpVideoSender
            sender = UdpVideoSender(targetHost, videoPort) {
                mainHandler.post { if (udpSender === sender) requestEncoderSync() }
            }
            udpSender = sender

            var frameCount = 0L
            val h264Encoder = HardwareH264Encoder(requestedConfig) { data, ptsUs, frameFlags ->
                sender.offerFrame(data, ptsUs, frameFlags)
                frameCount++
                if (frameCount == 1L || frameCount % 60L == 0L) {
                    _status.update {
                        if (it.requestId == requestId && it.isStreaming) it.copy(framesEncoded = frameCount) else it
                    }
                }
            }
            encoder = h264Encoder
            val cfg = h264Encoder.activeConfig
            h264Encoder.start()

            val dm = resources.displayMetrics
            val densityDpi = dm.densityDpi
            activeCropMode = cropMode
            activeEncodeWidth = cfg.width
            activeEncodeHeight = cfg.height

            val (capW, capH) = computeCaptureDimensions(dm.widthPixels, dm.heightPixels, cfg.width, cfg.height)
            val processor = CropSurfaceProcessor(
                captureWidth = capW,
                captureHeight = capH,
                encodeWidth = cfg.width,
                encodeHeight = cfg.height,
                fitInside = (cropMode == AspectCropMode.NATIVE),
                encoderInputSurface = h264Encoder.inputSurface
            )
            cropProcessor = processor
            val captureSurface = processor.startAndGetInputSurface()
            currentCaptureWidth = capW
            currentCaptureHeight = capH

            virtualDisplay = projection.createVirtualDisplay(
                "ExpressCastDisplay",
                capW,
                capH,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                captureSurface,
                null,
                null
            )
            registerOrientationListener()

            if (audioEnabled) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Log.i(TAG, "Internal audio playback capture requires Android 10+; streaming video only")
                } else {
                    val hasAudioPerm = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasAudioPerm) {
                        audioSender = AudioCaptureSender(projection, targetHost, audioPort).also {
                            it.start()
                        }
                    } else {
                        Log.w(TAG, "RECORD_AUDIO not granted; streaming video without audio")
                    }
                }
            }

            _status.value = CaptureStatus(
                isStreaming = true,
                activeConfig = cfg,
                requestId = requestId,
                audioActive = audioSender?.isRunning == true,
                framesEncoded = 0L,
                error = null
            )
            Log.i(TAG, "Capture pipeline started -> $targetHost:$videoPort (capture=${capW}x${capH}, encode=${cfg.width}x${cfg.height}@${cfg.fps}, crop=$cropMode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting capture pipeline", e)
            stopCapturePipeline()
            _status.value = CaptureStatus(requestId = requestId, error = e.message ?: "Capture start failed")
            stopSelf()
        }
    }

    private fun stopCapturePipeline() {
        unregisterOrientationListener()
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing VirtualDisplay: ${e.message}")
        }
        virtualDisplay = null

        cropProcessor?.release()
        cropProcessor = null

        udpSender?.close()
        udpSender = null

        encoder?.stop()
        encoder = null

        audioSender?.stop()
        audioSender = null

        val projection = mediaProjection
        mediaProjection = null
        try {
            projection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaProjection: ${e.message}")
        }
        releaseLowLatencyWifiLock()
        lastSyncRequestNs = null
        _status.value = CaptureStatus(requestId = _status.value.requestId)
    }

    private fun requestEncoderSync() {
        val activeEncoder = encoder ?: return
        val now = System.nanoTime()
        if (lastSyncRequestNs?.let { now - it < 100_000_000L } == true) return
        lastSyncRequestNs = now
        activeEncoder.requestSyncFrame()
    }

    private fun computeCaptureDimensions(
        displayWidth: Int,
        displayHeight: Int,
        encodeWidth: Int,
        encodeHeight: Int
    ): Pair<Int, Int> {
        val phoneAspect = displayWidth.toFloat() / displayHeight.coerceAtLeast(1)
        return if (phoneAspect >= 1f) {
            makeEven((encodeHeight * phoneAspect).toInt().coerceAtLeast(2)) to encodeHeight
        } else {
            val baseW = minOf(encodeWidth, 720)
            baseW to makeEven((baseW / phoneAspect).toInt().coerceAtLeast(2))
        }
    }

    private fun registerOrientationListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val metrics = resources.displayMetrics
                val (newW, newH) = computeCaptureDimensions(
                    metrics.widthPixels,
                    metrics.heightPixels,
                    activeEncodeWidth,
                    activeEncodeHeight
                )
                if (newW != currentCaptureWidth || newH != currentCaptureHeight) {
                    currentCaptureWidth = newW
                    currentCaptureHeight = newH
                    virtualDisplay?.resize(newW, newH, metrics.densityDpi)
                    cropProcessor?.updateCaptureSize(newW, newH)
                    Log.i(TAG, "Resized VirtualDisplay for rotation -> ${newW}x${newH}")
                }
            }
        }
        displayListener = listener
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun unregisterOrientationListener() {
        val listener = displayListener ?: return
        displayListener = null
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        dm.unregisterDisplayListener(listener)
    }

    private fun acquireLowLatencyWifiLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            if (wifiLock == null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(lockMode, "ExpressCast-LowLatency")
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire low-latency WifiLock: ${e.message}")
        }
    }

    private fun releaseLowLatencyWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to release WifiLock: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ExpressCast.CaptureSvc"
        private const val CHANNEL_ID = "expresscast_stream"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "dev.towhedul.expresscast.action.START_CAPTURE"
        const val ACTION_STOP = "dev.towhedul.expresscast.action.STOP_CAPTURE"
        const val ACTION_REQUEST_KEYFRAME = "dev.towhedul.expresscast.action.REQUEST_KEYFRAME"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_HOST = "target_host"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_VIDEO_PORT = "video_port"
        const val EXTRA_AUDIO_ENABLED = "audio_enabled"
        const val EXTRA_AUDIO_PORT = "audio_port"
        const val EXTRA_STREAM_WIDTH = "stream_width"
        const val EXTRA_STREAM_HEIGHT = "stream_height"
        const val EXTRA_STREAM_FPS = "stream_fps"
        const val EXTRA_STREAM_BITRATE = "stream_bitrate"
        const val EXTRA_CROP_MODE = "crop_mode"

        private fun makeEven(value: Int): Int = (value / 2) * 2

        private val _status = MutableStateFlow(CaptureStatus())
        val status: StateFlow<CaptureStatus> = _status.asStateFlow()

        fun requestKeyframe(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_REQUEST_KEYFRAME
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
