package dev.towhedul.expresscast.network

import android.os.Build
import android.util.Log
import dev.towhedul.expresscast.shared.LanDiscoveryProtocol
import dev.towhedul.expresscast.shared.NetworkConfig
import dev.towhedul.expresscast.shared.ProtocolInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * UDP LAN discovery beacon responder running alongside [NsdAdvertiser].
 * Replies directly to TV discovery probes and sends periodic subnet broadcast beacons so
 * receivers discover the phone even on routers/APs that drop multicast mDNS traffic.
 */
class LanDiscoveryResponder(
    private val deviceName: String = Build.MODEL ?: ProtocolInfo.APP_NAME
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    private var worker: Thread? = null

    fun start(controlPort: Int = NetworkConfig.DEFAULT_CONTROL_PORT) {
        stop()
        running = true
        worker = Thread({
            runLoop(controlPort)
        }, "ExpressCast-LanBeacon").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LAN beacon socket: ${e.message}")
        }
        socket = null
        worker?.interrupt()
        worker = null
        _isRunning.value = false
    }

    private fun runLoop(controlPort: Int) {
        try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = RECEIVE_TIMEOUT_MS
                bind(java.net.InetSocketAddress(NetworkConfig.DEFAULT_DISCOVERY_PORT))
            }.use { udp ->
                socket = udp
                _isRunning.value = true
                Log.i(TAG, "LAN discovery beacon listening on UDP :${NetworkConfig.DEFAULT_DISCOVERY_PORT} (ctrl=:$controlPort)")

                val announcePayload = LanDiscoveryProtocol.encodeAnnounce(deviceName, controlPort)
                val rxBuf = ByteArray(256)
                val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
                var lastBroadcastMs = 0L

                while (running) {
                    val now = System.currentTimeMillis()
                    if (now - lastBroadcastMs >= BROADCAST_INTERVAL_MS) {
                        lastBroadcastMs = now
                        sendSubnetBroadcasts(udp, announcePayload)
                    }

                    rxPacket.length = rxBuf.size
                    try {
                        udp.receive(rxPacket)
                        if (LanDiscoveryProtocol.isProbe(rxBuf, rxPacket.length)) {
                            val reply = DatagramPacket(
                                announcePayload,
                                announcePayload.size,
                                rxPacket.address,
                                rxPacket.port
                            )
                            udp.send(reply)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Periodic wakeup to send subnet announcements.
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "LAN discovery beacon stopped: ${e.message}")
            }
        } finally {
            socket = null
            _isRunning.value = false
        }
    }

    private fun sendSubnetBroadcasts(udp: DatagramSocket, payload: ByteArray) {
        val targets = collectBroadcastTargets()
        for (addr in targets) {
            try {
                udp.send(DatagramPacket(payload, payload.size, addr, NetworkConfig.DEFAULT_DISCOVERY_PORT))
            } catch (e: Exception) {
                Log.d(TAG, "Broadcast to ${addr.hostAddress} skipped: ${e.message}")
            }
        }
    }

    private fun collectBroadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ifAddr in iface.interfaceAddresses) {
                    if (ifAddr.address is Inet4Address) {
                        ifAddr.broadcast?.let { targets.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Unable to enumerate interface broadcast addresses: ${e.message}")
        }
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return targets.toList()
    }

    private companion object {
        const val TAG = "ExpressCast.LanDisc"
        const val RECEIVE_TIMEOUT_MS = 1000
        const val BROADCAST_INTERVAL_MS = 3000L
    }
}
