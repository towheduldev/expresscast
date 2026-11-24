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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    val activeShowOverlay: Boolean = true
)

/**
 * TCP control client running on the Android TV receiver.
 */
class TvControlClient(
    private val deviceName: String = Build.MODEL ?: "Android TV"
) {
    private val _state = MutableStateFlow(TvClientUiState())
    val state: StateFlow<TvClientUiState> = _state.asStateFlow()

    private val writeLock = Any()
    private val pendingKeyframe = AtomicBoolean()
    private val keyframeWriter = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ExpressCast-TvIdr").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var outputStream: OutputStream? = null

    fun connect(host: String, port: Int = NetworkConfig.DEFAULT_CONTROL_PORT) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            _state.value = _state.value.copy(
                connectionState = ConnectionState.Failed(ExpressCastError.NETWORK_UNAVAILABLE, "Host IP is required")
            )
            return
        }

        disconnect()
        running = true
        _state.value = TvClientUiState(
            connectionState = ConnectionState.Connecting,
            targetHost = cleanHost,
            targetPort = port
        )

        Thread({
            runClientLoop(cleanHost, port)
        }, "ExpressCast-TvControl").apply {
            isDaemon = true
            start()
        }
    }

    fun requestKeyframe() {
        val output = outputStream ?: return
        if (keyframeWriter.isShutdown || !pendingKeyframe.compareAndSet(false, true)) return
        try {
            keyframeWriter.execute {
                try {
                    sendFrameIfConnected(ControlMessage.RequestKeyframe, output)
                } finally {
                    pendingKeyframe.set(false)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pendingKeyframe.set(false)
            if (!keyframeWriter.isShutdown) Log.w(TAG, "Keyframe request could not be scheduled", e)
        }
    }

    fun close() {
        disconnect()
        keyframeWriter.shutdownNow()
    }

    fun disconnect() {
        running = false
        try {
            sendFrameIfConnected(ControlMessage.Disconnect("TV disconnected"))
        } catch (e: Exception) {
            Log.w(TAG, "Error sending disconnect frame: ${e.message}")
        }
        closeSocket()
        if (_state.value.connectionState !is ConnectionState.Idle) {
            _state.value = _state.value.copy(
                connectionState = ConnectionState.Disconnected,
                lastRttMs = null
            )
        }
    }

    private fun runClientLoop(host: String, port: Int) {
        val clientSocket = Socket()
        socket = clientSocket
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
                            _state.value = _state.value.copy(
                                connectionState = ConnectionState.Failed(err, "Rejected by ${message.deviceName}")
                            )
                            break
                        }
                        val pin = if (message.requiresPairing) Random.nextInt(1000, 10000).toString() else ""
                        val nextState = if (message.requiresPairing) {
                            ConnectionState.Pairing
                        } else {
                            startHeartbeatThread()
                            ConnectionState.Connected
                        }
                        _state.value = _state.value.copy(
                            connectionState = nextState,
                            connectedPhoneName = message.deviceName,
                            pairingPin = pin
                        )
                    }

                    is ControlMessage.PairRequest -> {
                        val expectedPin = _state.value.pairingPin
                        if (expectedPin.isNotEmpty() && message.pin == expectedPin) {
                            Log.i(TAG, "Phone pairing PIN verified")
                            sendFrame(output, ControlMessage.PairResult(accepted = true))
                            _state.value = _state.value.copy(
                                connectionState = ConnectionState.Connected,
                                pairingPin = ""
                            )
                            startHeartbeatThread()
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
                        Log.i(TAG, "Stream started by phone: ${message.config} on UDP :${message.videoPort}, audio=${message.audioEnabled}:${message.audioPort}")
                        _state.value = _state.value.copy(
                            connectionState = ConnectionState.Streaming,
                            activeStreamConfig = message.config,
                            activeVideoPort = message.videoPort,
                            peerAddress = clientSocket.inetAddress,
                            activeAudioEnabled = message.audioEnabled,
                            activeAudioPort = message.audioPort,
                            activeShowOverlay = message.showStatsOverlay
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
                        Log.i(TAG, "Server closed control channel: ${message.reason}")
                        break
                    }

                    is ControlMessage.Hello,
                    is ControlMessage.PairResult,
                    ControlMessage.RequestKeyframe -> {
                        Log.w(TAG, "Ignoring unexpected client-bound message: $message")
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Control connection failed to $host:$port", e)
                _state.value = _state.value.copy(
                    connectionState = ConnectionState.Failed(ExpressCastError.CONNECTION_LOST, e.message)
                )
            }
        } finally {
            running = false
            closeSocket()
            if (_state.value.connectionState !is ConnectionState.Failed) {
                _state.value = _state.value.copy(
                    connectionState = ConnectionState.Disconnected,
                    lastRttMs = null
                )
            }
        }
    }

    private fun startHeartbeatThread() {
        Thread({
            while (running && isActiveSession(_state.value.connectionState)) {
                try {
                    Thread.sleep(NetworkConfig.HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (running && isActiveSession(_state.value.connectionState)) {
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
