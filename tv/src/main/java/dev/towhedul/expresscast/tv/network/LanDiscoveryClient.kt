package dev.towhedul.expresscast.tv.network

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
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Active UDP LAN discovery fallback that complements [NsdBrowser].
 * Sends subnet discovery probes and listens for phone announcements on [NetworkConfig.DEFAULT_DISCOVERY_PORT].
 */
class LanDiscoveryClient {
    private data class SeenEntry(val server: DiscoveredServer, val lastSeenMs: Long)

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val seenMap = LinkedHashMap<String, SeenEntry>()

    @Volatile
    private var running = false

    @Volatile
    private var probeSocket: DatagramSocket? = null

    @Volatile
    private var listenerSocket: DatagramSocket? = null

    @Volatile
    private var immediateProbeRequested = false

    private var probeThread: Thread? = null
    private var listenerThread: Thread? = null

    fun start() {
        if (running) {
            probeNow()
            return
        }
        running = true
        immediateProbeRequested = true
        _isScanning.value = true

        probeThread = Thread(::runProbeLoop, "ExpressCast-LanProbe").apply {
            isDaemon = true
            start()
        }
        listenerThread = Thread(::runBroadcastListener, "ExpressCast-LanListen").apply {
            isDaemon = true
            start()
        }
    }

    fun probeNow() {
        immediateProbeRequested = true
    }

    fun stop() {
        running = false
        _isScanning.value = false
        try {
            probeSocket?.close()
        } catch (_: Exception) {
        }
        try {
            listenerSocket?.close()
        } catch (_: Exception) {
        }
        probeSocket = null
        listenerSocket = null
        probeThread?.interrupt()
        listenerThread?.interrupt()
        probeThread = null
        listenerThread = null
    }

    private fun runProbeLoop() {
        try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = RECEIVE_TIMEOUT_MS
            }.use { udp ->
                probeSocket = udp
                val probe = LanDiscoveryProtocol.encodeProbe()
                val rxBuf = ByteArray(512)
                val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
                var lastProbeMs = 0L

                while (running) {
                    val now = System.currentTimeMillis()
                    if (immediateProbeRequested || now - lastProbeMs >= PROBE_INTERVAL_MS) {
                        immediateProbeRequested = false
                        lastProbeMs = now
                        sendProbes(udp, probe)
                        pruneStale(now)
                    }

                    rxPacket.length = rxBuf.size
                    try {
                        udp.receive(rxPacket)
                        handlePacket(rxPacket.address, rxBuf, rxPacket.length)
                    } catch (_: SocketTimeoutException) {
                        pruneStale(System.currentTimeMillis())
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "LAN probe loop ended: ${e.message}")
            }
        } finally {
            probeSocket = null
            _isScanning.value = false
        }
    }

    private fun runBroadcastListener() {
        try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(NetworkConfig.DEFAULT_DISCOVERY_PORT))
            }.use { udp ->
                listenerSocket = udp
                val rxBuf = ByteArray(512)
                val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
                while (running) {
                    rxPacket.length = rxBuf.size
                    try {
                        udp.receive(rxPacket)
                        handlePacket(rxPacket.address, rxBuf, rxPacket.length)
                    } catch (_: SocketTimeoutException) {
                        // Continue until stopped
                    }
                }
            }
        } catch (e: Exception) {
            // Port may be unavailable if another instance holds it; unicast probe replies still work.
            if (running) {
                Log.d(TAG, "Optional broadcast listener unavailable: ${e.message}")
            }
        } finally {
            listenerSocket = null
        }
    }

    private fun handlePacket(address: InetAddress?, data: ByteArray, length: Int) {
        val host = address?.hostAddress ?: return
        val announce = LanDiscoveryProtocol.decodeAnnounce(data, length) ?: return
        if (!ProtocolInfo.isCompatible(announce.protocolVersion)) return

        val key = "$host:${announce.controlPort}"
        val server = DiscoveredServer(
            serviceName = announce.deviceName,
            host = host,
            port = announce.controlPort
        )
        val now = System.currentTimeMillis()
        synchronized(seenMap) {
            val isNew = !seenMap.containsKey(key)
            seenMap[key] = SeenEntry(server, now)
            _servers.value = seenMap.values.map { it.server }
            if (isNew) {
                Log.i(TAG, "LAN beacon discovered ${server.serviceName} -> ${server.host}:${server.port}")
            }
        }
    }

    private fun pruneStale(nowMs: Long) {
        synchronized(seenMap) {
            val removed = seenMap.entries.removeIf { nowMs - it.value.lastSeenMs > STALE_TIMEOUT_MS }
            if (removed) {
                _servers.value = seenMap.values.map { it.server }
            }
        }
    }

    private fun sendProbes(udp: DatagramSocket, probe: ByteArray) {
        val targets = collectBroadcastTargets()
        for (addr in targets) {
            try {
                udp.send(DatagramPacket(probe, probe.size, addr, NetworkConfig.DEFAULT_DISCOVERY_PORT))
            } catch (e: Exception) {
                Log.d(TAG, "Probe to ${addr.hostAddress} skipped: ${e.message}")
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
            Log.d(TAG, "Unable to enumerate broadcast interfaces: ${e.message}")
        }
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return targets.toList()
    }

    private companion object {
        const val TAG = "ExpressCast.LanClient"
        const val RECEIVE_TIMEOUT_MS = 500
        const val PROBE_INTERVAL_MS = 2000L
        const val STALE_TIMEOUT_MS = 10_000L
    }
}
