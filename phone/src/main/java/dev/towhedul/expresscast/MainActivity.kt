package dev.towhedul.expresscast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.towhedul.expresscast.capture.ScreenCaptureService
import dev.towhedul.expresscast.codec.HardwareH264Encoder
import dev.towhedul.expresscast.network.LanDiscoveryResponder
import dev.towhedul.expresscast.network.NsdAdvertiser
import dev.towhedul.expresscast.network.PhoneControlServer
import dev.towhedul.expresscast.settings.CastSettings
import dev.towhedul.expresscast.settings.CastSettingsRepository
import dev.towhedul.expresscast.shared.AdaptiveBitrateController
import dev.towhedul.expresscast.shared.ConnectionState
import dev.towhedul.expresscast.shared.ControlMessage
import dev.towhedul.expresscast.shared.NetworkConfig
import dev.towhedul.expresscast.ui.PhoneCastScreen
import dev.towhedul.expresscast.ui.theme.ExpressCastTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private data class CastRequest(val id: Long, val targetHost: String, val showOverlay: Boolean)

    private lateinit var controlServer: PhoneControlServer
    private lateinit var nsdAdvertiser: NsdAdvertiser
    private lateinit var lanResponder: LanDiscoveryResponder
    private lateinit var settingsRepository: CastSettingsRepository

    private var abrController: AdaptiveBitrateController? = null

    private var currentSettings: CastSettings = CastSettings()
    private var pendingCast: CastRequest? by mutableStateOf(null)
    private var streamAnnounced = false

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        launchMediaProjectionPrompt()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val peer = controlServer.state.value
        val tvHost = peer.connectedTvAddress
        if (result.resultCode == Activity.RESULT_OK && data != null && !tvHost.isNullOrBlank() &&
            peer.connectionState == ConnectionState.Connected && pendingCast == null
        ) {
            val dm = resources.displayMetrics
            val streamConfig = currentSettings.toVideoStreamConfig(dm.widthPixels, dm.heightPixels)
            abrController = if (currentSettings.adaptiveBitrate) {
                AdaptiveBitrateController(maxBitrateBps = streamConfig.bitrateBps)
            } else {
                null
            }
            val request = CastRequest(System.nanoTime(), tvHost, currentSettings.showStatsOverlay)
            pendingCast = request
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_TARGET_HOST, tvHost)
                putExtra(ScreenCaptureService.EXTRA_REQUEST_ID, request.id)
                putExtra(
                    ScreenCaptureService.EXTRA_AUDIO_ENABLED,
                    currentSettings.audioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                )
                putExtra(ScreenCaptureService.EXTRA_AUDIO_PORT, NetworkConfig.DEFAULT_AUDIO_PORT)
                putExtra(ScreenCaptureService.EXTRA_VIDEO_PORT, NetworkConfig.DEFAULT_VIDEO_PORT)
                putExtra(ScreenCaptureService.EXTRA_STREAM_WIDTH, streamConfig.width)
                putExtra(ScreenCaptureService.EXTRA_STREAM_HEIGHT, streamConfig.height)
                putExtra(ScreenCaptureService.EXTRA_STREAM_FPS, streamConfig.fps)
                putExtra(ScreenCaptureService.EXTRA_STREAM_BITRATE, streamConfig.bitrateBps)
                putExtra(ScreenCaptureService.EXTRA_CROP_MODE, currentSettings.cropMode.name)
            }
            startForegroundService(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = CastSettingsRepository(this)
        currentSettings = settingsRepository.load()

        controlServer = PhoneControlServer()
        controlServer.onKeyframeRequested = {
            ScreenCaptureService.requestKeyframe(this)
        }
        controlServer.onStreamStatsReceived = { report ->
            val controller = abrController
            if (controller != null && currentSettings.adaptiveBitrate && pendingCast != null) {
                val nextBitrate = controller.onStatsReport(report, System.currentTimeMillis())
                if (nextBitrate != null) {
                    ScreenCaptureService.updateBitrate(this, nextBitrate)
                }
            }
        }

        nsdAdvertiser = NsdAdvertiser(this)
        lanResponder = LanDiscoveryResponder()

        startDiscoveryAndServer()

        lifecycleScope.launch {
            ScreenCaptureService.status.collect { status ->
                val request = pendingCast ?: return@collect
                if (status.requestId != request.id) return@collect
                val config = status.activeConfig
                if (status.isStreaming && config != null && !streamAnnounced) {
                    val peer = controlServer.state.value
                    if (peer.connectionState != ConnectionState.Connected ||
                        peer.connectedTvAddress != request.targetHost
                    ) {
                        stopCasting()
                        return@collect
                    }
                    streamAnnounced = true
                    controlServer.sendStartStream(config, audioEnabled = status.audioActive, showStatsOverlay = request.showOverlay)
                } else if (!status.isStreaming && (status.error != null || streamAnnounced)) {
                    pendingCast = null
                    abrController = null
                    if (streamAnnounced) controlServer.sendStopStream()
                    streamAnnounced = false
                }
            }
        }

        lifecycleScope.launch {
            controlServer.state.collect { state ->
                val request = pendingCast ?: return@collect
                val activeConnection = state.connectedTvAddress == request.targetHost &&
                    (state.connectionState == ConnectionState.Connected || state.connectionState == ConnectionState.Streaming)
                if (!activeConnection) {
                    stopCasting()
                }
            }
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContent {
            ExpressCastTheme {
                val serverState by controlServer.state.collectAsState()
                val nsdState by nsdAdvertiser.state.collectAsState()
                val lanBeaconRunning by lanResponder.isRunning.collectAsState()
                val captureStatus by ScreenCaptureService.status.collectAsState()
                var settings by remember { mutableStateOf(currentSettings) }

                val dm = resources.displayMetrics
                val supportedFps = remember(
                    settings.resolution,
                    settings.cropMode,
                    serverState.peerCapabilities
                ) {
                    computeSupportedFps(
                        settings = settings,
                        displayW = dm.widthPixels,
                        displayH = dm.heightPixels,
                        peerCaps = serverState.peerCapabilities
                    )
                }

                if (settings.fps !in supportedFps && !captureStatus.isStreaming) {
                    val fallbackFps = supportedFps.filter { it <= settings.fps }.maxOrNull()
                        ?: supportedFps.minOrNull()
                        ?: 30
                    if (fallbackFps != settings.fps) {
                        val clamped = settings.copy(fps = fallbackFps)
                        settings = clamped
                        currentSettings = clamped
                        settingsRepository.save(clamped)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PhoneCastScreen(
                        serverState = serverState,
                        nsdState = nsdState,
                        lanBeaconRunning = lanBeaconRunning,
                        captureStatus = captureStatus,
                        castStarting = pendingCast != null && !captureStatus.isStreaming,
                        settings = settings,
                        supportedFps = supportedFps,
                        onSettingsChanged = { updated ->
                            settings = updated
                            currentSettings = updated
                            settingsRepository.save(updated)
                        },
                        onStartServer = ::startDiscoveryAndServer,
                        onStopServer = {
                            stopCasting()
                            lanResponder.stop()
                            nsdAdvertiser.stop()
                            controlServer.stop()
                        },
                        onRefreshIps = { controlServer.refreshLocalIps() },
                        onSubmitPin = { controlServer.submitPairingPin(it) },
                        onStartCasting = { requestScreenCaptureWithPermission() },
                        onStopCasting = ::stopCasting,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (pendingCast != null) ScreenCaptureService.stop(this)
        lanResponder.stop()
        nsdAdvertiser.stop()
        controlServer.close()
        super.onDestroy()
    }

    private fun startDiscoveryAndServer() {
        controlServer.start()
        val port = controlServer.state.value.port
        nsdAdvertiser.start(port = port)
        lanResponder.start(controlPort = port)
    }

    private fun stopCasting() {
        abrController = null
        pendingCast = null
        ScreenCaptureService.stop(this)
        if (streamAnnounced || controlServer.state.value.connectionState == ConnectionState.Streaming) {
            controlServer.sendStopStream()
        }
        streamAnnounced = false
    }

    private fun computeSupportedFps(
        settings: CastSettings,
        displayW: Int,
        displayH: Int,
        peerCaps: ControlMessage.PeerCapabilities?
    ): Set<Int> {
        val cfg = settings.toVideoStreamConfig(displayW, displayH)
        val localSupported = HardwareH264Encoder.querySupportedFps(cfg.width, cfg.height, CastSettings.FPS_OPTIONS)
        if (peerCaps == null) return localSupported
        val tvMaxFps = if (cfg.height >= 1080) peerCaps.maxFps1080p else peerCaps.maxFps720p
        return localSupported.filterTo(LinkedHashSet()) { it <= tvMaxFps }
            .ifEmpty { setOf(15, 30) }
    }

    private fun requestScreenCaptureWithPermission() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            currentSettings.audioEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            permissionsLauncher.launch(needed.toTypedArray())
            return
        }
        launchMediaProjectionPrompt()
    }

    private fun launchMediaProjectionPrompt() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
