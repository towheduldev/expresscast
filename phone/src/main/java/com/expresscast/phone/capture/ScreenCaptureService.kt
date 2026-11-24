package com.expresscast.phone.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.expresscast.phone.codec.HardwareH264Encoder
import com.expresscast.phone.network.UdpVideoSender
import com.expresscast.phone.settings.AspectCropMode
import com.expresscast.shared.NetworkConfig
import com.expresscast.shared.VideoPacketHeader
import com.expresscast.shared.VideoStreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureStatus(
    val isStreaming: Boolean = false,
    val activeConfig: VideoStreamConfig? = null,
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
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var cachedSpsPps: ByteArray? = null

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
                val videoPort = intent.getIntExtra(EXTRA_VIDEO_PORT, NetworkConfig.DEFAULT_VIDEO_PORT)
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
                    _status.value = CaptureStatus(error = "Missing MediaProjection consent or TV address")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundWithNotification()
                startCapturePipeline(resultCode, resultData, targetHost, videoPort, streamConfig, cropMode)
            }

            ACTION_REQUEST_KEYFRAME -> {
                encoder?.requestSyncFrame()
            }

            ACTION_STOP -> {
                stopCapturePipeline()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapturePipeline()
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
        requestedConfig: VideoStreamConfig,
        cropMode: AspectCropMode
    ) {
        stopCapturePipeline()
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                _status.value = CaptureStatus(error = "MediaProjection returned null")
                stopSelf()
                return
            }
            mediaProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system/user")
                    stopCapturePipeline()
                    stopSelf()
                }
            }, null)

            acquireLowLatencyWifiLock()

            val sender = UdpVideoSender(targetHost, videoPort)
            udpSender = sender

            var frameCount = 0L
            val h264Encoder = HardwareH264Encoder(requestedConfig) { data, ptsUs, frameFlags ->
                val isConfig = (frameFlags and VideoPacketHeader.FLAG_CODEC_CONFIG) != 0
                val isKeyframe = (frameFlags and VideoPacketHeader.FLAG_KEYFRAME) != 0
                if (isConfig) {
                    cachedSpsPps = data
                } else if (isKeyframe) {
                    cachedSpsPps?.let { spsPps ->
                        sender.sendFrame(spsPps, ptsUs, VideoPacketHeader.FLAG_CODEC_CONFIG)
                    }
                }
                sender.sendFrame(data, ptsUs, frameFlags)
                frameCount++
                if (frameCount == 1L || frameCount % 60L == 0L) {
                    Log.d(TAG, "Encoded & sent frame #$frameCount (bytes=${data.size}, flags=$frameFlags)")
                    _status.value = _status.value.copy(framesEncoded = frameCount)
                }
            }
            encoder = h264Encoder
            val cfg = h264Encoder.activeConfig
            h264Encoder.start()

            val dm = resources.displayMetrics
            val densityDpi = dm.densityDpi
            val (captureW, captureH, captureSurface) = if (cropMode == AspectCropMode.NATIVE) {
                Triple(cfg.width, cfg.height, h264Encoder.inputSurface)
            } else {
                val phoneAspect = dm.widthPixels.toFloat() / dm.heightPixels.coerceAtLeast(1)
                val (capW, capH) = if (phoneAspect >= 1f) {
                    makeEven((cfg.height * phoneAspect).toInt().coerceAtLeast(2)) to cfg.height
                } else {
                    val baseW = minOf(cfg.width, 720)
                    baseW to makeEven((baseW / phoneAspect).toInt().coerceAtLeast(2))
                }
                val processor = CropSurfaceProcessor(capW, capH, cfg.width, cfg.height, h264Encoder.inputSurface)
                cropProcessor = processor
                Triple(capW, capH, processor.startAndGetInputSurface())
            }

            virtualDisplay = projection.createVirtualDisplay(
                "ExpressCastDisplay",
                captureW,
                captureH,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                captureSurface,
                null,
                null
            )

            _status.value = CaptureStatus(
                isStreaming = true,
                activeConfig = cfg,
                framesEncoded = 0L,
                error = null
            )
            Log.i(TAG, "Capture pipeline started -> $targetHost:$videoPort (capture=${captureW}x${captureH}, encode=${cfg.width}x${cfg.height}@${cfg.fps}, crop=$cropMode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting capture pipeline", e)
            _status.value = CaptureStatus(error = e.message ?: "Capture start failed")
            stopCapturePipeline()
            stopSelf()
        }
    }

    private fun stopCapturePipeline() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing VirtualDisplay: ${e.message}")
        }
        virtualDisplay = null

        cropProcessor?.release()
        cropProcessor = null

        encoder?.stop()
        encoder = null

        udpSender?.close()
        udpSender = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaProjection: ${e.message}")
        }
        mediaProjection = null

        releaseLowLatencyWifiLock()
        cachedSpsPps = null
        _status.value = CaptureStatus(isStreaming = false)
    }

    private fun acquireLowLatencyWifiLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            if (wifiLock == null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ExpressCast-LowLatency")
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

        const val ACTION_START = "com.expresscast.phone.action.START_CAPTURE"
        const val ACTION_STOP = "com.expresscast.phone.action.STOP_CAPTURE"
        const val ACTION_REQUEST_KEYFRAME = "com.expresscast.phone.action.REQUEST_KEYFRAME"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_HOST = "target_host"
        const val EXTRA_VIDEO_PORT = "video_port"
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
