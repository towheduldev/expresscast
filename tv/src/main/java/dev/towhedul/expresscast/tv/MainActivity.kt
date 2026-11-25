package dev.towhedul.expresscast.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.towhedul.expresscast.shared.ConnectionState
import dev.towhedul.expresscast.tv.network.DiscoveredServer
import dev.towhedul.expresscast.tv.network.LanDiscoveryClient
import dev.towhedul.expresscast.tv.network.NsdBrowser
import dev.towhedul.expresscast.tv.network.TvControlClient
import dev.towhedul.expresscast.tv.ui.DiscoveredPhoneItem
import dev.towhedul.expresscast.tv.ui.TvReceiverScreen
import dev.towhedul.expresscast.tv.ui.TvStreamingPlayer
import dev.towhedul.expresscast.tv.ui.theme.TvTheme

class MainActivity : ComponentActivity() {
    private lateinit var nsdBrowser: NsdBrowser
    private lateinit var lanDiscoveryClient: LanDiscoveryClient
    private lateinit var controlClient: TvControlClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdBrowser = NsdBrowser(this)
        lanDiscoveryClient = LanDiscoveryClient()
        controlClient = TvControlClient()
        nsdBrowser.start()
        lanDiscoveryClient.start()

        setContent {
            TvTheme {
                val nsdServers by nsdBrowser.servers.collectAsState()
                val lanServers by lanDiscoveryClient.servers.collectAsState()
                val nsdDiscovering by nsdBrowser.isDiscovering.collectAsState()
                val lanScanning by lanDiscoveryClient.isScanning.collectAsState()
                val clientState by controlClient.state.collectAsState()

                val isStreaming = clientState.connectionState == ConnectionState.Streaming
                LaunchedEffect(isStreaming) {
                    if (isStreaming) {
                        nsdBrowser.stop()
                        lanDiscoveryClient.stop()
                    } else {
                        nsdBrowser.start()
                        lanDiscoveryClient.start()
                    }
                }

                val mergedPhones = remember(nsdServers, lanServers) {
                    mergeDiscoveredPhones(
                        nsdServers = nsdServers,
                        lanServers = lanServers
                    )
                }

                val streamConfig = clientState.activeStreamConfig
                if (isStreaming && streamConfig != null) {
                    key(streamConfig, clientState.activeVideoPort, clientState.peerAddress) {
                        TvStreamingPlayer(
                            window = window,
                            config = streamConfig,
                            videoPort = clientState.activeVideoPort,
                            peerAddress = clientState.peerAddress,
                            audioEnabled = clientState.activeAudioEnabled,
                            audioPort = clientState.activeAudioPort,
                            showOverlay = clientState.activeShowOverlay,
                            lastRttMs = clientState.lastRttMs,
                            onRequestKeyframe = controlClient::requestKeyframe,
                            onSendStreamStats = controlClient::sendStreamStats,
                            onDisconnect = controlClient::disconnect
                        )
                    }
                } else {
                    TvReceiverScreen(
                        phones = mergedPhones,
                        isDiscovering = nsdDiscovering || lanScanning,
                        clientState = clientState,
                        onRefreshDiscovery = {
                            nsdBrowser.stop()
                            nsdBrowser.start()
                            lanDiscoveryClient.start()
                            lanDiscoveryClient.probeNow()
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
        lanDiscoveryClient.stop()
        controlClient.close()
        super.onDestroy()
    }

    private fun mergeDiscoveredPhones(
        nsdServers: List<DiscoveredServer>,
        lanServers: List<DiscoveredServer>
    ): List<DiscoveredPhoneItem> {
        val byEndpoint = LinkedHashMap<String, DiscoveredPhoneItem>()
        for (server in nsdServers) {
            val key = "${server.host}:${server.port}"
            byEndpoint[key] = DiscoveredPhoneItem(
                server = server,
                fromNsd = true,
                fromLanBeacon = false
            )
        }
        for (server in lanServers) {
            val key = "${server.host}:${server.port}"
            val existing = byEndpoint[key]
            if (existing != null) {
                byEndpoint[key] = existing.copy(fromLanBeacon = true)
            } else {
                byEndpoint[key] = DiscoveredPhoneItem(
                    server = server,
                    fromNsd = false,
                    fromLanBeacon = true
                )
            }
        }
        return byEndpoint.values.toList()
    }
}
