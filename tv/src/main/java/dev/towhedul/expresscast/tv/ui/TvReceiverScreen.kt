package dev.towhedul.expresscast.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.towhedul.expresscast.shared.ConnectionState
import dev.towhedul.expresscast.shared.NetworkConfig
import dev.towhedul.expresscast.shared.ProtocolInfo
import dev.towhedul.expresscast.tv.R
import dev.towhedul.expresscast.tv.network.DiscoveredServer
import dev.towhedul.expresscast.tv.network.TvClientUiState
import dev.towhedul.expresscast.tv.ui.theme.TvCanvasBg
import dev.towhedul.expresscast.tv.ui.theme.TvCardBorder
import dev.towhedul.expresscast.tv.ui.theme.TvCardWhite
import dev.towhedul.expresscast.tv.ui.theme.TvEmerald
import dev.towhedul.expresscast.tv.ui.theme.TvEmeraldDark
import dev.towhedul.expresscast.tv.ui.theme.TvFocusDeep
import dev.towhedul.expresscast.tv.ui.theme.TvInkPrimary
import dev.towhedul.expresscast.tv.ui.theme.TvInkSecondary
import dev.towhedul.expresscast.tv.ui.theme.TvMintBorder
import dev.towhedul.expresscast.tv.ui.theme.TvMintTint
import dev.towhedul.expresscast.tv.ui.theme.TvPillNeutral
import dev.towhedul.expresscast.tv.ui.theme.TvStatusAmber
import dev.towhedul.expresscast.tv.ui.theme.TvStatusGreen
import dev.towhedul.expresscast.tv.ui.theme.TvStatusRed

data class DiscoveredPhoneItem(
    val server: DiscoveredServer,
    val fromNsd: Boolean,
    val fromLanBeacon: Boolean
)

