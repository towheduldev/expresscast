package dev.towhedul.expresscast.tv.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.towhedul.expresscast.shared.ControlMessage
import dev.towhedul.expresscast.shared.NetworkConfig
import dev.towhedul.expresscast.shared.VideoStreamConfig
import dev.towhedul.expresscast.tv.audio.UdpAudioReceiver
import dev.towhedul.expresscast.tv.codec.HardwareH264Decoder
import dev.towhedul.expresscast.tv.network.UdpVideoReceiver
import dev.towhedul.expresscast.tv.ui.theme.TvEmerald
import dev.towhedul.expresscast.tv.ui.theme.TvStatusAmber
import kotlinx.coroutines.delay
import java.net.InetAddress

@Composable
fun TvStreamingPlayer(
    window: Window,
    config: VideoStreamConfig,
    videoPort: Int,
    peerAddress: InetAddress?,
    audioEnabled: Boolean,
    audioPort: Int,
    showOverlay: Boolean,
    lastRttMs: Long?,
    onRequestKeyframe: () -> Unit,
    onSendStreamStats: (ControlMessage.StreamStatsReport) -> Unit,
    onDisconnect: () -> Unit
) {
    var decoder by remember { mutableStateOf<HardwareH264Decoder?>(null) }
    var receiver by remember { mutableStateOf<UdpVideoReceiver?>(null) }
    var audioReceiver by remember { mutableStateOf<UdpAudioReceiver?>(null) }
    var currentFps by remember { mutableIntStateOf(0) }
    var recentLostFrames by remember { mutableIntStateOf(0) }
    var videoWidth by remember(config) { mutableIntStateOf(config.width) }
    var videoHeight by remember(config) { mutableIntStateOf(config.height) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    BackHandler {
        onDisconnect()
    }

    LaunchedEffect(receiver) {
        val activeReceiver = receiver ?: return@LaunchedEffect
        while (true) {
            delay(NetworkConfig.STATS_REPORT_INTERVAL_MS)
            val (completed, lost) = activeReceiver.drainWindowStats()
            recentLostFrames = lost
            onSendStreamStats(
                ControlMessage.StreamStatsReport(
                    windowMs = NetworkConfig.STATS_REPORT_INTERVAL_MS.toInt(),
                    framesCompleted = completed,
                    framesLost = lost,
                    renderedFps = currentFps,
                    rttMs = (lastRttMs ?: 0L).toInt().coerceAtLeast(0)
                )
            )
        }
    }

    DisposableEffect(audioEnabled, audioPort) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setPreferMinimalPostProcessing(true)
        }
        if (audioEnabled) {
            audioReceiver = UdpAudioReceiver(audioPort, peerAddress).also { it.start() }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setPreferMinimalPostProcessing(false)
            }
            audioReceiver?.stop()
            audioReceiver = null
            receiver?.stop()
            receiver = null
            decoder?.stop()
            decoder = null
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val containerAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else (16f / 9f)
        val videoAspect = videoWidth.toFloat() / videoHeight.coerceAtLeast(1)
        val (surfaceWidth, surfaceHeight) = if (videoAspect >= containerAspect) {
            maxWidth to (maxWidth / videoAspect)
        } else {
            (maxHeight * videoAspect) to maxHeight
        }

        AndroidView(
            modifier = Modifier
                .align(Alignment.Center)
                .size(surfaceWidth, surfaceHeight),
            factory = { context ->
                SurfaceView(context).apply {
                    keepScreenOn = true
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            lateinit var h264Decoder: HardwareH264Decoder
                            val onPlaybackError = {
                                mainHandler.post { if (decoder === h264Decoder) onDisconnect() }
                                Unit
                            }
                            h264Decoder = HardwareH264Decoder(
                                config = config,
                                outputSurface = holder.surface,
                                onFpsUpdated = { fps ->
                                    mainHandler.post { if (decoder === h264Decoder) currentFps = fps }
                                },
                                onVideoSizeChanged = { w, h ->
                                    mainHandler.post {
                                        if (decoder === h264Decoder) {
                                            videoWidth = w
                                            videoHeight = h
                                        }
                                    }
                                },
                                onError = onPlaybackError
                            )
                            decoder = h264Decoder
                            val udpRx = UdpVideoReceiver(
                                port = videoPort,
                                peerAddress = peerAddress,
                                onFrame = { frame ->
                                    h264Decoder.offerFrame(frame.data, frame.ptsUs, frame.flags)
                                },
                                onPacketLoss = h264Decoder::onFrameDropped,
                                waitingForSync = { h264Decoder.ready && h264Decoder.waitingForSync },
                                requestKeyframe = onRequestKeyframe,
                                onError = onPlaybackError
                            )
                            receiver = udpRx
                            h264Decoder.start()
                            udpRx.start()
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            receiver?.stop()
                            receiver = null
                            decoder?.stop()
                            decoder = null
                        }
                    })
                }
            }
        )

        if (showOverlay) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp)
            ) {
                val rttText = lastRttMs?.let { "${it} ms" } ?: "— ms"
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(TvEmerald)
                    )
                    Text(
                        text = "$currentFps FPS · $rttText",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (recentLostFrames > 0) {
                        Text(
                            text = "Loss: $recentLostFrames",
                            color = TvStatusAmber,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
