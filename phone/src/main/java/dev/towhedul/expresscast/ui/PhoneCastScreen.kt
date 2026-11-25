package dev.towhedul.expresscast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.towhedul.expresscast.R
import dev.towhedul.expresscast.capture.CaptureStatus
import dev.towhedul.expresscast.network.NsdAdvertiserState
import dev.towhedul.expresscast.network.PhoneServerUiState
import dev.towhedul.expresscast.settings.AspectCropMode
import dev.towhedul.expresscast.settings.CastSettings
import dev.towhedul.expresscast.settings.ResolutionPreset
import dev.towhedul.expresscast.shared.ConnectionState
import dev.towhedul.expresscast.shared.ProtocolInfo
import dev.towhedul.expresscast.ui.theme.CanvasBg
import dev.towhedul.expresscast.ui.theme.CardBorder
import dev.towhedul.expresscast.ui.theme.CardWhite
import dev.towhedul.expresscast.ui.theme.DividerSoft
import dev.towhedul.expresscast.ui.theme.EmeraldDark
import dev.towhedul.expresscast.ui.theme.EmeraldPrimary
import dev.towhedul.expresscast.ui.theme.InkPrimary
import dev.towhedul.expresscast.ui.theme.InkSecondary
import dev.towhedul.expresscast.ui.theme.MintBorder
import dev.towhedul.expresscast.ui.theme.MintTint
import dev.towhedul.expresscast.ui.theme.PillNeutral
import dev.towhedul.expresscast.ui.theme.StatusAmber
import dev.towhedul.expresscast.ui.theme.StatusGreen
import dev.towhedul.expresscast.ui.theme.StatusRed
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun PhoneCastScreen(
    serverState: PhoneServerUiState,
    nsdState: NsdAdvertiserState,
    lanBeaconRunning: Boolean,
    captureStatus: CaptureStatus,
    castStarting: Boolean,
    settings: CastSettings,
    supportedFps: Set<Int>,
    onSettingsChanged: (CastSettings) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRefreshIps: () -> Unit,
    onSubmitPin: (String) -> Unit,
    onStartCasting: () -> Unit,
    onStopCasting: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBg)
    ) {
        MintWaveBackdrop(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(170.dp)
        )

        if (!showSettings) {
            PhoneHomeScreen(
                serverState = serverState,
                nsdState = nsdState,
                lanBeaconRunning = lanBeaconRunning,
                captureStatus = captureStatus,
                castStarting = castStarting,
                settings = settings,
                onOpenSettings = { showSettings = true },
                onStartServer = onStartServer,
                onStopServer = onStopServer,
                onRefreshIps = onRefreshIps,
                onSubmitPin = onSubmitPin,
                onStartCasting = onStartCasting,
                onStopCasting = onStopCasting
            )
        } else {
            PhoneSettingsScreen(
                canEditSettings = !captureStatus.isStreaming && !castStarting,
                settings = settings,
                supportedFps = supportedFps,
                onBack = { showSettings = false },
                onSettingsChanged = onSettingsChanged
            )
        }
    }
}

