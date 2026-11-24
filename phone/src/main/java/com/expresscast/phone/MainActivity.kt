package com.expresscast.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.expresscast.phone.capture.CaptureStatus
import com.expresscast.phone.capture.ScreenCaptureService
import com.expresscast.phone.network.NsdAdvertiser
import com.expresscast.phone.network.NsdAdvertiserState
import com.expresscast.phone.network.PhoneControlServer
import com.expresscast.phone.network.PhoneServerUiState
import com.expresscast.phone.settings.AspectCropMode
import com.expresscast.phone.settings.CastSettings
import com.expresscast.phone.settings.CastSettingsRepository
import com.expresscast.phone.settings.ResolutionPreset
import com.expresscast.phone.ui.theme.ExpressCastTheme
import com.expresscast.shared.ConnectionState
import com.expresscast.shared.NetworkConfig
import com.expresscast.shared.ProtocolInfo

class MainActivity : ComponentActivity() {
    private lateinit var controlServer: PhoneControlServer
    private lateinit var nsdAdvertiser: NsdAdvertiser
    private lateinit var settingsRepository: CastSettingsRepository

    private var currentSettings: CastSettings = CastSettings()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        launchMediaProjectionPrompt()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val tvHost = controlServer.state.value.connectedTvAddress
        if (result.resultCode == Activity.RESULT_OK && data != null && !tvHost.isNullOrBlank()) {
            val dm = resources.displayMetrics
            val streamConfig = currentSettings.toVideoStreamConfig(dm.widthPixels, dm.heightPixels)
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_TARGET_HOST, tvHost)
                putExtra(ScreenCaptureService.EXTRA_VIDEO_PORT, NetworkConfig.DEFAULT_VIDEO_PORT)
                putExtra(ScreenCaptureService.EXTRA_AUDIO_ENABLED, currentSettings.audioEnabled)
                putExtra(ScreenCaptureService.EXTRA_AUDIO_PORT, NetworkConfig.DEFAULT_AUDIO_PORT)
                putExtra(ScreenCaptureService.EXTRA_STREAM_WIDTH, streamConfig.width)
                putExtra(ScreenCaptureService.EXTRA_STREAM_HEIGHT, streamConfig.height)
                putExtra(ScreenCaptureService.EXTRA_STREAM_FPS, streamConfig.fps)
                putExtra(ScreenCaptureService.EXTRA_STREAM_BITRATE, streamConfig.bitrateBps)
                putExtra(ScreenCaptureService.EXTRA_CROP_MODE, currentSettings.cropMode.name)
            }
            startForegroundService(intent)
            controlServer.sendStartStream(
                config = streamConfig,
                videoPort = NetworkConfig.DEFAULT_VIDEO_PORT,
                audioEnabled = currentSettings.audioEnabled,
                audioPort = NetworkConfig.DEFAULT_AUDIO_PORT,
                showStatsOverlay = currentSettings.showStatsOverlay
            )
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
        nsdAdvertiser = NsdAdvertiser(this)

        controlServer.start()
        nsdAdvertiser.start(port = controlServer.state.value.port)

        enableEdgeToEdge()
        setContent {
            ExpressCastTheme {
                val serverState by controlServer.state.collectAsState()
                val nsdState by nsdAdvertiser.state.collectAsState()
                val captureStatus by ScreenCaptureService.status.collectAsState()
                var settings by remember { mutableStateOf(currentSettings) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PhoneCastScreen(
                        serverState = serverState,
                        nsdState = nsdState,
                        captureStatus = captureStatus,
                        settings = settings,
                        onSettingsChanged = { updated ->
                            settings = updated
                            currentSettings = updated
                            settingsRepository.save(updated)
                        },
                        onStartServer = {
                            controlServer.start()
                            nsdAdvertiser.start(port = controlServer.state.value.port)
                        },
                        onStopServer = {
                            ScreenCaptureService.stop(this)
                            controlServer.sendStopStream()
                            nsdAdvertiser.stop()
                            controlServer.stop()
                        },
                        onSubmitPin = { controlServer.submitPairingPin(it) },
                        onStartCasting = { requestScreenCaptureWithPermission() },
                        onStopCasting = {
                            ScreenCaptureService.stop(this)
                            controlServer.sendStopStream()
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        ScreenCaptureService.stop(this)
        nsdAdvertiser.stop()
        controlServer.stop()
        super.onDestroy()
    }

    private fun requestScreenCaptureWithPermission() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (currentSettings.audioEnabled &&
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneCastScreen(
    serverState: PhoneServerUiState,
    nsdState: NsdAdvertiserState,
    captureStatus: CaptureStatus,
    settings: CastSettings,
    onSettingsChanged: (CastSettings) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSubmitPin: (String) -> Unit,
    onStartCasting: () -> Unit,
    onStopCasting: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pinInput by remember { mutableStateOf("") }

    LaunchedEffect(serverState.connectionState) {
        if (serverState.connectionState != ConnectionState.Pairing) {
            pinInput = ""
        }
    }

    val isPaired = serverState.connectionState == ConnectionState.Connected ||
        serverState.connectionState == ConnectionState.Streaming
    val canEditSettings = !captureStatus.isStreaming

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = ProtocolInfo.APP_NAME,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = formatConnectionState(serverState.connectionState),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Discovery: ${formatNsdState(nsdState)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                serverState.connectedTvName?.let { tvName ->
                    Text(
                        text = "TV: $tvName",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                serverState.lastRttMs?.let { rtt ->
                    Text(
                        text = "Latency: $rtt ms",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!serverState.isListening) {
                        Button(onClick = onStartServer) {
                            Text("Enable Discovery")
                        }
                    } else {
                        OutlinedButton(onClick = onStopServer) {
                            Text("Stop")
                        }
                    }
                }
            }
        }

        if (serverState.connectionState == ConnectionState.Pairing) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Pair with ${serverState.connectedTvName ?: "TV"}",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Enter the 4-digit PIN shown on your TV screen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { value ->
                            val digitsOnly = value.filter { it.isDigit() }.take(4)
                            pinInput = digitsOnly
                            if (digitsOnly.length == 4) {
                                onSubmitPin(digitsOnly)
                            }
                        },
                        label = { Text("4-digit PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    serverState.pairingError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = { onSubmitPin(pinInput) },
                        enabled = pinInput.length == 4,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Pair")
                    }
                }
            }
        }

        if (isPaired) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Cast Screen", style = MaterialTheme.typography.titleLarge)
                    if (!captureStatus.isStreaming) {
                        Button(
                            onClick = onStartCasting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Casting to TV")
                        }
                    } else {
                        val cfg = captureStatus.activeConfig
                        Text(
                            text = "Casting ${cfg?.width}×${cfg?.height} @ ${cfg?.fps} fps",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Frames sent: ${captureStatus.framesEncoded}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = onStopCasting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Stop Casting")
                        }
                    }
                    captureStatus.error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Stream Settings", style = MaterialTheme.typography.titleMedium)
                if (!canEditSettings) {
                    Text(
                        text = "Stop casting to change stream settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text("Aspect / Crop", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AspectCropMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.cropMode == mode,
                            onClick = { onSettingsChanged(settings.copy(cropMode = mode)) },
                            enabled = canEditSettings,
                            label = { Text(mode.label) }
                        )
                    }
                }

                Text("Resolution", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResolutionPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = settings.resolution == preset,
                            onClick = { onSettingsChanged(settings.copy(resolution = preset)) },
                            enabled = canEditSettings,
                            label = { Text(preset.label) }
                        )
                    }
                }

                Text("Target FPS", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60).forEach { targetFps ->
                        FilterChip(
                            selected = settings.fps == targetFps,
                            onClick = { onSettingsChanged(settings.copy(fps = targetFps)) },
                            enabled = canEditSettings,
                            label = { Text("$targetFps fps") }
                        )
                    }
                }

                Text("Bitrate", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(4, 6, 10, 15).forEach { mbps ->
                        FilterChip(
                            selected = settings.bitrateMbps == mbps,
                            onClick = { onSettingsChanged(settings.copy(bitrateMbps = mbps)) },
                            enabled = canEditSettings,
                            label = { Text("$mbps Mbps") }
                        )
                    }
                }

                Text("Audio", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.audioEnabled,
                        onClick = { onSettingsChanged(settings.copy(audioEnabled = true)) },
                        enabled = canEditSettings,
                        label = { Text("Game / Media Audio") }
                    )
                    FilterChip(
                        selected = !settings.audioEnabled,
                        onClick = { onSettingsChanged(settings.copy(audioEnabled = false)) },
                        enabled = canEditSettings,
                        label = { Text("Muted") }
                    )
                }

                Text("TV Stats Overlay", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.showStatsOverlay,
                        onClick = { onSettingsChanged(settings.copy(showStatsOverlay = true)) },
                        enabled = canEditSettings,
                        label = { Text("Show FPS & Latency") }
                    )
                    FilterChip(
                        selected = !settings.showStatsOverlay,
                        onClick = { onSettingsChanged(settings.copy(showStatsOverlay = false)) },
                        enabled = canEditSettings,
                        label = { Text("Off") }
                    )
                }
            }
        }
    }
}

private fun formatNsdState(state: NsdAdvertiserState): String = when (state) {
    NsdAdvertiserState.Idle -> "Off"
    NsdAdvertiserState.Registering -> "Starting…"
    is NsdAdvertiserState.Registered -> "Visible as ${state.serviceName}"
    is NsdAdvertiserState.Failed -> "Error (${state.errorCode})"
}

private fun formatConnectionState(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> "Ready — select this phone on your TV"
    ConnectionState.Discovering -> "Discovering"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Pairing -> "Waiting for PIN"
    ConnectionState.Connected -> "Paired — ready to cast"
    ConnectionState.Streaming -> "Casting to TV"
    is ConnectionState.Reconnecting -> "Reconnecting (#${state.attempt})"
    ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Failed -> "Failed: ${state.error} ${state.detail.orEmpty()}"
}
