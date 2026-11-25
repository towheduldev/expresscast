package dev.towhedul.expresscast.tv.network

import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.towhedul.expresscast.shared.ConnectionState
import dev.towhedul.expresscast.shared.ControlFraming
import dev.towhedul.expresscast.shared.ControlMessage
import dev.towhedul.expresscast.shared.DeviceRole
import dev.towhedul.expresscast.shared.ExpressCastError
import dev.towhedul.expresscast.shared.NetworkConfig
import dev.towhedul.expresscast.shared.ProtocolInfo
import dev.towhedul.expresscast.shared.VideoStreamConfig
import dev.towhedul.expresscast.tv.codec.HardwareH264Decoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class TvClientUiState(
    val connectionState: ConnectionState = ConnectionState.Idle,
    val targetHost: String = "",
    val targetPort: Int = NetworkConfig.DEFAULT_CONTROL_PORT,
    val connectedPhoneName: String? = null,
    val pairingPin: String = "",
    val lastRttMs: Long? = null,
    val activeStreamConfig: VideoStreamConfig? = null,
    val activeVideoPort: Int = NetworkConfig.DEFAULT_VIDEO_PORT,
    val peerAddress: InetAddress? = null,
    val activeAudioEnabled: Boolean = true,
    val activeAudioPort: Int = NetworkConfig.DEFAULT_AUDIO_PORT,
    val activeShowOverlay: Boolean = true,
    val negotiatedProtocolVersion: Int = 1,
    val statusDetail: String? = null
)

/**
 * TCP control client running on the Android TV receiver.
 * Handles protocol negotiation, 4-digit PIN pairing, capability reporting,
 * live stream stats reporting, and heartbeat monitoring.
 */
