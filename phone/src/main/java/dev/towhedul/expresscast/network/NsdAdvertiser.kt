package dev.towhedul.expresscast.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.towhedul.expresscast.shared.NetworkConfig
import dev.towhedul.expresscast.shared.ProtocolInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface NsdAdvertiserState {
    data object Idle : NsdAdvertiserState
    data object Registering : NsdAdvertiserState
    data class Registered(val serviceName: String) : NsdAdvertiserState
    data class Failed(val errorCode: Int) : NsdAdvertiserState
}

/**
 * Advertises the ExpressCast phone control server over mDNS/NSD (`_expresscast._tcp`).
 */
class NsdAdvertiser(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _state = MutableStateFlow<NsdAdvertiserState>(NsdAdvertiserState.Idle)
    val state: StateFlow<NsdAdvertiserState> = _state.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start(serviceName: String = ProtocolInfo.APP_NAME, port: Int = NetworkConfig.DEFAULT_CONTROL_PORT) {
        stop()
        _state.value = NsdAdvertiserState.Registering

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = NetworkConfig.NSD_SERVICE_TYPE
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service registered: ${info.serviceName} (${info.serviceType}) on port $port")
                _state.value = NsdAdvertiserState.Registered(info.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed with errorCode=$errorCode")
                _state.value = NsdAdvertiserState.Failed(errorCode)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD service unregistered: ${serviceInfo.serviceName}")
                _state.value = NsdAdvertiserState.Idle
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed with errorCode=$errorCode")
                _state.value = NsdAdvertiserState.Idle
            }
        }

        registrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
            registrationListener = null
            _state.value = NsdAdvertiserState.Failed(-1)
        }
    }

    fun stop() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "NSD listener already unregistered: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering NSD service", e)
        }
        _state.value = NsdAdvertiserState.Idle
    }

    private companion object {
        const val TAG = "ExpressCast.NsdAdv"
    }
}