@Composable
fun TvReceiverScreen(
    phones: List<DiscoveredPhoneItem>,
    isDiscovering: Boolean,
    clientState: TvClientUiState,
    onRefreshDiscovery: () -> Unit,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvCanvasBg)
    ) {
        TvMintWaveBackdrop(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(180.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 44.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Top bar matching Phone brand header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_source),
                        contentDescription = "${ProtocolInfo.APP_NAME} TV",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Column {
                        Text(
                            text = "${ProtocolInfo.APP_NAME} TV",
                            style = MaterialTheme.typography.displaySmall,
                            color = TvInkPrimary
                        )
                        Text(
                            text = "Low-Latency LAN Cast Receiver",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TvInkSecondary
                        )
                    }
                }
                TvStatusChip(clientState.connectionState, isDiscovering)
            }

            when (clientState.connectionState) {
                ConnectionState.Pairing -> {
                    CenteredHeroCard {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvShieldCheckIcon(color = TvEmerald, modifier = Modifier.size(30.dp))
                            Text(
                                text = "Enter PIN on ${clientState.connectedPhoneName ?: "your phone"}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TvInkPrimary
                            )
                        }
                        clientState.statusDetail?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TvStatusAmber,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            clientState.pairingPin.forEach { digit ->
                                Surface(
                                    color = TvMintTint,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(2.dp, TvMintBorder)
                                ) {
                                    Text(
                                        text = digit.toString(),
                                        style = MaterialTheme.typography.displayLarge,
                                        color = TvInkPrimary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Enter this 4-digit PIN in ExpressCast on your phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TvInkSecondary
                        )
                        TvActionButton(
                            text = "Cancel",
                            onClick = onDisconnect,
                            isPrimary = false
                        )
                    }
                }

                ConnectionState.Connected -> {
                    CenteredHeroCard(borderColor = TvMintBorder) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(TvStatusGreen)
                            )
                            Text(
                                text = "Connected to ${clientState.connectedPhoneName ?: clientState.targetHost}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TvInkPrimary
                            )
                        }
                        Text(
                            text = "Tap \"Start Casting to TV\" on your phone",
                            style = MaterialTheme.typography.titleLarge,
                            color = TvEmeraldDark
                        )
                        Surface(
                            color = TvPillNeutral,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "Control RTT: ${clientState.lastRttMs?.let { "$it ms" } ?: "—"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = TvInkSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        TvActionButton(
                            text = "Disconnect",
                            onClick = onDisconnect,
                            isPrimary = false
                        )
                    }
                }

                is ConnectionState.Reconnecting -> {
                    CenteredHeroCard(borderColor = TvStatusAmber) {
                        Text(
                            text = "Reconnecting to ${clientState.connectedPhoneName ?: clientState.targetHost}…",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TvStatusAmber
                        )
                        Text(
                            text = clientState.statusDetail ?: "Attempting automatic resume",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TvInkSecondary
                        )
                        TvActionButton(
                            text = "Cancel Reconnect",
                            onClick = onDisconnect,
                            isPrimary = false
                        )
                    }
                }

                else -> {
                    DiscoveryAndManualIpLayout(
                        phones = phones,
                        clientState = clientState,
                        isDiscovering = isDiscovering,
                        onRefreshDiscovery = onRefreshDiscovery,
                        onConnect = onConnect,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryAndManualIpLayout(
    phones: List<DiscoveredPhoneItem>,
    clientState: TvClientUiState,
    isDiscovering: Boolean,
    onRefreshDiscovery: () -> Unit,
    onConnect: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var ipInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf(NetworkConfig.DEFAULT_CONTROL_PORT.toString()) }
    var inputError by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Left card: Nearby phones
        Surface(
            color = TvCardWhite,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, TvCardBorder),
            shadowElevation = 2.dp,
            modifier = Modifier
                .weight(1.15f)
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFE7FAEF), Color(0xFFF6FCF9), TvCardWhite)
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvSignalIcon(color = TvEmerald, modifier = Modifier.size(22.dp))
                            Text(
                                text = "Nearby Phones",
                                style = MaterialTheme.typography.titleLarge,
                                color = TvInkPrimary
                            )
                        }
                        TvBadge(
                            text = if (isDiscovering) "Scanning mDNS + LAN" else "Scan Idle",
                            highlighted = isDiscovering
                        )
                    }

                    if (phones.isEmpty()) {
                        Surface(
                            color = TvPillNeutral,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Open ExpressCast on your phone on the same Wi-Fi network. If your router blocks discovery, enter the Local IP on the right.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TvInkSecondary,
                                modifier = Modifier.padding(18.dp)
                            )
                        }
                    } else {
                        phones.forEach { item ->
                            val sourceLabel = when {
                                item.fromNsd && item.fromLanBeacon -> "mDNS + LAN"
                                item.fromLanBeacon -> "LAN Beacon"
                                else -> "mDNS"
                            }
                            TvPhoneRowButton(
                                title = item.server.serviceName,
                                subtitle = "${item.server.host}:${item.server.port} · $sourceLabel",
                                isPrimary = true,
                                onClick = { onConnect(item.server.host, item.server.port) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f, fill = false))

                    TvActionButton(
                        text = "Refresh Scan",
                        onClick = onRefreshDiscovery,
                        isPrimary = false
                    )
                }
            }
        }

        // Right card: Manual IP fallback
        Surface(
            color = TvCardWhite,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, TvCardBorder),
            shadowElevation = 2.dp,
            modifier = Modifier
                .weight(0.85f)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvMonitorIcon(color = TvEmerald, modifier = Modifier.size(22.dp))
                    Text(
                        text = "Connect by IP",
                        style = MaterialTheme.typography.titleLarge,
                        color = TvInkPrimary
                    )
                }
                Text(
                    text = "Phone not showing up? Enter the Local IP Address displayed in the ExpressCast phone app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvInkSecondary
                )

                val textFieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TvEmerald,
                    unfocusedBorderColor = TvCardBorder,
                    focusedLabelColor = TvEmeraldDark,
                    unfocusedLabelColor = TvInkSecondary,
                    focusedTextColor = TvInkPrimary,
                    unfocusedTextColor = TvInkPrimary,
                    cursorColor = TvEmerald
                )

                OutlinedTextField(
                    value = ipInput,
                    onValueChange = {
                        ipInput = it
                        inputError = null
                    },
                    label = { Text("Phone IP Address") },
                    placeholder = { Text("e.g. 192.168.1.42") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = textFieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { value ->
                        portInput = value.filter { it.isDigit() }.take(5)
                        inputError = null
                    },
                    label = { Text("Control Port") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = textFieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                inputError?.let { err ->
                    Text(
                        text = err,
                        color = TvStatusRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (clientState.connectionState is ConnectionState.Failed) {
                    Surface(
                        color = TvStatusRed.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, TvStatusRed.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = clientState.statusDetail
                                ?: clientState.connectionState.detail
                                ?: "Connection failed (${clientState.connectionState.error})",
                            color = TvStatusRed,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else if (clientState.connectionState == ConnectionState.Connecting) {
                    Text(
                        text = clientState.statusDetail ?: "Connecting…",
                        color = TvEmeraldDark,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                TvActionButton(
                    text = "Connect to IP",
                    onClick = {
                        val host = ipInput.trim()
                        val port = portInput.toIntOrNull()
                        when {
                            host.isEmpty() -> inputError = "Enter the phone's IP address."
                            port == null || port !in 1..65535 -> inputError = "Enter a port between 1 and 65535."
                            else -> onConnect(host, port)
                        }
                    },
                    isPrimary = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CenteredHeroCard(
    borderColor: Color = TvCardBorder,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TvCardWhite,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, borderColor),
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(max = 740.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFE7FAEF), Color(0xFFF6FCF9), TvCardWhite)
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun TvPhoneRowButton(
    title: String,
    subtitle: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val border = when {
        isFocused -> BorderStroke(3.dp, TvEmerald)
        isPrimary -> BorderStroke(1.dp, TvMintBorder.copy(alpha = 0.55f))
        else -> BorderStroke(1.dp, TvCardBorder)
    }
    val containerColor = when {
        isFocused -> TvMintTint
        isPrimary -> Color(0xFFF7FBF9)
        else -> TvPillNeutral
    }

    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = shape,
        border = border,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = TvInkPrimary
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isFocused) it.scale(1.02f) else it }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TvInkPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TvInkSecondary
                )
            }
            if (isFocused) {
                TvCheckCircleBadge()
            }
        }
    }
}

@Composable
private fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(999.dp)
    val border = when {
        isFocused && isPrimary -> BorderStroke(3.dp, TvFocusDeep)
        isFocused -> BorderStroke(3.dp, TvEmerald)
        !isPrimary -> BorderStroke(1.dp, TvCardBorder)
        else -> null
    }
    val scaleModifier = if (isFocused) modifier.scale(1.03f) else modifier

    if (isPrimary) {
        Button(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = shape,
            border = border,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isFocused) TvEmeraldDark else TvEmerald,
                contentColor = Color.White
            ),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            modifier = scaleModifier
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = shape,
            border = border,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isFocused) TvMintTint else TvCardWhite,
                contentColor = if (isFocused) TvEmeraldDark else TvInkPrimary
            ),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            modifier = scaleModifier
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TvBadge(text: String, highlighted: Boolean) {
    Surface(
        color = if (highlighted) TvMintTint else TvPillNeutral,
        shape = RoundedCornerShape(999.dp),
        border = if (highlighted) BorderStroke(1.dp, TvMintBorder.copy(alpha = 0.45f)) else null
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) TvEmeraldDark else TvInkSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun TvStatusChip(state: ConnectionState, isDiscovering: Boolean) {
    val (color, label) = when (state) {
        ConnectionState.Connecting -> TvStatusAmber to "Connecting"
        ConnectionState.Pairing -> TvStatusAmber to "Pairing"
        ConnectionState.Connected -> TvStatusGreen to "Ready"
        ConnectionState.Streaming -> TvStatusGreen to "Casting"
        is ConnectionState.Reconnecting -> TvStatusAmber to "Reconnecting (#${state.attempt})"
        is ConnectionState.Failed -> TvStatusRed to "Failed"
        ConnectionState.Disconnected -> TvInkSecondary to if (isDiscovering) "Searching" else "Disconnected"
        else -> TvEmerald to if (isDiscovering) "Searching" else "Idle"
    }
    Surface(
        color = TvCardWhite,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = TvInkPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