class TvControlClient(
    private val deviceName: String = Build.MODEL ?: "Android TV",
    private val capabilitiesProvider: () -> ControlMessage.PeerCapabilities = {
        HardwareH264Decoder.queryCapabilities()
    }
) {
    private val _state = MutableStateFlow(TvClientUiState())
    val state: StateFlow<TvClientUiState> = _state.asStateFlow()

    private val writeLock = Any()
    private val pendingKeyframe = AtomicBoolean()
    private val sessionGeneration = AtomicLong(0L)
    private val controlWriter = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ExpressCast-TvControlTx").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    @Volatile
    private var manualDisconnect = false

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var outputStream: OutputStream? = null

    private var clientThread: Thread? = null

    fun connect(host: String, port: Int = NetworkConfig.DEFAULT_CONTROL_PORT) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            _state.value = _state.value.copy(
                connectionState = ConnectionState.Failed(ExpressCastError.NETWORK_UNAVAILABLE, "Host IP is required"),
                statusDetail = "Enter a valid IP address"
            )
            return
        }

        stopActiveConnection(isManual = false)
        val gen = sessionGeneration.incrementAndGet()
        manualDisconnect = false
        running = true
        _state.value = TvClientUiState(
            connectionState = ConnectionState.Connecting,
            targetHost = cleanHost,
            targetPort = port,
            statusDetail = "Connecting to $cleanHost:$port…"
        )

        clientThread = Thread({
            runClientLoop(cleanHost, port, gen)
        }, "ExpressCast-TvControl").apply {
            isDaemon = true
            start()
        }
    }

    fun requestKeyframe() {
        val output = outputStream ?: return
        if (controlWriter.isShutdown || !pendingKeyframe.compareAndSet(false, true)) return
        try {
            controlWriter.execute {
                try {
                    sendFrameIfConnected(ControlMessage.RequestKeyframe, output)
                } finally {
                    pendingKeyframe.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            pendingKeyframe.set(false)
            if (!controlWriter.isShutdown) Log.w(TAG, "Keyframe request could not be scheduled", e)
        }
    }

    fun sendStreamStats(report: ControlMessage.StreamStatsReport) {
        val output = outputStream ?: return
        val current = _state.value
        if (current.negotiatedProtocolVersion < 2 || current.connectionState != ConnectionState.Streaming) return
        if (controlWriter.isShutdown) return
        try {
            controlWriter.execute {
                sendFrameIfConnected(report, output)
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    fun close() {
        disconnect()
        controlWriter.shutdownNow()
    }

    fun disconnect() {
        stopActiveConnection(isManual = true)
        if (_state.value.connectionState !is ConnectionState.Idle) {
            _state.value = _state.value.copy(
                connectionState = ConnectionState.Disconnected,
                activeStreamConfig = null,
                lastRttMs = null,
                pairingPin = "",
                statusDetail = null
            )
        }
    }

    private fun stopActiveConnection(isManual: Boolean) {
        manualDisconnect = isManual
        sessionGeneration.incrementAndGet()
        running = false
        val out = outputStream
        if (out != null && !controlWriter.isShutdown) {
            try {
                controlWriter.submit {
                    sendFrameIfConnected(ControlMessage.Disconnect("TV disconnected"), out)
                }.get(150L, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Error sending disconnect frame: ${e.message}")
            }
        }
        closeSocket()
        clientThread?.interrupt()
        clientThread = null
    }

    private fun runClientLoop(host: String, port: Int, gen: Long) {
        val clientSocket = Socket()
        socket = clientSocket
        var remoteClosedGracefully = false
        var protocolRejected = false
        var negotiatedVersion = 1
        var connectedPhoneName = host

        try {
            clientSocket.connect(InetSocketAddress(host, port), NetworkConfig.CONNECT_TIMEOUT_MS.toInt())
            clientSocket.tcpNoDelay = true
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            outputStream = output

            Log.i(TAG, "Connected TCP control socket to $host:$port")
            sendFrame(
                output,
                ControlMessage.Hello(
                    protocolVersion = ProtocolInfo.PROTOCOL_VERSION,
                    deviceName = deviceName,
                    role = DeviceRole.TV
                )
            )

            while (running && !clientSocket.isClosed) {
                val message = ControlFraming.readFrame(input) ?: break
                when (message) {
                    is ControlMessage.HelloAck -> {
                        if (!message.accepted) {
                            val err = message.error ?: ExpressCastError.PROTOCOL_MISMATCH
                            Log.w(TAG, "Handshake rejected by ${message.deviceName}: $err")
                            protocolRejected = true
                            _state.value = _state.value.copy(
                                connectionState = ConnectionState.Failed(err, "Rejected by ${message.deviceName}"),
                                statusDetail = "Protocol mismatch with ${message.deviceName}"
                            )
                            break
                        }

                        negotiatedVersion = ProtocolInfo.negotiatedVersion(message.protocolVersion)
                        connectedPhoneName = message.deviceName.ifBlank { host }

                        if (message.requiresPairing) {
                            val pin = Random.nextInt(1000, 10000).toString()
                            _state.value = _state.value.copy(
                                connectionState = ConnectionState.Pairing,
                                connectedPhoneName = connectedPhoneName,
                                pairingPin = pin,
                                negotiatedProtocolVersion = negotiatedVersion,
                                statusDetail = null
                            )
                        } else {
                            enableHeartbeatReadTimeout(clientSocket)
                            _state.value = _state.value.copy(
                                connectionState = ConnectionState.Connected,
                                connectedPhoneName = connectedPhoneName,
                                pairingPin = "",
                                negotiatedProtocolVersion = negotiatedVersion,
                                statusDetail = null
                            )
                            sendCapabilitiesIfSupported(output, negotiatedVersion)
                            startHeartbeatThread(clientSocket)
                        }
                    }

                    is ControlMessage.PairRequest -> {
                        val expectedPin = _state.value.pairingPin
                        if (expectedPin.isNotEmpty() && message.pin == expectedPin) {
                            Log.i(TAG, "Phone pairing PIN verified")
                            sendFrame(output, ControlMessage.PairResult(accepted = true))
                            enableHeartbeatReadTimeout(clientSocket)
                            sendCapabilitiesIfSupported(output, negotiatedVersion)

                            _state.value = _state.value.copy(
                                connectionState = ConnectionState.Connected,
                                pairingPin = "",
                                statusDetail = null
                            )
                            startHeartbeatThread(clientSocket)
                        } else {
                            Log.w(TAG, "Phone submitted invalid pairing PIN")
                            sendFrame(
                                output,
                                ControlMessage.PairResult(
                                    accepted = false,
                                    error = ExpressCastError.PAIRING_REJECTED
                                )
                            )
                        }
                    }

                    is ControlMessage.Ping -> {
                        sendFrame(output, ControlMessage.Pong(message.timestampMs))
                    }

                    is ControlMessage.Pong -> {
                        val rtt = (SystemClock.elapsedRealtime() - message.timestampMs).coerceAtLeast(0L)
                        _state.value = _state.value.copy(lastRttMs = rtt)
                    }

                    is ControlMessage.TextMessage -> Unit

                    is ControlMessage.StartStream -> {
                        Log.i(
                            TAG,
                            "Stream started by phone: ${message.config} on UDP :${message.videoPort}, audio=${message.audioEnabled}:${message.audioPort}"
                        )
                        _state.value = _state.value.copy(
                            connectionState = ConnectionState.Streaming,
                            activeStreamConfig = message.config,
                            activeVideoPort = message.videoPort,
                            peerAddress = clientSocket.inetAddress,
                            activeAudioEnabled = message.audioEnabled,
                            activeAudioPort = message.audioPort,
                            activeShowOverlay = message.showStatsOverlay,
                            statusDetail = null
                        )
                    }

                    ControlMessage.StopStream -> {
                        Log.i(TAG, "Stream stopped by phone")
                        _state.value = _state.value.copy(
                            connectionState = ConnectionState.Connected,
                            activeStreamConfig = null
                        )
                    }

                    is ControlMessage.Disconnect -> {
                        remoteClosedGracefully = true
                        Log.i(TAG, "Server closed control channel: ${message.reason}")
                        break
                    }

                    is ControlMessage.Hello,
                    is ControlMessage.PairResult,
                    is ControlMessage.StreamStatsReport,
                    is ControlMessage.PeerCapabilities,
                    ControlMessage.RequestKeyframe -> {
                        Log.w(TAG, "Ignoring unexpected client-bound message: $message")
                    }
                }
            }
        } catch (e: Exception) {
            if (running && !manualDisconnect) {
                Log.w(TAG, "Control connection error ($host:$port): ${e.message}")
                if (sessionGeneration.get() == gen) {
                    _state.value = _state.value.copy(
                        connectionState = ConnectionState.Failed(
                            ExpressCastError.CONNECTION_LOST,
                            e.message ?: "Unable to connect to $host:$port"
                        ),
                        activeStreamConfig = null,
                        lastRttMs = null,
                        statusDetail = e.message ?: "Connection lost"
                    )
                }
            }
        } finally {
            closeSocket()
            if (sessionGeneration.get() == gen) {
                running = false
                if (_state.value.connectionState !is ConnectionState.Failed && !protocolRejected) {
                    _state.value = _state.value.copy(
                        connectionState = ConnectionState.Disconnected,
                        activeStreamConfig = null,
                        lastRttMs = null,
                        pairingPin = "",
                        statusDetail = if (remoteClosedGracefully) "Phone ended session" else null
                    )
                }
            }
        }
    }

    private fun enableHeartbeatReadTimeout(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = (NetworkConfig.HEARTBEAT_TIMEOUT_MS + 500L).toInt()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to set TV heartbeat read timeout: ${e.message}")
        }
    }

    private fun sendCapabilitiesIfSupported(output: OutputStream, negotiatedVersion: Int) {
        if (negotiatedVersion < 2) return
        try {
            sendFrame(output, capabilitiesProvider())
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending TV capabilities: ${e.message}")
        }
    }

    private fun startHeartbeatThread(activeSocket: Socket) {
        Thread({
            while (running && socket === activeSocket && isActiveSession(_state.value.connectionState)) {
                try {
                    Thread.sleep(NetworkConfig.HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (running && socket === activeSocket && isActiveSession(_state.value.connectionState)) {
                    sendFrameIfConnected(ControlMessage.Ping(SystemClock.elapsedRealtime()))
                }
            }
        }, "ExpressCast-TvHeartbeat").apply {
            isDaemon = true
            start()
        }
    }

    private fun isActiveSession(state: ConnectionState): Boolean {
        return state == ConnectionState.Connected || state == ConnectionState.Streaming
    }

    private fun sendFrame(output: OutputStream, message: ControlMessage) {
        synchronized(writeLock) {
            ControlFraming.writeFrame(output, message)
        }
    }

    private fun sendFrameIfConnected(message: ControlMessage, out: OutputStream? = outputStream): Boolean {
        if (out == null) return false
        return try {
            synchronized(writeLock) {
                if (outputStream !== out) return false
                ControlFraming.writeFrame(out, message)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending $message: ${e.message}")
            false
        }
    }

    private fun closeSocket() {
        outputStream = null
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing TV socket: ${e.message}")
        }
        socket = null
    }

    private companion object {
        const val TAG = "ExpressCast.TvCtrl"
    }
}