@Composable
private fun PhoneHomeScreen(
    serverState: PhoneServerUiState,
    nsdState: NsdAdvertiserState,
    lanBeaconRunning: Boolean,
    captureStatus: CaptureStatus,
    castStarting: Boolean,
    settings: CastSettings,
    onOpenSettings: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRefreshIps: () -> Unit,
    onSubmitPin: (String) -> Unit,
    onStartCasting: () -> Unit,
    onStopCasting: () -> Unit
) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var copiedFeedback by remember { mutableStateOf(false) }

    LaunchedEffect(serverState.connectionState) {
        if (serverState.connectionState != ConnectionState.Pairing) {
            pinInput = ""
        }
    }

    LaunchedEffect(copiedFeedback) {
        if (copiedFeedback) {
            delay(1800L)
            copiedFeedback = false
        }
    }

    val isPaired = serverState.connectionState == ConnectionState.Connected ||
        serverState.connectionState == ConnectionState.Streaming

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Brand Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_source),
                    contentDescription = ProtocolInfo.APP_NAME,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                Column {
                    Text(
                        text = ProtocolInfo.APP_NAME,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = InkPrimary
                    )
                    Text(
                        text = "Low-Latency LAN Cast",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
            }

            Surface(
                color = PillNeutral,
                shape = CircleShape,
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettings)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    SettingsGearIcon(color = InkPrimary, modifier = Modifier.size(21.dp))
                }
            }
        }

        // Hero Status + Power + Local IP Card
        val (statusColor, statusTitle, statusSubtitle) = when {
            captureStatus.isStreaming -> Triple(
                StatusGreen,
                "Casting to ${serverState.connectedTvName ?: "TV"}",
                buildStreamingSubtitle(captureStatus, serverState, settings)
            )
            serverState.connectionState == ConnectionState.Pairing -> Triple(
                StatusAmber,
                "Pair with ${serverState.connectedTvName ?: "TV"}",
                "Enter the 4-digit PIN shown on your TV screen"
            )
            serverState.connectionState == ConnectionState.Connected -> Triple(
                StatusGreen,
                "Paired with ${serverState.connectedTvName ?: "TV"}",
                buildConnectedSubtitle(serverState)
            )
            serverState.isListening -> Triple(
                StatusGreen,
                "Ready to Cast",
                buildDiscoverySubtitle(nsdState, lanBeaconRunning)
            )
            else -> Triple(
                InkSecondary,
                "Discovery Stopped",
                "Tap the power button to become discoverable"
            )
        }

        Surface(
            color = CardWhite,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, CardBorder),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFE7FAEF),
                                Color(0xFFF4FCF8),
                                CardWhite
                            )
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Status + Circular Power Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = statusTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = InkPrimary
                                )
                            }
                            Text(
                                text = statusSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }

                        val powerButtonColor = when {
                            captureStatus.isStreaming -> StatusRed
                            serverState.isListening -> EmeraldPrimary
                            else -> Color(0xFF94A3B8)
                        }
                        Surface(
                            color = powerButtonColor,
                            shape = CircleShape,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .clickable {
                                    when {
                                        captureStatus.isStreaming -> onStopCasting()
                                        serverState.isListening -> onStopServer()
                                        else -> onStartServer()
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                PowerIcon(color = Color.White, modifier = Modifier.size(26.dp))
                            }
                        }
                    }

                    // Contextual actions (Pairing PIN / Start Cast / Stop Cast)
                    if (serverState.connectionState == ConnectionState.Pairing) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = { value ->
                                    val digitsOnly = value.filter { it.isDigit() }.take(4)
                                    pinInput = digitsOnly
                                    if (digitsOnly.length == 4) {
                                        onSubmitPin(digitsOnly)
                                    }
                                },
                                label = { Text("4-digit TV PIN") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = EmeraldPrimary,
                                    unfocusedBorderColor = CardBorder,
                                    focusedLabelColor = EmeraldDark
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            serverState.pairingError?.let { err ->
                                Text(text = err, color = StatusRed, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = { onSubmitPin(pinInput) },
                                enabled = pinInput.length == 4,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldPrimary,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("Pair", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else if (isPaired) {
                        if (!captureStatus.isStreaming) {
                            Button(
                                onClick = onStartCasting,
                                enabled = !castStarting,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldPrimary,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Text(
                                    text = if (castStarting) "Starting Cast…" else "Start Casting to TV",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = onStopCasting,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text(
                                    text = "Stop Casting",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        captureStatus.error?.let { err ->
                            Text(text = err, color = StatusRed, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    HorizontalDivider(color = DividerSoft)

                    // Local IP Address Row (Manual IP fallback)
                    val primaryIp = serverState.localIps.firstOrNull()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MonitorIcon(color = InkPrimary, modifier = Modifier.size(22.dp))
                            Column {
                                Text(
                                    text = "Local IP Address",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary
                                )
                                Text(
                                    text = primaryIp ?: if (serverState.isListening) "No Wi-Fi IP" else "Server Off",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = InkPrimary
                                )
                                if (primaryIp != null && serverState.localIps.size > 1) {
                                    Text(
                                        text = "Port ${serverState.port} · ${serverState.localIps.drop(1).joinToString(", ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = InkSecondary
                                    )
                                }
                            }
                        }

                        if (primaryIp != null) {
                            Surface(
                                color = if (copiedFeedback) MintTint else CardWhite,
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, if (copiedFeedback) MintBorder else CardBorder),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable {
                                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("ExpressCast IP", primaryIp))
                                        copiedFeedback = true
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CopyIcon(
                                        color = if (copiedFeedback) EmeraldDark else InkPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (copiedFeedback) "Copied" else "Copy",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (copiedFeedback) EmeraldDark else InkPrimary
                                    )
                                }
                            }
                        } else if (serverState.isListening) {
                            TextButton(onClick = onRefreshIps) {
                                Text("Refresh", color = EmeraldDark)
                            }
                        }
                    }
                }
            }
        }

        // Stream Settings Summary Card (tap to open full settings screen)
        Surface(
            color = CardWhite,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, CardBorder),
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onOpenSettings)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayTriangleIcon(color = EmeraldPrimary, modifier = Modifier.size(22.dp))
                        Column {
                            Text(
                                text = "Stream Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = InkPrimary
                            )
                            Text(
                                text = "Your current streaming configuration",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }
                    }
                    ChevronRightIcon(color = InkSecondary, modifier = Modifier.size(18.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SummaryMetricColumn(
                        icon = { MonitorIcon(color = InkPrimary, modifier = Modifier.size(22.dp)) },
                        value = settings.resolution.label,
                        label = "Resolution",
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(52.dp)
                            .background(DividerSoft)
                    )
                    SummaryMetricColumn(
                        icon = { GaugeIcon(color = InkPrimary, modifier = Modifier.size(22.dp)) },
                        value = "${settings.fps} fps",
                        label = "Frame Rate",
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(52.dp)
                            .background(DividerSoft)
                    )
                    SummaryMetricColumn(
                        icon = { SignalBarsIcon(color = InkPrimary, modifier = Modifier.size(20.dp)) },
                        value = "${settings.bitrateMbps} Mbps",
                        label = if (settings.adaptiveBitrate) "Max Bitrate" else "Fixed Bitrate",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SummaryMetricColumn(
    icon: @Composable () -> Unit,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon()
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = InkPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = InkSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneSettingsScreen(
    canEditSettings: Boolean,
    settings: CastSettings,
    supportedFps: Set<Int>,
    onBack: () -> Unit,
    onSettingsChanged: (CastSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top navigation bar
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = PillNeutral,
                shape = CircleShape,
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    BackArrowIcon(color = InkPrimary, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = "Stream Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = InkPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (!canEditSettings) {
            Surface(
                color = MintTint,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MintBorder.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Stop casting to modify stream settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmeraldDark,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        // Card 1: Aspect Ratio / Crop
        SettingsCard {
            SettingsCardHeader(
                icon = { MonitorIcon(color = EmeraldPrimary, modifier = Modifier.size(22.dp)) },
                title = "Aspect Ratio / Crop",
                subtitle = "Choose how the video is displayed"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AspectCropMode.entries.forEach { mode ->
                    AspectPreviewThumbnail(
                        mode = mode,
                        selected = settings.cropMode == mode,
                        enabled = canEditSettings,
                        onClick = { onSettingsChanged(settings.copy(cropMode = mode)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Card 2: Resolution
        SettingsCard {
            SettingsCardHeader(
                icon = { HdBadgeIcon(color = EmeraldPrimary, modifier = Modifier.size(24.dp)) },
                title = "Resolution",
                subtitle = "Higher quality for better visuals"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ResolutionPreset.entries.forEach { preset ->
                    SelectablePill(
                        label = preset.label,
                        selected = settings.resolution == preset,
                        enabled = canEditSettings,
                        onClick = { onSettingsChanged(settings.copy(resolution = preset)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Card 3: Frame Rate
        SettingsCard {
            SettingsCardHeader(
                icon = { LightningIcon(color = EmeraldPrimary, modifier = Modifier.size(22.dp)) },
                title = "Frame Rate",
                subtitle = "Smoothness of motion"
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CastSettings.FPS_OPTIONS.forEach { targetFps ->
                    val supported = targetFps in supportedFps
                    SelectablePill(
                        label = "$targetFps fps",
                        selected = settings.fps == targetFps,
                        enabled = canEditSettings && supported,
                        onClick = { onSettingsChanged(settings.copy(fps = targetFps)) }
                    )
                }
            }
            val fpsNote = when {
                settings.fps == 15 -> "15 fps uses steady pacing to reduce frame-rate jumps on weak Wi-Fi."
                90 !in supportedFps && 120 !in supportedFps ->
                    "90/120 fps disabled when phone encoder or TV decoder caps at 60 fps."
                else -> "90/120 fps active for supported resolutions and TV panels."
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InfoCircleIcon(color = InkSecondary, modifier = Modifier.size(16.dp))
                Text(
                    text = fpsNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }
        }

        // Card 4: Bitrate + Audio + Overlay Toggles
        SettingsCard {
            SettingsSwitchRow(
                icon = { SignalBarsIcon(color = EmeraldPrimary, modifier = Modifier.size(20.dp)) },
                title = if (settings.adaptiveBitrate) "Max Bitrate (Auto)" else "Bitrate (Fixed)",
                subtitle = if (settings.adaptiveBitrate) {
                    "Adjust bitrate automatically (${settings.bitrateMbps} Mbps ceiling)"
                } else {
                    "Constant target bitrate (${settings.bitrateMbps} Mbps)"
                },
                checked = settings.adaptiveBitrate,
                enabled = canEditSettings,
                onCheckedChange = { onSettingsChanged(settings.copy(adaptiveBitrate = it)) }
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Bitrate Target",
                        style = MaterialTheme.typography.labelLarge,
                        color = InkSecondary
                    )
                    Surface(color = MintTint, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = "${settings.bitrateMbps} Mbps",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldDark,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Slider(
                    value = settings.bitrateMbps.toFloat(),
                    onValueChange = { value ->
                        val mbps = value.roundToInt().coerceIn(
                            CastSettings.MIN_BITRATE_MBPS,
                            CastSettings.MAX_BITRATE_MBPS
                        )
                        if (mbps != settings.bitrateMbps) {
                            onSettingsChanged(settings.copy(bitrateMbps = mbps))
                        }
                    },
                    valueRange = CastSettings.MIN_BITRATE_MBPS.toFloat()..CastSettings.MAX_BITRATE_MBPS.toFloat(),
                    steps = CastSettings.MAX_BITRATE_MBPS - CastSettings.MIN_BITRATE_MBPS - 1,
                    enabled = canEditSettings,
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldPrimary,
                        activeTrackColor = EmeraldPrimary,
                        inactiveTrackColor = PillNeutral
                    )
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CastSettings.BITRATE_PRESETS_MBPS.forEach { mbps ->
                        val active = settings.bitrateMbps == mbps
                        Surface(
                            color = if (active) MintTint else PillNeutral,
                            shape = RoundedCornerShape(999.dp),
                            border = if (active) BorderStroke(1.dp, MintBorder) else null,
                            modifier = Modifier
                                .alpha(if (canEditSettings) 1f else 0.5f)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(enabled = canEditSettings) {
                                    onSettingsChanged(settings.copy(bitrateMbps = mbps))
                                }
                        ) {
                            Text(
                                text = "$mbps Mbps",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) EmeraldDark else InkSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = DividerSoft)

            val audioCaptureSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            SettingsSwitchRow(
                icon = { SpeakerIcon(color = EmeraldPrimary, modifier = Modifier.size(20.dp)) },
                title = "Internal Game / Media Audio",
                subtitle = if (audioCaptureSupported) {
                    "Streams 48 kHz internal audio without microphone noise"
                } else {
                    "Requires Android 10+ (video-only on Android 9)"
                },
                checked = audioCaptureSupported && settings.audioEnabled,
                enabled = canEditSettings && audioCaptureSupported,
                onCheckedChange = { onSettingsChanged(settings.copy(audioEnabled = it)) }
            )

            HorizontalDivider(color = DividerSoft)

            SettingsSwitchRow(
                icon = { MonitorIcon(color = EmeraldPrimary, modifier = Modifier.size(20.dp)) },
                title = "TV Stats Overlay",
                subtitle = "Shows live FPS and control RTT in the corner of the TV",
                checked = settings.showStatsOverlay,
                enabled = canEditSettings,
                onCheckedChange = { onSettingsChanged(settings.copy(showStatsOverlay = it)) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        color = CardWhite,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, CardBorder),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsCardHeader(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = InkPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )
        }
    }
}

@Composable
private fun SelectablePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (selected) MintTint else PillNeutral)
                .then(
                    if (selected) {
                        Modifier.border(1.5.dp, MintBorder, RoundedCornerShape(999.dp))
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) EmeraldDark else InkPrimary
            )
        }
        if (selected) {
            CheckCircleBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-5).dp)
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = InkPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = EmeraldPrimary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFDDE4EC),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

private fun buildDiscoverySubtitle(nsdState: NsdAdvertiserState, lanBeaconRunning: Boolean): String {
    return when {
        nsdState is NsdAdvertiserState.Registered && lanBeaconRunning ->
            "Discoverable on your local network (mDNS + LAN)"
        lanBeaconRunning -> "Discoverable via LAN beacon on your network"
        nsdState is NsdAdvertiserState.Registered -> "Discoverable via mDNS on your local network"
        else -> "Starting network discovery…"
    }
}

private fun buildConnectedSubtitle(serverState: PhoneServerUiState): String {
    val rtt = serverState.lastRttMs
    return if (rtt != null) "${rtt} ms RTT · Ready to cast" else "Ready to start screen mirroring"
}

private fun buildStreamingSubtitle(
    captureStatus: CaptureStatus,
    serverState: PhoneServerUiState,
    settings: CastSettings
): String {
    val cfg = captureStatus.activeConfig
    val res = if (cfg != null) "${cfg.width}×${cfg.height} @ ${cfg.fps} fps" else "Streaming"
    val liveMbps = (captureStatus.currentBitrateBps.takeIf { it > 0 } ?: cfg?.bitrateBps ?: 0) / 1_000_000f
    val rate = if (settings.adaptiveBitrate) String.format("%.1f Mbps Auto", liveMbps) else String.format("%.0f Mbps", liveMbps)
    val tvFps = serverState.lastStatsReport?.let { " · TV ${it.renderedFps} fps" }.orEmpty()
    return "$res · $rate$tvFps"
}
