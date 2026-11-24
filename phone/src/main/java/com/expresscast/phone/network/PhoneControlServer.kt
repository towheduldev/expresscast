package com.expresscast.phone.network

import android.os.Build
import android.util.Log
import com.expresscast.shared.ConnectionState
import com.expresscast.shared.ControlFraming
import com.expresscast.shared.ControlMessage
import com.expresscast.shared.ExpressCastError
import com.expresscast.shared.NetworkConfig
import com.expresscast.shared.ProtocolInfo
import com.expresscast.shared.VideoStreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.random.Random

data class PhoneServerUiState(
    val isListening: Boolean = false,
    val port: Int = NetworkConfig.DEFAULT_CONTROL_PORT,
    val localIps: List<String> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Idle,
    val connectedTvName: String? = null,
    val connectedTvAddress: String? = null,
    val lastRttMs: Long? = null,
    val pairingError: String? = null
)

/**
 * TCP control server running on the phone.
 * Handles protocol handshake, 4-digit pairing PIN verification, heartbeats, and test messages.
 */
class PhoneControlServer(
    private val deviceName: String = Build.MODEL ?: "Android Phone"
) {
    private val _state = MutableStateFlow(PhoneServerUiState())
    val state: StateFlow<PhoneServerUiState> = _state.asStateFlow()

    private val writeLock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var clientSocket: Socket? = null

    @Volatile
    private var clientOutput: OutputStream? = null

    private var acceptThread: Thread? = null

    @Volatile
    var onKeyframeRequested: (() -> Unit)? = null

    fun start(port: Int = NetworkConfig.DEFAULT_CONTROL_PORT) {
        if (running) return
        running = true

        val ips = discoverLocalIpv4Addresses()

        try {
            val socket = ServerSocket(port).apply {
                reuseAddress = true
            }
            serverSocket = socket
            _state.value = PhoneServerUiState(
                isListening = true,
                port = socket.localPort,
                localIps = ips,
                connectionState = ConnectionState.Idle
            )
            Log.i(TAG, "Control server listening on port ${socket.localPort}, IPs=$ips")

            acceptThread = Thread({
                acceptLoop(socket)
            }, "ExpressCast-ControlAccept").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start control server on port $port", e)
            running = false
            serverSocket = null
            _state.value = _state.value.copy(
                isListening = false,
                connectionState = ConnectionState.Failed(ExpressCastError.NETWORK_UNAVAILABLE, e.message)
            )
        }
    }

    fun stop() {
        running = false
        try {
            sendFrameIfConnected(ControlMessage.Disconnect("Server stopped"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending disconnect frame during stop: ${e.message}")
        }
        closeClientSocket()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        _state.value = PhoneServerUiState(
            isListening = false,
            connectionState = ConnectionState.Disconnected
        )
        Log.i(TAG, "Control server stopped")
    }

    fun submitPairingPin(pin: String) {
        val cleanPin = pin.trim()
        if (cleanPin.length != 4) return
        _state.value = _state.value.copy(pairingError = null)
        Thread({
            sendFrameIfConnected(ControlMessage.PairRequest(cleanPin))
        }, "ExpressCast-SubmitPin").start()
    }

    fun sendPing() {
        Thread({
            sendFrameIfConnected(ControlMessage.Ping(System.currentTimeMillis()))
        }, "ExpressCast-SendPing").start()
    }

    fun sendStartStream(
        config: VideoStreamConfig,
        videoPort: Int = NetworkConfig.DEFAULT_VIDEO_PORT,
        audioEnabled: Boolean = true,
        audioPort: Int = NetworkConfig.DEFAULT_AUDIO_PORT,
        showStatsOverlay: Boolean = true
    ) {
        Thread({
            if (sendFrameIfConnected(ControlMessage.StartStream(config, videoPort, audioEnabled, audioPort, showStatsOverlay))) {
                _state.value = _state.value.copy(connectionState = ConnectionState.Streaming)
            }
        }, "ExpressCast-StartStream").start()
    }

    fun sendStopStream() {
        Thread({
            if (sendFrameIfConnected(ControlMessage.StopStream)) {
                if (_state.value.connectionState == ConnectionState.Streaming) {
                    _state.value = _state.value.copy(connectionState = ConnectionState.Connected)
                }
            }
        }, "ExpressCast-StopStream").start()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running && !server.isClosed) {
            try {
                val client = server.accept()
                Log.i(TAG, "Accepted TCP control connection from ${client.inetAddress.hostAddress}")
                closeClientSocket()
                handleClient(client)
            } catch (e: SocketException) {
                if (running) {
                    Log.w(TAG, "Server accept socket error: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in accept loop", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        clientSocket = socket
        socket.tcpNoDelay = true
        val remoteAddress = socket.inetAddress.hostAddress ?: "unknown"
        var paired = false

        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            clientOutput = output

            _state.value = _state.value.copy(
                connectionState = ConnectionState.Connecting,
                connectedTvAddress = remoteAddress,
                connectedTvName = null,
                lastRttMs = null
            )

            while (running && !socket.isClosed) {
                val message = ControlFraming.readFrame(input) ?: break
                when (message) {
                    is ControlMessage.Hello -> {
                        val compatible = ProtocolInfo.isCompatible(message.protocolVersion)
                        if (!compatible) {
                            Log.w(TAG, "Rejected incompatible protocol v${message.protocolVersion} from ${message.deviceName}")
                            sendFrame(
                                output,
                                ControlMessage.HelloAck(
                                    protocolVersion = ProtocolInfo.PROTOCOL_VERSION,
                                    deviceName = deviceName,
                                    accepted = false,
                                    requiresPairing = false,
                                    error = ExpressCastError.PROTOCOL_MISMATCH
                                )
                            )
                            _state.value = _state.value.copy(
                                connectionState = ConnectionState.Failed(
                                    ExpressCastError.PROTOCOL_MISMATCH,
                                    "TV protocol v${message.protocolVersion} is unsupported"
                                )
                            )
                            break
                        }

                        Log.i(TAG, "Handshake Hello from ${message.deviceName} (v${message.protocolVersion})")
                        sendFrame(
                            output,
                            ControlMessage.HelloAck(
                                protocolVersion = ProtocolInfo.PROTOCOL_VERSION,
                                deviceName = deviceName,
                                accepted = true,
                                requiresPairing = true
                            )
                        )
                        _state.value = _state.value.copy(
                            connectionState = ConnectionState.Pairing,
                            connectedTvName = message.deviceName
                        )
                    }

                    is ControlMessage.PairResult -> {
                        if (message.accepted) {
                            paired = true
                            Log.i(TAG, "TV pairing accepted")
                            _state.value = _state.value.copy(
                                connectionState = ConnectionState.Connected,
                                pairingError = null
                            )
                        } else {
                            Log.w(TAG, "TV rejected pairing PIN: ${message.error}")
                            _state.value = _state.value.copy(
                                pairingError = "Invalid PIN, please check the TV screen"
                            )
                        }
                    }

                    is ControlMessage.Ping -> {
                        if (paired) {
                            sendFrame(output, ControlMessage.Pong(message.timestampMs))
                        }
                    }

                    is ControlMessage.Pong -> {
                        if (paired) {
                            val rtt = (System.currentTimeMillis() - message.timestampMs).coerceAtLeast(0L)
                            _state.value = _state.value.copy(lastRttMs = rtt)
                        }
                    }

                    is ControlMessage.TextMessage -> Unit

                    ControlMessage.RequestKeyframe -> {
                        if (paired) {
                            Log.d(TAG, "TV requested keyframe")
                            onKeyframeRequested?.invoke()
                        }
                    }

                    is ControlMessage.Disconnect -> {
                        Log.i(TAG, "TV disconnected: ${message.reason}")
                        break
                    }

                    is ControlMessage.HelloAck,
                    is ControlMessage.PairRequest,
                    is ControlMessage.StartStream,
                    ControlMessage.StopStream -> {
                        Log.w(TAG, "Ignoring unexpected server-bound message: $message")
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "Client connection ended with error: ${e.message}")
            }
        } finally {
            closeClientSocket()
            if (running) {
                _state.value = _state.value.copy(
                    connectionState = ConnectionState.Idle,
                    connectedTvName = null,
                    connectedTvAddress = null,
                    lastRttMs = null
                )
            }
        }
    }

    private fun sendFrame(output: OutputStream, message: ControlMessage) {
        synchronized(writeLock) {
            ControlFraming.writeFrame(output, message)
        }
    }

    private fun sendFrameIfConnected(message: ControlMessage): Boolean {
        val output = clientOutput ?: return false
        return try {
            sendFrame(output, message)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send control message $message: ${e.message}")
            false
        }
    }

    private fun closeClientSocket() {
        clientOutput = null
        try {
            clientSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing client socket: ${e.message}")
        }
        clientSocket = null
    }

    private fun discoverLocalIpv4Addresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate local IPv4 addresses: ${e.message}")
            emptyList()
        }
    }

    private companion object {
        const val TAG = "ExpressCast.PhoneCtrl"
    }
}
