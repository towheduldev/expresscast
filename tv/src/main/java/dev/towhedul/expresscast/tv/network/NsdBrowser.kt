package dev.towhedul.expresscast.tv.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dev.towhedul.expresscast.shared.NetworkConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class DiscoveredServer(
    val serviceName: String,
    val host: String,
    val port: Int
)

/**
 * Discovers ExpressCast phone servers on the local network via Android NSD/mDNS.
 */
class NsdBrowser(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val serviceInfoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    fun start() {
        if (discoveryListener != null) return

        acquireMulticastLock()
        _servers.value = emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "NSD discovery started for $regType")
                _isDiscovering.value = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                if (!serviceInfo.serviceType.contains(NetworkConfig.NSD_SERVICE_TYPE)) return
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD service lost: ${serviceInfo.serviceName}")
                removeServer(serviceInfo.serviceName)
                unregisterServiceInfoCallback(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD discovery stopped")
                _isDiscovering.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD start discovery failed: errorCode=$errorCode")
                _isDiscovering.value = false
                discoveryListener = null
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD stop discovery failed: errorCode=$errorCode")
                _isDiscovering.value = false
            }
        }

        discoveryListener = listener
        try {
            nsdManager.discoverServices(NetworkConfig.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
            discoveryListener = null
            _isDiscovering.value = false
            releaseMulticastLock()
        }
    }

    fun stop() {
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping NSD discovery: ${e.message}")
            }
        }
        clearServiceInfoCallbacks()
        releaseMulticastLock()
        _isDiscovering.value = false
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val name = serviceInfo.serviceName
            unregisterServiceInfoCallback(name)
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(TAG, "ServiceInfoCallback registration failed for $name: $errorCode")
                }

                override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                    val host = updatedInfo.hostAddresses.firstOrNull()?.hostAddress ?: return
                    upsertServer(DiscoveredServer(updatedInfo.serviceName, host, updatedInfo.port))
                }

                override fun onServiceLost() {
                    removeServer(name)
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            serviceInfoCallbacks[name] = callback
            try {
                nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed registering ServiceInfoCallback for $name: ${e.message}")
                serviceInfoCallbacks.remove(name)
            }
        } else {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "NSD resolve failed for ${info.serviceName}: errorCode=$errorCode")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = resolved.host?.hostAddress ?: return
                        upsertServer(DiscoveredServer(resolved.serviceName, host, resolved.port))
                    }
                }
            )
        }
    }

    private fun upsertServer(server: DiscoveredServer) {
        Log.i(TAG, "Resolved server: ${server.serviceName} -> ${server.host}:${server.port}")
        val filtered = _servers.value.filterNot { it.serviceName == server.serviceName }
        _servers.value = filtered + server
    }

    private fun removeServer(serviceName: String) {
        _servers.value = _servers.value.filterNot { it.serviceName == serviceName }
    }

    private fun unregisterServiceInfoCallback(serviceName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = serviceInfoCallbacks.remove(serviceName) ?: return
            try {
                nsdManager.unregisterServiceInfoCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering ServiceInfoCallback: ${e.message}")
            }
        }
    }

    private fun clearServiceInfoCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfoCallbacks.keys.toList().forEach { unregisterServiceInfoCallback(it) }
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("ExpressCast-NSD")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to release MulticastLock: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ExpressCast.NsdBrowser"
    }
}
