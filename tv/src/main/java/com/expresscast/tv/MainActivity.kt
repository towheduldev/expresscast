package com.expresscast.tv

import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.expresscast.shared.ConnectionState
import com.expresscast.shared.ProtocolInfo
import com.expresscast.shared.VideoStreamConfig
import com.expresscast.tv.codec.HardwareH264Decoder
import com.expresscast.tv.network.DiscoveredServer
import com.expresscast.tv.network.NsdBrowser
import com.expresscast.tv.network.TvClientUiState
import com.expresscast.tv.network.TvControlClient
import com.expresscast.tv.network.UdpVideoReceiver
import com.expresscast.tv.ui.theme.TvTheme

class MainActivity : ComponentActivity() {
    private lateinit var nsdBrowser: NsdBrowser
    private lateinit var controlClient: TvControlClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdBrowser = NsdBrowser(this)
        controlClient = TvControlClient()
        nsdBrowser.start()

        setContent {
            TvTheme {
                val servers by nsdBrowser.servers.collectAsState()
                val isDiscovering by nsdBrowser.isDiscovering.collectAsState()
                val clientState by controlClient.state.collectAsState()

                val streamConfig = clientState.activeStreamConfig
                if (clientState.connectionState == ConnectionState.Streaming && streamConfig != null) {
                    TvStreamingPlayer(
                        window = window,
                        config = streamConfig,
                        videoPort = clientState.activeVideoPort,
                        lastRttMs = clientState.lastRttMs,
                        onRequestKeyframe = { controlClient.requestKeyframe() },
                        onDisconnect = { controlClient.disconnect() }
                    )
                } else {
                    TvReceiverScreen(
                        servers = servers,
                        isDiscovering = isDiscovering,
                        clientState = clientState,
                        onRefreshDiscovery = {
                            nsdBrowser.stop()
                            nsdBrowser.start()
                        },
                        onConnect = { host, port -> controlClient.connect(host, port) },
                        onDisconnect = { controlClient.disconnect() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        nsdBrowser.stop()
        controlClient.disconnect()
        super.onDestroy()
    }
}

@Composable
fun TvStreamingPlayer(
    window: Window,
    config: VideoStreamConfig,
    videoPort: Int,
    lastRttMs: Long?,
    onRequestKeyframe: () -> Unit,
    onDisconnect: () -> Unit
) {
    var decoder by remember { mutableStateOf<HardwareH264Decoder?>(null) }
    var receiver by remember { mutableStateOf<UdpVideoReceiver?>(null) }

    DisposableEffect(config, videoPort) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setPreferMinimalPostProcessing(true)
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setPreferMinimalPostProcessing(false)
            }
            receiver?.stop()
            receiver = null
            decoder?.stop()
            decoder = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    keepScreenOn = true
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val h264Decoder = HardwareH264Decoder(config, holder.surface)
                            decoder = h264Decoder
                            val udpRx = UdpVideoReceiver(
                                port = videoPort,
                                onFrame = { frame ->
                                    h264Decoder.submitFrame(frame.data, frame.ptsUs, frame.flags)
                                },
                                onPacketLoss = {
                                    h264Decoder.onFrameDropped()
                                    onRequestKeyframe()
                                }
                            )
                            receiver = udpRx
                            udpRx.start()
                            onRequestKeyframe()
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

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            color = Color.Black.copy(alpha = 0.65f),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${config.width}×${config.height}@${config.fps}  •  RTT: ${lastRttMs?.let { "$it ms" } ?: "—"}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                TvActionButton(
                    text = "Stop",
                    onClick = onDisconnect,
                    isPrimary = false
                )
            }
        }
    }
}

@Composable
fun TvReceiverScreen(
    servers: List<DiscoveredServer>,
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
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 680.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "${ProtocolInfo.APP_NAME} TV",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = formatTvConnectionState(clientState.connectionState, isDiscovering),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            when (clientState.connectionState) {
                ConnectionState.Pairing -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Enter this PIN on ${clientState.connectedPhoneName ?: "your phone"}",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = clientState.pairingPin.chunked(1).joinToString("  "),
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TvActionButton(
                                text = "Cancel",
                                onClick = onDisconnect,
                                isPrimary = false
                            )
                        }
                    }
                }

                ConnectionState.Connected -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Paired with ${clientState.connectedPhoneName ?: clientState.targetHost}",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = "Tap \"Start Casting to TV\" on your phone  •  RTT: ${clientState.lastRttMs?.let { "$it ms" } ?: "—"}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            TvActionButton(
                                text = "Disconnect",
                                onClick = onDisconnect,
                                isPrimary = false
                            )
                        }
                    }
                }

                else -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text("Available Phones", style = MaterialTheme.typography.titleLarge)
                            if (servers.isEmpty()) {
                                Text(
                                    text = "Open ExpressCast on your phone on the same Wi-Fi network.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                servers.forEach { server ->
                                    TvActionButton(
                                        text = "Cast from ${server.serviceName}",
                                        onClick = { onConnect(server.host, server.port) },
                                        isPrimary = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            TvActionButton(
                                text = "Refresh",
                                onClick = onRefreshDiscovery,
                                isPrimary = false
                            )
                        }
                    }
                }
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
    val shape = RoundedCornerShape(12.dp)
    val border = if (isFocused) {
        BorderStroke(3.dp, Color.White)
    } else if (!isPrimary) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    } else {
        null
    }
    val scaleModifier = if (isFocused) modifier.scale(1.03f) else modifier

    if (isPrimary) {
        Button(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = shape,
            border = border,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = scaleModifier
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = shape,
            border = border,
            modifier = scaleModifier
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun formatTvConnectionState(state: ConnectionState, isDiscovering: Boolean): String = when (state) {
    ConnectionState.Idle -> if (isDiscovering) "Searching for phones…" else "Ready"
    ConnectionState.Discovering -> "Searching for phones…"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Pairing -> "Waiting for phone confirmation…"
    ConnectionState.Connected -> "Ready to cast"
    ConnectionState.Streaming -> "Casting"
    is ConnectionState.Reconnecting -> "Reconnecting (#${state.attempt})"
    ConnectionState.Disconnected -> if (isDiscovering) "Disconnected — searching…" else "Disconnected"
    is ConnectionState.Failed -> "Connection failed (${state.error})"
}
